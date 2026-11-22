package io.github.jabe1122.mixflip2cover;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.util.Log;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import io.github.libxposed.api.XposedModule;

/**
 * Unlocks the MIX Flip 2 cover launcher ({@code com.miui.fliphome}) so any installed user/system/dual-profile
 * app can be added to the cover screen desktop instead of being filtered by {@code app_intercept_allowlist}
 * or {@code mBlackPackageSet}.
 */
final class CoverLauncherApps {
    private static final String TAG = "MF2C.LauncherApps";
    static final String ACTION_LAUNCHER_APPS_SYNC = "io.github.jabe1122.mixflip2cover.LAUNCHER_APPS_SYNC";
    private static final String CONTACT_DETAIL_COMPONENT =
            "com.android.contacts/com.android.contacts.activities.ContactDetailActivity";

    private static volatile Field mAllInstallAppsField;
    private static volatile Field mAllAppsField;
    private static volatile Field mAllowPkgSetField;
    private static volatile WeakReference<Object> lastLauncherAppsRef;
    private static volatile Method flipAppGetInstanceMethod;
    private static volatile Method flipAppGetModelMethod;
    private static volatile Method modelReloadAppsMethod;
    private static volatile Method modelInitSettingsDataMethod;
    private static final Object APPS_MUTATION_LOCK = new Object();
    private static final Set<String> injectedAllowPkgs = Collections.synchronizedSet(new HashSet<>());
    private static volatile boolean receiverRegistered;
    private static volatile Boolean lastKnownUnlockState;

    private CoverLauncherApps() {}

    static boolean isEligibleOuterLauncherPackage(String pkg) {
        return pkg != null
                && !pkg.isEmpty()
                && !"com.miui.fliphome".equals(pkg)
                && !"com.miui.home".equals(pkg);
    }

    static void install(XposedModule module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> dlaClass = loader.loadClass("com.miui.fliphome.model.DefaultLauncherApps");
        mAllInstallAppsField = dlaClass.getDeclaredField("mAllInstallApps");
        mAllInstallAppsField.setAccessible(true);
        mAllAppsField = dlaClass.getDeclaredField("mAllApps");
        mAllAppsField.setAccessible(true);
        mAllowPkgSetField = dlaClass.getDeclaredField("mAllowPkgSet");
        mAllowPkgSetField.setAccessible(true);

        try {
            Class<?> flipAppClass = loader.loadClass("com.miui.fliphome.FlipApplication");
            flipAppGetInstanceMethod = flipAppClass.getDeclaredMethod("getInstance");
            flipAppGetInstanceMethod.setAccessible(true);
            flipAppGetModelMethod = flipAppClass.getDeclaredMethod("getModel");
            flipAppGetModelMethod.setAccessible(true);
            Class<?> modelClass = loader.loadClass("com.miui.fliphome.model.LauncherModel");
            modelReloadAppsMethod = modelClass.getDeclaredMethod("reloadApps");
            modelReloadAppsMethod.setAccessible(true);
            modelInitSettingsDataMethod = modelClass.getDeclaredMethod("initSettingsData", boolean.class);
            modelInitSettingsDataMethod.setAccessible(true);
        } catch (Throwable t) {
            Log.w(TAG, "LauncherModel reload handles partial", t);
        }

        Method isCanAdd = dlaClass.getDeclaredMethod("isPackageCanAddOuterScreen", String.class, UserHandle.class);
        isCanAdd.setAccessible(true);
        module.hook(isCanAdd).intercept(chain -> {
            try {
                if (ModuleConfig.unlockLauncherApps()
                        && chain.getArg(0) instanceof String pkg
                        && isEligibleOuterLauncherPackage(pkg)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
            return chain.proceed();
        });

        Method addValid = dlaClass.getDeclaredMethod("addValidAppsToAllAppList", List.class, UserHandle.class);
        addValid.setAccessible(true);
        module.hook(addValid).intercept(chain -> {
            Object res = chain.proceed();
            try {
                Object self = chain.getThisObject();
                if (self != null) lastLauncherAppsRef = new WeakReference<>(self);
                if (ModuleConfig.unlockLauncherApps()) {
                    populateAllInstalledApps(self);
                }
            } catch (Throwable t) {
                Log.w(TAG, "addValidAppsToAllAppList unlock skipped", t);
            }
            return res;
        });

        for (String name : new String[]{"loadAllInstallApps", "checkContinuityApps"}) {
            try {
                Method m = dlaClass.getDeclaredMethod(name);
                m.setAccessible(true);
                module.hook(m).intercept(chain -> {
                    Object res = chain.proceed();
                    try {
                        Object self = chain.getThisObject();
                        if (self != null) lastLauncherAppsRef = new WeakReference<>(self);
                        if (ModuleConfig.unlockLauncherApps()) {
                            populateAllInstalledApps(self);
                        }
                        ensureSyncReceiverRegistered();
                    } catch (Throwable t) {
                        Log.w(TAG, name + " unlock skipped", t);
                    }
                    return res;
                });
            } catch (NoSuchMethodException ignored) {
            }
        }

        lastKnownUnlockState = ModuleConfig.unlockLauncherApps();
        ModuleConfig.addListener(() -> {
            boolean cur = ModuleConfig.unlockLauncherApps();
            if (!Boolean.valueOf(cur).equals(lastKnownUnlockState)) {
                lastKnownUnlockState = cur;
                reloadLauncherModel(cur);
            }
        });
        ModLog.i(TAG, "cover launcher all-apps unlock hooks ready");
    }

    @SuppressWarnings("unchecked")
    private static void populateAllInstalledApps(Object launcherApps) {
        if (launcherApps == null || mAllInstallAppsField == null || mAllAppsField == null) return;
        try {
            Object installMapObj = mAllInstallAppsField.get(launcherApps);
            Object allAppsMapObj = mAllAppsField.get(launcherApps);
            Object allowSetObj = mAllowPkgSetField != null ? mAllowPkgSetField.get(launcherApps) : null;
            if (!(installMapObj instanceof Map<?, ?> installMap) || !(allAppsMapObj instanceof Map<?, ?> rawAllApps)) {
                return;
            }
            Map<Object, Object> allApps = (Map<Object, Object>) rawAllApps;
            Set<String> allowPkgSet = (allowSetObj instanceof Set<?> s) ? (Set<String>) s : null;
            int added = 0;
            synchronized (APPS_MUTATION_LOCK) {
                List<Map.Entry<?, ?>> entries = new ArrayList<>(installMap.entrySet());
                for (Map.Entry<?, ?> entry : entries) {
                    Object key = entry.getKey();
                    Object val = entry.getValue();
                    if (key == null || !(val instanceof LauncherActivityInfo info)) continue;
                    ComponentName cn = info.getComponentName();
                    if (cn == null) continue;
                    String pkg = cn.getPackageName();
                    if (!isEligibleOuterLauncherPackage(pkg)) continue;
                    if (CONTACT_DETAIL_COMPONENT.equals(cn.flattenToString())) continue;
                    if (allApps.putIfAbsent(key, info) == null) {
                        added++;
                    }
                    if (allowPkgSet != null && allowPkgSet.add(pkg)) {
                        injectedAllowPkgs.add(pkg);
                    }
                }
            }
            if (added > 0) {
                ModLog.i(TAG, "unlocked " + added + " additional app entries for cover launcher (total=" + allApps.size() + ")");
            }
        } catch (Throwable t) {
            Log.w(TAG, "populateAllInstalledApps failed", t);
        }
    }

    private static void ensureSyncReceiverRegistered() {
        if (receiverRegistered) return;
        Context appCtx = resolveFlipAppContext();
        if (appCtx == null) return;
        synchronized (CoverLauncherApps.class) {
            if (receiverRegistered) return;
            try {
                Handler main = new Handler(Looper.getMainLooper());
                appCtx.registerReceiver(new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent intent) {
                        if (intent == null || !ACTION_LAUNCHER_APPS_SYNC.equals(intent.getAction())) return;
                        try {
                            int sender = getSentFromUid();
                            int moduleUid = c.getPackageManager().getPackageUid(
                                    BuildConfig.APPLICATION_ID,
                                    PackageManager.PackageInfoFlags.of(0));
                            if (sender < 0 || sender % 100000 != moduleUid % 100000) return;
                            boolean unlock = intent.getBooleanExtra(ModuleConfig.KEY_UNLOCK_LAUNCHER_APPS, true);
                            ModuleConfig.overrideUnlockLauncherApps(unlock);
                            lastKnownUnlockState = unlock;
                            reloadLauncherModel(unlock);
                        } catch (Throwable ignored) {
                        }
                    }
                }, new IntentFilter(ACTION_LAUNCHER_APPS_SYNC), null, main, Context.RECEIVER_EXPORTED);
                receiverRegistered = true;
            } catch (Throwable ignored) {
            }
        }
    }

    private static Context resolveFlipAppContext() {
        try {
            if (flipAppGetInstanceMethod != null) {
                Object inst = flipAppGetInstanceMethod.invoke(null);
                if (inst instanceof Context c) {
                    return c.getApplicationContext() != null ? c.getApplicationContext() : c;
                }
            }
        } catch (Throwable ignored) {
        }
        return CoverGeometry.application();
    }

    @SuppressWarnings("unchecked")
    private static void reloadLauncherModel(boolean unlock) {
        try {
            Object dla = lastLauncherAppsRef != null ? lastLauncherAppsRef.get() : null;
            if (dla != null) {
                if (unlock) {
                    populateAllInstalledApps(dla);
                } else if (mAllowPkgSetField != null && !injectedAllowPkgs.isEmpty()) {
                    synchronized (APPS_MUTATION_LOCK) {
                        Object allowSetObj = mAllowPkgSetField.get(dla);
                        if (allowSetObj instanceof Set<?> s) {
                            ((Set<String>) s).removeAll(injectedAllowPkgs);
                        }
                        injectedAllowPkgs.clear();
                    }
                }
            }
            if (flipAppGetInstanceMethod != null && flipAppGetModelMethod != null) {
                Object flipApp = flipAppGetInstanceMethod.invoke(null);
                Object model = flipApp != null ? flipAppGetModelMethod.invoke(flipApp) : null;
                if (model != null) {
                    if (modelReloadAppsMethod != null) {
                        modelReloadAppsMethod.invoke(model);
                    }
                    if (modelInitSettingsDataMethod != null) {
                        modelInitSettingsDataMethod.invoke(model, true);
                    }
                    ModLog.i(TAG, "reloaded FlipHome LauncherModel apps (unlock=" + unlock + ")");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "reloadLauncherModel failed", t);
        }
    }
}
