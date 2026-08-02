package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.domain.persistence.DurableAtomicFile;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Serializes cross-context Guest storage moves and rolls back partial companion-file transfers. */
final class GuestStorageTransferCoordinator {
    static final String IDENTITY_MISMATCH = "CROSS_GUEST_STORAGE_MOVE_DENIED";
    static final String MOVE_FAILED = "GUEST_STORAGE_MOVE_FAILED";
    static final String ROLLBACK_FAILED = "GUEST_STORAGE_MOVE_ROLLBACK_FAILED";

    private static final String LOCK_NAME = ".guest-storage-transfer.lock";
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
                return moveLocked(canonicalSource, canonicalTarget, mover, companionSuffixes);
            } catch (IOException error) {
                throw new IllegalStateException(MOVE_FAILED, error);
            }
        }
    }

    private static boolean moveLocked(File source, File target, FileMover mover,
                                      String[] companionSuffixes) {
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
        try {
            for (String suffix : companions) {
                File companion = new File(source.getPath() + suffix);
                if (companion.exists()) {
                    File destination = new File(target.getPath() + suffix);
                    mover.move(companion, destination);
                    completed.add(new Move(companion, destination));
                }
            }
            mover.move(source, target); // Main artifact is the commit marker and moves last.
            completed.add(new Move(source, target));
            syncDirectory(source.getParentFile());
            if (!source.getParentFile().equals(targetParent)) syncDirectory(targetParent);
            return true;
        } catch (RuntimeException failure) {
            rollback(completed, mover, failure);
            throw failure;
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

    private static void moveOne(File source, File target) {
        try {
            DurableAtomicFile.move(source.toPath(), target.toPath());
        } catch (IOException error) {
            throw new IllegalStateException(MOVE_FAILED + ":" + source.getName(), error);
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

    private static void syncDirectory(File directory) {
        try {
            DurableAtomicFile.syncDirectory(directory.toPath());
        } catch (IOException error) {
            throw new IllegalStateException("GUEST_STORAGE_DIRECTORY_FSYNC_FAILED", error);
        }
    }

    interface FileMover { void move(File source, File target); }

    private static final class Move {
        final File source;
        final File target;
        Move(File source, File target) { this.source = source; this.target = target; }
    }
}
