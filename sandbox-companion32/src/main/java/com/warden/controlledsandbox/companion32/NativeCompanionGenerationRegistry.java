package com.warden.controlledsandbox.companion32;

import com.warden.controlledsandbox.contract.NativeCompanionRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded in-process ownership registry for prepared 32-bit Guest generations. */
final class NativeCompanionGenerationRegistry {
    private final int maximum;
    private final Map<String, PreparedGeneration> prepared = new LinkedHashMap<>();

    NativeCompanionGenerationRegistry(int maximum) {
        if (maximum < 1 || maximum > 4096) throw new IllegalArgumentException("invalid maximum");
        this.maximum = maximum;
    }

    synchronized String accept(NativeCompanionRequest request) {
        if (!NativeCompanionRequest.OP_PREPARE_GENERATION.equals(request.operation())) return "";
        String key = request.packageName() + "\n" + request.virtualUserId();
        PreparedGeneration current = prepared.get(key);
        if (current != null && request.generation() < current.generation) return "STALE_GENERATION";
        if (current != null && request.generation() == current.generation
                && (!current.sessionId.equals(request.sessionId())
                || !current.packageRevision.equals(request.packageRevision())
                || !current.abi.equals(request.requestedAbi()))) {
            return "GENERATION_IDENTITY_MISMATCH";
        }
        if (!prepared.containsKey(key) && prepared.size() >= maximum) {
            prepared.remove(prepared.keySet().iterator().next());
        }
        prepared.put(key, new PreparedGeneration(request.sessionId(), request.generation(),
                request.packageRevision(), request.requestedAbi()));
        return "";
    }

    synchronized int size() { return prepared.size(); }

    private record PreparedGeneration(String sessionId, long generation,
                                      String packageRevision, String abi) { }
}
