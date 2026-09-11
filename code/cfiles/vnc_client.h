#ifndef IVYVNC_VNC_CLIENT_H
#define IVYVNC_VNC_CLIENT_H

#include <android/native_window.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Give the VNC layer a window to draw into. Called from
 * Java_..._nativeSurfaceCreated / nativeSurfaceChanged.
 *
 * Ownership: caller (jni_bridge.c) has already called
 * ANativeWindow_acquire() on this window. vnc_client takes over that
 * reference and will release it itself (either when replaced by a new
 * window, or on vncclient_surface_destroyed()).
 */
void vncclient_set_surface(ANativeWindow *window);

/* The Surface is gone. Stop drawing and drop our ANativeWindow reference. */
void vncclient_surface_destroyed(void);

/* Forward a touch event (raw MotionEvent action + surface-local coords). */
void vncclient_touch_event(int action, float x, float y);

/*
 * Start connecting to host:port in a background thread. Returns
 * immediately. password may be NULL for no-auth servers.
 */
void vncclient_connect(const char *host, int port, const char *password);

/* Tear down the connection and stop the background thread. Blocks
 * until the thread has actually exited, so call this before the
 * process that owns the JNIEnv goes away (e.g. from onDestroy). */
void vncclient_disconnect(void);

#ifdef __cplusplus
}
#endif

#endif /* IVYVNC_VNC_CLIENT_H */
