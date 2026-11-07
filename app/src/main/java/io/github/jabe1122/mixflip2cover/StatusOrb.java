package io.github.jabe1122.mixflip2cover;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.View;
import android.view.animation.PathInterpolator;
import java.util.ArrayList;
import java.util.Date;

/** Transparent Duo-style status cluster with optional battery numbers and physical SIM rows. */
final class StatusOrb extends View {
    private static final String TAG = "MF2C.Orb";
    private static final Typeface LABEL_FONT = Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), 700, false);
    private static final float BATTERY_STROKE = 7.0f;
    private static final float NUMBER_SIZE = 23f;
    private static final float NUMBER_BASELINE = 60.5f;
    private static final float CHARGE_SYMBOL_CENTER_Y = 51.5f;
    private static final float CLOCK_SIZE = 25f;
    /** Static glyph geometry in the 100x150 design canvas; built once instead of per frame. */
    private static final Path BOLT = buildBolt();
    private static final Path AIRPLANE = buildAirplane();
    private static final Path HOTSPOT_LEFT = new Path();
    private static final Path HOTSPOT_RIGHT = new Path();
    private static final float[] SIGNAL_XS = {30, 43.054f, 56.946f, 70};
    private static final float[] SIGNAL_YS = {122.641f, 127.392f, 127.392f, 122.641f};

    static {
        buildHotspot(HOTSPOT_LEFT, HOTSPOT_RIGHT);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint numberPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ring = new RectF(10, 48, 90, 128);
    private final RectF arc = new RectF();
    private Typeface numberFaceSource, numberFace;
    private final ArrayList<Layer> layers = new ArrayList<>();
    private ValueAnimator stateAnimator;
    private VisualState targetState;
    private final NetworkStatus network;
    private final Runnable configChanged = () -> {
        readStatus();
        postInvalidateOnAnimation();
    };
    private int battery = -1, wifi = -1;
    private int batteryColor = Color.WHITE;
    private boolean charging, full, powerSave, receiverRegistered, powerObserverRegistered;
    private final ContentObserver powerObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange) {
            if (running) {
                readStatus();
                postInvalidateOnAnimation();
            }
        }
    };
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String action = i.getAction();
            if (Intent.ACTION_TIME_TICK.equals(action)
                    || Intent.ACTION_TIME_CHANGED.equals(action)
                    || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
                String next = DateFormat.getTimeFormat(getContext()).format(new Date());
                if (!next.equals(time)) {
                    time = next;
                    updateDescription();
                    invalidate();
                }
                scheduleMinuteRefresh();
                return;
            }
            if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                // Sticky battery ticks are frequent: update only battery-derived state, not the radios.
                int prevBattery = battery;
                boolean prevCharging = charging;
                boolean prevFull = full;
                readBattery(i);
                batteryColor = NativeStatusStyle.batteryColor(charging, powerSave, battery >= 0 && battery < 20);
                updateVisual();
                updateDescription();
                if (battery != prevBattery || charging != prevCharging || full != prevFull) {
                    invalidate();
                }
            } else {
                readStatus();
                lastStatusRead = SystemClock.elapsedRealtime();
                invalidate();
            }
        }
    };
    private boolean wifiConnected, wifiKnown, running;
    private String time = "";
    private String lastStatusSummary = "";
    private long lastStatusRead;
    private ValueAnimator homeAnimator;
    private boolean homeOverride;
    private int homeTarget, animatedInk, lastDrawnInk = Color.WHITE;
    private long homeDeadline;

    void animateHomeInk(int target) {
        if (homeAnimator != null) homeAnimator.cancel();
        homeTarget = target;
        animatedInk = lastDrawnInk;
        homeOverride = true;
        homeDeadline = SystemClock.elapsedRealtime() + 1200;
        homeAnimator = ValueAnimator.ofArgb(lastDrawnInk, target);
        homeAnimator.setDuration(120);
        homeAnimator.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        homeAnimator.addUpdateListener(v -> {
            animatedInk = (Integer) v.getAnimatedValue();
            postInvalidateOnAnimation();
        });
        homeAnimator.start();
        ModLog.i(TAG, "panel animation started target=" + Integer.toHexString(target));
    }

    void finishHomeTransition() {
        if (homeAnimator != null) homeAnimator.cancel();
        homeOverride = false;
        postInvalidateOnAnimation();
    }

    private int displayInk(int nativeInk) {
        if (homeOverride) {
            boolean animating = homeAnimator != null && homeAnimator.isRunning();
            int delta = Math.abs(Color.red(nativeInk) - Color.red(homeTarget))
                    + Math.abs(Color.green(nativeInk) - Color.green(homeTarget))
                    + Math.abs(Color.blue(nativeInk) - Color.blue(homeTarget));
            if (!animating && (delta < 12 || SystemClock.elapsedRealtime() > homeDeadline)) homeOverride = false;
        }
        return lastDrawnInk = homeOverride ? animatedInk : nativeInk;
    }

    private void scheduleMinuteRefresh() {
        if (!running) return;
        removeCallbacks(refresh);
        long delay = 60_000L - (System.currentTimeMillis() % 60_000L) + 50L;
        postDelayed(refresh, Math.max(1000L, delay));
    }

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            long now = SystemClock.elapsedRealtime();
            if (lastStatusRead == 0 || now - lastStatusRead >= 60_000L) {
                readStatus();
                lastStatusRead = now;
                invalidate();
            } else {
                String next = DateFormat.getTimeFormat(getContext()).format(new Date());
                if (!next.equals(time)) {
                    time = next;
                    updateDescription();
                    invalidate();
                }
            }
            scheduleMinuteRefresh();
        }
    };

    StatusOrb(Context context) {
        super(context);
        network = new NetworkStatus(context, () -> post(configChanged));
        NativeStatusStyle.observeTint(this);
        if (SystemClock.uptimeMillis() < NativeStatusStyle.homeInkUntil) {
            homeTarget = animatedInk = lastDrawnInk = NativeStatusStyle.homeInk;
            homeOverride = true;
            homeDeadline = SystemClock.elapsedRealtime() + 1200;
        }
        setClickable(false);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    void startUpdates() {
        if (running) return;
        running = true;
        network.start();
        ModuleConfig.addListener(configChanged);
        if (!powerObserverRegistered) {
            try {
                getContext().getContentResolver().registerContentObserver(
                        Settings.System.getUriFor(NativeStatusStyle.MIUI_POWER_SAVE_KEY), false, powerObserver);
                powerObserverRegistered = true;
            } catch (RuntimeException e) {
                Log.w(TAG, "MIUI power-save observer unavailable; polling remains active", e);
            }
        }
        if (!receiverRegistered) {
            IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            f.addAction(Intent.ACTION_TIME_TICK);
            f.addAction(Intent.ACTION_TIME_CHANGED);
            f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            f.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
            f.addAction(Intent.ACTION_POWER_CONNECTED);
            f.addAction(Intent.ACTION_POWER_DISCONNECTED);
            f.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
            f.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
            f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
            f.addAction(WifiManager.RSSI_CHANGED_ACTION);
            f.addAction("android.net.wifi.WIFI_AP_STATE_CHANGED");
            getContext().registerReceiver(batteryReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
        }
        lastStatusRead = 0;
        post(refresh);
    }

    void stopUpdates() {
        running = false;
        if (stateAnimator != null) {
            ValueAnimator old = stateAnimator;
            stateAnimator = null;
            old.cancel();
        }
        network.stop();
        ModuleConfig.removeListener(configChanged);
        removeCallbacks(configChanged);
        removeCallbacks(refresh);
        if (receiverRegistered) {
            try {
                getContext().unregisterReceiver(batteryReceiver);
            } catch (RuntimeException ignored) {
            }
            receiverRegistered = false;
        }
        if (powerObserverRegistered) {
            try {
                getContext().getContentResolver().unregisterContentObserver(powerObserver);
            } catch (RuntimeException ignored) {
            }
            powerObserverRegistered = false;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopUpdates();
        if (homeAnimator != null) homeAnimator.cancel();
        NativeStatusStyle.removeTintObserver(this);
        super.onDetachedFromWindow();
    }

    private void readBattery(Intent b) {
        battery = -1;
        charging = full = false;
        if (b == null) return;
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level >= 0 && scale > 0) battery = Math.min(100, level * 100 / scale);
        int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        charging = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                && (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL);
        full = charging && (status == BatteryManager.BATTERY_STATUS_FULL || battery == 100);
    }

    @SuppressWarnings("deprecation")
    private void readStatus() {
        wifi = -1;
        wifiKnown = wifiConnected = false;
        time = DateFormat.getTimeFormat(getContext()).format(new Date());
        powerSave = NativeStatusStyle.powerSaveEnabled(getContext());
        try {
            readBattery(getContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)));
        } catch (RuntimeException ignored) {
            readBattery(null);
        }
        try {
            WifiManager wm = getContext().getSystemService(WifiManager.class);
            WifiInfo info = wm == null ? null : wm.getConnectionInfo();
            wifiKnown = wm != null && (!wm.isWifiEnabled() || info != null);
            wifiConnected = info != null && info.getSupplicantState() == SupplicantState.COMPLETED;
            if (wifiConnected && info.getRssi() > -127) {
                wifi = Math.min(3, (int) Math.ceil(wm.calculateSignalLevel(info.getRssi()) * 3f
                        / Math.max(1, wm.getMaxSignalLevel())));
            }
        } catch (RuntimeException ignored) {
        }
        try {
            network.read();
        } catch (RuntimeException e) {
            Log.w(TAG, "network status unavailable", e);
        }
        batteryColor = NativeStatusStyle.batteryColor(charging, powerSave, battery >= 0 && battery < 20);
        updateVisual();
        updateDescription();
    }

    /** Accessibility text only: time, battery, charging state and connectivity. */
    private void updateDescription() {
        StringBuilder s = new StringBuilder(96);
        s.append(time);
        s.append("，电量 ").append(battery < 0 ? "未知" : battery + "%");
        s.append(full ? "，已充满" : charging ? "，充电中" : "");
        if (powerSave) s.append("，省电模式");
        if (network.airplane) s.append("，飞行模式");
        if (wifiKnown) {
            s.append("，Wi-Fi ").append(!wifiConnected ? "未连接" : wifi < 0 ? "已连接" : "信号 " + wifi + " 格");
        }
        s.append("，SIM1 ").append(signalDescription(network.signals[0]));
        if (ModuleConfig.dualSim()) s.append("，SIM2 ").append(signalDescription(network.signals[1]));
        if (network.hotspot) s.append("，个人热点已开启");
        if (!network.technology.isEmpty()) s.append("，").append(network.technology);
        String summary = s.toString();
        if (!summary.equals(lastStatusSummary)) {
            lastStatusSummary = summary;
            setContentDescription(summary);
        }
    }

    private static String signalDescription(int level) {
        return level < 0 ? "无信号" : "信号 " + level + " 格";
    }

    private final class VisualState {
        final boolean plugged = charging;
        final boolean charged = full;
        final boolean connected = wifiConnected;
        final boolean airplane = network.airplane;
        final boolean hotspot = network.hotspot;
        final boolean dual = ModuleConfig.dualSim() && network.dualSimActive();
        final boolean number = ModuleConfig.batteryNumber() && battery >= 0 && !full;
        final int level = battery;
        final int color = batteryColor;
        final int signal1 = dual ? network.signals[0] : network.primarySignal();
        final int signal2 = network.signals[1];
        final int wifiLevel = wifi;
        final String technology = network.technology;

        boolean same(VisualState other) {
            return other != null && level == other.level && color == other.color && badgeSame(other) && networkSame(other);
        }

        boolean badgeSame(VisualState other) {
            return plugged == other.plugged && charged == other.charged && number == other.number
                    && (!number || level == other.level);
        }

        boolean networkSame(VisualState other) {
            return signal1 == other.signal1 && signal2 == other.signal2 && wifiLevel == other.wifiLevel
                    && connected == other.connected && airplane == other.airplane && hotspot == other.hotspot
                    && dual == other.dual && technology.equals(other.technology);
        }
    }

    private final class Layer {
        final VisualState state;
        float alpha, start;

        Layer(VisualState state, float alpha) {
            this.state = state;
            this.alpha = alpha;
        }
    }

    private void updateVisual() {
        VisualState next = new VisualState();
        if (next.same(targetState)) return;
        targetState = next;
        if (stateAnimator != null) {
            ValueAnimator previous = stateAnimator;
            stateAnimator = null;
            previous.cancel();
        }
        layers.removeIf(layer -> layer.alpha < .001f);
        if (layers.isEmpty() || !running || !isAttachedToWindow()) {
            layers.clear();
            layers.add(new Layer(next, 1));
            return;
        }
        Layer target = null;
        for (Layer layer : layers) {
            layer.start = layer.alpha;
            if (layer.state.same(next)) target = layer;
        }
        if (target == null) {
            target = new Layer(next, 0);
            layers.add(target);
        }
        final Layer incoming = target;
        ValueAnimator animator = ValueAnimator.ofFloat(0, 1);
        stateAnimator = animator;
        animator.setDuration(260);
        animator.setInterpolator(new PathInterpolator(.2f, 0f, 0f, 1f));
        animator.addUpdateListener(value -> {
            float progress = (Float) value.getAnimatedValue();
            for (Layer layer : layers) layer.alpha = layer.start + ((layer == incoming ? 1 : 0) - layer.start) * progress;
            postInvalidateOnAnimation();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (stateAnimator != animation) return;
                layers.removeIf(layer -> layer != incoming);
                incoming.alpha = 1;
                stateAnimator = null;
                postInvalidateOnAnimation();
            }
        });
        animator.start();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        int ink = displayInk(NativeStatusStyle.inkColor());
        int muted = (ink & 0x00ffffff) | 0x60000000;
        int saved = c.save();
        float scale = Math.min(getWidth() / CoverGeometry.PANEL_DESIGN_WIDTH, getHeight() / CoverGeometry.PANEL_DESIGN_HEIGHT);
        c.translate((getWidth() - scale * CoverGeometry.PANEL_DESIGN_WIDTH) / 2,
                (getHeight() - scale * CoverGeometry.PANEL_DESIGN_HEIGHT) / 2);
        c.scale(scale, scale);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ink);
        NativeStatusStyle.applyClock(paint);
        paint.setColor(ink);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(23);
        Display display = getDisplay();
        boolean reversed = display != null && display.getRotation() == Surface.ROTATION_180;
        Paint.FontMetrics oldFont = paint.getFontMetrics();
        float textCenter = 24 + (oldFont.ascent + oldFont.descent) / 2f;
        paint.setTextSize(CLOCK_SIZE);
        Paint.FontMetrics font = paint.getFontMetrics();
        float timeBaseline = (reversed ? 150 - textCenter : textCenter) - (font.ascent + font.descent) / 2f;
        c.drawText(time, 50, timeBaseline, paint);
        if (reversed) c.translate(0, 150 - 2 * CoverGeometry.PANEL_DESIGN_ANCHOR_Y);
        paint.reset();
        paint.setAntiAlias(true);
        drawBattery(c, ink);
        for (int index = 0; index < layers.size(); index++) {
            Layer layer = layers.get(index);
            boolean repeated = false;
            for (int j = 0; j < index; j++) {
                if (layer.state.networkSame(layers.get(j).state)) {
                    repeated = true;
                    break;
                }
            }
            if (repeated) continue;
            float weight = 0;
            for (Layer other : layers) {
                if (layer.state.networkSame(other.state)) weight += other.alpha;
            }
            if (weight < .001f) continue;
            int networkSave = c.saveLayerAlpha(null, Math.round(255 * Math.min(1, weight)));
            c.translate(0, 4 * (1 - weight));
            c.scale(.9f + .1f * weight, .9f + .1f * weight, 50, 88);
            drawNetwork(c, layer.state, ink, muted);
            c.restoreToCount(networkSave);
        }
        c.restoreToCount(saved);
    }

    private void drawNetwork(Canvas c, VisualState state, int ink, int muted) {
        boolean dual = state.dual;
        if (state.airplane) {
            if (state.connected) drawWifi(c, state, ink, muted, 94);
            drawAirplane(c, ink);
        } else if (state.hotspot) {
            drawHotspot(c, ink);
            drawSignals(c, state, ink, muted);
        } else if (state.connected) {
            drawWifi(c, state, ink, muted, dual ? 94 : 96.5f);
            drawSignals(c, state, ink, muted);
        } else if (!state.technology.isEmpty()) {
            if (dual) {
                drawDots(c, state.signal1, 82, ink, muted);
                drawDots(c, state.signal2, 94, ink, muted);
            } else {
                drawBars(c, state.signal1, ink, muted);
            }
            text(c, state.technology, 50, 135, 25, ink);
        } else {
            // Missing service, disabled data, and unknown state never pretend to be connected Wi-Fi.
            drawSignals(c, state, ink, muted);
        }
    }

    private void prepareNumberPaint(int ink) {
        numberPaint.reset();
        NativeStatusStyle.applyClock(numberPaint);
        Typeface clockFace = numberPaint.getTypeface();
        if (clockFace != null) {
            if (clockFace != numberFaceSource || numberFace == null) {
                numberFaceSource = clockFace;
                numberFace = Typeface.create(clockFace, Math.max(800, clockFace.getWeight()), clockFace.isItalic());
            }
            numberPaint.setTypeface(numberFace);
        }
        numberPaint.setStyle(Paint.Style.FILL);
        numberPaint.setColor(ink);
        numberPaint.setTextAlign(Paint.Align.CENTER);
        numberPaint.setTextSize(NUMBER_SIZE);
        numberPaint.setTextScaleX(1);
    }

    private final class Badge {
        final String label;
        final float labelWidth, symbolWidth, contentWidth, gap;

        Badge(VisualState state) {
            label = state.number ? Integer.toString(state.level) : "";
            labelWidth = numberPaint.measureText(label);
            symbolWidth = state.plugged ? 16 : 0;
            contentWidth = labelWidth + (state.number && state.plugged ? 1.5f : 0) + symbolWidth;
            gap = contentWidth == 0 ? 0 : (float) Math.toDegrees(2 * Math.asin(Math.min(.86f, (contentWidth + 8) / 80f)));
        }
    }

    private void drawBattery(Canvas c, int ink) {
        prepareNumberPaint(ink);
        float gap = 0, level = 0, red = 0, green = 0, blue = 0, alpha = 0;
        for (Layer layer : layers) {
            VisualState state = layer.state;
            gap += new Badge(state).gap * layer.alpha;
            level += Math.max(0, state.level) * layer.alpha;
            int color = state.color;
            int range = Math.max(Color.red(color), Math.max(Color.green(color), Color.blue(color)))
                    - Math.min(Color.red(color), Math.min(Color.green(color), Color.blue(color)));
            if (range < 45) color = ink;
            red += Color.red(color) * layer.alpha;
            green += Color.green(color) * layer.alpha;
            blue += Color.blue(color) * layer.alpha;
            alpha += Color.alpha(color) * layer.alpha;
        }
        int arcColor = Color.argb(Math.round(alpha), Math.round(red), Math.round(green), Math.round(blue));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(BATTERY_STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        float side = 120 - gap / 2;
        float amount = 2 * side * level / 100f;
        drawBatterySegment(c, ring, 150, side, Math.min(side, amount), arcColor);
        drawBatterySegment(c, ring, 270 + gap / 2, side, Math.max(0, amount - side), arcColor);
        for (int index = 0; index < layers.size(); index++) {
            Layer layer = layers.get(index);
            boolean repeated = false;
            for (int j = 0; j < index; j++) {
                if (layer.state.badgeSame(layers.get(j).state)) {
                    repeated = true;
                    break;
                }
            }
            if (repeated) continue;
            float weight = 0;
            for (Layer other : layers) {
                if (layer.state.badgeSame(other.state)) weight += other.alpha;
            }
            if (weight < .001f) continue;
            int badgeSave = c.saveLayerAlpha(null, Math.round(255 * Math.min(1, weight)));
            c.translate(0, 2.5f * (1 - weight));
            c.scale(.9f + .1f * weight, .9f + .1f * weight, 50, 53);
            VisualState state = layer.state;
            Badge badge = new Badge(state);
            float start = 50 - badge.contentWidth / 2;
            if (state.number) c.drawText(badge.label, start + badge.labelWidth / 2, NUMBER_BASELINE, numberPaint);
            if (state.plugged) {
                float cx = start + badge.contentWidth - badge.symbolWidth / 2;
                if (state.charged) drawPlug(c, cx, CHARGE_SYMBOL_CENTER_Y, ink);
                else drawBolt(c, cx, CHARGE_SYMBOL_CENTER_Y, ink);
            }
            c.restoreToCount(badgeSave);
        }
    }

    private void drawBatterySegment(Canvas c, RectF ring, float start, float sweep, float filled, int color) {
        paint.setColor((color & 0x00ffffff) | 0x41000000);
        c.drawArc(ring, start, sweep, false, paint);
        if (filled > 0) {
            paint.setColor(color);
            c.drawArc(ring, start, filled, false, paint);
        }
    }

    private void drawWifi(Canvas c, VisualState state, int ink, int muted, float base) {
        // Three tiers, matching the native Wi-Fi glyph: two arcs and a dot.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(5.8f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 2; i++) {
            paint.setColor(state.connected && state.wifiLevel >= i + 2 ? ink : muted);
            float radius = 11.5f + 9 * i;
            arc.set(50 - radius, base - radius, 50 + radius, base + radius);
            c.drawArc(arc, 225, 90, false, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(state.connected && state.wifiLevel >= 0 ? ink : muted);
        c.drawCircle(50, base, 4.0f, paint);
    }

    private void drawSignals(Canvas c, VisualState state, int ink, int muted) {
        if (state.dual) {
            drawDots(c, state.signal1, 117, ink, muted);
            drawDots(c, state.signal2, 129, ink, muted);
            return;
        }
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 4; i++) {
            paint.setColor(state.signal1 > i ? ink : muted);
            c.drawCircle(SIGNAL_XS[i], SIGNAL_YS[i], 4.1f, paint);
        }
    }

    private void drawDots(Canvas c, int level, float y, int ink, int muted) {
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 4; i++) {
            paint.setColor(level > i ? ink : muted);
            c.drawCircle(32 + i * 12, y, 3.5f, paint);
        }
    }

    private void drawBars(Canvas c, int level, int ink, int muted) {
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 4; i++) {
            paint.setColor(level > i ? ink : muted);
            float x = 32 + i * 12, height = 12 + i * 7;
            c.drawRoundRect(x - 3.6f, 102 - height, x + 3.6f, 102, 3.6f, 3.6f, paint);
        }
    }

    private void text(Canvas c, String value, float x, float baseline, float size, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(LABEL_FONT);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(size);
        paint.setColor(color);
        c.drawText(value, x, baseline, paint);
    }

    /** Lightning bolt centred on (0, 0); translated to the badge position when drawn. */
    private static Path buildBolt() {
        Path path = new Path();
        path.moveTo(3, -13);
        path.lineTo(-8, 1);
        path.lineTo(-1, 1);
        path.lineTo(-4, 13);
        path.lineTo(8, -2);
        path.lineTo(1, -2);
        path.close();
        return path;
    }

    private void drawBolt(Canvas c, float x, float y, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        int save = c.save();
        c.translate(x, y);
        c.drawPath(BOLT, paint);
        c.restoreToCount(save);
    }

    private void drawPlug(Canvas c, float x, float y, int color) {
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3.0f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(x - 3, y - 12, x - 3, y - 6, paint);
        c.drawLine(x + 3, y - 12, x + 3, y - 6, paint);
        c.drawLine(x, y + 5, x, y + 12, paint);
        paint.setStyle(Paint.Style.FILL);
        c.drawRoundRect(x - 6.5f, y - 6, x + 6.5f, y + 6, 3.5f, 3.5f, paint);
    }

    private static Path buildAirplane() {
        Path p = new Path();
        p.moveTo(66, 123);
        p.cubicTo(66, 121, 62, 120, 60, 120);
        p.lineTo(52, 120);
        p.lineTo(44, 108);
        p.lineTo(39, 108);
        p.lineTo(43, 120);
        p.lineTo(36, 120);
        p.lineTo(32, 115);
        p.lineTo(29, 115);
        p.lineTo(31, 123);
        p.lineTo(29, 131);
        p.lineTo(32, 131);
        p.lineTo(36, 126);
        p.lineTo(43, 126);
        p.lineTo(39, 138);
        p.lineTo(44, 138);
        p.lineTo(52, 126);
        p.lineTo(60, 126);
        p.cubicTo(62, 126, 66, 125, 66, 123);
        p.close();
        return p;
    }

    private void drawAirplane(Canvas c, int ink) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ink);
        c.save();
        c.scale(.85f, .85f, 50, 123);
        c.drawPath(AIRPLANE, paint);
        c.restore();
    }

    /**
     * Hotspot glyph: two rounded horizontal links offset diagonally, each with its own break where they
     * cross. Open paths keep the gaps transparent on every background.
     */
    private static void buildHotspot(Path left, Path right) {
        left.moveTo(48, 62.4f);
        left.cubicTo(45.3f, 60.9f, 44.3f, 58.5f, 44.3f, 55.7f);
        left.cubicTo(44.3f, 51.9f, 47.1f, 49.4f, 51, 49.4f);
        left.lineTo(59.5f, 49.4f);
        left.cubicTo(63.7f, 49.4f, 66.4f, 52, 66.4f, 55.9f);
        left.cubicTo(66.4f, 60.3f, 63.7f, 63, 59.4f, 63);
        left.lineTo(58.5f, 63);
        right.moveTo(61, 56.1f);
        right.lineTo(60, 56.1f);
        right.cubicTo(56.1f, 56.1f, 53.8f, 58.9f, 53.8f, 62.8f);
        right.cubicTo(53.8f, 67.2f, 56.2f, 69.4f, 60.2f, 69.4f);
        right.lineTo(69.4f, 69.4f);
        right.cubicTo(73.6f, 69.4f, 76.2f, 66.9f, 76.2f, 62.9f);
        right.cubicTo(76.2f, 60, 74.6f, 58, 72.2f, 56.8f);
    }

    private void drawHotspot(Canvas c, int ink) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.8f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(ink);
        c.save();
        c.translate(-10, 28.5f);
        c.drawPath(HOTSPOT_LEFT, paint);
        c.drawPath(HOTSPOT_RIGHT, paint);
        c.restore();
    }
}
