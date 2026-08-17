package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata;
import com.warden.controlledsandbox.framework.identity.VirtualPermissionPolicy;
import com.warden.controlledsandbox.framework.identity.SandboxAppOpsPolicy;
import com.warden.controlledsandbox.framework.packagemanager.PackageManagerInvocationHandlerTestAccess;
import com.warden.controlledsandbox.framework.capability.CapabilityAuditSink;
import com.warden.controlledsandbox.framework.capability.CapabilityLeaseRegistry;
import com.warden.controlledsandbox.framework.identity.VirtualPackageUniverse;
import com.warden.controlledsandbox.framework.identity.VirtualSystemServiceState;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class FrameworkIdentityProxySelfTest {
    public static void main(String[] args) {
        ApplicationInfo info = new ApplicationInfo();
        info.packageName = "guest.pkg";
        info.uid = 12001;
        info.targetSdkVersion = 29;
        VirtualPackageMetadata metadata = new VirtualPackageMetadata("guest.pkg", "guest.pkg.MainActivity", info,
                Arrays.asList(
                        new VirtualPackageMetadata.Component(VirtualPackageMetadata.Type.ACTIVITY,
                                "guest.pkg.MainActivity", "guest.pkg", true, true, false,
                                Set.of("android.intent.action.MAIN"), ""),
                        new VirtualPackageMetadata.Component(VirtualPackageMetadata.Type.SERVICE,
                                "guest.pkg.SyncService", "guest.pkg:remote", false, true, false,
                                Set.of("guest.SYNC"), ""),
                        new VirtualPackageMetadata.Component(VirtualPackageMetadata.Type.RECEIVER,
                                "guest.pkg.BootReceiver", "guest.pkg", true, true, false,
                                Set.of("guest.PING"), "", "guest.SEND_PING"),
                        new VirtualPackageMetadata.Component(VirtualPackageMetadata.Type.PROVIDER,
                                "guest.pkg.DataProvider", "guest.pkg:provider", false, true, false,
                                Set.of(), "guest.pkg.data")));
        GuestIdentity identity = new GuestIdentity("guest.pkg", 12001, info,
                Set.of("android.permission.CAMERA", "android.permission.INTERNET"),
                "host.pkg", 10001, metadata, "guest.pkg", 0, 1L,
                new VirtualPermissionPolicy(
                        Set.of("android.permission.CAMERA", "android.permission.INTERNET"),
                        java.util.Map.of("android.permission.CAMERA", "DENIED")),
                new SandboxAppOpsPolicy(java.util.Map.of("android:camera", "IGNORED")));
        testIdentityRewriting(identity);
        testPackageQueries(identity);
        testCrossPackageSigning(identity, metadata, info);
        testPermissionAndAppOps(identity);
        testActivityManagerHistory(identity);
        testHookReadinessPolicy();
        System.out.println("PASS framework identity and package-manager proxy self-test");
    }

    private static void testIdentityRewriting(GuestIdentity identity) {
        FakeService delegate = new FakeService();
        FakeApi proxy = (FakeApi) Proxy.newProxyInstance(FrameworkIdentityProxySelfTest.class.getClassLoader(),
                new Class<?>[]{FakeApi.class}, new SystemServiceInvocationHandler(delegate, identity));
        require("guest.pkg".equals(proxy.note("guest.pkg", 12001)), "result identity virtualization");
        require("host.pkg".equals(delegate.lastPackage) && delegate.lastUid == 10001,
                "delegate host identity");
        FakeAttribution tail = new FakeAttribution("guest.pkg", 12001, "tail", null);
        FakeAttribution attribution = new FakeAttribution("guest.pkg", 12001, "root", tail);
        proxy.attribution(attribution);
        require("host.pkg".equals(delegate.lastPackage) && delegate.lastUid == 10001,
                "nested attribution rewrite");
        require("host.pkg".equals(delegate.lastTailPackage) && delegate.lastTailUid == 10001,
                "attribution chain rewrite");
        require("root".equals(delegate.lastAttributionTag) && "tail".equals(delegate.lastTailTag),
                "attribution tags preserved");
        require("guest.pkg".equals(attribution.mPackageName) && attribution.mUid == 12001
                        && "guest.pkg".equals(tail.mPackageName) && tail.mUid == 12001,
                "nested attribution chain restored");
        FakeAttributionContext wrapped = new FakeAttributionContext(
                new FakeAttributionState("guest.pkg", 12001));
        proxy.wrappedAttribution(wrapped);
        require("host.pkg".equals(delegate.lastPackage) && delegate.lastUid == 10001,
                "nested attribution state holder rewritten");
        require("guest.pkg".equals(wrapped.mAttributionSourceState.packageName)
                        && wrapped.mAttributionSourceState.uid == 12001,
                "nested attribution state holder restored");
        String[] packages = proxy.packages();
        require(packages.length == 1 && "guest.pkg".equals(packages[0]), "package array result");
    }

    private static void testPackageQueries(GuestIdentity identity) {
        FakePackageService delegate = new FakePackageService();
        FakePackageApi proxy = (FakePackageApi) Proxy.newProxyInstance(
                FrameworkIdentityProxySelfTest.class.getClassLoader(), new Class<?>[]{FakePackageApi.class},
                PackageManagerInvocationHandlerTestAccess.create(delegate, identity));
        ApplicationInfo application = proxy.getApplicationInfo("guest.pkg", 0);
        require("guest.pkg".equals(application.packageName), "virtual application info");
        PackageInfo packageInfo = proxy.getPackageInfo("guest.pkg", 0x0f);
        require(packageInfo.activities.length == 1 && packageInfo.services.length == 1
                && packageInfo.receivers.length == 1 && packageInfo.providers.length == 1,
                "package component matrix");
        require("guest.SEND_PING".equals(packageInfo.receivers[0].permission),
                "manifest Receiver permission metadata");
        ActivityInfo activity = proxy.getActivityInfo(new ComponentName("guest.pkg", "guest.pkg.MainActivity"), 0);
        require("guest.pkg.MainActivity".equals(activity.name), "component info");
        Intent launch = new Intent().setAction("android.intent.action.MAIN");
        ResolveInfo resolved = proxy.resolveIntent(launch, null, 0, 0);
        require(resolved != null && resolved.activityInfo != null, "resolve activity");
        List<ResolveInfo> services = proxy.queryIntentServices(new Intent().setAction("guest.SYNC"), null, 0, 0);
        require(services.size() == 1 && services.get(0).serviceInfo.processName.endsWith(":remote"),
                "query service");
        require(proxy.getPackagesForUid(12001)[0].equals("guest.pkg"), "virtual UID query");
        require(proxy.getInstalledApplications(0, 0).size() == 1, "installed applications isolated");
        require(proxy.checkPermission("android.permission.CAMERA", "guest.pkg", 0) == -1,
                "explicit virtual permission denial");
        require(proxy.checkPermission("android.permission.INTERNET", "guest.pkg", 0) == 0,
                "default declared permission grant");
        require(proxy.getApplicationInfo("host.pkg", 0) == null,
                "host package lookup is NameNotFound-shaped");
        require(proxy.getPackageInfo("host.pkg", 0) == null,
                "host package getPackageInfo is NameNotFound-shaped");
        require(proxy.getActivityInfo(new ComponentName("host.pkg", "host.pkg.Stub"), 0) == null,
                "host component lookup is NameNotFound-shaped");
        ResolveInfo hiddenHostResolve = proxy.resolveIntent(
                new Intent().setPackage("host.pkg"), null, 0, 0);
        require(hiddenHostResolve == null, "explicit host Intent is hidden");
        require(proxy.getApplicationInfo("foreign.pkg", 0) == null,
                "foreign package query is NameNotFound-shaped");
        require(proxy.getPackageInfo("foreign.pkg", 0) == null,
                "foreign package getPackageInfo cannot fall back to Host PackageManager");
        require(proxy.resolveIntent(new Intent().setPackage("foreign.pkg"), null, 0, 0) == null,
                "foreign Intent resolve cannot expose Host activities");
        require(proxy.getPackagesForUid(424242).length == 0,
                "foreign UID query cannot expose Host packages");
        require(delegate.calls == 0, "virtual and hidden-host queries avoid host delegate");
        require("host-result".equals(proxy.unhandled("guest.pkg")), "fallback delegates with host identity");
        require("host.pkg".equals(delegate.lastPackage), "fallback package rewritten");
    }

    private static void testPermissionAndAppOps(GuestIdentity identity) {
        FakePermissionService permissionDelegate = new FakePermissionService();
        FakePermissionApi permission = (FakePermissionApi) Proxy.newProxyInstance(
                FrameworkIdentityProxySelfTest.class.getClassLoader(),
                new Class<?>[]{FakePermissionApi.class},
                new SystemServiceInvocationHandler(permissionDelegate, identity, "permission"));
        require(permission.checkPermission("android.permission.CAMERA", "guest.pkg", 12001) == -1,
                "PermissionManager denial uses virtual policy");
        require(permission.checkPermission("android.permission.INTERNET", "guest.pkg", 12001) == 0,
                "PermissionManager grant uses virtual policy");
        boolean mutationBlocked = false;
        try { permission.grantRuntimePermission("guest.pkg", "android.permission.CAMERA", 0); }
        catch (SecurityException expected) {
            mutationBlocked = expected.getMessage().contains("VIRTUAL_PERMISSION_MUTATION_REQUIRES_PACKAGE_SERVICE");
        }
        require(mutationBlocked, "PermissionManager mutation is fail-closed");
        require(permissionDelegate.calls == 0, "PermissionManager virtual decision avoids host delegate");

        FakeAppOpsService appOpsDelegate = new FakeAppOpsService();
        FakeAppOpsApi appOps = (FakeAppOpsApi) Proxy.newProxyInstance(
                FrameworkIdentityProxySelfTest.class.getClassLoader(),
                new Class<?>[]{FakeAppOpsApi.class},
                new SystemServiceInvocationHandler(appOpsDelegate, identity, "appops"));
        require(appOps.checkOperation("android:camera", 12001, "guest.pkg") == 1,
                "AppOps override maps to MODE_IGNORED");
        require(appOps.checkOperation("android:record_audio", 12001, "guest.pkg") == 3,
                "AppOps default maps to MODE_DEFAULT");
        require(appOps.checkOperation(26, 12001, "guest.pkg") == 1,
                "integer Camera AppOps code maps to virtual camera mode");
        require(appOps.checkOperation(999, 12001, "guest.pkg") == 3,
                "unknown integer AppOps code fails closed to MODE_DEFAULT");
        FakeAttribution source = new FakeAttribution("guest.pkg", 12001, "proxy", null);
        require(appOps.noteProxyOperation("android:camera", source) == 1,
                "proxy AppOps attribution chain targets Guest policy");
        appOps.checkPackage(12001, "guest.pkg");
        boolean packageMismatch = false;
        try { appOps.checkPackage(12002, "guest.pkg"); }
        catch (SecurityException expected) {
            packageMismatch = expected.getMessage().contains("VIRTUAL_APPOPS_PACKAGE_UID_MISMATCH");
        }
        require(packageMismatch, "AppOps checkPackage rejects a virtual UID/package mismatch");
        require(appOps.getOpsForPackage(12001, "guest.pkg", new int[]{26}).isEmpty(),
                "AppOps package inventory is Guest-owned and does not expose Host records");
        boolean appOpsMutationBlocked = false;
        try { appOps.setMode(26, 12001, "guest.pkg", 0); }
        catch (SecurityException expected) {
            appOpsMutationBlocked = expected.getMessage().contains("VIRTUAL_APPOPS_MUTATION_REQUIRES_PACKAGE_SERVICE");
        }
        require(appOpsMutationBlocked, "AppOps mutation is Package-Service-owned");
        require("proxy".equals(source.attributionTag), "proxy attributionTag remains unchanged");
        require(appOpsDelegate.calls == 0, "AppOps virtual decision avoids host delegate");
    }

    private static void testCrossPackageSigning(GuestIdentity callerIdentity,
                                                 VirtualPackageMetadata callerMetadata,
                                                 ApplicationInfo callerInfo) {
        ApplicationInfo peerInfo = new ApplicationInfo();
        peerInfo.packageName = "peer.pkg";
        peerInfo.uid = 12002;
        VirtualPackageMetadata peerMetadata = new VirtualPackageMetadata("peer.pkg", "",
                peerInfo, List.of(), "", 0L, repeat('b'), 0L, 0L, "", List.of(),
                List.of(), List.of(), List.of(), true);
        VirtualPackageMetadata signedCallerMetadata = new VirtualPackageMetadata("guest.pkg", "",
                callerInfo, callerMetadata.components(), "", 0L, repeat('a'), 0L, 0L, "",
                List.of(), List.of(), List.of(), List.of(), true);
        GuestIdentity identity = new GuestIdentity("guest.pkg", 12001, callerInfo,
                Set.of("android.permission.INTERNET"), "host.pkg", 10001,
                signedCallerMetadata, "guest.pkg", 0, 1L,
                new VirtualPermissionPolicy(Set.of("android.permission.INTERNET"),
                        java.util.Map.of()), new SandboxAppOpsPolicy(java.util.Map.of()),
                CapabilityAuditSink.NO_OP, new CapabilityLeaseRegistry(),
                new VirtualSystemServiceState(), "signature-test",
                new VirtualPackageUniverse(List.of(signedCallerMetadata, peerMetadata)));
        FakePackageService delegate = new FakePackageService();
        FakePackageApi proxy = (FakePackageApi) Proxy.newProxyInstance(
                FrameworkIdentityProxySelfTest.class.getClassLoader(),
                new Class<?>[]{FakePackageApi.class},
                PackageManagerInvocationHandlerTestAccess.create(delegate, identity));
        byte[] callerCertificate = new byte[32];
        java.util.Arrays.fill(callerCertificate, (byte) 0xaa);
        byte[] peerCertificate = new byte[32];
        java.util.Arrays.fill(peerCertificate, (byte) 0xbb);
        require(proxy.hasSigningCertificate("guest.pkg", callerCertificate, 0),
                "current Guest certificate matches virtual signature");
        require(!proxy.hasSigningCertificate("peer.pkg", callerCertificate, 0),
                "cross-package certificate does not reuse caller signature");
        require(proxy.hasSigningCertificate("peer.pkg", peerCertificate, 0),
                "visible cross-package certificate uses target signature");
        require(delegate.calls == 0, "signature queries avoid host PackageManager");
    }

    private static void testActivityManagerHistory(GuestIdentity identity) {
        FakeActivityManagerHistory delegate = new FakeActivityManagerHistory();
        for (String serviceName : List.of("activityManager", "activity-manager")) {
            FakeActivityManagerHistoryApi proxy = (FakeActivityManagerHistoryApi)
                    Proxy.newProxyInstance(FrameworkIdentityProxySelfTest.class.getClassLoader(),
                            new Class<?>[]{FakeActivityManagerHistoryApi.class},
                            new SystemServiceInvocationHandler(delegate, identity, serviceName));
            require(proxy.getHistoricalProcessExitReasons("guest.pkg", 12001, 10, 0).isEmpty(),
                    "activity-manager exit history is Guest-owned and empty: " + serviceName);
        }
        require(delegate.calls == 0, "activity-manager history never delegates Host DUMP data");
    }

    private static void testHookReadinessPolicy() {
        FrameworkSignatureAudit api35Assistant = FrameworkSignatureAudit.inspect(
                FrameworkServiceSpec.activityTaskManager(), java.util.List.of(Api35AssistantApi.class));
        require(api35Assistant.passed(), "API 35 eight-argument assistant activity signature is supported");
        FrameworkSignatureAudit api32Caller = FrameworkSignatureAudit.inspect(
                FrameworkServiceSpec.activityTaskManager(), java.util.List.of(Api32CallerApi.class));
        require(api32Caller.passed(), "API 32 thirteen-argument caller signature is supported");
        FrameworkSignatureAudit api35Caller = FrameworkSignatureAudit.inspect(
                FrameworkServiceSpec.activityTaskManager(), java.util.List.of(Api35CallerApi.class));
        require(api35Caller.passed(), "API 35 twelve-argument caller signature is supported");
        FrameworkSignatureAudit unsupportedCaller = FrameworkSignatureAudit.inspect(
                FrameworkServiceSpec.activityTaskManager(), java.util.List.of(UnsupportedCallerApi.class));
        require(!unsupportedCaller.passed(), "unsupported ATM caller signature fails closed");
        require("android.app.IActivityTaskManager".equals(
                        FrameworkServiceSpec.activityTaskManager().expectedDescriptor()),
                "ATM Binder descriptor contract is explicit");
        try {
            FrameworkProxyInstaller.validateBinderDescriptorForTest(
                    new android.os.Binder() {
                        @Override public String getInterfaceDescriptor() {
                            return "android.app.IActivityTaskManager";
                        }
                    }, "android.app.IActivityTaskManager", "activity-task-manager");
        } catch (Exception error) {
            throw new AssertionError("valid ATM Binder descriptor rejected", error);
        }
        boolean invalidDescriptor = false;
        try {
            FrameworkProxyInstaller.validateBinderDescriptorForTest(
                    new android.os.Binder() {
                        @Override public String getInterfaceDescriptor() { return "wrong.descriptor"; }
                    }, "android.app.IActivityTaskManager", "activity-task-manager");
        } catch (Exception expected) {
            invalidDescriptor = expected.getMessage().contains("Unexpected Binder descriptor");
        }
        require(invalidDescriptor, "invalid ATM Binder descriptor fails closed");

        java.util.Map<String, Boolean> allInstalled = new java.util.LinkedHashMap<>();
        for (String name : java.util.List.of("packageManager", "activityManager", "activityTaskManager",
                "appOps", "permission", "notification", "jobScheduler", "alarm", "clipboard",
                "account", "storage", "inputManager", "captioning")) allInstalled.put(name, true);
        FrameworkHookReport ready = new FrameworkHookReport(allInstalled, java.util.Map.of());
        require(ready.readiness() == FrameworkHookReport.Readiness.READY, "mandatory hook ready");
        ready.requireMandatoryReady();

        java.util.Map<String, String> optionalFailure = java.util.Map.of(
                "bluetooth", "java.lang.IllegalStateException:not available");
        FrameworkHookReport degraded = new FrameworkHookReport(allInstalled, optionalFailure);
        require(degraded.readiness() == FrameworkHookReport.Readiness.DEGRADED,
                "non-core hook failure is degraded");
        degraded.requireMandatoryReady();

        java.util.Map<String, Boolean> missingMandatory = new java.util.LinkedHashMap<>(allInstalled);
        missingMandatory.put("notification", false);
        FrameworkHookReport blocked = new FrameworkHookReport(missingMandatory,
                java.util.Map.of("notification", "java.lang.IllegalStateException:signature mismatch"));
        require(blocked.readiness() == FrameworkHookReport.Readiness.BLOCKED,
                "mandatory hook failure is blocked");
        boolean rejected = false;
        try { blocked.requireMandatoryReady(); }
        catch (IllegalStateException expected) { rejected = expected.getMessage().contains("notification"); }
        require(rejected, "mandatory hook failure rejects guest prepare");
    }

    private static String repeat(char value) {
        StringBuilder result = new StringBuilder(64);
        for (int index = 0; index < 64; index++) result.append(value);
        return result.toString();
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    interface FakeApi {
        String note(String packageName, int uid);
        void attribution(FakeAttribution source);
        void wrappedAttribution(FakeAttributionContext source);
        String[] packages();
    }

    interface Api35AssistantApi {
        void startAssistantActivity(String packageName, String featureId, int callingPid,
                int callingUid, android.content.Intent intent, String resolvedType,
                android.os.Bundle options, int userId);
    }

    interface Api32CallerApi {
        void startActivityAsCaller(Object caller, String callingPackage,
                android.content.Intent intent, String resolvedType, android.os.IBinder resultTo,
                String resultWho, int requestCode, int flags, Object profilerInfo,
                android.os.Bundle options, android.os.IBinder permissionToken,
                boolean ignoreTargetSecurity, int userId);
    }

    interface Api35CallerApi {
        void startActivityAsCaller(Object caller, String callingPackage,
                android.content.Intent intent, String resolvedType, android.os.IBinder resultTo,
                String resultWho, int requestCode, int flags, Object profilerInfo,
                android.os.Bundle options, boolean ignoreTargetSecurity, int userId);
    }

    interface UnsupportedCallerApi {
        void startActivityAsCaller(Object caller, String callingPackage,
                android.content.Intent intent, String resolvedType, android.os.IBinder resultTo,
                String resultWho, int requestCode, int flags, Object profilerInfo,
                android.os.Bundle options, android.os.IBinder permissionToken,
                boolean ignoreTargetSecurity, int userId, String unsupportedTail);
    }

    static final class FakeService implements FakeApi {
        String lastPackage;
        int lastUid;
        String lastTailPackage;
        int lastTailUid;
        String lastAttributionTag;
        String lastTailTag;
        @Override public String note(String packageName, int uid) {
            lastPackage = packageName;
            lastUid = uid;
            return packageName;
        }
        @Override public void attribution(FakeAttribution source) {
            lastPackage = source.mPackageName;
            lastUid = source.mUid;
            lastAttributionTag = source.attributionTag;
            if (source.mNext != null) {
                lastTailPackage = source.mNext.mPackageName;
                lastTailUid = source.mNext.mUid;
                lastTailTag = source.mNext.attributionTag;
            }
        }
        @Override public void wrappedAttribution(FakeAttributionContext source) {
            lastPackage = source.mAttributionSourceState.packageName;
            lastUid = source.mAttributionSourceState.uid;
        }
        @Override public String[] packages() { return new String[]{"host.pkg"}; }
    }

    public interface FakePackageApi {
        ApplicationInfo getApplicationInfo(String packageName, long flags);
        PackageInfo getPackageInfo(String packageName, long flags);
        ActivityInfo getActivityInfo(ComponentName component, long flags);
        ResolveInfo resolveIntent(Intent intent, String resolvedType, long flags, int userId);
        List<ResolveInfo> queryIntentServices(Intent intent, String resolvedType, long flags, int userId);
        String[] getPackagesForUid(int uid);
        List<ApplicationInfo> getInstalledApplications(long flags, int userId);
        int checkPermission(String permission, String packageName, int userId);
        boolean hasSigningCertificate(String packageName, byte[] certificate, int type);
        String unhandled(String packageName);
    }

    public static final class FakePackageService implements FakePackageApi {
        int calls;
        String lastPackage;
        private <T> T called(T value) { calls++; return value; }
        @Override public ApplicationInfo getApplicationInfo(String packageName, long flags) { return called(new ApplicationInfo()); }
        @Override public PackageInfo getPackageInfo(String packageName, long flags) { return called(new PackageInfo()); }
        @Override public ActivityInfo getActivityInfo(ComponentName component, long flags) { return called(new ActivityInfo()); }
        @Override public ResolveInfo resolveIntent(Intent intent, String resolvedType, long flags, int userId) { return called(null); }
        @Override public List<ResolveInfo> queryIntentServices(Intent intent, String resolvedType, long flags, int userId) { return called(List.of()); }
        @Override public String[] getPackagesForUid(int uid) { return called(new String[]{"host.pkg"}); }
        @Override public List<ApplicationInfo> getInstalledApplications(long flags, int userId) { return called(List.of()); }
        @Override public int checkPermission(String permission, String packageName, int userId) { return called(-99); }
        @Override public boolean hasSigningCertificate(String packageName, byte[] certificate, int type) {
            return called(Boolean.FALSE);
        }
        @Override public String unhandled(String packageName) { lastPackage = packageName; return called("host-result"); }
    }

    interface FakePermissionApi {
        int checkPermission(String permission, String packageName, int uid);
        void grantRuntimePermission(String packageName, String permission, int userId);
    }

    static final class FakePermissionService implements FakePermissionApi {
        int calls;
        @Override public int checkPermission(String permission, String packageName, int uid) {
            calls++; return -99;
        }
        @Override public void grantRuntimePermission(String packageName, String permission, int userId) {
            calls++;
        }
    }

    interface FakeActivityManagerHistoryApi {
        List<Object> getHistoricalProcessExitReasons(String packageName, int uid,
                                                      int maxNum, int userId);
    }

    static final class FakeActivityManagerHistory implements FakeActivityManagerHistoryApi {
        int calls;
        @Override public List<Object> getHistoricalProcessExitReasons(String packageName, int uid,
                                                                       int maxNum, int userId) {
            calls++;
            return List.of(new Object());
        }
    }

    interface FakeAppOpsApi {
        int checkOperation(String opName, int uid, String packageName);
        int checkOperation(int opCode, int uid, String packageName);
        int noteProxyOperation(String opName, FakeAttribution source);
        void checkPackage(int uid, String packageName);
        List<Object> getOpsForPackage(int uid, String packageName, int[] operations);
        void setMode(int op, int uid, String packageName, int mode);
    }

    static final class FakeAppOpsService implements FakeAppOpsApi {
        int calls;
        @Override public int checkOperation(String opName, int uid, String packageName) {
            calls++; return -99;
        }
        @Override public int checkOperation(int opCode, int uid, String packageName) {
            calls++; return -99;
        }
        @Override public int noteProxyOperation(String opName, FakeAttribution source) {
            calls++; return -99;
        }
        @Override public void checkPackage(int uid, String packageName) {
            calls++;
        }
        @Override public List<Object> getOpsForPackage(int uid, String packageName, int[] operations) {
            calls++; return List.of(new Object());
        }
        @Override public void setMode(int op, int uid, String packageName, int mode) {
            calls++;
        }
    }


    static final class FakeAttributionContext {
        FakeAttributionState mAttributionSourceState;
        FakeAttributionContext(FakeAttributionState state) { mAttributionSourceState = state; }
    }

    static final class FakeAttributionState {
        String packageName;
        int uid;
        FakeAttributionState(String packageName, int uid) {
            this.packageName = packageName;
            this.uid = uid;
        }
    }

    static final class FakeAttribution {
        String mPackageName;
        int mUid;
        String attributionTag;
        FakeAttribution mNext;
        FakeAttribution(String packageName, int uid) { this(packageName, uid, "", null); }
        FakeAttribution(String packageName, int uid, String tag, FakeAttribution next) {
            mPackageName = packageName; mUid = uid; attributionTag = tag; mNext = next;
        }
    }
}
