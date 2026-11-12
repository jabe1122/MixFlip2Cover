package io.github.jabe1122.mixflip2cover;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowManager;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import io.github.libxposed.api.XposedModule;

/**
 * Universal client-side layout, resource, and WindowInsets compatibility for cover-screen apps.
 */
final class AppCompatibility {
    private static final String TAG = "MF2C.Compat";
    static final String VOICEASSIST_PKG = "com.miui.voiceassist";
    private static final int INNER_DISPLAY_MIN_LONG_EDGE = 2000;

    static volatile boolean outerProcess;
    static volatile boolean portraitProcess;
    private static volatile boolean nativeCoverActivityActive;
    private static volatile boolean voiceAssistProcess;
    private static volatile boolean voiceAssistCompatActivityActive;
    private static volatile boolean voiceAssistDisplayListenerRegistered;
    private static volatile WeakReference<Context> voiceAssistAppContextRef;
    private static volatile Display cachedDefaultRealDisplay;
    private static volatile java.lang.reflect.Field displayResourcesField;
    private static volatile java.lang.reflect.Method wcGetMaxBoundsMethod;
    private static volatile java.lang.reflect.Method wcGetBoundsMethod;
    private static volatile java.lang.reflect.Method getFlipFoldedCutoutMethod;
    private static volatile java.lang.reflect.Field lpMiuiAlwaysDisplayInCutoutField;
    private static volatile int lpMiuiAlwaysDisplayInCutoutState;
    private static volatile Object cachedWindowManagerGlobal;
    private static volatile java.lang.reflect.Method wmgUpdateViewLayoutMethod;
    private static volatile boolean voiceAssistHaloSyncPending;
    private static final Map<View, WindowManager.LayoutParams> voiceAssistHaloViews =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<Boolean> updatingVoiceAssistHaloLayout = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> resolvingDisplayCutout = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> dispatchingCompatWindowInsets = new ThreadLocal<>();
    private static final Set<Resources> compatActivityResources =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final Set<Activity> startedActivities =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static volatile boolean imeProcess;
    /** True while the IME service of this process last evaluated itself as shown on the cover screen. */
    private static volatile boolean imeCoverActive;
    /** Resources.getDisplayMetrics/getConfiguration hooks are only needed by IMEs and natively adapted apps. */
    private static volatile boolean metricsHooksNeeded;
    /** 0 when not cover-adapted (inner screen); >0 holds the last cover pixel width applied to WeType. */
    private static volatile int lastImeAdaptedWidth = 0;
    /** Set whenever WeType observes the cover display; cleared once inner-screen cache reset completes. */
    private static volatile boolean weTypeNeedsInnerReset;
    private static String packageName = "";
    private static long packageVersionCode = -1;
    private static final WeakHashMap<Activity, State> states = new WeakHashMap<>();
    private static final Map<Long, Boolean> statusHeightIdCache = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Boolean> nativeCoverActivityCache = new ConcurrentHashMap<>();

    private static final class WeakIdentityKey extends WeakReference<Object> {
        final int hash;

        WeakIdentityKey(Object referent) {
            super(referent);
            this.hash = System.identityHashCode(referent);
        }

        WeakIdentityKey(Object referent, ReferenceQueue<Object> queue) {
            super(referent, queue);
            this.hash = System.identityHashCode(referent);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof WeakIdentityKey other)) return false;
            Object mine = get();
            return mine != null && mine == other.get();
        }
    }

    private static final class WeakIdentityMap<V> {
        private final HashMap<WeakIdentityKey, V> map = new HashMap<>();
        private final ReferenceQueue<Object> queue = new ReferenceQueue<>();

        private void expunge() {
            Reference<?> ref;
            while ((ref = queue.poll()) != null) {
                map.remove(ref);
            }
        }

        synchronized V get(Object key) {
            if (key == null) return null;
            expunge();
            return map.get(new WeakIdentityKey(key));
        }

        synchronized void put(Object key, V value) {
            if (key == null) return;
            expunge();
            map.put(new WeakIdentityKey(key, queue), value);
        }

        synchronized boolean isEmpty() {
            expunge();
            return map.isEmpty();
        }

        synchronized List<Map.Entry<Object, V>> entriesSnapshot() {
            expunge();
            ArrayList<Map.Entry<Object, V>> out = new ArrayList<>(map.size());
            for (Map.Entry<WeakIdentityKey, V> e : map.entrySet()) {
                Object k = e.getKey().get();
                if (k != null) {
                    out.add(new AbstractMap.SimpleImmutableEntry<>(k, e.getValue()));
                }
            }
            return out;
        }

        synchronized void clear() {
            map.clear();
            while (queue.poll() != null) {
                // drain cleared references
            }
        }
    }

    /**
     * System-provided DisplayMetrics/Configuration values that were overwritten in place while a natively adapted
     * cover Activity (or the cover IME) was active. Keyed weakly by identity (both types override equals/hashCode)
     * and restored as soon as the override is no longer needed, without strong retention or entry caps.
     */
    private static final WeakIdentityMap<Object> overriddenOriginals = new WeakIdentityMap<>();
    /** Per-window tag (true = compat Activity window, false = floating overlay window) for WindowInsets in voiceassist. */
    private static final WeakIdentityMap<Boolean> windowInsetsCompatTag = new WeakIdentityMap<>();

    /** NetEase Cloud Music builds whose obfuscated MiMiniPlayerActivity.H8() was verified (9.6.x). */
    private static final long NETEASE_MIN = 9006000L, NETEASE_MAX_EXCLUSIVE = 9007000L;
    /** WeChat Input Method builds whose obfuscated layout cache (utils.m1) was verified (4.0.0: 58xxx, 4.0.1: 59xxx). */
    static final long WETYPE_V400_MIN = 58000L;
    static final long WETYPE_V401_MIN = 59000L;
    static final long WETYPE_MIN = WETYPE_V400_MIN, WETYPE_MAX_EXCLUSIVE = 60000L;

    private static XposedModule hookModule;
    private static volatile boolean packageInspected;

    private AppCompatibility() {}

    /**
     * Inspects the hooked package once (from Application.attach, where a Context exists) and installs the
     * comparatively expensive Resources metrics hooks and InputMethodService hooks only for packages that need
     * them: input methods, and apps that declare native cover-screen layouts via miui.supportFlipFullScreen=0.
     */
    private static synchronized void inspectPackage(Context c) {
        if (packageInspected || c == null || hookModule == null) return;
        packageInspected = true;
        boolean ime = false;
        boolean nativeCover = false;
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            android.content.pm.PackageInfo info = pm.getPackageInfo(c.getPackageName(),
                    android.content.pm.PackageManager.PackageInfoFlags.of(
                            android.content.pm.PackageManager.GET_SERVICES
                                    | android.content.pm.PackageManager.GET_ACTIVITIES
                                    | android.content.pm.PackageManager.GET_META_DATA));
            packageVersionCode = info.getLongVersionCode();
            if (info.services != null) {
                for (android.content.pm.ServiceInfo s : info.services) {
                    if (android.Manifest.permission.BIND_INPUT_METHOD.equals(s.permission)) {
                        ime = true;
                        break;
                    }
                }
            }
            if (info.applicationInfo != null && info.applicationInfo.metaData != null
                    && info.applicationInfo.metaData.getInt("miui.supportFlipFullScreen", -1) == 0) {
                nativeCover = true;
            }
            if (!nativeCover && info.activities != null) {
                for (android.content.pm.ActivityInfo a : info.activities) {
                    if (a.metaData != null && a.metaData.getInt("miui.supportFlipFullScreen", -1) == 0) {
                        nativeCover = true;
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "package inspection failed", t);
        }
        imeProcess = ime;
        voiceAssistProcess = VOICEASSIST_PKG.equals(packageName);
        metricsHooksNeeded = ime || nativeCover || voiceAssistProcess;
        ModLog.i(TAG, "package " + c.getPackageName() + " version=" + packageVersionCode
                + " ime=" + ime + " nativeCover=" + nativeCover + " voiceAssist=" + voiceAssistProcess);
        if ("com.miui.fliphome".equals(packageName) || !metricsHooksNeeded) return;
        try {
            installMetricsHooks(hookModule);
        } catch (Throwable t) {
            Log.w(TAG, "metrics hooks unavailable", t);
        }
        if (ime) {
            installImeClientHooks(hookModule);
            registerImeDisplayListener(c);
        }
        if (voiceAssistProcess) {
            Context appCtx = c.getApplicationContext() != null ? c.getApplicationContext() : c;
            voiceAssistAppContextRef = new WeakReference<>(appCtx);
            ModLog.registerLogCollector(appCtx, VOICEASSIST_PKG);
            installVoiceAssistClientHooks(hookModule);
            registerVoiceAssistDisplayListener(appCtx);
            syncVoiceAssistDisplayState(appCtx);
        }
    }

    private static boolean versionIn(long min, long maxExclusive) {
        return packageVersionCode >= min && packageVersionCode < maxExclusive;
    }

    static boolean isSupportedWeTypeM1Version(long versionCode) {
        return versionCode >= WETYPE_MIN && versionCode < WETYPE_MAX_EXCLUSIVE;
    }

    static boolean isWeTypeV401(long versionCode) {
        return versionCode >= WETYPE_V401_MIN && versionCode < WETYPE_MAX_EXCLUSIVE;
    }

    static String weTypeUpdateResourceLayoutMethod(long versionCode) {
        return isWeTypeV401(versionCode) ? "f3" : "h3";
    }

    static String weTypeModeFlowMethod(long versionCode) {
        return isWeTypeV401(versionCode) ? "h3" : "j3";
    }

    static String weTypeRebuildAllMethod(long versionCode) {
        return isWeTypeV401(versionCode) ? "c2" : "e2";
    }

    static String weTypeResetDataCacheMethod(long versionCode) {
        return isWeTypeV401(versionCode) ? "g2" : "i2";
    }

    static String weTypeDefaultBodyHeightMethod(long versionCode) {
        return isWeTypeV401(versionCode) ? "k0" : "j0";
    }

    static String weTypeSelfDrawScaleMethod(long versionCode) {
        return isWeTypeV401(versionCode) ? "B2" : "F2";
    }

    static boolean isModuleOverriddenMetrics(android.util.DisplayMetrics dm) {
        if (dm == null) return false;
        for (int rot = 0; rot <= 3; rot++) {
            if (dm.widthPixels == CoverDisplay.coverImeWidth(rot)
                    && dm.heightPixels == CoverDisplay.coverImeDisplayHeight(rot)
                    && dm.densityDpi == CoverDisplay.coverImeDensityDpi(rot)) {
                return true;
            }
        }
        return dm.densityDpi == CoverDisplay.DENSITY_DPI && CoverDisplay.isOuterDimensions(dm.widthPixels, dm.heightPixels);
    }

    static boolean isCoverSizedMetrics(android.util.DisplayMetrics dm) {
        if (dm == null) return false;
        if (CoverDisplay.isOuterDimensions(dm.widthPixels, dm.heightPixels)) return true;
        for (int rot = 0; rot <= 3; rot++) {
            int w = CoverDisplay.coverImeWidth(rot);
            int h = CoverDisplay.coverImeDisplayHeight(rot);
            if ((dm.widthPixels == w && dm.heightPixels == h)
                    || (dm.widthPixels == h && dm.heightPixels == w)) {
                return true;
            }
        }
        // Also recognize HyperOS ProcessCompatController scaled cover bounds (e.g., 1011x1740 at 0.8x).
        int min = Math.min(dm.widthPixels, dm.heightPixels);
        int max = Math.max(dm.widthPixels, dm.heightPixels);
        return min > 0 && min <= CoverDisplay.WIDTH && max > 0 && max <= 1800;
    }

    static boolean isModuleOverriddenConfig(android.content.res.Configuration cfg) {
        if (cfg == null) return false;
        for (int rot = 0; rot <= 3; rot++) {
            if (cfg.densityDpi == CoverDisplay.coverImeDensityDpi(rot)
                    && cfg.screenWidthDp == CoverDisplay.coverImeScreenWidthDp(rot)
                    && cfg.screenHeightDp == CoverDisplay.coverImeScreenHeightDp(rot)) {
                return true;
            }
        }
        return cfg.densityDpi == CoverDisplay.DENSITY_DPI
                && ((cfg.screenWidthDp == 372 && cfg.screenHeightDp == 428)
                || (cfg.screenWidthDp == 428 && cfg.screenHeightDp == 372));
    }

    static boolean isCoverSizedConfig(android.content.res.Configuration cfg) {
        if (cfg == null) return false;
        if (isModuleOverriddenConfig(cfg)) return true;
        return cfg.screenWidthDp > 0 && cfg.screenWidthDp <= 680
                && cfg.screenHeightDp > 0 && cfg.screenHeightDp <= 680;
    }

    private static void copyMetrics(android.util.DisplayMetrics dst, android.util.DisplayMetrics src) {
        if (dst == null || src == null) return;
        dst.setTo(src);
        dst.widthPixels = src.widthPixels;
        dst.heightPixels = src.heightPixels;
        dst.density = src.density;
        dst.densityDpi = src.densityDpi;
        dst.scaledDensity = src.scaledDensity;
        dst.xdpi = src.xdpi;
        dst.ydpi = src.ydpi;
    }

    private static boolean isMetricsOverridePermitted() {
        if (hookModule == null) {
            return true;
        }
        if (imeCoverActive && ModuleConfig.coverThirdPartyImeEnabled()) {
            return true;
        }
        return outerProcess
                && ((nativeCoverActivityActive && nativeCoverCompatEnabled()) || voiceAssistOverlayAdapting());
    }

    /** Overwrites a DisplayMetrics in place, remembering the latest system-provided values for restoration. */
    static void overrideMetrics(android.util.DisplayMetrics dm, int w, int h, int dpi) {
        if (dm == null) return;
        float density = dpi / 160f;
        synchronized (overriddenOriginals) {
            if (!isMetricsOverridePermitted()) return;
            if (dm.widthPixels == w && dm.heightPixels == h && dm.densityDpi == dpi && dm.density == density) return;
            if (!isModuleOverriddenMetrics(dm)) {
                android.util.DisplayMetrics copy = new android.util.DisplayMetrics();
                copyMetrics(copy, dm);
                overriddenOriginals.put(dm, copy);
            }
            float fontScale = dm.density > 0 && dm.scaledDensity > 0 ? dm.scaledDensity / dm.density : 1f;
            dm.widthPixels = w;
            dm.heightPixels = h;
            dm.densityDpi = dpi;
            dm.density = density;
            dm.scaledDensity = density * fontScale;
        }
    }

    private static void copyConfig(android.content.res.Configuration dst, android.content.res.Configuration src) {
        if (dst == null || src == null) return;
        dst.setTo(src);
        dst.densityDpi = src.densityDpi;
        dst.screenWidthDp = src.screenWidthDp;
        dst.screenHeightDp = src.screenHeightDp;
        dst.smallestScreenWidthDp = src.smallestScreenWidthDp;
        dst.orientation = src.orientation;
    }

    /** Remembers the latest system-provided Configuration before it is overwritten in place. */
    static void rememberConfig(android.content.res.Configuration cfg) {
        if (cfg == null) return;
        synchronized (overriddenOriginals) {
            if (!isModuleOverriddenConfig(cfg)) {
                android.content.res.Configuration copy = new android.content.res.Configuration(cfg);
                copyConfig(copy, cfg);
                overriddenOriginals.put(cfg, copy);
            }
        }
    }

    /**
     * Synthesizes true inner-screen DisplayMetrics from hardware Display.Mode dimensions when HyperOS
     * ActivityThread.sizeCompatBounds momentarily clamps Display.getRealMetrics() to cover bounds (e.g. 810x1392)
     * during the ~50ms unfold transition window before updateSizeCompatBounds(null) executes on the main Looper.
     */
    static boolean sanitizeInnerRealMetrics(
            android.util.DisplayMetrics real, int modePhysW, int modePhysH, int rotation, int fallbackDpi) {
        if (real == null) return false;
        boolean clampedOrInvalid = real.widthPixels <= 0
                || real.heightPixels <= 0
                || isCoverSizedMetrics(real)
                || Math.max(real.widthPixels, real.heightPixels) < INNER_DISPLAY_MIN_LONG_EDGE;
        if (!clampedOrInvalid || modePhysW <= 0 || modePhysH <= 0) {
            return false;
        }
        int shortSide = Math.min(modePhysW, modePhysH);
        int longSide = Math.max(modePhysW, modePhysH);
        if (longSide < INNER_DISPLAY_MIN_LONG_EDGE) {
            return false;
        }
        boolean portrait = CoverDisplay.isPortraitRotation(rotation);
        float fontScale = real.density > 0 && real.scaledDensity > 0
                ? real.scaledDensity / real.density : 1f;
        real.widthPixels = portrait ? shortSide : longSide;
        real.heightPixels = portrait ? longSide : shortSide;
        int compatScaledDpi = Math.round(CoverDisplay.DENSITY_DPI * 0.8f);
        if (real.densityDpi <= 0
                || real.densityDpi == CoverDisplay.IME_DENSITY_DPI_ROT0
                || real.densityDpi == CoverDisplay.IME_DENSITY_DPI_WIDE
                || real.densityDpi == compatScaledDpi) {
            int dpi = fallbackDpi > 0 ? fallbackDpi : CoverDisplay.DENSITY_DPI;
            real.densityDpi = dpi;
            real.density = dpi / 160f;
            real.scaledDensity = real.density * fontScale;
            if (real.xdpi <= 0f) real.xdpi = dpi;
            if (real.ydpi <= 0f) real.ydpi = dpi;
        }
        return true;
    }

    private static android.util.DisplayMetrics queryInnerRealMetrics() {
        try {
            Display d = resolveRealDefaultDisplay();
            if (d != null && !CoverDisplay.isOuterDisplay(d)) {
                android.util.DisplayMetrics real = new android.util.DisplayMetrics();
                d.getRealMetrics(real);
                int physW = 0;
                int physH = 0;
                Display.Mode mode = d.getMode();
                if (mode != null) {
                    physW = mode.getPhysicalWidth();
                    physH = mode.getPhysicalHeight();
                }
                int rot = getLiveImeDisplayRotation(false);
                if (rot < 0) rot = d.getRotation();
                int sysDpi = 0;
                try {
                    int d0 = Resources.getSystem().getConfiguration().densityDpi;
                    if (d0 > 0
                            && d0 != CoverDisplay.IME_DENSITY_DPI_ROT0
                            && d0 != CoverDisplay.IME_DENSITY_DPI_WIDE
                            && d0 != Math.round(CoverDisplay.DENSITY_DPI * 0.8f)) {
                        sysDpi = d0;
                    }
                } catch (Throwable ignored) {
                }
                int fallbackDpi = sysDpi > 0 ? sysDpi
                        : (android.util.DisplayMetrics.DENSITY_DEVICE_STABLE > 0
                                ? android.util.DisplayMetrics.DENSITY_DEVICE_STABLE
                                : CoverDisplay.DENSITY_DPI);
                sanitizeInnerRealMetrics(real, physW, physH, rot, fallbackDpi);
                if (real.widthPixels > 0 && real.heightPixels > 0 && !isCoverSizedMetrics(real)) {
                    return real;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void applyInnerRealToMetrics(
            android.util.DisplayMetrics dm, android.util.DisplayMetrics innerReal) {
        if (dm == null || innerReal == null) return;
        float fontScale = dm.density > 0 && dm.scaledDensity > 0 ? dm.scaledDensity / dm.density : 1f;
        copyMetrics(dm, innerReal);
        dm.scaledDensity = dm.density * fontScale;
    }

    private static void applyInnerRealToConfig(
            android.content.res.Configuration cfg, android.util.DisplayMetrics innerReal) {
        if (cfg == null || innerReal == null || innerReal.densityDpi <= 0) return;
        int wDp = Math.round(innerReal.widthPixels * 160f / innerReal.densityDpi);
        int hDp = Math.round(innerReal.heightPixels * 160f / innerReal.densityDpi);
        cfg.densityDpi = innerReal.densityDpi;
        cfg.screenWidthDp = wDp;
        cfg.screenHeightDp = hDp;
        cfg.smallestScreenWidthDp = Math.min(wDp, hDp);
        cfg.orientation = innerReal.widthPixels <= innerReal.heightPixels
                ? android.content.res.Configuration.ORIENTATION_PORTRAIT
                : android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        setWindowBounds(cfg, new Rect(0, 0, innerReal.widthPixels, innerReal.heightPixels));
    }

    /** Restores every DisplayMetrics/Configuration still holding a module override. */
    static void restoreOverrides(boolean nowOnCover) {
        lastImeBoundsRotation = -1;
        if (overriddenOriginals.isEmpty()) return;
        android.util.DisplayMetrics innerReal = nowOnCover ? null : queryInnerRealMetrics();
        boolean restored = false;
        synchronized (overriddenOriginals) {
            if (overriddenOriginals.isEmpty()) return;
            for (Map.Entry<Object, Object> e : overriddenOriginals.entriesSnapshot()) {
                try {
                    if (e.getKey() instanceof android.util.DisplayMetrics dm
                            && e.getValue() instanceof android.util.DisplayMetrics orig) {
                        if (!isModuleOverriddenMetrics(dm)) continue;
                        if (!nowOnCover && isCoverSizedMetrics(orig)) {
                            applyInnerRealToMetrics(dm, innerReal);
                        } else {
                            copyMetrics(dm, orig);
                        }
                    } else if (e.getKey() instanceof android.content.res.Configuration cfg
                            && e.getValue() instanceof android.content.res.Configuration orig) {
                        if (!isModuleOverriddenConfig(cfg)) continue;
                        if (!nowOnCover && isCoverSizedConfig(orig)) {
                            applyInnerRealToConfig(cfg, innerReal);
                        } else {
                            copyConfig(cfg, orig);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            overriddenOriginals.clear();
            restored = true;
        }
        if (restored) {
            ModLog.i(TAG, "restored system display metrics");
        }
    }

    private static void setNativeCoverActive(boolean active) {
        boolean was = nativeCoverActivityActive;
        nativeCoverActivityActive = active;
        if (was && !active) restoreOverrides(outerProcess);
    }

    static final java.util.List<String> ADAPTED_APP_SCOPE = java.util.List.of(
            "com.tencent.mm",
            "com.tencent.mobileqq",
            "com.sankuai.meituan",
            "com.xiaomi.shop",
            "com.mi.health",
            "com.android.calendar",
            "com.netease.cloudmusic",
            VOICEASSIST_PKG
    );

    private static final java.util.Set<String> ADAPTED_APP_SET = new java.util.HashSet<>(ADAPTED_APP_SCOPE);

    private static final String[] PACKER_CLASS_PREFIXES = {
            "com.stub.StubApp",
            "com.qihoo.util.",
            "com.secneo.",
            "com.secshell.",
            "com.bangcle.",
            "s.h.e.l.l.",
            "com.ijiami.",
            "com.tencent.StubShell.",
            "com.baidu.protect.",
            "com.ali.mobisecenhance.",
            "com.netease.nis.wrapper.",
            "cn.securitystack."
    };

    private static final String[] PACKER_NATIVE_LIBS = {
            "libjiagu.so",
            "libjiagu_64.so",
            "libjiagu_a64.so",
            "libDexHelper.so",
            "libDexHelper-x86.so",
            "libSecShell.so",
            "libexec.so",
            "libexecmain.so",
            "libbaiduprotect.so",
            "libshella.so",
            "libtup.so"
    };

    static boolean supports(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        return !"android".equals(pkg)
                && !"system".equals(pkg)
                && !"com.android.systemui".equals(pkg)
                && !"com.miui.home".equals(pkg)
                && !BuildConfig.APPLICATION_ID.equals(pkg);
    }

    static boolean hasDedicatedInProcessAdapter(String pkg) {
        return pkg != null && ADAPTED_APP_SET.contains(pkg);
    }

    static boolean isPackerClassName(String className) {
        if (className == null || className.isEmpty()) return false;
        for (String prefix : PACKER_CLASS_PREFIXES) {
            if (className.equals(prefix) || className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    static boolean isPackerLibraryName(String fileName) {
        if (fileName == null || fileName.isEmpty()) return false;
        for (String lib : PACKER_NATIVE_LIBS) {
            if (fileName.equals(lib)) return true;
        }
        return fileName.startsWith("libjiagu") && fileName.endsWith(".so");
    }

    /**
     * Returns true when {@code appInfo} uses a commercial packer / anti-hook wrapper (e.g., 360 Jiagu, Bangcle/SecNeo,
     * IJM, Tencent Legu, Baidu Protect) that scans {@code /proc/self/maps} for Zygisk/LSPosed injection and kills or
     * flags the process. Such apps must stay out of LSPosed scope and rely on {@code system_server} adaptation.
     */
    static boolean isPackedOrProtectedApp(android.content.pm.ApplicationInfo appInfo) {
        if (appInfo == null) return false;
        if (hasDedicatedInProcessAdapter(appInfo.packageName)) return false;
        if (isPackerClassName(appInfo.className) || isPackerClassName(appInfo.appComponentFactory)) {
            return true;
        }
        String libDir = appInfo.nativeLibraryDir;
        if (libDir != null && !libDir.isEmpty()) {
            try {
                java.io.File dir = new java.io.File(libDir);
                for (String lib : PACKER_NATIVE_LIBS) {
                    if (new java.io.File(dir, lib).exists()) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static Display resolveRealDefaultDisplay() {
        Display cached = cachedDefaultRealDisplay;
        if (cached != null) return cached;
        try {
            Object dmg = dmgInstance;
            if (dmg == null) {
                dmg = Class.forName("android.hardware.display.DisplayManagerGlobal")
                        .getDeclaredMethod("getInstance").invoke(null);
                dmgInstance = dmg;
            }
            if (dmg != null) {
                Display d = (Display) dmg.getClass()
                        .getDeclaredMethod("getRealDisplay", int.class)
                        .invoke(dmg, Display.DEFAULT_DISPLAY);
                if (d != null) {
                    cachedDefaultRealDisplay = d;
                    return d;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isLiveDefaultDisplayInner() {
        try {
            Display d = resolveRealDefaultDisplay();
            if (d != null && d.getMode() != null && !CoverDisplay.isOuterDisplay(d)) {
                outerProcess = false;
                portraitProcess = false;
                if (voiceAssistProcess) {
                    voiceAssistCompatActivityActive = false;
                }
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    static boolean isInnerScreenMetrics(android.util.DisplayMetrics dm) {
        return dm != null && Math.max(dm.widthPixels, dm.heightPixels) >= INNER_DISPLAY_MIN_LONG_EDGE;
    }

    static boolean isInnerScreenResources(Resources r) {
        if (r == null) return false;
        try {
            if (isInnerScreenMetrics(r.getDisplayMetrics())) {
                outerProcess = false;
                portraitProcess = false;
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    static boolean isInnerScreenConfig(android.content.res.Configuration cfg) {
        if (cfg == null) return false;
        try {
            java.lang.reflect.Field wcField = windowConfigurationField;
            if (wcField == null) {
                wcField = android.content.res.Configuration.class.getDeclaredField("windowConfiguration");
                wcField.setAccessible(true);
                windowConfigurationField = wcField;
            }
            Object wc = wcField.get(cfg);
            if (wc != null) {
                if (wcGetBoundsMethod == null) {
                    wcGetBoundsMethod = wc.getClass().getMethod("getBounds");
                }
                if (wcGetBoundsMethod.invoke(wc) instanceof Rect b
                        && Math.max(b.width(), b.height()) >= INNER_DISPLAY_MIN_LONG_EDGE) {
                    return true;
                }
                if (wcGetMaxBoundsMethod == null) {
                    wcGetMaxBoundsMethod = wc.getClass().getMethod("getMaxBounds");
                }
                if (wcGetMaxBoundsMethod.invoke(wc) instanceof Rect mb
                        && Math.max(mb.width(), mb.height()) >= INNER_DISPLAY_MIN_LONG_EDGE) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    static boolean isInnerScreenContext(Context c) {
        if (c != null) {
            try {
                if (isInnerScreenResources(c.getResources())) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
            try {
                Display d = c.getDisplay();
                if (d != null && d.getMode() != null && !CoverDisplay.isOuterDisplay(d)) {
                    outerProcess = false;
                    portraitProcess = false;
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return isLiveDefaultDisplayInner();
    }

    static boolean resourcePatchActive() {
        return !nativeCoverActivityActive
                && (!voiceAssistProcess || voiceAssistCompatActivityActive)
                && FrameworkRuntime.active()
                && ModuleConfig.immersiveEnabled()
                && ModuleConfig.compatEnabled(packageName)
                && outerProcess
                && portraitProcess
                && !isLiveDefaultDisplayInner();
    }

    static boolean isResourcePatchActiveFor(Activity activity) {
        if (activity == null || isNativeCoverFullscreenActivity(activity)) return false;
        try {
            Display d = activity.getDisplay();
            return FrameworkRuntime.active()
                    && ModuleConfig.immersiveEnabled()
                    && ModuleConfig.compatEnabled(activity.getPackageName())
                    && CoverDisplay.isOuterPortraitDisplay(d);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean nativeCoverCompatEnabled() {
        return FrameworkRuntime.active()
                && ModuleConfig.immersiveEnabled()
                && ModuleConfig.compatEnabled(packageName);
    }

    static boolean isNativeCoverFullscreenActivity(Activity a) {
        if (a == null) return false;
        if (VOICEASSIST_PKG.equals(a.getPackageName())) {
            return false;
        }
        Class<?> cls = a.getClass();
        Boolean cached = nativeCoverActivityCache.get(cls);
        if (cached != null) return cached;
        boolean result = false;
        try {
            if ("com.netease.cloudmusic.activity.MiMiniPlayerActivity".equals(cls.getName())) {
                result = true;
            } else {
                android.content.pm.PackageManager pm = a.getPackageManager();
                if (pm != null) {
                    android.content.pm.ActivityInfo ai = pm.getActivityInfo(
                            a.getComponentName(), android.content.pm.PackageManager.GET_META_DATA);
                    if (ai != null && ai.metaData != null && ai.metaData.containsKey("miui.supportFlipFullScreen")) {
                        result = ai.metaData.getInt("miui.supportFlipFullScreen", -1) == 0;
                    } else {
                        android.content.pm.ApplicationInfo appInfo = pm.getApplicationInfo(
                                a.getPackageName(), android.content.pm.PackageManager.GET_META_DATA);
                        if (appInfo != null && appInfo.metaData != null && appInfo.metaData.containsKey("miui.supportFlipFullScreen")) {
                            result = appInfo.metaData.getInt("miui.supportFlipFullScreen", -1) == 0;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        nativeCoverActivityCache.put(cls, result);
        return result;
    }

    static void install(XposedModule module, ClassLoader loader, String pkg) throws ReflectiveOperationException {
        packageName = pkg;
        hookModule = module;
        refreshProcessDisplayFallback();
        if ("com.tencent.mm".equals(pkg)) {
            try {
                WeChatInsets.install(module, loader);
            } catch (Throwable t) {
                Log.w(TAG, "WeChat insets compat unavailable", t);
            }
            try {
                WeChatTabLayout.install(module);
            } catch (Throwable t) {
                Log.w(TAG, "WeChat tab layout compat unavailable", t);
            }
        }

        module.hook(Application.class.getDeclaredMethod("attach", Context.class)).intercept(chain -> {
            Context c = null;
            try {
                c = (Context) chain.getArg(0);
                updateContext(c);
                inspectPackage(c);
            } catch (Throwable ignored) {
            }
            Object res = chain.proceed();
            try {
                if ("com.tencent.wetype".equals(packageName)) {
                    if (chain.getThisObject() instanceof Context appCtx) {
                        ensureWeTypeCoverHooks(appCtx);
                    }
                    if (c != null) {
                        ensureWeTypeCoverHooks(c);
                    }
                }
            } catch (Throwable ignored) {
            }
            return res;
        });

        module.hook(Instrumentation.class.getDeclaredMethod("callApplicationOnCreate", Application.class)).intercept(chain -> {
            try {
                Application app = (Application) chain.getArg(0);
                updateContext(app);
                inspectPackage(app);
                if ("com.tencent.wetype".equals(packageName)) {
                    ensureWeTypeCoverHooks(app);
                }
            } catch (Throwable ignored) {
            }
            return chain.proceed();
        });

        if (!"com.miui.fliphome".equals(pkg)) {
            installStatusHeightHooks(module);
            installClientInsetsHooks(module);
        }

        module.hook(Instrumentation.class.getDeclaredMethod("callActivityOnCreate", Activity.class, Bundle.class)).intercept(chain -> {
            Activity a = (Activity) chain.getArg(0);
            try {
                if (a != null) startedActivities.add(a);
                onActivityPreActive(a);
                if ("com.tencent.mm".equals(pkg)) {
                    WeChatInsets.install(module, a.getClassLoader());
                }
            } catch (Throwable ignored) {
            }
            Object result = chain.proceed();
            try {
                attach(a);
            } catch (Throwable e) {
                Log.w(TAG, "create layout skipped", e);
            }
            return result;
        });

        try {
            module.hook(Instrumentation.class.getDeclaredMethod("callActivityOnStart", Activity.class)).intercept(chain -> {
                Activity a = (Activity) chain.getArg(0);
                try {
                    if (a != null) startedActivities.add(a);
                    onActivityPreActive(a);
                } catch (Throwable ignored) {
                }
                Object result = chain.proceed();
                try {
                    attach(a);
                } catch (Throwable ignored) {
                }
                return result;
            });
        } catch (NoSuchMethodException ignored) {
        }

        try {
            module.hook(Activity.class.getDeclaredMethod(
                    "onConfigurationChanged", android.content.res.Configuration.class)).intercept(chain -> {
                Activity a = (Activity) chain.getThisObject();
                try {
                    if (a != null) {
                        if (chain.getArg(0) instanceof android.content.res.Configuration cfg
                                && isInnerScreenConfig(cfg)) {
                            outerProcess = false;
                            portraitProcess = false;
                            if (voiceAssistProcess) {
                                voiceAssistCompatActivityActive = false;
                            }
                        } else {
                            updateMode(a);
                        }
                        onActivityPreActive(a);
                    }
                } catch (Throwable ignored) {
                }
                Object result = chain.proceed();
                try {
                    if (a != null) {
                        State state = states.get(a);
                        if (state != null) state.apply();
                    }
                } catch (Throwable ignored) {
                }
                return result;
            });
        } catch (NoSuchMethodException ignored) {
        }

        module.hook(Instrumentation.class.getDeclaredMethod("callActivityOnResume", Activity.class)).intercept(chain -> {
            Activity a = (Activity) chain.getArg(0);
            try {
                if (a != null) startedActivities.add(a);
                onActivityPreActive(a);
            } catch (Throwable ignored) {
            }
            Object result = chain.proceed();
            try {
                homeEvent(a, true);
                attach(a);
            } catch (Throwable e) {
                Log.w(TAG, "resume layout skipped", e);
            }
            return result;
        });

        module.hook(Instrumentation.class.getDeclaredMethod("callActivityOnPause", Activity.class)).intercept(chain -> {
            try {
                Activity a = (Activity) chain.getArg(0);
                if ("com.miui.fliphome".equals(pkg)) {
                    homeEvent(a, false);
                }
                if (a != null && isNativeCoverFullscreenActivity(a)) {
                    recomputeNativeCoverActivityState(a);
                }
                if (voiceAssistProcess && a != null && a.isFinishing()) {
                    recomputeVoiceAssistCompatActivityState(a);
                }
            } catch (Throwable ignored) {
            }
            return chain.proceed();
        });

        module.hook(Instrumentation.class.getDeclaredMethod("callActivityOnStop", Activity.class)).intercept(chain -> {
            try {
                Activity a = (Activity) chain.getArg(0);
                if (a != null) startedActivities.remove(a);
                if (a != null && isNativeCoverFullscreenActivity(a)) {
                    recomputeNativeCoverActivityState(a);
                }
                if (voiceAssistProcess) {
                    recomputeVoiceAssistCompatActivityState(a);
                }
            } catch (Throwable ignored) {
            }
            return chain.proceed();
        });

        module.hook(Instrumentation.class.getDeclaredMethod("callActivityOnDestroy", Activity.class)).intercept(chain -> {
            try {
                Activity a = (Activity) chain.getArg(0);
                if (a != null) startedActivities.remove(a);
                State state = states.remove(a);
                if (state != null) state.stop();
                recomputeNativeCoverActivityState(a);
                if (voiceAssistProcess) {
                    if (a != null) {
                        try {
                            Resources r = a.getResources();
                            if (r != null) compatActivityResources.remove(r);
                        } catch (Throwable ignored) {
                        }
                    }
                    recomputeVoiceAssistCompatActivityState(a);
                }
            } catch (Throwable ignored) {
            }
            return chain.proceed();
        });

        ModuleConfig.addListener(() -> {
            if (!nativeCoverCompatEnabled() && nativeCoverActivityActive) {
                setNativeCoverActive(false);
            } else if (metricsHooksNeeded && !imeProcess && !voiceAssistProcess) {
                recomputeNativeCoverActivityState(null);
            }
            if (voiceAssistProcess) {
                syncVoiceAssistDisplayState(voiceAssistAppContextRef != null ? voiceAssistAppContextRef.get() : null);
            }
            for (State s : new ArrayList<>(states.values())) {
                s.apply();
            }
        });
        ModLog.i(TAG, "app layout compatibility installed package=" + pkg);
    }

    private static void installImeClientHooks(XposedModule module) {
        try {
            Class<?> imsClass = Class.forName("android.inputmethodservice.InputMethodService");
            module.hook(imsClass.getDeclaredMethod("onEvaluateFullscreenMode")).intercept(chain -> {
                try {
                    if (ModuleConfig.coverThirdPartyImeEnabled() && chain.getThisObject() instanceof Context ctx) {
                        if (isImeOnCoverDisplay(ctx)) {
                            return false;
                        }
                    }
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });

            for (String methodName : new String[]{"onCreate", "onInitializeInterface", "onWindowShown", "updateInputViewShown"}) {
                try {
                    module.hook(imsClass.getDeclaredMethod(methodName)).intercept(chain -> {
                        if (chain.getThisObject() instanceof Context ctx) {
                            adaptImeServiceContextAndWindow(ctx, false);
                        }
                        Object res = chain.proceed();
                        if (chain.getThisObject() instanceof Context ctx) {
                            adaptImeServiceContextAndWindow(ctx, true);
                        }
                        return res;
                    });
                } catch (Throwable ignored) {
                }
            }

            try {
                module.hook(imsClass.getDeclaredMethod(
                        "onConfigurationChanged", android.content.res.Configuration.class)).intercept(chain -> {
                    if (chain.getThisObject() instanceof Context ctx) {
                        Object cfgArg = chain.getArg(0);
                        if (cfgArg instanceof android.content.res.Configuration cfg && isImeOnCoverDisplay(ctx)
                                && ModuleConfig.coverThirdPartyImeEnabled()) {
                            adaptImeConfig(cfg, getDisplayRotation(ctx, true));
                        }
                        adaptImeServiceContextAndWindow(ctx, false);
                    }
                    Object res = chain.proceed();
                    if (chain.getThisObject() instanceof Context ctx) {
                        adaptImeServiceContextAndWindow(ctx, true);
                    }
                    return res;
                });
            } catch (Throwable ignored) {
            }

            try {
                Class<?> editorInfoClass = Class.forName("android.view.inputmethod.EditorInfo");
                module.hook(imsClass.getDeclaredMethod("onStartInputView", editorInfoClass, boolean.class)).intercept(chain -> {
                    if (chain.getThisObject() instanceof Context ctx) {
                        adaptImeServiceContextAndWindow(ctx, false);
                    }
                    Object res = chain.proceed();
                    if (chain.getThisObject() instanceof Context ctx) {
                        adaptImeServiceContextAndWindow(ctx, true);
                    }
                    return res;
                });
            } catch (Throwable ignored) {
            }

            try {
                module.hook(imsClass.getDeclaredMethod(
                        "onConfigureWindow", android.view.Window.class, boolean.class, boolean.class)).intercept(chain -> {
                    Object res = chain.proceed();
                    if (chain.getThisObject() instanceof Context ctx) {
                        adaptImeServiceContextAndWindow(ctx, true);
                    }
                    return res;
                });
            } catch (Throwable ignored) {
            }

            try {
                Class<?> insetsClass = Class.forName("android.inputmethodservice.InputMethodService$Insets");
                java.lang.reflect.Field contentTopField = insetsClass.getField("contentTopInsets");
                java.lang.reflect.Field visibleTopField = insetsClass.getField("visibleTopInsets");
                module.hook(imsClass.getDeclaredMethod("onComputeInsets", insetsClass)).intercept(chain -> {
                    Object res = chain.proceed();
                    try {
                        if (ModuleConfig.coverThirdPartyImeEnabled()
                                && chain.getThisObject() instanceof Context ctx
                                && isImeOnCoverDisplay(ctx)) {
                            Object outInsets = chain.getArg(0);
                            if (outInsets != null) {
                                int rot = getDisplayRotation(ctx, true);
                                int minTop = CoverDisplay.coverImeMinTop(rot);
                                if (minTop > 0) {
                                    int ct = contentTopField.getInt(outInsets);
                                    if (ct > 0 && ct < minTop) contentTopField.setInt(outInsets, minTop);
                                    int vt = visibleTopField.getInt(outInsets);
                                    if (vt > 0 && vt < minTop) visibleTopField.setInt(outInsets, minTop);
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    return res;
                });
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isImeOnCoverDisplay(Context ctx) {
        if (ctx == null) return false;
        try {
            Display d = null;
            try {
                d = ctx.getDisplay();
            } catch (Throwable ignored) {
            }
            if (d == null) {
                DisplayManager dm = ctx.getSystemService(DisplayManager.class);
                if (dm != null) d = dm.getDisplay(Display.DEFAULT_DISPLAY);
            }
            // The display is authoritative. Metrics are only a fallback when no display is attached yet, and the
            // values written by this module (810 px / 340 dpi) are deliberately not treated as evidence.
            if (d != null) return CoverDisplay.isOuterDisplay(d);
            return CoverResourceOverlay.isDefaultDisplayOuter(ctx);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static volatile Object dmgInstance;
    private static volatile Object wmsInstance;
    private static volatile java.lang.reflect.Method wmsGetDefaultDisplayRotationMethod;
    private static volatile int liveImeDisplayRotation = -1;
    private static volatile long liveImeDisplayRotationUptimeMs;
    private static volatile boolean imeDisplayListenerRegistered;
    private static volatile java.lang.reflect.Method wcGetDisplayRotationMethod;
    private static volatile java.lang.reflect.Method wcGetRotationMethod;

    private static int getConfigRotation(android.content.res.Configuration cfg) {
        if (cfg == null) return -1;
        try {
            java.lang.reflect.Field wcField = windowConfigurationField;
            if (wcField == null) {
                wcField = android.content.res.Configuration.class.getDeclaredField("windowConfiguration");
                wcField.setAccessible(true);
                windowConfigurationField = wcField;
            }
            Object wc = wcField.get(cfg);
            if (wc != null) {
                Class<?> wcClass = wc.getClass();
                if (wcGetDisplayRotationMethod == null) {
                    wcGetDisplayRotationMethod = wcClass.getMethod("getDisplayRotation");
                }
                int dr = (Integer) wcGetDisplayRotationMethod.invoke(wc);
                if (dr >= 0) return dr;
                if (wcGetRotationMethod == null) {
                    wcGetRotationMethod = wcClass.getMethod("getRotation");
                }
                int r = (Integer) wcGetRotationMethod.invoke(wc);
                if (r >= 0) return r;
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /**
     * Queries DisplayContent(DEFAULT_DISPLAY).getRotation() directly from WindowManagerService via
     * IWindowManager.getDefaultDisplayRotation(). Unlike DisplayManagerGlobal.getDisplayInfo(0) on HyperOS 4 (whose
     * client-side DisplayInfo cache can report rotation = 0 while DisplayContent(0) is in ROTATION_180), WMS always
     * reflects the true logical rotation (2 in ROTATION_180, 0 in ROTATION_0).
     */
    private static int queryWindowManagerRotation() {
        try {
            Object wms = wmsInstance;
            if (wms == null) {
                wms = Class.forName("android.view.WindowManagerGlobal")
                        .getDeclaredMethod("getWindowManagerService")
                        .invoke(null);
                wmsInstance = wms;
            }
            if (wms != null) {
                java.lang.reflect.Method m = wmsGetDefaultDisplayRotationMethod;
                if (m == null) {
                    m = Class.forName("android.view.IWindowManager")
                            .getMethod("getDefaultDisplayRotation");
                    wmsGetDefaultDisplayRotationMethod = m;
                }
                int r = (Integer) m.invoke(wms);
                if (r >= 0) {
                    liveImeDisplayRotation = r;
                    liveImeDisplayRotationUptimeMs = android.os.SystemClock.uptimeMillis();
                    return r;
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static int getLiveImeDisplayRotation(boolean forceRefresh) {
        long now = android.os.SystemClock.uptimeMillis();
        int cached = liveImeDisplayRotation;
        if (!forceRefresh && cached >= 0 && (now - liveImeDisplayRotationUptimeMs) < 150L) {
            return cached;
        }
        return queryWindowManagerRotation();
    }

    private static int getDisplayRotation(Context ctx) {
        return getDisplayRotation(ctx, false);
    }

    /**
     * Resolves the authoritative cover display rotation, preferring WindowManagerService's live default-display
     * rotation (and Configuration.windowConfiguration.getDisplayRotation() as fallback) so both ROTATION_180 (2) and
     * transitions back to ROTATION_0 (0) are tracked accurately.
     */
    private static int getDisplayRotation(Context ctx, boolean forceRefresh) {
        int wmRot = getLiveImeDisplayRotation(forceRefresh);
        if (wmRot >= 0) return wmRot;
        Context svc = activeImeServiceRef != null ? activeImeServiceRef.get() : null;
        for (Context c : new Context[]{ctx, svc}) {
            if (c == null) continue;
            try {
                int r = getConfigRotation(c.getResources().getConfiguration());
                if (r >= 0) return r;
            } catch (Throwable ignored) {
            }
        }
        try {
            int r = getConfigRotation(Resources.getSystem().getConfiguration());
            if (r >= 0) return r;
        } catch (Throwable ignored) {
        }
        for (Context c : new Context[]{ctx, svc}) {
            if (c == null) continue;
            try {
                Display d = c.getDisplay();
                if (d != null) {
                    int r = d.getRotation();
                    if (r >= 0) return r;
                }
            } catch (Throwable ignored) {
            }
        }
        return imeRotation >= 0 ? imeRotation : android.view.Surface.ROTATION_0;
    }

    private static void registerImeDisplayListener(Context ctx) {
        if (ctx == null || imeDisplayListenerRegistered) return;
        try {
            Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
            DisplayManager dm = appCtx.getSystemService(DisplayManager.class);
            if (dm == null) return;
            WeakReference<Context> fallbackRef = new WeakReference<>(appCtx);
            imeDisplayListenerRegistered = true;
            dm.registerDisplayListener(new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                    if (displayId == Display.DEFAULT_DISPLAY) {
                        onImeDisplayChanged(fallbackRef.get());
                    }
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                    if (displayId == Display.DEFAULT_DISPLAY) {
                        onImeDisplayChanged(fallbackRef.get());
                    }
                }

                @Override
                public void onDisplayChanged(int displayId) {
                    if (displayId == Display.DEFAULT_DISPLAY) {
                        onImeDisplayChanged(fallbackRef.get());
                    }
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (Throwable t) {
            Log.w(TAG, "IME display listener skipped", t);
        }
    }

    private static volatile boolean innerImeSettlementPending;

    private static void scheduleInnerImeSettlement(Context fallbackCtx) {
        if (innerImeSettlementPending) return;
        innerImeSettlementPending = true;
        WeakReference<Context> fallbackRef = fallbackCtx != null ? new WeakReference<>(fallbackCtx) : null;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            innerImeSettlementPending = false;
            try {
                Context svc = activeImeServiceRef != null ? activeImeServiceRef.get() : null;
                Context targetCtx = svc != null ? svc : (fallbackRef != null ? fallbackRef.get() : null);
                if (targetCtx == null || isImeOnCoverDisplay(targetCtx)) return;
                if (svc != null) {
                    adaptImeServiceContextAndWindow(svc, true);
                } else if ("com.tencent.wetype".equals(packageName)) {
                    refreshWeTypeCoverState(targetCtx);
                    if (!imeCoverActive) {
                        syncWeTypeResources(targetCtx);
                        if (weTypeNeedsInnerReset || isWeTypeWidthCacheStaleForInner(targetCtx)) {
                            updateWeTypeCache(targetCtx, 0);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }, 250L);
    }

    private static void onImeDisplayChanged(Context fallbackCtx) {
        try {
            int liveRot = queryWindowManagerRotation();
            Context svc = activeImeServiceRef != null ? activeImeServiceRef.get() : null;
            Context targetCtx = svc != null ? svc : fallbackCtx;
            if (targetCtx == null) return;
            boolean wantCover = ModuleConfig.coverThirdPartyImeEnabled() && isImeOnCoverDisplay(targetCtx);
            if (svc != null) {
                boolean transitioningToInner = !wantCover
                        && (imeCoverActive || lastImeAdaptedWidth > 0 || weTypeNeedsInnerReset);
                if (wantCover != imeCoverActive
                        || (wantCover && liveRot >= 0 && liveRot != imeRotation)
                        || transitioningToInner) {
                    adaptImeServiceContextAndWindow(svc, true);
                    if (transitioningToInner) {
                        scheduleInnerImeSettlement(svc);
                    }
                }
            } else if ("com.tencent.wetype".equals(packageName)) {
                boolean wasCover = imeCoverActive;
                refreshWeTypeCoverState(targetCtx);
                if (imeCoverActive) {
                    syncWeTypeResources(targetCtx);
                    int targetW = CoverDisplay.coverImeWidth(imeRotation);
                    if (lastImeAdaptedWidth != targetW) {
                        updateWeTypeCache(targetCtx, targetW);
                    }
                } else if (wasCover || lastImeAdaptedWidth > 0 || weTypeNeedsInnerReset) {
                    lastImeBoundsRotation = -1;
                    restoreOverrides(false);
                    syncWeTypeResources(targetCtx);
                    if (!isImeOnCoverDisplay(targetCtx)) {
                        updateWeTypeCache(targetCtx, 0);
                        scheduleInnerImeSettlement(targetCtx);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    static int resolveImeRotationForConfig(
            android.content.res.Configuration cfg, int liveDisplayRotation, int currentImeRotation) {
        if (liveDisplayRotation >= 0) {
            return liveDisplayRotation;
        }
        int cfgRot = getConfigRotation(cfg);
        if (cfgRot >= 0) {
            return cfgRot;
        }
        return currentImeRotation >= 0 ? currentImeRotation : android.view.Surface.ROTATION_0;
    }

    private static volatile int imeRotation;
    private static volatile int lastImeBoundsRotation = -1;
    private static volatile boolean imeWindowOverridden;
    private static volatile java.lang.reflect.Field windowConfigurationField;
    private static volatile java.lang.reflect.Method[] windowBoundsSetters;
    private static volatile WeakReference<Context> activeImeServiceRef;
    private static volatile WeakReference<View> lastWeTypeRootViewRef;
    private static volatile WeakReference<View> lastWeTypeAdaptedRootRef;
    private static volatile boolean rebuildingWeType;
    private static final WeakHashMap<View, android.graphics.drawable.Drawable> savedCandidateBackgrounds = new WeakHashMap<>();
    private static final WeakHashMap<View, Integer> savedContainerWidths = new WeakHashMap<>();
    /**
     * Measured top-edge y-offsets (px relative to keyboard top y = 662) every 20 px across x = 0..400 from native
     * Xiaomi SogouIME's cover candidate bar (candidate_flip_bg.png) in ROTATION_180, forming the concave camera
     * avoidance curve below the top-left rear camera lens Rect(0, 0, 398, 728).
     */
    private static final int[] SOGOU_CAMERA_CURVE_OFFSETS = {
            0, 3, 13, 29, 47, 61, 72, 80, 85, 88, 89, 88, 84, 79, 71, 59, 45, 26, 11, 2, 0
    };
    private static android.graphics.Path cachedCoverImeClipPath;
    private static android.graphics.Path cachedCoverImeBorderPath;
    private static int cachedCoverImePathW = -1;
    private static int cachedCoverImePathH = -1;
    private static android.graphics.Paint coverImeTopBorderPaint;
    private static android.graphics.Paint coverImeHeaderFillPaint;

    private static void setWindowBounds(android.content.res.Configuration cfg, Rect bounds) {
        try {
            java.lang.reflect.Field wcField = windowConfigurationField;
            java.lang.reflect.Method[] setters = windowBoundsSetters;
            if (wcField == null || setters == null) {
                wcField = android.content.res.Configuration.class.getDeclaredField("windowConfiguration");
                wcField.setAccessible(true);
                Class<?> wcClass = wcField.getType();
                setters = new java.lang.reflect.Method[]{
                        wcClass.getMethod("setBounds", Rect.class),
                        wcClass.getMethod("setAppBounds", Rect.class),
                        wcClass.getMethod("setMaxBounds", Rect.class)};
                windowConfigurationField = wcField;
                windowBoundsSetters = setters;
            }
            Object wc = wcField.get(cfg);
            if (wc != null) {
                for (java.lang.reflect.Method m : setters) m.invoke(wc, bounds);
            }
        } catch (Throwable ignored) {
        }
    }

    static void adaptImeConfig(android.content.res.Configuration cfg, int rotation) {
        if (cfg == null) return;
        int dpi = CoverDisplay.coverImeDensityDpi(rotation);
        int widthDp = CoverDisplay.coverImeScreenWidthDp(rotation);
        int heightDp = CoverDisplay.coverImeScreenHeightDp(rotation);
        Rect safe = CoverDisplay.coverImeSafeBounds(rotation);
        synchronized (overriddenOriginals) {
            if (hookModule != null && (!imeCoverActive || !ModuleConfig.coverThirdPartyImeEnabled())) {
                return;
            }
            boolean same = cfg.densityDpi == dpi && cfg.screenWidthDp == widthDp && cfg.screenHeightDp == heightDp;
            if (same && lastImeBoundsRotation == rotation) return;
            if (!same) rememberConfig(cfg);
            lastImeBoundsRotation = rotation;
            cfg.densityDpi = dpi;
            cfg.screenWidthDp = widthDp;
            cfg.smallestScreenWidthDp = widthDp;
            cfg.screenHeightDp = heightDp;
            cfg.orientation = android.content.res.Configuration.ORIENTATION_PORTRAIT;
            setWindowBounds(cfg, safe);
        }
    }

    private static void adaptImeServiceContextAndWindow(Context ctx, boolean includeWindow) {
        if (ctx == null) return;
        try {
            activeImeServiceRef = new WeakReference<>(ctx);
            registerImeDisplayListener(ctx);
            boolean wasCover = imeCoverActive;
            int prevAdaptedWidth = lastImeAdaptedWidth;
            boolean enabled = ModuleConfig.coverThirdPartyImeEnabled();
            boolean physicalOuter = isImeOnCoverDisplay(ctx);
            if (physicalOuter) {
                weTypeNeedsInnerReset = true;
            }
            boolean outer = enabled && physicalOuter;
            int rotation = outer ? getDisplayRotation(ctx, true) : android.view.Surface.ROTATION_0;
            imeCoverActive = outer;
            if (outer) {
                imeRotation = rotation;
            }
            if ("com.tencent.wetype".equals(packageName)) {
                ensureWeTypeCoverHooksInstalled(ctx);
            }
            if (!outer) {
                if (wasCover || !overriddenOriginals.isEmpty() || (!physicalOuter && hasCoverSizedWeTypeResources(ctx))) {
                    lastImeBoundsRotation = -1;
                    restoreOverrides(physicalOuter);
                    syncWeTypeResources(ctx);
                }
                if (includeWindow && imeWindowOverridden) {
                    Object softInputWindow = ctx.getClass().getMethod("getWindow").invoke(ctx);
                    if (softInputWindow instanceof android.app.Dialog dialog && dialog.getWindow() != null) {
                        android.view.Window w = dialog.getWindow();
                        android.view.WindowManager.LayoutParams lp = w.getAttributes();
                        if (lp != null) {
                            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                            lp.gravity = android.view.Gravity.BOTTOM;
                            lp.x = 0;
                            lp.layoutInDisplayCutoutMode =
                                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
                            lp.setFitInsetsTypes(lp.getFitInsetsTypes() | WindowInsets.Type.displayCutout());
                            w.setAttributes(lp);
                            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        }
                        imeWindowOverridden = false;
                    }
                }
                if ("com.tencent.wetype".equals(packageName)) {
                    if (wasCover
                            || prevAdaptedWidth > 0
                            || (!physicalOuter && (weTypeNeedsInnerReset || isWeTypeWidthCacheStaleForInner(ctx)))) {
                        updateWeTypeCache(ctx, 0);
                    } else {
                        View curRoot = lastWeTypeRootViewRef != null ? lastWeTypeRootViewRef.get() : null;
                        if (curRoot != null) {
                            applyWeTypeCoverLayoutState(curRoot);
                        }
                    }
                }
                return;
            }
            int targetWidth = CoverDisplay.coverImeWidth(rotation);
            int targetHeight = CoverDisplay.coverImeDisplayHeight(rotation);
            int targetDpi = CoverDisplay.coverImeDensityDpi(rotation);
            for (Resources res : new Resources[]{
                    ctx.getResources(),
                    ctx.getApplicationContext() != null ? ctx.getApplicationContext().getResources() : null,
                    Resources.getSystem()}) {
                if (res == null) continue;
                overrideMetrics(res.getDisplayMetrics(), targetWidth, targetHeight, targetDpi);
                adaptImeConfig(res.getConfiguration(), rotation);
            }
            syncWeTypeResources(ctx);
            if (!includeWindow && "com.tencent.wetype".equals(packageName) && lastImeAdaptedWidth != targetWidth) {
                updateWeTypeCache(ctx, targetWidth);
            }
            if (includeWindow) {
                Object softInputWindow = ctx.getClass().getMethod("getWindow").invoke(ctx);
                if (softInputWindow instanceof android.app.Dialog dialog && dialog.getWindow() != null) {
                    android.view.Window w = dialog.getWindow();
                    android.view.WindowManager.LayoutParams lp = w.getAttributes();
                    int gravity = android.view.Gravity.BOTTOM
                            | (CoverDisplay.coverImeSafeLeft(rotation) > 0
                            ? android.view.Gravity.RIGHT
                            : android.view.Gravity.LEFT);
                    if (lp != null) {
                        int fitTypes = lp.getFitInsetsTypes() & ~WindowInsets.Type.displayCutout();
                        if (lp.width != targetWidth
                                || lp.gravity != gravity
                                || lp.x != 0
                                || lp.layoutInDisplayCutoutMode
                                        != android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                                || lp.getFitInsetsTypes() != fitTypes) {
                            lp.width = targetWidth;
                            lp.gravity = gravity;
                            lp.x = 0;
                            lp.layoutInDisplayCutoutMode =
                                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                            lp.setFitInsetsTypes(fitTypes);
                            w.setAttributes(lp);
                            w.setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
                            imeWindowOverridden = true;
                        }
                    }
                }
                updateWeTypeCache(ctx, targetWidth);
            }
        } catch (Throwable ignored) {
        }
    }

    private static Resources getWeTypeResources(Context ctx, String methodName) {
        if (ctx == null || !"com.tencent.wetype".equals(packageName)) {
            return null;
        }
        try {
            Object res = ctx.getClassLoader().loadClass("e7.u").getDeclaredMethod(methodName).invoke(null);
            if (res instanceof Resources r) return r;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean hasCoverSizedWeTypeResources(Context ctx) {
        if (ctx == null || !"com.tencent.wetype".equals(packageName)) return false;
        try {
            for (Resources res : new Resources[]{
                    ctx.getResources(),
                    Resources.getSystem(),
                    getWeTypeResources(ctx, "j"),
                    getWeTypeResources(ctx, "g")}) {
                if (res != null && isCoverSizedMetrics(res.getDisplayMetrics())) {
                    return true;
                }
            }
            Class<?> b7aClass = ctx.getClassLoader().loadClass("b7.a");
            for (String fieldName : new String[]{"d", "e"}) {
                java.lang.reflect.Field f = b7aClass.getDeclaredField(fieldName);
                f.setAccessible(true);
                if (f.get(null) instanceof android.util.DisplayMetrics staticDm && isCoverSizedMetrics(staticDm)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static volatile long lastInnerStaleResetUptimeMs;
    private static volatile Class<?> cachedWeTypeSelfDrawOClass;
    private static volatile java.lang.reflect.Field cachedWeTypeSelfDrawONField;
    private static volatile java.lang.reflect.Method cachedWeTypeGetKeyboardTypeMethod;
    private static volatile Object cachedWeTypeM1Instance;
    private static volatile java.lang.reflect.Method cachedWeTypeM1I0Method;
    private static volatile java.lang.reflect.Method cachedWeTypeM1YMethod;
    private static volatile WeakReference<View> lastRebuiltStaleKeyboardRef;

    static boolean isFullRowWeTypeSelfDrawKeyboardClass(String className) {
        if (className == null) return false;
        final String prefix = "com.tencent.wetype.plugin.hld.keyboard.selfdraw.S";
        if (!className.startsWith(prefix) || !className.endsWith("Keyboard")) {
            return false;
        }
        return !"com.tencent.wetype.plugin.hld.keyboard.selfdraw.S11EmojiKeyboard".equals(className);
    }

    static boolean isWeTypeLaidOutKeyWidthStale(int laidOutRowWidth, int expectedKeyboardWidth) {
        // Full-row self-drawn keyboards always span at least CoverDisplay.CAMERA_LEFT (~810px minus rounding);
        // row-0 rounding vs m1.I0(t) is <= 24px (including in user one-handed mode where m1.I0(t) itself shrinks),
        // whereas cover <-> inner or ROTATION_0 <-> ROTATION_180 staleness differs by >= 200..414px.
        if (laidOutRowWidth < 720 || expectedKeyboardWidth < 720) {
            return false;
        }
        return Math.abs(expectedKeyboardWidth - laidOutRowWidth) > 96;
    }

    private static boolean ensureWeTypeSelfDrawInspectors(ClassLoader cl) {
        if (cl == null) return false;
        if (cachedWeTypeSelfDrawONField != null
                && cachedWeTypeGetKeyboardTypeMethod != null
                && cachedWeTypeM1Instance != null
                && cachedWeTypeM1I0Method != null
                && cachedWeTypeM1YMethod != null) {
            return true;
        }
        try {
            Class<?> oCls = cl.loadClass("com.tencent.wetype.plugin.hld.keyboard.selfdraw.o");
            java.lang.reflect.Field nField = oCls.getDeclaredField("N");
            nField.setAccessible(true);
            java.lang.reflect.Method getKbType = oCls.getMethod("getKeyboardType");
            Class<?> tCls = cl.loadClass("com.tencent.wetype.plugin.hld.keyboard.t");
            Class<?> m1Cls = cl.loadClass("com.tencent.wetype.plugin.hld.utils.m1");
            Object m1Inst = m1Cls.getDeclaredField("a").get(null);
            if (m1Inst == null) return false;
            java.lang.reflect.Method i0Method = m1Cls.getDeclaredMethod("I0", tCls);
            java.lang.reflect.Method yMethod = m1Cls.getDeclaredMethod("y", boolean.class);
            cachedWeTypeSelfDrawOClass = oCls;
            cachedWeTypeSelfDrawONField = nField;
            cachedWeTypeGetKeyboardTypeMethod = getKbType;
            cachedWeTypeM1Instance = m1Inst;
            cachedWeTypeM1I0Method = i0Method;
            cachedWeTypeM1YMethod = yMethod;
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static View findStaleWeTypeKeyboardInTree(View node, int coverTargetWidth) {
        if (node == null || node.getVisibility() != View.VISIBLE) return null;
        String clsName = node.getClass().getName();
        if (isFullRowWeTypeSelfDrawKeyboardClass(clsName)) {
            if (lastRebuiltStaleKeyboardRef != null && lastRebuiltStaleKeyboardRef.get() == node) {
                return null;
            }
            ClassLoader cl = node.getClass().getClassLoader();
            if (ensureWeTypeSelfDrawInspectors(cl)
                    && cachedWeTypeSelfDrawOClass != null
                    && cachedWeTypeSelfDrawOClass.isInstance(node)) {
                try {
                    int laidOutW = cachedWeTypeSelfDrawONField.getInt(node);
                    if (laidOutW > 0) {
                        if (coverTargetWidth > 0) {
                            Object yObj = cachedWeTypeM1YMethod.invoke(cachedWeTypeM1Instance, false);
                            if (yObj instanceof Integer yw && yw > 0 && Math.abs(yw - coverTargetWidth) > 24) {
                                return node;
                            }
                        }
                        Object kbType = cachedWeTypeGetKeyboardTypeMethod.invoke(node);
                        if (kbType != null) {
                            Object expObj = cachedWeTypeM1I0Method.invoke(cachedWeTypeM1Instance, kbType);
                            int expectedW = expObj instanceof Integer ew && ew > 0 ? ew : coverTargetWidth;
                            if (isWeTypeLaidOutKeyWidthStale(laidOutW, expectedW)) {
                                return node;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            return null;
        }
        if (node instanceof ViewGroup vg) {
            int count = vg.getChildCount();
            for (int i = 0; i < count; i++) {
                View found = findStaleWeTypeKeyboardInTree(vg.getChildAt(i), coverTargetWidth);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean hasStaleWeTypeSelfDrawKeyboardWidth(View rootView, int coverTargetWidth) {
        if (rootView == null
                || rebuildingWeType
                || !"com.tencent.wetype".equals(packageName)
                || !isWeTypeV401(packageVersionCode)) {
            return false;
        }
        View stale = findStaleWeTypeKeyboardInTree(rootView, coverTargetWidth);
        if (stale != null) {
            lastRebuiltStaleKeyboardRef = new WeakReference<>(stale);
            return true;
        }
        return false;
    }

    private static boolean isWeTypeWidthCacheStaleForInner(Context ctx) {
        if (ctx == null
                || rebuildingWeType
                || !"com.tencent.wetype".equals(packageName)
                || !isWeTypeV401(packageVersionCode)) {
            return false;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastInnerStaleResetUptimeMs < 200L) {
            return false;
        }
        try {
            android.util.DisplayMetrics innerReal = queryInnerRealMetrics();
            int expectedMinW = innerReal != null
                    ? Math.min(innerReal.widthPixels, innerReal.heightPixels)
                    : CoverDisplay.WIDTH + 1;
            if (expectedMinW <= CoverDisplay.WIDTH) return false;
            ClassLoader cl = ctx.getClassLoader();
            Class<?> m1Class = cl.loadClass("com.tencent.wetype.plugin.hld.utils.m1");
            Object m1Instance = m1Class.getDeclaredField("a").get(null);
            if (m1Instance == null) return false;
            Object widthObj = m1Class.getDeclaredMethod("y", boolean.class).invoke(m1Instance, false);
            if (widthObj instanceof Integer cachedW && cachedW > 0 && cachedW < expectedMinW) {
                lastInnerStaleResetUptimeMs = now;
                return true;
            }
            View root = lastWeTypeRootViewRef != null ? lastWeTypeRootViewRef.get() : null;
            View staleKb = findStaleWeTypeKeyboardInTree(root, 0);
            if (staleKb == null) {
                try {
                    Class<?> nClass = cl.loadClass("com.tencent.wetype.plugin.hld.model.N");
                    java.lang.reflect.Field hField = nClass.getDeclaredField("h");
                    hField.setAccessible(true);
                    if (hField.get(null) instanceof View modelRoot && modelRoot != root) {
                        staleKb = findStaleWeTypeKeyboardInTree(modelRoot, 0);
                    }
                } catch (Throwable ignored) {
                }
            }
            if (staleKb != null) {
                lastRebuiltStaleKeyboardRef = new WeakReference<>(staleKb);
                lastInnerStaleResetUptimeMs = now;
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Synchronizes both Android's internal ResourcesImpl.mMetrics (used by Resources.getDimensionPixelSize) and
     * WeType's custom MMDensityManager (b7.a) / MMResources (b7.b) DisplayMetrics fields. Note that
     * b7.b.updateConfiguration invokes BOTH wrapped MiuiResources.updateConfiguration (jRes / bWrapped) AND
     * b7.a.C(cfg, dm); we therefore place gRes BEFORE jRes / bWrapped so jRes and bWrapped are sanitized after
     * gRes.updateConfiguration completes, and re-apply overrides to b7.a's fields immediately afterward.
     */
    @SuppressWarnings("deprecation")
    private static void syncWeTypeResources(Context ctx) {
        if (ctx == null || !"com.tencent.wetype".equals(packageName)) {
            return;
        }
        try {
            ClassLoader cl = ctx.getClassLoader();
            Resources gRes = getWeTypeResources(ctx, "g");
            Resources jRes = getWeTypeResources(ctx, "j");
            Resources bWrapped = null;
            if (gRes != null) {
                try {
                    java.lang.reflect.Field bField = gRes.getClass().getDeclaredField("b");
                    bField.setAccessible(true);
                    if (bField.get(gRes) instanceof Resources r) bWrapped = r;
                } catch (Throwable ignored) {
                }
            }
            Resources[] allRes = new Resources[]{
                    ctx.getResources(),
                    ctx.getApplicationContext() != null ? ctx.getApplicationContext().getResources() : null,
                    Resources.getSystem(),
                    gRes,
                    jRes,
                    bWrapped
            };
            if (imeCoverActive) {
                int targetWidth = CoverDisplay.coverImeWidth(imeRotation);
                int targetHeight = CoverDisplay.coverImeDisplayHeight(imeRotation);
                int targetDpi = CoverDisplay.coverImeDensityDpi(imeRotation);
                for (Resources res : allRes) {
                    if (res == null) continue;
                    try {
                        android.util.DisplayMetrics dm = res.getDisplayMetrics();
                        overrideMetrics(dm, targetWidth, targetHeight, targetDpi);
                        android.content.res.Configuration cfg = res.getConfiguration();
                        adaptImeConfig(cfg, imeRotation);
                        res.updateConfiguration(cfg, dm);
                        overrideMetrics(res.getDisplayMetrics(), targetWidth, targetHeight, targetDpi);
                        adaptImeConfig(res.getConfiguration(), imeRotation);
                        if ("b7.b".equals(res.getClass().getName())) {
                            java.lang.reflect.Field dField = res.getClass().getDeclaredField("d");
                            dField.setAccessible(true);
                            Object b7aObj = dField.get(res);
                            if (b7aObj != null) {
                                java.lang.reflect.Field aField = b7aObj.getClass().getDeclaredField("a");
                                aField.setAccessible(true);
                                if (aField.get(b7aObj) instanceof android.util.DisplayMetrics aDm) {
                                    overrideMetrics(aDm, targetWidth, targetHeight, targetDpi);
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    Class<?> b7aClass = cl.loadClass("b7.a");
                    for (String fieldName : new String[]{"d", "e"}) {
                        java.lang.reflect.Field f = b7aClass.getDeclaredField(fieldName);
                        f.setAccessible(true);
                        if (f.get(null) instanceof android.util.DisplayMetrics staticDm) {
                            overrideMetrics(staticDm, targetWidth, targetHeight, targetDpi);
                        }
                    }
                } catch (Throwable ignored) {
                }
            } else {
                android.util.DisplayMetrics innerReal = isImeOnCoverDisplay(ctx) ? null : queryInnerRealMetrics();
                for (Resources res : allRes) {
                    if (res == null) continue;
                    try {
                        android.util.DisplayMetrics dm = res.getDisplayMetrics();
                        android.content.res.Configuration cfg = res.getConfiguration();
                        if (innerReal != null) {
                            if (isCoverSizedMetrics(dm)) {
                                applyInnerRealToMetrics(dm, innerReal);
                            }
                            if (isCoverSizedConfig(cfg)) {
                                applyInnerRealToConfig(cfg, innerReal);
                            }
                        }
                        res.updateConfiguration(cfg, dm);
                        if (innerReal != null) {
                            if (isCoverSizedMetrics(res.getDisplayMetrics())) {
                                applyInnerRealToMetrics(res.getDisplayMetrics(), innerReal);
                            }
                            if (isCoverSizedConfig(res.getConfiguration())) {
                                applyInnerRealToConfig(res.getConfiguration(), innerReal);
                            }
                            if ("b7.b".equals(res.getClass().getName())) {
                                java.lang.reflect.Field dField = res.getClass().getDeclaredField("d");
                                dField.setAccessible(true);
                                Object b7aObj = dField.get(res);
                                if (b7aObj != null) {
                                    java.lang.reflect.Field aField = b7aObj.getClass().getDeclaredField("a");
                                    aField.setAccessible(true);
                                    if (aField.get(b7aObj) instanceof android.util.DisplayMetrics aDm
                                            && isCoverSizedMetrics(aDm)) {
                                        applyInnerRealToMetrics(aDm, innerReal);
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
                if (innerReal != null) {
                    try {
                        Class<?> b7aClass = cl.loadClass("b7.a");
                        for (String fieldName : new String[]{"d", "e"}) {
                            java.lang.reflect.Field f = b7aClass.getDeclaredField(fieldName);
                            f.setAccessible(true);
                            if (f.get(null) instanceof android.util.DisplayMetrics staticDm
                                    && isCoverSizedMetrics(staticDm)) {
                                applyInnerRealToMetrics(staticDm, innerReal);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void updateWeTypeCache(Context ctx, int targetWidth) {
        if (!"com.tencent.wetype".equals(packageName) || rebuildingWeType) return;
        View curRoot = lastWeTypeRootViewRef != null ? lastWeTypeRootViewRef.get() : null;
        boolean forceInnerReset = targetWidth <= 0
                && (weTypeNeedsInnerReset || isWeTypeWidthCacheStaleForInner(ctx));
        boolean staleCoverKeyboard = targetWidth > 0
                && curRoot != null
                && hasStaleWeTypeSelfDrawKeyboardWidth(curRoot, targetWidth);
        boolean widthChanged = lastImeAdaptedWidth != targetWidth || forceInnerReset || staleCoverKeyboard;
        boolean rootChanged = targetWidth > 0
                && curRoot != null
                && (lastWeTypeAdaptedRootRef == null || lastWeTypeAdaptedRootRef.get() != curRoot);
        if (!widthChanged && !rootChanged) {
            if (curRoot != null) {
                applyWeTypeCoverLayoutState(curRoot);
            }
            return;
        }
        lastImeAdaptedWidth = Math.max(targetWidth, 0);
        if (targetWidth <= 0) {
            lastWeTypeAdaptedRootRef = null;
        } else if (curRoot != null) {
            lastWeTypeAdaptedRootRef = new WeakReference<>(curRoot);
        }
        invalidateWeTypeCachedDimensions(ctx, ctx.getResources().getConfiguration(), curRoot != null);
        View updatedRoot = lastWeTypeRootViewRef != null ? lastWeTypeRootViewRef.get() : curRoot;
        if (updatedRoot != null) {
            applyWeTypeCoverLayoutState(updatedRoot);
            updatedRoot.requestLayout();
            updatedRoot.invalidate();
        }
    }

    private static final java.util.Set<ClassLoader> hookedWeTypeClassLoaders =
            java.util.Collections.newSetFromMap(new WeakHashMap<>());
    private static volatile boolean weTypeViewHooksInstalled;

    private static void ensureWeTypeCoverHooksInstalled(Context c) {
        if (c == null || hookModule == null || !"com.tencent.wetype".equals(packageName)) return;
        ClassLoader cl = c.getClassLoader();
        if (cl != null) {
            boolean shouldInstall;
            synchronized (hookedWeTypeClassLoaders) {
                shouldInstall = hookedWeTypeClassLoaders.add(cl);
            }
            if (shouldInstall) {
                installWeTypeCoverHooks(hookModule, cl);
            }
        }
    }

    private static void ensureWeTypeCoverHooks(Context c) {
        if (c == null || hookModule == null || !"com.tencent.wetype".equals(packageName)) return;
        refreshWeTypeCoverState(c);
        if (imeCoverActive) {
            syncWeTypeResources(c);
        }
        ensureWeTypeCoverHooksInstalled(c);
    }

    private static void refreshWeTypeCoverState(Context ctx) {
        if (ctx == null || !"com.tencent.wetype".equals(packageName)) return;
        try {
            boolean enabled = ModuleConfig.coverThirdPartyImeEnabled();
            boolean physicalOuter = isImeOnCoverDisplay(ctx);
            if (physicalOuter) {
                weTypeNeedsInnerReset = true;
            }
            boolean outer = enabled && physicalOuter;
            int rot = outer ? getDisplayRotation(ctx, true) : android.view.Surface.ROTATION_0;
            if (outer && (!imeCoverActive || imeRotation != rot)) {
                lastImeAdaptedWidth = 0;
            }
            imeCoverActive = outer;
            if (outer) {
                imeRotation = rot;
            }
        } catch (Throwable ignored) {
        }
    }

    private static void adjustWeTypeHeightModel(
            Object q, java.lang.reflect.Field dField, java.lang.reflect.Field eField) {
        if (q == null || !imeCoverActive) return;
        try {
            eField.setInt(q, CoverDisplay.IME_BOTTOM_CHIN_PX);
            if (imeRotation == android.view.Surface.ROTATION_180) {
                dField.setInt(q, CoverDisplay.IME_TARGET_HEIGHT_PX - CoverDisplay.IME_BOTTOM_CHIN_PX);
            }
        } catch (Throwable ignored) {
        }
    }

    static View weTypeResetInputViewArg(long versionCode, ViewGroup container, View rootView) {
        // WeType 4.0.1 (59xxx) refactored WxHldService.Q(View) so its parameter is ignoreView
        // (returning early without attaching when targetChildView == ignoreView); 4.0.0 (58xxx) passed rv directly.
        return isWeTypeV401(versionCode) ? container : rootView;
    }

    /**
     * WeChat Input Method caches keyboard dimensions in the obfuscated singleton utils.m1 and pre-builds its
     * ImeRootView / ImeCandidateView / keyboard views in model.N. The member names were verified on WeType 4.0.0
     * (58xxx) and 4.0.1 (59xxx); other versions skip direct m1 manipulation.
     */
    private static void invalidateWeTypeCachedDimensions(
            Context ctx, android.content.res.Configuration cfg, boolean viewReady) {
        if (!isSupportedWeTypeM1Version(packageVersionCode) || rebuildingWeType) return;
        rebuildingWeType = true;
        try {
            ensureWeTypeCoverHooksInstalled(ctx);
            ClassLoader cl = ctx.getClassLoader();
            Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
            syncWeTypeResources(ctx);
            if (imeCoverActive && cfg != null) {
                adaptImeConfig(cfg, imeRotation);
            } else if (!imeCoverActive && cfg != null && !isImeOnCoverDisplay(ctx) && isCoverSizedConfig(cfg)) {
                applyInnerRealToConfig(cfg, queryInnerRealMetrics());
            }
            Class<?> m1Class = cl.loadClass("com.tencent.wetype.plugin.hld.utils.m1");
            Object m1Instance = m1Class.getDeclaredField("a").get(null);
            if (m1Instance == null || m1Instance.getClass() != m1Class) return;
            if (isWeTypeV401(packageVersionCode)) {
                try {
                    java.lang.reflect.Method resetFoldState = m1Class.getDeclaredMethod("e2", boolean.class);
                    resetFoldState.setAccessible(true);
                    resetFoldState.invoke(m1Instance, false);
                } catch (Throwable ignored) {
                }
            }
            if (cfg != null) {
                try {
                    java.lang.reflect.Field rField = m1Class.getDeclaredField("r");
                    rField.setAccessible(true);
                    rField.setInt(null, cfg.densityDpi);
                } catch (Throwable ignored) {
                }
            }
            String updateResLayoutName = weTypeUpdateResourceLayoutMethod(packageVersionCode);
            try {
                m1Class.getDeclaredMethod(updateResLayoutName, Context.class, android.content.res.Configuration.class)
                        .invoke(m1Instance, appCtx, cfg);
            } catch (Throwable ignored) {
            }
            String modeFlowName = weTypeModeFlowMethod(packageVersionCode);
            try {
                java.lang.reflect.Method modeFlowMethod = m1Class.getDeclaredMethod(modeFlowName);
                modeFlowMethod.setAccessible(true);
                modeFlowMethod.invoke(m1Instance);
            } catch (Throwable ignored) {
            }
            syncWeTypeResources(ctx);
            Class<?> nClass = null;
            java.lang.reflect.Field hField = null;
            Object existingRoot = null;
            try {
                nClass = cl.loadClass("com.tencent.wetype.plugin.hld.model.N");
                hField = nClass.getDeclaredField("h");
                hField.setAccessible(true);
                existingRoot = hField.get(null);
            } catch (Throwable ignored) {
            }
            String rebuildAllName = weTypeRebuildAllMethod(packageVersionCode);
            String resetDataCacheName = weTypeResetDataCacheMethod(packageVersionCode);
            if (viewReady || existingRoot != null) {
                boolean rebuiltAll = false;
                try {
                    java.lang.reflect.Method rebuildAll = m1Class.getDeclaredMethod(
                            rebuildAllName, Context.class, boolean.class, boolean.class, boolean.class, boolean.class);
                    rebuildAll.setAccessible(true);
                    rebuildAll.invoke(m1Instance, appCtx, true, true, false, false);
                    rebuiltAll = true;
                } catch (Throwable ignored) {
                }
                if (!rebuiltAll) {
                    try {
                        m1Class.getDeclaredMethod(resetDataCacheName, Context.class, boolean.class)
                                .invoke(m1Instance, appCtx, false);
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    if (hField != null) {
                        Object newRoot = hField.get(null);
                        if (newRoot == null && nClass != null) {
                            Object nInstance = nClass.getDeclaredField("a").get(null);
                            if (nInstance != null) {
                                newRoot = nClass.getDeclaredMethod("C0").invoke(nInstance);
                            }
                        }
                        if (newRoot instanceof View rv) {
                            lastWeTypeRootViewRef = new WeakReference<>(rv);
                            if (imeCoverActive) {
                                lastWeTypeAdaptedRootRef = new WeakReference<>(rv);
                            }
                            Context svc = activeImeServiceRef != null ? activeImeServiceRef.get() : ctx;
                            if (svc != null && "com.tencent.wetype.plugin.hld.WxHldService".equals(svc.getClass().getName())) {
                                java.lang.reflect.Field aField = svc.getClass().getDeclaredField("A");
                                aField.setAccessible(true);
                                if (aField.get(svc) instanceof ViewGroup container) {
                                    if (rv.getParent() != container) {
                                        java.lang.reflect.Method qMethod = svc.getClass().getDeclaredMethod("Q", View.class);
                                        qMethod.setAccessible(true);
                                        View qArg = weTypeResetInputViewArg(packageVersionCode, container, rv);
                                        qMethod.invoke(svc, qArg);
                                        if (rv.getParent() != container && qArg != container) {
                                            qMethod.invoke(svc, container);
                                        }
                                    }
                                    java.lang.reflect.Field wField = svc.getClass().getDeclaredField("w");
                                    wField.setAccessible(true);
                                    Object proxy = wField.get(svc);
                                    if (proxy != null) {
                                        int targetH = (Integer) proxy.getClass().getMethod("a").invoke(proxy);
                                        ViewGroup.LayoutParams lp = rv.getLayoutParams();
                                        if (lp != null && targetH > 0 && lp.height != targetH) {
                                            lp.height = targetH;
                                            rv.setLayoutParams(lp);
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            } else {
                try {
                    m1Class.getDeclaredMethod(resetDataCacheName, Context.class, boolean.class)
                            .invoke(m1Instance, appCtx, false);
                } catch (Throwable ignored) {
                }
            }
            if (isWeTypeV401(packageVersionCode)) {
                try {
                    java.lang.reflect.Method syncLastFields = m1Class.getDeclaredMethod("i2");
                    syncLastFields.setAccessible(true);
                    syncLastFields.invoke(m1Instance);
                } catch (Throwable ignored) {
                }
            }
            if (!imeCoverActive && !isImeOnCoverDisplay(ctx)) {
                weTypeNeedsInnerReset = false;
            }
        } catch (Throwable ignored) {
        } finally {
            rebuildingWeType = false;
        }
    }

    private static void installWeTypeCoverHooks(XposedModule module, ClassLoader cl) {
        try {
            if (cl != null) {
                try {
                    Class<?> b7bClass = cl.loadClass("b7.b");
                    module.hook(b7bClass.getDeclaredMethod("getDisplayMetrics")).intercept(chain -> {
                        Object res = chain.proceed();
                        try {
                            if (imeCoverActive) {
                                if (isLiveDefaultDisplayInner()) {
                                    imeCoverActive = false;
                                    lastImeBoundsRotation = -1;
                                    restoreOverrides(false);
                                    if (res instanceof android.util.DisplayMetrics dm && isCoverSizedMetrics(dm)) {
                                        applyInnerRealToMetrics(dm, queryInnerRealMetrics());
                                    }
                                    return res;
                                }
                                if (res instanceof android.util.DisplayMetrics dm) {
                                    int rot = getDisplayRotation(null);
                                    imeRotation = rot;
                                    overrideMetrics(dm,
                                            CoverDisplay.coverImeWidth(rot),
                                            CoverDisplay.coverImeDisplayHeight(rot),
                                            CoverDisplay.coverImeDensityDpi(rot));
                                }
                            } else if (res instanceof android.util.DisplayMetrics dm
                                    && isCoverSizedMetrics(dm)
                                    && isLiveDefaultDisplayInner()) {
                                applyInnerRealToMetrics(dm, queryInnerRealMetrics());
                            }
                        } catch (Throwable ignored) {
                        }
                        return res;
                    });
                } catch (Throwable ignored) {
                }

                try {
                    Class<?> appLikeClass = cl.loadClass("com.tencent.wetype.HldApplicationLike");
                    java.lang.reflect.Method getAppMethod = appLikeClass.getMethod("getApplication");
                    module.hook(appLikeClass.getDeclaredMethod("onCreate")).intercept(chain -> {
                        try {
                            Object self = chain.getThisObject();
                            if (self != null && getAppMethod.invoke(self) instanceof Context appCtx) {
                                refreshWeTypeCoverState(appCtx);
                                if (imeCoverActive) {
                                    syncWeTypeResources(appCtx);
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    });
                    module.hook(appLikeClass.getDeclaredMethod(
                            "onConfigurationChanged", android.content.res.Configuration.class)).intercept(chain -> {
                        Context appCtx = null;
                        boolean wasCover = imeCoverActive;
                        try {
                            Object self = chain.getThisObject();
                            appCtx = self != null && getAppMethod.invoke(self) instanceof Context c ? c : null;
                            if (appCtx != null) {
                                refreshWeTypeCoverState(appCtx);
                            }
                            Context svc = activeImeServiceRef != null ? activeImeServiceRef.get() : null;
                            Context syncCtx = svc != null ? svc : appCtx;
                            if (imeCoverActive) {
                                if (chain.getArg(0) instanceof android.content.res.Configuration cfg) {
                                    adaptImeConfig(cfg, imeRotation);
                                }
                                if (syncCtx != null) syncWeTypeResources(syncCtx);
                                int targetW = CoverDisplay.coverImeWidth(imeRotation);
                                if (lastImeAdaptedWidth != targetW) {
                                    if (svc != null) {
                                        adaptImeServiceContextAndWindow(svc, true);
                                    } else if (appCtx != null) {
                                        updateWeTypeCache(appCtx, targetW);
                                    }
                                }
                                if (lastImeAdaptedWidth == targetW) {
                                    // Prevent HldApplicationLike.onConfigurationChanged -> m1.C2/A2 from calling
                                    // requestHideSelf(0) and closing the active cover keyboard mid-input or mid-rotation.
                                    return null;
                                }
                            } else {
                                if (wasCover || !overriddenOriginals.isEmpty()
                                        || (syncCtx != null && !isImeOnCoverDisplay(syncCtx) && hasCoverSizedWeTypeResources(syncCtx))) {
                                    lastImeBoundsRotation = -1;
                                    restoreOverrides(false);
                                    if (syncCtx != null) syncWeTypeResources(syncCtx);
                                }
                                if (chain.getArg(0) instanceof android.content.res.Configuration cfg
                                        && syncCtx != null
                                        && !isImeOnCoverDisplay(syncCtx)
                                        && isCoverSizedConfig(cfg)) {
                                    applyInnerRealToConfig(cfg, queryInnerRealMetrics());
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                        Object res = chain.proceed();
                        try {
                            Context svc = activeImeServiceRef != null ? activeImeServiceRef.get() : null;
                            if (imeCoverActive) {
                                int targetW = CoverDisplay.coverImeWidth(imeRotation);
                                if (svc != null && lastImeAdaptedWidth != targetW) {
                                    adaptImeServiceContextAndWindow(svc, true);
                                } else if (appCtx != null && lastImeAdaptedWidth != targetW) {
                                    updateWeTypeCache(appCtx, targetW);
                                }
                            } else {
                                if (svc != null
                                        && (wasCover
                                                || lastImeAdaptedWidth > 0
                                                || weTypeNeedsInnerReset
                                                || isWeTypeWidthCacheStaleForInner(svc))) {
                                    adaptImeServiceContextAndWindow(svc, true);
                                } else if (appCtx != null
                                        && !isImeOnCoverDisplay(appCtx)
                                        && (wasCover
                                                || lastImeAdaptedWidth > 0
                                                || weTypeNeedsInnerReset
                                                || isWeTypeWidthCacheStaleForInner(appCtx))) {
                                    updateWeTypeCache(appCtx, 0);
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                        return res;
                    });
                } catch (Throwable ignored) {
                }

                if (isSupportedWeTypeM1Version(packageVersionCode)) {
                    try {
                        Class<?> m1Class = cl.loadClass("com.tencent.wetype.plugin.hld.utils.m1");
                        String updateResLayoutName = weTypeUpdateResourceLayoutMethod(packageVersionCode);
                        module.hook(m1Class.getDeclaredMethod(
                                updateResLayoutName, Context.class, android.content.res.Configuration.class)).intercept(chain -> {
                            Context ctxArg = chain.getArg(0) instanceof Context c ? c : null;
                            try {
                                if (ctxArg != null && activeImeServiceRef == null) {
                                    refreshWeTypeCoverState(ctxArg);
                                }
                                if (imeCoverActive) {
                                    if (chain.getArg(1) instanceof android.content.res.Configuration cfg) {
                                        adaptImeConfig(cfg, imeRotation);
                                    }
                                    if (ctxArg != null) syncWeTypeResources(ctxArg);
                                } else if (ctxArg != null && !isImeOnCoverDisplay(ctxArg)) {
                                    if (chain.getArg(1) instanceof android.content.res.Configuration cfg
                                            && isCoverSizedConfig(cfg)) {
                                        applyInnerRealToConfig(cfg, queryInnerRealMetrics());
                                    }
                                    if (hasCoverSizedWeTypeResources(ctxArg)) {
                                        syncWeTypeResources(ctxArg);
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                            Object res = chain.proceed();
                            try {
                                if (ctxArg != null) {
                                    syncWeTypeResources(ctxArg);
                                }
                            } catch (Throwable ignored) {
                            }
                            return res;
                        });
                        final double coverHeightScale = CoverDisplay.IME_TARGET_CANDIDATE_HEIGHT_PX / 160.0d;
                        final int coverBodyHeight = CoverDisplay.IME_TARGET_HEIGHT_PX - CoverDisplay.IME_BOTTOM_CHIN_PX;
                        module.hook(m1Class.getDeclaredMethod("s0")).intercept(chain -> {
                            if (imeCoverActive && imeRotation == android.view.Surface.ROTATION_180) {
                                return coverHeightScale;
                            }
                            return chain.proceed();
                        });
                        if (isWeTypeV401(packageVersionCode)) {
                            try {
                                module.hook(m1Class.getDeclaredMethod("g1")).intercept(chain -> {
                                    if (imeCoverActive && imeRotation == android.view.Surface.ROTATION_180) {
                                        return coverHeightScale;
                                    }
                                    return chain.proceed();
                                });
                            } catch (Throwable ignored) {
                            }
                        }
                        final Set<Class<?>> heightScaleCacheClasses =
                                Collections.newSetFromMap(new ConcurrentHashMap<>());
                        if (isWeTypeV401(packageVersionCode)) {
                            for (String fieldName : new String[]{"D", "E"}) {
                                try {
                                    java.lang.reflect.Field f = m1Class.getDeclaredField(fieldName);
                                    f.setAccessible(true);
                                    Object item = f.get(null);
                                    if (item != null) {
                                        heightScaleCacheClasses.add(item.getClass());
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                        } else {
                            try {
                                heightScaleCacheClasses.add(cl.loadClass("com.tencent.wetype.plugin.hld.utils.m1$X"));
                            } catch (Throwable ignored) {
                            }
                        }
                        for (Class<?> scaleCls : heightScaleCacheClasses) {
                            try {
                                module.hook(scaleCls.getDeclaredMethod("c")).intercept(chain -> {
                                    if (imeCoverActive && imeRotation == android.view.Surface.ROTATION_180) {
                                        return coverHeightScale;
                                    }
                                    return chain.proceed();
                                });
                            } catch (Throwable ignored) {
                            }
                        }
                        try {
                            Class<?> m1f0Class = cl.loadClass("com.tencent.wetype.plugin.hld.utils.m1$f0");
                            module.hook(m1f0Class.getDeclaredMethod("get")).intercept(chain -> {
                                Object self = chain.getThisObject();
                                if (imeCoverActive
                                        && imeRotation == android.view.Surface.ROTATION_180
                                        && self != null
                                        && heightScaleCacheClasses.contains(self.getClass())) {
                                    return coverHeightScale;
                                }
                                return chain.proceed();
                            });
                        } catch (Throwable ignored) {
                        }
                        try {
                            Class<?> selfDrawOClass = cl.loadClass("com.tencent.wetype.plugin.hld.keyboard.selfdraw.o");
                            String selfDrawScaleMethod = weTypeSelfDrawScaleMethod(packageVersionCode);
                            module.hook(selfDrawOClass.getDeclaredMethod(selfDrawScaleMethod, Float.class)).intercept(chain -> {
                                if (imeCoverActive && imeRotation == android.view.Surface.ROTATION_180) {
                                    return chain.getArg(0) instanceof Float f
                                            ? (float) (f * coverHeightScale)
                                            : 0f;
                                }
                                return chain.proceed();
                            });
                        } catch (Throwable ignored) {
                        }

                        Class<?> tClass = cl.loadClass("com.tencent.wetype.plugin.hld.keyboard.t");
                        module.hook(m1Class.getDeclaredMethod("R", tClass)).intercept(chain -> {
                            if (imeCoverActive && imeRotation == android.view.Surface.ROTATION_180) {
                                return CoverDisplay.IME_TARGET_CANDIDATE_HEIGHT_PX;
                            }
                            return chain.proceed();
                        });
                        try {
                            module.hook(m1Class.getDeclaredMethod("S", m1Class, tClass, int.class, Object.class)).intercept(chain -> {
                                if (imeCoverActive && imeRotation == android.view.Surface.ROTATION_180) {
                                    return CoverDisplay.IME_TARGET_CANDIDATE_HEIGHT_PX;
                                }
                                return chain.proceed();
                            });
                        } catch (Throwable ignored) {
                        }
                        try {
                            Class<?> m1cClass = cl.loadClass("com.tencent.wetype.plugin.hld.utils.m1$c");
                            module.hook(m1cClass.getDeclaredMethod("a", tClass)).intercept(chain -> {
                                if (imeCoverActive && imeRotation == android.view.Surface.ROTATION_180) {
                                    return CoverDisplay.IME_TARGET_CANDIDATE_HEIGHT_PX;
                                }
                                return chain.proceed();
                            });
                        } catch (Throwable ignored) {
                        }

                        for (String methodName : new String[]{
                                "D0", weTypeDefaultBodyHeightMethod(packageVersionCode)}) {
                            try {
                                module.hook(m1Class.getDeclaredMethod(methodName, tClass)).intercept(chain -> {
                                    if (imeCoverActive && imeRotation == android.view.Surface.ROTATION_180) {
                                        return coverBodyHeight;
                                    }
                                    return chain.proceed();
                                });
                            } catch (Throwable ignored) {
                            }
                        }
                        String[] syntheticBodyHeightMethods = isWeTypeV401(packageVersionCode)
                                ? new String[]{"E0"}
                                : new String[]{"E0", "k0"};
                        for (String methodName : syntheticBodyHeightMethods) {
                            try {
                                module.hook(m1Class.getDeclaredMethod(methodName, m1Class, tClass, int.class, Object.class)).intercept(chain -> {
                                    if (imeCoverActive && imeRotation == android.view.Surface.ROTATION_180) {
                                        return coverBodyHeight;
                                    }
                                    return chain.proceed();
                                });
                            } catch (Throwable ignored) {
                            }
                        }
                        for (String subClsName : new String[]{
                                "com.tencent.wetype.plugin.hld.utils.m1$J",
                                "com.tencent.wetype.plugin.hld.utils.m1$n"}) {
                            try {
                                Class<?> subCls = cl.loadClass(subClsName);
                                module.hook(subCls.getDeclaredMethod("a", tClass)).intercept(chain -> {
                                    if (imeCoverActive && imeRotation == android.view.Surface.ROTATION_180) {
                                        return coverBodyHeight;
                                    }
                                    return chain.proceed();
                                });
                            } catch (Throwable ignored) {
                            }
                        }

                        Class<?> i1Class = cl.loadClass("com.tencent.wetype.plugin.hld.utils.i1");
                        Class<?> a7bClass = cl.loadClass("a7.b");
                        java.lang.reflect.Field dField = a7bClass.getDeclaredField("d");
                        java.lang.reflect.Field eField = a7bClass.getDeclaredField("e");
                        dField.setAccessible(true);
                        eField.setAccessible(true);
                        module.hook(i1Class.getDeclaredMethod("W0", tClass)).intercept(chain -> {
                            Object q = chain.proceed();
                            adjustWeTypeHeightModel(q, dField, eField);
                            return q;
                        });
                        try {
                            module.hook(i1Class.getDeclaredMethod("Y0", tClass, boolean.class)).intercept(chain -> {
                                Object q = chain.proceed();
                                adjustWeTypeHeightModel(q, dField, eField);
                                return q;
                            });
                        } catch (Throwable ignored) {
                        }
                        try {
                            module.hook(m1Class.getDeclaredMethod("G0", tClass)).intercept(chain -> {
                                Object q = chain.proceed();
                                adjustWeTypeHeightModel(q, dField, eField);
                                return q;
                            });
                        } catch (Throwable ignored) {
                        }
                        try {
                            module.hook(m1Class.getDeclaredMethod("H0", m1Class, tClass, int.class, Object.class)).intercept(chain -> {
                                Object q = chain.proceed();
                                adjustWeTypeHeightModel(q, dField, eField);
                                return q;
                            });
                        } catch (Throwable ignored) {
                        }
                        try {
                            Class<?> m1LClass = cl.loadClass("com.tencent.wetype.plugin.hld.utils.m1$L");
                            module.hook(m1LClass.getDeclaredMethod("a", tClass)).intercept(chain -> {
                                Object q = chain.proceed();
                                adjustWeTypeHeightModel(q, dField, eField);
                                return q;
                            });
                        } catch (Throwable ignored) {
                        }
                        ModLog.i(TAG, "installed WeType classLoader hooks cl=" + cl + " ver=" + packageVersionCode);
                    } catch (Throwable t) {
                        Log.w(TAG, "WeType m1 hooks failed for cl=" + cl, t);
                    }
                }
            }

            if (weTypeViewHooksInstalled) return;
            weTypeViewHooksInstalled = true;
            module.hook(android.widget.FrameLayout.class.getDeclaredMethod("onMeasure", int.class, int.class)).intercept(chain -> {
                try {
                    Object self = chain.getThisObject();
                    if (self instanceof View v) {
                        String clsName = v.getClass().getName();
                        boolean isImeRoot = "com.tencent.wetype.plugin.hld.view.ImeRootView".equals(clsName);
                        boolean isDecor = imeProcess && "com.android.internal.policy.DecorView".equals(clsName);
                        if (isDecor && imeCoverActive) {
                            Context svc = activeImeServiceRef != null ? activeImeServiceRef.get() : null;
                            Context targetCtx = svc != null ? svc : v.getContext();
                            if (isLiveDefaultDisplayInner()) {
                                adaptImeServiceContextAndWindow(targetCtx, true);
                            } else {
                                int liveRot = getDisplayRotation(targetCtx, true);
                                if (liveRot != imeRotation) {
                                    adaptImeServiceContextAndWindow(targetCtx, true);
                                }
                                View curRoot = lastWeTypeRootViewRef != null ? lastWeTypeRootViewRef.get() : null;
                                if (curRoot != null) {
                                    applyWeTypeCoverLayoutState(curRoot);
                                }
                                if (imeCoverActive) {
                                    int targetW = CoverDisplay.coverImeWidth(imeRotation);
                                    int wSpec = (Integer) chain.getArg(0);
                                    if (View.MeasureSpec.getSize(wSpec) != targetW) {
                                        int newWSpec = View.MeasureSpec.makeMeasureSpec(targetW, View.MeasureSpec.EXACTLY);
                                        return chain.proceed(new Object[]{newWSpec, chain.getArg(1)});
                                    }
                                }
                            }
                        } else if (isImeRoot) {
                            lastWeTypeRootViewRef = new WeakReference<>(v);
                            Context svc = activeImeServiceRef != null ? activeImeServiceRef.get() : null;
                            Context targetCtx = svc != null ? svc : v.getContext();
                            if (imeCoverActive && isLiveDefaultDisplayInner()) {
                                adaptImeServiceContextAndWindow(targetCtx, true);
                            } else if (imeCoverActive) {
                                int liveRot = getDisplayRotation(targetCtx, true);
                                if (liveRot != imeRotation) {
                                    adaptImeServiceContextAndWindow(targetCtx, true);
                                }
                            }
                            if (imeCoverActive
                                    && (lastWeTypeAdaptedRootRef == null
                                            || lastWeTypeAdaptedRootRef.get() != v
                                            || lastImeAdaptedWidth != CoverDisplay.coverImeWidth(imeRotation)
                                            || hasStaleWeTypeSelfDrawKeyboardWidth(
                                                    v, CoverDisplay.coverImeWidth(imeRotation)))) {
                                updateWeTypeCache(targetCtx, CoverDisplay.coverImeWidth(imeRotation));
                            } else if (!imeCoverActive
                                    && (lastImeAdaptedWidth > 0
                                            || weTypeNeedsInnerReset
                                            || (!isImeOnCoverDisplay(targetCtx) && isWeTypeWidthCacheStaleForInner(targetCtx)))) {
                                updateWeTypeCache(targetCtx, 0);
                            }
                            applyWeTypeCoverLayoutState(v);
                            if (imeCoverActive) {
                                int targetW = CoverDisplay.coverImeWidth(imeRotation);
                                int wSpec = (Integer) chain.getArg(0);
                                int hSpec = (Integer) chain.getArg(1);
                                int newWSpec = View.MeasureSpec.makeMeasureSpec(targetW, View.MeasureSpec.EXACTLY);
                                int newHSpec = hSpec;
                                ViewGroup.LayoutParams rlp = v.getLayoutParams();
                                if (rlp != null && rlp.height > 0 && View.MeasureSpec.getSize(hSpec) != rlp.height) {
                                    newHSpec = View.MeasureSpec.makeMeasureSpec(rlp.height, View.MeasureSpec.EXACTLY);
                                }
                                if (newWSpec != wSpec || newHSpec != hSpec) {
                                    return chain.proceed(new Object[]{newWSpec, newHSpec});
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });

            module.hook(ViewGroup.class.getDeclaredMethod("dispatchDraw", android.graphics.Canvas.class)).intercept(chain -> {
                Object self = chain.getThisObject();
                if (self instanceof View v
                        && "com.tencent.wetype.plugin.hld.view.ImeRootView".equals(v.getClass().getName())
                        && chain.getArg(0) instanceof android.graphics.Canvas canvas) {
                    try {
                        lastWeTypeRootViewRef = new WeakReference<>(v);
                        if (imeCoverActive && isLiveDefaultDisplayInner()) {
                            imeCoverActive = false;
                            lastImeBoundsRotation = -1;
                            restoreOverrides(false);
                        }
                        applyWeTypeCoverLayoutState(v);
                        if (imeCoverActive) {
                            int w = v.getWidth();
                            int h = v.getHeight();
                            if (w > 0 && h > 0) {
                                boolean curveLeft = imeRotation == android.view.Surface.ROTATION_180;
                                int saveCount = canvas.save();
                                if (curveLeft) {
                                    ensureCoverImePaths(w, h);
                                    canvas.clipPath(cachedCoverImeClipPath);
                                }
                                drawWeTypeUnifiedHeaderBackground(canvas, v, w);
                                chain.proceed();
                                if (curveLeft && cachedCoverImeBorderPath != null && coverImeTopBorderPaint != null) {
                                    canvas.drawPath(cachedCoverImeBorderPath, coverImeTopBorderPaint);
                                } else if (!curveLeft && coverImeTopBorderPaint != null) {
                                    canvas.drawLine(0f, 0.75f, w, 0.75f, coverImeTopBorderPaint);
                                }
                                canvas.restoreToCount(saveCount);
                                return null;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            Log.w(TAG, "WeType cover hooks skipped", t);
        }
    }

    private static void applyWeTypeCoverLayoutState(View rootView) {
        if (!(rootView instanceof ViewGroup root) || root.getChildCount() < 2) return;
        try {
            View upper = null;
            if (root.getChildAt(0) instanceof ViewGroup bgHolder && bgHolder.getChildCount() >= 2) {
                upper = bgHolder.getChildAt(0);
            }
            ViewGroup containerRl = root.getChildAt(1) instanceof ViewGroup vg ? vg : null;
            View candidate = null;
            if (containerRl != null) {
                for (int i = 0; i < containerRl.getChildCount(); i++) {
                    if (containerRl.getChildAt(i) instanceof android.widget.LinearLayout ll && ll.getChildCount() > 0) {
                        View c0 = ll.getChildAt(0);
                        if (c0 != null && c0.getClass().getName().endsWith("ImeCandidateView")) {
                            candidate = c0;
                            break;
                        }
                    }
                }
            }
            if (candidate != null) {
                ViewGroup.LayoutParams lp = candidate.getLayoutParams();
                int wantLeft = imeCoverActive ? CoverDisplay.coverImeCandidateLeftInset(imeRotation) : 0;
                if (lp instanceof ViewGroup.MarginLayoutParams mlp) {
                    if (mlp.leftMargin != wantLeft || mlp.width != ViewGroup.LayoutParams.MATCH_PARENT) {
                        mlp.leftMargin = wantLeft;
                        mlp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                        candidate.setLayoutParams(mlp);
                    }
                }
                if (imeCoverActive) {
                    android.graphics.drawable.Drawable bg = candidate.getBackground();
                    if (bg != null) {
                        synchronized (savedCandidateBackgrounds) {
                            savedCandidateBackgrounds.putIfAbsent(candidate, bg);
                        }
                        candidate.setBackground(null);
                    }
                } else {
                    android.graphics.drawable.Drawable orig;
                    synchronized (savedCandidateBackgrounds) {
                        orig = savedCandidateBackgrounds.remove(candidate);
                    }
                    if (orig != null && candidate.getBackground() == null) {
                        candidate.setBackground(orig);
                    }
                }
            }
            if (upper != null) {
                float wantAlpha = imeCoverActive ? 0f : 1f;
                if (upper.getAlpha() != wantAlpha) {
                    upper.setAlpha(wantAlpha);
                }
            }
            if (containerRl != null) {
                ViewGroup.LayoutParams clp = containerRl.getLayoutParams();
                if (clp != null) {
                    if (imeCoverActive) {
                        int targetW = CoverDisplay.coverImeWidth(imeRotation);
                        if (clp.width != targetW) {
                            synchronized (savedContainerWidths) {
                                savedContainerWidths.putIfAbsent(containerRl, clp.width);
                            }
                            clp.width = targetW;
                            containerRl.setLayoutParams(clp);
                        }
                    } else {
                        Integer origWidth;
                        synchronized (savedContainerWidths) {
                            origWidth = savedContainerWidths.remove(containerRl);
                        }
                        int wantW = origWidth != null ? origWidth : ViewGroup.LayoutParams.MATCH_PARENT;
                        if (origWidth != null || CoverDisplay.isCoverImeWidth(clp.width)) {
                            if (clp.width != wantW) {
                                clp.width = wantW;
                                containerRl.setLayoutParams(clp);
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void drawWeTypeUnifiedHeaderBackground(android.graphics.Canvas canvas, View rootView, int width) {
        try {
            View upper = null;
            View below = null;
            if (rootView instanceof ViewGroup root
                    && root.getChildCount() > 0
                    && root.getChildAt(0) instanceof ViewGroup bgHolder
                    && bgHolder.getChildCount() >= 2) {
                upper = bgHolder.getChildAt(0);
                below = bgHolder.getChildAt(1);
            }
            int headerH = upper != null && upper.getHeight() > 0 ? upper.getHeight() : 144;
            android.graphics.drawable.Drawable belowBg = below != null ? below.getBackground() : null;
            if (belowBg instanceof android.graphics.drawable.ColorDrawable cd) {
                if (coverImeHeaderFillPaint == null) {
                    coverImeHeaderFillPaint = new android.graphics.Paint();
                }
                coverImeHeaderFillPaint.setColor(cd.getColor());
                canvas.drawRect(0f, 0f, width, headerH, coverImeHeaderFillPaint);
            } else if (belowBg != null) {
                Rect oldBounds = new Rect(belowBg.getBounds());
                int totalH = headerH + (below != null ? below.getHeight() : 0);
                belowBg.setBounds(0, 0, width, totalH);
                int sc = canvas.save();
                canvas.clipRect(0, 0, width, headerH);
                belowBg.draw(canvas);
                canvas.restoreToCount(sc);
                belowBg.setBounds(oldBounds);
            } else {
                if (coverImeHeaderFillPaint == null) {
                    coverImeHeaderFillPaint = new android.graphics.Paint();
                }
                coverImeHeaderFillPaint.setColor(0xFFEBEBEB);
                canvas.drawRect(0f, 0f, width, headerH, coverImeHeaderFillPaint);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void ensureCoverImePaths(int w, int h) {
        if (cachedCoverImeClipPath != null && cachedCoverImePathW == w && cachedCoverImePathH == h) {
            return;
        }
        cachedCoverImePathW = w;
        cachedCoverImePathH = h;
        android.graphics.Path clip = new android.graphics.Path();
        android.graphics.Path border = new android.graphics.Path();
        int n = SOGOU_CAMERA_CURVE_OFFSETS.length;
        float x0 = 0f;
        float y0 = SOGOU_CAMERA_CURVE_OFFSETS[0];
        clip.moveTo(x0, y0);
        border.moveTo(x0, y0 + 0.75f);
        for (int i = 0; i < n - 1; i++) {
            float cx = i * 20f;
            float cy = SOGOU_CAMERA_CURVE_OFFSETS[i];
            float nx = (i + 1) * 20f;
            float ny = SOGOU_CAMERA_CURVE_OFFSETS[i + 1];
            if (i == 0) {
                float mx = (cx + nx) * 0.5f;
                float my = (cy + ny) * 0.5f;
                clip.lineTo(mx, my);
                border.lineTo(mx, my + 0.75f);
            } else if (i < n - 2) {
                float mx = (cx + nx) * 0.5f;
                float my = (cy + ny) * 0.5f;
                clip.quadTo(cx, cy, mx, my);
                border.quadTo(cx, cy + 0.75f, mx, my + 0.75f);
            } else {
                clip.quadTo(cx, cy, nx, ny);
                border.quadTo(cx, cy + 0.75f, nx, ny + 0.75f);
            }
        }
        clip.lineTo(w, 0f);
        clip.lineTo(w, h);
        clip.lineTo(0f, h);
        clip.close();
        border.lineTo(w, 0.75f);
        cachedCoverImeClipPath = clip;
        cachedCoverImeBorderPath = border;
        if (coverImeTopBorderPaint == null) {
            android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(1.5f);
            p.setColor(0x1A000000);
            coverImeTopBorderPaint = p;
        }
    }

    private static boolean isCompatActivityResources(Resources r) {
        return r != null && compatActivityResources.contains(r);
    }

    private static boolean isDisplayUsingCompatResources(Display d) {
        if (d == null) return false;
        try {
            java.lang.reflect.Field f = displayResourcesField;
            if (f == null) {
                f = Display.class.getDeclaredField("mResources");
                f.setAccessible(true);
                displayResourcesField = f;
            }
            Object res = f.get(d);
            return res instanceof Resources r && isCompatActivityResources(r);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hasWindowBounds(android.content.res.Configuration cfg, int w, int h) {
        if (cfg == null) return false;
        try {
            java.lang.reflect.Field wcField = windowConfigurationField;
            if (wcField == null) {
                wcField = android.content.res.Configuration.class.getDeclaredField("windowConfiguration");
                wcField.setAccessible(true);
                windowConfigurationField = wcField;
            }
            Object wc = wcField.get(cfg);
            if (wc == null) return false;
            java.lang.reflect.Method m = wcGetMaxBoundsMethod;
            if (m == null) {
                m = wc.getClass().getMethod("getMaxBounds");
                wcGetMaxBoundsMethod = m;
            }
            if (m.invoke(wc) instanceof Rect r) {
                return r.width() == w && r.height() == h;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean voiceAssistOverlayAdapting() {
        return voiceAssistProcess
                && FrameworkRuntime.active()
                && ModuleConfig.immersiveEnabled()
                && ModuleConfig.compatEnabled(VOICEASSIST_PKG);
    }

    static boolean shouldExposeVoiceAssistCoverCutout(
            boolean isVoiceAssistProcess,
            boolean isOuterDisplay,
            boolean isCompatActivityActive,
            boolean immersive,
            boolean appCompat) {
        return isVoiceAssistProcess
                && isOuterDisplay
                && !isCompatActivityActive
                && immersive
                && appCompat;
    }

    private static boolean isEmptyCutout(Object cutoutObj) {
        if (!(cutoutObj instanceof android.view.DisplayCutout cutout)) return true;
        try {
            return cutout.getSafeInsetLeft() == 0
                    && cutout.getSafeInsetTop() == 0
                    && cutout.getSafeInsetRight() == 0
                    && cutout.getSafeInsetBottom() == 0
                    && cutout.getBoundingRects().isEmpty();
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * Restores the real cover-screen rear camera DisplayCutout (Rect(810, 664, 1208, 1392) in ROTATION_0;
     * Rect(0, 0, 398, 728) in ROTATION_180) for Super XiaoAi's fullscreen floating overlay when HyperOS
     * ActivityThreadImpl.inMiuiSizeCompatMode() suppresses Display.getCutout() / WindowInsets.getDisplayCutout()
     * to DisplayCutout.NO_CUTOUT.
     */
    private static android.view.DisplayCutout resolveOuterFoldedCutout(Display display) {
        try {
            Display realDisplay = null;
            if (display == null) {
                Object dmg = Class.forName("android.hardware.display.DisplayManagerGlobal")
                        .getDeclaredMethod("getInstance").invoke(null);
                if (dmg != null) {
                    realDisplay = (Display) dmg.getClass()
                            .getDeclaredMethod("getRealDisplay", int.class)
                            .invoke(dmg, Display.DEFAULT_DISPLAY);
                }
                display = realDisplay;
            }
            if (display == null || !CoverDisplay.isOuterDisplay(display)) {
                if (display != null && display.getDisplayId() == Display.DEFAULT_DISPLAY && outerProcess) {
                    outerProcess = false;
                    portraitProcess = false;
                }
                return null;
            }
            if (display.getDisplayId() == Display.DEFAULT_DISPLAY) {
                outerProcess = true;
                portraitProcess = CoverDisplay.isOuterPortraitDisplay(display);
            }
            try {
                java.lang.reflect.Method m = getFlipFoldedCutoutMethod;
                if (m == null) {
                    m = Display.class.getDeclaredMethod("getFlipFoldedCutout");
                    m.setAccessible(true);
                    getFlipFoldedCutoutMethod = m;
                }
                Object fc = m.invoke(display);
                if (fc instanceof android.view.DisplayCutout cutout && !isEmptyCutout(cutout)) {
                    return cutout;
                }
            } catch (Throwable ignored) {
            }
            if (realDisplay == null) {
                try {
                    Object dmg = Class.forName("android.hardware.display.DisplayManagerGlobal")
                            .getDeclaredMethod("getInstance").invoke(null);
                    if (dmg != null) {
                        realDisplay = (Display) dmg.getClass()
                                .getDeclaredMethod("getRealDisplay", int.class)
                                .invoke(dmg, Display.DEFAULT_DISPLAY);
                    }
                } catch (Throwable ignored) {
                }
            }
            if (realDisplay != null && CoverDisplay.isOuterDisplay(realDisplay)) {
                android.view.DisplayCutout rc = realDisplay.getCutout();
                if (!isEmptyCutout(rc)) {
                    return rc;
                }
            }
            return CoverDisplay.buildOuterDisplayCutout(display.getRotation());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void syncVoiceAssistDisplayState(Context ctx) {
        if (!voiceAssistProcess) return;
        if (!voiceAssistOverlayAdapting()) {
            restoreOverrides(outerProcess);
            return;
        }
        Display d = null;
        try {
            if (ctx != null) {
                DisplayManager dm = ctx.getSystemService(DisplayManager.class);
                if (dm != null) d = dm.getDisplay(Display.DEFAULT_DISPLAY);
            }
            if (d == null) {
                Object dmg = Class.forName("android.hardware.display.DisplayManagerGlobal")
                        .getDeclaredMethod("getInstance").invoke(null);
                if (dmg != null) {
                    d = (Display) dmg.getClass()
                            .getDeclaredMethod("getRealDisplay", int.class)
                            .invoke(dmg, Display.DEFAULT_DISPLAY);
                }
            }
        } catch (Throwable ignored) {
        }
        if (d == null) return;
        boolean wasOuter = outerProcess;
        boolean nowOuter = CoverDisplay.isOuterDisplay(d);
        outerProcess = nowOuter;
        portraitProcess = nowOuter && CoverDisplay.isOuterPortraitDisplay(d);
        if (!nowOuter) {
            voiceAssistCompatActivityActive = false;
            if (wasOuter) {
                restoreOverrides(false);
            }
            syncVoiceAssistHaloWindows(d, false);
            return;
        }
        int[] t = nativeCoverTarget(portraitProcess);
        Context appCtx = ctx != null && ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        for (Resources res : new Resources[]{
                ctx != null ? ctx.getResources() : null,
                appCtx != null ? appCtx.getResources() : null,
                Resources.getSystem()}) {
            if (res == null || isCompatActivityResources(res)) continue;
            overrideMetrics(res.getDisplayMetrics(), t[0], t[1], CoverDisplay.DENSITY_DPI);
            adaptNativeCoverConfig(res.getConfiguration(), t);
        }
        syncVoiceAssistHaloWindows(d, true);
    }

    static boolean isVoiceAssistHaloWindowTitle(CharSequence title) {
        if (title == null) return false;
        String s = title.toString();
        return "voice_assist_root".equals(s)
                || "voice_assist_gesture_line_handoff_effect".equals(s);
    }

    static boolean isVoiceAssistFullscreenHaloWindow(
            int windowType, CharSequence title, String viewClassName) {
        if (windowType < WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW) {
            return false;
        }
        if (isVoiceAssistHaloWindowTitle(title)) {
            return true;
        }
        return "com.xiaomi.voiceassistant.mainui.board.FloatViewRootLayout".equals(viewClassName);
    }

    private static boolean isVoiceAssistFullscreenHaloWindow(
            WindowManager.LayoutParams lp, View view) {
        if (lp == null) return false;
        CharSequence title = null;
        try {
            title = lp.getTitle();
        } catch (Throwable ignored) {
        }
        String viewCls = view != null ? view.getClass().getName() : null;
        return isVoiceAssistFullscreenHaloWindow(lp.type, title, viewCls);
    }

    private static boolean setMiuiAlwaysDisplayInCutout(
            WindowManager.LayoutParams lp, boolean value) {
        if (lp == null) return false;
        int state = lpMiuiAlwaysDisplayInCutoutState;
        if (state < 0) return false;
        try {
            java.lang.reflect.Field f = lpMiuiAlwaysDisplayInCutoutField;
            if (f == null) {
                f = WindowManager.LayoutParams.class.getDeclaredField("miuiAlwaysDisplayInCutout");
                f.setAccessible(true);
                lpMiuiAlwaysDisplayInCutoutField = f;
                lpMiuiAlwaysDisplayInCutoutState = 1;
            }
            if (f.getBoolean(lp) != value) {
                f.setBoolean(lp, value);
                return true;
            }
        } catch (Throwable ignored) {
            if (lpMiuiAlwaysDisplayInCutoutState == 0) {
                lpMiuiAlwaysDisplayInCutoutState = -1;
            }
        }
        return false;
    }

    /**
     * Normalizes Super XiaoAi's fullscreen halo overlay LayoutParams on the folded cover screen:
     * 1) Full 1208x1392 (portrait) or 1392x1208 (landscape) bounds even if VoiceService folded from
     *    1224x2912 at ROTATION_0 without changing rotation (where s1.reMeasureViews skips relayout).
     * 2) Gravity.TOP | Gravity.START (0x00800033) instead of Gravity.TOP (0x30 / CENTER_HORIZONTAL
     *    used by un1.b in ROTATION_0) so client-side WindowLayout.computeFrames never shifts the
     *    1208px window -199px off-screen inside an 810px cutout-safe frame.
     * 3) LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS + miuiAlwaysDisplayInCutout=true so both WMS and
     *    client-side WindowLayoutStubImpl span the full physical cover panel on all 4 edges.
     */
    static boolean normalizeVoiceAssistCoverHaloLayoutParams(
            WindowManager.LayoutParams lp, int rotation) {
        if (lp == null) return false;
        boolean portrait = CoverDisplay.isPortraitRotation(rotation);
        int targetW = portrait ? CoverDisplay.WIDTH : CoverDisplay.HEIGHT;
        int targetH = portrait ? CoverDisplay.HEIGHT : CoverDisplay.WIDTH;
        int targetGravity = android.view.Gravity.TOP | android.view.Gravity.START;
        int targetCutout = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        boolean changed = false;
        if (lp.width != targetW) {
            lp.width = targetW;
            changed = true;
        }
        if (lp.height != targetH) {
            lp.height = targetH;
            changed = true;
        }
        if (lp.x != 0) {
            lp.x = 0;
            changed = true;
        }
        if (lp.y != 0) {
            lp.y = 0;
            changed = true;
        }
        if (lp.gravity != targetGravity) {
            lp.gravity = targetGravity;
            changed = true;
        }
        if (lp.layoutInDisplayCutoutMode != targetCutout) {
            lp.layoutInDisplayCutoutMode = targetCutout;
            changed = true;
        }
        if (setMiuiAlwaysDisplayInCutout(lp, true)) {
            changed = true;
        }
        return changed;
    }

    /**
     * Restores Super XiaoAi's fullscreen halo overlay LayoutParams back to inner-screen bounds
     * when unfolding from 1208x1392 cover (ROTATION_0) to 1224x2912 inner screen (ROTATION_0).
     */
    static boolean restoreVoiceAssistInnerHaloLayoutParams(
            WindowManager.LayoutParams lp, int physicalWidth, int physicalHeight, int rotation) {
        if (lp == null || physicalWidth <= 0 || physicalHeight <= 0) return false;
        if (CoverDisplay.isOuterDimensions(physicalWidth, physicalHeight)) return false;
        if (!CoverDisplay.isOuterDimensions(lp.width, lp.height)) return false;
        int shortSide = Math.min(physicalWidth, physicalHeight);
        int longSide = Math.max(physicalWidth, physicalHeight);
        boolean portrait = CoverDisplay.isPortraitRotation(rotation);
        int targetW = portrait ? shortSide : longSide;
        int targetH = portrait ? longSide : shortSide;
        int targetGravity = rotation == android.view.Surface.ROTATION_0
                ? android.view.Gravity.TOP
                : (android.view.Gravity.TOP | android.view.Gravity.START);
        int targetCutout = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        boolean changed = false;
        if (lp.width != targetW) {
            lp.width = targetW;
            changed = true;
        }
        if (lp.height != targetH) {
            lp.height = targetH;
            changed = true;
        }
        if (lp.x != 0) {
            lp.x = 0;
            changed = true;
        }
        if (lp.y != 0) {
            lp.y = 0;
            changed = true;
        }
        if (lp.gravity != targetGravity) {
            lp.gravity = targetGravity;
            changed = true;
        }
        if (lp.layoutInDisplayCutoutMode != targetCutout) {
            lp.layoutInDisplayCutoutMode = targetCutout;
            changed = true;
        }
        if (setMiuiAlwaysDisplayInCutout(lp, false)) {
            changed = true;
        }
        return changed;
    }

    private static void invokeWindowManagerGlobalUpdateViewLayout(
            View view, WindowManager.LayoutParams lp) {
        if (view == null || lp == null) return;
        try {
            Object wmg = cachedWindowManagerGlobal;
            if (wmg == null) {
                wmg = Class.forName("android.view.WindowManagerGlobal")
                        .getDeclaredMethod("getInstance")
                        .invoke(null);
                cachedWindowManagerGlobal = wmg;
            }
            if (wmg == null) return;
            java.lang.reflect.Method m = wmgUpdateViewLayoutMethod;
            if (m == null) {
                m = wmg.getClass().getDeclaredMethod(
                        "updateViewLayout", View.class, ViewGroup.LayoutParams.class);
                m.setAccessible(true);
                wmgUpdateViewLayoutMethod = m;
            }
            m.invoke(wmg, view, lp);
        } catch (Throwable ignored) {
            voiceAssistHaloViews.remove(view);
        }
    }

    private static void syncVoiceAssistHaloWindows(Display display, boolean isOuter) {
        if (voiceAssistHaloViews.isEmpty()) return;
        if (Boolean.TRUE.equals(updatingVoiceAssistHaloLayout.get())) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            if (!voiceAssistHaloSyncPending) {
                voiceAssistHaloSyncPending = true;
                new Handler(Looper.getMainLooper()).post(() -> {
                    voiceAssistHaloSyncPending = false;
                    try {
                        Display live = resolveRealDefaultDisplay();
                        if (live != null) {
                            syncVoiceAssistHaloWindows(live, CoverDisplay.isOuterDisplay(live));
                        }
                    } catch (Throwable ignored) {
                    }
                });
            }
            return;
        }
        Display d = display != null ? display : resolveRealDefaultDisplay();
        if (d == null) return;
        List<Map.Entry<View, WindowManager.LayoutParams>> snapshot;
        synchronized (voiceAssistHaloViews) {
            if (voiceAssistHaloViews.isEmpty()) return;
            snapshot = new ArrayList<>(voiceAssistHaloViews.entrySet());
        }
        updatingVoiceAssistHaloLayout.set(Boolean.TRUE);
        try {
            int rot = d.getRotation();
            int physW = 0;
            int physH = 0;
            if (!isOuter) {
                try {
                    Display.Mode mode = d.getMode();
                    if (mode != null) {
                        physW = mode.getPhysicalWidth();
                        physH = mode.getPhysicalHeight();
                    }
                    if (physW <= 0 || physH <= 0) {
                        Point p = new Point();
                        d.getRealSize(p);
                        physW = p.x;
                        physH = p.y;
                    }
                } catch (Throwable ignored) {
                }
            }
            boolean adaptingCover = isOuter && voiceAssistOverlayAdapting();
            for (Map.Entry<View, WindowManager.LayoutParams> entry : snapshot) {
                View view = entry.getKey();
                WindowManager.LayoutParams lp = entry.getValue();
                if (view == null || lp == null || view.getParent() == null) continue;
                if (adaptingCover) {
                    boolean changed = normalizeVoiceAssistCoverHaloLayoutParams(lp, rot);
                    boolean sizeMismatch = view.getWidth() > 0 && view.getHeight() > 0
                            && (view.getWidth() != lp.width || view.getHeight() != lp.height);
                    if (changed || sizeMismatch) {
                        invokeWindowManagerGlobalUpdateViewLayout(view, lp);
                        view.requestLayout();
                    }
                } else if (!isOuter) {
                    boolean changed = restoreVoiceAssistInnerHaloLayoutParams(lp, physW, physH, rot);
                    boolean sizeMismatch = CoverDisplay.isOuterDimensions(view.getWidth(), view.getHeight())
                            && (view.getWidth() != lp.width || view.getHeight() != lp.height);
                    if (changed || sizeMismatch) {
                        invokeWindowManagerGlobalUpdateViewLayout(view, lp);
                        view.requestLayout();
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            updatingVoiceAssistHaloLayout.remove();
        }
    }

    private static void registerVoiceAssistDisplayListener(Context ctx) {
        if (ctx == null || voiceAssistDisplayListenerRegistered) return;
        try {
            Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
            DisplayManager dm = appCtx.getSystemService(DisplayManager.class);
            if (dm == null) return;
            WeakReference<Context> fallbackRef = new WeakReference<>(appCtx);
            voiceAssistDisplayListenerRegistered = true;
            dm.registerDisplayListener(new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                    if (displayId == Display.DEFAULT_DISPLAY) {
                        syncVoiceAssistDisplayState(voiceAssistAppContextRef != null
                                ? voiceAssistAppContextRef.get()
                                : fallbackRef.get());
                    }
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                    if (displayId == Display.DEFAULT_DISPLAY) {
                        syncVoiceAssistDisplayState(voiceAssistAppContextRef != null
                                ? voiceAssistAppContextRef.get()
                                : fallbackRef.get());
                    }
                }

                @Override
                public void onDisplayChanged(int displayId) {
                    if (displayId == Display.DEFAULT_DISPLAY) {
                        syncVoiceAssistDisplayState(voiceAssistAppContextRef != null
                                ? voiceAssistAppContextRef.get()
                                : fallbackRef.get());
                    }
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (Throwable t) {
            Log.w(TAG, "voiceassist display listener skipped", t);
        }
    }

    @android.annotation.SuppressLint("WrongConstant")
    private static void applyRealCoverMetrics(android.util.DisplayMetrics out, int[] target) {
        float fontScale = out.density > 0 && out.scaledDensity > 0
                ? out.scaledDensity / out.density : 1f;
        out.widthPixels = target[0];
        out.heightPixels = target[1];
        out.densityDpi = CoverDisplay.DENSITY_DPI;
        out.density = CoverDisplay.DENSITY_DPI / 160f;
        out.scaledDensity = out.density * fontScale;
    }

    /**
     * Keeps Super XiaoAi's floating overlay (VoiceService / WindowManager type 2017) at full unscaled 1208x1392 @ 520dpi
     * on the cover display, even if system_server has not yet been rebooted after updating BoundsCompatUtils.
     * Activity windows (e.g. TabHomeActivity) keep their Activity-scoped 0.8x compat Resources via compatActivityResources.
     */
    private static void installVoiceAssistClientHooks(XposedModule module) {
        try {
            module.hook(Display.class.getDeclaredMethod("getCutout")).intercept(chain -> {
                if (Boolean.TRUE.equals(resolvingDisplayCutout.get())) {
                    return chain.proceed();
                }
                resolvingDisplayCutout.set(Boolean.TRUE);
                try {
                    Object result = chain.proceed();
                    try {
                        if (isEmptyCutout(result)
                                && chain.getThisObject() instanceof Display d
                                && !isDisplayUsingCompatResources(d)
                                && shouldExposeVoiceAssistCoverCutout(
                                        voiceAssistProcess,
                                        CoverDisplay.isOuterDisplay(d),
                                        false,
                                        ModuleConfig.immersiveEnabled(),
                                        ModuleConfig.compatEnabled(VOICEASSIST_PKG))) {
                            android.view.DisplayCutout fc = resolveOuterFoldedCutout(d);
                            if (fc != null) {
                                return fc;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    return result;
                } finally {
                    resolvingDisplayCutout.remove();
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "Display.getCutout hook unavailable", t);
        }

        try {
            module.hook(Display.class.getDeclaredMethod("getRoundedCorner", int.class)).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    if ((result == null || (result instanceof android.view.RoundedCorner rc && rc.getRadius() <= 0))
                            && chain.getThisObject() instanceof Display d
                            && d.getDisplayId() == Display.DEFAULT_DISPLAY
                            && CoverDisplay.isOuterDisplay(d)
                            && voiceAssistOverlayAdapting()
                            && !isDisplayUsingCompatResources(d)) {
                        int position = (Integer) chain.getArg(0);
                        android.view.RoundedCorner corner =
                                CoverDisplay.buildOuterRoundedCorner(d.getRotation(), position);
                        if (corner != null) {
                            return corner;
                        }
                    }
                } catch (Throwable ignored) {
                }
                return result;
            });
        } catch (Throwable t) {
            Log.w(TAG, "Display.getRoundedCorner hook unavailable", t);
        }

        try {
            module.hook(Display.class.getDeclaredMethod("getRealMetrics", android.util.DisplayMetrics.class)).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Display d = (Display) chain.getThisObject();
                    android.util.DisplayMetrics out = (android.util.DisplayMetrics) chain.getArg(0);
                    if (d != null) {
                        boolean isOuter = CoverDisplay.isOuterDisplay(d);
                        if (d.getDisplayId() == Display.DEFAULT_DISPLAY) {
                            if (!isOuter) {
                                if (outerProcess) {
                                    outerProcess = false;
                                    portraitProcess = false;
                                    voiceAssistCompatActivityActive = false;
                                    restoreOverrides(false);
                                }
                                syncVoiceAssistHaloWindows(d, false);
                                return result;
                            }
                            outerProcess = true;
                            portraitProcess = CoverDisplay.isOuterPortraitDisplay(d);
                        }
                        if (isOuter && out != null && voiceAssistOverlayAdapting() && !isDisplayUsingCompatResources(d)) {
                            int[] t = nativeCoverTarget(CoverDisplay.isPortraitRotation(d.getRotation()));
                            applyRealCoverMetrics(out, t);
                            if (d.getDisplayId() == Display.DEFAULT_DISPLAY) {
                                syncVoiceAssistHaloWindows(d, true);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                return result;
            });
        } catch (Throwable t) {
            Log.w(TAG, "Display.getRealMetrics hook unavailable", t);
        }

        try {
            module.hook(Display.class.getDeclaredMethod("getRealSize", Point.class)).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Display d = (Display) chain.getThisObject();
                    Point out = (Point) chain.getArg(0);
                    if (d != null) {
                        boolean isOuter = CoverDisplay.isOuterDisplay(d);
                        if (d.getDisplayId() == Display.DEFAULT_DISPLAY) {
                            if (!isOuter) {
                                if (outerProcess) {
                                    outerProcess = false;
                                    portraitProcess = false;
                                    voiceAssistCompatActivityActive = false;
                                    restoreOverrides(false);
                                }
                                syncVoiceAssistHaloWindows(d, false);
                                return result;
                            }
                            outerProcess = true;
                            portraitProcess = CoverDisplay.isOuterPortraitDisplay(d);
                        }
                        if (isOuter && out != null && voiceAssistOverlayAdapting() && !isDisplayUsingCompatResources(d)) {
                            int[] t = nativeCoverTarget(CoverDisplay.isPortraitRotation(d.getRotation()));
                            out.set(t[0], t[1]);
                            if (d.getDisplayId() == Display.DEFAULT_DISPLAY) {
                                syncVoiceAssistHaloWindows(d, true);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                return result;
            });
        } catch (Throwable t) {
            Log.w(TAG, "Display.getRealSize hook unavailable", t);
        }

        try {
            Class<?> wmgCls = Class.forName("android.view.WindowManagerGlobal");
            for (java.lang.reflect.Method m : wmgCls.getDeclaredMethods()) {
                String name = m.getName();
                if ("addView".equals(name)) {
                    module.hook(m).intercept(chain -> {
                        try {
                            if (chain.getArg(0) instanceof View v
                                    && chain.getArg(1) instanceof WindowManager.LayoutParams lp
                                    && isVoiceAssistFullscreenHaloWindow(lp, v)) {
                                voiceAssistHaloViews.put(v, lp);
                                Display d = chain.getArgs().size() > 2 && chain.getArg(2) instanceof Display disp
                                        ? disp : resolveRealDefaultDisplay();
                                if (d != null) {
                                    if (CoverDisplay.isOuterDisplay(d) && voiceAssistOverlayAdapting()) {
                                        normalizeVoiceAssistCoverHaloLayoutParams(lp, d.getRotation());
                                    } else if (!CoverDisplay.isOuterDisplay(d) && d.getMode() != null) {
                                        restoreVoiceAssistInnerHaloLayoutParams(
                                                lp,
                                                d.getMode().getPhysicalWidth(),
                                                d.getMode().getPhysicalHeight(),
                                                d.getRotation());
                                    }
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    });
                } else if ("updateViewLayout".equals(name) && m.getParameterCount() == 2) {
                    module.hook(m).intercept(chain -> {
                        try {
                            if (!Boolean.TRUE.equals(updatingVoiceAssistHaloLayout.get())
                                    && chain.getArg(0) instanceof View v
                                    && chain.getArg(1) instanceof WindowManager.LayoutParams lp
                                    && isVoiceAssistFullscreenHaloWindow(lp, v)) {
                                voiceAssistHaloViews.put(v, lp);
                                Display d = resolveRealDefaultDisplay();
                                if (d != null) {
                                    if (CoverDisplay.isOuterDisplay(d) && voiceAssistOverlayAdapting()) {
                                        normalizeVoiceAssistCoverHaloLayoutParams(lp, d.getRotation());
                                    } else if (!CoverDisplay.isOuterDisplay(d) && d.getMode() != null) {
                                        restoreVoiceAssistInnerHaloLayoutParams(
                                                lp,
                                                d.getMode().getPhysicalWidth(),
                                                d.getMode().getPhysicalHeight(),
                                                d.getRotation());
                                    }
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    });
                } else if ("removeView".equals(name) && m.getParameterCount() == 2) {
                    module.hook(m).intercept(chain -> {
                        try {
                            if (chain.getArg(0) instanceof View v) {
                                voiceAssistHaloViews.remove(v);
                            }
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    });
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "WindowManagerGlobal voiceassist halo hook unavailable", t);
        }

        try {
            module.hook(Application.class.getDeclaredMethod(
                    "onConfigurationChanged", android.content.res.Configuration.class)).intercept(chain -> {
                try {
                    if (chain.getThisObject() instanceof Context ctx) {
                        syncVoiceAssistDisplayState(ctx);
                        if (outerProcess && voiceAssistOverlayAdapting()
                                && chain.getArg(0) instanceof android.content.res.Configuration cfg) {
                            adaptNativeCoverConfig(cfg, nativeCoverTarget(portraitProcess));
                        }
                    }
                } catch (Throwable ignored) {
                }
                Object res = chain.proceed();
                try {
                    if (chain.getThisObject() instanceof Context ctx) {
                        syncVoiceAssistDisplayState(ctx);
                    }
                } catch (Throwable ignored) {
                }
                return res;
            });
        } catch (Throwable ignored) {
        }

        try {
            module.hook(Service.class.getDeclaredMethod("onCreate")).intercept(chain -> {
                try {
                    if (chain.getThisObject() instanceof Context ctx) {
                        syncVoiceAssistDisplayState(ctx);
                    }
                } catch (Throwable ignored) {
                }
                Object res = chain.proceed();
                try {
                    if (chain.getThisObject() instanceof Context ctx) {
                        syncVoiceAssistDisplayState(ctx);
                    }
                } catch (Throwable ignored) {
                }
                return res;
            });
        } catch (Throwable ignored) {
        }

        try {
            module.hook(Service.class.getDeclaredMethod(
                    "onConfigurationChanged", android.content.res.Configuration.class)).intercept(chain -> {
                try {
                    if (chain.getThisObject() instanceof Context ctx) {
                        syncVoiceAssistDisplayState(ctx);
                        if (outerProcess && voiceAssistOverlayAdapting()
                                && chain.getArg(0) instanceof android.content.res.Configuration cfg) {
                            adaptNativeCoverConfig(cfg, nativeCoverTarget(portraitProcess));
                        }
                    }
                } catch (Throwable ignored) {
                }
                Object res = chain.proceed();
                try {
                    if (chain.getThisObject() instanceof Context ctx) {
                        syncVoiceAssistDisplayState(ctx);
                    }
                } catch (Throwable ignored) {
                }
                return res;
            });
        } catch (Throwable ignored) {
        }
    }

    private static int[] nativeCoverTarget(boolean portrait) {
        // {widthPx, heightPx, widthDp, heightDp} of the full cover panel at its native 520 dpi.
        return portrait
                ? new int[]{CoverDisplay.WIDTH, CoverDisplay.HEIGHT, 372, 428}
                : new int[]{CoverDisplay.HEIGHT, CoverDisplay.WIDTH, 428, 372};
    }

    private static void adaptNativeCoverConfig(android.content.res.Configuration cfg, int[] t) {
        if (cfg == null) return;
        int orientation = t[0] <= t[1]
                ? android.content.res.Configuration.ORIENTATION_PORTRAIT
                : android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        synchronized (overriddenOriginals) {
            if (hookModule != null
                    && (!outerProcess
                            || !((nativeCoverActivityActive && nativeCoverCompatEnabled())
                                    || voiceAssistOverlayAdapting()))) {
                return;
            }
            if (cfg.densityDpi == CoverDisplay.DENSITY_DPI
                    && cfg.screenWidthDp == t[2]
                    && cfg.screenHeightDp == t[3]
                    && cfg.orientation == orientation
                    && hasWindowBounds(cfg, t[0], t[1])) {
                return;
            }
            rememberConfig(cfg);
            cfg.densityDpi = CoverDisplay.DENSITY_DPI;
            cfg.screenWidthDp = t[2];
            cfg.screenHeightDp = t[3];
            cfg.smallestScreenWidthDp = Math.min(t[2], t[3]);
            cfg.orientation = orientation;
            setWindowBounds(cfg, new Rect(0, 0, t[0], t[1]));
        }
    }

    private static void ensureNativeCoverMetrics(Activity a) {
        if (a == null || !nativeCoverCompatEnabled()) return;
        try {
            Display d = a.getDisplay();
            if (!CoverDisplay.isOuterDisplay(d)) return;
            int[] t = nativeCoverTarget(CoverDisplay.isPortraitRotation(d.getRotation()));
            for (Resources res : new Resources[]{
                    a.getResources(),
                    a.getApplicationContext() != null ? a.getApplicationContext().getResources() : null,
                    Resources.getSystem()}) {
                if (res == null) continue;
                overrideMetrics(res.getDisplayMetrics(), t[0], t[1], CoverDisplay.DENSITY_DPI);
                adaptNativeCoverConfig(res.getConfiguration(), t);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Keeps natively adapted cover Activities (and the cover IME) on unscaled metrics even when HyperOS
     * ProcessCompat pushes a scaled configuration to the rest of the process. The hooks short-circuit on two
     * volatile flags, so the cost for the normal case is a single field read.
     */
    private static void installMetricsHooks(XposedModule module) throws ReflectiveOperationException {
        module.hook(Resources.class.getDeclaredMethod("getDisplayMetrics")).intercept(chain -> {
            Object res = chain.proceed();
            try {
                if (imeCoverActive) {
                    if (isLiveDefaultDisplayInner()) {
                        imeCoverActive = false;
                        lastImeBoundsRotation = -1;
                        restoreOverrides(false);
                        if (res instanceof android.util.DisplayMetrics dm && isCoverSizedMetrics(dm)) {
                            applyInnerRealToMetrics(dm, queryInnerRealMetrics());
                        }
                        return res;
                    }
                    if (res instanceof android.util.DisplayMetrics dm) {
                        int rot = getDisplayRotation(null);
                        imeRotation = rot;
                        overrideMetrics(dm,
                                CoverDisplay.coverImeWidth(rot),
                                CoverDisplay.coverImeDisplayHeight(rot),
                                CoverDisplay.coverImeDensityDpi(rot));
                    }
                } else if (imeProcess
                        && res instanceof android.util.DisplayMetrics dm
                        && isCoverSizedMetrics(dm)
                        && isLiveDefaultDisplayInner()) {
                    applyInnerRealToMetrics(dm, queryInnerRealMetrics());
                } else if (outerProcess
                        && ((nativeCoverActivityActive && nativeCoverCompatEnabled())
                                || (voiceAssistOverlayAdapting() && !isCompatActivityResources((Resources) chain.getThisObject())))
                        && res instanceof android.util.DisplayMetrics dm) {
                    int[] t = nativeCoverTarget(portraitProcess);
                    overrideMetrics(dm, t[0], t[1], CoverDisplay.DENSITY_DPI);
                }
            } catch (Throwable ignored) {
            }
            return res;
        });

        module.hook(Resources.class.getDeclaredMethod("getConfiguration")).intercept(chain -> {
            Object res = chain.proceed();
            try {
                if (imeCoverActive) {
                    if (isLiveDefaultDisplayInner()) {
                        imeCoverActive = false;
                        lastImeBoundsRotation = -1;
                        restoreOverrides(false);
                        if (res instanceof android.content.res.Configuration cfg && isCoverSizedConfig(cfg)) {
                            applyInnerRealToConfig(cfg, queryInnerRealMetrics());
                        }
                        return res;
                    }
                    if (res instanceof android.content.res.Configuration cfg) {
                        int cfgRot = getConfigRotation(cfg);
                        int liveRot = getLiveImeDisplayRotation(cfgRot >= 0 && cfgRot != liveImeDisplayRotation);
                        int rot = resolveImeRotationForConfig(cfg, liveRot, imeRotation);
                        imeRotation = rot;
                        adaptImeConfig(cfg, rot);
                    }
                } else if (imeProcess
                        && res instanceof android.content.res.Configuration cfg
                        && isCoverSizedConfig(cfg)
                        && isLiveDefaultDisplayInner()) {
                    applyInnerRealToConfig(cfg, queryInnerRealMetrics());
                } else if (outerProcess
                        && ((nativeCoverActivityActive && nativeCoverCompatEnabled())
                                || (voiceAssistOverlayAdapting() && !isCompatActivityResources((Resources) chain.getThisObject())))
                        && res instanceof android.content.res.Configuration cfg) {
                    adaptNativeCoverConfig(cfg, nativeCoverTarget(portraitProcess));
                }
            } catch (Throwable ignored) {
            }
            return res;
        });
    }

    private static boolean isVoiceAssistCompatView(View v) {
        if (v == null) return voiceAssistCompatActivityActive;
        try {
            View root = v.getRootView();
            if (root != null && root.getLayoutParams() instanceof WindowManager.LayoutParams lp) {
                if (lp.type >= WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW) {
                    return false;
                }
                if (lp.type >= 1 && lp.type < WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW) {
                    return true;
                }
            }
            Resources r = v.getResources();
            if (isCompatActivityResources(r)) return true;
            Context c = v.getContext();
            int depth = 0;
            while (c instanceof android.content.ContextWrapper cw && depth++ < 12) {
                if (c instanceof Activity act) {
                    return !isNativeCoverFullscreenActivity(act);
                }
                if (c instanceof Service || c instanceof Application) {
                    return false;
                }
                c = cw.getBaseContext();
            }
            if (c instanceof Activity act) {
                return !isNativeCoverFullscreenActivity(act);
            }
            if (c instanceof Service || c instanceof Application) {
                return false;
            }
        } catch (Throwable ignored) {
        }
        return voiceAssistCompatActivityActive;
    }

    private static boolean shouldClampWindowInsets(WindowInsets wi) {
        if (!voiceAssistProcess) {
            return resourcePatchActive();
        }
        if (nativeCoverActivityActive
                || !FrameworkRuntime.active()
                || !ModuleConfig.immersiveEnabled()
                || !ModuleConfig.compatEnabled(VOICEASSIST_PKG)
                || !outerProcess
                || !portraitProcess
                || isLiveDefaultDisplayInner()) {
            return false;
        }
        Boolean tag = wi != null ? windowInsetsCompatTag.get(wi) : null;
        if (tag == null) {
            tag = dispatchingCompatWindowInsets.get();
        }
        return tag != null ? tag : voiceAssistCompatActivityActive;
    }

    private static boolean shouldExposeVoiceAssistWindowInsetsCutout(WindowInsets wi) {
        if (!voiceAssistOverlayAdapting() || !outerProcess || isLiveDefaultDisplayInner()) {
            return false;
        }
        Boolean tag = wi != null ? windowInsetsCompatTag.get(wi) : null;
        if (tag == null) {
            tag = dispatchingCompatWindowInsets.get();
        }
        boolean compatWin = tag != null ? tag : voiceAssistCompatActivityActive;
        return shouldExposeVoiceAssistCoverCutout(
                voiceAssistProcess,
                outerProcess,
                compatWin,
                ModuleConfig.immersiveEnabled(),
                ModuleConfig.compatEnabled(VOICEASSIST_PKG));
    }

    private static void installStatusHeightHooks(XposedModule module) throws ReflectiveOperationException {
        for (String name : new String[]{"getDimensionPixelSize", "getDimensionPixelOffset"}) {
            module.hook(Resources.class.getDeclaredMethod(name, int.class)).intercept(chain -> {
                if (resourcePatchActive()) {
                    try {
                        Resources r = (Resources) chain.getThisObject();
                        if ((!voiceAssistProcess || isCompatActivityResources(r)) && !isInnerScreenResources(r)) {
                            int id = (Integer) chain.getArg(0);
                            if (isStatusHeightResource(r, id)) {
                                return Integer.valueOf(CoverResourceOverlay.COVER_APP_STATUS_BAR_PX);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
                return chain.proceed();
            });
        }

        module.hook(Resources.class.getDeclaredMethod("getDimension", int.class)).intercept(chain -> {
            if (resourcePatchActive()) {
                try {
                    Resources r = (Resources) chain.getThisObject();
                    if ((!voiceAssistProcess || isCompatActivityResources(r)) && !isInnerScreenResources(r)) {
                        int id = (Integer) chain.getArg(0);
                        if (isStatusHeightResource(r, id)) {
                            return Float.valueOf((float) CoverResourceOverlay.COVER_APP_STATUS_BAR_PX);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            return chain.proceed();
        });

        module.hook(Resources.class.getDeclaredMethod("getValue", int.class, TypedValue.class, boolean.class)).intercept(chain -> {
            Object result = chain.proceed();
            if (resourcePatchActive()) {
                try {
                    Resources r = (Resources) chain.getThisObject();
                    if ((!voiceAssistProcess || isCompatActivityResources(r)) && !isInnerScreenResources(r)) {
                        int id = (Integer) chain.getArg(0);
                        if (isStatusHeightResource(r, id)) {
                            ((TypedValue) chain.getArg(1)).data = (CoverResourceOverlay.COVER_APP_STATUS_BAR_PX << TypedValue.COMPLEX_MANTISSA_SHIFT)
                                    | TypedValue.COMPLEX_UNIT_PX;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            return result;
        });
    }

    private static void installClientInsetsHooks(XposedModule module) {
        int statusAndTappable = WindowInsets.Type.statusBars()
                | WindowInsets.Type.tappableElement()
                | WindowInsets.Type.displayCutout();
        int preserveTopMask = WindowInsets.Type.ime();

        try {
            module.hook(View.class.getDeclaredMethod("dispatchApplyWindowInsets", WindowInsets.class)).intercept(chain -> {
                if (!voiceAssistProcess) {
                    return chain.proceed();
                }
                Boolean prev = dispatchingCompatWindowInsets.get();
                boolean compatWin = false;
                try {
                    if (chain.getThisObject() instanceof View v) {
                        compatWin = isVoiceAssistCompatView(v);
                        dispatchingCompatWindowInsets.set(compatWin);
                        if (chain.getArg(0) instanceof WindowInsets in) {
                            windowInsetsCompatTag.put(in, compatWin);
                        }
                    }
                } catch (Throwable ignored) {
                }
                try {
                    Object out = chain.proceed();
                    if (out instanceof WindowInsets outInsets) {
                        windowInsetsCompatTag.put(outInsets, compatWin);
                    }
                    return out;
                } finally {
                    if (prev != null) {
                        dispatchingCompatWindowInsets.set(prev);
                    } else {
                        dispatchingCompatWindowInsets.remove();
                    }
                }
            });
        } catch (ReflectiveOperationException ignored) {
        }

        for (String methodName : new String[]{"getInsets", "getInsetsIgnoringVisibility"}) {
            try {
                module.hook(WindowInsets.class.getDeclaredMethod(methodName, int.class)).intercept(chain -> {
                    Object res = chain.proceed();
                    try {
                        WindowInsets wi = chain.getThisObject() instanceof WindowInsets w ? w : null;
                        if (shouldClampWindowInsets(wi) && res instanceof Insets insets && insets.top > 0
                                && insets.top <= CoverDisplay.MAX_STATUS_BAR_INSETS_TOP) {
                            int typeMask = (Integer) chain.getArg(0);
                            if ((typeMask & statusAndTappable) != 0 && (typeMask & preserveTopMask) == 0) {
                                int top = (typeMask & (WindowInsets.Type.statusBars() | WindowInsets.Type.tappableElement())) != 0
                                        ? CoverResourceOverlay.COVER_APP_STATUS_BAR_PX
                                        : 0;
                                return Insets.of(insets.left, top, insets.right, insets.bottom);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    return res;
                });
            } catch (ReflectiveOperationException ignored) {
            }
        }

        try {
            module.hook(WindowInsets.class.getDeclaredMethod("getDisplayCutout")).intercept(chain -> {
                WindowInsets wi = chain.getThisObject() instanceof WindowInsets w ? w : null;
                if (shouldClampWindowInsets(wi)) {
                    return null;
                }
                Object res = chain.proceed();
                try {
                    if (shouldExposeVoiceAssistWindowInsetsCutout(wi) && isEmptyCutout(res)) {
                        android.view.DisplayCutout fc = resolveOuterFoldedCutout(null);
                        if (fc != null) {
                            return fc;
                        }
                    }
                } catch (Throwable ignored) {
                }
                return res;
            });
        } catch (ReflectiveOperationException ignored) {
        }

        for (String legacyName : new String[]{"getSystemWindowInsetTop", "getStableInsetTop"}) {
            try {
                module.hook(WindowInsets.class.getDeclaredMethod(legacyName)).intercept(chain -> {
                    Object res = chain.proceed();
                    try {
                        WindowInsets wi = chain.getThisObject() instanceof WindowInsets w ? w : null;
                        if (shouldClampWindowInsets(wi) && res instanceof Integer top
                                && top > 0 && top <= CoverDisplay.MAX_STATUS_BAR_INSETS_TOP) {
                            return Integer.valueOf(CoverResourceOverlay.COVER_APP_STATUS_BAR_PX);
                        }
                    } catch (Throwable ignored) {
                    }
                    return res;
                });
            } catch (ReflectiveOperationException ignored) {
            }
        }

        try {
            module.hook(View.class.getDeclaredMethod("fitSystemWindows", Rect.class)).intercept(chain -> {
                try {
                    View v = chain.getThisObject() instanceof View view ? view : null;
                    boolean active = voiceAssistProcess
                            ? (v != null && isVoiceAssistCompatView(v)
                                    && !nativeCoverActivityActive
                                    && FrameworkRuntime.active()
                                    && ModuleConfig.immersiveEnabled()
                                    && ModuleConfig.compatEnabled(VOICEASSIST_PKG)
                                    && outerProcess
                                    && portraitProcess
                                    && !isInnerScreenContext(v.getContext()))
                            : (resourcePatchActive() && (v == null || !isInnerScreenContext(v.getContext())));
                    if (active && chain.getArg(0) instanceof Rect rect
                            && rect.top > 0 && rect.top <= CoverDisplay.MAX_STATUS_BAR_INSETS_TOP) {
                        Rect adjusted = new Rect(rect);
                        adjusted.top = CoverResourceOverlay.COVER_APP_STATUS_BAR_PX;
                        return chain.proceed(new Object[]{adjusted});
                    }
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static boolean isStatusHeightResource(Resources r, int id) {
        if (id == 0 || r == null) return false;
        long cacheKey = ((id >>> 24) == 0x01)
                ? (id & 0xffffffffL)
                : ((((long) System.identityHashCode(r)) << 32) | (id & 0xffffffffL));
        Boolean cached = statusHeightIdCache.get(cacheKey);
        if (cached != null) return cached;
        try {
            String entry = r.getResourceEntryName(id);
            boolean match = isStatusHeightName(entry);
            if (statusHeightIdCache.size() < 1024) {
                statusHeightIdCache.put(cacheKey, match);
            }
            return match;
        } catch (Throwable e) {
            return false;
        }
    }

    private static boolean isStatusHeightName(String id) {
        if (id == null) return false;
        return id.equals("status_bar_height")
                || id.startsWith("status_bar_height_")
                || id.equals("all_in_one_status_bar_height")
                || id.equals("miui_status_bar_height");
    }

    private static void refreshProcessDisplayFallback() {
        try {
            Object dmg = Class.forName("android.hardware.display.DisplayManagerGlobal")
                    .getDeclaredMethod("getInstance").invoke(null);
            if (dmg != null) {
                Display d = (Display) dmg.getClass()
                        .getDeclaredMethod("getRealDisplay", int.class)
                        .invoke(dmg, Display.DEFAULT_DISPLAY);
                if (d != null) {
                    setDisplay(d);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void homeEvent(Activity a, boolean resumed) {
        if (!"com.miui.fliphome".equals(a.getPackageName())
                || !"com.miui.fliphome.FlipLauncher".equals(a.getClass().getName())) {
            return;
        }
        Display d = a.getDisplay();
        if (d == null || d.getState() != Display.STATE_ON) return;
        a.sendBroadcast(new Intent("io.github.jabe1122.mixflip2cover.HOME")
                .setPackage("com.android.systemui")
                .putExtra("resumed", resumed));
    }

    private static void updateContext(Context c) {
        if (c == null) return;
        if ("com.miui.fliphome".equals(packageName)) {
            WidgetAnchor.observe(c);
        }
        Display d = null;
        try {
            d = c.getDisplay();
        } catch (Throwable ignored) {
        }
        if (d == null) {
            DisplayManager dm = c.getSystemService(DisplayManager.class);
            if (dm != null) d = dm.getDisplay(Display.DEFAULT_DISPLAY);
        }
        setDisplay(d);
    }

    private static void setDisplay(Display d) {
        if (d == null) {
            outerProcess = portraitProcess = false;
            return;
        }
        outerProcess = CoverDisplay.isOuterDisplay(d);
        portraitProcess = CoverDisplay.isOuterPortraitDisplay(d);
    }

    private static void updateMode(Activity a) {
        if (a != null) {
            setDisplay(a.getDisplay());
        }
    }

    private static void onActivityPreActive(Activity a) {
        if (a == null) return;
        updateMode(a);
        boolean nativeCover = outerProcess && nativeCoverCompatEnabled() && isNativeCoverFullscreenActivity(a);
        setNativeCoverActive(nativeCover);
        if (voiceAssistProcess) {
            try {
                Resources actRes = a.getResources();
                Resources appRes = a.getApplicationContext() != null ? a.getApplicationContext().getResources() : null;
                if (outerProcess && !nativeCover && actRes != null && actRes != appRes && actRes != Resources.getSystem()) {
                    compatActivityResources.add(actRes);
                } else if (actRes != null) {
                    compatActivityResources.remove(actRes);
                }
            } catch (Throwable ignored) {
            }
            voiceAssistCompatActivityActive = outerProcess && !nativeCover;
        }
        if (nativeCover) {
            ensureNativeCoverMetrics(a);
        }
    }

    private static void recomputeVoiceAssistCompatActivityState(Activity leaving) {
        if (!voiceAssistProcess) return;
        boolean anyCompat = false;
        ArrayList<Activity> candidates;
        synchronized (startedActivities) {
            candidates = new ArrayList<>(startedActivities);
        }
        for (Activity act : candidates) {
            if (act == null || act == leaving || act.isDestroyed() || act.isFinishing()) {
                continue;
            }
            if (CoverDisplay.isOuterDisplay(act.getDisplay()) && !isNativeCoverFullscreenActivity(act)) {
                anyCompat = true;
                break;
            }
        }
        voiceAssistCompatActivityActive = anyCompat;
    }

    /** Re-evaluates the process flag when a natively adapted Activity pauses, finishes or is destroyed. */
    private static void recomputeNativeCoverActivityState(Activity leaving) {
        if (leaving != null) {
            updateMode(leaving);
        } else {
            refreshProcessDisplayFallback();
        }
        if (!nativeCoverCompatEnabled()) {
            setNativeCoverActive(false);
            return;
        }
        boolean anyNativeCover = false;
        for (Activity act : new ArrayList<>(states.keySet())) {
            if (act == null || act == leaving || act.isDestroyed() || act.isFinishing() || !act.hasWindowFocus()) {
                continue;
            }
            if (CoverDisplay.isOuterDisplay(act.getDisplay()) && isNativeCoverFullscreenActivity(act)) {
                anyNativeCover = true;
                break;
            }
        }
        setNativeCoverActive(anyNativeCover);
    }

    /**
     * NetEase Cloud Music lays out its cover player from the DisplayCutout it reads in the obfuscated
     * MiMiniPlayerActivity.H8() during onAttachedToWindow. After insets change (fold/rotation) it is invoked again
     * so the player re-reads the real cutout. Verified on 9.6.x only; other versions are left alone.
     */
    private static void notifyMiMiniPlayerCutout(Activity a) {
        if (a == null || !"com.netease.cloudmusic.activity.MiMiniPlayerActivity".equals(a.getClass().getName())
                || !versionIn(NETEASE_MIN, NETEASE_MAX_EXCLUSIVE)) {
            return;
        }
        try {
            java.lang.reflect.Method m = a.getClass().getDeclaredMethod("H8");
            if (m.getReturnType() != void.class) return;
            m.setAccessible(true);
            m.invoke(a);
        } catch (Throwable ignored) {
        }
    }

    private static void attach(Activity a) {
        if (a == null) return;
        onActivityPreActive(a);
        State state = states.get(a);
        if (state == null) {
            state = new State(a);
            states.put(a, state);
        }
        state.apply();
    }

    private static long modeKey(Activity a) {
        Display d = a.getDisplay();
        if (d == null) return -1;
        Display.Mode m = d.getMode();
        if (m == null) return -1;
        long key = m.getPhysicalWidth() * 10000000L + m.getPhysicalHeight() * 10L + d.getRotation();
        return key * 16
                + (FrameworkRuntime.active() ? 8 : 0)
                + (ModuleConfig.immersiveEnabled() ? 4 : 0)
                + (ModuleConfig.compatEnabled(packageName) ? 2 : 0)
                + (ModuleConfig.hideAppNames() ? 1 : 0);
    }

    private static final class State implements DisplayManager.DisplayListener {
        final WeakReference<Activity> activity;
        final DisplayManager manager;
        final ViewTreeObserver.OnWindowFocusChangeListener focusListener;
        Integer desktopMargin, desktopPadding;
        long appliedMode = Long.MIN_VALUE;

        State(Activity a) {
            activity = new WeakReference<>(a);
            manager = a.getSystemService(DisplayManager.class);
            if (manager != null) {
                manager.registerDisplayListener(this, new Handler(Looper.getMainLooper()));
            }
            focusListener = f -> {
                if (f) {
                    Activity act = activity.get();
                    if (act != null) onActivityPreActive(act);
                    apply();
                }
            };
            if (a.getWindow() != null && a.getWindow().getDecorView() != null) {
                a.getWindow().getDecorView().getViewTreeObserver().addOnWindowFocusChangeListener(focusListener);
            }
        }

        void apply() {
            Activity a = activity.get();
            if (a == null || a.isDestroyed() || a.getWindow() == null || a.getWindow().getDecorView() == null) {
                stop();
                return;
            }
            updateMode(a);
            boolean compatActive = nativeCoverCompatEnabled();
            boolean nativeActivity = isNativeCoverFullscreenActivity(a);
            boolean nativeCover = compatActive && nativeActivity;
            if ((!outerProcess || !compatActive) && nativeCoverActivityActive) {
                setNativeCoverActive(false);
            } else if (nativeCover && outerProcess && nativeCoverActivityActive) {
                ensureNativeCoverMetrics(a);
            }
            a.getWindow().getDecorView().post(() -> {
                if (a.isDestroyed() || a.getWindow() == null || a.getWindow().getDecorView() == null) return;
                try {
                    updateMode(a);
                    boolean liveCompatActive = nativeCoverCompatEnabled();
                    boolean liveNativeActivity = isNativeCoverFullscreenActivity(a);
                    boolean liveNativeCover = liveCompatActive && liveNativeActivity;
                    long key = modeKey(a);
                    if ("com.miui.fliphome".equals(a.getPackageName())) {
                        int id = a.getResources().getIdentifier("launcher_container", "id", "com.miui.fliphome");
                        View container = a.findViewById(id);
                        if (container != null && container.getLayoutParams() instanceof ViewGroup.MarginLayoutParams lp) {
                            if (desktopMargin == null) desktopMargin = lp.topMargin;
                            boolean expanded = FrameworkRuntime.active() && ModuleConfig.immersiveEnabled();
                            int margin = expanded ? 0 : desktopMargin;
                            if (lp.topMargin != margin) {
                                lp.topMargin = margin;
                                container.setLayoutParams(lp);
                            }
                            if (container.getParent() instanceof View parent) {
                                if (desktopPadding == null) desktopPadding = parent.getPaddingTop();
                                int padding = expanded ? 0 : desktopPadding;
                                if (parent.getPaddingTop() != padding) {
                                    parent.setPadding(parent.getPaddingLeft(), padding, parent.getPaddingRight(), parent.getPaddingBottom());
                                }
                            }
                        }
                        DesktopAlignment.update(a);
                    }
                    if (liveNativeCover) {
                        if (outerProcess && nativeCoverActivityActive) {
                            ensureNativeCoverMetrics(a);
                        }
                    } else if (!liveNativeActivity) {
                        AppLayoutAdapters.update(a);
                    }
                    if (key != appliedMode) {
                        appliedMode = key;
                        a.getWindow().getDecorView().requestApplyInsets();
                        if (liveNativeCover && outerProcess) {
                            notifyMiMiniPlayerCutout(a);
                        }
                        ModLog.i(TAG, "layout " + a.getClass().getName()
                                + " outer=" + outerProcess
                                + " nativeCover=" + liveNativeCover
                                + " resourcePatch=" + resourcePatchActive());
                    }
                } catch (Throwable e) {
                    Log.w(TAG, "app layout unchanged", e);
                }
            });
        }

        void stop() {
            try {
                if (manager != null) manager.unregisterDisplayListener(this);
            } catch (Throwable ignored) {
            }
            try {
                Activity a = activity.get();
                if (a != null && a.getWindow() != null && a.getWindow().getDecorView() != null) {
                    ViewTreeObserver vto = a.getWindow().getDecorView().getViewTreeObserver();
                    if (vto != null && vto.isAlive()) {
                        vto.removeOnWindowFocusChangeListener(focusListener);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        @Override public void onDisplayAdded(int id) { apply(); }
        @Override public void onDisplayRemoved(int id) { apply(); }
        @Override
        public void onDisplayChanged(int id) {
            Activity a = activity.get();
            if (a != null && a.getDisplay() != null && a.getDisplay().getDisplayId() == id && modeKey(a) != appliedMode) {
                apply();
            }
        }
    }
}
