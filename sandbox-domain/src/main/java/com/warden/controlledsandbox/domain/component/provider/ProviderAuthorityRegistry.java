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
    public static final class Entry {
        private final String instanceId;
        private final int virtualUserId;
        private final String authority;
        private final String component;
        private final String processName;
        private final boolean exported;
        private final String sessionId;
        private final long generation;

        private Entry(String instanceId, int virtualUserId, String authority, String component,
                      String processName, boolean exported, String sessionId, long generation) {
            this.instanceId = instanceId;
            this.virtualUserId = virtualUserId;
            this.authority = authority;
            this.component = component;
            this.processName = processName;
            this.exported = exported;
            this.sessionId = sessionId;
            this.generation = generation;
        }

        public String instanceId() { return instanceId; }
        public int virtualUserId() { return virtualUserId; }
        public String authority() { return authority; }
        public String component() { return component; }
        public String processName() { return processName; }
        public boolean exported() { return exported; }
        public String sessionId() { return sessionId; }
        public long generation() { return generation; }
        public String virtualAuthority() { return "u" + virtualUserId + "." + safe(instanceId) + "." + authority; }

        private boolean sameOwner(String expectedInstanceId, String expectedComponent, String expectedProcessName,
                                  boolean expectedExported, String expectedSessionId, long expectedGeneration) {
            return instanceId.equals(expectedInstanceId)
                    && component.equals(expectedComponent)
                    && processName.equals(expectedProcessName)
                    && exported == expectedExported
                    && sessionId.equals(expectedSessionId)
                    && generation == expectedGeneration;
        }

        private Entry rebound(String newSessionId, long newGeneration) {
            return new Entry(instanceId, virtualUserId, authority, component, processName, exported,
                    newSessionId, newGeneration);
        }
    }

    public static final class Registration {
        private final List<Entry> entries;
        private final Set<String> createdAuthorities;

        private Registration(List<Entry> entries, Set<String> createdAuthorities) {
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
            this.createdAuthorities = Collections.unmodifiableSet(new LinkedHashSet<>(createdAuthorities));
        }

        public List<Entry> entries() { return entries; }
        public Set<String> createdAuthorities() { return createdAuthorities; }
        public boolean createdAny() { return !createdAuthorities.isEmpty(); }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    /** Legacy helper retained for domain callers that do not model a live Guest session. */
    public synchronized List<Entry> register(String instanceId, int virtualUserId, String authorities,
                                             String component, String processName, boolean exported) {
        return registerSession(instanceId, virtualUserId, authorities, component, processName, exported,
                instanceId, 0).entries();
    }

    /** Atomically registers all authorities for one live Guest session. */
    public synchronized Registration registerSession(String instanceId, int virtualUserId, String authorities,
                                                     String component, String processName, boolean exported,
                                                     String sessionId, long generation) {
        requireText(instanceId, "instanceId");
        requireText(authorities, "authorities");
        requireText(component, "component");
        requireText(sessionId, "sessionId");
        if (virtualUserId < 0) throw new IllegalArgumentException("virtualUserId must be non-negative");
        if (generation < 0) throw new IllegalArgumentException("generation must be non-negative");
        String normalizedProcess = processName == null ? "" : processName.trim();
        Set<String> parsed = parseAuthorities(authorities);
        List<Entry> resolved = new ArrayList<>();
        List<Entry> staged = new ArrayList<>();
        Set<String> created = new LinkedHashSet<>();
        for (String authority : parsed) {
            String key = key(virtualUserId, authority);
            Entry existing = entries.get(key);
            if (existing != null) {
                if (!existing.sameOwner(instanceId, component, normalizedProcess, exported, sessionId, generation)) {
                    throw new IllegalStateException("DUPLICATE_PROVIDER_AUTHORITY:" + authority);
                }
                resolved.add(existing);
                continue;
            }
            Entry entry = new Entry(instanceId, virtualUserId, authority, component,
                    normalizedProcess, exported, sessionId, generation);
            staged.add(entry);
            resolved.add(entry);
            created.add(authority);
        }
        for (Entry entry : staged) entries.put(key(virtualUserId, entry.authority), entry);
        return new Registration(resolved, created);
    }

    public synchronized Entry resolve(int virtualUserId, String instanceId, String authority) {
        Entry entry = entries.get(key(virtualUserId, authority));
        return entry != null && entry.instanceId.equals(instanceId) ? entry : null;
    }

    /** Resolves the authoritative owner for one virtual-user authority namespace. */
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
        if (!entry.instanceId.equals(instanceId)
                || !entry.sessionId.equals(sessionId)
                || entry.generation != generation) {
            throw new SecurityException("PROVIDER_AUTHORITY_OWNER_MISMATCH:" + authority);
        }
        return entry;
    }

    public synchronized Entry resolveExported(int virtualUserId, String authority) {
        Entry entry = entries.get(key(virtualUserId, authority));
        return entry != null && entry.exported ? entry : null;
    }

    /** Removes only authorities created by a failed reservation. */
    public synchronized int rollback(int virtualUserId, String instanceId, String sessionId,
                                     long generation, Collection<String> authorities) {
        if (authorities == null || authorities.isEmpty()) return 0;
        int removed = 0;
        for (String authority : authorities) {
            if (authority == null || authority.trim().isEmpty()) continue;
            String normalized = authority.trim();
            String key = key(virtualUserId, normalized);
            Entry entry = entries.get(key);
            if (entry != null
                    && entry.instanceId.equals(instanceId)
                    && entry.sessionId.equals(sessionId)
                    && entry.generation == generation
                    && entries.remove(key, entry)) removed++;
        }
        return removed;
    }

    /** Rebinds Provider ownership after a recoverable Guest process generation change. */
    public synchronized int rebindSession(String instanceId, int virtualUserId, String processName,
                                          String oldSessionId, long oldGeneration,
                                          String newSessionId, long newGeneration) {
        requireText(instanceId, "instanceId");
        requireText(oldSessionId, "oldSessionId");
        requireText(newSessionId, "newSessionId");
        if (newGeneration <= oldGeneration) throw new IllegalArgumentException("generation must increase");
        String normalizedProcess = processName == null ? "" : processName.trim();
        int updated = 0;
        for (Map.Entry<String, Entry> item : new ArrayList<>(entries.entrySet())) {
            Entry entry = item.getValue();
            if (entry.virtualUserId == virtualUserId
                    && entry.instanceId.equals(instanceId)
                    && entry.processName.equals(normalizedProcess)
                    && entry.sessionId.equals(oldSessionId)
                    && entry.generation == oldGeneration) {
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

    private static String key(int userId, String authority) {
        return userId + "#" + authority;
    }

    private static String safe(String value) { return value.replaceAll("[^A-Za-z0-9_.-]", "_"); }

    private static void requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
    }
}
