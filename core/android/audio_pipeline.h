/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#ifndef AIRPLAYTV_AUDIO_PIPELINE_H
#define AIRPLAYTV_AUDIO_PIPELINE_H

#include "media.h"

bool ap_start(const audio_format_t *format);
void ap_frame(const uint8_t *data, size_t len, uint32_t rtp_ts, uint64_t remote_ts_ns);
void ap_flush(void);
void ap_volume(float db);
void ap_stop(void);

/* Called by the AudioTrack writer thread. Copies up to max_bytes of 16-bit stereo
 * PCM. Returns the number of bytes, 0 after timeout_ms without data, or -1 when
 * the audio stream has ended. */
int ap_read(uint8_t *out, int max_bytes, int timeout_ms);

#endif
