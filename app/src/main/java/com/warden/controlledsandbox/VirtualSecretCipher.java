package com.warden.controlledsandbox;

import com.warden.controlledsandbox.domain.persistence.DurableAtomicFile;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.spec.AlgorithmParameterSpec;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** Per-install AES-GCM boundary for virtual account passwords and auth tokens at rest. */
final class VirtualSecretCipher {
    static final String PREFIX = "aesgcm:v1:";
    static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MAX_CIPHERTEXT_BYTES = 64 * 1024;
    private static final String ANDROID_KEY_ALIAS =
            "ControlledSandbox.VirtualSystemServices.v1";

    private final SecretKey key;
    private final SecretKeySpec legacyKey;
    private final File legacyKeyFile;
    private final boolean keyStoreBacked;
    private volatile boolean legacyKeyUsed;
    private final SecureRandom random = new SecureRandom();

    VirtualSecretCipher(File keyFile) {
        if (keyFile == null) throw new IllegalArgumentException("secret key file is required");
        SecretKey androidKey = loadOrCreateAndroidKey();
        this.legacyKeyFile = keyFile;
        if (androidKey != null) {
            this.key = androidKey;
            this.legacyKey = loadExistingLegacyKey(keyFile);
            this.keyStoreBacked = true;
        } else {
            this.key = new SecretKeySpec(loadOrCreateKey(keyFile), "AES");
            this.legacyKey = null;
            this.keyStoreBacked = false;
        }
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
        byte[] payload;
        try {
            payload = Base64.getUrlDecoder().decode(encoded.substring(PREFIX.length()));
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("VIRTUAL_SECRET_CIPHERTEXT_INVALID", error);
        }
        if (payload.length <= NONCE_BYTES || payload.length > MAX_CIPHERTEXT_BYTES) {
            throw new IllegalStateException("VIRTUAL_SECRET_CIPHERTEXT_INVALID");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        byte[] ciphertext = new byte[payload.length - NONCE_BYTES];
        System.arraycopy(payload, 0, nonce, 0, NONCE_BYTES);
        System.arraycopy(payload, NONCE_BYTES, ciphertext, 0, ciphertext.length);
        try {
            return decryptWithKey(safeAad, nonce, ciphertext, key);
        } catch (GeneralSecurityException | RuntimeException primaryError) {
            if (legacyKey == null) {
                throw new IllegalStateException("VIRTUAL_SECRET_DECRYPTION_FAILED", primaryError);
            }
            try {
                String result = decryptWithKey(safeAad, nonce, ciphertext, legacyKey);
                legacyKeyUsed = true;
                return result;
            } catch (GeneralSecurityException | RuntimeException legacyError) {
                legacyError.addSuppressed(primaryError);
                throw new IllegalStateException("VIRTUAL_SECRET_DECRYPTION_FAILED", legacyError);
            }
        }
    }

    boolean isKeyStoreBacked() { return keyStoreBacked; }

    boolean legacyKeyUsed() { return legacyKeyUsed; }

    boolean hasLegacyKey() { return legacyKey != null; }

    /** Deletes the legacy file key only after a successful store rewrite with the Keystore key. */
    void retireLegacyKey() {
        if (!keyStoreBacked || legacyKey == null || legacyKeyFile == null) return;
        try {
            Files.deleteIfExists(legacyKeyFile.toPath());
            legacyKeyUsed = false;
        } catch (Exception ignored) {
            // Retaining an unread legacy key is safer than deleting it before the rewrite is
            // durable. It is no longer used for encryption and will be retried next startup.
        }
    }

    private static String decryptWithKey(String associatedData, byte[] nonce, byte[] ciphertext,
                                         SecretKey decryptionKey)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, decryptionKey, new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }

    /**
     * Uses reflection so the Host-only source compiler does not need Android Keystore stubs.
     * A missing provider is a supported development fallback; on Android a new installation
     * must get the hardware/system Keystore key before a file key is created.
     */
    private static SecretKey loadOrCreateAndroidKey() {
        try {
            Class<?> properties = Class.forName("android.security.keystore.KeyProperties");
            int purposes = properties.getField("PURPOSE_ENCRYPT").getInt(null)
                    | properties.getField("PURPOSE_DECRYPT").getInt(null);
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            Key existing = store.getKey(ANDROID_KEY_ALIAS, null);
            if (existing instanceof SecretKey) return (SecretKey) existing;

            Class<?> builderType = Class.forName(
                    "android.security.keystore.KeyGenParameterSpec$Builder");
            Object builder = builderType.getConstructor(String.class, int.class)
                    .newInstance(ANDROID_KEY_ALIAS, purposes);
            builderType.getMethod("setBlockModes", String[].class)
                    .invoke(builder, (Object) new String[]{"GCM"});
            builderType.getMethod("setEncryptionPaddings", String[].class)
                    .invoke(builder, (Object) new String[]{"NoPadding"});
            try {
                builderType.getMethod("setRandomizedEncryptionRequired", boolean.class)
                        .invoke(builder, true);
            } catch (NoSuchMethodException ignored) {
                // API 23+ exposes this method; an OEM provider may omit it.
            }
            AlgorithmParameterSpec spec = (AlgorithmParameterSpec) builderType
                    .getMethod("build").invoke(builder);
            javax.crypto.KeyGenerator generator = javax.crypto.KeyGenerator
                    .getInstance("AES", "AndroidKeyStore");
            generator.init(spec);
            return generator.generateKey();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static SecretKeySpec loadExistingLegacyKey(File keyFile) {
        try {
            if (!keyFile.isFile()) return null;
            byte[] existing = Files.readAllBytes(keyFile.toPath());
            if (existing.length != KEY_BYTES) {
                quarantine(keyFile);
                return null;
            }
            return new SecretKeySpec(existing, "AES");
        } catch (Exception ignored) {
            return null;
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
                DurableAtomicFile.replacePreparedAcknowledged(temp.toPath(), keyFile.toPath());
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
