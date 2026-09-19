#include <jni.h>
#include <android/native_window_jni.h>
#include <pthread.h>
#include <stdlib.h>

#include "vnc_client.h"

/*
 * Function names below must match MainActivity.java exactly:
 *   package com.IvyVNC;  class MainActivity;  method nativeXxx
 * -> Java_com_IvyVNC_MainActivity_nativeXxx
 * If you rename the package or class, these names must change too --
 * javac won't catch the mismatch, it'll just fail at System.loadLibrary
 * time with an UnsatisfiedLinkError.
 */

/* Cached once in JNI_OnLoad so jni_notify_status() (called from the
 * background vnc_client.c thread, which the JVM doesn't know about) can
 * attach itself and call back into Java. */
static JavaVM *g_vm = NULL;

/* Global ref to the MainActivity instance, set via nativeSetContext().
 * Needed because our native methods are all `static` -- there's no
 * implicit `this` to call back onto, so Java has to hand us one
 * explicitly. */
static jobject g_activity = NULL;
static jmethodID g_status_method = NULL; /* cached lazily on first use */

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    g_vm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL
Java_com_IvyVNC_MainActivity_nativeSetContext(JNIEnv *env, jclass clazz, jobject activity) {
    (void) clazz;
    if (g_activity) {
        (*env)->DeleteGlobalRef(env, g_activity);
    }
    g_activity = (*env)->NewGlobalRef(env, activity);
    g_status_method = NULL; /* re-resolve against the new instance */
}

/* Called from vnc_client.c, potentially from the background connection
 * thread -- which the JVM has never seen, so we must attach it before
 * making any JNI calls, and detach afterward since we don't own this
 * thread's lifetime. */
void jni_notify_status(int status, const char *message) {
    if (!g_vm || !g_activity) return;

    JNIEnv *env = NULL;
    int did_attach = 0;
    int get_env_result = (*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6);
    if (get_env_result == JNI_EDETACHED) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != 0) {
            return; /* couldn't attach, give up quietly -- this is best-effort UI feedback */
        }
        did_attach = 1;
    } else if (get_env_result != JNI_OK) {
        return;
    }

    if (!g_status_method) {
        jclass clazz = (*env)->GetObjectClass(env, g_activity);
        g_status_method = (*env)->GetMethodID(env, clazz, "onNativeStatus",
                                               "(ILjava/lang/String;)V");
        (*env)->DeleteLocalRef(env, clazz);
    }

    if (g_status_method) {
        jstring jmessage = (*env)->NewStringUTF(env, message);
        (*env)->CallVoidMethod(env, g_activity, g_status_method, (jint) status, jmessage);
        (*env)->DeleteLocalRef(env, jmessage);
    }

    if (did_attach) {
        (*g_vm)->DetachCurrentThread(g_vm);
    }
}

JNIEXPORT void JNICALL
Java_com_IvyVNC_MainActivity_nativeSurfaceCreated(JNIEnv *env, jclass clazz, jobject surface) {
    (void) clazz;
    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    vncclient_set_surface(window); /* vnc_client now owns this reference */
}

JNIEXPORT void JNICALL
Java_com_IvyVNC_MainActivity_nativeSurfaceChanged(JNIEnv *env, jclass clazz, jobject surface,
                                                   jint format, jint width, jint height) {
    (void) clazz; (void) format; (void) width; (void) height;
    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    vncclient_set_surface(window);
}

JNIEXPORT void JNICALL
Java_com_IvyVNC_MainActivity_nativeSurfaceDestroyed(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    vncclient_surface_destroyed();
}

JNIEXPORT void JNICALL
Java_com_IvyVNC_MainActivity_nativeCursorDelta(JNIEnv *env, jclass clazz,
                                                jfloat dx, jfloat dy) {
    (void) env; (void) clazz;
    vncclient_cursor_delta(dx, dy);
}

JNIEXPORT void JNICALL
Java_com_IvyVNC_MainActivity_nativeCursorTap(JNIEnv *env, jclass clazz, jint button) {
    (void) env; (void) clazz;
    vncclient_cursor_tap(button);
}

JNIEXPORT void JNICALL
Java_com_IvyVNC_MainActivity_nativeCursorButton(JNIEnv *env, jclass clazz,
                                                 jint button, jboolean down) {
    (void) env; (void) clazz;
    vncclient_cursor_button(button, down ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_com_IvyVNC_MainActivity_nativeCursorScroll(JNIEnv *env, jclass clazz, jint ticks) {
    (void) env; (void) clazz;
    vncclient_cursor_scroll(ticks);
}

JNIEXPORT void JNICALL
Java_com_IvyVNC_MainActivity_nativeKeyEvent(JNIEnv *env, jclass clazz,
                                             jint keysym, jboolean down) {
    (void) env; (void) clazz;
    vncclient_key_event((uint32_t) keysym, down ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_com_IvyVNC_MainActivity_nativeConnect(JNIEnv *env, jclass clazz,
                                            jstring jhost, jint port, jstring jpassword) {
    (void) clazz;
    const char *host = (*env)->GetStringUTFChars(env, jhost, NULL);
    const char *password = jpassword ? (*env)->GetStringUTFChars(env, jpassword, NULL) : NULL;

    vncclient_connect(host, port, password);

    (*env)->ReleaseStringUTFChars(env, jhost, host);
    if (jpassword) {
        (*env)->ReleaseStringUTFChars(env, jpassword, password);
    }
}

JNIEXPORT jstring JNICALL
Java_com_IvyVNC_MainActivity_nativeGetSessionInfo(JNIEnv *env, jclass clazz) {
    (void) clazz;
    char buf[256];
    vncclient_get_session_info(buf, sizeof(buf));
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT void JNICALL
Java_com_IvyVNC_MainActivity_nativeDisconnect(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    vncclient_disconnect();
}

JNIEXPORT void JNICALL
Java_com_IvyVNC_MainActivity_nativeSetRenderOptions(JNIEnv *env, jclass clazz,
                                                     jint colorMode, jint scalingMode, jint renderingMode) {
    (void) env; (void) clazz;
    vncclient_set_render_options(colorMode, scalingMode, renderingMode);
}

JNIEXPORT void JNICALL
Java_com_IvyVNC_MainActivity_nativeCaptureThumbnail(JNIEnv *env, jclass clazz,
                                                     jstring jpath, jint maxDim) {
    (void) clazz;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    vncclient_capture_thumbnail(path, maxDim);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
}
