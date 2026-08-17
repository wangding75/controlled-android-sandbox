package com.warden.controlledsandbox.runtime.hostile;

import android.os.ParcelFileDescriptor;
import com.warden.controlledsandbox.contract.HostileCapabilityRequest;
import com.warden.controlledsandbox.contract.HostileCapabilityResult;
import com.warden.controlledsandbox.contract.IHostileCapabilityBroker;
import java.io.File;

/** Production Broker Binder. Never accepts a host path or arbitrary endpoint from the child. */
public final class HostileCapabilityBrokerStub extends IHostileCapabilityBroker.Stub {
    private final HostileCapabilityRegistry registry;

    public HostileCapabilityBrokerStub(HostileCapabilityRegistry registry) {
        if (registry == null) throw new IllegalArgumentException("registry is required");
        this.registry = registry;
    }

    @Override public HostileCapabilityResult readResource(HostileCapabilityRequest request) {
        try {
            HostileCapabilityRegistry.Record record = registry.require(request,
                    HostileCapabilityRequest.OP_READ_RESOURCE);
            String body = registry.readFile(record);
            if (!record.expectedBody.isEmpty() && !record.expectedBody.equals(body)) {
                return HostileCapabilityResult.denied("SENTINEL_MISMATCH");
            }
            return HostileCapabilityResult.success("PASS_CAPABILITY", body);
        } catch (SecurityException denied) {
            return HostileCapabilityResult.denied(denied.getMessage());
        } catch (Exception error) {
            return HostileCapabilityResult.denied("BROKER_FS_ERROR");
        }
    }

    @Override public HostileCapabilityResult networkRequest(HostileCapabilityRequest request) {
        try {
            HostileCapabilityRegistry.Record record = registry.require(request,
                    HostileCapabilityRequest.OP_NETWORK_REQUEST);
            String body = registry.connectLoopback(record);
            return HostileCapabilityResult.success("PASS_CAPABILITY", body);
        } catch (SecurityException denied) {
            return HostileCapabilityResult.denied(denied.getMessage());
        } catch (Exception error) {
            return HostileCapabilityResult.denied("BROKER_NET_ERROR");
        }
    }

    @Override public HostileCapabilityResult delegateReadOnlyFd(HostileCapabilityRequest request) {
        try {
            HostileCapabilityRegistry.Record record = registry.require(request,
                    HostileCapabilityRequest.OP_DELEGATE_FD);
            ParcelFileDescriptor fd = ParcelFileDescriptor.open(new File(record.hostPath()),
                    ParcelFileDescriptor.MODE_READ_ONLY);
            return HostileCapabilityResult.successFd("PASS_FD", fd);
        } catch (SecurityException denied) {
            return HostileCapabilityResult.denied(denied.getMessage());
        } catch (Exception error) {
            return HostileCapabilityResult.denied("BROKER_FD_ERROR");
        }
    }
}
