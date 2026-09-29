/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#ifndef AIRPLAYTV_NETUTIL_H
#define AIRPLAYTV_NETUTIL_H

#include <netinet/in.h>
#include <sys/socket.h>

#include "common.h"

/* A self-pipe used to wake threads blocked in poll(). */
typedef struct {
    int rd;
    int wr;
} wakeup_t;

int wakeup_init(wakeup_t *w);
void wakeup_signal(wakeup_t *w);
void wakeup_drain(wakeup_t *w);
void wakeup_close(wakeup_t *w);

/* Creates a listening TCP socket bound to the wildcard address. *port may be 0
 * (ephemeral) and receives the bound port. Returns the fd or -1. */
int net_listen_tcp(int family, uint16_t *port, int backlog);

/* Creates a UDP socket bound to the wildcard address. */
int net_bind_udp(int family, uint16_t *port);

int net_set_nonblocking(int fd, bool nonblocking);
void net_close(int *fd);

/* Writes all bytes, waiting up to timeout_ms for the socket to become writable. */
int net_send_all(int fd, const void *data, size_t len, int timeout_ms);

/* Reads exactly len bytes unless the peer closes, an error occurs, or the wake
 * pipe fires. Returns 1 on success, 0 on orderly close, -1 on error/wakeup. */
int net_recv_exact(int fd, void *buf, size_t len, wakeup_t *wake);

/* Normalises IPv4-mapped IPv6 addresses to plain IPv4. */
void net_normalize_addr(struct sockaddr_storage *ss, socklen_t *len);

/* Formats an address without the port. */
void net_addr_to_string(const struct sockaddr_storage *ss, char *out, size_t cap);

/* True if the peer is on a directly attached network (same subnet as one of our
 * interfaces, IPv6 link-local, or loopback). Used to refuse off-LAN connections. */
bool net_is_lan_peer(const struct sockaddr_storage *peer);

/* Compares the address part (not the port) of two socket addresses. */
bool net_same_host(const struct sockaddr_storage *a, const struct sockaddr_storage *b);

void net_set_port(struct sockaddr_storage *ss, uint16_t port);

#endif
