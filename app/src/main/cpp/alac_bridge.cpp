#include "ALACDecoder.h"
#include "ALACBitUtilities.h"
#include <vector>
#include <cstdint>
#include <cstring>
#include <new>
#ifdef __ANDROID__
#include <jni.h>
#endif
struct Decoder { ALACDecoder codec; unsigned frames; };
static void be32(uint8_t *p, uint32_t n) { p[0]=n>>24;p[1]=n>>16;p[2]=n>>8;p[3]=n; }
extern "C" void *tv_alac_create(int rate, int frames) {
    if ((rate != 44100 && rate != 48000) || frames < 32 || frames > 4096) return nullptr;
    auto *d = new(std::nothrow) Decoder(); if (!d) return nullptr;
    uint8_t cookie[24] = {}; be32(cookie,frames);
    cookie[5]=16;cookie[6]=40;cookie[7]=10;cookie[8]=14;cookie[9]=2;cookie[11]=255;be32(cookie+20,rate);
    if (d->codec.Init(cookie,sizeof(cookie))) { delete d;return nullptr; }
    d->frames=frames; return d;
}
extern "C" void tv_alac_close(void *handle) { delete static_cast<Decoder *>(handle); }
extern "C" int tv_alac_decode(void *handle, const uint8_t *data, int length, uint8_t *output, int capacity) {
    auto *d=static_cast<Decoder *>(handle);
    if (!d || !data || length < 3 || length > 16384 || !output || capacity < int(d->frames*4)) return -1;
    // Only the advertised stereo 16-bit format is accepted. Bound partial-frame sizes.
    if ((data[0] >> 5) != 1) return -1;
    if (data[2] & 0x10) {
        if (length < 7) return -1;
        uint32_t frames=0;
        for (int bit=23;bit<55;bit++) frames=(frames<<1)|((data[bit/8]>>(7-bit%8))&1);
        if (frames == 0 || frames > d->frames) return -1;
    }
    std::vector<uint8_t> packet(length+32,0);std::memcpy(packet.data(),data,length);
    BitBuffer bits;BitBufferInit(&bits,packet.data(),length);
    uint32_t samples=0;
    int status=d->codec.Decode(&bits,output,d->frames,2,&samples);
    if (status || samples>d->frames) return -1;
    return int(samples*4);
}
#ifdef __ANDROID__
extern "C" JNIEXPORT jlong JNICALL Java_com_mrgbuild_tvairplay_NativeAlac_create(JNIEnv*,jclass,jint rate,jint frames) {
    return reinterpret_cast<intptr_t>(tv_alac_create(rate,frames));
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_mrgbuild_tvairplay_NativeAlac_decode(JNIEnv *env,jclass,jlong handle,jbyteArray input) {
    auto *d=reinterpret_cast<Decoder *>(handle);if(!d || !input)return nullptr;
    int length=env->GetArrayLength(input);if(length<3 || length>16384)return nullptr;
    std::vector<uint8_t> bytes(length),pcm(d->frames*4);
    env->GetByteArrayRegion(input,0,length,reinterpret_cast<jbyte *>(bytes.data()));
    int n=tv_alac_decode(d,bytes.data(),length,pcm.data(),pcm.size());if(n<0)return nullptr;
    jbyteArray result=env->NewByteArray(n);
    if(result)env->SetByteArrayRegion(result,0,n,reinterpret_cast<jbyte *>(pcm.data()));return result;
}
extern "C" JNIEXPORT void JNICALL Java_com_mrgbuild_tvairplay_NativeAlac_destroy(JNIEnv*,jclass,jlong handle) {tv_alac_close(reinterpret_cast<void *>(handle));}
#endif
