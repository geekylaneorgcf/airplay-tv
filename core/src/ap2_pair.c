/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky and contributors
 *
 * See ap2_pair.h.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#include "ap2_pair.h"

#include <stdlib.h>

#include "crypto.h"
#include "log.h"
#include "mbedtls/bignum.h"
#include "monocypher-ed25519.h"
#include "monocypher.h"
#include "util.h"

/* ------------------------------------------------------------------------- */
/* TLV8                                                                       */

int tlv_parse(tlv_t *out, const uint8_t *data, size_t len) {
    memset(out, 0, sizeof(*out));
    /* the joined values never exceed the input, so one buffer of that size holds them all */
    out->joined = (uint8_t *) malloc(len ? len : 1);
    if (!out->joined) {
        return -1;
    }
    size_t pos = 0;
    int last = -1;
    while (pos < len) {
        if (pos + 2 > len) {
            goto bad;
        }
        uint8_t type = data[pos];
        size_t n = data[pos + 1];
        pos += 2;
        if (pos + n > len) {
            goto bad;
        }
        if (last >= 0 && out->items[last].type == type && out->items[last].len > 0 && out->items[last].len % 255 == 0) {
            /* the previous piece was full and the type repeats: this is its continuation */
            uint8_t *dst = (uint8_t *) out->items[last].data + out->items[last].len;
            memcpy(dst, data + pos, n);
            out->items[last].len += n;
            out->joined_len += n;
        } else {
            if (out->count >= TLV_MAX_ITEMS) {
                goto bad;
            }
            uint8_t *dst = out->joined + out->joined_len;
            memcpy(dst, data + pos, n);
            out->items[out->count].type = type;
            out->items[out->count].data = dst;
            out->items[out->count].len = n;
            out->joined_len += n;
            last = out->count++;
        }
        pos += n;
    }
    return 0;
bad:
    tlv_free(out);
    return -1;
}

const tlv_item_t *tlv_get(const tlv_t *t, uint8_t type) {
    for (int i = 0; i < t->count; i++) {
        if (t->items[i].type == type) {
            return &t->items[i];
        }
    }
    return NULL;
}

void tlv_free(tlv_t *t) {
    free(t->joined);
    memset(t, 0, sizeof(*t));
}

bool tlv_put(uint8_t *buf, size_t cap, size_t *used, uint8_t type, const void *value, size_t len) {
    const uint8_t *p = (const uint8_t *) value;
    size_t pos = *used;
    do {
        size_t piece = len > 255 ? 255 : len;
        if (pos + 2 + piece > cap) {
            return false;
        }
        buf[pos++] = type;
        buf[pos++] = (uint8_t) piece;
        if (piece) {
            memcpy(buf + pos, p, piece);
        }
        pos += piece;
        p += piece;
        len -= piece;
    } while (len > 0);
    *used = pos;
    return true;
}

/* ------------------------------------------------------------------------- */
/* hashing helpers                                                            */

typedef crypto_sha512_ctx hash_t;

static void h_start(hash_t *h) {
    crypto_sha512_init(h);
}

static void h_update(hash_t *h, const void *data, size_t len) {
    if (len) {
        crypto_sha512_update(h, (const uint8_t *) data, len);
    }
}

static void h_final(hash_t *h, uint8_t out[64]) {
    crypto_sha512_final(h, out);
}

/* the big-endian bytes of x without leading zeros (one zero byte for zero), the way both sides hash a number */
static int mpi_min(const mbedtls_mpi *x, uint8_t *buf, size_t cap, size_t *len) {
    size_t n = mbedtls_mpi_size(x);
    if (n == 0) {
        n = 1;
        if (cap < 1) {
            return -1;
        }
        buf[0] = 0;
        *len = 1;
        return 0;
    }
    if (n > cap || mbedtls_mpi_write_binary(x, buf, n) != 0) {
        return -1;
    }
    *len = n;
    return 0;
}

#define SRP_N_LEN 384

static int h_update_mpi(hash_t *h, const mbedtls_mpi *x) {
    uint8_t buf[SRP_N_LEN];
    size_t n;
    if (mpi_min(x, buf, sizeof(buf), &n) != 0) {
        return -1;
    }
    h_update(h, buf, n);
    return 0;
}

/* H(PAD(a) | PAD(b)) as a number, both padded to the length of the modulus */
static int h_padded_pair(const mbedtls_mpi *a, const mbedtls_mpi *b, mbedtls_mpi *out) {
    uint8_t buf[2 * SRP_N_LEN];
    uint8_t digest[64];
    if (mbedtls_mpi_write_binary(a, buf, SRP_N_LEN) != 0 || mbedtls_mpi_write_binary(b, buf + SRP_N_LEN, SRP_N_LEN) != 0) {
        return -1;
    }
    crypto_sha512(digest, buf, sizeof(buf));
    return mbedtls_mpi_read_binary(out, digest, sizeof(digest)) == 0 ? 0 : -1;
}

/* ------------------------------------------------------------------------- */
/* SRP-6a, 3072-bit group (RFC 5054 appendix A), g = 5, SHA-512, user name "Pair-Setup" */

static const char kN3072[] =
    "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B"
    "139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485"
    "B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1F"
    "E649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F83655D23"
    "DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32"
    "905E462E36CE3BE39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF69558"
    "17183995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33A85521"
    "ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7ABF5AE8CDB0933D7"
    "1E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D87602733EC86A64521F2B1817"
    "7B200CBBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFCE0FD108E4B82"
    "D120A93AD2CAFFFFFFFFFFFFFFFF";

#define SRP_USER "Pair-Setup"

struct ap2_pair {
    char pin[16];
    int step;                  /* 0: nothing yet, 1: M2 sent, 2: finished */
    bool transient;
    bool failed;
    mbedtls_mpi N, g, v, b, B;
    uint8_t salt[16];
    uint8_t K[64];
    bool have_key;
};

ap2_pair_t *ap2_pair_new(const char *pin) {
    ap2_pair_t *p = (ap2_pair_t *) calloc(1, sizeof(*p));
    if (!p) {
        return NULL;
    }
    str_copy(p->pin, sizeof(p->pin), pin ? pin : AP2_PIN);
    mbedtls_mpi_init(&p->N);
    mbedtls_mpi_init(&p->g);
    mbedtls_mpi_init(&p->v);
    mbedtls_mpi_init(&p->b);
    mbedtls_mpi_init(&p->B);
    return p;
}

void ap2_pair_free(ap2_pair_t *p) {
    if (!p) {
        return;
    }
    mbedtls_mpi_free(&p->N);
    mbedtls_mpi_free(&p->g);
    mbedtls_mpi_free(&p->v);
    mbedtls_mpi_free(&p->b);
    mbedtls_mpi_free(&p->B);
    secure_zero(p, sizeof(*p));
    free(p);
}

bool ap2_pair_done(const ap2_pair_t *p, uint8_t shared[AP2_SHARED_LEN]) {
    if (!p || !p->have_key || p->step != 2) {
        return false;
    }
    memcpy(shared, p->K, AP2_SHARED_LEN);
    return true;
}

static int reply_state(uint8_t state, int error, const uint8_t *salt, const uint8_t *pub, size_t pub_len, const uint8_t *proof,
                       uint8_t **out, size_t *out_len) {
    size_t cap = 1024;
    uint8_t *buf = (uint8_t *) malloc(cap);
    if (!buf) {
        return -1;
    }
    size_t used = 0;
    bool ok = tlv_put(buf, cap, &used, TLV_STATE, &state, 1);
    if (ok && error >= 0) {
        uint8_t e = (uint8_t) error;
        ok = tlv_put(buf, cap, &used, TLV_ERROR, &e, 1);
    }
    if (ok && salt) {
        ok = tlv_put(buf, cap, &used, TLV_SALT, salt, 16);
    }
    if (ok && pub) {
        ok = tlv_put(buf, cap, &used, TLV_PUBLIC_KEY, pub, pub_len);
    }
    if (ok && proof) {
        ok = tlv_put(buf, cap, &used, TLV_PROOF, proof, 64);
    }
    if (!ok) {
        free(buf);
        return -1;
    }
    *out = buf;
    *out_len = used;
    return 0;
}

/* M1 of the phone: start of the exchange. Makes the salt, the verifier and B and answers with them. */
static int setup_m1(ap2_pair_t *p, const tlv_t *req, uint8_t **out, size_t *out_len) {
    const tlv_item_t *method = tlv_get(req, TLV_METHOD);
    if (method && (method->len != 1 || method->data[0] != 0)) {
        return reply_state(2, TLV_ERROR_UNAVAILABLE, NULL, NULL, 0, NULL, out, out_len);
    }
    const tlv_item_t *flags = tlv_get(req, TLV_FLAGS);
    p->transient = flags && flags->len >= 1 && (flags->data[0] & TLV_FLAG_TRANSIENT);
    if (!p->transient) {
        /* a normal pairing would need the phone to ask for a code on the TV and store the pairing: not offered */
        return reply_state(2, TLV_ERROR_UNAVAILABLE, NULL, NULL, 0, NULL, out, out_len);
    }
    mbedtls_mpi x, k, t1, t2;
    mbedtls_mpi_init(&x);
    mbedtls_mpi_init(&k);
    mbedtls_mpi_init(&t1);
    mbedtls_mpi_init(&t2);
    uint8_t b_bytes[32];
    int rc = -1;
    /* the salt is used as a 16-byte value and as a number: its first byte must not be zero */
    if (crypto_random(p->salt, sizeof(p->salt)) != 0 || crypto_random(b_bytes, sizeof(b_bytes)) != 0) {
        goto done;
    }
    p->salt[0] |= 0x80;
    if (mbedtls_mpi_read_string(&p->N, 16, kN3072) != 0 || mbedtls_mpi_lset(&p->g, 5) != 0 ||
        mbedtls_mpi_read_binary(&p->b, b_bytes, sizeof(b_bytes)) != 0) {
        goto done;
    }
    /* x = H(s | H(I ":" P)), v = g^x, k = H(PAD(N) | PAD(g)), B = k*v + g^b */
    {
        uint8_t inner[64];
        uint8_t xd[64];
        hash_t h;
        h_start(&h);
        h_update(&h, SRP_USER, strlen(SRP_USER));
        h_update(&h, ":", 1);
        h_update(&h, p->pin, strlen(p->pin));
        h_final(&h, inner);
        h_start(&h);
        h_update(&h, p->salt, sizeof(p->salt));
        h_update(&h, inner, sizeof(inner));
        h_final(&h, xd);
        if (mbedtls_mpi_read_binary(&x, xd, sizeof(xd)) != 0) {
            goto done;
        }
    }
    if (mbedtls_mpi_exp_mod(&p->v, &p->g, &x, &p->N, NULL) != 0 || h_padded_pair(&p->N, &p->g, &k) != 0 ||
        mbedtls_mpi_mul_mpi(&t1, &k, &p->v) != 0 || mbedtls_mpi_exp_mod(&t2, &p->g, &p->b, &p->N, NULL) != 0 ||
        mbedtls_mpi_add_mpi(&t1, &t1, &t2) != 0 || mbedtls_mpi_mod_mpi(&p->B, &t1, &p->N) != 0) {
        goto done;
    }
    uint8_t b_pad[SRP_N_LEN];
    if (mbedtls_mpi_write_binary(&p->B, b_pad, sizeof(b_pad)) != 0) {
        goto done;
    }
    p->step = 1;
    rc = reply_state(2, -1, p->salt, b_pad, sizeof(b_pad), NULL, out, out_len);
done:
    secure_zero(b_bytes, sizeof(b_bytes));
    mbedtls_mpi_free(&x);
    mbedtls_mpi_free(&k);
    mbedtls_mpi_free(&t1);
    mbedtls_mpi_free(&t2);
    return rc;
}

/* M3 of the phone: its public value and proof. Checks the proof and answers with ours; the session key is the shared secret. */
static int setup_m3(ap2_pair_t *p, const tlv_t *req, uint8_t **out, size_t *out_len) {
    const tlv_item_t *a_item = tlv_get(req, TLV_PUBLIC_KEY);
    const tlv_item_t *proof = tlv_get(req, TLV_PROOF);
    if (!a_item || a_item->len == 0 || a_item->len > SRP_N_LEN || !proof || proof->len != 64) {
        return -1;
    }
    mbedtls_mpi A, u, S, t1, t2;
    mbedtls_mpi_init(&A);
    mbedtls_mpi_init(&u);
    mbedtls_mpi_init(&S);
    mbedtls_mpi_init(&t1);
    mbedtls_mpi_init(&t2);
    int rc = -1;
    uint8_t K[64];
    uint8_t M[64];
    uint8_t M2[64];
    if (mbedtls_mpi_read_binary(&A, a_item->data, a_item->len) != 0) {
        goto done;
    }
    /* SRP-6a: A mod N must not be zero */
    if (mbedtls_mpi_mod_mpi(&t1, &A, &p->N) != 0 || mbedtls_mpi_cmp_int(&t1, 0) == 0) {
        goto done;
    }
    /* u = H(PAD(A) | PAD(B)), S = (A * v^u)^b */
    if (h_padded_pair(&A, &p->B, &u) != 0 || mbedtls_mpi_cmp_int(&u, 0) == 0 ||
        mbedtls_mpi_exp_mod(&t1, &p->v, &u, &p->N, NULL) != 0 || mbedtls_mpi_mul_mpi(&t2, &A, &t1) != 0 ||
        mbedtls_mpi_mod_mpi(&t2, &t2, &p->N) != 0 || mbedtls_mpi_exp_mod(&S, &t2, &p->b, &p->N, NULL) != 0) {
        goto done;
    }
    /* K = H(S) */
    {
        hash_t h;
        h_start(&h);
        if (h_update_mpi(&h, &S) != 0) {
            goto done;
        }
        h_final(&h, K);
    }
    /* M = H( H(N) xor H(g) | H(I) | s | A | B | K ) */
    {
        uint8_t hn[64], hg[64], hi[64];
        hash_t h;
        h_start(&h);
        h_update_mpi(&h, &p->N);
        h_final(&h, hn);
        h_start(&h);
        h_update_mpi(&h, &p->g);
        h_final(&h, hg);
        crypto_sha512(hi, (const uint8_t *) SRP_USER, strlen(SRP_USER));
        for (int i = 0; i < 64; i++) {
            hn[i] ^= hg[i];
        }
        h_start(&h);
        h_update(&h, hn, 64);
        h_update(&h, hi, 64);
        h_update(&h, p->salt, sizeof(p->salt));
        h_update_mpi(&h, &A);
        h_update_mpi(&h, &p->B);
        h_update(&h, K, 64);
        h_final(&h, M);
    }
    if (crypto_memcmp(M, proof->data, 64) != 0) {
        LOG_W(PAIRING, "AirPlay 2 pairing: the phone's proof does not match");
        rc = reply_state(4, TLV_ERROR_AUTHENTICATION, NULL, NULL, 0, NULL, out, out_len);
        p->failed = true;
        goto done;
    }
    /* M2 = H(A | M | K) */
    {
        hash_t h;
        h_start(&h);
        h_update_mpi(&h, &A);
        h_update(&h, M, 64);
        h_update(&h, K, 64);
        h_final(&h, M2);
    }
    memcpy(p->K, K, 64);
    p->have_key = true;
    p->step = 2;
    rc = reply_state(4, -1, NULL, NULL, 0, M2, out, out_len);
done:
    secure_zero(K, sizeof(K));
    mbedtls_mpi_free(&A);
    mbedtls_mpi_free(&u);
    mbedtls_mpi_free(&S);
    mbedtls_mpi_free(&t1);
    mbedtls_mpi_free(&t2);
    return rc;
}

int ap2_pair_setup(ap2_pair_t *p, const uint8_t *in, size_t in_len, uint8_t **out, size_t *out_len) {
    *out = NULL;
    *out_len = 0;
    tlv_t req;
    if (!p || tlv_parse(&req, in, in_len) != 0) {
        return -1;
    }
    const tlv_item_t *state = tlv_get(&req, TLV_STATE);
    int rc = -1;
    if (state && state->len == 1) {
        if (state->data[0] == 1 && p->step == 0) {
            rc = setup_m1(p, &req, out, out_len);
        } else if (state->data[0] == 3 && p->step == 1) {
            rc = setup_m3(p, &req, out, out_len);
        }
    }
    tlv_free(&req);
    return rc;
}

int ap2_pair_verify_refusal(const uint8_t *in, size_t in_len, uint8_t **out, size_t *out_len) {
    tlv_t req;
    uint8_t state = 2;
    if (tlv_parse(&req, in, in_len) == 0) {
        const tlv_item_t *s = tlv_get(&req, TLV_STATE);
        if (s && s->len == 1 && s->data[0] == 3) {
            state = 4;
        }
        tlv_free(&req);
    }
    return reply_state(state, TLV_ERROR_AUTHENTICATION, NULL, NULL, 0, NULL, out, out_len);
}

/* ------------------------------------------------------------------------- */
/* keys and the encrypted channel                                             */

void ap2_hkdf32(uint8_t out[32], const uint8_t *ikm, size_t ikm_len, const char *salt, const char *info) {
    crypto_sha512_hkdf(out, 32, ikm, ikm_len, (const uint8_t *) salt, strlen(salt), (const uint8_t *) info, strlen(info));
}

void ap2_aead_seal(const uint8_t key[32], const uint8_t nonce[12], const uint8_t *ad, size_t ad_len, const uint8_t *plain,
                   size_t len, uint8_t *cipher, uint8_t tag[AP2_TAG_LEN]) {
    crypto_aead_ctx ctx;
    crypto_aead_init_ietf(&ctx, key, nonce);
    crypto_aead_write(&ctx, cipher, tag, ad, ad_len, plain, len);
    crypto_wipe(&ctx, sizeof(ctx));
}

int ap2_aead_open(const uint8_t key[32], const uint8_t nonce[12], const uint8_t *ad, size_t ad_len, const uint8_t *cipher,
                  size_t len, const uint8_t tag[AP2_TAG_LEN], uint8_t *plain) {
    crypto_aead_ctx ctx;
    crypto_aead_init_ietf(&ctx, key, nonce);
    int rc = crypto_aead_read(&ctx, plain, tag, ad, ad_len, cipher, len);
    crypto_wipe(&ctx, sizeof(ctx));
    return rc;
}

void ap2_cipher_init(ap2_cipher_t *c, const uint8_t *shared, size_t shared_len, ap2_channel_t channel, bool server) {
    memset(c, 0, sizeof(*c));
    const char *salt = channel == AP2_CHANNEL_EVENTS ? "Events-Salt" : "Control-Salt";
    /* "write" and "read" are named from the phone's side: the receiver reads what the phone writes. On the event
     * connection the receiver is the one that speaks, so the roles are the other way round. */
    const char *phone_writes = channel == AP2_CHANNEL_EVENTS ? "Events-Write-Encryption-Key" : "Control-Write-Encryption-Key";
    const char *phone_reads = channel == AP2_CHANNEL_EVENTS ? "Events-Read-Encryption-Key" : "Control-Read-Encryption-Key";
    uint8_t from_phone[32];
    uint8_t to_phone[32];
    ap2_hkdf32(from_phone, shared, shared_len, salt, phone_writes);
    ap2_hkdf32(to_phone, shared, shared_len, salt, phone_reads);
    if (channel == AP2_CHANNEL_EVENTS) {
        /* pair_ap: the events channel is opposite, because it is a connection the receiver opens in the other direction */
        uint8_t tmp[32];
        memcpy(tmp, from_phone, 32);
        memcpy(from_phone, to_phone, 32);
        memcpy(to_phone, tmp, 32);
    }
    if (server) {
        memcpy(c->read_key, from_phone, 32);
        memcpy(c->write_key, to_phone, 32);
    } else {
        memcpy(c->read_key, to_phone, 32);
        memcpy(c->write_key, from_phone, 32);
    }
    secure_zero(from_phone, sizeof(from_phone));
    secure_zero(to_phone, sizeof(to_phone));
    c->on = true;
}

static void nonce_of(uint64_t counter, uint8_t nonce[12]) {
    memset(nonce, 0, 12);
    for (int i = 0; i < 8; i++) {
        nonce[4 + i] = (uint8_t) (counter >> (8 * i));
    }
}

size_t ap2_seal(ap2_cipher_t *c, const uint8_t *plain, size_t plain_len, uint8_t *out, size_t out_cap) {
    size_t blocks = plain_len ? (plain_len + AP2_BLOCK_MAX - 1) / AP2_BLOCK_MAX : 0;
    if (out_cap < plain_len + blocks * (2 + AP2_TAG_LEN)) {
        return 0;
    }
    size_t used = 0;
    for (size_t pos = 0; pos < plain_len;) {
        size_t n = MIN((size_t) AP2_BLOCK_MAX, plain_len - pos);
        uint8_t ad[2] = { (uint8_t) (n & 0xff), (uint8_t) (n >> 8) };
        uint8_t nonce[12];
        nonce_of(c->write_counter++, nonce);
        out[used] = ad[0];
        out[used + 1] = ad[1];
        ap2_aead_seal(c->write_key, nonce, ad, 2, plain + pos, n, out + used + 2, out + used + 2 + n);
        used += 2 + n + AP2_TAG_LEN;
        pos += n;
    }
    return used;
}

int ap2_open(ap2_cipher_t *c, const uint8_t *in, size_t in_len, uint8_t *out, size_t out_cap, size_t *out_len, size_t *consumed) {
    size_t used = 0, made = 0;
    while (in_len - used >= 2) {
        size_t n = (size_t) in[used] | ((size_t) in[used + 1] << 8);
        if (n > AP2_BLOCK_MAX) {
            return -1;
        }
        if (in_len - used < 2 + n + AP2_TAG_LEN) {
            break; /* a partial frame: wait for the rest */
        }
        if (made + n > out_cap) {
            break;
        }
        uint8_t nonce[12];
        nonce_of(c->read_counter, nonce);
        if (ap2_aead_open(c->read_key, nonce, in + used, 2, in + used + 2, n, in + used + 2 + n, out + made) != 0) {
            return -1;
        }
        c->read_counter++;
        made += n;
        used += 2 + n + AP2_TAG_LEN;
    }
    *out_len = made;
    *consumed = used;
    return 0;
}
