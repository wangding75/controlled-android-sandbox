package com.warden.controlledsandbox.runtime.provider;

import android.os.Bundle;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.nio.charset.StandardCharsets;

/** Bounded wire contract for ContentProvider.bulkInsert(). */
public final class ProviderBulkInsertRuntime {
    public static final int MAX_VALUES = 128;
    public static final int MAX_BYTES = 512 * 1024;

    private ProviderBulkInsertRuntime() { }

    public static void validate(Bundle request) {
        if (request == null) throw new IllegalArgumentException("PROVIDER_BULK_REQUEST_REQUIRED");
        int count = request.getInt(RuntimeKeys.PROVIDER_BULK_VALUE_COUNT, -1);
        if (count < 0 || count > MAX_VALUES) {
            throw new IllegalArgumentException("PROVIDER_BULK_VALUE_COUNT_INVALID");
        }
        int total = 32;
        for (int index = 0; index < count; index++) {
            Bundle values = request.getBundle(RuntimeKeys.PROVIDER_BULK_VALUE_PREFIX + index);
            if (values == null) {
                throw new IllegalArgumentException("PROVIDER_BULK_VALUE_MISSING:" + index);
            }
            total = add(total, estimateBundle(values, 0));
            if (total > MAX_BYTES) throw new IllegalArgumentException("PROVIDER_BULK_TOO_LARGE");
        }
    }

    private static int estimateBundle(Bundle value, int depth) {
        if (depth > 8) throw new IllegalArgumentException("PROVIDER_BULK_VALUE_TOO_DEEP");
        int total = 16;
        for (String key : value.keySet()) {
            total = add(total, utf8(key) + 8);
            total = add(total, estimateValue(value.get(key), depth + 1));
        }
        return total;
    }

    private static int estimateValue(Object value, int depth) {
        if (value == null) return 1;
        if (value instanceof String) return add(utf8((String) value), 4);
        if (value instanceof Integer || value instanceof Float || value instanceof Boolean) return 8;
        if (value instanceof Long || value instanceof Double) return 12;
        if (value instanceof byte[]) return add(((byte[]) value).length, 4);
        if (value instanceof Bundle) return estimateBundle((Bundle) value, depth);
        throw new IllegalArgumentException("PROVIDER_BULK_VALUE_UNSUPPORTED:" + value.getClass().getName());
    }

    private static int utf8(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static int add(int left, int right) {
        try { return Math.addExact(left, right); }
        catch (ArithmeticException error) { throw new IllegalArgumentException("PROVIDER_BULK_TOO_LARGE", error); }
    }
}
