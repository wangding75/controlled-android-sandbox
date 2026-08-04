package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.domain.persistence.DurableAtomicFile;

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
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;

/** Collision-free logical-name mapping with cross-process transactional metadata. */
final class GuestStorageNameCodec {
    static final String LEGACY_COLLISION = "LEGACY_NAME_COLLISION_AMBIGUOUS";
    static final String LEGACY_INDEX_AMBIGUOUS = "LEGACY_NAME_INDEX_AMBIGUOUS";
    static final String PHYSICAL_COLLISION = "STORAGE_NAME_PHYSICAL_COLLISION";

    private static final int MAGIC = 0x43534E52; // CSNR
    private static final int VERSION = 1;
    private static final int MAX_ENTRIES = 100_000;
    private static final int MAX_REGISTRY_BYTES = 32 * 1024 * 1024;
    private static final int MAX_LOGICAL_NAME_BYTES = 64 * 1024;
    private static final int MAX_REVERSIBLE_COMPONENT_LENGTH = 180;
    private static final String REGISTRY_NAME = ".guest-storage-name-registry";
    private static final String LOCK_NAME = REGISTRY_NAME + ".lock";
    private static final ConcurrentHashMap<String, Object> JVM_LOCKS = new ConcurrentHashMap<>();

    private final File root;
    private final File registryFile;
    private final File lockFile;
    private final Object jvmLock;

    GuestStorageNameCodec(File root) {
        try {
            this.root = root.getCanonicalFile();
        } catch (IOException error) {
            throw new IllegalStateException("GUEST_STORAGE_ROOT_INVALID", error);
        }
        if (!this.root.isDirectory() && !this.root.mkdirs() && !this.root.isDirectory()) {
            throw new IllegalStateException("Cannot create directory " + this.root);
        }
        this.registryFile = new File(this.root, REGISTRY_NAME);
        this.lockFile = new File(this.root, LOCK_NAME);
        this.jvmLock = JVM_LOCKS.computeIfAbsent(this.root.getAbsolutePath(), ignored -> new Object());
        // Validate durable metadata under the same locks used by every mutation.
        withRegistry(registry -> null);
    }

    File resolve(File parent, String namespace, String logicalName,
                 String prefix, String suffix, String... companionSuffixes) {
        String logical = validateLogicalName(logicalName);
        File canonicalParent = canonicalParent(parent);
        String normalizedPrefix = nullToEmpty(prefix);
        String normalizedSuffix = nullToEmpty(suffix);
        List<String> companions = normalizedCompanions(companionSuffixes);
        String encoded = encode(logical);
        String physicalName = normalizedPrefix + encoded + normalizedSuffix;
        String scope = scope(namespace, canonicalParent);
        Key physicalKey = new Key(scope, physicalName);
        return withRegistry(registry -> {
            rejectUnsafePriorLegacyClaim(registry, scope, logical, normalizedPrefix, normalizedSuffix);
            if (encoded.startsWith("v2h_")) {
                registry.dirty |= claim(
                        registry.physicalClaims, physicalKey, logical, PHYSICAL_COLLISION);
            }
            String legacyName = legacyPhysicalName(logical, normalizedPrefix, normalizedSuffix);
            if (legacyName != null && hasAnyArtifact(canonicalParent, legacyName, companions)) {
                if (!isProvablyUniqueLegacyLogical(logical)) {
                    throw new IllegalStateException(LEGACY_COLLISION + ":" + legacyName);
                }
                Key legacyKey = new Key(scope, legacyName);
                registry.dirty |= claim(
                        registry.legacyClaims, legacyKey, logical, LEGACY_COLLISION);
                // The migration owner must be durable before moving any data.
                persistIfDirty(registry);
                migrateArtifacts(canonicalParent, legacyName, physicalName, companions);
            }
            return new File(canonicalParent, physicalName);
        });
    }

    String[] listExisting(File parent, String namespace,
                          String prefix, String suffix, String... companionSuffixes) {
        File canonicalParent = canonicalParent(parent);
        String normalizedPrefix = nullToEmpty(prefix);
        String normalizedSuffix = nullToEmpty(suffix);
        List<String> companions = normalizedCompanions(companionSuffixes);
        String scope = scope(namespace, canonicalParent);
        return withRegistry(registry -> {
            rejectUnsafeLegacyClaimsForListing(registry, scope);
            TreeSet<String> logicalNames = new TreeSet<>();
            Set<String> mainNames = mainArtifactNames(canonicalParent, companions);
            for (String physicalName : mainNames) {
                String encoded = removeAffixes(physicalName, normalizedPrefix, normalizedSuffix);
                if (encoded == null) {
                    continue;
                }
                if (encoded.startsWith("v2_")) {
                    logicalNames.add(decodeReversible(encoded));
                    continue;
                }
                if (encoded.startsWith("v2h_")) {
                    String owner = registry.physicalClaims.get(new Key(scope, physicalName));
                    if (owner == null) {
                        throw new IllegalStateException("STORAGE_NAME_HASH_OWNER_MISSING:" + physicalName);
                    }
                    logicalNames.add(owner);
                    continue;
                }
                String logical = uniqueLegacyLogical(encoded);
                if (logical == null) {
                    throw new IllegalStateException(LEGACY_INDEX_AMBIGUOUS + ":" + physicalName);
                }
                Key legacyKey = new Key(scope, physicalName);
                registry.dirty |= claim(
                        registry.legacyClaims, legacyKey, logical, LEGACY_COLLISION);
                String targetName = normalizedPrefix + encode(logical) + normalizedSuffix;
                persistIfDirty(registry);
                migrateArtifacts(canonicalParent, physicalName, targetName, companions);
                logicalNames.add(logical);
            }
            pruneMissingHashClaims(registry, scope, canonicalParent, companions);
            return logicalNames.toArray(new String[0]);
        });
    }

    void release(File parent, String namespace, String logicalName,
                 String prefix, String suffix, String... companionSuffixes) {
        String logical = validateLogicalName(logicalName);
        File canonicalParent = canonicalParent(parent);
        String normalizedPrefix = nullToEmpty(prefix);
        String normalizedSuffix = nullToEmpty(suffix);
        List<String> companions = normalizedCompanions(companionSuffixes);
        String encoded = encode(logical);
        if (!encoded.startsWith("v2h_")) {
            return;
        }
        String physicalName = normalizedPrefix + encoded + normalizedSuffix;
        String scope = scope(namespace, canonicalParent);
        withRegistry(registry -> {
            if (!hasAnyArtifact(canonicalParent, physicalName, companions)) {
                String removed = registry.physicalClaims.remove(new Key(scope, physicalName));
                registry.dirty |= removed != null;
            }
            return null;
        });
    }


    private void rejectUnsafeLegacyClaimsForListing(Registry registry, String scope) {
        for (Map.Entry<Key, String> entry : registry.legacyClaims.entrySet()) {
            if (entry.getKey().scope.equals(scope)
                    && !isProvablyUniqueLegacyLogical(entry.getValue())) {
                throw new IllegalStateException(
                        LEGACY_INDEX_AMBIGUOUS + ":" + entry.getKey().physicalName);
            }
        }
    }

    private void rejectUnsafePriorLegacyClaim(Registry registry, String scope, String logical,
                                               String prefix, String suffix) {
        String legacyName = legacyPhysicalName(logical, prefix, suffix);
        if (legacyName == null) {
            return;
        }
        String owner = registry.legacyClaims.get(new Key(scope, legacyName));
        if (owner != null && !owner.equals(logical)) {
            throw new IllegalStateException(LEGACY_COLLISION + ":" + legacyName);
        }
        if (owner != null && !isProvablyUniqueLegacyLogical(logical)) {
            // Older builds used first-access-wins. Do not continue trusting that ambiguous claim.
            throw new IllegalStateException(LEGACY_COLLISION + ":" + legacyName);
        }
    }

    private void pruneMissingHashClaims(Registry registry, String scope, File parent,
                                        List<String> companions) {
        List<Key> remove = new ArrayList<>();
        for (Key key : registry.physicalClaims.keySet()) {
            if (key.scope.equals(scope) && !hasAnyArtifact(parent, key.physicalName, companions)) {
                remove.add(key);
            }
        }
        for (Key key : remove) {
            registry.physicalClaims.remove(key);
            registry.dirty = true;
        }
    }

    private Set<String> mainArtifactNames(File parent, List<String> companions) {
        Set<String> names = new HashSet<>();
        File[] files = parent.listFiles();
        if (files == null) {
            return names;
        }
        for (File file : files) {
            String name = file.getName();
            if (name.equals(REGISTRY_NAME) || name.equals(LOCK_NAME)
                    || name.startsWith(REGISTRY_NAME + ".tmp.")) {
                continue;
            }
            boolean companion = false;
            for (String companionSuffix : companions) {
                if (name.endsWith(companionSuffix)) {
                    companion = true;
                    break;
                }
            }
            if (!companion) {
                names.add(name);
            }
        }
        return names;
    }

    private static String removeAffixes(String value, String prefix, String suffix) {
        if (!value.startsWith(prefix) || !value.endsWith(suffix)
                || value.length() < prefix.length() + suffix.length()) {
            return null;
        }
        return value.substring(prefix.length(), value.length() - suffix.length());
    }

    private static String uniqueLegacyLogical(String legacyCore) {
        if (!isProvablyUniqueLegacyLogical(legacyCore)) {
            return null;
        }
        return legacyCore;
    }

    private static boolean isProvablyUniqueLegacyLogical(String logical) {
        if (logical == null || logical.isEmpty() || logical.equals(".") || logical.equals("..")) {
            return false;
        }
        // The old mapper replaced every unsupported character with '_'. Any underscore therefore
        // has multiple possible preimages and cannot be assigned without external migration metadata.
        return logical.matches("[A-Za-z0-9.-]+");
    }

    private static boolean claim(Map<Key, String> claims, Key key, String logical, String errorCode) {
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
            move(source, target, "LEGACY_NAME_MIGRATION_FAILED");
        }
        syncDirectory(parent);
    }

    private static void move(File source, File target, String code) {
        try {
            DurableAtomicFile.moveAcknowledged(source.toPath(), target.toPath());
        } catch (IOException error) {
            throw new IllegalStateException(code + ":" + source.getName(), error);
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
            if (!valuePath.equals(rootPath) && !valuePath.startsWith(rootPath + File.separator)) {
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

    private static String decodeReversible(String encoded) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded.substring(3));
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            String logical = decoded.toString();
            if (!encode(logical).equals(encoded)) {
                throw new IllegalArgumentException("non-canonical storage name");
            }
            return validateLogicalName(logical);
        } catch (IllegalArgumentException | CharacterCodingException error) {
            throw new IllegalStateException("STORAGE_NAME_REVERSIBLE_DECODE_FAILED:" + encoded, error);
        }
    }

    private static String legacyPhysicalName(String logical, String prefix, String suffix) {
        if (logical.trim().isEmpty()) {
            return null;
        }
        String safe = logical.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.equals(".") || safe.equals("..")) {
            return null;
        }
        return prefix + safe + suffix;
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

    private <T> T withRegistry(RegistryAction<T> action) {
        synchronized (jvmLock) {
            try (RandomAccessFile rawLock = new RandomAccessFile(lockFile, "rw");
                 FileChannel channel = rawLock.getChannel();
                 FileLock ignored = channel.lock()) {
                Registry registry = loadRegistry();
                removeObsoleteReversibleClaims(registry);
                T result = action.run(registry);
                persistIfDirty(registry);
                return result;
            } catch (IOException error) {
                throw new IllegalStateException("GUEST_STORAGE_NAME_REGISTRY_LOCK_FAILED", error);
            }
        }
    }

    private void removeObsoleteReversibleClaims(Registry registry) {
        List<Key> remove = new ArrayList<>();
        for (Map.Entry<Key, String> entry : registry.physicalClaims.entrySet()) {
            if (!encode(entry.getValue()).startsWith("v2h_")) {
                remove.add(entry.getKey());
            }
        }
        for (Key key : remove) {
            registry.physicalClaims.remove(key);
            registry.dirty = true;
        }
    }

    private Registry loadRegistry() {
        Registry registry = new Registry();
        if (!registryFile.isFile()) {
            return registry;
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
                readClaims(payload, registry);
            }
            return registry;
        } catch (IOException error) {
            throw new IllegalStateException("GUEST_STORAGE_NAME_REGISTRY_CORRUPT", error);
        }
    }

    private void readClaims(byte[] payload, Registry registry) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            readClaimMap(input, registry.physicalClaims);
            readClaimMap(input, registry.legacyClaims);
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

    private void persistIfDirty(Registry registry) {
        if (!registry.dirty) {
            return;
        }
        persist(registry);
        registry.dirty = false;
    }

    private void persist(Registry registry) {
        File temporary = new File(root, REGISTRY_NAME + ".tmp." + UUID.randomUUID());
        try {
            byte[] payload = writePayload(registry);
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
            DurableAtomicFile.replacePreparedAcknowledged(temporary.toPath(), registryFile.toPath());
        } catch (IOException error) {
            throw new IllegalStateException("GUEST_STORAGE_NAME_REGISTRY_WRITE_FAILED", error);
        } finally {
            if (temporary.exists() && !temporary.delete()) {
                temporary.deleteOnExit();
            }
        }
    }

    private byte[] writePayload(Registry registry) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            writeClaimMap(output, registry.physicalClaims);
            writeClaimMap(output, registry.legacyClaims);
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

    private static void syncDirectory(File directory) {
        try {
            DurableAtomicFile.syncDirectory(directory.toPath());
        } catch (IOException error) {
            throw new IllegalStateException("GUEST_STORAGE_DIRECTORY_FSYNC_FAILED", error);
        }
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

    private interface RegistryAction<T> {
        T run(Registry registry);
    }

    private static final class Registry {
        final Map<Key, String> physicalClaims = new HashMap<>();
        final Map<Key, String> legacyClaims = new HashMap<>();
        boolean dirty;
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
