package io.github.jabe1122.mixflip2cover;

import android.content.Context;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import io.github.libxposed.api.XposedModule;

/**
 * Eliminates the excess top status-bar blank space in the cover-screen Notification Shade header
 * and Heads-Up (pop-up banner) notifications when the native cover status bar is hidden.
 */
final class CoverNotificationTop {
    private static final String TAG = "MF2C.NotifTop";

    static final float COVER_HEADER_TOP_TRIM_PX = 65f;
    static final float COVER_HEADER_MIN_HEIGHT_PX = 168f;
    static final int COVER_HEADS_UP_TOP_INSET_PX = CoverGeometry.EDGE_INSET;

    private static final ThreadLocal<Boolean> IN_CONTROL_CENTER_HEADER =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static final Set<Object> COMBINED_CONTROLLERS =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final Set<Object> HEIGHT_ANIMATORS =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final Set<View> STACK_SCROLL_LAYOUTS =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final Object DISPLAY_REFRESH_TOKEN = new Object();
    private static volatile Handler mainHandler;
    private static volatile boolean displayObserverRegistered;
    private static volatile Method miuiHeadsUpInsetMethod;
    private static volatile Field cachedNsslHeadsUpInsetField;
    private static volatile WeakReference<Runnable> configListenerRef;

    private CoverNotificationTop() {
    }

    static float adjustCombinedHeaderHeight(
            float originalHeight,
            boolean enabled,
            boolean outerDisplay,
            boolean inControlCenterHeader) {
        if (!enabled || !outerDisplay || inControlCenterHeader) {
            return originalHeight;
        }
        if (!(originalHeight > COVER_HEADER_MIN_HEIGHT_PX)) {
            return originalHeight;
        }
        return Math.max(COVER_HEADER_MIN_HEIGHT_PX, originalHeight - COVER_HEADER_TOP_TRIM_PX);
    }

    static int adjustHeadsUpInset(int originalInset, boolean enabled, boolean outerDisplay) {
        if (!enabled || !outerDisplay) {
            return originalInset;
        }
        return originalInset > COVER_HEADS_UP_TOP_INSET_PX
                ? COVER_HEADS_UP_TOP_INSET_PX
                : originalInset;
    }

    static boolean isOuterPortraitMetrics(int widthPx, int heightPx, int orientation) {
        if (orientation == Configuration.ORIENTATION_LANDSCAPE || widthPx <= 0 || heightPx <= 0) {
            return false;
        }
        return Math.max(widthPx, heightPx) < 2000 && widthPx <= heightPx;
    }

    static void install(XposedModule module, ClassLoader loader) {
        Handler main = new Handler(Looper.getMainLooper());
        mainHandler = main;
        installShadeHeaderHooks(module, loader);
        installHeadsUpHooks(module, loader);

        Runnable listener = () -> main.post(CoverNotificationTop::refreshAllControllers);
        configListenerRef = new WeakReference<>(listener);
        ModuleConfig.addListener(listener);
    }

    private static void ensureDisplayObserver(Context context) {
        if (displayObserverRegistered || context == null) return;
        synchronized (CoverNotificationTop.class) {
            if (displayObserverRegistered) return;
            try {
                DisplayManager dm = context.getSystemService(DisplayManager.class);
                Handler h = mainHandler != null ? mainHandler : new Handler(Looper.getMainLooper());
                if (dm != null) {
                    dm.registerDisplayListener(new DisplayManager.DisplayListener() {
                        @Override public void onDisplayAdded(int displayId) { scheduleRefresh(); }
                        @Override public void onDisplayRemoved(int displayId) { scheduleRefresh(); }
                        @Override public void onDisplayChanged(int displayId) { scheduleRefresh(); }

                        private void scheduleRefresh() {
                            h.removeCallbacksAndMessages(DISPLAY_REFRESH_TOKEN);
                            h.postDelayed(CoverNotificationTop::refreshAllControllers, DISPLAY_REFRESH_TOKEN, 0L);
                            h.postDelayed(CoverNotificationTop::refreshAllControllers, DISPLAY_REFRESH_TOKEN, 180L);
                        }
                    }, h);
                    displayObserverRegistered = true;
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void installShadeHeaderHooks(XposedModule module, ClassLoader loader) {
        try {
            Class<?> combinedCls = loader.loadClass(
                    "com.android.systemui.controlcenter.shade.CombinedHeaderController");
            Field contextField = findField(combinedCls, "context");
            Field notifHeaderField = findField(combinedCls, "notificationHeaderView");
            Field shadeHeaderCtrlField = findField(combinedCls, "shadeHeaderController");
            Field notifExpandCtrlField = findField(combinedCls, "notificationExpandProgressController");

            try {
                Method updateCcLayout = combinedCls.getDeclaredMethod("updateControlCenterHeaderLayout");
                module.hook(updateCcLayout).intercept(chain -> {
                    Object self = chain.getThisObject();
                    if (self != null) {
                        COMBINED_CONTROLLERS.add(self);
                    }
                    IN_CONTROL_CENTER_HEADER.set(Boolean.TRUE);
                    try {
                        return chain.proceed();
                    } finally {
                        IN_CONTROL_CENTER_HEADER.set(Boolean.FALSE);
                        if (self != null) {
                            syncNotificationHeaderLayout(
                                    self,
                                    contextField,
                                    notifHeaderField,
                                    shadeHeaderCtrlField,
                                    notifExpandCtrlField);
                        }
                    }
                });
            } catch (NoSuchMethodException e) {
                Log.w(TAG, "CombinedHeaderController.updateControlCenterHeaderLayout not found", e);
            }

            for (String methodName : new String[]{"start", "getHeight", "updateNormalHeaderLocation"}) {
                try {
                    Method m = combinedCls.getDeclaredMethod(methodName);
                    module.hook(m).intercept(chain -> {
                        Object self = chain.getThisObject();
                        if (self != null) {
                            COMBINED_CONTROLLERS.add(self);
                        }
                        Object result = chain.proceed();
                        if (self != null && !"getHeight".equals(methodName)) {
                            syncNotificationHeaderLayout(
                                    self,
                                    contextField,
                                    notifHeaderField,
                                    shadeHeaderCtrlField,
                                    notifExpandCtrlField);
                        }
                        return result;
                    });
                } catch (NoSuchMethodException ignored) {
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "CombinedHeaderController hooks unavailable", t);
        }

        try {
            Class<?> shadeHeaderCls = loader.loadClass(
                    "com.miui.systemui.shade.header.ShadeHeaderControllerImpl");
            Field ctxField = findField(shadeHeaderCls, "context");
            Method getCombinedHeaderHeight = shadeHeaderCls.getDeclaredMethod("getCombinedHeaderHeight");
            module.hook(getCombinedHeaderHeight).intercept(chain -> {
                Object result = chain.proceed();
                if (!(result instanceof Float orig)) {
                    return result;
                }
                Object self = chain.getThisObject();
                Context ctx = getContext(self, ctxField);
                ensureDisplayObserver(ctx);
                boolean outer = isOuterPortrait(ctx);
                boolean enabled = ModuleConfig.frameworkFullscreen();
                boolean inCc = Boolean.TRUE.equals(IN_CONTROL_CENTER_HEADER.get());
                return adjustCombinedHeaderHeight(orig, enabled, outer, inCc);
            });
        } catch (Throwable t) {
            Log.w(TAG, "ShadeHeaderControllerImpl hooks unavailable", t);
        }

        try {
            Class<?> heightAnimatorCls = loader.loadClass(
                    "com.miui.systemui.shade.header.ShadeHeaderHeightAnimator");
            Method updateAnimateHeight = heightAnimatorCls.getDeclaredMethod(
                    "updateAnimateHeight", boolean.class, boolean.class);
            module.hook(updateAnimateHeight).intercept(chain -> {
                Object self = chain.getThisObject();
                if (self != null) {
                    HEIGHT_ANIMATORS.add(self);
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            Log.w(TAG, "ShadeHeaderHeightAnimator hook unavailable", t);
        }
    }

    private static void installHeadsUpHooks(XposedModule module, ClassLoader loader) {
        try {
            Class<?> injectorKt = loader.loadClass(
                    "com.android.systemui.statusbar.notification.policy.HeadsUpManagerInjectorImplKt");
            Method miuiHeadsUpInset = injectorKt.getDeclaredMethod("miuiHeadsUpInset", Context.class);
            miuiHeadsUpInset.setAccessible(true);
            miuiHeadsUpInsetMethod = miuiHeadsUpInset;
            module.hook(miuiHeadsUpInset).intercept(chain -> {
                Object result = chain.proceed();
                if (!(result instanceof Integer orig)) {
                    return result;
                }
                Context ctx = chain.getArg(0) instanceof Context c ? c : null;
                ensureDisplayObserver(ctx);
                return adjustHeadsUpInset(orig, ModuleConfig.frameworkFullscreen(), isOuterPortrait(ctx));
            });
        } catch (Throwable t) {
            Log.w(TAG, "HeadsUpManagerInjectorImplKt hook unavailable", t);
        }

        try {
            Class<?> ssaCls = loader.loadClass(
                    "com.android.systemui.statusbar.notification.stack.StackScrollAlgorithm");
            Class<?> ambientStateCls = loader.loadClass(
                    "com.android.systemui.statusbar.notification.stack.AmbientState");
            Field hostViewField = findField(ssaCls, "mHostView");
            Field headsUpInsetField = findField(ssaCls, "mHeadsUpInset");
            Field headsUpTopPadField = findField(ssaCls, "mHeadsUpTopPad");
            Field headsUpTopPadVerticalField = findField(ssaCls, "mHeadsUpTopPadVertical");
            Field headsUpTopIslandField = findField(ssaCls, "mHeadsUpTopWithDynamicIsland");
            Field flipTinyOffsetField = findField(ssaCls, "mFlipTinyScreenHeadsUpOffset");

            Class<?> nsslCls = loader.loadClass(
                    "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout");
            Field nsslHeadsUpInsetField = findField(nsslCls, "mHeadsUpInset");
            cachedNsslHeadsUpInsetField = nsslHeadsUpInsetField;

            Method resetViewStates = ssaCls.getDeclaredMethod(
                    "resetViewStates", ambientStateCls, int.class);
            module.hook(resetViewStates).intercept(chain -> {
                Object self = chain.getThisObject();
                if (self == null) {
                    return chain.proceed();
                }
                View hostView = getView(self, hostViewField);
                if (hostView != null) {
                    STACK_SCROLL_LAYOUTS.add(hostView);
                    ensureDisplayObserver(hostView.getContext());
                }
                if (!ModuleConfig.frameworkFullscreen()) {
                    return chain.proceed();
                }
                Context ctx = hostView != null ? hostView.getContext() : null;
                if (!isOuterPortrait(ctx)) {
                    return chain.proceed();
                }

                float origInset = getFloat(self, headsUpInsetField);
                float origTopPad = getFloat(self, headsUpTopPadField);
                float origTopPadVert = getFloat(self, headsUpTopPadVerticalField);
                float origTopIsland = getFloat(self, headsUpTopIslandField);
                float origFlipOffset = getFloat(self, flipTinyOffsetField);
                Integer origHostInset = getInt(hostView, nsslHeadsUpInsetField);

                float targetTop = COVER_HEADS_UP_TOP_INSET_PX;
                try {
                    setFloat(self, headsUpInsetField, Math.min(origInset, targetTop));
                    setFloat(self, headsUpTopPadField, Math.min(origTopPad, targetTop));
                    setFloat(self, headsUpTopPadVerticalField, Math.min(origTopPadVert, targetTop));
                    setFloat(self, headsUpTopIslandField, Math.min(origTopIsland, targetTop));
                    setFloat(self, flipTinyOffsetField, 0f);
                    if (origHostInset != null) {
                        setInt(hostView, nsslHeadsUpInsetField,
                                adjustHeadsUpInset(origHostInset, true, true));
                    }
                    return chain.proceed();
                } finally {
                    setFloat(self, headsUpInsetField, origInset);
                    setFloat(self, headsUpTopPadField, origTopPad);
                    setFloat(self, headsUpTopPadVerticalField, origTopPadVert);
                    setFloat(self, headsUpTopIslandField, origTopIsland);
                    setFloat(self, flipTinyOffsetField, origFlipOffset);
                    if (origHostInset != null) {
                        setInt(hostView, nsslHeadsUpInsetField, origHostInset);
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "StackScrollAlgorithm hook unavailable", t);
        }
    }

    private static void syncNotificationHeaderLayout(
            Object combinedCtrl,
            Field contextField,
            Field notifHeaderField,
            Field shadeHeaderCtrlField,
            Field notifExpandCtrlField) {
        try {
            if (notifHeaderField == null || shadeHeaderCtrlField == null) return;
            if (!(notifHeaderField.get(combinedCtrl) instanceof View notifHeader)) return;
            Object shadeHeaderCtrl = shadeHeaderCtrlField.get(combinedCtrl);
            if (shadeHeaderCtrl == null) return;

            Method getHeight = shadeHeaderCtrl.getClass().getDeclaredMethod("getCombinedHeaderHeight");
            getHeight.setAccessible(true);
            Object hObj = getHeight.invoke(shadeHeaderCtrl);
            if (!(hObj instanceof Float targetHeightF)) return;

            int targetHeight = Math.round(targetHeightF);
            ViewGroup.LayoutParams lp = notifHeader.getLayoutParams();
            if (lp != null && lp.height != targetHeight) {
                lp.height = targetHeight;
                notifHeader.setLayoutParams(lp);
                notifHeader.requestLayout();
                if (notifExpandCtrlField != null) {
                    Object expandCtrl = notifExpandCtrlField.get(combinedCtrl);
                    if (expandCtrl != null) {
                        Field flipLocField = findField(expandCtrl.getClass(), "flipBigTimeHeaderLocationY");
                        if (flipLocField != null) {
                            flipLocField.setInt(expandCtrl, -1);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void refreshAllControllers() {
        synchronized (COMBINED_CONTROLLERS) {
            for (Object ctrl : COMBINED_CONTROLLERS) {
                if (ctrl == null) continue;
                Class<?> cls = ctrl.getClass();
                syncNotificationHeaderLayout(
                        ctrl,
                        findField(cls, "context"),
                        findField(cls, "notificationHeaderView"),
                        findField(cls, "shadeHeaderController"),
                        findField(cls, "notificationExpandProgressController"));
            }
        }
        synchronized (HEIGHT_ANIMATORS) {
            for (Object anim : HEIGHT_ANIMATORS) {
                if (anim == null) continue;
                try {
                    Method m = anim.getClass().getDeclaredMethod(
                            "updateAnimateHeight", boolean.class, boolean.class);
                    m.setAccessible(true);
                    m.invoke(anim, false, false);
                } catch (Throwable ignored) {
                }
            }
        }
        Method insetMethod = miuiHeadsUpInsetMethod;
        Field insetField = cachedNsslHeadsUpInsetField;
        if (insetMethod != null && insetField != null) {
            synchronized (STACK_SCROLL_LAYOUTS) {
                for (View layout : STACK_SCROLL_LAYOUTS) {
                    if (layout == null || !layout.isAttachedToWindow()) continue;
                    try {
                        Object val = insetMethod.invoke(null, layout.getContext());
                        if (val instanceof Integer updatedInset && insetField.getInt(layout) != updatedInset) {
                            insetField.setInt(layout, updatedInset);
                            layout.requestLayout();
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    /**
     * Evaluates live Display and Configuration dimensions directly instead of trusting the async
     * {@link CoverGeometry#defaultDisplayOuter()} cache, so synchronous {@code onConfigurationChanged}
     * callbacks during fold/unfold or landscape rotation never cache stale screen insets.
     */
    private static boolean isOuterPortrait(Context context) {
        if (context != null) {
            Display liveDisplay = null;
            try {
                try {
                    liveDisplay = context.getDisplay();
                } catch (Throwable ignored) {
                }
                if (liveDisplay == null) {
                    DisplayManager dm = context.getSystemService(DisplayManager.class);
                    if (dm != null) liveDisplay = dm.getDisplay(Display.DEFAULT_DISPLAY);
                }
            } catch (Throwable ignored) {
            }
            try {
                Configuration cfg = context.getResources().getConfiguration();
                DisplayMetrics dm = context.getResources().getDisplayMetrics();
                if (cfg != null && dm != null && dm.widthPixels > 0 && dm.heightPixels > 0) {
                    // Immediate unfold / landscape guard when Resources has already transitioned
                    if (Math.max(dm.widthPixels, dm.heightPixels) >= 2000
                            && (liveDisplay == null || !CoverDisplay.isOuterPortraitDisplay(liveDisplay))) {
                        return false;
                    }
                    if (!isOuterPortraitMetrics(dm.widthPixels, dm.heightPixels, cfg.orientation)
                            && (liveDisplay == null || !CoverDisplay.isOuterPortraitDisplay(liveDisplay))) {
                        return false;
                    }
                }
            } catch (Throwable ignored) {
            }
            if (liveDisplay != null) {
                return CoverDisplay.isOuterPortraitDisplay(liveDisplay);
            }
        }
        Display outer = context != null ? CoverGeometry.outerDisplay(context) : null;
        if (outer != null) {
            return CoverDisplay.isOuterPortraitDisplay(outer);
        }
        return Boolean.TRUE.equals(CoverGeometry.defaultDisplayOuter());
    }

    private static View getView(Object instance, Field field) {
        if (instance == null || field == null) return null;
        try {
            return field.get(instance) instanceof View v ? v : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Context getContext(Object instance, Field ctxField) {
        if (instance == null || ctxField == null) return null;
        try {
            return ctxField.get(instance) instanceof Context c ? c : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field findField(Class<?> cls, String name) {
        for (Class<?> cur = cls; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
            try {
                Field f = cur.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static Integer getInt(Object instance, Field field) {
        if (instance == null || field == null) return null;
        try {
            if (field.getType() == float.class) {
                return Math.round(field.getFloat(instance));
            }
            return field.getInt(instance);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void setInt(Object instance, Field field, int value) {
        if (instance == null || field == null) return;
        try {
            if (field.getType() == float.class) {
                field.setFloat(instance, (float) value);
            } else {
                field.setInt(instance, value);
            }
        } catch (Throwable ignored) {
        }
    }

    private static float getFloat(Object instance, Field field) {
        if (instance == null || field == null) return 0f;
        try {
            if (field.getType() == int.class) {
                return (float) field.getInt(instance);
            }
            return field.getFloat(instance);
        } catch (Throwable ignored) {
            return 0f;
        }
    }

    private static void setFloat(Object instance, Field field, float value) {
        if (instance == null || field == null) return;
        try {
            if (field.getType() == int.class) {
                field.setInt(instance, Math.round(value));
            } else {
                field.setFloat(instance, value);
            }
        } catch (Throwable ignored) {
        }
    }
}
