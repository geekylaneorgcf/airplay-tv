/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

/*
 * Hardware video decoding for the mirroring stream: AMediaCodec rendering
 * straight into the playback activity's Surface.
 *
 * Latency policy: every access unit is queued as soon as it arrives and every
 * decoded picture is released for display immediately; when the decoder hands
 * back several pictures at once only the newest one is shown.
 *
 * The sender only sends a key frame when a stream starts or its format
 * changes, so the decoder must never lose its reference pictures while the
 * session runs. When the Surface goes away (the activity is stopped) the
 * decoder is switched to an invisible ImageReader surface and keeps decoding;
 * when a Surface comes back it is switched back without a restart. If a
 * decoder has to be recreated anyway, the frames since the last key frame are
 * kept in a bounded cache and replayed into the new decoder.
 */

#include "video_decoder.h"

#include <media/NdkImageReader.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/resource.h>
#include <time.h>
#include <unistd.h>

#include "log.h"
#include "platform.h"
#include "util.h"

#define INPUT_TIMEOUT_US 20000
#define OUTPUT_TIMEOUT_US 20000
#define INPUT_STALL_NS (1500 * NS_PER_MS)
#define CACHE_MAX_BYTES (24u * 1024 * 1024)
#define CACHE_MAX_FRAMES 900u
#define TS_RING 64

typedef struct {
    AMediaCodec *codec;
    pthread_t thread;
    bool thread_started;
    _Atomic bool running;
    _Atomic bool render;
    _Atomic bool failed;
    video_codec_t type;
    int width;
    int height;
    pthread_mutex_t ts_lock;
    int64_t ts_pts[TS_RING];
    uint64_t ts_in[TS_RING];
    unsigned ts_next;
    int reported_w;
    int reported_h;
} decoder_t;

typedef struct {
    uint32_t len;
    uint64_t remote_ts;
} cache_record_t;

static struct {
    pthread_mutex_t lock;
    bool active;
    ANativeWindow *display;
    AImageReader *sink_reader;
    ANativeWindow *sink_window;
    decoder_t *dec;
    bool recreate;

    video_codec_t codec_type;
    int width;
    int height;
    uint8_t config[4096];
    size_t config_len;
    bool inband_config;

    uint8_t *cache;
    size_t cache_len;
    size_t cache_cap;
    uint32_t cache_frames;
    bool cache_valid;

    char avc_name[128];
    char hevc_name[128];
    int options;
    int64_t last_pts_us;
} g_vd = { .lock = PTHREAD_MUTEX_INITIALIZER, .options = VD_OPT_LOW_LATENCY | VD_OPT_VENDOR_KEYS | VD_OPT_REALTIME };

void vd_init(void) {
}

void vd_set_preferences(const char *avc_decoder, const char *hevc_decoder, int options, int max_width, int max_height) {
    (void) max_width;
    (void) max_height;
    pthread_mutex_lock(&g_vd.lock);
    str_copy(g_vd.avc_name, sizeof(g_vd.avc_name), avc_decoder ? avc_decoder : "");
    str_copy(g_vd.hevc_name, sizeof(g_vd.hevc_name), hevc_decoder ? hevc_decoder : "");
    g_vd.options = options;
    pthread_mutex_unlock(&g_vd.lock);
}

/* ------------------------------------------------------------------------- */
/* decoder instance                                                           */

static const char *mime_for(video_codec_t type) {
    return type == VIDEO_CODEC_H265 ? "video/hevc" : "video/avc";
}

static bool starts_with(const char *s, const char *prefix) {
    return strncasecmp(s, prefix, strlen(prefix)) == 0;
}

static void apply_low_latency(AMediaFormat *f, const char *name, int options) {
    if (options & VD_OPT_LOW_LATENCY) {
        AMediaFormat_setInt32(f, "low-latency", 1);
    }
    if (options & VD_OPT_REALTIME) {
        AMediaFormat_setInt32(f, "priority", 0);
    }
    if (!(options & VD_OPT_VENDOR_KEYS) || !name) {
        return;
    }
    /* vendor keys, as used by other open-source low-latency streaming clients */
    if (starts_with(name, "omx.qcom") || starts_with(name, "c2.qti")) {
        AMediaFormat_setInt32(f, "vendor.qti-ext-dec-picture-order.enable", 1);
        AMediaFormat_setInt32(f, "vendor.qti-ext-dec-low-latency.enable", 1);
    } else if (starts_with(name, "omx.hisi") || starts_with(name, "c2.hisi")) {
        AMediaFormat_setInt32(f, "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req", 1);
        AMediaFormat_setInt32(f, "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy", -1);
    } else if (starts_with(name, "omx.exynos") || starts_with(name, "c2.exynos")) {
        AMediaFormat_setInt32(f, "vendor.rtc-ext-dec-low-latency.enable", 1);
    } else if (starts_with(name, "omx.amlogic") || starts_with(name, "c2.amlogic")) {
        AMediaFormat_setInt32(f, "vendor.low-latency.enable", 1);
        AMediaFormat_setInt32(f, "vdec-lowlatency", 1);
    }
}

/* Splits Annex-B parameter sets into csd buffers (H.264: SPS, PPS; H.265: all in csd-0). */
static void set_codec_specific_data(AMediaFormat *f, video_codec_t type, const uint8_t *cfg, size_t len) {
    if (!len) {
        return;
    }
    if (type == VIDEO_CODEC_H265) {
        AMediaFormat_setBuffer(f, "csd-0", (void *) cfg, len);
        return;
    }
    /* find the first PPS start code (NAL type 8) */
    size_t pps = len;
    for (size_t i = 0; i + 4 < len; i++) {
        if (cfg[i] == 0 && cfg[i + 1] == 0 && cfg[i + 2] == 0 && cfg[i + 3] == 1 && (cfg[i + 4] & 0x1f) == 8) {
            pps = i;
            break;
        }
    }
    AMediaFormat_setBuffer(f, "csd-0", (void *) cfg, pps);
    if (pps < len) {
        AMediaFormat_setBuffer(f, "csd-1", (void *) (cfg + pps), len - pps);
    }
}

static void report_output_format(decoder_t *d) {
    AMediaFormat *f = AMediaCodec_getOutputFormat(d->codec);
    if (!f) {
        return;
    }
    int32_t w = 0;
    int32_t h = 0;
    int32_t left = 0;
    int32_t top = 0;
    int32_t right = -1;
    int32_t bottom = -1;
    AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_WIDTH, &w);
    AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_HEIGHT, &h);
    if (AMediaFormat_getInt32(f, "crop-left", &left) && AMediaFormat_getInt32(f, "crop-right", &right) &&
        AMediaFormat_getInt32(f, "crop-top", &top) && AMediaFormat_getInt32(f, "crop-bottom", &bottom) &&
        right > left && bottom > top) {
        w = right - left + 1;
        h = bottom - top + 1;
    }
    AMediaFormat_delete(f);
    if (w > 0 && h > 0 && (w != d->reported_w || h != d->reported_h)) {
        d->reported_w = w;
        d->reported_h = h;
        atomic_store_explicit(&g_stats.video_width, (uint32_t) w, memory_order_relaxed);
        atomic_store_explicit(&g_stats.video_height, (uint32_t) h, memory_order_relaxed);
        LOG_I(DECODER, "decoded picture size %dx%d", w, h);
        platform_on_video_size(w, h);
    }
}

static uint64_t lookup_input_time(decoder_t *d, int64_t pts) {
    uint64_t t = 0;
    pthread_mutex_lock(&d->ts_lock);
    for (unsigned i = 0; i < TS_RING; i++) {
        if (d->ts_pts[i] == pts) {
            t = d->ts_in[i];
            break;
        }
    }
    pthread_mutex_unlock(&d->ts_lock);
    return t;
}

static void *output_thread(void *arg) {
    decoder_t *d = (decoder_t *) arg;
    setpriority(PRIO_PROCESS, 0, -8);
    int errors = 0;
    while (atomic_load(&d->running)) {
        AMediaCodecBufferInfo info;
        ssize_t idx = AMediaCodec_dequeueOutputBuffer(d->codec, &info, OUTPUT_TIMEOUT_US);
        if (idx >= 0) {
            errors = 0;
            /* show only the newest picture if several are ready */
            for (;;) {
                AMediaCodecBufferInfo next_info;
                ssize_t next = AMediaCodec_dequeueOutputBuffer(d->codec, &next_info, 0);
                if (next == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
                    report_output_format(d);
                    continue;
                }
                if (next < 0) {
                    break;
                }
                AMediaCodec_releaseOutputBuffer(d->codec, (size_t) idx, false);
                stat_add(&g_stats.video_frames_dropped, 1);
                stat_add(&g_stats.video_frames_decoded, 1);
                idx = next;
                info = next_info;
            }
            uint64_t now = time_mono_ns();
            uint64_t in = lookup_input_time(d, info.presentationTimeUs);
            if (in && now > in) {
                stat_add(&g_stats.video_decode_time_us_total, (now - in) / NS_PER_US);
                stat_add(&g_stats.video_decode_samples, 1);
            }
            stat_add(&g_stats.video_frames_decoded, 1);
            if (atomic_load(&d->render) && info.size > 0) {
                AMediaCodec_releaseOutputBufferAtTime(d->codec, (size_t) idx, (int64_t) now);
                stat_add(&g_stats.video_frames_rendered, 1);
            } else {
                AMediaCodec_releaseOutputBuffer(d->codec, (size_t) idx, false);
            }
        } else if (idx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            report_output_format(d);
        } else if (idx == AMEDIACODEC_INFO_TRY_AGAIN_LATER || idx == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) {
            continue;
        } else {
            if (++errors > 20) {
                LOG_E(DECODER, "video decoder keeps failing (%zd)", idx);
                atomic_store(&d->failed, true);
                break;
            }
            usleep(10000);
        }
    }
    return NULL;
}

static void decoder_destroy(decoder_t *d) {
    if (!d) {
        return;
    }
    atomic_store(&d->running, false);
    if (d->thread_started) {
        pthread_join(d->thread, NULL);
    }
    if (d->codec) {
        AMediaCodec_stop(d->codec);
        AMediaCodec_delete(d->codec);
    }
    pthread_mutex_destroy(&d->ts_lock);
    free(d);
}

static AMediaCodec *create_codec(const char *preferred, const char *mime, char *used, size_t used_cap) {
    AMediaCodec *codec = NULL;
    if (preferred && preferred[0]) {
        codec = AMediaCodec_createCodecByName(preferred);
        if (codec) {
            str_copy(used, used_cap, preferred);
            return codec;
        }
        LOG_W(DECODER, "preferred decoder %s is not available", preferred);
    }
    /* the platform default decoder; its name is unknown before API 28, so no vendor keys */
    codec = AMediaCodec_createDecoderByType(mime);
    str_copy(used, used_cap, "");
    return codec;
}

/* Must be called with g_vd.lock held. */
static decoder_t *decoder_create(ANativeWindow *window, bool render) {
    video_codec_t type = g_vd.codec_type;
    const char *mime = mime_for(type);
    const char *preferred = type == VIDEO_CODEC_H265 ? g_vd.hevc_name : g_vd.avc_name;
    int width = g_vd.width > 0 ? g_vd.width : 1920;
    int height = g_vd.height > 0 ? g_vd.height : 1080;

    /* Try the full low-latency configuration first and fall back step by step. */
    const int ladders[4] = {
        g_vd.options,
        g_vd.options & ~VD_OPT_VENDOR_KEYS,
        g_vd.options & VD_OPT_REALTIME,
        0,
    };
    for (int attempt = 0; attempt < 4; attempt++) {
        if (attempt > 0 && ladders[attempt] == ladders[attempt - 1]) {
            continue;
        }
        char name[128];
        AMediaCodec *codec = create_codec(preferred, mime, name, sizeof(name));
        if (!codec) {
            LOG_E(DECODER, "no %s decoder available", mime);
            return NULL;
        }
        AMediaFormat *f = AMediaFormat_new();
        AMediaFormat_setString(f, AMEDIAFORMAT_KEY_MIME, mime);
        AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_WIDTH, width);
        AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_HEIGHT, height);
        int32_t max_input = width * height * 3 / 2;
        if (max_input < 2 * 1024 * 1024) {
            max_input = 2 * 1024 * 1024;
        }
        AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_MAX_INPUT_SIZE, max_input);
        set_codec_specific_data(f, type, g_vd.config, g_vd.config_len);
        apply_low_latency(f, name, ladders[attempt]);

        media_status_t st = AMediaCodec_configure(codec, f, window, NULL, 0);
        AMediaFormat_delete(f);
        if (st == AMEDIA_OK) {
            st = AMediaCodec_start(codec);
        }
        if (st != AMEDIA_OK) {
            LOG_W(DECODER, "%s rejected configuration %d (%d)", name, attempt, st);
            AMediaCodec_delete(codec);
            continue;
        }
        decoder_t *d = (decoder_t *) calloc(1, sizeof(decoder_t));
        if (!d) {
            AMediaCodec_stop(codec);
            AMediaCodec_delete(codec);
            return NULL;
        }
        d->codec = codec;
        d->type = type;
        d->width = width;
        d->height = height;
        pthread_mutex_init(&d->ts_lock, NULL);
        for (int i = 0; i < TS_RING; i++) {
            d->ts_pts[i] = -1;
        }
        atomic_store(&d->render, render);
        atomic_store(&d->running, true);
        if (pthread_create(&d->thread, NULL, output_thread, d) != 0) {
            decoder_destroy(d);
            return NULL;
        }
        d->thread_started = true;
        LOG_I(DECODER, "using %s for %s %dx%d (options %d)", name, mime, width, height, ladders[attempt]);
        return d;
    }
    return NULL;
}

/* ------------------------------------------------------------------------- */
/* surfaces                                                                   */

/* Invisible surface that keeps the decoder alive while no display is attached. */
static ANativeWindow *sink_window_locked(void) {
    if (g_vd.sink_window) {
        return g_vd.sink_window;
    }
    AImageReader *reader = NULL;
    media_status_t st = AImageReader_newWithUsage(640, 360, AIMAGE_FORMAT_PRIVATE,
                                                  AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE, 4, &reader);
    if (st != AMEDIA_OK || !reader) {
        LOG_W(DECODER, "background surface unavailable (%d)", st);
        return NULL;
    }
    ANativeWindow *window = NULL;
    if (AImageReader_getWindow(reader, &window) != AMEDIA_OK || !window) {
        AImageReader_delete(reader);
        return NULL;
    }
    g_vd.sink_reader = reader;
    g_vd.sink_window = window;
    return window;
}

static void release_sink_locked(void) {
    if (g_vd.sink_reader) {
        AImageReader_delete(g_vd.sink_reader);
    }
    g_vd.sink_reader = NULL;
    g_vd.sink_window = NULL;
}

void vd_set_display(ANativeWindow *window) {
    if (window) {
        ANativeWindow_acquire(window);
    }
    pthread_mutex_lock(&g_vd.lock);
    ANativeWindow *old = g_vd.display;
    g_vd.display = window;
    decoder_t *d = g_vd.dec;
    if (d) {
        ANativeWindow *target = window ? window : sink_window_locked();
        media_status_t st = target ? AMediaCodec_setOutputSurface(d->codec, target) : AMEDIA_ERROR_UNKNOWN;
        if (st == AMEDIA_OK) {
            atomic_store(&d->render, window != NULL);
            LOG_D(DECODER, "decoder output moved to the %s surface", window ? "display" : "background");
        } else {
            /* the decoder cannot follow; stop showing pictures and rebuild it on the next frame */
            atomic_store(&d->render, false);
            g_vd.recreate = true;
            LOG_W(DECODER, "decoder cannot switch surfaces (%d), it will be recreated", st);
        }
    }
    pthread_mutex_unlock(&g_vd.lock);
    if (old) {
        ANativeWindow_release(old);
    }
}

/* ------------------------------------------------------------------------- */
/* key-frame cache                                                            */

static void cache_clear_locked(bool valid) {
    g_vd.cache_len = 0;
    g_vd.cache_frames = 0;
    g_vd.cache_valid = valid;
}

static void cache_append_locked(const uint8_t *au, size_t len, uint64_t remote_ts) {
    if (!g_vd.cache_valid) {
        return;
    }
    size_t need = g_vd.cache_len + sizeof(cache_record_t) + len;
    if (need > CACHE_MAX_BYTES || g_vd.cache_frames >= CACHE_MAX_FRAMES) {
        cache_clear_locked(false);
        return;
    }
    if (need > g_vd.cache_cap) {
        size_t cap = g_vd.cache_cap ? g_vd.cache_cap : 1024 * 1024;
        while (cap < need) {
            cap *= 2;
        }
        if (cap > CACHE_MAX_BYTES) {
            cap = CACHE_MAX_BYTES;
        }
        uint8_t *p = (uint8_t *) realloc(g_vd.cache, cap);
        if (!p) {
            cache_clear_locked(false);
            return;
        }
        g_vd.cache = p;
        g_vd.cache_cap = cap;
    }
    cache_record_t rec = { (uint32_t) len, remote_ts };
    memcpy(g_vd.cache + g_vd.cache_len, &rec, sizeof(rec));
    memcpy(g_vd.cache + g_vd.cache_len + sizeof(rec), au, len);
    g_vd.cache_len = need;
    g_vd.cache_frames++;
}

/* ------------------------------------------------------------------------- */
/* input                                                                      */

/* Queues one access unit. Called from the mirroring thread without the lock; the
 * decoder is only created and destroyed on that thread, so d stays valid. */
static bool queue_access_unit(decoder_t *d, const uint8_t *prefix, size_t prefix_len,
                              const uint8_t *au, size_t len) {
    uint64_t start = time_mono_ns();
    ssize_t idx;
    for (;;) {
        idx = AMediaCodec_dequeueInputBuffer(d->codec, INPUT_TIMEOUT_US);
        if (idx >= 0) {
            break;
        }
        if (atomic_load(&d->failed) || idx != AMEDIACODEC_INFO_TRY_AGAIN_LATER ||
            time_mono_ns() - start > INPUT_STALL_NS) {
            LOG_W(DECODER, "decoder does not accept input (%zd)", idx);
            return false;
        }
    }
    size_t cap = 0;
    uint8_t *buf = AMediaCodec_getInputBuffer(d->codec, (size_t) idx, &cap);
    size_t total = prefix_len + len;
    if (!buf || total > cap) {
        LOG_W(DECODER, "access unit of %zu bytes does not fit the input buffer (%zu)", total, cap);
        AMediaCodec_queueInputBuffer(d->codec, (size_t) idx, 0, 0, 0, 0);
        return true;
    }
    if (prefix_len) {
        memcpy(buf, prefix, prefix_len);
    }
    memcpy(buf + prefix_len, au, len);

    int64_t pts = (int64_t) (time_mono_ns() / NS_PER_US);
    if (pts <= g_vd.last_pts_us) {
        pts = g_vd.last_pts_us + 1;
    }
    g_vd.last_pts_us = pts;
    pthread_mutex_lock(&d->ts_lock);
    d->ts_pts[d->ts_next % TS_RING] = pts;
    d->ts_in[d->ts_next % TS_RING] = time_mono_ns();
    d->ts_next++;
    pthread_mutex_unlock(&d->ts_lock);

    return AMediaCodec_queueInputBuffer(d->codec, (size_t) idx, 0, total, (uint64_t) pts, 0) == AMEDIA_OK;
}

static void destroy_decoder_locked(void) {
    decoder_t *d = g_vd.dec;
    g_vd.dec = NULL;
    if (d) {
        atomic_fetch_add(&g_stats.video_decoder_resets, 1);
        pthread_mutex_unlock(&g_vd.lock);
        decoder_destroy(d);
        pthread_mutex_lock(&g_vd.lock);
    }
}

void vd_start(void) {
    pthread_mutex_lock(&g_vd.lock);
    g_vd.active = true;
    g_vd.recreate = false;
    g_vd.codec_type = VIDEO_CODEC_NONE;
    g_vd.config_len = 0;
    g_vd.width = g_vd.height = 0;
    cache_clear_locked(false);
    pthread_mutex_unlock(&g_vd.lock);
    LOG_I(VIDEO, "mirroring started");
    platform_on_video_started();
}

void vd_config(video_codec_t codec, const uint8_t *config, size_t len, int width, int height) {
    pthread_mutex_lock(&g_vd.lock);
    if (!g_vd.active || len > sizeof(g_vd.config)) {
        pthread_mutex_unlock(&g_vd.lock);
        return;
    }
    bool format_changed = codec != g_vd.codec_type ||
                          (width > 0 && height > 0 && (width != g_vd.width || height != g_vd.height));
    memcpy(g_vd.config, config, len);
    g_vd.config_len = len;
    g_vd.codec_type = codec;
    if (width > 0 && height > 0) {
        g_vd.width = width;
        g_vd.height = height;
    }
    if (g_vd.dec && format_changed) {
        /* a new picture size is decoded by a fresh decoder, starting at the next key frame */
        destroy_decoder_locked();
    }
    g_vd.inband_config = g_vd.dec != NULL;
    cache_clear_locked(false);
    pthread_mutex_unlock(&g_vd.lock);
}

/* Time from the sender's capture timestamp to our decoder input, once the clock
 * offset to the sender is known (network, sender encoding and buffering). */
static void record_delay(uint64_t remote_ts_ns) {
    if (!atomic_load_explicit(&g_stats.clock_synced, memory_order_relaxed) || !remote_ts_ns) {
        return;
    }
    int64_t offset_ns = atomic_load_explicit(&g_stats.clock_offset_us, memory_order_relaxed) * 1000;
    int64_t capture_local = (int64_t) remote_ts_ns - offset_ns;
    int64_t delay = (int64_t) time_mono_ns() - capture_local;
    if (delay > 0 && delay < 5 * (int64_t) NS_PER_SEC) {
        stat_add(&g_stats.video_delay_us_total, (uint64_t) delay / NS_PER_US);
        stat_add(&g_stats.video_delay_samples, 1);
    }
}

void vd_frame(const uint8_t *au, size_t len, uint64_t remote_ts_ns, bool keyframe) {
    record_delay(remote_ts_ns);
    pthread_mutex_lock(&g_vd.lock);
    if (!g_vd.active || g_vd.codec_type == VIDEO_CODEC_NONE) {
        pthread_mutex_unlock(&g_vd.lock);
        return;
    }
    if (keyframe) {
        cache_clear_locked(true);
    }
    cache_append_locked(au, len, remote_ts_ns);

    if (g_vd.dec && (g_vd.recreate || atomic_load(&g_vd.dec->failed))) {
        destroy_decoder_locked();
        g_vd.recreate = false;
    }

    bool replay = false;
    if (!g_vd.dec) {
        if (!keyframe && !g_vd.cache_valid) {
            stat_add(&g_stats.video_frames_dropped, 1);
            pthread_mutex_unlock(&g_vd.lock);
            return; /* nothing to decode from until the next key frame */
        }
        ANativeWindow *window = g_vd.display ? g_vd.display : sink_window_locked();
        if (!window) {
            pthread_mutex_unlock(&g_vd.lock);
            return;
        }
        g_vd.dec = decoder_create(window, g_vd.display != NULL);
        if (!g_vd.dec) {
            pthread_mutex_unlock(&g_vd.lock);
            return;
        }
        g_vd.inband_config = false;
        replay = !keyframe;
    }
    decoder_t *d = g_vd.dec;

    if (replay) {
        /* Feed everything since the last key frame (this frame is the last record).
         * A copy is replayed so that surface changes are not blocked meanwhile. */
        LOG_I(DECODER, "restoring the picture from %u cached frames", g_vd.cache_frames);
        size_t replay_len = g_vd.cache_len;
        uint8_t *copy = (uint8_t *) malloc(replay_len);
        if (copy) {
            memcpy(copy, g_vd.cache, replay_len);
        }
        pthread_mutex_unlock(&g_vd.lock);
        bool ok = copy != NULL;
        size_t off = 0;
        while (ok && off + sizeof(cache_record_t) <= replay_len) {
            cache_record_t rec;
            memcpy(&rec, copy + off, sizeof(rec));
            off += sizeof(rec);
            if (rec.len > replay_len - off) {
                break;
            }
            ok = queue_access_unit(d, NULL, 0, copy + off, rec.len);
            off += rec.len;
        }
        free(copy);
        if (!ok) {
            pthread_mutex_lock(&g_vd.lock);
            g_vd.recreate = true;
            pthread_mutex_unlock(&g_vd.lock);
        }
        return;
    }

    uint8_t *prefix = NULL;
    size_t prefix_len = 0;
    if (g_vd.inband_config && g_vd.config_len) {
        prefix = (uint8_t *) malloc(g_vd.config_len);
        if (prefix) {
            memcpy(prefix, g_vd.config, g_vd.config_len);
            prefix_len = g_vd.config_len;
        }
        g_vd.inband_config = false;
    }
    pthread_mutex_unlock(&g_vd.lock);

    bool ok = queue_access_unit(d, prefix, prefix_len, au, len);
    free(prefix);
    if (!ok) {
        pthread_mutex_lock(&g_vd.lock);
        g_vd.recreate = true;
        pthread_mutex_unlock(&g_vd.lock);
    }
}

void vd_suspend(bool suspended) {
    LOG_I(VIDEO, "video %s", suspended ? "paused by the sender" : "resumed");
}

void vd_stop(void) {
    pthread_mutex_lock(&g_vd.lock);
    bool was_active = g_vd.active;
    g_vd.active = false;
    destroy_decoder_locked();
    release_sink_locked();
    free(g_vd.cache);
    g_vd.cache = NULL;
    g_vd.cache_cap = 0;
    cache_clear_locked(false);
    g_vd.codec_type = VIDEO_CODEC_NONE;
    g_vd.config_len = 0;
    pthread_mutex_unlock(&g_vd.lock);
    if (was_active) {
        LOG_I(VIDEO, "mirroring stopped");
        platform_on_video_stopped();
    }
}
