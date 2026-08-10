package com.warden.controlledsandbox.framework.service;

import com.warden.controlledsandbox.framework.core.DeviceServiceBindingRegistry;
import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.core.SmsServiceContract;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** Descriptor-validated, reversible Android SMS Binder projection. */
public final class SmsServiceHook {
    private SmsServiceHook() { }

    public static AutoCloseable install(GuestIdentity identity) throws Exception {
        String resolvedService = "";
        for (String serviceName : SmsServiceContract.SERVICE_NAMES) {
            String descriptor;
            try {
                descriptor = ReflectiveServiceHook.serviceManagerDescriptor(serviceName);
            } catch (Throwable error) {
                com.warden.controlledsandbox.framework.capability.FatalErrorPolicy.rethrowIfFatal(error);
                throw new IllegalStateException("SMS_SERVICE_DESCRIPTOR_LOOKUP_FAILED:" + serviceName,
                        error);
            }
            if (descriptor.isEmpty()) continue;
            if (!SmsServiceContract.DESCRIPTOR.equals(descriptor)) {
                throw new IllegalStateException("Unexpected Binder descriptor for SMS service "
                        + serviceName + ": " + descriptor + " expected="
                        + SmsServiceContract.DESCRIPTOR);
            }
            resolvedService = serviceName;
            break;
        }

        AutoCloseable serviceBinding;
        String method;
        if (resolvedService.isEmpty()) {
            try {
                serviceBinding = ReflectiveServiceHook.syntheticServiceManagerBindings(
                        SmsServiceContract.SERVICE_NAMES, SmsServiceContract.DESCRIPTOR,
                        SmsServiceContract.LOGICAL_SERVICE, identity);
                method = "synthetic ServiceManager Binder (all bounded aliases absent)";
            } catch (Throwable error) {
                com.warden.controlledsandbox.framework.capability.FatalErrorPolicy.rethrowIfFatal(error);
                if (error instanceof Exception exception) throw exception;
                throw new IllegalStateException("SMS_SERVICE_BINDING_FAILED", error);
            }
            resolvedService = "absent:" + String.join(",", SmsServiceContract.SERVICE_NAMES);
        } else {
            serviceBinding = ReflectiveServiceHook.serviceManagerBinding(
                    resolvedService, SmsServiceContract.LOGICAL_SERVICE,
                    SmsServiceContract.DESCRIPTOR, identity);
            method = "descriptor-validated ServiceManager proxy";
        }

        AutoCloseable cacheBinding;
        try {
            cacheBinding = ReflectiveServiceHook.staticFieldCandidatesWithDescriptorIfPresent(
                    "android.telephony.TelephonyManager", SmsServiceContract.DESCRIPTOR,
                    identity, SmsServiceContract.LOGICAL_SERVICE,
                    SmsServiceContract.TELEPHONY_MANAGER_CACHE_FIELD);
        } catch (Throwable error) {
            try { serviceBinding.close(); } catch (Throwable rollback) { error.addSuppressed(rollback); }
            com.warden.controlledsandbox.framework.capability.FatalErrorPolicy.rethrowIfFatal(error);
            if (error instanceof Exception exception) throw exception;
            throw new IllegalStateException("SMS_MANAGER_CACHE_BINDING_FAILED", error);
        }

        AutoCloseable result = new CompositeBinding(serviceBinding, cacheBinding);
        android.util.Log.i("CS_SMS_PROXY", "SMS_PROXY_READY service=" + resolvedService
                + " descriptor=" + SmsServiceContract.DESCRIPTOR
                + " manager=ServiceManager/TelephonyManager.sISms"
                + " method=" + method + " cache=synchronized-if-populated");
        return new DescribedBinding(result, "service=" + resolvedService
                + "; descriptor=" + SmsServiceContract.DESCRIPTOR
                + "; manager=TelephonyManager.sISms-or-lazy-ServiceManager"
                + "; method=" + method + "; aliases="
                + String.join(",", SmsServiceContract.SERVICE_NAMES));
    }

    private record DescribedBinding(AutoCloseable delegate, String description)
            implements DeviceServiceBindingRegistry.Described {
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
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
            if (failure != null) throw failure;
        }
    }
}
