/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#include "crypto.h"

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

#include "mbedtls/gcm.h"
#include "mbedtls/md5.h"
#include "mbedtls/sha1.h"
#include "mbedtls/sha512.h"
#include "monocypher.h"
#include "monocypher-ed25519.h"

#if defined(__linux__) && !defined(__ANDROID__)
#include <sys/random.h>
#endif

int crypto_random(void *buf, size_t len) {
#if defined(__ANDROID__) || defined(__APPLE__) || defined(__FreeBSD__)
    arc4random_buf(buf, len);
    return 0;
#elif defined(__linux__)
    uint8_t *p = (uint8_t *) buf;
    while (len > 0) {
        ssize_t n = getrandom(p, len, 0);
        if (n < 0) {
            if (errno == EINTR) {
                continue;
            }
            return -1;
        }
        p += n;
        len -= (size_t) n;
    }
    return 0;
#else
    int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        return -1;
    }
    uint8_t *p = (uint8_t *) buf;
    while (len > 0) {
        ssize_t n = read(fd, p, len);
        if (n <= 0) {
            if (n < 0 && errno == EINTR) {
                continue;
            }
            close(fd);
            return -1;
        }
        p += n;
        len -= (size_t) n;
    }
    close(fd);
    return 0;
#endif
}

int aes_ctr_init(aes_ctr_t *ctx, const uint8_t key[16], const uint8_t iv[16]) {
    memset(ctx, 0, sizeof(*ctx));
    mbedtls_aes_init(&ctx->aes);
    if (mbedtls_aes_setkey_enc(&ctx->aes, key, 128) != 0) {
        return -1;
    }
    memcpy(ctx->counter, iv, 16);
    ctx->offset = 0;
    return 0;
}

void aes_ctr_xcrypt(aes_ctr_t *ctx, const uint8_t *in, uint8_t *out, size_t len) {
    if (len) {
        mbedtls_aes_crypt_ctr(&ctx->aes, len, &ctx->offset, ctx->counter, ctx->stream_block, in, out);
    }
}

void aes_ctr_free(aes_ctr_t *ctx) {
    mbedtls_aes_free(&ctx->aes);
    secure_zero(ctx, sizeof(*ctx));
}

int aes_cbc_init_decrypt(aes_cbc_t *ctx, const uint8_t key[16], const uint8_t iv[16]) {
    memset(ctx, 0, sizeof(*ctx));
    mbedtls_aes_init(&ctx->aes);
    if (mbedtls_aes_setkey_dec(&ctx->aes, key, 128) != 0) {
        return -1;
    }
    memcpy(ctx->iv, iv, 16);
    return 0;
}

void aes_cbc_decrypt_packet(aes_cbc_t *ctx, uint8_t *buf, size_t len) {
    size_t blocks = len & ~(size_t) 15;
    if (blocks) {
        uint8_t iv[16];
        memcpy(iv, ctx->iv, 16);
        mbedtls_aes_crypt_cbc(&ctx->aes, MBEDTLS_AES_DECRYPT, blocks, iv, buf, buf);
    }
}

void aes_cbc_free(aes_cbc_t *ctx) {
    mbedtls_aes_free(&ctx->aes);
    secure_zero(ctx, sizeof(*ctx));
}

int aes_gcm_encrypt(const uint8_t key[16], const uint8_t iv[16], const uint8_t *in, size_t len,
                    uint8_t *out, uint8_t tag[16]) {
    mbedtls_gcm_context gcm;
    mbedtls_gcm_init(&gcm);
    int ret = mbedtls_gcm_setkey(&gcm, MBEDTLS_CIPHER_ID_AES, key, 128);
    if (ret == 0) {
        ret = mbedtls_gcm_crypt_and_tag(&gcm, MBEDTLS_GCM_ENCRYPT, len, iv, 16, NULL, 0, in, out, 16, tag);
    }
    mbedtls_gcm_free(&gcm);
    return ret == 0 ? 0 : -1;
}

int aes_gcm_decrypt(const uint8_t key[16], const uint8_t iv[16], const uint8_t *in, size_t len,
                    uint8_t *out, const uint8_t tag[16]) {
    mbedtls_gcm_context gcm;
    mbedtls_gcm_init(&gcm);
    int ret = mbedtls_gcm_setkey(&gcm, MBEDTLS_CIPHER_ID_AES, key, 128);
    if (ret == 0) {
        ret = mbedtls_gcm_auth_decrypt(&gcm, len, iv, 16, NULL, 0, tag, 16, in, out);
    }
    mbedtls_gcm_free(&gcm);
    return ret == 0 ? 0 : -1;
}

void sha512_2(const void *a, size_t alen, const void *b, size_t blen, uint8_t out[SHA512_SIZE]) {
    mbedtls_sha512_context ctx;
    mbedtls_sha512_init(&ctx);
    mbedtls_sha512_starts(&ctx, 0);
    if (alen) {
        mbedtls_sha512_update(&ctx, (const unsigned char *) a, alen);
    }
    if (blen) {
        mbedtls_sha512_update(&ctx, (const unsigned char *) b, blen);
    }
    mbedtls_sha512_finish(&ctx, out);
    mbedtls_sha512_free(&ctx);
}

void sha1_buf(const void *data, size_t len, uint8_t out[SHA1_SIZE]) {
    mbedtls_sha1((const unsigned char *) data, len, out);
}

void md5_hex(const char *s, char out[33]) {
    static const char digits[] = "0123456789abcdef";
    uint8_t digest[16];
    mbedtls_md5((const unsigned char *) s, strlen(s), digest);
    for (int i = 0; i < 16; i++) {
        out[2 * i] = digits[digest[i] >> 4];
        out[2 * i + 1] = digits[digest[i] & 0xf];
    }
    out[32] = '\0';
}

int crypto_memcmp(const void *a, const void *b, size_t len) {
    const volatile uint8_t *x = (const volatile uint8_t *) a;
    const volatile uint8_t *y = (const volatile uint8_t *) b;
    uint8_t diff = 0;
    for (size_t i = 0; i < len; i++) {
        diff |= (uint8_t) (x[i] ^ y[i]);
    }
    return diff != 0;
}

int x25519_generate(uint8_t secret[X25519_KEY_SIZE], uint8_t public_key[X25519_KEY_SIZE]) {
    if (crypto_random(secret, X25519_KEY_SIZE) != 0) {
        return -1;
    }
    crypto_x25519_public_key(public_key, secret);
    return 0;
}

int x25519_shared(uint8_t out[X25519_KEY_SIZE], const uint8_t secret[X25519_KEY_SIZE],
                  const uint8_t peer_public[X25519_KEY_SIZE]) {
    static const uint8_t zero[X25519_KEY_SIZE] = { 0 };
    crypto_x25519(out, secret, peer_public);
    return crypto_memcmp(out, zero, X25519_KEY_SIZE) == 0 ? -1 : 0;
}

void ed25519_from_seed(const uint8_t seed[32], uint8_t secret[ED25519_SECRET_SIZE],
                       uint8_t public_key[ED25519_KEY_SIZE]) {
    uint8_t copy[32];
    memcpy(copy, seed, 32); /* the key pair function wipes its seed argument */
    crypto_ed25519_key_pair(secret, public_key, copy);
    secure_zero(copy, sizeof(copy));
}

void ed25519_sign(uint8_t sig[ED25519_SIG_SIZE], const uint8_t secret[ED25519_SECRET_SIZE],
                  const uint8_t *msg, size_t len) {
    crypto_ed25519_sign(sig, secret, msg, len);
}

int ed25519_verify(const uint8_t sig[ED25519_SIG_SIZE], const uint8_t public_key[ED25519_KEY_SIZE],
                   const uint8_t *msg, size_t len) {
    return crypto_ed25519_check(sig, public_key, msg, len) == 0 ? 0 : -1;
}
