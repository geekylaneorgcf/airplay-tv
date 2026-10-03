/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky and contributors
 *
 * A manual interoperability check, not part of the unit tests: the receiver's AirPlay 2 pairing and channel code behind a bare
 * TCP socket (frames of a 4-byte big-endian length and a body), so that an independent implementation, the pair_ap client
 * (see ap2_interop_client.c), can pair with it and exchange encrypted messages.
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

#include "ap2_pair.h"

static int read_all(int fd, void *buf, size_t n) {
    uint8_t *p = (uint8_t *) buf;
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

static int read_frame(int fd, uint8_t *buf, size_t cap, size_t *len) {
    uint8_t h[4];
    if (read_all(fd, h, 4) != 0) {
        return -1;
    }
    size_t n = ((size_t) h[0] << 24) | ((size_t) h[1] << 16) | ((size_t) h[2] << 8) | h[3];
    if (n > cap || read_all(fd, buf, n) != 0) {
        return -1;
    }
    *len = n;
    return 0;
}

static int write_frame(int fd, const uint8_t *buf, size_t n) {
    uint8_t h[4] = { (uint8_t) (n >> 24), (uint8_t) (n >> 16), (uint8_t) (n >> 8), (uint8_t) n };
    if (write(fd, h, 4) != 4 || (n && write(fd, buf, n) != (ssize_t) n)) {
        return -1;
    }
    return 0;
}

int main(int argc, char **argv) {
    int port = argc > 1 ? atoi(argv[1]) : 7777;
    int ls = socket(AF_INET, SOCK_STREAM, 0);
    int one = 1;
    setsockopt(ls, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    struct sockaddr_in a = { .sin_family = AF_INET, .sin_port = htons((uint16_t) port), .sin_addr.s_addr = htonl(INADDR_LOOPBACK) };
    if (bind(ls, (struct sockaddr *) &a, sizeof(a)) != 0 || listen(ls, 1) != 0) {
        perror("listen");
        return 2;
    }
    fprintf(stderr, "stub listening on %d\n", port);
    int fd = accept(ls, NULL, NULL);
    static uint8_t in[16384], plain[16384];
    size_t n;
    ap2_pair_t *p = ap2_pair_new(AP2_PIN);
    uint8_t shared[AP2_SHARED_LEN];
    while (!ap2_pair_done(p, shared)) {
        if (read_frame(fd, in, sizeof(in), &n) != 0) {
            fprintf(stderr, "no more frames before pairing finished\n");
            return 1;
        }
        uint8_t *out = NULL;
        size_t out_len = 0;
        if (ap2_pair_setup(p, in, n, &out, &out_len) != 0) {
            fprintf(stderr, "pair-setup refused the message\n");
            return 1;
        }
        write_frame(fd, out, out_len);
        free(out);
    }
    fprintf(stderr, "paired; secret starts %02x%02x%02x%02x\n", shared[0], shared[1], shared[2], shared[3]);
    ap2_cipher_t c;
    ap2_cipher_init(&c, shared, sizeof(shared), AP2_CHANNEL_CONTROL, true);
    /* two encrypted messages from the client: a short one and one of several blocks; each is echoed back reversed in length */
    for (int round = 0; round < 2; round++) {
        if (read_frame(fd, in, sizeof(in), &n) != 0) {
            return 1;
        }
        size_t made = 0, used = 0;
        if (ap2_open(&c, in, n, plain, sizeof(plain), &made, &used) != 0 || used != n) {
            fprintf(stderr, "could not open the client's message (round %d)\n", round);
            return 1;
        }
        fprintf(stderr, "opened %zu bytes, starts \"%.8s\"\n", made, (const char *) plain);
        static uint8_t reply[20000];
        char text[64];
        snprintf(text, sizeof(text), "pong %zu", made);
        size_t rn = ap2_seal(&c, (const uint8_t *) text, strlen(text), reply, sizeof(reply));
        write_frame(fd, reply, rn);
    }
    fprintf(stderr, "OK\n");
    close(fd);
    ap2_pair_free(p);
    return 0;
}
