package io.github.jabe1122.mixflip2cover;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import java.lang.ref.WeakReference;
import io.github.libxposed.api.XposedModule;

/**
 * Mirrors a real launcher icon in the first row during unlock instead of approximating the native spring.
 *
 * The launcher side publishes the icon's alpha/scale over a protected broadcast. Frames are coalesced so at most
 * one broadcast is sent per {@link #MIN_INTERVAL_MS}; a trailing send always delivers the final state.
 */
final class UnlockMotion {
    private static final String TAG = "MF2C.Unlock";
    static final String ACTION = "io.github.jabe1122.mixflip2cover.UNLOCK_FRAME";
    private static final String LAUNCHER_APP_ITEM = "com.miui.fliphome.view.LauncherAppItem";
    private static final long MIN_INTERVAL_MS = 32;

    static WeakReference<View> icon = new WeakReference<>(null);
    static boolean queued;
    static volatile float alpha = 1f, scaleX = 1f, scaleY = 1f;
    static volatile long frameUntil;
    static Runnable changed;
    static final Handler main = new Handler(Looper.getMainLooper());
    private static long lastSentAt;
    private static boolean trailingPending;
    private static float sentAlpha = Float.NaN, sentScaleX = Float.NaN, sentScaleY = Float.NaN;
    private static final Runnable TRAILING = () -> {
        trailingPending = false;
        send();
    };

    private UnlockMotion() {}

    static void install(XposedModule module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> animator = loader.loadClass("com.miui.fliphome.animator.UserPresentAnimationCompatN8");
        module.hook(animator.getDeclaredMethod("prepareAnimation")).intercept(chain -> {
            icon.clear();
            return chain.proceed();
        });
        module.hook(animator.getDeclaredMethod("prepareUserPresentAnimation", View.class)).intercept(chain -> {
            Object result = chain.proceed();
            try {
                View v = (View) chain.getArg(0);
                View previous = icon.get();
                if (v != null && LAUNCHER_APP_ITEM.equals(v.getClass().getName())
                        && (previous == null || v.getTop() < previous.getTop()
                        || (v.getTop() == previous.getTop() && v.getLeft() > previous.getLeft()))) {
                    icon = new WeakReference<>(v);
                    publish();
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "unlock icon capture skipped", e);
            }
            return result;
        });
        for (String name : new String[]{"setAlpha", "setScaleX", "setScaleY"}) {
            module.hook(View.class.getDeclaredMethod(name, float.class)).intercept(chain -> {
                Object result = chain.proceed();
                // Identity comparison first: every other view in the launcher returns immediately.
                Object self = chain.getThisObject();
                if (!queued && self != null && self == icon.get()) {
                    queued = true;
                    try {
                        ((View) self).postOnAnimation(() -> {
                            queued = false;
                            publish();
                        });
                    } catch (RuntimeException e) {
                        queued = false;
                    }
                }
                return result;
            });
        }
    }

    /** Coalesces icon frames to at most one cross-process broadcast per {@link #MIN_INTERVAL_MS}. */
    static void publish() {
        long now = SystemClock.uptimeMillis();
        long wait = lastSentAt + MIN_INTERVAL_MS - now;
        if (wait > 0) {
            if (!trailingPending) {
                trailingPending = true;
                main.postDelayed(TRAILING, wait);
            }
            return;
        }
        send();
    }

    private static void send() {
        try {
            View v = icon.get();
            if (v == null || !ModuleConfig.panelEnabled() || !CoverDisplay.isOuterPortraitDisplay(v.getDisplay())) return;
            float a = v.getAlpha(), sx = v.getScaleX(), sy = v.getScaleY();
            if (a == sentAlpha && sx == sentScaleX && sy == sentScaleY
                    && SystemClock.uptimeMillis() - lastSentAt < 1000) {
                return;
            }
            sentAlpha = a;
            sentScaleX = sx;
            sentScaleY = sy;
            lastSentAt = SystemClock.uptimeMillis();
            Intent i = new Intent(ACTION)
                    .setPackage(CoverGeometry.SYSTEMUI)
                    .putExtra("alpha", a)
                    .putExtra("sx", sx)
                    .putExtra("sy", sy);
            v.getContext().sendBroadcast(i);
        } catch (RuntimeException e) {
            Log.w(TAG, "unlock frame skipped", e);
        }
    }

    static void start(Context c, Runnable callback) {
        changed = callback;
        c.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context ignored, Intent i) {
                alpha = Math.max(0f, Math.min(1f, i.getFloatExtra("alpha", 1f)));
                scaleX = Math.max(0.1f, Math.min(1.2f, i.getFloatExtra("sx", 1f)));
                scaleY = Math.max(0.1f, Math.min(1.2f, i.getFloatExtra("sy", 1f)));
                frameUntil = SystemClock.uptimeMillis() + 1800;
                callback.run();
            }
        }, new IntentFilter(ACTION), CoverGeometry.STATUS_BAR_PERMISSION, null, Context.RECEIVER_EXPORTED);
    }

    static boolean active() {
        return SystemClock.uptimeMillis() < frameUntil;
    }
}
