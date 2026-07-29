package com.warden.controlledsandbox.domain.component.provider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Broker-independent metadata registry for virtual ContentObserver ownership and URI matching. */
public final class ProviderObserverRegistry {
    public static final int MAX_OBSERVERS = 256;
    public static final class Entry {
        private final String id;
        private final String callerInstanceId;
        private final int virtualUserId;
        private final String callerSessionId;
        private final long callerGeneration;
        private final String targetInstanceId;
        private final String targetSessionId;
        private final long targetGeneration;
        private final String authority;
        private final String uri;
        private final boolean notifyDescendants;
        private final boolean deliverSelfNotifications;

        private Entry(String id, String callerInstanceId, int virtualUserId,
                      String callerSessionId, long callerGeneration,
                      String targetInstanceId, String targetSessionId, long targetGeneration,
                      String authority, String uri, boolean notifyDescendants,
                      boolean deliverSelfNotifications) {
            this.id = id;
            this.callerInstanceId = callerInstanceId;
            this.virtualUserId = virtualUserId;
            this.callerSessionId = callerSessionId;
            this.callerGeneration = callerGeneration;
            this.targetInstanceId = targetInstanceId;
            this.targetSessionId = targetSessionId;
            this.targetGeneration = targetGeneration;
            this.authority = authority;
            this.uri = uri;
            this.notifyDescendants = notifyDescendants;
            this.deliverSelfNotifications = deliverSelfNotifications;
        }

        public String id() { return id; }
        public String callerInstanceId() { return callerInstanceId; }
        public int virtualUserId() { return virtualUserId; }
        public String callerSessionId() { return callerSessionId; }
        public long callerGeneration() { return callerGeneration; }
        public String targetInstanceId() { return targetInstanceId; }
        public String targetSessionId() { return targetSessionId; }
        public long targetGeneration() { return targetGeneration; }
        public String authority() { return authority; }
        public String uri() { return uri; }
        public boolean notifyDescendants() { return notifyDescendants; }
        public boolean deliverSelfNotifications() { return deliverSelfNotifications; }
    }

    public static final class Registration {
        private final Entry entry;
        private final boolean created;

        private Registration(Entry entry, boolean created) {
            this.entry = entry;
            this.created = created;
        }

        public Entry entry() { return entry; }
        public boolean created() { return created; }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public synchronized Registration register(String id, String callerInstanceId, int virtualUserId,
                                              String callerSessionId, long callerGeneration,
                                              String targetInstanceId, String targetSessionId,
                                              long targetGeneration, String authority, String uri,
                                              boolean notifyDescendants,
                                              boolean deliverSelfNotifications) {
        requireText(id, "id");
        requireText(callerInstanceId, "callerInstanceId");
        requireText(callerSessionId, "callerSessionId");
        requireText(targetInstanceId, "targetInstanceId");
        requireText(targetSessionId, "targetSessionId");
        requireText(authority, "authority");
        if (virtualUserId < 0) throw new IllegalArgumentException("virtualUserId must be non-negative");
        if (callerGeneration < 1 || targetGeneration < 1) {
            throw new IllegalArgumentException("generations must be positive");
        }
        String normalizedUri = normalizeContentUri(uri, authority);
        Entry existing = entries.get(id);
        if (existing != null) {
            if (!same(existing, callerInstanceId, virtualUserId, callerSessionId, callerGeneration,
                    targetInstanceId, targetSessionId, targetGeneration, authority, normalizedUri,
                    notifyDescendants, deliverSelfNotifications)) {
                throw new SecurityException("PROVIDER_OBSERVER_ID_CONFLICT");
            }
            return new Registration(existing, false);
        }
        if (entries.size() >= MAX_OBSERVERS) {
            throw new IllegalStateException("PROVIDER_OBSERVER_CAPACITY_EXHAUSTED");
        }
        Entry created = new Entry(id, callerInstanceId, virtualUserId, callerSessionId,
                callerGeneration, targetInstanceId, targetSessionId, targetGeneration,
                authority, normalizedUri, notifyDescendants, deliverSelfNotifications);
        entries.put(id, created);
        return new Registration(created, true);
    }

    public synchronized Entry requireOwned(String id, String callerInstanceId,
                                           String callerSessionId, long callerGeneration) {
        Entry entry = entries.get(id);
        if (entry == null) throw new IllegalArgumentException("UNKNOWN_PROVIDER_OBSERVER");
        if (!entry.callerInstanceId.equals(callerInstanceId)
                || !entry.callerSessionId.equals(callerSessionId)
                || entry.callerGeneration != callerGeneration) {
            throw new SecurityException("PROVIDER_OBSERVER_OWNER_MISMATCH");
        }
        return entry;
    }

    public synchronized Entry unregister(String id, String callerInstanceId,
                                         String callerSessionId, long callerGeneration) {
        Entry entry = requireOwned(id, callerInstanceId, callerSessionId, callerGeneration);
        entries.remove(id);
        return entry;
    }

    public synchronized List<Entry> resolve(int virtualUserId, String authority, String changedUri,
                                            String notifyingInstanceId, String targetSessionId,
                                            long targetGeneration) {
        requireText(authority, "authority");
        requireText(notifyingInstanceId, "notifyingInstanceId");
        requireText(targetSessionId, "targetSessionId");
        String normalized = normalizeContentUri(changedUri, authority);
        List<Entry> matches = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (entry.virtualUserId != virtualUserId || !entry.authority.equals(authority)) continue;
            if (!entry.targetSessionId.equals(targetSessionId)
                    || entry.targetGeneration != targetGeneration) continue;
            if (entry.callerInstanceId.equals(notifyingInstanceId)
                    && !entry.deliverSelfNotifications) continue;
            if (normalized.equals(entry.uri)
                    || (entry.notifyDescendants && normalized.startsWith(entry.uri + "/"))) {
                matches.add(entry);
            }
        }
        return Collections.unmodifiableList(matches);
    }

    public synchronized int removeSession(String sessionId, long generation) {
        requireText(sessionId, "sessionId");
        List<String> ids = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if ((entry.callerSessionId.equals(sessionId) && entry.callerGeneration == generation)
                    || (entry.targetSessionId.equals(sessionId) && entry.targetGeneration == generation)) {
                ids.add(entry.id);
            }
        }
        for (String id : ids) entries.remove(id);
        return ids.size();
    }

    public synchronized int removeInstance(String instanceId) {
        requireText(instanceId, "instanceId");
        List<String> ids = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (entry.callerInstanceId.equals(instanceId) || entry.targetInstanceId.equals(instanceId)) {
                ids.add(entry.id);
            }
        }
        for (String id : ids) entries.remove(id);
        return ids.size();
    }

    public synchronized Entry get(String id) { return entries.get(id); }
    public synchronized int size() { return entries.size(); }

    private static boolean same(Entry entry, String callerInstanceId, int virtualUserId,
                                String callerSessionId, long callerGeneration,
                                String targetInstanceId, String targetSessionId,
                                long targetGeneration, String authority, String uri,
                                boolean notifyDescendants, boolean deliverSelfNotifications) {
        return entry.callerInstanceId.equals(callerInstanceId)
                && entry.virtualUserId == virtualUserId
                && entry.callerSessionId.equals(callerSessionId)
                && entry.callerGeneration == callerGeneration
                && entry.targetInstanceId.equals(targetInstanceId)
                && entry.targetSessionId.equals(targetSessionId)
                && entry.targetGeneration == targetGeneration
                && entry.authority.equals(authority)
                && entry.uri.equals(uri)
                && entry.notifyDescendants == notifyDescendants
                && entry.deliverSelfNotifications == deliverSelfNotifications;
    }

    private static String normalizeContentUri(String uri, String expectedAuthority) {
        requireText(uri, "uri");
        String normalized = uri.trim();
        while (normalized.endsWith("/") && normalized.length() > "content://x".length()) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        String prefix = "content://";
        if (!normalized.startsWith(prefix)) {
            throw new IllegalArgumentException("Provider observer URI must use content scheme");
        }
        int slash = normalized.indexOf('/', prefix.length());
        String authority = slash < 0 ? normalized.substring(prefix.length())
                : normalized.substring(prefix.length(), slash);
        if (!expectedAuthority.equals(authority)) {
            throw new SecurityException("PROVIDER_OBSERVER_URI_AUTHORITY_MISMATCH");
        }
        return normalized;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
    }
}
