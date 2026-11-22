package io.github.jabe1122.mixflip2cover;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.ResultReceiver;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Verbose logging gate and cross-process in-memory diagnostic ring buffer.
 *
 * <p>Informational logs are emitted to {@code logcat} only for debug builds or when the user enables
 * "verbose log" in the module settings, so release builds do not spam logcat of hooked processes.
 * Every hooked process also retains the most recent {@link #MAX_BUFFER_LINES} log entries in memory so
 * the settings app can inspect, copy, or share cross-process diagnostic logs on demand.
 */
final class ModLog {
    static final String ACTION_COLLECT_LOGS = "io.github.jabe1122.mixflip2cover.COLLECT_LOGS";
    static final String EXTRA_RESULT_RECEIVER = "result_receiver";
    static final String EXTRA_CLEAR_LOGS = "clear_logs";
    static final String EXTRA_PROCESS_LABEL = "process_label";
    static final String EXTRA_LOG_LINES = "log_lines";
    static final String EXTRA_LOGCAT_LINES = "logcat_lines";

    private static final int MAX_BUFFER_LINES = 250;
    private static final DateTimeFormatter TS_FORMATTER =
            DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS", Locale.US);
    private static final ArrayDeque<String> BUFFER = new ArrayDeque<>(MAX_BUFFER_LINES);
    private static final Set<String> REGISTERED_PROCESSES = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<String, String> HOOK_HEALTH = new ConcurrentHashMap<>();
    /**
     * Timestamp marker recorded when the user clears logs so we filter out older logcat lines
     * without running global {@code logcat -c} (which would wipe unrelated system crash logs).
     */
    private static final AtomicReference<String> LOGCAT_CLEAR_MARKER = new AtomicReference<>(null);
    private static volatile String currentProcessLabel = "";

    static final String[] LOGCAT_TAGS = {
            "MF2C.AppPolicy:V",
            "MF2C.Cards:V",
            "MF2C.Compat:V",
            "MF2C.Config:V",
            "MF2C.Desktop:V",
            "MF2C.Framework:V",
            "MF2C.Gesture:V",
            "MF2C.Ime:V",
            "MF2C.ImeGuard:V",
            "MF2C.Island:V",
            "MF2C.LauncherApps:V",
            "MF2C.Layout:V",
            "MF2C.Mask:V",
            "MF2C.NotifTop:V",
            "MF2C.Orb:V",
            "MF2C.Overlay:V",
            "MF2C.Panel:V",
            "MF2C.PinnedWidget:V",
            "MF2C.Privacy:V",
            "MF2C.QuickWidget:V",
            "MF2C.Settings:V",
            "MF2C.SystemUI:V",
            "MF2C.Tint:V",
            "MF2C.Unlock:V",
            "MF2C.WeChat:V",
            "MF2C.WeChatList:V",
            "MF2C.Widget:V",
            "MF2C.WidgetFit:V",
            "LSPosed:I",
            "*:S"
    };

    private ModLog() {}

    static boolean enabled() {
        return BuildConfig.DEBUG || ModuleConfig.verboseLog();
    }

    static void i(String tag, String msg) {
        record("I", tag, msg);
        if (enabled()) {
            try {
                Log.i(tag, msg);
            } catch (Throwable ignored) {
            }
        }
    }

    static void w(String tag, String msg) {
        record("W", tag, msg);
        try {
            Log.w(tag, msg);
        } catch (Throwable ignored) {
        }
    }

    static void e(String tag, String msg, Throwable t) {
        String full = t != null ? (msg + " (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")") : msg;
        record("E", tag, full);
        try {
            Log.e(tag, msg, t);
        } catch (Throwable ignored) {
        }
    }

    private static void record(String level, String tag, String msg) {
        try {
            String ts = TS_FORMATTER.format(LocalDateTime.now());
            int pid = 0;
            try {
                pid = Process.myPid();
            } catch (Throwable ignored) {
            }
            String proc = currentProcessLabel;
            String prefix = (proc != null && !proc.isEmpty())
                    ? String.format(Locale.US, "%s [%s:%d] %s/%s: ", ts, proc, pid, level, tag)
                    : String.format(Locale.US, "%s [%d] %s/%s: ", ts, pid, level, tag);
            String line = prefix + (msg != null ? msg : "");
            synchronized (BUFFER) {
                if (BUFFER.size() >= MAX_BUFFER_LINES) {
                    BUFFER.pollFirst();
                }
                BUFFER.addLast(line);
            }
        } catch (Throwable ignored) {
        }
    }

    static void recordHookHealth(String key, String status) {
        if (key == null || key.isEmpty()) return;
        HOOK_HEALTH.put(key, status != null ? status : "");
    }

    static String formatHookHealthSummary() {
        if (HOOK_HEALTH.isEmpty()) return "";
        TreeMap<String, String> sorted = new TreeMap<>(HOOK_HEALTH);
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    static ArrayList<String> snapshot() {
        synchronized (BUFFER) {
            return new ArrayList<>(BUFFER);
        }
    }

    static void clear() {
        synchronized (BUFFER) {
            BUFFER.clear();
        }
        try {
            LOGCAT_CLEAR_MARKER.set(TS_FORMATTER.format(LocalDateTime.now()));
        } catch (Throwable ignored) {
        }
    }

    static boolean isLogcatLineAtOrAfter(String line, String clearMarker) {
        if (clearMarker == null || clearMarker.length() < 18) return true;
        if (line == null || line.length() < 18) return false;
        if (line.charAt(2) != '-' || line.charAt(5) != ' '
                || line.charAt(8) != ':' || line.charAt(11) != ':' || line.charAt(14) != '.') {
            return false;
        }
        String ts = line.substring(0, 18);
        boolean markerDec = clearMarker.startsWith("12-");
        boolean markerJan = clearMarker.startsWith("01-");
        boolean lineDec = ts.startsWith("12-");
        boolean lineJan = ts.startsWith("01-");
        if (markerDec && lineJan) return true;
        if (markerJan && lineDec) return false;
        return ts.compareTo(clearMarker) >= 0;
    }

    /**
     * Registers an exported broadcast receiver in a hooked process ({@code system_server},
     * {@code com.android.systemui}, or {@code com.miui.fliphome}) so {@link MainActivity} can collect
     * or clear in-memory diagnostic logs across processes.
     */
    static void registerLogCollector(Context context, String processLabel) {
        if (context == null || processLabel == null || processLabel.isEmpty()) return;
        currentProcessLabel = processLabel;
        if (!REGISTERED_PROCESSES.add(processLabel)) return;
        try {
            Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            Handler handler = new Handler(Looper.getMainLooper());
            app.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    if (intent == null || !ACTION_COLLECT_LOGS.equals(intent.getAction())) return;
                    try {
                        int sender = getSentFromUid();
                        if (sender < 0) return;
                        boolean trusted = BuildConfig.APPLICATION_ID.equals(getSentFromPackage());
                        if (!trusted) {
                            int moduleUid = c.getPackageManager().getPackageUid(
                                    BuildConfig.APPLICATION_ID,
                                    PackageManager.PackageInfoFlags.of(0));
                            trusted = (sender % 100000 == moduleUid % 100000);
                        }
                        if (!trusted) {
                            return;
                        }
                        boolean clearRequested = intent.getBooleanExtra(EXTRA_CLEAR_LOGS, false);
                        if (clearRequested) {
                            clear();
                        }
                        ResultReceiver receiver = intent.getParcelableExtra(EXTRA_RESULT_RECEIVER, ResultReceiver.class);
                        if (receiver == null) return;
                        ArrayList<String> lines = snapshot();
                        if (!clearRequested) {
                            String health = formatHookHealthSummary();
                            if (!health.isEmpty()) {
                                lines.add(0, "[hook_health] " + health);
                            }
                        }
                        if ("system_server".equals(processLabel) && !clearRequested) {
                            new Thread(() -> {
                                Bundle b = new Bundle();
                                b.putString(EXTRA_PROCESS_LABEL, processLabel);
                                b.putStringArrayList(EXTRA_LOG_LINES, lines);
                                b.putStringArrayList(EXTRA_LOGCAT_LINES, readFilteredLogcat(300));
                                try {
                                    receiver.send(0, b);
                                } catch (Throwable ignored) {
                                }
                            }, "mf2c-logcat-collect").start();
                        } else {
                            Bundle b = new Bundle();
                            b.putString(EXTRA_PROCESS_LABEL, processLabel);
                            b.putStringArrayList(EXTRA_LOG_LINES, lines);
                            receiver.send(0, b);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }, new IntentFilter(ACTION_COLLECT_LOGS), null, handler, Context.RECEIVER_EXPORTED);
        } catch (Throwable ignored) {
            REGISTERED_PROCESSES.remove(processLabel);
        }
    }

    static ArrayList<String> readFilteredLogcat(int maxLines) {
        ArrayList<String> out = new ArrayList<>();
        String clearMarker = LOGCAT_CLEAR_MARKER.get();
        java.lang.Process proc = null;
        try {
            ArrayList<String> cmd = new ArrayList<>();
            cmd.add("logcat");
            cmd.add("-d");
            cmd.add("-v");
            cmd.add("time");
            cmd.add("-t");
            cmd.add(String.valueOf(Math.max(50, maxLines)));
            for (String spec : LOGCAT_TAGS) cmd.add(spec);
            final java.lang.Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            proc = p;
            Thread readerThread = new Thread(() -> {
                boolean lastKept = (clearMarker == null);
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("--------- beginning of")) continue;
                        if (clearMarker != null) {
                            boolean hasTimestamp = line.length() >= 18
                                    && line.charAt(2) == '-' && line.charAt(5) == ' '
                                    && line.charAt(8) == ':' && line.charAt(11) == ':' && line.charAt(14) == '.';
                            if (hasTimestamp) {
                                lastKept = isLogcatLineAtOrAfter(line, clearMarker);
                            }
                            if (!lastKept) continue;
                        }
                        synchronized (out) {
                            out.add(line);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }, "mf2c-logcat-read");
            readerThread.start();
            if (!p.waitFor(4, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
            readerThread.join(1000L);
        } catch (Throwable ignored) {
        } finally {
            if (proc != null) {
                try {
                    proc.destroyForcibly();
                } catch (Throwable ignored) {
                }
            }
        }
        synchronized (out) {
            return new ArrayList<>(out);
        }
    }
}
