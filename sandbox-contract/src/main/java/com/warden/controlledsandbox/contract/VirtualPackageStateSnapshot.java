package com.warden.controlledsandbox.contract;

import android.content.pm.ApplicationInfo;
import android.os.Parcel;
import android.os.Parcelable;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Immutable package/component/permission/AppOps state for one virtual user. */
public final class VirtualPackageStateSnapshot implements Parcelable {
    private static final int PARCEL_MAGIC = 0x43535053;
    private static final int MAX_UNCOMPRESSED_PARCEL_BYTES = 16 * 1024 * 1024;
    private static final int MAX_COMPRESSED_PARCEL_BYTES = 8 * 1024 * 1024;
    private final String packageName;
    private final int virtualUserId;
    private final String label;
    private final String versionName;
    private final long versionCode;
    private final String signatureSha256;
    private final String apkSha256;
    private final String launchActivity;
    private final String applicationClass;
    private final boolean enabled;
    private final long firstInstallTime;
    private final long lastUpdateTime;
    private final String installerPackageName;
    private final ArrayList<String> splitNames;
    private final ArrayList<String> sharedLibraries;
    private final ArrayList<VirtualSharedLibrarySnapshot> sharedLibraryDetails;
    private final ArrayList<VirtualInstrumentationSnapshot> instrumentations;
    private final ArrayList<VirtualPackageQuerySnapshot> queries;
    private final ArrayList<VirtualComponentSnapshot> components;
    private final ArrayList<VirtualPermissionSnapshot> permissions;
    private final ArrayList<VirtualPermissionDeclarationSnapshot> permissionDeclarations;
    private final ArrayList<VirtualPermissionGroupSnapshot> permissionGroups;
    private final ArrayList<PackageAppOpSnapshot> appOps;
    private final ApplicationInfo applicationInfo;

    public VirtualPackageStateSnapshot(String packageName, int virtualUserId, String label,
                                       String versionName, long versionCode,
                                       String signatureSha256, String apkSha256,
                                       String launchActivity, String applicationClass,
                                       boolean enabled,
                                       List<VirtualComponentSnapshot> components,
                                       List<VirtualPermissionSnapshot> permissions,
                                       List<PackageAppOpSnapshot> appOps) {
        this(packageName, virtualUserId, label, versionName, versionCode, signatureSha256,
                apkSha256, launchActivity, applicationClass, enabled, 0L, 0L, "",
                List.of(), List.of(), List.of(), List.of(), List.of(), components, permissions, appOps,
                null);
    }

    public VirtualPackageStateSnapshot(String packageName, int virtualUserId, String label,
                                       String versionName, long versionCode,
                                       String signatureSha256, String apkSha256,
                                       String launchActivity, String applicationClass,
                                       boolean enabled, List<String> splitNames,
                                       List<String> sharedLibraries,
                                       List<VirtualComponentSnapshot> components,
                                       List<VirtualPermissionSnapshot> permissions,
                                       List<PackageAppOpSnapshot> appOps) {
        this(packageName, virtualUserId, label, versionName, versionCode, signatureSha256,
                apkSha256, launchActivity, applicationClass, enabled, 0L, 0L, "",
                splitNames, sharedLibraries, List.of(), List.of(), List.of(), components, permissions, appOps,
                null);
    }

    public VirtualPackageStateSnapshot(String packageName, int virtualUserId, String label,
                                       String versionName, long versionCode,
                                       String signatureSha256, String apkSha256,
                                       String launchActivity, String applicationClass,
                                       boolean enabled, long firstInstallTime,
                                       long lastUpdateTime, String installerPackageName,
                                       List<String> splitNames, List<String> sharedLibraries,
                                       List<VirtualComponentSnapshot> components,
                                       List<VirtualPermissionSnapshot> permissions,
                                       List<PackageAppOpSnapshot> appOps) {
        this(packageName, virtualUserId, label, versionName, versionCode, signatureSha256,
                apkSha256, launchActivity, applicationClass, enabled, firstInstallTime,
                lastUpdateTime, installerPackageName, splitNames, sharedLibraries, List.of(),
                List.of(), List.of(), components, permissions, appOps, null);
    }

    public VirtualPackageStateSnapshot(String packageName, int virtualUserId, String label,
                                       String versionName, long versionCode,
                                       String signatureSha256, String apkSha256,
                                       String launchActivity, String applicationClass,
                                       boolean enabled, long firstInstallTime,
                                       long lastUpdateTime, String installerPackageName,
                                       List<String> splitNames, List<String> sharedLibraries,
                                       List<VirtualSharedLibrarySnapshot> sharedLibraryDetails,
                                       List<VirtualInstrumentationSnapshot> instrumentations,
                                       List<VirtualPackageQuerySnapshot> queries,
                                       List<VirtualComponentSnapshot> components,
                                       List<VirtualPermissionSnapshot> permissions,
                                       List<PackageAppOpSnapshot> appOps) {
        this(packageName, virtualUserId, label, versionName, versionCode, signatureSha256,
                apkSha256, launchActivity, applicationClass, enabled, firstInstallTime,
                lastUpdateTime, installerPackageName, splitNames, sharedLibraries,
                sharedLibraryDetails, instrumentations, queries, components, permissions, appOps,
                null);
    }

    public VirtualPackageStateSnapshot(String packageName, int virtualUserId, String label,
                                       String versionName, long versionCode,
                                       String signatureSha256, String apkSha256,
                                       String launchActivity, String applicationClass,
                                       boolean enabled, long firstInstallTime,
                                       long lastUpdateTime, String installerPackageName,
                                       List<String> splitNames, List<String> sharedLibraries,
                                       List<VirtualSharedLibrarySnapshot> sharedLibraryDetails,
                                       List<VirtualInstrumentationSnapshot> instrumentations,
                                       List<VirtualPackageQuerySnapshot> queries,
                                       List<VirtualComponentSnapshot> components,
                                       List<VirtualPermissionSnapshot> permissions,
                                       List<PackageAppOpSnapshot> appOps,
                                       ApplicationInfo applicationInfo) {
        this(packageName, virtualUserId, label, versionName, versionCode, signatureSha256,
                apkSha256, launchActivity, applicationClass, enabled, firstInstallTime,
                lastUpdateTime, installerPackageName, splitNames, sharedLibraries,
                sharedLibraryDetails, instrumentations, queries, components, permissions,
                List.of(), List.of(), appOps, applicationInfo);
    }

    /** Full package projection including custom permission and permission-group declarations. */
    public VirtualPackageStateSnapshot(String packageName, int virtualUserId, String label,
                                       String versionName, long versionCode,
                                       String signatureSha256, String apkSha256,
                                       String launchActivity, String applicationClass,
                                       boolean enabled, long firstInstallTime,
                                       long lastUpdateTime, String installerPackageName,
                                       List<String> splitNames, List<String> sharedLibraries,
                                       List<VirtualSharedLibrarySnapshot> sharedLibraryDetails,
                                       List<VirtualInstrumentationSnapshot> instrumentations,
                                       List<VirtualPackageQuerySnapshot> queries,
                                       List<VirtualComponentSnapshot> components,
                                       List<VirtualPermissionSnapshot> permissions,
                                       List<VirtualPermissionDeclarationSnapshot> permissionDeclarations,
                                       List<VirtualPermissionGroupSnapshot> permissionGroups,
                                       List<PackageAppOpSnapshot> appOps,
                                       ApplicationInfo applicationInfo) {
        this.packageName = required(packageName, "packageName");
        if (virtualUserId < 0 || virtualUserId > 999) {
            throw new IllegalArgumentException("virtualUserId out of range");
        }
        this.virtualUserId = virtualUserId;
        this.label = value(label);
        this.versionName = value(versionName);
        this.versionCode = versionCode;
        this.signatureSha256 = required(signatureSha256, "signatureSha256");
        this.apkSha256 = digest(apkSha256, "apkSha256");
        this.launchActivity = value(launchActivity);
        this.applicationClass = value(applicationClass);
        this.enabled = enabled;
        if (firstInstallTime < 0 || lastUpdateTime < 0
                || (firstInstallTime > 0 && lastUpdateTime > 0 && lastUpdateTime < firstInstallTime)) {
            throw new IllegalArgumentException("Invalid package install timestamps");
        }
        this.firstInstallTime = firstInstallTime;
        this.lastUpdateTime = lastUpdateTime;
        this.installerPackageName = value(installerPackageName);
        this.splitNames = validatedNames(splitNames, "splitName", 255);
        this.sharedLibraries = validatedNames(sharedLibraries, "sharedLibrary", 1024);
        this.sharedLibraryDetails = new ArrayList<>(sharedLibraryDetails == null ? List.of() : sharedLibraryDetails);
        this.instrumentations = new ArrayList<>(instrumentations == null ? List.of() : instrumentations);
        this.queries = new ArrayList<>(queries == null ? List.of() : queries);
        if (this.sharedLibraryDetails.size() > 1024) {
            throw new IllegalArgumentException("sharedLibraryDetails list is too large");
        }
        if (this.instrumentations.size() > 256) {
            throw new IllegalArgumentException("instrumentations list is too large");
        }
        if (this.queries.size() > 1024) {
            throw new IllegalArgumentException("package query list is too large");
        }
        this.components = new ArrayList<>(components == null ? List.of() : components);
        this.permissions = new ArrayList<>(permissions == null ? List.of() : permissions);
        this.permissionDeclarations = new ArrayList<>(permissionDeclarations == null
                ? List.of() : permissionDeclarations);
        this.permissionGroups = new ArrayList<>(permissionGroups == null
                ? List.of() : permissionGroups);
        if (this.permissionDeclarations.size() > 1024) {
            throw new IllegalArgumentException("permission declarations list is too large");
        }
        if (this.permissionGroups.size() > 256) {
            throw new IllegalArgumentException("permission groups list is too large");
        }
        this.appOps = new ArrayList<>(appOps == null ? List.of() : appOps);
        this.applicationInfo = applicationInfo == null ? null : new ApplicationInfo(applicationInfo);
    }

    private VirtualPackageStateSnapshot(Parcel in) {
        this(readSnapshot(in));
    }

    private VirtualPackageStateSnapshot(Parcel in, boolean raw) {
        this(in.readString(), in.readInt(), in.readString(), in.readString(), in.readLong(),
                in.readString(), in.readString(), in.readString(), in.readString(),
                in.readInt() != 0, in.readLong(), in.readLong(), in.readString(),
                in.createStringArrayList(), in.createStringArrayList(),
                in.createTypedArrayList(VirtualSharedLibrarySnapshot.CREATOR),
                in.createTypedArrayList(VirtualInstrumentationSnapshot.CREATOR),
                in.createTypedArrayList(VirtualPackageQuerySnapshot.CREATOR),
                in.createTypedArrayList(VirtualComponentSnapshot.CREATOR),
                in.createTypedArrayList(VirtualPermissionSnapshot.CREATOR),
                in.createTypedArrayList(VirtualPermissionDeclarationSnapshot.CREATOR),
                in.createTypedArrayList(VirtualPermissionGroupSnapshot.CREATOR),
                in.createTypedArrayList(PackageAppOpSnapshot.CREATOR),
                in.readTypedObject(ApplicationInfo.CREATOR));
    }

    private VirtualPackageStateSnapshot(VirtualPackageStateSnapshot source) {
        this(source.packageName, source.virtualUserId, source.label, source.versionName,
                source.versionCode, source.signatureSha256, source.apkSha256,
                source.launchActivity, source.applicationClass, source.enabled,
                source.firstInstallTime, source.lastUpdateTime, source.installerPackageName,
                source.splitNames, source.sharedLibraries, source.sharedLibraryDetails,
                source.instrumentations, source.queries, source.components, source.permissions,
                source.permissionDeclarations, source.permissionGroups, source.appOps,
                source.applicationInfo);
    }

    public String packageName() { return packageName; }
    public int virtualUserId() { return virtualUserId; }
    public String label() { return label; }
    public String versionName() { return versionName; }
    public long versionCode() { return versionCode; }
    public String signatureSha256() { return signatureSha256; }
    public String apkSha256() { return apkSha256; }
    public String launchActivity() { return launchActivity; }
    public String applicationClass() { return applicationClass; }
    public boolean enabled() { return enabled; }
    public long firstInstallTime() { return firstInstallTime; }
    public long lastUpdateTime() { return lastUpdateTime; }
    public String installerPackageName() { return installerPackageName; }
    public List<String> splitNames() { return Collections.unmodifiableList(splitNames); }
    public List<String> sharedLibraries() { return Collections.unmodifiableList(sharedLibraries); }
    public List<VirtualSharedLibrarySnapshot> sharedLibraryDetails() {
        return Collections.unmodifiableList(sharedLibraryDetails);
    }
    public List<VirtualInstrumentationSnapshot> instrumentations() {
        return Collections.unmodifiableList(instrumentations);
    }
    public List<VirtualPackageQuerySnapshot> queries() {
        return Collections.unmodifiableList(queries);
    }
    public List<VirtualComponentSnapshot> components() { return Collections.unmodifiableList(components); }
    public List<VirtualPermissionSnapshot> permissions() { return Collections.unmodifiableList(permissions); }
    public List<VirtualPermissionDeclarationSnapshot> permissionDeclarations() {
        return Collections.unmodifiableList(permissionDeclarations);
    }
    public List<VirtualPermissionGroupSnapshot> permissionGroups() {
        return Collections.unmodifiableList(permissionGroups);
    }
    public List<PackageAppOpSnapshot> appOps() { return Collections.unmodifiableList(appOps); }
    public ApplicationInfo applicationInfo() {
        return applicationInfo == null ? null : new ApplicationInfo(applicationInfo);
    }

    @Override public void writeToParcel(Parcel out, int flags) {
        Parcel payload = Parcel.obtain();
        try {
            writeRawContents(payload, flags);
            byte[] raw = payload.marshall();
            if (raw.length > MAX_UNCOMPRESSED_PARCEL_BYTES) {
                throw new IllegalArgumentException("Package state parcel is too large");
            }
            byte[] compressed = compress(raw);
            if (compressed.length > MAX_COMPRESSED_PARCEL_BYTES) {
                throw new IllegalArgumentException("Compressed package state parcel is too large");
            }
            out.writeInt(PARCEL_MAGIC);
            out.writeInt(raw.length);
            out.writeByteArray(compressed);
        } finally {
            payload.recycle();
        }
    }

    private void writeRawContents(Parcel out, int flags) {
        out.writeString(packageName); out.writeInt(virtualUserId); out.writeString(label);
        out.writeString(versionName); out.writeLong(versionCode); out.writeString(signatureSha256);
        out.writeString(apkSha256); out.writeString(launchActivity); out.writeString(applicationClass);
        out.writeInt(enabled ? 1 : 0); out.writeLong(firstInstallTime); out.writeLong(lastUpdateTime);
        out.writeString(installerPackageName); out.writeStringList(splitNames);
        out.writeStringList(sharedLibraries); out.writeTypedList(sharedLibraryDetails);
        out.writeTypedList(instrumentations); out.writeTypedList(queries);
        out.writeTypedList(components);
        out.writeTypedList(permissions); out.writeTypedList(permissionDeclarations);
        out.writeTypedList(permissionGroups); out.writeTypedList(appOps);
        out.writeTypedObject(applicationInfo, flags);
    }
    @Override public int describeContents() { return 0; }

    public static final Creator<VirtualPackageStateSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualPackageStateSnapshot createFromParcel(Parcel in) {
            return new VirtualPackageStateSnapshot(in);
        }
        @Override public VirtualPackageStateSnapshot[] newArray(int size) {
            return new VirtualPackageStateSnapshot[size];
        }
    };

    private static VirtualPackageStateSnapshot readSnapshot(Parcel in) {
        if (in.readInt() != PARCEL_MAGIC) {
            throw new IllegalArgumentException("Unsupported package state parcel format");
        }
        int rawLength = in.readInt();
        byte[] compressed = in.createByteArray();
        if (rawLength < 0 || rawLength > MAX_UNCOMPRESSED_PARCEL_BYTES
                || compressed == null || compressed.length > MAX_COMPRESSED_PARCEL_BYTES) {
            throw new IllegalArgumentException("Invalid package state parcel bounds");
        }
        byte[] raw = decompress(compressed, rawLength);
        Parcel payload = Parcel.obtain();
        try {
            payload.unmarshall(raw, 0, raw.length);
            payload.setDataPosition(0);
            return new VirtualPackageStateSnapshot(payload, true);
        } finally {
            payload.recycle();
        }
    }

    private static byte[] compress(byte[] raw) {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream output = new ByteArrayOutputStream(raw.length);
            byte[] buffer = new byte[16 * 1024];
            while (!deflater.finished()) output.write(buffer, 0, deflater.deflate(buffer));
            return output.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static byte[] decompress(byte[] compressed, int expectedLength) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            ByteArrayOutputStream output = new ByteArrayOutputStream(expectedLength);
            byte[] buffer = new byte[16 * 1024];
            while (!inflater.finished()) {
                if (inflater.needsDictionary() || inflater.needsInput()) {
                    throw new IllegalArgumentException("Invalid compressed package state parcel");
                }
                int count = inflater.inflate(buffer);
                if (count == 0) {
                    if (output.size() >= expectedLength && inflater.finished()) break;
                    throw new IllegalArgumentException("Invalid compressed package state parcel");
                }
                output.write(buffer, 0, count);
                if (output.size() > expectedLength) {
                    throw new IllegalArgumentException("Package state parcel expands beyond bounds");
                }
            }
            byte[] raw = output.toByteArray();
            if (raw.length != expectedLength) {
                throw new IllegalArgumentException("Package state parcel length mismatch");
            }
            return raw;
        } catch (java.util.zip.DataFormatException error) {
            throw new IllegalArgumentException("Invalid compressed package state parcel", error);
        } finally {
            inflater.end();
        }
    }

    private static ArrayList<String> validatedNames(List<String> input, String name, int maximum) {
        ArrayList<String> output = new ArrayList<>();
        java.util.Set<String> unique = new java.util.LinkedHashSet<>();
        if (input == null) return output;
        if (input.size() > maximum) throw new IllegalArgumentException(name + " list is too large");
        for (String value : input) {
            String normalized = required(value, name);
            if (!unique.add(normalized)) throw new IllegalArgumentException("Duplicate " + name + ": " + normalized);
            output.add(normalized);
        }
        return output;
    }
    private static String digest(String value, String name) {
        String normalized = required(value, name).toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must contain 64 hexadecimal characters");
        }
        return normalized;
    }
    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
    private static String value(String value) { return value == null ? "" : value; }
}
