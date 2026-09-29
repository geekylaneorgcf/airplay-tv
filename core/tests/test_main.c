/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#include <signal.h>
#include <stdlib.h>
#include <time.h>

#include "log.h"
#include "test.h"

#define MAX_TESTS 512

static struct {
    const char *name;
    test_fn_t fn;
} g_tests[MAX_TESTS];
static int g_count;
static bool g_current_failed;

void test_register(const char *name, test_fn_t fn) {
    if (g_count < MAX_TESTS) {
        g_tests[g_count].name = name;
        g_tests[g_count].fn = fn;
        g_count++;
    }
}

void test_fail(const char *file, int line, const char *expr) {
    g_current_failed = true;
    fprintf(stderr, "    %s:%d: check failed: %s\n", file, line, expr);
}

bool test_failed(void) {
    return g_current_failed;
}

size_t test_hex(const char *hex, uint8_t *out, size_t cap) {
    size_t n = 0;
    while (hex[0] && hex[1] && n < cap) {
        unsigned v;
        if (sscanf(hex, "%2x", &v) != 1) {
            break;
        }
        out[n++] = (uint8_t) v;
        hex += 2;
    }
    return n;
}

static void quiet_sink(log_level_t level, log_category_t category, const char *message) {
    if (getenv("TEST_VERBOSE")) {
        fprintf(stderr, "      [%s] %s\n", log_category_name(category), message);
    }
    (void) level;
}

int main(int argc, char **argv) {
    const char *filter = argc > 1 ? argv[1] : NULL;
    int failed = 0;
    int run = 0;
    signal(SIGPIPE, SIG_IGN);
    log_set_sink(quiet_sink);
    log_set_level(getenv("TEST_VERBOSE") ? LOGL_DEBUG : LOGL_WARN);
    for (int i = 0; i < g_count; i++) {
        if (filter && !strstr(g_tests[i].name, filter)) {
            continue;
        }
        g_current_failed = false;
        struct timespec t0;
        struct timespec t1;
        clock_gettime(CLOCK_MONOTONIC, &t0);
        g_tests[i].fn();
        clock_gettime(CLOCK_MONOTONIC, &t1);
        double ms = (double) (t1.tv_sec - t0.tv_sec) * 1000.0 + (double) (t1.tv_nsec - t0.tv_nsec) / 1e6;
        run++;
        if (g_current_failed) {
            failed++;
            printf("FAIL  %s (%.0f ms)\n", g_tests[i].name, ms);
        } else {
            printf("ok    %s (%.0f ms)\n", g_tests[i].name, ms);
        }
    }
    printf("\n%d tests, %d failed\n", run, failed);
    return failed ? 1 : 0;
}
