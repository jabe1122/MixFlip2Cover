package io.github.jabe1122.mixflip2cover;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Point;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.WeakHashMap;
import io.github.libxposed.api.XposedModule;

/**
 * Functional per-widget layout adaptation for the 356x356 cover-screen square card container:
 * - RecentTaskView: 2x2 large-icon grid (most recent 4 apps, original 40dp/130px icon size, symmetric spacing).
 * - Built-in MAML widgets (weather, dateshow, schedule_reminder, music, music_large, dynamic_clock,
 *   diamond_time, daily_steps, vitality): full-bleed 356x356 canvas with high-priority MAML variable
 *   adaptation so backgrounds fill the card, touch targets match 1:1, and all content fits without clipping.
 *
 * Several hooked symbols are build-specific (the RecentTaskView synthetic lambda, MAML variables with random
 * suffixes such as {@code _aqku4q}). Every such lookup is optional: a missing symbol only disables that
 * adaptation (logged once) and never affects the stock widget.
 */
final class WidgetContentFit {
    private static final String TAG = "MF2C.WidgetFit";
    private static final int VAR_HIGH_PRIORITY = 1;
    private static final float DEFAULT_SQUARE_SIZE = CoverGeometry.CARD_SIZE;
    private static final String RECENT_LAMBDA = "lambda$initViews$2$com-miui-fliphome-widget-ui-RecentTaskView";

    private static final WeakHashMap<View, String> mamlPaths = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> recentViews = new WeakHashMap<>();
    private static final WeakHashMap<Object, Object> dialSavedExpr = new WeakHashMap<>();
    private static final WeakHashMap<View, int[]> recentOriginalMetrics = new WeakHashMap<>();
    /** Last inputs adaptMaml ran with, per view; onLayout re-adapts only when one of them changes. */
    private static final WeakHashMap<View, MamlState> mamlStates = new WeakHashMap<>();
    private static volatile Boolean lastEffectiveDark;
    private static volatile Double lastDesktopPureBlur;
    private static volatile WeakReference<Object> lastBgHelperRef;
    private static volatile WeakReference<ClassLoader> lastBgLoaderRef;
    private static volatile Boolean lastSentDesk;
    private static volatile Integer lastSentAppColor;
    private static volatile Boolean lastSentWpDark;
    private static volatile boolean queryReceiverRegistered;
    /** Cached fliphome static helpers (BlurColorUtil.isColorDark, WallpaperUtils.hasAppliedDarkWallpaper). */
    private static volatile Method isColorDarkMethod, darkWallpaperMethod;
    private static volatile boolean isColorDarkResolved;
    private static volatile ClassLoader darkWallpaperFailedLoader;

    private record MamlState(int rootIdentity, float width, float height, boolean active, Boolean dark,
                             Double pureBlur, boolean night) {}

    private WidgetContentFit() {}

    static void install(XposedModule module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> maml = loader.loadClass("com.miui.maml.component.MamlView");
        Class<?> host = loader.loadClass("com.miui.fliphome.widget.ui.maml.FlipMaMlHostView");
        Class<?> recent = loader.loadClass("com.miui.fliphome.widget.ui.RecentTaskView");
        Class<?> recentAdapter = loader.loadClass("com.miui.fliphome.widget.ui.RecentTaskView$RecentTaskAdapter");
        Class<?> elementGroup = loader.loadClass("com.miui.maml.elements.ElementGroup");

        Method putVar = maml.getDeclaredMethod("putVariableNumber", String.class, double.class, int.class);
        putVar.setAccessible(true);
        Method setScale = maml.getDeclaredMethod("setScale", float.class, int.class, int.class);
        setScale.setAccessible(true);
        Method requestUpdate = maml.getDeclaredMethod("requestUpdate");
        requestUpdate.setAccessible(true);

        module.hook(host.getDeclaredConstructor(Context.class, String.class, int.class, String.class)).intercept(chain -> {
            Object result = chain.proceed();
            try {
                if (chain.getThisObject() instanceof View v && !recent.isInstance(v)) {
                    String path = chain.getArg(1) instanceof String s ? s : "";
                    mamlPaths.put(v, path);
                    adaptMaml(v, elementGroup, putVar, setScale, requestUpdate, true);
                }
            } catch (Throwable t) {
                Log.w(TAG, "widget adaptation skipped", t);
            }
            return result;
        });

        module.hook(maml.getDeclaredMethod("onLayout", boolean.class, int.class, int.class, int.class, int.class)).intercept(chain -> {
            Object result = chain.proceed();
            try {
                if (chain.getThisObject() instanceof View v && host.isInstance(v)) {
                    if (recent.isInstance(v)) {
                        recentViews.put(v, Boolean.TRUE);
                        adaptRecent(v);
                    } else {
                        mamlPaths.putIfAbsent(v, resolveWidgetName(v));
                        adaptMaml(v, elementGroup, putVar, setScale, requestUpdate, false);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "widget layout adaptation skipped", t);
            }
            return result;
        });

        module.hook(recent.getDeclaredMethod("initViews")).intercept(chain -> {
            Object result = chain.proceed();
            try {
                if (chain.getThisObject() instanceof View v) {
                    recentViews.put(v, Boolean.TRUE);
                    adaptRecent(v);
                }
            } catch (Throwable t) {
                Log.w(TAG, "recent apps adaptation skipped", t);
            }
            return result;
        });

        // Note: RECENT_LAMBDA (lambda$initViews$2) is hooked in PinnedAppsWidget.install()
        // so both pinned-app merging and 4-item truncation share a single hook on that method.

        Class<?> listAdapter = recentAdapter.getSuperclass();
        if (listAdapter != null) {
            try {
                Method submitList = listAdapter.getDeclaredMethod("submitList", List.class);
                module.hook(submitList).intercept(chain -> {
                    Object[] args = null;
                    try {
                        if (recentAdapter.isInstance(chain.getThisObject())
                                && (chain.getArg(0) == null || chain.getArg(0) instanceof List<?>)) {
                            List<?> incoming = (List<?>) chain.getArg(0);
                            List<?> filtered = PinnedAppsWidget.filterSubmitList(chain.getThisObject(), incoming);
                            if (filtered != incoming) {
                                args = chain.getArgs().toArray();
                                args[0] = filtered;
                            }
                        }
                    } catch (Throwable ignored) {
                        args = null;
                    }
                    return args != null ? chain.proceed(args) : chain.proceed();
                });
            } catch (NoSuchMethodException e) {
                Log.w(TAG, "recent apps submitList hook unavailable");
            }
        }

        PinnedAppsWidget.install(module, loader);
        installFrostedGlassMaterialHooks(module, loader);

        ModuleConfig.addListener(() -> {
            for (View v : new ArrayList<>(mamlPaths.keySet())) {
                if (v != null) adaptMaml(v, elementGroup, putVar, setScale, requestUpdate, true);
            }
            for (View v : new ArrayList<>(recentViews.keySet())) {
                if (v != null) {
                    adaptRecent(v);
                    PinnedAppsWidget.refreshView(v, true);
                }
            }
        });
    }

    private static void installFrostedGlassMaterialHooks(XposedModule module, ClassLoader loader) {
        try {
            Class<?> bgHelperClass = loader.loadClass("com.miui.fliphome.widget.WidgetBgHelper");
            Class<?> bgStateClass = loader.loadClass("com.miui.fliphome.widget.WidgetBgHelper$BackgroundState");
            Class<?> mamlClass = loader.loadClass("com.miui.maml.component.MamlView");
            Method putVar = mamlClass.getDeclaredMethod("putVariableNumber", String.class, double.class, int.class);
            putVar.setAccessible(true);
            Method reqUpdate = mamlClass.getDeclaredMethod("requestUpdate");
            reqUpdate.setAccessible(true);

            for (Constructor<?> ctor : bgHelperClass.getDeclaredConstructors()) {
                ctor.setAccessible(true);
                module.hook(ctor).intercept(chain -> {
                    Object res = chain.proceed();
                    try {
                        syncRightPaneToSystemUi(chain.getThisObject(), loader, true);
                    } catch (Throwable ignored) {
                    }
                    return res;
                });
            }

            try {
                Method setHomeBlur = bgHelperClass.getDeclaredMethod("setBlurStateOfHome", bgStateClass, boolean.class);
                setHomeBlur.setAccessible(true);
                module.hook(setHomeBlur).intercept(chain -> {
                    Object res = chain.proceed();
                    try {
                        Object helper = chain.getThisObject();
                        syncRightPaneToSystemUi(helper, loader, false);
                        if (SquareCards.active()) {
                            Object state = chain.getArg(0);
                            boolean effectiveDark = Boolean.TRUE.equals(chain.getArg(1))
                                    || isNightMode(helper)
                                    || isCoverWallpaperDark(helper, loader);
                            applySoftMatteFrostedGlass(helper, state, effectiveDark);
                        }
                    } catch (Throwable ignored) {
                    }
                    return res;
                });
            } catch (NoSuchMethodException ignored) {
            }

            try {
                Method setDefaultBlur = bgHelperClass.getDeclaredMethod("setDefaultBlurState", bgStateClass);
                setDefaultBlur.setAccessible(true);
                module.hook(setDefaultBlur).intercept(chain -> {
                    Object res = chain.proceed();
                    try {
                        Object helper = chain.getThisObject();
                        syncRightPaneToSystemUi(helper, loader, false);
                        if (SquareCards.active()) {
                            Object state = chain.getArg(0);
                            boolean effectiveDark = isEffectiveDark(helper, loader);
                            applySoftMatteFrostedGlass(helper, state, effectiveDark);
                        }
                    } catch (Throwable ignored) {
                    }
                    return res;
                });
            } catch (NoSuchMethodException ignored) {
            }

            try {
                Method refreshMaml = bgHelperClass.getDeclaredMethod(
                        "refreshMamlParams", List.class, double.class, double.class);
                refreshMaml.setAccessible(true);
                module.hook(refreshMaml).intercept(chain -> {
                    Object[] args = null;
                    boolean effectiveDark = false;
                    double pureBlur = 1.0;
                    try {
                        Object helper = chain.getThisObject();
                        syncRightPaneToSystemUi(helper, loader, false);
                        args = chain.getArgs().toArray();
                        effectiveDark = isEffectiveDark(helper, loader);
                        pureBlur = args.length >= 2 && args[1] instanceof Number n ? n.doubleValue() : 1.0;
                        if (SquareCards.active() && args.length >= 3) {
                            args[2] = effectiveDark ? 0.0 : 1.0; // 0.0 = white text, 1.0 = black text
                            lastEffectiveDark = effectiveDark;
                            lastDesktopPureBlur = pureBlur;
                        }
                    } catch (Throwable t) {
                        args = null;
                    }
                    if (args == null) return chain.proceed();
                    Object res = chain.proceed(args);
                    try {
                        if (SquareCards.active() && args.length >= 1 && args[0] instanceof List<?> list) {
                            for (Object item : list) {
                                if (item instanceof View v && mamlClass.isInstance(v)) {
                                    syncMamlTextColorVars(v, putVar, reqUpdate, effectiveDark, pureBlur);
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    return res;
                });
            } catch (NoSuchMethodException ignored) {
            }

            try {
                Method setSolid = bgHelperClass.getDeclaredMethod("setSolidColorState", bgStateClass, boolean.class);
                setSolid.setAccessible(true);
                module.hook(setSolid).intercept(chain -> {
                    Object[] args = null;
                    try {
                        Object helper = chain.getThisObject();
                        syncRightPaneToSystemUi(helper, loader, false);
                        if (SquareCards.active() && helper != null && chain.getArgs().size() >= 2) {
                            args = chain.getArgs().toArray();
                            args[1] = isEffectiveDark(helper, loader);
                        }
                    } catch (Throwable t) {
                        args = null;
                    }
                    return args != null ? chain.proceed(args) : chain.proceed();
                });
            } catch (NoSuchMethodException ignored) {
            }

            try {
                Method updateBg = bgHelperClass.getDeclaredMethod("updateBackground", boolean.class);
                updateBg.setAccessible(true);

                Method onDeskChanged = bgHelperClass.getDeclaredMethod("onDeskChanged", boolean.class);
                onDeskChanged.setAccessible(true);
                module.hook(onDeskChanged).intercept(chain -> {
                    Object helper = chain.getThisObject();
                    boolean wasDesk = false;
                    try {
                        wasDesk = helper != null && Boolean.TRUE.equals(CoverGeometry.field(helper, "isDesk"));
                    } catch (Throwable ignored) {
                    }
                    Object res = chain.proceed();
                    try {
                        syncRightPaneToSystemUi(helper, loader, false);
                        boolean nowDesk = Boolean.TRUE.equals(chain.getArg(0));
                        if (SquareCards.active() && helper != null && wasDesk && !nowDesk) {
                            updateBg.invoke(helper, false);
                        }
                    } catch (Throwable ignored) {
                    }
                    return res;
                });

                Method onAppColorChanged = bgHelperClass.getDeclaredMethod("onAppColorChanged", int.class);
                onAppColorChanged.setAccessible(true);
                module.hook(onAppColorChanged).intercept(chain -> {
                    Object res = chain.proceed();
                    try {
                        Object helper = chain.getThisObject();
                        syncRightPaneToSystemUi(helper, loader, false);
                        if (SquareCards.active()
                                && helper != null
                                && !Boolean.TRUE.equals(CoverGeometry.field(helper, "isDesk"))) {
                            boolean dark = isEffectiveDark(helper, loader);
                            if (lastEffectiveDark == null || lastEffectiveDark != dark) {
                                updateBg.invoke(helper, false);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    return res;
                });
            } catch (NoSuchMethodException ignored) {
            }
        } catch (Throwable t) {
            Log.w(TAG, "widget background material hooks unavailable", t);
        }
    }

    private static void syncRightPaneToSystemUi(Object helper, ClassLoader loader, boolean force) {
        if (helper == null) return;
        lastBgHelperRef = new WeakReference<>(helper);
        if (loader != null) lastBgLoaderRef = new WeakReference<>(loader);
        try {
            Object ctxObj = CoverGeometry.field(helper, "mContext");
            if (!(ctxObj instanceof Context ctx)) return;
            Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
            if (!queryReceiverRegistered) {
                queryReceiverRegistered = true;
                try {
                    // Only SystemUI (holder of STATUS_BAR_SERVICE) may ask the launcher to re-publish its state.
                    appCtx.registerReceiver(new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context c, Intent i) {
                            Object h = lastBgHelperRef != null ? lastBgHelperRef.get() : null;
                            ClassLoader cl = lastBgLoaderRef != null ? lastBgLoaderRef.get() : null;
                            syncRightPaneToSystemUi(h, cl, true);
                        }
                    }, new IntentFilter(NativeStatusStyle.ACTION_QUERY_RIGHT_PANE_BG),
                            CoverGeometry.STATUS_BAR_PERMISSION, null, Context.RECEIVER_EXPORTED);
                } catch (Throwable t) {
                    Log.w(TAG, "right pane query receiver unavailable", t);
                }
            }
            boolean isDesk = Boolean.TRUE.equals(CoverGeometry.field(helper, "isDesk"));
            int appColor = ((Number) CoverGeometry.field(helper, "appColor")).intValue();
            ClassLoader effectiveLoader = loader != null ? loader : (lastBgLoaderRef != null ? lastBgLoaderRef.get() : null);
            boolean wpDark = isCoverWallpaperDark(helper, effectiveLoader);
            if (!force
                    && lastSentDesk != null && lastSentDesk == isDesk
                    && lastSentAppColor != null && lastSentAppColor == appColor
                    && lastSentWpDark != null && lastSentWpDark == wpDark) {
                return;
            }
            lastSentDesk = isDesk;
            lastSentAppColor = appColor;
            lastSentWpDark = wpDark;
            ctx.sendBroadcast(new Intent(NativeStatusStyle.ACTION_RIGHT_PANE_BG)
                    .setPackage(CoverGeometry.SYSTEMUI)
                    .putExtra("isDesk", isDesk)
                    .putExtra("appColor", appColor)
                    .putExtra("wallpaperDark", wpDark));
        } catch (Throwable ignored) {
        }
    }

    private static boolean isEffectiveDark(Object helper, ClassLoader loader) {
        boolean isDesk = true;
        if (helper != null) {
            try {
                isDesk = Boolean.TRUE.equals(CoverGeometry.field(helper, "isDesk"));
            } catch (Throwable ignored) {
            }
        }
        if (isDesk) {
            return isCoverWallpaperDark(helper, loader) || isNightMode(helper);
        } else {
            return isAppBackgroundDark(helper, loader);
        }
    }

    private static boolean isAppBackgroundDark(Object helper, ClassLoader loader) {
        if (helper == null) return false;
        try {
            int appColor = ((Number) CoverGeometry.field(helper, "appColor")).intValue();
            Method isDark = isColorDarkMethod;
            if (!isColorDarkResolved && loader != null) {
                isColorDarkResolved = true;
                try {
                    isDark = loader.loadClass("com.miui.fliphome.utils.BlurColorUtil").getDeclaredMethod("isColorDark", int.class);
                    isDark.setAccessible(true);
                    isColorDarkMethod = isDark;
                } catch (Throwable t) {
                    isDark = null;
                }
            }
            if (isDark != null) {
                try {
                    return Boolean.TRUE.equals(isDark.invoke(null, appColor));
                } catch (Throwable ignored) {
                }
            }
            return NativeStatusStyle.isColorDark(appColor);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void applySoftMatteFrostedGlass(Object helper, Object state, boolean effectiveDark) {
        if (helper == null || state == null) return;
        boolean appliedOfficial = false;
        try {
            Object params = CoverGeometry.field(helper, "mBgParams");
            if (params != null) {
                int cScreen = ((Number) CoverGeometry.field(params, "BLUR_COLOR_OF_MODE_SCREEN")).intValue();
                int c1 = ((Number) CoverGeometry.field(params, "BLUR_DEFAULT_COLOR_ONE")).intValue();
                int c2 = ((Number) CoverGeometry.field(params, "BLUR_DEFAULT_COLOR_TWO")).intValue();
                int unFocus = ((Number) CoverGeometry.field(params,
                        effectiveDark ? "BLUR_DEFAULT_COLOR_UN_FOCUS_DARK" : "BLUR_DEFAULT_COLOR_UN_FOCUS")).intValue();

                ArrayList<Point> first = new ArrayList<>();
                if (effectiveDark) {
                    // Softly lift dark backgrounds (mode 14 = SCREEN) before applying official soft-matte glass layers
                    first.add(new Point(cScreen, 14));
                }
                first.add(new Point(c1, 19));
                first.add(new Point(c2, 3));

                ArrayList<Point> second = new ArrayList<>(first);
                second.add(new Point(unFocus, 3));

                CoverGeometry.setField(state, "blendFirstColors", first);
                CoverGeometry.setField(state, "blendSecondColors", second);
                CoverGeometry.setField(state, "blendSecondColor", unFocus);
                CoverGeometry.setField(state, "mamlTextColor", effectiveDark ? 0 : 1);
                appliedOfficial = true;
            }
        } catch (Throwable ignored) {
        }
        if (!appliedOfficial) {
            try {
                int fallbackTint = effectiveDark ? 0x4D1A1C20 : 0x42FFFFFF;
                ArrayList<Point> fallbackLayers = new ArrayList<>();
                fallbackLayers.add(new Point(fallbackTint, 3));
                CoverGeometry.setField(state, "blendFirstColors", fallbackLayers);
                CoverGeometry.setField(state, "blendSecondColors", new ArrayList<>(fallbackLayers));
                CoverGeometry.setField(state, "blendSecondColor", fallbackTint);
                CoverGeometry.setField(state, "mamlTextColor", effectiveDark ? 0 : 1);
            } catch (Throwable ignored) {
            }
        }
    }

    private static Object variables(Object root) throws ReflectiveOperationException {
        return CoverGeometry.invoke(root, "getVariables", CoverGeometry.NO_PARAMS);
    }

    private static void syncMamlTextColorVars(
            View v,
            Method putVar,
            Method reqUpdate,
            boolean effectiveDark,
            double desktopPureBlur) {
        try {
            Object root = CoverGeometry.field(v, "mRoot");
            if (root == null) return;
            Object vars = variables(root);
            double targetColorChange = effectiveDark ? 0.0 : 255.0;
            boolean updated = false;

            // MAML variable names below carry per-widget random suffixes from the stock widget packages; a
            // missing name simply skips that adjustment (hasVar is false).
            // Weather widget uses its own sky gradient on desktop (white text = 0.0), and adapts to app bg inside apps
            if (hasVar(vars, "colorChange_aqku4q")) {
                double weatherText = desktopPureBlur <= 1.0 ? 0.0 : targetColorChange;
                double weatherBg = desktopPureBlur <= 1.0 ? 255.0 : 0.0;
                putVar.invoke(v, "colorChange_aqku4q", weatherText, 0);
                putVar.invoke(v, "begin_aqku4q", weatherText, 0);
                putVar.invoke(v, "end_aqku4q", weatherText, 0);
                if (hasVar(vars, "bgChange_aqku4q")) {
                    putVar.invoke(v, "bgChange_aqku4q", weatherBg, 0);
                    putVar.invoke(v, "bgBegin_aqku4q", weatherBg, 0);
                    putVar.invoke(v, "bgEnd_aqku4q", weatherBg, 0);
                }
                if (hasVar(vars, "bgChange_rud6zc")) {
                    putVar.invoke(v, "bgChange_rud6zc", weatherBg, 0);
                }
                updated = true;
            }

            // Vitality & Daily Steps widgets switch between colored icons on desktop (255) and monochrome in-app icons (0)
            double coloredIconAlpha = desktopPureBlur <= 1.0 ? 255.0 : 0.0;
            String[] iconAlphaVars = {
                    "coloredImageAlpha_nmvwnx", "imageBegin_nmvwnx", "imageEnd_nmvwnx",
                    "imageAlpha_ngl4ct", "imageBegin_ngl4ct", "imageEnd_ngl4ct"
            };
            for (String varName : iconAlphaVars) {
                if (hasVar(vars, varName)) {
                    putVar.invoke(v, varName, coloredIconAlpha, 0);
                    updated = true;
                }
            }

            String[] colorVars = {
                    "colorChange_7n58oc", "begin_7n58oc", "end_7n58oc",
                    "colorChange_ajby5d", "begin_ajby5d", "end_ajby5d",
                    "colorChange_ngl4ct", "begin_ngl4ct", "end_ngl4ct",
                    "state.color_change", "begin_nmvwnx", "end_nmvwnx",
                    "colorChange_rud6zc",
                    "colorChange", "color_change", "begin", "end"
            };
            for (String varName : colorVars) {
                if (hasVar(vars, varName)) {
                    putVar.invoke(v, varName, targetColorChange, 0);
                    updated = true;
                }
            }
            if (updated) {
                reqUpdate.invoke(v);
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isNightMode(Object bgHelper) {
        try {
            if (bgHelper != null) {
                Object ctxObj = CoverGeometry.field(bgHelper, "mContext");
                if (ctxObj instanceof Context ctx) {
                    return (ctx.getResources().getConfiguration().uiMode & 48) == 32;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isCoverWallpaperDark(Object bgHelper, ClassLoader loader) {
        try {
            if (bgHelper != null) {
                Object wpHelper = CoverGeometry.field(bgHelper, "mWallpaperHelper");
                if (wpHelper != null && Boolean.TRUE.equals(CoverGeometry.field(wpHelper, "isDarkWallpaper"))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Method m = darkWallpaperMethod;
            if (m == null && loader != null && loader != darkWallpaperFailedLoader) {
                try {
                    m = loader.loadClass("com.miui.fliphome.wallpaper.WallpaperUtils").getDeclaredMethod("hasAppliedDarkWallpaper");
                    m.setAccessible(true);
                    darkWallpaperMethod = m;
                } catch (Throwable t) {
                    darkWallpaperFailedLoader = loader;
                    m = null;
                }
            }
            if (m != null && Boolean.TRUE.equals(m.invoke(null))) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    static void truncateRecentListField(View recentView) {
        if (ModuleConfig.hasPinnedWidgetApps()) return;
        try {
            Object raw = CoverGeometry.field(recentView, "mValues");
            if (raw instanceof List<?> list && list.size() > 4) {
                CoverGeometry.setField(recentView, "mValues", new ArrayList<>(list.subList(0, 4)));
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    private static void adaptRecent(View recentView) {
        try {
            Object rvObj = CoverGeometry.field(recentView, "mRecyclerView");
            if (!(rvObj instanceof ViewGroup rv)) return;
            Object adapter = CoverGeometry.invoke(rv, "getAdapter", CoverGeometry.NO_PARAMS);
            if (adapter == null) return;

            int iconSize = ((Number) CoverGeometry.field(adapter, "mIconSize")).intValue();
            if (iconSize <= 0) iconSize = 130;

            if (!recentOriginalMetrics.containsKey(recentView)
                    && rv.getLayoutParams() instanceof FrameLayout.LayoutParams initLp) {
                int origBottom = ((Number) CoverGeometry.field(adapter, "mItemMarginBottom")).intValue();
                int origEnd = ((Number) CoverGeometry.field(adapter, "mItemMarginEnd")).intValue();
                recentOriginalMetrics.put(recentView, new int[]{
                        initLp.topMargin, initLp.getMarginStart(), origBottom, origEnd
                });
            }

            if (!SquareCards.active()) {
                int[] orig = recentOriginalMetrics.get(recentView);
                if (orig != null && rv.getLayoutParams() instanceof FrameLayout.LayoutParams lp) {
                    lp.topMargin = orig[0];
                    lp.setMarginStart(orig[1]);
                    lp.setMarginEnd(0);
                    rv.setLayoutParams(lp);
                    CoverGeometry.setField(adapter, "mItemMarginBottom", orig[2]);
                    CoverGeometry.setField(adapter, "mItemMarginEnd", orig[3]);
                    updateRecyclerChildrenMargins(rv, orig[2], orig[3]);
                }
                PinnedAppsWidget.refreshView(recentView, false);
                return;
            }

            truncateRecentListField(recentView);

            int w = recentView.getWidth() > 0 ? recentView.getWidth() : Math.round(DEFAULT_SQUARE_SIZE);
            int h = recentView.getHeight() > 0 ? recentView.getHeight() : Math.round(DEFAULT_SQUARE_SIZE);
            int side = Math.min(w, h);
            int gap = Math.max(12, Math.round((side - 2 * iconSize) / 3f));

            if (rv.getLayoutParams() instanceof FrameLayout.LayoutParams lp) {
                if (lp.topMargin != gap || lp.getMarginStart() != gap || lp.getMarginEnd() != 0) {
                    lp.topMargin = gap;
                    lp.bottomMargin = 0;
                    lp.setMarginStart(gap);
                    lp.setMarginEnd(0);
                    rv.setLayoutParams(lp);
                }
            }
            CoverGeometry.setField(adapter, "mItemMarginBottom", gap);
            CoverGeometry.setField(adapter, "mItemMarginEnd", gap);
            updateRecyclerChildrenMargins(rv, gap, gap);
            PinnedAppsWidget.refreshView(recentView, false);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    private static void updateRecyclerChildrenMargins(ViewGroup rv, int bottomMargin, int endMargin) {
        int count = rv.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = rv.getChildAt(i);
            if (child != null && child.getLayoutParams() instanceof ViewGroup.MarginLayoutParams mlp) {
                if (mlp.bottomMargin != bottomMargin || mlp.getMarginEnd() != endMargin) {
                    mlp.bottomMargin = bottomMargin;
                    mlp.setMarginEnd(endMargin);
                    child.setLayoutParams(mlp);
                }
            }
        }
    }

    /**
     * @param force re-apply even when size / mode / tint are unchanged (construction and settings changes);
     *              layout passes pass {@code false} so requestUpdate cannot feed a layout loop.
     */
    private static void adaptMaml(
            View v,
            Class<?> elementGroupClass,
            Method putVar,
            Method setScale,
            Method requestUpdate,
            boolean force) {
        try {
            v.setScaleX(1f);
            v.setScaleY(1f);
            v.setTranslationX(0f);
            v.setTranslationY(0f);

            Object root = CoverGeometry.field(v, "mRoot");
            if (root == null) return;

            boolean active = SquareCards.active();
            float rawW = v.getWidth() > 0 ? v.getWidth() : DEFAULT_SQUARE_SIZE;
            float rawH = v.getHeight() > 0 ? v.getHeight() : DEFAULT_SQUARE_SIZE;
            Context ctx = v.getContext();
            boolean night = ctx != null && (ctx.getResources().getConfiguration().uiMode & 48) == 32;
            MamlState state = new MamlState(
                    System.identityHashCode(root), rawW, rawH, active, lastEffectiveDark, lastDesktopPureBlur, night);
            if (!force && Objects.equals(mamlStates.get(v), state)) return;
            mamlStates.put(v, state);

            float w = active ? Math.min(rawW, rawH) : rawW;
            float h = active ? Math.min(rawW, rawH) : rawH;
            double s = h / DEFAULT_SQUARE_SIZE;

            String name = resolveWidgetName(v);
            Object vars = variables(root);

            if (matches(name, "weather") || hasVar(vars, "scale_rud6zc")) {
                setScale.invoke(v, 1.0f, 0, 0);
                double sc = active ? 2.40 * s : 3.25;
                putVar.invoke(v, "state.scale", sc, VAR_HIGH_PRIORITY);
                putVar.invoke(v, "scale_rud6zc", sc, VAR_HIGH_PRIORITY);
                putVar.invoke(v, "beyond_design_rud6zc", 14.0 * sc, VAR_HIGH_PRIORITY);
            } else if (matches(name, "dateshow") || hasVar(vars, "scale_7n58oc")) {
                setScale.invoke(v, 1.0f, 0, 0);
                double sc = active ? 2.40 * s : 3.25;
                putVar.invoke(v, "scale_7n58oc", sc, VAR_HIGH_PRIORITY);
            } else if (matches(name, "schedule_reminder") || hasVar(vars, "scale_ajby5d")) {
                setScale.invoke(v, 1.0f, 0, 0);
                double sc = active ? 2.40 * s : 3.25;
                putVar.invoke(v, "scale_ajby5d", sc, VAR_HIGH_PRIORITY);
            } else if (matches(name, "music_large") || hasVar(vars, "albumMargin")) {
                setScale.invoke(v, 1.0f, 0, 0);
                double sr = active ? 2.10 * s : 2.908;
                putVar.invoke(v, "sr", sr, VAR_HIGH_PRIORITY);
                putVar.invoke(v, "albumMargin", (active ? 5.0 : 4.0) * sr, VAR_HIGH_PRIORITY);
                putVar.invoke(v, "albumSize", 95.0 * sr, VAR_HIGH_PRIORITY);
                putVar.invoke(v, "albumFontSize", 12.0 * sr, VAR_HIGH_PRIORITY);
                putVar.invoke(v, "buttonY", (active ? 106.5 : 119.69) * sr, VAR_HIGH_PRIORITY);
                putVar.invoke(v, "buttonSize", 36.61 * sr, VAR_HIGH_PRIORITY);
                putVar.invoke(v, "buttonLeftX", 11.69 * sr, VAR_HIGH_PRIORITY);
                putVar.invoke(v, "buttonRightX", 54.69 * sr, VAR_HIGH_PRIORITY);
                putVar.invoke(v, "buttonCenter", 103.0 * sr, VAR_HIGH_PRIORITY);
                reinitFolmeGeometry(root, elementGroupClass);
            } else if (matches(name, "music") || hasVar(vars, "design_rate")) {
                setScale.invoke(v, 1.0f, 0, 0);
                double rate = active ? 2.30 * s : 3.25;
                double albumSize = 40.0 * rate;
                putVar.invoke(v, "design_rate", rate, VAR_HIGH_PRIORITY);
                putVar.invoke(v, "button_radius", 18.3 * rate, VAR_HIGH_PRIORITY);
                putVar.invoke(v, "corner_size", (active ? 9.7 : 11.69) * rate, VAR_HIGH_PRIORITY);
                putVar.invoke(v, "button_corner_size", (active ? 13.0 : 11.69) * rate, VAR_HIGH_PRIORITY);
                putVar.invoke(v, "album_size", albumSize, VAR_HIGH_PRIORITY);
                putVar.invoke(v, "text_gap", 6.0 * rate, VAR_HIGH_PRIORITY);
                putVar.invoke(v, "title_top", (active ? 59.0 : 60.0) * rate, VAR_HIGH_PRIORITY);
                putVar.invoke(v, "title_font_size", 12.0 * rate, VAR_HIGH_PRIORITY);
                putVar.invoke(v, "artist_font_size", 10.0 * rate, VAR_HIGH_PRIORITY);
                adaptMusicElements(root, elementGroupClass, active, 44.0 * rate);
            } else if (matches(name, "dynamic_clock") || hasVar(vars, "pointDeltaCenter")) {
                setScale.invoke(v, 1.0f, 0, 0);
                double sc = active ? 2.16 * s : (CoverDisplay.WIDTH / 372.0);
                putVar.invoke(v, "scale", sc, VAR_HIGH_PRIORITY);
                adaptDynamicClockDialGroup(root, elementGroupClass, active, 57.0 * sc);
            } else if (matches(name, "diamond_time") || hasVar(vars, "hourId")) {
                putVar.invoke(v, "scale", 3.25, VAR_HIGH_PRIORITY);
                if (active) {
                    putVar.invoke(v, "view_height", 558.0, VAR_HIGH_PRIORITY);
                    setScale.invoke(v, h / 558.0f, Math.round(w / 2.0f), 0);
                } else {
                    setScale.invoke(v, 1.0f, 0, 0);
                }
            } else if (matches(name, "daily_steps") || hasVar(vars, "base_multiplier_ngl4ct")) {
                setScale.invoke(v, 1.0f, 0, 0);
                double bm = active ? 2.10 * s : 3.25;
                double whRatio = active ? 1.0 : (w / (103.0 * 3.25));
                putVar.invoke(v, "base_multiplier_ngl4ct", bm, VAR_HIGH_PRIORITY);
                putVar.invoke(v, "wh_ratio_ngl4ct", whRatio, VAR_HIGH_PRIORITY);
            } else if (matches(name, "vitality") || hasVar(vars, "scale_nmvwnx")) {
                setScale.invoke(v, 1.0f, 0, 0);
                double sc = active ? 2.15 * s : 3.25;
                putVar.invoke(v, "state.scale", sc, VAR_HIGH_PRIORITY);
                putVar.invoke(v, "scale_nmvwnx", sc, VAR_HIGH_PRIORITY);
                putVar.invoke(v, "line_width_nmvwnx", w - 30.0 * sc, VAR_HIGH_PRIORITY);
            } else if (active) {
                float rootW = ((Number) CoverGeometry.invoke(root, "getWidth", CoverGeometry.NO_PARAMS)).floatValue();
                float rootH = ((Number) CoverGeometry.invoke(root, "getHeight", CoverGeometry.NO_PARAMS)).floatValue();
                if (rootW > 0 && rootH > h * 1.08f) {
                    float fit = Math.min(w / rootW, h / rootH);
                    int offsetX = Math.round((w - rootW * fit) / 2f);
                    setScale.invoke(v, fit, offsetX, 0);
                } else {
                    setScale.invoke(v, 1.0f, 0, 0);
                }
            } else {
                setScale.invoke(v, 1.0f, 0, 0);
            }

            if (active) {
                boolean dark = lastEffectiveDark != null
                        ? lastEffectiveDark
                        : (night || isCoverWallpaperDark(null, v.getClass().getClassLoader()));
                double pureBlur = lastDesktopPureBlur != null ? lastDesktopPureBlur : 1.0;
                putVar.invoke(v, "desktop_pure_blur", pureBlur, 0);
                putVar.invoke(v, "applied_light_wallpaper", dark ? 0.0 : 1.0, 0);
                syncMamlTextColorVars(v, putVar, requestUpdate, dark, pureBlur);
            }

            requestUpdate.invoke(v);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    private static void adaptDynamicClockDialGroup(
            Object root,
            Class<?> elementGroupClass,
            boolean active,
            double targetY) {
        try {
            Object innerGroup = CoverGeometry.field(root, "mInnerGroup");
            if (innerGroup == null) return;
            Object elementsObj = CoverGeometry.field(innerGroup, "mElements");
            if (!(elementsObj instanceof List<?> elements)) return;
            for (Object elem : elements) {
                if (elem != null && elementGroupClass.isInstance(elem)) {
                    Object yProp = CoverGeometry.field(elem, "mYProperty");
                    if (yProp == null) continue;
                    if (active) {
                        Object currentExpr = CoverGeometry.field(yProp, "mExpression");
                        if (currentExpr != null) {
                            dialSavedExpr.putIfAbsent(yProp, currentExpr);
                        }
                        CoverGeometry.setField(yProp, "mExpression", null);
                        CoverGeometry.setField(yProp, "mDefaultValue", targetY);
                    } else {
                        Object saved = dialSavedExpr.get(yProp);
                        if (saved != null) {
                            CoverGeometry.setField(yProp, "mExpression", saved);
                        }
                    }
                    // Only the first element group holds the dial; stop after it.
                    break;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    private static void reinitFolmeGeometry(Object root, Class<?> elementGroupClass) {
        try {
            Object innerGroup = CoverGeometry.field(root, "mInnerGroup");
            if (innerGroup != null) {
                reinitFolmeNode(innerGroup, elementGroupClass);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    private static void reinitFolmeNode(Object node, Class<?> elementGroupClass) {
        if (node == null) return;
        String[] propNames = {
                "mXProperty", "mYProperty", "mWidthProperty",
                "mHeightProperty", "mPivotXProperty", "mPivotYProperty"
        };
        for (String propName : propNames) {
            try {
                Object prop = CoverGeometry.field(node, propName);
                if (prop != null) {
                    CoverGeometry.invoke(prop, "init", CoverGeometry.NO_PARAMS);
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        if (elementGroupClass.isInstance(node)) {
            try {
                Object elementsObj = CoverGeometry.field(node, "mElements");
                if (elementsObj instanceof List<?> children) {
                    for (Object child : children) {
                        reinitFolmeNode(child, elementGroupClass);
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
    }

    private static void adaptMusicElements(
            Object root,
            Class<?> elementGroupClass,
            boolean active,
            double maskSize) {
        try {
            Object musicControl = CoverGeometry.invoke(root, "findElement", CoverGeometry.STRING_PARAM, "music_control");
            if (musicControl != null && elementGroupClass.isInstance(musicControl)) {
                Object mcElementsObj = CoverGeometry.field(musicControl, "mElements");
                if (mcElementsObj instanceof List<?> mcElements) {
                    for (Object child : mcElements) {
                        if (child != null && elementGroupClass.isInstance(child)) {
                            Object albumChildrenObj = CoverGeometry.field(child, "mElements");
                            if (albumChildrenObj instanceof List<?> albumChildren && albumChildren.size() >= 4) {
                                Object maskImg = albumChildren.get(3);
                                double target = active ? maskSize : -1.0;
                                Object wProp = CoverGeometry.field(maskImg, "mWidthProperty");
                                Object hProp = CoverGeometry.field(maskImg, "mHeightProperty");
                                if (wProp != null) CoverGeometry.setField(wProp, "mDefaultValue", target);
                                if (hProp != null) CoverGeometry.setField(hProp, "mDefaultValue", target);
                            }
                            break;
                        }
                    }
                }
            }
            int clamp = active ? 1 : 2;
            String[] textNames = {"title", "title_rtl", "accept", "accept_rtl"};
            for (String textName : textNames) {
                Object textElem = CoverGeometry.invoke(root, "findElement", CoverGeometry.STRING_PARAM, textName);
                if (textElem != null) {
                    CoverGeometry.setField(textElem, "mLineClamp", clamp);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    private static String resolveWidgetName(View v) {
        String path = mamlPaths.get(v);
        if (path != null && !path.isEmpty()) return path;
        try {
            Object tag = v.getTag();
            if (tag != null) {
                Object fileName = CoverGeometry.field(tag, "mFileName");
                if (fileName instanceof String s && !s.isEmpty()) return s;
                Object resPath = CoverGeometry.field(tag, "mResPath");
                if (resPath instanceof String s && !s.isEmpty()) return s;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return "";
    }

    private static boolean matches(String name, String token) {
        return name != null && name.contains(token);
    }

    private static boolean hasVar(Object vars, String varName) {
        if (vars == null) return false;
        try {
            return Boolean.TRUE.equals(CoverGeometry.invoke(vars, "existsDouble", CoverGeometry.STRING_PARAM, varName));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
