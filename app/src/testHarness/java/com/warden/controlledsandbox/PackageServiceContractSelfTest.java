package com.warden.controlledsandbox;

import android.os.Parcel;
import com.warden.controlledsandbox.contract.PackageArtifactSnapshot;
import com.warden.controlledsandbox.contract.PackageCatalogSnapshot;
import com.warden.controlledsandbox.contract.PackageInstanceSnapshot;
import com.warden.controlledsandbox.contract.PackageRecordSnapshot;
import com.warden.controlledsandbox.contract.PackageServiceResult;
import com.warden.controlledsandbox.contract.PackageAppOpSnapshot;
import com.warden.controlledsandbox.contract.VirtualComponentSnapshot;
import com.warden.controlledsandbox.contract.VirtualIntentDataSnapshot;
import com.warden.controlledsandbox.contract.VirtualIntentFilterSnapshot;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
import com.warden.controlledsandbox.contract.VirtualPermissionSnapshot;
import com.warden.controlledsandbox.contract.VirtualAccountSnapshot;
import com.warden.controlledsandbox.contract.VirtualAlarmSnapshot;
import com.warden.controlledsandbox.contract.VirtualJobSnapshot;
import com.warden.controlledsandbox.contract.VirtualJobParametersSnapshot;
import com.warden.controlledsandbox.contract.VirtualNotificationChannelSnapshot;
import com.warden.controlledsandbox.contract.VirtualNotificationSnapshot;
import com.warden.controlledsandbox.contract.VirtualPendingIntentSnapshot;
import com.warden.controlledsandbox.contract.RuntimePermissionRequestSnapshot;
import com.warden.controlledsandbox.contract.PermissionAuditSnapshot;
import java.util.List;

public final class PackageServiceContractSelfTest {
    private PackageServiceContractSelfTest() { }

    public static void main(String[] args) {
        PackageRecordSnapshot record = new PackageRecordSnapshot(
                "com.example.fixture", "Fixture", "1.0", 1,
                "signer", "/files/packages/com.example.fixture/revisions/abc/base.apk",
                "/files/packages/com.example.fixture/revisions/abc/lib", "arm64-v8a", ".MainActivity",
                "com.example.fixture", ".FixtureApplication", ".FixtureService",
                "com.example.fixture", ".FixtureReceiver", "com.example.fixture",
                "com.example.ACTION", ".FixtureProvider", "com.example.fixture",
                "com.example.fixture.provider", "android.permission.INTERNET",
                "org.apache.http.legacy",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                List.of(new PackageArtifactSnapshot("", "BASE", "", "",
                                "/files/packages/com.example.fixture/revisions/abc/base.apk",
                                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"),
                        new PackageArtifactSnapshot("payments", "FEATURE", "", "",
                                "/files/packages/com.example.fixture/revisions/abc/splits/split_payments.apk",
                                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc")),
                10, "NOT_TESTED", 0);
        PackageInstanceSnapshot instance = new PackageInstanceSnapshot(
                "com.example.fixture", 3, "Clone 3", 11, "READY", 12);
        PackageCatalogSnapshot catalog = new PackageCatalogSnapshot(
                List.of(record), List.of(instance), "cleanup pending");
        PackageServiceResult result = PackageServiceResult.successCatalog("loadCatalog", catalog);

        Parcel parcel = Parcel.obtain();
        result.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        PackageServiceResult restored = PackageServiceResult.CREATOR.createFromParcel(parcel);
        parcel.recycle();

        require(restored.successful(), "result success lost");
        require("loadCatalog".equals(restored.operation()), "operation lost");
        require(restored.catalog() != null, "catalog lost");
        require(restored.catalog().packages().size() == 1, "package list lost");
        require(restored.catalog().instances().size() == 1, "instance list lost");
        require("com.example.fixture".equals(restored.catalog().packages().get(0).packageName()),
                "package identity lost");
        require(restored.catalog().instances().get(0).virtualUserId() == 3,
                "virtual user identity lost");
        require("cleanup pending".equals(restored.catalog().maintenanceWarning()),
                "maintenance warning lost");
        require(restored.catalog().packages().get(0).artifacts().size() == 2,
                "artifact list lost");
        require("payments".equals(restored.catalog().packages().get(0).artifacts().get(1).splitName()),
                "split identity lost");
        require("org.apache.http.legacy".equals(restored.catalog().packages().get(0).sharedLibraries()),
                "shared library metadata lost");
        require("arm64-v8a".equals(restored.catalog().packages().get(0).nativeAbi()),
                "native ABI metadata lost");

        VirtualPackageStateSnapshot packageState = new VirtualPackageStateSnapshot(
                "com.example.fixture", 3, "Fixture", "1.0", 1L, "signer",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "com.example.fixture.MainActivity", "com.example.fixture.FixtureApplication", true,
                100L, 200L, "com.warden.virtualinstaller",
                List.of("payments"), List.of("org.apache.http.legacy"),
                List.of(new VirtualComponentSnapshot("ACTIVITY",
                        "com.example.fixture.MainActivity", "com.example.fixture", true, true,
                        false, "", "", "ENABLED", List.of("android.intent.action.MAIN"),
                        List.of(new VirtualIntentFilterSnapshot(10,
                                List.of("android.intent.action.MAIN"),
                                List.of("android.intent.category.DEFAULT"),
                                List.of(new VirtualIntentDataSnapshot("https", "example.com",
                                        "", "/fixture", "", "text/*")))))),
                List.of(new VirtualPermissionSnapshot("android.permission.CAMERA", "DENIED", false)),
                List.of(new PackageAppOpSnapshot("android:camera", "IGNORED")));
        Parcel stateParcel = Parcel.obtain();
        PackageServiceResult.successPackageState("getVirtualPackageState", packageState)
                .writeToParcel(stateParcel, 0);
        stateParcel.setDataPosition(0);
        PackageServiceResult restoredState = PackageServiceResult.CREATOR.createFromParcel(stateParcel);
        stateParcel.recycle();
        require(restoredState.packageState() != null, "virtual package state lost");
        require(restoredState.packageState().components().size() == 1, "component state lost");
        require(!restoredState.packageState().permissions().get(0).effectiveGranted(),
                "permission decision lost");
        require("IGNORED".equals(restoredState.packageState().appOps().get(0).mode()),
                "AppOps mode lost");
        require(restoredState.packageState().splitNames().equals(List.of("payments")),
                "virtual split names lost");
        require(restoredState.packageState().sharedLibraries().equals(List.of("org.apache.http.legacy")),
                "virtual shared libraries lost");
        require(restoredState.packageState().firstInstallTime() == 100L
                        && restoredState.packageState().lastUpdateTime() == 200L,
                "install timestamps lost");
        require("com.warden.virtualinstaller".equals(
                restoredState.packageState().installerPackageName()), "install source lost");
        require("ENABLED".equals(restoredState.packageState().components().get(0).enabledSetting())
                        && restoredState.packageState().components().get(0).intentFilters().size() == 1,
                "component override or intent filter lost");
        require("/fixture".equals(restoredState.packageState().components().get(0)
                        .intentFilters().get(0).data().get(0).pathPrefix()),
                "intent data rule lost");
        boolean contradictionRejected = false;
        try { new VirtualPermissionSnapshot("android.permission.CAMERA", "DENIED", true); }
        catch (IllegalArgumentException expected) { contradictionRejected = true; }
        require(contradictionRejected, "permission decision contradiction rejected");
        boolean invalidAppOpRejected = false;
        try { new PackageAppOpSnapshot("android:camera", "UNSUPPORTED"); }
        catch (IllegalArgumentException expected) { invalidAppOpRejected = true; }
        require(invalidAppOpRejected, "invalid AppOps mode rejected");

        RuntimePermissionRequestSnapshot permissionRequest = new RuntimePermissionRequestSnapshot(
                7L, "com.example.fixture", 3, "android.permission.CAMERA", "android:camera",
                "GRANTED", true, 19, "session-7", 4L, 100L, 120L, "user granted");
        PermissionAuditSnapshot audit = new PermissionAuditSnapshot(
                9L, 120L, "com.example.fixture", 3, "android.permission.CAMERA",
                "RESOLVE", "GRANTED", "ANDROID_PERMISSION_RESULT", "user granted", 7L);
        Parcel permissionParcel = Parcel.obtain();
        PackageServiceResult.successPermissionRequest("resolveRuntimePermission",
                permissionRequest, packageState).writeToParcel(permissionParcel, 0);
        permissionParcel.setDataPosition(0);
        PackageServiceResult restoredPermission = PackageServiceResult.CREATOR
                .createFromParcel(permissionParcel);
        permissionParcel.recycle();
        require(restoredPermission.permissionRequest() != null
                        && restoredPermission.permissionRequest().requestId() == 7L,
                "runtime permission request lost");
        Parcel auditParcel = Parcel.obtain();
        PackageServiceResult.successPermissionAudit("listPermissionAudit", List.of(audit))
                .writeToParcel(auditParcel, 0);
        auditParcel.setDataPosition(0);
        PackageServiceResult restoredAudit = PackageServiceResult.CREATOR.createFromParcel(auditParcel);
        auditParcel.recycle();
        require(restoredAudit.permissionAudit().size() == 1
                        && "RESOLVE".equals(restoredAudit.permissionAudit().get(0).action()),
                "permission audit lost");
        boolean hostlessGrantRejected = false;
        try {
            new RuntimePermissionRequestSnapshot(8L, "com.example.fixture", 3,
                    "android.permission.CAMERA", "android:camera", "GRANTED", false,
                    20, "session-8", 5L, 200L, 220L, "invalid");
        } catch (IllegalArgumentException expected) {
            hostlessGrantRejected = true;
        }
        require(hostlessGrantRejected, "hostless runtime grant rejected");

        VirtualAccountSnapshot account = new VirtualAccountSnapshot(
                "alice", "mail", "secret", List.of("access"), List.of("token"));
        Parcel accountParcel = Parcel.obtain();
        account.writeToParcel(accountParcel, 0); accountParcel.setDataPosition(0);
        VirtualAccountSnapshot restoredAccount = VirtualAccountSnapshot.CREATOR.createFromParcel(accountParcel);
        accountParcel.recycle();
        require("alice".equals(restoredAccount.name())
                        && restoredAccount.tokens().equals(List.of("token")),
                "virtual account snapshot lost");

        VirtualPendingIntentSnapshot pendingIntent = new VirtualPendingIntentSnapshot(
                "pi-7", VirtualPendingIntentSnapshot.ACTIVITY_RESULT, 17, "guest.RESULT",
                "activity-token", "content://guest/result", "a=guest.RESULT|c=activity-token|d=content://guest/result", 0x02000000, "com.example.fixture",
                12003, "guest.permission.RESULT", "com.example.fixture", 9L,
                "revision-7", new byte[]{7, 1}, 2, false, 111L);
        Parcel pendingIntentParcel = Parcel.obtain();
        pendingIntent.writeToParcel(pendingIntentParcel, 0); pendingIntentParcel.setDataPosition(0);
        VirtualPendingIntentSnapshot restoredPendingIntent = VirtualPendingIntentSnapshot.CREATOR
                .createFromParcel(pendingIntentParcel);
        pendingIntentParcel.recycle();
        require("pi-7".equals(restoredPendingIntent.tokenId())
                        && restoredPendingIntent.creatorUid() == 12003
                        && restoredPendingIntent.ownerGeneration() == 9L
                        && java.util.Arrays.equals(new byte[]{7, 1}, restoredPendingIntent.payload()),
                "virtual PendingIntent snapshot lost");

        VirtualAlarmSnapshot alarm = new VirtualAlarmSnapshot("a7", 123L, 456L, new byte[]{1, 2, 3});
        Parcel alarmParcel = Parcel.obtain();
        alarm.writeToParcel(alarmParcel, 0); alarmParcel.setDataPosition(0);
        VirtualAlarmSnapshot restoredAlarm = VirtualAlarmSnapshot.CREATOR.createFromParcel(alarmParcel);
        alarmParcel.recycle();
        require("a7".equals(restoredAlarm.alarmId()) && restoredAlarm.intervalMs() == 456L
                        && java.util.Arrays.equals(new byte[]{1, 2, 3}, restoredAlarm.tokenPayload()),
                "virtual alarm snapshot lost");

        VirtualNotificationSnapshot notification = new VirtualNotificationSnapshot(
                7, 0x51000007, "updates", "cs:u3:g9:updates", "general",
                VirtualNotificationSnapshot.ACTIVE, new byte[]{4, 2}, 77L);
        Parcel notificationParcel = Parcel.obtain();
        notification.writeToParcel(notificationParcel, 0); notificationParcel.setDataPosition(0);
        VirtualNotificationSnapshot restoredNotification = VirtualNotificationSnapshot.CREATOR
                .createFromParcel(notificationParcel);
        notificationParcel.recycle();
        require(restoredNotification.hostId() == 0x51000007
                        && VirtualNotificationSnapshot.ACTIVE.equals(restoredNotification.state())
                        && java.util.Arrays.equals(new byte[]{4, 2}, restoredNotification.payload()),
                "virtual notification snapshot lost");

        VirtualNotificationChannelSnapshot channel = new VirtualNotificationChannelSnapshot(
                VirtualNotificationChannelSnapshot.CHANNEL, "general", "group", new byte[]{8}, 88L);
        Parcel channelParcel = Parcel.obtain();
        channel.writeToParcel(channelParcel, 0); channelParcel.setDataPosition(0);
        VirtualNotificationChannelSnapshot restoredChannel = VirtualNotificationChannelSnapshot.CREATOR
                .createFromParcel(channelParcel);
        channelParcel.recycle();
        require("general".equals(restoredChannel.id()) && "group".equals(restoredChannel.groupId()),
                "virtual notification channel snapshot lost");

        VirtualJobSnapshot job = new VirtualJobSnapshot(17, 0x52000011,
                VirtualJobSnapshot.SCHEDULED, "com.example.fixture:worker", 6L,
                "job-revision-a", VirtualJobSnapshot.NETWORK_UNMETERED,
                true, true, true, true, true, 900_000L, 300_000L,
                12_000L, 60_000L, true, true, VirtualJobSnapshot.BACKOFF_LINEAR,
                45_000L, 3, 120_000L, 90_000L, new byte[]{1, 7}, 99L);
        Parcel jobParcel = Parcel.obtain();
        job.writeToParcel(jobParcel, 0); jobParcel.setDataPosition(0);
        VirtualJobSnapshot restoredJob = VirtualJobSnapshot.CREATOR.createFromParcel(jobParcel);
        jobParcel.recycle();
        require(restoredJob.guestId() == 17 && restoredJob.hostId() == 0x52000011
                        && VirtualJobSnapshot.SCHEDULED.equals(restoredJob.state())
                        && "job-revision-a".equals(restoredJob.packageRevision())
                        && restoredJob.requiredNetworkType() == VirtualJobSnapshot.NETWORK_UNMETERED
                        && restoredJob.requiresCharging() && restoredJob.requiresBatteryNotLow()
                        && restoredJob.requiresStorageNotLow() && restoredJob.requiresDeviceIdle()
                        && restoredJob.periodic() && restoredJob.intervalMs() == 900_000L
                        && restoredJob.flexMs() == 300_000L && restoredJob.minimumLatencyMs() == 12_000L
                        && restoredJob.overrideDeadlineMs() == 60_000L && restoredJob.expedited()
                        && restoredJob.persisted() && restoredJob.backoffPolicy() == VirtualJobSnapshot.BACKOFF_LINEAR
                        && restoredJob.initialBackoffMs() == 45_000L && restoredJob.failureCount() == 3
                        && restoredJob.nextRunAtMs() == 120_000L && restoredJob.lastFailureAtMs() == 90_000L
                        && java.util.Arrays.equals(new byte[]{1, 7}, restoredJob.payload()),
                "virtual job policy snapshot lost");
        VirtualJobParametersSnapshot jobParameters = new VirtualJobParametersSnapshot(
                0x52000011, 17, "guest", new byte[]{1}, new byte[]{2}, new byte[]{3}, 4,
                true, true, false, List.of("content://guest/jobs/17"), List.of("guest.jobs"),
                new byte[]{5}, 6, 7, "constraint", 8L);
        Parcel jobParametersParcel = Parcel.obtain();
        jobParameters.writeToParcel(jobParametersParcel, 0); jobParametersParcel.setDataPosition(0);
        VirtualJobParametersSnapshot restoredJobParameters = VirtualJobParametersSnapshot.CREATOR
                .createFromParcel(jobParametersParcel);
        jobParametersParcel.recycle();
        require(restoredJobParameters.hostJobId() == 0x52000011
                        && restoredJobParameters.guestJobId() == 17
                        && restoredJobParameters.dispatchToken() == 8L
                        && restoredJobParameters.triggeredUris().size() == 1,
                "virtual JobParameters snapshot lost");
        System.out.println("PASS package service typed contract self-test");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
