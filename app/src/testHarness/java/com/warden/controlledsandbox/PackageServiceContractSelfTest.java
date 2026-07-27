package com.warden.controlledsandbox;

import android.os.Parcel;
import com.warden.controlledsandbox.contract.PackageCatalogSnapshot;
import com.warden.controlledsandbox.contract.PackageInstanceSnapshot;
import com.warden.controlledsandbox.contract.PackageRecordSnapshot;
import com.warden.controlledsandbox.contract.PackageServiceResult;
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
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
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
        System.out.println("PASS package service typed contract self-test");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
