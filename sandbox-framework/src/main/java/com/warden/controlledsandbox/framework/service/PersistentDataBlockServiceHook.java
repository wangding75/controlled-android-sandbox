package com.warden.controlledsandbox.framework.service;

import android.content.Context;
import android.os.Build;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPrivilegedServicesProfileSnapshot;
import com.warden.controlledsandbox.framework.core.PersistentDataBlockServiceContract;
import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** Descriptor-validated PersistentDataBlock projection with a controlled virtual fallback. */
public final class PersistentDataBlockServiceHook {
    private PersistentDataBlockServiceHook() { }

    public static AutoCloseable install(Context hostServiceContext, GuestIdentity identity)
            throws Exception {
        if (identity == null) throw new NullPointerException("identity");
        boolean managerRequired = PersistentDataBlockServiceContract.managerCacheRequired(
                Build.VERSION.SDK_INT);
        if (managerRequired && hostServiceContext == null) {
            throw new IllegalStateException("PERSISTENT_DATA_BLOCK_MANAGER_CONTEXT_REQUIRED");
        }

        String descriptor = ReflectiveServiceHook.serviceManagerDescriptor(
                PersistentDataBlockServiceContract.SERVICE_NAME);
        if (descriptor.isEmpty()) {
            requireSyntheticAllowed(identity);
            AutoCloseable serviceBinding = ReflectiveServiceHook.syntheticServiceManagerBindings(
                    PersistentDataBlockServiceContract.SERVICE_NAMES,
                    PersistentDataBlockServiceContract.DESCRIPTOR,
                    PersistentDataBlockServiceContract.LOGICAL_SERVICE,
                    identity);
            try {
                AutoCloseable managerBinding = managerRequired
                        ? ReflectiveServiceHook.managerFieldCandidates(
                                hostServiceContext,
                                PersistentDataBlockServiceContract.SERVICE_NAME,
                                PersistentDataBlockServiceContract.LOGICAL_SERVICE,
                                identity,
                                PersistentDataBlockServiceContract.MANAGER_SERVICE_FIELD)
                        : () -> { };
                android.util.Log.i("CS_PERSISTENT_DATA_BLOCK",
                        "PERSISTENT_DATA_BLOCK_READY path=synthetic service="
                                + PersistentDataBlockServiceContract.SERVICE_NAME
                                + " descriptor=" + PersistentDataBlockServiceContract.DESCRIPTOR
                                + " managerCache=" + (managerRequired
                                ? PersistentDataBlockServiceContract.MANAGER_CLASS + "."
                                        + PersistentDataBlockServiceContract.MANAGER_SERVICE_FIELD
                                : "service-manager-only-api32"));
                return new CompositeBinding(serviceBinding, managerBinding);
            } catch (Throwable error) {
                closeWithSuppressed(error, serviceBinding);
                rethrow(error, "PERSISTENT_DATA_BLOCK_SYNTHETIC_MANAGER_BINDING_FAILED");
                throw new AssertionError(error);
            }
        }

        if (!PersistentDataBlockServiceContract.DESCRIPTOR.equals(descriptor)) {
            throw new IllegalStateException("Unexpected Binder descriptor for "
                    + PersistentDataBlockServiceContract.SERVICE_NAME + ": " + descriptor
                    + " expected=" + PersistentDataBlockServiceContract.DESCRIPTOR);
        }

        // Bind the manager first while its cache still contains the real, descriptor-validated
        // framework interface.  The ServiceManager replacement then covers lazy constructors;
        // reversing this order would make manager-cache validation inspect our local proxy.
        AutoCloseable managerBinding = managerRequired
                ? ReflectiveServiceHook.managerFieldCandidatesWithDescriptor(
                        hostServiceContext,
                        PersistentDataBlockServiceContract.SERVICE_NAME,
                        PersistentDataBlockServiceContract.LOGICAL_SERVICE,
                        PersistentDataBlockServiceContract.DESCRIPTOR,
                        identity,
                        PersistentDataBlockServiceContract.MANAGER_SERVICE_FIELD)
                : () -> { };
        try {
            AutoCloseable serviceBinding = ReflectiveServiceHook.serviceManagerBinding(
                    PersistentDataBlockServiceContract.SERVICE_NAME,
                    PersistentDataBlockServiceContract.LOGICAL_SERVICE,
                    PersistentDataBlockServiceContract.DESCRIPTOR,
                    identity);
            android.util.Log.i("CS_PERSISTENT_DATA_BLOCK",
                    "PERSISTENT_DATA_BLOCK_READY path=real service="
                            + PersistentDataBlockServiceContract.SERVICE_NAME
                            + " descriptor=" + descriptor
                            + " managerCache=" + (managerRequired
                            ? PersistentDataBlockServiceContract.MANAGER_CLASS + "."
                                    + PersistentDataBlockServiceContract.MANAGER_SERVICE_FIELD
                            : "service-manager-only-api32"));
            return new CompositeBinding(serviceBinding, managerBinding);
        } catch (Throwable error) {
            closeWithSuppressed(error, managerBinding);
            rethrow(error, "PERSISTENT_DATA_BLOCK_BINDING_FAILED");
            throw new AssertionError(error);
        }
    }

    /** Compatibility overload for callers which only need the API32 ServiceManager boundary. */
    public static AutoCloseable install(GuestIdentity identity) throws Exception {
        return install(null, identity);
    }

    private static void requireSyntheticAllowed(GuestIdentity identity) {
        VirtualPrivilegedServicesProfileSnapshot profile =
                identity.virtualServices().privilegedServicesProfile();
        if (VirtualLocationProfileSnapshot.MODE_HOST.equals(
                profile.persistentDataBlock().mode())) {
            throw new IllegalStateException("PERSISTENT_DATA_BLOCK_HOST_SERVICE_REQUIRED");
        }
    }

    private static void closeWithSuppressed(Throwable error, AutoCloseable hook) {
        try {
            hook.close();
        } catch (Throwable rollback) {
            error.addSuppressed(rollback);
        }
    }

    private static void rethrow(Throwable error, String fallback) throws Exception {
        com.warden.controlledsandbox.framework.capability.FatalErrorPolicy.rethrowIfFatal(error);
        if (error instanceof Exception exception) throw exception;
        throw new IllegalStateException(fallback, error);
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
