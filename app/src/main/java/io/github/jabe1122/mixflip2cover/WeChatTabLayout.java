package io.github.jabe1122.mixflip2cover;

import android.app.Activity;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import io.github.libxposed.api.XposedModule;

/** WeChat 8.0.x: only Contacts and Discover reserve their existing floating application toolbar. */
final class WeChatTabLayout {
    private static final String TAG = "MF2C.WeChatList";
    private static final WeakHashMap<Activity, State> activities = new WeakHashMap<>();
    /** Guarded by itself: setPadding may in rare cases be called off the main thread. */
    private static final WeakHashMap<View, Padding> lists = new WeakHashMap<>();
    /** Concrete classes of the tracked list views; lets the global setPadding hook bail out in O(1). */
    private static final Set<Class<?>> trackedClasses = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<Boolean> own = new ThreadLocal<>();
    /**
     * Cheapest possible gate for the global View#setPadding hooks: false until a WeChat tab list has been
     * tracked (native padding changes of tracked lists must keep being recorded even while inactive).
     */
    private static volatile boolean anyTracked;
    private static volatile boolean installed;

    private WeChatTabLayout() {}

    private static final class Padding {
        int nativeTop, toolbarBottom, listTop, toolbarHeight, lastLogged = -1;
        volatile boolean active;

        Padding(View v) {
            nativeTop = v.getPaddingTop();
        }

        int target() {
            return active ? AppHeaderSpace.paddingTop(nativeTop, toolbarBottom, listTop, toolbarHeight) : nativeTop;
        }
    }

    static synchronized void install(XposedModule module) throws ReflectiveOperationException {
        if (installed) return;
        for (String name : new String[]{"setPadding", "setPaddingRelative"}) {
            Method m = View.class.getDeclaredMethod(name, int.class, int.class, int.class, int.class);
            module.hook(m).intercept(chain -> {
                // Hot path for every View in the process: two volatile/field reads before anything else.
                if (!anyTracked) return chain.proceed();
                Object[] replaced = null;
                try {
                    Object thiz = chain.getThisObject();
                    if (thiz != null && trackedClasses.contains(thiz.getClass())
                            && !Boolean.TRUE.equals(own.get())) {
                        Padding p;
                        synchronized (lists) {
                            p = lists.get(thiz);
                        }
                        if (p != null) {
                            p.nativeTop = (Integer) chain.getArg(1);
                            if (p.active) {
                                replaced = chain.getArgs().toArray();
                                replaced[1] = p.target();
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    replaced = null;
                }
                if (replaced == null) return chain.proceed();
                own.set(true);
                try {
                    return chain.proceed(replaced);
                } finally {
                    own.remove();
                }
            });
        }
        installed = true;
    }

    static void update(Activity a) {
        try {
            if (!a.getClass().getName().equals("com.tencent.mm.ui.LauncherUI")) return;
            State state = activities.get(a);
            if (state == null) {
                state = new State(a);
                activities.put(a, state);
            }
            state.apply();
        } catch (Throwable t) {
            Log.w(TAG, "WeChat tab layout failed", t);
        }
    }

    private static void setTop(View v, int top) {
        if (v.getPaddingTop() == top) return;
        own.set(true);
        try {
            if (v.isPaddingRelative()) {
                v.setPaddingRelative(v.getPaddingStart(), top, v.getPaddingEnd(), v.getPaddingBottom());
            } else {
                v.setPadding(v.getPaddingLeft(), top, v.getPaddingRight(), v.getPaddingBottom());
            }
        } finally {
            own.remove();
        }
    }

    private static int topInRoot(View view, View root) {
        int top = 0;
        View current = view;
        while (current != root) {
            top += current.getTop();
            if (!(current.getParent() instanceof View parent)) return Integer.MIN_VALUE;
            current = parent;
        }
        return top;
    }

    private static final class State {
        final WeakReference<Activity> activity;
        final Set<View> tracked = Collections.newSetFromMap(new WeakHashMap<>());
        final boolean supported;
        final int pagerId, barId, contactsId;

        State(Activity a) {
            activity = new WeakReference<>(a);
            supported = WeChatInsets.isVerifiedVersion(a);
            // Obfuscated resource names, only verified for the WeChatInsets version range; 0 = not found.
            pagerId = supported ? a.getResources().getIdentifier("kod", "id", "com.tencent.mm") : 0;
            barId = supported ? a.getResources().getIdentifier("ei", "id", "com.tencent.mm") : 0;
            contactsId = supported ? a.getResources().getIdentifier("mg", "id", "com.tencent.mm") : 0;
            if (supported && pagerId != 0 && barId != 0) {
                AppLayoutAdapters.attachLayoutListener(a, this::apply);
            }
        }

        void apply() {
            try {
                applyInternal();
            } catch (Throwable t) {
                Log.w(TAG, "apply failed", t);
            }
        }

        private void applyInternal() {
            Activity a = activity.get();
            if (a == null || a.isDestroyed()) return;
            Display d = a.getDisplay();
            boolean enabled = supported
                    && pagerId != 0 && barId != 0
                    && FrameworkRuntime.active()
                    && ModuleConfig.immersiveEnabled()
                    && ModuleConfig.compatEnabled("com.tencent.mm")
                    && CoverDisplay.isOuterPortraitDisplay(d);
            View root = a.getWindow().getDecorView();
            View bar = barId == 0 ? null : root.findViewById(barId);
            View pager = pagerId == 0 ? null : root.findViewById(pagerId);
            Set<View> selected = new HashSet<>();
            if (enabled && bar != null && bar.isShown() && bar.getHeight() > 0
                    && pager instanceof ViewGroup pages && pager.getWidth() > 0) {
                int barTop = topInRoot(bar, root);
                int barBottom = barTop == Integer.MIN_VALUE ? Integer.MIN_VALUE : barTop + bar.getHeight();
                for (int i = 0; i < pages.getChildCount(); i++) {
                    View page = pages.getChildAt(i);
                    if (!page.getClass().getName().equals("com.tencent.mm.ui.FrostedContentView") || page.getWidth() <= 0) {
                        continue;
                    }
                    int position = Math.round(page.getLeft() / (float) pager.getWidth());
                    View list = null;
                    if (position == 1 && contactsId != 0) {
                        View candidate = page.findViewById(contactsId);
                        if (candidate != null && candidate.getClass().getName().equals("com.tencent.mm.view.recyclerview.WxRecyclerView")) {
                            list = candidate;
                        }
                    } else if (position == 2) {
                        View candidate = page.findViewById(android.R.id.list);
                        if (candidate != null && candidate.getClass().getName().equals("com.tencent.mm.ui.widget.listview.PullDownListView")) {
                            list = candidate;
                        }
                    }
                    if (list == null || barBottom == Integer.MIN_VALUE) continue;
                    int listTop = topInRoot(list, root);
                    if (listTop == Integer.MIN_VALUE) continue;
                    Padding padding;
                    synchronized (lists) {
                        padding = lists.get(list);
                        if (padding == null) {
                            padding = new Padding(list);
                            lists.put(list, padding);
                        }
                    }
                    trackedClasses.add(list.getClass());
                    padding.toolbarBottom = barBottom;
                    padding.toolbarHeight = bar.getHeight();
                    padding.listTop = listTop;
                    padding.active = true;
                    anyTracked = true;
                    selected.add(list);
                    tracked.add(list);
                    int target = padding.target();
                    setTop(list, target);
                    if (padding.lastLogged != target) {
                        padding.lastLogged = target;
                        ModLog.i(TAG, "tab=" + position + " nativeTop=" + padding.nativeTop
                                + " toolbar=" + bar.getHeight() + " paddingTop=" + target);
                    }
                }
            }
            for (View list : new ArrayList<>(tracked)) {
                if (!selected.contains(list)) {
                    Padding padding;
                    synchronized (lists) {
                        padding = lists.get(list);
                    }
                    if (padding != null) {
                        padding.active = false;
                        setTop(list, padding.nativeTop);
                    }
                }
            }
        }
    }
}
