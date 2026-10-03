/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#include <arpa/inet.h>
#include <netinet/in.h>
#include <pthread.h>
#include <stdlib.h>
#include <time.h>
#include <unistd.h>

#include "ap2_buffered.h"
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

/* ---- buffered audio ---- */

static pthread_mutex_t g_bl = PTHREAD_MUTEX_INITIALIZER;
static uint32_t g_bts[400];
static int g_bn;
static int g_bflushes;

static void b_frame(void *ctx, const uint8_t *d, size_t len, uint32_t ts, uint64_t remote) {
    (void) ctx; (void) d; (void) len; (void) remote;
    pthread_mutex_lock(&g_bl);
    if (g_bn < 400) g_bts[g_bn++] = ts;
    pthread_mutex_unlock(&g_bl);
}
static void b_flush(void *ctx) { (void) ctx; pthread_mutex_lock(&g_bl); g_bflushes++; pthread_mutex_unlock(&g_bl); }

static int b_count(void) { pthread_mutex_lock(&g_bl); int n = g_bn; pthread_mutex_unlock(&g_bl); return n; }
static void b_sleep(int ms) { struct timespec t = { ms / 1000, (long) (ms % 1000) * 1000000L }; nanosleep(&t, NULL); }

static bool b_wait(int target, int ms) {
    for (int i = 0; i < ms / 10; i++) {
        if (b_count() >= target) return true;
        b_sleep(10);
    }
    return b_count() >= target;
}

/* One sealed block as the sender writes it: [len BE16][seq][ts][ssrc][cipher+tag][nonce]. */
static size_t b_block(uint8_t *out, const uint8_t key[32], uint32_t seq, uint32_t ts, uint64_t counter) {
    uint8_t plain[64];
    memset(plain, (int) (seq & 0xff), sizeof(plain));
    uint8_t *pkt = out + 2;
    wr32be(pkt, seq); wr32be(pkt + 4, ts); wr32be(pkt + 8, 0xface);
    uint8_t nonce[12] = { 0 };
    for (int i = 0; i < 8; i++) nonce[4 + i] = (uint8_t) (counter >> (8 * i));
    ap2_aead_seal(key, nonce, pkt + 4, 8, plain, sizeof(plain), pkt + 12, pkt + 12 + sizeof(plain));
    memcpy(pkt + 12 + sizeof(plain) + 16, nonce + 4, 8);
    size_t total = 2 + 12 + sizeof(plain) + 16 + 8;
    out[0] = (uint8_t) (total >> 8);
    out[1] = (uint8_t) total;
    return total;
}

TEST(ap2_seq_order_wraps_at_23_bits) {
    CHECK(ap2_seq_before(5, 6));
    CHECK(!ap2_seq_before(6, 5));
    CHECK(!ap2_seq_before(7, 7));
    CHECK(ap2_seq_before(0x7ffffe, 2));   /* across the wrap */
    CHECK(!ap2_seq_before(2, 0x7ffffe));
}

TEST(ap2_buffered_orders_paces_and_flushes) {
    g_bn = 0;
    g_bflushes = 0;
    media_sink_ops_t ops;
    memset(&ops, 0, sizeof(ops));
    ops.audio_frame = b_frame;
    ops.audio_flush = b_flush;
    uint8_t key[32];
    memset(key, 0x5a, sizeof(key));
    ap2_buffered_params_t p;
    memset(&p, 0, sizeof(p));
    p.ops = &ops;
    memcpy(p.key, key, 32);
    struct sockaddr_in *peer = (struct sockaddr_in *) &p.peer;
    peer->sin_family = AF_INET;
    peer->sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    p.samples_per_frame = 352;
    p.sample_rate = 44100;
    uint16_t dport = 0, cport = 0;
    ap2_buffered_t *b = ap2_buffered_start(&p, 1 << 20, &dport, &cport);
    CHECK(b != NULL);
    if (!b) return;
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    struct sockaddr_in to;
    memset(&to, 0, sizeof(to));
    to.sin_family = AF_INET;
    to.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    to.sin_port = htons(dport);
    CHECK(connect(fd, (struct sockaddr *) &to, sizeof(to)) == 0);
    /* 200 blocks (1.6 s of audio) at once, a bad one among them */
    uint8_t buf[256];
    for (uint32_t i = 0; i < 200; i++) {
        size_t n = b_block(buf, key, 1000 + i, 5000 + i * 352, i + 1);
        if (i == 3) buf[40] ^= 1;          /* does not authenticate: skipped, the stream goes on */
        CHECK(write(fd, buf, n) == (ssize_t) n);
    }
    b_sleep(100);
    CHECK_EQ(b_count(), 0);                /* nothing plays before the rate anchor */
    ap2_buffered_rate(b, true, 5000, true);
    CHECK(b_wait(30, 1000));               /* the first quarter second comes at once */
    int early = b_count();
    CHECK(early >= 30 && early <= 60);     /* ... and not the whole buffer */
    b_sleep(1000);
    int later = b_count();
    CHECK(later > early + 60 && later < 200);   /* then at the speed of the audio */
    pthread_mutex_lock(&g_bl);
    CHECK(g_bts[0] == 5000);
    bool ordered = true;
    for (int i = 1; i < g_bn; i++) {
        if (g_bts[i] <= g_bts[i - 1]) ordered = false;
    }
    pthread_mutex_unlock(&g_bl);
    CHECK(ordered);
    /* an immediate flush drops what is queued before the given block and stops playing until the next anchor */
    ap2_buffered_flush(b, false, 0, 1185);
    b_sleep(50);
    int stopped = b_count();
    b_sleep(200);
    CHECK_EQ(b_count(), stopped);
    CHECK(g_bflushes >= 1);
    ap2_buffered_rate(b, true, 5000 + 185 * 352, true);
    CHECK(b_wait(stopped + 1, 1000));
    pthread_mutex_lock(&g_bl);
    CHECK_EQ(g_bts[stopped], 5000 + 185 * 352);
    pthread_mutex_unlock(&g_bl);
    close(fd);
    ap2_buffered_stop(b);
}
