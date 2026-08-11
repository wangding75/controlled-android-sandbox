package com.warden.controlledsandbox.runtime.guest;

import android.content.BroadcastReceiver;
import android.os.Handler;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Process-local identity registry preserving dynamically registered Receiver objects and schedulers. */
final class GuestDynamicReceiverRegistry {
    private final Map<String, Record> byId = new LinkedHashMap<>();
    private final IdentityHashMap<BroadcastReceiver, LinkedHashSet<String>> byReceiver =
            new IdentityHashMap<>();

    synchronized String reserve(BroadcastReceiver receiver, Handler scheduler) {
        if (receiver == null) throw new IllegalArgumentException("receiver is required");
        String id = java.util.UUID.randomUUID().toString();
        byReceiver.computeIfAbsent(receiver, ignored -> new LinkedHashSet<>()).add(id);
        byId.put(id, new Record(receiver, scheduler));
        return id;
    }

    synchronized BroadcastReceiver require(String id) {
        Record record = byId.get(id);
        if (record == null) throw new IllegalArgumentException("UNKNOWN_RECEIVER_ID");
        return record.receiver;
    }

    synchronized Handler scheduler(String id) {
        Record record = byId.get(id);
        if (record == null) throw new IllegalArgumentException("UNKNOWN_RECEIVER_ID");
        return record.scheduler;
    }

    synchronized String id(BroadcastReceiver receiver) {
        List<String> ids = ids(receiver);
        return ids.get(ids.size() - 1);
    }

    synchronized List<String> ids(BroadcastReceiver receiver) {
        LinkedHashSet<String> ids = byReceiver.get(receiver);
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("RECEIVER_NOT_REGISTERED");
        }
        return List.copyOf(ids);
    }

    synchronized void rollback(String id) {
        Record record = byId.remove(id);
        if (record == null) return;
        LinkedHashSet<String> ids = byReceiver.get(record.receiver);
        if (ids != null) {
            ids.remove(id);
            if (ids.isEmpty()) byReceiver.remove(record.receiver);
        }
    }

    synchronized void remove(String id) { rollback(id); }

    synchronized void clear() {
        byId.clear();
        byReceiver.clear();
    }

    private static final class Record {
        final BroadcastReceiver receiver;
        final Handler scheduler;
        Record(BroadcastReceiver receiver, Handler scheduler) {
            this.receiver = receiver;
            this.scheduler = scheduler;
        }
    }
}
