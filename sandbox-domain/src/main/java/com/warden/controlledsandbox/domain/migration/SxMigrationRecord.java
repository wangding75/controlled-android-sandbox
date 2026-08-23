package com.warden.controlledsandbox.domain.migration;

import java.util.Map;

/** Durable migration ledger for one package/virtual-user. Old source is kept until commit. */
public final class SxMigrationRecord {
    public static final String OPEN = "OPEN";
    public static final String BACKED_UP = "BACKED_UP";
    public static final String COMMITTED = "COMMITTED";
    public static final String ROLLED_BACK = "ROLLED_BACK";
    public static final String FAILED = "FAILED";
    public static final String IDEMPOTENT = "IDEMPOTENT";
    public static final String INTERRUPTED = "INTERRUPTED";

    public final String packageName;
    public final int virtualUserId;
    public final String sourceSchema;
    public final String targetSchema;
    public final String sourceHash;
    public final String sourceCanonical;
    public final String status;
    public final Map<String, String> backup;
    public final String appliedHash;
    public final String mediaPath;
    public final boolean sourceKept;

    public SxMigrationRecord(String packageName, int virtualUserId, String sourceSchema,
            String targetSchema, String sourceHash, String sourceCanonical, String status,
            Map<String, String> backup, String appliedHash, String mediaPath, boolean sourceKept) {
        this.packageName = packageName;
        this.virtualUserId = virtualUserId;
        this.sourceSchema = sourceSchema;
        this.targetSchema = targetSchema;
        this.sourceHash = sourceHash == null ? "" : sourceHash;
        this.sourceCanonical = sourceCanonical == null ? "" : sourceCanonical;
        this.status = status == null ? OPEN : status;
        this.backup = backup == null ? Map.of() : Map.copyOf(backup);
        this.appliedHash = appliedHash == null ? "" : appliedHash;
        this.mediaPath = mediaPath == null ? "" : mediaPath;
        this.sourceKept = sourceKept;
    }

    public SxMigrationRecord withStatus(String next, Map<String, String> nextBackup,
            String nextApplied, String nextMedia, boolean kept) {
        return new SxMigrationRecord(packageName, virtualUserId, sourceSchema, targetSchema,
                sourceHash, sourceCanonical, next, nextBackup, nextApplied, nextMedia, kept);
    }
}
