package com.warden.controlledsandbox.domain.persistence;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;

/** Strict same-filesystem atomic replacement with file and parent-directory durability. */
public final class DurableAtomicFile {
    public static final String ATOMIC_MOVE_UNSUPPORTED = "STRICT_ATOMIC_MOVE_UNSUPPORTED";
    public static final String DIRECTORY_SYNC_FAILED = "PARENT_DIRECTORY_FSYNC_FAILED";

    private DurableAtomicFile() { }

    public static void write(Path destination, byte[] bytes) throws IOException {
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
            }
            replacePrepared(temporary, normalized);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Publishes a fully prepared file. The source remains untrusted until this method returns. */
    public static void replacePrepared(Path prepared, Path destination) throws IOException {
        replacePrepared(prepared, destination, NioOperations.INSTANCE);
    }

    /** Strictly moves an existing file or directory and persists both directory-entry changes. */
    public static void move(Path source, Path destination) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        Path normalizedSource = source.toAbsolutePath().normalize();
        Path normalizedDestination = destination.toAbsolutePath().normalize();
        Path destinationParent = requireParent(normalizedDestination);
        Files.createDirectories(destinationParent);
        Path sourceParent = requireParent(normalizedSource);
        try {
            Files.move(normalizedSource, normalizedDestination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw unsupported(unsupported);
        }
        syncDirectory(destinationParent);
        if (!sourceParent.equals(destinationParent)) syncDirectory(sourceParent);
    }

    public static void syncDirectory(Path directory) throws IOException {
        Objects.requireNonNull(directory, "directory");
        try (FileChannel channel = FileChannel.open(directory.toAbsolutePath().normalize(),
                StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException failure) {
            throw new IOException(DIRECTORY_SYNC_FAILED + ":" + directory, failure);
        }
    }

    static void replacePrepared(Path prepared, Path destination, Operations operations) throws IOException {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(operations, "operations");
        Path normalizedPrepared = prepared.toAbsolutePath().normalize();
        Path normalizedDestination = destination.toAbsolutePath().normalize();
        Path parent = requireParent(normalizedDestination);
        if (!requireParent(normalizedPrepared).equals(parent)) {
            throw new IOException("ATOMIC_REPLACE_TEMP_NOT_SIBLING");
        }
        operations.forceFile(normalizedPrepared);
        try {
            operations.atomicReplace(normalizedPrepared, normalizedDestination);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw unsupported(unsupported);
        }
        operations.forceDirectory(parent);
    }

    private static IOException unsupported(AtomicMoveNotSupportedException failure) {
        return new IOException(ATOMIC_MOVE_UNSUPPORTED + ":" + failure.getFile() + "->" + failure.getOtherFile(),
                failure);
    }

    private static Path requireParent(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent == null) throw new IOException("ATOMIC_REPLACE_PARENT_REQUIRED:" + path);
        return parent;
    }

    interface Operations {
        void forceFile(Path file) throws IOException;
        void atomicReplace(Path source, Path destination) throws IOException;
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
}
