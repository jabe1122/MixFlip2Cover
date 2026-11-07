package io.github.jabe1122.mixflip2cover;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.WeakHashMap;
import io.github.libxposed.api.XposedModule;

/**
 * Masks the native status bar window on the cover display when framework fullscreen is active, and narrows its
 * touchable region in the left app column (`x < 810`) to the top bezel edge so immersive cover apps can receive
 * taps on their top toolbars and tabs.
 */
final class SystemBarMask {
    private static final String TAG = "MF2C.Mask";
    /** Top bezel strip (px) in the left app column kept touchable for pulling down the notification shade. */
    private static final int TOP_PULL_EDGE_PX = 18;
    private static final int TOUCHABLE_INSETS_FRAME = 0;
    private static final int TOUCHABLE_INSETS_REGION = 3;

    static final class Bar {
        float alpha;
        Display display;
        Boolean lastMasked;
        Boolean lastHidePanel;
        Integer lastRotation;
        Object insetsListener;
        Region nativeRegion;
        WeakReference<View> phoneStatusBarViewRef;
        boolean touchForwarderAttached;
        boolean regionLogged;

        Bar(float a, Display d) {
            alpha = a;
            display = d;
        }
    }

    static final WeakHashMap<View, Bar> bars = new WeakHashMap<>();
    static final ThreadLocal<Boolean> ownChange = new ThreadLocal<>();
    static boolean observing;

    private static Class<?> phoneStatusBarViewClass;
    private static Field phoneStatusBarTouchableRegionField;
    private static Class<?> listenerInterface;
    private static Class<?> infoClass;
    private static Method addInsetsListener;
    private static Method removeInsetsListener;
    private static Method setTouchableInsets;
    private static Field touchableRegionField;
    private static Method getViewRootImpl;
    private static Method vriSetTouchableRegion;
    private static Field vriWindowField;
    private static Object windowSession;
    private static Method sessionSetInsets;
    private static Method sessionClearTouchableRegion;
    private static final ThreadLocal<Boolean> ownRegionChange = new ThreadLocal<>();
    private static boolean reflectionResolved;

    private SystemBarMask() {}

    private static synchronized void resolveInsetsReflection() {
        if (reflectionResolved) return;
        reflectionResolved = true;
        try {
            getViewRootImpl = View.class.getDeclaredMethod("getViewRootImpl");
            getViewRootImpl.setAccessible(true);
            Class<?> vriClass = Class.forName("android.view.ViewRootImpl");
            vriWindowField = vriClass.getDeclaredField("mWindow");
            vriWindowField.setAccessible(true);
            vriSetTouchableRegion = vriClass.getDeclaredMethod("setTouchableRegion", Region.class);
            vriSetTouchableRegion.setAccessible(true);
        } catch (Throwable t) {
            Log.w(TAG, "ViewRootImpl reflection unavailable", t);
        }
        try {
            Class<?> wmgClass = Class.forName("android.view.WindowManagerGlobal");
            Method getSession = wmgClass.getDeclaredMethod("getWindowSession");
            getSession.setAccessible(true);
            windowSession = getSession.invoke(null);
            if (windowSession != null) {
                Class<?> iWindowClass = Class.forName("android.view.IWindow");
                Class<?> sc = windowSession.getClass();
                sessionSetInsets = sc.getDeclaredMethod(
                        "setInsets", iWindowClass, int.class, Rect.class, Rect.class, Region.class);
                sessionSetInsets.setAccessible(true);
                sessionClearTouchableRegion = sc.getDeclaredMethod("clearTouchableRegion", iWindowClass);
                sessionClearTouchableRegion.setAccessible(true);
            }
        } catch (Throwable t) {
            Log.w(TAG, "IWindowSession reflection unavailable", t);
        }
        try {
            listenerInterface = Class.forName("android.view.ViewTreeObserver$OnComputeInternalInsetsListener");
            infoClass = Class.forName("android.view.ViewTreeObserver$InternalInsetsInfo");
            addInsetsListener = ViewTreeObserver.class.getDeclaredMethod("addOnComputeInternalInsetsListener", listenerInterface);
            addInsetsListener.setAccessible(true);
            removeInsetsListener = ViewTreeObserver.class.getDeclaredMethod("removeOnComputeInternalInsetsListener", listenerInterface);
            removeInsetsListener.setAccessible(true);
            setTouchableInsets = infoClass.getDeclaredMethod("setTouchableInsets", int.class);
            setTouchableInsets.setAccessible(true);
            touchableRegionField = infoClass.getDeclaredField("touchableRegion");
            touchableRegionField.setAccessible(true);
        } catch (Throwable t) {
            Log.w(TAG, "internal insets reflection unavailable", t);
        }
    }

    static boolean outer(View v, Bar b) {
        try {
            Display d = v != null ? v.getDisplay() : null;
            if (d == null && b != null) d = b.display;
            if (d == null && v != null && v.getContext() != null) {
                DisplayManager dm = v.getContext().getSystemService(DisplayManager.class);
                if (dm != null) d = dm.getDisplay(Display.DEFAULT_DISPLAY);
            }
            return CoverDisplay.isOuterDisplay(d);
        } catch (RuntimeException e) {
            return false;
        }
    }

    static boolean anyOuterBar() {
        for (var entry : bars.entrySet()) {
            View v = entry.getKey();
            Bar b = entry.getValue();
            if (v != null && b != null && v.isAttachedToWindow() && outer(v, b)) return true;
        }
        return false;
    }

    private static View findPhoneStatusBarView(View root, Bar b) {
        if (b != null && b.phoneStatusBarViewRef != null) {
            View cached = b.phoneStatusBarViewRef.get();
            if (cached != null && cached.isAttachedToWindow()) {
                return cached;
            }
        }
        View found = searchPhoneStatusBarView(root);
        if (b != null && found != null) {
            b.phoneStatusBarViewRef = new WeakReference<>(found);
        }
        return found;
    }

    private static View searchPhoneStatusBarView(View v) {
        if (v == null) return null;
        if (phoneStatusBarViewClass != null && phoneStatusBarViewClass.isInstance(v)) {
            return v;
        }
        if ("PhoneStatusBarView".equals(v.getClass().getSimpleName())) {
            return v;
        }
        if (v instanceof ViewGroup vg) {
            for (int i = 0, n = vg.getChildCount(); i < n; i++) {
                View found = searchPhoneStatusBarView(vg.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void expandPhoneStatusBarTouchableRegion(View phoneView) {
        if (phoneView == null || phoneStatusBarTouchableRegionField == null) return;
        try {
            Object r = phoneStatusBarTouchableRegionField.get(phoneView);
            if (r instanceof Region region) {
                region.set(
                        -CoverDisplay.WIDTH,
                        -CoverDisplay.HEIGHT,
                        CoverDisplay.WIDTH * 2,
                        CoverDisplay.HEIGHT * 2);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void attachCutoutTouchForwarder(View v, Bar b) {
        if (v == null || b == null || b.touchForwarderAttached) return;
        b.touchForwarderAttached = true;
        v.setOnTouchListener((view, event) -> {
            Bar cur = bars.get(view);
            if (event == null || !ModuleConfig.frameworkFullscreen() || !outer(view, cur)) {
                return false;
            }
            View phoneView = findPhoneStatusBarView(view, cur);
            if (phoneView == null) return false;
            expandPhoneStatusBarTouchableRegion(phoneView);
            return phoneView.onTouchEvent(event);
        });
    }

    private static void attachInsetsComputer(View v, Bar b) {
        resolveInsetsReflection();
        if (listenerInterface == null || addInsetsListener == null || setTouchableInsets == null || touchableRegionField == null) {
            return;
        }
        try {
            if (b.insetsListener == null) {
                WeakReference<View> viewRef = new WeakReference<>(v);
                WeakReference<Bar> barRef = new WeakReference<>(b);
                b.insetsListener = Proxy.newProxyInstance(
                        listenerInterface.getClassLoader(),
                        new Class<?>[]{listenerInterface},
                        (proxy, method, args) -> {
                            String name = method.getName();
                            if ("onComputeInternalInsets".equals(name) && args != null && args.length == 1 && args[0] != null) {
                                View targetView = viewRef.get();
                                Bar targetBar = barRef.get();
                                if (targetView != null && targetBar != null) {
                                    computeTouchableRegion(targetView, targetBar, args[0]);
                                }
                                return null;
                            }
                            if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                            if ("equals".equals(name)) return args != null && args.length == 1 && proxy == args[0];
                            if ("toString".equals(name)) return "MF2CStatusBarInsetsComputer";
                            return null;
                        });
            }
            ViewTreeObserver vto = v.getViewTreeObserver();
            if (vto != null && vto.isAlive()) {
                if (removeInsetsListener != null) {
                    removeInsetsListener.invoke(vto, b.insetsListener);
                }
                addInsetsListener.invoke(vto, b.insetsListener);
            }
        } catch (Throwable t) {
            Log.w(TAG, "status bar insets listener attach failed", t);
        }
    }

    private static void detachInsetsComputer(View v, Bar b) {
        if (v == null || b == null || b.insetsListener == null || removeInsetsListener == null) return;
        try {
            ViewTreeObserver vto = v.getViewTreeObserver();
            if (vto != null && vto.isAlive()) {
                removeInsetsListener.invoke(vto, b.insetsListener);
            }
        } catch (Throwable ignored) {
        }
    }

    static int coverTouchableTopWidth(int rotation) {
        return CoverDisplay.isPortraitRotation(rotation) ? CoverDisplay.WIDTH : CoverDisplay.HEIGHT;
    }

    private static Region buildCoverTouchableRegion(View v, Bar b) {
        Display d = v != null ? v.getDisplay() : null;
        if (d == null && b != null) d = b.display;
        int displayId = d != null ? d.getDisplayId() : Display.DEFAULT_DISPLAY;
        int rotation = d != null ? d.getRotation() : android.view.Surface.ROTATION_0;
        boolean portrait = CoverDisplay.isPortraitRotation(rotation);
        Region region = new Region(0, 0, coverTouchableTopWidth(rotation), TOP_PULL_EDGE_PX);
        if (portrait && !CoverAppPolicy.hidePanel(displayId)) {
            int left = rotation == android.view.Surface.ROTATION_180 ? 0 : CoverDisplay.CAMERA_LEFT;
            int right = rotation == android.view.Surface.ROTATION_180
                    ? (CoverDisplay.WIDTH - CoverDisplay.CAMERA_LEFT)
                    : CoverDisplay.WIDTH;
            region.op(
                    left,
                    0,
                    right,
                    CoverResourceOverlay.NATIVE_COVER_STATUS_BAR_PX,
                    Region.Op.UNION);
        }
        return region;
    }

    private static Object getIWindow(View v) {
        if (v == null || getViewRootImpl == null || vriWindowField == null) return null;
        try {
            Object vri = getViewRootImpl.invoke(v);
            return vri == null ? null : vriWindowField.get(vri);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void syncVriTouchableRegion(View v, Bar b, boolean masked) {
        resolveInsetsReflection();
        try {
            Object vri = getViewRootImpl != null ? getViewRootImpl.invoke(v) : null;
            if (vri == null) return;
            Region target;
            if (masked) {
                target = buildCoverTouchableRegion(v, b);
            } else if (b.nativeRegion != null) {
                target = new Region(b.nativeRegion);
            } else {
                target = null;
            }
            ownRegionChange.set(true);
            try {
                if (vriSetTouchableRegion != null) {
                    vriSetTouchableRegion.invoke(vri, target);
                }
                if (windowSession != null && vriWindowField != null) {
                    Object iWindow = vriWindowField.get(vri);
                    if (iWindow != null) {
                        if (target != null) {
                            if (sessionSetInsets != null) {
                                Rect empty = new Rect();
                                sessionSetInsets.invoke(windowSession, iWindow, TOUCHABLE_INSETS_REGION, empty, empty, target);
                            }
                        } else if (sessionClearTouchableRegion != null) {
                            sessionClearTouchableRegion.invoke(windowSession, iWindow);
                        } else if (sessionSetInsets != null) {
                            Rect empty = new Rect();
                            sessionSetInsets.invoke(windowSession, iWindow, TOUCHABLE_INSETS_FRAME, empty, empty, new Region());
                        }
                    }
                }
                ModLog.i(TAG, "syncVriTouchableRegion masked=" + masked + " target=" + target);
            } finally {
                ownRegionChange.remove();
            }
        } catch (Throwable t) {
            Log.w(TAG, "syncVriTouchableRegion skipped", t);
        }
    }

    private static void computeTouchableRegion(View v, Bar b, Object inoutInfo) {
        try {
            if (!ModuleConfig.frameworkFullscreen() || !outer(v, b)) return;
            Region region = (Region) touchableRegionField.get(inoutInfo);
            if (region == null) return;
            setTouchableInsets.invoke(inoutInfo, TOUCHABLE_INSETS_REGION);
            // Keep only the top bezel strip in the left app column so app toolbars/tabs at y >= 18px receive taps,
            // while still allowing a top-bezel downward swipe to open the notification shade.
            region.set(buildCoverTouchableRegion(v, b));
            if (!b.regionLogged) {
                b.regionLogged = true;
                ModLog.i(TAG, "status bar touchableRegion narrowed: " + region);
            }
        } catch (Throwable t) {
            Log.w(TAG, "computeTouchableRegion skipped", t);
        }
    }

    static void apply(View v) {
        Bar b = bars.get(v);
        if (b == null) return;
        boolean masked = ModuleConfig.frameworkFullscreen() && outer(v, b);
        Display d = v != null ? v.getDisplay() : null;
        if (d == null) d = b.display;
        boolean hidePanel = CoverAppPolicy.hidePanel(d != null ? d.getDisplayId() : Display.DEFAULT_DISPLAY);
        int rotation = d != null ? d.getRotation() : android.view.Surface.ROTATION_0;
        float target = masked ? 0f : b.alpha;
        if (Math.abs(v.getAlpha() - target) >= 0.001f) {
            ownChange.set(true);
            try {
                v.setAlpha(target);
            } finally {
                ownChange.remove();
            }
        }
        if (b.lastMasked == null || b.lastMasked != masked
                || b.lastHidePanel == null || b.lastHidePanel != hidePanel
                || b.lastRotation == null || b.lastRotation != rotation) {
            b.lastMasked = masked;
            b.lastHidePanel = hidePanel;
            b.lastRotation = rotation;
            attachCutoutTouchForwarder(v, b);
            attachInsetsComputer(v, b);
            syncVriTouchableRegion(v, b, masked);
            v.requestLayout();
            v.invalidate();
        }
    }

    static void refresh() {
        for (View v : new ArrayList<>(bars.keySet())) {
            if (v != null) apply(v);
        }
    }

    static void capture(View v, Display d) {
        Bar b = bars.get(v);
        if (b == null) {
            b = new Bar(v.getAlpha(), d);
            bars.put(v, b);
            v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View view) {
                    Bar bar = bars.get(view);
                    if (bar != null) {
                        attachInsetsComputer(view, bar);
                        bar.lastMasked = null;
                        apply(view);
                    }
                }

                @Override
                public void onViewDetachedFromWindow(View view) {
                    Bar bar = bars.get(view);
                    if (bar != null) {
                        detachInsetsComputer(view, bar);
                    }
                }
            });
            ModLog.i(TAG, "native status window captured");
        } else if (d != null) {
            b.display = d;
        }
        attachCutoutTouchForwarder(v, b);
        attachInsetsComputer(v, b);
        apply(v);
        WeakReference<View> viewRef = new WeakReference<>(v);
        v.post(() -> {
            View target = viewRef.get();
            if (target == null) return;
            Bar bar = bars.get(target);
            if (bar != null) {
                attachCutoutTouchForwarder(target, bar);
                attachInsetsComputer(target, bar);
                bar.lastMasked = null;
                apply(target);
            }
        });
    }

    static void install(XposedModule module, ClassLoader loader) throws Exception {
        if (loader != null) {
            try {
                phoneStatusBarViewClass = loader.loadClass("com.android.systemui.statusbar.phone.PhoneStatusBarView");
                phoneStatusBarTouchableRegionField = phoneStatusBarViewClass.getDeclaredField("mTouchableRegion");
                phoneStatusBarTouchableRegionField.setAccessible(true);
                Method onTouchEvent = phoneStatusBarViewClass.getDeclaredMethod("onTouchEvent", MotionEvent.class);
                module.hook(onTouchEvent).intercept(chain -> {
                    if (ModuleConfig.frameworkFullscreen() && chain.getThisObject() instanceof View phoneView) {
                        if (outer(phoneView, null)
                                || anyOuterBar()
                                || Boolean.TRUE.equals(CoverGeometry.defaultDisplayOuter())) {
                            expandPhoneStatusBarTouchableRegion(phoneView);
                        }
                    }
                    return chain.proceed();
                });
            } catch (Throwable t) {
                Log.w(TAG, "PhoneStatusBarView touchableRegion hook unavailable", t);
            }
        }
        Class<?> global = Class.forName("android.view.WindowManagerGlobal");
        for (Method method : global.getDeclaredMethods()) {
            if (!(method.getName().equals("addView") || method.getName().equals("updateViewLayout"))) continue;
            Class<?>[] p = method.getParameterTypes();
            if (p.length < 2 || p[0] != View.class) continue;
            module.hook(method).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    if (chain.getArg(0) instanceof View v
                            && chain.getArg(1) instanceof WindowManager.LayoutParams lp
                            && lp.type == WindowManager.LayoutParams.TYPE_STATUS_BAR) {
                        Display d = null;
                        for (Object arg : chain.getArgs()) {
                            if (arg instanceof Display display) d = display;
                        }
                        capture(v, d);
                    }
                } catch (RuntimeException e) {
                    Log.w(TAG, "window capture skipped", e);
                }
                return result;
            });
        }
        resolveInsetsReflection();
        if (sessionSetInsets != null) {
            try {
                module.hook(sessionSetInsets).intercept(chain -> {
                    if (!bars.isEmpty() && !Boolean.TRUE.equals(ownRegionChange.get())) {
                        Object win = chain.getArg(0);
                        if (win != null) {
                            for (var entry : bars.entrySet()) {
                                View v = entry.getKey();
                                Bar b = entry.getValue();
                                if (v != null && b != null && win == getIWindow(v)) {
                                    if (chain.getArg(4) instanceof Region r) {
                                        b.nativeRegion = new Region(r);
                                    }
                                    if (ModuleConfig.frameworkFullscreen() && outer(v, b)) {
                                        Object[] args = chain.getArgs().toArray();
                                        args[1] = TOUCHABLE_INSETS_REGION;
                                        args[4] = buildCoverTouchableRegion(v, b);
                                        return chain.proceed(args);
                                    }
                                    break;
                                }
                            }
                        }
                    }
                    return chain.proceed();
                });
            } catch (Throwable t) {
                Log.w(TAG, "IWindowSession.setInsets hook unavailable", t);
            }
        }
        if (sessionClearTouchableRegion != null && sessionSetInsets != null && windowSession != null) {
            try {
                module.hook(sessionClearTouchableRegion).intercept(chain -> {
                    if (!bars.isEmpty() && !Boolean.TRUE.equals(ownRegionChange.get())) {
                        Object win = chain.getArg(0);
                        if (win != null) {
                            for (var entry : bars.entrySet()) {
                                View v = entry.getKey();
                                Bar b = entry.getValue();
                                if (v != null && b != null && win == getIWindow(v)) {
                                    b.nativeRegion = null;
                                    if (ModuleConfig.frameworkFullscreen() && outer(v, b)) {
                                        ownRegionChange.set(true);
                                        try {
                                            Rect empty = new Rect();
                                            sessionSetInsets.invoke(
                                                    windowSession,
                                                    win,
                                                    TOUCHABLE_INSETS_REGION,
                                                    empty,
                                                    empty,
                                                    buildCoverTouchableRegion(v, b));
                                        } finally {
                                            ownRegionChange.remove();
                                        }
                                        return null;
                                    }
                                    break;
                                }
                            }
                        }
                    }
                    return chain.proceed();
                });
            } catch (Throwable t) {
                Log.w(TAG, "IWindowSession.clearTouchableRegion hook unavailable", t);
            }
        }
        if (vriSetTouchableRegion != null && getViewRootImpl != null) {
            try {
                module.hook(vriSetTouchableRegion).intercept(chain -> {
                    if (!bars.isEmpty() && !Boolean.TRUE.equals(ownRegionChange.get())) {
                        Object vri = chain.getThisObject();
                        for (var entry : bars.entrySet()) {
                            View v = entry.getKey();
                            Bar b = entry.getValue();
                            if (v != null && b != null && vri == getViewRootImpl.invoke(v)) {
                                if (chain.getArg(0) instanceof Region r) {
                                    b.nativeRegion = new Region(r);
                                } else {
                                    b.nativeRegion = null;
                                }
                                if (ModuleConfig.frameworkFullscreen() && outer(v, b)) {
                                    return chain.proceed(new Object[]{buildCoverTouchableRegion(v, b)});
                                }
                                break;
                            }
                        }
                    }
                    return chain.proceed();
                });
            } catch (Throwable t) {
                Log.w(TAG, "setTouchableRegion hook unavailable", t);
            }
        }
        if (infoClass != null) {
            try {
                Method dispatch = ViewTreeObserver.class.getDeclaredMethod("dispatchOnComputeInternalInsets", infoClass);
                module.hook(dispatch).intercept(chain -> {
                    Object res = chain.proceed();
                    if (!bars.isEmpty() && chain.getThisObject() instanceof ViewTreeObserver vto) {
                        Object info = chain.getArg(0);
                        if (info != null) {
                            for (var entry : bars.entrySet()) {
                                View v = entry.getKey();
                                Bar b = entry.getValue();
                                if (v != null && b != null && v.getViewTreeObserver() == vto) {
                                    computeTouchableRegion(v, b, info);
                                    break;
                                }
                            }
                        }
                    }
                    return res;
                });
            } catch (Throwable t) {
                Log.w(TAG, "dispatchOnComputeInternalInsets hook unavailable", t);
            }
        }
        module.hook(View.class.getDeclaredMethod("setAlpha", float.class)).intercept(chain -> {
            // Hot path for every view in SystemUI: status-bar windows are root views (parent is the
            // ViewRootImpl, not a View), so child views are rejected with one field read and instanceof.
            if (chain.getThisObject() instanceof View v
                    && !(v.getParent() instanceof View)
                    && !bars.isEmpty()
                    && !Boolean.TRUE.equals(ownChange.get())) {
                boolean mask = false;
                try {
                    Bar b = bars.get(v);
                    if (b != null) {
                        b.alpha = (Float) chain.getArg(0);
                        mask = ModuleConfig.frameworkFullscreen() && outer(v, b);
                    }
                } catch (RuntimeException ignored) {
                }
                if (mask) return chain.proceed(new Object[]{0f});
            }
            return chain.proceed();
        });
    }

    static void observe(Context c) {
        if (observing) return;
        observing = true;
        ModuleConfig.addListener(SystemBarMask::refresh);
        DisplayManager dm = c.getSystemService(DisplayManager.class);
        if (dm != null) {
            dm.registerDisplayListener(new DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int id) { refresh(); }
                @Override public void onDisplayChanged(int id) { refresh(); }
                @Override public void onDisplayRemoved(int id) { refresh(); }
            }, new Handler(Looper.getMainLooper()));
        }
        refresh();
    }
}
