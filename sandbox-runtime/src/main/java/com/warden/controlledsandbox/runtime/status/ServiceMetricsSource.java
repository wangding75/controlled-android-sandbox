package com.warden.controlledsandbox.runtime.status;

/** Read-only Service ownership metric consumed by runtime status reporting. */
@FunctionalInterface
public interface ServiceMetricsSource {
    int recordCount();
}
