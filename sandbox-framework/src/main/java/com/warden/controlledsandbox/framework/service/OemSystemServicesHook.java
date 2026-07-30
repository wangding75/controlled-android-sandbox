package com.warden.controlledsandbox.framework.service;

import com.warden.controlledsandbox.contract.VirtualOemSystemServicesProfileSnapshot;
import com.warden.controlledsandbox.framework.core.ServiceManagerBinderHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import java.util.ArrayList;
import java.util.List;

/** Installs configured OEM system Binder services through runtime descriptors. */
public final class OemSystemServicesHook {
    private OemSystemServicesHook() { }

    public static AutoCloseable install(GuestIdentity identity) throws Exception {
        VirtualOemSystemServicesProfileSnapshot profile =
                identity.virtualServices().peripheralServicesProfile().oemSystemServices();
        if (profile.serviceNames().isEmpty()) return () -> { };
        List<AutoCloseable> installed = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (String service : profile.serviceNames()) {
            try {
                installed.add(ServiceManagerBinderHook.installDiscovered(
                        service, identity, "oemSystem"));
            } catch (Exception error) {
                failures.add(service + "=" + error.getClass().getSimpleName());
            }
        }
        if (!failures.isEmpty()) {
            Exception rollbackFailure = null;
            try {
                closeReverse(installed);
            } catch (Exception error) {
                rollbackFailure = error;
            }
            IllegalStateException unavailable = new IllegalStateException(
                    "OEM_SYSTEM_SERVICES_UNAVAILABLE:" + String.join(",", failures));
            if (rollbackFailure != null) unavailable.addSuppressed(rollbackFailure);
            throw unavailable;
        }
        return () -> closeReverse(installed);
    }

    private static void closeReverse(List<AutoCloseable> installed) throws Exception {
        Exception failure = null;
        for (int index = installed.size() - 1; index >= 0; index--) {
            try {
                installed.get(index).close();
            } catch (Exception error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }
}
