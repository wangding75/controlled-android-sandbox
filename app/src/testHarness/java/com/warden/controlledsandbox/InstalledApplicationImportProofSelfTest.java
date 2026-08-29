package com.warden.controlledsandbox;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import com.warden.controlledsandbox.contract.InstallSessionParamsSnapshot;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.List;

/** Regression checks for the same-installed-revision proof and conservative invalidation. */
public final class InstalledApplicationImportProofSelfTest {
    public static void main(String[] args) throws Exception {
        File source = Files.createTempFile("same-revision-proof", ".apk").toFile();
        Files.write(source.toPath(), new byte[] {1, 2, 3, 4});
        String revision = "a".repeat(64);
        ApplicationInfo application = new ApplicationInfo();
        application.packageName = "com.example.proof";
        application.sourceDir = source.getAbsolutePath();
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = application.packageName;
        packageInfo.versionCode = 7;
        packageInfo.lastUpdateTime = 42L;
        SandboxRecord record = new SandboxRecord(application.packageName, "Proof", "1", 7,
                "signature", source.getAbsolutePath(), "", "", "", application.packageName,
                "", application.packageName, "", application.packageName, "", "", "",
                "", "", revision, 1L, "NOT_TESTED", 0L);

        InstalledApplicationImportProof proof = InstalledApplicationImportProof.capture(
                application.packageName, application, packageInfo,
                InstallSessionParamsSnapshot.NATIVE_GUEST_TRUST_UNTRUSTED, record, List.of(source));
        require(proof.usable(), "captured proof is usable");
        require(proof.matches(application.packageName, application, packageInfo,
                InstallSessionParamsSnapshot.NATIVE_GUEST_TRUST_UNTRUSTED, record, List.of(source)),
                "unchanged source matches");
        require(InstalledApplicationImportProof.fromJson(proof.toJson()).usable(),
                "proof JSON round trip");

        packageInfo.versionCode = 8;
        require(!proof.matches(application.packageName, application, packageInfo,
                InstallSessionParamsSnapshot.NATIVE_GUEST_TRUST_UNTRUSTED, record, List.of(source)),
                "version change invalidates proof");
        packageInfo.versionCode = 7;
        Files.setLastModifiedTime(source.toPath(), FileTime.fromMillis(
                Files.getLastModifiedTime(source.toPath()).toMillis() + 1000L));
        require(!proof.matches(application.packageName, application, packageInfo,
                InstallSessionParamsSnapshot.NATIVE_GUEST_TRUST_UNTRUSTED, record, List.of(source)),
                "source mtime change invalidates proof");
        System.out.println("PASS InstalledApplicationImportProofSelfTest");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private InstalledApplicationImportProofSelfTest() { }
}
