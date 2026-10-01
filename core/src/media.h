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
 * Interface between the protocol core and the platform media pipeline.
 * The core delivers decrypted Annex-B video access units and decrypted audio
 * frames; the platform (MediaCodec + AudioTrack on Android) decodes and renders.
 * Callbacks are invoked from receiver threads and must not block for long.
 */

#ifndef AIRPLAYTV_MEDIA_H
#define AIRPLAYTV_MEDIA_H

#include <stdatomic.h>

#include "common.h"

typedef enum {
    VIDEO_CODEC_NONE = 0,
    VIDEO_CODEC_H264 = 1,
    VIDEO_CODEC_H265 = 2,
} video_codec_t;

/* RAOP "ct" compression types */
enum {
    AUDIO_CT_PCM = 1,
    AUDIO_CT_ALAC = 2,
    AUDIO_CT_AAC_LC = 4,
    AUDIO_CT_AAC_ELD = 8,
};

typedef struct {
    int ct;
    int samples_per_frame;
    int sample_rate;
    int channels;
    bool using_screen;   /* audio belongs to a mirroring session */
    bool is_media;
} audio_format_t;

typedef struct {
    /* video (mirroring) */
    void (*video_start)(void *ctx);
    /* Parameter sets in Annex-B form (start code before every NAL unit). */
    void (*video_config)(void *ctx, video_codec_t codec, const uint8_t *config, size_t len,
                         int width, int height);
    /* One access unit in Annex-B form. remote_ts_ns is the sender's capture time. */
    void (*video_frame)(void *ctx, const uint8_t *au, size_t len, uint64_t remote_ts_ns, bool keyframe);
    void (*video_suspend)(void *ctx, bool suspended);
    void (*video_stop)(void *ctx);

    /* audio */
    bool (*audio_start)(void *ctx, const audio_format_t *format);
    void (*audio_frame)(void *ctx, const uint8_t *data, size_t len, uint32_t rtp_ts, uint64_t remote_ts_ns);
    void (*audio_flush)(void *ctx);
    /* AirPlay volume in dB: -144 is mute, otherwise -30..0. */
    void (*audio_volume)(void *ctx, float db);
    void (*audio_stop)(void *ctx);
} media_sink_ops_t;

/* Counters shared by the core and the platform pipeline, read by the debug overlay
 * and the diagnostics report. All fields are updated with relaxed atomics. */
typedef struct {
    _Atomic uint64_t video_frames_in;
    _Atomic uint64_t video_bytes_in;
    _Atomic uint64_t video_keyframes_in;
    _Atomic uint64_t video_frames_decoded;
    _Atomic uint64_t video_frames_rendered;
    _Atomic uint64_t video_frames_dropped;
    _Atomic uint64_t video_decode_time_us_total;   /* input queued -> output available */
    _Atomic uint64_t video_decode_samples;
    _Atomic uint64_t video_delay_us_total;         /* sender capture -> render, when the clock is known */
    _Atomic uint64_t video_delay_samples;
    _Atomic uint32_t video_width;
    _Atomic uint32_t video_height;
    _Atomic int32_t video_codec;
    _Atomic int32_t video_decoder_resets;
    _Atomic uint64_t audio_packets_in;
    _Atomic uint64_t audio_packets_lost;
    _Atomic uint64_t audio_frames_decoded;
    _Atomic uint64_t audio_underruns;
    _Atomic uint64_t audio_frames_dropped;
    _Atomic int32_t audio_ct;
    _Atomic int32_t audio_buffer_ms;
    _Atomic uint32_t audio_last_rtp;               /* RTP timestamp of the newest audio packet handed to the decoder */
    _Atomic uint64_t audio_last_rtp_ns;            /* local monotonic time it arrived, 0 when none yet */
    _Atomic int64_t clock_offset_us;               /* sender clock minus local monotonic clock */
    _Atomic int32_t clock_synced;
    _Atomic uint64_t sessions_started;
    _Atomic uint64_t connections_rejected;
} receiver_stats_t;

extern receiver_stats_t g_stats;

void stats_reset_session(void);

static inline void stat_add(_Atomic uint64_t *counter, uint64_t v) {
    atomic_fetch_add_explicit(counter, v, memory_order_relaxed);
}

#endif
