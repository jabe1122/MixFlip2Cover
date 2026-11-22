package io.github.jabe1122.mixflip2cover;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityOptions;
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
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
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
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.ResultReceiver;
import android.os.UserHandle;
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
import java.util.concurrent.ConcurrentHashMap;
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
            Map.entry(ModuleConfig.KEY_QUICK_WIDGET_ENABLED, false),
            Map.entry(ModuleConfig.KEY_BATTERY_NUMBER, false),
            Map.entry(ModuleConfig.KEY_DUAL_SIM, false),
            Map.entry(ModuleConfig.KEY_ALLOW_ALL_COVER_APPS, true),
            Map.entry(ModuleConfig.KEY_DIRECT_OPEN_COVER_NOTIF, true),
            Map.entry(ModuleConfig.KEY_FOLD_POCKET_GUARD, true),
            Map.entry(ModuleConfig.KEY_UNLOCK_LAUNCHER_APPS, true),
            Map.entry(ModuleConfig.KEY_DOUBLE_TAP_LOCK, true),
            Map.entry(ModuleConfig.KEY_VERBOSE_LOG, false));
    /** Keys whose defaults are written explicitly on first connection so hooked processes see them. */
    private static final List<String> PERSISTED_DEFAULT_KEYS = List.of(
            ModuleConfig.KEY_PANEL,
            ModuleConfig.KEY_IMMERSIVE,
            ModuleConfig.KEY_LIVE_ISLAND,
            ModuleConfig.KEY_UNIVERSAL_COMPAT,
            ModuleConfig.KEY_ALLOW_ALL_COVER_APPS,
            ModuleConfig.KEY_DIRECT_OPEN_COVER_NOTIF,
            ModuleConfig.KEY_FOLD_POCKET_GUARD,
            ModuleConfig.KEY_UNLOCK_LAUNCHER_APPS,
            ModuleConfig.KEY_DOUBLE_TAP_LOCK,
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

    private static final class TrackedDialog {
        final WeakReference<Dialog> ref;
        Consumer<Dialog> relayout;

        TrackedDialog(Dialog dialog) {
            this.ref = new WeakReference<>(dialog);
        }
    }

    private record ScopeSnapshot(
            XposedService forService,
            List<String> scope,
            LinkedHashSet<String> required,
            DeepCompatStats deepCompatStats) {}

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private XposedService service;
    private SharedPreferences prefs;
    private final List<M3Switch> switches = new ArrayList<>();
    private final Map<String, List<M3Switch>> switchesByKey = new HashMap<>();
    private final List<View> actionButtons = new ArrayList<>();
    private final List<TextView> scopeCountTexts = new ArrayList<>();
    private final List<TextView> deepCompatStatusTexts = new ArrayList<>();
    private final List<TextView> pinnedSummaryTexts = new ArrayList<>();
    private final List<TextView> quickWidgetSummaryTexts = new ArrayList<>();
    private final List<TextView> orbTapSummaryTexts = new ArrayList<>();
    private final List<TextView> orbLongSummaryTexts = new ArrayList<>();
    private final List<TextView> foldModeSummaryTexts = new ArrayList<>();
    private final List<TextView> foldAppsSummaryTexts = new ArrayList<>();
    private final List<TextView> imeTuningSummaryTexts = new ArrayList<>();
    private final List<TextView> perAppRulesSummaryTexts = new ArrayList<>();
    private final List<TrackedDialog> trackedDialogs = new ArrayList<>();
    private final LruCache<String, Drawable> iconCache = new LruCache<>(160);
    private final Set<String> iconLoading = new HashSet<>();
    private final Map<String, String> appLabelCache = new ConcurrentHashMap<>();
    private volatile ScopeSnapshot cachedScopeSnapshot;
    private int scopeRefreshGen;
    private LinearLayout root;
    private ScrollView contentScroll;
    private LinearLayout pageContainer;
    private CoverPreviewView coverPreview;
    private TextView heroMetaText;
    private TextView homeCompatSubtitleText;
    private Status lastRenderedStatus = Status.DISCONNECTED;
    private int selected;
    private int lastConfigDensityDpi;
    private int lastConfigUiMode;
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
        Configuration cfg = getResources().getConfiguration();
        lastConfigDensityDpi = cfg.densityDpi;
        lastConfigUiMode = cfg.uiMode;
        dark = (cfg.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
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

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (isFinishing() || isDestroyed()) return;
        boolean newDark = (newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        boolean themeOrDensityChanged = (newDark != dark)
                || (newConfig.densityDpi != lastConfigDensityDpi)
                || ((newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                        != (lastConfigUiMode & Configuration.UI_MODE_NIGHT_MASK));
        if (newDark != dark) {
            dark = newDark;
            setTheme(dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        }
        lastConfigDensityDpi = newConfig.densityDpi;
        lastConfigUiMode = newConfig.uiMode;
        palette = new M3Palette(this, dark);
        if (themeOrDensityChanged) {
            iconCache.evictAll();
        }
        configureEdgeToEdge(dark);
        render(false);
        relayoutTrackedDialogs();
        mainHandler.post(this::relayoutTrackedDialogs);
    }

    private void onServiceBound(XposedService bound) {
        if (service == bound && prefs != null) return;
        service = bound;
        cachedScopeSnapshot = null;
        try {
            prefs = bound.getRemotePreferences(ModuleConfig.PREFS_NAME);
            ModuleConfig.initLocal(prefs);
            ensureDefaults();
            IO.execute(() -> {
                try {
                    List<String> scope = bound.getScope();
                    ModLog.i(TAG, "connected; frameworkScope=" + scope.contains("system")
                            + " scopeCount=" + scope.size());
                } catch (RuntimeException ignored) {
                }
            });
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
        cachedScopeSnapshot = null;
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
        for (TrackedDialog entry : trackedDialogs) {
            Dialog d = entry.ref.get();
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
        if (!prefs.contains(ModuleConfig.KEY_FOLD_CONTINUITY_MODE)) {
            editor.putInt(ModuleConfig.KEY_FOLD_CONTINUITY_MODE, ModuleConfig.FOLD_CONT_SYSTEM);
            changed = true;
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
            if (updates.containsKey(ModuleConfig.KEY_QUICK_WIDGET_ENABLED)) {
                boolean qwEnabled = Boolean.TRUE.equals(updates.get(ModuleConfig.KEY_QUICK_WIDGET_ENABLED));
                String rawSlots = prefs.getString(
                        ModuleConfig.KEY_QUICK_WIDGET_SLOTS,
                        ModuleConfig.DEFAULT_QUICK_WIDGET_SLOTS);
                if (qwEnabled && !prefs.contains(ModuleConfig.KEY_QUICK_WIDGET_SLOTS)) {
                    prefs.edit().putString(
                            ModuleConfig.KEY_QUICK_WIDGET_SLOTS,
                            ModuleConfig.DEFAULT_QUICK_WIDGET_SLOTS).apply();
                    rawSlots = ModuleConfig.DEFAULT_QUICK_WIDGET_SLOTS;
                }
                broadcastQuickWidgetSync(qwEnabled, rawSlots, true);
            }
            if (updates.containsKey(ModuleConfig.KEY_UNLOCK_LAUNCHER_APPS)) {
                broadcastLauncherAppsSync(Boolean.TRUE.equals(updates.get(ModuleConfig.KEY_UNLOCK_LAUNCHER_APPS)));
            }
            if (updates.containsKey(ModuleConfig.KEY_THIRD_PARTY_IME)) refreshScopeTexts();
            if (coverPreview != null) coverPreview.invalidate();
            if (ModuleConfig.KEY_PINNED_WIDGET_ENABLED.equals(key) && checked && currentPinnedPackages().isEmpty()) {
                showPinnedWidgetAppsPickerDialog();
            } else if (ModuleConfig.KEY_THIRD_PARTY_IME.equals(key) && checked) {
                final XposedService boundService = service;
                IO.execute(() -> {
                    List<String> candidates = installedUnpackedThirdPartyImePackages();
                    List<String> missingImeScopes = new ArrayList<>();
                    if (!candidates.isEmpty() && boundService != null) {
                        try {
                            List<String> scope = boundService.getScope();
                            for (String pkg : candidates) {
                                if (!scope.contains(pkg)) missingImeScopes.add(pkg);
                            }
                        } catch (RuntimeException ignored) {
                        }
                    }
                    mainHandler.post(() -> {
                        if (isFinishing() || isDestroyed() || service != boundService) return;
                        if (!missingImeScopes.isEmpty()) {
                            requestPackages(missingImeScopes);
                        } else {
                            showSwitchEffectHint(key);
                        }
                    });
                });
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

    private boolean isBuiltInOrSystemImePackage(String pkg) {
        return pkg == null || pkg.isEmpty()
                || "com.sohu.inputmethod.sogou.xiaomi".equals(pkg)
                || pkg.startsWith("com.miui.")
                || pkg.startsWith("com.xiaomi.")
                || pkg.startsWith("com.android.")
                || "com.google.android.tts".equals(pkg);
    }

    private List<String> installedUnpackedThirdPartyImePackages() {
        LinkedHashSet<String> pkgs = new LinkedHashSet<>();
        try {
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            PackageManager pm = getPackageManager();
            if (imm != null && pm != null) {
                for (InputMethodInfo info : imm.getEnabledInputMethodList()) {
                    if (info == null) continue;
                    String pkg = info.getPackageName();
                    if (isBuiltInOrSystemImePackage(pkg)) continue;
                    if (info.getServiceInfo() != null && AppCompatibility.isPackedOrProtectedApp(info.getServiceInfo().applicationInfo)) {
                        continue;
                    }
                    pkgs.add(pkg);
                }
                if (pkgs.isEmpty()) {
                    try {
                        getPackageManager().getPackageInfo(WETYPE_PKG, 0);
                        pkgs.add(WETYPE_PKG);
                    } catch (PackageManager.NameNotFoundException ignored) {
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        return new ArrayList<>(pkgs);
    }

    private void broadcastLauncherAppsSync(boolean unlock) {
        try {
            Intent intent = new Intent(CoverLauncherApps.ACTION_LAUNCHER_APPS_SYNC)
                    .setPackage(CoverGeometry.FLIPHOME)
                    .putExtra(ModuleConfig.KEY_UNLOCK_LAUNCHER_APPS, unlock);
            BroadcastOptions options = BroadcastOptions.makeBasic();
            options.setShareIdentityEnabled(true);
            sendBroadcast(intent, null, options.toBundle());
        } catch (RuntimeException e) {
            Log.w(TAG, "launcher apps sync broadcast failed", e);
        }
    }

    private static final int DUAL_APP_USER_ID = 999;

    private static UserHandle userHandleForId(int userId) {
        if (userId <= 0) return Process.myUserHandle();
        return UserHandle.getUserHandleForUid(userId * 100000);
    }

    private static int extractUserId(UserHandle uh) {
        if (uh == null) return 0;
        try {
            Object id = UserHandle.class.getMethod("getIdentifier").invoke(uh);
            if (id instanceof Integer v) return Math.max(0, v);
        } catch (Throwable ignored) {
        }
        return Math.max(0, uh.hashCode());
    }

    private static List<UserHandle> querySecondaryUserHandles(LauncherApps launcherApps) {
        List<UserHandle> out = new ArrayList<>();
        Set<Integer> seenIds = new HashSet<>();
        seenIds.add(0);
        if (launcherApps != null) {
            try {
                for (UserHandle uh : launcherApps.getProfiles()) {
                    int uid = extractUserId(uh);
                    if (uid > 0 && seenIds.add(uid)) {
                        out.add(uh);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (seenIds.add(DUAL_APP_USER_ID)) {
            out.add(userHandleForId(DUAL_APP_USER_ID));
        }
        return out;
    }

    private boolean isAppTargetInstalled(PackageManager pm, LauncherApps launcherApps, String target) {
        String pkg = ModuleConfig.extractAppTargetPackage(target);
        if (pkg == null || pkg.isEmpty()) return false;
        int userId = ModuleConfig.extractAppTargetUserId(target);
        if (userId > 0) {
            if (launcherApps == null) return false;
            try {
                List<LauncherActivityInfo> list = launcherApps.getActivityList(pkg, userHandleForId(userId));
                return list != null && !list.isEmpty();
            } catch (Throwable ignored) {
                return false;
            }
        }
        try {
            pm.getApplicationInfo(pkg, 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Drawable loadAppIconSync(PackageManager pm, LauncherApps launcherApps, String pkg, int userId) {
        if (pkg == null || pkg.isEmpty()) return null;
        if (userId > 0 && launcherApps != null) {
            try {
                List<LauncherActivityInfo> list = launcherApps.getActivityList(pkg, userHandleForId(userId));
                if (list != null && !list.isEmpty()) {
                    Drawable badged = list.get(0).getBadgedIcon(0);
                    if (badged != null) return badged;
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            return pm.getApplicationIcon(pkg);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String cachedLabelOrPkg(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "";
        String cached = appLabelCache.get(pkg);
        return cached != null ? cached : pkg;
    }

    private String formatAppTargetLabel(String target) {
        String pkg = ModuleConfig.extractAppTargetPackage(target);
        int userId = ModuleConfig.extractAppTargetUserId(target);
        String base = cachedLabelOrPkg(pkg);
        return userId > 0 ? getString(R.string.dual_app_name_fmt, base) : base;
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

    private boolean isNarrowDialogFooter() {
        return currentSafeWindowFrame().safeRect().width() < dp(320);
    }

    private <T extends Dialog> T trackDialog(T dialog) {
        if (dialog != null) {
            trackedDialogs.removeIf(entry -> {
                Dialog existing = entry.ref.get();
                return existing == null || !existing.isShowing();
            });
            for (TrackedDialog entry : trackedDialogs) {
                if (entry.ref.get() == dialog) return dialog;
            }
            trackedDialogs.add(new TrackedDialog(dialog));
        }
        return dialog;
    }

    private void registerDialogRelayout(Dialog dialog, Consumer<Dialog> relayout) {
        if (dialog == null) return;
        for (TrackedDialog entry : trackedDialogs) {
            if (entry.ref.get() == dialog) {
                entry.relayout = relayout;
                return;
            }
        }
        TrackedDialog td = new TrackedDialog(dialog);
        td.relayout = relayout;
        trackedDialogs.add(td);
    }

    private void relayoutTrackedDialogs() {
        if (isFinishing() || isDestroyed()) return;
        trackedDialogs.removeIf(entry -> {
            Dialog existing = entry.ref.get();
            return existing == null || !existing.isShowing();
        });
        for (TrackedDialog entry : new ArrayList<>(trackedDialogs)) {
            Dialog d = entry.ref.get();
            if (d != null && d.isShowing() && entry.relayout != null) {
                try {
                    entry.relayout.accept(d);
                } catch (RuntimeException ignored) {
                }
            }
        }
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
        registerDialogRelayout(dialog, d -> configureCoverSafeDialogWindow(d, tallSheet, customBackground));
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
        registerDialogRelayout(dialog, d -> configureCoverSafePickerWindow(d, hasExtraTopBar));
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
        int savedScrollY = (!animateTransition && contentScroll != null) ? contentScroll.getScrollY() : 0;
        loading = true;
        switches.clear();
        switchesByKey.clear();
        actionButtons.clear();
        scopeCountTexts.clear();
        deepCompatStatusTexts.clear();
        pinnedSummaryTexts.clear();
        quickWidgetSummaryTexts.clear();
        orbTapSummaryTexts.clear();
        orbLongSummaryTexts.clear();
        foldModeSummaryTexts.clear();
        foldAppsSummaryTexts.clear();
        imeTuningSummaryTexts.clear();
        perAppRulesSummaryTexts.clear();
        coverPreview = null;
        heroMetaText = null;
        homeCompatSubtitleText = null;

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
        contentScroll = scroll;

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
        if (savedScrollY > 0) {
            final ScrollView targetScroll = scroll;
            targetScroll.post(() -> targetScroll.scrollTo(0, savedScrollY));
        }
        refreshAsyncSummaries();

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

    private ScopeSnapshot usableScopeSnapshot() {
        ScopeSnapshot snap = cachedScopeSnapshot;
        if (snap != null && snap.forService() == service) {
            return snap;
        }
        return new ScopeSnapshot(service, null, new LinkedHashSet<>(CORE_SCOPE), null);
    }

    private String formatHeroMeta(Status st, List<String> scope) {
        boolean unsupported = st == Status.UNSUPPORTED_DEVICE || st == Status.UNSUPPORTED_FIRMWARE;
        String fwText;
        if (frameworkReady()) fwText = getString(R.string.fw_ready);
        else if (frameworkProp.isEmpty()) fwText = getString(R.string.fw_inactive);
        else fwText = getString(R.string.fw_mismatch);
        StringBuilder meta = new StringBuilder(getString(R.string.meta_version, versionName()))
                .append(" · ")
                .append(getString(R.string.meta_target));
        if (!unsupported) meta.append(" · ").append(fwText);
        if (scope != null) meta.append(" · ").append(getString(R.string.meta_scopes, scope.size()));
        return meta.toString();
    }

    private void buildHomePage(Status st) {
        lastRenderedStatus = st;
        ScopeSnapshot snap = usableScopeSnapshot();
        List<String> scope = snap.scope();

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

        TextView heroMeta = createText(formatHeroMeta(st, scope), 13f, palette.onPrimaryContainerVariant, FONT_MEDIUM);
        heroMeta.setPadding(0, dp(4), 0, dp(10));
        heroInfo.addView(heroMeta);
        heroMetaText = heroMeta;

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
        int homeQuickRow = overviewGroup.addActionRow(
                Glyph.QUICK_GRID,
                getString(R.string.sw_quick_widget_title),
                quickWidgetSlotsSummary(),
                getString(R.string.chip_configure),
                false,
                true,
                this::showQuickWidgetSlotsDialog);
        int homeCompatRow = overviewGroup.addActionRow(
                Glyph.SHIELD,
                getString(R.string.home_nav_compat_title),
                compatOverviewSubtitle(scope, snap.required()),
                getString(R.string.chip_manage),
                false,
                false,
                () -> selectTab(3));
        overviewGroup.commit();
        addPinnedSummaryText(overviewGroup.subtitleAt(homePinnedRow));
        addQuickWidgetSummaryText(overviewGroup.subtitleAt(homeQuickRow));
        homeCompatSubtitleText = overviewGroup.subtitleAt(homeCompatRow);

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

    private String compatOverviewSubtitle(List<String> scope, LinkedHashSet<String> req) {
        if (scope == null) {
            return service == null
                    ? getString(R.string.home_nav_compat_sub_disconnected)
                    : getString(R.string.picker_loading);
        }
        LinkedHashSet<String> missing = new LinkedHashSet<>(req != null ? req : CORE_SCOPE);
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
        int orbTapRow = orbGroup.addActionRow(Glyph.ORB, getString(R.string.row_orb_tap_title),
                orbActionLabel(ModuleConfig.orbTapAction()), getString(R.string.chip_configure),
                false, true, () -> showOrbActionPickerDialog(false));
        int orbLongRow = orbGroup.addActionRow(Glyph.ORB, getString(R.string.row_orb_long_title),
                orbActionLabel(ModuleConfig.orbLongPressAction()), getString(R.string.chip_configure),
                false, true, () -> showOrbActionPickerDialog(true));
        orbGroup.commit();
        addTextIfPresent(orbTapSummaryTexts, orbGroup.subtitleAt(orbTapRow));
        addTextIfPresent(orbLongSummaryTexts, orbGroup.subtitleAt(orbLongRow));

        addSectionHeader(getString(R.string.section_desktop));
        SegmentedGroup desktopGroup = new SegmentedGroup();
        desktopGroup.addSwitch(Glyph.ISLAND, R.string.sw_island_title, R.string.sw_island_sub, ModuleConfig.KEY_LIVE_ISLAND);
        desktopGroup.addSwitch(Glyph.GRID, R.string.sw_hide_names_title, R.string.sw_hide_names_sub, ModuleConfig.KEY_HIDE_APP_NAMES);
        desktopGroup.addSwitch(Glyph.LAYERS, R.string.sw_unlock_launcher_title, R.string.sw_unlock_launcher_sub, ModuleConfig.KEY_UNLOCK_LAUNCHER_APPS);
        desktopGroup.addSwitch(Glyph.LAYOUT, R.string.sw_double_tap_lock_title, R.string.sw_double_tap_lock_sub, ModuleConfig.KEY_DOUBLE_TAP_LOCK);
        desktopGroup.addSwitch(Glyph.GRID, R.string.sw_pinned_widget_title, R.string.sw_pinned_widget_sub, ModuleConfig.KEY_PINNED_WIDGET_ENABLED);
        int pinnedRow = desktopGroup.addActionRow(Glyph.SPARKLE, getString(R.string.row_pinned_widget_apps_title),
                pinnedWidgetAppsSummary(), getString(R.string.chip_configure), false, true, this::showPinnedWidgetAppsPickerDialog);
        desktopGroup.addSwitch(Glyph.QUICK_GRID, R.string.sw_quick_widget_title, R.string.sw_quick_widget_sub, ModuleConfig.KEY_QUICK_WIDGET_ENABLED);
        int quickRow = desktopGroup.addActionRow(Glyph.SPARKLE, getString(R.string.row_quick_widget_slots_title),
                quickWidgetSlotsSummary(), getString(R.string.chip_configure), false, true, this::showQuickWidgetSlotsDialog);
        desktopGroup.commit();
        addPinnedSummaryText(desktopGroup.subtitleAt(pinnedRow));
        addQuickWidgetSummaryText(desktopGroup.subtitleAt(quickRow));
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

        addSectionHeader(getString(R.string.section_fold_continuity));
        SegmentedGroup foldGroup = new SegmentedGroup();
        int foldModeRow = foldGroup.addActionRow(Glyph.SPARKLE, getString(R.string.row_fold_mode_title),
                foldContinuityModeSummary(), getString(R.string.chip_configure), false, true, this::showFoldContinuityModeDialog);
        int foldAppsRow = foldGroup.addActionRow(Glyph.LAYERS, getString(R.string.row_fold_apps_title),
                foldContinuityAppsSummary(), getString(R.string.chip_configure), false, true, this::showFoldContinuityAppsDialog);
        foldGroup.addSwitch(Glyph.SHIELD, R.string.sw_fold_pocket_title, R.string.sw_fold_pocket_sub, ModuleConfig.KEY_FOLD_POCKET_GUARD);
        foldGroup.commit();
        addTextIfPresent(foldModeSummaryTexts, foldGroup.subtitleAt(foldModeRow));
        addTextIfPresent(foldAppsSummaryTexts, foldGroup.subtitleAt(foldAppsRow));

        addSectionHeader(getString(R.string.section_ime_compat));
        SegmentedGroup imeGroup = new SegmentedGroup();
        imeGroup.addSwitch(Glyph.KEYBOARD, R.string.sw_ime_title, R.string.sw_ime_sub, ModuleConfig.KEY_THIRD_PARTY_IME);
        int imeTuneRow = imeGroup.addActionRow(Glyph.KEYBOARD, getString(R.string.row_ime_tuning_title),
                imeTuningSummary(), getString(R.string.chip_configure), false, true, this::showImeTuningDialog);
        imeGroup.addInfoRow(Glyph.INFO, getString(R.string.info_ime_scope_title), getString(R.string.info_ime_scope_sub));
        imeGroup.commit();
        addTextIfPresent(imeTuningSummaryTexts, imeGroup.subtitleAt(imeTuneRow));
    }

    private void buildCompatibilityPage() {
        addSectionHeader(getString(R.string.section_per_app_rules));
        SegmentedGroup rulesGroup = new SegmentedGroup();
        int rulesRow = rulesGroup.addActionRow(Glyph.LAYOUT, getString(R.string.row_per_app_rules_title),
                perAppRulesSummary(), getString(R.string.chip_manage), false, true, this::showPerAppRulesManagerDialog);
        rulesGroup.addActionRow(Glyph.LINK, getString(R.string.row_rules_json_title),
                getString(R.string.row_rules_json_sub), getString(R.string.chip_configure), false, true, this::showPerAppRulesJsonDialog);
        rulesGroup.commit();
        addTextIfPresent(perAppRulesSummaryTexts, rulesGroup.subtitleAt(rulesRow));

        addSectionHeader(getString(R.string.section_scope));
        ScopeSnapshot snap = usableScopeSnapshot();
        buildDeepCompatGuideCard(snap);
        SegmentedGroup scopeGroup = new SegmentedGroup();
        scopeGroup.addActionRow(Glyph.LAYERS, getString(R.string.row_ext_scope_title), getString(R.string.row_ext_scope_sub),
                getString(R.string.chip_manage), false, true, this::showAppScopePickerDialog);
        int requestRow = scopeGroup.addActionRow(Glyph.SHIELD, getString(R.string.action_request_core),
                requestCoreSubtitle(snap.required()),
                getString(R.string.chip_request), false, true, this::requestCore);
        int resetRow = scopeGroup.addActionRow(Glyph.CLEAN, getString(R.string.row_reset_scope_title),
                resetCoreSubtitle(snap.required()),
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

    private String deepCompatStatusSummary(Collection<String> scope, DeepCompatStats stats) {
        if (stats == null) {
            return getString(R.string.picker_loading);
        }
        if (stats.installedCount() == 0) {
            return getString(R.string.deep_compat_status_none);
        }
        if (scope == null) {
            return getString(R.string.deep_compat_status_disconnected, stats.installedCount());
        }
        return getString(R.string.deep_compat_status_connected, stats.installedCount(), stats.enabledCount());
    }

    private void buildDeepCompatGuideCard(ScopeSnapshot snap) {
        boolean compact = isCompactCoverUi();
        List<String> scope = snap != null ? snap.scope() : null;
        DeepCompatStats stats = snap != null ? snap.deepCompatStats() : null;

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

        TextView statusView = createText(deepCompatStatusSummary(scope, stats), 12f, palette.primary, FONT_MEDIUM);
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
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
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
        refreshAsyncSummaries();
    }

    private Set<String> collectPackagesNeedingLabels() {
        LinkedHashSet<String> pkgs = new LinkedHashSet<>();
        for (String target : currentPinnedPackages()) {
            String pkg = ModuleConfig.extractAppTargetPackage(target);
            if (!pkg.isEmpty() && !appLabelCache.containsKey(pkg)) pkgs.add(pkg);
        }
        for (String token : currentQuickActionSlots()) {
            if (token != null && token.startsWith(ModuleConfig.QA_APP_PREFIX)) {
                String pkg = ModuleConfig.extractAppTargetPackage(token.substring(ModuleConfig.QA_APP_PREFIX.length()).trim());
                if (!pkg.isEmpty() && !appLabelCache.containsKey(pkg)) pkgs.add(pkg);
            }
        }
        String tap = ModuleConfig.orbTapAction();
        if (tap.startsWith(ModuleConfig.QA_APP_PREFIX)) {
            String pkg = ModuleConfig.extractAppTargetPackage(tap.substring(ModuleConfig.QA_APP_PREFIX.length()).trim());
            if (!pkg.isEmpty() && !appLabelCache.containsKey(pkg)) pkgs.add(pkg);
        }
        String lng = ModuleConfig.orbLongPressAction();
        if (lng.startsWith(ModuleConfig.QA_APP_PREFIX)) {
            String pkg = ModuleConfig.extractAppTargetPackage(lng.substring(ModuleConfig.QA_APP_PREFIX.length()).trim());
            if (!pkg.isEmpty() && !appLabelCache.containsKey(pkg)) pkgs.add(pkg);
        }
        int shown = 0;
        for (String pkg : ModuleConfig.foldContinuityApps()) {
            if (shown++ >= 4) break;
            if (!pkg.isEmpty() && !appLabelCache.containsKey(pkg)) pkgs.add(pkg);
        }
        return pkgs;
    }

    private void refreshAsyncSummaries() {
        if (isFinishing() || isDestroyed()) return;
        final int gen = ++scopeRefreshGen;
        final XposedService boundService = service;
        final boolean thirdPartyIme = value(ModuleConfig.KEY_THIRD_PARTY_IME);
        final Set<String> labelPackages = collectPackagesNeedingLabels();
        final PackageManager pm = getPackageManager();
        IO.execute(() -> {
            List<String> fetchedScope = null;
            RuntimeException scopeError = null;
            if (boundService != null) {
                try {
                    fetchedScope = new ArrayList<>(boundService.getScope());
                } catch (RuntimeException e) {
                    Log.w(TAG, "async getScope failed", e);
                    scopeError = e;
                }
            }
            LinkedHashSet<String> req = computeRequiredPackages(thirdPartyIme);
            DeepCompatStats stats = queryDeepCompatStats(fetchedScope);
            boolean labelsUpdated = false;
            for (String pkg : labelPackages) {
                String resolved = resolvePackageLabel(pm, pkg);
                if (resolved != null && !resolved.isEmpty()) {
                    appLabelCache.put(pkg, resolved);
                    labelsUpdated = true;
                }
            }
            final List<String> finalScope = fetchedScope;
            final RuntimeException finalError = scopeError;
            final boolean finalLabelsUpdated = labelsUpdated;
            mainHandler.post(() -> {
                if (gen != scopeRefreshGen || isFinishing() || isDestroyed()) return;
                if (finalError != null && service == boundService) {
                    if (cachedService == boundService) cachedService = null;
                    service = null;
                    cachedScopeSnapshot = null;
                    show(getString(R.string.msg_service_died));
                    scheduleRender();
                    return;
                }
                if (service != boundService) return;
                ScopeSnapshot snap = new ScopeSnapshot(boundService, finalScope, req, stats);
                cachedScopeSnapshot = snap;
                if (heroMetaText != null) {
                    heroMetaText.setText(formatHeroMeta(lastRenderedStatus, finalScope));
                }
                if (homeCompatSubtitleText != null) {
                    homeCompatSubtitleText.setText(compatOverviewSubtitle(finalScope, req));
                }
                if (scopeCountTexts.size() == 2) {
                    scopeCountTexts.get(0).setText(requestCoreSubtitle(req));
                    scopeCountTexts.get(1).setText(resetCoreSubtitle(req));
                }
                if (!deepCompatStatusTexts.isEmpty()) {
                    String status = deepCompatStatusSummary(finalScope, stats);
                    for (TextView tv : deepCompatStatusTexts) {
                        if (tv != null) tv.setText(status);
                    }
                }
                if (finalLabelsUpdated) {
                    refreshPinnedSummaryTexts();
                    refreshQuickWidgetSummaryTexts();
                    refreshOrbActionSummaries();
                    refreshFoldContinuitySummaries();
                }
            });
        });
    }

    private String requestCoreSubtitle(LinkedHashSet<String> req) {
        LinkedHashSet<String> target = req != null ? req : new LinkedHashSet<>(CORE_SCOPE);
        return getString(R.string.row_request_core_sub, target.size(), scopeLabels(target));
    }

    private String resetCoreSubtitle(LinkedHashSet<String> req) {
        int size = req != null ? req.size() : CORE_SCOPE.size();
        return getString(R.string.row_reset_scope_sub, size);
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
        row.setStateDescription(sw.getVisibility() == View.VISIBLE ? stateText(sw.isChecked()) : null);
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                if (sw.getVisibility() == View.VISIBLE) {
                    info.setClassName("android.widget.Switch");
                    info.setCheckable(true);
                    info.setChecked(sw.isChecked());
                }
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

    private LinkedHashSet<String> computeRequiredPackages(boolean thirdPartyImeEnabled) {
        LinkedHashSet<String> pkgs = new LinkedHashSet<>(CORE_SCOPE);
        try {
            getPackageManager().getPackageInfo(VOICEASSIST_PKG, 0);
            pkgs.add(VOICEASSIST_PKG);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        if (thirdPartyImeEnabled) {
            pkgs.addAll(installedUnpackedThirdPartyImePackages());
        }
        return pkgs;
    }

    private LinkedHashSet<String> required() {
        return computeRequiredPackages(value(ModuleConfig.KEY_THIRD_PARTY_IME));
    }

    private void requestCore() {
        if (!CoverDisplay.isSupportedFirmware() || service == null) return;
        final XposedService boundService = service;
        final boolean thirdPartyIme = value(ModuleConfig.KEY_THIRD_PARTY_IME);
        IO.execute(() -> {
            LinkedHashSet<String> req = computeRequiredPackages(thirdPartyIme);
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed() || service != boundService) return;
                requestPackages(req);
            });
        });
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
        final XposedService boundService = service;
        final boolean thirdPartyIme = value(ModuleConfig.KEY_THIRD_PARTY_IME);
        final PackageManager pm = getPackageManager();
        IO.execute(() -> {
            List<String> scope = null;
            RuntimeException scopeError = null;
            try {
                scope = new ArrayList<>(boundService.getScope());
            } catch (RuntimeException e) {
                Log.w(TAG, "enableUniversalZeroBlank getScope failed", e);
                scopeError = e;
            }
            List<String> packedInScope = new ArrayList<>();
            LinkedHashSet<String> missing = new LinkedHashSet<>();
            if (scope != null) {
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
                missing.addAll(computeRequiredPackages(thirdPartyIme));
                missing.removeAll(scope);
            }
            final RuntimeException finalError = scopeError;
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed() || service != boundService) return;
                if (finalError != null) {
                    if (cachedService == boundService) cachedService = null;
                    service = null;
                    cachedScopeSnapshot = null;
                    show(getString(R.string.msg_service_died));
                    render(false);
                    return;
                }
                if (!packedInScope.isEmpty()) {
                    removeScopeSafe(packedInScope, R.string.msg_remove_failed);
                }
                if (!missing.isEmpty()) {
                    requestPackages(missing);
                    render(false);
                    return;
                }
                show(getString(R.string.msg_zero_blank_done));
                render(false);
            });
        });
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
        if (!CoverDisplay.isSupportedFirmware() || service == null) return;
        final XposedService boundService = service;
        final boolean thirdPartyIme = value(ModuleConfig.KEY_THIRD_PARTY_IME);
        IO.execute(() -> {
            List<String> scope;
            try {
                scope = new ArrayList<>(boundService.getScope());
            } catch (RuntimeException e) {
                Log.w(TAG, "pruneScopes getScope failed", e);
                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed() || service != boundService) return;
                    if (cachedService == boundService) cachedService = null;
                    service = null;
                    cachedScopeSnapshot = null;
                    show(getString(R.string.msg_service_died));
                    scheduleRender();
                });
                return;
            }
            LinkedHashSet<String> req = computeRequiredPackages(thirdPartyIme);
            List<String> extra = new ArrayList<>(scope);
            extra.removeAll(req);
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed() || service != boundService) return;
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
            });
        });
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
        String rawSlots = ModuleConfig.DEFAULT_QUICK_WIDGET_SLOTS;
        if (prefs != null && service != null) {
            try {
                rawSlots = prefs.getString(
                        ModuleConfig.KEY_QUICK_WIDGET_SLOTS,
                        ModuleConfig.DEFAULT_QUICK_WIDGET_SLOTS);
                prefs.edit()
                        .putBoolean(ModuleConfig.KEY_IMMERSIVE, false)
                        .putBoolean(ModuleConfig.KEY_UNIVERSAL_COMPAT, false)
                        .putBoolean(ModuleConfig.KEY_THIRD_PARTY_IME, false)
                        .putBoolean(ModuleConfig.KEY_QUICK_WIDGET_ENABLED, false)
                        .putInt(ModuleConfig.KEY_FOLD_CONTINUITY_MODE, ModuleConfig.FOLD_CONT_SYSTEM)
                        .commit();
                syncSwitches(ModuleConfig.KEY_IMMERSIVE, false);
                syncSwitches(ModuleConfig.KEY_UNIVERSAL_COMPAT, false);
                syncSwitches(ModuleConfig.KEY_THIRD_PARTY_IME, false);
                syncSwitches(ModuleConfig.KEY_QUICK_WIDGET_ENABLED, false);
                refreshFoldContinuitySummaries();
                refreshScopeTexts();
                if (coverPreview != null) coverPreview.invalidate();
            } catch (RuntimeException e) {
                Log.w(TAG, "failed to commit default preferences before restore", e);
            }
        }
        broadcastQuickWidgetSync(false, rawSlots, true);
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

    static boolean isPublicDiagnosticPackage(String target) {
        if (target == null || target.isEmpty()) return false;
        String pkg = ModuleConfig.extractAppTargetPackage(target);
        if (pkg.isEmpty()) return false;
        if ("system".equals(pkg)
                || "android".equals(pkg)
                || CORE_SCOPE.contains(pkg)
                || VOICEASSIST_PKG.equals(pkg)
                || WETYPE_PKG.equals(pkg)
                || CoverImeGuard.SOGOU_PKG.equals(pkg)
                || DEEP_COMPAT_SPECS.containsKey(pkg)
                || BuildConfig.APPLICATION_ID.equals(pkg)) {
            return true;
        }
        return pkg.startsWith("com.android.")
                || pkg.startsWith("com.miui.")
                || pkg.startsWith("com.xiaomi.")
                || pkg.startsWith("android.");
    }

    static String formatDiagnosticPackageList(Collection<String> pkgs, boolean redact) {
        if (pkgs == null || pkgs.isEmpty()) return "[]";
        if (!redact) return new ArrayList<>(pkgs).toString();
        List<String> out = new ArrayList<>();
        int redactedCount = 0;
        for (String item : pkgs) {
            if (item == null || item.isEmpty()) continue;
            if (isPublicDiagnosticPackage(item)) {
                out.add(item);
            } else {
                redactedCount++;
            }
        }
        if (redactedCount > 0) {
            out.add("<redacted:" + redactedCount + ">");
        }
        return out.toString();
    }

    static String formatDiagnosticActionToken(String token, boolean redact) {
        if (!redact || token == null || !token.startsWith(ModuleConfig.QA_APP_PREFIX)) {
            return token;
        }
        String target = token.substring(ModuleConfig.QA_APP_PREFIX.length()).trim();
        if (isPublicDiagnosticPackage(target)) {
            return token;
        }
        int userId = ModuleConfig.extractAppTargetUserId(target);
        return ModuleConfig.QA_APP_PREFIX + (userId > 0 ? "<redacted>#" + userId : "<redacted>");
    }

    static String formatDiagnosticActionList(List<String> slots, boolean redact) {
        if (slots == null || slots.isEmpty()) return "[]";
        if (!redact) return new ArrayList<>(slots).toString();
        List<String> out = new ArrayList<>(slots.size());
        for (String slot : slots) {
            out.add(formatDiagnosticActionToken(slot, true));
        }
        return out.toString();
    }

    static String redactDiagnosticLogLine(String line, Collection<String> personalPackages) {
        if (line == null || line.isEmpty() || personalPackages == null || personalPackages.isEmpty()) {
            return line;
        }
        String out = line;
        for (String pkg : personalPackages) {
            if (pkg != null && pkg.length() >= 4 && pkg.contains(".") && out.contains(pkg)) {
                out = out.replace(pkg, "<redacted-pkg>");
            }
        }
        return out;
    }

    private List<String> collectPersonalDiagnosticPackages(List<String> scope) {
        LinkedHashSet<String> personal = new LinkedHashSet<>();
        Consumer<String> addIfPersonal = raw -> {
            if (raw == null || raw.isEmpty()) return;
            String pkg = ModuleConfig.extractAppTargetPackage(raw);
            if (!pkg.isEmpty() && pkg.contains(".") && !isPublicDiagnosticPackage(pkg)) {
                personal.add(pkg);
            }
        };
        if (scope != null) {
            for (String p : scope) addIfPersonal.accept(p);
        }
        for (String p : currentPinnedPackages()) addIfPersonal.accept(p);
        for (String slot : currentQuickActionSlots()) {
            if (slot != null && slot.startsWith(ModuleConfig.QA_APP_PREFIX)) {
                addIfPersonal.accept(slot.substring(ModuleConfig.QA_APP_PREFIX.length()).trim());
            }
        }
        for (String p : ModuleConfig.foldContinuityApps()) addIfPersonal.accept(p);
        String tap = ModuleConfig.orbTapAction();
        if (tap.startsWith(ModuleConfig.QA_APP_PREFIX)) {
            addIfPersonal.accept(tap.substring(ModuleConfig.QA_APP_PREFIX.length()).trim());
        }
        String lng = ModuleConfig.orbLongPressAction();
        if (lng.startsWith(ModuleConfig.QA_APP_PREFIX)) {
            addIfPersonal.accept(lng.substring(ModuleConfig.QA_APP_PREFIX.length()).trim());
        }
        for (String p : ModuleConfig.perAppRules().keySet()) addIfPersonal.accept(p);
        List<String> sorted = new ArrayList<>(personal);
        sorted.sort((a, b) -> Integer.compare(b.length(), a.length()));
        return sorted;
    }

    private void showDiagnosticLogsDialog() {
        boolean compact = isCompactCoverUi();
        int safeHeight = currentSafeWindowFrame().safeRect().height();
        boolean compactHeight = safeHeight < dp(420);
        boolean ultraCompactHeight = safeHeight < dp(320);

        Dialog dialog = trackDialog(new Dialog(this));
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        int padH = compact ? dp(10) : dp(18);
        int padV = dp(ultraCompactHeight ? 8 : (compactHeight ? 12 : 16));
        sheet.setPadding(padH, padV, padH, padV);
        sheet.setBackground(rounded(palette.surfaceContainerLow, ultraCompactHeight ? 22f : 28f));

        if (!compactHeight) {
            View handle = new View(this);
            handle.setBackground(rounded(palette.outlineVariant, 2f));
            LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(dp(36), dp(4));
            handleLp.gravity = Gravity.CENTER_HORIZONTAL;
            handleLp.bottomMargin = dp(10);
            sheet.addView(handle, handleLp);
        }

        TextView titleView = createText(
                getString(R.string.dialog_diag_log_title),
                ultraCompactHeight ? 15f : (compact ? 18f : 20f),
                palette.onSurface,
                FONT_BOLD);
        titleView.setAccessibilityHeading(true);
        sheet.addView(titleView);

        TextView subtitleView = createText(
                getString(R.string.dialog_diag_log_sub, getString(R.string.app_name), 0),
                ultraCompactHeight ? 11f : 12.5f, palette.onSurfaceVariant, FONT_REGULAR);
        subtitleView.setPadding(0, dp(3), 0, dp(ultraCompactHeight ? 6 : 8));
        sheet.addView(subtitleView);

        // Top action bar arranged as a clean 2x2 grid (>= 44dp touch height)
        LinearLayout topActions = new LinearLayout(this);
        topActions.setOrientation(LinearLayout.VERTICAL);
        topActions.setPadding(0, 0, 0, dp(8));

        int actionBtnH = dp(44);
        TextView btnRefresh = createDialogChipButton(getString(R.string.btn_refresh_log), false);
        TextView btnClear = createDialogChipButton(getString(R.string.btn_clear_log), false);
        TextView btnCopy = createDialogChipButton(getString(R.string.btn_copy_all), false);
        TextView btnShare = createDialogChipButton(getString(R.string.btn_share_log), true);

        LinearLayout actionRow1 = new LinearLayout(this);
        actionRow1.setOrientation(LinearLayout.HORIZONTAL);
        actionRow1.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams row1LeftLp = new LinearLayout.LayoutParams(0, actionBtnH, 1f);
        row1LeftLp.rightMargin = dp(8);
        actionRow1.addView(btnRefresh, row1LeftLp);
        actionRow1.addView(btnClear, new LinearLayout.LayoutParams(0, actionBtnH, 1f));
        LinearLayout.LayoutParams row1Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row1Lp.bottomMargin = dp(6);
        topActions.addView(actionRow1, row1Lp);

        LinearLayout actionRow2 = new LinearLayout(this);
        actionRow2.setOrientation(LinearLayout.HORIZONTAL);
        actionRow2.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams row2LeftLp = new LinearLayout.LayoutParams(0, actionBtnH, 1f);
        row2LeftLp.rightMargin = dp(8);
        actionRow2.addView(btnCopy, row2LeftLp);
        actionRow2.addView(btnShare, new LinearLayout.LayoutParams(0, actionBtnH, 1f));
        topActions.addView(actionRow2, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sheet.addView(topActions);

        // Privacy redaction toggle (ON by default)
        final boolean[] redactPackages = {true};
        final Runnable[] rebuildViewRef = new Runnable[1];

        LinearLayout redactRow = new LinearLayout(this);
        redactRow.setOrientation(LinearLayout.HORIZONTAL);
        redactRow.setGravity(Gravity.CENTER_VERTICAL);
        redactRow.setMinimumHeight(dp(44));
        redactRow.setPadding(dp(12), dp(6), dp(12), dp(6));
        redactRow.setBackground(ripple(palette.surfaceContainer, palette.rippleColor, 16f, 16f, 16f, 16f, 0, 0));
        redactRow.setClickable(true);
        redactRow.setFocusable(true);

        LinearLayout redactTextCol = new LinearLayout(this);
        redactTextCol.setOrientation(LinearLayout.VERTICAL);
        redactTextCol.setPadding(0, 0, dp(8), 0);
        TextView redactTitle = createText(
                getString(R.string.diag_log_redact_title),
                compact ? 12.5f : 13.5f,
                palette.onSurface,
                FONT_MEDIUM);
        redactTitle.setSingleLine(true);
        redactTitle.setEllipsize(TextUtils.TruncateAt.END);
        redactTextCol.addView(redactTitle);
        if (!ultraCompactHeight) {
            TextView redactSub = createText(
                    getString(R.string.diag_log_redact_sub),
                    11f,
                    palette.onSurfaceVariant,
                    FONT_REGULAR);
            redactSub.setMaxLines(2);
            redactSub.setEllipsize(TextUtils.TruncateAt.END);
            redactSub.setPadding(0, dp(1), 0, 0);
            redactTextCol.addView(redactSub);
        }
        redactRow.addView(redactTextCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        M3Switch redactSwitch = new M3Switch(this, true);
        redactSwitch.setContentDescription(getString(R.string.diag_log_redact_title));
        redactRow.addView(redactSwitch, new LinearLayout.LayoutParams(dp(52), dp(32)));
        bindSwitchRow(redactRow, redactSwitch);
        redactSwitch.setOnCheckedChangeListener(checked -> {
            redactPackages[0] = checked;
            if (rebuildViewRef[0] != null) rebuildViewRef[0].run();
        });
        redactRow.setOnClickListener(v -> redactSwitch.toggleChecked());

        LinearLayout.LayoutParams redactLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        redactLp.bottomMargin = dp(8);
        sheet.addView(redactRow, redactLp);

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

        // Bottom close button (>= 44dp touch height)
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.END);
        footer.setPadding(0, dp(10), 0, 0);

        TextView closeBtn = createText(getString(R.string.btn_close), 14f, palette.onSecondaryContainer, FONT_MEDIUM);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setSingleLine(true);
        closeBtn.setEllipsize(TextUtils.TruncateAt.END);
        closeBtn.setPadding(dp(22), 0, dp(22), 0);
        closeBtn.setMinHeight(dp(44));
        closeBtn.setBackground(pillRipple(palette.secondaryContainer, palette.rippleColor, 22f));
        closeBtn.setOnClickListener(v -> dialog.dismiss());
        footer.addView(closeBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        sheet.addView(footer);

        final Map<String, List<String>> processBuffers = new LinkedHashMap<>();
        final LinkedHashSet<String> respondedSources = new LinkedHashSet<>();
        final List<String> logcatLines = new ArrayList<>();
        final ScopeSnapshot initialSnap = usableScopeSnapshot();
        @SuppressWarnings("unchecked")
        final List<String>[] collectedScope = new List[]{initialSnap.scope()};
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

            boolean redact = redactPackages[0];
            List<String> personalPkgs = redact ? collectPersonalDiagnosticPackages(collectedScope[0]) : List.of();

            StringBuilder sb = new StringBuilder();
            sb.append(buildDiagnosticHeader(collectedScope[0], redact));
            sb.append("RespondedProcesses: ").append(sourcesToFormat).append('\n');
            sb.append("\n=== Runtime Logs (").append(count).append(") ===\n");
            if (mergedLogs.isEmpty()) {
                sb.append(getString(R.string.dialog_diag_log_empty)).append('\n');
            } else {
                for (String line : mergedLogs) {
                    sb.append(redact ? redactDiagnosticLogLine(line, personalPkgs) : line).append('\n');
                }
            }
            currentFullText[0] = sb.toString();
            logTextView.setText(currentFullText[0]);
        };
        rebuildViewRef[0] = rebuildView;

        Runnable triggerCollect = () -> {
            final int gen = ++collectGen[0];
            final XposedService boundService = service;
            processBuffers.clear();
            respondedSources.clear();
            logcatLines.clear();
            respondedSources.add("settings");
            processBuffers.put("settings", ModLog.snapshot());
            rebuildView.run();

            IO.execute(() -> {
                List<String> freshScope = null;
                if (boundService != null) {
                    try {
                        freshScope = new ArrayList<>(boundService.getScope());
                    } catch (RuntimeException ignored) {
                    }
                }
                ArrayList<String> localLogcat = ModLog.readFilteredLogcat(200);
                final List<String> resolvedScope = freshScope;
                mainHandler.post(() -> {
                    if (gen != collectGen[0] || isFinishing() || isDestroyed() || !dialog.isShowing()) return;
                    if (resolvedScope != null) collectedScope[0] = resolvedScope;
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
        btn.setMinHeight(dp(44));
        btn.setPadding(dp(8), 0, dp(8), 0);
        btn.setBackground(pillRipple(
                primary ? palette.primary : palette.secondaryContainer,
                palette.rippleColor,
                20f));
        btn.setClickable(true);
        btn.setFocusable(true);
        return btn;
    }

    private String buildDiagnosticHeader(List<String> scope, boolean redact) {
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
        sb.append("PrivacyRedaction: ").append(redact ? "on" : "off").append('\n');
        sb.append("LSPosed: ").append(service != null ? "connected" : "disconnected")
                .append(" · scopes=").append(formatDiagnosticPackageList(scope, redact)).append('\n');
        sb.append("Switches: ").append(currentState().toString()).append('\n');
        sb.append("PinnedApps: ").append(formatDiagnosticPackageList(currentPinnedPackages(), redact)).append('\n');
        sb.append("QuickActions: enabled=").append(value(ModuleConfig.KEY_QUICK_WIDGET_ENABLED))
                .append(" slots=").append(formatDiagnosticActionList(currentQuickActionSlots(), redact)).append('\n');
        sb.append("FoldContinuity: mode=").append(ModuleConfig.foldContinuityMode())
                .append(" apps=").append(ModuleConfig.foldContinuityApps().size())
                .append(" pocketGuard=").append(ModuleConfig.foldPocketGuardEnabled()).append('\n');
        sb.append("OrbActions: tap=").append(formatDiagnosticActionToken(ModuleConfig.orbTapAction(), redact))
                .append(" longPress=").append(formatDiagnosticActionToken(ModuleConfig.orbLongPressAction(), redact)).append('\n');
        sb.append("ImeTuning: scale=").append(ModuleConfig.imeDpiScalePercent())
                .append("% height=").append(ModuleConfig.imeMaxHeightPercent())
                .append("% inset=").append(ModuleConfig.imeTopInsetPx()).append('\n');
        sb.append("PerAppRules: count=").append(ModuleConfig.perAppRules().size()).append('\n');
        return sb.toString();
    }

    // ==================== App Scope Manager Sheet / Dialog ====================

    private static final class AppEntry {
        final String label;
        final String labelLower;
        final String packageName;
        final int userId;
        final String targetKey;
        final boolean systemApp;
        final boolean inScope;
        final boolean uninstalled;
        final String compatSummary;
        final String compatSummaryLower;
        /** ResolveInfo, LauncherActivityInfo, or InputMethodInfo used to load the icon lazily. */
        final Object iconSource;
        boolean selected;

        AppEntry(String label, String packageName, boolean systemApp, boolean inScope, Object iconSource) {
            this(label, packageName, systemApp, inScope, "", iconSource, 0, false);
        }

        AppEntry(String label, String packageName, boolean systemApp, boolean inScope,
                 String compatSummary, Object iconSource) {
            this(label, packageName, systemApp, inScope, compatSummary, iconSource, 0, false);
        }

        AppEntry(String label, String packageName, boolean systemApp, boolean inScope,
                 String compatSummary, Object iconSource, int userId, boolean uninstalled) {
            this.label = label;
            this.labelLower = label.toLowerCase(Locale.ROOT);
            this.packageName = packageName;
            this.userId = Math.max(0, userId);
            this.targetKey = ModuleConfig.formatAppTarget(packageName, this.userId);
            this.systemApp = systemApp;
            this.inScope = inScope;
            this.uninstalled = uninstalled;
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
        String cacheKey = app.targetKey;
        target.setTag(cacheKey);
        if (app.uninstalled) {
            target.setImageDrawable(null);
            return;
        }
        Drawable cached = iconCache.get(cacheKey);
        if (cached != null) {
            target.setImageDrawable(iconCopy(cached));
            return;
        }
        target.setImageDrawable(null);
        if (!iconLoading.add(cacheKey)) return;
        PackageManager pm = getPackageManager();
        LauncherApps launcherApps = getSystemService(LauncherApps.class);
        WeakReference<ImageView> targetRef = new WeakReference<>(target);
        IO.execute(() -> {
            Drawable d = null;
            try {
                if (app.iconSource instanceof LauncherActivityInfo lai) d = lai.getBadgedIcon(0);
                else if (app.iconSource instanceof ResolveInfo ri) d = ri.loadIcon(pm);
                else if (app.iconSource instanceof InputMethodInfo imi) d = imi.loadIcon(pm);
                else d = loadAppIconSync(pm, launcherApps, app.packageName, app.userId);
            } catch (Throwable ignored) {
            }
            final Drawable icon = d;
            mainHandler.post(() -> {
                iconLoading.remove(cacheKey);
                if (icon == null || isDestroyed()) return;
                iconCache.put(cacheKey, icon);
                ImageView view = targetRef.get();
                if (view != null && cacheKey.equals(view.getTag())) view.setImageDrawable(iconCopy(icon));
            });
        });
    }

    private void bindTargetIcon(String targetKey, ImageView target) {
        if (targetKey == null || targetKey.isEmpty()) {
            target.setTag(null);
            target.setImageDrawable(null);
            return;
        }
        target.setTag(targetKey);
        Drawable cached = iconCache.get(targetKey);
        if (cached != null) {
            target.setImageDrawable(iconCopy(cached));
            return;
        }
        target.setImageDrawable(null);
        if (!iconLoading.add(targetKey)) return;
        String pkg = ModuleConfig.extractAppTargetPackage(targetKey);
        int userId = ModuleConfig.extractAppTargetUserId(targetKey);
        PackageManager pm = getPackageManager();
        LauncherApps launcherApps = getSystemService(LauncherApps.class);
        WeakReference<ImageView> targetRef = new WeakReference<>(target);
        IO.execute(() -> {
            Drawable d = loadAppIconSync(pm, launcherApps, pkg, userId);
            final Drawable icon = d;
            mainHandler.post(() -> {
                iconLoading.remove(targetKey);
                if (icon == null || isDestroyed()) return;
                iconCache.put(targetKey, icon);
                ImageView view = targetRef.get();
                if (view != null && targetKey.equals(view.getTag())) view.setImageDrawable(iconCopy(icon));
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
        final boolean hasSwitch;
        int shape = -1;
        AppEntry entry;

        AppRowHolder() {
            this(true);
        }

        AppRowHolder(boolean hasSwitch) {
            this.hasSwitch = hasSwitch;
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
            if (hasSwitch) {
                bindSwitchRow(row, sw);
            } else {
                sw.setVisibility(View.GONE);
                sw.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                row.setStateDescription(null);
            }
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
                tag.setVisibility(View.GONE);
                compatDesc.setVisibility(View.GONE);
                if (app.uninstalled) {
                    deepTag.setText(R.string.tag_uninstalled);
                    deepTag.setTextColor(palette.onErrorContainer);
                    deepTag.setBackground(rounded(palette.errorContainer, 8f));
                    deepTag.setVisibility(View.VISIBLE);
                } else if (app.userId > 0) {
                    deepTag.setText(R.string.tag_dual_app);
                    deepTag.setTextColor(palette.onSecondaryContainer);
                    deepTag.setBackground(rounded(palette.secondaryContainer, 8f));
                    deepTag.setVisibility(View.VISIBLE);
                } else {
                    deepTag.setVisibility(View.GONE);
                }
            }
            syncBadgeRow();
            pkg.setText(app.targetKey);
            if (hasSwitch) {
                sw.setContentDescription(app.label);
                sw.jumpTo(app.selected);
            }
            bindIcon(app, icon);
        }
    }

    private final class AppListAdapter extends BaseAdapter {
        final List<AppEntry> items = new ArrayList<>();
        private final boolean showCompatMeta;

        AppListAdapter() {
            this(true);
        }

        AppListAdapter(boolean showCompatMeta) {
            this.showCompatMeta = showCompatMeta;
        }

        @Override public int getCount() { return items.size(); }
        @Override public AppEntry getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            AppRowHolder holder;
            if (convertView != null && convertView.getTag() instanceof AppRowHolder h) {
                holder = h;
            } else {
                holder = new AppRowHolder(true);
            }
            holder.bind(items.get(position), position, items.size(), showCompatMeta);
            return holder.row;
        }
    }

    private record PruneBannerHolder(LinearLayout container, TextView message) {
        void update(Context ctx, int uninstalledCount) {
            if (uninstalledCount > 0) {
                message.setText(ctx.getString(R.string.banner_uninstalled_found, uninstalledCount));
                container.setVisibility(View.VISIBLE);
            } else {
                container.setVisibility(View.GONE);
            }
        }
    }

    private PruneBannerHolder createUninstalledPruneBanner(boolean compact, boolean ultraCompactHeight, Runnable onPrune) {
        LinearLayout banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(dp(compact ? 10 : 12), dp(ultraCompactHeight ? 4 : 6),
                dp(compact ? 8 : 10), dp(ultraCompactHeight ? 4 : 6));
        banner.setBackground(rounded(palette.errorContainer, 14f));
        banner.setVisibility(View.GONE);

        TextView msg = createText("", ultraCompactHeight ? 11f : 12f, palette.onErrorContainer, FONT_MEDIUM);
        msg.setSingleLine(true);
        msg.setEllipsize(TextUtils.TruncateAt.END);
        banner.addView(msg, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView btn = createText(getString(R.string.btn_prune_uninstalled),
                ultraCompactHeight ? 11.5f : 12.5f, palette.onErrorContainer, FONT_BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setSingleLine(true);
        btn.setEllipsize(TextUtils.TruncateAt.END);
        btn.setMinHeight(dp(ultraCompactHeight ? 36 : 44));
        btn.setPadding(dp(10), 0, dp(10), 0);
        btn.setBackground(pillRipple(
                M3Palette.blend(palette.errorContainer, palette.onErrorContainer, 0.14f),
                palette.rippleColor,
                16f));
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setOnClickListener(v -> {
            if (onPrune != null) onPrune.run();
        });
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.leftMargin = dp(6);
        banner.addView(btn, btnLp);
        return new PruneBannerHolder(banner, msg);
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
        if (!CoverDisplay.isSupportedFirmware() || service == null) return;
        final XposedService boundService = service;
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
            Set<String> coreReq = usableScopeSnapshot().required();
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

        // Load scope + app list off the main thread.
        Context appCtx = this;
        PackageManager pm = getPackageManager();
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        IO.execute(() -> {
            Set<String> currentScope;
            try {
                currentScope = new HashSet<>(boundService.getScope());
            } catch (RuntimeException e) {
                Log.w(TAG, "failed to query scope for picker", e);
                mainHandler.post(() -> {
                    if (dialog.isShowing()) dialog.dismiss();
                    if (!isFinishing() && !isDestroyed() && service == boundService) {
                        if (cachedService == boundService) cachedService = null;
                        service = null;
                        cachedScopeSnapshot = null;
                        show(getString(R.string.msg_service_died));
                        scheduleRender();
                    }
                });
                return;
            }
            List<AppEntry> loaded;
            try {
                loaded = loadLaunchableApps(appCtx, pm, imm, currentScope);
                for (AppEntry e : loaded) appLabelCache.put(e.packageName, e.label);
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
        List<String> targets = currentPinnedPackages();
        if (targets.isEmpty()) {
            return getString(R.string.row_pinned_widget_apps_empty);
        }
        String sep = getString(R.string.list_separator);
        StringBuilder names = new StringBuilder();
        for (String target : targets) {
            if (names.length() > 0) names.append(sep);
            names.append(formatAppTargetLabel(target));
        }
        return getString(R.string.row_pinned_widget_apps_summary, targets.size(), names.toString());
    }

    /** Loads launchable launcher apps (except FlipHome itself), optionally including Dual-App (u999) profiles. */
    private static List<AppEntry> loadLaunchableAppsForWidget(
            PackageManager pm, LauncherApps launcherApps, List<String> pinnedOrder, boolean includeDualApps) {
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = pm.queryIntentActivities(mainIntent, 0);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        Set<String> pinnedSet = new HashSet<>(pinnedOrder);
        List<AppEntry> entries = new ArrayList<>();
        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null) continue;
            String pkg = info.activityInfo.packageName;
            if (pkg == null || pkg.isEmpty() || "com.miui.fliphome".equals(pkg)) continue;
            String target = ModuleConfig.formatAppTarget(pkg, 0);
            if (!seen.add(target)) continue;
            String label = String.valueOf(info.loadLabel(pm));
            entries.add(new AppEntry(label, pkg, isSystemApp(info.activityInfo.applicationInfo),
                    pinnedSet.contains(target), "", info, 0, false));
        }
        if (includeDualApps && launcherApps != null) {
            for (UserHandle uh : querySecondaryUserHandles(launcherApps)) {
                int uid = extractUserId(uh);
                if (uid <= 0) continue;
                try {
                    List<LauncherActivityInfo> activities = launcherApps.getActivityList(null, uh);
                    if (activities == null) continue;
                    for (LauncherActivityInfo lai : activities) {
                        if (lai == null) continue;
                        ApplicationInfo ai = lai.getApplicationInfo();
                        String pkg = ai != null ? ai.packageName
                                : (lai.getComponentName() != null ? lai.getComponentName().getPackageName() : null);
                        if (pkg == null || pkg.isEmpty() || "com.miui.fliphome".equals(pkg)) continue;
                        String target = ModuleConfig.formatAppTarget(pkg, uid);
                        if (!seen.add(target)) continue;
                        CharSequence rawLbl = lai.getLabel();
                        String label = (rawLbl != null && rawLbl.length() > 0) ? rawLbl.toString() : pkg;
                        entries.add(new AppEntry(label, pkg, isSystemApp(ai),
                                pinnedSet.contains(target), "", lai, uid, false));
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        entries.sort(Comparator
                .comparing((AppEntry a) -> {
                    int idx = pinnedOrder.indexOf(a.targetKey);
                    return idx >= 0 ? idx : Integer.MAX_VALUE;
                })
                .thenComparing(a -> a.systemApp)
                .thenComparing(a -> a.label, String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(a -> a.userId));
        return entries;
    }

    private static List<AppEntry> loadLaunchableAppsForWidget(PackageManager pm, List<String> pinnedOrder) {
        return loadLaunchableAppsForWidget(pm, null, pinnedOrder, false);
    }

    private void showPinnedWidgetAppsPickerDialog() {
        if (!CoverDisplay.isSupportedFirmware() || prefs == null || service == null) return;
        final List<String> selectedOrder = new ArrayList<>(currentPinnedPackages());
        final int[] focusedSlot = {selectedOrder.isEmpty() ? -1 : 0};
        final Set<String> uninstalledTargets = new LinkedHashSet<>();

        boolean compact = isCompactCoverUi();
        boolean narrowFooter = isNarrowDialogFooter();
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
            handleLp.bottomMargin = dp(12);
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
            sheetSub.setPadding(0, dp(4), 0, dp(8));
        }
        sheet.addView(sheetSub);

        final Map<String, AppEntry> entryByTarget = new HashMap<>();
        final List<AppEntry> allApps = new ArrayList<>();
        final Runnable[] refreshSlotsAndListRef = new Runnable[1];
        final Runnable[] refilterRef = new Runnable[1];

        // 1-tap prune banner for uninstalled pinned apps
        final PruneBannerHolder[] pruneBannerRef = new PruneBannerHolder[1];
        pruneBannerRef[0] = createUninstalledPruneBanner(compact, ultraCompactHeight, () -> {
            int removed = 0;
            for (String missing : new ArrayList<>(uninstalledTargets)) {
                if (selectedOrder.remove(missing)) removed++;
                AppEntry e = entryByTarget.remove(missing);
                if (e != null) allApps.remove(e);
            }
            uninstalledTargets.clear();
            pruneBannerRef[0].update(this, 0);
            if (focusedSlot[0] >= selectedOrder.size()) {
                focusedSlot[0] = selectedOrder.size() - 1;
            }
            if (refilterRef[0] != null) refilterRef[0].run();
            if (removed > 0) show(getString(R.string.msg_uninstalled_pruned, removed));
        });
        LinearLayout.LayoutParams pruneLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pruneLp.bottomMargin = dp(ultraCompactHeight ? 3 : 6);
        sheet.addView(pruneBannerRef[0].container(), pruneLp);

        // Live 4-Slot Preview Strip (tap a filled slot to select for reorder/remove)
        LinearLayout slotsBar = new LinearLayout(this);
        slotsBar.setOrientation(LinearLayout.HORIZONTAL);
        slotsBar.setGravity(Gravity.CENTER);
        int slotsPad = dp(ultraCompactHeight ? 3 : 8);
        slotsBar.setPadding(slotsPad, slotsPad, slotsPad, slotsPad);
        slotsBar.setBackground(rounded(palette.surfaceContainerHigh, ultraCompactHeight ? 14f : 20f));
        LinearLayout.LayoutParams slotsBarLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slotsBarLp.bottomMargin = dp(ultraCompactHeight ? 3 : 6);
        sheet.addView(slotsBar, slotsBarLp);

        final ImageView[] slotIcons = new ImageView[ModuleConfig.MAX_PINNED_WIDGET_APPS];
        final TextView[] slotLabels = new TextView[ModuleConfig.MAX_PINNED_WIDGET_APPS];
        final LinearLayout[] slotCells = new LinearLayout[ModuleConfig.MAX_PINNED_WIDGET_APPS];

        for (int i = 0; i < ModuleConfig.MAX_PINNED_WIDGET_APPS; i++) {
            final int slotIdx = i;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(ultraCompactHeight ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            int cellPadV = dp(ultraCompactHeight ? 2 : 6);
            cell.setPadding(dp(4), cellPadV, dp(4), cellPadV);
            cell.setBackground(rounded(palette.surfaceContainer, ultraCompactHeight ? 10f : 14f));
            cell.setClickable(true);
            cell.setFocusable(true);

            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int slotIconSize = dp(ultraCompactHeight ? 18 : 32);
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
                    focusedSlot[0] = slotIdx;
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

        // Slot Reorder Bar (↑ Up | ↓ Down | Remove)
        LinearLayout reorderBar = new LinearLayout(this);
        reorderBar.setOrientation(LinearLayout.HORIZONTAL);
        reorderBar.setGravity(Gravity.CENTER_VERTICAL);
        int reorderBtnH = dp(ultraCompactHeight ? 36 : 44);
        TextView btnMoveUp = createDialogChipButton(getString(R.string.btn_slot_move_up), false);
        btnMoveUp.setMinHeight(reorderBtnH);
        btnMoveUp.setContentDescription(getString(R.string.a11y_slot_move_prev));
        TextView btnMoveDown = createDialogChipButton(getString(R.string.btn_slot_move_down), false);
        btnMoveDown.setMinHeight(reorderBtnH);
        btnMoveDown.setContentDescription(getString(R.string.a11y_slot_move_next));
        TextView btnRemoveSlot = createDialogChipButton(getString(R.string.btn_slot_remove), false);
        btnRemoveSlot.setMinHeight(reorderBtnH);

        LinearLayout.LayoutParams rBtn1Lp = new LinearLayout.LayoutParams(0, reorderBtnH, 1f);
        rBtn1Lp.rightMargin = dp(6);
        reorderBar.addView(btnMoveUp, rBtn1Lp);
        LinearLayout.LayoutParams rBtn2Lp = new LinearLayout.LayoutParams(0, reorderBtnH, 1f);
        rBtn2Lp.rightMargin = dp(6);
        reorderBar.addView(btnMoveDown, rBtn2Lp);
        reorderBar.addView(btnRemoveSlot, new LinearLayout.LayoutParams(0, reorderBtnH, 1f));

        btnMoveUp.setOnClickListener(v -> {
            int idx = focusedSlot[0];
            if (idx > 0 && idx < selectedOrder.size()) {
                Collections.swap(selectedOrder, idx, idx - 1);
                focusedSlot[0] = idx - 1;
                if (refreshSlotsAndListRef[0] != null) refreshSlotsAndListRef[0].run();
            }
        });
        btnMoveDown.setOnClickListener(v -> {
            int idx = focusedSlot[0];
            if (idx >= 0 && idx < selectedOrder.size() - 1) {
                Collections.swap(selectedOrder, idx, idx + 1);
                focusedSlot[0] = idx + 1;
                if (refreshSlotsAndListRef[0] != null) refreshSlotsAndListRef[0].run();
            }
        });
        btnRemoveSlot.setOnClickListener(v -> {
            int idx = focusedSlot[0];
            if (idx >= 0 && idx < selectedOrder.size()) {
                String removedTarget = selectedOrder.remove(idx);
                AppEntry e = entryByTarget.get(removedTarget);
                if (e != null) e.selected = false;
                uninstalledTargets.remove(removedTarget);
                pruneBannerRef[0].update(this, uninstalledTargets.size());
                if (focusedSlot[0] >= selectedOrder.size()) focusedSlot[0] = selectedOrder.size() - 1;
                if (refreshSlotsAndListRef[0] != null) refreshSlotsAndListRef[0].run();
            }
        });

        LinearLayout.LayoutParams reorderBarLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        reorderBarLp.bottomMargin = dp(ultraCompactHeight ? 3 : 6);
        sheet.addView(reorderBar, reorderBarLp);

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
                    holder = new AppRowHolder(true);
                }
                AppEntry app = adapterItems.get(position);
                holder.bind(app, position, adapterItems.size(), false);
                int orderIdx = selectedOrder.indexOf(app.targetKey);
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
            focusedSlot[0] = -1;
            uninstalledTargets.clear();
            allApps.removeIf(e -> e.uninstalled);
            pruneBannerRef[0].update(this, 0);
            for (AppEntry e : allApps) e.selected = false;
            if (refilterRef[0] != null) refilterRef[0].run();
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
            if (selectedOrder.isEmpty()) {
                focusedSlot[0] = -1;
            } else if (focusedSlot[0] < 0 || focusedSlot[0] >= selectedOrder.size()) {
                focusedSlot[0] = 0;
            }
            for (int i = 0; i < ModuleConfig.MAX_PINNED_WIDGET_APPS; i++) {
                if (i < selectedOrder.size()) {
                    String target = selectedOrder.get(i);
                    AppEntry entry = entryByTarget.get(target);
                    boolean isMissing = uninstalledTargets.contains(target);
                    boolean isFocused = (i == focusedSlot[0]);
                    String baseLabel = entry != null
                            ? (entry.userId > 0 ? getString(R.string.dual_app_name_fmt, entry.label) : entry.label)
                            : formatAppTargetLabel(target);
                    slotLabels[i].setText(getString(R.string.slot_numbered_fmt, i + 1, baseLabel));
                    int fill = isMissing
                            ? palette.errorContainer
                            : (isFocused ? palette.primaryContainer : palette.secondaryContainer);
                    int fg = isMissing
                            ? palette.onErrorContainer
                            : (isFocused ? palette.onPrimaryContainer : palette.onSurface);
                    int strokeW = isFocused ? dp(2) : 0;
                    int strokeC = isMissing ? palette.onErrorContainer : palette.primary;
                    slotLabels[i].setTextColor(fg);
                    slotCells[i].setBackground(createShape(fill, slotRadius, slotRadius, slotRadius, slotRadius, strokeW, strokeC));
                    if (entry != null) {
                        bindIcon(entry, slotIcons[i]);
                    } else if (!isMissing) {
                        bindTargetIcon(target, slotIcons[i]);
                    } else {
                        slotIcons[i].setTag(null);
                        slotIcons[i].setImageDrawable(null);
                    }
                } else {
                    slotIcons[i].setTag(null);
                    slotIcons[i].setImageDrawable(null);
                    slotLabels[i].setText(getString(R.string.picker_pinned_slot_empty, i + 1));
                    slotLabels[i].setTextColor(palette.onSurfaceVariant);
                    slotCells[i].setBackground(rounded(palette.surfaceContainer, slotRadius));
                }
            }
            boolean hasSelection = !selectedOrder.isEmpty() && focusedSlot[0] >= 0;
            reorderBar.setVisibility(hasSelection ? View.VISIBLE : View.GONE);
            if (hasSelection) {
                boolean canUp = focusedSlot[0] > 0;
                boolean canDown = focusedSlot[0] < selectedOrder.size() - 1;
                btnMoveUp.setEnabled(canUp);
                btnMoveUp.setAlpha(canUp ? 1f : 0.4f);
                btnMoveDown.setEnabled(canDown);
                btnMoveDown.setAlpha(canDown ? 1f : 0.4f);
                btnRemoveSlot.setEnabled(true);
                btnRemoveSlot.setAlpha(1f);
            }
            pinnedAdapter.notifyDataSetChanged();
        };

        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= adapterItems.size()) return;
            AppEntry app = adapterItems.get(position);
            if (selectedOrder.contains(app.targetKey)) {
                int removedIdx = selectedOrder.indexOf(app.targetKey);
                selectedOrder.remove(app.targetKey);
                app.selected = false;
                if (uninstalledTargets.remove(app.targetKey)) {
                    pruneBannerRef[0].update(this, uninstalledTargets.size());
                }
                if (focusedSlot[0] >= selectedOrder.size()) focusedSlot[0] = selectedOrder.size() - 1;
                else if (removedIdx >= 0 && removedIdx < focusedSlot[0]) focusedSlot[0]--;
            } else {
                if (selectedOrder.size() >= ModuleConfig.MAX_PINNED_WIDGET_APPS) {
                    show(getString(R.string.msg_pinned_limit));
                    if (view.getTag() instanceof AppRowHolder h && h.entry == app) {
                        h.sw.setChecked(false, false);
                    }
                    return;
                }
                selectedOrder.add(app.targetKey);
                app.selected = true;
                focusedSlot[0] = selectedOrder.size() - 1;
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
                boolean isPinned = selectedOrder.contains(app.targetKey);
                if (activeFilter[0] == 0 && app.systemApp && !isPinned) continue;
                if (activeFilter[0] == 2 && !isPinned) continue;
                if (activeFilter[0] == 3 && !app.systemApp) continue;
                if (!query.isEmpty()
                        && !app.labelLower.contains(query)
                        && !app.targetKey.toLowerCase(Locale.ROOT).contains(query)) {
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
        int btnMinH = dp(ultraCompactHeight ? 36 : 44);

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
        LinearLayout.LayoutParams saveLp = narrowFooter
                ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
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
        LauncherApps launcherApps = getSystemService(LauncherApps.class);
        List<String> orderSnapshot = new ArrayList<>(selectedOrder);
        IO.execute(() -> {
            List<AppEntry> loaded;
            try {
                loaded = loadLaunchableAppsForWidget(pm, launcherApps, orderSnapshot, true);
                for (AppEntry e : loaded) {
                    if (e.userId == 0) appLabelCache.put(e.packageName, e.label);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "failed to load widget apps", e);
                loaded = List.of();
            }
            Set<String> loadedTargets = new HashSet<>();
            for (AppEntry e : loaded) loadedTargets.add(e.targetKey);
            List<String> missingTargets = new ArrayList<>();
            List<AppEntry> combined = new ArrayList<>(loaded);
            for (String target : orderSnapshot) {
                if (loadedTargets.contains(target)) continue;
                if (!isAppTargetInstalled(pm, launcherApps, target)) {
                    missingTargets.add(target);
                    String pkg = ModuleConfig.extractAppTargetPackage(target);
                    int uid = ModuleConfig.extractAppTargetUserId(target);
                    AppEntry synth = new AppEntry(pkg, pkg, false, true, "", null, uid, true);
                    combined.add(0, synth);
                }
            }
            mainHandler.post(() -> {
                if (isDestroyed() || !dialog.isShowing()) return;
                if (combined.isEmpty()) {
                    dialog.dismiss();
                    show(getString(R.string.msg_no_apps));
                    return;
                }
                allApps.addAll(combined);
                uninstalledTargets.clear();
                uninstalledTargets.addAll(missingTargets);
                for (AppEntry e : combined) {
                    entryByTarget.put(e.targetKey, e);
                    e.selected = selectedOrder.contains(e.targetKey);
                }
                pruneBannerRef[0].update(this, uninstalledTargets.size());
                emptyView.setText(R.string.picker_empty);
                saveBtn.setEnabled(true);
                saveBtn.setAlpha(1f);
                refilterRef[0].run();
            });
        });
    }

    // ==================== 2x2 Quick Actions Widget Configurator (4 Slots) ====================

    private void broadcastQuickWidgetSync(boolean enabled, String rawSlots, boolean explicitToggle) {
        try {
            Intent intent = new Intent(QuickActionsWidget.ACTION_QUICK_WIDGET_SYNC)
                    .setPackage(CoverGeometry.FLIPHOME)
                    .putExtra(ModuleConfig.KEY_QUICK_WIDGET_ENABLED, enabled)
                    .putExtra(ModuleConfig.KEY_QUICK_WIDGET_SLOTS,
                            rawSlots != null ? rawSlots : ModuleConfig.DEFAULT_QUICK_WIDGET_SLOTS)
                    .putExtra(QuickActionsWidget.EXTRA_EXPLICIT_TOGGLE, explicitToggle);
            BroadcastOptions options = BroadcastOptions.makeBasic();
            options.setShareIdentityEnabled(true);
            sendBroadcast(intent, null, options.toBundle());
        } catch (RuntimeException e) {
            Log.w(TAG, "quick widget sync broadcast failed", e);
        }
    }

    private List<String> currentQuickActionSlots() {
        SharedPreferences p = prefs;
        if (p == null) return ModuleConfig.parseQuickActionSlots(ModuleConfig.DEFAULT_QUICK_WIDGET_SLOTS);
        try {
            return ModuleConfig.parseQuickActionSlots(
                    p.getString(ModuleConfig.KEY_QUICK_WIDGET_SLOTS, ModuleConfig.DEFAULT_QUICK_WIDGET_SLOTS));
        } catch (RuntimeException e) {
            return ModuleConfig.parseQuickActionSlots(ModuleConfig.DEFAULT_QUICK_WIDGET_SLOTS);
        }
    }

    private void addQuickWidgetSummaryText(TextView tv) {
        if (tv != null) quickWidgetSummaryTexts.add(tv);
    }

    private void refreshQuickWidgetSummaryTexts() {
        String text = quickWidgetSlotsSummary();
        for (TextView tv : quickWidgetSummaryTexts) {
            if (tv != null) tv.setText(text);
        }
    }

    private String quickActionLabel(String token) {
        if (token == null) return "";
        return switch (token) {
            case ModuleConfig.QA_HOME -> getString(R.string.qa_action_home);
            case ModuleConfig.QA_RECENTS -> getString(R.string.qa_action_recents);
            case ModuleConfig.QA_LOCK -> getString(R.string.qa_action_lock);
            case ModuleConfig.QA_BACK -> getString(R.string.qa_action_back);
            case ModuleConfig.QA_NOTIFICATIONS -> getString(R.string.qa_action_notifications);
            case ModuleConfig.QA_CONTROL_CENTER -> getString(R.string.qa_action_control_center);
            case ModuleConfig.QA_TORCH -> getString(R.string.qa_action_torch);
            case ModuleConfig.QA_SCREENSHOT -> getString(R.string.qa_action_screenshot);
            case ModuleConfig.QA_RINGER -> getString(R.string.qa_action_ringer);
            case ModuleConfig.QA_ROTATION_LOCK -> getString(R.string.qa_action_rotation_lock);
            case ModuleConfig.QA_MEDIA_PLAY_PAUSE -> getString(R.string.qa_action_media_play_pause);
            case ModuleConfig.QA_BRIGHTNESS -> getString(R.string.qa_action_brightness);
            case ModuleConfig.QA_SCAN -> getString(R.string.qa_action_scan);
            default -> {
                if (token.startsWith(ModuleConfig.QA_APP_PREFIX)) {
                    String target = token.substring(ModuleConfig.QA_APP_PREFIX.length()).trim();
                    yield formatAppTargetLabel(target);
                }
                yield token;
            }
        };
    }

    private String quickWidgetSlotsSummary() {
        List<String> slots = currentQuickActionSlots();
        return getString(
                R.string.row_quick_widget_slots_summary,
                quickActionLabel(slots.get(0)),
                quickActionLabel(slots.get(1)),
                quickActionLabel(slots.get(2)),
                quickActionLabel(slots.get(3)));
    }

    private record QuickOptionSpec(String token, int titleRes, int descRes) {}

    private final class QuickActionPreviewIcon extends View {
        private String token = ModuleConfig.QA_HOME;
        private String boundAppTarget;
        private int iconColor;
        private Drawable appDrawable;

        QuickActionPreviewIcon(Context context, int initialColor) {
            super(context);
            this.iconColor = initialColor;
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void bind(String actionToken, int fgColor) {
            this.token = actionToken != null ? actionToken : ModuleConfig.QA_HOME;
            this.iconColor = fgColor;
            if (this.token.startsWith(ModuleConfig.QA_APP_PREFIX)) {
                String target = this.token.substring(ModuleConfig.QA_APP_PREFIX.length()).trim();
                this.boundAppTarget = target;
                Drawable cached = iconCache.get(target);
                if (cached != null) {
                    this.appDrawable = iconCopy(cached);
                } else {
                    this.appDrawable = null;
                    String pkg = ModuleConfig.extractAppTargetPackage(target);
                    int userId = ModuleConfig.extractAppTargetUserId(target);
                    PackageManager pm = getPackageManager();
                    LauncherApps launcherApps = getSystemService(LauncherApps.class);
                    WeakReference<QuickActionPreviewIcon> selfRef = new WeakReference<>(this);
                    IO.execute(() -> {
                        Drawable loaded = loadAppIconSync(pm, launcherApps, pkg, userId);
                        mainHandler.post(() -> {
                            if (loaded == null || isDestroyed()) return;
                            iconCache.put(target, loaded);
                            QuickActionPreviewIcon self = selfRef.get();
                            if (self != null && target.equals(self.boundAppTarget)) {
                                self.appDrawable = iconCopy(loaded);
                                self.invalidate();
                            }
                        });
                    });
                }
            } else {
                this.boundAppTarget = null;
                this.appDrawable = null;
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            if (w <= 0f || h <= 0f) return;
            if (appDrawable != null) {
                int pad = Math.round(Math.min(w, h) * 0.08f);
                appDrawable.setBounds(pad, pad, Math.round(w) - pad, Math.round(h) - pad);
                appDrawable.draw(canvas);
                return;
            }
            QuickActionsWidget.drawActionGlyph(
                    canvas,
                    token,
                    w / 2f,
                    h / 2f,
                    Math.min(w, h) * 0.88f,
                    iconColor,
                    false,
                    android.media.AudioManager.RINGER_MODE_NORMAL);
        }
    }

    private void showQuickWidgetSlotsDialog() {
        if (!CoverDisplay.isSupportedFirmware() || prefs == null || service == null) return;
        final List<String> workingSlots = new ArrayList<>(currentQuickActionSlots());
        final int[] activeSlot = {0};
        final Set<String> uninstalledAppTargets = new LinkedHashSet<>();

        boolean compact = isCompactCoverUi();
        boolean narrowFooter = isNarrowDialogFooter();
        int safeHeight = currentSafeWindowFrame().safeRect().height();
        boolean compactHeight = safeHeight < dp(440);
        boolean ultraCompactHeight = safeHeight < dp(330);

        Dialog dialog = trackDialog(new Dialog(this));
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        int sheetPadH = compact ? dp(10) : dp(20);
        int sheetPadTop = ultraCompactHeight ? dp(6) : (compactHeight ? dp(10) : dp(16));
        int sheetPadBot = ultraCompactHeight ? dp(6) : (compactHeight ? dp(12) : dp(18));
        sheet.setPadding(sheetPadH, sheetPadTop, sheetPadH, sheetPadBot);
        sheet.setBackground(rounded(palette.surfaceContainerLow, ultraCompactHeight ? 22f : 28f));

        if (!compactHeight) {
            View handle = new View(this);
            handle.setBackground(rounded(palette.outlineVariant, 2f));
            LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(dp(36), dp(4));
            handleLp.gravity = Gravity.CENTER_HORIZONTAL;
            handleLp.bottomMargin = dp(12);
            sheet.addView(handle, handleLp);
        }

        float titleSp = ultraCompactHeight ? 15f : (compact ? 18f : 20f);
        TextView sheetTitle = createText(getString(R.string.picker_quick_title), titleSp, palette.onSurface, FONT_BOLD);
        sheetTitle.setAccessibilityHeading(true);
        sheet.addView(sheetTitle);

        TextView sheetSub = createText(
                getString(R.string.picker_quick_sub),
                ultraCompactHeight ? 10.5f : 12.5f,
                palette.onSurfaceVariant,
                FONT_REGULAR);
        sheetSub.setPadding(0, dp(ultraCompactHeight ? 2 : 4), 0, dp(ultraCompactHeight ? 4 : 8));
        sheet.addView(sheetSub);

        final Runnable[] refreshUiRef = new Runnable[1];
        final PruneBannerHolder[] pruneBannerRef = new PruneBannerHolder[1];
        pruneBannerRef[0] = createUninstalledPruneBanner(compact, ultraCompactHeight, () -> {
            int cleaned = 0;
            for (int i = 0; i < workingSlots.size(); i++) {
                String tok = workingSlots.get(i);
                if (tok != null && tok.startsWith(ModuleConfig.QA_APP_PREFIX)) {
                    String target = tok.substring(ModuleConfig.QA_APP_PREFIX.length()).trim();
                    if (uninstalledAppTargets.contains(target)) {
                        workingSlots.set(i, ModuleConfig.DEFAULT_QUICK_WIDGET_SLOT_LIST.get(i));
                        cleaned++;
                    }
                }
            }
            uninstalledAppTargets.clear();
            pruneBannerRef[0].update(this, 0);
            if (refreshUiRef[0] != null) refreshUiRef[0].run();
            if (cleaned > 0) show(getString(R.string.msg_uninstalled_pruned, cleaned));
        });
        LinearLayout.LayoutParams pruneLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pruneLp.bottomMargin = dp(ultraCompactHeight ? 3 : 6);
        sheet.addView(pruneBannerRef[0].container(), pruneLp);

        // Interactive 2x2 Visual Preview Card
        LinearLayout gridCard = new LinearLayout(this);
        gridCard.setOrientation(LinearLayout.VERTICAL);
        int gridPad = dp(ultraCompactHeight ? 6 : 10);
        gridCard.setPadding(gridPad, gridPad, gridPad, gridPad);
        gridCard.setBackground(rounded(palette.surfaceContainerHigh, ultraCompactHeight ? 16f : 22f));
        LinearLayout.LayoutParams gridCardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gridCardLp.bottomMargin = dp(ultraCompactHeight ? 4 : 8);
        sheet.addView(gridCard, gridCardLp);

        final int[] posNameRes = {
                R.string.picker_quick_slot_pos_1,
                R.string.picker_quick_slot_pos_2,
                R.string.picker_quick_slot_pos_3,
                R.string.picker_quick_slot_pos_4
        };
        final LinearLayout[] slotCells = new LinearLayout[ModuleConfig.QUICK_WIDGET_SLOT_COUNT];
        final QuickActionPreviewIcon[] slotIcons = new QuickActionPreviewIcon[ModuleConfig.QUICK_WIDGET_SLOT_COUNT];
        final TextView[] slotTitles = new TextView[ModuleConfig.QUICK_WIDGET_SLOT_COUNT];

        for (int rowIdx = 0; rowIdx < 2; rowIdx++) {
            LinearLayout rowLayout = new LinearLayout(this);
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            for (int colIdx = 0; colIdx < 2; colIdx++) {
                final int slotIdx = rowIdx * 2 + colIdx;
                LinearLayout cell = new LinearLayout(this);
                cell.setOrientation(LinearLayout.HORIZONTAL);
                cell.setGravity(Gravity.CENTER_VERTICAL);
                int cellPadH = dp(ultraCompactHeight ? 8 : 12);
                int cellPadV = dp(ultraCompactHeight ? 6 : (compactHeight ? 8 : 11));
                cell.setPadding(cellPadH, cellPadV, cellPadH, cellPadV);
                cell.setClickable(true);
                cell.setFocusable(true);

                int iconDp = dp(ultraCompactHeight ? 20 : 26);
                QuickActionPreviewIcon iconView = new QuickActionPreviewIcon(this, palette.onSurface);
                LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconDp, iconDp);
                iconLp.rightMargin = dp(ultraCompactHeight ? 6 : 10);
                cell.addView(iconView, iconLp);

                TextView lbl = createText("", ultraCompactHeight ? 11.5f : 13f, palette.onSurface, FONT_MEDIUM);
                lbl.setSingleLine(true);
                lbl.setEllipsize(TextUtils.TruncateAt.END);
                cell.addView(lbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                cell.setOnClickListener(v -> {
                    activeSlot[0] = slotIdx;
                    if (refreshUiRef[0] != null) refreshUiRef[0].run();
                });

                LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (colIdx == 0) cellLp.rightMargin = dp(ultraCompactHeight ? 5 : 8);
                rowLayout.addView(cell, cellLp);

                slotCells[slotIdx] = cell;
                slotIcons[slotIdx] = iconView;
                slotTitles[slotIdx] = lbl;
            }
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (rowIdx == 0) rowLp.bottomMargin = dp(ultraCompactHeight ? 5 : 8);
            gridCard.addView(rowLayout, rowLp);
        }

        // Editing slot banner + ↑ / ↓ slot reorder buttons
        LinearLayout editingRow = new LinearLayout(this);
        editingRow.setOrientation(LinearLayout.HORIZONTAL);
        editingRow.setGravity(Gravity.CENTER_VERTICAL);
        editingRow.setPadding(dp(2), 0, dp(2), dp(ultraCompactHeight ? 3 : 6));

        TextView editingBanner = createText("", ultraCompactHeight ? 11f : 12f, palette.primary, FONT_MEDIUM);
        editingBanner.setSingleLine(true);
        editingBanner.setEllipsize(TextUtils.TruncateAt.END);
        editingRow.addView(editingBanner, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        int slotReorderH = dp(ultraCompactHeight ? 36 : 44);
        TextView btnSlotUp = createDialogChipButton("↑", false);
        btnSlotUp.setMinWidth(dp(44));
        btnSlotUp.setMinHeight(slotReorderH);
        btnSlotUp.setContentDescription(getString(R.string.a11y_slot_move_prev));
        TextView btnSlotDown = createDialogChipButton("↓", false);
        btnSlotDown.setMinWidth(dp(44));
        btnSlotDown.setMinHeight(slotReorderH);
        btnSlotDown.setContentDescription(getString(R.string.a11y_slot_move_next));

        LinearLayout.LayoutParams upLp = new LinearLayout.LayoutParams(dp(44), slotReorderH);
        upLp.leftMargin = dp(6);
        editingRow.addView(btnSlotUp, upLp);
        LinearLayout.LayoutParams downLp = new LinearLayout.LayoutParams(dp(44), slotReorderH);
        downLp.leftMargin = dp(4);
        editingRow.addView(btnSlotDown, downLp);

        btnSlotUp.setOnClickListener(v -> {
            int idx = activeSlot[0];
            if (idx > 0) {
                Collections.swap(workingSlots, idx, idx - 1);
                activeSlot[0] = idx - 1;
                if (refreshUiRef[0] != null) refreshUiRef[0].run();
            }
        });
        btnSlotDown.setOnClickListener(v -> {
            int idx = activeSlot[0];
            if (idx < workingSlots.size() - 1) {
                Collections.swap(workingSlots, idx, idx + 1);
                activeSlot[0] = idx + 1;
                if (refreshUiRef[0] != null) refreshUiRef[0].run();
            }
        });
        sheet.addView(editingRow);

        // Scrollable Grouped Action Options
        ScrollView optionsScroll = new ScrollView(this);
        optionsScroll.setVerticalScrollBarEnabled(false);
        LinearLayout optionsCol = new LinearLayout(this);
        optionsCol.setOrientation(LinearLayout.VERTICAL);
        optionsScroll.addView(optionsCol);
        sheet.addView(optionsScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        List<QuickOptionSpec> navSpecs = List.of(
                new QuickOptionSpec(ModuleConfig.QA_HOME, R.string.qa_action_home, R.string.qa_action_home_desc),
                new QuickOptionSpec(ModuleConfig.QA_RECENTS, R.string.qa_action_recents, R.string.qa_action_recents_desc),
                new QuickOptionSpec(ModuleConfig.QA_LOCK, R.string.qa_action_lock, R.string.qa_action_lock_desc),
                new QuickOptionSpec(ModuleConfig.QA_BACK, R.string.qa_action_back, R.string.qa_action_back_desc));
        List<QuickOptionSpec> ctrlSpecs = List.of(
                new QuickOptionSpec(ModuleConfig.QA_NOTIFICATIONS, R.string.qa_action_notifications, R.string.qa_action_notifications_desc),
                new QuickOptionSpec(ModuleConfig.QA_CONTROL_CENTER, R.string.qa_action_control_center, R.string.qa_action_control_center_desc),
                new QuickOptionSpec(ModuleConfig.QA_TORCH, R.string.qa_action_torch, R.string.qa_action_torch_desc),
                new QuickOptionSpec(ModuleConfig.QA_SCREENSHOT, R.string.qa_action_screenshot, R.string.qa_action_screenshot_desc),
                new QuickOptionSpec(ModuleConfig.QA_RINGER, R.string.qa_action_ringer, R.string.qa_action_ringer_desc),
                new QuickOptionSpec(ModuleConfig.QA_ROTATION_LOCK, R.string.qa_action_rotation_lock, R.string.qa_action_rotation_lock_desc),
                new QuickOptionSpec(ModuleConfig.QA_MEDIA_PLAY_PAUSE, R.string.qa_action_media_play_pause, R.string.qa_action_media_play_pause_desc),
                new QuickOptionSpec(ModuleConfig.QA_BRIGHTNESS, R.string.qa_action_brightness, R.string.qa_action_brightness_desc),
                new QuickOptionSpec(ModuleConfig.QA_SCAN, R.string.qa_action_scan, R.string.qa_action_scan_desc),
                new QuickOptionSpec(ModuleConfig.QA_APP_PREFIX, R.string.qa_action_app, R.string.qa_action_app_desc));

        record OptionRowViews(String token, LinearLayout row, QuickActionPreviewIcon icon,
                              TextView title, TextView sub, TextView badge) {}
        final List<OptionRowViews> optionRows = new ArrayList<>();

        java.util.function.BiConsumer<Integer, List<QuickOptionSpec>> appendSection = (headerRes, specs) -> {
            TextView secHeader = createText(getString(headerRes), 12f, palette.primary, FONT_MEDIUM);
            secHeader.setPadding(dp(8), dp(6), dp(8), dp(4));
            optionsCol.addView(secHeader);

            int count = specs.size();
            for (int i = 0; i < count; i++) {
                QuickOptionSpec spec = specs.get(i);
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int padH = compact ? dp(10) : dp(14);
                int padV = dp(ultraCompactHeight ? 7 : 10);
                row.setPadding(padH, padV, padH, padV);
                float rTop = (i == 0) ? 18f : 5f;
                float rBot = (i == count - 1) ? 18f : 5f;
                row.setBackground(ripple(palette.surfaceContainer, palette.rippleColor, rTop, rTop, rBot, rBot, 0, 0));
                row.setClickable(true);
                row.setFocusable(true);

                FrameLayout iconBadge = new FrameLayout(this);
                iconBadge.setBackground(rounded(palette.secondaryContainer, 12f));
                QuickActionPreviewIcon icon = new QuickActionPreviewIcon(this, palette.onSecondaryContainer);
                String glyphToken = ModuleConfig.QA_APP_PREFIX.equals(spec.token) ? "app_placeholder" : spec.token;
                icon.bind(glyphToken, palette.onSecondaryContainer);
                iconBadge.addView(icon, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
                LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(dp(34), dp(34));
                badgeLp.rightMargin = dp(10);
                row.addView(iconBadge, badgeLp);

                LinearLayout textCol = new LinearLayout(this);
                textCol.setOrientation(LinearLayout.VERTICAL);
                TextView tView = createText(getString(spec.titleRes), compact ? 13.5f : 14.5f, palette.onSurface, FONT_MEDIUM);
                textCol.addView(tView);
                TextView sView = createText(getString(spec.descRes), 11.5f, palette.onSurfaceVariant, FONT_REGULAR);
                sView.setPadding(0, dp(2), 0, 0);
                textCol.addView(sView);
                row.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView selTag = createText(getString(R.string.tag_enabled), 10.5f, palette.onPrimaryContainer, FONT_MEDIUM);
                selTag.setPadding(dp(8), dp(3), dp(8), dp(3));
                selTag.setBackground(rounded(palette.primaryContainer, 10f));
                selTag.setVisibility(View.GONE);
                LinearLayout.LayoutParams selLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                selLp.leftMargin = dp(6);
                row.addView(selTag, selLp);

                row.setOnClickListener(v -> {
                    int targetSlot = activeSlot[0];
                    if (ModuleConfig.QA_APP_PREFIX.equals(spec.token)) {
                        showSingleAppPickerForQuickSlot(
                                getString(R.string.picker_quick_app_title, targetSlot + 1),
                                chosenTarget -> {
                                    workingSlots.set(targetSlot, ModuleConfig.QA_APP_PREFIX + chosenTarget);
                                    if (refreshUiRef[0] != null) refreshUiRef[0].run();
                                });
                    } else {
                        workingSlots.set(targetSlot, spec.token);
                        if (refreshUiRef[0] != null) refreshUiRef[0].run();
                    }
                });

                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rowLp.bottomMargin = (i == count - 1) ? dp(8) : dp(2);
                optionsCol.addView(row, rowLp);
                optionRows.add(new OptionRowViews(spec.token, row, icon, tView, sView, selTag));
            }
        };

        appendSection.accept(R.string.qa_group_nav, navSpecs);
        appendSection.accept(R.string.qa_group_ctrl, ctrlSpecs);

        refreshUiRef[0] = () -> {
            float cellR = ultraCompactHeight ? 12f : 16f;
            int missingCount = 0;
            for (int i = 0; i < ModuleConfig.QUICK_WIDGET_SLOT_COUNT; i++) {
                boolean isSel = (i == activeSlot[0]);
                String slotToken = workingSlots.get(i);
                boolean isMissing = slotToken != null
                        && slotToken.startsWith(ModuleConfig.QA_APP_PREFIX)
                        && uninstalledAppTargets.contains(slotToken.substring(ModuleConfig.QA_APP_PREFIX.length()).trim());
                if (isMissing) missingCount++;
                int fill = isMissing
                        ? palette.errorContainer
                        : (isSel ? palette.primaryContainer : palette.surfaceContainer);
                int strokeW = isSel ? dp(2) : dp(1);
                int strokeC = isMissing ? palette.onErrorContainer : (isSel ? palette.primary : palette.outlineVariant);
                int fg = isMissing ? palette.onErrorContainer : (isSel ? palette.onPrimaryContainer : palette.onSurface);
                slotCells[i].setBackground(createShape(fill, cellR, cellR, cellR, cellR, strokeW, strokeC));
                slotIcons[i].bind(slotToken, fg);
                String lbl = quickActionLabel(slotToken);
                if (isMissing) lbl = getString(R.string.tag_uninstalled) + " · " + lbl;
                slotTitles[i].setText(getString(R.string.slot_numbered_fmt, i + 1, lbl));
                slotTitles[i].setTextColor(fg);
            }
            pruneBannerRef[0].update(this, missingCount);
            String posName = getString(posNameRes[activeSlot[0]]);
            editingBanner.setText(getString(R.string.picker_quick_editing_slot, activeSlot[0] + 1, posName));
            boolean canUp = activeSlot[0] > 0;
            boolean canDown = activeSlot[0] < ModuleConfig.QUICK_WIDGET_SLOT_COUNT - 1;
            btnSlotUp.setEnabled(canUp);
            btnSlotUp.setAlpha(canUp ? 1f : 0.35f);
            btnSlotDown.setEnabled(canDown);
            btnSlotDown.setAlpha(canDown ? 1f : 0.35f);

            String activeToken = workingSlots.get(activeSlot[0]);
            for (OptionRowViews orv : optionRows) {
                boolean match;
                if (ModuleConfig.QA_APP_PREFIX.equals(orv.token)) {
                    match = activeToken.startsWith(ModuleConfig.QA_APP_PREFIX);
                    if (match) {
                        orv.title.setText(getString(R.string.qa_action_app_fmt, quickActionLabel(activeToken)));
                    } else {
                        orv.title.setText(R.string.qa_action_app);
                    }
                } else {
                    match = orv.token.equals(activeToken);
                }
                orv.badge.setVisibility(match ? View.VISIBLE : View.GONE);
            }
        };
        refreshUiRef[0].run();

        // Bottom Row: Defaults | Cancel | Save (responsive on 249dp narrow cover)
        float btnSp = compact ? 12.5f : 14f;
        int btnPadH = compact ? dp(10) : dp(18);
        int btnPadV = dp(ultraCompactHeight ? 6 : 10);
        int btnMinH = dp(ultraCompactHeight ? 36 : 44);

        TextView resetBtn = createText(getString(R.string.btn_reset_default), btnSp, palette.onSurfaceVariant, FONT_MEDIUM);
        resetBtn.setGravity(Gravity.CENTER);
        resetBtn.setSingleLine(true);
        resetBtn.setEllipsize(TextUtils.TruncateAt.END);
        resetBtn.setPadding(btnPadH, btnPadV, btnPadH, btnPadV);
        resetBtn.setMinHeight(btnMinH);
        resetBtn.setBackground(pillRipple(palette.surfaceContainerHigh, palette.rippleColor, 22f));
        resetBtn.setOnClickListener(v -> {
            workingSlots.clear();
            workingSlots.addAll(ModuleConfig.DEFAULT_QUICK_WIDGET_SLOT_LIST);
            refreshUiRef[0].run();
        });

        TextView cancelBtn = createText(getString(R.string.btn_cancel), btnSp, palette.primary, FONT_MEDIUM);
        cancelBtn.setGravity(Gravity.CENTER);
        cancelBtn.setSingleLine(true);
        cancelBtn.setEllipsize(TextUtils.TruncateAt.END);
        cancelBtn.setPadding(btnPadH, btnPadV, btnPadH, btnPadV);
        cancelBtn.setMinHeight(btnMinH);
        cancelBtn.setBackground(pillRipple(Color.TRANSPARENT, palette.rippleColor, 22f));
        cancelBtn.setOnClickListener(v -> dialog.dismiss());

        TextView saveBtn = createText(getString(R.string.btn_save_quick_slots), btnSp, palette.onPrimary, FONT_MEDIUM);
        saveBtn.setGravity(Gravity.CENTER);
        saveBtn.setSingleLine(true);
        saveBtn.setEllipsize(TextUtils.TruncateAt.END);
        saveBtn.setPadding(compact ? dp(14) : dp(22), btnPadV, compact ? dp(14) : dp(22), btnPadV);
        saveBtn.setMinHeight(btnMinH);
        saveBtn.setBackground(pillRipple(palette.primary, palette.rippleColor, 22f));
        saveBtn.setOnClickListener(v -> {
            dialog.dismiss();
            try {
                String serialized = ModuleConfig.serializeQuickActionSlots(workingSlots);
                prefs.edit()
                        .putString(ModuleConfig.KEY_QUICK_WIDGET_SLOTS, serialized)
                        .putBoolean(ModuleConfig.KEY_QUICK_WIDGET_ENABLED, true)
                        .apply();
                broadcastQuickWidgetSync(true, serialized, true);
                syncSwitches(ModuleConfig.KEY_QUICK_WIDGET_ENABLED, true);
                refreshQuickWidgetSummaryTexts();
                if (coverPreview != null) coverPreview.invalidate();
                show(getString(R.string.msg_quick_slots_saved, quickWidgetSlotsSummary()));
            } catch (RuntimeException e) {
                show(getString(R.string.msg_save_failed, String.valueOf(e.getMessage())));
            }
        });

        if (narrowFooter) {
            LinearLayout footerCol = new LinearLayout(this);
            footerCol.setOrientation(LinearLayout.VERTICAL);
            footerCol.setPadding(0, dp(ultraCompactHeight ? 4 : 8), 0, 0);
            LinearLayout topBtnRow = new LinearLayout(this);
            topBtnRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            resetLp.rightMargin = dp(6);
            topBtnRow.addView(resetBtn, resetLp);
            topBtnRow.addView(cancelBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            footerCol.addView(topBtnRow);
            LinearLayout.LayoutParams saveFullLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            saveFullLp.topMargin = dp(6);
            footerCol.addView(saveBtn, saveFullLp);
            sheet.addView(footerCol);
        } else {
            LinearLayout bottomRow = new LinearLayout(this);
            bottomRow.setOrientation(LinearLayout.HORIZONTAL);
            bottomRow.setGravity(Gravity.CENTER_VERTICAL);
            bottomRow.setPadding(0, dp(ultraCompactHeight ? 4 : (compactHeight ? 8 : 12)), 0, 0);
            bottomRow.addView(resetBtn);
            bottomRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
            bottomRow.addView(cancelBtn);
            LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            saveLp.leftMargin = dp(4);
            bottomRow.addView(saveBtn, saveLp);
            sheet.addView(bottomRow);
        }

        dialog.setContentView(sheet, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        configureCoverSafePickerWindow(dialog, true);
        dialog.show();
        configureCoverSafePickerWindow(dialog, true);

        // Background check for uninstalled app slots & missing labels
        PackageManager pm = getPackageManager();
        LauncherApps launcherApps = getSystemService(LauncherApps.class);
        List<String> slotSnapshot = new ArrayList<>(workingSlots);
        IO.execute(() -> {
            Set<String> missing = new LinkedHashSet<>();
            boolean labelsAdded = false;
            for (String tok : slotSnapshot) {
                if (tok == null || !tok.startsWith(ModuleConfig.QA_APP_PREFIX)) continue;
                String target = tok.substring(ModuleConfig.QA_APP_PREFIX.length()).trim();
                if (!isAppTargetInstalled(pm, launcherApps, target)) {
                    missing.add(target);
                } else {
                    String pkg = ModuleConfig.extractAppTargetPackage(target);
                    if (!appLabelCache.containsKey(pkg)) {
                        String resolved = resolvePackageLabel(pm, pkg);
                        if (resolved != null && !resolved.isEmpty()) {
                            appLabelCache.put(pkg, resolved);
                            labelsAdded = true;
                        }
                    }
                }
            }
            if (!missing.isEmpty() || labelsAdded) {
                mainHandler.post(() -> {
                    if (isDestroyed() || !dialog.isShowing()) return;
                    uninstalledAppTargets.clear();
                    uninstalledAppTargets.addAll(missing);
                    refreshUiRef[0].run();
                });
            }
        });
    }

    private void showSingleAppPickerForQuickSlot(String dialogTitle, Consumer<String> onSelected) {
        boolean compact = isCompactCoverUi();
        int safeHeight = currentSafeWindowFrame().safeRect().height();
        boolean ultraCompactHeight = safeHeight < dp(330);

        Dialog appDialog = trackDialog(new Dialog(this));
        appDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        int padH = compact ? dp(10) : dp(20);
        int padV = dp(ultraCompactHeight ? 8 : 14);
        sheet.setPadding(padH, padV, padH, padV);
        sheet.setBackground(rounded(palette.surfaceContainerLow, ultraCompactHeight ? 22f : 28f));

        TextView titleView = createText(
                dialogTitle != null ? dialogTitle : getString(R.string.qa_action_app),
                ultraCompactHeight ? 15f : 18f,
                palette.onSurface,
                FONT_BOLD);
        titleView.setAccessibilityHeading(true);
        titleView.setPadding(0, 0, 0, dp(8));
        sheet.addView(titleView);

        LinearLayout searchBox = new LinearLayout(this);
        searchBox.setOrientation(LinearLayout.HORIZONTAL);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setPadding(dp(12), dp(2), dp(12), dp(2));
        searchBox.setBackground(rounded(palette.surfaceContainerHigh, 20f));

        EditText searchInput = new EditText(this);
        searchInput.setHint(R.string.picker_search_hint);
        searchInput.setHintTextColor(palette.onSurfaceVariant);
        searchInput.setTextColor(palette.onSurface);
        searchInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        searchInput.setSingleLine(true);
        searchInput.setBackground(null);
        searchInput.setMinHeight(dp(40));
        searchBox.addView(searchInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchLp.bottomMargin = dp(6);
        sheet.addView(searchBox, searchLp);

        final List<AppEntry> allApps = new ArrayList<>();
        final List<AppEntry> filteredApps = new ArrayList<>();
        BaseAdapter singleAdapter = new BaseAdapter() {
            @Override public int getCount() { return filteredApps.size(); }
            @Override public AppEntry getItem(int position) { return filteredApps.get(position); }
            @Override public long getItemId(int position) { return position; }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                AppRowHolder holder;
                if (convertView != null && convertView.getTag() instanceof AppRowHolder h) {
                    holder = h;
                } else {
                    holder = new AppRowHolder(false);
                }
                holder.bind(filteredApps.get(position), position, filteredApps.size(), false);
                return holder.row;
            }
        };

        FrameLayout listFrame = new FrameLayout(this);
        ListView listView = new ListView(this);
        listView.setVerticalScrollBarEnabled(false);
        listView.setDivider(new ColorDrawable(Color.TRANSPARENT));
        listView.setDividerHeight(dp(2));
        listView.setSelector(new ColorDrawable(Color.TRANSPARENT));
        listView.setAdapter(singleAdapter);
        TextView emptyView = createText(getString(R.string.picker_loading), 13f, palette.onSurfaceVariant, FONT_REGULAR);
        emptyView.setGravity(Gravity.CENTER);
        listFrame.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        listFrame.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        listView.setEmptyView(emptyView);
        sheet.addView(listFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= filteredApps.size()) return;
            AppEntry chosen = filteredApps.get(position);
            appDialog.dismiss();
            if (onSelected != null) onSelected.accept(chosen.targetKey);
        });

        Runnable refilter = () -> {
            String q = searchInput.getText() == null ? "" : searchInput.getText().toString().trim().toLowerCase(Locale.ROOT);
            filteredApps.clear();
            for (AppEntry app : allApps) {
                if (!q.isEmpty() && !app.labelLower.contains(q) && !app.targetKey.toLowerCase(Locale.ROOT).contains(q)) {
                    continue;
                }
                filteredApps.add(app);
            }
            singleAdapter.notifyDataSetChanged();
        };

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                mainHandler.removeCallbacks(refilter);
                mainHandler.postDelayed(refilter, 120);
            }
        });

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.END);
        footer.setPadding(0, dp(8), 0, 0);
        TextView closeBtn = createText(getString(R.string.btn_cancel), 13.5f, palette.primary, FONT_MEDIUM);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setSingleLine(true);
        closeBtn.setEllipsize(TextUtils.TruncateAt.END);
        closeBtn.setMinHeight(dp(44));
        closeBtn.setPadding(dp(16), dp(8), dp(16), dp(8));
        closeBtn.setBackground(pillRipple(Color.TRANSPARENT, palette.rippleColor, 22f));
        closeBtn.setOnClickListener(v -> appDialog.dismiss());
        footer.addView(closeBtn);
        sheet.addView(footer);

        appDialog.setContentView(sheet, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        appDialog.setOnDismissListener(d -> mainHandler.removeCallbacks(refilter));
        configureCoverSafePickerWindow(appDialog, true);
        appDialog.show();
        configureCoverSafePickerWindow(appDialog, true);

        PackageManager pm = getPackageManager();
        LauncherApps launcherApps = getSystemService(LauncherApps.class);
        IO.execute(() -> {
            List<AppEntry> loaded;
            try {
                loaded = loadLaunchableAppsForWidget(pm, launcherApps, List.of(), true);
                for (AppEntry e : loaded) {
                    if (e.userId == 0) appLabelCache.put(e.packageName, e.label);
                }
            } catch (RuntimeException e) {
                loaded = List.of();
            }
            final List<AppEntry> result = loaded;
            mainHandler.post(() -> {
                if (isDestroyed() || !appDialog.isShowing()) return;
                allApps.addAll(result);
                emptyView.setText(R.string.picker_empty);
                refilter.run();
            });
        });
    }

    // ==================== Feature 1, 2, 4, 6 Summary Helpers & Dialogs ====================

    private static void addTextIfPresent(List<TextView> bucket, TextView tv) {
        if (bucket != null && tv != null) bucket.add(tv);
    }

    private static void updateTextBucket(List<TextView> bucket, String value) {
        for (TextView tv : bucket) {
            if (tv != null) tv.setText(value);
        }
    }

    private String orbActionLabel(String token) {
        String normalized = ModuleConfig.normalizeOrbActionToken(token);
        if (ModuleConfig.QA_NONE.equals(normalized)) {
            return getString(R.string.orb_action_none);
        }
        return quickActionLabel(normalized);
    }

    private void refreshOrbActionSummaries() {
        updateTextBucket(orbTapSummaryTexts, orbActionLabel(ModuleConfig.orbTapAction()));
        updateTextBucket(orbLongSummaryTexts, orbActionLabel(ModuleConfig.orbLongPressAction()));
    }

    private String foldContinuityModeSummary() {
        return switch (ModuleConfig.foldContinuityMode()) {
            case ModuleConfig.FOLD_CONT_SELECTED ->
                    getString(R.string.fold_mode_selected_fmt, ModuleConfig.foldContinuityApps().size());
            case ModuleConfig.FOLD_CONT_ALL -> getString(R.string.fold_mode_all);
            default -> getString(R.string.fold_mode_system);
        };
    }

    private String foldContinuityAppsSummary() {
        Set<String> apps = ModuleConfig.foldContinuityApps();
        if (apps.isEmpty()) return getString(R.string.row_fold_apps_empty);
        String sep = getString(R.string.list_separator);
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (String pkg : apps) {
            if (shown >= 4) {
                sb.append(sep).append("…");
                break;
            }
            String label = cachedLabelOrPkg(pkg);
            if (sb.length() > 0) sb.append(sep);
            sb.append(label);
            shown++;
        }
        return getString(R.string.row_fold_apps_summary, apps.size(), sb.toString());
    }

    private void refreshFoldContinuitySummaries() {
        updateTextBucket(foldModeSummaryTexts, foldContinuityModeSummary());
        updateTextBucket(foldAppsSummaryTexts, foldContinuityAppsSummary());
    }

    private String imeInsetLabel(int insetPx) {
        if (insetPx < 0) return getString(R.string.ime_inset_auto);
        if (insetPx == 0) return getString(R.string.ime_inset_none);
        if (insetPx == 260) return getString(R.string.ime_inset_compact);
        if (insetPx == 398) return getString(R.string.ime_inset_standard);
        if (insetPx == 430) return getString(R.string.ime_inset_wide);
        return insetPx + "px";
    }

    private String imeTuningSummary() {
        return getString(
                R.string.row_ime_tuning_summary,
                ModuleConfig.imeDpiScalePercent(),
                ModuleConfig.imeMaxHeightPercent(),
                imeInsetLabel(ModuleConfig.imeTopInsetPx()));
    }

    private void refreshImeTuningSummaries() {
        updateTextBucket(imeTuningSummaryTexts, imeTuningSummary());
    }

    private String perAppRulesSummary() {
        int count = ModuleConfig.perAppRules().size();
        return count == 0
                ? getString(R.string.row_per_app_rules_empty)
                : getString(R.string.row_per_app_rules_summary, count);
    }

    private void refreshPerAppRulesSummaries() {
        updateTextBucket(perAppRulesSummaryTexts, perAppRulesSummary());
    }

    private String formatPerAppRuleSummary(ModuleConfig.PerAppRule rule) {
        if (rule == null || rule.isDefault()) return getString(R.string.tag_rule_default);
        List<String> parts = new ArrayList<>(3);
        if (rule.topGapPx() >= 0) parts.add(rule.topGapPx() + "px");
        if (rule.widthMode() == ModuleConfig.WIDTH_MODE_FULL) parts.add("1208px");
        else if (rule.widthMode() == ModuleConfig.WIDTH_MODE_SAFE_COLUMN) parts.add("810px");
        if (rule.scalePercent() > 0) parts.add(rule.scalePercent() + "%");
        return parts.isEmpty() ? getString(R.string.tag_rule_default) : TextUtils.join(" · ", parts);
    }

    private String resolvePackageLabel(PackageManager pm, String pkg) {
        if (pkg == null || pkg.isEmpty()) return "";
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence cs = pm.getApplicationLabel(ai);
            if (cs != null && cs.length() > 0) return cs.toString();
        } catch (Throwable ignored) {
        }
        return pkg;
    }

    private TextView createChoicePill(String text, boolean selectedPill, boolean compact) {
        TextView pill = createText(
                text,
                compact ? 11.5f : 12.5f,
                selectedPill ? palette.onPrimaryContainer : palette.onSurfaceVariant,
                FONT_MEDIUM);
        pill.setSingleLine(true);
        pill.setEllipsize(TextUtils.TruncateAt.END);
        pill.setGravity(Gravity.CENTER);
        pill.setMinHeight(dp(44));
        pill.setMinimumHeight(dp(44));
        pill.setPadding(dp(compact ? 11 : 14), 0, dp(compact ? 11 : 14), 0);
        Drawable bg = new InsetDrawable(
                pillRipple(
                        selectedPill ? palette.primaryContainer : palette.surfaceContainerHigh,
                        palette.rippleColor,
                        16f),
                0, dp(3), 0, dp(3));
        pill.setBackground(bg);
        pill.setClickable(true);
        pill.setFocusable(true);
        pill.setSelected(selectedPill);
        return pill;
    }

    private void showOrbActionPickerDialog(boolean isLongPress) {
        if (!CoverDisplay.isSupportedFirmware() || prefs == null || service == null) return;
        final String key = isLongPress ? ModuleConfig.KEY_ORB_LONG_PRESS_ACTION : ModuleConfig.KEY_ORB_TAP_ACTION;
        final String[] current = {isLongPress ? ModuleConfig.orbLongPressAction() : ModuleConfig.orbTapAction()};

        boolean compact = isCompactCoverUi();
        int safeHeight = currentSafeWindowFrame().safeRect().height();
        boolean compactHeight = safeHeight < dp(440);
        boolean ultraCompactHeight = safeHeight < dp(330);

        Dialog dialog = trackDialog(new Dialog(this));
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        int sheetPadH = compact ? dp(10) : dp(20);
        int sheetPadV = dp(ultraCompactHeight ? 8 : (compactHeight ? 12 : 16));
        sheet.setPadding(sheetPadH, sheetPadV, sheetPadH, sheetPadV);
        sheet.setBackground(rounded(palette.surfaceContainerLow, ultraCompactHeight ? 22f : 28f));

        TextView titleView = createText(
                getString(isLongPress ? R.string.picker_orb_long_title : R.string.picker_orb_tap_title),
                ultraCompactHeight ? 15f : (compact ? 18f : 20f),
                palette.onSurface,
                FONT_BOLD);
        titleView.setAccessibilityHeading(true);
        sheet.addView(titleView);
        TextView subView = createText(
                getString(R.string.picker_orb_sub),
                ultraCompactHeight ? 10.5f : 12f,
                palette.onSurfaceVariant,
                FONT_REGULAR);
        subView.setPadding(0, dp(3), 0, dp(8));
        sheet.addView(subView);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(col);
        sheet.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        List<QuickOptionSpec> specs = List.of(
                new QuickOptionSpec(ModuleConfig.QA_NONE, R.string.orb_action_none, R.string.orb_action_none_desc),
                new QuickOptionSpec(ModuleConfig.QA_TORCH, R.string.qa_action_torch, R.string.qa_action_torch_desc),
                new QuickOptionSpec(ModuleConfig.QA_LOCK, R.string.qa_action_lock, R.string.qa_action_lock_desc),
                new QuickOptionSpec(ModuleConfig.QA_ROTATION_LOCK, R.string.qa_action_rotation_lock, R.string.qa_action_rotation_lock_desc),
                new QuickOptionSpec(ModuleConfig.QA_MEDIA_PLAY_PAUSE, R.string.qa_action_media_play_pause, R.string.qa_action_media_play_pause_desc),
                new QuickOptionSpec(ModuleConfig.QA_BRIGHTNESS, R.string.qa_action_brightness, R.string.qa_action_brightness_desc),
                new QuickOptionSpec(ModuleConfig.QA_SCAN, R.string.qa_action_scan, R.string.qa_action_scan_desc),
                new QuickOptionSpec(ModuleConfig.QA_CONTROL_CENTER, R.string.qa_action_control_center, R.string.qa_action_control_center_desc),
                new QuickOptionSpec(ModuleConfig.QA_NOTIFICATIONS, R.string.qa_action_notifications, R.string.qa_action_notifications_desc),
                new QuickOptionSpec(ModuleConfig.QA_SCREENSHOT, R.string.qa_action_screenshot, R.string.qa_action_screenshot_desc),
                new QuickOptionSpec(ModuleConfig.QA_RINGER, R.string.qa_action_ringer, R.string.qa_action_ringer_desc),
                new QuickOptionSpec(ModuleConfig.QA_HOME, R.string.qa_action_home, R.string.qa_action_home_desc),
                new QuickOptionSpec(ModuleConfig.QA_RECENTS, R.string.qa_action_recents, R.string.qa_action_recents_desc),
                new QuickOptionSpec(ModuleConfig.QA_BACK, R.string.qa_action_back, R.string.qa_action_back_desc),
                new QuickOptionSpec(ModuleConfig.QA_APP_PREFIX, R.string.qa_action_app, R.string.qa_action_app_desc));

        Consumer<String> applyOrbChoice = chosen -> {
            String normalized = ModuleConfig.normalizeOrbActionToken(chosen);
            try {
                prefs.edit().putString(key, normalized).apply();
                refreshOrbActionSummaries();
                if (coverPreview != null) coverPreview.invalidate();
                show(getString(R.string.msg_orb_action_saved, orbActionLabel(normalized)));
            } catch (RuntimeException e) {
                show(getString(R.string.msg_save_failed, String.valueOf(e.getMessage())));
            }
        };

        int count = specs.size();
        for (int i = 0; i < count; i++) {
            QuickOptionSpec spec = specs.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(compact ? 10 : 14), dp(ultraCompactHeight ? 7 : 10),
                    dp(compact ? 10 : 14), dp(ultraCompactHeight ? 7 : 10));
            float rTop = (i == 0) ? 18f : 5f;
            float rBot = (i == count - 1) ? 18f : 5f;
            row.setBackground(ripple(palette.surfaceContainer, palette.rippleColor, rTop, rTop, rBot, rBot, 0, 0));
            row.setClickable(true);
            row.setFocusable(true);

            FrameLayout iconBadge = new FrameLayout(this);
            iconBadge.setBackground(rounded(palette.secondaryContainer, 12f));
            QuickActionPreviewIcon icon = new QuickActionPreviewIcon(this, palette.onSecondaryContainer);
            String glyphTok = ModuleConfig.QA_APP_PREFIX.equals(spec.token) ? "app_placeholder"
                    : (ModuleConfig.QA_NONE.equals(spec.token) ? "none" : spec.token);
            icon.bind(glyphTok, palette.onSecondaryContainer);
            iconBadge.addView(icon, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
            LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(dp(34), dp(34));
            badgeLp.rightMargin = dp(10);
            row.addView(iconBadge, badgeLp);

            LinearLayout textCol = new LinearLayout(this);
            textCol.setOrientation(LinearLayout.VERTICAL);
            boolean isAppMatch = ModuleConfig.QA_APP_PREFIX.equals(spec.token)
                    && current[0].startsWith(ModuleConfig.QA_APP_PREFIX);
            String titleStr = isAppMatch
                    ? getString(R.string.qa_action_app_fmt, orbActionLabel(current[0]))
                    : getString(spec.titleRes);
            TextView tView = createText(titleStr, compact ? 13.5f : 14.5f, palette.onSurface, FONT_MEDIUM);
            textCol.addView(tView);
            TextView sView = createText(getString(spec.descRes), 11.5f, palette.onSurfaceVariant, FONT_REGULAR);
            sView.setPadding(0, dp(2), 0, 0);
            textCol.addView(sView);
            row.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            boolean active = isAppMatch || spec.token.equals(current[0]);
            TextView tag = createText(getString(R.string.tag_enabled), 10.5f, palette.onPrimaryContainer, FONT_MEDIUM);
            tag.setPadding(dp(8), dp(3), dp(8), dp(3));
            tag.setBackground(rounded(palette.primaryContainer, 10f));
            tag.setVisibility(active ? View.VISIBLE : View.GONE);
            row.addView(tag);

            row.setOnClickListener(v -> {
                dialog.dismiss();
                if (ModuleConfig.QA_APP_PREFIX.equals(spec.token)) {
                    showSingleAppPickerForQuickSlot(
                            getString(isLongPress ? R.string.picker_orb_long_app_title : R.string.picker_orb_tap_app_title),
                            target -> applyOrbChoice.accept(ModuleConfig.QA_APP_PREFIX + target));
                } else {
                    applyOrbChoice.accept(spec.token);
                }
            });

            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = (i == count - 1) ? 0 : dp(2);
            col.addView(row, rowLp);
        }

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.END);
        footer.setPadding(0, dp(8), 0, 0);
        TextView closeBtn = createText(getString(R.string.btn_cancel), 13.5f, palette.primary, FONT_MEDIUM);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setSingleLine(true);
        closeBtn.setEllipsize(TextUtils.TruncateAt.END);
        closeBtn.setMinHeight(dp(44));
        closeBtn.setPadding(dp(16), dp(8), dp(16), dp(8));
        closeBtn.setBackground(pillRipple(Color.TRANSPARENT, palette.rippleColor, 22f));
        closeBtn.setOnClickListener(v -> dialog.dismiss());
        footer.addView(closeBtn);
        sheet.addView(footer);

        dialog.setContentView(sheet, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        configureCoverSafePickerWindow(dialog, true);
        dialog.show();
        configureCoverSafePickerWindow(dialog, true);
    }

    private void showFoldContinuityModeDialog() {
        if (!CoverDisplay.isSupportedFirmware() || prefs == null || service == null) return;
        int curMode = ModuleConfig.foldContinuityMode();
        int[] modes = {
                ModuleConfig.FOLD_CONT_SYSTEM,
                ModuleConfig.FOLD_CONT_SELECTED,
                ModuleConfig.FOLD_CONT_ALL
        };
        int[] labels = {
                R.string.fold_mode_system,
                R.string.fold_mode_selected,
                R.string.fold_mode_all
        };

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), dp(4));

        Dialog[] dialogHolder = new Dialog[1];
        for (int i = 0; i < modes.length; i++) {
            final int mode = modes[i];
            boolean selectedMode = (mode == curMode);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            float rTop = (i == 0) ? 18f : 5f;
            float rBot = (i == modes.length - 1) ? 18f : 5f;
            row.setBackground(ripple(
                    selectedMode ? palette.primaryContainer : palette.surfaceContainer,
                    palette.rippleColor,
                    rTop, rTop, rBot, rBot, 0, 0));
            TextView tv = createText(
                    getString(labels[i]),
                    14.5f,
                    selectedMode ? palette.onPrimaryContainer : palette.onSurface,
                    FONT_MEDIUM);
            row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (selectedMode) {
                TextView tag = createText(getString(R.string.tag_enabled), 10.5f, palette.primary, FONT_MEDIUM);
                row.addView(tag);
            }
            row.setOnClickListener(v -> {
                if (dialogHolder[0] != null) dialogHolder[0].dismiss();
                try {
                    prefs.edit().putInt(ModuleConfig.KEY_FOLD_CONTINUITY_MODE, mode).apply();
                    refreshFoldContinuitySummaries();
                    show(getString(R.string.msg_effect_realtime));
                    if (mode == ModuleConfig.FOLD_CONT_SELECTED && ModuleConfig.foldContinuityApps().isEmpty()) {
                        showFoldContinuityAppsDialog();
                    }
                } catch (RuntimeException e) {
                    show(getString(R.string.msg_save_failed, String.valueOf(e.getMessage())));
                }
            });
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = (i == modes.length - 1) ? 0 : dp(2);
            content.addView(row, rowLp);
        }

        AlertDialog d = trackDialog(dialogBuilder()
                .setTitle(R.string.row_fold_mode_title)
                .setView(content)
                .setNegativeButton(R.string.btn_cancel, null)
                .show());
        dialogHolder[0] = d;
        configureCoverSafeDialogWindow(d, false, false);
    }

    private void showFoldContinuityAppsDialog() {
        if (!CoverDisplay.isSupportedFirmware() || prefs == null || service == null) return;
        final Set<String> selectedPkgs = new LinkedHashSet<>(ModuleConfig.foldContinuityApps());
        final Set<String> uninstalledPkgs = new LinkedHashSet<>();

        boolean compact = isCompactCoverUi();
        boolean narrowFooter = isNarrowDialogFooter();
        int safeHeight = currentSafeWindowFrame().safeRect().height();
        boolean compactHeight = safeHeight < dp(420);
        boolean ultraCompactHeight = safeHeight < dp(320);

        Dialog dialog = trackDialog(new Dialog(this));
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        int sheetPadH = compact ? dp(8) : dp(20);
        int sheetPadV = dp(ultraCompactHeight ? 6 : (compactHeight ? 10 : 16));
        sheet.setPadding(sheetPadH, sheetPadV, sheetPadH, sheetPadV);
        sheet.setBackground(rounded(palette.surfaceContainerLow, ultraCompactHeight ? 22f : 28f));

        TextView titleView = createText(getString(R.string.picker_fold_apps_title),
                ultraCompactHeight ? 15f : (compact ? 18f : 20f), palette.onSurface, FONT_BOLD);
        titleView.setAccessibilityHeading(true);
        sheet.addView(titleView);
        TextView subView = createText(getString(R.string.picker_fold_apps_sub),
                ultraCompactHeight ? 10.5f : 12f, palette.onSurfaceVariant, FONT_REGULAR);
        if (ultraCompactHeight) {
            subView.setSingleLine(true);
            subView.setEllipsize(TextUtils.TruncateAt.END);
            subView.setPadding(0, dp(1), 0, dp(3));
        } else {
            subView.setPadding(0, dp(3), 0, dp(6));
        }
        sheet.addView(subView);

        final List<AppEntry> allApps = new ArrayList<>();
        final Runnable[] refilterRef = new Runnable[1];
        final PruneBannerHolder[] pruneBannerRef = new PruneBannerHolder[1];
        pruneBannerRef[0] = createUninstalledPruneBanner(compact, ultraCompactHeight, () -> {
            int removed = 0;
            for (String missing : new ArrayList<>(uninstalledPkgs)) {
                if (selectedPkgs.remove(missing)) removed++;
            }
            allApps.removeIf(e -> e.uninstalled);
            uninstalledPkgs.clear();
            pruneBannerRef[0].update(this, 0);
            if (refilterRef[0] != null) refilterRef[0].run();
            if (removed > 0) show(getString(R.string.msg_uninstalled_pruned, removed));
        });
        LinearLayout.LayoutParams pruneLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pruneLp.bottomMargin = dp(ultraCompactHeight ? 3 : 6);
        sheet.addView(pruneBannerRef[0].container(), pruneLp);

        LinearLayout searchBox = new LinearLayout(this);
        searchBox.setOrientation(LinearLayout.HORIZONTAL);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setPadding(dp(12), dp(2), dp(12), dp(2));
        searchBox.setBackground(rounded(palette.surfaceContainerHigh, 20f));
        EditText searchInput = new EditText(this);
        searchInput.setHint(R.string.picker_search_hint);
        searchInput.setHintTextColor(palette.onSurfaceVariant);
        searchInput.setTextColor(palette.onSurface);
        searchInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        searchInput.setSingleLine(true);
        searchInput.setBackground(null);
        searchInput.setMinHeight(dp(40));
        searchBox.addView(searchInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchLp.bottomMargin = dp(4);
        sheet.addView(searchBox, searchLp);

        final int[] activeFilter = {0};
        int[] filterRes = {R.string.filter_third, R.string.filter_selected, R.string.filter_all, R.string.filter_system};
        final List<TextView> chipViews = new ArrayList<>();
        final AppListAdapter adapter = new AppListAdapter(false);

        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < filterRes.length; i++) {
            final int idx = i;
            TextView chip = createChip(getString(filterRes[i]), palette.onSurfaceVariant, ultraCompactHeight);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.rightMargin = dp(6);
            chip.setOnClickListener(v -> {
                activeFilter[0] = idx;
                if (refilterRef[0] != null) refilterRef[0].run();
            });
            chipViews.add(chip);
            chipRow.addView(chip, clp);
        }
        chipScroll.addView(chipRow);
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipLp.bottomMargin = dp(4);
        sheet.addView(chipScroll, chipLp);

        FrameLayout listFrame = new FrameLayout(this);
        ListView listView = new ListView(this);
        listView.setVerticalScrollBarEnabled(false);
        listView.setDivider(new ColorDrawable(Color.TRANSPARENT));
        listView.setDividerHeight(dp(2));
        listView.setSelector(new ColorDrawable(Color.TRANSPARENT));
        listView.setAdapter(adapter);
        TextView emptyView = createText(getString(R.string.picker_loading), 13.5f, palette.onSurfaceVariant, FONT_REGULAR);
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
            if (app.selected) {
                selectedPkgs.add(app.packageName);
                if (app.uninstalled) {
                    uninstalledPkgs.add(app.packageName);
                    pruneBannerRef[0].update(this, uninstalledPkgs.size());
                }
            } else {
                selectedPkgs.remove(app.packageName);
                if (uninstalledPkgs.remove(app.packageName)) {
                    pruneBannerRef[0].update(this, uninstalledPkgs.size());
                }
            }
            if (view.getTag() instanceof AppRowHolder h && h.entry == app) {
                h.sw.setChecked(app.selected, false);
            } else {
                adapter.notifyDataSetChanged();
            }
        });

        refilterRef[0] = () -> {
            for (int i = 0; i < chipViews.size(); i++) {
                boolean sel = (i == activeFilter[0]);
                chipViews.get(i).setBackground(chipBackground(sel, ultraCompactHeight));
                chipViews.get(i).setTextColor(sel ? palette.onSecondaryContainer : palette.onSurfaceVariant);
            }
            String q = searchInput.getText() == null ? "" : searchInput.getText().toString().trim().toLowerCase(Locale.ROOT);
            adapter.items.clear();
            for (AppEntry app : allApps) {
                boolean match = switch (activeFilter[0]) {
                    case 0 -> !app.systemApp;
                    case 1 -> app.selected;
                    case 3 -> app.systemApp;
                    default -> true;
                };
                if (!match) continue;
                if (!q.isEmpty() && !app.labelLower.contains(q) && !app.packageName.toLowerCase(Locale.ROOT).contains(q)) {
                    continue;
                }
                adapter.items.add(app);
            }
            adapter.notifyDataSetChanged();
        };

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                mainHandler.removeCallbacks(refilterRef[0]);
                mainHandler.postDelayed(refilterRef[0], 120);
            }
        });

        float btnSp = compact ? 12.5f : 13.5f;
        int btnMinH = dp(ultraCompactHeight ? 36 : 44);
        int btnPadH = compact ? dp(10) : dp(14);
        int savePadH = compact ? dp(12) : dp(18);
        int btnPadV = dp(ultraCompactHeight ? 6 : 8);

        TextView clearBtn = createText(getString(R.string.picker_pinned_clear), btnSp, palette.onSurfaceVariant, FONT_MEDIUM);
        clearBtn.setGravity(Gravity.CENTER);
        clearBtn.setSingleLine(true);
        clearBtn.setEllipsize(TextUtils.TruncateAt.END);
        clearBtn.setMinHeight(btnMinH);
        clearBtn.setPadding(btnPadH, btnPadV, btnPadH, btnPadV);
        clearBtn.setBackground(pillRipple(palette.surfaceContainerHigh, palette.rippleColor, 20f));
        clearBtn.setOnClickListener(v -> {
            selectedPkgs.clear();
            uninstalledPkgs.clear();
            allApps.removeIf(e -> e.uninstalled);
            pruneBannerRef[0].update(this, 0);
            for (AppEntry app : allApps) app.selected = false;
            refilterRef[0].run();
        });

        TextView cancelBtn = createText(getString(R.string.btn_cancel), btnSp, palette.primary, FONT_MEDIUM);
        cancelBtn.setGravity(Gravity.CENTER);
        cancelBtn.setSingleLine(true);
        cancelBtn.setEllipsize(TextUtils.TruncateAt.END);
        cancelBtn.setMinHeight(btnMinH);
        cancelBtn.setPadding(btnPadH, btnPadV, btnPadH, btnPadV);
        cancelBtn.setBackground(pillRipple(Color.TRANSPARENT, palette.rippleColor, 20f));
        cancelBtn.setOnClickListener(v -> dialog.dismiss());

        TextView saveBtn = createText(getString(R.string.btn_save_fold_apps), btnSp, palette.onPrimary, FONT_MEDIUM);
        saveBtn.setGravity(Gravity.CENTER);
        saveBtn.setSingleLine(true);
        saveBtn.setEllipsize(TextUtils.TruncateAt.END);
        saveBtn.setMinHeight(btnMinH);
        saveBtn.setPadding(savePadH, btnPadV, savePadH, btnPadV);
        saveBtn.setBackground(pillRipple(palette.primary, palette.rippleColor, 20f));
        saveBtn.setOnClickListener(v -> {
            dialog.dismiss();
            try {
                String serialized = ModuleConfig.serializePackageList(selectedPkgs);
                SharedPreferences.Editor ed = prefs.edit()
                        .putString(ModuleConfig.KEY_FOLD_CONTINUITY_APPS, serialized);
                if (!selectedPkgs.isEmpty() && ModuleConfig.foldContinuityMode() == ModuleConfig.FOLD_CONT_SYSTEM) {
                    ed.putInt(ModuleConfig.KEY_FOLD_CONTINUITY_MODE, ModuleConfig.FOLD_CONT_SELECTED);
                }
                ed.apply();
                refreshFoldContinuitySummaries();
                show(getString(R.string.msg_fold_apps_saved, selectedPkgs.size()));
            } catch (RuntimeException e) {
                show(getString(R.string.msg_save_failed, String.valueOf(e.getMessage())));
            }
        });

        if (narrowFooter) {
            LinearLayout footerCol = new LinearLayout(this);
            footerCol.setOrientation(LinearLayout.VERTICAL);
            footerCol.setPadding(0, dp(ultraCompactHeight ? 4 : 8), 0, 0);
            LinearLayout topBtnRow = new LinearLayout(this);
            topBtnRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams clrLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            clrLp.rightMargin = dp(6);
            topBtnRow.addView(clearBtn, clrLp);
            topBtnRow.addView(cancelBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            footerCol.addView(topBtnRow);
            LinearLayout.LayoutParams saveFullLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            saveFullLp.topMargin = dp(6);
            footerCol.addView(saveBtn, saveFullLp);
            sheet.addView(footerCol);
        } else {
            LinearLayout bottomRow = new LinearLayout(this);
            bottomRow.setOrientation(LinearLayout.HORIZONTAL);
            bottomRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            bottomRow.setPadding(0, dp(ultraCompactHeight ? 4 : 8), 0, 0);
            bottomRow.addView(clearBtn);
            bottomRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
            bottomRow.addView(cancelBtn);
            LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            saveLp.leftMargin = dp(4);
            bottomRow.addView(saveBtn, saveLp);
            sheet.addView(bottomRow);
        }

        dialog.setContentView(sheet, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dialog.setOnDismissListener(d -> mainHandler.removeCallbacks(refilterRef[0]));
        configureCoverSafePickerWindow(dialog, false);
        dialog.show();
        configureCoverSafePickerWindow(dialog, false);

        PackageManager pm = getPackageManager();
        List<String> orderSnapshot = new ArrayList<>(selectedPkgs);
        IO.execute(() -> {
            List<AppEntry> loaded;
            try {
                loaded = loadLaunchableAppsForWidget(pm, orderSnapshot);
                for (AppEntry e : loaded) appLabelCache.put(e.packageName, e.label);
            } catch (RuntimeException e) {
                loaded = List.of();
            }
            Set<String> seenPkgs = new HashSet<>();
            for (AppEntry e : loaded) seenPkgs.add(e.packageName);
            List<String> missing = new ArrayList<>();
            List<AppEntry> combined = new ArrayList<>(loaded);
            for (String pkg : orderSnapshot) {
                if (seenPkgs.contains(pkg)) continue;
                if (!isAppTargetInstalled(pm, null, pkg)) {
                    missing.add(pkg);
                    combined.add(0, new AppEntry(pkg, pkg, false, true, "", null, 0, true));
                }
            }
            mainHandler.post(() -> {
                if (isDestroyed() || !dialog.isShowing()) return;
                allApps.addAll(combined);
                for (AppEntry e : combined) {
                    e.selected = selectedPkgs.contains(e.packageName);
                }
                uninstalledPkgs.clear();
                uninstalledPkgs.addAll(missing);
                pruneBannerRef[0].update(this, uninstalledPkgs.size());
                emptyView.setText(R.string.picker_empty);
                refilterRef[0].run();
            });
        });
    }

    private void showImeTuningDialog() {
        if (!CoverDisplay.isSupportedFirmware() || prefs == null || service == null) return;
        boolean compact = isCompactCoverUi();
        boolean narrowFooter = isNarrowDialogFooter();
        final int[] scaleState = {ModuleConfig.imeDpiScalePercent()};
        final int[] heightState = {ModuleConfig.imeMaxHeightPercent()};
        final int[] insetState = {ModuleConfig.imeTopInsetPx()};

        Dialog dialog = trackDialog(new Dialog(this));
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(compact ? 12 : 20), dp(14), dp(compact ? 12 : 20), dp(14));
        sheet.setBackground(rounded(palette.surfaceContainerLow, 26f));

        TextView titleView = createText(getString(R.string.dialog_ime_tuning_title),
                compact ? 18f : 20f, palette.onSurface, FONT_BOLD);
        titleView.setAccessibilityHeading(true);
        titleView.setPadding(0, 0, 0, dp(10));
        sheet.addView(titleView);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body);
        sheet.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        final int[] scaleOptions = {80, 90, 100, 110, 120};
        final int[] heightOptions = {80, 90, 100, 110};
        final int[] insetOptions = {-1, 0, 260, 398, 430};
        final Runnable[] refreshChips = new Runnable[1];

        java.util.function.BiConsumer<String, LinearLayout> addLabel = (txt, parent) -> {
            TextView lbl = createText(txt, 13f, palette.primary, FONT_MEDIUM);
            lbl.setPadding(dp(2), dp(8), dp(2), dp(4));
            parent.addView(lbl);
        };

        addLabel.accept(getString(R.string.ime_tuning_scale_label), body);
        HorizontalScrollView scaleScroll = new HorizontalScrollView(this);
        scaleScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout scaleRow = new LinearLayout(this);
        scaleScroll.addView(scaleRow);
        body.addView(scaleScroll);

        addLabel.accept(getString(R.string.ime_tuning_height_label), body);
        HorizontalScrollView heightScroll = new HorizontalScrollView(this);
        heightScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout heightRow = new LinearLayout(this);
        heightScroll.addView(heightRow);
        body.addView(heightScroll);

        addLabel.accept(getString(R.string.ime_tuning_inset_label), body);
        HorizontalScrollView insetScroll = new HorizontalScrollView(this);
        insetScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout insetRow = new LinearLayout(this);
        insetScroll.addView(insetRow);
        body.addView(insetScroll);

        refreshChips[0] = () -> {
            scaleRow.removeAllViews();
            for (int v : scaleOptions) {
                TextView p = createChoicePill(v + "%", v == scaleState[0], compact);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = dp(6);
                p.setOnClickListener(view -> {
                    scaleState[0] = v;
                    refreshChips[0].run();
                });
                scaleRow.addView(p, lp);
            }
            heightRow.removeAllViews();
            for (int v : heightOptions) {
                TextView p = createChoicePill(v + "%", v == heightState[0], compact);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = dp(6);
                p.setOnClickListener(view -> {
                    heightState[0] = v;
                    refreshChips[0].run();
                });
                heightRow.addView(p, lp);
            }
            insetRow.removeAllViews();
            for (int v : insetOptions) {
                TextView p = createChoicePill(imeInsetLabel(v), v == insetState[0], compact);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = dp(6);
                p.setOnClickListener(view -> {
                    insetState[0] = v;
                    refreshChips[0].run();
                });
                insetRow.addView(p, lp);
            }
        };
        refreshChips[0].run();

        TextView switchImeBtn = createText(getString(R.string.btn_switch_ime), 13f, palette.onSecondaryContainer, FONT_MEDIUM);
        switchImeBtn.setGravity(Gravity.CENTER);
        switchImeBtn.setSingleLine(true);
        switchImeBtn.setEllipsize(TextUtils.TruncateAt.END);
        switchImeBtn.setMinHeight(dp(44));
        switchImeBtn.setPadding(dp(14), dp(10), dp(14), dp(10));
        switchImeBtn.setBackground(pillRipple(palette.secondaryContainer, palette.rippleColor, 20f));
        LinearLayout.LayoutParams swImeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        swImeLp.topMargin = dp(12);
        switchImeBtn.setOnClickListener(v -> {
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm != null) imm.showInputMethodPicker();
        });
        body.addView(switchImeBtn, swImeLp);

        float btnSp = compact ? 12.5f : 13.5f;
        int btnPadH = compact ? dp(10) : dp(14);
        TextView resetBtn = createText(getString(R.string.btn_reset_default), btnSp, palette.onSurfaceVariant, FONT_MEDIUM);
        resetBtn.setGravity(Gravity.CENTER);
        resetBtn.setSingleLine(true);
        resetBtn.setEllipsize(TextUtils.TruncateAt.END);
        resetBtn.setMinHeight(dp(44));
        resetBtn.setPadding(btnPadH, dp(8), btnPadH, dp(8));
        resetBtn.setBackground(pillRipple(palette.surfaceContainerHigh, palette.rippleColor, 20f));
        resetBtn.setOnClickListener(v -> {
            scaleState[0] = ModuleConfig.DEFAULT_IME_DPI_SCALE_PERCENT;
            heightState[0] = ModuleConfig.DEFAULT_IME_MAX_HEIGHT_PERCENT;
            insetState[0] = ModuleConfig.IME_TOP_INSET_AUTO;
            refreshChips[0].run();
        });

        TextView cancelBtn = createText(getString(R.string.btn_cancel), btnSp, palette.primary, FONT_MEDIUM);
        cancelBtn.setGravity(Gravity.CENTER);
        cancelBtn.setSingleLine(true);
        cancelBtn.setEllipsize(TextUtils.TruncateAt.END);
        cancelBtn.setMinHeight(dp(44));
        cancelBtn.setPadding(btnPadH, dp(8), btnPadH, dp(8));
        cancelBtn.setBackground(pillRipple(Color.TRANSPARENT, palette.rippleColor, 20f));
        cancelBtn.setOnClickListener(v -> dialog.dismiss());

        TextView saveBtn = createText(getString(R.string.btn_save_quick_slots), btnSp, palette.onPrimary, FONT_MEDIUM);
        saveBtn.setGravity(Gravity.CENTER);
        saveBtn.setSingleLine(true);
        saveBtn.setEllipsize(TextUtils.TruncateAt.END);
        saveBtn.setMinHeight(dp(44));
        saveBtn.setPadding(compact ? dp(14) : dp(18), dp(8), compact ? dp(14) : dp(18), dp(8));
        saveBtn.setBackground(pillRipple(palette.primary, palette.rippleColor, 20f));
        saveBtn.setOnClickListener(v -> {
            dialog.dismiss();
            try {
                prefs.edit()
                        .putInt(ModuleConfig.KEY_IME_DPI_SCALE_PERCENT, scaleState[0])
                        .putInt(ModuleConfig.KEY_IME_MAX_HEIGHT_PERCENT, heightState[0])
                        .putInt(ModuleConfig.KEY_IME_TOP_INSET_PX, insetState[0])
                        .apply();
                refreshImeTuningSummaries();
                show(getString(R.string.msg_ime_tuning_saved));
            } catch (RuntimeException e) {
                show(getString(R.string.msg_save_failed, String.valueOf(e.getMessage())));
            }
        });

        if (narrowFooter) {
            LinearLayout footerCol = new LinearLayout(this);
            footerCol.setOrientation(LinearLayout.VERTICAL);
            footerCol.setPadding(0, dp(10), 0, 0);
            LinearLayout topBtnRow = new LinearLayout(this);
            topBtnRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            resetLp.rightMargin = dp(6);
            topBtnRow.addView(resetBtn, resetLp);
            topBtnRow.addView(cancelBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            footerCol.addView(topBtnRow);
            LinearLayout.LayoutParams saveFullLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            saveFullLp.topMargin = dp(6);
            footerCol.addView(saveBtn, saveFullLp);
            sheet.addView(footerCol);
        } else {
            LinearLayout bottomRow = new LinearLayout(this);
            bottomRow.setOrientation(LinearLayout.HORIZONTAL);
            bottomRow.setGravity(Gravity.CENTER_VERTICAL);
            bottomRow.setPadding(0, dp(12), 0, 0);
            bottomRow.addView(resetBtn);
            bottomRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
            bottomRow.addView(cancelBtn);
            LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            saveLp.leftMargin = dp(4);
            bottomRow.addView(saveBtn, saveLp);
            sheet.addView(bottomRow);
        }

        dialog.setContentView(sheet, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        configureCoverSafePickerWindow(dialog, true);
        dialog.show();
        configureCoverSafePickerWindow(dialog, true);
    }

    private void showPerAppRulesManagerDialog() {
        if (!CoverDisplay.isSupportedFirmware() || prefs == null || service == null) return;
        boolean compact = isCompactCoverUi();
        boolean narrowFooter = isNarrowDialogFooter();
        int safeHeight = currentSafeWindowFrame().safeRect().height();
        boolean compactHeight = safeHeight < dp(420);
        boolean ultraCompactHeight = safeHeight < dp(320);

        Dialog dialog = trackDialog(new Dialog(this));
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        int sheetPadH = compact ? dp(8) : dp(20);
        int sheetPadV = dp(ultraCompactHeight ? 6 : (compactHeight ? 10 : 16));
        sheet.setPadding(sheetPadH, sheetPadV, sheetPadH, sheetPadV);
        sheet.setBackground(rounded(palette.surfaceContainerLow, ultraCompactHeight ? 22f : 28f));

        TextView titleView = createText(getString(R.string.picker_rules_title),
                ultraCompactHeight ? 15f : (compact ? 18f : 20f), palette.onSurface, FONT_BOLD);
        titleView.setAccessibilityHeading(true);
        sheet.addView(titleView);
        TextView subView = createText(getString(R.string.picker_rules_sub),
                ultraCompactHeight ? 10.5f : 12f, palette.onSurfaceVariant, FONT_REGULAR);
        if (ultraCompactHeight) {
            subView.setSingleLine(true);
            subView.setEllipsize(TextUtils.TruncateAt.END);
            subView.setPadding(0, dp(1), 0, dp(3));
        } else {
            subView.setPadding(0, dp(3), 0, dp(6));
        }
        sheet.addView(subView);

        final List<AppEntry> allApps = new ArrayList<>();
        final List<AppEntry> filteredApps = new ArrayList<>();
        final Set<String> uninstalledRulePkgs = new LinkedHashSet<>();
        final Runnable[] refilterRef = new Runnable[1];

        final PruneBannerHolder[] pruneBannerRef = new PruneBannerHolder[1];
        pruneBannerRef[0] = createUninstalledPruneBanner(compact, ultraCompactHeight, () -> {
            if (uninstalledRulePkgs.isEmpty()) return;
            try {
                Map<String, ModuleConfig.PerAppRule> next = new LinkedHashMap<>(ModuleConfig.perAppRules());
                int removed = 0;
                for (String missing : new ArrayList<>(uninstalledRulePkgs)) {
                    if (next.remove(missing) != null) removed++;
                }
                String json = ModuleConfig.serializePerAppRulesJson(next, false);
                prefs.edit().putString(ModuleConfig.KEY_PER_APP_RULES, json).apply();
                allApps.removeIf(e -> e.uninstalled);
                uninstalledRulePkgs.clear();
                pruneBannerRef[0].update(this, 0);
                refreshPerAppRulesSummaries();
                if (refilterRef[0] != null) refilterRef[0].run();
                if (removed > 0) show(getString(R.string.msg_uninstalled_pruned, removed));
            } catch (RuntimeException e) {
                show(getString(R.string.msg_save_failed, String.valueOf(e.getMessage())));
            }
        });
        LinearLayout.LayoutParams pruneLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pruneLp.bottomMargin = dp(ultraCompactHeight ? 3 : 6);
        sheet.addView(pruneBannerRef[0].container(), pruneLp);

        LinearLayout searchBox = new LinearLayout(this);
        searchBox.setOrientation(LinearLayout.HORIZONTAL);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setPadding(dp(12), dp(2), dp(12), dp(2));
        searchBox.setBackground(rounded(palette.surfaceContainerHigh, 20f));
        EditText searchInput = new EditText(this);
        searchInput.setHint(R.string.picker_search_hint);
        searchInput.setHintTextColor(palette.onSurfaceVariant);
        searchInput.setTextColor(palette.onSurface);
        searchInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        searchInput.setSingleLine(true);
        searchInput.setBackground(null);
        searchInput.setMinHeight(dp(40));
        searchBox.addView(searchInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchLp.bottomMargin = dp(4);
        sheet.addView(searchBox, searchLp);

        final int[] activeFilter = {ModuleConfig.perAppRules().isEmpty() ? 1 : 0};
        int[] filterRes = {R.string.filter_has_rules, R.string.filter_third, R.string.filter_system, R.string.filter_all};
        final List<TextView> chipViews = new ArrayList<>();

        BaseAdapter ruleAdapter = new BaseAdapter() {
            @Override public int getCount() { return filteredApps.size(); }
            @Override public AppEntry getItem(int pos) { return filteredApps.get(pos); }
            @Override public long getItemId(int pos) { return pos; }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                AppRowHolder holder;
                if (convertView != null && convertView.getTag() instanceof AppRowHolder h) {
                    holder = h;
                } else {
                    holder = new AppRowHolder(false);
                }
                AppEntry entry = filteredApps.get(position);
                holder.bind(entry, position, filteredApps.size(), false);
                ModuleConfig.PerAppRule rule = ModuleConfig.perAppRule(entry.packageName);
                if (rule != null && !rule.isDefault()) {
                    holder.tag.setText(formatPerAppRuleSummary(rule));
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
        for (int i = 0; i < filterRes.length; i++) {
            final int idx = i;
            TextView chip = createChip(getString(filterRes[i]), palette.onSurfaceVariant, ultraCompactHeight);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.rightMargin = dp(6);
            chip.setOnClickListener(v -> {
                activeFilter[0] = idx;
                if (refilterRef[0] != null) refilterRef[0].run();
            });
            chipViews.add(chip);
            chipRow.addView(chip, clp);
        }
        chipScroll.addView(chipRow);
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipLp.bottomMargin = dp(4);
        sheet.addView(chipScroll, chipLp);

        FrameLayout listFrame = new FrameLayout(this);
        ListView listView = new ListView(this);
        listView.setVerticalScrollBarEnabled(false);
        listView.setDivider(new ColorDrawable(Color.TRANSPARENT));
        listView.setDividerHeight(dp(2));
        listView.setSelector(new ColorDrawable(Color.TRANSPARENT));
        listView.setAdapter(ruleAdapter);
        TextView emptyView = createText(getString(R.string.picker_loading), 13.5f, palette.onSurfaceVariant, FONT_REGULAR);
        emptyView.setGravity(Gravity.CENTER);
        listFrame.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        listFrame.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        listView.setEmptyView(emptyView);
        sheet.addView(listFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        refilterRef[0] = () -> {
            for (int i = 0; i < chipViews.size(); i++) {
                boolean sel = (i == activeFilter[0]);
                chipViews.get(i).setBackground(chipBackground(sel, ultraCompactHeight));
                chipViews.get(i).setTextColor(sel ? palette.onSecondaryContainer : palette.onSurfaceVariant);
            }
            Map<String, ModuleConfig.PerAppRule> rulesMap = ModuleConfig.perAppRules();
            uninstalledRulePkgs.removeIf(pkg -> !rulesMap.containsKey(pkg));
            pruneBannerRef[0].update(this, uninstalledRulePkgs.size());
            String q = searchInput.getText() == null ? "" : searchInput.getText().toString().trim().toLowerCase(Locale.ROOT);
            filteredApps.clear();
            for (AppEntry app : allApps) {
                boolean hasRule = rulesMap.containsKey(app.packageName);
                boolean match = switch (activeFilter[0]) {
                    case 0 -> hasRule;
                    case 1 -> !app.systemApp;
                    case 2 -> app.systemApp;
                    default -> true;
                };
                if (!match) continue;
                if (!q.isEmpty() && !app.labelLower.contains(q) && !app.packageName.toLowerCase(Locale.ROOT).contains(q)) {
                    continue;
                }
                filteredApps.add(app);
            }
            filteredApps.sort(Comparator
                    .comparing((AppEntry a) -> !rulesMap.containsKey(a.packageName))
                    .thenComparing(a -> a.systemApp)
                    .thenComparing(a -> a.label, String.CASE_INSENSITIVE_ORDER));
            ruleAdapter.notifyDataSetChanged();
        };

        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= filteredApps.size()) return;
            AppEntry app = filteredApps.get(position);
            showSingleAppRuleDialog(app.packageName, app.label, () -> {
                refreshPerAppRulesSummaries();
                if (refilterRef[0] != null) refilterRef[0].run();
            });
        });

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                mainHandler.removeCallbacks(refilterRef[0]);
                mainHandler.postDelayed(refilterRef[0], 120);
            }
        });

        int btnMinH = dp(ultraCompactHeight ? 36 : 44);
        float btnSp = compact ? 12.5f : 13.5f;
        TextView jsonBtn = createText(getString(R.string.row_rules_json_title), btnSp, palette.onSecondaryContainer, FONT_MEDIUM);
        jsonBtn.setGravity(Gravity.CENTER);
        jsonBtn.setSingleLine(true);
        jsonBtn.setEllipsize(TextUtils.TruncateAt.END);
        jsonBtn.setMinHeight(btnMinH);
        jsonBtn.setPadding(dp(12), dp(8), dp(12), dp(8));
        jsonBtn.setBackground(pillRipple(palette.secondaryContainer, palette.rippleColor, 20f));
        jsonBtn.setOnClickListener(v -> showPerAppRulesJsonDialog(() -> {
            refreshPerAppRulesSummaries();
            if (refilterRef[0] != null) refilterRef[0].run();
        }));

        TextView closeBtn = createText(getString(R.string.btn_close), btnSp, palette.primary, FONT_MEDIUM);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setSingleLine(true);
        closeBtn.setEllipsize(TextUtils.TruncateAt.END);
        closeBtn.setMinHeight(btnMinH);
        closeBtn.setPadding(dp(16), dp(8), dp(16), dp(8));
        closeBtn.setBackground(pillRipple(Color.TRANSPARENT, palette.rippleColor, 20f));
        closeBtn.setOnClickListener(v -> dialog.dismiss());

        if (narrowFooter) {
            LinearLayout footerCol = new LinearLayout(this);
            footerCol.setOrientation(LinearLayout.VERTICAL);
            footerCol.setPadding(0, dp(ultraCompactHeight ? 4 : 8), 0, 0);
            footerCol.addView(jsonBtn, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams clsLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clsLp.topMargin = dp(4);
            footerCol.addView(closeBtn, clsLp);
            sheet.addView(footerCol);
        } else {
            LinearLayout footer = new LinearLayout(this);
            footer.setOrientation(LinearLayout.HORIZONTAL);
            footer.setGravity(Gravity.CENTER_VERTICAL);
            footer.setPadding(0, dp(ultraCompactHeight ? 4 : 8), 0, 0);
            footer.addView(jsonBtn);
            footer.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
            footer.addView(closeBtn);
            sheet.addView(footer);
        }

        dialog.setContentView(sheet, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dialog.setOnDismissListener(d -> mainHandler.removeCallbacks(refilterRef[0]));
        configureCoverSafePickerWindow(dialog, false);
        dialog.show();
        configureCoverSafePickerWindow(dialog, false);

        PackageManager pm = getPackageManager();
        IO.execute(() -> {
            List<AppEntry> entries = new ArrayList<>();
            List<String> missingRulePkgs = new ArrayList<>();
            try {
                LinkedHashSet<String> seen = new LinkedHashSet<>();
                for (AppEntry e : loadLaunchableAppsForWidget(pm, List.of())) {
                    if (ModuleConfig.isValidRuleTargetPackage(e.packageName) && seen.add(e.packageName)) {
                        entries.add(e);
                        appLabelCache.put(e.packageName, e.label);
                    }
                }
                Set<String> configuredRulePkgs = new LinkedHashSet<>(ModuleConfig.perAppRules().keySet());
                LinkedHashSet<String> extraPkgs = new LinkedHashSet<>(DEEP_COMPAT_ORDER);
                extraPkgs.addAll(configuredRulePkgs);
                for (String pkg : extraPkgs) {
                    if (!ModuleConfig.isValidRuleTargetPackage(pkg) || !seen.add(pkg)) continue;
                    try {
                        ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                        String label = resolvePackageLabel(pm, pkg);
                        appLabelCache.put(pkg, label);
                        entries.add(new AppEntry(label, pkg, isSystemApp(ai), false, null));
                    } catch (PackageManager.NameNotFoundException ignored) {
                        if (configuredRulePkgs.contains(pkg)) {
                            missingRulePkgs.add(pkg);
                            entries.add(0, new AppEntry(pkg, pkg, false, false, "", null, 0, true));
                        }
                    }
                }
            } catch (RuntimeException ignored) {
            }
            mainHandler.post(() -> {
                if (isDestroyed() || !dialog.isShowing()) return;
                allApps.addAll(entries);
                uninstalledRulePkgs.clear();
                uninstalledRulePkgs.addAll(missingRulePkgs);
                pruneBannerRef[0].update(this, uninstalledRulePkgs.size());
                emptyView.setText(R.string.picker_empty);
                refilterRef[0].run();
            });
        });
    }

    private Display findOuterDisplay() {
        try {
            Display current = getDisplay();
            if (CoverDisplay.isOuterDisplay(current)) return current;
            DisplayManager dm = getSystemService(DisplayManager.class);
            if (dm != null) {
                for (Display d : dm.getDisplays()) {
                    if (CoverDisplay.isOuterDisplay(d) && d.getState() != Display.STATE_OFF) {
                        return d;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void launchPackageForCoverTest(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch == null) {
                show(getString(R.string.msg_launch_app_failed));
                return;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Display outer = findOuterDisplay();
            if (outer != null) {
                try {
                    ActivityOptions opts = ActivityOptions.makeBasic();
                    opts.setLaunchDisplayId(outer.getDisplayId());
                    startActivity(launch, opts.toBundle());
                    return;
                } catch (RuntimeException ignored) {
                }
            }
            startActivity(launch);
        } catch (RuntimeException e) {
            Log.w(TAG, "launchPackageForCoverTest failed for " + pkg, e);
            show(getString(R.string.msg_launch_app_failed));
        }
    }

    private void showSingleAppRuleDialog(String pkg, String appLabel, Runnable onUpdated) {
        if (!CoverDisplay.isSupportedFirmware() || prefs == null || service == null) return;
        ModuleConfig.PerAppRule existing = ModuleConfig.perAppRule(pkg);
        final int[] gapState = {existing != null ? existing.topGapPx() : ModuleConfig.TOP_GAP_DEFAULT};
        final int[] widthState = {existing != null ? existing.widthMode() : ModuleConfig.WIDTH_MODE_DEFAULT};
        final int[] scaleState = {existing != null ? existing.scalePercent() : ModuleConfig.SCALE_DEFAULT};

        boolean compact = isCompactCoverUi();
        boolean narrowFooter = isNarrowDialogFooter();
        Dialog dialog = trackDialog(new Dialog(this));
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(compact ? 12 : 20), dp(14), dp(compact ? 12 : 20), dp(14));
        sheet.setBackground(rounded(palette.surfaceContainerLow, 26f));

        TextView titleView = createText(getString(R.string.rule_editor_title, appLabel),
                compact ? 17f : 19f, palette.onSurface, FONT_BOLD);
        titleView.setAccessibilityHeading(true);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        sheet.addView(titleView);
        TextView pkgView = createText(pkg, 11.5f, palette.onSurfaceVariant, FONT_MONO);
        pkgView.setPadding(0, dp(2), 0, dp(8));
        sheet.addView(pkgView);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body);
        sheet.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        final int[] gapOptions = {-1, 0, 30, 60, 114};
        final int[] gapLabelRes = {
                R.string.rule_top_gap_default, R.string.rule_top_gap_zero,
                R.string.rule_top_gap_30, R.string.rule_top_gap_60, R.string.rule_top_gap_114
        };
        final int[] widthOptions = {
                ModuleConfig.WIDTH_MODE_DEFAULT, ModuleConfig.WIDTH_MODE_SAFE_COLUMN, ModuleConfig.WIDTH_MODE_FULL
        };
        final int[] widthLabelRes = {
                R.string.rule_width_default, R.string.rule_width_column, R.string.rule_width_full
        };
        final int[] scaleOptions = {0, 70, 75, 80, 90, 100, 110};
        final Runnable[] refreshUi = new Runnable[1];

        java.util.function.BiConsumer<String, LinearLayout> addLabel = (txt, parent) -> {
            TextView lbl = createText(txt, 13f, palette.primary, FONT_MEDIUM);
            lbl.setPadding(dp(2), dp(8), dp(2), dp(4));
            parent.addView(lbl);
        };

        addLabel.accept(getString(R.string.rule_top_gap_label), body);
        HorizontalScrollView gapScroll = new HorizontalScrollView(this);
        gapScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout gapRow = new LinearLayout(this);
        gapScroll.addView(gapRow);
        body.addView(gapScroll);

        addLabel.accept(getString(R.string.rule_width_label), body);
        HorizontalScrollView widthScroll = new HorizontalScrollView(this);
        widthScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout widthRow = new LinearLayout(this);
        widthScroll.addView(widthRow);
        body.addView(widthScroll);

        addLabel.accept(getString(R.string.rule_scale_label), body);
        HorizontalScrollView scaleScroll = new HorizontalScrollView(this);
        scaleScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout scaleRow = new LinearLayout(this);
        scaleScroll.addView(scaleRow);
        body.addView(scaleScroll);

        refreshUi[0] = () -> {
            gapRow.removeAllViews();
            boolean presetGapMatched = false;
            for (int i = 0; i < gapOptions.length; i++) {
                final int val = gapOptions[i];
                boolean sel = (val == gapState[0]);
                if (sel) presetGapMatched = true;
                TextView p = createChoicePill(getString(gapLabelRes[i]), sel, compact);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = dp(6);
                p.setOnClickListener(v -> {
                    gapState[0] = val;
                    refreshUi[0].run();
                });
                gapRow.addView(p, lp);
            }
            String customLabel = (!presetGapMatched && gapState[0] >= 0)
                    ? (gapState[0] + "px")
                    : getString(R.string.rule_top_gap_custom);
            TextView customPill = createChoicePill(customLabel, !presetGapMatched && gapState[0] >= 0, compact);
            customPill.setOnClickListener(v -> {
                EditText input = new EditText(this);
                input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                input.setHint(R.string.rule_gap_custom_hint);
                if (gapState[0] >= 0) input.setText(String.valueOf(gapState[0]));
                AlertDialog customDlg = trackDialog(dialogBuilder()
                        .setTitle(R.string.rule_top_gap_label)
                        .setView(input)
                        .setNegativeButton(R.string.btn_cancel, null)
                        .setPositiveButton(R.string.btn_save_rule, (d, w) -> {
                            try {
                                int px = Integer.parseInt(input.getText().toString().trim());
                                gapState[0] = Math.max(ModuleConfig.MIN_TOP_GAP_PX,
                                        Math.min(ModuleConfig.MAX_TOP_GAP_PX, px));
                                refreshUi[0].run();
                            } catch (NumberFormatException ignored) {
                            }
                        })
                        .show());
                configureCoverSafeDialogWindow(customDlg, false, false);
            });
            gapRow.addView(customPill);

            widthRow.removeAllViews();
            for (int i = 0; i < widthOptions.length; i++) {
                final int val = widthOptions[i];
                TextView p = createChoicePill(getString(widthLabelRes[i]), val == widthState[0], compact);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = dp(6);
                p.setOnClickListener(v -> {
                    widthState[0] = val;
                    refreshUi[0].run();
                });
                widthRow.addView(p, lp);
            }

            scaleRow.removeAllViews();
            for (int v : scaleOptions) {
                String lbl = v == 0 ? getString(R.string.rule_scale_default) : getString(R.string.rule_scale_fmt, v);
                TextView p = createChoicePill(lbl, v == scaleState[0], compact);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = dp(6);
                p.setOnClickListener(view -> {
                    scaleState[0] = v;
                    refreshUi[0].run();
                });
                scaleRow.addView(p, lp);
            }
        };
        refreshUi[0].run();

        // "Launch on cover" test button inside scroll body
        TextView launchTestBtn = createText(getString(R.string.btn_launch_cover_test),
                13f, palette.onSecondaryContainer, FONT_MEDIUM);
        launchTestBtn.setGravity(Gravity.CENTER);
        launchTestBtn.setSingleLine(true);
        launchTestBtn.setEllipsize(TextUtils.TruncateAt.END);
        launchTestBtn.setMinHeight(dp(44));
        launchTestBtn.setPadding(dp(14), dp(10), dp(14), dp(10));
        launchTestBtn.setBackground(pillRipple(palette.secondaryContainer, palette.rippleColor, 20f));
        LinearLayout.LayoutParams launchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        launchLp.topMargin = dp(12);
        launchTestBtn.setOnClickListener(v -> {
            ModuleConfig.PerAppRule rule = ModuleConfig.PerAppRule.of(gapState[0], widthState[0], scaleState[0]);
            savePerAppRuleUpdate(pkg, rule);
            if (onUpdated != null) onUpdated.run();
            launchPackageForCoverTest(pkg);
        });
        body.addView(launchTestBtn, launchLp);

        float btnSp = compact ? 12.5f : 13.5f;
        int btnPadH = compact ? dp(10) : dp(14);
        TextView resetBtn = createText(getString(R.string.btn_reset_default), btnSp, palette.onSurfaceVariant, FONT_MEDIUM);
        resetBtn.setGravity(Gravity.CENTER);
        resetBtn.setSingleLine(true);
        resetBtn.setEllipsize(TextUtils.TruncateAt.END);
        resetBtn.setMinHeight(dp(44));
        resetBtn.setPadding(btnPadH, dp(8), btnPadH, dp(8));
        resetBtn.setBackground(pillRipple(palette.surfaceContainerHigh, palette.rippleColor, 20f));
        resetBtn.setOnClickListener(v -> {
            dialog.dismiss();
            savePerAppRuleUpdate(pkg, ModuleConfig.PerAppRule.DEFAULT);
            show(getString(R.string.msg_rule_cleared, appLabel));
            if (onUpdated != null) onUpdated.run();
        });

        TextView cancelBtn = createText(getString(R.string.btn_cancel), btnSp, palette.primary, FONT_MEDIUM);
        cancelBtn.setGravity(Gravity.CENTER);
        cancelBtn.setSingleLine(true);
        cancelBtn.setEllipsize(TextUtils.TruncateAt.END);
        cancelBtn.setMinHeight(dp(44));
        cancelBtn.setPadding(btnPadH, dp(8), btnPadH, dp(8));
        cancelBtn.setBackground(pillRipple(Color.TRANSPARENT, palette.rippleColor, 20f));
        cancelBtn.setOnClickListener(v -> dialog.dismiss());

        TextView saveBtn = createText(getString(R.string.btn_save_rule), btnSp, palette.onPrimary, FONT_MEDIUM);
        saveBtn.setGravity(Gravity.CENTER);
        saveBtn.setSingleLine(true);
        saveBtn.setEllipsize(TextUtils.TruncateAt.END);
        saveBtn.setMinHeight(dp(44));
        saveBtn.setPadding(compact ? dp(12) : dp(18), dp(8), compact ? dp(12) : dp(18), dp(8));
        saveBtn.setBackground(pillRipple(palette.primary, palette.rippleColor, 20f));
        saveBtn.setOnClickListener(v -> {
            dialog.dismiss();
            ModuleConfig.PerAppRule rule = ModuleConfig.PerAppRule.of(gapState[0], widthState[0], scaleState[0]);
            savePerAppRuleUpdate(pkg, rule);
            show(rule.isDefault()
                    ? getString(R.string.msg_rule_cleared, appLabel)
                    : getString(R.string.msg_rule_saved, appLabel));
            if (onUpdated != null) onUpdated.run();
        });

        if (narrowFooter) {
            LinearLayout footerCol = new LinearLayout(this);
            footerCol.setOrientation(LinearLayout.VERTICAL);
            footerCol.setPadding(0, dp(10), 0, 0);
            LinearLayout topBtnRow = new LinearLayout(this);
            topBtnRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rstLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            rstLp.rightMargin = dp(6);
            topBtnRow.addView(resetBtn, rstLp);
            topBtnRow.addView(cancelBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            footerCol.addView(topBtnRow);
            LinearLayout.LayoutParams saveFullLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            saveFullLp.topMargin = dp(6);
            footerCol.addView(saveBtn, saveFullLp);
            sheet.addView(footerCol);
        } else {
            LinearLayout bottomRow = new LinearLayout(this);
            bottomRow.setOrientation(LinearLayout.HORIZONTAL);
            bottomRow.setGravity(Gravity.CENTER_VERTICAL);
            bottomRow.setPadding(0, dp(14), 0, 0);
            bottomRow.addView(resetBtn);
            bottomRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
            bottomRow.addView(cancelBtn);
            LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            saveLp.leftMargin = dp(4);
            bottomRow.addView(saveBtn, saveLp);
            sheet.addView(bottomRow);
        }

        dialog.setContentView(sheet, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        configureCoverSafePickerWindow(dialog, true);
        dialog.show();
        configureCoverSafePickerWindow(dialog, true);
    }

    private void savePerAppRuleUpdate(String pkg, ModuleConfig.PerAppRule rule) {
        if (prefs == null) return;
        try {
            Map<String, ModuleConfig.PerAppRule> next = new LinkedHashMap<>(ModuleConfig.perAppRules());
            if (rule == null || rule.isDefault()) {
                next.remove(pkg);
            } else {
                next.put(pkg, rule);
            }
            String json = ModuleConfig.serializePerAppRulesJson(next, false);
            prefs.edit().putString(ModuleConfig.KEY_PER_APP_RULES, json).apply();
            refreshPerAppRulesSummaries();
        } catch (RuntimeException e) {
            show(getString(R.string.msg_save_failed, String.valueOf(e.getMessage())));
        }
    }

    private void showPerAppRulesJsonDialog() {
        showPerAppRulesJsonDialog(null);
    }

    private void showPerAppRulesJsonDialog(Runnable onUpdated) {
        if (!CoverDisplay.isSupportedFirmware() || prefs == null || service == null) return;
        boolean compact = isCompactCoverUi();
        boolean narrowFooter = isNarrowDialogFooter();
        Map<String, ModuleConfig.PerAppRule> currentRules = ModuleConfig.perAppRules();
        String initialJson = ModuleConfig.serializePerAppRulesJson(currentRules, true);

        Dialog dialog = trackDialog(new Dialog(this));
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(compact ? 10 : 20), dp(14), dp(compact ? 10 : 20), dp(14));
        sheet.setBackground(rounded(palette.surfaceContainerLow, 26f));

        TextView titleView = createText(getString(R.string.dialog_rules_json_title),
                compact ? 18f : 20f, palette.onSurface, FONT_BOLD);
        titleView.setAccessibilityHeading(true);
        sheet.addView(titleView);
        TextView subView = createText(getString(R.string.dialog_rules_json_sub, currentRules.size()),
                12f, palette.onSurfaceVariant, FONT_REGULAR);
        subView.setPadding(0, dp(3), 0, dp(8));
        sheet.addView(subView);

        EditText jsonEdit = new EditText(this);
        jsonEdit.setText(initialJson);
        jsonEdit.setHint(R.string.dialog_rules_json_hint);
        jsonEdit.setHintTextColor(palette.onSurfaceVariant);
        jsonEdit.setTextColor(palette.onSurface);
        jsonEdit.setTypeface(FONT_MONO);
        jsonEdit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        jsonEdit.setGravity(Gravity.TOP | Gravity.START);
        jsonEdit.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        jsonEdit.setPadding(dp(12), dp(10), dp(12), dp(10));
        jsonEdit.setBackground(rounded(palette.surfaceContainerHigh, 16f));
        sheet.addView(jsonEdit, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout topActions = new LinearLayout(this);
        topActions.setOrientation(LinearLayout.HORIZONTAL);
        topActions.setPadding(0, dp(10), 0, 0);

        TextView copyBtn = createText(getString(R.string.btn_copy_json), 12.5f, palette.onSecondaryContainer, FONT_MEDIUM);
        copyBtn.setGravity(Gravity.CENTER);
        copyBtn.setSingleLine(true);
        copyBtn.setEllipsize(TextUtils.TruncateAt.END);
        copyBtn.setMinHeight(dp(44));
        copyBtn.setPadding(dp(12), dp(8), dp(12), dp(8));
        copyBtn.setBackground(pillRipple(palette.secondaryContainer, palette.rippleColor, 20f));
        copyBtn.setOnClickListener(v -> {
            copyToClipboard(jsonEdit.getText().toString());
            show(getString(R.string.msg_json_copied));
        });
        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        actLp.rightMargin = dp(6);
        topActions.addView(copyBtn, actLp);

        TextView shareBtn = createText(getString(R.string.btn_share_json), 12.5f, palette.onSecondaryContainer, FONT_MEDIUM);
        shareBtn.setGravity(Gravity.CENTER);
        shareBtn.setSingleLine(true);
        shareBtn.setEllipsize(TextUtils.TruncateAt.END);
        shareBtn.setMinHeight(dp(44));
        shareBtn.setPadding(dp(12), dp(8), dp(12), dp(8));
        shareBtn.setBackground(pillRipple(palette.secondaryContainer, palette.rippleColor, 20f));
        shareBtn.setOnClickListener(v -> {
            try {
                Intent send = new Intent(Intent.ACTION_SEND)
                        .setType("application/json")
                        .putExtra(Intent.EXTRA_SUBJECT, "MixFlip2Cover Per-App Rules")
                        .putExtra(Intent.EXTRA_TEXT, jsonEdit.getText().toString());
                startActivity(Intent.createChooser(send, getString(R.string.btn_share_json)));
            } catch (RuntimeException ignored) {
            }
        });
        topActions.addView(shareBtn, new LinearLayout.LayoutParams(0, dp(44), 1f));
        sheet.addView(topActions);

        java.util.function.Consumer<Boolean> doImport = replaceAll -> {
            try {
                Map<String, ModuleConfig.PerAppRule> incoming =
                        ModuleConfig.parsePerAppRulesJsonStrict(jsonEdit.getText().toString());
                Map<String, ModuleConfig.PerAppRule> merged =
                        ModuleConfig.mergePerAppRules(ModuleConfig.perAppRules(), incoming, replaceAll);
                prefs.edit()
                        .putString(ModuleConfig.KEY_PER_APP_RULES, ModuleConfig.serializePerAppRulesJson(merged, false))
                        .apply();
                refreshPerAppRulesSummaries();
                dialog.dismiss();
                show(getString(R.string.msg_json_imported, incoming.size()));
                if (onUpdated != null) onUpdated.run();
            } catch (IllegalArgumentException e) {
                show(getString(R.string.msg_json_invalid, String.valueOf(e.getMessage())));
            } catch (RuntimeException e) {
                show(getString(R.string.msg_save_failed, String.valueOf(e.getMessage())));
            }
        };

        float btnSp = compact ? 12.5f : 13f;
        int btnPadH = compact ? dp(10) : dp(14);
        TextView closeBtn = createText(getString(R.string.btn_close), btnSp, palette.primary, FONT_MEDIUM);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setSingleLine(true);
        closeBtn.setEllipsize(TextUtils.TruncateAt.END);
        closeBtn.setMinHeight(dp(44));
        closeBtn.setPadding(btnPadH, dp(8), btnPadH, dp(8));
        closeBtn.setBackground(pillRipple(Color.TRANSPARENT, palette.rippleColor, 20f));
        closeBtn.setOnClickListener(v -> dialog.dismiss());

        TextView mergeBtn = createText(getString(R.string.btn_import_merge), btnSp, palette.onPrimaryContainer, FONT_MEDIUM);
        mergeBtn.setGravity(Gravity.CENTER);
        mergeBtn.setSingleLine(true);
        mergeBtn.setEllipsize(TextUtils.TruncateAt.END);
        mergeBtn.setMinHeight(dp(44));
        mergeBtn.setPadding(btnPadH, dp(8), btnPadH, dp(8));
        mergeBtn.setBackground(pillRipple(palette.primaryContainer, palette.rippleColor, 20f));
        mergeBtn.setOnClickListener(v -> doImport.accept(false));

        TextView replaceBtn = createText(getString(R.string.btn_import_replace), btnSp, palette.onPrimary, FONT_MEDIUM);
        replaceBtn.setGravity(Gravity.CENTER);
        replaceBtn.setSingleLine(true);
        replaceBtn.setEllipsize(TextUtils.TruncateAt.END);
        replaceBtn.setMinHeight(dp(44));
        replaceBtn.setPadding(btnPadH, dp(8), btnPadH, dp(8));
        replaceBtn.setBackground(pillRipple(palette.primary, palette.rippleColor, 20f));
        replaceBtn.setOnClickListener(v -> doImport.accept(true));

        if (narrowFooter) {
            LinearLayout footerCol = new LinearLayout(this);
            footerCol.setOrientation(LinearLayout.VERTICAL);
            footerCol.setPadding(0, dp(8), 0, 0);
            LinearLayout importRow = new LinearLayout(this);
            importRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            mLp.rightMargin = dp(6);
            importRow.addView(mergeBtn, mLp);
            importRow.addView(replaceBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            footerCol.addView(importRow);
            LinearLayout.LayoutParams clsLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clsLp.topMargin = dp(4);
            footerCol.addView(closeBtn, clsLp);
            sheet.addView(footerCol);
        } else {
            LinearLayout bottomRow = new LinearLayout(this);
            bottomRow.setOrientation(LinearLayout.HORIZONTAL);
            bottomRow.setGravity(Gravity.CENTER_VERTICAL);
            bottomRow.setPadding(0, dp(8), 0, 0);
            bottomRow.addView(closeBtn);
            bottomRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
            LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mLp.rightMargin = dp(6);
            bottomRow.addView(mergeBtn, mLp);
            bottomRow.addView(replaceBtn);
            sheet.addView(bottomRow);
        }

        dialog.setContentView(sheet, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        configureCoverSafePickerWindow(dialog, false);
        dialog.show();
        configureCoverSafePickerWindow(dialog, false);
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
            if (hostRow != null) {
                hostRow.setStateDescription(getVisibility() == View.VISIBLE ? state : null);
            }
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
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        CoverPreviewView(Context context) {
            super(context);
            textPaint.setTypeface(FONT_BOLD);
            textPaint.setTextAlign(Paint.Align.CENTER);
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

            // Status Orb above lenses (live Battery %, Dual-SIM dots & Orb interaction indicator)
            if (value(ModuleConfig.KEY_PANEL)) {
                float orbY = h * 0.20f;
                float orbR = dp(8.2f);
                boolean orbInteractive = ModuleConfig.hasOrbInteraction();
                if (orbInteractive) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(dp(1.2f));
                    paint.setColor(palette.primary);
                    paint.setAlpha(95);
                    c.drawCircle(camX, orbY, orbR + dp(2.8f), paint);
                    paint.setAlpha(255);
                }
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2.0f));
                paint.setColor(palette.primary);
                c.drawCircle(camX, orbY, orbR, paint);

                boolean showBatt = value(ModuleConfig.KEY_BATTERY_NUMBER);
                boolean showDual = value(ModuleConfig.KEY_DUAL_SIM);
                if (showBatt) {
                    textPaint.setColor(palette.primary);
                    textPaint.setTextSize(dp(6.2f));
                    float textY = orbY + (showDual ? dp(0.6f) : dp(2.2f));
                    c.drawText("88", camX, textY, textPaint);
                } else if (!showDual) {
                    paint.setStyle(Paint.Style.FILL);
                    c.drawCircle(camX, orbY, dp(2f), paint);
                }
                if (showDual) {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(palette.primary);
                    float dotR = dp(1.05f);
                    float row1Y = showBatt ? (orbY + dp(3.4f)) : (orbY - dp(1.4f));
                    float row2Y = showBatt ? (orbY + dp(5.8f)) : (orbY + dp(1.8f));
                    for (int i = -1; i <= 1; i++) {
                        float dx = camX + i * dp(2.8f);
                        c.drawCircle(dx, row1Y, dotR, paint);
                        c.drawCircle(dx, row2Y, dotR, paint);
                    }
                }
                if (orbInteractive) {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(palette.primary);
                    c.drawCircle(camX + orbR * 0.88f, orbY - orbR * 0.88f, dp(1.8f), paint);
                }
            }

            // Square Quick Actions 2x2 card or Live Island card between Status Orb and Lenses
            boolean quickEnabled = value(ModuleConfig.KEY_QUICK_WIDGET_ENABLED);
            boolean islandEnabled = value(ModuleConfig.KEY_LIVE_ISLAND);
            if (quickEnabled || islandEnabled) {
                float boxHalf = dp(8.2f);
                float boxY = h * 0.40f;
                rect.set(camX - boxHalf, boxY - boxHalf, camX + boxHalf, boxY + boxHalf);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(palette.secondaryContainer);
                c.drawRoundRect(rect, dp(4.5f), dp(4.5f), paint);
                if (quickEnabled) {
                    paint.setColor(palette.primary);
                    float cellHalf = dp(2.4f);
                    float off = dp(3.3f);
                    float cr = dp(1.4f);
                    c.drawRoundRect(camX - off - cellHalf, boxY - off - cellHalf,
                            camX - off + cellHalf, boxY - off + cellHalf, cr, cr, paint);
                    c.drawRoundRect(camX + off - cellHalf, boxY - off - cellHalf,
                            camX + off + cellHalf, boxY - off + cellHalf, cr, cr, paint);
                    c.drawRoundRect(camX - off - cellHalf, boxY + off - cellHalf,
                            camX - off + cellHalf, boxY + off + cellHalf, cr, cr, paint);
                    c.drawRoundRect(camX + off - cellHalf, boxY + off - cellHalf,
                            camX + off + cellHalf, boxY + off + cellHalf, cr, cr, paint);
                }
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
        static final int QUICK_GRID = 20;
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
                case Glyph.QUICK_GRID -> {
                    rect.set(3.2f, 3.2f, 20.8f, 20.8f);
                    c.drawRoundRect(rect, 4.4f, 4.4f, paint);
                    paint.setStrokeWidth(1.9f);
                    rect.set(6.2f, 6.2f, 10.8f, 10.8f);
                    c.drawRoundRect(rect, 1.5f, 1.5f, paint);
                    rect.set(13.2f, 6.2f, 17.8f, 10.8f);
                    c.drawRoundRect(rect, 1.5f, 1.5f, paint);
                    rect.set(6.2f, 13.2f, 10.8f, 17.8f);
                    c.drawRoundRect(rect, 1.5f, 1.5f, paint);
                    rect.set(13.2f, 13.2f, 17.8f, 17.8f);
                    c.drawRoundRect(rect, 1.5f, 1.5f, paint);
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
