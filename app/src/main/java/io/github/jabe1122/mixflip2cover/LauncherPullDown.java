package io.github.jabe1122.mixflip2cover;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewConfiguration;
import java.util.WeakHashMap;
import io.github.libxposed.api.XposedModule;

/** Give a downward drag starting at the outer launcher's top edge or home surface to the system shade. */
final class LauncherPullDown {
    private static final String TAG = "MF2C.Gesture";
    static final String ACTION = "io.github.jabe1122.mixflip2cover.OPEN_PANEL";
    static final WeakHashMap<Object, Gesture> gestures = new WeakHashMap<>();

    static final class Gesture {
        float x, y;
        long downTime;
        boolean eligible, intercepted, control;
    }

    private LauncherPullDown() {}

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
                boolean enabled;
                try {
                    enabled = ModuleConfig.frameworkFullscreen() && outerView(view);
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
                    gesture.control = CoverDisplay.isControlCenterPull(gesture.x, width);
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
            boolean enabled;
            try {
                enabled = ModuleConfig.frameworkFullscreen() && outer(activity);
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
                // Left third of the panel opens the notification shade, the right two-thirds open the control center.
                gesture.control = CoverDisplay.isControlCenterPull(gesture.x, width);
                boolean inCameraColumn = isInPortraitCameraColumn(gesture.x, rotation);
                float topEdge = inCameraColumn
                        ? CoverResourceOverlay.NATIVE_COVER_STATUS_BAR_PX
                        : (Float.isFinite(DesktopAlignment.firstRowTop)
                                ? Math.max(32 * density, DesktopAlignment.firstRowTop)
                                : 32 * density);
                boolean inTopZone = gesture.y >= 0 && gesture.y <= topEdge;
                boolean homePull = gesture.y >= 0 && gesture.y < height
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
                if (!ModuleConfig.frameworkFullscreen() || isKeyguardLocked(context)) return;
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
}
