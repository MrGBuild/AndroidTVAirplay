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

## Receiver name and video resolution (version 0.3.0)

Select **Change receiver name** on the TV, enter a name such as **Living Room**,
and save. Video discovery uses **Living Room**; audio discovery uses **Living Room
Audio**. The name is stored locally and restored after reopening the app. Renaming
disconnects active senders and refreshes discovery; your password stays unchanged.
Refresh the receiver list on your sender if it still shows the old name. Android
may append a number if another receiver already uses the same name. Use a short
name; blank names, control characters and names over 40 UTF-8 bytes are rejected
to fit the audio protocol's discovery label.

The video player has no fixed 720p/1080p resolution cap. Playback depends on the
source stream, codec, device decoder, TV output mode and network. A Chromecast
with Google TV (4K) may play compatible 4K sources; the HD model outputs up to
1080p. Adaptive streams may choose lower quality based on bandwidth and device
capabilities. The app does not upscale source detail or implement screen
mirroring. 4K playback and sender interoperability have not been device-tested.

## Receiver password (version 0.2.0)

On first launch, choose and confirm a password on the TV. Both network receivers
stay closed until a password has been saved. There is no default password and no
password-free mode. Use 8–64 letters, numbers or symbols without spaces; a long,
unique password is recommended. Compatible senders prompt for it when connecting.

**Set / change password** on the TV changes the password and disconnects existing
senders. Previously queued remote commands are discarded. You do not need to know
the old password to reset it using the TV's physical remote. Local **Open media URL**
playback remains available even before setting a receiver password.

The password is stored on the TV using AES-GCM encryption with an Android Keystore
key. It is not stored in source code, advertised via discovery, logged, or sent to
an app backend. If it cannot be loaded, the network receivers stay closed.

This is **legacy AirPlay Digest MD5 password authentication**, not modern Apple
device pairing or a rotating on-screen PIN. Discovery stays visible, but playback
control and status endpoints require authentication. Each connection uses a fresh
random nonce. Discovery advertises that a password is required for audio and video.
Legacy authentication still needs testing with real iPhone senders.

Continue to use a trusted LAN; legacy Digest can allow offline password guessing
from captured exchanges. Password protection does not encrypt HTTP media URLs or
replace HTTPS. Do not expose the receiver ports to the Internet.

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
3. Open **TV AirPlay** from the TV launcher and set a receiver password. Keep the TV and sender on the same
   trusted network. Guest Wi-Fi/client isolation can prevent discovery.
4. Select **Open media URL**, enter a direct video/audio URL, and select **Play**.
   The remote controls playback; Back stops playback and returns to the receiver.
5. For a legacy receiver smoke test from another computer, replace `TV_IP` and
   the sample URL with reachable addresses:

```sh
curl http://TV_IP:7000/server-info
curl --digest --user AirPlay -X POST http://TV_IP:7000/play \
  -H 'Content-Type: text/parameters' \
  --data-binary 'Content-Location: https://example.com/video.mp4'
curl --digest --user AirPlay -X POST 'http://TV_IP:7000/rate?value=0'
curl --digest --user AirPlay -X POST 'http://TV_IP:7000/rate?value=1'
curl --digest --user AirPlay http://TV_IP:7000/playback-info
curl --digest --user AirPlay -X POST http://TV_IP:7000/stop
```

The example.com media path is a placeholder; it is not a playable sample.
`curl --user AirPlay` prompts for the receiver password without putting it into
the command line. `/server-info` is the only unauthenticated video endpoint.

## Validation and next milestones

The JVM tests exercise real TCP connections, binary/text media requests, playback
controls/state, invalid inputs, missing/incorrect/correct passwords, method/URL
binding, cross-connection replay rejection and unsupported operations. They do not verify
Android media decoding, multicast discovery, or interoperability with Apple devices.
An APK build and lint also do not prove these behaviors work on a TV.

The native smoke test exercises a real RTSP session, RSA challenge response,
RSA-encrypted session key, AES-encrypted UDP ALAC audio, exact PCM sample output,
volume control and teardown in baseline and password-protected sessions. The
protected session also verifies that missing/incorrect credentials cannot start,
change or stop playback. To run on Linux with CMake, a C compiler, Python 3
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
Password adaptations to upstream Digest nonce generation/validation are documented
in `app/src/main/cpp/shairplay/UPSTREAM.md` and marked in the modified source files.
