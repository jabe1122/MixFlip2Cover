package io.github.jabe1122.mixflip2cover;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import io.github.libxposed.api.XposedModule;

/**
 * Centralized remote preferences access and live change dispatching.
 *
 * <p>Listeners are stored in a {@link CopyOnWriteArrayList} because they are added from hook callbacks on
 * arbitrary threads. Callers that register per-view/per-activity listeners must remove them again with
 * {@link #removeListener(Runnable)}.
 */
final class ModuleConfig {
    private static final String TAG = "MF2C.Config";
    static final String PREFS_NAME = "panel";

    static final String KEY_PANEL = "panel_enabled";
    static final String KEY_IMMERSIVE = "immersive_enabled";
    static final String KEY_HIDE_APP_NAMES = "hide_app_names";
    static final String KEY_BATTERY_NUMBER = "battery_number";
    static final String KEY_DUAL_SIM = "dual_sim";
    static final String KEY_LIVE_ISLAND = "live_island";
    static final String KEY_UNIVERSAL_COMPAT = "universal_compat";
    static final String KEY_THIRD_PARTY_IME = "third_party_ime";
    static final String KEY_VERBOSE_LOG = "verbose_log";
    static final String KEY_ALLOW_ALL_COVER_APPS = "cover_allow_all_apps";
    static final String KEY_DIRECT_OPEN_COVER_NOTIF = "cover_direct_open_notif";
    static final String KEY_PINNED_WIDGET_ENABLED = "widget_pinned_apps_enabled";
    static final String KEY_PINNED_WIDGET_APPS = "widget_pinned_apps";
    static final int MAX_PINNED_WIDGET_APPS = 4;
    static final String KEY_QUICK_WIDGET_ENABLED = "widget_quick_actions_enabled";
    static final String KEY_QUICK_WIDGET_SLOTS = "widget_quick_actions_slots";
    static final int QUICK_WIDGET_SLOT_COUNT = 4;

    // Feature 1: Fold-close seamless continuity
    static final String KEY_FOLD_CONTINUITY_MODE = "fold_continuity_mode";
    static final String KEY_FOLD_CONTINUITY_APPS = "fold_continuity_apps";
    static final String KEY_FOLD_POCKET_GUARD = "fold_pocket_guard";
    static final int FOLD_CONT_SYSTEM = 0;
    static final int FOLD_CONT_SELECTED = 1;
    static final int FOLD_CONT_ALL = 2;

    // Feature 2: Launcher all-apps unlock, empty-space double-tap lock, StatusOrb interactions
    static final String KEY_UNLOCK_LAUNCHER_APPS = "cover_unlock_launcher_apps";
    static final String KEY_DOUBLE_TAP_LOCK = "cover_double_tap_lock";
    static final String KEY_ORB_TAP_ACTION = "orb_tap_action";
    static final String KEY_ORB_LONG_PRESS_ACTION = "orb_long_press_action";

    // Feature 4: Universal cover IME tuning
    static final String KEY_IME_DPI_SCALE_PERCENT = "ime_dpi_scale_percent";
    static final String KEY_IME_MAX_HEIGHT_PERCENT = "ime_max_height_percent";
    static final String KEY_IME_TOP_INSET_PX = "ime_top_inset_px";
    static final int DEFAULT_IME_DPI_SCALE_PERCENT = 100;
    static final int MIN_IME_DPI_SCALE_PERCENT = 75;
    static final int MAX_IME_DPI_SCALE_PERCENT = 125;
    static final int DEFAULT_IME_MAX_HEIGHT_PERCENT = 100;
    static final int MIN_IME_MAX_HEIGHT_PERCENT = 75;
    static final int MAX_IME_MAX_HEIGHT_PERCENT = 115;
    static final int IME_TOP_INSET_AUTO = -1;
    static final int MIN_IME_TOP_INSET_PX = 0;
    static final int MAX_IME_TOP_INSET_PX = 460;

    // Feature 6: Per-app cover rules & JSON import/export
    static final String KEY_PER_APP_RULES = "per_app_cover_rules";
    static final String RULES_SCHEMA = "mixflip2cover.rules.v1";
    static final int WIDTH_MODE_DEFAULT = 0;
    static final int WIDTH_MODE_FULL = 1;
    static final int WIDTH_MODE_SAFE_COLUMN = 2;
    static final int TOP_GAP_DEFAULT = -1;
    static final int MIN_TOP_GAP_PX = 0;
    static final int MAX_TOP_GAP_PX = 160;
    static final int SCALE_DEFAULT = 0;
    static final int MIN_SCALE_PERCENT = 65;
    static final int MAX_SCALE_PERCENT = 115;

    static final String QA_NONE = "none";
    static final String QA_HOME = "home";
    static final String QA_RECENTS = "recents";
    static final String QA_LOCK = "lock";
    static final String QA_BACK = "back";
    static final String QA_NOTIFICATIONS = "notifications";
    static final String QA_CONTROL_CENTER = "control_center";
    static final String QA_TORCH = "torch";
    static final String QA_SCREENSHOT = "screenshot";
    static final String QA_RINGER = "ringer";
    static final String QA_ROTATION_LOCK = "rotation_lock";
    static final String QA_MEDIA_PLAY_PAUSE = "media_play_pause";
    static final String QA_BRIGHTNESS = "brightness";
    static final String QA_SCAN = "scan";
    static final String QA_APP_PREFIX = "app:";
    static final List<String> DEFAULT_QUICK_WIDGET_SLOT_LIST = List.of(QA_HOME, QA_RECENTS, QA_LOCK, QA_BACK);
    static final String DEFAULT_QUICK_WIDGET_SLOTS = "home,recents,lock,back";

    /** Third-party IME on the cover screen modifies system IME settings, so it is opt-in. */
    static final boolean DEFAULT_THIRD_PARTY_IME = false;

    /** Per-app cover override rule; default sentinel values leave system/module defaults intact. */
    record PerAppRule(int topGapPx, int widthMode, int scalePercent) {
        static final PerAppRule DEFAULT = new PerAppRule(TOP_GAP_DEFAULT, WIDTH_MODE_DEFAULT, SCALE_DEFAULT);

        static PerAppRule of(int topGapPx, int widthMode, int scalePercent) {
            int gap = topGapPx < 0 ? TOP_GAP_DEFAULT : Math.max(MIN_TOP_GAP_PX, Math.min(MAX_TOP_GAP_PX, topGapPx));
            int mode = (widthMode == WIDTH_MODE_FULL || widthMode == WIDTH_MODE_SAFE_COLUMN)
                    ? widthMode : WIDTH_MODE_DEFAULT;
            int scale = scalePercent <= 0 ? SCALE_DEFAULT
                    : Math.max(MIN_SCALE_PERCENT, Math.min(MAX_SCALE_PERCENT, scalePercent));
            return new PerAppRule(gap, mode, scale);
        }

        boolean isDefault() {
            return topGapPx < 0 && widthMode == WIDTH_MODE_DEFAULT && scalePercent <= 0;
        }
    }

    static volatile SharedPreferences prefs;
    static SharedPreferences.OnSharedPreferenceChangeListener changeListener;
    private static volatile boolean localDirectRead;
    private static volatile Boolean pinnedEnabledOverride;
    private static volatile String pinnedAppsOverride;
    private static volatile Boolean quickEnabledOverride;
    private static volatile String quickSlotsOverride;
    private static volatile Integer foldModeOverride;
    private static volatile String foldAppsOverride;
    private static volatile Boolean foldPocketOverride;
    private static volatile Boolean unlockLauncherAppsOverride;
    private static volatile String orbTapOverride;
    private static volatile String orbLongPressOverride;
    private static volatile Integer imeDpiScaleOverride;
    private static volatile Integer imeHeightOverride;
    private static volatile Integer imeTopInsetOverride;
    private static volatile String perAppRulesOverride;

    private static volatile String cachedFoldAppsRaw;
    private static volatile Set<String> cachedFoldAppsSet = Collections.emptySet();
    private static volatile String cachedRulesRaw;
    private static volatile Map<String, PerAppRule> cachedRulesMap = Collections.emptyMap();

    /**
     * Immutable snapshot of all module preferences for lock-free, allocation-free hot-path reads in
     * hooked processes ({@code system_server}, SystemUI, FlipHome, and adapted client apps).
     */
    private static final class ConfigSnapshot {
        final SharedPreferences sourcePrefs;
        final boolean panelEnabled;
        final boolean immersiveEnabled;
        final boolean hideAppNames;
        final boolean batteryNumber;
        final boolean dualSim;
        final boolean liveIslandRaw;
        final boolean universalCompatRaw;
        final boolean thirdPartyIme;
        final boolean verboseLog;
        final boolean allowAllCoverApps;
        final boolean directOpenCoverNotif;
        final boolean pinnedWidgetEnabled;
        final String pinnedWidgetAppsRaw;
        final List<String> pinnedWidgetApps;
        final boolean quickWidgetEnabled;
        final String quickWidgetSlotsRaw;
        final List<String> quickWidgetSlots;
        final int foldContinuityMode;
        final boolean foldPocketGuard;
        final String foldContinuityAppsRaw;
        final Set<String> foldContinuityApps;
        final boolean unlockLauncherApps;
        final boolean doubleTapLock;
        final String orbTapAction;
        final String orbLongPressAction;
        final int imeDpiScalePercent;
        final int imeMaxHeightPercent;
        final int imeTopInsetPx;
        final String perAppRulesRaw;
        final Map<String, PerAppRule> perAppRules;
        final boolean compatAllLoaded;
        final Set<String> disabledCompatPackages;

        ConfigSnapshot(
                SharedPreferences sourcePrefs,
                boolean panelEnabled,
                boolean immersiveEnabled,
                boolean hideAppNames,
                boolean batteryNumber,
                boolean dualSim,
                boolean liveIslandRaw,
                boolean universalCompatRaw,
                boolean thirdPartyIme,
                boolean verboseLog,
                boolean allowAllCoverApps,
                boolean directOpenCoverNotif,
                boolean pinnedWidgetEnabled,
                String pinnedWidgetAppsRaw,
                List<String> pinnedWidgetApps,
                boolean quickWidgetEnabled,
                String quickWidgetSlotsRaw,
                List<String> quickWidgetSlots,
                int foldContinuityMode,
                boolean foldPocketGuard,
                String foldContinuityAppsRaw,
                Set<String> foldContinuityApps,
                boolean unlockLauncherApps,
                boolean doubleTapLock,
                String orbTapAction,
                String orbLongPressAction,
                int imeDpiScalePercent,
                int imeMaxHeightPercent,
                int imeTopInsetPx,
                String perAppRulesRaw,
                Map<String, PerAppRule> perAppRules,
                boolean compatAllLoaded,
                Set<String> disabledCompatPackages) {
            this.sourcePrefs = sourcePrefs;
            this.panelEnabled = panelEnabled;
            this.immersiveEnabled = immersiveEnabled;
            this.hideAppNames = hideAppNames;
            this.batteryNumber = batteryNumber;
            this.dualSim = dualSim;
            this.liveIslandRaw = liveIslandRaw;
            this.universalCompatRaw = universalCompatRaw;
            this.thirdPartyIme = thirdPartyIme;
            this.verboseLog = verboseLog;
            this.allowAllCoverApps = allowAllCoverApps;
            this.directOpenCoverNotif = directOpenCoverNotif;
            this.pinnedWidgetEnabled = pinnedWidgetEnabled;
            this.pinnedWidgetAppsRaw = pinnedWidgetAppsRaw;
            this.pinnedWidgetApps = pinnedWidgetApps;
            this.quickWidgetEnabled = quickWidgetEnabled;
            this.quickWidgetSlotsRaw = quickWidgetSlotsRaw;
            this.quickWidgetSlots = quickWidgetSlots;
            this.foldContinuityMode = foldContinuityMode;
            this.foldPocketGuard = foldPocketGuard;
            this.foldContinuityAppsRaw = foldContinuityAppsRaw;
            this.foldContinuityApps = foldContinuityApps;
            this.unlockLauncherApps = unlockLauncherApps;
            this.doubleTapLock = doubleTapLock;
            this.orbTapAction = orbTapAction;
            this.orbLongPressAction = orbLongPressAction;
            this.imeDpiScalePercent = imeDpiScalePercent;
            this.imeMaxHeightPercent = imeMaxHeightPercent;
            this.imeTopInsetPx = imeTopInsetPx;
            this.perAppRulesRaw = perAppRulesRaw;
            this.perAppRules = perAppRules;
            this.compatAllLoaded = compatAllLoaded;
            this.disabledCompatPackages = disabledCompatPackages;
        }
    }

    private static volatile ConfigSnapshot cachedSnapshot = buildSnapshot(null);

    private static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    private ModuleConfig() {}

    private static ConfigSnapshot buildSnapshot(SharedPreferences sp) {
        boolean panel = readBoolean(sp, KEY_PANEL, true);
        boolean immersive = readBoolean(sp, KEY_IMMERSIVE, true);
        boolean hideNames = readBoolean(sp, KEY_HIDE_APP_NAMES, false);
        boolean battNum = readBoolean(sp, KEY_BATTERY_NUMBER, false);
        boolean dual = readBoolean(sp, KEY_DUAL_SIM, false);
        boolean liveIsl = readBoolean(sp, KEY_LIVE_ISLAND, true);
        boolean univCompat = readBoolean(sp, KEY_UNIVERSAL_COMPAT, true);
        boolean thirdIme = readBoolean(sp, KEY_THIRD_PARTY_IME, DEFAULT_THIRD_PARTY_IME);
        boolean verbose = readBoolean(sp, KEY_VERBOSE_LOG, false);
        boolean allowAll = readBoolean(sp, KEY_ALLOW_ALL_COVER_APPS, true);
        boolean directNotif = readBoolean(sp, KEY_DIRECT_OPEN_COVER_NOTIF, true);

        Boolean pinEnOv = pinnedEnabledOverride;
        boolean pinEn = pinEnOv != null ? pinEnOv : readBoolean(sp, KEY_PINNED_WIDGET_ENABLED, false);
        String pinRawOv = pinnedAppsOverride;
        String pinRaw = pinRawOv != null ? pinRawOv : readString(sp, KEY_PINNED_WIDGET_APPS, "");
        List<String> pinList = Collections.unmodifiableList(parsePinnedApps(pinRaw));

        Boolean qwEnOv = quickEnabledOverride;
        boolean qwEn = qwEnOv != null ? qwEnOv : readBoolean(sp, KEY_QUICK_WIDGET_ENABLED, false);
        String qwRawOv = quickSlotsOverride;
        String qwRaw = qwRawOv != null ? qwRawOv : readString(sp, KEY_QUICK_WIDGET_SLOTS, DEFAULT_QUICK_WIDGET_SLOTS);
        List<String> qwList = Collections.unmodifiableList(parseQuickActionSlots(qwRaw));

        Integer foldMOv = foldModeOverride;
        int foldM = foldMOv != null ? foldMOv : clampFoldContinuityMode(readInt(sp, KEY_FOLD_CONTINUITY_MODE, FOLD_CONT_SYSTEM));
        Boolean foldPOv = foldPocketOverride;
        boolean foldP = foldPOv != null ? foldPOv : readBoolean(sp, KEY_FOLD_POCKET_GUARD, true);
        String foldAOv = foldAppsOverride;
        String foldARaw = foldAOv != null ? foldAOv : readString(sp, KEY_FOLD_CONTINUITY_APPS, "");
        Set<String> foldASet = parsePackageSet(foldARaw);

        Boolean unlockOv = unlockLauncherAppsOverride;
        boolean unlock = unlockOv != null ? unlockOv : readBoolean(sp, KEY_UNLOCK_LAUNCHER_APPS, true);
        boolean dtLock = readBoolean(sp, KEY_DOUBLE_TAP_LOCK, true);

        String tapOv = orbTapOverride;
        String tap = tapOv != null ? tapOv : normalizeOrbActionToken(readString(sp, KEY_ORB_TAP_ACTION, QA_NONE));
        String longOv = orbLongPressOverride;
        String longAct = longOv != null ? longOv : normalizeOrbActionToken(readString(sp, KEY_ORB_LONG_PRESS_ACTION, QA_NONE));

        Integer dpiOv = imeDpiScaleOverride;
        int dpi = dpiOv != null ? dpiOv : clampImeDpiScale(readInt(sp, KEY_IME_DPI_SCALE_PERCENT, DEFAULT_IME_DPI_SCALE_PERCENT));
        Integer hOv = imeHeightOverride;
        int maxH = hOv != null ? hOv : clampImeHeightPercent(readInt(sp, KEY_IME_MAX_HEIGHT_PERCENT, DEFAULT_IME_MAX_HEIGHT_PERCENT));
        Integer insetOv = imeTopInsetOverride;
        int topInset = insetOv != null ? insetOv : clampImeTopInsetPx(readInt(sp, KEY_IME_TOP_INSET_PX, IME_TOP_INSET_AUTO));

        String rulesOv = perAppRulesOverride;
        String rulesRaw = rulesOv != null ? rulesOv : readString(sp, KEY_PER_APP_RULES, "");
        Map<String, PerAppRule> rulesMap = parsePerAppRulesJson(rulesRaw);

        boolean compatLoaded = false;
        Set<String> disabledCompat = Collections.emptySet();
        if (sp == null) {
            compatLoaded = true;
        } else {
            try {
                Map<String, ?> all = sp.getAll();
                if (all != null) {
                    HashSet<String> disabled = null;
                    for (Map.Entry<String, ?> entry : all.entrySet()) {
                        String key = entry.getKey();
                        if (key != null && key.startsWith("compat_") && Boolean.FALSE.equals(entry.getValue())) {
                            if (disabled == null) disabled = new HashSet<>();
                            disabled.add(key.substring("compat_".length()));
                        }
                    }
                    disabledCompat = disabled == null ? Collections.emptySet() : Collections.unmodifiableSet(disabled);
                    compatLoaded = true;
                }
            } catch (RuntimeException ignored) {
            }
        }

        return new ConfigSnapshot(
                sp,
                panel,
                immersive,
                hideNames,
                battNum,
                dual,
                liveIsl,
                univCompat,
                thirdIme,
                verbose,
                allowAll,
                directNotif,
                pinEn,
                pinRaw,
                pinList,
                qwEn,
                qwRaw,
                qwList,
                foldM,
                foldP,
                foldARaw,
                foldASet,
                unlock,
                dtLock,
                tap,
                longAct,
                dpi,
                maxH,
                topInset,
                rulesRaw,
                rulesMap,
                compatLoaded,
                disabledCompat);
    }

    private static void refreshSnapshot() {
        ConfigSnapshot next = buildSnapshot(prefs);
        cachedSnapshot = next;
        cachedFoldAppsRaw = next.foldContinuityAppsRaw;
        cachedFoldAppsSet = next.foldContinuityApps;
        cachedRulesRaw = next.perAppRulesRaw;
        cachedRulesMap = next.perAppRules;
    }

    private static ConfigSnapshot currentSnapshot() {
        ConfigSnapshot s = cachedSnapshot;
        SharedPreferences p = prefs;
        if (s.sourcePrefs != p) {
            s = buildSnapshot(p);
            cachedSnapshot = s;
        }
        return s;
    }

    private static void invalidateCaches(String key) {
        if (key == null || KEY_FOLD_CONTINUITY_APPS.equals(key)) {
            cachedFoldAppsRaw = null;
        }
        if (key == null || KEY_PER_APP_RULES.equals(key)) {
            cachedRulesRaw = null;
        }
    }

    private static SharedPreferences.OnSharedPreferenceChangeListener ensureChangeListener() {
        if (changeListener == null) {
            changeListener = (sp, k) -> {
                if (KEY_PINNED_WIDGET_ENABLED.equals(k)) {
                    pinnedEnabledOverride = null;
                } else if (KEY_PINNED_WIDGET_APPS.equals(k)) {
                    pinnedAppsOverride = null;
                } else if (KEY_QUICK_WIDGET_ENABLED.equals(k)) {
                    quickEnabledOverride = null;
                } else if (KEY_QUICK_WIDGET_SLOTS.equals(k)) {
                    quickSlotsOverride = null;
                } else if (KEY_FOLD_CONTINUITY_MODE.equals(k)) {
                    foldModeOverride = null;
                } else if (KEY_FOLD_CONTINUITY_APPS.equals(k)) {
                    foldAppsOverride = null;
                } else if (KEY_FOLD_POCKET_GUARD.equals(k)) {
                    foldPocketOverride = null;
                } else if (KEY_UNLOCK_LAUNCHER_APPS.equals(k)) {
                    unlockLauncherAppsOverride = null;
                } else if (KEY_ORB_TAP_ACTION.equals(k)) {
                    orbTapOverride = null;
                } else if (KEY_ORB_LONG_PRESS_ACTION.equals(k)) {
                    orbLongPressOverride = null;
                } else if (KEY_IME_DPI_SCALE_PERCENT.equals(k)) {
                    imeDpiScaleOverride = null;
                } else if (KEY_IME_MAX_HEIGHT_PERCENT.equals(k)) {
                    imeHeightOverride = null;
                } else if (KEY_IME_TOP_INSET_PX.equals(k)) {
                    imeTopInsetOverride = null;
                } else if (KEY_PER_APP_RULES.equals(k)) {
                    perAppRulesOverride = null;
                }
                invalidateCaches(k);
                refreshSnapshot();
                Looper loop = Looper.getMainLooper();
                if (loop == null) return;
                new Handler(loop).post(() -> {
                    ModLog.i(TAG, "preference changed key=" + k);
                    for (Runnable r : listeners) {
                        try {
                            r.run();
                        } catch (RuntimeException e) {
                            Log.w(TAG, "listener failed", e);
                        }
                    }
                });
            };
        }
        return changeListener;
    }

    static synchronized void init(XposedModule module) {
        if (prefs != null) return;
        localDirectRead = false;
        SharedPreferences p = module.getRemotePreferences(PREFS_NAME);
        prefs = p;
        invalidateCaches(null);
        refreshSnapshot();
        p.registerOnSharedPreferenceChangeListener(ensureChangeListener());
    }

    static synchronized void initLocal(SharedPreferences sp) {
        if (sp == null) return;
        localDirectRead = true;
        SharedPreferences prev = prefs;
        SharedPreferences.OnSharedPreferenceChangeListener l = ensureChangeListener();
        if (prev == sp) {
            refreshSnapshot();
            return;
        }
        if (prev != null) {
            try {
                prev.unregisterOnSharedPreferenceChangeListener(l);
            } catch (RuntimeException ignored) {
            }
        }
        prefs = sp;
        invalidateCaches(null);
        refreshSnapshot();
        sp.registerOnSharedPreferenceChangeListener(l);
    }

    static void overridePinnedWidget(boolean enabled, String rawApps) {
        pinnedEnabledOverride = Boolean.valueOf(enabled);
        pinnedAppsOverride = rawApps != null ? rawApps : "";
        refreshSnapshot();
    }

    static void overrideQuickWidget(boolean enabled, String rawSlots) {
        quickEnabledOverride = Boolean.valueOf(enabled);
        quickSlotsOverride = rawSlots != null ? rawSlots : DEFAULT_QUICK_WIDGET_SLOTS;
        refreshSnapshot();
    }

    static void clearQuickWidgetOverrides() {
        quickEnabledOverride = null;
        quickSlotsOverride = null;
        refreshSnapshot();
    }

    static void overrideFoldContinuity(int mode, String rawApps, boolean pocketGuard) {
        foldModeOverride = clampFoldContinuityMode(mode);
        foldAppsOverride = rawApps != null ? rawApps : "";
        foldPocketOverride = Boolean.valueOf(pocketGuard);
        cachedFoldAppsRaw = null;
        refreshSnapshot();
    }

    static void clearFoldContinuityOverrides() {
        foldModeOverride = null;
        foldAppsOverride = null;
        foldPocketOverride = null;
        cachedFoldAppsRaw = null;
        refreshSnapshot();
    }

    static void overrideUnlockLauncherApps(boolean unlock) {
        unlockLauncherAppsOverride = Boolean.valueOf(unlock);
        refreshSnapshot();
    }

    static void clearUnlockLauncherAppsOverride() {
        unlockLauncherAppsOverride = null;
        refreshSnapshot();
    }

    static void overrideOrbActions(String tapAction, String longPressAction) {
        orbTapOverride = normalizeOrbActionToken(tapAction);
        orbLongPressOverride = normalizeOrbActionToken(longPressAction);
        refreshSnapshot();
    }

    static void clearOrbOverrides() {
        orbTapOverride = null;
        orbLongPressOverride = null;
        refreshSnapshot();
    }

    static void overrideImeTuning(int dpiScalePercent, int maxHeightPercent, int topInsetPx) {
        imeDpiScaleOverride = clampImeDpiScale(dpiScalePercent);
        imeHeightOverride = clampImeHeightPercent(maxHeightPercent);
        imeTopInsetOverride = clampImeTopInsetPx(topInsetPx);
        refreshSnapshot();
    }

    static void clearImeTuningOverrides() {
        imeDpiScaleOverride = null;
        imeHeightOverride = null;
        imeTopInsetOverride = null;
        refreshSnapshot();
    }

    static void overridePerAppRules(String json) {
        perAppRulesOverride = json != null ? json : "";
        cachedRulesRaw = null;
        refreshSnapshot();
    }

    static void clearPerAppRuleOverrides() {
        perAppRulesOverride = null;
        cachedRulesRaw = null;
        refreshSnapshot();
    }

    // ---- Dual-App (u999 / multi-user) app-target encoding helpers ----

    /** Formats a package + userId pair: primary user (<= 0) stays plain {@code pkg}; dual/secondary user becomes {@code pkg#<userId>}. */
    static String formatAppTarget(String pkg, int userId) {
        String clean = extractAppTargetPackage(pkg);
        if (clean.isEmpty()) return "";
        return userId > 0 ? (clean + "#" + userId) : clean;
    }

    /** Extracts the package name from {@code pkg}, {@code pkg#<userId>}, or {@code app:pkg#<userId>}. */
    static String extractAppTargetPackage(String target) {
        if (target == null) return "";
        String s = target.trim();
        if (s.startsWith(QA_APP_PREFIX)) {
            s = s.substring(QA_APP_PREFIX.length()).trim();
        }
        int hash = s.indexOf('#');
        return hash >= 0 ? s.substring(0, hash).trim() : s;
    }

    /** Extracts the positive userId from {@code pkg#<userId>} / {@code app:pkg#<userId>}, or {@code 0} for primary user. */
    static int extractAppTargetUserId(String target) {
        if (target == null) return 0;
        String s = target.trim();
        if (s.startsWith(QA_APP_PREFIX)) {
            s = s.substring(QA_APP_PREFIX.length()).trim();
        }
        int hash = s.indexOf('#');
        if (hash < 0 || hash + 1 >= s.length()) return 0;
        String suffix = s.substring(hash + 1).trim();
        if (suffix.isEmpty()) return 0;
        for (int i = 0; i < suffix.length(); i++) {
            char c = suffix.charAt(i);
            if (c < '0' || c > '9') return 0;
        }
        try {
            int uid = Integer.parseInt(suffix);
            return uid > 0 ? uid : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean isValidRawAppTarget(String raw) {
        if (raw == null) return false;
        String s = raw.trim();
        if (s.isEmpty()) return false;
        int hash = s.indexOf('#');
        String pkg;
        if (hash >= 0) {
            if (s.indexOf('#', hash + 1) >= 0) return false;
            pkg = s.substring(0, hash).trim();
            String userPart = s.substring(hash + 1).trim();
            if (userPart.isEmpty()) return false;
            for (int i = 0; i < userPart.length(); i++) {
                char c = userPart.charAt(i);
                if (c < '0' || c > '9') return false;
            }
            try {
                if (Integer.parseInt(userPart) <= 0) return false;
            } catch (NumberFormatException e) {
                return false;
            }
        } else {
            pkg = s;
        }
        if (pkg.isEmpty() || "com.miui.fliphome".equals(pkg) || !pkg.contains(".")) return false;
        for (int i = 0; i < pkg.length(); i++) {
            char c = pkg.charAt(i);
            if (c == ',' || c == '#' || Character.isWhitespace(c)) return false;
        }
        return true;
    }

    private static String normalizeRawAppTarget(String raw) {
        if (!isValidRawAppTarget(raw)) return "";
        String pkg = extractAppTargetPackage(raw);
        int userId = extractAppTargetUserId(raw);
        return formatAppTarget(pkg, userId);
    }

    static void addListener(Runnable r) {
        if (r != null) listeners.addIfAbsent(r);
    }

    static void removeListener(Runnable r) {
        if (r != null) listeners.remove(r);
    }

    static boolean panelEnabled() {
        return localDirectRead ? getBoolean(KEY_PANEL, true) : currentSnapshot().panelEnabled;
    }

    static boolean immersiveEnabled() {
        return localDirectRead ? getBoolean(KEY_IMMERSIVE, true) : currentSnapshot().immersiveEnabled;
    }

    static boolean frameworkFullscreen() {
        return FrameworkRuntime.active() && immersiveEnabled();
    }

    static boolean hideAppNames() {
        return localDirectRead ? getBoolean(KEY_HIDE_APP_NAMES, false) : currentSnapshot().hideAppNames;
    }

    static boolean pinnedWidgetEnabled() {
        if (!localDirectRead) return currentSnapshot().pinnedWidgetEnabled;
        Boolean ov = pinnedEnabledOverride;
        return ov != null ? ov : getBoolean(KEY_PINNED_WIDGET_ENABLED, false);
    }

    static String pinnedWidgetAppsRaw() {
        if (!localDirectRead) return currentSnapshot().pinnedWidgetAppsRaw;
        String ov = pinnedAppsOverride;
        return ov != null ? ov : getString(KEY_PINNED_WIDGET_APPS, "");
    }

    /**
     * Ordered list of up to {@link #MAX_PINNED_WIDGET_APPS} unique app targets ({@code pkg} or
     * {@code pkg#<userId>} for dual apps) pinned to the cover recent-apps widget ({@code RecentTaskView}).
     */
    static List<String> pinnedWidgetApps() {
        if (!localDirectRead) return currentSnapshot().pinnedWidgetApps;
        return parsePinnedApps(pinnedWidgetAppsRaw());
    }

    static boolean hasPinnedWidgetApps() {
        return pinnedWidgetEnabled() && !pinnedWidgetApps().isEmpty();
    }

    static List<String> parsePinnedApps(String raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String target = normalizeRawAppTarget(part);
            if (!target.isEmpty()) {
                unique.add(target);
                if (unique.size() >= MAX_PINNED_WIDGET_APPS) break;
            }
        }
        return unique.isEmpty() ? Collections.emptyList() : new ArrayList<>(unique);
    }

    static String serializePinnedApps(List<String> pkgs) {
        if (pkgs == null || pkgs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int count = 0;
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String raw : pkgs) {
            String target = normalizeRawAppTarget(raw);
            if (target.isEmpty() || !seen.add(target)) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(target);
            if (++count >= MAX_PINNED_WIDGET_APPS) break;
        }
        return sb.toString();
    }

    static String normalizePinnedWidgetApps(String raw) {
        return serializePinnedApps(parsePinnedApps(raw));
    }

    static boolean quickWidgetEnabled() {
        if (!localDirectRead) return currentSnapshot().quickWidgetEnabled;
        Boolean ov = quickEnabledOverride;
        return ov != null ? ov : getBoolean(KEY_QUICK_WIDGET_ENABLED, false);
    }

    static String quickWidgetSlotsRaw() {
        if (!localDirectRead) return currentSnapshot().quickWidgetSlotsRaw;
        String ov = quickSlotsOverride;
        return ov != null ? ov : getString(KEY_QUICK_WIDGET_SLOTS, DEFAULT_QUICK_WIDGET_SLOTS);
    }

    static List<String> quickWidgetSlots() {
        if (!localDirectRead) return currentSnapshot().quickWidgetSlots;
        return parseQuickActionSlots(quickWidgetSlotsRaw());
    }

    static boolean isValidQuickActionToken(String token) {
        if (token == null) return false;
        String t = token.trim();
        return switch (t) {
            case QA_HOME, QA_RECENTS, QA_LOCK, QA_BACK,
                    QA_NOTIFICATIONS, QA_CONTROL_CENTER, QA_TORCH,
                    QA_SCREENSHOT, QA_RINGER,
                    QA_ROTATION_LOCK, QA_MEDIA_PLAY_PAUSE, QA_BRIGHTNESS, QA_SCAN -> true;
            default -> t.startsWith(QA_APP_PREFIX) && isValidRawAppTarget(t.substring(QA_APP_PREFIX.length()));
        };
    }

    static String normalizeQuickActionToken(String token, String fallback) {
        if (token != null) {
            String t = token.trim();
            if (t.startsWith(QA_APP_PREFIX)) {
                String normalizedTarget = normalizeRawAppTarget(t.substring(QA_APP_PREFIX.length()));
                if (!normalizedTarget.isEmpty()) {
                    return QA_APP_PREFIX + normalizedTarget;
                }
                return fallback;
            }
            if (isValidQuickActionToken(t)) return t;
        }
        return fallback;
    }

    static List<String> parseQuickActionSlots(String raw) {
        ArrayList<String> out = new ArrayList<>(QUICK_WIDGET_SLOT_COUNT);
        String[] parts = raw != null ? raw.split(",", -1) : new String[0];
        for (int i = 0; i < QUICK_WIDGET_SLOT_COUNT; i++) {
            String fallback = DEFAULT_QUICK_WIDGET_SLOT_LIST.get(i);
            String candidate = i < parts.length ? parts[i] : null;
            out.add(normalizeQuickActionToken(candidate, fallback));
        }
        return out;
    }

    static String serializeQuickActionSlots(List<String> slots) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < QUICK_WIDGET_SLOT_COUNT; i++) {
            if (i > 0) sb.append(',');
            String fallback = DEFAULT_QUICK_WIDGET_SLOT_LIST.get(i);
            String candidate = (slots != null && i < slots.size()) ? slots.get(i) : null;
            sb.append(normalizeQuickActionToken(candidate, fallback));
        }
        return sb.toString();
    }

    // ---- Feature 1: Fold-close seamless continuity ----

    static int clampFoldContinuityMode(int mode) {
        return (mode >= FOLD_CONT_SYSTEM && mode <= FOLD_CONT_ALL) ? mode : FOLD_CONT_SYSTEM;
    }

    static int foldContinuityMode() {
        if (!localDirectRead) return currentSnapshot().foldContinuityMode;
        Integer ov = foldModeOverride;
        return ov != null ? ov : clampFoldContinuityMode(getInt(KEY_FOLD_CONTINUITY_MODE, FOLD_CONT_SYSTEM));
    }

    static boolean foldPocketGuardEnabled() {
        if (!localDirectRead) return currentSnapshot().foldPocketGuard;
        Boolean ov = foldPocketOverride;
        return ov != null ? ov : getBoolean(KEY_FOLD_POCKET_GUARD, true);
    }

    static String foldContinuityAppsRaw() {
        if (!localDirectRead) return currentSnapshot().foldContinuityAppsRaw;
        String ov = foldAppsOverride;
        return ov != null ? ov : getString(KEY_FOLD_CONTINUITY_APPS, "");
    }

    static Set<String> parsePackageSet(String raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptySet();
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String pkg = part == null ? "" : part.trim();
            if (!pkg.isEmpty() && !"com.miui.fliphome".equals(pkg) && !"com.miui.home".equals(pkg)) {
                set.add(pkg);
            }
        }
        return set.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(set);
    }

    static String serializePackageList(Collection<String> pkgs) {
        if (pkgs == null || pkgs.isEmpty()) return "";
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        StringBuilder sb = new StringBuilder();
        for (String raw : pkgs) {
            String pkg = raw == null ? "" : raw.trim();
            if (pkg.isEmpty() || "com.miui.fliphome".equals(pkg) || "com.miui.home".equals(pkg) || !seen.add(pkg)) {
                continue;
            }
            if (sb.length() > 0) sb.append(',');
            sb.append(pkg);
        }
        return sb.toString();
    }

    static Set<String> foldContinuityApps() {
        if (!localDirectRead) return currentSnapshot().foldContinuityApps;
        String raw = foldContinuityAppsRaw();
        if (raw.equals(cachedFoldAppsRaw)) {
            return cachedFoldAppsSet;
        }
        Set<String> parsed = parsePackageSet(raw);
        cachedFoldAppsSet = parsed;
        cachedFoldAppsRaw = raw;
        return parsed;
    }

    static boolean isFoldContinuityApp(String pkg) {
        return pkg != null && !pkg.isEmpty() && foldContinuityApps().contains(pkg);
    }

    // ---- Feature 2: Launcher all-apps unlock, double-tap lock, StatusOrb actions ----

    static boolean unlockLauncherApps() {
        if (!localDirectRead) return currentSnapshot().unlockLauncherApps;
        Boolean ov = unlockLauncherAppsOverride;
        return ov != null ? ov : getBoolean(KEY_UNLOCK_LAUNCHER_APPS, true);
    }

    static boolean doubleTapLockEnabled() {
        return localDirectRead ? getBoolean(KEY_DOUBLE_TAP_LOCK, true) : currentSnapshot().doubleTapLock;
    }

    static boolean isValidOrbActionToken(String token) {
        if (token == null) return false;
        String t = token.trim();
        return QA_NONE.equals(t) || isValidQuickActionToken(t);
    }

    static String normalizeOrbActionToken(String token) {
        if (token == null) return QA_NONE;
        String t = token.trim();
        if (QA_NONE.equals(t) || t.isEmpty()) return QA_NONE;
        return normalizeQuickActionToken(t, QA_NONE);
    }

    static String orbTapAction() {
        if (!localDirectRead) return currentSnapshot().orbTapAction;
        String ov = orbTapOverride;
        return ov != null ? ov : normalizeOrbActionToken(getString(KEY_ORB_TAP_ACTION, QA_NONE));
    }

    static String orbLongPressAction() {
        if (!localDirectRead) return currentSnapshot().orbLongPressAction;
        String ov = orbLongPressOverride;
        return ov != null ? ov : normalizeOrbActionToken(getString(KEY_ORB_LONG_PRESS_ACTION, QA_NONE));
    }

    static boolean hasOrbInteraction() {
        return !QA_NONE.equals(orbTapAction()) || !QA_NONE.equals(orbLongPressAction());
    }

    static boolean desktopEnabled() {
        return panelEnabled() || liveIslandEnabled() || immersiveEnabled()
                || hideAppNames() || hasPinnedWidgetApps() || quickWidgetEnabled()
                || unlockLauncherApps() || doubleTapLockEnabled();
    }

    static boolean batteryNumber() {
        if (!localDirectRead) {
            ConfigSnapshot s = currentSnapshot();
            return s.panelEnabled && s.batteryNumber;
        }
        return panelEnabled() && getBoolean(KEY_BATTERY_NUMBER, false);
    }

    static boolean dualSim() {
        if (!localDirectRead) {
            ConfigSnapshot s = currentSnapshot();
            return s.panelEnabled && s.dualSim;
        }
        return panelEnabled() && getBoolean(KEY_DUAL_SIM, false);
    }

    static boolean liveIslandEnabled() {
        if (!localDirectRead) {
            ConfigSnapshot s = currentSnapshot();
            return s.panelEnabled || s.liveIslandRaw;
        }
        return panelEnabled() || getBoolean(KEY_LIVE_ISLAND, true);
    }

    static boolean universalCompatEnabled() {
        if (!localDirectRead) {
            ConfigSnapshot s = currentSnapshot();
            return s.immersiveEnabled && s.universalCompatRaw;
        }
        return immersiveEnabled() && getBoolean(KEY_UNIVERSAL_COMPAT, true);
    }

    static boolean coverThirdPartyImeEnabled() {
        return localDirectRead ? getBoolean(KEY_THIRD_PARTY_IME, DEFAULT_THIRD_PARTY_IME) : currentSnapshot().thirdPartyIme;
    }

    // ---- Feature 4: Universal cover IME tuning ----

    static int clampImeDpiScale(int pct) {
        return Math.max(MIN_IME_DPI_SCALE_PERCENT, Math.min(MAX_IME_DPI_SCALE_PERCENT, pct));
    }

    static int clampImeHeightPercent(int pct) {
        return Math.max(MIN_IME_MAX_HEIGHT_PERCENT, Math.min(MAX_IME_MAX_HEIGHT_PERCENT, pct));
    }

    static int clampImeTopInsetPx(int px) {
        if (px < 0) return IME_TOP_INSET_AUTO;
        return Math.max(MIN_IME_TOP_INSET_PX, Math.min(MAX_IME_TOP_INSET_PX, px));
    }

    static int imeDpiScalePercent() {
        if (!localDirectRead) return currentSnapshot().imeDpiScalePercent;
        Integer ov = imeDpiScaleOverride;
        return ov != null ? ov : clampImeDpiScale(getInt(KEY_IME_DPI_SCALE_PERCENT, DEFAULT_IME_DPI_SCALE_PERCENT));
    }

    static int imeMaxHeightPercent() {
        if (!localDirectRead) return currentSnapshot().imeMaxHeightPercent;
        Integer ov = imeHeightOverride;
        return ov != null ? ov : clampImeHeightPercent(getInt(KEY_IME_MAX_HEIGHT_PERCENT, DEFAULT_IME_MAX_HEIGHT_PERCENT));
    }

    static int imeTopInsetPx() {
        if (!localDirectRead) return currentSnapshot().imeTopInsetPx;
        Integer ov = imeTopInsetOverride;
        return ov != null ? ov : clampImeTopInsetPx(getInt(KEY_IME_TOP_INSET_PX, IME_TOP_INSET_AUTO));
    }

    // ---- Feature 6: Per-app cover rules & pure-Java JSON serialization ----

    static boolean isValidRuleTargetPackage(String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.length() > 192) return false;
        if ("android".equals(pkg)
                || "system".equals(pkg)
                || "com.android.systemui".equals(pkg)
                || "com.miui.fliphome".equals(pkg)
                || "com.miui.home".equals(pkg)
                || BuildConfig.APPLICATION_ID.equals(pkg)) {
            return false;
        }
        if (!pkg.contains(".") || pkg.startsWith(".") || pkg.endsWith(".") || pkg.contains("..")) {
            return false;
        }
        for (int i = 0; i < pkg.length(); i++) {
            char c = pkg.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '.'
                    || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    static String perAppRulesRaw() {
        if (!localDirectRead) return currentSnapshot().perAppRulesRaw;
        String ov = perAppRulesOverride;
        return ov != null ? ov : getString(KEY_PER_APP_RULES, "");
    }

    static Map<String, PerAppRule> perAppRules() {
        if (!localDirectRead) return currentSnapshot().perAppRules;
        String raw = perAppRulesRaw();
        if (raw.equals(cachedRulesRaw)) {
            return cachedRulesMap;
        }
        Map<String, PerAppRule> parsed = parsePerAppRulesJson(raw);
        cachedRulesMap = parsed;
        cachedRulesRaw = raw;
        return parsed;
    }

    static PerAppRule perAppRule(String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        return perAppRules().get(pkg);
    }

    static int effectiveAppTopGapPx(String pkg, int defaultPx) {
        PerAppRule r = perAppRule(pkg);
        if (r != null && r.topGapPx() >= 0) {
            return r.topGapPx();
        }
        return defaultPx;
    }

    static Map<String, PerAppRule> mergePerAppRules(
            Map<String, PerAppRule> base, Map<String, PerAppRule> incoming, boolean replaceAll) {
        TreeMap<String, PerAppRule> out = new TreeMap<>();
        if (!replaceAll && base != null) {
            for (Map.Entry<String, PerAppRule> e : base.entrySet()) {
                if (isValidRuleTargetPackage(e.getKey()) && e.getValue() != null) {
                    PerAppRule norm = PerAppRule.of(
                            e.getValue().topGapPx(), e.getValue().widthMode(), e.getValue().scalePercent());
                    if (!norm.isDefault()) out.put(e.getKey(), norm);
                }
            }
        }
        if (incoming != null) {
            for (Map.Entry<String, PerAppRule> e : incoming.entrySet()) {
                if (isValidRuleTargetPackage(e.getKey()) && e.getValue() != null) {
                    PerAppRule norm = PerAppRule.of(
                            e.getValue().topGapPx(), e.getValue().widthMode(), e.getValue().scalePercent());
                    if (norm.isDefault()) {
                        out.remove(e.getKey());
                    } else {
                        out.put(e.getKey(), norm);
                    }
                }
            }
        }
        return out.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(out));
    }

    static String serializePerAppRulesJson(Map<String, PerAppRule> rules, boolean pretty) {
        TreeMap<String, PerAppRule> sorted = new TreeMap<>();
        if (rules != null) {
            for (Map.Entry<String, PerAppRule> e : rules.entrySet()) {
                if (isValidRuleTargetPackage(e.getKey()) && e.getValue() != null) {
                    PerAppRule norm = PerAppRule.of(
                            e.getValue().topGapPx(), e.getValue().widthMode(), e.getValue().scalePercent());
                    if (!norm.isDefault()) sorted.put(e.getKey(), norm);
                }
            }
        }
        if (sorted.isEmpty() && !pretty) return "";
        String nl = pretty ? "\n" : "";
        String i1 = pretty ? "  " : "";
        String i2 = pretty ? "    " : "";
        String i3 = pretty ? "      " : "";
        String sp = pretty ? " " : "";
        StringBuilder sb = new StringBuilder();
        sb.append('{').append(nl);
        sb.append(i1).append("\"schema\":").append(sp).append('"').append(RULES_SCHEMA).append('"').append(',').append(nl);
        sb.append(i1).append("\"rules\":").append(sp).append('{');
        if (!sorted.isEmpty()) {
            sb.append(nl);
            int idx = 0;
            int total = sorted.size();
            for (Map.Entry<String, PerAppRule> e : sorted.entrySet()) {
                PerAppRule r = e.getValue();
                sb.append(i2).append('"').append(escapeJsonString(e.getKey())).append("\":").append(sp).append('{').append(nl);
                sb.append(i3).append("\"topGapPx\":").append(sp).append(r.topGapPx()).append(',').append(nl);
                sb.append(i3).append("\"widthMode\":").append(sp).append(r.widthMode()).append(',').append(nl);
                sb.append(i3).append("\"scalePercent\":").append(sp).append(r.scalePercent()).append(nl);
                sb.append(i2).append('}');
                if (++idx < total) sb.append(',');
                sb.append(nl);
            }
            sb.append(i1);
        }
        sb.append('}').append(nl);
        sb.append('}');
        return sb.toString();
    }

    private static String escapeJsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    static Map<String, PerAppRule> parsePerAppRulesJson(String json) {
        if (json == null || json.trim().isEmpty()) return Collections.emptyMap();
        try {
            return parsePerAppRulesJsonStrict(json);
        } catch (IllegalArgumentException e) {
            return Collections.emptyMap();
        }
    }

    static Map<String, PerAppRule> parsePerAppRulesJsonStrict(String json) {
        if (json == null || json.trim().isEmpty()) {
            throw new IllegalArgumentException("Empty JSON");
        }
        Object parsed = new SimpleJsonParser(json).parseRoot();
        if (!(parsed instanceof Map<?, ?> root)) {
            throw new IllegalArgumentException("Root must be a JSON object");
        }
        Object rulesCandidate = root.containsKey("rules") ? root.get("rules") : root;
        if (!(rulesCandidate instanceof Map<?, ?> rulesMap)) {
            throw new IllegalArgumentException("Missing rules object");
        }
        TreeMap<String, PerAppRule> out = new TreeMap<>();
        for (Map.Entry<?, ?> entry : rulesMap.entrySet()) {
            if (!(entry.getKey() instanceof String pkg) || !isValidRuleTargetPackage(pkg)) {
                continue;
            }
            if (!(entry.getValue() instanceof Map<?, ?> ruleObj)) {
                throw new IllegalArgumentException("Rule for " + pkg + " must be an object");
            }
            int gap = readJsonInt(ruleObj, "topGapPx", TOP_GAP_DEFAULT);
            int widthMode = readJsonInt(ruleObj, "widthMode", WIDTH_MODE_DEFAULT);
            int scale = readJsonInt(ruleObj, "scalePercent", SCALE_DEFAULT);
            PerAppRule normalized = PerAppRule.of(gap, widthMode, scale);
            if (!normalized.isDefault()) {
                out.put(pkg, normalized);
            }
        }
        return out.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(out));
    }

    private static int readJsonInt(Map<?, ?> map, String key, int fallback) {
        Object val = map.get(key);
        if (val == null) return fallback;
        if (val instanceof Number n) return n.intValue();
        throw new IllegalArgumentException("Expected integer for " + key);
    }

    private static final class SimpleJsonParser {
        private final String src;
        private int pos;

        SimpleJsonParser(String src) {
            this.src = src;
        }

        Object parseRoot() {
            skipWhitespace();
            Object v = parseValue();
            skipWhitespace();
            if (pos != src.length()) {
                throw new IllegalArgumentException("Unexpected trailing characters at " + pos);
            }
            return v;
        }

        private Object parseValue() {
            skipWhitespace();
            if (pos >= src.length()) throw new IllegalArgumentException("Unexpected end of JSON");
            char c = src.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't', 'f' -> parseBoolean();
                case 'n' -> parseNull();
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) yield parseNumber();
                    throw new IllegalArgumentException("Unexpected char '" + c + "' at " + pos);
                }
            };
        }

        private Map<String, Object> parseObject() {
            expect('{');
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object val = parseValue();
                map.put(key, val);
                skipWhitespace();
                if (peek(',')) {
                    pos++;
                } else if (peek('}')) {
                    pos++;
                    return map;
                } else {
                    throw new IllegalArgumentException("Expected ',' or '}' at " + pos);
                }
            }
        }

        private List<Object> parseArray() {
            expect('[');
            ArrayList<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek(']')) {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (peek(',')) {
                    pos++;
                } else if (peek(']')) {
                    pos++;
                    return list;
                } else {
                    throw new IllegalArgumentException("Expected ',' or ']' at " + pos);
                }
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= src.length()) throw new IllegalArgumentException("Unterminated escape");
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case '"', '\\', '/' -> sb.append(esc);
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > src.length()) throw new IllegalArgumentException("Invalid unicode escape");
                            String hex = src.substring(pos, pos + 4);
                            pos += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                        }
                        default -> throw new IllegalArgumentException("Invalid escape \\" + esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated string");
        }

        private Number parseNumber() {
            int start = pos;
            if (peek('-')) pos++;
            boolean hasDigits = false;
            while (pos < src.length() && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') {
                hasDigits = true;
                pos++;
            }
            if (!hasDigits) throw new IllegalArgumentException("Invalid number at " + start);
            boolean isFloat = false;
            if (peek('.')) {
                isFloat = true;
                pos++;
                while (pos < src.length() && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') pos++;
            }
            if (peek('e') || peek('E')) {
                isFloat = true;
                pos++;
                if (peek('+') || peek('-')) pos++;
                while (pos < src.length() && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') pos++;
            }
            String numStr = src.substring(start, pos);
            try {
                if (!isFloat) {
                    long lv = Long.parseLong(numStr);
                    if (lv >= Integer.MIN_VALUE && lv <= Integer.MAX_VALUE) return (int) lv;
                    return lv;
                }
                return Double.parseDouble(numStr);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid number: " + numStr);
            }
        }

        private Boolean parseBoolean() {
            if (src.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (src.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("Invalid boolean at " + pos);
        }

        private Object parseNull() {
            if (src.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new IllegalArgumentException("Invalid null at " + pos);
        }

        private void skipWhitespace() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        private boolean peek(char ch) {
            return pos < src.length() && src.charAt(pos) == ch;
        }

        private void expect(char ch) {
            if (pos >= src.length() || src.charAt(pos) != ch) {
                throw new IllegalArgumentException("Expected '" + ch + "' at " + pos);
            }
            pos++;
        }
    }

    /**
     * Lets apps on HyperOS' cover intercept list (e.g. Settings) open on the cover screen instead of showing the
     * "unfold to continue" prompt. Enabled by default.
     */
    static boolean allowAllCoverApps() {
        return localDirectRead ? getBoolean(KEY_ALLOW_ALL_COVER_APPS, true) : currentSnapshot().allowAllCoverApps;
    }

    /**
     * Bypasses MiuiSystemUI's cover-screen notification continuity whitelist so tapping any notification opens it
     * directly on the cover screen instead of showing the "view on inner screen" modal prompt. Enabled by default.
     */
    static boolean directOpenCoverNotifications() {
        return localDirectRead ? getBoolean(KEY_DIRECT_OPEN_COVER_NOTIF, true) : currentSnapshot().directOpenCoverNotif;
    }

    static boolean verboseLog() {
        return localDirectRead ? getBoolean(KEY_VERBOSE_LOG, false) : currentSnapshot().verboseLog;
    }

    static boolean compatEnabled(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        if (!localDirectRead) {
            ConfigSnapshot s = currentSnapshot();
            if (!s.immersiveEnabled || !s.universalCompatRaw) return false;
            if (s.compatAllLoaded) {
                return !s.disabledCompatPackages.contains(pkg);
            }
        }
        return universalCompatEnabled() && getBoolean("compat_" + pkg, true);
    }

    private static boolean readBoolean(SharedPreferences p, String key, boolean fallback) {
        try {
            return p != null ? p.getBoolean(key, fallback) : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static int readInt(SharedPreferences p, String key, int fallback) {
        try {
            if (p == null) return fallback;
            try {
                return p.getInt(key, fallback);
            } catch (ClassCastException cce) {
                String raw = p.getString(key, null);
                if (raw == null || raw.isEmpty()) return fallback;
                return Integer.parseInt(raw.trim());
            }
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String readString(SharedPreferences p, String key, String fallback) {
        try {
            if (p == null) return fallback;
            String v = p.getString(key, fallback);
            return v != null ? v : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static boolean getBoolean(String key, boolean fallback) {
        return readBoolean(prefs, key, fallback);
    }

    private static int getInt(String key, int fallback) {
        return readInt(prefs, key, fallback);
    }

    private static String getString(String key, String fallback) {
        return readString(prefs, key, fallback);
    }
}
