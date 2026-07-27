package com.warden.controlledsandbox.domain.component.activity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Dependency-free model of Guest tasks and Activity lifecycle state.
 * Android adapters translate framework callbacks into these explicit transitions.
 */
public final class ActivityTaskRegistry {
    public enum State { INITIALIZED, CREATED, STARTED, RESUMED, PAUSED, STOPPED, DESTROYED }

    public static final class Snapshot {
        private final String token;
        private final String instanceId;
        private final String component;
        private final int taskId;
        private final State state;
        private final long generation;

        private Snapshot(String token, String instanceId, String component, int taskId,
                         State state, long generation) {
            this.token = token;
            this.instanceId = instanceId;
            this.component = component;
            this.taskId = taskId;
            this.state = state;
            this.generation = generation;
        }

        public String token() { return token; }
        public String instanceId() { return instanceId; }
        public String component() { return component; }
        public int taskId() { return taskId; }
        public State state() { return state; }
        public long generation() { return generation; }
    }

    private final Map<String, MutableRecord> byToken = new LinkedHashMap<>();
    private final Map<String, Deque<String>> taskStacks = new LinkedHashMap<>();
    private int nextTaskId = 1;

    public synchronized Snapshot launch(String instanceId, String component, Integer requestedTaskId,
                                        long generation) {
        requireText(instanceId, "instanceId");
        requireText(component, "component");
        if (generation < 1) throw new IllegalArgumentException("generation must be positive");
        int taskId = requestedTaskId == null ? nextTaskId++ : requestedTaskId;
        if (taskId < 1) throw new IllegalArgumentException("taskId must be positive");
        nextTaskId = Math.max(nextTaskId, taskId + 1);
        String token = UUID.randomUUID().toString();
        MutableRecord record = new MutableRecord(token, instanceId, component, taskId, generation);
        byToken.put(token, record);
        taskStacks.computeIfAbsent(taskKey(instanceId, taskId), ignored -> new ArrayDeque<>()).addLast(token);
        return record.snapshot();
    }

    public synchronized Snapshot transition(String token, long generation, State target) {
        MutableRecord record = requireRecord(token);
        requireGeneration(record, generation);
        Objects.requireNonNull(target, "target");
        if (!allowed(record.state, target)) {
            throw new IllegalStateException("INVALID_ACTIVITY_TRANSITION:" + record.state + "->" + target);
        }
        record.state = target;
        if (target == State.DESTROYED) {
            Deque<String> stack = taskStacks.get(taskKey(record.instanceId, record.taskId));
            if (stack != null) {
                stack.remove(token);
                if (stack.isEmpty()) taskStacks.remove(taskKey(record.instanceId, record.taskId));
            }
        }
        return record.snapshot();
    }

    public synchronized Snapshot top(String instanceId, int taskId) {
        Deque<String> stack = taskStacks.get(taskKey(instanceId, taskId));
        if (stack == null || stack.isEmpty()) return null;
        MutableRecord record = byToken.get(stack.peekLast());
        return record == null ? null : record.snapshot();
    }

    public synchronized List<Snapshot> task(String instanceId, int taskId) {
        Deque<String> stack = taskStacks.get(taskKey(instanceId, taskId));
        if (stack == null) return Collections.emptyList();
        List<Snapshot> out = new ArrayList<>();
        for (String token : stack) {
            MutableRecord record = byToken.get(token);
            if (record != null) out.add(record.snapshot());
        }
        return Collections.unmodifiableList(out);
    }

    public synchronized int destroyInstance(String instanceId, long generation) {
        List<String> tokens = new ArrayList<>();
        for (MutableRecord record : byToken.values()) {
            if (record.instanceId.equals(instanceId) && record.generation == generation
                    && record.state != State.DESTROYED) tokens.add(record.token);
        }
        for (String token : tokens) transition(token, generation, State.DESTROYED);
        return tokens.size();
    }

    private static boolean allowed(State from, State to) {
        if (from == to) return true;
        switch (from) {
            case INITIALIZED: return to == State.CREATED || to == State.DESTROYED;
            case CREATED: return to == State.STARTED || to == State.DESTROYED;
            case STARTED: return to == State.RESUMED || to == State.STOPPED || to == State.DESTROYED;
            case RESUMED: return to == State.PAUSED || to == State.DESTROYED;
            case PAUSED: return to == State.RESUMED || to == State.STOPPED || to == State.DESTROYED;
            case STOPPED: return to == State.STARTED || to == State.DESTROYED;
            case DESTROYED: return false;
            default: return false;
        }
    }

    private MutableRecord requireRecord(String token) {
        MutableRecord record = byToken.get(token);
        if (record == null) throw new IllegalArgumentException("UNKNOWN_ACTIVITY_TOKEN");
        return record;
    }

    private static void requireGeneration(MutableRecord record, long generation) {
        if (record.generation != generation) {
            throw new IllegalStateException("STALE_GENERATION:" + generation + ":expected:" + record.generation);
        }
    }

    private static String taskKey(String instanceId, int taskId) { return instanceId + "#" + taskId; }
    private static void requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
    }

    private static final class MutableRecord {
        final String token;
        final String instanceId;
        final String component;
        final int taskId;
        final long generation;
        State state = State.INITIALIZED;

        MutableRecord(String token, String instanceId, String component, int taskId, long generation) {
            this.token = token;
            this.instanceId = instanceId;
            this.component = component;
            this.taskId = taskId;
            this.generation = generation;
        }

        Snapshot snapshot() { return new Snapshot(token, instanceId, component, taskId, state, generation); }
    }
}
