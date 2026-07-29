package com.warden.controlledsandbox.runtime.broker;

import android.os.Bundle;
import com.warden.controlledsandbox.contract.VirtualComponentSnapshot;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

/** Rejects isolated components before they can be allocated to an ordinary Guest process slot. */
final class IsolatedProcessRoutePolicy {
    private IsolatedProcessRoutePolicy() { }

    static void rejectOrdinaryRoute(Bundle request) {
        if (request == null) return;
        VirtualPackageStateSnapshot state = request.getParcelable(RuntimeKeys.PACKAGE_STATE);
        if (state == null) return;
        String requestedClass = value(request.getString(RuntimeKeys.COMPONENT_CLASS, ""));
        String requestedAuthority = value(request.getString(ComponentOperations.AUTHORITY, ""));
        if (requestedClass.isEmpty() && requestedAuthority.isEmpty()) return;
        for (VirtualComponentSnapshot component : state.components()) {
            if (!component.isolated()) continue;
            if (matchesClass(state.packageName(), requestedClass, component.className())
                    || matchesAuthority(requestedAuthority, component.authority())) {
                throw new UnsupportedOperationException(
                        "ISOLATED_PROCESS_DEDICATED_UID_TRANSPORT_REQUIRED:" + component.className());
            }
        }
    }

    private static boolean matchesClass(String packageName, String requested, String declared) {
        if (requested.isEmpty() || declared == null || declared.trim().isEmpty()) return false;
        String normalizedRequested = expand(packageName, requested);
        String normalizedDeclared = expand(packageName, declared);
        return normalizedRequested.equals(normalizedDeclared);
    }

    private static boolean matchesAuthority(String requested, String declared) {
        if (requested.isEmpty() || declared == null || declared.trim().isEmpty()) return false;
        for (String authority : declared.split(";")) {
            if (requested.equals(authority.trim())) return true;
        }
        return false;
    }

    private static String expand(String packageName, String className) {
        String normalized = value(className);
        if (normalized.startsWith(".")) return packageName + normalized;
        if (normalized.indexOf('.') < 0) return packageName + "." + normalized;
        return normalized;
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }
}
