/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky
 *
 * Server side of SRP-6a for AirPlay PIN pairing. The protocol logic follows
 * csrp (Copyright (c) 2013 Tom Cocagne, MIT license) as adapted for Apple's
 * variant in UxPlay.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#include "srp.h"

#include <stdlib.h>

#include "crypto.h"
#include "mbedtls/bignum.h"
#include "mbedtls/sha1.h"
#include "util.h"

/* RFC 5054 2048-bit group, g = 2 */
static const char kModulusHex[] =
    "AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050A37329CBB4"
    "A099ED8193E0757767A13DD52312AB4B03310DCD7F48A9DA04FD50E8083969EDB767B0CF60"
    "95179A163AB3661A05FBD5FAAAE82918A9962F0B93B855F97993EC975EEAA80D740ADBF4FF"
    "747359D041D5C33EA71D281E446B14773BCA97B43A23FB801676BD207A436C6481F1D2B907"
    "8717461A5B9D32E688F87748544523B524B0D57D5EA77A2775D2ECFA032CFBDBF52FB37861"
    "60279004E57AE6AF874E7303CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8E9DB"
    "FBB694B5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73";

struct srp_server {
    mbedtls_mpi N;
    mbedtls_mpi g;
    mbedtls_mpi v;
    mbedtls_mpi b;
    mbedtls_mpi B;
    char username[64];
    uint8_t salt[SRP_SALT_LEN];
};

typedef struct {
    mbedtls_sha1_context ctx;
} hash_t;

static void hash_start(hash_t *h) {
    mbedtls_sha1_init(&h->ctx);
    mbedtls_sha1_starts(&h->ctx);
}

static void hash_update(hash_t *h, const void *data, size_t len) {
    if (len) {
        mbedtls_sha1_update(&h->ctx, (const unsigned char *) data, len);
    }
}

static void hash_finish(hash_t *h, uint8_t out[SHA1_SIZE]) {
    mbedtls_sha1_finish(&h->ctx, out);
    mbedtls_sha1_free(&h->ctx);
}

/* Big-endian bytes without leading zeros (the csrp convention). */
static int mpi_bytes(const mbedtls_mpi *x, uint8_t *buf, size_t cap, size_t *len) {
    size_t n = mbedtls_mpi_size(x);
    if (n > cap) {
        return -1;
    }
    if (mbedtls_mpi_write_binary(x, buf, n) != 0) {
        return -1;
    }
    *len = n;
    return 0;
}

static int hash_update_mpi(hash_t *h, const mbedtls_mpi *x) {
    uint8_t buf[SRP_MODULUS_LEN];
    size_t len;
    if (mpi_bytes(x, buf, sizeof(buf), &len) != 0) {
        return -1;
    }
    hash_update(h, buf, len);
    return 0;
}

/* H(PAD(a) | PAD(b)) where values are left-padded to the modulus length. */
static int hash_padded_pair(const mbedtls_mpi *a, const mbedtls_mpi *b, mbedtls_mpi *out) {
    uint8_t buf[2 * SRP_MODULUS_LEN];
    uint8_t digest[SHA1_SIZE];
    if (mbedtls_mpi_write_binary(a, buf, SRP_MODULUS_LEN) != 0 ||
        mbedtls_mpi_write_binary(b, buf + SRP_MODULUS_LEN, SRP_MODULUS_LEN) != 0) {
        return -1;
    }
    sha1_buf(buf, sizeof(buf), digest);
    return mbedtls_mpi_read_binary(out, digest, sizeof(digest)) == 0 ? 0 : -1;
}

srp_server_t *srp_server_start(const char *username, const char *password,
                               uint8_t salt[SRP_SALT_LEN], uint8_t *B_out, size_t *B_len) {
    if (!username || !password || strlen(username) >= sizeof(((srp_server_t *) 0)->username)) {
        return NULL;
    }
    srp_server_t *srp = (srp_server_t *) calloc(1, sizeof(srp_server_t));
    if (!srp) {
        return NULL;
    }
    mbedtls_mpi_init(&srp->N);
    mbedtls_mpi_init(&srp->g);
    mbedtls_mpi_init(&srp->v);
    mbedtls_mpi_init(&srp->b);
    mbedtls_mpi_init(&srp->B);
    str_copy(srp->username, sizeof(srp->username), username);

    mbedtls_mpi x;
    mbedtls_mpi k;
    mbedtls_mpi t1;
    mbedtls_mpi t2;
    mbedtls_mpi_init(&x);
    mbedtls_mpi_init(&k);
    mbedtls_mpi_init(&t1);
    mbedtls_mpi_init(&t2);
    int ok = 0;

    uint8_t b_bytes[32];
    /* The salt is used both as a 16-byte value and as a minimal big-endian number,
     * so its first byte must not be zero. */
    if (crypto_random(srp->salt, sizeof(srp->salt)) != 0 || crypto_random(b_bytes, sizeof(b_bytes)) != 0) {
        goto done;
    }
    srp->salt[0] |= 0x80;

    if (mbedtls_mpi_read_string(&srp->N, 16, kModulusHex) != 0 || mbedtls_mpi_lset(&srp->g, 2) != 0 ||
        mbedtls_mpi_read_binary(&srp->b, b_bytes, sizeof(b_bytes)) != 0) {
        goto done;
    }

    /* x = H(s | H(I ":" P)) */
    {
        uint8_t inner[SHA1_SIZE];
        uint8_t xd[SHA1_SIZE];
        hash_t h;
        hash_start(&h);
        hash_update(&h, username, strlen(username));
        hash_update(&h, ":", 1);
        hash_update(&h, password, strlen(password));
        hash_finish(&h, inner);
        hash_start(&h);
        hash_update(&h, srp->salt, sizeof(srp->salt));
        hash_update(&h, inner, sizeof(inner));
        hash_finish(&h, xd);
        if (mbedtls_mpi_read_binary(&x, xd, sizeof(xd)) != 0) {
            goto done;
        }
    }

    /* v = g^x mod N, k = H(PAD(N) | PAD(g)), B = (k*v + g^b) mod N */
    if (mbedtls_mpi_exp_mod(&srp->v, &srp->g, &x, &srp->N, NULL) != 0 ||
        hash_padded_pair(&srp->N, &srp->g, &k) != 0 ||
        mbedtls_mpi_mul_mpi(&t1, &k, &srp->v) != 0 ||
        mbedtls_mpi_exp_mod(&t2, &srp->g, &srp->b, &srp->N, NULL) != 0 ||
        mbedtls_mpi_add_mpi(&t1, &t1, &t2) != 0 ||
        mbedtls_mpi_mod_mpi(&srp->B, &t1, &srp->N) != 0) {
        goto done;
    }
    if (mpi_bytes(&srp->B, B_out, SRP_MODULUS_LEN, B_len) != 0) {
        goto done;
    }
    memcpy(salt, srp->salt, SRP_SALT_LEN);
    ok = 1;

done:
    secure_zero(b_bytes, sizeof(b_bytes));
    mbedtls_mpi_free(&x);
    mbedtls_mpi_free(&k);
    mbedtls_mpi_free(&t1);
    mbedtls_mpi_free(&t2);
    if (!ok) {
        srp_server_free(srp);
        return NULL;
    }
    return srp;
}

int srp_server_verify(srp_server_t *srp, const uint8_t *A_bytes, size_t A_len,
                      const uint8_t *M1, size_t M1_len,
                      uint8_t M2[SRP_PROOF_LEN], uint8_t K[SRP_SESSION_KEY_LEN]) {
    if (!srp || !A_bytes || A_len == 0 || A_len > SRP_MODULUS_LEN || !M1 || M1_len < SRP_PROOF_LEN) {
        return -1;
    }
    mbedtls_mpi A;
    mbedtls_mpi u;
    mbedtls_mpi S;
    mbedtls_mpi t1;
    mbedtls_mpi t2;
    mbedtls_mpi_init(&A);
    mbedtls_mpi_init(&u);
    mbedtls_mpi_init(&S);
    mbedtls_mpi_init(&t1);
    mbedtls_mpi_init(&t2);
    int ret = -1;
    uint8_t M[SHA1_SIZE];
    uint8_t key[SRP_SESSION_KEY_LEN];

    if (mbedtls_mpi_read_binary(&A, A_bytes, A_len) != 0) {
        goto done;
    }
    /* SRP-6a safety check: A mod N must not be zero */
    if (mbedtls_mpi_mod_mpi(&t1, &A, &srp->N) != 0 || mbedtls_mpi_cmp_int(&t1, 0) == 0) {
        goto done;
    }

    /* u = H(PAD(A) | PAD(B)), S = (A * v^u)^b mod N */
    if (hash_padded_pair(&A, &srp->B, &u) != 0 || mbedtls_mpi_cmp_int(&u, 0) == 0 ||
        mbedtls_mpi_exp_mod(&t1, &srp->v, &u, &srp->N, NULL) != 0 ||
        mbedtls_mpi_mul_mpi(&t2, &A, &t1) != 0 ||
        mbedtls_mpi_mod_mpi(&t2, &t2, &srp->N) != 0 ||
        mbedtls_mpi_exp_mod(&S, &t2, &srp->b, &srp->N, NULL) != 0) {
        goto done;
    }

    /* K = H(S | 00000000) | H(S | 00000001) */
    {
        uint8_t s_bytes[SRP_MODULUS_LEN];
        size_t s_len;
        static const uint8_t ctr0[4] = { 0, 0, 0, 0 };
        static const uint8_t ctr1[4] = { 0, 0, 0, 1 };
        if (mpi_bytes(&S, s_bytes, sizeof(s_bytes), &s_len) != 0) {
            goto done;
        }
        hash_t h;
        hash_start(&h);
        hash_update(&h, s_bytes, s_len);
        hash_update(&h, ctr0, 4);
        hash_finish(&h, key);
        hash_start(&h);
        hash_update(&h, s_bytes, s_len);
        hash_update(&h, ctr1, 4);
        hash_finish(&h, key + SHA1_SIZE);
        secure_zero(s_bytes, sizeof(s_bytes));
    }

    /* M = H(H(N) xor H(g) | H(I) | s | A | B | K) */
    {
        uint8_t hn[SHA1_SIZE];
        uint8_t hg[SHA1_SIZE];
        uint8_t hi[SHA1_SIZE];
        hash_t h;
        hash_start(&h);
        if (hash_update_mpi(&h, &srp->N) != 0) {
            hash_finish(&h, hn);
            goto done;
        }
        hash_finish(&h, hn);
        hash_start(&h);
        hash_update_mpi(&h, &srp->g);
        hash_finish(&h, hg);
        sha1_buf(srp->username, strlen(srp->username), hi);
        for (int i = 0; i < SHA1_SIZE; i++) {
            hn[i] ^= hg[i];
        }
        hash_start(&h);
        hash_update(&h, hn, sizeof(hn));
        hash_update(&h, hi, sizeof(hi));
        hash_update(&h, srp->salt, sizeof(srp->salt));
        hash_update_mpi(&h, &A);
        hash_update_mpi(&h, &srp->B);
        hash_update(&h, key, sizeof(key));
        hash_finish(&h, M);
    }

    if (crypto_memcmp(M, M1, SRP_PROOF_LEN) != 0) {
        goto done;
    }

    /* M2 = H(A | M | K) */
    {
        hash_t h;
        hash_start(&h);
        hash_update_mpi(&h, &A);
        hash_update(&h, M, sizeof(M));
        hash_update(&h, key, sizeof(key));
        hash_finish(&h, M2);
    }
    memcpy(K, key, SRP_SESSION_KEY_LEN);
    ret = 0;

done:
    secure_zero(key, sizeof(key));
    mbedtls_mpi_free(&A);
    mbedtls_mpi_free(&u);
    mbedtls_mpi_free(&S);
    mbedtls_mpi_free(&t1);
    mbedtls_mpi_free(&t2);
    return ret;
}

void srp_server_free(srp_server_t *srp) {
    if (!srp) {
        return;
    }
    mbedtls_mpi_free(&srp->N);
    mbedtls_mpi_free(&srp->g);
    mbedtls_mpi_free(&srp->v);
    mbedtls_mpi_free(&srp->b);
    mbedtls_mpi_free(&srp->B);
    secure_zero(srp, sizeof(*srp));
    free(srp);
}
