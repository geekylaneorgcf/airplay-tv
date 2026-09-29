/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#include <stdlib.h>

#include "alac.h"
#include "audio_rtp.h"
#include "fake_sender.h"
#include "h264_gen.h"
#include "mirror.h"
#include "test.h"

TEST(mirror_avcc_conversion) {
    uint8_t au[] = { 0, 0, 0, 3, 0x65, 0xaa, 0xbb, 0, 0, 0, 2, 0x06, 0x01 };
    bool key = false;
    CHECK_EQ(mirror_avcc_to_annexb(au, sizeof(au), VIDEO_CODEC_H264, &key), 2);
    CHECK(key);
    uint8_t expect[] = { 0, 0, 0, 1, 0x65, 0xaa, 0xbb, 0, 0, 0, 1, 0x06, 0x01 };
    CHECK_MEM(au, expect, sizeof(au));
    CHECK(mirror_is_keyframe(au, sizeof(au), VIDEO_CODEC_H264));

    uint8_t p[] = { 0, 0, 0, 2, 0x41, 0x9a };
    CHECK_EQ(mirror_avcc_to_annexb(p, sizeof(p), VIDEO_CODEC_H264, &key), 1);
    CHECK(!key);

    uint8_t hevc[] = { 0, 0, 0, 3, 0x26, 0x01, 0xaf }; /* IDR_W_RADL */
    CHECK_EQ(mirror_avcc_to_annexb(hevc, sizeof(hevc), VIDEO_CODEC_H265, &key), 1);
    CHECK(key);

    uint8_t overrun[] = { 0, 0, 0, 9, 0x65, 0x00 };
    CHECK_EQ(mirror_avcc_to_annexb(overrun, sizeof(overrun), VIDEO_CODEC_H264, &key), -1);
    uint8_t forbidden[] = { 0, 0, 0, 2, 0xe5, 0x00 };
    CHECK_EQ(mirror_avcc_to_annexb(forbidden, sizeof(forbidden), VIDEO_CODEC_H264, &key), -1);
    uint8_t zero_len[] = { 0, 0, 0, 0, 0x65 };
    CHECK_EQ(mirror_avcc_to_annexb(zero_len, sizeof(zero_len), VIDEO_CODEC_H264, &key), -1);
    uint8_t trailing[] = { 0, 0, 0, 1, 0x65, 0, 0 };
    CHECK_EQ(mirror_avcc_to_annexb(trailing, sizeof(trailing), VIDEO_CODEC_H264, &key), -1);
}

TEST(mirror_codec_config_avcc) {
    h264_gen_t g;
    h264_gen_init(&g, 320, 192);
    uint8_t avcc[128];
    size_t n = h264_gen_avcc(&g, avcc, sizeof(avcc));
    CHECK(n > 10);
    uint8_t out[256];
    video_codec_t codec = VIDEO_CODEC_NONE;
    int len = mirror_parse_codec_config(avcc, n, &codec, out, sizeof(out));
    CHECK(len > 0);
    CHECK_EQ(codec, VIDEO_CODEC_H264);
    /* SPS then PPS, each behind a 4-byte start code */
    CHECK_MEM(out, "\x00\x00\x00\x01\x67", 5);
    size_t sps_len = rd16be(avcc + 6);
    CHECK_MEM(out + 4 + sps_len, "\x00\x00\x00\x01\x68", 5);

    /* every truncation must be rejected or parsed safely */
    for (size_t cut = 0; cut < n; cut++) {
        mirror_parse_codec_config(avcc, cut, &codec, out, sizeof(out));
    }
    /* output buffer too small */
    CHECK(mirror_parse_codec_config(avcc, n, &codec, out, 8) < 0);
}

TEST(mirror_codec_config_hvcc) {
    /* hvc1 sample entry with an hvcC box holding VPS, SPS and PPS */
    uint8_t p[200];
    memset(p, 0, sizeof(p));
    size_t o = 0;
    o = 4;
    memcpy(p + o, "hvc1", 4);
    o = 0x56;
    size_t box_start = o;
    o += 4;
    memcpy(p + o, "hvcC", 4);
    o += 4;
    o += 22; /* profile/tier/level and friends */
    p[o++] = 3;
    const uint8_t types[3] = { 0xa0, 0xa1, 0xa2 };
    for (int i = 0; i < 3; i++) {
        p[o++] = types[i];
        p[o++] = 0;
        p[o++] = 1;
        p[o++] = 0;
        p[o++] = 4;
        p[o++] = (uint8_t) (0x40 + 2 * i);
        p[o++] = 0x01;
        p[o++] = 0x0c;
        p[o++] = (uint8_t) i;
    }
    uint32_t box = (uint32_t) (o - box_start);
    wr32be(p + box_start, box);
    uint8_t out[128];
    video_codec_t codec = VIDEO_CODEC_NONE;
    int len = mirror_parse_codec_config(p, o, &codec, out, sizeof(out));
    CHECK_EQ(codec, VIDEO_CODEC_H265);
    CHECK_EQ(len, 3 * 8);
    CHECK_MEM(out, "\x00\x00\x00\x01\x40\x01\x0c\x00", 8);
    CHECK_MEM(out + 16, "\x00\x00\x00\x01\x44\x01\x0c\x02", 8);
    for (size_t cut = 0; cut < o; cut++) {
        mirror_parse_codec_config(p, cut, &codec, out, sizeof(out));
    }
}

TEST(h264_generator_produces_valid_structure) {
    h264_gen_t g;
    h264_gen_init(&g, 320, 192);
    static uint8_t buf[200000];
    size_t n = h264_gen_frame(&g, 1, 0, buf, sizeof(buf));
    CHECK(n > 320 * 192);
    bool key = false;
    CHECK_EQ(mirror_avcc_to_annexb(buf, n, VIDEO_CODEC_H264, &key), 1);
    CHECK(key);
    n = h264_gen_frame(&g, 0, 1, buf, sizeof(buf));
    CHECK(n > 4 && n < 32);
    CHECK_EQ(mirror_avcc_to_annexb(buf, n, VIDEO_CODEC_H264, &key), 1);
    CHECK(!key);
}

TEST(mirror_keys_depend_on_stream_id) {
    uint8_t session[16] = { 1 };
    uint8_t k1[16], iv1[16], k2[16], iv2[16];
    mirror_derive_keys(session, 12345, k1, iv1);
    mirror_derive_keys(session, 12346, k2, iv2);
    CHECK(memcmp(k1, k2, 16) != 0);
    CHECK(memcmp(k1, iv1, 16) != 0);
    uint8_t k3[16], iv3[16];
    mirror_derive_keys(session, 12345, k3, iv3);
    CHECK_MEM(k1, k3, 16);
    CHECK_MEM(iv1, iv3, 16);
}

/* ---- audio jitter buffer ---- */

typedef struct {
    uint16_t seqs[64];
    int count;
    uint16_t resend_first;
    uint16_t resend_count;
    int resends;
} capture_t;

static void cap_deliver(void *ctx, const uint8_t *data, size_t len, uint16_t seq, uint32_t rtp_ts) {
    capture_t *c = (capture_t *) ctx;
    (void) data;
    (void) len;
    (void) rtp_ts;
    if (c->count < 64) {
        c->seqs[c->count++] = seq;
    }
}

static void cap_resend(void *ctx, uint16_t first, uint16_t count) {
    capture_t *c = (capture_t *) ctx;
    c->resend_first = first;
    c->resend_count = count;
    c->resends++;
}

TEST(audio_jitter_in_order_and_duplicates) {
    audio_jitter_t j;
    CHECK(audio_jitter_init(&j, 30 * 1000000ULL) == 0);
    capture_t c = { 0 };
    uint8_t payload[10] = { 1 };
    for (uint16_t s = 65534; s != 3; s++) {
        CHECK(audio_jitter_put(&j, s, s * 352u, payload, sizeof(payload)));
        CHECK(!audio_jitter_put(&j, s, s * 352u, payload, sizeof(payload))); /* redundant copy */
        audio_jitter_drain(&j, 0, cap_deliver, cap_resend, &c);
    }
    CHECK_EQ(c.count, 5);
    CHECK_EQ(c.seqs[0], 65534);
    CHECK_EQ(c.seqs[4], 2);
    CHECK(!audio_jitter_put(&j, 1, 0, payload, sizeof(payload))); /* already played */
    CHECK(!audio_jitter_put(&j, 3, 0, payload, 0));
    CHECK(!audio_jitter_put(&j, 3, 0, payload, AUDIO_SLOT_SIZE + 1));
    audio_jitter_free(&j);
}

TEST(audio_jitter_reorders_and_skips_gaps) {
    audio_jitter_t j;
    CHECK(audio_jitter_init(&j, 30 * 1000000ULL) == 0);
    capture_t c = { 0 };
    uint8_t payload[4] = { 0 };
    audio_jitter_put(&j, 100, 0, payload, 4);
    audio_jitter_drain(&j, 1000, cap_deliver, cap_resend, &c);
    audio_jitter_put(&j, 102, 0, payload, 4);
    audio_jitter_put(&j, 104, 0, payload, 4);
    uint64_t wait = audio_jitter_drain(&j, 2000, cap_deliver, cap_resend, &c);
    CHECK(wait > 0);
    CHECK_EQ(c.count, 1);
    CHECK_EQ(c.resends, 1);
    CHECK_EQ(c.resend_first, 101);
    CHECK_EQ(c.resend_count, 1);
    /* the retransmission arrives in time */
    audio_jitter_put(&j, 101, 0, payload, 4);
    audio_jitter_drain(&j, 3000, cap_deliver, cap_resend, &c);
    CHECK_EQ(c.count, 3); /* 100 101 102 */
    /* 103 never arrives: skipped after the wait */
    audio_jitter_drain(&j, 4000, cap_deliver, cap_resend, &c);
    CHECK_EQ(c.count, 3);
    audio_jitter_drain(&j, 4000 + 31 * 1000000ULL, cap_deliver, cap_resend, &c);
    CHECK_EQ(c.count, 4);
    CHECK_EQ(c.seqs[3], 104);
    CHECK_EQ(j.lost, 1);

    /* a jump far ahead restarts the buffer */
    audio_jitter_put(&j, 30000, 0, payload, 4);
    audio_jitter_drain(&j, 5000 + 31 * 1000000ULL, cap_deliver, cap_resend, &c);
    CHECK_EQ(c.seqs[c.count - 1], 30000);
    audio_jitter_free(&j);
}

/* ---- ALAC ---- */

TEST(alac_decodes_uncompressed_frame) {
    alac_file *alac = alac_create(16, 2);
    CHECK(alac != NULL);
    CHECK(alac_set_config(alac, 352, 16, 40, 10, 14) == 0);
    uint8_t frame[2048];
    size_t n = fs_make_alac_frame(frame, sizeof(frame), 0);
    CHECK(n > 1400);
    int16_t pcm[352 * 2];
    int out = 0;
    CHECK(alac_decode_frame(alac, frame, (int) n, pcm, sizeof(pcm), &out) == 0);
    CHECK_EQ(out, 352 * 4);
    /* left and right carry the same sine wave; sample 10 is clearly non-zero */
    CHECK_EQ(pcm[20], pcm[21]);
    CHECK(pcm[20] != 0);
    CHECK_EQ(pcm[0], 0);
    /* output buffer too small */
    CHECK(alac_decode_frame(alac, frame, (int) n, pcm, 100, &out) != 0);
    /* truncated input */
    CHECK(alac_decode_frame(alac, frame, 100, pcm, sizeof(pcm), &out) != 0);
    alac_free(alac);
}

TEST(alac_survives_garbage) {
    alac_file *alac = alac_create(16, 2);
    CHECK(alac_set_config(alac, 352, 16, 40, 10, 14) == 0);
    int16_t pcm[4096 * 2];
    uint8_t junk[1500];
    uint32_t seed = 1;
    for (int round = 0; round < 3000; round++) {
        size_t len = 1 + (seed % sizeof(junk));
        for (size_t i = 0; i < len; i++) {
            seed = seed * 1103515245u + 12345u;
            junk[i] = (uint8_t) (seed >> 16);
        }
        if (round % 3 == 0) {
            junk[0] = (uint8_t) ((junk[0] & 0x1f) | 0x20); /* stereo, compressed: deeper paths */
        }
        int out = 0;
        alac_decode_frame(alac, junk, (int) len, pcm, sizeof(pcm), &out);
        CHECK(out >= 0 && out <= (int) sizeof(pcm));
    }
    CHECK(alac_set_config(alac, 10, 16, 40, 10, 14) != 0);
    CHECK(alac_set_config(alac, 352, 24, 40, 10, 14) != 0);
    alac_free(alac);
}
