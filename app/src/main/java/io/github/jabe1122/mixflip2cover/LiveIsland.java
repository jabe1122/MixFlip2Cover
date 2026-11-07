package io.github.jabe1122.mixflip2cover;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import io.github.libxposed.api.XposedModule;

/** Fits the existing focus-notification window without replacing its content or PendingIntents. */
final class LiveIsland {
    private static final String TAG = "MF2C.Island";
    /** Maximum number of deferred re-fits a layout pass may trigger before waiting for the next native update. */
    private static final int MAX_FIT_RETRIES = 3;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Object controller;
    private static Method updateLayout, transitionAlpha;
    private static Context context;
    private static Host host;
    private static float iconCenter = -1, panelProgress = 1;
    private static boolean observing, updating;
    private static final int OWN_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
    private static WindowManager.LayoutParams original;
    private static boolean adapted, addedUntouchable;
    private static LiveIslandGeometry.Box target;
    private static final Rect targetRect = new Rect();
    private static boolean sizesSquare;
    /** Card size most recently reported by the native pager; read by the layout listeners (never captured). */
    private static int focusCardSize = CoverGeometry.CARD_SIZE;
    private static final WeakHashMap<View, View.OnLayoutChangeListener> focusLayoutListeners = new WeakHashMap<>();
    private static final WeakHashMap<View, int[]> fitRetries = new WeakHashMap<>();
    private static final ConcurrentHashMap<Integer, String> idNameCache = new ConcurrentHashMap<>(32);

    private LiveIsland() {}

    static void install(XposedModule module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> window = loader.loadClass("com.android.notification.decor.DecorWindowController");
        Class<?> manager = loader.loadClass("com.android.notification.decor.DecorWindowManagerImpl");
        updateLayout = window.getDeclaredMethod("updateLayoutParams");
        updateLayout.setAccessible(true);
        transitionAlpha = View.class.getDeclaredMethod("setTransitionAlpha", float.class);
        transitionAlpha.setAccessible(true);
        module.hook(updateLayout).intercept(chain -> {
            Object value = chain.getThisObject();
            try {
                restoreOwnedParams(value);
            } catch (Throwable t) {
                Log.w(TAG, "Native card params restore skipped", t);
            }
            Object result = chain.proceed();
            try {
                configure(value);
            } catch (Throwable e) {
                try {
                    restoreOwnedParams(value);
                    if (host != null) host.nativeSize();
                } catch (Throwable ignored) {
                }
                Log.w(TAG, "Native card layout retained", e);
            }
            return result;
        });
        module.hook(window.getDeclaredMethod("updateLength")).intercept(chain -> {
            Object result = chain.proceed();
            try {
                if (host != null && chain.getThisObject() == controller) {
                    // Native updateLength now sees our outer host; padding belongs to the original root.
                    int top = host.getPaddingTop();
                    host.setPadding(0, 0, 0, 0);
                    host.content.setPadding(0, top, 0, 0);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Native card padding skipped", e);
            }
            return result;
        });
        for (Constructor<?> constructor : manager.getDeclaredConstructors()) {
            module.hook(constructor).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    attachController(CoverGeometry.field(chain.getThisObject(), "mWindowController"));
                } catch (Throwable e) {
                    Log.w(TAG, "Native card attachment skipped", e);
                }
                return result;
            });
        }
        try {
            Method updateContent = manager.getDeclaredMethod("updateContentView", boolean.class);
            updateContent.setAccessible(true);
            module.hook(updateContent).intercept(chain -> {
                Object result = chain.proceed();
                Object mgr = chain.getThisObject();
                fitFocusCards(mgr);
                main.post(() -> fitFocusCards(mgr));
                return result;
            });
        } catch (NoSuchMethodException ignored) {
        }
        try {
            Method onlyUpdateContent = manager.getDeclaredMethod("onlyUpdateContentView", ViewGroup.class, boolean.class);
            onlyUpdateContent.setAccessible(true);
            module.hook(onlyUpdateContent).intercept(chain -> {
                Object result = chain.proceed();
                Object mgr = chain.getThisObject();
                fitFocusCards(mgr);
                main.post(() -> fitFocusCards(mgr));
                return result;
            });
        } catch (NoSuchMethodException ignored) {
        }
        for (Method method : window.getDeclaredMethods()) {
            if (method.getName().equals("showHideWindow") || method.getName().equals("refreshWindow")) {
                module.deoptimize(method);
            }
        }
        ModLog.i(TAG, "native focus-card hooks ready");
    }

    private static void attachController(Object value) throws ReflectiveOperationException {
        if (controller == value && host != null) return;
        Object root = CoverGeometry.field(value, "mWindowRootView");
        if (!(root instanceof View view) || view.getParent() != null) return;
        Field rootField = CoverGeometry.findField(value.getClass(), "mWindowRootView");
        if (rootField == null) throw new NoSuchFieldException("mWindowRootView");
        Host next = new Host(view.getContext(), view);
        rootField.set(value, next);
        controller = value;
        host = next;
        original = null;
        adapted = addedUntouchable = false;
        ModLog.i(TAG, "native focus-card host attached");
    }

    static void start(Context app) {
        if (observing) return;
        observing = true;
        context = app;
        DesktopAlignment.listen(app, center -> {
            iconCenter = center;
            refresh();
        });
        ModuleConfig.addListener(LiveIsland::refresh);
        DisplayManager displays = app.getSystemService(DisplayManager.class);
        if (displays != null) {
            displays.registerDisplayListener(new DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int id) { refresh(); }
                @Override public void onDisplayChanged(int id) { refresh(); }
                @Override public void onDisplayRemoved(int id) { refresh(); }
            }, main);
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        app.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                CoverGeometry.invalidateDeviceState();
                refresh();
            }
        }, filter, Context.RECEIVER_NOT_EXPORTED);
        refresh();
    }

    static void setPanelProgress(float progress) {
        panelProgress = Math.max(0, Math.min(1, progress));
        presentation();
    }

    static void presentation() {
        if (host == null || !host.isAttachedToWindow() || !adapted) return;
        try {
            boolean allowed = visibleOnCover();
            if (allowed && target != null) {
                targetRect.set(target.left(), target.top(), target.right(), target.bottom());
                allowed = !ImeAvoidance.overlaps(host.getDisplay(), targetRect);
            }
            float alpha = allowed ? (1 - panelProgress) * (UnlockMotion.active() ? UnlockMotion.alpha : 1) : 0;
            if (Math.abs(host.getTransitionAlpha() - alpha) > 0.001f) {
                transitionAlpha.invoke(host, alpha);
            }
            WindowManager.LayoutParams params = params(controller);
            boolean block = alpha < .05f;
            boolean wasBlocked = (params.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0;
            if (block && !wasBlocked) {
                params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                addedUntouchable = true;
                updateAttached(params);
            } else if (!block && addedUntouchable) {
                params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                addedUntouchable = false;
                updateAttached(params);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "Card presentation skipped", e);
        }
    }

    /** Uses cached display / keyguard / power state: called once per panel animation frame. */
    private static boolean visibleOnCover() {
        if (context == null || !ModuleConfig.liveIslandEnabled()) return false;
        if (!CoverGeometry.interactive(context) || CoverGeometry.keyguardLocked(context)) return false;
        Display outer = CoverGeometry.outerDisplay(context);
        return outer != null && outer.getState() == Display.STATE_ON && !CoverAppPolicy.hidePanel(outer.getDisplayId());
    }

    private static WindowManager.LayoutParams params(Object value) throws ReflectiveOperationException {
        return (WindowManager.LayoutParams) CoverGeometry.field(value, "mLayoutParams");
    }

    private static void restoreOwnedParams(Object value) throws ReflectiveOperationException {
        if (value != controller || original == null || !adapted) return;
        WindowManager.LayoutParams p = params(value);
        if (p == null) return;
        p.width = original.width;
        p.height = original.height;
        p.x = original.x;
        p.y = original.y;
        p.gravity = original.gravity;
        p.horizontalMargin = original.horizontalMargin;
        p.verticalMargin = original.verticalMargin;
        p.layoutInDisplayCutoutMode = original.layoutInDisplayCutoutMode;
        p.setFitInsetsTypes(original.getFitInsetsTypes());
        if (addedUntouchable) p.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        p.flags = (p.flags & ~OWN_FLAGS) | (original.flags & OWN_FLAGS);
        addedUntouchable = false;
        adapted = false;
    }

    private static void configure(Object value) throws ReflectiveOperationException {
        if (value != controller || host == null) return;
        WindowManager.LayoutParams p = params(value);
        int shortSide = ((Number) CoverGeometry.field(value, "mLengthShort")).intValue();
        int longSide = ((Number) CoverGeometry.field(value, "mLengthLonger")).intValue();
        host.sourceWidth = p.width;
        host.sourceHeight = p.height;
        Object manager = CoverGeometry.field(value, "mDecorWindowManager");
        if (SquareCards.active()) {
            Object pager = CoverGeometry.field(manager, "mPagerView");
            if (pager instanceof View) {
                int inner = ((Number) CoverGeometry.field(pager, "mItemViewWidth")).intValue();
                if (inner > 0) host.sourceWidth = host.sourceHeight = inner;
                host.content.setPadding(0, 0, 0, 0);
            }
        }
        fitFocusCards(manager);
        Display outer = CoverGeometry.outerDisplay(context);
        WindowManager wm = (WindowManager) CoverGeometry.field(value, "mWindowManager");
        Display windowDisplay = wm != null ? wm.getDefaultDisplay() : null;
        if (!ModuleConfig.liveIslandEnabled() || outer == null || outer.getState() != Display.STATE_ON
                || windowDisplay == null || windowDisplay.getDisplayId() != outer.getDisplayId()
                || Math.min(shortSide, longSide) <= 0 || host.sourceWidth <= 0 || host.sourceHeight <= 0) {
            host.nativeSize();
            transitionAlpha.invoke(host, 1f);
            return;
        }
        float density = host.getResources().getDisplayMetrics().density;
        Rect orb = SystemUiOrb.physicalPanelBounds(density, iconCenter);
        int gap = Math.max(12, Math.round(6 * density));
        int nativeWidth = Math.min(shortSide, longSide), nativeHeight = Math.max(shortSide, longSide);
        LiveIslandGeometry.Box physical = LiveIslandGeometry.compact(orb.bottom, gap, nativeWidth, nativeHeight);
        if (physical == null) {
            host.nativeSize();
            return;
        }
        original = CoverGeometry.copy(p);
        int rotation = outer.getRotation();
        target = LiveIslandGeometry.rotated(physical, rotation);
        if (target == null) {
            host.nativeSize();
            return;
        }
        LiveIslandGeometry.Box box = target;
        p.gravity = Gravity.TOP | Gravity.LEFT;
        p.x = box.left();
        p.y = box.top();
        p.width = box.width();
        p.height = box.height();
        p.horizontalMargin = p.verticalMargin = 0;
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        p.setFitInsetsTypes(0);
        p.flags |= OWN_FLAGS;
        boolean blocked = !visibleOnCover() || panelProgress > .95f;
        if (blocked && (p.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0) {
            p.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            addedUntouchable = true;
        }
        adapted = true;
        host.ensurePulse();
        host.requestLayout();
    }

    static void refresh() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(LiveIsland::refresh);
            return;
        }
        if (controller == null || host == null || updating) return;
        updating = true;
        try {
            boolean square = SquareCards.active();
            Object manager = CoverGeometry.field(controller, "mDecorWindowManager");
            if (sizesSquare != square) {
                Method windowSize = controller.getClass().getDeclaredMethod("updateLength");
                windowSize.setAccessible(true);
                windowSize.invoke(controller);
                Object pager = CoverGeometry.field(manager, "mPagerView");
                Method length = pager.getClass().getDeclaredMethod("updateLength");
                length.setAccessible(true);
                length.invoke(pager);
                sizesSquare = square;
                Method content = manager.getClass().getDeclaredMethod("updateContentView", boolean.class);
                content.setAccessible(true);
                content.invoke(manager, false);
            }
            fitFocusCards(manager);
            updateLayout.invoke(controller);
            if (host.isAttachedToWindow()) updateAttached(params(controller));
            presentation();
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "Native card refresh skipped", e);
        } finally {
            updating = false;
        }
    }

    private static void fitFocusCards(Object manager) {
        if (manager == null) return;
        try {
            int cardSize = CoverGeometry.CARD_SIZE;
            Object pager = CoverGeometry.field(manager, "mPagerView");
            if (pager != null) {
                int w = ((Number) CoverGeometry.field(pager, "mItemViewWidth")).intValue();
                if (w > 0) cardSize = w;
            }
            focusCardSize = cardSize;
            Object listObj = CoverGeometry.field(manager, "mChildrenViews");
            if (!(listObj instanceof List<?> children)) return;
            for (Object item : children) {
                if (item instanceof ViewGroup wrapper && wrapper.getChildCount() > 0) {
                    View focusView = wrapper.getChildAt(0);
                    if (focusView != null) {
                        // A native content update starts a fresh retry budget for deferred re-fits.
                        fitRetries.remove(focusView);
                        fitFocusView(wrapper, focusView, cardSize, true);
                        if (!focusLayoutListeners.containsKey(focusView)) {
                            View.OnLayoutChangeListener listener = (v, l, t, r, b, ol, ot, or, ob) -> {
                                if (v.getParent() instanceof ViewGroup parent) {
                                    fitFocusView(parent, v, focusCardSize, false);
                                }
                            };
                            focusLayoutListeners.put(focusView, listener);
                            focusView.addOnLayoutChangeListener(listener);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void fitFocusView(ViewGroup wrapper, View focusView, int cardSize, boolean forceMeasure) {
        if (wrapper == null || focusView == null) return;
        try {
            if (!SquareCards.active() || cardSize <= 0) {
                focusView.setScaleX(1f);
                focusView.setScaleY(1f);
                focusView.setTranslationX(0f);
                focusView.setTranslationY(0f);
                ViewGroup.LayoutParams lp = focusView.getLayoutParams();
                if (lp == null || lp.width != ViewGroup.LayoutParams.MATCH_PARENT || lp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                    focusView.setLayoutParams(new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                }
                return;
            }

            float density = focusView.getResources().getDisplayMetrics().density;
            View areaContainer = findByIdName(focusView, "area_container");
            View areaTop = findByIdName(focusView, "area_top");
            if (areaTop == null) areaTop = findByIdName(focusView, "content_container");
            View areaBottom = findByIdName(focusView, "area_bottom");
            if (areaBottom == null) areaBottom = findByIdName(focusView, "action_list");
            if (areaTop == null || areaBottom == null) return;

            boolean marginChanged = false;
            int maxOuterVertMargin = Math.round(6 * density);
            int horizMargins, vertMargins;
            if (areaContainer != null && areaContainer.getLayoutParams() instanceof ViewGroup.MarginLayoutParams mlp) {
                if (mlp.topMargin > maxOuterVertMargin || mlp.bottomMargin > maxOuterVertMargin) {
                    mlp.topMargin = Math.min(mlp.topMargin, maxOuterVertMargin);
                    mlp.bottomMargin = Math.min(mlp.bottomMargin, maxOuterVertMargin);
                    areaContainer.setLayoutParams(mlp);
                    marginChanged = true;
                }
                horizMargins = mlp.leftMargin + mlp.rightMargin + areaContainer.getPaddingLeft() + areaContainer.getPaddingRight();
                vertMargins = mlp.topMargin + mlp.bottomMargin + areaContainer.getPaddingTop() + areaContainer.getPaddingBottom();
            } else {
                horizMargins = focusView.getPaddingLeft() + focusView.getPaddingRight();
                vertMargins = focusView.getPaddingTop() + focusView.getPaddingBottom();
            }

            int maxBtnGap = Math.round(4 * density);
            String[] btnGroupIds = {"focus_notify_action_layout", "focus_container_module_button_4", "action_list"};
            for (String btnGroupId : btnGroupIds) {
                View btnGroup = findByIdName(areaBottom, btnGroupId);
                if (btnGroup instanceof ViewGroup bg) {
                    for (int i = 0; i < bg.getChildCount(); i++) {
                        View child = bg.getChildAt(i);
                        if (child != null && child.getLayoutParams() instanceof ViewGroup.MarginLayoutParams cmlp) {
                            if (cmlp.topMargin > maxBtnGap || cmlp.bottomMargin > maxBtnGap) {
                                cmlp.topMargin = Math.min(cmlp.topMargin, maxBtnGap);
                                cmlp.bottomMargin = Math.min(cmlp.bottomMargin, maxBtnGap);
                                child.setLayoutParams(cmlp);
                                marginChanged = true;
                            }
                        }
                    }
                }
            }

            if (!forceMeasure) {
                boolean topCrushed = areaTop.getVisibility() == View.VISIBLE
                        && areaBottom.getVisibility() == View.VISIBLE
                        && areaBottom.getHeight() > 0
                        && areaTop.getHeight() < Math.round(38 * density);
                if (marginChanged || topCrushed) {
                    // Layout listeners fire on every requested layout pass; cap the deferred re-fits so a
                    // card that can never satisfy the check does not re-layout forever.
                    int[] retries = fitRetries.computeIfAbsent(focusView, k -> new int[1]);
                    if (retries[0]++ < MAX_FIT_RETRIES) {
                        wrapper.post(() -> fitFocusView(wrapper, focusView, focusCardSize, true));
                    }
                }
                return;
            }

            int innerW = Math.max(1, cardSize - horizMargins);
            int wSpec = View.MeasureSpec.makeMeasureSpec(innerW, View.MeasureSpec.AT_MOST);
            int hSpec = View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.AT_MOST);
            int topH = 0, bottomH = 0;
            if (areaTop.getVisibility() != View.GONE) {
                areaTop.measure(wSpec, hSpec);
                topH = areaTop.getMeasuredHeight();
                if (areaTop.getLayoutParams() instanceof ViewGroup.MarginLayoutParams tlp) {
                    topH += tlp.topMargin + tlp.bottomMargin;
                }
                areaTop.requestLayout();
            }
            if (areaBottom.getVisibility() != View.GONE) {
                areaBottom.measure(wSpec, hSpec);
                bottomH = areaBottom.getMeasuredHeight();
                if (areaBottom.getLayoutParams() instanceof ViewGroup.MarginLayoutParams blp) {
                    bottomH += blp.topMargin + blp.bottomMargin;
                }
                areaBottom.requestLayout();
            }

            int gap = (topH > 0 && bottomH > 0) ? Math.round(10 * density) : 0;
            int neededH = topH + bottomH + gap + vertMargins;

            if (wrapper instanceof LinearLayout ll) {
                ll.setGravity(Gravity.TOP | Gravity.LEFT);
            }
            wrapper.setClipChildren(false);
            wrapper.setClipToPadding(false);
            focusView.setClipToOutline(false);

            int targetSize = neededH > cardSize
                    ? Math.min(neededH, Math.round(cardSize * 1.55f))
                    : cardSize;
            float scale = cardSize / (float) targetSize;

            ViewGroup.LayoutParams lp = focusView.getLayoutParams();
            if (lp == null || lp.width != targetSize || lp.height != targetSize) {
                focusView.setLayoutParams(new LinearLayout.LayoutParams(targetSize, targetSize));
            } else {
                focusView.requestLayout();
            }
            focusView.setPivotX(0f);
            focusView.setPivotY(0f);
            focusView.setScaleX(scale);
            focusView.setScaleY(scale);
            focusView.setTranslationX(0f);
            focusView.setTranslationY(0f);
        } catch (Throwable ignored) {
        }
    }

    private static View findByIdName(View root, String targetEntryName) {
        if (root == null || targetEntryName == null) return null;
        int id = root.getId();
        if (id != View.NO_ID) {
            String entryName = idNameCache.get(id);
            if (entryName == null) {
                try {
                    entryName = root.getResources().getResourceEntryName(id);
                } catch (Throwable ignored) {
                    entryName = "";
                }
                idNameCache.put(id, entryName != null ? entryName : "");
            }
            if (targetEntryName.equals(entryName)) {
                return root;
            }
        }
        if (root instanceof ViewGroup vg) {
            for (int i = 0; i < vg.getChildCount(); i++) {
                View found = findByIdName(vg.getChildAt(i), targetEntryName);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void updateAttached(WindowManager.LayoutParams p) throws ReflectiveOperationException {
        if (host == null || !host.isAttachedToWindow()) return;
        ((WindowManager) CoverGeometry.field(controller, "mWindowManager")).updateViewLayout(host, p);
    }

    /** Scale is a View transform, so Android also inversely transforms native child touch events. */
    private static final class Host extends ViewGroup {
        final View content;
        int sourceWidth = 1, sourceHeight = 1;
        final Runnable pulse = new Runnable() {
            @Override
            public void run() {
                if (!isAttachedToWindow()) return;
                presentation();
                if (adapted && CoverGeometry.interactive(getContext())) {
                    postDelayed(this, 1000);
                }
            }
        };

        void ensurePulse() {
            if (isAttachedToWindow()) {
                removeCallbacks(pulse);
                post(pulse);
            }
        }

        Host(Context c, View nativeRoot) {
            super(c);
            content = nativeRoot;
            setClipChildren(true);
            setClipToPadding(true);
            addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }

        void nativeSize() {
            content.setScaleX(1);
            content.setScaleY(1);
            requestLayout();
        }

        @Override
        protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w), height = MeasureSpec.getSize(h);
            int nativeW = adapted ? sourceWidth : width, nativeH = adapted ? sourceHeight : height;
            content.measure(MeasureSpec.makeMeasureSpec(Math.max(1, nativeW), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.max(1, nativeH), MeasureSpec.EXACTLY));
            setMeasuredDimension(width, height);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int width = content.getMeasuredWidth(), height = content.getMeasuredHeight();
            content.layout(0, 0, width, height);
            if (width <= 0 || height <= 0) return;
            float scale = Math.min(getWidth() / (float) width, getHeight() / (float) height);
            content.setPivotX(0);
            content.setPivotY(0);
            content.setScaleX(scale);
            content.setScaleY(scale);
            content.setTranslationX((getWidth() - width * scale) / 2);
            content.setTranslationY((getHeight() - height * scale) / 2);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            return false;
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            post(() -> {
                refresh();
                removeCallbacks(pulse);
                post(pulse);
            });
            ModLog.i(TAG, "native focus card window attached");
        }

        @Override
        protected void onDetachedFromWindow() {
            removeCallbacks(pulse);
            super.onDetachedFromWindow();
        }
    }
}
