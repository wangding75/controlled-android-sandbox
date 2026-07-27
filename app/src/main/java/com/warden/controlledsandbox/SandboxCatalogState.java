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
    private final List<SandboxRecord> records;
    private final List<SandboxInstance> instances;
    private final List<SandboxPolicyState> policies;

    SandboxCatalogState(List<SandboxRecord> records, List<SandboxInstance> instances) {
        this(records, instances, List.of());
    }

    SandboxCatalogState(List<SandboxRecord> records, List<SandboxInstance> instances,
                        List<SandboxPolicyState> policies) {
        List<SandboxRecord> recordCopy = new ArrayList<>(required(records, "records"));
        recordCopy.sort(Comparator.comparing(record -> record.packageName));
        List<SandboxInstance> instanceCopy = new ArrayList<>(required(instances, "instances"));
        instanceCopy.sort(Comparator.comparing((SandboxInstance instance) -> instance.packageName)
                .thenComparingInt(instance -> instance.virtualUserId));
        List<SandboxPolicyState> policyCopy = new ArrayList<>(required(policies, "policies"));
        policyCopy.sort(Comparator.comparing((SandboxPolicyState policy) -> policy.packageName)
                .thenComparingInt(policy -> policy.virtualUserId));
        validate(recordCopy, instanceCopy, policyCopy);
        this.records = Collections.unmodifiableList(recordCopy);
        this.instances = Collections.unmodifiableList(instanceCopy);
        this.policies = Collections.unmodifiableList(policyCopy);
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

    SandboxCatalogState withImported(SandboxRecord imported, long nowMs) {
        if (imported == null) throw new IllegalArgumentException("imported record is required");
        List<SandboxRecord> nextRecords = records();
        nextRecords.removeIf(record -> record.packageName.equals(imported.packageName));
        nextRecords.add(imported);
        List<SandboxInstance> nextInstances = instances();
        boolean found = false;
        for (SandboxInstance instance : nextInstances) {
            if (instance.packageName.equals(imported.packageName)) { found = true; break; }
        }
        if (!found) nextInstances.add(defaultInstance(imported.packageName, nowMs));
        return new SandboxCatalogState(nextRecords, nextInstances, policies);
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
        return new SandboxCatalogState(records, next, policies);
    }

    CloneResult withClone(String packageName, long nowMs) {
        if (findRecord(packageName) == null) throw new IllegalArgumentException("Package is not installed: " + packageName);
        List<SandboxInstance> next = instances();
        int userId = SandboxInstanceRepository.nextUserId(next, packageName);
        next.add(new SandboxInstance(packageName, userId, "Clone " + userId,
                nowMs, "NOT_TESTED", 0));
        return new CloneResult(new SandboxCatalogState(records, next, policies), userId);
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
        return new SandboxCatalogState(nextRecords, nextInstances, nextPolicies);
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
        return new SandboxCatalogState(records, next, policies);
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

    SandboxCatalogState withoutPolicy(String packageName, int virtualUserId) {
        requireInstance(packageName, virtualUserId);
        List<SandboxPolicyState> next = policies();
        next.removeIf(policy -> policy.packageName.equals(packageName)
                && policy.virtualUserId == virtualUserId);
        return new SandboxCatalogState(records, instances, next);
    }

    private SandboxCatalogState withPolicy(SandboxPolicyState updated) {
        List<SandboxPolicyState> next = policies();
        next.removeIf(policy -> policy.packageName.equals(updated.packageName)
                && policy.virtualUserId == updated.virtualUserId);
        if (!updated.permissionDecisions().isEmpty() || !updated.appOpModes().isEmpty()) {
            next.add(updated);
        }
        return new SandboxCatalogState(records, instances, next);
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

    private static <T> List<T> required(List<T> values, String name) {
        if (values == null) throw new IllegalArgumentException(name + " are required");
        return values;
    }

    private static void validate(List<SandboxRecord> records, List<SandboxInstance> instances,
                                 List<SandboxPolicyState> policies) {
        Set<String> packages = new HashSet<>();
        for (SandboxRecord record : records) {
            if (record == null) throw new PersistentStateException("Catalog contains a null package");
            if (!packages.add(record.packageName)) {
                throw new PersistentStateException("Duplicate package metadata: " + record.packageName);
            }
            if (record.packageName.trim().isEmpty() || record.versionCode < 0
                    || record.signatureSha256.trim().isEmpty() || record.apkPath.trim().isEmpty()
                    || !record.sha256.matches("[0-9a-fA-F]{64}")) {
                throw new PersistentStateException("Incomplete trusted package metadata: " + record.packageName);
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
