package com.warden.controlledsandbox;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.zip.CRC32;
import org.json.JSONObject;

/** Atomic bounded checksummed persistence for media/communication profiles. */
final class VirtualMediaCommunicationStorePersistence {
    static final int ENVELOPE_VERSION = 1;
    static final int MAX_PAYLOAD_BYTES = 8 * 1024 * 1024;
    static final int MAX_FILE_BYTES = 10 * 1024 * 1024;

    private final File file;

    VirtualMediaCommunicationStorePersistence(File file) {
        if (file == null) throw new IllegalArgumentException("store file is required");
        this.file = file;
    }

    String readPayload() {
        if (!file.isFile()) return null;
        try {
            if (file.length() > MAX_FILE_BYTES) {
                throw corrupt("MEDIA_COMMUNICATION_FILE_LIMIT_EXCEEDED");
            }
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(text);
            if (root.optInt("envelopeVersion", -1) != ENVELOPE_VERSION) {
                throw corrupt("MEDIA_COMMUNICATION_ENVELOPE_UNSUPPORTED");
            }
            String payload = bounded(root.getString("payload"));
            long expected = Long.parseUnsignedLong(root.getString("crc32"));
            long actual = checksum(payload.getBytes(StandardCharsets.UTF_8));
            if (expected != actual) {
                throw corrupt("MEDIA_COMMUNICATION_CHECKSUM_MISMATCH");
            }
            return payload;
        } catch (RuntimeException error) {
            quarantine();
            throw error;
        } catch (Exception error) {
            quarantine();
            throw new IllegalStateException("MEDIA_COMMUNICATION_STORE_CORRUPT", error);
        }
    }

    void writePayload(String payload) {
        try {
            String bounded = bounded(payload);
            byte[] payloadBytes = bounded.getBytes(StandardCharsets.UTF_8);
            JSONObject envelope = new JSONObject()
                    .put("envelopeVersion", ENVELOPE_VERSION)
                    .put("crc32", Long.toUnsignedString(checksum(payloadBytes)))
                    .put("payload", bounded);
            byte[] bytes = envelope.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_FILE_BYTES) {
                throw new IllegalStateException("MEDIA_COMMUNICATION_FILE_LIMIT_EXCEEDED");
            }
            File parent = file.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IllegalStateException("Cannot create media-communication directory");
            }
            File temporary = new File(parent, file.getName() + ".tmp");
            try {
                try (FileOutputStream out = new FileOutputStream(temporary)) {
                    out.write(bytes);
                    out.flush();
                    out.getFD().sync();
                }
                moveAtomically(temporary, file);
            } finally {
                if (temporary.exists() && !temporary.delete()) temporary.deleteOnExit();
            }
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("Cannot persist media-communication profile", error);
        }
    }

    void quarantine() {
        if (!file.isFile()) return;
        try {
            Files.move(
                    file.toPath(),
                    new File(file.getParentFile(), file.getName() + ".corrupt").toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
            // Best effort: the warning remains available through the owning store.
        }
    }

    private static void moveAtomically(File source, File target) throws Exception {
        try {
            Files.move(
                    source.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(
                    source.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String bounded(String value) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new IllegalStateException("MEDIA_COMMUNICATION_PAYLOAD_LIMIT_EXCEEDED");
        }
        return value;
    }

    private static IllegalStateException corrupt(String message) {
        return new IllegalStateException(message);
    }

    private static long checksum(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return crc.getValue();
    }
}
