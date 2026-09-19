package com.IvyVNC;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements SurfaceHolder.Callback {

    static {
        // matches building/app/lib/<abi>/libivyvnc.so
        System.loadLibrary("ivyvnc");
    }

    private static final String PREFS_NAME = "ivyvnc_prefs";
    private static final String PREF_CONNECTIONS = "connections"; // JSON array of saved profiles
    private static final String PREF_MOUSE_SENS = "mouse_sensitivity";
    private static final String PREF_CUSTOM_KEYS = "custom_keys"; // JSON array of user-defined toolbar buttons
    private static final String PREF_THEME_MODE = "theme_mode";
    private static final String PREF_KEEP_AWAKE = "keep_screen_awake";
    private static final String PREF_DELETE_TO_BACKSPACE = "delete_to_backspace";

    private static final float MIN_SENSITIVITY = 0.25f;
    private static final float MAX_SENSITIVITY = 3.0f;
    private static final float DEFAULT_SENSITIVITY = 1.0f;

    // Advanced per-connection display settings -- values match the
    // VNC_COLOR_*/VNC_SCALE_*/VNC_RENDER_* constants in vnc_client.h
    // exactly (no shared source of truth, keep in sync by hand).
    private static final int COLOR_MODE_FULL = 0;
    private static final int COLOR_MODE_256 = 1;
    private static final int COLOR_MODE_GRAYSCALE = 2;
    private static final String[] COLOR_MODE_LABELS = {"Full Color", "256 Colors", "Grayscale"};

    private static final int SCALE_MODE_FIT = 0;
    private static final int SCALE_MODE_STRETCH = 1;
    private static final int SCALE_MODE_CENTER = 2;
    private static final String[] SCALE_MODE_LABELS = {"Fit to Screen", "Stretch to Fill", "Actual Size (1:1)"};

    private static final int RENDER_MODE_NEAREST = 0;
    private static final int RENDER_MODE_BILINEAR = 1;
    private static final String[] RENDER_MODE_LABELS = {"Fast (Nearest Neighbor)", "Smooth (Bilinear)"};

    // Only 3 themes: follow the system, force dark, or force light --
    // no separate accent-color picker. "System" reads the device's
    // current day/night setting (Configuration.UI_MODE_NIGHT_*).
    private static final int THEME_MODE_SYSTEM = 0;
    private static final int THEME_MODE_DARK = 1;
    private static final int THEME_MODE_LIGHT = 2;

    // Longest edge, in pixels, of a saved connection-card thumbnail.
    private static final int THUMBNAIL_MAX_DIM = 240;

    // Must match VNC_STATUS_* in vnc_client.h -- no shared source of
    // truth between C and Java, so keep these in sync by hand.
    private static final int STATUS_CONNECTING = 0;
    private static final int STATUS_CONNECTED = 1;
    private static final int STATUS_FAILED = 2;
    private static final int STATUS_DISCONNECTED = 3;

    // Standard X11 keysym values (from rfb/keysym.h upstream) for the
    // special keys on the toolbar. Printable characters don't need a
    // lookup table -- their keysym is just their character code.
    private static final int XK_BackSpace = 0xff08;
    private static final int XK_Tab = 0xff09;
    private static final int XK_Return = 0xff0d;
    private static final int XK_Escape = 0xff1b;
    private static final int XK_Left = 0xff51;
    private static final int XK_Up = 0xff52;
    private static final int XK_Right = 0xff53;
    private static final int XK_Down = 0xff54;
    private static final int XK_Control_L = 0xffe3;
    private static final int XK_Alt_L = 0xffe9;
    private static final int XK_Shift_L = 0xffe1;
    private static final int XK_Super_L = 0xffeb;
    private static final int XK_Home = 0xff50;
    private static final int XK_Page_Up = 0xff55;
    private static final int XK_Page_Down = 0xff56;
    private static final int XK_End = 0xff57;
    private static final int XK_Delete = 0xffff;
    // F1..F12 are contiguous in the X11 keysym table, so F(n) below just
    // offsets from F1 instead of needing all twelve spelled out.
    private static final int XK_F1 = 0xffbe;

    // ---- palette: now lives in res/values/colors.xml (light/default)
    // and res/values-night/colors.xml (dark overrides), resolved
    // automatically by the resource system via themedContext (see
    // applyActivityTheme()). This method is only for the handful of
    // spots that still need a raw int at runtime -- a dynamically
    // toggled TextView color (modifier-lock visuals, page dots) or a
    // GradientDrawable built from a computed color (per-connection
    // avatar). Everything else reads colors straight out of XML. ----
    private int color(int resId) {
        return themedContext.getColor(resId);
    }

    // Touchpad-style relative cursor tuning.
    private static final int LONG_PRESS_MS = 450;
    private static final int TWO_FINGER_TAP_MS = 250;
    private static final float STATUS_FADE_ALPHA = 0.35f;
    private static final long STATUS_FADE_DELAY_MS = 2500;

    private SurfaceView surfaceView;
    private TextView statusText;
    private EditText keyCaptureInput;
    private TextView ctrlButton;
    private TextView altButton;
    private TextView shiftButton;
    private int lastStatus = -1;

    // ---- paged toolbar: three swipeable pages (custom | main | function
    // keys), with a dot-strip up top like Termux's extra-keys popup. ----
    private View[] toolbarPages;
    private TextView[] pageDots;
    private int currentToolbarPage = 1; // start on the main/nav-keys page
    private List<CustomKey> customKeys;
    private FrameLayout sessionRoot;
    private FrameLayout toolbarContainer;
    private View toolbarView;

    // Whether we're currently showing the VNC session view (surface +
    // toolbar) as opposed to the home/connections-list screen. Used by
    // the back button and by onDestroy to decide whether there's
    // actually a live connection worth tearing down.
    private boolean inSession = false;
    // Which non-session screen is up -- only meaningful while
    // !inSession, so a theme/setting change made from the Settings
    // screen redraws that screen instead of bouncing back to Home.
    private boolean onSettingsScreen = false;

    // Global pointer-move multiplier, tunable from Settings. Applied
    // only to hover/drag deltas -- scroll ticks are left alone.
    private float mouseSensitivity = DEFAULT_SENSITIVITY;

    // Whether to keep the screen on during a session (Settings), and
    // whether a local "Delete" key (KEYCODE_FORWARD_DEL) should be
    // remapped to Backspace instead of the remote Delete keysym --
    // some external/bluetooth keyboards don't produce a key some
    // devices recognize as Delete, so this gives those users a way to
    // get a working delete-ish key anyway.
    private boolean keepScreenAwake = true;
    private boolean deleteKeyToBackspace = false;

    // THEME_MODE_SYSTEM/DARK/LIGHT (Settings > Theme). isDarkTheme is
    // the resolved boolean the rest of the UI actually reads -- see
    // applyActivityTheme(), which also picks the matching native
    // Activity theme so AlertDialog/PopupMenu/Spinner/Switch (all
    // system-drawn, not our own hand-painted views) follow along too.
    private int themeMode = THEME_MODE_SYSTEM;
    private boolean isDarkTheme = true;

    // A Context whose Configuration's night-mode bit is force-set to
    // match isDarkTheme, so every XML layout we inflate through it
    // (and any @color lookup made via color() above) resolves against
    // res/values/ or res/values-night/ correctly -- even when the user
    // has picked "Dark" or "Light" from Settings rather than
    // "System" (which wouldn't otherwise match the device's actual
    // night-mode config). Rebuilt by applyActivityTheme() whenever the
    // resolved theme changes. Every inflate() in this file should go
    // through this -- but NOT AlertDialog.Builder/PopupMenu: those add
    // their own top-level window and need a real Activity context to
    // get a valid window token, or they crash with a BadTokenException
    // ("token null is not valid"). Views get inflated via themedContext
    // first and then handed to Builder(this)/PopupMenu(this, anchor),
    // so they still pick up the right night-mode colors while the
    // dialog/popup shell itself attaches correctly.
    private Context themedContext;
    private LayoutInflater inflater;

    // The connection currently driving the session view, if any --
    // needed by confirmDisconnect()/onNativeStatus() to know which
    // saved profile's thumbnail to refresh and which details to show.
    private Connection activeConnection;
    private String lastStatusMessage = "Not connected";

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable statusFadeRunnable = new Runnable() {
        @Override
        public void run() {
            statusText.animate().alpha(STATUS_FADE_ALPHA).setDuration(400).start();
        }
    };

    // ---- relative touchpad gesture state (see onTouchEvent) ----
    private int touchSlopPx;
    private int scrollStepPx;
    private float lastTouchX, lastTouchY;
    private float totalMoveDistance;
    private boolean singleFingerMoved;
    private boolean dragMode;
    private Runnable longPressRunnable;
    private boolean twoFingerMode;
    private boolean twoFingerMoved;
    private float lastTwoFingerAvgY;
    private float twoFingerScrollAccum;
    private long twoFingerDownTime;

    // ---- native entry points (implemented in jni_bridge.c) ----

    // Hands native a reference to this Activity instance so it can call
    // back onto onNativeStatus() from any thread (our native methods are
    // all static, so there's no implicit `this` otherwise).
    private static native void nativeSetContext(Object activity);

    private static native void nativeSurfaceCreated(Surface surface);
    private static native void nativeSurfaceChanged(Surface surface, int format, int width, int height);
    private static native void nativeSurfaceDestroyed();

    // Relative pointer control -- there is deliberately no "move cursor
    // to this absolute point" entry point. A finger touching down never
    // teleports the remote cursor there; only accumulated deltas move
    // it, the way a laptop touchpad works (and the way Winlator drives
    // a relative mouse). The server draws its own cursor into the
    // framebuffer for us, so we don't need to render one on our side.
    private static native void nativeCursorDelta(float dx, float dy);
    private static native void nativeCursorTap(int button);
    private static native void nativeCursorButton(int button, boolean down);
    private static native void nativeCursorScroll(int ticks);

    private static native void nativeKeyEvent(int keysym, boolean down);
    private static native void nativeConnect(String host, int port, String password);
    private static native void nativeDisconnect();

    // Advanced per-connection display settings -- see the
    // COLOR_MODE_*/SCALE_MODE_*/RENDER_MODE_* constants above, which
    // match vnc_client.h's VNC_COLOR_*/VNC_SCALE_*/VNC_RENDER_* values.
    private static native void nativeSetRenderOptions(int colorMode, int scalingMode, int renderingMode);

    // Snapshots the current remote framebuffer to a small raw file at
    // `path`, longest edge capped to maxDim. No-op if nothing is
    // connected yet.
    private static native void nativeCaptureThumbnail(String path, int maxDim);

    // Pipe-delimited "desktopName|width|height|bitsPerPixel" for the
    // live connection, or a placeholder string if nothing's connected.
    // See showConnectionDetailsDialog().
    private static native String nativeGetSessionInfo();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Has to happen before super.onCreate()/any view inflation --
        // it's what makes every AlertDialog/PopupMenu/Spinner/Switch
        // created afterward (system-drawn widgets, not our own
        // hand-painted views) actually follow the selected theme
        // instead of defaulting to the ancient light Holo look.
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        themeMode = prefs.getInt(PREF_THEME_MODE, THEME_MODE_SYSTEM);
        applyActivityTheme();

        super.onCreate(savedInstanceState);
        nativeSetContext(this);

        touchSlopPx = ViewConfiguration.get(this).getScaledTouchSlop();
        scrollStepPx = dp(48);
        mouseSensitivity = prefs.getFloat(PREF_MOUSE_SENS, DEFAULT_SENSITIVITY);
        keepScreenAwake = prefs.getBoolean(PREF_KEEP_AWAKE, true);
        deleteKeyToBackspace = prefs.getBoolean(PREF_DELETE_TO_BACKSPACE, false);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        applyKeepScreenAwake();

        showHomeScreen();
    }

    // Resolves themeMode to isDarkTheme and pushes a matching native
    // Activity theme. Safe to call again later (see setThemeMode()) --
    // Activity.setTheme() updates the live Resources.Theme in place, so
    // every dialog/popup/spinner built after the call (always freshly
    // constructed in this codebase, never reused) picks it up with no
    // Activity recreate() needed, which matters mid-session since
    // recreate() would tear down and rebuild the live SurfaceView.
    private void applyActivityTheme() {
        if (themeMode == THEME_MODE_DARK) {
            isDarkTheme = true;
        } else if (themeMode == THEME_MODE_LIGHT) {
            isDarkTheme = false;
        } else {
            int nightBits = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            isDarkTheme = nightBits == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        }
        setTheme(isDarkTheme
                ? android.R.style.Theme_Holo_NoActionBar
                : android.R.style.Theme_Holo_Light_NoActionBar);

        Configuration override = new Configuration(getResources().getConfiguration());
        override.uiMode = (override.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (isDarkTheme ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        themedContext = createConfigurationContext(override);
        inflater = LayoutInflater.from(themedContext);
    }

    // Persists the new theme mode, re-resolves isDarkTheme/the Activity
    // theme, and repaints whatever's currently on screen. In session,
    // that means rebuilding the toolbar/status chrome around the
    // SurfaceView (a brief flicker as the surface recreates, but the
    // live native connection and its background thread are untouched --
    // nativeConnect() is not called again); otherwise it's just
    // showHomeScreen().
    private void setThemeMode(int mode) {
        themeMode = mode;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putInt(PREF_THEME_MODE, mode)
                .apply();
        applyActivityTheme();
        if (inSession) {
            setContentView(buildSessionView());
            applyStatusToPill(lastStatus, lastStatusMessage);
        } else if (onSettingsScreen) {
            showSettingsScreen();
        } else {
            showHomeScreen();
        }
    }

    // Adds or clears FLAG_KEEP_SCREEN_ON to match the Settings toggle.
    // Called at startup and again right after it's toggled, since a
    // window flag set once at onCreate wouldn't otherwise notice a
    // mid-session change.
    private void applyKeepScreenAwake() {
        if (keepScreenAwake) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    // Still needed for the one dynamic drawable that can't live in
    // res/drawable/ -- a per-connection avatar background computed at
    // runtime from a hash of the connection's name (see colorForName).
    private GradientDrawable roundedDrawable(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    // ======================================================================
    // Home screen: topbar + list of saved connections (or an empty state).
    // This is what launches instead of jumping straight into a connect
    // dialog -- connections are named/saved profiles you tap to launch.
    // Structure lives in res/layout/activity_home.xml; this just wires
    // it up and fills in the dynamic content area.
    // ======================================================================

    private void showHomeScreen() {
        inSession = false;
        onSettingsScreen = false;
        setContentView(buildHomeView());
    }

    private View buildHomeView() {
        View root = inflater.inflate(R.layout.activity_home, null);

        final TextView menuButton = root.findViewById(R.id.home_menu_button);
        menuButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettingsScreen();
            }
        });

        FrameLayout content = root.findViewById(R.id.home_content);
        populateHomeContent(content);

        View fab = root.findViewById(R.id.home_fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showConnectionDialog(null);
            }
        });

        return root;
    }

    // Menu item ids for the in-session ☰ (showSessionMenu) and its
    // "Settings" sub-popup (showVncSettingsMenu). The home-screen ☰
    // opens a real full-screen Settings screen instead of a popup (see
    // showSettingsScreen() below), so it doesn't need ids here.
    private static final int MENU_ID_CONNECTION_DETAILS = 1;
    private static final int MENU_ID_DISCONNECT = 2;
    private static final int MENU_ID_VNC_SETTINGS = 3;
    private static final int MENU_ID_MOUSE_SENSITIVITY = 4;
    private static final int MENU_ID_COLOR_FULL = 5;
    private static final int MENU_ID_COLOR_256 = 6;
    private static final int MENU_ID_COLOR_GRAYSCALE = 7;
    private static final int MENU_ID_SCALE_FIT = 8;
    private static final int MENU_ID_SCALE_STRETCH = 9;
    private static final int MENU_ID_SCALE_CENTER = 10;
    private static final int MENU_ID_RENDER_NEAREST = 11;
    private static final int MENU_ID_RENDER_BILINEAR = 12;

    // ======================================================================
    // Home ☰: a real full-screen Settings screen (not a popup) -- pointer
    // sensitivity, theme, keep-awake, and the local-Delete-key remap.
    // Only mouse sensitivity needs an actual dialog (a slider doesn't
    // fit in a settings row); the rest toggle/cycle in place, and every
    // change re-renders this screen via showSettingsScreen() so the row
    // subtitle reflects the new value right away. Structure lives in
    // res/layout/screen_settings.xml + res/layout/item_setting_row.xml.
    // ======================================================================

    private void showSettingsScreen() {
        inSession = false;
        onSettingsScreen = true;
        setContentView(buildSettingsScreen());
    }

    private View buildSettingsScreen() {
        View root = inflater.inflate(R.layout.screen_settings, null);
        root.findViewById(R.id.settings_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showHomeScreen();
            }
        });

        LinearLayout column = root.findViewById(R.id.settings_column);

        column.addView(buildSettingRow("Mouse sensitivity", formatSensitivity(mouseSensitivity),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showMouseSensitivityDialog();
                    }
                }));
        column.addView(buildDivider());

        column.addView(buildSettingRow("Theme", themeModeLabel(), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showThemePickerDialog();
            }
        }));
        column.addView(buildDivider());

        column.addView(buildSettingRow("Keep screen awake", keepScreenAwake ? "On" : "Off",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        keepScreenAwake = !keepScreenAwake;
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                                .putBoolean(PREF_KEEP_AWAKE, keepScreenAwake)
                                .apply();
                        applyKeepScreenAwake();
                        showSettingsScreen();
                    }
                }));
        column.addView(buildDivider());

        column.addView(buildSettingRow("Delete key \u2192 Backspace", deleteKeyToBackspace ? "On" : "Off",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        deleteKeyToBackspace = !deleteKeyToBackspace;
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                                .putBoolean(PREF_DELETE_TO_BACKSPACE, deleteKeyToBackspace)
                                .apply();
                        showSettingsScreen();
                    }
                }));

        return root;
    }

    private View buildSettingRow(String title, String subtitle, View.OnClickListener onClick) {
        View row = inflater.inflate(R.layout.item_setting_row, null);
        ((TextView) row.findViewById(R.id.row_title)).setText(title);
        ((TextView) row.findViewById(R.id.row_subtitle)).setText(subtitle);
        row.setOnClickListener(onClick);
        return row;
    }

    private String themeModeLabel() {
        if (themeMode == THEME_MODE_DARK) {
            return "Dark";
        } else if (themeMode == THEME_MODE_LIGHT) {
            return "Light";
        }
        return "System default";
    }

    // Classic single-choice list dialog for picking one of a few
    // options -- same pattern old Android settings screens (and this
    // app's own Spinners) used for exactly this kind of choice.
    // setThemeMode() re-renders whichever screen is currently up (see
    // its onSettingsScreen branch), so there's nothing else to refresh
    // here after picking one.
    private void showThemePickerDialog() {
        final String[] labels = {"System default", "Dark", "Light"};
        final int[] modes = {THEME_MODE_SYSTEM, THEME_MODE_DARK, THEME_MODE_LIGHT};
        int checked = 0;
        for (int i = 0; i < modes.length; i++) {
            if (modes[i] == themeMode) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("Theme")
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        setThemeMode(modes[which]);
                    }
                })
                .show();
    }

    // Session ☰ > "Settings" -- mouse sensitivity plus the same
    // color/scaling/rendering options as the connection-edit dialog's
    // Advanced Settings, flattened as rows in this one popup so they
    // can be changed live mid-session. Only meaningful while
    // activeConnection is set, which is always true here (this is only
    // ever called from showVncSettingsMenu(), i.e. while in a session).
    private void appendVncSettingsMenuItems(android.view.Menu menu, int startOrder) {
        menu.add(0, MENU_ID_MOUSE_SENSITIVITY, startOrder, "Mouse sensitivity (" + formatSensitivity(mouseSensitivity) + ")");
        int colorMode = activeConnection != null ? activeConnection.colorMode : COLOR_MODE_FULL;
        int scalingMode = activeConnection != null ? activeConnection.scalingMode : SCALE_MODE_FIT;
        int renderingMode = activeConnection != null ? activeConnection.renderingMode : RENDER_MODE_NEAREST;
        menu.add(0, MENU_ID_COLOR_FULL, startOrder + 1, (colorMode == COLOR_MODE_FULL ? "\u2713 " : "") + "Color: " + COLOR_MODE_LABELS[COLOR_MODE_FULL]);
        menu.add(0, MENU_ID_COLOR_256, startOrder + 2, (colorMode == COLOR_MODE_256 ? "\u2713 " : "") + "Color: " + COLOR_MODE_LABELS[COLOR_MODE_256]);
        menu.add(0, MENU_ID_COLOR_GRAYSCALE, startOrder + 3, (colorMode == COLOR_MODE_GRAYSCALE ? "\u2713 " : "") + "Color: " + COLOR_MODE_LABELS[COLOR_MODE_GRAYSCALE]);
        menu.add(0, MENU_ID_SCALE_FIT, startOrder + 4, (scalingMode == SCALE_MODE_FIT ? "\u2713 " : "") + "Scaling: " + SCALE_MODE_LABELS[SCALE_MODE_FIT]);
        menu.add(0, MENU_ID_SCALE_STRETCH, startOrder + 5, (scalingMode == SCALE_MODE_STRETCH ? "\u2713 " : "") + "Scaling: " + SCALE_MODE_LABELS[SCALE_MODE_STRETCH]);
        menu.add(0, MENU_ID_SCALE_CENTER, startOrder + 6, (scalingMode == SCALE_MODE_CENTER ? "\u2713 " : "") + "Scaling: " + SCALE_MODE_LABELS[SCALE_MODE_CENTER]);
        menu.add(0, MENU_ID_RENDER_NEAREST, startOrder + 7, (renderingMode == RENDER_MODE_NEAREST ? "\u2713 " : "") + "Rendering: " + RENDER_MODE_LABELS[RENDER_MODE_NEAREST]);
        menu.add(0, MENU_ID_RENDER_BILINEAR, startOrder + 8, (renderingMode == RENDER_MODE_BILINEAR ? "\u2713 " : "") + "Rendering: " + RENDER_MODE_LABELS[RENDER_MODE_BILINEAR]);
    }

    // Handles a tap on any of the rows appendVncSettingsMenuItems()
    // added. Updates the live renderer immediately via
    // nativeSetRenderOptions() and persists the choice back onto the
    // saved connection profile so it's remembered next time.
    private boolean handleVncSettingsMenuItem(int itemId) {
        if (itemId == MENU_ID_MOUSE_SENSITIVITY) {
            showMouseSensitivityDialog();
            return true;
        }
        if (activeConnection == null) {
            return false;
        }
        if (itemId == MENU_ID_COLOR_FULL) {
            activeConnection.colorMode = COLOR_MODE_FULL;
        } else if (itemId == MENU_ID_COLOR_256) {
            activeConnection.colorMode = COLOR_MODE_256;
        } else if (itemId == MENU_ID_COLOR_GRAYSCALE) {
            activeConnection.colorMode = COLOR_MODE_GRAYSCALE;
        } else if (itemId == MENU_ID_SCALE_FIT) {
            activeConnection.scalingMode = SCALE_MODE_FIT;
        } else if (itemId == MENU_ID_SCALE_STRETCH) {
            activeConnection.scalingMode = SCALE_MODE_STRETCH;
        } else if (itemId == MENU_ID_SCALE_CENTER) {
            activeConnection.scalingMode = SCALE_MODE_CENTER;
        } else if (itemId == MENU_ID_RENDER_NEAREST) {
            activeConnection.renderingMode = RENDER_MODE_NEAREST;
        } else if (itemId == MENU_ID_RENDER_BILINEAR) {
            activeConnection.renderingMode = RENDER_MODE_BILINEAR;
        } else {
            return false;
        }
        nativeSetRenderOptions(activeConnection.colorMode, activeConnection.scalingMode, activeConnection.renderingMode);
        updateConnection(activeConnection);
        return true;
    }

    // The one setting that still needs a real dialog -- a slider has
    // nowhere to live inside a popup-menu row.
    private void showMouseSensitivityDialog() {
        View layout = inflater.inflate(R.layout.dialog_mouse_sensitivity, null);
        final TextView valueLabel = layout.findViewById(R.id.sens_value);
        valueLabel.setText(formatSensitivity(mouseSensitivity));

        SeekBar seekBar = layout.findViewById(R.id.sens_seekbar);
        seekBar.setProgress(sensitivityToProgress(mouseSensitivity));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                mouseSensitivity = progressToSensitivity(progress);
                valueLabel.setText(formatSensitivity(mouseSensitivity));
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putFloat(PREF_MOUSE_SENS, mouseSensitivity)
                        .apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        new AlertDialog.Builder(this)
                .setView(layout)
                .setPositiveButton("Done", null)
                .show();
    }

    private String formatSensitivity(float sens) {
        return String.format(Locale.US, "%.2fx", sens);
    }

    private int sensitivityToProgress(float sens) {
        float clamped = Math.max(MIN_SENSITIVITY, Math.min(MAX_SENSITIVITY, sens));
        return Math.round((clamped - MIN_SENSITIVITY) / (MAX_SENSITIVITY - MIN_SENSITIVITY) * 100f);
    }

    private float progressToSensitivity(int progress) {
        return MIN_SENSITIVITY + (MAX_SENSITIVITY - MIN_SENSITIVITY) * (progress / 100f);
    }

    // home_content (see activity_home.xml) already holds the FAB as its
    // one XML-declared child -- the empty-state/connections-list body
    // is inserted *beneath* it (index 0) so the FAB stays on top, and
    // any previously-inserted body is removed first rather than
    // wiping the whole container.
    private void populateHomeContent(FrameLayout content) {
        if (content.getChildCount() > 1) {
            content.removeViewAt(0);
        }
        List<Connection> connections = loadConnections();
        View body = connections.isEmpty() ? buildEmptyState() : buildConnectionsList(connections);
        content.addView(body, 0, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private View buildEmptyState() {
        View wrap = inflater.inflate(R.layout.view_empty_state, null);
        wrap.findViewById(R.id.empty_new_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showConnectionDialog(null);
            }
        });
        return wrap;
    }

    private View buildConnectionsList(List<Connection> connections) {
        View root = inflater.inflate(R.layout.view_connections_list, null);
        LinearLayout column = root.findViewById(R.id.connections_column);

        for (int i = 0; i < connections.size(); i++) {
            if (i > 0) {
                column.addView(buildDivider());
            }
            column.addView(buildConnectionCard(connections.get(i)), new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        return root;
    }

    // A 1dp hairline between list rows -- the classic ListView divider
    // look, used here in place of margins/card elevation.
    private View buildDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(color(R.color.divider));
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        return divider;
    }

    // A "container": thumbnail on the left, name + host:port in the
    // middle, a per-connection VNC-settings (gear) icon on the right.
    // Tap the card to connect; tap the gear (or long-press the card) to
    // edit or remove that profile. The thumbnail is whatever the remote
    // screen looked like the last time this connection was disconnected
    // (see captureActiveThumbnail()); until a session has actually run
    // once, there's nothing to show yet, so it falls back to a colored
    // initial-avatar instead. Static structure lives in
    // res/layout/item_connection_card.xml.
    private View buildConnectionCard(final Connection c) {
        View card = inflater.inflate(R.layout.item_connection_card, null);

        TextView thumb = card.findViewById(R.id.thumb_initial);
        String initial = c.name.length() > 0 ? c.name.substring(0, 1).toUpperCase() : "?";
        thumb.setText(initial);
        thumb.setBackground(roundedDrawable(colorForName(c.name), 0));

        Bitmap snapshot = loadThumbnail(c.id);
        if (snapshot != null) {
            // A real snapshot fully replaces the initial avatar rather
            // than sitting on top of it -- the two used to both stay
            // visible at once, which looked like the letter and the
            // screenshot were smeared together.
            thumb.setVisibility(View.GONE);
            ImageView image = card.findViewById(R.id.thumb_image);
            image.setImageBitmap(snapshot);
            image.setVisibility(View.VISIBLE);
        }

        ((TextView) card.findViewById(R.id.card_name)).setText(c.name);
        ((TextView) card.findViewById(R.id.card_subtitle)).setText(c.host + ":" + c.port);

        card.findViewById(R.id.card_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showConnectionDialog(c);
            }
        });

        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startSession(c);
            }
        });
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showConnectionDialog(c);
                return true;
            }
        });

        return card;
    }

    // Deterministic-but-varied color per connection name, so cards are
    // easy to tell apart at a glance without any real thumbnail image.
    private int colorForName(String name) {
        int hash = name.hashCode();
        float hue = Math.abs(hash) % 360;
        return Color.HSVToColor(new float[]{hue, 0.45f, 0.5f});
    }

    private void confirmDeleteConnection(final Connection c) {
        new AlertDialog.Builder(this)
                .setTitle("Remove connection")
                .setMessage("Remove \"" + c.name + "\"?")
                .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        removeConnection(c.id);
                        showHomeScreen();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- add/edit-connection dialog ("VNC settings" for a profile) ----
    // Static structure lives in res/layout/dialog_connection_edit.xml.

    // Add a new profile (existing == null) or edit one in place
    // (existing != null, pre-filled, with a Delete option). This is the
    // "VNC settings" for a single saved connection: name/host/port/
    // password -- the only things the native layer actually takes.
    private void showConnectionDialog(final Connection existing) {
        View layout = inflater.inflate(R.layout.dialog_connection_edit, null);

        ((TextView) layout.findViewById(R.id.dlg_title))
                .setText(existing == null ? "New VNC Connection" : "VNC Connection Settings");

        final EditText nameInput = layout.findViewById(R.id.field_name);
        final EditText hostInput = layout.findViewById(R.id.field_host);
        final EditText portInput = layout.findViewById(R.id.field_port);
        final EditText passwordInput = layout.findViewById(R.id.field_password);

        int existingColorMode = existing != null ? existing.colorMode : COLOR_MODE_FULL;
        int existingScalingMode = existing != null ? existing.scalingMode : SCALE_MODE_FIT;
        int existingRenderingMode = existing != null ? existing.renderingMode : RENDER_MODE_NEAREST;

        final Spinner colorModeSpinner = layout.findViewById(R.id.spinner_color);
        final Spinner scalingSpinner = layout.findViewById(R.id.spinner_scaling);
        final Spinner renderingSpinner = layout.findViewById(R.id.spinner_render);
        bindSpinner(colorModeSpinner, COLOR_MODE_LABELS, existingColorMode);
        bindSpinner(scalingSpinner, SCALE_MODE_LABELS, existingScalingMode);
        bindSpinner(renderingSpinner, RENDER_MODE_LABELS, existingRenderingMode);

        if (existing != null) {
            nameInput.setText(existing.name);
            hostInput.setText(existing.host);
            portInput.setText(String.valueOf(existing.port));
            passwordInput.setText(existing.password);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setView(layout)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String name = nameInput.getText().toString().trim();
                        String host = hostInput.getText().toString().trim();
                        String portStr = portInput.getText().toString().trim();
                        String password = passwordInput.getText().toString();
                        if (host.isEmpty()) {
                            return;
                        }
                        if (name.isEmpty()) {
                            name = host;
                        }
                        int port = portStr.isEmpty() ? 5900 : Integer.parseInt(portStr);

                        Connection c = existing != null ? existing : new Connection();
                        if (existing == null) {
                            c.id = System.currentTimeMillis();
                        }
                        c.name = name;
                        c.host = host;
                        c.port = port;
                        c.password = password;
                        c.colorMode = colorModeSpinner.getSelectedItemPosition();
                        c.scalingMode = scalingSpinner.getSelectedItemPosition();
                        c.renderingMode = renderingSpinner.getSelectedItemPosition();

                        if (existing == null) {
                            addConnection(c);
                        } else {
                            updateConnection(c);
                        }
                        showHomeScreen();
                    }
                })
                .setNegativeButton("Cancel", null);

        if (existing != null) {
            builder.setNeutralButton("Delete", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    confirmDeleteConnection(existing);
                }
            });
        }

        builder.show();
    }

    // Shared by the connection-edit dialog's three advanced-setting
    // spinners: binds the label array and pre-selects the current value.
    private void bindSpinner(Spinner spinner, String[] options, int selected) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(themedContext, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, Math.min(options.length - 1, selected)));
    }

    // ---- saved connection profiles (persisted as a JSON array) ----

    private static class Connection {
        long id;
        String name;
        String host;
        int port;
        String password;
        int colorMode = COLOR_MODE_FULL;
        int scalingMode = SCALE_MODE_FIT;
        int renderingMode = RENDER_MODE_NEAREST;
    }

    private List<Connection> loadConnections() {
        List<Connection> out = new ArrayList<>();
        String json = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_CONNECTIONS, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Connection c = new Connection();
                c.id = o.optLong("id", i);
                c.name = o.optString("name", "");
                c.host = o.optString("host", "");
                c.port = o.optInt("port", 5900);
                c.password = o.optString("password", "");
                c.colorMode = o.optInt("colorMode", COLOR_MODE_FULL);
                c.scalingMode = o.optInt("scalingMode", SCALE_MODE_FIT);
                c.renderingMode = o.optInt("renderingMode", RENDER_MODE_NEAREST);
                out.add(c);
            }
        } catch (JSONException e) {
            // Corrupt/missing prefs -- just start from an empty list.
        }
        return out;
    }

    private void saveConnections(List<Connection> connections) {
        JSONArray arr = new JSONArray();
        try {
            for (Connection c : connections) {
                JSONObject o = new JSONObject();
                o.put("id", c.id);
                o.put("name", c.name);
                o.put("host", c.host);
                o.put("port", c.port);
                o.put("password", c.password);
                o.put("colorMode", c.colorMode);
                o.put("scalingMode", c.scalingMode);
                o.put("renderingMode", c.renderingMode);
                arr.put(o);
            }
        } catch (JSONException e) {
            // Fields above never throw in practice (no cyclic structure).
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(PREF_CONNECTIONS, arr.toString())
                .apply();
    }

    private void addConnection(Connection c) {
        List<Connection> connections = loadConnections();
        connections.add(c);
        saveConnections(connections);
    }

    private void updateConnection(Connection updated) {
        List<Connection> connections = loadConnections();
        for (int i = 0; i < connections.size(); i++) {
            if (connections.get(i).id == updated.id) {
                connections.set(i, updated);
                break;
            }
        }
        saveConnections(connections);
    }

    private void removeConnection(long id) {
        List<Connection> connections = loadConnections();
        List<Connection> out = new ArrayList<>();
        for (Connection c : connections) {
            if (c.id != id) {
                out.add(c);
            }
        }
        saveConnections(out);
        //noinspection ResultOfMethodCallIgnored
        thumbnailFile(id).delete();
    }

    // ---- connection-card thumbnails: a small snapshot of the remote
    // screen captured at disconnect time (see captureActiveThumbnail()),
    // stored as a tiny app-private raw format written by native
    // (vncclient_capture_thumbnail): big-endian int32 width, int32
    // height, then width*height*4 raw RGBA bytes. No real image codec
    // needed since nothing outside this app ever reads the file. ----

    private File thumbnailFile(long connectionId) {
        File dir = new File(getFilesDir(), "thumbs");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return new File(dir, connectionId + ".rvt");
    }

    private Bitmap loadThumbnail(long connectionId) {
        File f = thumbnailFile(connectionId);
        if (!f.exists()) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
            int w = in.readInt();
            int h = in.readInt();
            if (w <= 0 || h <= 0 || (long) w * (long) h > 4_000_000L) {
                return null; // corrupt or unreasonably large -- ignore rather than OOM
            }
            int[] pixels = new int[w * h];
            byte[] row = new byte[w * 4];
            for (int y = 0; y < h; y++) {
                in.readFully(row);
                for (int x = 0; x < w; x++) {
                    int o = x * 4;
                    int r = row[o] & 0xFF;
                    int g = row[o + 1] & 0xFF;
                    int b = row[o + 2] & 0xFF;
                    int a = row[o + 3] & 0xFF;
                    pixels[y * w + x] = (a << 24) | (r << 16) | (g << 8) | b;
                }
            }
            return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888);
        } catch (IOException e) {
            return null;
        }
    }

    // Asks native to snapshot the current remote framebuffer to
    // activeConnection's thumbnail file. Safe to call even if there's
    // no live connection (native just no-ops).
    private void captureActiveThumbnail() {
        if (activeConnection == null) {
            return;
        }
        nativeCaptureThumbnail(thumbnailFile(activeConnection.id).getAbsolutePath(), THUMBNAIL_MAX_DIM);
    }

    // ======================================================================
    // Session screen: the actual VNC surface + Termux-style extra-keys
    // toolbar. Reached by tapping a saved connection on the home screen.
    // ======================================================================

    private void startSession(Connection c) {
        inSession = true;
        onSettingsScreen = false;
        activeConnection = c;
        setContentView(buildSessionView());
        nativeSetRenderOptions(c.colorMode, c.scalingMode, c.renderingMode);
        nativeConnect(c.host, c.port, c.password);
    }

    // Structure lives in res/layout/activity_session.xml; this wires up
    // the surface/status pill/key-capture field and builds the toolbar.
    private View buildSessionView() {
        ctrlLocked = false;
        ctrlArmed = false;
        altLocked = false;
        altArmed = false;
        shiftLocked = false;
        shiftArmed = false;
        currentToolbarPage = 1;
        customKeys = loadCustomKeys();

        View root = inflater.inflate(R.layout.activity_session, null);
        sessionRoot = (FrameLayout) root;

        surfaceView = root.findViewById(R.id.surface_view);
        surfaceView.getHolder().addCallback(this);

        statusText = root.findViewById(R.id.status_text);
        statusText.setBackgroundResource(R.drawable.bg_status_idle);
        statusText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                wakeStatusPill();
            }
        });

        // Off-screen EditText that exists only to capture soft-keyboard
        // input -- toggled focused/hidden by the keyboard button below.
        // No suggestions/autocorrect/autocapitalize (set in XML) -- any
        // of those would rewrite or batch characters before they reach
        // us, breaking the assumption that each keystroke forwards 1:1.
        keyCaptureInput = root.findViewById(R.id.key_capture_input);
        wireKeyCapture();

        toolbarView = buildToolbar();
        toolbarContainer = root.findViewById(R.id.toolbar_container);
        toolbarContainer.addView(toolbarView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        return root;
    }

    // Forwards typed characters and backspace/enter from the hidden
    // EditText to native as key events. Printable ASCII's keysym is
    // just its character code, so no lookup table is needed there.
    private void wireKeyCapture() {
        keyCaptureInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (count > 0) {
                    for (int i = start; i < start + count; i++) {
                        char c = s.charAt(i);
                        sendKeyTap((int) c); // routes through any armed/locked Ctrl or Alt
                    }
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
                // Clear immediately so the field never fills up -- we
                // only care about each keystroke as it happens.
                s.clear();
            }
        });

        keyCaptureInput.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                int keysym;
                if (keyCode == KeyEvent.KEYCODE_DEL) {
                    keysym = XK_BackSpace;
                } else if (keyCode == KeyEvent.KEYCODE_ENTER) {
                    keysym = XK_Return;
                } else if (keyCode == KeyEvent.KEYCODE_FORWARD_DEL) {
                    // A local (often external/bluetooth) keyboard's
                    // dedicated Delete key. Some devices don't route
                    // this the way the user expects, so Settings offers
                    // remapping it to Backspace instead of the remote
                    // Delete keysym.
                    keysym = deleteKeyToBackspace ? XK_BackSpace : XK_Delete;
                } else {
                    return false; // let normal character input go through the TextWatcher
                }
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    nativeKeyEvent(keysym, true);
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    nativeKeyEvent(keysym, false);
                }
                return true;
            }
        });
    }

    // ---- toolbar: an actual recreation of Termux's ExtraKeysView --
    // two rows of keys, transparent by default, flashing a flat solid
    // gray while held (no ripple animation), no dividers between keys.
    // The pressed-state flash lives in res/drawable/toolbar_key_bg.xml
    // now (a plain state-list selector) rather than a manual
    // OnTouchListener. Ctrl/Alt behave like Termux's real modifier
    // keys: a tap arms it for just the next key you send (auto-releases
    // after), a long-press locks it down until you tap it again to
    // release. ----

    private boolean ctrlLocked = false;
    private boolean ctrlArmed = false;
    private boolean altLocked = false;
    private boolean altArmed = false;
    private boolean shiftLocked = false;
    private boolean shiftArmed = false;

    // ---- paged toolbar shell: a thin dot-strip up top (tap a dot to
    // jump to that page, same as Termux's extra-keys popup) plus a
    // swipeable page area below it. Page 0 is the user's custom keys,
    // page 1 is the main nav/modifier keys, page 2 is F-keys + power. ----

    private View buildToolbar() {
        View container = inflater.inflate(R.layout.view_toolbar, null);
        bindPageDots(container);

        SwipePageLayout pager = new SwipePageLayout(this);
        toolbarPages = new View[]{
                buildCustomKeysPage(),
                buildMainKeysPage(),
                buildFunctionKeysPage(),
        };
        for (int i = 0; i < toolbarPages.length; i++) {
            toolbarPages[i].setVisibility(i == currentToolbarPage ? View.VISIBLE : View.GONE);
            pager.addView(toolbarPages[i], new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }
        // SwipePageLayout is a non-static inner class (it needs the
        // enclosing MainActivity for showToolbarPage()/updateDotVisuals()),
        // so unlike everything else here it can't be declared directly in
        // XML -- it's built in code and dropped into the placeholder
        // FrameLayout that view_toolbar.xml reserves for it.
        FrameLayout pagerSlot = container.findViewById(R.id.toolbar_pager);
        pagerSlot.addView(pager, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        updateDotVisuals();
        updateModifierVisuals();
        return container;
    }

    // There are always exactly three toolbar pages, so the dots are
    // just three fixed TextViews in view_toolbar.xml rather than a
    // programmatically-built row.
    private void bindPageDots(View toolbarRoot) {
        pageDots = new TextView[]{
                toolbarRoot.findViewById(R.id.dot0),
                toolbarRoot.findViewById(R.id.dot1),
                toolbarRoot.findViewById(R.id.dot2),
        };
        for (int i = 0; i < pageDots.length; i++) {
            final int pageIndex = i;
            pageDots[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showToolbarPage(pageIndex);
                }
            });
        }
    }

    // Switches the visible toolbar page (called from a dot tap or a
    // swipe). Pages are plain siblings in a FrameLayout with all but
    // the current one GONE -- simplest thing that works without pulling
    // in a ViewPager dependency this no-build-system project doesn't have.
    private void showToolbarPage(int index) {
        if (toolbarPages == null) {
            return;
        }
        index = Math.max(0, Math.min(toolbarPages.length - 1, index));
        if (index == currentToolbarPage) {
            return;
        }
        toolbarPages[currentToolbarPage].setVisibility(View.GONE);
        currentToolbarPage = index;
        toolbarPages[currentToolbarPage].setVisibility(View.VISIBLE);
        updateDotVisuals();
    }

    private void updateDotVisuals() {
        if (pageDots == null) {
            return;
        }
        for (int i = 0; i < pageDots.length; i++) {
            boolean active = i == currentToolbarPage;
            pageDots[i].setTextColor(active ? color(R.color.color_accent) : color(R.color.text_dim));
            pageDots[i].setTextSize(active ? 11 : 7);
        }
    }

    // A FrameLayout that steals a horizontal drag away from its
    // (clickable) key children once the gesture clearly reads as a
    // swipe rather than a tap, and turns the drag into a live-following
    // slide -- the previous version only snapped pages on release with
    // no visual feedback while dragging, which is what made swiping
    // feel like discrete taps ("typing") instead of a smooth gesture.
    // This one drags the current page and the revealed neighbor page
    // together under the finger, then animates the rest of the way to
    // whichever page wins once the finger lifts -- a minimal hand-rolled
    // stand-in for ViewPager.
    private class SwipePageLayout extends FrameLayout {
        private float downX, downY;
        private boolean dragging;
        // Index of the neighbor page currently slid in next to the
        // current one, or -1 if we're dragging past an edge (first/last
        // page) with no neighbor to reveal.
        private int dragTargetIndex = -1;
        private ValueAnimator settleAnimator;

        SwipePageLayout(Context context) {
            super(context);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getX();
                    downY = ev.getY();
                    dragging = false;
                    if (settleAnimator != null) {
                        settleAnimator.cancel();
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dx = ev.getX() - downX;
                    float dy = ev.getY() - downY;
                    if (!dragging && Math.abs(dx) > touchSlopPx && Math.abs(dx) > Math.abs(dy)) {
                        dragging = true;
                        beginDrag();
                        return true; // steal from the key that was about to claim this touch
                    }
                    break;
                default:
                    break;
            }
            return false;
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    if (dragging) {
                        applyDrag(ev.getX() - downX);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) {
                        settleDrag(ev.getX() - downX);
                    }
                    dragging = false;
                    break;
                default:
                    break;
            }
            return true;
        }

        // A negative drag (finger moving left) reveals the next page;
        // a positive drag reveals the previous one. Slides the target
        // in just off-screen on the correct side so it's ready to
        // follow the finger from frame one of the drag.
        private void beginDrag() {
            dragTargetIndex = -1; // resolved lazily in applyDrag once we know direction
        }

        private void applyDrag(float dx) {
            int width = getWidth();
            if (width <= 0) {
                return;
            }
            int direction = dx < 0 ? 1 : -1; // swiping left (negative dx) moves toward the next page
            int candidate = currentToolbarPage + direction;
            if (candidate < 0 || candidate >= toolbarPages.length) {
                candidate = -1; // at an edge -- nothing to reveal that way
            }
            if (candidate != dragTargetIndex) {
                // Direction changed mid-drag (or this is the first
                // move): hide whatever neighbor was previously staged
                // and stage the correct one instead.
                if (dragTargetIndex != -1) {
                    toolbarPages[dragTargetIndex].setVisibility(View.GONE);
                    toolbarPages[dragTargetIndex].setTranslationX(0f);
                }
                dragTargetIndex = candidate;
                if (dragTargetIndex != -1) {
                    toolbarPages[dragTargetIndex].setTranslationX(direction * width);
                    toolbarPages[dragTargetIndex].setVisibility(View.VISIBLE);
                }
            }

            // Rubber-band resistance at an edge with nothing to reveal,
            // so the drag doesn't feel like it's doing nothing.
            float effectiveDx = dragTargetIndex == -1 ? dx * 0.3f : dx;
            toolbarPages[currentToolbarPage].setTranslationX(effectiveDx);
            // The revealed neighbor starts fully off-screen on the
            // correct side (±width) and slides toward 0 as the drag
            // grows -- NOT (drag - direction*width), which was the bug
            // that sent it rocketing off to some unrelated position
            // instead of tracking the finger.
            if (dragTargetIndex != -1) {
                toolbarPages[dragTargetIndex].setTranslationX(direction * width + effectiveDx);
            }
        }

        // Finger lifted: finish the transition by animating both pages
        // the rest of the way, landing on the new page if the drag
        // passed the halfway point (or had enough velocity-by-proxy,
        // approximated here by distance alone -- simple and good enough
        // for a toolbar-width swipe), otherwise springing back.
        private void settleDrag(float dx) {
            final int width = getWidth();
            final int fromIndex = currentToolbarPage;
            final int targetIndex = dragTargetIndex;
            boolean pastThreshold = width > 0 && Math.abs(dx) > width * 0.3f;
            final boolean committing = pastThreshold && targetIndex != -1;
            final int direction = dx < 0 ? 1 : -1;

            final float fromStart = toolbarPages[fromIndex].getTranslationX();
            final float fromEnd = committing ? -direction * width : 0f;
            final float targetStart = targetIndex != -1 ? toolbarPages[targetIndex].getTranslationX() : 0f;
            final float targetEnd = committing ? 0f : direction * width;

            settleAnimator = ValueAnimator.ofFloat(0f, 1f);
            settleAnimator.setDuration(180);
            settleAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    float t = (float) animation.getAnimatedValue();
                    toolbarPages[fromIndex].setTranslationX(lerp(fromStart, fromEnd, t));
                    if (targetIndex != -1) {
                        toolbarPages[targetIndex].setTranslationX(lerp(targetStart, targetEnd, t));
                    }
                }
            });
            settleAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    toolbarPages[fromIndex].setTranslationX(0f);
                    if (targetIndex != -1) {
                        toolbarPages[targetIndex].setTranslationX(0f);
                    }
                    if (committing) {
                        toolbarPages[fromIndex].setVisibility(View.GONE);
                        currentToolbarPage = targetIndex;
                        updateDotVisuals();
                    } else if (targetIndex != -1) {
                        toolbarPages[targetIndex].setVisibility(View.GONE);
                    }
                    dragTargetIndex = -1;
                }
            });
            settleAnimator.start();
        }

        private float lerp(float a, float b, float t) {
            return a + (b - a) * t;
        }
    }

    // ---- page 1 (main): the original nav/modifier row set, matching
    // the Termux ExtraKeysView "primary" page. ----
    private View buildMainKeysPage() {
        View page = inflater.inflate(R.layout.view_toolbar_main_keys, null);

        page.findViewById(R.id.key_esc).setOnClickListener(tapKeyListener(XK_Escape));
        page.findViewById(R.id.key_kbd).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleSoftKeyboard();
            }
        });
        shiftButton = page.findViewById(R.id.key_shift);
        shiftButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onModifierTap(MOD_SHIFT);
            }
        });
        shiftButton.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                onModifierLongPress(MOD_SHIFT);
                return true;
            }
        });
        page.findViewById(R.id.key_pgup).setOnClickListener(tapKeyListener(XK_Page_Up));
        page.findViewById(R.id.key_pgdn).setOnClickListener(tapKeyListener(XK_Page_Down));
        page.findViewById(R.id.key_home).setOnClickListener(tapKeyListener(XK_Home));
        page.findViewById(R.id.key_up).setOnClickListener(tapKeyListener(XK_Up));
        page.findViewById(R.id.key_end).setOnClickListener(tapKeyListener(XK_End));

        ctrlButton = page.findViewById(R.id.key_ctrl);
        ctrlButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onModifierTap(MOD_CTRL);
            }
        });
        ctrlButton.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                onModifierLongPress(MOD_CTRL);
                return true;
            }
        });
        page.findViewById(R.id.key_super).setOnClickListener(tapKeyListener(XK_Super_L));
        altButton = page.findViewById(R.id.key_alt);
        altButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onModifierTap(MOD_ALT);
            }
        });
        altButton.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                onModifierLongPress(MOD_ALT);
                return true;
            }
        });
        page.findViewById(R.id.key_delfwd).setOnClickListener(tapKeyListener(XK_Delete));
        final TextView menuKey = page.findViewById(R.id.key_menu);
        menuKey.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSessionMenu(menuKey);
            }
        });
        page.findViewById(R.id.key_left).setOnClickListener(tapKeyListener(XK_Left));
        page.findViewById(R.id.key_down).setOnClickListener(tapKeyListener(XK_Down));
        page.findViewById(R.id.key_right).setOnClickListener(tapKeyListener(XK_Right));

        return page;
    }

    // ---- page 2 (right, swipe left from main): function keys, plus
    // power/disconnect and the three symbol keys that don't have a home
    // on a phone keyboard (~ / \). ----
    private View buildFunctionKeysPage() {
        View page = inflater.inflate(R.layout.view_toolbar_function_keys, null);

        int[] fKeyIds = {
                R.id.key_f1, R.id.key_f2, R.id.key_f3, R.id.key_f4,
                R.id.key_f5, R.id.key_f6, R.id.key_f7, R.id.key_f8,
                R.id.key_f9, R.id.key_f10, R.id.key_f11, R.id.key_f12,
        };
        for (int i = 0; i < fKeyIds.length; i++) {
            page.findViewById(fKeyIds[i]).setOnClickListener(tapKeyListener(XK_F1 + i));
        }
        page.findViewById(R.id.key_tilde).setOnClickListener(tapKeyListener((int) '~'));
        page.findViewById(R.id.key_slash).setOnClickListener(tapKeyListener((int) '/'));
        page.findViewById(R.id.key_backslash).setOnClickListener(tapKeyListener((int) '\\'));
        page.findViewById(R.id.key_power).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDisconnect();
            }
        });

        return page;
    }

    // ---- page 0 (left, swipe right from main): user-defined buttons.
    // Tap sends the assigned text; long-press renames/reassigns it.
    // Persisted the same way saved connections are (a JSON array in
    // SharedPreferences), see loadCustomKeys/saveCustomKeys below. ----
    private View buildCustomKeysPage() {
        View page = inflater.inflate(R.layout.view_toolbar_custom_keys, null);
        LinearLayout row1 = page.findViewById(R.id.custom_row1);
        LinearLayout row2 = page.findViewById(R.id.custom_row2);

        if (customKeys == null) {
            customKeys = loadCustomKeys();
        }
        for (int i = 0; i < customKeys.size(); i++) {
            LinearLayout row = i < (customKeys.size() + 1) / 2 ? row1 : row2;
            addCustomKeyButton(row, i);
        }

        return page;
    }

    private void addCustomKeyButton(LinearLayout row, final int index) {
        final CustomKey ck = customKeys.get(index);
        addToolbarKey(row, ck.label, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (ck.useKeysym) {
                    sendKeyTap(ck.keysym);
                } else {
                    sendKeyTapString(ck.text);
                }
            }
        }, new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showEditCustomKeyDialog(index);
                return true;
            }
        });
    }

    // Long-press a custom button to rename it and change what it sends.
    // Either fill in TEXT TO SEND (typed out character by character) or
    // KEYSYM (HEX) to send a single raw X11 keysym instead -- useful for
    // keys with no printable character at all (media keys, F13+,
    // anything in rfb/keysym.h upstream). The hex field wins if both are
    // filled in.
    private void showEditCustomKeyDialog(final int index) {
        final CustomKey ck = customKeys.get(index);

        View layout = inflater.inflate(R.layout.dialog_edit_custom_key, null);
        final EditText labelInput = layout.findViewById(R.id.key_label);
        labelInput.setText(ck.label);

        final EditText textInput = layout.findViewById(R.id.key_text);
        textInput.setText(ck.useKeysym ? "" : ck.text);

        final EditText keysymInput = layout.findViewById(R.id.key_keysym);
        keysymInput.setText(ck.useKeysym ? Integer.toHexString(ck.keysym) : "");

        new AlertDialog.Builder(this)
                .setView(layout)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String label = labelInput.getText().toString().trim();
                        String text = textInput.getText().toString();
                        String hex = keysymInput.getText().toString().trim();
                        if (hex.startsWith("0x") || hex.startsWith("0X")) {
                            hex = hex.substring(2);
                        }
                        if (!label.isEmpty()) {
                            ck.label = label;
                        }
                        if (hex.isEmpty()) {
                            ck.useKeysym = false;
                            ck.text = text;
                        } else {
                            try {
                                ck.keysym = Integer.parseInt(hex, 16);
                                ck.useKeysym = true;
                            } catch (NumberFormatException e) {
                                // Bad hex -- fall back to the text field
                                // rather than silently keeping whatever
                                // keysym was set before.
                                ck.useKeysym = false;
                                ck.text = text;
                            }
                        }
                        saveCustomKeys(customKeys);
                        refreshToolbar();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // Swaps in a freshly-built toolbar so an edited custom-key
    // label/value shows up immediately, without touching the surface or
    // the live native connection underneath it.
    private void refreshToolbar() {
        if (!inSession || toolbarContainer == null || toolbarView == null) {
            return;
        }
        toolbarContainer.removeView(toolbarView);
        toolbarView = buildToolbar();
        toolbarContainer.addView(toolbarView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private static class CustomKey {
        String label;
        String text;
        int keysym;
        boolean useKeysym;

        CustomKey(String label, String text) {
            this.label = label;
            this.text = text;
            this.useKeysym = false;
        }

        CustomKey(String label, int keysym) {
            this.label = label;
            this.text = "";
            this.keysym = keysym;
            this.useKeysym = true;
        }
    }

    private List<CustomKey> loadCustomKeys() {
        List<CustomKey> out = new ArrayList<>();
        String json = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_CUSTOM_KEYS, "");
        if (json.isEmpty()) {
            return defaultCustomKeys();
        }
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String label = o.optString("label", "?");
                boolean useKeysym = o.optBoolean("useKeysym", false);
                CustomKey ck = useKeysym
                        ? new CustomKey(label, o.optInt("keysym", 0))
                        : new CustomKey(label, o.optString("text", ""));
                out.add(ck);
            }
        } catch (JSONException e) {
            return defaultCustomKeys();
        }
        return out;
    }

    private void saveCustomKeys(List<CustomKey> keys) {
        JSONArray arr = new JSONArray();
        try {
            for (CustomKey ck : keys) {
                JSONObject o = new JSONObject();
                o.put("label", ck.label);
                o.put("text", ck.text);
                o.put("keysym", ck.keysym);
                o.put("useKeysym", ck.useKeysym);
                arr.put(o);
            }
        } catch (JSONException e) {
            // Fields above never throw in practice (no cyclic structure).
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(PREF_CUSTOM_KEYS, arr.toString())
                .apply();
    }

    // Starting set of custom buttons -- common shell/path symbols that
    // don't otherwise have a home on the toolbar. Long-press any of
    // them to make it your own.
    private List<CustomKey> defaultCustomKeys() {
        List<CustomKey> out = new ArrayList<>();
        String[] labels = {"-", "_", ":", ".", "=", "\"", "'", "$"};
        for (String s : labels) {
            out.add(new CustomKey(s, s));
        }
        return out;
    }

    // Sends a short run of characters as individual taps, e.g. for a
    // multi-character custom button. Each char still gets wrapped by
    // any armed/locked modifiers via sendKeyTap.
    private void sendKeyTapString(String text) {
        for (int i = 0; i < text.length(); i++) {
            sendKeyTap(text.charAt(i));
        }
    }

    private View.OnClickListener tapKeyListener(final int keysym) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendKeyTap(keysym);
            }
        };
    }

    // Adds one flat, evenly-weighted key from the view_toolbar_key.xml
    // template. Transparent at rest; flashes @color/key_pressed while
    // the finger is down (matches Termux's ExtraKeysView exactly -- a
    // plain background swap, not a ripple -- via the state-list
    // selector in res/drawable/toolbar_key_bg.xml).
    private TextView addToolbarKey(LinearLayout row, String label, View.OnClickListener clickListener,
                                     View.OnLongClickListener longClickListener) {
        TextView key = (TextView) inflater.inflate(R.layout.view_toolbar_key, row, false);
        key.setText(label);
        key.setOnClickListener(clickListener);
        if (longClickListener != null) {
            key.setOnLongClickListener(longClickListener);
        }
        row.addView(key);
        return key;
    }

    // Modifier ids for the onModifier*/sendKeyTap plumbing below.
    private static final int MOD_CTRL = 0;
    private static final int MOD_ALT = 1;
    private static final int MOD_SHIFT = 2;

    private int modifierKeysym(int mod) {
        switch (mod) {
            case MOD_CTRL: return XK_Control_L;
            case MOD_ALT: return XK_Alt_L;
            default: return XK_Shift_L;
        }
    }

    private boolean isModArmed(int mod) {
        switch (mod) {
            case MOD_CTRL: return ctrlArmed;
            case MOD_ALT: return altArmed;
            default: return shiftArmed;
        }
    }

    private boolean isModLocked(int mod) {
        switch (mod) {
            case MOD_CTRL: return ctrlLocked;
            case MOD_ALT: return altLocked;
            default: return shiftLocked;
        }
    }

    private void setModArmed(int mod, boolean armed) {
        switch (mod) {
            case MOD_CTRL: ctrlArmed = armed; break;
            case MOD_ALT: altArmed = armed; break;
            default: shiftArmed = armed; break;
        }
    }

    private void setModLocked(int mod, boolean locked) {
        switch (mod) {
            case MOD_CTRL: ctrlLocked = locked; break;
            case MOD_ALT: altLocked = locked; break;
            default: shiftLocked = locked; break;
        }
    }

    // Tap: arm the modifier for just the next key sent through
    // sendKeyTap() (auto-releases itself right after). Tapping again
    // while armed cancels it. Tapping while locked releases the lock.
    private void onModifierTap(int mod) {
        if (isModLocked(mod)) {
            setModLocked(mod, false);
            nativeKeyEvent(modifierKeysym(mod), false);
        } else {
            setModArmed(mod, !isModArmed(mod));
        }
        updateModifierVisuals();
    }

    // Long-press: lock the modifier down until it's tapped again.
    private void onModifierLongPress(int mod) {
        if (isModLocked(mod)) {
            setModLocked(mod, false);
            nativeKeyEvent(modifierKeysym(mod), false);
        } else {
            setModArmed(mod, false);
            setModLocked(mod, true);
            nativeKeyEvent(modifierKeysym(mod), true);
        }
        updateModifierVisuals();
    }

    // Sends a single key tap (down+up), wrapping it with any armed
    // Ctrl/Alt/Shift so e.g. tap CTRL then tap C sends Ctrl+C. Locked
    // modifiers are already held down separately and aren't touched
    // here. Everything that can produce a keystroke -- toolbar taps and
    // typed soft-keyboard characters alike -- routes through this.
    private void sendKeyTap(int keysym) {
        boolean ctrlOneShot = ctrlArmed && !ctrlLocked;
        boolean altOneShot = altArmed && !altLocked;
        boolean shiftOneShot = shiftArmed && !shiftLocked;
        if (ctrlOneShot) {
            nativeKeyEvent(XK_Control_L, true);
        }
        if (altOneShot) {
            nativeKeyEvent(XK_Alt_L, true);
        }
        if (shiftOneShot) {
            nativeKeyEvent(XK_Shift_L, true);
        }
        nativeKeyEvent(keysym, true);
        nativeKeyEvent(keysym, false);
        if (ctrlOneShot) {
            nativeKeyEvent(XK_Control_L, false);
            ctrlArmed = false;
        }
        if (altOneShot) {
            nativeKeyEvent(XK_Alt_L, false);
            altArmed = false;
        }
        if (shiftOneShot) {
            nativeKeyEvent(XK_Shift_L, false);
            shiftArmed = false;
        }
        if (ctrlOneShot || altOneShot || shiftOneShot) {
            updateModifierVisuals();
        }
    }

    // Idle: plain white label. Armed (one-shot pending): accent color.
    // Locked: accent color, bold -- same three-state look Termux uses.
    private void updateModifierVisuals() {
        updateModifierKeyVisual(ctrlButton, ctrlLocked, ctrlArmed);
        updateModifierKeyVisual(altButton, altLocked, altArmed);
        updateModifierKeyVisual(shiftButton, shiftLocked, shiftArmed);
    }

    private void updateModifierKeyVisual(TextView key, boolean locked, boolean armed) {
        if (key == null) {
            return;
        }
        if (locked) {
            key.setTextColor(color(R.color.color_accent));
            key.setTypeface(Typeface.DEFAULT_BOLD);
        } else if (armed) {
            key.setTextColor(color(R.color.color_accent));
            key.setTypeface(Typeface.DEFAULT);
        } else {
            key.setTextColor(color(R.color.text_primary));
            key.setTypeface(Typeface.DEFAULT);
        }
    }

    private void toggleSoftKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        keyCaptureInput.requestFocus();
        imm.showSoftInput(keyCaptureInput, InputMethodManager.SHOW_FORCED);
    }

    private void wakeStatusPill() {
        uiHandler.removeCallbacks(statusFadeRunnable);
        statusText.animate().alpha(1f).setDuration(150).start();
        if (lastStatus == STATUS_CONNECTED) {
            uiHandler.postDelayed(statusFadeRunnable, STATUS_FADE_DELAY_MS);
        }
    }

    // ---- session ☰ menu: reachable mid-session without leaving the
    // remote screen, unlike the home screen's ☰ (which only makes sense
    // when there's no live connection to disturb). ----

    // The session ☰: just three rows now -- Connection details,
    // Settings (opens the cascading popup below with the actual VNC
    // display options), and Disconnect. Those display options used to
    // be flattened directly into this popup, which made it an 11-row
    // wall of text; they now live behind "Settings" only.
    private void showSessionMenu(final View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, MENU_ID_CONNECTION_DETAILS, 0, "Connection details");
        menu.getMenu().add(0, MENU_ID_VNC_SETTINGS, 1, "Settings");
        menu.getMenu().add(0, MENU_ID_DISCONNECT, 2, "Disconnect");
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                int id = item.getItemId();
                if (id == MENU_ID_CONNECTION_DETAILS) {
                    showConnectionDetailsDialog();
                    return true;
                } else if (id == MENU_ID_VNC_SETTINGS) {
                    showVncSettingsMenu(anchor);
                    return true;
                } else if (id == MENU_ID_DISCONNECT) {
                    confirmDisconnect();
                    return true;
                }
                return false;
            }
        });
        menu.show();
    }

    // The session ☰'s "Settings" row: mouse sensitivity plus the same
    // color/scaling/rendering options as the connection-edit dialog's
    // Advanced Settings, opened as a second popup anchored at the same
    // ☰ button. A second freshly-built PopupMenu rather than a real
    // Menu#addSubMenu() -- PopupMenu's SubMenu/cascading-menu rendering
    // isn't reliable across Android versions, and this looks the same
    // to the user for one extra tap.
    private void showVncSettingsMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        appendVncSettingsMenuItems(menu.getMenu(), 0);
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                return handleVncSettingsMenuItem(item.getItemId());
            }
        });
        menu.show();
    }

    // Read-only metadata about the live remote desktop -- what the
    // server itself reports (name, resolution, color depth), not our
    // own bookkeeping about the connection (that's what the card/edit
    // screen on the home list is for). Disconnect lives as its own ☰
    // item now (see showSessionMenu()), not a button in here.
    private void showConnectionDetailsDialog() {
        View layout = inflater.inflate(R.layout.dialog_connection_details, null);

        // "desktopName|width|height|bitsPerPixel" -- see
        // nativeGetSessionInfo()/vncclient_get_session_info().
        String[] parts = nativeGetSessionInfo().split("\\|");
        String desktopName = parts.length > 0 ? parts[0] : "-";
        String resolution = parts.length > 2 ? (parts[1] + " \u00d7 " + parts[2]) : "-";
        String colorDepth = parts.length > 3 ? (parts[3] + "-bit") : "-";

        ((TextView) layout.findViewById(R.id.detail_desktop_name)).setText(desktopName);
        ((TextView) layout.findViewById(R.id.detail_resolution)).setText(resolution);
        ((TextView) layout.findViewById(R.id.detail_color_depth)).setText(colorDepth);

        new AlertDialog.Builder(this)
                .setView(layout)
                .setPositiveButton("Close", null)
                .show();
    }

    // Asks for confirmation before tearing down the live session and
    // returning to the connections list. Wired to both the toolbar's
    // power/disconnect key and the system back button while in session.
    private void confirmDisconnect() {
        new AlertDialog.Builder(this)
                .setTitle("Disconnect")
                .setMessage("Disconnect from this VNC session?")
                .setPositiveButton("Disconnect", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        captureActiveThumbnail(); // grab a snapshot while the frame is still live
                        nativeDisconnect();
                        showHomeScreen();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // Called from native (jni_notify_status in jni_bridge.c), possibly
    // from the background connection thread -- so hop to the UI thread
    // before touching any views.
    public void onNativeStatus(final int status, final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                lastStatus = status;
                lastStatusMessage = message;
                if (status == STATUS_DISCONNECTED) {
                    // The server hung up on us rather than us choosing
                    // to disconnect -- still worth grabbing whatever
                    // was last on screen for the card thumbnail.
                    captureActiveThumbnail();
                }
                applyStatusToPill(status, message);
            }
        });
    }

    // Paints statusText for a given status/message and (re)starts the
    // fade-to-low-key timer. Split out of onNativeStatus so a mid-session
    // theme change (see setThemeMode(), which rebuilds the SurfaceView
    // and therefore statusText too) can restore the real last-known
    // status immediately instead of it defaulting back to "Not
    // connected" until the next actual native callback.
    private void applyStatusToPill(int status, String message) {
        if (statusText == null) {
            return; // status arrived after we navigated back home
        }
        statusText.setText(message);
        switch (status) {
            case STATUS_CONNECTING:
                statusText.setBackgroundResource(R.drawable.bg_status_connecting);
                break;
            case STATUS_CONNECTED:
                statusText.setBackgroundResource(R.drawable.bg_status_connected);
                break;
            case STATUS_FAILED:
            case STATUS_DISCONNECTED:
                statusText.setBackgroundResource(R.drawable.bg_status_error);
                break;
            default:
                statusText.setBackgroundResource(R.drawable.bg_status_idle);
                break;
        }
        // Once connected, the pill has done its job -- fade it to a
        // low-key glance rather than sitting fully opaque over the
        // remote screen forever. Tapping it (see wakeStatusPill) brings
        // it back briefly.
        wakeStatusPill();
    }

    @Override
    public void onBackPressed() {
        if (inSession) {
            confirmDisconnect();
        } else if (onSettingsScreen) {
            showHomeScreen();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (inSession) {
            nativeDisconnect();
        }
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

    // ---- touch forwarding: relative "touchpad" cursor, not warp-to-touch ----
    //
    // One finger, small move: reposition the cursor by delta (hover),
    //   no button pressed -- lets you line the cursor up without
    //   clicking, same as sliding a finger on a laptop trackpad.
    // One finger, released quickly without much movement: a tap, sent
    //   as a left click at wherever the cursor currently is.
    // One finger, held in place past LONG_PRESS_MS: latches the left
    //   button down (like a trackpad's tap-and-hold-to-drag) so the
    //   following moves drag instead of just hovering; release lifts
    //   the button.
    // Two fingers, moved together vertically: scroll wheel.
    // Two fingers, tapped without much movement: right click.

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int actionMasked = event.getActionMasked();
        int pointerCount = event.getPointerCount();

        switch (actionMasked) {
            case MotionEvent.ACTION_DOWN:
                beginSingleFingerTouch(event.getX(), event.getY());
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (pointerCount == 2) {
                    beginTwoFingerTouch(event);
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (twoFingerMode && pointerCount >= 2) {
                    handleTwoFingerMove(event);
                } else if (!twoFingerMode && pointerCount == 1) {
                    handleSingleFingerMove(event.getX(), event.getY());
                }
                break;

            case MotionEvent.ACTION_POINTER_UP:
                if (pointerCount == 2) {
                    endTwoFingerTouch();
                    // Avoid a jump if one finger keeps going after the
                    // other lifts: re-baseline single-finger tracking
                    // to wherever the remaining finger already is, and
                    // treat the gesture as already-moved so lifting it
                    // later doesn't fire a spurious tap.
                    int upIndex = event.getActionIndex();
                    int remainingIndex = upIndex == 0 ? 1 : 0;
                    if (remainingIndex < pointerCount) {
                        lastTouchX = event.getX(remainingIndex);
                        lastTouchY = event.getY(remainingIndex);
                    }
                    singleFingerMoved = true;
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                twoFingerMode = false;
                endSingleFingerTouch();
                break;

            default:
                break;
        }
        return true;
    }

    private void beginSingleFingerTouch(float x, float y) {
        lastTouchX = x;
        lastTouchY = y;
        totalMoveDistance = 0f;
        singleFingerMoved = false;
        dragMode = false;
        cancelLongPress();
        longPressRunnable = new Runnable() {
            @Override
            public void run() {
                dragMode = true;
                nativeCursorButton(1, true);
                surfaceView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            }
        };
        uiHandler.postDelayed(longPressRunnable, LONG_PRESS_MS);
    }

    private void handleSingleFingerMove(float x, float y) {
        float dx = x - lastTouchX;
        float dy = y - lastTouchY;
        lastTouchX = x;
        lastTouchY = y;
        totalMoveDistance += Math.abs(dx) + Math.abs(dy);
        if (!singleFingerMoved && totalMoveDistance > touchSlopPx) {
            singleFingerMoved = true;
            cancelLongPress(); // a deliberate move, not a hold -- don't latch a drag later
        }
        nativeCursorDelta(dx * mouseSensitivity, dy * mouseSensitivity);
    }

    private void endSingleFingerTouch() {
        cancelLongPress();
        if (dragMode) {
            nativeCursorButton(1, false);
            dragMode = false;
        } else if (!singleFingerMoved) {
            nativeCursorTap(1);
        }
    }

    private void cancelLongPress() {
        if (longPressRunnable != null) {
            uiHandler.removeCallbacks(longPressRunnable);
        }
    }

    private void beginTwoFingerTouch(MotionEvent event) {
        cancelLongPress();
        if (dragMode) {
            nativeCursorButton(1, false);
            dragMode = false;
        }
        twoFingerMode = true;
        twoFingerMoved = false;
        twoFingerScrollAccum = 0f;
        twoFingerDownTime = System.currentTimeMillis();
        lastTwoFingerAvgY = averageY(event);
    }

    private void handleTwoFingerMove(MotionEvent event) {
        float avgY = averageY(event);
        float dy = avgY - lastTwoFingerAvgY;
        lastTwoFingerAvgY = avgY;
        twoFingerScrollAccum += dy;
        if (Math.abs(twoFingerScrollAccum) > touchSlopPx) {
            twoFingerMoved = true;
        }
        while (twoFingerScrollAccum > scrollStepPx) {
            nativeCursorScroll(1); // scroll down
            twoFingerScrollAccum -= scrollStepPx;
        }
        while (twoFingerScrollAccum < -scrollStepPx) {
            nativeCursorScroll(-1); // scroll up
            twoFingerScrollAccum += scrollStepPx;
        }
    }

    private void endTwoFingerTouch() {
        boolean wasQuickTap = !twoFingerMoved
                && (System.currentTimeMillis() - twoFingerDownTime) < TWO_FINGER_TAP_MS;
        twoFingerMode = false;
        if (wasQuickTap) {
            nativeCursorTap(3); // right click
        }
    }

    private static float averageY(MotionEvent event) {
        return (event.getY(0) + event.getY(1)) / 2f;
    }
}
