package com.warden.controlledsandbox.runtime.provider;

import android.os.Bundle;
import com.warden.controlledsandbox.contract.VirtualComponentSnapshot;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
import com.warden.controlledsandbox.contract.VirtualProviderPathRuleSnapshot;
import com.warden.controlledsandbox.domain.component.provider.ProviderPathRule;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Resolves authoritative Provider security metadata from the prepared package revision. */
final class ProviderManifestAuthorityResolver {
    record Metadata(boolean exported, String readPermission, String writePermission,
                    boolean grantUriPermissions, List<ProviderPathRule> pathRules) { }

    private ProviderManifestAuthorityResolver() { }

    static Metadata resolve(Bundle request, String componentClass, String authority) {
        VirtualComponentSnapshot provider = requireProvider(request, componentClass, authority);
        List<ProviderPathRule> pathRules = new ArrayList<>();
        for (VirtualProviderPathRuleSnapshot rule : provider.providerPathRules()) {
            pathRules.add(new ProviderPathRule(rule.path(), rule.pathPrefix(), rule.pathPattern(),
                    rule.readPermission(), rule.writePermission(), rule.uriGrantRule()));
        }
        return new Metadata(provider.exported(), provider.readPermission(), provider.writePermission(),
                provider.grantUriPermissions(), Collections.unmodifiableList(pathRules));
    }

    private static VirtualComponentSnapshot requireProvider(Bundle request, String componentClass,
                                                             String authority) {
        VirtualPackageStateSnapshot state = request.getParcelable(RuntimeKeys.PACKAGE_STATE);
        if (state == null) {
            if (!request.getString(RuntimeKeys.PACKAGE_NAME, "").trim().isEmpty()) {
                throw new SecurityException("PROVIDER_PACKAGE_STATE_REQUIRED");
            }
            // Direct domain harness compatibility only; production requests always carry packageName.
            return new VirtualComponentSnapshot("PROVIDER", componentClass, "",
                    request.getBoolean(RuntimeKeys.PROVIDER_EXPORTED, false), true, false,
                    authority, "", "", "", true, "DEFAULT", List.of(), List.of(), List.of());
        }
        for (VirtualComponentSnapshot component : state.components()) {
            if (!"PROVIDER".equals(component.type()) || !component.className().equals(componentClass)) continue;
            for (String declared : component.authority().split(";")) {
                if (authority.equals(declared.trim())) return component;
            }
        }
        throw new SecurityException("PROVIDER_METADATA_MISMATCH:" + authority);
    }
}
