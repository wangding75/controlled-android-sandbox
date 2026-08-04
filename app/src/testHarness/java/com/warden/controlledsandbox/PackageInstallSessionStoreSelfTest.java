package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.InstallSessionInfoSnapshot;
import com.warden.controlledsandbox.contract.InstallSessionParamsSnapshot;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
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

            InstallSessionParamsSnapshot params = new InstallSessionParamsSnapshot(
                    InstallSessionParamsSnapshot.MODE_INHERIT_EXISTING,
                    "com.example.target", "com.example.installer", "Target App",
                    4096L, 7, true, InstallSessionParamsSnapshot.USER_ACTION_REQUIRED,
                    InstallSessionParamsSnapshot.NATIVE_GUEST_TRUST_EXPLICITLY_TRUSTED);
            int sessionId = store.create(params);
            InstallSessionInfoSnapshot opened = store.info(sessionId);
            require(InstallSessionInfoSnapshot.STATE_OPEN.equals(opened.state()), "session opens");
            require(opened.params().rollbackEnabled(), "rollback parameter retained");
            require(opened.params().installFlags() == 7, "install flags retained");
            require(InstallSessionParamsSnapshot.NATIVE_GUEST_TRUST_EXPLICITLY_TRUSTED.equals(
                            opened.params().nativeGuestTrust()), "native trust retained");
            require(store.list().size() == 1, "active session listed");

            byte[] base = apkBytes("base");
            byte[] split = apkBytes("split");
            boolean nonZipRejected = false;
            try { store.addArtifact(sessionId, new ByteArrayInputStream("not-an-apk".getBytes())); }
            catch (IllegalArgumentException expected) { nonZipRejected = true; }
            require(nonZipRejected, "non-ZIP artifact rejected");
            require(!new File(root, "install-sessions/" + sessionId
                            + "/artifacts/artifact-000.apk").exists(),
                    "failed artifact does not advance session");

            String baseDigest = store.addArtifact(sessionId, new ByteArrayInputStream(base));
            String splitDigest = store.addArtifact(sessionId, new ByteArrayInputStream(split));
            require(baseDigest.matches("[0-9a-f]{64}"), "base digest");
            require(splitDigest.matches("[0-9a-f]{64}"), "split digest");
            InstallSessionInfoSnapshot staged = store.setProgress(sessionId, 0.75F);
            require(staged.artifactCount() == 2, "artifact count retained");
            require(staged.bytesStaged() == base.length + split.length, "staged bytes retained");
            require(Math.abs(staged.progress() - 0.75F) < 0.001F, "explicit progress retained");

            PackageInstallSessionStore restarted = new PackageInstallSessionStore(root);
            PackageInstallSessionStore.PreparedSession sealed = restarted.seal(sessionId);
            require(sealed.id == sessionId, "session id retained");
            require("com.example.target".equals(sealed.expectedPackageName),
                    "expected package retained");
            require(sealed.params.rollbackEnabled(), "prepared params retained");
            require(InstallSessionParamsSnapshot.NATIVE_GUEST_TRUST_EXPLICITLY_TRUSTED.equals(
                            sealed.params.nativeGuestTrust()), "prepared native trust retained");
            require(sealed.artifacts.size() == 2, "all artifacts sealed");
            InstallSessionInfoSnapshot sealedInfo = restarted.info(sessionId);
            require(InstallSessionInfoSnapshot.STATE_SEALED.equals(sealedInfo.state()),
                    "sealed state retained");
            require(sealedInfo.attemptCount() == 1, "attempt count advanced");
            boolean sealedWriteRejected = false;
            try { restarted.addArtifact(sessionId, new ByteArrayInputStream(base)); }
            catch (IllegalStateException expected) { sealedWriteRejected = true; }
            require(sealedWriteRejected, "sealed session rejects writes");

            restarted.markCommitting(sessionId);
            restarted.markFailed(sessionId, "INSTALL_VALIDATION", "package mismatch");
            InstallSessionInfoSnapshot failed = restarted.info(sessionId);
            require(InstallSessionInfoSnapshot.STATE_FAILED.equals(failed.state()),
                    "failed state retained");
            require("INSTALL_VALIDATION".equals(failed.failureCode()), "failure code retained");
            require("package mismatch".equals(failed.failureMessage()), "failure message retained");
            boolean failedProgressRejected = false;
            try { restarted.setProgress(sessionId, 0.5F); }
            catch (IllegalStateException expected) { failedProgressRejected = true; }
            require(failedProgressRejected, "failed session rejects progress mutation");

            InstallSessionInfoSnapshot retried = restarted.retry(sessionId);
            require(InstallSessionInfoSnapshot.STATE_OPEN.equals(retried.state()), "retry reopens");
            require(retried.failureCode().isEmpty(), "retry clears failure");
            require(retried.attemptCount() == 1, "retry preserves attempt history");
            restarted.addArtifact(sessionId, new ByteArrayInputStream(base));
            require(restarted.info(sessionId).artifactCount() == 3, "retry accepts more artifacts");

            int second = restarted.create(InstallSessionParamsSnapshot.fullInstall(""));
            List<InstallSessionInfoSnapshot> sessions = restarted.list();
            require(sessions.size() == 2 && sessions.get(0).sessionId() < sessions.get(1).sessionId(),
                    "sessions listed deterministically");
            restarted.abandon(second);
            restarted.abandon(sessionId);
            boolean missing = false;
            try { restarted.info(sessionId); }
            catch (IllegalArgumentException expected) { missing = true; }
            require(missing, "abandoned session removed");
            java.util.ArrayList<Integer> quotaSessions = new java.util.ArrayList<>();
            for (int index = 0; index < PackageInstallSessionStore.MAX_ACTIVE_SESSIONS; index++) {
                quotaSessions.add(restarted.create(InstallSessionParamsSnapshot.fullInstall("")));
            }
            boolean quotaRejected = false;
            try { restarted.create(InstallSessionParamsSnapshot.fullInstall("")); }
            catch (IllegalStateException expected) {
                quotaRejected = "INSTALL_SESSION_QUOTA_EXCEEDED".equals(expected.getMessage());
            }
            require(quotaRejected, "install-session total quota enforced");
            for (int id : quotaSessions) restarted.abandon(id);
            System.out.println("PASS persisted staged install session self-test with PackageInstaller-style state");
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
    private static void require(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
}
