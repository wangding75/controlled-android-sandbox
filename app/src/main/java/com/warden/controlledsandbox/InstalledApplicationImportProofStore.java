package com.warden.controlledsandbox;

import com.warden.controlledsandbox.domain.persistence.RecoverableFileStore;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/** Best-effort durable index for installed-application same-revision probes. */
final class InstalledApplicationImportProofStore {
    private static final int STORE_VERSION = 1;
    private final RecoverableFileStore store;
    private Map<String, InstalledApplicationImportProof> entries;

    InstalledApplicationImportProofStore(File filesDir) {
        if (filesDir == null) throw new IllegalArgumentException("filesDir is required");
        store = new RecoverableFileStore(new File(filesDir,
                "same-revision-import-proofs.json").toPath(), 2L * 1024 * 1024);
    }

    synchronized InstalledApplicationImportProof find(String packageName) {
        return load().get(packageName == null ? "" : packageName.trim());
    }

    synchronized void put(InstalledApplicationImportProof proof) throws Exception {
        if (proof == null) throw new IllegalArgumentException("proof is required");
        Map<String, InstalledApplicationImportProof> next = new LinkedHashMap<>(load());
        next.put(proof.packageName, proof);
        write(next);
    }

    synchronized void remove(String packageName) throws Exception {
        String normalized = packageName == null ? "" : packageName.trim();
        Map<String, InstalledApplicationImportProof> next = new LinkedHashMap<>(load());
        if (next.remove(normalized) != null) write(next);
    }

    private Map<String, InstalledApplicationImportProof> load() {
        if (entries != null) return entries;
        try {
            entries = store.read(InstalledApplicationImportProofStore::decode,
                    new LinkedHashMap<>());
        } catch (Throwable error) {
            FatalErrorPolicy.rethrowIfFatal(error);
            // This file is an optimization index, never the package authority.  A corrupt or
            // stale index simply makes the next import take the normal verified path.
            entries = new LinkedHashMap<>();
        }
        return entries;
    }

    private void write(Map<String, InstalledApplicationImportProof> values) throws Exception {
        JSONObject packages = new JSONObject();
        for (Map.Entry<String, InstalledApplicationImportProof> item : values.entrySet()) {
            packages.put(item.getKey(), item.getValue().toJson());
        }
        JSONObject root = new JSONObject().put("version", STORE_VERSION)
                .put("packages", packages);
        store.write(root.toString(2));
        entries = new LinkedHashMap<>(values);
    }

    private static Map<String, InstalledApplicationImportProof> decode(String content)
            throws Exception {
        JSONObject root = new JSONObject(content);
        if (root.optInt("version", -1) != STORE_VERSION) {
            throw new IllegalArgumentException("Unsupported same-revision proof version");
        }
        JSONObject packages = root.optJSONObject("packages");
        Map<String, InstalledApplicationImportProof> decoded = new LinkedHashMap<>();
        if (packages == null) return decoded;
        java.util.Iterator<String> keys = packages.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            InstalledApplicationImportProof proof = InstalledApplicationImportProof.fromJson(
                    packages.getJSONObject(key));
            if (!key.equals(proof.packageName)) {
                throw new IllegalArgumentException("same-revision proof package key mismatch");
            }
            decoded.put(key, proof);
        }
        return decoded;
    }
}
