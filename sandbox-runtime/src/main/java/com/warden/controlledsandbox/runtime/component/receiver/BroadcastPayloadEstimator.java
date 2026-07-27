package com.warden.controlledsandbox.runtime.component.receiver;

import android.os.Bundle;
import java.util.ArrayList;

/** Conservative, deterministic Bundle size estimator for broadcast admission control. */
public final class BroadcastPayloadEstimator {
    public static final int MAX_BROADCAST_BYTES = 512 * 1024;
    private static final int MAX_DEPTH = 4;

    private BroadcastPayloadEstimator() { }

    public static int requireWithinLimit(Bundle bundle) {
        int bytes = estimateBundle(bundle, 0);
        if (bytes > MAX_BROADCAST_BYTES) throw new IllegalArgumentException("BROADCAST_PAYLOAD_TOO_LARGE");
        return bytes;
    }

    private static int estimateBundle(Bundle bundle, int depth) {
        if (bundle == null) return 0;
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("BROADCAST_PAYLOAD_TOO_DEEP");
        long total = 32;
        for (String key : bundle.keySet()) {
            total += bytes(key) + estimate(bundle.get(key), depth + 1) + 16;
            if (total > Integer.MAX_VALUE) throw new IllegalArgumentException("BROADCAST_PAYLOAD_TOO_LARGE");
        }
        return (int) total;
    }

    private static int estimate(Object value, int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("BROADCAST_PAYLOAD_TOO_DEEP");
        if (value == null) return 4;
        if (value instanceof String) return bytes((String) value);
        if (value instanceof Integer || value instanceof Float || value instanceof Boolean) return 8;
        if (value instanceof Long || value instanceof Double) return 12;
        if (value instanceof byte[]) return ((byte[]) value).length + 16;
        if (value instanceof Bundle) return estimateBundle((Bundle) value, depth);
        if (value instanceof ArrayList<?>) {
            long total = 16;
            for (Object item : (ArrayList<?>) value) total += estimate(item, depth + 1) + 8;
            if (total > Integer.MAX_VALUE) throw new IllegalArgumentException("BROADCAST_PAYLOAD_TOO_LARGE");
            return (int) total;
        }
        // Binder/Parcelable payloads are rejected from this source-model broadcast path.
        throw new IllegalArgumentException("BROADCAST_PAYLOAD_TYPE_UNSUPPORTED:" + value.getClass().getName());
    }

    private static int bytes(String value) { return value == null ? 4 : value.length() * 2 + 8; }
}
