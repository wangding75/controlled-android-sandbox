package com.warden.controlledsandbox.runtime.guest;

import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import com.warden.controlledsandbox.contract.VirtualComponentMetadataSnapshot;
import com.warden.controlledsandbox.contract.VirtualComponentSnapshot;
import com.warden.controlledsandbox.contract.VirtualIntentDataSnapshot;
import com.warden.controlledsandbox.contract.VirtualIntentFilterSnapshot;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
import com.warden.controlledsandbox.contract.VirtualPackageQuerySnapshot;
import com.warden.controlledsandbox.contract.VirtualPackageProjectionSnapshot;
import com.warden.controlledsandbox.contract.VirtualProviderPathRuleSnapshot;
import com.warden.controlledsandbox.contract.VirtualSharedLibrarySnapshot;
import com.warden.controlledsandbox.contract.VirtualInstrumentationSnapshot;
import com.warden.controlledsandbox.contract.VirtualPermissionDeclarationSnapshot;
import com.warden.controlledsandbox.contract.VirtualPermissionGroupSnapshot;
import com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

/** Maps the package authority's immutable snapshot into the process-local PackageManager model. */
public final class GuestPackageMetadataMapper {
    private GuestPackageMetadataMapper() { }

    /** Builds the broker-side PackageManager view without requiring parsed Guest resources. */
    public static VirtualPackageMetadata fromSnapshot(VirtualPackageStateSnapshot state,
                                                       ApplicationInfo applicationInfo) {
        return fromSnapshot(state, applicationInfo, null);
    }

    static VirtualPackageMetadata fromSnapshot(VirtualPackageStateSnapshot state,
                                               ApplicationInfo applicationInfo,
                                               GuestManifestMetadata manifestMetadata) {
        if (state == null) throw new IllegalArgumentException("virtual package state is required");
        List<VirtualPackageMetadata.Component> components = new ArrayList<>();
        for (VirtualComponentSnapshot component : state.components()) {
            List<VirtualPackageMetadata.Filter> filters = new ArrayList<>();
            for (VirtualIntentFilterSnapshot filter : component.intentFilters()) {
                List<VirtualPackageMetadata.DataRule> data = new ArrayList<>();
                for (VirtualIntentDataSnapshot rule : filter.data()) {
                    data.add(new VirtualPackageMetadata.DataRule(rule.scheme(), rule.host(), rule.port(),
                            rule.path(), rule.pathPrefix(), rule.pathPattern(), rule.mimeType()));
                }
                filters.add(new VirtualPackageMetadata.Filter(filter.priority(),
                        new LinkedHashSet<>(filter.actions()),
                        new LinkedHashSet<>(filter.categories()), data));
            }
            List<VirtualPackageMetadata.ProviderPathRule> providerPathRules = new ArrayList<>();
            for (VirtualProviderPathRuleSnapshot rule : component.providerPathRules()) {
                providerPathRules.add(new VirtualPackageMetadata.ProviderPathRule(
                        rule.path(), rule.pathPrefix(), rule.pathPattern(),
                        rule.readPermission(), rule.writePermission(), rule.uriGrantRule()));
            }
            // The package authority carries component metadata in the immutable snapshot.  Use
            // it as the baseline for every package in the virtual universe; the primary Guest
            // may additionally have an APK-backed parser view, but cross-package projections do
            // not reopen the peer APK and therefore must not drop the snapshot value.
            Bundle componentMetadata = toMetadataBundle(component.metaData());
            Bundle parsedComponentMetadata = manifestMetadata == null
                    ? null : manifestMetadata.componentForClass(component.className());
            if (parsedComponentMetadata != null) {
                if (componentMetadata == null) componentMetadata = parsedComponentMetadata;
                else componentMetadata.putAll(parsedComponentMetadata);
            }
            if (VirtualPackageMetadata.Type.PROVIDER == VirtualPackageMetadata.Type.valueOf(component.type())
                    && componentMetadata == null && manifestMetadata != null) {
                componentMetadata = manifestMetadata.providerForClass(component.className());
                if (componentMetadata == null) {
                    for (String authority : component.authority().split(";")) {
                        componentMetadata = manifestMetadata.provider(authority);
                        if (componentMetadata != null) break;
                    }
                }
            }
            components.add(new VirtualPackageMetadata.Component(
                    VirtualPackageMetadata.Type.valueOf(component.type()),
                    component.className(), component.processName(), component.exported(),
                    component.enabled(), component.isolated(),
                    new LinkedHashSet<>(component.actions()), component.authority(),
                     component.permission(), component.readPermission(), component.writePermission(),
                    component.grantUriPermissions(), component.enabledSetting(), filters, providerPathRules,
                     componentMetadata, component.launchMode(), component.taskAffinity(),
                     component.documentLaunchMode(), component.configChanges(),
                     component.screenOrientation(), component.windowSoftInputMode(), component.flags(),
                     component.excludeFromRecents(), component.noHistory(),
                     component.finishOnTaskLaunch(), component.clearTaskOnLaunch(),
                     component.alwaysRetainTaskState(), component.allowTaskReparenting(),
                     component.resizeMode(), component.maxAspectRatio(), component.minAspectRatio(),
                     component.supportsPictureInPicture(), component.themeResId(),
                     component.foregroundServiceType(), component.stopWithTask(),
                     component.directBootAware(), component.multiprocess(), component.initOrder(),
                     component.syncable(), component.persistableMode(),
                     activityAliasTarget(component, manifestMetadata)));
        }
        List<String> permissions = new ArrayList<>();
        LinkedHashMap<String, Boolean> permissionGrants = new LinkedHashMap<>();
        for (com.warden.controlledsandbox.contract.VirtualPermissionSnapshot permission : state.permissions()) {
            permissions.add(permission.name());
            permissionGrants.put(permission.name(), permission.effectiveGranted());
        }
        List<VirtualPackageMetadata.PermissionDeclaration> permissionDeclarations = new ArrayList<>();
        for (VirtualPermissionDeclarationSnapshot declaration : state.permissionDeclarations()) {
            permissionDeclarations.add(new VirtualPackageMetadata.PermissionDeclaration(
                    declaration.name(), declaration.group(), declaration.label(),
                    declaration.description(), declaration.labelRes(), declaration.descriptionRes(),
                    declaration.icon(), declaration.protectionLevel(), declaration.flags(),
                    declaration.tree()));
        }
        List<VirtualPackageMetadata.PermissionGroup> permissionGroups = new ArrayList<>();
        for (VirtualPermissionGroupSnapshot group : state.permissionGroups()) {
            permissionGroups.add(new VirtualPackageMetadata.PermissionGroup(
                    group.name(), group.label(), group.description(), group.labelRes(),
                    group.descriptionRes(), group.icon(), group.requestRes(), group.priority(),
                    group.flags()));
        }
        List<VirtualPackageMetadata.SharedLibrary> sharedLibraryDetails = new ArrayList<>();
        for (VirtualSharedLibrarySnapshot library : state.sharedLibraryDetails()) {
            sharedLibraryDetails.add(new VirtualPackageMetadata.SharedLibrary(
                    library.kind(), library.name(), library.required(), library.version(),
                    library.certificateDigest(), library.resolved(), library.providerPackage()));
        }
        List<VirtualPackageMetadata.Instrumentation> instrumentations = new ArrayList<>();
        for (VirtualInstrumentationSnapshot instrumentation : state.instrumentations()) {
            instrumentations.add(new VirtualPackageMetadata.Instrumentation(
                    instrumentation.className(), instrumentation.targetPackage(),
                    instrumentation.targetProcesses(), instrumentation.handleProfiling(),
                    instrumentation.functionalTest(), instrumentation.enabled()));
        }
        java.util.LinkedHashSet<String> queryPackages = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<String> queryProviderAuthorities = new java.util.LinkedHashSet<>();
        List<VirtualPackageMetadata.Filter> queryIntentFilters = new ArrayList<>();
        for (VirtualPackageQuerySnapshot query : state.queries()) {
            if (VirtualPackageQuerySnapshot.PACKAGE.equals(query.kind())) {
                queryPackages.add(query.value());
            } else if (VirtualPackageQuerySnapshot.PROVIDER.equals(query.kind())) {
                queryProviderAuthorities.add(query.value());
            } else if (query.intent() != null) {
                List<VirtualPackageMetadata.DataRule> data = new ArrayList<>();
                for (VirtualIntentDataSnapshot rule : query.intent().data()) {
                    data.add(new VirtualPackageMetadata.DataRule(rule.scheme(), rule.host(), rule.port(),
                            rule.path(), rule.pathPrefix(), rule.pathPattern(), rule.mimeType()));
                }
                queryIntentFilters.add(new VirtualPackageMetadata.Filter(query.intent().priority(),
                        new LinkedHashSet<>(query.intent().actions()),
                        new LinkedHashSet<>(query.intent().categories()), data));
            }
        }
        return new VirtualPackageMetadata(state.packageName(), state.launchActivity(),
                applicationInfo, components, state.versionName(), state.versionCode(),
                state.signatureSha256(), state.firstInstallTime(), state.lastUpdateTime(),
                state.installerPackageName(), state.sharedLibraries(), sharedLibraryDetails,
                instrumentations, permissions, state.enabled(), queryPackages,
                queryProviderAuthorities, queryIntentFilters, permissionGrants,
                permissionDeclarations, permissionGroups, state.signingCertificates());
    }

    private static String activityAliasTarget(VirtualComponentSnapshot component,
                                              GuestManifestMetadata manifestMetadata) {
        if (component != null && !component.targetActivity().isEmpty()) {
            return component.targetActivity();
        }
        if (manifestMetadata == null || component == null
                || component.className().trim().isEmpty()) return "";
        return manifestMetadata.activityTarget(component.className());
    }

    public static VirtualPackageMetadata fromProjection(
            VirtualPackageProjectionSnapshot projection) {
        VirtualPackageStateSnapshot state = projection.packageState();
        // The parsedApplicationInfo field is an optional legacy transport hint. It may have
        // originated from the host PackageManager and is never authoritative for a peer Guest;
        // start from the package authority snapshot so host identity/path metadata cannot cross
        // the virtual PMS boundary.
        ApplicationInfo applicationInfo = state.applicationInfo();
        if (applicationInfo == null) applicationInfo = new ApplicationInfo();
        applicationInfo.packageName = state.packageName();
        applicationInfo.name = state.applicationClass().isEmpty() ? null : state.applicationClass();
        applicationInfo.sourceDir = projection.apkPath();
        applicationInfo.publicSourceDir = projection.apkPath();
        if (applicationInfo.nativeLibraryDir == null || applicationInfo.nativeLibraryDir.trim().isEmpty()) {
            applicationInfo.nativeLibraryDir = projection.nativeLibraryDir();
        }
        applicationInfo.dataDir = null;
        applicationInfo.uid = projection.virtualUid();
        applicationInfo.enabled = state.enabled();
        applicationInfo.flags |= ApplicationInfo.FLAG_HAS_CODE;
        return fromSnapshot(state, applicationInfo, null);
    }

    public static Bundle toMetadataBundle(List<VirtualComponentMetadataSnapshot> entries) {
        if (entries == null || entries.isEmpty()) return null;
        Bundle bundle = new Bundle();
        for (VirtualComponentMetadataSnapshot entry : entries) {
            if (entry == null || entry.name() == null) continue;
            String type = entry.type();
            if (VirtualComponentMetadataSnapshot.TYPE_INTEGER.equals(type)
                    || VirtualComponentMetadataSnapshot.TYPE_RESOURCE.equals(type)) {
                bundle.putInt(entry.name(), entry.resourceId() != 0 ? entry.resourceId() : entry.intValue());
            } else if (VirtualComponentMetadataSnapshot.TYPE_BOOLEAN.equals(type)) {
                bundle.putBoolean(entry.name(), entry.booleanValue());
            } else if (VirtualComponentMetadataSnapshot.TYPE_FLOAT.equals(type)) {
                bundle.putFloat(entry.name(), entry.floatValue());
            } else {
                bundle.putString(entry.name(), entry.stringValue());
            }
        }
        return bundle;
    }
}
