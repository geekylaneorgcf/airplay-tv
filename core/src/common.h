/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#ifndef AIRPLAYTV_COMMON_H
#define AIRPLAYTV_COMMON_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#define ARRAY_SIZE(a) (sizeof(a) / sizeof((a)[0]))

#ifndef MIN
#define MIN(a, b) ((a) < (b) ? (a) : (b))
#endif
#ifndef MAX
#define MAX(a, b) ((a) > (b) ? (a) : (b))
#endif

#define NS_PER_US 1000ULL
#define NS_PER_MS 1000000ULL
#define NS_PER_SEC 1000000000ULL

static inline uint16_t rd16be(const uint8_t *p) {
    return (uint16_t) (((uint16_t) p[0] << 8) | p[1]);
}

static inline uint32_t rd32be(const uint8_t *p) {
    return ((uint32_t) p[0] << 24) | ((uint32_t) p[1] << 16) | ((uint32_t) p[2] << 8) | p[3];
}

static inline uint64_t rd64be(const uint8_t *p) {
    return ((uint64_t) rd32be(p) << 32) | rd32be(p + 4);
}

static inline uint32_t rd32le(const uint8_t *p) {
    return ((uint32_t) p[3] << 24) | ((uint32_t) p[2] << 16) | ((uint32_t) p[1] << 8) | p[0];
}

static inline uint64_t rd64le(const uint8_t *p) {
    return ((uint64_t) rd32le(p + 4) << 32) | rd32le(p);
}

static inline float rdf32le(const uint8_t *p) {
    uint32_t u = rd32le(p);
    float f;
    memcpy(&f, &u, sizeof(f));
    return f;
}

static inline void wr16be(uint8_t *p, uint16_t v) {
    p[0] = (uint8_t) (v >> 8);
    p[1] = (uint8_t) v;
}

static inline void wr32be(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t) (v >> 24);
    p[1] = (uint8_t) (v >> 16);
    p[2] = (uint8_t) (v >> 8);
    p[3] = (uint8_t) v;
}

static inline void wr64be(uint8_t *p, uint64_t v) {
    wr32be(p, (uint32_t) (v >> 32));
    wr32be(p + 4, (uint32_t) v);
}

/* Monotonic clock, used for timeouts and latency measurements. */
uint64_t time_mono_ns(void);

/* Wall clock (CLOCK_REALTIME), used only for NTP exchanges with the sender. */
uint64_t time_real_ns(void);

/* Securely wipes memory that held key material. */
void secure_zero(void *p, size_t len);

#endif
