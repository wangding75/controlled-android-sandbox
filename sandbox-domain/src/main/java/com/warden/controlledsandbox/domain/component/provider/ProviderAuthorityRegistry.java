package com.warden.controlledsandbox.domain.component.provider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Broker-owned virtual Provider authority namespace, isolated by virtual user. */
public final class ProviderAuthorityRegistry {
    public static final int MAX_AUTHORITIES = 2048;

    public static final class Entry {
        private final String instanceId;
        private final int virtualUserId;
        private final String authority;
        private final String component;
        private final String processName;
        private final boolean exported;
        private final String readPermission;
        private final String writePermission;
        private final boolean grantUriPermissions;
        private final List<ProviderPathRule> pathRules;
        private final String sessionId;
        private final long generation;

        private Entry(String instanceId, int virtualUserId, String authority, String component,
                      String processName, boolean exported, String readPermission,
                      String writePermission, boolean grantUriPermissions,
                      List<ProviderPathRule> pathRules, String sessionId, long generation) {
            this.instanceId = instanceId;
            this.virtualUserId = virtualUserId;
            this.authority = authority;
            this.component = component;
            this.processName = processName;
            this.exported = exported;
            this.readPermission = normalize(readPermission);
            this.writePermission = normalize(writePermission);
            this.grantUriPermissions = grantUriPermissions;
            this.pathRules = Collections.unmodifiableList(new ArrayList<>(
                    pathRules == null ? List.of() : pathRules));
            this.sessionId = sessionId;
            this.generation = generation;
        }

        public String instanceId() { return instanceId; }
        public int virtualUserId() { return virtualUserId; }
        public String authority() { return authority; }
        public String component() { return component; }
        public String processName() { return processName; }
        public boolean exported() { return exported; }
        public String readPermission() { return readPermission; }
        public String writePermission() { return writePermission; }
        public boolean grantUriPermissions() { return grantUriPermissions; }
        public List<ProviderPathRule> pathRules() { return pathRules; }
        public String sessionId() { return sessionId; }
        public long generation() { return generation; }
        public String virtualAuthority() { return "u" + virtualUserId + "." + safe(instanceId) + "." + authority; }

        public String requiredReadPermission(String uri) {
            return ProviderAuthorityAccessPolicy.requiredPermission(pathRules, readPermission, uri, true);
        }

        public String requiredWritePermission(String uri) {
            return ProviderAuthorityAccessPolicy.requiredPermission(pathRules, writePermission, uri, false);
        }

        public boolean allowsUriGrant(String uri) {
            return ProviderAuthorityAccessPolicy.allowsUriGrant(grantUriPermissions, pathRules, uri);
        }

        private boolean sameOwner(String expectedInstanceId, String expectedComponent,
                                  String expectedProcessName, boolean expectedExported,
                                  String expectedReadPermission, String expectedWritePermission,
                                  boolean expectedGrantUriPermissions, List<ProviderPathRule> expectedPathRules,
                                  String expectedSessionId, long expectedGeneration) {
            return instanceId.equals(expectedInstanceId)
                    && component.equals(expectedComponent)
                    && processName.equals(expectedProcessName)
                    && exported == expectedExported
                    && readPermission.equals(normalize(expectedReadPermission))
                    && writePermission.equals(normalize(expectedWritePermission))
                    && grantUriPermissions == expectedGrantUriPermissions
                    && ProviderAuthorityAccessPolicy.equivalent(pathRules, expectedPathRules)
                    && sessionId.equals(expectedSessionId)
                    && generation == expectedGeneration;
        }

        private Entry rebound(String newSessionId, long newGeneration) {
            return new Entry(instanceId, virtualUserId, authority, component, processName, exported,
                    readPermission, writePermission, grantUriPermissions, pathRules,
                    newSessionId, newGeneration);
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public synchronized List<Entry> register(String instanceId, int virtualUserId, String authorities,
                                             String component, String processName, boolean exported) {
        return registerSession(instanceId, virtualUserId, authorities, component, processName, exported,
                "", "", false, List.of(), instanceId, 0).entries();
    }

    public synchronized ProviderAuthorityRegistration registerSession(String instanceId, int virtualUserId,
                                                      String authorities, String component,
                                                      String processName, boolean exported,
                                                      String sessionId, long generation) {
        return registerSession(instanceId, virtualUserId, authorities, component, processName, exported,
                "", "", false, List.of(), sessionId, generation);
    }

    public synchronized ProviderAuthorityRegistration registerSession(String instanceId, int virtualUserId,
                                                      String authorities, String component,
                                                      String processName, boolean exported,
                                                      String readPermission, String writePermission,
                                                      boolean grantUriPermissions, List<ProviderPathRule> pathRules,
                                                      String sessionId, long generation) {
        requireText(instanceId, "instanceId");
        requireText(authorities, "authorities");
        requireText(component, "component");
        requireText(sessionId, "sessionId");
        if (virtualUserId < 0) throw new IllegalArgumentException("virtualUserId must be non-negative");
        if (generation < 0) throw new IllegalArgumentException("generation must be non-negative");
        String normalizedProcess = normalize(processName);
        List<ProviderPathRule> normalizedRules = Collections.unmodifiableList(new ArrayList<>(
                pathRules == null ? List.of() : pathRules));
        Set<String> parsed = parseAuthorities(authorities);
        List<Entry> resolved = new ArrayList<>();
        List<Entry> staged = new ArrayList<>();
        Set<String> created = new LinkedHashSet<>();
        for (String authority : parsed) {
            String key = key(virtualUserId, authority);
            Entry existing = entries.get(key);
            if (existing != null) {
                if (!existing.sameOwner(instanceId, component, normalizedProcess, exported,
                        readPermission, writePermission, grantUriPermissions, normalizedRules,
                        sessionId, generation)) {
                    throw new IllegalStateException("DUPLICATE_PROVIDER_AUTHORITY:" + authority);
                }
                resolved.add(existing);
                continue;
            }
            Entry entry = new Entry(instanceId, virtualUserId, authority, component,
                    normalizedProcess, exported, readPermission, writePermission,
                    grantUriPermissions, normalizedRules, sessionId, generation);
            staged.add(entry);
            resolved.add(entry);
            created.add(authority);
        }
        if (entries.size() + staged.size() > MAX_AUTHORITIES) {
            throw new IllegalStateException("PROVIDER_AUTHORITY_CAPACITY_EXHAUSTED");
        }
        for (Entry entry : staged) entries.put(key(virtualUserId, entry.authority), entry);
        return new ProviderAuthorityRegistration(resolved, created);
    }

    public synchronized Entry resolve(int virtualUserId, String instanceId, String authority) {
        Entry entry = entries.get(key(virtualUserId, authority));
        return entry != null && entry.instanceId.equals(instanceId) ? entry : null;
    }

    public synchronized Entry resolveAuthority(int virtualUserId, String authority) {
        requireText(authority, "authority");
        if (virtualUserId < 0) throw new IllegalArgumentException("virtualUserId must be non-negative");
        return entries.get(key(virtualUserId, authority.trim()));
    }

    public synchronized Entry requireOwned(int virtualUserId, String instanceId, String authority,
                                           String sessionId, long generation) {
        requireText(instanceId, "instanceId");
        requireText(authority, "authority");
        requireText(sessionId, "sessionId");
        Entry entry = entries.get(key(virtualUserId, authority));
        if (entry == null) throw new IllegalArgumentException("UNKNOWN_PROVIDER_AUTHORITY:" + authority);
        if (!entry.instanceId.equals(instanceId) || !entry.sessionId.equals(sessionId)
                || entry.generation != generation) {
            throw new SecurityException("PROVIDER_AUTHORITY_OWNER_MISMATCH:" + authority);
        }
        return entry;
    }

    public synchronized Entry resolveExported(int virtualUserId, String authority) {
        Entry entry = entries.get(key(virtualUserId, authority));
        return entry != null && entry.exported ? entry : null;
    }

    public synchronized int rollback(int virtualUserId, String instanceId, String sessionId,
                                     long generation, Collection<String> authorities) {
        if (authorities == null || authorities.isEmpty()) return 0;
        int removed = 0;
        for (String authority : authorities) {
            if (authority == null || authority.trim().isEmpty()) continue;
            String normalized = authority.trim();
            String key = key(virtualUserId, normalized);
            Entry entry = entries.get(key);
            if (entry != null && entry.instanceId.equals(instanceId)
                    && entry.sessionId.equals(sessionId) && entry.generation == generation
                    && entries.remove(key, entry)) removed++;
        }
        return removed;
    }

    public synchronized int rebindSession(String instanceId, int virtualUserId, String processName,
                                          String oldSessionId, long oldGeneration,
                                          String newSessionId, long newGeneration) {
        requireText(instanceId, "instanceId");
        requireText(oldSessionId, "oldSessionId");
        requireText(newSessionId, "newSessionId");
        if (newGeneration <= oldGeneration) throw new IllegalArgumentException("generation must increase");
        String normalizedProcess = normalize(processName);
        int updated = 0;
        for (Map.Entry<String, Entry> item : new ArrayList<>(entries.entrySet())) {
            Entry entry = item.getValue();
            if (entry.virtualUserId == virtualUserId && entry.instanceId.equals(instanceId)
                    && entry.processName.equals(normalizedProcess)
                    && entry.sessionId.equals(oldSessionId) && entry.generation == oldGeneration) {
                entries.put(item.getKey(), entry.rebound(newSessionId, newGeneration));
                updated++;
            }
        }
        return updated;
    }

    public synchronized int removeSession(String sessionId, long generation) {
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, Entry> item : entries.entrySet()) {
            Entry entry = item.getValue();
            if (entry.sessionId.equals(sessionId) && entry.generation == generation) keys.add(item.getKey());
        }
        for (String key : keys) entries.remove(key);
        return keys.size();
    }

    public synchronized int unregisterInstance(int virtualUserId, String instanceId) {
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, Entry> item : entries.entrySet()) {
            Entry entry = item.getValue();
            if (entry.virtualUserId == virtualUserId && entry.instanceId.equals(instanceId)) keys.add(item.getKey());
        }
        for (String key : keys) entries.remove(key);
        return keys.size();
    }

    public synchronized int size() { return entries.size(); }

    private static Set<String> parseAuthorities(String raw) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String authority : raw.split(";")) {
            String normalized = authority.trim();
            if (!normalized.isEmpty()) out.add(normalized);
        }
        if (out.isEmpty()) throw new IllegalArgumentException("no valid authorities");
        return out;
    }

    private static String key(int userId, String authority) { return userId + "#" + authority; }
    private static String safe(String value) { return value.replaceAll("[^A-Za-z0-9_.-]", "_"); }
    private static String normalize(String value) { return value == null ? "" : value.trim(); }
    private static void requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
    }
}
