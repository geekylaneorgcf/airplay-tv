/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky
 *
 * Screen mirroring stream receiver. Packet format and key derivation as
 * documented by RPiPlay/UxPlay raop_rtp_mirror.c and mirror_buffer.c
 * (dsafa22, Florian Draschbacher, F. Duncanh; LGPL-2.1-or-later).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#ifndef AIRPLAYTV_MIRROR_H
#define AIRPLAYTV_MIRROR_H

#include <sys/socket.h>

#include "media.h"
#include "ntp.h"

#define MIRROR_HEADER_LEN 128
#define MIRROR_MAX_PAYLOAD (32 * 1024 * 1024)

typedef struct mirror_receiver mirror_receiver_t;

typedef struct {
    const media_sink_ops_t *ops;
    void *ops_ctx;
    uint8_t session_key[16];
    uint64_t stream_connection_id;
    struct sockaddr_storage peer;
    /* Called from the receiver thread when the stream ends unexpectedly. */
    void (*on_closed)(void *ctx);
    void *closed_ctx;
} mirror_params_t;

/* Listens on a new TCP port (returned in *port) and starts the receiver thread. */
mirror_receiver_t *mirror_start(const mirror_params_t *params, uint16_t *port);
void mirror_stop(mirror_receiver_t *m);

/* Parsing helpers, exposed for tests. */

/* Converts length-prefixed NAL units to Annex-B in place. Returns the number of
 * NAL units, or -1 if the lengths do not describe the buffer exactly or a NAL
 * header is invalid (which is what a decryption failure looks like).
 * *keyframe is set if the access unit contains an IDR/IRAP picture. */
int mirror_avcc_to_annexb(uint8_t *data, size_t len, video_codec_t codec, bool *keyframe);

/* Returns true if the Annex-B access unit contains an IDR/IRAP picture. */
bool mirror_is_keyframe(const uint8_t *annexb, size_t len, video_codec_t codec);

/* Extracts parameter sets from an avcC or hvc1/hvcC codec packet payload into
 * Annex-B form. Returns the number of bytes written or -1. */
int mirror_parse_codec_config(const uint8_t *payload, size_t len, video_codec_t *codec,
                              uint8_t *out, size_t out_cap);

/* Derives the AES-CTR key and IV of a mirroring stream. */
void mirror_derive_keys(const uint8_t session_key[16], uint64_t stream_connection_id,
                        uint8_t key[16], uint8_t iv[16]);

#endif
