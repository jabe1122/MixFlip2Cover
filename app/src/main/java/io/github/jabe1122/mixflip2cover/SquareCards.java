package io.github.jabe1122.mixflip2cover;

import android.app.Application;
import android.content.res.Resources;
import android.util.Log;
import java.lang.reflect.Method;
import io.github.libxposed.api.XposedModule;

/**
 * Reflows native focus-notification and widget card containers through their own size resources.
 *
 * The {@code Resources.getDimension*} hooks run for every dimension lookup in the process, so the hot path is a
 * plain int comparison against the four resolved "height" resource ids; resource names are only consulted once
 * per process (to resolve the ids) and again for the rare matching id (to rule out id collisions coming from
 * other packages' Resources).
 */
final class SquareCards {
    private static final String TAG = "MF2C.Cards";
    private static final ThreadLocal<Boolean> own = new ThreadLocal<>();
    private static final String[] HEIGHTS = {
            "decor_loop_view_height",
            "decor_window_height",
            "launcher_widget_stack_loop_view_height",
            "watch_height"
    };
    private static final String[] WIDTHS = {
            "decor_loop_view_width",
            "decor_window_width",
            "launcher_widget_stack_loop_view_width",
            "watch_width"
    };

    /** Package whose resources are rewritten in this process (systemui or fliphome). */
    private static volatile String ownerPackage;
    /** Resolved height ids; null until the process Application exists. */
    private static volatile int[] heightIds;
    private static volatile int[] widthIds;
    private static volatile boolean resolveFailedLogged;

    private SquareCards() {}

    static boolean outer() {
        Boolean value = CoverGeometry.defaultDisplayOuter();
        if (value == null) return AppCompatibility.outerProcess;
        Application app = CoverGeometry.application();
        if (app != null && CoverGeometry.FLIPHOME.equals(app.getPackageName())) return true;
        return value;
    }

    static boolean active() {
        return ModuleConfig.liveIslandEnabled() && outer();
    }

    static void install(XposedModule module, String packageName) throws ReflectiveOperationException {
        ownerPackage = packageName;
        for (String name : new String[]{"getDimension", "getDimensionPixelSize", "getDimensionPixelOffset"}) {
            Method method = Resources.class.getDeclaredMethod(name, int.class);
            module.hook(method).intercept(chain -> {
                if (Boolean.TRUE.equals(own.get())) return chain.proceed();
                Object replaced = null;
                try {
                    replaced = replacement(method, chain.getThisObject(), chain.getArg(0));
                } catch (Throwable ignored) {
                }
                return replaced != null ? replaced : chain.proceed();
            });
        }
    }

    private static Object replacement(Method method, Object thisObject, Object arg) throws Throwable {
        if (!(arg instanceof Integer boxed) || !(thisObject instanceof Resources r)) return null;
        int id = boxed;
        int[] heights = heightIds;
        if (heights == null) {
            heights = resolve();
            if (heights == null) return null;
        }
        int index = -1;
        for (int i = 0; i < heights.length; i++) {
            if (heights[i] == id && id != 0) {
                index = i;
                break;
            }
        }
        if (index < 0) return null;
        int width = widthIds[index];
        if (width == 0 || !ModuleConfig.liveIslandEnabled()) return null;
        String owner = ownerPackage;
        // Rare path: verify the id really belongs to the owner package of this Resources instance.
        if (!owner.equals(r.getResourcePackageName(id)) || !HEIGHTS[index].equals(r.getResourceEntryName(id))) {
            return null;
        }
        if (!CoverGeometry.FLIPHOME.equals(owner) && !outer()) return null;
        own.set(true);
        try {
            return method.invoke(r, width);
        } finally {
            own.remove();
        }
    }

    private static int[] resolve() {
        String owner = ownerPackage;
        Application app = CoverGeometry.application();
        if (owner == null || app == null) return null;
        try {
            Resources res = app.getResources();
            int[] heights = new int[HEIGHTS.length];
            int[] widths = new int[WIDTHS.length];
            for (int i = 0; i < HEIGHTS.length; i++) {
                heights[i] = res.getIdentifier(HEIGHTS[i], "dimen", owner);
                widths[i] = res.getIdentifier(WIDTHS[i], "dimen", owner);
            }
            widthIds = widths;
            heightIds = heights;
            return heights;
        } catch (RuntimeException e) {
            if (!resolveFailedLogged) {
                resolveFailedLogged = true;
                Log.w(TAG, "card size resources unavailable", e);
            }
            int[] none = new int[HEIGHTS.length];
            widthIds = new int[WIDTHS.length];
            heightIds = none;
            return none;
        }
    }
}
