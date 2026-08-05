package com.warden.controlledsandbox.runtime.guest;

import android.content.BroadcastReceiver;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Process-local identity registry preserving dynamically registered Receiver objects. */
final class GuestDynamicReceiverRegistry {
    private final Map<String, BroadcastReceiver> byId = new LinkedHashMap<>();
    private final IdentityHashMap<BroadcastReceiver, String> byReceiver = new IdentityHashMap<>();

    synchronized String reserve(BroadcastReceiver receiver) {
        if (receiver == null) throw new IllegalArgumentException("receiver is required");
        if (byReceiver.containsKey(receiver)) throw new IllegalStateException("RECEIVER_ALREADY_REGISTERED");
        String id = java.util.UUID.randomUUID().toString();
        byReceiver.put(receiver, id);
        byId.put(id, receiver);
        return id;
    }

    synchronized BroadcastReceiver require(String id) {
        BroadcastReceiver receiver = byId.get(id);
        if (receiver == null) throw new IllegalArgumentException("UNKNOWN_RECEIVER_ID");
        return receiver;
    }

    synchronized String id(BroadcastReceiver receiver) {
        String id = byReceiver.get(receiver);
        if (id == null) throw new IllegalArgumentException("RECEIVER_NOT_REGISTERED");
        return id;
    }

    synchronized void rollback(String id) {
        BroadcastReceiver receiver = byId.remove(id);
        if (receiver != null) byReceiver.remove(receiver);
    }

    synchronized void remove(String id) { rollback(id); }

    synchronized void clear() {
        byId.clear();
        byReceiver.clear();
    }
}
