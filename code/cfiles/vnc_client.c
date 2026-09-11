#include "vnc_client.h"

#include <android/log.h>
#include <android/native_window.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#include <rfb/rfbclient.h>

#define LOG_TAG "ivyvnc-native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* Matches android.view.MotionEvent action constants we care about. */
#define MOTION_ACTION_DOWN 0
#define MOTION_ACTION_UP   1
#define MOTION_ACTION_MOVE 2

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
        ANativeWindow_setBuffersGeometry(g_window, client->width, client->height,
                                          WINDOW_FORMAT_RGBA_8888);
    }
    pthread_mutex_unlock(&g_lock);

    SetFormatAndEncodings(client);
    return TRUE;
}

static void vnc_got_update(rfbClient *client, int x, int y, int w, int h) {
    (void) x; (void) y; (void) w; (void) h; /* full-buffer copy for now, see note below */

    pthread_mutex_lock(&g_lock);
    if (!g_window) {
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
    int copy_h = client->height < buf.height ? client->height : buf.height;
    int copy_w = client->width < buf.width ? client->width : buf.width;
    uint8_t *dst = (uint8_t *) buf.bits;
    uint8_t *src = client->frameBuffer;
    size_t dst_stride_bytes = (size_t) buf.stride * 4;
    size_t src_stride_bytes = (size_t) client->width * 4;
    size_t row_bytes = (size_t) copy_w * 4;

    for (int row = 0; row < copy_h; row++) {
        memcpy(dst + row * dst_stride_bytes, src + row * src_stride_bytes, row_bytes);
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

    pthread_mutex_lock(&g_lock);
    g_client = client;
    pthread_mutex_unlock(&g_lock);

    if (!rfbInitClient(client, NULL, NULL)) {
        LOGE("rfbInitClient failed (connect/handshake failed)");
        pthread_mutex_lock(&g_lock);
        g_client = NULL;
        pthread_mutex_unlock(&g_lock);
        /* rfbInitClient already frees `client` on failure */
        return NULL;
    }

    LOGI("connected: %dx%d", client->width, client->height);

    while (1) {
        pthread_mutex_lock(&g_lock);
        int stop = g_stop_requested;
        pthread_mutex_unlock(&g_lock);
        if (stop) break;

        int n = WaitForMessage(client, 100000 /* 100ms poll */);
        if (n < 0) {
            LOGE("WaitForMessage error, disconnecting");
            break;
        }
        if (n == 0) continue; /* just a poll timeout, loop and check stop flag */

        if (!HandleRFBServerMessage(client)) {
            LOGE("HandleRFBServerMessage failed, server likely closed connection");
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
    if (g_window && g_client) {
        ANativeWindow_setBuffersGeometry(g_window, g_client->width, g_client->height,
                                          WINDOW_FORMAT_RGBA_8888);
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

void vncclient_touch_event(int action, float x, float y) {
    pthread_mutex_lock(&g_lock);
    rfbClient *client = g_client;
    ANativeWindow *window = g_window;
    if (!client || !window) {
        pthread_mutex_unlock(&g_lock);
        return;
    }

    /* Scale surface-local coords to remote framebuffer coords, in case
     * the SurfaceView isn't a 1:1 pixel match with the remote screen. */
    int32_t win_w = ANativeWindow_getWidth(window);
    int32_t win_h = ANativeWindow_getHeight(window);
    int remote_x = win_w > 0 ? (int) (x * client->width / win_w) : (int) x;
    int remote_y = win_h > 0 ? (int) (y * client->height / win_h) : (int) y;

    int button_mask = 0;
    if (action == MOTION_ACTION_DOWN || action == MOTION_ACTION_MOVE) {
        button_mask = rfbButton1Mask; /* treat touch as left-click drag */
    }
    /* ACTION_UP leaves button_mask at 0, i.e. release. */

    SendPointerEvent(client, remote_x, remote_y, button_mask);
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
