/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

/*
 * Generates a valid H.264 Baseline stream without an encoder: key frames are
 * made of I_PCM macroblocks (raw samples), other frames are P slices in which
 * every macroblock is skipped. Any decoder can play it, which makes it useful
 * for end-to-end tests of the mirroring pipeline.
 */

#ifndef AIRPLAYTV_H264_GEN_H
#define AIRPLAYTV_H264_GEN_H

#include <stddef.h>
#include <stdint.h>

typedef struct {
    int width;   /* multiple of 16 */
    int height;  /* multiple of 16 */
    int frame_num;
    int idr_id;
} h264_gen_t;

void h264_gen_init(h264_gen_t *g, int width, int height);

/* avcC record (AVCDecoderConfigurationRecord) with one SPS and one PPS. */
size_t h264_gen_avcc(h264_gen_t *g, uint8_t *out, size_t cap);

/* One access unit in AVCC form (4-byte big-endian NAL lengths), as AirPlay
 * senders transmit it. phase animates the picture of key frames. */
size_t h264_gen_frame(h264_gen_t *g, int keyframe, int phase, uint8_t *out, size_t cap);

#endif
