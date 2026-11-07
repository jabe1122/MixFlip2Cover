package io.github.jabe1122.mixflip2cover;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Display;
import android.view.Surface;
import android.view.WindowInsets;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import io.github.libxposed.api.XposedModule;

/**
 * Observes system IME visibility per display ID so fixed overlays hide only when they collide.
 */
final class ImeAvoidance {
    private static final String ACTION = "io.github.jabe1122.mixflip2cover.IME_VISIBILITY";
    private static final String QUERY = "io.github.jabe1122.mixflip2cover.IME_QUERY";
    private static final Map<Integer, Boolean> visible = new HashMap<>();
    private static final Map<Integer, Rect> frames = new HashMap<>();
    private static Context context;
    private static Runnable changed;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private ImeAvoidance() {}

    static void install(XposedModule module, ClassLoader loader) throws ReflectiveOperationException {
        Method method = loader.loadClass("com.android.systemui.statusbar.CommandQueue")
                .getDeclaredMethod("setImeWindowStatus", int.class, int.class, int.class, boolean.class);
        module.hook(method).intercept(chain -> {
            Object result = chain.proceed();
            int display = (Integer) chain.getArg(0);
            int flags = (Integer) chain.getArg(1);
            boolean show = (flags & 2) != 0;
            main.post(() -> update(display, show, true));
            main.postDelayed(() -> {
                if (Boolean.TRUE.equals(visible.get(display)) == show) {
                    update(display, show, true);
                }
            }, 100);
            return result;
        });
    }

    static void start(Context c, Runnable callback, boolean systemUi) {
        context = c;
        changed = callback;
        if (systemUi) {
            c.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context ignored, Intent i) {
                    for (Integer display : new ArrayList<>(visible.keySet())) {
                        send(display);
                    }
                }
            }, new IntentFilter(QUERY), CoverGeometry.STATUS_BAR_PERMISSION, main, Context.RECEIVER_EXPORTED);
            for (Integer display : new ArrayList<>(visible.keySet())) {
                send(display);
            }
        } else {
            c.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context ignored, Intent i) {
                    int id = i.getIntExtra("display", -1);
                    Rect frame = i.getParcelableExtra("frame", Rect.class);
                    if (frame != null && !Objects.equals(frames.put(id, frame), frame)) {
                        visible.remove(id);
                    }
                    update(id, i.getBooleanExtra("visible", false), false);
                }
            }, new IntentFilter(ACTION), CoverGeometry.STATUS_BAR_PERMISSION, main, Context.RECEIVER_EXPORTED);
            main.postDelayed(() -> c.sendBroadcast(new Intent(QUERY).setPackage("com.android.systemui")), 1000);
        }
    }

    private static void update(int display, boolean show, boolean broadcast) {
        if (display < 0) return;
        boolean same = Objects.equals(visible.put(display, show), show);
        if (broadcast) {
            Rect frame = show ? queryFrame(display) : new Rect();
            same &= Objects.equals(frames.put(display, frame), frame);
        }
        if (same) return;
        try {
            if (changed != null) changed.run();
            if (broadcast) send(display);
        } catch (RuntimeException e) {
            android.util.Log.w("MF2C.Ime", "IME visibility update skipped", e);
        }
    }

    private static void send(int display) {
        if (context == null) return;
        try {
            context.sendBroadcast(
                    new Intent(ACTION)
                            .setPackage("com.miui.fliphome")
                            .putExtra("display", display)
                            .putExtra("visible", Boolean.TRUE.equals(visible.get(display)))
                            .putExtra("frame", frames.get(display)),
                    CoverGeometry.STATUS_BAR_PERMISSION);
        } catch (RuntimeException e) {
            android.util.Log.w("MF2C.Ime", "IME visibility broadcast skipped", e);
        }
    }

    private static Rect queryFrame(int display) {
        try {
            Class<?> stateType = Class.forName("android.view.InsetsState");
            Object state = stateType.getDeclaredConstructor().newInstance();
            Object wm = Class.forName("android.view.WindowManagerGlobal")
                    .getDeclaredMethod("getWindowManagerService").invoke(null);
            Class.forName("android.view.IWindowManager")
                    .getMethod("getWindowInsets", int.class, IBinder.class, stateType)
                    .invoke(wm, display, null, state);
            Method count = stateType.getMethod("sourceSize");
            Method at = stateType.getMethod("sourceAt", int.class);
            Rect result = new Rect();
            int size = (Integer) count.invoke(state);
            for (int i = 0; i < size; i++) {
                Object source = at.invoke(state, i);
                int type = (Integer) source.getClass().getMethod("getType").invoke(source);
                if ((type & WindowInsets.Type.ime()) != 0) {
                    result.union((Rect) source.getClass().getMethod("getFrame").invoke(source));
                }
            }
            return result;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return new Rect();
        }
    }

    static boolean overlaps(Display display, Rect location) {
        if (display == null || !Boolean.TRUE.equals(visible.get(display.getDisplayId()))) return false;
        Rect frame = frames.get(display.getDisplayId());
        if (frame != null && !frame.isEmpty()) return Rect.intersects(location, frame);
        if (display.getRotation() == Surface.ROTATION_180) return true;
        if (display.getRotation() == Surface.ROTATION_0) return false;
        Point size = new Point();
        display.getRealSize(size);
        return Rect.intersects(location, new Rect(0, Math.round(size.y * 0.45f), size.x, size.y));
    }
}
