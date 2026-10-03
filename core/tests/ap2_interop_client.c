/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky and contributors
 *
 * The other half of the interoperability check (see ap2_interop_stub.c): pairs as a client with the pair_ap library
 * (ejurgensen/owntone, MIT; the copy in shairport-sync's pair_ap folder), which is an independent implementation, then
 * sends encrypted messages. Build outside the project: needs pair_ap's sources (pair.c pair-tlv.c pair_homekit.c), libsodium
 * and OpenSSL (-DCONFIG_OPENSSL), and a stand-in for the Apple TV verification type:
 *
 *   cc -DCONFIG_OPENSSL -I<pair_ap> -I<sodium>/include ap2_interop_client.c <pair_ap>/{pair,pair-tlv,pair_homekit}.c \
 *      <sodium>/lib/libsodium.a -L<openssl>/lib -lcrypto -o ap2_interop_client
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#include <arpa/inet.h>
#include <netinet/in.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#include "pair-internal.h"
#include "pair.h"

struct pair_definition pair_client_fruit; /* never used here: Apple TV verification needs libplist */

static int read_all(int fd, void *buf, size_t n) {
    uint8_t *p = buf;
    while (n) {
        ssize_t r = read(fd, p, n);
        if (r <= 0) {
            return -1;
        }
        p += r;
        n -= (size_t) r;
    }
    return 0;
}

static int send_frame(int fd, const uint8_t *b, size_t n) {
    uint8_t h[4] = { n >> 24, n >> 16, n >> 8, n };
    return write(fd, h, 4) == 4 && write(fd, b, n) == (ssize_t) n ? 0 : -1;
}

static uint8_t *recv_frame(int fd, size_t *n) {
    uint8_t h[4];
    if (read_all(fd, h, 4) != 0) {
        return NULL;
    }
    *n = ((size_t) h[0] << 24) | ((size_t) h[1] << 16) | ((size_t) h[2] << 8) | h[3];
    uint8_t *b = malloc(*n + 1);
    return b && read_all(fd, b, *n) == 0 ? b : NULL;
}

int main(int argc, char **argv) {
    int port = argc > 1 ? atoi(argv[1]) : 7777;
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    struct sockaddr_in a = { .sin_family = AF_INET, .sin_port = htons(port), .sin_addr.s_addr = htonl(INADDR_LOOPBACK) };
    if (connect(fd, (struct sockaddr *) &a, sizeof(a)) != 0) {
        perror("connect");
        return 2;
    }
    struct pair_setup_context *ctx = pair_setup_new(PAIR_CLIENT_HOMEKIT_TRANSIENT, "3939", NULL, NULL, "interop-client");
    if (!ctx) {
        fprintf(stderr, "pair_setup_new failed\n");
        return 1;
    }
    uint8_t *out = NULL, *reply = NULL;
    size_t out_len = 0, reply_len = 0;
    int steps = 0;
    struct pair_result *result = NULL;
    /* the library's first step is its own call; the later ones go through pair_setup(), which reports -1 on the last answer
     * of a transient pairing because there is nothing more to send: completion is what pair_setup_result() says */
    out = pair_setup_request1(&out_len, ctx);
    for (;;) {
        if (!out) {
            fprintf(stderr, "pair_ap: %s\n", pair_setup_errmsg(ctx));
            return 1;
        }
        send_frame(fd, out, out_len);
        free(out);
        free(reply);
        reply = recv_frame(fd, &reply_len);
        if (!reply) {
            fprintf(stderr, "the stub closed the connection after step %d\n", steps);
            return 1;
        }
        steps++;
        int ret = pair_setup(&out, &out_len, ctx, reply, reply_len);
        if (pair_setup_result(NULL, &result, ctx) == 0) {
            break;
        }
        if (ret < 0) {
            fprintf(stderr, "pair_ap refused the answer: %s\n", pair_setup_errmsg(ctx));
            return 1;
        }
    }
    fprintf(stderr, "pair_ap says the pairing is complete after %d exchanges, secret %zu bytes\n", steps, result->shared_secret_len);
    struct pair_cipher_context *cc = pair_cipher_new(PAIR_CLIENT_HOMEKIT_TRANSIENT, 0, result->shared_secret, result->shared_secret_len, "");
    if (!cc) {
        return 1;
    }
    size_t sizes[2] = { 20, 3000 };
    for (int round = 0; round < 2; round++) {
        uint8_t *plain = malloc(sizes[round]);
        memcpy(plain, "ping-from-pair_ap", 17);
        for (size_t i = 17; i < sizes[round]; i++) {
            plain[i] = (uint8_t) i;
        }
        uint8_t *enc;
        size_t enc_len;
        if (pair_encrypt(&enc, &enc_len, plain, sizes[round], cc) != (ssize_t) sizes[round]) {
            fprintf(stderr, "pair_ap could not encrypt\n");
            return 1;
        }
        send_frame(fd, enc, enc_len);
        size_t rl;
        uint8_t *r = recv_frame(fd, &rl);
        if (!r) {
            fprintf(stderr, "no answer in round %d\n", round);
            return 1;
        }
        uint8_t *dec;
        size_t dec_len;
        if (pair_decrypt(&dec, &dec_len, r, rl, cc) < 0) {
            fprintf(stderr, "pair_ap could not open the stub's answer: %s\n", pair_cipher_errmsg(cc));
            return 1;
        }
        dec[dec_len] = 0;
        fprintf(stderr, "round %d: sent %zu bytes, answer \"%.*s\"\n", round, sizes[round], (int) dec_len, dec);
        char want[64];
        snprintf(want, sizeof(want), "pong %zu", sizes[round]);
        if (dec_len != strlen(want) || memcmp(dec, want, dec_len) != 0) {
            fprintf(stderr, "WRONG ANSWER\n");
            return 1;
        }
    }
    fprintf(stderr, "INTEROP OK\n");
    return 0;
}
