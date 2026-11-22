package io.github.jabe1122.mixflip2cover;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.view.DisplayCutout;
import android.view.WindowInsets;
import android.view.WindowManager;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

/**
 * System server WindowManager policy for the MIX Flip 2 cover screen.
 * Strips top statusBar and top-edge status-bar tappableElement InsetsSources for eligible cover activities
 * while preserving the shared InsetsState and inner display behavior.
 */
public final class SystemServerEntry extends XposedModule {
    private static final String TAG = "MF2C.Framework";
    static final String ACTION_COVER_QUICK_ACTION = "io.github.jabe1122.mixflip2cover.COVER_QUICK_ACTION";
    static final String EXTRA_QUICK_ACTION = "quick_action";
    private static final int MAX_TOP_BAR_SOURCE_HEIGHT = 220;
    private static final int INNER_DISPLAY_WIDTH = 1224;
    private static final int INNER_DISPLAY_HEIGHT = 2912;
    private static final int COVER_DISPLAY_WIDTH = 1208;
    private static final int COVER_DISPLAY_HEIGHT = 1392;
    private static final int COVER_SAFE_WIDTH = 810;
    private static final int FIXED_ASPECT_RATIO_STATE = 8;
    private static final int SNAPSHOT_REF_BROADCAST = 16;
    private static final Configuration EMPTY_CONFIG = resolveEmptyConfiguration();

    private static Configuration resolveEmptyConfiguration() {
        try {
            Field f = Configuration.class.getDeclaredField("EMPTY");
            f.setAccessible(true);
            Object v = f.get(null);
            return v instanceof Configuration c ? c : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private final ArrayList<HookHandle> hooks = new ArrayList<>();
    private final Map<Object, String> published = Collections.synchronizedMap(new WeakHashMap<>());
    private final Set<Object> deliveryLogged = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private final Set<Object> knownImeProcesses = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private final Map<String, Boolean> nativeFlipActivityCache = new ConcurrentHashMap<>();
    private final Map<Integer, String> uidPackageCache = new ConcurrentHashMap<>();
    private final AtomicBoolean receiversRegistered = new AtomicBoolean(false);

    private Field attrs, record, rawTypes, infoUnique, infoWidth, infoHeight, displayFocus;
    private Field serviceHandler, serviceLock, serviceRoot, serviceContext, windowConfigField;
    private Field ownerUidField, arPkgField, arComponentField, arInfoField, arFinishingField, arUserIdField;
    private Method arIsVisibleRequestedMethod, displayGetIdMethod;
    private final ThreadLocal<Boolean> sendingCompatState = new ThreadLocal<>();
    private Method getDisplay, getInfo, getMode, sourceSize, sourceAt, sourceType, getSourceFrame, addSource, sourceFrame, sourceVisibleFrame, sourceVisible, sourceIsVisible;
    private Method getDisplayCutout, setDisplayCutout, wcGetBounds, wcGetAppBounds, wcSetBounds, wcSetAppBounds, asActivityMethod;
    private Method wcSetMaxBounds, wcGetMaxBounds, wcGetRotation, wcGetDisplayRotation;
    private Method ccGetReqOverrideConfig, ccGetResolvedOverrideConfig, ccGetConfig, ccOnConfigChanged, wcGetMergedOverrideConfig;
    private Field wpcInfoField, wpcUidField, wpcHasImeServiceField, wpcLastReportedConfigField, wpcCompatCtrlField, wpcConfigRecordField, wpcInitConfigField;
    private Method wpcDispatchConfig, wpcGetActivitiesMethod;
    private Field pccProcessField, pccBoundsField, pccCurrentScaleField, pccGlobalScaleField, pccStateField, pccLastStateField;
    private Method pccSendCompatState;
    private Field winSessionField, winLastReportedConfigField, winLastReportedFramesField, sessionProcessField;
    private Field cwfFrameField, cwfDisplayFrameField, cwfParentFrameField, cwfAttachedFrameField;
    private Method mcGetGlobalConfig, mcGetOverrideConfig, mcGetMergedConfig;
    private Field infoAppWidth, infoAppHeight, infoDensityDpi, infoSmallestW, infoSmallestH, infoLargestW, infoLargestH, infoRotation;
    private Field cfgCompatWField, cfgCompatHField, cfgCompatSwField;
    private Object noCutout;
    private volatile int flipHomeUid = -1;
    private volatile int miuiHomeUid = 10151;
    private volatile int systemUiUid = -1;
    private volatile int voiceAssistUid = -1;
    private volatile int activeThirdPartyImeAppId = -1;
    private volatile String activeThirdPartyImePkg = null;
    private Method setProperty, forAllWindows, notifyInsets, layoutNeeded, traversal, rootDisplay;
    private Constructor<?> stateCopy, sourceCopy, infoCopy;
    private Field infoDisplayCutout;
    private Object miuiCompatStub;
    private Method isFlipFoldedMethod;
    private volatile Object service;
    private static final Object FOLD_TOKEN = new Object();
    private static final Object POCKET_GUARD_TOKEN = new Object();
    private static final int SENSOR_TYPE_PROXIMITY_BACK = 33171123;
    private static final int SENSOR_TYPE_OEM_POCKET = 33171095;
    private final Object foldLock = new Object();
    private Boolean lastFoldDispatched;
    private volatile boolean imeCompatApplied;
    private volatile boolean installed;
    private volatile boolean moduleUninstalled;
    private volatile ClassLoader systemServerClassLoader;
    private final Object letterboxGuard = new Object();
    private final Set<String> strippedLetterboxPackages = ConcurrentHashMap.newKeySet();
    private final Set<String> removedHideLetterboxEntries = new HashSet<>();
    private final Set<String> removedAspectBlacklistEntries = new HashSet<>();
    private final Set<Integer> appliedCoverImeDpis = ConcurrentHashMap.newKeySet();
    private volatile Object interceptActivityControllerRef;
    private Field iacTopActivityField, iacPolicyField, iacIsTopKeepedField;
    private Method policyGetFgPkgMethod;
    private Field flipPolicyNeedContinueField;
    private volatile long lastSeamlessFoldContinuityAt;
    private final Object pocketSensorLock = new Object();
    private SensorManager activePocketSensorManager;
    private SensorEventListener activePocketSensorListener;
    private volatile boolean lastCoverDisplayState;
    private volatile boolean lastCoverDisplayInitialized;
    private volatile long lastDeviceStateChangeAtMs;
    private volatile int cachedCoverDisplayRotation = -1;
    private long lastError;


    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!param.isFirstPackage()) return;
        String pkg = param.getPackageName();
        if ("android".equals(pkg) || "system".equals(pkg)) {
            try {
                if (CoverGeometry.SYSTEMUI.equals(android.app.Application.getProcessName())) return;
            } catch (Throwable ignored) {
            }
            initSystemServer(param.getClassLoader());
        }
    }

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        initSystemServer(param.getClassLoader());
    }

    private synchronized void initSystemServer(ClassLoader loader) {
        if (installed) return;
        if (!CoverDisplay.isSupportedFirmware()) {
            Log.w(TAG, "unsupported device/firmware " + Build.DEVICE + " " + Build.VERSION.INCREMENTAL
                    + "; stock framework retained");
            return;
        }
        systemServerClassLoader = loader;
        try {
            Class<?> win = loader.loadClass("com.android.server.wm.WindowState");
            Class<?> display = loader.loadClass("com.android.server.wm.DisplayContent");
            Class<?> info = Class.forName("android.view.DisplayInfo");
            Class<?> state = Class.forName("android.view.InsetsState");
            Class<?> source = Class.forName("android.view.InsetsSource");
            Class<?> wms = loader.loadClass("com.android.server.wm.WindowManagerService");
            Class<?> container = loader.loadClass("com.android.server.wm.WindowContainer");
            Class<?> token = loader.loadClass("com.android.server.wm.WindowToken");
            Class<?> policy = loader.loadClass("com.android.server.wm.InsetsPolicy");

            attrs = field(win, "mAttrs", WindowManager.LayoutParams.class);
            record = field(win, "mActivityRecord", loader.loadClass("com.android.server.wm.ActivityRecord"));
            rawTypes = field(win, "mRequestedVisibleTypes", int.class);
            getDisplay = method(win, "getDisplayContent", display);
            getInfo = method(display, "getDisplayInfo", info);
            getMode = method(win, "getWindowingMode", int.class);
            infoUnique = field(info, "uniqueId", String.class);
            infoWidth = field(info, "logicalWidth", int.class);
            infoHeight = field(info, "logicalHeight", int.class);
            displayFocus = field(display, "mCurrentFocus", win);
            sourceSize = method(state, "sourceSize", int.class);
            sourceAt = method(state, "sourceAt", source, int.class);
            sourceType = method(source, "getType", int.class);
            getSourceFrame = method(source, "getFrame", Rect.class);
            addSource = method(state, "addSource", void.class, source);
            sourceFrame = method(source, "setFrame", source, int.class, int.class, int.class, int.class);
            sourceVisibleFrame = method(source, "setVisibleFrame", source, Rect.class);
            sourceVisible = method(source, "setVisible", source, boolean.class);
            sourceIsVisible = optionalMethod(source, "isVisible", boolean.class);
            getDisplayCutout = method(state, "getDisplayCutout", DisplayCutout.class);
            setDisplayCutout = method(state, "setDisplayCutout", void.class, DisplayCutout.class);
            noCutout = field(DisplayCutout.class, "NO_CUTOUT", DisplayCutout.class).get(null);
            Class<?> wcClass = Class.forName("android.app.WindowConfiguration");
            windowConfigField = field(Configuration.class, "windowConfiguration", wcClass);
            wcGetBounds = method(wcClass, "getBounds", Rect.class);
            wcGetAppBounds = method(wcClass, "getAppBounds", Rect.class);
            wcSetBounds = method(wcClass, "setBounds", void.class, Rect.class);
            wcSetAppBounds = method(wcClass, "setAppBounds", void.class, Rect.class);
            sourceCopy = source.getDeclaredConstructor(source);
            sourceCopy.setAccessible(true);
            stateCopy = state.getDeclaredConstructor(state);
            stateCopy.setAccessible(true);
            infoCopy = info.getDeclaredConstructor(info);
            infoCopy.setAccessible(true);
            infoDisplayCutout = field(info, "displayCutout", DisplayCutout.class);
            setProperty = method(Class.forName("android.os.SystemProperties"), "set", void.class, String.class, String.class);
            serviceHandler = field(wms, "mH", loader.loadClass("com.android.server.wm.WindowManagerService$H"));
            serviceLock = field(wms, "mGlobalLock", loader.loadClass("com.android.server.wm.WindowManagerGlobalLock"));
            serviceRoot = field(wms, "mRoot", loader.loadClass("com.android.server.wm.RootWindowContainer"));
            serviceContext = field(wms, "mContext", Context.class);
            forAllWindows = method(container, "forAllWindows", void.class, Consumer.class, boolean.class);
            notifyInsets = method(win, "notifyInsetsChanged", void.class);
            layoutNeeded = method(display, "setLayoutNeeded", void.class);
            traversal = method(wms, "requestTraversal", void.class);

            Method insetsBool = method(win, "getInsetsState", state, boolean.class);
            Method insetsNoArg = method(win, "getInsetsState", state);
            Method requested = method(win, "getRequestedVisibleTypes", int.class);
            Method visible = method(win, "isRequestedVisible", boolean.class, int.class);
            Method metrics = method(policy, "getInsetsForWindowMetrics", void.class, token, state);
            Method fill = method(win, "fillInsetsState", void.class, state, boolean.class);
            Field lastReported = field(win, "mLastReportedInsetsState", state);
            Method windowMetrics = method(wms, "getWindowInsets", void.class, int.class, IBinder.class, state);
            rootDisplay = method(loader.loadClass("com.android.server.wm.RootWindowContainer"), "getDisplayContent", display, int.class);
            Method windowToken = method(display, "getWindowToken", token, IBinder.class);
            Method imeWindow = method(display, "getImeWindow", win);
            Field ownerUid = field(win, "mOwnerUid", int.class);
            ownerUidField = ownerUid;
            Field tokenType = field(token, "windowType", int.class);
            Method tokenDisplay = method(token, "getDisplayContent", display);
            Class<?> arClass = loader.loadClass("com.android.server.wm.ActivityRecord");
            Method asActivity = method(token, "asActivityRecord", arClass);
            asActivityMethod = asActivity;
            arPkgField = field(arClass, "packageName", String.class);
            arComponentField = optionalField(arClass, "mActivityComponent", ComponentName.class);
            arInfoField = optionalField(arClass, "info", ActivityInfo.class);
            arFinishingField = optionalField(arClass, "finishing", boolean.class);
            arUserIdField = optionalField(arClass, "mUserId", int.class);
            arIsVisibleRequestedMethod = optionalMethod(arClass, "isVisibleRequested", boolean.class);
            displayGetIdMethod = method(display, "getDisplayId", int.class);
            Method tokenMode = method(token, "getWindowingMode", int.class);
            Field policyDisplay = field(policy, "mDisplayContent", display);

            verifySourceCopies(state, source);
            ModuleConfig.init(this);
            CoverResourceOverlay.installCoreResourceGuard(this);
            ModLog.recordHookHealth("overlay_hooks", "ok");
            setProperty.invoke(null, FrameworkRuntime.READY, "0");

            hooks.add(hook(insetsBool).intercept(chain -> {
                Object original = chain.proceed();
                try {
                    checkDisplayTransition(service);
                    Object w = chain.getThisObject();
                    if (applies(w)) return withoutStatus(original, targetTopPxForWindow(w));
                    if (isCoverFolded() && coverImeEnabled() && isThirdPartyImeWindow(w)) {
                        ensureCoverImeWindowAttrs(w, getWindowRotation(w));
                    }
                } catch (Throwable t) {
                    error(t);
                }
                return original;
            }));
            hooks.add(hook(insetsNoArg).intercept(chain -> {
                Object original = chain.proceed();
                try {
                    checkDisplayTransition(service);
                    Object w = chain.getThisObject();
                    if (applies(w)) return withoutStatus(original, targetTopPxForWindow(w));
                    if (isCoverFolded() && coverImeEnabled() && isThirdPartyImeWindow(w)) {
                        ensureCoverImeWindowAttrs(w, getWindowRotation(w));
                    }
                } catch (Throwable t) {
                    error(t);
                }
                return original;
            }));
            try {
                Method insetsVisOverride = method(win, "getInsetsStateWithVisibilityOverride", state);
                hooks.add(hook(insetsVisOverride).intercept(chain -> {
                    Object original = chain.proceed();
                    try {
                        checkDisplayTransition(service);
                        Object w = chain.getThisObject();
                        if (applies(w)) return withoutStatus(original, targetTopPxForWindow(w));
                        if (isCoverFolded() && coverImeEnabled() && isThirdPartyImeWindow(w)) {
                            ensureCoverImeWindowAttrs(w, getWindowRotation(w));
                        }
                    } catch (Throwable t) {
                        error(t);
                    }
                    return original;
                }));
            } catch (NoSuchMethodException ignored) {
            }
            hooks.add(hook(fill).intercept(chain -> {
                Object original = chain.proceed();
                Object window = chain.getThisObject();
                try {
                    checkDisplayTransition(service);
                    if (applies(window)) {
                        int topPx = targetTopPxForWindow(window);
                        Object out = chain.getArg(0);
                        int before = (Integer) sourceSize.invoke(out);
                        stripStatus(out, topPx);
                        Object last = lastReported.get(window);
                        if (last != null && last != out) stripStatus(last, topPx);
                        if (deliveryLogged.add(window)) {
                            ModLog.i(TAG, "client Insets delivered package="
                                    + ((WindowManager.LayoutParams) attrs.get(window)).packageName
                                    + " topPx=" + topPx
                                    + " sources=" + before + "->" + sourceSize.invoke(out));
                        }
                    } else if (isCoverFolded() && coverImeEnabled() && isThirdPartyImeWindow(window)) {
                        ensureCoverImeWindowAttrs(window, getWindowRotation(window));
                        Object out = chain.getArg(0);
                        if (out != null && setDisplayCutout != null && noCutout != null) {
                            setDisplayCutout.invoke(out, noCutout);
                        }
                        Object last = lastReported.get(window);
                        if (last != null && last != out && setDisplayCutout != null && noCutout != null) {
                            setDisplayCutout.invoke(last, noCutout);
                        }
                    }
                } catch (Throwable t) {
                    error(t);
                }
                return original;
            }));
            hooks.add(hook(windowMetrics).intercept(chain -> {
                int caller = Binder.getCallingUid();
                Object original = chain.proceed();
                try {
                    checkDisplayTransition(service);
                    if (enabled() && caller % 100000 >= 10000) {
                        Object s = chain.getThisObject();
                        synchronized (serviceLock.get(s)) {
                            Object dc = rootDisplay.invoke(serviceRoot.get(s), (Integer) chain.getArg(0));
                            Object keyboard = dc == null ? null : imeWindow.invoke(dc);
                            if (keyboard != null && ownerUid.getInt(keyboard) == caller) return original;
                            Object target = dc == null ? null : windowToken.invoke(dc, chain.getArg(1));
                            int type = target == null ? 1 : tokenType.getInt(target);
                            int mode = target == null ? 1 : (Integer) tokenMode.invoke(target);
                            if (tokenPolicyApplies(target, caller)
                                    && displayEligible(dc, type, true, mode)
                                    && !skipsStatusStrippingForToken(target, caller)) {
                                stripStatus(chain.getArg(2), targetTopPxForToken(target, caller));
                            }
                        }
                    }
                } catch (Throwable t) {
                    error(t);
                }
                return original;
            }));
            hooks.add(hook(requested).intercept(chain -> {
                int original = (Integer) chain.proceed();
                try {
                    Object w = chain.getThisObject();
                    if (runtimeActive() && eligibleWindow(w)) {
                        reportFocus(w);
                        if (windowPolicyApplies(w) && !skipsStatusStrippingForWindow(w)) {
                            return FrameworkPolicy.filteredTypes(
                                    original,
                                    WindowInsets.Type.statusBars());
                        }
                    }
                } catch (Throwable t) {
                    error(t);
                }
                return original;
            }));
            hooks.add(hook(visible).intercept(chain -> {
                Object original = chain.proceed();
                try {
                    if (applies(chain.getThisObject())) {
                        int masked = FrameworkPolicy.filteredTypes(
                                (Integer) chain.getArg(0),
                                WindowInsets.Type.statusBars());
                        return (rawTypes.getInt(chain.getThisObject()) & masked) != 0;
                    }
                } catch (Throwable t) {
                    error(t);
                }
                return original;
            }));
            hooks.add(hook(metrics).intercept(chain -> {
                int caller = Binder.getCallingUid();
                Object original = chain.proceed();
                try {
                    checkDisplayTransition(service);
                    if (enabled() && caller % 100000 >= 10000 && !isThirdPartyImeUid(caller)) {
                        Object t = chain.getArg(0);
                        Object dc = t != null ? tokenDisplay.invoke(t) : policyDisplay.get(chain.getThisObject());
                        int type = t != null ? tokenType.getInt(t) : 1;
                        if (type == 2011 || type == 2012) return original;
                        int mode = t != null ? (Integer) tokenMode.invoke(t) : 1;
                        if (tokenPolicyApplies(t, caller)
                                && displayEligible(dc, type, true, mode)
                                && !skipsStatusStrippingForToken(t, caller)) {
                            stripStatus(chain.getArg(1), targetTopPxForToken(t, caller));
                        }
                    }
                } catch (Throwable t) {
                    error(t);
                }
                return original;
            }));
            for (Constructor<?> ctor : wms.getDeclaredConstructors()) {
                hooks.add(hook(ctor).intercept(chain -> {
                    Object result = chain.proceed();
                    service = chain.getThisObject();
                    try {
                        Handler h = (Handler) serviceHandler.get(service);
                        if (h != null) {
                            h.postDelayed(() -> {
                                try {
                                    Context ctx = (Context) serviceContext.get(service);
                                    if (ctx != null) {
                                        PackageManager pm = ctx.getPackageManager();
                                        if (pm != null) {
                                            try { flipHomeUid = pm.getPackageUid("com.miui.fliphome", 0) % 100000; } catch (Throwable ignored) {}
                                            try { miuiHomeUid = pm.getPackageUid("com.miui.home", 0) % 100000; } catch (Throwable ignored) {}
                                            try { systemUiUid = pm.getPackageUid("com.android.systemui", 0) % 100000; } catch (Throwable ignored) {}
                                            try { voiceAssistUid = pm.getPackageUid("com.miui.voiceassist", 0) % 100000; } catch (Throwable ignored) {}
                                        }
                                    }
                                    CoverResourceOverlay.observe(ctx);
                                    boolean foldedNow = isCoverFolded();
                                    CoverResourceOverlay.syncForFoldState(foldedNow);
                                    CoverImeGuard.setRotationChangeListener(() -> syncImeProcessesForFoldState(service, isCoverFolded()));
                                    CoverImeGuard.observe(ctx);
                                    refreshActiveThirdPartyImeUid(ctx);
                                    registerSystemReceivers(ctx, h);
                                    syncImeProcessesForFoldState(service, foldedNow);
                                } catch (Throwable bootInitErr) {
                                    Log.w(TAG, "post-WMS boot init failed", bootInitErr);
                                }
                            }, 3000L);
                        }
                    } catch (Throwable ignored) {
                    }
                    Log.i(TAG, "WMS attached; cover policy available");
                    return result;
                }));
            }
            for (String name : new String[]{"WindowState", "DisplayPolicy", "InsetsPolicy", "InsetsStateController", "InsetsSourceProvider"}) {
                Class<?> c = loader.loadClass("com.android.server.wm." + name);
                for (Method m : c.getDeclaredMethods()) {
                    String n = m.getName();
                    if (n.contains("Insets") || n.contains("ControlTarget") || n.equals("layoutWindowLw")
                            || n.equals("applyPostLayoutPolicyLw") || n.equals("updateSystemBars")
                            || n.equals("updateClientVisibility") || n.equals("setClientVisible")
                            || n.equals("updateVisibility") || n.equals("reportResized")) {
                        if (!Modifier.isAbstract(m.getModifiers()) && !Modifier.isNative(m.getModifiers())) {
                            deoptimize(m);
                        }
                    }
                }
            }
            for (Method m : wms.getDeclaredMethods()) {
                String n = m.getName();
                if (n.equals("relayoutWindow") || n.equals("addWindow") || n.equals("getWindowInsets")) {
                    deoptimize(m);
                }
            }
            try {
                installFoldStateObserver(wms);
            } catch (Throwable foldErr) {
                Log.w(TAG, "fold state observer hook skipped", foldErr);
            }
            try {
                installUniversalCoverAppCompat(loader);
            } catch (Throwable compatErr) {
                Log.w(TAG, "universal cover app compat hook skipped", compatErr);
            }
            ModuleConfig.addListener(this::configurationChanged);
            installed = true;
            setProperty.invoke(null, FrameworkRuntime.READY, FrameworkRuntime.VERSION);
            ModLog.recordHookHealth("insets_hooks", "ok");
            ModLog.recordHookHealth("system_server_init", "ok");
            Log.i(TAG, "cover Insets policy installed (" + BuildConfig.VERSION_NAME + ")");
        } catch (Throwable t) {
            installed = false;
            ModLog.recordHookHealth("system_server_init", "err:" + t.getClass().getSimpleName());
            for (HookHandle h : hooks) {
                try {
                    h.unhook();
                } catch (Throwable ignored) {
                }
            }
            hooks.clear();
            try {
                if (setProperty != null) setProperty.invoke(null, FrameworkRuntime.READY, "0");
            } catch (Throwable ignored) {
            }
            Log.e(TAG, "validation failed; all framework hooks rolled back", t);
        }
    }

    private boolean runtimeActive() {
        return installed && !moduleUninstalled && FrameworkRuntime.active();
    }

    private boolean enabled() {
        return runtimeActive() && ModuleConfig.immersiveEnabled();
    }

    private boolean appCompatEnabled(String pkg) {
        if (!enabled()) return false;
        if (pkg == null || pkg.isEmpty()) return ModuleConfig.universalCompatEnabled();
        return ModuleConfig.compatEnabled(pkg);
    }

    private boolean coverImeEnabled() {
        return runtimeActive() && ModuleConfig.coverThirdPartyImeEnabled();
    }

    /**
     * system_server receivers:
     * - ACTION_SHUTDOWN / QUICKBOOT_POWEROFF: undo every persistent change so a reboot without this module
     *   (disabled/uninstalled) starts from the stock state.
     * - ACTION_RESTORE: explicit "restore system defaults" from the settings app. The broadcast is exported, so
     *   the sender identity (shared via BroadcastOptions) must be this module's own uid.
     * - package changes: drop cached manifest lookups and restore persistent state immediately if this module APK
     *   is uninstalled (`!EXTRA_REPLACING`).
     * - IME settings: refresh the cached third-party IME uid off the WindowManager lock.
     */
    private void registerSystemReceivers(Context ctx, Handler h) {
        if (ctx == null || !receiversRegistered.compareAndSet(false, true)) return;
        ModLog.registerLogCollector(ctx, "system_server");
        try {
            android.content.IntentFilter shutdownFilter = new android.content.IntentFilter();
            shutdownFilter.addAction(Intent.ACTION_SHUTDOWN);
            shutdownFilter.addAction("android.intent.action.QUICKBOOT_POWEROFF");
            ctx.registerReceiver(new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    Log.i(TAG, "shutdown: removing persistent system changes");
                    cancelFoldPocketGuard();
                    CoverResourceOverlay.onShutdown();
                    CoverImeGuard.onShutdown(ctx);
                    restoreAllPackageHideLetterboxes(systemServerClassLoader);
                }
            }, shutdownFilter, null, h, Context.RECEIVER_NOT_EXPORTED);
        } catch (Throwable t) {
            Log.w(TAG, "shutdown receiver unavailable", t);
        }
        try {
            ctx.registerReceiver(new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    int sender = getSentFromUid();
                    int moduleUid;
                    try {
                        moduleUid = ctx.getPackageManager().getPackageUid(BuildConfig.APPLICATION_ID,
                                PackageManager.PackageInfoFlags.of(0));
                    } catch (Throwable t) {
                        return;
                    }
                    if (sender < 0 || sender % 100000 != moduleUid % 100000) {
                        Log.w(TAG, "ignored restore request from uid " + sender);
                        return;
                    }
                    Log.i(TAG, "restore system defaults requested");
                    cancelFoldPocketGuard();
                    CoverResourceOverlay.removeAll();
                    CoverImeGuard.restoreSystem(ctx);
                    restoreAllPackageHideLetterboxes(systemServerClassLoader);
                    syncImeProcessesForFoldState(service, isCoverFolded());
                    configurationChanged();
                }
            }, new android.content.IntentFilter(FrameworkRuntime.ACTION_RESTORE), null, h, Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            Log.w(TAG, "restore receiver unavailable", t);
        }
        try {
            ctx.registerReceiver(new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    if (intent == null || !ACTION_COVER_QUICK_ACTION.equals(intent.getAction())) return;
                    int sender = getSentFromUid();
                    if (!isTrustedCoverQuickActionSender(ctx, sender)) {
                        Log.w(TAG, "ignored cover quick action from uid " + sender);
                        return;
                    }
                    String action = intent.getStringExtra(EXTRA_QUICK_ACTION);
                    handleCoverQuickAction(ctx, action);
                }
            }, new android.content.IntentFilter(ACTION_COVER_QUICK_ACTION), null, h, Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            Log.w(TAG, "cover quick action receiver unavailable", t);
        }
        try {
            android.content.IntentFilter pkgFilter = new android.content.IntentFilter();
            pkgFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
            pkgFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
            pkgFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
            pkgFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            pkgFilter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
            pkgFilter.addDataScheme("package");
            ctx.registerReceiver(new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    nativeFlipActivityCache.clear();
                    uidPackageCache.clear();
                    if (intent == null || intent.getData() == null) return;
                    String pkg = intent.getData().getSchemeSpecificPart();
                    if (!BuildConfig.APPLICATION_ID.equals(pkg)) return;
                    String action = intent.getAction();
                    if (Intent.ACTION_PACKAGE_REMOVED.equals(action)
                            || Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(action)) {
                        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
                        Log.i(TAG, "module package uninstalled: restoring persistent system state");
                        moduleUninstalled = true;
                        cancelFoldPocketGuard();
                        CoverResourceOverlay.setUninstalled(true);
                        CoverImeGuard.setUninstalled(ctx, true);
                        restoreAllPackageHideLetterboxes(systemServerClassLoader);
                        syncImeProcessesForFoldState(service, isCoverFolded());
                        configurationChanged();
                    } else if (Intent.ACTION_PACKAGE_ADDED.equals(action)
                            || Intent.ACTION_PACKAGE_REPLACED.equals(action)) {
                        if (moduleUninstalled) {
                            moduleUninstalled = false;
                            CoverResourceOverlay.setUninstalled(false);
                            CoverImeGuard.setUninstalled(ctx, false);
                            CoverResourceOverlay.syncForFoldState(isCoverFolded());
                            CoverImeGuard.sync(ctx);
                            configurationChanged();
                        }
                    }
                }
            }, pkgFilter, null, h, Context.RECEIVER_NOT_EXPORTED);
        } catch (Throwable t) {
            Log.w(TAG, "package receiver unavailable", t);
        }
        try {
            android.database.ContentObserver imeObserver = new android.database.ContentObserver(h) {
                @Override
                public void onChange(boolean selfChange) {
                    refreshActiveThirdPartyImeUid(ctx);
                }
            };
            android.content.ContentResolver cr = ctx.getContentResolver();
            cr.registerContentObserver(android.provider.Settings.Secure.getUriFor(
                    android.provider.Settings.Secure.DEFAULT_INPUT_METHOD), false, imeObserver);
            cr.registerContentObserver(android.provider.Settings.Secure.getUriFor(
                    android.provider.Settings.Secure.ENABLED_INPUT_METHODS), false, imeObserver);
            cr.registerContentObserver(android.provider.Settings.Secure.getUriFor(
                    "internal_screen_input_method_in_flip"), false, imeObserver);
        } catch (Throwable t) {
            Log.w(TAG, "IME settings observer unavailable", t);
        }
    }

    private boolean isTrustedCoverQuickActionSender(Context ctx, int sender) {
        if (sender < 0 || ctx == null) return false;
        int appId = sender % 100000;
        int cachedFlipUid = flipHomeUid;
        if (cachedFlipUid > 0 && appId == (cachedFlipUid % 100000)) {
            return true;
        }
        int cachedSysUiUid = systemUiUid;
        if (cachedSysUiUid > 0 && appId == (cachedSysUiUid % 100000)) {
            return true;
        }
        try {
            PackageManager pm = ctx.getPackageManager();
            if (pm != null) {
                try {
                    int fhUid = pm.getPackageUid(CoverGeometry.FLIPHOME, PackageManager.PackageInfoFlags.of(0));
                    if (fhUid > 0) {
                        flipHomeUid = fhUid;
                        if (appId == (fhUid % 100000)) return true;
                    }
                } catch (Throwable ignored) {
                }
                try {
                    int suUid = pm.getPackageUid(CoverGeometry.SYSTEMUI, PackageManager.PackageInfoFlags.of(0));
                    if (suUid > 0) {
                        systemUiUid = suUid;
                        if (appId == (suUid % 100000)) return true;
                    }
                } catch (Throwable ignored) {
                }
                try {
                    int modUid = pm.getPackageUid(BuildConfig.APPLICATION_ID, PackageManager.PackageInfoFlags.of(0));
                    if (modUid > 0 && appId == (modUid % 100000)) return true;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void handleCoverQuickAction(Context ctx, String action) {
        if (ctx == null || action == null || action.isEmpty()) return;
        if (!isCoverFolded()) {
            ModLog.i(TAG, "ignored cover quick action while unfolded: " + action);
            return;
        }
        int displayId = resolveActiveCoverDisplayId(ctx);
        if (action.startsWith("app:")) {
            launchCoverTargetApp(ctx, action.substring(4), displayId);
            return;
        }
        switch (action) {
            case ModuleConfig.QA_BACK -> injectCoverKeyEvent(ctx, android.view.KeyEvent.KEYCODE_BACK, displayId);
            case ModuleConfig.QA_RECENTS -> injectCoverKeyEvent(ctx, android.view.KeyEvent.KEYCODE_APP_SWITCH, displayId);
            case ModuleConfig.QA_HOME -> {
                if (!injectCoverKeyEvent(ctx, android.view.KeyEvent.KEYCODE_HOME, displayId)) {
                    try {
                        Intent home = new Intent(Intent.ACTION_MAIN)
                                .addCategory(Intent.CATEGORY_HOME)
                                .setPackage(CoverGeometry.FLIPHOME)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        android.app.ActivityOptions ao = android.app.ActivityOptions.makeBasic();
                        ao.setLaunchDisplayId(displayId);
                        startActivityForUser(ctx, home, ao.toBundle(), resolveCurrentUserId());
                    } catch (Throwable t) {
                        Log.w(TAG, "cover home fallback failed", t);
                    }
                }
            }
            case ModuleConfig.QA_SCREENSHOT -> injectCoverKeyEvent(ctx, android.view.KeyEvent.KEYCODE_SYSRQ, displayId);
            case ModuleConfig.QA_LOCK -> {
                try {
                    android.os.PowerManager pm = ctx.getSystemService(android.os.PowerManager.class);
                    if (pm != null) {
                        Method goToSleep = android.os.PowerManager.class.getMethod("goToSleep", long.class);
                        goToSleep.invoke(pm, SystemClock.uptimeMillis());
                        ModLog.i(TAG, "cover quick action executed: lock");
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "cover lock quick action failed", t);
                }
            }
            case ModuleConfig.QA_ROTATION_LOCK -> handleCoverRotationLock(ctx);
            case ModuleConfig.QA_MEDIA_PLAY_PAUSE -> handleCoverMediaPlayPause(ctx, displayId);
            case ModuleConfig.QA_BRIGHTNESS -> handleCoverBrightnessCycle(ctx);
            case ModuleConfig.QA_SCAN -> handleCoverScan(ctx, displayId);
            default -> Log.w(TAG, "unknown cover quick action: " + action);
        }
    }

    private int resolveCurrentUserId() {
        try {
            Object u = android.app.ActivityManager.class.getMethod("getCurrentUser").invoke(null);
            if (u instanceof Integer id && id >= 0) return id;
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private int readSystemSettingInt(android.content.ContentResolver cr, String key, int def, int userId) {
        if (cr == null) return def;
        try {
            Method m = android.provider.Settings.System.class.getMethod(
                    "getIntForUser", android.content.ContentResolver.class, String.class, int.class, int.class);
            Object v = m.invoke(null, cr, key, def, userId);
            if (v instanceof Integer i) return i;
        } catch (Throwable ignored) {
        }
        return android.provider.Settings.System.getInt(cr, key, def);
    }

    private void writeSystemSettingInt(android.content.ContentResolver cr, String key, int value, int userId) {
        if (cr == null) return;
        try {
            Method m = android.provider.Settings.System.class.getMethod(
                    "putIntForUser", android.content.ContentResolver.class, String.class, int.class, int.class);
            m.invoke(null, cr, key, value, userId);
            return;
        } catch (Throwable ignored) {
        }
        try {
            android.provider.Settings.System.putInt(cr, key, value);
        } catch (Throwable ignored) {
        }
    }

    private void handleCoverRotationLock(Context ctx) {
        try {
            android.content.ContentResolver cr = ctx.getContentResolver();
            int userId = resolveCurrentUserId();
            int currentAuto = readSystemSettingInt(cr, android.provider.Settings.System.ACCELEROMETER_ROTATION, 1, userId);
            int nextAuto = currentAuto == 0 ? 1 : 0;
            int rot = resolveActiveCoverDisplayRotation(ctx);
            if (nextAuto == 0 && rot >= 0) {
                writeSystemSettingInt(cr, android.provider.Settings.System.USER_ROTATION, rot, userId);
            }
            writeSystemSettingInt(cr, android.provider.Settings.System.ACCELEROMETER_ROTATION, nextAuto, userId);
            ModLog.i(TAG, "cover quick action executed: rotation_lock auto=" + nextAuto + " rot=" + rot);
        } catch (Throwable t) {
            Log.w(TAG, "cover rotation_lock quick action failed", t);
        }
    }

    private void handleCoverMediaPlayPause(Context ctx, int displayId) {
        try {
            android.media.AudioManager am = ctx.getSystemService(android.media.AudioManager.class);
            if (am != null) {
                long now = SystemClock.uptimeMillis();
                am.dispatchMediaKeyEvent(new android.view.KeyEvent(
                        now, now, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0));
                am.dispatchMediaKeyEvent(new android.view.KeyEvent(
                        now, now + 8L, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0));
                ModLog.i(TAG, "cover quick action executed: media_play_pause");
                return;
            }
        } catch (Throwable t) {
            Log.w(TAG, "AudioManager media_play_pause dispatch failed; falling back to input inject", t);
        }
        injectCoverKeyEvent(ctx, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, displayId);
    }

    private void handleCoverBrightnessCycle(Context ctx) {
        try {
            android.content.ContentResolver cr = ctx.getContentResolver();
            int userId = resolveCurrentUserId();
            int maxVal = CoverDisplay.maxScreenBrightnessInt();
            int mode = readSystemSettingInt(
                    cr,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
                    userId);
            int level = readSystemSettingInt(
                    cr,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS,
                    Math.round(maxVal * 0.55f),
                    userId);
            int next = CoverDisplay.nextCoverBrightnessLevel(mode, level, maxVal);
            String stage;
            if (next < 0) {
                writeSystemSettingInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                        android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC, userId);
                stage = "AUTO";
            } else {
                if (mode != android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
                    writeSystemSettingInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                            android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL, userId);
                }
                writeSystemSettingInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS, next, userId);
                stage = next + "/" + maxVal;
            }
            ModLog.i(TAG, "cover quick action executed: brightness=" + stage);
        } catch (Throwable t) {
            Log.w(TAG, "cover brightness quick action failed", t);
        }
    }

    private void handleCoverScan(Context ctx, int displayId) {
        try {
            int userId = resolveCurrentUserId();
            PackageManager pm = resolvePackageManagerForUser(ctx, userId);
            boolean hasWeChat = false;
            if (pm != null) {
                try {
                    pm.getPackageInfo("com.tencent.mm", 0);
                    hasWeChat = true;
                } catch (Throwable ignored) {
                }
            }
            android.app.ActivityOptions ao = android.app.ActivityOptions.makeBasic();
            ao.setLaunchDisplayId(displayId);
            if (hasWeChat) {
                Intent scan = new Intent(Intent.ACTION_MAIN)
                        .setComponent(new ComponentName("com.tencent.mm", "com.tencent.mm.ui.LauncherUI"))
                        .putExtra("LauncherUI.From.Scaner.Shortcut", true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivityForUser(ctx, scan, ao.toBundle(), userId);
                ModLog.i(TAG, "cover quick action executed: scan (wechat)");
            } else {
                Intent alipayScan = new Intent(
                        Intent.ACTION_VIEW,
                        android.net.Uri.parse("alipays://platformapi/startapp?saId=10000007"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivityForUser(ctx, alipayScan, ao.toBundle(), userId);
                ModLog.i(TAG, "cover quick action executed: scan (alipay)");
            }
        } catch (Throwable t) {
            Log.w(TAG, "cover scan quick action failed", t);
        }
    }

    private void launchCoverTargetApp(Context ctx, String target, int displayId) {
        try {
            String pkg = ModuleConfig.extractAppTargetPackage(target);
            if (pkg == null || pkg.isEmpty()) return;
            int userId = ModuleConfig.extractAppTargetUserId(target);
            if (userId <= 0) userId = resolveCurrentUserId();
            PackageManager pm = resolvePackageManagerForUser(ctx, userId);
            Intent launch = pm != null ? pm.getLaunchIntentForPackage(pkg) : null;
            if (launch == null) {
                launch = new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setPackage(pkg);
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            android.app.ActivityOptions ao = android.app.ActivityOptions.makeBasic();
            ao.setLaunchDisplayId(displayId);
            startActivityForUser(ctx, launch, ao.toBundle(), userId);
            ModLog.i(TAG, "cover quick action launched app=" + pkg + " user=" + userId);
        } catch (Throwable t) {
            Log.w(TAG, "cover app launch fallback failed: " + target, t);
        }
    }

    private void startActivityForUser(Context ctx, Intent intent, Bundle options, int userId) throws Throwable {
        try {
            Object uh = android.os.UserHandle.class.getMethod("of", int.class).invoke(null, Math.max(0, userId));
            if (uh != null) {
                Method m = Context.class.getMethod(
                        "startActivityAsUser", Intent.class, Bundle.class, android.os.UserHandle.class);
                m.invoke(ctx, intent, options, uh);
                return;
            }
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw cause != null ? cause : e;
        } catch (NoSuchMethodException e) {
            if (userId > 0) {
                throw e;
            }
        }
        if (userId > 0) {
            throw new IllegalStateException("Cannot resolve UserHandle for user " + userId);
        }
        ctx.startActivity(intent, options);
    }

    private int resolveActiveCoverDisplayRotation(Context ctx) {
        try {
            android.hardware.display.DisplayManager dm =
                    ctx.getSystemService(android.hardware.display.DisplayManager.class);
            if (dm != null) {
                for (android.view.Display d : dm.getDisplays()) {
                    if (CoverDisplay.isOuterDisplay(d)) {
                        return d.getRotation();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        int cached = cachedCoverDisplayRotation;
        return cached >= 0 ? cached : 0;
    }

    private int resolveActiveCoverDisplayId(Context ctx) {
        try {
            android.hardware.display.DisplayManager dm =
                    ctx.getSystemService(android.hardware.display.DisplayManager.class);
            if (dm != null) {
                for (android.view.Display d : dm.getDisplays()) {
                    if (CoverDisplay.isOuterDisplay(d)) {
                        return d.getDisplayId();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return android.view.Display.DEFAULT_DISPLAY;
    }

    private boolean injectCoverKeyEvent(Context ctx, int keyCode, int displayId) {
        try {
            long now = SystemClock.uptimeMillis();
            int flags = android.view.KeyEvent.FLAG_FROM_SYSTEM | android.view.KeyEvent.FLAG_VIRTUAL_HARD_KEY;
            android.view.KeyEvent down = new android.view.KeyEvent(
                    now, now, android.view.KeyEvent.ACTION_DOWN, keyCode, 0, 0,
                    android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags,
                    android.view.InputDevice.SOURCE_KEYBOARD);
            android.view.KeyEvent up = new android.view.KeyEvent(
                    now, now + 8L, android.view.KeyEvent.ACTION_UP, keyCode, 0, 0,
                    android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags,
                    android.view.InputDevice.SOURCE_KEYBOARD);
            try {
                Method setDisplayId = android.view.InputEvent.class.getMethod("setDisplayId", int.class);
                setDisplayId.invoke(down, displayId);
                setDisplayId.invoke(up, displayId);
            } catch (Throwable ignored) {
            }

            Object target = null;
            Method inject = null;
            try {
                Class<?> imgClass = Class.forName("android.hardware.input.InputManagerGlobal");
                target = imgClass.getMethod("getInstance").invoke(null);
                inject = imgClass.getMethod("injectInputEvent", android.view.InputEvent.class, int.class);
            } catch (Throwable ignored) {
            }
            if (target == null || inject == null) {
                android.hardware.input.InputManager im =
                        ctx.getSystemService(android.hardware.input.InputManager.class);
                if (im != null) {
                    target = im;
                    inject = android.hardware.input.InputManager.class.getMethod(
                            "injectInputEvent", android.view.InputEvent.class, int.class);
                }
            }
            if (target != null && inject != null) {
                boolean okDown = Boolean.TRUE.equals(inject.invoke(target, down, 0));
                boolean okUp = Boolean.TRUE.equals(inject.invoke(target, up, 0));
                ModLog.i(TAG, "cover quick key injected keyCode=" + keyCode + " displayId=" + displayId
                        + " ok=" + (okDown && okUp));
                return okDown && okUp;
            }
        } catch (Throwable t) {
            Log.w(TAG, "injectCoverKeyEvent failed for keyCode=" + keyCode, t);
        }
        return false;
    }

    private String windowPackageName(Object window) throws ReflectiveOperationException {
        if (window == null) return null;
        WindowManager.LayoutParams p = (WindowManager.LayoutParams) attrs.get(window);
        if (p != null && p.packageName != null && !p.packageName.isEmpty()) {
            return p.packageName;
        }
        if (record != null && arPkgField != null) {
            Object ar = record.get(window);
            if (ar != null) {
                return (String) arPkgField.get(ar);
            }
        }
        return null;
    }

    private boolean windowPolicyApplies(Object window) throws ReflectiveOperationException {
        String pkg = windowPackageName(window);
        if ("com.miui.fliphome".equals(pkg)) {
            return enabled();
        }
        return appCompatEnabled(pkg);
    }

    private String resolveTokenPackage(Object token, int callerUid) {
        try {
            if (token != null && asActivityMethod != null && arPkgField != null) {
                Object ar = asActivityMethod.invoke(token);
                if (ar != null) {
                    String pkg = (String) arPkgField.get(ar);
                    if (pkg != null && !pkg.isEmpty()) return pkg;
                }
            }
            int appId = callerUid % 100000;
            if (appId > 0) {
                if (appId == flipHomeUid) return "com.miui.fliphome";
                if (appId == voiceAssistUid) return "com.miui.voiceassist";
                if (appId == systemUiUid) return "com.android.systemui";
                if (appId == miuiHomeUid) return "com.miui.home";
                String cached = uidPackageCache.get(appId);
                if (cached != null) return cached;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private boolean tokenPolicyApplies(Object token, int callerUid) {
        int appId = callerUid % 100000;
        if (appId < 10000 || isThirdPartyImeUid(callerUid)) {
            return false;
        }
        String pkg = resolveTokenPackage(token, callerUid);
        if (pkg != null && ("com.android.systemui".equals(pkg)
                || "com.miui.home".equals(pkg)
                || "android".equals(pkg)
                || "com.sohu.inputmethod.sogou.xiaomi".equals(pkg)
                || "com.miui.securityinputmethod".equals(pkg))) {
            return false;
        }
        if ("com.miui.fliphome".equals(pkg)) {
            return enabled();
        }
        return appCompatEnabled(pkg);
    }

    private boolean displayEligible(Object display, int type, boolean activity, int mode) throws ReflectiveOperationException {
        Object info = display == null ? null : getInfo.invoke(display);
        return info != null && FrameworkPolicy.eligible(
                (String) infoUnique.get(info), infoWidth.getInt(info), infoHeight.getInt(info), type, activity, mode);
    }

    private boolean applies(Object window) throws ReflectiveOperationException {
        return enabled() && eligibleWindow(window) && windowPolicyApplies(window) && !skipsStatusStrippingForWindow(window);
    }

    private boolean skipsStatusStrippingForWindow(Object window) {
        try {
            String pkg = windowPackageName(window);
            ModuleConfig.PerAppRule rule = ModuleConfig.perAppRule(pkg);
            if (rule != null) {
                if (rule.topGapPx() >= 0) return false;
                if (rule.widthMode() == ModuleConfig.WIDTH_MODE_SAFE_COLUMN) return false;
            }
        } catch (Throwable ignored) {
        }
        return isNativeFlipFullscreenWindow(window);
    }

    private boolean skipsStatusStrippingForToken(Object token, int callerUid) {
        String pkg = resolveTokenPackage(token, callerUid);
        ModuleConfig.PerAppRule rule = ModuleConfig.perAppRule(pkg);
        if (rule != null) {
            if (rule.topGapPx() >= 0) return false;
            if (rule.widthMode() == ModuleConfig.WIDTH_MODE_SAFE_COLUMN) return false;
        }
        return isNativeFlipFullscreenToken(token, callerUid / 100000);
    }

    private boolean isNativeFlipFullscreenWindow(Object window) {
        if (window == null || record == null) return false;
        try {
            int fallbackUserId = 0;
            if (ownerUidField != null) {
                fallbackUserId = Math.max(0, ownerUidField.getInt(window) / 100000);
            }
            return isNativeFlipFullscreenActivityRecord(record.get(window), fallbackUserId);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isNativeFlipFullscreenToken(Object token, int fallbackUserId) {
        if (token == null || asActivityMethod == null) return false;
        try {
            return isNativeFlipFullscreenActivityRecord(asActivityMethod.invoke(token), fallbackUserId);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Whether the activity (or its application) declares
     * {@code <meta-data android:name="miui.supportFlipFullScreen" android:value="0"/>}, i.e. ships its own cover
     * screen layout. ActivityRecord.info is resolved without GET_META_DATA, so the manifest is queried once per
     * component and cached.
     */
    private boolean isNativeFlipFullscreenActivityRecord(Object ar) {
        return isNativeFlipFullscreenActivityRecord(ar, 0);
    }

    private boolean isNativeFlipFullscreenActivityRecord(Object ar, int fallbackUserId) {
        if (ar == null) return false;
        try {
            ComponentName cn = arComponentField != null ? (ComponentName) arComponentField.get(ar) : null;
            int userId = fallbackUserId;
            if (arUserIdField != null) {
                int arUser = arUserIdField.getInt(ar);
                if (arUser >= 0) userId = arUser;
            }
            return isNativeFlipFullscreenComponent(cn, userId);
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean isNativeFlipFullscreenComponent(ComponentName cn, int userId) {
        if (cn == null) return false;
        // The cover launcher declares the flag too, but its status area is managed by this module itself.
        // Super XiaoAi declares supportFlipFullScreen=0 on TabHomeActivity, which we override to left-pane
        // compact mode (mode 2) when active and leave to stock HyperOS compat scaling when disabled.
        if ("com.miui.fliphome".equals(cn.getPackageName())
                || "com.miui.voiceassist".equals(cn.getPackageName())) {
            return false;
        }
        if ("com.netease.cloudmusic.activity.MiMiniPlayerActivity".equals(cn.getClassName())) return true;
        int safeUserId = Math.max(0, userId);
        String cacheKey = safeUserId + ":" + cn.flattenToShortString();
        Boolean cached = nativeFlipActivityCache.get(cacheKey);
        if (cached != null) return cached;
        boolean result = false;
        boolean queryCompleted = false;
        try {
            Bundle activityMeta = null;
            Bundle appMeta = null;
            Object s = service;
            Context ctx = s != null ? (Context) serviceContext.get(s) : null;
            if (ctx == null) {
                return false;
            }
            PackageManager pm = resolvePackageManagerForUser(ctx, safeUserId);
            if (pm == null) {
                return false;
            }
            long token = Binder.clearCallingIdentity();
            try {
                try {
                    ActivityInfo full = pm.getActivityInfo(cn,
                            PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA
                                    | PackageManager.MATCH_DISABLED_COMPONENTS));
                    activityMeta = full.metaData;
                    if (full.applicationInfo != null && full.applicationInfo.metaData != null) {
                        appMeta = full.applicationInfo.metaData;
                    }
                    queryCompleted = true;
                } catch (PackageManager.NameNotFoundException ignored) {
                }
                if (appMeta == null) {
                    try {
                        appMeta = pm.getApplicationInfo(cn.getPackageName(),
                                PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA)).metaData;
                        queryCompleted = true;
                    } catch (PackageManager.NameNotFoundException ignored) {
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            if (!queryCompleted) {
                return false;
            }
            if (activityMeta != null && activityMeta.containsKey("miui.supportFlipFullScreen")) {
                result = activityMeta.getInt("miui.supportFlipFullScreen", -1) == 0;
            } else if (appMeta != null && appMeta.containsKey("miui.supportFlipFullScreen")) {
                result = appMeta.getInt("miui.supportFlipFullScreen", -1) == 0;
            }
            nativeFlipActivityCache.put(cacheKey, result);
            if (result) ModLog.i(TAG, "native cover layout declared by " + cacheKey);
            return result;
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static PackageManager resolvePackageManagerForUser(Context ctx, int userId) {
        if (ctx == null) return null;
        if (userId > 0) {
            try {
                Object uh = android.os.UserHandle.class.getMethod("of", int.class).invoke(null, userId);
                if (uh != null) {
                    Context userCtx = (Context) Context.class
                            .getMethod("createContextAsUser", android.os.UserHandle.class, int.class)
                            .invoke(ctx, uh, 0);
                    if (userCtx != null && userCtx.getPackageManager() != null) {
                        return userCtx.getPackageManager();
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return ctx.getPackageManager();
    }

    private boolean hasNativeFlipFullscreenTopActivity(Object wpc) {
        if (wpc == null || wpcGetActivitiesMethod == null) return false;
        try {
            java.util.List<?> activities = (java.util.List<?>) wpcGetActivitiesMethod.invoke(wpc);
            if (activities == null || activities.isEmpty()) return false;
            // WindowProcessController keeps activities in launch order, not z-order. Prefer the activity that
            // WindowManager currently wants visible; fall back to the most recently added one.
            if (arIsVisibleRequestedMethod != null) {
                for (int i = activities.size() - 1; i >= 0; i--) {
                    Object ar = activities.get(i);
                    if (ar == null) continue;
                    if (arFinishingField != null && arFinishingField.getBoolean(ar)) continue;
                    if (Boolean.TRUE.equals(arIsVisibleRequestedMethod.invoke(ar))) {
                        return isNativeFlipFullscreenActivityRecord(ar);
                    }
                }
            }
            for (int i = activities.size() - 1; i >= 0; i--) {
                Object ar = activities.get(i);
                if (ar == null) continue;
                if (arFinishingField != null && arFinishingField.getBoolean(ar)) continue;
                return isNativeFlipFullscreenActivityRecord(ar);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** True when the process of this ProcessCompatController shows a natively adapted or full-width Activity on the cover. */
    private boolean isNativeFlipTop(Object pcc) throws ReflectiveOperationException {
        if (pcc == null || !enabled() || !isCoverFolded()) return false;
        Object wpc = pccProcessField.get(pcc);
        if (wpc == null || wpcInfoField == null) return false;
        ApplicationInfo ai = (ApplicationInfo) wpcInfoField.get(wpc);
        if (ai == null || !appCompatEnabled(ai.packageName)) return false;
        if (isThirdPartyImeProcess(wpc)) return false;
        ModuleConfig.PerAppRule rule = ModuleConfig.perAppRule(ai.packageName);
        if (rule != null) {
            if (rule.widthMode() == ModuleConfig.WIDTH_MODE_FULL) return true;
            if (rule.widthMode() == ModuleConfig.WIDTH_MODE_SAFE_COLUMN) return false;
        }
        return hasNativeFlipFullscreenTopActivity(wpc);
    }

    /** Drops the fixed-aspect/scaled compat state. Returns whether anything changed. */
    private boolean clearFixedAspectState(Object pcc) throws ReflectiveOperationException {
        int state = pccStateField.getInt(pcc);
        boolean changed = (state & FIXED_ASPECT_RATIO_STATE) != 0
                || pccCurrentScaleField.getFloat(pcc) != 1.0f
                || pccGlobalScaleField.getFloat(pcc) != -1.0f;
        if (!changed) return false;
        pccBoundsField.set(pcc, new Rect(0, 0, 1, 1));
        pccCurrentScaleField.setFloat(pcc, 1.0f);
        pccGlobalScaleField.setFloat(pcc, -1.0f);
        pccStateField.setInt(pcc, state & ~FIXED_ASPECT_RATIO_STATE);
        return true;
    }

    private boolean eligibleWindow(Object window) throws ReflectiveOperationException {
        if (window == null) return false;
        WindowManager.LayoutParams p = (WindowManager.LayoutParams) attrs.get(window);
        if (p == null) return false;
        if (ownerUidField != null) {
            int uid = ownerUidField.getInt(window) % 100000;
            if ("com.miui.fliphome".equals(p.packageName)) flipHomeUid = uid;
            else if ("com.miui.home".equals(p.packageName)) miuiHomeUid = uid;
            else if ("com.android.systemui".equals(p.packageName)) systemUiUid = uid;
            else if ("com.miui.voiceassist".equals(p.packageName)) voiceAssistUid = uid;
            if (uid >= 10000 && p.packageName != null && !p.packageName.isEmpty()) {
                uidPackageCache.putIfAbsent(uid, p.packageName);
            }
        }
        return displayEligible(getDisplay.invoke(window), p.type, record.get(window) != null, (Integer) getMode.invoke(window));
    }

    private int targetTopPxForWindow(Object window) {
        String pkg = null;
        try {
            pkg = windowPackageName(window);
            if ("com.miui.fliphome".equals(pkg)) {
                if (window != null && ownerUidField != null) {
                    flipHomeUid = ownerUidField.getInt(window) % 100000;
                }
                return 0;
            }
        } catch (Throwable ignored) {
        }
        return ModuleConfig.effectiveAppTopGapPx(pkg, CoverResourceOverlay.COVER_APP_STATUS_BAR_PX);
    }

    private int targetTopPxForToken(Object token, int callerUid) {
        String pkg = resolveTokenPackage(token, callerUid);
        if ("com.miui.fliphome".equals(pkg)) {
            return 0;
        }
        return ModuleConfig.effectiveAppTopGapPx(pkg, CoverResourceOverlay.COVER_APP_STATUS_BAR_PX);
    }

    private boolean shouldAdjustSource(Object source, int topPx) throws ReflectiveOperationException {
        int type = (Integer) sourceType.invoke(source);
        Rect frame = (Rect) getSourceFrame.invoke(source);
        if ((type & WindowInsets.Type.statusBars()) != 0) {
            if (topPx <= 0) {
                return frame != null && !frame.isEmpty();
            }
            return frame == null || frame.top != 0 || frame.bottom != topPx;
        }
        if ((type & (WindowInsets.Type.tappableElement() | WindowInsets.Type.mandatorySystemGestures())) != 0) {
            return frame != null && frame.top == 0 && frame.bottom > topPx && frame.bottom <= MAX_TOP_BAR_SOURCE_HEIGHT;
        }
        return false;
    }

    private Object withoutStatus(Object original, int topPx) throws ReflectiveOperationException {
        if (original == null) return null;
        for (int i = (Integer) sourceSize.invoke(original) - 1; i >= 0; i--) {
            if (shouldAdjustSource(sourceAt.invoke(original, i), topPx)) {
                Object copy = stateCopy.newInstance(original);
                stripStatus(copy, topPx);
                return copy;
            }
        }
        return original;
    }

    private void stripStatus(Object state, int topPx) throws ReflectiveOperationException {
        for (int i = (Integer) sourceSize.invoke(state) - 1; i >= 0; i--) {
            Object s = sourceAt.invoke(state, i);
            if (!shouldAdjustSource(s, topPx)) continue;
            Rect frame = (Rect) getSourceFrame.invoke(s);
            boolean wasVisible = sourceIsVisible == null || Boolean.TRUE.equals(sourceIsVisible.invoke(s));
            Object copy = sourceCopy.newInstance(s);
            if (topPx <= 0) {
                sourceFrame.invoke(copy, 0, 0, 0, 0);
                sourceVisibleFrame.invoke(copy, (Object) null);
                sourceVisible.invoke(copy, false);
            } else {
                int left = (frame != null && frame.right > frame.left) ? frame.left : 0;
                int right = (frame != null && frame.right > frame.left)
                        ? frame.right
                        : Math.max(COVER_DISPLAY_WIDTH, COVER_DISPLAY_HEIGHT);
                sourceFrame.invoke(copy, left, 0, right, topPx);
                // Preserve hidden visibility for native fullscreen/immersive apps instead of forcing statusBars visible.
                if (wasVisible) {
                    sourceVisibleFrame.invoke(copy, new Rect(left, 0, right, topPx));
                    sourceVisible.invoke(copy, true);
                } else {
                    sourceVisibleFrame.invoke(copy, (Object) null);
                    sourceVisible.invoke(copy, false);
                }
            }
            addSource.invoke(state, copy);
        }
    }

    private void verifySourceCopies(Class<?> stateType, Class<?> sourceTypeClass) throws ReflectiveOperationException {
        Object original = stateType.getDeclaredConstructor().newInstance();
        Object status = sourceTypeClass.getDeclaredConstructor(int.class, int.class).newInstance(1, WindowInsets.Type.statusBars());
        Object ime = sourceTypeClass.getDeclaredConstructor(int.class, int.class).newInstance(8, WindowInsets.Type.ime());
        sourceFrame.invoke(status, 0, 0, 1208, 114);
        sourceVisible.invoke(status, true);
        addSource.invoke(original, status);
        sourceFrame.invoke(ime, 0, 662, 1208, 1392);
        sourceVisible.invoke(ime, true);
        addSource.invoke(original, ime);
        Object filteredZero = withoutStatus(original, 0);
        Object filteredApp = withoutStatus(original, CoverResourceOverlay.COVER_APP_STATUS_BAR_PX);
        if (((Rect) getSourceFrame.invoke(status)).height() != 114
                || (Integer) sourceSize.invoke(original) != 2
                || (Integer) sourceSize.invoke(filteredZero) != 2
                || (Integer) sourceSize.invoke(filteredApp) != 2) {
            throw new IllegalStateException("Insets source copy changed original state");
        }
        for (int i = 0; i < 2; i++) {
            Object s0 = sourceAt.invoke(filteredZero, i);
            int type0 = (Integer) sourceType.invoke(s0);
            Rect bounds0 = (Rect) getSourceFrame.invoke(s0);
            if ((type0 & WindowInsets.Type.statusBars()) != 0 && !bounds0.isEmpty()) {
                throw new IllegalStateException("Desktop status space not cleared");
            }
            if ((type0 & WindowInsets.Type.ime()) != 0 && !bounds0.equals(new Rect(0, 662, 1208, 1392))) {
                throw new IllegalStateException("IME source changed");
            }

            Object sApp = sourceAt.invoke(filteredApp, i);
            int typeApp = (Integer) sourceType.invoke(sApp);
            Rect boundsApp = (Rect) getSourceFrame.invoke(sApp);
            if ((typeApp & WindowInsets.Type.statusBars()) != 0
                    && boundsApp.height() != CoverResourceOverlay.COVER_APP_STATUS_BAR_PX) {
                throw new IllegalStateException("App status space not clamped to 30px");
            }
        }
        Log.i(TAG, "Insets copy isolation check passed");
    }

    private void configurationChanged() {
        Object s = service;
        if (s == null) return;
        try {
            ((Handler) serviceHandler.get(s)).post(() -> {
                try {
                    synchronized (serviceLock.get(s)) {
                        syncConfiguredLetterboxes(systemServerClassLoader);
                        published.clear();
                        Set<Object> displays = new HashSet<>();
                        forAllWindows.invoke(serviceRoot.get(s), (Consumer<Object>) w -> {
                            try {
                                Object d = getDisplay.invoke(w);
                                WindowManager.LayoutParams p = (WindowManager.LayoutParams) attrs.get(w);
                                if (p != null && displayEligible(d, p.type, record.get(w) != null, (Integer) getMode.invoke(w))) {
                                    notifyInsets.invoke(w);
                                    displays.add(d);
                                }
                            } catch (Throwable t) {
                                error(t);
                            }
                        }, true);
                        for (Object d : displays) layoutNeeded.invoke(d);
                        traversal.invoke(s);
                    }
                    syncImeProcessesForFoldState(s, isCoverFolded());
                } catch (Throwable t) {
                    error(t);
                }
            });
        } catch (ReflectiveOperationException e) {
            error(e);
        }
    }

    private void reportFocus(Object window) throws ReflectiveOperationException {
        Object s = service, display = getDisplay.invoke(window);
        if (s == null || displayFocus.get(display) != window) return;
        WindowManager.LayoutParams p = (WindowManager.LayoutParams) attrs.get(window);
        boolean full = FrameworkPolicy.nativeFullscreen(
                rawTypes.getInt(window),
                (p.flags & WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0,
                "com.miui.fliphome".equals(p.packageName));
        int displayId = (Integer) displayGetIdMethod.invoke(display);
        String key = p.packageName + ":" + full + ":" + displayId;
        if (key.equals(published.put(display, key))) return;
        final boolean fullscreen = full;
        ((Handler) serviceHandler.get(s)).post(() -> {
            try {
                Context c = (Context) serviceContext.get(s);
                Intent intent = new Intent(FrameworkRuntime.ACTION)
                        .setPackage("com.android.systemui")
                        .putExtra("package", p.packageName)
                        .putExtra("display", displayId)
                        .putExtra("fullscreen", fullscreen);
                CoverDisplay.sendBroadcastAsUser(c, intent);
            } catch (ReflectiveOperationException | RuntimeException e) {
                error(e);
            }
        });
    }

    private void installFoldStateObserver(Class<?> wms) {
        try {
            Method onDeviceState = method(wms, "onDisplayDeviceStateChange", void.class, int.class);
            deoptimize(onDeviceState);
            hooks.add(hook(onDeviceState).intercept(chain -> {
                Object res = chain.proceed();
                try {
                    int state = (Integer) chain.getArg(0);
                    lastDeviceStateChangeAtMs = SystemClock.uptimeMillis();
                    onFoldStateChanged(chain.getThisObject(), state == 0 || state == 1);
                } catch (Throwable t) {
                    error(t);
                }
                return res;
            }));
            Log.i(TAG, "fold state observer installed");
        } catch (Throwable t) {
            Log.w(TAG, "fold state observer hook skipped", t);
        }
    }

    private void checkDisplayTransition(Object s) {
        if (s == null || !installed) return;
        // Let onDisplayDeviceStateChange remain authoritative briefly while DisplayContent(0) switches logical dims.
        if (lastDeviceStateChangeAtMs > 0L
                && (SystemClock.uptimeMillis() - lastDeviceStateChangeAtMs) < 400L) {
            return;
        }
        try {
            boolean coverNow = isDefaultDisplayCover(s);
            if (lastCoverDisplayInitialized && lastCoverDisplayState == coverNow) return;
            lastCoverDisplayInitialized = true;
            lastCoverDisplayState = coverNow;
            onFoldStateChanged(s, coverNow);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Single entry point for fold/unfold, fed by both WMS.onDisplayDeviceStateChange and the lazy display check in
     * the Insets hooks. Pending work from a previous transition is cancelled so one transition runs the IME and
     * overlay synchronisation once (plus one settle pass), instead of up to six times.
     */
    private void onFoldStateChanged(Object s, boolean folded) {
        if (s == null) return;
        synchronized (foldLock) {
            if (lastFoldDispatched != null && lastFoldDispatched == folded) return;
            lastFoldDispatched = folded;
        }
        lastCoverDisplayInitialized = true;
        lastCoverDisplayState = folded;
        try {
            Handler h = (Handler) serviceHandler.get(s);
            if (h == null) {
                if (!folded) cancelFoldPocketGuard();
                CoverResourceOverlay.syncForFoldState(folded);
                syncImeProcessesForFoldState(s, folded);
                return;
            }
            h.removeCallbacksAndMessages(FOLD_TOKEN);
            if (folded) {
                h.postDelayed(() -> {
                    CoverResourceOverlay.syncForFoldState(true);
                    syncImeProcessesForFoldState(s, true);
                }, FOLD_TOKEN, 0L);
                h.postDelayed(() -> syncImeProcessesForFoldState(s, true), FOLD_TOKEN, 150L);
                scheduleFoldPocketGuardCheck(s, h);
            } else {
                cancelFoldPocketGuard();
                h.postDelayed(() -> {
                    CoverResourceOverlay.syncForFoldState(false);
                    syncImeProcessesForFoldState(s, false);
                }, FOLD_TOKEN, 0L);
                h.postDelayed(() -> {
                    CoverResourceOverlay.syncForFoldState(false);
                    syncImeProcessesForFoldState(s, false);
                }, FOLD_TOKEN, 280L);
            }
        } catch (Throwable t) {
            error(t);
        }
    }

    private void cancelFoldPocketGuard() {
        try {
            Object s = service;
            Handler h = (s != null && serviceHandler != null) ? (Handler) serviceHandler.get(s) : null;
            if (h != null) {
                h.removeCallbacksAndMessages(POCKET_GUARD_TOKEN);
            }
        } catch (Throwable ignored) {
        }
        SensorManager sm;
        SensorEventListener l;
        synchronized (pocketSensorLock) {
            sm = activePocketSensorManager;
            l = activePocketSensorListener;
            activePocketSensorManager = null;
            activePocketSensorListener = null;
        }
        if (sm != null && l != null) {
            try {
                sm.unregisterListener(l);
            } catch (Throwable ignored) {
            }
        }
    }

    private void scheduleFoldPocketGuardCheck(Object s, Handler h) {
        if (s == null || h == null || !runtimeActive() || !ModuleConfig.foldPocketGuardEnabled()) {
            return;
        }
        boolean seamlessActive = ModuleConfig.foldContinuityMode() != ModuleConfig.FOLD_CONT_SYSTEM
                || (SystemClock.uptimeMillis() - lastSeamlessFoldContinuityAt) < 3000L;
        if (!seamlessActive) return;
        cancelFoldPocketGuard();
        h.postDelayed(() -> {
            try {
                if (!isCoverFolded() || !ModuleConfig.foldPocketGuardEnabled()) return;
                Context ctx = (Context) serviceContext.get(s);
                if (ctx == null || isDeviceKeyguardLocked(ctx)) return;
                android.os.PowerManager pm = ctx.getSystemService(android.os.PowerManager.class);
                if (pm == null || !pm.isInteractive()) return;
                SensorManager sm = ctx.getSystemService(SensorManager.class);
                if (sm == null) return;
                Sensor sensor = findBackProximitySensor(sm);
                if (sensor == null) return;

                final float nearThreshold = Math.min(1.0f, Math.max(0.5f, sensor.getMaximumRange() * 0.5f));
                final boolean[] pocketNear = {false};
                final boolean[] settled = {false};
                final SensorEventListener listener = new SensorEventListener() {
                    @Override
                    public void onSensorChanged(SensorEvent event) {
                        if (settled[0] || event == null || event.values == null || event.values.length == 0) return;
                        float val = event.values[0];
                        boolean near = val >= 0f && val < nearThreshold;
                        pocketNear[0] = near;
                        if (!near) {
                            settled[0] = true;
                            cancelFoldPocketGuard();
                        }
                    }

                    @Override
                    public void onAccuracyChanged(Sensor sensor1, int accuracy) {}
                };
                synchronized (pocketSensorLock) {
                    activePocketSensorManager = sm;
                    activePocketSensorListener = listener;
                }
                if (!sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, h)) {
                    cancelFoldPocketGuard();
                    return;
                }
                h.postDelayed(() -> {
                    if (settled[0]) return;
                    try {
                        if (pocketNear[0] && isCoverFolded() && ModuleConfig.foldPocketGuardEnabled()) {
                            android.os.PowerManager power = ctx.getSystemService(android.os.PowerManager.class);
                            if (power != null && power.isInteractive()) {
                                Method goToSleep = android.os.PowerManager.class.getMethod("goToSleep", long.class);
                                goToSleep.invoke(power, SystemClock.uptimeMillis());
                                ModLog.i(TAG, "fold pocket guard: cover occluded, locking screen");
                            }
                        }
                    } catch (Throwable t) {
                        error(t);
                    } finally {
                        settled[0] = true;
                        cancelFoldPocketGuard();
                    }
                }, POCKET_GUARD_TOKEN, 450L);
                h.postDelayed(() -> {
                    if (!settled[0]) {
                        settled[0] = true;
                        cancelFoldPocketGuard();
                    }
                }, POCKET_GUARD_TOKEN, 1800L);
            } catch (Throwable t) {
                cancelFoldPocketGuard();
                error(t);
            }
        }, POCKET_GUARD_TOKEN, 260L);
    }

    private static Sensor findBackProximitySensor(SensorManager sm) {
        if (sm == null) return null;
        Sensor fallback = null;
        try {
            for (Sensor sensor : sm.getSensorList(Sensor.TYPE_ALL)) {
                if (sensor == null) continue;
                int type = sensor.getType();
                if (type == SENSOR_TYPE_PROXIMITY_BACK) {
                    return sensor;
                }
                if (type == SENSOR_TYPE_OEM_POCKET && fallback == null) {
                    fallback = sensor;
                }
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static boolean isDeviceKeyguardLocked(Context ctx) {
        if (ctx == null) return false;
        try {
            android.app.KeyguardManager km = ctx.getSystemService(android.app.KeyguardManager.class);
            return km != null && km.isKeyguardLocked();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isLauncherOrSystemUiPackage(String pkg) {
        return pkg == null || pkg.isEmpty()
                || "com.miui.home".equals(pkg)
                || "com.miui.fliphome".equals(pkg)
                || "com.android.systemui".equals(pkg)
                || "android".equals(pkg);
    }

    private String resolveActiveInnerForegroundApp() {
        Object iac = interceptActivityControllerRef;
        if (iac != null) {
            String iacPkg = resolveFoldForegroundPackage(iac);
            if (iacPkg != null) return iacPkg;
        }
        try {
            Object s = service;
            if (s != null && serviceRoot != null && rootDisplay != null && displayFocus != null) {
                Object root = serviceRoot.get(s);
                Object dc = root != null ? rootDisplay.invoke(root, 0) : null;
                Object win = dc != null ? displayFocus.get(dc) : null;
                String pkg = windowPackageName(win);
                if (!isLauncherOrSystemUiPackage(pkg)) {
                    return pkg;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private String resolveFoldForegroundPackage(Object iac) {
        if (iac == null) return null;
        try {
            if (iacTopActivityField != null) {
                Object ar = iacTopActivityField.get(iac);
                if (ar != null) {
                    boolean finishing = arFinishingField != null && arFinishingField.getBoolean(ar);
                    if (!finishing) {
                        String pkg = arPkgField != null ? (String) arPkgField.get(ar) : null;
                        if ((pkg == null || pkg.isEmpty()) && arComponentField != null) {
                            ComponentName cn = (ComponentName) arComponentField.get(ar);
                            if (cn != null) pkg = cn.getPackageName();
                        }
                        if (!isLauncherOrSystemUiPackage(pkg)) {
                            return pkg;
                        }
                        if ("com.miui.home".equals(pkg) || "com.miui.fliphome".equals(pkg)) {
                            return null;
                        }
                    }
                }
            }
            if (iacPolicyField != null && policyGetFgPkgMethod != null) {
                Object policy = iacPolicyField.get(iac);
                if (policy != null && policyGetFgPkgMethod.invoke(policy) instanceof String fgPkg) {
                    if (!isLauncherOrSystemUiPackage(fgPkg)) {
                        return fgPkg;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private boolean isDefaultDisplayCover(Object s) throws ReflectiveOperationException {
        if (s == null || rootDisplay == null) return false;
        Object root = serviceRoot.get(s);
        if (root == null) return false;
        Object dc = rootDisplay.invoke(root, 0);
        Object info = dc == null ? null : getInfo.invoke(dc);
        return info != null && FrameworkPolicy.eligible(
                (String) infoUnique.get(info), infoWidth.getInt(info), infoHeight.getInt(info), 1, true, 1);
    }

    private boolean isCoverFolded() {
        try {
            if (miuiCompatStub != null && isFlipFoldedMethod != null) {
                Object res = isFlipFoldedMethod.invoke(miuiCompatStub);
                if (res instanceof Boolean b) {
                    return b;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            return isDefaultDisplayCover(service);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean shouldStripLetterboxForPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        if ("com.miui.voiceassist".equals(pkg) && appCompatEnabled(pkg)) {
            ModuleConfig.PerAppRule r = ModuleConfig.perAppRule(pkg);
            return r == null || r.widthMode() != ModuleConfig.WIDTH_MODE_FULL;
        }
        if (!enabled()) return false;
        ModuleConfig.PerAppRule rule = ModuleConfig.perAppRule(pkg);
        return rule != null && rule.widthMode() == ModuleConfig.WIDTH_MODE_SAFE_COLUMN;
    }

    private void syncConfiguredLetterboxes(ClassLoader loader) {
        if (strippedLetterboxPackages.isEmpty()) return;
        for (String pkg : new ArrayList<>(strippedLetterboxPackages)) {
            if (!shouldStripLetterboxForPackage(pkg)) {
                syncPackageHideLetterbox(loader, pkg, false);
            }
        }
    }

    private void restoreAllPackageHideLetterboxes(ClassLoader loader) {
        if (strippedLetterboxPackages.isEmpty()
                && removedHideLetterboxEntries.isEmpty()
                && removedAspectBlacklistEntries.isEmpty()) {
            return;
        }
        for (String pkg : new ArrayList<>(strippedLetterboxPackages)) {
            syncPackageHideLetterbox(loader, pkg, false);
        }
    }

    @SuppressWarnings("unchecked")
    private void syncPackageHideLetterbox(ClassLoader loader, String pkg, boolean strip) {
        if (pkg == null || pkg.isEmpty()) return;
        if (strip == strippedLetterboxPackages.contains(pkg)) return;
        ClassLoader cl = loader != null ? loader : systemServerClassLoader;
        Object s = service;
        if (cl == null || s == null) return;
        Object lockObj = foldLock;
        try {
            if (serviceLock != null) {
                Object wmLock = serviceLock.get(s);
                if (wmLock != null) lockObj = wmLock;
            }
        } catch (Throwable ignored) {
        }
        synchronized (lockObj) {
            synchronized (letterboxGuard) {
                if (strip == strippedLetterboxPackages.contains(pkg)) return;
                try {
                    Class<?> arImplClass = Class.forName("com.android.server.wm.ActivityRecordImpl", false, cl);
                    Field hideLbField = arImplClass.getDeclaredField("FLIP_SCF_HIDE_LETTERBOX_LIST");
                    hideLbField.setAccessible(true);
                    Object hideLbSet = hideLbField.get(null);

                    Class<?> atmsImplClass = Class.forName("com.android.server.wm.ActivityTaskManagerServiceImpl", false, cl);
                    Field blField = atmsImplClass.getDeclaredField("BLACK_LIST_SHOULD_NOT_APPLY_ASPECT_RATIO");
                    blField.setAccessible(true);
                    Object blObj = blField.get(null);

                    String prefix = pkg + "/";
                    if (strip) {
                        if (hideLbSet instanceof java.util.Collection<?> col) {
                            if (col.remove(pkg)) {
                                removedHideLetterboxEntries.add(pkg);
                            }
                        }
                        if (blObj instanceof java.util.Collection<?> col) {
                            java.util.Iterator<?> it = col.iterator();
                            while (it.hasNext()) {
                                Object item = it.next();
                                if (item instanceof String str && (pkg.equals(str) || str.startsWith(prefix))) {
                                    removedAspectBlacklistEntries.add(str);
                                    it.remove();
                                }
                            }
                        }
                        strippedLetterboxPackages.add(pkg);
                    } else {
                        if (hideLbSet instanceof java.util.Collection<?> col && removedHideLetterboxEntries.remove(pkg)) {
                            ((java.util.Collection<String>) col).add(pkg);
                        }
                        if (blObj instanceof java.util.Collection<?> col && !removedAspectBlacklistEntries.isEmpty()) {
                            java.util.Iterator<String> it = removedAspectBlacklistEntries.iterator();
                            while (it.hasNext()) {
                                String entry = it.next();
                                if (pkg.equals(entry) || entry.startsWith(prefix)) {
                                    ((java.util.Collection<String>) col).add(entry);
                                    it.remove();
                                }
                            }
                        }
                        strippedLetterboxPackages.remove(pkg);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void installUniversalCoverAppCompat(ClassLoader loader) {
        try {
            Class<?> stubClass = Class.forName("android.sizecompat.MiuiAppSizeCompatModeStub", false, loader);
            miuiCompatStub = stubClass.getDeclaredMethod("get").invoke(null);
            if (miuiCompatStub != null) {
                isFlipFoldedMethod = miuiCompatStub.getClass().getMethod("isFlipFolded");
                isFlipFoldedMethod.setAccessible(true);
            }
        } catch (Throwable t) {
            Log.w(TAG, "MiuiAppSizeCompatModeStub unavailable", t);
        }

        try {
            Class<?> acmClass = loader.loadClass("com.android.server.wm.ApplicationCompatManager");
            for (Method m : acmClass.getDeclaredMethods()) {
                String name = m.getName();
                if ("hasPropertyByApplication".equals(name) || "hasPropertyByActivity".equals(name)) {
                    m.setAccessible(true);
                    hooks.add(hook(m).intercept(chain -> {
                        try {
                            if (enabled() && !isSystemCompatTarget(chain.getArgs())
                                    && appCompatEnabled(extractCompatTargetPackage(chain.getArgs()))) {
                                for (Object arg : chain.getArgs()) {
                                    if ("miui.continuity.policy".equals(arg)) {
                                        return true;
                                    }
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    }));
                } else if ("getPropertyIntByApplication".equals(name) || "getPropertyIntByActivity".equals(name)) {
                    m.setAccessible(true);
                    hooks.add(hook(m).intercept(chain -> {
                        try {
                            if (enabled() && !isSystemCompatTarget(chain.getArgs())
                                    && appCompatEnabled(extractCompatTargetPackage(chain.getArgs()))) {
                                for (Object arg : chain.getArgs()) {
                                    if ("miui.continuity.policy".equals(arg)) {
                                        return 5;
                                    }
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    }));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "ApplicationCompatManager hook skipped", t);
        }

        try {
            Class<?> bcuClass = loader.loadClass("com.android.server.wm.BoundsCompatUtils");
            for (Method m : bcuClass.getDeclaredMethods()) {
                String name = m.getName();
                if ("getFlipCompatModeByActivity".equals(name)) {
                    m.setAccessible(true);
                    deoptimize(m);
                    hooks.add(hook(m).intercept(chain -> {
                        try {
                            Object ar = chain.getArg(0);
                            if (ar != null && arPkgField != null && arPkgField.get(ar) instanceof String pkg) {
                                ModuleConfig.PerAppRule rule = enabled() ? ModuleConfig.perAppRule(pkg) : null;
                                if (rule != null && rule.widthMode() == ModuleConfig.WIDTH_MODE_FULL) {
                                    syncPackageHideLetterbox(loader, pkg, false);
                                    return 0;
                                }
                                if (rule != null && rule.widthMode() == ModuleConfig.WIDTH_MODE_SAFE_COLUMN) {
                                    syncPackageHideLetterbox(loader, pkg, true);
                                    return 2;
                                }
                                if ("com.miui.voiceassist".equals(pkg)) {
                                    if (appCompatEnabled(pkg)) {
                                        syncPackageHideLetterbox(loader, pkg, true);
                                        return 2;
                                    }
                                    syncPackageHideLetterbox(loader, pkg, false);
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    }));
                } else if ("getFlipCompatModeByApp".equals(name)) {
                    m.setAccessible(true);
                    deoptimize(m);
                    hooks.add(hook(m).intercept(chain -> {
                        try {
                            if (chain.getArg(1) instanceof String pkg) {
                                ModuleConfig.PerAppRule rule = enabled() ? ModuleConfig.perAppRule(pkg) : null;
                                if (rule != null && rule.widthMode() != ModuleConfig.WIDTH_MODE_DEFAULT) {
                                    return 0;
                                }
                                if ("com.miui.voiceassist".equals(pkg)) {
                                    if (appCompatEnabled(pkg)) {
                                        return 0;
                                    }
                                    syncPackageHideLetterbox(loader, pkg, false);
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    }));
                } else if ("getGlobalScaleByName".equals(name) && m.getReturnType() == float.class) {
                    m.setAccessible(true);
                    deoptimize(m);
                    hooks.add(hook(m).intercept(chain -> {
                        try {
                            if (enabled() && isCoverFolded() && chain.getArg(0) instanceof String pkg) {
                                ModuleConfig.PerAppRule rule = ModuleConfig.perAppRule(pkg);
                                if (rule != null && rule.scalePercent() > 0) {
                                    if (rule.widthMode() == ModuleConfig.WIDTH_MODE_FULL
                                            || rule.scalePercent() > 100) {
                                        return 1.0f;
                                    }
                                    return Math.max(0.65f, Math.min(1.0f, rule.scalePercent() / 100f));
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    }));
                }
            }
            for (String clsName : new String[]{
                    "com.android.server.wm.BoundsCompatController",
                    "com.android.server.wm.ProcessCompatController"
            }) {
                try {
                    Class<?> cls = loader.loadClass(clsName);
                    for (Method m : cls.getDeclaredMethods()) {
                        String n = m.getName();
                        if (n.contains("Flip") || n.contains("FixedAspectRatio") || n.contains("BoundsCompat") || n.contains("CompatScale")) {
                            if (!Modifier.isAbstract(m.getModifiers()) && !Modifier.isNative(m.getModifiers())) {
                                deoptimize(m);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "BoundsCompatUtils hook skipped", t);
        }

        try {
            Class<?> wmsImplClass = loader.loadClass("com.android.server.wm.WindowManagerServiceImpl");
            for (Method m : wmsImplClass.getDeclaredMethods()) {
                String name = m.getName();
                if (name.contains("Flip") || name.contains("FullScreen")) {
                    if (!Modifier.isAbstract(m.getModifiers()) && !Modifier.isNative(m.getModifiers())) {
                        deoptimize(m);
                    }
                }
                if ("getFullScreenValue".equals(name)) {
                    m.setAccessible(true);
                    hooks.add(hook(m).intercept(chain -> {
                        try {
                            if (chain.getArg(0) instanceof PackageItemInfo info && info.packageName != null) {
                                String pkg = info.packageName;
                                ModuleConfig.PerAppRule rule = enabled() ? ModuleConfig.perAppRule(pkg) : null;
                                if (rule != null && rule.widthMode() == ModuleConfig.WIDTH_MODE_FULL) {
                                    return 0;
                                }
                                if (rule != null && rule.widthMode() == ModuleConfig.WIDTH_MODE_SAFE_COLUMN) {
                                    return 2;
                                }
                                if ("com.miui.voiceassist".equals(pkg) && appCompatEnabled(pkg)) {
                                    return 2;
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    }));
                } else if ("enableFullScreen".equals(name)) {
                    m.setAccessible(true);
                    hooks.add(hook(m).intercept(chain -> {
                        try {
                            Object ar = chain.getArg(0);
                            if (ar != null && arPkgField != null && arPkgField.get(ar) instanceof String pkg) {
                                ModuleConfig.PerAppRule rule = enabled() ? ModuleConfig.perAppRule(pkg) : null;
                                if (rule != null && rule.widthMode() == ModuleConfig.WIDTH_MODE_FULL) {
                                    syncPackageHideLetterbox(loader, pkg, false);
                                    return true;
                                }
                                if (rule != null && rule.widthMode() == ModuleConfig.WIDTH_MODE_SAFE_COLUMN) {
                                    syncPackageHideLetterbox(loader, pkg, true);
                                    return false;
                                }
                                if ("com.miui.voiceassist".equals(pkg)) {
                                    if (appCompatEnabled(pkg)) {
                                        syncPackageHideLetterbox(loader, pkg, true);
                                        return false;
                                    }
                                    syncPackageHideLetterbox(loader, pkg, false);
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    }));
                } else if ("getFlipCompatModeByActivity".equals(name) && m.getReturnType() == int.class) {
                    m.setAccessible(true);
                    hooks.add(hook(m).intercept(chain -> {
                        try {
                            Object arg0 = chain.getArg(0);
                            String pkg = arg0 instanceof ComponentName cn ? cn.getPackageName()
                                    : (arg0 != null && arPkgField != null && arPkgField.getDeclaringClass().isInstance(arg0)
                                    ? (String) arPkgField.get(arg0) : null);
                            ModuleConfig.PerAppRule rule = enabled() ? ModuleConfig.perAppRule(pkg) : null;
                            if (rule != null && rule.widthMode() == ModuleConfig.WIDTH_MODE_FULL) {
                                return 0;
                            }
                            if (rule != null && rule.widthMode() == ModuleConfig.WIDTH_MODE_SAFE_COLUMN) {
                                syncPackageHideLetterbox(loader, pkg, true);
                                return 2;
                            }
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    }));
                } else if ("getFlipCompatModeByApp".equals(name) && m.getReturnType() == int.class) {
                    m.setAccessible(true);
                    hooks.add(hook(m).intercept(chain -> {
                        try {
                            String pkg = null;
                            for (Object arg : chain.getArgs()) {
                                if (arg instanceof String s && !s.isEmpty()) {
                                    pkg = s;
                                    break;
                                }
                            }
                            ModuleConfig.PerAppRule rule = enabled() ? ModuleConfig.perAppRule(pkg) : null;
                            if (rule != null && rule.widthMode() != ModuleConfig.WIDTH_MODE_DEFAULT) {
                                return 0;
                            }
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    }));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "WindowManagerServiceImpl hook skipped", t);
        }

        try {
            Class<?> iacClass = loader.loadClass("com.android.server.wm.InterceptActivityController");
            iacTopActivityField = optionalField(iacClass, "mCurrentTopActivityRecord",
                    loader.loadClass("com.android.server.wm.ActivityRecord"));
            iacIsTopKeepedField = optionalField(iacClass, "mIsTopActivityContinuityKeeped", boolean.class);
            try {
                Class<?> cpsClass = loader.loadClass("com.android.server.wm.ContinuityPolicyService");
                iacPolicyField = optionalField(iacClass, "mPolicy", cpsClass);
                policyGetFgPkgMethod = optionalMethod(cpsClass, "getForegroundPackageName", String.class);
            } catch (Throwable ignored) {
            }
            for (java.lang.reflect.Constructor<?> ctor : iacClass.getDeclaredConstructors()) {
                ctor.setAccessible(true);
                hooks.add(hook(ctor).intercept(chain -> {
                    Object res = chain.proceed();
                    interceptActivityControllerRef = chain.getThisObject();
                    return res;
                }));
            }
            for (Method m : iacClass.getDeclaredMethods()) {
                if (!Modifier.isAbstract(m.getModifiers()) && !Modifier.isNative(m.getModifiers())) {
                    deoptimize(m);
                }
            }
            for (Method m : iacClass.getDeclaredMethods()) {
                String iacName = m.getName();
                if (("isInterceptList".equals(iacName)
                        || "isInterceptListUnCheckFold".equals(iacName)
                        || "isInterceptActivity".equals(iacName))
                        && m.getReturnType() == boolean.class) {
                    // HyperOS blocks apps on its cover intercept list (Settings, ...) with an "unfold to continue"
                    // prompt. Report them as not intercepted so they open directly on the cover screen.
                    m.setAccessible(true);
                    hooks.add(hook(m).intercept(chain -> {
                        if (chain.getThisObject() != null) {
                            interceptActivityControllerRef = chain.getThisObject();
                        }
                        try {
                            if (runtimeActive() && ModuleConfig.allowAllCoverApps()) {
                                return false;
                            }
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    }));
                } else if ("isFlipContinuityEnabled".equals(iacName)
                        && m.getParameterCount() == 0
                        && m.getReturnType() == boolean.class) {
                    m.setAccessible(true);
                    hooks.add(hook(m).intercept(chain -> {
                        if (chain.getThisObject() != null) {
                            interceptActivityControllerRef = chain.getThisObject();
                        }
                        try {
                            if (runtimeActive() && ModuleConfig.foldContinuityMode() != ModuleConfig.FOLD_CONT_SYSTEM) {
                                return true;
                            }
                        } catch (Throwable t) {
                            error(t);
                        }
                        return chain.proceed();
                    }));
                } else if ("isFlipContinuityEnabledFromSetting".equals(iacName)
                        && m.getParameterCount() == 3
                        && m.getReturnType() == boolean.class) {
                    m.setAccessible(true);
                    hooks.add(hook(m).intercept(chain -> {
                        if (chain.getThisObject() != null) {
                            interceptActivityControllerRef = chain.getThisObject();
                        }
                        try {
                            if (runtimeActive()) {
                                int mode = ModuleConfig.foldContinuityMode();
                                if (mode != ModuleConfig.FOLD_CONT_SYSTEM
                                        && chain.getArg(0) instanceof String pkg
                                        && !isLauncherOrSystemUiPackage(pkg)) {
                                    return mode == ModuleConfig.FOLD_CONT_ALL
                                            || ModuleConfig.isFoldContinuityApp(pkg);
                                }
                            }
                        } catch (Throwable t) {
                            error(t);
                        }
                        return chain.proceed();
                    }));
                } else if ("isKeepContinuityInFold".equals(iacName)
                        && m.getParameterCount() == 0
                        && m.getReturnType() == boolean.class) {
                    m.setAccessible(true);
                    hooks.add(hook(m).intercept(chain -> {
                        Object self = chain.getThisObject();
                        if (self != null) interceptActivityControllerRef = self;
                        try {
                            if (runtimeActive()) {
                                int mode = ModuleConfig.foldContinuityMode();
                                if (mode != ModuleConfig.FOLD_CONT_SYSTEM) {
                                    String fgPkg = resolveFoldForegroundPackage(self);
                                    if (fgPkg == null) {
                                        return chain.proceed();
                                    }
                                    boolean keep = (mode == ModuleConfig.FOLD_CONT_ALL)
                                            || ModuleConfig.isFoldContinuityApp(fgPkg);
                                    if (iacIsTopKeepedField != null && self != null) {
                                        iacIsTopKeepedField.setBoolean(self, keep);
                                    }
                                    if (keep) {
                                        lastSeamlessFoldContinuityAt = SystemClock.uptimeMillis();
                                    }
                                    ModLog.i(TAG, "isKeepContinuityInFold pkg=" + fgPkg + " mode=" + mode + " -> " + keep);
                                    return keep;
                                }
                            }
                        } catch (Throwable t) {
                            error(t);
                        }
                        return chain.proceed();
                    }));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "InterceptActivityController hook skipped", t);
        }

        installMiuiFlipPolicyHook(loader);

        for (String extraClsName : new String[]{
                "com.android.server.wm.AppCompatTask",
                "com.android.server.wm.FoldOuterAppBlockController"
        }) {
            try {
                Class<?> extraCls = loader.loadClass(extraClsName);
                for (Method m : extraCls.getDeclaredMethods()) {
                    String n = m.getName();
                    if (("isActivityInterception".equals(n) || "isAppBlockedOnFoldOuter".equals(n))
                            && m.getReturnType() == boolean.class) {
                        m.setAccessible(true);
                        deoptimize(m);
                        hooks.add(hook(m).intercept(chain -> {
                            try {
                                if (runtimeActive() && ModuleConfig.allowAllCoverApps()) {
                                    return false;
                                }
                            } catch (Throwable ignored) {
                            }
                            return chain.proceed();
                        }));
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        installCoverThirdPartyImeHooks(loader);

        Log.i(TAG, "universal cover app continuity hooks installed");
    }

    private void installMiuiFlipPolicyHook(ClassLoader loader) {
        try {
            Class<?> flipPolicyClass = loader.loadClass("com.android.server.display.MiuiFlipPolicy");
            flipPolicyNeedContinueField = optionalField(flipPolicyClass, "mNeedContinueShowAPP", boolean.class);
            Method shouldSleep = method(flipPolicyClass, "shouldDeviceBeSleep", boolean.class);
            deoptimize(shouldSleep);
            hooks.add(hook(shouldSleep).intercept(chain -> {
                try {
                    if (!runtimeActive()) {
                        return chain.proceed();
                    }
                    int mode = ModuleConfig.foldContinuityMode();
                    if (mode == ModuleConfig.FOLD_CONT_SYSTEM) {
                        return chain.proceed();
                    }
                    Object self = chain.getThisObject();
                    Object s = service;
                    Context ctx = s != null && serviceContext != null ? (Context) serviceContext.get(s) : null;
                    if (isDeviceKeyguardLocked(ctx)) {
                        return chain.proceed();
                    }
                    String fgPkg = resolveActiveInnerForegroundApp();
                    if (fgPkg == null) {
                        return chain.proceed();
                    }
                    boolean shouldContinue = (mode == ModuleConfig.FOLD_CONT_ALL)
                            || ModuleConfig.isFoldContinuityApp(fgPkg);
                    if (shouldContinue) {
                        lastSeamlessFoldContinuityAt = SystemClock.uptimeMillis();
                        ModLog.i(TAG, "MiuiFlipPolicy seamless continuity kept alive pkg=" + fgPkg);
                        return false;
                    }
                    if (flipPolicyNeedContinueField != null && self != null) {
                        boolean prev = flipPolicyNeedContinueField.getBoolean(self);
                        flipPolicyNeedContinueField.setBoolean(self, false);
                        try {
                            return chain.proceed();
                        } finally {
                            flipPolicyNeedContinueField.setBoolean(self, prev);
                        }
                    }
                } catch (Throwable t) {
                    error(t);
                }
                return chain.proceed();
            }));
            Log.i(TAG, "MiuiFlipPolicy fold continuity hook installed");
        } catch (Throwable t) {
            Log.w(TAG, "MiuiFlipPolicy hook skipped", t);
        }
    }

    private void installCoverThirdPartyImeHooks(ClassLoader loader) {
        try {
            Class<?> immsImplClass = loader.loadClass("com.android.server.inputmethod.InputMethodManagerServiceImpl");
            for (Method m : immsImplClass.getDeclaredMethods()) {
                String name = m.getName();
                if (("isFlipTinyScreen".equals(name) || "showCountdownAlertDialog".equals(name))
                        && m.getReturnType() == boolean.class) {
                    m.setAccessible(true);
                    deoptimize(m);
                    hooks.add(hook(m).intercept(chain -> {
                        try {
                            if (coverImeEnabled()) {
                                return false;
                            }
                        } catch (Throwable t) {
                            error(t);
                        }
                        return chain.proceed();
                    }));
                } else if ("onDisplayDeviceStateChanged".equals(name) && m.getReturnType() == void.class) {
                    m.setAccessible(true);
                    deoptimize(m);
                    hooks.add(hook(m).intercept(chain -> {
                        try {
                            if (coverImeEnabled()) {
                                return null;
                            }
                        } catch (Throwable t) {
                            error(t);
                        }
                        return chain.proceed();
                    }));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "InputMethodManagerServiceImpl cover IME hook skipped", t);
        }

        try {
            Class<?> sogouSwitcherClass = loader.loadClass("com.android.server.inputmethod.SogouInputMethodSwitcher");
            for (Method m : sogouSwitcherClass.getDeclaredMethods()) {
                String name = m.getName();
                if (("mayChangeInputMethodLocked".equals(name) || "shouldHideImeSwitcherLocked".equals(name))
                        && m.getReturnType() == boolean.class) {
                    m.setAccessible(true);
                    deoptimize(m);
                    hooks.add(hook(m).intercept(chain -> {
                        try {
                            if (coverImeEnabled()) {
                                return false;
                            }
                        } catch (Throwable t) {
                            error(t);
                        }
                        return chain.proceed();
                    }));
                } else if ("removeMethod".equals(name) && m.getReturnType() == void.class) {
                    m.setAccessible(true);
                    deoptimize(m);
                    hooks.add(hook(m).intercept(chain -> {
                        try {
                            if (coverImeEnabled()) {
                                return null;
                            }
                        } catch (Throwable t) {
                            error(t);
                        }
                        return chain.proceed();
                    }));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "SogouInputMethodSwitcher cover IME hook skipped", t);
        }

        try {
            Class<?> wcClass = Class.forName("android.app.WindowConfiguration");
            wcSetMaxBounds = optionalMethod(wcClass, "setMaxBounds", void.class, Rect.class);
            wcGetMaxBounds = optionalMethod(wcClass, "getMaxBounds", Rect.class);
            wcGetRotation = optionalMethod(wcClass, "getRotation", int.class);
            wcGetDisplayRotation = optionalMethod(wcClass, "getDisplayRotation", int.class);
            cfgCompatWField = optionalField(Configuration.class, "compatScreenWidthDp", int.class);
            cfgCompatHField = optionalField(Configuration.class, "compatScreenHeightDp", int.class);
            cfgCompatSwField = optionalField(Configuration.class, "compatSmallestScreenWidthDp", int.class);

            Class<?> ccClass = loader.loadClass("com.android.server.wm.ConfigurationContainer");
            Class<?> wcContainerClass = loader.loadClass("com.android.server.wm.WindowContainer");
            ccGetReqOverrideConfig = method(ccClass, "getRequestedOverrideConfiguration", Configuration.class);
            ccGetResolvedOverrideConfig = method(ccClass, "getResolvedOverrideConfiguration", Configuration.class);
            ccGetConfig = method(ccClass, "getConfiguration", Configuration.class);
            ccOnConfigChanged = method(ccClass, "onConfigurationChanged", void.class, Configuration.class);
            wcGetMergedOverrideConfig = method(wcContainerClass, "getMergedOverrideConfiguration", Configuration.class);

            Class<?> wpcClass = loader.loadClass("com.android.server.wm.WindowProcessController");
            wpcInfoField = field(wpcClass, "mInfo", ApplicationInfo.class);
            wpcUidField = field(wpcClass, "mUid", int.class);
            wpcHasImeServiceField = field(wpcClass, "mHasImeService", boolean.class);
            wpcLastReportedConfigField = field(wpcClass, "mLastReportedConfiguration", Configuration.class);
            wpcInitConfigField = optionalField(wpcClass, "mWindowProcessControllerConfig", Configuration.class);
            wpcConfigRecordField = optionalField(wpcClass, "mConfigActivityRecord", loader.loadClass("com.android.server.wm.ActivityRecord"));
            wpcCompatCtrlField = optionalField(wpcClass, "mCompatController", loader.loadClass("com.android.server.wm.ProcessCompatControllerStub"));
            wpcDispatchConfig = method(wpcClass, "dispatchConfiguration", void.class, Configuration.class);
            wpcGetActivitiesMethod = optionalMethod(wpcClass, "getActivities", java.util.List.class);
            Method wpcResolveOverride = method(wpcClass, "resolveOverrideConfiguration", void.class, Configuration.class);
            Method wpcOnConfigChanged = method(wpcClass, "onConfigurationChanged", void.class, Configuration.class);
            Method wpcGetInitConfig = optionalMethod(wpcClass, "getWindowProcessControllerConfig", Configuration.class);
            Method wpcOnServiceStarted = optionalMethod(wpcClass, "onServiceStarted", void.class, android.content.pm.ServiceInfo.class);

            for (Method m : wpcClass.getDeclaredMethods()) {
                String n = m.getName();
                if ("resolveOverrideConfiguration".equals(n)
                        || "onConfigurationChanged".equals(n)
                        || "dispatchConfiguration".equals(n)
                        || "getWindowProcessControllerConfig".equals(n)
                        || "onServiceStarted".equals(n)) {
                    if (!Modifier.isAbstract(m.getModifiers()) && !Modifier.isNative(m.getModifiers())) {
                        deoptimize(m);
                    }
                }
            }

            Class<?> pccClass = loader.loadClass("com.android.server.wm.ProcessCompatController");
            pccProcessField = field(pccClass, "mProcess", wpcClass);
            pccBoundsField = field(pccClass, "mBounds", Rect.class);
            pccCurrentScaleField = field(pccClass, "mCurrentScale", float.class);
            pccGlobalScaleField = field(pccClass, "mGlobalScale", float.class);
            pccStateField = field(pccClass, "mState", int.class);
            pccLastStateField = optionalField(pccClass, "mLastState", int.class);
            pccSendCompatState = method(pccClass, "sendCompatState", void.class);
            Method pccUpdateOverride = method(pccClass, "updateOverrideConfiguration", void.class, Configuration.class, Configuration.class);
            Method pccUpdateConfig = method(pccClass, "updateConfiguration", void.class, Configuration.class);
            Method pccGetCompatConfig = method(pccClass, "getCompatConfiguration", Configuration.class, Configuration.class);
            Method pccShouldNotUseFixedAspectRatio = optionalMethod(pccClass, "shouldNotUseFixedAspectRatio", boolean.class);

            for (Method m : pccClass.getDeclaredMethods()) {
                if (!Modifier.isAbstract(m.getModifiers()) && !Modifier.isNative(m.getModifiers())) {
                    deoptimize(m);
                }
            }

            if (pccShouldNotUseFixedAspectRatio != null) {
                hooks.add(hook(pccShouldNotUseFixedAspectRatio).intercept(chain -> {
                    try {
                        Object pcc = chain.getThisObject();
                        if (isNativeFlipTop(pcc)) {
                            clearFixedAspectState(pcc);
                            return true;
                        }
                    } catch (Throwable t) {
                        error(t);
                    }
                    return chain.proceed();
                }));
            }

            if (wpcGetInitConfig != null) {
                hooks.add(hook(wpcGetInitConfig).intercept(chain -> {
                    Configuration cfg = (Configuration) chain.proceed();
                    try {
                        Object wpc = chain.getThisObject();
                        if (wpc != null && cfg != null && imeHooksActive() && isThirdPartyImeProcess(wpc)) {
                            knownImeProcesses.add(wpc);
                            if (isCoverImeActiveForProcess()) {
                                // Always clone before mutating: wpc.mWindowProcessControllerConfig may alias
                                // ATMS.mGlobalConfiguration (mRootWindowContainer.mFullConfiguration).
                                Configuration copy = new Configuration(cfg);
                                int rot = getRotationFromConfig(copy);
                                adaptCoverImeConfiguration(copy, rot);
                                // Do NOT push FIXED_ASPECT_RATIO_STATE via out-of-band scheduleTransactionItemNow
                                // during process init before the client receives a full Configuration with appBounds.
                                return copy;
                            } else {
                                syncProcessCompatForIme(wpc, false, 0);
                                if (hasCoverImeOverride(cfg)) {
                                    Configuration dcCfg = getDefaultDisplayConfig();
                                    if (dcCfg != null) {
                                        return new Configuration(dcCfg);
                                    }
                                }
                                return new Configuration(cfg);
                            }
                        }
                    } catch (Throwable t) {
                        error(t);
                    }
                    return cfg;
                }));
            }

            if (wpcOnServiceStarted != null) {
                hooks.add(hook(wpcOnServiceStarted).intercept(chain -> {
                    Object res = chain.proceed();
                    try {
                        Object wpc = chain.getThisObject();
                        if (wpc != null && imeHooksActive() && isThirdPartyImeProcess(wpc)) {
                            knownImeProcesses.add(wpc);
                            boolean coverActive = isCoverImeActiveForProcess();
                            Configuration procCfg = (Configuration) ccGetConfig.invoke(wpc);
                            int rot = getRotationFromConfig(procCfg);
                            Configuration reqOverride = (Configuration) ccGetReqOverrideConfig.invoke(wpc);
                            Configuration resOverride = (Configuration) ccGetResolvedOverrideConfig.invoke(wpc);
                            if (coverActive) {
                                adaptCoverImeConfiguration(reqOverride, rot);
                                adaptCoverImeConfiguration(resOverride, rot);
                                adaptCoverImeConfiguration(procCfg, rot);
                                setIsolatedWpcInitConfig(wpc, null, true, rot);
                            } else {
                                procCfg = scrubInnerImeProcessConfig(wpc, procCfg);
                            }
                            if (wpcLastReportedConfigField != null) {
                                Configuration lastCfg = (Configuration) wpcLastReportedConfigField.get(wpc);
                                if (lastCfg != null && !isSharedSystemConfiguration(lastCfg)) {
                                    lastCfg.densityDpi = 1;
                                }
                            }
                            if (!coverActive) {
                                syncProcessCompatForIme(wpc, false, 0);
                                wpcDispatchConfig.invoke(wpc, procCfg);
                            } else {
                                wpcDispatchConfig.invoke(wpc, procCfg);
                                if (hasNonNullAppBounds(procCfg)) {
                                    syncProcessCompatForIme(wpc, true, rot);
                                }
                            }
                        }
                    } catch (Throwable t) {
                        error(t);
                    }
                    return res;
                }));
            }

            hooks.add(hook(pccSendCompatState).intercept(chain -> {
                try {
                    Object pcc = chain.getThisObject();
                    Object wpc = pcc != null ? pccProcessField.get(pcc) : null;
                    if (wpc != null && imeHooksActive() && isThirdPartyImeProcess(wpc)) {
                        Configuration procCfg = (Configuration) ccGetConfig.invoke(wpc);
                        if (isCoverImeActiveForProcess() && hasNonNullAppBounds(procCfg)) {
                            int rot = getRotationFromConfig(procCfg);
                            pccBoundsField.set(pcc, coverImeSafeBounds(rot));
                            pccCurrentScaleField.setFloat(pcc, 1.0f);
                            pccGlobalScaleField.setFloat(pcc, 1.0f);
                            pccStateField.setInt(pcc, FIXED_ASPECT_RATIO_STATE);
                        } else {
                            pccBoundsField.set(pcc, new Rect(0, 0, 1, 1));
                            pccCurrentScaleField.setFloat(pcc, 1.0f);
                            pccGlobalScaleField.setFloat(pcc, -1.0f);
                            pccStateField.setInt(pcc, 0);
                        }
                    } else if (isNativeFlipTop(pcc)) {
                        clearFixedAspectState(pcc);
                    }
                } catch (Throwable t) {
                    error(t);
                }
                return chain.proceed();
            }));

            hooks.add(hook(pccGetCompatConfig).intercept(chain -> {
                try {
                    Object pcc = chain.getThisObject();
                    Object wpc = pcc != null ? pccProcessField.get(pcc) : null;
                    if (wpc != null && imeHooksActive() && isThirdPartyImeProcess(wpc)) {
                        knownImeProcesses.add(wpc);
                        Configuration config = (Configuration) chain.getArg(0);
                        if (config == null) {
                            return chain.proceed();
                        }
                        if (isCoverImeActiveForProcess()) {
                            Configuration copy = new Configuration(config);
                            int rot = getRotationFromConfig(config);
                            adaptCoverImeConfiguration(copy, rot);
                            Configuration procCfg = (Configuration) ccGetConfig.invoke(wpc);
                            if (hasNonNullAppBounds(procCfg)) {
                                syncProcessCompatForIme(wpc, true, rot);
                            }
                            return copy;
                        } else {
                            syncProcessCompatForIme(wpc, false, 0);
                            // Always return an isolated clone so WPC.<init>'s mWindowProcessControllerConfig
                            // is detached from ATMS.mGlobalConfiguration from birth.
                            return new Configuration(config);
                        }
                    } else if (enabled() && isCoverFolded() && wpc != null && wpcInfoField != null) {
                        ApplicationInfo ai = (ApplicationInfo) wpcInfoField.get(wpc);
                        String procPkg = ai != null ? ai.packageName : null;
                        ModuleConfig.PerAppRule rule = procPkg != null ? ModuleConfig.perAppRule(procPkg) : null;
                        boolean nativeTop = isNativeFlipTop(pcc);
                        if (nativeTop) {
                            // Only push a new compat state when it actually changes; getCompatConfiguration is a hot
                            // getter and must not send IPC on every call.
                            if (clearFixedAspectState(pcc) && sendingCompatState.get() == null) {
                                sendingCompatState.set(Boolean.TRUE);
                                try {
                                    pccSendCompatState.invoke(pcc);
                                } finally {
                                    sendingCompatState.remove();
                                }
                            }
                        }
                        if (rule != null && rule.scalePercent() > 0
                                && (nativeTop || rule.widthMode() == ModuleConfig.WIDTH_MODE_FULL || rule.scalePercent() > 100)) {
                            Configuration baseCfg = (Configuration) (nativeTop ? chain.getArg(0) : chain.proceed());
                            if (baseCfg != null) {
                                Configuration copy = new Configuration(baseCfg);
                                applyPerAppCoverScaleConfig(copy, rule.scalePercent());
                                return copy;
                            }
                        }
                        if (nativeTop) {
                            return chain.getArg(0);
                        }
                    }
                } catch (Throwable t) {
                    error(t);
                }
                return chain.proceed();
            }));

            hooks.add(hook(pccUpdateOverride).intercept(chain -> {
                Object res = chain.proceed();
                try {
                    Object pcc = chain.getThisObject();
                    Object wpc = pcc != null ? pccProcessField.get(pcc) : null;
                    if (wpc != null && imeHooksActive() && isThirdPartyImeProcess(wpc)) {
                        Configuration overrideCfg = (Configuration) chain.getArg(0);
                        Configuration parentCfg = (Configuration) chain.getArg(1);
                        if (isCoverImeActiveForProcess()) {
                            int rot = getRotationFromConfig(parentCfg);
                            adaptCoverImeConfiguration(overrideCfg, rot);
                            Configuration procCfg = (Configuration) ccGetConfig.invoke(wpc);
                            if (hasNonNullAppBounds(procCfg)) {
                                syncProcessCompatForIme(wpc, true, rot);
                            }
                        } else {
                            clearCoverImeOverride(overrideCfg);
                            syncProcessCompatForIme(wpc, false, 0);
                        }
                    }
                } catch (Throwable t) {
                    error(t);
                }
                return res;
            }));

            hooks.add(hook(pccUpdateConfig).intercept(chain -> {
                Object res = chain.proceed();
                try {
                    Object pcc = chain.getThisObject();
                    Object wpc = pcc != null ? pccProcessField.get(pcc) : null;
                    if (wpc != null && imeHooksActive() && isThirdPartyImeProcess(wpc)) {
                        Configuration cfg = (Configuration) chain.getArg(0);
                        if (isCoverImeActiveForProcess()) {
                            int rot = getRotationFromConfig(cfg);
                            adaptCoverImeConfiguration(cfg, rot);
                            if (hasNonNullAppBounds(cfg)) {
                                syncProcessCompatForIme(wpc, true, rot);
                            }
                        } else {
                            syncProcessCompatForIme(wpc, false, 0);
                            scrubInnerImeProcessConfig(wpc, cfg);
                        }
                    }
                } catch (Throwable t) {
                    error(t);
                }
                return res;
            }));

            hooks.add(hook(wpcResolveOverride).intercept(chain -> {
                try {
                    Object wpc = chain.getThisObject();
                    if (wpc != null && imeHooksActive() && isThirdPartyImeProcess(wpc)) {
                        Configuration reqOverride = (Configuration) ccGetReqOverrideConfig.invoke(wpc);
                        if (isCoverImeActiveForProcess()) {
                            Configuration parentCfg = (Configuration) chain.getArg(0);
                            adaptCoverImeConfiguration(reqOverride, getRotationFromConfig(parentCfg));
                        } else {
                            clearCoverImeOverride(reqOverride);
                        }
                    }
                } catch (Throwable t) {
                    error(t);
                }
                Object res = chain.proceed();
                try {
                    Object wpc = chain.getThisObject();
                    if (wpc != null && imeHooksActive() && isThirdPartyImeProcess(wpc)) {
                        if (isCoverImeActiveForProcess()) {
                            Configuration parentCfg = (Configuration) chain.getArg(0);
                            int rot = getRotationFromConfig(parentCfg);
                            Configuration resolved = (Configuration) ccGetResolvedOverrideConfig.invoke(wpc);
                            adaptCoverImeConfiguration(resolved, rot);
                        }
                    }
                } catch (Throwable t) {
                    error(t);
                }
                return res;
            }));

            hooks.add(hook(wpcOnConfigChanged).intercept(chain -> {
                try {
                    Object wpc = chain.getThisObject();
                    if (wpc != null && imeHooksActive() && isThirdPartyImeProcess(wpc)) {
                        Configuration reqOverride = (Configuration) ccGetReqOverrideConfig.invoke(wpc);
                        Configuration resOverride = (Configuration) ccGetResolvedOverrideConfig.invoke(wpc);
                        if (isCoverImeActiveForProcess()) {
                            Configuration newParent = (Configuration) chain.getArg(0);
                            int rot = getRotationFromConfig(newParent);
                            adaptCoverImeConfiguration(reqOverride, rot);
                            adaptCoverImeConfiguration(resOverride, rot);
                        } else {
                            clearCoverImeOverride(reqOverride);
                            clearCoverImeOverride(resOverride);
                        }
                    }
                } catch (Throwable t) {
                    error(t);
                }
                Object res = chain.proceed();
                try {
                    Object wpc = chain.getThisObject();
                    if (wpc != null && imeHooksActive() && isThirdPartyImeProcess(wpc)) {
                        if (isCoverImeActiveForProcess()) {
                            Configuration newParent = (Configuration) chain.getArg(0);
                            int rot = getRotationFromConfig(newParent);
                            Configuration full = (Configuration) ccGetConfig.invoke(wpc);
                            adaptCoverImeConfiguration(full, rot);
                        }
                    }
                } catch (Throwable t) {
                    error(t);
                }
                return res;
            }));

            hooks.add(hook(wpcDispatchConfig).intercept(chain -> {
                Object wpc = null;
                Configuration cfg = null;
                Configuration overrideArg = null;
                boolean pushCoverCompatAfter = false;
                int pushRot = 0;
                try {
                    wpc = chain.getThisObject();
                    if (wpc != null && imeHooksActive() && isThirdPartyImeProcess(wpc)) {
                        knownImeProcesses.add(wpc);
                        cfg = (Configuration) chain.getArg(0);
                        if (cfg != null) {
                            if (isCoverImeActiveForProcess()) {
                                int rot = getRotationFromConfig(cfg);
                                Configuration dispatchCopy = new Configuration(cfg);
                                adaptCoverImeConfiguration(dispatchCopy, rot);
                                overrideArg = dispatchCopy;
                                Configuration full = (Configuration) ccGetConfig.invoke(wpc);
                                adaptCoverImeConfiguration(full, rot);
                                pushCoverCompatAfter = hasNonNullAppBounds(dispatchCopy);
                                pushRot = rot;
                            } else {
                                // Clear state=0 BEFORE dispatching inner/mirror config so client never runs BoundsCompat
                                // against a transitioning Configuration.
                                syncProcessCompatForIme(wpc, false, 0);
                                Configuration scrubbed = scrubInnerImeProcessConfig(wpc, cfg);
                                if (scrubbed != null && scrubbed != cfg) {
                                    overrideArg = scrubbed;
                                }
                            }
                        }
                    }
                } catch (Throwable t) {
                    error(t);
                }
                Object res = overrideArg != null
                        ? chain.proceed(new Object[]{overrideArg})
                        : chain.proceed();
                if (pushCoverCompatAfter && wpc != null) {
                    try {
                        // Push state=8 AFTER ConfigurationChangeItem is queued with non-null appBounds.
                        syncProcessCompatForIme(wpc, true, pushRot);
                    } catch (Throwable t) {
                        error(t);
                    }
                }
                return res;
            }));

            Class<?> winClass = loader.loadClass("com.android.server.wm.WindowState");
            Class<?> sessionClass = loader.loadClass("com.android.server.wm.Session");
            Class<?> cwfClass = Class.forName("android.window.ClientWindowFrames");
            Class<?> mcClass = Class.forName("android.util.MergedConfiguration");
            Class<?> awiClass = Class.forName("android.window.ActivityWindowInfo");

            winSessionField = field(winClass, "mSession", sessionClass);
            sessionProcessField = field(sessionClass, "mProcess", wpcClass);
            winLastReportedConfigField = field(winClass, "mLastReportedConfiguration", mcClass);
            winLastReportedFramesField = field(winClass, "mLastReportedFrames", cwfClass);

            cwfFrameField = field(cwfClass, "frame", Rect.class);
            cwfDisplayFrameField = field(cwfClass, "displayFrame", Rect.class);
            cwfParentFrameField = field(cwfClass, "parentFrame", Rect.class);
            cwfAttachedFrameField = optionalField(cwfClass, "attachedFrame", Rect.class);

            mcGetGlobalConfig = method(mcClass, "getGlobalConfiguration", Configuration.class);
            mcGetOverrideConfig = method(mcClass, "getOverrideConfiguration", Configuration.class);
            mcGetMergedConfig = method(mcClass, "getMergedConfiguration", Configuration.class);

            Method winSetFrames = method(winClass, "setFrames", void.class, cwfClass, int.class, int.class);
            Method winFillFramesAndConfig = method(
                    winClass,
                    "fillClientWindowFramesAndConfiguration",
                    void.class,
                    cwfClass,
                    mcClass,
                    awiClass,
                    boolean.class,
                    boolean.class);

            deoptimize(winSetFrames);
            deoptimize(winFillFramesAndConfig);

            hooks.add(hook(winSetFrames).intercept(chain -> {
                try {
                    Object win = chain.getThisObject();
                    if (isThirdPartyImeWindow(win)) {
                        if (isCoverImeActiveForWindow(win)) {
                            int rot = getWindowRotation(win);
                            ensureCoverImeWindowAttrs(win, rot);
                            clampClientFramesHorizontally(chain.getArg(0), rot);
                        } else if (imeHooksActive()) {
                            restoreInnerImeWindowAttrs(win);
                        }
                    }
                } catch (Throwable t) {
                    error(t);
                }
                return chain.proceed();
            }));

            hooks.add(hook(winFillFramesAndConfig).intercept(chain -> {
                Object res = chain.proceed();
                try {
                    Object win = chain.getThisObject();
                    if (isThirdPartyImeWindow(win)) {
                        if (isCoverImeActiveForWindow(win)) {
                            int rot = getWindowRotation(win);
                            ensureCoverImeWindowAttrs(win, rot);
                            Object outFrames = chain.getArg(0);
                            Object outMerged = chain.getArg(1);
                            clampClientFramesHorizontally(outFrames, rot);
                            Object lastFrames = winLastReportedFramesField.get(win);
                            if (lastFrames != null && lastFrames != outFrames) {
                                clampClientFramesHorizontally(lastFrames, rot);
                            }
                            adaptMergedConfiguration(outMerged, rot);
                            Object lastMerged = winLastReportedConfigField.get(win);
                            if (lastMerged != null && lastMerged != outMerged) {
                                adaptMergedConfiguration(lastMerged, rot);
                            }
                            Configuration winCfg = (Configuration) ccGetConfig.invoke(win);
                            adaptCoverImeConfiguration(winCfg, rot);
                            Configuration winMergedOverride = (Configuration) wcGetMergedOverrideConfig.invoke(win);
                            adaptCoverImeConfiguration(winMergedOverride, rot);
                        } else if (imeHooksActive()) {
                            restoreInnerImeWindowAttrs(win);
                        }
                    }
                } catch (Throwable t) {
                    error(t);
                }
                return res;
            }));

            Class<?> dInfoClass = Class.forName("android.view.DisplayInfo");
            infoAppWidth = optionalField(dInfoClass, "appWidth", int.class);
            infoAppHeight = optionalField(dInfoClass, "appHeight", int.class);
            infoDensityDpi = optionalField(dInfoClass, "logicalDensityDpi", int.class);
            infoSmallestW = optionalField(dInfoClass, "smallestNominalAppWidth", int.class);
            infoSmallestH = optionalField(dInfoClass, "smallestNominalAppHeight", int.class);
            infoLargestW = optionalField(dInfoClass, "largestNominalAppWidth", int.class);
            infoLargestH = optionalField(dInfoClass, "largestNominalAppHeight", int.class);
            infoRotation = optionalField(dInfoClass, "rotation", int.class);

            Class<?> dmsBinderClass = loader.loadClass("com.android.server.display.DisplayManagerService$BinderService");
            Method getDisplayInfoMethod = method(dmsBinderClass, "getDisplayInfo", dInfoClass, int.class);
            deoptimize(getDisplayInfoMethod);
            hooks.add(hook(getDisplayInfoMethod).intercept(chain -> {
                Object infoObj = chain.proceed();
                try {
                    int displayId = (Integer) chain.getArg(0);
                    if (infoObj != null
                            && displayId == 0
                            && isCoverImeActiveForProcess()
                            && isThirdPartyImeUid(Binder.getCallingUid())
                            && Binder.getCallingPid() != android.os.Process.myPid()) {
                        Object copy = infoCopy.newInstance(infoObj);
                        int rot = getCurrentCoverDisplayRotation();
                        if (rot < 0) rot = infoRotation != null ? infoRotation.getInt(copy) : 0;
                        if (infoRotation != null) infoRotation.setInt(copy, rot);
                        int scalePct = ModuleConfig.imeDpiScalePercent();
                        int imeW = CoverDisplay.coverImeWidth(rot);
                        int imeH = CoverDisplay.coverImeDisplayHeight(rot);
                        int imeDpi = CoverDisplay.coverImeDensityDpi(rot, scalePct);
                        appliedCoverImeDpis.add(imeDpi);
                        int minDim = Math.min(imeW, imeH);
                        int maxDim = Math.max(imeW, imeH);
                        infoWidth.setInt(copy, imeW);
                        infoHeight.setInt(copy, imeH);
                        if (infoAppWidth != null) infoAppWidth.setInt(copy, imeW);
                        if (infoAppHeight != null) infoAppHeight.setInt(copy, imeH);
                        if (infoDensityDpi != null) infoDensityDpi.setInt(copy, imeDpi);
                        if (infoSmallestW != null) infoSmallestW.setInt(copy, minDim);
                        if (infoSmallestH != null) infoSmallestH.setInt(copy, minDim);
                        if (infoLargestW != null) infoLargestW.setInt(copy, maxDim);
                        if (infoLargestH != null) infoLargestH.setInt(copy, maxDim);
                        if (infoDisplayCutout != null && noCutout != null) {
                            infoDisplayCutout.set(copy, noCutout);
                        }
                        return copy;
                    }
                } catch (Throwable t) {
                    error(t);
                }
                return infoObj;
            }));

            ModLog.recordHookHealth("ime_hooks", "ok");
            Log.i(TAG, "cover third-party IME bounds & density adaptation hooks installed");
        } catch (Throwable t) {
            ModLog.recordHookHealth("ime_hooks", "err:" + t.getClass().getSimpleName());
            Log.w(TAG, "cover third-party IME bounds & density adaptation hook failed", t);
        }
    }

    private static boolean isBuiltInCoverImePackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        return "com.sohu.inputmethod.sogou.xiaomi".equals(pkg)
                || "android".equals(pkg)
                || pkg.startsWith("com.miui.")
                || pkg.startsWith("com.xiaomi.")
                || pkg.startsWith("com.android.");
    }

    private void refreshActiveThirdPartyImeUid(Context ctx) {
        if (ctx == null) return;
        try {
            android.content.ContentResolver cr = ctx.getContentResolver();
            String ime = android.provider.Settings.Secure.getString(
                    cr,
                    android.provider.Settings.Secure.DEFAULT_INPUT_METHOD);
            String pkg = extractImePackage(ime);
            if (isBuiltInCoverImePackage(pkg)) {
                String backupIme = android.provider.Settings.Secure.getString(
                        cr,
                        "internal_screen_input_method_in_flip");
                pkg = extractImePackage(backupIme);
            }
            if (isBuiltInCoverImePackage(pkg)) {
                String enabledImes = android.provider.Settings.Secure.getString(
                        cr,
                        android.provider.Settings.Secure.ENABLED_INPUT_METHODS);
                if (enabledImes != null) {
                    for (String entry : enabledImes.split(":")) {
                        String candidate = extractImePackage(entry);
                        if (!isBuiltInCoverImePackage(candidate)) {
                            pkg = candidate;
                            break;
                        }
                    }
                }
            }
            if (isBuiltInCoverImePackage(pkg)) {
                return;
            }
            PackageManager pm = ctx.getPackageManager();
            if (pm != null) {
                int uid = pm.getPackageUid(pkg, 0) % 100000;
                if (uid >= 10000) {
                    activeThirdPartyImePkg = pkg;
                    activeThirdPartyImeAppId = uid;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static String extractImePackage(String imeId) {
        if (imeId == null || imeId.isEmpty()) return null;
        int semi = imeId.indexOf(';');
        String base = semi > 0 ? imeId.substring(0, semi) : imeId;
        int slash = base.indexOf('/');
        return slash > 0 ? base.substring(0, slash) : base;
    }

    /**
     * Pure cache lookup. It is called from WindowManager/DisplayManager binder paths, sometimes while the WM global
     * lock is held, so it must never call into Settings or PackageManager. The cache is refreshed off-lock by
     * {@link #refreshActiveThirdPartyImeUid} (boot, IME settings observer, fold transitions).
     */
    private boolean isThirdPartyImeUid(int uid) {
        int appId = uid % 100000;
        if (appId < 10000) return false;
        return appId == activeThirdPartyImeAppId;
    }

    private boolean isThirdPartyImeProcess(Object wpc) throws ReflectiveOperationException {
        if (wpc == null || wpcInfoField == null) return false;
        ApplicationInfo ai = (ApplicationInfo) wpcInfoField.get(wpc);
        if (ai == null || isBuiltInCoverImePackage(ai.packageName)) return false;
        int uid = wpcUidField.getInt(wpc);
        if (wpcHasImeServiceField.getBoolean(wpc)) {
            int appId = uid % 100000;
            if (appId >= 10000) {
                activeThirdPartyImeAppId = appId;
                activeThirdPartyImePkg = ai.packageName;
            }
            knownImeProcesses.add(wpc);
            return true;
        }
        if (wpcConfigRecordField != null && wpcConfigRecordField.get(wpc) != null) {
            return false;
        }
        if (isThirdPartyImeUid(uid)) {
            knownImeProcesses.add(wpc);
            return true;
        }
        String targetPkg = activeThirdPartyImePkg;
        if (targetPkg != null && targetPkg.equals(ai.packageName)) {
            knownImeProcesses.add(wpc);
            return true;
        }
        return false;
    }

    private boolean isThirdPartyImeWindow(Object win) throws ReflectiveOperationException {
        if (win == null) return false;
        WindowManager.LayoutParams p = (WindowManager.LayoutParams) attrs.get(win);
        if (p == null || (p.type != 2011 && p.type != 2012)) return false;
        if (isBuiltInCoverImePackage(p.packageName)) return false;
        if (ownerUidField != null) {
            int appId = ownerUidField.getInt(win) % 100000;
            if (appId >= 10000) {
                activeThirdPartyImeAppId = appId;
                activeThirdPartyImePkg = p.packageName;
            }
        }
        return true;
    }

    private static Rect coverImeSafeBounds(int rotation) {
        return CoverDisplay.coverImeSafeBounds(rotation);
    }

    /**
     * Lock-free reader safe to invoke from DMS.getDisplayInfo (which must NEVER acquire WMS.mGlobalLock to avoid
     * DMS<->WMS AB-BA deadlock). Returns the cached rotation maintained by locked WM paths.
     */
    private int getCurrentCoverDisplayRotation() {
        return cachedCoverDisplayRotation;
    }

    /** Caller must already hold WMS.mGlobalLock. */
    private int refreshCoverDisplayRotationLocked(Object dc) {
        try {
            if (dc != null && getInfo != null && infoRotation != null) {
                Object di = getInfo.invoke(dc);
                if (di != null) {
                    int r = infoRotation.getInt(di);
                    if (r >= 0) {
                        cachedCoverDisplayRotation = r;
                        return r;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return cachedCoverDisplayRotation;
    }

    private int getRotationFromConfig(Configuration cfg) {
        if (cfg != null && windowConfigField != null) {
            try {
                Object wc = windowConfigField.get(cfg);
                if (wc != null) {
                    if (wcGetDisplayRotation != null) {
                        int dr = (Integer) wcGetDisplayRotation.invoke(wc);
                        if (dr >= 0) {
                            if (isCoverFolded()) cachedCoverDisplayRotation = dr;
                            return dr;
                        }
                    }
                    if (wcGetRotation != null) {
                        int r = (Integer) wcGetRotation.invoke(wc);
                        if (r >= 0) {
                            if (isCoverFolded()) cachedCoverDisplayRotation = r;
                            return r;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        int cached = cachedCoverDisplayRotation;
        return cached >= 0 ? cached : 0;
    }

    private int getWindowRotation(Object win) {
        try {
            if (win != null && getDisplay != null && getInfo != null && infoRotation != null) {
                Object dc = getDisplay.invoke(win);
                if (dc != null) {
                    Object di = getInfo.invoke(dc);
                    if (di != null) {
                        int r = infoRotation.getInt(di);
                        if (r >= 0) {
                            if (isCoverFolded() && isWindowOnDefaultDisplay(win)) {
                                cachedCoverDisplayRotation = r;
                            }
                            return r;
                        }
                    }
                }
            }
            if (win != null && ccGetConfig != null) {
                Configuration cfg = (Configuration) ccGetConfig.invoke(win);
                return getRotationFromConfig(cfg);
            }
        } catch (Throwable ignored) {
        }
        int cached = cachedCoverDisplayRotation;
        return cached >= 0 ? cached : 0;
    }

    private void ensureCoverImeWindowAttrs(Object win, int rotation) {
        try {
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) attrs.get(win);
            if (lp != null) {
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                lp.setFitInsetsTypes(lp.getFitInsetsTypes() & ~WindowInsets.Type.displayCutout());
                int targetW = CoverDisplay.coverImeWidth(rotation);
                if (lp.width > 0 && lp.width != targetW) {
                    lp.width = targetW;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void restoreInnerImeWindowAttrs(Object win) {
        try {
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) attrs.get(win);
            if (lp != null) {
                if (lp.type == 2011 && CoverDisplay.isCoverImeWidth(lp.width)) {
                    lp.width = WindowManager.LayoutParams.MATCH_PARENT;
                    lp.x = 0;
                }
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
                lp.setFitInsetsTypes(lp.getFitInsetsTypes() | WindowInsets.Type.displayCutout());
            }
        } catch (Throwable ignored) {
        }
    }

    private Configuration getDefaultDisplayConfig() {
        try {
            Object s = service;
            Object root = (s != null && serviceRoot != null) ? serviceRoot.get(s) : null;
            Object dc = (root != null && rootDisplay != null) ? rootDisplay.invoke(root, 0) : null;
            return (dc != null && ccGetConfig != null) ? (Configuration) ccGetConfig.invoke(dc) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Hard identity guard preventing any in-place mutation of shared global/display Configuration
     * instances ({@code mRootWindowContainer}'s global config or {@code DisplayContent(0)}'s config).
     */
    private boolean isSharedSystemConfiguration(Configuration cfg) {
        if (cfg == null) return false;
        if (EMPTY_CONFIG != null && cfg == EMPTY_CONFIG) return true;
        try {
            Object s = service;
            Object root = (s != null && serviceRoot != null) ? serviceRoot.get(s) : null;
            if (root != null && ccGetConfig != null) {
                if (cfg == ccGetConfig.invoke(root)) return true;
                if (ccGetReqOverrideConfig != null && cfg == ccGetReqOverrideConfig.invoke(root)) return true;
                if (ccGetResolvedOverrideConfig != null && cfg == ccGetResolvedOverrideConfig.invoke(root)) return true;
                Object dc0 = rootDisplay != null ? rootDisplay.invoke(root, 0) : null;
                if (dc0 != null) {
                    if (cfg == ccGetConfig.invoke(dc0)) return true;
                    if (ccGetReqOverrideConfig != null && cfg == ccGetReqOverrideConfig.invoke(dc0)) return true;
                    if (ccGetResolvedOverrideConfig != null && cfg == ccGetResolvedOverrideConfig.invoke(dc0)) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void setIsolatedWpcInitConfig(Object wpc, Configuration source, boolean adaptCover, int rot)
            throws ReflectiveOperationException {
        if (wpc == null || wpcInitConfigField == null) return;
        Configuration base = source != null ? source : (Configuration) wpcInitConfigField.get(wpc);
        if (base == null) return;
        if (!adaptCover && hasCoverImeOverride(base)) {
            Configuration dcCfg = getDefaultDisplayConfig();
            if (dcCfg != null) {
                wpcInitConfigField.set(wpc, new Configuration(dcCfg));
                return;
            }
        }
        Configuration copy = new Configuration(base);
        if (adaptCover) {
            adaptCoverImeConfiguration(copy, rot);
        } else if (hasCoverImeOverride(copy)) {
            clearCoverImeOverride(copy);
        }
        wpcInitConfigField.set(wpc, copy);
    }

    private void applyPerAppCoverScaleConfig(Configuration cfg, int scalePercent) throws ReflectiveOperationException {
        if (cfg == null || scalePercent <= 0) return;
        if (isSharedSystemConfiguration(cfg)) {
            ModLog.w(TAG, "refused in-place mutation of shared system Configuration in applyPerAppCoverScaleConfig");
            return;
        }
        int targetDpi = Math.max(240, Math.min(640, Math.round(CoverDisplay.DENSITY_DPI * (scalePercent / 100f))));
        int oldDpi = cfg.densityDpi > 0 ? cfg.densityDpi : CoverDisplay.DENSITY_DPI;
        cfg.densityDpi = targetDpi;
        Rect bounds = null;
        if (windowConfigField != null && wcGetAppBounds != null) {
            Object wc = windowConfigField.get(cfg);
            if (wc != null) bounds = (Rect) wcGetAppBounds.invoke(wc);
        }
        int wDp;
        int hDp;
        if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
            wDp = Math.max(200, Math.round(bounds.width() * 160f / targetDpi));
            hDp = Math.max(200, Math.round(bounds.height() * 160f / targetDpi));
        } else {
            wDp = Math.max(200, Math.round(cfg.screenWidthDp * (oldDpi / (float) targetDpi)));
            hDp = Math.max(200, Math.round(cfg.screenHeightDp * (oldDpi / (float) targetDpi)));
        }
        int swDp = Math.min(wDp, hDp);
        cfg.screenWidthDp = wDp;
        cfg.screenHeightDp = hDp;
        cfg.smallestScreenWidthDp = swDp;
        if (cfgCompatWField != null) cfgCompatWField.setInt(cfg, wDp);
        if (cfgCompatHField != null) cfgCompatHField.setInt(cfg, hDp);
        if (cfgCompatSwField != null) cfgCompatSwField.setInt(cfg, swDp);
    }

    private void adaptCoverImeConfiguration(Configuration cfg, int rotation) throws ReflectiveOperationException {
        if (cfg == null) return;
        if (isSharedSystemConfiguration(cfg)) {
            ModLog.w(TAG, "refused in-place mutation of shared system Configuration in adaptCoverImeConfiguration");
            return;
        }
        Rect safe = coverImeSafeBounds(rotation);
        int scalePct = ModuleConfig.imeDpiScalePercent();
        int dpi = CoverDisplay.coverImeDensityDpi(rotation, scalePct);
        int wDp = CoverDisplay.coverImeScreenWidthDp(rotation, scalePct);
        int hDp = CoverDisplay.coverImeScreenHeightDp(rotation, scalePct);
        appliedCoverImeDpis.add(dpi);
        Object wc = windowConfigField.get(cfg);
        if (wc != null) {
            wcSetBounds.invoke(wc, safe);
            wcSetAppBounds.invoke(wc, new Rect(safe));
            if (wcSetMaxBounds != null) wcSetMaxBounds.invoke(wc, new Rect(safe));
        }
        cfg.densityDpi = dpi;
        cfg.screenWidthDp = wDp;
        cfg.smallestScreenWidthDp = wDp;
        cfg.screenHeightDp = hDp;
        if (cfgCompatWField != null) cfgCompatWField.setInt(cfg, wDp);
        if (cfgCompatSwField != null) cfgCompatSwField.setInt(cfg, wDp);
        if (cfgCompatHField != null) cfgCompatHField.setInt(cfg, hDp);
        cfg.orientation = Configuration.ORIENTATION_PORTRAIT;
    }

    private boolean hasCoverImeOverride(Configuration cfg) throws ReflectiveOperationException {
        if (cfg == null) return false;
        Object wc = windowConfigField.get(cfg);
        Rect b = wc != null ? (Rect) wcGetBounds.invoke(wc) : null;
        return cfg.densityDpi == CoverDisplay.IME_DENSITY_DPI_ROT0
                || cfg.densityDpi == CoverDisplay.IME_DENSITY_DPI_WIDE
                || appliedCoverImeDpis.contains(cfg.densityDpi)
                || (b != null && (CoverDisplay.isOuterDimensions(b.right, b.bottom)
                || (b.width() == COVER_SAFE_WIDTH && b.height() == COVER_DISPLAY_HEIGHT)
                || (b.width() == CoverDisplay.CAMERA_TOP && b.height() == CoverDisplay.WIDTH)));
    }

    private boolean hasNonNullAppBounds(Configuration cfg) {
        if (cfg == null || windowConfigField == null || wcGetAppBounds == null) return false;
        try {
            Object wc = windowConfigField.get(cfg);
            return wc != null && wcGetAppBounds.invoke(wc) instanceof Rect r && !r.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Configuration scrubInnerImeProcessConfig(Object wpc, Configuration cfg) throws ReflectiveOperationException {
        clearCoverImeOverride((Configuration) ccGetReqOverrideConfig.invoke(wpc));
        clearCoverImeOverride((Configuration) ccGetResolvedOverrideConfig.invoke(wpc));
        if (wpcInitConfigField != null) {
            Configuration initCfg = (Configuration) wpcInitConfigField.get(wpc);
            if (initCfg != null && hasCoverImeOverride(initCfg)) {
                Configuration dcCfg = getDefaultDisplayConfig();
                if (dcCfg != null) {
                    wpcInitConfigField.set(wpc, new Configuration(dcCfg));
                } else {
                    Configuration initCopy = new Configuration(initCfg);
                    clearCoverImeOverride(initCopy);
                    wpcInitConfigField.set(wpc, initCopy);
                }
            } else if (initCfg != null && isSharedSystemConfiguration(initCfg)) {
                wpcInitConfigField.set(wpc, new Configuration(initCfg));
            }
        }
        // Never call clearCoverImeOverride(cfg) directly on a full dispatched Configuration (which would set
        // windowConfiguration.mAppBounds = null and crash BoundsCompat.adaptDisplayInfo:100 with NPE). Always
        // restore from DisplayContent(0)'s complete Configuration instead.
        if ((!isCoverFolded() || CoverImeGuard.isMirrorVirtualDisplayActive()) && hasCoverImeOverride(cfg)) {
            try {
                Configuration dcCfg = getDefaultDisplayConfig();
                if (dcCfg != null) {
                    Configuration full = (Configuration) ccGetConfig.invoke(wpc);
                    if (full != null && !isSharedSystemConfiguration(full)) {
                        full.setTo(dcCfg);
                    }
                    if (wpcInitConfigField != null) {
                        wpcInitConfigField.set(wpc, new Configuration(dcCfg));
                    }
                    return new Configuration(dcCfg);
                }
            } catch (Throwable ignored) {
            }
        }
        return cfg;
    }

    private void clearCoverImeOverride(Configuration reqOverride) throws ReflectiveOperationException {
        if (reqOverride == null) return;
        if (isSharedSystemConfiguration(reqOverride)) {
            ModLog.w(TAG, "refused in-place mutation of shared system Configuration in clearCoverImeOverride");
            return;
        }
        if (!hasCoverImeOverride(reqOverride)) return;
        Object wc = windowConfigField.get(reqOverride);
        if (wc != null) {
            wcSetBounds.invoke(wc, (Object) null);
            wcSetAppBounds.invoke(wc, (Object) null);
            if (wcSetMaxBounds != null) wcSetMaxBounds.invoke(wc, (Object) null);
        }
        reqOverride.densityDpi = Configuration.DENSITY_DPI_UNDEFINED;
        reqOverride.screenWidthDp = Configuration.SCREEN_WIDTH_DP_UNDEFINED;
        reqOverride.smallestScreenWidthDp = Configuration.SMALLEST_SCREEN_WIDTH_DP_UNDEFINED;
        reqOverride.screenHeightDp = Configuration.SCREEN_HEIGHT_DP_UNDEFINED;
        if (cfgCompatWField != null) cfgCompatWField.setInt(reqOverride, Configuration.SCREEN_WIDTH_DP_UNDEFINED);
        if (cfgCompatSwField != null) cfgCompatSwField.setInt(reqOverride, Configuration.SMALLEST_SCREEN_WIDTH_DP_UNDEFINED);
        if (cfgCompatHField != null) cfgCompatHField.setInt(reqOverride, Configuration.SCREEN_HEIGHT_DP_UNDEFINED);
        reqOverride.orientation = Configuration.ORIENTATION_UNDEFINED;
    }

    private void adaptMergedConfiguration(Object mergedCfg, int rotation) throws ReflectiveOperationException {
        if (mergedCfg == null) return;
        Configuration g = (Configuration) mcGetGlobalConfig.invoke(mergedCfg);
        Configuration o = (Configuration) mcGetOverrideConfig.invoke(mergedCfg);
        Configuration m = (Configuration) mcGetMergedConfig.invoke(mergedCfg);
        adaptCoverImeConfiguration(g, rotation);
        adaptCoverImeConfiguration(o, rotation);
        adaptCoverImeConfiguration(m, rotation);
    }

    private void clampClientFramesHorizontally(Object frames, int rotation) throws ReflectiveOperationException {
        if (frames == null) return;
        int safeLeft = CoverDisplay.coverImeSafeLeft(rotation);
        int safeRight = CoverDisplay.coverImeSafeRight(rotation);
        int maxBottom = CoverDisplay.coverImeDisplayHeight(rotation);
        clampRectHorizontally((Rect) cwfFrameField.get(frames), safeLeft, safeRight, maxBottom);
        clampRectHorizontally((Rect) cwfDisplayFrameField.get(frames), safeLeft, safeRight, maxBottom);
        clampRectHorizontally((Rect) cwfParentFrameField.get(frames), safeLeft, safeRight, maxBottom);
        if (cwfAttachedFrameField != null) {
            clampRectHorizontally((Rect) cwfAttachedFrameField.get(frames), safeLeft, safeRight, maxBottom);
        }
    }

    private static void clampRectHorizontally(Rect r, int safeLeft, int safeRight, int maxBottom) {
        if (r == null || r.isEmpty()) return;
        r.left = safeLeft;
        r.right = safeRight;
        if (r.bottom > maxBottom) {
            r.bottom = maxBottom;
        }
        if (r.top < 0 || r.top >= r.bottom) {
            r.top = 0;
        }
    }

    /** IME compat hooks only act while the feature is on, or once more to undo a previously applied state. */
    private boolean imeHooksActive() {
        return installed && (imeCompatApplied || coverImeEnabled());
    }

    /** Lock-free check: cover IME adaptation pauses while an active mirror/virtual display is hosting windows. */
    private boolean isCoverImeActiveForProcess() {
        return isCoverFolded() && coverImeEnabled() && !CoverImeGuard.isMirrorVirtualDisplayActive();
    }

    private boolean isWindowOnDefaultDisplay(Object win) {
        if (win == null || getDisplay == null || displayGetIdMethod == null) return true;
        try {
            Object dc = getDisplay.invoke(win);
            if (dc == null) return false;
            return (Integer) displayGetIdMethod.invoke(dc) == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isCoverImeActiveForWindow(Object win) {
        return isCoverImeActiveForProcess() && isWindowOnDefaultDisplay(win);
    }

    private void syncProcessCompatForIme(Object wpc, boolean coverActive, int rotation) {
        if (wpc == null || wpcCompatCtrlField == null || pccBoundsField == null || pccSendCompatState == null) return;
        try {
            Object pcc = wpcCompatCtrlField.get(wpc);
            if (pcc == null) return;
            Rect wantBounds = coverActive ? coverImeSafeBounds(rotation) : new Rect(0, 0, 1, 1);
            float wantGlobal = coverActive ? 1.0f : -1.0f;
            int wantState = coverActive ? FIXED_ASPECT_RATIO_STATE : 0;
            if (wantBounds.equals(pccBoundsField.get(pcc))
                    && pccCurrentScaleField.getFloat(pcc) == 1.0f
                    && pccGlobalScaleField.getFloat(pcc) == wantGlobal
                    && pccStateField.getInt(pcc) == wantState) {
                return;
            }
            if (sendingCompatState.get() != null) return;
            if (coverActive) {
                Rect safe = coverImeSafeBounds(rotation);
                pccBoundsField.set(pcc, safe);
                pccCurrentScaleField.setFloat(pcc, 1.0f);
                pccGlobalScaleField.setFloat(pcc, 1.0f);
                pccStateField.setInt(pcc, FIXED_ASPECT_RATIO_STATE);
            } else {
                pccBoundsField.set(pcc, new Rect(0, 0, 1, 1));
                pccCurrentScaleField.setFloat(pcc, 1.0f);
                pccGlobalScaleField.setFloat(pcc, -1.0f);
                pccStateField.setInt(pcc, 0);
            }
            if (pccLastStateField != null) {
                pccLastStateField.setInt(pcc, -1);
            }
            sendingCompatState.set(Boolean.TRUE);
            try {
                pccSendCompatState.invoke(pcc);
            } finally {
                sendingCompatState.remove();
            }
        } catch (Throwable ignored) {
        }
    }

    private void syncImeProcessesForFoldState(Object s, boolean folded) {
        if (s == null || ccGetConfig == null || wpcDispatchConfig == null) return;
        boolean featureOn = coverImeEnabled();
        // Nothing to apply and nothing to undo: skip the full window walk (the default configuration).
        if (!featureOn && !imeCompatApplied) return;
        try {
            Context ctx = (Context) serviceContext.get(s);
            if (featureOn) refreshActiveThirdPartyImeUid(ctx);
            synchronized (serviceLock.get(s)) {
                Object root = serviceRoot.get(s);
                if (root == null) return;
                Object dc = rootDisplay.invoke(root, 0);
                if (dc == null) return;
                Configuration dcConfig = (Configuration) ccGetConfig.invoke(dc);
                int liveRot = refreshCoverDisplayRotationLocked(dc);
                int rot = liveRot >= 0 ? liveRot : getRotationFromConfig(dcConfig);
                boolean coverActive = folded && featureOn && !CoverImeGuard.isMirrorVirtualDisplayActive();
                if (coverActive) imeCompatApplied = true;

                Set<Object> imeProcs;
                synchronized (knownImeProcesses) {
                    imeProcs = new HashSet<>(knownImeProcesses);
                }
                ArrayList<Object> imeWindows = new ArrayList<>();

                forAllWindows.invoke(root, (Consumer<Object>) w -> {
                    try {
                        if (isThirdPartyImeWindow(w)) {
                            imeWindows.add(w);
                        }
                        if (winSessionField != null && sessionProcessField != null) {
                            Object session = winSessionField.get(w);
                            Object wpc = session != null ? sessionProcessField.get(session) : null;
                            if (wpc != null && (isThirdPartyImeProcess(wpc) || isThirdPartyImeWindow(w))) {
                                imeProcs.add(wpc);
                                knownImeProcesses.add(wpc);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }, true);

                for (Object wpc : imeProcs) {
                    if (wpc == null) continue;
                    try {
                        Configuration reqOverride = (Configuration) ccGetReqOverrideConfig.invoke(wpc);
                        Configuration resOverride = (Configuration) ccGetResolvedOverrideConfig.invoke(wpc);
                        if (coverActive) {
                            adaptCoverImeConfiguration(reqOverride, rot);
                            adaptCoverImeConfiguration(resOverride, rot);
                            setIsolatedWpcInitConfig(wpc, null, true, rot);
                        } else {
                            clearCoverImeOverride(reqOverride);
                            clearCoverImeOverride(resOverride);
                            setIsolatedWpcInitConfig(wpc, null, false, 0);
                        }
                        if (wpcLastReportedConfigField != null) {
                            Configuration lastCfg = (Configuration) wpcLastReportedConfigField.get(wpc);
                            if (lastCfg != null && !isSharedSystemConfiguration(lastCfg)) {
                                lastCfg.densityDpi = 1;
                            }
                        }
                        ccOnConfigChanged.invoke(wpc, dcConfig);
                        Configuration procCfg = (Configuration) ccGetConfig.invoke(wpc);
                        if (coverActive) {
                            adaptCoverImeConfiguration(procCfg, rot);
                            wpcDispatchConfig.invoke(wpc, procCfg);
                            if (hasNonNullAppBounds(procCfg)) {
                                syncProcessCompatForIme(wpc, true, rot);
                            }
                        } else {
                            if (wpcInitConfigField != null && procCfg != null) {
                                wpcInitConfigField.set(wpc, new Configuration(procCfg));
                            }
                            syncProcessCompatForIme(wpc, false, 0);
                            wpcDispatchConfig.invoke(wpc, procCfg);
                        }
                    } catch (Throwable t) {
                        error(t);
                    }
                }

                Set<Object> displaysToLayout = new HashSet<>();
                displaysToLayout.add(dc);
                for (Object w : imeWindows) {
                    try {
                        if (coverActive && isWindowOnDefaultDisplay(w)) {
                            ensureCoverImeWindowAttrs(w, rot);
                        } else {
                            restoreInnerImeWindowAttrs(w);
                        }
                        notifyInsets.invoke(w);
                        Object winDc = getDisplay != null ? getDisplay.invoke(w) : null;
                        if (winDc != null) displaysToLayout.add(winDc);
                    } catch (Throwable ignored) {
                    }
                }
                for (Object d : displaysToLayout) {
                    layoutNeeded.invoke(d);
                }
                traversal.invoke(s);
                if (!featureOn) imeCompatApplied = false;
            }
        } catch (Throwable t) {
            error(t);
        }
    }

    private static Method optionalMethod(Class<?> type, String name, Class<?> result, Class<?>... args) {
        try {
            return method(type, name, result, args);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Field optionalField(Class<?> type, String name, Class<?> expected) {
        try {
            return field(type, name, expected);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static String extractCompatTargetPackage(Iterable<?> args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof String s && !"miui.continuity.policy".equals(s) && !s.isEmpty()) {
                return s;
            } else if (arg instanceof ApplicationInfo ai && ai.packageName != null) {
                return ai.packageName;
            } else if (arg instanceof ActivityInfo acti && acti.packageName != null) {
                return acti.packageName;
            } else if (arg instanceof ComponentName cn) {
                return cn.getPackageName();
            }
        }
        return null;
    }

    private static boolean isSystemCompatTarget(Iterable<?> args) {
        if (args == null) return false;
        for (Object arg : args) {
            String pkg = null;
            if (arg instanceof String s && !"miui.continuity.policy".equals(s)) {
                pkg = s;
            } else if (arg instanceof ApplicationInfo ai) {
                pkg = ai.packageName;
            } else if (arg instanceof ActivityInfo acti) {
                pkg = acti.packageName;
            } else if (arg instanceof ComponentName cn) {
                pkg = cn.getPackageName();
            }
            if (pkg != null && (pkg.startsWith("com.miui.")
                    || pkg.startsWith("com.xiaomi.")
                    || pkg.startsWith("com.android.")
                    || "android".equals(pkg))) {
                return true;
            }
        }
        return false;
    }

    private void error(Throwable t) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastError > 10000) {
            lastError = now;
            Log.w(TAG, "native result retained after policy error", t);
        }
    }

    private static Method method(Class<?> type, String name, Class<?> result, Class<?>... args) throws ReflectiveOperationException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, args);
                if (m.getReturnType() != result) throw new NoSuchMethodException(name + " return type");
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private static Field field(Class<?> type, String name, Class<?> expected) throws ReflectiveOperationException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                if (f.getType() != expected) throw new NoSuchFieldException(name + " type");
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }
}
