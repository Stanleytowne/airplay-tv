# AirPlay TV Home

Personal home build based on `flymop/airplay-tv` commit `a4003d82f713c5b04b623f74ad0cce4f21a46bc8`.
The original GPLv3 license and attribution remain in effect. This is an independent modified build.

## Changes

- Separate package `com.flymop.airplaytv.home`; does not overwrite the upstream app.
- PIN pairing is enabled on a fresh installation. The default receiver name is `小米电视 AirPlay`.
- Remote Back/Escape closes the PIN prompt before affecting settings or playback. Its stored UI state
  is cleared so it stays closed when the activity rebinds, and a later PIN request can show it again.
  Successful PIN pairing also closes the prompt immediately.
- Authenticated client public keys are stored in private app preferences and survive a service/device restart.
  Settings includes **Forget paired devices**, which restarts the receiver and revokes existing sessions.
  PIN values and network addresses are not saved. The trust list is limited to 64 public keys.
- Pair verification checks both a trusted public key and the sender's signature. PIN setup checks binary field
  lengths and exchange order, uses a fresh random PIN for a two-minute pairing window, and has no fixed fallback PIN.
  The PIN remains stable across Apple's parallel/retried connections; eight failed attempts close the window until expiry.
- In PIN mode, HTTP video control must come from a sender address with a live authenticated channel.
  The HLS playlist proxy only accepts actual loopback connections. An unauthenticated discovery request
  cannot take over a playing session.
- Malformed seek/speed parameters return 400. Request URL/header/body sizes are bounded before buffering.
  Fragmented HTTP/RTSP protocol identification does not read beyond input boundaries.
- Media URLs are omitted from app playback logs; Android backup is disabled for local pairing material.
- OpenSSL 3.4.4 and the Gradle 8.7 distribution are checked against their upstream SHA-256 digests.
  Mandatory native patches fail the build if they cannot be applied.
- Release builds require an explicitly supplied persistent signing key; they never silently use debug signing.

## Compatibility boundary

Android 8.0/API 26 or newer is required, as in the original project. ARM32 and ARM64 packages are supported.
Screen mirroring and audio are the primary PIN-authenticated paths. Some Apple apps send standalone HTTP
video without first creating an authenticated channel: these requests are rejected in PIN mode. Start a
paired mirroring/audio session first, or use screen mirroring for that content. The separate HTTP channel
is associated by the live sender's network address, not by end-to-end HTTP encryption. Use a trusted home LAN.
Apple DRM-protected video remains subject to the original receiver's limitations.

Native regression tests are not a substitute for an iPhone/iPad/Mac and Android TV compatibility test.
No claim is made that all third-party native code has been audited or that every possible defect is fixed.

## Playback stability (1.1.3)

- Video output drains on a dedicated thread, so a decoded frame no longer waits for the next network packet.
  Timestamp pacing uses a 40 ms initial cushion, rebases across discontinuities and caps future waits.
  The SurfaceTexture frame notification uses its own handler instead of depending on the UI event queue.
- The HUD separates receive rate from unique textures submitted to the display. The output rate is not
  a claim about the panel's scan-out rate; SurfaceFlinger presentation timestamps remain the system check.
- Adaptive audio starts with at least 40 ms of jitter headroom and targets the 99th percentile by default.
  Automatic low-latency output starts at three bursts and increases on AAudio underruns, bounded at
  100 ms or the device buffer capacity. Explicit fixed audio buffer settings are respected.
- These settings favor steady home video playback at the cost of a small amount of additional delay.
  They cannot remove Wi-Fi congestion or an overloaded TV's scheduling stalls.
- Read non-content performance counters with
  `adb shell dumpsys activity service com.flymop.airplaytv.home/com.flymop.airplaytv.service.AirPlayService`.

Playback checks: `./gradlew testReleaseUnitTest` exercises pacing, clock discontinuities and display metrics.
`tests/audio_cushion.cpp` exercises adaptive headroom and startup buffering under ASan/UBSan.
API references: [MediaCodec](https://developer.android.com/reference/android/media/MediaCodec),
[SurfaceTexture](https://developer.android.com/reference/android/graphics/SurfaceTexture), and
[Oboe buffer tuning](https://google.github.io/oboe/classoboe_1_1_latency_tuner.html).

## Video edge correction (1.1.4)

- Use high precision texture coordinates when supported, with matching vertex/fragment qualifiers.
  Keep sampling inside the transformed crop, guarding one outer texel plus the bilinear filter footprint.
  This replicates the nearest safe edge sample without enlarging the picture.
- Read actual decoder output dimensions on format changes. Refresh the EGL viewport after surface
  resizes and clear the full color buffer so uncovered pixels cannot retain stale content.
- Crop/flip/rotation and edge filtering regression checks are included in `TextureBoundsTest`.

## Discovery identity (1.1.5)

The receiver always uses a persisted random locally administered address, rather than the
TV's physical Wi-Fi MAC. The built-in Xiaomi receiver can advertise that same physical MAC
concurrently. Home also replaces the shared example pairing UUID with a stable UUIDv8
derived from its receiver address; Bonjour and `/info` report the same identity.
Existing private/public keys and trusted clients are preserved. Apple devices may request
one additional PIN pairing after this identity migration. The native regression suite checks
identity consistency, restart stability and separation of different receiver addresses.

## Build

### Playback recovery (1.1.8)

The experimental 1.1.6 shared playback clock, timestamp conversion and RGBA8888 selection were
withdrawn after the TV froze on a single picture. Original timestamp forwarding and video pacing
are restored. Adaptive decoders now reserve the advertised landscape dimensions at startup instead
of using only the first portrait picture's bounds. This avoids exceeding the configured size when
the phone changes from portrait Control Center to landscape video. Audio's adaptive jitter cushion
is capped at 200 ms, and discontinuities clear old jitter history; explicit fixed cushions are unchanged.
The cushion is not the total audio latency. Complete lip-sync and intermittent edge flicker remain
subject to real-device verification.

Initialize the pinned submodules, use JDK 17 and Android SDK 34, NDK 27.0.12077973 and CMake 3.22.1.
Set `sdk.dir` in your ignored `local.properties`, or configure `ANDROID_HOME`.

Keep a signing properties file **outside the repository**, with mode 0600:

```properties
storeFile=/absolute/path/to/your-private-release.p12
storePassword=YOUR_PASSWORD
keyAlias=YOUR_ALIAS
keyPassword=YOUR_PASSWORD
```

Then build:

```sh
git submodule update --init --recursive
export AIRPLAY_SIGNING_PROPERTIES=/absolute/path/to/signing.properties
./gradlew assembleRelease -PairplayAbis=armeabi-v7a,arm64-v8a
```

Keep the signing key and passwords backed up privately; future updates must use the same key.
CI expects `AIRPLAY_KEYSTORE_B64`, `AIRPLAY_STORE_PASSWORD`, `AIRPLAY_KEY_ALIAS`, and `AIRPLAY_KEY_PASSWORD` secrets.

## Verify

The native tests use the actual patched request parser, dispatch code, numeric controls and pairing handlers,
with ASan and UBSan. They do not open a listening port. Clang and OpenSSL development headers are required.

```sh
./gradlew applyUxplayPatches
# macOS with Homebrew OpenSSL uses the default prefix; on Linux use OPENSSL_PREFIX=/usr
python3 tests/run_native_security.py
```

Install the matching signed APK using ADB, or sideload the ARM universal APK on the TV.
Select `小米电视 AirPlay` when multiple receivers appear. The built-in receiver can keep running;
its identity is separate from Home's identity.
