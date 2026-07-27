package com.warden.controlledsandbox.domain.component.provider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Broker-owned, session-bound URI permission grants. */
public final class UriGrantRegistry {
    public static final int READ = 1;
    public static final int WRITE = 1 << 1;
    public static final int MAX_ACTIVE_GRANTS = 256;
    public static final long MAX_TTL_MS = 86_400_000L;

    public static final class Grant {
        private final String id;
        private final String ownerInstanceId;
        private final String ownerSessionId;
        private final long ownerGeneration;
        private final String targetInstanceId;
        private final String targetSessionId;
        private final long targetGeneration;
        private final int virtualUserId;
        private final String uriPrefix;
        private final int flags;
        private final boolean oneTime;
        private final long expiresAtMs;

        private Grant(String id, String ownerInstanceId, String ownerSessionId, long ownerGeneration,
                      String targetInstanceId, String targetSessionId, long targetGeneration,
                      int virtualUserId, String uriPrefix, int flags, boolean oneTime,
                      long expiresAtMs) {
            this.id = id;
            this.ownerInstanceId = ownerInstanceId;
            this.ownerSessionId = ownerSessionId;
            this.ownerGeneration = ownerGeneration;
            this.targetInstanceId = targetInstanceId;
            this.targetSessionId = targetSessionId;
            this.targetGeneration = targetGeneration;
            this.virtualUserId = virtualUserId;
            this.uriPrefix = uriPrefix;
            this.flags = flags;
            this.oneTime = oneTime;
            this.expiresAtMs = expiresAtMs;
        }

        public String id() { return id; }
        public String ownerInstanceId() { return ownerInstanceId; }
        public String ownerSessionId() { return ownerSessionId; }
        public long ownerGeneration() { return ownerGeneration; }
        public String targetInstanceId() { return targetInstanceId; }
        public String targetSessionId() { return targetSessionId; }
        public long targetGeneration() { return targetGeneration; }
        public int virtualUserId() { return virtualUserId; }
        public String uriPrefix() { return uriPrefix; }
        public int flags() { return flags; }
        public boolean oneTime() { return oneTime; }
        public long expiresAtMs() { return expiresAtMs; }
    }

    public static final class AuthorizationResult {
        private final List<String> grantIds;
        private final boolean oneTimeConsumed;

        private AuthorizationResult(List<String> grantIds, boolean oneTimeConsumed) {
            this.grantIds = Collections.unmodifiableList(new ArrayList<>(grantIds));
            this.oneTimeConsumed = oneTimeConsumed;
        }

        public List<String> grantIds() { return grantIds; }
        public boolean oneTimeConsumed() { return oneTimeConsumed; }
    }

    public final class Authorization {
        private final String targetInstanceId;
        private final String targetSessionId;
        private final long targetGeneration;
        private final int virtualUserId;
        private final long nowMs;
        private final List<Requirement> requirements = new ArrayList<>();
        private boolean committed;

        private Authorization(String targetInstanceId, String targetSessionId, long targetGeneration,
                              int virtualUserId, long nowMs) {
            this.targetInstanceId = requireText(targetInstanceId, "targetInstanceId");
            this.targetSessionId = requireText(targetSessionId, "targetSessionId");
            if (targetGeneration < 1) throw new IllegalArgumentException("targetGeneration must be positive");
            if (virtualUserId < 0) throw new IllegalArgumentException("virtualUserId must be non-negative");
            this.targetGeneration = targetGeneration;
            this.virtualUserId = virtualUserId;
            this.nowMs = nowMs;
        }

        public boolean allows(String ignoredTargetInstance, String uri, int flags) {
            if (!targetInstanceId.equals(ignoredTargetInstance)) return false;
            Requirement requirement = new Requirement(normalize(uri), requireFlags(flags));
            synchronized (UriGrantRegistry.this) {
                purgeExpired(nowMs);
                if (!hasMatchingGrant(targetInstanceId, targetSessionId, targetGeneration,
                        virtualUserId, requirement, false, null)) return false;
            }
            requirements.add(requirement);
            return true;
        }

        public AuthorizationResult commit(long currentNowMs) {
            if (committed) throw new IllegalStateException("URI_GRANT_AUTHORIZATION_ALREADY_COMMITTED");
            if (currentNowMs < nowMs) throw new IllegalArgumentException("authorization time moved backwards");
            committed = true;
            return commitAuthorization(this, currentNowMs);
        }
    }

    private static final class Requirement {
        private final String uri;
        private final int flags;

        private Requirement(String uri, int flags) {
            this.uri = uri;
            this.flags = flags;
        }
    }

    private final Map<String, Grant> grants = new LinkedHashMap<>();

    public synchronized Grant grant(String ownerInstanceId, String ownerSessionId, long ownerGeneration,
                                    String targetInstanceId, String targetSessionId, long targetGeneration,
                                    int virtualUserId, String uriPrefix, int flags, boolean oneTime,
                                    long nowMs, long ttlMs) {
        requireText(ownerInstanceId, "ownerInstanceId");
        requireText(ownerSessionId, "ownerSessionId");
        requireText(targetInstanceId, "targetInstanceId");
        requireText(targetSessionId, "targetSessionId");
        if (ownerGeneration < 1 || targetGeneration < 1) {
            throw new IllegalArgumentException("generations must be positive");
        }
        if (virtualUserId < 0) throw new IllegalArgumentException("virtualUserId must be non-negative");
        requireFlags(flags);
        if (ttlMs < 1 || ttlMs > MAX_TTL_MS) throw new IllegalArgumentException("invalid URI grant ttlMs");
        purgeExpired(nowMs);
        if (grants.size() >= MAX_ACTIVE_GRANTS) throw new IllegalStateException("URI_GRANT_CAPACITY_EXHAUSTED");
        String id;
        do { id = UUID.randomUUID().toString(); } while (grants.containsKey(id));
        Grant grant = new Grant(id, ownerInstanceId, ownerSessionId, ownerGeneration,
                targetInstanceId, targetSessionId, targetGeneration, virtualUserId,
                normalize(uriPrefix), flags, oneTime, Math.addExact(nowMs, ttlMs));
        grants.put(id, grant);
        return grant;
    }

    public Authorization beginAuthorization(String targetInstanceId, String targetSessionId,
                                            long targetGeneration, int virtualUserId, long nowMs) {
        return new Authorization(targetInstanceId, targetSessionId, targetGeneration, virtualUserId, nowMs);
    }

    public synchronized Grant require(String grantId, long nowMs) {
        purgeExpired(nowMs);
        Grant grant = grants.get(requireText(grantId, "grantId"));
        if (grant == null) throw new IllegalArgumentException("UNKNOWN_URI_GRANT");
        return grant;
    }

    public synchronized boolean revoke(String grantId, String ownerInstanceId,
                                       String ownerSessionId, long ownerGeneration, long nowMs) {
        purgeExpired(nowMs);
        Grant grant = grants.get(requireText(grantId, "grantId"));
        if (grant == null) return false;
        if (!grant.ownerInstanceId.equals(requireText(ownerInstanceId, "ownerInstanceId"))) {
            throw new SecurityException("URI_GRANT_OWNER_MISMATCH");
        }
        if (!grant.ownerSessionId.equals(requireText(ownerSessionId, "ownerSessionId"))
                || grant.ownerGeneration != ownerGeneration) {
            throw new SecurityException("URI_GRANT_OWNER_SESSION_MISMATCH");
        }
        grants.remove(grantId);
        return true;
    }

    public synchronized int revokeSession(String sessionId, long generation) {
        requireText(sessionId, "sessionId");
        List<String> ids = new ArrayList<>();
        for (Grant grant : grants.values()) {
            if ((grant.ownerSessionId.equals(sessionId) && grant.ownerGeneration == generation)
                    || (grant.targetSessionId.equals(sessionId) && grant.targetGeneration == generation)) {
                ids.add(grant.id);
            }
        }
        for (String id : ids) grants.remove(id);
        return ids.size();
    }

    public synchronized int revokeInstance(String instanceId) {
        requireText(instanceId, "instanceId");
        List<String> ids = new ArrayList<>();
        for (Grant grant : grants.values()) {
            if (grant.ownerInstanceId.equals(instanceId) || grant.targetInstanceId.equals(instanceId)) ids.add(grant.id);
        }
        for (String id : ids) grants.remove(id);
        return ids.size();
    }

    public synchronized int purgeExpiredGrants(long nowMs) {
        return purgeExpired(nowMs);
    }

    public synchronized int size(long nowMs) {
        purgeExpired(nowMs);
        return grants.size();
    }

    public synchronized List<Grant> snapshot(long nowMs) {
        purgeExpired(nowMs);
        return Collections.unmodifiableList(new ArrayList<>(grants.values()));
    }

    private synchronized AuthorizationResult commitAuthorization(Authorization authorization, long currentNowMs) {
        purgeExpired(currentNowMs);
        if (authorization.requirements.isEmpty()) {
            throw new SecurityException("URI_GRANT_AUTHORIZATION_HAS_NO_REQUIREMENTS");
        }
        Set<String> selected = new LinkedHashSet<>();
        for (Requirement requirement : authorization.requirements) {
            Grant match = findMatchingGrant(authorization.targetInstanceId, authorization.targetSessionId,
                    authorization.targetGeneration, authorization.virtualUserId, requirement, true, selected);
            if (match == null) {
                throw new SecurityException("URI_PERMISSION_REVOKED_EXPIRED_OR_CONSUMED");
            }
            selected.add(match.id);
        }
        boolean oneTimeConsumed = false;
        for (String id : selected) {
            Grant grant = grants.get(id);
            if (grant != null && grant.oneTime) {
                grants.remove(id);
                oneTimeConsumed = true;
            }
        }
        return new AuthorizationResult(new ArrayList<>(selected), oneTimeConsumed);
    }

    private boolean hasMatchingGrant(String targetInstanceId, String targetSessionId,
                                     long targetGeneration, int virtualUserId, Requirement requirement,
                                     boolean allowSelectedOneTime, Set<String> selected) {
        return findMatchingGrant(targetInstanceId, targetSessionId, targetGeneration, virtualUserId,
                requirement, allowSelectedOneTime, selected) != null;
    }

    private Grant findMatchingGrant(String targetInstanceId, String targetSessionId,
                                    long targetGeneration, int virtualUserId, Requirement requirement,
                                    boolean allowSelectedOneTime, Set<String> selected) {
        Grant oneTime = null;
        for (Grant grant : grants.values()) {
            if (!matchesIdentity(grant, targetInstanceId, targetSessionId, targetGeneration, virtualUserId)) continue;
            if (!covers(grant, requirement)) continue;
            if (!grant.oneTime) return grant;
            if (allowSelectedOneTime && selected != null && selected.contains(grant.id)) return grant;
            if (oneTime == null) oneTime = grant;
        }
        return oneTime;
    }

    private static boolean matchesIdentity(Grant grant, String targetInstanceId, String targetSessionId,
                                           long targetGeneration, int virtualUserId) {
        return grant.targetInstanceId.equals(targetInstanceId)
                && grant.targetSessionId.equals(targetSessionId)
                && grant.targetGeneration == targetGeneration
                && grant.virtualUserId == virtualUserId;
    }

    private static boolean covers(Grant grant, Requirement requirement) {
        if ((grant.flags & requirement.flags) != requirement.flags) return false;
        return requirement.uri.equals(grant.uriPrefix) || requirement.uri.startsWith(grant.uriPrefix + "/");
    }

    private int purgeExpired(long nowMs) {
        List<String> ids = new ArrayList<>();
        for (Grant grant : grants.values()) if (grant.expiresAtMs <= nowMs) ids.add(grant.id);
        for (String id : ids) grants.remove(id);
        return ids.size();
    }

    private static String normalize(String uri) {
        String normalized = requireText(uri, "uri").trim();
        if (!normalized.startsWith("content://")) throw new IllegalArgumentException("URI_GRANT_REQUIRES_CONTENT_URI");
        while (normalized.endsWith("/") && normalized.length() > "content://x".length()) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static int requireFlags(int flags) {
        if ((flags & ~(READ | WRITE)) != 0 || flags == 0) throw new IllegalArgumentException("invalid URI grant flags");
        return flags;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
}
