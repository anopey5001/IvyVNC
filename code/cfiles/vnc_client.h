#ifndef IVYVNC_VNC_CLIENT_H
#define IVYVNC_VNC_CLIENT_H

#include <android/native_window.h>
#include <stddef.h>
#include <stdint.h>

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

/*
 * Relative pointer control -- gesture recognition (tap vs. drag vs.
 * scroll) lives in Java (MainActivity has the real multitouch APIs for
 * that); native just owns the virtual cursor position and turns these
 * primitives into RFB pointer/wheel events. There's no absolute "move
 * cursor to this touch point" call anymore -- the finger's raw position
 * on glass is never sent, only deltas, so touching down somewhere never
 * teleports the remote cursor there (the server draws its own cursor
 * into the framebuffer for us, since we never advertise a rich-cursor
 * pseudo-encoding, so we don't need to render one ourselves either).
 */

/* dx/dy are in surface-local (on-screen) pixels, same space as the
 * MotionEvent coordinates Java already has -- native rescales them into
 * remote framebuffer pixels using the same letterbox scale it renders
 * with, and nudges the virtual cursor by that amount. */
void vncclient_cursor_delta(float dx, float dy);

/* Quick click at the current virtual cursor position: press then
 * release. button is 1 (left), 2 (middle), or 3 (right). */
void vncclient_cursor_tap(int button);

/* Explicit press/release at the current virtual cursor position, for
 * press-and-drag gestures where the button needs to stay down across
 * several vncclient_cursor_delta() calls. */
void vncclient_cursor_button(int button, int down);

/* One scroll step at the current virtual cursor position. Positive
 * ticks scroll down, negative scroll up; magnitude beyond the sign is
 * ignored -- call once per step Java decides has happened. */
void vncclient_cursor_scroll(int ticks);

/*
 * Start connecting to host:port in a background thread. Returns
 * immediately. password may be NULL for no-auth servers.
 */
void vncclient_connect(const char *host, int port, const char *password);

/* Tear down the connection and stop the background thread. Blocks
 * until the thread has actually exited, so call this before the
 * process that owns the JNIEnv goes away (e.g. from onDestroy). */
void vncclient_disconnect(void);

/* Send a key event using an X11 keysym (same values libvncclient/RFB
 * expect -- printable ASCII keysyms equal the ASCII code itself;
 * special keys like Backspace/Enter/Ctrl use the XK_* constants from
 * rfb/keysym.h). down is nonzero for key-press, zero for key-release. */
void vncclient_key_event(uint32_t keysym, int down);

/* Advanced display options, settable per-connection from Java before
 * vncclient_connect() (also safe to call mid-session -- takes effect
 * on the next framebuffer update). See the VNC_COLOR_..., VNC_SCALE_...
 * and VNC_RENDER_... constants below for the values each parameter takes. */
void vncclient_set_render_options(int color_mode, int scaling_mode, int rendering_mode);

#define VNC_COLOR_FULL      0 /* true color, no post-processing */
#define VNC_COLOR_256       1 /* posterized to a ~256-color palette */
#define VNC_COLOR_GRAYSCALE 2 /* luminance only */

#define VNC_SCALE_FIT     0 /* letterboxed, aspect ratio preserved (default) */
#define VNC_SCALE_STRETCH 1 /* fills the window, aspect ratio not preserved */
#define VNC_SCALE_CENTER  2 /* actual size (1:1), centered, cropped if larger */

#define VNC_RENDER_NEAREST  0 /* nearest-neighbor sampling, cheapest (default) */
#define VNC_RENDER_BILINEAR 1 /* bilinear-interpolated sampling, smoother */

/* Downscale the current remote framebuffer to fit within max_dim x
 * max_dim (preserving aspect ratio) and write it to `path` as a tiny
 * raw format: two big-endian int32 (width, height) followed by
 * width*height*4 raw RGBA bytes. No-op if there's no live connection
 * or framebuffer yet. Meant to be called right before disconnecting,
 * so the saved connection has a "last seen" thumbnail. */
void vncclient_capture_thumbnail(const char *path, int max_dim);

/* Writes "desktopName|width|height|bitsPerPixel" into out (truncated to
 * out_size, always NUL-terminated), or "Not connected" if there's no
 * live connection yet. Used by the session ☰ > Connection details
 * dialog to show what the server itself reports about the remote
 * desktop. */
void vncclient_get_session_info(char *out, size_t out_size);

/*
 * Status codes reported to Java via jni_notify_status() (implemented in
 * jni_bridge.c, called from vnc_client.c). Keep these in sync with the
 * STATUS_* constants in MainActivity.java -- there's no shared source
 * of truth between C and Java here, so a mismatch is silent.
 */
#define VNC_STATUS_CONNECTING   0
#define VNC_STATUS_CONNECTED    1
#define VNC_STATUS_FAILED       2
#define VNC_STATUS_DISCONNECTED 3

#ifdef __cplusplus
}
#endif

#endif /* IVYVNC_VNC_CLIENT_H */
