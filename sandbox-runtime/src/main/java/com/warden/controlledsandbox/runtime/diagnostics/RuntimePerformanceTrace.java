package com.warden.controlledsandbox.runtime.diagnostics;

import android.os.Bundle;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONObject;

/**
 * Lightweight request-scoped performance trace used by launch/import diagnostics.
 *
 * <p>Every stage is emitted as a bounded pair of events and the terminal event carries the
 * complete timing/counter breakdown.  The recorder is deliberately best effort: a diagnostics
 * failure can never change the runtime operation's functional result.</p>
 */
public final class RuntimePerformanceTrace implements AutoCloseable {
    private static final String[] COUNTER_NAMES = {
            "apkBytesRead", "apkBytesWritten", "shaBytesRead", "zipEntryCount",
            "zipStreamOpenCount", "binderCallCount", "binderRetryCount", "fsyncCount",
            "splitCount", "nativeLibCount", "nativeBytesExtracted", "catalogPackageCount",
            "packageUniverseCount"};
    public static final String CLIENT_LAUNCH_BEGIN = "CLIENT_LAUNCH_BEGIN";
    public static final String PACKAGE_LOAD = "PACKAGE_LOAD";
    public static final String PACKAGE_STATE = "PACKAGE_STATE";
    public static final String PACKAGE_UNIVERSE = "PACKAGE_UNIVERSE";
    public static final String BROKER_CONNECT = "BROKER_CONNECT";
    public static final String REVISION_VERIFY = "REVISION_VERIFY";
    public static final String GUEST_BIND = "GUEST_BIND";
    public static final String GUEST_PREPARE = "GUEST_PREPARE";
    public static final String NATIVE_BOOTSTRAP = "NATIVE_BOOTSTRAP";
    public static final String CLASSLOADER = "CLASSLOADER";
    public static final String RESOURCES = "RESOURCES";
    public static final String FRAMEWORK_HOOK = "FRAMEWORK_HOOK";
    public static final String SYSTEM_SERVICE = "SYSTEM_SERVICE";
    public static final String APPLICATION_ATTACH = "APPLICATION_ATTACH";
    public static final String PROVIDER_PREPARE = "PROVIDER_PREPARE";
    public static final String APPLICATION_ONCREATE = "APPLICATION_ONCREATE";
    public static final String HOST_START_ACTIVITY = "HOST_START_ACTIVITY";
    public static final String ACTIVITY_CREATED = "ACTIVITY_CREATED";
    public static final String ACTIVITY_RESUMED = "ACTIVITY_RESUMED";
    public static final String WINDOW_VISIBLE = "WINDOW_VISIBLE";
    public static final String FIRST_FRAME_DRAWN = "FIRST_FRAME_DRAWN";
    // Explicit event-name aliases mirror the PERF-T00 contract and are useful to static tooling.
    public static final String PACKAGE_LOAD_BEGIN = PACKAGE_LOAD + "_BEGIN";
    public static final String PACKAGE_LOAD_END = PACKAGE_LOAD + "_END";
    public static final String PACKAGE_STATE_BEGIN = PACKAGE_STATE + "_BEGIN";
    public static final String PACKAGE_STATE_END = PACKAGE_STATE + "_END";
    public static final String PACKAGE_UNIVERSE_BEGIN = PACKAGE_UNIVERSE + "_BEGIN";
    public static final String PACKAGE_UNIVERSE_END = PACKAGE_UNIVERSE + "_END";
    public static final String BROKER_CONNECT_BEGIN = BROKER_CONNECT + "_BEGIN";
    public static final String BROKER_CONNECT_END = BROKER_CONNECT + "_END";
    public static final String GUEST_BIND_BEGIN = GUEST_BIND + "_BEGIN";
    public static final String GUEST_BIND_END = GUEST_BIND + "_END";
    public static final String GUEST_PREPARE_BEGIN = GUEST_PREPARE + "_BEGIN";
    public static final String GUEST_PREPARE_END = GUEST_PREPARE + "_END";
    public static final String HOST_START_ACTIVITY_BEGIN = HOST_START_ACTIVITY + "_BEGIN";
    public static final String HOST_START_ACTIVITY_END = HOST_START_ACTIVITY + "_END";

    private final String requestId;
    private final String operationId;
    private final String packageName;
    private final long startedAt = android.os.SystemClock.elapsedRealtime();
    private final Map<String, Long> stageStarted = new LinkedHashMap<>();
    private final Map<String, Long> timings = new LinkedHashMap<>();
    private final Map<String, Long> counters = new LinkedHashMap<>();
    private boolean closed;

    public RuntimePerformanceTrace(String requestId, String operationId, String packageName) {
        this.requestId = requestId == null || requestId.trim().isEmpty()
                ? UUID.randomUUID().toString() : requestId.trim();
        this.operationId = operationId == null || operationId.trim().isEmpty()
                ? this.requestId + "-launch" : operationId.trim();
        this.packageName = packageName == null ? "" : packageName.trim();
    }

    public String requestId() { return requestId; }
    public String operationId() { return operationId; }

    public synchronized Stage stage(String name) {
        String normalized = require(name);
        long now = android.os.SystemClock.elapsedRealtime();
        stageStarted.put(normalized, now);
        emit(normalized, "BEGIN", 0L);
        return new Stage(normalized, now);
    }

    public synchronized void addCounter(String name, long delta) {
        if (name == null || name.trim().isEmpty() || delta == 0L) return;
        counters.merge(name.trim(), delta, Long::sum);
    }

    public synchronized void event(String name) { emit(require(name), "POINT", 0L); }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        Bundle fields = fields("COMPLETE", "");
        fields.putLong("perfTotalElapsedMs", Math.max(0L,
                android.os.SystemClock.elapsedRealtime() - startedAt));
        fields.putString("perfStageTimingsMs", json(timings));
        for (String counter : COUNTER_NAMES) counters.putIfAbsent(counter, 0L);
        fields.putString("perfCounters", json(counters));
        RuntimeEventLog.event("PERF_TRACE_COMPLETE", fields);
    }

    public final class Stage implements AutoCloseable {
        private final String name;
        private final long started;
        private boolean done;
        private Stage(String name, long started) { this.name = name; this.started = started; }
        @Override public void close() {
            synchronized (RuntimePerformanceTrace.this) {
                if (done) return;
                done = true;
                long duration = Math.max(0L,
                        android.os.SystemClock.elapsedRealtime() - started);
                timings.merge(name, duration, Long::sum);
                stageStarted.remove(name);
                emit(name, "END", duration);
            }
        }
    }

    private void emit(String stage, String phase, long duration) {
        try {
            Bundle fields = fields(phase, stage);
            fields.putLong("perfDurationMs", duration);
            RuntimeEventLog.event("PERF_TRACE_STAGE", fields);
        } catch (Throwable ignored) { }
    }

    private Bundle fields(String phase, String stage) {
        Bundle fields = new Bundle();
        fields.putString(RuntimeKeys.REQUEST_ID, requestId);
        fields.putString(RuntimeKeys.OPERATION_ID, operationId);
        fields.putString(RuntimeKeys.PACKAGE_NAME, packageName);
        fields.putString("perfPhase", phase);
        fields.putString("perfStage", stage);
        fields.putString("perfEvent", stage + ("POINT".equals(phase) ? "" : "_" + phase));
        fields.putLong("perfElapsedMs", Math.max(0L,
                android.os.SystemClock.elapsedRealtime() - startedAt));
        return fields;
    }

    private static String require(String value) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("stage is required");
        return value.trim();
    }

    private static String json(Map<String, Long> values) {
        try {
            JSONObject out = new JSONObject();
            for (Map.Entry<String, Long> item : values.entrySet()) out.put(item.getKey(), item.getValue());
            return out.toString();
        } catch (Exception ignored) {
            return "{}";
        }
    }
}
