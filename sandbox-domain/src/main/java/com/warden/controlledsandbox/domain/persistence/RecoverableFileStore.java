package com.warden.controlledsandbox.domain.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * UTF-8 file store with an independently written last-known-good copy.
 * Corruption is never converted into an empty state.
 */
public final class RecoverableFileStore {
    public static final long DEFAULT_MAX_BYTES = 8L * 1024 * 1024;
    private final Path primary;
    private final Path backup;
    private final long maxBytes;

    public RecoverableFileStore(Path primary) {
        this(primary, DEFAULT_MAX_BYTES);
    }

    public RecoverableFileStore(Path primary, long maxBytes) {
        this.primary = Objects.requireNonNull(primary, "primary").toAbsolutePath().normalize();
        this.backup = this.primary.resolveSibling(this.primary.getFileName() + ".lastgood");
        if (maxBytes < 1) throw new IllegalArgumentException("maxBytes must be positive");
        this.maxBytes = maxBytes;
    }

    public synchronized <T> T read(Decoder<T> decoder, T emptyValue) {
        Objects.requireNonNull(decoder, "decoder");
        boolean primaryExists = Files.isRegularFile(primary);
        boolean backupExists = Files.isRegularFile(backup);
        if (!primaryExists && !backupExists) return emptyValue;

        Throwable primaryFailure = null;
        if (primaryExists) {
            String trusted = null;
            T decoded = null;
            try {
                trusted = readUtf8(primary);
                decoded = decoder.decode(trusted);
            } catch (Throwable error) {
                FatalErrorPolicy.rethrowIfFatal(error);
                primaryFailure = error;
            }
            if (primaryFailure == null) {
                try {
                    writePath(backup, trusted);
                } catch (IOException error) {
                    throw new PersistentStateException("Cannot refresh last-known-good backup: " + backup, error);
                }
                return decoded;
            }
        }

        if (backupExists) {
            try {
                String recovered = readUtf8(backup);
                T decoded = decoder.decode(recovered);
                writePath(primary, recovered);
                return decoded;
            } catch (Throwable backupFailure) {
                FatalErrorPolicy.rethrowIfFatal(backupFailure);
                PersistentStateException failure = new PersistentStateException(
                        "Persisted state is corrupt and no trusted backup can be recovered: " + primary,
                        primaryFailure == null ? backupFailure : primaryFailure);
                if (primaryFailure != null) failure.addSuppressed(backupFailure);
                throw failure;
            }
        }

        throw new PersistentStateException("Persisted state is corrupt and no backup exists: " + primary,
                primaryFailure);
    }

    public synchronized void write(String content) throws IOException {
        Objects.requireNonNull(content, "content");
        byte[] encoded = content.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > maxBytes) throw new IOException("Persisted state exceeds limit: " + encoded.length);
        // Publish the recoverable copy first. If the primary write then fails in the
        // same process, restore the previous backup (or remove the first-write backup)
        // so callers may safely roll back files associated with the failed metadata switch.
        boolean previousBackupExists = Files.isRegularFile(backup);
        String previousBackup = previousBackupExists ? readUtf8(backup) : null;
        writePath(backup, content, encoded);
        try {
            writePath(primary, content, encoded);
        } catch (IOException primaryFailure) {
            try {
                if (previousBackupExists) writePath(backup, previousBackup, previousBackup.getBytes(StandardCharsets.UTF_8));
                else Files.deleteIfExists(backup);
            } catch (IOException rollbackFailure) {
                primaryFailure.addSuppressed(rollbackFailure);
            }
            throw primaryFailure;
        }
    }

    public Path primary() { return primary; }
    public Path backup() { return backup; }

    private String readUtf8(Path path) throws IOException {
        long size = Files.size(path);
        if (size < 0 || size > maxBytes) throw new IOException("Persisted state exceeds limit: " + size);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private void writePath(Path destination, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) throw new IOException("Persisted state exceeds limit: " + bytes.length);
        writePath(destination, content, bytes);
    }

    private static void writePath(Path destination, String content, byte[] bytes) throws IOException {
        DurableAtomicFile.write(destination, bytes);
    }

    @FunctionalInterface
    public interface Decoder<T> {
        T decode(String content) throws Exception;
    }
}
