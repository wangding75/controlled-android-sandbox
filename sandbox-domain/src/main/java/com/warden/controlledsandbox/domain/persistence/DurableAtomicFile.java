package com.warden.controlledsandbox.domain.persistence;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

/** Strict same-filesystem atomic replacement with file and parent-directory durability. */
public final class DurableAtomicFile {
    public static final String ATOMIC_MOVE_UNSUPPORTED = "STRICT_ATOMIC_MOVE_UNSUPPORTED";
    public static final String DIRECTORY_SYNC_FAILED = "PARENT_DIRECTORY_FSYNC_FAILED";
    private static final String DURABILITY_REPAIR_PREFIX = ".durability-pending-";

    /** Observable commit phase for callers that must distinguish logical commit from durability. */
    public enum CommitState {
        PRE_RENAME_FAILED,
        POST_RENAME_DURABILITY_UNCERTAIN,
        COMMITTED
    }

    /** Result returned after the directory entry was atomically changed. */
    public static final class CommitResult {
        private static final CommitResult COMMITTED_RESULT =
                new CommitResult(CommitState.COMMITTED, null);

        private final CommitState state;
        private final IOException durabilityFailure;

        private CommitResult(CommitState state, IOException durabilityFailure) {
            this.state = Objects.requireNonNull(state, "state");
            this.durabilityFailure = durabilityFailure;
        }

        public CommitState state() { return state; }
        public boolean committed() { return state != CommitState.PRE_RENAME_FAILED; }
        public boolean durabilityConfirmed() { return state == CommitState.COMMITTED; }
        public IOException durabilityFailure() { return durabilityFailure; }

        public static CommitResult confirmed() { return COMMITTED_RESULT; }

        public static CommitResult uncertain(IOException failure) {
            return new CommitResult(CommitState.POST_RENAME_DURABILITY_UNCERTAIN,
                    Objects.requireNonNull(failure, "failure"));
        }
    }

    /** Failure guaranteed to have occurred before the atomic directory-entry change. */
    public static final class CommitFailure extends IOException {
        private static final long serialVersionUID = 1L;
        private final CommitState state;

        private CommitFailure(String message, Throwable cause) {
            super(message, cause);
            state = CommitState.PRE_RENAME_FAILED;
        }

        public CommitState state() { return state; }
    }

    private DurableAtomicFile() { }

    public static CommitResult write(Path destination, byte[] bytes) throws IOException {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(bytes, "bytes");
        Path normalized = destination.toAbsolutePath().normalize();
        Path parent = requireParent(normalized);
        Files.createDirectories(parent);
        Path temporary = parent.resolve(normalized.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            } catch (IOException failure) {
                throw preRenameFailure(failure);
            }
            return replacePrepared(temporary, normalized);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Publishes a fully prepared file. The source remains untrusted until the rename commits. */
    public static CommitResult replacePrepared(Path prepared, Path destination) throws IOException {
        return replacePrepared(prepared, destination, NioOperations.INSTANCE);
    }

    /** Strictly moves an existing file or directory and persists both directory-entry changes. */
    public static CommitResult move(Path source, Path destination) throws IOException {
        return move(source, destination, NioMoveOperations.INSTANCE);
    }

    /** Performs a write and records any committed-but-not-yet-confirmed directory entry. */
    public static void writeAcknowledged(Path destination, byte[] bytes) throws IOException {
        repairPending(destination);
        CommitResult result = write(destination, bytes);
        acknowledge(destination, result);
    }

    /** Publishes a prepared file and records any committed durability uncertainty. */
    public static void replacePreparedAcknowledged(Path prepared, Path destination)
            throws IOException {
        repairPending(destination);
        CommitResult result = replacePrepared(prepared, destination);
        acknowledge(destination, result);
    }

    /** Moves a path and records uncertainty for both changed parent directories. */
    public static void moveAcknowledged(Path source, Path destination) throws IOException {
        repairPending(source);
        repairPending(destination);
        CommitResult result = move(source, destination);
        if (!result.durabilityConfirmed()) {
            acknowledge(source, result);
            acknowledge(destination, result);
        }
    }

    /** Records post-rename uncertainty without turning an already committed mutation into failure. */
    static void acknowledge(Path destination, CommitResult result) {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(result, "result");
        if (result.durabilityConfirmed()) return;
        Path marker = repairMarker(destination);
        try {
            Files.createDirectories(marker.getParent());
            byte[] payload = (destination.toAbsolutePath().normalize() + "\n"
                    + result.state() + "\n"
                    + String.valueOf(result.durabilityFailure()) + "\n")
                    .getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(marker, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(payload);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try { syncDirectory(marker.getParent()); }
            catch (IOException markerSyncFailure) {
                if (result.durabilityFailure() != null) {
                    result.durabilityFailure().addSuppressed(markerSyncFailure);
                }
            }
        } catch (IOException markerFailure) {
            if (result.durabilityFailure() != null) {
                result.durabilityFailure().addSuppressed(markerFailure);
            }
        }
    }

    /** Repairs and clears a previous durability marker before the next mutation of the same path. */
    static void repairPending(Path destination) throws IOException {
        Objects.requireNonNull(destination, "destination");
        Path marker = repairMarker(destination);
        if (!Files.exists(marker)) return;
        Path parent = marker.getParent();
        syncDirectory(parent);
        Files.deleteIfExists(marker);
        syncDirectory(parent);
    }

    static boolean hasPendingDurabilityRepair(Path destination) {
        return Files.exists(repairMarker(destination));
    }

    public static void syncDirectory(Path directory) throws IOException {
        Objects.requireNonNull(directory, "directory");
        Path normalized = directory.toAbsolutePath().normalize();
        if (Files.isDirectory(normalized) && isWindows()) {
            return;
        }
        try (FileChannel channel = FileChannel.open(normalized, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (java.nio.file.AccessDeniedException accessDenied) {
            if (Files.isDirectory(normalized)) {
                return;
            }
            throw directorySyncFailure(directory, accessDenied);
        } catch (IOException | UnsupportedOperationException failure) {
            throw directorySyncFailure(directory, failure);
        }
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name");
        return os != null && os.toLowerCase().contains("win");
    }

    static CommitResult replacePrepared(Path prepared, Path destination, Operations operations)
            throws IOException {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(operations, "operations");
        Path normalizedPrepared = prepared.toAbsolutePath().normalize();
        Path normalizedDestination = destination.toAbsolutePath().normalize();
        Path parent = requireParent(normalizedDestination);
        if (!requireParent(normalizedPrepared).equals(parent)) {
            throw preRenameFailure(new IOException("ATOMIC_REPLACE_TEMP_NOT_SIBLING"));
        }
        try {
            operations.forceFile(normalizedPrepared);
            operations.atomicReplace(normalizedPrepared, normalizedDestination);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw preRenameFailure(unsupported(unsupported));
        } catch (IOException failure) {
            throw preRenameFailure(failure);
        }
        try {
            operations.forceDirectory(parent);
            return CommitResult.confirmed();
        } catch (IOException failure) {
            return CommitResult.uncertain(normalizeDirectoryFailure(parent, failure));
        }
    }

    static CommitResult move(Path source, Path destination, MoveOperations operations)
            throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(operations, "operations");
        Path normalizedSource = source.toAbsolutePath().normalize();
        Path normalizedDestination = destination.toAbsolutePath().normalize();
        Path destinationParent = requireParent(normalizedDestination);
        Path sourceParent = requireParent(normalizedSource);
        try {
            operations.createDirectories(destinationParent);
            operations.atomicMove(normalizedSource, normalizedDestination);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw preRenameFailure(unsupported(unsupported));
        } catch (IOException failure) {
            throw preRenameFailure(failure);
        }

        IOException durabilityFailure = null;
        try {
            operations.forceDirectory(destinationParent);
        } catch (IOException failure) {
            durabilityFailure = normalizeDirectoryFailure(destinationParent, failure);
        }
        if (!sourceParent.equals(destinationParent)) {
            try {
                operations.forceDirectory(sourceParent);
            } catch (IOException failure) {
                IOException normalized = normalizeDirectoryFailure(sourceParent, failure);
                if (durabilityFailure == null) durabilityFailure = normalized;
                else durabilityFailure.addSuppressed(normalized);
            }
        }
        return durabilityFailure == null
                ? CommitResult.confirmed()
                : CommitResult.uncertain(durabilityFailure);
    }

    private static CommitFailure preRenameFailure(IOException failure) {
        if (failure instanceof CommitFailure commitFailure) return commitFailure;
        String message = failure.getMessage();
        return new CommitFailure(message == null || message.isBlank()
                ? CommitState.PRE_RENAME_FAILED.name() : message, failure);
    }

    private static IOException normalizeDirectoryFailure(Path directory, IOException failure) {
        if (failure.getMessage() != null && failure.getMessage().contains(DIRECTORY_SYNC_FAILED)) {
            return failure;
        }
        return directorySyncFailure(directory, failure);
    }

    private static IOException directorySyncFailure(Path directory, Throwable failure) {
        return new IOException(DIRECTORY_SYNC_FAILED + ":" + directory, failure);
    }

    private static IOException unsupported(AtomicMoveNotSupportedException failure) {
        return new IOException(ATOMIC_MOVE_UNSUPPORTED + ":" + failure.getFile() + "->" + failure.getOtherFile(),
                failure);
    }

    private static Path repairMarker(Path destination) {
        Path normalized = destination.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null) parent = normalized.toAbsolutePath().getParent();
        String digest;
        try {
            byte[] encoded = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(16);
            for (int i = 0; i < 8; i++) value.append(String.format("%02x", encoded[i]));
            digest = value.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
        return Objects.requireNonNull(parent, "destination parent")
                .resolve(DURABILITY_REPAIR_PREFIX + digest + ".marker");
    }

    private static Path requireParent(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent == null) throw preRenameFailure(new IOException("ATOMIC_REPLACE_PARENT_REQUIRED:" + path));
        return parent;
    }

    interface Operations {
        void forceFile(Path file) throws IOException;
        void atomicReplace(Path source, Path destination) throws IOException;
        void forceDirectory(Path directory) throws IOException;
    }

    interface MoveOperations {
        void createDirectories(Path directory) throws IOException;
        void atomicMove(Path source, Path destination) throws IOException;
        void forceDirectory(Path directory) throws IOException;
    }

    private enum NioOperations implements Operations {
        INSTANCE;

        @Override public void forceFile(Path file) throws IOException {
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
        }

        @Override public void atomicReplace(Path source, Path destination) throws IOException {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        }

        @Override public void forceDirectory(Path directory) throws IOException {
            syncDirectory(directory);
        }
    }

    private enum NioMoveOperations implements MoveOperations {
        INSTANCE;

        @Override public void createDirectories(Path directory) throws IOException {
            Files.createDirectories(directory);
        }

        @Override public void atomicMove(Path source, Path destination) throws IOException {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        }

        @Override public void forceDirectory(Path directory) throws IOException {
            syncDirectory(directory);
        }
    }
}
