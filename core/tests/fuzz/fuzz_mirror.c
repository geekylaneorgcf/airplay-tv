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

#include "mirror.h"

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    uint8_t out[4096];
    video_codec_t codec = VIDEO_CODEC_NONE;
    mirror_parse_codec_config(data, size, &codec, out, sizeof(out));

    uint8_t *copy = (uint8_t *) malloc(size ? size : 1);
    if (!copy) {
        return 0;
    }
    memcpy(copy, data, size);
    bool key = false;
    mirror_avcc_to_annexb(copy, size, (size & 1) ? VIDEO_CODEC_H265 : VIDEO_CODEC_H264, &key);
    mirror_is_keyframe(copy, size, VIDEO_CODEC_H264);
    free(copy);
    return 0;
}
