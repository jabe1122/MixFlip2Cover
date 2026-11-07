package io.github.jabe1122.mixflip2cover;

import android.app.ActivityManager;
import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;
import android.view.View;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * Mirrors the native status-bar typography and tint (clock font, icon tone, battery state colours) for the
 * module's cover status panel, and tracks whether the right-hand pane shows the launcher or an app.
 */
final class NativeStatusStyle {
    private static final String TAG = "MF2C.Tint";
    static final String MIUI_POWER_SAVE_KEY = "POWER_SAVE_MODE_OPEN";
    static final String ACTION_HOME = "io.github.jabe1122.mixflip2cover.HOME";
    static final String ACTION_RIGHT_PANE_BG = "io.github.jabe1122.mixflip2cover.RIGHT_PANE_BG";
    static final String ACTION_QUERY_RIGHT_PANE_BG = "io.github.jabe1122.mixflip2cover.QUERY_RIGHT_PANE_BG";
    private static final String MIUI_CLOCK = "com.android.systemui.statusbar.views.MiuiClock";
    private static final String AOSP_CLOCK = "com.android.systemui.statusbar.policy.Clock";
    private static final String MIUI_BATTERY = "com.android.systemui.statusbar.views.MiuiBatteryMeterView";
    /** How long a chosen native clock/battery view is reused before the candidate list is scanned again. */
    private static final long CHOOSE_TTL_MS = 250;
    /** Minimum interval between navigation-bar colour queries triggered by tint callbacks. */
    private static final long NAV_COLOR_THROTTLE_MS = 250;
    private static final Typeface FALLBACK_CLOCK_FACE =
            Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), 900, false);

    static final ArrayList<WeakReference<View>> clocks = new ArrayList<>();
    static final ArrayList<WeakReference<View>> batteries = new ArrayList<>();
    static final ArrayList<WeakReference<View>> tintObservers = new ArrayList<>();
    /** Weak so a detached status bar view hierarchy is never pinned by the module. */
    private static WeakReference<View> lastClock = new WeakReference<>(null), lastBattery = new WeakReference<>(null);
    private static long clockChosenAt, batteryChosenAt;
    private static final Rect scratchRect = new Rect();
    private static final int[] scratchXy = new int[2];

    static int loggedTintFrames, lastLoggedTint;
    static Context appContext;
    static volatile boolean rightPaneIsDesk = true;
    static volatile int rightPaneAppColor = 0;
    static volatile Boolean coverWallpaperDark = null;
    static boolean transitionReceiverRegistered;
    static int homeInk;
    static long homeInkUntil;

    private static Method getWindowManagerService;
    private static Method getNavColorMethod;
    private static boolean navColorMethodResolved;
    private static long navColorQueriedAt;
    private static int darkToneId = -1, lightToneId = -1;

    private NativeStatusStyle() {}

    static boolean powerSaveEnabled(Context context) {
        boolean miui = false, standard = false;
        try {
            miui = Settings.System.getInt(context.getContentResolver(), MIUI_POWER_SAVE_KEY, 0) == 1;
        } catch (RuntimeException ignored) {
        }
        try {
            PowerManager pm = context.getSystemService(PowerManager.class);
            standard = pm != null && pm.isPowerSaveMode();
        } catch (RuntimeException ignored) {
        }
        return miui || standard;
    }

    static void capture(View view) {
        String name = view.getClass().getName();
        ArrayList<WeakReference<View>> list = null;
        if (view instanceof TextView && (name.equals(MIUI_CLOCK) || name.equals(AOSP_CLOCK))) list = clocks;
        if (name.equals(MIUI_BATTERY)) list = batteries;
        if (list != null) {
            for (WeakReference<View> ref : list) {
                if (ref.get() == view) return;
            }
            if (list.size() > 64) list.remove(0);
            list.add(new WeakReference<>(view));
            clockChosenAt = batteryChosenAt = 0;
        }
    }

    static View choose(ArrayList<WeakReference<View>> list, View fallback) {
        Rect rect = scratchRect;
        for (WeakReference<View> ref : list) {
            View v = ref.get();
            if (v != null && v.isShown() && v.getGlobalVisibleRect(rect)
                    && rect.top < CoverGeometry.NATIVE_STATUS_TOP
                    && rect.bottom <= CoverGeometry.NATIVE_STATUS_BOTTOM
                    && rect.left < CoverDisplay.CAMERA_LEFT) {
                return v;
            }
        }
        for (WeakReference<View> ref : list) {
            View v = ref.get();
            if (v == null || v.getWidth() <= 0 || v.getHeight() <= 0) continue;
            Display display = v.getDisplay();
            if (display == null) continue;
            Display.Mode m = display.getMode();
            if (m == null || m.getPhysicalWidth() != CoverDisplay.WIDTH || m.getPhysicalHeight() != CoverDisplay.HEIGHT) continue;
            v.getLocationOnScreen(scratchXy);
            if (scratchXy[0] >= CoverDisplay.CAMERA_LEFT || scratchXy[1] >= CoverGeometry.NATIVE_STATUS_TOP) continue;
            if (v instanceof TextView) {
                try {
                    if (!v.getResources().getResourceEntryName(v.getId()).equals("clock")) continue;
                } catch (RuntimeException e) {
                    continue;
                }
            }
            return v;
        }
        return fallback;
    }

    private static View clock() {
        long now = SystemClock.uptimeMillis();
        if (clockChosenAt == 0 || now - clockChosenAt >= CHOOSE_TTL_MS) {
            lastClock = new WeakReference<>(choose(clocks, lastClock.get()));
            clockChosenAt = now;
        }
        return lastClock.get();
    }

    private static View battery() {
        long now = SystemClock.uptimeMillis();
        if (batteryChosenAt == 0 || now - batteryChosenAt >= CHOOSE_TTL_MS) {
            lastBattery = new WeakReference<>(choose(batteries, lastBattery.get()));
            batteryChosenAt = now;
        }
        return lastBattery.get();
    }

    static void applyClock(Paint paint) {
        if (clock() instanceof TextView text) {
            paint.set(text.getPaint());
            paint.setAntiAlias(true);
        } else {
            paint.setTypeface(FALLBACK_CLOCK_FACE);
            paint.setFakeBoldText(true);
        }
    }

    static int inkColor() {
        if (!rightPaneIsDesk) {
            int bg = rightPaneAppColor;
            if (bg == 0) {
                boolean night = appContext != null
                        && (appContext.getResources().getConfiguration().uiMode & 48) == 32;
                bg = night ? Color.BLACK : Color.WHITE;
            }
            return toneInk(appContext, !isColorDark(bg));
        }
        if (coverWallpaperDark != null && appContext != null) {
            return toneInk(appContext, !coverWallpaperDark);
        }
        if (clock() instanceof TextView text) {
            return text.getCurrentTextColor();
        }
        return Color.WHITE;
    }

    static void observeTint(View view) {
        tintObservers.removeIf(r -> r.get() == null);
        tintObservers.add(new WeakReference<>(view));
    }

    static void removeTintObserver(View view) {
        tintObservers.removeIf(r -> r.get() == null || r.get() == view);
    }

    static boolean isColorDark(int color) {
        int r = (color >> 16) & 0xff, g = (color >> 8) & 0xff, b = color & 0xff;
        return (0.299 * r + 0.587 * g + 0.114 * b) < 128.0;
    }

    static int toneInk(Context context, boolean darkText) {
        if (context != null) {
            int id = darkText ? darkToneId : lightToneId;
            if (id < 0) {
                String resource = darkText ? "dark_mode_icon_color_single_tone" : "light_mode_icon_color_single_tone";
                try {
                    id = context.getResources().getIdentifier(resource, "color", CoverGeometry.SYSTEMUI);
                } catch (RuntimeException e) {
                    id = 0;
                }
                if (darkText) darkToneId = id;
                else lightToneId = id;
            }
            if (id != 0) {
                try {
                    return context.getColor(id);
                } catch (RuntimeException ignored) {
                }
            }
        }
        return darkText ? 0xbf000000 : 0xe6ffffff;
    }

    static void refreshRightPaneColor() {
        refreshRightPaneColor(false);
    }

    /** Queries the colour of the app currently behind the right pane; throttled unless {@code force}. */
    static void refreshRightPaneColor(boolean force) {
        long now = SystemClock.uptimeMillis();
        if (!force && navColorQueriedAt != 0 && now - navColorQueriedAt < NAV_COLOR_THROTTLE_MS) return;
        navColorQueriedAt = now;
        try {
            Method service = getWindowManagerService;
            if (service == null) {
                service = Class.forName("android.view.WindowManagerGlobal").getDeclaredMethod("getWindowManagerService");
                service.setAccessible(true);
                getWindowManagerService = service;
            }
            Object wms = service.invoke(null);
            if (wms == null) return;
            if (!navColorMethodResolved) {
                navColorMethodResolved = true;
                try {
                    getNavColorMethod = wms.getClass().getMethod("getCurrentNavigationBarColor");
                    getNavColorMethod.setAccessible(true);
                } catch (NoSuchMethodException ignored) {
                }
            }
            if (getNavColorMethod != null) {
                Object res = getNavColorMethod.invoke(wms);
                if (res instanceof Integer color && color != 0) rightPaneAppColor = color;
            }
        } catch (Throwable ignored) {
        }
    }

    static void refreshTopTaskDeskState(Context context) {
        if (context == null) return;
        try {
            ActivityManager am = context.getSystemService(ActivityManager.class);
            if (am == null) return;
            for (ActivityManager.RunningTaskInfo task : am.getRunningTasks(5)) {
                if (task.topActivity == null) continue;
                if (!(CoverGeometry.field(task, "displayId") instanceof Integer displayId) || displayId != 0) continue;
                rightPaneIsDesk = CoverGeometry.FLIPHOME.equals(task.topActivity.getPackageName());
                return;
            }
        } catch (Throwable ignored) {
        }
    }

    static void onForegroundPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        boolean desk = CoverGeometry.FLIPHOME.equals(pkg);
        rightPaneIsDesk = desk;
        if (!desk) refreshRightPaneColor(true);
        tintChanged();
    }

    static void tintChanged() {
        if (!rightPaneIsDesk) refreshRightPaneColor(false);
        int color = inkColor();
        if (loggedTintFrames < 64 && color != lastLoggedTint) {
            lastLoggedTint = color;
            loggedTintFrames++;
            ModLog.i(TAG, "native frame=" + Integer.toHexString(color) + " isDesk=" + rightPaneIsDesk
                    + " appColor=" + Integer.toHexString(rightPaneAppColor));
        }
        for (WeakReference<View> ref : new ArrayList<>(tintObservers)) {
            View v = ref.get();
            if (v != null && v.isAttachedToWindow()) v.postInvalidateOnAnimation();
        }
    }

    static int wallpaperInk(Context context) {
        if (coverWallpaperDark != null) return toneInk(context, !coverWallpaperDark);
        boolean darkText = false;
        try {
            WallpaperColors colors = context.getSystemService(WallpaperManager.class)
                    .getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
            if (colors != null) {
                darkText = (colors.getColorHints() & WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0;
            }
        } catch (RuntimeException ignored) {
        }
        return toneInk(context, darkText);
    }

    private static void animateHome(int target) {
        for (WeakReference<View> ref : new ArrayList<>(tintObservers)) {
            if (ref.get() instanceof StatusOrb orb) orb.animateHomeInk(target);
        }
    }

    static void registerTransitions(Context context) {
        appContext = context;
        refreshTopTaskDeskState(context);
        refreshRightPaneColor(true);
        if (transitionReceiverRegistered) return;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                try {
                    handle(context, intent);
                } catch (RuntimeException e) {
                    android.util.Log.w(TAG, "tint broadcast skipped", e);
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_HOME);
        filter.addAction(ACTION_RIGHT_PANE_BG);
        context.registerReceiver(receiver, filter, CoverGeometry.STATUS_BAR_PERMISSION, null, Context.RECEIVER_EXPORTED);
        transitionReceiverRegistered = true;
        try {
            context.sendBroadcast(new Intent(ACTION_QUERY_RIGHT_PANE_BG).setPackage(CoverGeometry.FLIPHOME));
        } catch (RuntimeException ignored) {
        }
    }

    private static void handle(Context context, Intent intent) {
        String action = intent.getAction();
        if (ACTION_RIGHT_PANE_BG.equals(action)) {
            rightPaneIsDesk = intent.getBooleanExtra("isDesk", rightPaneIsDesk);
            int color = intent.getIntExtra("appColor", 0);
            if (color != 0) rightPaneAppColor = color;
            if (intent.hasExtra("wallpaperDark")) coverWallpaperDark = intent.getBooleanExtra("wallpaperDark", false);
            if (rightPaneIsDesk && SystemClock.uptimeMillis() < homeInkUntil) {
                int target = wallpaperInk(context);
                homeInk = target;
                animateHome(target);
            }
            tintChanged();
            return;
        }
        boolean home = intent.getBooleanExtra("resumed", false);
        rightPaneIsDesk = home;
        if (!home) refreshRightPaneColor(true);
        CoverAppPolicy.homeTransition(home);
        if (!home) {
            homeInkUntil = 0;
            for (WeakReference<View> ref : new ArrayList<>(tintObservers)) {
                if (ref.get() instanceof StatusOrb orb) orb.finishHomeTransition();
            }
        } else {
            int target = wallpaperInk(context);
            homeInk = target;
            homeInkUntil = SystemClock.uptimeMillis() + 1200;
            ModLog.i(TAG, "home wallpaper target=" + Integer.toHexString(target) + " duration=120ms");
            animateHome(target);
        }
        tintChanged();
    }

    static int batteryColor(boolean charging, boolean powerSave, boolean low) {
        if (!charging && !powerSave && !low) return Color.WHITE;
        View battery = battery();
        try {
            Object icon = CoverGeometry.field(battery, "mBatteryIconView");
            String name = powerSave ? "mBatteryPowerSaveColor" : charging ? "mBatteryChargingColor" : "mBatteryLowColor";
            Object value = CoverGeometry.field(icon, name);
            if (value instanceof Integer color && color != 0) return color;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return powerSave ? 0xffffcc00 : charging ? 0xff23c838 : 0xfff44336;
    }
}
