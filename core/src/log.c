/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#include "log.h"

#include <pthread.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

#define HISTORY_ENTRIES 256
#define ENTRY_LEN 200

static const char *const kCategoryNames[LOGC_COUNT] = {
    "DISCOVERY", "AIRPLAY", "SESSION", "NETWORK", "VIDEO", "AUDIO", "DECODER", "SERVICE", "PAIRING",
};

static const char kLevelChars[] = { 'E', 'W', 'I', 'D', 'T' };

static _Atomic int g_level = LOGL_WARN;
static log_sink_fn g_sink;

static pthread_mutex_t g_history_lock = PTHREAD_MUTEX_INITIALIZER;
static char g_history[HISTORY_ENTRIES][ENTRY_LEN];
static unsigned g_history_next;
static unsigned g_history_count;

void log_set_sink(log_sink_fn sink) {
    g_sink = sink;
}

void log_set_level(log_level_t level) {
    atomic_store(&g_level, (int) level);
}

log_level_t log_get_level(void) {
    return (log_level_t) atomic_load(&g_level);
}

bool log_enabled(log_level_t level) {
    return (int) level <= atomic_load(&g_level) || level <= LOGL_INFO;
}

const char *log_category_name(log_category_t category) {
    return (category >= 0 && category < LOGC_COUNT) ? kCategoryNames[category] : "?";
}

void log_msg(log_level_t level, log_category_t category, const char *fmt, ...) {
    char message[ENTRY_LEN - 32];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(message, sizeof(message), fmt, ap);
    va_end(ap);

    if ((int) level <= atomic_load(&g_level) && g_sink) {
        g_sink(level, category, message);
    }
    log_history_add(level, category, message);
}

void log_history_add(log_level_t level, log_category_t category, const char *message) {
    if (level <= LOGL_INFO || (int) level <= atomic_load(&g_level)) {
        struct timespec ts;
        struct tm tm;
        clock_gettime(CLOCK_REALTIME, &ts);
        time_t secs = ts.tv_sec;
        localtime_r(&secs, &tm);
        pthread_mutex_lock(&g_history_lock);
        snprintf(g_history[g_history_next], ENTRY_LEN, "%02d:%02d:%02d.%03ld %c %-9s %s",
                 tm.tm_hour, tm.tm_min, tm.tm_sec, ts.tv_nsec / 1000000L,
                 kLevelChars[level <= LOGL_TRACE ? level : LOGL_TRACE],
                 log_category_name(category), message);
        g_history_next = (g_history_next + 1) % HISTORY_ENTRIES;
        if (g_history_count < HISTORY_ENTRIES) {
            g_history_count++;
        }
        pthread_mutex_unlock(&g_history_lock);
    }
}

size_t log_dump_history(char *buf, size_t cap) {
    size_t used = 0;
    if (!buf || cap == 0) {
        return 0;
    }
    buf[0] = '\0';
    pthread_mutex_lock(&g_history_lock);
    unsigned start = (g_history_next + HISTORY_ENTRIES - g_history_count) % HISTORY_ENTRIES;
    for (unsigned i = 0; i < g_history_count; i++) {
        const char *line = g_history[(start + i) % HISTORY_ENTRIES];
        int n = snprintf(buf + used, cap - used, "%s\n", line);
        if (n < 0 || (size_t) n >= cap - used) {
            break;
        }
        used += (size_t) n;
    }
    pthread_mutex_unlock(&g_history_lock);
    return used;
}

void log_clear_history(void) {
    pthread_mutex_lock(&g_history_lock);
    g_history_next = 0;
    g_history_count = 0;
    pthread_mutex_unlock(&g_history_lock);
}
