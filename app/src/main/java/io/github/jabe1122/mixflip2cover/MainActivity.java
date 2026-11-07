package io.github.jabe1122.mixflip2cover;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.BroadcastOptions;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.ResultReceiver;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * Material 3 Expressive (Android 16/17) settings interface with Monet dynamic color tokens,
 * edge-to-edge insets, segmented list cards, animated M3 switches, live cover-screen preview,
 * and a searchable app-scope manager sheet.
 */
public final class MainActivity extends Activity {
    private static final String TAG = "MF2C.Settings";
    private static final PathInterpolator M3_STANDARD = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final Typeface FONT_REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    private static final Typeface FONT_MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private static final Typeface FONT_BOLD = Typeface.create("sans-serif", Typeface.BOLD);
    private static final Typeface FONT_MONO = Typeface.MONOSPACE;

    private static final String GITHUB_URL = "https://github.com/jabe1122/MixFlip2Cover";
    private static final String WETYPE_PKG = "com.tencent.wetype";
    private static final String VOICEASSIST_PKG = AppCompatibility.VOICEASSIST_PKG;
    private static final List<String> CORE_SCOPE = List.of("system", "com.android.systemui", "com.miui.fliphome");
    private static final int FILTER_THIRD_PARTY = 0;
    private static final int FILTER_DEEP_COMPAT = 1;
    private static final int FILTER_ALL_APPS = 2;
    private static final int FILTER_IN_SCOPE = 3;
    private static final int FILTER_SYSTEM_APPS = 4;
    /**
     * Extended apps with dedicated package-specific cover adaptation in {@link AppCompatibility},
     * {@link AppLayoutAdapters}, {@link WeChatInsets}, or {@link WeChatTabLayout} (excluding preset
     * core scopes {@code com.miui.fliphome}/{@code com.miui.voiceassist} and the dedicated IME switch
     * package {@code com.tencent.wetype}).
     */
    private static final Map<String, Integer> DEEP_COMPAT_SPECS = buildDeepCompatSpecs();
    private static final List<String> DEEP_COMPAT_ORDER = List.copyOf(DEEP_COMPAT_SPECS.keySet());

    private static Map<String, Integer> buildDeepCompatSpecs() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("com.tencent.mm", R.string.deep_desc_wechat);
        m.put("com.tencent.mobileqq", R.string.deep_desc_qq);
        m.put("com.sankuai.meituan", R.string.deep_desc_meituan);
        m.put("com.netease.cloudmusic", R.string.deep_desc_cloudmusic);
        m.put("com.android.calendar", R.string.deep_desc_calendar);
        m.put("com.xiaomi.shop", R.string.deep_desc_xiaomishop);
        m.put("com.mi.health", R.string.deep_desc_mihealth);
        m.put("com.android.settings", R.string.deep_desc_settings);
        return Collections.unmodifiableMap(m);
    }

    private static boolean isDeepCompatApp(String pkg) {
        return pkg != null && DEEP_COMPAT_SPECS.containsKey(pkg);
    }

    private static int deepCompatOrder(String pkg) {
        int idx = pkg == null ? -1 : DEEP_COMPAT_ORDER.indexOf(pkg);
        return idx >= 0 ? idx : Integer.MAX_VALUE;
    }

    private static int compatSummaryRes(String pkg) {
        if (pkg == null) return 0;
        Integer res = DEEP_COMPAT_SPECS.get(pkg);
        if (res != null) return res;
        if (WETYPE_PKG.equals(pkg)) return R.string.deep_desc_wetype;
        return 0;
    }
    private static final String[] LOG_TARGET_PACKAGES = {
            "android", CoverGeometry.SYSTEMUI, CoverGeometry.FLIPHOME, VOICEASSIST_PKG
    };
    /** Manual fallback when the module (and therefore the restore receiver) is already disabled. */
    private static final String[] ADB_RESTORE_COMMANDS = {
            "adb shell cmd overlay disable --user 0 android:mixflip2cover_statusbar",
            "adb shell cmd overlay disable --user 0 android:flipduo_cover_statusbar_v1",
            "adb shell cmd overlay disable --user 0 com.android.systemui:flipduo_cover_statusbar_v1",
            "adb shell pm enable com.sohu.inputmethod.sogou.xiaomi/com.sohu.inputmethod.sogou.xiaomi.SogouIME",
            "adb shell settings put secure default_input_method com.sohu.inputmethod.sogou.xiaomi/.SogouIME"
    };

    /** Default value of every boolean preference shown in the UI; must match {@link ModuleConfig}. */
    private static final Map<String, Boolean> DEFAULTS = Map.ofEntries(
            Map.entry(ModuleConfig.KEY_PANEL, true),
            Map.entry(ModuleConfig.KEY_IMMERSIVE, true),
            Map.entry(ModuleConfig.KEY_LIVE_ISLAND, true),
            Map.entry(ModuleConfig.KEY_UNIVERSAL_COMPAT, true),
            Map.entry(ModuleConfig.KEY_THIRD_PARTY_IME, ModuleConfig.DEFAULT_THIRD_PARTY_IME),
            Map.entry(ModuleConfig.KEY_HIDE_APP_NAMES, false),
            Map.entry(ModuleConfig.KEY_PINNED_WIDGET_ENABLED, false),
            Map.entry(ModuleConfig.KEY_BATTERY_NUMBER, false),
            Map.entry(ModuleConfig.KEY_DUAL_SIM, false),
            Map.entry(ModuleConfig.KEY_ALLOW_ALL_COVER_APPS, true),
            Map.entry(ModuleConfig.KEY_DIRECT_OPEN_COVER_NOTIF, true),
            Map.entry(ModuleConfig.KEY_VERBOSE_LOG, false));
    /** Keys whose defaults are written explicitly on first connection so hooked processes see them. */
    private static final List<String> PERSISTED_DEFAULT_KEYS = List.of(
            ModuleConfig.KEY_PANEL,
            ModuleConfig.KEY_IMMERSIVE,
            ModuleConfig.KEY_LIVE_ISLAND,
            ModuleConfig.KEY_UNIVERSAL_COMPAT,
            ModuleConfig.KEY_ALLOW_ALL_COVER_APPS,
            ModuleConfig.KEY_DIRECT_OPEN_COVER_NOTIF,
            ModuleConfig.KEY_THIRD_PARTY_IME);

    private static final ExecutorService IO = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "mf2c-settings-io");
        t.setDaemon(true);
        return t;
    });

    // ---- Process-wide service binding. The listener is static and only ever registered once; it forwards
    // events to the currently visible activity through a weak reference so old activities are not leaked.
    private static volatile XposedService cachedService;
    private static volatile WeakReference<MainActivity> currentActivity = new WeakReference<>(null);
    private static boolean serviceListenerRegistered;
    private static final XposedServiceHelper.OnServiceListener SERVICE_LISTENER = new XposedServiceHelper.OnServiceListener() {
        @Override
        public void onServiceBind(XposedService bound) {
            cachedService = bound;
            dispatchToActivity(a -> a.onServiceBound(bound));
        }

        @Override
        public void onServiceDied(XposedService bound) {
            if (cachedService == bound) cachedService = null;
            dispatchToActivity(a -> a.onServiceLost(bound));
        }
    };

    private static void dispatchToActivity(Consumer<MainActivity> action) {
        MainActivity a = currentActivity.get();
        if (a == null) return;
        a.runOnUiThread(() -> {
            if (!a.isFinishing() && !a.isDestroyed()) action.accept(a);
        });
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private XposedService service;
    private SharedPreferences prefs;
    private final List<M3Switch> switches = new ArrayList<>();
    private final Map<String, List<M3Switch>> switchesByKey = new HashMap<>();
    private final List<View> actionButtons = new ArrayList<>();
    private final List<TextView> scopeCountTexts = new ArrayList<>();
    private final List<TextView> deepCompatStatusTexts = new ArrayList<>();
    private final List<TextView> pinnedSummaryTexts = new ArrayList<>();
    private final List<WeakReference<Dialog>> trackedDialogs = new ArrayList<>();
    private final LruCache<String, Drawable> iconCache = new LruCache<>(160);
    private final Set<String> iconLoading = new HashSet<>();
    private LinearLayout root;
    private LinearLayout pageContainer;
    private CoverPreviewView coverPreview;
    private int selected;
    private boolean loading, renderPending, dark;
    private String frameworkProp = "";
    private Toast toast;
    private M3Palette palette;
    private OnBackInvokedCallback backCallback;
    private boolean backCallbackRegistered;

    private final int[] pageTitles = {R.string.tab_home, R.string.tab_appearance, R.string.tab_layout, R.string.tab_compat};
    private final int[] pageIcons = {Glyph.HOME, Glyph.ORB, Glyph.SPARKLE, Glyph.SHIELD};

    @Override
    public void onCreate(Bundle state) {
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        setTheme(dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(state);
        palette = new M3Palette(this, dark);

        if (state != null) {
            selected = state.getInt("page", 0);
        }
        frameworkProp = FrameworkRuntime.readProperty();
        setupPredictiveBack();
        render(false);
        configureEdgeToEdge(dark);

        currentActivity = new WeakReference<>(this);
        if (!serviceListenerRegistered) {
            serviceListenerRegistered = true;
            XposedServiceHelper.registerListener(SERVICE_LISTENER);
        }
        XposedService existing = cachedService;
        if (existing != null) onServiceBound(existing);
    }

    private void onServiceBound(XposedService bound) {
        if (service == bound && prefs != null) return;
        service = bound;
        try {
            prefs = bound.getRemotePreferences(ModuleConfig.PREFS_NAME);
            ModuleConfig.initLocal(prefs);
            ensureDefaults();
            List<String> scope = bound.getScope();
            ModLog.i(TAG, "connected; frameworkScope=" + scope.contains("system") + " scopeCount=" + scope.size());
        } catch (RuntimeException e) {
            Log.w(TAG, "service bind failed", e);
            if (cachedService == bound) cachedService = null;
            service = null;
            show(getString(R.string.msg_connect_failed, String.valueOf(e.getMessage())));
        }
        render(false);
    }

    private void onServiceLost(XposedService bound) {
        if (service != bound) return;
        service = null;
        show(getString(R.string.msg_service_died));
        render(false);
    }

    /**
     * Runs a call against the LSPosed service. Any RuntimeException (the service process may die at any time)
     * is caught; when {@code disconnectOnError} is set, or the binder is dead, the service is treated as
     * disconnected and the UI is re-rendered. Returns {@code null} on failure.
     */
    private <T> T callService(String what, Function<XposedService, T> call, boolean disconnectOnError) {
        XposedService s = service;
        if (s == null) return null;
        try {
            return call.apply(s);
        } catch (RuntimeException e) {
            Log.w(TAG, "service call failed: " + what, e);
            boolean dead = e.getCause() instanceof DeadObjectException;
            if (disconnectOnError || dead) {
                if (cachedService == s) cachedService = null;
                if (service == s) service = null;
                show(getString(R.string.msg_service_died));
                scheduleRender();
            } else {
                throw e;
            }
            return null;
        }
    }

    /** Current scope or {@code null} when disconnected. */
    private List<String> scopeSafe() {
        return callService("getScope", s -> new ArrayList<>(s.getScope()), true);
    }

    private void scheduleRender() {
        if (renderPending) return;
        renderPending = true;
        mainHandler.post(() -> {
            renderPending = false;
            render(false);
        });
    }

    private void configureEdgeToEdge(boolean dark) {
        try {
            Window window = getWindow();
            window.getDecorView();
            window.setDecorFitsSystemWindows(false);
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
            if (Build.VERSION.SDK_INT >= 29) {
                window.setNavigationBarContrastEnforced(false);
            }
            WindowInsetsController controller = window.getDecorView().getWindowInsetsController();
            if (controller != null) {
                int lightFlags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(dark ? 0 : lightFlags, lightFlags);
            }
        } catch (RuntimeException ignored) {
        }
    }

    private void setupPredictiveBack() {
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = () -> {
                if (selected != 0) {
                    selectTab(0);
                }
            };
            updateBackCallbackRegistration();
        }
    }

    private void updateBackCallbackRegistration() {
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            if (selected != 0 && !backCallbackRegistered) {
                getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                        OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
                backCallbackRegistered = true;
            } else if (selected == 0 && backCallbackRegistered) {
                getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
                backCallbackRegistered = false;
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        currentActivity = new WeakReference<>(this);
        // SystemProperties.get via reflection is a cheap in-process read; no getprop process is spawned.
        String prop = FrameworkRuntime.readProperty();
        if (!prop.equals(frameworkProp)) {
            frameworkProp = prop;
            render(false);
        } else {
            refreshScopeTexts();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putInt("page", selected);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null && backCallbackRegistered) {
            try {
                getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
            } catch (RuntimeException ignored) {
            }
            backCallbackRegistered = false;
        }
        if (currentActivity.get() == this) currentActivity = new WeakReference<>(null);
        for (WeakReference<Dialog> ref : trackedDialogs) {
            Dialog d = ref.get();
            if (d != null && d.isShowing()) {
                try {
                    d.dismiss();
                } catch (RuntimeException ignored) {
                }
            }
        }
        trackedDialogs.clear();
        mainHandler.removeCallbacksAndMessages(null);
        if (toast != null) toast.cancel();
        super.onDestroy();
    }

    // ==================== Preferences & switch dependencies ====================

    private boolean defaultValue(String key) {
        Boolean d = DEFAULTS.get(key);
        return d != null && d;
    }

    private boolean value(String key) {
        boolean fallback = defaultValue(key);
        SharedPreferences p = prefs;
        if (p == null) return fallback;
        try {
            return p.getBoolean(key, fallback);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private Map<String, Boolean> currentState() {
        Map<String, Boolean> state = new HashMap<>();
        for (String key : DEFAULTS.keySet()) state.put(key, value(key));
        return state;
    }

    /**
     * Single source of truth for switch dependencies, used both when a switch changes and to repair
     * inconsistent stored state. Hard constraints:
     * <ul>
     *   <li>status orb ⇒ Super Island / widget adaptation;</li>
     *   <li>battery number / dual SIM ⇒ status orb;</li>
     *   <li>top-gap removal ⇒ fullscreen cover apps.</li>
     * </ul>
     * A violated constraint is resolved in favour of the switch the user just changed. Additionally, turning
     * fullscreen on also turns top-gap removal on (the two are designed to be used together).
     *
     * @param changedKey the key the user changed, or {@code null} to only repair the stored state
     * @return the preference values that differ from {@code state} (always contains {@code changedKey})
     */
    static Map<String, Boolean> resolveDependencies(Map<String, Boolean> state, String changedKey, boolean value) {
        Map<String, Boolean> s = new HashMap<>(state);
        if (changedKey != null) {
            s.put(changedKey, value);
            if (ModuleConfig.KEY_IMMERSIVE.equals(changedKey) && value) {
                s.put(ModuleConfig.KEY_UNIVERSAL_COMPAT, true);
            }
        }
        // C: universal compat ⇒ immersive
        if (bool(s, ModuleConfig.KEY_UNIVERSAL_COMPAT) && !bool(s, ModuleConfig.KEY_IMMERSIVE)) {
            if (ModuleConfig.KEY_UNIVERSAL_COMPAT.equals(changedKey)) s.put(ModuleConfig.KEY_IMMERSIVE, true);
            else s.put(ModuleConfig.KEY_UNIVERSAL_COMPAT, false);
        }
        enforceOrbChildren(s, changedKey);
        // A: panel ⇒ live island
        if (bool(s, ModuleConfig.KEY_PANEL) && !bool(s, ModuleConfig.KEY_LIVE_ISLAND)) {
            if (ModuleConfig.KEY_LIVE_ISLAND.equals(changedKey)) s.put(ModuleConfig.KEY_PANEL, false);
            else s.put(ModuleConfig.KEY_LIVE_ISLAND, true);
        }
        enforceOrbChildren(s, changedKey);

        Map<String, Boolean> diff = new LinkedHashMap<>();
        if (changedKey != null) diff.put(changedKey, value);
        for (Map.Entry<String, Boolean> e : s.entrySet()) {
            if (!e.getValue().equals(state.get(e.getKey()))) diff.put(e.getKey(), e.getValue());
        }
        return diff;
    }

    /** B: battery number / dual SIM ⇒ panel. */
    private static void enforceOrbChildren(Map<String, Boolean> s, String changedKey) {
        boolean child = bool(s, ModuleConfig.KEY_BATTERY_NUMBER) || bool(s, ModuleConfig.KEY_DUAL_SIM);
        if (child && !bool(s, ModuleConfig.KEY_PANEL)) {
            if (ModuleConfig.KEY_BATTERY_NUMBER.equals(changedKey) || ModuleConfig.KEY_DUAL_SIM.equals(changedKey)) {
                s.put(ModuleConfig.KEY_PANEL, true);
            } else {
                s.put(ModuleConfig.KEY_BATTERY_NUMBER, false);
                s.put(ModuleConfig.KEY_DUAL_SIM, false);
            }
        }
    }

    private static boolean bool(Map<String, Boolean> s, String key) {
        Boolean v = s.get(key);
        return v != null && v;
    }

    private void ensureDefaults() {
        if (prefs == null) return;
        SharedPreferences.Editor editor = prefs.edit();
        boolean changed = false;
        for (String key : PERSISTED_DEFAULT_KEYS) {
            if (!prefs.contains(key)) {
                editor.putBoolean(key, defaultValue(key));
                changed = true;
            }
        }
        for (Map.Entry<String, Boolean> e : resolveDependencies(currentState(), null, false).entrySet()) {
            editor.putBoolean(e.getKey(), e.getValue());
            changed = true;
        }
        if (changed) editor.apply();
    }

    private void applySwitchChange(String key, boolean checked) {
        if (prefs == null || service == null || !CoverDisplay.isSupportedFirmware()) return;
        try {
            Map<String, Boolean> updates = resolveDependencies(currentState(), key, checked);
            SharedPreferences.Editor editor = prefs.edit();
            for (Map.Entry<String, Boolean> entry : updates.entrySet()) {
                editor.putBoolean(entry.getKey(), entry.getValue());
            }
            editor.apply();
            for (Map.Entry<String, Boolean> entry : updates.entrySet()) {
                syncSwitches(entry.getKey(), entry.getValue());
            }
            if (updates.containsKey(ModuleConfig.KEY_PINNED_WIDGET_ENABLED)) {
                boolean pinEnabled = Boolean.TRUE.equals(updates.get(ModuleConfig.KEY_PINNED_WIDGET_ENABLED));
                broadcastPinnedWidgetSync(pinEnabled, prefs.getString(ModuleConfig.KEY_PINNED_WIDGET_APPS, ""));
            }
            if (updates.containsKey(ModuleConfig.KEY_THIRD_PARTY_IME)) refreshScopeTexts();
            if (coverPreview != null) coverPreview.invalidate();
            if (ModuleConfig.KEY_PINNED_WIDGET_ENABLED.equals(key) && checked && currentPinnedPackages().isEmpty()) {
                showPinnedWidgetAppsPickerDialog();
            } else if (ModuleConfig.KEY_THIRD_PARTY_IME.equals(key) && checked && isWeTypeInstalledWithoutScope()) {
                requestPackages(List.of(WETYPE_PKG));
            } else {
                showSwitchEffectHint(key);
            }
        } catch (RuntimeException e) {
            show(getString(R.string.msg_save_failed, String.valueOf(e.getMessage())));
        }
    }

    private void showSwitchEffectHint(String key) {
        int msgRes = switch (key) {
            case ModuleConfig.KEY_HIDE_APP_NAMES -> R.string.msg_effect_launcher;
            case ModuleConfig.KEY_IMMERSIVE, ModuleConfig.KEY_UNIVERSAL_COMPAT -> R.string.msg_effect_reopen_app;
            case ModuleConfig.KEY_THIRD_PARTY_IME -> R.string.msg_effect_ime;
            default -> R.string.msg_effect_realtime;
        };
        show(getString(msgRes));
    }

    private void syncSwitches(String key, boolean value) {
        List<M3Switch> bound = switchesByKey.get(key);
        if (bound != null) {
            for (M3Switch sw : bound) sw.setChecked(value, false);
        }
    }

    private boolean isWeTypeInstalledWithoutScope() {
        try {
            getPackageManager().getPackageInfo(WETYPE_PKG, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
        List<String> scope = scopeSafe();
        return scope != null && !scope.contains(WETYPE_PKG);
    }

    private boolean isCompactCoverUi() {
        Configuration cfg = getResources().getConfiguration();
        if (cfg.screenWidthDp > 0 && cfg.screenWidthDp < 360) return true;
        try {
            if (CoverDisplay.isOuterDisplay(getDisplay())) return true;
        } catch (RuntimeException ignored) {
        }
        int widthPx = getResources().getDisplayMetrics().widthPixels;
        float density = getResources().getDisplayMetrics().density;
        return density > 0f && (widthPx / density) < 360f;
    }

    private <T extends Dialog> T trackDialog(T dialog) {
        if (dialog != null) {
            trackedDialogs.removeIf(ref -> {
                Dialog existing = ref.get();
                return existing == null || !existing.isShowing();
            });
            trackedDialogs.add(new WeakReference<>(dialog));
        }
        return dialog;
    }

    private record SafeWindowFrame(int winWidth, int winHeight, Rect safeRect) {}

    private SafeWindowFrame currentSafeWindowFrame() {
        int winWidth = getResources().getDisplayMetrics().widthPixels;
        int winHeight = getResources().getDisplayMetrics().heightPixels;
        int safeLeft = 0;
        int safeTop = 0;
        int safeRight = 0;
        int safeBottom = 0;
        boolean hasCutoutInsets = false;
        try {
            WindowMetrics wm = getWindowManager().getCurrentWindowMetrics();
            Rect bounds = wm.getBounds();
            if (bounds.width() > 0 && bounds.height() > 0) {
                winWidth = bounds.width();
                winHeight = bounds.height();
            }
            WindowInsets wi = wm.getWindowInsets();
            Insets cutout = wi.getInsets(WindowInsets.Type.displayCutout());
            hasCutoutInsets = cutout.left > 0 || cutout.top > 0 || cutout.right > 0 || cutout.bottom > 0;
            Insets safe = wi.getInsets(WindowInsets.Type.displayCutout() | WindowInsets.Type.systemBars());
            safeLeft = safe.left;
            safeTop = safe.top;
            safeRight = safe.right;
            safeBottom = safe.bottom;
        } catch (RuntimeException ignored) {
        }
        Display display = null;
        try {
            display = getDisplay();
        } catch (RuntimeException ignored) {
        }
        boolean onCover = CoverDisplay.isOuterDisplay(display);
        if (onCover && CoverDisplay.isOuterDimensions(winWidth, winHeight) && !hasCutoutInsets) {
            int rotation = display != null ? display.getRotation() : Surface.ROTATION_0;
            int[] fallback = CoverDisplay.outerCutoutSafeInsets(rotation);
            safeLeft = Math.max(safeLeft, fallback[0]);
            safeTop = Math.max(safeTop, fallback[1]);
            safeRight = Math.max(safeRight, fallback[2]);
            safeBottom = Math.max(safeBottom, fallback[3]);
        }
        int right = Math.max(safeLeft + 1, winWidth - safeRight);
        int bottom = Math.max(safeTop + 1, winHeight - safeBottom);
        return new SafeWindowFrame(winWidth, winHeight, new Rect(safeLeft, safeTop, right, bottom));
    }

    private void configureCoverSafeDialogWindow(Dialog dialog, boolean tallSheet, boolean customBackground) {
        if (dialog == null) return;
        Window w = dialog.getWindow();
        if (w == null) return;
        if (customBackground) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        SafeWindowFrame frame = currentSafeWindowFrame();
        Rect safe = frame.safeRect();
        int safeW = safe.width();
        int safeH = safe.height();
        int safeRight = frame.winWidth() - safe.right;
        int safeBottom = frame.winHeight() - safe.bottom;

        int capW = Math.max(1, safeW - dp(8));
        int maxDpWidth = tallSheet ? dp(520) : dp(440);
        int rawWidth = Math.min(maxDpWidth, Math.round(safeW * 0.94f));
        int width = Math.min(capW, Math.max(Math.min(dp(240), capW), rawWidth));
        int capH = Math.max(1, safeH - dp(12));
        int height = tallSheet
                ? Math.min(capH, Math.max(Math.min(dp(240), capH), Math.round(safeH * 0.88f)))
                : ViewGroup.LayoutParams.WRAP_CONTENT;

        WindowManager.LayoutParams lp = w.getAttributes();
        lp.gravity = Gravity.CENTER;
        lp.x = Math.round((safe.left - safeRight) / 2f);
        lp.y = Math.round((safe.top - safeBottom) / 2f);
        w.setAttributes(lp);
        w.setLayout(width, height);
    }

    private void configureCoverSafePickerWindow(Dialog dialog, boolean hasExtraTopBar) {
        if (dialog == null) return;
        Window w = dialog.getWindow();
        if (w == null) return;
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        SafeWindowFrame frame = currentSafeWindowFrame();
        Rect safe = frame.safeRect();
        int safeW = safe.width();
        int safeH = safe.height();
        int safeRight = frame.winWidth() - safe.right;
        int safeBottom = frame.winHeight() - safe.bottom;

        int capW = Math.max(1, safeW - dp(8));
        int rawWidth = Math.min(dp(440), Math.round(safeW * 0.94f));
        int width = Math.min(capW, Math.max(Math.min(dp(240), capW), rawWidth));

        int capH = Math.max(1, safeH - dp(12));
        int height;
        if (safeH < dp(420)) {
            height = capH;
        } else {
            int maxDpH = hasExtraTopBar ? dp(640) : dp(580);
            float ratio = hasExtraTopBar ? 0.86f : 0.82f;
            height = Math.min(capH, Math.min(maxDpH, Math.round(safeH * ratio)));
        }

        WindowManager.LayoutParams lp = w.getAttributes();
        lp.gravity = Gravity.CENTER;
        lp.x = Math.round((safe.left - safeRight) / 2f);
        lp.y = Math.round((safe.top - safeBottom) / 2f);
        w.setAttributes(lp);
        w.setLayout(width, height);
    }

    // ==================== Rendering ====================

    private int dp(float n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    private TextView createText(CharSequence value, float sizeSp, int color, Typeface typeface) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        t.setTextColor(color);
        t.setTypeface(typeface);
        return t;
    }

    private GradientDrawable createShape(int fillColor, float rTopLeftDp, float rTopRightDp, float rBottomRightDp, float rBottomLeftDp, int strokeWidthPx, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fillColor);
        float tl = dp(rTopLeftDp);
        float tr = dp(rTopRightDp);
        float br = dp(rBottomRightDp);
        float bl = dp(rBottomLeftDp);
        d.setCornerRadii(new float[]{tl, tl, tr, tr, br, br, bl, bl});
        if (strokeWidthPx > 0) {
            d.setStroke(strokeWidthPx, strokeColor);
        }
        return d;
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        return createShape(color, radiusDp, radiusDp, radiusDp, radiusDp, 0, 0);
    }

    private RippleDrawable ripple(int contentColor, int rippleColor, float rTl, float rTr, float rBr, float rBl, int strokeWidthPx, int strokeColor) {
        GradientDrawable content = createShape(contentColor, rTl, rTr, rBr, rBl, strokeWidthPx, strokeColor);
        GradientDrawable mask = createShape(Color.WHITE, rTl, rTr, rBr, rBl, 0, 0);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
    }

    private RippleDrawable pillRipple(int contentColor, int rippleColor, float radiusDp) {
        return ripple(contentColor, rippleColor, radiusDp, radiusDp, radiusDp, radiusDp, 0, 0);
    }

    private void selectTab(int index) {
        if (selected == index) return;
        selected = index;
        updateBackCallbackRegistration();
        render(true);
    }

    private enum Status { UNSUPPORTED_DEVICE, UNSUPPORTED_FIRMWARE, DISCONNECTED, READY, NEEDS_REBOOT }

    private Status status() {
        if (!CoverDisplay.isSupportedDevice()) return Status.UNSUPPORTED_DEVICE;
        if (!CoverDisplay.isSupportedFirmware()) return Status.UNSUPPORTED_FIRMWARE;
        if (service == null) return Status.DISCONNECTED;
        return frameworkReady() ? Status.READY : Status.NEEDS_REBOOT;
    }

    private boolean frameworkReady() {
        return FrameworkRuntime.VERSION.equals(frameworkProp);
    }

    private void render(boolean animateTransition) {
        if (isFinishing() || isDestroyed()) return;
        loading = true;
        switches.clear();
        switchesByKey.clear();
        actionButtons.clear();
        scopeCountTexts.clear();
        deepCompatStatusTexts.clear();
        pinnedSummaryTexts.clear();
        coverPreview = null;

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(palette.background);

        boolean compact = isCompactCoverUi();

        // Top App Bar (Material 3 Large Header style)
        LinearLayout appBar = new LinearLayout(this);
        appBar.setOrientation(LinearLayout.HORIZONTAL);
        appBar.setGravity(Gravity.CENTER_VERTICAL);
        int appBarPadH = compact ? dp(16) : dp(24);
        appBar.setPadding(appBarPadH, dp(16), appBarPadH, dp(12));

        LinearLayout titleColumn = new LinearLayout(this);
        titleColumn.setOrientation(LinearLayout.VERTICAL);
        TextView headline = createText(
                getString(selected == 0 ? R.string.app_name : pageTitles[selected]),
                compact ? 22f : 28f, palette.onSurface, FONT_MEDIUM);
        headline.setLetterSpacing(-0.01f);
        headline.setAccessibilityHeading(true);
        titleColumn.addView(headline);

        int subText = switch (selected) {
            case 0 -> R.string.sub_home;
            case 1 -> R.string.sub_appearance;
            case 2 -> R.string.sub_layout;
            default -> R.string.sub_compat;
        };
        TextView subtitle = createText(getString(subText), 13f, palette.onSurfaceVariant, FONT_REGULAR);
        subtitle.setPadding(0, dp(3), 0, 0);
        titleColumn.addView(subtitle);
        appBar.addView(titleColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Top-right status badge chip
        Status st = status();
        int badgeText, badgeFg, badgeBg;
        switch (st) {
            case UNSUPPORTED_DEVICE, UNSUPPORTED_FIRMWARE -> {
                badgeText = R.string.badge_unsupported;
                badgeFg = palette.onErrorContainer;
                badgeBg = palette.errorContainer;
            }
            case DISCONNECTED -> {
                badgeText = R.string.badge_disconnected;
                badgeFg = palette.onErrorContainer;
                badgeBg = palette.errorContainer;
            }
            case READY -> {
                badgeText = R.string.badge_ready;
                badgeFg = palette.onPrimaryContainer;
                badgeBg = palette.primaryContainer;
            }
            default -> {
                badgeText = R.string.badge_reboot;
                badgeFg = palette.onTertiaryContainer;
                badgeBg = palette.tertiaryContainer;
            }
        }
        TextView topBadge = createText(getString(badgeText), 12f, badgeFg, FONT_MEDIUM);
        topBadge.setPadding(dp(12), dp(6), dp(12), dp(6));
        topBadge.setBackground(rounded(badgeBg, 16f));
        appBar.addView(topBadge);
        root.addView(appBar);

        // Scrollable Content Area
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);

        pageContainer = new LinearLayout(this);
        pageContainer.setOrientation(LinearLayout.VERTICAL);
        pageContainer.setPadding(dp(16), dp(6), dp(16), dp(28));
        scroll.addView(pageContainer);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        switch (selected) {
            case 0 -> buildHomePage(st);
            case 1 -> buildAppearancePage();
            case 2 -> buildLayoutPage();
            default -> buildCompatibilityPage();
        }

        // Material 3 Navigation Bar at bottom
        LinearLayout navBarContainer = new LinearLayout(this);
        navBarContainer.setOrientation(LinearLayout.VERTICAL);
        navBarContainer.setBackgroundColor(palette.surfaceContainer);

        View navDivider = new View(this);
        navDivider.setBackgroundColor(palette.outlineVariant);
        navBarContainer.addView(navDivider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        LinearLayout navBar = new LinearLayout(this);
        navBar.setOrientation(LinearLayout.HORIZONTAL);
        navBar.setGravity(Gravity.CENTER_VERTICAL);
        navBar.setPadding(dp(8), dp(10), dp(8), dp(10));

        for (int i = 0; i < pageTitles.length; i++) {
            final int index = i;
            boolean active = i == selected;
            String title = getString(pageTitles[i]);
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setClickable(true);
            item.setFocusable(true);
            item.setMinimumHeight(dp(48));
            item.setSelected(active);
            item.setContentDescription(title);
            item.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName("android.widget.Button");
                    info.setSelected(active);
                }
            });

            FrameLayout pill = new FrameLayout(this);
            pill.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            if (active) {
                pill.setBackground(rounded(palette.secondaryContainer, 16f));
            }
            GlyphView iconView = new GlyphView(this, pageIcons[i],
                    active ? palette.onSecondaryContainer : palette.onSurfaceVariant, active);
            FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER);
            pill.addView(iconView, iconLp);
            LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(compact ? dp(52) : dp(64), dp(32));
            pillLp.gravity = Gravity.CENTER_HORIZONTAL;
            item.addView(pill, pillLp);

            TextView label = createText(title, 12f,
                    active ? palette.onSurface : palette.onSurfaceVariant,
                    active ? FONT_MEDIUM : FONT_REGULAR);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, dp(4), 0, 0);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            labelLp.gravity = Gravity.CENTER_HORIZONTAL;
            item.addView(label, labelLp);

            item.setOnClickListener(v -> selectTab(index));
            navBar.addView(item, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (active && animateTransition) {
                pill.setScaleX(0.65f);
                pill.setAlpha(0.5f);
                pill.animate().scaleX(1f).alpha(1f).setDuration(220).setInterpolator(M3_STANDARD).start();
            }
        }
        navBarContainer.addView(navBar);
        root.addView(navBarContainer);

        final LinearLayout pages = pageContainer;
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            var sysBars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            appBar.setPadding(appBarPadH + sysBars.left, dp(14) + sysBars.top, appBarPadH + sysBars.right, dp(10));
            pages.setPadding(dp(16) + sysBars.left, dp(6), dp(16) + sysBars.right, dp(28));
            navBar.setPadding(dp(8) + sysBars.left, dp(10), dp(8) + sysBars.right, dp(10) + sysBars.bottom);
            return WindowInsets.CONSUMED;
        });

        setContentView(root);
        boolean interactive = service != null && CoverDisplay.isSupportedFirmware();
        for (M3Switch s : switches) s.setSwitchEnabled(interactive);
        for (View b : actionButtons) {
            b.setEnabled(interactive);
            b.setAlpha(interactive ? 1f : 0.45f);
        }
        loading = false;
        root.requestApplyInsets();

        if (animateTransition) {
            pages.setAlpha(0f);
            pages.setTranslationY(dp(10));
            pages.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(200)
                    .setInterpolator(M3_STANDARD)
                    .start();
        }
    }

    // ==================== Page Builders ====================

    private String versionName() {
        return BuildConfig.VERSION_NAME;
    }

    private void buildHomePage(Status st) {
        List<String> scope = service == null ? null : scopeSafe();
        int scopeCount = scope == null ? 0 : scope.size();

        // Expressive M3 Hero Banner Card with Live Cover Display Preview
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(20), dp(20), dp(20), dp(20));
        hero.setBackground(createShape(palette.primaryContainer, 28f, 28f, 28f, 28f, 0, 0));
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        heroLp.bottomMargin = dp(16);
        pageContainer.addView(hero, heroLp);

        LinearLayout heroTop = new LinearLayout(this);
        heroTop.setOrientation(LinearLayout.HORIZONTAL);
        heroTop.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout heroInfo = new LinearLayout(this);
        heroInfo.setOrientation(LinearLayout.VERTICAL);
        heroInfo.setPadding(0, 0, dp(16), 0);

        String title = switch (st) {
            case UNSUPPORTED_DEVICE -> getString(R.string.status_unsupported_device);
            case UNSUPPORTED_FIRMWARE -> getString(R.string.status_unsupported_firmware, CoverDisplay.systemVersionSummary());
            case DISCONNECTED -> getString(R.string.status_inactive);
            case READY -> getString(R.string.status_running);
            default -> getString(R.string.status_needs_reboot);
        };
        boolean unsupported = st == Status.UNSUPPORTED_DEVICE || st == Status.UNSUPPORTED_FIRMWARE;
        TextView heroStatusTitle = createText(title, unsupported ? 18f : 22f, palette.onPrimaryContainer, FONT_BOLD);
        heroInfo.addView(heroStatusTitle);

        String fwText;
        if (frameworkReady()) fwText = getString(R.string.fw_ready);
        else if (frameworkProp.isEmpty()) fwText = getString(R.string.fw_inactive);
        else fwText = getString(R.string.fw_mismatch);
        StringBuilder meta = new StringBuilder(getString(R.string.meta_version, versionName()))
                .append(" · ")
                .append(getString(R.string.meta_target));
        if (!unsupported) meta.append(" · ").append(fwText);
        if (scope != null) meta.append(" · ").append(getString(R.string.meta_scopes, scopeCount));
        TextView heroMeta = createText(meta.toString(), 13f, palette.onPrimaryContainerVariant, FONT_MEDIUM);
        heroMeta.setPadding(0, dp(4), 0, dp(10));
        heroInfo.addView(heroMeta);

        int hint = switch (st) {
            case UNSUPPORTED_DEVICE, UNSUPPORTED_FIRMWARE -> R.string.hint_unsupported;
            case DISCONNECTED -> R.string.hint_disconnected;
            case READY -> R.string.hint_ready;
            default -> R.string.hint_reboot;
        };
        TextView hintText = createText(getString(hint), 13f, palette.onPrimaryContainerVariant, FONT_REGULAR);
        hintText.setLineSpacing(dp(3), 1f);
        heroInfo.addView(hintText);

        heroTop.addView(heroInfo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        boolean compact = isCompactCoverUi();

        // Live Cover Screen Preview Widget
        coverPreview = new CoverPreviewView(this);
        coverPreview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        heroTop.addView(coverPreview, new LinearLayout.LayoutParams(
                compact ? dp(76) : dp(92), compact ? dp(88) : dp(106)));
        hero.addView(heroTop);

        // Hero Quick Action Pill Buttons (stacked vertically on narrow cover widths to prevent truncation)
        LinearLayout heroActions = new LinearLayout(this);
        heroActions.setOrientation(compact ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        heroActions.setPadding(0, dp(16), 0, 0);

        View primaryBtn = createPillButton(
                getString(R.string.action_zero_blank),
                Glyph.SPARKLE,
                palette.primary,
                palette.onPrimary,
                this::enableUniversalZeroBlank);
        LinearLayout.LayoutParams btn1Lp = compact
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
                : new LinearLayout.LayoutParams(0, dp(48), 1.2f);
        if (compact) btn1Lp.bottomMargin = dp(8);
        else btn1Lp.rightMargin = dp(10);
        heroActions.addView(primaryBtn, btn1Lp);

        View secondaryBtn = createPillButton(
                getString(R.string.action_request_core),
                Glyph.SHIELD,
                palette.surfaceContainer,
                palette.onSurface,
                this::requestCore);
        LinearLayout.LayoutParams btn2Lp = compact
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
                : new LinearLayout.LayoutParams(0, dp(48), 1f);
        heroActions.addView(secondaryBtn, btn2Lp);
        hero.addView(heroActions);

        // Overview & quick navigation cards (no duplicate switches on Home)
        addSectionHeader(getString(R.string.section_overview));
        SegmentedGroup overviewGroup = new SegmentedGroup();
        overviewGroup.addActionRow(
                Glyph.ORB,
                getString(R.string.home_nav_appearance_title),
                appearanceOverviewSubtitle(),
                getString(R.string.chip_goto),
                false,
                false,
                () -> selectTab(1));
        overviewGroup.addActionRow(
                Glyph.FULLSCREEN,
                getString(R.string.home_nav_layout_title),
                layoutOverviewSubtitle(),
                getString(R.string.chip_goto),
                false,
                false,
                () -> selectTab(2));
        int homePinnedRow = overviewGroup.addActionRow(
                Glyph.GRID,
                getString(R.string.sw_pinned_widget_title),
                pinnedWidgetAppsSummary(),
                getString(R.string.chip_configure),
                false,
                true,
                this::showPinnedWidgetAppsPickerDialog);
        overviewGroup.addActionRow(
                Glyph.SHIELD,
                getString(R.string.home_nav_compat_title),
                compatOverviewSubtitle(scope),
                getString(R.string.chip_manage),
                false,
                false,
                () -> selectTab(3));
        overviewGroup.commit();
        addPinnedSummaryText(overviewGroup.subtitleAt(homePinnedRow));

        addSectionHeader(getString(R.string.section_builtin));
        SegmentedGroup builtinGroup = new SegmentedGroup();
        builtinGroup.addInfoRow(Glyph.GRID, getString(R.string.info_grid_title), getString(R.string.info_grid_sub));
        builtinGroup.addInfoRow(Glyph.KEYBOARD, getString(R.string.info_ime_avoid_title), getString(R.string.info_ime_avoid_sub));
        builtinGroup.commit();
    }

    private String appearanceOverviewSubtitle() {
        return getString(
                R.string.home_nav_appearance_sub,
                stateText(value(ModuleConfig.KEY_PANEL)),
                stateText(value(ModuleConfig.KEY_LIVE_ISLAND)),
                stateText(value(ModuleConfig.KEY_HIDE_APP_NAMES)));
    }

    private String layoutOverviewSubtitle() {
        boolean zeroBlank = value(ModuleConfig.KEY_IMMERSIVE) && value(ModuleConfig.KEY_UNIVERSAL_COMPAT);
        boolean unblocked = value(ModuleConfig.KEY_ALLOW_ALL_COVER_APPS) && value(ModuleConfig.KEY_DIRECT_OPEN_COVER_NOTIF);
        return getString(
                R.string.home_nav_layout_sub,
                stateText(zeroBlank),
                stateText(unblocked),
                stateText(value(ModuleConfig.KEY_THIRD_PARTY_IME)));
    }

    private String compatOverviewSubtitle(List<String> scope) {
        if (scope == null) {
            return getString(R.string.home_nav_compat_sub_disconnected);
        }
        LinkedHashSet<String> missing = required();
        missing.removeAll(scope);
        if (!missing.isEmpty()) {
            return getString(R.string.home_nav_compat_sub_missing, missing.size(), scopeLabels(missing));
        }
        return getString(R.string.home_nav_compat_sub_ready, scope.size());
    }

    private void buildAppearancePage() {
        addSectionHeader(getString(R.string.section_orb));
        SegmentedGroup orbGroup = new SegmentedGroup();
        orbGroup.addSwitch(Glyph.ORB, R.string.sw_panel_title, R.string.sw_panel_sub, ModuleConfig.KEY_PANEL);
        orbGroup.addSwitch(Glyph.BATTERY, R.string.sw_battery_title, R.string.sw_battery_sub, ModuleConfig.KEY_BATTERY_NUMBER);
        orbGroup.addSwitch(Glyph.SIM, R.string.sw_dual_title, R.string.sw_dual_sub, ModuleConfig.KEY_DUAL_SIM);
        orbGroup.commit();

        addSectionHeader(getString(R.string.section_desktop));
        SegmentedGroup desktopGroup = new SegmentedGroup();
        desktopGroup.addSwitch(Glyph.ISLAND, R.string.sw_island_title, R.string.sw_island_sub, ModuleConfig.KEY_LIVE_ISLAND);
        desktopGroup.addSwitch(Glyph.GRID, R.string.sw_hide_names_title, R.string.sw_hide_names_sub, ModuleConfig.KEY_HIDE_APP_NAMES);
        desktopGroup.addSwitch(Glyph.GRID, R.string.sw_pinned_widget_title, R.string.sw_pinned_widget_sub, ModuleConfig.KEY_PINNED_WIDGET_ENABLED);
        int pinnedRow = desktopGroup.addActionRow(Glyph.SPARKLE, getString(R.string.row_pinned_widget_apps_title),
                pinnedWidgetAppsSummary(), getString(R.string.chip_configure), false, true, this::showPinnedWidgetAppsPickerDialog);
        desktopGroup.commit();
        addPinnedSummaryText(desktopGroup.subtitleAt(pinnedRow));
    }

    private void buildLayoutPage() {
        addSectionHeader(getString(R.string.section_window));
        SegmentedGroup windowGroup = new SegmentedGroup();
        windowGroup.addSwitch(Glyph.FULLSCREEN, R.string.sw_immersive_title, R.string.sw_immersive_sub, ModuleConfig.KEY_IMMERSIVE);
        windowGroup.addSwitch(Glyph.SPARKLE, R.string.sw_compat_title, R.string.sw_compat_sub, ModuleConfig.KEY_UNIVERSAL_COMPAT);
        windowGroup.addActionRow(Glyph.SPARKLE, getString(R.string.row_zero_blank_title), getString(R.string.row_zero_blank_sub),
                getString(R.string.chip_enable), true, true, this::enableUniversalZeroBlank);
        windowGroup.commit();

        addSectionHeader(getString(R.string.section_unblock));
        SegmentedGroup unblockGroup = new SegmentedGroup();
        unblockGroup.addSwitch(Glyph.LAYOUT, R.string.sw_allow_all_title, R.string.sw_allow_all_sub, ModuleConfig.KEY_ALLOW_ALL_COVER_APPS);
        unblockGroup.addSwitch(Glyph.ISLAND, R.string.sw_notif_open_title, R.string.sw_notif_open_sub, ModuleConfig.KEY_DIRECT_OPEN_COVER_NOTIF);
        unblockGroup.commit();

        addSectionHeader(getString(R.string.section_ime_compat));
        SegmentedGroup imeGroup = new SegmentedGroup();
        imeGroup.addSwitch(Glyph.KEYBOARD, R.string.sw_ime_title, R.string.sw_ime_sub, ModuleConfig.KEY_THIRD_PARTY_IME);
        imeGroup.addInfoRow(Glyph.INFO, getString(R.string.info_ime_scope_title), getString(R.string.info_ime_scope_sub));
        imeGroup.commit();
    }

    private void buildCompatibilityPage() {
        addSectionHeader(getString(R.string.section_scope));
        buildDeepCompatGuideCard();
        SegmentedGroup scopeGroup = new SegmentedGroup();
        scopeGroup.addActionRow(Glyph.LAYERS, getString(R.string.row_ext_scope_title), getString(R.string.row_ext_scope_sub),
                getString(R.string.chip_manage), false, true, this::showAppScopePickerDialog);
        int requestRow = scopeGroup.addActionRow(Glyph.SHIELD, getString(R.string.action_request_core), requestCoreSubtitle(),
                getString(R.string.chip_request), false, true, this::requestCore);
        int resetRow = scopeGroup.addActionRow(Glyph.CLEAN, getString(R.string.row_reset_scope_title), resetCoreSubtitle(),
                getString(R.string.chip_reset), false, true, this::pruneScopes);
        scopeGroup.commit();
        addScopeCountText(scopeGroup.subtitleAt(requestRow));
        addScopeCountText(scopeGroup.subtitleAt(resetRow));

        addSectionHeader(getString(R.string.section_advanced));
        SegmentedGroup advancedGroup = new SegmentedGroup();
        advancedGroup.addSwitch(Glyph.LOG, R.string.sw_verbose_title, R.string.sw_verbose_sub, ModuleConfig.KEY_VERBOSE_LOG);
        advancedGroup.addActionRow(Glyph.LOG, getString(R.string.row_diag_log_title), getString(R.string.row_diag_log_sub),
                getString(R.string.chip_view), false, false, this::showDiagnosticLogsDialog);
        advancedGroup.addActionRow(Glyph.RESTORE, getString(R.string.row_restore_title), getString(R.string.row_restore_sub),
                getString(R.string.chip_restore), false, false, this::confirmRestoreSystemDefaults);
        advancedGroup.addActionRow(Glyph.TERMINAL, getString(R.string.row_adb_title), getString(R.string.row_adb_sub),
                getString(R.string.chip_view), false, false, () -> showRecoveryDialog(false));
        advancedGroup.commit();

        addSectionHeader(getString(R.string.section_about));
        SegmentedGroup aboutGroup = new SegmentedGroup();
        aboutGroup.addInfoRow(Glyph.SHIELD, getString(R.string.about_target_title), getString(R.string.about_target_sub));
        aboutGroup.addInfoRow(Glyph.INFO, getString(R.string.about_version_title),
                getString(R.string.about_version_sub, versionName(), BuildConfig.VERSION_CODE));
        aboutGroup.addActionRow(Glyph.LINK, getString(R.string.about_github_title), GITHUB_URL,
                getString(R.string.chip_open), false, false, this::openGitHub);
        aboutGroup.addInfoRow(Glyph.LICENSE, getString(R.string.about_license_title), getString(R.string.about_license_sub));
        aboutGroup.commit();
    }

    private record DeepCompatStats(int installedCount, int enabledCount) {}

    private DeepCompatStats queryDeepCompatStats(Collection<String> scope) {
        PackageManager pm = getPackageManager();
        Set<String> scopeSet = scope == null ? Set.of() : new HashSet<>(scope);
        int installed = 0;
        int enabled = 0;
        for (String pkg : DEEP_COMPAT_ORDER) {
            try {
                pm.getApplicationInfo(pkg, 0);
                installed++;
                if (scopeSet.contains(pkg)) enabled++;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return new DeepCompatStats(installed, enabled);
    }

    private String deepCompatStatusSummary(Collection<String> scope) {
        DeepCompatStats stats = queryDeepCompatStats(scope);
        if (stats.installedCount() == 0) {
            return getString(R.string.deep_compat_status_none);
        }
        if (scope == null) {
            return getString(R.string.deep_compat_status_disconnected, stats.installedCount());
        }
        return getString(R.string.deep_compat_status_connected, stats.installedCount(), stats.enabledCount());
    }

    private void buildDeepCompatGuideCard() {
        boolean compact = isCompactCoverUi();
        List<String> scope = service == null ? null : scopeSafe();

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int padH = compact ? dp(14) : dp(16);
        card.setPadding(padH, dp(15), padH, dp(15));
        int cardFill = M3Palette.blend(palette.surfaceContainer, palette.secondaryContainer, dark ? 0.34f : 0.44f);
        card.setBackground(ripple(cardFill, palette.rippleColor, 24f, 24f, 24f, 24f, dp(1), palette.outlineVariant));
        card.setOnClickListener(v -> {
            if (service != null && CoverDisplay.isSupportedFirmware()) {
                showAppScopePickerDialog(FILTER_DEEP_COMPAT);
            }
        });
        card.setFocusable(false);

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout badge = new FrameLayout(this);
        badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        badge.setBackground(rounded(palette.primaryContainer, 14f));
        badge.addView(
                new GlyphView(this, Glyph.SPARKLE, palette.onPrimaryContainer, false),
                new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(dp(40), dp(40));
        badgeLp.rightMargin = dp(12);
        headerRow.addView(badge, badgeLp);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = createText(getString(R.string.deep_compat_card_title), 16f, palette.onSurface, FONT_MEDIUM);
        titleView.setAccessibilityHeading(true);
        titleCol.addView(titleView);

        TextView statusView = createText(deepCompatStatusSummary(scope), 12f, palette.primary, FONT_MEDIUM);
        statusView.setLineSpacing(dp(2f), 1f);
        statusView.setPadding(0, dp(3), 0, 0);
        titleCol.addView(statusView);
        deepCompatStatusTexts.add(statusView);

        headerRow.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(headerRow);

        TextView bodyView = createText(getString(R.string.deep_compat_card_body), 12.5f, palette.onSurfaceVariant, FONT_REGULAR);
        bodyView.setLineSpacing(dp(3f), 1f);
        bodyView.setPadding(0, dp(10), 0, 0);
        card.addView(bodyView);

        LinearLayout ctaRow = new LinearLayout(this);
        ctaRow.setOrientation(LinearLayout.HORIZONTAL);
        ctaRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        ctaRow.setPadding(0, dp(12), 0, 0);
        View ctaBtn = createPillButton(
                getString(R.string.deep_compat_card_action),
                Glyph.SPARKLE,
                palette.primary,
                palette.onPrimary,
                () -> showAppScopePickerDialog(FILTER_DEEP_COMPAT));
        ctaBtn.setMinimumHeight(dp(44));
        LinearLayout.LayoutParams ctaLp = compact
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
        ctaRow.addView(ctaBtn, ctaLp);
        card.addView(ctaRow);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(8);
        pageContainer.addView(card, cardLp);
    }

    private void addScopeCountText(TextView t) {
        if (t != null) scopeCountTexts.add(t);
    }

    private void refreshScopeTexts() {
        if (scopeCountTexts.size() == 2) {
            scopeCountTexts.get(0).setText(requestCoreSubtitle());
            scopeCountTexts.get(1).setText(resetCoreSubtitle());
        }
        if (!deepCompatStatusTexts.isEmpty()) {
            List<String> scope = service == null ? null : scopeSafe();
            String status = deepCompatStatusSummary(scope);
            for (TextView tv : deepCompatStatusTexts) {
                if (tv != null) tv.setText(status);
            }
        }
    }

    private String requestCoreSubtitle() {
        LinkedHashSet<String> req = required();
        return getString(R.string.row_request_core_sub, req.size(), scopeLabels(req));
    }

    private String resetCoreSubtitle() {
        return getString(R.string.row_reset_scope_sub, required().size());
    }

    // ==================== M3 Expressive Components ====================

    private void addSectionHeader(String title) {
        TextView t = createText(title, 13f, palette.primary, FONT_MEDIUM);
        t.setPadding(dp(12), dp(14), dp(12), dp(8));
        t.setAccessibilityHeading(true);
        pageContainer.addView(t);
    }

    private View createPillButton(String label, int glyph, int bgColor, int fgColor, Runnable onClick) {
        LinearLayout btn = new LinearLayout(this);
        btn.setOrientation(LinearLayout.HORIZONTAL);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(14), 0, dp(16), 0);
        btn.setBackground(pillRipple(bgColor, palette.rippleColor, 24f));
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setContentDescription(label);
        btn.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Button");
            }
        });

        GlyphView icon = new GlyphView(this, glyph, fgColor, true);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(18), dp(18));
        iconLp.rightMargin = dp(8);
        btn.addView(icon, iconLp);

        TextView text = createText(label, 13.5f, fgColor, FONT_MEDIUM);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        btn.addView(text);

        btn.setOnClickListener(v -> onClick.run());
        actionButtons.add(btn);
        return btn;
    }

    private String stateText(boolean checked) {
        return getString(checked ? R.string.a11y_on : R.string.a11y_off);
    }

    /**
     * Makes {@code row} the single accessibility-focusable element of a "label + switch" row: it is announced
     * as a Switch with the switch's checked state, while the inner switch is hidden from accessibility and
     * does not take focus or clicks itself.
     */
    private void bindSwitchRow(View row, M3Switch sw) {
        sw.setFocusable(false);
        sw.setClickable(false);
        sw.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        sw.hostRow = row;
        row.setStateDescription(stateText(sw.isChecked()));
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Switch");
                info.setCheckable(true);
                info.setChecked(sw.isChecked());
            }
        });
    }

    /**
     * Builds an Android 16 / Material 3 Expressive segmented card list where the top item has
     * 24dp top corners, middle items have 6dp corners, and the bottom item has 24dp bottom corners.
     */
    private final class SegmentedGroup {
        private record ItemSpec(
                int kind, // 0=switch, 1=action, 2=info
                int glyph,
                String title,
                String subtitle,
                String key,
                String actionLabel,
                boolean primaryAction,
                boolean needsService,
                Runnable callback
        ) {}

        private final List<ItemSpec> items = new ArrayList<>();
        private final List<TextView> subtitles = new ArrayList<>();

        void addSwitch(int glyph, int title, int subtitle, String key) {
            items.add(new ItemSpec(0, glyph, getString(title), getString(subtitle), key, null, false, true, null));
        }

        int addActionRow(int glyph, String title, String subtitle, String actionLabel, boolean primaryAction,
                         boolean needsService, Runnable callback) {
            items.add(new ItemSpec(1, glyph, title, subtitle, null, actionLabel, primaryAction, needsService, callback));
            return items.size() - 1;
        }

        void addInfoRow(int glyph, String title, String subtitle) {
            items.add(new ItemSpec(2, glyph, title, subtitle, null, null, false, false, null));
        }

        TextView subtitleAt(int index) {
            return index >= 0 && index < subtitles.size() ? subtitles.get(index) : null;
        }

        void commit() {
            int count = items.size();
            for (int i = 0; i < count; i++) {
                ItemSpec spec = items.get(i);
                float rTop = (i == 0) ? 24f : 6f;
                float rBottom = (i == count - 1) ? 24f : 6f;

                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(16), dp(15), dp(16), dp(15));
                row.setMinimumHeight(dp(56));
                row.setBackground(ripple(palette.surfaceContainer, palette.rippleColor, rTop, rTop, rBottom, rBottom, 0, 0));

                // Leading tonal icon badge (40dp x 40dp, 14dp radius)
                FrameLayout badge = new FrameLayout(MainActivity.this);
                badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                int badgeBg = spec.primaryAction ? palette.primaryContainer : palette.secondaryContainer;
                int badgeFg = spec.primaryAction ? palette.onPrimaryContainer : palette.onSecondaryContainer;
                badge.setBackground(rounded(badgeBg, 14f));
                GlyphView glyphView = new GlyphView(MainActivity.this, spec.glyph, badgeFg, false);
                badge.addView(glyphView, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
                LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(dp(40), dp(40));
                badgeLp.rightMargin = dp(14);
                row.addView(badge, badgeLp);

                // Title & subtitle column
                LinearLayout labels = new LinearLayout(MainActivity.this);
                labels.setOrientation(LinearLayout.VERTICAL);
                labels.setPadding(0, 0, dp(12), 0);
                labels.addView(createText(spec.title, 16f, palette.onSurface, FONT_MEDIUM));
                TextView sub = null;
                if (spec.subtitle != null && !spec.subtitle.isEmpty()) {
                    sub = createText(spec.subtitle, 12.5f, palette.onSurfaceVariant, FONT_REGULAR);
                    sub.setLineSpacing(dp(2.5f), 1f);
                    sub.setPadding(0, dp(4), 0, 0);
                    labels.addView(sub);
                }
                subtitles.add(sub);
                row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                if (spec.kind == 0) {
                    M3Switch toggle = new M3Switch(MainActivity.this, value(spec.key));
                    toggle.setContentDescription(spec.title);
                    row.addView(toggle, new LinearLayout.LayoutParams(dp(52), dp(32)));
                    switches.add(toggle);
                    switchesByKey.computeIfAbsent(spec.key, k -> new ArrayList<>()).add(toggle);

                    toggle.setOnCheckedChangeListener(checked -> {
                        if (loading || prefs == null) return;
                        applySwitchChange(spec.key, checked);
                    });
                    row.setClickable(true);
                    row.setFocusable(true);
                    row.setOnClickListener(v -> {
                        if (toggle.isSwitchEnabled()) toggle.toggleChecked();
                    });
                    bindSwitchRow(row, toggle);
                } else if (spec.kind == 1) {
                    TextView chip = createText(
                            spec.actionLabel,
                            13f,
                            spec.primaryAction ? palette.onPrimary : palette.onSecondaryContainer,
                            FONT_MEDIUM);
                    chip.setGravity(Gravity.CENTER);
                    chip.setPadding(dp(14), dp(8), dp(14), dp(8));
                    chip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    chip.setBackground(rounded(
                            spec.primaryAction ? palette.primary : palette.secondaryContainer,
                            18f));
                    row.addView(chip);
                    row.setClickable(true);
                    row.setFocusable(true);
                    row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                        @Override
                        public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                            super.onInitializeAccessibilityNodeInfo(host, info);
                            info.setClassName("android.widget.Button");
                        }
                    });
                    row.setOnClickListener(v -> {
                        if ((!spec.needsService || (service != null && CoverDisplay.isSupportedFirmware()))
                                && spec.callback != null) {
                            spec.callback.run();
                        }
                    });
                    if (spec.needsService) actionButtons.add(row);
                }

                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rowLp.bottomMargin = (i == count - 1) ? dp(10) : dp(2);
                pageContainer.addView(row, rowLp);
            }
        }
    }

    private AlertDialog.Builder dialogBuilder() {
        return new AlertDialog.Builder(this, dark
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
    }

    // ==================== Scope management ====================

    private String scopeLabel(String pkg) {
        return switch (pkg) {
            case "system" -> getString(R.string.scope_system);
            case "com.android.systemui" -> getString(R.string.scope_systemui);
            case "com.miui.fliphome" -> getString(R.string.scope_fliphome);
            case VOICEASSIST_PKG -> getString(R.string.scope_voiceassist);
            case WETYPE_PKG -> getString(R.string.scope_wetype);
            default -> pkg;
        };
    }

    private String scopeLabels(Collection<String> pkgs) {
        List<String> labels = new ArrayList<>();
        for (String pkg : pkgs) labels.add(scopeLabel(pkg));
        return TextUtils.join(getString(R.string.list_separator), labels);
    }

    private LinkedHashSet<String> required() {
        LinkedHashSet<String> pkgs = new LinkedHashSet<>(CORE_SCOPE);
        try {
            getPackageManager().getPackageInfo(VOICEASSIST_PKG, 0);
            pkgs.add(VOICEASSIST_PKG);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        if (value(ModuleConfig.KEY_THIRD_PARTY_IME)) {
            try {
                getPackageManager().getPackageInfo(WETYPE_PKG, 0);
                pkgs.add(WETYPE_PKG);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return pkgs;
    }

    private void requestCore() {
        requestPackages(required());
    }

    private void enableUniversalZeroBlank() {
        if (prefs == null || service == null || !CoverDisplay.isSupportedFirmware()) return;
        try {
            prefs.edit()
                    .putBoolean(ModuleConfig.KEY_IMMERSIVE, true)
                    .putBoolean(ModuleConfig.KEY_UNIVERSAL_COMPAT, true)
                    .apply();
        } catch (RuntimeException e) {
            show(getString(R.string.msg_save_failed, String.valueOf(e.getMessage())));
            return;
        }
        List<String> scope = scopeSafe();
        if (scope != null) {
            PackageManager pm = getPackageManager();
            List<String> packedInScope = new ArrayList<>();
            for (String pkg : scope) {
                if (CORE_SCOPE.contains(pkg) || WETYPE_PKG.equals(pkg)
                        || VOICEASSIST_PKG.equals(pkg) || isDeepCompatApp(pkg)) {
                    continue;
                }
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    if (AppCompatibility.isPackedOrProtectedApp(ai)) {
                        packedInScope.add(pkg);
                    }
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
            if (!packedInScope.isEmpty()) {
                removeScopeSafe(packedInScope, R.string.msg_remove_failed);
            }
            LinkedHashSet<String> missing = required();
            missing.removeAll(scope);
            if (!missing.isEmpty()) {
                requestPackages(missing);
                render(false);
                return;
            }
        }
        show(getString(R.string.msg_zero_blank_done));
        render(false);
    }

    private static final class ScopeCallback implements XposedService.OnScopeEventListener {
        @Override
        public void onScopeRequestApproved(List<String> approved) {
            int n = approved == null ? 0 : approved.size();
            ModLog.i(TAG, "scope approved; count=" + n);
            dispatchToActivity(a -> {
                a.show(a.getString(R.string.msg_scope_approved, n));
                a.render(false);
            });
        }

        @Override
        public void onScopeRequestFailed(String reason) {
            Log.w(TAG, "scope request failed: " + reason);
            dispatchToActivity(a -> a.show(a.getString(R.string.msg_scope_failed, String.valueOf(reason))));
        }
    }

    private void requestPackages(Collection<String> packages) {
        if (!CoverDisplay.isSupportedFirmware()) return;
        List<String> scope = scopeSafe();
        if (scope == null) return;
        LinkedHashSet<String> missing = new LinkedHashSet<>(packages);
        missing.removeAll(scope);
        if (missing.isEmpty()) {
            show(getString(R.string.msg_scopes_complete));
            return;
        }
        try {
            Boolean ok = callService("requestScope", s -> {
                s.requestScope(new ArrayList<>(missing), new ScopeCallback());
                return Boolean.TRUE;
            }, false);
            if (Boolean.TRUE.equals(ok)) show(getString(R.string.msg_scope_request, missing.size()));
        } catch (RuntimeException e) {
            show(getString(R.string.msg_request_failed, String.valueOf(e.getMessage())));
        }
    }

    /** Returns true on success; failures are shown to the user. */
    private boolean removeScopeSafe(List<String> packages, int failureMessage) {
        try {
            Boolean ok = callService("removeScope", s -> {
                s.removeScope(packages);
                return Boolean.TRUE;
            }, false);
            return Boolean.TRUE.equals(ok);
        } catch (RuntimeException e) {
            show(getString(failureMessage, String.valueOf(e.getMessage())));
            return false;
        }
    }

    private void pruneScopes() {
        if (!CoverDisplay.isSupportedFirmware()) return;
        List<String> scope = scopeSafe();
        if (scope == null) return;
        LinkedHashSet<String> req = required();
        List<String> extra = new ArrayList<>(scope);
        extra.removeAll(req);
        if (extra.isEmpty()) {
            show(getString(R.string.msg_reset_nothing));
            return;
        }
        AlertDialog resetDialog = trackDialog(dialogBuilder()
                .setTitle(R.string.dialog_reset_title)
                .setMessage(getString(R.string.dialog_reset_message, extra.size(), scopeLabels(req)))
                .setNegativeButton(R.string.btn_cancel, null)
                .setPositiveButton(R.string.chip_reset, (d, w) -> {
                    if (removeScopeSafe(extra, R.string.msg_prune_failed)) {
                        show(getString(R.string.msg_pruned, extra.size()));
                    }
                    render(false);
                })
                .show());
        configureCoverSafeDialogWindow(resetDialog, false, false);
    }

    // ==================== Restore / recovery / about ====================

    private void confirmRestoreSystemDefaults() {
        showRecoveryDialog(true);
    }

    private void showRecoveryDialog(boolean withRestoreAction) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(8), dp(24), dp(4));

        TextView message = createText(
                getString(withRestoreAction ? R.string.dialog_restore_message : R.string.dialog_adb_message),
                14f, palette.onSurfaceVariant, FONT_REGULAR);
        message.setLineSpacing(dp(3), 1f);
        content.addView(message);

        for (String cmd : ADB_RESTORE_COMMANDS) {
            TextView cmdView = createText(cmd, 12f, palette.onSurface, FONT_MONO);
            cmdView.setPadding(dp(12), dp(10), dp(12), dp(10));
            cmdView.setBackground(ripple(palette.surfaceContainerHigh, palette.rippleColor, 12f, 12f, 12f, 12f, 0, 0));
            cmdView.setMinimumHeight(dp(48));
            cmdView.setLongClickable(true);
            cmdView.setOnLongClickListener(v -> {
                copyToClipboard(cmd);
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            content.addView(cmdView, lp);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);

        AlertDialog.Builder b = dialogBuilder()
                .setTitle(withRestoreAction ? R.string.dialog_restore_title : R.string.dialog_adb_title)
                .setView(scroll)
                .setNeutralButton(R.string.btn_copy_all, (d, w) -> copyToClipboard(String.join("\n", ADB_RESTORE_COMMANDS)));
        if (withRestoreAction) {
            b.setNegativeButton(R.string.btn_cancel, null)
                    .setPositiveButton(R.string.btn_restore, (d, w) -> restoreSystemDefaults());
        } else {
            b.setPositiveButton(R.string.btn_close, null);
        }
        AlertDialog recoveryDialog = trackDialog(b.show());
        configureCoverSafeDialogWindow(recoveryDialog, false, false);
    }

    private void copyToClipboard(String text) {
        try {
            ClipboardManager cm = getSystemService(ClipboardManager.class);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("adb", text));
        } catch (RuntimeException e) {
            Log.w(TAG, "clipboard failed", e);
        }
    }

    private void restoreSystemDefaults() {
        if (prefs != null && service != null) {
            try {
                prefs.edit()
                        .putBoolean(ModuleConfig.KEY_IMMERSIVE, false)
                        .putBoolean(ModuleConfig.KEY_UNIVERSAL_COMPAT, false)
                        .putBoolean(ModuleConfig.KEY_THIRD_PARTY_IME, false)
                        .commit();
                syncSwitches(ModuleConfig.KEY_IMMERSIVE, false);
                syncSwitches(ModuleConfig.KEY_UNIVERSAL_COMPAT, false);
                syncSwitches(ModuleConfig.KEY_THIRD_PARTY_IME, false);
                refreshScopeTexts();
                if (coverPreview != null) coverPreview.invalidate();
            } catch (RuntimeException e) {
                Log.w(TAG, "failed to commit default preferences before restore", e);
            }
        }
        try {
            Intent intent = new Intent(FrameworkRuntime.ACTION_RESTORE).setPackage("android");
            BroadcastOptions options = BroadcastOptions.makeBasic();
            options.setShareIdentityEnabled(true);
            sendBroadcast(intent, null, options.toBundle());
            show(getString(R.string.msg_restore_sent));
        } catch (RuntimeException e) {
            Log.w(TAG, "restore broadcast failed", e);
            show(getString(R.string.msg_restore_failed, String.valueOf(e.getMessage())));
        }
    }

    private void openGitHub() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException | SecurityException e) {
            show(getString(R.string.msg_open_failed));
        }
    }

    // ==================== In-App Diagnostic & Runtime Log Viewer ====================

    private String logSourceLabel(String source) {
        if (source == null) return "";
        return switch (source) {
            case "system_server", "android", "system" -> getString(R.string.scope_system);
            case CoverGeometry.SYSTEMUI -> getString(R.string.scope_systemui);
            case CoverGeometry.FLIPHOME -> getString(R.string.scope_fliphome);
            case VOICEASSIST_PKG -> getString(R.string.scope_voiceassist);
            case "settings" -> getString(R.string.app_name);
            default -> source;
        };
    }

    private void showDiagnosticLogsDialog() {
        Dialog dialog = trackDialog(new Dialog(this));
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(18), dp(16), dp(18), dp(16));
        sheet.setBackground(rounded(palette.surfaceContainerLow, 28f));

        View handle = new View(this);
        handle.setBackground(rounded(palette.outlineVariant, 2f));
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(dp(36), dp(4));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = dp(12);
        sheet.addView(handle, handleLp);

        TextView titleView = createText(getString(R.string.dialog_diag_log_title), 20f, palette.onSurface, FONT_BOLD);
        sheet.addView(titleView);

        TextView subtitleView = createText(
                getString(R.string.dialog_diag_log_sub, getString(R.string.app_name), 0),
                12.5f, palette.onSurfaceVariant, FONT_REGULAR);
        subtitleView.setPadding(0, dp(4), 0, dp(10));
        sheet.addView(subtitleView);

        // Top action bar arranged as a clean 2x2 grid so buttons never truncate on the cover screen
        LinearLayout topActions = new LinearLayout(this);
        topActions.setOrientation(LinearLayout.VERTICAL);
        topActions.setPadding(0, 0, 0, dp(10));

        TextView btnRefresh = createDialogChipButton(getString(R.string.btn_refresh_log), false);
        TextView btnClear = createDialogChipButton(getString(R.string.btn_clear_log), false);
        TextView btnCopy = createDialogChipButton(getString(R.string.btn_copy_all), false);
        TextView btnShare = createDialogChipButton(getString(R.string.btn_share_log), true);

        LinearLayout actionRow1 = new LinearLayout(this);
        actionRow1.setOrientation(LinearLayout.HORIZONTAL);
        actionRow1.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams row1LeftLp = new LinearLayout.LayoutParams(0, dp(38), 1f);
        row1LeftLp.rightMargin = dp(8);
        actionRow1.addView(btnRefresh, row1LeftLp);
        actionRow1.addView(btnClear, new LinearLayout.LayoutParams(0, dp(38), 1f));
        LinearLayout.LayoutParams row1Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row1Lp.bottomMargin = dp(6);
        topActions.addView(actionRow1, row1Lp);

        LinearLayout actionRow2 = new LinearLayout(this);
        actionRow2.setOrientation(LinearLayout.HORIZONTAL);
        actionRow2.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams row2LeftLp = new LinearLayout.LayoutParams(0, dp(38), 1f);
        row2LeftLp.rightMargin = dp(8);
        actionRow2.addView(btnCopy, row2LeftLp);
        actionRow2.addView(btnShare, new LinearLayout.LayoutParams(0, dp(38), 1f));
        topActions.addView(actionRow2, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sheet.addView(topActions);

        // Monospace scrollable log box
        ScrollView logScroll = new ScrollView(this);
        logScroll.setFillViewport(true);
        logScroll.setBackground(rounded(palette.surfaceContainerHigh, 16f));
        logScroll.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView logTextView = createText("", 11.5f, palette.onSurface, FONT_MONO);
        logTextView.setLineSpacing(dp(2.5f), 1f);
        logTextView.setTextIsSelectable(true);
        logScroll.addView(logTextView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sheet.addView(logScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Bottom close button
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.END);
        footer.setPadding(0, dp(12), 0, 0);

        TextView closeBtn = createText(getString(R.string.btn_close), 14f, palette.onSecondaryContainer, FONT_MEDIUM);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setPadding(dp(22), 0, dp(22), 0);
        closeBtn.setBackground(pillRipple(palette.secondaryContainer, palette.rippleColor, 22f));
        closeBtn.setOnClickListener(v -> dialog.dismiss());
        footer.addView(closeBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)));
        sheet.addView(footer);

        final Map<String, List<String>> processBuffers = new LinkedHashMap<>();
        final LinkedHashSet<String> respondedSources = new LinkedHashSet<>();
        final List<String> logcatLines = new ArrayList<>();
        final String[] currentFullText = {""};
        final int[] collectGen = {0};

        Runnable rebuildView = () -> {
            if (isFinishing() || isDestroyed() || !dialog.isShowing()) return;
            LinkedHashSet<String> mergedLogs = new LinkedHashSet<>();
            for (Map.Entry<String, List<String>> e : processBuffers.entrySet()) {
                List<String> lines = e.getValue();
                if (lines != null) mergedLogs.addAll(lines);
            }
            mergedLogs.addAll(logcatLines);

            List<String> sourceLabels = new ArrayList<>();
            LinkedHashSet<String> sourcesToFormat = respondedSources.isEmpty()
                    ? new LinkedHashSet<>(List.of("settings"))
                    : respondedSources;
            for (String src : sourcesToFormat) {
                String lbl = logSourceLabel(src);
                if (!lbl.isEmpty() && !sourceLabels.contains(lbl)) {
                    sourceLabels.add(lbl);
                }
            }
            String sourcesSummary = TextUtils.join(getString(R.string.list_separator), sourceLabels);
            int count = mergedLogs.size();
            subtitleView.setText(getString(R.string.dialog_diag_log_sub, sourcesSummary, count));

            StringBuilder sb = new StringBuilder();
            sb.append(buildDiagnosticHeader());
            sb.append("RespondedProcesses: ").append(sourcesToFormat).append('\n');
            sb.append("\n=== Runtime Logs (").append(count).append(") ===\n");
            if (mergedLogs.isEmpty()) {
                sb.append(getString(R.string.dialog_diag_log_empty)).append('\n');
            } else {
                for (String line : mergedLogs) {
                    sb.append(line).append('\n');
                }
            }
            currentFullText[0] = sb.toString();
            logTextView.setText(currentFullText[0]);
        };

        Runnable triggerCollect = () -> {
            final int gen = ++collectGen[0];
            processBuffers.clear();
            respondedSources.clear();
            logcatLines.clear();
            respondedSources.add("settings");
            processBuffers.put("settings", ModLog.snapshot());
            rebuildView.run();

            IO.execute(() -> {
                ArrayList<String> localLogcat = ModLog.readFilteredLogcat(200);
                mainHandler.post(() -> {
                    if (gen != collectGen[0] || isFinishing() || isDestroyed() || !dialog.isShowing()) return;
                    logcatLines.addAll(localLogcat);
                    rebuildView.run();
                });
            });

            try {
                ResultReceiver localReceiver = new ResultReceiver(mainHandler) {
                    @Override
                    protected void onReceiveResult(int resultCode, Bundle resultData) {
                        if (gen != collectGen[0] || resultData == null
                                || isFinishing() || isDestroyed() || !dialog.isShowing()) {
                            return;
                        }
                        String proc = resultData.getString(ModLog.EXTRA_PROCESS_LABEL, "remote");
                        respondedSources.add(proc);
                        ArrayList<String> lines = resultData.getStringArrayList(ModLog.EXTRA_LOG_LINES);
                        ArrayList<String> remoteLogcat = resultData.getStringArrayList(ModLog.EXTRA_LOGCAT_LINES);
                        if (lines != null && !lines.isEmpty()) {
                            processBuffers.put(proc, lines);
                        }
                        if (remoteLogcat != null && !remoteLogcat.isEmpty()) {
                            logcatLines.addAll(remoteLogcat);
                        }
                        rebuildView.run();
                    }
                };
                Parcel parcel = Parcel.obtain();
                ResultReceiver ipcReceiver;
                try {
                    localReceiver.writeToParcel(parcel, 0);
                    parcel.setDataPosition(0);
                    ipcReceiver = ResultReceiver.CREATOR.createFromParcel(parcel);
                } finally {
                    parcel.recycle();
                }
                BroadcastOptions opts = BroadcastOptions.makeBasic();
                opts.setShareIdentityEnabled(true);
                for (String targetPkg : LOG_TARGET_PACKAGES) {
                    Intent collect = new Intent(ModLog.ACTION_COLLECT_LOGS)
                            .setPackage(targetPkg)
                            .putExtra(ModLog.EXTRA_RESULT_RECEIVER, ipcReceiver);
                    sendBroadcast(collect, null, opts.toBundle());
                }
            } catch (Throwable t) {
                Log.w(TAG, "cross-process log collect broadcast failed", t);
            }
        };

        btnRefresh.setOnClickListener(v -> triggerCollect.run());
        btnClear.setOnClickListener(v -> {
            ++collectGen[0];
            ModLog.clear();
            processBuffers.clear();
            respondedSources.clear();
            respondedSources.add("settings");
            logcatLines.clear();
            try {
                BroadcastOptions opts = BroadcastOptions.makeBasic();
                opts.setShareIdentityEnabled(true);
                for (String targetPkg : LOG_TARGET_PACKAGES) {
                    Intent clearIntent = new Intent(ModLog.ACTION_COLLECT_LOGS)
                            .setPackage(targetPkg)
                            .putExtra(ModLog.EXTRA_CLEAR_LOGS, true);
                    sendBroadcast(clearIntent, null, opts.toBundle());
                }
            } catch (Throwable ignored) {
            }
            rebuildView.run();
            show(getString(R.string.msg_log_cleared));
        });
        btnCopy.setOnClickListener(v -> {
            copyToClipboard(currentFullText[0]);
            show(getString(R.string.msg_log_copied));
        });
        btnShare.setOnClickListener(v -> {
            try {
                Intent share = new Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "MixFlip2Cover Diagnostic Log")
                        .putExtra(Intent.EXTRA_TEXT, currentFullText[0]);
                startActivity(Intent.createChooser(share, getString(R.string.btn_share_log)));
            } catch (RuntimeException e) {
                copyToClipboard(currentFullText[0]);
                show(getString(R.string.msg_log_copied));
            }
        });

        dialog.setContentView(sheet);
        configureCoverSafeDialogWindow(dialog, true, true);
        dialog.show();
        configureCoverSafeDialogWindow(dialog, true, true);
        ModLog.i(TAG, "diagnostic log viewer opened");
        triggerCollect.run();
    }

    private TextView createDialogChipButton(String label, boolean primary) {
        TextView btn = createText(
                label,
                12.5f,
                primary ? palette.onPrimary : palette.onSecondaryContainer,
                FONT_MEDIUM);
        btn.setGravity(Gravity.CENTER);
        btn.setSingleLine(true);
        btn.setEllipsize(TextUtils.TruncateAt.END);
        btn.setPadding(dp(8), 0, dp(8), 0);
        btn.setBackground(pillRipple(
                primary ? palette.primary : palette.secondaryContainer,
                palette.rippleColor,
                18f));
        btn.setClickable(true);
        btn.setFocusable(true);
        return btn;
    }

    private String buildDiagnosticHeader() {
        List<String> scope = service == null ? null : scopeSafe();
        StringBuilder sb = new StringBuilder();
        sb.append("=== MixFlip2Cover Diagnostic Report ===\n");
        sb.append("Module: v").append(versionName()).append(" (").append(BuildConfig.VERSION_CODE).append(")\n");
        sb.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append(" / ").append(Build.PRODUCT).append(")\n");
        sb.append("System: ").append(CoverDisplay.systemVersionSummary())
                .append(" · Android ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Status: ").append(status().name())
                .append(" · fw_prop=").append(frameworkProp.isEmpty() ? "<empty>" : frameworkProp)
                .append(" (expected=").append(FrameworkRuntime.VERSION).append(")\n");
        sb.append("LSPosed: ").append(service != null ? "connected" : "disconnected")
                .append(" · scopes=").append(scope != null ? scope.toString() : "[]").append('\n');
        sb.append("Switches: ").append(currentState().toString()).append('\n');
        sb.append("PinnedApps: ").append(currentPinnedPackages().toString()).append('\n');
        return sb.toString();
    }

    // ==================== App Scope Manager Sheet / Dialog ====================

    private static final class AppEntry {
        final String label;
        final String labelLower;
        final String packageName;
        final boolean systemApp;
        final boolean inScope;
        final String compatSummary;
        final String compatSummaryLower;
        /** ResolveInfo or InputMethodInfo used to load the icon lazily (null falls back to ApplicationInfo icon). */
        final Object iconSource;
        boolean selected;

        AppEntry(String label, String packageName, boolean systemApp, boolean inScope, Object iconSource) {
            this(label, packageName, systemApp, inScope, "", iconSource);
        }

        AppEntry(String label, String packageName, boolean systemApp, boolean inScope,
                 String compatSummary, Object iconSource) {
            this.label = label;
            this.labelLower = label.toLowerCase(Locale.ROOT);
            this.packageName = packageName;
            this.systemApp = systemApp;
            this.inScope = inScope;
            this.compatSummary = compatSummary != null ? compatSummary : "";
            this.compatSummaryLower = this.compatSummary.toLowerCase(Locale.ROOT);
            this.iconSource = iconSource;
            this.selected = inScope;
        }
    }

    private static boolean isSystemApp(ApplicationInfo appInfo) {
        return appInfo != null
                && (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                && (appInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
    }

    private static String resolveCompatSummary(Context ctx, String pkg) {
        int res = compatSummaryRes(pkg);
        return res != 0 ? ctx.getString(res) : "";
    }

    /** Runs on a background thread: labels only, icons are loaded lazily while scrolling. */
    private static List<AppEntry> loadLaunchableApps(Context ctx, PackageManager pm, InputMethodManager imm, Set<String> currentScope) {
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = pm.queryIntentActivities(mainIntent, 0);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<AppEntry> entries = new ArrayList<>();
        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null) continue;
            String pkg = info.activityInfo.packageName;
            if (!AppCompatibility.supports(pkg)
                    || CORE_SCOPE.contains(pkg)
                    || VOICEASSIST_PKG.equals(pkg)
                    || !seen.add(pkg)) {
                continue;
            }
            boolean inScope = currentScope.contains(pkg);
            if (!inScope && !isDeepCompatApp(pkg)
                    && AppCompatibility.isPackedOrProtectedApp(info.activityInfo.applicationInfo)) {
                continue;
            }
            String label = String.valueOf(info.loadLabel(pm));
            entries.add(new AppEntry(label, pkg, isSystemApp(info.activityInfo.applicationInfo),
                    inScope, resolveCompatSummary(ctx, pkg), info));
        }
        // Ensure installed deep-compatibility apps (including system Settings/Calendar) are always listed
        // even if a ROM variant omits their CATEGORY_LAUNCHER activity alias.
        for (String pkg : DEEP_COMPAT_ORDER) {
            if (seen.contains(pkg) || !AppCompatibility.supports(pkg)
                    || CORE_SCOPE.contains(pkg) || VOICEASSIST_PKG.equals(pkg)) {
                continue;
            }
            try {
                ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
                boolean inScope = currentScope.contains(pkg);
                CharSequence rawLabel = pm.getApplicationLabel(appInfo);
                String label = (rawLabel != null && rawLabel.length() > 0) ? rawLabel.toString() : pkg;
                seen.add(pkg);
                entries.add(new AppEntry(label, pkg, isSystemApp(appInfo),
                        inScope, resolveCompatSummary(ctx, pkg), null));
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        try {
            if (imm != null) {
                for (InputMethodInfo imi : imm.getInputMethodList()) {
                    if (imi == null) continue;
                    String pkg = imi.getPackageName();
                    if (!AppCompatibility.supports(pkg)
                            || CORE_SCOPE.contains(pkg)
                            || VOICEASSIST_PKG.equals(pkg)
                            || pkg.startsWith(CoverImeGuard.SOGOU_PKG)
                            || !seen.add(pkg)) {
                        continue;
                    }
                    ApplicationInfo appInfo = imi.getServiceInfo() != null ? imi.getServiceInfo().applicationInfo : null;
                    boolean inScope = currentScope.contains(pkg);
                    if (!inScope && !isDeepCompatApp(pkg) && AppCompatibility.isPackedOrProtectedApp(appInfo)) continue;
                    String label = String.valueOf(imi.loadLabel(pm));
                    entries.add(new AppEntry(label, pkg, isSystemApp(appInfo),
                            inScope, resolveCompatSummary(ctx, pkg), imi));
                }
            }
        } catch (Throwable ignored) {
        }
        entries.sort(Comparator
                .comparing((AppEntry a) -> a.systemApp)
                .thenComparing(a -> !a.inScope)
                .thenComparing(a -> !isDeepCompatApp(a.packageName))
                .thenComparingInt(a -> deepCompatOrder(a.packageName))
                .thenComparing(a -> a.label, String.CASE_INSENSITIVE_ORDER));
        return entries;
    }

    private Drawable iconCopy(Drawable d) {
        Drawable.ConstantState cs = d.getConstantState();
        return cs != null ? cs.newDrawable(getResources()) : d;
    }

    private void bindIcon(AppEntry app, ImageView target) {
        target.setTag(app.packageName);
        Drawable cached = iconCache.get(app.packageName);
        if (cached != null) {
            target.setImageDrawable(iconCopy(cached));
            return;
        }
        target.setImageDrawable(null);
        if (!iconLoading.add(app.packageName)) return;
        PackageManager pm = getPackageManager();
        WeakReference<ImageView> targetRef = new WeakReference<>(target);
        IO.execute(() -> {
            Drawable d = null;
            try {
                if (app.iconSource instanceof ResolveInfo ri) d = ri.loadIcon(pm);
                else if (app.iconSource instanceof InputMethodInfo imi) d = imi.loadIcon(pm);
                else d = pm.getApplicationIcon(app.packageName);
            } catch (Throwable ignored) {
            }
            final Drawable icon = d;
            mainHandler.post(() -> {
                iconLoading.remove(app.packageName);
                if (icon == null || isDestroyed()) return;
                iconCache.put(app.packageName, icon);
                ImageView view = targetRef.get();
                if (view != null && app.packageName.equals(view.getTag())) view.setImageDrawable(iconCopy(icon));
            });
        });
    }

    private final class AppRowHolder {
        final LinearLayout row;
        final ImageView icon;
        final TextView name;
        final LinearLayout badgeRow;
        final TextView deepTag;
        final TextView tag;
        final TextView compatDesc;
        final TextView pkg;
        final M3Switch sw;
        int shape = -1;
        AppEntry entry;

        AppRowHolder() {
            boolean compact = isCompactCoverUi();
            row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int rowPadH = compact ? dp(8) : dp(14);
            int rowPadV = compact ? dp(8) : dp(10);
            row.setPadding(rowPadH, rowPadV, rowPadH, rowPadV);
            row.setMinimumHeight(compact ? dp(48) : dp(56));
            row.setLayoutParams(new AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            icon = new ImageView(MainActivity.this);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            int iconSize = compact ? dp(28) : dp(38);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
            iconLp.rightMargin = compact ? dp(8) : dp(12);
            row.addView(icon, iconLp);

            LinearLayout textCol = new LinearLayout(MainActivity.this);
            textCol.setOrientation(LinearLayout.VERTICAL);
            textCol.setPadding(0, 0, compact ? dp(6) : dp(10), 0);

            LinearLayout titleLine = new LinearLayout(MainActivity.this);
            titleLine.setOrientation(LinearLayout.HORIZONTAL);
            titleLine.setGravity(Gravity.CENTER_VERTICAL);
            name = createText("", compact ? 14f : 15f, palette.onSurface, FONT_MEDIUM);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            titleLine.addView(name, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            float badgeSp = compact ? 9.5f : 10f;
            int badgePadH = compact ? dp(4.5f) : dp(6);
            deepTag = createText(getString(R.string.tag_deep_compat), badgeSp, palette.onTertiaryContainer, FONT_MEDIUM);
            deepTag.setSingleLine(true);
            deepTag.setPadding(badgePadH, dp(1.5f), badgePadH, dp(1.5f));
            deepTag.setBackground(rounded(palette.tertiaryContainer, 8f));
            deepTag.setVisibility(View.GONE);

            tag = createText(getString(R.string.tag_enabled), badgeSp, palette.onPrimaryContainer, FONT_MEDIUM);
            tag.setSingleLine(true);
            tag.setPadding(badgePadH, dp(1.5f), badgePadH, dp(1.5f));
            tag.setBackground(rounded(palette.primaryContainer, 8f));
            tag.setVisibility(View.GONE);

            if (compact) {
                badgeRow = new LinearLayout(MainActivity.this);
                badgeRow.setOrientation(LinearLayout.HORIZONTAL);
                badgeRow.setGravity(Gravity.CENTER_VERTICAL);
                badgeRow.setPadding(0, dp(3), 0, 0);
                badgeRow.setVisibility(View.GONE);
                badgeRow.addView(deepTag, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                badgeRow.addView(tag, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                textCol.addView(titleLine);
                textCol.addView(badgeRow);
            } else {
                badgeRow = null;
                LinearLayout.LayoutParams deepLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                deepLp.leftMargin = dp(6);
                titleLine.addView(deepTag, deepLp);
                LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                tagLp.leftMargin = dp(6);
                titleLine.addView(tag, tagLp);
                textCol.addView(titleLine);
            }

            compatDesc = createText("", 11.5f, palette.primary, FONT_MEDIUM);
            compatDesc.setMaxLines(3);
            compatDesc.setEllipsize(TextUtils.TruncateAt.END);
            compatDesc.setLineSpacing(dp(1.5f), 1f);
            compatDesc.setPadding(0, dp(2.5f), 0, 0);
            compatDesc.setVisibility(View.GONE);
            textCol.addView(compatDesc);

            pkg = createText("", 11f, palette.onSurfaceVariant, FONT_REGULAR);
            pkg.setSingleLine(true);
            pkg.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            pkg.setPadding(0, dp(2), 0, 0);
            textCol.addView(pkg);
            row.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            sw = new M3Switch(MainActivity.this, false);
            row.addView(sw, new LinearLayout.LayoutParams(dp(52), dp(32)));
            bindSwitchRow(row, sw);
            row.setTag(this);
        }

        void syncBadgeRow() {
            if (badgeRow == null) return;
            boolean deepVis = deepTag.getVisibility() == View.VISIBLE;
            boolean tagVis = tag.getVisibility() == View.VISIBLE;
            if (tag.getLayoutParams() instanceof LinearLayout.LayoutParams lp) {
                int wantMargin = deepVis ? dp(4) : 0;
                if (lp.leftMargin != wantMargin) {
                    lp.leftMargin = wantMargin;
                    tag.setLayoutParams(lp);
                }
            }
            badgeRow.setVisibility((deepVis || tagVis) ? View.VISIBLE : View.GONE);
        }

        void bind(AppEntry app, int position, int count, boolean showCompatMeta) {
            entry = app;
            int type = count == 1 ? 0 : position == 0 ? 1 : position == count - 1 ? 3 : 2;
            if (type != shape) {
                shape = type;
                float rTop = (type == 0 || type == 1) ? 20f : 5f;
                float rBot = (type == 0 || type == 3) ? 20f : 5f;
                row.setBackground(ripple(palette.surfaceContainer, palette.rippleColor, rTop, rTop, rBot, rBot, 0, 0));
            }
            name.setText(app.label);
            if (showCompatMeta) {
                tag.setText(R.string.tag_enabled);
                tag.setVisibility(app.inScope ? View.VISIBLE : View.GONE);
                boolean deepCompat = isDeepCompatApp(app.packageName);
                if (deepCompat) {
                    deepTag.setText(R.string.tag_deep_compat);
                    deepTag.setTextColor(palette.onTertiaryContainer);
                    deepTag.setBackground(rounded(palette.tertiaryContainer, 8f));
                    deepTag.setVisibility(View.VISIBLE);
                } else if (WETYPE_PKG.equals(app.packageName)) {
                    deepTag.setText(R.string.tag_ime_compat);
                    deepTag.setTextColor(palette.onSecondaryContainer);
                    deepTag.setBackground(rounded(palette.secondaryContainer, 8f));
                    deepTag.setVisibility(View.VISIBLE);
                } else {
                    deepTag.setVisibility(View.GONE);
                }
                if (!app.compatSummary.isEmpty()) {
                    compatDesc.setText(app.compatSummary);
                    compatDesc.setTextColor(deepCompat ? palette.primary : palette.onSurfaceVariant);
                    compatDesc.setVisibility(View.VISIBLE);
                } else {
                    compatDesc.setVisibility(View.GONE);
                }
            } else {
                deepTag.setVisibility(View.GONE);
                tag.setVisibility(View.GONE);
                compatDesc.setVisibility(View.GONE);
            }
            syncBadgeRow();
            pkg.setText(app.packageName);
            sw.setContentDescription(app.label);
            sw.jumpTo(app.selected);
            bindIcon(app, icon);
        }
    }

    private final class AppListAdapter extends BaseAdapter {
        final List<AppEntry> items = new ArrayList<>();

        @Override public int getCount() { return items.size(); }
        @Override public AppEntry getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            AppRowHolder holder;
            if (convertView != null && convertView.getTag() instanceof AppRowHolder h) {
                holder = h;
            } else {
                holder = new AppRowHolder();
            }
            holder.bind(items.get(position), position, items.size(), true);
            return holder.row;
        }
    }

    private TextView createChip(String text, int color, boolean ultraCompactHeight) {
        TextView chip = createText(text, ultraCompactHeight ? 11.5f : 12.5f, color, FONT_MEDIUM);
        chip.setGravity(Gravity.CENTER);
        int padH = dp(ultraCompactHeight ? 10 : 12);
        chip.setPadding(padH, 0, padH, 0);
        chip.setMinHeight(dp(ultraCompactHeight ? 36 : 48));
        chip.setClickable(true);
        chip.setFocusable(true);
        return chip;
    }

    /** Visual chip is 32dp tall (28dp in ultra-compact landscape) while keeping full touch target via inset. */
    private Drawable chipBackground(boolean selectedChip, boolean ultraCompactHeight) {
        float r = ultraCompactHeight ? 14f : 16f;
        int vInset = dp(ultraCompactHeight ? 4 : 8);
        GradientDrawable shape = selectedChip
                ? rounded(palette.secondaryContainer, r)
                : createShape(Color.TRANSPARENT, r, r, r, r, dp(1), palette.outlineVariant);
        return new InsetDrawable(shape, 0, vInset, 0, vInset);
    }

    private void showAppScopePickerDialog() {
        showAppScopePickerDialog(FILTER_THIRD_PARTY);
    }

    private void showAppScopePickerDialog(int initialFilter) {
        if (!CoverDisplay.isSupportedFirmware()) return;
        List<String> scopeList = scopeSafe();
        if (scopeList == null) return;
        Set<String> currentScope = new HashSet<>(scopeList);
        int startFilter = (initialFilter >= FILTER_THIRD_PARTY && initialFilter <= FILTER_SYSTEM_APPS)
                ? initialFilter : FILTER_THIRD_PARTY;

        boolean compact = isCompactCoverUi();
        int safeHeight = currentSafeWindowFrame().safeRect().height();
        boolean compactHeight = safeHeight < dp(420);
        boolean ultraCompactHeight = safeHeight < dp(320);

        Dialog dialog = trackDialog(new Dialog(this));
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        int sheetPadH = compact ? dp(8) : dp(20);
        int sheetPadTop = ultraCompactHeight ? dp(6) : (compactHeight ? dp(10) : dp(16));
        int sheetPadBot = ultraCompactHeight ? dp(6) : (compactHeight ? dp(12) : dp(18));
        sheet.setPadding(sheetPadH, sheetPadTop, sheetPadH, sheetPadBot);
        sheet.setBackground(rounded(palette.surfaceContainerLow, ultraCompactHeight ? 22f : 28f));

        // Drag handle pill at top of sheet (omitted on short landscape cover heights)
        if (!compactHeight) {
            View handle = new View(this);
            handle.setBackground(rounded(palette.outlineVariant, 2f));
            LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(dp(36), dp(4));
            handleLp.gravity = Gravity.CENTER_HORIZONTAL;
            handleLp.bottomMargin = dp(14);
            sheet.addView(handle, handleLp);
        }

        // Header row
        float titleSp = ultraCompactHeight ? 15f : (compact ? 18f : 20f);
        TextView sheetTitle = createText(getString(R.string.picker_title), titleSp, palette.onSurface, FONT_BOLD);
        sheetTitle.setAccessibilityHeading(true);
        if (ultraCompactHeight) {
            sheetTitle.setSingleLine(true);
            sheetTitle.setEllipsize(TextUtils.TruncateAt.END);
        }
        sheet.addView(sheetTitle);
        TextView sheetSub = createText(getString(R.string.picker_sub),
                ultraCompactHeight ? 10.5f : 12f, palette.onSurfaceVariant, FONT_REGULAR);
        sheetSub.setLineSpacing(dp(ultraCompactHeight ? 1f : 2f), 1f);
        if (ultraCompactHeight) {
            sheetSub.setSingleLine(true);
            sheetSub.setEllipsize(TextUtils.TruncateAt.END);
            sheetSub.setPadding(0, dp(1), 0, dp(3));
        } else if (compactHeight) {
            sheetSub.setMaxLines(2);
            sheetSub.setEllipsize(TextUtils.TruncateAt.END);
            sheetSub.setPadding(0, dp(3), 0, dp(6));
        } else {
            sheetSub.setPadding(0, dp(3), 0, dp(10));
        }
        sheet.addView(sheetSub);

        // Search Bar (Material 3 Search Bar Pill)
        LinearLayout searchBox = new LinearLayout(this);
        searchBox.setOrientation(LinearLayout.HORIZONTAL);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        int searchPadV = ultraCompactHeight ? 0 : dp(2);
        searchBox.setPadding(dp(12), searchPadV, dp(12), searchPadV);
        searchBox.setBackground(rounded(palette.surfaceContainerHigh, ultraCompactHeight ? 18f : 24f));

        EditText searchInput = new EditText(this);
        searchInput.setHint(R.string.picker_search_hint);
        searchInput.setHintTextColor(palette.onSurfaceVariant);
        searchInput.setTextColor(palette.onSurface);
        searchInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, ultraCompactHeight ? 13f : 14f);
        searchInput.setSingleLine(true);
        searchInput.setBackground(null);
        int inputPadV = dp(ultraCompactHeight ? 4 : 6);
        searchInput.setPadding(dp(4), inputPadV, dp(4), inputPadV);
        searchInput.setMinHeight(dp(ultraCompactHeight ? 36 : (compactHeight ? 40 : 46)));
        searchBox.addView(searchInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchLp.bottomMargin = dp(ultraCompactHeight ? 2 : 4);
        sheet.addView(searchBox, searchLp);

        // Filter Chips Row (0=user, 1=deep fit [incl. system Calendar/Settings], 2=all, 3=in scope, 4=system)
        final int[] activeFilter = {startFilter};
        int[] filterNames = {
                R.string.filter_third,
                R.string.filter_deep_compat,
                R.string.filter_all,
                R.string.filter_scoped,
                R.string.filter_system
        };
        final List<TextView> chipViews = new ArrayList<>();
        final List<AppEntry> allApps = new ArrayList<>();
        final AppListAdapter adapter = new AppListAdapter();
        final Runnable[] refilterRef = new Runnable[1];

        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setGravity(Gravity.CENTER_VERTICAL);

        for (int i = 0; i < filterNames.length; i++) {
            final int filterIdx = i;
            TextView chip = createChip(getString(filterNames[i]), palette.onSurfaceVariant, ultraCompactHeight);
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            chipLp.rightMargin = dp(ultraCompactHeight ? 6 : 8);
            chip.setOnClickListener(v -> {
                activeFilter[0] = filterIdx;
                refilterRef[0].run();
            });
            chipViews.add(chip);
            chipRow.addView(chip, chipLp);
        }

        // Quick "select all" chip
        TextView selectAllChip = createChip(getString(R.string.picker_select_all), palette.primary, ultraCompactHeight);
        selectAllChip.setBackground(chipBackground(false, ultraCompactHeight));
        chipRow.addView(selectAllChip);
        chipScroll.addView(chipRow);

        LinearLayout.LayoutParams chipScrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipScrollLp.bottomMargin = dp(ultraCompactHeight ? 2 : 4);
        sheet.addView(chipScroll, chipScrollLp);

        if (startFilter > 0 && startFilter < chipViews.size()) {
            chipScroll.post(() -> {
                TextView selChip = chipViews.get(startFilter);
                chipScroll.scrollTo(Math.max(0, selChip.getLeft() - dp(12)), 0);
            });
        }

        // App list (recycled rows; weighted 0dp + 1f inside bounded window so bottomRow never clips)
        FrameLayout listFrame = new FrameLayout(this);
        ListView listView = new ListView(this);
        listView.setVerticalScrollBarEnabled(false);
        listView.setDivider(new ColorDrawable(Color.TRANSPARENT));
        listView.setDividerHeight(dp(2));
        listView.setSelector(new ColorDrawable(Color.TRANSPARENT));
        listView.setCacheColorHint(Color.TRANSPARENT);
        listView.setAdapter(adapter);
        TextView emptyView = createText(getString(R.string.picker_loading),
                ultraCompactHeight ? 12.5f : 14f, palette.onSurfaceVariant, FONT_REGULAR);
        emptyView.setGravity(Gravity.CENTER);
        listFrame.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        listFrame.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        listView.setEmptyView(emptyView);
        sheet.addView(listFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= adapter.items.size()) return;
            AppEntry app = adapter.items.get(position);
            app.selected = !app.selected;
            if (view.getTag() instanceof AppRowHolder h && h.entry == app) {
                h.sw.setChecked(app.selected, false);
            } else {
                adapter.notifyDataSetChanged();
            }
        });

        refilterRef[0] = () -> {
            sheetSub.setText(activeFilter[0] == FILTER_DEEP_COMPAT
                    ? R.string.picker_sub_deep : R.string.picker_sub);
            for (int i = 0; i < chipViews.size(); i++) {
                boolean sel = i == activeFilter[0];
                TextView c = chipViews.get(i);
                c.setTextColor(sel ? palette.onSecondaryContainer : palette.onSurfaceVariant);
                c.setBackground(chipBackground(sel, ultraCompactHeight));
                c.setSelected(sel);
            }
            String query = searchInput.getText() == null ? "" : searchInput.getText().toString().trim().toLowerCase(Locale.ROOT);
            adapter.items.clear();
            for (AppEntry app : allApps) {
                if (activeFilter[0] == FILTER_THIRD_PARTY && app.systemApp) continue;
                if (activeFilter[0] == FILTER_DEEP_COMPAT && !isDeepCompatApp(app.packageName)) continue;
                if (activeFilter[0] == FILTER_IN_SCOPE && !app.selected && !app.inScope) continue;
                if (activeFilter[0] == FILTER_SYSTEM_APPS && !app.systemApp) continue;
                if (!query.isEmpty()
                        && !app.labelLower.contains(query)
                        && !app.packageName.toLowerCase(Locale.ROOT).contains(query)
                        && !app.compatSummaryLower.contains(query)) {
                    continue;
                }
                adapter.items.add(app);
            }
            if (activeFilter[0] == FILTER_DEEP_COMPAT) {
                adapter.items.sort(Comparator
                        .comparing((AppEntry a) -> !a.inScope)
                        .thenComparingInt(a -> deepCompatOrder(a.packageName)));
            }
            adapter.notifyDataSetChanged();
        };
        refilterRef[0].run();

        selectAllChip.setOnClickListener(v -> {
            boolean allChecked = true;
            for (AppEntry e : adapter.items) {
                if (!e.selected) {
                    allChecked = false;
                    break;
                }
            }
            boolean target = !allChecked;
            for (AppEntry e : adapter.items) e.selected = target;
            adapter.notifyDataSetChanged();
        });

        // Debounced search: filtering only reuses rows, the full list is never rebuilt per keystroke.
        Runnable debouncedFilter = () -> refilterRef[0].run();
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                mainHandler.removeCallbacks(debouncedFilter);
                mainHandler.postDelayed(debouncedFilter, 150);
            }
        });

        // Dialog Bottom Action Buttons
        LinearLayout bottomRow = new LinearLayout(this);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        bottomRow.setPadding(0, dp(ultraCompactHeight ? 4 : (compactHeight ? 8 : 12)), 0, 0);

        float btnSp = compact ? 12.5f : 14f;
        int cancelPadH = compact ? dp(8) : dp(20);
        int savePadH = compact ? dp(10) : dp(22);
        int btnPadV = dp(ultraCompactHeight ? 6 : 10);
        int btnMinH = dp(ultraCompactHeight ? 36 : (compactHeight ? 42 : 48));

        TextView cancelBtn = createText(getString(R.string.btn_cancel), btnSp, palette.primary, FONT_MEDIUM);
        cancelBtn.setGravity(Gravity.CENTER);
        cancelBtn.setSingleLine(true);
        cancelBtn.setEllipsize(TextUtils.TruncateAt.END);
        cancelBtn.setPadding(cancelPadH, btnPadV, cancelPadH, btnPadV);
        cancelBtn.setMinHeight(btnMinH);
        cancelBtn.setBackground(pillRipple(Color.TRANSPARENT, palette.rippleColor, 24f));
        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        bottomRow.addView(cancelBtn);

        TextView saveBtn = createText(getString(R.string.btn_save_scope), btnSp, palette.onPrimary, FONT_MEDIUM);
        saveBtn.setGravity(Gravity.CENTER);
        saveBtn.setSingleLine(true);
        saveBtn.setEllipsize(TextUtils.TruncateAt.END);
        saveBtn.setPadding(savePadH, btnPadV, savePadH, btnPadV);
        saveBtn.setMinHeight(btnMinH);
        saveBtn.setBackground(pillRipple(palette.primary, palette.rippleColor, 24f));
        saveBtn.setEnabled(false);
        saveBtn.setAlpha(0.45f);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        saveLp.leftMargin = compact ? dp(4) : dp(8);
        saveBtn.setOnClickListener(v -> {
            dialog.dismiss();
            boolean weTypeUnchecked = false;
            for (AppEntry app : allApps) {
                if (!app.selected && app.inScope && WETYPE_PKG.equals(app.packageName)) {
                    weTypeUnchecked = true;
                    break;
                }
            }
            if (weTypeUnchecked && prefs != null) {
                try {
                    prefs.edit().putBoolean(ModuleConfig.KEY_THIRD_PARTY_IME, false).apply();
                    syncSwitches(ModuleConfig.KEY_THIRD_PARTY_IME, false);
                    refreshScopeTexts();
                } catch (RuntimeException ignored) {
                }
            }
            List<String> toAdd = new ArrayList<>();
            List<String> toRemove = new ArrayList<>();
            Set<String> coreReq = required();
            for (AppEntry app : allApps) {
                if (app.selected && !app.inScope) {
                    toAdd.add(app.packageName);
                } else if (!app.selected && app.inScope && !coreReq.contains(app.packageName)) {
                    toRemove.add(app.packageName);
                }
            }
            boolean removed = false;
            if (!toRemove.isEmpty()) {
                removed = removeScopeSafe(toRemove, R.string.msg_remove_failed);
            }
            if (!toAdd.isEmpty()) {
                requestPackages(toAdd);
                if (removed) render(false);
            } else if (removed) {
                show(getString(R.string.msg_removed, toRemove.size()));
                render(false);
            } else if (toRemove.isEmpty()) {
                show(getString(R.string.msg_unchanged));
            }
        });
        bottomRow.addView(saveBtn, saveLp);
        sheet.addView(bottomRow);

        dialog.setContentView(sheet, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dialog.setOnDismissListener(d -> mainHandler.removeCallbacks(debouncedFilter));
        configureCoverSafePickerWindow(dialog, false);
        dialog.show();
        configureCoverSafePickerWindow(dialog, false);

        // Load the app list off the main thread.
        Context appCtx = this;
        PackageManager pm = getPackageManager();
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        IO.execute(() -> {
            List<AppEntry> loaded;
            try {
                loaded = loadLaunchableApps(appCtx, pm, imm, currentScope);
            } catch (RuntimeException e) {
                Log.w(TAG, "failed to load apps", e);
                loaded = List.of();
            }
            final List<AppEntry> result = loaded;
            mainHandler.post(() -> {
                if (isDestroyed() || !dialog.isShowing()) return;
                if (result.isEmpty()) {
                    dialog.dismiss();
                    show(getString(R.string.msg_no_apps));
                    return;
                }
                allApps.addAll(result);
                emptyView.setText(R.string.picker_empty);
                saveBtn.setEnabled(true);
                saveBtn.setAlpha(1f);
                refilterRef[0].run();
            });
        });
    }

    // ==================== Pinned Widget Favorite Apps Picker (4 Slots) ====================

    private void broadcastPinnedWidgetSync(boolean enabled, String rawApps) {
        try {
            Intent intent = new Intent(PinnedAppsWidget.ACTION_PINNED_WIDGET_SYNC)
                    .setPackage(CoverGeometry.FLIPHOME)
                    .putExtra(ModuleConfig.KEY_PINNED_WIDGET_ENABLED, enabled)
                    .putExtra(ModuleConfig.KEY_PINNED_WIDGET_APPS, rawApps != null ? rawApps : "");
            BroadcastOptions options = BroadcastOptions.makeBasic();
            options.setShareIdentityEnabled(true);
            sendBroadcast(intent, null, options.toBundle());
        } catch (RuntimeException e) {
            Log.w(TAG, "pinned widget sync broadcast failed", e);
        }
    }

    private List<String> currentPinnedPackages() {
        SharedPreferences p = prefs;
        if (p == null) return List.of();
        try {
            return ModuleConfig.parsePinnedApps(p.getString(ModuleConfig.KEY_PINNED_WIDGET_APPS, ""));
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private void addPinnedSummaryText(TextView tv) {
        if (tv != null) pinnedSummaryTexts.add(tv);
    }

    private void refreshPinnedSummaryTexts() {
        String text = pinnedWidgetAppsSummary();
        for (TextView tv : pinnedSummaryTexts) {
            if (tv != null) tv.setText(text);
        }
    }

    private String pinnedWidgetAppsSummary() {
        List<String> pkgs = currentPinnedPackages();
        if (pkgs.isEmpty()) {
            return getString(R.string.row_pinned_widget_apps_empty);
        }
        PackageManager pm = getPackageManager();
        String sep = getString(R.string.list_separator);
        StringBuilder names = new StringBuilder();
        for (String pkg : pkgs) {
            String label = pkg;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                CharSequence cs = pm.getApplicationLabel(ai);
                if (cs != null && cs.length() > 0) label = cs.toString();
            } catch (Throwable ignored) {
            }
            if (names.length() > 0) names.append(sep);
            names.append(label);
        }
        return getString(R.string.row_pinned_widget_apps_summary, pkgs.size(), names.toString());
    }

    /** Loads all launchable launcher apps (except FlipHome itself) for the 4-slot widget icon picker. */
    private static List<AppEntry> loadLaunchableAppsForWidget(PackageManager pm, List<String> pinnedOrder) {
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = pm.queryIntentActivities(mainIntent, 0);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        Set<String> pinnedSet = new HashSet<>(pinnedOrder);
        List<AppEntry> entries = new ArrayList<>();
        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null) continue;
            String pkg = info.activityInfo.packageName;
            if (pkg == null || pkg.isEmpty() || "com.miui.fliphome".equals(pkg) || !seen.add(pkg)) continue;
            String label = String.valueOf(info.loadLabel(pm));
            entries.add(new AppEntry(label, pkg, isSystemApp(info.activityInfo.applicationInfo),
                    pinnedSet.contains(pkg), info));
        }
        entries.sort(Comparator
                .comparing((AppEntry a) -> {
                    int idx = pinnedOrder.indexOf(a.packageName);
                    return idx >= 0 ? idx : Integer.MAX_VALUE;
                })
                .thenComparing(a -> a.systemApp)
                .thenComparing(a -> a.label, String.CASE_INSENSITIVE_ORDER));
        return entries;
    }

    private void showPinnedWidgetAppsPickerDialog() {
        if (!CoverDisplay.isSupportedFirmware() || prefs == null || service == null) return;
        final List<String> selectedOrder = new ArrayList<>(currentPinnedPackages());

        boolean compact = isCompactCoverUi();
        int safeHeight = currentSafeWindowFrame().safeRect().height();
        boolean compactHeight = safeHeight < dp(420);
        boolean ultraCompactHeight = safeHeight < dp(320);

        Dialog dialog = trackDialog(new Dialog(this));
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        int sheetPadH = compact ? dp(8) : dp(20);
        int sheetPadTop = ultraCompactHeight ? dp(6) : (compactHeight ? dp(10) : dp(16));
        int sheetPadBot = ultraCompactHeight ? dp(6) : (compactHeight ? dp(12) : dp(18));
        sheet.setPadding(sheetPadH, sheetPadTop, sheetPadH, sheetPadBot);
        sheet.setBackground(rounded(palette.surfaceContainerLow, ultraCompactHeight ? 22f : 28f));

        if (!compactHeight) {
            View handle = new View(this);
            handle.setBackground(rounded(palette.outlineVariant, 2f));
            LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(dp(36), dp(4));
            handleLp.gravity = Gravity.CENTER_HORIZONTAL;
            handleLp.bottomMargin = dp(14);
            sheet.addView(handle, handleLp);
        }

        float titleSp = ultraCompactHeight ? 15f : (compact ? 18f : 20f);
        TextView sheetTitle = createText(getString(R.string.picker_pinned_title), titleSp, palette.onSurface, FONT_BOLD);
        sheetTitle.setAccessibilityHeading(true);
        if (ultraCompactHeight) {
            sheetTitle.setSingleLine(true);
            sheetTitle.setEllipsize(TextUtils.TruncateAt.END);
        }
        sheet.addView(sheetTitle);
        TextView sheetSub = createText(getString(R.string.picker_pinned_sub),
                ultraCompactHeight ? 10.5f : 12.5f, palette.onSurfaceVariant, FONT_REGULAR);
        if (ultraCompactHeight) {
            sheetSub.setSingleLine(true);
            sheetSub.setEllipsize(TextUtils.TruncateAt.END);
            sheetSub.setPadding(0, dp(1), 0, dp(3));
        } else if (compactHeight) {
            sheetSub.setMaxLines(2);
            sheetSub.setEllipsize(TextUtils.TruncateAt.END);
            sheetSub.setPadding(0, dp(3), 0, dp(6));
        } else {
            sheetSub.setPadding(0, dp(4), 0, dp(10));
        }
        sheet.addView(sheetSub);

        // Live 4-Slot Preview Strip (tapping a filled slot removes it)
        LinearLayout slotsBar = new LinearLayout(this);
        slotsBar.setOrientation(LinearLayout.HORIZONTAL);
        slotsBar.setGravity(Gravity.CENTER);
        int slotsPad = dp(ultraCompactHeight ? 3 : 10);
        slotsBar.setPadding(slotsPad, slotsPad, slotsPad, slotsPad);
        slotsBar.setBackground(rounded(palette.surfaceContainerHigh, ultraCompactHeight ? 14f : 20f));
        LinearLayout.LayoutParams slotsBarLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slotsBarLp.bottomMargin = dp(ultraCompactHeight ? 3 : (compactHeight ? 6 : 10));
        sheet.addView(slotsBar, slotsBarLp);

        final ImageView[] slotIcons = new ImageView[ModuleConfig.MAX_PINNED_WIDGET_APPS];
        final TextView[] slotLabels = new TextView[ModuleConfig.MAX_PINNED_WIDGET_APPS];
        final LinearLayout[] slotCells = new LinearLayout[ModuleConfig.MAX_PINNED_WIDGET_APPS];
        final Map<String, AppEntry> entryByPkg = new HashMap<>();
        final Runnable[] refreshSlotsAndListRef = new Runnable[1];

        for (int i = 0; i < ModuleConfig.MAX_PINNED_WIDGET_APPS; i++) {
            final int slotIdx = i;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(ultraCompactHeight ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            int cellPadV = dp(ultraCompactHeight ? 2 : 6);
            cell.setPadding(dp(4), cellPadV, dp(4), cellPadV);
            cell.setBackground(rounded(palette.surfaceContainer, ultraCompactHeight ? 10f : 14f));

            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int slotIconSize = dp(ultraCompactHeight ? 18 : 34);
            LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(slotIconSize, slotIconSize);
            if (ultraCompactHeight) ivLp.rightMargin = dp(4);
            cell.addView(iv, ivLp);

            TextView lbl = createText(getString(R.string.picker_pinned_slot_empty, i + 1),
                    ultraCompactHeight ? 10f : 11f, palette.onSurfaceVariant, FONT_MEDIUM);
            lbl.setSingleLine(true);
            lbl.setEllipsize(TextUtils.TruncateAt.END);
            lbl.setGravity(ultraCompactHeight ? Gravity.START | Gravity.CENTER_VERTICAL : Gravity.CENTER);
            lbl.setPadding(ultraCompactHeight ? 0 : dp(2), ultraCompactHeight ? 0 : dp(4), ultraCompactHeight ? 0 : dp(2), 0);
            cell.addView(lbl, ultraCompactHeight
                    ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            cell.setOnClickListener(v -> {
                if (slotIdx < selectedOrder.size()) {
                    String removedPkg = selectedOrder.remove(slotIdx);
                    AppEntry e = entryByPkg.get(removedPkg);
                    if (e != null) e.selected = false;
                    if (refreshSlotsAndListRef[0] != null) refreshSlotsAndListRef[0].run();
                }
            });

            LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i < ModuleConfig.MAX_PINNED_WIDGET_APPS - 1) cellLp.rightMargin = dp(ultraCompactHeight ? 4 : 6);
            slotsBar.addView(cell, cellLp);
            slotIcons[i] = iv;
            slotLabels[i] = lbl;
            slotCells[i] = cell;
        }

        // Search Bar
        LinearLayout searchBox = new LinearLayout(this);
        searchBox.setOrientation(LinearLayout.HORIZONTAL);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        int searchPadV = ultraCompactHeight ? 0 : dp(2);
        searchBox.setPadding(dp(12), searchPadV, dp(12), searchPadV);
        searchBox.setBackground(rounded(palette.surfaceContainerHigh, ultraCompactHeight ? 18f : 24f));

        EditText searchInput = new EditText(this);
        searchInput.setHint(R.string.picker_search_hint);
        searchInput.setHintTextColor(palette.onSurfaceVariant);
        searchInput.setTextColor(palette.onSurface);
        searchInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, ultraCompactHeight ? 13f : 14f);
        searchInput.setSingleLine(true);
        searchInput.setBackground(null);
        int inputPadV = dp(ultraCompactHeight ? 4 : 6);
        searchInput.setPadding(dp(4), inputPadV, dp(4), inputPadV);
        searchInput.setMinHeight(dp(ultraCompactHeight ? 36 : (compactHeight ? 40 : 44)));
        searchBox.addView(searchInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchLp.bottomMargin = dp(ultraCompactHeight ? 2 : 4);
        sheet.addView(searchBox, searchLp);

        // Filter Chips Row
        final int[] activeFilter = {0}; // 0=user apps, 1=all apps, 2=pinned, 3=system apps
        int[] filterNames = {R.string.filter_third, R.string.filter_all, R.string.filter_pinned_only, R.string.filter_system};
        final List<TextView> chipViews = new ArrayList<>();
        final List<AppEntry> allApps = new ArrayList<>();
        final Runnable[] refilterRef = new Runnable[1];

        final List<AppEntry> adapterItems = new ArrayList<>();
        final BaseAdapter pinnedAdapter = new BaseAdapter() {
            @Override public int getCount() { return adapterItems.size(); }
            @Override public AppEntry getItem(int position) { return adapterItems.get(position); }
            @Override public long getItemId(int position) { return position; }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                AppRowHolder holder;
                if (convertView != null && convertView.getTag() instanceof AppRowHolder h) {
                    holder = h;
                } else {
                    holder = new AppRowHolder();
                }
                AppEntry app = adapterItems.get(position);
                holder.bind(app, position, adapterItems.size(), false);
                int orderIdx = selectedOrder.indexOf(app.packageName);
                if (orderIdx >= 0) {
                    holder.tag.setText(getString(R.string.tag_pinned_order, orderIdx + 1));
                    holder.tag.setVisibility(View.VISIBLE);
                } else {
                    holder.tag.setVisibility(View.GONE);
                }
                holder.syncBadgeRow();
                return holder.row;
            }
        };

        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setGravity(Gravity.CENTER_VERTICAL);

        for (int i = 0; i < filterNames.length; i++) {
            final int filterIdx = i;
            TextView chip = createChip(getString(filterNames[i]), palette.onSurfaceVariant, ultraCompactHeight);
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            chipLp.rightMargin = dp(ultraCompactHeight ? 6 : 8);
            chip.setOnClickListener(v -> {
                activeFilter[0] = filterIdx;
                refilterRef[0].run();
            });
            chipViews.add(chip);
            chipRow.addView(chip, chipLp);
        }

        TextView clearChip = createChip(getString(R.string.picker_pinned_clear), palette.primary, ultraCompactHeight);
        clearChip.setBackground(chipBackground(false, ultraCompactHeight));
        clearChip.setOnClickListener(v -> {
            selectedOrder.clear();
            for (AppEntry e : allApps) e.selected = false;
            if (refreshSlotsAndListRef[0] != null) refreshSlotsAndListRef[0].run();
        });
        chipRow.addView(clearChip);
        chipScroll.addView(chipRow);

        LinearLayout.LayoutParams chipScrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipScrollLp.bottomMargin = dp(ultraCompactHeight ? 2 : 4);
        sheet.addView(chipScroll, chipScrollLp);

        FrameLayout listFrame = new FrameLayout(this);
        ListView listView = new ListView(this);
        listView.setVerticalScrollBarEnabled(false);
        listView.setDivider(new ColorDrawable(Color.TRANSPARENT));
        listView.setDividerHeight(dp(2));
        listView.setSelector(new ColorDrawable(Color.TRANSPARENT));
        listView.setCacheColorHint(Color.TRANSPARENT);
        listView.setAdapter(pinnedAdapter);
        TextView emptyView = createText(getString(R.string.picker_loading),
                ultraCompactHeight ? 12.5f : 14f, palette.onSurfaceVariant, FONT_REGULAR);
        emptyView.setGravity(Gravity.CENTER);
        listFrame.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        listFrame.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        listView.setEmptyView(emptyView);
        sheet.addView(listFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        refreshSlotsAndListRef[0] = () -> {
            float slotRadius = ultraCompactHeight ? 10f : 14f;
            PackageManager pm = getPackageManager();
            for (int i = 0; i < ModuleConfig.MAX_PINNED_WIDGET_APPS; i++) {
                if (i < selectedOrder.size()) {
                    String pkg = selectedOrder.get(i);
                    AppEntry entry = entryByPkg.get(pkg);
                    String label = entry != null ? entry.label : pkg;
                    if (entry == null) {
                        try {
                            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                            CharSequence cs = pm.getApplicationLabel(ai);
                            if (cs != null && cs.length() > 0) label = cs.toString();
                        } catch (Throwable ignored) {
                        }
                    }
                    slotLabels[i].setText((i + 1) + ". " + label);
                    slotLabels[i].setTextColor(palette.onSurface);
                    slotCells[i].setBackground(rounded(palette.secondaryContainer, slotRadius));
                    if (entry != null) {
                        bindIcon(entry, slotIcons[i]);
                    } else {
                        try {
                            slotIcons[i].setImageDrawable(pm.getApplicationIcon(pkg));
                        } catch (Throwable ignored) {
                            slotIcons[i].setImageDrawable(null);
                        }
                    }
                } else {
                    slotIcons[i].setTag(null);
                    slotIcons[i].setImageDrawable(null);
                    slotLabels[i].setText(getString(R.string.picker_pinned_slot_empty, i + 1));
                    slotLabels[i].setTextColor(palette.onSurfaceVariant);
                    slotCells[i].setBackground(rounded(palette.surfaceContainer, slotRadius));
                }
            }
            pinnedAdapter.notifyDataSetChanged();
        };

        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= adapterItems.size()) return;
            AppEntry app = adapterItems.get(position);
            if (selectedOrder.contains(app.packageName)) {
                selectedOrder.remove(app.packageName);
                app.selected = false;
            } else {
                if (selectedOrder.size() >= ModuleConfig.MAX_PINNED_WIDGET_APPS) {
                    show(getString(R.string.msg_pinned_limit));
                    if (view.getTag() instanceof AppRowHolder h && h.entry == app) {
                        h.sw.setChecked(false, false);
                    }
                    return;
                }
                selectedOrder.add(app.packageName);
                app.selected = true;
            }
            refreshSlotsAndListRef[0].run();
        });

        refilterRef[0] = () -> {
            for (int i = 0; i < chipViews.size(); i++) {
                boolean sel = i == activeFilter[0];
                TextView c = chipViews.get(i);
                c.setTextColor(sel ? palette.onSecondaryContainer : palette.onSurfaceVariant);
                c.setBackground(chipBackground(sel, ultraCompactHeight));
                c.setSelected(sel);
            }
            String query = searchInput.getText() == null ? "" : searchInput.getText().toString().trim().toLowerCase(Locale.ROOT);
            adapterItems.clear();
            for (AppEntry app : allApps) {
                if (activeFilter[0] == 0 && app.systemApp && !selectedOrder.contains(app.packageName)) continue;
                if (activeFilter[0] == 2 && !selectedOrder.contains(app.packageName)) continue;
                if (activeFilter[0] == 3 && !app.systemApp) continue;
                if (!query.isEmpty()
                        && !app.labelLower.contains(query)
                        && !app.packageName.toLowerCase(Locale.ROOT).contains(query)) {
                    continue;
                }
                adapterItems.add(app);
            }
            refreshSlotsAndListRef[0].run();
        };
        refilterRef[0].run();

        Runnable debouncedFilter = () -> refilterRef[0].run();
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                mainHandler.removeCallbacks(debouncedFilter);
                mainHandler.postDelayed(debouncedFilter, 150);
            }
        });

        LinearLayout bottomRow = new LinearLayout(this);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        bottomRow.setPadding(0, dp(ultraCompactHeight ? 4 : (compactHeight ? 8 : 12)), 0, 0);

        float btnSp = compact ? 12.5f : 14f;
        int cancelPadH = compact ? dp(8) : dp(20);
        int savePadH = compact ? dp(10) : dp(22);
        int btnPadV = dp(ultraCompactHeight ? 6 : 10);
        int btnMinH = dp(ultraCompactHeight ? 36 : (compactHeight ? 42 : 48));

        TextView cancelBtn = createText(getString(R.string.btn_cancel), btnSp, palette.primary, FONT_MEDIUM);
        cancelBtn.setGravity(Gravity.CENTER);
        cancelBtn.setSingleLine(true);
        cancelBtn.setEllipsize(TextUtils.TruncateAt.END);
        cancelBtn.setPadding(cancelPadH, btnPadV, cancelPadH, btnPadV);
        cancelBtn.setMinHeight(btnMinH);
        cancelBtn.setBackground(pillRipple(Color.TRANSPARENT, palette.rippleColor, 24f));
        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        bottomRow.addView(cancelBtn);

        TextView saveBtn = createText(getString(R.string.btn_save_pinned), btnSp, palette.onPrimary, FONT_MEDIUM);
        saveBtn.setGravity(Gravity.CENTER);
        saveBtn.setSingleLine(true);
        saveBtn.setEllipsize(TextUtils.TruncateAt.END);
        saveBtn.setPadding(savePadH, btnPadV, savePadH, btnPadV);
        saveBtn.setMinHeight(btnMinH);
        saveBtn.setBackground(pillRipple(palette.primary, palette.rippleColor, 24f));
        saveBtn.setEnabled(false);
        saveBtn.setAlpha(0.45f);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        saveLp.leftMargin = compact ? dp(4) : dp(8);
        saveBtn.setOnClickListener(v -> {
            dialog.dismiss();
            try {
                String serialized = ModuleConfig.serializePinnedApps(selectedOrder);
                boolean enableSwitch = !selectedOrder.isEmpty();
                prefs.edit()
                        .putString(ModuleConfig.KEY_PINNED_WIDGET_APPS, serialized)
                        .putBoolean(ModuleConfig.KEY_PINNED_WIDGET_ENABLED, enableSwitch)
                        .apply();
                broadcastPinnedWidgetSync(enableSwitch, serialized);
                syncSwitches(ModuleConfig.KEY_PINNED_WIDGET_ENABLED, enableSwitch);
                refreshPinnedSummaryTexts();
                if (coverPreview != null) coverPreview.invalidate();
                if (enableSwitch) {
                    show(getString(R.string.msg_pinned_saved, selectedOrder.size()));
                } else {
                    show(getString(R.string.msg_pinned_cleared));
                }
            } catch (RuntimeException e) {
                show(getString(R.string.msg_save_failed, String.valueOf(e.getMessage())));
            }
        });
        bottomRow.addView(saveBtn, saveLp);
        sheet.addView(bottomRow);

        dialog.setContentView(sheet, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dialog.setOnDismissListener(d -> mainHandler.removeCallbacks(debouncedFilter));
        configureCoverSafePickerWindow(dialog, true);
        dialog.show();
        configureCoverSafePickerWindow(dialog, true);

        PackageManager pm = getPackageManager();
        IO.execute(() -> {
            List<AppEntry> loaded;
            try {
                loaded = loadLaunchableAppsForWidget(pm, selectedOrder);
            } catch (RuntimeException e) {
                Log.w(TAG, "failed to load widget apps", e);
                loaded = List.of();
            }
            final List<AppEntry> result = loaded;
            mainHandler.post(() -> {
                if (isDestroyed() || !dialog.isShowing()) return;
                if (result.isEmpty()) {
                    dialog.dismiss();
                    show(getString(R.string.msg_no_apps));
                    return;
                }
                allApps.addAll(result);
                for (AppEntry e : result) {
                    entryByPkg.put(e.packageName, e);
                    e.selected = selectedOrder.contains(e.packageName);
                }
                emptyView.setText(R.string.picker_empty);
                saveBtn.setEnabled(true);
                saveBtn.setAlpha(1f);
                refilterRef[0].run();
            });
        });
    }

    /** Shows a message on every page; Toast is used because only the home page has a status card. */
    private void show(String text) {
        if (isFinishing() || isDestroyed() || text == null) return;
        if (toast != null) toast.cancel();
        toast = Toast.makeText(this, text, Toast.LENGTH_LONG);
        toast.show();
    }

    // ==================== Custom Material 3 Views & Palette ====================

    /**
     * Material 3 Dynamic Color (Monet) palette resolved from Android 12-17 system_* tokens.
     */
    private static final class M3Palette {
        final int background;
        final int surfaceContainerLow;
        final int surfaceContainer;
        final int surfaceContainerHigh;
        final int primary;
        final int onPrimary;
        final int primaryContainer;
        final int onPrimaryContainer;
        final int onPrimaryContainerVariant;
        final int secondaryContainer;
        final int onSecondaryContainer;
        final int tertiaryContainer;
        final int onTertiaryContainer;
        final int errorContainer;
        final int onErrorContainer;
        final int onSurface;
        final int onSurfaceVariant;
        final int outline;
        final int outlineVariant;
        final int rippleColor;

        M3Palette(Context c, boolean dark) {
            if (dark) {
                background = resolve(c, android.R.color.system_neutral1_900, 0xff111318);
                int n800 = resolve(c, android.R.color.system_neutral1_800, 0xff1d2026);
                int n2_800 = resolve(c, android.R.color.system_neutral2_800, 0xff232730);
                surfaceContainerLow = blend(background, n800, 0.65f);
                surfaceContainer = blend(n800, n2_800, 0.5f);
                surfaceContainerHigh = resolve(c, android.R.color.system_neutral2_700, 0xff2d313b);
                primary = resolve(c, android.R.color.system_accent1_200, 0xffa8c7fa);
                onPrimary = resolve(c, android.R.color.system_accent1_800, 0xff062e6f);
                primaryContainer = resolve(c, android.R.color.system_accent1_700, 0xff0842a0);
                onPrimaryContainer = resolve(c, android.R.color.system_accent1_100, 0xffd3e3fd);
                onPrimaryContainerVariant = blend(onPrimaryContainer, primaryContainer, 0.22f);
                secondaryContainer = resolve(c, android.R.color.system_accent2_700, 0xff33445c);
                onSecondaryContainer = resolve(c, android.R.color.system_accent2_100, 0xffd7e3f8);
                tertiaryContainer = resolve(c, android.R.color.system_accent3_700, 0xff4c3858);
                onTertiaryContainer = resolve(c, android.R.color.system_accent3_100, 0xfff3daff);
                errorContainer = 0xff8c1d18;
                onErrorContainer = 0xfff9dedc;
                onSurface = resolve(c, android.R.color.system_neutral1_100, 0xffe2e2e9);
                onSurfaceVariant = resolve(c, android.R.color.system_neutral2_200, 0xffc4c6d0);
                outline = resolve(c, android.R.color.system_neutral2_400, 0xff8e9099);
                outlineVariant = resolve(c, android.R.color.system_neutral2_700, 0xff44474f);
                rippleColor = 0x26ffffff;
            } else {
                background = resolve(c, android.R.color.system_neutral1_50, 0xfff6f7fb);
                surfaceContainerLow = resolve(c, android.R.color.system_neutral1_10, 0xfffcfcff);
                surfaceContainer = Color.WHITE;
                surfaceContainerHigh = resolve(c, android.R.color.system_neutral2_100, 0xffe7e8ee);
                primary = resolve(c, android.R.color.system_accent1_600, 0xff1b5e91);
                onPrimary = Color.WHITE;
                primaryContainer = resolve(c, android.R.color.system_accent1_100, 0xffd6e3ff);
                onPrimaryContainer = resolve(c, android.R.color.system_accent1_900, 0xff001b3e);
                onPrimaryContainerVariant = blend(onPrimaryContainer, primaryContainer, 0.28f);
                secondaryContainer = resolve(c, android.R.color.system_accent2_100, 0xffdae2f9);
                onSecondaryContainer = resolve(c, android.R.color.system_accent2_900, 0xff131c2b);
                tertiaryContainer = resolve(c, android.R.color.system_accent3_100, 0xfffad8fd);
                onTertiaryContainer = resolve(c, android.R.color.system_accent3_900, 0xff28132e);
                errorContainer = 0xfff9dedc;
                onErrorContainer = 0xff410e0b;
                onSurface = resolve(c, android.R.color.system_neutral1_900, 0xff191c20);
                onSurfaceVariant = resolve(c, android.R.color.system_neutral2_700, 0xff44474e);
                outline = resolve(c, android.R.color.system_neutral2_500, 0xff74777f);
                outlineVariant = resolve(c, android.R.color.system_neutral2_200, 0xffdfe2eb);
                rippleColor = 0x1f000000;
            }
        }

        private static int resolve(Context c, int resId, int fallback) {
            try {
                return c.getColor(resId);
            } catch (RuntimeException e) {
                return fallback;
            }
        }

        static int blend(int c1, int c2, float ratio) {
            float inv = 1f - ratio;
            int a = Math.round(Color.alpha(c1) * inv + Color.alpha(c2) * ratio);
            int r = Math.round(Color.red(c1) * inv + Color.red(c2) * ratio);
            int g = Math.round(Color.green(c1) * inv + Color.green(c2) * ratio);
            int b = Math.round(Color.blue(c1) * inv + Color.blue(c2) * ratio);
            return Color.argb(a, r, g, b);
        }
    }

    /**
     * Authentic Material 3 Switch (52dp x 32dp stadium track, animated thumb diameter 16dp -> 24dp,
     * checkmark glyph inside checked thumb). Exposed to accessibility services as android.widget.Switch.
     */
    private final class M3Switch extends View {
        private boolean checked;
        private boolean switchEnabled = true;
        private float progress;
        private ValueAnimator animator;
        private Consumer<Boolean> listener;
        /** Row that represents this switch for accessibility (see {@link #bindSwitchRow}). */
        View hostRow;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF trackRect = new RectF();
        private final Path checkPath = new Path();

        M3Switch(Context context, boolean initialChecked) {
            super(context);
            this.checked = initialChecked;
            this.progress = initialChecked ? 1f : 0f;
            setClickable(true);
            setFocusable(true);
            setOnClickListener(v -> {
                if (switchEnabled) toggleChecked();
            });
            setStateDescription(stateText(initialChecked));
        }

        @Override
        public CharSequence getAccessibilityClassName() {
            return "android.widget.Switch";
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.Switch");
            info.setCheckable(true);
            info.setChecked(checked);
            info.setEnabled(switchEnabled);
        }

        void setSwitchEnabled(boolean enabled) {
            this.switchEnabled = enabled;
            setAlpha(enabled ? 1f : 0.45f);
            if (hostRow != null) hostRow.setEnabled(enabled);
            invalidate();
        }

        boolean isSwitchEnabled() {
            return switchEnabled;
        }

        boolean isChecked() {
            return checked;
        }

        void setOnCheckedChangeListener(Consumer<Boolean> listener) {
            this.listener = listener;
        }

        void toggleChecked() {
            setChecked(!checked, true);
        }

        private void publishState() {
            String state = stateText(checked);
            setStateDescription(state);
            if (hostRow != null) hostRow.setStateDescription(state);
        }

        /** Sets the state without animation (used when recycling list rows). */
        void jumpTo(boolean value) {
            if (animator != null) animator.cancel();
            checked = value;
            progress = value ? 1f : 0f;
            publishState();
            invalidate();
        }

        void setChecked(boolean value, boolean notify) {
            if (this.checked == value) return;
            this.checked = value;
            publishState();
            if (animator != null) animator.cancel();
            animator = ValueAnimator.ofFloat(progress, value ? 1f : 0f);
            animator.setDuration(190);
            animator.setInterpolator(M3_STANDARD);
            animator.addUpdateListener(a -> {
                progress = (Float) a.getAnimatedValue();
                invalidate();
            });
            animator.start();
            if (notify && listener != null) {
                listener.accept(value);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float stroke = dp(2);
            float radius = h / 2f;

            // Track fill & border
            int trackColor = M3Palette.blend(palette.surfaceContainerHigh, palette.primary, progress);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(trackColor);
            trackRect.set(stroke / 2f, stroke / 2f, w - stroke / 2f, h - stroke / 2f);
            canvas.drawRoundRect(trackRect, radius, radius, paint);

            if (progress < 0.99f) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(stroke * (1f - progress));
                paint.setColor(palette.outline);
                canvas.drawRoundRect(trackRect, radius, radius, paint);
            }

            // Thumb position & diameter (16dp unchecked -> 24dp checked)
            float minThumb = dp(16);
            float maxThumb = dp(24);
            float thumbSize = minThumb + (maxThumb - minThumb) * progress;
            float startCx = h / 2f;
            float endCx = w - h / 2f;
            float cx = startCx + (endCx - startCx) * progress;
            float cy = h / 2f;

            int thumbColor = M3Palette.blend(palette.outline, palette.onPrimary, progress);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(thumbColor);
            canvas.drawCircle(cx, cy, thumbSize / 2f, paint);

            // Checkmark icon inside thumb when checked
            if (progress > 0.35f) {
                float p = (progress - 0.35f) / 0.65f;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2f));
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setColor(palette.primary);
                paint.setAlpha(Math.round(255 * p));
                float s = dp(5f) * p;
                checkPath.reset();
                checkPath.moveTo(cx - s * 0.95f, cy + s * 0.05f);
                checkPath.lineTo(cx - s * 0.2f, cy + s * 0.8f);
                checkPath.lineTo(cx + s * 1.05f, cy - s * 0.7f);
                canvas.drawPath(checkPath, paint);
            }
        }
    }

    /**
     * Live interactive schematic preview of the MIX Flip 2 Cover Display inside the Home Hero Card.
     */
    private final class CoverPreviewView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        CoverPreviewView(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w = getWidth();
            float h = getHeight();
            float pad = dp(2);

            // Outer cover phone chassis
            rect.set(pad, pad, w - pad, h - pad);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(palette.surfaceContainer);
            c.drawRoundRect(rect, dp(18), dp(18), paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(palette.primary);
            c.drawRoundRect(rect, dp(18), dp(18), paint);

            // Dual camera cutout lenses at bottom-right
            float camX = w * 0.75f;
            float camR = dp(8.5f);
            float cam1Y = h * 0.60f;
            float cam2Y = h * 0.80f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(palette.onSurface);
            c.drawCircle(camX, cam1Y, camR, paint);
            c.drawCircle(camX, cam2Y, camR, paint);
            paint.setColor(palette.surfaceContainerHigh);
            c.drawCircle(camX, cam1Y, camR * 0.42f, paint);
            c.drawCircle(camX, cam2Y, camR * 0.42f, paint);

            // Status Orb above lenses (if panel_enabled)
            if (value(ModuleConfig.KEY_PANEL)) {
                float orbY = h * 0.22f;
                float orbR = dp(7.5f);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2.2f));
                paint.setColor(palette.primary);
                c.drawCircle(camX, orbY, orbR, paint);
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(camX, orbY, dp(2f), paint);
            }

            // Square Live Island card between Status Orb and Lenses (if live_island)
            if (value(ModuleConfig.KEY_LIVE_ISLAND)) {
                float boxHalf = dp(7.5f);
                float boxY = h * 0.40f;
                rect.set(camX - boxHalf, boxY - boxHalf, camX + boxHalf, boxY + boxHalf);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(palette.secondaryContainer);
                c.drawRoundRect(rect, dp(4), dp(4), paint);
            }

            // Left-side 5-row app grid preview
            boolean immersive = value(ModuleConfig.KEY_IMMERSIVE);
            float topStart = immersive ? h * 0.14f : h * 0.23f;
            if (!immersive) {
                // Draw legacy status bar strip at top when immersive is off
                rect.set(dp(8), dp(7), w - dp(8), dp(13));
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(palette.outlineVariant);
                c.drawRoundRect(rect, dp(3), dp(3), paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(palette.primary);
            float col1 = w * 0.22f;
            float col2 = w * 0.43f;
            float dotR = dp(3.8f);
            float step = (h * 0.85f - topStart) / 4f;
            for (int r = 0; r < 5; r++) {
                float cy = topStart + r * step;
                c.drawRoundRect(col1 - dotR, cy - dotR, col1 + dotR, cy + dotR, dp(2.2f), dp(2.2f), paint);
                c.drawRoundRect(col2 - dotR, cy - dotR, col2 + dotR, cy + dotR, dp(2.2f), dp(2.2f), paint);
            }
        }
    }

    /**
     * Vector glyph icons rendered directly via Canvas paths (zero external asset overhead).
     */
    private static final class Glyph {
        static final int HOME = 1;
        static final int ORB = 2;
        static final int LAYOUT = 3;
        static final int SHIELD = 4;
        static final int BATTERY = 5;
        static final int SIM = 6;
        static final int ISLAND = 7;
        static final int FULLSCREEN = 8;
        static final int GRID = 9;
        static final int SPARKLE = 10;
        static final int LAYERS = 11;
        static final int KEYBOARD = 12;
        static final int CLEAN = 13;
        static final int INFO = 14;
        static final int RESTORE = 15;
        static final int TERMINAL = 16;
        static final int LINK = 17;
        static final int LOG = 18;
        static final int LICENSE = 19;
    }

    private final class GlyphView extends View {
        private final int glyph;
        private final int color;
        private final boolean filled;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();

        GlyphView(Context context, int glyph, int color, boolean filled) {
            super(context);
            this.glyph = glyph;
            this.color = color;
            this.filled = filled;
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w = getWidth();
            float h = getHeight();
            float s = Math.min(w, h) / 24f;
            c.save();
            c.translate((w - 24f * s) / 2f, (h - 24f * s) / 2f);
            c.scale(s, s);

            paint.reset();
            paint.setAntiAlias(true);
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.1f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);

            switch (glyph) {
                case Glyph.HOME -> {
                    path.reset();
                    path.moveTo(4, 10.5f);
                    path.lineTo(12, 4);
                    path.lineTo(20, 10.5f);
                    path.lineTo(20, 19.5f);
                    path.lineTo(4, 19.5f);
                    path.close();
                    if (filled) paint.setStyle(Paint.Style.FILL_AND_STROKE);
                    c.drawPath(path, paint);
                }
                case Glyph.ORB -> {
                    c.drawCircle(12, 13, 6.5f, paint);
                    paint.setStyle(Paint.Style.FILL);
                    c.drawCircle(12, 13, 2.2f, paint);
                    c.drawRoundRect(8.5f, 3f, 15.5f, 5f, 1f, 1f, paint);
                }
                case Glyph.LAYOUT, Glyph.GRID -> {
                    rect.set(4, 4, 10.5f, 10.5f);
                    c.drawRoundRect(rect, 2f, 2f, paint);
                    rect.set(13.5f, 4, 20, 10.5f);
                    c.drawRoundRect(rect, 2f, 2f, paint);
                    rect.set(4, 13.5f, 10.5f, 20);
                    c.drawRoundRect(rect, 2f, 2f, paint);
                    rect.set(13.5f, 13.5f, 20, 20);
                    c.drawRoundRect(rect, 2f, 2f, paint);
                }
                case Glyph.SHIELD -> {
                    path.reset();
                    path.moveTo(12, 3.2f);
                    path.lineTo(19, 6.2f);
                    path.lineTo(19, 11.8f);
                    path.cubicTo(19, 16.2f, 15.8f, 19.5f, 12, 20.8f);
                    path.cubicTo(8.2f, 19.5f, 5, 16.2f, 5, 11.8f);
                    path.lineTo(5, 6.2f);
                    path.close();
                    c.drawPath(path, paint);
                    path.reset();
                    path.moveTo(9.2f, 12.2f);
                    path.lineTo(11.2f, 14.2f);
                    path.lineTo(15.2f, 10.0f);
                    c.drawPath(path, paint);
                }
                case Glyph.BATTERY -> {
                    rect.set(6, 5.5f, 18, 20);
                    c.drawRoundRect(rect, 2.5f, 2.5f, paint);
                    paint.setStyle(Paint.Style.FILL);
                    c.drawRoundRect(9.5f, 3.2f, 14.5f, 5.5f, 1f, 1f, paint);
                    path.reset();
                    path.moveTo(12.8f, 8.8f);
                    path.lineTo(10.0f, 13.2f);
                    path.lineTo(12.3f, 13.2f);
                    path.lineTo(11.2f, 17.2f);
                    path.lineTo(14.2f, 12.6f);
                    path.lineTo(11.9f, 12.6f);
                    path.close();
                    c.drawPath(path, paint);
                }
                case Glyph.SIM -> {
                    paint.setStyle(Paint.Style.FILL);
                    for (int i = 0; i < 4; i++) {
                        c.drawCircle(6 + i * 4f, 9.5f, 1.5f, paint);
                        c.drawCircle(6 + i * 4f, 15.5f, 1.5f, paint);
                    }
                }
                case Glyph.ISLAND -> {
                    rect.set(4.5f, 4.5f, 19.5f, 19.5f);
                    c.drawRoundRect(rect, 5f, 5f, paint);
                    c.drawLine(8.5f, 10f, 15.5f, 10f, paint);
                    c.drawLine(8.5f, 14f, 13.5f, 14f, paint);
                }
                case Glyph.FULLSCREEN -> {
                    path.reset();
                    path.moveTo(4, 9); path.lineTo(4, 4); path.lineTo(9, 4);
                    path.moveTo(15, 4); path.lineTo(20, 4); path.lineTo(20, 9);
                    path.moveTo(20, 15); path.lineTo(20, 20); path.lineTo(15, 20);
                    path.moveTo(9, 20); path.lineTo(4, 20); path.lineTo(4, 15);
                    c.drawPath(path, paint);
                }
                case Glyph.SPARKLE -> {
                    path.reset();
                    path.moveTo(12, 3.5f);
                    path.quadTo(12.8f, 11.2f, 20.5f, 12f);
                    path.quadTo(12.8f, 12.8f, 12f, 20.5f);
                    path.quadTo(11.2f, 12.8f, 3.5f, 12f);
                    path.quadTo(11.2f, 11.2f, 12f, 3.5f);
                    path.close();
                    c.drawPath(path, paint);
                }
                case Glyph.LAYERS -> {
                    path.reset();
                    path.moveTo(12, 4); path.lineTo(20, 8.5f); path.lineTo(12, 13); path.lineTo(4, 8.5f); path.close();
                    path.moveTo(4, 13); path.lineTo(12, 17.5f); path.lineTo(20, 13);
                    path.moveTo(4, 16.8f); path.lineTo(12, 21f); path.lineTo(20, 16.8f);
                    c.drawPath(path, paint);
                }
                case Glyph.KEYBOARD -> {
                    rect.set(3.5f, 6f, 20.5f, 18.5f);
                    c.drawRoundRect(rect, 2.5f, 2.5f, paint);
                    c.drawLine(8.5f, 15.2f, 15.5f, 15.2f, paint);
                    paint.setStyle(Paint.Style.FILL);
                    for (int i = 0; i < 4; i++) {
                        c.drawCircle(7f + i * 3.3f, 9.6f, 1.0f, paint);
                        c.drawCircle(7f + i * 3.3f, 12.4f, 1.0f, paint);
                    }
                }
                case Glyph.CLEAN -> {
                    c.drawLine(5, 7, 19, 7, paint);
                    rect.set(7.5f, 7, 16.5f, 19.5f);
                    c.drawRoundRect(rect, 2f, 2f, paint);
                    c.drawLine(10, 4.5f, 14, 4.5f, paint);
                }
                case Glyph.RESTORE -> {
                    rect.set(5, 5, 19, 19);
                    c.drawArc(rect, -60f, 290f, false, paint);
                    path.reset();
                    path.moveTo(15.2f, 2.6f);
                    path.lineTo(15.5f, 5.9f);
                    path.lineTo(18.8f, 5.4f);
                    c.drawPath(path, paint);
                }
                case Glyph.TERMINAL -> {
                    rect.set(3.5f, 5f, 20.5f, 19f);
                    c.drawRoundRect(rect, 2.5f, 2.5f, paint);
                    path.reset();
                    path.moveTo(7f, 9.5f);
                    path.lineTo(10f, 12f);
                    path.lineTo(7f, 14.5f);
                    c.drawPath(path, paint);
                    c.drawLine(12f, 15f, 16.5f, 15f, paint);
                }
                case Glyph.LINK -> {
                    c.save();
                    c.rotate(-45f, 12f, 12f);
                    rect.set(3f, 9f, 12.5f, 15f);
                    c.drawRoundRect(rect, 3f, 3f, paint);
                    rect.set(11.5f, 9f, 21f, 15f);
                    c.drawRoundRect(rect, 3f, 3f, paint);
                    c.restore();
                }
                case Glyph.LOG, Glyph.LICENSE -> {
                    rect.set(6f, 3.5f, 18f, 20.5f);
                    c.drawRoundRect(rect, 2f, 2f, paint);
                    c.drawLine(9f, 8f, 15f, 8f, paint);
                    c.drawLine(9f, 12f, 15f, 12f, paint);
                    c.drawLine(9f, 16f, glyph == Glyph.LOG ? 13f : 15f, 16f, paint);
                }
                default -> {
                    c.drawCircle(12, 12, 8f, paint);
                    c.drawLine(12, 11f, 12, 16f, paint);
                    paint.setStyle(Paint.Style.FILL);
                    c.drawCircle(12, 8f, 1.2f, paint);
                }
            }
            c.restore();
        }
    }
}
