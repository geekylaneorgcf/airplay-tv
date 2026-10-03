/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky and contributors
 *
 * A manual test tool, not part of the unit tests: the receiver core in AirPlay 2 mode behind a plain TCP port, printing the
 * audio it is handed. Run it, then run ap2_sender (see ap2_sender.c) against the port it prints.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "airplay.h"
#include "media.h"
#include "util.h"

static unsigned g_frames;
static size_t g_bytes;

static bool m_audio_start(void *ctx, const audio_format_t *f) {
    (void) ctx;
    printf("[server] audio_start ct=%d spf=%d sr=%d ch=%d\n", f->ct, f->samples_per_frame, f->sample_rate, f->channels);
    fflush(stdout);
    return true;
}
static void m_audio_frame(void *ctx, const uint8_t *d, size_t len, uint32_t rtp, uint64_t ts) {
    (void) ctx; (void) d; (void) ts;
    if (g_frames++ % 128 == 0) {
        printf("[server] audio frame #%u len=%zu rtp=%u\n", g_frames, len, rtp);
        fflush(stdout);
    }
    g_bytes += len;
}
static void m_audio_flush(void *ctx) { (void) ctx; }
static void m_audio_volume(void *ctx, float db) { (void) ctx; printf("[server] volume %.1f dB\n", (double) db); fflush(stdout); }
static void m_audio_stop(void *ctx) {
    (void) ctx;
    printf("[server] audio_stop after %u frames, %zu bytes; packets_in %llu, dropped_aead %llu, lost %llu, decoded %llu, dropped %llu\n", g_frames, g_bytes,
           (unsigned long long) g_stats.audio_packets_in, (unsigned long long) g_stats.audio_dropped_aead,
           (unsigned long long) g_stats.audio_packets_lost, (unsigned long long) g_stats.audio_frames_decoded,
           (unsigned long long) g_stats.audio_frames_dropped);
    fflush(stdout);
}
static void ev_started(void *ctx, const char *name, const char *model) {
    (void) ctx;
    printf("[server] session_started from \"%s\" (%s)\n", name, model);
    fflush(stdout);
}
static void ev_ended(void *ctx) { (void) ctx; printf("[server] session_ended\n"); fflush(stdout); }

static const media_sink_ops_t kOps = {
    .audio_start = m_audio_start, .audio_frame = m_audio_frame, .audio_flush = m_audio_flush,
    .audio_volume = m_audio_volume, .audio_stop = m_audio_stop,
};

int main(int argc, char **argv) {
    airplay_config_t cfg;
    memset(&cfg, 0, sizeof(cfg));
    str_copy(cfg.name, sizeof(cfg.name), "AP2 Dev Server");
    memcpy(cfg.device_id, "\x02\x11\x22\x33\x44\x66", 6);
    str_copy(cfg.public_id, sizeof(cfg.public_id), "5d1a3c3e-0f2b-4c64-8a51-2d7c9f2b6a11");
    memset(cfg.identity_seed, 0x43, 32);
    cfg.airplay2 = argc > 1 ? atoi(argv[1]) : 1;
    cfg.display_width = 1920;
    cfg.display_height = 1080;
    cfg.display_fps = 60;
    airplay_events_t ev = { .session_started = ev_started, .session_ended = ev_ended };
    airplay_server_t *s = airplay_server_create(&cfg, &ev, &kOps, NULL);
    int port = s ? airplay_server_start(s) : -1;
    if (port <= 0) {
        fprintf(stderr, "could not start\n");
        return 1;
    }
    printf("[server] listening on %d (airplay2=%d, features 0x%llx)\n", port, cfg.airplay2, (unsigned long long) airplay_features(&cfg));
    fflush(stdout);
    for (;;) {
        sleep(1);
    }
}
