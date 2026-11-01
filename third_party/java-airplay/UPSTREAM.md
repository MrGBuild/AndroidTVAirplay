Upstream: https://github.com/serezhka/java-airplay
Commit: 59816100c87212353a4191a922aa97226af2cccd
License: MIT (see LICENSE). This license applies to these vendor files, not the app's own code.

Android adaptations: portable bounded NIO event loops; startup/lifecycle safeguards;
Android-compatible stream/hex APIs; stable identity, receiver profile and password
hooks; packet-size checks. Advertisement and Android playback are supplied by the
app. Desktop entrypoints and desktop discovery are excluded. All modifications
are available in this repository's Git history against the pinned upstream.

The upstream org.whispersystems Curve25519 dependency (GPLv3) is excluded. X25519
uses the Bouncy Castle lightweight API instead. Public pairing identity is persisted
by the Android adapter. Password gating, timing replies and actual network discovery
checks are supplied by the app. Diagnostic payload dumping is not enabled.
