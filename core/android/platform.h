/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

/* Calls from the native pipeline into the Kotlin layer (implemented in jni_main.c). */

#ifndef AIRPLAYTV_PLATFORM_H
#define AIRPLAYTV_PLATFORM_H

#include <stdbool.h>

void platform_on_video_started(void);
void platform_on_video_stopped(void);
void platform_on_video_size(int width, int height);
void platform_on_audio_started(int sample_rate, int channels, bool low_latency);
void platform_on_audio_stopped(void);
void platform_on_volume(float gain);

#endif
