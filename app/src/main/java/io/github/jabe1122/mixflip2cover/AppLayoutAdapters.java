package io.github.jabe1122.mixflip2cover;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Universal fake status-bar placeholder collapsing and layout adaptations for cover-screen apps.
 */
final class AppLayoutAdapters {
    private static final String TAG = "MF2C.Layout";
    private static final Set<String> FAKE_STATUS_BAR_IDS = Set.of(
            "status_bar_view",
            "fake_status_bar_view",
            "immersion_status_bar_view",
            "statusbarutil_fake_status_bar_view",
            "statusbarutil_translucent_view",
            "custom_status_bar_background",
            "status_bar_placeholder",
            "status_bar_space",
            "status_bar_fix",
            "v_status_bar",
            "view_status_bar",
            "top_status_bar",
            "status_bar_bg",
            "qmui_topbar_status_bar_placeholder"
    );

    // All maps below are only touched on the main thread.
    static final WeakHashMap<Activity, CalendarLayout> calendars = new WeakHashMap<>();
    static final WeakHashMap<Activity, MeituanLayout> meituans = new WeakHashMap<>();
    static final WeakHashMap<Activity, XiaomiShopLayout> xiaomiShops = new WeakHashMap<>();
    static final WeakHashMap<Activity, MiHealthLayout> miHealths = new WeakHashMap<>();
    static final WeakHashMap<Activity, QqLayout> qqs = new WeakHashMap<>();
    static final WeakHashMap<Activity, PlaceholderCollapse> placeholders = new WeakHashMap<>();
    /** Global-layout listeners registered per Activity, removed again when the Activity is destroyed. */
    private static final WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener> layoutListeners = new WeakHashMap<>();

    private AppLayoutAdapters() {}

    static void update(Activity a) {
        try {
            if (a == null || a.isDestroyed()) return;
            String pkg = a.getPackageName();
            if ("com.miui.fliphome".equals(pkg)) return;

            if ("com.tencent.mm".equals(pkg)) {
                WeChatTabLayout.update(a);
                return;
            }
            if ("com.android.calendar".equals(pkg)) {
                CalendarLayout adapter = calendars.get(a);
                if (adapter == null) {
                    adapter = new CalendarLayout(a);
                    calendars.put(a, adapter);
                }
                adapter.apply();
                return;
            }
            if ("com.sankuai.meituan".equals(pkg)) {
                MeituanLayout mt = meituans.get(a);
                if (mt == null) {
                    mt = new MeituanLayout(a);
                    meituans.put(a, mt);
                }
                mt.apply(true);
                return;
            }
            if ("com.xiaomi.shop".equals(pkg)) {
                XiaomiShopLayout shop = xiaomiShops.get(a);
                if (shop == null) {
                    shop = new XiaomiShopLayout(a);
                    xiaomiShops.put(a, shop);
                }
                shop.apply(true);
                return;
            }
            if ("com.mi.health".equals(pkg)) {
                MiHealthLayout health = miHealths.get(a);
                if (health == null) {
                    health = new MiHealthLayout(a);
                    miHealths.put(a, health);
                }
                health.apply(true);
                return;
            }
            if ("com.tencent.mobileqq".equals(pkg)) {
                QqLayout qq = qqs.get(a);
                if (qq == null) {
                    qq = new QqLayout(a);
                    qqs.put(a, qq);
                }
                qq.apply(true);
                return;
            }

            PlaceholderCollapse collapse = placeholders.get(a);
            if (collapse == null) {
                collapse = new PlaceholderCollapse(a);
                placeholders.put(a, collapse);
            }
            collapse.apply(true);
        } catch (Throwable t) {
            Log.w(TAG, "layout adapter failed", t);
        }
    }

    /**
     * Registers {@code listener} on the Activity's decor view and removes it (plus all per-Activity state)
     * when the Activity is destroyed, so listeners never outlive their Activity.
     */
    static void attachLayoutListener(Activity a, ViewTreeObserver.OnGlobalLayoutListener listener) {
        if (a == null || a.getWindow() == null || layoutListeners.containsKey(a)) return;
        View decor = a.getWindow().getDecorView();
        decor.getViewTreeObserver().addOnGlobalLayoutListener(listener);
        layoutListeners.put(a, listener);
        a.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}
            @Override public void onActivityStarted(Activity activity) {}
            @Override public void onActivityResumed(Activity activity) {}
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

            @Override
            public void onActivityDestroyed(Activity activity) {
                if (activity != a) return;
                detach(activity);
                activity.unregisterActivityLifecycleCallbacks(this);
            }
        });
    }

    private static void detach(Activity a) {
        try {
            ViewTreeObserver.OnGlobalLayoutListener l = layoutListeners.remove(a);
            if (l != null && a.getWindow() != null) {
                ViewTreeObserver vto = a.getWindow().getDecorView().getViewTreeObserver();
                if (vto.isAlive()) vto.removeOnGlobalLayoutListener(l);
            }
        } catch (Throwable ignored) {
        }
        calendars.remove(a);
        meituans.remove(a);
        xiaomiShops.remove(a);
        miHealths.remove(a);
        qqs.remove(a);
        placeholders.remove(a);
    }

    static final class CalendarLayout {
        final WeakReference<Activity> activity;
        final int tinyId, monthId;
        View content, header;
        Integer originalMargin;
        int gap;

        CalendarLayout(Activity a) {
            activity = new WeakReference<>(a);
            // Resolved once; 0 means this Calendar version does not have the view and the adapter stays idle.
            tinyId = a.getResources().getIdentifier("action_bar_container_tiny", "id", a.getPackageName());
            monthId = a.getResources().getIdentifier("month_motion_container", "id", a.getPackageName());
            if (tinyId != 0 && monthId != 0) {
                attachLayoutListener(a, this::apply);
            } else {
                Log.w(TAG, "calendar ids missing; calendar layout adaptation disabled");
            }
        }

        void apply() {
            try {
                applyInternal();
            } catch (Throwable t) {
                Log.w(TAG, "calendar layout failed", t);
            }
        }

        private void applyInternal() {
            if (tinyId == 0 || monthId == 0) return;
            Activity a = activity.get();
            if (a == null || a.isDestroyed()) return;
            if (content == null) {
                View tiny = a.findViewById(tinyId);
                if (tiny == null
                        || !(tiny.getParent() instanceof View parent)
                        || !(parent.getParent() instanceof View h)
                        || !(h.getParent() instanceof View c)
                        || !(c.getLayoutParams() instanceof ViewGroup.MarginLayoutParams lp)) {
                    return;
                }
                View month = a.findViewById(monthId);
                if (month == null || month.getParent() != c) return;
                content = c;
                header = h;
                originalMargin = lp.topMargin;
            }
            if (!(content.getLayoutParams() instanceof ViewGroup.MarginLayoutParams lp)) return;
            Display d = content.getDisplay();
            if (d == null) return;
            boolean outer = CoverDisplay.isOuterPortraitDisplay(d)
                    && FrameworkRuntime.active()
                    && ModuleConfig.immersiveEnabled()
                    && ModuleConfig.compatEnabled("com.android.calendar");
            if (outer && header.getTop() > 0 && header.getTop() < 200) {
                gap = header.getTop();
            }
            int target = originalMargin - (outer ? gap : 0);
            if (lp.topMargin != target) {
                lp.topMargin = target;
                content.setLayoutParams(lp);
                ModLog.i(TAG, "calendar top offset=" + target);
            }
        }
    }

    static final class PlaceholderCollapse {
        /**
         * Layout passes right after creation always rescan because content is still being inflated; after
         * that the tree is only rescanned when the decor size or the active state changes (explicit
         * {@link AppLayoutAdapters#update} calls always rescan).
         */
        private static final int INITIAL_SCAN_BUDGET = 12;

        final WeakReference<Activity> activity;
        final WeakHashMap<View, Integer> originalHeights = new WeakHashMap<>();
        int lastWidth = -1, lastHeight = -1, initialScans;
        Boolean lastActive;

        PlaceholderCollapse(Activity a) {
            activity = new WeakReference<>(a);
            attachLayoutListener(a, () -> apply(false));
        }

        void apply(boolean force) {
            try {
                Activity a = activity.get();
                if (a == null || a.isDestroyed() || a.getWindow() == null) return;
                View root = a.getWindow().getDecorView();
                if (root == null) return;
                boolean active = AppCompatibility.isResourcePatchActiveFor(a);
                int w = root.getWidth();
                int h = root.getHeight();
                boolean changed = force
                        || initialScans < INITIAL_SCAN_BUDGET
                        || w != lastWidth || h != lastHeight
                        || lastActive == null || lastActive != active;
                if (!changed) return;
                if (initialScans < INITIAL_SCAN_BUDGET) initialScans++;
                lastWidth = w;
                lastHeight = h;
                lastActive = active;
                if (active) {
                    scanTree(root, 0);
                } else {
                    restoreAll();
                }
            } catch (Throwable ignored) {
            }
        }

        private void scanTree(View view, int depth) {
            if (view == null || depth > 7) return;
            if (isFakeStatusBarPlaceholder(view)) {
                ViewGroup.LayoutParams lp = view.getLayoutParams();
                if (lp != null && lp.height > CoverResourceOverlay.COVER_APP_STATUS_BAR_PX) {
                    originalHeights.putIfAbsent(view, lp.height);
                    lp.height = CoverResourceOverlay.COVER_APP_STATUS_BAR_PX;
                    view.setLayoutParams(lp);
                    ModLog.i(TAG, "clamped placeholder view to " + CoverResourceOverlay.COVER_APP_STATUS_BAR_PX + "px in " + view.getContext().getPackageName());
                }
            }
            if (view instanceof ViewGroup group) {
                int count = group.getChildCount();
                for (int i = 0; i < count; i++) {
                    scanTree(group.getChildAt(i), depth + 1);
                }
            }
        }

        private void restoreAll() {
            for (var entry : new ArrayList<>(originalHeights.entrySet())) {
                View v = entry.getKey();
                Integer orig = entry.getValue();
                if (v != null && orig != null && v.getLayoutParams() != null
                        && v.getLayoutParams().height == CoverResourceOverlay.COVER_APP_STATUS_BAR_PX) {
                    ViewGroup.LayoutParams lp = v.getLayoutParams();
                    lp.height = orig;
                    v.setLayoutParams(lp);
                }
            }
        }

        static boolean isFakeStatusBarPlaceholder(View v) {
            if (v instanceof ViewGroup vg && vg.getChildCount() > 0) return false;
            int id = v.getId();
            if (id == View.NO_ID || id == 0) return false;
            int h = v.getHeight();
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            int lpH = lp != null ? lp.height : -1;
            if ((h <= 0 || h > CoverDisplay.MAX_STATUS_BAR_INSETS_TOP)
                    && (lpH <= 0 || lpH > CoverDisplay.MAX_STATUS_BAR_INSETS_TOP)) {
                return false;
            }
            if (v.getTop() > 4) return false;
            try {
                String entry = v.getResources().getResourceEntryName(id).toLowerCase(Locale.ROOT);
                return FAKE_STATUS_BAR_IDS.contains(entry);
            } catch (Throwable e) {
                return false;
            }
        }
    }

    /**
     * Layout adapter for Meituan ({@code com.sankuai.meituan}):
     * <ul>
     *   <li>Synchronizes the static cached status-bar height in {@code com.meituan.android.pt.homepage.utils.x.a}
     *       across cover/inner screen switches;</li>
     *   <li>Updates {@code MainActivity.G}, {@code MainActivity.F} (top status-bar overlay), and
     *       {@code MainActivity.j0} (tab container top margin);</li>
     *   <li>Collapses the extra status-bar top padding on the home header container
     *       ({@code PullToRefreshView} headers {@code LinearLayout} with negative {@code topMargin}), since its
     *       inner {@code PTLinearLayout} already provides {@code 42px > 30px} top padding on the cover screen;</li>
     *   <li>Clamps top {@link android.widget.Space} views (such as {@code SecondFloor}'s status-bar spacer) and
     *       standard fake status-bar placeholders.</li>
     * </ul>
     */
    static final class MeituanLayout {
        private static final int INITIAL_SCAN_BUDGET = 16;
        private static final int FALLBACK_INNER_STATUS_BAR_PX = 146;
        private static final int COVER_SUBHEADER_TOP_PX = 16;

        private static volatile boolean reflectionResolved;
        private static volatile java.lang.reflect.Field mtUtilsStatusBarField;
        private static volatile java.lang.reflect.Field mainActivityGField;
        private static volatile java.lang.reflect.Field mainActivityFField;
        private static volatile java.lang.reflect.Field mainActivityJ0Field;

        final WeakReference<Activity> activity;
        final WeakHashMap<View, Integer> originalHeights = new WeakHashMap<>();
        final WeakHashMap<View, Integer> originalExtraPaddingTop = new WeakHashMap<>();
        final WeakHashMap<View, Integer> originalSubHeaderPaddingTop = new WeakHashMap<>();
        final WeakHashMap<View, Integer> originalSubHeaderTopMargin = new WeakHashMap<>();
        WeakReference<View> cachedHeaderGroup;
        Integer originalMainActivityG;
        Integer originalJ0TopMargin;
        int lastWidth = -1, lastHeight = -1, initialScans, headerSearchBudget = 40;
        Boolean lastActive;

        MeituanLayout(Activity a) {
            activity = new WeakReference<>(a);
            attachLayoutListener(a, () -> apply(false));
        }

        private static void resolveReflection(Activity a) {
            if (reflectionResolved || a == null) return;
            reflectionResolved = true;
            ClassLoader cl = a.getClassLoader();
            try {
                Class<?> xCls = Class.forName("com.meituan.android.pt.homepage.utils.x", false, cl);
                java.lang.reflect.Field f = xCls.getDeclaredField("a");
                if (f.getType() == int.class && java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    mtUtilsStatusBarField = f;
                }
            } catch (Throwable ignored) {
            }
            try {
                Class<?> mainCls = Class.forName("com.meituan.android.pt.homepage.activity.MainActivity", false, cl);
                try {
                    java.lang.reflect.Field g = mainCls.getDeclaredField("G");
                    if (g.getType() == int.class) {
                        g.setAccessible(true);
                        mainActivityGField = g;
                    }
                } catch (Throwable ignored) {
                }
                try {
                    java.lang.reflect.Field f = mainCls.getDeclaredField("F");
                    if (View.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        mainActivityFField = f;
                    }
                } catch (Throwable ignored) {
                }
                try {
                    java.lang.reflect.Field j0 = mainCls.getDeclaredField("j0");
                    if (View.class.isAssignableFrom(j0.getType())) {
                        j0.setAccessible(true);
                        mainActivityJ0Field = j0;
                    }
                } catch (Throwable ignored) {
                }
            } catch (Throwable ignored) {
            }
        }

        private static int querySystemStatusBarHeight() {
            try {
                android.content.res.Resources sys = android.content.res.Resources.getSystem();
                int id = sys.getIdentifier("status_bar_height", "dimen", "android");
                if (id > 0) {
                    int px = sys.getDimensionPixelSize(id);
                    if (px > CoverResourceOverlay.CORE_GUARD_MAX_PATCHED_PX) return px;
                }
            } catch (Throwable ignored) {
            }
            return FALLBACK_INNER_STATUS_BAR_PX;
        }

        void apply(boolean force) {
            try {
                Activity a = activity.get();
                if (a == null || a.isDestroyed() || a.getWindow() == null) return;
                View root = a.getWindow().getDecorView();
                if (root == null) return;
                resolveReflection(a);
                boolean active = AppCompatibility.isResourcePatchActiveFor(a);
                syncStaticStatusBarCache(active);
                int w = root.getWidth();
                int h = root.getHeight();
                boolean isMain = "com.meituan.android.pt.homepage.activity.MainActivity".equals(a.getClass().getName());
                View hdr = cachedHeaderGroup != null ? cachedHeaderGroup.get() : null;
                boolean headerNeedsCollapse = false;
                boolean headerMissing = false;
                if (active && isMain) {
                    if (hdr != null && hdr.isAttachedToWindow()) {
                        if (hdr.getLayoutParams() instanceof ViewGroup.MarginLayoutParams mlp
                                && mlp.topMargin < -20
                                && hdr.getPaddingTop() + mlp.topMargin > 0) {
                            headerNeedsCollapse = true;
                        }
                    } else if (headerSearchBudget > 0) {
                        headerMissing = true;
                    }
                }
                boolean changed = force
                        || initialScans < INITIAL_SCAN_BUDGET
                        || headerNeedsCollapse
                        || headerMissing
                        || w != lastWidth || h != lastHeight
                        || lastActive == null || lastActive != active;
                if (!changed) return;
                if (initialScans < INITIAL_SCAN_BUDGET) initialScans++;
                if (headerMissing && headerSearchBudget > 0) headerSearchBudget--;
                lastWidth = w;
                lastHeight = h;
                lastActive = active;
                if (active) {
                    applyMainActivityFields(a, true);
                    scanTree(root, 0);
                } else {
                    applyMainActivityFields(a, false);
                    restoreAll();
                }
            } catch (Throwable ignored) {
            }
        }

        private static void syncStaticStatusBarCache(boolean active) {
            java.lang.reflect.Field f = mtUtilsStatusBarField;
            if (f == null) return;
            try {
                int cur = f.getInt(null);
                if (active) {
                    if (cur > CoverResourceOverlay.COVER_APP_STATUS_BAR_PX) {
                        f.setInt(null, CoverResourceOverlay.COVER_APP_STATUS_BAR_PX);
                    }
                } else {
                    if (cur > 0 && cur <= CoverResourceOverlay.COVER_APP_STATUS_BAR_PX) {
                        f.setInt(null, 0);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        private void applyMainActivityFields(Activity a, boolean active) {
            if (!"com.meituan.android.pt.homepage.activity.MainActivity".equals(a.getClass().getName())) return;
            try {
                java.lang.reflect.Field gField = mainActivityGField;
                if (gField != null) {
                    int g = gField.getInt(a);
                    if (active) {
                        if (g > CoverResourceOverlay.COVER_APP_STATUS_BAR_PX) {
                            if (originalMainActivityG == null) originalMainActivityG = g;
                            gField.setInt(a, CoverResourceOverlay.COVER_APP_STATUS_BAR_PX);
                        } else if (g == CoverResourceOverlay.COVER_APP_STATUS_BAR_PX && originalMainActivityG == null) {
                            originalMainActivityG = FALLBACK_INNER_STATUS_BAR_PX;
                        }
                    } else if (g > 0 && g <= CoverResourceOverlay.COVER_APP_STATUS_BAR_PX) {
                        int restoreG = originalMainActivityG != null && originalMainActivityG > CoverResourceOverlay.COVER_APP_STATUS_BAR_PX
                                ? originalMainActivityG
                                : querySystemStatusBarHeight();
                        gField.setInt(a, restoreG);
                    }
                }
            } catch (Throwable ignored) {
            }
            try {
                java.lang.reflect.Field fField = mainActivityFField;
                if (fField != null && fField.get(a) instanceof View fView) {
                    ViewGroup.LayoutParams lp = fView.getLayoutParams();
                    if (lp != null) {
                        if (active) {
                            if (lp.height > CoverResourceOverlay.COVER_APP_STATUS_BAR_PX
                                    && lp.height <= CoverDisplay.MAX_STATUS_BAR_INSETS_TOP) {
                                originalHeights.putIfAbsent(fView, lp.height);
                                lp.height = CoverResourceOverlay.COVER_APP_STATUS_BAR_PX;
                                fView.setLayoutParams(lp);
                            } else if (lp.height == CoverResourceOverlay.COVER_APP_STATUS_BAR_PX) {
                                originalHeights.putIfAbsent(fView, FALLBACK_INNER_STATUS_BAR_PX);
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            try {
                java.lang.reflect.Field j0Field = mainActivityJ0Field;
                if (j0Field != null && j0Field.get(a) instanceof View j0View
                        && j0View.getLayoutParams() instanceof ViewGroup.MarginLayoutParams mlp) {
                    if (active) {
                        if (mlp.topMargin > CoverResourceOverlay.COVER_APP_STATUS_BAR_PX
                                && mlp.topMargin <= CoverDisplay.MAX_STATUS_BAR_INSETS_TOP) {
                            if (originalJ0TopMargin == null) originalJ0TopMargin = mlp.topMargin;
                            mlp.topMargin = CoverResourceOverlay.COVER_APP_STATUS_BAR_PX;
                            j0View.setLayoutParams(mlp);
                        } else if (mlp.topMargin == CoverResourceOverlay.COVER_APP_STATUS_BAR_PX && originalJ0TopMargin == null) {
                            originalJ0TopMargin = FALLBACK_INNER_STATUS_BAR_PX;
                        }
                    } else if (mlp.topMargin > 0 && mlp.topMargin <= CoverResourceOverlay.COVER_APP_STATUS_BAR_PX) {
                        mlp.topMargin = originalJ0TopMargin != null && originalJ0TopMargin > CoverResourceOverlay.COVER_APP_STATUS_BAR_PX
                                ? originalJ0TopMargin
                                : querySystemStatusBarHeight();
                        j0View.setLayoutParams(mlp);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        private void scanTree(View view, int depth) {
            if (view == null || depth > 16) return;
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp != null && (PlaceholderCollapse.isFakeStatusBarPlaceholder(view)
                    || (view instanceof android.widget.Space && view.getTop() <= 4))) {
                if (lp.height > CoverResourceOverlay.COVER_APP_STATUS_BAR_PX
                        && lp.height <= CoverDisplay.MAX_STATUS_BAR_INSETS_TOP) {
                    originalHeights.putIfAbsent(view, lp.height);
                    lp.height = CoverResourceOverlay.COVER_APP_STATUS_BAR_PX;
                    view.setLayoutParams(lp);
                } else if (lp.height == CoverResourceOverlay.COVER_APP_STATUS_BAR_PX) {
                    originalHeights.putIfAbsent(view, FALLBACK_INNER_STATUS_BAR_PX);
                }
            }
            if (view instanceof ViewGroup group) {
                if (lp instanceof ViewGroup.MarginLayoutParams mlp && mlp.topMargin < -20) {
                    int extraTop = group.getPaddingTop() + mlp.topMargin;
                    if (extraTop >= 0 && extraTop <= CoverDisplay.MAX_STATUS_BAR_INSETS_TOP) {
                        cachedHeaderGroup = new WeakReference<>(group);
                        headerSearchBudget = 24;
                        if (extraTop > 0) {
                            originalExtraPaddingTop.putIfAbsent(group,
                                    extraTop > CoverResourceOverlay.COVER_APP_STATUS_BAR_PX ? extraTop : FALLBACK_INNER_STATUS_BAR_PX);
                            group.setPadding(
                                    group.getPaddingLeft(),
                                    -mlp.topMargin,
                                    group.getPaddingRight(),
                                    group.getPaddingBottom());
                            ModLog.i(TAG, "collapsed Meituan header extraTop=" + extraTop + " -> 0");
                        }
                        compactHeaderSubtree(group, 0);
                    }
                }
                int count = group.getChildCount();
                for (int i = 0; i < count; i++) {
                    scanTree(group.getChildAt(i), depth + 1);
                }
            }
        }

        private void compactHeaderSubtree(ViewGroup parent, int subDepth) {
            if (parent == null || subDepth > 3) return;
            int padTop = parent.getPaddingTop();
            if (subDepth >= 1 && padTop >= 34 && padTop <= 64) {
                originalSubHeaderPaddingTop.putIfAbsent(parent, padTop);
                parent.setPadding(
                        parent.getPaddingLeft(),
                        COVER_SUBHEADER_TOP_PX,
                        parent.getPaddingRight(),
                        parent.getPaddingBottom());
            }
            int count = parent.getChildCount();
            for (int i = 0; i < count; i++) {
                View child = parent.getChildAt(i);
                if (child == null) continue;
                if (i == 0 && child.getLayoutParams() instanceof ViewGroup.MarginLayoutParams cMlp
                        && cMlp.topMargin >= 34 && cMlp.topMargin <= 64) {
                    originalSubHeaderTopMargin.putIfAbsent(child, cMlp.topMargin);
                    cMlp.topMargin = COVER_SUBHEADER_TOP_PX;
                    child.setLayoutParams(cMlp);
                }
                if (child instanceof ViewGroup childGroup) {
                    compactHeaderSubtree(childGroup, subDepth + 1);
                }
            }
        }

        private void restoreAll() {
            int innerSb = querySystemStatusBarHeight();
            for (var entry : new ArrayList<>(originalHeights.entrySet())) {
                View v = entry.getKey();
                Integer orig = entry.getValue();
                if (v != null && v.getLayoutParams() != null
                        && v.getLayoutParams().height == CoverResourceOverlay.COVER_APP_STATUS_BAR_PX) {
                    ViewGroup.LayoutParams lp = v.getLayoutParams();
                    lp.height = (orig != null && orig > CoverResourceOverlay.COVER_APP_STATUS_BAR_PX) ? orig : innerSb;
                    v.setLayoutParams(lp);
                }
            }
            for (var entry : new ArrayList<>(originalExtraPaddingTop.entrySet())) {
                View v = entry.getKey();
                Integer origExtra = entry.getValue();
                if (v != null && v.getLayoutParams() instanceof ViewGroup.MarginLayoutParams mlp
                        && mlp.topMargin < -20
                        && v.getPaddingTop() == -mlp.topMargin) {
                    int restoreExtra = (origExtra != null && origExtra > CoverResourceOverlay.COVER_APP_STATUS_BAR_PX)
                            ? origExtra
                            : innerSb;
                    v.setPadding(
                            v.getPaddingLeft(),
                            -mlp.topMargin + restoreExtra,
                            v.getPaddingRight(),
                            v.getPaddingBottom());
                }
            }
            for (var entry : new ArrayList<>(originalSubHeaderPaddingTop.entrySet())) {
                View v = entry.getKey();
                Integer origPad = entry.getValue();
                if (v != null && origPad != null && v.getPaddingTop() == COVER_SUBHEADER_TOP_PX) {
                    v.setPadding(v.getPaddingLeft(), origPad, v.getPaddingRight(), v.getPaddingBottom());
                }
            }
            for (var entry : new ArrayList<>(originalSubHeaderTopMargin.entrySet())) {
                View v = entry.getKey();
                Integer origMargin = entry.getValue();
                if (v != null && origMargin != null
                        && v.getLayoutParams() instanceof ViewGroup.MarginLayoutParams cMlp
                        && cMlp.topMargin == COVER_SUBHEADER_TOP_PX) {
                    cMlp.topMargin = origMargin;
                    v.setLayoutParams(cMlp);
                }
            }
        }
    }

    /**
     * Layout adapter for Xiaomi Mall ({@code com.xiaomi.shop}):
     * <ul>
     *   <li>Synchronizes host status-bar height caches ({@code ScreenInfo}, {@code HomeUtils.sStatusBarHeight},
     *       {@code WindowUtils.sStatusBarHeight}, {@code Utils.sStatusBarHeight}, and {@code BaseActivity});</li>
     *   <li>Compacts {@code HomeTopBarView} ({@code statusBarHeight}, {@code mViewHeight},
     *       {@code HomeViewHeightUtil.HOME_TOPBAR_VIEWHEIGHT}, {@code mRootView}, child {@code topMargin}s, and
     *       {@code mLlAddress}) plus sibling {@code Home3Fragment} views ({@code searchRoot} and
     *       {@code outSearchContainer}) on the cover screen and restores them on the inner screen;</li>
     *   <li>Compacts top title-bar / user-header containers ({@code UserCentralFragment}, {@code XMTitleBar},
     *       {@code NotchPhoneTitlebarHandler}) and collapses fake status-bar placeholders.</li>
     * </ul>
     */
    static final class XiaomiShopLayout {
        private static final int INITIAL_SCAN_BUDGET = 16;
        private static final int FALLBACK_INNER_STATUS_BAR_PX = 146;
        private static final int COVER_SHOP_TOP_PX = 6;
        private static final String HOME_TOP_BAR_CLASS = "com.xiaomi.shop.plugin.homepage2.view.HomeTopBarView";

        private static volatile boolean hostReflectionResolved;
        private static volatile Object screenInfoInstance;
        private static volatile java.lang.reflect.Method screenInfoSetStatusBarMethod;
        private static volatile java.lang.reflect.Field homeUtilsStatusBarField;
        private static volatile java.lang.reflect.Field homeViewHeightFieldHost;
        private static volatile java.lang.reflect.Field windowUtilsStatusBarField;
        private static volatile java.lang.reflect.Field jrUtilsStatusBarField;
        private static volatile java.lang.reflect.Field baseActivityStatusBarField;
        private static volatile java.lang.reflect.Field baseActivityTintMgrField;
        private static volatile java.lang.reflect.Field tintMgrConfigField;
        private static volatile java.lang.reflect.Field tintMgrViewField;
        private static volatile java.lang.reflect.Field tintConfigStatusBarField;

        private static volatile Class<?> resolvedTopBarClass;
        private static volatile java.lang.reflect.Field topBarStatusBarField;
        private static volatile java.lang.reflect.Field topBarViewHeightField;
        private static volatile java.lang.reflect.Field topBarRootViewField;
        private static volatile java.lang.reflect.Field topBarLeftViewField;
        private static volatile java.lang.reflect.Field topBarTabLayoutField;
        private static volatile java.lang.reflect.Field topBarRightViewField;
        private static volatile java.lang.reflect.Field topBarMsgLayoutField;
        private static volatile java.lang.reflect.Field topBarSearchParentField;
        private static volatile java.lang.reflect.Field topBarLlAddressField;
        private static volatile java.lang.reflect.Field homeViewHeightFieldPlugin;

        final WeakReference<Activity> activity;
        final WeakHashMap<View, Integer> originalHeights = new WeakHashMap<>();
        final WeakHashMap<View, Integer> originalHeaderPaddingTop = new WeakHashMap<>();
        final WeakHashMap<View, Integer> originalHeaderBaseHeight = new WeakHashMap<>();
        WeakReference<View> cachedTopBar;
        WeakReference<ViewGroup> cachedFloorRoot;
        Integer originalInnerStatusBarPx;
        int lastWidth = -1, lastHeight = -1, initialScans, topBarSearchBudget = 40;
        Boolean lastActive;

        XiaomiShopLayout(Activity a) {
            activity = new WeakReference<>(a);
            attachLayoutListener(a, () -> apply(false));
        }

        private static java.lang.reflect.Field findField(Class<?> cls, String name, Class<?> expectedType) {
            try {
                java.lang.reflect.Field f = cls.getDeclaredField(name);
                if (expectedType == null || expectedType.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f;
                }
            } catch (Throwable ignored) {
            }
            return null;
        }

        private static void resolveHostReflection(Activity a) {
            if (hostReflectionResolved || a == null) return;
            hostReflectionResolved = true;
            ClassLoader cl = a.getClassLoader();
            try {
                Class<?> siCls = Class.forName("com.xiaomi.shop2.util.ScreenInfo", false, cl);
                java.lang.reflect.Method getInst = siCls.getDeclaredMethod("getInstance");
                getInst.setAccessible(true);
                screenInfoInstance = getInst.invoke(null);
                java.lang.reflect.Method setSb = siCls.getDeclaredMethod("setStatusBarHeight", int.class);
                setSb.setAccessible(true);
                screenInfoSetStatusBarMethod = setSb;
            } catch (Throwable ignored) {
            }
            try {
                Class<?> huCls = Class.forName("com.xiaomi.shop.home.view.type.utils.HomeUtils", false, cl);
                homeUtilsStatusBarField = findField(huCls, "sStatusBarHeight", int.class);
            } catch (Throwable ignored) {
            }
            try {
                Class<?> hvCls = Class.forName("com.xiaomi.shop.home.view.type.utils.HomeViewHeightUtil", false, cl);
                homeViewHeightFieldHost = findField(hvCls, "HOME_TOPBAR_VIEWHEIGHT", int.class);
            } catch (Throwable ignored) {
            }
            try {
                Class<?> wuCls = Class.forName("com.xiaomi.retail.mirnsdk2.util.WindowUtils", false, cl);
                windowUtilsStatusBarField = findField(wuCls, "sStatusBarHeight", int.class);
            } catch (Throwable ignored) {
            }
            try {
                Class<?> juCls = Class.forName("com.xiaomi.jr.common.utils.Utils", false, cl);
                jrUtilsStatusBarField = findField(juCls, "sStatusBarHeight", int.class);
            } catch (Throwable ignored) {
            }
            try {
                Class<?> baseCls = Class.forName("com.xiaomi.shop2.activity.BaseActivity", false, cl);
                baseActivityStatusBarField = findField(baseCls, "statusBarHeight", int.class);
                baseActivityTintMgrField = findField(baseCls, "mSystemBarTintManager", null);
                if (baseActivityTintMgrField != null) {
                    Class<?> tmCls = baseActivityTintMgrField.getType();
                    tintMgrConfigField = findField(tmCls, "mConfig", null);
                    tintMgrViewField = findField(tmCls, "mStatusBarTintView", View.class);
                    if (tintMgrConfigField != null) {
                        tintConfigStatusBarField = findField(tintMgrConfigField.getType(), "mStatusBarHeight", int.class);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        private static void resolveTopBarReflection(Class<?> topBarCls) {
            if (topBarCls == null || topBarCls == resolvedTopBarClass) return;
            resolvedTopBarClass = topBarCls;
            topBarStatusBarField = findField(topBarCls, "statusBarHeight", int.class);
            topBarViewHeightField = findField(topBarCls, "mViewHeight", int.class);
            topBarRootViewField = findField(topBarCls, "mRootView", View.class);
            topBarLeftViewField = findField(topBarCls, "mLeftBarView", View.class);
            topBarTabLayoutField = findField(topBarCls, "mTabLayout", View.class);
            topBarRightViewField = findField(topBarCls, "mRightBarView", View.class);
            topBarMsgLayoutField = findField(topBarCls, "mMsgLayout", View.class);
            topBarSearchParentField = findField(topBarCls, "mSearchContainerParent", View.class);
            topBarLlAddressField = findField(topBarCls, "mLlAddress", View.class);
            try {
                Class<?> hvCls = Class.forName(
                        "com.xiaomi.shop.home.view.type.utils.HomeViewHeightUtil",
                        false,
                        topBarCls.getClassLoader());
                homeViewHeightFieldPlugin = findField(hvCls, "HOME_TOPBAR_VIEWHEIGHT", int.class);
            } catch (Throwable ignored) {
            }
        }

        private static int querySystemStatusBarHeight() {
            try {
                android.content.res.Resources sys = android.content.res.Resources.getSystem();
                int id = sys.getIdentifier("status_bar_height", "dimen", "android");
                if (id > 0) {
                    int px = sys.getDimensionPixelSize(id);
                    if (px > CoverResourceOverlay.CORE_GUARD_MAX_PATCHED_PX) return px;
                }
            } catch (Throwable ignored) {
            }
            return FALLBACK_INNER_STATUS_BAR_PX;
        }

        private static int dp(View v, int dpVal) {
            float d = v.getResources().getDisplayMetrics().density;
            return Math.round(dpVal * (d > 0f ? d : 3.0f));
        }

        void apply(boolean force) {
            try {
                Activity a = activity.get();
                if (a == null || a.isDestroyed() || a.getWindow() == null) return;
                View root = a.getWindow().getDecorView();
                if (root == null) return;
                resolveHostReflection(a);
                boolean active = AppCompatibility.isResourcePatchActiveFor(a);
                syncHostStatusBarCaches(a, active);
                int w = root.getWidth();
                int h = root.getHeight();
                boolean isMain = "com.xiaomi.shop2.activity.MainActivity".equals(a.getClass().getName());
                View topBar = cachedTopBar != null ? cachedTopBar.get() : null;
                ViewGroup floorRoot = cachedFloorRoot != null ? cachedFloorRoot.get() : null;
                boolean topBarNeedsCompact = false;
                boolean topBarMissing = false;
                if (active && isMain) {
                    if (topBar != null && topBar.isAttachedToWindow()) {
                        topBarNeedsCompact = doesTopBarNeedCompact(topBar);
                    } else if (topBarSearchBudget > 0) {
                        topBarMissing = true;
                    }
                    if (floorRoot == null || !floorRoot.isAttachedToWindow()) {
                        if (topBarSearchBudget > 0) topBarMissing = true;
                    }
                }
                boolean changed = force
                        || initialScans < INITIAL_SCAN_BUDGET
                        || topBarNeedsCompact
                        || topBarMissing
                        || w != lastWidth || h != lastHeight
                        || lastActive == null || lastActive != active;
                if (!changed) return;
                if (initialScans < INITIAL_SCAN_BUDGET) initialScans++;
                if (topBarMissing && topBarSearchBudget > 0) topBarSearchBudget--;
                lastWidth = w;
                lastHeight = h;
                lastActive = active;
                if (active) {
                    scanTree(root, 0);
                } else {
                    restoreAll();
                }
            } catch (Throwable ignored) {
            }
        }

        private boolean doesTopBarNeedCompact(View topBar) {
            try {
                resolveTopBarReflection(topBar.getClass());
                if (topBarStatusBarField != null && topBarStatusBarField.getInt(topBar) != COVER_SHOP_TOP_PX) {
                    return true;
                }
                if (topBarRootViewField != null && topBarRootViewField.get(topBar) instanceof View rv) {
                    ViewGroup.LayoutParams lp = rv.getLayoutParams();
                    int expected = dp(topBar, 40) + COVER_SHOP_TOP_PX;
                    if (lp != null && lp.height > expected) return true;
                }
            } catch (Throwable ignored) {
            }
            return false;
        }

        private void syncHostStatusBarCaches(Activity a, boolean active) {
            int innerSb = originalInnerStatusBarPx != null && originalInnerStatusBarPx > CoverResourceOverlay.COVER_APP_STATUS_BAR_PX
                    ? originalInnerStatusBarPx
                    : querySystemStatusBarHeight();
            int targetCacheSb = active ? CoverResourceOverlay.COVER_APP_STATUS_BAR_PX : innerSb;
            try {
                if (screenInfoInstance != null && screenInfoSetStatusBarMethod != null) {
                    screenInfoSetStatusBarMethod.invoke(screenInfoInstance, targetCacheSb);
                }
            } catch (Throwable ignored) {
            }
            try {
                if (homeUtilsStatusBarField != null) {
                    homeUtilsStatusBarField.setInt(null, active ? CoverResourceOverlay.COVER_APP_STATUS_BAR_PX : -1);
                }
            } catch (Throwable ignored) {
            }
            try {
                if (windowUtilsStatusBarField != null) {
                    windowUtilsStatusBarField.setInt(null, active ? CoverResourceOverlay.COVER_APP_STATUS_BAR_PX : -1);
                }
            } catch (Throwable ignored) {
            }
            try {
                if (jrUtilsStatusBarField != null) {
                    jrUtilsStatusBarField.setInt(null, active ? CoverResourceOverlay.COVER_APP_STATUS_BAR_PX : 0);
                }
            } catch (Throwable ignored) {
            }
            try {
                if (baseActivityStatusBarField != null && baseActivityStatusBarField.getDeclaringClass().isInstance(a)) {
                    int cur = baseActivityStatusBarField.getInt(a);
                    if (cur > CoverResourceOverlay.COVER_APP_STATUS_BAR_PX && originalInnerStatusBarPx == null) {
                        originalInnerStatusBarPx = cur;
                    }
                    baseActivityStatusBarField.setInt(a, targetCacheSb);
                }
                if (baseActivityTintMgrField != null && baseActivityTintMgrField.getDeclaringClass().isInstance(a)) {
                    Object tm = baseActivityTintMgrField.get(a);
                    if (tm != null) {
                        if (tintMgrConfigField != null && tintConfigStatusBarField != null) {
                            Object cfg = tintMgrConfigField.get(tm);
                            if (cfg != null) tintConfigStatusBarField.setInt(cfg, targetCacheSb);
                        }
                        if (tintMgrViewField != null && tintMgrViewField.get(tm) instanceof View tintView) {
                            ViewGroup.LayoutParams lp = tintView.getLayoutParams();
                            if (lp != null && lp.height > 0 && lp.height != targetCacheSb) {
                                lp.height = targetCacheSb;
                                tintView.setLayoutParams(lp);
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        private void scanTree(View view, int depth) {
            if (view == null || depth > 18) return;
            if (HOME_TOP_BAR_CLASS.equals(view.getClass().getName())) {
                cachedTopBar = new WeakReference<>(view);
                topBarSearchBudget = 24;
                adaptHomeTopBar(view, true);
                return;
            }
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp != null && PlaceholderCollapse.isFakeStatusBarPlaceholder(view)) {
                if (lp.height > CoverResourceOverlay.COVER_APP_STATUS_BAR_PX
                        && lp.height <= CoverDisplay.MAX_STATUS_BAR_INSETS_TOP) {
                    originalHeights.putIfAbsent(view, lp.height);
                    lp.height = CoverResourceOverlay.COVER_APP_STATUS_BAR_PX;
                    view.setLayoutParams(lp);
                } else if (lp.height == CoverResourceOverlay.COVER_APP_STATUS_BAR_PX) {
                    originalHeights.putIfAbsent(view, FALLBACK_INNER_STATUS_BAR_PX);
                }
            }
            if (view instanceof ViewGroup group) {
                if ("rl_floor_root".equals(resourceEntryName(group))) {
                    cachedFloorRoot = new WeakReference<>(group);
                    adaptFloorRoot(group, true);
                } else {
                    adaptTopHeaderContainer(group);
                }
                int count = group.getChildCount();
                for (int i = 0; i < count; i++) {
                    scanTree(group.getChildAt(i), depth + 1);
                }
            }
        }

        private void adaptTopHeaderContainer(ViewGroup group) {
            if (group.getTop() > 4) return;
            String entry = resourceEntryName(group);
            if ("outSearchContainer".equals(entry) || "ll_head_address".equals(entry)) return;
            int padTop = group.getPaddingTop();
            boolean isStatusBarPad = (padTop == CoverResourceOverlay.COVER_APP_STATUS_BAR_PX)
                    || (padTop >= 110 && padTop <= CoverDisplay.MAX_STATUS_BAR_INSETS_TOP);
            if (!isStatusBarPad) return;
            ViewGroup.LayoutParams lp = group.getLayoutParams();
            if (lp == null || lp.height <= padTop || lp.height > padTop + dp(group, 140)) return;
            if (padTop > CoverResourceOverlay.COVER_APP_STATUS_BAR_PX && originalInnerStatusBarPx == null) {
                originalInnerStatusBarPx = padTop;
            }
            int baseH = lp.height - padTop;
            originalHeaderPaddingTop.putIfAbsent(group,
                    padTop > CoverResourceOverlay.COVER_APP_STATUS_BAR_PX ? padTop : FALLBACK_INNER_STATUS_BAR_PX);
            originalHeaderBaseHeight.putIfAbsent(group, baseH);
            group.setPadding(group.getPaddingLeft(), COVER_SHOP_TOP_PX, group.getPaddingRight(), group.getPaddingBottom());
            lp.height = baseH + COVER_SHOP_TOP_PX;
            group.setLayoutParams(lp);
            ModLog.i(TAG, "compacted XiaomiShop header " + entry + " padTop=" + padTop + " -> " + COVER_SHOP_TOP_PX);
        }

        private void adaptHomeTopBar(View topBar, boolean active) {
            try {
                resolveTopBarReflection(topBar.getClass());
                int innerSb = originalInnerStatusBarPx != null && originalInnerStatusBarPx > CoverResourceOverlay.COVER_APP_STATUS_BAR_PX
                        ? originalInnerStatusBarPx
                        : querySystemStatusBarHeight();
                int targetSb = active ? COVER_SHOP_TOP_PX : innerSb;
                int curSb = topBarStatusBarField != null ? topBarStatusBarField.getInt(topBar) : -1;
                if (curSb > CoverResourceOverlay.COVER_APP_STATUS_BAR_PX && originalInnerStatusBarPx == null) {
                    originalInnerStatusBarPx = curSb;
                }
                int curViewH = topBarViewHeightField != null ? topBarViewHeightField.getInt(topBar) : -1;
                int baseBarH = (curViewH > curSb && curSb >= 0) ? (curViewH - curSb) : dp(topBar, 40);
                int targetViewH = baseBarH + targetSb;

                if (topBarStatusBarField != null && curSb != targetSb) {
                    topBarStatusBarField.setInt(topBar, targetSb);
                }
                if (topBarViewHeightField != null && curViewH != targetViewH) {
                    topBarViewHeightField.setInt(topBar, targetViewH);
                }
                try {
                    if (homeViewHeightFieldPlugin != null) homeViewHeightFieldPlugin.setInt(null, targetViewH);
                } catch (Throwable ignored) {
                }
                try {
                    if (homeViewHeightFieldHost != null) homeViewHeightFieldHost.setInt(null, targetViewH);
                } catch (Throwable ignored) {
                }

                if (topBarRootViewField != null && topBarRootViewField.get(topBar) instanceof View rootView) {
                    ViewGroup.LayoutParams rlp = rootView.getLayoutParams();
                    if (rlp != null && rlp.height != targetViewH) {
                        rlp.height = targetViewH;
                        rootView.setLayoutParams(rlp);
                    }
                }
                ViewGroup.LayoutParams tlp = topBar.getLayoutParams();
                if (tlp != null && tlp.height > 0 && tlp.height != targetViewH) {
                    tlp.height = targetViewH;
                    topBar.setLayoutParams(tlp);
                }

                updateChildTopMargin(topBarLeftViewField, topBar, targetSb);
                updateChildTopMargin(topBarTabLayoutField, topBar, targetSb);
                updateChildTopMargin(topBarRightViewField, topBar, targetSb);
                updateChildTopMargin(topBarMsgLayoutField, topBar, targetSb);
                updateChildTopMargin(topBarSearchParentField, topBar, targetSb);

                if (topBarLlAddressField != null && topBarLlAddressField.get(topBar) instanceof View llAddr) {
                    if (llAddr.getPaddingTop() != targetSb) {
                        llAddr.setPadding(llAddr.getPaddingLeft(), targetSb, llAddr.getPaddingRight(), llAddr.getPaddingBottom());
                    }
                    ViewGroup.LayoutParams alp = llAddr.getLayoutParams();
                    int addrTargetH = dp(topBar, 24) + targetSb;
                    if (alp != null && alp.height > 0 && alp.height != addrTargetH) {
                        alp.height = addrTargetH;
                        llAddr.setLayoutParams(alp);
                    }
                }

                if (topBar.getParent() instanceof ViewGroup parentGroup) {
                    adaptFloorRoot(parentGroup, active);
                }
                if (curSb != targetSb) {
                    ModLog.i(TAG, "adjusted XiaomiShop HomeTopBarView sb=" + curSb + " -> " + targetSb + " viewH=" + targetViewH);
                }
            } catch (Throwable ignored) {
            }
        }

        private void adaptFloorRoot(ViewGroup floorRoot, boolean active) {
            if (floorRoot == null) return;
            int innerSb = originalInnerStatusBarPx != null && originalInnerStatusBarPx > CoverResourceOverlay.COVER_APP_STATUS_BAR_PX
                    ? originalInnerStatusBarPx
                    : querySystemStatusBarHeight();
            int targetSb = active ? COVER_SHOP_TOP_PX : innerSb;
            int count = floorRoot.getChildCount();
            for (int i = 0; i < count; i++) {
                View child = floorRoot.getChildAt(i);
                if (child == null) continue;
                String entry = resourceEntryName(child);
                if ("searchRoot".equals(entry)
                        && child.getLayoutParams() instanceof ViewGroup.MarginLayoutParams smlp) {
                    int searchBarH = smlp.height > 0 ? smlp.height : dp(child, 40);
                    int targetTopMargin = searchBarH + targetSb;
                    if (smlp.topMargin != targetTopMargin) {
                        int oldSb = smlp.topMargin - searchBarH;
                        if (oldSb > CoverResourceOverlay.COVER_APP_STATUS_BAR_PX && originalInnerStatusBarPx == null) {
                            originalInnerStatusBarPx = oldSb;
                        }
                        smlp.topMargin = targetTopMargin;
                        child.setLayoutParams(smlp);
                        ModLog.i(TAG, "adjusted XiaomiShop searchRoot topMargin -> " + targetTopMargin);
                    }
                } else if ("outSearchContainer".equals(entry)) {
                    int targetPadTop = dp(child, 24) + targetSb;
                    if (child.getPaddingTop() != targetPadTop) {
                        child.setPadding(
                                child.getPaddingLeft(),
                                targetPadTop,
                                child.getPaddingRight(),
                                child.getPaddingBottom());
                    }
                }
            }
        }

        private static void updateChildTopMargin(java.lang.reflect.Field field, Object owner, int targetTopMargin) {
            if (field == null) return;
            try {
                if (field.get(owner) instanceof View v
                        && v.getLayoutParams() instanceof ViewGroup.MarginLayoutParams mlp
                        && mlp.topMargin != targetTopMargin) {
                    mlp.topMargin = targetTopMargin;
                    v.setLayoutParams(mlp);
                }
            } catch (Throwable ignored) {
            }
        }

        private static String resourceEntryName(View v) {
            int id = v.getId();
            if (id == View.NO_ID || id == 0) return "";
            try {
                return v.getResources().getResourceEntryName(id);
            } catch (Throwable e) {
                return "";
            }
        }

        private void restoreAll() {
            int innerSb = originalInnerStatusBarPx != null && originalInnerStatusBarPx > CoverResourceOverlay.COVER_APP_STATUS_BAR_PX
                    ? originalInnerStatusBarPx
                    : querySystemStatusBarHeight();
            View topBar = cachedTopBar != null ? cachedTopBar.get() : null;
            if (topBar != null) {
                adaptHomeTopBar(topBar, false);
            }
            ViewGroup floorRoot = cachedFloorRoot != null ? cachedFloorRoot.get() : null;
            if (floorRoot != null) {
                adaptFloorRoot(floorRoot, false);
            }
            for (var entry : new ArrayList<>(originalHeights.entrySet())) {
                View v = entry.getKey();
                Integer orig = entry.getValue();
                if (v != null && v.getLayoutParams() != null
                        && v.getLayoutParams().height == CoverResourceOverlay.COVER_APP_STATUS_BAR_PX) {
                    ViewGroup.LayoutParams lp = v.getLayoutParams();
                    lp.height = (orig != null && orig > CoverResourceOverlay.COVER_APP_STATUS_BAR_PX) ? orig : innerSb;
                    v.setLayoutParams(lp);
                }
            }
            for (var entry : new ArrayList<>(originalHeaderPaddingTop.entrySet())) {
                View v = entry.getKey();
                Integer origPad = entry.getValue();
                Integer baseH = originalHeaderBaseHeight.get(v);
                if (v != null && v.getPaddingTop() == COVER_SHOP_TOP_PX) {
                    int restorePad = (origPad != null && origPad > CoverResourceOverlay.COVER_APP_STATUS_BAR_PX) ? origPad : innerSb;
                    v.setPadding(v.getPaddingLeft(), restorePad, v.getPaddingRight(), v.getPaddingBottom());
                    ViewGroup.LayoutParams lp = v.getLayoutParams();
                    if (lp != null && baseH != null && baseH > 0) {
                        lp.height = baseH + restorePad;
                        v.setLayoutParams(lp);
                    }
                }
            }
        }
    }

    /**
     * Xiaomi Fitness (com.mi.health) cover-screen top blank space adaptation:
     * 1. Native main tabs ("健康" HealthV2Fragment, "设备" DeviceTabParentFragmentV4) inflate an inner
     *    ConstraintLayout with hardcoded android:paddingTop="35dp" (114px) plus HomeHeaderTabTitleView whose
     *    internal title_list RecyclerView has vertical margins of 20dp (65px), creating 55dp (179px) of top
     *    blank space above the tab title and a 383px total header height. On the cover screen we compact the
     *    parent paddingTop to 4dp (13px) and title_list vertical margins to 2dp (7px), shifting the header up
     *    by 49dp (159px) and shrinking total header height from 383px to 166px.
     * 2. React Native main tabs ("运动" SportTabV4Fragment, "我的" MineFragmentV4) attach a YRNCRootView
     *    inside #main_fl_content (FrameLayout) whose JS bundle positions the tab title at y=179..318 (55dp).
     *    Setting topMargin = -49dp (-159px) on that YRNCRootView aligns the RN tab headers to the exact same
     *    y=20..159 position while expanding the RN root height to keep the bottom flush.
     */
    static final class MiHealthLayout {
        private static final int COVER_PARENT_PAD_TOP_DP = 4;
        private static final int COVER_TITLE_LIST_MARGIN_V_DP = 2;
        private static final int COVER_RN_SHIFT_UP_DP = 49;

        private final WeakReference<Activity> activity;
        private final WeakHashMap<View, Integer> originalParentPaddingTop = new WeakHashMap<>();
        private final WeakHashMap<View, int[]> originalTitleListMargins = new WeakHashMap<>();
        private final WeakHashMap<View, Integer> originalRnTopMargin = new WeakHashMap<>();
        private boolean updating;
        private Boolean lastActive;

        MiHealthLayout(Activity a) {
            activity = new WeakReference<>(a);
            attachLayoutListener(a, () -> apply(false));
        }

        void apply(boolean force) {
            if (updating) return;
            Activity a = activity.get();
            if (a == null || a.isDestroyed() || a.getWindow() == null) return;

            PlaceholderCollapse collapse = placeholders.get(a);
            if (collapse == null) {
                collapse = new PlaceholderCollapse(a);
                placeholders.put(a, collapse);
            }
            collapse.apply(force);

            boolean active = AppCompatibility.isResourcePatchActiveFor(a);
            updating = true;
            try {
                if (!active) {
                    if (force || !Boolean.FALSE.equals(lastActive)) {
                        restoreAll();
                        lastActive = false;
                    }
                    return;
                }
                lastActive = true;
                View decor = a.getWindow().getDecorView();
                if (decor instanceof ViewGroup vg) {
                    scan(a, vg, 0);
                }
            } catch (Throwable t) {
                Log.w(TAG, "mi health layout adaptation failed", t);
            } finally {
                updating = false;
            }
        }

        private void scan(Activity a, ViewGroup group, int depth) {
            if (depth > 18) return;
            for (int i = 0, n = group.getChildCount(); i < n; i++) {
                View child = group.getChildAt(i);
                if (child == null) continue;
                String cls = child.getClass().getName();
                if (cls.endsWith(".HomeHeaderTabTitleView")) {
                    adaptNativeHeader(a, child);
                    continue;
                }
                if (cls.endsWith(".YRNCRootView")) {
                    if ("main_fl_content".equals(resourceEntryName(group))) {
                        adaptRnRootView(a, child);
                    }
                    continue;
                }
                if (child instanceof ViewGroup childGroup) {
                    scan(a, childGroup, depth + 1);
                }
            }
        }

        private void adaptNativeHeader(Activity a, View headerView) {
            if (headerView.getParent() instanceof View parentView) {
                int targetPadTop = dp(a, COVER_PARENT_PAD_TOP_DP);
                int padTop = parentView.getPaddingTop();
                if (padTop > targetPadTop + dp(a, 8)) {
                    originalParentPaddingTop.putIfAbsent(parentView, padTop);
                }
                if (originalParentPaddingTop.containsKey(parentView) && padTop != targetPadTop) {
                    parentView.setPadding(
                            parentView.getPaddingLeft(),
                            targetPadTop,
                            parentView.getPaddingRight(),
                            parentView.getPaddingBottom());
                }
            }
            View titleList = findTitleList(headerView);
            if (titleList != null && titleList.getLayoutParams() instanceof ViewGroup.MarginLayoutParams mlp) {
                int targetMargin = dp(a, COVER_TITLE_LIST_MARGIN_V_DP);
                if (mlp.topMargin > targetMargin + dp(a, 6)) {
                    originalTitleListMargins.putIfAbsent(titleList, new int[]{mlp.topMargin, mlp.bottomMargin});
                }
                if (originalTitleListMargins.containsKey(titleList)
                        && (mlp.topMargin != targetMargin || mlp.bottomMargin != targetMargin)) {
                    mlp.topMargin = targetMargin;
                    mlp.bottomMargin = targetMargin;
                    titleList.setLayoutParams(mlp);
                }
            }
        }

        private static View findTitleList(View root) {
            if ("title_list".equals(resourceEntryName(root))) {
                return root;
            }
            if (root instanceof ViewGroup vg) {
                for (int i = 0, n = vg.getChildCount(); i < n; i++) {
                    View found = findTitleList(vg.getChildAt(i));
                    if (found != null) return found;
                }
            }
            return null;
        }

        private void adaptRnRootView(Activity a, View rnRoot) {
            if (rnRoot.getLayoutParams() instanceof ViewGroup.MarginLayoutParams mlp) {
                int targetTopMargin = -dp(a, COVER_RN_SHIFT_UP_DP);
                if (mlp.topMargin != targetTopMargin) {
                    originalRnTopMargin.putIfAbsent(rnRoot, mlp.topMargin);
                    mlp.topMargin = targetTopMargin;
                    rnRoot.setLayoutParams(mlp);
                }
            }
        }

        private void restoreAll() {
            for (var entry : new ArrayList<>(originalParentPaddingTop.entrySet())) {
                View v = entry.getKey();
                Integer origPad = entry.getValue();
                if (v != null && origPad != null && v.getPaddingTop() != origPad) {
                    v.setPadding(v.getPaddingLeft(), origPad, v.getPaddingRight(), v.getPaddingBottom());
                }
            }
            for (var entry : new ArrayList<>(originalTitleListMargins.entrySet())) {
                View v = entry.getKey();
                int[] orig = entry.getValue();
                if (v != null && orig != null && v.getLayoutParams() instanceof ViewGroup.MarginLayoutParams mlp) {
                    if (mlp.topMargin != orig[0] || mlp.bottomMargin != orig[1]) {
                        mlp.topMargin = orig[0];
                        mlp.bottomMargin = orig[1];
                        v.setLayoutParams(mlp);
                    }
                }
            }
            for (var entry : new ArrayList<>(originalRnTopMargin.entrySet())) {
                View v = entry.getKey();
                Integer origTop = entry.getValue();
                if (v != null && origTop != null && v.getLayoutParams() instanceof ViewGroup.MarginLayoutParams mlp) {
                    if (mlp.topMargin != origTop) {
                        mlp.topMargin = origTop;
                        v.setLayoutParams(mlp);
                    }
                }
            }
        }

        private static int dp(Activity a, int dp) {
            float density = a.getResources().getDisplayMetrics().density;
            return Math.round(dp * density);
        }

        private static String resourceEntryName(View v) {
            if (v == null) return "";
            int id = v.getId();
            if (id == View.NO_ID || id == 0) return "";
            try {
                return v.getResources().getResourceEntryName(id);
            } catch (Throwable e) {
                return "";
            }
        }
    }

    /**
     * Compacts QQ ({@code com.tencent.mobileqq}) top header and status-bar placeholders on the cover screen.
     *
     * <p>In {@code SplashActivity}, QQ sets the top header margin ({@code TitleAreaHeadLayout},
     * {@code TitleAreaRightLayout}), left header height & child top margins ({@code TitleAreaLeftLayout}),
     * header container heights, and {@code ClipSkinnableRecycleView} {@code paddingTop} using
     * {@code ImmersiveUtils.getStatusBarHeight()}. When QQ launches on the inner screen first,
     * {@code ImmersiveUtils} caches the inner-screen status bar height ({@code ~149px}) in a static field,
     * leaving a huge {@code 300px} header on the cover screen; even when launched on the cover screen,
     * {@code 31px} status bar offset plus {@code 21px} internal top padding leaves {@code 52px} top gap.
     * This adapter resets {@code ImmersiveUtils}'s cached status bar height on display transitions and
     * compacts the home header offset down to {@code 2dp} ({@code ~6px}) on the cover screen.
     */
    static final class QqLayout {
        final WeakReference<Activity> activity;
        final WeakHashMap<View, Integer> originalTopMargin = new WeakHashMap<>();
        final WeakHashMap<View, Integer> originalLayoutHeight = new WeakHashMap<>();
        final WeakHashMap<View, Integer> originalPaddingTop = new WeakHashMap<>();
        Boolean lastOuter = null;
        boolean loggedOnce = false;

        QqLayout(Activity a) {
            activity = new WeakReference<>(a);
            attachLayoutListener(a, () -> apply(false));
        }

        void apply(boolean scheduleFollowups) {
            Activity a = activity.get();
            if (a == null || a.isDestroyed() || a.getWindow() == null) return;
            View root = a.getWindow().getDecorView();
            boolean outer = AppCompatibility.isResourcePatchActiveFor(a);

            if (lastOuter == null || lastOuter != outer) {
                lastOuter = outer;
                resetImmersiveUtilsStatusBarCache(a);
            }

            PlaceholderCollapse collapse = placeholders.get(a);
            if (collapse == null) {
                collapse = new PlaceholderCollapse(a);
                placeholders.put(a, collapse);
            }
            collapse.apply(false);

            if (!outer) {
                restoreAll();
                return;
            }

            adaptTree(a, root);
            if (scheduleFollowups) {
                root.postDelayed(() -> apply(false), 180);
                root.postDelayed(() -> apply(false), 600);
            }
        }

        private static void resetImmersiveUtilsStatusBarCache(Activity a) {
            try {
                Class<?> cls = Class.forName("com.tencent.widget.immersive.ImmersiveUtils", false, a.getClassLoader());
                for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && f.getType() == int.class) {
                        String n = f.getName().toLowerCase(Locale.ROOT);
                        if (n.contains("statusbar")) {
                            f.setAccessible(true);
                            int val = f.getInt(null);
                            if (val > 0) {
                                f.setInt(null, -1);
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        private void adaptTree(Activity a, View root) {
            ArrayList<View> headLayouts = new ArrayList<>(2);
            ArrayList<View> rightLayouts = new ArrayList<>(2);
            ArrayList<View> leftLayouts = new ArrayList<>(2);
            ArrayList<View> chatRecyclerViews = new ArrayList<>(2);
            ArrayList<View> immersiveTitleBars = new ArrayList<>(4);
            collectQqViews(root, headLayouts, rightLayouts, leftLayouts, chatRecyclerViews, immersiveTitleBars);

            int targetSbOffset = dp(a, 2); // ~6px on 3.25x cover screen (children already have ~20px internal top offset)
            int minOrigSb = dp(a, 7);      // >= 22px (covers 31px cover-patched and 115-149px inner-cached status bar)
            int maxOrigSb = dp(a, 65);     // <= 211px
            int headerDelta = 0;

            for (View head : headLayouts) {
                int d = shiftTopMarginToTarget(head, targetSbOffset, minOrigSb, maxOrigSb);
                if (d > headerDelta) headerDelta = d;
            }
            for (View right : rightLayouts) {
                int d = shiftTopMarginToTarget(right, targetSbOffset, minOrigSb, maxOrigSb);
                if (d > headerDelta) headerDelta = d;
                // Also shift sibling status-bar placeholder and inner title container inside #b_y if present
                if (right.getParent() instanceof ViewGroup byGroup) {
                    for (int i = 0, n = byGroup.getChildCount(); i < n; i++) {
                        View sibling = byGroup.getChildAt(i);
                        if (sibling == null || sibling == right) continue;
                        String sCls = sibling.getClass().getName();
                        if (sCls.endsWith("TitleAreaLeftLayout")) continue;
                        ViewGroup.LayoutParams slp = sibling.getLayoutParams();
                        if (slp != null && slp.height >= minOrigSb && slp.height <= maxOrigSb && sibling.getTop() == 0) {
                            originalLayoutHeight.putIfAbsent(sibling, slp.height);
                            if (slp.height != targetSbOffset) {
                                slp.height = targetSbOffset;
                                sibling.setLayoutParams(slp);
                            }
                        } else if (slp instanceof ViewGroup.MarginLayoutParams smlp) {
                            Integer origM = originalTopMargin.get(sibling);
                            if (origM == null && smlp.topMargin >= minOrigSb && smlp.topMargin <= maxOrigSb) {
                                origM = smlp.topMargin;
                                originalTopMargin.put(sibling, origM);
                            }
                            if (origM != null && smlp.topMargin != targetSbOffset) {
                                smlp.topMargin = targetSbOffset;
                                sibling.setLayoutParams(smlp);
                            }
                        }
                    }
                }
            }

            if (headerDelta > 0) {
                for (View left : leftLayouts) {
                    shrinkHeightByDelta(left, headerDelta, dp(a, 48), dp(a, 110));
                    if (left instanceof ViewGroup lg) {
                        for (int i = 0, n = lg.getChildCount(); i < n; i++) {
                            View child = lg.getChildAt(i);
                            if (child == null) continue;
                            if (child.getLayoutParams() instanceof ViewGroup.MarginLayoutParams cmlp) {
                                Integer origTop = originalTopMargin.get(child);
                                if (origTop == null && cmlp.topMargin >= headerDelta) {
                                    origTop = cmlp.topMargin;
                                    originalTopMargin.put(child, origTop);
                                }
                                if (origTop != null) {
                                    int targetTop = Math.max(0, origTop - headerDelta);
                                    if (cmlp.topMargin != targetTop) {
                                        cmlp.topMargin = targetTop;
                                        child.setLayoutParams(cmlp);
                                    }
                                }
                            }
                        }
                    }
                    // Shrink ancestor header containers (#b_y -> #j2j -> #3ut) and blur sibling (#4nn)
                    View p = left.getParent() instanceof View pv ? pv : null;
                    for (int depth = 0; depth < 3 && p != null; depth++) {
                        shrinkHeightByDelta(p, headerDelta, dp(a, 48), dp(a, 110));
                        if (depth == 2 && p.getParent() instanceof ViewGroup outerHeaderParent) {
                            for (int i = 0, n = outerHeaderParent.getChildCount(); i < n; i++) {
                                View sib = outerHeaderParent.getChildAt(i);
                                if (sib != null && sib != p && sib.getClass().getName().contains("BlurView")) {
                                    shrinkHeightByDelta(sib, headerDelta, dp(a, 48), dp(a, 115));
                                }
                            }
                        }
                        p = p.getParent() instanceof View pv ? pv : null;
                    }
                }

                for (View rv : chatRecyclerViews) {
                    Integer origPad = originalPaddingTop.get(rv);
                    int curPad = rv.getPaddingTop();
                    if (origPad == null && curPad >= dp(a, 48) && curPad <= dp(a, 110)) {
                        origPad = curPad;
                        originalPaddingTop.put(rv, origPad);
                    }
                    if (origPad != null) {
                        int targetPad = Math.max(dp(a, 40), origPad - headerDelta);
                        if (curPad != targetPad) {
                            int diff = curPad - targetPad;
                            rv.setPadding(rv.getPaddingLeft(), targetPad, rv.getPaddingRight(), rv.getPaddingBottom());
                            if (rv instanceof ViewGroup rvg && rvg.getChildCount() > 0) {
                                View first = rvg.getChildAt(0);
                                if (first != null && first.getTop() > targetPad) {
                                    try {
                                        rv.scrollBy(0, diff);
                                    } catch (Throwable ignored) {
                                    }
                                }
                            }
                        }
                    }
                }

                if (!loggedOnce) {
                    loggedOnce = true;
                    ModLog.i(TAG, "qq layout compacted activity=" + a.getClass().getName()
                            + " delta=" + headerDelta + " targetSb=" + targetSbOffset);
                }
            }

            // Sub-activities & secondary pages with ImmersiveTitleBar2 status bar placeholder view
            int subTargetSb = dp(a, 8);
            for (View bar : immersiveTitleBars) {
                ViewGroup.LayoutParams lp = bar.getLayoutParams();
                if (lp == null) continue;
                Integer origH = originalLayoutHeight.get(bar);
                int curH = lp.height > 0 ? lp.height : bar.getHeight();
                if (origH == null && curH > subTargetSb && curH <= maxOrigSb) {
                    origH = curH;
                    originalLayoutHeight.put(bar, origH);
                }
                if (origH != null && lp.height != subTargetSb) {
                    lp.height = subTargetSb;
                    bar.setLayoutParams(lp);
                }
            }
        }

        private int shiftTopMarginToTarget(View v, int targetTop, int minOrig, int maxOrig) {
            if (!(v.getLayoutParams() instanceof ViewGroup.MarginLayoutParams mlp)) return 0;
            Integer origTop = originalTopMargin.get(v);
            if (origTop == null) {
                if (mlp.topMargin >= minOrig && mlp.topMargin <= maxOrig) {
                    origTop = mlp.topMargin;
                    originalTopMargin.put(v, origTop);
                } else {
                    return 0;
                }
            }
            int delta = Math.max(0, origTop - targetTop);
            if (delta > 0 && mlp.topMargin != targetTop) {
                mlp.topMargin = targetTop;
                v.setLayoutParams(mlp);
            }
            return delta;
        }

        private void shrinkHeightByDelta(View v, int delta, int minOrigH, int maxOrigH) {
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp == null) return;
            Integer origH = originalLayoutHeight.get(v);
            int curH = lp.height > 0 ? lp.height : v.getHeight();
            if (origH == null && curH >= minOrigH && curH <= maxOrigH) {
                origH = curH;
                originalLayoutHeight.put(v, origH);
            }
            if (origH != null) {
                int targetH = Math.max(1, origH - delta);
                if (lp.height != targetH) {
                    lp.height = targetH;
                    v.setLayoutParams(lp);
                }
            }
        }

        private void collectQqViews(View v, ArrayList<View> headLayouts, ArrayList<View> rightLayouts,
                ArrayList<View> leftLayouts, ArrayList<View> chatRecyclerViews, ArrayList<View> immersiveTitleBars) {
            if (v == null || v.getVisibility() == View.GONE) return;
            String cls = v.getClass().getName();
            if (cls.endsWith("TitleAreaHeadLayout")) {
                headLayouts.add(v);
            } else if (cls.endsWith("TitleAreaRightLayout")) {
                rightLayouts.add(v);
            } else if (cls.endsWith("TitleAreaLeftLayout")) {
                leftLayouts.add(v);
            } else if (cls.endsWith("ClipSkinnableRecycleView")) {
                chatRecyclerViews.add(v);
            } else if (cls.endsWith("ImmersiveTitleBar2")) {
                immersiveTitleBars.add(v);
            }
            if (v instanceof ViewGroup vg) {
                for (int i = 0, n = vg.getChildCount(); i < n; i++) {
                    collectQqViews(vg.getChildAt(i), headLayouts, rightLayouts, leftLayouts,
                            chatRecyclerViews, immersiveTitleBars);
                }
            }
        }

        private void restoreAll() {
            for (var entry : new ArrayList<>(originalTopMargin.entrySet())) {
                View v = entry.getKey();
                Integer origTop = entry.getValue();
                if (v != null && origTop != null && v.getLayoutParams() instanceof ViewGroup.MarginLayoutParams mlp) {
                    if (mlp.topMargin != origTop) {
                        mlp.topMargin = origTop;
                        v.setLayoutParams(mlp);
                    }
                }
            }
            for (var entry : new ArrayList<>(originalLayoutHeight.entrySet())) {
                View v = entry.getKey();
                Integer origH = entry.getValue();
                if (v != null && origH != null && v.getLayoutParams() != null) {
                    ViewGroup.LayoutParams lp = v.getLayoutParams();
                    if (lp.height != origH) {
                        lp.height = origH;
                        v.setLayoutParams(lp);
                    }
                }
            }
            for (var entry : new ArrayList<>(originalPaddingTop.entrySet())) {
                View v = entry.getKey();
                Integer origPad = entry.getValue();
                if (v != null && origPad != null && v.getPaddingTop() != origPad) {
                    v.setPadding(v.getPaddingLeft(), origPad, v.getPaddingRight(), v.getPaddingBottom());
                }
            }
        }

        private static int dp(Activity a, int dp) {
            float density = a.getResources().getDisplayMetrics().density;
            return Math.round(dp * density);
        }
    }
}
