package com.IvyVNC;

import android.app.Activity;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.Window;
import android.view.WindowManager;

public class MainActivity extends Activity implements SurfaceHolder.Callback {

    static {
        // matches building/app/lib/<abi>/libivyvnc.so
        System.loadLibrary("ivyvnc");
    }

    private SurfaceView surfaceView;

    // ---- native entry points (implemented in jni_bridge.c) ----
    // Called once the Surface exists. Native stores it via ANativeWindow_fromSurface
    // and can start drawing frames into it whenever the VNC framebuffer updates.
    private static native void nativeSurfaceCreated(Surface surface);

    // Called when the surface's size/format changes (e.g. rotation).
    private static native void nativeSurfaceChanged(Surface surface, int format, int width, int height);

    // Called when the surface is going away. Native must stop drawing and
    // release the ANativeWindow (ANativeWindow_release) before returning.
    private static native void nativeSurfaceDestroyed();

    // Forwarded touch input: action is the raw MotionEvent action int,
    // x/y are surface-local pixel coordinates.
    private static native void nativeTouchEvent(int action, float x, float y);

    // Kicks off the VNC connection on a native thread. Non-blocking: native
    // spins up its own pthread for the libvncclient event loop.
    private static native void nativeConnect(String host, int port, String password);

    // Tells native to tear down the VNC session (called from onDestroy).
    private static native void nativeDisconnect();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // fullscreen, no title bar -- this is a remote screen, not a normal app UI
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        setContentView(surfaceView);

        // TODO: replace with real connection UI (host/port/password entry).
        // Wiring the call here just to prove the bridge end to end first.
        // nativeConnect("192.168.1.10", 5900, "");
    }

    @Override
    protected void onDestroy() {
        nativeDisconnect();
        super.onDestroy();
    }

    // ---- SurfaceHolder.Callback ----

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        nativeSurfaceCreated(holder.getSurface());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        nativeSurfaceChanged(holder.getSurface(), format, width, height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        nativeSurfaceDestroyed();
    }

    // ---- touch forwarding ----

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        nativeTouchEvent(event.getAction(), event.getX(), event.getY());
        return true;
    }
}
