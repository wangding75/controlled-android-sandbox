package com.warden.controlledsandbox.framework.service;

import android.content.Context;
import android.os.Build;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPrivilegedServicesProfileSnapshot;
import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.core.SystemUpdateServiceContract;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** Descriptor-validated SystemUpdateManager projection with controlled-unavailable fallback. */
public final class SystemUpdateServiceHook {
    private SystemUpdateServiceHook() { }

    public static AutoCloseable install(Context hostServiceContext, GuestIdentity identity)
            throws Exception {
        if (identity == null) throw new NullPointerException("identity");
        boolean managerRequired = SystemUpdateServiceContract.managerCacheRequired(
                Build.VERSION.SDK_INT);
        if (managerRequired && hostServiceContext == null) {
            throw new IllegalStateException("SYSTEM_UPDATE_MANAGER_CONTEXT_REQUIRED");
        }

        String descriptor = ReflectiveServiceHook.serviceManagerDescriptor(
                SystemUpdateServiceContract.SERVICE_NAME);
        AutoCloseable serviceBinding;
        String path;
        if (descriptor.isEmpty()) {
            requireSyntheticAllowed(identity);
            serviceBinding = ReflectiveServiceHook.syntheticServiceManagerBindings(
                    SystemUpdateServiceContract.SERVICE_NAMES,
                    SystemUpdateServiceContract.DESCRIPTOR,
                    SystemUpdateServiceContract.LOGICAL_SERVICE,
                    identity);
            path = "synthetic";
        } else {
            if (!SystemUpdateServiceContract.DESCRIPTOR.equals(descriptor)) {
                throw new IllegalStateException("Unexpected Binder descriptor for "
                        + SystemUpdateServiceContract.SERVICE_NAME + ": " + descriptor
                        + " expected=" + SystemUpdateServiceContract.DESCRIPTOR);
            }
            serviceBinding = ReflectiveServiceHook.serviceManagerBinding(
                    SystemUpdateServiceContract.SERVICE_NAME,
                    SystemUpdateServiceContract.LOGICAL_SERVICE,
                    SystemUpdateServiceContract.DESCRIPTOR,
                    identity);
            path = "real";
        }

        try {
            AutoCloseable managerBinding = managerRequired
                    ? ReflectiveServiceHook.managerFieldCandidates(
                            hostServiceContext,
                            SystemUpdateServiceContract.SERVICE_NAME,
                            SystemUpdateServiceContract.LOGICAL_SERVICE,
                            identity,
                            SystemUpdateServiceContract.MANAGER_SERVICE_FIELD)
                    : () -> { };
            android.util.Log.i("CS_SYSTEM_UPDATE", "SYSTEM_UPDATE_READY path=" + path
                    + " service=" + SystemUpdateServiceContract.SERVICE_NAME
                    + " descriptor=" + SystemUpdateServiceContract.DESCRIPTOR
                    + " manager=" + SystemUpdateServiceContract.MANAGER_CLASS + "."
                    + SystemUpdateServiceContract.MANAGER_SERVICE_FIELD);
            return new CompositeBinding(serviceBinding, managerBinding);
        } catch (Throwable error) {
            try { serviceBinding.close(); } catch (Throwable rollback) {
                error.addSuppressed(rollback);
            }
            com.warden.controlledsandbox.framework.capability.FatalErrorPolicy
                    .rethrowIfFatal(error);
            if (error instanceof Exception exception) throw exception;
            throw new IllegalStateException("SYSTEM_UPDATE_MANAGER_BINDING_FAILED", error);
        }
    }

    /** Compatibility overload for callers that only use the ServiceManager boundary. */
    public static AutoCloseable install(GuestIdentity identity) throws Exception {
        return install(null, identity);
    }

    private static void requireSyntheticAllowed(GuestIdentity identity) {
        VirtualPrivilegedServicesProfileSnapshot profile =
                identity.virtualServices().privilegedServicesProfile();
        if (VirtualLocationProfileSnapshot.MODE_HOST.equals(
                profile.systemUpdate().mode())) {
            throw new IllegalStateException("SYSTEM_UPDATE_HOST_SERVICE_REQUIRED");
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
