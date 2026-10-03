/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky
 *
 * AirPlay RTSP server and request handlers. The protocol behaviour (request
 * set, /info contents, SETUP key handling, stream setup and teardown) follows
 * UxPlay's raop.c and raop_handlers.h (Juho Vähä-Herttua, dsafa22,
 * F. Duncanh and contributors; LGPL-2.1-or-later), re-implemented around an
 * event-driven connection loop with strict validation of all input.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#include "airplay.h"

#include <errno.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

#include "ap2_pair.h"
#include "audio_rtp.h"
#include "bplist.h"
#include "crypto.h"
#include "fairplay.h"
#include "log.h"
#include "mirror.h"
#include "netutil.h"
#include "ntp.h"
#include "pairing.h"
#include "rtsp.h"
#include "util.h"

#define MAX_CONNECTIONS 8
#define RX_LIMIT ((size_t) RTSP_MAX_HEAD + RTSP_MAX_BODY)
#define IDLE_TIMEOUT_NS (60 * NS_PER_SEC)
#define PARTIAL_REQUEST_TIMEOUT_NS (15 * NS_PER_SEC)
#define SESSION_TIMEOUT_NS (90 * NS_PER_SEC)
#define PIN_TIMEOUT_NS (120 * NS_PER_SEC)

#define CT_BPLIST "application/x-apple-binary-plist"
#define CT_OCTET "application/octet-stream"
#define CT_PARAMS "text/parameters"

#define FEATURES_BASE 0x527FFEE6u     /* UxPlay's mirroring + audio feature set */
#define FEATURE_VIDEO (1ull << 0)
#define FEATURE_VIDEO_HLS (1ull << 4)
#define FEATURE_LEGACY_PAIRING (1u << 27)
#define FEATURE_SCREEN_MULTI_CODEC (1ull << 42)

typedef struct session session_t;
typedef struct conn conn_t;

/* Photos the sender asked us to keep (X-Apple-AssetAction: cacheOnly), so it can show them later
 * with displayCached without sending them again. Bounded in count and in bytes. */
#define PHOTO_CACHE_MAX 8
#define PHOTO_CACHE_BYTES (48u * 1024u * 1024u)
#define REVERSED_MAX_AGE_NS (6ull * 3600ull * NS_PER_SEC)

typedef struct {
    char key[64];
    uint8_t *data;
    size_t len;
    uint64_t used_ns;
} photo_entry_t;

struct conn {
    int fd;
    struct sockaddr_storage peer;
    uint8_t *rx;
    size_t rx_len;
    size_t rx_cap;
    uint64_t created_ns;
    uint64_t last_rx_ns;
    uint64_t partial_since_ns;
    bool rtsp;
    bool reversed;            /* upgraded to the sender's event channel (POST /reverse); stays open while idle */
    bool close_after_reply;
    bool closing;
    int trace;                /* requests of this connection shown so far, see trace_request */
    /* AirPlay 2 (cfg.airplay2): transient pairing, then every byte of the connection is sealed */
    ap2_pair_t *ap2_pair;
    ap2_cipher_t ap2c;
    uint8_t ap2_shared[AP2_SHARED_LEN];
    bool ap2_have_shared;
    bool ap2_pending;         /* the pairing finished with the reply being sent: the next bytes in both directions are sealed */
    uint8_t *wire;            /* sealed bytes received and not opened yet */
    size_t wire_len;
    size_t wire_cap;
    struct ap2_events *events; /* the event connection of this sender */
    pairing_session_t pair;
    bool pair_trusted;        /* PIN mode: pair-verify proved a paired identity */
    fairplay_t fp;
    session_t *session;
};

static void ap2_free_conn(conn_t *c);

struct session {
    airplay_server_t *server;
    conn_t *owner;
    uint32_t id;
    uint8_t key[16];
    uint8_t iv[16];
    ntp_client_t *ntp;
    mirror_receiver_t *mirror;
    audio_receiver_t *audio;
    char client_name[64];
    char client_model[32];
    _Atomic bool stream_lost;
};

struct airplay_server {
    airplay_config_t cfg;
    airplay_events_t ev;
    const media_sink_ops_t *media;
    void *media_ctx;
    pairing_identity_t identity;
    uint64_t features;
    char pk_hex[65];
    char device_id_str[18];
    photo_entry_t photos[PHOTO_CACHE_MAX];
    size_t photo_bytes;
    bool photo_shown;         /* a photo is on screen, so the end of the photo session must be reported */
    char dacp_id[48];         /* last remote-control identity reported to the events */
    char active_remote[48];

    int listen_fd[2];
    uint16_t port;
    wakeup_t wake;
    pthread_t thread;
    bool thread_started;
    _Atomic bool running;
    _Atomic bool disconnect_requested;
    _Atomic bool session_active;
    _Atomic int takeover;     /* AIRPLAY_TAKEOVER_* */

    conn_t *conns[MAX_CONNECTIONS];
    session_t *active;
    uint32_t next_session_id;
    uint64_t last_media_ns;
    uint64_t last_media_count;

    char pin[8];
    bool pin_active;
    uint64_t pin_since_ns;
    conn_t *pin_owner;
    float volume_db;

    pthread_mutex_t paired_lock;
    char **paired;
    int paired_count;

    /* AirPlay video: a /play was accepted and no /stop has come; what the player last reported (under play_lock) */
    bool video_active;
    char video_session[64];
    pthread_mutex_t play_lock;
    double play_duration;
    double play_position;
    double play_rate;
    bool play_ready;
};

/* ------------------------------------------------------------------------- */
/* identity, features, TXT records                                            */

/* The AirPlay 2 feature set of shairport-sync (audio, metadata the classic way, transient pairing, core utils pairing and
 * encryption), without bits 40 (buffered audio) and 41 (PTP) in mode 1: this receiver cannot keep PTP time, which needs UDP ports
 * 319 and 320, so it offers realtime audio only. Mode 2 sets them as well, to see what a sender does with them. */
#define FEATURES_AP2 0x00018040405F4A00ull

uint64_t airplay_features(const airplay_config_t *config) {
    if (config->airplay2 > 0) {
        return FEATURES_AP2 | (config->airplay2 >= 2 ? ((1ull << 40) | (1ull << 41)) : 0);
    }
    uint64_t f = FEATURES_BASE;
    if (config->require_pin) {
        f |= FEATURE_LEGACY_PAIRING;
    }
    if (config->hevc) {
        f |= FEATURE_SCREEN_MULTI_CODEC;
    }
    if (config->video) {
        f |= FEATURE_VIDEO | FEATURE_VIDEO_HLS;
    }
    return f;
}

void airplay_public_key_hex(const airplay_server_t *s, char out[65]) {
    memcpy(out, s->pk_hex, 65);
}

void airplay_raop_name(const airplay_config_t *config, char *out, size_t cap) {
    char id[13];
    format_device_id_compact(config->device_id, id);
    snprintf(out, cap, "%s@%s", id, config->name);
}

static int txt_add(txt_entry_t *out, int max, int n, const char *key, const char *value) {
    if (n < max) {
        str_copy(out[n].key, sizeof(out[n].key), key);
        str_copy(out[n].value, sizeof(out[n].value), value);
        n++;
    }
    return n;
}

static void features_string(uint64_t features, char *out, size_t cap) {
    snprintf(out, cap, "0x%X,0x%X", (unsigned) (features & 0xffffffffu), (unsigned) (features >> 32));
}

/* "fex": the eight bytes of the features, least significant first, in base64 without padding */
static void fex_string(uint64_t features, char *out, size_t cap) {
    uint8_t b[8];
    for (int i = 0; i < 8; i++) {
        b[i] = (uint8_t) (features >> (8 * i));
    }
    char text[16];
    base64_encode(b, 8, text);
    size_t n = strlen(text);
    while (n > 0 && text[n - 1] == '=') {
        text[--n] = '\0';
    }
    str_copy(out, cap, text);
}

/* a second UUID for "psi", made from the first so that it is as stable as it */
static void psi_string(const airplay_server_t *s, char *out, size_t cap) {
    uint8_t h[SHA512_SIZE];
    sha512_2("psi:", 4, s->cfg.public_id, strlen(s->cfg.public_id), h);
    h[6] = (uint8_t) ((h[6] & 0x0f) | 0x40);
    h[8] = (uint8_t) ((h[8] & 0x3f) | 0x80);
    snprintf(out, cap, "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x", h[0], h[1], h[2], h[3], h[4], h[5],
             h[6], h[7], h[8], h[9], h[10], h[11], h[12], h[13], h[14], h[15]);
}

int airplay_txt_airplay(const airplay_server_t *s, txt_entry_t *out, int max) {
    char features[32];
    features_string(s->features, features, sizeof(features));
    int n = 0;
    if (s->cfg.airplay2 > 0) {
        char fex[24], psi[40];
        fex_string(s->features, fex, sizeof(fex));
        psi_string(s, psi, sizeof(psi));
        n = txt_add(out, max, n, "acl", "0");
        n = txt_add(out, max, n, "deviceid", s->device_id_str);
        n = txt_add(out, max, n, "fex", fex);
        n = txt_add(out, max, n, "features", features);
        n = txt_add(out, max, n, "flags", "0x4");
        n = txt_add(out, max, n, "gid", s->cfg.public_id);
        n = txt_add(out, max, n, "igl", "0");
        n = txt_add(out, max, n, "gcgl", "0");
        n = txt_add(out, max, n, "model", s->cfg.model);
        n = txt_add(out, max, n, "protovers", "1.1");
        n = txt_add(out, max, n, "pi", s->cfg.public_id);
        n = txt_add(out, max, n, "psi", psi);
        n = txt_add(out, max, n, "pk", s->pk_hex);
        n = txt_add(out, max, n, "srcvers", s->cfg.srcvers);
        n = txt_add(out, max, n, "osvers", "16.0");
        n = txt_add(out, max, n, "vv", "2");
        return n;
    }
    n = txt_add(out, max, n, "deviceid", s->device_id_str);
    n = txt_add(out, max, n, "features", features);
    n = txt_add(out, max, n, "flags", "0x4");
    n = txt_add(out, max, n, "model", s->cfg.model);
    n = txt_add(out, max, n, "pk", s->pk_hex);
    n = txt_add(out, max, n, "pi", s->cfg.public_id);
    n = txt_add(out, max, n, "pw", s->cfg.require_pin ? "true" : "false");
    n = txt_add(out, max, n, "srcvers", s->cfg.srcvers);
    n = txt_add(out, max, n, "vv", "2");
    return n;
}

int airplay_txt_raop(const airplay_server_t *s, txt_entry_t *out, int max) {
    char features[32];
    features_string(s->features, features, sizeof(features));
    int n = 0;
    if (s->cfg.airplay2 > 0) {
        n = txt_add(out, max, n, "cn", "0,1");
        n = txt_add(out, max, n, "da", "true");
        n = txt_add(out, max, n, "et", "0,1");
        n = txt_add(out, max, n, "ft", features);
        n = txt_add(out, max, n, "fv", "p20.78000012.3");
        n = txt_add(out, max, n, "sf", "0x4");
        n = txt_add(out, max, n, "md", "0,1,2");
        n = txt_add(out, max, n, "am", s->cfg.model);
        n = txt_add(out, max, n, "pk", s->pk_hex);
        n = txt_add(out, max, n, "tp", "UDP");
        n = txt_add(out, max, n, "vn", "65537");
        n = txt_add(out, max, n, "vs", s->cfg.srcvers);
        n = txt_add(out, max, n, "ov", "16.0");
        return n;
    }
    n = txt_add(out, max, n, "ch", "2");
    n = txt_add(out, max, n, "cn", "0,1,2,3");
    n = txt_add(out, max, n, "da", "true");
    n = txt_add(out, max, n, "et", "0,3,5");
    n = txt_add(out, max, n, "vv", "2");
    n = txt_add(out, max, n, "ft", features);
    n = txt_add(out, max, n, "am", s->cfg.model);
    n = txt_add(out, max, n, "md", "0,1,2");
    n = txt_add(out, max, n, "rhd", "5.6.0.0");
    n = txt_add(out, max, n, "pw", s->cfg.require_pin ? "true" : "false");
    n = txt_add(out, max, n, "sr", "44100");
    n = txt_add(out, max, n, "ss", "16");
    n = txt_add(out, max, n, "sv", "false");
    n = txt_add(out, max, n, "tp", "UDP");
    n = txt_add(out, max, n, "txtvers", "1");
    n = txt_add(out, max, n, "sf", s->cfg.require_pin ? "0x8c" : "0x4");
    n = txt_add(out, max, n, "vs", s->cfg.srcvers);
    n = txt_add(out, max, n, "vn", "65537");
    n = txt_add(out, max, n, "pk", s->pk_hex);
    return n;
}

size_t airplay_txt_encode(const txt_entry_t *entries, int count, uint8_t *out, size_t cap) {
    size_t pos = 0;
    for (int i = 0; i < count; i++) {
        size_t klen = strlen(entries[i].key);
        size_t vlen = strlen(entries[i].value);
        size_t len = klen + 1 + vlen;
        if (len > 255 || pos + 1 + len > cap) {
            return 0;
        }
        out[pos++] = (uint8_t) len;
        memcpy(out + pos, entries[i].key, klen);
        pos += klen;
        out[pos++] = '=';
        memcpy(out + pos, entries[i].value, vlen);
        pos += vlen;
    }
    return pos;
}

/* ------------------------------------------------------------------------- */
/* paired clients                                                             */

void airplay_server_set_paired_clients(airplay_server_t *s, const char *const *keys, int count) {
    pthread_mutex_lock(&s->paired_lock);
    for (int i = 0; i < s->paired_count; i++) {
        free(s->paired[i]);
    }
    free(s->paired);
    s->paired = NULL;
    s->paired_count = 0;
    if (count > 0) {
        s->paired = (char **) calloc((size_t) count, sizeof(char *));
        if (s->paired) {
            for (int i = 0; i < count; i++) {
                if (keys[i] && (s->paired[s->paired_count] = strdup(keys[i])) != NULL) {
                    s->paired_count++;
                }
            }
        }
    }
    pthread_mutex_unlock(&s->paired_lock);
}

static bool is_paired(airplay_server_t *s, const char *key_b64) {
    bool found = false;
    pthread_mutex_lock(&s->paired_lock);
    for (int i = 0; i < s->paired_count && !found; i++) {
        found = strcmp(s->paired[i], key_b64) == 0;
    }
    pthread_mutex_unlock(&s->paired_lock);
    return found;
}

static void add_paired(airplay_server_t *s, const char *key_b64) {
    pthread_mutex_lock(&s->paired_lock);
    bool found = false;
    for (int i = 0; i < s->paired_count && !found; i++) {
        found = strcmp(s->paired[i], key_b64) == 0;
    }
    if (!found) {
        char **grown = (char **) realloc(s->paired, ((size_t) s->paired_count + 1) * sizeof(char *));
        if (grown) {
            s->paired = grown;
            s->paired[s->paired_count] = strdup(key_b64);
            if (s->paired[s->paired_count]) {
                s->paired_count++;
            }
        }
    }
    pthread_mutex_unlock(&s->paired_lock);
}

static void key_to_b64(const uint8_t key[ED25519_KEY_SIZE], char out[48]) {
    base64_encode(key, ED25519_KEY_SIZE, out);
}

/* ------------------------------------------------------------------------- */
/* sessions                                                                   */

static void on_stream_lost(void *ctx) {
    session_t *session = (session_t *) ctx;
    atomic_store(&session->stream_lost, true);
    wakeup_signal(&session->server->wake);
}

static void session_stop_video(session_t *session) {
    airplay_server_t *s = session->server;
    if (session->mirror) {
        mirror_stop(session->mirror);
        session->mirror = NULL;
        if (s->media->video_stop) {
            s->media->video_stop(s->media_ctx);
        }
    }
}

static void session_stop_audio(session_t *session) {
    airplay_server_t *s = session->server;
    if (session->audio) {
        audio_stop(session->audio);
        session->audio = NULL;
        if (s->media->audio_stop) {
            s->media->audio_stop(s->media_ctx);
        }
    }
}

static void session_destroy(airplay_server_t *s, session_t *session) {
    if (!session) {
        return;
    }
    session_stop_video(session);
    session_stop_audio(session);
    ntp_client_stop(session->ntp);
    session->ntp = NULL;
    if (session->owner) {
        session->owner->session = NULL;
    }
    if (s->active == session) {
        s->active = NULL;
        atomic_store(&s->session_active, false);
        s->dacp_id[0] = '\0';
        s->active_remote[0] = '\0';
        LOG_I(SESSION, "session %u ended", session->id);
        if (s->ev.session_ended) {
            s->ev.session_ended(s->ev.ctx);
        }
    }
    secure_zero(session->key, sizeof(session->key));
    free(session);
}

/* ------------------------------------------------------------------------- */
/* connections                                                                */

static void hide_pin(airplay_server_t *s);

/* ------------------------------------------------------------------------- */
/* photos (HTTP): PUT /photo, POST /reverse, POST /stop, GET /server-info     */

static void photo_cache_clear(airplay_server_t *s) {
    for (int i = 0; i < PHOTO_CACHE_MAX; i++) {
        free(s->photos[i].data);
        memset(&s->photos[i], 0, sizeof(s->photos[i]));
    }
    s->photo_bytes = 0;
}

static photo_entry_t *photo_cache_find(airplay_server_t *s, const char *key) {
    for (int i = 0; i < PHOTO_CACHE_MAX; i++) {
        if (s->photos[i].data && strcmp(s->photos[i].key, key) == 0) {
            return &s->photos[i];
        }
    }
    return NULL;
}

static void photo_cache_drop(airplay_server_t *s, photo_entry_t *e) {
    s->photo_bytes -= e->len;
    free(e->data);
    memset(e, 0, sizeof(*e));
}

/* Keeps a copy of a photo under its asset key, evicting the least recently used ones when the cache
 * would grow past its limits. A photo that alone exceeds the byte limit is not kept. */
static void photo_cache_put(airplay_server_t *s, const char *key, const uint8_t *data, size_t len) {
    if (!key[0] || strlen(key) >= sizeof(s->photos[0].key) || len == 0 || len > PHOTO_CACHE_BYTES) {
        return;
    }
    photo_entry_t *old = photo_cache_find(s, key);
    if (old) {
        photo_cache_drop(s, old);
    }
    for (;;) {
        photo_entry_t *free_slot = NULL;
        photo_entry_t *oldest = NULL;
        for (int i = 0; i < PHOTO_CACHE_MAX; i++) {
            photo_entry_t *e = &s->photos[i];
            if (!e->data) {
                free_slot = e;
            } else if (!oldest || e->used_ns < oldest->used_ns) {
                oldest = e;
            }
        }
        if (free_slot && s->photo_bytes + len <= PHOTO_CACHE_BYTES) {
            uint8_t *copy = (uint8_t *) malloc(len);
            if (!copy) {
                return;
            }
            memcpy(copy, data, len);
            str_copy(free_slot->key, sizeof(free_slot->key), key);
            free_slot->data = copy;
            free_slot->len = len;
            free_slot->used_ns = time_mono_ns();
            s->photo_bytes += len;
            return;
        }
        if (!oldest) {
            return;
        }
        photo_cache_drop(s, oldest);
    }
}

static void photo_show(airplay_server_t *s, const char *key, const uint8_t *data, size_t len) {
    s->photo_shown = true;
    if (s->ev.photo) {
        s->ev.photo(s->ev.ctx, key, data, len);
    }
}

static void photo_stop(airplay_server_t *s) {
    photo_cache_clear(s);
    if (s->photo_shown) {
        s->photo_shown = false;
        if (s->ev.photo_stop) {
            s->ev.photo_stop(s->ev.ctx);
        }
    }
}

static bool is_image(const uint8_t *d, size_t n) {
    static const uint8_t png[8] = { 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n' };
    return (n >= 3 && d[0] == 0xff && d[1] == 0xd8 && d[2] == 0xff) || (n >= 8 && memcmp(d, png, sizeof(png)) == 0);
}

/* HTTP replies need an explicit length; the RTSP serializer leaves it out when there is no body. */
static void http_empty(rtsp_reply_t *reply, int status, const char *reason) {
    rtsp_reply_status(reply, status, reason);
    rtsp_reply_header(reply, "Content-Length", "0");
}

static bool path_is(const char *url, const char *path) {
    size_t n = strlen(path);
    return strncmp(url, path, n) == 0 && (url[n] == '\0' || url[n] == '?');
}

static void handle_server_info(airplay_server_t *s, rtsp_reply_t *reply) {
    char xml[768];
    int n = snprintf(xml, sizeof(xml),
                     "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                     "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" "
                     "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
                     "<plist version=\"1.0\"><dict>\n"
                     "<key>deviceid</key><string>%s</string>\n"
                     "<key>features</key><integer>%llu</integer>\n"
                     "<key>model</key><string>%s</string>\n"
                     "<key>protovers</key><string>1.0</string>\n"
                     "<key>srcvers</key><string>%s</string>\n"
                     "</dict></plist>\n",
                     s->device_id_str, (unsigned long long) s->features, s->cfg.model, s->cfg.srcvers);
    if (n <= 0 || (size_t) n >= sizeof(xml) || !rtsp_reply_body_copy(reply, "text/x-apple-plist+xml", xml, (size_t) n)) {
        http_empty(reply, 500, "Internal Server Error");
        return;
    }
    rtsp_reply_status(reply, 200, "OK");
}

static void handle_photo(airplay_server_t *s, const rtsp_request_t *req, rtsp_reply_t *reply) {
    const char *key = rtsp_header(req, "X-Apple-AssetKey");
    const char *action = rtsp_header(req, "X-Apple-AssetAction");
    if (!key) {
        key = "";
    }
    if (action && str_ieq(action, "displayCached")) {
        photo_entry_t *e = key[0] ? photo_cache_find(s, key) : NULL;
        if (!e) {
            LOG_W(AIRPLAY, "photo was not cached, asking the sender to send it again");
            http_empty(reply, 412, "Precondition Failed");
            return;
        }
        e->used_ns = time_mono_ns();
        photo_show(s, key, e->data, e->len);
        http_empty(reply, 200, "OK");
        return;
    }
    if (!req->body || req->body_len == 0 || !is_image(req->body, req->body_len)) {
        LOG_W(AIRPLAY, "photo upload without a JPEG or PNG body");
        http_empty(reply, 400, "Bad Request");
        return;
    }
    photo_cache_put(s, key, req->body, req->body_len);
    if (!(action && str_ieq(action, "cacheOnly"))) {
        photo_show(s, key, req->body, req->body_len);
    }
    http_empty(reply, 200, "OK");
}

/* ------------------------------------------------------------------------- */
/* video (HTTP): POST /play, GET /playback-info, POST /rate, POST|GET /scrub    */

static bp_node_t *parse_plist_body(const rtsp_request_t *req);

void airplay_server_set_playback(airplay_server_t *s, double duration, double position, double rate, bool ready) {
    if (!s) {
        return;
    }
    pthread_mutex_lock(&s->play_lock);
    s->play_duration = duration;
    s->play_position = position;
    s->play_rate = rate;
    s->play_ready = ready;
    pthread_mutex_unlock(&s->play_lock);
}

/* The value of the query parameter name in url ("/rate?value=1.0"), parsed as a number; false when it is not there. */
static bool query_number(const char *url, const char *name, double *out) {
    const char *q = strchr(url, '?');
    size_t n = strlen(name);
    while (q) {
        q++;
        if (strncmp(q, name, n) == 0 && q[n] == '=') {
            char *end = NULL;
            double v = strtod(q + n + 1, &end);
            if (end == q + n + 1) {
                return false;
            }
            *out = v;
            return true;
        }
        q = strchr(q, '&');
    }
    return false;
}

static void video_end(airplay_server_t *s) {
    if (!s->video_active) {
        return;
    }
    s->video_active = false;
    s->video_session[0] = '\0';
    airplay_server_set_playback(s, 0, 0, 0, false);
    if (s->ev.video_end) {
        s->ev.video_end(s->ev.ctx);
    }
}

/* The body of a /play is a binary plist (Content-Location, Start-Position-Seconds or Start-Position), or, from older senders,
 * lines of "Name: value" (text/parameters). */
static bool parse_play(const rtsp_request_t *req, char *url, size_t url_cap, double *seconds, double *fraction) {
    *seconds = -1;
    *fraction = 0;
    url[0] = '\0';
    bp_node_t *root = parse_plist_body(req);
    if (root && bp_type(root) == BP_DICT) {
        const char *loc = bp_get_string(bp_dict_get(root, "Content-Location"));
        if (loc) {
            str_copy(url, url_cap, loc);
        }
        double v = 0;
        if (bp_get_real(bp_dict_get(root, "Start-Position-Seconds"), &v) && v >= 0) {
            *seconds = v;
        } else {
            int64_t iv = 0;
            if (bp_get_int(bp_dict_get(root, "Start-Position-Seconds"), &iv) && iv >= 0) {
                *seconds = (double) iv;
            } else if (bp_get_real(bp_dict_get(root, "Start-Position"), &v) && v >= 0 && v <= 1) {
                *fraction = v;
            }
        }
        bp_free(root);
    } else {
        bp_free(root);
        if (req->body && req->body_len > 0) {
            char text[2048];
            size_t n = req->body_len < sizeof(text) - 1 ? req->body_len : sizeof(text) - 1;
            memcpy(text, req->body, n);
            text[n] = '\0';
            for (char *line = strtok(text, "\r\n"); line; line = strtok(NULL, "\r\n")) {
                if (strncmp(line, "Content-Location:", 17) == 0) {
                    const char *v = line + 17;
                    while (*v == ' ') {
                        v++;
                    }
                    str_copy(url, url_cap, v);
                } else if (strncmp(line, "Start-Position:", 15) == 0) {
                    double v = strtod(line + 15, NULL);
                    if (v >= 0 && v <= 1) {
                        *fraction = v;
                    }
                }
            }
        }
    }
    return strncmp(url, "http://", 7) == 0 || strncmp(url, "https://", 8) == 0;
}

static void handle_play(airplay_server_t *s, const rtsp_request_t *req, rtsp_reply_t *reply) {
    char url[1536];
    double seconds = -1;
    double fraction = 0;
    if (!parse_play(req, url, sizeof(url), &seconds, &fraction)) {
        LOG_W(AIRPLAY, "/play without a usable address");
        http_empty(reply, 400, "Bad Request");
        return;
    }
    const char *session = rtsp_header(req, "X-Apple-Session-ID");
    if (s->video_active) {
        /* a new /play replaces the one that plays */
        video_end(s);
    }
    s->video_active = true;
    str_copy(s->video_session, sizeof(s->video_session), session ? session : "");
    airplay_server_set_playback(s, 0, seconds > 0 ? seconds : 0, 1, false);
    LOG_I(AIRPLAY, "video: playing a stream sent by the sender");
    if (s->ev.video_play) {
        s->ev.video_play(s->ev.ctx, url, seconds, fraction);
    } else {
        s->video_active = false;
        http_empty(reply, 501, "Not Implemented");
        return;
    }
    http_empty(reply, 200, "OK");
}

static void handle_playback_info(airplay_server_t *s, rtsp_reply_t *reply) {
    double duration;
    double position;
    double rate;
    bool ready;
    pthread_mutex_lock(&s->play_lock);
    duration = s->play_duration;
    position = s->play_position;
    rate = s->play_rate;
    ready = s->play_ready;
    pthread_mutex_unlock(&s->play_lock);
    char xml[2048];
    int n;
    if (!s->video_active) {
        n = snprintf(xml, sizeof(xml),
                     "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                     "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
                     "<plist version=\"1.0\"><dict>\n<key>readyToPlay</key><false/>\n</dict></plist>\n");
    } else {
        n = snprintf(xml, sizeof(xml),
                     "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                     "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
                     "<plist version=\"1.0\"><dict>\n"
                     "<key>duration</key><real>%.3f</real>\n"
                     "<key>position</key><real>%.3f</real>\n"
                     "<key>rate</key><real>%.3f</real>\n"
                     "<key>readyToPlay</key><%s/>\n"
                     "<key>playbackBufferEmpty</key><false/>\n"
                     "<key>playbackBufferFull</key><true/>\n"
                     "<key>playbackLikelyToKeepUp</key><true/>\n"
                     "<key>stallCount</key><integer>0</integer>\n"
                     "<key>loadedTimeRanges</key><array><dict><key>duration</key><real>%.3f</real><key>start</key><real>0.0</real></dict></array>\n"
                     "<key>seekableTimeRanges</key><array><dict><key>duration</key><real>%.3f</real><key>start</key><real>0.0</real></dict></array>\n"
                     "</dict></plist>\n",
                     duration, position, rate, ready ? "true" : "false", duration, duration);
    }
    if (n <= 0 || (size_t) n >= sizeof(xml) || !rtsp_reply_body_copy(reply, "text/x-apple-plist+xml", xml, (size_t) n)) {
        http_empty(reply, 500, "Internal Server Error");
        return;
    }
    rtsp_reply_status(reply, 200, "OK");
}

static void handle_rate(airplay_server_t *s, const char *url, rtsp_reply_t *reply) {
    double rate = 0;
    if (!query_number(url, "value", &rate) || !s->video_active) {
        http_empty(reply, s->video_active ? 400 : 454, s->video_active ? "Bad Request" : "Session Not Found");
        return;
    }
    pthread_mutex_lock(&s->play_lock);
    s->play_rate = rate;
    pthread_mutex_unlock(&s->play_lock);
    if (s->ev.video_rate) {
        s->ev.video_rate(s->ev.ctx, rate);
    }
    http_empty(reply, 200, "OK");
}

static void handle_scrub(airplay_server_t *s, const char *method, const char *url, rtsp_reply_t *reply) {
    if (!s->video_active) {
        http_empty(reply, 454, "Session Not Found");
        return;
    }
    if (strcmp(method, "POST") == 0) {
        double position = 0;
        if (!query_number(url, "position", &position) || position < 0) {
            http_empty(reply, 400, "Bad Request");
            return;
        }
        pthread_mutex_lock(&s->play_lock);
        s->play_position = position;
        pthread_mutex_unlock(&s->play_lock);
        if (s->ev.video_scrub) {
            s->ev.video_scrub(s->ev.ctx, position);
        }
        http_empty(reply, 200, "OK");
        return;
    }
    char text[96];
    pthread_mutex_lock(&s->play_lock);
    int n = snprintf(text, sizeof(text), "duration: %.3f\r\nposition: %.3f\r\n", s->play_duration, s->play_position);
    pthread_mutex_unlock(&s->play_lock);
    if (n <= 0 || !rtsp_reply_body_copy(reply, CT_PARAMS, text, (size_t) n)) {
        http_empty(reply, 500, "Internal Server Error");
        return;
    }
    rtsp_reply_status(reply, 200, "OK");
}

/* The HTTP/1.1 side of AirPlay on the same port as RTSP: photos from the Photos app and, when video is on, URL playback
 * (/play, /playback-info, /rate, /scrub). Everything else (slideshows) is refused, and logged so a refused attempt can be recognised. */
static void handle_http(airplay_server_t *s, conn_t *c, const rtsp_request_t *req, rtsp_reply_t *reply) {
    const char *m = req->method;
    const char *url = req->url;
    if (s->cfg.require_pin && !c->pair_trusted) {
        LOG_W(AIRPLAY, "HTTP %s %s refused: this receiver requires a PIN", m, url);
        http_empty(reply, 403, "Forbidden");
        reply->close_connection = true;
        return;
    }
    if (strcmp(m, "GET") == 0 && path_is(url, "/server-info")) {
        handle_server_info(s, reply);
    } else if (strcmp(m, "POST") == 0 && path_is(url, "/reverse")) {
        /* The sender turns this connection around to receive events from us. We have none to send, but
         * it must stay open for as long as the photo session lasts. */
        rtsp_reply_status(reply, 101, "Switching Protocols");
        rtsp_reply_header(reply, "Upgrade", "PTTH/1.0");
        rtsp_reply_header(reply, "Connection", "Upgrade");
        c->reversed = true;
    } else if (strcmp(m, "PUT") == 0 && path_is(url, "/photo")) {
        handle_photo(s, req, reply);
    } else if (strcmp(m, "POST") == 0 && path_is(url, "/stop")) {
        photo_stop(s);
        video_end(s);
        http_empty(reply, 200, "OK");
    } else if (s->cfg.video && strcmp(m, "POST") == 0 && path_is(url, "/play")) {
        handle_play(s, req, reply);
    } else if (s->cfg.video && strcmp(m, "GET") == 0 && path_is(url, "/playback-info")) {
        handle_playback_info(s, reply);
    } else if (s->cfg.video && strcmp(m, "POST") == 0 && path_is(url, "/rate")) {
        handle_rate(s, url, reply);
    } else if (s->cfg.video && path_is(url, "/scrub") && (strcmp(m, "POST") == 0 || strcmp(m, "GET") == 0)) {
        handle_scrub(s, m, url, reply);
    } else if (s->cfg.video && (path_is(url, "/setProperty") || path_is(url, "/getProperty") || path_is(url, "/action"))) {
        http_empty(reply, 200, "OK");
    } else {
        /* slideshows (and AirPlay video, unless it is switched on) are not offered by this receiver. */
        LOG_W(AIRPLAY, "HTTP %s %s is not supported", m, url);
        http_empty(reply, 501, "Not Implemented");
        reply->close_connection = true;
    }
}

static void conn_close(airplay_server_t *s, int index) {
    conn_t *c = s->conns[index];
    if (!c) {
        return;
    }
    if (s->pin_owner == c) {
        /* the sender that asked for a PIN went away: take the code off the screen */
        s->pin_owner = NULL;
        hide_pin(s);
    }
    if (c->session) {
        session_destroy(s, c->session);
    }
    if (c->reversed) {
        photo_stop(s);
        video_end(s);
    }
    net_close(&c->fd);
    ap2_free_conn(c);
    pairing_session_clear(&c->pair);
    fairplay_reset(&c->fp);
    free(c->rx);
    free(c);
    s->conns[index] = NULL;
}

static int conn_index(airplay_server_t *s, const conn_t *c) {
    for (int i = 0; i < MAX_CONNECTIONS; i++) {
        if (s->conns[i] == c) {
            return i;
        }
    }
    return -1;
}

static void configure_control_socket(int fd) {
    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &one, sizeof(one));
#ifdef TCP_KEEPIDLE
    int idle = 15;
    int interval = 5;
    int count = 3;
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPIDLE, &idle, sizeof(idle));
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPINTVL, &interval, sizeof(interval));
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPCNT, &count, sizeof(count));
#endif
}

static void accept_connections(airplay_server_t *s, int listen_fd) {
    for (;;) {
        struct sockaddr_storage peer;
        socklen_t peer_len = sizeof(peer);
        int fd = accept4(listen_fd, (struct sockaddr *) &peer, &peer_len, SOCK_NONBLOCK | SOCK_CLOEXEC);
        if (fd < 0) {
            return;
        }
        net_normalize_addr(&peer, NULL);
        char addr[64];
        net_addr_to_string(&peer, addr, sizeof(addr));
        if (!net_is_lan_peer(&peer)) {
            LOG_W(NETWORK, "refused connection from outside the local network (%s)", addr);
            stat_add(&g_stats.connections_rejected, 1);
            close(fd);
            continue;
        }
        int slot = -1;
        for (int i = 0; i < MAX_CONNECTIONS; i++) {
            if (!s->conns[i]) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            /* make room by dropping the oldest connection that has no session */
            uint64_t oldest = UINT64_MAX;
            for (int i = 0; i < MAX_CONNECTIONS; i++) {
                if (s->conns[i] && !s->conns[i]->session && s->conns[i]->created_ns < oldest) {
                    oldest = s->conns[i]->created_ns;
                    slot = i;
                }
            }
            if (slot < 0) {
                LOG_W(NETWORK, "too many connections, refusing %s", addr);
                close(fd);
                continue;
            }
            conn_close(s, slot);
        }
        conn_t *c = (conn_t *) calloc(1, sizeof(conn_t));
        if (!c) {
            close(fd);
            continue;
        }
        c->fd = fd;
        c->peer = peer;
        c->created_ns = c->last_rx_ns = time_mono_ns();
        pairing_session_init(&c->pair);
        fairplay_reset(&c->fp);
        configure_control_socket(fd);
        s->conns[slot] = c;
        LOG_I(NETWORK, "connection from %s", addr);
    }
}

/* ------------------------------------------------------------------------- */
/* request helpers                                                            */

static bp_node_t *parse_plist_body(const rtsp_request_t *req) {
    if (!req->body || req->body_len == 0) {
        return NULL;
    }
    return bp_parse(req->body, req->body_len);
}

static void reply_plist(rtsp_reply_t *reply, bp_node_t *root) {
    uint8_t *data = NULL;
    size_t len = 0;
    if (root && bp_write(root, &data, &len) == 0) {
        rtsp_reply_body(reply, CT_BPLIST, data, len);
    } else {
        rtsp_reply_status(reply, 500, "Internal Server Error");
    }
    bp_free(root);
}

static void dict_set_uint(bp_node_t *d, const char *key, uint64_t v) {
    bp_dict_set(d, key, bp_new_uint(v));
}

/* ------------------------------------------------------------------------- */
/* handlers                                                                   */

static void handle_info(airplay_server_t *s, conn_t *c, const rtsp_request_t *req, rtsp_reply_t *reply) {
    (void) c;
    const char *qualifier = NULL;
    bp_node_t *request = parse_plist_body(req);
    if (request) {
        const char *q = bp_get_string(bp_array_get(bp_dict_get(request, "qualifier"), 0));
        if (q && (strcmp(q, "txtAirPlay") == 0 || strcmp(q, "txtRAOP") == 0)) {
            qualifier = strcmp(q, "txtAirPlay") == 0 ? "txtAirPlay" : "txtRAOP";
        }
    } else if (!rtsp_header(req, "CSeq")) {
        /* discovery without mDNS: GET /info?txtAirPlay&txtRAOP */
        if (strstr(req->url, "txtAirPlay")) {
            qualifier = "txtAirPlay";
        } else if (strstr(req->url, "txtRAOP")) {
            qualifier = "txtRAOP";
        }
    }

    bp_node_t *root = bp_new_dict();
    if (qualifier) {
        txt_entry_t entries[24];
        uint8_t txt[1024];
        int n = strcmp(qualifier, "txtAirPlay") == 0 ? airplay_txt_airplay(s, entries, 24)
                                                     : airplay_txt_raop(s, entries, 24);
        size_t len = airplay_txt_encode(entries, n, txt, sizeof(txt));
        bp_dict_set(root, qualifier, bp_new_data(txt, len));
        bp_free(request);
        reply_plist(reply, root);
        return;
    }
    bp_free(request);

    uint8_t pk[ED25519_KEY_SIZE];
    memcpy(pk, s->identity.public_key, sizeof(pk));
    bp_dict_set(root, "deviceID", bp_new_string(s->device_id_str));
    bp_dict_set(root, "macAddress", bp_new_string(s->device_id_str));
    bp_dict_set(root, "pk", bp_new_data(pk, sizeof(pk)));
    dict_set_uint(root, "features", s->features);
    bp_dict_set(root, "name", bp_new_string(s->cfg.name));
    bp_dict_set(root, "pi", bp_new_string(s->cfg.public_id));
    dict_set_uint(root, "vv", 2);
    dict_set_uint(root, "statusFlags", 68);
    dict_set_uint(root, "keepAliveLowPower", 1);
    bp_dict_set(root, "sourceVersion", bp_new_string(s->cfg.srcvers));
    bp_dict_set(root, "keepAliveSendStatsAsBody", bp_new_bool(true));
    bp_dict_set(root, "model", bp_new_string(s->cfg.model));
    bp_dict_set(root, "initialVolume", bp_new_real(s->volume_db));

    bp_node_t *latencies = bp_new_array();
    bp_node_t *formats = bp_new_array();
    for (int type = 100; type <= 101; type++) {
        bp_node_t *l = bp_new_dict();
        dict_set_uint(l, "type", (uint64_t) type);
        bp_dict_set(l, "audioType", bp_new_string("default"));
        dict_set_uint(l, "inputLatencyMicros", 0);
        bp_dict_set(l, "outputLatencyMicros", bp_new_bool(false));
        bp_array_append(latencies, l);

        bp_node_t *f = bp_new_dict();
        dict_set_uint(f, "type", (uint64_t) type);
        dict_set_uint(f, "audioInputFormats", 0x3fffffc);
        dict_set_uint(f, "audioOutputFormats", 0x3fffffc);
        bp_array_append(formats, f);
    }
    bp_dict_set(root, "audioLatencies", latencies);
    bp_dict_set(root, "audioFormats", formats);

    char uuid[40];
    snprintf(uuid, sizeof(uuid), "e0ff8a27-6738-3d56-8a16-%02x%02x%02x%02x%02x%02x",
             s->cfg.device_id[0], s->cfg.device_id[1], s->cfg.device_id[2],
             s->cfg.device_id[3], s->cfg.device_id[4], s->cfg.device_id[5]);
    bp_node_t *displays = bp_new_array();
    bp_node_t *d = bp_new_dict();
    bp_dict_set(d, "uuid", bp_new_string(uuid));
    dict_set_uint(d, "widthPhysical", 0);
    dict_set_uint(d, "heightPhysical", 0);
    dict_set_uint(d, "width", (uint64_t) s->cfg.display_width);
    dict_set_uint(d, "height", (uint64_t) s->cfg.display_height);
    dict_set_uint(d, "widthPixels", (uint64_t) s->cfg.display_width);
    dict_set_uint(d, "heightPixels", (uint64_t) s->cfg.display_height);
    bp_dict_set(d, "rotation", bp_new_bool(false));
    bp_dict_set(d, "refreshRate", bp_new_real(1.0 / (double) (s->cfg.display_fps > 0 ? s->cfg.display_fps : 60)));
    dict_set_uint(d, "maxFPS", (uint64_t) s->cfg.display_fps);
    bp_dict_set(d, "overscanned", bp_new_bool(false));
    dict_set_uint(d, "features", 14);
    bp_array_append(displays, d);
    bp_dict_set(root, "displays", displays);

    reply_plist(reply, root);
}

static void handle_pair_setup(airplay_server_t *s, conn_t *c, const rtsp_request_t *req, rtsp_reply_t *reply) {
    if (req->body_len != ED25519_KEY_SIZE) {
        rtsp_reply_status(reply, 400, "Bad Request");
        return;
    }
    if (c->pair.state == PAIR_STATE_INITIAL) {
        c->pair.state = PAIR_STATE_SETUP;
    }
    rtsp_reply_body_copy(reply, CT_OCTET, s->identity.public_key, ED25519_KEY_SIZE);
    LOG_D(PAIRING, "pair-setup");
}

static void handle_pair_verify(airplay_server_t *s, conn_t *c, const rtsp_request_t *req, rtsp_reply_t *reply) {
    const uint8_t *d = req->body;
    if (!d || req->body_len < 4) {
        rtsp_reply_status(reply, 400, "Bad Request");
        return;
    }
    if (d[0] == 1) {
        if (req->body_len != 4 + X25519_KEY_SIZE + ED25519_KEY_SIZE) {
            rtsp_reply_status(reply, 400, "Bad Request");
            return;
        }
        const uint8_t *client_ecdh = d + 4;
        const uint8_t *client_ed = d + 4 + X25519_KEY_SIZE;
        if (s->cfg.require_pin) {
            char b64[48];
            key_to_b64(client_ed, b64);
            bool trusted = (c->pair.pin_paired &&
                            crypto_memcmp(c->pair.pin_client_public, client_ed, ED25519_KEY_SIZE) == 0) ||
                           is_paired(s, b64);
            if (!trusted) {
                /* An empty answer makes the sender fall back to PIN pairing. */
                LOG_I(PAIRING, "sender is not paired yet, PIN required");
                return;
            }
            c->pair_trusted = true;
        }
        uint8_t out[PAIR_VERIFY_REPLY_LEN];
        if (pairing_verify_start(&c->pair, &s->identity, client_ecdh, client_ed, out) != 0) {
            LOG_W(PAIRING, "pair-verify could not be started");
            rtsp_reply_status(reply, 470, "Connection Authorization Required");
            reply->close_connection = true;
            return;
        }
        rtsp_reply_body_copy(reply, CT_OCTET, out, sizeof(out));
    } else if (d[0] == 0) {
        if (req->body_len != 4 + ED25519_SIG_SIZE) {
            rtsp_reply_status(reply, 400, "Bad Request");
            return;
        }
        if (pairing_verify_finish(&c->pair, d + 4) != 0) {
            LOG_W(PAIRING, "pair-verify signature rejected");
            rtsp_reply_status(reply, 470, "Connection Authorization Required");
            reply->close_connection = true;
            return;
        }
        LOG_I(PAIRING, "pair-verify completed");
        reply->content_type = CT_OCTET;
    } else {
        rtsp_reply_status(reply, 400, "Bad Request");
    }
}

static void handle_fp_setup(airplay_server_t *s, conn_t *c, const rtsp_request_t *req, rtsp_reply_t *reply) {
    (void) s;
    if (req->body_len == FAIRPLAY_SETUP_REQUEST_LEN) {
        uint8_t out[FAIRPLAY_SETUP_REPLY_LEN];
        if (fairplay_setup(&c->fp, req->body, req->body_len, out) != 0) {
            LOG_W(AIRPLAY, "unsupported FairPlay setup request");
            rtsp_reply_status(reply, 501, "Not Implemented");
            return;
        }
        rtsp_reply_body_copy(reply, CT_OCTET, out, sizeof(out));
    } else if (req->body_len == FAIRPLAY_HANDSHAKE_REQUEST_LEN) {
        uint8_t out[FAIRPLAY_HANDSHAKE_REPLY_LEN];
        if (fairplay_handshake(&c->fp, req->body, req->body_len, out) != 0) {
            LOG_W(AIRPLAY, "unsupported FairPlay handshake");
            rtsp_reply_status(reply, 501, "Not Implemented");
            return;
        }
        rtsp_reply_body_copy(reply, CT_OCTET, out, sizeof(out));
    } else {
        rtsp_reply_status(reply, 400, "Bad Request");
    }
}

static void hide_pin(airplay_server_t *s) {
    if (s->pin_active) {
        s->pin_active = false;
        secure_zero(s->pin, sizeof(s->pin));
        if (s->ev.pin_display) {
            s->ev.pin_display(s->ev.ctx, NULL);
        }
    }
}

static void handle_pair_pin_start(airplay_server_t *s, conn_t *c, const rtsp_request_t *req, rtsp_reply_t *reply) {
    (void) req;
    if (!s->cfg.require_pin) {
        rtsp_reply_status(reply, 404, "Not Found");
        return;
    }
    uint16_t r = 0;
    crypto_random(&r, sizeof(r));
    snprintf(s->pin, sizeof(s->pin), "%04u", (unsigned) (r % 10000));
    s->pin_active = true;
    s->pin_since_ns = time_mono_ns();
    s->pin_owner = c;
    LOG_I(PAIRING, "showing pairing PIN");
    if (s->ev.pin_display) {
        s->ev.pin_display(s->ev.ctx, s->pin);
    }
}

static void pin_failed(rtsp_reply_t *reply) {
    rtsp_reply_status(reply, 470, "Client Authentication Failure");
}

static void handle_pair_setup_pin(airplay_server_t *s, conn_t *c, const rtsp_request_t *req, rtsp_reply_t *reply) {
    if (!s->cfg.require_pin || !s->pin_active) {
        pin_failed(reply);
        return;
    }
    bp_node_t *root = parse_plist_body(req);
    if (!root) {
        pin_failed(reply);
        return;
    }
    const char *method = bp_get_string(bp_dict_get(root, "method"));
    const char *user = bp_get_string(bp_dict_get(root, "user"));
    size_t pk_len = 0;
    size_t proof_len = 0;
    size_t epk_len = 0;
    size_t tag_len = 0;
    const uint8_t *pk = bp_get_data(bp_dict_get(root, "pk"), &pk_len);
    const uint8_t *proof = bp_get_data(bp_dict_get(root, "proof"), &proof_len);
    const uint8_t *epk = bp_get_data(bp_dict_get(root, "epk"), &epk_len);
    const uint8_t *tag = bp_get_data(bp_dict_get(root, "authTag"), &tag_len);

    if (method && user) {
        /* step 1: start SRP */
        uint8_t salt[SRP_SALT_LEN];
        uint8_t B[SRP_MODULUS_LEN];
        size_t B_len = 0;
        if (strcmp(method, "pin") != 0 || strlen(user) > 32 ||
            pairing_pin_start(&c->pair, user, s->pin, salt, B, &B_len) != 0) {
            pin_failed(reply);
        } else {
            bp_node_t *out = bp_new_dict();
            bp_dict_set(out, "pk", bp_new_data(B, B_len));
            bp_dict_set(out, "salt", bp_new_data(salt, sizeof(salt)));
            reply_plist(reply, out);
        }
    } else if (pk && proof) {
        /* step 2: verify the client proof */
        uint8_t M2[SRP_PROOF_LEN];
        if (pairing_pin_verify(&c->pair, pk, pk_len, proof, proof_len, M2) != 0) {
            LOG_W(PAIRING, "wrong PIN entered");
            pin_failed(reply);
        } else {
            bp_node_t *out = bp_new_dict();
            bp_dict_set(out, "proof", bp_new_data(M2, sizeof(M2)));
            reply_plist(reply, out);
        }
    } else if (epk && tag && epk_len == ED25519_KEY_SIZE && tag_len == 16) {
        /* step 3: exchange long-term keys */
        uint8_t out_epk[ED25519_KEY_SIZE];
        uint8_t out_tag[16];
        if (pairing_pin_exchange(&c->pair, &s->identity, epk, tag, out_epk, out_tag) != 0) {
            LOG_W(PAIRING, "PIN pairing key exchange failed");
            pin_failed(reply);
        } else {
            LOG_I(PAIRING, "PIN pairing completed");
            hide_pin(s);
            bp_node_t *out = bp_new_dict();
            bp_dict_set(out, "epk", bp_new_data(out_epk, sizeof(out_epk)));
            bp_dict_set(out, "authTag", bp_new_data(out_tag, sizeof(out_tag)));
            reply_plist(reply, out);
        }
    } else {
        pin_failed(reply);
    }
    bp_free(root);
}

static bool old_protocol_client(const rtsp_request_t *req) {
    const char *ua = rtsp_header(req, "User-Agent");
    return ua && strstr(ua, "AirMyPC") != NULL;
}

static bool setup_session(airplay_server_t *s, conn_t *c, const rtsp_request_t *req, bp_node_t *root,
                          bp_node_t *out) {
    size_t ekey_len = 0;
    size_t eiv_len = 0;
    const uint8_t *ekey = bp_get_data(bp_dict_get(root, "ekey"), &ekey_len);
    const uint8_t *eiv = bp_get_data(bp_dict_get(root, "eiv"), &eiv_len);
    if (!ekey || !eiv || ekey_len != FAIRPLAY_EKEY_LEN || eiv_len < 16) {
        LOG_W(AIRPLAY, "SETUP without a valid stream key");
        return false;
    }
    if (s->cfg.require_pin && (!c->pair_trusted || c->pair.state != PAIR_STATE_VERIFIED)) {
        LOG_W(PAIRING, "refusing SETUP from a sender that is not paired");
        return false;
    }

    uint8_t key[16];
    if (fairplay_decrypt(&c->fp, ekey, ekey_len, key) != 0) {
        LOG_W(AIRPLAY, "SETUP before the FairPlay handshake");
        return false;
    }
    uint8_t shared[X25519_KEY_SIZE];
    if (!old_protocol_client(req) && pairing_shared_secret(&c->pair, shared)) {
        uint8_t hash[SHA512_SIZE];
        sha512_2(key, sizeof(key), shared, sizeof(shared), hash);
        memcpy(key, hash, 16);
        secure_zero(hash, sizeof(hash));
        secure_zero(shared, sizeof(shared));
    }

    /* One sender at a time: a new sender replaces the current one, unless the owner chose to keep the one that plays. */
    if (s->active && s->active->owner != c && atomic_load(&s->takeover) == AIRPLAY_TAKEOVER_KEEP) {
        const char *newcomer = bp_get_string(bp_dict_get(root, "name"));
        LOG_I(SESSION, "a second sender is turned away: the current session is kept");
        if (s->ev.session_blocked) {
            s->ev.session_blocked(s->ev.ctx, newcomer && newcomer[0] ? newcomer : "iPhone");
        }
        secure_zero(key, sizeof(key));
        return false;
    }
    if (s->active && s->active->owner != c) {
        conn_t *old = s->active->owner;
        LOG_I(SESSION, "new sender replaces the current session");
        session_destroy(s, s->active);
        if (old) {
            old->closing = true;
        }
    }
    if (c->session) {
        session_destroy(s, c->session);
    }

    session_t *session = (session_t *) calloc(1, sizeof(session_t));
    if (!session) {
        return false;
    }
    session->server = s;
    session->owner = c;
    session->id = ++s->next_session_id;
    memcpy(session->key, key, sizeof(key));
    memcpy(session->iv, eiv, 16);
    secure_zero(key, sizeof(key));
    const char *name = bp_get_string(bp_dict_get(root, "name"));
    const char *model = bp_get_string(bp_dict_get(root, "model"));
    if (!name || !name[0]) {
        name = "iPhone";
    }
    sanitize_utf8(session->client_name, sizeof(session->client_name), name, strlen(name));
    sanitize_utf8(session->client_model, sizeof(session->client_model), model ? model : "", model ? strlen(model) : 0);

    uint64_t timing_port = 0;
    bp_get_uint(bp_dict_get(root, "timingPort"), &timing_port);
    uint16_t local_timing = 0;
    session->ntp = ntp_client_start(&c->peer, timing_port <= 0xffff ? (uint16_t) timing_port : 0, &local_timing);

    c->session = session;
    s->active = session;
    atomic_store(&s->session_active, true);
    s->last_media_ns = time_mono_ns();
    stat_add(&g_stats.sessions_started, 1);
    stats_reset_session();

    if (s->cfg.require_pin && c->pair.pin_paired) {
        /* remember the sender so the PIN is only needed once */
        char b64[48];
        key_to_b64(c->pair.pin_client_public, b64);
        add_paired(s, b64);
        if (s->ev.client_paired) {
            s->ev.client_paired(s->ev.ctx, b64, session->client_name);
        }
    }

    LOG_I(SESSION, "session %u started (%s)", session->id, session->client_model[0] ? session->client_model : "unknown model");
    if (s->ev.session_started) {
        s->ev.session_started(s->ev.ctx, session->client_name, session->client_model);
    }

    dict_set_uint(out, "timingPort", local_timing);
    dict_set_uint(out, "eventPort", 0);
    return true;
}

static bp_node_t *setup_mirror_stream(airplay_server_t *s, session_t *session, bp_node_t *stream) {
    uint64_t connection_id = 0;
    if (!bp_get_uint(bp_dict_get(stream, "streamConnectionID"), &connection_id)) {
        int64_t signed_id = 0;
        if (bp_get_int(bp_dict_get(stream, "streamConnectionID"), &signed_id)) {
            connection_id = (uint64_t) signed_id;
        }
    }
    session_stop_video(session);
    mirror_params_t p;
    memset(&p, 0, sizeof(p));
    p.ops = s->media;
    p.ops_ctx = s->media_ctx;
    memcpy(p.session_key, session->key, 16);
    p.stream_connection_id = connection_id;
    p.peer = session->owner->peer;
    p.on_closed = on_stream_lost;
    p.closed_ctx = session;
    if (s->media->video_start) {
        s->media->video_start(s->media_ctx);
    }
    uint16_t port = 0;
    session->mirror = mirror_start(&p, &port);
    secure_zero(p.session_key, sizeof(p.session_key));
    if (!session->mirror) {
        if (s->media->video_stop) {
            s->media->video_stop(s->media_ctx);
        }
        return NULL;
    }
    LOG_I(VIDEO, "mirroring stream set up");
    bp_node_t *out = bp_new_dict();
    dict_set_uint(out, "dataPort", port);
    dict_set_uint(out, "type", 110);
    return out;
}

static bp_node_t *setup_audio_stream(airplay_server_t *s, session_t *session, bp_node_t *stream, const uint8_t *aead_key) {
    uint64_t ct = 0;
    uint64_t spf = 0;
    uint64_t control_port = 0;
    bool using_screen = false;
    bool is_media = false;
    bp_get_uint(bp_dict_get(stream, "ct"), &ct);
    bp_get_uint(bp_dict_get(stream, "spf"), &spf);
    bp_get_uint(bp_dict_get(stream, "controlPort"), &control_port);
    bp_get_bool(bp_dict_get(stream, "usingScreen"), &using_screen);
    bp_get_bool(bp_dict_get(stream, "isMedia"), &is_media);
    if (aead_key && ct == 0) {
        /* AirPlay 2 names the format with a code (bit 18 is ALAC 44100/16/2, the one realtime stream) */
        uint64_t format = 0;
        bp_get_uint(bp_dict_get(stream, "audioFormat"), &format);
        if (format & 0x40000) {
            ct = AUDIO_CT_ALAC;
        }
    }

    if (ct != AUDIO_CT_ALAC && ct != AUDIO_CT_AAC_LC && ct != AUDIO_CT_AAC_ELD) {
        LOG_W(AUDIO, "unsupported audio compression type %llu", (unsigned long long) ct);
        return NULL;
    }
    session_stop_audio(session);

    audio_params_t p;
    memset(&p, 0, sizeof(p));
    p.ops = s->media;
    p.ops_ctx = s->media_ctx;
    memcpy(p.key, session->key, 16);
    memcpy(p.iv, session->iv, 16);
    if (aead_key) {
        p.aead = true;
        memcpy(p.aead_key, aead_key, 32);
        control_port = 0;
    }
    p.peer = session->owner->peer;
    p.peer_control_port = control_port <= 0xffff ? (uint16_t) control_port : 0;
    p.format.ct = (int) ct;
    p.format.samples_per_frame = (spf > 0 && spf <= 4096) ? (int) spf
                                 : (ct == AUDIO_CT_ALAC ? 352 : (ct == AUDIO_CT_AAC_ELD ? 480 : 1024));
    p.format.sample_rate = 44100;
    p.format.channels = 2;
    p.format.using_screen = using_screen;
    p.format.is_media = is_media;

    if (s->media->audio_start && !s->media->audio_start(s->media_ctx, &p.format)) {
        LOG_W(AUDIO, "audio output could not be started");
    }
    if (s->media->audio_volume) {
        s->media->audio_volume(s->media_ctx, s->volume_db);
    }
    uint16_t dport = 0;
    uint16_t cport = 0;
    session->audio = audio_start(&p, &dport, &cport);
    secure_zero(p.key, sizeof(p.key));
    secure_zero(p.aead_key, sizeof(p.aead_key));
    if (!session->audio) {
        if (s->media->audio_stop) {
            s->media->audio_stop(s->media_ctx);
        }
        return NULL;
    }
    atomic_store_explicit(&g_stats.audio_ct, (int32_t) ct, memory_order_relaxed);
    LOG_I(AUDIO, "audio stream set up (ct=%llu, spf=%d)", (unsigned long long) ct, p.format.samples_per_frame);
    bp_node_t *out = bp_new_dict();
    dict_set_uint(out, "dataPort", dport);
    dict_set_uint(out, "controlPort", cport);
    dict_set_uint(out, "type", 96);
    return out;
}

static void handle_setup(airplay_server_t *s, conn_t *c, const rtsp_request_t *req, rtsp_reply_t *reply) {
    bp_node_t *root = parse_plist_body(req);
    if (!root || bp_type(root) != BP_DICT) {
        bp_free(root);
        rtsp_reply_status(reply, 400, "Bad Request");
        return;
    }
    bp_node_t *out = bp_new_dict();
    if (bp_dict_get(root, "ekey")) {
        if (!setup_session(s, c, req, root, out)) {
            bp_free(out);
            bp_free(root);
            rtsp_reply_status(reply, 403, "Forbidden");
            reply->close_connection = true;
            return;
        }
    }

    bp_node_t *streams = bp_dict_get(root, "streams");
    if (streams) {
        session_t *session = c->session;
        if (!session || bp_type(streams) != BP_ARRAY) {
            bp_free(out);
            bp_free(root);
            rtsp_reply_status(reply, 455, "Method Not Valid In This State");
            return;
        }
        bp_node_t *result = bp_new_array();
        for (size_t i = 0; i < bp_count(streams) && i < 4; i++) {
            bp_node_t *stream = bp_array_get(streams, i);
            uint64_t type = 0;
            bp_get_uint(bp_dict_get(stream, "type"), &type);
            bp_node_t *desc = NULL;
            if (type == 110) {
                desc = setup_mirror_stream(s, session, stream);
            } else if (type == 96) {
                desc = setup_audio_stream(s, session, stream, NULL);
            } else {
                LOG_W(AIRPLAY, "sender requested unsupported stream type %llu", (unsigned long long) type);
                reply->close_connection = true;
            }
            if (desc) {
                bp_array_append(result, desc);
            }
        }
        bp_dict_set(out, "streams", result);
    }
    bp_free(root);
    reply_plist(reply, out);
}

static void handle_record(airplay_server_t *s, conn_t *c, const rtsp_request_t *req, rtsp_reply_t *reply) {
    (void) s;
    (void) c;
    (void) req;
    /* 0.25 s in samples at 44.1 kHz, as announced by UxPlay */
    rtsp_reply_header(reply, "Audio-Latency", "11025");
    rtsp_reply_header(reply, "Audio-Jack-Status", "connected; type=analog");
}

static void handle_get_parameter(airplay_server_t *s, conn_t *c, const rtsp_request_t *req, rtsp_reply_t *reply) {
    (void) c;
    const char *ct = rtsp_header(req, "Content-Type");
    if (!ct) {
        rtsp_reply_status(reply, 451, "Parameter Not Understood");
        return;
    }
    if (str_ieq(ct, CT_PARAMS) && req->body_len >= 6 && memcmp(req->body, "volume", 6) == 0) {
        char text[32];
        int n = snprintf(text, sizeof(text), "volume: %.6f\r\n", (double) s->volume_db);
        rtsp_reply_body_copy(reply, CT_PARAMS, text, (size_t) n);
    }
}

/* DMAP items are a 4 byte ASCII tag, a big-endian 32 bit length and the payload. */
typedef struct {
    char title[256];
    char artist[256];
    char album[256];
} dmap_track_t;

static uint32_t dmap_be32(const uint8_t *p) {
    return ((uint32_t) p[0] << 24) | ((uint32_t) p[1] << 16) | ((uint32_t) p[2] << 8) | (uint32_t) p[3];
}

static void dmap_copy_text(char *dst, size_t cap, const uint8_t *src, size_t len) {
    size_t n = MIN(len, cap - 1);
    memcpy(dst, src, n);
    if (len > n) {
        /* Do not leave half of a multi-byte UTF-8 sequence at the cut. */
        while (n > 0 && ((uint8_t) dst[n - 1] & 0xC0) == 0x80) {
            n--;
        }
        if (n > 0 && (uint8_t) dst[n - 1] >= 0xC0) {
            n--;
        }
    }
    dst[n] = '\0';
}

static void dmap_scan(const uint8_t *data, size_t len, int depth, dmap_track_t *out) {
    size_t pos = 0;
    while (len - pos >= 8) {
        const uint8_t *tag = data + pos;
        size_t item = dmap_be32(data + pos + 4);
        pos += 8;
        if (item > len - pos) {
            break;
        }
        if (memcmp(tag, "minm", 4) == 0) {
            dmap_copy_text(out->title, sizeof(out->title), data + pos, item);
        } else if (memcmp(tag, "asar", 4) == 0) {
            dmap_copy_text(out->artist, sizeof(out->artist), data + pos, item);
        } else if (memcmp(tag, "asal", 4) == 0) {
            dmap_copy_text(out->album, sizeof(out->album), data + pos, item);
        } else if (depth < 4 && memcmp(tag, "mlit", 4) == 0) {
            dmap_scan(data + pos, item, depth + 1, out);
        }
        pos += item;
    }
}

static void handle_set_parameter(airplay_server_t *s, conn_t *c, const rtsp_request_t *req, rtsp_reply_t *reply) {
    const char *ct = rtsp_header(req, "Content-Type");
    if (!ct) {
        rtsp_reply_status(reply, 451, "Parameter Not Understood");
        return;
    }
    if (req->body && req->body_len > 0) {
        if (str_ieq(ct, "application/x-dmap-tagged")) {
            if (s->ev.track_info) {
                dmap_track_t track;
                memset(&track, 0, sizeof(track));
                dmap_scan(req->body, req->body_len, 0, &track);
                s->ev.track_info(s->ev.ctx, track.title, track.artist, track.album);
            }
            return;
        }
        if (strncmp(ct, "image/", 6) == 0) {
            if (s->ev.artwork) {
                s->ev.artwork(s->ev.ctx, req->body, req->body_len);
            }
            return;
        }
    }
    if (!str_ieq(ct, CT_PARAMS) || !req->body) {
        return;
    }
    char text[128];
    size_t n = MIN(req->body_len, sizeof(text) - 1);
    memcpy(text, req->body, n);
    text[n] = '\0';
    if (strncmp(text, "volume: ", 8) == 0) {
        char *end = NULL;
        float db = strtof(text + 8, &end);
        if (end != text + 8 && db == db) {
            if (db < -144.0f) {
                db = -144.0f;
            } else if (db > 0.0f) {
                db = 0.0f;
            }
            s->volume_db = db;
            if (c->session && c->session->audio && s->media->audio_volume) {
                s->media->audio_volume(s->media_ctx, db);
            }
            LOG_D(AUDIO, "volume %.1f dB", (double) db);
        }
    } else if (strncmp(text, "progress: ", 10) == 0 && s->ev.progress) {
        unsigned long start = 0, current = 0, end = 0;
        if (sscanf(text + 10, "%lu/%lu/%lu", &start, &current, &end) == 3) {
            s->ev.progress(s->ev.ctx, (uint32_t) start, (uint32_t) current, (uint32_t) end);
        }
    }
}

static void handle_flush(airplay_server_t *s, conn_t *c, const rtsp_request_t *req, rtsp_reply_t *reply) {
    (void) s;
    (void) reply;
    int next_seq = -1;
    const char *info = rtsp_header(req, "RTP-Info");
    if (info && strncmp(info, "seq=", 4) == 0) {
        const char *digits = info + 4;
        size_t len = strspn(digits, "0123456789");
        long long v = parse_uint(digits, len, 0xffff);
        next_seq = (int) v;
    }
    if (c->session && c->session->audio) {
        audio_request_flush(c->session->audio, next_seq);
    }
}

static void handle_teardown(airplay_server_t *s, conn_t *c, const rtsp_request_t *req, rtsp_reply_t *reply) {
    bool audio = false;
    bool video = false;
    bp_node_t *root = parse_plist_body(req);
    bp_node_t *streams = bp_dict_get(root, "streams");
    for (size_t i = 0; i < bp_count(streams); i++) {
        uint64_t type = 0;
        bp_get_uint(bp_dict_get(bp_array_get(streams, i), "type"), &type);
        audio |= type == 96;
        video |= type == 110;
    }
    bp_free(root);
    session_t *session = c->session;
    if (!session) {
        return;
    }
    if (audio || video) {
        if (video) {
            LOG_I(VIDEO, "mirroring stopped by the sender");
            session_stop_video(session);
        }
        if (audio) {
            session_stop_audio(session);
        }
        return;
    }
    rtsp_reply_header(reply, "Connection", "close");
    session_destroy(s, session);
}

/* Appends the shape of a property list to out: the keys, the kind of each value, numbers, and the text of the few values that say
 * which protocol is used. Nothing else is shown, so a name or an address never reaches the log. */
static void trace_plist(const bp_node_t *node, char *out, size_t cap, int depth) {
    size_t n = strlen(out);
    if (!node || n + 8 >= cap || depth > 3) {
        return;
    }
    bp_type_t t = bp_type(node);
    if (t == BP_DICT) {
        n += (size_t) snprintf(out + n, cap - n, "{");
        for (size_t i = 0, count = bp_count(node); i < count && strlen(out) + 24 < cap; i++) {
            const char *key = bp_dict_key_at(node, i);
            bp_node_t *v = bp_dict_value_at(node, i);
            n = strlen(out);
            n += (size_t) snprintf(out + n, cap - n, "%s%s=", i ? "," : "", key ? key : "?");
            const char *text = bp_get_string(v);
            uint64_t u = 0;
            bool b = false;
            if (text && key && (strcmp(key, "timingProtocol") == 0 || strcmp(key, "et") == 0)) {
                snprintf(out + n, cap - n, "\"%.20s\"", text);
            } else if (bp_get_uint(v, &u)) {
                snprintf(out + n, cap - n, "%llu", (unsigned long long) u);
            } else if (bp_get_bool(v, &b)) {
                snprintf(out + n, cap - n, "%s", b ? "true" : "false");
            } else if (bp_type(v) == BP_DICT || bp_type(v) == BP_ARRAY) {
                trace_plist(v, out, cap, depth + 1);
            } else {
                snprintf(out + n, cap - n, "~");
            }
        }
        n = strlen(out);
        snprintf(out + n, cap - n, "}");
    } else if (t == BP_ARRAY) {
        snprintf(out + n, cap - n, "[%zu x ", bp_count(node));
        if (bp_count(node) > 0) {
            trace_plist(bp_array_get(node, 0), out, cap, depth + 1);
        }
        n = strlen(out);
        snprintf(out + n, cap - n, "]");
    }
}

/* ------------------------------------------------------------------------- */
/* AirPlay 2 (cfg.airplay2)                                                    */

/* The event connection: the sender opens it after SETUP and, sealed with the events keys, talks on it. Nothing it says is needed
 * here yet, so it is read and dropped; the connection is kept open for as long as the control connection lives. */
struct ap2_events {
    int listen_fd;
    int fd;
    uint8_t shared[AP2_SHARED_LEN];
    _Atomic bool stop;
    pthread_t thread;
};

static void *ap2_events_thread(void *arg) {
    struct ap2_events *e = (struct ap2_events *) arg;
    while (!atomic_load(&e->stop) && e->fd < 0) {
        struct pollfd p = { .fd = e->listen_fd, .events = POLLIN };
        if (poll(&p, 1, 100) > 0 && (p.revents & POLLIN)) {
            e->fd = accept(e->listen_fd, NULL, NULL);
        }
    }
    if (e->fd >= 0) {
        LOG_I(AIRPLAY, "AirPlay 2 event connection opened");
        ap2_cipher_t cipher;
        ap2_cipher_init(&cipher, e->shared, AP2_SHARED_LEN, AP2_CHANNEL_EVENTS, true);
        uint8_t in[4096], out[4096];
        size_t have = 0;
        while (!atomic_load(&e->stop)) {
            struct pollfd p = { .fd = e->fd, .events = POLLIN };
            if (poll(&p, 1, 100) <= 0) {
                continue;
            }
            ssize_t n = recv(e->fd, in + have, sizeof(in) - have, 0);
            if (n <= 0) {
                break;
            }
            have += (size_t) n;
            size_t made = 0, used = 0;
            if (ap2_open(&cipher, in, have, out, sizeof(out), &made, &used) != 0) {
                LOG_W(AIRPLAY, "AirPlay 2 event data did not authenticate");
                break;
            }
            if (made) {
                LOG_D(AIRPLAY, "AirPlay 2 event data: %zu bytes", made);
            }
            memmove(in, in + used, have - used);
            have -= used;
            if (have == sizeof(in)) {
                break;
            }
        }
        secure_zero(&cipher, sizeof(cipher));
    }
    return NULL;
}

static void ap2_events_stop(struct ap2_events *e) {
    if (!e) {
        return;
    }
    atomic_store(&e->stop, true);
    pthread_join(e->thread, NULL);
    net_close(&e->listen_fd);
    net_close(&e->fd);
    secure_zero(e->shared, sizeof(e->shared));
    free(e);
}

/* Opens the port for the event connection and returns it, or 0. */
static uint16_t ap2_events_start(conn_t *c) {
    if (c->events) {
        ap2_events_stop(c->events);
        c->events = NULL;
    }
    struct ap2_events *e = (struct ap2_events *) calloc(1, sizeof(*e));
    if (!e) {
        return 0;
    }
    e->fd = -1;
    uint16_t port = 0;
    e->listen_fd = net_listen_tcp(c->peer.ss_family == AF_INET6 ? AF_INET6 : AF_INET, &port, 2);
    if (e->listen_fd < 0) {
        free(e);
        return 0;
    }
    memcpy(e->shared, c->ap2_shared, sizeof(e->shared));
    if (pthread_create(&e->thread, NULL, ap2_events_thread, e) != 0) {
        net_close(&e->listen_fd);
        free(e);
        return 0;
    }
    c->events = e;
    return port;
}

/* The sealed bytes of the control connection are opened into the plain request buffer. */
static bool ap2_open_wire(conn_t *c) {
    if (c->wire_len == 0) {
        return true;
    }
    uint8_t *plain = (uint8_t *) malloc(c->wire_len);
    if (!plain) {
        return false;
    }
    size_t made = 0, used = 0;
    if (ap2_open(&c->ap2c, c->wire, c->wire_len, plain, c->wire_len, &made, &used) != 0) {
        LOG_W(AIRPLAY, "AirPlay 2: a sealed message did not authenticate, closing the connection");
        free(plain);
        return false;
    }
    if (made > 0) {
        if (c->rx_len + made > RX_LIMIT) {
            free(plain);
            return false;
        }
        if (c->rx_len + made > c->rx_cap) {
            size_t cap = MAX(c->rx_len + made, c->rx_cap ? c->rx_cap : 4096);
            uint8_t *grown = (uint8_t *) realloc(c->rx, cap);
            if (!grown) {
                free(plain);
                return false;
            }
            c->rx = grown;
            c->rx_cap = cap;
        }
        memcpy(c->rx + c->rx_len, plain, made);
        c->rx_len += made;
    }
    free(plain);
    memmove(c->wire, c->wire + used, c->wire_len - used);
    c->wire_len -= used;
    return true;
}

static int conn_send(conn_t *c, const uint8_t *data, size_t len) {
    if (!c->ap2c.on) {
        return net_send_all(c->fd, data, len, 3000);
    }
    size_t cap = len + (len / AP2_BLOCK_MAX + 1) * (2 + AP2_TAG_LEN);
    uint8_t *sealed = (uint8_t *) malloc(cap);
    if (!sealed) {
        return -1;
    }
    size_t n = ap2_seal(&c->ap2c, data, len, sealed, cap);
    int rc = n > 0 ? net_send_all(c->fd, sealed, n, 3000) : -1;
    free(sealed);
    return rc;
}

static void ap2_free_conn(conn_t *c) {
    ap2_pair_free(c->ap2_pair);
    c->ap2_pair = NULL;
    ap2_events_stop(c->events);
    c->events = NULL;
    free(c->wire);
    c->wire = NULL;
    c->wire_len = c->wire_cap = 0;
    secure_zero(&c->ap2c, sizeof(c->ap2c));
    secure_zero(c->ap2_shared, sizeof(c->ap2_shared));
}

static void ap2_local_ip(const conn_t *c, char *out, size_t cap) {
    struct sockaddr_storage ss;
    socklen_t len = sizeof(ss);
    out[0] = '\0';
    if (getsockname(c->fd, (struct sockaddr *) &ss, &len) == 0) {
        net_normalize_addr(&ss, NULL);
        net_addr_to_string(&ss, out, cap);
    }
}

static void ap2_handle_pair_setup(airplay_server_t *s, conn_t *c, const rtsp_request_t *req, rtsp_reply_t *reply) {
    (void) s;
    if (!c->ap2_pair) {
        c->ap2_pair = ap2_pair_new(AP2_PIN);
    }
    uint8_t *out = NULL;
    size_t out_len = 0;
    if (!c->ap2_pair || !req->body || ap2_pair_setup(c->ap2_pair, req->body, req->body_len, &out, &out_len) != 0) {
        LOG_W(PAIRING, "AirPlay 2 pair-setup: the request was not understood");
        rtsp_reply_status(reply, 400, "Bad Request");
        reply->close_connection = true;
        return;
    }
    rtsp_reply_body_copy(reply, CT_OCTET, out, out_len);
    free(out);
    if (ap2_pair_done(c->ap2_pair, c->ap2_shared)) {
        c->ap2_have_shared = true;
        c->ap2_pending = true; /* sealed from the next byte on, after this reply has gone out in the clear */
        LOG_I(PAIRING, "AirPlay 2 pairing done (transient)");
    }
}

/* /info the AirPlay 2 way: what this receiver is and can do, in one plist. */
static void ap2_handle_info(airplay_server_t *s, conn_t *c, rtsp_reply_t *reply) {
    (void) c;
    bp_node_t *root = bp_new_dict();
    char fex[24], psi[40];
    fex_string(s->features, fex, sizeof(fex));
    psi_string(s, psi, sizeof(psi));
    dict_set_uint(root, "vv", 2);
    bp_node_t *caps = bp_new_dict();
    bp_dict_set(caps, "supportsInterstitials", bp_new_bool(false));
    bp_dict_set(caps, "supportsFPSSecureStop", bp_new_bool(false));
    bp_dict_set(caps, "supportsUIForAudioOnlyContent", bp_new_bool(false));
    bp_dict_set(root, "playbackCapabilities", caps);
    bp_dict_set(root, "canRecordScreenStream", bp_new_bool(false));
    bp_dict_set(root, "keepAliveSendStatsAsBody", bp_new_bool(false));
    bp_dict_set(root, "protocolVersion", bp_new_string("1.1"));
    dict_set_uint(root, "volumeControlType", 3);
    bp_dict_set(root, "screenDemoMode", bp_new_bool(false));
    bp_dict_set(root, "psi", bp_new_string(psi));
    bp_dict_set(root, "featuresEx", bp_new_string(fex));
    dict_set_uint(root, "features", s->features);
    dict_set_uint(root, "statusFlags", 4);
    bp_dict_set(root, "deviceID", bp_new_string(s->device_id_str));
    bp_dict_set(root, "pi", bp_new_string(s->cfg.public_id));
    bp_dict_set(root, "name", bp_new_string(s->cfg.name));
    bp_dict_set(root, "model", bp_new_string(s->cfg.model));
    bp_dict_set(root, "pk", bp_new_data(s->identity.public_key, ED25519_KEY_SIZE));
    bp_dict_set(root, "initialVolume", bp_new_real(s->volume_db));
    bp_dict_set(root, "sourceVersion", bp_new_string(s->cfg.srcvers));
    bp_node_t *formats = bp_new_dict();
    dict_set_uint(formats, "audioStream", 0x1440800);
    dict_set_uint(formats, "bufferStream", 0x40000ull | 0x400000ull | 0x200000ull | 0x800000ull);
    bp_dict_set(root, "supportedFormats", formats);
    txt_entry_t entries[24];
    uint8_t txt[1024];
    int n = airplay_txt_airplay(s, entries, 24);
    size_t len = airplay_txt_encode(entries, n, txt, sizeof(txt));
    bp_dict_set(root, "txtAirPlay", bp_new_data(txt, len));
    reply_plist(reply, root);
}

/* The first SETUP of a sender (name, model, timing) makes the session; the later ones add the audio stream. */
static session_t *ap2_begin_session(airplay_server_t *s, conn_t *c, bp_node_t *root) {
    const char *name = bp_get_string(bp_dict_get(root, "name"));
    const char *model = bp_get_string(bp_dict_get(root, "model"));
    if (s->active && s->active->owner != c && atomic_load(&s->takeover) == AIRPLAY_TAKEOVER_KEEP) {
        LOG_I(SESSION, "a second sender is turned away: the current session is kept");
        if (s->ev.session_blocked) {
            s->ev.session_blocked(s->ev.ctx, name && name[0] ? name : "iPhone");
        }
        return NULL;
    }
    if (s->active && s->active->owner != c) {
        conn_t *old = s->active->owner;
        LOG_I(SESSION, "new sender replaces the current session");
        session_destroy(s, s->active);
        if (old) {
            old->closing = true;
        }
    }
    if (c->session) {
        return c->session; /* a repeated first SETUP: the session is there */
    }
    session_t *session = (session_t *) calloc(1, sizeof(session_t));
    if (!session) {
        return NULL;
    }
    session->server = s;
    session->owner = c;
    session->id = ++s->next_session_id;
    if (!name || !name[0]) {
        name = "iPhone";
    }
    sanitize_utf8(session->client_name, sizeof(session->client_name), name, strlen(name));
    sanitize_utf8(session->client_model, sizeof(session->client_model), model ? model : "", model ? strlen(model) : 0);
    c->session = session;
    s->active = session;
    atomic_store(&s->session_active, true);
    s->last_media_ns = time_mono_ns();
    stat_add(&g_stats.sessions_started, 1);
    stats_reset_session();
    LOG_I(SESSION, "session %u started (AirPlay 2, %s)", session->id, session->client_model[0] ? session->client_model : "unknown model");
    if (s->ev.session_started) {
        s->ev.session_started(s->ev.ctx, session->client_name, session->client_model);
    }
    return session;
}

static void ap2_handle_setup(airplay_server_t *s, conn_t *c, const rtsp_request_t *req, rtsp_reply_t *reply) {
    bp_node_t *root = parse_plist_body(req);
    if (!root || bp_type(root) != BP_DICT) {
        bp_free(root);
        rtsp_reply_status(reply, 400, "Bad Request");
        return;
    }
    bp_node_t *out = bp_new_dict();
    bp_node_t *streams = bp_dict_get(root, "streams");
    if (!streams) {
        const char *timing = bp_get_string(bp_dict_get(root, "timingProtocol"));
        bool remote_only = false;
        bp_get_bool(bp_dict_get(root, "isRemoteControlOnly"), &remote_only);
        LOG_I(AIRPLAY, "AirPlay 2 SETUP: timing %s%s", timing ? timing : "-", remote_only ? ", remote control only" : "");
        session_t *session = NULL;
        if (!remote_only) {
            session = ap2_begin_session(s, c, root);
            if (!session) {
                bp_free(out);
                bp_free(root);
                rtsp_reply_status(reply, 403, "Forbidden");
                reply->close_connection = true;
                return;
            }
        }
        uint16_t event_port = ap2_events_start(c);
        dict_set_uint(out, "eventPort", event_port);
        if (session && timing && strcmp(timing, "NTP") == 0) {
            uint64_t timing_port = 0;
            bp_get_uint(bp_dict_get(root, "timingPort"), &timing_port);
            uint16_t local_timing = 0;
            ntp_client_stop(session->ntp);
            session->ntp = ntp_client_start(&c->peer, timing_port <= 0xffff ? (uint16_t) timing_port : 0, &local_timing);
            dict_set_uint(out, "timingPort", local_timing);
        } else {
            /* PTP needs UDP ports 319 and 320, which an app cannot open: say so by naming only this address as a clock peer */
            char ip[64];
            ap2_local_ip(c, ip, sizeof(ip));
            bp_node_t *peer = bp_new_dict();
            bp_node_t *addresses = bp_new_array();
            bp_array_append(addresses, bp_new_string(ip));
            bp_dict_set(peer, "Addresses", addresses);
            bp_dict_set(peer, "ID", bp_new_string(ip));
            bp_dict_set(out, "timingPeerInfo", peer);
            dict_set_uint(out, "timingPort", 0);
        }
    } else {
        session_t *session = c->session;
        if (!session || bp_type(streams) != BP_ARRAY) {
            bp_free(out);
            bp_free(root);
            rtsp_reply_status(reply, 455, "Method Not Valid In This State");
            return;
        }
        bp_node_t *result = bp_new_array();
        for (size_t i = 0; i < bp_count(streams) && i < 4; i++) {
            bp_node_t *stream = bp_array_get(streams, i);
            uint64_t type = 0;
            bp_get_uint(bp_dict_get(stream, "type"), &type);
            size_t shk_len = 0;
            const uint8_t *shk = bp_get_data(bp_dict_get(stream, "shk"), &shk_len);
            LOG_I(AIRPLAY, "AirPlay 2 SETUP stream: type %llu, key %zu bytes", (unsigned long long) type, shk_len);
            bp_node_t *desc = NULL;
            if (type == 96 && shk && shk_len == 32) {
                desc = setup_audio_stream(s, session, stream, shk);
            } else {
                LOG_W(AIRPLAY, "AirPlay 2: stream type %llu is not offered here (realtime audio only)", (unsigned long long) type);
                reply->close_connection = true;
            }
            if (desc) {
                bp_array_append(result, desc);
            }
        }
        bp_dict_set(out, "streams", result);
    }
    bp_free(root);
    reply_plist(reply, out);
}

/* Heartbeat and commands of an AirPlay 2 sender; the answer to a heartbeat names the running streams. */
static void ap2_handle_post(airplay_server_t *s, conn_t *c, const rtsp_request_t *req, rtsp_reply_t *reply) {
    (void) s;
    (void) req;
    bp_node_t *root = bp_new_dict();
    if (strcmp(req->url, "/feedback") == 0 && c->session && c->session->audio) {
        bp_node_t *list = bp_new_array();
        bp_node_t *stream = bp_new_dict();
        dict_set_uint(stream, "type", 96);
        bp_dict_set(stream, "sr", bp_new_real(44100.0));
        bp_array_append(list, stream);
        bp_dict_set(root, "streams", list);
    }
    reply_plist(reply, root);
}

/* The AirPlay 2 requests this receiver answers itself. Returns true when the request was handled. */
static bool ap2_try_handle(airplay_server_t *s, conn_t *c, const rtsp_request_t *req, rtsp_reply_t *reply) {
    const char *m = req->method;
    const char *url = req->url;
    if (strcmp(m, "POST") == 0 && strcmp(url, "/pair-setup") == 0 && req->body_len != ED25519_KEY_SIZE) {
        ap2_handle_pair_setup(s, c, req, reply);
        return true;
    }
    if (strcmp(m, "POST") == 0 && strcmp(url, "/pair-verify") == 0 && req->body && req->body_len > 0 && req->body[0] == TLV_STATE) {
        /* a HomeKit style verify: this receiver stores no pairings, so the sender is told to set up again */
        uint8_t *out = NULL;
        size_t out_len = 0;
        if (ap2_pair_verify_refusal(req->body, req->body_len, &out, &out_len) == 0) {
            rtsp_reply_body_copy(reply, CT_OCTET, out, out_len);
            free(out);
        } else {
            rtsp_reply_status(reply, 400, "Bad Request");
        }
        return true;
    }
    if (strcmp(m, "GET") == 0 && (strcmp(url, "/info") == 0 || strncmp(url, "/info?", 6) == 0)) {
        ap2_handle_info(s, c, reply);
        return true;
    }
    if (strcmp(m, "OPTIONS") == 0) {
        rtsp_reply_header(reply, "Public",
                          "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, FLUSHBUFFERED, TEARDOWN, OPTIONS, POST, GET, PUT, SETPEERS, "
                          "SETPEERSX, SETRATEANCHORTIME, GET_PARAMETER, SET_PARAMETER");
        return true;
    }
    if (!c->ap2c.on) {
        return false;
    }
    if (strcmp(m, "SETUP") == 0) {
        ap2_handle_setup(s, c, req, reply);
        return true;
    }
    if (strcmp(m, "POST") == 0 && (strcmp(url, "/feedback") == 0 || strcmp(url, "/command") == 0 || strcmp(url, "/audioMode") == 0)) {
        ap2_handle_post(s, c, req, reply);
        return true;
    }
    if (strcmp(m, "SETPEERS") == 0 || strcmp(m, "SETPEERSX") == 0 || strcmp(m, "SETRATEANCHORTIME") == 0 || strcmp(m, "PAUSE") == 0) {
        return true; /* timing peers and the clock anchor belong to PTP, which is not kept: acknowledged and ignored */
    }
    if (strcmp(m, "FLUSHBUFFERED") == 0) {
        if (c->session && c->session->audio) {
            audio_request_flush(c->session->audio, -1);
        }
        return true;
    }
    return false;
}

/* What the sender asks of the receiver, for the first requests of each connection: method, address, a few harmless headers, the text
 * of a parameter request, the shape of a plist body. Shown at INFO so a session can be read from the log without a packet capture
 * (this is how the AirPlay 2 question is answered). Pairing and FairPlay bodies are never shown. */
static void trace_request(conn_t *c, const rtsp_request_t *req) {
    if (c->trace >= 80) {
        return;
    }
    c->trace++;
    const char *ct = rtsp_header(req, "Content-Type");
    const char *ua = rtsp_header(req, "User-Agent");
    const char *pv = rtsp_header(req, "X-Apple-ProtocolVersion");
    char names[200] = "";
    for (int i = 0; i < req->header_count && strlen(names) + 30 < sizeof(names); i++) {
        size_t n = strlen(names);
        snprintf(names + n, sizeof(names) - n, "%s%.24s", i ? "," : "", req->headers[i].name);
    }
    char shape[320] = "";
    bool secret = strstr(req->url, "pair") != NULL || strstr(req->url, "fp-") != NULL;
    if (!secret && ct && req->body && req->body_len > 0) {
        if (str_ieq(ct, CT_PARAMS) && req->body_len < 100) {
            size_t n = MIN(req->body_len, sizeof(shape) - 1);
            for (size_t i = 0; i < n; i++) {
                shape[i] = (req->body[i] >= 32 && req->body[i] < 127) ? (char) req->body[i] : '.';
            }
            shape[n] = '\0';
        } else if (req->body_len >= 8 && memcmp(req->body, "bplist00", 8) == 0) {
            bp_node_t *root = bp_parse(req->body, req->body_len);
            if (root) {
                trace_plist(root, shape, sizeof(shape), 0);
                bp_free(root);
            }
        }
    }
    LOG_I(AIRPLAY, "request %s %s %s body %zu type %s ua %s proto %s headers %s%s%s", req->method, req->url, req->protocol,
          req->body_len, ct ? ct : "-", ua ? ua : "-", pv ? pv : "-", names, shape[0] ? " body-shape " : "", shape);
}

static void handle_request(airplay_server_t *s, conn_t *c, const rtsp_request_t *req, rtsp_reply_t *reply) {
    const char *m = req->method;
    const char *url = req->url;
    bool rtsp = strcmp(req->protocol, "RTSP/1.0") == 0;

    const char *dacp_id = rtsp_header(req, "DACP-ID");
    const char *active_remote = rtsp_header(req, "Active-Remote");
    if (dacp_id && active_remote && s->ev.remote &&
        (strcmp(dacp_id, s->dacp_id) != 0 || strcmp(active_remote, s->active_remote) != 0) &&
        strlen(dacp_id) < sizeof(s->dacp_id) && strlen(active_remote) < sizeof(s->active_remote)) {
        snprintf(s->dacp_id, sizeof(s->dacp_id), "%s", dacp_id);
        snprintf(s->active_remote, sizeof(s->active_remote), "%s", active_remote);
        s->ev.remote(s->ev.ctx, s->dacp_id, s->active_remote);
    }

    if (!rtsp) {
        handle_http(s, c, req, reply);
        return;
    }
    c->rtsp = true;

    if (s->cfg.airplay2 > 0 && ap2_try_handle(s, c, req, reply)) {
        return;
    }

    if (strcmp(m, "GET") == 0 && (strcmp(url, "/info") == 0 || strncmp(url, "/info?", 6) == 0)) {
        handle_info(s, c, req, reply);
    } else if (strcmp(m, "POST") == 0) {
        if (strcmp(url, "/pair-setup") == 0) {
            handle_pair_setup(s, c, req, reply);
        } else if (strcmp(url, "/pair-verify") == 0) {
            handle_pair_verify(s, c, req, reply);
        } else if (strcmp(url, "/fp-setup") == 0) {
            handle_fp_setup(s, c, req, reply);
        } else if (strcmp(url, "/pair-pin-start") == 0) {
            handle_pair_pin_start(s, c, req, reply);
        } else if (strcmp(url, "/pair-setup-pin") == 0) {
            handle_pair_setup_pin(s, c, req, reply);
        } else if (strcmp(url, "/feedback") == 0 || strcmp(url, "/audioMode") == 0 ||
                   strcmp(url, "/command") == 0) {
            /* heartbeat and hints: nothing to do beyond noting the activity */
        } else {
            LOG_W(AIRPLAY, "unhandled POST %s", url);
        }
    } else if (strcmp(m, "OPTIONS") == 0) {
        rtsp_reply_header(reply, "Public",
                          "SETUP, RECORD, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER");
    } else if (strcmp(m, "SETUP") == 0) {
        handle_setup(s, c, req, reply);
    } else if (strcmp(m, "RECORD") == 0) {
        handle_record(s, c, req, reply);
    } else if (strcmp(m, "GET_PARAMETER") == 0) {
        handle_get_parameter(s, c, req, reply);
    } else if (strcmp(m, "SET_PARAMETER") == 0) {
        handle_set_parameter(s, c, req, reply);
    } else if (strcmp(m, "FLUSH") == 0) {
        handle_flush(s, c, req, reply);
    } else if (strcmp(m, "TEARDOWN") == 0) {
        handle_teardown(s, c, req, reply);
    } else {
        LOG_W(AIRPLAY, "unsupported request %s %s", m, url);
        rtsp_reply_status(reply, 501, "Not Implemented");
    }
}

/* Parses and answers every complete request in the receive buffer. Returns false
 * if the connection must be closed. */
static bool process_rx(airplay_server_t *s, conn_t *c) {
    size_t offset = 0;
    bool keep = true;
    while (keep && offset < c->rx_len) {
        rtsp_request_t *req = (rtsp_request_t *) calloc(1, sizeof(rtsp_request_t));
        if (!req) {
            return false;
        }
        size_t consumed = 0;
        rtsp_parse_result_t r = rtsp_parse_request(c->rx + offset, c->rx_len - offset, req, &consumed);
        if (r == RTSP_PARSE_INCOMPLETE) {
            free(req);
            break;
        }
        if (r != RTSP_PARSE_OK) {
            LOG_W(AIRPLAY, "malformed request, closing connection");
            rtsp_request_clear(req);
            free(req);
            return false;
        }
        offset += consumed;

        rtsp_reply_t reply;
        rtsp_reply_init(&reply);
        const char *cseq = rtsp_header(req, "CSeq");
        long long cseq_value = cseq ? parse_uint(cseq, strlen(cseq), 0x7fffffff) : -1;
        if (cseq && cseq_value < 0) {
            rtsp_reply_status(&reply, 400, "Bad Request");
            reply.close_connection = true;
        } else {
            LOG_D(AIRPLAY, "%s %s", req->method, req->url);
            trace_request(c, req);
            handle_request(s, c, req, &reply);
        }
        if (strcmp(req->protocol, "RTSP/1.0") == 0) {
            if (strcmp(req->method, "RECORD") != 0) {
                rtsp_reply_header(&reply, "Audio-Jack-Status", "connected; type=digital");
            }
            char server_header[40];
            snprintf(server_header, sizeof(server_header), "AirTunes/%s", s->cfg.srcvers);
            rtsp_reply_header(&reply, "Server", server_header);
            if (cseq_value >= 0) {
                char num[16];
                snprintf(num, sizeof(num), "%lld", cseq_value);
                rtsp_reply_header(&reply, "CSeq", num);
            }
        }
        uint8_t *out = NULL;
        size_t out_len = 0;
        if (rtsp_reply_serialize(&reply, req->protocol, &out, &out_len) == 0) {
            if (c->fd >= 0 && conn_send(c, out, out_len) != 0) {
                keep = false;
            }
            free(out);
            if (c->ap2_pending) {
                ap2_cipher_init(&c->ap2c, c->ap2_shared, AP2_SHARED_LEN, AP2_CHANNEL_CONTROL, true);
                c->ap2_pending = false;
            }
        } else {
            keep = false;
        }
        if (reply.close_connection || c->closing) {
            keep = false;
        }
        rtsp_reply_clear(&reply);
        rtsp_request_clear(req);
        free(req);
    }
    if (offset > 0) {
        memmove(c->rx, c->rx + offset, c->rx_len - offset);
        c->rx_len -= offset;
    }
    c->partial_since_ns = c->rx_len ? (c->partial_since_ns ? c->partial_since_ns : time_mono_ns()) : 0;
    return keep;
}

static bool read_connection(airplay_server_t *s, conn_t *c) {
    for (;;) {
        /* once sealed, the bytes on the wire go to their own buffer and are opened into the request buffer afterwards */
        bool sealed = c->ap2c.on;
        uint8_t **buf = sealed ? &c->wire : &c->rx;
        size_t *len = sealed ? &c->wire_len : &c->rx_len;
        size_t *cap = sealed ? &c->wire_cap : &c->rx_cap;
        if (*len == *cap) {
            if (*cap >= RX_LIMIT) {
                LOG_W(AIRPLAY, "request too large");
                return false;
            }
            size_t grown_cap = *cap ? MIN(*cap * 2, RX_LIMIT) : 4096;
            uint8_t *p = (uint8_t *) realloc(*buf, grown_cap);
            if (!p) {
                return false;
            }
            *buf = p;
            *cap = grown_cap;
        }
        ssize_t n = recv(c->fd, *buf + *len, *cap - *len, 0);
        if (n > 0) {
            *len += (size_t) n;
            c->last_rx_ns = time_mono_ns();
            continue;
        }
        if (n == 0) {
            return false;
        }
        if (errno == EINTR) {
            continue;
        }
        if (errno == EAGAIN || errno == EWOULDBLOCK) {
            break;
        }
        return false;
    }
    if (c->ap2c.on && !ap2_open_wire(c)) {
        return false;
    }
    return process_rx(s, c);
}

/* ------------------------------------------------------------------------- */
/* server thread                                                              */

static uint64_t media_counter(void) {
    return atomic_load_explicit(&g_stats.video_frames_in, memory_order_relaxed) +
           atomic_load_explicit(&g_stats.audio_packets_in, memory_order_relaxed);
}

static void check_timeouts(airplay_server_t *s, uint64_t now) {
    if (s->active) {
        uint64_t count = media_counter();
        if (count != s->last_media_count) {
            s->last_media_count = count;
            s->last_media_ns = now;
        }
    }
    for (int i = 0; i < MAX_CONNECTIONS; i++) {
        conn_t *c = s->conns[i];
        if (!c) {
            continue;
        }
        bool close_it = c->closing;
        if (c->partial_since_ns && now - c->partial_since_ns > PARTIAL_REQUEST_TIMEOUT_NS) {
            LOG_W(NETWORK, "incomplete request timed out");
            close_it = true;
        }
        if (c->session) {
            uint64_t last = MAX(c->last_rx_ns, s->last_media_ns);
            if (now - last > SESSION_TIMEOUT_NS) {
                LOG_W(SESSION, "sender stopped responding, ending session");
                close_it = true;
            }
        } else if (c->reversed) {
            /* An idle event channel is normal while a photo stays on screen. */
            if (now - c->created_ns > REVERSED_MAX_AGE_NS) {
                close_it = true;
            }
        } else if (now - c->last_rx_ns > IDLE_TIMEOUT_NS) {
            close_it = true;
        }
        if (close_it) {
            conn_close(s, i);
        }
    }
    if (s->pin_active && now - s->pin_since_ns > PIN_TIMEOUT_NS) {
        hide_pin(s);
    }
}

static void *server_thread(void *arg) {
    airplay_server_t *s = (airplay_server_t *) arg;
    struct pollfd pfds[3 + MAX_CONNECTIONS];
    conn_t *polled[MAX_CONNECTIONS];

    while (atomic_load(&s->running)) {
        int n = 0;
        pfds[n++] = (struct pollfd) { .fd = s->wake.rd, .events = POLLIN };
        for (int i = 0; i < 2; i++) {
            if (s->listen_fd[i] >= 0) {
                pfds[n++] = (struct pollfd) { .fd = s->listen_fd[i], .events = POLLIN };
            }
        }
        int first_conn = n;
        int conn_count = 0;
        for (int i = 0; i < MAX_CONNECTIONS; i++) {
            if (s->conns[i]) {
                polled[conn_count++] = s->conns[i];
                pfds[n++] = (struct pollfd) { .fd = s->conns[i]->fd, .events = POLLIN };
            }
        }
        /* Timers only run while there is something to supervise; idle means no wakeups. */
        int timeout_ms = (conn_count > 0 || s->pin_active) ? 2000 : -1;
        int r = poll(pfds, (nfds_t) n, timeout_ms);
        if (r < 0 && errno != EINTR) {
            LOG_E(NETWORK, "server poll failed: %d", errno);
            break;
        }
        if (!atomic_load(&s->running)) {
            break;
        }
        if (pfds[0].revents & POLLIN) {
            wakeup_drain(&s->wake);
        }
        if (atomic_exchange(&s->disconnect_requested, false) && s->active) {
            conn_t *owner = s->active->owner;
            LOG_I(SESSION, "session ended on the receiver");
            session_destroy(s, s->active);
            if (owner) {
                owner->closing = true;
            }
        }
        if (s->active && atomic_load(&s->active->stream_lost)) {
            conn_t *owner = s->active->owner;
            session_destroy(s, s->active);
            if (owner) {
                owner->closing = true;
            }
        }
        for (int k = 0; k < conn_count; k++) {
            conn_t *c = polled[k];
            int idx = conn_index(s, c);
            if (idx < 0) {
                continue; /* closed meanwhile */
            }
            short ev = pfds[first_conn + k].revents;
            if (c->closing) {
                conn_close(s, idx);
                continue;
            }
            if (ev & (POLLIN | POLLHUP | POLLERR)) {
                if (!read_connection(s, c)) {
                    idx = conn_index(s, c);
                    if (idx >= 0) {
                        conn_close(s, idx);
                    }
                }
            }
        }
        for (int i = 1; i < first_conn; i++) {
            if (pfds[i].revents & POLLIN) {
                accept_connections(s, pfds[i].fd);
            }
        }
        check_timeouts(s, time_mono_ns());
    }

    for (int i = 0; i < MAX_CONNECTIONS; i++) {
        conn_close(s, i);
    }
    hide_pin(s);
    return NULL;
}

/* ------------------------------------------------------------------------- */
/* public API                                                                 */

/* Keeps only characters that are safe in a TXT record and a header (letters, digits and ",._-"), and falls
 * back to [fallback] when nothing is left, so a bad value can never make the advertisement unusable. */
static void clean_identity(char *value, size_t cap, const char *fallback) {
    size_t n = 0;
    for (size_t i = 0; i < cap && value[i]; i++) {
        char c = value[i];
        bool ok = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == ',' || c == '.' ||
                  c == '_' || c == '-';
        if (ok && n + 1 < cap) {
            value[n++] = c;
        }
    }
    value[n] = '\0';
    if (n == 0) {
        str_copy(value, cap, fallback);
    }
}

airplay_server_t *airplay_server_create(const airplay_config_t *config, const airplay_events_t *events,
                                        const media_sink_ops_t *media, void *media_ctx) {
    if (!config || !media) {
        return NULL;
    }
    airplay_server_t *s = (airplay_server_t *) calloc(1, sizeof(airplay_server_t));
    if (!s) {
        return NULL;
    }
    s->cfg = *config;
    if (events) {
        s->ev = *events;
    }
    s->media = media;
    s->media_ctx = media_ctx;
    s->listen_fd[0] = s->listen_fd[1] = -1;
    s->wake.rd = s->wake.wr = -1;
    s->volume_db = 0.0f;
    pthread_mutex_init(&s->paired_lock, NULL);
    pthread_mutex_init(&s->play_lock, NULL);

    if (s->cfg.display_width < 320 || s->cfg.display_height < 240) {
        s->cfg.display_width = 1920;
        s->cfg.display_height = 1080;
    }
    if (s->cfg.display_fps <= 0 || s->cfg.display_fps > 120) {
        s->cfg.display_fps = 60;
    }
    if (!s->cfg.name[0]) {
        str_copy(s->cfg.name, sizeof(s->cfg.name), "AirPlay TV");
    }
    if (s->cfg.airplay2 > 0) {
        /* an AirPlay 2 receiver says it is one: a source version from the AirPlay 2 era, and a model iOS has no Apple TV tile for */
        clean_identity(s->cfg.model, sizeof(s->cfg.model), "AirPlayTV,1");
        clean_identity(s->cfg.srcvers, sizeof(s->cfg.srcvers), "366.0");
        s->cfg.video = false;
        s->cfg.require_pin = false;
    } else {
        clean_identity(s->cfg.model, sizeof(s->cfg.model), AIRPLAY_MODEL);
        clean_identity(s->cfg.srcvers, sizeof(s->cfg.srcvers), AIRPLAY_SOURCE_VERSION);
    }
    pairing_identity_init(&s->identity, config->identity_seed);
    secure_zero(s->cfg.identity_seed, sizeof(s->cfg.identity_seed));
    hex_encode(s->identity.public_key, ED25519_KEY_SIZE, s->pk_hex);
    format_device_id(s->cfg.device_id, s->device_id_str);
    s->features = airplay_features(&s->cfg);
    return s;
}

int airplay_server_start(airplay_server_t *s) {
    if (!s || s->thread_started) {
        return -1;
    }
    uint16_t port = s->cfg.port;
    s->listen_fd[0] = net_listen_tcp(AF_INET, &port, 8);
    if (s->listen_fd[0] < 0 && port != 0) {
        LOG_W(NETWORK, "port %u is busy, using another port", s->cfg.port);
        port = 0;
        s->listen_fd[0] = net_listen_tcp(AF_INET, &port, 8);
    }
    if (s->listen_fd[0] < 0) {
        LOG_E(NETWORK, "cannot listen for AirPlay connections: %d", errno);
        return -1;
    }
    uint16_t port6 = port;
    s->listen_fd[1] = net_listen_tcp(AF_INET6, &port6, 8);
    if (s->listen_fd[1] < 0) {
        LOG_W(NETWORK, "IPv6 is not available");
    }
    for (int i = 0; i < 2; i++) {
        if (s->listen_fd[i] >= 0) {
            net_set_nonblocking(s->listen_fd[i], true);
        }
    }
    if (wakeup_init(&s->wake) != 0) {
        airplay_server_stop(s);
        return -1;
    }
    s->port = port;
    atomic_store(&s->running, true);
    if (pthread_create(&s->thread, NULL, server_thread, s) != 0) {
        atomic_store(&s->running, false);
        airplay_server_stop(s);
        return -1;
    }
    s->thread_started = true;
    LOG_I(AIRPLAY, "receiver listening on port %u", port);
    return port;
}

void airplay_server_stop(airplay_server_t *s) {
    if (!s) {
        return;
    }
    atomic_store(&s->running, false);
    if (s->thread_started) {
        wakeup_signal(&s->wake);
        pthread_join(s->thread, NULL);
        s->thread_started = false;
    }
    for (int i = 0; i < 2; i++) {
        net_close(&s->listen_fd[i]);
    }
    wakeup_close(&s->wake);
}

void airplay_server_destroy(airplay_server_t *s) {
    if (!s) {
        return;
    }
    airplay_server_stop(s);
    photo_cache_clear(s);
    airplay_server_set_paired_clients(s, NULL, 0);
    pthread_mutex_destroy(&s->paired_lock);
    pthread_mutex_destroy(&s->play_lock);
    secure_zero(&s->identity, sizeof(s->identity));
    free(s);
}

void airplay_server_set_takeover(airplay_server_t *s, int policy) {
    if (s) {
        atomic_store(&s->takeover, policy == AIRPLAY_TAKEOVER_KEEP ? AIRPLAY_TAKEOVER_KEEP : AIRPLAY_TAKEOVER_REPLACE);
    }
}

void airplay_server_disconnect(airplay_server_t *s) {
    if (s) {
        atomic_store(&s->disconnect_requested, true);
        wakeup_signal(&s->wake);
    }
}

bool airplay_server_session_active(airplay_server_t *s) {
    return s && atomic_load(&s->session_active);
}

#ifdef AIRPLAYTV_FUZZING
/* Feeds raw bytes through the request handlers as if they came from a LAN client. */
void airplay_server_fuzz_input(airplay_server_t *s, const uint8_t *data, size_t len) {
    conn_t *c = (conn_t *) calloc(1, sizeof(conn_t));
    if (!c) {
        return;
    }
    c->fd = -1;
    struct sockaddr_in *peer = (struct sockaddr_in *) &c->peer;
    peer->sin_family = AF_INET;
    peer->sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    c->created_ns = c->last_rx_ns = time_mono_ns();
    pairing_session_init(&c->pair);
    fairplay_reset(&c->fp);
    c->rx = (uint8_t *) malloc(len ? len : 1);
    if (c->rx) {
        memcpy(c->rx, data, len);
        c->rx_len = len;
        c->rx_cap = len;
    }
    s->conns[0] = c;
    process_rx(s, c);
    conn_close(s, 0);
}
#endif
