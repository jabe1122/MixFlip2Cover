package io.github.jabe1122.mixflip2cover;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.Surface;
import android.view.WindowManager;
import android.view.WindowMetrics;

/**
 * SystemUI-hosted cover status overlay anchored above the dual camera cutout.
 */
final class SystemUiOrb {
    private static final String TAG = "MF2C.Orb";
    private static final long CHECK_INTERVAL_MS = 1500;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final DisplayManager displays;
    private final PowerManager power;
    private boolean started;
    private boolean shadeStateKnown;
    private float panelFraction = 1f;
    private WindowManager windowManager;
    private StatusOrb orb;
    private int attachedDisplayId = Display.INVALID_DISPLAY;
    private int lastRotation = -1;
    private Rect lastBounds;
    private Rect lastCutout;
    private Rect lastLocation;
    private float desktopIconCenter = -1f;
    /** Window context for the cover display, created once per display id instead of on every poll. */
    private int windowContextDisplayId = Display.INVALID_DISPLAY;
    private Context windowContext;
    private WindowManager windowContextManager;
    private String lastLoggedLocation;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!started) return;
            refresh();
            Display outer = CoverGeometry.outerDisplay(context);
            if (outer != null && outer.getState() == Display.STATE_ON
                    && CoverGeometry.interactive(context)
                    && CoverGeometry.keyguardLocked(context)) {
                handler.postDelayed(this, CHECK_INTERVAL_MS);
            }
        }
    };

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) { scheduleRefresh(); }
        @Override public void onDisplayChanged(int id) { scheduleRefresh(); }
        @Override public void onDisplayRemoved(int id) { scheduleRefresh(); }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ignored, Intent intent) {
            CoverGeometry.invalidateDeviceState();
            scheduleRefresh();
        }
    };

    SystemUiOrb(Context systemUiContext) {
        Context app = systemUiContext.getApplicationContext();
        context = app != null ? app : systemUiContext;
        displays = context.getSystemService(DisplayManager.class);
        power = context.getSystemService(PowerManager.class);
    }

    void start() {
        handler.post(() -> {
            if (started || displays == null || power == null) return;
            try {
                IntentFilter filter = new IntentFilter();
                filter.addAction(Intent.ACTION_SCREEN_ON);
                filter.addAction(Intent.ACTION_SCREEN_OFF);
                filter.addAction(Intent.ACTION_USER_PRESENT);
                context.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
                displays.registerDisplayListener(displayListener, handler);
                DesktopAlignment.listen(context, center -> {
                    if (desktopIconCenter != center) {
                        desktopIconCenter = center;
                        lastRotation = -1;
                        scheduleRefresh();
                    }
                });
                started = true;
                scheduleRefresh();
            } catch (RuntimeException e) {
                Log.e(TAG, "Unable to start overlay observer", e);
                try { context.unregisterReceiver(screenReceiver); } catch (RuntimeException ignored) {}
                try { displays.unregisterDisplayListener(displayListener); } catch (RuntimeException ignored) {}
                removeOverlay();
            }
        });
    }

    void setSystemPanelExpanded(boolean expanded) {
        Runnable update = () -> {
            shadeStateKnown = true;
            if (expanded) removeOverlay();
            scheduleRefresh();
        };
        if (Looper.myLooper() == Looper.getMainLooper()) update.run();
        else handler.post(update);
    }

    void setPanelProgress(float value) {
        shadeStateKnown = true;
        panelFraction = value;
        if (orb != null) applyPresentation();
        else scheduleRefresh();
    }

    void applyPresentation() {
        if (orb == null) return;
        boolean unlock = UnlockMotion.active();
        orb.setAlpha((1f - panelFraction) * (unlock ? UnlockMotion.alpha : 1f));
        orb.setPivotX(orb.getWidth() / 2f);
        orb.setPivotY(orb.getHeight() * CoverGeometry.PANEL_DESIGN_ANCHOR_Y / CoverGeometry.PANEL_DESIGN_HEIGHT);
        orb.setScaleX(unlock ? UnlockMotion.scaleX : 1f);
        orb.setScaleY(unlock ? UnlockMotion.scaleY : 1f);
    }

    private void scheduleRefresh() {
        if (!started) return;
        handler.removeCallbacks(poll);
        handler.post(poll);
    }

    void requestRefresh() {
        scheduleRefresh();
    }

    private WindowManager windowManagerFor(Display outer) {
        int id = outer.getDisplayId();
        if (windowContextManager != null && windowContextDisplayId == id) return windowContextManager;
        Context displayContext = context.createDisplayContext(outer);
        Context next = displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        WindowManager manager = next.getSystemService(WindowManager.class);
        windowContext = next;
        windowContextManager = manager;
        windowContextDisplayId = manager != null ? id : Display.INVALID_DISPLAY;
        return manager;
    }

    @SuppressLint("WrongConstant")
    private void refresh() {
        try {
            if (!ModuleConfig.panelEnabled() || !shadeStateKnown
                    || !CoverGeometry.interactive(context) || CoverGeometry.keyguardLocked(context)) {
                removeOverlay();
                return;
            }
            Display outer = CoverGeometry.outerDisplay(context);
            if (outer == null || outer.getState() != Display.STATE_ON) {
                removeOverlay();
                return;
            }
            if (CoverAppPolicy.hidePanel(outer.getDisplayId())) {
                removeOverlay();
                return;
            }
            WindowManager candidateManager = windowManagerFor(outer);
            if (candidateManager == null) {
                removeOverlay();
                return;
            }
            WindowMetrics metrics = candidateManager.getMaximumWindowMetrics();
            Rect bounds = metrics.getBounds();
            DisplayCutout cutout = metrics.getWindowInsets().getDisplayCutout();
            if (cutout == null || bounds.isEmpty()) {
                removeOverlay();
                return;
            }
            Rect obstruction = new Rect();
            for (Rect rect : cutout.getBoundingRects()) obstruction.union(rect);
            if (obstruction.isEmpty()) {
                removeOverlay();
                return;
            }

            float density = windowContext.getResources().getDisplayMetrics().density;
            int size = Math.round(54 * density);
            int height = Math.round(81 * density);
            int margin = physicalPanelBounds(density, desktopIconCenter).top;
            int rotation = outer.getRotation();
            Rect location = findLocation(bounds, cutout, size, height, margin, rotation);
            if (location == null) {
                ModLog.i(TAG, "position unavailable: bounds=" + bounds + " cutout=" + cutout.getBoundingRects()
                        + " rotation=" + rotation);
                removeOverlay();
                return;
            }
            if (ImeAvoidance.overlaps(outer, location)) {
                removeOverlay();
                return;
            }
            if (orb != null && attachedDisplayId == outer.getDisplayId()
                    && lastRotation == rotation && bounds.equals(lastBounds)
                    && obstruction.equals(lastCutout) && location.equals(lastLocation)) {
                applyPresentation();
                return;
            }
            String logged = location.toShortString();
            if (!logged.equals(lastLoggedLocation)) {
                lastLoggedLocation = logged;
                ModLog.i(TAG, "position=" + location);
            }

            if (orb != null && attachedDisplayId == outer.getDisplayId()) {
                WindowManager.LayoutParams p = (WindowManager.LayoutParams) orb.getLayoutParams();
                p.width = size;
                p.height = height;
                p.x = location.left - bounds.left;
                p.y = location.top - bounds.top;
                windowManager.updateViewLayout(orb, p);
                lastRotation = rotation;
                lastBounds = new Rect(bounds);
                lastCutout = obstruction;
                lastLocation = new Rect(location);
                applyPresentation();
                orb.invalidate();
                return;
            }
            removeOverlay();

            StatusOrb next = new StatusOrb(windowContext);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    size,
                    height,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.x = location.left - bounds.left;
            params.y = location.top - bounds.top;
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            params.setFitInsetsTypes(0);
            windowManager = candidateManager;
            orb = next;
            attachedDisplayId = outer.getDisplayId();
            lastRotation = rotation;
            lastBounds = new Rect(bounds);
            lastCutout = obstruction;
            lastLocation = new Rect(location);
            candidateManager.addView(next, params);
            applyPresentation();
            next.startUpdates();
        } catch (RuntimeException e) {
            Log.w(TAG, "Overlay unavailable; preserving the original UI", e);
            removeOverlay();
        }
    }

    /** Physical bounds of the status panel in portrait cover coordinates. */
    static Rect physicalPanelBounds(float density, float iconCenter) {
        int size = Math.round(54 * density), height = Math.round(81 * density), margin = Math.round(8 * density);
        if (iconCenter >= 0f) {
            float scale = Math.min(size / CoverGeometry.PANEL_DESIGN_WIDTH, height / CoverGeometry.PANEL_DESIGN_HEIGHT);
            float center = (height - CoverGeometry.PANEL_DESIGN_HEIGHT * scale) / 2f
                    + CoverGeometry.PANEL_DESIGN_ANCHOR_Y * scale;
            margin = Math.max(1, Math.round(iconCenter - center));
        }
        int left = CoverGeometry.PANEL_CENTER_X - size / 2;
        return new Rect(left, margin, left + size, margin + height);
    }

    private static Rect findLocation(Rect bounds, DisplayCutout cutout, int size, int height, int margin, int rotation) {
        if (bounds.left != 0 || bounds.top != 0 || size <= 0 || height <= 0 || margin <= 0) return null;
        if (!CoverDisplay.isOuterDimensions(bounds.width(), bounds.height())) return null;
        float scale = Math.min(size / CoverGeometry.PANEL_DESIGN_WIDTH, height / CoverGeometry.PANEL_DESIGN_HEIGHT);
        int centerX = CoverGeometry.PANEL_CENTER_X;
        if (rotation == Surface.ROTATION_0) {
            return new Rect(centerX - size / 2, margin, centerX - size / 2 + size, margin + height);
        }
        if (rotation == Surface.ROTATION_180) {
            int left = CoverDisplay.PHYSICAL_WIDTH - (centerX - size / 2 + size);
            int top = CoverDisplay.PHYSICAL_HEIGHT - margin - height;
            return new Rect(left, top, left + size, top + height);
        }
        float anchor = CoverGeometry.PANEL_DESIGN_ANCHOR_Y * scale;
        float cx = centerX, cy = margin + anchor;
        if (rotation == Surface.ROTATION_90) {
            float old = cx;
            cx = cy;
            cy = CoverDisplay.PHYSICAL_WIDTH - old;
        } else if (rotation == Surface.ROTATION_270) {
            float old = cx;
            cx = CoverDisplay.PHYSICAL_HEIGHT - cy;
            cy = old;
        }
        int anchorLeft = Math.round(cx - size / 2f), anchorTop = Math.round(cy - anchor);
        Rect anchored = new Rect(anchorLeft, anchorTop, anchorLeft + size, anchorTop + height);
        for (Rect hole : cutout.getBoundingRects()) {
            if (Rect.intersects(anchored, hole)) return null;
        }
        return bounds.contains(anchored) ? anchored : null;
    }

    private void removeOverlay() {
        StatusOrb old = orb;
        WindowManager oldManager = windowManager;
        orb = null;
        windowManager = null;
        attachedDisplayId = Display.INVALID_DISPLAY;
        lastBounds = null;
        lastCutout = null;
        lastLocation = null;
        if (old != null) old.stopUpdates();
        if (old != null && oldManager != null) {
            try { oldManager.removeViewImmediate(old); } catch (RuntimeException ignored) {}
        }
    }
}
