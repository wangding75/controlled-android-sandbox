package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.InstallSessionParamsSnapshot;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class NativeGuestExecutionPolicySelfTest {
    private static final String SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    public static void main(String[] args) throws Exception {
        NativeGuestExecutionPolicy.requireInstallAllowed(false,
                InstallSessionParamsSnapshot.NATIVE_GUEST_TRUST_UNTRUSTED);

        NativeGuestPolicyException denied = expectDenied(() ->
                NativeGuestExecutionPolicy.requireInstallAllowed(true,
                        InstallSessionParamsSnapshot.NATIVE_GUEST_TRUST_UNTRUSTED));
        require(NativeGuestExecutionPolicy.ERROR_UNTRUSTED_NATIVE_GUEST_DENIED.equals(denied.code()),
                "stable native denial code");

        NativeGuestExecutionPolicy.requireInstallAllowed(true,
                InstallSessionParamsSnapshot.NATIVE_GUEST_TRUST_EXPLICITLY_TRUSTED);
        boolean silentTrustRejected = false;
        try {
            new InstallSessionParamsSnapshot(InstallSessionParamsSnapshot.MODE_FULL,
                    "com.example.guest", "com.example.installer", "Guest", -1L, 0,
                    false, InstallSessionParamsSnapshot.USER_ACTION_NOT_REQUIRED,
                    InstallSessionParamsSnapshot.NATIVE_GUEST_TRUST_EXPLICITLY_TRUSTED);
        } catch (IllegalArgumentException expected) {
            silentTrustRejected = true;
        }
        require(silentTrustRejected, "explicit native trust requires user action");

        File root = Files.createTempDirectory("native-guest-policy").toFile();
        try {
            File javaOnly = new File(root, "java-only.apk");
            writeArchive(javaOnly, "classes.dex", new byte[]{1, 2, 3});
            File nativeApk = new File(root, "native.apk");
            writeArchive(nativeApk, "lib/x86_64/libguest.so", new byte[]{1, 2, 3});
            File disguisedNativeApk = new File(root, "disguised-native.apk");
            writeArchive(disguisedNativeApk, "assets/runtime.bin",
                    new byte[]{0x7f, 'E', 'L', 'F', 1, 2, 3});
            require(!ApkImportManager.containsNativeCode(List.of(
                    PackageArtifactRecord.legacyBase(javaOnly.getAbsolutePath(), SHA))),
                    "Java-only APK is not native");
            require(ApkImportManager.containsNativeCode(List.of(
                    PackageArtifactRecord.legacyBase(nativeApk.getAbsolutePath(), SHA))),
                    "lib/<abi>/*.so is detected");
            require(ApkImportManager.containsNativeCode(List.of(
                    PackageArtifactRecord.legacyBase(disguisedNativeApk.getAbsolutePath(), SHA))),
                    "ELF payload outside lib/ is detected");

            SandboxRecord javaRecord = record(false,
                    InstallSessionParamsSnapshot.NATIVE_GUEST_TRUST_UNTRUSTED, "", "");
            NativeGuestExecutionPolicy.requireRuntimeAllowed(javaRecord);

            SandboxRecord legacyNativeRecord = record(true,
                    InstallSessionParamsSnapshot.NATIVE_GUEST_TRUST_UNTRUSTED,
                    new File(root, "lib").getAbsolutePath(), "x86_64");
            expectDenied(() -> NativeGuestExecutionPolicy.requireRuntimeAllowed(legacyNativeRecord));
            require(!NativeGuestExecutionPolicy.isRuntimeAllowed(legacyNativeRecord),
                    "legacy native record is fail-closed");

            SandboxRecord trustedNativeRecord = record(true,
                    InstallSessionParamsSnapshot.NATIVE_GUEST_TRUST_EXPLICITLY_TRUSTED,
                    new File(root, "trusted-lib").getAbsolutePath(), "x86_64");
            NativeGuestExecutionPolicy.requireRuntimeAllowed(trustedNativeRecord);
            require(NativeGuestExecutionPolicy.MODE_BEST_EFFORT_COMPATIBILITY.equals(
                            trustedNativeRecord.nativeExecutionMode()),
                    "native hook mode is compatibility-only");

            SandboxRecord restored = SandboxRecord.fromJson(trustedNativeRecord.toJson());
            require(restored.containsNativeCode, "native flag persists");
            require(InstallSessionParamsSnapshot.NATIVE_GUEST_TRUST_EXPLICITLY_TRUSTED.equals(
                            restored.nativeGuestTrust),
                    "native trust persists");
        } finally {
            ApkImportManager.deleteTreeOrThrow(root);
        }
        System.out.println("PASS native Guest explicit-trust admission and runtime boundary self-test");
    }

    private static SandboxRecord record(boolean containsNativeCode, String trust,
                                        String nativeDirectory, String abi) {
        return new SandboxRecord("com.example.guest", "Guest", "1", 1L, SHA,
                "/tmp/base.apk", nativeDirectory, abi, containsNativeCode, trust,
                "com.example.guest.MainActivity", "com.example.guest", "",
                "", "com.example.guest", "", "com.example.guest", "",
                "", "com.example.guest", "", "", "", SHA, SHA,
                List.of(PackageArtifactRecord.legacyBase("/tmp/base.apk", SHA)),
                1L, "NOT_TESTED", 0L);
    }

    private static void writeArchive(File file, String entryName, byte[] payload) throws Exception {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(file.toPath()))) {
            output.putNextEntry(new ZipEntry(entryName));
            output.write(payload);
            output.closeEntry();
        }
    }

    private static NativeGuestPolicyException expectDenied(ThrowingRunnable action) throws Exception {
        try {
            action.run();
        } catch (NativeGuestPolicyException expected) {
            return expected;
        }
        throw new AssertionError("native Guest policy should deny");
    }

    private static void require(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }

    private interface ThrowingRunnable { void run() throws Exception; }
}
