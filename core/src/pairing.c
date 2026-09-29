/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky
 *
 * Legacy AirPlay pairing, following shairplay/RPiPlay/UxPlay pairing.c
 * (Juho Vähä-Herttua, Jaslo Ziska, F. Duncanh; LGPL-2.1-or-later).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#include "pairing.h"

#include "util.h"

#define SALT_VERIFY_KEY "Pair-Verify-AES-Key"
#define SALT_VERIFY_IV "Pair-Verify-AES-IV"
#define SALT_SETUP_KEY "Pair-Setup-AES-Key"
#define SALT_SETUP_IV "Pair-Setup-AES-IV"

void pairing_identity_init(pairing_identity_t *id, const uint8_t seed[32]) {
    ed25519_from_seed(seed, id->secret, id->public_key);
}

void pairing_session_init(pairing_session_t *s) {
    memset(s, 0, sizeof(*s));
}

void pairing_session_clear(pairing_session_t *s) {
    srp_server_free(s->srp);
    secure_zero(s, sizeof(*s));
}

static void derive_verify_key(const pairing_session_t *s, uint8_t key[16], uint8_t iv[16]) {
    uint8_t hash[SHA512_SIZE];
    sha512_2(SALT_VERIFY_KEY, strlen(SALT_VERIFY_KEY), s->shared_secret, X25519_KEY_SIZE, hash);
    memcpy(key, hash, 16);
    sha512_2(SALT_VERIFY_IV, strlen(SALT_VERIFY_IV), s->shared_secret, X25519_KEY_SIZE, hash);
    memcpy(iv, hash, 16);
    secure_zero(hash, sizeof(hash));
}

int pairing_verify_start(pairing_session_t *s, const pairing_identity_t *id,
                         const uint8_t client_ecdh[X25519_KEY_SIZE], const uint8_t client_ed[ED25519_KEY_SIZE],
                         uint8_t reply[PAIR_VERIFY_REPLY_LEN]) {
    if (s->state == PAIR_STATE_VERIFIED) {
        return -1;
    }
    memcpy(s->ecdh_public_theirs, client_ecdh, X25519_KEY_SIZE);
    memcpy(s->ed_public_theirs, client_ed, ED25519_KEY_SIZE);
    if (x25519_generate(s->ecdh_secret_ours, s->ecdh_public_ours) != 0 ||
        x25519_shared(s->shared_secret, s->ecdh_secret_ours, s->ecdh_public_theirs) != 0) {
        return -1;
    }
    s->have_shared_secret = true;

    /* Sign our and their ECDH public keys, then encrypt the signature. */
    uint8_t message[2 * X25519_KEY_SIZE];
    uint8_t signature[ED25519_SIG_SIZE];
    memcpy(message, s->ecdh_public_ours, X25519_KEY_SIZE);
    memcpy(message + X25519_KEY_SIZE, s->ecdh_public_theirs, X25519_KEY_SIZE);
    ed25519_sign(signature, id->secret, message, sizeof(message));

    uint8_t key[16];
    uint8_t iv[16];
    aes_ctr_t ctr;
    derive_verify_key(s, key, iv);
    if (aes_ctr_init(&ctr, key, iv) != 0) {
        return -1;
    }
    aes_ctr_xcrypt(&ctr, signature, signature, sizeof(signature));
    aes_ctr_free(&ctr);
    secure_zero(key, sizeof(key));

    memcpy(reply, s->ecdh_public_ours, X25519_KEY_SIZE);
    memcpy(reply + X25519_KEY_SIZE, signature, ED25519_SIG_SIZE);
    s->state = PAIR_STATE_VERIFYING;
    return 0;
}

int pairing_verify_finish(pairing_session_t *s, const uint8_t encrypted_signature[ED25519_SIG_SIZE]) {
    if (s->state != PAIR_STATE_VERIFYING) {
        return -1;
    }
    uint8_t key[16];
    uint8_t iv[16];
    uint8_t skip[ED25519_SIG_SIZE] = { 0 };
    uint8_t signature[ED25519_SIG_SIZE];
    aes_ctr_t ctr;
    derive_verify_key(s, key, iv);
    if (aes_ctr_init(&ctr, key, iv) != 0) {
        return -1;
    }
    /* The keystream continues from the server's own signature message. */
    aes_ctr_xcrypt(&ctr, skip, skip, sizeof(skip));
    aes_ctr_xcrypt(&ctr, encrypted_signature, signature, sizeof(signature));
    aes_ctr_free(&ctr);
    secure_zero(key, sizeof(key));

    uint8_t message[2 * X25519_KEY_SIZE];
    memcpy(message, s->ecdh_public_theirs, X25519_KEY_SIZE);
    memcpy(message + X25519_KEY_SIZE, s->ecdh_public_ours, X25519_KEY_SIZE);
    if (ed25519_verify(signature, s->ed_public_theirs, message, sizeof(message)) != 0) {
        return -1;
    }
    s->state = PAIR_STATE_VERIFIED;
    return 0;
}

bool pairing_shared_secret(const pairing_session_t *s, uint8_t out[X25519_KEY_SIZE]) {
    if (!s->have_shared_secret) {
        return false;
    }
    memcpy(out, s->shared_secret, X25519_KEY_SIZE);
    return true;
}

int pairing_pin_start(pairing_session_t *s, const char *username, const char *pin,
                      uint8_t salt[SRP_SALT_LEN], uint8_t *server_public, size_t *server_public_len) {
    srp_server_free(s->srp);
    s->srp = srp_server_start(username, pin, salt, server_public, server_public_len);
    s->srp_verified = false;
    s->pin_paired = false;
    return s->srp ? 0 : -1;
}

int pairing_pin_verify(pairing_session_t *s, const uint8_t *client_public, size_t client_public_len,
                       const uint8_t *client_proof, size_t client_proof_len, uint8_t server_proof[SRP_PROOF_LEN]) {
    if (!s->srp) {
        return -1;
    }
    int ret = srp_server_verify(s->srp, client_public, client_public_len, client_proof, client_proof_len,
                                server_proof, s->srp_key);
    srp_server_free(s->srp);
    s->srp = NULL;
    s->srp_verified = (ret == 0);
    return ret;
}

int pairing_pin_exchange(pairing_session_t *s, const pairing_identity_t *id,
                         const uint8_t client_epk[ED25519_KEY_SIZE], const uint8_t client_tag[16],
                         uint8_t reply_epk[ED25519_KEY_SIZE], uint8_t reply_tag[16]) {
    if (!s->srp_verified) {
        return -1;
    }
    uint8_t hash[SHA512_SIZE];
    uint8_t key[16];
    uint8_t iv[16];
    sha512_2(SALT_SETUP_KEY, strlen(SALT_SETUP_KEY), s->srp_key, SRP_SESSION_KEY_LEN, hash);
    memcpy(key, hash, 16);
    sha512_2(SALT_SETUP_IV, strlen(SALT_SETUP_IV), s->srp_key, SRP_SESSION_KEY_LEN, hash);
    memcpy(iv, hash, 16);
    secure_zero(hash, sizeof(hash));
    s->srp_verified = false; /* one exchange per SRP session */

    /* Apple increments the last IV byte (without carry) once per message. */
    iv[15]++;
    uint8_t client_pk[ED25519_KEY_SIZE];
    if (aes_gcm_decrypt(key, iv, client_epk, ED25519_KEY_SIZE, client_pk, client_tag) != 0) {
        secure_zero(key, sizeof(key));
        return -1;
    }
    iv[15]++;
    int ret = aes_gcm_encrypt(key, iv, id->public_key, ED25519_KEY_SIZE, reply_epk, reply_tag);
    secure_zero(key, sizeof(key));
    if (ret != 0) {
        return -1;
    }
    memcpy(s->pin_client_public, client_pk, ED25519_KEY_SIZE);
    s->pin_paired = true;
    s->state = PAIR_STATE_SETUP;
    return 0;
}
