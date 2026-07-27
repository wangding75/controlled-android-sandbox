package com.warden.controlledsandbox.runtime.protocol;

import com.warden.controlledsandbox.domain.session.PackageRevision;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

public final class PackageRevisionSetVerifierSelfTest {
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("revision-set").toFile();
        try {
            File base = new File(root, "base.apk");
            File feature = new File(root, "feature.apk");
            File config = new File(root, "config.apk");
            Files.write(base.toPath(), "base-bytes".getBytes(StandardCharsets.UTF_8));
            Files.write(feature.toPath(), "feature-bytes".getBytes(StandardCharsets.UTF_8));
            Files.write(config.toPath(), "config-bytes".getBytes(StandardCharsets.UTF_8));
            String baseSha = ApkRevisionVerifier.sha256(base);
            PackageRevisionSetVerifier.Artifact featureArtifact = new PackageRevisionSetVerifier.Artifact(
                    "payments", "FEATURE", "", "", feature, ApkRevisionVerifier.sha256(feature));
            PackageRevisionSetVerifier.Artifact configArtifact = new PackageRevisionSetVerifier.Artifact(
                    "config.en", "CONFIG", "payments", "payments", config,
                    ApkRevisionVerifier.sha256(config));
            List<PackageRevisionSetVerifier.Artifact> all = List.of(
                    new PackageRevisionSetVerifier.Artifact("", "BASE", "", "", base, baseSha),
                    featureArtifact, configArtifact);
            String revision = PackageRevisionSetVerifier.revisionDigest(all);
            PackageRevision verified = PackageRevisionSetVerifier.verify(base, baseSha,
                    List.of(featureArtifact, configArtifact), 88L, revision);
            require(verified.canonical().equals("v88:sha256:" + revision), "revision canonical");

            Files.write(feature.toPath(), "tampered".getBytes(StandardCharsets.UTF_8));
            boolean rejected = false;
            try {
                PackageRevisionSetVerifier.verify(base, baseSha,
                        List.of(featureArtifact, configArtifact), 88L, revision);
            } catch (SecurityException expected) {
                rejected = expected.getMessage().startsWith("SPLIT_APK_SHA256_MISMATCH");
            }
            require(rejected, "split tampering rejected");

            boolean dependencyRejected = false;
            try {
                PackageRevisionSetVerifier.revisionDigest(List.of(
                        new PackageRevisionSetVerifier.Artifact("", "BASE", "", "", base, baseSha),
                        new PackageRevisionSetVerifier.Artifact("feature", "FEATURE", "", "missing",
                                feature, featureArtifact.sha256)));
            } catch (IllegalArgumentException expected) {
                dependencyRejected = expected.getMessage().startsWith("Missing split dependency");
            }
            require(dependencyRejected, "missing split dependency rejected");
            System.out.println("PASS immutable multi-APK revision set verifier self-test");
        } finally {
            delete(root);
        }
    }

    private static void delete(File file) throws Exception {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) delete(child);
        }
        Files.deleteIfExists(file.toPath());
    }
    private static void require(boolean value, String label) { if (!value) throw new AssertionError(label); }
}
