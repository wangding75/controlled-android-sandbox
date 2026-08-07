package com.warden.controlledsandbox.runtime.provider;

import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** Host-JVM checks for bounded Provider Batch validation and execution. */
public final class ProviderBatchRuntimeSelfTest {
    private static final String AUTHORITY = "batch.authority";

    private ProviderBatchRuntimeSelfTest() { }

    public static void main(String[] args) throws Exception {
        standardBatch();
        validationFailures();
        customCallBatch();
        customCallRequiresExtension();
        customFailureIndex();
        concurrentBatches();
        resultValidation();
        assertionWithProjectionFails();
        assertionWithValuesMatchingPasses();
        assertionWithValuesMismatchFails();
        assertionWithValuesAndExpectedCountPasses();
        assertionWithSelectionArgsAndValues();
        assertionWithNoValuesOnlyCount();
        System.out.println("PASS ProviderBatchRuntimeSelfTest");
    }

    private static void assertionWithValuesMatchingPasses() throws Exception {
        RecordingProvider provider = new RecordingProvider();
        provider.insert(Uri.parse("content://" + AUTHORITY + "/items"), cv("READY"));
        Bundle assertOp = operation(ProviderBatchRuntime.ASSERT, "/items", values("READY"), -1);
        Bundle request = request(assertOp);
        ProviderBatchRuntime.Batch batch = ProviderBatchRuntime.validate(request, AUTHORITY);
        Bundle result = ProviderBatchRuntime.execute(provider, batch);
        require("PROVIDER_BATCH_APPLIED".equals(result.getString(RuntimeKeys.STATUS, "")), "assertion matching values status");
    }

    private static void assertionWithValuesMismatchFails() throws Exception {
        RecordingProvider provider = new RecordingProvider();
        provider.insert(Uri.parse("content://" + AUTHORITY + "/items"), cv("FAILED"));
        Bundle assertOp = operation(ProviderBatchRuntime.ASSERT, "/items", values("READY"), -1);
        Bundle request = request(assertOp);
        ProviderBatchRuntime.Batch batch = ProviderBatchRuntime.validate(request, AUTHORITY);
        try {
            ProviderBatchRuntime.execute(provider, batch);
            throw new AssertionError("Expected BatchException for mismatched assertion values");
        } catch (ProviderBatchRuntime.BatchException error) {
            require("PROVIDER_BATCH_APPLICATION_FAILED".equals(error.getMessage()), "error message for mismatch");
        }
    }

    private static void assertionWithValuesAndExpectedCountPasses() throws Exception {
        RecordingProvider provider = new RecordingProvider();
        provider.insert(Uri.parse("content://" + AUTHORITY + "/items"), cv("READY"));
        Bundle assertOp = operation(ProviderBatchRuntime.ASSERT, "/items", values("READY"), 1);
        Bundle request = request(assertOp);
        ProviderBatchRuntime.Batch batch = ProviderBatchRuntime.validate(request, AUTHORITY);
        Bundle result = ProviderBatchRuntime.execute(provider, batch);
        require("PROVIDER_BATCH_APPLIED".equals(result.getString(RuntimeKeys.STATUS, "")), "assertion matching values and count status");
    }

    private static void assertionWithSelectionArgsAndValues() throws Exception {
        RecordingProvider provider = new RecordingProvider();
        provider.insert(Uri.parse("content://" + AUTHORITY + "/items"), cv("READY"));
        Bundle assertOp = operation(ProviderBatchRuntime.ASSERT, "/items", values("READY"), 1);
        assertOp.putString(RuntimeKeys.PROVIDER_SELECTION, "_id=?");
        ArrayList<String> args = new ArrayList<>();
        args.add("1");
        assertOp.putStringArrayList(RuntimeKeys.PROVIDER_SELECTION_ARGS, args);
        Bundle request = request(assertOp);
        ProviderBatchRuntime.Batch batch = ProviderBatchRuntime.validate(request, AUTHORITY);
        Bundle result = ProviderBatchRuntime.execute(provider, batch);
        require("PROVIDER_BATCH_APPLIED".equals(result.getString(RuntimeKeys.STATUS, "")), "assertion with selection args status");
    }

    private static void assertionWithNoValuesOnlyCount() throws Exception {
        RecordingProvider provider = new RecordingProvider();
        provider.insert(Uri.parse("content://" + AUTHORITY + "/items"), cv("READY"));
        Bundle assertOp = operation(ProviderBatchRuntime.ASSERT, "/items", null, 1);
        Bundle request = request(assertOp);
        ProviderBatchRuntime.Batch batch = ProviderBatchRuntime.validate(request, AUTHORITY);
        Bundle result = ProviderBatchRuntime.execute(provider, batch);
        require("PROVIDER_BATCH_APPLIED".equals(result.getString(RuntimeKeys.STATUS, "")), "assertion count only status");
    }

    private static void assertionWithProjectionFails() throws Exception {
        Bundle assertOp = operation(ProviderBatchRuntime.ASSERT, "/items", null, 1);
        ArrayList<String> proj = new ArrayList<>();
        proj.add("column1");
        assertOp.putStringArrayList(RuntimeKeys.PROVIDER_PROJECTION, proj);
        Bundle request = request(assertOp);
        ProviderBatchRuntime.Batch batch = ProviderBatchRuntime.validate(request, AUTHORITY);
        try {
            ProviderBatchRuntime.execute(new RecordingProvider(), batch);
            throw new AssertionError("Expected BatchException for non-empty projection in Assert");
        } catch (ProviderBatchRuntime.BatchException error) {
            require(error.operationIndex() == 0, "operation index");
            require("PROVIDER_BATCH_ASSERT_PROJECTION_UNSUPPORTED".equals(error.getMessage()), "error message");
        }
    }

    private static void standardBatch() throws Exception {
        RecordingProvider provider = new RecordingProvider();
        Bundle request = request(
                operation(ProviderBatchRuntime.INSERT, "/items", values("one"), -1),
                operation(ProviderBatchRuntime.UPDATE, "/items", values("two"), -1),
                operation(ProviderBatchRuntime.ASSERT, "/items", null, 1),
                operation(ProviderBatchRuntime.DELETE, "/items", null, -1));
        ProviderBatchRuntime.Batch batch = ProviderBatchRuntime.validate(request, AUTHORITY);
        require(batch.operations().size() == 4, "operation count");
        require(batch.requiredFlags() == 3, "mixed read/write flags");
        Bundle result = ProviderBatchRuntime.execute(provider, batch);
        require("PROVIDER_BATCH_APPLIED".equals(result.getString(RuntimeKeys.STATUS, "")), "batch status");
        require(result.getInt(RuntimeKeys.PROVIDER_BATCH_RESULT_COUNT, -1) == 4, "result count");
        require(provider.rows.isEmpty(), "delete applied");
    }

    private static void validationFailures() throws Exception {
        Bundle mismatch = request(operation(ProviderBatchRuntime.INSERT, "content://other/items", values("x"), -1));
        expectBatchFailure(0, () -> ProviderBatchRuntime.validate(mismatch, AUTHORITY));

        Bundle unknown = request(operation("DROP", "/items", null, -1));
        expectBatchFailure(0, () -> ProviderBatchRuntime.validate(unknown, AUTHORITY));

        Bundle missingValues = request(operation(ProviderBatchRuntime.INSERT, "/items", null, -1));
        expectBatchFailure(0, () -> ProviderBatchRuntime.validate(missingValues, AUTHORITY));

        Bundle tooMany = new Bundle();
        tooMany.putInt(RuntimeKeys.PROVIDER_BATCH_COUNT, ProviderBatchRuntime.MAX_OPERATIONS + 1);
        expectBatchFailure(-1, () -> ProviderBatchRuntime.validate(tooMany, AUTHORITY));

        Bundle huge = operation(ProviderBatchRuntime.INSERT, "/items", values("x"), -1);
        Bundle hugeValues = new Bundle();
        hugeValues.putByteArray("blob", new byte[ProviderBatchRuntime.MAX_OPERATION_BYTES]);
        huge.putBundle(RuntimeKeys.PROVIDER_VALUES, hugeValues);
        expectBatchFailure(0, () -> ProviderBatchRuntime.validate(request(huge), AUTHORITY));
    }

    private static void customCallBatch() throws Exception {
        AtomicRecordingProvider provider = new AtomicRecordingProvider();
        Bundle call = operation(ProviderBatchRuntime.CALL, "", null, -1);
        call.putString(RuntimeKeys.PROVIDER_METHOD, "refresh");
        Bundle request = request(call);
        ProviderBatchRuntime.Batch batch = ProviderBatchRuntime.validate(request, AUTHORITY);
        require(batch.requiredFlags() == 3, "call batch requires read and write");
        require(ProviderBatchRuntime.CALL.equals(batch.operations().get(0).wire()
                .getString(RuntimeKeys.PROVIDER_BATCH_TYPE, "")), "call type normalized");
        require(("content://" + AUTHORITY).equals(batch.operations().get(0).wire()
                .getString(RuntimeKeys.URI, "")), "call URI normalized");
        Bundle result = ProviderBatchRuntime.execute(provider, batch);
        require(provider.called, "atomic extension called");
        require(result.getInt(RuntimeKeys.PROVIDER_BATCH_RESULT_COUNT, -1) == 1, "custom result count");
    }

    private static void customCallRequiresExtension() throws Exception {
        Bundle call = operation(ProviderBatchRuntime.CALL, "", null, -1);
        call.putString(RuntimeKeys.PROVIDER_METHOD, "refresh");
        ProviderBatchRuntime.Batch batch = ProviderBatchRuntime.validate(request(call), AUTHORITY);
        expectBatchFailure(-1, () -> ProviderBatchRuntime.execute(new RecordingProvider(), batch));
    }


    private static void customFailureIndex() throws Exception {
        FailingAtomicProvider provider = new FailingAtomicProvider();
        Bundle call = operation(ProviderBatchRuntime.CALL, "", null, -1);
        call.putString(RuntimeKeys.PROVIDER_METHOD, "fail");
        ProviderBatchRuntime.Batch batch = ProviderBatchRuntime.validate(request(call), AUTHORITY);
        expectBatchFailure(0, () -> ProviderBatchRuntime.execute(provider, batch));
    }

    private static void concurrentBatches() throws Exception {
        RecordingProvider provider = new RecordingProvider();
        int workers = 16;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(workers);
        AtomicInteger successes = new AtomicInteger();
        for (int index = 0; index < workers; index++) {
            final int value = index;
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    Bundle batchRequest = request(operation(ProviderBatchRuntime.INSERT, "/items",
                            values("v" + value), -1));
                    ProviderBatchRuntime.execute(provider,
                            ProviderBatchRuntime.validate(batchRequest, AUTHORITY));
                    successes.incrementAndGet();
                } catch (Exception error) {
                    throw new AssertionError(error);
                } finally {
                    done.countDown();
                }
            });
            thread.start();
        }
        ready.await();
        start.countDown();
        done.await();
        require(successes.get() == workers, "concurrent batch success count");
        require(provider.rowCount() == workers, "concurrent batch row count");
    }

    private static void resultValidation() throws Exception {
        Bundle invalid = new Bundle();
        invalid.putString(RuntimeKeys.STATUS, "PROVIDER_BATCH_APPLIED");
        invalid.putInt(RuntimeKeys.PROVIDER_BATCH_RESULT_COUNT, 1);
        expectBatchFailure(0, () -> ProviderBatchRuntime.validateResult(invalid, 1));
    }

    private static Bundle request(Bundle... operations) {
        Bundle request = new Bundle();
        request.putString(ComponentOperations.AUTHORITY, AUTHORITY);
        request.putString(RuntimeKeys.URI, "content://" + AUTHORITY);
        request.putInt(RuntimeKeys.PROVIDER_BATCH_COUNT, operations.length);
        for (int index = 0; index < operations.length; index++) {
            request.putBundle(RuntimeKeys.PROVIDER_BATCH_OPERATION_PREFIX + index, operations[index]);
        }
        return request;
    }

    private static Bundle operation(String type, String pathOrUri, Bundle values, int expectedCount) {
        Bundle operation = new Bundle();
        operation.putString(RuntimeKeys.PROVIDER_BATCH_TYPE, type);
        String uri = pathOrUri == null || pathOrUri.isEmpty() ? ""
                : pathOrUri.startsWith("content://") ? pathOrUri : "content://" + AUTHORITY + pathOrUri;
        operation.putString(RuntimeKeys.URI, uri);
        if (values != null) operation.putBundle(RuntimeKeys.PROVIDER_VALUES, values);
        if (expectedCount >= 0) operation.putInt(RuntimeKeys.PROVIDER_BATCH_EXPECTED_COUNT, expectedCount);
        return operation;
    }

    private static Bundle values(String value) {
        Bundle values = new Bundle();
        values.putString("value", value);
        return values;
    }

    private static ContentValues cv(String value) {
        ContentValues cv = new ContentValues();
        cv.put("value", value);
        return cv;
    }

    private static void expectBatchFailure(int expectedIndex, CheckedRunnable action) throws Exception {
        try {
            action.run();
            throw new AssertionError("Expected ProviderBatchRuntime.BatchException");
        } catch (ProviderBatchRuntime.BatchException error) {
            require(error.operationIndex() == expectedIndex,
                    "failure index expected=" + expectedIndex + " actual=" + error.operationIndex());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public interface CheckedRunnable { void run() throws Exception; }

    static class RecordingProvider extends ContentProvider {
        final Map<Long, String> rows = new LinkedHashMap<>();
        long nextId = 1;

        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
            MatrixCursor cursor = new MatrixCursor(new String[]{"_id", "value"});
            for (Map.Entry<Long, String> row : rows.entrySet()) cursor.addRow(new Object[]{row.getKey(), row.getValue()});
            return cursor;
        }
        @Override public String getType(Uri uri) { return "vnd.test"; }
        @Override public synchronized Uri insert(Uri uri, ContentValues values) {
            long id = nextId++;
            rows.put(id, values == null ? "" : values.getAsString("value"));
            return Uri.parse(uri.toString() + "/" + id);
        }
        synchronized int rowCount() { return rows.size(); }
        @Override public synchronized int delete(Uri uri, String selection, String[] args) {
            int count = rows.size();
            rows.clear();
            return count;
        }
        @Override public synchronized int update(Uri uri, ContentValues values, String selection, String[] args) {
            String value = values == null ? "" : values.getAsString("value");
            for (Long id : new ArrayList<>(rows.keySet())) rows.put(id, value);
            return rows.size();
        }
    }


    static final class FailingAtomicProvider extends RecordingProvider implements AtomicProviderBatch {
        @Override public Bundle applyAtomicBatch(String authority, ArrayList<Bundle> operations)
                throws AtomicProviderBatchException {
            throw new AtomicProviderBatchException(0, "expected failure");
        }
    }

    static final class AtomicRecordingProvider extends RecordingProvider implements AtomicProviderBatch {
        boolean called;

        @Override public Bundle applyAtomicBatch(String authority, ArrayList<Bundle> operations) {
            called = true;
            Bundle result = new Bundle();
            result.putString(RuntimeKeys.STATUS, "PROVIDER_BATCH_APPLIED");
            result.putInt(RuntimeKeys.PROVIDER_BATCH_RESULT_COUNT, operations.size());
            for (int index = 0; index < operations.size(); index++) {
                Bundle item = new Bundle();
                item.putString(RuntimeKeys.PROVIDER_BATCH_TYPE,
                        operations.get(index).getString(RuntimeKeys.PROVIDER_BATCH_TYPE, ""));
                result.putBundle(RuntimeKeys.PROVIDER_BATCH_RESULT_PREFIX + index, item);
            }
            return result;
        }
    }
}
