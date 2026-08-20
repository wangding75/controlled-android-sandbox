package com.warden.controlledsandbox.runtime.component.activity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Bounded physical Activity identity allocator.
 *
 * <p>A physical window is held for the whole lifetime of its virtual Activity mapping.  The
 * allocator deliberately scans the fixed pool instead of wrapping a counter: a full pool is an
 * explicit launch failure, never an alias or an overwrite of a live ActivityRecord.</p>
 */
final class PhysicalActivityIdentityAllocator {
    record Assignment(int processSlot, int window) { }
    private final int poolSize;
    private final Map<String, Integer> windowsByActivity = new HashMap<>();
    private final Map<String, Integer> processSlotsByActivity = new HashMap<>();

    PhysicalActivityIdentityAllocator(int poolSize) {
        if (poolSize < 1) throw new IllegalArgumentException("poolSize must be positive");
        this.poolSize = poolSize;
    }

    int allocate(int processSlot, String activityToken) {
        requireActivityToken(activityToken);
        Integer existing = windowsByActivity.get(activityToken);
        if (existing != null) return existing;
        Set<Integer> used = usedWindows(processSlot);
        for (int window = 0; window < poolSize; window++) {
            if (used.contains(window)) continue;
            windowsByActivity.put(activityToken, window);
            processSlotsByActivity.put(activityToken, processSlot);
            return window;
        }
        throw new IllegalStateException("PHYSICAL_ACTIVITY_IDENTITY_POOL_EXHAUSTED:"
                + processSlot + ":" + poolSize);
    }

    Integer windowFor(String activityToken) {
        return windowsByActivity.get(activityToken);
    }

    void release(String activityToken) {
        if (activityToken == null) return;
        windowsByActivity.remove(activityToken);
        processSlotsByActivity.remove(activityToken);
    }

    void rebind(String oldActivityToken, String newActivityToken) {
        requireActivityToken(oldActivityToken);
        requireActivityToken(newActivityToken);
        Integer window = windowsByActivity.get(oldActivityToken);
        Integer processSlot = processSlotsByActivity.get(oldActivityToken);
        if (window == null || processSlot == null) {
            throw new IllegalStateException("PHYSICAL_ACTIVITY_IDENTITY_MISSING:" + oldActivityToken);
        }
        if (windowsByActivity.containsKey(newActivityToken)) {
            throw new IllegalStateException("PHYSICAL_ACTIVITY_IDENTITY_COLLISION:" + newActivityToken);
        }
        windowsByActivity.remove(oldActivityToken);
        processSlotsByActivity.remove(oldActivityToken);
        windowsByActivity.put(newActivityToken, window);
        processSlotsByActivity.put(newActivityToken, processSlot);
    }

    int liveCount() { return windowsByActivity.size(); }

    Map<String, Assignment> snapshot() {
        Map<String, Assignment> copy = new HashMap<>();
        for (Map.Entry<String, Integer> entry : windowsByActivity.entrySet()) {
            copy.put(entry.getKey(), new Assignment(
                    processSlotsByActivity.get(entry.getKey()), entry.getValue()));
        }
        return copy;
    }

    void restore(Map<String, Assignment> snapshot) {
        windowsByActivity.clear();
        processSlotsByActivity.clear();
        if (snapshot == null) return;
        for (Map.Entry<String, Assignment> entry : snapshot.entrySet()) {
            Assignment assignment = entry.getValue();
            if (assignment == null || assignment.processSlot() < 0
                    || assignment.window() < 0 || assignment.window() >= poolSize) {
                throw new IllegalArgumentException("invalid physical identity snapshot");
            }
            if (usedWindows(assignment.processSlot()).contains(assignment.window())) {
                throw new IllegalArgumentException("physical identity snapshot collision");
            }
            windowsByActivity.put(entry.getKey(), assignment.window());
            processSlotsByActivity.put(entry.getKey(), assignment.processSlot());
        }
    }

    boolean hasCollision() {
        Set<String> identities = new HashSet<>();
        for (Map.Entry<String, Integer> entry : windowsByActivity.entrySet()) {
            String identity = processSlotsByActivity.get(entry.getKey()) + ":" + entry.getValue();
            if (!identities.add(identity)) return true;
        }
        return false;
    }

    private Set<Integer> usedWindows(int processSlot) {
        Set<Integer> used = new HashSet<>();
        for (Map.Entry<String, Integer> entry : windowsByActivity.entrySet()) {
            if (Integer.valueOf(processSlot).equals(processSlotsByActivity.get(entry.getKey()))) {
                used.add(entry.getValue());
            }
        }
        return used;
    }

    private static void requireActivityToken(String activityToken) {
        if (activityToken == null || activityToken.isBlank()) {
            throw new IllegalArgumentException("activityToken must not be blank");
        }
    }
}
