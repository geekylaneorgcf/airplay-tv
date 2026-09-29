/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#include "ntp.h"

#include <errno.h>
#include <poll.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <sys/socket.h>

#include "log.h"
#include "media.h"
#include "netutil.h"

#define SAMPLES 8
#define BURST_INTERVAL_MS 250
#define STEADY_INTERVAL_MS 3000
#define RESPONSE_TIMEOUT_MS 1000

typedef struct {
    int64_t offset_ns;
    int64_t delay_ns;
    bool valid;
} sample_t;

struct ntp_client {
    int fd;
    struct sockaddr_storage remote;
    socklen_t remote_len;
    bool send_requests;
    wakeup_t wake;
    pthread_t thread;
    bool thread_started;
    _Atomic bool running;
    _Atomic int64_t offset_ns;
    _Atomic bool have_offset;
    sample_t samples[SAMPLES];
    unsigned next_sample;
};

uint64_t ntp_to_ns(uint64_t ntp) {
    uint64_t secs = ntp >> 32;
    uint64_t frac = ntp & 0xffffffffULL;
    return secs * NS_PER_SEC + ((frac * NS_PER_SEC) >> 32);
}

uint64_t ns_to_ntp(uint64_t ns) {
    uint64_t secs = ns / NS_PER_SEC;
    uint64_t rem = ns % NS_PER_SEC;
    return (secs << 32) | ((rem << 32) / NS_PER_SEC);
}

static void update_estimate(ntp_client_t *n) {
    /* Use the offset of the sample with the smallest round trip (RFC 5905 clock filter idea). */
    const sample_t *best = NULL;
    for (unsigned i = 0; i < SAMPLES; i++) {
        if (n->samples[i].valid && (!best || n->samples[i].delay_ns < best->delay_ns)) {
            best = &n->samples[i];
        }
    }
    if (!best) {
        return;
    }
    int64_t offset = best->offset_ns;
    if (atomic_load(&n->have_offset)) {
        /* smooth small corrections, jump on large ones (e.g. sender clock step) */
        int64_t prev = atomic_load(&n->offset_ns);
        int64_t diff = offset - prev;
        if (diff > -50 * (int64_t) NS_PER_MS && diff < 50 * (int64_t) NS_PER_MS) {
            offset = prev + diff / 4;
        }
    }
    atomic_store(&n->offset_ns, offset);
    atomic_store(&n->have_offset, true);
    atomic_store_explicit(&g_stats.clock_offset_us, offset / 1000, memory_order_relaxed);
    atomic_store_explicit(&g_stats.clock_synced, 1, memory_order_relaxed);
}

static void handle_response(ntp_client_t *n, const uint8_t *p, ssize_t len, uint64_t t4) {
    if (len < 32 || (p[1] & 0x7f) != 0x53) {
        return;
    }
    uint64_t t1 = ntp_to_ns(rd64be(p + 8));   /* our transmit time, echoed */
    uint64_t t2 = ntp_to_ns(rd64be(p + 16));  /* sender receive time */
    uint64_t t3 = ntp_to_ns(rd64be(p + 24));  /* sender transmit time */
    if (t1 == 0 || t1 > t4 || t4 - t1 > 2 * NS_PER_SEC) {
        return; /* not an answer to a recent request of ours */
    }
    int64_t delay = (int64_t) (t4 - t1) - (int64_t) (t3 - t2);
    if (delay < 0) {
        delay = 0;
    }
    int64_t offset = ((int64_t) (t2 - t1) + (int64_t) (t3 - t4)) / 2;
    sample_t *s = &n->samples[n->next_sample++ % SAMPLES];
    s->offset_ns = offset;
    s->delay_ns = delay;
    s->valid = true;
    update_estimate(n);
}

static void *ntp_thread(void *arg) {
    ntp_client_t *n = (ntp_client_t *) arg;
    unsigned sent = 0;
    uint64_t next_send = time_mono_ns();

    while (atomic_load(&n->running)) {
        uint64_t now = time_mono_ns();
        if (n->send_requests && now >= next_send) {
            uint8_t req[32] = { 0x80, 0xd2, 0x00, 0x07 };
            wr64be(req + 24, ns_to_ntp(now));
            if (sendto(n->fd, req, sizeof(req), 0, (struct sockaddr *) &n->remote, n->remote_len) < 0) {
                LOG_D(NETWORK, "timing request failed: %d", errno);
            }
            sent++;
            next_send = now + (sent < SAMPLES ? BURST_INTERVAL_MS : STEADY_INTERVAL_MS) * NS_PER_MS;
        }

        int timeout_ms = -1;
        if (n->send_requests) {
            uint64_t t = time_mono_ns();
            timeout_ms = next_send > t ? (int) ((next_send - t) / NS_PER_MS) + 1 : 0;
        }
        struct pollfd pfds[2] = {
            { .fd = n->fd, .events = POLLIN },
            { .fd = n->wake.rd, .events = POLLIN },
        };
        int r = poll(pfds, 2, timeout_ms);
        if (r < 0 && errno != EINTR) {
            break;
        }
        if (pfds[1].revents & POLLIN) {
            wakeup_drain(&n->wake);
        }
        if (pfds[0].revents & POLLIN) {
            uint8_t buf[128];
            struct sockaddr_storage from;
            socklen_t from_len = sizeof(from);
            ssize_t len = recvfrom(n->fd, buf, sizeof(buf), 0, (struct sockaddr *) &from, &from_len);
            uint64_t t4 = time_mono_ns();
            if (len > 0 && net_same_host(&from, &n->remote)) {
                handle_response(n, buf, len, t4);
            }
        }
    }
    return NULL;
}

ntp_client_t *ntp_client_start(const struct sockaddr_storage *remote, uint16_t remote_port, uint16_t *local_port) {
    ntp_client_t *n = (ntp_client_t *) calloc(1, sizeof(ntp_client_t));
    if (!n) {
        return NULL;
    }
    n->fd = -1;
    n->wake.rd = n->wake.wr = -1;
    n->remote = *remote;
    net_normalize_addr(&n->remote, NULL);
    n->remote_len = n->remote.ss_family == AF_INET6 ? sizeof(struct sockaddr_in6) : sizeof(struct sockaddr_in);
    net_set_port(&n->remote, remote_port);
    n->send_requests = remote_port != 0;

    uint16_t port = 0;
    n->fd = net_bind_udp(n->remote.ss_family, &port);
    if (n->fd < 0 || wakeup_init(&n->wake) != 0) {
        ntp_client_stop(n);
        return NULL;
    }
    atomic_store(&n->running, true);
    if (pthread_create(&n->thread, NULL, ntp_thread, n) != 0) {
        ntp_client_stop(n);
        return NULL;
    }
    n->thread_started = true;
    *local_port = port;
    return n;
}

void ntp_client_stop(ntp_client_t *n) {
    if (!n) {
        return;
    }
    atomic_store(&n->running, false);
    if (n->thread_started) {
        wakeup_signal(&n->wake);
        pthread_join(n->thread, NULL);
    }
    net_close(&n->fd);
    wakeup_close(&n->wake);
    free(n);
}

bool ntp_client_offset(ntp_client_t *n, int64_t *offset_ns) {
    if (!n || !atomic_load(&n->have_offset)) {
        return false;
    }
    *offset_ns = atomic_load(&n->offset_ns);
    return true;
}
