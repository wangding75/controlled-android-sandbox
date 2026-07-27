package com.warden.controlledsandbox.domain.packageinfo;

/** Conservative same-package upgrade policy: no signer change and no version-code downgrade. */
public final class PackageUpgradePolicy {
    public void validate(long installedVersionCode, String installedSignerDigest,
                         long candidateVersionCode, String candidateSignerDigest) {
        if (candidateVersionCode < 0) throw new IllegalArgumentException("candidateVersionCode must be non-negative");
        if (candidateSignerDigest == null || candidateSignerDigest.trim().isEmpty()) {
            throw new SecurityException("APK signing certificate is missing");
        }
        if (installedSignerDigest != null && !installedSignerDigest.trim().isEmpty()
                && !installedSignerDigest.equals(candidateSignerDigest)) {
            throw new SecurityException("Package signing certificate changed");
        }
        if (installedVersionCode > 0 && candidateVersionCode < installedVersionCode) {
            throw new SecurityException("Package downgrade rejected: " + candidateVersionCode + " < " + installedVersionCode);
        }
    }
}
