/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#include "bplist.h"

#include <stdlib.h>

struct bp_node {
    bp_type_t type;
    union {
        bool b;
        struct {
            uint64_t raw;
            bool negative;
        } i;
        double r;
        struct {
            uint8_t *ptr;
            size_t len;
        } buf;
        struct {
            bp_node_t **items;
            char **keys;
            size_t count;
            size_t cap;
        } coll;
    } u;
};

/* ------------------------------------------------------------------------- */
/* nodes                                                                      */

static bp_node_t *node_new(bp_type_t type) {
    bp_node_t *n = (bp_node_t *) calloc(1, sizeof(bp_node_t));
    if (n) {
        n->type = type;
    }
    return n;
}

void bp_free(bp_node_t *node) {
    if (!node) {
        return;
    }
    switch (node->type) {
        case BP_DATA:
        case BP_STRING:
        case BP_UID:
            free(node->u.buf.ptr);
            break;
        case BP_ARRAY:
        case BP_DICT:
            for (size_t i = 0; i < node->u.coll.count; i++) {
                bp_free(node->u.coll.items[i]);
                if (node->u.coll.keys) {
                    free(node->u.coll.keys[i]);
                }
            }
            free(node->u.coll.items);
            free(node->u.coll.keys);
            break;
        default:
            break;
    }
    free(node);
}

bp_type_t bp_type(const bp_node_t *node) {
    return node ? node->type : BP_NULL;
}

static bool coll_reserve(bp_node_t *n, size_t want) {
    if (want <= n->u.coll.cap) {
        return true;
    }
    size_t cap = n->u.coll.cap ? n->u.coll.cap * 2 : 8;
    while (cap < want) {
        cap *= 2;
    }
    bp_node_t **items = (bp_node_t **) realloc(n->u.coll.items, cap * sizeof(*items));
    if (!items) {
        return false;
    }
    n->u.coll.items = items;
    if (n->type == BP_DICT) {
        char **keys = (char **) realloc(n->u.coll.keys, cap * sizeof(*keys));
        if (!keys) {
            return false;
        }
        n->u.coll.keys = keys;
    }
    n->u.coll.cap = cap;
    return true;
}

bp_node_t *bp_dict_get(const bp_node_t *dict, const char *key) {
    if (!dict || dict->type != BP_DICT || !key) {
        return NULL;
    }
    for (size_t i = 0; i < dict->u.coll.count; i++) {
        if (strcmp(dict->u.coll.keys[i], key) == 0) {
            return dict->u.coll.items[i];
        }
    }
    return NULL;
}

size_t bp_count(const bp_node_t *collection) {
    if (!collection || (collection->type != BP_ARRAY && collection->type != BP_DICT)) {
        return 0;
    }
    return collection->u.coll.count;
}

bp_node_t *bp_array_get(const bp_node_t *array, size_t index) {
    if (!array || array->type != BP_ARRAY || index >= array->u.coll.count) {
        return NULL;
    }
    return array->u.coll.items[index];
}

const char *bp_dict_key_at(const bp_node_t *dict, size_t index) {
    if (!dict || dict->type != BP_DICT || index >= dict->u.coll.count) {
        return NULL;
    }
    return dict->u.coll.keys[index];
}

bp_node_t *bp_dict_value_at(const bp_node_t *dict, size_t index) {
    if (!dict || dict->type != BP_DICT || index >= dict->u.coll.count) {
        return NULL;
    }
    return dict->u.coll.items[index];
}

bool bp_get_bool(const bp_node_t *node, bool *out) {
    if (!node || node->type != BP_BOOL) {
        return false;
    }
    *out = node->u.b;
    return true;
}

bool bp_get_uint(const bp_node_t *node, uint64_t *out) {
    if (!node || node->type != BP_INT || node->u.i.negative) {
        return false;
    }
    *out = node->u.i.raw;
    return true;
}

bool bp_get_int(const bp_node_t *node, int64_t *out) {
    if (!node || node->type != BP_INT) {
        return false;
    }
    if (node->u.i.negative) {
        *out = (int64_t) node->u.i.raw;
        return true;
    }
    if (node->u.i.raw > (uint64_t) INT64_MAX) {
        return false;
    }
    *out = (int64_t) node->u.i.raw;
    return true;
}

bool bp_get_real(const bp_node_t *node, double *out) {
    if (!node || (node->type != BP_REAL && node->type != BP_DATE)) {
        return false;
    }
    *out = node->u.r;
    return true;
}

const char *bp_get_string(const bp_node_t *node) {
    if (!node || node->type != BP_STRING) {
        return NULL;
    }
    return (const char *) node->u.buf.ptr;
}

const uint8_t *bp_get_data(const bp_node_t *node, size_t *len) {
    if (!node || node->type != BP_DATA) {
        if (len) {
            *len = 0;
        }
        return NULL;
    }
    if (len) {
        *len = node->u.buf.len;
    }
    return node->u.buf.ptr;
}

bp_node_t *bp_new_dict(void) {
    return node_new(BP_DICT);
}

bp_node_t *bp_new_array(void) {
    return node_new(BP_ARRAY);
}

bp_node_t *bp_new_bool(bool value) {
    bp_node_t *n = node_new(BP_BOOL);
    if (n) {
        n->u.b = value;
    }
    return n;
}

bp_node_t *bp_new_uint(uint64_t value) {
    bp_node_t *n = node_new(BP_INT);
    if (n) {
        n->u.i.raw = value;
    }
    return n;
}

bp_node_t *bp_new_int(int64_t value) {
    bp_node_t *n = node_new(BP_INT);
    if (n) {
        n->u.i.raw = (uint64_t) value;
        n->u.i.negative = value < 0;
    }
    return n;
}

bp_node_t *bp_new_real(double value) {
    bp_node_t *n = node_new(BP_REAL);
    if (n) {
        n->u.r = value;
    }
    return n;
}

static bp_node_t *new_buffer_node(bp_type_t type, const void *data, size_t len) {
    bp_node_t *n = node_new(type);
    if (!n) {
        return NULL;
    }
    n->u.buf.ptr = (uint8_t *) malloc(len + 1);
    if (!n->u.buf.ptr) {
        free(n);
        return NULL;
    }
    if (len) {
        memcpy(n->u.buf.ptr, data, len);
    }
    n->u.buf.ptr[len] = 0;
    n->u.buf.len = len;
    return n;
}

bp_node_t *bp_new_string(const char *utf8) {
    if (!utf8) {
        return NULL;
    }
    return new_buffer_node(BP_STRING, utf8, strlen(utf8));
}

bp_node_t *bp_new_data(const void *data, size_t len) {
    if (!data && len) {
        return NULL;
    }
    return new_buffer_node(BP_DATA, data, len);
}

bool bp_dict_set(bp_node_t *dict, const char *key, bp_node_t *value) {
    if (!dict || dict->type != BP_DICT || !key || !value) {
        bp_free(value);
        return false;
    }
    for (size_t i = 0; i < dict->u.coll.count; i++) {
        if (strcmp(dict->u.coll.keys[i], key) == 0) {
            bp_free(dict->u.coll.items[i]);
            dict->u.coll.items[i] = value;
            return true;
        }
    }
    char *k = strdup(key);
    if (!k || !coll_reserve(dict, dict->u.coll.count + 1)) {
        free(k);
        bp_free(value);
        return false;
    }
    dict->u.coll.keys[dict->u.coll.count] = k;
    dict->u.coll.items[dict->u.coll.count] = value;
    dict->u.coll.count++;
    return true;
}

bool bp_array_append(bp_node_t *array, bp_node_t *value) {
    if (!array || array->type != BP_ARRAY || !value || !coll_reserve(array, array->u.coll.count + 1)) {
        bp_free(value);
        return false;
    }
    array->u.coll.items[array->u.coll.count++] = value;
    return true;
}

/* ------------------------------------------------------------------------- */
/* parser                                                                     */

typedef struct {
    const uint8_t *data;
    size_t objects_end;
    size_t offset_table;
    uint8_t offset_size;
    uint8_t ref_size;
    uint64_t num_objects;
    size_t nodes;
} parse_ctx_t;

static uint64_t read_be(const uint8_t *p, size_t n) {
    uint64_t v = 0;
    for (size_t i = 0; i < n; i++) {
        v = (v << 8) | p[i];
    }
    return v;
}

static bool object_offset(const parse_ctx_t *c, uint64_t index, size_t *off) {
    if (index >= c->num_objects) {
        return false;
    }
    uint64_t o = read_be(c->data + c->offset_table + (size_t) index * c->offset_size, c->offset_size);
    if (o < 8 || o >= c->objects_end) {
        return false;
    }
    *off = (size_t) o;
    return true;
}

/* Reads the element count that follows a marker. */
static bool read_count(const parse_ctx_t *c, size_t off, uint8_t low, size_t *count, size_t *header) {
    if (low != 0x0f) {
        *count = low;
        *header = 1;
        return true;
    }
    if (off + 2 > c->objects_end) {
        return false;
    }
    uint8_t marker = c->data[off + 1];
    if ((marker & 0xf0) != 0x10 || (marker & 0x0f) > 3) {
        return false;
    }
    size_t nbytes = (size_t) 1 << (marker & 0x0f);
    if (off + 2 + nbytes > c->objects_end) {
        return false;
    }
    uint64_t v = read_be(c->data + off + 2, nbytes);
    if (v > c->objects_end) {
        return false;
    }
    *count = (size_t) v;
    *header = 2 + nbytes;
    return true;
}

static bool fits(const parse_ctx_t *c, size_t off, size_t header, size_t count, size_t unit) {
    size_t start = off + header;
    if (start > c->objects_end) {
        return false;
    }
    return count <= (c->objects_end - start) / unit;
}

static void put_utf8(uint8_t *out, size_t *o, uint32_t cp) {
    if (cp < 0x80) {
        out[(*o)++] = (uint8_t) cp;
    } else if (cp < 0x800) {
        out[(*o)++] = (uint8_t) (0xc0 | (cp >> 6));
        out[(*o)++] = (uint8_t) (0x80 | (cp & 0x3f));
    } else if (cp < 0x10000) {
        out[(*o)++] = (uint8_t) (0xe0 | (cp >> 12));
        out[(*o)++] = (uint8_t) (0x80 | ((cp >> 6) & 0x3f));
        out[(*o)++] = (uint8_t) (0x80 | (cp & 0x3f));
    } else {
        out[(*o)++] = (uint8_t) (0xf0 | (cp >> 18));
        out[(*o)++] = (uint8_t) (0x80 | ((cp >> 12) & 0x3f));
        out[(*o)++] = (uint8_t) (0x80 | ((cp >> 6) & 0x3f));
        out[(*o)++] = (uint8_t) (0x80 | (cp & 0x3f));
    }
}

static bp_node_t *utf16be_to_string(const uint8_t *p, size_t units) {
    bp_node_t *n = node_new(BP_STRING);
    if (!n) {
        return NULL;
    }
    n->u.buf.ptr = (uint8_t *) malloc(units * 3 + 1);
    if (!n->u.buf.ptr) {
        free(n);
        return NULL;
    }
    size_t o = 0;
    for (size_t i = 0; i < units; i++) {
        uint32_t cu = rd16be(p + 2 * i);
        if (cu >= 0xd800 && cu <= 0xdbff && i + 1 < units) {
            uint32_t lo = rd16be(p + 2 * (i + 1));
            if (lo >= 0xdc00 && lo <= 0xdfff) {
                put_utf8(n->u.buf.ptr, &o, 0x10000 + ((cu - 0xd800) << 10) + (lo - 0xdc00));
                i++;
                continue;
            }
        }
        if (cu >= 0xd800 && cu <= 0xdfff) {
            cu = 0xfffd;
        }
        if (cu == 0) {
            cu = 0xfffd; /* keep strings NUL-free */
        }
        put_utf8(n->u.buf.ptr, &o, cu);
    }
    n->u.buf.ptr[o] = 0;
    n->u.buf.len = o;
    return n;
}

static bp_node_t *parse_object(parse_ctx_t *c, uint64_t index, int depth);

static bp_node_t *parse_ref(parse_ctx_t *c, const uint8_t *ref, int depth) {
    return parse_object(c, read_be(ref, c->ref_size), depth);
}

static bp_node_t *parse_object(parse_ctx_t *c, uint64_t index, int depth) {
    size_t off;
    if (depth > BP_MAX_DEPTH || ++c->nodes > BP_MAX_NODES || !object_offset(c, index, &off)) {
        return NULL;
    }
    const uint8_t *d = c->data;
    uint8_t marker = d[off];
    uint8_t high = marker >> 4;
    uint8_t low = marker & 0x0f;
    size_t count;
    size_t header;

    switch (high) {
        case 0x0:
            if (marker == 0x00) {
                return node_new(BP_NULL);
            }
            if (marker == 0x08 || marker == 0x09) {
                return bp_new_bool(marker == 0x09);
            }
            return NULL;
        case 0x1: {
            if (low > 4) {
                return NULL;
            }
            size_t nbytes = (size_t) 1 << low;
            if (!fits(c, off, 1, nbytes, 1)) {
                return NULL;
            }
            bp_node_t *n = node_new(BP_INT);
            if (!n) {
                return NULL;
            }
            if (nbytes == 16) {
                /* 128-bit integers only appear for values beyond INT64_MAX */
                n->u.i.raw = read_be(d + off + 9, 8);
            } else {
                n->u.i.raw = read_be(d + off + 1, nbytes);
                n->u.i.negative = (nbytes == 8) && (n->u.i.raw >> 63);
            }
            return n;
        }
        case 0x2:
        case 0x3: {
            if ((high == 0x2 && low != 2 && low != 3) || (high == 0x3 && marker != 0x33)) {
                return NULL;
            }
            size_t nbytes = (size_t) 1 << low;
            if (!fits(c, off, 1, nbytes, 1)) {
                return NULL;
            }
            bp_node_t *n = node_new(high == 0x2 ? BP_REAL : BP_DATE);
            if (!n) {
                return NULL;
            }
            if (nbytes == 4) {
                uint32_t u = (uint32_t) read_be(d + off + 1, 4);
                float f;
                memcpy(&f, &u, 4);
                n->u.r = f;
            } else {
                uint64_t u = read_be(d + off + 1, 8);
                memcpy(&n->u.r, &u, 8);
            }
            return n;
        }
        case 0x4:
        case 0x5:
            if (!read_count(c, off, low, &count, &header) || !fits(c, off, header, count, 1)) {
                return NULL;
            }
            if (high == 0x5) {
                for (size_t i = 0; i < count; i++) {
                    uint8_t ch = d[off + header + i];
                    if (ch == 0 || ch > 0x7f) {
                        return NULL;
                    }
                }
            }
            return new_buffer_node(high == 0x4 ? BP_DATA : BP_STRING, d + off + header, count);
        case 0x6:
            if (!read_count(c, off, low, &count, &header) || !fits(c, off, header, count, 2)) {
                return NULL;
            }
            return utf16be_to_string(d + off + header, count);
        case 0x8:
            if (!fits(c, off, 1, (size_t) low + 1, 1)) {
                return NULL;
            }
            return new_buffer_node(BP_UID, d + off + 1, (size_t) low + 1);
        case 0xa:
        case 0xc: {
            if (!read_count(c, off, low, &count, &header) || !fits(c, off, header, count, c->ref_size)) {
                return NULL;
            }
            bp_node_t *arr = node_new(BP_ARRAY);
            if (!arr || !coll_reserve(arr, count ? count : 1)) {
                bp_free(arr);
                return NULL;
            }
            for (size_t i = 0; i < count; i++) {
                bp_node_t *item = parse_ref(c, d + off + header + i * c->ref_size, depth + 1);
                if (!item) {
                    bp_free(arr);
                    return NULL;
                }
                arr->u.coll.items[arr->u.coll.count++] = item;
            }
            return arr;
        }
        case 0xd: {
            if (!read_count(c, off, low, &count, &header) || !fits(c, off, header, count, 2 * (size_t) c->ref_size)) {
                return NULL;
            }
            bp_node_t *dict = node_new(BP_DICT);
            if (!dict || !coll_reserve(dict, count ? count : 1)) {
                bp_free(dict);
                return NULL;
            }
            const uint8_t *keys = d + off + header;
            const uint8_t *values = keys + count * c->ref_size;
            for (size_t i = 0; i < count; i++) {
                bp_node_t *key = parse_ref(c, keys + i * c->ref_size, depth + 1);
                if (!key || key->type != BP_STRING) {
                    bp_free(key);
                    bp_free(dict);
                    return NULL;
                }
                bp_node_t *value = parse_ref(c, values + i * c->ref_size, depth + 1);
                if (!value) {
                    bp_free(key);
                    bp_free(dict);
                    return NULL;
                }
                dict->u.coll.keys[dict->u.coll.count] = (char *) key->u.buf.ptr;
                key->u.buf.ptr = NULL;
                bp_free(key);
                dict->u.coll.items[dict->u.coll.count++] = value;
            }
            return dict;
        }
        default:
            return NULL;
    }
}

bp_node_t *bp_parse(const uint8_t *data, size_t len) {
    if (!data || len < 8 + 32 || memcmp(data, "bplist00", 8) != 0) {
        return NULL;
    }
    const uint8_t *trailer = data + len - 32;
    parse_ctx_t c;
    memset(&c, 0, sizeof(c));
    c.data = data;
    c.offset_size = trailer[6];
    c.ref_size = trailer[7];
    c.num_objects = read_be(trailer + 8, 8);
    uint64_t top = read_be(trailer + 16, 8);
    uint64_t table = read_be(trailer + 24, 8);

    bool size_ok = (c.offset_size == 1 || c.offset_size == 2 || c.offset_size == 4 || c.offset_size == 8) &&
                   (c.ref_size == 1 || c.ref_size == 2 || c.ref_size == 4 || c.ref_size == 8);
    if (!size_ok || c.num_objects == 0 || c.num_objects > len || top >= c.num_objects ||
        table < 8 || table > len - 32) {
        return NULL;
    }
    if (c.num_objects > (len - 32 - table) / c.offset_size) {
        return NULL;
    }
    c.offset_table = (size_t) table;
    c.objects_end = (size_t) table;
    return parse_object(&c, top, 0);
}

/* ------------------------------------------------------------------------- */
/* writer                                                                     */

typedef struct {
    uint8_t *buf;
    size_t len;
    size_t cap;
    bool failed;
} wbuf_t;

static void wb_put(wbuf_t *w, const void *data, size_t n) {
    if (w->failed) {
        return;
    }
    if (w->len + n > w->cap) {
        size_t cap = w->cap ? w->cap * 2 : 256;
        while (cap < w->len + n) {
            cap *= 2;
        }
        uint8_t *nb = (uint8_t *) realloc(w->buf, cap);
        if (!nb) {
            w->failed = true;
            return;
        }
        w->buf = nb;
        w->cap = cap;
    }
    memcpy(w->buf + w->len, data, n);
    w->len += n;
}

static void wb_byte(wbuf_t *w, uint8_t b) {
    wb_put(w, &b, 1);
}

static void wb_be(wbuf_t *w, uint64_t v, size_t n) {
    uint8_t tmp[8];
    for (size_t i = 0; i < n; i++) {
        tmp[n - 1 - i] = (uint8_t) (v >> (8 * i));
    }
    wb_put(w, tmp, n);
}

static void write_uint_object(wbuf_t *w, uint64_t v) {
    if (v <= 0xff) {
        wb_byte(w, 0x10);
        wb_be(w, v, 1);
    } else if (v <= 0xffff) {
        wb_byte(w, 0x11);
        wb_be(w, v, 2);
    } else if (v <= 0xffffffffULL) {
        wb_byte(w, 0x12);
        wb_be(w, v, 4);
    } else if (v <= (uint64_t) INT64_MAX) {
        wb_byte(w, 0x13);
        wb_be(w, v, 8);
    } else {
        wb_byte(w, 0x14);
        wb_be(w, 0, 8);
        wb_be(w, v, 8);
    }
}

static void write_marker(wbuf_t *w, uint8_t type, size_t count) {
    if (count < 15) {
        wb_byte(w, (uint8_t) (type | count));
    } else {
        wb_byte(w, (uint8_t) (type | 0x0f));
        write_uint_object(w, count);
    }
}

static bool is_ascii(const uint8_t *s, size_t len) {
    for (size_t i = 0; i < len; i++) {
        if (s[i] > 0x7f) {
            return false;
        }
    }
    return true;
}

/* Decodes one UTF-8 sequence; invalid input yields U+FFFD and consumes one byte. */
static uint32_t next_codepoint(const uint8_t *s, size_t len, size_t *i) {
    uint8_t c = s[*i];
    uint32_t cp;
    size_t n;
    if (c < 0x80) {
        (*i)++;
        return c;
    } else if ((c & 0xe0) == 0xc0) {
        cp = c & 0x1f;
        n = 1;
    } else if ((c & 0xf0) == 0xe0) {
        cp = c & 0x0f;
        n = 2;
    } else if ((c & 0xf8) == 0xf0) {
        cp = c & 0x07;
        n = 3;
    } else {
        (*i)++;
        return 0xfffd;
    }
    for (size_t k = 1; k <= n; k++) {
        if (*i + k >= len || (s[*i + k] & 0xc0) != 0x80) {
            (*i)++;
            return 0xfffd;
        }
        cp = (cp << 6) | (s[*i + k] & 0x3f);
    }
    *i += n + 1;
    if (cp > 0x10ffff || (cp >= 0xd800 && cp <= 0xdfff)) {
        return 0xfffd;
    }
    return cp;
}

static void write_string_object(wbuf_t *w, const uint8_t *s, size_t len) {
    if (is_ascii(s, len)) {
        write_marker(w, 0x50, len);
        wb_put(w, s, len);
        return;
    }
    /* count UTF-16 code units first */
    size_t units = 0;
    for (size_t i = 0; i < len;) {
        uint32_t cp = next_codepoint(s, len, &i);
        units += cp >= 0x10000 ? 2 : 1;
    }
    write_marker(w, 0x60, units);
    for (size_t i = 0; i < len;) {
        uint32_t cp = next_codepoint(s, len, &i);
        if (cp >= 0x10000) {
            cp -= 0x10000;
            wb_be(w, 0xd800 + (cp >> 10), 2);
            wb_be(w, 0xdc00 + (cp & 0x3ff), 2);
        } else {
            wb_be(w, cp, 2);
        }
    }
}

/* Number of objects in the subtree (dictionary keys count as objects). */
static size_t subtree_count(const bp_node_t *n, int depth, bool *ok) {
    if (depth > BP_MAX_DEPTH) {
        *ok = false;
        return 0;
    }
    size_t total = 1;
    if (n->type == BP_ARRAY || n->type == BP_DICT) {
        for (size_t i = 0; i < n->u.coll.count; i++) {
            total += subtree_count(n->u.coll.items[i], depth + 1, ok);
            if (n->type == BP_DICT) {
                total++;
            }
        }
    }
    return total;
}

typedef struct {
    wbuf_t w;
    uint64_t *offsets;
    size_t ref_size;
} write_ctx_t;

static size_t write_node(write_ctx_t *ctx, const bp_node_t *n, size_t index);

static size_t write_node(write_ctx_t *ctx, const bp_node_t *n, size_t index) {
    wbuf_t *w = &ctx->w;
    ctx->offsets[index] = w->len;
    size_t next = index + 1;
    switch (n->type) {
        case BP_NULL:
            wb_byte(w, 0x00);
            break;
        case BP_BOOL:
            wb_byte(w, n->u.b ? 0x09 : 0x08);
            break;
        case BP_INT:
            if (n->u.i.negative) {
                wb_byte(w, 0x13);
                wb_be(w, n->u.i.raw, 8);
            } else {
                write_uint_object(w, n->u.i.raw);
            }
            break;
        case BP_REAL:
        case BP_DATE: {
            uint64_t bits;
            memcpy(&bits, &n->u.r, 8);
            wb_byte(w, n->type == BP_REAL ? 0x23 : 0x33);
            wb_be(w, bits, 8);
            break;
        }
        case BP_DATA:
            write_marker(w, 0x40, n->u.buf.len);
            wb_put(w, n->u.buf.ptr, n->u.buf.len);
            break;
        case BP_STRING:
            write_string_object(w, n->u.buf.ptr, n->u.buf.len);
            break;
        case BP_UID:
            wb_byte(w, (uint8_t) (0x80 | ((n->u.buf.len - 1) & 0x0f)));
            wb_put(w, n->u.buf.ptr, n->u.buf.len);
            break;
        case BP_ARRAY: {
            size_t count = n->u.coll.count;
            write_marker(w, 0xa0, count);
            size_t child = next;
            bool ok = true;
            for (size_t i = 0; i < count; i++) {
                wb_be(w, child, ctx->ref_size);
                child += subtree_count(n->u.coll.items[i], 0, &ok);
            }
            for (size_t i = 0; i < count; i++) {
                next = write_node(ctx, n->u.coll.items[i], next);
            }
            break;
        }
        case BP_DICT: {
            size_t count = n->u.coll.count;
            write_marker(w, 0xd0, count);
            for (size_t i = 0; i < count; i++) {
                wb_be(w, next + i, ctx->ref_size);
            }
            size_t child = next + count;
            bool ok = true;
            for (size_t i = 0; i < count; i++) {
                wb_be(w, child, ctx->ref_size);
                child += subtree_count(n->u.coll.items[i], 0, &ok);
            }
            for (size_t i = 0; i < count; i++) {
                ctx->offsets[next] = w->len;
                write_string_object(w, (const uint8_t *) n->u.coll.keys[i], strlen(n->u.coll.keys[i]));
                next++;
            }
            for (size_t i = 0; i < count; i++) {
                next = write_node(ctx, n->u.coll.items[i], next);
            }
            break;
        }
    }
    return next;
}

int bp_write(const bp_node_t *root, uint8_t **out, size_t *out_len) {
    if (!root || !out || !out_len) {
        return -1;
    }
    bool ok = true;
    size_t total = subtree_count(root, 0, &ok);
    if (!ok || total > BP_MAX_NODES) {
        return -1;
    }
    write_ctx_t ctx;
    memset(&ctx, 0, sizeof(ctx));
    ctx.offsets = (uint64_t *) calloc(total, sizeof(uint64_t));
    if (!ctx.offsets) {
        return -1;
    }
    ctx.ref_size = total <= 0xff ? 1 : (total <= 0xffff ? 2 : 4);

    wb_put(&ctx.w, "bplist00", 8);
    size_t written = write_node(&ctx, root, 0);

    uint64_t table_offset = ctx.w.len;
    uint8_t offset_size = table_offset <= 0xff ? 1 : (table_offset <= 0xffff ? 2 : (table_offset <= 0xffffffffULL ? 4 : 8));
    for (size_t i = 0; i < total; i++) {
        wb_be(&ctx.w, ctx.offsets[i], offset_size);
    }
    uint8_t trailer[32] = { 0 };
    trailer[6] = offset_size;
    trailer[7] = (uint8_t) ctx.ref_size;
    wr64be(trailer + 8, total);
    wr64be(trailer + 16, 0);
    wr64be(trailer + 24, table_offset);
    wb_put(&ctx.w, trailer, sizeof(trailer));
    free(ctx.offsets);

    if (ctx.w.failed || written != total) {
        free(ctx.w.buf);
        return -1;
    }
    *out = ctx.w.buf;
    *out_len = ctx.w.len;
    return 0;
}
