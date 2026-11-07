package io.github.jabe1122.mixflip2cover;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import io.github.libxposed.api.XposedModule;

/** Five complete rows laid out inside the real viewport; never translate the whole grid. */
final class DesktopAlignment {
    private static final String TAG = "MF2C.Desktop";
    static final String GEOMETRY = "io.github.jabe1122.mixflip2cover.DESKTOP_GEOMETRY";
    private static final String APP_ITEM = "com.miui.fliphome.view.LauncherAppItem";
    private static final String HOME_RECYCLER = "com.miui.fliphome.recyclerview.HomeRecyclerView";

    static final WeakHashMap<View, Grid> grids = new WeakHashMap<>();
    static final WeakHashMap<View, Icon> icons = new WeakHashMap<>();
    static final WeakHashMap<View, Icon> titles = new WeakHashMap<>();
    static final WeakHashMap<View, Decoration> decorations = new WeakHashMap<>();
    static final WeakHashMap<View, Integer> headers = new WeakHashMap<>();
    static final ThreadLocal<Boolean> ownVisibility = new ThreadLocal<>();
    static int rowHeight, labelGap, lifecycleLogs;
    static float firstRowTop = Float.NaN;
    /** In-process (launcher) geometry listeners; SystemUI receives the same value via {@link #GEOMETRY}. */
    private static final CopyOnWriteArrayList<Consumer<Float>> localListeners = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<Consumer<Float>> remoteListeners = new CopyOnWriteArrayList<>();
    private static volatile BroadcastReceiver geometryReceiver;
    private static volatile float lastIconCenter = Float.NaN;
    private static volatile float lastRemoteIconCenter = Float.NaN;
    private static int appTitleId = -1;
    private static Class<?> positionOwner;
    private static Method getChildAdapterPosition;

    private DesktopAlignment() {}

    static boolean outer(View view) {
        Display d = view != null ? view.getDisplay() : null;
        if (d == null) return AppCompatibility.outerProcess;
        Display.Mode m = d.getMode();
        if (m == null) return AppCompatibility.outerProcess;
        return m.getPhysicalWidth() == CoverDisplay.WIDTH && m.getPhysicalHeight() == CoverDisplay.HEIGHT;
    }

    static boolean item(View view) {
        return view != null && view.getClass().getName().equals(APP_ITEM);
    }

    static void applyItem(View view) {
        if (!item(view)) return;
        Icon state = icons.computeIfAbsent(view, Icon::new);
        state.apply(rowHeight, labelGap);
        if (ModuleConfig.hideAppNames() && lifecycleLogs++ < 12) {
            View titleView = state.titleRef.get();
            ModLog.i(TAG, "item attached=" + view.isAttachedToWindow()
                    + " titleVisibility=" + (titleView == null ? -1 : titleView.getVisibility()));
        }
    }

    /** Whether {@code view} is an app label inside a launcher item; the id is resolved once. */
    static boolean title(View view) {
        try {
            if (appTitleId < 0) {
                appTitleId = view.getResources().getIdentifier("app_title", "id", CoverGeometry.FLIPHOME);
            }
            return appTitleId != 0 && view.getId() == appTitleId && view.getParent() instanceof View parent && item(parent);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static void applyHeader(View view) {
        ViewGroup.LayoutParams p = view.getLayoutParams();
        if (p == null) return;
        Integer original = headers.get(view);
        if (original == null) {
            original = p.height;
            headers.put(view, original);
        }
        int target = ModuleConfig.desktopEnabled() && outer(view) ? 0 : original;
        if (p.height != target) {
            p.height = target;
            view.setLayoutParams(p);
        }
    }

    static boolean atListStart(ViewGroup recycler) {
        if (recycler == null || recycler.getChildCount() == 0) return true;
        try {
            Class<?> type = recycler.getClass();
            if (type != positionOwner) {
                getChildAdapterPosition = type.getMethod("getChildAdapterPosition", View.class);
                positionOwner = type;
            }
            View firstChild = recycler.getChildAt(0);
            for (int i = 0; i < recycler.getChildCount(); i++) {
                View child = recycler.getChildAt(i);
                if (!item(child)) continue;
                int pos = (Integer) getChildAdapterPosition.invoke(recycler, child);
                if (pos >= 0 && pos <= 3) {
                    if (child.getTop() >= recycler.getPaddingTop() - 4) return true;
                    if (firstChild != null && !item(firstChild) && firstChild.getTop() >= 0 && child.getTop() >= -2) {
                        return true;
                    }
                    return false;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            positionOwner = null;
        }
        return !recycler.canScrollVertically(-1);
    }

    static boolean canScrollUp(Activity activity) {
        if (activity == null) return false;
        for (Grid grid : new ArrayList<>(grids.values())) {
            if (grid == null) continue;
            View container = grid.containerRef.get();
            ViewGroup recycler = grid.recyclerRef != null ? grid.recyclerRef.get() : null;
            if (recycler != null && container != null
                    && (container.getContext() == activity || recycler.isAttachedToWindow())) {
                return recycler.canScrollVertically(-1) && !atListStart(recycler);
            }
        }
        try {
            int id = activity.getResources().getIdentifier("recycle_view", "id", CoverGeometry.FLIPHOME);
            if (id != 0 && activity.findViewById(id) instanceof ViewGroup recycler) {
                return recycler.canScrollVertically(-1) && !atListStart(recycler);
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    static void install(XposedModule module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> appItem = loader.loadClass(APP_ITEM);
        for (Method method : appItem.getDeclaredMethods()) {
            if (method.getName().equals("onFinishInflate") || method.getName().equals("bindItem")) {
                module.hook(method).intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        applyItem((View) chain.getThisObject());
                    } catch (RuntimeException e) {
                        Log.w(TAG, "item layout skipped", e);
                    }
                    return result;
                });
            }
        }
        Class<?> header = loader.loadClass("com.miui.fliphome.adapter.FlipLauncherAdapter$HeaderViewHolder");
        for (Constructor<?> constructor : header.getDeclaredConstructors()) {
            module.hook(constructor).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    for (Object argument : chain.getArgs()) {
                        if (argument instanceof View view) applyHeader(view);
                    }
                } catch (RuntimeException e) {
                    Log.w(TAG, "header layout skipped", e);
                }
                return result;
            });
        }
        Class<?> shadow = loader.loadClass("com.miui.fliphome.widget.ShadowTextView");
        for (String drawMethodName : new String[]{"draw", "onDraw"}) {
            try {
                Method drawMethod = shadow.getDeclaredMethod(drawMethodName, Canvas.class);
                drawMethod.setAccessible(true);
                module.hook(drawMethod).intercept(chain -> {
                    boolean hide = false;
                    try {
                        View view = (View) chain.getThisObject();
                        hide = ModuleConfig.hideAppNames() && title(view);
                    } catch (RuntimeException ignored) {
                    }
                    return hide ? null : chain.proceed();
                });
            } catch (NoSuchMethodException ignored) {
            }
        }
        module.hook(View.class.getDeclaredMethod("setVisibility", int.class)).intercept(chain -> {
            // Cheapest checks first: nothing to do until a launcher label has been registered.
            if (!titles.isEmpty()
                    && chain.getThisObject() instanceof View view
                    && !Boolean.TRUE.equals(ownVisibility.get())
                    && (Object) titles.get(view) instanceof Icon icon) {
                boolean hide = false;
                try {
                    icon.originalVisibility = (Integer) chain.getArg(0);
                    hide = ModuleConfig.hideAppNames();
                } catch (RuntimeException ignored) {
                }
                if (hide) return chain.proceed(new Object[]{View.INVISIBLE});
            }
            return chain.proceed();
        });
        Class<?> recycler = loader.loadClass("androidx.recyclerview.widget.RecyclerView");
        module.hook(recycler.getDeclaredMethod("dispatchChildAttached", View.class)).intercept(chain -> {
            Object result = chain.proceed();
            try {
                applyItem((View) chain.getArg(0));
            } catch (RuntimeException e) {
                Log.w(TAG, "child layout skipped", e);
            }
            return result;
        });
        module.hook(recycler.getDeclaredMethod("getItemDecorInsetsForChild", View.class)).intercept(chain -> {
            Object value = chain.proceed();
            try {
                if (!(value instanceof Rect rect)) return value;
                View child = (View) chain.getArg(0);
                View host = (View) chain.getThisObject();
                if ((!item(child) && !headers.containsKey(child)) || !host.getClass().getName().equals(HOME_RECYCLER)) {
                    return rect;
                }
                Decoration saved = decorations.computeIfAbsent(child, v -> new Decoration());
                if (saved.rect != rect || !saved.applied || rect.top != 0 || rect.bottom != 0) {
                    saved.top = rect.top;
                    saved.bottom = rect.bottom;
                    saved.rect = rect;
                }
                boolean active = ModuleConfig.desktopEnabled() && outer(host);
                rect.top = active ? 0 : saved.top;
                rect.bottom = active ? 0 : saved.bottom;
                saved.applied = active;
            } catch (RuntimeException e) {
                Log.w(TAG, "item decoration skipped", e);
            }
            return value;
        });
        Class<?> spring = loader.loadClass("miuix.springback.view.SpringBackLayout");
        module.hook(spring.getDeclaredMethod("isTargetScrollToTop", int.class)).intercept(chain -> {
            try {
                if ((Integer) chain.getArg(0) == 2 && chain.getThisObject() instanceof View view
                        && ModuleConfig.desktopEnabled() && outer(view)
                        && CoverGeometry.field(view, "mTarget") instanceof ViewGroup target
                        && target.getClass().getName().equals(HOME_RECYCLER)
                        && atListStart(target)) {
                    return true;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
            return chain.proceed();
        });
        for (Method method : spring.getDeclaredMethods()) {
            String name = method.getName();
            if (name.equals("onVerticalInterceptTouchEvent") || name.equals("onVerticalTouchEvent")
                    || name.equals("handleOverscrollMovements") || name.equals("onNestedScroll")) {
                module.deoptimize(method);
            }
        }
        ModuleConfig.addListener(DesktopAlignment::refreshAll);
    }

    static void refreshAll() {
        for (Icon icon : new ArrayList<>(icons.values())) {
            try {
                icon.apply(rowHeight, labelGap);
            } catch (RuntimeException ignored) {
            }
        }
        for (Grid grid : new ArrayList<>(grids.values())) {
            try {
                grid.apply();
            } catch (RuntimeException ignored) {
            }
        }
    }

    static final class Decoration {
        Rect rect;
        int top, bottom;
        boolean applied;
    }

    static final class Icon {
        final WeakReference<View> itemRef, iconRef, titleRef;
        final int originalHeight, iconMargin, titleMargin;
        int originalVisibility;

        Icon(View item) {
            this.itemRef = new WeakReference<>(item);
            int imageId = item.getResources().getIdentifier("app_icon", "id", CoverGeometry.FLIPHOME);
            int titleId = item.getResources().getIdentifier("app_title", "id", CoverGeometry.FLIPHOME);
            View icon = item.findViewById(imageId);
            View title = item.findViewById(titleId);
            this.iconRef = new WeakReference<>(icon);
            this.titleRef = new WeakReference<>(title);
            int heightId = item.getResources().getIdentifier("launch_item_app_height", "dimen", CoverGeometry.FLIPHOME);
            originalHeight = item.getLayoutParams() != null
                    ? item.getLayoutParams().height
                    : heightId != 0 ? item.getResources().getDimensionPixelSize(heightId) : -2;
            iconMargin = icon != null && icon.getLayoutParams() instanceof ViewGroup.MarginLayoutParams p ? p.topMargin : 0;
            titleMargin = title != null && title.getLayoutParams() instanceof ViewGroup.MarginLayoutParams p ? p.topMargin : 0;
            originalVisibility = title == null ? View.VISIBLE : title.getVisibility();
            if (title != null) titles.put(title, this);
        }

        void apply(int rowHeight, int labelGap) {
            View item = itemRef.get();
            if (item == null) return;
            View icon = iconRef.get();
            View title = titleRef.get();
            boolean active = ModuleConfig.desktopEnabled() && outer(item);
            if (title != null) {
                int target = ModuleConfig.hideAppNames() ? View.INVISIBLE : originalVisibility;
                if (title.getVisibility() != target) {
                    ownVisibility.set(true);
                    try {
                        title.setVisibility(target);
                    } finally {
                        ownVisibility.remove();
                    }
                }
                title.invalidate();
            }
            if (icon != null && icon.getLayoutParams() instanceof ViewGroup.MarginLayoutParams p) {
                int target = active ? 0 : iconMargin;
                if (p.topMargin != target) {
                    p.topMargin = target;
                    icon.setLayoutParams(p);
                }
            }
            if (rowHeight > 0 && item.getLayoutParams() != null) {
                ViewGroup.LayoutParams p = item.getLayoutParams();
                int target = active ? rowHeight : originalHeight;
                if (p.height != target) {
                    p.height = target;
                    item.setLayoutParams(p);
                }
            } else if (!active && item.getLayoutParams() != null && item.getLayoutParams().height != originalHeight) {
                ViewGroup.LayoutParams p = item.getLayoutParams();
                p.height = originalHeight;
                item.setLayoutParams(p);
            }
            if (title != null && title.getLayoutParams() instanceof ViewGroup.MarginLayoutParams p) {
                int target = active && rowHeight > 0 ? Math.min(titleMargin, labelGap) : titleMargin;
                if (p.topMargin != target) {
                    p.topMargin = target;
                    title.setLayoutParams(p);
                }
            }
        }
    }

    static View find(View root, int id) {
        if (root.getId() == id) return root;
        if (root instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), id);
                if (found != null) return found;
            }
        }
        return null;
    }

    static void update(Activity activity) {
        int id = activity.getResources().getIdentifier("launcher_container", "id", CoverGeometry.FLIPHOME);
        View container = activity.findViewById(id);
        if (container == null) return;
        Grid grid = grids.get(container);
        if (grid == null) {
            grid = new Grid(container);
            grids.put(container, grid);
            grid.attachLayoutListener(container);
            container.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    Grid g = grids.get(v);
                    if (g != null) {
                        g.attachLayoutListener(v);
                        g.apply();
                    }
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    Grid g = grids.get(v);
                    if (g != null) {
                        g.detachLayoutListener(v);
                    }
                }
            });
        } else {
            grid.attachLayoutListener(container);
        }
        grid.apply();
        WeakReference<Grid> gridRef = new WeakReference<>(grid);
        container.postDelayed(() -> {
            Grid g = gridRef.get();
            if (g != null) g.apply();
        }, 250);
    }

    static final class Grid {
        final WeakReference<View> containerRef;
        final ViewTreeObserver.OnGlobalLayoutListener layoutListener;
        boolean layoutListenerAttached;
        WeakReference<ViewGroup> recyclerRef;
        WeakReference<View> springRef;
        int[] padding;
        int springHeight, springTop, springBottom, recyclerHeight;
        boolean active, applying;
        int lastStep = -1, lastTop = -1;

        Grid(View container) {
            this.containerRef = new WeakReference<>(container);
            WeakReference<Grid> selfRef = new WeakReference<>(this);
            this.layoutListener = () -> {
                Grid self = selfRef.get();
                if (self != null) self.apply();
            };
        }

        void attachLayoutListener(View container) {
            if (container == null || layoutListenerAttached) return;
            ViewTreeObserver vto = container.getViewTreeObserver();
            if (vto != null && vto.isAlive()) {
                vto.addOnGlobalLayoutListener(layoutListener);
                layoutListenerAttached = true;
            }
        }

        void detachLayoutListener(View container) {
            if (container == null || !layoutListenerAttached) return;
            ViewTreeObserver vto = container.getViewTreeObserver();
            if (vto != null && vto.isAlive()) {
                vto.removeOnGlobalLayoutListener(layoutListener);
            }
            layoutListenerAttached = false;
        }

        void apply() {
            if (applying) return;
            View container = containerRef.get();
            if (container == null) return;
            applying = true;
            try {
                ViewGroup recycler = recyclerRef != null ? recyclerRef.get() : null;
                View spring = springRef != null ? springRef.get() : null;
                if (recycler == null || spring == null) {
                    int id = container.getResources().getIdentifier("recycle_view", "id", CoverGeometry.FLIPHOME);
                    View found = find(container, id);
                    if (!(found instanceof ViewGroup group) || !(group.getParent() instanceof View parent)) return;
                    recycler = group;
                    spring = parent;
                    recyclerRef = new WeakReference<>(recycler);
                    springRef = new WeakReference<>(spring);
                    padding = new int[]{group.getPaddingLeft(), group.getPaddingTop(), group.getPaddingRight(), group.getPaddingBottom()};
                    recyclerHeight = group.getLayoutParams().height;
                    ViewGroup.LayoutParams p = spring.getLayoutParams();
                    springHeight = p.height;
                    if (p instanceof ViewGroup.MarginLayoutParams m) {
                        springTop = m.topMargin;
                        springBottom = m.bottomMargin;
                    }
                }
                boolean enabled = ModuleConfig.desktopEnabled() && outer(container);
                if (!enabled) {
                    rowHeight = labelGap = 0;
                    firstRowTop = Float.NaN;
                    if (active) {
                        restore(recycler, spring);
                        active = false;
                        lastStep = lastTop = -1;
                    }
                    for (Icon icon : new ArrayList<>(icons.values())) icon.apply(0, 0);
                    for (View header : new ArrayList<>(headers.keySet())) applyHeader(header);
                    return;
                }
                View first = null;
                for (int i = 0; i < recycler.getChildCount(); i++) {
                    if (item(recycler.getChildAt(i))) {
                        first = recycler.getChildAt(i);
                        break;
                    }
                }
                if (first == null) return;
                Icon sample = icons.computeIfAbsent(first, Icon::new);
                View sampleIcon = sample.iconRef.get();
                View sampleTitle = sample.titleRef.get();
                if (sampleIcon == null || sampleIcon.getHeight() <= 0) return;
                int iconHeight = sampleIcon.getHeight();
                int labelHeight = sampleTitle == null ? 0 : sampleTitle.getMeasuredHeight();
                Display cd = container.getDisplay();
                Display.Mode cm = cd != null ? cd.getMode() : null;
                int screenHeight = cm != null ? cm.getPhysicalHeight() : CoverDisplay.HEIGHT;
                int topInset = 0;
                if (!ModuleConfig.frameworkFullscreen() && container.getRootWindowInsets() != null) {
                    topInset = container.getRootWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top;
                }
                float edge = Math.max(topInset, (screenHeight - 5f * iconHeight) / 6f);
                int step = Math.round((screenHeight - 2 * edge - iconHeight) / 4f);
                if (step < iconHeight + labelHeight + 2) return;
                int firstTop = Math.max(topInset, Math.round((screenHeight - 4f * step - iconHeight) / 2f));
                int localTop = Math.max(0, firstTop - topInset);
                int gap = Math.max(0, step - iconHeight - labelHeight - 2);
                DesktopAlignment.rowHeight = step;
                DesktopAlignment.labelGap = gap;
                firstRowTop = firstTop;
                boolean reset = !active || lastStep != step || lastTop != localTop;
                active = true;
                lastStep = step;
                lastTop = localTop;
                ViewGroup.LayoutParams p = spring.getLayoutParams();
                boolean change = false;
                if (p instanceof ViewGroup.MarginLayoutParams m && (m.topMargin != 0 || m.bottomMargin != 0)) {
                    m.topMargin = m.bottomMargin = 0;
                    change = true;
                }
                if (p.height != 0) {
                    p.height = 0;
                    change = true;
                }
                if (change) spring.setLayoutParams(p);
                p = recycler.getLayoutParams();
                if (p.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                    p.height = ViewGroup.LayoutParams.MATCH_PARENT;
                    recycler.setLayoutParams(p);
                }
                if (recycler.getPaddingTop() != localTop || recycler.getPaddingBottom() != firstTop) {
                    recycler.setPadding(padding[0], localTop, padding[2], firstTop);
                }
                for (int i = 0; i < recycler.getChildCount(); i++) {
                    View child = recycler.getChildAt(i);
                    if (item(child)) icons.computeIfAbsent(child, Icon::new).apply(step, gap);
                }
                for (View header : new ArrayList<>(headers.keySet())) applyHeader(header);
                if (reset) {
                    try {
                        recycler.getClass().getMethod("invalidateItemDecorations").invoke(recycler);
                    } catch (ReflectiveOperationException ignored) {
                    }
                    WeakReference<ViewGroup> listRef = new WeakReference<>(recycler);
                    recycler.post(() -> {
                        ViewGroup list = listRef.get();
                        if (list == null) return;
                        try {
                            Object manager = list.getClass().getMethod("getLayoutManager").invoke(list);
                            manager.getClass().getMethod("scrollToPositionWithOffset", int.class, int.class).invoke(manager, 0, 0);
                            list.post(() -> {
                                ViewGroup inner = listRef.get();
                                if (inner != null) {
                                    ModLog.i(TAG, "origin canScrollUp=" + (inner.canScrollVertically(-1) && !atListStart(inner)));
                                }
                            });
                        } catch (ReflectiveOperationException | RuntimeException ignored) {
                        }
                    });
                    publishIconCenter(container.getContext(), firstTop + iconHeight / 2f);
                    ModLog.i(TAG, "five rows: top=" + firstTop + " step=" + step + " icon=" + iconHeight
                            + " bottom=" + (screenHeight - firstTop - 4 * step - iconHeight));
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "grid update skipped", e);
            } finally {
                applying = false;
            }
        }

        void restore(ViewGroup recycler, View spring) {
            if (recycler == null || spring == null || padding == null) return;
            ViewGroup.LayoutParams p = spring.getLayoutParams();
            p.height = springHeight;
            if (p instanceof ViewGroup.MarginLayoutParams m) {
                m.topMargin = springTop;
                m.bottomMargin = springBottom;
            }
            spring.setLayoutParams(p);
            p = recycler.getLayoutParams();
            p.height = recyclerHeight;
            recycler.setLayoutParams(p);
            recycler.setPadding(padding[0], padding[1], padding[2], padding[3]);
            try {
                recycler.getClass().getMethod("invalidateItemDecorations").invoke(recycler);
            } catch (ReflectiveOperationException ignored) {
            }
        }
    }

    /** Sends the first-row icon centre to SystemUI and to launcher-process listeners (e.g. the widget card). */
    private static void publishIconCenter(Context context, float center) {
        lastIconCenter = center;
        try {
            context.sendBroadcast(new Intent(GEOMETRY).setPackage(CoverGeometry.SYSTEMUI).putExtra("iconCenter", center));
        } catch (RuntimeException e) {
            Log.w(TAG, "geometry broadcast skipped", e);
        }
        if (validCenter(center)) {
            for (Consumer<Float> listener : localListeners) {
                try {
                    listener.accept(center);
                } catch (RuntimeException e) {
                    Log.w(TAG, "geometry listener failed", e);
                }
            }
        }
    }

    private static boolean validCenter(float center) {
        return Float.isFinite(center) && center >= 0 && center <= 500;
    }

    /** Registers a listener inside the launcher process; replays the last known value immediately. */
    static void listenLocal(Consumer<Float> callback) {
        if (callback == null || !localListeners.addIfAbsent(callback)) return;
        float last = lastIconCenter;
        if (validCenter(last)) callback.accept(last);
    }

    /** SystemUI side: receives the launcher geometry over the protected {@link #GEOMETRY} broadcast. */
    static BroadcastReceiver listen(Context context, Consumer<Float> callback) {
        if (callback != null && remoteListeners.addIfAbsent(callback)) {
            float cached = lastRemoteIconCenter;
            if (validCenter(cached)) callback.accept(cached);
        }
        if (geometryReceiver == null && context != null) {
            synchronized (DesktopAlignment.class) {
                if (geometryReceiver == null) {
                    Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
                    BroadcastReceiver receiver = new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context c, Intent intent) {
                            if (intent == null) return;
                            float center = intent.getFloatExtra("iconCenter", Float.NaN);
                            if (!validCenter(center)) return;
                            lastRemoteIconCenter = center;
                            for (Consumer<Float> listener : remoteListeners) {
                                try {
                                    listener.accept(center);
                                } catch (RuntimeException e) {
                                    Log.w(TAG, "remote geometry listener failed", e);
                                }
                            }
                        }
                    };
                    app.registerReceiver(receiver, new IntentFilter(GEOMETRY), CoverGeometry.STATUS_BAR_PERMISSION,
                            null, Context.RECEIVER_EXPORTED);
                    geometryReceiver = receiver;
                }
            }
        }
        return geometryReceiver;
    }
}
