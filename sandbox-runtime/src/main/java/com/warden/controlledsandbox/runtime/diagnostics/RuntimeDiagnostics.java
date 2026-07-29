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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Process-local crash, ANR, liveness and structured runtime evidence recorder. */
public final class RuntimeDiagnostics {
    private static final String TAG = "CS_DIAGNOSTICS";
    private static final long MAX_FILE_BYTES = 2L * 1024L * 1024L;
    private static final long HEARTBEAT_INTERVAL_MS = 2_000L;
    private static final long ANR_THRESHOLD_MS = 10_000L;
    private static final long ANR_TRACE_REFRESH_MS = 30_000L;
    private static final int MAX_THREADS = 64;
    private static final int MAX_FRAMES_PER_THREAD = 64;
    private static final Object LOCK = new Object();
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicLong MAIN_ACK = new AtomicLong();
    private static final AnrEpisodeTracker ANR_TRACKER = new AnrEpisodeTracker(ANR_THRESHOLD_MS);
    private static volatile File eventFile;
    private static volatile File nativeCrashFile;
    private static volatile File javaCrashFile;
    private static volatile File anrTraceFile;
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
            String prefix = role + "-" + Process.myPid();
            eventFile = new File(directory, prefix + ".jsonl");
            nativeCrashFile = new File(directory, prefix + "-native.jsonl");
            javaCrashFile = new File(directory, prefix + "-java-crash.txt");
            anrTraceFile = new File(directory, prefix + "-anr-traces.txt");
        }
        if (!INSTALLED.compareAndSet(false, true)) return;
        installCrashHandler();
        installMainThreadWatchdog();
        record("DIAGNOSTICS_INSTALLED", null, "role=" + role + " schema=2");
    }

    public static File eventFile() { return eventFile; }
    public static File nativeCrashFile() { return nativeCrashFile; }
    static File javaCrashFile() { return javaCrashFile; }
    static File anrTraceFile() { return anrTraceFile; }

    public static Bundle snapshot() {
        Bundle out = new Bundle();
        File events = eventFile;
        File nativeEvents = nativeCrashFile;
        File javaCrashes = javaCrashFile;
        File anrTraces = anrTraceFile;
        out.putString(RuntimeKeys.STATUS, events == null ? "DIAGNOSTICS_NOT_INSTALLED" : "DIAGNOSTICS_READY");
        out.putString("diagnosticsRole", role);
        putFile(out, "diagnosticsEvent", events);
        putFile(out, "diagnosticsNative", nativeEvents);
        putFile(out, "diagnosticsJavaCrash", javaCrashes);
        putFile(out, "diagnosticsAnrTrace", anrTraces);
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
            copyWithPrevious(eventFile, "runtime-events", ".jsonl", destinationDirectory, exported);
            copyWithPrevious(nativeCrashFile, "native-crash", ".jsonl", destinationDirectory, exported);
            copyWithPrevious(javaCrashFile, "java-crash", ".txt", destinationDirectory, exported);
            copyWithPrevious(anrTraceFile, "anr-traces", ".txt", destinationDirectory, exported);
            File manifest = new File(destinationDirectory, "diagnostics-manifest.txt");
            StringBuilder body = new StringBuilder();
            body.append("schemaVersion=2\n");
            body.append("exportedAtMs=").append(System.currentTimeMillis()).append('\n');
            body.append("role=").append(role).append('\n');
            body.append("pid=").append(Process.myPid()).append('\n');
            body.append("fileCount=").append(exported.size()).append('\n');
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
        appendEvidence(target, line);
    }

    private static void installCrashHandler() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                writeJavaCrashEvidence(thread, error);
                record("UNCAUGHT_EXCEPTION", null,
                        "thread=" + thread.getName() + " type=" + error.getClass().getName()
                                + " message=" + String.valueOf(error.getMessage()));
            } catch (Throwable ignored) { }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    private static void installMainThreadWatchdog() {
        Handler main = new Handler(Looper.getMainLooper());
        MAIN_ACK.set(SystemClock.elapsedRealtime());
        Thread watchdog = new Thread(() -> {
            long lastTraceAt = 0;
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
                AnrEpisodeTracker.Sample sample = ANR_TRACKER.observe(now, Math.max(0, delay));
                if (sample.transition == AnrEpisodeTracker.Transition.STARTED) {
                    lastTraceAt = now;
                    writeAnrEvidence(sample, "STARTED");
                    record("ANR_SUSPECTED", null, anrDetail(sample));
                } else if (sample.transition == AnrEpisodeTracker.Transition.CONTINUING
                        && now - lastTraceAt >= ANR_TRACE_REFRESH_MS) {
                    lastTraceAt = now;
                    writeAnrEvidence(sample, "CONTINUING");
                    record("ANR_STILL_BLOCKED", null, anrDetail(sample));
                } else if (sample.transition == AnrEpisodeTracker.Transition.RECOVERED) {
                    record("ANR_RECOVERED", null, anrDetail(sample));
                }
            }
        }, "controlled-sandbox-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static String anrDetail(AnrEpisodeTracker.Sample sample) {
        return "episode=" + sample.episodeId + " delayMs=" + sample.delayMs
                + " maxDelayMs=" + sample.maxDelayMs + " samples=" + sample.sampleCount
                + " durationMs=" + sample.durationMs;
    }

    private static void writeJavaCrashEvidence(Thread thread, Throwable error) {
        File target = javaCrashFile;
        if (target == null) return;
        StringBuilder out = new StringBuilder(16 * 1024);
        out.append("event=JAVA_UNCAUGHT_EXCEPTION\n")
                .append("timestampMs=").append(System.currentTimeMillis()).append('\n')
                .append("elapsedMs=").append(SystemClock.elapsedRealtime()).append('\n')
                .append("pid=").append(Process.myPid()).append('\n')
                .append("role=").append(role).append('\n')
                .append("thread=").append(thread == null ? "unknown" : thread.getName()).append('\n')
                .append("type=").append(error == null ? "unknown" : error.getClass().getName()).append('\n')
                .append("message=").append(error == null ? "" : String.valueOf(error.getMessage())).append('\n')
                .append(stack(error)).append("\n---\n");
        appendEvidence(target, out.toString());
    }

    private static void writeAnrEvidence(AnrEpisodeTracker.Sample sample, String state) {
        File target = anrTraceFile;
        if (target == null) return;
        StringBuilder out = new StringBuilder(128 * 1024);
        out.append("event=ANR_TRACE\n")
                .append("state=").append(state).append('\n')
                .append("timestampMs=").append(System.currentTimeMillis()).append('\n')
                .append("elapsedMs=").append(SystemClock.elapsedRealtime()).append('\n')
                .append("pid=").append(Process.myPid()).append('\n')
                .append("role=").append(role).append('\n')
                .append(anrDetail(sample)).append('\n');
        List<Map.Entry<Thread, StackTraceElement[]>> entries = new ArrayList<>(Thread.getAllStackTraces().entrySet());
        entries.sort(Comparator.comparing(item -> item.getKey().getName()));
        int threadCount = 0;
        for (Map.Entry<Thread, StackTraceElement[]> entry : entries) {
            if (threadCount++ >= MAX_THREADS) break;
            Thread thread = entry.getKey();
            out.append("\nthread=").append(thread.getName())
                    .append(" id=").append(thread.getId())
                    .append(" state=").append(thread.getState()).append('\n');
            StackTraceElement[] trace = entry.getValue();
            for (int i = 0; i < Math.min(trace.length, MAX_FRAMES_PER_THREAD); i++) {
                out.append("  at ").append(trace[i]).append('\n');
            }
        }
        out.append("\n---\n");
        appendEvidence(target, out.toString());
    }

    private static void appendEvidence(File target, String body) {
        synchronized (LOCK) {
            try {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                rotateIfNeeded(target, bytes.length);
                try (FileOutputStream output = new FileOutputStream(target, true)) {
                    output.write(bytes);
                    output.getFD().sync();
                }
            } catch (Throwable error) {
                Log.e(TAG, "Diagnostics write failed: " + error);
            }
        }
    }

    private static void putFile(Bundle out, String prefix, File file) {
        out.putString(prefix + "File", file == null ? "" : file.getAbsolutePath());
        out.putLong(prefix + "Bytes", file != null && file.isFile() ? file.length() : 0L);
        // Preserve legacy keys used by existing debug tooling.
        if ("diagnosticsEvent".equals(prefix)) {
            out.putString("diagnosticsEventFile", file == null ? "" : file.getAbsolutePath());
            out.putLong("diagnosticsEventBytes", file != null && file.isFile() ? file.length() : 0L);
        } else if ("diagnosticsNative".equals(prefix)) {
            out.putString("diagnosticsNativeFile", file == null ? "" : file.getAbsolutePath());
            out.putLong("diagnosticsNativeBytes", file != null && file.isFile() ? file.length() : 0L);
        }
    }

    private static void copyWithPrevious(File source, String baseName, String suffix,
                                         File destinationDirectory, ArrayList<File> exported) {
        copyIfPresent(source, new File(destinationDirectory, baseName + suffix), exported);
        copyIfPresent(source == null ? null : new File(source.getParentFile(), source.getName() + ".1"),
                new File(destinationDirectory, baseName + ".previous" + suffix), exported);
    }

    private static void copyIfPresent(File source, File destination, ArrayList<File> exported) {
        if (source == null || !source.isFile()) return;
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            long total = 0;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_FILE_BYTES) throw new IllegalStateException("Diagnostics export exceeds limit");
                output.write(buffer, 0, count);
            }
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
        if (incomingBytes > MAX_FILE_BYTES) throw new IllegalStateException("Diagnostics record exceeds limit");
        if (!target.isFile() || target.length() + incomingBytes <= MAX_FILE_BYTES) return;
        File previous = new File(target.getParentFile(), target.getName() + ".1");
        if (previous.exists() && !previous.delete()) throw new IllegalStateException("Cannot replace diagnostics rotation");
        if (!target.renameTo(previous)) throw new IllegalStateException("Cannot rotate diagnostics file");
    }

    private static String stack(Throwable error) {
        if (error == null) return "";
        StringBuilder out = new StringBuilder();
        Throwable current = error;
        int causeDepth = 0;
        while (current != null && causeDepth++ < 8) {
            out.append(current).append('\n');
            StackTraceElement[] trace = current.getStackTrace();
            for (int i = 0; i < Math.min(trace.length, 64); i++) out.append("at ").append(trace[i]).append('\n');
            current = current.getCause();
            if (current != null) out.append("Caused by: ");
        }
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
