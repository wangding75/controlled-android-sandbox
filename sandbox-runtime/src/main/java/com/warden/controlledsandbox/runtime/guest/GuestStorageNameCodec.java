package com.warden.controlledsandbox.runtime.guest;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.CRC32;

/** Collision-free logical-name to Guest-storage path mapping with fail-closed legacy migration. */
final class GuestStorageNameCodec {
    static final String LEGACY_COLLISION = "LEGACY_NAME_COLLISION_AMBIGUOUS";
    static final String PHYSICAL_COLLISION = "STORAGE_NAME_PHYSICAL_COLLISION";

    private static final int MAGIC = 0x43534E52; // CSNR
    private static final int VERSION = 1;
    private static final int MAX_ENTRIES = 100_000;
    private static final int MAX_REGISTRY_BYTES = 32 * 1024 * 1024;
    private static final int MAX_LOGICAL_NAME_BYTES = 64 * 1024;
    private static final int MAX_REVERSIBLE_COMPONENT_LENGTH = 180;
    private static final String REGISTRY_NAME = ".guest-storage-name-registry";

    private final File root;
    private final File registryFile;
    private final Map<Key, String> physicalClaims = new HashMap<>();
    private final Map<Key, String> legacyClaims = new HashMap<>();

    GuestStorageNameCodec(File root) {
        try {
            this.root = root.getCanonicalFile();
        } catch (IOException error) {
            throw new IllegalStateException("GUEST_STORAGE_ROOT_INVALID", error);
        }
        this.registryFile = new File(this.root, REGISTRY_NAME);
        load();
    }

    synchronized File resolve(File parent, String namespace, String logicalName,
                              String prefix, String suffix, String... companionSuffixes) {
        String logical = validateLogicalName(logicalName);
        File canonicalParent = canonicalParent(parent);
        String scope = scope(namespace, canonicalParent);
        String physicalName = nullToEmpty(prefix) + encode(logical) + nullToEmpty(suffix);
        Key physicalKey = new Key(scope, physicalName);
        boolean physicalAdded = claim(physicalClaims, physicalKey, logical, PHYSICAL_COLLISION);
        boolean legacyAdded = false;
        boolean registryPersisted = false;
        Key legacyKey = null;
        try {
            boolean changed = physicalAdded;
            String legacyName = legacyPhysicalName(logical, prefix, suffix);
            List<String> companions = normalizedCompanions(companionSuffixes);
            if (legacyName != null) {
                legacyKey = new Key(scope, legacyName);
                String legacyOwner = legacyClaims.get(legacyKey);
                if (legacyOwner != null && !legacyOwner.equals(logical)) {
                    throw new IllegalStateException(LEGACY_COLLISION + ":" + legacyName);
                }
                if (hasAnyArtifact(canonicalParent, legacyName, companions)) {
                    if (legacyOwner == null) {
                        legacyClaims.put(legacyKey, logical);
                        legacyAdded = true;
                        changed = true;
                    }
                    // Persist the unique legacy claim before moving any artifact. A crash can be
                    // retried idempotently, while a later colliding logical name remains fail-closed.
                    if (changed) {
                        persist();
                        registryPersisted = true;
                        changed = false;
                    }
                    migrateArtifacts(canonicalParent, legacyName, physicalName, companions);
                }
            }
            if (changed) {
                persist();
                registryPersisted = true;
            }
            return new File(canonicalParent, physicalName);
        } catch (RuntimeException error) {
            // If the registry update itself failed, restore the in-memory view to the last durable
            // state. Once a claim was durably written, keep it across migration failures so retry
            // remains idempotent and colliding legacy names remain blocked.
            if (!registryPersisted) {
                if (physicalAdded) physicalClaims.remove(physicalKey);
                if (legacyAdded && legacyKey != null) legacyClaims.remove(legacyKey);
            }
            throw error;
        }
    }

    synchronized String[] listExisting(File parent, String namespace) {
        File canonicalParent = canonicalParent(parent);
        String scope = scope(namespace, canonicalParent);
        List<String> logicalNames = new ArrayList<>();
        for (Map.Entry<Key, String> entry : physicalClaims.entrySet()) {
            if (!entry.getKey().scope.equals(scope)) {
                continue;
            }
            if (new File(canonicalParent, entry.getKey().physicalName).exists()) {
                logicalNames.add(entry.getValue());
            }
        }
        Collections.sort(logicalNames);
        return logicalNames.toArray(new String[0]);
    }

    private boolean claim(Map<Key, String> claims, Key key, String logical, String errorCode) {
        String owner = claims.get(key);
        if (owner != null && !owner.equals(logical)) {
            throw new IllegalStateException(errorCode + ":" + key.physicalName);
        }
        if (owner == null) {
            claims.put(key, logical);
            return true;
        }
        return false;
    }

    private void migrateArtifacts(File parent, String legacyName, String physicalName,
                                  List<String> companionSuffixes) {
        List<String> ordered = new ArrayList<>(companionSuffixes);
        ordered.add(""); // Move the main artifact last.
        for (String companion : ordered) {
            File source = new File(parent, legacyName + companion);
            File target = new File(parent, physicalName + companion);
            if (!source.exists()) {
                continue;
            }
            if (target.exists()) {
                throw new IllegalStateException("LEGACY_AND_V2_BOTH_EXIST:" + source.getName());
            }
            try {
                try {
                    Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(source.toPath(), target.toPath());
                }
            } catch (IOException error) {
                throw new IllegalStateException("LEGACY_NAME_MIGRATION_FAILED:" + source.getName(), error);
            }
        }
    }

    private boolean hasAnyArtifact(File parent, String baseName, List<String> companions) {
        if (new File(parent, baseName).exists()) {
            return true;
        }
        for (String companion : companions) {
            if (new File(parent, baseName + companion).exists()) {
                return true;
            }
        }
        return false;
    }

    private File canonicalParent(File parent) {
        try {
            File value = parent.getCanonicalFile();
            String rootPath = root.getPath();
            String valuePath = value.getPath();
            if (!valuePath.equals(rootPath)
                    && !valuePath.startsWith(rootPath + File.separator)) {
                throw new SecurityException("GUEST_STORAGE_PARENT_OUTSIDE_INSTANCE");
            }
            if (!value.isDirectory() && !value.mkdirs() && !value.isDirectory()) {
                throw new IllegalStateException("Cannot create directory " + value);
            }
            return value;
        } catch (IOException error) {
            throw new IllegalStateException("GUEST_STORAGE_PARENT_INVALID", error);
        }
    }

    private String scope(String namespace, File parent) {
        if (namespace == null || namespace.isEmpty()) {
            throw new IllegalArgumentException("namespace is required");
        }
        String relative = root.toPath().relativize(parent.toPath()).toString();
        return namespace + "\n" + relative;
    }

    private static String validateLogicalName(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("name is required");
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_LOGICAL_NAME_BYTES) {
            throw new IllegalArgumentException("name is too long");
        }
        return value;
    }

    private static String encode(String logical) {
        byte[] bytes = logical.getBytes(StandardCharsets.UTF_8);
        String reversible = "v2_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        if (reversible.length() <= MAX_REVERSIBLE_COMPONENT_LENGTH) {
            return reversible;
        }
        return "v2h_" + hex(sha256(bytes));
    }

    private static String legacyPhysicalName(String logical, String prefix, String suffix) {
        if (logical.trim().isEmpty()) {
            return null;
        }
        String safe = logical.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.equals(".") || safe.equals("..")) {
            return null;
        }
        return nullToEmpty(prefix) + safe + nullToEmpty(suffix);
    }

    private static List<String> normalizedCompanions(String[] values) {
        if (values == null || values.length == 0) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value == null || value.isEmpty() || result.contains(value)) {
                continue;
            }
            result.add(value);
        }
        return result;
    }

    private void load() {
        if (!registryFile.isFile()) {
            return;
        }
        try {
            long length = registryFile.length();
            if (length <= 0L || length > MAX_REGISTRY_BYTES) {
                throw new IOException("invalid registry length");
            }
            try (DataInputStream input = new DataInputStream(
                    new BufferedInputStream(new FileInputStream(registryFile)))) {
                if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                    throw new IOException("unsupported registry header");
                }
                int payloadLength = input.readInt();
                if (payloadLength < 0 || payloadLength > MAX_REGISTRY_BYTES) {
                    throw new IOException("invalid registry payload length");
                }
                byte[] payload = new byte[payloadLength];
                input.readFully(payload);
                long expectedCrc = input.readLong();
                if (input.read() != -1 || crc32(payload) != expectedCrc) {
                    throw new IOException("registry checksum mismatch");
                }
                readClaims(payload);
            }
        } catch (IOException error) {
            throw new IllegalStateException("GUEST_STORAGE_NAME_REGISTRY_CORRUPT", error);
        }
    }

    private void readClaims(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            readClaimMap(input, physicalClaims);
            readClaimMap(input, legacyClaims);
            if (input.read() != -1) {
                throw new IOException("trailing registry payload");
            }
        }
    }

    private static void readClaimMap(DataInputStream input, Map<Key, String> target)
            throws IOException {
        int count = input.readInt();
        if (count < 0 || count > MAX_ENTRIES) {
            throw new IOException("invalid registry entry count");
        }
        for (int index = 0; index < count; index++) {
            Key key = new Key(readString(input), readString(input));
            String logical = readString(input);
            String previous = target.put(key, logical);
            if (previous != null && !previous.equals(logical)) {
                throw new IOException("duplicate registry claim");
            }
        }
    }

    private void persist() {
        try {
            byte[] payload = writePayload();
            File temporary = new File(root, REGISTRY_NAME + ".tmp");
            try (FileOutputStream raw = new FileOutputStream(temporary);
                 DataOutputStream output = new DataOutputStream(new BufferedOutputStream(raw))) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                output.writeInt(payload.length);
                output.write(payload);
                output.writeLong(crc32(payload));
                output.flush();
                raw.getFD().sync();
            }
            try {
                Files.move(temporary.toPath(), registryFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary.toPath(), registryFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new IllegalStateException("GUEST_STORAGE_NAME_REGISTRY_WRITE_FAILED", error);
        }
    }

    private byte[] writePayload() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            writeClaimMap(output, physicalClaims);
            writeClaimMap(output, legacyClaims);
        }
        byte[] payload = bytes.toByteArray();
        if (payload.length > MAX_REGISTRY_BYTES) {
            throw new IOException("registry is too large");
        }
        return payload;
    }

    private static void writeClaimMap(DataOutputStream output, Map<Key, String> claims)
            throws IOException {
        if (claims.size() > MAX_ENTRIES) {
            throw new IOException("too many registry entries");
        }
        List<Map.Entry<Key, String>> entries = new ArrayList<>(claims.entrySet());
        entries.sort(Comparator.comparing((Map.Entry<Key, String> entry) -> entry.getKey().scope)
                .thenComparing(entry -> entry.getKey().physicalName));
        output.writeInt(entries.size());
        for (Map.Entry<Key, String> entry : entries) {
            writeString(output, entry.getKey().scope);
            writeString(output, entry.getKey().physicalName);
            writeString(output, entry.getValue());
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_LOGICAL_NAME_BYTES * 2) {
            throw new EOFException("invalid registry string length");
        }
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static long crc32(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return crc.getValue();
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) {
            value.append(Character.forDigit((item >>> 4) & 0xf, 16));
            value.append(Character.forDigit(item & 0xf, 16));
        }
        return value.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static final class Key {
        final String scope;
        final String physicalName;

        Key(String scope, String physicalName) {
            this.scope = scope;
            this.physicalName = physicalName;
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return scope.equals(key.scope) && physicalName.equals(key.physicalName);
        }

        @Override public int hashCode() {
            return Objects.hash(scope, physicalName);
        }
    }
}
