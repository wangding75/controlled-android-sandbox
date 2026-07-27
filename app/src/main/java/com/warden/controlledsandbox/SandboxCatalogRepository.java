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
    private static final int SCHEMA_VERSION = 2;
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
        root.put("packages", packages);
        root.put("instances", instances);
        root.put("policies", policies);
        store.write(root.toString(2));
    }

    private static SandboxCatalogState decode(String content) throws Exception {
        JSONObject root = new JSONObject(content);
        int version = root.optInt("schemaVersion", -1);
        if (version != 1 && version != SCHEMA_VERSION) {
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
        return new SandboxCatalogState(packages, instances, policies);
    }
}
