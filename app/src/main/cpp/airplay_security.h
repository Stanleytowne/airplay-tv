#ifndef AIRPLAY_SECURITY_H
#define AIRPLAY_SECURITY_H

#include <errno.h>
#include <math.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <time.h>

static inline uint64_t airplay_monotonic_seconds(void) {
    struct timespec now;
    return clock_gettime(CLOCK_MONOTONIC, &now) == 0 ? (uint64_t)now.tv_sec : 0;
}

/* Parse a complete finite query value, never a partial number or a null pointer. */
static inline bool airplay_query_number(const char *url, const char *key,
                                       double minimum, double maximum, double *result) {
    const char *query = strchr(url, '?');
    bool found = false;
    if (!query) return false;
    for (const char *part = query + 1; *part;) {
        const char *next = strchr(part, '&');
        const char *end = next ? next : part + strlen(part);
        const char *equals = memchr(part, '=', (size_t)(end - part));
        if (equals && (size_t)(equals - part) == strlen(key) &&
            !strncmp(part, key, (size_t)(equals - part))) {
            if (found || equals + 1 == end) return false;
            errno = 0;
            char *number_end = NULL;
            double value = strtod(equals + 1, &number_end);
            if (number_end != end || errno == ERANGE || !isfinite(value) ||
                value < minimum || value > maximum) return false;
            *result = value;
            found = true;
        }
        if (!next) break;
        part = next + 1;
    }
    return found;
}

static inline bool airplay_is_loopback(const unsigned char *address, int length) {
    static const unsigned char v6_loopback[16] = {0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1};
    static const unsigned char mapped_prefix[12] = {0,0,0,0,0,0,0,0,0,0,255,255};
    if (length == 4) return address[0] == 127;
    return length == 16 && (!memcmp(address, v6_loopback, 16) ||
        (!memcmp(address, mapped_prefix, 12) && address[12] == 127));
}

static inline bool airplay_same_peer(const unsigned char *a, int a_len, unsigned a_zone,
                                     const unsigned char *b, int b_len, unsigned b_zone) {
    static const unsigned char mapped_prefix[12] = {0,0,0,0,0,0,0,0,0,0,255,255};
    if (a_len == 16 && !memcmp(a, mapped_prefix, 12)) { a += 12; a_len = 4; }
    if (b_len == 16 && !memcmp(b, mapped_prefix, 12)) { b += 12; b_len = 4; }
    return a_len == b_len && (a_len == 4 || (a_len == 16 && a_zone == b_zone)) &&
           !memcmp(a, b, (size_t)a_len);
}

#endif
