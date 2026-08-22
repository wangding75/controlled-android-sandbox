package com.warden.controlledsandbox.fixture;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.content.res.AssetFileDescriptor;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.net.Uri;
import android.util.Log;
import java.io.File;
import java.io.FileNotFoundException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class FixtureProvider extends ContentProvider {
    private static final String[] COLUMNS = {"_id", "value"};
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, String> rows = new LinkedHashMap<>();

    @Override public synchronized boolean onCreate() {
        rows.put(nextId.getAndIncrement(), "seed");
        Log.i("CS_FIXTURE", "PROVIDER_CREATE process=" + getContext().getApplicationInfo().processName);
        return true;
    }

    @Override public synchronized Cursor query(Uri uri, String[] projection, String selection,
                                                String[] selectionArgs, String sortOrder) {
        if ("cas.anr".equals(selection)) {
            Log.i("CS_FAULT", "ANR_PROVIDER_BEGIN uri=" + uri);
            try {
                Thread.sleep(25_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            Log.i("CS_FAULT", "ANR_PROVIDER_END");
        }
        String[] columns = projection == null || projection.length == 0 ? COLUMNS : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        for (Map.Entry<Long, String> row : rows.entrySet()) {
            Object[] values = new Object[columns.length];
            for (int index = 0; index < columns.length; index++) {
                if ("_id".equals(columns[index])) values[index] = row.getKey();
                else if ("value".equals(columns[index])) values[index] = row.getValue();
                else values[index] = null;
            }
            cursor.addRow(values);
        }
        Log.i("CS_FIXTURE", "PROVIDER_QUERY rows=" + rows.size() + " uri=" + uri);
        return cursor;
    }

    @Override public String getType(Uri uri) { return "vnd.android.cursor.dir/vnd.controlledsandbox.fixture"; }

    @Override public synchronized Uri insert(Uri uri, ContentValues values) {
        long id = nextId.getAndIncrement();
        String value = values == null ? null : values.getAsString("value");
        rows.put(id, value == null ? "" : value);
        notifyChanged(uri);
        Log.i("CS_FIXTURE", "PROVIDER_INSERT id=" + id);
        return Uri.parse(uri.toString() + "/" + id);
    }

    @Override public synchronized int bulkInsert(Uri uri, ContentValues[] values) {
        int inserted = 0;
        if (values != null) {
            for (ContentValues value : values) {
                long id = nextId.getAndIncrement();
                String text = value == null ? null : value.getAsString("value");
                rows.put(id, text == null ? "" : text);
                inserted++;
            }
        }
        if (inserted > 0) notifyChanged(uri);
        Log.i("CS_FIXTURE", "PROVIDER_BULK_INSERT count=" + inserted);
        return inserted;
    }

    @Override public synchronized int delete(Uri uri, String selection, String[] selectionArgs) {
        int count = rows.size();
        rows.clear();
        if (count > 0) notifyChanged(uri);
        Log.i("CS_FIXTURE", "PROVIDER_DELETE count=" + count);
        return count;
    }

    @Override public synchronized int update(Uri uri, ContentValues values,
                                               String selection, String[] selectionArgs) {
        String value = values == null ? null : values.getAsString("value");
        if (value == null) return 0;
        for (Long id : new java.util.ArrayList<>(rows.keySet())) rows.put(id, value);
        if (!rows.isEmpty()) notifyChanged(uri);
        Log.i("CS_FIXTURE", "PROVIDER_UPDATE count=" + rows.size());
        return rows.size();
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if ("c2-t02-attribution".equals(method)) {
            Bundle result = new Bundle();
            String callingPackage = (String) invokeCalling("getCallingPackage");
            result.putString("callingPackage", callingPackage);
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                Object source = invokeCalling("getCallingAttributionSource");
                result.putString("callingAttributionPackage",
                        source == null ? "" : (String) invokeValue(source, "getPackageName"));
                result.putInt("callingAttributionUid", source == null ? -1
                        : (Integer) invokeValue(source, "getUid"));
            } else {
                result.putString("callingAttributionPackage", callingPackage);
                result.putInt("callingAttributionUid", -1);
            }
            return result;
        }
        if ("batch-call".equals(method)) {
            Bundle result = new Bundle();
            result.putString("method", method);
            result.putString("arg", arg);
            result.putString("extra", extras == null ? null : extras.getString("source"));
            return result;
        }
        if ("batch-fail".equals(method)) {
            throw new IllegalStateException("fixture batch failure");
        }
        return super.call(method, arg, extras);
    }

    private Object invokeCalling(String methodName) {
        try {
            Method method = ContentProvider.class.getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(this);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException("fixture calling identity invocation failed", cause);
        } catch (Exception error) {
            throw new IllegalStateException("fixture calling identity method unavailable: "
                    + methodName, error);
        }
    }

    private static Object invokeValue(Object receiver, String methodName) {
        try {
            Method method = receiver.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(receiver);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException("fixture attribution invocation failed", cause);
        } catch (Exception error) {
            throw new IllegalStateException("fixture attribution method unavailable: "
                    + methodName, error);
        }
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = new File(getContext().getFilesDir(), "fixture-provider.txt");
        return ParcelFileDescriptor.open(file, descriptorMode(mode));
    }

    @Override public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
        return new AssetFileDescriptor(openFile(uri, mode), 0L, AssetFileDescriptor.UNKNOWN_LENGTH);
    }

    @Override public AssetFileDescriptor openTypedAssetFile(Uri uri, String mimeType, Bundle options)
            throws FileNotFoundException {
        if (mimeType == null || mimeType.trim().isEmpty()) throw new FileNotFoundException("mimeType required");
        return openAssetFile(uri, "r");
    }

    private static int descriptorMode(String mode) throws FileNotFoundException {
        if ("r".equals(mode)) return ParcelFileDescriptor.MODE_READ_ONLY;
        if ("w".equals(mode) || "wt".equals(mode)) {
            return ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_TRUNCATE;
        }
        if ("wa".equals(mode)) {
            return ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_APPEND;
        }
        if ("rw".equals(mode)) {
            return ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE;
        }
        if ("rwt".equals(mode)) {
            return ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_TRUNCATE;
        }
        throw new FileNotFoundException("Unsupported mode: " + mode);
    }

    private void notifyChanged(Uri uri) {
        if (getContext() != null) getContext().getContentResolver().notifyChange(uri, null);
    }

}
