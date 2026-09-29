/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#ifndef AIRPLAYTV_UTIL_H
#define AIRPLAYTV_UTIL_H

#include "common.h"

/* Lowercase hex encoding. out must hold 2 * len + 1 bytes. */
void hex_encode(const uint8_t *in, size_t len, char *out);

/* Decodes exactly out_len bytes from a hex string. Returns 0 on success. */
int hex_decode(const char *in, uint8_t *out, size_t out_len);

/* Standard base64 with padding. out must hold base64_encoded_len(len) bytes. */
size_t base64_encoded_len(size_t len);
void base64_encode(const uint8_t *in, size_t len, char *out);

/* Formats a 6-byte device id as "AA:BB:CC:DD:EE:FF" (18 bytes incl. NUL). */
void format_device_id(const uint8_t id[6], char out[18]);

/* Formats a 6-byte device id as "AABBCCDDEEFF" (13 bytes incl. NUL). */
void format_device_id_compact(const uint8_t id[6], char out[13]);

/* Case-insensitive ASCII string equality. */
bool str_ieq(const char *a, const char *b);

/* Copies at most cap - 1 bytes and always terminates. Returns strlen(src). */
size_t str_copy(char *dst, size_t cap, const char *src);

/* Parses a non-negative decimal integer that must fit into max. Returns -1 on error. */
long long parse_uint(const char *s, size_t len, long long max);

/* Copies src into dst, replacing control characters and invalid UTF-8 with '?'. */
void sanitize_utf8(char *dst, size_t cap, const char *src, size_t len);

#endif
