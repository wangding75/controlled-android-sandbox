package com.warden.controlledsandbox.runtime.component.activity;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
import com.warden.controlledsandbox.runtime.guest.GuestContext;
import com.warden.controlledsandbox.runtime.guest.GuestPackageSpec;
import com.warden.controlledsandbox.runtime.guest.GuestRuntimeEnvironment;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import sun.misc.Unsafe;

public final class ActivityFieldBridgeSelfTest {
    private ActivityFieldBridgeSelfTest() { }

    public static void main(String[] args) throws Exception {
        testFieldCopyAndContract();
        testLegacyPreLaunchRecordSuccess();
        testLegacyPreLaunchRecordMissingFailsClosed();
        testAndroid15DirectLaunchActivityItemAuthoritative();

        System.out.println("PASS audited Activity field bridge self-test");
    }

    private static void testFieldCopyAndContract() {
        FakeActivity host = new FakeActivity();
        host.required = "host-required";
        host.optional = "host-optional";
        FakeActivity guest = new FakeActivity();
        guest.required = "guest-required";
        guest.direct = "guest-direct";
        ActivityFieldBridge.BridgeReport report = ActivityFieldBridge.installFields(
                host, guest, List.of("required"), List.of("optional", "missing"),
                Map.of("direct", "new-direct"), 36);
        check("host-required".equals(guest.required), "required field not copied");
        check("host-optional".equals(guest.optional), "optional field not copied");
        check("new-direct".equals(guest.direct), "direct field not written");
        check(report.optionalMissingFields().equals(List.of("missing")), "missing optional field not reported");

        expectFailure(() -> ActivityFieldBridge.installFields(host, new MissingRequired(),
                List.of("required"), List.of(), Map.of(), 36), "missing required field must fail closed");
        expectFailure(() -> ActivityFieldBridge.installFields(host, guest,
                List.of("required"), List.of(), Map.of(), 37), "unknown API must fail closed");
        expectFailure(() -> ActivityFieldBridge.installFields(new WrongTypeHost(), new WrongTypeTarget(),
                List.of("required"), List.of(), Map.of(), 36), "type mismatch must fail closed");
    }

    private static void testLegacyPreLaunchRecordSuccess() throws Exception {
        GuestRuntimeEnvironment.Session session = createTestSession("session-legacy", 1L, "com.guest.legacy");
        IBinder token = new Binder();
        FakeActivityClientRecord record = new FakeActivityClientRecord();
        record.activityInfo = new ActivityInfo();
        record.activityInfo.name = "com.warden.controlledsandbox.runtime.component.activity.StubActivity0";
        record.intent = new Intent();

        FakeActivityThreadLegacy thread = new FakeActivityThreadLegacy();
        thread.mLaunchingActivities.put(token, record);

        FakeLaunchActivityItem launchItem = new FakeLaunchActivityItem();
        launchItem.mInfo = new ActivityInfo();
        launchItem.mInfo.name = "com.warden.controlledsandbox.runtime.component.activity.StubActivity0";
        launchItem.mIntent = createHostIntent("tok-legacy", "session-legacy", 1L, "com.guest.legacy.MainActivity");

        FakeClientTransaction transaction = new FakeClientTransaction();
        transaction.mActivityToken = token;
        transaction.mActivityCallbacks.add(launchItem);

        Message message = new Message();
        message.what = 159;
        message.obj = transaction;

        boolean projected = ActivityFieldBridge.projectFrameworkLaunchTransaction(thread, message, session);
        check(projected, "legacy transaction must be projected");
        check("com.guest.legacy.MainActivity".equals(launchItem.mIntent.getComponent().getClassName()),
                "LaunchActivityItem.mIntent component must be projected to guest");
        check("com.guest.legacy.MainActivity".equals(launchItem.mInfo.name),
                "LaunchActivityItem.mInfo must be projected to guest");
        check(record.activityInfo != null && "com.guest.legacy.MainActivity".equals(record.activityInfo.name),
                "legacy mLaunchingActivities ActivityClientRecord.activityInfo must be projected");
        check(record.intent != null && "com.guest.legacy.MainActivity".equals(record.intent.getComponent().getClassName()),
                "legacy mLaunchingActivities ActivityClientRecord.intent must be projected");
    }

    private static void testLegacyPreLaunchRecordMissingFailsClosed() throws Exception {
        GuestRuntimeEnvironment.Session session = createTestSession("session-fail", 1L, "com.guest.fail");
        IBinder token = new Binder();

        FakeActivityThreadLegacy thread = new FakeActivityThreadLegacy();
        // mLaunchingActivities is present but does NOT contain the token.

        FakeLaunchActivityItem launchItem = new FakeLaunchActivityItem();
        launchItem.mInfo = new ActivityInfo();
        launchItem.mIntent = createHostIntent("tok-fail", "session-fail", 1L, "com.guest.fail.MainActivity");

        FakeClientTransaction transaction = new FakeClientTransaction();
        transaction.mActivityToken = token;
        transaction.mActivityCallbacks.add(launchItem);

        Message message = new Message();
        message.what = 159;
        message.obj = transaction;

        expectFailure(() -> ActivityFieldBridge.projectFrameworkLaunchTransaction(thread, message, session),
                "missing record in existing mLaunchingActivities must fail closed");
    }

    private static void testAndroid15DirectLaunchActivityItemAuthoritative() throws Exception {
        GuestRuntimeEnvironment.Session session = createTestSession("session-a15", 2L, "com.guest.a15");
        IBinder token = new Binder();

        // Android 15 ActivityThread has NO mLaunchingActivities and NO getLaunchingActivity.
        FakeActivityThreadAndroid15 thread = new FakeActivityThreadAndroid15();

        FakeLaunchActivityItem launchItem = new FakeLaunchActivityItem();
        launchItem.mInfo = new ActivityInfo();
        launchItem.mInfo.name = "com.warden.controlledsandbox.runtime.component.activity.StubActivity54";
        launchItem.mIntent = createHostIntent("tok-a15", "session-a15", 2L, "com.guest.a15.MainActivity");

        FakeClientTransaction transaction = new FakeClientTransaction();
        transaction.mActivityToken = token;
        transaction.mActivityCallbacks.add(launchItem);

        Message message = new Message();
        message.what = 159;
        message.obj = transaction;

        boolean projected = ActivityFieldBridge.projectFrameworkLaunchTransaction(thread, message, session);
        check(projected, "Android 15 transaction must be projected");
        check("com.guest.a15.MainActivity".equals(launchItem.mIntent.getComponent().getClassName()),
                "LaunchActivityItem.mIntent must be authoritative on Android 15");
        check("com.guest.a15.MainActivity".equals(launchItem.mInfo.name),
                "LaunchActivityItem.mInfo must be authoritative on Android 15");
        check("com.guest.a15".equals(launchItem.mInfo.packageName),
                "LaunchActivityItem.mInfo package must be projected");
    }

    private static Intent createHostIntent(String routeToken, String sessionId, long generation, String componentClass) {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName("com.warden.controlledsandbox.debug",
                "com.warden.controlledsandbox.runtime.component.activity.StubActivity54"));
        intent.putExtra(RuntimeKeys.ROUTE_TOKEN, routeToken);
        intent.putExtra(RuntimeKeys.SESSION_ID, sessionId);
        intent.putExtra(RuntimeKeys.GENERATION, generation);
        intent.putExtra(RuntimeKeys.COMPONENT_CLASS, componentClass);
        return intent;
    }

    private static GuestRuntimeEnvironment.Session createTestSession(
            String sessionId, long generation, String packageName) throws Exception {
        Bundle bundle = new Bundle();
        bundle.putInt(RuntimeKeys.PROTOCOL, 3);
        bundle.putString(RuntimeKeys.SESSION_ID, sessionId);
        bundle.putLong(RuntimeKeys.GENERATION, generation);
        bundle.putString(RuntimeKeys.PACKAGE_NAME, packageName);
        bundle.putInt(RuntimeKeys.VIRTUAL_USER_ID, 0);
        bundle.putInt(RuntimeKeys.VIRTUAL_UID, 10000);
        bundle.putInt(RuntimeKeys.PROCESS_SLOT, 0);
        bundle.putString(RuntimeKeys.PROCESS_NAME, packageName);
        bundle.putString(RuntimeKeys.APK_PATH, "/tmp/base.apk");
        String sha256 = "0000000000000000000000000000000000000000000000000000000000000000";
        bundle.putString(RuntimeKeys.APK_SHA256, sha256);
        bundle.putString(RuntimeKeys.BASE_APK_SHA256, sha256);
        bundle.putLong(RuntimeKeys.APK_VERSION_CODE, 1L);
        bundle.putString(RuntimeKeys.PACKAGE_REVISION, "v1");
        bundle.putString(RuntimeKeys.DATA_ROOT, "/tmp/guest");
        bundle.putParcelable(RuntimeKeys.PACKAGE_STATE,
                new VirtualPackageStateSnapshot(packageName, 0, "Guest", "1.0", 1L,
                        sha256, sha256, packageName + ".MainActivity", "", true,
                        List.of(), List.of(), List.of()));
        GuestPackageSpec spec = new GuestPackageSpec(bundle);

        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.packageName = packageName;
        appInfo.processName = packageName;

        com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata.Component comp =
                new com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata.Component(
                        com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata.Type.ACTIVITY,
                        packageName + ".MainActivity", packageName, true, true, false, java.util.Set.of(), "");

        com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata metadata =
                new com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata(
                        packageName, packageName + ".MainActivity", appInfo, List.of(comp),
                        "1.0", 1L, sha256, 0L, 0L, "", List.of(), List.of(), true);

        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);

        GuestRuntimeEnvironment.Session session =
                (GuestRuntimeEnvironment.Session) unsafe.allocateInstance(GuestRuntimeEnvironment.Session.class);

        setField(session, "spec", spec);
        setField(session, "packageMetadata", metadata);

        GuestContext guestContext = (GuestContext) unsafe.allocateInstance(GuestContext.class);
        setField(guestContext, "applicationInfo", appInfo);
        setField(session, "context", guestContext);

        return session;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static void expectFailure(Action action, String message) {
        try { action.run(); } catch (Throwable expected) { return; }
        throw new AssertionError(message);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Throwable;
    }

    public static class FakeClientTransaction {
        public IBinder mActivityToken;
        public List<Object> mActivityCallbacks = new ArrayList<>();
        public IBinder getActivityToken() { return mActivityToken; }
        public List<Object> getCallbacks() { return mActivityCallbacks; }
    }

    public static class FakeLaunchActivityItem {
        public Intent mIntent;
        public ActivityInfo mInfo;
    }

    public static class FakeActivityThreadLegacy {
        public Map<Object, Object> mLaunchingActivities = new HashMap<>();
    }

    public static class FakeActivityThreadAndroid15 {
        // Android 15 removed mLaunchingActivities and getLaunchingActivity()
    }

    public static class FakeActivityClientRecord {
        public ActivityInfo activityInfo;
        public Intent intent;
    }

    private static class FakeActivity {
        Object required;
        Object optional;
        Object direct;
    }
    private static final class MissingRequired { }
    private static final class WrongTypeHost { String required = "wrong"; }
    private static final class WrongTypeTarget { Integer required = 1; }
}

