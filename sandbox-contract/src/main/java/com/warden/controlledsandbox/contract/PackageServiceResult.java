package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One typed result envelope for package-management Binder operations. */
public final class PackageServiceResult implements Parcelable {
    private final boolean successful;
    private final String operation;
    private final String errorCode;
    private final String errorMessage;
    private final PackageCatalogSnapshot catalog;
    private final PackageRecordSnapshot record;
    private final VirtualPackageStateSnapshot packageState;
    private final RuntimePermissionRequestSnapshot permissionRequest;
    private final ArrayList<RuntimePermissionRequestSnapshot> permissionRequests;
    private final ArrayList<PermissionAuditSnapshot> permissionAudit;
    private final int intValue;
    private final String textValue;

    private PackageServiceResult(boolean successful, String operation, String errorCode,
                                 String errorMessage, PackageCatalogSnapshot catalog,
                                 PackageRecordSnapshot record, VirtualPackageStateSnapshot packageState,
                                 RuntimePermissionRequestSnapshot permissionRequest,
                                 List<RuntimePermissionRequestSnapshot> permissionRequests,
                                 List<PermissionAuditSnapshot> permissionAudit,
                                 int intValue, String textValue) {
        this.successful = successful;
        this.operation = value(operation);
        this.errorCode = value(errorCode);
        this.errorMessage = value(errorMessage);
        this.catalog = catalog;
        this.record = record;
        this.packageState = packageState;
        this.permissionRequest = permissionRequest;
        this.permissionRequests = new ArrayList<>(permissionRequests == null ? List.of() : permissionRequests);
        this.permissionAudit = new ArrayList<>(permissionAudit == null ? List.of() : permissionAudit);
        this.intValue = intValue;
        this.textValue = value(textValue);
    }

    private PackageServiceResult(Parcel in) {
        this(in.readInt() != 0, in.readString(), in.readString(), in.readString(),
                in.readParcelable(PackageCatalogSnapshot.class.getClassLoader()),
                in.readParcelable(PackageRecordSnapshot.class.getClassLoader()),
                in.readParcelable(VirtualPackageStateSnapshot.class.getClassLoader()),
                in.readParcelable(RuntimePermissionRequestSnapshot.class.getClassLoader()),
                in.createTypedArrayList(RuntimePermissionRequestSnapshot.CREATOR),
                in.createTypedArrayList(PermissionAuditSnapshot.CREATOR),
                in.readInt(), in.readString());
    }

    public static PackageServiceResult successCatalog(String operation, PackageCatalogSnapshot catalog) {
        return value(true, operation, catalog, null, null, null, null, null, 0, "");
    }
    public static PackageServiceResult successRecord(String operation, PackageRecordSnapshot record) {
        return value(true, operation, null, record, null, null, null, null, 0, "");
    }
    public static PackageServiceResult successPackageState(String operation,
                                                           VirtualPackageStateSnapshot packageState) {
        return value(true, operation, null, null, packageState, null, null, null, 0, "");
    }
    public static PackageServiceResult successPermissionRequest(String operation,
                                                                 RuntimePermissionRequestSnapshot request,
                                                                 VirtualPackageStateSnapshot packageState) {
        return value(true, operation, null, null, packageState, request, null, null, 0, "");
    }
    public static PackageServiceResult successPermissionRequests(String operation,
                                                                  List<RuntimePermissionRequestSnapshot> requests) {
        return value(true, operation, null, null, null, null, requests, null, 0, "");
    }
    public static PackageServiceResult successPermissionAudit(String operation,
                                                               List<PermissionAuditSnapshot> audit) {
        return value(true, operation, null, null, null, null, null, audit, 0, "");
    }
    public static PackageServiceResult successInt(String operation, int value) {
        return value(true, operation, null, null, null, null, null, null, value, "");
    }
    public static PackageServiceResult successText(String operation, String text) {
        return value(true, operation, null, null, null, null, null, null, 0, text);
    }
    public static PackageServiceResult success(String operation) {
        return value(true, operation, null, null, null, null, null, null, 0, "");
    }
    public static PackageServiceResult failure(String operation, String errorCode, String errorMessage) {
        return new PackageServiceResult(false, operation, errorCode, errorMessage,
                null, null, null, null, null, null, 0, "");
    }

    private static PackageServiceResult value(boolean successful, String operation,
                                               PackageCatalogSnapshot catalog,
                                               PackageRecordSnapshot record,
                                               VirtualPackageStateSnapshot packageState,
                                               RuntimePermissionRequestSnapshot permissionRequest,
                                               List<RuntimePermissionRequestSnapshot> requests,
                                               List<PermissionAuditSnapshot> audit,
                                               int intValue, String textValue) {
        return new PackageServiceResult(successful, operation, "", "", catalog, record,
                packageState, permissionRequest, requests, audit, intValue, textValue);
    }

    public boolean successful() { return successful; }
    public String operation() { return operation; }
    public String errorCode() { return errorCode; }
    public String errorMessage() { return errorMessage; }
    public PackageCatalogSnapshot catalog() { return catalog; }
    public PackageRecordSnapshot record() { return record; }
    public VirtualPackageStateSnapshot packageState() { return packageState; }
    public RuntimePermissionRequestSnapshot permissionRequest() { return permissionRequest; }
    public List<RuntimePermissionRequestSnapshot> permissionRequests() {
        return Collections.unmodifiableList(permissionRequests);
    }
    public List<PermissionAuditSnapshot> permissionAudit() {
        return Collections.unmodifiableList(permissionAudit);
    }
    public int intValue() { return intValue; }
    public String textValue() { return textValue; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeInt(successful ? 1 : 0); out.writeString(operation); out.writeString(errorCode);
        out.writeString(errorMessage); out.writeParcelable(catalog, flags);
        out.writeParcelable(record, flags); out.writeParcelable(packageState, flags);
        out.writeParcelable(permissionRequest, flags); out.writeTypedList(permissionRequests);
        out.writeTypedList(permissionAudit); out.writeInt(intValue); out.writeString(textValue);
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
