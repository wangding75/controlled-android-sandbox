package com.warden.controlledsandbox.framework.packagemanager;

import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import com.warden.controlledsandbox.framework.identity.SandboxAppOpsPolicy;
import com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata;
import com.warden.controlledsandbox.framework.identity.VirtualPermissionPolicy;

import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Set;

public final class PackageVisibilityPolicySelfTest {
    public static void main(String[] args) throws Exception {
        GuestIdentity identity = identity();
        require(PackageVisibilityPolicy.classify(identity, "guest.pkg")
                        == PackageVisibilityClass.GUEST_OWNED,
                "guest package is GUEST_OWNED");
        require(PackageVisibilityPolicy.classify(identity, "android")
                        == PackageVisibilityClass.SYSTEM_PROJECTED,
                "android framework package is SYSTEM_PROJECTED");
        require(PackageVisibilityPolicy.classify(identity, "com.android.permissioncontroller")
                        == PackageVisibilityClass.SYSTEM_PROJECTED,
                "permission controller is SYSTEM_PROJECTED");
        require(PackageVisibilityPolicy.classify(identity, "com.google.android.gms")
                        == PackageVisibilityClass.SYSTEM_DEPENDENCY_PROJECTED,
                "Play Services is a system dependency role, not an app special case");
        require(PackageVisibilityPolicy.classify(identity, "com.android.vending")
                        == PackageVisibilityClass.SYSTEM_DEPENDENCY_PROJECTED,
                "Play Store is a system dependency role");
        require(PackageVisibilityPolicy.classify(identity, "host.pkg")
                        == PackageVisibilityClass.EXPLICITLY_DENIED,
                "host package is EXPLICITLY_DENIED");
        require(PackageVisibilityPolicy.classify(identity, "com.example.otherapp")
                        == PackageVisibilityClass.HOST_USER_APP_HIDDEN,
                "ordinary host user app is HOST_USER_APP_HIDDEN");
        require(PackageVisibilityPolicy.classify(identity, "") == null, "empty target is unclassified");

        FakePackageService delegate = new FakePackageService();
        FakePackageApi proxy = (FakePackageApi) Proxy.newProxyInstance(
                PackageVisibilityPolicySelfTest.class.getClassLoader(),
                new Class<?>[]{FakePackageApi.class},
                PackageManagerInvocationHandlerTestAccess.create(delegate, identity));

        ApplicationInfo guest = proxy.getApplicationInfo("guest.pkg", 0);
        require(guest != null && "guest.pkg".equals(guest.packageName), "guest identity remains virtual");

        require(proxy.getPackageInfo("com.google.android.gms", 0) == null,
                "system dependency getPackageInfo is NameNotFound-shaped");
        require(proxy.getApplicationInfo("com.google.android.gms", 0) == null,
                "system dependency getApplicationInfo is NameNotFound-shaped");
        require(!proxy.isPackageAvailable("com.google.android.gms"),
                "system dependency isPackageAvailable is false");
        require(proxy.getPackageUid("com.google.android.gms", 0) == -1,
                "system dependency uid is absent");

        require(proxy.getPackageInfo("android", 0) == null,
                "unprojected SYSTEM_PROJECTED package is absent, not Host-leaked");

        require(proxy.getApplicationInfo("host.pkg", 0) == null,
                "host package getApplicationInfo is NameNotFound-shaped");
        require(proxy.getPackageInfo("host.pkg", 0) == null,
                "host package getPackageInfo is NameNotFound-shaped");
        require(proxy.getActivityInfo(new ComponentName("host.pkg", "host.pkg.Main"), 0) == null,
                "host package getActivityInfo is NameNotFound-shaped");
        require(proxy.getReceiverInfo(new ComponentName("host.pkg", "host.pkg.Recv"), 0) == null,
                "host package getReceiverInfo is NameNotFound-shaped");
        require(proxy.getProviderInfo(new ComponentName("host.pkg", "host.pkg.Prov"), 0) == null,
                "host package getProviderInfo is NameNotFound-shaped");

        require(proxy.getApplicationInfo("com.example.otherapp", 0) == null,
                "ordinary host user app getApplicationInfo is NameNotFound-shaped");
        require(proxy.getPackageInfo("com.example.otherapp", 0) == null,
                "ordinary host user app getPackageInfo is NameNotFound-shaped");
        require(proxy.getActivityInfo(new ComponentName("com.example.otherapp", "X"), 0) == null,
                "ordinary host user app getActivityInfo is NameNotFound-shaped");
        require(!proxy.isPackageAvailable("com.example.otherapp"),
                "hidden user app isPackageAvailable is false");
        require(proxy.getPackageUid("com.example.otherapp", 0) == -1,
                "hidden user app uid is absent");

        require(proxy.getServiceInfo(new ComponentName("host.pkg", "host.pkg.Stub"), 0) == null,
                "host stub service probe is NameNotFound-shaped");
        boolean mutationBlocked = false;
        try { proxy.setComponentEnabledSetting(new ComponentName("host.pkg", "host.pkg.Stub"), 1); }
        catch (SecurityException expected) {
            mutationBlocked = expected.getMessage().contains("HOST_PACKAGE_MUTATION_BLOCKED");
        }
        require(mutationBlocked, "hidden package mutation remains SecurityException");
        require(delegate.calls == 0,
                "classified identity queries do not touch Host PackageManager");
        System.out.println("PASS package visibility policy self-test");
    }

    private static GuestIdentity identity() {
        ApplicationInfo info = new ApplicationInfo();
        info.packageName = "guest.pkg";
        info.uid = 12001;
        VirtualPackageMetadata metadata = new VirtualPackageMetadata("guest.pkg", "guest.pkg.MainActivity", info,
                Arrays.asList(
                        new VirtualPackageMetadata.Component(VirtualPackageMetadata.Type.ACTIVITY,
                                "guest.pkg.MainActivity", "guest.pkg", true, true, false,
                                Set.of("android.intent.action.MAIN"), "")));
        return new GuestIdentity("guest.pkg", 12001, info,
                Set.of("android.permission.INTERNET"),
                "host.pkg", 10001, metadata, "guest.pkg", 0, 1L,
                new VirtualPermissionPolicy(Set.of("android.permission.INTERNET"), java.util.Map.of()),
                new SandboxAppOpsPolicy(java.util.Map.of()));
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    public interface FakePackageApi {
        ApplicationInfo getApplicationInfo(String packageName, long flags);
        PackageInfo getPackageInfo(String packageName, long flags);
        android.content.pm.ActivityInfo getActivityInfo(ComponentName component, long flags);
        android.content.pm.ServiceInfo getServiceInfo(ComponentName component, long flags);
        android.content.pm.ActivityInfo getReceiverInfo(ComponentName component, long flags);
        android.content.pm.ProviderInfo getProviderInfo(ComponentName component, long flags);
        boolean isPackageAvailable(String packageName);
        int getPackageUid(String packageName, long flags);
        void setComponentEnabledSetting(ComponentName component, int newState);
    }

    public static final class FakePackageService implements FakePackageApi {
        int calls;
        @Override public ApplicationInfo getApplicationInfo(String packageName, long flags) {
            calls++;
            return new ApplicationInfo();
        }
        @Override public PackageInfo getPackageInfo(String packageName, long flags) {
            calls++;
            return new PackageInfo();
        }
        @Override public android.content.pm.ActivityInfo getActivityInfo(ComponentName component, long flags) {
            calls++;
            return new android.content.pm.ActivityInfo();
        }
        @Override public android.content.pm.ServiceInfo getServiceInfo(ComponentName component, long flags) {
            calls++;
            return new android.content.pm.ServiceInfo();
        }
        @Override public android.content.pm.ActivityInfo getReceiverInfo(ComponentName component, long flags) {
            calls++;
            return new android.content.pm.ActivityInfo();
        }
        @Override public android.content.pm.ProviderInfo getProviderInfo(ComponentName component, long flags) {
            calls++;
            return new android.content.pm.ProviderInfo();
        }
        @Override public void setComponentEnabledSetting(ComponentName component, int newState) {
            calls++;
        }
        @Override public boolean isPackageAvailable(String packageName) {
            calls++;
            return true;
        }
        @Override public int getPackageUid(String packageName, long flags) {
            calls++;
            return 10001;
        }
    }
}
