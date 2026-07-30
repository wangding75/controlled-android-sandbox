package com.warden.controlledsandbox.framework.service;

import com.warden.controlledsandbox.contract.VirtualOemProfileSnapshot;
import com.warden.controlledsandbox.framework.core.ServiceManagerBinderHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import java.util.ArrayList;
import java.util.List;

/** Installs configured OEM identifier Binder services by runtime interface descriptor. */
public final class OemIdentifierServiceHook {
    private OemIdentifierServiceHook() { }

    public static AutoCloseable install(GuestIdentity identity) throws Exception {
        VirtualOemProfileSnapshot profile =
                identity.virtualServices().compatibilityProfile().oem();
        if (profile.availableServices().isEmpty()) {
            return () -> { };
        }

        List<AutoCloseable> installed = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (String service : profile.availableServices()) {
            try {
                installed.add(ServiceManagerBinderHook.installDiscovered(
                        service, identity, "oemidentifier"));
            } catch (Exception error) {
                failures.add(service + "=" + error.getClass().getSimpleName());
            }
        }
        if (installed.isEmpty()) {
            throw new IllegalStateException(
                    "OEM_IDENTIFIER_SERVICES_UNAVAILABLE:" + String.join(",", failures));
        }
        return () -> {
            Exception failure = null;
            for (int index = installed.size() - 1; index >= 0; index--) {
                try {
                    installed.get(index).close();
                } catch (Exception error) {
                    if (failure == null) {
                        failure = error;
                    } else {
                        failure.addSuppressed(error);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        };
    }
}
