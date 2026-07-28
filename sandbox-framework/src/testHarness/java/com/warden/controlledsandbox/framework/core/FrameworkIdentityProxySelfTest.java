package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata;
import com.warden.controlledsandbox.framework.identity.VirtualPermissionPolicy;
import com.warden.controlledsandbox.framework.identity.SandboxAppOpsPolicy;
import com.warden.controlledsandbox.framework.packagemanager.PackageManagerInvocationHandlerTestAccess;

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
        testPermissionAndAppOps(identity);
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
        boolean hostHidden = false;
        try { proxy.getApplicationInfo("host.pkg", 0); }
        catch (IllegalArgumentException expected) { hostHidden = expected.getMessage().contains("HOST_PACKAGE_HIDDEN"); }
        require(hostHidden, "host package identity hidden");
        ResolveInfo hiddenHostResolve = proxy.resolveIntent(
                new Intent().setPackage("host.pkg"), null, 0, 0);
        require(hiddenHostResolve == null, "explicit host Intent is hidden");
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
        require("proxy".equals(source.attributionTag), "proxy attributionTag remains unchanged");
        require(appOpsDelegate.calls == 0, "AppOps virtual decision avoids host delegate");
    }

    private static void testHookReadinessPolicy() {
        java.util.Map<String, Boolean> allInstalled = new java.util.LinkedHashMap<>();
        for (String name : java.util.List.of("packageManager", "activityManager", "activityTaskManager",
                "appOps", "permission", "notification")) allInstalled.put(name, true);
        FrameworkHookReport ready = new FrameworkHookReport(allInstalled, java.util.Map.of());
        require(ready.readiness() == FrameworkHookReport.Readiness.READY, "mandatory hook ready");
        ready.requireMandatoryReady();

        java.util.Map<String, String> optionalFailure = java.util.Map.of(
                "notification", "java.lang.IllegalStateException:not available");
        FrameworkHookReport degraded = new FrameworkHookReport(allInstalled, optionalFailure);
        require(degraded.readiness() == FrameworkHookReport.Readiness.DEGRADED,
                "optional hook failure is degraded");
        degraded.requireMandatoryReady();

        java.util.Map<String, Boolean> missingMandatory = new java.util.LinkedHashMap<>(allInstalled);
        missingMandatory.put("activityTaskManager", false);
        FrameworkHookReport blocked = new FrameworkHookReport(missingMandatory,
                java.util.Map.of("activityTaskManager", "java.lang.IllegalStateException:signature mismatch"));
        require(blocked.readiness() == FrameworkHookReport.Readiness.BLOCKED,
                "mandatory hook failure is blocked");
        boolean rejected = false;
        try { blocked.requireMandatoryReady(); }
        catch (IllegalStateException expected) { rejected = expected.getMessage().contains("activityTaskManager"); }
        require(rejected, "mandatory hook failure rejects guest prepare");
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

    interface FakeAppOpsApi {
        int checkOperation(String opName, int uid, String packageName);
        int checkOperation(int opCode, int uid, String packageName);
        int noteProxyOperation(String opName, FakeAttribution source);
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
