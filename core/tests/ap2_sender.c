/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky and contributors
 *
 * A manual test sender for the receiver's AirPlay 2 mode, not part of the unit tests. It pairs with the pair_ap library (an
 * independent implementation of the pairing, MIT licensed), seals its requests with it, sets up a realtime stream and sends a
 * 440 Hz tone as ChaCha20-Poly1305 sealed ALAC frames (libsodium), the way an iPhone sends audio in AirPlay 2.
 *
 *   ap2_sender <ip> <port> [seconds] [--ntp] [--name NAME]
 *
 * Build outside the project (see ap2_interop_client.c for what is needed):
 *   cc -DCONFIG_OPENSSL -I<pair_ap> -I<core>/src -I<sodium>/include ap2_sender.c <core>/src/bplist.c <pair_ap>/{pair,pair-tlv,pair_homekit}.c \
 *      <sodium>/lib/libsodium.a -L<openssl>/lib -lcrypto -lm -o ap2_sender
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#include <arpa/inet.h>
#include <math.h>
#include <netinet/in.h>
#include <poll.h>
#include <sodium.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#include "bplist.h"
#include "pair-internal.h"
#include "pair.h"

struct pair_definition pair_client_fruit; /* never used: Apple TV verification needs libplist */

static int g_fd = -1;
static const char *g_aac_path = NULL; /* buffered audio from an ADTS file (afconvert -f adts -d aac) instead of the sine as ALAC */
static uint8_t *g_aac = NULL;
static size_t g_aac_len = 0;
static size_t g_aac_off[4096];
static size_t g_aac_size[4096];
static int g_aac_count = 0;
static int g_aac_rate = 44100;

/* Splits an ADTS file into its raw AAC frames. */
static int load_aac(const char *path) {
    FILE *f = fopen(path, "rb");
    if (!f) {
        perror(path);
        return -1;
    }
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    g_aac = (uint8_t *) malloc((size_t) n);
    g_aac_len = fread(g_aac, 1, (size_t) n, f);
    fclose(f);
    static const int rates[] = { 96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000 };
    size_t pos = 0;
    while (pos + 7 < g_aac_len && g_aac_count < 4096) {
        const uint8_t *h = g_aac + pos;
        if (h[0] != 0xff || (h[1] & 0xf0) != 0xf0) {
            break;
        }
        size_t flen = (size_t) (((h[3] & 3) << 11) | (h[4] << 3) | (h[5] >> 5));
        size_t hlen = (h[1] & 1) ? 7 : 9;
        int idx = (h[2] >> 2) & 15;
        if (idx < 12) {
            g_aac_rate = rates[idx];
        }
        if (flen <= hlen || pos + flen > g_aac_len) {
            break;
        }
        g_aac_off[g_aac_count] = pos + hlen;
        g_aac_size[g_aac_count] = flen - hlen;
        g_aac_count++;
        pos += flen;
    }
    printf("%s: %d AAC frames at %d Hz\n", path, g_aac_count, g_aac_rate);
    return g_aac_count > 0 ? 0 : -1;
}

static int g_event_update_ok = -1; /* the receiver's first message on the event connection was a good updateInfo: 1, bad 0, unchecked -1 */
static bool g_buffered = false; /* buffered audio (type 103) instead of realtime */
static const char *g_abuse = ""; /* garbage, badtype, noshk, tamper, abrupt: what to do wrong, see main */
static int g_cseq = 0;
static struct pair_cipher_context *g_cipher = NULL;
static uint8_t *g_wire = NULL;
static size_t g_wire_len = 0;
static uint8_t *g_plain = NULL;
static size_t g_plain_len = 0;

static void append(uint8_t **buf, size_t *len, const uint8_t *data, size_t n) {
    *buf = realloc(*buf, *len + n + 1);
    memcpy(*buf + *len, data, n);
    *len += n;
}

static int send_all(const uint8_t *d, size_t n) {
    while (n) {
        ssize_t w = write(g_fd, d, n);
        if (w <= 0) {
            return -1;
        }
        d += w;
        n -= (size_t) w;
    }
    return 0;
}

/* One RTSP request; the reply's status is returned and its body is malloc'd into *body. */
static int rtsp(const char *method, const char *url, const char *ctype, const uint8_t *data, size_t len, uint8_t **body,
                size_t *body_len) {
    char head[512];
    int hn = snprintf(head, sizeof(head), "%s %s RTSP/1.0\r\nCSeq: %d\r\nUser-Agent: AirPlay/660.4\r\nContent-Length: %zu\r\n%s%s%s\r\n",
                      method, url, ++g_cseq, len, ctype ? "Content-Type: " : "", ctype ? ctype : "", ctype ? "\r\n" : "");
    uint8_t *msg = malloc((size_t) hn + len);
    memcpy(msg, head, (size_t) hn);
    if (len) {
        memcpy(msg + hn, data, len);
    }
    size_t mlen = (size_t) hn + len;
    int rc;
    if (g_cipher) {
        uint8_t *enc;
        size_t enc_len;
        if (pair_encrypt(&enc, &enc_len, msg, mlen, g_cipher) != (ssize_t) mlen) {
            return -1;
        }
        rc = send_all(enc, enc_len);
        free(enc);
    } else {
        rc = send_all(msg, mlen);
    }
    free(msg);
    if (rc != 0) {
        return -1;
    }
    for (;;) {
        /* a complete reply in the plain buffer? */
        char *end = g_plain_len ? memmem(g_plain, g_plain_len, "\r\n\r\n", 4) : NULL;
        if (end) {
            size_t head_len = (size_t) (end - (char *) g_plain) + 4;
            size_t clen = 0;
            char *cl = memmem(g_plain, head_len, "Content-Length:", 15);
            if (cl) {
                clen = (size_t) atoi(cl + 15);
            }
            if (g_plain_len >= head_len + clen) {
                int status = atoi((char *) g_plain + 9);
                if (body) {
                    *body = malloc(clen + 1);
                    memcpy(*body, g_plain + head_len, clen);
                    (*body)[clen] = 0;
                    *body_len = clen;
                }
                memmove(g_plain, g_plain + head_len + clen, g_plain_len - head_len - clen);
                g_plain_len -= head_len + clen;
                return status;
            }
        }
        uint8_t buf[8192];
        ssize_t r = read(g_fd, buf, sizeof(buf));
        if (r <= 0) {
            return -1;
        }
        if (g_cipher) {
            append(&g_wire, &g_wire_len, buf, (size_t) r);
            uint8_t *dec;
            size_t dec_len = 0;
            ssize_t used = pair_decrypt(&dec, &dec_len, g_wire, g_wire_len, g_cipher);
            if (used < 0) {
                fprintf(stderr, "could not open the receiver's reply\n");
                return -1;
            }
            append(&g_plain, &g_plain_len, dec, dec_len);
            free(dec);
            memmove(g_wire, g_wire + used, g_wire_len - (size_t) used);
            g_wire_len -= (size_t) used;
        } else {
            append(&g_plain, &g_plain_len, buf, (size_t) r);
        }
    }
}

static int rtsp_plist(const char *method, const char *url, bp_node_t *root, bp_node_t **reply) {
    uint8_t *data = NULL;
    size_t len = 0;
    if (root) {
        bp_write(root, &data, &len);
        bp_free(root);
    }
    uint8_t *body = NULL;
    size_t body_len = 0;
    int status = rtsp(method, url, root ? "application/x-apple-binary-plist" : NULL, data, len, &body, &body_len);
    free(data);
    if (reply) {
        *reply = body && body_len ? bp_parse(body, body_len) : NULL;
    }
    free(body);
    return status;
}

static void dset(bp_node_t *d, const char *k, uint64_t v) {
    bp_dict_set(d, k, bp_new_uint(v));
}

static size_t alac_frame(uint8_t *out, int phase) {
    const int samples = 352;
    size_t bits = 23 + (size_t) samples * 32;
    size_t bytes = (bits + 7) / 8;
    memset(out, 0, bytes);
    size_t pos = 0;
#define PUT(value, n)                                                                       \
    for (int b_ = (n) - 1; b_ >= 0; b_--, pos++) {                                          \
        if (((uint32_t) (value) >> b_) & 1) out[pos / 8] |= (uint8_t) (0x80 >> (pos % 8)); \
    }
    PUT(1, 3);
    PUT(0, 4);
    PUT(0, 12);
    PUT(0, 1);
    PUT(0, 2);
    PUT(1, 1);
    for (int i = 0; i < samples; i++) {
        double t = (double) (phase * samples + i) / 44100.0;
        int16_t v = (int16_t) (8000.0 * sin(2.0 * 3.14159265358979 * 440.0 * t));
        PUT((uint16_t) v, 16);
        PUT((uint16_t) v, 16);
    }
#undef PUT
    return bytes;
}

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    if (argc < 3) {
        fprintf(stderr, "usage: ap2_sender <ip> <port> [seconds] [--ntp] [--name NAME] [--buffered] [--aac FILE.aac] [--abuse garbage|badtype|noshk|tamper|abrupt]\n");
        return 2;
    }
    int seconds = argc > 3 && argv[3][0] != '-' ? atoi(argv[3]) : 6;
    bool ntp = false;
    const char *name = "AP2 test sender";
    for (int i = 3; i < argc; i++) {
        if (strcmp(argv[i], "--ntp") == 0) {
            ntp = true;
        } else if (strcmp(argv[i], "--name") == 0 && i + 1 < argc) {
            name = argv[++i];
        } else if (strcmp(argv[i], "--buffered") == 0) {
            g_buffered = true;
        } else if (strcmp(argv[i], "--aac") == 0 && i + 1 < argc) {
            g_aac_path = argv[++i];
            g_buffered = true;
        } else if (strcmp(argv[i], "--abuse") == 0 && i + 1 < argc) {
            g_abuse = argv[++i];
        }
    }
    if (sodium_init() < 0) {
        return 2;
    }
    if (g_aac_path && load_aac(g_aac_path) != 0) {
        return 2;
    }
    g_fd = socket(AF_INET, SOCK_STREAM, 0);
    struct sockaddr_in a = { .sin_family = AF_INET, .sin_port = htons((uint16_t) atoi(argv[2])) };
    inet_pton(AF_INET, argv[1], &a.sin_addr);
    if (connect(g_fd, (struct sockaddr *) &a, sizeof(a)) != 0) {
        perror("connect");
        return 2;
    }

    /* GET /info with the qualifier, as an iPhone asks */
    {
        bp_node_t *q = bp_new_dict();
        bp_node_t *arr = bp_new_array();
        bp_array_append(arr, bp_new_string("txtAirPlay"));
        bp_dict_set(q, "qualifier", arr);
        bp_node_t *reply = NULL;
        int st = rtsp_plist("GET", "/info", q, &reply);
        printf("GET /info -> %d\n", st);
        if (st != 200 || !reply) {
            return 1;
        }
        uint64_t f = 0;
        bp_get_uint(bp_dict_get(reply, "features"), &f);
        printf("  features 0x%llx, model %s, name %s\n", (unsigned long long) f, bp_get_string(bp_dict_get(reply, "model")),
               bp_get_string(bp_dict_get(reply, "name")));
    }

    /* transient pairing with pair_ap */
    struct pair_setup_context *ctx = pair_setup_new(PAIR_CLIENT_HOMEKIT_TRANSIENT, "3939", NULL, NULL, "ap2-test-sender");
    size_t out_len = 0;
    uint8_t *out = pair_setup_request1(&out_len, ctx);
    struct pair_result *result = NULL;
    int steps = 0;
    for (;;) {
        if (!out) {
            fprintf(stderr, "pair_ap: %s\n", pair_setup_errmsg(ctx));
            return 1;
        }
        uint8_t *reply = NULL;
        size_t reply_len = 0;
        int st = rtsp("POST", "/pair-setup", "application/octet-stream", out, out_len, &reply, &reply_len);
        free(out);
        if (st != 200) {
            fprintf(stderr, "pair-setup -> %d\n", st);
            return 1;
        }
        steps++;
        int ret = pair_setup(&out, &out_len, ctx, reply, reply_len);
        free(reply);
        if (pair_setup_result(NULL, &result, ctx) == 0) {
            break;
        }
        if (ret < 0) {
            fprintf(stderr, "pair_ap refused: %s\n", pair_setup_errmsg(ctx));
            return 1;
        }
    }
    printf("paired after %d exchanges\n", steps);
    g_cipher = pair_cipher_new(PAIR_CLIENT_HOMEKIT_TRANSIENT, 0, result->shared_secret, result->shared_secret_len, "");

    /* SETUP, first stage */
    uint16_t event_port = 0, timing_port = 0;
    {
        bp_node_t *d = bp_new_dict();
        bp_dict_set(d, "name", bp_new_string(name));
        bp_dict_set(d, "model", bp_new_string("iPhone17,1"));
        bp_dict_set(d, "deviceID", bp_new_string("AA:BB:CC:11:22:33"));
        bp_dict_set(d, "sessionUUID", bp_new_string("0A1B2C3D-0000-4000-8000-000000000001"));
        bp_dict_set(d, "timingProtocol", bp_new_string(ntp ? "NTP" : "PTP"));
        bp_dict_set(d, "isMultiroom", bp_new_bool(false));
        dset(d, "timingPort", 51000);
        bp_node_t *reply = NULL;
        int st = rtsp_plist("SETUP", "rtsp://127.0.0.1/12345", d, &reply);
        printf("SETUP 1 -> %d\n", st);
        if (st != 200 || !reply) {
            return 1;
        }
        uint64_t v = 0;
        bp_get_uint(bp_dict_get(reply, "eventPort"), &v);
        event_port = (uint16_t) v;
        v = 0;
        bp_get_uint(bp_dict_get(reply, "timingPort"), &v);
        timing_port = (uint16_t) v;
        printf("  eventPort %u, timingPort %u\n", event_port, timing_port);
    }

    /* the event connection, sealed with the events keys */
    int ev = -1;
    if (event_port) {
        ev = socket(AF_INET, SOCK_STREAM, 0);
        struct sockaddr_in e = a;
        e.sin_port = htons(event_port);
        if (connect(ev, (struct sockaddr *) &e, sizeof(e)) == 0) {
            struct pair_cipher_context *ec = pair_cipher_new(PAIR_CLIENT_HOMEKIT_TRANSIENT, 1, result->shared_secret, result->shared_secret_len, "");
            uint8_t *enc;
            size_t enc_len;
            const char *hello = "event hello";
            if (ec && pair_encrypt(&enc, &enc_len, (const uint8_t *) hello, strlen(hello), ec) > 0) {
                (void) write(ev, enc, enc_len);
                free(enc);
            }
            printf("  event connection opened\n");
            /* what the receiver says first on this connection (shairport-sync sends an updateInfo request): read it with pair_ap's cipher,
             * which checks the receiver's events keys from the other side */
            if (ec) {
                uint8_t wire[8192];
                size_t have = 0;
                uint8_t *plain_all = NULL;
                size_t plain_all_len = 0;
                for (int tries = 0; tries < 15; tries++) {
                    struct pollfd pf = { .fd = ev, .events = POLLIN };
                    if (poll(&pf, 1, 100) <= 0) {
                        if (have) break;
                        continue;
                    }
                    ssize_t n = read(ev, wire + have, sizeof(wire) - have);
                    if (n <= 0) break;
                    have += (size_t) n;
                    struct pollfd more = { .fd = ev, .events = POLLIN };
                    if (poll(&more, 1, 150) > 0) continue;
                    break;
                }
                if (have) {
                    uint8_t *plain = NULL;
                    size_t plain_len = 0;
                    ssize_t used = pair_decrypt(&plain, &plain_len, wire, have, ec);
                    if (used < 0 || !plain) {
                        printf("  event update: could not be opened with the events keys (%s)\n", pair_cipher_errmsg(ec));
                        g_event_update_ok = 0;
                    } else {
                        append(&plain_all, &plain_all_len, plain, plain_len);
                        free(plain);
                        char first[80];
                        size_t m = 0;
                        while (m < plain_all_len && m < sizeof(first) - 1 && plain_all[m] != '\r') {
                            first[m] = (char) plain_all[m];
                            m++;
                        }
                        first[m] = '\0';
                        const uint8_t *body = NULL;
                        for (size_t i = 0; i + 3 < plain_all_len; i++) {
                            if (memcmp(plain_all + i, "\r\n\r\n", 4) == 0) {
                                body = plain_all + i + 4;
                                break;
                            }
                        }
                        bp_node_t *msg = body ? bp_parse(body, plain_all_len - (size_t) (body - plain_all)) : NULL;
                        const char *type = msg ? bp_get_string(bp_dict_get(msg, "type")) : NULL;
                        bp_node_t *value = msg ? bp_dict_get(msg, "value") : NULL;
                        const char *sender_addr = value ? bp_get_string(bp_dict_get(value, "senderAddress")) : NULL;
                        size_t txt_len = 0;
                        const uint8_t *txt = value ? bp_get_data(bp_dict_get(value, "txtAirPlay"), &txt_len) : NULL;
                        printf("  event update: \"%s\", %zu bytes, type %s, senderAddress %s, txtAirPlay %zu bytes\n", first, plain_all_len,
                               type ? type : "?", sender_addr ? sender_addr : "-", txt ? txt_len : 0);
                        g_event_update_ok = (type && strcmp(type, "updateInfo") == 0 && txt) ? 1 : 0;
                        bp_free(msg);
                    }
                    free(plain_all);
                } else {
                    printf("  event update: nothing arrived\n");
                    g_event_update_ok = 0;
                }
            }
        }
    }

    if (strcmp(g_abuse, "garbage") == 0) {
        /* bytes that are no sealed frame: the receiver must close the connection and go on */
        uint8_t junk[64];
        randombytes_buf(junk, sizeof(junk));
        junk[0] = 40;
        junk[1] = 0;
        send_all(junk, sizeof(junk));
        uint8_t b[16];
        struct pollfd pf = { .fd = g_fd, .events = POLLIN };
        int pr = poll(&pf, 1, 3000);
        ssize_t rr = pr > 0 ? read(g_fd, b, sizeof(b)) : -2;
        printf("garbage: the connection was %s\n", rr == 0 ? "closed (good)" : rr < 0 && pr > 0 ? "reset (good)" : "NOT closed (bad)");
        return rr <= 0 && pr > 0 ? 0 : 1;
    }

    /* SETUP, second stage: one realtime audio stream */
    uint8_t shk[32];
    randombytes_buf(shk, sizeof(shk));
    int audio_fd = socket(AF_INET, SOCK_DGRAM, 0);
    struct sockaddr_in ctl = { .sin_family = AF_INET };
    bind(audio_fd, (struct sockaddr *) &ctl, sizeof(ctl));
    socklen_t cl = sizeof(ctl);
    getsockname(audio_fd, (struct sockaddr *) &ctl, &cl);
    uint16_t data_port = 0;
    {
        bp_node_t *d = bp_new_dict();
        bp_node_t *streams = bp_new_array();
        bp_node_t *s0 = bp_new_dict();
        dset(s0, "audioFormat", g_aac_path ? (g_aac_rate == 48000 ? 0x800000 : 0x400000) : 0x40000);
        bp_dict_set(s0, "audioMode", bp_new_string("default"));
        dset(s0, "controlPort", ntohs(ctl.sin_port));
        dset(s0, "ct", g_aac_path ? 4 : 2);
        bp_dict_set(s0, "isMedia", bp_new_bool(true));
        dset(s0, "latencyMax", 88200);
        dset(s0, "latencyMin", 11025);
        if (strcmp(g_abuse, "noshk") != 0) {
            bp_dict_set(s0, "shk", bp_new_data(shk, sizeof(shk)));
        }
        dset(s0, "spf", g_aac_path ? 1024 : 352);
        dset(s0, "sr", 44100);
        dset(s0, "type", strcmp(g_abuse, "badtype") == 0 ? 110 : (g_buffered ? 103 : 96));
        bp_array_append(streams, s0);
        bp_dict_set(d, "streams", streams);
        bp_node_t *reply = NULL;
        int st = rtsp_plist("SETUP", "rtsp://127.0.0.1/12345", d, &reply);
        printf("SETUP 2 -> %d\n", st);
        if (strcmp(g_abuse, "badtype") == 0 || strcmp(g_abuse, "noshk") == 0) {
            size_t n = reply ? bp_count(bp_dict_get(reply, "streams")) : 0;
            printf("%s: status %d, %zu streams set up (none expected)\n", g_abuse, st, n);
            printf("TEARDOWN -> %d\n", rtsp("TEARDOWN", "rtsp://127.0.0.1/12345", NULL, NULL, 0, NULL, NULL));
            return n == 0 ? 0 : 1;
        }
        if (st != 200 || !reply) {
            return 1;
        }
        bp_node_t *r0 = bp_array_get(bp_dict_get(reply, "streams"), 0);
        uint64_t v = 0;
        bp_get_uint(bp_dict_get(r0, "dataPort"), &v);
        data_port = (uint16_t) v;
        printf("  dataPort %u\n", data_port);
        if (!data_port) {
            return 1;
        }
    }

    printf("RECORD -> %d\n", rtsp("RECORD", "rtsp://127.0.0.1/12345", NULL, NULL, 0, NULL, NULL));
    {
        const char *vol = "volume: -20.000000\r\n";
        printf("SET_PARAMETER volume -> %d\n", rtsp("SET_PARAMETER", "rtsp://127.0.0.1/12345", "text/parameters", (const uint8_t *) vol, strlen(vol), NULL, NULL));
    }
    {
        uint8_t *body = NULL;
        size_t bl = 0;
        printf("POST /feedback -> %d\n", rtsp("POST", "/feedback", NULL, NULL, 0, &body, &bl));
        if (body && bl) {
            bp_node_t *fb = bp_parse(body, bl);
            printf("  streams in the answer: %zu\n", fb ? bp_count(bp_dict_get(fb, "streams")) : 0);
        }
    }

    if (g_buffered) {
        /* the sender connects to the data port over TCP and pushes sealed blocks ahead of time; playing starts with the rate anchor */
        int tcp = socket(AF_INET, SOCK_STREAM, 0);
        struct sockaddr_in t = a;
#ifdef __APPLE__
        t.sin_len = sizeof(t);
#endif
        t.sin_port = htons(data_port);
        if (connect(tcp, (struct sockaddr *) &t, sizeof(t)) != 0) {
            perror("buffered connect");
            return 1;
        }
        uint32_t ts0 = 222222;
        {
            bp_node_t *d = bp_new_dict();
            bp_dict_set(d, "rate", bp_new_uint(1));
            dset(d, "rtpTime", ts0);
            dset(d, "networkTimeSecs", 1000);
            dset(d, "networkTimeFrac", 0);
            printf("SETRATEANCHORTI play -> %d\n", rtsp_plist("SETRATEANCHORTI", "rtsp://127.0.0.1/12345", d, NULL));
        }
        int step = g_aac_path ? 1024 : 352;
        int rate = g_aac_path ? g_aac_rate : 44100;
        int frames = seconds * rate / step;
        uint32_t ssrc = g_aac_path ? (g_aac_rate == 48000 ? 0x17000000u : 0x16000000u) : 0xfaceu;
        uint64_t counter = 1;
        for (int i = 0; i < frames; i++) {
            uint8_t plain[4096], pkt[4300];
            size_t plen;
            if (g_aac_path) {
                int k = i % g_aac_count;
                plen = g_aac_size[k];
                memcpy(plain, g_aac + g_aac_off[k], plen);
            } else {
                plen = alac_frame(plain, i);
            }
            uint32_t seq = 5000 + (uint32_t) i, ts = ts0 + (uint32_t) i * (uint32_t) step;
            pkt[0] = (uint8_t) (seq >> 24); pkt[1] = (uint8_t) (seq >> 16); pkt[2] = (uint8_t) (seq >> 8); pkt[3] = (uint8_t) seq;
            pkt[4] = (uint8_t) (ts >> 24); pkt[5] = (uint8_t) (ts >> 16); pkt[6] = (uint8_t) (ts >> 8); pkt[7] = (uint8_t) ts;
            pkt[8] = (uint8_t) (ssrc >> 24); pkt[9] = (uint8_t) (ssrc >> 16); pkt[10] = (uint8_t) (ssrc >> 8); pkt[11] = (uint8_t) ssrc;
            uint8_t nonce[12] = { 0 };
            for (int b = 0; b < 8; b++) {
                nonce[4 + b] = (uint8_t) (counter >> (8 * b));
            }
            unsigned long long clen = 0;
            crypto_aead_chacha20poly1305_ietf_encrypt(pkt + 12, &clen, plain, plen, pkt + 4, 8, NULL, nonce, shk);
            memcpy(pkt + 12 + clen, nonce + 4, 8);
            counter++;
            size_t total = 2 + 12 + (size_t) clen + 8;
            uint8_t head[2] = { (uint8_t) (total >> 8), (uint8_t) total };
            if (write(tcp, head, 2) != 2 || write(tcp, pkt, total - 2) != (ssize_t) (total - 2)) {
                perror("buffered write");
                return 1;
            }
        }
        printf("pushed %d blocks (%d s of audio) at once\n", frames, seconds);
        if (strcmp(g_abuse, "pauseflush") == 0) {
            sleep(2);
            bp_node_t *d = bp_new_dict();
            bp_dict_set(d, "rate", bp_new_uint(0));
            printf("pause -> %d\n", rtsp_plist("SETRATEANCHORTI", "rtsp://127.0.0.1/12345", d, NULL));
            sleep(1);
            bp_node_t *f = bp_new_dict();
            dset(f, "flushUntilSeq", 5000 + 300);
            dset(f, "flushUntilTS", ts0 + 300 * 352);
            printf("flush -> %d\n", rtsp_plist("FLUSHBUFFERED", "rtsp://127.0.0.1/12345", f, NULL));
            bp_node_t *r = bp_new_dict();
            bp_dict_set(r, "rate", bp_new_uint(1));
            dset(r, "rtpTime", ts0 + 300 * 352);
            dset(r, "networkTimeSecs", 1002);
            dset(r, "networkTimeFrac", 0);
            printf("resume -> %d\n", rtsp_plist("SETRATEANCHORTI", "rtsp://127.0.0.1/12345", r, NULL));
        }
        sleep((unsigned) seconds + 1);
        printf("TEARDOWN -> %d\n", rtsp("TEARDOWN", "rtsp://127.0.0.1/12345", NULL, NULL, 0, NULL, NULL));
        if (g_event_update_ok == 0) {
            printf("FAIL: the receiver's first message on the event connection was not a good updateInfo\n");
            return 1;
        }
        return 0;
    }

    /* the audio: 352-sample ALAC frames, each sealed: RTP header, ciphertext and tag, then the 8-byte nonce */
    struct sockaddr_in to = a;
    to.sin_port = htons(data_port);
#ifdef __APPLE__
    to.sin_len = sizeof(to);
#endif
    uint16_t seq = 1000;
    uint32_t ts = 111111;
    uint64_t nonce_counter = 1;
    int frames = seconds * 44100 / 352;
    struct timespec t0;
    clock_gettime(CLOCK_MONOTONIC, &t0);
    for (int i = 0; i < frames; i++) {
        uint8_t plain[2048], pkt[2200];
        size_t plen = alac_frame(plain, i);
        pkt[0] = 0x80;
        pkt[1] = 0x60;
        pkt[2] = (uint8_t) (seq >> 8);
        pkt[3] = (uint8_t) seq;
        pkt[4] = (uint8_t) (ts >> 24);
        pkt[5] = (uint8_t) (ts >> 16);
        pkt[6] = (uint8_t) (ts >> 8);
        pkt[7] = (uint8_t) ts;
        memcpy(pkt + 8, "\x11\x22\x33\x44", 4);
        uint8_t nonce[12] = { 0 };
        for (int b = 0; b < 8; b++) {
            nonce[4 + b] = (uint8_t) (nonce_counter >> (8 * b));
        }
        unsigned long long clen = 0;
        crypto_aead_chacha20poly1305_ietf_encrypt(pkt + 12, &clen, plain, plen, pkt + 4, 8, NULL, nonce, shk);
        memcpy(pkt + 12 + clen, nonce + 4, 8);
        nonce_counter++;
        if (strcmp(g_abuse, "tamper") == 0 && i % 2 == 1) {
            pkt[20] ^= 0x01; /* every second packet no longer authenticates */
        }
        if (sendto(audio_fd, pkt, 12 + (size_t) clen + 8, 0, (struct sockaddr *) &to, sizeof(to)) < 0 && i < 3) {
            fprintf(stderr, "sendto fam=%d len=%d port=%d fd=%d: ", to.sin_family, (int) to.sin_len, ntohs(to.sin_port), audio_fd);
            perror("");
        }
        seq++;
        ts += 352;
        /* paced like real time */
        struct timespec now;
        clock_gettime(CLOCK_MONOTONIC, &now);
        double elapsed = (double) (now.tv_sec - t0.tv_sec) + (double) (now.tv_nsec - t0.tv_nsec) / 1e9;
        double due = (double) (i + 1) * 352.0 / 44100.0;
        if (due > elapsed) {
            usleep((useconds_t) ((due - elapsed) * 1e6));
        }
    }
    printf("sent %d frames\n", frames);
    if (strcmp(g_abuse, "abrupt") == 0) {
        printf("abrupt: closing every socket without TEARDOWN\n");
        return 0;
    }
    printf("TEARDOWN -> %d\n", rtsp("TEARDOWN", "rtsp://127.0.0.1/12345", NULL, NULL, 0, NULL, NULL));
    (void) timing_port;
    if (g_event_update_ok == 0) {
        printf("FAIL: the receiver's first message on the event connection was not a good updateInfo\n");
        return 1;
    }
    return 0;
}
