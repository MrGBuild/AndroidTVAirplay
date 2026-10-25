Source: https://github.com/juhovh/shairplay
Commit: 096b61ad14c90169f438e690d096e3fcf87e504e

The unused playfair/ directory is omitted. Android TV password adaptations modify
lib/digest.c, lib/digest.h and lib/raop.c: cryptographically random per-connection
nonces with failure handling, safe empty digest fields, and fixed-size digest
comparison. Modified sections are marked in the source. Other vendored files
are unchanged.
CMakeLists.txt and the Android JNI bridge are maintained in the parent directory.
DNS-SD is provided by Android NsdManager; upstream dnssd.c is not compiled.
The library is dynamically linked as libshairplay.so (LGPL-2.1-or-later).
