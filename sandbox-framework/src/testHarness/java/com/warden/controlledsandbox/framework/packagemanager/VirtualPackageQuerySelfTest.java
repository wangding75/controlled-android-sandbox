package com.warden.controlledsandbox.framework.packagemanager;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata;
import java.util.List;
import java.util.Set;

/** Host-side contract tests for deterministic virtual PackageManager query semantics. */
public final class VirtualPackageQuerySelfTest {
    public static void main(String[] args) {
        ApplicationInfo application = new ApplicationInfo();
        application.packageName = "guest.pkg";
        VirtualPackageMetadata.Filter exact = new VirtualPackageMetadata.Filter(20,
                Set.of("android.intent.action.VIEW"),
                Set.of("android.intent.category.DEFAULT", "android.intent.category.BROWSABLE"),
                List.of(new VirtualPackageMetadata.DataRule("https", "example.com", "",
                        "/docs", "", "text/*")));
        VirtualPackageMetadata.Filter generic = new VirtualPackageMetadata.Filter(5,
                Set.of("android.intent.action.VIEW"), Set.of("android.intent.category.DEFAULT", "android.intent.category.BROWSABLE"),
                List.of(new VirtualPackageMetadata.DataRule("https", "example.com", "", "", "", "*/*")));
        VirtualPackageMetadata.Filter noDefault = new VirtualPackageMetadata.Filter(50,
                Set.of("guest.INTERNAL"), Set.of(), List.of());
        VirtualPackageMetadata metadata = new VirtualPackageMetadata("guest.pkg", "guest.pkg.ViewActivity",
                application, List.of(
                new VirtualPackageMetadata.Component(VirtualPackageMetadata.Type.ACTIVITY,
                        "guest.pkg.ViewActivity", "guest.pkg", true, true, false,
                        Set.of("android.intent.action.VIEW"), "", "", "DEFAULT", List.of(exact)),
                new VirtualPackageMetadata.Component(VirtualPackageMetadata.Type.ACTIVITY,
                        "guest.pkg.GenericActivity", "guest.pkg", true, true, false,
                        Set.of("android.intent.action.VIEW"), "", "", "DEFAULT", List.of(generic)),
                new VirtualPackageMetadata.Component(VirtualPackageMetadata.Type.ACTIVITY,
                        "guest.pkg.InternalActivity", "guest.pkg", false, true, false,
                        Set.of("guest.INTERNAL"), "", "", "DEFAULT", List.of(noDefault)),
                new VirtualPackageMetadata.Component(VirtualPackageMetadata.Type.ACTIVITY,
                        "guest.pkg.DisabledActivity", "guest.pkg", true, false, false,
                        Set.of(), "", "", "DISABLED", List.of()),
                new VirtualPackageMetadata.Component(VirtualPackageMetadata.Type.PROVIDER,
                        "guest.pkg.Provider", "guest.pkg", true, true, false,
                        Set.of(), "guest.one;guest.two", "", "DEFAULT", List.of())),
                "2.3", 23L, repeat('a'), 100L, 200L, "com.warden.virtualinstaller",
                List.of("org.apache.http.legacy"), List.of("android.permission.INTERNET"), true);

        Intent view = new Intent().setAction("android.intent.action.VIEW")
                .addCategory("android.intent.category.BROWSABLE")
                .setDataAndType(Uri.parse("https://example.com/docs/guide"), "text/plain");
        List<ResolveInfo> matches = metadata.query(view, VirtualPackageMetadata.Type.ACTIVITY, 0L);
        require(matches.size() == 2, "both exact and generic filters match");
        require("guest.pkg.ViewActivity".equals(matches.get(0).activityInfo.name),
                "priority and specificity order is deterministic");
        require(matches.get(0).priority == 20 && matches.get(0).match > matches.get(1).match,
                "ResolveInfo carries priority and match score");

        Intent wrongHost = new Intent().setAction("android.intent.action.VIEW")
                .setDataAndType(Uri.parse("https://invalid.example/docs/guide"), "text/plain");
        require(metadata.query(wrongHost, VirtualPackageMetadata.Type.ACTIVITY, 0L).isEmpty(),
                "host mismatch is rejected");
        Intent packageMismatch = new Intent().setAction("android.intent.action.VIEW")
                .setPackage("other.pkg");
        require(metadata.query(packageMismatch, VirtualPackageMetadata.Type.ACTIVITY, 0L).isEmpty(),
                "explicit package isolation");

        Intent internal = new Intent().setAction("guest.INTERNAL");
        require(metadata.resolve(internal, VirtualPackageMetadata.Type.ACTIVITY, 0L) != null,
                "non-default filter resolves without MATCH_DEFAULT_ONLY");
        require(metadata.resolve(internal, VirtualPackageMetadata.Type.ACTIVITY,
                VirtualPackageMetadata.MATCH_DEFAULT_ONLY) == null,
                "MATCH_DEFAULT_ONLY is enforced");

        ComponentName disabled = new ComponentName("guest.pkg", "guest.pkg.DisabledActivity");
        require(metadata.componentInfo(disabled, VirtualPackageMetadata.Type.ACTIVITY, 0L) == null,
                "disabled component hidden by default");
        require(metadata.componentInfo(disabled, VirtualPackageMetadata.Type.ACTIVITY,
                VirtualPackageMetadata.MATCH_DISABLED_COMPONENTS) != null,
                "MATCH_DISABLED_COMPONENTS exposes disabled metadata");
        require(metadata.componentEnabledSetting(disabled) == 2,
                "component override state is preserved");

        PackageInfo basic = metadata.packageInfo(0L);
        require(basic.activities == null && basic.providers == null,
                "PackageInfo respects component flags");
        PackageInfo detailed = metadata.packageInfo(0x00000001L | 0x00000008L | 0x00001000L);
        require(detailed.activities.length == 3 && detailed.providers.length == 1,
                "PackageInfo filters disabled components and requested fields");
        require(detailed.firstInstallTime == 100L && detailed.lastUpdateTime == 200L,
                "install timestamps are stable");
        require(metadata.provider("guest.two") != null, "multi-authority Provider resolution");
        require("com.warden.virtualinstaller".equals(metadata.installerPackageName()),
                "synthetic install source is stable");
        require(metadata.sharedLibraries().contains("org.apache.http.legacy"),
                "shared library metadata is exposed");

        VirtualPackageMetadata disabledPackage = new VirtualPackageMetadata("guest.pkg",
                "guest.pkg.ViewActivity", application, metadata.components(), "2.3", 23L,
                repeat('a'), 100L, 200L, "com.warden.virtualinstaller",
                List.of("org.apache.http.legacy"), List.of("android.permission.INTERNET"), false);
        require(disabledPackage.query(view, VirtualPackageMetadata.Type.ACTIVITY, 0L).isEmpty(),
                "disabled package is hidden by default");
        require(!disabledPackage.query(view, VirtualPackageMetadata.Type.ACTIVITY,
                VirtualPackageMetadata.MATCH_DISABLED_COMPONENTS).isEmpty(),
                "MATCH_DISABLED_COMPONENTS exposes disabled package metadata");
        System.out.println("PASS virtual PackageManager query and Intent resolve self-test");
    }

    private static String repeat(char value) {
        StringBuilder output = new StringBuilder();
        for (int index = 0; index < 64; index++) output.append(value);
        return output.toString();
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
