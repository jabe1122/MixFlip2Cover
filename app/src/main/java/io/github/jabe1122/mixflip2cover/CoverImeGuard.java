package io.github.jabe1122.mixflip2cover;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Optional (off by default): lets third-party input methods (such as WeChat Input Method) work on the MIX Flip 2
 * cover screen. Runs in system_server only.
 *
 * HyperOS miui-services forces com.sohu.inputmethod.sogou.xiaomi/.SogouIME on the cover screen via:
 * 1. InputMethodManagerServiceImpl.onDisplayDeviceStateChanged() overwriting default_input_method
 *    when mDeviceFolded is true (and clearing default_input_method to null if Sogou is absent).
 * 2. SogouInputMethodSwitcher.mayChangeInputMethodLocked() switching back to Sogou on every startInput
 *    whenever getSogouMethodIdLocked() finds Sogou in InputMethodSettingsRepository.
 *
 * While the feature is enabled the Sogou IME component is disabled and Settings.Secure.DEFAULT_INPUT_METHOD is
 * guarded against null/Sogou resets, and the IME DisplayArea is constrained to the 810 px column left of the
 * camera. Every persistent change is undone when the feature is switched off, on "restore system defaults", and
 * on every shutdown ({@link #onShutdown}), so disabling or uninstalling the module always leaves the stock setup.
 */
final class CoverImeGuard {
    private static final String TAG = "MF2C.ImeGuard";
    private static final String KEY_DEFAULT_IME = "default_input_method";
    private static final String KEY_ENABLED_IMES = "enabled_input_methods";
    private static final String KEY_INTERNAL_FLIP_IME = "internal_screen_input_method_in_flip";

    static final String SOGOU_PKG = "com.sohu.inputmethod.sogou.xiaomi";
    private static final String SOGOU_CLS = "com.sohu.inputmethod.sogou.xiaomi.SogouIME";
    private static final String SOGOU_IME_ID = SOGOU_PKG + "/.SogouIME";
    private static final String WETYPE_IME_ID = "com.tencent.wetype/.plugin.hld.WxHldService";
    private static final ComponentName SOGOU_COMPONENT = new ComponentName(SOGOU_PKG, SOGOU_CLS);

    /** WindowManager DisplayArea feature ids (android.window.DisplayAreaOrganizer). */
    private static final int FEATURE_IME_PLACEHOLDER = 7;
    private static final int FEATURE_IME = 8;

    private static boolean observing;
    private static Context appContext;
    private static Handler handler;
    private static volatile String lastKnownThirdPartyIme;
    private static volatile Runnable rotationChangeListener;
    private static Object imePlaceholderOrganizer;
    private static final Map<Integer, List<Object>> imeTokensByDisplay = new HashMap<>();
    private static long lastAppliedImeConfigKey = Long.MIN_VALUE;
    private static Boolean lastAllowThirdParty;
    private static long lastDisplayKey = Long.MIN_VALUE;
    private static volatile boolean shuttingDown;
    private static volatile boolean uninstalled;
    private static final Runnable syncRunnable = () -> sync(appContext);

    private CoverImeGuard() {}

    static void setRotationChangeListener(Runnable listener) {
        rotationChangeListener = listener;
    }

    static synchronized void observe(Context context) {
        if (Process.myUid() != 1000 || context == null || observing) return;
        observing = true;
        appContext = context;
        handler = new Handler(Looper.getMainLooper());

        try {
            ContentResolver cr = context.getContentResolver();
            ContentObserver settingsObserver = new ContentObserver(handler) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    if (ModuleConfig.coverThirdPartyImeEnabled()) schedule();
                }
            };
            cr.registerContentObserver(Settings.Secure.getUriFor(KEY_DEFAULT_IME), false, settingsObserver);
            cr.registerContentObserver(Settings.Secure.getUriFor(KEY_INTERNAL_FLIP_IME), false, settingsObserver);
        } catch (Throwable t) {
            Log.w(TAG, "settings observer registration skipped", t);
        }

        try {
            DisplayManager dm = context.getSystemService(DisplayManager.class);
            if (dm != null) {
                dm.registerDisplayListener(new DisplayManager.DisplayListener() {
                    @Override
                    public void onDisplayAdded(int displayId) {
                        onDisplayMaybeChanged();
                    }

                    @Override
                    public void onDisplayChanged(int displayId) {
                        onDisplayMaybeChanged();
                    }

                    @Override
                    public void onDisplayRemoved(int displayId) {
                    }
                }, handler);
            }
        } catch (Throwable t) {
            Log.w(TAG, "display listener registration skipped", t);
        }

        ModuleConfig.addListener(CoverImeGuard::schedule);
        sync(context);
    }

    /**
     * onDisplayChanged fires for brightness, refresh-rate and state changes many times per second. Only a fold
     * transition or a rotation of display 0 matters here, so everything else returns after two cheap reads.
     */
    private static void onDisplayMaybeChanged() {
        Context c = appContext;
        if (c == null) return;
        boolean outer = CoverResourceOverlay.isDefaultDisplayOuter(c);
        int rotation = 0;
        try {
            DisplayManager dm = c.getSystemService(DisplayManager.class);
            Display d = dm != null ? dm.getDisplay(Display.DEFAULT_DISPLAY) : null;
            if (d != null) rotation = d.getRotation();
        } catch (Throwable ignored) {
        }
        long key = (outer ? 10 : 0) + rotation;
        if (key == lastDisplayKey) return;
        lastDisplayKey = key;
        schedule();
        Runnable listener = rotationChangeListener;
        if (listener != null) {
            try {
                listener.run();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void schedule() {
        if (shuttingDown || uninstalled) return;
        Handler h = handler;
        if (h == null) return;
        h.removeCallbacks(syncRunnable);
        h.postDelayed(syncRunnable, 50L);
    }

    static synchronized void sync(Context context) {
        if (Process.myUid() != 1000 || context == null || shuttingDown || uninstalled) return;
        try {
            ContentResolver cr = context.getContentResolver();
            PackageManager pm = context.getPackageManager();
            if (cr == null || pm == null) return;

            boolean allowThirdParty = ModuleConfig.coverThirdPartyImeEnabled() && FrameworkRuntime.active();
            boolean wasAllowed = Boolean.TRUE.equals(lastAllowThirdParty);
            lastAllowThirdParty = allowThirdParty;

            if (allowThirdParty) {
                syncImeContainerBounds(context, true);
                String preferredIme = resolvePreferredThirdPartyIme(context, cr);
                if (preferredIme == null || preferredIme.isEmpty()) return;

                if (pm.getComponentEnabledSetting(SOGOU_COMPONENT) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                    pm.setComponentEnabledSetting(SOGOU_COMPONENT,
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
                    Log.i(TAG, "disabled forced Sogou cover IME component; preferred=" + preferredIme);
                }
                ensureImeInEnabledList(cr, preferredIme);
                if (isBlankOrSogou(Settings.Secure.getString(cr, KEY_INTERNAL_FLIP_IME))) {
                    Settings.Secure.putString(cr, KEY_INTERNAL_FLIP_IME, preferredIme);
                }
                if (isBlankOrSogou(Settings.Secure.getString(cr, KEY_DEFAULT_IME))) {
                    Settings.Secure.putString(cr, KEY_DEFAULT_IME, preferredIme);
                    Log.i(TAG, "restored cover default_input_method=" + preferredIme);
                }
            } else {
                // Feature off (the default): only undo what this module changed, never touch stock settings.
                releaseImeContainer(context);
                reenableSogou(pm);
                if (wasAllowed || isLeftoverWeTypeCoverState(context, cr)) {
                    restoreStockCoverIme(context, cr);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "cover IME guard sync failed", t);
        }
    }

    /** "Restore system defaults" request from the settings app. */
    static synchronized void restoreSystem(Context context) {
        if (Process.myUid() != 1000 || context == null) return;
        try {
            lastAllowThirdParty = false;
            releaseImeContainer(context);
            reenableSogou(context.getPackageManager());
            restoreStockCoverIme(context, context.getContentResolver());
            Log.i(TAG, "stock cover IME restored on request");
        } catch (Throwable t) {
            Log.w(TAG, "cover IME restore failed", t);
        }
    }

    static synchronized void setUninstalled(Context context, boolean removed) {
        uninstalled = removed;
        if (removed) {
            Handler h = handler;
            if (h != null) h.removeCallbacks(syncRunnable);
            restoreSystem(context);
        }
    }

    /**
     * Called on ACTION_SHUTDOWN / QUICKBOOT_POWEROFF: blocks late syncs, releases the IME container, re-enables the
     * stock Sogou component, and restores the stock cover IME while folded so a reboot without this module (disabled
     * or uninstalled) starts with the stock cover IME. If the module is still active after the reboot and the
     * feature is on, {@link #sync} re-applies the third-party IME.
     */
    static synchronized void onShutdown(Context context) {
        if (Process.myUid() != 1000 || context == null) return;
        shuttingDown = true;
        try {
            Handler h = handler;
            if (h != null) h.removeCallbacks(syncRunnable);
            releaseImeContainer(context);
            reenableSogou(context.getPackageManager());
            restoreStockCoverIme(context, context.getContentResolver());
        } catch (Throwable t) {
            Log.w(TAG, "shutdown restore failed", t);
        }
    }

    private static boolean isLeftoverWeTypeCoverState(Context context, ContentResolver cr) {
        if (cr == null || !CoverResourceOverlay.isDefaultDisplayOuter(context)) return false;
        String currentDefault = Settings.Secure.getString(cr, KEY_DEFAULT_IME);
        String internalFlip = Settings.Secure.getString(cr, KEY_INTERNAL_FLIP_IME);
        return WETYPE_IME_ID.equals(currentDefault) && WETYPE_IME_ID.equals(internalFlip);
    }

    private static void reenableSogou(PackageManager pm) {
        if (pm == null) return;
        try {
            if (pm.getComponentEnabledSetting(SOGOU_COMPONENT) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                pm.setComponentEnabledSetting(SOGOU_COMPONENT,
                        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP);
                Log.i(TAG, "re-enabled stock Sogou cover IME component");
            }
        } catch (IllegalArgumentException ignored) {
            // Sogou is not installed on this device.
        }
    }

    /** While folded, hand the cover screen back to the stock IME and remember the user's IME for the inner screen. */
    private static void restoreStockCoverIme(Context context, ContentResolver cr) {
        if (context == null || cr == null || !CoverResourceOverlay.isDefaultDisplayOuter(context)) return;
        try {
            context.getPackageManager().getPackageInfo(SOGOU_PKG, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }
        String currentDefault = Settings.Secure.getString(cr, KEY_DEFAULT_IME);
        if (currentDefault == null || isSogouIme(currentDefault)) return;
        ensureImeInEnabledList(cr, SOGOU_IME_ID);
        Settings.Secure.putString(cr, KEY_INTERNAL_FLIP_IME, currentDefault);
        Settings.Secure.putString(cr, KEY_DEFAULT_IME, SOGOU_IME_ID);
        Log.i(TAG, "restored stock cover default_input_method=" + SOGOU_IME_ID);
    }

    private static void ensureImeDisplayAreaTokens(Context context) {
        if (!imeTokensByDisplay.isEmpty()) return;
        try {
            Class<?> daoClass = Class.forName("android.window.DisplayAreaOrganizer");
            java.lang.reflect.Constructor<?> ctor = daoClass.getConstructor(java.util.concurrent.Executor.class);
            java.lang.reflect.Method registerMethod = daoClass.getMethod("registerOrganizer", int.class);
            java.util.concurrent.Executor executor = context.getMainExecutor();

            Object organizer = ctor.newInstance(executor);
            Object listObj = registerMethod.invoke(organizer, FEATURE_IME_PLACEHOLDER);
            imePlaceholderOrganizer = organizer;
            if (listObj instanceof List<?> appearedList) {
                for (Object appeared : appearedList) {
                    if (appeared == null) continue;
                    Object daInfo = appeared.getClass().getMethod("getDisplayAreaInfo").invoke(appeared);
                    if (daInfo == null) continue;
                    int displayId = daInfo.getClass().getField("displayId").getInt(daInfo);
                    Object token = daInfo.getClass().getField("token").get(daInfo);
                    if (token != null) {
                        imeTokensByDisplay.computeIfAbsent(displayId, k -> new ArrayList<>()).add(token);
                        ModLog.i(TAG, "registered IME DisplayArea token display=" + displayId);
                    }
                }
            }

            // Clear overrides that earlier builds may have left on the FEATURE_IME container.
            try {
                Object tempImeOrganizer = ctor.newInstance(executor);
                Object imeList = registerMethod.invoke(tempImeOrganizer, FEATURE_IME);
                if (imeList instanceof List<?> appearedList && !appearedList.isEmpty()) {
                    Class<?> tokenClass = Class.forName("android.window.WindowContainerToken");
                    Class<?> wctClass = Class.forName("android.window.WindowContainerTransaction");
                    Object wct = wctClass.getConstructor().newInstance();
                    android.graphics.Rect empty = new android.graphics.Rect();
                    for (Object appeared : appearedList) {
                        if (appeared == null) continue;
                        Object daInfo = appeared.getClass().getMethod("getDisplayAreaInfo").invoke(appeared);
                        if (daInfo == null) continue;
                        Object token = daInfo.getClass().getField("token").get(daInfo);
                        if (token != null) {
                            wctClass.getMethod("setBounds", tokenClass, android.graphics.Rect.class).invoke(wct, token, empty);
                            wctClass.getMethod("setDensityDpi", tokenClass, int.class).invoke(wct, token, 0);
                            wctClass.getMethod("setScreenSizeDp", tokenClass, int.class, int.class).invoke(wct, token, 0, 0);
                            wctClass.getMethod("setSmallestScreenWidthDp", tokenClass, int.class).invoke(wct, token, 0);
                        }
                    }
                    Class<?> woClass = Class.forName("android.window.WindowOrganizer");
                    woClass.getMethod("applyTransaction", wctClass).invoke(woClass.getConstructor().newInstance(), wct);
                }
                daoClass.getMethod("unregisterOrganizer").invoke(tempImeOrganizer);
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            Log.w(TAG, "IME DisplayArea organizer unavailable", t);
        }
    }

    /** Resets the IME placeholder overrides and gives the DisplayArea back to WindowManager. */
    private static void releaseImeContainer(Context context) {
        if (imePlaceholderOrganizer == null) return;
        try {
            syncImeContainerBounds(context, false);
            Class.forName("android.window.DisplayAreaOrganizer").getMethod("unregisterOrganizer")
                    .invoke(imePlaceholderOrganizer);
        } catch (Throwable t) {
            Log.w(TAG, "IME DisplayArea organizer release failed", t);
        }
        imePlaceholderOrganizer = null;
        imeTokensByDisplay.clear();
        lastAppliedImeConfigKey = Long.MIN_VALUE;
    }

    private static void syncImeContainerBounds(Context context, boolean allowThirdParty) {
        try {
            if (allowThirdParty) ensureImeDisplayAreaTokens(context);
            if (imeTokensByDisplay.isEmpty()) return;

            DisplayManager dm = context.getSystemService(DisplayManager.class);
            if (dm == null) return;

            long key = allowThirdParty ? 1000L : 0L;
            for (Map.Entry<Integer, List<Object>> entry : imeTokensByDisplay.entrySet()) {
                Display d = dm.getDisplay(entry.getKey());
                boolean constrain = allowThirdParty && d != null && CoverDisplay.isOuterDisplay(d);
                int rotation = d != null ? d.getRotation() : android.view.Surface.ROTATION_0;
                key = key * 31 + (constrain ? (10 + rotation) : 1);
            }
            if (key == lastAppliedImeConfigKey) return;

            Class<?> tokenClass = Class.forName("android.window.WindowContainerToken");
            Class<?> wctClass = Class.forName("android.window.WindowContainerTransaction");
            Object wct = wctClass.getConstructor().newInstance();
            java.lang.reflect.Method setBounds = wctClass.getMethod("setBounds", tokenClass, android.graphics.Rect.class);
            java.lang.reflect.Method setAppBounds = wctClass.getMethod("setAppBounds", tokenClass, android.graphics.Rect.class);
            java.lang.reflect.Method setDensityDpi = wctClass.getMethod("setDensityDpi", tokenClass, int.class);
            java.lang.reflect.Method setScreenSizeDp = wctClass.getMethod("setScreenSizeDp", tokenClass, int.class, int.class);
            java.lang.reflect.Method setSmallestScreenWidthDp = wctClass.getMethod("setSmallestScreenWidthDp", tokenClass, int.class);
            android.graphics.Rect empty = new android.graphics.Rect();

            boolean defaultOuter = false;
            for (Map.Entry<Integer, List<Object>> entry : imeTokensByDisplay.entrySet()) {
                int displayId = entry.getKey();
                Display d = dm.getDisplay(displayId);
                boolean isOuter = d != null && CoverDisplay.isOuterDisplay(d);
                int rotation = d != null ? d.getRotation() : android.view.Surface.ROTATION_0;
                if (displayId == Display.DEFAULT_DISPLAY) defaultOuter = isOuter;
                if (allowThirdParty && isOuter) {
                    android.graphics.Rect safe = CoverDisplay.coverImeSafeBounds(rotation);
                    // Keep the DisplayArea container origin at (0, 0) so WindowState.getRelativePosition()
                    // produces surface coordinates identical to display coordinates, while appBounds reflects safe.
                    android.graphics.Rect containerBounds = new android.graphics.Rect(0, 0, safe.right, safe.bottom);
                    int dpi = CoverDisplay.coverImeDensityDpi(rotation);
                    int swDp = CoverDisplay.coverImeScreenWidthDp(rotation);
                    int shDp = CoverDisplay.coverImeScreenHeightDp(rotation);
                    for (Object token : entry.getValue()) {
                        setBounds.invoke(wct, token, containerBounds);
                        setAppBounds.invoke(wct, token, safe);
                        setDensityDpi.invoke(wct, token, dpi);
                        setScreenSizeDp.invoke(wct, token, swDp, shDp);
                        setSmallestScreenWidthDp.invoke(wct, token, swDp);
                    }
                } else {
                    android.graphics.Rect fullAppBounds = new android.graphics.Rect();
                    if (d != null && d.getMode() != null) {
                        fullAppBounds.set(0, 0, d.getMode().getPhysicalWidth(), d.getMode().getPhysicalHeight());
                    }
                    for (Object token : entry.getValue()) {
                        setBounds.invoke(wct, token, empty);
                        setAppBounds.invoke(wct, token, fullAppBounds.isEmpty() ? empty : fullAppBounds);
                        setDensityDpi.invoke(wct, token, 0);
                        setScreenSizeDp.invoke(wct, token, 0, 0);
                        setSmallestScreenWidthDp.invoke(wct, token, 0);
                    }
                }
            }

            Class<?> woClass = Class.forName("android.window.WindowOrganizer");
            woClass.getMethod("applyTransaction", wctClass).invoke(woClass.getConstructor().newInstance(), wct);
            lastAppliedImeConfigKey = key;
            ModLog.i(TAG, "applied IME container bounds defaultOuter=" + defaultOuter + " allowThirdParty=" + allowThirdParty);
        } catch (Throwable t) {
            Log.w(TAG, "syncImeContainerBounds failed", t);
        }
    }

    private static String resolvePreferredThirdPartyIme(Context context, ContentResolver cr) {
        String currentDefault = Settings.Secure.getString(cr, KEY_DEFAULT_IME);
        if (isValidThirdPartyIme(currentDefault)) {
            lastKnownThirdPartyIme = currentDefault;
            return currentDefault;
        }

        String internalFlipIme = Settings.Secure.getString(cr, KEY_INTERNAL_FLIP_IME);
        if (isValidThirdPartyIme(internalFlipIme)) {
            lastKnownThirdPartyIme = internalFlipIme;
            return internalFlipIme;
        }

        if (isValidThirdPartyIme(lastKnownThirdPartyIme)) {
            return lastKnownThirdPartyIme;
        }

        String enabled = Settings.Secure.getString(cr, KEY_ENABLED_IMES);
        if (enabled != null && !enabled.isEmpty()) {
            String firstCandidate = null;
            for (String entry : enabled.split(":")) {
                if (entry == null || entry.isEmpty()) continue;
                int semi = entry.indexOf(';');
                String imeId = semi >= 0 ? entry.substring(0, semi) : entry;
                if (!isValidThirdPartyIme(imeId)) continue;
                if (WETYPE_IME_ID.equals(imeId) || imeId.startsWith("com.tencent.wetype/")) {
                    lastKnownThirdPartyIme = imeId;
                    return imeId;
                }
                if (firstCandidate == null) firstCandidate = imeId;
            }
            if (firstCandidate != null) {
                lastKnownThirdPartyIme = firstCandidate;
                return firstCandidate;
            }
        }

        try {
            InputMethodManager imm = context.getSystemService(InputMethodManager.class);
            if (imm != null) {
                String firstInstalled = null;
                for (InputMethodInfo info : imm.getInputMethodList()) {
                    if (info == null) continue;
                    String id = info.getId();
                    if (!isValidThirdPartyIme(id)) continue;
                    if (WETYPE_IME_ID.equals(id) || id.startsWith("com.tencent.wetype/")) {
                        lastKnownThirdPartyIme = id;
                        return id;
                    }
                    if (firstInstalled == null) firstInstalled = id;
                }
                if (firstInstalled != null) {
                    lastKnownThirdPartyIme = firstInstalled;
                    return firstInstalled;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void ensureImeInEnabledList(ContentResolver cr, String imeId) {
        if (imeId == null || imeId.isEmpty()) return;
        String enabled = Settings.Secure.getString(cr, KEY_ENABLED_IMES);
        if (enabled == null || enabled.isEmpty()) {
            Settings.Secure.putString(cr, KEY_ENABLED_IMES, imeId);
            return;
        }
        for (String entry : enabled.split(":")) {
            int semi = entry.indexOf(';');
            String id = semi >= 0 ? entry.substring(0, semi) : entry;
            if (imeId.equals(id)) return;
        }
        Settings.Secure.putString(cr, KEY_ENABLED_IMES, enabled + ":" + imeId);
    }

    private static boolean isBlankOrSogou(String imeId) {
        return imeId == null || imeId.isEmpty() || "null".equalsIgnoreCase(imeId) || isSogouIme(imeId);
    }

    private static boolean isSogouIme(String imeId) {
        return imeId != null && imeId.startsWith(SOGOU_PKG);
    }

    private static boolean isValidThirdPartyIme(String imeId) {
        return imeId != null
                && !imeId.isEmpty()
                && !"null".equalsIgnoreCase(imeId)
                && !imeId.startsWith(SOGOU_PKG)
                && !imeId.startsWith("com.google.android.tts");
    }
}
