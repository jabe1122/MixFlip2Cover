package io.github.jabe1122.mixflip2cover;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;

/** Physical slots stay in SIM1/SIM2 order; the data badge follows the data subscription. */
final class NetworkStatus {
    final int[] signals = {-1, -1};
    boolean airplane, hotspot;
    String technology = "";
    private static java.lang.reflect.Method wifiApMethod;
    private static java.lang.reflect.Method miuiLevelMethod;
    private static java.lang.reflect.Method uiccEnabledMethod;
    private static boolean reflectionResolved;
    private final Context context;
    private final Runnable changed;
    private TelephonyManager callbackManager;
    private DisplayCallback callback;
    private TelephonyDisplayInfo displayInfo;
    private int callbackSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private int failedSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private boolean watching;

    NetworkStatus(Context context, Runnable changed) {
        this.context = context;
        this.changed = changed;
    }

    void start() { watching = true; failedSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID; }
    void stop() { watching = false; clearCallback(); }

    boolean dualSimActive() {
        return signals[0] >= 0 && signals[1] >= 0;
    }

    int primarySignal() {
        return signals[0] >= 0 ? signals[0] : signals[1];
    }

    void read() {
        if (!reflectionResolved) {
            reflectionResolved = true;
            try { wifiApMethod = WifiManager.class.getMethod("isWifiApEnabled"); } catch (ReflectiveOperationException ignored) { }
            try { miuiLevelMethod = SignalStrength.class.getMethod("getMiuiLevel"); } catch (ReflectiveOperationException ignored) { }
            try { uiccEnabledMethod = SubscriptionInfo.class.getMethod("areUiccApplicationsEnabled"); } catch (ReflectiveOperationException ignored) { }
        }
        airplane = Settings.Global.getInt(context.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
        hotspot = false;
        try {
            WifiManager wifi = context.getSystemService(WifiManager.class);
            if (wifi != null && wifiApMethod != null) hotspot = Boolean.TRUE.equals(wifiApMethod.invoke(wifi));
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        SubscriptionManager subscriptions = context.getSystemService(SubscriptionManager.class);
        TelephonyManager telephony = context.getSystemService(TelephonyManager.class);
        for (int slot = 0; slot < 2; slot++) {
            signals[slot] = -1;
            try {
                SubscriptionInfo sim = subscriptions == null ? null : subscriptions.getActiveSubscriptionInfoForSimSlotIndex(slot);
                if (sim == null || telephony == null || airplane) continue;
                if (uiccEnabledMethod != null) {
                    try {
                        if (Boolean.FALSE.equals(uiccEnabledMethod.invoke(sim))) continue;
                    } catch (ReflectiveOperationException ignored) { }
                }
                int simState = telephony.getSimState(slot);
                if (simState == TelephonyManager.SIM_STATE_ABSENT
                        || simState == TelephonyManager.SIM_STATE_NOT_READY
                        || simState == TelephonyManager.SIM_STATE_UNKNOWN) continue;
                signals[slot] = 0;
                TelephonyManager manager = telephony.createForSubscriptionId(sim.getSubscriptionId());
                ServiceState service = manager.getServiceState();
                if (service != null && service.getState() != ServiceState.STATE_IN_SERVICE) {
                    continue;
                }
                SignalStrength strength = manager.getSignalStrength();
                if (strength == null) continue;
                int level = strength.getLevel();
                if (miuiLevelMethod != null) {
                    try { level = (Integer) miuiLevelMethod.invoke(strength); }
                    catch (ReflectiveOperationException ignored) { }
                }
                signals[slot] = Math.min(4, Math.max(0, level));
            } catch (RuntimeException ignored) { }
        }
        technology = "";
        try {
            int subId = SubscriptionManager.getDefaultDataSubscriptionId();
            if (telephony == null || !SubscriptionManager.isValidSubscriptionId(subId)) {
                clearCallback();
                return;
            }
            TelephonyManager data = telephony.createForSubscriptionId(subId);
            watchDataSubscription(data, subId);
            if (airplane || !data.isDataEnabled()) return;
            ServiceState service = data.getServiceState();
            if (service != null && service.getState() != ServiceState.STATE_IN_SERVICE) return;
            int type = displayInfo != null && displayInfo.getNetworkType() != TelephonyManager.NETWORK_TYPE_UNKNOWN
                    ? displayInfo.getNetworkType() : data.getDataNetworkType();
            int override = displayInfo != null ? displayInfo.getOverrideNetworkType() : TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE;
            technology = technology(type, override);
        } catch (RuntimeException ignored) { }
    }

    static String technology(int type, int override) {
        if (override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA
                || override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED) return "5G";
        if (override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA
                || override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO) return "4G+";
        return switch (type) {
            case TelephonyManager.NETWORK_TYPE_NR -> "5G";
            case TelephonyManager.NETWORK_TYPE_LTE -> "4G";
            case TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_HSDPA,
                 TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_HSPA,
                 TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_EVDO_0,
                 TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_EVDO_B,
                 TelephonyManager.NETWORK_TYPE_EHRPD, TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "3G";
            case TelephonyManager.NETWORK_TYPE_EDGE -> "E";
            case TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_GSM,
                 TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_1xRTT -> "2G";
            default -> "";
        };
    }

    private void watchDataSubscription(TelephonyManager manager, int subId) {
        if (!watching || failedSubId == subId || (callback != null && callbackSubId == subId)) return;
        clearCallback();
        DisplayCallback next = new DisplayCallback();
        callback = next;
        callbackManager = manager;
        callbackSubId = subId;
        try {
            manager.registerTelephonyCallback(context.getMainExecutor(), next);
        } catch (RuntimeException e) {
            failedSubId = subId;
            android.util.Log.w("MF2C.Orb", "Network display callback unavailable; using reported network type", e);
            clearCallback();
        }
    }

    private void clearCallback() {
        if (callbackManager != null && callback != null) {
            try { callbackManager.unregisterTelephonyCallback(callback); } catch (RuntimeException ignored) { }
        }
        callbackManager = null;
        callback = null;
        displayInfo = null;
        callbackSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    }

    private final class DisplayCallback extends TelephonyCallback implements TelephonyCallback.DisplayInfoListener {
        @Override public void onDisplayInfoChanged(TelephonyDisplayInfo info) {
            if (callback != this || !watching) return;
            displayInfo = info;
            changed.run();
        }
    }
}
