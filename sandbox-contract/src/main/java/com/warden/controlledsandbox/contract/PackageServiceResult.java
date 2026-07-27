package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** One typed result envelope for package-management Binder operations. */
public final class PackageServiceResult implements Parcelable {
    private final boolean successful;
    private final String operation;
    private final String errorCode;
    private final String errorMessage;
    private final PackageCatalogSnapshot catalog;
    private final PackageRecordSnapshot record;
    private final int intValue;
    private final String textValue;

    private PackageServiceResult(boolean successful, String operation, String errorCode,
                                 String errorMessage, PackageCatalogSnapshot catalog,
                                 PackageRecordSnapshot record, int intValue, String textValue) {
        this.successful = successful;
        this.operation = value(operation);
        this.errorCode = value(errorCode);
        this.errorMessage = value(errorMessage);
        this.catalog = catalog;
        this.record = record;
        this.intValue = intValue;
        this.textValue = value(textValue);
    }

    private PackageServiceResult(Parcel in) {
        this(in.readInt() != 0, in.readString(), in.readString(), in.readString(),
                in.readParcelable(PackageCatalogSnapshot.class.getClassLoader()),
                in.readParcelable(PackageRecordSnapshot.class.getClassLoader()),
                in.readInt(), in.readString());
    }

    public static PackageServiceResult successCatalog(String operation, PackageCatalogSnapshot catalog) {
        return new PackageServiceResult(true, operation, "", "", catalog, null, 0, "");
    }
    public static PackageServiceResult successRecord(String operation, PackageRecordSnapshot record) {
        return new PackageServiceResult(true, operation, "", "", null, record, 0, "");
    }
    public static PackageServiceResult successInt(String operation, int value) {
        return new PackageServiceResult(true, operation, "", "", null, null, value, "");
    }
    public static PackageServiceResult successText(String operation, String value) {
        return new PackageServiceResult(true, operation, "", "", null, null, 0, value);
    }
    public static PackageServiceResult success(String operation) {
        return new PackageServiceResult(true, operation, "", "", null, null, 0, "");
    }
    public static PackageServiceResult failure(String operation, String errorCode, String errorMessage) {
        return new PackageServiceResult(false, operation, errorCode, errorMessage, null, null, 0, "");
    }

    public boolean successful() { return successful; }
    public String operation() { return operation; }
    public String errorCode() { return errorCode; }
    public String errorMessage() { return errorMessage; }
    public PackageCatalogSnapshot catalog() { return catalog; }
    public PackageRecordSnapshot record() { return record; }
    public int intValue() { return intValue; }
    public String textValue() { return textValue; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeInt(successful ? 1 : 0); out.writeString(operation); out.writeString(errorCode);
        out.writeString(errorMessage); out.writeParcelable(catalog, flags);
        out.writeParcelable(record, flags); out.writeInt(intValue); out.writeString(textValue);
    }
    @Override public int describeContents() { return 0; }

    public static final Creator<PackageServiceResult> CREATOR = new Creator<>() {
        @Override public PackageServiceResult createFromParcel(Parcel in) {
            return new PackageServiceResult(in);
        }
        @Override public PackageServiceResult[] newArray(int size) {
            return new PackageServiceResult[size];
        }
    };

    private static String value(String value) { return value == null ? "" : value; }
}
