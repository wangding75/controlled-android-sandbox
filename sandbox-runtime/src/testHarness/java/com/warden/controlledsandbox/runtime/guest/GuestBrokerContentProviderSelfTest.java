package com.warden.controlledsandbox.runtime.guest;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.DisplayMetrics;
import com.warden.controlledsandbox.contract.ActivityResultRequest;
import com.warden.controlledsandbox.contract.ActivityResultResult;
import com.warden.controlledsandbox.contract.ActivityTaskRequest;
import com.warden.controlledsandbox.contract.ActivityTaskResult;
import com.warden.controlledsandbox.contract.IRuntimeBroker;
import com.warden.controlledsandbox.contract.PackageServiceResult;
import com.warden.controlledsandbox.contract.RuntimeOperationRequest;
import com.warden.controlledsandbox.contract.RuntimeOperationResult;
import com.warden.controlledsandbox.contract.RuntimeStatusRequest;
import com.warden.controlledsandbox.contract.RuntimeStatusResult;
import com.warden.controlledsandbox.contract.VirtualComponentSnapshot;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Method;

/** Regression evidence for standard ContentProvider primitives routed through the runtime Broker. */
public final class GuestBrokerContentProviderSelfTest {
    public static void main(String[] args) throws Throwable {
        FakeBroker broker = new FakeBroker();
        GuestPackageSpec spec = new GuestPackageSpec(specBundle(broker));
        GuestContext context = new GuestContext(new Context(), spec,
                GuestBrokerContentProviderSelfTest.class.getClassLoader(),
                new Resources(new AssetManager(), new DisplayMetrics(), new Configuration()),
                new AssetManager(), new android.content.pm.PackageManager());
        GuestBrokerContentProvider provider = new GuestBrokerContentProvider(
                context, spec, "guest.authority", "guest.pkg.Provider");

        provider.prepare();
        require(ComponentOperations.PREPARE_PROVIDER.equals(broker.operations.get(0)),
                "provider preparation uses Broker");

        Cursor cursor = provider.query(Uri.parse("content://guest.authority/items"),
                new String[]{"id", "name"}, "id=?", new String[]{"7"}, "id ASC");
        require(cursor.getCount() == 2, "paged Broker cursor is materialized");
        require(cursor.moveToNext() && cursor.getLong(0) == 7L
                        && "alpha".equals(cursor.getString(1)),
                "first query row decoded");
        require(cursor.moveToNext() && cursor.getLong(0) == 8L
                        && "beta".equals(cursor.getString(1)),
                "second query row decoded");
        require(broker.operations.contains(ComponentOperations.PROVIDER_CURSOR_PAGE)
                        && broker.operations.contains(ComponentOperations.PROVIDER_CURSOR_CLOSE),
                "query pages and closes remote cursor");

        require("vnd.android.cursor.dir/item".equals(
                provider.getType(Uri.parse("content://guest.authority/items"))),
                "getType routed");

        ContentValues values = new ContentValues();
        values.put("name", "inserted");
        values.put("byte", Byte.valueOf((byte) 2));
        values.put("short", Short.valueOf((short) 3));
        Uri inserted = provider.insert(Uri.parse("content://guest.authority/items"), values);
        require("content://guest.authority/items/9".equals(String.valueOf(inserted)),
                "insert result routed");
        require(provider.update(Uri.parse("content://guest.authority/items/9"), values,
                "id=?", new String[]{"9"}) == 1, "update result routed");
        require(provider.delete(Uri.parse("content://guest.authority/items/9"),
                "id=?", new String[]{"9"}) == 1, "delete result routed");

        Bundle extras = new Bundle();
        extras.putString("input", "value");
        Bundle called = provider.call("ping", "arg", extras);
        require("pong".equals(called.getString("reply", "")), "call result routed");
        require(provider.call("returnsNull", null, null) == null,
                "null Provider.call result and argument are preserved");

        Cursor nullableQuery = provider.query(Uri.parse("content://guest.authority/items"),
                null, null, null, null);
        require(nullableQuery.getCount() == 2, "null query arguments remain routable");

        ParcelFileDescriptor descriptor = provider.openFile(
                Uri.parse("content://guest.authority/file"), "r");
        require(descriptor != null, "openFile descriptor routed");

        for (Bundle request : broker.requests) {
            require("guest.pkg".equals(request.getString(RuntimeKeys.PACKAGE_NAME, "")),
                    "Broker call preserves Guest package identity");
            require("session-provider".equals(request.getString(RuntimeKeys.SESSION_ID, ""))
                            && request.getLong(RuntimeKeys.GENERATION, 0L) == 5L,
                    "Broker call preserves session identity");
            require("guest.authority".equals(
                    request.getString(ComponentOperations.AUTHORITY, "")),
                    "Broker call remains authority scoped");
        }
        require("inserted".equals(broker.insertValues.getString("name", ""))
                        && broker.insertValues.getInt("byte", -1) == 2
                        && broker.insertValues.getInt("short", -1) == 3,
                "ContentValues primitive types encoded without Host object leakage");
        Bundle nullableRequest = broker.lastRequest(ComponentOperations.PROVIDER_QUERY);
        require(nullableRequest.getString(RuntimeKeys.PROVIDER_SELECTION) == null
                        && nullableRequest.getString(RuntimeKeys.PROVIDER_SORT_ORDER) == null
                        && nullableRequest.getStringArrayList(RuntimeKeys.PROVIDER_SELECTION_ARGS) == null,
                "null query arguments preserve Android ContentProvider semantics");

        GuestContentProviderFrameworkInterceptor interceptor =
                new GuestContentProviderFrameworkInterceptor(context, spec);
        Method providerMethod = FakeActivityManager.class.getMethod(
                "getContentProvider", String.class, String.class);
        boolean unknownAuthorityDenied = false;
        try {
            interceptor.intercept("activity-manager", providerMethod,
                    new Object[]{"guest.pkg", "host.authority"});
        } catch (SecurityException expected) {
            unknownAuthorityDenied = expected.getMessage().contains(
                    "CONTENT_PROVIDER_AUTHORITY_NOT_VIRTUALIZED");
        }
        require(unknownAuthorityDenied,
                "unknown Provider authority cannot fall through to Host ContentResolver");
        Method modernProviderMethod = ModernActivityManager.class.getMethod(
                "getContentProvider", Object.class, String.class, String.class,
                String.class, int.class, boolean.class);
        boolean modernUnknownDenied = false;
        try {
            interceptor.intercept("activity-manager", modernProviderMethod,
                    new Object[]{new Object(), "guest.pkg", "feature-id", "host.modern", 0, true});
        } catch (SecurityException expected) {
            modernUnknownDenied = expected.getMessage().endsWith(":host.modern");
        }
        require(modernUnknownDenied,
                "modern getContentProvider signature resolves authority immediately before user id");
        interceptor.close();
        System.out.println("PASS standard ContentProvider Broker bridge self-test");
    }

    public interface FakeActivityManager {
        Object getContentProvider(String callerPackage, String authority);
    }

    public interface ModernActivityManager {
        Object getContentProvider(Object caller, String callerPackage, String featureId,
                String authority, int userId, boolean stable);
    }

    private static final class FakeBroker extends IRuntimeBroker.Stub {
        final List<String> operations = new ArrayList<>();
        final List<Bundle> requests = new ArrayList<>();
        Bundle insertValues = new Bundle();

        @Override public RuntimeOperationResult executeV2(RuntimeOperationRequest request) {
            Bundle payload = request.payload();
            String operation = payload.getString(ComponentOperations.OPERATION, "");
            operations.add(operation);
            requests.add(new Bundle(payload));
            Bundle result = new Bundle();
            result.putString(RuntimeKeys.STATUS, "OK");
            switch (operation) {
                case ComponentOperations.PROVIDER_QUERY:
                    result.putString(RuntimeKeys.CURSOR_TOKEN, "cursor-1");
                    result.putStringArrayList(RuntimeKeys.CURSOR_COLUMNS,
                            new ArrayList<>(List.of("id", "name")));
                    result.putInt(RuntimeKeys.CURSOR_TOTAL_ROWS, 2);
                    result.putInt(RuntimeKeys.CURSOR_ROWS_RETURNED, 1);
                    result.putStringArrayList(RuntimeKeys.CURSOR_ROW_PREFIX + 0,
                            new ArrayList<>(List.of("I:7", "S:YWxwaGE=")));
                    result.putBoolean(RuntimeKeys.CURSOR_END_REACHED, false);
                    result.putInt(RuntimeKeys.CURSOR_NEXT_OFFSET, 1);
                    result.putLong(RuntimeKeys.CURSOR_NEXT_SEQUENCE, 1L);
                    break;
                case ComponentOperations.PROVIDER_CURSOR_PAGE:
                    result.putInt(RuntimeKeys.CURSOR_ROWS_RETURNED, 1);
                    result.putStringArrayList(RuntimeKeys.CURSOR_ROW_PREFIX + 0,
                            new ArrayList<>(List.of("I:8", "S:YmV0YQ==")));
                    result.putBoolean(RuntimeKeys.CURSOR_END_REACHED, true);
                    result.putInt(RuntimeKeys.CURSOR_NEXT_OFFSET, 2);
                    result.putLong(RuntimeKeys.CURSOR_NEXT_SEQUENCE, 2L);
                    break;
                case ComponentOperations.PROVIDER_GET_TYPE:
                    result.putString("mimeType", "vnd.android.cursor.dir/item");
                    break;
                case ComponentOperations.PROVIDER_INSERT:
                    insertValues = payload.getBundle(RuntimeKeys.PROVIDER_VALUES);
                    result.putString(RuntimeKeys.URI, "content://guest.authority/items/9");
                    break;
                case ComponentOperations.PROVIDER_UPDATE:
                case ComponentOperations.PROVIDER_DELETE:
                    result.putInt("affectedRows", 1);
                    break;
                case ComponentOperations.PROVIDER_CALL:
                    if (!"returnsNull".equals(payload.getString(RuntimeKeys.PROVIDER_METHOD, ""))) {
                        Bundle called = new Bundle();
                        called.putString("reply", "pong");
                        result.putBundle(RuntimeKeys.PROVIDER_RESULT, called);
                    } else {
                        require(payload.getString(RuntimeKeys.PROVIDER_ARGUMENT) == null,
                                "null call argument preserved on wire");
                        result.putBundle(RuntimeKeys.PROVIDER_RESULT, null);
                    }
                    break;
                case ComponentOperations.PROVIDER_OPEN_FILE:
                    result.putParcelable(RuntimeKeys.FILE_DESCRIPTOR, new ParcelFileDescriptor());
                    break;
                default:
                    break;
            }
            return RuntimeOperationResult.success(request, "OK", result);
        }


        Bundle lastRequest(String operation) {
            for (int index = operations.size() - 1; index >= 0; index--) {
                if (operation.equals(operations.get(index))) return requests.get(index);
            }
            throw new AssertionError("missing request " + operation);
        }

        @Override public ActivityTaskResult activityTaskOperation(ActivityTaskRequest request) { return null; }
        @Override public ActivityResultResult activityResultOperation(ActivityResultRequest request) { return null; }
        @Override public PackageServiceResult requestRuntimePermission(String sessionId, long generation,
                String permission, int requestCode) { return null; }
        @Override public PackageServiceResult reportRuntimePermissionResult(String sessionId, long generation,
                String permission, int requestCode, boolean hostGranted, String reason) { return null; }
        @Override public RuntimeStatusResult runtimeStatusV2(RuntimeStatusRequest request) { return null; }
        @Override public void stopGuest(String packageName, int virtualUserId) { }
    }

    private static Bundle specBundle(FakeBroker broker) {
        Bundle input = new Bundle();
        input.putInt(RuntimeKeys.PROTOCOL, 3);
        input.putString(RuntimeKeys.SESSION_ID, "session-provider");
        input.putLong(RuntimeKeys.GENERATION, 5L);
        input.putString(RuntimeKeys.PACKAGE_NAME, "guest.pkg");
        input.putInt(RuntimeKeys.VIRTUAL_USER_ID, 2);
        input.putInt(RuntimeKeys.VIRTUAL_UID, 12002);
        input.putInt(RuntimeKeys.PROCESS_SLOT, 0);
        input.putString(RuntimeKeys.PROCESS_NAME, "guest.pkg");
        input.putString(RuntimeKeys.APK_PATH, "/tmp/base.apk");
        String sha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        input.putString(RuntimeKeys.APK_SHA256, sha);
        input.putString(RuntimeKeys.BASE_APK_SHA256, sha);
        input.putLong(RuntimeKeys.APK_VERSION_CODE, 1L);
        input.putString(RuntimeKeys.PACKAGE_REVISION, "v1:" + sha);
        input.putString(RuntimeKeys.DATA_ROOT, "/tmp/guest");
        input.putBinder(RuntimeKeys.RUNTIME_BROKER_BINDER, broker.asBinder());
        VirtualComponentSnapshot provider = new VirtualComponentSnapshot(
                "PROVIDER", "guest.pkg.Provider", "guest.pkg", false, true, false,
                "guest.authority", "", List.of());
        input.putParcelable(RuntimeKeys.PACKAGE_STATE,
                new VirtualPackageStateSnapshot("guest.pkg", 2, "Guest", "1.0", 1L,
                        sha, sha, "guest.pkg.MainActivity", "", true,
                        List.of(provider), List.of(), List.of()));
        return input;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
