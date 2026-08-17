package com.warden.controlledsandbox.runtime.systemservice;

import android.os.Parcel;
import android.os.Parcelable;
import com.warden.controlledsandbox.contract.IVirtualJobExecution;
import com.warden.controlledsandbox.contract.IVirtualSystemServiceObserver;
import com.warden.controlledsandbox.contract.IVirtualSystemServiceSession;
import com.warden.controlledsandbox.contract.VirtualAccountSnapshot;
import com.warden.controlledsandbox.contract.VirtualAccountPage;
import com.warden.controlledsandbox.contract.VirtualAccountSummary;
import com.warden.controlledsandbox.contract.VirtualAlarmPage;
import com.warden.controlledsandbox.contract.VirtualJobPage;
import com.warden.controlledsandbox.contract.VirtualNotificationChannelPage;
import com.warden.controlledsandbox.contract.VirtualNotificationPage;
import com.warden.controlledsandbox.contract.VirtualPageRequest;
import com.warden.controlledsandbox.contract.VirtualPageView;
import com.warden.controlledsandbox.contract.VirtualPendingIntentPage;
import com.warden.controlledsandbox.contract.VirtualSettingPage;
import com.warden.controlledsandbox.contract.VirtualShortcutPage;
import com.warden.controlledsandbox.contract.VirtualWidgetPage;
import com.warden.controlledsandbox.contract.VirtualAlarmSnapshot;
import com.warden.controlledsandbox.contract.VirtualJobSnapshot;
import com.warden.controlledsandbox.contract.VirtualNotificationChannelSnapshot;
import com.warden.controlledsandbox.contract.VirtualNotificationSnapshot;
import com.warden.controlledsandbox.contract.VirtualPendingIntentSnapshot;
import com.warden.controlledsandbox.contract.VirtualJobParametersSnapshot;
import com.warden.controlledsandbox.contract.VirtualJobWorkItemSnapshot;
import com.warden.controlledsandbox.contract.VirtualDeviceServiceProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualInteractionProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualNetworkServiceProfileSnapshot;
import com.warden.controlledsandbox.contract.ApplicationEnvironmentProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualCompatibilityProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPolicyServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualMediaCommunicationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPeripheralServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPrivilegedServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualShortcutSnapshot;
import com.warden.controlledsandbox.contract.VirtualWidgetSnapshot;
import com.warden.controlledsandbox.contract.VirtualUsageEventSnapshot;
import com.warden.controlledsandbox.contract.VirtualSettingSnapshot;
import com.warden.controlledsandbox.framework.identity.VirtualSystemServiceAuthority;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Guest-side adapter for a scoped Binder-owned virtual system-service capability. */
public final class RemoteVirtualSystemServiceAuthority implements VirtualSystemServiceAuthority {
    private static final int PARCEL_PAYLOAD_PARCELABLE = 1;
    private static final int PARCEL_PAYLOAD_PARCELABLE_ARRAY = 2;
    private static final int MAX_PAYLOAD_BYTES = 512 * 1024;
    private static final int PAGE_MAX_ITEMS = 64;
    private static final int PAGE_MAX_BYTES = 224 * 1024;
    private static final int MAX_PAGE_COUNT = 4096;
    private final IVirtualSystemServiceSession session;
    private final ClassLoader classLoader;
    private final ConcurrentMap<String, Runnable> alarmDeliveries = new ConcurrentHashMap<>();
    private volatile Runnable clipboardListener = () -> { };
    private volatile VirtualDeviceServiceProfileSnapshot deviceProfile;
    private volatile VirtualInteractionProfileSnapshot interactionProfile;
    private volatile VirtualNetworkServiceProfileSnapshot networkProfile;
    private volatile ApplicationEnvironmentProfileSnapshot applicationEnvironmentProfile;
    private volatile VirtualCompatibilityProfileSnapshot compatibilityProfile;
    private volatile VirtualPolicyServicesProfileSnapshot policyServicesProfile;
    private volatile VirtualMediaCommunicationProfileSnapshot mediaCommunicationProfile;
    private volatile VirtualPeripheralServicesProfileSnapshot peripheralServicesProfile;
    private volatile VirtualPrivilegedServicesProfileSnapshot privilegedServicesProfile;
    private volatile java.util.function.BiConsumer<String, String> applicationEnvironmentChangeListener = (domain, key) -> { };
    private volatile java.util.function.Function<AlarmRecord, Boolean> recoveredAlarmDelivery = value -> false;
    private volatile JobExecutionListener jobExecutionListener = new JobExecutionListener() {
        @Override public boolean onStart(int guestJobId, Object jobPayload,
                JobParametersRecord parameters, JobExecution execution) { return false; }
        @Override public boolean onStop(int guestJobId, JobParametersRecord parameters) { return true; }
    };
    private volatile boolean closed;
    private final IVirtualSystemServiceObserver observer = new IVirtualSystemServiceObserver.Stub() {
        @Override public void onClipboardChanged() { clipboardListener.run(); }
        @Override public void onDeviceServiceProfileChanged(long policyVersion) {
            VirtualDeviceServiceProfileSnapshot current = call(session::getDeviceServiceProfile);
            if (current != null && current.policyVersion() >= policyVersion) deviceProfile = current;
        }
        @Override public void onInteractionProfileChanged(long policyVersion) {
            VirtualInteractionProfileSnapshot current = call(session::getInteractionProfile);
            if (current != null && current.policyVersion() >= policyVersion) interactionProfile = current;
        }
        @Override public void onNetworkServiceProfileChanged(long policyVersion) {
            VirtualNetworkServiceProfileSnapshot current = call(session::getNetworkServiceProfile);
            if (current != null && current.policyVersion() >= policyVersion) networkProfile = current;
        }
        @Override public void onApplicationEnvironmentProfileChanged(long policyVersion) {
            ApplicationEnvironmentProfileSnapshot current = call(session::getApplicationEnvironmentProfile);
            if (current != null && current.policyVersion() >= policyVersion) applicationEnvironmentProfile = current;
        }
        @Override public void onCompatibilityProfileChanged(long policyVersion) {
            VirtualCompatibilityProfileSnapshot current = call(session::getCompatibilityProfile);
            if (current != null && current.policyVersion() >= policyVersion) compatibilityProfile = current;
        }
        @Override public void onPolicyServicesProfileChanged(long policyVersion) {
            VirtualPolicyServicesProfileSnapshot current = call(session::getPolicyServicesProfile);
            if (current != null && current.policyVersion() >= policyVersion) policyServicesProfile = current;
        }
        @Override public void onMediaCommunicationProfileChanged(long policyVersion) {
            VirtualMediaCommunicationProfileSnapshot current = call(session::getMediaCommunicationProfile);
            if (current != null && current.policyVersion() >= policyVersion) mediaCommunicationProfile = current;
        }
        @Override public void onPeripheralServicesProfileChanged(long policyVersion) {
            VirtualPeripheralServicesProfileSnapshot current = call(session::getPeripheralServicesProfile);
            if (current != null && current.policyVersion() >= policyVersion) peripheralServicesProfile = current;
        }
        @Override public void onPrivilegedServicesProfileChanged(long policyVersion) {
            VirtualPrivilegedServicesProfileSnapshot current = call(session::getPrivilegedServicesProfile);
            if (current != null && current.policyVersion() >= policyVersion) privilegedServicesProfile = current;
        }
        @Override public void onApplicationEnvironmentDataChanged(String domain, String key) {
            applicationEnvironmentChangeListener.accept(domain == null ? "" : domain, key == null ? "" : key);
        }
        @Override public void onAlarm(VirtualAlarmSnapshot alarm) {
            if (alarm == null) return;
            Runnable delivery = alarmDeliveries.get(alarm.alarmId());
            if (delivery != null) { delivery.run(); return; }
            AlarmRecord recovered = alarm(alarm);
            if (!Boolean.TRUE.equals(recoveredAlarmDelivery.apply(recovered))) {
                throw new IllegalStateException("VIRTUAL_ALARM_RECOVERED_TARGET_UNAVAILABLE:" + alarm.alarmId());
            }
        }
        @Override public boolean onJobStart(int guestJobId, byte[] jobPayload,
                VirtualJobParametersSnapshot parameters, IVirtualJobExecution execution) {
            if (parameters == null || execution == null) return false;
            return jobExecutionListener.onStart(guestJobId, unmarshal(jobPayload),
                    parameters(parameters), execution(execution));
        }
        @Override public boolean onJobStop(int guestJobId, VirtualJobParametersSnapshot parameters) {
            return parameters != null && jobExecutionListener.onStop(guestJobId, parameters(parameters));
        }
    };

    public RemoteVirtualSystemServiceAuthority(IVirtualSystemServiceSession session, ClassLoader classLoader) {
        this.session = java.util.Objects.requireNonNull(session, "session");
        this.classLoader = classLoader == null ? getClass().getClassLoader() : classLoader;
        call(() -> { this.session.registerObserver(observer); return null; });
        deviceProfile = call(this.session::getDeviceServiceProfile);
        if (deviceProfile == null) throw new IllegalStateException("VIRTUAL_DEVICE_PROFILE_MISSING");
        interactionProfile = call(this.session::getInteractionProfile);
        if (interactionProfile == null) throw new IllegalStateException("VIRTUAL_INTERACTION_PROFILE_MISSING");
        networkProfile = call(this.session::getNetworkServiceProfile);
        if (networkProfile == null) throw new IllegalStateException("VIRTUAL_NETWORK_PROFILE_MISSING");
        applicationEnvironmentProfile = call(this.session::getApplicationEnvironmentProfile);
        if (applicationEnvironmentProfile == null) {
            throw new IllegalStateException("VIRTUAL_APPLICATION_ENVIRONMENT_PROFILE_MISSING");
        }
        compatibilityProfile = call(this.session::getCompatibilityProfile);
        if (compatibilityProfile == null) throw new IllegalStateException("VIRTUAL_COMPATIBILITY_PROFILE_MISSING");
        policyServicesProfile = call(this.session::getPolicyServicesProfile);
        if (policyServicesProfile == null) throw new IllegalStateException("VIRTUAL_POLICY_SERVICES_PROFILE_MISSING");
        mediaCommunicationProfile = call(this.session::getMediaCommunicationProfile);
        if (mediaCommunicationProfile == null) {
            throw new IllegalStateException("VIRTUAL_MEDIA_COMMUNICATION_PROFILE_MISSING");
        }
        peripheralServicesProfile = call(this.session::getPeripheralServicesProfile);
        if (peripheralServicesProfile == null) {
            throw new IllegalStateException("VIRTUAL_PERIPHERAL_SERVICES_PROFILE_MISSING");
        }
        privilegedServicesProfile = call(this.session::getPrivilegedServicesProfile);
        if (privilegedServicesProfile == null) {
            throw new IllegalStateException("VIRTUAL_PRIVILEGED_SERVICES_PROFILE_MISSING");
        }
    }

    @Override public VirtualDeviceServiceProfileSnapshot deviceServiceProfile() { return deviceProfile; }
    @Override public VirtualInteractionProfileSnapshot interactionProfile() { return interactionProfile; }
    @Override public VirtualNetworkServiceProfileSnapshot networkServiceProfile() { return networkProfile; }
    @Override public ApplicationEnvironmentProfileSnapshot applicationEnvironmentProfile() {
        return applicationEnvironmentProfile;
    }
    @Override public VirtualCompatibilityProfileSnapshot compatibilityProfile() { return compatibilityProfile; }
    @Override public VirtualPolicyServicesProfileSnapshot policyServicesProfile() { return policyServicesProfile; }
    @Override public VirtualMediaCommunicationProfileSnapshot mediaCommunicationProfile() {
        return mediaCommunicationProfile;
    }
    @Override public VirtualPeripheralServicesProfileSnapshot peripheralServicesProfile() {
        return peripheralServicesProfile;
    }
    @Override public VirtualPrivilegedServicesProfileSnapshot privilegedServicesProfile() {
        return privilegedServicesProfile;
    }
    @Override public List<VirtualShortcutSnapshot> shortcuts() {
        return collectPages(session::listShortcutsPage, VirtualPageView::items);
    }
    @Override public boolean replaceDynamicShortcuts(List<VirtualShortcutSnapshot> shortcuts) {
        return call(() -> session.replaceDynamicShortcuts(shortcuts));
    }
    @Override public boolean addDynamicShortcuts(List<VirtualShortcutSnapshot> shortcuts) {
        return call(() -> session.addDynamicShortcuts(shortcuts));
    }
    @Override public void removeShortcuts(List<String> shortcutIds) {
        call(() -> { session.removeShortcuts(shortcutIds); return null; });
    }
    @Override public void setShortcutsEnabled(List<String> shortcutIds, boolean enabled, String disabledMessage) {
        call(() -> { session.setShortcutsEnabled(shortcutIds, enabled, safe(disabledMessage)); return null; });
    }
    @Override public void reportShortcutUsed(String shortcutId) {
        call(() -> { session.reportShortcutUsed(shortcutId); return null; });
    }
    @Override public int allocateAppWidgetId(int hostId) { return call(() -> session.allocateAppWidgetId(hostId)); }
    @Override public boolean deleteAppWidgetId(int appWidgetId) { return call(() -> session.deleteAppWidgetId(appWidgetId)); }
    @Override public List<VirtualWidgetSnapshot> appWidgets(int hostId) {
        return collectPages(request -> session.listAppWidgetsPage(hostId, request),
                page -> RemoteVirtualPageHydrator.widgets(session, page));
    }
    @Override public boolean bindAppWidgetId(int appWidgetId, String providerPackage, String providerClass) {
        return call(() -> session.bindAppWidgetId(appWidgetId, safe(providerPackage), safe(providerClass)));
    }
    @Override public void updateAppWidget(VirtualWidgetSnapshot appWidget) {
        call(() -> { session.updateAppWidget(appWidget); return null; });
    }
    @Override public void reportUsageEvent(VirtualUsageEventSnapshot event) {
        call(() -> { session.reportUsageEvent(event); return null; });
    }
    @Override public List<VirtualUsageEventSnapshot> usageEvents(long beginMs, long endMs, int limit) {
        List<VirtualUsageEventSnapshot> values = call(() -> session.queryUsageEvents(beginMs, endMs, limit));
        return values == null ? List.of() : List.copyOf(values);
    }
    @Override public VirtualSettingSnapshot setting(String namespace, String key) {
        return call(() -> session.getSetting(namespace, key));
    }
    @Override public void putSetting(VirtualSettingSnapshot setting) {
        call(() -> { session.putSetting(setting); return null; });
    }
    @Override public boolean deleteSetting(String namespace, String key) {
        return call(() -> session.deleteSetting(namespace, key));
    }
    @Override public List<VirtualSettingSnapshot> settings(String namespace) {
        return collectPages(request -> session.listSettingsPage(namespace, request), VirtualPageView::items);
    }
    @Override public void setApplicationEnvironmentChangeListener(
            java.util.function.BiConsumer<String, String> listener) {
        applicationEnvironmentChangeListener = listener == null ? (domain, key) -> { } : listener;
    }

    @Override public Object clipboard() { return unmarshal(call(session::getClipboard)); }
    @Override public void setClipboard(Object value) { call(() -> { session.setClipboard(marshal(value)); return null; }); }
    @Override public void clearClipboard() { call(() -> { session.clearClipboard(); return null; }); }
    @Override public void setClipboardChangeListener(Runnable listener) {
        clipboardListener = listener == null ? () -> { } : listener;
    }

    @Override public List<AccountRecord> accounts(String requestedType) {
        String type = requestedType == null ? "" : requestedType;
        List<VirtualAccountSummary> summaries = collectPages(
                request -> session.listAccountsPage(type, request), VirtualPageView::items);
        List<AccountRecord> result = new ArrayList<>(summaries.size());
        for (VirtualAccountSummary summary : summaries) {
            result.add(new AccountRecord(summary.name(), summary.type(), "", Map.of()));
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

    @Override public PendingIntentRecord reservePendingIntent(PendingIntentRecord candidate,
            boolean noCreate, boolean cancelCurrent, boolean updateCurrent) {
        VirtualPendingIntentSnapshot value = new VirtualPendingIntentSnapshot(candidate.tokenId(),
                candidate.kind(), candidate.requestCode(), candidate.action(), candidate.component(),
                candidate.data(), candidate.filterIdentity(), candidate.flags(), candidate.creatorPackage(), candidate.creatorUid(),
                candidate.requiredPermission(), candidate.ownerProcessName(), candidate.ownerGeneration(),
                candidate.packageRevision(), marshal(candidate.payload()), candidate.sends(),
                candidate.cancelled(), candidate.updatedAtMs());
        VirtualPendingIntentSnapshot result = call(() -> session.reservePendingIntent(
                value, noCreate, cancelCurrent, updateCurrent));
        return result == null ? null : pendingIntent(result);
    }
    @Override public PendingIntentRecord markPendingIntentSent(String tokenId) {
        VirtualPendingIntentSnapshot value = call(() -> session.markPendingIntentSent(tokenId));
        return value == null ? null : pendingIntent(value);
    }
    @Override public boolean cancelPendingIntent(String tokenId) {
        return call(() -> session.cancelPendingIntent(tokenId));
    }
    @Override public List<PendingIntentRecord> pendingIntents() {
        List<VirtualPendingIntentSnapshot> values = collectPages(session::listPendingIntentsPage,
                page -> RemoteVirtualPageHydrator.pendingIntents(session, page));
        List<PendingIntentRecord> out = new ArrayList<>(values.size());
        for (VirtualPendingIntentSnapshot value : values) out.add(pendingIntent(value));
        return Collections.unmodifiableList(out);
    }

    @Override public void scheduleAlarm(AlarmRecord candidate, Runnable delivery) {
        java.util.Objects.requireNonNull(candidate, "candidate");
        Runnable requiredDelivery = java.util.Objects.requireNonNull(delivery, "delivery");
        Runnable registeredDelivery = candidate.intervalMs() == 0L
                ? () -> { try { requiredDelivery.run(); } finally { alarmDeliveries.remove(candidate.alarmId()); } }
                : requiredDelivery;
        alarmDeliveries.put(candidate.alarmId(), registeredDelivery);
        byte[] payload = candidate.token() instanceof Parcelable ? marshal(candidate.token()) : new byte[0];
        VirtualAlarmSnapshot snapshot = new VirtualAlarmSnapshot(candidate.alarmId(), candidate.triggerAtMs(),
                candidate.intervalMs(), candidate.exact(), candidate.allowWhileIdle(), candidate.deliveryPath(),
                candidate.pendingIntentTokenId(), candidate.ownerProcessName(), candidate.ownerGeneration(),
                candidate.packageRevision(), payload, candidate.deliveryCount(), candidate.updatedAtMs(),
                candidate.alarmClock(), candidate.alarmClockShowIntent() instanceof Parcelable
                        ? marshal(candidate.alarmClockShowIntent()) : new byte[0]);
        try { call(() -> { session.scheduleAlarm(snapshot); return null; }); }
        catch (RuntimeException error) { alarmDeliveries.remove(candidate.alarmId()); throw error; }
    }
    @Override public boolean cancelAlarm(String alarmId) {
        boolean removed = call(() -> session.cancelAlarm(alarmId));
        if (removed) alarmDeliveries.remove(alarmId); return removed;
    }
    @Override public List<AlarmRecord> alarms() {
        List<VirtualAlarmSnapshot> snapshots = collectPages(session::listAlarmsPage,
                page -> RemoteVirtualPageHydrator.alarms(session, page));
        List<AlarmRecord> result = new ArrayList<>(snapshots.size());
        for (VirtualAlarmSnapshot snapshot : snapshots) result.add(alarm(snapshot));
        return Collections.unmodifiableList(result);
    }
    @Override public void setRecoveredAlarmDelivery(java.util.function.Function<AlarmRecord, Boolean> delivery) {
        recoveredAlarmDelivery = delivery == null ? value -> false : delivery;
    }

    @Override public NotificationRecord reserveNotification(NotificationRecord candidate) {
        return notification(call(() -> session.reserveNotification(notification(candidate, false))));
    }
    @Override public void commitNotification(NotificationRecord value) {
        call(() -> { session.commitNotification(notification(value, true)); return null; });
    }
    @Override public boolean removeNotification(int guestId, String guestTag) {
        return call(() -> session.removeNotification(guestId, safe(guestTag)));
    }
    @Override public List<NotificationRecord> notifications() {
        List<VirtualNotificationSnapshot> values = collectPages(session::listNotificationsPage,
                page -> RemoteVirtualPageHydrator.notifications(session, page));
        List<NotificationRecord> out = new ArrayList<>(values.size());
        for (VirtualNotificationSnapshot value : values) out.add(notification(value));
        return Collections.unmodifiableList(out);
    }
    @Override public void upsertNotificationChannel(NotificationChannelRecord value) {
        call(() -> { session.upsertNotificationChannel(new VirtualNotificationChannelSnapshot(
                value.kind(), value.id(), safe(value.groupId()), value.packageRevision(),
                marshal(value.payload()), value.updatedAtMs())); return null; });
    }
    @Override public boolean removeNotificationChannel(String kind, String id) {
        return call(() -> session.removeNotificationChannel(kind, id));
    }
    @Override public List<NotificationChannelRecord> notificationChannels() {
        List<VirtualNotificationChannelSnapshot> values = collectPages(session::listNotificationChannelsPage,
                page -> RemoteVirtualPageHydrator.channels(session, page));
        List<NotificationChannelRecord> out = new ArrayList<>(values.size());
        for (VirtualNotificationChannelSnapshot value : values) {
            out.add(new NotificationChannelRecord(value.kind(), value.id(), value.groupId(),
                    value.packageRevision(), unmarshal(value.payload()), value.updatedAtMs()));
        }
        return Collections.unmodifiableList(out);
    }

    @Override public JobRecord reserveJob(JobRecord candidate) {
        VirtualJobSnapshot snapshot = new VirtualJobSnapshot(candidate.guestId(), Math.max(0, candidate.hostId()),
                candidate.state(), candidate.ownerProcessName(), candidate.ownerGeneration(),
                candidate.packageRevision(), candidate.requiredNetworkType(), candidate.requiresCharging(),
                candidate.requiresBatteryNotLow(), candidate.requiresStorageNotLow(), candidate.requiresDeviceIdle(),
                candidate.periodic(), candidate.intervalMs(), candidate.flexMs(), candidate.minimumLatencyMs(),
                candidate.overrideDeadlineMs(), candidate.expedited(), candidate.persisted(),
                candidate.backoffPolicy(), candidate.initialBackoffMs(), candidate.failureCount(),
                candidate.nextRunAtMs(), candidate.lastFailureAtMs(), marshal(candidate.payload()),
                candidate.updatedAtMs());
        return job(call(() -> session.reserveJob(snapshot)));
    }
    @Override public void commitJob(int guestId) { call(() -> { session.commitJob(guestId); return null; }); }
    @Override public boolean removeJob(int guestId) { return call(() -> session.removeJob(guestId)); }
    @Override public List<JobRecord> jobs() {
        List<VirtualJobSnapshot> values = collectPages(session::listJobsPage,
                page -> RemoteVirtualPageHydrator.jobs(session, page));
        List<JobRecord> out = new ArrayList<>(values.size());
        for (VirtualJobSnapshot value : values) out.add(job(value));
        return Collections.unmodifiableList(out);
    }
    @Override public void setJobExecutionListener(JobExecutionListener listener) {
        jobExecutionListener = listener == null ? new JobExecutionListener() {
            @Override public boolean onStart(int guestJobId, Object payload,
                    JobParametersRecord parameters, JobExecution execution) { return false; }
            @Override public boolean onStop(int guestJobId, JobParametersRecord parameters) { return true; }
        } : listener;
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
        recoveredAlarmDelivery = value -> false;
        jobExecutionListener = new JobExecutionListener() {
            @Override public boolean onStart(int guestJobId, Object payload,
                    JobParametersRecord parameters, JobExecution execution) { return false; }
            @Override public boolean onStop(int guestJobId, JobParametersRecord parameters) { return true; }
        };
        try { session.close(); } catch (Exception ignored) { }
    }


    private JobParametersRecord parameters(VirtualJobParametersSnapshot value) {
        return new JobParametersRecord(value.hostJobId(), value.guestJobId(), value.namespace(),
                unmarshal(value.extras()), unmarshal(value.transientExtras()), unmarshal(value.clipData()),
                value.clipGrantFlags(), value.overrideDeadlineExpired(), value.expedited(),
                value.userInitiated(), value.triggeredUris(), value.triggeredAuthorities(),
                unmarshal(value.network()), value.stopReason(), value.internalStopReason(),
                value.debugStopReason(), value.dispatchToken());
    }
    private static JobExecution execution(IVirtualJobExecution remote) {
        return new JobExecution() {
            @Override public int guestJobId() { return callRemote(remote::guestJobId); }
            @Override public long generation() { return callRemote(remote::generation); }
            @Override public long dispatchToken() { return callRemote(remote::dispatchToken); }
            @Override public boolean active() { return callRemote(remote::isActive); }
            @Override public void finish(boolean needsReschedule) {
                callRemote(() -> { remote.finish(needsReschedule); return null; });
            }
            @Override public VirtualJobWorkItemSnapshot dequeueWork() {
                return callRemote(remote::dequeueWork);
            }
            @Override public boolean completeWork(int workId) {
                return callRemote(() -> remote.completeWork(workId));
            }
        };
    }
    private static <T> T callRemote(RemoteCall<T> operation) {
        try { return operation.run(); }
        catch (RuntimeException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException("VIRTUAL_JOB_EXECUTION_REMOTE_FAILURE", error); }
    }

    private AlarmRecord alarm(VirtualAlarmSnapshot snapshot) {
        return new AlarmRecord(snapshot.alarmId(), snapshot.triggerAtMs(), snapshot.intervalMs(),
                snapshot.exact(), snapshot.allowWhileIdle(), snapshot.deliveryPath(),
                snapshot.pendingIntentTokenId(), snapshot.ownerProcessName(), snapshot.ownerGeneration(),
                snapshot.packageRevision(), unmarshal(snapshot.tokenPayload()), snapshot.deliveryCount(),
                snapshot.updatedAtMs(), snapshot.alarmClock(), unmarshal(snapshot.alarmClockPayload()));
    }
    private PendingIntentRecord pendingIntent(VirtualPendingIntentSnapshot value) {
        return new PendingIntentRecord(value.tokenId(), value.kind(), value.requestCode(),
                value.action(), value.component(), value.data(), value.filterIdentity(), value.flags(), value.creatorPackage(),
                value.creatorUid(), value.requiredPermission(), value.ownerProcessName(),
                value.ownerGeneration(), value.packageRevision(), unmarshal(value.payload()),
                value.sends(), value.cancelled(), value.updatedAtMs());
    }
    private NotificationRecord notification(VirtualNotificationSnapshot value) {
        return new NotificationRecord(value.guestId(), value.hostId(), value.guestTag(), value.hostTag(),
                value.channelId(), value.state(), value.packageRevision(), value.contentIntentTokenId(),
                value.deleteIntentTokenId(), value.actionIntentTokenIds(), value.foregroundService(),
                value.foregroundServiceKey(), unmarshal(value.payload()), value.updatedAtMs());
    }
    private VirtualNotificationSnapshot notification(NotificationRecord value, boolean includePayload) {
        return new VirtualNotificationSnapshot(value.guestId(), Math.max(0, value.hostId()), value.guestTag(),
                value.hostTag(), value.channelId(), value.state(), value.packageRevision(),
                value.contentIntentTokenId(), value.deleteIntentTokenId(), value.actionIntentTokenIds(),
                value.foregroundService(), value.foregroundServiceKey(),
                includePayload ? marshal(value.payload()) : new byte[0], value.updatedAtMs());
    }
    private JobRecord job(VirtualJobSnapshot value) {
        return new JobRecord(value.guestId(), value.hostId(), value.state(), value.ownerProcessName(),
                value.ownerGeneration(), value.packageRevision(), value.requiredNetworkType(),
                value.requiresCharging(), value.requiresBatteryNotLow(), value.requiresStorageNotLow(),
                value.requiresDeviceIdle(), value.periodic(), value.intervalMs(), value.flexMs(),
                value.minimumLatencyMs(), value.overrideDeadlineMs(), value.expedited(), value.persisted(),
                value.backoffPolicy(), value.initialBackoffMs(), value.failureCount(), value.nextRunAtMs(),
                value.lastFailureAtMs(), unmarshal(value.payload()), value.updatedAtMs());
    }
    private byte[] marshal(Object value) {
        if (value == null) return new byte[0];
        boolean array = value instanceof Parcelable[];
        if (!(value instanceof Parcelable) && !array) {
            throw new SecurityException("VIRTUAL_SYSTEM_SERVICE_VALUE_NOT_PARCELABLE:" + value.getClass().getName());
        }
        Parcel parcel = Parcel.obtain();
        try {
            if (array) {
                parcel.writeInt(PARCEL_PAYLOAD_PARCELABLE_ARRAY);
                parcel.writeParcelableArray((Parcelable[]) value, 0);
            } else {
                parcel.writeInt(PARCEL_PAYLOAD_PARCELABLE);
                parcel.writeParcelable((Parcelable) value, 0);
            }
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
            int marker = parcel.readInt();
            if (marker == PARCEL_PAYLOAD_PARCELABLE_ARRAY) {
                return parcel.readParcelableArray(classLoader);
            }
            if (marker == PARCEL_PAYLOAD_PARCELABLE) {
                return parcel.readParcelable(classLoader);
            }
            // Backward compatibility for payloads written before the explicit envelope.
            parcel.setDataPosition(0);
            return parcel.readParcelable(classLoader);
        } finally { parcel.recycle(); }
    }
    private <T extends Parcelable, P extends VirtualPageView<T>> List<T> collectPages(
            PageFetcher<P> fetcher, PageHydrator<T, P> hydrator) {
        ArrayList<T> out = new ArrayList<>();
        String token = "";
        long revision = -1L;
        for (int pageCount = 0; pageCount < MAX_PAGE_COUNT; pageCount++) {
            String currentToken = token;
            P page = call(() -> fetcher.fetch(
                    new VirtualPageRequest(PAGE_MAX_ITEMS, PAGE_MAX_BYTES, currentToken)));
            if (page == null) throw new IllegalStateException("VIRTUAL_PAGE_MISSING");
            if (revision < 0L) revision = page.snapshotRevision();
            else if (revision != page.snapshotRevision()) throw new IllegalStateException("VIRTUAL_PAGE_REVISION_CHANGED");
            List<T> hydrated = call(() -> hydrator.hydrate(page));
            if (hydrated == null || hydrated.size() != page.items().size()) {
                throw new IllegalStateException("VIRTUAL_PAGE_HYDRATION_INVALID");
            }
            out.addAll(hydrated);
            String next = page.nextPageToken();
            if (next == null || next.isEmpty()) return Collections.unmodifiableList(out);
            if (next.equals(token)) throw new IllegalStateException("VIRTUAL_PAGE_TOKEN_DID_NOT_ADVANCE");
            token = next;
        }
        throw new IllegalStateException("VIRTUAL_PAGE_COUNT_EXCEEDED");
    }

    private <T> T call(RemoteCall<T> operation) {
        if (closed) throw new IllegalStateException("VIRTUAL_SYSTEM_SERVICE_SESSION_CLOSED");
        try { return operation.run(); }
        catch (RuntimeException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException("VIRTUAL_SYSTEM_SERVICE_REMOTE_FAILURE", error); }
    }
    private static String safe(String value) { return value == null ? "" : value.trim(); }
    @FunctionalInterface private interface PageFetcher<P> { P fetch(VirtualPageRequest request) throws Exception; }
    @FunctionalInterface private interface PageHydrator<T, P> { List<T> hydrate(P page) throws Exception; }
    @FunctionalInterface private interface RemoteCall<T> { T run() throws Exception; }
}
