package com.warden.controlledsandbox.runtime.provider;

import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.os.Bundle;
import android.os.IBinder;
import com.warden.controlledsandbox.contract.IProviderObserver;
import com.warden.controlledsandbox.contract.internal.DeathRegistrationHelper;
import com.warden.controlledsandbox.domain.component.provider.ProviderObserverRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Broker-owned ContentObserver registrations and callback delivery. */
public final class BrokerObserverRuntime {
    static final int MAX_ACTIVE_OBSERVERS = 256;

    public static final class RegisterResult {
        private final ProviderObserverRegistry.Entry entry;
        private final boolean created;

        private RegisterResult(ProviderObserverRegistry.Entry entry, boolean created) {
            this.entry = entry;
            this.created = created;
        }

        public ProviderObserverRegistry.Entry entry() { return entry; }
        public boolean created() { return created; }
    }

    public static final class NotifyResult {
        private final int matched;
        private final int delivered;
        private final List<String> failures;

        private NotifyResult(int matched, int delivered, List<String> failures) {
            this.matched = matched;
            this.delivered = delivered;
            this.failures = failures;
        }

        public int matched() { return matched; }
        public int delivered() { return delivered; }
        public List<String> failures() { return failures; }
    }

    private static final class CallbackRecord {
        private final IProviderObserver callback;
        private final IBinder binder;
        private final DeathRegistrationHelper deathRegistration;

        private CallbackRecord(IProviderObserver callback, IBinder binder, Runnable deathAction) {
            this.callback = callback;
            this.binder = binder;
            this.deathRegistration = new DeathRegistrationHelper(binder, deathAction);
        }
    }

    private final ProviderObserverRegistry registry = new ProviderObserverRegistry();
    private final Map<String, CallbackRecord> callbacks = new LinkedHashMap<>();

    public synchronized RegisterResult register(Bundle request, String callerInstance, String callerSessionId,
                                         long callerGeneration, String targetInstance,
                                         String targetSessionId, long targetGeneration,
                                         int virtualUserId, String authority, String uri) {
        IBinder binder = request.getBinder(RuntimeKeys.OBSERVER_CALLBACK);
        if (binder == null) throw new IllegalArgumentException(RuntimeKeys.OBSERVER_CALLBACK + " is required");
        IProviderObserver callback = IProviderObserver.Stub.asInterface(binder);
        if (callback == null) throw new IllegalArgumentException("INVALID_PROVIDER_OBSERVER_CALLBACK");
        String requestedId = request.getString(RuntimeKeys.OBSERVER_ID, "");
        String id = requestedId == null || requestedId.trim().isEmpty()
                ? UUID.randomUUID().toString() : requestedId.trim();
        if (registry.get(id) == null && callbacks.size() >= MAX_ACTIVE_OBSERVERS) {
            throw new IllegalStateException("PROVIDER_OBSERVER_CAPACITY_EXHAUSTED");
        }
        boolean descendants = request.getBoolean(RuntimeKeys.OBSERVER_NOTIFY_DESCENDANTS, false);
        boolean deliverSelf = request.getBoolean(RuntimeKeys.OBSERVER_DELIVER_SELF, false);

        ProviderObserverRegistry.Registration registration = registry.register(id, callerInstance,
                virtualUserId, callerSessionId, callerGeneration, targetInstance, targetSessionId,
                targetGeneration, authority, uri, descendants, deliverSelf);
        CallbackRecord existing = callbacks.get(id);
        if (!registration.created()) {
            if (existing == null || existing.binder != binder) {
                throw new SecurityException("PROVIDER_OBSERVER_CALLBACK_CONFLICT");
            }
            return new RegisterResult(registration.entry(), false);
        }

        CallbackRecord record = new CallbackRecord(callback, binder, () -> removeDead(id, binder));
        callbacks.put(id, record);
        try {
            boolean linked = record.deathRegistration.linkAfterReservation();
            if (!linked || callbacks.get(id) != record
                    || !record.deathRegistration.linkedAndAlive()) {
                removeDead(id, binder);
                throw new IllegalStateException("PROVIDER_OBSERVER_CALLBACK_DEAD_DURING_LINK");
            }
        } catch (android.os.RemoteException error) {
            removeDead(id, binder);
            throw new IllegalStateException("PROVIDER_OBSERVER_DEATH_LINK_FAILED", error);
        } catch (RuntimeException | Error error) {
            removeDead(id, binder);
            throw error;
        }
        return new RegisterResult(registration.entry(), true);
    }

    public synchronized ProviderObserverRegistry.Entry unregister(String id, String callerInstance,
                                                           String callerSessionId,
                                                           long callerGeneration) {
        ProviderObserverRegistry.Entry removed = registry.unregister(id, callerInstance,
                callerSessionId, callerGeneration);
        unlink(id);
        return removed;
    }

    public NotifyResult notifyChange(int virtualUserId, String authority, String uri,
                              String notifyingInstance, String targetSessionId,
                              long targetGeneration, int flags) {
        List<ProviderObserverRegistry.Entry> matches = registry.resolve(virtualUserId, authority, uri,
                notifyingInstance, targetSessionId, targetGeneration);
        int delivered = 0;
        List<String> failures = new ArrayList<>();
        for (ProviderObserverRegistry.Entry entry : matches) {
            CallbackRecord record;
            synchronized (this) { record = callbacks.get(entry.id()); }
            if (record == null) {
                failures.add(entry.id() + ":CALLBACK_MISSING");
                removeDead(entry.id(), null);
                continue;
            }
            boolean selfChange = entry.callerInstanceId().equals(notifyingInstance);
            try {
                record.callback.onChange(uri, selfChange, flags);
                delivered++;
            } catch (Throwable error) {
                failures.add(entry.id() + ":" + error.getClass().getSimpleName());
                removeDead(entry.id(), record.binder);
            }
        }
        return new NotifyResult(matches.size(), delivered, failures);
    }

    synchronized int invalidateSession(String sessionId, long generation) {
        List<String> ids = idsForSession(sessionId, generation);
        registry.removeSession(sessionId, generation);
        for (String id : ids) unlink(id);
        return ids.size();
    }

    synchronized int invalidateInstance(String instanceId) {
        List<String> ids = idsForInstance(instanceId);
        registry.removeInstance(instanceId);
        for (String id : ids) unlink(id);
        return ids.size();
    }

    synchronized int size() { return registry.size(); }

    private synchronized void removeDead(String id, IBinder expectedBinder) {
        CallbackRecord record = callbacks.get(id);
        if (record == null) return;
        if (expectedBinder != null && record.binder != expectedBinder) return;
        ProviderObserverRegistry.Entry entry = registry.get(id);
        if (entry != null) {
            try {
                registry.unregister(id, entry.callerInstanceId(), entry.callerSessionId(),
                        entry.callerGeneration());
            } catch (Throwable ignored) { }
        }
        unlink(id);
    }

    private void unlink(String id) {
        CallbackRecord record = callbacks.remove(id);
        if (record == null) return;
        record.deathRegistration.close();
    }

    private List<String> idsForSession(String sessionId, long generation) {
        List<String> ids = new ArrayList<>();
        for (String id : callbacks.keySet()) {
            ProviderObserverRegistry.Entry entry = registry.get(id);
            if (entry == null) continue;
            if ((entry.callerSessionId().equals(sessionId) && entry.callerGeneration() == generation)
                    || (entry.targetSessionId().equals(sessionId)
                    && entry.targetGeneration() == generation)) {
                ids.add(id);
            }
        }
        return ids;
    }

    private List<String> idsForInstance(String instanceId) {
        List<String> ids = new ArrayList<>();
        for (String id : callbacks.keySet()) {
            ProviderObserverRegistry.Entry entry = registry.get(id);
            if (entry != null && (entry.callerInstanceId().equals(instanceId)
                    || entry.targetInstanceId().equals(instanceId))) {
                ids.add(id);
            }
        }
        return ids;
    }
}
