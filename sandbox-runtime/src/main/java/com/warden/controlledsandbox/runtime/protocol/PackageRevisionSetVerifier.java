package com.warden.controlledsandbox.runtime.protocol;

import android.os.ParcelFileDescriptor;
import com.warden.controlledsandbox.domain.session.PackageRevision;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Verifies every APK artifact and the deterministic multi-artifact revision identity. */
public final class PackageRevisionSetVerifier {
    private PackageRevisionSetVerifier() { }

    public static PackageRevision verify(File baseApk, String baseSha256,
                                         List<Artifact> splits, long versionCode,
                                         String expectedRevisionSha256) throws Exception {
        List<Artifact> all = new ArrayList<>();
        all.add(new Artifact("", "BASE", "", "", baseApk, baseSha256));
        return verifyArtifacts(all, splits, versionCode, expectedRevisionSha256);
    }

    /** Verifies a base APK received as an isolated-process file capability. */
    public static PackageRevision verify(ParcelFileDescriptor baseApk, String baseSha256,
                                         List<Artifact> splits, long versionCode,
                                         String expectedRevisionSha256) throws Exception {
        List<Artifact> all = new ArrayList<>();
        all.add(new Artifact("", "BASE", "", "", baseApk, baseSha256));
        return verifyArtifacts(all, splits, versionCode, expectedRevisionSha256);
    }

    private static PackageRevision verifyArtifacts(List<Artifact> base,
                                                   List<Artifact> splits,
                                                   long versionCode,
                                                   String expectedRevisionSha256) throws Exception {
        List<Artifact> all = new ArrayList<>(base);
        if (splits != null) all.addAll(splits);
        validateArtifactSet(all);
        String actualBase = digest(all.get(0));
        requireDigest(all.get(0).sha256, actualBase, "BASE_APK_SHA256_MISMATCH");
        for (Artifact artifact : all) {
            if (!artifact.hasSource()) {
                throw new IllegalArgumentException("APK artifact is missing: " + artifact.splitName);
            }
            requireDigest(artifact.sha256, digest(artifact),
                    artifact.base() ? "BASE_APK_SHA256_MISMATCH"
                            : "SPLIT_APK_SHA256_MISMATCH:" + artifact.splitName);
        }
        String actualRevision = digestValidated(all);
        requireDigest(expectedRevisionSha256, actualRevision, "PACKAGE_REVISION_SET_MISMATCH");
        return PackageRevision.of(versionCode, expectedRevisionSha256);
    }

    private static String digest(Artifact artifact) throws Exception {
        return artifact.descriptor == null
                ? ApkRevisionVerifier.sha256(artifact.file)
                : ApkRevisionVerifier.sha256(artifact.descriptor);
    }

    public static String revisionDigest(List<Artifact> artifacts) throws Exception {
        validateArtifactSet(artifacts);
        return digestValidated(artifacts);
    }

    private static String digestValidated(List<Artifact> artifacts) throws Exception {
        if (artifacts.size() == 1 && artifacts.get(0).base()) return artifacts.get(0).sha256;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<Artifact> sorted = new ArrayList<>(artifacts);
        sorted.sort(Comparator.comparing((Artifact value) -> !value.base())
                .thenComparing(value -> value.splitName));
        for (Artifact artifact : sorted) {
            String line = artifact.type + "|" + artifact.splitName + "|" + artifact.configForSplit
                    + "|" + artifact.usesSplit + "|"
                    + artifact.sha256.toLowerCase(java.util.Locale.ROOT) + "\n";
            digest.update(line.getBytes(StandardCharsets.UTF_8));
        }
        return toHex(digest.digest());
    }

    private static void validateArtifactSet(List<Artifact> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) {
            throw new IllegalArgumentException("APK artifact set is required");
        }
        if (artifacts.size() > 256) throw new IllegalArgumentException("Too many APK artifacts");
        int baseCount = 0;
        Map<String, Artifact> splits = new HashMap<>();
        for (Artifact artifact : artifacts) {
            if (artifact == null) throw new IllegalArgumentException("APK artifact is required");
            if (artifact.base()) {
                baseCount++;
            } else if (splits.put(artifact.splitName, artifact) != null) {
                throw new IllegalArgumentException("Duplicate split name: " + artifact.splitName);
            }
        }
        if (baseCount != 1) throw new IllegalArgumentException("Exactly one base APK is required");
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String name : splits.keySet()) validateDependencyGraph(name, splits, visiting, visited);
    }

    private static void validateDependencyGraph(String name, Map<String, Artifact> splits,
                                                Set<String> visiting, Set<String> visited) {
        if (visited.contains(name)) return;
        if (!visiting.add(name)) throw new IllegalArgumentException("Split dependency cycle: " + name);
        Artifact artifact = splits.get(name);
        validateDependency(artifact.usesSplit, splits, visiting, visited);
        if ("CONFIG".equals(artifact.type) && !"base".equals(artifact.configForSplit)) {
            validateDependency(artifact.configForSplit, splits, visiting, visited);
        }
        visiting.remove(name);
        visited.add(name);
    }

    private static void validateDependency(String dependency, Map<String, Artifact> splits,
                                           Set<String> visiting, Set<String> visited) {
        if (dependency.isEmpty() || "base".equals(dependency)) return;
        if (!splits.containsKey(dependency)) {
            throw new IllegalArgumentException("Missing split dependency: " + dependency);
        }
        validateDependencyGraph(dependency, splits, visiting, visited);
    }

    private static void requireDigest(String expected, String actual, String code) {
        if (expected == null || !expected.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("Invalid expected SHA-256");
        }
        if (!MessageDigest.isEqual(hex(expected), hex(actual))) {
            throw new SecurityException(code + " expected=" + expected + " actual=" + actual);
        }
    }
    private static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
    private static String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return out.toString();
    }

    public static final class Artifact {
        public final String splitName;
        public final String type;
        public final String configForSplit;
        public final String usesSplit;
        public final File file;
        public final ParcelFileDescriptor descriptor;
        public final String sha256;

        public Artifact(String splitName, String type, String configForSplit, String usesSplit,
                        File file, String sha256) {
            this(splitName, type, configForSplit, usesSplit, file, null, sha256);
        }

        public Artifact(String splitName, String type, String configForSplit, String usesSplit,
                        ParcelFileDescriptor descriptor, String sha256) {
            this(splitName, type, configForSplit, usesSplit, null, descriptor, sha256);
        }

        private Artifact(String splitName, String type, String configForSplit, String usesSplit,
                         File file, ParcelFileDescriptor descriptor, String sha256) {
            this.splitName = splitValue(splitName, "splitName");
            this.type = normalizeType(type);
            this.configForSplit = splitValue(configForSplit, "configForSplit");
            this.usesSplit = splitValue(usesSplit, "usesSplit");
            if (file == null && descriptor == null) {
                throw new NullPointerException("file or descriptor");
            }
            if (file != null && descriptor != null) {
                throw new IllegalArgumentException("file and descriptor are mutually exclusive");
            }
            this.file = file;
            this.descriptor = descriptor;
            this.sha256 = required(sha256, "sha256").toLowerCase(java.util.Locale.ROOT);
            if (!this.sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("sha256 must contain 64 hexadecimal characters");
            }
            if (base()) {
                if (!this.splitName.isEmpty() || !this.configForSplit.isEmpty()
                        || !this.usesSplit.isEmpty()) {
                    throw new IllegalArgumentException("Base artifact cannot declare split metadata");
                }
            } else {
                if (this.splitName.isEmpty()) throw new IllegalArgumentException("Split artifact requires splitName");
                if ("CONFIG".equals(this.type) && this.configForSplit.isEmpty()) {
                    throw new IllegalArgumentException("Configuration split requires configForSplit");
                }
                if (!"CONFIG".equals(this.type) && !this.configForSplit.isEmpty()) {
                    throw new IllegalArgumentException("Only configuration splits may declare configForSplit");
                }
            }
        }

        boolean base() { return "BASE".equals(type); }

        boolean hasSource() {
            return descriptor != null ? descriptor.getFd() >= 0 : file != null && file.isFile();
        }

        private static String normalizeType(String value) {
            String normalized = required(value, "type").toUpperCase(java.util.Locale.ROOT);
            if (!"BASE".equals(normalized) && !"FEATURE".equals(normalized)
                    && !"CONFIG".equals(normalized)) {
                throw new IllegalArgumentException("Unsupported artifact type: " + value);
            }
            return normalized;
        }
        private static String required(String value, String name) {
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalArgumentException(name + " is required");
            }
            return value.trim();
        }
        private static String splitValue(String value, String name) {
            String normalized = value == null ? "" : value.trim();
            if (!normalized.isEmpty() && !"base".equals(normalized)
                    && !normalized.matches("[A-Za-z0-9._-]+")) {
                throw new IllegalArgumentException(name + " contains unsupported characters");
            }
            return normalized;
        }
    }
}
