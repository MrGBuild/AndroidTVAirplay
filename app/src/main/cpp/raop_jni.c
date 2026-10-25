#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "raop.h"

typedef struct {
    JavaVM *vm;
    jobject owner;
    jmethodID create;
    jmethodID write, destroy, flush, volume;
    raop_t *raop;
    unsigned short port;
} receiver_t;

static JNIEnv *environment(receiver_t *r, int *attached) {
    JNIEnv *env = NULL; *attached = 0;
    if ((*r->vm)->GetEnv(r->vm, (void **)&env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        if ((*r->vm)->AttachCurrentThread(r->vm, &env, NULL) != JNI_OK) return NULL;
        *attached = 1;
    }
    return env;
}
static void finished(receiver_t *r, JNIEnv *env, int attached) {
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (attached) (*r->vm)->DetachCurrentThread(r->vm);
}
static void *audio_init(void *cls, int bits, int channels, int rate) {
    receiver_t *r = cls; int attached; JNIEnv *env = environment(r, &attached);
    if (!env) return NULL;
    jobject local = (*env)->CallObjectMethod(env, r->owner, r->create, bits, channels, rate);
    jobject session = NULL;
    if (!(*env)->ExceptionCheck(env) && local) {
        session = (*env)->NewGlobalRef(env, local); (*env)->DeleteLocalRef(env, local);
    }
    finished(r, env, attached); return session;
}
static void audio_process(void *cls, void *session, const void *buffer, int length) {
    if (!session || length <= 0) return;
    receiver_t *r = cls; int attached; JNIEnv *env = environment(r, &attached);
    if (!env) return;
    jbyteArray bytes = (*env)->NewByteArray(env, length);
    if (bytes) {
        (*env)->SetByteArrayRegion(env, bytes, 0, length, buffer);
        if (!(*env)->ExceptionCheck(env)) (*env)->CallVoidMethod(env, session, r->write, bytes);
        (*env)->DeleteLocalRef(env, bytes);
    }
    finished(r, env, attached);
}
static void audio_destroy(void *cls, void *session) {
    if (!session) return;
    receiver_t *r = cls; int attached; JNIEnv *env = environment(r, &attached);
    if (!env) return;
    (*env)->CallVoidMethod(env, session, r->destroy);
    (*env)->DeleteGlobalRef(env, session); finished(r, env, attached);
}
static void audio_flush(void *cls, void *session) {
    if (!session) return;
    receiver_t *r = cls; int attached; JNIEnv *env = environment(r, &attached);
    if (!env) return;
    (*env)->CallVoidMethod(env, session, r->flush); finished(r, env, attached);
}
static void audio_volume(void *cls, void *session, float volume) {
    if (!session) return;
    receiver_t *r = cls; int attached; JNIEnv *env = environment(r, &attached);
    if (!env) return;
    (*env)->CallVoidMethod(env, session, r->volume, volume); finished(r, env, attached);
}
JNIEXPORT jlong JNICALL Java_com_mrgbuild_tvairplay_NativeRaop_startNative(JNIEnv *env, jobject owner, jbyteArray hw, jstring pem) {
    if ((*env)->GetArrayLength(env, hw) != 6) return 0;
    receiver_t *r = calloc(1, sizeof(*r)); if (!r) return 0;
    (*env)->GetJavaVM(env, &r->vm); r->owner = (*env)->NewGlobalRef(env, owner);
    jclass cls = (*env)->GetObjectClass(env, owner);
    r->create = (*env)->GetMethodID(env, cls, "createSession", "(III)Lcom/mrgbuild/tvairplay/NativeRaop$Session;");
    (*env)->DeleteLocalRef(env, cls);
    cls = (*env)->FindClass(env, "com/mrgbuild/tvairplay/NativeRaop$Session");
    r->write = (*env)->GetMethodID(env, cls, "write", "([B)V");
    r->destroy = (*env)->GetMethodID(env, cls, "close", "()V");
    r->flush = (*env)->GetMethodID(env, cls, "flush", "()V");
    r->volume = (*env)->GetMethodID(env, cls, "volume", "(F)V");
    (*env)->DeleteLocalRef(env, cls);
    if ((*env)->ExceptionCheck(env)) goto fail;
    const char *key = (*env)->GetStringUTFChars(env, pem, NULL);
    if (!key) goto fail;
    int error = 0; raop_callbacks_t callbacks = {0};
    callbacks.cls = r; callbacks.audio_init = audio_init; callbacks.audio_process = audio_process;
    callbacks.audio_destroy = audio_destroy; callbacks.audio_flush = audio_flush; callbacks.audio_set_volume = audio_volume;
    r->raop = raop_init(1, &callbacks, key, &error);
    (*env)->ReleaseStringUTFChars(env, pem, key);
    if (!r->raop) goto fail;
    // Upstream debug logs include session material; keep all logging disabled.
    raop_set_log_level(r->raop, RAOP_LOG_EMERG);
    char bytes[6]; (*env)->GetByteArrayRegion(env, hw, 0, 6, (jbyte *)bytes);
    r->port = 5000;
    if (raop_start(r->raop, &r->port, bytes, 6, NULL) < 0) goto fail;
    return (jlong)(intptr_t)r;
fail:
    if (r->raop) raop_destroy(r->raop);
    if (r->owner) (*env)->DeleteGlobalRef(env, r->owner);
    free(r); return 0;
}
JNIEXPORT jint JNICALL Java_com_mrgbuild_tvairplay_NativeRaop_portNative(JNIEnv *env, jobject owner, jlong handle) {
    receiver_t *r = (receiver_t *)(intptr_t)handle; return r ? r->port : 0;
}
JNIEXPORT void JNICALL Java_com_mrgbuild_tvairplay_NativeRaop_stopNative(JNIEnv *env, jobject owner, jlong handle) {
    receiver_t *r = (receiver_t *)(intptr_t)handle; if (!r) return;
    raop_destroy(r->raop);
    (*env)->DeleteGlobalRef(env, r->owner); free(r);
}
