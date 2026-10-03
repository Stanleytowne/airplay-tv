#ifndef AIRPLAY_IDENTITY_H
#define AIRPLAY_IDENTITY_H

#include <openssl/sha.h>
#include <stddef.h>
#include <stdio.h>

/* Stable UUIDv8 derived from our per-install receiver address. This is a public
 * discovery identifier, not an authentication secret. Both Bonjour and /info
 * must report the same value instead of UxPlay's shared example UUID. */
static inline void airplay_receiver_uuid(const char *address, size_t length, char out[37]) {
    unsigned char hash[SHA256_DIGEST_LENGTH];
    SHA256((const unsigned char *) address, length, hash);
    hash[6] = (hash[6] & 0x0f) | 0x80;
    hash[8] = (hash[8] & 0x3f) | 0x80;
    snprintf(out, 37,
             "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
             hash[0], hash[1], hash[2], hash[3], hash[4], hash[5], hash[6], hash[7],
             hash[8], hash[9], hash[10], hash[11], hash[12], hash[13], hash[14], hash[15]);
}

#endif
