package com.warden.controlledsandbox;

import android.os.Parcel;
import com.warden.controlledsandbox.contract.PackageArtifactSnapshot;
import com.warden.controlledsandbox.contract.PackageCatalogSnapshot;
import com.warden.controlledsandbox.contract.PackageInstanceSnapshot;
import com.warden.controlledsandbox.contract.PackageRecordSnapshot;
import com.warden.controlledsandbox.contract.PackageServiceResult;
import com.warden.controlledsandbox.contract.PackageAppOpSnapshot;
import com.warden.controlledsandbox.contract.VirtualComponentSnapshot;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
import com.warden.controlledsandbox.contract.VirtualPermissionSnapshot;
import com.warden.controlledsandbox.contract.RuntimePermissionRequestSnapshot;
import com.warden.controlledsandbox.contract.PermissionAuditSnapshot;
import java.util.List;

public final class PackageServiceContractSelfTest {
    private PackageServiceContractSelfTest() { }

    public static void main(String[] args) {
        PackageRecordSnapshot record = new PackageRecordSnapshot(
                "com.example.fixture", "Fixture", "1.0", 1,
                "signer", "/files/packages/com.example.fixture/revisions/abc/base.apk",
                "/files/packages/com.example.fixture/revisions/abc/lib", ".MainActivity",
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

        VirtualPackageStateSnapshot packageState = new VirtualPackageStateSnapshot(
                "com.example.fixture", 3, "Fixture", "1.0", 1L, "signer",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "com.example.fixture.MainActivity", "com.example.fixture.FixtureApplication", true,
                List.of("payments"), List.of("org.apache.http.legacy"),
                List.of(new VirtualComponentSnapshot("ACTIVITY",
                        "com.example.fixture.MainActivity", "com.example.fixture", true, true,
                        false, "", "", List.of("android.intent.action.MAIN"))),
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
        System.out.println("PASS package service typed contract self-test");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
