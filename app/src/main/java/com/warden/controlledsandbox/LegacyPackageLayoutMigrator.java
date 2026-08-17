package com.warden.controlledsandbox;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.List;

/** One-time copy-forward migration from the pre-M4 mutable package directory layout. */
final class LegacyPackageLayoutMigrator {
    private final PackageStorageLayout layout;

    LegacyPackageLayoutMigrator(PackageStorageLayout layout) {
        this.layout = layout;
    }

    SandboxCatalogState migrate(SandboxCatalogState legacy) throws Exception {
        List<SandboxRecord> migrated = new ArrayList<>();
        for (SandboxRecord record : legacy.records()) migrated.add(migrateRecord(record));
        return new SandboxCatalogState(migrated, legacy.instances());
    }

    private SandboxRecord migrateRecord(SandboxRecord record) throws Exception {
        File expectedApk = layout.apkFile(record.packageName, record.sha256).getCanonicalFile();
        File expectedNative = layout.nativeLibraryDirectory(record.packageName, record.sha256)
                .getCanonicalFile();
        layout.requireInsidePackagesRoot(expectedApk);
        layout.requireInsidePackagesRoot(expectedNative);
        layout.requireNoManagedSymlinks(expectedApk);
        layout.requireNoManagedSymlinks(expectedNative);
        File configuredSourceApk = new File(record.apkPath);
        if (Files.isSymbolicLink(configuredSourceApk.toPath())) {
            throw new SecurityException("LEGACY_PACKAGE_PATH_IS_SYMBOLIC_LINK: " + record.packageName);
        }
        layout.requireNoManagedSymlinks(configuredSourceApk);
        File sourceApk = configuredSourceApk.getCanonicalFile();
        if (expectedApk.equals(sourceApk)) {
            requirePublishedRevision(record, expectedApk, expectedNative);
            return record.withStoragePaths(expectedApk.getAbsolutePath(),
                    record.nativeLibraryDir.trim().isEmpty() ? "" : expectedNative.getAbsolutePath());
        }
        if (!layout.isInsidePackagesRoot(sourceApk) || !sourceApk.isFile()) {
            throw new SecurityException("LEGACY_PACKAGE_PATH_INVALID: " + record.packageName);
        }
        if (!record.baseApkSha256.equalsIgnoreCase(ApkImportManager.sha256(sourceApk))) {
            throw new SecurityException("LEGACY_PACKAGE_DIGEST_MISMATCH: " + record.packageName);
        }

        File sourceNative = null;
        if (!record.nativeLibraryDir.trim().isEmpty()) {
            File configuredSourceNative = new File(record.nativeLibraryDir);
            if (Files.isSymbolicLink(configuredSourceNative.toPath())) {
                throw new SecurityException("LEGACY_NATIVE_PATH_IS_SYMBOLIC_LINK: " + record.packageName);
            }
            layout.requireNoManagedSymlinks(configuredSourceNative);
            sourceNative = configuredSourceNative.getCanonicalFile();
            File sourceRevision = sourceApk.getParentFile();
            if (sourceRevision == null || !sourceNative.getParentFile().equals(sourceRevision)
                    || !sourceNative.isDirectory()) {
                throw new SecurityException("LEGACY_NATIVE_PATH_INVALID: " + record.packageName);
            }
        }

        File revision = expectedApk.getParentFile();
        if (revision == null) throw new IllegalStateException("Revision directory is unavailable");
        if (revision.exists()) {
            requirePublishedRevision(record, expectedApk, expectedNative);
        } else {
            File packagesRoot = layout.packagesRoot();
            if (!packagesRoot.isDirectory() && !packagesRoot.mkdirs() && !packagesRoot.isDirectory()) {
                throw new IllegalStateException("Cannot create package root");
            }
            File transaction = new File(packagesRoot, ".migration-" + System.nanoTime());
            if (!transaction.mkdirs()) throw new IllegalStateException("Cannot create migration transaction");
            try {
                File stagedApk = new File(transaction, "base.apk");
                copyFile(sourceApk, stagedApk);
                if (sourceNative != null) copyDirectory(sourceNative, new File(transaction, "lib"));
                File parent = revision.getParentFile();
                if (parent == null || (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory())) {
                    throw new IllegalStateException("Cannot create revision root");
                }
                ApkImportManager.publishDirectory(transaction, revision);
            } catch (Exception error) {
                try {
                    ApkImportManager.deleteTreeOrThrow(transaction);
                } catch (Exception cleanupFailure) {
                    error.addSuppressed(cleanupFailure);
                }
                throw error;
            }
            requirePublishedRevision(record, expectedApk, expectedNative);
        }

        return record.withStoragePaths(expectedApk.getAbsolutePath(),
                sourceNative == null ? "" : expectedNative.getAbsolutePath());
    }

    private static void requirePublishedRevision(SandboxRecord record, File apk,
                                                 File nativeDirectory) throws Exception {
        if (!apk.isFile() || !record.baseApkSha256.equalsIgnoreCase(ApkImportManager.sha256(apk))) {
            throw new SecurityException("MIGRATED_REVISION_DIGEST_MISMATCH: " + record.packageName);
        }
        if (!record.nativeLibraryDir.trim().isEmpty() && !nativeDirectory.isDirectory()) {
            throw new SecurityException("MIGRATED_NATIVE_DIRECTORY_MISSING: " + record.packageName);
        }
        apk.setReadable(true, true);
        apk.setWritable(false, false);
        apk.setExecutable(false, false);
    }

    private static void copyDirectory(File source, File destination) throws Exception {
        java.nio.file.Path path = source.toPath();
        if (Files.isSymbolicLink(path)) {
            throw new SecurityException("Symbolic link is not allowed in legacy native directory: " + source);
        }
        if (!destination.isDirectory() && !destination.mkdirs() && !destination.isDirectory()) {
            throw new IllegalStateException("Cannot create migrated native directory " + destination);
        }
        File[] children = source.listFiles();
        if (children == null) throw new IllegalStateException("Cannot list legacy native directory " + source);
        for (File child : children) {
            if (Files.isSymbolicLink(child.toPath())) {
                throw new SecurityException("Symbolic link is not allowed in legacy native directory: " + child);
            }
            File target = new File(destination, child.getName());
            if (Files.isDirectory(child.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                copyDirectory(child, target);
            } else if (Files.isRegularFile(child.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                copyFile(child, target);
                target.setReadable(true, true);
                target.setWritable(false, false);
                if (target.getName().endsWith(".so")) target.setExecutable(true, true);
            } else {
                throw new SecurityException("Unsupported legacy native entry: " + child);
            }
        }
    }

    private static void copyFile(File source, File destination) throws Exception {
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(source));
             FileOutputStream file = new FileOutputStream(destination);
             BufferedOutputStream output = new BufferedOutputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.flush();
            file.getFD().sync();
        }
    }
}
