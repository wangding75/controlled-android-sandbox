package com.warden.controlledsandbox;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.os.Build;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Cheap, persisted proof for an installed-host APK set.
 *
 * <p>The proof deliberately uses PackageManager's version/signing/update identity plus stable
 * file metadata (size, mtime and a stable file identity).  It is not a replacement for the
 * immutable APK digest: if any part is unavailable or changes, callers must take the ordinary
 * import path and mint a fresh cryptographic revision.  On filesystems that expose a file key
 * this avoids reading the APK bytes on a repeated Add; filesystems without one use creation time
 * as the conservative replacement identity.</p>
 */
final class InstalledApplicationImportProof {
    static final int VERSION = 1;

    final String packageName;
    final long versionCode;
    final long lastUpdateTime;
    final String signatureSha256;
    final String nativeGuestTrust;
    final String revisionSha256;
    final List<Artifact> artifacts;

    private InstalledApplicationImportProof(String packageName, long versionCode,
                                            long lastUpdateTime, String signatureSha256,
                                            String nativeGuestTrust, String revisionSha256,
                                            List<Artifact> artifacts) {
        this.packageName = required(packageName, "packageName");
        this.versionCode = versionCode;
        this.lastUpdateTime = lastUpdateTime;
        this.signatureSha256 = value(signatureSha256);
        this.nativeGuestTrust = NativeGuestExecutionPolicy.normalizeTrust(nativeGuestTrust);
        this.revisionSha256 = requiredDigest(revisionSha256, "revisionSha256");
        this.artifacts = Collections.unmodifiableList(new ArrayList<>(artifacts == null
                ? List.of() : artifacts));
    }

    static InstalledApplicationImportProof capture(String packageName,
                                                    ApplicationInfo application,
                                                    PackageInfo packageInfo,
                                                    String nativeGuestTrust,
                                                    SandboxRecord record,
                                                    List<File> sourceArtifacts) throws Exception {
        if (application == null || packageInfo == null || record == null) {
            throw new IllegalArgumentException("application proof inputs are required");
        }
        List<Artifact> artifacts = new ArrayList<>();
        for (File source : sourceArtifacts == null ? List.<File>of() : sourceArtifacts) {
            artifacts.add(Artifact.capture(source));
        }
        return new InstalledApplicationImportProof(packageName, versionCode(packageInfo),
                packageInfo.lastUpdateTime, signingDigest(packageInfo), nativeGuestTrust,
                record.sha256, artifacts);
    }

    static InstalledApplicationImportProof fromJson(JSONObject value) throws JSONException {
        if (value.optInt("version", -1) != VERSION) {
            throw new IllegalArgumentException("Unsupported installed-application proof version");
        }
        JSONArray array = value.optJSONArray("artifacts");
        List<Artifact> artifacts = new ArrayList<>();
        if (array != null) {
            for (int index = 0; index < array.length(); index++) {
                artifacts.add(Artifact.fromJson(array.getJSONObject(index)));
            }
        }
        return new InstalledApplicationImportProof(value.getString("packageName"),
                value.optLong("versionCode", -1L), value.optLong("lastUpdateTime", -1L),
                value.optString("signatureSha256"), value.optString("nativeGuestTrust"),
                value.getString("revisionSha256"), artifacts);
    }

    boolean usable() {
        if (versionCode < 0 || lastUpdateTime <= 0L || signatureSha256.isEmpty()
                || artifacts.isEmpty()) return false;
        for (Artifact artifact : artifacts) if (!artifact.usable()) return false;
        return true;
    }

    boolean matches(String requestedPackageName, ApplicationInfo application,
                    PackageInfo packageInfo, String requestedTrust,
                    SandboxRecord record, List<File> sourceArtifacts) {
        if (!usable() || application == null || packageInfo == null || record == null
                || !packageName.equals(requestedPackageName)
                || !packageName.equals(packageInfo.packageName == null
                        ? requestedPackageName : packageInfo.packageName)
                || versionCode != versionCode(packageInfo)
                || lastUpdateTime != packageInfo.lastUpdateTime
                || !signatureSha256.equals(signingDigest(packageInfo))
                || !nativeGuestTrust.equals(NativeGuestExecutionPolicy.normalizeTrust(requestedTrust))
                || record.sha256 == null || !revisionSha256.equalsIgnoreCase(record.sha256)
                || sourceArtifacts == null || artifacts.size() != sourceArtifacts.size()) {
            return false;
        }
        for (int index = 0; index < artifacts.size(); index++) {
            if (!artifacts.get(index).matches(sourceArtifacts.get(index))) return false;
        }
        return true;
    }

    JSONObject toJson() throws JSONException {
        JSONArray array = new JSONArray();
        for (Artifact artifact : artifacts) array.put(artifact.toJson());
        return new JSONObject().put("version", VERSION).put("packageName", packageName)
                .put("versionCode", versionCode).put("lastUpdateTime", lastUpdateTime)
                .put("signatureSha256", signatureSha256).put("nativeGuestTrust", nativeGuestTrust)
                .put("revisionSha256", revisionSha256).put("artifacts", array);
    }

    private static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    private static String signingDigest(PackageInfo info) {
        try {
            Signature[] signatures;
            if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
                signatures = info.signingInfo.getApkContentsSigners();
            } else {
                signatures = info.signatures;
            }
            if (signatures == null || signatures.length == 0) return "";
            List<String> digests = new ArrayList<>();
            for (Signature signature : signatures) {
                if (signature == null) return "";
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                digests.add(hex(digest.digest(signature.toByteArray())));
            }
            Collections.sort(digests);
            return String.join(",", digests);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String hex(byte[] bytes) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            result[index * 2] = alphabet[value >>> 4];
            result[index * 2 + 1] = alphabet[value & 0x0f];
        }
        return new String(result);
    }

    private static String required(String value, String name) {
        String normalized = value(value);
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " is required");
        return normalized;
    }

    private static String requiredDigest(String value, String name) {
        String normalized = required(value, name).toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be 64 hex characters");
        }
        return normalized;
    }

    private static String value(String value) { return value == null ? "" : value.trim(); }

    static final class Artifact {
        final String path;
        final long size;
        final long lastModifiedTime;
        final String fileKey;

        private Artifact(String path, long size, long lastModifiedTime, String fileKey) {
            this.path = required(path, "path");
            this.size = size;
            this.lastModifiedTime = lastModifiedTime;
            this.fileKey = value(fileKey);
        }

        static Artifact capture(File file) throws Exception {
            if (file == null) throw new IllegalArgumentException("source artifact is required");
            java.nio.file.Path path = file.toPath().toRealPath();
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            if (!attributes.isRegularFile()) throw new IllegalArgumentException(
                    "source artifact is not a regular file: " + file);
            Object key = attributes.fileKey();
            String identity = key == null ? creationIdentity(attributes) : String.valueOf(key);
            return new Artifact(path.toString(), attributes.size(),
                    attributes.lastModifiedTime().toMillis(), identity);
        }

        private static String creationIdentity(BasicFileAttributes attributes) {
            long creationTime = attributes.creationTime().toMillis();
            return creationTime <= 0L ? "" : "creation:" + creationTime;
        }

        static Artifact fromJson(JSONObject value) throws JSONException {
            return new Artifact(value.getString("path"), value.optLong("size", -1L),
                    value.optLong("lastModifiedTime", -1L), value.optString("fileKey"));
        }

        boolean usable() {
            return size > 0L && lastModifiedTime >= 0L && !fileKey.isEmpty();
        }

        boolean matches(File file) {
            try {
                Artifact current = capture(file);
                return path.equals(current.path) && size == current.size
                        && lastModifiedTime == current.lastModifiedTime
                        && fileKey.equals(current.fileKey);
            } catch (Exception ignored) {
                return false;
            }
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("path", path).put("size", size)
                    .put("lastModifiedTime", lastModifiedTime).put("fileKey", fileKey);
        }
    }
}
