package com.warden.controlledsandbox.runtime.guest;

import android.content.pm.ApplicationInfo;
import com.warden.controlledsandbox.contract.VirtualComponentSnapshot;
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
            components.add(new VirtualPackageMetadata.Component(
                    VirtualPackageMetadata.Type.valueOf(component.type()),
                    component.className(), component.processName(), component.exported(),
                    component.enabled(), component.isolated(),
                    new LinkedHashSet<>(component.actions()), component.authority(),
                    component.permission()));
        }
        return new VirtualPackageMetadata(state.packageName(), state.launchActivity(),
                applicationInfo, components);
    }
}
