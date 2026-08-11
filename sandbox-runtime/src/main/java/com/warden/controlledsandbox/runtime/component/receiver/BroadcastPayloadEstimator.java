package com.warden.controlledsandbox.runtime.component.receiver;

import android.os.Bundle;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
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

    /**
     * Estimates only the public Intent payload carried by a Broker request. Control-plane
     * Parcelable values such as the prepared package snapshot must never be treated as broadcast
     * application data.
     */
    public static int requireIntentWithinLimit(Bundle request) {
        Bundle payload = new Bundle();
        copyString(request, payload, ComponentOperations.ACTION);
        copyString(request, payload, RuntimeKeys.TARGET_PACKAGE_NAME);
        copyString(request, payload, RuntimeKeys.URI);
        copyString(request, payload, RuntimeKeys.BROADCAST_SCHEME);
        copyString(request, payload, RuntimeKeys.BROADCAST_HOST);
        copyString(request, payload, RuntimeKeys.BROADCAST_PATH);
        copyString(request, payload, RuntimeKeys.BROADCAST_MIME_TYPE);
        if (request != null) {
            ArrayList<String> categories = request.getStringArrayList(RuntimeKeys.BROADCAST_CATEGORIES);
            if (categories != null) payload.putStringArrayList(RuntimeKeys.BROADCAST_CATEGORIES,
                    new ArrayList<>(categories));
            Bundle extras = request.getBundle(RuntimeKeys.INTENT_EXTRAS);
            if (extras != null) payload.putBundle(RuntimeKeys.INTENT_EXTRAS, new Bundle(extras));
            copyString(request, payload, RuntimeKeys.BROADCAST_RESULT_DATA);
            Bundle resultExtras = request.getBundle(RuntimeKeys.BROADCAST_RESULT_EXTRAS);
            if (resultExtras != null) payload.putBundle(RuntimeKeys.BROADCAST_RESULT_EXTRAS,
                    new Bundle(resultExtras));
        }
        return requireWithinLimit(payload);
    }

    private static void copyString(Bundle source, Bundle target, String key) {
        if (source == null || !source.containsKey(key)) return;
        target.putString(key, source.getString(key, ""));
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
