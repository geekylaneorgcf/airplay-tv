/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky
 *
 * RAOP audio receiver: RTP over UDP with AES-CBC payload encryption, sync and
 * retransmission on the control channel. Packet formats as documented by
 * shairplay/UxPlay raop_rtp.c and raop_buffer.c (LGPL-2.1-or-later).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#ifndef AIRPLAYTV_AUDIO_RTP_H
#define AIRPLAYTV_AUDIO_RTP_H

#include <sys/socket.h>

#include "media.h"

typedef struct audio_receiver audio_receiver_t;

typedef struct {
    const media_sink_ops_t *ops;
    void *ops_ctx;
    uint8_t key[16];
    uint8_t iv[16];
    bool aead;                    /* AirPlay 2 realtime audio: ChaCha20-Poly1305 with aead_key instead of AES-CBC, no sync or resend */
    uint8_t aead_key[32];
    struct sockaddr_storage peer;
    uint16_t peer_control_port;   /* 0 disables retransmission requests */
    audio_format_t format;
} audio_params_t;

audio_receiver_t *audio_start(const audio_params_t *params, uint16_t *data_port, uint16_t *control_port);
void audio_stop(audio_receiver_t *a);

/* Drops buffered packets. next_seq < 0 means "resynchronise on the next packet". */
void audio_request_flush(audio_receiver_t *a, int next_seq);

/* ---- jitter buffer, exposed for tests ---- */

#define AUDIO_SLOTS 256
#define AUDIO_SLOT_SIZE 2048

typedef void (*audio_deliver_fn)(void *ctx, const uint8_t *data, size_t len, uint16_t seq, uint32_t rtp_ts);
typedef void (*audio_resend_fn)(void *ctx, uint16_t first_missing, uint16_t count);

typedef struct {
    bool filled;
    uint16_t seq;
    uint32_t rtp_ts;
    uint16_t len;
    uint8_t data[AUDIO_SLOT_SIZE];
} audio_slot_t;

typedef struct {
    audio_slot_t *slots;
    bool have_next;
    uint16_t next_seq;
    uint16_t highest_seq;
    uint64_t gap_since_ns;
    uint64_t max_wait_ns;
    uint64_t lost;
} audio_jitter_t;

int audio_jitter_init(audio_jitter_t *j, uint64_t max_wait_ns);
void audio_jitter_free(audio_jitter_t *j);
void audio_jitter_reset(audio_jitter_t *j, int next_seq);
/* Stores a decrypted payload. Returns false for late, duplicate or oversized packets. */
bool audio_jitter_put(audio_jitter_t *j, uint16_t seq, uint32_t rtp_ts, const uint8_t *payload, size_t len);
/* Delivers everything that is in order, skipping gaps older than max_wait. Returns the
 * number of nanoseconds until the next deadline, or 0 if no gap is pending. */
uint64_t audio_jitter_drain(audio_jitter_t *j, uint64_t now_ns, audio_deliver_fn deliver, audio_resend_fn resend, void *ctx);

#endif
