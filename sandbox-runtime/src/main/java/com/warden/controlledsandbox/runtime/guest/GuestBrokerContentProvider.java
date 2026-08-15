package com.warden.controlledsandbox.runtime.guest;

import android.content.ContentProvider;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentValues;
import android.content.OperationApplicationException;
import android.content.res.AssetFileDescriptor;
import android.database.AbstractCursor;
import android.database.CharArrayBuffer;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import com.warden.controlledsandbox.runtime.provider.CursorWireCodec;
import com.warden.controlledsandbox.runtime.provider.ProviderBulkInsertRuntime;
import java.io.FileNotFoundException;
import java.util.ArrayList;

/** Local ContentProvider transport that projects standard ContentResolver calls onto the Broker. */
final class GuestBrokerContentProvider extends ContentProvider {
    private static final int PAGE_SIZE = 128;
    private final GuestRuntimeBrokerBridge bridge;
    private final GuestPackageSpec spec;
    private final String authority;
    private final String componentClass;

    GuestBrokerContentProvider(GuestContext context, GuestPackageSpec spec, String authority, String componentClass) {
        java.util.Objects.requireNonNull(context, "context");
        this.spec = java.util.Objects.requireNonNull(spec, "spec");
        this.bridge = new GuestRuntimeBrokerBridge(spec, context.mainThread);
        this.authority = required(authority, "authority");
        this.componentClass = required(componentClass, "componentClass");
    }

    @Override public boolean onCreate() { return true; }

    void prepare() {
        Bundle request = request(ComponentOperations.PREPARE_PROVIDER, null);
        request.putBoolean(RuntimeKeys.PROVIDER_EXPORTED, providerExported());
        bridge.invokeComponent(request);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        Bundle request = request(ComponentOperations.PROVIDER_QUERY, uri);
        request.putStringArrayList(RuntimeKeys.PROVIDER_PROJECTION, strings(projection));
        request.putString(RuntimeKeys.PROVIDER_SELECTION, selection);
        request.putStringArrayList(RuntimeKeys.PROVIDER_SELECTION_ARGS, strings(selectionArgs));
        request.putString(RuntimeKeys.PROVIDER_SORT_ORDER, sortOrder);
        request.putInt(RuntimeKeys.CURSOR_PAGE_SIZE, PAGE_SIZE);
        Bundle page = bridge.invokeComponent(request);
        String token = required(page, RuntimeKeys.CURSOR_TOKEN);
        ArrayList<String> columns = page.getStringArrayList(RuntimeKeys.CURSOR_COLUMNS);
        try {
            return new RemoteCursor(bridge, request, request(ComponentOperations.PROVIDER_CURSOR_PAGE, null),
                    columns == null ? new String[0] : columns.toArray(new String[0]),
                    Math.max(0, page.getInt(RuntimeKeys.CURSOR_TOTAL_ROWS, 0)), token, page);
        } catch (Throwable error) {
            Bundle close = request(ComponentOperations.PROVIDER_CURSOR_CLOSE, null);
            close.putString(RuntimeKeys.CURSOR_TOKEN, token);
            try { bridge.invokeComponent(close); } catch (RuntimeException ignored) { }
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw error instanceof RuntimeException ? (RuntimeException) error
                    : new IllegalStateException("PROVIDER_CURSOR_CREATE_FAILED", error);
        }
    }

    @Override public String getType(Uri uri) {
        Bundle result = bridge.invokeComponent(request(ComponentOperations.PROVIDER_GET_TYPE, uri));
        return result.getString("mimeType");
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        Bundle request = request(ComponentOperations.PROVIDER_INSERT, uri);
        request.putBundle(RuntimeKeys.PROVIDER_VALUES, values(values));
        String inserted = bridge.invokeComponent(request).getString(RuntimeKeys.URI, "");
        return inserted.isEmpty() ? null : Uri.parse(inserted);
    }

    @Override public int bulkInsert(Uri uri, ContentValues[] values) {
        if (values == null) return 0;
        Bundle request = request(ComponentOperations.PROVIDER_BULK_INSERT, uri);
        request.putInt(RuntimeKeys.PROVIDER_BULK_VALUE_COUNT, values.length);
        for (int index = 0; index < values.length; index++) {
            request.putBundle(RuntimeKeys.PROVIDER_BULK_VALUE_PREFIX + index, values(values[index]));
        }
        ProviderBulkInsertRuntime.validate(request);
        return bridge.invokeComponent(request).getInt("affectedRows", 0);
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        Bundle request = request(ComponentOperations.PROVIDER_DELETE, uri);
        request.putString(RuntimeKeys.PROVIDER_SELECTION, selection);
        request.putStringArrayList(RuntimeKeys.PROVIDER_SELECTION_ARGS, strings(selectionArgs));
        return bridge.invokeComponent(request).getInt("affectedRows", 0);
    }

    @Override public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) {
        Bundle request = request(ComponentOperations.PROVIDER_UPDATE, uri);
        request.putBundle(RuntimeKeys.PROVIDER_VALUES, values(values));
        request.putString(RuntimeKeys.PROVIDER_SELECTION, selection);
        request.putStringArrayList(RuntimeKeys.PROVIDER_SELECTION_ARGS, strings(selectionArgs));
        return bridge.invokeComponent(request).getInt("affectedRows", 0);
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        Bundle request = request(ComponentOperations.PROVIDER_CALL, Uri.parse("content://" + authority));
        request.putString(RuntimeKeys.PROVIDER_METHOD, required(method, "method"));
        request.putString(RuntimeKeys.PROVIDER_ARGUMENT, arg);
        if (extras != null) {
            request.putBundle(RuntimeKeys.PROVIDER_EXTRAS, new Bundle(extras));
        }
        Bundle result = bridge.invokeComponent(request).getBundle(RuntimeKeys.PROVIDER_RESULT);
        return result == null ? null : new Bundle(result);
    }

    @Override public ContentProviderResult[] applyBatch(ArrayList<ContentProviderOperation> operations)
            throws OperationApplicationException {
        if (operations == null || operations.isEmpty()) {
            throw new OperationApplicationException("PROVIDER_BATCH_EMPTY");
        }
        Bundle request = request(ComponentOperations.PROVIDER_APPLY_BATCH, null);
        request.putInt(RuntimeKeys.PROVIDER_BATCH_COUNT, operations.size());
        for (int index = 0; index < operations.size(); index++) {
            ContentProviderOperation operation = operations.get(index);
            if (operation == null) {
                throw new OperationApplicationException("PROVIDER_BATCH_OPERATION_MISSING:" + index);
            }
            Bundle wire = encodeBatchOperation(operation, index);
            request.putBundle(RuntimeKeys.PROVIDER_BATCH_OPERATION_PREFIX + index, wire);
        }
        android.util.Log.i("CS_RUNTIME", "GUEST_PROVIDER_TRANSPORT_APPLY_BATCH count="
                + operations.size() + " authority=" + authority);
        final Bundle result;
        try {
            result = bridge.invokeComponent(request);
        } catch (RuntimeException error) {
            throw new OperationApplicationException(
                    "PROVIDER_BATCH_TRANSPORT_FAILED:" + String.valueOf(error.getMessage()));
        }
        int count = result.getInt(RuntimeKeys.PROVIDER_BATCH_RESULT_COUNT, -1);
        if (!"PROVIDER_BATCH_APPLIED".equals(result.getString(RuntimeKeys.STATUS, ""))
                || count != operations.size()) {
            throw new OperationApplicationException("PROVIDER_BATCH_RESULT_INVALID count=" + count);
        }
        ContentProviderResult[] out = new ContentProviderResult[count];
        for (int index = 0; index < count; index++) {
            Bundle item = result.getBundle(RuntimeKeys.PROVIDER_BATCH_RESULT_PREFIX + index);
            if (item == null) {
                throw new OperationApplicationException("PROVIDER_BATCH_RESULT_MISSING:" + index);
            }
            String uri = item.getString(RuntimeKeys.URI, "");
            if (!uri.isEmpty()) {
                out[index] = new ContentProviderResult(Uri.parse(uri));
            } else if (item.containsKey(RuntimeKeys.PROVIDER_BATCH_AFFECTED_ROWS)) {
                out[index] = new ContentProviderResult(
                        item.getInt(RuntimeKeys.PROVIDER_BATCH_AFFECTED_ROWS, 0));
            } else {
                out[index] = new ContentProviderResult(0);
            }
        }
        return out;
    }

    private static Bundle encodeBatchOperation(ContentProviderOperation operation, int index)
            throws OperationApplicationException {
        rejectBackReferences(operation, index);
        Bundle wire = new Bundle();
        wire.putString(RuntimeKeys.URI, operation.getUri().toString());
        if (operation.isInsert()) {
            wire.putString(RuntimeKeys.PROVIDER_BATCH_TYPE, "INSERT");
            wire.putBundle(RuntimeKeys.PROVIDER_VALUES,
                    values(field(operation, "mValues")));
        } else if (operation.isUpdate()) {
            wire.putString(RuntimeKeys.PROVIDER_BATCH_TYPE, "UPDATE");
            wire.putBundle(RuntimeKeys.PROVIDER_VALUES,
                    values(field(operation, "mValues")));
            putSelection(wire, operation);
        } else if (operation.isDelete()) {
            wire.putString(RuntimeKeys.PROVIDER_BATCH_TYPE, "DELETE");
            putSelection(wire, operation);
        } else if (operation.isAssertQuery()) {
            wire.putString(RuntimeKeys.PROVIDER_BATCH_TYPE, "ASSERT");
            wire.putBundle(RuntimeKeys.PROVIDER_VALUES,
                    values(field(operation, "mValues")));
            putSelection(wire, operation);
            Object expected = field(operation, "mExpectedCount");
            wire.putInt(RuntimeKeys.PROVIDER_BATCH_EXPECTED_COUNT,
                    expected instanceof Number ? ((Number) expected).intValue() : -1);
        } else {
            throw new OperationApplicationException(
                    "PROVIDER_BATCH_OPERATION_UNSUPPORTED:" + index);
        }
        return wire;
    }

    private static void putSelection(Bundle wire, ContentProviderOperation operation) {
        wire.putString(RuntimeKeys.PROVIDER_SELECTION, (String) field(operation, "mSelection"));
        String[] args = (String[]) field(operation, "mSelectionArgs");
        if (args != null) {
            ArrayList<String> values = new ArrayList<>();
            java.util.Collections.addAll(values, args);
            wire.putStringArrayList(RuntimeKeys.PROVIDER_SELECTION_ARGS, values);
        }
    }

    private static void rejectBackReferences(ContentProviderOperation operation, int index)
            throws OperationApplicationException {
        if (hasBackReferences(field(operation, "mValuesBackReferences"))
                || hasBackReferences(field(operation, "mSelectionArgsBackReferences"))) {
            throw new OperationApplicationException(
                    "PROVIDER_BATCH_BACK_REFERENCES_UNSUPPORTED:" + index);
        }
    }

    private static boolean hasBackReferences(Object value) {
        if (value == null) return false;
        try {
            java.lang.reflect.Method size = value.getClass().getMethod("size");
            Object count = size.invoke(value);
            return count instanceof Number && ((Number) count).intValue() > 0;
        } catch (ReflectiveOperationException ignored) {
            return true;
        }
    }

    private static Object field(ContentProviderOperation operation, String name) {
        Class<?> type = operation.getClass();
        while (type != null) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(operation);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("PROVIDER_BATCH_OPERATION_FIELD_UNAVAILABLE:" + name,
                        error);
            }
        }
        return null;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        Bundle request = request(ComponentOperations.PROVIDER_OPEN_FILE, uri);
        request.putString(RuntimeKeys.PROVIDER_FILE_MODE, required(mode, "mode"));
        Bundle result = bridge.invokeComponent(request);
        ParcelFileDescriptor descriptor = result.getParcelable(RuntimeKeys.FILE_DESCRIPTOR);
        if (descriptor == null) throw new FileNotFoundException("Provider returned no file descriptor");
        return descriptor;
    }

    @Override public AssetFileDescriptor openAssetFile(Uri uri, String mode)
            throws FileNotFoundException {
        Bundle request = request(ComponentOperations.PROVIDER_OPEN_ASSET_FILE, uri);
        request.putString(RuntimeKeys.PROVIDER_FILE_MODE, required(mode, "mode"));
        Bundle result = bridge.invokeComponent(request);
        AssetFileDescriptor descriptor = result.getParcelable(RuntimeKeys.ASSET_FILE_DESCRIPTOR);
        if (descriptor == null) throw new FileNotFoundException("Provider returned no asset descriptor");
        return descriptor;
    }

    @Override public AssetFileDescriptor openTypedAssetFile(Uri uri, String mimeType, Bundle options)
            throws FileNotFoundException {
        Bundle request = request(ComponentOperations.PROVIDER_OPEN_TYPED_ASSET_FILE, uri);
        request.putString(RuntimeKeys.PROVIDER_MIME_TYPE, required(mimeType, "mimeType"));
        if (options != null) {
            request.putBundle(RuntimeKeys.PROVIDER_FILE_OPTIONS, new Bundle(options));
        }
        Bundle result = bridge.invokeComponent(request);
        AssetFileDescriptor descriptor = result.getParcelable(RuntimeKeys.ASSET_FILE_DESCRIPTOR);
        if (descriptor == null) throw new FileNotFoundException("Provider returned no typed asset descriptor");
        return descriptor;
    }

    private Bundle request(String operation, Uri uri) {
        Bundle request = bridge.baseRequest();
        request.putString(ComponentOperations.OPERATION, operation);
        request.putString(ComponentOperations.AUTHORITY, authority);
        request.putString(RuntimeKeys.COMPONENT_CLASS, componentClass);
        request.putString(RuntimeKeys.TARGET_PACKAGE_NAME, spec.packageName);
        request.putInt(RuntimeKeys.TARGET_VIRTUAL_USER_ID, spec.virtualUserId);
        if (uri != null) request.putString(RuntimeKeys.URI, uri.toString());
        return request;
    }

    private boolean providerExported() {
        for (com.warden.controlledsandbox.contract.VirtualComponentSnapshot component
                : spec.packageState.components()) {
            if ("PROVIDER".equals(component.type())
                    && componentClass.equals(component.className())) return component.exported();
        }
        return false;
    }

    /** Framework Cursor facade backed by the Broker's ordered page lease. */
    private static final class RemoteCursor extends AbstractCursor {
        private final GuestRuntimeBrokerBridge bridge;
        private final Bundle queryRequest;
        private final Bundle pageRequest;
        private String[] columns;
        private int totalRows;
        private String token;
        private Bundle extras;
        private Uri notificationUri;
        private final ArrayList<Object[]> rows = new ArrayList<>();
        private int nextOffset;
        private long nextSequence;
        private boolean endReached;
        private boolean closed;

        RemoteCursor(GuestRuntimeBrokerBridge bridge, Bundle queryRequest, Bundle pageRequest,
                     String[] columns,
                     int totalRows, String token, Bundle firstPage) {
            this.bridge = java.util.Objects.requireNonNull(bridge, "bridge");
            this.queryRequest = new Bundle(queryRequest);
            this.pageRequest = new Bundle(pageRequest);
            this.columns = columns.clone();
            this.totalRows = totalRows;
            this.token = token;
            append(firstPage);
        }

        @Override public int getCount() { return totalRows; }
        @Override public String[] getColumnNames() { return columns.clone(); }
        @Override public boolean onMove(int oldPosition, int newPosition) {
            if (closed) return false;
            ensureLoaded(newPosition);
            return newPosition < rows.size();
        }
        @Override public String getString(int column) {
            Object value = value(column);
            if (value == null) return null;
            if (value instanceof byte[]) return new String((byte[]) value,
                    java.nio.charset.StandardCharsets.UTF_8);
            return String.valueOf(value);
        }
        @Override public short getShort(int column) { return (short) getLong(column); }
        @Override public int getInt(int column) { return (int) getLong(column); }
        @Override public long getLong(int column) {
            Object value = value(column);
            return value instanceof Number ? ((Number) value).longValue()
                    : Long.parseLong(String.valueOf(value));
        }
        @Override public float getFloat(int column) { return (float) getDouble(column); }
        @Override public double getDouble(int column) {
            Object value = value(column);
            return value instanceof Number ? ((Number) value).doubleValue()
                    : Double.parseDouble(String.valueOf(value));
        }
        @Override public byte[] getBlob(int column) {
            Object value = value(column);
            return value instanceof byte[] ? (byte[]) value : null;
        }
        @Override public boolean isNull(int column) { return value(column) == null; }
        @Override public int getType(int column) {
            Object value = value(column);
            if (value == null) return FIELD_TYPE_NULL;
            if (value instanceof byte[]) return FIELD_TYPE_BLOB;
            if (value instanceof Float || value instanceof Double) return FIELD_TYPE_FLOAT;
            if (value instanceof Number) return FIELD_TYPE_INTEGER;
            return FIELD_TYPE_STRING;
        }
        @Override public void copyStringToBuffer(int column, CharArrayBuffer buffer) {
            String value = getString(column);
            if (value == null) { buffer.sizeCopied = 0; return; }
            char[] chars = value.toCharArray();
            if (buffer.data == null || buffer.data.length < chars.length) buffer.data = new char[chars.length];
            System.arraycopy(chars, 0, buffer.data, 0, chars.length);
            buffer.sizeCopied = chars.length;
        }
        @Override public Bundle getExtras() {
            return extras == null ? Bundle.EMPTY : new Bundle(extras);
        }
        public Uri getNotificationUri() { return notificationUri; }

        @Override public boolean requery() {
            if (closed) return false;
            Bundle close = new Bundle(pageRequest);
            close.putString(ComponentOperations.OPERATION, ComponentOperations.PROVIDER_CURSOR_CLOSE);
            close.putString(RuntimeKeys.CURSOR_TOKEN, token);
            try { bridge.invokeComponent(close); } catch (RuntimeException ignored) { }
            Bundle page = bridge.invokeComponent(queryRequest);
            ArrayList<String> freshColumns = page.getStringArrayList(RuntimeKeys.CURSOR_COLUMNS);
            columns = freshColumns == null ? new String[0]
                    : freshColumns.toArray(new String[0]);
            totalRows = Math.max(0, page.getInt(RuntimeKeys.CURSOR_TOTAL_ROWS, 0));
            token = required(page, RuntimeKeys.CURSOR_TOKEN);
            rows.clear();
            nextOffset = 0;
            nextSequence = 0;
            endReached = false;
            extras = null;
            notificationUri = null;
            mPos = -1;
            append(page);
            return true;
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            Bundle close = new Bundle(pageRequest);
            close.putString(ComponentOperations.OPERATION, ComponentOperations.PROVIDER_CURSOR_CLOSE);
            close.putString(RuntimeKeys.CURSOR_TOKEN, token);
            try { bridge.invokeComponent(close); } catch (RuntimeException ignored) { }
            rows.clear();
            super.close();
        }

        private Object value(int column) {
            checkPosition();
            if (column < 0 || column >= columns.length) {
                throw new android.database.CursorIndexOutOfBoundsException(column, columns.length);
            }
            return rows.get(getPosition())[column];
        }

        private void ensureLoaded(int position) {
            if (position < 0 || position >= totalRows) return;
            while (rows.size() <= position && !endReached) {
                Bundle request = new Bundle(pageRequest);
                request.putString(RuntimeKeys.CURSOR_TOKEN, token);
                request.putInt(RuntimeKeys.CURSOR_OFFSET, nextOffset);
                request.putLong(RuntimeKeys.CURSOR_PAGE_SEQUENCE, nextSequence);
                request.putInt(RuntimeKeys.CURSOR_PAGE_SIZE, PAGE_SIZE);
                append(bridge.invokeComponent(request));
            }
        }

        private void append(Bundle page) {
            Bundle pageExtras = page.getBundle(RuntimeKeys.CURSOR_EXTRAS);
            if (pageExtras != null) extras = new Bundle(pageExtras);
            String notification = page.getString(RuntimeKeys.CURSOR_NOTIFICATION_URI, "");
            if (!notification.isEmpty()) notificationUri = Uri.parse(notification);
            int count = page.getInt(RuntimeKeys.CURSOR_ROWS_RETURNED, 0);
            for (int rowIndex = 0; rowIndex < count; rowIndex++) {
                ArrayList<String> wire = page.getStringArrayList(RuntimeKeys.CURSOR_ROW_PREFIX + rowIndex);
                Object[] row = new Object[columns.length];
                for (int column = 0; column < row.length; column++) {
                    row[column] = wire == null || column >= wire.size()
                            ? null : CursorWireCodec.decode(wire.get(column));
                }
                rows.add(row);
            }
            nextOffset = page.getInt(RuntimeKeys.CURSOR_NEXT_OFFSET, rows.size());
            nextSequence = page.getLong(RuntimeKeys.CURSOR_NEXT_SEQUENCE, nextSequence);
            endReached = page.getBoolean(RuntimeKeys.CURSOR_END_REACHED, nextOffset >= totalRows);
            if (count == 0 && !endReached) throw new IllegalStateException("PROVIDER_CURSOR_EMPTY_PAGE");
        }
    }

    private static Bundle values(Object rawValues) {
        Bundle out = new Bundle();
        if (rawValues == null) return out;
        if (rawValues instanceof ContentValues contentValues) {
            for (String key : contentValues.keySet()) {
                putValue(out, key, contentValues.get(key));
            }
            return out;
        }
        if (!(rawValues instanceof java.util.Map<?, ?> values)) {
            throw new IllegalArgumentException("Unsupported ContentValues backing type: "
                    + rawValues.getClass().getName());
        }
        for (java.util.Map.Entry<?, ?> entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("ContentValues key must be String");
            }
            putValue(out, key, entry.getValue());
        }
        return out;
    }

    private static void putValue(Bundle out, String key, Object value) {
            if (value == null) out.putString(key, null);
            else if (value instanceof String) out.putString(key, (String) value);
            else if (value instanceof Byte) out.putInt(key, ((Byte) value).intValue());
            else if (value instanceof Short) out.putInt(key, ((Short) value).intValue());
            else if (value instanceof Integer) out.putInt(key, (Integer) value);
            else if (value instanceof Long) out.putLong(key, (Long) value);
            else if (value instanceof Boolean) out.putBoolean(key, (Boolean) value);
            else if (value instanceof Float) out.putFloat(key, (Float) value);
            else if (value instanceof Double) out.putDouble(key, (Double) value);
            else if (value instanceof byte[]) out.putByteArray(key, (byte[]) value);
            else throw new IllegalArgumentException("Unsupported ContentValues type for " + key);
    }

    private static ArrayList<String> strings(String[] values) {
        if (values == null) return null;
        ArrayList<String> out = new ArrayList<>();
        java.util.Collections.addAll(out, values);
        return out;
    }

    private static String required(Bundle bundle, String key) {
        return required(bundle.getString(key, ""), key);
    }
    private static String required(String value, String name) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " is required");
        return normalized;
    }
}
