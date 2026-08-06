package com.warden.controlledsandbox.runtime.guest;

import android.content.ContentProvider;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentValues;
import android.content.OperationApplicationException;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import com.warden.controlledsandbox.runtime.provider.CursorWireCodec;
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
        MatrixCursor cursor = new MatrixCursor(
                columns == null ? new String[0] : columns.toArray(new String[0]),
                Math.max(0, page.getInt(RuntimeKeys.CURSOR_TOTAL_ROWS, 0)));
        try {
            appendRows(cursor, page);
            while (!page.getBoolean(RuntimeKeys.CURSOR_END_REACHED, true)) {
                Bundle next = request(ComponentOperations.PROVIDER_CURSOR_PAGE, null);
                next.putString(RuntimeKeys.CURSOR_TOKEN, token);
                next.putInt(RuntimeKeys.CURSOR_OFFSET,
                        page.getInt(RuntimeKeys.CURSOR_NEXT_OFFSET, cursor.getCount()));
                next.putLong(RuntimeKeys.CURSOR_PAGE_SEQUENCE,
                        page.getLong(RuntimeKeys.CURSOR_NEXT_SEQUENCE, 0L));
                next.putInt(RuntimeKeys.CURSOR_PAGE_SIZE, PAGE_SIZE);
                page = bridge.invokeComponent(next);
                appendRows(cursor, page);
            }
            return cursor;
        } finally {
            Bundle close = request(ComponentOperations.PROVIDER_CURSOR_CLOSE, null);
            close.putString(RuntimeKeys.CURSOR_TOKEN, token);
            try { bridge.invokeComponent(close); } catch (RuntimeException ignored) { }
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
        int inserted = 0;
        for (ContentValues value : values) if (insert(uri, value) != null) inserted++;
        return inserted;
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
        // Android's default implementation preserves operation ordering and back-reference behavior.
        // Each primitive operation is still routed through the Broker and remains authorization checked.
        return super.applyBatch(operations);
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

    private static void appendRows(MatrixCursor cursor, Bundle page) {
        int count = page.getInt(RuntimeKeys.CURSOR_ROWS_RETURNED, 0);
        for (int rowIndex = 0; rowIndex < count; rowIndex++) {
            ArrayList<String> wire = page.getStringArrayList(RuntimeKeys.CURSOR_ROW_PREFIX + rowIndex);
            Object[] row = new Object[wire == null ? 0 : wire.size()];
            for (int column = 0; column < row.length; column++) {
                row[column] = CursorWireCodec.decode(wire.get(column));
            }
            cursor.addRow(row);
        }
    }

    private static Bundle values(ContentValues values) {
        Bundle out = new Bundle();
        if (values == null) return out;
        for (String key : values.keySet()) {
            Object value = values.get(key);
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
        return out;
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
