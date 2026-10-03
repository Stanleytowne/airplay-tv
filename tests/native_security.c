/* Regression checks execute the patched protocol handlers without opening a listening port. */
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "raop.c"
#include <srp.h>
#include "android_dnssd_shim.h"

static int control_calls;
static void test_log(void *cls, int level, const char *message) { fprintf(stderr, "%s\n", message); }
static void on_control(void *cls, float value) { control_calls++; }
static void on_stop(void *cls) { control_calls++; }
static void on_reset(void *cls, reset_type_t type) {}
static void on_audio(void *cls, raop_ntp_t *ntp, audio_decode_struct *d) {}
static void on_video(void *cls, raop_ntp_t *ntp, video_decode_struct *d) {}
static double on_initial_volume(void *cls) { return 0.0; }

static http_request_t *parse(const char *message, int fragment) {
    http_request_t *r = http_request_init();
    for (size_t pos = 0; pos < strlen(message);) {
        int n = fragment ? 1 : (int)(strlen(message) - pos);
        http_request_add_data(r, message + pos, n);
        pos += n;
        if (http_request_has_error(r)) break;
    }
    return r;
}

static void check_response(raop_conn_t *conn, const char *request, const char *status, int calls) {
    http_request_t *r = parse(request, 1);
    assert(!http_request_has_error(r) && http_request_is_complete(r));
    http_response_t *response = NULL;
    int before = control_calls;
    conn_request(conn, r, &response);
    assert(response);
    int len = 0;
    const char *data = http_response_get_data(response, &len);
    /* Early access denials have not yet been serialized by conn_request. */
    if (!data) {
        http_response_finish(response, NULL, 0);
        data = http_response_get_data(response, &len);
    }
    if (!data || strncmp(data, status, strlen(status))) {
        fprintf(stderr, "Expected %s, got %.*s\n", status, len, data ? data : "");
        abort();
    }
    assert(control_calls - before == calls);
    http_response_destroy(response);
    http_request_destroy(r);
}

static bool trusted_key_enabled;
static char *trusted_key;
static char displayed_pin[5];
static int registered_clients;
static void capture_pin(void *cls, char *pin) { snprintf(displayed_pin, sizeof(displayed_pin), "%s", pin); }
static void register_trusted(void *cls, const char *id, const char *key, const char *name) {
    assert(key && strlen(key) == 44);
    registered_clients++;
}
static bool check_trusted(void *cls, const char *key) {
    return trusted_key_enabled && trusted_key && !strcmp(key, trusted_key);
}

static http_request_t *binary_request(const char *path, const unsigned char *body, int len,
                                     const char *content_type) {
    char header[512];
    snprintf(header, sizeof(header), "POST %s RTSP/1.0\r\nCSeq: 1\r\nContent-Type: %s\r\nContent-Length: %d\r\n\r\n",
             path, content_type, len);
    http_request_t *r = http_request_init();
    http_request_add_data(r, header, (int)strlen(header));
    http_request_add_data(r, (const char *)body, len);
    assert(!http_request_has_error(r) && http_request_is_complete(r));
    return r;
}

static plist_t exchange_pin(raop_conn_t *conn, plist_t body, const char *status) {
    char *bytes = NULL; uint32_t size = 0;
    plist_to_bin(body, &bytes, &size);
    http_request_t *request = binary_request("/pair-setup-pin", (unsigned char *)bytes, size,
                                           "application/x-apple-binary-plist");
    http_response_t *response = NULL;
    conn_request(conn, request, &response);
    assert(response);
    int len = 0;
    const char *wire = http_response_get_data(response, &len);
    assert(wire && !strncmp(wire, status, strlen(status)));
    const char *payload = NULL;
    for (int i = 0; i + 4 <= len; ++i) {
        if (!memcmp(wire + i, "\r\n\r\n", 4)) { payload = wire + i + 4; break; }
    }
    assert(payload);
    plist_t result = NULL;
    if (payload < wire + len) plist_from_bin(payload, (uint32_t)(wire + len - payload), &result);
    http_response_destroy(response); http_request_destroy(request); free(bytes);
    return result;
}

static void check_pin_pairing(raop_t *raop, unsigned char *local, unsigned char *remote) {
    raop->callbacks.display_pin = capture_pin;
    raop->callbacks.register_client = register_trusted;
    const char *start = "POST /pair-pin-start RTSP/1.0\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n";
    plist_t first = plist_new_dict();
    plist_dict_set_item(first, "method", plist_new_string("pin"));
    plist_dict_set_item(first, "user", plist_new_string("test-client"));
    for (int wrong = 1; wrong >= 0; --wrong) {
        raop_conn_t *display = conn_init(raop, local, 4, remote, 4, 0);
        check_response(display, start, "RTSP/1.0 200", 0);
        unsigned pin = raop->pin;
        conn_destroy(display);
        raop_conn_t *conn = conn_init(raop, local, 4, remote, 4, 0);
        check_response(conn, start, "RTSP/1.0 200", 0);
        assert(raop->pin == pin && strlen(displayed_pin) == 4);
        char entered[5]; strcpy(entered, displayed_pin);
        if (wrong) entered[0] = entered[0] == '9' ? '0' : entered[0] + 1;
        plist_t reply = exchange_pin(conn, first, "RTSP/1.0 200");
        assert(reply);
        char *salt = NULL, *server_pk = NULL;
        uint64_t salt_len = 0, server_pk_len = 0;
        plist_get_data_val(plist_dict_get_item(reply, "salt"), &salt, &salt_len);
        plist_get_data_val(plist_dict_get_item(reply, "pk"), &server_pk, &server_pk_len);
        struct SRPUser *user = srp_user_new(SRP_SHA, SRP_NG, "test-client",
                                           (unsigned char *)entered, 4, NULL, NULL, 1);
        const unsigned char *client_pk = NULL, *proof = NULL; const char *username = NULL;
        int client_pk_len = 0, proof_len = 0;
        srp_user_start_authentication(user, &username, &client_pk, &client_pk_len);
        srp_user_process_challenge(user, (unsigned char *)salt, (int)salt_len,
                                   (unsigned char *)server_pk, (int)server_pk_len, &proof, &proof_len);
        assert(proof && proof_len == 20);
        free(salt); free(server_pk); plist_free(reply);
        plist_t second = plist_new_dict();
        plist_dict_set_item(second, "pk", plist_new_data((const char *)client_pk, client_pk_len));
        plist_dict_set_item(second, "proof", plist_new_data((const char *)proof, proof_len));
        reply = exchange_pin(conn, second, wrong ? "RTSP/1.0 470" : "RTSP/1.0 200");
        plist_free(second);
        if (wrong) {
            assert(!reply && !conn->pin_verified && registered_clients == 0);
        } else {
            char *server_proof = NULL; uint64_t server_proof_len = 0;
            plist_get_data_val(plist_dict_get_item(reply, "proof"), &server_proof, &server_proof_len);
            assert(server_proof_len == 20);
            srp_user_verify_session(user, (unsigned char *)server_proof);
            assert(srp_user_is_authenticated(user));
            free(server_proof); plist_free(reply);
            int key_len = 0;
            const unsigned char *session_key = srp_user_get_session_key(user, &key_len);
            assert(key_len == SRP_SESSION_KEY_SIZE);
            unsigned char key[64], iv[64], identity[32] = {42}, epk[32], tag[16];
            const char *salts[] = {"Pair-Setup-AES-Key", "Pair-Setup-AES-IV"};
            for (int i = 0; i < 2; ++i) {
                sha_ctx_t *hash = sha_init();
                sha_update(hash, (const unsigned char *)salts[i], (int)strlen(salts[i]));
                sha_update(hash, session_key, key_len);
                sha_final(hash, i ? iv : key, NULL); sha_destroy(hash);
            }
            iv[15]++;
            assert(gcm_encrypt(identity, 32, epk, key, iv, tag) == 32);
            plist_t third = plist_new_dict();
            plist_dict_set_item(third, "epk", plist_new_data((const char *)epk, 32));
            plist_dict_set_item(third, "authTag", plist_new_data((const char *)tag, 16));
            reply = exchange_pin(conn, third, "RTSP/1.0 200");
            assert(reply && registered_clients == 1 && raop->pin_expires_at == 0);
            char *server_epk = NULL, *server_tag = NULL; uint64_t epk_len = 0, tag_len = 0;
            plist_get_data_val(plist_dict_get_item(reply, "epk"), &server_epk, &epk_len);
            plist_get_data_val(plist_dict_get_item(reply, "authTag"), &server_tag, &tag_len);
            assert(epk_len == 32 && tag_len == 16);
            iv[15]++;
            unsigned char decrypted[32], expected[32];
            assert(gcm_decrypt((unsigned char *)server_epk, 32, decrypted, key, iv, (unsigned char *)server_tag) == 32);
            pairing_get_public_key(raop->pairing, expected);
            assert(!memcmp(decrypted, expected, 32));
            free(server_epk); free(server_tag); plist_free(reply); plist_free(third);
        }
        srp_user_delete(user); conn_destroy(conn);
    }
    raop_conn_t *conn = conn_init(raop, local, 4, remote, 4, 0);
    assert(!exchange_pin(conn, first, "RTSP/1.0 470")); /* successful window is closed */
    check_response(conn, start, "RTSP/1.0 200", 0);
    raop->pin_failures = 8;
    assert(!exchange_pin(conn, first, "RTSP/1.0 470"));
    check_response(conn, start, "RTSP/1.0 429", 0);
    raop->pin_expires_at = airplay_monotonic_seconds() - 1;
    assert(!exchange_pin(conn, first, "RTSP/1.0 470"));
    check_response(conn, start, "RTSP/1.0 200", 0);
    assert(raop->pin_failures == 0);
    conn_destroy(conn); plist_free(first);
    puts("PASS: cross-connection PIN start, stable retries, complete SRP/GCM exchange, wrong PIN rejection, expiry and attempt limit");
}

static void check_pair_verify(raop_t *raop, unsigned char *local, unsigned char *remote) {
    int unused;
    ed25519_key_t *identity = ed25519_key_generate("test-client", "", &unused);
    unsigned char identity_pk[32];
    ed25519_key_get_raw(identity_pk, identity);
    ed25519_pk_to_base64(identity_pk, &trusted_key);
    raop->callbacks.check_register = check_trusted;
    for (int mode = 0; mode < 3; ++mode) {
        raop_conn_t *conn = conn_init(raop, local, 4, remote, 4, 0);
        x25519_key_t *ephemeral = x25519_key_generate();
        unsigned char first[68] = {1, 0, 0, 0};
        x25519_key_get_raw(first + 4, ephemeral);
        memcpy(first + 36, identity_pk, 32);
        trusted_key_enabled = mode != 0;
        http_request_t *request = binary_request("/pair-verify", first, sizeof(first), "application/octet-stream");
        http_response_t *response = http_response_create();
        http_response_init(response, "RTSP/1.0", 200, "OK");
        char *data = NULL;
        int len = 0;
        raop_handler_pairverify(conn, request, response, &data, &len);
        assert(!conn->pin_verified);
        if (mode == 0) {
            assert(!conn->verify_key_allowed && !data);
        } else {
            assert(conn->verify_key_allowed && data && len == 96);
            x25519_key_t *server = x25519_key_from_raw((const unsigned char *)data);
            unsigned char secret[32], key[64], iv[64], signed_message[64], second[68] = {0};
            x25519_derive_secret(secret, ephemeral, server);
            const char *salts[] = {"Pair-Verify-AES-Key", "Pair-Verify-AES-IV"};
            for (int i = 0; i < 2; ++i) {
                sha_ctx_t *hash = sha_init();
                sha_update(hash, (const unsigned char *)salts[i], (int)strlen(salts[i]));
                sha_update(hash, secret, 32);
                sha_final(hash, i ? iv : key, NULL);
                sha_destroy(hash);
            }
            memcpy(signed_message, first + 4, 32);
            memcpy(signed_message + 32, data, 32);
            ed25519_sign(second + 4, 64, signed_message, 64, identity);
            aes_ctx_t *aes = aes_ctr_init(key, iv);
            unsigned char skip[64] = {0};
            aes_ctr_encrypt(aes, skip, skip, 64);
            aes_ctr_encrypt(aes, second + 4, second + 4, 64);
            aes_ctr_destroy(aes); x25519_key_destroy(server);
            if (mode == 2) second[10] ^= 1;
            http_request_destroy(request);
            request = binary_request("/pair-verify", second, sizeof(second), "application/octet-stream");
            free(data); data = NULL; len = 0;
            raop_handler_pairverify(conn, request, response, &data, &len);
            assert(conn->pin_verified == (mode == 1));
            assert(!conn->verify_key_allowed);
        }
        free(data); http_request_destroy(request); http_response_destroy(response);
        x25519_key_destroy(ephemeral); conn_destroy(conn);
    }
    free(trusted_key); trusted_key = NULL; ed25519_key_destroy(identity);
    raop_conn_t *conn = conn_init(raop, local, 4, remote, 4, 0);
    unsigned char zero[512] = {0};
    assert(srp_validate_proof(conn->session, raop->pairing, zero, 32, zero, 20, 64) < 0);
    assert(srp_confirm_pair_setup(conn->session, raop->pairing, zero, zero) < 0);
    conn->pairing_window = raop->pin_expires_at;
    plist_t plist = plist_new_dict();
    plist_dict_set_item(plist, "pk", plist_new_data((const char *)zero, 256));
    plist_dict_set_item(plist, "proof", plist_new_data((const char *)zero, 512));
    char *body = NULL; uint32_t body_len = 0;
    plist_to_bin(plist, &body, &body_len);
    http_request_t *request = binary_request("/pair-setup-pin", (unsigned char *)body, body_len, "application/x-apple-binary-plist");
    http_response_t *response = http_response_create();
    http_response_init(response, "RTSP/1.0", 200, "OK");
    char *data = NULL; int len = 0;
    raop_handler_pairsetup_pin(conn, request, response, &data, &len);
    assert(!data && !conn->pin_verified);
    http_response_finish(response, NULL, 0);
    assert(!strncmp(http_response_get_data(response, &len), "RTSP/1.0 470", 12));
    http_request_destroy(request); http_response_destroy(response);
    plist_free(plist); free(body); conn_destroy(conn);
    puts("PASS: registered-key signature verification, unknown key rejection, bad signature rejection, malformed/out-of-order PIN exchange");
}

static const char *airplay_txt(dnssd_t *dns, const char *key) {
    for (int i = 0; i < android_dnssd_get_airplay_txt_count(dns); i++) {
        if (!strcmp(android_dnssd_get_airplay_txt_key(dns, i), key))
            return android_dnssd_get_airplay_txt_val(dns, i);
    }
    return NULL;
}

static void check_receiver_identity(raop_t *raop, raop_conn_t *conn) {
    char first_pi[37] = {0};
    const char addresses[3][6] = {{0x0a, 1, 2, 3, 4, 5}, {0x0a, 1, 2, 3, 4, 5}, {0x0a, 1, 2, 3, 4, 6}};
    for (int i = 0; i < 3; i++) {
        int error = 0;
        dnssd_t *dns = dnssd_init("Home", 4, addresses[i], 6, 1, &error);
        assert(dns && !error);
        raop_set_dnssd(raop, dns);
        assert(!dnssd_register_airplay(dns, 7000) && !dnssd_register_raop(dns, 7000));
        const char *pi = airplay_txt(dns, "pi");
        assert(pi && strlen(pi) == 36 && strcmp(pi, AIRPLAY_PI));
        if (i == 0) strcpy(first_pi, pi);
        else assert((strcmp(first_pi, pi) == 0) == (i == 1));
        assert(!strcmp(airplay_txt(dns, "pk"), raop->pk_str));
        assert(!strcmp(airplay_txt(dns, "pw"), "true"));
        assert(!strcmp(airplay_txt(dns, "deviceid"), i == 2 ? "0a:01:02:03:04:06" : "0a:01:02:03:04:05"));
        assert(!strcmp(android_dnssd_get_raop_servname(dns), i == 2 ? "0A0102030406@Home" : "0A0102030405@Home"));

        http_request_t *request = parse("GET /info RTSP/1.0\r\nCSeq: 1\r\n\r\n", 1);
        http_response_t *response = http_response_create();
        http_response_init(response, "RTSP/1.0", 200, "OK");
        char *data = NULL; int len = 0;
        raop_handler_info(conn, request, response, &data, &len);
        plist_t info = NULL;
        plist_from_bin(data, len, &info);
        assert(info);
        const char *fields[] = {"deviceID", "macAddress", "pi"};
        for (int j = 0; j < 3; j++) {
            char *value = NULL;
            plist_get_string_val(plist_dict_get_item(info, fields[j]), &value);
            assert(value && !strcmp(value, j == 2 ? pi : airplay_txt(dns, "deviceid")));
            free(value);
        }
        plist_free(info); free(data);
        http_request_destroy(request); http_response_destroy(response);
        dnssd_destroy(dns);
        raop->dnssd = NULL;
    }
    puts("PASS: receiver identity survives restart, differs between installs, agrees across Bonjour/RAOP/info and preserves pairing key");
}

int main(void) {
    const char *valid[] = {
        "OPTIONS * RTSP/1.0\r\nCSeq: 1\r\n\r\n",
        "POST /stop HTTP/1.1\r\nX-Apple-Session-ID: test\r\nContent-Length: 0\r\n\r\n"
    };
    for (int i = 0; i < 2; i++) {
        http_request_t *r = parse(valid[i], 1);
        assert(!http_request_has_error(r) && http_request_is_complete(r));
        assert(!strcmp(http_request_get_protocol(r), i ? "HTTP/1.1" : "RTSP/1.0"));
        http_request_destroy(r);
    }
    http_request_t *large = parse("POST /play HTTP/1.1\r\nContent-Length: 8388609\r\n\r\n", 1);
    assert(http_request_has_error(large));
    http_request_destroy(large);
    char *header = malloc(17000);
    strcpy(header, "GET /info RTSP/1.0\r\nX: ");
    size_t n = strlen(header);
    memset(header + n, 'x', 16500);
    header[n + 16500] = '\0';
    large = parse(header, 0);
    assert(http_request_has_error(large));
    http_request_destroy(large); free(header);

    raop_callbacks_t callbacks = {0};
    callbacks.audio_process = on_audio; callbacks.video_process = on_video;
    callbacks.audio_set_client_volume = on_initial_volume;
    callbacks.on_video_scrub = on_control; callbacks.on_video_rate = on_control;
    callbacks.on_video_stop = on_stop; callbacks.video_reset = on_reset;
    raop_t *raop = raop_init(&callbacks);
    assert(raop);
    raop_set_log_level(raop, LOGGER_ERR);
    raop_set_log_callback(raop, test_log, NULL);
    assert(raop_init2(raop, 0, "02:00:00:00:00:01", "") == 0);
    raop->use_pin = true; raop->hls_support = true;
    unsigned char local[4] = {127, 0, 0, 1}, remote[4] = {192, 168, 31, 99};
    raop_conn_t *conn = conn_init(raop, local, 4, remote, 4, 0);
    assert(conn);
    check_receiver_identity(raop, conn);
    check_response(conn, valid[1], "HTTP/1.1 470", 0);
    check_response(conn, "GET /master.m3u8 HTTP/1.1\r\nHost: localhost:7000\r\n\r\n", "HTTP/1.1 403", 0);
    check_response(conn, valid[0], "RTSP/1.0 200", 0);
    assert(conn->connection_type == CONNECTION_TYPE_UNKNOWN);
    conn->pin_verified = true;
    const char *paths[] = {"/scrub?position=1", "/rate?value=1", "/scrub?bad", "/rate?bad",
        "/scrub?position=nan", "/rate?value=inf", "/rate?value=1oops", "/rate?value=-1",
        "/scrub?position=1&position=2", "/rate?value=17", "/rate?value=0"};
    for (int i = 0; i < 11; i++) {
        char message[512];
        snprintf(message, sizeof(message), "POST %s HTTP/1.1\r\nX-Apple-Session-ID: test\r\nContent-Length: 0\r\n\r\n", paths[i]);
        bool valid = i < 2 || i == 10;
        check_response(conn, message, valid ? "HTTP/1.1 200" : "HTTP/1.1 400", valid ? 1 : 0);
    }
    check_response(conn, "POST /stop HTTP/1.1\r\nX-Apple-Session-ID: changed\r\nContent-Length: 0\r\n\r\n", "HTTP/1.1 400", 0);
    check_response(conn, valid[1], "HTTP/1.1 200", 1);
    unsigned char mapped[16] = {0,0,0,0,0,0,0,0,0,0,255,255,192,168,31,99};
    assert(airplay_same_peer(remote, 4, 0, mapped, 16, 0));
    assert(!airplay_is_loopback(remote, 4));
    assert(airplay_is_loopback(local, 4));
    conn_destroy(conn);
    check_pin_pairing(raop, local, remote);
    check_pair_verify(raop, local, remote);
    raop_destroy(raop);
    puts("PASS: fragmented HTTP/RTSP, request limits, unauthenticated controls, local proxy, numeric validation, session binding");
    return 0;
}
