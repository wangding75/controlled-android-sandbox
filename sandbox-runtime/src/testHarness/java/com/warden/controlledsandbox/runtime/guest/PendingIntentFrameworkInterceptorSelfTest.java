package com.warden.controlledsandbox.runtime.guest;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import com.warden.controlledsandbox.framework.core.FrameworkCallInterceptor;
import com.warden.controlledsandbox.framework.routing.VirtualPendingIntentRegistry;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

public final class PendingIntentFrameworkInterceptorSelfTest {
    public static void main(String[] args) throws Throwable {
        Bundle specBundle = TestGuestSpecFactory.bundle();
        GuestPackageSpec spec = new GuestPackageSpec(specBundle);
        AtomicInteger deliveries = new AtomicInteger();
        PendingIntentFrameworkInterceptor interceptor = new PendingIntentFrameworkInterceptor(
                spec, (record, fillIn) -> deliveries.incrementAndGet());
        Method create = FakeAms.class.getMethod("getIntentSender", int.class, String.class,
                int.class, Intent[].class, int.class, int.class);
        Intent intent = new Intent("guest.ACTION").setComponent(
                new ComponentName(spec.packageName, "guest.pkg.Receiver"));
        Object[] createArgs = {1, spec.packageName, 7, new Intent[]{intent},
                VirtualPendingIntentRegistry.FLAG_UPDATE_CURRENT, spec.virtualUserId};
        FrameworkCallInterceptor.Interception created = interceptor.intercept(
                "activity-manager", create, createArgs);
        require(created.handled() && created.result() instanceof FakeIntentSender,
                "virtual sender created");
        FakeIntentSender sender = (FakeIntentSender) created.result();
        require(sender.asBinder() != null && sender.send(null) == 1 && deliveries.get() == 1,
                "virtual sender dispatches through runtime callback");

        Method packageQuery = FakeAms.class.getMethod("getPackageForIntentSender", FakeIntentSender.class);
        FrameworkCallInterceptor.Interception packageResult = interceptor.intercept(
                "activity-manager", packageQuery, new Object[]{sender});
        require(spec.packageName.equals(packageResult.result()), "sender package remains Guest identity");

        Method uidQuery = FakeAms.class.getMethod("getUidForIntentSender", FakeIntentSender.class);
        require(((Integer) interceptor.intercept("activity-manager", uidQuery,
                new Object[]{sender}).result()) == spec.virtualUid, "sender UID remains virtual");

        Method cancel = FakeAms.class.getMethod("cancelIntentSender", FakeIntentSender.class);
        interceptor.intercept("activity-manager", cancel, new Object[]{sender});
        boolean rejected = false;
        try { sender.send(null); } catch (IllegalStateException expected) { rejected = true; }
        require(rejected && interceptor.snapshot().active() == 0, "cancelled sender cannot dispatch");
        interceptor.close();
        System.out.println("PASS runtime PendingIntent framework routing self-test");
    }

    public interface FakeIntentSender {
        IBinder asBinder();
        int send(Intent fillIn) throws Exception;
    }
    public interface FakeAms {
        FakeIntentSender getIntentSender(int type, String packageName, int requestCode,
                                        Intent[] intents, int flags, int userId);
        void cancelIntentSender(FakeIntentSender sender);
        String getPackageForIntentSender(FakeIntentSender sender);
        int getUidForIntentSender(FakeIntentSender sender);
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
