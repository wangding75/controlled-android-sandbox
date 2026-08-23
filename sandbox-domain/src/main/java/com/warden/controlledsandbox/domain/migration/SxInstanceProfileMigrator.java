package com.warden.controlledsandbox.domain.migration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Versioned, idempotent SX → CAS instance-profile migration.
 * Old source remains on disk until COMMITTED. Interrupt after backup restores the backup.
 */
public final class SxInstanceProfileMigrator {
    private final SxMigrationStore store;

    public SxInstanceProfileMigrator(SxMigrationStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public SxMigrationRecord migrate(SxLegacyConfigDocument document) {
        return migrate(document, false);
    }

    public SxMigrationRecord migrate(SxLegacyConfigDocument document, boolean abortAfterBackup) {
        Objects.requireNonNull(document, "document");
        SxMigrationRecord existing = store.read(document.packageName, document.virtualUserId);
        if (existing != null && SxMigrationRecord.COMMITTED.equals(existing.status)
                && document.sourceHash.equals(existing.sourceHash)) {
            return existing.withStatus(SxMigrationRecord.IDEMPOTENT, existing.backup,
                    existing.appliedHash, existing.mediaPath, true);
        }
        Map<String, String> backup = store.snapshotProfiles(document.packageName,
                document.virtualUserId);
        SxMigrationRecord record = new SxMigrationRecord(document.packageName,
                document.virtualUserId, SxLegacyConfigDocument.SOURCE_SCHEMA,
                SxLegacyConfigDocument.TARGET_SCHEMA, document.sourceHash, document.canonical(),
                SxMigrationRecord.BACKED_UP, backup, "", "", true);
        store.write(record);
        if (abortAfterBackup) {
            return record.withStatus(SxMigrationRecord.INTERRUPTED, backup, "", "", true);
        }
        try {
            String mediaPath = "";
            if (document.mediaBytes.length > 0) {
                mediaPath = store.writeMedia(document.packageName, document.virtualUserId,
                        document.mediaKind, document.mediaBytes);
            }
            Map<String, String> mapped = map(document, mediaPath);
            store.applyProfiles(document.packageName, document.virtualUserId, mapped);
            Map<String, String> applied = store.readApplied(document.packageName,
                    document.virtualUserId);
            String appliedHash = SxLegacyConfigDocument.sha256(applied.toString());
            SxMigrationRecord committed = record.withStatus(SxMigrationRecord.COMMITTED, backup,
                    appliedHash, mediaPath, true);
            store.write(committed);
            return committed;
        } catch (RuntimeException error) {
            rollback(document.packageName, document.virtualUserId);
            throw error;
        }
    }

    public SxMigrationRecord rollback(String packageName, int virtualUserId) {
        SxMigrationRecord current = store.read(packageName, virtualUserId);
        if (current == null) {
            return new SxMigrationRecord(packageName, virtualUserId,
                    SxLegacyConfigDocument.SOURCE_SCHEMA, SxLegacyConfigDocument.TARGET_SCHEMA,
                    "", "", SxMigrationRecord.ROLLED_BACK, Map.of(), "", "", false);
        }
        store.restoreProfiles(packageName, virtualUserId, current.backup);
        if (!current.mediaPath.isEmpty()) {
            store.deleteMedia(packageName, virtualUserId, current.mediaPath);
        }
        SxMigrationRecord rolled = current.withStatus(SxMigrationRecord.ROLLED_BACK, current.backup,
                "", "", true);
        store.write(rolled);
        return rolled;
    }

    static Map<String, String> map(SxLegacyConfigDocument document, String mediaPath) {
        Map<String, String> mapped = new LinkedHashMap<>();
        mapped.put("packageName", document.packageName);
        mapped.put("virtualUserId", Integer.toString(document.virtualUserId));
        mapped.put("displayName", document.displayName);
        mapped.put("sourceHash", document.sourceHash);
        mapped.put("targetSchema", SxLegacyConfigDocument.TARGET_SCHEMA);
        putPrefix(mapped, "location", document.location);
        putPrefix(mapped, "device", document.device);
        putPrefix(mapped, "network", document.network);
        putPrefix(mapped, "camera", document.camera);
        putPrefix(mapped, "bluetooth", document.bluetooth);
        mapped.put("camera.mediaPath", mediaPath);
        mapped.put("camera.mediaBytes", Integer.toString(document.mediaBytes.length));
        mapped.put("dropped.license", "DROP_NON_BUSINESS");
        mapped.put("dropped.time_guard", "DROP_NON_BUSINESS");
        return mapped;
    }

    private static void putPrefix(Map<String, String> target, String prefix,
            Map<String, String> source) {
        for (Map.Entry<String, String> entry : source.entrySet()) {
            target.put(prefix + "." + entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
        }
    }
}
