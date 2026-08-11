package com.warden.controlledsandbox.sdk;

/** Immutable virtual-user instance metadata. */
public record SandboxInstance(String packageName, int virtualUserId, String displayName,
                              long createdAt, String lastRuntimeStatus, long lastRuntimeAt) {
    public SandboxInstance {
        if (packageName == null || packageName.isBlank()) throw new IllegalArgumentException("packageName is required");
        if (virtualUserId < 0 || virtualUserId > 999) throw new IllegalArgumentException("virtualUserId out of range");
        displayName = displayName == null ? "" : displayName;
        lastRuntimeStatus = lastRuntimeStatus == null ? "" : lastRuntimeStatus;
    }

    public SandboxIdentity identity(SandboxPackage pkg) {
        return SandboxIdentity.forInstance(packageName, virtualUserId, packageName,
                pkg.versionCode(), pkg.apkSha256());
    }
}
