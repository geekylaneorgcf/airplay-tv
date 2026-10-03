/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#include "media.h"

receiver_stats_t g_stats;

void stats_reset_session(void) {
    atomic_store(&g_stats.video_frames_in, 0);
    atomic_store(&g_stats.audio_dropped_aead, 0);
    atomic_store(&g_stats.video_bytes_in, 0);
    atomic_store(&g_stats.video_keyframes_in, 0);
    atomic_store(&g_stats.video_frames_decoded, 0);
    atomic_store(&g_stats.video_frames_rendered, 0);
    atomic_store(&g_stats.video_frames_dropped, 0);
    atomic_store(&g_stats.video_decode_time_us_total, 0);
    atomic_store(&g_stats.video_decode_samples, 0);
    atomic_store(&g_stats.video_delay_us_total, 0);
    atomic_store(&g_stats.video_delay_samples, 0);
    atomic_store(&g_stats.video_width, 0);
    atomic_store(&g_stats.video_height, 0);
    atomic_store(&g_stats.video_codec, 0);
    atomic_store(&g_stats.video_decoder_resets, 0);
    atomic_store(&g_stats.audio_packets_in, 0);
    atomic_store(&g_stats.audio_packets_lost, 0);
    atomic_store(&g_stats.audio_frames_decoded, 0);
    atomic_store(&g_stats.audio_underruns, 0);
    atomic_store(&g_stats.audio_frames_dropped, 0);
    atomic_store(&g_stats.audio_ct, 0);
    atomic_store(&g_stats.audio_buffer_ms, 0);
    atomic_store(&g_stats.audio_last_rtp, 0);
    atomic_store(&g_stats.audio_last_rtp_ns, 0);
    atomic_store(&g_stats.clock_offset_us, 0);
    atomic_store(&g_stats.clock_synced, 0);
}
