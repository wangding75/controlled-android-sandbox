package com.warden.controlledsandbox.runtime.systemservice;

import android.os.ParcelFileDescriptor;
import com.warden.controlledsandbox.contract.IVirtualSystemServiceSession;
import com.warden.controlledsandbox.contract.VirtualAlarmPage;
import com.warden.controlledsandbox.contract.VirtualAlarmSnapshot;
import com.warden.controlledsandbox.contract.VirtualJobPage;
import com.warden.controlledsandbox.contract.VirtualJobSnapshot;
import com.warden.controlledsandbox.contract.VirtualNotificationChannelPage;
import com.warden.controlledsandbox.contract.VirtualNotificationChannelSnapshot;
import com.warden.controlledsandbox.contract.VirtualNotificationPage;
import com.warden.controlledsandbox.contract.VirtualNotificationSnapshot;
import com.warden.controlledsandbox.contract.VirtualPageBlob;
import com.warden.controlledsandbox.contract.VirtualPendingIntentPage;
import com.warden.controlledsandbox.contract.VirtualPendingIntentSnapshot;
import com.warden.controlledsandbox.contract.VirtualWidgetPage;
import com.warden.controlledsandbox.contract.VirtualWidgetSnapshot;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Rehydrates binary fields transferred through scoped ParcelFileDescriptor grants. */
final class RemoteVirtualPageHydrator {
    private static final int MAX_BLOB_BYTES = 512 * 1024;

    static List<VirtualPendingIntentSnapshot> pendingIntents(
            IVirtualSystemServiceSession session, VirtualPendingIntentPage page) throws Exception {
        Map<Integer, byte[]> payloads = payloads(session, page.blobs(), "payload", page.items().size());
        ArrayList<VirtualPendingIntentSnapshot> out = new ArrayList<>(page.items().size());
        for (int index = 0; index < page.items().size(); index++) {
            VirtualPendingIntentSnapshot value = page.items().get(index);
            byte[] payload = payloads.get(index);
            out.add(payload == null ? value : new VirtualPendingIntentSnapshot(value.tokenId(), value.kind(),
                    value.requestCode(), value.action(), value.component(), value.data(), value.filterIdentity(),
                    value.flags(), value.creatorPackage(), value.creatorUid(), value.requiredPermission(),
                    value.ownerProcessName(), value.ownerGeneration(), value.packageRevision(), payload,
                    value.sends(), value.cancelled(), value.updatedAtMs()));
        }
        return List.copyOf(out);
    }

    static List<VirtualAlarmSnapshot> alarms(
            IVirtualSystemServiceSession session, VirtualAlarmPage page) throws Exception {
        Map<Integer, byte[]> payloads = payloads(session, page.blobs(), "tokenPayload", page.items().size());
        ArrayList<VirtualAlarmSnapshot> out = new ArrayList<>(page.items().size());
        for (int index = 0; index < page.items().size(); index++) {
            VirtualAlarmSnapshot value = page.items().get(index);
            byte[] payload = payloads.get(index);
            out.add(payload == null ? value : new VirtualAlarmSnapshot(value.alarmId(), value.triggerAtMs(),
                    value.intervalMs(), value.exact(), value.allowWhileIdle(), value.deliveryPath(),
                    value.pendingIntentTokenId(), value.ownerProcessName(), value.ownerGeneration(),
                    value.packageRevision(), payload, value.deliveryCount(), value.updatedAtMs()));
        }
        return List.copyOf(out);
    }

    static List<VirtualNotificationSnapshot> notifications(
            IVirtualSystemServiceSession session, VirtualNotificationPage page) throws Exception {
        Map<Integer, byte[]> payloads = payloads(session, page.blobs(), "payload", page.items().size());
        ArrayList<VirtualNotificationSnapshot> out = new ArrayList<>(page.items().size());
        for (int index = 0; index < page.items().size(); index++) {
            VirtualNotificationSnapshot value = page.items().get(index);
            byte[] payload = payloads.get(index);
            out.add(payload == null ? value : new VirtualNotificationSnapshot(value.guestId(), value.hostId(),
                    value.guestTag(), value.hostTag(), value.channelId(), value.state(), value.packageRevision(),
                    value.contentIntentTokenId(), value.deleteIntentTokenId(), value.actionIntentTokenIds(),
                    value.foregroundService(), value.foregroundServiceKey(), payload, value.updatedAtMs()));
        }
        return List.copyOf(out);
    }

    static List<VirtualNotificationChannelSnapshot> channels(
            IVirtualSystemServiceSession session, VirtualNotificationChannelPage page) throws Exception {
        Map<Integer, byte[]> payloads = payloads(session, page.blobs(), "payload", page.items().size());
        ArrayList<VirtualNotificationChannelSnapshot> out = new ArrayList<>(page.items().size());
        for (int index = 0; index < page.items().size(); index++) {
            VirtualNotificationChannelSnapshot value = page.items().get(index);
            byte[] payload = payloads.get(index);
            out.add(payload == null ? value : new VirtualNotificationChannelSnapshot(value.kind(), value.id(),
                    value.groupId(), value.packageRevision(), payload, value.updatedAtMs()));
        }
        return List.copyOf(out);
    }

    static List<VirtualJobSnapshot> jobs(
            IVirtualSystemServiceSession session, VirtualJobPage page) throws Exception {
        Map<Integer, byte[]> payloads = payloads(session, page.blobs(), "payload", page.items().size());
        ArrayList<VirtualJobSnapshot> out = new ArrayList<>(page.items().size());
        for (int index = 0; index < page.items().size(); index++) {
            VirtualJobSnapshot value = page.items().get(index);
            byte[] payload = payloads.get(index);
            out.add(payload == null ? value : new VirtualJobSnapshot(value.guestId(), value.hostId(), value.state(),
                    value.ownerProcessName(), value.ownerGeneration(), value.packageRevision(),
                    value.requiredNetworkType(), value.requiresCharging(), value.requiresBatteryNotLow(),
                    value.requiresStorageNotLow(), value.requiresDeviceIdle(), value.periodic(), value.intervalMs(),
                    value.flexMs(), value.minimumLatencyMs(), value.overrideDeadlineMs(), value.expedited(),
                    value.persisted(), value.backoffPolicy(), value.initialBackoffMs(), value.failureCount(),
                    value.nextRunAtMs(), value.lastFailureAtMs(), payload, value.updatedAtMs()));
        }
        return List.copyOf(out);
    }

    static List<VirtualWidgetSnapshot> widgets(
            IVirtualSystemServiceSession session, VirtualWidgetPage page) throws Exception {
        Map<Integer, byte[]> payloads = payloads(session, page.blobs(), "remoteViewsPayload", page.items().size());
        ArrayList<VirtualWidgetSnapshot> out = new ArrayList<>(page.items().size());
        for (int index = 0; index < page.items().size(); index++) {
            VirtualWidgetSnapshot value = page.items().get(index);
            byte[] payload = payloads.get(index);
            out.add(payload == null ? value : new VirtualWidgetSnapshot(value.appWidgetId(), value.hostId(),
                    value.providerPackage(), value.providerClass(), value.bound(), value.optionKeys(),
                    value.optionValues(), payload, value.updatedAtMs()));
        }
        return List.copyOf(out);
    }

    private static Map<Integer, byte[]> payloads(IVirtualSystemServiceSession session,
            List<VirtualPageBlob> descriptors, String expectedField, int itemCount) throws Exception {
        HashMap<Integer, byte[]> out = new HashMap<>();
        for (VirtualPageBlob descriptor : descriptors) {
            if (!expectedField.equals(descriptor.fieldName())) {
                throw new SecurityException("PAGE_BLOB_FIELD_MISMATCH");
            }
            if (descriptor.itemIndex() < 0 || descriptor.itemIndex() >= itemCount) {
                throw new SecurityException("PAGE_BLOB_ITEM_INDEX_INVALID");
            }
            if (out.containsKey(descriptor.itemIndex())) {
                throw new SecurityException("PAGE_BLOB_DUPLICATE_ITEM");
            }
            out.put(descriptor.itemIndex(), read(session, descriptor));
        }
        return out;
    }

    private static byte[] read(IVirtualSystemServiceSession session, VirtualPageBlob descriptor)
            throws Exception {
        if (descriptor.byteCount() < 0 || descriptor.byteCount() > MAX_BLOB_BYTES) {
            throw new SecurityException("PAGE_BLOB_SIZE_INVALID");
        }
        byte[] payload;
        try (ParcelFileDescriptor file = session.openPageBlob(descriptor.blobToken());
             FileInputStream input = new FileInputStream(file.getFileDescriptor())) {
            payload = input.readNBytes(descriptor.byteCount() + 1);
        }
        if (payload.length != descriptor.byteCount()) {
            throw new SecurityException("PAGE_BLOB_LENGTH_MISMATCH");
        }
        String digest = sha256(payload);
        if (!MessageDigest.isEqual(digest.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                descriptor.sha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            throw new SecurityException("PAGE_BLOB_DIGEST_MISMATCH");
        }
        return payload;
    }

    private static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder out = new StringBuilder(64);
            for (byte item : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private RemoteVirtualPageHydrator() { }
}
