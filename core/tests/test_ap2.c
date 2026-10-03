/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#include <stdlib.h>

#include "ap2_pair.h"
#include "test.h"

TEST(ap2_aead_rfc8439_vector) {
    /* RFC 8439 section 2.8.2 */
    uint8_t key[32], nonce[12], ad[12], tag_expect[16];
    test_hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f", key, 32);
    test_hex("070000004041424344454647", nonce, 12);
    test_hex("50515253c0c1c2c3c4c5c6c7", ad, 12);
    test_hex("1ae10b594f09e26a7e902ecbd0600691", tag_expect, 16);
    const char *text = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.";
    size_t n = strlen(text);
    uint8_t cipher[128], tag[16], back[128], first[4];
    ap2_aead_seal(key, nonce, ad, 12, (const uint8_t *) text, n, cipher, tag);
    CHECK_MEM(tag, tag_expect, 16);
    test_hex("d31a8d34", first, 4);
    CHECK_MEM(cipher, first, 4);
    CHECK(ap2_aead_open(key, nonce, ad, 12, cipher, n, tag, back) == 0);
    CHECK(memcmp(back, text, n) == 0);
    cipher[5] ^= 1;
    CHECK(ap2_aead_open(key, nonce, ad, 12, cipher, n, tag, back) != 0);
}

TEST(ap2_hkdf_sha512_vector) {
    /* the HKDF of RFC 5869 is checked with SHA-256 elsewhere; for SHA-512 the output must at least be stable and keyed */
    uint8_t a[32], b[32], c[32];
    uint8_t ikm[64];
    memset(ikm, 7, sizeof(ikm));
    ap2_hkdf32(a, ikm, sizeof(ikm), "Control-Salt", "Control-Write-Encryption-Key");
    ap2_hkdf32(b, ikm, sizeof(ikm), "Control-Salt", "Control-Write-Encryption-Key");
    ap2_hkdf32(c, ikm, sizeof(ikm), "Control-Salt", "Control-Read-Encryption-Key");
    CHECK_MEM(a, b, 32);
    CHECK(memcmp(a, c, 32) != 0);
}

TEST(ap2_tlv_fragments_roundtrip) {
    uint8_t buf[1024];
    size_t used = 0;
    uint8_t big[384];
    for (size_t i = 0; i < sizeof(big); i++) {
        big[i] = (uint8_t) (i * 7 + 1);
    }
    uint8_t state = 2;
    CHECK(tlv_put(buf, sizeof(buf), &used, TLV_STATE, &state, 1));
    CHECK(tlv_put(buf, sizeof(buf), &used, TLV_PUBLIC_KEY, big, sizeof(big)));
    CHECK(tlv_put(buf, sizeof(buf), &used, TLV_SALT, big, 16));
    /* 384 bytes are two pieces (255 + 129) */
    CHECK_EQ(used, 3 + 2 + 255 + 2 + 129 + 2 + 16);
    tlv_t t;
    CHECK(tlv_parse(&t, buf, used) == 0);
    const tlv_item_t *pk = tlv_get(&t, TLV_PUBLIC_KEY);
    CHECK(pk != NULL);
    CHECK_EQ(pk->len, 384);
    CHECK_MEM(pk->data, big, 384);
    const tlv_item_t *salt = tlv_get(&t, TLV_SALT);
    CHECK(salt != NULL);
    CHECK_EQ(salt->len, 16);
    CHECK(tlv_get(&t, TLV_ERROR) == NULL);
    tlv_free(&t);
    /* a cut-off item is refused */
    CHECK(tlv_parse(&t, buf, used - 3) != 0);
}

TEST(ap2_channel_frames_roundtrip_and_tamper) {
    uint8_t secret[AP2_SHARED_LEN];
    memset(secret, 0x5a, sizeof(secret));
    ap2_cipher_t server, client;
    ap2_cipher_init(&server, secret, sizeof(secret), AP2_CHANNEL_CONTROL, true);
    ap2_cipher_init(&client, secret, sizeof(secret), AP2_CHANNEL_CONTROL, false);
    uint8_t msg[3000];
    for (size_t i = 0; i < sizeof(msg); i++) {
        msg[i] = (uint8_t) i;
    }
    uint8_t wire[4096];
    size_t n = ap2_seal(&client, msg, sizeof(msg), wire, sizeof(wire));
    CHECK_EQ(n, 3000 + 3 * 18); /* 1024 + 1024 + 952 */
    uint8_t plain[4096];
    size_t made = 0, used = 0;
    /* delivered in two pieces, the second cut inside a frame: only whole frames come out */
    CHECK(ap2_open(&server, wire, 1500, plain, sizeof(plain), &made, &used) == 0);
    CHECK_EQ(made, 1024);
    CHECK_EQ(used, 1042);
    size_t made2 = 0, used2 = 0;
    CHECK(ap2_open(&server, wire + used, n - used, plain + made, sizeof(plain) - made, &made2, &used2) == 0);
    CHECK_EQ(made + made2, 3000);
    CHECK_MEM(plain, msg, 3000);
    /* the other direction uses the other key */
    uint8_t reply[64];
    size_t rn = ap2_seal(&server, (const uint8_t *) "hello", 5, reply, sizeof(reply));
    CHECK_EQ(rn, 5 + 18);
    uint8_t got[16];
    size_t gm = 0, gu = 0;
    CHECK(ap2_open(&client, reply, rn, got, sizeof(got), &gm, &gu) == 0);
    CHECK_EQ(gm, 5);
    CHECK(memcmp(got, "hello", 5) == 0);
    /* a flipped bit or a replayed frame does not authenticate */
    ap2_cipher_t again;
    ap2_cipher_init(&again, secret, sizeof(secret), AP2_CHANNEL_CONTROL, false);
    reply[3] ^= 1;
    CHECK(ap2_open(&again, reply, rn, got, sizeof(got), &gm, &gu) != 0);
    CHECK(ap2_open(&client, reply, rn, got, sizeof(got), &gm, &gu) != 0);
}

TEST(ap2_pair_setup_refuses_normal_pairing_and_bad_steps) {
    ap2_pair_t *p = ap2_pair_new(AP2_PIN);
    CHECK(p != NULL);
    uint8_t m1[16];
    size_t used = 0;
    uint8_t one = 1, zero = 0;
    CHECK(tlv_put(m1, sizeof(m1), &used, TLV_METHOD, &zero, 1));
    CHECK(tlv_put(m1, sizeof(m1), &used, TLV_STATE, &one, 1));
    uint8_t *out = NULL;
    size_t out_len = 0;
    /* no transient flag: a normal pairing is not offered, and the answer says so */
    CHECK(ap2_pair_setup(p, m1, used, &out, &out_len) == 0);
    tlv_t t;
    CHECK(tlv_parse(&t, out, out_len) == 0);
    CHECK(tlv_get(&t, TLV_ERROR) != NULL);
    tlv_free(&t);
    free(out);
    /* step 3 before step 1 is refused outright */
    uint8_t m3[8];
    size_t u3 = 0, three = 3;
    uint8_t s3 = (uint8_t) three;
    CHECK(tlv_put(m3, sizeof(m3), &u3, TLV_STATE, &s3, 1));
    CHECK(ap2_pair_setup(p, m3, u3, &out, &out_len) == -1);
    uint8_t shared[AP2_SHARED_LEN];
    CHECK(!ap2_pair_done(p, shared));
    ap2_pair_free(p);
}
