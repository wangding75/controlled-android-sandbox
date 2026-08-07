package com.warden.controlledsandbox.runtime.provider;

import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.content.ContentProvider;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentValues;
import android.content.OperationApplicationException;
import android.net.Uri;
import android.os.Bundle;
import com.warden.controlledsandbox.domain.component.provider.UriGrantRegistry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Validation, bounded wire encoding and Guest-side execution for Provider batches. */
public final class ProviderBatchRuntime {
    static final int MAX_OPERATIONS = 128;
    static final int MAX_OPERATION_BYTES = 64 * 1024;
    static final int MAX_BATCH_BYTES = 512 * 1024;

    static final String INSERT = "INSERT";
    static final String UPDATE = "UPDATE";
    static final String DELETE = "DELETE";
    static final String ASSERT = "ASSERT";
    static final String CALL = "CALL";

    public static final class BatchException extends Exception {
        private static final long serialVersionUID = 1L;
        private final int operationIndex;

        BatchException(int operationIndex, String message) {
            super(message);
            this.operationIndex = operationIndex;
        }

        BatchException(int operationIndex, String message, Throwable cause) {
            super(message, cause);
            this.operationIndex = operationIndex;
        }

        public int operationIndex() { return operationIndex; }
    }

    static final class Operation {
        private final int index;
        private final String type;
        private final String uri;
        private final Bundle wire;
        private final int flags;

        Operation(int index, String type, String uri, Bundle wire, int flags) {
            this.index = index;
            this.type = type;
            this.uri = uri;
            this.wire = wire;
            this.flags = flags;
        }

        int index() { return index; }
        String type() { return type; }
        String uri() { return uri; }
        Bundle wire() { return new Bundle(wire); }
        int flags() { return flags; }
    }

    public static final class Batch {
        private final String authority;
        private final List<Operation> operations;
        private final int requiredFlags;
        private final int estimatedBytes;
        private final boolean containsCall;

        Batch(String authority, List<Operation> operations, int requiredFlags,
              int estimatedBytes, boolean containsCall) {
            this.authority = authority;
            this.operations = Collections.unmodifiableList(new ArrayList<>(operations));
            this.requiredFlags = requiredFlags;
            this.estimatedBytes = estimatedBytes;
            this.containsCall = containsCall;
        }

        String authority() { return authority; }
        List<Operation> operations() { return operations; }
        int requiredFlags() { return requiredFlags; }
        int estimatedBytes() { return estimatedBytes; }
        boolean containsCall() { return containsCall; }
    }

    private ProviderBatchRuntime() { }

    public static Batch validate(Bundle request, String expectedAuthority) throws BatchException {
        if (request == null) throw new BatchException(-1, "PROVIDER_BATCH_REQUEST_REQUIRED");
        String authority = requiredAuthority(expectedAuthority);
        int count = request.getInt(RuntimeKeys.PROVIDER_BATCH_COUNT, -1);
        if (count <= 0) throw new BatchException(-1, "PROVIDER_BATCH_EMPTY");
        if (count > MAX_OPERATIONS) throw new BatchException(-1, "PROVIDER_BATCH_TOO_MANY_OPERATIONS");

        List<Operation> operations = new ArrayList<>(count);
        int flags = 0;
        int totalBytes = 32;
        boolean containsCall = false;
        for (int index = 0; index < count; index++) {
            Bundle operation = request.getBundle(RuntimeKeys.PROVIDER_BATCH_OPERATION_PREFIX + index);
            if (operation == null) throw new BatchException(index, "PROVIDER_BATCH_OPERATION_MISSING");
            Bundle copy = new Bundle(operation);
            String type = copy.getString(RuntimeKeys.PROVIDER_BATCH_TYPE, "").trim().toUpperCase(java.util.Locale.ROOT);
            if (!isKnownType(type)) throw new BatchException(index, "PROVIDER_BATCH_UNKNOWN_OPERATION:" + type);
            String uri = copy.getString(RuntimeKeys.URI, "");
            if (CALL.equals(type) && uri.trim().isEmpty()) uri = "content://" + authority;
            validateUriAuthority(uri, authority, index);
            validateOperation(type, copy, index);
            copy.putString(RuntimeKeys.PROVIDER_BATCH_TYPE, type);
            copy.putString(RuntimeKeys.URI, uri);
            int operationBytes;
            try {
                operationBytes = estimateBundle(copy, 0);
            } catch (IllegalArgumentException error) {
                throw new BatchException(index, "PROVIDER_BATCH_UNSUPPORTED_VALUE", error);
            }
            if (operationBytes > MAX_OPERATION_BYTES) {
                throw new BatchException(index, "PROVIDER_BATCH_OPERATION_TOO_LARGE");
            }
            totalBytes = Math.addExact(totalBytes, operationBytes);
            if (totalBytes > MAX_BATCH_BYTES) throw new BatchException(index, "PROVIDER_BATCH_TOO_LARGE");
            int operationFlags = flagsFor(type);
            flags |= operationFlags;
            containsCall |= CALL.equals(type);
            operations.add(new Operation(index, type, uri, copy, operationFlags));
        }
        return new Batch(authority, operations, flags, totalBytes, containsCall);
    }

    public static Bundle execute(ContentProvider provider, Batch batch) throws BatchException {
        if (provider == null) throw new BatchException(-1, "PROVIDER_BATCH_PROVIDER_REQUIRED");
        if (batch.containsCall()) {
            if (!(provider instanceof AtomicProviderBatch)) {
                throw new BatchException(-1, "PROVIDER_BATCH_CALL_REQUIRES_ATOMIC_EXTENSION");
            }
            ArrayList<Bundle> wireOperations = new ArrayList<>();
            for (Operation operation : batch.operations()) wireOperations.add(operation.wire());
            try {
                Bundle result = ((AtomicProviderBatch) provider).applyAtomicBatch(batch.authority(), wireOperations);
                validateResult(result, batch.operations().size());
                return new Bundle(result);
            } catch (BatchException error) {
                throw error;
            } catch (AtomicProviderBatchException error) {
                throw new BatchException(error.operationIndex(),
                        "PROVIDER_BATCH_CUSTOM_EXECUTION_FAILED", error);
            } catch (Throwable error) {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
                throw new BatchException(-1, "PROVIDER_BATCH_CUSTOM_EXECUTION_FAILED", error);
            }
        }

        ArrayList<ContentProviderOperation> nativeOperations = new ArrayList<>();
        for (Operation operation : batch.operations()) nativeOperations.add(toNativeOperation(operation));
        try {
            ContentProviderResult[] results = provider.applyBatch(nativeOperations);
            return encodeNativeResults(batch, results);
        } catch (OperationApplicationException error) {
            throw new BatchException(-1, "PROVIDER_BATCH_APPLICATION_FAILED", error);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw new BatchException(-1, "PROVIDER_BATCH_EXECUTION_FAILED", error);
        }
    }

    public static void validateResult(Bundle result, int expectedCount) throws BatchException {
        if (result == null) throw new BatchException(-1, "PROVIDER_BATCH_NULL_RESULT");
        if (!"PROVIDER_BATCH_APPLIED".equals(result.getString(RuntimeKeys.STATUS, ""))) {
            throw new BatchException(result.getInt(RuntimeKeys.PROVIDER_BATCH_FAILURE_INDEX, -1),
                    "PROVIDER_BATCH_INVALID_RESULT_STATUS");
        }
        int count = result.getInt(RuntimeKeys.PROVIDER_BATCH_RESULT_COUNT, -1);
        if (count != expectedCount) throw new BatchException(-1, "PROVIDER_BATCH_RESULT_COUNT_MISMATCH");
        for (int index = 0; index < count; index++) {
            Bundle item = result.getBundle(RuntimeKeys.PROVIDER_BATCH_RESULT_PREFIX + index);
            if (item == null) throw new BatchException(index, "PROVIDER_BATCH_RESULT_MISSING");
        }
        if (estimateBundle(result, 0) > MAX_BATCH_BYTES) {
            throw new BatchException(-1, "PROVIDER_BATCH_RESULT_TOO_LARGE");
        }
    }

    private static ContentProviderOperation toNativeOperation(Operation operation) throws BatchException {
        Uri uri = Uri.parse(operation.uri());
        Bundle wire = operation.wire();
        try {
            ContentProviderOperation.Builder builder;
            switch (operation.type()) {
                case INSERT:
                    builder = ContentProviderOperation.newInsert(uri)
                            .withValues(contentValues(wire.getBundle(RuntimeKeys.PROVIDER_VALUES)));
                    break;
                case UPDATE:
                    builder = ContentProviderOperation.newUpdate(uri)
                            .withValues(contentValues(wire.getBundle(RuntimeKeys.PROVIDER_VALUES)));
                    applySelection(builder, wire);
                    break;
                case DELETE:
                    builder = ContentProviderOperation.newDelete(uri);
                    applySelection(builder, wire);
                    break;
                case ASSERT:
                    builder = ContentProviderOperation.newAssertQuery(uri);
                    ArrayList<String> projection = wire.getStringArrayList(RuntimeKeys.PROVIDER_PROJECTION);
                    applySelection(builder, wire);
                    int expected = wire.getInt(RuntimeKeys.PROVIDER_BATCH_EXPECTED_COUNT, -1);
                    if (expected >= 0) builder.withExpectedCount(expected);
                    break;
                default:
                    throw new BatchException(operation.index(), "PROVIDER_BATCH_NATIVE_TYPE_UNSUPPORTED");
            }
            return builder.build();
        } catch (BatchException error) {
            throw error;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw new BatchException(operation.index(), "PROVIDER_BATCH_BUILD_FAILED", error);
        }
    }

    private static void applySelection(ContentProviderOperation.Builder builder, Bundle wire) {
        ArrayList<String> args = wire.getStringArrayList(RuntimeKeys.PROVIDER_SELECTION_ARGS);
        builder.withSelection(wire.getString(RuntimeKeys.PROVIDER_SELECTION, ""),
                args == null ? null : args.toArray(new String[0]));
    }

    private static Bundle encodeNativeResults(Batch batch, ContentProviderResult[] results) throws BatchException {
        int count = batch.operations().size();
        if (results == null || results.length != count) {
            throw new BatchException(-1, "PROVIDER_BATCH_NATIVE_RESULT_COUNT_MISMATCH");
        }
        Bundle out = new Bundle();
        out.putString(RuntimeKeys.STATUS, "PROVIDER_BATCH_APPLIED");
        out.putInt(RuntimeKeys.PROVIDER_BATCH_RESULT_COUNT, count);
        out.putInt(RuntimeKeys.PROVIDER_BATCH_ESTIMATED_BYTES, batch.estimatedBytes());
        for (int index = 0; index < count; index++) {
            ContentProviderResult result = results[index];
            Bundle item = new Bundle();
            item.putString(RuntimeKeys.PROVIDER_BATCH_TYPE, batch.operations().get(index).type());
            if (result != null && result.uri != null) item.putString(RuntimeKeys.URI, result.uri.toString());
            if (result != null && result.count != null) item.putInt(RuntimeKeys.PROVIDER_BATCH_AFFECTED_ROWS, result.count);
            out.putBundle(RuntimeKeys.PROVIDER_BATCH_RESULT_PREFIX + index, item);
        }
                validateResult(out, count);
        return out;
    }

    private static void validateOperation(String type, Bundle operation, int index) throws BatchException {
        if (INSERT.equals(type) || UPDATE.equals(type)) {
            Bundle values = operation.getBundle(RuntimeKeys.PROVIDER_VALUES);
            if (values == null) throw new BatchException(index, "PROVIDER_BATCH_VALUES_REQUIRED");
            try { contentValues(values); }
            catch (IllegalArgumentException error) {
                throw new BatchException(index, "PROVIDER_BATCH_VALUES_INVALID", error);
            }
        }
        if (ASSERT.equals(type)) {
            int expected = operation.getInt(RuntimeKeys.PROVIDER_BATCH_EXPECTED_COUNT, -1);
            if (expected < -1) throw new BatchException(index, "PROVIDER_BATCH_EXPECTED_COUNT_INVALID");
        }
        if (CALL.equals(type)) {
            String method = operation.getString(RuntimeKeys.PROVIDER_METHOD, "");
            if (method == null || method.trim().isEmpty()) {
                throw new BatchException(index, "PROVIDER_BATCH_CALL_METHOD_REQUIRED");
            }
        }
    }

    private static int flagsFor(String type) {
        if (ASSERT.equals(type)) return UriGrantRegistry.READ;
        if (CALL.equals(type)) return UriGrantRegistry.READ | UriGrantRegistry.WRITE;
        return UriGrantRegistry.WRITE;
    }

    private static boolean isKnownType(String type) {
        return INSERT.equals(type) || UPDATE.equals(type) || DELETE.equals(type)
                || ASSERT.equals(type) || CALL.equals(type);
    }

    private static ContentValues contentValues(Bundle values) {
        ContentValues out = new ContentValues();
        if (values == null) return out;
        for (String key : values.keySet()) {
            Object value = values.get(key);
            if (value == null) out.putNull(key);
            else if (value instanceof String) out.put(key, (String) value);
            else if (value instanceof Integer) out.put(key, (Integer) value);
            else if (value instanceof Long) out.put(key, (Long) value);
            else if (value instanceof Boolean) out.put(key, (Boolean) value);
            else if (value instanceof Float) out.put(key, (Float) value);
            else if (value instanceof Double) out.put(key, (Double) value);
            else if (value instanceof byte[]) out.put(key, (byte[]) value);
            else throw new IllegalArgumentException("Unsupported ContentValues type for " + key);
        }
        return out;
    }

    private static int estimateBundle(Bundle value, int depth) {
        if (depth > 8) throw new IllegalArgumentException("Bundle nesting too deep");
        int total = 16;
        for (String key : value.keySet()) {
            total = Math.addExact(total, utf8(key) + 8 + estimateValue(value.get(key), depth + 1));
        }
        return total;
    }

    private static int estimateValue(Object value, int depth) {
        if (value == null) return 1;
        if (value instanceof String) return utf8((String) value) + 4;
        if (value instanceof Integer || value instanceof Float || value instanceof Boolean) return 8;
        if (value instanceof Long || value instanceof Double) return 12;
        if (value instanceof byte[]) return ((byte[]) value).length + 4;
        if (value instanceof Bundle) return estimateBundle((Bundle) value, depth);
        if (value instanceof ArrayList<?>) {
            int total = 8;
            for (Object item : (ArrayList<?>) value) {
                if (!(item instanceof String)) throw new IllegalArgumentException("Only String ArrayList is supported");
                total = Math.addExact(total, utf8((String) item) + 4);
            }
            return total;
        }
        throw new IllegalArgumentException("Unsupported Bundle type: " + value.getClass().getName());
    }

    private static int utf8(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static void validateUriAuthority(String uri, String authority, int index) throws BatchException {
        String prefix = "content://";
        if (uri == null || !uri.startsWith(prefix)) {
            throw new BatchException(index, "PROVIDER_BATCH_URI_SCHEME_INVALID");
        }
        int slash = uri.indexOf('/', prefix.length());
        String actual = slash < 0 ? uri.substring(prefix.length()) : uri.substring(prefix.length(), slash);
        if (!authority.equals(actual)) throw new BatchException(index, "PROVIDER_BATCH_URI_AUTHORITY_MISMATCH");
    }

    private static String requiredAuthority(String authority) throws BatchException {
        if (authority == null || authority.trim().isEmpty()) {
            throw new BatchException(-1, "PROVIDER_BATCH_AUTHORITY_REQUIRED");
        }
        return authority;
    }
}
