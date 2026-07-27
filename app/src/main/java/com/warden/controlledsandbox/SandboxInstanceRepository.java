package com.warden.controlledsandbox;

import android.content.Context;
import com.warden.controlledsandbox.domain.persistence.PersistentStateException;
import com.warden.controlledsandbox.domain.persistence.RecoverableFileStore;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;

final class SandboxInstanceRepository {
    private final RecoverableFileStore store;

    SandboxInstanceRepository(Context context) {
        File base = new File(context.getFilesDir(), "sandbox-instances.json");
        store = new RecoverableFileStore(base.toPath());
    }

    synchronized List<SandboxInstance> load() {
        List<SandboxInstance> instances = store.read(SandboxInstanceRepository::decode, List.of());
        return new ArrayList<>(instances);
    }

    synchronized void save(List<SandboxInstance> instances) throws Exception {
        if (instances == null) throw new IllegalArgumentException("instances are required");
        validate(instances);
        JSONArray array = new JSONArray();
        for (SandboxInstance instance : instances) array.put(instance.toJson());
        store.write(array.toString(2));
    }

    static int nextUserId(List<SandboxInstance> instances, String packageName) {
        int next = 0;
        for (SandboxInstance instance : instances) {
            if (instance.packageName.equals(packageName)) next = Math.max(next, instance.virtualUserId + 1);
        }
        if (next > 999) throw new IllegalStateException("Virtual user limit reached");
        return next;
    }

    private static List<SandboxInstance> decode(String content) throws Exception {
        JSONArray array = new JSONArray(content);
        List<SandboxInstance> instances = new ArrayList<>();
        for (int index = 0; index < array.length(); index++) {
            instances.add(SandboxInstance.fromJson(array.getJSONObject(index)));
        }
        validate(instances);
        instances.sort(Comparator.comparing((SandboxInstance item) -> item.packageName)
                .thenComparingInt(item -> item.virtualUserId));
        return instances;
    }

    private static void validate(List<SandboxInstance> instances) {
        Set<String> keys = new HashSet<>();
        for (SandboxInstance instance : instances) {
            if (instance == null) throw new PersistentStateException("Instance metadata contains a null record");
            String key = instance.packageName + "#" + instance.virtualUserId;
            if (!keys.add(key)) throw new PersistentStateException("Duplicate sandbox instance: " + key);
            if (instance.virtualUserId > 999) {
                throw new PersistentStateException("Virtual user id out of range: " + key);
            }
        }
    }
}
