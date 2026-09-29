/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky
 *
 * Legacy AirPlay pairing (pair-setup, pair-verify and PIN pair-setup), following
 * the protocol as implemented in shairplay/RPiPlay/UxPlay pairing.c
 * (Juho Vähä-Herttua, Jaslo Ziska, F. Duncanh; LGPL-2.1-or-later).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#ifndef AIRPLAYTV_PAIRING_H
#define AIRPLAYTV_PAIRING_H

#include "common.h"
#include "crypto.h"
#include "srp.h"

#define PAIR_VERIFY_REPLY_LEN (X25519_KEY_SIZE + ED25519_SIG_SIZE)

/* Long-term Ed25519 identity of this receiver. */
typedef struct {
    uint8_t secret[ED25519_SECRET_SIZE];
    uint8_t public_key[ED25519_KEY_SIZE];
} pairing_identity_t;

void pairing_identity_init(pairing_identity_t *id, const uint8_t seed[32]);

typedef enum {
    PAIR_STATE_INITIAL = 0,
    PAIR_STATE_SETUP,        /* pair-setup done in this connection */
    PAIR_STATE_VERIFYING,    /* pair-verify step 1 done */
    PAIR_STATE_VERIFIED,     /* pair-verify step 2 succeeded */
} pair_state_t;

typedef struct {
    pair_state_t state;
    uint8_t ecdh_secret_ours[X25519_KEY_SIZE];
    uint8_t ecdh_public_ours[X25519_KEY_SIZE];
    uint8_t ecdh_public_theirs[X25519_KEY_SIZE];
    uint8_t ed_public_theirs[ED25519_KEY_SIZE];
    uint8_t shared_secret[X25519_KEY_SIZE];
    bool have_shared_secret;

    /* PIN pairing */
    srp_server_t *srp;
    uint8_t srp_key[SRP_SESSION_KEY_LEN];
    bool srp_verified;
    uint8_t pin_client_public[ED25519_KEY_SIZE];
    bool pin_paired;
} pairing_session_t;

void pairing_session_init(pairing_session_t *s);
void pairing_session_clear(pairing_session_t *s);

/* pair-verify, first message: client X25519 and Ed25519 public keys in; our X25519
 * public key followed by the encrypted signature out. */
int pairing_verify_start(pairing_session_t *s, const pairing_identity_t *id,
                         const uint8_t client_ecdh[X25519_KEY_SIZE], const uint8_t client_ed[ED25519_KEY_SIZE],
                         uint8_t reply[PAIR_VERIFY_REPLY_LEN]);

/* pair-verify, second message: the client's encrypted signature. Returns 0 if valid. */
int pairing_verify_finish(pairing_session_t *s, const uint8_t encrypted_signature[ED25519_SIG_SIZE]);

/* Shared X25519 secret established by pair-verify step 1 (used to hash the stream key). */
bool pairing_shared_secret(const pairing_session_t *s, uint8_t out[X25519_KEY_SIZE]);

/* PIN pair-setup step 1: start SRP for the client's user name and the displayed PIN. */
int pairing_pin_start(pairing_session_t *s, const char *username, const char *pin,
                      uint8_t salt[SRP_SALT_LEN], uint8_t *server_public, size_t *server_public_len);

/* PIN pair-setup step 2: verify the client proof; writes the 20-byte server proof. */
int pairing_pin_verify(pairing_session_t *s, const uint8_t *client_public, size_t client_public_len,
                       const uint8_t *client_proof, size_t client_proof_len, uint8_t server_proof[SRP_PROOF_LEN]);

/* PIN pair-setup step 3: exchange Ed25519 keys protected by AES-GCM. */
int pairing_pin_exchange(pairing_session_t *s, const pairing_identity_t *id,
                         const uint8_t client_epk[ED25519_KEY_SIZE], const uint8_t client_tag[16],
                         uint8_t reply_epk[ED25519_KEY_SIZE], uint8_t reply_tag[16]);

#endif
