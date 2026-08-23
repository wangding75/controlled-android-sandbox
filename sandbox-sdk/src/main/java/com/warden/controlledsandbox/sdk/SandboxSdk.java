package com.warden.controlledsandbox.sdk;

/** Public SX contract. Implementations may depend on the app adapter, never on runtime internals. */
public interface SandboxSdk extends AutoCloseable {
    SandboxCatalog catalog() throws Exception;
    SandboxOperationResult importPackage(String source) throws Exception;
    SandboxOperationResult importInstalledApplication(String packageName, String nativeGuestTrust)
            throws Exception;
    SandboxOperationResult ensureInstance(String packageName, int virtualUserId) throws Exception;
    SandboxOperationResult cloneInstance(String packageName) throws Exception;
    SandboxOperationResult launch(SandboxIdentity identity) throws Exception;
    SandboxOperationResult stop(SandboxIdentity identity) throws Exception;
    SandboxOperationResult stopAll() throws Exception;
    SandboxOperationResult clearData(SandboxIdentity identity) throws Exception;
    SandboxOperationResult deleteInstance(SandboxIdentity identity) throws Exception;
    SandboxOperationResult status() throws Exception;

    @Override void close();
}
