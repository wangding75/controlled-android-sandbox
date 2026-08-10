package com.warden.controlledsandbox.domain.component.receiver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Session-owned dynamic receiver registrations with deterministic action resolution. */
public final class DynamicReceiverRegistry {
    public static final int MAX_REGISTRATIONS = 4096;
    public static final int MAX_ACTIONS_PER_REGISTRATION = 128;
    public static final class Registration {
        private final String id;
        private final String packageName;
        private final String sessionId;
        private final long generation;
        private final int virtualUserId;
        private final String receiverClass;
        private final ManifestReceiverRegistry.Filter filter;
        private final String requiredSenderPermission;
        private final boolean exported;

        private Registration(String id, String packageName, String sessionId, long generation,
                             int virtualUserId, String receiverClass,
                             ManifestReceiverRegistry.Filter filter, String requiredSenderPermission,
                             boolean exported) {
            this.id = id;
            this.packageName = packageName;
            this.sessionId = sessionId;
            this.generation = generation;
            this.virtualUserId = virtualUserId;
            this.receiverClass = receiverClass;
            this.filter = java.util.Objects.requireNonNull(filter, "filter");
            this.requiredSenderPermission = normalize(requiredSenderPermission);
            this.exported = exported;
        }

        public String id() { return id; }
        public String packageName() { return packageName; }
        public String sessionId() { return sessionId; }
        public long generation() { return generation; }
        public int virtualUserId() { return virtualUserId; }
        public String receiverClass() { return receiverClass; }
        public Set<String> actions() { return filter.actions(); }
        public Set<String> categories() { return filter.categories(); }
        public java.util.List<ManifestReceiverRegistry.DataRule> dataRules() { return filter.dataRules(); }
        public int priority() { return filter.priority(); }
        public String requiredSenderPermission() { return requiredSenderPermission; }
        public boolean exported() { return exported; }
        boolean matches(BroadcastIntent intent) { return filter.matches(intent); }
    }

    public record Snapshot(int registrations, int actionSubscriptions) {
        public Snapshot {
            if (registrations < 0 || actionSubscriptions < 0) {
                throw new IllegalArgumentException("receiver snapshot counts must be non-negative");
            }
        }
    }

    private final Map<String, Registration> registrations = new LinkedHashMap<>();

    public synchronized Registration register(String id, String packageName, String sessionId,
                                              long generation, int virtualUserId,
                                              String receiverClass, Collection<String> actions,
                                              boolean exported) {
        LinkedHashSet<String> normalized = normalizedActions(actions);
        return register(id, packageName, sessionId, generation, virtualUserId, receiverClass,
                new ManifestReceiverRegistry.Filter(0, normalized, Collections.emptySet(),
                        Collections.emptyList()), "", exported);
    }

    public synchronized Registration register(String id, String packageName, String sessionId,
                                              long generation, int virtualUserId,
                                              String receiverClass,
                                              ManifestReceiverRegistry.Filter filter,
                                              boolean exported) {
        return register(id, packageName, sessionId, generation, virtualUserId, receiverClass,
                filter, "", exported);
    }

    public synchronized Registration register(String id, String packageName, String sessionId,
                                              long generation, int virtualUserId,
                                              String receiverClass,
                                              ManifestReceiverRegistry.Filter filter,
                                              String requiredSenderPermission, boolean exported) {
        requireText(id, "id");
        requireText(packageName, "packageName");
        requireText(sessionId, "sessionId");
        requireText(receiverClass, "receiverClass");
        if (generation < 1 || virtualUserId < 0) throw new IllegalArgumentException("invalid registration owner");
        if (filter == null) throw new IllegalArgumentException("filter is required");
        // Android permits an empty IntentFilter for an inert, non-delivering registration.
        // Keep it in the lifecycle registry, but Filter.matches() will never resolve it because
        // no action can be contained in the empty action set.
        String ownerKey = key(sessionId, generation, id);
        if (registrations.containsKey(ownerKey)) {
            throw new IllegalStateException("DUPLICATE_RECEIVER_REGISTRATION");
        }
        if (registrations.size() >= MAX_REGISTRATIONS) {
            throw new IllegalStateException("DYNAMIC_RECEIVER_CAPACITY_EXCEEDED");
        }
        if (filter.actions().size() > MAX_ACTIONS_PER_REGISTRATION) {
            throw new IllegalArgumentException("DYNAMIC_RECEIVER_ACTION_LIMIT_EXCEEDED");
        }
        Registration registration = new Registration(id, packageName.trim(), sessionId, generation,
                virtualUserId, receiverClass, filter, requiredSenderPermission, exported);
        registrations.put(ownerKey, registration);
        return registration;
    }

    public synchronized Registration requireOwned(String id, String sessionId, long generation) {
        requireText(id, "id");
        requireText(sessionId, "sessionId");
        if (generation < 1) throw new IllegalArgumentException("generation must be positive");
        Registration registration = registrations.get(key(sessionId, generation, id));
        if (registration != null) return registration;
        for (Registration candidate : registrations.values()) {
            if (candidate.id.equals(id)) throw new SecurityException("RECEIVER_OWNER_MISMATCH");
        }
        throw new IllegalArgumentException("UNKNOWN_RECEIVER_REGISTRATION");
    }

    public synchronized Registration unregister(String id, String sessionId, long generation) {
        Registration registration = requireOwned(id, sessionId, generation);
        registrations.remove(key(sessionId, generation, id));
        return registration;
    }

    public synchronized List<Registration> resolve(String action, int virtualUserId,
                                                   String senderSessionId, boolean externalBroadcast) {
        return resolve(new BroadcastIntent(action, Collections.emptySet(), "", "", "", ""),
                virtualUserId, senderSessionId, externalBroadcast);
    }

    public synchronized List<Registration> resolve(BroadcastIntent intent, int virtualUserId,
                                                   String senderSessionId, boolean externalBroadcast) {
        if (intent == null) throw new IllegalArgumentException("intent is required");
        List<Registration> out = new ArrayList<>();
        for (Registration registration : registrations.values()) {
            if (registration.virtualUserId != virtualUserId || !registration.matches(intent)) continue;
            if (externalBroadcast && !registration.exported) continue;
            if (!externalBroadcast && senderSessionId != null && !senderSessionId.isEmpty()
                    && !registration.sessionId.equals(senderSessionId) && !registration.exported) continue;
            out.add(registration);
        }
        out.sort(java.util.Comparator.comparingInt(Registration::priority).reversed()
                .thenComparing(Registration::packageName)
                .thenComparing(Registration::id));
        return Collections.unmodifiableList(out);
    }

    public synchronized int removeSession(String sessionId, long generation) {
        return removeMatching(registration -> registration.sessionId.equals(sessionId)
                && registration.generation == generation);
    }

    public synchronized int removeInstance(String packageName, int virtualUserId) {
        requireText(packageName, "packageName");
        if (virtualUserId < 0) throw new IllegalArgumentException("virtualUserId must be non-negative");
        String normalized = packageName.trim();
        return removeMatching(registration -> registration.packageName.equals(normalized)
                && registration.virtualUserId == virtualUserId);
    }

    public synchronized int clear() {
        int count = registrations.size();
        registrations.clear();
        return count;
    }

    public synchronized int size() { return registrations.size(); }

    public synchronized Snapshot snapshot() {
        int subscriptions = 0;
        for (Registration registration : registrations.values()) subscriptions += registration.actions().size();
        return new Snapshot(registrations.size(), subscriptions);
    }

    private int removeMatching(Matcher matcher) {
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, Registration> entry : registrations.entrySet()) {
            if (matcher.matches(entry.getValue())) keys.add(entry.getKey());
        }
        for (String key : keys) registrations.remove(key);
        return keys.size();
    }

    private static LinkedHashSet<String> normalizedActions(Collection<String> actions) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (actions != null) {
            for (String action : actions) {
                if (action != null && !action.trim().isEmpty()) normalized.add(action.trim());
            }
        }
        if (normalized.size() > MAX_ACTIONS_PER_REGISTRATION) {
            throw new IllegalArgumentException("DYNAMIC_RECEIVER_ACTION_LIMIT_EXCEEDED");
        }
        return normalized;
    }

    private static String key(String sessionId, long generation, String id) {
        return sessionId.length() + ":" + sessionId + ":" + generation + ":" + id;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
    }

    private interface Matcher { boolean matches(Registration registration); }
}
