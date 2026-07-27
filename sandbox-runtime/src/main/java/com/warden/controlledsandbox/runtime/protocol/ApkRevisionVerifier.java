package com.warden.controlledsandbox.runtime.protocol;

import com.warden.controlledsandbox.domain.session.PackageRevision;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Verifies immutable APK byte identity before Broker reuse and again before Guest loading. */
public final class ApkRevisionVerifier {
    private ApkRevisionVerifier() { }

    public static PackageRevision verify(File apk, long versionCode, String expectedSha256)
            throws IOException {
        if (apk == null || !apk.isFile()) throw new IllegalArgumentException("APK file is missing");
        PackageRevision expected = PackageRevision.of(versionCode, expectedSha256);
        String actual = sha256(apk);
        if (!MessageDigest.isEqual(hexBytes(expected.apkSha256()), hexBytes(actual))) {
            throw new SecurityException("APK_SHA256_MISMATCH expected=" + expected.apkSha256()
                    + " actual=" + actual);
        }
        return expected;
    }

    public static String sha256(File source) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
        byte[] buffer = new byte[64 * 1024];
        try (FileInputStream input = new FileInputStream(source)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return toHex(digest.digest());
    }

    private static byte[] hexBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return out.toString();
    }
}
