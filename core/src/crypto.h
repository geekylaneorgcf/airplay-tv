/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#ifndef AIRPLAYTV_CRYPTO_H
#define AIRPLAYTV_CRYPTO_H

#include "common.h"

#include "mbedtls/aes.h"

#define X25519_KEY_SIZE 32
#define ED25519_KEY_SIZE 32
#define ED25519_SECRET_SIZE 64
#define ED25519_SIG_SIZE 64
#define SHA512_SIZE 64
#define SHA1_SIZE 20

/* Fills buf with cryptographically secure random bytes. Returns 0 on success. */
int crypto_random(void *buf, size_t len);

/* AES-128 in CTR mode with a continuous keystream across calls. */
typedef struct {
    mbedtls_aes_context aes;
    uint8_t counter[16];
    uint8_t stream_block[16];
    size_t offset;
} aes_ctr_t;

int aes_ctr_init(aes_ctr_t *ctx, const uint8_t key[16], const uint8_t iv[16]);
void aes_ctr_xcrypt(aes_ctr_t *ctx, const uint8_t *in, uint8_t *out, size_t len);
void aes_ctr_free(aes_ctr_t *ctx);

/* AES-128-CBC decryption where the IV restarts for every call (RAOP audio packets). */
typedef struct {
    mbedtls_aes_context aes;
    uint8_t iv[16];
} aes_cbc_t;

int aes_cbc_init_decrypt(aes_cbc_t *ctx, const uint8_t key[16], const uint8_t iv[16]);
/* Decrypts the leading whole 16-byte blocks of buf in place; a trailing partial block
 * is left untouched, matching the RAOP packet format. */
void aes_cbc_decrypt_packet(aes_cbc_t *ctx, uint8_t *buf, size_t len);
void aes_cbc_free(aes_cbc_t *ctx);

/* AES-128-GCM with a 16-byte IV and a 16-byte tag. Return 0 on success,
 * aes_gcm_decrypt returns -1 when authentication fails. */
int aes_gcm_encrypt(const uint8_t key[16], const uint8_t iv[16], const uint8_t *in, size_t len,
                    uint8_t *out, uint8_t tag[16]);
int aes_gcm_decrypt(const uint8_t key[16], const uint8_t iv[16], const uint8_t *in, size_t len,
                    uint8_t *out, const uint8_t tag[16]);

/* SHA-512 over the concatenation a || b (either part may be empty). */
void sha512_2(const void *a, size_t alen, const void *b, size_t blen, uint8_t out[SHA512_SIZE]);

void sha1_buf(const void *data, size_t len, uint8_t out[SHA1_SIZE]);

/* Lowercase hex MD5 of a NUL-terminated string (33 bytes incl. NUL). */
void md5_hex(const char *s, char out[33]);

/* Constant-time comparison. Returns 0 when equal. */
int crypto_memcmp(const void *a, const void *b, size_t len);

/* X25519 */
int x25519_generate(uint8_t secret[X25519_KEY_SIZE], uint8_t public_key[X25519_KEY_SIZE]);
/* Returns -1 if the peer key produces an all-zero shared secret. */
int x25519_shared(uint8_t out[X25519_KEY_SIZE], const uint8_t secret[X25519_KEY_SIZE],
                  const uint8_t peer_public[X25519_KEY_SIZE]);

/* Ed25519 (RFC 8032). The 64-byte secret is derived from a 32-byte seed. */
void ed25519_from_seed(const uint8_t seed[32], uint8_t secret[ED25519_SECRET_SIZE],
                       uint8_t public_key[ED25519_KEY_SIZE]);
void ed25519_sign(uint8_t sig[ED25519_SIG_SIZE], const uint8_t secret[ED25519_SECRET_SIZE],
                  const uint8_t *msg, size_t len);
/* Returns 0 when the signature is valid. */
int ed25519_verify(const uint8_t sig[ED25519_SIG_SIZE], const uint8_t public_key[ED25519_KEY_SIZE],
                   const uint8_t *msg, size_t len);

#endif
