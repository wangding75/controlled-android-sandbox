package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata;
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
        GuestIdentity identity = new GuestIdentity("guest.pkg", 12001, info, Set.of("camera"),
                "host.pkg", 10001, metadata);
        testIdentityRewriting(identity);
        testPackageQueries(identity);
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
        FakeAttribution attribution = new FakeAttribution("guest.pkg", 12001);
        proxy.attribution(attribution);
        require("host.pkg".equals(delegate.lastPackage) && delegate.lastUid == 10001,
                "nested attribution rewrite");
        require("guest.pkg".equals(attribution.mPackageName) && attribution.mUid == 12001,
                "nested attribution restored");
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
        PackageInfo packageInfo = proxy.getPackageInfo("guest.pkg", 0);
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
        require(delegate.calls == 0, "virtual queries avoid host delegate");
        require("host-result".equals(proxy.unhandled("guest.pkg")), "fallback delegates with host identity");
        require("host.pkg".equals(delegate.lastPackage), "fallback package rewritten");
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
        String[] packages();
    }

    static final class FakeService implements FakeApi {
        String lastPackage;
        int lastUid;
        @Override public String note(String packageName, int uid) {
            lastPackage = packageName;
            lastUid = uid;
            return packageName;
        }
        @Override public void attribution(FakeAttribution source) {
            lastPackage = source.mPackageName;
            lastUid = source.mUid;
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
        @Override public String unhandled(String packageName) { lastPackage = packageName; return called("host-result"); }
    }

    static final class FakeAttribution {
        String mPackageName;
        int mUid;
        FakeAttribution(String packageName, int uid) { mPackageName = packageName; mUid = uid; }
    }
}
