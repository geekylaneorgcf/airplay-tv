/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

/* Minimal self-registering unit test framework. */

#ifndef AIRPLAYTV_TEST_H
#define AIRPLAYTV_TEST_H

#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

typedef void (*test_fn_t)(void);

void test_register(const char *name, test_fn_t fn);
void test_fail(const char *file, int line, const char *expr);
bool test_failed(void);

#define TEST(name)                                                              \
    static void name(void);                                                     \
    __attribute__((constructor)) static void test_register_##name(void) {      \
        test_register(#name, name);                                             \
    }                                                                           \
    static void name(void)

#define CHECK(cond)                                                             \
    do {                                                                        \
        if (!(cond)) {                                                          \
            test_fail(__FILE__, __LINE__, #cond);                               \
            return;                                                             \
        }                                                                       \
    } while (0)

#define CHECK_EQ(a, b)                                                          \
    do {                                                                        \
        long long va_ = (long long) (a);                                        \
        long long vb_ = (long long) (b);                                        \
        if (va_ != vb_) {                                                       \
            char msg_[256];                                                     \
            snprintf(msg_, sizeof(msg_), "%s == %s (%lld != %lld)", #a, #b, va_, vb_); \
            test_fail(__FILE__, __LINE__, msg_);                                \
            return;                                                             \
        }                                                                       \
    } while (0)

#define CHECK_MEM(a, b, n) CHECK(memcmp((a), (b), (n)) == 0)
#define CHECK_STR(a, b) CHECK((a) != NULL && strcmp((a), (b)) == 0)

/* Decodes hex into out (up to cap bytes). Returns the number of bytes. */
size_t test_hex(const char *hex, uint8_t *out, size_t cap);

#endif
