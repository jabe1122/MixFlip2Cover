package io.github.jabe1122.mixflip2cover;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import io.github.libxposed.api.XposedModule;

/**
 * Extends {@code com.miui.fliphome.widget.ui.RecentTaskView} so the user can pin up to 4 custom frequently used
 * app icons into the 2x2 cover-screen app widget.
 *
 * <ul>
 *   <li>When 4 apps are pinned, the widget displays those 4 apps in the exact order chosen by the user.</li>
 *   <li>When 1–3 apps are pinned, the remaining slots (up to 4) are automatically filled with non-duplicate
 *       recent tasks.</li>
 *   <li>Even when background recent tasks are empty, pinned apps keep the 2x2 icon grid visible and hide the
 *       "No recent apps" empty state container via {@code RecentTaskView.checkValues(List)}.</li>
 *   <li>Tapping a pinned app launches it directly on the cover screen via {@code PerformLaunchAction} (with
 *       the native FlipLauncher icon-expand transition and fallback to {@code Context.startActivity}).</li>
 * </ul>
 */
final class PinnedAppsWidget {
    private static final String TAG = "MF2C.PinnedWidget";
    private static final String RECENT_VIEW_CLASS = "com.miui.fliphome.widget.ui.RecentTaskView";
    private static final String RECENT_ADAPTER_CLASS = "com.miui.fliphome.widget.ui.RecentTaskView$RecentTaskAdapter";
    private static final String TASK_CLASS = "com.android.systemui.shared.recents.model.Task";
    private static final String TASK_KEY_CLASS = "com.android.systemui.shared.recents.model.Task$TaskKey";
    private static final String FLIP_APP_CLASS = "com.miui.fliphome.FlipApplication";
    private static final String PERFORM_LAUNCH_CLASS = "com.miui.fliphome.utils.PerformLaunchAction";
    private static final String DEVICE_CONFIG_CLASS = "com.miui.fliphome.utils.DeviceConfig";

    private static final String LAMBDA_APPLY = "lambda$initViews$2$com-miui-fliphome-widget-ui-RecentTaskView";
    private static final String LAMBDA_OBSERVE = "lambda$initViews$3$com-miui-fliphome-widget-ui-RecentTaskView";
    private static final String LAMBDA_CLICK = "lambda$onBindViewHolder$0";

    /** Explicit live-sync broadcast sent from MainActivity (identity shared) to com.miui.fliphome. */
    static final String ACTION_PINNED_WIDGET_SYNC = "io.github.jabe1122.mixflip2cover.PINNED_WIDGET_SYNC";

    /** Synthetic task IDs for pinned items are always <= -1000 so they never collide with real TaskKey.id >= 0. */
    static final int SYNTHETIC_TASK_ID_BASE = -1000;

    private static final Set<View> knownRecentViews =
            Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));
    private static final Map<View, List<Object>> rawRecentsByView = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<View, String> appliedSignatureByView = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ConcurrentHashMap<String, ResolvedApp> resolvedAppCache = new ConcurrentHashMap<>();
    private static final Map<View, PinnedClickListener> fallbackClickListeners =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile boolean syncReceiverRegistered;
    private static volatile boolean clickLambdaHooked;
    private static volatile java.lang.reflect.Field viewListenerInfoField;
    private static volatile java.lang.reflect.Field listenerInfoOnClickField;
    private static volatile boolean viewClickReflectionResolved;

    private static volatile Class<?> taskClass;
    private static volatile Class<?> taskKeyClass;
    private static volatile Constructor<?> taskCtor;
    private static volatile Constructor<?> taskKeyTaskInfoCtor;
    private static volatile Method checkValuesMethod;
    private static volatile Method submitListMethod;
    private static volatile Method flipAppGetLauncherMethod;
    private static volatile Constructor<?> performLaunchCtor;
    private static volatile Method performLaunchMethod;
    private static volatile Object deviceConfigInstance;
    private static volatile Method getClosingDisappearRectMethod;
    private static volatile Method folmeUseAtMethod;
    private static volatile Method folmeStateMethod;
    private static volatile Method folmeSetToMethod;

    private static final class ResolvedApp {
        final String packageName;
        final ComponentName component;
        final String label;
        final Drawable icon;

        ResolvedApp(String packageName, ComponentName component, String label) {
            this(packageName, component, label, null);
        }

        ResolvedApp(String packageName, ComponentName component, String label, Drawable icon) {
            this.packageName = packageName;
            this.component = component;
            this.label = label;
            this.icon = icon;
        }
    }

    private static final class PinnedClickListener implements View.OnClickListener {
        private final Object task;

        PinnedClickListener(Object task) {
            this.task = task;
        }

        @Override
        public void onClick(View v) {
            launchPinnedTask(task, v);
        }
    }

    private PinnedAppsWidget() {}

    static void install(XposedModule module, ClassLoader loader) {
        try {
            Class<?> recentViewClass = loader.loadClass(RECENT_VIEW_CLASS);
            Class<?> recentAdapterClass = loader.loadClass(RECENT_ADAPTER_CLASS);
            resolveReflectionHandles(loader, recentViewClass, recentAdapterClass);

            // 1. Capture raw recent tasks in lambda$initViews$3 before replacing mValues with pinned + recent tasks
            try {
                Method observeLambda = recentViewClass.getDeclaredMethod(LAMBDA_OBSERVE, recentAdapterClass, ArrayList.class);
                observeLambda.setAccessible(true);
                module.hook(observeLambda).intercept(chain -> {
                    Object res = chain.proceed();
                    try {
                        if (chain.getThisObject() instanceof View recentView) {
                            knownRecentViews.add(recentView);
                            ensureSyncReceiverRegistered(recentView.getContext());
                            captureRawRecentsFromField(recentView);
                            ModLog.i(TAG, "observeLambda enabled=" + ModuleConfig.pinnedWidgetEnabled()
                                    + " raw=" + ModuleConfig.pinnedWidgetAppsRaw());
                            if (ModuleConfig.hasPinnedWidgetApps()) {
                                List<Object> merged = buildMergedTaskList(recentView);
                                CoverGeometry.setField(recentView, "mValues", merged);
                                ModLog.i(TAG, "observeLambda merged size=" + merged.size());
                            }
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "observeLambda pinned merge skipped", t);
                    }
                    return res;
                });
            } catch (NoSuchMethodException e) {
                Log.w(TAG, "observeLambda hook unavailable", e);
            }

            // 2. Ensure mValues has pinned tasks right before lambda$initViews$2 runs checkValues + submitList
            try {
                Method applyLambda = recentViewClass.getDeclaredMethod(LAMBDA_APPLY, recentAdapterClass);
                applyLambda.setAccessible(true);
                module.hook(applyLambda).intercept(chain -> {
                    View targetView = chain.getThisObject() instanceof View v ? v : null;
                    try {
                        if (targetView != null) {
                            knownRecentViews.add(targetView);
                            ensureSyncReceiverRegistered(targetView.getContext());
                            if (ModuleConfig.hasPinnedWidgetApps()) {
                                captureRawRecentsIfOnlyReal(targetView);
                                List<Object> merged = buildMergedTaskList(targetView);
                                CoverGeometry.setField(targetView, "mValues", merged);
                                appliedSignatureByView.put(targetView, currentConfigSignature(targetView));
                                ModLog.i(TAG, "applyLambda merged size=" + merged.size());
                            } else if (SquareCards.active()) {
                                WidgetContentFit.truncateRecentListField(targetView);
                            }
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "applyLambda pinned prep skipped", t);
                    }
                    Object res = chain.proceed();
                    try {
                        if (targetView != null && CoverGeometry.field(targetView, "mValues") instanceof List<?> vals) {
                            syncEmptyStateVisibility(targetView, vals);
                        }
                    } catch (Throwable ignored) {
                    }
                    return res;
                });
            } catch (NoSuchMethodException e) {
                Log.w(TAG, "applyLambda hook unavailable", e);
            }

            // 3. Refresh pinned tasks on windowResume / present / onAttachedToWindow so config changes apply immediately
            try {
                Method sendCommand = recentViewClass.getDeclaredMethod("sendCommand", String.class);
                sendCommand.setAccessible(true);
                module.hook(sendCommand).intercept(chain -> {
                    Object res = chain.proceed();
                    try {
                        if (chain.getThisObject() instanceof View recentView
                                && chain.getArg(0) instanceof String cmd
                                && ("windowResume".equals(cmd) || "present".equals(cmd))) {
                            knownRecentViews.add(recentView);
                            ensureSyncReceiverRegistered(recentView.getContext());
                            refreshView(recentView, false);
                        }
                    } catch (Throwable ignored) {
                    }
                    return res;
                });
            } catch (NoSuchMethodException ignored) {
            }

            try {
                Method onAttached = recentViewClass.getDeclaredMethod("onAttachedToWindow");
                onAttached.setAccessible(true);
                module.hook(onAttached).intercept(chain -> {
                    Object res = chain.proceed();
                    try {
                        if (chain.getThisObject() instanceof View recentView) {
                            knownRecentViews.add(recentView);
                            ensureSyncReceiverRegistered(recentView.getContext());
                            refreshView(recentView, false);
                        }
                    } catch (Throwable ignored) {
                    }
                    return res;
                });
            } catch (NoSuchMethodException ignored) {
            }

            // 4. Intercept clicks on pinned items in RecentTaskAdapter.lambda$onBindViewHolder$0(Task, View)
            try {
                Method clickLambda = recentAdapterClass.getDeclaredMethod(LAMBDA_CLICK, taskClass, View.class);
                clickLambda.setAccessible(true);
                module.hook(clickLambda).intercept(chain -> {
                    try {
                        Object task = chain.getArg(0);
                        View clickedView = chain.getArg(1) instanceof View v ? v : null;
                        if (isPinnedTask(task)) {
                            launchPinnedTask(task, clickedView);
                            return null;
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "clickLambda intercept failed", t);
                    }
                    return chain.proceed();
                });
                clickLambdaHooked = true;
            } catch (NoSuchMethodException e) {
                clickLambdaHooked = false;
                Log.w(TAG, "clickLambda hook unavailable; onBindViewHolder click fallback will handle clicks");
            }

            // 5. Hook RecentTaskAdapter.onBindViewHolder to ensure fallback icon & click handler for pinned tasks
            for (Method m : recentAdapterClass.getDeclaredMethods()) {
                if (!"onBindViewHolder".equals(m.getName()) || m.isBridge() || m.getParameterCount() != 2) continue;
                m.setAccessible(true);
                module.hook(m).intercept(chain -> {
                    Object res = chain.proceed();
                    try {
                        Object adapter = chain.getThisObject();
                        Object holder = chain.getArg(0);
                        int pos = ((Number) chain.getArg(1)).intValue();
                        Object task = getAdapterItem(adapter, pos);
                        if (holder != null && task != null) {
                            ensureHolderIconAndClick(adapter, holder, task);
                        }
                    } catch (Throwable ignored) {
                    }
                    return res;
                });
                break;
            }
            ModLog.i(TAG, "pinned apps widget hooks ready");
        } catch (Throwable t) {
            Log.w(TAG, "PinnedAppsWidget install failed", t);
        }
    }

    private static void ensureSyncReceiverRegistered(Context context) {
        if (syncReceiverRegistered || context == null) return;
        synchronized (PinnedAppsWidget.class) {
            if (syncReceiverRegistered) return;
            try {
                Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
                ModLog.registerLogCollector(app, "com.miui.fliphome");
                Handler main = new Handler(Looper.getMainLooper());
                app.registerReceiver(new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent intent) {
                        if (intent == null || !ACTION_PINNED_WIDGET_SYNC.equals(intent.getAction())) return;
                        try {
                            int sender = getSentFromUid();
                            int moduleUid = c.getPackageManager().getPackageUid(
                                    BuildConfig.APPLICATION_ID,
                                    PackageManager.PackageInfoFlags.of(0));
                            if (sender < 0 || sender % 100000 != moduleUid % 100000) {
                                Log.w(TAG, "ignored pinned widget sync from uid " + sender);
                                return;
                            }
                            boolean enabled = intent.getBooleanExtra(ModuleConfig.KEY_PINNED_WIDGET_ENABLED, false);
                            String rawApps = intent.getStringExtra(ModuleConfig.KEY_PINNED_WIDGET_APPS);
                            ModuleConfig.overridePinnedWidget(enabled, rawApps != null ? rawApps : "");
                            ModLog.i(TAG, "received live pinned widget sync enabled=" + enabled + " raw=" + rawApps);
                            main.post(() -> refreshAllViews(true));
                        } catch (Throwable t) {
                            Log.w(TAG, "pinned widget sync receiver failed", t);
                        }
                    }
                }, new IntentFilter(ACTION_PINNED_WIDGET_SYNC), null, main, Context.RECEIVER_EXPORTED);
                IntentFilter pkgFilter = new IntentFilter();
                pkgFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
                pkgFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
                pkgFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
                pkgFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
                pkgFilter.addDataScheme("package");
                app.registerReceiver(new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent intent) {
                        String action = intent != null ? intent.getAction() : null;
                        String changedPkg = intent != null && intent.getData() != null
                                ? intent.getData().getSchemeSpecificPart()
                                : null;
                        if (Intent.ACTION_PACKAGE_CHANGED.equals(action)
                                && changedPkg != null
                                && !changedPkg.isEmpty()
                                && !isPackageRelevantToWidget(changedPkg)) {
                            resolvedAppCache.remove(changedPkg);
                            return;
                        }
                        if (changedPkg != null && !changedPkg.isEmpty()) {
                            resolvedAppCache.remove(changedPkg);
                        } else {
                            resolvedAppCache.clear();
                        }
                        appliedSignatureByView.clear();
                        ModLog.i(TAG, "package change detected (" + action
                                + (changedPkg != null ? ":" + changedPkg : "") + "), refreshing pinned apps");
                        main.post(() -> refreshAllViews(true));
                    }
                }, pkgFilter, null, main, Context.RECEIVER_NOT_EXPORTED);
                syncReceiverRegistered = true;
            } catch (Throwable t) {
                Log.w(TAG, "ensureSyncReceiverRegistered skipped", t);
            }
        }
    }

    static void refreshAllViews(boolean forceSubmit) {
        List<View> views;
        synchronized (knownRecentViews) {
            views = new ArrayList<>(knownRecentViews);
        }
        for (View v : views) {
            if (v != null) {
                refreshView(v, forceSubmit);
            }
        }
    }

    private static void resolveReflectionHandles(ClassLoader loader, Class<?> recentViewClass, Class<?> recentAdapterClass)
            throws ReflectiveOperationException {
        taskClass = loader.loadClass(TASK_CLASS);
        taskKeyClass = loader.loadClass(TASK_KEY_CLASS);
        taskCtor = taskClass.getDeclaredConstructor();
        taskCtor.setAccessible(true);
        try {
            taskKeyTaskInfoCtor = taskKeyClass.getDeclaredConstructor(android.app.TaskInfo.class);
            taskKeyTaskInfoCtor.setAccessible(true);
        } catch (NoSuchMethodException ignored) {
        }

        try {
            checkValuesMethod = recentViewClass.getDeclaredMethod("checkValues", List.class);
            checkValuesMethod.setAccessible(true);
        } catch (NoSuchMethodException ignored) {
        }

        try {
            Class<?> folmeClass = loader.loadClass("miuix.animation.Folme");
            Class<?> iFolmeClass = loader.loadClass("miuix.animation.IFolme");
            Class<?> iStateStyleClass = loader.loadClass("miuix.animation.IStateStyle");
            folmeUseAtMethod = folmeClass.getDeclaredMethod("useAt", View[].class);
            folmeUseAtMethod.setAccessible(true);
            folmeStateMethod = iFolmeClass.getDeclaredMethod("state");
            folmeStateMethod.setAccessible(true);
            folmeSetToMethod = iStateStyleClass.getDeclaredMethod("setTo", Object.class);
            folmeSetToMethod.setAccessible(true);
        } catch (Throwable ignored) {
        }

        Class<?> listAdapterClass = recentAdapterClass.getSuperclass();
        if (listAdapterClass != null) {
            try {
                submitListMethod = listAdapterClass.getDeclaredMethod("submitList", List.class);
                submitListMethod.setAccessible(true);
            } catch (NoSuchMethodException ignored) {
            }
        }

        try {
            Class<?> flipAppClass = loader.loadClass(FLIP_APP_CLASS);
            flipAppGetLauncherMethod = flipAppClass.getDeclaredMethod("getLauncher");
            flipAppGetLauncherMethod.setAccessible(true);
        } catch (Throwable ignored) {
        }

        try {
            Class<?> performLaunchClass = loader.loadClass(PERFORM_LAUNCH_CLASS);
            performLaunchCtor = performLaunchClass.getDeclaredConstructor(Activity.class);
            performLaunchCtor.setAccessible(true);
            performLaunchMethod = performLaunchClass.getDeclaredMethod("launch", Intent.class, Object.class, View.class);
            performLaunchMethod.setAccessible(true);
        } catch (Throwable ignored) {
        }

        try {
            Class<?> deviceConfigClass = loader.loadClass(DEVICE_CONFIG_CLASS);
            java.lang.reflect.Field f = deviceConfigClass.getDeclaredField("INSTANCE");
            f.setAccessible(true);
            deviceConfigInstance = f.get(null);
            getClosingDisappearRectMethod = deviceConfigClass.getDeclaredMethod("getClosingAppDefaultDisappearRect");
            getClosingDisappearRectMethod.setAccessible(true);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Called from {@link WidgetContentFit#adaptRecent(View)} and {@link ModuleConfig} change listeners.
     */
    static void refreshView(View recentView, boolean forceSubmit) {
        if (recentView == null) return;
        try {
            knownRecentViews.add(recentView);
            ensureSyncReceiverRegistered(recentView.getContext());
            captureRawRecentsIfOnlyReal(recentView);
            boolean enabled = ModuleConfig.hasPinnedWidgetApps();
            String sig = currentConfigSignature(recentView);
            String prevSig = appliedSignatureByView.get(recentView);

            if (!enabled) {
                if (prevSig != null) {
                    appliedSignatureByView.remove(recentView);
                    List<Object> restored = getRawRecentsCopy(recentView);
                    int maxItems = SquareCards.active() ? 4 : 6;
                    if (restored.size() > maxItems) {
                        restored = new ArrayList<>(restored.subList(0, maxItems));
                    }
                    applyTaskListToView(recentView, restored);
                }
                return;
            }

            if (!forceSubmit && sig.equals(prevSig)) {
                Object currentValues = CoverGeometry.field(recentView, "mValues");
                if (currentValues instanceof List<?> list && !list.isEmpty() && containsAnyPinnedTask(list)) {
                    syncEmptyStateVisibility(recentView, list);
                    return;
                }
            }

            List<Object> merged = buildMergedTaskList(recentView);
            appliedSignatureByView.put(recentView, sig);
            ModLog.i(TAG, "refreshView applying merged size=" + merged.size() + " sig=" + sig);
            applyTaskListToView(recentView, merged);
        } catch (Throwable t) {
            Log.w(TAG, "refreshView failed", t);
        }
    }

    /**
     * Filters or replaces the list passed into {@code RecentTaskAdapter.submitList(List)}.
     */
    static List<?> filterSubmitList(Object adapter, List<?> incomingList) {
        try {
            Object recentViewObj = CoverGeometry.field(adapter, "this$0");
            if (recentViewObj instanceof View recentView && ModuleConfig.hasPinnedWidgetApps()) {
                knownRecentViews.add(recentView);
                ensureSyncReceiverRegistered(recentView.getContext());
                if (incomingList != null && !containsAnyPinnedTask(incomingList)) {
                    storeRawRecents(recentView, incomingList);
                }
                List<Object> merged = buildMergedTaskList(recentView);
                CoverGeometry.setField(recentView, "mValues", merged);
                syncEmptyStateVisibility(recentView, merged);
                appliedSignatureByView.put(recentView, currentConfigSignature(recentView));
                ModLog.i(TAG, "filterSubmitList incoming=" + (incomingList == null ? -1 : incomingList.size())
                        + " merged=" + merged.size());
                return merged;
            }
        } catch (Throwable t) {
            Log.w(TAG, "filterSubmitList failed", t);
        }
        if (incomingList != null && incomingList.size() > 4 && SquareCards.active()) {
            return new ArrayList<>(incomingList.subList(0, 4));
        }
        return incomingList;
    }

    private static void applyTaskListToView(View recentView, List<Object> list) {
        try {
            CoverGeometry.setField(recentView, "mValues", list);
            syncEmptyStateVisibility(recentView, list);
            Object rvObj = CoverGeometry.field(recentView, "mRecyclerView");
            if (rvObj instanceof ViewGroup rv) {
                Object adapter = CoverGeometry.invoke(rv, "getAdapter", CoverGeometry.NO_PARAMS);
                if (adapter != null && submitListMethod != null) {
                    submitListMethod.invoke(adapter, list);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyTaskListToView failed", t);
        }
    }

    /**
     * Synchronizes {@code RecentTaskView}'s empty-state container ({@code mNoDataContainer}) and icon grid
     * ({@code mRecyclerView}) both through {@code Folme.state().setTo(...)} and direct View properties.
     * This prevents {@code checkValues(List)} from getting stuck when called before {@code RecentTaskView} is
     * attached to a window.
     */
    private static void syncEmptyStateVisibility(View recentView, List<?> list) {
        if (recentView == null) return;
        boolean hasItems = list != null && !list.isEmpty();
        try {
            if (checkValuesMethod != null) {
                checkValuesMethod.invoke(recentView, list);
            }
        } catch (Throwable ignored) {
        }
        try {
            CoverGeometry.setField(recentView, "mIsShowRecycler", hasItems);
            Object rvObj = CoverGeometry.field(recentView, "mRecyclerView");
            Object noDataObj = CoverGeometry.field(recentView, "mNoDataContainer");
            Object showState = CoverGeometry.field(recentView, "mShowState");
            Object hideState = CoverGeometry.field(recentView, "mHideState");

            if (folmeUseAtMethod != null && folmeStateMethod != null && folmeSetToMethod != null
                    && showState != null && hideState != null) {
                if (noDataObj instanceof View noData) {
                    Object iFolme = folmeUseAtMethod.invoke(null, (Object) new View[]{noData});
                    Object iState = folmeStateMethod.invoke(iFolme);
                    folmeSetToMethod.invoke(iState, hasItems ? hideState : showState);
                }
                if (rvObj instanceof View rv) {
                    Object iFolme = folmeUseAtMethod.invoke(null, (Object) new View[]{rv});
                    Object iState = folmeStateMethod.invoke(iFolme);
                    folmeSetToMethod.invoke(iState, hasItems ? showState : hideState);
                }
            }

            if (noDataObj instanceof View noData) {
                if (hasItems) {
                    noData.setAlpha(0f);
                    noData.setScaleX(0.95f);
                    noData.setScaleY(0.95f);
                    noData.setVisibility(View.INVISIBLE);
                } else {
                    noData.setVisibility(View.VISIBLE);
                    noData.setAlpha(1f);
                    noData.setScaleX(1f);
                    noData.setScaleY(1f);
                }
            }
            if (rvObj instanceof View rv) {
                if (hasItems) {
                    rv.setVisibility(View.VISIBLE);
                    rv.setAlpha(1f);
                    rv.setScaleX(1f);
                    rv.setScaleY(1f);
                } else {
                    rv.setAlpha(0f);
                    rv.setScaleX(0.95f);
                    rv.setScaleY(0.95f);
                    rv.setVisibility(View.INVISIBLE);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "syncEmptyStateVisibility fallback failed", t);
        }
    }

    private static String currentConfigSignature(View recentView) {
        StringBuilder sb = new StringBuilder();
        sb.append(ModuleConfig.pinnedWidgetEnabled())
                .append(':')
                .append(ModuleConfig.pinnedWidgetAppsRaw())
                .append(':')
                .append(SquareCards.active());
        if (recentView != null) {
            List<Object> raw = rawRecentsByView.get(recentView);
            if (raw != null && !raw.isEmpty()) {
                sb.append('|');
                int count = 0;
                for (Object item : raw) {
                    if (item == null || isPinnedTask(item)) continue;
                    if (count > 0) sb.append(',');
                    sb.append(extractTaskToken(item));
                    if (++count >= ModuleConfig.MAX_PINNED_WIDGET_APPS) break;
                }
            }
        }
        return sb.toString();
    }

    private static String extractTaskToken(Object task) {
        if (task == null) return "";
        String pkg = extractPackageName(task);
        try {
            Object key = getField(task, "key");
            if (key != null) {
                Object idObj = getField(key, "id");
                if (idObj instanceof Number n) {
                    return n.intValue() + "@" + (pkg != null ? pkg : "");
                }
            }
        } catch (Throwable ignored) {
        }
        return pkg != null ? pkg : String.valueOf(System.identityHashCode(task));
    }

    private static boolean isPackageRelevantToWidget(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        if (ModuleConfig.pinnedWidgetApps().contains(pkg)) return true;
        List<View> views;
        synchronized (knownRecentViews) {
            views = new ArrayList<>(knownRecentViews);
        }
        for (View v : views) {
            if (v == null) continue;
            List<Object> raw = rawRecentsByView.get(v);
            if (raw != null) {
                for (Object item : raw) {
                    if (pkg.equals(extractPackageName(item))) return true;
                }
            }
            try {
                Object vals = CoverGeometry.field(v, "mValues");
                if (vals instanceof List<?> list) {
                    for (Object item : list) {
                        if (pkg.equals(extractPackageName(item))) return true;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static void captureRawRecentsFromField(View recentView) {
        try {
            Object raw = CoverGeometry.field(recentView, "mValues");
            if (raw instanceof List<?> list) {
                storeRawRecents(recentView, list);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void captureRawRecentsIfOnlyReal(View recentView) {
        try {
            Object raw = CoverGeometry.field(recentView, "mValues");
            if (raw instanceof List<?> list && !containsAnyPinnedTask(list)) {
                storeRawRecents(recentView, list);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void storeRawRecents(View recentView, List<?> list) {
        if (recentView == null || list == null) return;
        List<Object> clean = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item != null && !isPinnedTask(item)) {
                clean.add(item);
            }
        }
        rawRecentsByView.put(recentView, clean);
    }

    private static List<Object> getRawRecentsCopy(View recentView) {
        List<Object> saved = rawRecentsByView.get(recentView);
        if (saved != null) return new ArrayList<>(saved);
        try {
            Object raw = CoverGeometry.field(recentView, "mValues");
            if (raw instanceof List<?> list) {
                List<Object> clean = new ArrayList<>();
                for (Object item : list) {
                    if (item != null && !isPinnedTask(item)) clean.add(item);
                }
                return clean;
            }
        } catch (Throwable ignored) {
        }
        return new ArrayList<>();
    }

    /**
     * Builds the up-to-4 task list combining user-pinned apps (in exact order) and non-duplicate recent tasks.
     */
    static List<Object> buildMergedTaskList(View recentView) {
        Context context = recentView != null ? recentView.getContext() : null;
        if (context == null) {
            Object appObj = CoverGeometry.application();
            if (appObj instanceof Context c) context = c;
        }
        List<String> pinnedPkgs = ModuleConfig.pinnedWidgetApps();
        List<Object> rawRecents = getRawRecentsCopy(recentView);
        if (pinnedPkgs.isEmpty() || context == null) {
            int max = SquareCards.active() ? 4 : 6;
            return rawRecents.size() > max ? new ArrayList<>(rawRecents.subList(0, max)) : rawRecents;
        }

        List<Object> result = new ArrayList<>(ModuleConfig.MAX_PINNED_WIDGET_APPS);
        LinkedHashSet<String> includedPackages = new LinkedHashSet<>();

        for (int i = 0; i < pinnedPkgs.size() && result.size() < ModuleConfig.MAX_PINNED_WIDGET_APPS; i++) {
            String pkg = pinnedPkgs.get(i);
            ResolvedApp app = resolveApp(context, pkg);
            if (app == null) continue;
            Object syntheticTask = createSyntheticTask(app, i);
            if (syntheticTask != null) {
                result.add(syntheticTask);
                includedPackages.add(pkg);
            }
        }

        // Fill remaining slots (up to 4) with non-duplicate recent tasks
        for (Object recentTask : rawRecents) {
            if (result.size() >= ModuleConfig.MAX_PINNED_WIDGET_APPS) break;
            if (recentTask == null || isPinnedTask(recentTask)) continue;
            String pkg = extractPackageName(recentTask);
            if (pkg != null && includedPackages.contains(pkg)) continue;
            result.add(recentTask);
            if (pkg != null) includedPackages.add(pkg);
        }

        return result;
    }

    private static ResolvedApp resolveApp(Context context, String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        ResolvedApp cached = resolvedAppCache.get(pkg);
        if (cached != null) return cached;
        try {
            PackageManager pm = context.getPackageManager();
            if (pm == null) return null;

            ComponentName component = null;
            String label = pkg;
            Drawable icon = null;

            Intent launchIntent = pm.getLaunchIntentForPackage(pkg);
            if (launchIntent != null && launchIntent.getComponent() != null) {
                component = launchIntent.getComponent();
            }
            if (component == null) {
                Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg);
                List<ResolveInfo> infos = pm.queryIntentActivities(query, 0);
                if (infos != null && !infos.isEmpty() && infos.get(0).activityInfo != null) {
                    component = new ComponentName(
                            infos.get(0).activityInfo.packageName,
                            infos.get(0).activityInfo.name);
                    CharSequence l = infos.get(0).loadLabel(pm);
                    if (l != null && l.length() > 0) label = l.toString();
                    try {
                        icon = infos.get(0).loadIcon(pm);
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (component == null) {
                Log.w(TAG, "resolveApp found no launcher component for " + pkg);
                return null;
            }

            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                CharSequence appLabel = pm.getApplicationLabel(ai);
                if (appLabel != null && appLabel.length() > 0) {
                    label = appLabel.toString();
                }
                if (icon == null) {
                    icon = pm.getApplicationIcon(ai);
                }
            } catch (Throwable ignored) {
            }
            if (icon == null) {
                try {
                    icon = pm.getApplicationIcon(pkg);
                } catch (Throwable ignored) {
                }
            }

            ResolvedApp resolved = new ResolvedApp(pkg, component, label, icon);
            resolvedAppCache.put(pkg, resolved);
            return resolved;
        } catch (Throwable t) {
            Log.w(TAG, "resolveApp failed for " + pkg, t);
            return null;
        }
    }

    private static int currentUserId() {
        try {
            return Process.myUid() / 100000;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    static Object createSyntheticTask(ResolvedApp app, int slotIndex) {
        if (app == null || taskCtor == null || taskKeyClass == null) return null;
        try {
            int userId = currentUserId();
            int syntheticId = computeSyntheticTaskId(app.component, slotIndex);
            Intent baseIntent = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(app.component)
                    .setPackage(app.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

            Object taskKey = null;
            if (taskKeyTaskInfoCtor != null) {
                try {
                    ActivityManager.RunningTaskInfo ti = new ActivityManager.RunningTaskInfo();
                    ti.taskId = syntheticId;
                    ti.baseIntent = baseIntent;
                    ti.topActivity = app.component;
                    try {
                        setField(ti, "userId", userId);
                        setField(ti, "stackId", syntheticId);
                    } catch (Throwable ignored) {
                    }
                    taskKey = taskKeyTaskInfoCtor.newInstance(ti);
                } catch (Throwable ignored) {
                    try {
                        taskKey = taskKeyTaskInfoCtor.newInstance(new Object[]{null});
                    } catch (Throwable ignored2) {
                        taskKey = null;
                    }
                }
            }
            if (taskKey == null) {
                try {
                    Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
                    java.lang.reflect.Field f = unsafeClass.getDeclaredField("theUnsafe");
                    f.setAccessible(true);
                    Object unsafe = f.get(null);
                    Method alloc = unsafeClass.getMethod("allocateInstance", Class.class);
                    taskKey = alloc.invoke(unsafe, taskKeyClass);
                } catch (Throwable ignored) {
                }
            }
            if (taskKey == null) {
                return null;
            }

            // Ensure fields and mHashCode are deterministic so DiffUtil detects changes per slot & app
            setField(taskKey, "id", syntheticId);
            setField(taskKey, "stackId", syntheticId);
            setField(taskKey, "userId", userId);
            setField(taskKey, "baseIntent", baseIntent);
            setField(taskKey, "topActivity", app.component);
            setField(taskKey, "mHashCode", Objects.hash(syntheticId, syntheticId, 0, userId));

            Object task = taskCtor.newInstance();
            setField(task, "key", taskKey);
            setField(task, "title", app.label);
            setField(task, "titleDescription", app.label);
            setField(task, "topActivity", app.component);
            setField(task, "baseActivity", app.component);
            if (app.icon != null) {
                try {
                    Drawable taskIcon = cloneDrawable(app.icon);
                    if (taskIcon != null) {
                        setField(task, "icon", taskIcon);
                    }
                } catch (Throwable ignored) {
                }
            }
            return task;
        } catch (Throwable t) {
            Log.w(TAG, "createSyntheticTask failed for " + app.packageName, t);
            return null;
        }
    }

    private static Object getField(Object obj, String name) throws ReflectiveOperationException {
        if (obj == null) return null;
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void setField(Object obj, String name, Object value) throws ReflectiveOperationException {
        if (obj == null) return;
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(obj, value);
                return;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    static int computeSyntheticTaskId(ComponentName component, int slotIndex) {
        int hash = component != null ? (component.flattenToShortString().hashCode() & 0x0FFFFFFF) : 0;
        return SYNTHETIC_TASK_ID_BASE - (slotIndex * 1000) - (hash % 500000000);
    }

    static boolean isPinnedTask(Object task) {
        if (task == null) return false;
        try {
            Object key = getField(task, "key");
            if (key == null) return false;
            int id = ((Number) getField(key, "id")).intValue();
            return id <= SYNTHETIC_TASK_ID_BASE;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean containsAnyPinnedTask(List<?> list) {
        if (list == null) return false;
        for (Object item : list) {
            if (isPinnedTask(item)) return true;
        }
        return false;
    }

    private static ComponentName extractComponent(Object task) {
        if (task == null) return null;
        try {
            Object top = getField(task, "topActivity");
            if (top instanceof ComponentName cn) return cn;
            Object key = getField(task, "key");
            if (key != null) {
                Object keyTop = getField(key, "topActivity");
                if (keyTop instanceof ComponentName cn) return cn;
                Object baseIntent = getField(key, "baseIntent");
                if (baseIntent instanceof Intent intent && intent.getComponent() != null) {
                    return intent.getComponent();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String extractPackageName(Object task) {
        ComponentName cn = extractComponent(task);
        return cn != null ? cn.getPackageName() : null;
    }

    private static Object getAdapterItem(Object adapter, int position) {
        if (adapter == null || position < 0) return null;
        try {
            Method getItem = adapter.getClass().getSuperclass().getDeclaredMethod("getItem", int.class);
            getItem.setAccessible(true);
            return getItem.invoke(adapter, position);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Drawable cloneDrawable(Drawable src) {
        if (src == null) return null;
        try {
            Drawable.ConstantState cs = src.getConstantState();
            if (cs != null) {
                return cs.newDrawable().mutate();
            }
        } catch (Throwable ignored) {
        }
        return src;
    }

    private static View.OnClickListener getCurrentClickListener(View v) {
        if (v == null) return null;
        if (!viewClickReflectionResolved) {
            viewClickReflectionResolved = true;
            try {
                java.lang.reflect.Field liField = View.class.getDeclaredField("mListenerInfo");
                liField.setAccessible(true);
                Class<?> liClass = Class.forName("android.view.View$ListenerInfo");
                java.lang.reflect.Field clickField = liClass.getDeclaredField("mOnClickListener");
                clickField.setAccessible(true);
                viewListenerInfoField = liField;
                listenerInfoOnClickField = clickField;
            } catch (Throwable ignored) {
            }
        }
        try {
            if (viewListenerInfoField != null && listenerInfoOnClickField != null) {
                Object li = viewListenerInfoField.get(v);
                if (li != null) {
                    Object listener = listenerInfoOnClickField.get(li);
                    if (listener instanceof View.OnClickListener l) return l;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void ensureHolderIconAndClick(Object adapter, Object holder, Object task) {
        try {
            Object ivObj = CoverGeometry.field(holder, "ivIcon");
            String pkg = extractPackageName(task);
            boolean pinned = isPinnedTask(task);
            if (ivObj instanceof ImageView iv) {
                Object ctxObj = CoverGeometry.field(adapter, "mContext");
                Context ctx = ctxObj instanceof Context c ? c : iv.getContext();
                if (pinned) {
                    Drawable pinnedIcon = null;
                    if (pkg != null && ctx != null) {
                        ResolvedApp resolved = resolveApp(ctx, pkg);
                        if (resolved != null && resolved.icon != null) {
                            pinnedIcon = cloneDrawable(resolved.icon);
                        }
                        if (pinnedIcon == null) {
                            try {
                                pinnedIcon = ctx.getPackageManager().getApplicationIcon(pkg);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                    if (pinnedIcon != null) {
                        iv.setImageDrawable(pinnedIcon);
                    }
                } else if (iv.getDrawable() == null && pkg != null && ctx != null) {
                    try {
                        Drawable fallbackIcon = ctx.getPackageManager().getApplicationIcon(pkg);
                        if (fallbackIcon != null) iv.setImageDrawable(fallbackIcon);
                    } catch (Throwable ignored) {
                    }
                }
                ModLog.i(TAG, "onBindViewHolder pkg=" + pkg + " pinned=" + pinned
                        + " hasIcon=" + (iv.getDrawable() != null));
            }
            Object itemViewObj = CoverGeometry.field(holder, "itemView");
            if (itemViewObj instanceof View itemView) {
                if (pinned && !clickLambdaHooked) {
                    PinnedClickListener listener = new PinnedClickListener(task);
                    fallbackClickListeners.put(itemView, listener);
                    itemView.setOnClickListener(listener);
                } else if (!pinned) {
                    PinnedClickListener prev = fallbackClickListeners.remove(itemView);
                    View.OnClickListener current = getCurrentClickListener(itemView);
                    if (current instanceof PinnedClickListener || (prev != null && current == null)) {
                        itemView.setOnClickListener(null);
                        itemView.setClickable(false);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "ensureHolderIconAndClick failed", t);
        }
    }

    static void launchPinnedTask(Object task, View clickedView) {
        ComponentName cn = extractComponent(task);
        if (cn == null) return;
        String pkg = cn.getPackageName();

        Intent launchIntent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(cn)
                .setPackage(pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            launchIntent.putExtra("profile", Process.myUserHandle());
        } catch (Throwable ignored) {
        }

        // Primary path: FlipHome's PerformLaunchAction(FlipLauncher) for native cover launcher transition & gesture state
        try {
            Object launcher = flipAppGetLauncherMethod != null ? flipAppGetLauncherMethod.invoke(null) : null;
            if (launcher instanceof Activity activity && performLaunchCtor != null && performLaunchMethod != null) {
                Object action = performLaunchCtor.newInstance(activity);
                performLaunchMethod.invoke(action, launchIntent, null, clickedView);
                ModLog.i(TAG, "launched pinned app via PerformLaunchAction: " + cn.flattenToShortString());
                return;
            }
            if (launcher instanceof Activity activity) {
                BundleHolder opts = buildFlipLaunchOptions(activity, clickedView);
                if (opts != null && opts.bundle != null) {
                    activity.startActivity(launchIntent, opts.bundle);
                } else {
                    activity.startActivity(launchIntent);
                }
                ModLog.i(TAG, "launched pinned app via FlipLauncher: " + cn.flattenToShortString());
                return;
            }
        } catch (Throwable t) {
            Log.w(TAG, "PerformLaunchAction failed, falling back to Context.startActivity", t);
        }

        // Fallback path: direct Context.startActivity
        try {
            Context ctx = clickedView != null ? clickedView.getContext() : null;
            if (ctx == null) {
                Object appObj = CoverGeometry.application();
                if (appObj instanceof Context c) ctx = c;
            }
            if (ctx != null) {
                PackageManager pm = ctx.getPackageManager();
                Intent fallback = pm != null ? pm.getLaunchIntentForPackage(pkg) : null;
                if (fallback == null) fallback = launchIntent;
                else fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                ctx.startActivity(fallback);
            }
        } catch (Throwable t) {
            Log.e(TAG, "failed to launch pinned app: " + pkg, t);
        }
    }

    private static final class BundleHolder {
        final android.os.Bundle bundle;
        BundleHolder(android.os.Bundle bundle) { this.bundle = bundle; }
    }

    private static BundleHolder buildFlipLaunchOptions(Activity launcher, View view) {
        try {
            Rect rect = null;
            if (deviceConfigInstance != null && getClosingDisappearRectMethod != null) {
                Object r = getClosingDisappearRectMethod.invoke(deviceConfigInstance);
                if (r instanceof Rect rc) rect = rc;
            }
            Method getOpts = launcher.getClass().getMethod("getActivityLaunchOptions", View.class, Rect.class);
            Object opts = getOpts.invoke(launcher, view, rect);
            if (opts instanceof ActivityOptions ao) {
                return new BundleHolder(ao.toBundle());
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
