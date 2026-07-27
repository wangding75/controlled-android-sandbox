package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Typed package-service response containing the authoritative package catalog. */
public final class PackageCatalogSnapshot implements Parcelable {
    private final ArrayList<PackageRecordSnapshot> packages;
    private final ArrayList<PackageInstanceSnapshot> instances;
    private final String maintenanceWarning;

    public PackageCatalogSnapshot(List<PackageRecordSnapshot> packages,
                                  List<PackageInstanceSnapshot> instances,
                                  String maintenanceWarning) {
        this.packages = new ArrayList<>(packages == null ? List.of() : packages);
        this.instances = new ArrayList<>(instances == null ? List.of() : instances);
        this.maintenanceWarning = maintenanceWarning == null ? "" : maintenanceWarning;
    }

    private PackageCatalogSnapshot(Parcel in) {
        ArrayList<PackageRecordSnapshot> packageValues = in.createTypedArrayList(PackageRecordSnapshot.CREATOR);
        ArrayList<PackageInstanceSnapshot> instanceValues = in.createTypedArrayList(PackageInstanceSnapshot.CREATOR);
        packages = packageValues == null ? new ArrayList<>() : packageValues;
        instances = instanceValues == null ? new ArrayList<>() : instanceValues;
        maintenanceWarning = value(in.readString());
    }

    public List<PackageRecordSnapshot> packages() {
        return Collections.unmodifiableList(packages);
    }
    public List<PackageInstanceSnapshot> instances() {
        return Collections.unmodifiableList(instances);
    }
    public String maintenanceWarning() { return maintenanceWarning; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeTypedList(packages);
        out.writeTypedList(instances);
        out.writeString(maintenanceWarning);
    }
    @Override public int describeContents() { return 0; }

    public static final Creator<PackageCatalogSnapshot> CREATOR = new Creator<>() {
        @Override public PackageCatalogSnapshot createFromParcel(Parcel in) {
            return new PackageCatalogSnapshot(in);
        }
        @Override public PackageCatalogSnapshot[] newArray(int size) {
            return new PackageCatalogSnapshot[size];
        }
    };

    private static String value(String value) { return value == null ? "" : value; }
}
