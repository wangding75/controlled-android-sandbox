package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.os.Build;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.OperationApplicationException;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.content.res.AssetFileDescriptor;
import android.content.pm.PackageManager;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Package-neutral Guest API campaign for C1-T04.  Every operation in this class enters through
 * Context/ContentResolver; the Host command only controls Activity generations and records the
 * resulting markers.
 */
public final class ProviderCampaignActivity extends Activity {
    private static final String TAG = "CS_PROVIDER_CAMPAIGN";
    private static final String PEER_PACKAGE = "com.warden.controlledsandbox.fixture32";
    private static final int PAGE_ROWS = 128;
    private static final int URI_READ_WRITE = 1 | 2;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        new Thread(this::runCampaign, "c1-t04-provider-campaign").start();
    }

    private void runCampaign() {
        boolean passed = false;
        try {
            ContentResolver resolver = getContentResolver();
            Uri self = Uri.parse("content://" + getPackageName() + ".provider/rows");
            Uri peer = Uri.parse("content://" + PEER_PACKAGE + ".provider/rows");
            Uri fault = Uri.parse("content://" + getPackageName() + ".fault.anr/rows");
            delete(resolver, self);
            runCrudAndCursor(resolver, self);
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                throw new IllegalStateException("C1_T04_PROVIDER_BATCH_API_UNAVAILABLE");
            }
            runBatch(resolver, self);
            runFiles(resolver, self);
            runUriGrant(self);
            runCancellation(resolver, fault);
            runPeer(resolver, peer);
            delete(resolver, self);
            Log.i(TAG, "C1_T04_PROVIDER_PASS");
            passed = true;
        } catch (Throwable error) {
            Log.e(TAG, "C1_T04_PROVIDER_FAIL", error);
        } finally {
            final boolean result = passed;
            runOnUiThread(() -> {
                if (!result) Log.e(TAG, "C1_T04_PROVIDER_RUNTIME_FAIL");
                finish();
            });
        }
    }

    private void runCrudAndCursor(ContentResolver resolver, Uri uri) throws Exception {
        String type = resolver.getType(uri);
        if (!"vnd.android.cursor.dir/vnd.controlledsandbox.fixture".equals(type)) {
            throw new AssertionError("C1_T04_PROVIDER_TYPE_MISMATCH:" + type);
        }
        ContentValues[] values = new ContentValues[PAGE_ROWS];
        for (int index = 0; index < values.length; index++) {
            ContentValues value = new ContentValues();
            value.put("value", "c1-t04-bulk-" + index + "-" + UUID.randomUUID());
            values[index] = value;
        }
        int inserted = resolver.bulkInsert(uri, values);
        if (inserted != PAGE_ROWS) throw new AssertionError("C1_T04_PROVIDER_BULK_COUNT=" + inserted);
        for (int index = 0; index < 8; index++) {
            ContentValues value = new ContentValues();
            value.put("value", "c1-t04-page-tail-" + index);
            if (insert(resolver, uri, value) == null) throw new AssertionError("C1_T04_INSERT_NULL");
        }
        int rows = 0;
        try (Cursor cursor = resolver.query(uri, new String[]{"_id", "value"},
                null, null, "_id ASC")) {
            if (cursor == null || cursor.getCount() < PAGE_ROWS + 8) {
                throw new AssertionError("C1_T04_PROVIDER_CURSOR_COUNT="
                        + (cursor == null ? -1 : cursor.getCount()));
            }
            int idColumn = columnIndex(cursor, "_id");
            int valueColumn = columnIndex(cursor, "value");
            while (cursor.moveToNext()) {
                if (cursor.getLong(idColumn) <= 0 || cursor.getString(valueColumn) == null) {
                    throw new AssertionError("C1_T04_PROVIDER_CURSOR_ROW_INVALID");
                }
                rows++;
            }
            if (rows < PAGE_ROWS + 8 || !requery(cursor)) {
                throw new AssertionError("C1_T04_PROVIDER_CURSOR_REQUERY_FAILED rows=" + rows);
            }
        }
        ContentValues update = new ContentValues();
        update.put("value", "c1-t04-updated");
        int updated = update(resolver, uri, update);
        if (updated < PAGE_ROWS + 8) throw new AssertionError("C1_T04_PROVIDER_UPDATE=" + updated);
        Log.i(TAG, "C1_T04_PROVIDER_CRUD_PASS inserted=" + inserted + " updated=" + updated);
        Log.i(TAG, "C1_T04_PROVIDER_CURSOR_PASS rows=" + rows + " paged=true requery=true");
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.R)
    private void runBatch(ContentResolver resolver, Uri uri) throws Exception {
        ArrayList<ContentProviderOperation> operations = new ArrayList<>();
        operations.add(ContentProviderOperation.newInsert(uri)
                .withValue("value", "c1-t04-batch-insert").build());
        operations.add(ContentProviderOperation.newUpdate(uri)
                .withValue("value", "c1-t04-batch-update").build());
        Bundle extras = new Bundle();
        extras.putString("source", "c1-t04-batch");
        operations.add(ContentProviderOperation.newCall(uri, "batch-call", "c1-t04-batch-arg")
                .withExtras(extras).withYieldAllowed(true).build());
        operations.add(ContentProviderOperation.newCall(uri, "batch-fail", null)
                .withExceptionAllowed(true).build());
        ContentProviderOperation.Builder backReference =
                ContentProviderOperation.newCall(uri, "batch-call", "c1-t04-back-ref");
        withExtraBackReference(backReference, "source", 2, "extra");
        operations.add(backReference.build());
        ContentProviderResult[] results = resolver.applyBatch(uri.getAuthority(), operations);
        if (results == null || results.length != operations.size()
                || results[2] == null || results[2].extras == null
                || !"c1-t04-batch".equals(results[2].extras.getString("extra"))
                || results[3] == null || results[3].exception == null
                || results[4] == null || results[4].extras == null
                || !"c1-t04-batch".equals(results[4].extras.getString("extra"))) {
            throw new AssertionError("C1_T04_PROVIDER_BATCH_RESULT_INVALID");
        }
        Log.i(TAG, "C1_T04_PROVIDER_BATCH_PASS count=" + results.length + " rollback=true");
    }

    private void runFiles(ContentResolver resolver, Uri uri) throws Exception {
        byte[] expected = "c1-t04-provider-fd".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (ParcelFileDescriptor descriptor = openFileDescriptor(resolver, uri, "wt")) {
            if (descriptor == null) throw new IOException("C1_T04_FD_NULL");
            try (FileOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
                output.write(expected);
            }
        }
        byte[] actual;
        try (ParcelFileDescriptor descriptor = openFileDescriptor(resolver, uri, "r")) {
            if (descriptor == null) throw new IOException("C1_T04_FD_READ_NULL");
            try (FileInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[128];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (count > 0) output.write(buffer, 0, count);
                }
                actual = output.toByteArray();
            }
        }
        if (!java.util.Arrays.equals(expected, actual)) throw new IOException("C1_T04_FD_CONTENT_MISMATCH");
        try (AssetFileDescriptor asset = openAssetFileDescriptor(resolver, uri, "r")) {
            if (asset == null || asset.getParcelFileDescriptor() == null) {
                throw new IOException("C1_T04_ASSET_FD_NULL");
            }
        }
        try (AssetFileDescriptor typed = openTypedAssetFileDescriptor(
                resolver, uri, "text/plain", null)) {
            if (typed == null || typed.getParcelFileDescriptor() == null) {
                throw new IOException("C1_T04_TYPED_FD_NULL");
            }
        }
        Log.i(TAG, "C1_T04_PROVIDER_FD_PASS bytes=" + actual.length + " asset=true typed=true");
    }

    @android.annotation.SuppressLint("WrongConstant")
    private void runUriGrant(Uri uri) {
        grantUriPermission(PEER_PACKAGE, uri, URI_READ_WRITE);
        int check = checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(),
                URI_READ_WRITE);
        if (check != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("C1_T04_URI_GRANT_CHECK=" + check);
        }
        revokeUriPermission(uri, URI_READ_WRITE);
        Log.i(TAG, "C1_T04_PROVIDER_GRANT_PASS target=" + PEER_PACKAGE
                + " flags=" + URI_READ_WRITE + " check=" + check + " revoked=true");
    }

    private void runCancellation(ContentResolver resolver, Uri uri) throws Exception {
        CancellationSignal signal = new CancellationSignal();
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread query = new Thread(() -> {
            try (Cursor ignored = queryWithCancellation(resolver, uri, signal)) {
                failure.set(new AssertionError("C1_T04_PROVIDER_CANCEL_QUERY_RETURNED"));
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                finished.countDown();
            }
        }, "c1-t04-provider-cancel");
        query.start();
        Thread.sleep(400L);
        signal.cancel();
        if (!finished.await(10L, TimeUnit.SECONDS)) {
            throw new AssertionError("C1_T04_PROVIDER_CANCEL_TIMEOUT");
        }
        Throwable error = failure.get();
        String detail = error == null ? "" : String.valueOf(error) + " " + error.getClass().getName();
        String normalized = detail.toLowerCase(java.util.Locale.ROOT);
        if (error == null || !(normalized.contains("cancel") || normalized.contains("abort"))) {
            throw new AssertionError("C1_T04_PROVIDER_CANCEL_NOT_PROPAGATED:" + detail);
        }
        Log.i(TAG, "C1_T04_PROVIDER_CANCEL_PASS detail=" + error.getClass().getSimpleName());
    }

    private void runPeer(ContentResolver resolver, Uri uri) throws Exception {
        int rows;
        try (Cursor cursor = resolver.query(uri, new String[]{"_id", "value"},
                null, null, null)) {
            if (cursor == null || (rows = cursor.getCount()) < 1) {
                throw new AssertionError("C1_T04_PROVIDER_CROSS_PACKAGE_EMPTY");
            }
        }
        CountDownLatch changed = new CountDownLatch(1);
        ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange, Uri changedUri) {
                Log.i(TAG, "C1_T04_PROVIDER_OBSERVER_DELIVERED uri=" + changedUri
                        + " self=" + selfChange);
                changed.countDown();
            }
        };
        CountDownLatch registered = new CountDownLatch(1);
        runOnUiThread(() -> {
            resolver.registerContentObserver(uri, true, observer);
            resolver.notifyChange(uri, null);
            registered.countDown();
        });
        try {
            if (!registered.await(5L, TimeUnit.SECONDS)) {
                throw new AssertionError("C1_T04_PROVIDER_OBSERVER_REGISTER_TIMEOUT");
            }
            if (!changed.await(5L, TimeUnit.SECONDS)) {
                throw new AssertionError("C1_T04_PROVIDER_CROSS_PACKAGE_OBSERVER_TIMEOUT");
            }
        } finally {
            runOnUiThread(() -> resolver.unregisterContentObserver(observer));
        }
        Log.i(TAG, "C1_T04_PROVIDER_OBSERVER_PASS");
        Log.i(TAG, "C1_T04_PROVIDER_CROSS_PACKAGE_PASS rows=" + rows + " observer=true");
    }

    private static int columnIndex(Cursor cursor, String name) {
        return cursor.getColumnIndexOrThrow(name);
    }

    private static boolean requery(Cursor cursor) {
        return cursor.requery();
    }

    private static Uri insert(ContentResolver resolver, Uri uri, ContentValues values) {
        return resolver.insert(uri, values);
    }

    private static int update(ContentResolver resolver, Uri uri, ContentValues values) {
        return resolver.update(uri, values, null, null);
    }

    private static int delete(ContentResolver resolver, Uri uri) {
        return resolver.delete(uri, null, null);
    }

    private static ParcelFileDescriptor openFileDescriptor(ContentResolver resolver, Uri uri,
                                                           String mode) throws IOException {
        return resolver.openFileDescriptor(uri, mode);
    }

    private static AssetFileDescriptor openAssetFileDescriptor(ContentResolver resolver, Uri uri,
                                                               String mode) throws IOException {
        return resolver.openAssetFileDescriptor(uri, mode);
    }

    private static AssetFileDescriptor openTypedAssetFileDescriptor(ContentResolver resolver,
                                                                     Uri uri, String mimeType,
                                                                     Bundle options) throws IOException {
        return resolver.openTypedAssetFileDescriptor(uri, mimeType, options);
    }

    private static Cursor queryWithCancellation(ContentResolver resolver, Uri uri,
                                                CancellationSignal signal) {
        Bundle queryArgs = new Bundle();
        queryArgs.putString("android:query-arg-sql-selection", "cas.anr");
        return resolver.query(uri, new String[]{"_id"}, queryArgs, signal);
    }

    private static void withExtraBackReference(ContentProviderOperation.Builder builder,
                                                String key, int fromIndex, String fromKey) {
        try {
            java.lang.reflect.Method method = builder.getClass().getMethod(
                    "withExtraBackReference", String.class, int.class, String.class);
            method.invoke(builder, key, fromIndex, fromKey);
        } catch (Throwable error) {
            throw new IllegalStateException("C1_T04_EXTRA_BACK_REFERENCE_API_MISSING", error);
        }
    }
}
