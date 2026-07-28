package com.warden.controlledsandbox.runtime.guest;

import android.content.pm.ApplicationInfo;
import com.warden.controlledsandbox.contract.VirtualComponentSnapshot;
import com.warden.controlledsandbox.contract.VirtualIntentDataSnapshot;
import com.warden.controlledsandbox.contract.VirtualIntentFilterSnapshot;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
import com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Maps the package authority's immutable snapshot into the process-local PackageManager model. */
final class GuestPackageMetadataMapper {
    private GuestPackageMetadataMapper() { }

    static VirtualPackageMetadata fromSnapshot(VirtualPackageStateSnapshot state,
                                               ApplicationInfo applicationInfo) {
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
            components.add(new VirtualPackageMetadata.Component(
                    VirtualPackageMetadata.Type.valueOf(component.type()),
                    component.className(), component.processName(), component.exported(),
                    component.enabled(), component.isolated(),
                    new LinkedHashSet<>(component.actions()), component.authority(),
                    component.permission(), component.enabledSetting(), filters));
        }
        List<String> permissions = new ArrayList<>();
        for (com.warden.controlledsandbox.contract.VirtualPermissionSnapshot permission : state.permissions()) {
            permissions.add(permission.name());
        }
        return new VirtualPackageMetadata(state.packageName(), state.launchActivity(),
                applicationInfo, components, state.versionName(), state.versionCode(),
                state.signatureSha256(), state.firstInstallTime(), state.lastUpdateTime(),
                state.installerPackageName(), state.sharedLibraries(), permissions, state.enabled());
    }
}
