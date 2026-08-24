package com.warden.controlledsandbox;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Process-authoritative package/user single-flight registry. */
final class PackageMutationCoordinator {
    private static final int MAX_RECENT = 128;
    private final Map<String, PackageMutationTrace> active = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> recent = new LinkedHashMap<>();

    synchronized Start begin(String requestId, String operation, String packageName,
            int virtualUserId) {
        String normalizedPackage = required(packageName, "packageName");
        String normalizedRequest = requestId == null || requestId.trim().isEmpty()
                ? UUID.randomUUID().toString() : requestId.trim();
        String key = key(normalizedPackage, virtualUserId);
        PackageMutationTrace inFlight = active.get(key);
        if (inFlight != null) return Start.busy(inFlight);
        PackageMutationTrace trace = new PackageMutationTrace(normalizedRequest, operation,
                normalizedPackage, virtualUserId);
        active.put(key, trace);
        return Start.owner(trace);
    }

    synchronized void complete(PackageMutationTrace trace) {
        if (trace == null) return;
        active.remove(key(trace.packageName(), trace.virtualUserId()), trace);
        recent.put(trace.requestId(), trace.toJson());
        while (recent.size() > MAX_RECENT) {
            recent.remove(recent.keySet().iterator().next());
        }
    }

    synchronized String snapshot(String requestId) {
        if (requestId == null) return "";
        for (PackageMutationTrace trace : active.values()) {
            if (requestId.equals(trace.requestId())) return trace.toJson();
        }
        return recent.getOrDefault(requestId, "");
    }

    synchronized int activeCount() { return active.size(); }

    private static String key(String packageName, int virtualUserId) {
        return packageName + "\u0000" + virtualUserId;
    }

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    static final class Start {
        final boolean owner;
        final PackageMutationTrace trace;
        private Start(boolean owner, PackageMutationTrace trace) {
            this.owner = owner;
            this.trace = trace;
        }
        static Start owner(PackageMutationTrace trace) { return new Start(true, trace); }
        static Start busy(PackageMutationTrace trace) { return new Start(false, trace); }
    }
}
