/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky
 *
 * Server side of SRP-6a as used by AirPlay PIN pairing: 2048-bit group from
 * RFC 5054, SHA-1, padded k and u, and Apple's 40-byte session key
 * K = SHA1(S | 00000000) | SHA1(S | 00000001).
 *
 * The protocol logic follows csrp by Tom Cocagne (MIT) as adapted for AirPlay
 * in UxPlay; this implementation uses mbedTLS big-number arithmetic.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#ifndef AIRPLAYTV_SRP_H
#define AIRPLAYTV_SRP_H

#include "common.h"

#define SRP_SALT_LEN 16
#define SRP_MODULUS_LEN 256
#define SRP_PROOF_LEN 20
#define SRP_SESSION_KEY_LEN 40

typedef struct srp_server srp_server_t;

/* Starts a session for username/password. Produces the salt and the server public
 * value B (big-endian, minimal length, at most SRP_MODULUS_LEN bytes). */
srp_server_t *srp_server_start(const char *username, const char *password,
                               uint8_t salt[SRP_SALT_LEN], uint8_t *B, size_t *B_len);

/* Verifies the client's public value A and proof M1. On success returns 0 and
 * writes the server proof M2 and the session key K. */
int srp_server_verify(srp_server_t *srp, const uint8_t *A, size_t A_len,
                      const uint8_t *M1, size_t M1_len,
                      uint8_t M2[SRP_PROOF_LEN], uint8_t K[SRP_SESSION_KEY_LEN]);

void srp_server_free(srp_server_t *srp);

#endif
