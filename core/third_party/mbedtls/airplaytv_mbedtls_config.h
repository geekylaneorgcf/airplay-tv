/*
 * mbedTLS configuration for AirPlay TV: only the primitives the AirPlay
 * protocol needs (AES-CTR/CBC/GCM, SHA-1, SHA-512, MD5, big numbers).
 */

#ifndef AIRPLAYTV_MBEDTLS_CONFIG_H
#define AIRPLAYTV_MBEDTLS_CONFIG_H

#define MBEDTLS_HAVE_ASM
#define MBEDTLS_AES_C
#define MBEDTLS_AES_ROM_TABLES
#define MBEDTLS_AESCE_C
#define MBEDTLS_AESNI_C
#define MBEDTLS_CIPHER_MODE_CBC
#define MBEDTLS_CIPHER_MODE_CTR
#define MBEDTLS_GCM_C
#define MBEDTLS_SHA1_C
#define MBEDTLS_SHA512_C
#define MBEDTLS_MD5_C
#define MBEDTLS_BIGNUM_C

#endif
