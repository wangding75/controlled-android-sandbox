package com.warden.controlledsandbox.framework.packagemanager;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;

import com.warden.controlledsandbox.framework.capability.CapabilityAuditSink;
import com.warden.controlledsandbox.framework.capability.CapabilityLeaseRegistry;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import com.warden.controlledsandbox.framework.identity.SandboxAppOpsPolicy;
import com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata;
import com.warden.controlledsandbox.framework.identity.VirtualPackageUniverse;
import com.warden.controlledsandbox.framework.identity.VirtualPermissionPolicy;
import com.warden.controlledsandbox.framework.identity.VirtualSystemServiceState;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Regression coverage for hidden PackageManager query signatures used by modern Apps. */
public final class PackageManagerQueryVariantsSelfTest {
    public static void main(String[] args) {
        ApplicationInfo guestInfo = application("guest.pkg", 12001, 33);
        ApplicationInfo peerInfo = application("peer.pkg", 12002, 33);
        VirtualPackageMetadata guest = new VirtualPackageMetadata("guest.pkg", "guest.pkg.Main",
                guestInfo, List.of(
                activity("guest.pkg.Main", "guest.pkg", Set.of("android.intent.action.VIEW")),
                provider("guest.pkg.DataProvider", "guest.pkg:provider", "guest.data", 4),
                provider("guest.pkg.BootstrapProvider", "guest.pkg:provider", "guest.bootstrap", 12)));
        VirtualPackageMetadata peer = new VirtualPackageMetadata("peer.pkg", "peer.pkg.Share",
                peerInfo, List.of(
                activity("peer.pkg.Share", "peer.pkg", Set.of("android.intent.action.VIEW")),
                provider("peer.pkg.DataProvider", "peer.pkg:provider", "peer.data", 1)));
        VirtualPackageMetadata peerWithPermissions = new VirtualPackageMetadata("peer.pkg",
                "peer.pkg.Share", peerInfo, peer.components(), "", 0L, "peer-signature",
                0L, 0L, "", List.of(), List.of(), List.of(), List.of(), true,
                Set.of(), Set.of(), List.of(), Map.of(),
                List.of(new VirtualPackageMetadata.PermissionDeclaration(
                        "peer.pkg.permission.SEND", "peer.pkg.permission.GROUP", "Send",
                        "Send data", 0x7f010001, 0x7f010002, 0x7f020001, 1, 0, true)),
                List.of(new VirtualPackageMetadata.PermissionGroup(
                        "peer.pkg.permission.GROUP", "Peer", "Peer permissions", 0x7f010003,
                        0x7f010004, 0x7f020002, 0x7f010005, 10, 0)));
        // The explicit query package makes the peer visible under Android 11+ package-visibility
        // rules. All query variants below must use this same visibility graph.
        VirtualPackageMetadata guestWithQueries = new VirtualPackageMetadata("guest.pkg",
                "guest.pkg.Main", guestInfo, guest.components(), "", 0L, "guest-signature",
                0L, 0L, "", List.of(), List.of(), List.of(), List.of(), true,
                Set.of("peer.pkg"), Set.of(), List.of());
        VirtualPackageUniverse universe = new VirtualPackageUniverse(
                List.of(guestWithQueries, peerWithPermissions));
        GuestIdentity identity = new GuestIdentity("guest.pkg", 12001, guestInfo,
                Set.of(), "host.pkg", 10001, guestWithQueries, "guest.pkg", 0, 1L,
                new VirtualPermissionPolicy(Set.of(), Map.of()),
                new SandboxAppOpsPolicy(Map.of()), CapabilityAuditSink.NO_OP,
                new CapabilityLeaseRegistry(), new VirtualSystemServiceState(), "query-variants",
                universe);
        FakePackageApi proxy = (FakePackageApi) Proxy.newProxyInstance(
                PackageManagerQueryVariantsSelfTest.class.getClassLoader(),
                new Class<?>[]{FakePackageApi.class},
                PackageManagerInvocationHandlerTestAccess.create(new FakePackageApiDelegate(), identity));

        List<ResolveInfo> activities = proxy.queryIntentActivitiesAsUser(
                new Intent("android.intent.action.VIEW"), null, 0L, 0);
        require(activities.size() == 2, "AsUser activity query returns visible virtual packages");

        Intent peerExplicit = new Intent().setComponent(
                new ComponentName("peer.pkg", "peer.pkg.Share"));
        List<ResolveInfo> options = proxy.queryIntentActivityOptions(
                null, new Intent[]{peerExplicit}, new Intent("android.intent.action.VIEW"),
                null, 0L, 0);
        require(options.size() == 2, "activity options preserve specific and generic results");
        require(options.get(0).activityInfo != null
                        && "peer.pkg.Share".equals(options.get(0).activityInfo.name),
                "activity options put the specific result first");

        Intent peerProvider = new Intent().setComponent(
                new ComponentName("peer.pkg", "peer.pkg.DataProvider"));
        List<ResolveInfo> providers = proxy.queryIntentContentProviders(peerProvider, null, 0L, 0);
        require(providers.size() == 1 && providers.get(0).providerInfo != null
                        && "peer.pkg.DataProvider".equals(providers.get(0).providerInfo.name),
                "content-provider intent query projects ProviderInfo through PMS");
        require(proxy.activitySupportsIntent(new ComponentName("peer.pkg", "peer.pkg.Share"),
                        peerExplicit, 0L),
                "activitySupportsIntent accepts an explicit visible Activity");
        require(!proxy.activitySupportsIntent(new ComponentName("peer.pkg", "peer.pkg.Share"),
                        new Intent("android.intent.action.SEND"), 0L),
                "activitySupportsIntent rejects an unmatched implicit action");
        PermissionInfo permissionInfo = proxy.getPermissionInfo("peer.pkg.permission.SEND", 0);
        require(permissionInfo != null
                        && "peer.pkg.permission.SEND".equals(permissionInfo.name)
                        && "peer.pkg".equals(permissionInfo.packageName)
                        && "peer.pkg.permission.GROUP".equals(permissionInfo.group)
                        && permissionInfo.protectionLevel == 1,
                "getPermissionInfo projects a visible custom permission");
        PermissionInfo platformPermission = proxy.getPermissionInfo("android.permission.INTERNET", 0);
        require(platformPermission != null
                        && "android.permission.INTERNET".equals(platformPermission.name)
                        && "android".equals(platformPermission.packageName),
                "getPermissionInfo preserves the platform permission catalog");
        List<PermissionInfo> groupedPermissions = proxy.queryPermissionsByGroup(
                "peer.pkg.permission.GROUP", 0);
        require(groupedPermissions.size() == 1
                        && "peer.pkg.permission.SEND".equals(groupedPermissions.get(0).name),
                "queryPermissionsByGroup projects the visible permission declaration");
        List<PermissionInfo> platformPermissions = proxy.queryPermissionsByGroup(
                "android.permission-group.CONTACTS", 0);
        require(platformPermissions.size() == 1
                        && "android.permission.READ_CONTACTS".equals(platformPermissions.get(0).name),
                "queryPermissionsByGroup preserves platform permission groups");
        PermissionGroupInfo groupInfo = proxy.getPermissionGroupInfo(
                "peer.pkg.permission.GROUP", 0);
        require(groupInfo != null && "peer.pkg".equals(groupInfo.packageName)
                        && groupInfo.priority == 10,
                "getPermissionGroupInfo projects the visible permission group");
        PermissionGroupInfo platformGroup = proxy.getPermissionGroupInfo(
                "android.permission-group.CONTACTS", 0);
        require(platformGroup != null && "android".equals(platformGroup.packageName),
                "getPermissionGroupInfo preserves the platform group catalog");
        List<PermissionGroupInfo> groups = proxy.getAllPermissionGroups(0);
        require(groups.size() == 2
                        && groups.stream().anyMatch(group ->
                        "peer.pkg.permission.GROUP".equals(group.name))
                        && groups.stream().anyMatch(group ->
                        "android.permission-group.CONTACTS".equals(group.name)),
                "getAllPermissionGroups merges virtual and platform inventories");
        require(proxy.getPermissionInfo("hidden.pkg.permission.X", 0) == null,
                "unknown custom permission is fail-closed without Host delegation");
        PackageInfo peerPackage = proxy.getPackageInfo("peer.pkg", PackageManager.GET_PERMISSIONS);
        require(peerPackage.permissions != null && peerPackage.permissions.length == 1
                        && "peer.pkg.permission.SEND".equals(peerPackage.permissions[0].name)
                        && "peer.pkg.permission.GROUP".equals(peerPackage.permissions[0].group),
                "GET_PERMISSIONS projects PackageInfo.permissions declarations");
        require(peerWithPermissions.permissionDeclarations().get(0).tree(),
                "permission-tree metadata survives the framework-neutral PMS model");

        List<ProviderInfo> currentProcess = proxy.queryContentProviders(
                "guest.pkg:provider", 12001, 0, 0);
        require(currentProcess.size() == 2
                        && "guest.pkg.BootstrapProvider".equals(currentProcess.get(0).name)
                        && "guest.pkg.DataProvider".equals(currentProcess.get(1).name),
                "provider inventory honors process/UID filters and descending initOrder");
        List<ProviderInfo> currentUid = proxy.queryContentProviders(null, 12001, 0, 0);
        require(currentUid.size() == 2
                        && "guest.pkg.BootstrapProvider".equals(currentUid.get(0).name),
                "provider inventory honors UID when process is unspecified");

        List<PackageInfo> packages = proxy.getInstalledPackagesAsUser(0L, 0);
        require(packages.size() == 2, "installed-package AsUser query uses virtual inventory");

        require(proxy.getComponentEnabledSetting(new ComponentName(
                        "peer.pkg", "peer.pkg.Share")) == 0,
                "visible peer component state must come from the virtual PMS");
        proxy.setComponentEnabledSetting(new ComponentName("guest.pkg", "guest.pkg.Main"), 2);
        require(proxy.getComponentEnabledSetting(new ComponentName(
                        "guest.pkg", "guest.pkg.Main")) == 2,
                "current Guest component state must remain virtual and mutable");
        try {
            proxy.setComponentEnabledSetting(new ComponentName("peer.pkg", "peer.pkg.Share"), 2);
            throw new AssertionError("cross-package component mutation was accepted");
        } catch (SecurityException expected) {
            // Package visibility is a read edge; it is not a write capability.
        }
        require(proxy.getComponentEnabledSetting(new ComponentName(
                        "host.pkg", "host.pkg.Stub")) == 0,
                "hidden Host component state must fail closed without Host delegation");

        VirtualPackageMetadata disabled = new VirtualPackageMetadata("disabled.pkg",
                "disabled.pkg.Main", application("disabled.pkg", 12003, 33),
                List.of(activity("disabled.pkg.Main", "disabled.pkg", Set.of(
                        "android.intent.action.VIEW"))), "", 0L, "disabled-signature",
                0L, 0L, "", List.of(), List.of(), List.of(), List.of(), false,
                Set.of(), Set.of(), List.of());
        VirtualPackageUniverse disabledUniverse = VirtualPackageUniverse.single(disabled);
        GuestIdentity disabledIdentity = new GuestIdentity("disabled.pkg", 12003,
                disabled.applicationInfo(), Set.of(), "host.pkg", 10001, disabled,
                "disabled.pkg", 0, 1L, new VirtualPermissionPolicy(Set.of(), Map.of()),
                new SandboxAppOpsPolicy(Map.of()), CapabilityAuditSink.NO_OP,
                new CapabilityLeaseRegistry(), new VirtualSystemServiceState(), "disabled",
                disabledUniverse);
        FakePackageApi disabledProxy = (FakePackageApi) Proxy.newProxyInstance(
                PackageManagerQueryVariantsSelfTest.class.getClassLoader(),
                new Class<?>[]{FakePackageApi.class},
                PackageManagerInvocationHandlerTestAccess.create(
                        new FakePackageApiDelegate(), disabledIdentity));
        require(!disabledProxy.getApplicationInfo("disabled.pkg", 0).enabled,
                "disabled package projects ApplicationInfo.enabled=false");
        require(!disabledProxy.getPackageInfo("disabled.pkg", 0).applicationInfo.enabled,
                "disabled package projects PackageInfo.applicationInfo.enabled=false");
        require(disabledProxy.getApplicationEnabledSetting("disabled.pkg", 0) == 2,
                "disabled package returns virtual DISABLED setting");
        System.out.println("PASS package-manager query variants self-test");
    }

    private static ApplicationInfo application(String packageName, int uid, int targetSdk) {
        ApplicationInfo info = new ApplicationInfo();
        info.packageName = packageName;
        info.uid = uid;
        info.targetSdkVersion = targetSdk;
        return info;
    }

    private static VirtualPackageMetadata.Component activity(String name, String process,
                                                               Set<String> actions) {
        return new VirtualPackageMetadata.Component(VirtualPackageMetadata.Type.ACTIVITY, name,
                process, true, true, false, actions, "");
    }

    private static VirtualPackageMetadata.Component provider(String name, String process,
                                                               String authority, int initOrder) {
        return new VirtualPackageMetadata.Component(VirtualPackageMetadata.Type.PROVIDER, name,
                process, true, true, false, Set.of(), authority, "", "", "", false, "DEFAULT",
                List.of(), List.of(), null, "standard", "", "none", 0, "", 0, 0, false,
                false, false, false, false, false, "", 0f, 0f, false, 0, 0, false, false,
                false, initOrder, false, "never");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    public interface FakePackageApi {
        List<ResolveInfo> queryIntentActivitiesAsUser(Intent intent, String resolvedType,
                                                        long flags, int userId);
        List<ResolveInfo> queryIntentActivityOptions(ComponentName caller, Intent[] specifics,
                                                      Intent intent, String resolvedType,
                                                      long flags, int userId);
        List<ResolveInfo> queryIntentContentProviders(Intent intent, String resolvedType,
                                                       long flags, int userId);
        List<ProviderInfo> queryContentProviders(String processName, int uid, int flags,
                                                  int userId);
        List<PackageInfo> getInstalledPackagesAsUser(long flags, int userId);
        int getComponentEnabledSetting(ComponentName component);
        void setComponentEnabledSetting(ComponentName component, int newState);
        boolean activitySupportsIntent(ComponentName component, Intent intent, long flags);
        PermissionInfo getPermissionInfo(String name, int flags);
        List<PermissionInfo> queryPermissionsByGroup(String group, int flags);
        PermissionGroupInfo getPermissionGroupInfo(String name, int flags);
        List<PermissionGroupInfo> getAllPermissionGroups(int flags);
        ApplicationInfo getApplicationInfo(String packageName, int flags);
        PackageInfo getPackageInfo(String packageName, int flags);
        int getApplicationEnabledSetting(String packageName, int userId);
    }

    public static final class FakePackageApiDelegate implements FakePackageApi {
        @Override public List<ResolveInfo> queryIntentActivitiesAsUser(Intent intent,
                String resolvedType, long flags, int userId) { throw new AssertionError("delegate"); }
        @Override public List<ResolveInfo> queryIntentActivityOptions(ComponentName caller,
                Intent[] specifics, Intent intent, String resolvedType, long flags, int userId) {
            throw new AssertionError("delegate");
        }
        @Override public List<ResolveInfo> queryIntentContentProviders(Intent intent,
                String resolvedType, long flags, int userId) { throw new AssertionError("delegate"); }
        @Override public List<ProviderInfo> queryContentProviders(String processName, int uid,
                int flags, int userId) { throw new AssertionError("delegate"); }
        @Override public List<PackageInfo> getInstalledPackagesAsUser(long flags, int userId) {
            throw new AssertionError("delegate");
        }
        @Override public int getComponentEnabledSetting(ComponentName component) {
            throw new AssertionError("delegate");
        }
        @Override public void setComponentEnabledSetting(ComponentName component, int newState) {
            throw new AssertionError("delegate");
        }
        @Override public boolean activitySupportsIntent(ComponentName component, Intent intent,
                                                         long flags) {
            throw new AssertionError("delegate");
        }
        @Override public PermissionInfo getPermissionInfo(String name, int flags) {
            if ("android.permission.INTERNET".equals(name)) {
                PermissionInfo info = new PermissionInfo();
                info.name = name; info.packageName = "android";
                return info;
            }
            throw new AssertionError("delegate");
        }
        @Override public List<PermissionInfo> queryPermissionsByGroup(String group, int flags) {
            if ("android.permission-group.CONTACTS".equals(group)) {
                PermissionInfo matching = new PermissionInfo();
                matching.name = "android.permission.READ_CONTACTS";
                matching.packageName = "android"; matching.group = group;
                PermissionInfo ungrouped = new PermissionInfo();
                ungrouped.name = "android.permission.INTERNET";
                ungrouped.packageName = "android";
                PermissionInfo otherGroup = new PermissionInfo();
                otherGroup.name = "android.permission.READ_CALENDAR";
                otherGroup.packageName = "android";
                otherGroup.group = "android.permission-group.CALENDAR";
                return List.of(matching, ungrouped, otherGroup);
            }
            throw new AssertionError("delegate");
        }
        @Override public PermissionGroupInfo getPermissionGroupInfo(String name, int flags) {
            if ("android.permission-group.CONTACTS".equals(name)) {
                PermissionGroupInfo info = new PermissionGroupInfo();
                info.name = name; info.packageName = "android";
                return info;
            }
            throw new AssertionError("delegate");
        }
        @Override public List<PermissionGroupInfo> getAllPermissionGroups(int flags) {
            PermissionGroupInfo info = new PermissionGroupInfo();
            info.name = "android.permission-group.CONTACTS";
            info.packageName = "android";
            PermissionGroupInfo hostOnly = new PermissionGroupInfo();
            hostOnly.name = "com.host.permission-group.INTERNAL";
            hostOnly.packageName = "com.host.framework";
            return List.of(info, hostOnly);
        }
        @Override public ApplicationInfo getApplicationInfo(String packageName, int flags) {
            throw new AssertionError("delegate");
        }
        @Override public PackageInfo getPackageInfo(String packageName, int flags) {
            throw new AssertionError("delegate");
        }
        @Override public int getApplicationEnabledSetting(String packageName, int userId) {
            throw new AssertionError("delegate");
        }
    }
}
