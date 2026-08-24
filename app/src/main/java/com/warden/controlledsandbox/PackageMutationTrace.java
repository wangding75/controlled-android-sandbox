package com.warden.controlledsandbox;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
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

    private static final ThreadLocal<PackageMutationTrace> CURRENT = new ThreadLocal<>();
    private static final Map<String, Long> DEADLINES_MS = Map.of(
            COPY, 120_000L,
            HASH, 30_000L,
            PARSE, 60_000L,
            NATIVE_EXTRACT, 180_000L,
            PUBLISH, 30_000L,
            CATALOG, 30_000L,
            ENSURE_INSTANCE, 10_000L);

    private final String requestId;
    private final String operationId = UUID.randomUUID().toString();
    private final String operation;
    private final String packageName;
    private final int virtualUserId;
    private final long startedAtMs = System.currentTimeMillis();
    private final long startedAtNanos = System.nanoTime();
    private final LinkedHashMap<String, Long> stageNanos = new LinkedHashMap<>();
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
        }

        @Override public void close() {
            synchronized (PackageMutationTrace.this) {
                if (closed) return;
                closed = true;
                long elapsed = System.nanoTime() - started;
                stageNanos.merge(measuredStage, elapsed, Long::sum);
                stage = previousStage;
                activeStageStartedNanos = previousStarted;
                requireWithinDeadline(measuredStage, stageNanos.get(measuredStage));
            }
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
