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

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    static alac_file *alac;
    static int16_t pcm[ALAC_MAX_FRAME_SAMPLES * 2];
    if (!alac) {
        alac = alac_create(16, 2);
        alac_set_config(alac, 352, 16, 40, 10, 14);
    }
    int out = 0;
    if (size > 0 && size < 1 << 16) {
        alac_decode_frame(alac, data, (int) size, pcm, (int) sizeof(pcm), &out);
        if (out < 0 || out > (int) sizeof(pcm)) {
            abort();
        }
    }
    return 0;
}
