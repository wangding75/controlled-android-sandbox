package com.warden.controlledsandbox.domain.session;

import java.util.Locale;

/** Immutable identity of imported APK bytes plus their declared version metadata. */
public final class PackageRevision {
    private static final int SHA256_HEX_LENGTH = 64;

    private final long versionCode;
    private final String apkSha256;
    private final String canonical;

    private PackageRevision(long versionCode, String apkSha256) {
        this.versionCode = versionCode;
        this.apkSha256 = apkSha256;
        this.canonical = "v" + versionCode + ":sha256:" + apkSha256;
    }

    public static PackageRevision of(long versionCode, String apkSha256) {
        if (versionCode < 0) throw new IllegalArgumentException("versionCode must be non-negative");
        if (apkSha256 == null) throw new IllegalArgumentException("apkSha256 is required");
        String normalized = apkSha256.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() != SHA256_HEX_LENGTH || !normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("apkSha256 must be 64 lowercase or uppercase hexadecimal characters");
        }
        return new PackageRevision(versionCode, normalized);
    }

    public long versionCode() { return versionCode; }
    public String apkSha256() { return apkSha256; }
    public String canonical() { return canonical; }

    @Override public String toString() { return canonical; }
}
