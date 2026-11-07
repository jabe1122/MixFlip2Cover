package io.github.jabe1122.mixflip2cover;

import android.app.Application;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * Cover-display status panel, launcher integration, and universal client-app compatibility entry.
 */
public final class SystemUiEntry extends XposedModule {
    private static final String TAG = "MF2C.SystemUI";
    private static final String[] NATIVE_STATUS_CLASSES = {
            "com.android.systemui.statusbar.views.MiuiClock",
            "com.android.systemui.statusbar.views.MiuiBatteryMeterView",
            "com.android.systemui.statusbar.policy.Clock"
    };

    private static volatile boolean systemUiProcessActive;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Object, Object> controlListeners = Collections.synchronizedMap(new WeakHashMap<>());
    /** One settings listener per attached launcher widget overlay; removed again on detach. */
    private final Map<View, Runnable> widgetListeners = new WeakHashMap<>();
    private SystemUiOrb orb;
    private WeakReference<Object> candidateControllerRef;
    private Method addCallback;
    private Method getExpanded;
    private Class<?> listenerType;
    private Boolean notificationExpanded = Boolean.FALSE;
    private Boolean controlExpanded;

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!param.isFirstPackage()) return;
        String pkg = param.getPackageName();
        if ("android".equals(pkg) || "system".equals(pkg)) return;

        if (!CoverDisplay.isSupportedFirmware()) {
            Log.w(TAG, "unsupported build: no hooks installed");
            return;
        }
        if (!CoverGeometry.SYSTEMUI.equals(pkg) && !AppCompatibility.supports(pkg)) return;

        try {
            ModuleConfig.init(this);
        } catch (Throwable t) {
            Log.e(TAG, "preferences unavailable", t);
            return;
        }

        if (!CoverGeometry.SYSTEMUI.equals(pkg)) {
            if (!CoverGeometry.FLIPHOME.equals(pkg)
                    && AppCompatibility.isPackedOrProtectedApp(param.getApplicationInfo())) {
                Log.w(TAG, "skipping in-process hooks for packed package " + pkg + "; handled by system_server");
                return;
            }
            try {
                AppCompatibility.install(this, param.getClassLoader(), pkg);
            } catch (Throwable t) {
                Log.w(TAG, "application window hooks unavailable", t);
            }
        }

        if (CoverGeometry.FLIPHOME.equals(pkg)) {
            ClassLoader loader = param.getClassLoader();
            try {
                CoverResourceOverlay.installCoreResourceGuard(this);
            } catch (Throwable t) {
                Log.w(TAG, "resource guard unavailable", t);
            }
            try {
                SquareCards.install(this, pkg);
                WidgetAnchor.install(this, loader);
            } catch (Throwable t) {
                Log.w(TAG, "native widget region unavailable", t);
            }
            try {
                DesktopAlignment.install(this, loader);
            } catch (Throwable t) {
                Log.w(TAG, "desktop layout hooks unavailable", t);
            }
            try {
                LauncherPullDown.install(this, loader);
            } catch (Throwable t) {
                Log.w(TAG, "launcher pull-down priority unavailable", t);
            }
            try {
                UnlockMotion.install(this, loader);
            } catch (Throwable t) {
                Log.w(TAG, "unlock frame mirror unavailable", t);
            }
            installWidgetHide(param);
            return;
        }

        if (!CoverGeometry.SYSTEMUI.equals(pkg)) return;
        systemUiProcessActive = true;

        try {
            CoverResourceOverlay.installCoreResourceGuard(this);
        } catch (Throwable t) {
            Log.w(TAG, "resource guard unavailable", t);
        }
        try {
            SquareCards.install(this, pkg);
            ImeAvoidance.install(this, param.getClassLoader());
        } catch (Throwable t) {
            Log.w(TAG, "square cards or keyboard observer unavailable", t);
        }
        try {
            LiveIsland.install(this, param.getClassLoader());
        } catch (Throwable t) {
            Log.w(TAG, "native live-card hooks unavailable", t);
        }
        try {
            SystemBarMask.install(this, param.getClassLoader());
        } catch (Throwable t) {
            Log.w(TAG, "startup status-bar mask unavailable", t);
        }
        try {
            PrivacyAnchor.install(this, param.getClassLoader());
        } catch (Throwable t) {
            Log.w(TAG, "privacy anchor unavailable", t);
        }
        try {
            PanelMotion.install(this, param.getClassLoader());
        } catch (Throwable t) {
            Log.w(TAG, "panel animation observer unavailable", t);
        }
        try {
            CoverNotificationTop.install(this, param.getClassLoader());
        } catch (Throwable t) {
            Log.w(TAG, "cover notification top inset hooks unavailable", t);
        }
        installTinyNotificationClickBypass(param.getClassLoader());
        try {
            for (Method method : param.getClassLoader().loadClass("com.android.systemui.statusbar.CommandQueue").getDeclaredMethods()) {
                if (method.getName().equals("appTransitionPending") || method.getName().equals("appTransitionStarting")) {
                    hook(method).intercept(chain -> {
                        Object result = chain.proceed();
                        if (chain.getArg(0) instanceof Integer display) {
                            main.post(() -> CoverAppPolicy.transitionStart(display));
                        }
                        return result;
                    });
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "transition observer unavailable", t);
        }

        List<HookHandle> handles = new ArrayList<>();
        try {
            ClassLoader loader = param.getClassLoader();
            // Native clock/battery views are recorded so the cover panel can mirror their font and tint.
            // Each class is hooked on its own onAttachedToWindow; a process-wide View hook is only installed
            // as a fallback when one of them does not declare that method on this build.
            boolean needGlobalAttachHook = false;
            for (String nativeClass : NATIVE_STATUS_CLASSES) {
                Class<?> nativeView;
                try {
                    nativeView = loader.loadClass(nativeClass);
                } catch (ClassNotFoundException e) {
                    continue;
                }
                try {
                    Method darkChanged = nativeView.getDeclaredMethod("onDarkChanged", ArrayList.class, float.class, int.class);
                    handles.add(hook(darkChanged).intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            NativeStatusStyle.tintChanged();
                        } catch (Throwable ignored) {
                        }
                        return result;
                    }));
                } catch (NoSuchMethodException ignored) {
                }
                try {
                    handles.add(hook(nativeView.getDeclaredMethod("onAttachedToWindow")).intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            if (chain.getThisObject() instanceof View view) {
                                NativeStatusStyle.capture(view);
                                view.post(() -> NativeStatusStyle.capture(view));
                            }
                        } catch (Throwable ignored) {
                        }
                        return result;
                    }));
                } catch (NoSuchMethodException e) {
                    needGlobalAttachHook = true;
                }
            }
            if (needGlobalAttachHook) {
                handles.add(hook(View.class.getDeclaredMethod("onAttachedToWindow")).intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        if (chain.getThisObject() instanceof View view) NativeStatusStyle.capture(view);
                    } catch (Throwable ignored) {
                    }
                    return result;
                }));
            }

            Class<?> appClass = loader.loadClass("com.android.systemui.application.impl.SystemUIApplicationImpl");
            Class<?> shadeClass = loader.loadClass("com.android.systemui.shade.NotificationShadeWindowControllerImpl");
            Class<?> controlClass = loader.loadClass("com.miui.systemui.controlcenter.ControlCenterImpl");
            listenerType = loader.loadClass("com.miui.interfaces.shade.ShadeWrapper$OnExpandChangedListener");
            if (!Application.class.isAssignableFrom(appClass) || !listenerType.isInterface()) {
                throw new IllegalStateException("unexpected application/listener class");
            }
            Method onCreate = exactMethod(appClass, "onCreate", void.class);
            Method shadeChanged = exactMethod(shadeClass, "onShadeOrQsExpanded", void.class, Boolean.class);
            addCallback = exactMethod(controlClass, "addCallback", void.class, listenerType);
            getExpanded = exactMethod(controlClass, "getExpanded", boolean.class);
            exactMethod(listenerType, "onExpandChanged", void.class, boolean.class);
            Constructor<?>[] constructors = controlClass.getDeclaredConstructors();
            if (constructors.length == 0) throw new IllegalStateException("no control center constructor");

            handles.add(hook(onCreate).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    if (chain.getThisObject() instanceof Application app) {
                        main.post(() -> startObserver(app));
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "application callback skipped", t);
                }
                return result;
            }));
            handles.add(hook(shadeChanged).intercept(chain -> {
                Object arg = chain.getArg(0);
                if (!Boolean.FALSE.equals(arg)) postNotification(arg instanceof Boolean ? (Boolean) arg : null);
                Object result = chain.proceed();
                if (Boolean.FALSE.equals(arg)) postNotification(false);
                return result;
            }));
            handles.add(hook(addCallback).intercept(chain -> {
                Object result = chain.proceed();
                Object controller = chain.getThisObject();
                if (controller != null) main.post(() -> observeController(controller));
                return result;
            }));
            for (Constructor<?> constructor : constructors) {
                constructor.setAccessible(true);
                handles.add(hook(constructor).intercept(chain -> {
                    Object result = chain.proceed();
                    Object controller = chain.getThisObject();
                    if (controller != null) main.post(() -> observeController(controller));
                    return result;
                }));
            }
            ModLog.i(TAG, "system UI hooks ready");
        } catch (Throwable t) {
            for (HookHandle h : handles) {
                try {
                    h.unhook();
                } catch (Throwable ignored) {
                }
            }
            Log.e(TAG, "setup failed: hooks rolled back", t);
        }
    }

    static boolean coverNotificationDirectOpenActive() {
        return (!systemUiProcessActive || FrameworkRuntime.active())
                && ModuleConfig.directOpenCoverNotifications()
                && ModuleConfig.allowAllCoverApps();
    }

    /**
     * ArrayList wrapper for {@code NotificationSettingsManager.mContinuityPackagesWhiteList} that treats every
     * package as whitelisted when {@link #coverNotificationDirectOpenActive()} is enabled, bypassing the
     * cover-screen "view on inner screen" modal interception when tapping notifications.
     */
    static final class CoverContinuityWhitelist extends ArrayList<String> {
        CoverContinuityWhitelist(Collection<String> initial) {
            super(initial != null ? initial : Collections.emptyList());
        }

        @Override
        public boolean contains(Object o) {
            if (coverNotificationDirectOpenActive()) {
                return true;
            }
            return super.contains(o);
        }
    }

    @SuppressWarnings("unchecked")
    private static void ensureContinuityWhitelist(Object settingsMgr, Field whitelistField) {
        if (settingsMgr == null || whitelistField == null) return;
        try {
            Object current = whitelistField.get(settingsMgr);
            if (!(current instanceof CoverContinuityWhitelist)) {
                Collection<String> src = current instanceof Collection<?> c
                        ? (Collection<String>) c
                        : Collections.emptyList();
                whitelistField.set(settingsMgr, new CoverContinuityWhitelist(src));
            }
        } catch (Throwable ignored) {
        }
    }

    private void installTinyNotificationClickBypass(ClassLoader loader) {
        try {
            Class<?> nsmClass = loader.loadClass("com.miui.systemui.notification.NotificationSettingsManager");
            Field whitelistField = nsmClass.getDeclaredField("mContinuityPackagesWhiteList");
            whitelistField.setAccessible(true);
            Field sInstanceField = null;
            try {
                sInstanceField = nsmClass.getDeclaredField("sINSTANCE");
                sInstanceField.setAccessible(true);
                ensureContinuityWhitelist(sInstanceField.get(null), whitelistField);
            } catch (Throwable ignored) {
            }
            for (Constructor<?> ctor : nsmClass.getDeclaredConstructors()) {
                ctor.setAccessible(true);
                hook(ctor).intercept(chain -> {
                    Object res = chain.proceed();
                    ensureContinuityWhitelist(chain.getThisObject(), whitelistField);
                    return res;
                });
            }
            for (Method m : nsmClass.getDeclaredMethods()) {
                String name = m.getName();
                if ("onCloudDataUpdated".equals(name) || "onLocalDataUsed".equals(name)) {
                    m.setAccessible(true);
                    hook(m).intercept(chain -> {
                        Object res = chain.proceed();
                        ensureContinuityWhitelist(chain.getThisObject(), whitelistField);
                        return res;
                    });
                }
            }
            final Field sInst = sInstanceField;
            hookNotificationStarterMethods(
                    loader,
                    "com.android.systemui.statusbar.phone.StatusBarNotificationActivityStarter",
                    sInst,
                    whitelistField,
                    "startNotificationIntentPublic",
                    "performActionAfterKeyguardDismissed",
                    "launchFullScreenIntent",
                    "startNotificationGutsIntent",
                    "onNotificationClicked");
            hookNotificationStarterMethods(
                    loader,
                    "com.android.systemui.statusbar.phone.StatusBarNotificationActivityStarterInjectorImpl",
                    sInst,
                    whitelistField,
                    "performActionAfterKeyguardDismissed",
                    "shouldInterceptFocusClick");
            try {
                Class<?> lambdaClass = loader.loadClass(
                        "com.android.systemui.statusbar.phone.StatusBarNotificationActivityStarter$$ExternalSyntheticLambda5");
                Method runMethod = lambdaClass.getDeclaredMethod("run");
                runMethod.setAccessible(true);
                hook(runMethod).intercept(chain -> {
                    if (sInst != null) {
                        try {
                            ensureContinuityWhitelist(sInst.get(null), whitelistField);
                        } catch (Throwable ignored) {
                        }
                    }
                    return chain.proceed();
                });
            } catch (Throwable ignored) {
            }
            ModLog.i(TAG, "cover notification whitelist bypass ready");
        } catch (Throwable t) {
            Log.w(TAG, "cover notification whitelist bypass unavailable", t);
        }

        try {
            Class<?> reflectUtilsClass = loader.loadClass("com.miui.clock.utils.ReflectUtils");
            for (Method m : reflectUtilsClass.getDeclaredMethods()) {
                if ("invokeObject".equals(m.getName())) {
                    m.setAccessible(true);
                    hook(m).intercept(chain -> {
                        try {
                            List<Object> args = chain.getArgs();
                            if (args.size() >= 3
                                    && "isInterceptActivity".equals(args.get(2))
                                    && coverNotificationDirectOpenActive()) {
                                return Boolean.FALSE;
                            }
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    });
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "cover notification ReflectUtils bypass unavailable", t);
        }
    }

    private void hookNotificationStarterMethods(
            ClassLoader loader,
            String className,
            Field sInstanceField,
            Field whitelistField,
            String... methodNames) {
        if (sInstanceField == null || whitelistField == null) return;
        try {
            Class<?> targetClass = loader.loadClass(className);
            for (Method m : targetClass.getDeclaredMethods()) {
                if (m.isBridge() || Modifier.isAbstract(m.getModifiers())) continue;
                String name = m.getName();
                boolean match = false;
                for (String expected : methodNames) {
                    if (expected.equals(name)) {
                        match = true;
                        break;
                    }
                }
                if (!match) continue;
                m.setAccessible(true);
                hook(m).intercept(chain -> {
                    try {
                        ensureContinuityWhitelist(sInstanceField.get(null), whitelistField);
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                });
            }
        } catch (Throwable ignored) {
        }
    }

    private void installWidgetHide(PackageReadyParam param) {
        try {
            Class<?> fading = param.getClassLoader().loadClass("com.miui.fliphome.view.LauncherFadingView");
            hook(fading.getDeclaredMethod("onDraw", Canvas.class)).intercept(chain ->
                    ModuleConfig.immersiveEnabled() ? null : chain.proceed());
        } catch (Throwable t) {
            Log.w(TAG, "desktop fading view unavailable", t);
        }

        try {
            Class<?> widget = param.getClassLoader().loadClass("com.miui.fliphome.widget.ui.WatchOverlayGroupView");
            hook(View.class.getDeclaredMethod("setVisibility", int.class)).intercept(chain -> {
                // Class check first; every other view returns straight to the original method.
                if (widget.isInstance(chain.getThisObject()) && hideNativeWidget()) {
                    return chain.proceed(new Object[]{View.GONE});
                }
                return chain.proceed();
            });
            Method attach;
            try {
                attach = widget.getDeclaredMethod("onAttachedToWindow");
            } catch (NoSuchMethodException e) {
                attach = View.class.getDeclaredMethod("onAttachedToWindow");
            }
            hook(attach).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    if (widget.isInstance(chain.getThisObject())) onWidgetAttached((View) chain.getThisObject());
                } catch (Throwable t) {
                    Log.w(TAG, "cover widget visibility sync skipped", t);
                }
                return result;
            });
            ModLog.i(TAG, "cover widget hide hooks ready");
        } catch (Throwable t) {
            Log.e(TAG, "widget hide unavailable", t);
        }
    }

    private static boolean hideNativeWidget() {
        return ModuleConfig.panelEnabled() && !ModuleConfig.liveIslandEnabled();
    }

    private void onWidgetAttached(View widgetView) {
        if (!widgetListeners.containsKey(widgetView)) {
            int original = widgetView.getVisibility();
            java.lang.ref.WeakReference<View> reference = new java.lang.ref.WeakReference<>(widgetView);
            Runnable listener = () -> {
                View v = reference.get();
                if (v != null) v.setVisibility(hideNativeWidget() ? View.GONE : original);
            };
            widgetListeners.put(widgetView, listener);
            ModuleConfig.addListener(listener);
            widgetView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    Runnable r = widgetListeners.remove(v);
                    if (r != null) ModuleConfig.removeListener(r);
                    v.removeOnAttachStateChangeListener(this);
                }
            });
        }
        if (hideNativeWidget()) widgetView.setVisibility(View.GONE);
        ModLog.i(TAG, "cover widget hidden; configuration preserved");
    }

    private void startObserver(Application app) {
        try {
            if (orb != null) return;
            ModLog.registerLogCollector(app, "com.android.systemui");
            // Status-bar overlay and cover IME guard are owned by system_server (SystemServerEntry), which also
            // reverts them on shutdown/restore; running them here as well would apply every change twice.
            SystemBarMask.observe(app);
            PrivacyAnchor.observe(app);
            NativeStatusStyle.registerTransitions(app);
            orb = new SystemUiOrb(app);
            ImeAvoidance.start(app, () -> {
                orb.requestRefresh();
                LiveIsland.refresh();
            }, true);
            LiveIsland.start(app);
            PanelMotion.start(app, value -> {
                orb.setPanelProgress(value);
                LiveIsland.setPanelProgress(value);
            });
            LauncherPullDown.start(app);
            UnlockMotion.start(app, () -> {
                orb.applyPresentation();
                LiveIsland.presentation();
            });
            CoverAppPolicy.start(app, () -> {
                orb.requestRefresh();
                LiveIsland.presentation();
                SystemBarMask.refresh();
            });
            orb.start();
            ModuleConfig.addListener(this::publish);
            publish();
            Object pendingController = candidateControllerRef != null ? candidateControllerRef.get() : null;
            if (pendingController != null) observeController(pendingController);
        } catch (Throwable t) {
            Log.e(TAG, "observer unavailable", t);
        }
    }

    private void postNotification(Boolean expanded) {
        main.post(() -> {
            notificationExpanded = expanded;
            ModLog.i(TAG, "notification callback=" + expanded);
            publish();
        });
    }

    private void observeController(Object controller) {
        try {
            candidateControllerRef = new WeakReference<>(controller);
            PanelMotion.controlCenter = controller;
            if (orb == null || controlListeners.containsKey(controller)) return;
            WeakReference<Object> controllerRef = new WeakReference<>(controller);
            Object listener = Proxy.newProxyInstance(
                    listenerType.getClassLoader(),
                    new Class<?>[]{listenerType},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                            if ("equals".equals(method.getName())) return args != null && args.length == 1 && proxy == args[0];
                            if ("toString".equals(method.getName())) return "MF2CExpandListener";
                        }
                        if ("onExpandChanged".equals(method.getName()) && args != null && args.length == 1
                                && args[0] instanceof Boolean value) {
                            main.post(() -> {
                                Object target = controllerRef.get();
                                if (target != null) onControlChanged(target, value);
                            });
                        }
                        return null;
                    });
            controlListeners.put(controller, listener);
            try {
                addCallback.invoke(controller, listener);
            } catch (Throwable t) {
                controlListeners.remove(controller);
                throw t;
            }
            try {
                Object initial = getExpanded.invoke(controller);
                if (initial instanceof Boolean b) {
                    controlExpanded = b;
                }
            } catch (Throwable ignored) {
            }
            ModLog.i(TAG, "control listener registered; initial expanded=" + controlExpanded);
            publish();
        } catch (Throwable t) {
            controlExpanded = null;
            publish();
            Log.e(TAG, "control listener unavailable", t);
        }
    }

    private void onControlChanged(Object controller, boolean expanded) {
        try {
            Object actual = getExpanded.invoke(controller);
            controlExpanded = actual instanceof Boolean && ((Boolean) actual) == expanded ? expanded : null;
            ModLog.i(TAG, "control callback=" + expanded + ", consistent=" + (controlExpanded != null));
        } catch (Throwable t) {
            controlExpanded = null;
            Log.e(TAG, "control state unavailable", t);
        }
        publish();
    }

    private void publish() {
        if (orb == null) return;
        boolean blocked = !ModuleConfig.panelEnabled() || notificationExpanded == null || controlExpanded == null;
        if (blocked) {
            orb.setSystemPanelExpanded(true);
            return;
        }
        PanelMotion.expanded(notificationExpanded, controlExpanded);
    }

    private static Method exactMethod(Class<?> owner, String name, Class<?> result, Class<?>... args)
            throws ReflectiveOperationException {
        Method method = owner.getDeclaredMethod(name, args);
        if (method.getReturnType() != result) throw new NoSuchMethodException(name + " return type");
        method.setAccessible(true);
        return method;
    }
}
