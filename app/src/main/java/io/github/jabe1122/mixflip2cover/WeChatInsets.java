package io.github.jabe1122.mixflip2cover;

import android.content.Context;
import android.graphics.Rect;
import android.util.Log;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import io.github.libxposed.api.XposedModule;

/**
 * Status-space and FitSystemWindowLayoutView cache helpers for WeChat 8.0.x.
 *
 * <p>The hooked symbols ({@code com.tencent.mm.ui.ol#h/i/j/k}, {@code HomeUI$FitSystemWindowLayoutView#d},
 * {@code plugin.taskbar.ui.b0}) are obfuscated and only verified for the WeChat versionCode range
 * [{@link #MIN_VERIFIED_VERSION}, {@link #MAX_VERIFIED_VERSION_EXCLUSIVE}); other versions are skipped.</p>
 */
final class WeChatInsets {
    private static final String TAG = "MF2C.WeChat";
    static final long MIN_VERIFIED_VERSION = 3200;
    static final long MAX_VERIFIED_VERSION_EXCLUSIVE = 3240;

    static volatile Boolean supported;
    static final Set<Class<?>> installed = ConcurrentHashMap.newKeySet();
    static final Set<Class<?>> fitInstalled = ConcurrentHashMap.newKeySet();
    static final Set<Class<?>> deoptimized = ConcurrentHashMap.newKeySet();
    static final AtomicInteger logged = new AtomicInteger();

    private WeChatInsets() {}

    /** True when the installed WeChat versionCode is inside the verified whitelist range. Logs once otherwise. */
    static boolean isVerifiedVersion(Context c) {
        Boolean s = supported;
        if (s == null && c != null) {
            long version = -1;
            try {
                version = c.getPackageManager().getPackageInfo("com.tencent.mm", 0).getLongVersionCode();
                s = version >= MIN_VERIFIED_VERSION && version < MAX_VERIFIED_VERSION_EXCLUSIVE;
            } catch (Throwable t) {
                s = false;
            }
            supported = s;
            if (!s) {
                Log.w(TAG, "WeChat versionCode " + version + " outside verified range ["
                        + MIN_VERIFIED_VERSION + ", " + MAX_VERIFIED_VERSION_EXCLUSIVE + "); WeChat adaptation skipped");
            }
        }
        return Boolean.TRUE.equals(s);
    }

    static boolean active(Context c) {
        return isVerifiedVersion(c)
                && FrameworkRuntime.active()
                && AppCompatibility.outerProcess
                && AppCompatibility.portraitProcess
                && !AppCompatibility.isInnerScreenContext(c)
                && ModuleConfig.immersiveEnabled()
                && ModuleConfig.compatEnabled("com.tencent.mm");
    }

    private static boolean safeActive(Object maybeContext) {
        try {
            return maybeContext instanceof Context context && active(context);
        } catch (Throwable t) {
            return false;
        }
    }

    static void install(XposedModule module, ClassLoader loader) {
        try {
            Class<?> c = loader.loadClass("com.tencent.mm.ui.ol");
            if (installed.add(c)) {
                for (String n : new String[]{"h", "i", "j", "k"}) {
                    Class<?>[] args = (n.equals("i") || n.equals("k"))
                            ? new Class<?>[]{Context.class, int.class}
                            : new Class<?>[]{Context.class};
                    Method method = c.getDeclaredMethod(n, args);
                    if (method.getReturnType() != int.class || !Modifier.isStatic(method.getModifiers())) {
                        throw new NoSuchMethodException(n);
                    }
                    module.hook(method).intercept(chain -> {
                        boolean override = false;
                        try {
                            override = safeActive(chain.getArg(0));
                        } catch (Throwable ignored) {
                        }
                        if (override) {
                            if (logged.getAndIncrement() < 8) {
                                ModLog.i(TAG, "verified status height " + n + " -> " + CoverResourceOverlay.COVER_APP_STATUS_BAR_PX);
                            }
                            return CoverResourceOverlay.COVER_APP_STATUS_BAR_PX;
                        }
                        return chain.proceed();
                    });
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
        }

        try {
            Class<?> fit = loader.loadClass("com.tencent.mm.ui.HomeUI$FitSystemWindowLayoutView");
            Field cache = fit.getDeclaredField("d");
            cache.setAccessible(true);
            if (cache.getType() != int.class) {
                throw new NoSuchFieldException("cache type");
            }
            if (fitInstalled.add(fit)) {
                module.hook(fit.getDeclaredMethod("getCacheInsetsTop")).intercept(chain -> {
                    boolean override = false;
                    try {
                        override = chain.getThisObject() instanceof View view && safeActive(view.getContext());
                    } catch (Throwable ignored) {
                    }
                    if (override) return CoverResourceOverlay.COVER_APP_STATUS_BAR_PX;
                    return chain.proceed();
                });
                module.hook(fit.getDeclaredMethod("fitSystemWindows", Rect.class)).intercept(chain -> {
                    Object[] replaced = null;
                    try {
                        if (chain.getThisObject() instanceof View view && safeActive(view.getContext())
                                && chain.getArg(0) instanceof Rect original) {
                            cache.setInt(view, CoverResourceOverlay.COVER_APP_STATUS_BAR_PX);
                            Rect insets = new Rect(original);
                            insets.top = CoverResourceOverlay.COVER_APP_STATUS_BAR_PX;
                            replaced = new Object[]{insets};
                        }
                    } catch (Throwable ignored) {
                        replaced = null;
                    }
                    return replaced != null ? chain.proceed(replaced) : chain.proceed();
                });
                ModLog.i(TAG, "verified HomeUI cache hooks installed");
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
        }

        for (String name : new String[]{
                "com.tencent.mm.ui.ol",
                "com.tencent.mm.ui.HomeUI",
                "com.tencent.mm.ui.HomeUI$FitSystemWindowLayoutView",
                "com.tencent.mm.plugin.taskbar.ui.b0",
                "com.tencent.mm.plugin.taskbar.ui.TaskBarContainer"
        }) {
            try {
                Class<?> c = loader.loadClass(name);
                if (deoptimized.add(c)) {
                    for (Method m : c.getDeclaredMethods()) {
                        if (!Modifier.isAbstract(m.getModifiers()) && !Modifier.isNative(m.getModifiers())) {
                            module.deoptimize(m);
                        }
                    }
                }
            } catch (ClassNotFoundException | RuntimeException | LinkageError ignored) {
            }
        }
    }
}
