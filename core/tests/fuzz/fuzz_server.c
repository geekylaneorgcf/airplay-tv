/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

/* Drives the RTSP request handlers (pairing, FairPlay, SETUP, ...) with arbitrary input. */

#include <signal.h>
#include <stdlib.h>

#include "airplay.h"
#include "log.h"

static void quiet(log_level_t level, log_category_t category, const char *message) {
    (void) level;
    (void) category;
    (void) message;
}

static void noop(void *ctx) { (void) ctx; }
static bool noop_start(void *ctx, const audio_format_t *f) { (void) ctx; (void) f; return true; }

static const media_sink_ops_t kOps = {
    .video_start = noop,
    .video_stop = noop,
    .audio_start = noop_start,
    .audio_stop = noop,
};

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    static airplay_server_t *s[2];
    if (!s[0]) {
        signal(SIGPIPE, SIG_IGN);
        log_set_sink(quiet);
        log_set_level(LOGL_ERROR);
        for (int i = 0; i < 2; i++) {
            airplay_config_t cfg;
            memset(&cfg, 0, sizeof(cfg));
            memcpy(cfg.name, "Fuzz TV", 8);
            cfg.require_pin = i == 1;
            memset(cfg.identity_seed, 7, sizeof(cfg.identity_seed));
            s[i] = airplay_server_create(&cfg, NULL, &kOps, NULL);
        }
    }
    airplay_server_fuzz_input(s[size & 1], data, size);
    return 0;
}
