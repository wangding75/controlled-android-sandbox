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
import android.os.CancellationSignal;
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
    private final String targetPackageName;
    private final int targetVirtualUserId;
    private final String targetProcessName;
    private final String authority;
    private final String componentClass;
    private final boolean exported;

    GuestBrokerContentProvider(GuestContext context, GuestPackageSpec spec,
                              String targetPackageName, int targetVirtualUserId,
                              String targetProcessName, String authority, String componentClass,
                              boolean exported) {
        java.util.Objects.requireNonNull(context, "context");
        this.spec = java.util.Objects.requireNonNull(spec, "spec");
        this.bridge = new GuestRuntimeBrokerBridge(spec, context.mainThread);
        this.targetPackageName = required(targetPackageName, "targetPackageName");
        if (targetVirtualUserId < 0) throw new IllegalArgumentException(
                "targetVirtualUserId must be non-negative");
        this.targetVirtualUserId = targetVirtualUserId;
        this.targetProcessName = required(targetProcessName, "targetProcessName");
        this.authority = required(authority, "authority");
        this.componentClass = required(componentClass, "componentClass");
        this.exported = exported;
    }

    @Override public boolean onCreate() { return true; }

    void prepare() {
        Bundle request = request(ComponentOperations.PREPARE_PROVIDER, null);
        request.putBoolean(RuntimeKeys.PROVIDER_EXPORTED, providerExported());
        bridge.invokeComponent(request);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        return queryInternal(uri, projection, null, selection, selectionArgs, sortOrder, null);
    }

    /**
     * Keep the API 26+ query path intact.  Calling the legacy overload here would silently
     * discard QUERY_ARG_SQL_LIMIT / sort arguments for providers that implement only the modern
     * entry point, and would leave CancellationSignal disconnected from the remote cursor lease.
     */
    // Do not annotate this overload: the compact API stubs used by the static verifier model
    // only the API 1 signature, while Android 26+ ContentProvider.Transport dispatches this
    // exact method at runtime.
    public Cursor query(Uri uri, String[] projection, Bundle queryArgs,
            CancellationSignal cancellationSignal) {
        return queryInternal(uri, projection, queryArgs, null, null, null, cancellationSignal);
    }

    private Cursor queryInternal(Uri uri, String[] projection, Bundle queryArgs,
            String selection, String[] selectionArgs, String sortOrder,
            CancellationSignal cancellationSignal) {
        if (cancellationSignal != null && cancellationSignal.isCanceled()) {
            cancellationSignal.throwIfCanceled();
            throw new IllegalStateException("PROVIDER_QUERY_CANCELLATION_UNAVAILABLE");
        }
        Bundle request = request(ComponentOperations.PROVIDER_QUERY, uri);
        String queryId = cancellationSignal == null ? ""
                : java.util.UUID.randomUUID().toString();
        if (!queryId.isEmpty()) request.putString(RuntimeKeys.PROVIDER_QUERY_ID, queryId);
        request.putStringArrayList(RuntimeKeys.PROVIDER_PROJECTION, strings(projection));
        request.putString(RuntimeKeys.PROVIDER_SELECTION, selection);
        request.putStringArrayList(RuntimeKeys.PROVIDER_SELECTION_ARGS, strings(selectionArgs));
        request.putString(RuntimeKeys.PROVIDER_SORT_ORDER, sortOrder);
        if (queryArgs != null) request.putBundle(RuntimeKeys.PROVIDER_QUERY_ARGS,
                boundedQueryArgs(queryArgs));
        request.putInt(RuntimeKeys.CURSOR_PAGE_SIZE, PAGE_SIZE);
        InFlightQueryCancellation inFlight = cancellationSignal == null ? null
                : new InFlightQueryCancellation(bridge, request, queryId);
        if (inFlight != null) cancellationSignal.setOnCancelListener(inFlight);
        Bundle page;
        try {
            page = bridge.invokeComponent(request);
        } catch (RuntimeException error) {
            if (inFlight != null) cancellationSignal.setOnCancelListener(null);
            throw error;
        }
        String token = required(page, RuntimeKeys.CURSOR_TOKEN);
        ArrayList<String> columns = page.getStringArrayList(RuntimeKeys.CURSOR_COLUMNS);
        try {
            if (inFlight != null) inFlight.complete(token);
            if (cancellationSignal != null && cancellationSignal.isCanceled()) {
                cancellationSignal.throwIfCanceled();
                throw new IllegalStateException("PROVIDER_QUERY_CANCELLATION_UNAVAILABLE");
            }
            return new RemoteCursor(bridge, request, request(ComponentOperations.PROVIDER_CURSOR_PAGE, null),
                    columns == null ? new String[0] : columns.toArray(new String[0]),
                    Math.max(0, page.getInt(RuntimeKeys.CURSOR_TOTAL_ROWS, 0)), token, page,
                    cancellationSignal);
        } catch (Throwable error) {
            Bundle close = request(ComponentOperations.PROVIDER_CURSOR_CLOSE, null);
            close.putString(RuntimeKeys.CURSOR_TOKEN, token);
            try { bridge.invokeComponent(close); } catch (RuntimeException ignored) { }
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw error instanceof RuntimeException ? (RuntimeException) error
                    : new IllegalStateException("PROVIDER_CURSOR_CREATE_FAILED", error);
        }
    }

    /** Sends cancellation while Broker/Provider.query() is still blocked. */
    private static final class InFlightQueryCancellation
            implements CancellationSignal.OnCancelListener {
        private final GuestRuntimeBrokerBridge bridge;
        private final Bundle request;
        private final String queryId;
        private final boolean cancelCursorAfterCompletion;
        private boolean completed;
        private String cursorToken;

        private InFlightQueryCancellation(GuestRuntimeBrokerBridge bridge, Bundle request,
                                          String queryId) {
            this(bridge, request, queryId, true);
        }

        private InFlightQueryCancellation(GuestRuntimeBrokerBridge bridge, Bundle request,
                                          String queryId, boolean cancelCursorAfterCompletion) {
            this.bridge = bridge;
            this.request = new Bundle(request);
            this.queryId = queryId;
            this.cancelCursorAfterCompletion = cancelCursorAfterCompletion;
        }

        @Override public synchronized void onCancel() {
            if (completed) {
                if (cancelCursorAfterCompletion
                        && cursorToken != null && !cursorToken.isEmpty()) {
                    sendCursorCancel(cursorToken);
                }
                return;
            }
            Bundle cancel = new Bundle(request);
            cancel.putString(ComponentOperations.OPERATION,
                    ComponentOperations.PROVIDER_QUERY_CANCEL);
            cancel.putString(RuntimeKeys.PROVIDER_QUERY_ID, queryId);
            try { bridge.invokeComponentAsync(cancel, ignored -> { }, ignored -> { }); }
            catch (RuntimeException ignored) { }
        }

        private synchronized void complete(String token) {
            completed = true;
            cursorToken = token;
        }

        private void sendCursorCancel(String token) {
            Bundle cancel = new Bundle(request);
            cancel.putString(ComponentOperations.OPERATION,
                    ComponentOperations.PROVIDER_CURSOR_CANCEL);
            cancel.putString(RuntimeKeys.CURSOR_TOKEN, token);
            try { bridge.invokeComponentAsync(cancel, ignored -> { }, ignored -> { }); }
            catch (RuntimeException ignored) { }
        }
    }

    /**
     * Query arguments are an application-controlled Bundle.  Only the scalar/array shapes used
     * by ContentResolver are allowed across the Guest/Broker boundary; arbitrary Parcelables and
     * Binder objects would defeat the transport's object-ownership invariant.
     */
    private static Bundle boundedQueryArgs(Bundle source) {
        Bundle out = new Bundle();
        if (source == null) return out;
        if (source.size() > 32) throw new IllegalArgumentException("PROVIDER_QUERY_ARGS_LIMIT_EXCEEDED");
        for (String key : source.keySet()) {
            if (key == null || key.length() > 256) {
                throw new IllegalArgumentException("PROVIDER_QUERY_ARG_KEY_INVALID");
            }
            Object value = source.get(key);
            if (value == null) {
                out.putString(key, null);
            } else if (value instanceof String string) {
                putQueryString(out, key, string);
            } else if (value instanceof String[] strings) {
                if (strings.length > 1024) {
                    throw new IllegalArgumentException("PROVIDER_QUERY_ARG_ARRAY_LIMIT_EXCEEDED");
                }
                for (String item : strings) putQueryStringValue(item);
                out.putStringArray(key, strings.clone());
            } else if (value instanceof Integer integer) {
                out.putInt(key, integer);
            } else if (value instanceof Long longValue) {
                out.putLong(key, longValue);
            } else if (value instanceof Boolean booleanValue) {
                out.putBoolean(key, booleanValue);
            } else if (value instanceof Float floatValue) {
                out.putFloat(key, floatValue);
            } else if (value instanceof Double doubleValue) {
                out.putDouble(key, doubleValue);
            } else if (value instanceof ArrayList<?> list) {
                if (list.size() > 1024) {
                    throw new IllegalArgumentException("PROVIDER_QUERY_ARG_LIST_LIMIT_EXCEEDED");
                }
                ArrayList<String> strings = new ArrayList<>(list.size());
                for (Object item : list) {
                    if (!(item instanceof String string)) {
                        throw new IllegalArgumentException("PROVIDER_QUERY_ARG_LIST_TYPE_INVALID");
                    }
                    putQueryStringValue(string);
                    strings.add(string);
                }
                out.putStringArrayList(key, strings);
            } else {
                throw new IllegalArgumentException("PROVIDER_QUERY_ARG_TYPE_UNSUPPORTED:" + key);
            }
        }
        return out;
    }

    private static void putQueryString(Bundle out, String key, String value) {
        putQueryStringValue(value);
        out.putString(key, value);
    }

    private static void putQueryStringValue(String value) {
        if (value != null && value.length() > 64 * 1024) {
            throw new IllegalArgumentException("PROVIDER_QUERY_ARG_STRING_LIMIT_EXCEEDED");
        }
    }

    @Override public String getType(Uri uri) {
        Bundle result = bridge.invokeComponent(request(ComponentOperations.PROVIDER_GET_TYPE, uri));
        return result.getString("mimeType");
    }

    // These overloads are deliberately kept public without @Override so the API 26+ transport
    // is available while the repository's compact API stubs remain usable for static checks.
    public String getTypeAnonymous(Uri uri) {
        Bundle result = bridge.invokeComponent(
                request(ComponentOperations.PROVIDER_GET_TYPE_ANONYMOUS, uri));
        return result.getString("mimeType");
    }

    public String[] getStreamTypes(Uri uri, String mimeTypeFilter) {
        Bundle request = request(ComponentOperations.PROVIDER_GET_STREAM_TYPES, uri);
        request.putString(RuntimeKeys.PROVIDER_MIME_TYPE, required(mimeTypeFilter, "mimeTypeFilter"));
        Bundle result = bridge.invokeComponent(request);
        return stringArray(result, RuntimeKeys.PROVIDER_STREAM_TYPES);
    }

    public Uri canonicalize(Uri uri) {
        Bundle result = bridge.invokeComponent(
                request(ComponentOperations.PROVIDER_CANONICALIZE, uri));
        String value = result.getString(RuntimeKeys.URI, "");
        return value.isEmpty() ? null : Uri.parse(value);
    }

    public Uri uncanonicalize(Uri uri) {
        Bundle result = bridge.invokeComponent(
                request(ComponentOperations.PROVIDER_UNCANONICALIZE, uri));
        String value = result.getString(RuntimeKeys.URI, "");
        return value.isEmpty() ? null : Uri.parse(value);
    }

    public boolean refresh(Uri uri, Bundle args, CancellationSignal cancellationSignal) {
        if (cancellationSignal != null && cancellationSignal.isCanceled()) {
            cancellationSignal.throwIfCanceled();
            throw new IllegalStateException("PROVIDER_REFRESH_CANCELLATION_UNAVAILABLE");
        }
        Bundle request = request(ComponentOperations.PROVIDER_REFRESH, uri);
        String refreshId = cancellationSignal == null ? ""
                : java.util.UUID.randomUUID().toString();
        if (!refreshId.isEmpty()) request.putString(RuntimeKeys.PROVIDER_QUERY_ID, refreshId);
        if (args != null) request.putBundle(RuntimeKeys.PROVIDER_EXTRAS, boundedQueryArgs(args));
        InFlightQueryCancellation inFlight = cancellationSignal == null ? null
                : new InFlightQueryCancellation(bridge, request, refreshId, false);
        if (inFlight != null) cancellationSignal.setOnCancelListener(inFlight);
        Bundle result;
        try {
            result = bridge.invokeComponent(request);
            if (inFlight != null) inFlight.complete("");
        } catch (RuntimeException error) {
            if (inFlight != null) cancellationSignal.setOnCancelListener(null);
            throw error;
        } finally {
            if (inFlight != null) cancellationSignal.setOnCancelListener(null);
        }
        if (cancellationSignal != null && cancellationSignal.isCanceled()) {
            cancellationSignal.throwIfCanceled();
            throw new IllegalStateException("PROVIDER_REFRESH_CANCELLATION_UNAVAILABLE");
        }
        return result.getBoolean("refreshed", false);
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
            // call() is provider-defined and may legitimately contain nested Bundles and
            // primitive arrays.  Use the call-specific codec rather than the narrower query
            // argument projection, while still rejecting Binder/Guest-owned object handles.
            request.putBundle(RuntimeKeys.PROVIDER_EXTRAS, ProviderCallBundleCodec.copy(extras));
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
            String exceptionType = item.getString(
                    RuntimeKeys.PROVIDER_BATCH_RESULT_EXCEPTION_TYPE, "");
            if (!exceptionType.isEmpty()) {
                out[index] = resultFromException(exceptionType,
                        item.getString(RuntimeKeys.PROVIDER_BATCH_RESULT_EXCEPTION_MESSAGE, ""));
                continue;
            }
            String uri = item.getString(RuntimeKeys.URI, "");
            if (!uri.isEmpty()) {
                out[index] = new ContentProviderResult(Uri.parse(uri));
            } else if (item.containsKey(RuntimeKeys.PROVIDER_BATCH_AFFECTED_ROWS)) {
                out[index] = new ContentProviderResult(
                        item.getInt(RuntimeKeys.PROVIDER_BATCH_AFFECTED_ROWS, 0));
            } else if (item.containsKey(RuntimeKeys.PROVIDER_BATCH_RESULT_EXTRAS)) {
                Bundle extras = item.getBundle(RuntimeKeys.PROVIDER_BATCH_RESULT_EXTRAS);
                out[index] = resultFromExtras(extras == null ? new Bundle() : extras);
            } else {
                out[index] = new ContentProviderResult(0);
            }
        }
        return out;
    }

    private static Bundle encodeBatchOperation(ContentProviderOperation operation, int index)
            throws OperationApplicationException {
        Bundle wire = new Bundle();
        wire.putString(RuntimeKeys.URI, operation.getUri().toString());
        wire.putBoolean(RuntimeKeys.PROVIDER_BATCH_YIELD_ALLOWED,
                booleanMethod(operation, "isYieldAllowed"));
        wire.putBoolean(RuntimeKeys.PROVIDER_BATCH_EXCEPTION_ALLOWED,
                booleanMethod(operation, "isExceptionAllowed"));
        if (booleanMethod(operation, "isCall")) {
            wire.putString(RuntimeKeys.PROVIDER_BATCH_TYPE, "CALL");
            Object method = field(operation, "mMethod");
            if (method != null) wire.putString(RuntimeKeys.PROVIDER_METHOD, String.valueOf(method));
            Object argument = field(operation, "mArg");
            if (argument != null) wire.putString(RuntimeKeys.PROVIDER_ARGUMENT, String.valueOf(argument));
            // Android stores call extras in an ArrayMap<String,Object>, not a Bundle.  Use the
            // same bounded call codec as direct ContentProvider.call(); the ContentValues
            // projection is intentionally scalar-only and loses provider-defined arguments.
            Object extras = field(operation, "mExtras");
            if (extras != null) {
                putCallExtras(wire, extras, index);
            }
            putBackReferences(wire, RuntimeKeys.PROVIDER_BATCH_EXTRAS_BACK_REFERENCES,
                    field(operation, "mExtrasBackReferences"), index, true);
        } else if (operation.isInsert()) {
            wire.putString(RuntimeKeys.PROVIDER_BATCH_TYPE, "INSERT");
            putProjectedValues(wire, field(operation, "mValues"),
                    RuntimeKeys.PROVIDER_VALUES,
                    RuntimeKeys.PROVIDER_BATCH_VALUES_BACK_REFERENCES,
                    RuntimeKeys.PROVIDER_BATCH_VALUES_BACK_REFERENCE_SOURCES,
                    index);
            putBackReferences(wire, RuntimeKeys.PROVIDER_BATCH_VALUES_BACK_REFERENCES,
                    field(operation, "mValuesBackReferences"), index, true);
        } else if (operation.isUpdate()) {
            wire.putString(RuntimeKeys.PROVIDER_BATCH_TYPE, "UPDATE");
            putProjectedValues(wire, field(operation, "mValues"),
                    RuntimeKeys.PROVIDER_VALUES,
                    RuntimeKeys.PROVIDER_BATCH_VALUES_BACK_REFERENCES,
                    RuntimeKeys.PROVIDER_BATCH_VALUES_BACK_REFERENCE_SOURCES,
                    index);
            putSelection(wire, operation, index);
            putBackReferences(wire, RuntimeKeys.PROVIDER_BATCH_VALUES_BACK_REFERENCES,
                    field(operation, "mValuesBackReferences"), index, true);
            putBackReferences(wire, RuntimeKeys.PROVIDER_BATCH_SELECTION_BACK_REFERENCES,
                    field(operation, "mSelectionArgsBackReferences"), index, false);
        } else if (operation.isDelete()) {
            wire.putString(RuntimeKeys.PROVIDER_BATCH_TYPE, "DELETE");
            putSelection(wire, operation, index);
            putBackReferences(wire, RuntimeKeys.PROVIDER_BATCH_SELECTION_BACK_REFERENCES,
                    field(operation, "mSelectionArgsBackReferences"), index, false);
        } else if (operation.isAssertQuery()) {
            wire.putString(RuntimeKeys.PROVIDER_BATCH_TYPE, "ASSERT");
            putProjectedValues(wire, field(operation, "mValues"),
                    RuntimeKeys.PROVIDER_VALUES,
                    RuntimeKeys.PROVIDER_BATCH_VALUES_BACK_REFERENCES,
                    RuntimeKeys.PROVIDER_BATCH_VALUES_BACK_REFERENCE_SOURCES,
                    index);
            putSelection(wire, operation, index);
            putBackReferences(wire, RuntimeKeys.PROVIDER_BATCH_VALUES_BACK_REFERENCES,
                    field(operation, "mValuesBackReferences"), index, true);
            putBackReferences(wire, RuntimeKeys.PROVIDER_BATCH_SELECTION_BACK_REFERENCES,
                    field(operation, "mSelectionArgsBackReferences"), index, false);
            Object expected = field(operation, "mExpectedCount");
            wire.putInt(RuntimeKeys.PROVIDER_BATCH_EXPECTED_COUNT,
                    expected instanceof Number ? ((Number) expected).intValue() : -1);
        } else {
            throw new OperationApplicationException(
                    "PROVIDER_BATCH_OPERATION_UNSUPPORTED:" + index);
        }
        return wire;
    }

    /** API 30 added isCall()/isExceptionAllowed(); reflection keeps API 28 guests loadable. */
    private static boolean booleanMethod(Object target, String name) {
        try {
            java.lang.reflect.Method method = target.getClass().getMethod(name);
            Object value = method.invoke(target);
            return value instanceof Boolean && (Boolean) value;
        } catch (NoSuchMethodException ignored) {
            return false;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw new IllegalStateException("PROVIDER_BATCH_OPERATION_METHOD_UNAVAILABLE:" + name,
                    error);
        }
    }

    /**
     * Preserve ContentProviderOperation's two back-reference maps across the Binder wire.
     * Android uses a Map for values and a SparseArray/Map depending on the platform release;
     * accepting both keeps the transport semantic rather than API-shape dependent.
     */
    private static void putBackReferences(Bundle wire, String key, Object raw, int operationIndex,
                                          boolean valueKeys) throws OperationApplicationException {
        Bundle encoded = new Bundle();
        if (raw == null) {
            return;
        }
        try {
            if (raw instanceof java.util.Map<?, ?> map) {
                for (java.util.Map.Entry<?, ?> entry : map.entrySet()) {
                    addBackReference(encoded, entry.getKey(), entry.getValue(), operationIndex,
                            valueKeys);
                }
            } else {
                java.lang.reflect.Method size = findMethod(raw.getClass(), "size");
                java.lang.reflect.Method keyAt = findMethod(raw.getClass(), "keyAt");
                java.lang.reflect.Method valueAt = findMethod(raw.getClass(), "valueAt");
                if (size == null || keyAt == null || valueAt == null) {
                    throw new IllegalArgumentException("back-reference container shape unsupported");
                }
                int count = ((Number) size.invoke(raw)).intValue();
                for (int entryIndex = 0; entryIndex < count; entryIndex++) {
                    addBackReference(encoded, keyAt.invoke(raw, entryIndex),
                            valueAt.invoke(raw, entryIndex), operationIndex, valueKeys);
                }
            }
        } catch (OperationApplicationException error) {
            throw error;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw new OperationApplicationException("PROVIDER_BATCH_BACK_REFERENCES_UNREADABLE:"
                    + operationIndex);
        }
        Bundle existing = wire.getBundle(key);
        if (existing != null) {
            for (String entry : existing.keySet()) {
                if (!encoded.containsKey(entry)) encoded.putInt(entry, existing.getInt(entry));
            }
        }
        if (!encoded.isEmpty()) wire.putBundle(key, encoded);
    }

    private static void addBackReference(Bundle encoded, Object rawKey, Object rawValue,
                                         int operationIndex, boolean valueKeys)
            throws OperationApplicationException {
        if (!(rawValue instanceof Number)) {
            throw new OperationApplicationException("PROVIDER_BATCH_BACK_REFERENCE_INDEX_INVALID:"
                    + operationIndex);
        }
        int previousIndex = ((Number) rawValue).intValue();
        if (previousIndex < 0 || previousIndex >= operationIndex) {
            throw new OperationApplicationException("PROVIDER_BATCH_BACK_REFERENCE_ORDER_INVALID:"
                    + operationIndex);
        }
        final String key;
        if (valueKeys) {
            if (!(rawKey instanceof String) || ((String) rawKey).isEmpty()) {
                throw new OperationApplicationException("PROVIDER_BATCH_VALUE_BACK_REFERENCE_KEY_INVALID:"
                        + operationIndex);
            }
            key = (String) rawKey;
        } else if (rawKey instanceof Number) {
            int selectionIndex = ((Number) rawKey).intValue();
            if (selectionIndex < 0) {
                throw new OperationApplicationException("PROVIDER_BATCH_SELECTION_BACK_REFERENCE_KEY_INVALID:"
                        + operationIndex);
            }
            key = Integer.toString(selectionIndex);
        } else {
            try {
                int selectionIndex = Integer.parseInt(String.valueOf(rawKey));
                if (selectionIndex < 0) throw new NumberFormatException();
                key = Integer.toString(selectionIndex);
            } catch (NumberFormatException error) {
                throw new OperationApplicationException(
                        "PROVIDER_BATCH_SELECTION_BACK_REFERENCE_KEY_INVALID:" + operationIndex);
            }
        }
        encoded.putInt(key, previousIndex);
    }

    private static java.lang.reflect.Method findMethod(Class<?> type, String name) {
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            try {
                java.lang.reflect.Method method = cursor.getDeclaredMethod(name);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) { }
        }
        return null;
    }

    private static void putSelection(Bundle wire, ContentProviderOperation operation, int index)
            throws OperationApplicationException {
        wire.putString(RuntimeKeys.PROVIDER_SELECTION, (String) field(operation, "mSelection"));
        Object rawArgs = field(operation, "mSelectionArgs");
        if (rawArgs == null) return;
        ArrayList<String> values = new ArrayList<>();
        Bundle references = new Bundle();
        Bundle sources = new Bundle();
        if (rawArgs instanceof String[] args) {
            java.util.Collections.addAll(values, args);
        } else {
            java.lang.reflect.Method size = findMethod(rawArgs.getClass(), "size");
            java.lang.reflect.Method keyAt = findMethod(rawArgs.getClass(), "keyAt");
            java.lang.reflect.Method valueAt = findMethod(rawArgs.getClass(), "valueAt");
            if (size == null || keyAt == null || valueAt == null) {
                throw new OperationApplicationException(
                        "PROVIDER_BATCH_SELECTION_ARGS_CONTAINER_UNSUPPORTED:" + index);
            }
            try {
                int count = ((Number) size.invoke(rawArgs)).intValue();
                int max = -1;
                for (int entry = 0; entry < count; entry++) {
                    max = Math.max(max, ((Number) keyAt.invoke(rawArgs, entry)).intValue());
                }
                for (int position = 0; position <= max; position++) values.add(null);
                for (int entry = 0; entry < count; entry++) {
                    int position = ((Number) keyAt.invoke(rawArgs, entry)).intValue();
                    Object value = valueAt.invoke(rawArgs, entry);
                    if (isBackReference(value)) {
                        addProjectedBackReference(references, sources,
                                Integer.toString(position), value, index);
                    } else if (value != null) {
                        values.set(position, String.valueOf(value));
                    }
                }
            } catch (OperationApplicationException error) {
                throw error;
            } catch (Throwable error) {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
                throw new OperationApplicationException(
                        "PROVIDER_BATCH_SELECTION_ARGS_UNREADABLE:" + index);
            }
        }
        if (!values.isEmpty()) {
            wire.putStringArrayList(RuntimeKeys.PROVIDER_SELECTION_ARGS, values);
        }
        if (!references.isEmpty()) {
            wire.putBundle(RuntimeKeys.PROVIDER_BATCH_SELECTION_BACK_REFERENCES, references);
        }
        if (!sources.isEmpty()) {
            wire.putBundle(RuntimeKeys.PROVIDER_BATCH_SELECTION_BACK_REFERENCE_SOURCES, sources);
        }
    }

    private static void putProjectedValues(Bundle wire, Object rawValues,
                                           String valuesKey, String referencesKey,
                                           String sourcesKey, int index)
            throws OperationApplicationException {
        Bundle projected = new Bundle();
        Bundle references = new Bundle();
        Bundle sources = new Bundle();
        if (rawValues != null) {
            if (rawValues instanceof ContentValues contentValues) {
                for (String key : contentValues.keySet()) {
                    putValue(projected, key, contentValues.get(key));
                }
            } else if (rawValues instanceof java.util.Map<?, ?> map) {
                for (java.util.Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        throw new OperationApplicationException(
                                "PROVIDER_BATCH_VALUES_KEY_INVALID:" + index);
                    }
                    Object value = entry.getValue();
                    if (isBackReference(value)) {
                        addProjectedBackReference(references, sources, key, value, index);
                    } else {
                        putValue(projected, key, value);
                    }
                }
            } else {
                throw new OperationApplicationException(
                        "PROVIDER_BATCH_VALUES_CONTAINER_UNSUPPORTED:" + index);
            }
        }
        wire.putBundle(valuesKey, projected);
        if (!references.isEmpty()) wire.putBundle(referencesKey, references);
        if (!sources.isEmpty()) wire.putBundle(sourcesKey, sources);
    }

    private static void putCallExtras(Bundle wire, Object rawExtras, int operationIndex)
            throws OperationApplicationException {
        java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>();
        Bundle references = new Bundle();
        Bundle sources = new Bundle();
        try {
            if (rawExtras instanceof java.util.Map<?, ?> map) {
                for (java.util.Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        throw new OperationApplicationException(
                                "PROVIDER_BATCH_CALL_EXTRAS_KEY_INVALID:" + operationIndex);
                    }
                    if (isBackReference(entry.getValue())) {
                        addProjectedBackReference(references, sources, key, entry.getValue(),
                                operationIndex);
                    } else {
                        values.put(key, entry.getValue());
                    }
                }
            } else if (rawExtras instanceof Bundle bundle) {
                for (String key : bundle.keySet()) {
                    Object value = bundle.get(key);
                    if (isBackReference(value)) {
                        addProjectedBackReference(references, sources, key, value,
                                operationIndex);
                    } else {
                        values.put(key, value);
                    }
                }
            } else {
                throw new OperationApplicationException(
                        "PROVIDER_BATCH_CALL_EXTRAS_CONTAINER_UNSUPPORTED:" + operationIndex);
            }
            wire.putBundle(RuntimeKeys.PROVIDER_BATCH_EXTRAS,
                    ProviderCallBundleCodec.copyMap(values));
            if (!references.isEmpty()) {
                wire.putBundle(RuntimeKeys.PROVIDER_BATCH_EXTRAS_BACK_REFERENCES, references);
            }
            if (!sources.isEmpty()) {
                wire.putBundle(RuntimeKeys.PROVIDER_BATCH_EXTRAS_BACK_REFERENCE_SOURCES, sources);
            }
        } catch (OperationApplicationException error) {
            throw error;
        } catch (IllegalArgumentException error) {
            throw new OperationApplicationException(
                    "PROVIDER_BATCH_CALL_EXTRAS_UNSUPPORTED:" + operationIndex + ":"
                            + String.valueOf(error.getMessage()));
        }
    }

    private static void addProjectedBackReference(Bundle references, Bundle sources,
                                                   String key, Object reference, int operationIndex)
            throws OperationApplicationException {
        int previousIndex = backReferenceIndex(reference);
        if (previousIndex < 0 || previousIndex >= operationIndex) {
            throw new OperationApplicationException(
                    "PROVIDER_BATCH_BACK_REFERENCE_ORDER_INVALID:" + operationIndex);
        }
        references.putInt(key, previousIndex);
        String source = backReferenceSource(reference);
        if (source != null) sources.putString(key, source);
    }

    private static boolean isBackReference(Object value) {
        return value != null && (value.getClass().getName().equals(
                "android.content.ContentProviderOperation$BackReference")
                || value.getClass().getSimpleName().equals("BackReference"));
    }

    private static int backReferenceIndex(Object reference)
            throws OperationApplicationException {
        Object value = objectField(reference, "fromIndex");
        if (!(value instanceof Number)) {
            throw new OperationApplicationException("PROVIDER_BATCH_BACK_REFERENCE_INDEX_INVALID");
        }
        return ((Number) value).intValue();
    }

    private static String backReferenceSource(Object reference)
            throws OperationApplicationException {
        Object value = objectField(reference, "fromKey");
        if (value == null) return null;
        if (!(value instanceof String)) {
            throw new OperationApplicationException("PROVIDER_BATCH_BACK_REFERENCE_SOURCE_INVALID");
        }
        return (String) value;
    }

    private static Object objectField(Object target, String name)
            throws OperationApplicationException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // Continue through the hidden implementation's superclass chain.
            } catch (Throwable error) {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
                throw new OperationApplicationException(
                        "PROVIDER_BATCH_BACK_REFERENCE_FIELD_UNAVAILABLE:" + name);
            }
        }
        throw new OperationApplicationException("PROVIDER_BATCH_BACK_REFERENCE_FIELD_MISSING:" + name);
    }

    private static Object field(ContentProviderOperation operation, String name) {
        String alternate = alternateOperationField(name);
        for (String candidate : alternate == null ? new String[]{name}
                : new String[]{name, alternate}) {
            Class<?> type = operation.getClass();
            while (type != null) {
                try {
                    java.lang.reflect.Field field = type.getDeclaredField(candidate);
                    field.setAccessible(true);
                    return field.get(operation);
                } catch (NoSuchFieldException ignored) {
                    type = type.getSuperclass();
                } catch (ReflectiveOperationException error) {
                    throw new IllegalStateException(
                            "PROVIDER_BATCH_OPERATION_FIELD_UNAVAILABLE:" + candidate, error);
                }
            }
        }
        return null;
    }

    /** Compact test stubs and Android releases use different private field spellings. */
    private static String alternateOperationField(String name) {
        return switch (name) {
            case "mMethod" -> "method";
            case "mArg" -> "argument";
            case "mValues" -> "values";
            case "mExtras" -> "extras";
            case "mSelection" -> "selection";
            case "mSelectionArgs" -> "args";
            case "mExpectedCount" -> "expected";
            case "mValuesBackReferences" -> "valueBackReferences";
            case "mSelectionArgsBackReferences" -> "selectionBackReferences";
            case "mExtrasBackReferences" -> "extrasBackReferences";
            default -> name.startsWith("m") && name.length() > 1
                    ? Character.toLowerCase(name.charAt(1)) + name.substring(2) : null;
        };
    }

    private static ContentProviderResult resultFromExtras(Bundle extras)
            throws OperationApplicationException {
        try {
            java.lang.reflect.Constructor<ContentProviderResult> constructor =
                    ContentProviderResult.class.getConstructor(Bundle.class);
            return constructor.newInstance(new Bundle(extras));
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw new OperationApplicationException("PROVIDER_BATCH_RESULT_EXTRAS_UNSUPPORTED");
        }
    }

    private static ContentProviderResult resultFromException(String type, String message)
            throws OperationApplicationException {
        String detail = type + (message == null || message.isEmpty() ? "" : ":" + message);
        try {
            java.lang.reflect.Constructor<ContentProviderResult> constructor =
                    ContentProviderResult.class.getConstructor(Throwable.class);
            return constructor.newInstance(new OperationApplicationException(detail));
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw new OperationApplicationException("PROVIDER_BATCH_RESULT_EXCEPTION_UNSUPPORTED:" + detail);
        }
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
            request.putBundle(RuntimeKeys.PROVIDER_FILE_OPTIONS, ProviderCallBundleCodec.copy(options));
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
        request.putString(RuntimeKeys.TARGET_PACKAGE_NAME, targetPackageName);
        request.putInt(RuntimeKeys.TARGET_VIRTUAL_USER_ID, targetVirtualUserId);
        request.putString(RuntimeKeys.PROCESS_NAME, targetProcessName);
        if (uri != null) request.putString(RuntimeKeys.URI, uri.toString());
        return request;
    }

    private boolean providerExported() {
        return exported;
    }

    /** Framework Cursor facade backed by the Broker's ordered page lease. */
    private static final class RemoteCursor extends AbstractCursor {
        private final GuestRuntimeBrokerBridge bridge;
        private final Bundle queryRequest;
        private final Bundle pageRequest;
        private final CancellationSignal cancellationSignal;
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
        private boolean remoteLeaseClosed;

        RemoteCursor(GuestRuntimeBrokerBridge bridge, Bundle queryRequest, Bundle pageRequest,
                     String[] columns,
                     int totalRows, String token, Bundle firstPage,
                     CancellationSignal cancellationSignal) {
            this.bridge = java.util.Objects.requireNonNull(bridge, "bridge");
            this.queryRequest = new Bundle(queryRequest);
            this.pageRequest = new Bundle(pageRequest);
            this.cancellationSignal = cancellationSignal;
            this.columns = columns.clone();
            this.totalRows = totalRows;
            this.token = token;
            if (cancellationSignal != null) {
                cancellationSignal.setOnCancelListener(this::cancelRemoteLease);
            }
            append(firstPage);
        }

        @Override public int getCount() { return totalRows; }
        @Override public String[] getColumnNames() { return columns.clone(); }
        @Override public boolean onMove(int oldPosition, int newPosition) {
            if (closed) return false;
            if (cancellationSignal != null && cancellationSignal.isCanceled()) {
                cancelRemoteLease();
                cancellationSignal.throwIfCanceled();
                throw new IllegalStateException("PROVIDER_CURSOR_CANCELLATION_UNAVAILABLE");
            }
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
            if (cancellationSignal != null && cancellationSignal.isCanceled()) {
                cancellationSignal.throwIfCanceled();
                throw new IllegalStateException("PROVIDER_CURSOR_CANCELLATION_UNAVAILABLE");
            }
            closeRemoteLease();
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
            remoteLeaseClosed = false;
            mPos = -1;
            append(page);
            return true;
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            if (cancellationSignal != null) cancellationSignal.setOnCancelListener(null);
            closeRemoteLease();
            rows.clear();
            super.close();
        }

        /**
         * The Android Cursor may remain usable after its remote rows have all been materialized.
         * Release only the Broker lease here; the local projection remains readable until the
         * caller explicitly closes the Cursor. This also makes clients that omit close() safe.
         */
        private synchronized void cancelRemoteLease() {
            if (remoteLeaseClosed || token == null || token.isEmpty()) return;
            remoteLeaseClosed = true;
            Bundle cancel = new Bundle(pageRequest);
            cancel.putString(ComponentOperations.OPERATION, ComponentOperations.PROVIDER_CURSOR_CANCEL);
            cancel.putString(RuntimeKeys.CURSOR_TOKEN, token);
            try { bridge.invokeComponent(cancel); } catch (RuntimeException ignored) { }
        }

        private synchronized void closeRemoteLease() {
            if (remoteLeaseClosed || token == null || token.isEmpty()) return;
            remoteLeaseClosed = true;
            Bundle close = new Bundle(pageRequest);
            close.putString(ComponentOperations.OPERATION, ComponentOperations.PROVIDER_CURSOR_CLOSE);
            close.putString(RuntimeKeys.CURSOR_TOKEN, token);
            try { bridge.invokeComponent(close); } catch (RuntimeException ignored) { }
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
            if (endReached) closeRemoteLease();
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

    private static String[] stringArray(Bundle bundle, String key) {
        try {
            java.lang.reflect.Method getter = Bundle.class.getMethod("getStringArray", String.class);
            Object value = getter.invoke(bundle, key);
            if (value instanceof String[] strings) return strings.clone();
        } catch (NoSuchMethodException ignored) {
            // The compact verifier stubs expose only ArrayList accessors.
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("PROVIDER_STRING_ARRAY_UNREADABLE:" + key, error);
        }
        ArrayList<String> values = bundle.getStringArrayList(key);
        return values == null ? null : values.toArray(new String[0]);
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
