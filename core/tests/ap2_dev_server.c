/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky and contributors
 *
 * A manual test tool, not part of the unit tests: the receiver core in AirPlay 2 mode behind a plain TCP port, printing the
 * audio it is handed. Run it, then run ap2_sender (see ap2_sender.c) against the port it prints.
 *
 *   ap2_dev_server [mode] [--advertise NAME] [--model M] [--srcvers V]
 *
 * With --advertise it also registers the receiver with the Mac's mDNS (the dns-sd tool, two children) under NAME, so that a real
 * iPhone finds it in its AirPlay list and plays to it. The audio is only counted, not played. That makes the core a receiver on a
 * host where UDP ports 319 and 320 can be opened (run a PTP listener beside it), which is the comparison with the Fire TV stick,
 * where they cannot: if a phone starts playing here and not there, the difference is the clock; if it does not start here either,
 * it is the receiver's replies. Ctrl-C ends it and its children.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#include "airplay.h"
#include "log.h"
#include "media.h"
#include "util.h"

static unsigned g_frames;
static double g_t0;
static size_t g_bytes;

static double now_s(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (double) t.tv_sec + (double) t.tv_nsec / 1e9;
}

static bool m_audio_start(void *ctx, const audio_format_t *f) {
    (void) ctx;
    g_t0 = now_s();
    printf("[server] audio_start ct=%d spf=%d sr=%d ch=%d\n", f->ct, f->samples_per_frame, f->sample_rate, f->channels);
    fflush(stdout);
    return true;
}
static void m_audio_frame(void *ctx, const uint8_t *d, size_t len, uint32_t rtp, uint64_t ts) {
    (void) ctx; (void) d; (void) ts;
    if (g_frames++ % 64 == 0) {
        printf("[server] %6.2fs audio frame #%u len=%zu rtp=%u\n", now_s() - g_t0, g_frames, len, rtp);
        fflush(stdout);
    }
    g_bytes += len;
}
static void m_audio_flush(void *ctx) { (void) ctx; printf("[server] audio_flush\n"); fflush(stdout); g_frames = 0; }
static void m_audio_volume(void *ctx, float db) { (void) ctx; printf("[server] volume %.1f dB\n", (double) db); fflush(stdout); }
static void m_audio_stop(void *ctx) {
    (void) ctx;
    printf("[server] audio_stop after %u frames, %zu bytes; packets_in %llu, dropped_aead %llu, lost %llu, decoded %llu, dropped %llu\n", g_frames, g_bytes,
           (unsigned long long) g_stats.audio_packets_in, (unsigned long long) g_stats.audio_dropped_aead,
           (unsigned long long) g_stats.audio_packets_lost, (unsigned long long) g_stats.audio_frames_decoded,
           (unsigned long long) g_stats.audio_frames_dropped);
    fflush(stdout);
    g_frames = 0;
    g_bytes = 0;
}
static void ev_started(void *ctx, const char *name, const char *model) {
    (void) ctx;
    printf("[server] session_started from \"%s\" (%s)\n", name, model);
    fflush(stdout);
}
static void ev_ended(void *ctx) { (void) ctx; printf("[server] session_ended\n"); fflush(stdout); }
static void ev_track(void *ctx, const char *title, const char *artist, const char *album) {
    (void) ctx;
    printf("[server] track: \"%s\" / \"%s\" / \"%s\"\n", title, artist, album);
    fflush(stdout);
}
static void ev_artwork(void *ctx, const uint8_t *data, size_t len) {
    (void) ctx;
    printf("[server] artwork: %zu bytes\n", len);
    FILE *f = fopen("/tmp/ap2_dev_artwork.img", "wb");
    if (f) {
        fwrite(data, 1, len, f);
        fclose(f);
    }
    fflush(stdout);
}
static void ev_progress(void *ctx, uint32_t start, uint32_t cur, uint32_t end) {
    (void) ctx;
    printf("[server] progress: %u / %u / %u\n", start, cur, end);
    fflush(stdout);
}

static const media_sink_ops_t kOps = {
    .audio_start = m_audio_start, .audio_frame = m_audio_frame, .audio_flush = m_audio_flush,
    .audio_volume = m_audio_volume, .audio_stop = m_audio_stop,
};

/* what the core logs (the request trace, the audio, the pairing): the same lines the Android app puts in logcat */
static void core_log(log_level_t level, log_category_t category, const char *message) {
    (void) level;
    static double t0;
    if (t0 == 0) {
        t0 = now_s();
    }
    printf("[core %8.3f] %-9s %s\n", now_s() - t0, log_category_name(category), message);
    fflush(stdout);
}

static pid_t g_children[2];

static void kill_children(int sig) {
    (void) sig;
    for (int i = 0; i < 2; i++) {
        if (g_children[i] > 0) {
            kill(g_children[i], SIGTERM);
        }
    }
    _exit(0);
}

/* dns-sd -R "instance" type local port key=value ... : the registration lives as long as the child does */
static pid_t register_service(const char *instance, const char *type, int port, const txt_entry_t *txt, int count) {
    pid_t pid = fork();
    if (pid != 0) {
        return pid;
    }
    char *argv[64];
    int n = 0;
    char portbuf[16];
    snprintf(portbuf, sizeof(portbuf), "%d", port);
    argv[n++] = (char *) "dns-sd";
    argv[n++] = (char *) "-R";
    argv[n++] = (char *) instance;
    argv[n++] = (char *) type;
    argv[n++] = (char *) "local";
    argv[n++] = portbuf;
    char entries[24][200];
    for (int i = 0; i < count && i < 24 && n < 62; i++) {
        snprintf(entries[i], sizeof(entries[i]), "%s=%s", txt[i].key, txt[i].value);
        argv[n++] = entries[i];
    }
    argv[n] = NULL;
    freopen("/dev/null", "w", stdout);
    execvp("dns-sd", argv);
    _exit(127);
}

int main(int argc, char **argv) {
    airplay_config_t cfg;
    memset(&cfg, 0, sizeof(cfg));
    const char *advertise = NULL;
    int mode = 1;
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--advertise") == 0 && i + 1 < argc) {
            advertise = argv[++i];
        } else if (strcmp(argv[i], "--model") == 0 && i + 1 < argc) {
            str_copy(cfg.model, sizeof(cfg.model), argv[++i]);
        } else if (strcmp(argv[i], "--srcvers") == 0 && i + 1 < argc) {
            str_copy(cfg.srcvers, sizeof(cfg.srcvers), argv[++i]);
        } else {
            mode = atoi(argv[i]);
        }
    }
    str_copy(cfg.name, sizeof(cfg.name), advertise ? advertise : "AP2 Dev Server");
    /* the identity follows from the name, so that two servers on one host (two modes side by side) are two devices */
    uint64_t h = 1469598103934665603ull;
    for (const char *c = cfg.name; *c; c++) {
        h = (h ^ (uint8_t) *c) * 1099511628211ull;
    }
    cfg.device_id[0] = 0x02;
    for (int i = 1; i < 6; i++) {
        cfg.device_id[i] = (uint8_t) (h >> (8 * (i - 1)));
    }
    snprintf(cfg.public_id, sizeof(cfg.public_id), "5d1a3c3e-0f2b-4c64-8a51-%02x%02x%02x%02x%02x%02x", cfg.device_id[0], cfg.device_id[1],
             cfg.device_id[2], cfg.device_id[3], cfg.device_id[4], cfg.device_id[5]);
    memset(cfg.identity_seed, (int) (h & 0xff), 32);
    cfg.airplay2 = mode;
    cfg.display_width = 1920;
    cfg.display_height = 1080;
    cfg.display_fps = 60;
    log_set_sink(core_log);
    airplay_events_t ev = { .session_started = ev_started, .session_ended = ev_ended, .track_info = ev_track, .artwork = ev_artwork,
                            .progress = ev_progress };
    airplay_server_t *s = airplay_server_create(&cfg, &ev, &kOps, NULL);
    int port = s ? airplay_server_start(s) : -1;
    if (port <= 0) {
        fprintf(stderr, "could not start\n");
        return 1;
    }
    printf("[server] listening on %d (airplay2=%d, features 0x%llx)\n", port, cfg.airplay2, (unsigned long long) airplay_features(&cfg));
    fflush(stdout);
    if (advertise) {
        txt_entry_t airplay[24];
        txt_entry_t raop[24];
        int na = airplay_txt_airplay(s, airplay, 24);
        int nr = airplay_txt_raop(s, raop, 24);
        char raop_name[96];
        airplay_raop_name(&cfg, raop_name, sizeof(raop_name));
        g_children[0] = register_service(advertise, "_airplay._tcp", port, airplay, na);
        g_children[1] = register_service(raop_name, "_raop._tcp", port, raop, nr);
        signal(SIGINT, kill_children);
        signal(SIGTERM, kill_children);
        printf("[server] advertised as \"%s\" and \"%s\"\n", advertise, raop_name);
        fflush(stdout);
    }
    for (;;) {
        sleep(1);
    }
}
