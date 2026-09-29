/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky
 *
 * Based on fairplay_playfair.c from UxPlay/RPiPlay/shairplay
 * (Copyright (C) 2011-2012 Juho Vähä-Herttua and contributors, LGPL-2.1-or-later).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

/*
 * FairPlay SAP handshake used by AirPlay senders to deliver the stream key.
 * This is the session-setup handshake of the AirPlay protocol itself; it has
 * nothing to do with content DRM and cannot decrypt protected media.
 */

#ifndef AIRPLAYTV_FAIRPLAY_H
#define AIRPLAYTV_FAIRPLAY_H

#include "common.h"

#define FAIRPLAY_SETUP_REQUEST_LEN 16
#define FAIRPLAY_SETUP_REPLY_LEN 142
#define FAIRPLAY_HANDSHAKE_REQUEST_LEN 164
#define FAIRPLAY_HANDSHAKE_REPLY_LEN 32
#define FAIRPLAY_EKEY_LEN 72

typedef struct {
    uint8_t keymsg[FAIRPLAY_HANDSHAKE_REQUEST_LEN];
    bool have_keymsg;
} fairplay_t;

void fairplay_reset(fairplay_t *fp);

/* Phase 1. Returns 0 and fills the reply, or -1 for an unsupported request. */
int fairplay_setup(fairplay_t *fp, const uint8_t *req, size_t len, uint8_t reply[FAIRPLAY_SETUP_REPLY_LEN]);

/* Phase 2. */
int fairplay_handshake(fairplay_t *fp, const uint8_t *req, size_t len, uint8_t reply[FAIRPLAY_HANDSHAKE_REPLY_LEN]);

/* Decrypts the 72-byte "ekey" from SETUP into the 16-byte AES session key. */
int fairplay_decrypt(fairplay_t *fp, const uint8_t *ekey, size_t len, uint8_t key[16]);

#endif
