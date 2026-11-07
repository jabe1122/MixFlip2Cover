package io.github.jabe1122.mixflip2cover;

import android.os.SystemClock;
import java.lang.reflect.Method;

/**
 * Runtime handshake state published by system_server once framework hooks are active.
 *
 * {@link #VERSION} is a framework/client protocol number, not the app version: it only changes when the
 * system_server side contract changes, so app processes can tell whether a reboot is still required.
 */
final class FrameworkRuntime {
    static final String READY = "debug.mixflip2cover.fw_ready";
    static final String VERSION = "100";
    static final String ACTION = "io.github.jabe1122.mixflip2cover.FW_WINDOW_STATE";
    /** Sent by the settings app (identity shared) to system_server to undo every persistent system change. */
    static final String ACTION_RESTORE = "io.github.jabe1122.mixflip2cover.RESTORE_SYSTEM";

    private static volatile Method propGet;
    private static volatile boolean readyCached;
    private static volatile long lastCheck;

    private FrameworkRuntime() {}

    static String readProperty() {
        try {
            Method m = propGet;
            if (m == null) {
                m = Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class);
                propGet = m;
            }
            return (String) m.invoke(null, READY, "");
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * The property is set once per system_server lifetime and every app process is restarted together with
     * system_server, so a positive result is cached for the process lifetime; negative results are
     * re-checked at most once per second.
     */
    static boolean active() {
        if (readyCached) return true;
        long now = SystemClock.uptimeMillis();
        if (now - lastCheck < 1000L && lastCheck != 0L) return false;
        lastCheck = now;
        boolean ready = VERSION.equals(readProperty());
        if (ready) readyCached = true;
        return ready;
    }
}
