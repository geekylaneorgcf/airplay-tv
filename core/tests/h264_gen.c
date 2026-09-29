/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#include "h264_gen.h"

#include <string.h>

typedef struct {
    uint8_t *buf;
    size_t cap;
    size_t bytes;
    int bits; /* bits used in the current byte */
    int overflow;
} bw_t;

static void bw_bit(bw_t *w, int bit) {
    if (w->bytes >= w->cap) {
        w->overflow = 1;
        return;
    }
    if (w->bits == 0) {
        w->buf[w->bytes] = 0;
    }
    if (bit) {
        w->buf[w->bytes] |= (uint8_t) (0x80 >> w->bits);
    }
    if (++w->bits == 8) {
        w->bits = 0;
        w->bytes++;
    }
}

static void bw_u(bw_t *w, uint32_t v, int n) {
    for (int i = n - 1; i >= 0; i--) {
        bw_bit(w, (v >> i) & 1);
    }
}

static void bw_ue(bw_t *w, uint32_t v) {
    uint32_t x = v + 1;
    int len = 0;
    while ((x >> len) > 1) {
        len++;
    }
    for (int i = 0; i < len; i++) {
        bw_bit(w, 0);
    }
    bw_u(w, x, len + 1);
}

static void bw_se(bw_t *w, int32_t v) {
    bw_ue(w, v <= 0 ? (uint32_t) (-2 * v) : (uint32_t) (2 * v - 1));
}

static void bw_align_zero(bw_t *w) {
    while (w->bits) {
        bw_bit(w, 0);
    }
}

static void bw_trailing(bw_t *w) {
    bw_bit(w, 1);
    bw_align_zero(w);
}

static size_t bw_len(const bw_t *w) {
    return w->bytes + (w->bits ? 1 : 0);
}

/* Converts RBSP to a NAL unit payload (emulation prevention) behind a header byte. */
static size_t make_nal(uint8_t header, const uint8_t *rbsp, size_t len, uint8_t *out, size_t cap) {
    size_t o = 0;
    int zeros = 0;
    if (cap < 1) {
        return 0;
    }
    out[o++] = header;
    for (size_t i = 0; i < len; i++) {
        if (zeros >= 2 && rbsp[i] <= 3) {
            if (o >= cap) {
                return 0;
            }
            out[o++] = 3;
            zeros = 0;
        }
        if (o >= cap) {
            return 0;
        }
        out[o++] = rbsp[i];
        zeros = rbsp[i] == 0 ? zeros + 1 : 0;
    }
    return o;
}

void h264_gen_init(h264_gen_t *g, int width, int height) {
    memset(g, 0, sizeof(*g));
    g->width = width;
    g->height = height;
}

static size_t make_sps(h264_gen_t *g, uint8_t *out, size_t cap) {
    uint8_t rbsp[64];
    bw_t w = { rbsp, sizeof(rbsp), 0, 0, 0 };
    bw_u(&w, 66, 8);        /* profile_idc: baseline */
    bw_u(&w, 0xc0, 8);      /* constraint_set0/1 */
    bw_u(&w, 30, 8);        /* level 3.0 */
    bw_ue(&w, 0);           /* seq_parameter_set_id */
    bw_ue(&w, 0);           /* log2_max_frame_num_minus4 */
    bw_ue(&w, 2);           /* pic_order_cnt_type */
    bw_ue(&w, 1);           /* max_num_ref_frames */
    bw_u(&w, 0, 1);         /* gaps_in_frame_num_value_allowed_flag */
    bw_ue(&w, (uint32_t) (g->width / 16 - 1));
    bw_ue(&w, (uint32_t) (g->height / 16 - 1));
    bw_u(&w, 1, 1);         /* frame_mbs_only_flag */
    bw_u(&w, 1, 1);         /* direct_8x8_inference_flag */
    bw_u(&w, 0, 1);         /* frame_cropping_flag */
    bw_u(&w, 0, 1);         /* vui_parameters_present_flag */
    bw_trailing(&w);
    return make_nal(0x67, rbsp, bw_len(&w), out, cap);
}

static size_t make_pps(uint8_t *out, size_t cap) {
    uint8_t rbsp[32];
    bw_t w = { rbsp, sizeof(rbsp), 0, 0, 0 };
    bw_ue(&w, 0);           /* pic_parameter_set_id */
    bw_ue(&w, 0);           /* seq_parameter_set_id */
    bw_u(&w, 0, 1);         /* entropy_coding_mode_flag: CAVLC */
    bw_u(&w, 0, 1);         /* bottom_field_pic_order_in_frame_present_flag */
    bw_ue(&w, 0);           /* num_slice_groups_minus1 */
    bw_ue(&w, 0);           /* num_ref_idx_l0_default_active_minus1 */
    bw_ue(&w, 0);           /* num_ref_idx_l1_default_active_minus1 */
    bw_u(&w, 0, 1);         /* weighted_pred_flag */
    bw_u(&w, 0, 2);         /* weighted_bipred_idc */
    bw_se(&w, 0);           /* pic_init_qp_minus26 */
    bw_se(&w, 0);           /* pic_init_qs_minus26 */
    bw_se(&w, 0);           /* chroma_qp_index_offset */
    bw_u(&w, 0, 1);         /* deblocking_filter_control_present_flag */
    bw_u(&w, 0, 1);         /* constrained_intra_pred_flag */
    bw_u(&w, 0, 1);         /* redundant_pic_cnt_present_flag */
    bw_trailing(&w);
    return make_nal(0x68, rbsp, bw_len(&w), out, cap);
}

size_t h264_gen_avcc(h264_gen_t *g, uint8_t *out, size_t cap) {
    uint8_t sps[64];
    uint8_t pps[32];
    size_t sps_len = make_sps(g, sps, sizeof(sps));
    size_t pps_len = make_pps(pps, sizeof(pps));
    size_t need = 6 + 2 + sps_len + 1 + 2 + pps_len;
    if (!sps_len || !pps_len || need > cap) {
        return 0;
    }
    size_t o = 0;
    out[o++] = 1;           /* configurationVersion */
    out[o++] = sps[1];      /* profile */
    out[o++] = sps[2];      /* compatibility */
    out[o++] = sps[3];      /* level */
    out[o++] = 0xff;        /* 4-byte NAL lengths */
    out[o++] = 0xe1;        /* one SPS */
    out[o++] = (uint8_t) (sps_len >> 8);
    out[o++] = (uint8_t) sps_len;
    memcpy(out + o, sps, sps_len);
    o += sps_len;
    out[o++] = 1;           /* one PPS */
    out[o++] = (uint8_t) (pps_len >> 8);
    out[o++] = (uint8_t) pps_len;
    memcpy(out + o, pps, pps_len);
    o += pps_len;
    return o;
}

/* A gradient with a moving bar; phase shifts the pattern. */
static uint8_t luma(int x, int y, int w, int h, int phase) {
    int bar = ((phase * 8) % w);
    if (x >= bar && x < bar + w / 10) {
        return 235;
    }
    int v = 32 + (x * 160) / w + (y * 32) / h;
    return (uint8_t) (v > 235 ? 235 : v);
}

size_t h264_gen_frame(h264_gen_t *g, int keyframe, int phase, uint8_t *out, size_t cap) {
    int mbs_w = g->width / 16;
    int mbs_h = g->height / 16;
    size_t rbsp_cap = (size_t) mbs_w * mbs_h * 400 + 64;
    static uint8_t rbsp[1920 / 16 * 1088 / 16 * 400 + 64];
    if (rbsp_cap > sizeof(rbsp) || cap < 8) {
        return 0;
    }
    bw_t w = { rbsp, sizeof(rbsp), 0, 0, 0 };
    uint8_t header;
    if (keyframe) {
        g->frame_num = 0;
        header = 0x65;
        bw_ue(&w, 0);                   /* first_mb_in_slice */
        bw_ue(&w, 7);                   /* slice_type: I */
        bw_ue(&w, 0);                   /* pic_parameter_set_id */
        bw_u(&w, 0, 4);                 /* frame_num */
        bw_ue(&w, (uint32_t) (g->idr_id++ & 1)); /* idr_pic_id */
        bw_u(&w, 0, 1);                 /* no_output_of_prior_pics_flag */
        bw_u(&w, 0, 1);                 /* long_term_reference_flag */
        bw_se(&w, 0);                   /* slice_qp_delta */
        for (int my = 0; my < mbs_h; my++) {
            for (int mx = 0; mx < mbs_w; mx++) {
                bw_ue(&w, 25);          /* mb_type: I_PCM */
                bw_align_zero(&w);
                for (int y = 0; y < 16; y++) {
                    for (int x = 0; x < 16; x++) {
                        bw_u(&w, luma(mx * 16 + x, my * 16 + y, g->width, g->height, phase), 8);
                    }
                }
                for (int i = 0; i < 64; i++) {
                    bw_u(&w, (uint8_t) (128 + ((phase * 3) % 64) - 32), 8); /* Cb */
                }
                for (int i = 0; i < 64; i++) {
                    bw_u(&w, 128, 8); /* Cr */
                }
            }
        }
    } else {
        g->frame_num = (g->frame_num + 1) % 16;
        header = 0x41;
        bw_ue(&w, 0);                   /* first_mb_in_slice */
        bw_ue(&w, 5);                   /* slice_type: P */
        bw_ue(&w, 0);                   /* pic_parameter_set_id */
        bw_u(&w, (uint32_t) g->frame_num, 4);
        bw_u(&w, 0, 1);                 /* num_ref_idx_active_override_flag */
        bw_u(&w, 0, 1);                 /* ref_pic_list_modification_flag_l0 */
        bw_u(&w, 0, 1);                 /* adaptive_ref_pic_marking_mode_flag */
        bw_se(&w, 0);                   /* slice_qp_delta */
        bw_ue(&w, (uint32_t) (mbs_w * mbs_h)); /* mb_skip_run: every macroblock */
    }
    bw_trailing(&w);
    if (w.overflow) {
        return 0;
    }
    size_t n = make_nal(header, rbsp, bw_len(&w), out + 4, cap - 4);
    if (!n) {
        return 0;
    }
    out[0] = (uint8_t) (n >> 24);
    out[1] = (uint8_t) (n >> 16);
    out[2] = (uint8_t) (n >> 8);
    out[3] = (uint8_t) n;
    return n + 4;
}
