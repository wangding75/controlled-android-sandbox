package com.warden.controlledsandbox.runtime.broker;

import android.os.Bundle;
import com.warden.controlledsandbox.contract.VirtualComponentSnapshot;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

/** Resolves manifest isolated components before ordinary Guest process allocation. */
final class IsolatedProcessRoutePolicy {
    static final class Match {
        private final VirtualComponentSnapshot component;
        private final String normalizedClass;
        private final String isolatedProcessName;

        Match(VirtualComponentSnapshot component, String normalizedClass, String isolatedProcessName) {
            this.component = component;
            this.normalizedClass = normalizedClass;
            this.isolatedProcessName = isolatedProcessName;
        }

        VirtualComponentSnapshot component() { return component; }
        String componentClass() { return normalizedClass; }
        String processName() { return isolatedProcessName; }
    }

    private IsolatedProcessRoutePolicy() { }

    /**
     * NBB's bindIsolatedService hook clears the instance name and enters ordinary virtual
     * BindServiceCommon. Keep that compatibility decision explicit and limited to the service
     * binding operations emitted by GuestContext; every other isolated component still requires
     * the dedicated platform transport.
     */
    static boolean isVirtualBindFallback(Bundle request) {
        if (request == null
                || !request.getBoolean(RuntimeKeys.ISOLATED_SERVICE_BIND_FALLBACK, false)) {
            return false;
        }
        String operation = request.getString(ComponentOperations.OPERATION, "");
        return ComponentOperations.BIND_SERVICE.equals(operation)
                || ComponentOperations.UNBIND_SERVICE.equals(operation)
                || ComponentOperations.ROUTE_FRAMEWORK_SERVICE.equals(operation)
                // A framework-owned Service callback is the second half of the same ordinary
                // virtual bind transaction. Keep its Broker lifecycle commit on the virtual
                // process selected above; it must not re-enter the dedicated isolated worker.
                || ComponentOperations.FRAMEWORK_SERVICE_EVENT.equals(operation);
    }

    static Match match(Bundle request) {
        if (request == null) return null;
        if (isVirtualBindFallback(request)) return null;
        // API 32 may restore a Binder-delivered Bundle with the boot class loader.  Set the
        // contract loader before reading the Parcelable or the runtime peer dies with a
        // NoClassDefFoundError instead of reaching the isolated-route policy.
        request.setClassLoader(VirtualPackageStateSnapshot.class.getClassLoader());
        VirtualPackageStateSnapshot state = request.getParcelable(RuntimeKeys.PACKAGE_STATE);
        if (state == null) return null;
        String requestedClass = value(request.getString(RuntimeKeys.COMPONENT_CLASS, ""));
        String requestedAuthority = value(request.getString(ComponentOperations.AUTHORITY, ""));
        if (requestedClass.isEmpty() && requestedAuthority.isEmpty()) return null;
        for (VirtualComponentSnapshot component : state.components()) {
            if (!component.isolated()) continue;
            if (matchesClass(state.packageName(), requestedClass, component.className())
                    || matchesAuthority(requestedAuthority, component.authority())) {
                String normalized = expand(state.packageName(), component.className());
                return new Match(component, normalized,
                        state.packageName() + ":isolated_" + safe(normalized));
            }
        }
        return null;
    }

    static void rejectOrdinaryRoute(Bundle request) {
        Match match = match(request);
        if (match != null) {
            throw new UnsupportedOperationException(
                    "ISOLATED_PROCESS_DEDICATED_UID_TRANSPORT_REQUIRED:" + match.componentClass());
        }
    }

    static Match requireIsolatedService(Bundle request) {
        Match match = match(request);
        if (match == null) throw new IllegalArgumentException("ISOLATED_SERVICE_COMPONENT_NOT_FOUND");
        if (!"SERVICE".equals(match.component().type())) {
            throw new UnsupportedOperationException(
                    "ISOLATED_PROCESS_ONLY_SERVICE_SUPPORTED:" + match.componentClass());
        }
        String operation = request.getString(ComponentOperations.OPERATION, "");
        if (!ComponentOperations.isServiceOperation(operation)) {
            throw new UnsupportedOperationException(
                    "ISOLATED_PROCESS_NON_SERVICE_OPERATION_REJECTED:" + operation);
        }
        return match;
    }

    private static boolean matchesClass(String packageName, String requested, String declared) {
        if (requested.isEmpty() || declared == null || declared.trim().isEmpty()) return false;
        return expand(packageName, requested).equals(expand(packageName, declared));
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

    private static String safe(String value) { return value.replaceAll("[^A-Za-z0-9_]", "_"); }
    private static String value(String value) { return value == null ? "" : value.trim(); }
}
