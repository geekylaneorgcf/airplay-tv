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
 * RAOP timing client. Periodically sends NTP-style timing requests to the
 * sender's timing port and estimates the offset between the sender clock and
 * our monotonic clock (the approach of UxPlay's raop_ntp.c, simplified).
 */

#ifndef AIRPLAYTV_NTP_H
#define AIRPLAYTV_NTP_H

#include <sys/socket.h>

#include "common.h"

typedef struct ntp_client ntp_client_t;

/* Converts a big-endian 64-bit NTP timestamp (Q32.32) to nanoseconds. */
uint64_t ntp_to_ns(uint64_t ntp);
uint64_t ns_to_ntp(uint64_t ns);

/* Binds a UDP socket (same family as remote), starts the timing thread and returns
 * the local port in *local_port. remote_port 0 disables the requests but keeps the
 * socket so SETUP can still report a timing port. */
ntp_client_t *ntp_client_start(const struct sockaddr_storage *remote, uint16_t remote_port, uint16_t *local_port);
void ntp_client_stop(ntp_client_t *ntp);

/* Offset such that local_mono_ns = remote_ns - offset. Returns false until the first
 * valid exchange. */
bool ntp_client_offset(ntp_client_t *ntp, int64_t *offset_ns);

#endif
