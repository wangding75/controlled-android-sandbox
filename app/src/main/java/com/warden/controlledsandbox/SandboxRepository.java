package com.warden.controlledsandbox;

import android.content.Context;
import com.warden.controlledsandbox.domain.persistence.PersistentStateException;
import com.warden.controlledsandbox.domain.persistence.RecoverableFileStore;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;

final class SandboxRepository {
    private final RecoverableFileStore store;

    SandboxRepository(Context context) {
        File base = new File(context.getFilesDir(), "sandbox-packages.json");
        store = new RecoverableFileStore(base.toPath());
    }

    synchronized List<SandboxRecord> load() {
        List<SandboxRecord> records = store.read(SandboxRepository::decode, List.of());
        return new ArrayList<>(records);
    }

    synchronized void save(List<SandboxRecord> records) throws Exception {
        if (records == null) throw new IllegalArgumentException("records are required");
        validate(records);
        JSONArray array = new JSONArray();
        for (SandboxRecord record : records) array.put(record.toJson());
        store.write(array.toString(2));
    }

    private static List<SandboxRecord> decode(String content) throws Exception {
        JSONArray array = new JSONArray(content);
        List<SandboxRecord> records = new ArrayList<>();
        for (int index = 0; index < array.length(); index++) {
            records.add(SandboxRecord.fromJson(array.getJSONObject(index)));
        }
        validate(records);
        records.sort(Comparator.comparing(record -> record.label.toLowerCase(Locale.ROOT)));
        return records;
    }

    private static void validate(List<SandboxRecord> records) {
        Set<String> packages = new HashSet<>();
        for (SandboxRecord record : records) {
            if (record == null) throw new PersistentStateException("Package metadata contains a null record");
            if (!packages.add(record.packageName)) {
                throw new PersistentStateException("Duplicate package metadata: " + record.packageName);
            }
            if (record.versionCode < 0 || record.signatureSha256.trim().isEmpty()
                    || record.apkPath.trim().isEmpty()) {
                throw new PersistentStateException("Incomplete trusted package metadata: " + record.packageName);
            }
        }
    }
}
