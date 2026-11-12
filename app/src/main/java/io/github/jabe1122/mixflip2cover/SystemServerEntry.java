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
    private static final int MAX_TOP_BAR_SOURCE_HEIGHT = 220;
    private static final int INNER_DISPLAY_WIDTH = 1224;
    private static final int INNER_DISPLAY_HEIGHT = 2912;
    private static final int COVER_DISPLAY_WIDTH = 1208;
    private static final int COVER_DISPLAY_HEIGHT = 1392;
    private static final int COVER_SAFE_WIDTH = 810;
    private static final int FIXED_ASPECT_RATIO_STATE = 8;
    private static final int SNAPSHOT_REF_BROADCAST = 16;

    private final ArrayList<HookHandle> hooks = new ArrayList<>();
    private final Map<Object, String> published = Collections.synchronizedMap(new WeakHashMap<>());
    private final Set<Object> deliveryLogged = Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));
    private final Set<Object> knownImeProcesses = Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));
    private final Map<ComponentName, Boolean> nativeFlipActivityCache = new ConcurrentHashMap<>();
    private final Map<Integer, String> uidPackageCache = new ConcurrentHashMap<>();
    private final AtomicBoolean receiversRegistered = new AtomicBoolean(false);

    private Field attrs, record, rawTypes, infoUnique, infoWidth, infoHeight, displayFocus;
    private Field serviceHandler, serviceLock, serviceRoot, serviceContext, windowConfigField;
    private Field ownerUidField, arPkgField, arComponentField, arInfoField, arFinishingField;
    private Method arIsVisibleRequestedMethod, displayGetIdMethod;
    private final ThreadLocal<Boolean> sendingCompatState = new ThreadLocal<>();
    private Method getDisplay, getInfo, getMode, sourceSize, sourceAt, sourceType, getSourceFrame, addSource, sourceFrame, sourceVisibleFrame, sourceVisible;
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
    private final Object foldLock = new Object();
    private Boolean lastFoldDispatched;
    private volatile boolean imeCompatApplied;
    private volatile boolean installed;
    private volatile boolean moduleUninstalled;
    private volatile ClassLoader systemServerClassLoader;
    private final Object voiceAssistLetterboxGuard = new Object();
    private final Set<String> removedHideLetterboxEntries = new HashSet<>();
    private final Set<String> removedAspectBlacklistEntries = new HashSet<>();
    private volatile boolean lastCoverDisplayState;
    private volatile boolean lastCoverDisplayInitialized;
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
            arIsVisibleRequestedMethod = optionalMethod(arClass, "isVisibleRequested", boolean.class);
            displayGetIdMethod = method(display, "getDisplayId", int.class);
            Method tokenMode = method(token, "getWindowingMode", int.class);
            Field policyDisplay = field(policy, "mDisplayContent", display);

            verifySourceCopies(state, source);
            ModuleConfig.init(this);
            CoverResourceOverlay.installCoreResourceGuard(this);
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
                                    && !isNativeFlipFullscreenToken(target)) {
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
                        if (windowPolicyApplies(w) && !isNativeFlipFullscreenWindow(w)) {
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
                    if (enabled()) {
                        Object t = chain.getArg(0);
                        Object dc = t != null ? tokenDisplay.invoke(t) : policyDisplay.get(chain.getThisObject());
                        int type = t != null ? tokenType.getInt(t) : 1;
                        int mode = t != null ? (Integer) tokenMode.invoke(t) : 1;
                        if (tokenPolicyApplies(t, caller)
                                && displayEligible(dc, type, true, mode)
                                && !isNativeFlipFullscreenToken(t)) {
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
                                } catch (Throwable ignored) {
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
            Log.i(TAG, "cover Insets policy installed (" + BuildConfig.VERSION_NAME + ")");
        } catch (Throwable t) {
            installed = false;
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
                    CoverResourceOverlay.onShutdown();
                    CoverImeGuard.onShutdown(ctx);
                    syncVoiceAssistHideLetterbox(systemServerClassLoader, false);
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
                    CoverResourceOverlay.removeAll();
                    CoverImeGuard.restoreSystem(ctx);
                    syncVoiceAssistHideLetterbox(systemServerClassLoader, false);
                    syncImeProcessesForFoldState(service, isCoverFolded());
                    configurationChanged();
                }
            }, new android.content.IntentFilter(FrameworkRuntime.ACTION_RESTORE), null, h, Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            Log.w(TAG, "restore receiver unavailable", t);
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
                        CoverResourceOverlay.setUninstalled(true);
                        CoverImeGuard.setUninstalled(ctx, true);
                        syncVoiceAssistHideLetterbox(systemServerClassLoader, false);
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
        String pkg = resolveTokenPackage(token, callerUid);
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
        return windowPolicyApplies(window) && eligibleWindow(window) && !isNativeFlipFullscreenWindow(window);
    }

    private boolean isNativeFlipFullscreenWindow(Object window) {
        if (window == null || record == null) return false;
        try {
            return isNativeFlipFullscreenActivityRecord(record.get(window));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isNativeFlipFullscreenToken(Object token) {
        if (token == null || asActivityMethod == null) return false;
        try {
            return isNativeFlipFullscreenActivityRecord(asActivityMethod.invoke(token));
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
        if (ar == null) return false;
        try {
            ComponentName cn = arComponentField != null ? (ComponentName) arComponentField.get(ar) : null;
            return isNativeFlipFullscreenComponent(cn);
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean isNativeFlipFullscreenComponent(ComponentName cn) {
        if (cn == null) return false;
        // The cover launcher declares the flag too, but its status area is managed by this module itself.
        // Super XiaoAi declares supportFlipFullScreen=0 on TabHomeActivity, which we override to left-pane
        // compact mode (mode 2) when active and leave to stock HyperOS compat scaling when disabled.
        if ("com.miui.fliphome".equals(cn.getPackageName())
                || "com.miui.voiceassist".equals(cn.getPackageName())) {
            return false;
        }
        if ("com.netease.cloudmusic.activity.MiMiniPlayerActivity".equals(cn.getClassName())) return true;
        Boolean cached = nativeFlipActivityCache.get(cn);
        if (cached != null) return cached;
        boolean result = false;
        try {
            Bundle activityMeta = null;
            Bundle appMeta = null;
            Object s = service;
            Context ctx = s != null ? (Context) serviceContext.get(s) : null;
            if (ctx != null) {
                long token = Binder.clearCallingIdentity();
                try {
                    ActivityInfo full = ctx.getPackageManager().getActivityInfo(cn,
                            PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA
                                    | PackageManager.MATCH_DISABLED_COMPONENTS));
                    activityMeta = full.metaData;
                    if (full.applicationInfo != null && full.applicationInfo.metaData != null) {
                        appMeta = full.applicationInfo.metaData;
                    }
                    if (appMeta == null) {
                        appMeta = ctx.getPackageManager().getApplicationInfo(cn.getPackageName(),
                                PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA)).metaData;
                    }
                } catch (Throwable ignored) {
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
            if (activityMeta != null && activityMeta.containsKey("miui.supportFlipFullScreen")) {
                result = activityMeta.getInt("miui.supportFlipFullScreen", -1) == 0;
            } else if (appMeta != null && appMeta.containsKey("miui.supportFlipFullScreen")) {
                result = appMeta.getInt("miui.supportFlipFullScreen", -1) == 0;
            }
            nativeFlipActivityCache.put(cn, result);
            if (result) ModLog.i(TAG, "native cover layout declared by " + cn.flattenToShortString());
            return result;
        } catch (Throwable ignored) {
        }
        return false;
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

    /** True when the process of this ProcessCompatController shows a natively adapted Activity on the cover. */
    private boolean isNativeFlipTop(Object pcc) throws ReflectiveOperationException {
        if (pcc == null || !enabled() || !isCoverFolded()) return false;
        Object wpc = pccProcessField.get(pcc);
        if (wpc == null || wpcInfoField == null) return false;
        ApplicationInfo ai = (ApplicationInfo) wpcInfoField.get(wpc);
        if (ai == null || !appCompatEnabled(ai.packageName)) return false;
        return !isThirdPartyImeProcess(wpc) && hasNativeFlipFullscreenTopActivity(wpc);
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
        try {
            if (window != null) {
                WindowManager.LayoutParams p = (WindowManager.LayoutParams) attrs.get(window);
                if (p != null && "com.miui.fliphome".equals(p.packageName)) {
                    if (ownerUidField != null) {
                        flipHomeUid = ownerUidField.getInt(window) % 100000;
                    }
                    return 0;
                }
            }
        } catch (Throwable ignored) {
        }
        return CoverResourceOverlay.COVER_APP_STATUS_BAR_PX;
    }

    private int targetTopPxForToken(Object token, int callerUid) {
        try {
            int appId = callerUid % 100000;
            if (appId > 0 && appId == flipHomeUid) {
                return 0;
            }
            if (token != null && asActivityMethod != null && arPkgField != null) {
                Object ar = asActivityMethod.invoke(token);
                if (ar != null && "com.miui.fliphome".equals(arPkgField.get(ar))) {
                    return 0;
                }
            }
        } catch (Throwable ignored) {
        }
        return CoverResourceOverlay.COVER_APP_STATUS_BAR_PX;
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
                sourceVisibleFrame.invoke(copy, new Rect(left, 0, right, topPx));
                sourceVisible.invoke(copy, true);
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
                        if (!appCompatEnabled("com.miui.voiceassist")) {
                            syncVoiceAssistHideLetterbox(systemServerClassLoader, false);
                        }
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
            } else {
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

    private volatile boolean voiceAssistLetterboxCleared;

    @SuppressWarnings("unchecked")
    private void syncVoiceAssistHideLetterbox(ClassLoader loader, boolean strip) {
        if (strip == voiceAssistLetterboxCleared) return;
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
            synchronized (voiceAssistLetterboxGuard) {
                if (strip == voiceAssistLetterboxCleared) return;
                try {
                    Class<?> arImplClass = Class.forName("com.android.server.wm.ActivityRecordImpl", false, cl);
                    Field hideLbField = arImplClass.getDeclaredField("FLIP_SCF_HIDE_LETTERBOX_LIST");
                    hideLbField.setAccessible(true);
                    Object hideLbSet = hideLbField.get(null);

                    Class<?> atmsImplClass = Class.forName("com.android.server.wm.ActivityTaskManagerServiceImpl", false, cl);
                    Field blField = atmsImplClass.getDeclaredField("BLACK_LIST_SHOULD_NOT_APPLY_ASPECT_RATIO");
                    blField.setAccessible(true);
                    Object blObj = blField.get(null);

                    if (strip) {
                        if (hideLbSet instanceof java.util.Collection<?> col) {
                            if (col.remove("com.miui.voiceassist")) {
                                removedHideLetterboxEntries.add("com.miui.voiceassist");
                            }
                        }
                        if (blObj instanceof java.util.Collection<?> col) {
                            java.util.Iterator<?> it = col.iterator();
                            while (it.hasNext()) {
                                Object item = it.next();
                                if (item instanceof String str
                                        && ("com.miui.voiceassist".equals(str) || str.startsWith("com.miui.voiceassist/"))) {
                                    removedAspectBlacklistEntries.add(str);
                                    it.remove();
                                }
                            }
                        }
                        voiceAssistLetterboxCleared = true;
                    } else {
                        if (hideLbSet instanceof java.util.Collection<?> col && !removedHideLetterboxEntries.isEmpty()) {
                            ((java.util.Collection<String>) col).addAll(removedHideLetterboxEntries);
                            removedHideLetterboxEntries.clear();
                        }
                        if (blObj instanceof java.util.Collection<?> col && !removedAspectBlacklistEntries.isEmpty()) {
                            ((java.util.Collection<String>) col).addAll(removedAspectBlacklistEntries);
                            removedAspectBlacklistEntries.clear();
                        }
                        voiceAssistLetterboxCleared = false;
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
                            if (ar != null && arPkgField != null && "com.miui.voiceassist".equals(arPkgField.get(ar))) {
                                if (appCompatEnabled("com.miui.voiceassist")) {
                                    syncVoiceAssistHideLetterbox(loader, true);
                                    return 2;
                                }
                                syncVoiceAssistHideLetterbox(loader, false);
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
                            if ("com.miui.voiceassist".equals(chain.getArg(1))) {
                                if (appCompatEnabled("com.miui.voiceassist")) {
                                    return 0;
                                }
                                syncVoiceAssistHideLetterbox(loader, false);
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
                            if (chain.getArg(0) instanceof PackageItemInfo info
                                    && "com.miui.voiceassist".equals(info.packageName)
                                    && appCompatEnabled("com.miui.voiceassist")) {
                                return 2;
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
                            if (ar != null && arPkgField != null && "com.miui.voiceassist".equals(arPkgField.get(ar))) {
                                if (appCompatEnabled("com.miui.voiceassist")) {
                                    syncVoiceAssistHideLetterbox(loader, true);
                                    return false;
                                }
                                syncVoiceAssistHideLetterbox(loader, false);
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
            for (Method m : iacClass.getDeclaredMethods()) {
                if ("isFlipContinuityEnabledFromSetting".equals(m.getName())
                        && m.getReturnType() == boolean.class) {
                    m.setAccessible(true);
                    hooks.add(hook(m).intercept(chain -> {
                        try {
                            if (enabled() && ModuleConfig.universalCompatEnabled()) {
                                return true;
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
                            if (isCoverFolded() && coverImeEnabled()) {
                                int rot = getRotationFromConfig(cfg);
                                adaptCoverImeConfiguration(cfg, rot);
                                syncProcessCompatForIme(wpc, true, rot);
                            } else {
                                syncProcessCompatForIme(wpc, false, 0);
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
                            boolean coverActive = isCoverFolded() && coverImeEnabled();
                            Configuration procCfg = (Configuration) ccGetConfig.invoke(wpc);
                            int rot = getRotationFromConfig(procCfg);
                            Configuration reqOverride = (Configuration) ccGetReqOverrideConfig.invoke(wpc);
                            Configuration resOverride = (Configuration) ccGetResolvedOverrideConfig.invoke(wpc);
                            if (coverActive) {
                                adaptCoverImeConfiguration(reqOverride, rot);
                                adaptCoverImeConfiguration(resOverride, rot);
                                adaptCoverImeConfiguration(procCfg, rot);
                                if (wpcInitConfigField != null) {
                                    Configuration initCfg = (Configuration) wpcInitConfigField.get(wpc);
                                    adaptCoverImeConfiguration(initCfg, rot);
                                }
                            } else {
                                clearCoverImeOverride(reqOverride);
                                clearCoverImeOverride(resOverride);
                            }
                            if (wpcLastReportedConfigField != null) {
                                Configuration lastCfg = (Configuration) wpcLastReportedConfigField.get(wpc);
                                if (lastCfg != null) lastCfg.densityDpi = 1;
                            }
                            wpcDispatchConfig.invoke(wpc, procCfg);
                            syncProcessCompatForIme(wpc, coverActive, rot);
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
                        if (isCoverFolded() && coverImeEnabled()) {
                            Configuration procCfg = (Configuration) ccGetConfig.invoke(wpc);
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
                        Configuration config = (Configuration) chain.getArg(0);
                        if (isCoverFolded() && coverImeEnabled()) {
                            Configuration copy = new Configuration(config);
                            int rot = getRotationFromConfig(config);
                            adaptCoverImeConfiguration(copy, rot);
                            syncProcessCompatForIme(wpc, true, rot);
                            return copy;
                        } else {
                            syncProcessCompatForIme(wpc, false, 0);
                            return config;
                        }
                    } else if (isNativeFlipTop(pcc)) {
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
                        return chain.getArg(0);
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
                        if (isCoverFolded() && coverImeEnabled()) {
                            int rot = getRotationFromConfig(parentCfg);
                            adaptCoverImeConfiguration(overrideCfg, rot);
                            syncProcessCompatForIme(wpc, true, rot);
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
                        if (isCoverFolded() && coverImeEnabled()) {
                            int rot = getRotationFromConfig(cfg);
                            adaptCoverImeConfiguration(cfg, rot);
                            syncProcessCompatForIme(wpc, true, rot);
                        } else {
                            scrubInnerImeProcessConfig(wpc, cfg);
                            syncProcessCompatForIme(wpc, false, 0);
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
                        if (isCoverFolded() && coverImeEnabled()) {
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
                        if (isCoverFolded() && coverImeEnabled()) {
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
                        if (isCoverFolded() && coverImeEnabled()) {
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
                        if (isCoverFolded() && coverImeEnabled()) {
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
                try {
                    Object wpc = chain.getThisObject();
                    if (wpc != null && imeHooksActive() && isThirdPartyImeProcess(wpc)) {
                        Configuration cfg = (Configuration) chain.getArg(0);
                        if (isCoverFolded() && coverImeEnabled()) {
                            int rot = getRotationFromConfig(cfg);
                            adaptCoverImeConfiguration(cfg, rot);
                            Configuration full = (Configuration) ccGetConfig.invoke(wpc);
                            adaptCoverImeConfiguration(full, rot);
                            syncProcessCompatForIme(wpc, true, rot);
                        } else {
                            scrubInnerImeProcessConfig(wpc, cfg);
                            syncProcessCompatForIme(wpc, false, 0);
                        }
                    }
                } catch (Throwable t) {
                    error(t);
                }
                return chain.proceed();
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
                    if (isCoverFolded() && coverImeEnabled() && isThirdPartyImeWindow(win)) {
                        int rot = getWindowRotation(win);
                        ensureCoverImeWindowAttrs(win, rot);
                        clampClientFramesHorizontally(chain.getArg(0), rot);
                    } else if (imeHooksActive() && isThirdPartyImeWindow(win)) {
                        restoreInnerImeWindowAttrs(win);
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
                    if (isCoverFolded() && coverImeEnabled() && isThirdPartyImeWindow(win)) {
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
                            && coverImeEnabled()
                            && isThirdPartyImeUid(Binder.getCallingUid())
                            && Binder.getCallingPid() != android.os.Process.myPid()
                            && isCoverFolded()) {
                        Object copy = infoCopy.newInstance(infoObj);
                        int rot = getCurrentCoverDisplayRotation();
                        if (rot < 0) rot = infoRotation != null ? infoRotation.getInt(copy) : 0;
                        if (infoRotation != null) infoRotation.setInt(copy, rot);
                        int imeW = CoverDisplay.coverImeWidth(rot);
                        int imeH = CoverDisplay.coverImeDisplayHeight(rot);
                        int imeDpi = CoverDisplay.coverImeDensityDpi(rot);
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

            Log.i(TAG, "cover third-party IME bounds & density adaptation hooks installed");
        } catch (Throwable t) {
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

    private int getCurrentCoverDisplayRotation() {
        try {
            Object s = service;
            if (s != null && serviceRoot != null && rootDisplay != null && getInfo != null && infoRotation != null) {
                Object root = serviceRoot.get(s);
                if (root != null) {
                    Object dc = rootDisplay.invoke(root, 0);
                    if (dc != null) {
                        Object di = getInfo.invoke(dc);
                        if (di != null) {
                            int r = infoRotation.getInt(di);
                            if (r >= 0) return r;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private int getRotationFromConfig(Configuration cfg) {
        if (isCoverFolded()) {
            int live = getCurrentCoverDisplayRotation();
            if (live >= 0) return live;
        }
        if (cfg != null && windowConfigField != null) {
            try {
                Object wc = windowConfigField.get(cfg);
                if (wc != null) {
                    if (wcGetDisplayRotation != null) {
                        int dr = (Integer) wcGetDisplayRotation.invoke(wc);
                        if (dr >= 0) return dr;
                    }
                    if (wcGetRotation != null) {
                        int r = (Integer) wcGetRotation.invoke(wc);
                        if (r >= 0) return r;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        int fallback = getCurrentCoverDisplayRotation();
        return fallback >= 0 ? fallback : 0;
    }

    private int getWindowRotation(Object win) {
        try {
            if (win != null && getDisplay != null && getInfo != null && infoRotation != null) {
                Object dc = getDisplay.invoke(win);
                if (dc != null) {
                    Object di = getInfo.invoke(dc);
                    if (di != null) {
                        int r = infoRotation.getInt(di);
                        if (r >= 0) return r;
                    }
                }
            }
            if (win != null && ccGetConfig != null) {
                Configuration cfg = (Configuration) ccGetConfig.invoke(win);
                return getRotationFromConfig(cfg);
            }
        } catch (Throwable ignored) {
        }
        int fallback = getCurrentCoverDisplayRotation();
        return fallback >= 0 ? fallback : 0;
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

    private void adaptCoverImeConfiguration(Configuration cfg, int rotation) throws ReflectiveOperationException {
        if (cfg == null) return;
        Rect safe = coverImeSafeBounds(rotation);
        int dpi = CoverDisplay.coverImeDensityDpi(rotation);
        int wDp = CoverDisplay.coverImeScreenWidthDp(rotation);
        int hDp = CoverDisplay.coverImeScreenHeightDp(rotation);
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
                || (b != null && (CoverDisplay.isOuterDimensions(b.right, b.bottom)
                || (b.width() == COVER_SAFE_WIDTH && b.height() == COVER_DISPLAY_HEIGHT)
                || (b.width() == CoverDisplay.CAMERA_TOP && b.height() == CoverDisplay.WIDTH)));
    }

    private void scrubInnerImeProcessConfig(Object wpc, Configuration cfg) throws ReflectiveOperationException {
        clearCoverImeOverride((Configuration) ccGetReqOverrideConfig.invoke(wpc));
        clearCoverImeOverride((Configuration) ccGetResolvedOverrideConfig.invoke(wpc));
        if (wpcInitConfigField != null) {
            clearCoverImeOverride((Configuration) wpcInitConfigField.get(wpc));
        }
        if (!isCoverFolded() && hasCoverImeOverride(cfg)) {
            try {
                Object s = service;
                Object root = s != null ? serviceRoot.get(s) : null;
                Object dc = root != null ? rootDisplay.invoke(root, 0) : null;
                Configuration dcCfg = dc != null ? (Configuration) ccGetConfig.invoke(dc) : null;
                if (dcCfg != null) {
                    Configuration full = (Configuration) ccGetConfig.invoke(wpc);
                    if (full != null) full.setTo(dcCfg);
                    cfg.setTo(dcCfg);
                    if (wpcInitConfigField != null) {
                        Configuration initCfg = (Configuration) wpcInitConfigField.get(wpc);
                        if (initCfg != null) initCfg.setTo(dcCfg);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void clearCoverImeOverride(Configuration reqOverride) throws ReflectiveOperationException {
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
                int rot = getRotationFromConfig(dcConfig);
                boolean coverActive = folded && featureOn;
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
                            if (wpcInitConfigField != null) {
                                Configuration initCfg = (Configuration) wpcInitConfigField.get(wpc);
                                adaptCoverImeConfiguration(initCfg, rot);
                            }
                        } else {
                            clearCoverImeOverride(reqOverride);
                            clearCoverImeOverride(resOverride);
                            if (wpcInitConfigField != null) {
                                Configuration initCfg = (Configuration) wpcInitConfigField.get(wpc);
                                clearCoverImeOverride(initCfg);
                            }
                        }
                        if (wpcLastReportedConfigField != null) {
                            Configuration lastCfg = (Configuration) wpcLastReportedConfigField.get(wpc);
                            if (lastCfg != null) {
                                lastCfg.densityDpi = 1;
                            }
                        }
                        ccOnConfigChanged.invoke(wpc, dcConfig);
                        Configuration procCfg = (Configuration) ccGetConfig.invoke(wpc);
                        if (coverActive) {
                            adaptCoverImeConfiguration(procCfg, rot);
                        } else if (wpcInitConfigField != null && procCfg != null) {
                            Configuration initCfg = (Configuration) wpcInitConfigField.get(wpc);
                            if (initCfg != null) initCfg.setTo(procCfg);
                        }
                        wpcDispatchConfig.invoke(wpc, procCfg);
                        syncProcessCompatForIme(wpc, coverActive, rot);
                    } catch (Throwable t) {
                        error(t);
                    }
                }

                for (Object w : imeWindows) {
                    try {
                        if (coverActive) {
                            ensureCoverImeWindowAttrs(w, rot);
                        } else {
                            restoreInnerImeWindowAttrs(w);
                        }
                        notifyInsets.invoke(w);
                    } catch (Throwable ignored) {
                    }
                }
                layoutNeeded.invoke(dc);
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
