#include <jni.h>
#include <android/native_window_jni.h>
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
Java_com_IvyVNC_MainActivity_nativeTouchEvent(JNIEnv *env, jclass clazz,
                                               jint action, jfloat x, jfloat y) {
    (void) env; (void) clazz;
    vncclient_touch_event(action, x, y);
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

JNIEXPORT void JNICALL
Java_com_IvyVNC_MainActivity_nativeDisconnect(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    vncclient_disconnect();
}
