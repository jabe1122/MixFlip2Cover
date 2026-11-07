package io.github.jabe1122.mixflip2cover;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;
import android.util.Log;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks native full-screen state on the cover display from the protected system_server broadcast.
 */
final class CoverAppPolicy {
    private static final String TAG = "MF2C.AppPolicy";
    static Context context;
    static Runnable changed;
    static final Map<Integer, Boolean> frameworkFullscreen = new ConcurrentHashMap<>();
    static volatile long homeTransitionUntil;

    private CoverAppPolicy() {}

    static void start(Context c, Runnable callback) {
        context = c;
        changed = callback;
        c.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context ignored, Intent i) {
                try {
                    int display = i.getIntExtra("display", -1);
                    if (display < 0) return;
                    boolean full = i.getBooleanExtra("fullscreen", false);
                    frameworkFullscreen.put(display, full);
                    if (display == 0) {
                        String pkg = i.getStringExtra("package");
                        if (pkg != null && !pkg.isEmpty()) {
                            NativeStatusStyle.onForegroundPackage(pkg);
                        }
                    }
                    if (full) homeTransitionUntil = 0;
                    callback.run();
                } catch (RuntimeException e) {
                    Log.w(TAG, "window state update skipped", e);
                }
            }
        }, new IntentFilter(FrameworkRuntime.ACTION), CoverGeometry.STATUS_BAR_PERMISSION, null, Context.RECEIVER_EXPORTED);
    }

    static boolean hidePanel(int displayId) {
        if (SystemClock.uptimeMillis() < homeTransitionUntil) return false;
        return FrameworkRuntime.active() && Boolean.TRUE.equals(frameworkFullscreen.get(displayId));
    }

    static void homeTransition(boolean resumed) {
        homeTransitionUntil = resumed ? SystemClock.uptimeMillis() + 1200 : 0;
        if (changed != null) changed.run();
    }

    static void transitionStart(int displayId) {
        if (context == null) return;
        try {
            ActivityManager am = context.getSystemService(ActivityManager.class);
            if (am == null) return;
            for (ActivityManager.RunningTaskInfo task : am.getRunningTasks(5)) {
                // RunningTaskInfo.displayId is hidden API; the Field is resolved once and cached.
                if (!(CoverGeometry.field(task, "displayId") instanceof Integer taskDisplay)
                        || taskDisplay != displayId || task.topActivity == null) {
                    continue;
                }
                if (CoverGeometry.FLIPHOME.equals(task.topActivity.getPackageName())) {
                    int target = NativeStatusStyle.wallpaperInk(context);
                    NativeStatusStyle.homeInk = target;
                    NativeStatusStyle.homeInkUntil = SystemClock.uptimeMillis() + 1200;
                    homeTransition(true);
                }
                return;
            }
        } catch (RuntimeException | ReflectiveOperationException ignored) {
        }
    }
}
