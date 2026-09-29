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
 */

#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

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

    fake_sender_t f;
    if (fs_connect(&f, ip, (uint16_t) port) != 0) {
        fprintf(stderr, "cannot connect to %s:%d\n", ip, port);
        return 1;
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
