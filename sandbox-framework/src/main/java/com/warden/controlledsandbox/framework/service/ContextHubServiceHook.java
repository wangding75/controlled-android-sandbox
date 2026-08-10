package com.warden.controlledsandbox.framework.service;

import android.content.Context;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPrivilegedServicesProfileSnapshot;
import com.warden.controlledsandbox.framework.core.ContextHubServiceContract;
import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.core.ServiceManagerBinderHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** Descriptor-validated Context Hub Binder projection with controlled-unavailable fallback. */
public final class ContextHubServiceHook {
    private ContextHubServiceHook() { }

    public static AutoCloseable install(Context hostServiceContext, GuestIdentity identity)
            throws Exception {
        String descriptor = ReflectiveServiceHook.serviceManagerDescriptor(
                ContextHubServiceContract.SERVICE_NAME);
        if (descriptor.isEmpty()) {
            requireSyntheticAllowed(identity);
            AutoCloseable serviceBinding = ReflectiveServiceHook.syntheticServiceManagerBindings(
                    ContextHubServiceContract.SERVICE_NAMES,
                    ContextHubServiceContract.DESCRIPTOR,
                    ContextHubServiceContract.LOGICAL_SERVICE,
                    identity);
            try {
                // ContextHubManager is lazy on API32/API35.  Resolving it after the synthetic
                // ServiceManager entry is installed makes its mService cache virtual as well.
                AutoCloseable managerBinding = ReflectiveServiceHook.managerFieldCandidates(
                        hostServiceContext,
                        ContextHubServiceContract.SERVICE_NAME,
                        ContextHubServiceContract.LOGICAL_SERVICE,
                        identity,
                        ContextHubServiceContract.MANAGER_SERVICE_FIELD);
                return new CompositeBinding(serviceBinding, managerBinding);
            } catch (Throwable error) {
                try { serviceBinding.close(); } catch (Throwable rollback) {
                    error.addSuppressed(rollback);
                }
                com.warden.controlledsandbox.framework.capability.FatalErrorPolicy
                        .rethrowIfFatal(error);
                if (error instanceof Exception exception) throw exception;
                throw new IllegalStateException("CONTEXT_HUB_SYNTHETIC_MANAGER_BINDING_FAILED", error);
            }
        }

        if (!ContextHubServiceContract.DESCRIPTOR.equals(descriptor)) {
            throw new IllegalStateException("Unexpected Binder descriptor for Context Hub service "
                    + ContextHubServiceContract.SERVICE_NAME + ": " + descriptor + " expected="
                    + ContextHubServiceContract.DESCRIPTOR);
        }
        AutoCloseable serviceBinding = ReflectiveServiceHook.serviceManagerBinding(
                ContextHubServiceContract.SERVICE_NAME,
                ContextHubServiceContract.LOGICAL_SERVICE,
                ContextHubServiceContract.DESCRIPTOR,
                identity);
        try {
            AutoCloseable managerBinding = ReflectiveServiceHook.managerFieldCandidatesWithDescriptor(
                    hostServiceContext,
                    ContextHubServiceContract.SERVICE_NAME,
                    ContextHubServiceContract.LOGICAL_SERVICE,
                    ContextHubServiceContract.DESCRIPTOR,
                    identity,
                    ContextHubServiceContract.MANAGER_SERVICE_FIELD);
            return new CompositeBinding(serviceBinding, managerBinding);
        } catch (Throwable error) {
            try { serviceBinding.close(); } catch (Throwable rollback) {
                error.addSuppressed(rollback);
            }
            com.warden.controlledsandbox.framework.capability.FatalErrorPolicy.rethrowIfFatal(error);
            if (error instanceof Exception exception) throw exception;
            throw new IllegalStateException("CONTEXT_HUB_MANAGER_BINDING_FAILED", error);
        }
    }

    /** Compatibility overload retained for callers that do not have a Context cache owner. */
    public static AutoCloseable install(GuestIdentity identity) throws Exception {
        return ServiceManagerBinderHook.install(
                ContextHubServiceContract.SERVICE_NAME,
                ContextHubServiceContract.DESCRIPTOR + "$Stub",
                identity,
                ContextHubServiceContract.LOGICAL_SERVICE);
    }

    private static void requireSyntheticAllowed(GuestIdentity identity) {
        VirtualPrivilegedServicesProfileSnapshot profile =
                identity.virtualServices().privilegedServicesProfile();
        if (VirtualLocationProfileSnapshot.MODE_HOST.equals(profile.contextHub().mode())) {
            throw new IllegalStateException("CONTEXT_HUB_HOST_SERVICE_REQUIRED");
        }
    }

    private static final class CompositeBinding implements AutoCloseable {
        private final AutoCloseable service;
        private final AutoCloseable manager;

        private CompositeBinding(AutoCloseable service, AutoCloseable manager) {
            this.service = service;
            this.manager = manager;
        }

        @Override public void close() throws Exception {
            Exception failure = null;
            try { manager.close(); } catch (Exception error) { failure = error; }
            try { service.close(); } catch (Exception error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
            if (failure != null) throw failure;
        }
    }
}
