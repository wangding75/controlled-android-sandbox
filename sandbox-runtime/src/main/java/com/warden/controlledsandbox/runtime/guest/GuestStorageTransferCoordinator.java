package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.domain.persistence.DurableAtomicFile;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Serializes cross-context Guest storage moves and rolls back partial companion-file transfers. */
final class GuestStorageTransferCoordinator {
    static final String IDENTITY_MISMATCH = "CROSS_GUEST_STORAGE_MOVE_DENIED";
    static final String MOVE_FAILED = "GUEST_STORAGE_MOVE_FAILED";
    static final String ROLLBACK_FAILED = "GUEST_STORAGE_MOVE_ROLLBACK_FAILED";

    private static final String LOCK_NAME = ".guest-storage-transfer.lock";
    private static final String REPAIR_NAME = ".guest-storage-transfer.repair";
    private static final ConcurrentHashMap<String, Object> JVM_LOCKS = new ConcurrentHashMap<>();

    private GuestStorageTransferCoordinator() {}

    static boolean move(File instanceRoot, File source, File target, String... companionSuffixes) {
        return move(instanceRoot, source, target, GuestStorageTransferCoordinator::moveOne,
                companionSuffixes);
    }

    static boolean moveForTest(File instanceRoot, File source, File target,
                               FileMover mover, String... companionSuffixes) {
        return move(instanceRoot, source, target, mover, companionSuffixes);
    }

    private static boolean move(File instanceRoot, File source, File target, FileMover mover,
                                String... companionSuffixes) {
        File canonicalRoot = canonical(instanceRoot, "GUEST_STORAGE_TRANSFER_ROOT_INVALID");
        File canonicalSource = canonical(source, "GUEST_STORAGE_TRANSFER_SOURCE_INVALID");
        File canonicalTarget = canonical(target, "GUEST_STORAGE_TRANSFER_TARGET_INVALID");
        requireInside(canonicalRoot, canonicalSource);
        requireInside(canonicalRoot, canonicalTarget);
        if (canonicalSource.equals(canonicalTarget)) return false;

        Object jvmLock = JVM_LOCKS.computeIfAbsent(canonicalRoot.getAbsolutePath(), ignored -> new Object());
        synchronized (jvmLock) {
            File lockFile = new File(canonicalRoot, LOCK_NAME);
            try (RandomAccessFile raw = new RandomAccessFile(lockFile, "rw");
                 FileChannel channel = raw.getChannel();
                 FileLock lock = channel.lock()) {
                if (!lock.isValid()) throw new IOException("transfer lock is invalid");
                boolean priorRepairComplete = retryPendingDurabilityRepair(canonicalRoot);
                return moveLocked(canonicalRoot, canonicalSource, canonicalTarget, mover,
                        priorRepairComplete, companionSuffixes);
            } catch (IOException error) {
                throw new IllegalStateException(MOVE_FAILED, error);
            }
        }
    }

    private static boolean moveLocked(File instanceRoot, File source, File target, FileMover mover,
                                      boolean priorRepairComplete, String[] companionSuffixes) {
        if (!source.isFile()) return false;
        List<String> companions = normalizedCompanions(companionSuffixes);
        if (target.exists()) return false;
        for (String suffix : companions) {
            if (new File(target.getPath() + suffix).exists()) return false;
        }
        File targetParent = target.getParentFile();
        if (!targetParent.isDirectory() && !targetParent.mkdirs() && !targetParent.isDirectory()) {
            throw new IllegalStateException(MOVE_FAILED + ":cannot create target directory");
        }

        List<Move> completed = new ArrayList<>();
        List<File> uncertainDirectories = new ArrayList<>();
        try {
            for (String suffix : companions) {
                File companion = new File(source.getPath() + suffix);
                if (companion.exists()) {
                    File destination = new File(target.getPath() + suffix);
                    DurableAtomicFile.CommitResult result = mover.move(companion, destination);
                    completed.add(new Move(companion, destination));
                    collectUncertainDirectories(result, companion, destination,
                            uncertainDirectories);
                }
            }
            // Main artifact is the commit marker and moves last.
            DurableAtomicFile.CommitResult mainResult = mover.move(source, target);
            completed.add(new Move(source, target));
            collectUncertainDirectories(mainResult, source, target, uncertainDirectories);

            collectDirectorySyncUncertainty(source.getParentFile(), uncertainDirectories);
            if (!source.getParentFile().equals(targetParent)) {
                collectDirectorySyncUncertainty(targetParent, uncertainDirectories);
            }
            if (uncertainDirectories.isEmpty()) {
                if (priorRepairComplete) clearRepairMarker(instanceRoot);
            } else {
                recordRepairMarker(instanceRoot, uncertainDirectories);
            }
            return true;
        } catch (RuntimeException failure) {
            rollback(completed, mover, failure);
            throw failure;
        }
    }

    private static void collectUncertainDirectories(DurableAtomicFile.CommitResult result,
                                                     File source, File target,
                                                     List<File> directories) {
        if (result == null) {
            throw new IllegalStateException(MOVE_FAILED + ":mover returned no commit result");
        }
        if (result.state() != DurableAtomicFile.CommitState.POST_RENAME_DURABILITY_UNCERTAIN) {
            return;
        }
        directories.add(source.getParentFile());
        if (!source.getParentFile().equals(target.getParentFile())) {
            directories.add(target.getParentFile());
        }
    }

    private static void collectDirectorySyncUncertainty(File directory, List<File> directories) {
        try {
            DurableAtomicFile.syncDirectory(directory.toPath());
        } catch (IOException error) {
            directories.add(directory);
        }
    }

    private static void rollback(List<Move> completed, FileMover mover, RuntimeException original) {
        RuntimeException rollbackFailure = null;
        for (int index = completed.size() - 1; index >= 0; index--) {
            Move move = completed.get(index);
            if (!move.target.exists()) continue;
            try {
                mover.move(move.target, move.source);
            } catch (RuntimeException failure) {
                if (rollbackFailure == null) {
                    rollbackFailure = new IllegalStateException(ROLLBACK_FAILED, failure);
                } else {
                    rollbackFailure.addSuppressed(failure);
                }
            }
        }
        if (rollbackFailure != null) {
            rollbackFailure.addSuppressed(original);
            throw rollbackFailure;
        }
    }

    private static DurableAtomicFile.CommitResult moveOne(File source, File target) {
        try {
            return DurableAtomicFile.move(source.toPath(), target.toPath());
        } catch (IOException error) {
            throw new IllegalStateException(MOVE_FAILED + ":" + source.getName(), error);
        }
    }

    private static boolean retryPendingDurabilityRepair(File instanceRoot) {
        File marker = new File(instanceRoot, REPAIR_NAME);
        if (!marker.isFile()) return true;
        boolean repaired = true;
        try {
            for (String relative : Files.readAllLines(marker.toPath(), StandardCharsets.UTF_8)) {
                if (relative == null || relative.isBlank()) continue;
                File directory = canonical(new File(instanceRoot, relative),
                        "GUEST_STORAGE_REPAIR_PATH_INVALID");
                requireInside(instanceRoot, directory);
                if (!directory.isDirectory()) continue;
                try {
                    DurableAtomicFile.syncDirectory(directory.toPath());
                } catch (IOException error) {
                    repaired = false;
                }
            }
        } catch (IOException | RuntimeException error) {
            repaired = false;
        }
        if (repaired) clearRepairMarker(instanceRoot);
        return repaired;
    }

    private static void recordRepairMarker(File instanceRoot, List<File> directories) {
        Set<String> relativeDirectories = new LinkedHashSet<>();
        File marker = new File(instanceRoot, REPAIR_NAME);
        if (marker.isFile()) {
            try {
                for (String existing : Files.readAllLines(marker.toPath(), StandardCharsets.UTF_8)) {
                    if (existing != null && !existing.isBlank()) relativeDirectories.add(existing);
                }
            } catch (IOException ignored) { }
        }
        for (File directory : directories) {
            if (directory == null) continue;
            File canonicalDirectory = canonical(directory, "GUEST_STORAGE_REPAIR_PATH_INVALID");
            requireInside(instanceRoot, canonicalDirectory);
            String relative = instanceRoot.toPath().relativize(
                    canonicalDirectory.toPath()).toString();
            relativeDirectories.add(relative.isEmpty() ? "." : relative);
        }
        if (relativeDirectories.isEmpty()) return;
        try {
            String payload = String.join("\n", relativeDirectories) + "\n";
            DurableAtomicFile.writeAcknowledged(new File(instanceRoot, REPAIR_NAME).toPath(),
                    payload.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // The data move is already committed. Failure to persist the advisory repair marker
            // must not turn a committed move into a reported failure.
        }
    }

    private static void clearRepairMarker(File instanceRoot) {
        File marker = new File(instanceRoot, REPAIR_NAME);
        try {
            if (!Files.deleteIfExists(marker.toPath())) return;
            DurableAtomicFile.syncDirectory(instanceRoot.toPath());
        } catch (IOException ignored) {
            // A stale marker is safe: the next serialized transfer retries the directory syncs.
        }
    }

    private static List<String> normalizedCompanions(String[] suffixes) {
        if (suffixes == null || suffixes.length == 0) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (String suffix : suffixes) {
            if (suffix == null || suffix.isEmpty() || result.contains(suffix)) continue;
            result.add(suffix);
        }
        return result;
    }

    private static File canonical(File file, String code) {
        try {
            return file.getCanonicalFile();
        } catch (IOException error) {
            throw new IllegalStateException(code, error);
        }
    }

    private static void requireInside(File root, File file) {
        String rootPath = root.getPath();
        String filePath = file.getPath();
        if (!filePath.equals(rootPath) && !filePath.startsWith(rootPath + File.separator)) {
            throw new SecurityException(IDENTITY_MISMATCH + ":outside instance root");
        }
    }

    interface FileMover {
        DurableAtomicFile.CommitResult move(File source, File target);
    }

    private static final class Move {
        final File source;
        final File target;
        Move(File source, File target) { this.source = source; this.target = target; }
    }
}
