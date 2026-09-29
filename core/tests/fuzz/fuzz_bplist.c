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

#include "bplist.h"

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    bp_node_t *root = bp_parse(data, size);
    if (root) {
        uint8_t *out = NULL;
        size_t len = 0;
        if (bp_write(root, &out, &len) == 0) {
            bp_node_t *again = bp_parse(out, len);
            if (!again) {
                abort(); /* anything we write must parse */
            }
            bp_free(again);
            free(out);
        }
        bp_free(root);
    }
    return 0;
}
