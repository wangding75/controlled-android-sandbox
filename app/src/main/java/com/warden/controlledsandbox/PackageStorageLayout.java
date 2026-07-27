package com.warden.controlledsandbox;

import java.io.File;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

/** Canonical app-private location policy for immutable package revisions. */
final class PackageStorageLayout {
    private final File packagesRoot;

    PackageStorageLayout(File filesDir) {
        if (filesDir == null) throw new IllegalArgumentException("filesDir is required");
        packagesRoot = new File(filesDir, "packages");
    }

    File packagesRoot() { return packagesRoot; }
    File packageDirectory(String packageName) { return new File(packagesRoot, safeSegment(packageName)); }
    File revisionsDirectory(String packageName) { return new File(packageDirectory(packageName), "revisions"); }

    File revisionDirectory(String packageName, String revisionSha256) {
        requireDigest(revisionSha256);
        return new File(revisionsDirectory(packageName), revisionSha256.toLowerCase(java.util.Locale.ROOT));
    }

    File apkFile(String packageName, String revisionSha256) {
        return new File(revisionDirectory(packageName, revisionSha256), "base.apk");
    }

    File splitDirectory(String packageName, String revisionSha256) {
        return new File(revisionDirectory(packageName, revisionSha256), "splits");
    }

    File splitApkFile(String packageName, String revisionSha256, String splitName) {
        if (splitName == null || splitName.trim().isEmpty()) {
            throw new IllegalArgumentException("splitName is required");
        }
        return new File(splitDirectory(packageName, revisionSha256),
                "split_" + PackageArtifactRecord.safe(splitName) + ".apk");
    }

    File nativeLibraryDirectory(String packageName, String revisionSha256) {
        return new File(revisionDirectory(packageName, revisionSha256), "lib");
    }

    void requireCatalogLayout(SandboxCatalogState state) throws Exception {
        for (SandboxRecord record : state.records()) requireRecordLayout(record);
    }

    void requireRecordLayout(SandboxRecord record) throws Exception {
        File configuredExpectedApk = apkFile(record.packageName, record.sha256);
        requireNoManagedSymlinks(configuredExpectedApk);
        File expectedApk = configuredExpectedApk.getCanonicalFile();
        requireInsidePackagesRoot(expectedApk);
        File configuredApk = new File(record.apkPath);
        requireNoManagedSymlinks(configuredApk);
        if (Files.isSymbolicLink(configuredApk.toPath())) {
            throw new SecurityException("PACKAGE_APK_PATH_IS_SYMBOLIC_LINK: " + record.packageName);
        }
        File actualApk = configuredApk.getCanonicalFile();
        if (!expectedApk.equals(actualApk)) {
            throw new SecurityException("PACKAGE_REVISION_PATH_MISMATCH: " + record.packageName);
        }
        if (!actualApk.isFile()) {
            throw new SecurityException("PACKAGE_REVISION_APK_MISSING: " + record.packageName);
        }

        int baseCount = 0;
        Set<String> splitNames = new HashSet<>();
        for (PackageArtifactRecord artifact : record.artifacts) {
            File expected = artifact.base()
                    ? expectedApk
                    : splitApkFile(record.packageName, record.sha256, artifact.splitName).getCanonicalFile();
            requireInsidePackagesRoot(expected);
            File configured = new File(artifact.path);
            requireNoManagedSymlinks(configured);
            if (Files.isSymbolicLink(configured.toPath()) || !configured.isFile()) {
                throw new SecurityException("PACKAGE_ARTIFACT_MISSING: " + artifact.splitName);
            }
            if (!expected.equals(configured.getCanonicalFile())) {
                throw new SecurityException("PACKAGE_ARTIFACT_PATH_MISMATCH: " + artifact.splitName);
            }
            String actualDigest = ApkImportManager.sha256(configured);
            if (!artifact.sha256.equals(actualDigest)) {
                throw new SecurityException("PACKAGE_ARTIFACT_DIGEST_MISMATCH: " + artifact.splitName);
            }
            if (artifact.base()) {
                baseCount++;
                if (!artifact.sha256.equals(record.baseApkSha256)) {
                    throw new SecurityException("PACKAGE_BASE_DIGEST_MISMATCH: " + record.packageName);
                }
            } else if (!splitNames.add(artifact.splitName)) {
                throw new SecurityException("PACKAGE_DUPLICATE_SPLIT: " + artifact.splitName);
            }
        }
        if (baseCount != 1) throw new SecurityException("PACKAGE_BASE_ARTIFACT_COUNT_INVALID");
        String computedRevision = ApkImportManager.revisionDigestRecords(record.artifacts);
        if (!record.sha256.equals(computedRevision)) {
            throw new SecurityException("PACKAGE_REVISION_DIGEST_MISMATCH: " + record.packageName);
        }

        if (!record.nativeLibraryDir.trim().isEmpty()) {
            File configuredExpectedNative = nativeLibraryDirectory(record.packageName, record.sha256);
            requireNoManagedSymlinks(configuredExpectedNative);
            File expectedNative = configuredExpectedNative.getCanonicalFile();
            requireInsidePackagesRoot(expectedNative);
            File configuredNative = new File(record.nativeLibraryDir);
            requireNoManagedSymlinks(configuredNative);
            if (Files.isSymbolicLink(configuredNative.toPath())) {
                throw new SecurityException("PACKAGE_NATIVE_PATH_IS_SYMBOLIC_LINK: " + record.packageName);
            }
            File actualNative = configuredNative.getCanonicalFile();
            if (!expectedNative.equals(actualNative)) {
                throw new SecurityException("PACKAGE_NATIVE_PATH_MISMATCH: " + record.packageName);
            }
            if (!actualNative.isDirectory()) {
                throw new SecurityException("PACKAGE_NATIVE_DIRECTORY_MISSING: " + record.packageName);
            }
        }
    }

    boolean isInsidePackagesRoot(File file) throws Exception {
        java.nio.file.Path root = packagesRoot.getCanonicalFile().toPath();
        java.nio.file.Path candidate = file.getCanonicalFile().toPath();
        return candidate.startsWith(root);
    }

    void requireInsidePackagesRoot(File file) throws Exception {
        if (!isInsidePackagesRoot(file)) {
            throw new SecurityException("PACKAGE_PATH_OUTSIDE_MANAGED_ROOT: " + file);
        }
    }

    void requireNoManagedSymlinks(File file) throws Exception {
        java.nio.file.Path root = packagesRoot.getAbsoluteFile().toPath().normalize();
        java.nio.file.Path candidate = file.getAbsoluteFile().toPath().normalize();
        if (!candidate.startsWith(root)) {
            throw new SecurityException("PACKAGE_PATH_OUTSIDE_MANAGED_ROOT: " + file);
        }
        java.nio.file.Path current = root;
        if (Files.isSymbolicLink(current)) {
            throw new SecurityException("PACKAGE_PATH_CONTAINS_SYMBOLIC_LINK: " + current);
        }
        java.nio.file.Path relative = root.relativize(candidate);
        for (java.nio.file.Path part : relative) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new SecurityException("PACKAGE_PATH_CONTAINS_SYMBOLIC_LINK: " + current);
            }
        }
    }

    private static void requireDigest(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("Revision SHA-256 must contain 64 hexadecimal characters");
        }
    }

    static String safeSegment(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("packageName is required");
        }
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
