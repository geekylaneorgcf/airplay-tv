/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky
 *
 * RAOP audio receiver: RTP over UDP with AES-CBC payload encryption, sync and
 * retransmission on the control channel. Packet formats as documented by
 * shairplay/UxPlay raop_rtp.c and raop_buffer.c (LGPL-2.1-or-later).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#include "audio_rtp.h"

#include <errno.h>
#include <poll.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <sys/resource.h>

#include "crypto.h"
#include "log.h"
#include "netutil.h"
#include "ntp.h"

#define RTP_HEADER_LEN 12
#define FLUSH_NONE (-2)
#define FLUSH_ANY (-1)

struct audio_receiver {
    audio_params_t p;
    int data_fd;
    int control_fd;
    wakeup_t wake;
    pthread_t thread;
    bool thread_started;
    _Atomic bool running;
    _Atomic int flush_request;
    aes_cbc_t cbc;
    bool cbc_ready;
    audio_jitter_t jitter;

    bool have_sync;
    uint32_t sync_rtp;
    uint64_t sync_ns;

    struct sockaddr_storage control_addr;
    socklen_t control_addr_len;
    bool have_control_addr;
    uint16_t resend_counter;
    uint64_t last_resend_ns;
};

/* ------------------------------------------------------------------------- */
/* jitter buffer                                                              */

int audio_jitter_init(audio_jitter_t *j, uint64_t max_wait_ns) {
    memset(j, 0, sizeof(*j));
    j->slots = (audio_slot_t *) calloc(AUDIO_SLOTS, sizeof(audio_slot_t));
    j->max_wait_ns = max_wait_ns;
    return j->slots ? 0 : -1;
}

void audio_jitter_free(audio_jitter_t *j) {
    free(j->slots);
    j->slots = NULL;
}

void audio_jitter_reset(audio_jitter_t *j, int next_seq) {
    for (int i = 0; i < AUDIO_SLOTS; i++) {
        j->slots[i].filled = false;
    }
    j->gap_since_ns = 0;
    if (next_seq >= 0 && next_seq <= 0xffff) {
        j->have_next = true;
        j->next_seq = (uint16_t) next_seq;
        j->highest_seq = (uint16_t) (next_seq - 1);
    } else {
        j->have_next = false;
    }
}

bool audio_jitter_put(audio_jitter_t *j, uint16_t seq, uint32_t rtp_ts, const uint8_t *payload, size_t len) {
    if (len == 0 || len > AUDIO_SLOT_SIZE) {
        return false;
    }
    if (!j->have_next) {
        j->have_next = true;
        j->next_seq = seq;
        j->highest_seq = (uint16_t) (seq - 1);
    }
    int16_t ahead = (int16_t) (uint16_t) (seq - j->next_seq);
    if (ahead < 0) {
        return false; /* already played or skipped */
    }
    if (ahead >= AUDIO_SLOTS) {
        /* the sender jumped far ahead (new stream after a pause): start over */
        audio_jitter_reset(j, seq);
    }
    audio_slot_t *slot = &j->slots[seq % AUDIO_SLOTS];
    if (slot->filled && slot->seq == seq) {
        return false; /* redundant copy */
    }
    slot->filled = true;
    slot->seq = seq;
    slot->rtp_ts = rtp_ts;
    slot->len = (uint16_t) len;
    memcpy(slot->data, payload, len);
    if ((int16_t) (uint16_t) (seq - j->highest_seq) > 0) {
        j->highest_seq = seq;
    }
    return true;
}

uint64_t audio_jitter_drain(audio_jitter_t *j, uint64_t now_ns, audio_deliver_fn deliver, audio_resend_fn resend, void *ctx) {
    if (!j->have_next) {
        return 0;
    }
    for (;;) {
        audio_slot_t *slot = &j->slots[j->next_seq % AUDIO_SLOTS];
        if (slot->filled && slot->seq == j->next_seq) {
            slot->filled = false;
            deliver(ctx, slot->data, slot->len, slot->seq, slot->rtp_ts);
            j->next_seq++;
            j->gap_since_ns = 0;
            continue;
        }
        /* next packet is missing: is anything newer waiting behind it? */
        int16_t pending = (int16_t) (uint16_t) (j->highest_seq - j->next_seq);
        if (pending <= 0) {
            j->gap_since_ns = 0;
            return 0;
        }
        if (j->gap_since_ns == 0) {
            j->gap_since_ns = now_ns;
            if (resend) {
                uint16_t count = 0;
                for (uint16_t s = j->next_seq; (int16_t) (uint16_t) (j->highest_seq - s) > 0; s++) {
                    audio_slot_t *m = &j->slots[s % AUDIO_SLOTS];
                    if (m->filled && m->seq == s) {
                        break;
                    }
                    count++;
                }
                resend(ctx, j->next_seq, count);
            }
        }
        uint64_t waited = now_ns - j->gap_since_ns;
        if (waited < j->max_wait_ns) {
            return j->max_wait_ns - waited;
        }
        /* give up on the missing run and continue with what we have */
        while (!(j->slots[j->next_seq % AUDIO_SLOTS].filled &&
                 j->slots[j->next_seq % AUDIO_SLOTS].seq == j->next_seq) &&
               (int16_t) (uint16_t) (j->highest_seq - j->next_seq) > 0) {
            j->next_seq++;
            j->lost++;
            stat_add(&g_stats.audio_packets_lost, 1);
        }
        j->gap_since_ns = 0;
    }
}

/* ------------------------------------------------------------------------- */
/* receiver                                                                   */

static uint64_t remote_time_ns(const audio_receiver_t *a, uint32_t rtp_ts) {
    if (!a->have_sync || a->p.format.sample_rate <= 0) {
        return 0;
    }
    int32_t delta = (int32_t) (rtp_ts - a->sync_rtp);
    int64_t offset = (int64_t) delta * (int64_t) NS_PER_SEC / a->p.format.sample_rate;
    return (uint64_t) ((int64_t) a->sync_ns + offset);
}

static void deliver_packet(void *ctx, const uint8_t *data, size_t len, uint16_t seq, uint32_t rtp_ts) {
    (void) seq;
    audio_receiver_t *a = (audio_receiver_t *) ctx;
    stat_add(&g_stats.audio_packets_in, 1);
    if (a->p.ops->audio_frame) {
        a->p.ops->audio_frame(a->p.ops_ctx, data, len, rtp_ts, remote_time_ns(a, rtp_ts));
    }
}

static void request_resend(void *ctx, uint16_t first, uint16_t count) {
    audio_receiver_t *a = (audio_receiver_t *) ctx;
    if (count == 0 || (!a->have_control_addr && a->p.peer_control_port == 0)) {
        return;
    }
    uint64_t now = time_mono_ns();
    if (now - a->last_resend_ns < 5 * NS_PER_MS) {
        return;
    }
    a->last_resend_ns = now;
    uint8_t req[8];
    req[0] = 0x80;
    req[1] = 0x55 | 0x80;
    wr16be(req + 2, a->resend_counter++);
    wr16be(req + 4, first);
    wr16be(req + 6, count);
    struct sockaddr_storage to;
    socklen_t to_len;
    if (a->have_control_addr) {
        to = a->control_addr;
        to_len = a->control_addr_len;
    } else {
        to = a->p.peer;
        net_normalize_addr(&to, NULL);
        net_set_port(&to, a->p.peer_control_port);
        to_len = to.ss_family == AF_INET6 ? sizeof(struct sockaddr_in6) : sizeof(struct sockaddr_in);
    }
    sendto(a->control_fd, req, sizeof(req), 0, (struct sockaddr *) &to, to_len);
    LOG_D(AUDIO, "requested %u missing packet(s) from %u", count, first);
}

static bool is_empty_packet(const audio_receiver_t *a, const uint8_t *pkt, size_t len) {
    static const uint8_t kNoData[4] = { 0x00, 0x68, 0x34, 0x00 };
    if (len == RTP_HEADER_LEN) {
        return true;
    }
    if (len == RTP_HEADER_LEN + 4 && memcmp(pkt + RTP_HEADER_LEN, kNoData, 4) == 0) {
        return true;
    }
    /* ALAC streams start with 32-byte format packets that carry no audio */
    return a->p.format.ct == AUDIO_CT_ALAC && len == RTP_HEADER_LEN + 32;
}

static void handle_rtp(audio_receiver_t *a, uint8_t *pkt, size_t len) {
    if (len < RTP_HEADER_LEN || (pkt[0] & 0xc0) != 0x80 || is_empty_packet(a, pkt, len)) {
        return;
    }
    uint16_t seq = rd16be(pkt + 2);
    uint32_t ts = rd32be(pkt + 4);
    uint8_t *payload = pkt + RTP_HEADER_LEN;
    size_t payload_len = len - RTP_HEADER_LEN;
    if (payload_len > AUDIO_SLOT_SIZE) {
        return;
    }
    if (a->cbc_ready) {
        aes_cbc_decrypt_packet(&a->cbc, payload, payload_len);
    }
    audio_jitter_put(&a->jitter, seq, ts, payload, payload_len);
}

static void handle_control(audio_receiver_t *a, uint8_t *pkt, size_t len,
                           const struct sockaddr_storage *from, socklen_t from_len) {
    if (len < 4) {
        return;
    }
    if (!a->have_control_addr) {
        a->control_addr = *from;
        a->control_addr_len = from_len;
        a->have_control_addr = true;
    }
    uint8_t type = pkt[1] & 0x7f;
    if (type == 0x54 && len >= 20) {
        /* sync: RTP time of the next packet and the sender's NTP time for it */
        a->sync_rtp = rd32be(pkt + 4);
        a->sync_ns = ntp_to_ns(rd64be(pkt + 8));
        if (!a->have_sync) {
            LOG_D(AUDIO, "first audio sync packet");
        }
        a->have_sync = true;
    } else if (type == 0x56 && len >= 4 + RTP_HEADER_LEN) {
        handle_rtp(a, pkt + 4, len - 4);
    }
}

static void *audio_thread(void *arg) {
    audio_receiver_t *a = (audio_receiver_t *) arg;
    setpriority(PRIO_PROCESS, 0, -16);
    uint8_t buf[AUDIO_SLOT_SIZE + RTP_HEADER_LEN + 64];
    int timeout_ms = -1;

    while (atomic_load(&a->running)) {
        struct pollfd pfds[3] = {
            { .fd = a->data_fd, .events = POLLIN },
            { .fd = a->control_fd, .events = POLLIN },
            { .fd = a->wake.rd, .events = POLLIN },
        };
        int r = poll(pfds, 3, timeout_ms);
        if (r < 0 && errno != EINTR) {
            LOG_E(AUDIO, "poll failed: %d", errno);
            break;
        }
        if (pfds[2].revents & POLLIN) {
            wakeup_drain(&a->wake);
        }
        int flush = atomic_exchange(&a->flush_request, FLUSH_NONE);
        if (flush != FLUSH_NONE) {
            audio_jitter_reset(&a->jitter, flush);
            if (a->p.ops->audio_flush) {
                a->p.ops->audio_flush(a->p.ops_ctx);
            }
        }
        for (int i = 0; i < 2; i++) {
            if (!(pfds[i].revents & POLLIN)) {
                continue;
            }
            /* drain everything that is queued on this socket */
            for (int n = 0; n < 64; n++) {
                struct sockaddr_storage from;
                socklen_t from_len = sizeof(from);
                ssize_t len = recvfrom(pfds[i].fd, buf, sizeof(buf), MSG_DONTWAIT,
                                       (struct sockaddr *) &from, &from_len);
                if (len <= 0) {
                    break;
                }
                if (!net_same_host(&from, &a->p.peer)) {
                    continue;
                }
                if (i == 0) {
                    handle_rtp(a, buf, (size_t) len);
                } else {
                    handle_control(a, buf, (size_t) len, &from, from_len);
                }
            }
        }
        uint64_t wait_ns = audio_jitter_drain(&a->jitter, time_mono_ns(), deliver_packet, request_resend, a);
        timeout_ms = wait_ns ? (int) (wait_ns / NS_PER_MS) + 1 : -1;
    }
    return NULL;
}

audio_receiver_t *audio_start(const audio_params_t *params, uint16_t *data_port, uint16_t *control_port) {
    audio_receiver_t *a = (audio_receiver_t *) calloc(1, sizeof(audio_receiver_t));
    if (!a) {
        return NULL;
    }
    a->p = *params;
    a->data_fd = a->control_fd = -1;
    a->wake.rd = a->wake.wr = -1;
    atomic_store(&a->flush_request, FLUSH_NONE);

    /* Mirroring audio is sent with redundancy, so waiting long for a retransmission
     * only adds latency. Music streams are buffered by the sender and can wait. */
    uint64_t max_wait = params->format.ct == AUDIO_CT_AAC_ELD ? 30 * NS_PER_MS : 250 * NS_PER_MS;
    if (audio_jitter_init(&a->jitter, max_wait) != 0) {
        free(a);
        return NULL;
    }
    a->cbc_ready = aes_cbc_init_decrypt(&a->cbc, params->key, params->iv) == 0;

    struct sockaddr_storage peer = params->peer;
    net_normalize_addr(&peer, NULL);
    int family = peer.ss_family == AF_INET6 ? AF_INET6 : AF_INET;
    uint16_t dport = 0;
    uint16_t cport = 0;
    a->data_fd = net_bind_udp(family, &dport);
    a->control_fd = net_bind_udp(family, &cport);
    if (a->data_fd < 0 || a->control_fd < 0 || !a->cbc_ready || wakeup_init(&a->wake) != 0) {
        LOG_E(AUDIO, "cannot open audio ports");
        audio_stop(a);
        return NULL;
    }
    int rcvbuf = 256 * 1024;
    setsockopt(a->data_fd, SOL_SOCKET, SO_RCVBUF, &rcvbuf, sizeof(rcvbuf));

    atomic_store(&a->running, true);
    if (pthread_create(&a->thread, NULL, audio_thread, a) != 0) {
        audio_stop(a);
        return NULL;
    }
    a->thread_started = true;
    *data_port = dport;
    *control_port = cport;
    return a;
}

void audio_stop(audio_receiver_t *a) {
    if (!a) {
        return;
    }
    atomic_store(&a->running, false);
    if (a->thread_started) {
        wakeup_signal(&a->wake);
        pthread_join(a->thread, NULL);
    }
    net_close(&a->data_fd);
    net_close(&a->control_fd);
    wakeup_close(&a->wake);
    if (a->cbc_ready) {
        aes_cbc_free(&a->cbc);
    }
    audio_jitter_free(&a->jitter);
    free(a);
}

void audio_request_flush(audio_receiver_t *a, int next_seq) {
    if (!a) {
        return;
    }
    atomic_store(&a->flush_request, next_seq >= 0 && next_seq <= 0xffff ? next_seq : FLUSH_ANY);
    wakeup_signal(&a->wake);
}
