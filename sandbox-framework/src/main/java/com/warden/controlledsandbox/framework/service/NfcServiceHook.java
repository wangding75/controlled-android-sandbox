package com.warden.controlledsandbox.framework.service;

import com.warden.controlledsandbox.framework.core.DeviceServiceBindingRegistry;
import com.warden.controlledsandbox.framework.contract.NfcServiceContract;
import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** Descriptor-validated NFC Binder projection with a bounded synthetic fallback. */
public final class NfcServiceHook {
    private NfcServiceHook() { }

    public static AutoCloseable install(GuestIdentity identity) throws Exception {
        String descriptor;
        try {
            descriptor = ReflectiveServiceHook.serviceManagerDescriptor(NfcServiceContract.SERVICE_NAME);
        } catch (Throwable error) {
            com.warden.controlledsandbox.framework.capability.FatalErrorPolicy.rethrowIfFatal(error);
            throw new IllegalStateException("NFC_SERVICE_DESCRIPTOR_LOOKUP_FAILED", error);
        }

        AutoCloseable serviceBinding;
        String resolvedService;
        String method;
        if (descriptor.isEmpty()) {
            serviceBinding = ReflectiveServiceHook.syntheticServiceManagerBindings(
                    NfcServiceContract.SERVICE_NAMES, NfcServiceContract.DESCRIPTOR,
                    NfcServiceContract.LOGICAL_SERVICE, identity);
            resolvedService = "absent:" + String.join(",", NfcServiceContract.SERVICE_NAMES);
            method = "synthetic ServiceManager Binder (all bounded aliases absent)";
        } else {
            if (!NfcServiceContract.DESCRIPTOR.equals(descriptor)) {
                throw new IllegalStateException("Unexpected Binder descriptor for NFC service "
                        + NfcServiceContract.SERVICE_NAME + ": " + descriptor + " expected="
                        + NfcServiceContract.DESCRIPTOR);
            }
            serviceBinding = ReflectiveServiceHook.serviceManagerBinding(
                    NfcServiceContract.SERVICE_NAME, NfcServiceContract.LOGICAL_SERVICE,
                    NfcServiceContract.DESCRIPTOR, identity);
            resolvedService = NfcServiceContract.SERVICE_NAME;
            method = "descriptor-validated ServiceManager proxy";
        }

        AutoCloseable cacheBinding;
        try {
            cacheBinding = ReflectiveServiceHook.staticFieldCandidatesWithDescriptorIfPresent(
                    "android.nfc.NfcAdapter", NfcServiceContract.DESCRIPTOR, identity,
                    NfcServiceContract.LOGICAL_SERVICE, NfcServiceContract.ADAPTER_CACHE_FIELD);
        } catch (Throwable error) {
            try { serviceBinding.close(); } catch (Throwable rollback) { error.addSuppressed(rollback); }
            com.warden.controlledsandbox.framework.capability.FatalErrorPolicy.rethrowIfFatal(error);
            if (error instanceof Exception exception) throw exception;
            throw new IllegalStateException("NFC_MANAGER_CACHE_BINDING_FAILED", error);
        }

        AutoCloseable result = new CompositeBinding(serviceBinding, cacheBinding);
        android.util.Log.i("CS_NFC_PROXY", "NFC_PROXY_READY service=" + resolvedService
                + " descriptor=" + NfcServiceContract.DESCRIPTOR
                + " manager=ServiceManager/NfcAdapter.sService"
                + " method=" + method + " cache=synchronized-if-populated"
                + " feature=" + NfcServiceContract.FEATURE_NFC);
        return new DescribedBinding(result, "service=" + resolvedService
                + "; descriptor=" + NfcServiceContract.DESCRIPTOR
                + "; manager=NfcAdapter.sService-or-lazy-ServiceManager"
                + "; method=" + method + "; feature=" + NfcServiceContract.FEATURE_NFC);
    }

    private static final class DescribedBinding implements DeviceServiceBindingRegistry.Described {
        private final AutoCloseable delegate;
        private final String description;

        private DescribedBinding(AutoCloseable delegate, String description) {
            this.delegate = delegate;
            this.description = description;
        }

        @Override public String description() { return description; }
        @Override public void close() throws Exception { delegate.close(); }
    }

    private static final class CompositeBinding implements AutoCloseable {
        private final AutoCloseable service;
        private final AutoCloseable cache;

        private CompositeBinding(AutoCloseable service, AutoCloseable cache) {
            this.service = service;
            this.cache = cache;
        }

        @Override public void close() throws Exception {
            Exception failure = null;
            try { cache.close(); } catch (Exception error) { failure = error; }
            try { service.close(); } catch (Exception error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
            if (failure != null) throw failure;
        }
    }
}
