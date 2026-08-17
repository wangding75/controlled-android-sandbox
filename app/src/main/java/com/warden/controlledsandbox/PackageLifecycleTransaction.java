package com.warden.controlledsandbox;

import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Transactional package-lifecycle record. Package revision, install revision, data revision
 * and identity generation are independent. A package-revision change does not reset guest data.
 */
final class PackageLifecycleTransaction {
    enum State {
        INSTALLED,
        UPDATING_PREPARE,
        UPDATING_SWITCH,
        ACTIVE,
        ROLLBACK_PENDING,
        DELETING,
        DELETED,
        RESETTING
    }

    final String packageName;
    final State state;
    final String currentPackageRevision;
    final String previousPackageRevision;
    final String previousApkPath;
    final long currentVersionCode;
    final long previousVersionCode;
    final long installRevision;
    final long dataRevision;
    final long identityGeneration;
    final String previousRecordJson;
    final long updatedAtMs;
    final String reason;

    PackageLifecycleTransaction(String packageName, State state, String currentPackageRevision,
                                String previousPackageRevision, String previousApkPath,
                                long currentVersionCode, long previousVersionCode,
                                long installRevision, long dataRevision, long identityGeneration,
                                String previousRecordJson, long updatedAtMs, String reason) {
        if (packageName == null || packageName.trim().isEmpty()) {
            throw new IllegalArgumentException("packageName is required");
        }
        this.packageName = packageName.trim();
        this.state = state == null ? State.ACTIVE : state;
        this.currentPackageRevision = currentPackageRevision == null ? "" : currentPackageRevision;
        this.previousPackageRevision = previousPackageRevision == null ? "" : previousPackageRevision;
        this.previousApkPath = previousApkPath == null ? "" : previousApkPath;
        this.currentVersionCode = currentVersionCode;
        this.previousVersionCode = previousVersionCode;
        this.installRevision = Math.max(1L, installRevision);
        this.dataRevision = Math.max(1L, dataRevision);
        this.identityGeneration = Math.max(1L, identityGeneration);
        this.previousRecordJson = previousRecordJson == null ? "" : previousRecordJson;
        this.updatedAtMs = updatedAtMs;
        this.reason = reason == null ? "" : reason;
    }

    static PackageLifecycleTransaction installed(SandboxRecord record, long nowMs) {
        return new PackageLifecycleTransaction(record.packageName, State.ACTIVE, record.sha256,
                "", "", record.versionCode, 0L, 1L, 1L, 1L, "", nowMs, "installed");
    }

    PackageLifecycleTransaction withState(State next, long nowMs, String nextReason) {
        return new PackageLifecycleTransaction(packageName, next, currentPackageRevision,
                previousPackageRevision, previousApkPath, currentVersionCode, previousVersionCode,
                installRevision, dataRevision, identityGeneration, previousRecordJson, nowMs,
                nextReason);
    }

    PackageLifecycleTransaction prepareUpdate(SandboxRecord current, long nowMs) throws JSONException {
        requireMutable();
        return new PackageLifecycleTransaction(packageName, State.UPDATING_PREPARE,
                current.sha256, current.sha256, current.apkPath, current.versionCode,
                current.versionCode, installRevision, dataRevision, identityGeneration,
                current.toJson().toString(), nowMs, "update-prepare");
    }

    PackageLifecycleTransaction switchUpdate(SandboxRecord imported, long nowMs) {
        if (state != State.UPDATING_PREPARE && state != State.UPDATING_SWITCH) {
            throw new IllegalStateException("LIFECYCLE_NOT_PREPARED:" + state);
        }
        return new PackageLifecycleTransaction(packageName, State.UPDATING_SWITCH,
                imported.sha256, previousPackageRevision, previousApkPath, imported.versionCode,
                previousVersionCode, installRevision + 1L, dataRevision, identityGeneration,
                previousRecordJson, nowMs, "update-switch");
    }

    PackageLifecycleTransaction activate(long nowMs, String nextReason) {
        return new PackageLifecycleTransaction(packageName, State.ACTIVE, currentPackageRevision,
                previousPackageRevision, previousApkPath, currentVersionCode, previousVersionCode,
                installRevision, dataRevision, identityGeneration, previousRecordJson, nowMs,
                nextReason);
    }

    PackageLifecycleTransaction abortToPrevious(long nowMs) {
        if (state != State.UPDATING_PREPARE && state != State.UPDATING_SWITCH
                && state != State.ROLLBACK_PENDING) {
            throw new IllegalStateException("LIFECYCLE_ABORT_INVALID:" + state);
        }
        return new PackageLifecycleTransaction(packageName, State.ACTIVE, previousPackageRevision,
                "", "", previousVersionCode, 0L, installRevision, dataRevision, identityGeneration,
                "", nowMs, "update-aborted");
    }

    PackageLifecycleTransaction beginRollback(long nowMs) {
        if (previousPackageRevision.isEmpty() || previousRecordJson.isEmpty()) {
            throw new IllegalStateException("LIFECYCLE_NO_ROLLBACK_REVISION");
        }
        return withState(State.ROLLBACK_PENDING, nowMs, "rollback-prepare");
    }

    PackageLifecycleTransaction resetIdentity(long nowMs) {
        return new PackageLifecycleTransaction(packageName, State.ACTIVE, currentPackageRevision,
                previousPackageRevision, previousApkPath, currentVersionCode, previousVersionCode,
                installRevision, dataRevision + 1L, identityGeneration + 1L, previousRecordJson,
                nowMs, "identity-reset");
    }

    boolean retainsRevision(String revisionDirCanonical) {
        return !previousApkPath.isEmpty() && previousApkPath.replace('\\', '/')
                .contains(revisionDirCanonical.replace('\\', '/'));
    }

    boolean inFlight() {
        return state == State.UPDATING_PREPARE || state == State.UPDATING_SWITCH
                || state == State.ROLLBACK_PENDING || state == State.RESETTING
                || state == State.DELETING;
    }

    void requireNotInFlight(String operation) {
        if (inFlight()) {
            throw new IllegalStateException("LIFECYCLE_IN_FLIGHT:" + state + ":" + operation);
        }
    }

    private void requireMutable() {
        if (state == State.DELETING || state == State.DELETED) {
            throw new IllegalStateException("LIFECYCLE_TERMINAL:" + state);
        }
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("packageName", packageName)
                .put("state", state.name())
                .put("currentPackageRevision", currentPackageRevision)
                .put("previousPackageRevision", previousPackageRevision)
                .put("previousApkPath", previousApkPath)
                .put("currentVersionCode", currentVersionCode)
                .put("previousVersionCode", previousVersionCode)
                .put("installRevision", installRevision)
                .put("dataRevision", dataRevision)
                .put("identityGeneration", identityGeneration)
                .put("previousRecordJson", previousRecordJson)
                .put("updatedAtMs", updatedAtMs)
                .put("reason", reason);
    }

    static PackageLifecycleTransaction fromJson(JSONObject value) throws JSONException {
        return new PackageLifecycleTransaction(
                value.getString("packageName"),
                State.valueOf(value.optString("state", State.ACTIVE.name()).toUpperCase(Locale.ROOT)),
                value.optString("currentPackageRevision"),
                value.optString("previousPackageRevision"),
                value.optString("previousApkPath"),
                value.optLong("currentVersionCode"),
                value.optLong("previousVersionCode"),
                value.optLong("installRevision", 1L),
                value.optLong("dataRevision", 1L),
                value.optLong("identityGeneration", 1L),
                value.optString("previousRecordJson"),
                value.optLong("updatedAtMs"),
                value.optString("reason"));
    }
}
