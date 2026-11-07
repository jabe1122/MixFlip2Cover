package io.github.jabe1122.mixflip2cover;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import io.github.libxposed.api.XposedModule;

/**
 * Samples notification shade and control center expansion once per display frame.
 */
final class PanelMotion {
    private static final String TAG = "MF2C.Panel";
    private static final String[] HEADER_IDS = {
            "normal_notification_header_view",
            "normal_control_center_header_view",
            "control_center_header_view",
            "normal_control_center_status_bar",
            "normal_fake_control_center_status_bar",
            "control_center_status_bar"
    };

    static Object notification, control, controlCenter;
    static boolean notificationOpen, controlOpen, queued;
    static float notificationFraction, controlFraction;
    static Context context;
    static Consumer<Float> listener;
    static Method transitionAlpha;
    static final Handler main = new Handler(Looper.getMainLooper());

    static final class Header {
        final int kind;
        float nativeAlpha = 1f;

        Header(int k) {
            kind = k;
        }
    }

    static final WeakHashMap<View, Header> headers = new WeakHashMap<>();
    static final Set<View> scanned = Collections.newSetFromMap(new WeakHashMap<>());
    static final ThreadLocal<Boolean> ownAlpha = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> inTouchRemap = new ThreadLocal<>();
    private static volatile long activeRemapDownTime = -1L;
    private static volatile float activeRemapDeltaX = 0f;
    private static volatile int activeRemapWidth = CoverDisplay.WIDTH;
    private static volatile long finishedRemapDownTime = -1L;
    private static volatile float finishedRemapDeltaX = 0f;
    private static volatile int finishedRemapWidth = CoverDisplay.WIDTH;
    static long keepSamplingUntil;
    static int lastBucket = -1, logged;

    /** Reflection cache for the per-frame expansion sampling (keyed by the controller's class). */
    private static Class<?> expansionOwner, valueOwner, containerOwner;
    private static Method getExpansionState, getValue, getContainer;
    private static int[] headerIds;
    private static final Choreographer.FrameCallback FRAME = t -> frame();

    private PanelMotion() {}

    static void install(XposedModule module, ClassLoader loader) throws ReflectiveOperationException {
        String[] names = {
                "com.android.systemui.shade.NotificationPanelExpandController",
                "com.miui.systemui.controlcenter.container.ControlCenterExpandControllerDelegate"
        };
        for (int i = 0; i < names.length; i++) {
            final boolean isNotification = i == 0;
            Class<?> c = loader.loadClass(names[i]);
            for (Constructor<?> ctor : c.getDeclaredConstructors()) {
                module.hook(ctor).intercept(chain -> {
                    Object result = chain.proceed();
                    if (isNotification) notification = chain.getThisObject();
                    else control = chain.getThisObject();
                    wake();
                    return result;
                });
            }
            for (Method m : c.getDeclaredMethods()) {
                String name = m.getName();
                if (name.equals("notifyExpandHeightChanged") || name.equals("onExpansionChanged")
                        || name.equals("notifyAppearanceChanged") || name.equals("onAppearanceChanged")) {
                    module.hook(m).intercept(chain -> {
                        Object result = chain.proceed();
                        if (isNotification) notification = chain.getThisObject();
                        else control = chain.getThisObject();
                        wake();
                        return result;
                    });
                }
            }
        }
        transitionAlpha = View.class.getDeclaredMethod("setTransitionAlpha", float.class);
        transitionAlpha.setAccessible(true);
        module.hook(transitionAlpha).intercept(chain -> {
            // Cheapest checks first: empty map / own call skip the WeakHashMap lookup.
            if (!headers.isEmpty()
                    && !Boolean.TRUE.equals(ownAlpha.get())
                    && chain.getThisObject() instanceof View v
                    && (Object) headers.get(v) instanceof Header h) {
                Float adjusted = null;
                try {
                    h.nativeAlpha = (Float) chain.getArg(0);
                    adjusted = h.nativeAlpha * headerFactor(v, h);
                } catch (RuntimeException e) {
                    Log.w(TAG, "header alpha skipped", e);
                }
                if (adjusted != null) return chain.proceed(new Object[]{adjusted});
            }
            return chain.proceed();
        });
        String[] headerClasses = {
                "com.android.systemui.qs.MiuiNotificationHeaderView",
                "com.android.systemui.qs.MiuiQSHeaderView",
                "com.android.systemui.statusbar.views.MiuiNotificationHeaderClock"
        };
        for (String name : headerClasses) {
            try {
                Class<?> c = loader.loadClass(name);
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals("onAttachedToWindow") || m.getName().equals("onFinishInflate")) {
                        module.hook(m).intercept(chain -> {
                            Object result = chain.proceed();
                            try {
                                register((View) chain.getThisObject(), name.contains("QSHeader") ? 2 : 1);
                            } catch (RuntimeException ignored) {
                            }
                            return result;
                        });
                    }
                }
            } catch (ClassNotFoundException ignored) {
            }
        }
        try {
            Class<?> shadeWindowClass = loader.loadClass(
                    "com.android.systemui.shade.NotificationShadeWindowView");
            Method dispatchTouchEvent = shadeWindowClass.getDeclaredMethod(
                    "dispatchTouchEvent", MotionEvent.class);
            module.hook(dispatchTouchEvent).intercept(chain -> {
                if (chain.getArg(0) instanceof MotionEvent event) {
                    View hostView = chain.getThisObject() instanceof View v ? v : null;
                    MotionEvent remapped = handlePullDownTouchStream(event, hostView, "shade_dispatch");
                    if (remapped != event) {
                        inTouchRemap.set(Boolean.TRUE);
                        try {
                            return chain.proceed(new Object[]{remapped});
                        } finally {
                            inTouchRemap.remove();
                            remapped.recycle();
                        }
                    }
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            Log.w(TAG, "NotificationShadeWindowView hook unavailable", t);
        }
        try {
            Class<?> handlerClass = loader.loadClass(
                    "com.miui.systemui.controlcenter.container.ControlCenterEventHandlerImpl");
            Method handleExpandEvent = handlerClass.getDeclaredMethod(
                    "handleExpandEvent", MotionEvent.class, String.class);
            module.hook(handleExpandEvent).intercept(chain -> {
                if (chain.getArg(0) instanceof MotionEvent event) {
                    MotionEvent remapped = handlePullDownTouchStream(
                            event, null, String.valueOf(chain.getArg(1)));
                    if (remapped != event) {
                        inTouchRemap.set(Boolean.TRUE);
                        try {
                            return chain.proceed(new Object[]{remapped, chain.getArg(1)});
                        } finally {
                            inTouchRemap.remove();
                            remapped.recycle();
                        }
                    }
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            Log.w(TAG, "ControlCenterEventHandlerImpl hook unavailable", t);
        }
    }

    static boolean shouldStartPullDownSplit(
            boolean fullscreenEnabled,
            boolean outerDisplay,
            boolean notifExpanded,
            boolean ctrlExpanded,
            float rawY) {
        return fullscreenEnabled
                && outerDisplay
                && !notifExpanded
                && !ctrlExpanded
                && rawY >= 0f
                && rawY <= CoverResourceOverlay.NATIVE_COVER_STATUS_BAR_PX;
    }

    static float computePullDownDeltaX(float downRawX, int displayWidth) {
        if (displayWidth <= 0) return 0f;
        boolean toControl = CoverDisplay.isControlCenterPull(downRawX, displayWidth);
        if (!toControl) {
            float maxNotifX = displayWidth * 0.42f;
            return downRawX > maxNotifX ? (maxNotifX - downRawX) : 0f;
        }
        float minControlX = Math.max(700f, Math.round(displayWidth * 0.58f));
        return downRawX < minControlX ? (minControlX - downRawX) : 0f;
    }

    private static MotionEvent handlePullDownTouchStream(
            MotionEvent event, View hostView, String source) {
        if (event == null || Boolean.TRUE.equals(inTouchRemap.get())) {
            return event;
        }
        try {
            int action = event.getActionMasked();
            long downTime = event.getDownTime();
            if (action == MotionEvent.ACTION_DOWN) {
                if (downTime == activeRemapDownTime && Math.abs(activeRemapDeltaX) >= 0.5f) {
                    return obtainShiftedEvent(event, activeRemapDeltaX, activeRemapWidth);
                }
                activeRemapDownTime = -1L;
                activeRemapDeltaX = 0f;
                finishedRemapDownTime = -1L;
                finishedRemapDeltaX = 0f;
                boolean outer = isLiveOuterDisplay(hostView);
                boolean notifExpanded = notification != null
                        ? fraction(notification, false) > 0.05f
                        : (notificationOpen && (context == null || !CoverGeometry.keyguardLocked(context)));
                boolean ctrlExpanded = control != null
                        ? fraction(control, false) > 0.05f
                        : controlOpen;
                if (!shouldStartPullDownSplit(
                        ModuleConfig.frameworkFullscreen(),
                        outer,
                        notifExpanded,
                        ctrlExpanded,
                        event.getRawY())) {
                    return event;
                }
                int width = coverPullDisplayWidth(hostView);
                float rawX = event.getRawX();
                boolean toControl = CoverDisplay.isControlCenterPull(rawX, width);
                float deltaX = computePullDownDeltaX(rawX, width);
                ModLog.i(TAG, "pull-down split (" + source + ") x=" + Math.round(rawX)
                        + " deltaX=" + Math.round(deltaX)
                        + " -> " + (toControl ? "control_center" : "notifications"));
                if (Math.abs(deltaX) < 0.5f) {
                    return event;
                }
                activeRemapDownTime = downTime;
                activeRemapDeltaX = deltaX;
                activeRemapWidth = width;
                return obtainShiftedEvent(event, deltaX, width);
            }
            if (downTime == activeRemapDownTime && Math.abs(activeRemapDeltaX) >= 0.5f) {
                float deltaX = activeRemapDeltaX;
                int width = activeRemapWidth;
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    finishedRemapDownTime = downTime;
                    finishedRemapDeltaX = deltaX;
                    finishedRemapWidth = width;
                    activeRemapDownTime = -1L;
                    activeRemapDeltaX = 0f;
                }
                return obtainShiftedEvent(event, deltaX, width);
            }
            if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                    && downTime == finishedRemapDownTime
                    && Math.abs(finishedRemapDeltaX) >= 0.5f) {
                return obtainShiftedEvent(event, finishedRemapDeltaX, finishedRemapWidth);
            }
            return event;
        } catch (Throwable t) {
            Log.w(TAG, "handlePullDownTouchStream skipped", t);
            return event;
        }
    }

    private static MotionEvent obtainShiftedEvent(MotionEvent event, float deltaX, int width) {
        int pointerCount = event.getPointerCount();
        float maxX = Math.max(1f, width - 1f);
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[pointerCount];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[pointerCount];
        for (int p = 0; p < pointerCount; p++) {
            props[p] = new MotionEvent.PointerProperties();
            event.getPointerProperties(p, props[p]);
            coords[p] = new MotionEvent.PointerCoords();
        }
        float localOffsetX = event.getX() - event.getRawX();
        float localOffsetY = event.getY() - event.getRawY();
        float rawDeltaBaseX = -localOffsetX;
        float rawDeltaBaseY = -localOffsetY;
        int historySize = event.getHistorySize();
        MotionEvent copy;
        if (historySize > 0) {
            for (int p = 0; p < pointerCount; p++) {
                event.getHistoricalPointerCoords(p, 0, coords[p]);
                coords[p].x = Math.max(0f, Math.min(maxX, coords[p].x + rawDeltaBaseX + deltaX));
                coords[p].y = coords[p].y + rawDeltaBaseY;
            }
            copy = MotionEvent.obtain(
                    event.getDownTime(),
                    event.getHistoricalEventTime(0),
                    event.getAction(),
                    pointerCount,
                    props,
                    coords,
                    event.getMetaState(),
                    event.getButtonState(),
                    event.getXPrecision(),
                    event.getYPrecision(),
                    event.getDeviceId(),
                    event.getEdgeFlags(),
                    event.getSource(),
                    event.getFlags());
            for (int h = 1; h < historySize; h++) {
                for (int p = 0; p < pointerCount; p++) {
                    event.getHistoricalPointerCoords(p, h, coords[p]);
                    coords[p].x = Math.max(0f, Math.min(maxX, coords[p].x + rawDeltaBaseX + deltaX));
                    coords[p].y = coords[p].y + rawDeltaBaseY;
                }
                copy.addBatch(event.getHistoricalEventTime(h), coords, event.getMetaState());
            }
            for (int p = 0; p < pointerCount; p++) {
                event.getPointerCoords(p, coords[p]);
                coords[p].x = Math.max(0f, Math.min(maxX, coords[p].x + rawDeltaBaseX + deltaX));
                coords[p].y = coords[p].y + rawDeltaBaseY;
            }
            copy.addBatch(event.getEventTime(), coords, event.getMetaState());
        } else {
            for (int p = 0; p < pointerCount; p++) {
                event.getPointerCoords(p, coords[p]);
                coords[p].x = Math.max(0f, Math.min(maxX, coords[p].x + rawDeltaBaseX + deltaX));
                coords[p].y = coords[p].y + rawDeltaBaseY;
            }
            copy = MotionEvent.obtain(
                    event.getDownTime(),
                    event.getEventTime(),
                    event.getAction(),
                    pointerCount,
                    props,
                    coords,
                    event.getMetaState(),
                    event.getButtonState(),
                    event.getXPrecision(),
                    event.getYPrecision(),
                    event.getDeviceId(),
                    event.getEdgeFlags(),
                    event.getSource(),
                    event.getFlags());
        }
        if (Math.abs(localOffsetX) > 0.01f || Math.abs(localOffsetY) > 0.01f) {
            copy.offsetLocation(localOffsetX, localOffsetY);
        }
        return copy;
    }

    private static boolean isLiveOuterDisplay(View hostView) {
        try {
            if (hostView != null) {
                Display d = hostView.getDisplay();
                if (d != null) return CoverDisplay.isOuterDisplay(d);
            }
            Context c = context;
            if (c != null) {
                DisplayManager dm = c.getSystemService(DisplayManager.class);
                Display d = dm != null ? dm.getDisplay(Display.DEFAULT_DISPLAY) : null;
                if (d == null) {
                    try {
                        d = c.getDisplay();
                    } catch (Throwable ignored) {
                    }
                }
                if (d != null) return CoverDisplay.isOuterDisplay(d);
            }
        } catch (Throwable ignored) {
        }
        return SystemBarMask.anyOuterBar() || Boolean.TRUE.equals(CoverGeometry.defaultDisplayOuter());
    }

    private static int coverPullDisplayWidth(View hostView) {
        try {
            Display d = hostView != null ? hostView.getDisplay() : null;
            Context c = context;
            if (d == null && c != null) {
                DisplayManager dm = c.getSystemService(DisplayManager.class);
                d = dm != null ? dm.getDisplay(Display.DEFAULT_DISPLAY) : c.getDisplay();
            }
            if (d != null && !CoverDisplay.isPortraitRotation(d.getRotation())) {
                return CoverDisplay.HEIGHT;
            }
        } catch (Throwable ignored) {
        }
        return CoverDisplay.WIDTH;
    }

    static void start(Context c, Consumer<Float> callback) {
        context = c;
        listener = callback;
        wake();
    }

    static void expanded(boolean n, boolean c) {
        notificationOpen = n;
        controlOpen = c;
        if (!n) notificationFraction = 0f;
        if (!c) controlFraction = 0f;
        wake();
    }

    static void wake() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(PanelMotion::wake);
            return;
        }
        keepSamplingUntil = SystemClock.uptimeMillis() + 700;
        if (!queued) {
            queued = true;
            Choreographer.getInstance().postFrameCallback(FRAME);
        }
    }

    static float fraction(Object controller, boolean fallback) {
        if (controller == null) return fallback ? 1f : 0f;
        try {
            Class<?> type = controller.getClass();
            if (type != expansionOwner) {
                getExpansionState = type.getMethod("getExpansionState");
                expansionOwner = type;
            }
            Object state = getExpansionState.invoke(controller);
            if (state != null) {
                Class<?> stateType = state.getClass();
                if (stateType != valueOwner) {
                    getValue = stateType.getMethod("getValue");
                    valueOwner = stateType;
                }
                Object value = getValue.invoke(state);
                if (value instanceof Number n) return Math.max(0f, Math.min(1f, n.floatValue()));
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            expansionOwner = valueOwner = null;
        }
        return fallback ? 1f : 0f;
    }

    static void frame() {
        queued = false;
        try {
            notificationFraction = fraction(notification, notificationOpen);
            controlFraction = fraction(control, controlOpen);
            int bucket = Math.round(notificationFraction * 10) * 20 + Math.round(controlFraction * 10);
            if (bucket != lastBucket && logged++ < 70) {
                lastBucket = bucket;
                ModLog.i(TAG, "notification=" + notificationFraction + " control=" + controlFraction);
            }
            if (listener != null) listener.accept(Math.max(notificationFraction, controlFraction));
            fadeNative();
        } catch (RuntimeException e) {
            Log.w(TAG, "panel frame skipped", e);
        }
        if (SystemClock.uptimeMillis() < keepSamplingUntil) {
            queued = true;
            Choreographer.getInstance().postFrameCallback(FRAME);
        }
    }

    static void alpha(View v, float value) {
        if (transitionAlpha == null) return;
        ownAlpha.set(true);
        try {
            transitionAlpha.invoke(v, value);
        } catch (ReflectiveOperationException ignored) {
        } finally {
            ownAlpha.remove();
        }
    }

    static float headerFactor(View v, Header h) {
        if (context == null || !ModuleConfig.frameworkFullscreen() || !CoverGeometry.isOuterPortrait(v)
                || CoverGeometry.keyguardLocked(context)) {
            return 1f;
        }
        return h.kind == 1 ? notificationFraction
                : h.kind == 2 ? controlFraction
                : Math.max(notificationFraction, controlFraction);
    }

    static void register(View v, int kind) {
        if (v != null && headers.putIfAbsent(v, new Header(kind)) == null) {
            ModLog.i(TAG, "native header mapped: " + v.getClass().getSimpleName() + " kind=" + kind);
        }
    }

    static void scanRoot(View root) {
        if (root == null || !scanned.add(root)) return;
        int[] ids = headerIds;
        if (ids == null) {
            ids = new int[HEADER_IDS.length];
            for (int i = 0; i < HEADER_IDS.length; i++) {
                ids[i] = root.getResources().getIdentifier(HEADER_IDS[i], "id", CoverGeometry.SYSTEMUI);
            }
            headerIds = ids;
        }
        for (int i = 0; i < ids.length; i++) {
            View v = ids[i] == 0 ? null : root.findViewById(ids[i]);
            if (v != null) register(v, i == 0 ? 1 : 2);
        }
    }

    static void fadeNative() {
        try {
            Object center = controlCenter;
            if (center != null) {
                Class<?> type = center.getClass();
                if (type != containerOwner) {
                    getContainer = type.getMethod("getContainer");
                    containerOwner = type;
                }
                if (getContainer.invoke(center) instanceof View v) scanRoot(v.getRootView());
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            containerOwner = null;
        }
        for (var entry : new ArrayList<>(headers.entrySet())) {
            View v = entry.getKey();
            Header h = entry.getValue();
            if (v != null && v.isAttachedToWindow()) {
                scanRoot(v.getRootView());
                alpha(v, h.nativeAlpha * headerFactor(v, h));
            }
        }
    }
}
