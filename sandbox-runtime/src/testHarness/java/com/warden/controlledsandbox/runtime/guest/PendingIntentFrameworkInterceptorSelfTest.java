package com.warden.controlledsandbox.runtime.guest;

import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import com.warden.controlledsandbox.framework.core.FrameworkCallInterceptor;
import com.warden.controlledsandbox.framework.identity.VirtualSystemServiceState;
import com.warden.controlledsandbox.framework.routing.VirtualPendingIntentRegistry;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class PendingIntentFrameworkInterceptorSelfTest {
    public static void main(String[] args) throws Throwable {
        Bundle specBundle = TestGuestSpecFactory.bundle();
        GuestPackageSpec spec = new GuestPackageSpec(specBundle);
        VirtualSystemServiceState durableState = new VirtualSystemServiceState();
        AtomicInteger deliveries = new AtomicInteger();
        AtomicReference<VirtualPendingIntentRegistry.Record> delivered = new AtomicReference<>();
        AtomicReference<VirtualPendingIntentRegistry.SendRequest> request = new AtomicReference<>();
        PendingIntentFrameworkInterceptor interceptor = new PendingIntentFrameworkInterceptor(
                spec, durableState.pendingIntents(), token -> "activity-token-7",
                (record, value) -> { delivered.set(record); request.set(value); return deliveries.incrementAndGet(); });

        Method create = FakeAms.class.getMethod("getIntentSender", int.class, String.class,
                int.class, Intent[].class, int.class, int.class, String.class);
        Intent intent = new Intent("guest.ACTION").setComponent(
                new ComponentName(spec.packageName, "guest.pkg.Receiver"))
                .setDataAndType(Uri.parse("content://guest/item/7"), "text/plain")
                .setPackage(spec.packageName).addCategory("guest.CATEGORY")
                .putExtra("base", "payload");
        Object[] createArgs = {1, spec.packageName, 7, new Intent[]{intent},
                VirtualPendingIntentRegistry.FLAG_UPDATE_CURRENT
                        | VirtualPendingIntentRegistry.FLAG_MUTABLE,
                spec.virtualUserId, "guest.permission.SEND"};
        FrameworkCallInterceptor.Interception created = interceptor.intercept(
                "activity-manager", create, createArgs);
        require(created.handled() && created.result() instanceof FakeIntentSender,
                "virtual sender created");
        FakeIntentSender sender = (FakeIntentSender) created.result();

        Intent equivalentIntent = new Intent(intent).putExtra("base", "updated");
        FakeIntentSender equivalent = (FakeIntentSender) interceptor.intercept(
                "activity-manager", create, new Object[]{1, spec.packageName, 7,
                        new Intent[]{equivalentIntent}, VirtualPendingIntentRegistry.FLAG_UPDATE_CURRENT
                                | VirtualPendingIntentRegistry.FLAG_MUTABLE,
                        spec.virtualUserId, "guest.permission.SEND"}).result();
        require(sender == equivalent && sender.equals(equivalent)
                        && sender.hashCode() == equivalent.hashCode(),
                "PendingIntent equality ignores extras and reuses filter identity");

        Intent differentType = new Intent(intent).setType("application/json");
        FakeIntentSender distinct = (FakeIntentSender) interceptor.intercept(
                "activity-manager", create, new Object[]{1, spec.packageName, 7,
                        new Intent[]{differentType}, VirtualPendingIntentRegistry.FLAG_MUTABLE,
                        spec.virtualUserId, "guest.permission.SEND"}).result();
        require(distinct != sender && !sender.equals(distinct),
                "MIME type participates in PendingIntent equality");

        Intent fillIn = new Intent().putExtra("fill", "value")
                .setClipData(ClipData.newRawUri("guest-clip", Uri.parse("content://guest/clip")));
        require(sender.asBinder() != null
                        && sender.send(fillIn, 0x30, 0x20, "guest.permission.SEND") == 1
                        && deliveries.get() == 1,
                "virtual sender dispatches through runtime callback");
        require(request.get().fillInPayload() == fillIn && request.get().flagsMask() == 0
                        && request.get().flagsValues() == 0,
                "IIntentSender fill-in reaches delivery policy without inventing flag arguments");
        require(ComponentOperations.START_SERVICE.equals(GuestPendingIntentDispatcher.serviceOperation(
                        VirtualPendingIntentRegistry.Kind.SERVICE))
                        && ComponentOperations.START_FOREGROUND_SERVICE.equals(
                        GuestPendingIntentDispatcher.serviceOperation(
                                VirtualPendingIntentRegistry.Kind.FOREGROUND_SERVICE)),
                "PendingIntent service kinds preserve foreground-service transaction semantics");
        Intent merged = GuestPendingIntentDispatcher.selectedIntent(delivered.get().payload(), request.get());
        require("payload".equals(merged.getStringExtra("base"))
                        || "updated".equals(merged.getStringExtra("base")),
                "base extras survive PendingIntent dispatch");
        require("value".equals(merged.getStringExtra("fill"))
                        && merged.getClipData() != null
                        && merged.getClipData().getItemCount() == 1
                        && merged.getFlags() == 0,
                "FillIn extras, ClipData and flags are merged");
        require(sender.send(0x37, null, "", null, null, "guest.permission.SEND", null) == 2
                        && request.get().flagsMask() == 0 && request.get().flagsValues() == 0
                        && request.get().resultCode() == 0x37,
                "IIntentSender result code is not misclassified as Intent fill-in flags");
        String persistentTokenId = delivered.get().persistentTokenId();

        boolean permissionDenied = false;
        try { sender.send(null, 0, 0, "guest.permission.WRONG"); }
        catch (SecurityException expected) { permissionDenied = true; }
        require(permissionDenied, "sender permission is enforced");

        Method packageQuery = FakeAms.class.getMethod("getPackageForIntentSender", FakeIntentSender.class);
        FrameworkCallInterceptor.Interception packageResult = interceptor.intercept(
                "activity-manager", packageQuery, new Object[]{sender});
        require(spec.packageName.equals(packageResult.result()), "sender package remains Guest identity");

        Method uidQuery = FakeAms.class.getMethod("getUidForIntentSender", FakeIntentSender.class);
        require(((Integer) interceptor.intercept("activity-manager", uidQuery,
                new Object[]{sender}).result()) == spec.virtualUid, "sender UID remains virtual");
        Method flagsQuery = FakeAms.class.getMethod("getFlagsForIntentSender", FakeIntentSender.class);
        require((((Integer) interceptor.intercept("activity-manager", flagsQuery,
                new Object[]{sender}).result()) & VirtualPendingIntentRegistry.FLAG_MUTABLE) != 0,
                "sender flags query returns virtual flags");

        Method createResult = FakeAms.class.getMethod("getIntentSenderResult", int.class,
                String.class, IBinder.class, int.class, Intent[].class, int.class, int.class);
        FakeIntentSender resultSender = (FakeIntentSender) interceptor.intercept(
                "activity-task-manager", createResult, new Object[]{3, spec.packageName,
                        new Binder(), 19, new Intent[]{new Intent("guest.RESULT")},
                        VirtualPendingIntentRegistry.FLAG_MUTABLE, spec.virtualUserId}).result();
        resultSender.send(new Intent("guest.RESULT.FILL"), 0, 0, "");
        require(delivered.get().spec().kind() == VirtualPendingIntentRegistry.Kind.ACTIVITY_RESULT
                        && "activity-token-7".equals(delivered.get().spec().component()),
                "Activity Result PendingIntent binds virtual Activity token");

        Object immutableCreated = interceptor.intercept("activity-manager", create,
                new Object[]{1, spec.packageName, 28, new Intent[]{new Intent("guest.IMMUTABLE")},
                        VirtualPendingIntentRegistry.FLAG_IMMUTABLE, spec.virtualUserId, ""}).result();
        boolean immutableDenied = false;
        try { ((FakeIntentSender) immutableCreated).send(new Intent("fill"), 0, 0, ""); }
        catch (SecurityException expected) { immutableDenied = true; }
        require(immutableDenied, "immutable sender rejects FillIn Intent");
        require(((FakeIntentSender) immutableCreated).send(0x19, null, "", null, null, "", null) == 4,
                "immutable sender accepts a non-zero IIntentSender result code without fill-in");

        require(durableState.pendingIntents().records().size() >= 4,
                "PendingIntent tokens are stored outside Guest process");
        interceptor.close();
        require(durableState.pendingIntents().records().size() >= 4,
                "Guest process close does not delete durable tokens");
        PendingIntentFrameworkInterceptor recovered = new PendingIntentFrameworkInterceptor(
                spec, durableState.pendingIntents(), token -> "activity-token-7", (record, value) -> 9);
        FakeIntentSender recoveredSender = (FakeIntentSender) recovered.intercept(
                "activity-manager", create, createArgs).result();
        require(recoveredSender.send(null, 0, 0, "guest.permission.SEND") == 9,
                "persistent sender is reattached after Guest process recreation");
        PendingIntentFrameworkInterceptor.PersistentSendResult detailed =
                recovered.sendPersistentResult(persistentTokenId,
                        new VirtualPendingIntentRegistry.SendRequest(
                                new Intent().putExtra("recovered-fill", "yes"), 0x30, 0x20,
                                "guest.permission.SEND", -1));
        Intent deliveredIntent = detailed.deliveredIntent();
        require(detailed.delivered() && detailed.resultCode() == 9 && deliveredIntent != null,
                "persistent send returns a completion Intent after rebind");
        require("guest.ACTION".equals(deliveredIntent.getAction())
                && spec.packageName.equals(deliveredIntent.getPackage())
                        && ("updated".equals(deliveredIntent.getStringExtra("base"))
                                || "payload".equals(deliveredIntent.getStringExtra("base")))
                        && "yes".equals(deliveredIntent.getStringExtra("recovered-fill"))
                        && deliveredIntent.getFlags() == 0x20,
                "completion Intent is the merged base sender Intent, not raw fill-in");

        Method cancel = FakeAms.class.getMethod("cancelIntentSender", FakeIntentSender.class);
        recovered.intercept("activity-manager", cancel, new Object[]{recoveredSender});
        boolean rejected = false;
        try { recoveredSender.send(null, 0, 0, "guest.permission.SEND"); }
        catch (IllegalStateException expected) { rejected = true; }
        require(rejected, "cancelled sender cannot dispatch");
        recovered.close();
        durableState.close();
        System.out.println("PASS runtime PendingIntent framework routing self-test");
    }

    public interface FakeIntentSender {
        IBinder asBinder();
        int send(int code, Intent fillIn, String resolvedType, IBinder whitelistToken,
                 IBinder finishedReceiver, String permission, Bundle options) throws Exception;
        default int send(Intent fillIn, int flagsMask, int flagsValues, String permission)
                throws Exception {
            return send(0, fillIn, "", null, null, permission, null);
        }
    }
    public interface FakeAms {
        FakeIntentSender getIntentSender(int type, String packageName, int requestCode,
                                        Intent[] intents, int flags, int userId, String permission);
        FakeIntentSender getIntentSenderResult(int type, String packageName, IBinder activityToken,
                                              int requestCode, Intent[] intents, int flags, int userId);
        void cancelIntentSender(FakeIntentSender sender);
        String getPackageForIntentSender(FakeIntentSender sender);
        int getUidForIntentSender(FakeIntentSender sender);
        int getFlagsForIntentSender(FakeIntentSender sender);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class TestGuestSpecFactory {
        static Bundle bundle() {
            Bundle input = new Bundle();
            input.putInt(RuntimeKeys.PROTOCOL, 3);
            input.putString(RuntimeKeys.SESSION_ID, "session-1");
            input.putLong(RuntimeKeys.GENERATION, 3L);
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
            input.putParcelable(RuntimeKeys.PACKAGE_STATE,
                    new com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot(
                            "guest.pkg", 2, "Guest", "1.0", 1L, sha, sha,
                            "guest.pkg.MainActivity", "", true, java.util.List.of(),
                            java.util.List.of(), java.util.List.of()));
            return input;
        }
    }
}
