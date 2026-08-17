package com.warden.controlledsandbox;

import com.warden.controlledsandbox.domain.persistence.PersistentStateException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Immutable package/instance metadata aggregate persisted as one atomic catalog. */
final class SandboxCatalogState {
    private static final int MAX_PERMISSION_HISTORY = 4096;
    private static final int RETAIN_PERMISSION_REQUESTS = 3072;
    private final List<SandboxRecord> records;
    private final List<SandboxInstance> instances;
    private final List<SandboxPolicyState> policies;
    private final List<RuntimePermissionRequestRecord> permissionRequests;
    private final List<PermissionAuditRecord> permissionAudit;

    SandboxCatalogState(List<SandboxRecord> records, List<SandboxInstance> instances) {
        this(records, instances, List.of());
    }

    SandboxCatalogState(List<SandboxRecord> records, List<SandboxInstance> instances,
                        List<SandboxPolicyState> policies) {
        this(records, instances, policies, List.of(), List.of());
    }

    SandboxCatalogState(List<SandboxRecord> records, List<SandboxInstance> instances,
                        List<SandboxPolicyState> policies,
                        List<RuntimePermissionRequestRecord> permissionRequests,
                        List<PermissionAuditRecord> permissionAudit) {
        List<SandboxRecord> recordCopy = new ArrayList<>(required(records, "records"));
        recordCopy.sort(Comparator.comparing(record -> record.packageName));
        List<SandboxInstance> instanceCopy = new ArrayList<>(required(instances, "instances"));
        instanceCopy.sort(Comparator.comparing((SandboxInstance instance) -> instance.packageName)
                .thenComparingInt(instance -> instance.virtualUserId));
        List<SandboxPolicyState> policyCopy = new ArrayList<>(required(policies, "policies"));
        policyCopy.sort(Comparator.comparing((SandboxPolicyState policy) -> policy.packageName)
                .thenComparingInt(policy -> policy.virtualUserId));
        List<RuntimePermissionRequestRecord> requestCopy = new ArrayList<>(
                required(permissionRequests, "permissionRequests"));
        requestCopy.sort(Comparator.comparingLong(request -> request.requestId));
        List<PermissionAuditRecord> auditCopy = new ArrayList<>(
                required(permissionAudit, "permissionAudit"));
        auditCopy.sort(Comparator.comparingLong(audit -> audit.sequence));
        validate(recordCopy, instanceCopy, policyCopy, requestCopy, auditCopy);
        this.records = Collections.unmodifiableList(recordCopy);
        this.instances = Collections.unmodifiableList(instanceCopy);
        this.policies = Collections.unmodifiableList(policyCopy);
        this.permissionRequests = Collections.unmodifiableList(requestCopy);
        this.permissionAudit = Collections.unmodifiableList(auditCopy);
    }

    static SandboxCatalogState empty() { return new SandboxCatalogState(List.of(), List.of()); }

    static SandboxCatalogState normalizeLegacy(List<SandboxRecord> records,
                                               List<SandboxInstance> instances,
                                               long nowMs) {
        List<SandboxRecord> packages = new ArrayList<>(required(records, "records"));
        Set<String> names = new HashSet<>();
        for (SandboxRecord record : packages) names.add(record.packageName);
        List<SandboxInstance> normalized = new ArrayList<>();
        for (SandboxInstance instance : required(instances, "instances")) {
            if (names.contains(instance.packageName)) normalized.add(instance);
        }
        for (SandboxRecord record : packages) {
            boolean found = false;
            for (SandboxInstance instance : normalized) {
                if (record.packageName.equals(instance.packageName)) { found = true; break; }
            }
            if (!found) normalized.add(defaultInstance(record.packageName, nowMs));
        }
        return new SandboxCatalogState(packages, normalized);
    }

    List<SandboxRecord> records() { return new ArrayList<>(records); }
    List<SandboxInstance> instances() { return new ArrayList<>(instances); }
    List<SandboxPolicyState> policies() { return new ArrayList<>(policies); }
    List<RuntimePermissionRequestRecord> permissionRequests() { return new ArrayList<>(permissionRequests); }
    List<PermissionAuditRecord> permissionAudit() { return new ArrayList<>(permissionAudit); }

    SandboxPolicyState policy(String packageName, int virtualUserId) {
        requireInstance(packageName, virtualUserId);
        for (SandboxPolicyState policy : policies) {
            if (policy.packageName.equals(packageName) && policy.virtualUserId == virtualUserId) {
                return policy;
            }
        }
        return SandboxPolicyState.empty(packageName, virtualUserId);
    }

    SandboxRecord findRecord(String packageName) {
        for (SandboxRecord record : records) if (record.packageName.equals(packageName)) return record;
        return null;
    }

    SandboxCatalogState withRestoredRevision(SandboxRecord previous) {
        if (previous == null) throw new IllegalArgumentException("previous record is required");
        if (findRecord(previous.packageName) == null) {
            throw new IllegalArgumentException("Package is not installed: " + previous.packageName);
        }
        List<SandboxRecord> nextRecords = records();
        nextRecords.removeIf(record -> record.packageName.equals(previous.packageName));
        nextRecords.add(previous);
        return new SandboxCatalogState(nextRecords, instances, policies,
                permissionRequests, permissionAudit);
    }

    SandboxCatalogState withImported(SandboxRecord imported, long nowMs) {
        if (imported == null) throw new IllegalArgumentException("imported record is required");
        SandboxRecord previous = findRecord(imported.packageName);
        long firstInstallAt = previous == null ? nowMs : previous.firstInstallAt;
        SandboxRecord timedImport = imported.withInstallTimes(firstInstallAt, nowMs);
        List<SandboxRecord> nextRecords = records();
        nextRecords.removeIf(record -> record.packageName.equals(imported.packageName));
        nextRecords.add(timedImport);
        List<SandboxInstance> nextInstances = instances();
        boolean found = false;
        for (SandboxInstance instance : nextInstances) {
            if (instance.packageName.equals(imported.packageName)) { found = true; break; }
        }
        if (!found) nextInstances.add(defaultInstance(imported.packageName, nowMs));
        List<RuntimePermissionRequestRecord> nextRequests = permissionRequests();
        List<PermissionAuditRecord> nextAudit = permissionAudit();
        if (previous != null && !previous.sha256.equals(imported.sha256)) {
            for (int index = 0; index < nextRequests.size(); index++) {
                RuntimePermissionRequestRecord request = nextRequests.get(index);
                if (!request.packageName.equals(imported.packageName)
                        || !RuntimePermissionRequestRecord.PENDING.equals(request.state)) continue;
                RuntimePermissionRequestRecord cancelled = request.resolve(
                        RuntimePermissionRequestRecord.CANCELLED, false, nowMs,
                        "package revision changed");
                nextRequests.set(index, cancelled);
                nextAudit = appendAudit(nextAudit, request.packageName, request.virtualUserId,
                        request.permission, "PACKAGE_UPDATE",
                        RuntimePermissionRequestRecord.CANCELLED, "PACKAGE_SERVICE",
                        "package revision changed", request.requestId, nowMs);
            }
        }
        return new SandboxCatalogState(nextRecords, nextInstances, policies,
                nextRequests, nextAudit);
    }

    SandboxCatalogState withEnsuredInstance(String packageName, int virtualUserId, long nowMs) {
        if (findRecord(packageName) == null) {
            throw new IllegalArgumentException("Package is not installed: " + packageName);
        }
        for (SandboxInstance instance : instances) {
            if (instance.packageName.equals(packageName) && instance.virtualUserId == virtualUserId) {
                return this;
            }
        }
        if (virtualUserId < 0 || virtualUserId > 999) {
            throw new IllegalArgumentException("Virtual user id out of range: " + virtualUserId);
        }
        List<SandboxInstance> next = instances();
        String displayName = virtualUserId == 0 ? "Default" : "Clone " + virtualUserId;
        next.add(new SandboxInstance(packageName, virtualUserId, displayName,
                nowMs, "NOT_TESTED", 0));
        return new SandboxCatalogState(records, next, policies, permissionRequests, permissionAudit);
    }

    CloneResult withClone(String packageName, long nowMs) {
        if (findRecord(packageName) == null) throw new IllegalArgumentException("Package is not installed: " + packageName);
        List<SandboxInstance> next = instances();
        int userId = SandboxInstanceRepository.nextUserId(next, packageName);
        next.add(new SandboxInstance(packageName, userId, "Clone " + userId,
                nowMs, "NOT_TESTED", 0));
        return new CloneResult(new SandboxCatalogState(records, next, policies, permissionRequests, permissionAudit), userId);
    }

    SandboxCatalogState withoutInstance(String packageName, int virtualUserId) {
        List<SandboxInstance> nextInstances = instances();
        boolean removed = nextInstances.removeIf(instance -> instance.packageName.equals(packageName)
                && instance.virtualUserId == virtualUserId);
        if (!removed) throw new IllegalArgumentException("Sandbox instance does not exist");
        boolean packageStillUsed = false;
        for (SandboxInstance instance : nextInstances) {
            if (instance.packageName.equals(packageName)) { packageStillUsed = true; break; }
        }
        List<SandboxRecord> nextRecords = records();
        if (!packageStillUsed) nextRecords.removeIf(record -> record.packageName.equals(packageName));
        List<SandboxPolicyState> nextPolicies = policies();
        nextPolicies.removeIf(policy -> policy.packageName.equals(packageName)
                && policy.virtualUserId == virtualUserId);
        List<RuntimePermissionRequestRecord> nextRequests = permissionRequests();
        nextRequests.removeIf(request -> request.packageName.equals(packageName)
                && request.virtualUserId == virtualUserId);
        List<PermissionAuditRecord> nextAudit = permissionAudit();
        nextAudit.removeIf(audit -> audit.packageName.equals(packageName)
                && audit.virtualUserId == virtualUserId);
        return new SandboxCatalogState(nextRecords, nextInstances, nextPolicies, nextRequests, nextAudit);
    }

    SandboxCatalogState withInstanceStatus(String packageName, int virtualUserId,
                                           String status, long nowMs) {
        List<SandboxInstance> next = instances();
        boolean updated = false;
        for (int index = 0; index < next.size(); index++) {
            SandboxInstance instance = next.get(index);
            if (instance.packageName.equals(packageName) && instance.virtualUserId == virtualUserId) {
                next.set(index, instance.withStatus(status, nowMs));
                updated = true;
                break;
            }
        }
        if (!updated) throw new IllegalArgumentException("Sandbox instance does not exist");
        return new SandboxCatalogState(records, next, policies, permissionRequests, permissionAudit);
    }

    SandboxCatalogState withPermissionDecision(String packageName, int virtualUserId,
                                               String permission, String decision) {
        SandboxPolicyState current = policy(packageName, virtualUserId);
        SandboxPolicyState updated = current.withPermissionDecision(permission, decision);
        return withPolicy(updated);
    }

    SandboxCatalogState withAppOpMode(String packageName, int virtualUserId,
                                      String opName, String mode) {
        SandboxPolicyState current = policy(packageName, virtualUserId);
        SandboxPolicyState updated = current.withAppOpMode(opName, mode);
        return withPolicy(updated);
    }

    SandboxCatalogState withPackageState(String packageName, int virtualUserId,
                                          String state) {
        SandboxPolicyState current = policy(packageName, virtualUserId);
        return withPolicy(current.withPackageState(state));
    }

    SandboxCatalogState withComponentState(String packageName, int virtualUserId,
                                            String className, String state) {
        SandboxPolicyState current = policy(packageName, virtualUserId);
        SandboxPolicyState updated = current.withComponentState(className, state);
        return withPolicy(updated);
    }

    PermissionRequestResult withPermissionRequest(String packageName, int virtualUserId,
                                                  String permission, String appOpName,
                                                  boolean hostGranted, int requestCode,
                                                  String sessionId, long generation,
                                                  long nowMs, String actor) {
        requireInstance(packageName, virtualUserId);
        String normalizedSession = requiredText(sessionId, "sessionId");
        List<RuntimePermissionRequestRecord> nextRequests = permissionRequests();
        List<PermissionAuditRecord> nextAudit = permissionAudit();
        for (int index = 0; index < nextRequests.size(); index++) {
            RuntimePermissionRequestRecord existing = nextRequests.get(index);
            if (!existing.packageName.equals(packageName) || existing.virtualUserId != virtualUserId
                    || !existing.permission.equals(permission)
                    || !RuntimePermissionRequestRecord.PENDING.equals(existing.state)) continue;
            if (existing.sessionId.equals(normalizedSession) && existing.generation == generation
                    && existing.requestCode == requestCode) {
                return new PermissionRequestResult(this, existing);
            }
            RuntimePermissionRequestRecord cancelled = existing.resolve(
                    RuntimePermissionRequestRecord.CANCELLED, false, nowMs,
                    "superseded by another Guest generation");
            nextRequests.set(index, cancelled);
            nextAudit = appendAudit(nextAudit, existing.packageName, existing.virtualUserId,
                    existing.permission, "SUPERSEDE", RuntimePermissionRequestRecord.CANCELLED,
                    actor, "stale pending request replaced", existing.requestId, nowMs);
        }
        long requestId = 1;
        for (RuntimePermissionRequestRecord existing : nextRequests) {
            requestId = Math.max(requestId, existing.requestId + 1);
        }
        pruneResolvedPermissionHistory(nextRequests, nextAudit);
        RuntimePermissionRequestRecord request = new RuntimePermissionRequestRecord(
                requestId, packageName, virtualUserId, permission, appOpName,
                RuntimePermissionRequestRecord.PENDING, hostGranted, requestCode, normalizedSession,
                generation, nowMs, 0, "");
        nextRequests.add(request);
        nextAudit = appendAudit(nextAudit, packageName, virtualUserId, permission,
                "REQUEST", "PENDING", actor, "", requestId, nowMs);
        return new PermissionRequestResult(new SandboxCatalogState(records, instances, policies,
                nextRequests, nextAudit), request);
    }

    PermissionRequestResult withPermissionResolution(long requestId, String outcome,
                                                     boolean hostGranted, String reason,
                                                     String actor, long nowMs) {
        List<RuntimePermissionRequestRecord> nextRequests = permissionRequests();
        RuntimePermissionRequestRecord resolved = null;
        for (int index = 0; index < nextRequests.size(); index++) {
            RuntimePermissionRequestRecord current = nextRequests.get(index);
            if (current.requestId != requestId) continue;
            resolved = current.resolve(outcome, hostGranted, nowMs, reason);
            nextRequests.set(index, resolved);
            break;
        }
        if (resolved == null) throw new IllegalArgumentException("Permission request does not exist: " + requestId);
        SandboxCatalogState policyUpdated = this;
        if (RuntimePermissionRequestRecord.GRANTED.equals(resolved.state)) {
            policyUpdated = policyUpdated.withPermissionDecision(resolved.packageName, resolved.virtualUserId,
                    resolved.permission, SandboxPolicyState.PERMISSION_GRANTED);
            if (!resolved.appOpName.isEmpty()) policyUpdated = policyUpdated.withAppOpMode(
                    resolved.packageName, resolved.virtualUserId, resolved.appOpName,
                    SandboxPolicyState.APP_OP_ALLOWED);
        } else if (RuntimePermissionRequestRecord.DENIED.equals(resolved.state)) {
            policyUpdated = policyUpdated.withPermissionDecision(resolved.packageName, resolved.virtualUserId,
                    resolved.permission, SandboxPolicyState.PERMISSION_DENIED);
            if (!resolved.appOpName.isEmpty()) policyUpdated = policyUpdated.withAppOpMode(
                    resolved.packageName, resolved.virtualUserId, resolved.appOpName,
                    SandboxPolicyState.APP_OP_IGNORED);
        }
        List<PermissionAuditRecord> nextAudit = appendAudit(policyUpdated.permissionAudit(),
                resolved.packageName, resolved.virtualUserId, resolved.permission, "RESOLVE",
                resolved.state, actor, reason, resolved.requestId, nowMs);
        return new PermissionRequestResult(new SandboxCatalogState(policyUpdated.records,
                policyUpdated.instances, policyUpdated.policies, nextRequests, nextAudit), resolved);
    }

    SandboxCatalogState withPermissionRevocation(String packageName, int virtualUserId,
                                                 String permission, String appOpName,
                                                 String reason, String actor, long nowMs) {
        SandboxCatalogState updated = withPermissionDecision(packageName, virtualUserId,
                permission, SandboxPolicyState.PERMISSION_DENIED);
        if (appOpName != null && !appOpName.trim().isEmpty()) {
            updated = updated.withAppOpMode(packageName, virtualUserId, appOpName,
                    SandboxPolicyState.APP_OP_IGNORED);
        }
        List<RuntimePermissionRequestRecord> nextRequests = updated.permissionRequests();
        for (int index = 0; index < nextRequests.size(); index++) {
            RuntimePermissionRequestRecord current = nextRequests.get(index);
            if (current.packageName.equals(packageName) && current.virtualUserId == virtualUserId
                    && current.permission.equals(permission)
                    && RuntimePermissionRequestRecord.PENDING.equals(current.state)) {
                nextRequests.set(index, current.resolve(RuntimePermissionRequestRecord.CANCELLED,
                        false, nowMs, "revoked"));
            }
        }
        List<PermissionAuditRecord> nextAudit = appendAudit(updated.permissionAudit(), packageName,
                virtualUserId, permission, "REVOKE", "DENIED", actor, reason, 0, nowMs);
        return new SandboxCatalogState(updated.records, updated.instances, updated.policies,
                nextRequests, nextAudit);
    }

    List<RuntimePermissionRequestRecord> pendingPermissionRequests(String packageName, int virtualUserId) {
        requireInstance(packageName, virtualUserId);
        List<RuntimePermissionRequestRecord> output = new ArrayList<>();
        for (RuntimePermissionRequestRecord request : permissionRequests) {
            if (request.packageName.equals(packageName) && request.virtualUserId == virtualUserId
                    && RuntimePermissionRequestRecord.PENDING.equals(request.state)) output.add(request);
        }
        return output;
    }

    RuntimePermissionRequestRecord permissionRequest(long requestId) {
        for (RuntimePermissionRequestRecord request : permissionRequests) {
            if (request.requestId == requestId) return request;
        }
        return null;
    }

    RuntimePermissionRequestRecord pendingPermissionRequest(String packageName, int virtualUserId,
                                                            String permission, int requestCode,
                                                            String sessionId, long generation) {
        for (RuntimePermissionRequestRecord request : permissionRequests) {
            if (request.packageName.equals(packageName) && request.virtualUserId == virtualUserId
                    && request.permission.equals(permission) && request.requestCode == requestCode
                    && request.sessionId.equals(sessionId) && request.generation == generation
                    && RuntimePermissionRequestRecord.PENDING.equals(request.state)) return request;
        }
        return null;
    }

    String latestPermissionRequestState(String packageName, int virtualUserId, String permission) {
        RuntimePermissionRequestRecord latest = null;
        for (RuntimePermissionRequestRecord request : permissionRequests) {
            if (request.packageName.equals(packageName) && request.virtualUserId == virtualUserId
                    && request.permission.equals(permission)
                    && (latest == null || request.requestId > latest.requestId)) latest = request;
        }
        return latest == null ? "NONE" : latest.state;
    }

    List<PermissionAuditRecord> permissionAudit(String packageName, int virtualUserId, int limit) {
        requireInstance(packageName, virtualUserId);
        if (limit < 1 || limit > 256) throw new IllegalArgumentException("audit limit out of range");
        List<PermissionAuditRecord> output = new ArrayList<>();
        for (int index = permissionAudit.size() - 1; index >= 0 && output.size() < limit; index--) {
            PermissionAuditRecord audit = permissionAudit.get(index);
            if (audit.packageName.equals(packageName) && audit.virtualUserId == virtualUserId) output.add(audit);
        }
        return output;
    }

    private static List<PermissionAuditRecord> appendAudit(List<PermissionAuditRecord> existing,
                                                            String packageName, int virtualUserId,
                                                            String permission, String action,
                                                            String outcome, String actor, String reason,
                                                            long requestId, long nowMs) {
        long sequence = 1;
        for (PermissionAuditRecord audit : existing) sequence = Math.max(sequence, audit.sequence + 1);
        existing.add(new PermissionAuditRecord(sequence, nowMs, packageName, virtualUserId,
                permission, action, outcome, actor, reason, requestId));
        while (existing.size() > MAX_PERMISSION_HISTORY) existing.remove(0);
        return existing;
    }

    SandboxCatalogState withPolicyReset(String packageName, int virtualUserId,
                                        String reason, String actor, long nowMs) {
        requireInstance(packageName, virtualUserId);
        SandboxPolicyState currentPolicy = policy(packageName, virtualUserId);
        List<SandboxPolicyState> nextPolicies = policies();
        nextPolicies.removeIf(policy -> policy.packageName.equals(packageName)
                && policy.virtualUserId == virtualUserId);
        List<RuntimePermissionRequestRecord> nextRequests = permissionRequests();
        List<PermissionAuditRecord> nextAudit = permissionAudit();
        Set<String> auditedPermissions = new HashSet<>();
        for (int index = 0; index < nextRequests.size(); index++) {
            RuntimePermissionRequestRecord current = nextRequests.get(index);
            if (!current.packageName.equals(packageName) || current.virtualUserId != virtualUserId
                    || !RuntimePermissionRequestRecord.PENDING.equals(current.state)) continue;
            RuntimePermissionRequestRecord cancelled = current.resolve(
                    RuntimePermissionRequestRecord.CANCELLED, false, nowMs, "policy reset");
            nextRequests.set(index, cancelled);
            nextAudit = appendAudit(nextAudit, packageName, virtualUserId, current.permission,
                    "RESET", RuntimePermissionRequestRecord.CANCELLED, actor, reason,
                    current.requestId, nowMs);
            auditedPermissions.add(current.permission);
        }
        for (String permission : currentPolicy.permissionDecisions().keySet()) {
            if (!auditedPermissions.add(permission)) continue;
            nextAudit = appendAudit(nextAudit, packageName, virtualUserId, permission,
                    "RESET", SandboxPolicyState.PERMISSION_DEFAULT, actor, reason, 0, nowMs);
        }
        for (String appOpName : currentPolicy.appOpModes().keySet()) {
            nextAudit = appendAudit(nextAudit, packageName, virtualUserId, appOpName,
                    "RESET_APP_OP", SandboxPolicyState.APP_OP_DEFAULT, actor, reason, 0, nowMs);
        }
        return new SandboxCatalogState(records, instances, nextPolicies, nextRequests, nextAudit);
    }

    private static void pruneResolvedPermissionHistory(
            List<RuntimePermissionRequestRecord> requests, List<PermissionAuditRecord> audit) {
        if (requests.size() < MAX_PERMISSION_HISTORY) return;
        Set<Long> removed = new HashSet<>();
        for (int index = 0; index < requests.size()
                && requests.size() >= RETAIN_PERMISSION_REQUESTS;) {
            RuntimePermissionRequestRecord candidate = requests.get(index);
            if (RuntimePermissionRequestRecord.PENDING.equals(candidate.state)) {
                index++;
                continue;
            }
            removed.add(candidate.requestId);
            requests.remove(index);
        }
        if (requests.size() >= MAX_PERMISSION_HISTORY) {
            throw new PersistentStateException("Too many pending permission requests");
        }
        if (!removed.isEmpty()) audit.removeIf(item -> removed.contains(item.requestId));
    }

    SandboxCatalogState withoutPolicy(String packageName, int virtualUserId) {
        requireInstance(packageName, virtualUserId);
        List<SandboxPolicyState> next = policies();
        next.removeIf(policy -> policy.packageName.equals(packageName)
                && policy.virtualUserId == virtualUserId);
        return new SandboxCatalogState(records, instances, next, permissionRequests, permissionAudit);
    }

    private SandboxCatalogState withPolicy(SandboxPolicyState updated) {
        List<SandboxPolicyState> next = policies();
        next.removeIf(policy -> policy.packageName.equals(updated.packageName)
                && policy.virtualUserId == updated.virtualUserId);
        if (!updated.permissionDecisions().isEmpty() || !updated.appOpModes().isEmpty()
                || !SandboxPolicyState.COMPONENT_DEFAULT.equals(updated.packageState())
                || !updated.componentStates().isEmpty()) {
            next.add(updated);
        }
        return new SandboxCatalogState(records, instances, next, permissionRequests, permissionAudit);
    }

    private void requireInstance(String packageName, int virtualUserId) {
        for (SandboxInstance instance : instances) {
            if (instance.packageName.equals(packageName) && instance.virtualUserId == virtualUserId) return;
        }
        throw new IllegalArgumentException("Sandbox instance does not exist: "
                + packageName + " user=" + virtualUserId);
    }

    private static SandboxInstance defaultInstance(String packageName, long nowMs) {
        return new SandboxInstance(packageName, 0, "Default", nowMs, "NOT_TESTED", 0);
    }

    private static String requiredText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private static <T> List<T> required(List<T> values, String name) {
        if (values == null) throw new IllegalArgumentException(name + " are required");
        return values;
    }

    private static void validate(List<SandboxRecord> records, List<SandboxInstance> instances,
                                 List<SandboxPolicyState> policies,
                                 List<RuntimePermissionRequestRecord> permissionRequests,
                                 List<PermissionAuditRecord> permissionAudit) {
        Set<String> packages = new HashSet<>();
        for (SandboxRecord record : records) {
            if (record == null) throw new PersistentStateException("Catalog contains a null package");
            if (!packages.add(record.packageName)) {
                throw new PersistentStateException("Duplicate package metadata: " + record.packageName);
            }
            if (record.packageName.trim().isEmpty() || record.versionCode < 0
                    || record.signatureSha256.trim().isEmpty() || record.apkPath.trim().isEmpty()
                    || !record.sha256.matches("[0-9a-fA-F]{64}")
                    || !record.baseApkSha256.matches("[0-9a-fA-F]{64}")
                    || record.artifacts.isEmpty()) {
                throw new PersistentStateException("Incomplete trusted package metadata: " + record.packageName);
            }
            int baseArtifacts = 0;
            Set<String> splitNames = new HashSet<>();
            for (PackageArtifactRecord artifact : record.artifacts) {
                if (artifact.base()) baseArtifacts++;
                else if (!splitNames.add(artifact.splitName)) {
                    throw new PersistentStateException("Duplicate split metadata: " + artifact.splitName);
                }
            }
            if (baseArtifacts != 1) {
                throw new PersistentStateException("Package must contain exactly one base artifact: " + record.packageName);
            }
        }
        Set<String> instanceKeys = new HashSet<>();
        for (SandboxInstance instance : instances) {
            if (instance == null) throw new PersistentStateException("Catalog contains a null instance");
            if (!packages.contains(instance.packageName)) {
                throw new PersistentStateException("Instance references missing package: " + instance.packageName);
            }
            String key = instance.packageName + "#" + instance.virtualUserId;
            if (!instanceKeys.add(key)) throw new PersistentStateException("Duplicate sandbox instance: " + key);
            if (instance.virtualUserId < 0 || instance.virtualUserId > 999) {
                throw new PersistentStateException("Virtual user id out of range: " + key);
            }
        }
        Set<String> policyKeys = new HashSet<>();
        for (SandboxPolicyState policy : policies) {
            if (policy == null) throw new PersistentStateException("Catalog contains a null policy");
            String key = policy.packageName + "#" + policy.virtualUserId;
            if (!instanceKeys.contains(key)) {
                throw new PersistentStateException("Policy references missing instance: " + key);
            }
            if (!policyKeys.add(key)) {
                throw new PersistentStateException("Duplicate sandbox policy: " + key);
            }
        }
        Set<Long> requestIds = new HashSet<>();
        Set<String> pendingKeys = new HashSet<>();
        if (permissionRequests.size() > MAX_PERMISSION_HISTORY) {
            throw new PersistentStateException("Too many permission requests");
        }
        for (RuntimePermissionRequestRecord request : permissionRequests) {
            if (request == null) throw new PersistentStateException("Catalog contains a null permission request");
            String owner = request.packageName + "#" + request.virtualUserId;
            if (!instanceKeys.contains(owner)) {
                throw new PersistentStateException("Permission request references missing instance: " + owner);
            }
            if (!requestIds.add(request.requestId)) {
                throw new PersistentStateException("Duplicate permission request id: " + request.requestId);
            }
            if (RuntimePermissionRequestRecord.PENDING.equals(request.state)) {
                String pendingKey = owner + "#" + request.permission;
                if (!pendingKeys.add(pendingKey)) {
                    throw new PersistentStateException("Duplicate pending permission request: " + pendingKey);
                }
            }
        }
        Set<Long> auditSequences = new HashSet<>();
        if (permissionAudit.size() > MAX_PERMISSION_HISTORY) {
            throw new PersistentStateException("Too many permission audit records");
        }
        for (PermissionAuditRecord audit : permissionAudit) {
            if (audit == null) throw new PersistentStateException("Catalog contains a null permission audit");
            String owner = audit.packageName + "#" + audit.virtualUserId;
            if (!instanceKeys.contains(owner)) {
                throw new PersistentStateException("Permission audit references missing instance: " + owner);
            }
            if (!auditSequences.add(audit.sequence)) {
                throw new PersistentStateException("Duplicate permission audit sequence: " + audit.sequence);
            }
            if (audit.requestId > 0 && !requestIds.contains(audit.requestId)) {
                throw new PersistentStateException("Permission audit references missing request: " + audit.requestId);
            }
        }
    }

    static final class PermissionRequestResult {
        final SandboxCatalogState state;
        final RuntimePermissionRequestRecord request;
        PermissionRequestResult(SandboxCatalogState state, RuntimePermissionRequestRecord request) {
            this.state = state; this.request = request;
        }
    }

    static final class CloneResult {
        final SandboxCatalogState state;
        final int virtualUserId;
        CloneResult(SandboxCatalogState state, int virtualUserId) {
            this.state = state;
            this.virtualUserId = virtualUserId;
        }
    }
}
