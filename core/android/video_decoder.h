/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#ifndef AIRPLAYTV_VIDEO_DECODER_H
#define AIRPLAYTV_VIDEO_DECODER_H

#include <android/native_window.h>

#include "media.h"

/* Low-latency options, most to least likely to be rejected by a decoder. */
#define VD_OPT_LOW_LATENCY   0x1   /* standard "low-latency" key (API 30) */
#define VD_OPT_VENDOR_KEYS   0x2   /* vendor specific low-latency keys */
#define VD_OPT_REALTIME      0x4   /* realtime priority / high operating rate */

void vd_init(void);
void vd_set_preferences(const char *avc_decoder, const char *hevc_decoder, int options, int max_width, int max_height);

/* Surface of the playback activity; NULL when it is gone. Takes its own reference. */
void vd_set_display(ANativeWindow *window);

/* media sink callbacks */
void vd_start(void);
void vd_config(video_codec_t codec, const uint8_t *config, size_t len, int width, int height);
void vd_frame(const uint8_t *au, size_t len, uint64_t remote_ts_ns, bool keyframe);
void vd_suspend(bool suspended);
void vd_stop(void);

#endif
