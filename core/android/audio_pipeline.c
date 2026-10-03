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
 * Audio decoding and buffering. AAC-ELD (mirroring) and AAC-LC are decoded by
 * the platform's software AAC decoder through MediaCodec, ALAC by the bundled
 * decoder. Decoded PCM goes into a small ring buffer that the Kotlin AudioTrack
 * writer drains. Playback starts once a small cushion is buffered (40 ms for
 * mirroring, 250 ms for music). The ring keeps latency bounded: if the sender's
 * clock runs faster than the audio output, the oldest samples are dropped
 * instead of letting the delay grow.
 */

#include "audio_pipeline.h"

#include <errno.h>
#include <math.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "alac.h"
#include "log.h"
#include "platform.h"

#define DEFAULT_RATE 44100
#define CHANNELS 2
#define FRAME_BYTES (CHANNELS * 2)
#define RING_SECONDS 2

static struct {
    pthread_mutex_t lock;
    pthread_cond_t cond;
    bool active;
    int ct;
    int rate;              /* the sample rate of this session's audio: 44100 or 48000 */
    unsigned peek_tick;    /* counts the chunks written, for the debug log of what was decoded */
    AMediaCodec *aac;
    alac_file *alac;
    int16_t *pcm;          /* decode scratch buffer */
    size_t pcm_cap;

    int16_t *ring;
    size_t ring_frames;
    size_t read_pos;
    size_t fill;
    size_t target_frames;
    size_t max_frames;
    bool prebuffering;
    int64_t pts_us;
    bool had_data;         /* audio has reached the speakers in this session */
    bool starved;          /* the supply ran dry for STARVE_MS: the sender paused */
    int64_t empty_since_ms; /* monotonic time the supply last ran dry, 0 while audio flows */
} g_ap = { .lock = PTHREAD_MUTEX_INITIALIZER, .cond = PTHREAD_COND_INITIALIZER };

static const uint8_t kAscAacEld[] = { 0xf8, 0xe8, 0x50, 0x00 };  /* ELD, 44.1 kHz, stereo, 480 */

/* The AudioSpecificConfig of AAC-LC stereo: 5 bits of object type (2), 4 of the sampling frequency index, 4 of channels (2). */
static int aac_frequency_index(int rate) {
    static const int kRates[] = { 96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000 };
    for (int i = 0; i < (int) (sizeof(kRates) / sizeof(kRates[0])); i++) {
        if (kRates[i] == rate) {
            return i;
        }
    }
    return 4;
}

static AMediaCodec *create_aac_decoder(int ct, int rate) {
    uint8_t lc[2];
    int index = aac_frequency_index(rate);
    lc[0] = (uint8_t) ((2 << 3) | (index >> 1));
    lc[1] = (uint8_t) (((index & 1) << 7) | (CHANNELS << 3));
    /* The platform software decoder supports ELD everywhere; vendor decoders often do not. */
    static const char *const kNames[] = { "c2.android.aac.decoder", "OMX.google.aac.decoder" };
    AMediaFormat *f = AMediaFormat_new();
    AMediaFormat_setString(f, AMEDIAFORMAT_KEY_MIME, "audio/mp4a-latm");
    AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_SAMPLE_RATE, rate);
    AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_CHANNEL_COUNT, CHANNELS);
    AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_IS_ADTS, 0);
    if (ct == AUDIO_CT_AAC_ELD) {
        AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_AAC_PROFILE, 39);
        AMediaFormat_setBuffer(f, "csd-0", (void *) kAscAacEld, sizeof(kAscAacEld));
    } else {
        AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_AAC_PROFILE, 2);
        AMediaFormat_setBuffer(f, "csd-0", (void *) lc, sizeof(lc));
    }
    AMediaCodec *codec = NULL;
    for (size_t i = 0; i <= sizeof(kNames) / sizeof(kNames[0]); i++) {
        codec = i < sizeof(kNames) / sizeof(kNames[0]) ? AMediaCodec_createCodecByName(kNames[i])
                                                        : AMediaCodec_createDecoderByType("audio/mp4a-latm");
        if (!codec) {
            continue;
        }
        if (AMediaCodec_configure(codec, f, NULL, NULL, 0) == AMEDIA_OK && AMediaCodec_start(codec) == AMEDIA_OK) {
            break;
        }
        AMediaCodec_delete(codec);
        codec = NULL;
    }
    AMediaFormat_delete(f);
    return codec;
}

static void ring_reset_locked(void) {
    g_ap.read_pos = 0;
    g_ap.fill = 0;
    g_ap.prebuffering = true;
}

/* Appends decoded frames; drops the oldest ones when the buffer grows too large. */
static void ring_write_locked(const int16_t *pcm, size_t frames) {
    if (!g_ap.ring || frames == 0) {
        return;
    }
    if (log_enabled(LOGL_DEBUG) && (g_ap.peek_tick++ % 256) == 0) {
        /* in debug logs: whether what was decoded is sound, and how loud (the peak of this chunk, 32767 at the most) */
        int peak = 0;
        for (size_t i = 0; i < frames * CHANNELS; i++) {
            int v = pcm[i] < 0 ? -pcm[i] : pcm[i];
            if (v > peak) {
                peak = v;
            }
        }
        LOG_D(AUDIO, "decoded %zu frames, peak %d", frames, peak);
    }
    if (frames > g_ap.ring_frames) {
        pcm += (frames - g_ap.ring_frames) * CHANNELS;
        frames = g_ap.ring_frames;
    }
    if (g_ap.fill + frames > g_ap.max_frames) {
        size_t excess = g_ap.fill + frames - g_ap.target_frames;
        if (excess > g_ap.fill) {
            excess = g_ap.fill;
        }
        g_ap.read_pos = (g_ap.read_pos + excess) % g_ap.ring_frames;
        g_ap.fill -= excess;
        stat_add(&g_stats.audio_frames_dropped, excess);
    }
    size_t write_pos = (g_ap.read_pos + g_ap.fill) % g_ap.ring_frames;
    size_t first = g_ap.ring_frames - write_pos;
    if (first > frames) {
        first = frames;
    }
    memcpy(g_ap.ring + write_pos * CHANNELS, pcm, first * FRAME_BYTES);
    if (frames > first) {
        memcpy(g_ap.ring, pcm + first * CHANNELS, (frames - first) * FRAME_BYTES);
    }
    g_ap.fill += frames;
    if (g_ap.prebuffering && g_ap.fill >= g_ap.target_frames) {
        g_ap.prebuffering = false;
    }
    atomic_store_explicit(&g_stats.audio_buffer_ms, (int32_t) (g_ap.fill * 1000 / (size_t) g_ap.rate),
                          memory_order_relaxed);
    pthread_cond_signal(&g_ap.cond);
}

bool ap_start(const audio_format_t *format) {
    bool low_latency = format->ct == AUDIO_CT_AAC_ELD;
    pthread_mutex_lock(&g_ap.lock);
    g_ap.ct = format->ct;
    /* 44100 for everything AirPlay 1 sends; AirPlay 2 may send 48000 Hz audio too */
    g_ap.rate = format->sample_rate >= 8000 && format->sample_rate <= 96000 ? format->sample_rate : DEFAULT_RATE;
    size_t rate = (size_t) g_ap.rate;
    g_ap.ring_frames = rate * RING_SECONDS;
    g_ap.ring = (int16_t *) calloc(g_ap.ring_frames, FRAME_BYTES);
    g_ap.pcm_cap = ALAC_MAX_FRAME_SAMPLES * CHANNELS;
    g_ap.pcm = (int16_t *) calloc(g_ap.pcm_cap, sizeof(int16_t));
    /* Mirroring favours latency; music favours smooth playback over Wi-Fi. */
    g_ap.target_frames = low_latency ? rate * 40 / 1000 : rate * 250 / 1000;
    g_ap.max_frames = low_latency ? rate * 150 / 1000 : rate * 700 / 1000;
    ring_reset_locked();
    g_ap.pts_us = 0;

    bool ok = g_ap.ring && g_ap.pcm;
    if (ok && format->ct == AUDIO_CT_ALAC) {
        g_ap.alac = alac_create(16, CHANNELS);
        int spf = format->samples_per_frame > 0 ? format->samples_per_frame : 352;
        ok = g_ap.alac && alac_set_config(g_ap.alac, (uint32_t) spf, 16, 40, 10, 14) == 0;
    } else if (ok) {
        g_ap.aac = create_aac_decoder(format->ct, g_ap.rate);
        ok = g_ap.aac != NULL;
    }
    if (!ok) {
        LOG_E(AUDIO, "cannot create a decoder for audio type %d", format->ct);
        alac_free(g_ap.alac);
        g_ap.alac = NULL;
        free(g_ap.ring);
        free(g_ap.pcm);
        g_ap.ring = NULL;
        g_ap.pcm = NULL;
        pthread_mutex_unlock(&g_ap.lock);
        return false;
    }
    g_ap.had_data = false;
    g_ap.starved = false;
    g_ap.empty_since_ms = 0;
    g_ap.active = true;
    pthread_mutex_unlock(&g_ap.lock);
    LOG_I(AUDIO, "audio output started (%s, %d Hz)", format->ct == AUDIO_CT_ALAC ? "ALAC"
                                                     : format->ct == AUDIO_CT_AAC_ELD ? "AAC-ELD" : "AAC-LC", g_ap.rate);
    platform_on_audio_started(g_ap.rate, CHANNELS, low_latency);
    return true;
}

static void drain_aac_locked(void) {
    for (;;) {
        AMediaCodecBufferInfo info;
        ssize_t idx = AMediaCodec_dequeueOutputBuffer(g_ap.aac, &info, 0);
        if (idx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED || idx == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) {
            continue;
        }
        if (idx < 0) {
            return;
        }
        size_t cap = 0;
        uint8_t *buf = AMediaCodec_getOutputBuffer(g_ap.aac, (size_t) idx, &cap);
        if (buf && info.size > 0 && (size_t) (info.offset + info.size) <= cap) {
            size_t frames = (size_t) info.size / FRAME_BYTES;
            ring_write_locked((const int16_t *) (buf + info.offset), frames);
            stat_add(&g_stats.audio_frames_decoded, 1);
        }
        AMediaCodec_releaseOutputBuffer(g_ap.aac, (size_t) idx, false);
    }
}

void ap_frame(const uint8_t *data, size_t len, uint32_t rtp_ts, uint64_t remote_ts_ns) {
    (void) remote_ts_ns;
    pthread_mutex_lock(&g_ap.lock);
    if (!g_ap.active) {
        pthread_mutex_unlock(&g_ap.lock);
        return;
    }
    if (g_ap.alac) {
        int out = 0;
        if (alac_decode_frame(g_ap.alac, data, (int) len, g_ap.pcm, (int) (g_ap.pcm_cap * sizeof(int16_t)), &out) == 0) {
            ring_write_locked(g_ap.pcm, (size_t) out / FRAME_BYTES);
            stat_add(&g_stats.audio_frames_decoded, 1);
        } else {
            LOG_D(AUDIO, "undecodable ALAC frame (%zu bytes)", len);
        }
    } else if (g_ap.aac) {
        ssize_t idx = AMediaCodec_dequeueInputBuffer(g_ap.aac, 5000);
        if (idx >= 0) {
            size_t cap = 0;
            uint8_t *buf = AMediaCodec_getInputBuffer(g_ap.aac, (size_t) idx, &cap);
            if (buf && len <= cap) {
                memcpy(buf, data, len);
                g_ap.pts_us += 10000;
                AMediaCodec_queueInputBuffer(g_ap.aac, (size_t) idx, 0, len, (uint64_t) g_ap.pts_us, 0);
            } else {
                AMediaCodec_queueInputBuffer(g_ap.aac, (size_t) idx, 0, 0, (uint64_t) g_ap.pts_us, 0);
            }
        }
        drain_aac_locked();
    }
    /* where in the song the newest packet is, for lining lyrics up with what is heard */
    struct timespec arrived;
    clock_gettime(CLOCK_MONOTONIC, &arrived);
    atomic_store_explicit(&g_stats.audio_last_rtp, rtp_ts, memory_order_relaxed);
    atomic_store_explicit(&g_stats.audio_last_rtp_ns, (uint64_t) arrived.tv_sec * 1000000000ULL + (uint64_t) arrived.tv_nsec,
                          memory_order_relaxed);
    pthread_mutex_unlock(&g_ap.lock);
}

void ap_flush(void) {
    pthread_mutex_lock(&g_ap.lock);
    ring_reset_locked();
    if (g_ap.aac) {
        AMediaCodec_flush(g_ap.aac);
    }
    pthread_mutex_unlock(&g_ap.lock);
}

void ap_volume(float db) {
    float gain = db <= -144.0f ? 0.0f : powf(10.0f, db / 20.0f);
    if (gain > 1.0f) {
        gain = 1.0f;
    }
    platform_on_volume(gain);
}

void ap_stop(void) {
    pthread_mutex_lock(&g_ap.lock);
    bool was_active = g_ap.active;
    g_ap.active = false;
    if (g_ap.aac) {
        AMediaCodec_stop(g_ap.aac);
        AMediaCodec_delete(g_ap.aac);
        g_ap.aac = NULL;
    }
    alac_free(g_ap.alac);
    g_ap.alac = NULL;
    free(g_ap.ring);
    free(g_ap.pcm);
    g_ap.ring = NULL;
    g_ap.pcm = NULL;
    g_ap.fill = 0;
    pthread_cond_broadcast(&g_ap.cond);
    pthread_mutex_unlock(&g_ap.lock);
    if (was_active) {
        LOG_I(AUDIO, "audio output stopped");
        platform_on_audio_stopped();
    }
}

/* A sender that pauses does not send a FLUSH; it simply stops sending audio. So "paused" is
 * defined by what the speakers get: no audio for STARVE_MS. */
#define STARVE_MS 300

static int64_t monotonic_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

/* Lock held, nothing to play. Returns true when this makes the supply count as paused. */
static bool note_dry_locked(void) {
    if (!g_ap.had_data || g_ap.starved) {
        return false;
    }
    int64_t now = monotonic_ms();
    if (g_ap.empty_since_ms == 0) {
        g_ap.empty_since_ms = now;
        return false;
    }
    if (now - g_ap.empty_since_ms < STARVE_MS) {
        return false;
    }
    g_ap.starved = true;
    return true;
}

/* Lock held, audio was delivered. Returns true when this ends a pause. */
static bool note_data_locked(void) {
    g_ap.had_data = true;
    g_ap.empty_since_ms = 0;
    bool resumed = g_ap.starved;
    g_ap.starved = false;
    return resumed;
}

int ap_read(uint8_t *out, int max_bytes, int timeout_ms) {
    struct timespec deadline;
    clock_gettime(CLOCK_REALTIME, &deadline);
    deadline.tv_nsec += (long) timeout_ms * 1000000L;
    deadline.tv_sec += deadline.tv_nsec / 1000000000L;
    deadline.tv_nsec %= 1000000000L;

    pthread_mutex_lock(&g_ap.lock);
    while (g_ap.active && (g_ap.fill == 0 || g_ap.prebuffering)) {
        if (pthread_cond_timedwait(&g_ap.cond, &g_ap.lock, &deadline) == ETIMEDOUT) {
            break;
        }
    }
    if (!g_ap.active) {
        pthread_mutex_unlock(&g_ap.lock);
        return -1;
    }
    if (g_ap.fill == 0 || g_ap.prebuffering) {
        bool paused = note_dry_locked();
        pthread_mutex_unlock(&g_ap.lock);
        if (paused) {
            LOG_I(AUDIO, "no audio from the sender: paused");
            platform_on_playing(false);
        }
        return 0;
    }
    size_t frames = (size_t) max_bytes / FRAME_BYTES;
    if (frames > g_ap.fill) {
        frames = g_ap.fill;
    }
    size_t first = g_ap.ring_frames - g_ap.read_pos;
    if (first > frames) {
        first = frames;
    }
    memcpy(out, g_ap.ring + g_ap.read_pos * CHANNELS, first * FRAME_BYTES);
    if (frames > first) {
        memcpy(out + first * FRAME_BYTES, g_ap.ring, (frames - first) * FRAME_BYTES);
    }
    g_ap.read_pos = (g_ap.read_pos + frames) % g_ap.ring_frames;
    g_ap.fill -= frames;
    bool resumed = note_data_locked();
    pthread_mutex_unlock(&g_ap.lock);
    if (resumed) {
        LOG_I(AUDIO, "audio again: playing");
        platform_on_playing(true);
    }
    return (int) (frames * FRAME_BYTES);
}
