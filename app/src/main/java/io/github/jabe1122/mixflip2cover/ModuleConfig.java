package io.github.jabe1122.mixflip2cover;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import io.github.libxposed.api.XposedModule;

/**
 * Centralized remote preferences access and live change dispatching.
 *
 * Listeners are stored in a {@link CopyOnWriteArrayList} because they are added from hook callbacks on
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

    /** Third-party IME on the cover screen modifies system IME settings, so it is opt-in. */
    static final boolean DEFAULT_THIRD_PARTY_IME = false;

    static volatile SharedPreferences prefs;
    static SharedPreferences.OnSharedPreferenceChangeListener changeListener;
    private static volatile Boolean pinnedEnabledOverride;
    private static volatile String pinnedAppsOverride;
    private static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    private ModuleConfig() {}

    private static SharedPreferences.OnSharedPreferenceChangeListener ensureChangeListener() {
        if (changeListener == null) {
            changeListener = (sp, k) -> {
                if (KEY_PINNED_WIDGET_ENABLED.equals(k)) {
                    pinnedEnabledOverride = null;
                } else if (KEY_PINNED_WIDGET_APPS.equals(k)) {
                    pinnedAppsOverride = null;
                }
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
        SharedPreferences p = module.getRemotePreferences(PREFS_NAME);
        prefs = p;
        p.registerOnSharedPreferenceChangeListener(ensureChangeListener());
    }

    static synchronized void initLocal(SharedPreferences sp) {
        if (sp == null) return;
        SharedPreferences prev = prefs;
        SharedPreferences.OnSharedPreferenceChangeListener l = ensureChangeListener();
        if (prev == sp) return;
        if (prev != null) {
            try {
                prev.unregisterOnSharedPreferenceChangeListener(l);
            } catch (RuntimeException ignored) {
            }
        }
        prefs = sp;
        sp.registerOnSharedPreferenceChangeListener(l);
    }

    static void overridePinnedWidget(boolean enabled, String rawApps) {
        pinnedEnabledOverride = Boolean.valueOf(enabled);
        pinnedAppsOverride = rawApps != null ? rawApps : "";
    }

    static void addListener(Runnable r) {
        if (r != null) listeners.addIfAbsent(r);
    }

    static void removeListener(Runnable r) {
        if (r != null) listeners.remove(r);
    }

    static boolean panelEnabled() {
        return getBoolean(KEY_PANEL, true);
    }

    static boolean immersiveEnabled() {
        return getBoolean(KEY_IMMERSIVE, true);
    }

    static boolean frameworkFullscreen() {
        return FrameworkRuntime.active() && immersiveEnabled();
    }

    static boolean hideAppNames() {
        return getBoolean(KEY_HIDE_APP_NAMES, false);
    }

    static boolean pinnedWidgetEnabled() {
        Boolean ov = pinnedEnabledOverride;
        return ov != null ? ov : getBoolean(KEY_PINNED_WIDGET_ENABLED, false);
    }

    static String pinnedWidgetAppsRaw() {
        String ov = pinnedAppsOverride;
        return ov != null ? ov : getString(KEY_PINNED_WIDGET_APPS, "");
    }

    /**
     * Ordered list of up to {@link #MAX_PINNED_WIDGET_APPS} unique package names pinned to the cover
     * recent-apps widget ({@code RecentTaskView}).
     */
    static List<String> pinnedWidgetApps() {
        return parsePinnedApps(pinnedWidgetAppsRaw());
    }

    static boolean hasPinnedWidgetApps() {
        return pinnedWidgetEnabled() && !pinnedWidgetApps().isEmpty();
    }

    static List<String> parsePinnedApps(String raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String pkg = part == null ? "" : part.trim();
            if (!pkg.isEmpty() && !"com.miui.fliphome".equals(pkg)) {
                unique.add(pkg);
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
            String pkg = raw == null ? "" : raw.trim();
            if (pkg.isEmpty() || "com.miui.fliphome".equals(pkg) || !seen.add(pkg)) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(pkg);
            if (++count >= MAX_PINNED_WIDGET_APPS) break;
        }
        return sb.toString();
    }

    static boolean desktopEnabled() {
        return panelEnabled() || liveIslandEnabled() || immersiveEnabled() || hideAppNames() || hasPinnedWidgetApps();
    }

    static boolean batteryNumber() {
        return panelEnabled() && getBoolean(KEY_BATTERY_NUMBER, false);
    }

    static boolean dualSim() {
        return panelEnabled() && getBoolean(KEY_DUAL_SIM, false);
    }

    static boolean liveIslandEnabled() {
        return panelEnabled() || getBoolean(KEY_LIVE_ISLAND, true);
    }

    static boolean universalCompatEnabled() {
        return immersiveEnabled() && getBoolean(KEY_UNIVERSAL_COMPAT, true);
    }

    static boolean coverThirdPartyImeEnabled() {
        return getBoolean(KEY_THIRD_PARTY_IME, DEFAULT_THIRD_PARTY_IME);
    }

    /**
     * Lets apps on HyperOS' cover intercept list (e.g. Settings) open on the cover screen instead of showing the
     * "unfold to continue" prompt. Enabled by default.
     */
    static boolean allowAllCoverApps() {
        return getBoolean(KEY_ALLOW_ALL_COVER_APPS, true);
    }

    /**
     * Bypasses MiuiSystemUI's cover-screen notification continuity whitelist so tapping any notification opens it
     * directly on the cover screen instead of showing the "view on inner screen" modal prompt. Enabled by default.
     */
    static boolean directOpenCoverNotifications() {
        return getBoolean(KEY_DIRECT_OPEN_COVER_NOTIF, true);
    }

    static boolean verboseLog() {
        return getBoolean(KEY_VERBOSE_LOG, false);
    }

    static boolean compatEnabled(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        return universalCompatEnabled() && getBoolean("compat_" + pkg, true);
    }

    private static boolean getBoolean(String key, boolean fallback) {
        try {
            SharedPreferences p = prefs;
            return p != null ? p.getBoolean(key, fallback) : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String getString(String key, String fallback) {
        try {
            SharedPreferences p = prefs;
            if (p == null) return fallback;
            String v = p.getString(key, fallback);
            return v != null ? v : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
