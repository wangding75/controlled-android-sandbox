package com.warden.controlledsandbox;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Per-install AES-GCM boundary for virtual account passwords and auth tokens at rest. */
final class VirtualSecretCipher {
    static final String PREFIX = "aesgcm:v1:";
    static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MAX_CIPHERTEXT_BYTES = 64 * 1024;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    VirtualSecretCipher(File keyFile) {
        if (keyFile == null) throw new IllegalArgumentException("secret key file is required");
        this.key = new SecretKeySpec(loadOrCreateKey(keyFile), "AES");
    }

    String encrypt(String associatedData, String plaintext) {
        String safeAad = required(associatedData, "associatedData");
        String safePlaintext = plaintext == null ? "" : plaintext;
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(safeAad.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(safePlaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, payload, 0, nonce.length);
            System.arraycopy(encrypted, 0, payload, nonce.length, encrypted.length);
            if (payload.length > MAX_CIPHERTEXT_BYTES) {
                throw new IllegalArgumentException("virtual secret ciphertext exceeds limit");
            }
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("VIRTUAL_SECRET_ENCRYPTION_FAILED", error);
        }
    }

    String decrypt(String associatedData, String encoded) {
        String safeAad = required(associatedData, "associatedData");
        if (encoded == null || !encoded.startsWith(PREFIX)) {
            throw new IllegalStateException("VIRTUAL_SECRET_CIPHERTEXT_INVALID");
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(encoded.substring(PREFIX.length()));
            if (payload.length <= NONCE_BYTES || payload.length > MAX_CIPHERTEXT_BYTES) {
                throw new IllegalStateException("VIRTUAL_SECRET_CIPHERTEXT_INVALID");
            }
            byte[] nonce = new byte[NONCE_BYTES];
            byte[] ciphertext = new byte[payload.length - NONCE_BYTES];
            System.arraycopy(payload, 0, nonce, 0, NONCE_BYTES);
            System.arraycopy(payload, NONCE_BYTES, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(safeAad.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | GeneralSecurityException error) {
            throw new IllegalStateException("VIRTUAL_SECRET_DECRYPTION_FAILED", error);
        }
    }

    private static byte[] loadOrCreateKey(File keyFile) {
        try {
            if (keyFile.isFile()) {
                byte[] existing = Files.readAllBytes(keyFile.toPath());
                if (existing.length != KEY_BYTES) {
                    quarantine(keyFile);
                    throw new IllegalStateException("VIRTUAL_SECRET_KEY_INVALID");
                }
                return existing;
            }
            File parent = keyFile.getParentFile();
            if (parent == null || (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory())) {
                throw new IllegalStateException("Cannot create virtual secret key directory");
            }
            byte[] generated = new byte[KEY_BYTES];
            new SecureRandom().nextBytes(generated);
            File temp = new File(parent, keyFile.getName() + ".tmp");
            try {
                try (FileOutputStream output = new FileOutputStream(temp)) {
                    output.write(generated);
                    output.flush();
                    output.getFD().sync();
                }
                restrictOwnerOnly(temp);
                try {
                    Files.move(temp.toPath(), keyFile.toPath(), StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException error) {
                    Files.move(temp.toPath(), keyFile.toPath());
                }
                restrictOwnerOnly(keyFile);
            } finally {
                if (temp.exists() && !temp.delete()) temp.deleteOnExit();
            }
            return generated;
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("Cannot initialize virtual secret key", error);
        }
    }

    private static void restrictOwnerOnly(File file) {
        file.setReadable(false, false);
        file.setWritable(false, false);
        file.setExecutable(false, false);
        file.setReadable(true, true);
        file.setWritable(true, true);
    }

    private static void quarantine(File keyFile) {
        try {
            Files.move(keyFile.toPath(), new File(keyFile.getParentFile(),
                    keyFile.getName() + ".corrupt").toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) { }
    }

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
}
