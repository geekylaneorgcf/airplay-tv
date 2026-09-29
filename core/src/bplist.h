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
 * Minimal binary property list ("bplist00") reader and writer.
 *
 * Only the subset used by AirPlay is supported: null, booleans, integers, reals,
 * dates, data, ASCII/UTF-16 strings, UIDs, arrays, sets (read as arrays) and
 * dictionaries with string keys. The parser treats its input as hostile: every
 * offset and length is validated, recursion depth and the total number of
 * decoded nodes are bounded, and reference cycles cannot cause unbounded work.
 */

#ifndef AIRPLAYTV_BPLIST_H
#define AIRPLAYTV_BPLIST_H

#include "common.h"

typedef enum {
    BP_NULL,
    BP_BOOL,
    BP_INT,
    BP_REAL,
    BP_DATE,
    BP_DATA,
    BP_STRING,
    BP_UID,
    BP_ARRAY,
    BP_DICT
} bp_type_t;

typedef struct bp_node bp_node_t;

#define BP_MAX_DEPTH 24
#define BP_MAX_NODES 16384

/* Parses a binary plist. Returns NULL for malformed or oversized input. */
bp_node_t *bp_parse(const uint8_t *data, size_t len);

/* Serializes a tree. On success *out is malloc'd and must be freed by the caller. */
int bp_write(const bp_node_t *root, uint8_t **out, size_t *out_len);

void bp_free(bp_node_t *node);

bp_type_t bp_type(const bp_node_t *node);

/* Accessors return false / NULL / 0 when the node is missing or of another type. */
bp_node_t *bp_dict_get(const bp_node_t *dict, const char *key);
size_t bp_count(const bp_node_t *collection);
bp_node_t *bp_array_get(const bp_node_t *array, size_t index);
const char *bp_dict_key_at(const bp_node_t *dict, size_t index);
bp_node_t *bp_dict_value_at(const bp_node_t *dict, size_t index);
bool bp_get_bool(const bp_node_t *node, bool *out);
/* Accepts non-negative integers only. */
bool bp_get_uint(const bp_node_t *node, uint64_t *out);
bool bp_get_int(const bp_node_t *node, int64_t *out);
bool bp_get_real(const bp_node_t *node, double *out);
/* NUL-terminated UTF-8. */
const char *bp_get_string(const bp_node_t *node);
const uint8_t *bp_get_data(const bp_node_t *node, size_t *len);

/* Builders. The container functions take ownership of value, also on failure. */
bp_node_t *bp_new_dict(void);
bp_node_t *bp_new_array(void);
bp_node_t *bp_new_bool(bool value);
bp_node_t *bp_new_uint(uint64_t value);
bp_node_t *bp_new_int(int64_t value);
bp_node_t *bp_new_real(double value);
bp_node_t *bp_new_string(const char *utf8);
bp_node_t *bp_new_data(const void *data, size_t len);
bool bp_dict_set(bp_node_t *dict, const char *key, bp_node_t *value);
bool bp_array_append(bp_node_t *array, bp_node_t *value);

#endif
