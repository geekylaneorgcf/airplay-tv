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
#include "fairplay.h"
#include "pairing.h"
#include "srp.h"
#include "test.h"

TEST(crypto_hash_vectors) {
    uint8_t out[64];
    uint8_t expect[64];
    sha512_2("ab", 2, "c", 1, out);
    test_hex("ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a"
             "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f", expect, 64);
    CHECK_MEM(out, expect, 64);
    sha1_buf("abc", 3, out);
    test_hex("a9993e364706816aba3e25717850c26c9cd0d89d", expect, 20);
    CHECK_MEM(out, expect, 20);
    char md5[33];
    md5_hex("abc", md5);
    CHECK_STR(md5, "900150983cd24fb0d6963f7d28e17f72");
}

TEST(crypto_aes_ctr_nist) {
    /* NIST SP 800-38A F.5.1, split at an odd boundary to exercise the streaming state */
    uint8_t key[16], iv[16], pt[32], ct[32], out[32];
    test_hex("2b7e151628aed2a6abf7158809cf4f3c", key, 16);
    test_hex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff", iv, 16);
    test_hex("6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51", pt, 32);
    test_hex("874d6191b620e3261bef6864990db6ce9806f66b7970fdff8617187bb9fffdff", ct, 32);
    aes_ctr_t ctr;
    CHECK(aes_ctr_init(&ctr, key, iv) == 0);
    aes_ctr_xcrypt(&ctr, pt, out, 7);
    aes_ctr_xcrypt(&ctr, pt + 7, out + 7, 20);
    aes_ctr_xcrypt(&ctr, pt + 27, out + 27, 5);
    aes_ctr_free(&ctr);
    CHECK_MEM(out, ct, 32);
}

TEST(crypto_aes_cbc_packet) {
    /* NIST SP 800-38A F.2.2; a trailing partial block stays in the clear */
    uint8_t key[16], iv[16], buf[20], pt[16];
    test_hex("2b7e151628aed2a6abf7158809cf4f3c", key, 16);
    test_hex("000102030405060708090a0b0c0d0e0f", iv, 16);
    test_hex("7649abac8119b246cee98e9b12e9197d01020304", buf, 20);
    test_hex("6bc1bee22e409f96e93d7e117393172a", pt, 16);
    aes_cbc_t cbc;
    CHECK(aes_cbc_init_decrypt(&cbc, key, iv) == 0);
    uint8_t copy[20];
    memcpy(copy, buf, 20);
    aes_cbc_decrypt_packet(&cbc, copy, 20);
    CHECK_MEM(copy, pt, 16);
    CHECK_MEM(copy + 16, "\x01\x02\x03\x04", 4);
    /* the IV restarts for every packet */
    memcpy(copy, buf, 20);
    aes_cbc_decrypt_packet(&cbc, copy, 20);
    CHECK_MEM(copy, pt, 16);
    aes_cbc_free(&cbc);
}

TEST(crypto_aes_gcm_roundtrip) {
    uint8_t key[16] = { 1, 2, 3 };
    uint8_t iv[16] = { 9, 8, 7 };
    uint8_t msg[32] = "thirty-two bytes of public key.";
    uint8_t enc[32], dec[32], tag[16];
    CHECK(aes_gcm_encrypt(key, iv, msg, 32, enc, tag) == 0);
    CHECK(memcmp(enc, msg, 32) != 0);
    CHECK(aes_gcm_decrypt(key, iv, enc, 32, dec, tag) == 0);
    CHECK_MEM(dec, msg, 32);
    tag[0] ^= 1;
    CHECK(aes_gcm_decrypt(key, iv, enc, 32, dec, tag) != 0);
}

TEST(crypto_x25519_rfc7748) {
    uint8_t a_sk[32], a_pk[32], b_pk[32], shared[32], expect[32];
    test_hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a", a_sk, 32);
    test_hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", b_pk, 32);
    test_hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742", expect, 32);
    CHECK(x25519_shared(shared, a_sk, b_pk) == 0);
    CHECK_MEM(shared, expect, 32);
    uint8_t zero[32] = { 0 };
    CHECK(x25519_shared(shared, a_sk, zero) != 0);
    (void) a_pk;
}

TEST(crypto_ed25519_rfc8032) {
    uint8_t seed[32], pk_expect[32], sig_expect[64], sk[64], pk[32], sig[64];
    test_hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60", seed, 32);
    test_hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", pk_expect, 32);
    test_hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46b"
             "d25bf5f0595bbe24655141438e7a100b", sig_expect, 64);
    ed25519_from_seed(seed, sk, pk);
    CHECK_MEM(pk, pk_expect, 32);
    ed25519_sign(sig, sk, (const uint8_t *) "", 0);
    CHECK_MEM(sig, sig_expect, 64);
    CHECK(ed25519_verify(sig, pk, (const uint8_t *) "", 0) == 0);
    sig[5] ^= 0x10;
    CHECK(ed25519_verify(sig, pk, (const uint8_t *) "", 0) != 0);
}

TEST(pairing_verify_handshake) {
    uint8_t seed[32] = { 42 };
    pairing_identity_t server_id;
    pairing_identity_init(&server_id, seed);

    uint8_t client_seed[32] = { 7 };
    uint8_t client_sk[64], client_pk[32];
    ed25519_from_seed(client_seed, client_sk, client_pk);
    uint8_t cx_sk[32], cx_pk[32];
    CHECK(x25519_generate(cx_sk, cx_pk) == 0);

    pairing_session_t s;
    pairing_session_init(&s);
    uint8_t reply[PAIR_VERIFY_REPLY_LEN];
    CHECK(pairing_verify_start(&s, &server_id, cx_pk, client_pk, reply) == 0);

    /* client side */
    uint8_t shared[32];
    CHECK(x25519_shared(shared, cx_sk, reply) == 0);
    uint8_t server_shared[32];
    CHECK(pairing_shared_secret(&s, server_shared));
    CHECK_MEM(shared, server_shared, 32);
    uint8_t h[64], key[16], iv[16];
    sha512_2("Pair-Verify-AES-Key", 19, shared, 32, h);
    memcpy(key, h, 16);
    sha512_2("Pair-Verify-AES-IV", 18, shared, 32, h);
    memcpy(iv, h, 16);
    aes_ctr_t ctr;
    aes_ctr_init(&ctr, key, iv);
    uint8_t sig[64];
    aes_ctr_xcrypt(&ctr, reply + 32, sig, 64);
    uint8_t msg[64];
    memcpy(msg, reply, 32);
    memcpy(msg + 32, cx_pk, 32);
    CHECK(ed25519_verify(sig, server_id.public_key, msg, 64) == 0);

    memcpy(msg, cx_pk, 32);
    memcpy(msg + 32, reply, 32);
    uint8_t mine[64], enc[64];
    ed25519_sign(mine, client_sk, msg, 64);
    aes_ctr_xcrypt(&ctr, mine, enc, 64);
    aes_ctr_free(&ctr);

    uint8_t bad[64];
    memcpy(bad, enc, 64);
    bad[0] ^= 1;
    pairing_session_t copy = s;
    copy.srp = NULL;
    CHECK(pairing_verify_finish(&copy, bad) != 0);
    CHECK(pairing_verify_finish(&s, enc) == 0);
    CHECK_EQ(s.state, PAIR_STATE_VERIFIED);
    /* a finished session cannot be restarted */
    CHECK(pairing_verify_start(&s, &server_id, cx_pk, client_pk, reply) != 0);
    pairing_session_clear(&s);
}

TEST(srp_rejects_wrong_proof) {
    uint8_t salt[SRP_SALT_LEN];
    uint8_t B[SRP_MODULUS_LEN];
    size_t B_len = 0;
    srp_server_t *srp = srp_server_start("AA:BB:CC:DD:EE:FF", "1234", salt, B, &B_len);
    CHECK(srp != NULL);
    CHECK(B_len > 200 && B_len <= SRP_MODULUS_LEN);
    CHECK(salt[0] & 0x80);
    uint8_t A[SRP_MODULUS_LEN];
    memset(A, 0x5a, sizeof(A));
    uint8_t M1[20] = { 0 };
    uint8_t M2[20];
    uint8_t K[40];
    CHECK(srp_server_verify(srp, A, sizeof(A), M1, sizeof(M1), M2, K) != 0);
    /* A = 0 and A = N are rejected (SRP-6a safety check) */
    uint8_t zero[1] = { 0 };
    CHECK(srp_server_verify(srp, zero, 1, M1, sizeof(M1), M2, K) != 0);
    CHECK(srp_server_verify(srp, A, 0, M1, sizeof(M1), M2, K) != 0);
    CHECK(srp_server_verify(srp, A, sizeof(A), M1, 3, M2, K) != 0);
    srp_server_free(srp);
}

TEST(fairplay_framing) {
    fairplay_t fp;
    fairplay_reset(&fp);
    uint8_t req[16] = { 'F', 'P', 'L', 'Y', 3, 1, 1, 0, 0, 0, 0, 4, 2, 0, 0, 0xbb };
    uint8_t reply[142];
    for (uint8_t mode = 0; mode < 4; mode++) {
        req[14] = mode;
        CHECK(fairplay_setup(&fp, req, sizeof(req), reply) == 0);
        CHECK_MEM(reply, "FPLY", 4);
        CHECK_EQ(reply[13], mode);
    }
    req[14] = 4;
    CHECK(fairplay_setup(&fp, req, sizeof(req), reply) != 0);
    req[14] = 0;
    req[4] = 2;
    CHECK(fairplay_setup(&fp, req, sizeof(req), reply) != 0);
    CHECK(fairplay_setup(&fp, req, 15, reply) != 0);

    uint8_t key[16];
    uint8_t ekey[72] = { 0 };
    CHECK(fairplay_decrypt(&fp, ekey, sizeof(ekey), key) != 0); /* before the handshake */

    uint8_t msg[164] = { 'F', 'P', 'L', 'Y', 3, 1, 3, 0, 0, 0, 0, 0x98, 9 };
    uint8_t hs[32];
    CHECK(fairplay_handshake(&fp, msg, sizeof(msg), hs) != 0); /* mode 9 would index out of bounds */
    msg[12] = 3;
    for (int i = 144; i < 164; i++) msg[i] = (uint8_t) i;
    CHECK(fairplay_handshake(&fp, msg, sizeof(msg), hs) == 0);
    CHECK_MEM(hs + 12, msg + 144, 20);
    CHECK(fairplay_decrypt(&fp, ekey, sizeof(ekey), key) == 0);
    CHECK(fairplay_decrypt(&fp, ekey, 71, key) != 0);
}
