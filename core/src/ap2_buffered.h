/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky and contributors
 *
 * AirPlay 2 buffered audio (stream type 103): the sender opens a TCP connection to the receiver and pushes sealed audio blocks
 * far ahead of their playing time, then tells the receiver with SETRATEANCHORTI when to play and with FLUSHBUFFERED what to
 * throw away. Normally "when" is a time on the PTP clock; this receiver has no PTP (UDP ports 319 and 320 cannot be opened without
 * root), so it plays from the anchor's RTP time as soon as the anchor arrives and paces the blocks itself. That serves one receiver
 * playing alone, which is what this is. The block format is read from the open shairport-sync implementation (MIT).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#ifndef AIRPLAYTV_AP2_BUFFERED_H
#define AIRPLAYTV_AP2_BUFFERED_H

#include <sys/socket.h>

#include "media.h"

typedef struct ap2_buffered ap2_buffered_t;

typedef struct {
    const media_sink_ops_t *ops;
    void *ops_ctx;
    uint8_t key[32];                  /* the stream's "shk" */
    struct sockaddr_storage peer;     /* only this host may connect */
    audio_format_t format;            /* what the stream starts as; a block can say it is something else (see ap2_format_for_ssrc) */
    const volatile float *volume_db;  /* the volume to give the audio output again when it is restarted for another format, may be NULL */
} ap2_buffered_params_t;

/* Opens the TCP port the sender connects to (and a UDP port it may send timing packets to, which are ignored) and starts the thread.
 * buffer_bytes is how much audio may wait in the receiver (it is told to the sender). Returns NULL on failure. */
ap2_buffered_t *ap2_buffered_start(const ap2_buffered_params_t *params, size_t buffer_bytes, uint16_t *data_port,
                                   uint16_t *control_port);
void ap2_buffered_stop(ap2_buffered_t *b);

/* SETRATEANCHORTI: play (true) from the block that contains anchor_rtp, or pause (false). */
void ap2_buffered_rate(ap2_buffered_t *b, bool play, uint32_t anchor_rtp, bool have_anchor);

/* FLUSHBUFFERED. Without a "from" the flush is immediate: playing stops and every block before until_seq goes, also those that arrive
 * later. With one, blocks from from_seq up to (not including) until_seq go. Sequence numbers are 23 bits. */
void ap2_buffered_flush(ap2_buffered_t *b, bool have_from, uint32_t from_seq, uint32_t until_seq);

/* The RTP time of the block being played, 0 and false when nothing has played yet. */
bool ap2_buffered_position(ap2_buffered_t *b, uint32_t *rtp);

/* ---- pieces exposed for tests ---- */

/* The formats of the stream setup's "audioFormat" bit: ALAC 44100 Hz (bit 18), AAC-LC 44100 Hz (bit 22), AAC-LC 48000 Hz (bit 23 of
 * the list, 0x800000). False for the others (ALAC at 48000 Hz and 24 bits, surround), which the audio output does not play. */
bool ap2_format_for_code(uint64_t code, audio_format_t *out);

/* The same for the codec id that every block carries (the SSRC field), as shairport-sync names them. */
bool ap2_format_for_ssrc(uint32_t ssrc, audio_format_t *out);

/* True when a comes before b in the 23-bit sequence space. */
bool ap2_seq_before(uint32_t a, uint32_t b);

#endif
