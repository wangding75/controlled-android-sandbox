package com.warden.controlledsandbox;

import java.io.File;

/** Canonical app-private location policy for immutable package revisions. */
final class PackageStorageLayout {
    private final File packagesRoot;

    PackageStorageLayout(File filesDir) {
        if (filesDir == null) throw new IllegalArgumentException("filesDir is required");
        packagesRoot = new File(filesDir, "packages");
    }

    File packagesRoot() { return packagesRoot; }

    File packageDirectory(String packageName) {
        return new File(packagesRoot, safeSegment(packageName));
    }

    File revisionsDirectory(String packageName) {
        return new File(packageDirectory(packageName), "revisions");
    }

    File revisionDirectory(String packageName, String sha256) {
        requireDigest(sha256);
        return new File(revisionsDirectory(packageName), sha256.toLowerCase(java.util.Locale.ROOT));
    }

    File apkFile(String packageName, String sha256) {
        return new File(revisionDirectory(packageName, sha256), "base.apk");
    }

    File nativeLibraryDirectory(String packageName, String sha256) {
        return new File(revisionDirectory(packageName, sha256), "lib");
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
        if (java.nio.file.Files.isSymbolicLink(configuredApk.toPath())) {
            throw new SecurityException("PACKAGE_APK_PATH_IS_SYMBOLIC_LINK: " + record.packageName);
        }
        File actualApk = configuredApk.getCanonicalFile();
        if (!expectedApk.equals(actualApk)) {
            throw new SecurityException("PACKAGE_REVISION_PATH_MISMATCH: " + record.packageName);
        }
        if (!actualApk.isFile()) {
            throw new SecurityException("PACKAGE_REVISION_APK_MISSING: " + record.packageName);
        }
        if (!record.nativeLibraryDir.trim().isEmpty()) {
            File configuredExpectedNative = nativeLibraryDirectory(record.packageName, record.sha256);
            requireNoManagedSymlinks(configuredExpectedNative);
            File expectedNative = configuredExpectedNative.getCanonicalFile();
            requireInsidePackagesRoot(expectedNative);
            File configuredNative = new File(record.nativeLibraryDir);
            requireNoManagedSymlinks(configuredNative);
            if (java.nio.file.Files.isSymbolicLink(configuredNative.toPath())) {
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
        if (java.nio.file.Files.isSymbolicLink(current)) {
            throw new SecurityException("PACKAGE_PATH_CONTAINS_SYMBOLIC_LINK: " + current);
        }
        java.nio.file.Path relative = root.relativize(candidate);
        for (java.nio.file.Path part : relative) {
            current = current.resolve(part);
            if (java.nio.file.Files.isSymbolicLink(current)) {
                throw new SecurityException("PACKAGE_PATH_CONTAINS_SYMBOLIC_LINK: " + current);
            }
        }
    }

    private static void requireDigest(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("APK SHA-256 must contain 64 hexadecimal characters");
        }
    }

    static String safeSegment(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("packageName is required");
        }
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
