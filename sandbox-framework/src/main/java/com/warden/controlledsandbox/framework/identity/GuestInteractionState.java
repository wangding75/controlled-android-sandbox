package com.warden.controlledsandbox.framework.identity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Process-local ownership ledger for Window, ActivityClient, IME and virtual-display sessions. */
public final class GuestInteractionState implements AutoCloseable {
    private final WindowState windows = new WindowState();
    private final InputMethodState inputMethods = new InputMethodState();
    private final ActivityClientState activities = new ActivityClientState();
    private final DisplayState displays = new DisplayState();

    public WindowState windows() { return windows; }
    public InputMethodState inputMethods() { return inputMethods; }
    public ActivityClientState activities() { return activities; }
    public DisplayState displays() { return displays; }

    @Override public void close() {
        windows.clear(); inputMethods.clear(); activities.clear(); displays.clear();
    }

    public static final class WindowState {
        private final AtomicLong nextSessionId = new AtomicLong(1L);
        private final IdentityHashMap<Object, SessionRecord> sessions = new IdentityHashMap<>();
        private final IdentityHashMap<Object, WindowRecord> windows = new IdentityHashMap<>();

        public synchronized long openSession(Object session) {
            if (session == null) throw new IllegalArgumentException("window session is required");
            SessionRecord current = sessions.get(session);
            if (current != null) return current.sessionId;
            long id = nextSessionId.getAndIncrement();
            sessions.put(session, new SessionRecord(id));
            return id;
        }

        public synchronized void register(Object session, Object token, int displayId, int type,
                int maximumWindows) {
            if (session == null || token == null) {
                throw new SecurityException("VIRTUAL_WINDOW_SESSION_OR_TOKEN_REQUIRED");
            }
            SessionRecord owner = sessions.get(session);
            if (owner == null) {
                owner = new SessionRecord(nextSessionId.getAndIncrement());
                sessions.put(session, owner);
            }
            WindowRecord existing = windows.get(token);
            if (existing != null && existing.session != session) {
                throw new SecurityException("VIRTUAL_WINDOW_TOKEN_OWNED_BY_OTHER_SESSION");
            }
            if (existing == null && windows.size() >= maximumWindows) {
                throw new SecurityException("VIRTUAL_WINDOW_LIMIT_EXCEEDED");
            }
            windows.put(token, new WindowRecord(session, owner.sessionId, displayId, type,
                    System.currentTimeMillis()));
            owner.tokens.put(token, Boolean.TRUE);
        }

        public synchronized boolean relayout(Object session, Object token, int displayId, int type) {
            WindowRecord current = windows.get(token);
            if (current == null || current.session != session) return false;
            windows.put(token, new WindowRecord(session, current.sessionId, displayId, type,
                    System.currentTimeMillis()));
            return true;
        }

        public synchronized boolean remove(Object session, Object token) {
            WindowRecord current = windows.get(token);
            if (current == null || current.session != session) return false;
            windows.remove(token);
            SessionRecord owner = sessions.get(session);
            if (owner != null) owner.tokens.remove(token);
            return true;
        }

        public synchronized void closeSession(Object session) {
            SessionRecord owner = sessions.remove(session);
            if (owner == null) return;
            for (Object token : new ArrayList<>(owner.tokens.keySet())) windows.remove(token);
            owner.tokens.clear();
        }

        public synchronized int sessionCount() { return sessions.size(); }
        public synchronized int windowCount() { return windows.size(); }
        public synchronized boolean owns(Object session, Object token) {
            WindowRecord record = windows.get(token);
            return record != null && record.session == session;
        }
        public synchronized List<Long> sessionIds() {
            List<Long> result = new ArrayList<>();
            for (SessionRecord record : sessions.values()) result.add(record.sessionId);
            Collections.sort(result);
            return Collections.unmodifiableList(result);
        }
        synchronized void clear() { sessions.clear(); windows.clear(); }

        private static final class SessionRecord {
            final long sessionId;
            final IdentityHashMap<Object, Boolean> tokens = new IdentityHashMap<>();
            SessionRecord(long sessionId) { this.sessionId = sessionId; }
        }
        private record WindowRecord(Object session, long sessionId, int displayId, int type,
                                    long updatedAtMs) { }
    }

    public static final class InputMethodState {
        private final AtomicLong nextId = new AtomicLong(1L);
        private final IdentityHashMap<Object, SessionRecord> sessions = new IdentityHashMap<>();

        public synchronized long start(Object clientToken, Object focusToken, int maximumSessions) {
            Object key = clientToken == null ? focusToken : clientToken;
            if (key == null) throw new SecurityException("VIRTUAL_INPUT_CLIENT_TOKEN_REQUIRED");
            SessionRecord current = sessions.get(key);
            if (current != null) {
                current.focusToken = focusToken;
                current.active = true;
                current.updatedAtMs = System.currentTimeMillis();
                return current.sessionId;
            }
            if (sessions.size() >= maximumSessions) {
                throw new SecurityException("VIRTUAL_INPUT_SESSION_LIMIT_EXCEEDED");
            }
            long id = nextId.getAndIncrement();
            sessions.put(key, new SessionRecord(id, focusToken));
            return id;
        }
        public synchronized boolean finish(Object clientToken) {
            if (clientToken == null) return false;
            return sessions.remove(clientToken) != null;
        }
        public synchronized boolean active(Object clientToken) {
            SessionRecord record = sessions.get(clientToken);
            return record != null && record.active;
        }
        public synchronized int size() { return sessions.size(); }
        synchronized void clear() { sessions.clear(); }

        private static final class SessionRecord {
            final long sessionId;
            Object focusToken;
            boolean active = true;
            long updatedAtMs = System.currentTimeMillis();
            SessionRecord(long sessionId, Object focusToken) {
                this.sessionId = sessionId; this.focusToken = focusToken;
            }
        }
    }

    public static final class ActivityClientState {
        private final IdentityHashMap<Object, ActivityRecord> activities = new IdentityHashMap<>();

        public synchronized void event(Object token, String event) {
            if (token == null) return;
            String normalized = event == null ? "UNKNOWN" : event.toUpperCase(java.util.Locale.ROOT);
            if ("DESTROYED".equals(normalized) || "FINISHED".equals(normalized)) {
                activities.remove(token);
                return;
            }
            ActivityRecord current = activities.get(token);
            activities.put(token, new ActivityRecord(normalized,
                    current == null ? "" : current.taskDescription, System.currentTimeMillis()));
        }
        public synchronized void taskDescription(Object token, String description) {
            if (token == null) return;
            ActivityRecord current = activities.get(token);
            activities.put(token, new ActivityRecord(current == null ? "UNKNOWN" : current.state,
                    description == null ? "" : description, System.currentTimeMillis()));
        }
        public synchronized String state(Object token) {
            ActivityRecord record = activities.get(token);
            return record == null ? "" : record.state;
        }
        public synchronized int size() { return activities.size(); }
        synchronized void clear() { activities.clear(); }
        private record ActivityRecord(String state, String taskDescription, long updatedAtMs) { }
    }

    public static final class DisplayState {
        private final AtomicLong nextId = new AtomicLong(1L);
        private final IdentityHashMap<Object, DisplayRecord> owned = new IdentityHashMap<>();

        public synchronized long reserve(Object callbackToken, String name, int maximum) {
            if (callbackToken == null) throw new SecurityException("VIRTUAL_DISPLAY_CALLBACK_REQUIRED");
            DisplayRecord current = owned.get(callbackToken);
            if (current != null) return current.virtualId;
            if (owned.size() >= maximum) throw new SecurityException("VIRTUAL_DISPLAY_LIMIT_EXCEEDED");
            long id = nextId.getAndIncrement();
            owned.put(callbackToken, new DisplayRecord(id, name == null ? "" : name,
                    System.currentTimeMillis()));
            return id;
        }
        public synchronized boolean release(Object callbackToken) {
            return callbackToken != null && owned.remove(callbackToken) != null;
        }
        public synchronized boolean owns(Object callbackToken) {
            return callbackToken != null && owned.containsKey(callbackToken);
        }
        public synchronized int size() { return owned.size(); }
        synchronized void clear() { owned.clear(); }
        private record DisplayRecord(long virtualId, String name, long updatedAtMs) { }
    }
}
