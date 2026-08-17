package com.warden.controlledsandbox;

import com.warden.controlledsandbox.domain.persistence.RecoverableFileStore;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Persists per-package lifecycle transactions next to the catalog. */
final class PackageLifecycleTransactionStore {
    private final RecoverableFileStore store;

    PackageLifecycleTransactionStore(File filesDir) {
        if (filesDir == null) throw new IllegalArgumentException("filesDir is required");
        this.store = new RecoverableFileStore(
                new File(filesDir, "package-lifecycle-transactions.json").toPath());
    }

    synchronized Map<String, PackageLifecycleTransaction> load() throws Exception {
        Map<String, PackageLifecycleTransaction> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(store.primary()) && !Files.isRegularFile(store.backup())) {
            return out;
        }
        JSONObject root = store.read(JSONObject::new, new JSONObject());
        JSONArray rows = root.optJSONArray("transactions");
        if (rows == null) return out;
        for (int index = 0; index < rows.length(); index++) {
            PackageLifecycleTransaction row = PackageLifecycleTransaction.fromJson(
                    rows.getJSONObject(index));
            out.put(row.packageName, row);
        }
        return out;
    }

    synchronized void save(Map<String, PackageLifecycleTransaction> transactions) throws Exception {
        JSONArray rows = new JSONArray();
        for (PackageLifecycleTransaction row : transactions.values()) {
            rows.put(row.toJson());
        }
        JSONObject root = new JSONObject().put("schema", 1).put("transactions", rows);
        store.write(root.toString());
    }

    synchronized PackageLifecycleTransaction get(String packageName) throws Exception {
        return load().get(packageName);
    }

    synchronized void put(PackageLifecycleTransaction transaction) throws Exception {
        Map<String, PackageLifecycleTransaction> all = load();
        all.put(transaction.packageName, transaction);
        save(all);
    }

    synchronized void remove(String packageName) throws Exception {
        Map<String, PackageLifecycleTransaction> all = load();
        if (all.remove(packageName) != null) save(all);
    }

    synchronized List<String> retainedRevisionPaths() throws Exception {
        List<String> paths = new ArrayList<>();
        for (PackageLifecycleTransaction row : load().values()) {
            if (!row.previousApkPath.isEmpty()) paths.add(row.previousApkPath);
        }
        return paths;
    }
}
