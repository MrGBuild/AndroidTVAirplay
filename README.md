# TV AirPlay — receiver rebuild 0.4.3

Android TV / Chromecast with Google TV receiver. The app layer remains privately
licensed; its third-party components have their own licenses. Versions 0.1–0.3
were legacy prototypes whose tests did not establish iPhone discovery. This
version replaces the primary receiver, discovery and Android media paths.

## Install

[Download the current APK](https://github.com/MrGBuild/AndroidTVAirplay/raw/refs/heads/main/downloads/TV-AirPlay-debug.apk)

Use Downloader on Google TV, then **Install** or **Update**. Delete older downloaded
copies so you do not select a stale APK. Private repositories require GitHub sign-in.
The development APK is debug-signed; its SHA-256 is in `downloads/TV-AirPlay-debug.apk.sha256`.

1. Open **TV AirPlay** and keep it open on the TV.
2. Set a receiver password if prompted. Upgrading preserves the saved name/password.
3. Wait for **Advertising “your name”** with the TV's network address.
4. On an iPhone on the same home LAN, try the music panel's AirPlay selector or
   **Screen Mirroring**. Both protocol paths are implemented in this rebuild;
   physical iPhone-to-TV interoperability still needs testing.
5. If absent, select **Restart discovery**, then **Connection diagnostics**, and
   report the displayed status and which phone app/menu was used.

Version 0.4.3 removes an unnecessary character restriction on SRP identities.
Bounded string and binary identities are preserved exactly for password proofs,
including punctuation and Unicode. Rejected pairing requests now leave a local,
sanitized reason in connection history. The reported first-request 400 does not
include enough information to confirm its cause; this update fixes a known
rejection case and makes remaining failures diagnosable without exposing payloads.

Version 0.4.2 implements the legacy `/pair-pin-start` and `/pair-setup-pin` exchange
requested by the reported iPhone. Both endpoints remain password gated. SRP-6a
proofs and authenticated AES-GCM key exchange replace the previous 404 response;
the saved receiver password is used for pairing, with no separate generated PIN.
HTTP pairing can continue through `/pair-verify` with matching HTTP responses,
as well as the existing RTSP path.
This is legacy AirPlay pairing, not HomeKit pairing. An independent Python client
checks HTTP/RTSP proofs, receiver identity and incorrect-password rejection
(`tests/legacy_pin_interop.py`, test-only dependencies `srptools`, `cryptography`).
It connects to the synthetic `PinInteropHost` test fixture, never a user's receiver.
Physical iPhone/TV acceptance still needs retesting after this update.

Version 0.4.1 preserves the live UDP timing port in the mirroring SETUP response
and adds a bounded, memory-only connection history. It includes TCP connections,
early info probes, password challenges, response codes and protocol failures.
The old "Last sender event" field omitted early requests and was misleading.
History omits addresses, credentials, headers, bodies and media URLs. Reproduce
the failure before opening diagnostics; restarting the receiver clears history.

The first 0.4 launch changes the discovery identity once to avoid cached legacy
capabilities. The new pairing identity is then kept encrypted locally. The chosen
name and password are not reset. A successful announcement does not prove that a
router forwards multicast between its Wi-Fi clients.

## Receiver and playback

- MIT-licensed Java protocol components provide pairing, encrypted media transport,
  mirroring framing, reverse events and HLS exchanges. Android adapters provide
  H.264 output to a Surface, ALAC via Apple's Apache-2.0 decoder, and AAC/AAC-ELD
  through Android MediaCodec. Protected streaming services may refuse playback.
- Direct IPv4 mDNS is bound to the selected Wi-Fi/Ethernet address. A foreground
  Wi-Fi multicast lock permits discovery traffic. Startup waits for announcement;
  network changes trigger a restart, and the TV offers explicit restart/diagnostics.
- Discovery and `/info` use matching capabilities, public keys and names. Setup
  returns real timing/event ports. RTP sequence wrap/loss cannot permanently stall
  the audio handler. Network, codec and lifecycle work stay off the UI thread.
- Audio and video now share your chosen receiver name. Renaming restarts the
  receiver and disconnects senders. Names are saved locally, with a 40 UTF-8-byte
  limit so the required discovery identity prefix fits the DNS label.
- Password protection remains mandatory. Media setup/control uses legacy Digest
  authentication. Local playlist reads need a per-process token sent only to
  loopback addresses. This is not HomeKit pairing or Apple certification.
- Mirroring initially advertises 1920×1080 at 30 fps; the decoder follows the source
  H.264 configuration. HLS/direct video depends on source, codec, TV and bandwidth.
  A 4K Chromecast may play compatible 4K media; the HD model outputs at most 1080p.
  Physical 4K playback and buffered/multiroom AirPlay 2 are not validated/supported.
- A browser at the displayed receiver address gets an explanatory status message.
  It is not a web-based media upload UI. Leaving the TV app stops its receivers.

## Password and privacy

Use **Set / change password** on the TV: 8–64 printable characters without spaces.
Changing it disconnects senders and invalidates queued commands. Passwords and
pairing seeds use AES-GCM with Android Keystore keys; failure to read them keeps
network receivers closed. There is no password-free network mode.

There are no ads, analytics, diagnostic uploads, recording feature or app backend.
Diagnostics stay local and show the LAN address and coarse protocol status, not
passwords, private keys, request bodies or media URLs. Discovery broadcasts the
chosen name and public receiver identity on the LAN. Playing an online URL contacts
its host, which can see the network IP address. Use a trusted LAN: legacy Digest
permits offline guessing of weak passwords from captured exchanges, and password
protection does not encrypt plain HTTP media.

## Build and validation

Cloud tasks are already isolated; use the existing checkout without creating a
worktree unless explicitly requested. Requirements: full JDK 17, SDK 35, build tools
34/35, NDK 27.2.12479018, CMake 3.22.1. Minimum Android API 26; native builds target
arm64-v8a, armeabi-v7a and x86_64.

```sh
./gradlew :receiver:test :airplay-engine:test :app:assembleDebug :app:lintDebug
cmake -S app/src/main/cpp -B /tmp/tv-airplay-native
cmake --build /tmp/tv-airplay-native --parallel 2
python3 tests/native_alac_smoke.py /tmp/tv-airplay-native/libtvcodec.so
```

The native test uses Python 3 and FFmpeg to compare exact stereo samples and an
actual compressed ALAC frame against a reference decode. The old LGPL engine and
`tests/native_audio_smoke.py` remain only as historical host regression fixtures;
that engine is not included or used in the Android APK.

The JVM suites exercise pairing/key exchange, framing, HTTP/RTSP, password checks,
timing, shutdown, RTP wrap and actual mDNS queries using a separate UDP socket.
They do not cross a customer's router or establish phone interoperability.

For Android runtime checks on a dedicated emulator:

```sh
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
```

Instrumentation overwrites the app settings with synthetic credentials/names; use a
dedicated test emulator, not your everyday TV installation. It exercises Keystore, the protocol
server, ALAC JNI, AAC-to-AudioTrack output and H.264-to-Surface output. A non-exported
debug-only activity hosts the codec test Surface. These are Android checks, not
physical iPhone-to-TV acceptance tests.

CI creates a disposable debug signing key. For updates over the APK linked above,
use the tracked `downloads/` build, whose signing identity is retained in the cloud
environment; a CI artifact may require a clean installation.

APK: `app/build/outputs/apk/debug/app-debug.apk`. When distributing an authorized
new build, update the tracked `downloads/` APK and checksum from verified output.
Ordinary environment setup must not modify tracked files.

## Licenses

See [LICENSE](LICENSE) for the private app layer. The receiver port is pinned in
`third_party/java-airplay/UPSTREAM.md` (MIT). Its original GPL Curve25519 dependency
is not used: pairing uses Bouncy Castle's permissively licensed X25519 code instead.
No GPLv3 receiver engine or desktop media stack is included. Apple's ALAC decoder
is Apache-2.0. The old LGPL source is retained only for historical host tests.
Third-party notices/licenses are preserved in source and the APK.
