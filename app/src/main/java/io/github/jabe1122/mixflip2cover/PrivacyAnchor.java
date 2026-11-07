package io.github.jabe1122.mixflip2cover;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.WeakHashMap;
import io.github.libxposed.api.XposedModule;

/**
 * Relocates the native live privacy indicator on the cover display without suppressing it.
 */
final class PrivacyAnchor {
    private static final String TAG = "MF2C.Privacy";
    static final WeakHashMap<View, WindowManager.LayoutParams> windows = new WeakHashMap<>();
    static final WeakHashMap<View, int[]> capsuleLayouts = new WeakHashMap<>();
    static boolean observing;
    static final ThreadLocal<Boolean> own = new ThreadLocal<>();

    private PrivacyAnchor() {}

    static boolean isPrivacy(View v) {
        for (Class<?> c = v.getClass(); c != null; c = c.getSuperclass()) {
            if ("com.android.systemui.statusbar.privacy.PrivacyDotWindowView".equals(c.getName())) return true;
        }
        return false;
    }

    static boolean outer(View v) {
        return CoverGeometry.isOuterPortrait(v);
    }

    static WindowManager.LayoutParams copy(WindowManager.LayoutParams p) {
        return CoverGeometry.copy(p);
    }

    static WindowManager.LayoutParams anchored(View v, WindowManager.LayoutParams source) {
        WindowManager.LayoutParams p = copy(source);
        if (!ModuleConfig.frameworkFullscreen() || !outer(v)) {
            WindowManager.LayoutParams original = v != null ? windows.get(v) : null;
            if (original != null) {
                p.x = original.x;
                p.y = original.y;
                p.gravity = original.gravity;
                p.setFitInsetsTypes(original.getFitInsetsTypes());
                p.layoutInDisplayCutoutMode = original.layoutInDisplayCutoutMode;
            }
            return p;
        }
        int rotation = v.getDisplay().getRotation();
        int height = p.height > 0 ? p.height : CoverGeometry.STATUS_BAR_HEIGHT;
        int inset = CoverGeometry.EDGE_INSET;
        p.gravity = Gravity.TOP | Gravity.RIGHT;
        p.x = inset;
        p.y = inset;
        if (rotation == Surface.ROTATION_180) {
            p.gravity = Gravity.TOP | Gravity.LEFT;
            p.x = inset;
            p.y = CoverDisplay.PHYSICAL_HEIGHT - inset - height;
        } else if (rotation != Surface.ROTATION_0) {
            return copy(source);
        }
        p.setFitInsetsTypes(0);
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        return p;
    }

    static void fixContent(View v) {
        if (!ModuleConfig.frameworkFullscreen() || !outer(v)) {
            try {
                if (CoverGeometry.field(v, "mPrivacyCapsule") instanceof View capsule
                        && (Object) capsuleLayouts.get(capsule) instanceof int[] saved
                        && capsule.getLayoutParams() instanceof FrameLayout.LayoutParams lp) {
                    if (lp.gravity != saved[0] || lp.leftMargin != saved[1] || lp.rightMargin != saved[2]) {
                        lp.gravity = saved[0];
                        lp.leftMargin = saved[1];
                        lp.rightMargin = saved[2];
                        capsule.setLayoutParams(lp);
                    }
                }
            } catch (ReflectiveOperationException ignored) {
            }
            return;
        }
        int rotation = v.getDisplay().getRotation();
        if (rotation != Surface.ROTATION_0 && rotation != Surface.ROTATION_180) return;
        v.setPadding(0, v.getPaddingTop(), 0, v.getPaddingBottom());
        try {
            if (CoverGeometry.field(v, "mPrivacyCapsule") instanceof View capsule
                    && capsule.getLayoutParams() instanceof FrameLayout.LayoutParams lp) {
                capsuleLayouts.putIfAbsent(capsule, new int[]{lp.gravity, lp.leftMargin, lp.rightMargin});
                int gravity = (rotation == Surface.ROTATION_180 ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL;
                if (lp.gravity != gravity || lp.leftMargin != 0 || lp.rightMargin != 0) {
                    lp.gravity = gravity;
                    lp.leftMargin = lp.rightMargin = 0;
                    capsule.setLayoutParams(lp);
                }
            }
        } catch (ReflectiveOperationException ignored) {
        }
    }

    static void refresh() {
        for (View v : new ArrayList<>(windows.keySet())) {
            if (v == null || !v.isAttachedToWindow()) continue;
            try {
                WindowManager wm = v.getContext().getSystemService(WindowManager.class);
                if (wm == null || !(v.getLayoutParams() instanceof WindowManager.LayoutParams current)) continue;
                WindowManager.LayoutParams p = anchored(v, current);
                own.set(true);
                try {
                    wm.updateViewLayout(v, p);
                } finally {
                    own.remove();
                }
                Method update = v.getClass().getMethod("updateLayout");
                update.invoke(v);
                fixContent(v);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
    }

    static void observe(Context c) {
        if (observing) return;
        observing = true;
        ModuleConfig.addListener(PrivacyAnchor::refresh);
        DisplayManager dm = c.getSystemService(DisplayManager.class);
        if (dm != null) {
            dm.registerDisplayListener(new DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int id) { refresh(); }
                @Override public void onDisplayRemoved(int id) { refresh(); }
                @Override public void onDisplayChanged(int id) { refresh(); }
            }, new Handler(Looper.getMainLooper()));
        }
    }

    static void install(XposedModule module, ClassLoader loader) throws Exception {
        for (String name : new String[]{
                "com.android.systemui.statusbar.privacy.PrivacyDotWindowView",
                "com.android.systemui.statusbar.privacy.PrivacyFlaresView"
        }) {
            try {
                Class<?> c = loader.loadClass(name);
                module.hook(c.getDeclaredMethod("updateLayout")).intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        fixContent((View) chain.getThisObject());
                    } catch (RuntimeException e) {
                        Log.w(TAG, "privacy content layout skipped", e);
                    }
                    return result;
                });
            } catch (ClassNotFoundException | NoSuchMethodException ignored) {
            }
        }
        for (Method method : Class.forName("android.view.WindowManagerGlobal").getDeclaredMethods()) {
            if (!(method.getName().equals("addView") || method.getName().equals("updateViewLayout"))) continue;
            Class<?>[] p = method.getParameterTypes();
            if (p.length < 2 || p[0] != View.class) continue;
            module.hook(method).intercept(chain -> {
                if (!Boolean.TRUE.equals(own.get())
                        && chain.getArg(0) instanceof View v
                        && isPrivacy(v)
                        && chain.getArg(1) instanceof WindowManager.LayoutParams lp) {
                    Object[] args = null;
                    try {
                        windows.putIfAbsent(v, copy(lp));
                        args = chain.getArgs().toArray();
                        args[1] = anchored(v, lp);
                    } catch (RuntimeException e) {
                        Log.w(TAG, "privacy window anchor skipped", e);
                    }
                    if (args == null) return chain.proceed();
                    Object result = chain.proceed(args);
                    v.post(() -> {
                        try {
                            fixContent(v);
                            refresh();
                        } catch (RuntimeException e) {
                            Log.w(TAG, "privacy refresh skipped", e);
                        }
                    });
                    return result;
                }
                return chain.proceed();
            });
        }
    }
}
