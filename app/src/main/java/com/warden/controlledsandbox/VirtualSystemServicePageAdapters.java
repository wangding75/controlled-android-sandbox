package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.VirtualAlarmSnapshot;
import com.warden.controlledsandbox.contract.VirtualJobSnapshot;
import com.warden.controlledsandbox.contract.VirtualNotificationChannelSnapshot;
import com.warden.controlledsandbox.contract.VirtualNotificationSnapshot;
import com.warden.controlledsandbox.contract.VirtualPendingIntentSnapshot;
import com.warden.controlledsandbox.contract.VirtualWidgetSnapshot;

/** Binary-field adapters used by Binder paging and handle-based payload transfer. */
final class VirtualSystemServicePageAdapters {
    static final VirtualSystemServicePager.BinaryAdapter<VirtualPendingIntentSnapshot> PENDING_INTENT =
            new VirtualSystemServicePager.BinaryAdapter<>() {
                @Override public String fieldName() { return "payload"; }
                @Override public byte[] payload(VirtualPendingIntentSnapshot value) { return value.payload(); }
                @Override public VirtualPendingIntentSnapshot withoutPayload(VirtualPendingIntentSnapshot value) {
                    return new VirtualPendingIntentSnapshot(value.tokenId(), value.kind(), value.requestCode(),
                            value.action(), value.component(), value.data(), value.filterIdentity(), value.flags(),
                            value.creatorPackage(), value.creatorUid(), value.requiredPermission(),
                            value.ownerProcessName(), value.ownerGeneration(), value.packageRevision(), new byte[0],
                            value.sends(), value.cancelled(), value.updatedAtMs());
                }
            };

    static final VirtualSystemServicePager.BinaryAdapter<VirtualAlarmSnapshot> ALARM =
            new VirtualSystemServicePager.BinaryAdapter<>() {
                @Override public String fieldName() { return "tokenPayload"; }
                @Override public byte[] payload(VirtualAlarmSnapshot value) { return value.tokenPayload(); }
                @Override public VirtualAlarmSnapshot withoutPayload(VirtualAlarmSnapshot value) {
                    return new VirtualAlarmSnapshot(value.alarmId(), value.triggerAtMs(), value.intervalMs(),
                            value.exact(), value.allowWhileIdle(), value.deliveryPath(), value.pendingIntentTokenId(),
                            value.ownerProcessName(), value.ownerGeneration(), value.packageRevision(), new byte[0],
                            value.deliveryCount(), value.updatedAtMs(), value.alarmClock(),
                            value.alarmClockPayload());
                }
            };

    static final VirtualSystemServicePager.BinaryAdapter<VirtualNotificationSnapshot> NOTIFICATION =
            new VirtualSystemServicePager.BinaryAdapter<>() {
                @Override public String fieldName() { return "payload"; }
                @Override public byte[] payload(VirtualNotificationSnapshot value) { return value.payload(); }
                @Override public VirtualNotificationSnapshot withoutPayload(VirtualNotificationSnapshot value) {
                    return new VirtualNotificationSnapshot(value.guestId(), value.hostId(), value.guestTag(),
                            value.hostTag(), value.channelId(), value.state(), value.packageRevision(),
                            value.contentIntentTokenId(), value.deleteIntentTokenId(), value.actionIntentTokenIds(),
                            value.foregroundService(), value.foregroundServiceKey(), new byte[0], value.updatedAtMs());
                }
            };

    static final VirtualSystemServicePager.BinaryAdapter<VirtualNotificationChannelSnapshot> CHANNEL =
            new VirtualSystemServicePager.BinaryAdapter<>() {
                @Override public String fieldName() { return "payload"; }
                @Override public byte[] payload(VirtualNotificationChannelSnapshot value) { return value.payload(); }
                @Override public VirtualNotificationChannelSnapshot withoutPayload(VirtualNotificationChannelSnapshot value) {
                    return new VirtualNotificationChannelSnapshot(value.kind(), value.id(), value.groupId(),
                            value.packageRevision(), new byte[0], value.updatedAtMs());
                }
            };

    static final VirtualSystemServicePager.BinaryAdapter<VirtualJobSnapshot> JOB =
            new VirtualSystemServicePager.BinaryAdapter<>() {
                @Override public String fieldName() { return "payload"; }
                @Override public byte[] payload(VirtualJobSnapshot value) { return value.payload(); }
                @Override public VirtualJobSnapshot withoutPayload(VirtualJobSnapshot value) {
                    return new VirtualJobSnapshot(value.guestId(), value.hostId(), value.state(),
                            value.ownerProcessName(), value.ownerGeneration(), value.packageRevision(),
                            value.requiredNetworkType(), value.requiresCharging(), value.requiresBatteryNotLow(),
                            value.requiresStorageNotLow(), value.requiresDeviceIdle(), value.periodic(),
                            value.intervalMs(), value.flexMs(), value.minimumLatencyMs(), value.overrideDeadlineMs(),
                            value.expedited(), value.persisted(), value.backoffPolicy(), value.initialBackoffMs(),
                            value.failureCount(), value.nextRunAtMs(), value.lastFailureAtMs(), new byte[0],
                            value.updatedAtMs());
                }
            };

    static final VirtualSystemServicePager.BinaryAdapter<VirtualWidgetSnapshot> WIDGET =
            new VirtualSystemServicePager.BinaryAdapter<>() {
                @Override public String fieldName() { return "remoteViewsPayload"; }
                @Override public byte[] payload(VirtualWidgetSnapshot value) { return value.remoteViewsPayload(); }
                @Override public VirtualWidgetSnapshot withoutPayload(VirtualWidgetSnapshot value) {
                    return new VirtualWidgetSnapshot(value.appWidgetId(), value.hostId(), value.providerPackage(),
                            value.providerClass(), value.bound(), value.optionKeys(), value.optionValues(),
                            new byte[0], value.updatedAtMs());
                }
            };

    private VirtualSystemServicePageAdapters() { }
}
