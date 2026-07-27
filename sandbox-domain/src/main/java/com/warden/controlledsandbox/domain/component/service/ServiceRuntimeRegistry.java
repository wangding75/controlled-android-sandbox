package com.warden.controlledsandbox.domain.component.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit started/bound Service ownership model, independent of Android Binder classes. */
public final class ServiceRuntimeRegistry {
    public enum State { CREATED, ACTIVE, STOPPING, DESTROYED, RECOVERING }
    public enum RestartMode { NOT_STICKY, STICKY, REDELIVER_INTENT }

    public static final class Snapshot {
        private final String instanceId;
        private final String component;
        private final String processName;
        private final State state;
        private final RestartMode restartMode;
        private final int lastStartId;
        private final int startCount;
        private final Set<String> connectionIds;
        private final long generation;

        private Snapshot(MutableRecord record) {
            instanceId = record.instanceId;
            component = record.component;
            processName = record.processName;
            state = record.state;
            restartMode = record.restartMode;
            lastStartId = record.lastStartId;
            startCount = record.startCount;
            connectionIds = Collections.unmodifiableSet(new LinkedHashSet<>(record.connectionIds));
            generation = record.generation;
        }

        public String instanceId() { return instanceId; }
        public String component() { return component; }
        public String processName() { return processName; }
        public State state() { return state; }
        public RestartMode restartMode() { return restartMode; }
        public int lastStartId() { return lastStartId; }
        public int startCount() { return startCount; }
        public Set<String> connectionIds() { return connectionIds; }
        public long generation() { return generation; }
        public boolean started() { return startCount > 0; }
        public boolean bound() { return !connectionIds.isEmpty(); }
    }

    private final Map<String, MutableRecord> records = new LinkedHashMap<>();

    public synchronized Snapshot start(String instanceId, String component, String processName,
                                       RestartMode restartMode, long generation) {
        MutableRecord record = getOrCreate(instanceId, component, processName, generation);
        requireGeneration(record, generation);
        if (record.state == State.DESTROYED) throw new IllegalStateException("SERVICE_DESTROYED");
        record.lastStartId++;
        record.startCount++;
        record.restartMode = restartMode == null ? RestartMode.NOT_STICKY : restartMode;
        record.state = State.ACTIVE;
        return new Snapshot(record);
    }

    public synchronized Snapshot bind(String instanceId, String component, String processName,
                                      String connectionId, long generation) {
        requireText(connectionId, "connectionId");
        MutableRecord record = getOrCreate(instanceId, component, processName, generation);
        requireGeneration(record, generation);
        if (record.state == State.DESTROYED) throw new IllegalStateException("SERVICE_DESTROYED");
        if (!record.connectionIds.add(connectionId)) throw new IllegalStateException("DUPLICATE_SERVICE_CONNECTION");
        record.state = State.ACTIVE;
        return new Snapshot(record);
    }

    public synchronized Snapshot unbind(String instanceId, String component, String connectionId,
                                        long generation) {
        MutableRecord record = requireRecord(instanceId, component);
        requireGeneration(record, generation);
        if (!record.connectionIds.remove(connectionId)) throw new IllegalArgumentException("UNKNOWN_SERVICE_CONNECTION");
        settle(record);
        return new Snapshot(record);
    }

    public synchronized Snapshot stopStarted(String instanceId, String component, long generation) {
        MutableRecord record = requireRecord(instanceId, component);
        requireGeneration(record, generation);
        record.startCount = 0;
        settle(record);
        return new Snapshot(record);
    }

    public synchronized List<Snapshot> markProcessDied(
            String instanceId, String processName, long generation) {
        requireText(instanceId, "instanceId");
        requireText(processName, "processName");
        if (generation < 1) throw new IllegalArgumentException("generation must be positive");
        List<Snapshot> affected = new ArrayList<>();
        for (MutableRecord record : records.values()) {
            if (!record.instanceId.equals(instanceId)
                    || !record.processName.equals(processName)
                    || record.generation != generation
                    || record.state == State.DESTROYED) continue;
            record.connectionIds.clear();
            if (record.startCount > 0 && record.restartMode != RestartMode.NOT_STICKY) {
                record.state = State.RECOVERING;
            } else {
                record.startCount = 0;
                record.state = State.DESTROYED;
            }
            affected.add(new Snapshot(record));
        }
        return Collections.unmodifiableList(affected);
    }

    public synchronized Snapshot completeRecovery(String instanceId, String component,
                                                  long oldGeneration, long newGeneration) {
        MutableRecord record = requireRecord(instanceId, component);
        requireGeneration(record, oldGeneration);
        if (record.state != State.RECOVERING) throw new IllegalStateException("SERVICE_NOT_RECOVERING");
        if (newGeneration <= oldGeneration) throw new IllegalArgumentException("new generation must increase");
        record.generation = newGeneration;
        record.state = State.ACTIVE;
        return new Snapshot(record);
    }

    public synchronized Snapshot find(String instanceId, String component) {
        MutableRecord record = records.get(key(instanceId, component));
        return record == null ? null : new Snapshot(record);
    }

    public synchronized List<Snapshot> completeProcessRecovery(
            String instanceId, String processName, long oldGeneration, long newGeneration) {
        requireText(instanceId, "instanceId");
        requireText(processName, "processName");
        if (oldGeneration < 1 || newGeneration <= oldGeneration) {
            throw new IllegalArgumentException("new generation must increase");
        }
        List<Snapshot> recovered = new ArrayList<>();
        for (MutableRecord record : records.values()) {
            if (record.instanceId.equals(instanceId)
                    && record.processName.equals(processName)
                    && record.generation == oldGeneration
                    && record.state == State.RECOVERING) {
                record.generation = newGeneration;
                record.state = State.ACTIVE;
                recovered.add(new Snapshot(record));
            }
        }
        return Collections.unmodifiableList(recovered);
    }

    public synchronized int destroyInstance(String instanceId, long generation) {
        requireText(instanceId, "instanceId");
        if (generation < 1) throw new IllegalArgumentException("generation must be positive");
        int removed = 0;
        java.util.Iterator<Map.Entry<String, MutableRecord>> iterator = records.entrySet().iterator();
        while (iterator.hasNext()) {
            MutableRecord record = iterator.next().getValue();
            if (record.instanceId.equals(instanceId) && record.generation <= generation) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    public synchronized List<Snapshot> snapshot() {
        List<Snapshot> out = new ArrayList<>();
        for (MutableRecord record : records.values()) out.add(new Snapshot(record));
        return Collections.unmodifiableList(out);
    }

    private MutableRecord getOrCreate(String instanceId, String component, String processName, long generation) {
        requireText(instanceId, "instanceId");
        requireText(component, "component");
        requireText(processName, "processName");
        if (generation < 1) throw new IllegalArgumentException("generation must be positive");
        String key = key(instanceId, component);
        MutableRecord record = records.get(key);
        if (record == null || record.state == State.DESTROYED) {
            record = new MutableRecord(instanceId, component, processName, generation);
            records.put(key, record);
        } else if (!record.processName.equals(processName)) {
            throw new IllegalStateException("SERVICE_PROCESS_CHANGED");
        }
        return record;
    }

    private MutableRecord requireRecord(String instanceId, String component) {
        MutableRecord record = records.get(key(instanceId, component));
        if (record == null) throw new IllegalArgumentException("UNKNOWN_SERVICE");
        return record;
    }

    private static void settle(MutableRecord record) {
        if (record.startCount == 0 && record.connectionIds.isEmpty()) record.state = State.DESTROYED;
        else record.state = State.ACTIVE;
    }

    private static void requireGeneration(MutableRecord record, long generation) {
        if (record.generation != generation) throw new IllegalStateException("STALE_GENERATION");
    }

    private static String key(String instanceId, String component) { return instanceId + "#" + component; }
    private static void requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
    }

    private static final class MutableRecord {
        final String instanceId;
        final String component;
        final String processName;
        final Set<String> connectionIds = new LinkedHashSet<>();
        State state = State.CREATED;
        RestartMode restartMode = RestartMode.NOT_STICKY;
        int lastStartId;
        int startCount;
        long generation;

        MutableRecord(String instanceId, String component, String processName, long generation) {
            this.instanceId = instanceId;
            this.component = component;
            this.processName = processName;
            this.generation = generation;
        }
    }
}
