package com.warden.controlledsandbox;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import com.warden.controlledsandbox.contract.InstallSessionInfoSnapshot;
import com.warden.controlledsandbox.contract.InstallSessionParamsSnapshot;
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
    /**
     * Retired install-session flag token. Rollback is {@link PackageLifecycleTransaction},
     * not an install-session boolean.
     */
    static final String INSTALL_SESSION_ROLLBACK_UNSUPPORTED =
            "INSTALL_SESSION_ROLLBACK_UNSUPPORTED";

    @FunctionalInterface
    interface RevisionCommitBarrier {
        void beforeCommit(SandboxCatalogState current, SandboxRecord imported) throws Exception;
    }

    private final Context context;
    private final SandboxCatalogRepository catalogRepository;
    private final ApkImportManager importer;
    private final PackageInstallSessionStore installSessions;
    private final PackageLifecycleTransactionStore lifecycleTransactions;
    private String maintenanceWarning = "";

    SandboxPackageLifecycle(Context context) {
        this.context = context.getApplicationContext();
        catalogRepository = new SandboxCatalogRepository(this.context);
        importer = new ApkImportManager(this.context);
        installSessions = new PackageInstallSessionStore(this.context.getFilesDir());
        lifecycleTransactions = new PackageLifecycleTransactionStore(this.context.getFilesDir());
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

    synchronized SandboxRecord importApk(Uri uri, RevisionCommitBarrier barrier) throws Exception {
        SandboxCatalogState current = catalogRepository.load();
        return commitImported(current, importer.importApk(uri, current.records()), barrier);
    }

    synchronized SandboxRecord importApk(Uri uri, String nativeGuestTrust) throws Exception {
        SandboxCatalogState current = catalogRepository.load();
        return commitImported(current,
                importer.importApk(uri, current.records(), nativeGuestTrust));
    }

    synchronized SandboxRecord importApk(Uri uri, String nativeGuestTrust,
                                         RevisionCommitBarrier barrier) throws Exception {
        SandboxCatalogState current = catalogRepository.load();
        return commitImported(current,
                importer.importApk(uri, current.records(), nativeGuestTrust), barrier);
    }

    synchronized SandboxRecord importApkFile(File source) throws Exception {
        SandboxCatalogState current = catalogRepository.load();
        return commitImported(current, importer.importApkFile(source, current.records()));
    }

    synchronized SandboxRecord importApkFile(File source, RevisionCommitBarrier barrier)
            throws Exception {
        SandboxCatalogState current = catalogRepository.load();
        return commitImported(current, importer.importApkFile(source, current.records()), barrier);
    }

    synchronized SandboxRecord importApkFile(File source, String nativeGuestTrust) throws Exception {
        SandboxCatalogState current = catalogRepository.load();
        return commitImported(current,
                importer.importApkFile(source, current.records(), nativeGuestTrust));
    }

    synchronized SandboxRecord importApkFile(File source, String nativeGuestTrust,
                                             RevisionCommitBarrier barrier) throws Exception {
        SandboxCatalogState current = catalogRepository.load();
        return commitImported(current,
                importer.importApkFile(source, current.records(), nativeGuestTrust), barrier);
    }

    /** Resolve a physical host-installed application and reuse the normal artifact pipeline. */
    synchronized SandboxRecord importInstalledApplication(String packageName,
                                                           String nativeGuestTrust) throws Exception {
        String normalized = packageName == null ? "" : packageName.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("packageName is required");
        ApplicationInfo application = context.getPackageManager().getApplicationInfo(
                normalized, PackageManager.GET_META_DATA);
        List<File> artifacts = new ArrayList<>();
        artifacts.add(requireInstalledArtifact(application.sourceDir, "sourceDir"));
        if (application.splitSourceDirs != null) {
            for (String split : application.splitSourceDirs) {
                artifacts.add(requireInstalledArtifact(split, "splitSourceDirs"));
            }
        }
        SandboxCatalogState current = catalogRepository.load();
        return commitImported(current,
                importer.importApkFiles(artifacts, current.records(), nativeGuestTrust));
    }

    synchronized SandboxRecord importInstalledApplication(String packageName,
                                                           String nativeGuestTrust,
                                                           RevisionCommitBarrier barrier)
            throws Exception {
        String normalized = packageName == null ? "" : packageName.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("packageName is required");
        ApplicationInfo application = context.getPackageManager().getApplicationInfo(
                normalized, PackageManager.GET_META_DATA);
        List<File> artifacts = new ArrayList<>();
        artifacts.add(requireInstalledArtifact(application.sourceDir, "sourceDir"));
        if (application.splitSourceDirs != null) {
            for (String split : application.splitSourceDirs) {
                artifacts.add(requireInstalledArtifact(split, "splitSourceDirs"));
            }
        }
        SandboxCatalogState current = catalogRepository.load();
        return commitImported(current,
                importer.importApkFiles(artifacts, current.records(), nativeGuestTrust), barrier);
    }

    synchronized int createInstallSession(String expectedPackageName) throws Exception {
        return installSessions.create(expectedPackageName);
    }

    synchronized InstallSessionInfoSnapshot createInstallSession(
            InstallSessionParamsSnapshot params) throws Exception {
        int sessionId = installSessions.create(params);
        return installSessions.info(sessionId);
    }

    synchronized InstallSessionInfoSnapshot installSessionInfo(int sessionId) throws Exception {
        return installSessions.info(sessionId);
    }

    synchronized List<InstallSessionInfoSnapshot> installSessions() throws Exception {
        return installSessions.list();
    }

    synchronized InstallSessionInfoSnapshot setInstallSessionProgress(
            int sessionId, float progress) throws Exception {
        return installSessions.setProgress(sessionId, progress);
    }

    synchronized InstallSessionInfoSnapshot retryInstallSession(int sessionId) throws Exception {
        return installSessions.retry(sessionId);
    }

    synchronized String addInstallArtifact(int sessionId, Uri source) throws Exception {
        if (source == null) throw new IllegalArgumentException("source URI is required");
        return installSessions.addArtifact(sessionId,
                context.getContentResolver().openInputStream(source));
    }

    synchronized SandboxRecord commitInstallSession(int sessionId) throws Exception {
        return commitInstallSession(sessionId, null);
    }

    synchronized SandboxRecord commitInstallSession(int sessionId, RevisionCommitBarrier barrier)
            throws Exception {
        PackageInstallSessionStore.PreparedSession prepared = installSessions.seal(sessionId);
        SandboxRecord committed;
        try {
            requireSupportedCommitParams(prepared.params);
            installSessions.markCommitting(sessionId);
            SandboxCatalogState current = catalogRepository.load();
            SandboxRecord imported = importer.importApkFiles(prepared.artifacts, current.records(),
                    prepared.params.nativeGuestTrust());
            if (!prepared.expectedPackageName.isEmpty()
                    && !prepared.expectedPackageName.equals(imported.packageName)) {
                deletePublishedRevisionIfUnreferenced(current, imported);
                throw new SecurityException("INSTALL_SESSION_PACKAGE_MISMATCH");
            }
            committed = commitImported(current, imported, barrier);
        } catch (Exception error) {
            try {
                installSessions.markFailed(sessionId, failureCode(error), error.getMessage());
            } catch (Exception stateFailure) {
                error.addSuppressed(stateFailure);
            }
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

    private static void requireSupportedCommitParams(InstallSessionParamsSnapshot params) {
        if (!InstallSessionParamsSnapshot.MODE_FULL.equals(params.mode())) {
            throw new IllegalArgumentException("INSTALL_SESSION_INHERIT_EXISTING_UNSUPPORTED");
        }
        if (params.installFlags() != 0) {
            throw new IllegalArgumentException("INSTALL_SESSION_FLAGS_UNSUPPORTED");
        }
    }

    private static String failureCode(Exception error) {
        if (error instanceof NativeGuestPolicyException policyError) return policyError.code();
        if (error instanceof SecurityException) return "INSTALL_SESSION_SECURITY_FAILURE";
        if (error instanceof IllegalArgumentException) return "INSTALL_SESSION_VALIDATION_FAILURE";
        if (error instanceof IllegalStateException) return "INSTALL_SESSION_STATE_FAILURE";
        return "INSTALL_SESSION_COMMIT_FAILURE";
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
                state.policy(packageName, virtualUserId), state);
    }

    synchronized SandboxPackagePolicyView setPermissionDecision(String packageName, int virtualUserId,
                                                                 String permission, String decision)
            throws Exception {
        SandboxCatalogState current = catalogRepository.load();
        SandboxCatalogState next = current.withPermissionDecision(
                packageName, virtualUserId, permission, decision);
        catalogRepository.save(next);
        return new SandboxPackagePolicyView(next.findRecord(packageName),
                next.policy(packageName, virtualUserId), next);
    }

    synchronized SandboxPackagePolicyView setAppOpMode(String packageName, int virtualUserId,
                                                        String opName, String mode) throws Exception {
        SandboxCatalogState current = catalogRepository.load();
        SandboxCatalogState next = current.withAppOpMode(packageName, virtualUserId, opName, mode);
        catalogRepository.save(next);
        return new SandboxPackagePolicyView(next.findRecord(packageName),
                next.policy(packageName, virtualUserId), next);
    }

    synchronized SandboxPackagePolicyView setPackageState(String packageName, int virtualUserId,
                                                            String state) throws Exception {
        SandboxCatalogState current = catalogRepository.load();
        SandboxCatalogState next = current.withPackageState(packageName, virtualUserId, state);
        catalogRepository.save(next);
        return new SandboxPackagePolicyView(next.findRecord(packageName),
                next.policy(packageName, virtualUserId), next);
    }

    synchronized SandboxPackagePolicyView setComponentState(String packageName, int virtualUserId,
                                                              String className, String state) throws Exception {
        SandboxCatalogState current = catalogRepository.load();
        SandboxCatalogState next = current.withComponentState(packageName, virtualUserId, className, state);
        catalogRepository.save(next);
        return new SandboxPackagePolicyView(next.findRecord(packageName),
                next.policy(packageName, virtualUserId), next);
    }

    synchronized SandboxPackagePolicyView resetPolicy(String packageName, int virtualUserId)
            throws Exception {
        SandboxCatalogState current = catalogRepository.load();
        SandboxCatalogState next = current.withPolicyReset(packageName, virtualUserId,
                "management policy reset", "HOST_MAIN", System.currentTimeMillis());
        catalogRepository.save(next);
        return new SandboxPackagePolicyView(next.findRecord(packageName),
                next.policy(packageName, virtualUserId), next);
    }

    synchronized SandboxCatalogState.PermissionRequestResult requestRuntimePermission(
            String packageName, int virtualUserId, String permission, String appOpName,
            boolean hostGranted, int requestCode, String sessionId, long generation,
            String actor) throws Exception {
        SandboxCatalogState current = catalogRepository.load();
        SandboxCatalogState.PermissionRequestResult result = current.withPermissionRequest(
                packageName, virtualUserId, permission, appOpName, hostGranted, requestCode,
                sessionId, generation, System.currentTimeMillis(), actor);
        if (result.state != current) catalogRepository.save(result.state);
        return result;
    }

    synchronized SandboxCatalogState.PermissionRequestResult resolveRuntimePermission(
            long requestId, String outcome, boolean hostGranted, String reason, String actor)
            throws Exception {
        SandboxCatalogState current = catalogRepository.load();
        SandboxCatalogState.PermissionRequestResult result = current.withPermissionResolution(
                requestId, outcome, hostGranted, reason, actor, System.currentTimeMillis());
        catalogRepository.save(result.state);
        return result;
    }

    synchronized SandboxPackagePolicyView revokeRuntimePermission(
            String packageName, int virtualUserId, String permission, String appOpName,
            String reason, String actor) throws Exception {
        SandboxCatalogState current = catalogRepository.load();
        SandboxCatalogState next = current.withPermissionRevocation(packageName, virtualUserId,
                permission, appOpName, reason, actor, System.currentTimeMillis());
        catalogRepository.save(next);
        return new SandboxPackagePolicyView(next.findRecord(packageName),
                next.policy(packageName, virtualUserId), next);
    }

    synchronized RuntimePermissionRequestRecord permissionRequest(long requestId) throws Exception {
        return catalogRepository.load().permissionRequest(requestId);
    }

    synchronized RuntimePermissionRequestRecord pendingPermissionRequest(
            String packageName, int virtualUserId, String permission, int requestCode,
            String sessionId, long generation) throws Exception {
        return catalogRepository.load().pendingPermissionRequest(packageName, virtualUserId,
                permission, requestCode, sessionId, generation);
    }

    synchronized List<RuntimePermissionRequestRecord> pendingPermissionRequests(
            String packageName, int virtualUserId) throws Exception {
        return catalogRepository.load().pendingPermissionRequests(packageName, virtualUserId);
    }

    synchronized List<PermissionAuditRecord> permissionAudit(
            String packageName, int virtualUserId, int limit) throws Exception {
        return catalogRepository.load().permissionAudit(packageName, virtualUserId, limit);
    }

    synchronized void ensureInstance(String packageName, int virtualUserId) throws Exception {
        SandboxCatalogState current = catalogRepository.load();
        SandboxCatalogState next = current.withEnsuredInstance(
                packageName, virtualUserId, System.currentTimeMillis());
        if (next != current) catalogRepository.save(next);
    }

    private SandboxRecord commitImported(SandboxCatalogState current,
                                         SandboxRecord imported) throws Exception {
        return commitImported(current, imported, null);
    }

    private SandboxRecord commitImported(SandboxCatalogState current,
                                         SandboxRecord imported,
                                         RevisionCommitBarrier barrier) throws Exception {
        try {
            VirtualPackageStateBuilder.requireInstallableSharedLibraries(imported, current);
        } catch (Exception error) {
            try {
                deletePublishedRevisionIfUnreferenced(current, imported);
            } catch (Exception cleanupFailure) {
                error.addSuppressed(cleanupFailure);
            }
            throw error;
        }
        SandboxRecord previous = current.findRecord(imported.packageName);
        PackageLifecycleTransaction transaction = lifecycleTransactions.get(imported.packageName);
        long now = System.currentTimeMillis();
        if (transaction != null) transaction.requireNotInFlight("commit");
        if (previous != null && !previous.sha256.equals(imported.sha256)) {
            transaction = (transaction == null
                    ? PackageLifecycleTransaction.installed(previous, now)
                    : transaction).prepareUpdate(previous, now);
            lifecycleTransactions.put(transaction);
        }
        if (barrier != null) {
            try {
                barrier.beforeCommit(current, imported);
            } catch (Exception error) {
                abortPreparedUpdate(imported.packageName, current, imported, error);
                throw error;
            }
        }
        if (transaction != null && transaction.state == PackageLifecycleTransaction.State.UPDATING_PREPARE) {
            transaction = transaction.switchUpdate(imported, now);
            lifecycleTransactions.put(transaction);
        }
        SandboxCatalogState next = current.withImported(imported, now);
        try {
            catalogRepository.save(next);
        } catch (Exception error) {
            abortPreparedUpdate(imported.packageName, current, imported, error);
            throw error;
        }
        if (transaction != null && transaction.state == PackageLifecycleTransaction.State.UPDATING_SWITCH) {
            lifecycleTransactions.put(transaction.activate(now, "update-active"));
        } else if (previous == null) {
            lifecycleTransactions.put(PackageLifecycleTransaction.installed(imported, now));
        }
        sweepUnreferencedFiles(next);
        return imported;
    }

    synchronized PackageLifecycleTransaction rollbackPackage(String packageName) throws Exception {
        String normalized = requirePackageName(packageName);
        PackageLifecycleTransaction transaction = lifecycleTransactions.get(normalized);
        if (transaction == null) throw new IllegalStateException("LIFECYCLE_NO_ROLLBACK_REVISION");
        transaction = transaction.beginRollback(System.currentTimeMillis());
        lifecycleTransactions.put(transaction);
        SandboxRecord previous = SandboxRecord.fromJson(new org.json.JSONObject(transaction.previousRecordJson));
        File previousApk = new File(previous.apkPath);
        if (!previousApk.isFile()) {
            lifecycleTransactions.put(transaction.activate(System.currentTimeMillis(), "rollback-missing-apk"));
            throw new IllegalStateException("LIFECYCLE_ROLLBACK_APK_MISSING");
        }
        SandboxCatalogState current = catalogRepository.load();
        catalogRepository.save(current.withRestoredRevision(previous));
        PackageLifecycleTransaction restored = transaction.abortToPrevious(System.currentTimeMillis());
        lifecycleTransactions.put(restored);
        return restored;
    }

    synchronized PackageLifecycleTransaction resetIdentity(String packageName) throws Exception {
        String normalized = requirePackageName(packageName);
        SandboxRecord record = findRecord(normalized);
        if (record == null) throw new IllegalArgumentException("Package is not installed: " + normalized);
        long now = System.currentTimeMillis();
        PackageLifecycleTransaction transaction = lifecycleTransactions.get(normalized);
        if (transaction == null) transaction = PackageLifecycleTransaction.installed(record, now);
        transaction.requireNotInFlight("identity-reset");
        transaction = transaction.withState(PackageLifecycleTransaction.State.RESETTING, now, "identity-reset");
        lifecycleTransactions.put(transaction);
        PackageLifecycleTransaction reset = transaction.resetIdentity(now);
        lifecycleTransactions.put(reset);
        return reset;
    }

    synchronized PackageLifecycleTransaction lifecycleTransaction(String packageName) throws Exception {
        return lifecycleTransactions.get(requirePackageName(packageName));
    }

    private void abortPreparedUpdate(String packageName, SandboxCatalogState current,
                                     SandboxRecord imported, Exception cause) {
        try {
            PackageLifecycleTransaction transaction = lifecycleTransactions.get(packageName);
            if (transaction != null && (transaction.state == PackageLifecycleTransaction.State.UPDATING_PREPARE
                    || transaction.state == PackageLifecycleTransaction.State.UPDATING_SWITCH)) {
                lifecycleTransactions.put(transaction.abortToPrevious(System.currentTimeMillis()));
            }
            deletePublishedRevisionIfUnreferenced(current, imported);
        } catch (Exception cleanupFailure) {
            if (cause != null) cause.addSuppressed(cleanupFailure);
        }
    }

    private static String requirePackageName(String packageName) {
        String normalized = packageName == null ? "" : packageName.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("packageName is required");
        return normalized;
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

    /** Clears only the selected Guest instance root and keeps the authoritative instance record. */
    synchronized void clearInstanceData(String packageName, int virtualUserId) throws Exception {
        SandboxCatalogState current = catalogRepository.load();
        boolean exists = false;
        for (SandboxInstance instance : current.instances()) {
            if (instance.packageName.equals(packageName) && instance.virtualUserId == virtualUserId) {
                exists = true;
                break;
            }
        }
        if (!exists) throw new IllegalArgumentException("Sandbox instance does not exist");
        File root = instanceDirectory(packageName, virtualUserId).getCanonicalFile();
        if (java.nio.file.Files.isSymbolicLink(root.toPath())) {
            throw new SecurityException("INSTANCE_DATA_ROOT_SYMLINK");
        }
        if (!root.exists()) {
            resetPolicyAfterDataClear(current, packageName, virtualUserId);
            return;
        }
        if (!root.isDirectory()) throw new IllegalStateException("Instance data root is not a directory");
        File[] children = root.listFiles();
        if (children == null) throw new IllegalStateException("Cannot list instance data root");
        for (File child : children) {
            if (java.nio.file.Files.isSymbolicLink(child.toPath())) {
                throw new SecurityException("INSTANCE_DATA_CHILD_SYMLINK");
            }
            ApkImportManager.deleteTreeOrThrow(child);
        }
        resetPolicyAfterDataClear(current, packageName, virtualUserId);
    }

    private void resetPolicyAfterDataClear(SandboxCatalogState current, String packageName,
                                           int virtualUserId) throws Exception {
        SandboxCatalogState next = current.withPolicyReset(packageName, virtualUserId,
                "clear instance data", "HOST_MAIN", System.currentTimeMillis());
        catalogRepository.save(next);
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
        try {
            for (String retained : lifecycleTransactions.retainedRevisionPaths()) {
                File parent = new File(retained).getCanonicalFile().getParentFile();
                if (parent != null) referencedRevisionDirectories.add(parent.getCanonicalPath());
            }
        } catch (Exception error) {
            failures.add("Cannot resolve retained rollback revisions: " + error.getMessage());
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

    private static File requireInstalledArtifact(String path, String field) throws Exception {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("Installed application " + field + " is missing");
        }
        File file = new File(path).getCanonicalFile();
        if (!file.isFile()) throw new IllegalArgumentException(
                "Installed application " + field + " is unavailable: " + file);
        return file;
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
