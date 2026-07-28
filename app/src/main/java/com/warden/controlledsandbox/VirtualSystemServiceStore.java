package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.IVirtualSystemServiceObserver;
import com.warden.controlledsandbox.contract.VirtualAccountSnapshot;
import com.warden.controlledsandbox.contract.VirtualAlarmSnapshot;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Binder-owned durable state for bounded virtual system services. */
final class VirtualSystemServiceStore implements AutoCloseable {
    interface Client {
        Scope scope();
        String processName();
        long generation();
        IVirtualSystemServiceObserver observer();
        boolean active();
    }
    record Scope(String packageName, int virtualUserId) {
        Scope {
            if (packageName == null || packageName.trim().isEmpty() || virtualUserId < 0) {
                throw new IllegalArgumentException("Invalid virtual system-service scope");
            }
            packageName = packageName.trim();
        }
        String key() { return packageName + "#u" + virtualUserId; }
    }
    private record AccountKey(String name, String type) { }
    private static final class AccountRecord {
        String password;
        final Map<String, String> tokens = new LinkedHashMap<>();
        AccountRecord(String password) { this.password = safe(password); }
    }
    private static final class AlarmRecord {
        final String id;
        long triggerAtMs;
        final long intervalMs;
        final byte[] tokenPayload;
        final String ownerProcessName;
        long ownerGeneration;
        volatile ScheduledFuture<?> future;
        AlarmRecord(String id, long triggerAtMs, long intervalMs, byte[] tokenPayload,
                    String ownerProcessName, long ownerGeneration) {
            this.id = required(id, "alarmId");
            this.triggerAtMs = Math.max(0L, triggerAtMs);
            this.intervalMs = Math.max(0L, intervalMs);
            this.tokenPayload = boundedPayload(tokenPayload, "alarmToken");
            this.ownerProcessName = required(ownerProcessName, "ownerProcessName");
            if (ownerGeneration < 0L) throw new IllegalArgumentException("ownerGeneration must be non-negative");
            this.ownerGeneration = ownerGeneration;
        }
    }
    private static final class NamespaceState {
        int next;
        final Map<Integer, Integer> guestToHost = new LinkedHashMap<>();
        final Map<Integer, Integer> hostToGuest = new LinkedHashMap<>();
        NamespaceState(int seed) { next = seed; }
    }
    private static final class ScopeState {
        byte[] clipboard = new byte[0];
        final Map<AccountKey, AccountRecord> accounts = new LinkedHashMap<>();
        final Map<String, AlarmRecord> alarms = new LinkedHashMap<>();
        final Map<String, NamespaceState> namespaces = new LinkedHashMap<>();
    }

    private static final int SCHEMA = 1;
    private static final int MAX_PAYLOAD_BYTES = 512 * 1024;
    private static final int MAX_ACCOUNTS_PER_SCOPE = 64;
    private static final int MAX_TOKENS_PER_ACCOUNT = 32;
    private static final int MAX_ALARMS_PER_SCOPE = 256;
    private static final int MAX_NAMESPACE_MAPPINGS = 4096;
    private static final int MAX_KEY_CHARS = 512;
    private static final int MAX_SECRET_CHARS = 16 * 1024;
    private static final long RETRY_WITHOUT_CLIENT_MS = 30_000L;
    private final File file;
    private volatile String maintenanceWarning = "";
    private final Map<Scope, ScopeState> states = new LinkedHashMap<>();
    private final Set<Client> clients = new LinkedHashSet<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sandbox-system-service-authority");
        thread.setDaemon(true); return thread;
    });

    VirtualSystemServiceStore(File filesDir) {
        file = new File(filesDir, "sandbox-system-services.json");
        load();
        synchronized (this) {
            for (Map.Entry<Scope, ScopeState> item : states.entrySet()) {
                for (AlarmRecord alarm : item.getValue().alarms.values()) scheduleFuture(item.getKey(), alarm);
            }
        }
    }

    synchronized void register(Client client) {
        clients.add(client);
        for (AlarmRecord alarm : state(client.scope()).alarms.values()) {
            if (alarm.future == null || alarm.future.isCancelled() || alarm.future.isDone()) {
                scheduleFuture(client.scope(), alarm);
            }
        }
    }
    synchronized void unregister(Client client) { clients.remove(client); }

    synchronized byte[] clipboard(Scope scope) { return state(scope).clipboard.clone(); }
    synchronized void setClipboard(Scope scope, byte[] payload) {
        ScopeState before = snapshot(scope);
        state(scope).clipboard = boundedPayload(payload, "clipboard");
        persistOrRestore(scope, before);
        scheduler.execute(() -> notifyClipboard(scope));
    }
    synchronized void clearClipboard(Scope scope) {
        ScopeState before = snapshot(scope);
        state(scope).clipboard = new byte[0];
        persistOrRestore(scope, before);
        scheduler.execute(() -> notifyClipboard(scope));
    }

    synchronized List<VirtualAccountSnapshot> accounts(Scope scope, String requestedType) {
        String type = normalize(requestedType);
        List<VirtualAccountSnapshot> out = new ArrayList<>();
        for (Map.Entry<AccountKey, AccountRecord> item : state(scope).accounts.entrySet()) {
            if (!type.isEmpty() && !type.equals(item.getKey().type)) continue;
            List<String> tokenTypes = new ArrayList<>(item.getValue().tokens.keySet());
            List<String> tokens = new ArrayList<>();
            for (String tokenType : tokenTypes) tokens.add(item.getValue().tokens.get(tokenType));
            out.add(new VirtualAccountSnapshot(item.getKey().name, item.getKey().type,
                    item.getValue().password, tokenTypes, tokens));
        }
        out.sort(Comparator.comparing(VirtualAccountSnapshot::type).thenComparing(VirtualAccountSnapshot::name));
        return Collections.unmodifiableList(out);
    }
    synchronized boolean addAccount(Scope scope, String name, String type, String password) {
        AccountKey key = accountKey(name, type); ScopeState state = state(scope);
        if (state.accounts.containsKey(key)) return false;
        if (state.accounts.size() >= MAX_ACCOUNTS_PER_SCOPE) {
            throw new IllegalStateException("VIRTUAL_ACCOUNT_LIMIT_EXCEEDED");
        }
        ScopeState before = snapshot(scope);
        state.accounts.put(key, new AccountRecord(password));
        persistOrRestore(scope, before); return true;
    }
    synchronized boolean removeAccount(Scope scope, String name, String type) {
        ScopeState before = snapshot(scope);
        boolean removed = state(scope).accounts.remove(accountKey(name, type)) != null;
        if (removed) persistOrRestore(scope, before); return removed;
    }
    synchronized void setPassword(Scope scope, String name, String type, String password) {
        ScopeState before = snapshot(scope);
        requireAccount(scope, name, type).password = safe(password);
        persistOrRestore(scope, before);
    }
    synchronized String password(Scope scope, String name, String type) {
        AccountRecord record = state(scope).accounts.get(accountKey(name, type));
        return record == null ? null : record.password;
    }
    synchronized void setToken(Scope scope, String name, String type, String tokenType, String token) {
        AccountRecord record = requireAccount(scope, name, type);
        String normalizedType = normalizeRequired(tokenType, "tokenType");
        if (!record.tokens.containsKey(normalizedType)
                && record.tokens.size() >= MAX_TOKENS_PER_ACCOUNT) {
            throw new IllegalStateException("VIRTUAL_ACCOUNT_TOKEN_LIMIT_EXCEEDED");
        }
        ScopeState before = snapshot(scope);
        record.tokens.put(normalizedType, safe(token));
        persistOrRestore(scope, before);
    }
    synchronized String token(Scope scope, String name, String type, String tokenType) {
        AccountRecord record = state(scope).accounts.get(accountKey(name, type));
        return record == null ? null : record.tokens.get(normalize(tokenType));
    }
    synchronized void invalidateToken(Scope scope, String accountType, String token) {
        ScopeState before = snapshot(scope);
        String normalizedType = normalize(accountType); boolean changed = false;
        for (Map.Entry<AccountKey, AccountRecord> item : state(scope).accounts.entrySet()) {
            if (!normalizedType.isEmpty() && !normalizedType.equals(item.getKey().type)) continue;
            changed |= item.getValue().tokens.values().removeIf(value -> java.util.Objects.equals(value, token));
        }
        if (changed) persistOrRestore(scope, before);
    }

    synchronized void scheduleAlarm(Scope scope, String processName, long generation,
                                    String alarmId, long triggerAtMs, long intervalMs,
                                    byte[] tokenPayload) {
        ScopeState before = snapshot(scope);
        ScopeState state = state(scope);
        String normalizedId = required(alarmId, "alarmId");
        AlarmRecord previous = state.alarms.get(normalizedId);
        if (previous == null && state.alarms.size() >= MAX_ALARMS_PER_SCOPE) {
            throw new IllegalStateException("VIRTUAL_ALARM_LIMIT_EXCEEDED");
        }
        AlarmRecord record = new AlarmRecord(normalizedId, triggerAtMs, intervalMs,
                boundedPayload(tokenPayload, "alarmToken"),
                required(processName, "processName"), generation);
        state.alarms.put(record.id, record);
        persistOrRestore(scope, before);
        if (previous != null && previous.future != null) previous.future.cancel(false);
        scheduleFuture(scope, record);
    }
    synchronized boolean cancelAlarm(Scope scope, String alarmId) {
        ScopeState before = snapshot(scope);
        AlarmRecord removed = state(scope).alarms.remove(required(alarmId, "alarmId"));
        if (removed == null) return false;
        persistOrRestore(scope, before);
        if (removed.future != null) removed.future.cancel(false); return true;
    }
    synchronized List<VirtualAlarmSnapshot> alarms(Scope scope, String processName, long generation) {
        ScopeState before = snapshot(scope);
        String owner = required(processName, "processName");
        List<VirtualAlarmSnapshot> out = new ArrayList<>();
        boolean claimed = false;
        for (AlarmRecord alarm : state(scope).alarms.values()) {
            if (!owner.equals(alarm.ownerProcessName)) continue;
            if (alarm.ownerGeneration != generation) {
                alarm.ownerGeneration = generation;
                claimed = true;
            }
            out.add(new VirtualAlarmSnapshot(alarm.id, alarm.triggerAtMs, alarm.intervalMs, alarm.tokenPayload));
        }
        if (claimed) persistOrRestore(scope, before);
        out.sort(Comparator.comparingLong(VirtualAlarmSnapshot::triggerAtMs).thenComparing(VirtualAlarmSnapshot::alarmId));
        return Collections.unmodifiableList(out);
    }

    synchronized String maintenanceWarning() { return maintenanceWarning; }

    synchronized void deleteScopeBestEffort(Scope scope) {
        ScopeState removed = states.remove(scope);
        if (removed == null) return;
        for (AlarmRecord alarm : removed.alarms.values()) if (alarm.future != null) alarm.future.cancel(false);
        try {
            persist();
            maintenanceWarning = "";
        } catch (RuntimeException error) {
            states.put(scope, removed);
            for (AlarmRecord alarm : removed.alarms.values()) scheduleFuture(scope, alarm);
            maintenanceWarning = "VIRTUAL_SYSTEM_SERVICE_SCOPE_CLEANUP_FAILED:" + error.getClass().getSimpleName();
        }
    }

    synchronized int ensureNamespace(Scope scope, String namespace, int guestId) {
        ScopeState before = snapshot(scope);
        NamespaceState state = namespace(scope, namespace); Integer existing = state.guestToHost.get(guestId);
        if (existing != null) return existing;
        if (state.guestToHost.size() >= MAX_NAMESPACE_MAPPINGS) {
            throw new IllegalStateException("VIRTUAL_NAMESPACE_LIMIT_EXCEEDED");
        }
        int candidate; do { candidate = state.next++; } while (state.hostToGuest.containsKey(candidate));
        state.guestToHost.put(guestId, candidate); state.hostToGuest.put(candidate, guestId);
        persistOrRestore(scope, before); return candidate;
    }
    synchronized int hostIdIfPresent(Scope scope, String namespace, int guestId) {
        Integer value = namespace(scope, namespace).guestToHost.get(guestId); return value == null ? -1 : value;
    }
    synchronized int guestIdForHost(Scope scope, String namespace, int hostId) {
        Integer value = namespace(scope, namespace).hostToGuest.get(hostId); return value == null ? -1 : value;
    }
    synchronized int removeNamespace(Scope scope, String namespace, int guestId) {
        ScopeState before = snapshot(scope);
        NamespaceState state = namespace(scope, namespace); Integer host = state.guestToHost.remove(guestId);
        if (host == null) return -1; state.hostToGuest.remove(host);
        persistOrRestore(scope, before); return host;
    }
    synchronized int[] namespaceGuestIds(Scope scope, String namespace) {
        List<Integer> values = new ArrayList<>(namespace(scope, namespace).guestToHost.keySet());
        Collections.sort(values); int[] out = new int[values.size()];
        for (int index = 0; index < values.size(); index++) out[index] = values.get(index); return out;
    }

    private synchronized void scheduleFuture(Scope scope, AlarmRecord alarm) {
        if (alarm.future != null) alarm.future.cancel(false);
        long delay = Math.max(0L, alarm.triggerAtMs - System.currentTimeMillis());
        alarm.future = scheduler.schedule(() -> fire(scope, alarm.id), delay, TimeUnit.MILLISECONDS);
    }
    private void fire(Scope scope, String alarmId) {
        AlarmRecord alarm;
        List<IVirtualSystemServiceObserver> observers = new ArrayList<>();
        synchronized (this) {
            alarm = state(scope).alarms.get(alarmId);
            if (alarm == null) return;
            for (Client client : new ArrayList<>(clients)) {
                if (client.active() && client.scope().equals(scope)
                        && client.processName().equals(alarm.ownerProcessName)
                        && client.generation() == alarm.ownerGeneration
                        && client.observer() != null) {
                    observers.add(client.observer());
                }
            }
        }
        boolean delivered = false;
        for (IVirtualSystemServiceObserver observer : observers) {
            try { observer.onAlarm(alarmId); delivered = true; } catch (Exception ignored) { }
        }
        synchronized (this) {
            AlarmRecord current = state(scope).alarms.get(alarmId);
            if (current != alarm) return;
            ScopeState before = snapshot(scope);
            boolean reschedule = false;
            if (!delivered) {
                alarm.triggerAtMs = System.currentTimeMillis() + RETRY_WITHOUT_CLIENT_MS;
                reschedule = true;
            } else if (alarm.intervalMs > 0L) {
                long next = alarm.triggerAtMs + alarm.intervalMs;
                long now = System.currentTimeMillis();
                while (next <= now) next += alarm.intervalMs;
                alarm.triggerAtMs = next;
                reschedule = true;
            } else {
                state(scope).alarms.remove(alarmId);
            }
            try {
                persistOrRestore(scope, before);
            } catch (RuntimeException error) {
                maintenanceWarning = "VIRTUAL_ALARM_PERSIST_FAILED:"
                        + error.getClass().getSimpleName();
                AlarmRecord restored = state(scope).alarms.get(alarmId);
                if (restored != null) {
                    restored.triggerAtMs = System.currentTimeMillis() + RETRY_WITHOUT_CLIENT_MS;
                    scheduleFuture(scope, restored);
                }
                return;
            }
            if (reschedule) scheduleFuture(scope, alarm);
        }
    }
    private void notifyClipboard(Scope scope) {
        List<IVirtualSystemServiceObserver> observers = new ArrayList<>();
        synchronized (this) {
            for (Client client : new ArrayList<>(clients)) {
                if (client.active() && client.scope().equals(scope) && client.observer() != null) {
                    observers.add(client.observer());
                }
            }
        }
        for (IVirtualSystemServiceObserver observer : observers) {
            try { observer.onClipboardChanged(); } catch (Exception ignored) { }
        }
    }

    private ScopeState snapshot(Scope scope) {
        ScopeState current = states.get(scope);
        if (current == null) return null;
        ScopeState copy = new ScopeState();
        copy.clipboard = current.clipboard.clone();
        for (Map.Entry<AccountKey, AccountRecord> item : current.accounts.entrySet()) {
            AccountRecord account = new AccountRecord(item.getValue().password);
            account.tokens.putAll(item.getValue().tokens);
            copy.accounts.put(item.getKey(), account);
        }
        for (Map.Entry<String, AlarmRecord> item : current.alarms.entrySet()) {
            AlarmRecord alarm = item.getValue();
            AlarmRecord alarmCopy = new AlarmRecord(alarm.id, alarm.triggerAtMs, alarm.intervalMs,
                    alarm.tokenPayload, alarm.ownerProcessName, alarm.ownerGeneration);
            alarmCopy.future = alarm.future;
            copy.alarms.put(item.getKey(), alarmCopy);
        }
        for (Map.Entry<String, NamespaceState> item : current.namespaces.entrySet()) {
            NamespaceState namespace = new NamespaceState(item.getValue().next);
            namespace.guestToHost.putAll(item.getValue().guestToHost);
            namespace.hostToGuest.putAll(item.getValue().hostToGuest);
            copy.namespaces.put(item.getKey(), namespace);
        }
        return copy;
    }
    private void persistOrRestore(Scope scope, ScopeState before) {
        try { persist(); }
        catch (RuntimeException error) {
            if (before == null) states.remove(scope); else states.put(scope, before);
            throw error;
        }
    }

    private ScopeState state(Scope scope) { return states.computeIfAbsent(scope, ignored -> new ScopeState()); }
    private NamespaceState namespace(Scope scope, String namespace) {
        String normalized = normalizeRequired(namespace, "namespace");
        int seed = switch (normalized) { case "notification" -> 0x51000000; case "job" -> 0x52000000; default -> 0x53000000; };
        return state(scope).namespaces.computeIfAbsent(normalized, ignored -> new NamespaceState(seed));
    }
    private AccountRecord requireAccount(Scope scope, String name, String type) {
        AccountRecord record = state(scope).accounts.get(accountKey(name, type));
        if (record == null) throw new IllegalArgumentException("VIRTUAL_ACCOUNT_NOT_FOUND"); return record;
    }
    private static AccountKey accountKey(String name, String type) {
        return new AccountKey(required(name, "name"), required(type, "type"));
    }

    private void load() {
        if (!file.isFile()) return;
        try {
            JSONObject root = new JSONObject(Files.readString(file.toPath(), StandardCharsets.UTF_8));
            if (root.optInt("schemaVersion", -1) != SCHEMA) throw new IllegalStateException("Unsupported virtual service schema");
            JSONArray scopes = root.optJSONArray("scopes");
            if (scopes == null) return;
            for (int i = 0; i < scopes.length(); i++) {
                JSONObject item = scopes.getJSONObject(i);
                Scope scope = new Scope(item.getString("packageName"), item.getInt("virtualUserId"));
                ScopeState state = new ScopeState();
                state.clipboard = decode(item.optString("clipboard", ""));
                JSONArray accounts = item.optJSONArray("accounts");
                if (accounts != null) for (int j = 0; j < accounts.length(); j++) {
                    JSONObject account = accounts.getJSONObject(j);
                    AccountKey key = accountKey(account.getString("name"), account.getString("type"));
                    AccountRecord record = new AccountRecord(account.optString("password", ""));
                    JSONObject tokens = account.optJSONObject("tokens");
                    if (tokens != null) for (String tokenType : tokens.keySet()) record.tokens.put(tokenType, tokens.optString(tokenType, ""));
                    state.accounts.put(key, record);
                }
                JSONArray alarms = item.optJSONArray("alarms");
                if (alarms != null) for (int j = 0; j < alarms.length(); j++) {
                    JSONObject alarm = alarms.getJSONObject(j);
                    AlarmRecord record = new AlarmRecord(alarm.getString("id"), alarm.getLong("triggerAtMs"),
                            alarm.optLong("intervalMs", 0L), decode(alarm.optString("token", "")),
                            alarm.optString("ownerProcessName", scope.packageName()),
                            alarm.optLong("ownerGeneration", 0L));
                    state.alarms.put(record.id, record);
                }
                JSONObject namespaces = item.optJSONObject("namespaces");
                if (namespaces != null) for (String name : namespaces.keySet()) {
                    JSONObject namespace = namespaces.getJSONObject(name);
                    NamespaceState value = new NamespaceState(namespace.getInt("next"));
                    JSONArray mappings = namespace.optJSONArray("mappings");
                    if (mappings != null) for (int j = 0; j < mappings.length(); j++) {
                        JSONObject mapping = mappings.getJSONObject(j); int guest = mapping.getInt("guest"); int host = mapping.getInt("host");
                        value.guestToHost.put(guest, host); value.hostToGuest.put(host, guest);
                    }
                    state.namespaces.put(name, value);
                }
                states.put(scope, state);
            }
        } catch (Exception error) {
            throw new IllegalStateException("Cannot load virtual system-service store", error);
        }
    }
    private synchronized void persist() {
        try {
            JSONObject root = new JSONObject().put("schemaVersion", SCHEMA);
            JSONArray scopes = new JSONArray();
            List<Scope> keys = new ArrayList<>(states.keySet());
            keys.sort(Comparator.comparing(Scope::packageName).thenComparingInt(Scope::virtualUserId));
            for (Scope scope : keys) {
                ScopeState state = states.get(scope);
                JSONObject item = new JSONObject().put("packageName", scope.packageName())
                        .put("virtualUserId", scope.virtualUserId()).put("clipboard", encode(state.clipboard));
                JSONArray accounts = new JSONArray();
                for (Map.Entry<AccountKey, AccountRecord> account : state.accounts.entrySet()) {
                    JSONObject tokens = new JSONObject();
                    for (Map.Entry<String, String> token : account.getValue().tokens.entrySet()) tokens.put(token.getKey(), token.getValue());
                    accounts.put(new JSONObject().put("name", account.getKey().name).put("type", account.getKey().type)
                            .put("password", account.getValue().password).put("tokens", tokens));
                }
                item.put("accounts", accounts);
                JSONArray alarms = new JSONArray();
                for (AlarmRecord alarm : state.alarms.values()) alarms.put(new JSONObject().put("id", alarm.id)
                        .put("triggerAtMs", alarm.triggerAtMs).put("intervalMs", alarm.intervalMs)
                        .put("token", encode(alarm.tokenPayload))
                        .put("ownerProcessName", alarm.ownerProcessName)
                        .put("ownerGeneration", alarm.ownerGeneration));
                item.put("alarms", alarms);
                JSONObject namespaces = new JSONObject();
                for (Map.Entry<String, NamespaceState> namespace : state.namespaces.entrySet()) {
                    JSONArray mappings = new JSONArray();
                    for (Map.Entry<Integer, Integer> mapping : namespace.getValue().guestToHost.entrySet()) {
                        mappings.put(new JSONObject().put("guest", mapping.getKey()).put("host", mapping.getValue()));
                    }
                    namespaces.put(namespace.getKey(), new JSONObject().put("next", namespace.getValue().next).put("mappings", mappings));
                }
                item.put("namespaces", namespaces); scopes.put(item);
            }
            root.put("scopes", scopes);
            File parent = file.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) throw new IllegalStateException("Cannot create store directory");
            File temp = new File(parent, file.getName() + ".tmp");
            byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
            try {
                try (FileOutputStream out = new FileOutputStream(temp)) {
                    out.write(bytes); out.flush(); out.getFD().sync();
                }
                try { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException error) {
                    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                if (temp.exists()) temp.delete();
            }
        } catch (Exception error) {
            throw new IllegalStateException("Cannot persist virtual system-service store", error);
        }
    }

    @Override public synchronized void close() {
        for (ScopeState state : states.values()) for (AlarmRecord alarm : state.alarms.values()) if (alarm.future != null) alarm.future.cancel(false);
        clients.clear(); scheduler.shutdownNow();
    }
    private static byte[] boundedPayload(byte[] value, String name) {
        byte[] copy = value == null ? new byte[0] : value.clone();
        if (copy.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException(name + " exceeds " + MAX_PAYLOAD_BYTES + " bytes");
        }
        return copy;
    }
    private static String encode(byte[] value) {
        if (value == null || value.length == 0) return "";
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte item : value) out.append(Character.forDigit((item >>> 4) & 0xF, 16)).append(Character.forDigit(item & 0xF, 16));
        return out.toString();
    }
    private static byte[] decode(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) return new byte[0];
        if ((normalized.length() & 1) != 0) throw new IllegalArgumentException("Invalid hex payload");
        byte[] out = new byte[normalized.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int high = Character.digit(normalized.charAt(i * 2), 16); int low = Character.digit(normalized.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) throw new IllegalArgumentException("Invalid hex payload"); out[i] = (byte) ((high << 4) | low);
        }
        return out;
    }
    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_KEY_CHARS) {
            throw new IllegalArgumentException(name + " exceeds " + MAX_KEY_CHARS + " characters");
        }
        return normalized;
    }
    private static String normalizeRequired(String value, String name) { return normalize(required(value, name)); }
    private static String normalize(String value) { return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT); }
    private static String safe(String value) {
        String normalized = value == null ? "" : value;
        if (normalized.length() > MAX_SECRET_CHARS) {
            throw new IllegalArgumentException("virtual secret exceeds " + MAX_SECRET_CHARS + " characters");
        }
        return normalized;
    }
}
