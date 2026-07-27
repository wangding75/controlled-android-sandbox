package com.warden.controlledsandbox.domain.identity;

import com.warden.controlledsandbox.domain.persistence.PersistentStateException;
import com.warden.controlledsandbox.domain.persistence.RecoverableFileStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Persistent, collision-free package-to-appId registry. */
public final class VirtualUidRegistry {
    private static final String HEADER = "controlled-sandbox-virtual-uids-v1";
    private final RecoverableFileStore store;
    private final VirtualUidAllocator allocator = new VirtualUidAllocator();
    private final Map<String, Integer> packageToAppId = new HashMap<>();
    private final Set<Integer> usedAppIds = new HashSet<>();
    private int nextCandidate = VirtualUidAllocator.FIRST_APPLICATION_UID;

    public VirtualUidRegistry(Path file) {
        store = new RecoverableFileStore(Objects.requireNonNull(file, "file"));
        RegistryState state = store.read(VirtualUidRegistry::decode, RegistryState.empty());
        packageToAppId.putAll(state.packageToAppId());
        usedAppIds.addAll(state.packageToAppId().values());
        advanceCandidate();
    }

    public synchronized int uidFor(String packageName, int virtualUserId) {
        return allocator.compose(appIdFor(packageName), virtualUserId);
    }

    public synchronized int appIdFor(String packageName) {
        String normalized = requirePackageName(packageName);
        Integer existing = packageToAppId.get(normalized);
        if (existing != null) return existing;
        int appId = nextAvailableAppId();
        packageToAppId.put(normalized, appId);
        usedAppIds.add(appId);
        advanceCandidate();
        persistOrRollback(normalized, appId);
        return appId;
    }

    /** Allocates many packages in one durable transaction; primarily used by import/recovery tooling. */
    public synchronized Map<String, Integer> assignAll(Collection<String> packageNames) {
        Objects.requireNonNull(packageNames, "packageNames");
        Map<String, Integer> before = new HashMap<>(packageToAppId);
        Set<Integer> usedBefore = new HashSet<>(usedAppIds);
        int nextBefore = nextCandidate;
        try {
            for (String packageName : packageNames) {
                String normalized = requirePackageName(packageName);
                if (packageToAppId.containsKey(normalized)) continue;
                int appId = nextAvailableAppId();
                packageToAppId.put(normalized, appId);
                usedAppIds.add(appId);
                advanceCandidate();
            }
            persist();
            return Collections.unmodifiableMap(new HashMap<>(packageToAppId));
        } catch (RuntimeException error) {
            packageToAppId.clear(); packageToAppId.putAll(before);
            usedAppIds.clear(); usedAppIds.addAll(usedBefore);
            nextCandidate = nextBefore;
            throw error;
        }
    }

    public synchronized int size() { return packageToAppId.size(); }

    public synchronized Map<String, Integer> snapshot() {
        return Collections.unmodifiableMap(new HashMap<>(packageToAppId));
    }

    private int nextAvailableAppId() {
        advanceCandidate();
        if (nextCandidate > VirtualUidAllocator.LAST_APPLICATION_UID) {
            throw new IllegalStateException("Virtual application UID range exhausted");
        }
        return nextCandidate;
    }

    private void advanceCandidate() {
        while (nextCandidate <= VirtualUidAllocator.LAST_APPLICATION_UID
                && usedAppIds.contains(nextCandidate)) nextCandidate++;
    }

    private void persistOrRollback(String packageName, int appId) {
        try {
            persist();
        } catch (RuntimeException error) {
            packageToAppId.remove(packageName);
            usedAppIds.remove(appId);
            nextCandidate = Math.min(nextCandidate, appId);
            throw error;
        }
    }

    private void persist() {
        try {
            store.write(encode(packageToAppId));
        } catch (IOException error) {
            throw new PersistentStateException("Cannot persist virtual UID registry", error);
        }
    }

    private static String encode(Map<String, Integer> values) {
        List<String> packages = new ArrayList<>(values.keySet());
        Collections.sort(packages);
        StringBuilder output = new StringBuilder(HEADER).append('\n');
        for (String packageName : packages) {
            output.append(values.get(packageName)).append('\t').append(packageName).append('\n');
        }
        return output.toString();
    }

    private static RegistryState decode(String content) {
        String[] lines = content.split("\\R", -1);
        if (lines.length == 0 || !HEADER.equals(lines[0])) {
            throw new PersistentStateException("Unsupported virtual UID registry format");
        }
        Map<String, Integer> values = new HashMap<>();
        Set<Integer> ids = new HashSet<>();
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index];
            if (line.isEmpty()) continue;
            int separator = line.indexOf('\t');
            if (separator <= 0 || separator == line.length() - 1) {
                throw new PersistentStateException("Invalid virtual UID registry line " + (index + 1));
            }
            int appId;
            try { appId = Integer.parseInt(line.substring(0, separator)); }
            catch (NumberFormatException error) {
                throw new PersistentStateException("Invalid appId at line " + (index + 1), error);
            }
            if (appId < VirtualUidAllocator.FIRST_APPLICATION_UID
                    || appId > VirtualUidAllocator.LAST_APPLICATION_UID) {
                throw new PersistentStateException("Out-of-range appId at line " + (index + 1));
            }
            String packageName = requirePackageName(line.substring(separator + 1));
            if (values.putIfAbsent(packageName, appId) != null) {
                throw new PersistentStateException("Duplicate package in virtual UID registry: " + packageName);
            }
            if (!ids.add(appId)) {
                throw new PersistentStateException("Duplicate appId in virtual UID registry: " + appId);
            }
        }
        return new RegistryState(values);
    }

    private static String requirePackageName(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            throw new IllegalArgumentException("packageName is required");
        }
        String normalized = packageName.trim();
        if (normalized.indexOf('\t') >= 0 || normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("packageName contains unsupported control characters");
        }
        return normalized;
    }

    private record RegistryState(Map<String, Integer> packageToAppId) {
        private RegistryState {
            packageToAppId = Collections.unmodifiableMap(new HashMap<>(packageToAppId));
        }
        static RegistryState empty() { return new RegistryState(Map.of()); }
    }
}
