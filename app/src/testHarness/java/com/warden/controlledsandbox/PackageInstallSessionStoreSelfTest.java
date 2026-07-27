package com.warden.controlledsandbox;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class PackageInstallSessionStoreSelfTest {
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("install-session-store").toFile();
        try {
            PackageInstallSessionStore store = new PackageInstallSessionStore(root);
            boolean invalidPackageRejected = false;
            try { store.create("invalid package"); }
            catch (IllegalArgumentException expected) { invalidPackageRejected = true; }
            require(invalidPackageRejected, "invalid expected package rejected");

            int sessionId = store.create("com.example.target");
            byte[] base = apkBytes("base");
            byte[] split = apkBytes("split");
            boolean nonZipRejected = false;
            try { store.addArtifact(sessionId, new ByteArrayInputStream("not-an-apk".getBytes())); }
            catch (IllegalArgumentException expected) { nonZipRejected = true; }
            require(nonZipRejected, "non-ZIP artifact rejected");
            require(!new File(root, "install-sessions/" + sessionId + "/artifacts/artifact-000.apk").exists(),
                    "failed artifact does not advance session");

            String baseDigest = store.addArtifact(sessionId, new ByteArrayInputStream(base));
            String splitDigest = store.addArtifact(sessionId, new ByteArrayInputStream(split));
            require(baseDigest.matches("[0-9a-f]{64}"), "base digest");
            require(splitDigest.matches("[0-9a-f]{64}"), "split digest");

            PackageInstallSessionStore restarted = new PackageInstallSessionStore(root);
            PackageInstallSessionStore.PreparedSession sealed = restarted.seal(sessionId);
            require(sealed.id == sessionId, "session id retained");
            require("com.example.target".equals(sealed.expectedPackageName), "expected package retained");
            require(sealed.artifacts.size() == 2, "all artifacts sealed");
            boolean sealedWriteRejected = false;
            try { restarted.addArtifact(sessionId, new ByteArrayInputStream(base)); }
            catch (IllegalStateException expected) { sealedWriteRejected = true; }
            require(sealedWriteRejected, "sealed session rejects writes");
            restarted.reopenAfterFailure(sessionId);
            restarted.addArtifact(sessionId, new ByteArrayInputStream(base));
            restarted.abandon(sessionId);
            boolean missing = false;
            try { restarted.seal(sessionId); }
            catch (IllegalArgumentException expected) { missing = true; }
            require(missing, "abandoned session removed");
            System.out.println("PASS persisted staged install session self-test");
        } finally {
            ApkImportManager.deleteTreeOrThrow(root);
        }
    }

    private static byte[] apkBytes(String payload) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            output.putNextEntry(new ZipEntry("payload.txt"));
            output.write(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return bytes.toByteArray();
    }
    private static void require(boolean value, String label) { if (!value) throw new AssertionError(label); }
}
