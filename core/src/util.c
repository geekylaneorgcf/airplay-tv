/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#include "util.h"

#include <ctype.h>
#include <stdio.h>
#include <time.h>

uint64_t time_mono_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t) ts.tv_sec * NS_PER_SEC + (uint64_t) ts.tv_nsec;
}

uint64_t time_real_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    return (uint64_t) ts.tv_sec * NS_PER_SEC + (uint64_t) ts.tv_nsec;
}

void secure_zero(void *p, size_t len) {
    volatile uint8_t *v = (volatile uint8_t *) p;
    while (len--) {
        *v++ = 0;
    }
}

void hex_encode(const uint8_t *in, size_t len, char *out) {
    static const char digits[] = "0123456789abcdef";
    for (size_t i = 0; i < len; i++) {
        out[2 * i] = digits[in[i] >> 4];
        out[2 * i + 1] = digits[in[i] & 0xf];
    }
    out[2 * len] = '\0';
}

static int hex_value(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

int hex_decode(const char *in, uint8_t *out, size_t out_len) {
    if (!in || strlen(in) != out_len * 2) {
        return -1;
    }
    for (size_t i = 0; i < out_len; i++) {
        int hi = hex_value(in[2 * i]);
        int lo = hex_value(in[2 * i + 1]);
        if (hi < 0 || lo < 0) {
            return -1;
        }
        out[i] = (uint8_t) ((hi << 4) | lo);
    }
    return 0;
}

size_t base64_encoded_len(size_t len) {
    return ((len + 2) / 3) * 4 + 1;
}

void base64_encode(const uint8_t *in, size_t len, char *out) {
    static const char tbl[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    size_t o = 0;
    size_t i = 0;
    while (i + 2 < len) {
        uint32_t v = ((uint32_t) in[i] << 16) | ((uint32_t) in[i + 1] << 8) | in[i + 2];
        out[o++] = tbl[(v >> 18) & 63];
        out[o++] = tbl[(v >> 12) & 63];
        out[o++] = tbl[(v >> 6) & 63];
        out[o++] = tbl[v & 63];
        i += 3;
    }
    if (i < len) {
        uint32_t v = (uint32_t) in[i] << 16;
        if (i + 1 < len) {
            v |= (uint32_t) in[i + 1] << 8;
        }
        out[o++] = tbl[(v >> 18) & 63];
        out[o++] = tbl[(v >> 12) & 63];
        out[o++] = (i + 1 < len) ? tbl[(v >> 6) & 63] : '=';
        out[o++] = '=';
    }
    out[o] = '\0';
}

void format_device_id(const uint8_t id[6], char out[18]) {
    snprintf(out, 18, "%02X:%02X:%02X:%02X:%02X:%02X", id[0], id[1], id[2], id[3], id[4], id[5]);
}

void format_device_id_compact(const uint8_t id[6], char out[13]) {
    snprintf(out, 13, "%02X%02X%02X%02X%02X%02X", id[0], id[1], id[2], id[3], id[4], id[5]);
}

bool str_ieq(const char *a, const char *b) {
    if (!a || !b) {
        return false;
    }
    while (*a && *b) {
        if (tolower((unsigned char) *a) != tolower((unsigned char) *b)) {
            return false;
        }
        a++;
        b++;
    }
    return *a == *b;
}

size_t str_copy(char *dst, size_t cap, const char *src) {
    size_t len = src ? strlen(src) : 0;
    if (cap > 0) {
        size_t n = len < cap - 1 ? len : cap - 1;
        if (n) {
            memcpy(dst, src, n);
        }
        dst[n] = '\0';
    }
    return len;
}

long long parse_uint(const char *s, size_t len, long long max) {
    long long v = 0;
    if (!s || len == 0 || len > 19) {
        return -1;
    }
    for (size_t i = 0; i < len; i++) {
        if (s[i] < '0' || s[i] > '9') {
            return -1;
        }
        v = v * 10 + (s[i] - '0');
        if (v > max) {
            return -1;
        }
    }
    return v;
}

void sanitize_utf8(char *dst, size_t cap, const char *src, size_t len) {
    size_t o = 0;
    size_t i = 0;
    if (cap == 0) {
        return;
    }
    while (i < len && o + 1 < cap) {
        unsigned char c = (unsigned char) src[i];
        size_t seq = 0;
        if (c == 0) {
            break;
        } else if (c < 0x20 || c == 0x7f) {
            dst[o++] = '?';
            i++;
            continue;
        } else if (c < 0x80) {
            seq = 1;
        } else if ((c & 0xe0) == 0xc0 && c >= 0xc2) {
            seq = 2;
        } else if ((c & 0xf0) == 0xe0) {
            seq = 3;
        } else if ((c & 0xf8) == 0xf0 && c <= 0xf4) {
            seq = 4;
        }
        bool valid = seq > 0 && i + seq <= len;
        for (size_t k = 1; valid && k < seq; k++) {
            if (((unsigned char) src[i + k] & 0xc0) != 0x80) {
                valid = false;
            }
        }
        if (!valid) {
            dst[o++] = '?';
            i++;
            continue;
        }
        if (o + seq >= cap) {
            break;
        }
        memcpy(dst + o, src + i, seq);
        o += seq;
        i += seq;
    }
    dst[o] = '\0';
}
