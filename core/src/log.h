/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#ifndef AIRPLAYTV_LOG_H
#define AIRPLAYTV_LOG_H

#include <stddef.h>
#include <stdbool.h>

typedef enum {
    LOGC_DISCOVERY = 0,
    LOGC_AIRPLAY,
    LOGC_SESSION,
    LOGC_NETWORK,
    LOGC_VIDEO,
    LOGC_AUDIO,
    LOGC_DECODER,
    LOGC_SERVICE,
    LOGC_PAIRING,
    LOGC_COUNT
} log_category_t;

typedef enum {
    LOGL_ERROR = 0,
    LOGL_WARN = 1,
    LOGL_INFO = 2,
    LOGL_DEBUG = 3,
    LOGL_TRACE = 4
} log_level_t;

/* Output hook for the platform (logcat on Android, stderr on the host). */
typedef void (*log_sink_fn)(log_level_t level, log_category_t category, const char *message);

void log_set_sink(log_sink_fn sink);

/* Messages up to this level go to the sink. INFO and above are always kept in the
 * in-memory history used for diagnostics, regardless of this setting. */
void log_set_level(log_level_t level);
log_level_t log_get_level(void);
bool log_enabled(log_level_t level);

void log_msg(log_level_t level, log_category_t category, const char *fmt, ...)
    __attribute__((format(printf, 3, 4)));

/* Adds a message to the history only (used for messages that were already printed
 * by the platform layer). */
void log_history_add(log_level_t level, log_category_t category, const char *message);

const char *log_category_name(log_category_t category);

/* Copies the recent history (oldest first, one entry per line) into buf.
 * Returns the number of bytes written, excluding the terminating NUL. */
size_t log_dump_history(char *buf, size_t cap);
void log_clear_history(void);

#define LOG_E(cat, ...) log_msg(LOGL_ERROR, LOGC_##cat, __VA_ARGS__)
#define LOG_W(cat, ...) log_msg(LOGL_WARN, LOGC_##cat, __VA_ARGS__)
#define LOG_I(cat, ...) log_msg(LOGL_INFO, LOGC_##cat, __VA_ARGS__)
#define LOG_D(cat, ...) do { if (log_enabled(LOGL_DEBUG)) log_msg(LOGL_DEBUG, LOGC_##cat, __VA_ARGS__); } while (0)
#define LOG_T(cat, ...) do { if (log_enabled(LOGL_TRACE)) log_msg(LOGL_TRACE, LOGC_##cat, __VA_ARGS__); } while (0)

#endif
