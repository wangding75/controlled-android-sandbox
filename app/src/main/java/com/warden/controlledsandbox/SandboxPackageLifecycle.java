package com.warden.controlledsandbox;

import android.content.Context;
import android.net.Uri;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Transaction coordinator for package metadata and immutable APK revisions.
 * Metadata is the authority. File deletion happens only after the atomic catalog switch;
 * failed cleanup is retained as an explicit maintenance warning and retried on the next load.
 */
final class SandboxPackageLifecycle {
    private final Context context;
    private final SandboxCatalogRepository catalogRepository;
    private final ApkImportManager importer;
    private final PackageInstallSessionStore installSessions;
    private String maintenanceWarning = "";

    SandboxPackageLifecycle(Context context) {
        this.context = context.getApplicationContext();
        catalogRepository = new SandboxCatalogRepository(this.context);
        importer = new ApkImportManager(this.context);
        installSessions = new PackageInstallSessionStore(this.context.getFilesDir());
    }

    synchronized SandboxCatalogState load() throws Exception {
        SandboxCatalogState state = catalogRepository.load();
        List<String> cleanupFailures = new ArrayList<>();
        try {
            installSessions.sweepStale(24L * 60 * 60 * 1000);
        } catch (Exception cleanupFailure) {
            cleanupFailures.add("Install-session maintenance failed: " + cleanupFailure.getMessage());
        }
        sweepUnreferencedFiles(state, cleanupFailures);
        maintenanceWarning = formatMaintenanceWarning(cleanupFailures);
        return state;
    }

    synchronized SandboxRecord importApk(Uri uri) throws Exception {
        SandboxCatalogState current = catalogRepository.load();
        return commitImported(current, importer.importApk(uri, current.records()));
    }

    synchronized SandboxRecord importApkFile(File source) throws Exception {
        SandboxCatalogState current = catalogRepository.load();
        return commitImported(current, importer.importApkFile(source, current.records()));
    }

    synchronized int createInstallSession(String expectedPackageName) throws Exception {
        return installSessions.create(expectedPackageName);
    }

    synchronized String addInstallArtifact(int sessionId, Uri source) throws Exception {
        if (source == null) throw new IllegalArgumentException("source URI is required");
        return installSessions.addArtifact(sessionId,
                context.getContentResolver().openInputStream(source));
    }

    synchronized SandboxRecord commitInstallSession(int sessionId) throws Exception {
        PackageInstallSessionStore.PreparedSession prepared = installSessions.seal(sessionId);
        SandboxCatalogState current = catalogRepository.load();
        SandboxRecord committed;
        try {
            SandboxRecord imported = importer.importApkFiles(prepared.artifacts, current.records());
            if (!prepared.expectedPackageName.isEmpty()
                    && !prepared.expectedPackageName.equals(imported.packageName)) {
                deletePublishedRevisionIfUnreferenced(current, imported);
                throw new SecurityException("INSTALL_SESSION_PACKAGE_MISMATCH");
            }
            committed = commitImported(current, imported);
        } catch (Exception error) {
            try { installSessions.reopenAfterFailure(sessionId); }
            catch (Exception reopenFailure) { error.addSuppressed(reopenFailure); }
            throw error;
        }
        try {
            installSessions.complete(sessionId);
        } catch (Exception cleanupFailure) {
            maintenanceWarning = "Committed install session cleanup failed: "
                    + cleanupFailure.getMessage();
        }
        return committed;
    }

    synchronized void abandonInstallSession(int sessionId) throws Exception {
        installSessions.abandon(sessionId);
    }

    synchronized SandboxRecord findRecord(String packageName) throws Exception {
        return catalogRepository.load().findRecord(packageName);
    }

    synchronized SandboxPackagePolicyView packagePolicy(String packageName, int virtualUserId)
            throws Exception {
        SandboxCatalogState state = catalogRepository.load();
        return new SandboxPackagePolicyView(state.findRecord(packageName),
                state.policy(packageName, virtualUserId));
    }

    synchronized SandboxPackagePolicyView setPermissionDecision(String packageName, int virtualUserId,
                                                                 String permission, String decision)
            throws Exception {
        SandboxCatalogState current = catalogRepository.load();
        SandboxCatalogState next = current.withPermissionDecision(
                packageName, virtualUserId, permission, decision);
        catalogRepository.save(next);
        return new SandboxPackagePolicyView(next.findRecord(packageName),
                next.policy(packageName, virtualUserId));
    }

    synchronized SandboxPackagePolicyView setAppOpMode(String packageName, int virtualUserId,
                                                        String opName, String mode) throws Exception {
        SandboxCatalogState current = catalogRepository.load();
        SandboxCatalogState next = current.withAppOpMode(packageName, virtualUserId, opName, mode);
        catalogRepository.save(next);
        return new SandboxPackagePolicyView(next.findRecord(packageName),
                next.policy(packageName, virtualUserId));
    }

    synchronized SandboxPackagePolicyView resetPolicy(String packageName, int virtualUserId)
            throws Exception {
        SandboxCatalogState current = catalogRepository.load();
        SandboxCatalogState next = current.withoutPolicy(packageName, virtualUserId);
        catalogRepository.save(next);
        return new SandboxPackagePolicyView(next.findRecord(packageName),
                next.policy(packageName, virtualUserId));
    }

    synchronized void ensureInstance(String packageName, int virtualUserId) throws Exception {
        SandboxCatalogState current = catalogRepository.load();
        SandboxCatalogState next = current.withEnsuredInstance(
                packageName, virtualUserId, System.currentTimeMillis());
        if (next != current) catalogRepository.save(next);
    }

    private SandboxRecord commitImported(SandboxCatalogState current,
                                         SandboxRecord imported) throws Exception {
        SandboxCatalogState next = current.withImported(imported, System.currentTimeMillis());
        try {
            catalogRepository.save(next);
        } catch (Exception error) {
            try {
                deletePublishedRevisionIfUnreferenced(current, imported);
            } catch (Exception cleanupFailure) {
                error.addSuppressed(cleanupFailure);
            }
            throw error;
        }
        sweepUnreferencedFiles(next);
        return imported;
    }

    synchronized int createClone(String packageName) throws Exception {
        SandboxCatalogState current = catalogRepository.load();
        SandboxCatalogState.CloneResult result = current.withClone(packageName, System.currentTimeMillis());
        catalogRepository.save(result.state);
        return result.virtualUserId;
    }

    synchronized void updateInstanceStatus(String packageName, int virtualUserId,
                                           String status) throws Exception {
        SandboxCatalogState current = catalogRepository.load();
        SandboxCatalogState next = current.withInstanceStatus(
                packageName, virtualUserId, status, System.currentTimeMillis());
        catalogRepository.save(next);
    }

    synchronized SandboxCatalogState deleteInstance(String packageName, int virtualUserId)
            throws Exception {
        SandboxCatalogState current = catalogRepository.load();
        SandboxCatalogState next = current.withoutInstance(packageName, virtualUserId);
        catalogRepository.save(next);
        List<String> cleanupFailures = new ArrayList<>();
        deleteForMaintenance(instanceDirectory(packageName, virtualUserId), cleanupFailures);
        sweepUnreferencedFiles(next, cleanupFailures);
        maintenanceWarning = formatMaintenanceWarning(cleanupFailures);
        return next;
    }

    synchronized String maintenanceWarning() {
        return maintenanceWarning;
    }

    private void deletePublishedRevisionIfUnreferenced(SandboxCatalogState current,
                                                       SandboxRecord imported) throws Exception {
        for (SandboxRecord record : current.records()) {
            if (sameFile(record.apkPath, imported.apkPath)) return;
        }
        File apk = new File(imported.apkPath).getCanonicalFile();
        File revision = apk.getParentFile();
        if (revision == null) throw new IllegalStateException("Imported APK has no revision directory");
        ApkImportManager.deleteTreeOrThrow(revision);
    }

    private void sweepUnreferencedFiles(SandboxCatalogState state) {
        List<String> failures = new ArrayList<>();
        sweepUnreferencedFiles(state, failures);
        maintenanceWarning = formatMaintenanceWarning(failures);
    }

    private void sweepUnreferencedFiles(SandboxCatalogState state, List<String> failures) {
        sweepPackageRevisions(state, failures);
        sweepInstanceDirectories(state, failures);
    }

    private void sweepPackageRevisions(SandboxCatalogState state, List<String> failures) {
        File packagesRoot = new File(context.getFilesDir(), "packages");
        if (java.nio.file.Files.isSymbolicLink(packagesRoot.toPath())) {
            failures.add("Package root is a symbolic link: " + packagesRoot);
            return;
        }
        if (!packagesRoot.isDirectory()) return;
        Set<String> referencedRevisionDirectories = new HashSet<>();
        for (SandboxRecord record : state.records()) {
            try {
                File parent = new File(record.apkPath).getCanonicalFile().getParentFile();
                if (parent != null) referencedRevisionDirectories.add(parent.getCanonicalPath());
            } catch (Exception error) {
                failures.add("Cannot resolve package revision for " + record.packageName + ": " + error.getMessage());
            }
        }
        File[] children = packagesRoot.listFiles();
        if (children == null) {
            failures.add("Cannot list package root " + packagesRoot);
            return;
        }
        for (File child : children) {
            if (java.nio.file.Files.isSymbolicLink(child.toPath())) {
                deleteForMaintenance(child, failures);
                continue;
            }
            if (child.getName().startsWith(".install-")
                    || child.getName().startsWith(".migration-")) {
                deleteForMaintenance(child, failures);
                continue;
            }
            File revisions = new File(child, "revisions");
            if (java.nio.file.Files.isSymbolicLink(revisions.toPath())) {
                deleteForMaintenance(revisions, failures);
            } else if (revisions.isDirectory()) {
                File[] revisionDirs = revisions.listFiles();
                if (revisionDirs == null) {
                    failures.add("Cannot list revision directory " + revisions);
                } else {
                    for (File revision : revisionDirs) {
                        try {
                            if (!referencedRevisionDirectories.contains(revision.getCanonicalPath())) {
                                deleteForMaintenance(revision, failures);
                            }
                        } catch (Exception error) {
                            failures.add("Cannot inspect revision " + revision + ": " + error.getMessage());
                        }
                    }
                }
            }
            File[] packageEntries = child.listFiles();
            if (packageEntries == null) {
                failures.add("Cannot list package directory " + child);
            } else {
                for (File entry : packageEntries) {
                    if (!"revisions".equals(entry.getName())) {
                        deleteForMaintenance(entry, failures);
                    }
                }
            }
            removeEmptyDirectory(revisions, failures);
            removeEmptyDirectory(child, failures);
        }
    }

    private void sweepInstanceDirectories(SandboxCatalogState state, List<String> failures) {
        File instancesRoot = new File(context.getFilesDir(), "instances");
        if (java.nio.file.Files.isSymbolicLink(instancesRoot.toPath())) {
            failures.add("Instance root is a symbolic link: " + instancesRoot);
            return;
        }
        if (!instancesRoot.isDirectory()) return;
        Set<String> referenced = new HashSet<>();
        for (SandboxInstance instance : state.instances()) {
            try {
                referenced.add(instanceDirectory(instance.packageName, instance.virtualUserId)
                        .getCanonicalPath());
            } catch (Exception error) {
                failures.add("Cannot resolve instance directory for " + instance.packageName
                        + " user=" + instance.virtualUserId + ": " + error.getMessage());
            }
        }
        File[] users = instancesRoot.listFiles();
        if (users == null) {
            failures.add("Cannot list instance root " + instancesRoot);
            return;
        }
        for (File user : users) {
            if (java.nio.file.Files.isSymbolicLink(user.toPath())) {
                deleteForMaintenance(user, failures);
                continue;
            }
            File[] packages = user.listFiles();
            if (packages == null) {
                if (user.isDirectory()) failures.add("Cannot list virtual user directory " + user);
                continue;
            }
            for (File packageDirectory : packages) {
                if (java.nio.file.Files.isSymbolicLink(packageDirectory.toPath())) {
                    deleteForMaintenance(packageDirectory, failures);
                    continue;
                }
                try {
                    if (!referenced.contains(packageDirectory.getCanonicalPath())) {
                        deleteForMaintenance(packageDirectory, failures);
                    }
                } catch (Exception error) {
                    failures.add("Cannot inspect instance directory " + packageDirectory
                            + ": " + error.getMessage());
                }
            }
            removeEmptyDirectory(user, failures);
        }
        removeEmptyDirectory(instancesRoot, failures);
    }

    private static void deleteForMaintenance(File file, List<String> failures) {
        try {
            ApkImportManager.deleteTreeOrThrow(file);
        } catch (Exception error) {
            failures.add("Cannot delete " + file + ": " + error.getMessage());
        }
    }

    private static void removeEmptyDirectory(File directory, List<String> failures) {
        if (!directory.isDirectory()) return;
        File[] remaining = directory.listFiles();
        if (remaining == null) {
            failures.add("Cannot list directory " + directory);
            return;
        }
        if (remaining.length == 0 && !directory.delete() && directory.exists()) {
            failures.add("Cannot remove empty directory " + directory);
        }
    }

    private File instanceDirectory(String packageName, int virtualUserId) {
        return new File(context.getFilesDir(), "instances/u" + virtualUserId
                + "/" + safeSegment(packageName));
    }

    private static String formatMaintenanceWarning(List<String> failures) {
        if (failures.isEmpty()) return "";
        int extra = Math.max(0, failures.size() - 1);
        return failures.get(0) + (extra == 0 ? "" : " (and " + extra + " more cleanup failures)");
    }

    private static boolean sameFile(String left, String right) {
        try {
            return new File(left).getCanonicalFile().equals(new File(right).getCanonicalFile());
        } catch (Exception error) {
            return false;
        }
    }

    private static String safeSegment(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
