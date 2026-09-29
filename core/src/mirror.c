/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky
 *
 * Screen mirroring stream receiver. Packet format and key derivation as
 * documented by RPiPlay/UxPlay raop_rtp_mirror.c and mirror_buffer.c
 * (dsafa22, Florian Draschbacher, F. Duncanh; LGPL-2.1-or-later).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#include "mirror.h"

#include <errno.h>
#include <inttypes.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/resource.h>
#include <unistd.h>

#include "crypto.h"
#include "log.h"
#include "netutil.h"

/* Video timestamps count from the sender's boot without the NTP era offset that
 * its timing replies and audio sync packets carry. */
#define NTP_UNIX_EPOCH_OFFSET_SEC 2208988800ULL

enum {
    PKT_VIDEO = 0x00,
    PKT_CODEC = 0x01,
    PKT_HEARTBEAT = 0x02,
    PKT_REPORT = 0x05,
};

struct mirror_receiver {
    mirror_params_t p;
    int listen_fd;
    int stream_fd;
    wakeup_t wake;
    pthread_t thread;
    bool thread_started;
    _Atomic bool running;
    aes_ctr_t ctr;
    bool ctr_ready;
    uint8_t *payload;
    size_t payload_cap;
    video_codec_t codec;
    bool suspended;
    uint8_t config[4096];
};

void mirror_derive_keys(const uint8_t session_key[16], uint64_t stream_connection_id,
                        uint8_t key[16], uint8_t iv[16]) {
    char label[64];
    uint8_t hash[SHA512_SIZE];
    int n = snprintf(label, sizeof(label), "AirPlayStreamKey%" PRIu64, stream_connection_id);
    sha512_2(label, (size_t) n, session_key, 16, hash);
    memcpy(key, hash, 16);
    n = snprintf(label, sizeof(label), "AirPlayStreamIV%" PRIu64, stream_connection_id);
    sha512_2(label, (size_t) n, session_key, 16, hash);
    memcpy(iv, hash, 16);
    secure_zero(hash, sizeof(hash));
}

int mirror_avcc_to_annexb(uint8_t *data, size_t len, video_codec_t codec, bool *keyframe) {
    size_t off = 0;
    int count = 0;
    bool key = false;
    while (off < len) {
        if (len - off < 5) {
            return -1;
        }
        uint32_t nal_len = rd32be(data + off);
        if (nal_len == 0 || nal_len > len - off - 4) {
            return -1;
        }
        uint8_t hdr = data[off + 4];
        if (hdr & 0x80) {
            return -1; /* forbidden_zero_bit */
        }
        if (codec == VIDEO_CODEC_H265) {
            int type = (hdr >> 1) & 0x3f;
            if (type >= 16 && type <= 21) {
                key = true;
            }
        } else if ((hdr & 0x1f) == 5) {
            key = true;
        }
        data[off] = 0;
        data[off + 1] = 0;
        data[off + 2] = 0;
        data[off + 3] = 1;
        off += 4 + nal_len;
        count++;
    }
    if (keyframe) {
        *keyframe = key;
    }
    return count;
}

bool mirror_is_keyframe(const uint8_t *annexb, size_t len, video_codec_t codec) {
    for (size_t i = 0; i + 4 < len; i++) {
        if (annexb[i] == 0 && annexb[i + 1] == 0 && annexb[i + 2] == 1) {
            uint8_t hdr = annexb[i + 3];
            if (codec == VIDEO_CODEC_H265) {
                int type = (hdr >> 1) & 0x3f;
                if (type >= 16 && type <= 21) {
                    return true;
                }
            } else if ((hdr & 0x1f) == 5) {
                return true;
            }
            i += 2;
        }
    }
    return false;
}

static bool append_nal(uint8_t *out, size_t cap, size_t *pos, const uint8_t *nal, size_t len) {
    if (len == 0 || len > cap || *pos > cap - len - 4) {
        return false;
    }
    out[(*pos)++] = 0;
    out[(*pos)++] = 0;
    out[(*pos)++] = 0;
    out[(*pos)++] = 1;
    memcpy(out + *pos, nal, len);
    *pos += len;
    return true;
}

/* Parses the NAL unit arrays of an HEVCDecoderConfigurationRecord. */
static int parse_hvcc_arrays(const uint8_t *p, size_t len, uint8_t *out, size_t cap) {
    size_t pos = 0;
    size_t off = 0;
    if (len < 1) {
        return -1;
    }
    unsigned arrays = p[off++];
    for (unsigned a = 0; a < arrays; a++) {
        if (len - off < 3) {
            return -1;
        }
        unsigned type = p[off] & 0x3f;
        unsigned count = rd16be(p + off + 1);
        off += 3;
        for (unsigned i = 0; i < count; i++) {
            if (len - off < 2) {
                return -1;
            }
            size_t nal_len = rd16be(p + off);
            off += 2;
            if (nal_len == 0 || nal_len > len - off) {
                return -1;
            }
            if (type >= 32 && type <= 34) { /* VPS, SPS, PPS */
                if (!append_nal(out, cap, &pos, p + off, nal_len)) {
                    return -1;
                }
            }
            off += nal_len;
        }
    }
    return pos > 0 ? (int) pos : -1;
}

int mirror_parse_codec_config(const uint8_t *payload, size_t len, video_codec_t *codec,
                              uint8_t *out, size_t out_cap) {
    if (!payload || len < 8) {
        return -1;
    }
    if (memcmp(payload + 4, "hvc1", 4) == 0) {
        *codec = VIDEO_CODEC_H265;
        for (size_t i = 8; i + 4 <= len; i++) {
            if (memcmp(payload + i, "hvcC", 4) == 0) {
                size_t rec = i + 4;
                size_t rec_len = len - rec;
                if (i >= 4) {
                    uint32_t box = rd32be(payload + i - 4);
                    if (box >= 8 && box - 8 < rec_len) {
                        rec_len = box - 8;
                    }
                }
                /* 22 bytes of profile/tier/level fields precede numOfArrays */
                if (rec_len < 23) {
                    return -1;
                }
                return parse_hvcc_arrays(payload + rec + 22, rec_len - 22, out, out_cap);
            }
        }
        /* Some senders omit the box header; the arrays then start at a fixed offset. */
        if (len > 0x75) {
            const uint8_t *arrays = payload + 0x75;
            size_t arrays_len = len - 0x75;
            /* prepend the array count: VPS, SPS and PPS entries follow each other */
            uint8_t *buf = (uint8_t *) malloc(arrays_len + 1);
            if (!buf) {
                return -1;
            }
            buf[0] = 3;
            memcpy(buf + 1, arrays, arrays_len);
            int r = parse_hvcc_arrays(buf, arrays_len + 1, out, out_cap);
            free(buf);
            return r;
        }
        return -1;
    }

    /* avcC: version, profile, compatibility, level, length size, SPS count ... */
    *codec = VIDEO_CODEC_H264;
    if (payload[0] != 1 || len < 7) {
        return -1;
    }
    size_t pos = 0;
    size_t off = 5;
    unsigned sps_count = payload[off++] & 0x1f;
    for (unsigned i = 0; i < sps_count; i++) {
        if (len - off < 2) {
            return -1;
        }
        size_t n = rd16be(payload + off);
        off += 2;
        if (n == 0 || n > len - off || !append_nal(out, out_cap, &pos, payload + off, n)) {
            return -1;
        }
        off += n;
    }
    if (len - off < 1) {
        return -1;
    }
    unsigned pps_count = payload[off++];
    for (unsigned i = 0; i < pps_count; i++) {
        if (len - off < 2) {
            return -1;
        }
        size_t n = rd16be(payload + off);
        off += 2;
        if (n == 0 || n > len - off || !append_nal(out, out_cap, &pos, payload + off, n)) {
            return -1;
        }
        off += n;
    }
    return (sps_count > 0 && pps_count > 0) ? (int) pos : -1;
}

static bool ensure_payload(mirror_receiver_t *m, size_t size) {
    if (size <= m->payload_cap) {
        return true;
    }
    size_t cap = m->payload_cap ? m->payload_cap : 256 * 1024;
    while (cap < size) {
        cap *= 2;
    }
    uint8_t *p = (uint8_t *) realloc(m->payload, cap);
    if (!p) {
        return false;
    }
    m->payload = p;
    m->payload_cap = cap;
    return true;
}

static void configure_stream_socket(int fd) {
    int one = 1;
    setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &one, sizeof(one));
#ifdef TCP_KEEPIDLE
    int idle = 10;
    int interval = 5;
    int count = 3;
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPIDLE, &idle, sizeof(idle));
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPINTVL, &interval, sizeof(interval));
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPCNT, &count, sizeof(count));
#endif
}

static int accept_stream(mirror_receiver_t *m) {
    while (atomic_load(&m->running)) {
        struct pollfd pfds[2] = {
            { .fd = m->listen_fd, .events = POLLIN },
            { .fd = m->wake.rd, .events = POLLIN },
        };
        int r = poll(pfds, 2, -1);
        if (r < 0) {
            if (errno == EINTR) {
                continue;
            }
            return -1;
        }
        if (pfds[1].revents & POLLIN) {
            return -1;
        }
        if (!(pfds[0].revents & POLLIN)) {
            continue;
        }
        struct sockaddr_storage from;
        socklen_t from_len = sizeof(from);
        int fd = accept4(m->listen_fd, (struct sockaddr *) &from, &from_len, SOCK_CLOEXEC);
        if (fd < 0) {
            if (errno == EINTR || errno == EAGAIN || errno == ECONNABORTED) {
                continue;
            }
            return -1;
        }
        if (!net_same_host(&from, &m->p.peer)) {
            LOG_W(VIDEO, "rejected mirroring stream from a different host");
            close(fd);
            continue;
        }
        configure_stream_socket(fd);
        return fd;
    }
    return -1;
}

static void handle_codec_packet(mirror_receiver_t *m, const uint8_t *header, const uint8_t *payload, size_t len) {
    uint8_t option = header[6];
    bool suspending = option == 0x56 || option == 0x5e;
    bool resuming = option == 0x16 || option == 0x1e;
    if (suspending && !m->suspended) {
        m->suspended = true;
        LOG_I(VIDEO, "sender paused the video stream");
        if (m->p.ops->video_suspend) {
            m->p.ops->video_suspend(m->p.ops_ctx, true);
        }
    } else if (resuming && m->suspended) {
        m->suspended = false;
        LOG_I(VIDEO, "sender resumed the video stream");
        if (m->p.ops->video_suspend) {
            m->p.ops->video_suspend(m->p.ops_ctx, false);
        }
    }

    float fw = rdf32le(header + 56);
    float fh = rdf32le(header + 60);
    int width = (fw >= 16.0f && fw <= 8192.0f) ? (int) fw : 0;
    int height = (fh >= 16.0f && fh <= 8192.0f) ? (int) fh : 0;

    if (len == 0) {
        LOG_E(VIDEO, "sender offered a codec this receiver did not advertise");
        return;
    }
    video_codec_t codec = VIDEO_CODEC_NONE;
    int n = mirror_parse_codec_config(payload, len, &codec, m->config, sizeof(m->config));
    if (n < 0) {
        LOG_W(VIDEO, "ignoring malformed codec configuration (%zu bytes)", len);
        return;
    }
    if (m->codec != VIDEO_CODEC_NONE && m->codec != codec) {
        LOG_W(VIDEO, "codec changed mid-stream");
    }
    m->codec = codec;
    atomic_store_explicit(&g_stats.video_codec, (int32_t) codec, memory_order_relaxed);
    atomic_store_explicit(&g_stats.video_width, (uint32_t) width, memory_order_relaxed);
    atomic_store_explicit(&g_stats.video_height, (uint32_t) height, memory_order_relaxed);
    LOG_I(VIDEO, "stream format %s %dx%d", codec == VIDEO_CODEC_H265 ? "H.265" : "H.264", width, height);
    if (m->p.ops->video_config) {
        m->p.ops->video_config(m->p.ops_ctx, codec, m->config, (size_t) n, width, height);
    }
}

static void handle_video_packet(mirror_receiver_t *m, const uint8_t *header, uint8_t *payload, size_t len) {
    if (!m->ctr_ready || len == 0) {
        return;
    }
    /* The keystream runs continuously over all encrypted packets, so every packet is
     * decrypted even if it is dropped afterwards. */
    aes_ctr_xcrypt(&m->ctr, payload, payload, len);
    if (m->codec == VIDEO_CODEC_NONE) {
        return; /* no parameter sets yet */
    }
    bool keyframe = false;
    if (mirror_avcc_to_annexb(payload, len, m->codec, &keyframe) < 0) {
        stat_add(&g_stats.video_frames_dropped, 1);
        LOG_D(VIDEO, "dropping undecodable video packet (%zu bytes)", len);
        return;
    }
    uint64_t raw = rd64le(header + 8);
    uint64_t remote_ns = ntp_to_ns(raw) + NTP_UNIX_EPOCH_OFFSET_SEC * NS_PER_SEC;
    stat_add(&g_stats.video_frames_in, 1);
    stat_add(&g_stats.video_bytes_in, len);
    if (keyframe) {
        stat_add(&g_stats.video_keyframes_in, 1);
    }
    if (m->p.ops->video_frame) {
        m->p.ops->video_frame(m->p.ops_ctx, payload, len, remote_ns, keyframe);
    }
}

static void *mirror_thread(void *arg) {
    mirror_receiver_t *m = (mirror_receiver_t *) arg;
    setpriority(PRIO_PROCESS, 0, -8);

    m->stream_fd = accept_stream(m);
    if (m->stream_fd < 0) {
        goto out;
    }
    LOG_I(VIDEO, "mirroring stream connected");

    uint8_t header[MIRROR_HEADER_LEN];
    while (atomic_load(&m->running)) {
        int r = net_recv_exact(m->stream_fd, header, sizeof(header), &m->wake);
        if (r <= 0) {
            break;
        }
        uint32_t size = rd32le(header);
        if (size > MIRROR_MAX_PAYLOAD) {
            LOG_E(VIDEO, "invalid mirroring packet size %u", size);
            break;
        }
        if (size > 0) {
            if (!ensure_payload(m, size)) {
                LOG_E(VIDEO, "out of memory for a %u byte packet", size);
                break;
            }
            r = net_recv_exact(m->stream_fd, m->payload, size, &m->wake);
            if (r <= 0) {
                break;
            }
        }
        switch (header[4]) {
            case PKT_VIDEO:
                handle_video_packet(m, header, m->payload, size);
                break;
            case PKT_CODEC:
                handle_codec_packet(m, header, m->payload, size);
                break;
            case PKT_HEARTBEAT:
            case PKT_REPORT:
                break;
            default:
                LOG_D(VIDEO, "ignoring mirroring packet type %u", header[4]);
                break;
        }
    }

out:
    net_close(&m->stream_fd);
    if (atomic_load(&m->running)) {
        LOG_I(VIDEO, "mirroring stream ended by the sender");
        if (m->p.on_closed) {
            m->p.on_closed(m->p.closed_ctx);
        }
    }
    return NULL;
}

mirror_receiver_t *mirror_start(const mirror_params_t *params, uint16_t *port) {
    mirror_receiver_t *m = (mirror_receiver_t *) calloc(1, sizeof(mirror_receiver_t));
    if (!m) {
        return NULL;
    }
    m->p = *params;
    m->listen_fd = -1;
    m->stream_fd = -1;
    m->wake.rd = m->wake.wr = -1;

    uint8_t key[16];
    uint8_t iv[16];
    mirror_derive_keys(params->session_key, params->stream_connection_id, key, iv);
    m->ctr_ready = aes_ctr_init(&m->ctr, key, iv) == 0;
    secure_zero(key, sizeof(key));
    secure_zero(iv, sizeof(iv));

    struct sockaddr_storage peer = params->peer;
    net_normalize_addr(&peer, NULL);
    uint16_t p = 0;
    m->listen_fd = net_listen_tcp(peer.ss_family == AF_INET6 ? AF_INET6 : AF_INET, &p, 1);
    if (m->listen_fd < 0 || !m->ctr_ready || wakeup_init(&m->wake) != 0) {
        LOG_E(VIDEO, "cannot open the mirroring port");
        mirror_stop(m);
        return NULL;
    }
    atomic_store(&m->running, true);
    if (pthread_create(&m->thread, NULL, mirror_thread, m) != 0) {
        mirror_stop(m);
        return NULL;
    }
    m->thread_started = true;
    *port = p;
    LOG_D(VIDEO, "mirroring port %u", p);
    return m;
}

void mirror_stop(mirror_receiver_t *m) {
    if (!m) {
        return;
    }
    atomic_store(&m->running, false);
    if (m->thread_started) {
        wakeup_signal(&m->wake);
        pthread_join(m->thread, NULL);
    }
    net_close(&m->listen_fd);
    net_close(&m->stream_fd);
    wakeup_close(&m->wake);
    if (m->ctr_ready) {
        aes_ctr_free(&m->ctr);
    }
    free(m->payload);
    free(m);
}
