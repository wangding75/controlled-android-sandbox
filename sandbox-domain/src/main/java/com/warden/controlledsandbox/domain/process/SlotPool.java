package com.warden.controlledsandbox.domain.process;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Thread-safe fixed process slot allocator. */
public final class SlotPool {
    private final String[] owners;
    private final Map<String, Integer> byOwner = new HashMap<>();

    public SlotPool(int size) {
        if (size < 1 || size > 128) throw new IllegalArgumentException("size must be between 1 and 128");
        owners = new String[size];
    }

    public synchronized int reserve(String packageName, int virtualUserId) {
        String owner = key(packageName, virtualUserId);
        Integer existing = byOwner.get(owner);
        if (existing != null) return existing;
        int preferred = Math.floorMod(owner.hashCode(), owners.length);
        for (int distance = 0; distance < owners.length; distance++) {
            int slot = (preferred + distance) % owners.length;
            if (owners[slot] == null) {
                owners[slot] = owner;
                byOwner.put(owner, slot);
                return slot;
            }
        }
        return -1;
    }

    public synchronized void release(String packageName, int virtualUserId) {
        String owner = key(packageName, virtualUserId);
        Integer slot = byOwner.remove(owner);
        if (slot != null && owner.equals(owners[slot])) owners[slot] = null;
    }

    public synchronized void releaseSlot(int slot) {
        if (slot < 0 || slot >= owners.length) return;
        String owner = owners[slot];
        if (owner != null) byOwner.remove(owner);
        owners[slot] = null;
    }

    public synchronized String ownerOf(int slot) {
        if (slot < 0 || slot >= owners.length) return "";
        return owners[slot] == null ? "" : owners[slot];
    }

    public synchronized int capacity() { return owners.length; }
    public synchronized int used() { return byOwner.size(); }
    public synchronized String[] snapshot() { return Arrays.copyOf(owners, owners.length); }

    private static String key(String packageName, int virtualUserId) {
        if (packageName == null || packageName.trim().isEmpty()) throw new IllegalArgumentException("packageName is required");
        if (virtualUserId < 0) throw new IllegalArgumentException("virtualUserId must be non-negative");
        return virtualUserId + ":" + packageName;
    }
}
