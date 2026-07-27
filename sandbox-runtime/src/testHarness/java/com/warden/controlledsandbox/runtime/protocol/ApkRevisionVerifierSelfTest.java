package com.warden.controlledsandbox.runtime.protocol;

import com.warden.controlledsandbox.domain.session.PackageRevision;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class ApkRevisionVerifierSelfTest {
    public static void main(String[] args) throws Exception {
        File apk = Files.createTempFile("apk-revision", ".apk").toFile();
        try {
            Files.write(apk.toPath(), "first-revision".getBytes(StandardCharsets.UTF_8));
            String digest = ApkRevisionVerifier.sha256(apk);
            PackageRevision revision = ApkRevisionVerifier.verify(apk, 42L, digest.toUpperCase());
            require(revision.versionCode() == 42L, "version retained");
            require(revision.apkSha256().equals(digest), "digest normalized");
            require(revision.canonical().equals("v42:sha256:" + digest), "canonical revision");

            Files.write(apk.toPath(), "changed-revision".getBytes(StandardCharsets.UTF_8));
            boolean mismatch = false;
            try { ApkRevisionVerifier.verify(apk, 42L, digest); }
            catch (SecurityException expected) {
                mismatch = expected.getMessage().startsWith("APK_SHA256_MISMATCH");
            }
            require(mismatch, "changed APK bytes rejected");
            System.out.println("PASS immutable APK revision verifier self-test");
        } finally {
            if (!apk.delete()) apk.deleteOnExit();
        }
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
