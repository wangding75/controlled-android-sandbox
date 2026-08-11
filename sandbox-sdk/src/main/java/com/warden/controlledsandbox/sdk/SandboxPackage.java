package com.warden.controlledsandbox.sdk;

/** Immutable package metadata exposed to SX adapters and UI. */
public record SandboxPackage(String packageName, String label, String versionName,
                             long versionCode, String apkSha256, String launchActivity,
                             String nativeAbi, boolean containsNativeCode) {
    public SandboxPackage {
        if (packageName == null || packageName.isBlank()) throw new IllegalArgumentException("packageName is required");
        if (versionCode < 0) throw new IllegalArgumentException("versionCode must be non-negative");
        if (apkSha256 == null || !apkSha256.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("apkSha256 must be a SHA-256 digest");
        }
        label = label == null ? "" : label;
        versionName = versionName == null ? "" : versionName;
        launchActivity = launchActivity == null ? "" : launchActivity;
        nativeAbi = nativeAbi == null ? "" : nativeAbi;
    }
}
