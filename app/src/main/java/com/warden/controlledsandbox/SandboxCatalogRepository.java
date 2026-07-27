package com.warden.controlledsandbox;

import android.content.Context;
import com.warden.controlledsandbox.domain.persistence.RecoverableFileStore;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Single atomic metadata authority for installed packages and virtual instances. */
final class SandboxCatalogRepository {
    private static final int SCHEMA_VERSION = 4;
    private final RecoverableFileStore store;
    private final SandboxRepository legacyPackages;
    private final SandboxInstanceRepository legacyInstances;
    private final PackageStorageLayout storageLayout;
    private final LegacyPackageLayoutMigrator legacyLayoutMigrator;

    SandboxCatalogRepository(Context context) {
        File filesDir = context.getFilesDir();
        File base = new File(filesDir, "sandbox-catalog.json");
        store = new RecoverableFileStore(base.toPath());
        legacyPackages = new SandboxRepository(context);
        legacyInstances = new SandboxInstanceRepository(context);
        storageLayout = new PackageStorageLayout(filesDir);
        legacyLayoutMigrator = new LegacyPackageLayoutMigrator(storageLayout);
    }

    synchronized SandboxCatalogState load() throws Exception {
        boolean catalogExists = Files.isRegularFile(store.primary()) || Files.isRegularFile(store.backup());
        if (catalogExists) {
            SandboxCatalogState state = store.read(
                    SandboxCatalogRepository::decode, SandboxCatalogState.empty());
            storageLayout.requireCatalogLayout(state);
            return state;
        }
        SandboxCatalogState legacy = SandboxCatalogState.normalizeLegacy(
                legacyPackages.load(), legacyInstances.load(), System.currentTimeMillis());
        SandboxCatalogState migrated = legacyLayoutMigrator.migrate(legacy);
        if (!migrated.records().isEmpty() || !migrated.instances().isEmpty()) save(migrated);
        return migrated;
    }

    synchronized void save(SandboxCatalogState state) throws Exception {
        if (state == null) throw new IllegalArgumentException("catalog state is required");
        storageLayout.requireCatalogLayout(state);
        JSONObject root = new JSONObject();
        root.put("schemaVersion", SCHEMA_VERSION);
        JSONArray packages = new JSONArray();
        for (SandboxRecord record : state.records()) packages.put(record.toJson());
        JSONArray instances = new JSONArray();
        for (SandboxInstance instance : state.instances()) instances.put(instance.toJson());
        JSONArray policies = new JSONArray();
        for (SandboxPolicyState policy : state.policies()) policies.put(policy.toJson());
        JSONArray permissionRequests = new JSONArray();
        for (RuntimePermissionRequestRecord request : state.permissionRequests()) {
            permissionRequests.put(request.toJson());
        }
        JSONArray permissionAudit = new JSONArray();
        for (PermissionAuditRecord audit : state.permissionAudit()) permissionAudit.put(audit.toJson());
        root.put("packages", packages);
        root.put("instances", instances);
        root.put("policies", policies);
        root.put("permissionRequests", permissionRequests);
        root.put("permissionAudit", permissionAudit);
        store.write(root.toString(2));
    }

    private static SandboxCatalogState decode(String content) throws Exception {
        JSONObject root = new JSONObject(content);
        int version = root.optInt("schemaVersion", -1);
        if (version != 1 && version != 2 && version != 3 && version != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported catalog schema: " + version);
        }
        JSONArray packageArray = root.getJSONArray("packages");
        List<SandboxRecord> packages = new ArrayList<>();
        for (int index = 0; index < packageArray.length(); index++) {
            packages.add(SandboxRecord.fromJson(packageArray.getJSONObject(index)));
        }
        JSONArray instanceArray = root.getJSONArray("instances");
        List<SandboxInstance> instances = new ArrayList<>();
        for (int index = 0; index < instanceArray.length(); index++) {
            instances.add(SandboxInstance.fromJson(instanceArray.getJSONObject(index)));
        }
        List<SandboxPolicyState> policies = new ArrayList<>();
        JSONArray policyArray = root.optJSONArray("policies");
        if (policyArray != null) {
            for (int index = 0; index < policyArray.length(); index++) {
                policies.add(SandboxPolicyState.fromJson(policyArray.getJSONObject(index)));
            }
        }
        List<RuntimePermissionRequestRecord> permissionRequests = new ArrayList<>();
        JSONArray requestArray = root.optJSONArray("permissionRequests");
        if (requestArray != null) {
            for (int index = 0; index < requestArray.length(); index++) {
                permissionRequests.add(RuntimePermissionRequestRecord.fromJson(
                        requestArray.getJSONObject(index)));
            }
        }
        List<PermissionAuditRecord> permissionAudit = new ArrayList<>();
        JSONArray auditArray = root.optJSONArray("permissionAudit");
        if (auditArray != null) {
            for (int index = 0; index < auditArray.length(); index++) {
                permissionAudit.add(PermissionAuditRecord.fromJson(auditArray.getJSONObject(index)));
            }
        }
        return new SandboxCatalogState(packages, instances, policies,
                permissionRequests, permissionAudit);
    }
}
