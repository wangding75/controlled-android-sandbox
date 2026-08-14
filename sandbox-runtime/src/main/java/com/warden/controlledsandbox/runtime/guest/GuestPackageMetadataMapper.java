package com.warden.controlledsandbox.runtime.guest;

import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import com.warden.controlledsandbox.contract.VirtualComponentSnapshot;
import com.warden.controlledsandbox.contract.VirtualIntentDataSnapshot;
import com.warden.controlledsandbox.contract.VirtualIntentFilterSnapshot;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
import com.warden.controlledsandbox.contract.VirtualPackageQuerySnapshot;
import com.warden.controlledsandbox.contract.VirtualPackageProjectionSnapshot;
import com.warden.controlledsandbox.contract.VirtualProviderPathRuleSnapshot;
import com.warden.controlledsandbox.contract.VirtualSharedLibrarySnapshot;
import com.warden.controlledsandbox.contract.VirtualInstrumentationSnapshot;
import com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Maps the package authority's immutable snapshot into the process-local PackageManager model. */
final class GuestPackageMetadataMapper {
    private GuestPackageMetadataMapper() { }

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
                    data.add(new VirtualPackageMetadata.DataRule(rule.scheme(), rule.host(),
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
            Bundle providerMetadata = null;
            if (VirtualPackageMetadata.Type.PROVIDER == VirtualPackageMetadata.Type.valueOf(component.type())
                    && manifestMetadata != null) {
                providerMetadata = manifestMetadata.providerForClass(component.className());
                if (providerMetadata == null) {
                    for (String authority : component.authority().split(";")) {
                        providerMetadata = manifestMetadata.provider(authority);
                        if (providerMetadata != null) break;
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
                     providerMetadata, component.launchMode(), component.taskAffinity(),
                     component.documentLaunchMode(), component.configChanges(),
                     component.screenOrientation(), component.windowSoftInputMode(), component.flags(),
                     component.excludeFromRecents(), component.noHistory(),
                     component.finishOnTaskLaunch(), component.clearTaskOnLaunch(),
                     component.alwaysRetainTaskState(), component.allowTaskReparenting(),
                     component.resizeMode(), component.maxAspectRatio(), component.minAspectRatio(),
                     component.supportsPictureInPicture(), component.themeResId()));
        }
        List<String> permissions = new ArrayList<>();
        for (com.warden.controlledsandbox.contract.VirtualPermissionSnapshot permission : state.permissions()) {
            permissions.add(permission.name());
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
                    data.add(new VirtualPackageMetadata.DataRule(rule.scheme(), rule.host(),
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
                queryProviderAuthorities, queryIntentFilters);
    }

    static VirtualPackageMetadata fromProjection(VirtualPackageProjectionSnapshot projection) {
        VirtualPackageStateSnapshot state = projection.packageState();
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.packageName = state.packageName();
        applicationInfo.name = state.applicationClass().isEmpty() ? null : state.applicationClass();
        applicationInfo.sourceDir = projection.apkPath();
        applicationInfo.publicSourceDir = projection.apkPath();
        applicationInfo.nativeLibraryDir = projection.nativeLibraryDir();
        applicationInfo.uid = projection.virtualUid();
        applicationInfo.enabled = state.enabled();
        applicationInfo.flags = ApplicationInfo.FLAG_HAS_CODE;
        return fromSnapshot(state, applicationInfo, null);
    }
}
