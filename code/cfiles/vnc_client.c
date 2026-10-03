#include "vnc_client.h"

#include <android/log.h>
#include <android/native_window.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <rfb/rfbclient.h>

#define LOG_TAG "ivyvnc-native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* Implemented in jni_bridge.c -- attaches to the JVM (safe to call from
 * this background pthread) and calls MainActivity.onNativeStatus(). */
extern void jni_notify_status(int status, const char *message);

/* How many remote pixels the virtual cursor moves per on-screen pixel
 * of finger travel, on top of the letterbox scale correction. 1.0 would
 * make the cursor track the remote screen at the same physical rate as
 * a 1:1 warp would have; a bit above that (like a real touchpad, e.g.
 * Winlator's relative-mouse mode) means you don't need a full-width
 * swipe to cross a wide remote desktop. Tune to taste. */
#define CURSOR_SENSITIVITY 1.4

/*
 * One mutex guards every field below. This is not a high-performance
 * design (touch events and framebuffer decode both serialize on it)
 * but it is simple and correct, which matters more until the rest of
 * the app exists. Revisit if input feels laggy on real hardware.
 */
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

static ANativeWindow *g_window = NULL;
static rfbClient      *g_client = NULL;
static pthread_t       g_thread;
static int             g_thread_running = 0;
static int             g_stop_requested = 0;
static char            g_password[256];
static int             g_have_password = 0;

/* Virtual cursor, in remote framebuffer coordinates (not on-screen
 * pixels). Nothing sets this from a raw touch point anymore -- it only
 * ever moves by accumulated deltas from vncclient_cursor_delta(), so a
 * finger landing somewhere never warps the remote cursor there. Reset
 * to the middle of the remote screen on every new connection, since we
 * have no way to know the server's actual current cursor position. */
static double           g_cursor_x = 0.0;
static double           g_cursor_y = 0.0;
/* Buttons currently held down (bitmask of rfbButton*Mask), so a
 * press-and-drag gesture can keep sending button-down across multiple
 * delta moves. */
static int              g_button_mask = 0;

/* Advanced display options, set from Java via vncclient_set_render_options()
 * (see VNC_COLOR_..., VNC_SCALE_... and VNC_RENDER_... in the header).
 * These only affect how vnc_got_update() blits into the on-screen window -- the
 * stored client->frameBuffer itself always stays true 32bpp color, so
 * thumbnail capture doesn't need to care about any of this. */
static int              g_color_mode = VNC_COLOR_FULL;
static int              g_scaling_mode = VNC_SCALE_FIT;
static int              g_rendering_mode = VNC_RENDER_NEAREST;

/* ---- libvncclient callbacks (invoked from the background thread) ---- */

static rfbBool vnc_malloc_framebuffer(rfbClient *client) {
    /* Ask for 32bpp RGBA laid out to match Android's RGBA_8888 window
     * format: byte order R,G,B,A in memory => as a little-endian u32,
     * R is the low byte, so redShift=0, greenShift=8, blueShift=16. */
    client->format.bitsPerPixel = 32;
    client->format.depth = 24;
    client->format.redShift = 0;
    client->format.greenShift = 8;
    client->format.blueShift = 16;
    client->format.redMax = 255;
    client->format.greenMax = 255;
    client->format.blueMax = 255;

    if (client->frameBuffer) {
        free(client->frameBuffer);
        client->frameBuffer = NULL;
    }

    size_t size = (size_t) client->width * (size_t) client->height * 4;
    client->frameBuffer = (uint8_t *) malloc(size);
    if (!client->frameBuffer) {
        LOGE("malloc frameBuffer failed (%dx%d)", client->width, client->height);
        return FALSE;
    }

    pthread_mutex_lock(&g_lock);
    if (g_window) {
        /* Leave width/height as 0,0 so the window keeps ITS OWN on-screen
         * pixel size (e.g. the phone's full screen) instead of being
         * resized to the remote framebuffer's resolution. If we sized the
         * buffer to the remote resolution instead, the compositor would
         * stretch it non-uniformly to fill the SurfaceView's on-screen
         * bounds -- which is exactly the distorted/stretched look we saw
         * before this fix. We do the aspect-correct scaling ourselves in
         * vnc_got_update() instead, so the buffer's actual pixel size
         * should always match the window it's presented in. */
        ANativeWindow_setBuffersGeometry(g_window, 0, 0, WINDOW_FORMAT_RGBA_8888);
    }
    pthread_mutex_unlock(&g_lock);

    SetFormatAndEncodings(client);
    return TRUE;
}

/* Posterize/grayscale a pixel's channels in place according to the
 * selected color mode. Mode FULL is not routed through here at all
 * (the caller skips the call), so there's no branch to fall through. */
static inline void apply_color_mode(int mode, uint8_t *r, uint8_t *g, uint8_t *b) {
    if (mode == VNC_COLOR_256) {
        /* 3-3-2 bit posterization -- the classic "256 color" palette
         * shape (3 bits red, 3 bits green, 2 bits blue), applied
         * directly to the true-color sample instead of a real indexed
         * palette. Visually equivalent banding, much simpler code. */
        *r = (uint8_t) ((*r >> 5) * 36);
        *g = (uint8_t) ((*g >> 5) * 36);
        *b = (uint8_t) ((*b >> 6) * 85);
    } else if (mode == VNC_COLOR_GRAYSCALE) {
        uint8_t y = (uint8_t) ((77 * (int) (*r) + 150 * (int) (*g) + 29 * (int) (*b)) >> 8);
        *r = y;
        *g = y;
        *b = y;
    }
}

static inline double bilerp(double v00, double v10, double v01, double v11, double fx, double fy) {
    double top = v00 + (v10 - v00) * fx;
    double bot = v01 + (v11 - v01) * fx;
    return top + (bot - top) * fy;
}

static void vnc_got_update(rfbClient *client, int x, int y, int w, int h) {
    (void) x; (void) y; (void) w; (void) h; /* full-buffer copy for now, see note below */

    pthread_mutex_lock(&g_lock);
    if (!g_window) {
        pthread_mutex_unlock(&g_lock);
        return;
    }

    int src_w = client->width;
    int src_h = client->height;
    if (src_w <= 0 || src_h <= 0) {
        pthread_mutex_unlock(&g_lock);
        return;
    }

    ANativeWindow_Buffer buf;
    if (ANativeWindow_lock(g_window, &buf, NULL) != 0) {
        LOGE("ANativeWindow_lock failed");
        pthread_mutex_unlock(&g_lock);
        return;
    }

    /* NOTE: this recopies the whole framebuffer on every update callback,
     * not just the dirty rect (x,y,w,h). Simplest correct thing to ship
     * first; switch to a per-row copy of just the dirty rect once the
     * bridge is proven end to end and you care about redraw cost. */

    int color_mode = g_color_mode;
    int scaling_mode = g_scaling_mode;
    int rendering_mode = g_rendering_mode;

    /* Three scaling behaviors, picked in Advanced Settings per connection:
     *   FIT     -- letterbox/pillarbox, aspect ratio preserved (default)
     *   STRETCH -- fills the window, aspect ratio not preserved
     *   CENTER  -- actual size (1:1 remote pixels), centered, cropped
     *              against the window if the remote screen is bigger */
    int dest_w, dest_h, offset_x, offset_y;
    double scale_x, scale_y;

    if (scaling_mode == VNC_SCALE_STRETCH) {
        dest_w = buf.width;
        dest_h = buf.height;
        offset_x = 0;
        offset_y = 0;
        scale_x = (double) buf.width / (double) src_w;
        scale_y = (double) buf.height / (double) src_h;
    } else if (scaling_mode == VNC_SCALE_CENTER) {
        dest_w = src_w;
        dest_h = src_h;
        offset_x = (buf.width - dest_w) / 2;
        offset_y = (buf.height - dest_h) / 2;
        scale_x = 1.0;
        scale_y = 1.0;
    } else {
        double sx = (double) buf.width / (double) src_w;
        double sy = (double) buf.height / (double) src_h;
        double s = sx < sy ? sx : sy;
        dest_w = (int) (src_w * s);
        dest_h = (int) (src_h * s);
        if (dest_w < 1) dest_w = 1;
        if (dest_h < 1) dest_h = 1;
        offset_x = (buf.width - dest_w) / 2;
        offset_y = (buf.height - dest_h) / 2;
        scale_x = s;
        scale_y = s;
    }

    uint32_t *dst_base = (uint32_t *) buf.bits;
    uint32_t *src_base = (uint32_t *) client->frameBuffer;

    /* Black out the whole buffer first so old content doesn't linger in
     * the letterbox bars, in cropped-off edges, or in previously-visible
     * areas after a resize to a smaller remote screen. */
    memset(buf.bits, 0, (size_t) buf.stride * (size_t) buf.height * 4);

    for (int dy = 0; dy < dest_h; dy++) {
        int win_y = offset_y + dy;
        if (win_y < 0 || win_y >= buf.height) continue;
        uint32_t *dst_row = dst_base + (size_t) win_y * buf.stride;

        double src_y_f = dy / scale_y;
        int src_y0 = (int) src_y_f;
        if (src_y0 >= src_h) src_y0 = src_h - 1;

        for (int dx = 0; dx < dest_w; dx++) {
            int win_x = offset_x + dx;
            if (win_x < 0 || win_x >= buf.width) continue;

            double src_x_f = dx / scale_x;
            int src_x0 = (int) src_x_f;
            if (src_x0 >= src_w) src_x0 = src_w - 1;

            uint8_t r, g, b;
            if (rendering_mode == VNC_RENDER_BILINEAR) {
                int x1 = src_x0 + 1 < src_w ? src_x0 + 1 : src_x0;
                int y1 = src_y0 + 1 < src_h ? src_y0 + 1 : src_y0;
                double fx = src_x_f - src_x0;
                double fy = src_y_f - src_y0;
                if (fx < 0) fx = 0; else if (fx > 1) fx = 1;
                if (fy < 0) fy = 0; else if (fy > 1) fy = 1;

                uint32_t p00 = src_base[(size_t) src_y0 * src_w + src_x0];
                uint32_t p10 = src_base[(size_t) src_y0 * src_w + x1];
                uint32_t p01 = src_base[(size_t) y1 * src_w + src_x0];
                uint32_t p11 = src_base[(size_t) y1 * src_w + x1];

                r = (uint8_t) bilerp(p00 & 0xFF, p10 & 0xFF, p01 & 0xFF, p11 & 0xFF, fx, fy);
                g = (uint8_t) bilerp((p00 >> 8) & 0xFF, (p10 >> 8) & 0xFF, (p01 >> 8) & 0xFF, (p11 >> 8) & 0xFF, fx, fy);
                b = (uint8_t) bilerp((p00 >> 16) & 0xFF, (p10 >> 16) & 0xFF, (p01 >> 16) & 0xFF, (p11 >> 16) & 0xFF, fx, fy);
            } else {
                uint32_t px = src_base[(size_t) src_y0 * src_w + src_x0];
                r = (uint8_t) (px & 0xFF);
                g = (uint8_t) ((px >> 8) & 0xFF);
                b = (uint8_t) ((px >> 16) & 0xFF);
            }

            if (color_mode != VNC_COLOR_FULL) {
                apply_color_mode(color_mode, &r, &g, &b);
            }

            dst_row[win_x] = (uint32_t) r | ((uint32_t) g << 8) | ((uint32_t) b << 16) | 0xFF000000u;
        }
    }

    ANativeWindow_unlockAndPost(g_window);
    pthread_mutex_unlock(&g_lock);
}

static char *vnc_get_password(rfbClient *client) {
    (void) client;
    pthread_mutex_lock(&g_lock);
    char *pw = g_have_password ? strdup(g_password) : strdup("");
    pthread_mutex_unlock(&g_lock);
    return pw; /* libvncclient takes ownership and frees this */
}

/* ---- background connection thread ---- */

static void *vnc_thread_main(void *arg) {
    char *host = (char *) arg; /* owned by us now, must free */

    rfbClient *client = rfbGetClient(8, 3, 4);
    if (!client) {
        LOGE("rfbGetClient failed");
        free(host);
        return NULL;
    }
    client->MallocFrameBuffer = vnc_malloc_framebuffer;
    client->GotFrameBufferUpdate = vnc_got_update;
    client->GetPassword = vnc_get_password;
    client->canHandleNewFBSize = TRUE;

    /* host string is "hostname:port"; split it back apart */
    char *colon = strrchr(host, ':');
    int port = 5900;
    if (colon) {
        port = atoi(colon + 1);
        *colon = '\0';
    }
    client->serverHost = strdup(host);
    client->serverPort = port;
    free(host);

    char connecting_msg[300];
    snprintf(connecting_msg, sizeof(connecting_msg), "Connecting to %s:%d", client->serverHost, port);
    jni_notify_status(VNC_STATUS_CONNECTING, connecting_msg);

    pthread_mutex_lock(&g_lock);
    g_client = client;
    pthread_mutex_unlock(&g_lock);

    if (!rfbInitClient(client, NULL, NULL)) {
        LOGE("rfbInitClient failed (connect/handshake failed)");
        jni_notify_status(VNC_STATUS_FAILED, "Connection failed (check host/port/password)");
        pthread_mutex_lock(&g_lock);
        g_client = NULL;
        pthread_mutex_unlock(&g_lock);
        /* rfbInitClient already frees `client` on failure */
        return NULL;
    }

    LOGI("connected: %dx%d", client->width, client->height);
    char connected_msg[64];
    snprintf(connected_msg, sizeof(connected_msg), "Connected (%dx%d)", client->width, client->height);

    /* Start the virtual cursor centered -- we have no way to learn the
     * server's actual current pointer position (no rich-cursor
     * pseudo-encoding negotiated), so this is just a reasonable place
     * to begin relative tracking from. */
    pthread_mutex_lock(&g_lock);
    g_cursor_x = client->width / 2.0;
    g_cursor_y = client->height / 2.0;
    g_button_mask = 0;
    pthread_mutex_unlock(&g_lock);

    jni_notify_status(VNC_STATUS_CONNECTED, connected_msg);

    while (1) {
        pthread_mutex_lock(&g_lock);
        int stop = g_stop_requested;
        pthread_mutex_unlock(&g_lock);
        if (stop) break;

        int n = WaitForMessage(client, 100000 /* 100ms poll */);
        if (n < 0) {
            LOGE("WaitForMessage error, disconnecting");
            jni_notify_status(VNC_STATUS_DISCONNECTED, "Connection lost");
            break;
        }
        if (n == 0) continue; /* just a poll timeout, loop and check stop flag */

        if (!HandleRFBServerMessage(client)) {
            LOGE("HandleRFBServerMessage failed, server likely closed connection");
            jni_notify_status(VNC_STATUS_DISCONNECTED, "Server closed the connection");
            break;
        }
    }

    pthread_mutex_lock(&g_lock);
    g_client = NULL;
    pthread_mutex_unlock(&g_lock);

    rfbClientCleanup(client);
    return NULL;
}

/* ---- public API (called from jni_bridge.c) ---- */

void vncclient_set_surface(ANativeWindow *window) {
    pthread_mutex_lock(&g_lock);
    if (g_window) {
        ANativeWindow_release(g_window);
    }
    g_window = window; /* caller already acquired a reference for us */
    if (g_window) {
        /* 0,0 keeps the window's own on-screen size -- see the comment
         * in vnc_malloc_framebuffer for why we don't size this to the
         * remote framebuffer's resolution. */
        ANativeWindow_setBuffersGeometry(g_window, 0, 0, WINDOW_FORMAT_RGBA_8888);
    }
    pthread_mutex_unlock(&g_lock);
}

void vncclient_surface_destroyed(void) {
    pthread_mutex_lock(&g_lock);
    if (g_window) {
        ANativeWindow_release(g_window);
        g_window = NULL;
    }
    pthread_mutex_unlock(&g_lock);
}

/* Same letterbox scale used by vnc_got_update() for rendering -- reused
 * here so a delta expressed in on-screen pixels converts to the right
 * number of remote pixels regardless of how the image is letterboxed.
 * Must be called with g_lock held. */
static double cursor_scale_locked(rfbClient *client, ANativeWindow *window) {
    int32_t win_w = ANativeWindow_getWidth(window);
    int32_t win_h = ANativeWindow_getHeight(window);
    double scale_x = win_w > 0 ? (double) win_w / (double) client->width : 1.0;
    double scale_y = win_h > 0 ? (double) win_h / (double) client->height : 1.0;
    double scale = scale_x < scale_y ? scale_x : scale_y;
    return scale > 0 ? scale : 1.0;
}

/* Clamp g_cursor_x/y into the remote screen and send it, with whatever
 * buttons are currently held in g_button_mask. Must be called with
 * g_lock held, and only once g_client is known non-NULL. */
static void send_cursor_locked(rfbClient *client) {
    if (client->width <= 0 || client->height <= 0) return;
    if (g_cursor_x < 0) g_cursor_x = 0;
    if (g_cursor_x > client->width - 1) g_cursor_x = client->width - 1;
    if (g_cursor_y < 0) g_cursor_y = 0;
    if (g_cursor_y > client->height - 1) g_cursor_y = client->height - 1;
    SendPointerEvent(client, (int) g_cursor_x, (int) g_cursor_y, g_button_mask);
}

static int button_mask_for(int button) {
    switch (button) {
        case 1: return rfbButton1Mask;
        case 2: return rfbButton2Mask;
        case 3: return rfbButton3Mask;
        default: return 0;
    }
}

void vncclient_cursor_delta(float dx, float dy) {
    pthread_mutex_lock(&g_lock);
    rfbClient *client = g_client;
    ANativeWindow *window = g_window;
    if (!client || !window) {
        pthread_mutex_unlock(&g_lock);
        return;
    }
    double scale = cursor_scale_locked(client, window);
    g_cursor_x += (dx / scale) * CURSOR_SENSITIVITY;
    g_cursor_y += (dy / scale) * CURSOR_SENSITIVITY;
    send_cursor_locked(client);
    pthread_mutex_unlock(&g_lock);
}

void vncclient_cursor_tap(int button) {
    pthread_mutex_lock(&g_lock);
    rfbClient *client = g_client;
    if (!client) {
        pthread_mutex_unlock(&g_lock);
        return;
    }
    int mask = button_mask_for(button);
    int saved = g_button_mask;
    g_button_mask = saved | mask;
    send_cursor_locked(client);
    g_button_mask = saved;
    send_cursor_locked(client);
    pthread_mutex_unlock(&g_lock);
}

void vncclient_cursor_button(int button, int down) {
    pthread_mutex_lock(&g_lock);
    rfbClient *client = g_client;
    if (!client) {
        pthread_mutex_unlock(&g_lock);
        return;
    }
    int mask = button_mask_for(button);
    if (down) {
        g_button_mask |= mask;
    } else {
        g_button_mask &= ~mask;
    }
    send_cursor_locked(client);
    pthread_mutex_unlock(&g_lock);
}

void vncclient_cursor_scroll(int ticks) {
    pthread_mutex_lock(&g_lock);
    rfbClient *client = g_client;
    if (!client) {
        pthread_mutex_unlock(&g_lock);
        return;
    }
    int mask = ticks > 0 ? rfbWheelDownMask : rfbWheelUpMask;
    int saved = g_button_mask;
    g_button_mask = saved | mask;
    send_cursor_locked(client);
    g_button_mask = saved;
    send_cursor_locked(client);
    pthread_mutex_unlock(&g_lock);
}

void vncclient_connect(const char *host, int port, const char *password) {
    pthread_mutex_lock(&g_lock);
    if (g_thread_running) {
        pthread_mutex_unlock(&g_lock);
        LOGE("vncclient_connect: already connected/connecting, ignoring");
        return;
    }
    g_stop_requested = 0;
    if (password) {
        strncpy(g_password, password, sizeof(g_password) - 1);
        g_password[sizeof(g_password) - 1] = '\0';
        g_have_password = 1;
    } else {
        g_have_password = 0;
    }
    pthread_mutex_unlock(&g_lock);

    /* pack "host:port" into one owned buffer for the thread */
    size_t len = strlen(host) + 1 + 10 + 1;
    char *hostport = (char *) malloc(len);
    snprintf(hostport, len, "%s:%d", host, port);

    pthread_mutex_lock(&g_lock);
    g_thread_running = 1;
    pthread_mutex_unlock(&g_lock);

    pthread_create(&g_thread, NULL, vnc_thread_main, hostport);
}

void vncclient_disconnect(void) {
    pthread_mutex_lock(&g_lock);
    int was_running = g_thread_running;
    g_stop_requested = 1;
    pthread_mutex_unlock(&g_lock);

    if (was_running) {
        pthread_join(g_thread, NULL);
    }

    pthread_mutex_lock(&g_lock);
    g_thread_running = 0;
    pthread_mutex_unlock(&g_lock);
}

void vncclient_set_render_options(int color_mode, int scaling_mode, int rendering_mode) {
    pthread_mutex_lock(&g_lock);
    g_color_mode = color_mode;
    g_scaling_mode = scaling_mode;
    g_rendering_mode = rendering_mode;
    pthread_mutex_unlock(&g_lock);
}

/* Downscales client->frameBuffer (always true 32bpp RGBA, regardless of
 * the display color mode -- see the g_color_mode comment above) into a
 * small buffer under lock, then writes it out to `path` once unlocked
 * so disk I/O never happens while holding g_lock. */
void vncclient_capture_thumbnail(const char *path, int max_dim) {
    pthread_mutex_lock(&g_lock);
    rfbClient *client = g_client;
    if (!client || !client->frameBuffer || client->width <= 0 || client->height <= 0) {
        pthread_mutex_unlock(&g_lock);
        return;
    }

    int src_w = client->width;
    int src_h = client->height;
    double scale = 1.0;
    int longest = src_w > src_h ? src_w : src_h;
    if (max_dim > 0 && longest > max_dim) {
        scale = (double) max_dim / (double) longest;
    }
    int out_w = (int) (src_w * scale);
    int out_h = (int) (src_h * scale);
    if (out_w < 1) out_w = 1;
    if (out_h < 1) out_h = 1;

    uint8_t *out = (uint8_t *) malloc((size_t) out_w * (size_t) out_h * 4);
    if (!out) {
        pthread_mutex_unlock(&g_lock);
        return;
    }

    uint32_t *src = (uint32_t *) client->frameBuffer;
    for (int y = 0; y < out_h; y++) {
        int sy = (int) (y / scale);
        if (sy >= src_h) sy = src_h - 1;
        for (int x = 0; x < out_w; x++) {
            int sx = (int) (x / scale);
            if (sx >= src_w) sx = src_w - 1;
            uint32_t px = src[(size_t) sy * src_w + sx];
            uint8_t *dst = out + ((size_t) y * out_w + x) * 4;
            dst[0] = (uint8_t) (px & 0xFF);         /* R */
            dst[1] = (uint8_t) ((px >> 8) & 0xFF);  /* G */
            dst[2] = (uint8_t) ((px >> 16) & 0xFF); /* B */
            dst[3] = (uint8_t) ((px >> 24) & 0xFF); /* A */
        }
    }
    pthread_mutex_unlock(&g_lock);

    FILE *f = fopen(path, "wb");
    if (f) {
        /* Big-endian header to match Java's DataInputStream.readInt(). */
        uint32_t w32 = (uint32_t) out_w, h32 = (uint32_t) out_h;
        uint8_t hdr[8] = {
                (uint8_t) (w32 >> 24), (uint8_t) (w32 >> 16), (uint8_t) (w32 >> 8), (uint8_t) w32,
                (uint8_t) (h32 >> 24), (uint8_t) (h32 >> 16), (uint8_t) (h32 >> 8), (uint8_t) h32,
        };
        fwrite(hdr, 1, sizeof(hdr), f);
        fwrite(out, 1, (size_t) out_w * (size_t) out_h * 4, f);
        fclose(f);
    } else {
        LOGE("vncclient_capture_thumbnail: fopen failed for %s", path);
    }
    free(out);
}

void vncclient_get_session_info(char *out, size_t out_size) {
    if (!out || out_size == 0) {
        return;
    }
    pthread_mutex_lock(&g_lock);
    rfbClient *client = g_client;
    if (!client) {
        snprintf(out, out_size, "Not connected");
        pthread_mutex_unlock(&g_lock);
        return;
    }
    const char *name = client->desktopName ? client->desktopName : "(unnamed)";
    snprintf(out, out_size, "%s|%d|%d|%d", name, client->width, client->height, client->format.bitsPerPixel);
    pthread_mutex_unlock(&g_lock);
}

void vncclient_key_event(uint32_t keysym, int down) {
    pthread_mutex_lock(&g_lock);
    rfbClient *client = g_client;
    if (!client) {
        pthread_mutex_unlock(&g_lock);
        return;
    }
    SendKeyEvent(client, keysym, down ? TRUE : FALSE);
    pthread_mutex_unlock(&g_lock);
}
