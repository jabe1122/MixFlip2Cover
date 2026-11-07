package io.github.jabe1122.mixflip2cover;

import android.app.Application;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared cover-panel layout constants and cheap, cached runtime helpers used by the SystemUI and launcher hooks.
 *
 * All coordinates are physical pixels of the portrait cover panel (see {@link CoverDisplay}). Values marked
 * "measured" were taken from the stock HyperOS 4 SystemUI / launcher layouts on bixi.
 */
final class CoverGeometry {
    static final String SYSTEMUI = "com.android.systemui";
    static final String FLIPHOME = "com.miui.fliphome";
    /** Signature permission held by SystemUI and the cover launcher; guards every exported module receiver. */
    static final String STATUS_BAR_PERMISSION = "android.permission.STATUS_BAR_SERVICE";

    /** Horizontal centre of the column above the camera block: (CAMERA_LEFT + WIDTH) / 2 = 1009. */
    static final int PANEL_CENTER_X = (CoverDisplay.CAMERA_LEFT + CoverDisplay.WIDTH) / 2;
    /** Width of one native launcher / focus-notification card (measured, fliphome mItemViewWidth). */
    static final int CARD_SIZE = 356;
    /** Height of the native cover status bar window (measured). */
    static final int STATUS_BAR_HEIGHT = 114;
    /** Margin of the relocated privacy indicator from the panel edges. */
    static final int EDGE_INSET = 16;
    /** Native status-bar clock / battery views live above this y (top) and end at or above NATIVE_STATUS_BOTTOM. */
    static final int NATIVE_STATUS_TOP = 150;
    static final int NATIVE_STATUS_BOTTOM = 170;
    /** Status panel design canvas: 100 x 150 units, battery ring centred at y = 88 units. */
    static final float PANEL_DESIGN_WIDTH = 100f;
    static final float PANEL_DESIGN_HEIGHT = 150f;
    static final float PANEL_DESIGN_ANCHOR_Y = 88f;

    private static final long STATE_TTL_MS = 500;
    private static final Object MISSING = new Object();
    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, Object>> FIELDS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, Object>> METHODS = new ConcurrentHashMap<>();
    static final Class<?>[] NO_PARAMS = new Class<?>[0];
    static final Class<?>[] STRING_PARAM = {String.class};
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile Application application;
    private static volatile Method currentApplication;
    private static volatile boolean displayListenerRegistered;
    private static volatile boolean screenReceiverRegistered;
    private static volatile Display outerDisplay;
    private static volatile boolean outerDisplayValid;
    private static volatile Boolean defaultDisplayOuter;
    private static volatile boolean keyguardLocked;
    private static volatile boolean interactive = true;
    private static volatile long stateCheckedAt;

    private CoverGeometry() {}

    /** Current process Application, resolved once through ActivityThread and cached for the process lifetime. */
    static Application application() {
        Application app = application;
        if (app != null) return app;
        try {
            Method m = currentApplication;
            if (m == null) {
                m = Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication");
                m.setAccessible(true);
                currentApplication = m;
            }
            app = (Application) m.invoke(null);
        } catch (Throwable ignored) {
            app = null;
        }
        if (app != null) {
            application = app;
            ensureDisplayListener(app);
        }
        return app;
    }

    /** Whether Display.DEFAULT_DISPLAY of this process is the cover panel; refreshed on every display change. */
    static Boolean defaultDisplayOuter() {
        Boolean cached = defaultDisplayOuter;
        if (cached != null) return cached;
        Application app = application();
        if (app == null) return null;
        try {
            DisplayManager dm = app.getSystemService(DisplayManager.class);
            Display d = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
            cached = CoverDisplay.isOuterDisplay(d);
        } catch (RuntimeException e) {
            cached = Boolean.FALSE;
        }
        defaultDisplayOuter = cached;
        return cached;
    }

    /** Cached cover Display object (live object; state/rotation reads stay current). */
    static Display outerDisplay(Context context) {
        if (outerDisplayValid) return outerDisplay;
        Context app = context != null ? context : application();
        if (app == null) return null;
        ensureDisplayListener(app);
        Display d = null;
        try {
            d = CoverDisplay.findOuterDisplay(app.getSystemService(DisplayManager.class));
        } catch (RuntimeException ignored) {
        }
        outerDisplay = d;
        outerDisplayValid = displayListenerRegistered;
        return d;
    }

    static boolean isOuterPortrait(View v) {
        return v != null && CoverDisplay.isOuterPortraitDisplay(v.getDisplay());
    }

    /** Keyguard state, refreshed at most every 500 ms and immediately on screen on/off/user-present. */
    static boolean keyguardLocked(Context context) {
        refreshDeviceState(context);
        return keyguardLocked;
    }

    static boolean interactive(Context context) {
        refreshDeviceState(context);
        return interactive;
    }

    static void invalidateDeviceState() {
        stateCheckedAt = 0;
    }

    private static void refreshDeviceState(Context context) {
        long now = SystemClock.uptimeMillis();
        if (stateCheckedAt != 0 && now - stateCheckedAt < STATE_TTL_MS) return;
        Context c = context != null ? context : application();
        if (c == null) return;
        ensureScreenReceiver(c);
        stateCheckedAt = now;
        try {
            KeyguardManager guard = c.getSystemService(KeyguardManager.class);
            keyguardLocked = guard != null && guard.isKeyguardLocked();
        } catch (RuntimeException ignored) {
        }
        try {
            PowerManager power = c.getSystemService(PowerManager.class);
            interactive = power == null || power.isInteractive();
        } catch (RuntimeException ignored) {
        }
    }

    private static void ensureScreenReceiver(Context context) {
        if (screenReceiverRegistered) return;
        synchronized (CoverGeometry.class) {
            if (screenReceiverRegistered) return;
            try {
                Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
                IntentFilter filter = new IntentFilter();
                filter.addAction(Intent.ACTION_SCREEN_ON);
                filter.addAction(Intent.ACTION_SCREEN_OFF);
                filter.addAction(Intent.ACTION_USER_PRESENT);
                app.registerReceiver(new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent intent) {
                        invalidateDeviceState();
                    }
                }, filter, null, MAIN, Context.RECEIVER_NOT_EXPORTED);
                screenReceiverRegistered = true;
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static void ensureDisplayListener(Context context) {
        if (displayListenerRegistered || context == null) return;
        synchronized (CoverGeometry.class) {
            if (displayListenerRegistered) return;
            try {
                DisplayManager dm = context.getSystemService(DisplayManager.class);
                if (dm == null) return;
                dm.registerDisplayListener(new DisplayManager.DisplayListener() {
                    @Override public void onDisplayAdded(int id) { invalidateDisplays(); }
                    @Override public void onDisplayRemoved(int id) { invalidateDisplays(); }
                    @Override public void onDisplayChanged(int id) { invalidateDisplays(); }
                }, MAIN);
                displayListenerRegistered = true;
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static void invalidateDisplays() {
        outerDisplayValid = false;
        outerDisplay = null;
        defaultDisplayOuter = null;
        invalidateDeviceState();
    }

    /** Reads a (possibly inherited) field; Field lookups are cached per class, misses included. */
    static Object field(Object object, String name) throws ReflectiveOperationException {
        if (object == null) return null;
        Field f = findField(object.getClass(), name);
        if (f == null) throw new NoSuchFieldException(name);
        return f.get(object);
    }

    /** Writes a (possibly inherited) field; silently ignores a null target. */
    static void setField(Object object, String name, Object value) throws ReflectiveOperationException {
        if (object == null) return;
        Field f = findField(object.getClass(), name);
        if (f == null) throw new NoSuchFieldException(name);
        f.set(object, value);
    }

    static Field findField(Class<?> type, String name) {
        ConcurrentHashMap<String, Object> perClass = FIELDS.computeIfAbsent(type, k -> new ConcurrentHashMap<>());
        Object cached = perClass.get(name);
        if (cached == null) {
            cached = MISSING;
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    cached = f;
                    break;
                } catch (NoSuchFieldException ignored) {
                } catch (RuntimeException e) {
                    break;
                }
            }
            perClass.put(name, cached);
        }
        return cached instanceof Field f ? f : null;
    }

    static WindowManager.LayoutParams copy(WindowManager.LayoutParams p) {
        WindowManager.LayoutParams value = new WindowManager.LayoutParams();
        value.copyFrom(p);
        return value;
    }

    /** Public (possibly inherited) method lookup via Class.getMethod, cached per class/name/arity; misses included. */
    static Method findMethod(Class<?> type, String name, Class<?>... params) {
        ConcurrentHashMap<String, Object> perClass = METHODS.computeIfAbsent(type, k -> new ConcurrentHashMap<>());
        String key = name + '/' + params.length;
        Object cached = perClass.get(key);
        if (cached == null) {
            try {
                Method m = type.getMethod(name, params);
                m.setAccessible(true);
                cached = m;
            } catch (NoSuchMethodException | RuntimeException e) {
                cached = MISSING;
            }
            perClass.put(key, cached);
        }
        return cached instanceof Method m ? m : null;
    }

    /** Invokes a cached public no-arg/one-arg method; throws NoSuchMethodException when absent. */
    static Object invoke(Object target, String name, Class<?>[] params, Object... args) throws ReflectiveOperationException {
        if (target == null) return null;
        Method m = findMethod(target.getClass(), name, params);
        if (m == null) throw new NoSuchMethodException(name);
        return m.invoke(target, args);
    }
}
