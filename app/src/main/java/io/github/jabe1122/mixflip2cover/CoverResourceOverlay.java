package io.github.jabe1122.mixflip2cover;

import android.content.Context;
import android.content.res.Resources;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.DisplayCutout;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import io.github.libxposed.api.XposedModule;

/**
 * Fold-state-aware FabricatedOverlay for @android:dimen/status_bar_height* on the MIX Flip 2 cover screen.
 *
 * Why a small (30 px) value plus dynamic fold-state toggling:
 * 1. HyperOS ProcessCompat sends an inner-display-like configuration (sw377dp/w377dp/h896dp) to cover-screen
 *    BoundsCompat apps, so no static resource qualifier can tell "cover" from "inner". The overlay is therefore
 *    enabled strictly while display 0 is the cover panel (1208x1392) and disabled on the inner screen, which keeps
 *    the native 146 px inner status bar untouched.
 * 2. A positive value (instead of 0) prevents third-party ImmersionBar / StatusBarUtil / QMUI style SDKs from
 *    falling back to `dp2px(25)` when they read a status-bar height <= 0.
 * 3. Core system processes (system_server, SystemUI, the cover launcher) are guarded in memory by
 *    {@link #installCoreResourceGuard} so they keep the native 114 px cover status-bar geometry.
 *
 * Persistence safety: the overlay is owned by "android" and would otherwise survive removal of this module. It is
 * therefore unregistered on every shutdown/reboot ({@link #removeAll}) and on the explicit "restore system"
 * request, and re-registered after boot only while the module is active.
 */
final class CoverResourceOverlay {
    private static final String TAG = "MF2C.Overlay";
    private static final String OWNING_PACKAGE = "android";
    private static final String TARGET_PACKAGE = "android";
    static final String OVERLAY_NAME = "mixflip2cover_statusbar";
    /** Overlay names/owners used by pre-release builds; removed on first sync. */
    private static final String[][] LEGACY_OVERLAYS = {
            {"flipduo_cover_statusbar_v1", "android"},
            {"flipduo_cover_statusbar_v1", "com.android.systemui"},
    };

    static final int NATIVE_COVER_STATUS_BAR_PX = 114;
    static final int NATIVE_INNER_STATUS_BAR_PX = 146;
    static final int COVER_APP_STATUS_BAR_PX = 30;
    /** Max patched px returned by the 30 px FabricatedOverlay (including BoundsCompat scaling). */
    static final int CORE_GUARD_MAX_PATCHED_PX = 48;
    /** Any display whose longer edge reaches this size is the inner panel (2912 px); the cover panel is 1392 px. */
    private static final int INNER_DISPLAY_MIN_LONG_EDGE = 2000;

    /** Small LRU of resource-id -> "is a status bar height dimen" decisions. */
    private static final Map<Long, Boolean> statusHeightIdCache =
            Collections.synchronizedMap(new LinkedHashMap<Long, Boolean>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
                    return size() > 2048;
                }
            });
    private static boolean observing;
    private static boolean valueRegisteredThisBoot;
    private static boolean legacyCleaned;
    private static boolean syncedOnce;
    private static volatile boolean lastFoldedState;
    private static volatile boolean shuttingDown;
    private static volatile boolean uninstalled;

    private CoverResourceOverlay() {}

    static void installCoreResourceGuard(XposedModule module) {
        try {
            for (String name : new String[]{"getDimensionPixelSize", "getDimensionPixelOffset"}) {
                module.hook(Resources.class.getDeclaredMethod(name, int.class)).intercept(chain -> {
                    Object res = chain.proceed();
                    try {
                        if (res instanceof Integer val && val <= CORE_GUARD_MAX_PATCHED_PX) {
                            Resources r = (Resources) chain.getThisObject();
                            int id = (Integer) chain.getArg(0);
                            if (isStatusHeightResource(r, id)) {
                                return Integer.valueOf(nativeStatusBarPx(r));
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    return res;
                });
            }

            module.hook(Resources.class.getDeclaredMethod("getDimension", int.class)).intercept(chain -> {
                Object res = chain.proceed();
                try {
                    if (res instanceof Float val && val <= CORE_GUARD_MAX_PATCHED_PX + 0.5f) {
                        Resources r = (Resources) chain.getThisObject();
                        int id = (Integer) chain.getArg(0);
                        if (isStatusHeightResource(r, id)) {
                            return Float.valueOf((float) nativeStatusBarPx(r));
                        }
                    }
                } catch (Throwable ignored) {
                }
                return res;
            });

            module.hook(Resources.class.getDeclaredMethod("getValue", int.class, TypedValue.class, boolean.class)).intercept(chain -> {
                Object res = chain.proceed();
                try {
                    TypedValue out = (TypedValue) chain.getArg(1);
                    if (out != null && out.type == TypedValue.TYPE_DIMENSION
                            && (out.data == 0 || TypedValue.complexToFloat(out.data) <= CORE_GUARD_MAX_PATCHED_PX + 0.5f)) {
                        Resources r = (Resources) chain.getThisObject();
                        int id = (Integer) chain.getArg(0);
                        if (isStatusHeightResource(r, id)) {
                            int px = nativeStatusBarPx(r);
                            out.data = (px << TypedValue.COMPLEX_MANTISSA_SHIFT) | TypedValue.COMPLEX_UNIT_PX;
                        }
                    }
                } catch (Throwable ignored) {
                }
                return res;
            });
        } catch (Throwable t) {
            Log.w(TAG, "core resource guard unavailable", t);
        }

        try {
            Class<?> sbu = Class.forName("com.android.internal.policy.SystemBarUtils");
            try {
                Method m1 = sbu.getDeclaredMethod("getStatusBarHeight", Resources.class, DisplayCutout.class);
                module.hook(m1).intercept(chain -> {
                    Object res = chain.proceed();
                    try {
                        if (res instanceof Integer val && val <= CORE_GUARD_MAX_PATCHED_PX) {
                            return Integer.valueOf(nativeStatusBarPx((Resources) chain.getArg(0)));
                        }
                    } catch (Throwable ignored) {
                    }
                    return res;
                });
            } catch (NoSuchMethodException ignored) {
            }
            try {
                Method m2 = sbu.getDeclaredMethod("getStatusBarHeightForRotation", Context.class, int.class);
                module.hook(m2).intercept(chain -> {
                    Object res = chain.proceed();
                    try {
                        if (res instanceof Integer val && val <= CORE_GUARD_MAX_PATCHED_PX) {
                            Context c = (Context) chain.getArg(0);
                            return Integer.valueOf(nativeStatusBarPx(c == null ? null : c.getResources()));
                        }
                    } catch (Throwable ignored) {
                    }
                    return res;
                });
            } catch (NoSuchMethodException ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    private static int nativeStatusBarPx(Resources r) {
        try {
            if (r != null) {
                DisplayMetrics dm = r.getDisplayMetrics();
                if (dm != null && Math.max(dm.widthPixels, dm.heightPixels) >= INNER_DISPLAY_MIN_LONG_EDGE) {
                    return NATIVE_INNER_STATUS_BAR_PX;
                }
            }
            Display d = resolveDefaultDisplay(null);
            if (d != null && !CoverDisplay.isOuterDisplay(d)) {
                return NATIVE_INNER_STATUS_BAR_PX;
            }
        } catch (Throwable ignored) {
        }
        return NATIVE_COVER_STATUS_BAR_PX;
    }

    /** system_server only: follows display 0 between the cover and inner panel. */
    static synchronized void observe(Context context) {
        if (android.os.Process.myUid() != 1000) return;
        if (observing) return;
        observing = true;
        try {
            if (context != null) {
                DisplayManager dm = context.getSystemService(DisplayManager.class);
                if (dm != null) {
                    dm.registerDisplayListener(new DisplayManager.DisplayListener() {
                        @Override
                        public void onDisplayAdded(int displayId) {
                            if (displayId == Display.DEFAULT_DISPLAY) sync(context);
                        }

                        @Override
                        public void onDisplayChanged(int displayId) {
                            if (displayId == Display.DEFAULT_DISPLAY) sync(context);
                        }

                        @Override
                        public void onDisplayRemoved(int displayId) {
                        }
                    }, new Handler(Looper.getMainLooper()));
                }
            }
        } catch (Throwable ignored) {
        }
        lastFoldedState = isDefaultDisplayOuter(context);
        ModuleConfig.addListener(() -> syncForFoldState(lastFoldedState));
        syncForFoldState(lastFoldedState);
    }

    static synchronized void sync(Context context) {
        if (shuttingDown || uninstalled || android.os.Process.myUid() != 1000) return;
        boolean folded = isDefaultDisplayOuter(context);
        // onDisplayChanged also fires for brightness/refresh-rate changes; only act on real fold transitions.
        if (folded == lastFoldedState && syncedOnce) return;
        syncForFoldState(folded);
    }

    static boolean isDefaultDisplayOuter(Context context) {
        boolean imeClamped = false;
        try {
            Object dmg = Class.forName("android.hardware.display.DisplayManagerGlobal")
                    .getDeclaredMethod("getInstance").invoke(null);
            if (dmg != null) {
                Object info = dmg.getClass()
                        .getDeclaredMethod("getDisplayInfo", int.class)
                        .invoke(dmg, Display.DEFAULT_DISPLAY);
                if (info != null) {
                    int w = info.getClass().getDeclaredField("logicalWidth").getInt(info);
                    int h = info.getClass().getDeclaredField("logicalHeight").getInt(info);
                    if (CoverDisplay.isOuterDimensions(w, h)) return true;
                    imeClamped = (w == CoverDisplay.CAMERA_LEFT && h == CoverDisplay.HEIGHT)
                            || (w == CoverDisplay.HEIGHT && h == CoverDisplay.CAMERA_LEFT);
                    if (w > 0 && h > 0 && !imeClamped) return false;
                }
            }
        } catch (Throwable ignored) {
        }
        Display d = resolveDefaultDisplay(context);
        return d != null ? CoverDisplay.isOuterDisplay(d) : imeClamped;
    }

    /** Overlay manager handles resolved once per process. */
    private static final class Om {
        Object service;
        Class<?> fabClass, idClass, txBuilderClass, txClass;
        Constructor<?> fabCtor;
        Method setOwningPkg, setResDimen, getId, getOverlayInfo, registerFab, unregisterFab, setEnabled, buildTx, commit;

        static Om get() throws ReflectiveOperationException {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            IBinder binder = (IBinder) smClass.getDeclaredMethod("getService", String.class).invoke(null, "overlay");
            if (binder == null) return null;
            Om o = new Om();
            o.service = Class.forName("android.content.om.IOverlayManager$Stub")
                    .getDeclaredMethod("asInterface", IBinder.class).invoke(null, binder);
            if (o.service == null) return null;
            o.fabClass = Class.forName("android.content.om.FabricatedOverlay");
            o.idClass = Class.forName("android.content.om.OverlayIdentifier");
            o.txBuilderClass = Class.forName("android.content.om.OverlayManagerTransaction$Builder");
            o.txClass = Class.forName("android.content.om.OverlayManagerTransaction");
            o.fabCtor = o.fabClass.getDeclaredConstructor(String.class, String.class);
            o.setOwningPkg = o.fabClass.getDeclaredMethod("setOwningPackage", String.class);
            o.setResDimen = o.fabClass.getDeclaredMethod("setResourceValue", String.class, float.class, int.class, String.class);
            o.getId = o.fabClass.getDeclaredMethod("getIdentifier");
            o.getOverlayInfo = o.service.getClass().getMethod("getOverlayInfoByIdentifier", o.idClass, int.class);
            o.registerFab = o.txBuilderClass.getDeclaredMethod("registerFabricatedOverlay", o.fabClass);
            o.unregisterFab = o.txBuilderClass.getDeclaredMethod("unregisterFabricatedOverlay", o.idClass);
            o.setEnabled = o.txBuilderClass.getDeclaredMethod("setEnabled", o.idClass, boolean.class, int.class);
            o.buildTx = o.txBuilderClass.getDeclaredMethod("build");
            o.commit = o.service.getClass().getMethod("commit", o.txClass);
            return o;
        }

        Object identifier(String name, String owner) throws ReflectiveOperationException {
            Object fab = fabCtor.newInstance(name, TARGET_PACKAGE);
            setOwningPkg.invoke(fab, owner);
            return getId.invoke(fab);
        }

        Object info(Object id, int user) throws ReflectiveOperationException {
            return getOverlayInfo.invoke(service, id, user);
        }
    }

    /** Users whose overlay state is managed: the system user and the current foreground user. */
    private static int[] managedUsers() {
        int current = 0;
        try {
            current = (Integer) android.app.ActivityManager.class.getMethod("getCurrentUser").invoke(null);
        } catch (Throwable ignored) {
        }
        return current == 0 ? new int[]{0} : new int[]{0, current};
    }

    static synchronized void syncForFoldState(boolean coverFolded) {
        if (shuttingDown || uninstalled || android.os.Process.myUid() != 1000) return;
        lastFoldedState = coverFolded;
        syncedOnce = true;
        boolean shouldEnable = coverFolded
                && FrameworkRuntime.active()
                && ModuleConfig.immersiveEnabled()
                && ModuleConfig.universalCompatEnabled();
        try {
            Om om = Om.get();
            if (om == null) return;
            Object identifier = om.identifier(OVERLAY_NAME, OWNING_PACKAGE);
            int[] users = managedUsers();

            boolean registered = false;
            boolean allMatch = true;
            for (int user : users) {
                Object info = om.info(identifier, user);
                registered |= info != null;
                boolean enabled = info != null && Boolean.TRUE.equals(info.getClass().getMethod("isEnabled").invoke(info));
                if (enabled != shouldEnable) allMatch = false;
            }
            if (valueRegisteredThisBoot && legacyCleaned && allMatch) return;
            if (!shouldEnable && !registered && legacyCleaned) return;

            Object builder = om.txBuilderClass.getDeclaredConstructor().newInstance();
            if (!legacyCleaned) {
                for (String[] legacy : LEGACY_OVERLAYS) {
                    try {
                        Object legacyId = om.identifier(legacy[0], legacy[1]);
                        if (om.info(legacyId, 0) != null) om.unregisterFab.invoke(builder, legacyId);
                    } catch (Throwable ignored) {
                    }
                }
                legacyCleaned = true;
            }
            if (shouldEnable && !valueRegisteredThisBoot) {
                Object fab = om.fabCtor.newInstance(OVERLAY_NAME, TARGET_PACKAGE);
                om.setOwningPkg.invoke(fab, OWNING_PACKAGE);
                for (String resName : new String[]{
                        "android:dimen/status_bar_height",
                        "android:dimen/status_bar_height_default",
                        "android:dimen/status_bar_height_portrait",
                        "android:dimen/status_bar_height_landscape"
                }) {
                    om.setResDimen.invoke(fab, resName, (float) COVER_APP_STATUS_BAR_PX, TypedValue.COMPLEX_UNIT_PX, null);
                }
                om.registerFab.invoke(builder, fab);
                valueRegisteredThisBoot = true;
                registered = true;
            }
            if (registered) {
                for (int user : users) om.setEnabled.invoke(builder, identifier, shouldEnable, user);
            }
            om.commit.invoke(om.service, om.buildTx.invoke(builder));
            ModLog.i(TAG, "status bar overlay synced folded=" + coverFolded + " enabled=" + shouldEnable);
        } catch (Throwable t) {
            Log.w(TAG, "status bar overlay sync skipped", t);
        }
    }

    static synchronized void onShutdown() {
        shuttingDown = true;
        removeAll();
    }

    static synchronized void setUninstalled(boolean removed) {
        uninstalled = removed;
        if (removed) {
            removeAll();
        }
    }

    /**
     * Unregisters this module's overlay (and pre-release leftovers). Called on shutdown/reboot and when the user
     * requests "restore system defaults", so nothing persists once the module is disabled or uninstalled.
     */
    static synchronized void removeAll() {
        if (android.os.Process.myUid() != 1000) return;
        try {
            Om om = Om.get();
            if (om == null) return;
            Object builder = om.txBuilderClass.getDeclaredConstructor().newInstance();
            boolean any = false;
            Object id = om.identifier(OVERLAY_NAME, OWNING_PACKAGE);
            if (om.info(id, 0) != null) {
                om.unregisterFab.invoke(builder, id);
                any = true;
            }
            for (String[] legacy : LEGACY_OVERLAYS) {
                Object legacyId = om.identifier(legacy[0], legacy[1]);
                if (om.info(legacyId, 0) != null) {
                    om.unregisterFab.invoke(builder, legacyId);
                    any = true;
                }
            }
            if (any) om.commit.invoke(om.service, om.buildTx.invoke(builder));
            valueRegisteredThisBoot = false;
            Log.i(TAG, "status bar overlay removed");
        } catch (Throwable t) {
            Log.w(TAG, "status bar overlay removal failed", t);
        }
    }

    private static Display resolveDefaultDisplay(Context context) {
        try {
            if (context != null) {
                DisplayManager dm = context.getSystemService(DisplayManager.class);
                if (dm != null) {
                    Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
                    if (d != null) return d;
                }
            }
            Object dmg = Class.forName("android.hardware.display.DisplayManagerGlobal")
                    .getDeclaredMethod("getInstance").invoke(null);
            if (dmg != null) {
                return (Display) dmg.getClass()
                        .getDeclaredMethod("getRealDisplay", int.class)
                        .invoke(dmg, Display.DEFAULT_DISPLAY);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    static boolean isStatusHeightResource(Resources r, int id) {
        if (id == 0 || r == null) return false;
        long cacheKey = ((id >>> 24) == 0x01)
                ? (id & 0xffffffffL)
                : ((((long) System.identityHashCode(r)) << 32) | (id & 0xffffffffL));
        synchronized (statusHeightIdCache) {
            Boolean cached = statusHeightIdCache.get(cacheKey);
            if (cached != null) return cached;
        }
        try {
            String entry = r.getResourceEntryName(id);
            boolean match = entry != null && (entry.equals("status_bar_height")
                    || entry.startsWith("status_bar_height_")
                    || entry.equals("all_in_one_status_bar_height")
                    || entry.equals("miui_status_bar_height"));
            synchronized (statusHeightIdCache) {
                statusHeightIdCache.put(cacheKey, match);
            }
            return match;
        } catch (Throwable e) {
            return false;
        }
    }
}
