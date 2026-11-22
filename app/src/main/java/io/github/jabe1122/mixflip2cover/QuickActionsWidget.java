package io.github.jabe1122.mixflip2cover;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.BroadcastOptions;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import io.github.libxposed.api.XposedModule;

/**
 * Standalone 2x2 customizable Quick Actions cover-screen widget ({@code mf2cQuickActionsView})
 * integrated natively into {@code com.miui.fliphome}'s widget stack ({@code WidgetPagerView}).
 *
 * <p>Supports per-slot assignment of Home, Recents, Lock Screen, Back, Notification Shade,
 * Control Center, Flashlight, Screenshot, Sound Mode, or any installed app shortcut, matching
 * the soft-matte frosted square card aesthetics of {@link WidgetContentFit} and {@link SquareCards}.
 */
final class QuickActionsWidget {
    private static final String TAG = "MF2C.QuickWidget";

    static final String ACTION_QUICK_WIDGET_SYNC = "io.github.jabe1122.mixflip2cover.QUICK_WIDGET_SYNC";
    static final String ACTION_TRIGGER_RECENTS = "io.github.jabe1122.mixflip2cover.TRIGGER_RECENTS";
    static final String EXTRA_EXPLICIT_TOGGLE = "explicit_toggle";
    static final String QUICK_WIDGET_FILE_NAME = "mf2cQuickActionsView";
    private static final String RECENT_WIDGET_FILE_NAME = "recentTaskView";
    private static final String QUICK_WIDGET_TYPE_TAG = "theme_recent";

    private static final String PREFS_STATE = "mf2c_quick_widget_state";
    private static final String KEY_AUTO_SEEDED = "auto_seeded";
    private static final String LIGHT_PREVIEW_FILE = "mf2c_quick_preview_light.png";
    private static final String DARK_PREVIEW_FILE = "mf2c_quick_preview_dark.png";
    private static final int MAX_WIDGET_LIMIT_WITH_QUICK = 6;

    private static final PathInterpolator PRESS_INTERPOLATOR = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final ExecutorService PREVIEW_IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mf2c-quick-widget-io");
        t.setDaemon(true);
        return t;
    });

    private static final Set<View> knownHostViews =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final Set<QuickActionsGridView> knownGridViews =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private static volatile WeakReference<Object> lastWidgetModelRef;
    private static volatile Class<?> widgetInfoClass;
    private static volatile Constructor<?> widgetInfoCtor;
    private static volatile Constructor<?> hostTwoArgCtor;
    private static volatile Method modelGetInstanceMethod;
    private static volatile Method modelInitShowListMethod;
    private static volatile Method modelUpdateShowListMethod;
    private static volatile Method modelDoUpdateEntityMethod;
    private static volatile Method flipAppGetLauncherMethod;
    private static volatile Class<?> launcherStateClass;
    private static volatile Object normalStateObj;
    private static volatile Object overviewStateObj;
    private static volatile Method overviewOnStateEnabledMethod;
    private static volatile Method getStateManagerMethod;
    private static volatile Method goToStateMethod;
    private static volatile Method exitOverviewStateIfNeedMethod;
    private static volatile Method isInStateMethod;
    private static volatile Method performHomeToHomeMethod;
    private static volatile Method getRecentsViewMethod;
    private static volatile Method getRecentsContainerMethod;
    private static volatile Method getShortcutMenuLayerMethod;
    private static volatile Method recentsModelGetInstanceMethod;
    private static volatile Method recentsModelPreloadMethod;
    private static volatile Method recentsContainerOnBackMethod;
    private static volatile Method recentsContainerDismissHomeMethod;
    private static volatile Method recentsContainerFadeInMethod;
    private static volatile Method recentsContainerBgFadeInMethod;
    private static volatile Method recentsViewGetTaskViewsMethod;
    private static volatile Method springUtilsGetInstanceMethod;
    private static volatile Method startToRecentsAnimMethod;
    private static volatile Object shortcutLayerElementInstance;
    private static volatile Method shortcutLayerAnimToMethod;
    private static volatile Object shortcutLayerParamsCompanion;
    private static volatile Method shortcutLayerRecentParamsMethod;
    private static volatile Class<?> onResumeCallbackInterface;
    private static volatile Method addOnResumeCallbackMethod;

    private static final Object RECEIVER_LOCK = new Object();
    private static volatile boolean syncReceiverRegistered;
    private static volatile Boolean lastKnownEnabledState;
    private static volatile String lastPreviewSignature = "";

    // Shared live hardware state for Torch, Ringer, Rotation Lock & Brightness tiles
    private static volatile boolean torchCallbackRegistered;
    private static volatile String backFlashCameraId;
    private static volatile boolean torchEnabled;
    private static volatile int currentRingerMode = AudioManager.RINGER_MODE_NORMAL;
    private static volatile boolean rotationLocked;
    private static volatile boolean brightnessAuto = true;
    private static volatile int brightnessLevel;

    private static final class GlyphScratch {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();
        final RectF rect = new RectF();
    }

    private static final ThreadLocal<GlyphScratch> GLYPH_SCRATCH = ThreadLocal.withInitial(GlyphScratch::new);

    private QuickActionsWidget() {}

    static boolean isQuickWidgetHost(View view) {
        return view != null && knownHostViews.contains(view);
    }

    static boolean isPackageRelevantToQuickWidget(String pkg) {
        if (pkg == null || pkg.isEmpty() || !ModuleConfig.quickWidgetEnabled()) return false;
        for (String slot : ModuleConfig.quickWidgetSlots()) {
            if (slot != null && slot.startsWith(ModuleConfig.QA_APP_PREFIX)
                    && pkg.equals(ModuleConfig.extractAppTargetPackage(slot))) {
                return true;
            }
        }
        return false;
    }

    static void onPackageChanged(String pkg) {
        if (pkg == null || pkg.isEmpty() || isPackageRelevantToQuickWidget(pkg)) {
            refreshAllGrids();
        }
    }

    static void onThemeUpdated(boolean effectiveDark) {
        List<QuickActionsGridView> grids;
        synchronized (knownGridViews) {
            grids = new ArrayList<>(knownGridViews);
        }
        for (QuickActionsGridView g : grids) {
            if (g != null) {
                g.onThemeChanged(effectiveDark);
            }
        }
    }

    static void install(XposedModule module, ClassLoader loader) {
        try {
            resolveReflectionHandles(loader);
            new Handler(Looper.getMainLooper()).post(() -> ensureSyncReceiverRegistered(CoverGeometry.application()));

            // 1. Intercept FlipMaMlWidgetCompat.createMamlHostView(Context, FlipWidgetInfo)
            Class<?> compatClass = loader.loadClass("com.miui.fliphome.widget.ui.maml.FlipMaMlWidgetCompat");
            Method createHost = compatClass.getDeclaredMethod("createMamlHostView", Context.class, widgetInfoClass);
            createHost.setAccessible(true);
            module.hook(createHost).intercept(chain -> {
                try {
                    Object info = chain.getArg(1);
                    if (chain.getArg(0) instanceof Context ctx && isQuickWidgetInfo(info)) {
                        ensureSyncReceiverRegistered(ctx);
                        View hostView = buildQuickActionsHostView(ctx, info);
                        if (hostView != null) {
                            return hostView;
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "createMamlHostView intercept failed", t);
                }
                return chain.proceed();
            });

            // 2. Append QuickActionsWidget to FlipWatchDefaultConfig.loadAllWidget & loadDefaultWidget
            Class<?> defaultCfgClass = loader.loadClass("com.miui.fliphome.widget.model.FlipWatchDefaultConfig");
            for (String methodName : new String[]{"loadAllWidget", "loadDefaultWidget"}) {
                try {
                    Method loadMethod = defaultCfgClass.getDeclaredMethod(methodName);
                    loadMethod.setAccessible(true);
                    module.hook(loadMethod).intercept(chain -> {
                        Object res = chain.proceed();
                        try {
                            if (ModuleConfig.quickWidgetEnabled() && res instanceof List<?> rawList) {
                                Context ctx = resolveAppContext(chain.getThisObject());
                                if (ctx != null) ensureSyncReceiverRegistered(ctx);
                                if (!containsQuickWidget(rawList)) {
                                    @SuppressWarnings("unchecked")
                                    List<Object> mutable = (List<Object>) res;
                                    Object quickInfo = createQuickWidgetInfo(ctx, mutable.size());
                                    if (quickInfo != null) {
                                        mutable.add(quickInfo);
                                    }
                                } else {
                                    refreshQuickWidgetMetadataInList(ctx, rawList);
                                }
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, methodName + " hook failed", t);
                        }
                        return res;
                    });
                } catch (NoSuchMethodException ignored) {
                }
            }

            // 3. Hook FlipWidgetModel for auto-seeding, dynamic enable/disable filtering, and 6-widget persistence
            Class<?> modelClass = loader.loadClass("com.miui.fliphome.widget.model.FlipWidgetModel");
            try {
                modelGetInstanceMethod = modelClass.getDeclaredMethod("getInstance");
                modelGetInstanceMethod.setAccessible(true);
            } catch (NoSuchMethodException ignored) {
            }
            try {
                modelInitShowListMethod = modelClass.getDeclaredMethod("initShowList");
                modelInitShowListMethod.setAccessible(true);
            } catch (NoSuchMethodException ignored) {
            }
            modelUpdateShowListMethod = modelClass.getDeclaredMethod("updateShowList", List.class);
            modelUpdateShowListMethod.setAccessible(true);
            modelDoUpdateEntityMethod = modelClass.getDeclaredMethod("doUpdateWidgetEntity", List.class);
            modelDoUpdateEntityMethod.setAccessible(true);

            for (Constructor<?> ctor : modelClass.getDeclaredConstructors()) {
                ctor.setAccessible(true);
                module.hook(ctor).intercept(chain -> {
                    Object res = chain.proceed();
                    try {
                        Object model = chain.getThisObject();
                        if (model != null) {
                            lastWidgetModelRef = new WeakReference<>(model);
                            Context ctx = resolveAppContext(model);
                            if (ctx != null) ensureSyncReceiverRegistered(ctx);
                        }
                    } catch (Throwable ignored) {
                    }
                    return res;
                });
            }

            Method getAddedBlocked = modelClass.getDeclaredMethod("getAddedListBlocked");
            getAddedBlocked.setAccessible(true);
            module.hook(getAddedBlocked).intercept(chain -> {
                Object res = chain.proceed();
                try {
                    Object model = chain.getThisObject();
                    if (model != null) lastWidgetModelRef = new WeakReference<>(model);
                    if (!(res instanceof List<?> rawList)) return res;
                    Context ctx = resolveAppContext(model);
                    if (ctx != null) ensureSyncReceiverRegistered(ctx);

                    boolean enabled = ModuleConfig.quickWidgetEnabled();
                    lastKnownEnabledState = enabled;
                    if (!enabled) {
                        if (!containsQuickWidget(rawList)) return res;
                        ArrayList<Object> filtered = new ArrayList<>(rawList.size());
                        for (Object item : rawList) {
                            if (!isQuickWidgetInfo(item)) {
                                filtered.add(item);
                            }
                        }
                        return filtered;
                    }

                    refreshQuickWidgetMetadataInList(ctx, rawList);
                    if (!containsQuickWidget(rawList) && ctx != null && !isAutoSeeded(ctx)) {
                        setAutoSeeded(ctx, true);
                        ArrayList<Object> seeded = buildSeededList(ctx, rawList);
                        try {
                            CoverGeometry.setField(model, "mShowWidgetList", seeded);
                        } catch (Throwable ignored) {
                        }
                        persistSeededListAsync(model, seeded);
                        ModLog.i(TAG, "auto-seeded QuickActionsWidget into FlipWidgetModel size=" + seeded.size());
                        return seeded;
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "getAddedListBlocked hook failed", t);
                }
                return res;
            });

            Method saveToDb = modelClass.getDeclaredMethod("saveToDatabase", List.class, boolean.class);
            saveToDb.setAccessible(true);
            module.hook(saveToDb).intercept(chain -> {
                try {
                    Object model = chain.getThisObject();
                    if (model != null
                            && chain.getArg(0) instanceof List<?> list
                            && list.size() == MAX_WIDGET_LIMIT_WITH_QUICK
                            && ModuleConfig.quickWidgetEnabled()
                            && containsQuickWidget(list)
                            && modelDoUpdateEntityMethod != null) {
                        boolean async = Boolean.TRUE.equals(chain.getArg(1));
                        List<Object> copy = new ArrayList<>(list);
                        if (async) {
                            PREVIEW_IO.execute(() -> {
                                try {
                                    modelDoUpdateEntityMethod.invoke(model, copy);
                                } catch (Throwable t) {
                                    Log.w(TAG, "async 6-widget doUpdateWidgetEntity failed", t);
                                }
                            });
                        } else {
                            modelDoUpdateEntityMethod.invoke(model, copy);
                        }
                        return Boolean.TRUE;
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "saveToDatabase hook failed", t);
                }
                return chain.proceed();
            });

            // 4. Allow adding QuickActionsWidget as the 6th widget in WidgetSettingsActivity
            installSettingsPageLimitHooks(module, loader);

            ModuleConfig.addListener(() -> {
                boolean nowEnabled = ModuleConfig.quickWidgetEnabled();
                Boolean prev = lastKnownEnabledState;
                lastKnownEnabledState = nowEnabled;
                refreshAllGrids();
                Context appCtx = CoverGeometry.application();
                if (appCtx != null) {
                    schedulePreviewGeneration(appCtx, true);
                }
                if (prev == null || prev != nowEnabled) {
                    syncWidgetPresenceOnMainThread(appCtx, nowEnabled, false);
                }
            });

            ModLog.recordHookHealth("quick_actions_hooks", "ok");
            ModLog.i(TAG, "QuickActionsWidget hooks installed");
        } catch (Throwable t) {
            ModLog.recordHookHealth("quick_actions_hooks", "failed:" + t.getClass().getSimpleName());
            Log.e(TAG, "QuickActionsWidget install failed", t);
        }
    }

    private static boolean isQuickWidgetAddedInEditor(Object editorOrVm) {
        if (editorOrVm == null) return false;
        try {
            Object addedIdObj = CoverGeometry.field(editorOrVm, "mAddedIdList");
            if (addedIdObj == null) {
                Object inner = CoverGeometry.field(editorOrVm, "editor");
                if (inner != null) {
                    addedIdObj = CoverGeometry.field(inner, "mAddedIdList");
                }
            }
            if (addedIdObj instanceof List<?> idList) {
                return idList.contains(QUICK_WIDGET_FILE_NAME);
            }
            Object outer = CoverGeometry.field(editorOrVm, "this$0");
            if (outer != null && CoverGeometry.field(outer, "mList") instanceof List<?> outerList && !outerList.isEmpty()) {
                Object addedLine = outerList.get(0);
                if (addedLine != null && CoverGeometry.field(addedLine, "items") instanceof List<?> items) {
                    for (Object it : items) {
                        if (it != null && QUICK_WIDGET_FILE_NAME.equals(CoverGeometry.field(it, "id"))) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void installSettingsPageLimitHooks(XposedModule module, ClassLoader loader) {
        try {
            Class<?> childAdapterClass = loader.loadClass("com.miui.fliphome.settings.widget.WidgetChildAdapter");
            Method isLimited = childAdapterClass.getDeclaredMethod("isItemLimited");
            isLimited.setAccessible(true);
            module.hook(isLimited).intercept(chain -> {
                try {
                    Object adapter = chain.getThisObject();
                    if (adapter != null
                            && ModuleConfig.quickWidgetEnabled()
                            && Boolean.FALSE.equals(CoverGeometry.field(adapter, "mIsMineType"))) {
                        Object editor = CoverGeometry.field(adapter, "mEditor");
                        if (editor != null) {
                            int count = ((Number) CoverGeometry.invoke(
                                    editor, "getAddedCount", CoverGeometry.NO_PARAMS)).intValue();
                            if (count < 5 || (count < MAX_WIDGET_LIMIT_WITH_QUICK && isQuickWidgetAddedInEditor(editor))) {
                                return Boolean.FALSE;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });

            try {
                Class<?> holderClass = loader.loadClass(
                        "com.miui.fliphome.settings.widget.WidgetChildAdapter$ChildViewHolder");
                Method onBind = childAdapterClass.getDeclaredMethod("onBindViewHolder", holderClass, int.class);
                onBind.setAccessible(true);
                module.hook(onBind).intercept(chain -> {
                    Object res = chain.proceed();
                    try {
                        Object adapter = chain.getThisObject();
                        Object holder = chain.getArg(0);
                        int pos = ((Number) chain.getArg(1)).intValue();
                        if (adapter != null
                                && holder != null
                                && ModuleConfig.quickWidgetEnabled()
                                && Boolean.FALSE.equals(CoverGeometry.field(adapter, "mIsMineType"))
                                && CoverGeometry.field(adapter, "mList") instanceof List<?> list
                                && pos >= 0
                                && pos < list.size()) {
                            Object item = list.get(pos);
                            if (item != null && QUICK_WIDGET_FILE_NAME.equals(CoverGeometry.field(item, "id"))) {
                                Object editor = CoverGeometry.field(adapter, "mEditor");
                                if (editor != null) {
                                    int count = ((Number) CoverGeometry.invoke(
                                            editor, "getAddedCount", CoverGeometry.NO_PARAMS)).intValue();
                                    boolean alreadyAdded = Boolean.TRUE.equals(
                                            CoverGeometry.invoke(editor, "isAdded", new Class<?>[]{item.getClass()}, item));
                                    if (!alreadyAdded && count < MAX_WIDGET_LIMIT_WITH_QUICK) {
                                        if (CoverGeometry.field(holder, "ivIcon") instanceof View ivIcon) {
                                            ivIcon.setEnabled(true);
                                        }
                                        try {
                                            if (CoverGeometry.field(holder, "ivPreview") instanceof View ivPreview) {
                                                ivPreview.setEnabled(true);
                                                ivPreview.setAlpha(1f);
                                            }
                                        } catch (Throwable ignored) {
                                        }
                                        try {
                                            if (CoverGeometry.field(holder, "itemView") instanceof View itemView) {
                                                itemView.setEnabled(true);
                                                itemView.setAlpha(1f);
                                            }
                                        } catch (Throwable ignored) {
                                        }
                                    }
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

        try {
            Class<?> itemClass = loader.loadClass("com.miui.fliphome.settings.widget.WidgetViewModel$WidgetItem");
            Class<?> adapterEditorClass = loader.loadClass(
                    "com.miui.fliphome.settings.widget.WidgetAdapter$AdapterWidgetEditor");
            Method adapterAddWidget = adapterEditorClass.getDeclaredMethod("addWidget", itemClass);
            adapterAddWidget.setAccessible(true);
            Method notifyAdapter = adapterEditorClass.getDeclaredMethod(
                    "notifyAdapter", int.class, int.class, int.class);
            notifyAdapter.setAccessible(true);
            Method notifyAvail = adapterEditorClass.getDeclaredMethod(
                    "notifyAvailableItemChanged", itemClass, int.class, boolean.class);
            notifyAvail.setAccessible(true);
            Method notifyNormal = adapterEditorClass.getDeclaredMethod("notifyNormalItemChanged", itemClass);
            notifyNormal.setAccessible(true);
            Method refreshTitle = adapterEditorClass.getDeclaredMethod("refreshTitle");
            refreshTitle.setAccessible(true);
            Method scrollToEnd = adapterEditorClass.getDeclaredMethod("scrollToEndPosition");
            scrollToEnd.setAccessible(true);

            module.hook(adapterAddWidget).intercept(chain -> {
                try {
                    Object adapterEditor = chain.getThisObject();
                    Object widgetItem = chain.getArg(0);
                    if (adapterEditor != null && widgetItem != null && ModuleConfig.quickWidgetEnabled()) {
                        Object outerAdapter = CoverGeometry.field(adapterEditor, "this$0");
                        if (outerAdapter != null
                                && CoverGeometry.field(outerAdapter, "mList") instanceof List<?> outerList
                                && !outerList.isEmpty()) {
                            Object addedLine = outerList.get(0);
                            if (addedLine != null
                                    && CoverGeometry.field(addedLine, "items") instanceof List<?> addedItems
                                    && addedItems.size() == 5
                                    && !addedItems.contains(widgetItem)) {
                                String itemId = String.valueOf(CoverGeometry.field(widgetItem, "id"));
                                if (QUICK_WIDGET_FILE_NAME.equals(itemId)
                                        || isQuickWidgetAddedInEditor(adapterEditor)) {
                                    Object innerEditor = CoverGeometry.field(adapterEditor, "editor");
                                    if (innerEditor != null) {
                                        CoverGeometry.invoke(
                                                innerEditor, "addWidget", new Class<?>[]{itemClass}, widgetItem);
                                        if (addedItems.size() > 5) {
                                            notifyAdapter.invoke(adapterEditor, 0, addedItems.size() - 1, 1);
                                            boolean isO8 = Boolean.TRUE.equals(
                                                    CoverGeometry.field(outerAdapter, "mIsO8Model"));
                                            boolean atMax = addedItems.size() >= MAX_WIDGET_LIMIT_WITH_QUICK;
                                            if (isO8) {
                                                notifyAvail.invoke(adapterEditor, widgetItem, 2, atMax);
                                            } else {
                                                notifyNormal.invoke(adapterEditor, atMax ? null : widgetItem);
                                            }
                                            refreshTitle.invoke(adapterEditor);
                                            scrollToEnd.invoke(adapterEditor);
                                        }
                                        return null;
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "AdapterWidgetEditor.addWidget hook failed", t);
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {
        }

        try {
            Class<?> vmClass = loader.loadClass("com.miui.fliphome.settings.widget.WidgetViewModel");
            Class<?> itemClass = loader.loadClass("com.miui.fliphome.settings.widget.WidgetViewModel$WidgetItem");
            Class<?> commonUtilsClass = loader.loadClass("com.miui.fliphome.utils.CommonUtils");
            Method addWidget = vmClass.getDeclaredMethod("addWidget", itemClass);
            addWidget.setAccessible(true);
            Method getAddedWidgetInfo = vmClass.getDeclaredMethod("getAddedWidgetInfo");
            getAddedWidgetInfo.setAccessible(true);
            Method getAvailableWidgetInfo = vmClass.getDeclaredMethod("getAvailableWidgetInfo");
            getAvailableWidgetInfo.setAccessible(true);
            Method getAddedTitle = vmClass.getDeclaredMethod("getAddedTitle");
            getAddedTitle.setAccessible(true);
            Method onAddedUpdate = vmClass.getDeclaredMethod("onAddedUpdate");
            onAddedUpdate.setAccessible(true);
            Method calcPos = commonUtilsClass.getDeclaredMethod(
                    "calculateWidgetItemPosition", List.class, itemClass);
            calcPos.setAccessible(true);

            try {
                Class<?> flipAppClass = loader.loadClass("com.miui.fliphome.FlipApplication");
                Method getFlipApp = flipAppClass.getDeclaredMethod("getInstance");
                getFlipApp.setAccessible(true);
                Class<?> rStringClass = loader.loadClass("com.miui.fliphome.R$string");
                final int resAdded = rStringClass.getDeclaredField("widget_settings_added").getInt(null);
                final int resMine = rStringClass.getDeclaredField("widget_settings_mine").getInt(null);
                module.hook(getAddedTitle).intercept(chain -> {
                    try {
                        Object vm = chain.getThisObject();
                        if (vm != null
                                && ModuleConfig.quickWidgetEnabled()
                                && CoverGeometry.field(vm, "mAddedIdList") instanceof List<?> idList
                                && idList.size() == 5
                                && idList.contains(QUICK_WIDGET_FILE_NAME)) {
                            Object app = getFlipApp.invoke(null);
                            if (app instanceof Context ctx) {
                                boolean isO8 = Boolean.TRUE.equals(CoverGeometry.field(vm, "mIsO8Model"));
                                return ctx.getString(isO8 ? resAdded : resMine);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                });
            } catch (Throwable ignored) {
            }

            module.hook(addWidget).intercept(chain -> {
                try {
                    Object vm = chain.getThisObject();
                    Object widgetItem = chain.getArg(0);
                    if (vm != null
                            && widgetItem != null
                            && ModuleConfig.quickWidgetEnabled()
                            && CoverGeometry.field(vm, "mAddedIdList") instanceof List<?> idList
                            && idList.size() == 5) {
                        String itemId = String.valueOf(CoverGeometry.field(widgetItem, "id"));
                        if (!idList.contains(itemId)
                                && (QUICK_WIDGET_FILE_NAME.equals(itemId)
                                || idList.contains(QUICK_WIDGET_FILE_NAME))) {
                            @SuppressWarnings("unchecked")
                            List<Object> mutableIds = (List<Object>) idList;
                            mutableIds.add(itemId);

                            Object addedInfo = getAddedWidgetInfo.invoke(vm);
                            if (addedInfo != null
                                    && CoverGeometry.field(addedInfo, "items") instanceof List<?> addedItems) {
                                @SuppressWarnings("unchecked")
                                List<Object> mutableAddedItems = (List<Object>) addedItems;
                                mutableAddedItems.add(widgetItem);
                                CoverGeometry.setField(addedInfo, "typeTitle", getAddedTitle.invoke(vm));
                            }

                            if (Boolean.TRUE.equals(CoverGeometry.field(vm, "mIsO8Model"))) {
                                Object availInfo = getAvailableWidgetInfo.invoke(vm);
                                if (availInfo != null
                                        && CoverGeometry.field(availInfo, "items") instanceof List<?> availItems) {
                                    int pos = ((Number) calcPos.invoke(null, availItems, widgetItem)).intValue();
                                    if (pos >= 0 && pos < availItems.size()) {
                                        availItems.remove(pos);
                                    }
                                }
                            }

                            onAddedUpdate.invoke(vm);
                            if (CoverGeometry.field(vm, "mNewAddedWidget") instanceof List<?> newAdded) {
                                @SuppressWarnings("unchecked")
                                List<Object> mutableNewAdded = (List<Object>) newAdded;
                                mutableNewAdded.add(widgetItem);
                            }
                            return null;
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "WidgetViewModel.addWidget hook failed", t);
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {
        }
    }

    private static void resolveReflectionHandles(ClassLoader loader) throws ReflectiveOperationException {
        widgetInfoClass = loader.loadClass("com.miui.fliphome.widget.FlipWidgetInfo");
        widgetInfoCtor = widgetInfoClass.getDeclaredConstructor(String.class);
        widgetInfoCtor.setAccessible(true);

        Class<?> hostClass = loader.loadClass("com.miui.fliphome.widget.ui.maml.FlipMaMlHostView");
        hostTwoArgCtor = hostClass.getDeclaredConstructor(Context.class, String.class);
        hostTwoArgCtor.setAccessible(true);

        try {
            Class<?> flipAppClass = loader.loadClass("com.miui.fliphome.FlipApplication");
            flipAppGetLauncherMethod = flipAppClass.getDeclaredMethod("getLauncher");
            flipAppGetLauncherMethod.setAccessible(true);
        } catch (Throwable t) {
            Log.w(TAG, "FlipApplication.getLauncher unavailable", t);
        }

        try {
            launcherStateClass = loader.loadClass("com.miui.fliphome.LauncherState");
            java.lang.reflect.Field normalField = launcherStateClass.getDeclaredField("NORMAL");
            normalField.setAccessible(true);
            normalStateObj = normalField.get(null);
            java.lang.reflect.Field overviewField = launcherStateClass.getDeclaredField("OVERVIEW");
            overviewField.setAccessible(true);
            overviewStateObj = overviewField.get(null);

            Class<?> launcherClass = loader.loadClass("com.miui.fliphome.FlipLauncher");
            getStateManagerMethod = launcherClass.getMethod("getStateManager");
            isInStateMethod = launcherClass.getMethod("isInState", launcherStateClass);
            if (overviewStateObj != null) {
                try {
                    overviewOnStateEnabledMethod = overviewStateObj.getClass()
                            .getMethod("onStateEnabled", launcherClass);
                } catch (Throwable ignored) {
                }
            }
            try {
                performHomeToHomeMethod = launcherClass.getMethod("performHomeToHomeTriggered");
            } catch (NoSuchMethodException ignored) {
            }
            try {
                getRecentsViewMethod = launcherClass.getMethod("getRecentsView");
                getRecentsContainerMethod = launcherClass.getMethod("getRecentsContainer");
                getShortcutMenuLayerMethod = launcherClass.getMethod("getShortcutMenuLayer");
            } catch (Throwable ignored) {
            }
            try {
                onResumeCallbackInterface = loader.loadClass("com.miui.fliphome.FlipLauncher$OnResumeCallback");
                addOnResumeCallbackMethod = launcherClass.getMethod("addOnResumeCallback", onResumeCallbackInterface);
            } catch (Throwable ignored) {
            }

            Class<?> stateMgrClass = loader.loadClass("com.miui.fliphome.LauncherStateManager");
            goToStateMethod = stateMgrClass.getMethod("goToState", launcherStateClass, boolean.class);
            try {
                exitOverviewStateIfNeedMethod = stateMgrClass
                        .getMethod("exitOverviewStateIfNeed", boolean.class, boolean.class);
            } catch (Throwable ignored) {
            }

            try {
                Class<?> recentsModelClass = loader.loadClass("com.miui.fliphome.recents.RecentsModel");
                recentsModelGetInstanceMethod = recentsModelClass.getMethod("getInstance", Context.class);
                recentsModelPreloadMethod = recentsModelClass.getMethod("preloadRecents");
            } catch (Throwable ignored) {
            }

            try {
                Class<?> recentsContainerClass = loader.loadClass("com.miui.fliphome.recents.views.RecentsContainer");
                recentsContainerOnBackMethod = recentsContainerClass.getMethod("onBackPressed");
                recentsContainerDismissHomeMethod = recentsContainerClass.getMethod("dismissRecentsToHome");
                recentsContainerFadeInMethod = recentsContainerClass
                        .getMethod("startRecentsContainerFadeInAnim", long.class, long.class);
                recentsContainerBgFadeInMethod = recentsContainerClass
                        .getMethod("startBackgroundFadeInAnim", long.class, long.class);
            } catch (Throwable ignored) {
            }

            try {
                Class<?> recentsViewClass = loader.loadClass("com.miui.fliphome.recents.views.RecentsView");
                recentsViewGetTaskViewsMethod = recentsViewClass.getMethod("getTaskViews");
                Class<?> springUtilsClass = loader.loadClass("com.miui.fliphome.recents.util.SpringAnimationUtils");
                springUtilsGetInstanceMethod = springUtilsClass.getMethod("getInstance");
                startToRecentsAnimMethod = springUtilsClass
                        .getMethod("startToRecentsAnim", recentsViewClass, Runnable.class);
            } catch (Throwable ignored) {
            }

            try {
                Class<?> elemClass = loader.loadClass("com.miui.fliphome.recents.anim.ShortcutMenuLayerElement");
                java.lang.reflect.Field compField = elemClass.getDeclaredField("Companion");
                compField.setAccessible(true);
                Object elemCompanion = compField.get(null);
                if (elemCompanion != null) {
                    Method getInst = elemCompanion.getClass().getMethod("getInstance");
                    shortcutLayerElementInstance = getInst.invoke(elemCompanion);
                    shortcutLayerAnimToMethod = elemClass.getMethod("animTo", Object.class);
                }
                Class<?> paramsClass = loader.loadClass("com.miui.fliphome.recents.anim.ShortcutMenuLayerParams");
                java.lang.reflect.Field pCompField = paramsClass.getDeclaredField("Companion");
                pCompField.setAccessible(true);
                shortcutLayerParamsCompanion = pCompField.get(null);
                if (shortcutLayerParamsCompanion != null) {
                    shortcutLayerRecentParamsMethod = shortcutLayerParamsCompanion.getClass()
                            .getMethod("getRecentStateParams");
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            Log.w(TAG, "FlipLauncher state handles partially unavailable", t);
        }
    }

    private static View buildQuickActionsHostView(Context ctx, Object info) {
        try {
            ViewGroup hostView = (ViewGroup) hostTwoArgCtor.newInstance(ctx, null);
            hostView.clearFocus();
            hostView.setTag(info);
            hostView.setClipChildren(false);
            hostView.setClipToPadding(false);
            hostView.setOnTouchListener((v, ev) -> true);

            QuickActionsGridView gridView = new QuickActionsGridView(ctx);
            hostView.addView(gridView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            knownHostViews.add(hostView);
            knownGridViews.add(gridView);
            ModLog.i(TAG, "created QuickActionsWidget host view");
            return hostView;
        } catch (Throwable t) {
            Log.e(TAG, "failed to instantiate FlipMaMlHostView for QuickActionsWidget", t);
            return null;
        }
    }

    private static Context resolveAppContext(Object owner) {
        if (owner != null) {
            try {
                Object c = CoverGeometry.field(owner, "mContext");
                if (c instanceof Context ctx) {
                    return ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
                }
            } catch (Throwable ignored) {
            }
        }
        return CoverGeometry.application();
    }

    private static boolean isQuickWidgetInfo(Object info) {
        if (info == null) return false;
        try {
            return QUICK_WIDGET_FILE_NAME.equals(CoverGeometry.field(info, "mFileName"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean containsQuickWidget(List<?> list) {
        if (list == null) return false;
        for (Object item : list) {
            if (isQuickWidgetInfo(item)) return true;
        }
        return false;
    }

    private static void applyPreviewPaths(Object info, String lightPath, String darkPath) {
        if (info == null) return;
        for (String f : new String[]{"mLightPreviewPath", "mZHCNLightPreviewPath", "mENUSLightPreviewPath"}) {
            try {
                CoverGeometry.setField(info, f, lightPath);
            } catch (Throwable ignored) {
            }
        }
        for (String f : new String[]{"mDarkPreviewPath", "mZHCNDarkPreviewPath", "mENUSDarkPreviewPath"}) {
            try {
                CoverGeometry.setField(info, f, darkPath);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void refreshQuickWidgetMetadataInList(Context ctx, List<?> list) {
        if (ctx == null || list == null) return;
        schedulePreviewGeneration(ctx, false);
        String title = localizedWidgetTitle(ctx);
        String lightPath = new File(ctx.getFilesDir(), LIGHT_PREVIEW_FILE).getAbsolutePath();
        String darkPath = new File(ctx.getFilesDir(), DARK_PREVIEW_FILE).getAbsolutePath();
        for (Object item : list) {
            if (!isQuickWidgetInfo(item)) continue;
            try {
                CoverGeometry.setField(item, "mTitle", title);
                CoverGeometry.setField(item, "mTypeTag", QUICK_WIDGET_TYPE_TAG);
                applyPreviewPaths(item, lightPath, darkPath);
            } catch (Throwable ignored) {
            }
        }
    }

    private static Object createQuickWidgetInfo(Context ctx, int showIndex) {
        if (widgetInfoCtor == null) return null;
        try {
            Context effectiveCtx = ctx != null ? ctx : CoverGeometry.application();
            if (effectiveCtx != null) {
                schedulePreviewGeneration(effectiveCtx, false);
            }
            Object info = widgetInfoCtor.newInstance(QUICK_WIDGET_FILE_NAME);
            CoverGeometry.setField(info, "mFileName", QUICK_WIDGET_FILE_NAME);
            CoverGeometry.setField(info, "mTypeTag", QUICK_WIDGET_TYPE_TAG);
            CoverGeometry.setField(info, "mTitle", localizedWidgetTitle(effectiveCtx));
            CoverGeometry.setField(info, "mShowInSetPage", showIndex);
            if (effectiveCtx != null) {
                String lightPath = new File(effectiveCtx.getFilesDir(), LIGHT_PREVIEW_FILE).getAbsolutePath();
                String darkPath = new File(effectiveCtx.getFilesDir(), DARK_PREVIEW_FILE).getAbsolutePath();
                applyPreviewPaths(info, lightPath, darkPath);
            }
            return info;
        } catch (Throwable t) {
            Log.w(TAG, "createQuickWidgetInfo failed", t);
            return null;
        }
    }

    private static ArrayList<Object> buildSeededList(Context ctx, List<?> existing) {
        ArrayList<Object> out = new ArrayList<>(existing.size() + 1);
        int insertIndex = -1;
        for (int i = 0; i < existing.size(); i++) {
            Object item = existing.get(i);
            out.add(item);
            try {
                if (RECENT_WIDGET_FILE_NAME.equals(CoverGeometry.field(item, "mFileName"))) {
                    insertIndex = out.size();
                }
            } catch (Throwable ignored) {
            }
        }
        Object quickInfo = createQuickWidgetInfo(ctx, out.size());
        if (quickInfo != null) {
            if (insertIndex >= 0 && insertIndex <= out.size()) {
                out.add(insertIndex, quickInfo);
            } else {
                out.add(quickInfo);
            }
        }
        return out;
    }

    private static void persistSeededListAsync(Object model, List<Object> seeded) {
        if (model == null || modelDoUpdateEntityMethod == null) return;
        List<Object> copy = new ArrayList<>(seeded);
        PREVIEW_IO.execute(() -> {
            try {
                modelDoUpdateEntityMethod.invoke(model, copy);
            } catch (Throwable t) {
                Log.w(TAG, "persistSeededListAsync failed", t);
            }
        });
    }

    private static boolean isAutoSeeded(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS_STATE, Context.MODE_PRIVATE);
            return sp.getBoolean(KEY_AUTO_SEEDED, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void setAutoSeeded(Context ctx, boolean seeded) {
        try {
            ctx.getSharedPreferences(PREFS_STATE, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_AUTO_SEEDED, seeded)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    static void ensureSyncReceiverRegistered(Context context) {
        if (syncReceiverRegistered || context == null) return;
        synchronized (RECEIVER_LOCK) {
            if (syncReceiverRegistered) return;
            Context app = context.getApplicationContext();
            if (app == null) app = context;
            try {
                Handler main = new Handler(Looper.getMainLooper());
                final Context appCtx = app;

                // 1. Settings live sync broadcast receiver
                appCtx.registerReceiver(new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent intent) {
                        if (intent == null || !ACTION_QUICK_WIDGET_SYNC.equals(intent.getAction())) return;
                        try {
                            int sender = getSentFromUid();
                            int moduleUid = c.getPackageManager().getPackageUid(
                                    BuildConfig.APPLICATION_ID,
                                    PackageManager.PackageInfoFlags.of(0));
                            if (sender < 0 || sender % 100000 != moduleUid % 100000) {
                                Log.w(TAG, "ignored quick widget sync from uid " + sender);
                                return;
                            }
                            boolean enabled = intent.getBooleanExtra(ModuleConfig.KEY_QUICK_WIDGET_ENABLED, false);
                            String rawSlots = intent.getStringExtra(ModuleConfig.KEY_QUICK_WIDGET_SLOTS);
                            boolean explicitToggle = intent.getBooleanExtra(EXTRA_EXPLICIT_TOGGLE, false);
                            ModuleConfig.overrideQuickWidget(enabled,
                                    rawSlots != null ? rawSlots : ModuleConfig.DEFAULT_QUICK_WIDGET_SLOTS);
                            lastKnownEnabledState = enabled;
                            ModLog.i(TAG, "received live quick widget sync enabled=" + enabled
                                    + " slots=" + rawSlots + " explicit=" + explicitToggle);
                            main.post(() -> {
                                refreshAllGrids();
                                schedulePreviewGeneration(appCtx, true);
                                syncWidgetPresenceOnMainThread(appCtx, enabled, explicitToggle);
                            });
                        } catch (Throwable t) {
                            Log.w(TAG, "quick widget sync receiver failed", t);
                        }
                    }
                }, new IntentFilter(ACTION_QUICK_WIDGET_SYNC), null, main, Context.RECEIVER_EXPORTED);

                // 2. Ringer mode change receiver for live Sound Mode tile state
                AudioManager am = appCtx.getSystemService(AudioManager.class);
                if (am != null) {
                    currentRingerMode = am.getRingerMode();
                }
                appCtx.registerReceiver(new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent intent) {
                        try {
                            AudioManager audio = c.getSystemService(AudioManager.class);
                            if (audio != null) {
                                currentRingerMode = audio.getRingerMode();
                                invalidateAllTiles();
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }, new IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION), null, main, Context.RECEIVER_NOT_EXPORTED);

                // 3. CameraManager TorchCallback for live Flashlight tile state
                registerTorchTracker(appCtx, main);

                // 3b. Settings.System ContentObserver for live Rotation Lock & Brightness tile states
                refreshSystemToggleStates(appCtx);
                try {
                    ContentObserver settingsObserver = new ContentObserver(main) {
                        @Override
                        public void onChange(boolean selfChange) {
                            refreshSystemToggleStates(appCtx);
                            invalidateAllTiles();
                        }
                    };
                    ContentResolver cr = appCtx.getContentResolver();
                    cr.registerContentObserver(
                            Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION), false, settingsObserver);
                    cr.registerContentObserver(
                            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE), false, settingsObserver);
                    cr.registerContentObserver(
                            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, settingsObserver);
                } catch (Throwable ignored) {
                }

                // 4. Recents trigger broadcast from SystemUI (StatusOrb action)
                appCtx.registerReceiver(new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent intent) {
                        if (intent == null || !ACTION_TRIGGER_RECENTS.equals(intent.getAction())) return;
                        if (!isTrustedRecentsTriggerSender(c, getSentFromUid())) {
                            Log.w(TAG, "ignored trigger recents from uid " + getSentFromUid());
                            return;
                        }
                        performRecentsAction(appCtx);
                    }
                }, new IntentFilter(ACTION_TRIGGER_RECENTS), null, main, Context.RECEIVER_EXPORTED);

                syncReceiverRegistered = true;
            } catch (Throwable t) {
                Log.w(TAG, "ensureSyncReceiverRegistered skipped", t);
            }
        }
    }

    private static boolean isTrustedRecentsTriggerSender(Context c, int sender) {
        if (sender < 0 || c == null) return false;
        int appId = sender % 100000;
        if (appId == 1000) return true;
        PackageManager pm = c.getPackageManager();
        if (pm == null) return false;
        for (String pkg : new String[]{CoverGeometry.SYSTEMUI, BuildConfig.APPLICATION_ID}) {
            try {
                int uid = pm.getPackageUid(pkg, PackageManager.PackageInfoFlags.of(0));
                if (uid > 0 && appId == (uid % 100000)) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static void refreshSystemToggleStates(Context ctx) {
        if (ctx == null) return;
        try {
            ContentResolver cr = ctx.getContentResolver();
            rotationLocked = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1) == 0;
            brightnessAuto = Settings.System.getInt(
                    cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
                    == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
            int maxVal = CoverDisplay.maxScreenBrightnessInt();
            brightnessLevel = Settings.System.getInt(
                    cr, Settings.System.SCREEN_BRIGHTNESS, Math.round(maxVal * 0.55f));
        } catch (Throwable ignored) {
        }
    }

    private static void registerTorchTracker(Context appCtx, Handler main) {
        if (torchCallbackRegistered || appCtx == null) return;
        torchCallbackRegistered = true;
        PREVIEW_IO.execute(() -> {
            try {
                CameraManager cm = appCtx.getSystemService(CameraManager.class);
                if (cm == null) {
                    torchCallbackRegistered = false;
                    return;
                }
                backFlashCameraId = findFlashCameraId(cm);
                main.post(() -> {
                    try {
                        cm.registerTorchCallback(new CameraManager.TorchCallback() {
                            @Override
                            public void onTorchModeChanged(String cameraId, boolean enabled) {
                                if (cameraId != null && (backFlashCameraId == null || cameraId.equals(backFlashCameraId))) {
                                    backFlashCameraId = cameraId;
                                    torchEnabled = enabled;
                                    invalidateAllTiles();
                                }
                            }

                            @Override
                            public void onTorchModeUnavailable(String cameraId) {
                                if (cameraId != null && cameraId.equals(backFlashCameraId)) {
                                    torchEnabled = false;
                                    invalidateAllTiles();
                                }
                            }
                        }, main);
                    } catch (Throwable t) {
                        torchCallbackRegistered = false;
                        Log.w(TAG, "TorchCallback registration unavailable", t);
                    }
                });
            } catch (Throwable t) {
                torchCallbackRegistered = false;
                Log.w(TAG, "TorchCallback init unavailable", t);
            }
        });
    }

    private static String findFlashCameraId(CameraManager cm) {
        if (cm == null) return null;
        try {
            String fallbackId = null;
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(id);
                Boolean hasFlash = cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (!Boolean.TRUE.equals(hasFlash)) continue;
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return id;
                }
                if (fallbackId == null) fallbackId = id;
            }
            return fallbackId;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void syncWidgetPresenceOnMainThread(Context ctx, boolean enabled, boolean explicitToggle) {
        try {
            Object model = lastWidgetModelRef != null ? lastWidgetModelRef.get() : null;
            if (model == null && modelGetInstanceMethod != null) {
                model = modelGetInstanceMethod.invoke(null);
            }
            if (model == null) return;

            boolean updatedViaModel = false;
            if (!enabled) {
                if (ctx != null) {
                    setAutoSeeded(ctx, false);
                }
                List<?> rawList = null;
                try {
                    Object fieldList = CoverGeometry.field(model, "mShowWidgetList");
                    if (fieldList instanceof List<?> l) rawList = l;
                } catch (Throwable ignored) {
                }
                if (rawList == null) {
                    Object blockedObj = CoverGeometry.invoke(model, "getAddedListBlocked", CoverGeometry.NO_PARAMS);
                    if (blockedObj instanceof List<?> l) rawList = l;
                }
                if (rawList != null) {
                    ArrayList<Object> cleaned = new ArrayList<>(rawList.size());
                    boolean removed = false;
                    for (Object item : rawList) {
                        if (isQuickWidgetInfo(item)) {
                            removed = true;
                        } else {
                            cleaned.add(item);
                        }
                    }
                    if (removed || explicitToggle) {
                        try {
                            CoverGeometry.setField(model, "mShowWidgetList", cleaned);
                        } catch (Throwable ignored) {
                        }
                        // Persist cleaned list directly to FlipHome's SQLite DB so no orphaned synthetic row survives disable/uninstall
                        persistSeededListAsync(model, cleaned);
                        if (modelUpdateShowListMethod != null) {
                            modelUpdateShowListMethod.invoke(model, cleaned);
                            updatedViaModel = true;
                        }
                        ModLog.i(TAG, "removed QuickActionsWidget from FlipWidgetModel & SQLite DB size=" + cleaned.size());
                    }
                }
            } else if (ctx != null && (explicitToggle || !isAutoSeeded(ctx))) {
                setAutoSeeded(ctx, true);
                Object rawListObj = CoverGeometry.invoke(model, "getAddedListBlocked", CoverGeometry.NO_PARAMS);
                if (rawListObj instanceof List<?> rawList && !containsQuickWidget(rawList)) {
                    ArrayList<Object> updated = buildSeededList(ctx, rawList);
                    if (modelUpdateShowListMethod != null) {
                        modelUpdateShowListMethod.invoke(model, updated);
                        updatedViaModel = true;
                        ModLog.i(TAG, "added QuickActionsWidget via updateShowList size=" + updated.size());
                    }
                }
            }
            if (!updatedViaModel) {
                if (modelInitShowListMethod != null) {
                    modelInitShowListMethod.invoke(model);
                } else {
                    Object listObj = CoverGeometry.invoke(model, "getAddedListBlocked", CoverGeometry.NO_PARAMS);
                    Object liveData = CoverGeometry.field(model, "mShowListLiveData");
                    if (liveData != null && listObj != null) {
                        CoverGeometry.invoke(liveData, "postValue", new Class<?>[]{Object.class}, listObj);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "syncWidgetPresenceOnMainThread failed", t);
        }
    }

    private static void refreshAllGrids() {
        List<QuickActionsGridView> grids;
        synchronized (knownGridViews) {
            grids = new ArrayList<>(knownGridViews);
        }
        for (QuickActionsGridView g : grids) {
            if (g != null) {
                g.reloadSlots();
            }
        }
    }

    private static void invalidateAllTiles() {
        List<QuickActionsGridView> grids;
        synchronized (knownGridViews) {
            grids = new ArrayList<>(knownGridViews);
        }
        for (QuickActionsGridView g : grids) {
            if (g != null) {
                g.invalidateTiles();
            }
        }
    }

    // ==================== Preview PNG Generation for WidgetSettingsActivity ====================

    private static void schedulePreviewGeneration(Context ctx, boolean force) {
        if (ctx == null) return;
        final Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        final List<String> slots = ModuleConfig.quickWidgetSlots();
        final String sig = String.join(",", slots);
        File lightFile = new File(appCtx.getFilesDir(), LIGHT_PREVIEW_FILE);
        File darkFile = new File(appCtx.getFilesDir(), DARK_PREVIEW_FILE);
        if (!force && sig.equals(lastPreviewSignature) && lightFile.exists() && darkFile.exists()) {
            return;
        }
        lastPreviewSignature = sig;
        PREVIEW_IO.execute(() -> {
            writePreviewPng(appCtx, slots, false, lightFile);
            writePreviewPng(appCtx, slots, true, darkFile);
        });
    }

    private static void writePreviewPng(Context ctx, List<String> slots, boolean darkTheme, File target) {
        Bitmap bmp = null;
        try {
            int size = 356;
            bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            RectF rect = new RectF(0, 0, size, size);

            p.setStyle(Paint.Style.FILL);
            p.setColor(darkTheme ? 0xFF22262E : 0xFFF1F3F8);
            c.drawRoundRect(rect, 54f, 54f, p);

            float tileSize = Math.round(size * 0.382f);
            float gap = Math.round(size * 0.068f);
            float start = (size - 2f * tileSize - gap) / 2f;
            float tileRadius = tileSize * 0.28f;

            for (int i = 0; i < ModuleConfig.QUICK_WIDGET_SLOT_COUNT; i++) {
                int col = i % 2;
                int row = i / 2;
                float left = start + col * (tileSize + gap);
                float top = start + row * (tileSize + gap);
                rect.set(left, top, left + tileSize, top + tileSize);

                p.setStyle(Paint.Style.FILL);
                p.setColor(darkTheme ? 0x26FFFFFF : 0x12000000);
                c.drawRoundRect(rect, tileRadius, tileRadius, p);

                String token = i < slots.size() ? slots.get(i) : ModuleConfig.DEFAULT_QUICK_WIDGET_SLOT_LIST.get(i);
                float cx = rect.centerX();
                float cy = rect.centerY();
                if (token.startsWith(ModuleConfig.QA_APP_PREFIX)) {
                    String pkg = token.substring(ModuleConfig.QA_APP_PREFIX.length());
                    Drawable icon = PinnedAppsWidget.loadAppIcon(ctx, pkg);
                    if (icon != null) {
                        int iconHalf = Math.round(tileSize * 0.31f);
                        icon.setBounds(
                                Math.round(cx) - iconHalf,
                                Math.round(cy) - iconHalf,
                                Math.round(cx) + iconHalf,
                                Math.round(cy) + iconHalf);
                        icon.draw(c);
                        continue;
                    }
                }
                int fg = darkTheme ? 0xF2FFFFFF : 0xE614171F;
                drawActionGlyph(c, token, cx, cy, tileSize * 0.44f, fg, false, AudioManager.RINGER_MODE_NORMAL);
            }

            File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.flush();
            }
            if (!tmp.renameTo(target)) {
                try (FileOutputStream fos = new FileOutputStream(target)) {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                    fos.flush();
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Throwable t) {
            Log.w(TAG, "writePreviewPng failed for " + target.getName(), t);
        } finally {
            if (bmp != null) bmp.recycle();
        }
    }

    // ==================== Action Execution ====================

    private static void executeQuickAction(Context ctx, String token, View tileView) {
        if (ctx == null || token == null || token.isEmpty()) return;
        ModLog.i(TAG, "executing quick action: " + token);
        switch (token) {
            case ModuleConfig.QA_HOME -> performHomeAction(ctx);
            case ModuleConfig.QA_RECENTS -> performRecentsAction(ctx);
            case ModuleConfig.QA_LOCK -> performLockAction(ctx);
            case ModuleConfig.QA_BACK -> performBackAction(ctx);
            case ModuleConfig.QA_NOTIFICATIONS -> sendPanelExpandBroadcast(ctx, false);
            case ModuleConfig.QA_CONTROL_CENTER -> sendPanelExpandBroadcast(ctx, true);
            case ModuleConfig.QA_TORCH -> toggleTorch(ctx);
            case ModuleConfig.QA_SCREENSHOT -> {
                if (!injectKeyEventInFlipHome(ctx, android.view.KeyEvent.KEYCODE_SYSRQ)) {
                    sendSystemQuickAction(ctx, ModuleConfig.QA_SCREENSHOT);
                }
            }
            case ModuleConfig.QA_RINGER -> cycleRingerMode(ctx);
            case ModuleConfig.QA_ROTATION_LOCK -> toggleRotationLock(ctx);
            case ModuleConfig.QA_MEDIA_PLAY_PAUSE -> dispatchMediaPlayPause(ctx);
            case ModuleConfig.QA_BRIGHTNESS -> cycleBrightness(ctx);
            case ModuleConfig.QA_SCAN -> launchScanOnCover(ctx);
            default -> {
                if (token.startsWith(ModuleConfig.QA_APP_PREFIX)) {
                    String target = token.substring(ModuleConfig.QA_APP_PREFIX.length()).trim();
                    if (!target.isEmpty()) {
                        PinnedAppsWidget.launchPackageOnCover(ctx, target, tileView);
                    }
                }
            }
        }
    }

    private static Object currentFlipLauncher() {
        try {
            return flipAppGetLauncherMethod != null ? flipAppGetLauncherMethod.invoke(null) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isLauncherResumedOnCover(Object launcher) {
        if (!(launcher instanceof Activity activity) || activity.isFinishing() || activity.isDestroyed()) {
            return false;
        }
        try {
            return !Boolean.TRUE.equals(CoverGeometry.field(launcher, "mIsPause"));
        } catch (Throwable ignored) {
            return true;
        }
    }

    static boolean isInOverviewOnCover(Activity activity) {
        Object target = activity != null ? activity : currentFlipLauncher();
        return isLauncherInOverview(target);
    }

    private static boolean isLauncherInOverview(Object launcher) {
        if (launcher == null || isInStateMethod == null || overviewStateObj == null) return false;
        try {
            return Boolean.TRUE.equals(isInStateMethod.invoke(launcher, overviewStateObj));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void resetOverviewFlags() {
        if (overviewStateObj == null) return;
        try {
            CoverGeometry.setField(overviewStateObj, "mIsFromFsGesture", false);
        } catch (Throwable ignored) {
        }
        try {
            CoverGeometry.setField(overviewStateObj, "mIsIgnoreOverviewAnim", false);
        } catch (Throwable ignored) {
        }
        try {
            CoverGeometry.setField(overviewStateObj, "disableRestore", false);
        } catch (Throwable ignored) {
        }
    }

    private static boolean exitLauncherOverviewToHome(Object launcher) {
        if (launcher == null) return false;
        resetOverviewFlags();
        try {
            if (getRecentsContainerMethod != null && recentsContainerDismissHomeMethod != null) {
                Object container = getRecentsContainerMethod.invoke(launcher);
                if (container != null) {
                    recentsContainerDismissHomeMethod.invoke(container);
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            if (getStateManagerMethod != null) {
                Object stateMgr = getStateManagerMethod.invoke(launcher);
                if (stateMgr != null) {
                    if (exitOverviewStateIfNeedMethod != null) {
                        exitOverviewStateIfNeedMethod.invoke(stateMgr, true, false);
                        return true;
                    }
                    if (goToStateMethod != null && normalStateObj != null) {
                        goToStateMethod.invoke(stateMgr, normalStateObj, true);
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "exitLauncherOverviewToHome failed", t);
        }
        return false;
    }

    private static boolean enterLauncherOverview(Context ctx, Object launcher) {
        if (launcher == null || getStateManagerMethod == null
                || goToStateMethod == null || overviewStateObj == null) {
            return false;
        }
        try {
            if (ctx != null && recentsModelGetInstanceMethod != null && recentsModelPreloadMethod != null) {
                try {
                    Object model = recentsModelGetInstanceMethod.invoke(null, ctx.getApplicationContext());
                    if (model != null) {
                        recentsModelPreloadMethod.invoke(model);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "preloadRecents failed", t);
                }
            }

            Object stateMgr = getStateManagerMethod.invoke(launcher);
            if (stateMgr == null) return false;

            boolean alreadyOverview = isLauncherInOverview(launcher);
            try {
                CoverGeometry.setField(overviewStateObj, "mIsFromFsGesture", true);
                CoverGeometry.setField(overviewStateObj, "mIsIgnoreOverviewAnim", false);
                CoverGeometry.setField(overviewStateObj, "disableRestore", false);
            } catch (Throwable ignored) {
            }
            try {
                if (alreadyOverview && overviewOnStateEnabledMethod != null) {
                    overviewOnStateEnabledMethod.invoke(overviewStateObj, launcher);
                } else {
                    goToStateMethod.invoke(stateMgr, overviewStateObj, false);
                }
            } finally {
                resetOverviewFlags();
            }

            boolean shortcutAnimated = false;
            if (shortcutLayerElementInstance != null
                    && shortcutLayerAnimToMethod != null
                    && shortcutLayerParamsCompanion != null
                    && shortcutLayerRecentParamsMethod != null) {
                try {
                    Object params = shortcutLayerRecentParamsMethod.invoke(shortcutLayerParamsCompanion);
                    if (params != null) {
                        shortcutLayerAnimToMethod.invoke(shortcutLayerElementInstance, params);
                        shortcutAnimated = true;
                    }
                } catch (Throwable ignored) {
                }
            }
            if (!shortcutAnimated && getShortcutMenuLayerMethod != null) {
                try {
                    Object layer = getShortcutMenuLayerMethod.invoke(launcher);
                    if (layer instanceof View v) {
                        v.setAlpha(0f);
                    }
                } catch (Throwable ignored) {
                }
            }

            if (getRecentsContainerMethod != null) {
                try {
                    Object containerObj = getRecentsContainerMethod.invoke(launcher);
                    if (containerObj instanceof View containerView) {
                        containerView.setVisibility(View.VISIBLE);
                        containerView.setAlpha(1f);
                    }
                    if (containerObj != null) {
                        if (recentsContainerFadeInMethod != null) {
                            recentsContainerFadeInMethod.invoke(containerObj, 0L, 200L);
                        }
                        if (recentsContainerBgFadeInMethod != null) {
                            recentsContainerBgFadeInMethod.invoke(containerObj, 0L, 180L);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }

            if (getRecentsViewMethod != null) {
                try {
                    Object rvObj = getRecentsViewMethod.invoke(launcher);
                    if (rvObj instanceof View recentsView) {
                        recentsView.setAlpha(1f);
                        recentsView.setScaleX(1f);
                        recentsView.setScaleY(1f);
                        recentsView.setTranslationY(0f);
                        recentsView.post(() -> {
                            try {
                                if (!isLauncherInOverview(launcher)) return;
                                if (recentsViewGetTaskViewsMethod != null) {
                                    Object listObj = recentsViewGetTaskViewsMethod.invoke(recentsView);
                                    if (listObj instanceof List<?> taskViews) {
                                        for (Object tv : taskViews) {
                                            if (tv == null) continue;
                                            try {
                                                Object header = CoverGeometry.invoke(
                                                        tv, "getHeaderView", CoverGeometry.NO_PARAMS);
                                                if (header instanceof View hv) {
                                                    hv.setAlpha(1f);
                                                }
                                            } catch (Throwable ignored) {
                                            }
                                        }
                                    }
                                }
                                if (springUtilsGetInstanceMethod != null && startToRecentsAnimMethod != null) {
                                    Object springUtils = springUtilsGetInstanceMethod.invoke(null);
                                    if (springUtils != null) {
                                        startToRecentsAnimMethod.invoke(springUtils, recentsView, null);
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        });
                    }
                } catch (Throwable ignored) {
                }
            }
            return true;
        } catch (Throwable t) {
            resetOverviewFlags();
            Log.w(TAG, "enterLauncherOverview failed", t);
            return false;
        }
    }

    private static void performHomeAction(Context ctx) {
        try {
            Object launcher = currentFlipLauncher();
            if (isLauncherResumedOnCover(launcher)) {
                if (isLauncherInOverview(launcher) && exitLauncherOverviewToHome(launcher)) {
                    return;
                }
                if (performHomeToHomeMethod != null) {
                    performHomeToHomeMethod.invoke(launcher);
                    return;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "in-process home action fallback to key injection", t);
        }
        if (!injectKeyEventInFlipHome(ctx, android.view.KeyEvent.KEYCODE_HOME)) {
            sendSystemQuickAction(ctx, ModuleConfig.QA_HOME);
        }
    }

    private static void performLockAction(Context ctx) {
        // Primary immediate path: SystemUI holds DEVICE_POWER and trusts FlipHome's STATUS_BAR_SERVICE permission
        try {
            Intent lockIntent = new Intent(LauncherPullDown.ACTION)
                    .setPackage(CoverGeometry.SYSTEMUI)
                    .putExtra("lock_cover", true);
            ctx.sendBroadcast(lockIntent);
            return;
        } catch (Throwable t) {
            Log.w(TAG, "SystemUI lock broadcast failed, falling back to system_server", t);
        }
        sendSystemQuickAction(ctx, ModuleConfig.QA_LOCK);
    }

    private static void performBackAction(Context ctx) {
        try {
            Object launcher = currentFlipLauncher();
            if (isLauncherResumedOnCover(launcher) && isLauncherInOverview(launcher)) {
                if (getRecentsContainerMethod != null && recentsContainerOnBackMethod != null) {
                    Object container = getRecentsContainerMethod.invoke(launcher);
                    if (container != null) {
                        recentsContainerOnBackMethod.invoke(container);
                        return;
                    }
                }
                if (exitLauncherOverviewToHome(launcher)) {
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
        if (!injectKeyEventInFlipHome(ctx, android.view.KeyEvent.KEYCODE_BACK)) {
            sendSystemQuickAction(ctx, ModuleConfig.QA_BACK);
        }
    }

    private static boolean injectKeyEventInFlipHome(Context ctx, int keyCode) {
        try {
            int displayId = android.view.Display.DEFAULT_DISPLAY;
            android.hardware.display.DisplayManager dm = ctx != null
                    ? ctx.getSystemService(android.hardware.display.DisplayManager.class)
                    : null;
            if (dm != null) {
                for (android.view.Display d : dm.getDisplays()) {
                    if (CoverDisplay.isOuterDisplay(d)) {
                        displayId = d.getDisplayId();
                        break;
                    }
                }
            }
            long now = android.os.SystemClock.uptimeMillis();
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
            Class<?> imgClass = Class.forName("android.hardware.input.InputManagerGlobal");
            Object img = imgClass.getMethod("getInstance").invoke(null);
            Method inject = imgClass.getMethod("injectInputEvent", android.view.InputEvent.class, int.class);
            boolean okDown = Boolean.TRUE.equals(inject.invoke(img, down, 0));
            boolean okUp = Boolean.TRUE.equals(inject.invoke(img, up, 0));
            return okDown && okUp;
        } catch (Throwable t) {
            Log.w(TAG, "injectKeyEventInFlipHome fallback to system_server for keyCode=" + keyCode, t);
            return false;
        }
    }

    private static void performRecentsAction(Context ctx) {
        try {
            Object launcher = currentFlipLauncher();
            if (isLauncherResumedOnCover(launcher)) {
                if (isLauncherInOverview(launcher)) {
                    if (exitLauncherOverviewToHome(launcher)) {
                        ModLog.i(TAG, "exited FlipLauncher OverviewState to Home");
                        return;
                    }
                } else if (enterLauncherOverview(ctx, launcher)) {
                    ModLog.i(TAG, "entered FlipLauncher OverviewState from cover desktop");
                    return;
                }
            }

            // Currently inside a foreground cover app (or FlipLauncher paused):
            // bring FlipLauncher to front on the cover display via KEYCODE_HOME (or SECONDARY_HOME fallback,
            // never bare FlipLauncher/CATEGORY_HOME which HyperOS LauncherSwitchController redirects to
            // inner-screen com.miui.home) and enter OverviewState immediately upon FlipLauncher.onResume().
            final AtomicBoolean transitioned = new AtomicBoolean(false);
            final Handler main = new Handler(Looper.getMainLooper());
            final java.util.function.BooleanSupplier tryEnter = () -> {
                Object activeLauncher = currentFlipLauncher();
                if (!isLauncherResumedOnCover(activeLauncher)) return false;
                if (!transitioned.compareAndSet(false, true)) return true;
                if (enterLauncherOverview(ctx, activeLauncher)) {
                    ModLog.i(TAG, "entered FlipLauncher OverviewState from foreground app");
                    return true;
                }
                transitioned.set(false);
                return false;
            };

            if (launcher != null && onResumeCallbackInterface != null && addOnResumeCallbackMethod != null) {
                try {
                    Object proxy = Proxy.newProxyInstance(
                            onResumeCallbackInterface.getClassLoader(),
                            new Class<?>[]{onResumeCallbackInterface},
                            (p, m, args) -> {
                                if ("onLauncherResume".equals(m.getName())) {
                                    main.post(tryEnter::getAsBoolean);
                                }
                                return null;
                            });
                    addOnResumeCallbackMethod.invoke(launcher, proxy);
                } catch (Throwable ignored) {
                }
            }

            if (!injectKeyEventInFlipHome(ctx, android.view.KeyEvent.KEYCODE_HOME)) {
                Intent flipHomeIntent = new Intent(Intent.ACTION_MAIN)
                        .addCategory("android.intent.category.SECONDARY_HOME")
                        .setClassName(CoverGeometry.FLIPHOME, "com.miui.fliphome.FlipLauncher")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(flipHomeIntent);
            }

            main.postDelayed(() -> {
                if (!transitioned.get()) {
                    tryEnter.getAsBoolean();
                }
            }, 90L);
            main.postDelayed(() -> {
                if (!transitioned.get()) {
                    tryEnter.getAsBoolean();
                }
            }, 220L);
        } catch (Throwable t) {
            Log.w(TAG, "performRecentsAction failed", t);
        }
    }

    private static void sendSystemQuickAction(Context ctx, String action) {
        try {
            Intent intent = new Intent(SystemServerEntry.ACTION_COVER_QUICK_ACTION)
                    .setPackage("android")
                    .putExtra(SystemServerEntry.EXTRA_QUICK_ACTION, action);
            BroadcastOptions options = BroadcastOptions.makeBasic();
            options.setShareIdentityEnabled(true);
            ctx.sendBroadcast(intent, null, options.toBundle());
        } catch (Throwable t) {
            Log.w(TAG, "sendSystemQuickAction failed for " + action, t);
        }
    }

    private static void sendPanelExpandBroadcast(Context ctx, boolean controlCenter) {
        try {
            Intent intent = new Intent(LauncherPullDown.ACTION)
                    .setPackage(CoverGeometry.SYSTEMUI)
                    .putExtra("control", controlCenter)
                    .putExtra("force", true);
            ctx.sendBroadcast(intent);
        } catch (Throwable t) {
            Log.w(TAG, "sendPanelExpandBroadcast failed", t);
        }
    }

    private static void toggleTorch(Context ctx) {
        try {
            CameraManager cm = ctx.getSystemService(CameraManager.class);
            if (cm == null) return;
            String id = backFlashCameraId;
            if (id != null) {
                boolean next = !torchEnabled;
                cm.setTorchMode(id, next);
                torchEnabled = next;
                invalidateAllTiles();
                return;
            }
            Handler main = new Handler(Looper.getMainLooper());
            PREVIEW_IO.execute(() -> {
                String resolved = findFlashCameraId(cm);
                if (resolved == null) return;
                backFlashCameraId = resolved;
                main.post(() -> {
                    try {
                        boolean next = !torchEnabled;
                        cm.setTorchMode(resolved, next);
                        torchEnabled = next;
                        invalidateAllTiles();
                    } catch (Throwable t) {
                        Log.w(TAG, "toggleTorch async failed", t);
                    }
                });
            });
        } catch (Throwable t) {
            Log.w(TAG, "toggleTorch failed", t);
        }
    }

    private static void toggleRotationLock(Context ctx) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            int cur = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1);
            int next = cur == 0 ? 1 : 0;
            if (next == 0) {
                Display outer = CoverGeometry.outerDisplay(ctx);
                int rot = outer != null ? outer.getRotation() : Surface.ROTATION_0;
                Settings.System.putInt(cr, Settings.System.USER_ROTATION, rot);
            }
            Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, next);
            rotationLocked = (next == 0);
            invalidateAllTiles();
        } catch (Throwable t) {
            sendSystemQuickAction(ctx, ModuleConfig.QA_ROTATION_LOCK);
        }
    }

    private static void dispatchMediaPlayPause(Context ctx) {
        try {
            AudioManager am = ctx.getSystemService(AudioManager.class);
            if (am == null) {
                sendSystemQuickAction(ctx, ModuleConfig.QA_MEDIA_PLAY_PAUSE);
                return;
            }
            long now = SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(now, now + 8L, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0));
        } catch (Throwable t) {
            sendSystemQuickAction(ctx, ModuleConfig.QA_MEDIA_PLAY_PAUSE);
        }
    }

    private static void cycleBrightness(Context ctx) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            int maxVal = CoverDisplay.maxScreenBrightnessInt();
            int mode = Settings.System.getInt(
                    cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
            int val = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, Math.round(maxVal * 0.55f));
            int next = CoverDisplay.nextCoverBrightnessLevel(mode, val, maxVal);
            if (next < 0) {
                Settings.System.putInt(
                        cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
                brightnessAuto = true;
            } else {
                if (mode != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
                    Settings.System.putInt(
                            cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                }
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, next);
                brightnessAuto = false;
                brightnessLevel = next;
            }
            invalidateAllTiles();
        } catch (Throwable t) {
            sendSystemQuickAction(ctx, ModuleConfig.QA_BRIGHTNESS);
        }
    }

    private static void launchScanOnCover(Context ctx) {
        try {
            Display outer = CoverGeometry.outerDisplay(ctx);
            int displayId = outer != null ? outer.getDisplayId() : Display.DEFAULT_DISPLAY;
            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setLaunchDisplayId(displayId);

            PackageManager pm = ctx.getPackageManager();
            boolean hasWeChat = false;
            try {
                pm.getPackageInfo("com.tencent.mm", 0);
                hasWeChat = true;
            } catch (Throwable ignored) {
            }
            Intent scanIntent;
            if (hasWeChat) {
                scanIntent = new Intent(Intent.ACTION_MAIN)
                        .setComponent(new ComponentName("com.tencent.mm", "com.tencent.mm.ui.LauncherUI"))
                        .putExtra("LauncherUI.From.Scaner.Shortcut", true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            } else {
                scanIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("alipays://platformapi/startapp?saId=10000007"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            ctx.startActivity(scanIntent, opts.toBundle());
        } catch (Throwable t) {
            sendSystemQuickAction(ctx, ModuleConfig.QA_SCAN);
        }
    }

    private static void cycleRingerMode(Context ctx) {
        try {
            AudioManager am = ctx.getSystemService(AudioManager.class);
            if (am == null) return;
            int cur = am.getRingerMode();
            boolean policyGranted = false;
            try {
                NotificationManager nm = ctx.getSystemService(NotificationManager.class);
                policyGranted = nm != null && nm.isNotificationPolicyAccessGranted();
            } catch (Throwable ignored) {
            }
            int next;
            if (cur == AudioManager.RINGER_MODE_NORMAL) {
                next = AudioManager.RINGER_MODE_VIBRATE;
            } else if (cur == AudioManager.RINGER_MODE_VIBRATE) {
                next = policyGranted ? AudioManager.RINGER_MODE_SILENT : AudioManager.RINGER_MODE_NORMAL;
            } else {
                next = AudioManager.RINGER_MODE_NORMAL;
            }
            try {
                am.setRingerMode(next);
            } catch (SecurityException se) {
                int safeNext = cur == AudioManager.RINGER_MODE_NORMAL
                        ? AudioManager.RINGER_MODE_VIBRATE
                        : AudioManager.RINGER_MODE_NORMAL;
                am.setRingerMode(safeNext);
            }
            currentRingerMode = am.getRingerMode();
            invalidateAllTiles();
        } catch (Throwable t) {
            Log.w(TAG, "cycleRingerMode failed", t);
        }
    }

    // ==================== Shared Localization & Vector Glyph Renderer ====================

    private static boolean isChineseLocale(Context ctx) {
        try {
            Locale loc = ctx != null && ctx.getResources() != null
                    ? ctx.getResources().getConfiguration().getLocales().get(0)
                    : Locale.getDefault();
            return loc != null && loc.getLanguage().startsWith("zh");
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static String localizedWidgetTitle(Context ctx) {
        return isChineseLocale(ctx) ? "快捷按键" : "Quick Actions";
    }

    private static String localizedActionLabel(Context ctx, String token) {
        boolean zh = isChineseLocale(ctx);
        if (token == null) return "";
        return switch (token) {
            case ModuleConfig.QA_HOME -> zh ? "主页" : "Home";
            case ModuleConfig.QA_RECENTS -> zh ? "任务" : "Recents";
            case ModuleConfig.QA_LOCK -> zh ? "锁屏" : "Lock Screen";
            case ModuleConfig.QA_BACK -> zh ? "返回" : "Back";
            case ModuleConfig.QA_NOTIFICATIONS -> zh ? "通知栏" : "Notifications";
            case ModuleConfig.QA_CONTROL_CENTER -> zh ? "控制中心" : "Control Center";
            case ModuleConfig.QA_TORCH -> zh ? "手电筒" : "Flashlight";
            case ModuleConfig.QA_SCREENSHOT -> zh ? "截屏" : "Screenshot";
            case ModuleConfig.QA_RINGER -> zh ? "响铃模式" : "Sound Mode";
            case ModuleConfig.QA_ROTATION_LOCK -> zh ? "方向锁定" : "Rotation Lock";
            case ModuleConfig.QA_MEDIA_PLAY_PAUSE -> zh ? "播放/暂停" : "Play / Pause";
            case ModuleConfig.QA_BRIGHTNESS -> zh ? "亮度循环" : "Brightness";
            case ModuleConfig.QA_SCAN -> zh ? "扫一扫" : "Scan";
            default -> {
                if (token.startsWith(ModuleConfig.QA_APP_PREFIX)) {
                    String target = token.substring(ModuleConfig.QA_APP_PREFIX.length());
                    String label = PinnedAppsWidget.loadAppLabel(ctx, target);
                    yield ModuleConfig.extractAppTargetUserId(target) > 0
                            ? label + (zh ? " · 双开" : " · Dual")
                            : label;
                }
                yield token;
            }
        };
    }

    /**
     * Draws crisp anti-aliased vector action glyphs on a normalized 24x24 grid centered at (cx, cy).
     * Shared across {@link QuickActionTileView}, {@code WidgetSettingsActivity} preview PNGs, and
     * {@link MainActivity}'s interactive 2x2 slot configurator.
     */
    static void drawActionGlyph(
            Canvas c,
            String token,
            float cx,
            float cy,
            float size,
            int color,
            boolean activeState,
            int ringerMode) {
        if (c == null || token == null || size <= 0f) return;
        float s = size / 24f;
        c.save();
        c.translate(cx - 12f * s, cy - 12f * s);
        c.scale(s, s);

        GlyphScratch scratch = GLYPH_SCRATCH.get();
        Paint paint = scratch.paint;
        paint.reset();
        paint.setAntiAlias(true);
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.15f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

        Path path = scratch.path;
        path.reset();
        RectF rect = scratch.rect;
        rect.setEmpty();

        switch (token) {
            case ModuleConfig.QA_HOME -> {
                path.moveTo(4.2f, 10.8f);
                path.lineTo(12.0f, 4.2f);
                path.lineTo(19.8f, 10.8f);
                c.drawPath(path, paint);

                path.reset();
                path.moveTo(6.2f, 9.6f);
                path.lineTo(6.2f, 17.5f);
                path.quadTo(6.2f, 19.5f, 8.2f, 19.5f);
                path.lineTo(15.8f, 19.5f);
                path.quadTo(17.8f, 19.5f, 17.8f, 17.5f);
                path.lineTo(17.8f, 9.6f);
                c.drawPath(path, paint);

                c.drawLine(9.8f, 15.2f, 14.2f, 15.2f, paint);
            }
            case ModuleConfig.QA_RECENTS -> {
                rect.set(4.5f, 6.8f, 16.5f, 18.8f);
                c.drawRoundRect(rect, 3.0f, 3.0f, paint);

                path.moveTo(8.8f, 4.2f);
                path.lineTo(16.8f, 4.2f);
                path.quadTo(19.8f, 4.2f, 19.8f, 7.2f);
                path.lineTo(19.8f, 15.2f);
                c.drawPath(path, paint);
            }
            case ModuleConfig.QA_LOCK -> {
                rect.set(5.5f, 10.8f, 18.5f, 19.8f);
                c.drawRoundRect(rect, 3.0f, 3.0f, paint);

                path.moveTo(8.2f, 10.8f);
                path.lineTo(8.2f, 8.2f);
                rect.set(8.2f, 4.2f, 15.8f, 11.8f);
                path.arcTo(rect, 180f, 180f, false);
                path.lineTo(15.8f, 10.8f);
                c.drawPath(path, paint);

                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(12.0f, 14.8f, 1.35f, paint);
                paint.setStyle(Paint.Style.STROKE);
                c.drawLine(12.0f, 15.6f, 12.0f, 17.0f, paint);
            }
            case ModuleConfig.QA_BACK -> {
                path.moveTo(13.6f, 5.5f);
                path.lineTo(6.8f, 12.0f);
                path.lineTo(13.6f, 18.5f);
                c.drawPath(path, paint);
                c.drawLine(7.4f, 12.0f, 18.5f, 12.0f, paint);
            }
            case ModuleConfig.QA_NOTIFICATIONS -> {
                path.moveTo(12.0f, 3.6f);
                path.lineTo(12.0f, 5.0f);
                c.drawPath(path, paint);

                path.reset();
                path.moveTo(7.2f, 10.2f);
                rect.set(7.2f, 5.2f, 16.8f, 14.8f);
                path.arcTo(rect, 180f, 180f, false);
                path.lineTo(16.8f, 14.2f);
                path.lineTo(18.6f, 16.5f);
                path.lineTo(5.4f, 16.5f);
                path.lineTo(7.2f, 14.2f);
                path.close();
                c.drawPath(path, paint);

                rect.set(10.0f, 16.8f, 14.0f, 20.2f);
                c.drawArc(rect, 0f, 180f, false, paint);
            }
            case ModuleConfig.QA_CONTROL_CENTER -> {
                rect.set(4.2f, 5.6f, 19.8f, 10.6f);
                c.drawRoundRect(rect, 2.5f, 2.5f, paint);
                rect.set(4.2f, 13.4f, 19.8f, 18.4f);
                c.drawRoundRect(rect, 2.5f, 2.5f, paint);

                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(16.4f, 8.1f, 1.55f, paint);
                c.drawCircle(7.6f, 15.9f, 1.55f, paint);
            }
            case ModuleConfig.QA_TORCH -> {
                path.moveTo(7.5f, 5.5f);
                path.lineTo(16.5f, 5.5f);
                path.lineTo(14.5f, 10.0f);
                path.lineTo(14.5f, 18.0f);
                path.quadTo(14.5f, 20.0f, 12.5f, 20.0f);
                path.lineTo(11.5f, 20.0f);
                path.quadTo(9.5f, 20.0f, 9.5f, 18.0f);
                path.lineTo(9.5f, 10.0f);
                path.close();
                c.drawPath(path, paint);
                c.drawLine(8.2f, 8.0f, 15.8f, 8.0f, paint);

                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(12.0f, 13.8f, 1.25f, paint);
                if (activeState) {
                    paint.setStyle(Paint.Style.STROKE);
                    c.drawLine(12.0f, 2.2f, 12.0f, 3.6f, paint);
                    c.drawLine(7.6f, 2.8f, 8.5f, 3.9f, paint);
                    c.drawLine(16.4f, 2.8f, 15.5f, 3.9f, paint);
                }
            }
            case ModuleConfig.QA_SCREENSHOT -> {
                path.moveTo(4.5f, 9.0f);
                path.lineTo(4.5f, 6.2f);
                path.quadTo(4.5f, 4.5f, 6.2f, 4.5f);
                path.lineTo(9.0f, 4.5f);

                path.moveTo(15.0f, 4.5f);
                path.lineTo(17.8f, 4.5f);
                path.quadTo(19.5f, 4.5f, 19.5f, 6.2f);
                path.lineTo(19.5f, 9.0f);

                path.moveTo(19.5f, 15.0f);
                path.lineTo(19.5f, 17.8f);
                path.quadTo(19.5f, 19.5f, 17.8f, 19.5f);
                path.lineTo(15.0f, 19.5f);

                path.moveTo(9.0f, 19.5f);
                path.lineTo(6.2f, 19.5f);
                path.quadTo(4.5f, 19.5f, 4.5f, 17.8f);
                path.lineTo(4.5f, 15.0f);
                c.drawPath(path, paint);

                c.drawCircle(12.0f, 12.0f, 3.0f, paint);
            }
            case ModuleConfig.QA_RINGER -> {
                path.moveTo(4.8f, 9.6f);
                path.lineTo(8.4f, 9.6f);
                path.lineTo(12.8f, 6.0f);
                path.lineTo(12.8f, 18.0f);
                path.lineTo(8.4f, 14.4f);
                path.lineTo(4.8f, 14.4f);
                path.close();
                c.drawPath(path, paint);

                if (ringerMode == AudioManager.RINGER_MODE_SILENT) {
                    c.drawLine(15.5f, 9.5f, 19.5f, 14.5f, paint);
                    c.drawLine(19.5f, 9.5f, 15.5f, 14.5f, paint);
                } else if (ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
                    path.reset();
                    path.moveTo(16.0f, 7.6f);
                    path.lineTo(17.8f, 9.8f);
                    path.lineTo(15.8f, 12.0f);
                    path.lineTo(17.8f, 14.2f);
                    path.lineTo(16.0f, 16.4f);
                    c.drawPath(path, paint);
                } else {
                    rect.set(11.8f, 8.6f, 16.8f, 15.4f);
                    c.drawArc(rect, -52f, 104f, false, paint);
                    rect.set(10.8f, 6.0f, 19.8f, 18.0f);
                    c.drawArc(rect, -52f, 104f, false, paint);
                }
            }
            case ModuleConfig.QA_ROTATION_LOCK -> {
                rect.set(4.5f, 4.5f, 19.5f, 19.5f);
                c.drawArc(rect, -55f, 290f, false, paint);
                path.reset();
                path.moveTo(15.6f, 3.0f);
                path.lineTo(16.2f, 6.4f);
                path.lineTo(19.5f, 5.6f);
                c.drawPath(path, paint);

                rect.set(9.2f, 11.4f, 14.8f, 15.6f);
                c.drawRoundRect(rect, 1.4f, 1.4f, paint);
                path.reset();
                path.moveTo(10.4f, 11.4f);
                path.lineTo(10.4f, 10.2f);
                rect.set(10.4f, 8.6f, 13.6f, 11.8f);
                path.arcTo(rect, 180f, 180f, false);
                path.lineTo(13.6f, 11.4f);
                c.drawPath(path, paint);
            }
            case ModuleConfig.QA_MEDIA_PLAY_PAUSE -> {
                path.moveTo(4.6f, 7.0f);
                path.lineTo(12.2f, 12.0f);
                path.lineTo(4.6f, 17.0f);
                path.close();
                paint.setStyle(Paint.Style.FILL_AND_STROKE);
                c.drawPath(path, paint);
                paint.setStyle(Paint.Style.STROKE);
                c.drawLine(15.6f, 7.0f, 15.6f, 17.0f, paint);
                c.drawLine(19.4f, 7.0f, 19.4f, 17.0f, paint);
            }
            case ModuleConfig.QA_BRIGHTNESS -> {
                c.drawCircle(12.0f, 12.0f, 4.0f, paint);
                c.drawLine(12.0f, 3.4f, 12.0f, 5.4f, paint);
                c.drawLine(12.0f, 18.6f, 12.0f, 20.6f, paint);
                c.drawLine(3.4f, 12.0f, 5.4f, 12.0f, paint);
                c.drawLine(18.6f, 12.0f, 20.6f, 12.0f, paint);
                c.drawLine(5.9f, 5.9f, 7.3f, 7.3f, paint);
                c.drawLine(16.7f, 16.7f, 18.1f, 18.1f, paint);
                c.drawLine(18.1f, 5.9f, 16.7f, 7.3f, paint);
                c.drawLine(7.3f, 16.7f, 5.9f, 18.1f, paint);
                if (activeState) {
                    paint.setStyle(Paint.Style.FILL);
                    c.drawCircle(12.0f, 12.0f, 2.0f, paint);
                }
            }
            case ModuleConfig.QA_SCAN -> {
                path.moveTo(4.5f, 8.8f);
                path.lineTo(4.5f, 6.2f);
                path.quadTo(4.5f, 4.5f, 6.2f, 4.5f);
                path.lineTo(8.8f, 4.5f);

                path.moveTo(15.2f, 4.5f);
                path.lineTo(17.8f, 4.5f);
                path.quadTo(19.5f, 4.5f, 19.5f, 6.2f);
                path.lineTo(19.5f, 8.8f);

                path.moveTo(19.5f, 15.2f);
                path.lineTo(19.5f, 17.8f);
                path.quadTo(19.5f, 19.5f, 17.8f, 19.5f);
                path.lineTo(15.2f, 19.5f);

                path.moveTo(8.8f, 19.5f);
                path.lineTo(6.2f, 19.5f);
                path.quadTo(4.5f, 19.5f, 4.5f, 17.8f);
                path.lineTo(4.5f, 15.2f);
                c.drawPath(path, paint);

                c.drawLine(5.2f, 12.0f, 18.8f, 12.0f, paint);
            }
            default -> {
                rect.set(5.5f, 5.5f, 18.5f, 18.5f);
                c.drawRoundRect(rect, 4.0f, 4.0f, paint);
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(12.0f, 12.0f, 2.0f, paint);
            }
        }
        c.restore();
    }

    // ==================== 2x2 Frosted Grid & Tile Views ====================

    private static final class QuickActionsGridView extends ViewGroup {
        private final QuickActionTileView[] tiles = new QuickActionTileView[ModuleConfig.QUICK_WIDGET_SLOT_COUNT];
        private boolean effectiveDark;

        QuickActionsGridView(Context context) {
            super(context);
            setClipChildren(false);
            setClipToPadding(false);
            effectiveDark = resolveInitialDark(context);
            List<String> initialSlots = ModuleConfig.quickWidgetSlots();
            for (int i = 0; i < ModuleConfig.QUICK_WIDGET_SLOT_COUNT; i++) {
                QuickActionTileView tile = new QuickActionTileView(context);
                tile.setThemeDark(effectiveDark);
                tile.bindAction(initialSlots.get(i));
                tiles[i] = tile;
                addView(tile);
            }
        }

        private static boolean resolveInitialDark(Context ctx) {
            Boolean tracked = WidgetContentFit.currentEffectiveDark();
            if (tracked != null) return tracked;
            if (ctx == null || ctx.getResources() == null) return true;
            return (ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
        }

        void reloadSlots() {
            List<String> slots = ModuleConfig.quickWidgetSlots();
            for (int i = 0; i < tiles.length; i++) {
                tiles[i].bindAction(slots.get(i));
            }
        }

        void onThemeChanged(boolean dark) {
            if (this.effectiveDark == dark) return;
            this.effectiveDark = dark;
            for (QuickActionTileView t : tiles) {
                t.setThemeDark(dark);
            }
        }

        void invalidateTiles() {
            for (QuickActionTileView t : tiles) {
                t.invalidate();
            }
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            knownGridViews.add(this);
            ensureSyncReceiverRegistered(getContext());
            Boolean tracked = WidgetContentFit.currentEffectiveDark();
            if (tracked != null) {
                onThemeChanged(tracked);
            }
            reloadSlots();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = MeasureSpec.getSize(widthMeasureSpec);
            int h = MeasureSpec.getSize(heightMeasureSpec);
            if (w <= 0) w = Math.round(CoverGeometry.CARD_SIZE);
            if (h <= 0) h = Math.round(CoverGeometry.CARD_SIZE);
            setMeasuredDimension(w, h);

            int side = Math.min(w, h);
            int tileW = Math.round(side * 0.382f);
            int gap = Math.round(side * 0.068f);
            boolean tallCard = h > Math.round(w * 1.16f);
            int tileH = tallCard
                    ? Math.min(Math.round(tileW * 1.24f), Math.max(tileW, (h - gap * 3) / 2))
                    : tileW;

            int wSpec = MeasureSpec.makeMeasureSpec(tileW, MeasureSpec.EXACTLY);
            int hSpec = MeasureSpec.makeMeasureSpec(tileH, MeasureSpec.EXACTLY);
            for (QuickActionTileView t : tiles) {
                t.measure(wSpec, hSpec);
            }
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int w = r - l;
            int h = b - t;
            if (w <= 0 || h <= 0) return;

            int tileW = tiles[0].getMeasuredWidth();
            int tileH = tiles[0].getMeasuredHeight();
            int side = Math.min(w, h);
            int gap = Math.round(side * 0.068f);

            int startX = Math.round((w - 2f * tileW - gap) / 2f);
            int startY = Math.round((h - 2f * tileH - gap) / 2f);

            for (int i = 0; i < tiles.length; i++) {
                int col = i % 2;
                int row = i / 2;
                int childLeft = startX + col * (tileW + gap);
                int childTop = startY + row * (tileH + gap);
                tiles[i].layout(childLeft, childTop, childLeft + tileW, childTop + tileH);
            }
        }
    }

    private static final class QuickActionTileView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF tileRect = new RectF();
        private final Path iconClipPath = new Path();
        private final RectF iconClipRect = new RectF();

        private String actionToken = ModuleConfig.QA_HOME;
        private Drawable appIcon;
        private boolean themeDark = true;
        private boolean pressedState;
        private float pressProgress;
        private ValueAnimator pressAnimator;

        QuickActionTileView(Context context) {
            super(context);
            setClickable(true);
            setFocusable(true);
            setOnClickListener(v -> {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                executeQuickAction(getContext(), actionToken, this);
            });
        }

        void setThemeDark(boolean dark) {
            if (this.themeDark != dark) {
                this.themeDark = dark;
                invalidate();
            }
        }

        void bindAction(String token) {
            String normalized = token != null ? token : ModuleConfig.QA_HOME;
            this.actionToken = normalized;
            if (normalized.startsWith(ModuleConfig.QA_APP_PREFIX)) {
                String target = normalized.substring(ModuleConfig.QA_APP_PREFIX.length()).trim();
                Drawable cached = PinnedAppsWidget.getCachedAppIcon(target);
                this.appIcon = cached;
                if (cached == null && !target.isEmpty()) {
                    final Context appCtx = getContext() != null ? getContext().getApplicationContext() : null;
                    final Context loadCtx = appCtx != null ? appCtx : getContext();
                    final String expectedToken = normalized;
                    PREVIEW_IO.execute(() -> {
                        Drawable loaded = PinnedAppsWidget.loadAppIcon(loadCtx, target);
                        String label = localizedActionLabel(loadCtx, expectedToken);
                        post(() -> {
                            if (expectedToken.equals(actionToken)) {
                                appIcon = loaded;
                                setContentDescription(label);
                                invalidate();
                            }
                        });
                    });
                }
            } else {
                this.appIcon = null;
            }
            setContentDescription(localizedActionLabel(getContext(), normalized));
            invalidate();
        }

        @Override
        protected void onDetachedFromWindow() {
            if (pressAnimator != null) {
                pressAnimator.cancel();
                pressAnimator = null;
            }
            pressedState = false;
            pressProgress = 0f;
            setScaleX(1f);
            setScaleY(1f);
            super.onDetachedFromWindow();
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.Button");
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                animatePress(true);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                animatePress(false);
            } else if (action == MotionEvent.ACTION_MOVE) {
                float x = event.getX();
                float y = event.getY();
                if (x < 0 || y < 0 || x > getWidth() || y > getHeight()) {
                    animatePress(false);
                }
            }
            return super.onTouchEvent(event);
        }

        private void animatePress(boolean pressed) {
            if (this.pressedState == pressed) return;
            this.pressedState = pressed;
            if (pressAnimator != null) pressAnimator.cancel();
            pressAnimator = ValueAnimator.ofFloat(pressProgress, pressed ? 1f : 0f);
            pressAnimator.setDuration(pressed ? 105L : 190L);
            pressAnimator.setInterpolator(PRESS_INTERPOLATOR);
            pressAnimator.addUpdateListener(a -> {
                pressProgress = (Float) a.getAnimatedValue();
                float scale = 1f - 0.075f * pressProgress;
                setScaleX(scale);
                setScaleY(scale);
                invalidate();
            });
            pressAnimator.start();
        }

        private boolean isToggleActive() {
            if (ModuleConfig.QA_TORCH.equals(actionToken)) {
                return torchEnabled;
            }
            if (ModuleConfig.QA_RINGER.equals(actionToken)) {
                return currentRingerMode != AudioManager.RINGER_MODE_NORMAL;
            }
            if (ModuleConfig.QA_ROTATION_LOCK.equals(actionToken)) {
                return rotationLocked;
            }
            if (ModuleConfig.QA_BRIGHTNESS.equals(actionToken)) {
                return !brightnessAuto;
            }
            return false;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            if (w <= 0f || h <= 0f) return;

            float radius = Math.min(w, h) * 0.28f;
            float inset = 0.75f;
            tileRect.set(inset, inset, w - inset, h - inset);

            boolean active = isToggleActive();
            int baseFill;
            int pressedFill;
            int borderColor;
            int glyphColor;

            if (active) {
                if (themeDark) {
                    baseFill = 0xEBFFFFFF;
                    pressedFill = 0xFFFFFFFF;
                    borderColor = 0x33FFFFFF;
                    glyphColor = 0xFF15181E;
                } else {
                    baseFill = 0xE0181C24;
                    pressedFill = 0xF2181C24;
                    borderColor = 0x26000000;
                    glyphColor = 0xFFFFFFFF;
                }
            } else if (themeDark) {
                baseFill = 0x24FFFFFF;
                pressedFill = 0x40FFFFFF;
                borderColor = 0x22FFFFFF;
                glyphColor = 0xF2FFFFFF;
            } else {
                baseFill = 0x12000000;
                pressedFill = 0x26000000;
                borderColor = 0x16000000;
                glyphColor = 0xE614171F;
            }

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(blendColor(baseFill, pressedFill, pressProgress));
            canvas.drawRoundRect(tileRect, radius, radius, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f);
            paint.setColor(borderColor);
            canvas.drawRoundRect(tileRect, radius, radius, paint);

            float cx = w / 2f;
            float cy = h / 2f;
            float minDim = Math.min(w, h);

            if (actionToken.startsWith(ModuleConfig.QA_APP_PREFIX) && appIcon != null) {
                float iconSize = minDim * 0.64f;
                float half = iconSize / 2f;
                float iconR = iconSize * 0.25f;
                iconClipRect.set(cx - half, cy - half, cx + half, cy + half);
                iconClipPath.reset();
                iconClipPath.addRoundRect(iconClipRect, iconR, iconR, Path.Direction.CW);
                canvas.save();
                canvas.clipPath(iconClipPath);
                appIcon.setBounds(
                        Math.round(iconClipRect.left),
                        Math.round(iconClipRect.top),
                        Math.round(iconClipRect.right),
                        Math.round(iconClipRect.bottom));
                appIcon.draw(canvas);
                canvas.restore();
                if (ModuleConfig.extractAppTargetUserId(actionToken) > 0) {
                    float badgeR = iconSize * 0.16f;
                    float bx = iconClipRect.right - badgeR * 0.55f;
                    float by = iconClipRect.bottom - badgeR * 0.55f;
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(0xFFF57C00);
                    canvas.drawCircle(bx, by, badgeR, paint);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(Math.max(1.5f, badgeR * 0.25f));
                    paint.setColor(0xFFFFFFFF);
                    canvas.drawCircle(bx, by, badgeR, paint);
                }
                return;
            }

            drawActionGlyph(canvas, actionToken, cx, cy, minDim * 0.44f, glyphColor, active, currentRingerMode);
        }

        private static int blendColor(int c1, int c2, float t) {
            float inv = 1f - t;
            int a = Math.round(Color.alpha(c1) * inv + Color.alpha(c2) * t);
            int r = Math.round(Color.red(c1) * inv + Color.red(c2) * t);
            int g = Math.round(Color.green(c1) * inv + Color.green(c2) * t);
            int b = Math.round(Color.blue(c1) * inv + Color.blue(c2) * t);
            return Color.argb(a, r, g, b);
        }
    }
}
