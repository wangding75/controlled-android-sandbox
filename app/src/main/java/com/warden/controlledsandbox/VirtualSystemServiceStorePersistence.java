package com.warden.controlledsandbox;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.zip.CRC32;
import org.json.JSONObject;

/** Bounded, checksummed and atomic file boundary for virtual system-service state. */
final class VirtualSystemServiceStorePersistence {
    static final int ENVELOPE_VERSION = 1;
    static final int MAX_PAYLOAD_BYTES = 8 * 1024 * 1024;
    static final int MAX_FILE_BYTES = 12 * 1024 * 1024;

    static final class CorruptStoreException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        CorruptStoreException(String message, Throwable cause) { super(message, cause); }
        CorruptStoreException(String message) { super(message); }
    }

    private final File file;

    VirtualSystemServiceStorePersistence(File file) {
        if (file == null) throw new IllegalArgumentException("store file is required");
        this.file = file;
    }

    String readPayload() {
        if (!file.isFile()) return null;
        try {
            long length = file.length();
            if (length < 0L || length > MAX_FILE_BYTES) {
                throw new CorruptStoreException("VIRTUAL_SYSTEM_SERVICE_STORE_FILE_LIMIT_EXCEEDED");
            }
            byte[] bytes = Files.readAllBytes(file.toPath());
            if (bytes.length > MAX_FILE_BYTES) {
                throw new CorruptStoreException("VIRTUAL_SYSTEM_SERVICE_STORE_FILE_LIMIT_EXCEEDED");
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(text);
            if (!root.keySet().contains("envelopeVersion")) {
                // Schema 1-5 legacy files were raw JSON payloads.
                return boundedPayload(text);
            }
            if (root.optInt("envelopeVersion", -1) != ENVELOPE_VERSION) {
                throw new CorruptStoreException("VIRTUAL_SYSTEM_SERVICE_STORE_ENVELOPE_UNSUPPORTED");
            }
            String payload = boundedPayload(root.getString("payload"));
            long expected = Long.parseUnsignedLong(root.getString("crc32"));
            long actual = checksum(payload.getBytes(StandardCharsets.UTF_8));
            if (expected != actual) {
                throw new CorruptStoreException("VIRTUAL_SYSTEM_SERVICE_STORE_CHECKSUM_MISMATCH");
            }
            return payload;
        } catch (CorruptStoreException error) {
            quarantine();
            throw error;
        } catch (Exception error) {
            quarantine();
            throw new CorruptStoreException("VIRTUAL_SYSTEM_SERVICE_STORE_CORRUPT", error);
        }
    }

    void writePayload(String payload) {
        try {
            String bounded = boundedPayload(payload);
            byte[] payloadBytes = bounded.getBytes(StandardCharsets.UTF_8);
            JSONObject envelope = new JSONObject()
                    .put("envelopeVersion", ENVELOPE_VERSION)
                    .put("crc32", Long.toUnsignedString(checksum(payloadBytes)))
                    .put("payload", bounded);
            byte[] bytes = envelope.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_FILE_BYTES) {
                throw new IllegalStateException("VIRTUAL_SYSTEM_SERVICE_STORE_FILE_LIMIT_EXCEEDED");
            }
            File parent = file.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IllegalStateException("Cannot create store directory");
            }
            File temp = new File(parent, file.getName() + ".tmp");
            try {
                try (FileOutputStream out = new FileOutputStream(temp)) {
                    out.write(bytes);
                    out.flush();
                    out.getFD().sync();
                }
                try {
                    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException error) {
                    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                if (temp.exists() && !temp.delete()) temp.deleteOnExit();
            }
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("Cannot persist virtual system-service store", error);
        }
    }

    File file() { return file; }

    private String boundedPayload(String payload) {
        if (payload == null) throw new CorruptStoreException("VIRTUAL_SYSTEM_SERVICE_STORE_PAYLOAD_MISSING");
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_PAYLOAD_BYTES) {
            throw new CorruptStoreException("VIRTUAL_SYSTEM_SERVICE_STORE_PAYLOAD_LIMIT_EXCEEDED");
        }
        return payload;
    }

    void quarantine() {
        if (!file.isFile()) return;
        File parent = file.getParentFile();
        File corrupt = new File(parent, file.getName() + ".corrupt");
        try {
            Files.move(file.toPath(), corrupt.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
            // The caller still fails closed; inability to rename cannot make the state trusted.
        }
    }

    private static long checksum(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return crc.getValue();
    }
}
