/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky and contributors
 *
 * AirPlay 2 buffered audio, see ap2_buffered.h. The wire format of the TCP stream: blocks of [length BE16 (counting these two
 * bytes)] [seq 4 bytes: the low 23 bits count] [timestamp 4] [ssrc 4, the codec] [ChaCha20-Poly1305 ciphertext and 16-byte tag] [8-byte
 * nonce]; the 8 bytes from the timestamp on are the associated data and the 12-byte nonce is four zero bytes and the 8 at the end.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#include "ap2_buffered.h"

#include <errno.h>
#include <poll.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <sys/resource.h>
#include <unistd.h>

#include "ap2_pair.h"
#include "audio_rtp.h"
#include "log.h"
#include "netutil.h"

#define SEQ_MASK 0x7fffffu
#define MAX_BLOCK 16384
#define MAX_QUEUED 4096
#define MAX_DEFERRED 4
#define LEAD_NS (250ull * NS_PER_MS)       /* how far ahead of its playing time a block is handed to the audio output */
#define RESTART_AFTER_NS (1500ull * NS_PER_MS)  /* behind by this much (a stall): start the clock again instead of racing */
#define BLOCK_OVERHEAD (12 + AP2_TAG_LEN + 8)

typedef struct {
    uint32_t seq;
    uint32_t ts;
    size_t len;
    uint8_t *data;
} entry_t;

typedef struct {
    bool in_use;
    uint32_t from;
    uint32_t until;
} deferred_t;

struct ap2_buffered {
    ap2_buffered_params_t p;
    size_t buffer_bytes;
    int listen_fd;
    int conn_fd;
    int control_fd;
    wakeup_t wake;
    pthread_t thread;
    bool thread_started;
    _Atomic bool running;

    pthread_mutex_t lock;              /* the queue and the flush state, shared with the RTSP thread */
    entry_t queue[MAX_QUEUED];
    size_t head;
    size_t count;
    size_t queued_bytes;
    bool play;
    bool anchor_valid;
    uint32_t anchor_rtp;
    bool imm_active;                   /* an immediate flush still discards blocks before imm_until */
    uint32_t imm_until;
    deferred_t deferred[MAX_DEFERRED];
    bool reanchor;                     /* the next block starts the playing clock */
    uint64_t base_ns;
    uint32_t base_ts;
    uint32_t last_ts;
    bool played;
    uint64_t blocks_in;
    bool ssrc_seen;
    uint32_t ssrc;

    uint8_t *rx;                       /* bytes of the TCP stream not yet made into a block */
    size_t rx_len;
};

bool ap2_seq_before(uint32_t a, uint32_t b) {
    return ((a - b) & SEQ_MASK) >= 0x400000u;
}

/* with b->lock held */
static bool is_flushed(const ap2_buffered_t *b, uint32_t seq) {
    if (b->imm_active && ap2_seq_before(seq, b->imm_until)) {
        return true;
    }
    for (int i = 0; i < MAX_DEFERRED; i++) {
        if (b->deferred[i].in_use && !ap2_seq_before(seq, b->deferred[i].from) && ap2_seq_before(seq, b->deferred[i].until)) {
            return true;
        }
    }
    return false;
}

/* with b->lock held: forgets finished flush ranges once a block at or past their end arrived */
static void retire_flushes(ap2_buffered_t *b, uint32_t seq) {
    if (b->imm_active && !ap2_seq_before(seq, b->imm_until)) {
        b->imm_active = false;
    }
    for (int i = 0; i < MAX_DEFERRED; i++) {
        if (b->deferred[i].in_use && !ap2_seq_before(seq, b->deferred[i].until)) {
            b->deferred[i].in_use = false;
        }
    }
}

static void drop_head(ap2_buffered_t *b) {
    entry_t *e = &b->queue[b->head];
    b->queued_bytes -= e->len;
    free(e->data);
    e->data = NULL;
    b->head = (b->head + 1) % MAX_QUEUED;
    b->count--;
}

/* with b->lock held: removes the queued blocks that a flush covers */
static void purge_flushed(ap2_buffered_t *b) {
    size_t keep = 0;
    size_t n = b->count;
    entry_t *kept = (entry_t *) calloc(n ? n : 1, sizeof(entry_t));
    if (!kept) {
        return;
    }
    for (size_t i = 0; i < n; i++) {
        entry_t *e = &b->queue[(b->head + i) % MAX_QUEUED];
        if (is_flushed(b, e->seq)) {
            b->queued_bytes -= e->len;
            free(e->data);
        } else {
            kept[keep++] = *e;
        }
    }
    for (size_t i = 0; i < n; i++) {
        b->queue[(b->head + i) % MAX_QUEUED].data = NULL;
    }
    b->head = 0;
    b->count = keep;
    for (size_t i = 0; i < keep; i++) {
        b->queue[i] = kept[i];
    }
    free(kept);
}

void ap2_buffered_rate(ap2_buffered_t *b, bool play, uint32_t anchor_rtp, bool have_anchor) {
    if (!b) {
        return;
    }
    pthread_mutex_lock(&b->lock);
    b->play = play;
    if (have_anchor) {
        b->anchor_valid = true;
        b->anchor_rtp = anchor_rtp;
    }
    if (play) {
        b->reanchor = true;
    }
    pthread_mutex_unlock(&b->lock);
    wakeup_signal(&b->wake);
}

void ap2_buffered_flush(ap2_buffered_t *b, bool have_from, uint32_t from_seq, uint32_t until_seq) {
    if (!b) {
        return;
    }
    pthread_mutex_lock(&b->lock);
    if (!have_from) {
        b->imm_active = true;
        b->imm_until = until_seq & SEQ_MASK;
        b->play = false;
        b->anchor_valid = false;
        b->reanchor = true;
    } else {
        int slot = -1;
        for (int i = 0; i < MAX_DEFERRED && slot < 0; i++) {
            if (!b->deferred[i].in_use) {
                slot = i;
            }
        }
        if (slot < 0) {
            slot = 0; /* all taken: the oldest request is replaced, a sender does not keep more than a few */
        }
        b->deferred[slot].in_use = true;
        b->deferred[slot].from = from_seq & SEQ_MASK;
        b->deferred[slot].until = until_seq & SEQ_MASK;
    }
    purge_flushed(b);
    pthread_mutex_unlock(&b->lock);
    if (b->p.ops->audio_flush && !have_from) {
        b->p.ops->audio_flush(b->p.ops_ctx);
    }
    wakeup_signal(&b->wake);
}

bool ap2_buffered_position(ap2_buffered_t *b, uint32_t *rtp) {
    if (!b) {
        return false;
    }
    pthread_mutex_lock(&b->lock);
    bool ok = b->played;
    if (ok && rtp) {
        *rtp = b->last_ts;
    }
    pthread_mutex_unlock(&b->lock);
    return ok;
}

/* Opens one block and queues it. Returns false when the stream is broken and the connection should end. */
static bool accept_block(ap2_buffered_t *b, const uint8_t *pkt, size_t len) {
    if (len < BLOCK_OVERHEAD + 1) {
        return false;
    }
    uint32_t seq = rd32be(pkt) & SEQ_MASK;
    uint32_t ts = rd32be(pkt + 4);
    uint32_t ssrc = rd32be(pkt + 8);
    size_t clen = len - BLOCK_OVERHEAD;
    uint8_t nonce[12] = { 0 };
    memcpy(nonce + 4, pkt + len - 8, 8);
    uint8_t *plain = (uint8_t *) malloc(clen);
    if (!plain) {
        return false;
    }
    if (ap2_aead_open(b->p.key, nonce, pkt + 4, 8, pkt + 12, clen, pkt + 12 + clen, plain) != 0) {
        stat_add(&g_stats.audio_dropped_aead, 1);
        free(plain);
        return true; /* one bad block does not end the stream */
    }
    pthread_mutex_lock(&b->lock);
    if (!b->ssrc_seen || b->ssrc != ssrc) {
        LOG_I(AUDIO, "AirPlay 2 buffered audio: block codec id 0x%08x", ssrc);
        b->ssrc_seen = true;
        b->ssrc = ssrc;
    }
    b->blocks_in++;
    bool flushed = is_flushed(b, seq);
    retire_flushes(b, seq);
    if (flushed || clen > AUDIO_SLOT_SIZE || b->count >= MAX_QUEUED) {
        pthread_mutex_unlock(&b->lock);
        free(plain);
        return true;
    }
    entry_t *e = &b->queue[(b->head + b->count) % MAX_QUEUED];
    e->seq = seq;
    e->ts = ts;
    e->len = clen;
    e->data = plain;
    b->count++;
    b->queued_bytes += clen;
    pthread_mutex_unlock(&b->lock);
    return true;
}

static void close_conn(ap2_buffered_t *b) {
    net_close(&b->conn_fd);
    b->rx_len = 0;
}

/* Reads what the sender has sent and makes blocks of it. */
static void read_stream(ap2_buffered_t *b) {
    uint8_t buf[8192];
    ssize_t n = recv(b->conn_fd, buf, sizeof(buf), MSG_DONTWAIT);
    if (n == 0 || (n < 0 && errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR)) {
        LOG_I(AUDIO, "AirPlay 2 buffered audio connection closed");
        close_conn(b);
        return;
    }
    if (n < 0) {
        return;
    }
    if (b->rx_len + (size_t) n > MAX_BLOCK + 8192) {
        close_conn(b);
        return;
    }
    memcpy(b->rx + b->rx_len, buf, (size_t) n);
    b->rx_len += (size_t) n;
    for (;;) {
        if (b->rx_len < 2) {
            return;
        }
        size_t total = rd16be(b->rx);
        if (total < 2 + BLOCK_OVERHEAD || total > MAX_BLOCK) {
            LOG_W(AUDIO, "AirPlay 2 buffered audio: a block of %zu bytes, closing", total);
            close_conn(b);
            return;
        }
        if (b->rx_len < total) {
            return;
        }
        bool ok = accept_block(b, b->rx + 2, total - 2);
        memmove(b->rx, b->rx + total, b->rx_len - total);
        b->rx_len -= total;
        if (!ok) {
            close_conn(b);
            return;
        }
    }
}

/* Hands the blocks that are due to the audio output. Returns the nanoseconds until the next one is due, or 0 for "nothing pending". */
static uint64_t deliver_due(ap2_buffered_t *b) {
    uint64_t next = 0;
    for (;;) {
        pthread_mutex_lock(&b->lock);
        if (!b->play || b->count == 0) {
            pthread_mutex_unlock(&b->lock);
            return 0;
        }
        entry_t *e = &b->queue[b->head];
        /* a block that ends before the anchor was played already (the sender buffered it before a seek or a pause) */
        if (b->anchor_valid && (int32_t) (e->ts + (uint32_t) b->p.samples_per_frame - b->anchor_rtp) <= 0) {
            drop_head(b);
            pthread_mutex_unlock(&b->lock);
            continue;
        }
        uint64_t now = time_mono_ns();
        if (b->reanchor) {
            b->reanchor = false;
            b->base_ns = now;
            b->base_ts = b->anchor_valid && (int32_t) (b->anchor_rtp - e->ts) > 0 ? b->anchor_rtp : e->ts;
        }
        int64_t offset = (int64_t) (int32_t) (e->ts - b->base_ts) * (int64_t) NS_PER_SEC / b->p.sample_rate;
        int64_t due_offset = offset - (int64_t) LEAD_NS;
        int64_t due = (int64_t) b->base_ns + (due_offset > 0 ? due_offset : 0);
        if (now > (uint64_t) due + RESTART_AFTER_NS) {
            /* the stream stalled or the sender jumped: play on from here instead of delivering a burst */
            b->base_ns = now;
            b->base_ts = e->ts;
            due = (int64_t) now;
        }
        if ((int64_t) now < due) {
            next = (uint64_t) due - now;
            pthread_mutex_unlock(&b->lock);
            return next;
        }
        uint8_t *data = e->data;
        size_t len = e->len;
        uint32_t ts = e->ts;
        e->data = NULL;
        b->queued_bytes -= len;
        b->head = (b->head + 1) % MAX_QUEUED;
        b->count--;
        b->last_ts = ts;
        b->played = true;
        pthread_mutex_unlock(&b->lock);
        stat_add(&g_stats.audio_packets_in, 1);
        if (b->p.ops->audio_frame) {
            b->p.ops->audio_frame(b->p.ops_ctx, data, len, ts, 0);
        }
        free(data);
    }
}

static void *buffered_thread(void *arg) {
    ap2_buffered_t *b = (ap2_buffered_t *) arg;
    setpriority(PRIO_PROCESS, 0, -16);
    int timeout_ms = -1;
    while (atomic_load(&b->running)) {
        struct pollfd pfds[4];
        int n = 0;
        pfds[n++] = (struct pollfd) { .fd = b->wake.rd, .events = POLLIN };
        pfds[n++] = (struct pollfd) { .fd = b->control_fd, .events = POLLIN };
        pthread_mutex_lock(&b->lock);
        bool room = b->queued_bytes < b->buffer_bytes && b->count < MAX_QUEUED;
        pthread_mutex_unlock(&b->lock);
        int conn_idx = -1;
        int listen_idx = -1;
        if (b->conn_fd >= 0) {
            if (room) {
                conn_idx = n;
                pfds[n++] = (struct pollfd) { .fd = b->conn_fd, .events = POLLIN };
            }
        } else if (b->listen_fd >= 0) {
            listen_idx = n;
            pfds[n++] = (struct pollfd) { .fd = b->listen_fd, .events = POLLIN };
        }
        int r = poll(pfds, (nfds_t) n, room ? timeout_ms : (timeout_ms < 0 || timeout_ms > 20 ? 20 : timeout_ms));
        if (r < 0 && errno != EINTR) {
            LOG_E(AUDIO, "poll failed: %d", errno);
            break;
        }
        if (r > 0) {
            if (pfds[0].revents & POLLIN) {
                wakeup_drain(&b->wake);
            }
            if (pfds[1].revents & POLLIN) {
                uint8_t junk[512];
                while (recv(b->control_fd, junk, sizeof(junk), MSG_DONTWAIT) > 0) {
                }
            }
            if (listen_idx >= 0 && (pfds[listen_idx].revents & POLLIN)) {
                struct sockaddr_storage from;
                socklen_t from_len = sizeof(from);
                int fd = accept(b->listen_fd, (struct sockaddr *) &from, &from_len);
                if (fd >= 0) {
                    if (net_same_host(&from, &b->p.peer)) {
                        net_set_nonblocking(fd, true);
                        b->conn_fd = fd;
                        LOG_I(AUDIO, "AirPlay 2 buffered audio connection opened");
                    } else {
                        close(fd);
                    }
                }
            }
            if (conn_idx >= 0 && (pfds[conn_idx].revents & (POLLIN | POLLHUP | POLLERR))) {
                read_stream(b);
            }
        }
        uint64_t wait_ns = deliver_due(b);
        timeout_ms = wait_ns ? (int) (wait_ns / NS_PER_MS) + 1 : -1;
    }
    return NULL;
}

ap2_buffered_t *ap2_buffered_start(const ap2_buffered_params_t *params, size_t buffer_bytes, uint16_t *data_port,
                                   uint16_t *control_port) {
    ap2_buffered_t *b = (ap2_buffered_t *) calloc(1, sizeof(*b));
    if (!b) {
        return NULL;
    }
    b->p = *params;
    b->buffer_bytes = buffer_bytes;
    b->listen_fd = b->conn_fd = b->control_fd = -1;
    b->wake.rd = b->wake.wr = -1;
    b->reanchor = true;
    b->rx = (uint8_t *) malloc(MAX_BLOCK + 8192);
    struct sockaddr_storage peer = params->peer;
    net_normalize_addr(&peer, NULL);
    int family = peer.ss_family == AF_INET6 ? AF_INET6 : AF_INET;
    uint16_t dport = 0;
    uint16_t cport = 0;
    pthread_mutex_init(&b->lock, NULL);
    b->listen_fd = net_listen_tcp(family, &dport, 2);
    b->control_fd = net_bind_udp(family, &cport);
    if (!b->rx || b->listen_fd < 0 || b->control_fd < 0 || wakeup_init(&b->wake) != 0) {
        LOG_E(AUDIO, "cannot open the buffered audio ports");
        ap2_buffered_stop(b);
        return NULL;
    }
    net_set_nonblocking(b->listen_fd, true);
    atomic_store(&b->running, true);
    if (pthread_create(&b->thread, NULL, buffered_thread, b) != 0) {
        ap2_buffered_stop(b);
        return NULL;
    }
    b->thread_started = true;
    *data_port = dport;
    *control_port = cport;
    return b;
}

void ap2_buffered_stop(ap2_buffered_t *b) {
    if (!b) {
        return;
    }
    atomic_store(&b->running, false);
    if (b->thread_started) {
        wakeup_signal(&b->wake);
        pthread_join(b->thread, NULL);
    }
    net_close(&b->conn_fd);
    net_close(&b->listen_fd);
    net_close(&b->control_fd);
    wakeup_close(&b->wake);
    while (b->count > 0) {
        drop_head(b);
    }
    pthread_mutex_destroy(&b->lock);
    free(b->rx);
    free(b);
}
