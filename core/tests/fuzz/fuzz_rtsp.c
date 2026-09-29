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

#include "rtsp.h"

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    size_t offset = 0;
    while (offset < size) {
        rtsp_request_t *req = (rtsp_request_t *) malloc(sizeof(rtsp_request_t));
        size_t used = 0;
        rtsp_parse_result_t r = rtsp_parse_request(data + offset, size - offset, req, &used);
        if (r != RTSP_PARSE_OK) {
            free(req);
            break;
        }
        if (used == 0 || used > size - offset) {
            abort();
        }
        (void) rtsp_header(req, "CSeq");
        rtsp_request_clear(req);
        free(req);
        offset += used;
    }
    return 0;
}
