package com.warden.controlledsandbox.domain.component.receiver;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Broker-owned ordered-broadcast result chain independent of Android Bundle. */
public final class OrderedBroadcastState {
    public static final int MAX_RESULT_DATA_CHARS = 4096;
    public static final int MAX_EXTRA_ENTRIES = 128;
    public static final int MAX_EXTRA_KEY_CHARS = 128;
    public static final int MAX_EXTRA_VALUE_CHARS = 4096;

    private final int resultCode;
    private final String resultData;
    private final Map<String, String> resultExtras;
    private final boolean aborted;

    public OrderedBroadcastState(int resultCode, String resultData,
                                 Map<String, String> resultExtras, boolean aborted) {
        this.resultCode = resultCode;
        this.resultData = normalizeLimited(resultData, MAX_RESULT_DATA_CHARS, "resultData");
        this.resultExtras = immutableExtras(resultExtras);
        this.aborted = aborted;
    }

    public static OrderedBroadcastState initial(int code, String data, Map<String, String> extras) {
        return new OrderedBroadcastState(code, data, extras, false);
    }

    public OrderedBroadcastState apply(ResultUpdate update) {
        if (update == null) return this;
        boolean nextAborted = update.clearAbort ? false : (aborted || update.abort);
        int nextCode = update.hasResultCode ? update.resultCode : resultCode;
        String nextData = update.hasResultData ? update.resultData : resultData;
        Map<String, String> nextExtras = update.hasResultExtras ? update.resultExtras : resultExtras;
        return new OrderedBroadcastState(nextCode, nextData, nextExtras, nextAborted);
    }

    public int resultCode() { return resultCode; }
    public String resultData() { return resultData; }
    public Map<String, String> resultExtras() { return resultExtras; }
    public boolean aborted() { return aborted; }

    public static final class ResultUpdate {
        private boolean hasResultCode;
        private int resultCode;
        private boolean hasResultData;
        private String resultData = "";
        private boolean hasResultExtras;
        private Map<String, String> resultExtras = Collections.emptyMap();
        private boolean abort;
        private boolean clearAbort;

        public ResultUpdate resultCode(int value) { hasResultCode = true; resultCode = value; return this; }
        public ResultUpdate resultData(String value) { hasResultData = true; resultData = value; return this; }
        public ResultUpdate resultExtras(Map<String, String> value) {
            hasResultExtras = true; resultExtras = immutableExtras(value); return this;
        }
        public ResultUpdate abort() { abort = true; return this; }
        public ResultUpdate clearAbort() { clearAbort = true; return this; }

        public boolean hasResultCode() { return hasResultCode; }
        public int resultCode() { return resultCode; }
        public boolean hasResultData() { return hasResultData; }
        public String resultData() { return resultData; }
        public boolean hasResultExtras() { return hasResultExtras; }
        public Map<String, String> resultExtras() { return resultExtras; }
        public boolean abortRequested() { return abort; }
        public boolean clearAbortRequested() { return clearAbort; }
    }

    private static Map<String, String> immutableExtras(Map<String, String> extras) {
        if (extras == null || extras.isEmpty()) return Collections.emptyMap();
        if (extras.size() > MAX_EXTRA_ENTRIES) throw new IllegalArgumentException("Too many ordered result extras");
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : extras.entrySet()) {
            String key = normalizeLimited(entry.getKey(), MAX_EXTRA_KEY_CHARS, "resultExtra key");
            if (key.isEmpty()) throw new IllegalArgumentException("Ordered result extra key is required");
            copy.put(key, normalizeLimited(entry.getValue(), MAX_EXTRA_VALUE_CHARS, "resultExtra value"));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static String normalizeLimited(String value, int limit, String label) {
        String normalized = value == null ? "" : value;
        if (normalized.length() > limit) throw new IllegalArgumentException(label + " is too long");
        return normalized;
    }
}
