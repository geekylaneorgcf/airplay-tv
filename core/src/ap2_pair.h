/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky and contributors
 *
 * AirPlay 2 pairing and the encrypted control channel: the HomeKit-style TLV8 messages, transient
 * pair-setup (SRP-6a with a 3072-bit group and SHA-512, fixed PIN 3939), the keys derived from its
 * session key, and the ChaCha20-Poly1305 framing every message uses afterwards.
 *
 * Written from the open descriptions of the protocol (the pair_ap library by ejurgensen and the
 * shairport-sync AirPlay 2 receiver, both MIT-licensed) and checked against pair_ap's client in
 * core/tests (ap2_interop). Only transient pairing is offered: a phone that has no pairing with this
 * receiver does the two-step setup and goes on, nothing is stored.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#ifndef AIRPLAYTV_AP2_PAIR_H
#define AIRPLAYTV_AP2_PAIR_H

#include "common.h"

#define AP2_SHARED_LEN 64        /* the SHA-512 of the SRP secret */
#define AP2_TAG_LEN 16
#define AP2_BLOCK_MAX 1024       /* plaintext bytes in one encrypted block of the control channel */
#define AP2_PIN "3939"

/* ---- TLV8 -------------------------------------------------------------------------------------------- */

enum {
    TLV_METHOD = 0x00,
    TLV_IDENTIFIER = 0x01,
    TLV_SALT = 0x02,
    TLV_PUBLIC_KEY = 0x03,
    TLV_PROOF = 0x04,
    TLV_ENCRYPTED = 0x05,
    TLV_STATE = 0x06,
    TLV_ERROR = 0x07,
    TLV_SIGNATURE = 0x0A,
    TLV_FLAGS = 0x13,
};

#define TLV_FLAG_TRANSIENT 0x10
#define TLV_ERROR_AUTHENTICATION 0x02
#define TLV_ERROR_UNAVAILABLE 0x06

#define TLV_MAX_ITEMS 12

typedef struct {
    uint8_t type;
    const uint8_t *data;   /* points into the parsed buffer, or into the builder's storage */
    size_t len;
} tlv_item_t;

typedef struct {
    tlv_item_t items[TLV_MAX_ITEMS];
    int count;
    uint8_t *joined;       /* storage for values that were split in fragments, freed by tlv_free */
    size_t joined_len;
} tlv_t;

/* Parses TLV8 data; fragments (a type repeated back to back with full 255-byte pieces) are joined. 0 on success. */
int tlv_parse(tlv_t *out, const uint8_t *data, size_t len);
const tlv_item_t *tlv_get(const tlv_t *t, uint8_t type);
void tlv_free(tlv_t *t);

/* Appends one value to a growing buffer, splitting it into 255-byte pieces. Returns false when the buffer is full. */
bool tlv_put(uint8_t *buf, size_t cap, size_t *used, uint8_t type, const void *value, size_t len);

/* ---- pair-setup (transient) -------------------------------------------------------------------------- */

typedef struct ap2_pair ap2_pair_t;

ap2_pair_t *ap2_pair_new(const char *pin);
void ap2_pair_free(ap2_pair_t *p);

/* Handles one POST /pair-setup body. On return 0 *out is a malloc'd reply body (a TLV error reply included: the pairing
 * failed but the answer is well formed); on -1 nothing is to be sent and the connection should be closed. */
int ap2_pair_setup(ap2_pair_t *p, const uint8_t *in, size_t in_len, uint8_t **out, size_t *out_len);

/* True once the second step succeeded; shared receives the 64-byte secret the channel keys come from. */
bool ap2_pair_done(const ap2_pair_t *p, uint8_t shared[AP2_SHARED_LEN]);

/* The reply to a POST /pair-verify: this receiver has no stored pairings, so the answer is "authentication failed",
 * which makes the phone do a transient setup instead. */
int ap2_pair_verify_refusal(const uint8_t *in, size_t in_len, uint8_t **out, size_t *out_len);

/* ---- the encrypted channel --------------------------------------------------------------------------- */

typedef struct {
    uint8_t write_key[32];     /* what this side seals with */
    uint8_t read_key[32];      /* what this side opens with */
    uint64_t write_counter;
    uint64_t read_counter;
    bool on;
} ap2_cipher_t;

typedef enum {
    AP2_CHANNEL_CONTROL = 0,   /* RTSP requests and replies */
    AP2_CHANNEL_EVENTS = 1,    /* the event connection: the receiver speaks to the sender on it */
} ap2_channel_t;

/* Derives the keys of a channel from the pairing secret. [server] true for the receiver's side. */
void ap2_cipher_init(ap2_cipher_t *c, const uint8_t *shared, size_t shared_len, ap2_channel_t channel, bool server);

/* Seals plain into frames [len LE16 | ciphertext | tag] of at most AP2_BLOCK_MAX bytes each; returns the bytes written,
 * or 0 when out_cap is too small (needs plain_len + 18 per block). */
size_t ap2_seal(ap2_cipher_t *c, const uint8_t *plain, size_t plain_len, uint8_t *out, size_t out_cap);

/* Opens the whole frames at the start of in and appends their plaintext to out (cap out_cap). *consumed is how much of in
 * was used; a partial frame is left. Returns 0, or -1 when a frame does not authenticate (the connection must be closed). */
int ap2_open(ap2_cipher_t *c, const uint8_t *in, size_t in_len, uint8_t *out, size_t out_cap, size_t *out_len, size_t *consumed);

/* The ChaCha20-Poly1305 construction of RFC 8439 for one message with a 12-byte nonce (exposed for tests and audio). */
void ap2_aead_seal(const uint8_t key[32], const uint8_t nonce[12], const uint8_t *ad, size_t ad_len, const uint8_t *plain,
                   size_t len, uint8_t *cipher, uint8_t tag[AP2_TAG_LEN]);
int ap2_aead_open(const uint8_t key[32], const uint8_t nonce[12], const uint8_t *ad, size_t ad_len, const uint8_t *cipher,
                  size_t len, const uint8_t tag[AP2_TAG_LEN], uint8_t *plain);

/* HKDF-SHA-512 with a text salt and info, 32 bytes out. */
void ap2_hkdf32(uint8_t out[32], const uint8_t *ikm, size_t ikm_len, const char *salt, const char *info);

#endif
