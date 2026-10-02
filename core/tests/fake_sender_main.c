/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

/*
 * Streams a generated test picture (and optionally a tone) to a running
 * receiver, e.g. the app on an emulator or a TV:
 *
 *   airplaytv_sender <ip> [port] [seconds] [--pair] [--audio]
 *   airplaytv_sender <ip> [port] [seconds] --play <url>     AirPlay video: plays the address, reports where it is, stops
 */

#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "bplist.h"
#include "fake_sender.h"
#include "h264_gen.h"
#include "ntp.h"
#include "util.h"

static void sleep_until(uint64_t deadline_ns) {
    uint64_t now = time_mono_ns();
    if (deadline_ns > now) {
        uint64_t d = deadline_ns - now;
        struct timespec ts = { (time_t) (d / NS_PER_SEC), (long) (d % NS_PER_SEC) };
        nanosleep(&ts, NULL);
    }
}

/* AirPlay video as an iPhone asks for it: POST /play with the address, then GET /playback-info about once a second. */
static int run_play(fake_sender_t *f, const char *url, int seconds) {
    bp_node_t *d = bp_new_dict();
    bp_dict_set(d, "Content-Location", bp_new_string(url));
    bp_dict_set(d, "Start-Position-Seconds", bp_new_real(0.0));
    bp_dict_set(d, "rate", bp_new_real(1.0));
    uint8_t *body = NULL;
    size_t len = 0;
    if (bp_write(d, &body, &len) != 0) {
        bp_free(d);
        return 1;
    }
    bp_free(d);
    int status = 0;
    char resp[4096];
    int rc = fs_http(f, "POST", "/play", "Content-Type: application/x-apple-binary-plist\r\nX-Apple-Session-ID: test\r\n", body, len,
                     &status, resp, sizeof(resp));
    free(body);
    printf("POST /play: %d\n", rc == 0 ? status : -1);
    if (rc != 0 || status != 200) {
        return 1;
    }
    for (int i = 0; i < seconds; i++) {
        struct timespec ts = { 1, 0 };
        nanosleep(&ts, NULL);
        if (fs_http(f, "GET", "/playback-info", NULL, NULL, 0, &status, resp, sizeof(resp)) != 0) {
            printf("playback-info failed\n");
            break;
        }
        const char *dur = strstr(resp, "<key>duration</key><real>");
        const char *pos = strstr(resp, "<key>position</key><real>");
        const char *ready = strstr(resp, "<key>readyToPlay</key><true/>");
        printf("t=%2ds duration=%s position=%s ready=%s\n", i + 1, dur ? strtok((char *) dur + 25, "<") : "?",
               pos ? strtok((char *) pos + 25, "<") : "?", ready ? "yes" : "no");
    }
    fs_http(f, "POST", "/stop", NULL, NULL, 0, &status, NULL, 0);
    printf("POST /stop: %d\n", status);
    return 0;
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s <ip> [port] [seconds] [--pair] [--audio]\n", argv[0]);
        return 2;
    }
    signal(SIGPIPE, SIG_IGN);
    const char *ip = argv[1];
    int port = argc > 2 ? atoi(argv[2]) : 7000;
    int seconds = argc > 3 ? atoi(argv[3]) : 10;
    bool pair = false;
    bool audio = false;
    bool pin_only = false;
    for (int i = 2; i < argc; i++) {
        pair |= strcmp(argv[i], "--pair") == 0;
        audio |= strcmp(argv[i], "--audio") == 0;
        pin_only |= strcmp(argv[i], "--pin-screen") == 0;
    }

    const char *play_url = NULL;
    for (int i = 2; i + 1 < argc; i++) {
        if (strcmp(argv[i], "--play") == 0) {
            play_url = argv[i + 1];
        }
    }

    fake_sender_t f;
    if (fs_connect(&f, ip, (uint16_t) port) != 0) {
        fprintf(stderr, "cannot connect to %s:%d\n", ip, port);
        return 1;
    }
    if (play_url) {
        int rc = run_play(&f, play_url, seconds);
        fs_close(&f);
        return rc;
    }
    if (pin_only) {
        /* ask a PIN-protected receiver to show its code, then wait */
        int status = 0;
        fs_request(&f, "POST", "/pair-pin-start", NULL, NULL, 0, &status, NULL, NULL);
        printf("pair-pin-start: %d\n", status);
        sleep_until(time_mono_ns() + (uint64_t) seconds * NS_PER_SEC);
        fs_close(&f);
        return status == 200 ? 0 : 1;
    }
    if (pair ? fs_pair(&f) != 0 : fs_info(&f, NULL) != 0) {
        fprintf(stderr, "pairing/info failed\n");
        return 1;
    }
    if (fs_fairplay(&f) != 0) {
        fprintf(stderr, "fp-setup failed\n");
        return 1;
    }
    int st = fs_setup_session(&f, "Test Sender", "iPhone15,2");
    if (st != 200) {
        fprintf(stderr, "SETUP failed (%d)\n", st);
        return 1;
    }
    if (fs_setup_mirror(&f, 0x7e57ULL) != 0) {
        fprintf(stderr, "mirror SETUP failed\n");
        return 1;
    }
    if (audio && fs_setup_audio(&f, 2, 352) != 0) {
        fprintf(stderr, "audio SETUP failed\n");
        return 1;
    }

    const int width = 640;
    const int height = 352;
    const int fps = 30;
    h264_gen_t g;
    h264_gen_init(&g, width, height);
    uint8_t avcc[128];
    size_t avcc_len = h264_gen_avcc(&g, avcc, sizeof(avcc));
    fs_send_codec(&f, avcc, avcc_len, width, height, 0x16);

    static uint8_t au[1024 * 1024];
    uint8_t alac[2048];
    uint64_t start = time_mono_ns();
    uint64_t next_video = start;
    uint64_t next_audio = start;
    uint64_t next_feedback = start;
    int frame = 0;
    int packet = 0;
    while (time_mono_ns() - start < (uint64_t) seconds * NS_PER_SEC) {
        uint64_t now = time_mono_ns();
        if (now >= next_video) {
            size_t n = h264_gen_frame(&g, frame % fps == 0, frame / 2, au, sizeof(au));
            if (fs_send_video(&f, au, n, ns_to_ntp(now)) != 0) {
                fprintf(stderr, "video stream closed\n");
                break;
            }
            frame++;
            next_video += NS_PER_SEC / fps;
        }
        if (audio && now >= next_audio) {
            if (packet % 100 == 0) {
                fs_send_sync(&f);
            }
            size_t n = fs_make_alac_frame(alac, sizeof(alac), packet++);
            fs_send_audio(&f, alac, n, 352);
            next_audio += 352ULL * NS_PER_SEC / 44100;
        }
        if (now >= next_feedback) {
            fs_feedback(&f);
            next_feedback += 2 * NS_PER_SEC;
        }
        uint64_t next = next_video;
        if (audio && next_audio < next) next = next_audio;
        sleep_until(next);
    }
    fs_teardown(&f, 0);
    fs_close(&f);
    printf("sent %d frames%s\n", frame, audio ? " with audio" : "");
    return 0;
}
