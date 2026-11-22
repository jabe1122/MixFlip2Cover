package io.github.jabe1122.mixflip2cover;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import java.util.WeakHashMap;
import io.github.libxposed.api.XposedModule;

/**
 * Give a downward drag starting at the outer launcher's top edge or home surface to the system shade,
 * and lock the cover display when double-tapping empty desktop space.
 */
final class LauncherPullDown {
    private static final String TAG = "MF2C.Gesture";
    static final String ACTION = "io.github.jabe1122.mixflip2cover.OPEN_PANEL";
    private static final long MAX_SINGLE_TAP_MS = 260L;
    static final WeakHashMap<Object, Gesture> gestures = new WeakHashMap<>();
    static final WeakHashMap<Object, DoubleTapTracker> doubleTaps = new WeakHashMap<>();

    static final class Gesture {
        float x, y;
        long downTime;
        boolean eligible, intercepted, control;
    }

    static final class DoubleTapTracker {
        float downX, downY;
        long downTime;
        boolean validTapCandidate;
        long lastUpTime;
        float lastUpX, lastUpY;
    }

    private LauncherPullDown() {}

    static boolean isWithinDoubleTapWindow(
            long prevUpMs,
            long nowUpMs,
            float prevX,
            float prevY,
            float nowX,
            float nowY,
            long timeoutMs,
            float maxDistPx) {
        if (prevUpMs <= 0L || nowUpMs <= prevUpMs || timeoutMs <= 0L || maxDistPx <= 0f) {
            return false;
        }
        if (nowUpMs - prevUpMs > timeoutMs) {
            return false;
        }
        float dx = nowX - prevX;
        float dy = nowY - prevY;
        return (dx * dx + dy * dy) <= (maxDistPx * maxDistPx);
    }

    static boolean outer(Activity activity) {
        Display d = activity != null ? activity.getDisplay() : null;
        return CoverDisplay.isOuterDisplay(d);
    }

    static boolean outerView(View view) {
        if (view == null) return false;
        Display d = view.getDisplay();
        if (d == null && view.getContext() instanceof Activity a) d = a.getDisplay();
        if (d == null) return AppCompatibility.outerProcess;
        Display.Mode mode = d.getMode();
        if (mode == null) return AppCompatibility.outerProcess;
        return CoverDisplay.isOuterDimensions(mode.getPhysicalWidth(), mode.getPhysicalHeight());
    }

    static int logicalDisplayWidth(int rotation) {
        return CoverDisplay.isPortraitRotation(rotation) ? CoverDisplay.WIDTH : CoverDisplay.HEIGHT;
    }

    static int logicalDisplayHeight(int rotation) {
        return CoverDisplay.isPortraitRotation(rotation) ? CoverDisplay.HEIGHT : CoverDisplay.WIDTH;
    }

    /** True when rawX lands in the non-app-grid camera/widget column on portrait cover rotations. */
    static boolean isInPortraitCameraColumn(float rawX, int rotation) {
        if (rotation == Surface.ROTATION_0) {
            return rawX >= CoverDisplay.CAMERA_LEFT;
        }
        if (rotation == Surface.ROTATION_180) {
            return rawX < (CoverDisplay.WIDTH - CoverDisplay.CAMERA_LEFT);
        }
        return false;
    }

    private static int rotationOf(View view) {
        Display d = view != null ? view.getDisplay() : null;
        if (d == null && view != null && view.getContext() instanceof Activity a) d = a.getDisplay();
        return d != null ? d.getRotation() : Surface.ROTATION_0;
    }

    private static int rotationOf(Activity activity) {
        Display d = activity != null ? activity.getDisplay() : null;
        return d != null ? d.getRotation() : Surface.ROTATION_0;
    }

    private static float eventRawX(MotionEvent event) {
        float rawX = event.getRawX();
        float x = event.getX();
        return (rawX == 0f && x != 0f) ? x : rawX;
    }

    private static float eventRawY(MotionEvent event) {
        float rawY = event.getRawY();
        float y = event.getY();
        return (rawY == 0f && y != 0f) ? y : rawY;
    }

    private static boolean isPointInsideView(View v, float rawX, float rawY) {
        if (v == null || v.getVisibility() != View.VISIBLE || v.getAlpha() < 0.05f) return false;
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        float w = v.getWidth() * v.getScaleX();
        float h = v.getHeight() * v.getScaleY();
        if (w <= 0f || h <= 0f) return false;
        return rawX >= loc[0] && rawX <= (loc[0] + w)
                && rawY >= loc[1] && rawY <= (loc[1] + h);
    }

    private static boolean isPointInsideVisibleWidgetPager(View watchGroupView, float rawX, float rawY) {
        if (watchGroupView == null) return false;
        try {
            if (!(CoverGeometry.field(watchGroupView, "mPagerView") instanceof View pager)) {
                return false;
            }
            return isPointInsideView(pager, rawX, rawY);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Returns true when {@code WatchOverlayGroupView}'s child {@code WidgetPagerView} is visible,
     * hosts more than one widget card ({@code !isSingleItem()}), and {@code (rawX, rawY)} lands inside
     * its on-screen bounds — in which case vertical drags must be left to {@code WidgetPagerView} so
     * the user can swipe both up and down between stacked widgets.
     */
    private static boolean isMultiItemWidgetPagerTouch(View watchGroupView, float rawX, float rawY) {
        if (watchGroupView == null) return false;
        try {
            if (!(CoverGeometry.field(watchGroupView, "mPagerView") instanceof View pager)) {
                return false;
            }
            if (pager.getVisibility() != View.VISIBLE || pager.getAlpha() < 0.5f) {
                return false;
            }
            Object singleObj = pager.getClass().getMethod("isSingleItem").invoke(pager);
            if (Boolean.TRUE.equals(singleObj)) {
                return false;
            }
            int[] loc = new int[2];
            pager.getLocationOnScreen(loc);
            float w = pager.getWidth() * pager.getScaleX();
            float h = pager.getHeight() * pager.getScaleY();
            if (w <= 0f || h <= 0f) {
                return true;
            }
            return rawX >= loc[0] && rawX <= (loc[0] + w)
                    && rawY >= loc[1] && rawY <= (loc[1] + h);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hitsDesktopInteractiveChild(View root, View node, float rawX, float rawY) {
        if (node == null || node.getVisibility() != View.VISIBLE || node.getAlpha() < 0.05f) {
            return false;
        }
        String cls = node.getClass().getName();
        if ("com.miui.fliphome.view.LauncherAppItem".equals(cls)
                || "com.miui.fliphome.view.ShortcutMenu".equals(cls)
                || "com.miui.fliphome.widget.ui.WidgetPagerView".equals(cls)
                || "com.miui.fliphome.recents.views.RecentsContainer".equals(cls)
                || "com.miui.fliphome.recents.views.TaskView".equals(cls)
                || cls.contains("Folder")) {
            if (isPointInsideView(node, rawX, rawY)) {
                return true;
            }
        }
        if (node instanceof ViewGroup vg) {
            for (int i = vg.getChildCount() - 1; i >= 0; i--) {
                if (hitsDesktopInteractiveChild(root, vg.getChildAt(i), rawX, rawY)) {
                    return true;
                }
            }
            // Non-root compact clickable ViewGroups (folders, action pills, edit controls) are interactive.
            if (node != root && node.isClickable()
                    && node.getWidth() > 0 && node.getHeight() > 0
                    && node.getWidth() < Math.round(CoverDisplay.WIDTH * 0.85f)
                    && node.getHeight() < Math.round(CoverDisplay.HEIGHT * 0.65f)
                    && isPointInsideView(node, rawX, rawY)) {
                return true;
            }
        } else if (node.isClickable() && isPointInsideView(node, rawX, rawY)) {
            return true;
        }
        return false;
    }

    private static boolean hasVisibleLauncherOverlay(View node) {
        if (node == null || node.getVisibility() != View.VISIBLE || node.getAlpha() < 0.05f) {
            return false;
        }
        String cls = node.getClass().getName();
        if ("com.miui.fliphome.view.ShortcutMenu".equals(cls)
                || cls.contains("FolderContainer")
                || cls.contains("FolderView")
                || cls.contains("AppFolder")) {
            return node.getWidth() > 0 && node.getHeight() > 0;
        }
        if (node instanceof ViewGroup vg) {
            for (int i = vg.getChildCount() - 1; i >= 0; i--) {
                if (hasVisibleLauncherOverlay(vg.getChildAt(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isLauncherOverlayOpen(Activity activity) {
        if (activity == null) return false;
        if (QuickActionsWidget.isInOverviewOnCover(activity)) return true;
        View decor = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
        return decor != null && hasVisibleLauncherOverlay(decor);
    }

    private static boolean isEmptyDesktopTap(Activity activity, View rootView, float rawX, float rawY) {
        if (activity != null && isLauncherOverlayOpen(activity)) {
            return false;
        }
        View target = rootView;
        if (target == null && activity != null && activity.getWindow() != null) {
            target = activity.getWindow().getDecorView();
        }
        if (target == null) return false;
        return !hitsDesktopInteractiveChild(target, target, rawX, rawY);
    }

    private static void sendLockCoverBroadcast(Context context, String source) {
        if (context == null) return;
        try {
            context.sendBroadcast(new Intent(ACTION)
                    .setPackage(CoverGeometry.SYSTEMUI)
                    .putExtra("lock_cover", true));
            ModLog.i(TAG, "double-tap empty cover space -> lock screen (" + source + ")");
        } catch (RuntimeException e) {
            Log.w(TAG, "double-tap lock request not sent", e);
        }
    }

    private static boolean handleDoubleTapTracking(
            Context context,
            Activity activity,
            View rootView,
            DoubleTapTracker dt,
            MotionEvent event,
            boolean isWatchOverlay,
            boolean gestureIntercepted) {
        if (context == null || dt == null || !ModuleConfig.doubleTapLockEnabled() || isKeyguardLocked(context)) {
            if (dt != null) {
                dt.validTapCandidate = false;
                dt.lastUpTime = 0L;
            }
            return false;
        }
        int action = event.getActionMasked();
        float rawX = eventRawX(event);
        float rawY = eventRawY(event);
        ViewConfiguration vc = ViewConfiguration.get(context);
        float slop = vc.getScaledTouchSlop();

        if (action == MotionEvent.ACTION_DOWN) {
            boolean empty = isWatchOverlay
                    ? !isPointInsideVisibleWidgetPager(rootView, rawX, rawY)
                            && !hitsDesktopInteractiveChild(rootView, rootView, rawX, rawY)
                    : isEmptyDesktopTap(activity, rootView, rawX, rawY);
            dt.validTapCandidate = empty;
            dt.downX = rawX;
            dt.downY = rawY;
            dt.downTime = SystemClock.uptimeMillis();
            return false;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN || event.getPointerCount() > 1 || gestureIntercepted) {
            dt.validTapCandidate = false;
            dt.lastUpTime = 0L;
            return false;
        }
        if (action == MotionEvent.ACTION_MOVE && dt.validTapCandidate) {
            float dx = rawX - dt.downX;
            float dy = rawY - dt.downY;
            if ((dx * dx + dy * dy) > (slop * slop)
                    || (SystemClock.uptimeMillis() - dt.downTime) > MAX_SINGLE_TAP_MS) {
                dt.validTapCandidate = false;
            }
            return false;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            dt.validTapCandidate = false;
            return false;
        }
        if (action == MotionEvent.ACTION_UP && dt.validTapCandidate) {
            dt.validTapCandidate = false;
            long nowUp = SystemClock.uptimeMillis();
            float dx = rawX - dt.downX;
            float dy = rawY - dt.downY;
            if ((dx * dx + dy * dy) > (slop * slop) || (nowUp - dt.downTime) > MAX_SINGLE_TAP_MS) {
                dt.lastUpTime = 0L;
                return false;
            }
            float maxDoubleTapDist = Math.max(vc.getScaledDoubleTapSlop(), slop * 3f);
            if (isWithinDoubleTapWindow(
                    dt.lastUpTime,
                    nowUp,
                    dt.lastUpX,
                    dt.lastUpY,
                    rawX,
                    rawY,
                    ViewConfiguration.getDoubleTapTimeout(),
                    maxDoubleTapDist)) {
                dt.lastUpTime = 0L;
                sendLockCoverBroadcast(context, isWatchOverlay ? "widget_column" : "desktop");
                return true;
            }
            dt.lastUpTime = nowUp;
            dt.lastUpX = rawX;
            dt.lastUpY = rawY;
        }
        return false;
    }

    static void install(XposedModule module, ClassLoader loader) throws ReflectiveOperationException {
        try {
            Class<?> watchWindow = loader.loadClass("com.miui.fliphome.widget.WatchOverlayWindow");
            module.hook(watchWindow.getDeclaredMethod("onInputMonitorEvent", MotionEvent.class)).intercept(chain -> {
                try {
                    if (ModuleConfig.frameworkFullscreen()) return false;
                } catch (RuntimeException ignored) {
                }
                return chain.proceed();
            });
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "watch overlay input monitor hook skipped", e);
        }
        try {
            Class<?> watchGroup = loader.loadClass("com.miui.fliphome.widget.ui.WatchOverlayGroupView");
            module.hook(watchGroup.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class)).intercept(chain -> {
                if (!(chain.getThisObject() instanceof View view) || !(chain.getArg(0) instanceof MotionEvent event)) {
                    return chain.proceed();
                }
                Context context = view.getContext();
                if (context == null) return chain.proceed();
                Gesture gesture = gestures.computeIfAbsent(view, v -> new Gesture());
                DoubleTapTracker dt = doubleTaps.computeIfAbsent(view, v -> new DoubleTapTracker());
                boolean outerNow = outerView(view);
                if (outerNow && handleDoubleTapTracking(context, null, view, dt, event, true, gesture.intercepted)) {
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    try {
                        chain.proceed(new Object[]{cancel});
                    } finally {
                        cancel.recycle();
                    }
                    return true;
                }
                boolean enabled;
                try {
                    enabled = ModuleConfig.frameworkFullscreen() && outerNow;
                } catch (RuntimeException e) {
                    enabled = false;
                }
                if (!enabled) {
                    gesture.eligible = gesture.intercepted = false;
                    return chain.proceed();
                }
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    int rotation = rotationOf(view);
                    int width = logicalDisplayWidth(rotation);
                    int height = logicalDisplayHeight(rotation);
                    gesture.x = eventRawX(event);
                    gesture.y = eventRawY(event);
                    gesture.downTime = event.getDownTime();
                    gesture.intercepted = false;
                    gesture.control = CoverDisplay.isControlCenterPull(gesture.x, width, rotation);
                    gesture.eligible = gesture.x >= 0 && gesture.x < width
                            && gesture.y >= 0 && gesture.y < height
                            && !isMultiItemWidgetPagerTouch(view, gesture.x, gesture.y);
                    return chain.proceed();
                }
                if (gesture.intercepted) {
                    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                        gesture.intercepted = false;
                    }
                    return true;
                }
                if (action == MotionEvent.ACTION_POINTER_DOWN || event.getPointerCount() > 1) {
                    gesture.eligible = false;
                }
                if (action == MotionEvent.ACTION_MOVE && gesture.eligible) {
                    if (gesture.downTime > 0
                            && event.getEventTime() - gesture.downTime >= ViewConfiguration.getLongPressTimeout()) {
                        gesture.eligible = false;
                        return chain.proceed();
                    }
                    float dx = eventRawX(event) - gesture.x, dy = eventRawY(event) - gesture.y;
                    float slop = ViewConfiguration.get(context).getScaledTouchSlop();
                    if (dy < -slop || (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy))) {
                        gesture.eligible = false;
                    } else if (dy > slop && dy > Math.abs(dx) * 1.2f) {
                        MotionEvent cancel = MotionEvent.obtain(event);
                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                        try {
                            chain.proceed(new Object[]{cancel});
                        } finally {
                            cancel.recycle();
                        }
                        gesture.intercepted = true;
                        gesture.eligible = false;
                        dt.validTapCandidate = false;
                        dt.lastUpTime = 0L;
                        try {
                            context.sendBroadcast(new Intent(ACTION)
                                    .setPackage(CoverGeometry.SYSTEMUI)
                                    .putExtra("control", gesture.control));
                        } catch (RuntimeException e) {
                            Log.w(TAG, "panel request not sent", e);
                        }
                        ModLog.i(TAG, gesture.control ? "widget pull routed to control center"
                                : "widget pull routed to notifications");
                        return true;
                    }
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    gesture.eligible = false;
                }
                return chain.proceed();
            });
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "watch overlay group touch hook skipped", e);
        }
        Class<?> launcher = loader.loadClass("com.miui.fliphome.FlipLauncher");
        module.hook(launcher.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class)).intercept(chain -> {
            Activity activity = (Activity) chain.getThisObject();
            MotionEvent event = (MotionEvent) chain.getArg(0);
            Gesture gesture = gestures.computeIfAbsent(activity, a -> new Gesture());
            DoubleTapTracker dt = doubleTaps.computeIfAbsent(activity, a -> new DoubleTapTracker());
            boolean outerNow = outer(activity);
            if (outerNow && handleDoubleTapTracking(activity, activity, null, dt, event, false, gesture.intercepted)) {
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                try {
                    chain.proceed(new Object[]{cancel});
                } finally {
                    cancel.recycle();
                }
                return true;
            }
            boolean enabled;
            try {
                enabled = ModuleConfig.frameworkFullscreen() && outerNow;
            } catch (RuntimeException e) {
                enabled = false;
            }
            if (!enabled) {
                gesture.eligible = gesture.intercepted = false;
                return chain.proceed();
            }
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                int rotation = rotationOf(activity);
                int width = logicalDisplayWidth(rotation);
                int height = logicalDisplayHeight(rotation);
                gesture.x = eventRawX(event);
                gesture.y = eventRawY(event);
                gesture.downTime = event.getDownTime();
                gesture.intercepted = false;
                float density = activity.getResources().getDisplayMetrics().density;
                // Upright: left 1/3 notifications, right 2/3 Control Center; inverted (ROTATION_180): middle 1/3 notifications, rightmost 1/3 Control Center.
                gesture.control = CoverDisplay.isControlCenterPull(gesture.x, width, rotation);
                boolean inCameraColumn = isInPortraitCameraColumn(gesture.x, rotation);
                float topEdge = inCameraColumn
                        ? CoverResourceOverlay.NATIVE_COVER_STATUS_BAR_PX
                        : (Float.isFinite(DesktopAlignment.firstRowTop)
                                ? Math.max(32 * density, DesktopAlignment.firstRowTop)
                                : 32 * density);
                boolean inTopZone = gesture.y >= 0 && gesture.y <= topEdge;
                boolean homePull = gesture.y >= 0 && gesture.y < height
                        && !isLauncherOverlayOpen(activity)
                        && (inCameraColumn || !DesktopAlignment.canScrollUp(activity));
                gesture.eligible = (inTopZone || homePull) && gesture.x >= 0 && gesture.x < width;
                return chain.proceed();
            }
            if (gesture.intercepted) {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) gesture.intercepted = false;
                return true;
            }
            if (action == MotionEvent.ACTION_POINTER_DOWN || event.getPointerCount() > 1) gesture.eligible = false;
            if (action == MotionEvent.ACTION_MOVE && gesture.eligible) {
                if (gesture.downTime > 0
                        && event.getEventTime() - gesture.downTime >= ViewConfiguration.getLongPressTimeout()) {
                    gesture.eligible = false;
                    return chain.proceed();
                }
                float dx = eventRawX(event) - gesture.x, dy = eventRawY(event) - gesture.y;
                float slop = ViewConfiguration.get(activity).getScaledTouchSlop();
                if (dy < -slop || (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy))) {
                    gesture.eligible = false;
                } else if (dy > slop && dy > Math.abs(dx) * 1.2f) {
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    try {
                        chain.proceed(new Object[]{cancel});
                    } finally {
                        cancel.recycle();
                    }
                    gesture.intercepted = true;
                    gesture.eligible = false;
                    dt.validTapCandidate = false;
                    dt.lastUpTime = 0L;
                    try {
                        activity.sendBroadcast(new Intent(ACTION)
                                .setPackage(CoverGeometry.SYSTEMUI)
                                .putExtra("control", gesture.control));
                    } catch (RuntimeException e) {
                        Log.w(TAG, "panel request not sent", e);
                    }
                    ModLog.i(TAG, gesture.control ? "top-edge pull routed to control center"
                            : "top-edge pull routed to notifications");
                    return true;
                }
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) gesture.eligible = false;
            return chain.proceed();
        });
    }

    /** Live (uncached) check: this runs once per user gesture, not per frame. */
    private static boolean isKeyguardLocked(Context context) {
        android.app.KeyguardManager guard = context.getSystemService(android.app.KeyguardManager.class);
        return guard != null && guard.isKeyguardLocked();
    }

    static void start(Context context) {
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context ignored, Intent intent) {
                if (intent != null && intent.getBooleanExtra("lock_cover", false)) {
                    try {
                        Boolean outer = CoverGeometry.defaultDisplayOuter();
                        if (!Boolean.FALSE.equals(outer)) {
                            android.os.PowerManager pm = context.getSystemService(android.os.PowerManager.class);
                            if (pm != null) {
                                PowerManagerInvoke.goToSleep(pm);
                                ModLog.i(TAG, "cover lock requested via SystemUI DEVICE_POWER");
                            }
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "cover lock via SystemUI failed", t);
                    }
                    return;
                }
                boolean force = intent != null && intent.getBooleanExtra("force", false);
                if ((!ModuleConfig.frameworkFullscreen() && !force) || isKeyguardLocked(context)) return;
                try {
                    boolean control = intent.getBooleanExtra("control", false);
                    Object controller = control ? PanelMotion.controlCenter : PanelMotion.notification;
                    if (controller != null) {
                        controller.getClass().getMethod(control ? "expand" : "show", boolean.class).invoke(controller, true);
                        PanelMotion.wake();
                        ModLog.i(TAG, control ? "native control center requested" : "native notification panel requested");
                    }
                } catch (ReflectiveOperationException | RuntimeException error) {
                    Log.w(TAG, "panel request unavailable", error);
                }
            }
        }, new IntentFilter(ACTION), CoverGeometry.STATUS_BAR_PERMISSION, null, Context.RECEIVER_EXPORTED);
    }

    private static final class PowerManagerInvoke {
        static void goToSleep(android.os.PowerManager pm) throws ReflectiveOperationException {
            java.lang.reflect.Method m = android.os.PowerManager.class.getMethod("goToSleep", long.class);
            m.invoke(pm, android.os.SystemClock.uptimeMillis());
        }
    }
}
