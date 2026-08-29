package com.warden.controlledsandbox;

import android.content.Context;
import com.warden.controlledsandbox.domain.persistence.RecoverableFileStore;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Single atomic metadata authority for installed packages and virtual instances. */
final class SandboxCatalogRepository {
    private static final int SCHEMA_VERSION = 5;
    private final RecoverableFileStore store;
    private final SandboxRepository legacyPackages;
    private final SandboxInstanceRepository legacyInstances;
    private final PackageStorageLayout storageLayout;
    private final LegacyPackageLayoutMigrator legacyLayoutMigrator;
    private long generation;
    private SandboxCatalogState cachedState;
    private CatalogStamp cachedPrimary;
    private CatalogStamp cachedBackup;
    private boolean cachedFullyValidated;

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
        if (cachedState != null && cacheMatchesDisk()) {
            try {
                if (cachedFullyValidated) {
                    // A fully validated cache hit still performs the cheap published-layout check
                    // so a missing, replaced or symlinked revision fails closed without reparsing
                    // the catalog or hashing every APK on every launch/import.
                    storageLayout.requireCatalogLayoutFast(cachedState);
                } else {
                    // A fast-path probe may have populated this cache without cryptographic
                    // artifact validation.  The ordinary load is the authority and upgrades it
                    // only after the full digest check succeeds.
                    storageLayout.requireCatalogLayout(cachedState);
                    cachedFullyValidated = true;
                }
                return cachedState;
            } catch (Exception staleCache) {
                clearCache();
            }
        }
        boolean catalogExists = Files.isRegularFile(store.primary()) || Files.isRegularFile(store.backup());
        if (catalogExists) {
            SandboxCatalogState state = store.read(
                    SandboxCatalogRepository::decode, SandboxCatalogState.empty());
            storageLayout.requireCatalogLayout(state);
            cacheState(state, true);
            return state;
        }
        SandboxCatalogState legacy = SandboxCatalogState.normalizeLegacy(
                legacyPackages.load(), legacyInstances.load(), System.currentTimeMillis());
        SandboxCatalogState migrated = legacyLayoutMigrator.migrate(legacy);
        if (!migrated.records().isEmpty() || !migrated.instances().isEmpty()) save(migrated);
        else clearCache();
        return migrated;
    }

    /**
     * Reads the catalog with only the cheap published-layout checks needed by a same-revision
     * import probe.  Callers must use {@link #load()} before any operation that mutates or
     * publishes a revision; a failed fast-path probe therefore remains fail-closed.
     */
    synchronized SandboxCatalogState loadForFastPath() throws Exception {
        if (cachedState != null && cacheMatchesDisk()) {
            try {
                storageLayout.requireCatalogLayoutFast(cachedState);
                return cachedState;
            } catch (Exception staleCache) {
                clearCache();
            }
        }
        boolean catalogExists = Files.isRegularFile(store.primary()) || Files.isRegularFile(store.backup());
        if (!catalogExists) return SandboxCatalogState.empty();
        SandboxCatalogState state = store.read(SandboxCatalogRepository::decode,
                SandboxCatalogState.empty());
        storageLayout.requireCatalogLayoutFast(state);
        cacheState(state, false);
        return state;
    }

    synchronized void save(SandboxCatalogState state) throws Exception {
        if (state == null) throw new IllegalArgumentException("catalog state is required");
        storageLayout.requireCatalogLayout(state);
        write(state);
        generation++;
        cacheState(state, true);
    }

    /**
     * Persists only after the cheap layout validation used by a same-revision probe.
     *
     * <p>This is intentionally limited to adding an instance/user binding for an already
     * proven revision.  Any revision import or replacement must continue to use {@link #save},
     * which recomputes every published artifact digest.</p>
     */
    synchronized void saveForFastPath(SandboxCatalogState state) throws Exception {
        if (state == null) throw new IllegalArgumentException("catalog state is required");
        storageLayout.requireCatalogLayoutFast(state);
        write(state);
        generation++;
        cacheState(state, false);
    }

    synchronized long generation() { return generation; }

    private boolean cacheMatchesDisk() {
        if (cachedPrimary == null || cachedBackup == null) return false;
        CatalogStamp currentPrimary = CatalogStamp.capture(store.primary());
        CatalogStamp currentBackup = CatalogStamp.capture(store.backup());
        return currentPrimary != null && currentBackup != null
                && cachedPrimary.equals(currentPrimary) && cachedBackup.equals(currentBackup);
    }

    private void cacheState(SandboxCatalogState state, boolean fullyValidated) {
        CatalogStamp primary = CatalogStamp.capture(store.primary());
        CatalogStamp backup = CatalogStamp.capture(store.backup());
        if (primary == null || backup == null) {
            clearCache();
            return;
        }
        cachedState = state;
        cachedPrimary = primary;
        cachedBackup = backup;
        cachedFullyValidated = fullyValidated;
    }

    private void clearCache() {
        cachedState = null;
        cachedPrimary = null;
        cachedBackup = null;
        cachedFullyValidated = false;
    }

    private void write(SandboxCatalogState state) throws Exception {
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
        if (version != 1 && version != 2 && version != 3 && version != 4 && version != SCHEMA_VERSION) {
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

    private static final class CatalogStamp {
        final boolean exists;
        final long size;
        final long modified;
        final String identity;

        CatalogStamp(boolean exists, long size, long modified, String identity) {
            this.exists = exists;
            this.size = size;
            this.modified = modified;
            this.identity = identity == null ? "" : identity;
        }

        static CatalogStamp capture(Path path) {
            try {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    return new CatalogStamp(false, 0L, 0L, "");
                }
                BasicFileAttributes attributes = Files.readAttributes(path,
                        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                Object key = attributes.fileKey();
                String identity = key == null
                        ? "creation:" + attributes.creationTime().toMillis()
                        : String.valueOf(key);
                return new CatalogStamp(true, attributes.size(),
                        attributes.lastModifiedTime().toMillis(), identity);
            } catch (Exception error) {
                return null;
            }
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof CatalogStamp stamp)) return false;
            return exists == stamp.exists && size == stamp.size && modified == stamp.modified
                    && identity.equals(stamp.identity);
        }

        @Override public int hashCode() {
            return java.util.Objects.hash(exists, size, modified, identity);
        }
    }
}
