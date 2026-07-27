package com.warden.controlledsandbox;

import java.util.List;

public final class PackageArtifactOrderSelfTest {
    private static final String SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    public static void main(String[] args) throws Exception {
        PackageArtifactRecord base = artifact("", PackageArtifactRecord.TYPE_BASE, "", "", "/base.apk");
        PackageArtifactRecord feature = artifact("payments", PackageArtifactRecord.TYPE_FEATURE,
                "", "core", "/payments.apk");
        PackageArtifactRecord core = artifact("core", PackageArtifactRecord.TYPE_FEATURE,
                "", "", "/core.apk");
        PackageArtifactRecord config = artifact("config.en", PackageArtifactRecord.TYPE_CONFIG,
                "payments", "", "/config.apk");
        List<PackageArtifactRecord> ordered = PackageArtifactOrder.runtimeOrder(
                List.of(config, feature, base, core));
        require(ordered.get(0).base(), "base first");
        require("core".equals(ordered.get(1).splitName), "feature dependency first");
        require("payments".equals(ordered.get(2).splitName), "dependent feature second");
        require("config.en".equals(ordered.get(3).splitName), "configuration after target");

        String appDigest = ApkImportManager.revisionDigestRecords(ordered);
        java.util.List<com.warden.controlledsandbox.runtime.protocol.PackageRevisionSetVerifier.Artifact> runtime =
                new java.util.ArrayList<>();
        for (PackageArtifactRecord artifact : ordered) {
            runtime.add(new com.warden.controlledsandbox.runtime.protocol.PackageRevisionSetVerifier.Artifact(
                    artifact.splitName, artifact.type, artifact.configForSplit, artifact.usesSplit,
                    new java.io.File(artifact.path), artifact.sha256));
        }
        String runtimeDigest = com.warden.controlledsandbox.runtime.protocol.PackageRevisionSetVerifier
                .revisionDigest(runtime);
        require(appDigest.equals(runtimeDigest), "import and runtime revision algorithms match");
        require(ApkImportManager.revisionDigestRecords(java.util.List.of(base)).equals(SHA),
                "single APK revision remains backward compatible");

        boolean missingRejected = false;
        try {
            PackageArtifactOrder.runtimeOrder(List.of(base,
                    artifact("feature", PackageArtifactRecord.TYPE_FEATURE, "", "missing", "/f.apk")));
        } catch (IllegalArgumentException expected) { missingRejected = true; }
        require(missingRejected, "missing split dependency rejected");

        boolean cycleRejected = false;
        try {
            PackageArtifactOrder.runtimeOrder(List.of(base,
                    artifact("one", PackageArtifactRecord.TYPE_FEATURE, "", "two", "/one.apk"),
                    artifact("two", PackageArtifactRecord.TYPE_FEATURE, "", "one", "/two.apk")));
        } catch (IllegalArgumentException expected) { cycleRejected = true; }
        require(cycleRejected, "split dependency cycle rejected");

        boolean unsafeNameRejected = false;
        try { artifact("../bad", PackageArtifactRecord.TYPE_FEATURE, "", "", "/bad.apk"); }
        catch (IllegalArgumentException expected) { unsafeNameRejected = true; }
        require(unsafeNameRejected, "unsafe split name rejected");
        System.out.println("PASS dependency-ordered split artifact self-test");
    }

    private static PackageArtifactRecord artifact(String name, String type, String configFor,
                                                   String uses, String path) {
        return new PackageArtifactRecord(name, type, configFor, uses, path, SHA);
    }
    private static void require(boolean value, String label) { if (!value) throw new AssertionError(label); }
}
