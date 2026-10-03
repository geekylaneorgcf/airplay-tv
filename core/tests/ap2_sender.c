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
        fprintf(stderr, "usage: ap2_sender <ip> <port> [seconds] [--ntp] [--name NAME] [--abuse garbage|badtype|noshk|tamper|abrupt]\n");
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
        } else if (strcmp(argv[i], "--abuse") == 0 && i + 1 < argc) {
            g_abuse = argv[++i];
        }
    }
    if (sodium_init() < 0) {
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
        dset(s0, "audioFormat", 0x40000);
        bp_dict_set(s0, "audioMode", bp_new_string("default"));
        dset(s0, "controlPort", ntohs(ctl.sin_port));
        dset(s0, "ct", 2);
        bp_dict_set(s0, "isMedia", bp_new_bool(true));
        dset(s0, "latencyMax", 88200);
        dset(s0, "latencyMin", 11025);
        if (strcmp(g_abuse, "noshk") != 0) {
            bp_dict_set(s0, "shk", bp_new_data(shk, sizeof(shk)));
        }
        dset(s0, "spf", 352);
        dset(s0, "sr", 44100);
        dset(s0, "type", strcmp(g_abuse, "badtype") == 0 ? 103 : 96);
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
    return 0;
}
