package com.warden.controlledsandbox.framework.service;

import android.content.Context;
import com.warden.controlledsandbox.framework.core.CameraServiceContract;
import com.warden.controlledsandbox.framework.core.DeviceServiceBindingRegistry;
import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** Descriptor-validated, reversible Camera Binder and CameraManagerGlobal cache projection. */
public final class CameraServiceHook {
    private CameraServiceHook() { }

    public static AutoCloseable install(Context context, GuestIdentity identity) throws Exception {
        String descriptor;
        try {
            descriptor = ReflectiveServiceHook.serviceManagerDescriptor(
                    CameraServiceContract.SERVICE_NAME);
        } catch (Throwable error) {
            com.warden.controlledsandbox.framework.capability.FatalErrorPolicy.rethrowIfFatal(error);
            throw new IllegalStateException("CAMERA_SERVICE_DESCRIPTOR_LOOKUP_FAILED", error);
        }

        AutoCloseable serviceBinding;
        String resolvedService;
        String method;
        if (descriptor.isEmpty()) {
            serviceBinding = ReflectiveServiceHook.syntheticServiceManagerBindings(
                    CameraServiceContract.SERVICE_NAMES, CameraServiceContract.DESCRIPTOR,
                    CameraServiceContract.LOGICAL_SERVICE, identity);
            resolvedService = "absent:" + String.join(",", CameraServiceContract.SERVICE_NAMES);
            method = "synthetic ServiceManager Binder (bounded camera alias absent)";
        } else {
            if (!CameraServiceContract.DESCRIPTOR.equals(descriptor)) {
                throw new IllegalStateException("Unexpected Binder descriptor for Camera service "
                        + CameraServiceContract.SERVICE_NAME + ": " + descriptor + " expected="
                        + CameraServiceContract.DESCRIPTOR);
            }
            serviceBinding = ReflectiveServiceHook.serviceManagerBinding(
                    CameraServiceContract.SERVICE_NAME, CameraServiceContract.LOGICAL_SERVICE,
                    CameraServiceContract.DESCRIPTOR, identity);
            resolvedService = CameraServiceContract.SERVICE_NAME;
            method = "descriptor-validated ServiceManager proxy";
        }

        AutoCloseable managerCache = null;
        try {
            // CameraManagerGlobal is lazy on both target framework contracts.  If mCameraService
            // is already populated, replace and validate that exact cache entry; otherwise the
            // ServiceManager binding is the bounded source used by CameraManagerGlobal.get().
            managerCache = ReflectiveServiceHook.staticInstanceFieldCandidatesWithDescriptorIfPresent(
                    CameraServiceContract.CAMERA_MANAGER_GLOBAL_CLASS,
                    CameraServiceContract.CAMERA_MANAGER_GLOBAL_GETTER, identity,
                    CameraServiceContract.LOGICAL_SERVICE, CameraServiceContract.DESCRIPTOR,
                    CameraServiceContract.CAMERA_MANAGER_SERVICE_FIELD);
        } catch (Throwable error) {
            try { serviceBinding.close(); } catch (Throwable rollback) { error.addSuppressed(rollback); }
            com.warden.controlledsandbox.framework.capability.FatalErrorPolicy.rethrowIfFatal(error);
            if (error instanceof Exception exception) throw exception;
            throw new IllegalStateException("CAMERA_MANAGER_GLOBAL_CACHE_BINDING_FAILED", error);
        }

        AutoCloseable result = ReflectiveServiceHook.compose(managerCache, serviceBinding);
        android.util.Log.i("CS_CAMERA_PROXY", "CAMERA_PROXY_READY service=" + resolvedService
                + " descriptor=" + CameraServiceContract.DESCRIPTOR
                + " manager=CameraManagerGlobal.mCameraService-or-lazy-ServiceManager"
                + " method=" + method + " cache=synchronized-if-populated"
                + " features=" + CameraServiceContract.FEATURE_CAMERA + ","
                + CameraServiceContract.FEATURE_CAMERA_FRONT);
        return new DescribedBinding(result, "service=" + resolvedService
                + "; descriptor=" + CameraServiceContract.DESCRIPTOR
                + "; manager=CameraManagerGlobal.mCameraService-or-lazy-ServiceManager"
                + "; method=" + method + "; cache=synchronized-if-populated"
                + "; features=" + CameraServiceContract.FEATURE_CAMERA + ","
                + CameraServiceContract.FEATURE_CAMERA_FRONT);
    }

    private record DescribedBinding(AutoCloseable delegate, String description)
            implements DeviceServiceBindingRegistry.Described {
        @Override public String description() { return description; }
        @Override public void close() throws Exception { delegate.close(); }
    }
}
