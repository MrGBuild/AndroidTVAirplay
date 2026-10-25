# TV AirPlay — early preview

Android TV app with a remote-friendly receiver screen, Media3 video playback,
a legacy RAOP audio receiver, and an experimental subset of the legacy AirPlay
media-URL protocol.

## Download the preview APK

[Download TV-AirPlay-debug.apk](https://github.com/MrGBuild/AndroidTVAirplay/raw/refs/heads/main/downloads/TV-AirPlay-debug.apk)

This is the locally built, debug-signed preview. On a PC, download the APK and
transfer it to your Chromecast with Google TV to sideload it. The matching SHA-256
is in `downloads/TV-AirPlay-debug.apk.sha256`. GitHub Actions also builds updated
APKs: open **Actions → Android → a successful run → Artifacts → tv-airplay-debug**,
download the ZIP, and extract `app-debug.apk`.

**This is a first prototype, not a complete AirPlay receiver.** It does not support
AirPlay 2, FairPlay/DRM, screen mirroring, photos, or reverse HTTP event connections.
Compatibility with iPhone senders is unverified; current iPhones may not show or
connect to it. Legacy RAOP audio is a separate receiver from direct media URLs.

## What is implemented

- Android TV launcher, D-pad focus, playback controls, full-screen player and errors.
- Direct HTTP/HTTPS MP4, HLS and audio URLs using AndroidX Media3. Actual codec
  support depends on the TV. Media URLs must be reachable from the TV itself.
- DNS-SD advertisement as `TV AirPlay (Legacy)` / `_airplay._tcp` on TCP 7000,
  advertising only the legacy video feature.
- Separate `TV AirPlay Audio` / `_raop._tcp` audio receiver on TCP 5000, using
  Shairplay for RTSP, RSA/AES decryption and ALAC decoding, bridged through JNI
  to Android AudioTrack (16-bit stereo, advertised at 44.1 kHz). RTP uses dynamic
  UDP ports or a negotiated TCP port. Audio controls come from the sender;
  the app's **Stop** disconnects the sender. Starting video disconnects audio.
- `/server-info`, `/play`, `/rate`, `/scrub`, `/stop`, `/playback-info`.
  `/play` accepts text parameters and binary plists. XML plists and unsupported
  protocol operations return explicit errors.
- Bounded requests, timeouts, bounded connection pool, HTTP/HTTPS-only media URLs,
  and no media-URL logging.
- Receiver and playback stop when the app leaves the foreground.

Use only on a trusted local network. This prototype has no pairing or password;
other devices that can reach TCP 7000 can control playback and supply media URLs.
Do not expose the port to the Internet.

## Build

Requires JDK 17 or 21, Android SDK platform 35, build tools 35.0.0 (and AGP's
default 34.0.0), platform tools, NDK 27.2.12479018 and CMake 3.22.1.
Minimum device version: Android 8.0/API 26. Native builds include arm64-v8a,
armeabi-v7a and x86_64, with flexible page-size support enabled.

Open this folder in Android Studio and let it install the SDK packages, or set
`ANDROID_HOME` to an existing SDK and run:

```sh
./gradlew :receiver:test :app:assembleDebug :app:lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. This is a development build,
not a signed store release. The Gradle wrapper pins and verifies Gradle 8.9.

Cloud toolchains are kept outside the checkout in `/workspace/toolchains`.
The saved environment setup/start instructions configure the proxy and SDK paths;
they contain no credentials. Each cloud task is already isolated; use this checkout
directly, without creating a worktree.

## Try on Android TV

1. Enable developer options and USB/network debugging on your TV. Use `adb` to
   connect using your device manufacturer's instructions.
2. Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
3. Open **TV AirPlay** from the TV launcher. Keep the TV and sender on the same
   trusted network. Guest Wi-Fi/client isolation can prevent discovery.
4. Select **Open media URL**, enter a direct video/audio URL, and select **Play**.
   The remote controls playback; Back stops playback and returns to the receiver.
5. For a legacy receiver smoke test from another computer, replace `TV_IP` and
   the sample URL with reachable addresses:

```sh
curl http://TV_IP:7000/server-info
curl -X POST http://TV_IP:7000/play \
  -H 'Content-Type: text/parameters' \
  --data-binary 'Content-Location: https://example.com/video.mp4'
curl -X POST 'http://TV_IP:7000/rate?value=0'
curl -X POST 'http://TV_IP:7000/rate?value=1'
curl http://TV_IP:7000/playback-info
curl -X POST http://TV_IP:7000/stop
```

The example.com media path is a placeholder; it is not a playable sample.

## Validation and next milestones

The JVM tests exercise real TCP connections, binary/text media requests, playback
controls/state, invalid inputs and unsupported operations. They do not verify
Android media decoding, multicast discovery, or interoperability with Apple devices.
An APK build and lint also do not prove these behaviors work on a TV.

The native smoke test exercises a real RTSP session, RSA challenge response,
RSA-encrypted session key, AES-encrypted UDP ALAC audio, exact PCM sample output,
volume control and teardown. To run on Linux with CMake, a C compiler, Python 3
and OpenSSL:

```sh
cmake -S app/src/main/cpp -B /tmp/tv-airplay-native
cmake --build /tmp/tv-airplay-native --parallel 2
python3 tests/native_audio_smoke.py /tmp/tv-airplay-native/libshairplay.so
```

Next: test real iPhone video and audio on a TV, add the video sender event channel,
and assess modern AirPlay compatibility. DRM-based services may require licensed
technology and are outside this prototype. Native Shairplay is an older codebase;
this prototype is intended for trusted LAN development, not public deployment.

## Open source

Shairplay source is vendored at commit `096b61ad14c90169f438e690d096e3fcf87e504e`
and dynamically linked as a separate `libshairplay.so` under LGPL-2.1-or-later.
Corresponding source and build instructions are included. Its ALAC, crypto and
curve implementations retain their upstream notices. The FairPlay extension is
not included or compiled. License notices and texts are bundled in the APK and
accessible through **Open-source licenses** on the receiver screen.
