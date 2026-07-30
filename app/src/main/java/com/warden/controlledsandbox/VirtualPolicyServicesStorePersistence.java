package com.warden.controlledsandbox;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.zip.CRC32;
import org.json.JSONObject;
/** Atomic bounded checksummed persistence for framework policy-service profiles. */ final class VirtualPolicyServicesStorePersistence {
    static final int ENVELOPE_VERSION = 1;
    static final int MAX_PAYLOAD_BYTES = 8 * 1024 * 1024;
    static final int MAX_FILE_BYTES = 10 * 1024 * 1024;
    private final File file;
    VirtualPolicyServicesStorePersistence(File file) {
        if (file == null) throw new IllegalArgumentException("store file is required");
        this.file = file;
    }
    String readPayload() {
        if (!file.isFile()) return null;
        try {
            if (file.length() > MAX_FILE_BYTES) throw corrupt("POLICY_SERVICES_FILE_LIMIT_EXCEEDED");
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(text);
            if (root.optInt("envelopeVersion", -1) != ENVELOPE_VERSION) {
                throw corrupt("POLICY_SERVICES_ENVELOPE_UNSUPPORTED");
            }
            String payload = bounded(root.getString("payload"));
            long expected = Long.parseUnsignedLong(root.getString("crc32"));
            long actual = checksum(payload.getBytes(StandardCharsets.UTF_8));
            if (expected != actual) throw corrupt("POLICY_SERVICES_CHECKSUM_MISMATCH");
            return payload;
        } catch (RuntimeException error) {
            quarantine();
            throw error;
        } catch (Exception error) {
            quarantine();
            throw new IllegalStateException("POLICY_SERVICES_STORE_CORRUPT", error);
        }
    }
    void writePayload(String payload) {
        try {
            String bounded = bounded(payload);
            byte[] payloadBytes = bounded.getBytes(StandardCharsets.UTF_8);
            byte[] bytes = new JSONObject().put("envelopeVersion", ENVELOPE_VERSION).put("crc32", Long.toUnsignedString(checksum(payloadBytes))).put("payload", bounded).toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_FILE_BYTES) throw new IllegalStateException("POLICY_SERVICES_FILE_LIMIT_EXCEEDED");
            File parent = file.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IllegalStateException("Cannot create policy-services directory");
            }
            File temporary = new File(parent, file.getName() + ".tmp");
            try {
                try (FileOutputStream out = new FileOutputStream(temporary)) {
                    out.write(bytes);
                    out.flush();
                    out.getFD().sync();
                }
                try {
                    Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                if (temporary.exists() && !temporary.delete()) temporary.deleteOnExit();
            }
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("Cannot persist policy-services profile", error);
        }
    }
    void quarantine() {
        if (!file.isFile()) return;
        try {
            Files.move(file.toPath(), new File(file.getParentFile(), file.getName() + ".corrupt").toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
        }
    }
    private static String bounded(String value) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new IllegalStateException("POLICY_SERVICES_PAYLOAD_LIMIT_EXCEEDED");
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
