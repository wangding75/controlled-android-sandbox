package com.warden.controlledsandbox.sdk;

/**
 * Observes CAS catalog and engine status after each adapter operation.
 * Implementations must not cache these snapshots as an authority store.
 */
public interface SandboxEngineObserver {
    void onOperation(SandboxOperationResult result);
    void onCatalogChanged(SandboxCatalog catalog);
    void onStatusChanged(SandboxOperationResult status);
}
