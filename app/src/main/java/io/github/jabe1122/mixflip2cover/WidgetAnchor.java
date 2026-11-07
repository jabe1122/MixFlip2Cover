package io.github.jabe1122.mixflip2cover;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import io.github.libxposed.api.XposedModule;

/** Keeps the existing launcher widget window and its pager below the native live-card window. */
final class WidgetAnchor {
    private static final String TAG = "MF2C.Widget";
    private static final WeakHashMap<View, WindowManager.LayoutParams> windows = new WeakHashMap<>();
    private static final WeakHashMap<View, Integer> pagerGravity = new WeakHashMap<>();
    private static final WeakHashMap<View, Integer> pagerHeights = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> pagerObserved = new WeakHashMap<>();
    private static final WeakHashMap<View, Clip> clips = new WeakHashMap<>();

    private record Clip(ViewOutlineProvider outline, boolean enabled) {}

    private static final ThreadLocal<Boolean> own = new ThreadLocal<>();
    private static float iconCenter = -1;
    private static boolean observing;
    private static Method transitionAlpha;
    private static Constructor<?> animStatesCtor;
    static void install(XposedModule module, ClassLoader loader) throws ReflectiveOperationException {
        WidgetContentFit.install(module, loader);
        Class<?> root = loader.loadClass("com.miui.fliphome.widget.ui.WatchOverlayGroupView");
        Class<?> pagerClass = loader.loadClass("com.miui.fliphome.widget.ui.WidgetPagerView");
        try {
            Class<?> animStatesClass = loader.loadClass("com.miui.fliphome.widget.ui.WidgetPagerView$AnimStates");
            animStatesCtor = animStatesClass.getDeclaredConstructor(pagerClass);
            animStatesCtor.setAccessible(true);
        } catch (ReflectiveOperationException ignored) { }
        transitionAlpha = View.class.getDeclaredMethod("setTransitionAlpha", float.class);
        transitionAlpha.setAccessible(true);
        for (Method m : Class.forName("android.view.WindowManagerGlobal").getDeclaredMethods()) {
            Class<?>[] types = m.getParameterTypes();
            if (!(m.getName().equals("addView") || m.getName().equals("updateViewLayout")) || types.length < 2 || types[0] != View.class) continue;
            module.hook(m).intercept(chain -> {
                if (root.isInstance(chain.getArg(0)) && !Boolean.TRUE.equals(own.get())
                        && chain.getArg(1) instanceof WindowManager.LayoutParams p) {
                    View view = (View) chain.getArg(0);
                    Object[] args = null;
                    try {
                        if (!windows.containsKey(view)) {
                            view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fitPager(v));
                        }
                        WindowManager.LayoutParams saved = windows.computeIfAbsent(view, k -> copy(p));
                        fitPager(view);
                        args = chain.getArgs().toArray();
                        args[1] = layout(view, saved);
                    } catch (RuntimeException e) {
                        Log.w(TAG, "widget window anchor skipped", e);
                    }
                    if (args == null) return chain.proceed();
                    Object result = chain.proceed(args);
                    view.post(() -> fitPager(view));
                    return result;
                }
                return chain.proceed();
            });
        }
        module.hook(root.getDeclaredMethod("updateWindowSize")).intercept(chain -> {
            Object result = chain.proceed();
            try {
                View v = (View) chain.getThisObject();
                if (!SquareCards.active()
                        && CoverGeometry.field(v, "mLayoutParams") instanceof WindowManager.LayoutParams p) {
                    WindowManager.LayoutParams saved = windows.get(v);
                    if (saved != null && p.width > 0 && p.height > 0) {
                        saved.width = p.width;
                        saved.height = p.height;
                    }
                }
                fitPager(v);
                v.post(WidgetAnchor::refresh);
            } catch (Throwable t) {
                Log.w(TAG, "widget window size sync skipped", t);
            }
            return result;
        });
        module.hook(pagerClass.getDeclaredMethod("init")).intercept(chain -> {
            Object result = chain.proceed();
            try {
                if (chain.getThisObject() instanceof View pager) syncPagerSize(pager);
            } catch (Throwable ignored) {
            }
            return result;
        });
        module.hook(pagerClass.getDeclaredMethod("updateSizeByResources", android.content.res.Resources.class)).intercept(chain -> {
            Object result = chain.proceed();
            try {
                if (chain.getThisObject() instanceof View pager) syncPagerSize(pager);
            } catch (Throwable ignored) {
            }
            return result;
        });
    }
    static void observe(Context c) {
        if (observing) return;
        observing = true;
        // The launcher computes the first-row geometry in this same process: listen directly instead of
        // through the SystemUI-targeted DESKTOP_GEOMETRY broadcast (which this process never receives).
        DesktopAlignment.listenLocal(center -> {
            iconCenter = center;
            refresh();
        });
        ImeAvoidance.start(c, WidgetAnchor::refresh, false);
        ModuleConfig.addListener(WidgetAnchor::onConfigChanged);
        DisplayManager dm = c.getSystemService(DisplayManager.class);
        if (dm != null) {
            dm.registerDisplayListener(new DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int id) { refresh(); }
                @Override public void onDisplayChanged(int id) { refresh(); }
                @Override public void onDisplayRemoved(int id) { refresh(); }
            }, new Handler(Looper.getMainLooper()));
        }
    }
    private static void onConfigChanged() {
        for (View v : new ArrayList<>(windows.keySet())) {
            if (v == null) continue;
            try {
                Object pager = CoverGeometry.field(v, "mPagerView");
                if (pager == null) continue;
                CoverGeometry.setField(pager, "mSizedForDpi", -1);
                Method size = pager.getClass().getDeclaredMethod("updateSizeByResources", android.content.res.Resources.class);
                size.setAccessible(true);
                size.invoke(pager, v.getResources());
                Method windowSize = v.getClass().getDeclaredMethod("updateWindowSize");
                windowSize.setAccessible(true);
                windowSize.invoke(v);
            } catch (Throwable t) {
                Log.w(TAG, "widget resize after settings change skipped", t);
            }
        }
        refresh();
    }
    private static Display display(View v) {
        if (v.getDisplay() != null) return v.getDisplay();
        DisplayManager dm = v.getContext().getSystemService(DisplayManager.class);
        return dm != null ? dm.getDisplay(Display.DEFAULT_DISPLAY) : null;
    }
    private static WindowManager.LayoutParams copy(WindowManager.LayoutParams p) {
        return CoverGeometry.copy(p);
    }
    private static WindowManager.LayoutParams layout(View v, WindowManager.LayoutParams nativeParams) {
        WindowManager.LayoutParams p = copy(nativeParams);
        Display d = display(v);
        if (!SquareCards.active() || d == null || !CoverDisplay.isOuter(d)) {
            try {
                transitionAlpha.invoke(v, 1f);
            } catch (ReflectiveOperationException ignored) {
            }
            return p;
        }
        float density = v.getResources().getDisplayMetrics().density;
        Rect orb = SystemUiOrb.physicalPanelBounds(density, iconCenter);
        var box = LiveIslandGeometry.compact(orb.bottom, Math.max(12, Math.round(6 * density)), 1, 1);
        if (box == null) return p;
        box = LiveIslandGeometry.rotated(box, d.getRotation());
        if (box == null) return p;
        p.gravity = Gravity.TOP | Gravity.LEFT;
        p.x = box.left();
        p.y = box.top();
        p.width = box.width();
        p.height = box.height();
        p.horizontalMargin = p.verticalMargin = 0;
        p.setFitInsetsTypes(0);
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        try {
            CoverGeometry.setField(p, "paramsForRotation", null);
        } catch (ReflectiveOperationException ignored) {
        }
        boolean hidden = ImeAvoidance.overlaps(d, new Rect(box.left(), box.top(), box.right(), box.bottom()));
        if (hidden) p.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try {
            transitionAlpha.invoke(v, hidden ? 0f : 1f);
        } catch (ReflectiveOperationException ignored) {
        }
        return p;
    }
    private static void syncPagerSize(View pager) {
        try {
            boolean active = SquareCards.active();
            int itemW = ((Number) CoverGeometry.field(pager, "mItemViewWidth")).intValue();
            if (itemW <= 0) return;
            int itemH = ((Number) CoverGeometry.field(pager, "mItemViewHeight")).intValue();
            if (itemH > itemW) {
                pagerHeights.putIfAbsent(pager, itemH);
            }
            if (!active) {
                int origH = pagerHeights.getOrDefault(pager, Math.round(itemW * 602f / 356f));
                if (itemH != origH) {
                    itemH = origH;
                    setField(pager, "mItemViewHeight", itemH);
                    setField(pager, "mTransYThreshold", (itemH / 2f) / 3f);
                    rebuildAnimStates(pager);
                }
                int radius = ((Number) CoverGeometry.field(pager, "mRadius")).intValue();
                if (radius > 0) {
                    float r = radius;
                    pager.getClass().getMethod("setCornerRadii", float[].class)
                            .invoke(pager, (Object) new float[]{r, r, r, r, 0f, 0f, 0f, 0f});
                }
                if (pager.getLayoutParams() instanceof FrameLayout.LayoutParams lp) {
                    int origG = pagerGravity.getOrDefault(pager, lp.gravity);
                    if (lp.width != itemW || lp.height != itemH || lp.gravity != origG) {
                        lp.width = itemW;
                        lp.height = itemH;
                        lp.gravity = origG;
                        pager.setLayoutParams(lp);
                    }
                }
                if (CoverGeometry.field(pager, "mContainers") instanceof List<?> containers) {
                    for (Object c : containers) {
                        if (c instanceof View cv) {
                            ViewGroup.LayoutParams clp = cv.getLayoutParams();
                            if (clp != null && (clp.width != itemW || clp.height != itemH)) {
                                clp.width = itemW;
                                clp.height = itemH;
                                cv.setLayoutParams(clp);
                            }
                        }
                    }
                }
                return;
            }
            if (itemH != itemW) {
                setField(pager, "mItemViewHeight", itemW);
                setField(pager, "mTransYThreshold", (itemW / 2f) / 3f);
                rebuildAnimStates(pager);
            }
            int radius = ((Number) CoverGeometry.field(pager, "mRadius")).intValue();
            if (radius > 0) {
                float r = radius;
                pager.getClass().getMethod("setCornerRadii", float[].class)
                        .invoke(pager, (Object) new float[]{r, r, r, r, r, r, r, r});
            }
            if (pager.getLayoutParams() instanceof FrameLayout.LayoutParams lp) {
                pagerGravity.putIfAbsent(pager, lp.gravity);
                if (lp.height > itemW) pagerHeights.putIfAbsent(pager, lp.height);
                if (lp.width != itemW || lp.height != itemW || lp.gravity != Gravity.CENTER) {
                    lp.width = itemW;
                    lp.height = itemW;
                    lp.gravity = Gravity.CENTER;
                    pager.setLayoutParams(lp);
                }
            }
            if (CoverGeometry.field(pager, "mContainers") instanceof List<?> containers) {
                for (Object c : containers) {
                    if (c instanceof View cv) {
                        ViewGroup.LayoutParams clp = cv.getLayoutParams();
                        if (clp != null && (clp.width != itemW || clp.height != itemW)) {
                            clp.width = itemW;
                            clp.height = itemW;
                            cv.setLayoutParams(clp);
                        }
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
    }
    private static void rebuildAnimStates(View pager) {
        if (animStatesCtor == null) return;
        try {
            setField(pager, "mAnimStates", animStatesCtor.newInstance(pager));
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
    }
    private static void setField(Object target, String name, Object value) throws ReflectiveOperationException {
        CoverGeometry.setField(target, name, value);
    }
    private static void fitPager(View v) {
        try {
            if (CoverGeometry.field(v, "mPagerView") instanceof View pager) {
                if (!pagerObserved.containsKey(pager)) {
                    pagerObserved.put(pager, Boolean.TRUE);
                    pager.addOnLayoutChangeListener((pv, l, t, r, b, ol, ot, or, ob) -> fitPager(v));
                }
                syncPagerSize(pager);
                if (!SquareCards.active()) {
                    restoreClip(v);
                    restoreClip(pager);
                    pager.setScaleX(1);
                    pager.setScaleY(1);
                    return;
                }
                v.setPadding(0, 0, 0, 0);
                rounded(v);
                rounded(pager);
                int itemW = ((Number) CoverGeometry.field(pager, "mItemViewWidth")).intValue();
                float pw = itemW > 0 ? itemW : pager.getWidth();
                float ph = itemW > 0 ? itemW : pager.getHeight();
                if (v.getWidth() > 0 && v.getHeight() > 0 && pw > 0 && ph > 0) {
                    float scale = Math.min(v.getWidth() / pw, v.getHeight() / ph);
                    float pivotX = pager.getWidth() > 0 ? pager.getWidth() / 2f : pw / 2f;
                    float pivotY = pager.getHeight() == pager.getWidth() && pager.getHeight() > 0 ? pager.getHeight() / 2f : ph / 2f;
                    pager.setPivotX(pivotX); pager.setPivotY(pivotY);
                    pager.setScaleX(scale); pager.setScaleY(scale);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
    }
    private static void rounded(View view) {
        if (clips.containsKey(view)) {
            view.invalidateOutline();
            return;
        }
        clips.put(view, new Clip(view.getOutlineProvider(), view.getClipToOutline()));
        view.setClipToOutline(true);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                float radius = Math.min(v.getWidth(), v.getHeight()) * .18f;
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
    }

    private static void restoreClip(View view) {
        Clip clip = clips.remove(view);
        if (clip != null) {
            view.setOutlineProvider(clip.outline());
            view.setClipToOutline(clip.enabled());
        }
    }
    private static void refresh() {
        for (View v : new ArrayList<>(windows.keySet())) {
            if (v == null || !v.isAttachedToWindow()) continue;
            WindowManager.LayoutParams saved = windows.get(v);
            if (saved == null) continue;
            try {
                own.set(true);
                WindowManager wm = v.getContext().getSystemService(WindowManager.class);
                if (wm != null) wm.updateViewLayout(v, layout(v, saved));
                fitPager(v);
            } catch (RuntimeException ignored) { } finally { own.remove(); }
        }
    }
}
