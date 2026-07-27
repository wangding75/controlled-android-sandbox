package com.warden.controlledsandbox.runtime.status;

import com.warden.controlledsandbox.contract.RuntimeStatusSnapshot;

/** Read-only source for a coherent runtime status snapshot. */
@FunctionalInterface
public interface RuntimeStatusSource {
    RuntimeStatusSnapshot snapshot(long nowMs);
}
