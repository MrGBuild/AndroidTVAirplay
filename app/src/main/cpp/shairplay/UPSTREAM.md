Source: https://github.com/juhovh/shairplay
Commit: 096b61ad14c90169f438e690d096e3fcf87e504e

The lib/ and include/ files are copied unchanged, except unused playfair/ is omitted.
CMakeLists.txt and the Android JNI bridge are maintained in the parent directory.
DNS-SD is provided by Android NsdManager; upstream dnssd.c is not compiled.
The library is dynamically linked as libshairplay.so (LGPL-2.1-or-later).
