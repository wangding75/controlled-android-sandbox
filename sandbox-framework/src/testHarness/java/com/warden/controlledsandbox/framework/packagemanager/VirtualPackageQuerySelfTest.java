package com.warden.controlledsandbox.framework.packagemanager;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata;
import com.warden.controlledsandbox.framework.identity.VirtualPackageUniverse;
import java.util.List;
import java.util.Map;
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

        VirtualPackageMetadata aliasMetadata = new VirtualPackageMetadata("alias.pkg",
                "alias.pkg.LaunchAlias", application, List.of(
                new VirtualPackageMetadata.Component(VirtualPackageMetadata.Type.ACTIVITY,
                        "alias.pkg.LaunchAlias", "alias.pkg", true, true, false,
                        Set.of("android.intent.action.MAIN"), "", "", "", "", false,
                        "DEFAULT", List.of(), List.of(), null, "standard", "alias.pkg",
                        "none", 0, "", 0, 0, false, false, false, false, false, false,
                        "", 0f, 0f, false, 0, 0, false, false, false, 0, false, "never",
                        "alias.pkg.MainActivity")), "", 0L, repeat('j'), 0L, 0L, "",
                List.of(), List.of(), true);
        VirtualPackageMetadata.Component aliasComponent = aliasMetadata.component(
                "alias.pkg.LaunchAlias", VirtualPackageMetadata.Type.ACTIVITY);
        require(aliasComponent != null
                        && "alias.pkg.MainActivity".equals(aliasComponent.targetActivity()),
                "activity-alias target is retained in virtual package metadata");
        android.content.pm.ActivityInfo aliasInfo = (android.content.pm.ActivityInfo)
                aliasMetadata.componentInfo(new ComponentName("alias.pkg", "alias.pkg.LaunchAlias"),
                        VirtualPackageMetadata.Type.ACTIVITY, 0L);
        try {
            java.lang.reflect.Field target = android.content.pm.ActivityInfo.class
                    .getDeclaredField("targetActivity");
            target.setAccessible(true);
            require("alias.pkg.MainActivity".equals(target.get(aliasInfo)),
                    "activity-alias target is projected to ActivityInfo");
        } catch (ReflectiveOperationException unavailable) {
            throw new AssertionError("ActivityInfo.targetActivity projection unavailable", unavailable);
        }

        Intent view = new Intent().setAction("android.intent.action.VIEW")
                .addCategory("android.intent.category.BROWSABLE")
                .setDataAndType(Uri.parse("https://example.com/docs/guide"), "text/plain");
        List<ResolveInfo> matches = metadata.query(view, VirtualPackageMetadata.Type.ACTIVITY, 0L);
        require(matches.size() == 2, "both exact and generic filters match");
        require("guest.pkg.ViewActivity".equals(matches.get(0).activityInfo.name),
                "priority and specificity order is deterministic");
        require(matches.get(0).priority == 20
                        && matches.get(0).match == 0x00608000
                        && matches.get(1).match == 0x00608000,
                "ResolveInfo carries the framework TYPE match category and adjustment");
        require(matches.get(0).filter != null
                        && matches.get(0).filter.getPriority() == 20
                        && matches.get(0).filter.countActions() == 1
                        && "android.intent.action.VIEW".equals(matches.get(0).filter.getAction(0))
                        && matches.get(0).filter.countCategories() == 2
                        && ("android.intent.category.BROWSABLE".equals(
                                matches.get(0).filter.getCategory(0))
                                || "android.intent.category.BROWSABLE".equals(
                                matches.get(0).filter.getCategory(1)))
                        && matches.get(0).filter.countDataSchemes() == 1
                        && "https".equals(matches.get(0).filter.getDataScheme(0))
                        && matches.get(0).filter.countDataTypes() == 1,
                "ResolveInfo projects the matched IntentFilter contract");

        Intent selectorRoute = new SelectorIntent("guest.OUTER_ROUTING_ACTION",
                new Intent().setAction("guest.INTERNAL"));
        List<ResolveInfo> selectorMatches = metadata.query(selectorRoute,
                VirtualPackageMetadata.Type.ACTIVITY, 0L);
        require(selectorMatches.size() == 1
                        && "guest.pkg.InternalActivity".equals(
                                selectorMatches.get(0).activityInfo.name),
                "Intent selector controls PackageManager resolution");

        Intent wrongHost = new Intent().setAction("android.intent.action.VIEW")
                .setDataAndType(Uri.parse("https://invalid.example/docs/guide"), "text/plain");
        require(metadata.query(wrongHost, VirtualPackageMetadata.Type.ACTIVITY, 0L).isEmpty(),
                "host mismatch is rejected");

        VirtualPackageMetadata portMetadata = new VirtualPackageMetadata("port.pkg",
                "port.pkg.CallbackActivity", application, List.of(
                new VirtualPackageMetadata.Component(VirtualPackageMetadata.Type.ACTIVITY,
                        "port.pkg.CallbackActivity", "port.pkg", true, true, false,
                        Set.of("android.intent.action.VIEW"), "", "", "DEFAULT", List.of(
                        new VirtualPackageMetadata.Filter(0,
                                Set.of("android.intent.action.VIEW"),
                                Set.of("android.intent.category.DEFAULT"),
                                List.of(new VirtualPackageMetadata.DataRule("https", "example.com",
                                        8443, "", "", "", "*/*")))))));
        Intent matchingPort = new Intent().setAction("android.intent.action.VIEW")
                .setDataAndType(Uri.parse("https://example.com:8443/callback"), "text/plain");
        require(portMetadata.query(matchingPort, VirtualPackageMetadata.Type.ACTIVITY, 0L).size() == 1,
                "IntentFilter authority port matches the exact URI port");
        require(portMetadata.query(new Intent().setAction("android.intent.action.VIEW")
                        .setDataAndType(Uri.parse("https://example.com:443/callback"), "text/plain"),
                        VirtualPackageMetadata.Type.ACTIVITY, 0L).isEmpty(),
                "IntentFilter authority port rejects a different URI port");
        List<ResolveInfo> portResult = portMetadata.query(matchingPort,
                VirtualPackageMetadata.Type.ACTIVITY, 0L);
        require(portResult.get(0).match == 0x00608000
                        && portResult.get(0).filter != null
                        && portResult.get(0).filter.countDataAuthorities() == 1,
                "port match category and ResolveInfo.filter authority are projected");
        VirtualPackageMetadata.Filter wrongPortQuery = new VirtualPackageMetadata.Filter(0,
                Set.of("android.intent.action.VIEW"), Set.of(), List.of(
                new VirtualPackageMetadata.DataRule("https", "example.com", 9443,
                        "", "", "", "*/*")));
        require(!portMetadata.matchesQueryFilter(wrongPortQuery),
                "queries intent port intersection rejects disjoint authorities");
        VirtualPackageMetadata.Filter anyPortQuery = new VirtualPackageMetadata.Filter(0,
                Set.of("android.intent.action.VIEW"), Set.of(), List.of(
                new VirtualPackageMetadata.DataRule("https", "example.com", "", "", "", "*/*")));
        require(portMetadata.matchesQueryFilter(anyPortQuery),
                "queries intent without a port intersects an explicit target port");
        VirtualPackageMetadata pairedAuthorityMetadata = new VirtualPackageMetadata("paired.pkg",
                "paired.pkg.CallbackActivity", application, List.of(
                new VirtualPackageMetadata.Component(VirtualPackageMetadata.Type.ACTIVITY,
                        "paired.pkg.CallbackActivity", "paired.pkg", true, true, false,
                        Set.of("android.intent.action.VIEW"), "", "", "DEFAULT", List.of(
                        new VirtualPackageMetadata.Filter(0,
                                Set.of("android.intent.action.VIEW"), Set.of(), List.of(
                                new VirtualPackageMetadata.DataRule("https", "example.com", 8443,
                                        "", "", "", "*/*"),
                                new VirtualPackageMetadata.DataRule("https", "other.example", 9443,
                                        "", "", "", "*/*")))))));
        require(pairedAuthorityMetadata.query(new Intent().setAction("android.intent.action.VIEW")
                        .setDataAndType(Uri.parse("https://example.com:9443/callback"), "text/plain"),
                        VirtualPackageMetadata.Type.ACTIVITY, 0L).isEmpty(),
                "authority host and port pairs are not cross-combined");

        VirtualPackageMetadata wildcardHost = new VirtualPackageMetadata("guest.pkg",
                "guest.pkg.WildcardActivity", application, List.of(
                new VirtualPackageMetadata.Component(VirtualPackageMetadata.Type.ACTIVITY,
                        "guest.pkg.WildcardActivity", "guest.pkg", true, true, false,
                        Set.of("android.intent.action.VIEW"), "", "", "DEFAULT", List.of(
                        new VirtualPackageMetadata.Filter(0,
                                Set.of("android.intent.action.VIEW"),
                                Set.of("android.intent.category.DEFAULT"),
                                List.of(new VirtualPackageMetadata.DataRule("https",
                                        "*.example.com", "", "", "", "*/*")))))));
        Intent wildcardView = new Intent().setAction("android.intent.action.VIEW")
                .setDataAndType(Uri.parse("https://cdn.example.com/docs"), "text/plain");
        require(wildcardHost.query(wildcardView, VirtualPackageMetadata.Type.ACTIVITY, 0L).size() == 1,
                "host wildcard matches a subdomain");
        Intent siblingView = new Intent().setAction("android.intent.action.VIEW")
                .setDataAndType(Uri.parse("https://cdn.other.example/docs"), "text/plain");
        require(wildcardHost.query(siblingView, VirtualPackageMetadata.Type.ACTIVITY, 0L).isEmpty(),
                "host wildcard rejects a sibling domain");

        // Android merges attributes from multiple <data> tags in one intent-filter.  A real
        // manifest commonly declares scheme, authority, path and MIME on separate tags.
        VirtualPackageMetadata splitData = new VirtualPackageMetadata("split.pkg",
                "split.pkg.DeepLinkActivity", application, List.of(
                new VirtualPackageMetadata.Component(VirtualPackageMetadata.Type.ACTIVITY,
                        "split.pkg.DeepLinkActivity", "split.pkg", true, true, false,
                        Set.of("android.intent.action.VIEW"), "", "", "DEFAULT", List.of(
                        new VirtualPackageMetadata.Filter(0,
                                Set.of("android.intent.action.VIEW"), Set.of(), List.of(
                                new VirtualPackageMetadata.DataRule("https", "", "", "", "", ""),
                                new VirtualPackageMetadata.DataRule("", "example.com", "", "", "", ""),
                                new VirtualPackageMetadata.DataRule("", "", "", "/docs", "", ""),
                                new VirtualPackageMetadata.DataRule("", "", "", "", "", "text/plain")))))));
        Intent splitDataView = new Intent().setAction("android.intent.action.VIEW")
                .setDataAndType(Uri.parse("https://example.com/docs/guide"), "text/plain");
        require(splitData.query(splitDataView, VirtualPackageMetadata.Type.ACTIVITY, 0L).size() == 1,
                "multiple manifest data tags are merged by Framework dimensions");
        Intent splitDataWrongHost = new Intent().setAction("android.intent.action.VIEW")
                .setDataAndType(Uri.parse("https://other.example/docs/guide"), "text/plain");
        require(splitData.query(splitDataWrongHost, VirtualPackageMetadata.Type.ACTIVITY, 0L).isEmpty(),
                "merged manifest data tags still enforce authority constraints");

        VirtualPackageMetadata pathWithoutBase = new VirtualPackageMetadata("pathless.pkg",
                "pathless.pkg.Activity", application, List.of(
                new VirtualPackageMetadata.Component(VirtualPackageMetadata.Type.ACTIVITY,
                        "pathless.pkg.Activity", "pathless.pkg", true, true, false,
                        Set.of("android.intent.action.VIEW"), "", "", "DEFAULT", List.of(
                        new VirtualPackageMetadata.Filter(0,
                                Set.of("android.intent.action.VIEW"), Set.of(), List.of(
                                new VirtualPackageMetadata.DataRule("", "", "/docs", "", "", "")))))));
        require(pathWithoutBase.query(splitDataView, VirtualPackageMetadata.Type.ACTIVITY, 0L).isEmpty(),
                "path-only data declarations do not become an implicit URI matcher");

        VirtualPackageMetadata pathTarget = new VirtualPackageMetadata("path.pkg",
                "path.pkg.DocsActivity", application, List.of(
                new VirtualPackageMetadata.Component(VirtualPackageMetadata.Type.ACTIVITY,
                        "path.pkg.DocsActivity", "path.pkg", true, true, false,
                        Set.of("android.intent.action.VIEW"), "", "", "DEFAULT", List.of(
                        new VirtualPackageMetadata.Filter(0,
                                Set.of("android.intent.action.VIEW"), Set.of(), List.of(
                                new VirtualPackageMetadata.DataRule("https", "example.com",
                                        "/docs", "", "", "text/*")))))));
        VirtualPackageMetadata.Filter queryWrongHost = new VirtualPackageMetadata.Filter(0,
                Set.of("android.intent.action.VIEW"), Set.of(), List.of(
                new VirtualPackageMetadata.DataRule("https", "invalid.example", "", "",
                        "", "*/*")));
        require(!metadata.matchesQueryFilter(queryWrongHost),
                "queries intent host intersection is enforced");
        VirtualPackageMetadata.Filter queryTypedWildcard = new VirtualPackageMetadata.Filter(0,
                Set.of("android.intent.action.VIEW"), Set.of(), List.of(
                new VirtualPackageMetadata.DataRule("https", "example.com", "", "", "",
                        "text/plain")));
        require(metadata.matchesQueryFilter(queryTypedWildcard),
                "queries intent MIME wildcards intersect");
        VirtualPackageMetadata.Filter queryWrongPath = new VirtualPackageMetadata.Filter(0,
                Set.of("android.intent.action.VIEW"), Set.of(), List.of(
                new VirtualPackageMetadata.DataRule("https", "example.com", "/private", "",
                        "", "text/plain")));
        require(!pathTarget.matchesQueryFilter(queryWrongPath),
                "queries intent exact path mismatch is rejected");
        VirtualPackageMetadata.Filter queryPathPrefix = new VirtualPackageMetadata.Filter(0,
                Set.of("android.intent.action.VIEW"), Set.of(), List.of(
                new VirtualPackageMetadata.DataRule("https", "example.com", "", "/docs",
                        "", "text/plain")));
        require(pathTarget.matchesQueryFilter(queryPathPrefix),
                "queries intent path prefix intersects exact path");

        // Exercise the same filters through the Virtual PMS visibility graph.  Testing
        // matchesQueryFilter() alone would miss a regression where isVisibleTo() stops
        // consulting the stricter <queries><intent> intersection rules.
        ApplicationInfo visibilityCallerInfo = new ApplicationInfo();
        visibilityCallerInfo.packageName = "caller.query";
        visibilityCallerInfo.uid = 13001;
        visibilityCallerInfo.targetSdkVersion = 33;
        VirtualPackageMetadata visibilityCaller = new VirtualPackageMetadata("caller.query", "",
                visibilityCallerInfo, List.of(), "", 0L, repeat('e'), 0L, 0L, "", List.of(),
                List.of(), List.of(), List.of(), true, Set.of(), Set.of(),
                List.of(queryWrongHost));
        VirtualPackageUniverse visibilityUniverse = new VirtualPackageUniverse(
                List.of(visibilityCaller, pathTarget));
        require(!visibilityUniverse.isVisibleTo("caller.query", "path.pkg"),
                "Virtual PMS rejects a target hidden by queries intent host/path constraints");
        VirtualPackageMetadata visibleQueryCaller = new VirtualPackageMetadata("caller.query", "",
                visibilityCallerInfo, List.of(), "", 0L, repeat('e'), 0L, 0L, "", List.of(),
                List.of(), List.of(), List.of(), true, Set.of(), Set.of(),
                List.of(queryTypedWildcard));
        visibilityUniverse = new VirtualPackageUniverse(List.of(visibleQueryCaller, pathTarget));
        require(visibilityUniverse.isVisibleTo("caller.query", "path.pkg"),
                "Virtual PMS exposes a target whose queries intent MIME/path constraints intersect");

        VirtualPackageMetadata.Component privateActivity = new VirtualPackageMetadata.Component(
                VirtualPackageMetadata.Type.ACTIVITY, "private.query.target.InternalActivity",
                "private.query.target", false, true, false,
                Set.of("android.intent.action.VIEW"), "", "", "DEFAULT", List.of(
                new VirtualPackageMetadata.Filter(0, Set.of("android.intent.action.VIEW"),
                        Set.of(), List.of(new VirtualPackageMetadata.DataRule("https",
                                "example.com", "", "", "", "text/plain")))));
        VirtualPackageMetadata privateQueryTarget = new VirtualPackageMetadata("private.query.target",
                "", new ApplicationInfo(), List.of(privateActivity), "", 0L,
                repeat('f'), 0L, 0L, "", List.of(), List.of(), true);
        VirtualPackageMetadata privateQueryCaller = new VirtualPackageMetadata("private.query.caller",
                "", visibilityCallerInfo, List.<VirtualPackageMetadata.Component>of(), "", 0L,
                repeat('g'), 0L, 0L, "", List.<String>of(),
                List.<VirtualPackageMetadata.SharedLibrary>of(),
                List.<VirtualPackageMetadata.Instrumentation>of(), List.<String>of(), true,
                Set.<String>of(), Set.<String>of(), List.of(queryTypedWildcard));
        VirtualPackageUniverse privateUniverse = new VirtualPackageUniverse(
                List.of(privateQueryCaller, privateQueryTarget));
        require(!privateUniverse.isVisibleTo("private.query.caller", "private.query.target"),
                "Virtual PMS exposes a package through a non-exported query component");

        VirtualPackageMetadata privateProviderTarget = new VirtualPackageMetadata(
                "private.provider.target", "", new ApplicationInfo(), List.of(
                new VirtualPackageMetadata.Component(VirtualPackageMetadata.Type.PROVIDER,
                        "private.provider.target.InternalProvider", "private.provider.target",
                        false, true, false, Set.of(), "private.authority", "", "DEFAULT",
                        List.of())), "", 0L, repeat('h'), 0L, 0L, "", List.of(),
                List.of(), true);
        VirtualPackageMetadata providerQueryCaller = new VirtualPackageMetadata(
                "provider.query.caller", "", visibilityCallerInfo,
                List.<VirtualPackageMetadata.Component>of(), "", 0L, repeat('i'), 0L, 0L, "",
                List.<String>of(), List.<VirtualPackageMetadata.SharedLibrary>of(),
                List.<VirtualPackageMetadata.Instrumentation>of(), List.<String>of(), true,
                Set.<String>of(), Set.of("private.authority"),
                List.<VirtualPackageMetadata.Filter>of());
        VirtualPackageUniverse providerUniverse = new VirtualPackageUniverse(
                List.of(providerQueryCaller, privateProviderTarget));
        require(!providerUniverse.isVisibleTo("provider.query.caller", "private.provider.target"),
                "Virtual PMS exposes a package through a non-exported query provider");

        // A resolved uses-library edge is a first-class PackageManager visibility edge. It must
        // not depend on a separate <queries> declaration because LoadedApk needs the provider
        // APK while constructing the caller's class path (including the isolated FD-backed
        // loader path).
        VirtualPackageMetadata.SharedLibrary resolvedLibrary =
                new VirtualPackageMetadata.SharedLibrary("JAVA", "provider.lib", true, 0L,
                        "", true, "library.provider");
        ApplicationInfo libraryCallerInfo = new ApplicationInfo();
        libraryCallerInfo.packageName = "library.caller";
        libraryCallerInfo.uid = 15001;
        libraryCallerInfo.targetSdkVersion = 33;
        VirtualPackageMetadata libraryCaller = new VirtualPackageMetadata(
                "library.caller", "", libraryCallerInfo, List.of(), "", 0L,
                repeat('q'), 0L, 0L, "", List.of("provider.lib"),
                List.of(resolvedLibrary), List.of(), List.of(), true, Set.of(), Set.of(), List.of());
        ApplicationInfo libraryProviderInfo = new ApplicationInfo();
        libraryProviderInfo.packageName = "library.provider";
        libraryProviderInfo.uid = 15002;
        VirtualPackageMetadata libraryProvider = new VirtualPackageMetadata(
                "library.provider", "", libraryProviderInfo, List.of(), "", 0L,
                repeat('r'), 0L, 0L, "", List.of(), List.of(), List.of(), List.of(), true,
                Set.of(), Set.of(), List.of());
        VirtualPackageUniverse libraryUniverse = new VirtualPackageUniverse(
                List.of(libraryCaller, libraryProvider));
        require(libraryUniverse.isVisibleTo("library.caller", "library.provider"),
                "resolved shared-library provider is visible without an extra queries edge");

        Intent packageMismatch = new Intent().setAction("android.intent.action.VIEW")
                .setPackage("other.pkg");
        require(metadata.query(packageMismatch, VirtualPackageMetadata.Type.ACTIVITY, 0L).isEmpty(),
                "explicit package isolation");

        Intent internal = new Intent().setAction("guest.INTERNAL");
        ResolveInfo internalMatch = metadata.resolve(internal, VirtualPackageMetadata.Type.ACTIVITY, 0L);
        require(internalMatch != null,
                "non-default filter resolves without MATCH_DEFAULT_ONLY");
        require(internalMatch.match == 0x00108000,
                "empty-data ResolveInfo uses framework category and adjustment score");
        require(metadata.resolve(internal, VirtualPackageMetadata.Type.ACTIVITY,
                VirtualPackageMetadata.MATCH_DEFAULT_ONLY) == null,
                "MATCH_DEFAULT_ONLY is enforced");

        VirtualPackageMetadata.Filter ambiguousFilter = new VirtualPackageMetadata.Filter(10,
                Set.of("guest.AMBIGUOUS"), Set.of("android.intent.category.DEFAULT"), List.of());
        VirtualPackageMetadata ambiguous = new VirtualPackageMetadata("ambiguous.pkg", "",
                application, List.of(
                new VirtualPackageMetadata.Component(VirtualPackageMetadata.Type.ACTIVITY,
                        "ambiguous.pkg.First", "ambiguous.pkg", true, true, false,
                        Set.of("guest.AMBIGUOUS"), "", "", "DEFAULT", List.of(ambiguousFilter)),
                new VirtualPackageMetadata.Component(VirtualPackageMetadata.Type.ACTIVITY,
                        "ambiguous.pkg.Second", "ambiguous.pkg", true, true, false,
                        Set.of("guest.AMBIGUOUS"), "", "", "DEFAULT", List.of(ambiguousFilter))));
        Intent ambiguousIntent = new Intent("guest.AMBIGUOUS");
        require(ambiguous.query(ambiguousIntent, VirtualPackageMetadata.Type.ACTIVITY, 0L).size() == 2,
                "resolver query retains both equal activity candidates");
        require(ambiguous.resolve(ambiguousIntent, VirtualPackageMetadata.Type.ACTIVITY, 0L) == null,
                "resolver does not choose an indistinguishable activity candidate");
        require(VirtualPackageUniverse.single(ambiguous).resolve("ambiguous.pkg",
                ambiguousIntent, VirtualPackageMetadata.Type.ACTIVITY, 0L, Set.of()) == null,
                "virtual universe applies chooseBestActivity tie semantics");

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
        require(metadata.ownsAuthority("guest.two") && metadata.ownsAuthority("guest.one"),
                "owned authorities are recognized for PackageManager resolveContentProvider");
        require("com.warden.virtualinstaller".equals(metadata.installerPackageName()),
                "synthetic install source is stable");
        require(metadata.sharedLibraries().contains("org.apache.http.legacy"),
                "shared library metadata is exposed");

        VirtualPackageMetadata duplicateAuthority = new VirtualPackageMetadata("guest.pkg",
                "guest.pkg.ViewActivity", application, List.of(
                new VirtualPackageMetadata.Component(VirtualPackageMetadata.Type.PROVIDER,
                        "guest.pkg.FirstProvider", "guest.pkg", true, true, false,
                        Set.of(), "guest.shared", "", "DEFAULT", List.of()),
                new VirtualPackageMetadata.Component(VirtualPackageMetadata.Type.PROVIDER,
                        "guest.pkg.DuplicateProvider", "guest.pkg", true, true, false,
                        Set.of(), "guest.shared", "", "DEFAULT", List.of()),
                new VirtualPackageMetadata.Component(VirtualPackageMetadata.Type.PROVIDER,
                        "guest.pkg.UniqueProvider", "guest.pkg", true, true, false,
                        Set.of(), "guest.unique", "", "DEFAULT", List.of())));
        require(duplicateAuthority.components().size() == 3,
                "class-distinct providers are kept even when an authority is repeated");
        require(duplicateAuthority.componentInfo(
                        new ComponentName("guest.pkg", "guest.pkg.DuplicateProvider"),
                        VirtualPackageMetadata.Type.PROVIDER, 0L) != null,
                "second provider class remains queryable by ComponentName");
        require("guest.pkg.FirstProvider".equals(
                        duplicateAuthority.provider("guest.shared").name),
                "first provider retains duplicate authority ownership");
        require("guest.pkg.UniqueProvider".equals(
                        duplicateAuthority.provider("guest.unique").name),
                "unique provider authority remains available after duplicate filtering");

        ApplicationInfo signatureFirstInfo = new ApplicationInfo();
        signatureFirstInfo.packageName = "signature.first";
        signatureFirstInfo.uid = 14001;
        ApplicationInfo signatureSecondInfo = new ApplicationInfo();
        signatureSecondInfo.packageName = "signature.second";
        signatureSecondInfo.uid = 14002;
        VirtualPackageMetadata signatureFirst = new VirtualPackageMetadata("signature.first", "",
                signatureFirstInfo, List.of(), "", 0L, repeat('s'), 0L, 0L, "", List.of(),
                List.of(), List.of(), List.of(), true, Set.of("signature.mismatch"), Set.of(),
                List.of());
        VirtualPackageMetadata signatureSecond = new VirtualPackageMetadata("signature.second", "",
                signatureSecondInfo, List.of(), "", 0L, repeat('s'), 0L, 0L, "", List.of(),
                List.of(), List.of(), List.of(), true);
        VirtualPackageMetadata signatureMismatch = new VirtualPackageMetadata(
                "signature.mismatch", "", new ApplicationInfo(), List.of(), "", 0L,
                repeat('t'), 0L, 0L, "", List.of(), List.of(), List.of(), List.of(), true);
        VirtualPackageUniverse signatureUniverse = new VirtualPackageUniverse(List.of(
                signatureFirst, signatureSecond, signatureMismatch));
        require(signatureUniverse.checkSignatures("signature.first", "signature.first",
                        "signature.second") == VirtualPackageUniverse.SIGNATURE_MATCH,
                "virtual PMS reports matching package signatures");
        require(signatureUniverse.checkUidSignatures("signature.first", 14001, 14002)
                        == VirtualPackageUniverse.SIGNATURE_MATCH,
                "virtual PMS reports matching UID signatures");
        require(signatureUniverse.checkSignatures("signature.first", "signature.first",
                        "signature.mismatch") == VirtualPackageUniverse.SIGNATURE_NO_MATCH,
                "virtual PMS reports signature mismatch");
        require(signatureUniverse.checkSignatures("signature.first", "signature.first",
                        "hidden.pkg") == VirtualPackageUniverse.SIGNATURE_UNKNOWN_PACKAGE,
                "virtual PMS does not reveal hidden signature state");

        ApplicationInfo callerApplication = new ApplicationInfo();
        callerApplication.packageName = "caller.pkg";
        callerApplication.uid = 12001;
        callerApplication.targetSdkVersion = 33;
        ApplicationInfo peerApplication = new ApplicationInfo();
        peerApplication.packageName = "peer.pkg";
        peerApplication.uid = 12002;
        VirtualPackageMetadata callerMetadata = new VirtualPackageMetadata("caller.pkg", "",
                callerApplication, List.of(), "", 0L, repeat('c'), 0L, 0L, "", List.of(),
                List.of(), List.of(), List.of(), true, Set.of("peer.pkg"), Set.of(), List.of());
        VirtualPackageMetadata peerMetadata = new VirtualPackageMetadata("peer.pkg", "",
                peerApplication, List.of(new VirtualPackageMetadata.Component(
                        VirtualPackageMetadata.Type.PROVIDER, "peer.pkg.Provider", "peer.pkg",
                        true, true, false, Set.of(), "peer.authority")), "", 0L,
                repeat('d'), 0L, 0L, "", List.of(), List.of(), true);
        VirtualPackageUniverse universe = new VirtualPackageUniverse(
                List.of(callerMetadata, peerMetadata));
        ResolveInfo crossProvider = universe.resolve("caller.pkg",
                new Intent().setComponent(new ComponentName("peer.pkg", "peer.pkg.Provider")),
                VirtualPackageMetadata.Type.PROVIDER, 0L, Set.of());
        require(crossProvider != null && crossProvider.providerInfo != null
                        && "peer.pkg.Provider".equals(crossProvider.providerInfo.name),
                "cross-package exported Provider resolves through the virtual universe");

        VirtualPackageMetadata peerPermissionMetadata = new VirtualPackageMetadata("peer.pkg", "",
                peerApplication, peerMetadata.components(), "", 0L, repeat('d'), 0L, 0L, "",
                List.of(), List.of(), List.of(),
                List.of("peer.permission.READ", "peer.permission.DENIED"),
                true, Set.of(), Set.of(), List.of(),
                Map.of("peer.permission.READ", true, "peer.permission.DENIED", false));
        VirtualPackageUniverse permissionUniverse = new VirtualPackageUniverse(
                List.of(callerMetadata, peerPermissionMetadata));
        require(permissionUniverse.checkPermission("caller.pkg", "peer.pkg",
                        "peer.permission.READ") == 0,
                "cross-package checkPermission returns the target package grant");
        require(permissionUniverse.checkPermission("caller.pkg", "peer.pkg",
                        "peer.permission.DENIED") == -1,
                "cross-package checkPermission preserves a target package denial");
        require(permissionUniverse.checkPermission("caller.pkg", "hidden.pkg",
                        "peer.permission.READ") == -1,
                "checkPermission does not reveal an invisible package");

        application.sourceDir = "/data/app/guest.pkg/base.apk";
        application.nativeLibraryDir = "/data/app/guest.pkg/lib";
        VirtualPackageMetadata extended = new VirtualPackageMetadata("guest.pkg",
                "guest.pkg.ViewActivity", application, metadata.components(), "2.3", 23L,
                repeat('a'), 100L, 200L, "com.warden.virtualinstaller",
                List.of("org.apache.http.legacy", "guest.sdk"),
                List.of(
                        new VirtualPackageMetadata.SharedLibrary("JAVA",
                                "org.apache.http.legacy", true, 0L, "", true, "android"),
                        new VirtualPackageMetadata.SharedLibrary("SDK", "guest.sdk", true, 12L,
                                repeat('b'), true, "guest.provider")),
                List.of(new VirtualPackageMetadata.Instrumentation(
                                "guest.pkg.TestRunner", "guest.pkg", ":test", true, false, true),
                        new VirtualPackageMetadata.Instrumentation(
                                "guest.pkg.DisabledRunner", "guest.pkg", ":test", true, false, false)),
                List.of("android.permission.INTERNET"), true);
        PackageInfo instrumented = extended.packageInfo(0x00000010L);
        require(instrumented.instrumentation != null && instrumented.instrumentation.length == 1,
                "PackageInfo exposes instrumentation");
        require("guest.pkg.TestRunner".equals(instrumented.instrumentation[0].name)
                        && instrumented.instrumentation[0].handleProfiling,
                "instrumentation metadata retained");
        require(extended.instrumentationInfo(
                        new ComponentName("guest.pkg", "guest.pkg.TestRunner"), 0L) != null,
                "getInstrumentationInfo metadata available");
        require(extended.queryInstrumentation("guest.pkg", 0L).size() == 1,
                "queryInstrumentation target filtering");
        require(extended.queryInstrumentation("other.pkg", 0L).isEmpty(),
                "queryInstrumentation isolates target package");

        // Verify disabled runner visibility rules
        ComponentName disabledRunner = new ComponentName("guest.pkg", "guest.pkg.DisabledRunner");
        require(extended.instrumentationInfo(disabledRunner, 0L) == null,
                "disabled runner hidden by default");
        require(extended.instrumentationInfo(disabledRunner, VirtualPackageMetadata.MATCH_DISABLED_COMPONENTS) != null,
                "disabled runner visible with MATCH_DISABLED_COMPONENTS");

        // Verify InstrumentationInfo public metadata fields
        android.content.pm.InstrumentationInfo runnerInfo = extended.instrumentationInfo(
                new ComponentName("guest.pkg", "guest.pkg.TestRunner"), 0L);
        require(runnerInfo != null, "runnerInfo exists");
        require("guest.pkg.TestRunner".equals(runnerInfo.name), "runnerInfo name");
        require("guest.pkg".equals(runnerInfo.targetPackage), "runnerInfo targetPackage");
        require("/data/app/guest.pkg/base.apk".equals(runnerInfo.sourceDir), "runnerInfo sourceDir");

        require(extended.resolvedSharedLibraryNames().equals(
                        List.of("org.apache.http.legacy", "guest.sdk")),
                "resolved shared library names retained");
        require(extended.sharedLibraryInfoObjects().size() == 2,
                "SharedLibraryInfo objects created through version-tolerant factory");

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

    /** Keeps selector coverage executable against the compact API-32 harness stubs. */
    private static final class SelectorIntent extends Intent {
        private final Intent selector;
        SelectorIntent(String action, Intent selector) {
            super();
            setAction(action);
            this.selector = selector;
        }
        public Intent getSelector() { return selector; }
    }
}
