package com.warden.controlledsandbox.runtime.guest;

import android.content.BroadcastReceiver;
import android.os.Handler;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Process-local identity registry preserving dynamically registered Receiver objects and schedulers. */
final class GuestDynamicReceiverRegistry {
    private final Map<String, Record> byId = new LinkedHashMap<>();
    private final IdentityHashMap<BroadcastReceiver, String> byReceiver = new IdentityHashMap<>();

    synchronized String reserve(BroadcastReceiver receiver, Handler scheduler) {
        if (receiver == null) throw new IllegalArgumentException("receiver is required");
        if (byReceiver.containsKey(receiver)) throw new IllegalStateException("RECEIVER_ALREADY_REGISTERED");
        String id = java.util.UUID.randomUUID().toString();
        byReceiver.put(receiver, id);
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
        String id = byReceiver.get(receiver);
        if (id == null) throw new IllegalArgumentException("RECEIVER_NOT_REGISTERED");
        return id;
    }

    synchronized void rollback(String id) {
        Record record = byId.remove(id);
        if (record != null) byReceiver.remove(record.receiver);
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
