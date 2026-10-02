/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#ifndef AIRPLAYTV_AIRPLAY_H
#define AIRPLAYTV_AIRPLAY_H

#include "common.h"
#include "media.h"

#define AIRPLAY_MODEL "AppleTV3,2"
#define AIRPLAY_SOURCE_VERSION "220.68"
#define AIRPLAY_DEFAULT_PORT 7000

typedef struct airplay_server airplay_server_t;

typedef struct {
    char name[64];              /* UTF-8, shown in the sender's device list */
    uint8_t device_id[6];       /* stable random id, advertised like a MAC address */
    char public_id[40];         /* stable random UUID ("pi") */
    uint8_t identity_seed[32];  /* Ed25519 seed of the receiver identity */
    bool require_pin;
    uint16_t port;              /* preferred RTSP port, 0 for any */
    int display_width;
    int display_height;
    int display_fps;
    bool hevc;                  /* advertise H.265 mirroring */
    char model[24];             /* "model" / "am" in the advertisement, e.g. "AppleTV3,2"; empty = AIRPLAY_MODEL.
                                 * An iPhone draws the AirPlay icon from it. */
    char srcvers[16];           /* advertised source version; empty = AIRPLAY_SOURCE_VERSION */
    bool video;                 /* advertise AirPlay video (the sender gives a URL and the receiver plays it) */
} airplay_config_t;

typedef struct {
    void *ctx;
    /* A sender established a session (first SETUP). */
    void (*session_started)(void *ctx, const char *client_name, const char *client_model);
    void (*session_ended)(void *ctx);
    /* Show a PIN for pairing; NULL hides it again. */
    void (*pin_display)(void *ctx, const char *pin);
    /* A sender completed PIN pairing and should be remembered. */
    void (*client_paired)(void *ctx, const char *client_key_b64, const char *client_name);
    /* Track info pushed by the sender (DMAP). Strings are UTF-8, possibly empty. Optional. */
    void (*track_info)(void *ctx, const char *title, const char *artist, const char *album);
    /* Cover art as an encoded image (JPEG or PNG); only valid during the call. Optional. */
    void (*artwork)(void *ctx, const uint8_t *data, size_t len);
    /* Playback position in RTP timestamp units: start, current, end. Optional. */
    void (*progress)(void *ctx, uint32_t start, uint32_t current, uint32_t end);
    /* The sender's remote-control (DACP) identity, from the DACP-ID and Active-Remote headers.
     * Called when it first appears or changes. Optional. */
    void (*remote)(void *ctx, const char *dacp_id, const char *active_remote);
    /* A photo from the sender's Photos app (PUT /photo): an encoded JPEG or PNG, only valid during
     * the call. asset_key identifies it in the sender's cache. Optional. */
    void (*photo)(void *ctx, const char *asset_key, const uint8_t *data, size_t len);
    /* The photo session is over (POST /stop, or the sender closed its event connection). Optional. */
    void (*photo_stop)(void *ctx);
    /* A second sender asked to start a session while one is playing and was turned away (see airplay_server_set_takeover). Optional. */
    void (*session_blocked)(void *ctx, const char *client_name);
    /* AirPlay video: the sender asks to play the stream at url (HLS or a file, http or https). It starts at start_seconds, or, when
     * that is negative, at start_fraction (0..1) of its length. Optional. */
    void (*video_play)(void *ctx, const char *url, double start_seconds, double start_fraction);
    /* The sender sets the playback rate: 0 pauses, 1 plays. */
    void (*video_rate)(void *ctx, double rate);
    /* The sender moves to a place in the video. */
    void (*video_scrub)(void *ctx, double seconds);
    /* The video session is over (POST /stop, or the sender's event connection closed). */
    void (*video_end)(void *ctx);
} airplay_events_t;

typedef struct {
    char key[16];
    char value[160];
} txt_entry_t;

airplay_server_t *airplay_server_create(const airplay_config_t *config, const airplay_events_t *events,
                                        const media_sink_ops_t *media, void *media_ctx);
/* Binds the RTSP port and starts the server thread. Returns the port or -1. */
int airplay_server_start(airplay_server_t *s);
void airplay_server_stop(airplay_server_t *s);
void airplay_server_destroy(airplay_server_t *s);

/* Ends the current session from the receiver side (e.g. the user pressed Back). */
void airplay_server_disconnect(airplay_server_t *s);

/* What the player reports about the video that is playing, read when the sender asks (GET /playback-info, GET /scrub). */
void airplay_server_set_playback(airplay_server_t *s, double duration, double position, double rate, bool ready);

/* What happens when another sender starts a session while one is playing: AIRPLAY_TAKEOVER_REPLACE (the default) ends the
 * current session for it; AIRPLAY_TAKEOVER_KEEP turns the newcomer away (403) and reports it with session_blocked. */
#define AIRPLAY_TAKEOVER_REPLACE 0
#define AIRPLAY_TAKEOVER_KEEP 1
void airplay_server_set_takeover(airplay_server_t *s, int policy);

/* Replaces the list of remembered PIN-paired senders (base64 Ed25519 keys). */
void airplay_server_set_paired_clients(airplay_server_t *s, const char *const *keys, int count);

bool airplay_server_session_active(airplay_server_t *s);

uint64_t airplay_features(const airplay_config_t *config);
void airplay_public_key_hex(const airplay_server_t *s, char out[65]);

/* Service instance name of the RAOP record: "<DEVICEID>@<name>". */
void airplay_raop_name(const airplay_config_t *config, char *out, size_t cap);

/* TXT records of the _airplay._tcp and _raop._tcp services. Return the entry count. */
int airplay_txt_airplay(const airplay_server_t *s, txt_entry_t *out, int max);
int airplay_txt_raop(const airplay_server_t *s, txt_entry_t *out, int max);

/* DNS TXT wire encoding (length-prefixed "key=value" strings). */
size_t airplay_txt_encode(const txt_entry_t *entries, int count, uint8_t *out, size_t cap);

#ifdef AIRPLAYTV_FUZZING
void airplay_server_fuzz_input(airplay_server_t *s, const uint8_t *data, size_t len);
#endif

#endif
