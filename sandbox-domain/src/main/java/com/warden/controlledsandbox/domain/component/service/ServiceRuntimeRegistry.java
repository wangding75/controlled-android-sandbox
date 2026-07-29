package com.warden.controlledsandbox.domain.component.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit started, bound, foreground, and recovery ownership model independent of Android Binder classes. */
public final class ServiceRuntimeRegistry {
    public static final int MAX_SERVICE_RECORDS = 1024;
    public static final int MAX_CONNECTIONS_PER_SERVICE = 256;
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
        private final String lastStartAction;
        private final ForegroundServiceStateMachine.Snapshot foreground;
        private final boolean recoverForeground;

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
            lastStartAction = record.lastStartAction;
            foreground = record.foreground.snapshot();
            recoverForeground = record.recoverForeground;
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
        public String lastStartAction() { return lastStartAction; }
        public boolean foreground() { return foreground.active(); }
        public ForegroundServiceStateMachine.Snapshot foregroundSnapshot() { return foreground; }
        public boolean foregroundRequested() { return foreground.pending() || foreground.active(); }
        public boolean recoverForeground() { return recoverForeground; }
        public boolean started() { return startCount > 0; }
        public boolean bound() { return !connectionIds.isEmpty(); }
        public boolean recoverable() { return state == State.RECOVERING; }
    }

    private final Map<String, MutableRecord> records = new LinkedHashMap<>();

    public synchronized Snapshot start(String instanceId, String component, String processName,
                                       RestartMode restartMode, long generation) {
        return start(instanceId, component, processName, restartMode, generation, "", false);
    }

    /** Legacy source entry; foreground=true is treated as an immediate controlled promotion. */
    public synchronized Snapshot start(String instanceId, String component, String processName,
                                       RestartMode restartMode, long generation, String action,
                                       boolean foreground) {
        MutableRecord record = startRecord(instanceId, component, processName, restartMode,
                generation, action);
        if (foreground) {
            record.foreground.requestStart(0L, ForegroundServiceStateMachine.DEFAULT_PROMOTION_TIMEOUT_MS,
                    true, "LEGACY_IMMEDIATE_PROMOTION", 0);
            record.lastDeclaredForegroundTypeMask = 0;
            record.foreground.promote(0L, 0, 1, "legacy");
        }
        return new Snapshot(record);
    }

    public synchronized Snapshot startForegroundRequested(
            String instanceId, String component, String processName, RestartMode restartMode,
            long generation, String action, long nowMs, long promotionTimeoutMs,
            boolean backgroundAllowed, String exemptionReason, int declaredTypeMask) {
        ForegroundServiceStateMachine validation = new ForegroundServiceStateMachine();
        validation.requestStart(nowMs, promotionTimeoutMs, backgroundAllowed,
                exemptionReason, declaredTypeMask);
        MutableRecord record = startRecord(instanceId, component, processName, restartMode,
                generation, action);
        record.foreground.requestStart(nowMs, promotionTimeoutMs, backgroundAllowed,
                exemptionReason, declaredTypeMask);
        record.lastDeclaredForegroundTypeMask = declaredTypeMask;
        record.recoverForeground = true;
        return new Snapshot(record);
    }

    public synchronized Snapshot promoteForeground(
            String instanceId, String component, long generation, long nowMs,
            int requestedTypeMask, int notificationId, String notificationTag) {
        MutableRecord record = requireRecord(instanceId, component);
        requireGeneration(record, generation);
        if (record.startCount == 0) throw new IllegalStateException("FOREGROUND_SERVICE_NOT_STARTED");
        record.foreground.promote(nowMs, requestedTypeMask, notificationId, notificationTag);
        record.recoverForeground = true;
        record.state = State.ACTIVE;
        return new Snapshot(record);
    }

    public synchronized Snapshot demoteForeground(
            String instanceId, String component, long generation,
            boolean removeNotification, String reason) {
        MutableRecord record = requireRecord(instanceId, component);
        requireGeneration(record, generation);
        record.foreground.demote(removeNotification, reason);
        record.recoverForeground = false;
        settle(record);
        return new Snapshot(record);
    }

    public synchronized List<Snapshot> expireForeground(long nowMs) {
        List<Snapshot> expired = new ArrayList<>();
        for (MutableRecord record : records.values()) {
            if (!record.foreground.expire(nowMs)) continue;
            record.startCount = 0;
            record.recoverForeground = false;
            settle(record);
            expired.add(new Snapshot(record));
        }
        return Collections.unmodifiableList(expired);
    }

    public synchronized Snapshot bind(String instanceId, String component, String processName,
                                      String connectionId, long generation) {
        requireText(connectionId, "connectionId");
        MutableRecord record = getOrCreate(instanceId, component, processName, generation);
        requireGeneration(record, generation);
        if (record.state == State.DESTROYED) throw new IllegalStateException("SERVICE_DESTROYED");
        if (!record.connectionIds.contains(connectionId)
                && record.connectionIds.size() >= MAX_CONNECTIONS_PER_SERVICE) {
            throw new IllegalStateException("SERVICE_CONNECTION_LIMIT_EXCEEDED");
        }
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

    public synchronized Snapshot disconnect(String instanceId, String component, String connectionId,
                                             long generation) {
        MutableRecord record = requireRecord(instanceId, component);
        requireGeneration(record, generation);
        record.connectionIds.remove(connectionId);
        settle(record);
        return new Snapshot(record);
    }

    public synchronized Snapshot stopStarted(String instanceId, String component, long generation) {
        MutableRecord record = requireRecord(instanceId, component);
        requireGeneration(record, generation);
        record.startCount = 0;
        record.recoverForeground = false;
        record.foreground.terminate("SERVICE_STOPPED");
        settle(record);
        return new Snapshot(record);
    }

    /** Mirrors stopSelfResult: only the newest delivered start id can stop the started ownership. */
    public synchronized Snapshot stopStartId(String instanceId, String component, int startId,
                                             long generation) {
        if (startId < 1) throw new IllegalArgumentException("startId must be positive");
        MutableRecord record = requireRecord(instanceId, component);
        requireGeneration(record, generation);
        if (startId == record.lastStartId) {
            record.startCount = 0;
            record.recoverForeground = false;
            record.foreground.terminate("SERVICE_STOPPED_BY_START_ID");
            settle(record);
        }
        return new Snapshot(record);
    }

    /** Compatibility entry used by older callers. */
    public synchronized Snapshot setForeground(String instanceId, String component, boolean foreground,
                                               long generation) {
        if (foreground) {
            MutableRecord record = requireRecord(instanceId, component);
            requireGeneration(record, generation);
            if (!record.foreground.pending() && !record.foreground.active()) {
                record.foreground.requestStart(0L,
                        ForegroundServiceStateMachine.DEFAULT_PROMOTION_TIMEOUT_MS,
                        true, "LEGACY_SET_FOREGROUND", 0);
            }
            return promoteForeground(instanceId, component, generation, 0L, 0, 1, "legacy");
        }
        return demoteForeground(instanceId, component, generation, true, "LEGACY_DEMOTION");
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
            record.recoverForeground = record.foreground.requested();
            record.foreground.terminate("SERVICE_PROCESS_DIED");
            if (record.startCount > 0 && record.restartMode != RestartMode.NOT_STICKY) {
                record.state = State.RECOVERING;
            } else {
                record.startCount = 0;
                record.recoverForeground = false;
                record.state = State.DESTROYED;
            }
            affected.add(new Snapshot(record));
        }
        return Collections.unmodifiableList(affected);
    }

    public synchronized Snapshot completeRecovery(String instanceId, String component,
                                                  long oldGeneration, long newGeneration) {
        return completeRecovery(instanceId, component, oldGeneration, newGeneration, 0L);
    }

    public synchronized Snapshot completeRecovery(String instanceId, String component,
                                                  long oldGeneration, long newGeneration,
                                                  long nowMs) {
        MutableRecord record = requireRecord(instanceId, component);
        requireGeneration(record, oldGeneration);
        if (record.state != State.RECOVERING) throw new IllegalStateException("SERVICE_NOT_RECOVERING");
        if (newGeneration <= oldGeneration) throw new IllegalArgumentException("new generation must increase");
        record.generation = newGeneration;
        record.state = State.ACTIVE;
        if (record.recoverForeground) {
            record.foreground.requestStart(nowMs,
                    ForegroundServiceStateMachine.DEFAULT_PROMOTION_TIMEOUT_MS,
                    true, "PROCESS_RECOVERY", record.lastDeclaredForegroundTypeMask);
        }
        return new Snapshot(record);
    }

    public synchronized Snapshot find(String instanceId, String component) {
        MutableRecord record = records.get(key(instanceId, component));
        return record == null ? null : new Snapshot(record);
    }

    public synchronized List<Snapshot> recovering(String instanceId, String processName,
                                                  long generation) {
        List<Snapshot> result = new ArrayList<>();
        for (MutableRecord record : records.values()) {
            if (record.instanceId.equals(instanceId) && record.processName.equals(processName)
                    && record.generation == generation && record.state == State.RECOVERING) {
                result.add(new Snapshot(record));
            }
        }
        return Collections.unmodifiableList(result);
    }

    public synchronized List<Snapshot> completeProcessRecovery(
            String instanceId, String processName, long oldGeneration, long newGeneration) {
        return completeProcessRecovery(instanceId, processName, oldGeneration, newGeneration, 0L);
    }

    public synchronized List<Snapshot> completeProcessRecovery(
            String instanceId, String processName, long oldGeneration, long newGeneration,
            long nowMs) {
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
                if (record.recoverForeground) {
                    record.foreground.requestStart(nowMs,
                            ForegroundServiceStateMachine.DEFAULT_PROMOTION_TIMEOUT_MS,
                            true, "PROCESS_RECOVERY", record.lastDeclaredForegroundTypeMask);
                }
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
                record.foreground.terminate("SERVICE_INSTANCE_DESTROYED");
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

    private MutableRecord startRecord(String instanceId, String component, String processName,
                                      RestartMode restartMode, long generation, String action) {
        MutableRecord record = getOrCreate(instanceId, component, processName, generation);
        requireGeneration(record, generation);
        if (record.state == State.DESTROYED) throw new IllegalStateException("SERVICE_DESTROYED");
        record.lastStartId++;
        record.startCount++;
        record.restartMode = restartMode == null ? RestartMode.NOT_STICKY : restartMode;
        record.lastStartAction = action == null ? "" : action;
        record.state = State.ACTIVE;
        return record;
    }

    private MutableRecord getOrCreate(String instanceId, String component, String processName, long generation) {
        requireText(instanceId, "instanceId");
        requireText(component, "component");
        requireText(processName, "processName");
        if (generation < 1) throw new IllegalArgumentException("generation must be positive");
        String key = key(instanceId, component);
        MutableRecord record = records.get(key);
        if (record == null || record.state == State.DESTROYED) {
            if (record == null) {
                records.entrySet().removeIf(entry -> entry.getValue().state == State.DESTROYED);
                if (records.size() >= MAX_SERVICE_RECORDS) {
                    throw new IllegalStateException("SERVICE_RECORD_LIMIT_EXCEEDED");
                }
            }
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
        if (record.startCount == 0 && record.connectionIds.isEmpty()) {
            record.state = State.DESTROYED;
        } else record.state = State.ACTIVE;
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
        final ForegroundServiceStateMachine foreground = new ForegroundServiceStateMachine();
        State state = State.CREATED;
        RestartMode restartMode = RestartMode.NOT_STICKY;
        int lastStartId;
        int startCount;
        long generation;
        String lastStartAction = "";
        boolean recoverForeground;
        int lastDeclaredForegroundTypeMask;

        MutableRecord(String instanceId, String component, String processName, long generation) {
            this.instanceId = instanceId;
            this.component = component;
            this.processName = processName;
            this.generation = generation;
        }
    }
}
