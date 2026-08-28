package com.warden.controlledsandbox;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import android.os.Bundle;
import com.warden.controlledsandbox.runtime.diagnostics.RuntimeEventLog;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Request-scoped package mutation telemetry and deterministic stage deadlines. */
final class PackageMutationTrace {
    static final String COPY = "COPY";
    static final String HASH = "HASH";
    static final String PARSE = "PARSE";
    static final String NATIVE_EXTRACT = "NATIVE_EXTRACT";
    static final String PUBLISH = "PUBLISH";
    static final String CATALOG = "CATALOG";
    static final String ENSURE_INSTANCE = "ENSURE_INSTANCE";
    // PERF-T00 canonical import stages. Keep the legacy coarse names above as aliases used by
    // existing callers; these names are the stable contract consumed by the breakdown tooling.
    static final String IMPORT = "IMPORT";
    static final String SOURCE_DISCOVERY = "SOURCE_DISCOVERY";
    static final String CATALOG_LOAD = "CATALOG_LOAD";
    static final String MANIFEST_PARSE = "MANIFEST_PARSE";
    static final String PACKAGE_INFO = "PACKAGE_INFO";
    static final String NATIVE_DETECT = "NATIVE_DETECT";
    static final String EXISTING_REVISION_VERIFY = "EXISTING_REVISION_VERIFY";
    static final String STAGED_REVISION_VERIFY = "STAGED_REVISION_VERIFY";
    static final String PUBLISHED_REVISION_VERIFY = "PUBLISHED_REVISION_VERIFY";
    static final String PUBLISH_BEGIN = "PUBLISH";
    static final String CATALOG_WRITE = "CATALOG_WRITE";
    static final String CATALOG_SWEEP = "CATALOG_SWEEP";

    static final String APK_BYTES_READ = "apkBytesRead";
    static final String APK_BYTES_WRITTEN = "apkBytesWritten";
    static final String SHA_BYTES_READ = "shaBytesRead";
    static final String ZIP_ENTRY_COUNT = "zipEntryCount";
    static final String ZIP_STREAM_OPEN_COUNT = "zipStreamOpenCount";
    static final String BINDER_CALL_COUNT = "binderCallCount";
    static final String BINDER_RETRY_COUNT = "binderRetryCount";
    static final String FSYNC_COUNT = "fsyncCount";
    static final String SPLIT_COUNT = "splitCount";
    static final String NATIVE_LIB_COUNT = "nativeLibCount";
    static final String NATIVE_BYTES_EXTRACTED = "nativeBytesExtracted";
    static final String CATALOG_PACKAGE_COUNT = "catalogPackageCount";
    static final String PACKAGE_UNIVERSE_COUNT = "packageUniverseCount";
    static final String IMPORT_BEGIN = IMPORT + "_BEGIN";
    static final String IMPORT_END = IMPORT + "_END";
    static final String SOURCE_DISCOVERY_BEGIN = SOURCE_DISCOVERY + "_BEGIN";
    static final String SOURCE_DISCOVERY_END = SOURCE_DISCOVERY + "_END";
    static final String CATALOG_LOAD_BEGIN = CATALOG_LOAD + "_BEGIN";
    static final String CATALOG_LOAD_END = CATALOG_LOAD + "_END";
    static final String NATIVE_DETECT_BEGIN = NATIVE_DETECT + "_BEGIN";
    static final String NATIVE_DETECT_END = NATIVE_DETECT + "_END";
    static final String CATALOG_WRITE_BEGIN = CATALOG_WRITE + "_BEGIN";
    static final String CATALOG_WRITE_END = CATALOG_WRITE + "_END";

    private static final ThreadLocal<PackageMutationTrace> CURRENT = new ThreadLocal<>();
    private static final Map<String, Long> DEADLINES_MS = Map.of(
            COPY, 120_000L,
            HASH, 30_000L,
            PARSE, 60_000L,
            NATIVE_EXTRACT, 180_000L,
            PUBLISH, 30_000L,
            CATALOG, 30_000L,
            ENSURE_INSTANCE, 10_000L);
    private static final String[] COUNTER_NAMES = {
            APK_BYTES_READ, APK_BYTES_WRITTEN, SHA_BYTES_READ, ZIP_ENTRY_COUNT,
            ZIP_STREAM_OPEN_COUNT, BINDER_CALL_COUNT, BINDER_RETRY_COUNT, FSYNC_COUNT,
            SPLIT_COUNT, NATIVE_LIB_COUNT, NATIVE_BYTES_EXTRACTED, CATALOG_PACKAGE_COUNT,
            PACKAGE_UNIVERSE_COUNT};

    private final String requestId;
    private final String operationId = UUID.randomUUID().toString();
    private final String operation;
    private final String packageName;
    private final int virtualUserId;
    private final long startedAtMs = System.currentTimeMillis();
    private final long startedAtNanos = System.nanoTime();
    private final LinkedHashMap<String, Long> stageNanos = new LinkedHashMap<>();
    private final LinkedHashMap<String, Long> counters = new LinkedHashMap<>();
    private final JSONArray stageEvents = new JSONArray();
    private final JSONArray anomalies = new JSONArray();
    private String stage = "ACCEPTED";
    private String status = "IN_PROGRESS";
    private String errorCode = "";
    private boolean retryable;
    private int attempt = 1;
    private int retryBudget;
    private long activeStageStartedNanos;

    PackageMutationTrace(String requestId, String operation, String packageName,
            int virtualUserId) {
        this.requestId = required(requestId, "requestId");
        this.operation = required(operation, "operation");
        this.packageName = required(packageName, "packageName");
        this.virtualUserId = virtualUserId;
    }

    static PackageMutationTrace current() { return CURRENT.get(); }

    Scope attach() {
        PackageMutationTrace previous = CURRENT.get();
        CURRENT.set(this);
        return () -> {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        };
    }

    synchronized StageScope stage(String value) {
        String normalized = required(value, "stage");
        String previous = stage;
        long previousStarted = activeStageStartedNanos;
        stage = normalized;
        activeStageStartedNanos = System.nanoTime();
        return new StageScope(normalized, previous, previousStarted, activeStageStartedNanos);
    }

    synchronized void checkpoint() {
        if (activeStageStartedNanos == 0L) return;
        requireWithinDeadline(stage, System.nanoTime() - activeStageStartedNanos);
    }

    synchronized void addMeasuredNanos(String value, long nanos) {
        if (nanos <= 0L) return;
        stageNanos.merge(required(value, "stage"), nanos, Long::sum);
        requireWithinDeadline(value, stageNanos.get(value));
    }

    synchronized void addCounter(String name, long delta) {
        if (delta == 0L) return;
        String key = required(name, "counter");
        counters.put(key, Math.max(0L, counters.getOrDefault(key, 0L) + delta));
    }

    synchronized long counter(String name) { return counters.getOrDefault(name, 0L); }

    synchronized void anomaly(String code, String detail) {
        try {
            JSONObject value = new JSONObject();
            value.put("code", required(code, "code"));
            value.put("detail", detail == null ? "" : detail);
            anomalies.put(value);
        } catch (JSONException error) {
            throw new IllegalStateException("PACKAGE_OPERATION_TRACE_ENCODING_FAILED", error);
        }
    }

    synchronized void success() {
        stage = "DONE";
        status = "SUCCEEDED";
        errorCode = "";
        retryable = false;
    }

    synchronized void failure(String code, boolean canRetry) {
        stage = "FAILED";
        status = "FAILED";
        errorCode = required(code, "errorCode");
        retryable = canRetry;
    }

    String operationId() { return operationId; }
    String requestId() { return requestId; }
    String packageName() { return packageName; }
    int virtualUserId() { return virtualUserId; }

    synchronized String toJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("requestId", requestId);
            root.put("operationId", operationId);
            root.put("operation", operation);
            root.put("packageName", packageName);
            root.put("virtualUserId", virtualUserId);
            root.put("stage", stage);
            root.put("status", status);
            root.put("errorCode", errorCode);
            root.put("retryable", retryable);
            root.put("attempt", attempt);
            root.put("retryBudget", retryBudget);
            root.put("startedAtMs", startedAtMs);
            root.put("elapsedMs", nanosToMillis(System.nanoTime() - startedAtNanos));
            JSONObject timings = new JSONObject();
            for (Map.Entry<String, Long> item : stageNanos.entrySet()) {
                timings.put(item.getKey(), nanosToMillis(item.getValue()));
            }
            root.put("stageTimingsMs", timings);
            JSONObject counterValues = new JSONObject();
            for (String counter : COUNTER_NAMES) {
                long value = counters.getOrDefault(counter, 0L);
                counterValues.put(counter, value);
                root.put(counter, value);
            }
            for (Map.Entry<String, Long> item : counters.entrySet()) {
                if (java.util.Arrays.asList(COUNTER_NAMES).contains(item.getKey())) continue;
                counterValues.put(item.getKey(), item.getValue());
                root.put(item.getKey(), item.getValue());
            }
            root.put("counters", counterValues);
            // Preserve a bounded event list so a single pathological request cannot grow the
            // diagnostics record without bound. Stage timings remain authoritative.
            root.put("stageEvents", stageEvents);
            JSONObject deadlines = new JSONObject();
            for (Map.Entry<String, Long> item : DEADLINES_MS.entrySet()) {
                deadlines.put(item.getKey(), item.getValue());
            }
            root.put("stageDeadlinesMs", deadlines);
            root.put("anomalies", anomalies);
            return root.toString();
        } catch (JSONException error) {
            throw new IllegalStateException("PACKAGE_OPERATION_TRACE_ENCODING_FAILED", error);
        }
    }

    private static void requireWithinDeadline(String value, long elapsedNanos) {
        Long deadlineMs = DEADLINES_MS.get(value);
        if (deadlineMs != null && nanosToMillis(elapsedNanos) > deadlineMs) {
            throw new PackageOperationStageTimeoutException(value, deadlineMs);
        }
    }

    private static long nanosToMillis(long nanos) {
        return Math.max(0L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(nanos));
    }

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    interface Scope extends AutoCloseable { @Override void close(); }

    final class StageScope implements AutoCloseable {
        private final String measuredStage;
        private final String previousStage;
        private final long previousStarted;
        private final long started;
        private boolean closed;

        StageScope(String measuredStage, String previousStage, long previousStarted, long started) {
            this.measuredStage = measuredStage;
            this.previousStage = previousStage;
            this.previousStarted = previousStarted;
            this.started = started;
            emitStageEvent("BEGIN", measuredStage, 0L);
        }

        @Override public void close() {
            synchronized (PackageMutationTrace.this) {
                if (closed) return;
                closed = true;
                long elapsed = System.nanoTime() - started;
                stageNanos.merge(measuredStage, elapsed, Long::sum);
                emitStageEvent("END", measuredStage, nanosToMillis(elapsed));
                stage = previousStage;
                activeStageStartedNanos = previousStarted;
                requireWithinDeadline(measuredStage, stageNanos.get(measuredStage));
            }
        }
    }

    private void emitStageEvent(String phase, String measuredStage, long durationMs) {
        try {
            if (stageEvents.length() >= 256) return;
            JSONObject event = new JSONObject();
            event.put("phase", phase);
            event.put("stage", measuredStage);
            event.put("elapsedMs", nanosToMillis(System.nanoTime() - startedAtNanos));
            if ("END".equals(phase)) event.put("durationMs", durationMs);
            stageEvents.put(event);
            Bundle details = new Bundle();
            details.putString(RuntimeKeys.REQUEST_ID, requestId);
            details.putString(RuntimeKeys.OPERATION_ID, operationId);
            details.putString(RuntimeKeys.PACKAGE_NAME, packageName);
            details.putString("traceDomain", "FRAMEWORK");
            details.putString("perfStage", measuredStage);
            details.putString("perfPhase", phase);
            details.putLong("perfElapsedMs", nanosToMillis(System.nanoTime() - startedAtNanos));
            if ("END".equals(phase)) details.putLong("perfDurationMs", durationMs);
            RuntimeEventLog.event("PACKAGE_PERF_STAGE", details);
        } catch (Throwable ignored) {
            // Telemetry is strictly best-effort and must never change package semantics.
        }
    }
}

final class PackageOperationStageTimeoutException extends IllegalStateException {
    final String stage;
    PackageOperationStageTimeoutException(String stage, long deadlineMs) {
        super("PACKAGE_OPERATION_STAGE_TIMEOUT:" + stage + ":" + deadlineMs);
        this.stage = stage;
    }
}
