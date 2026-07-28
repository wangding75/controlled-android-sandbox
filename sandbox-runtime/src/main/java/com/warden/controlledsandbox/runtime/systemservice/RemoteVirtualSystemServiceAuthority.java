package com.warden.controlledsandbox.runtime.systemservice;

import android.os.Parcel;
import android.os.Parcelable;
import com.warden.controlledsandbox.contract.IVirtualSystemServiceObserver;
import com.warden.controlledsandbox.contract.IVirtualSystemServiceSession;
import com.warden.controlledsandbox.contract.VirtualAccountSnapshot;
import com.warden.controlledsandbox.contract.VirtualAlarmSnapshot;
import com.warden.controlledsandbox.contract.VirtualJobSnapshot;
import com.warden.controlledsandbox.contract.VirtualNotificationChannelSnapshot;
import com.warden.controlledsandbox.contract.VirtualNotificationSnapshot;
import com.warden.controlledsandbox.framework.identity.VirtualSystemServiceAuthority;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;

/** Guest-side adapter for a scoped Binder-owned virtual system-service capability. */
public final class RemoteVirtualSystemServiceAuthority implements VirtualSystemServiceAuthority {
    private static final int MAX_PAYLOAD_BYTES = 512 * 1024;
    private final IVirtualSystemServiceSession session;
    private final ClassLoader classLoader;
    private final ConcurrentMap<String, Runnable> alarmDeliveries = new ConcurrentHashMap<>();
    private volatile Runnable clipboardListener = () -> { };
    private volatile BiFunction<Integer, Object, Boolean> jobReadyListener = (id, payload) -> false;
    private volatile boolean closed;
    private final IVirtualSystemServiceObserver observer = new IVirtualSystemServiceObserver.Stub() {
        @Override public void onClipboardChanged() { clipboardListener.run(); }
        @Override public void onAlarm(String alarmId) {
            Runnable delivery = alarmDeliveries.get(alarmId);
            if (delivery != null) delivery.run();
        }
        @Override public boolean onJobReady(int guestJobId, byte[] jobPayload) {
            return Boolean.TRUE.equals(jobReadyListener.apply(guestJobId, unmarshal(jobPayload)));
        }
    };

    public RemoteVirtualSystemServiceAuthority(IVirtualSystemServiceSession session, ClassLoader classLoader) {
        this.session = java.util.Objects.requireNonNull(session, "session");
        this.classLoader = classLoader == null ? getClass().getClassLoader() : classLoader;
        call(() -> { this.session.registerObserver(observer); return null; });
    }

    @Override public Object clipboard() { return unmarshal(call(session::getClipboard)); }
    @Override public void setClipboard(Object value) { call(() -> { session.setClipboard(marshal(value)); return null; }); }
    @Override public void clearClipboard() { call(() -> { session.clearClipboard(); return null; }); }
    @Override public void setClipboardChangeListener(Runnable listener) {
        clipboardListener = listener == null ? () -> { } : listener;
    }

    @Override public List<AccountRecord> accounts(String requestedType) {
        List<VirtualAccountSnapshot> snapshots = call(() -> session.listAccounts(requestedType == null ? "" : requestedType));
        List<AccountRecord> result = new ArrayList<>();
        if (snapshots != null) for (VirtualAccountSnapshot snapshot : snapshots) {
            Map<String, String> tokens = new LinkedHashMap<>();
            for (int index = 0; index < snapshot.tokenTypes().size(); index++) {
                tokens.put(snapshot.tokenTypes().get(index), snapshot.tokens().get(index));
            }
            result.add(new AccountRecord(snapshot.name(), snapshot.type(), snapshot.password(),
                    Collections.unmodifiableMap(tokens)));
        }
        return Collections.unmodifiableList(result);
    }
    @Override public boolean addAccount(String name, String type, String password) {
        return call(() -> session.addAccount(name, type, password));
    }
    @Override public boolean removeAccount(String name, String type) {
        return call(() -> session.removeAccount(name, type));
    }
    @Override public void setPassword(String name, String type, String password) {
        call(() -> { session.setPassword(name, type, password); return null; });
    }
    @Override public String password(String name, String type) { return call(() -> session.getPassword(name, type)); }
    @Override public void setToken(String name, String type, String tokenType, String token) {
        call(() -> { session.setAuthToken(name, type, tokenType, token); return null; });
    }
    @Override public String token(String name, String type, String tokenType) {
        return call(() -> session.peekAuthToken(name, type, tokenType));
    }
    @Override public void invalidateToken(String accountType, String token) {
        call(() -> { session.invalidateAuthToken(accountType, token); return null; });
    }

    @Override public void scheduleAlarm(String alarmId, long triggerAtMs, long intervalMs,
                                        Object token, Runnable delivery) {
        Runnable requiredDelivery = java.util.Objects.requireNonNull(delivery, "delivery");
        Runnable registeredDelivery = intervalMs == 0L
                ? () -> { try { requiredDelivery.run(); } finally { alarmDeliveries.remove(alarmId); } }
                : requiredDelivery;
        alarmDeliveries.put(alarmId, registeredDelivery);
        try { call(() -> { session.scheduleAlarm(alarmId, triggerAtMs, intervalMs, marshal(token)); return null; }); }
        catch (RuntimeException error) { alarmDeliveries.remove(alarmId); throw error; }
    }
    @Override public boolean cancelAlarm(String alarmId) {
        boolean removed = call(() -> session.cancelAlarm(alarmId));
        if (removed) alarmDeliveries.remove(alarmId); return removed;
    }
    @Override public List<AlarmRecord> alarms() {
        List<VirtualAlarmSnapshot> snapshots = call(session::listAlarms);
        List<AlarmRecord> result = new ArrayList<>();
        if (snapshots != null) for (VirtualAlarmSnapshot snapshot : snapshots) {
            Object token = unmarshal(snapshot.tokenPayload());
            if (token != null) result.add(new AlarmRecord(snapshot.alarmId(), snapshot.triggerAtMs(),
                    snapshot.intervalMs(), token));
        }
        return Collections.unmodifiableList(result);
    }

    @Override public NotificationRecord reserveNotification(int guestId, String guestTag, String channelId) {
        return notification(call(() -> session.reserveNotification(guestId, safe(guestTag), safe(channelId))));
    }
    @Override public void commitNotification(int guestId, String guestTag, String channelId, Object payload) {
        call(() -> { session.commitNotification(guestId, safe(guestTag), safe(channelId), marshal(payload)); return null; });
    }
    @Override public boolean removeNotification(int guestId, String guestTag) {
        return call(() -> session.removeNotification(guestId, safe(guestTag)));
    }
    @Override public List<NotificationRecord> notifications() {
        List<VirtualNotificationSnapshot> values = call(session::listNotifications);
        List<NotificationRecord> out = new ArrayList<>();
        if (values != null) for (VirtualNotificationSnapshot value : values) out.add(notification(value));
        return Collections.unmodifiableList(out);
    }
    @Override public void upsertNotificationChannel(String kind, String id, String groupId, Object payload) {
        call(() -> { session.upsertNotificationChannel(kind, id, safe(groupId), marshal(payload)); return null; });
    }
    @Override public boolean removeNotificationChannel(String kind, String id) {
        return call(() -> session.removeNotificationChannel(kind, id));
    }
    @Override public List<NotificationChannelRecord> notificationChannels() {
        List<VirtualNotificationChannelSnapshot> values = call(session::listNotificationChannels);
        List<NotificationChannelRecord> out = new ArrayList<>();
        if (values != null) for (VirtualNotificationChannelSnapshot value : values) {
            out.add(new NotificationChannelRecord(value.kind(), value.id(), value.groupId(),
                    unmarshal(value.payload()), value.updatedAtMs()));
        }
        return Collections.unmodifiableList(out);
    }

    @Override public JobRecord reserveJob(int guestId, Object payload) {
        return job(call(() -> session.reserveJob(guestId, marshal(payload))));
    }
    @Override public void commitJob(int guestId) { call(() -> { session.commitJob(guestId); return null; }); }
    @Override public boolean removeJob(int guestId) { return call(() -> session.removeJob(guestId)); }
    @Override public List<JobRecord> jobs() {
        List<VirtualJobSnapshot> values = call(session::listJobs);
        List<JobRecord> out = new ArrayList<>();
        if (values != null) for (VirtualJobSnapshot value : values) out.add(job(value));
        return Collections.unmodifiableList(out);
    }
    @Override public void finishJob(int guestId, boolean needsReschedule) {
        call(() -> { session.finishJob(guestId, needsReschedule); return null; });
    }
    @Override public void setJobReadyListener(BiFunction<Integer, Object, Boolean> listener) {
        jobReadyListener = listener == null ? (id, payload) -> false : listener;
    }

    @Override public NamespaceMapping ensureNamespace(String namespace, int guestId) {
        int before = call(() -> session.hostIdIfPresent(namespace, guestId));
        int host = call(() -> session.ensureNamespace(namespace, guestId));
        return new NamespaceMapping(host, before < 0);
    }
    @Override public Integer hostIdIfPresent(String namespace, int guestId) {
        int value = call(() -> session.hostIdIfPresent(namespace, guestId)); return value < 0 ? null : value;
    }
    @Override public Integer guestId(String namespace, int hostId) {
        int value = call(() -> session.guestIdForHost(namespace, hostId)); return value < 0 ? null : value;
    }
    @Override public Integer removeNamespace(String namespace, int guestId) {
        int value = call(() -> session.removeNamespace(namespace, guestId)); return value < 0 ? null : value;
    }
    @Override public List<Integer> guestIds(String namespace) {
        int[] values = call(() -> session.listNamespaceGuestIds(namespace));
        List<Integer> result = new ArrayList<>();
        if (values != null) for (int value : values) result.add(value);
        return Collections.unmodifiableList(result);
    }
    @Override public int namespaceSize(String namespace) { return guestIds(namespace).size(); }

    @Override public void close() {
        if (closed) return; closed = true; alarmDeliveries.clear(); clipboardListener = () -> { };
        jobReadyListener = (id, payload) -> false;
        try { session.close(); } catch (Exception ignored) { }
    }

    private NotificationRecord notification(VirtualNotificationSnapshot value) {
        return new NotificationRecord(value.guestId(), value.hostId(), value.guestTag(), value.hostTag(),
                value.channelId(), value.state(), unmarshal(value.payload()), value.updatedAtMs());
    }
    private JobRecord job(VirtualJobSnapshot value) {
        return new JobRecord(value.guestId(), value.hostId(), value.state(), value.ownerProcessName(),
                value.ownerGeneration(), unmarshal(value.payload()), value.updatedAtMs());
    }
    private byte[] marshal(Object value) {
        if (value == null) return new byte[0];
        if (!(value instanceof Parcelable)) {
            throw new SecurityException("VIRTUAL_SYSTEM_SERVICE_VALUE_NOT_PARCELABLE:" + value.getClass().getName());
        }
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeParcelable((Parcelable) value, 0);
            byte[] payload = parcel.marshall();
            if (payload.length > MAX_PAYLOAD_BYTES) {
                throw new SecurityException("VIRTUAL_SYSTEM_SERVICE_PAYLOAD_TOO_LARGE");
            }
            return payload;
        } finally { parcel.recycle(); }
    }
    private Object unmarshal(byte[] payload) {
        if (payload == null || payload.length == 0) return null;
        Parcel parcel = Parcel.obtain();
        try {
            parcel.unmarshall(payload, 0, payload.length); parcel.setDataPosition(0);
            return parcel.readParcelable(classLoader);
        } finally { parcel.recycle(); }
    }
    private <T> T call(RemoteCall<T> operation) {
        if (closed) throw new IllegalStateException("VIRTUAL_SYSTEM_SERVICE_SESSION_CLOSED");
        try { return operation.run(); }
        catch (RuntimeException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException("VIRTUAL_SYSTEM_SERVICE_REMOTE_FAILURE", error); }
    }
    private static String safe(String value) { return value == null ? "" : value.trim(); }
    @FunctionalInterface private interface RemoteCall<T> { T run() throws Exception; }
}
