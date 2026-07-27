package com.warden.controlledsandbox.runtime.diagnostics;

import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Process-local crash, liveness and structured runtime evidence recorder. */
public final class RuntimeDiagnostics {
    private static final String TAG = "CS_DIAGNOSTICS";
    private static final long MAX_FILE_BYTES = 2L * 1024L * 1024L;
    private static final long HEARTBEAT_INTERVAL_MS = 2_000L;
    private static final long ANR_THRESHOLD_MS = 10_000L;
    private static final Object LOCK = new Object();
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicLong MAIN_ACK = new AtomicLong();
    private static volatile File eventFile;
    private static volatile File nativeCrashFile;
    private static volatile String role = "uninitialized";
    private static final Map<String, AtomicLong> EVENT_COUNTS = new ConcurrentHashMap<>();

    private RuntimeDiagnostics() { }

    public static void install(Context context, String processRole) {
        if (context == null) return;
        synchronized (LOCK) {
            role = safe(processRole == null ? "unknown" : processRole);
            File directory = new File(context.getFilesDir(), "runtime-diagnostics");
            if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
                Log.e(TAG, "Cannot create diagnostics directory " + directory);
                return;
            }
            eventFile = new File(directory, role + "-" + Process.myPid() + ".jsonl");
            nativeCrashFile = new File(directory, role + "-" + Process.myPid() + "-native.jsonl");
        }
        if (!INSTALLED.compareAndSet(false, true)) return;
        installCrashHandler();
        installMainThreadWatchdog();
        record("DIAGNOSTICS_INSTALLED", null, "role=" + role);
    }

    public static File eventFile() { return eventFile; }
    public static File nativeCrashFile() { return nativeCrashFile; }

    public static Bundle snapshot() {
        Bundle out = new Bundle();
        File events = eventFile;
        File nativeEvents = nativeCrashFile;
        out.putString(RuntimeKeys.STATUS, events == null ? "DIAGNOSTICS_NOT_INSTALLED" : "DIAGNOSTICS_READY");
        out.putString("diagnosticsRole", role);
        out.putString("diagnosticsEventFile", events == null ? "" : events.getAbsolutePath());
        out.putLong("diagnosticsEventBytes", events != null && events.isFile() ? events.length() : 0L);
        out.putString("diagnosticsNativeFile", nativeEvents == null ? "" : nativeEvents.getAbsolutePath());
        out.putLong("diagnosticsNativeBytes", nativeEvents != null && nativeEvents.isFile() ? nativeEvents.length() : 0L);
        ArrayList<String> counts = new ArrayList<>();
        EVENT_COUNTS.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(item -> counts.add(item.getKey() + "=" + item.getValue().get()));
        out.putStringArrayList("diagnosticsEventCounts", counts);
        return out;
    }

    public static File exportEvidence(File destinationDirectory) {
        if (destinationDirectory == null) throw new IllegalArgumentException("destinationDirectory is required");
        synchronized (LOCK) {
            if (!destinationDirectory.isDirectory() && !destinationDirectory.mkdirs()
                    && !destinationDirectory.isDirectory()) {
                throw new IllegalStateException("Cannot create diagnostics export directory");
            }
            ArrayList<File> exported = new ArrayList<>();
            copyIfPresent(eventFile, new File(destinationDirectory, "runtime-events.jsonl"), exported);
            copyIfPresent(eventFile == null ? null : new File(eventFile.getParentFile(), eventFile.getName() + ".1"),
                    new File(destinationDirectory, "runtime-events.previous.jsonl"), exported);
            copyIfPresent(nativeCrashFile, new File(destinationDirectory, "native-crash.jsonl"), exported);
            File manifest = new File(destinationDirectory, "diagnostics-manifest.txt");
            StringBuilder body = new StringBuilder();
            body.append("role=").append(role).append('\n');
            body.append("pid=").append(Process.myPid()).append('\n');
            for (File file : exported) {
                body.append("file=").append(file.getName())
                        .append(" bytes=").append(file.length())
                        .append(" sha256=").append(sha256(file)).append('\n');
            }
            writeBytes(manifest, body.toString().getBytes(StandardCharsets.UTF_8));
            return manifest;
        }
    }

    static void record(String event, Bundle data, String detail) {
        EVENT_COUNTS.computeIfAbsent(event == null ? "UNKNOWN" : event, ignored -> new AtomicLong()).incrementAndGet();
        File target = eventFile;
        if (target == null) return;
        String line = jsonLine(event, data, detail);
        synchronized (LOCK) {
            try {
                rotateIfNeeded(target, line.length());
                try (FileOutputStream output = new FileOutputStream(target, true)) {
                    output.write(line.getBytes(StandardCharsets.UTF_8));
                    output.getFD().sync();
                }
            } catch (Throwable error) {
                Log.e(TAG, "Diagnostics write failed: " + error);
            }
        }
    }

    private static void installCrashHandler() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                record("UNCAUGHT_EXCEPTION", null,
                        "thread=" + thread.getName() + " type=" + error.getClass().getName()
                                + " message=" + String.valueOf(error.getMessage()) + " stack=" + stack(error));
            } catch (Throwable ignored) { }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    private static void installMainThreadWatchdog() {
        Handler main = new Handler(Looper.getMainLooper());
        MAIN_ACK.set(SystemClock.elapsedRealtime());
        Thread watchdog = new Thread(() -> {
            long lastReport = 0;
            while (!Thread.currentThread().isInterrupted()) {
                long postedAt = SystemClock.elapsedRealtime();
                try {
                    main.post(() -> MAIN_ACK.set(SystemClock.elapsedRealtime()));
                    Thread.sleep(HEARTBEAT_INTERVAL_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable error) {
                    record("WATCHDOG_FAILURE", null, error.getClass().getName() + ":" + error.getMessage());
                    return;
                }
                long now = SystemClock.elapsedRealtime();
                long delay = now - Math.max(MAIN_ACK.get(), postedAt);
                if (delay >= ANR_THRESHOLD_MS && now - lastReport >= ANR_THRESHOLD_MS * 3) {
                    lastReport = now;
                    record("ANR_SUSPECTED", null, "mainThreadDelayMs=" + delay);
                }
            }
        }, "controlled-sandbox-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static void copyIfPresent(File source, File destination, ArrayList<File> exported) {
        if (source == null || !source.isFile()) return;
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
            exported.add(destination);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Cannot export diagnostics " + source, error);
        }
    }

    private static void writeBytes(File target, byte[] value) {
        try (FileOutputStream output = new FileOutputStream(target)) {
            output.write(value);
            output.getFD().sync();
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Cannot write diagnostics manifest", error);
        }
    }

    private static String sha256(File source) {
        try (FileInputStream input = new FileInputStream(source)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            StringBuilder out = new StringBuilder(64);
            for (byte item : digest.digest()) out.append(String.format("%02x", item & 0xff));
            return out.toString();
        } catch (Exception error) {
            throw new IllegalStateException("Cannot hash diagnostics file", error);
        }
    }

    private static String jsonLine(String event, Bundle data, String detail) {
        StringBuilder out = new StringBuilder(512);
        out.append('{');
        field(out, "timestampMs", String.valueOf(System.currentTimeMillis()), false);
        field(out, "elapsedMs", String.valueOf(SystemClock.elapsedRealtime()), false);
        field(out, "pid", String.valueOf(Process.myPid()), false);
        field(out, "role", role, true);
        field(out, "event", event, true);
        if (data != null) {
            field(out, "status", data.getString(RuntimeKeys.STATUS, ""), true);
            field(out, "package", data.getString(RuntimeKeys.PACKAGE_NAME, ""), true);
            field(out, "session", data.getString(RuntimeKeys.SESSION_ID, ""), true);
            field(out, "generation", String.valueOf(data.getLong(RuntimeKeys.GENERATION, 0)), false);
            field(out, "slot", String.valueOf(data.getInt(RuntimeKeys.PROCESS_SLOT, -1)), false);
            field(out, "component", data.getString(RuntimeKeys.COMPONENT_CLASS, ""), true);
        }
        field(out, "detail", detail == null ? "" : detail, true);
        if (out.charAt(out.length() - 1) == ',') out.setLength(out.length() - 1);
        return out.append("}\n").toString();
    }

    private static void field(StringBuilder out, String key, String value, boolean quote) {
        out.append('"').append(escape(key)).append("\":");
        if (quote) out.append('"').append(escape(value)).append('"');
        else out.append(value == null || value.isEmpty() ? "0" : value);
        out.append(',');
    }

    private static void rotateIfNeeded(File target, int incomingBytes) {
        if (!target.isFile() || target.length() + incomingBytes <= MAX_FILE_BYTES) return;
        File previous = new File(target.getParentFile(), target.getName() + ".1");
        if (previous.exists() && !previous.delete()) throw new IllegalStateException("Cannot replace diagnostics rotation");
        if (!target.renameTo(previous)) throw new IllegalStateException("Cannot rotate diagnostics file");
    }

    private static String stack(Throwable error) {
        StringBuilder out = new StringBuilder();
        out.append(error).append('\n');
        StackTraceElement[] trace = error.getStackTrace();
        for (int i = 0; i < Math.min(trace.length, 32); i++) out.append("at ").append(trace[i]).append('\n');
        return out.toString();
    }

    private static String escape(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '"': out.append("\\\""); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.toString();
    }

    private static String safe(String value) { return value.replaceAll("[^A-Za-z0-9._-]", "_"); }
}
