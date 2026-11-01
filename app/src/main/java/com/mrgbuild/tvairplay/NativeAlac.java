package com.mrgbuild.tvairplay;
final class NativeAlac {
    static { System.loadLibrary("tvcodec"); }
    static native long create(int rate, int frames);
    static native byte[] decode(long decoder, byte[] data);
    static native void destroy(long decoder);
}
