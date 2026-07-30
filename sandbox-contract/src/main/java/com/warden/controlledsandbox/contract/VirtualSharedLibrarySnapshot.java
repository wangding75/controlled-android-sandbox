package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.Locale;

/** Typed shared-library dependency and resolution result for one trusted package revision. */
public final class VirtualSharedLibrarySnapshot implements Parcelable {
    public static final String KIND_JAVA = "JAVA";
    public static final String KIND_NATIVE = "NATIVE";
    public static final String KIND_SDK = "SDK";
    public static final String KIND_STATIC = "STATIC";

    private final String kind;
    private final String name;
    private final boolean required;
    private final long version;
    private final String certificateDigest;
    private final boolean resolved;
    private final String providerPackage;

    public VirtualSharedLibrarySnapshot(String kind, String name, boolean required, long version,
                                        String certificateDigest, boolean resolved,
                                        String providerPackage) {
        this.kind = kind(kind);
        this.name = required(name, "name");
        if (version < 0) throw new IllegalArgumentException("version is invalid");
        this.version = version;
        this.required = required;
        String digest = value(certificateDigest).toLowerCase(Locale.ROOT).replace(":", "");
        if (!digest.isEmpty() && !digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("certificateDigest is invalid");
        }
        this.certificateDigest = digest;
        this.resolved = resolved;
        this.providerPackage = value(providerPackage);
        if (resolved && this.providerPackage.isEmpty()) {
            throw new IllegalArgumentException("resolved library requires providerPackage");
        }
    }

    private VirtualSharedLibrarySnapshot(Parcel in) {
        this(in.readString(), in.readString(), in.readInt() != 0, in.readLong(),
                in.readString(), in.readInt() != 0, in.readString());
    }

    public String kind() { return kind; }
    public String name() { return name; }
    public boolean required() { return required; }
    public long version() { return version; }
    public String certificateDigest() { return certificateDigest; }
    public boolean resolved() { return resolved; }
    public String providerPackage() { return providerPackage; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(kind); out.writeString(name); out.writeInt(required ? 1 : 0);
        out.writeLong(version); out.writeString(certificateDigest);
        out.writeInt(resolved ? 1 : 0); out.writeString(providerPackage);
    }
    @Override public int describeContents() { return 0; }

    public static final Creator<VirtualSharedLibrarySnapshot> CREATOR = new Creator<>() {
        @Override public VirtualSharedLibrarySnapshot createFromParcel(Parcel in) {
            return new VirtualSharedLibrarySnapshot(in);
        }
        @Override public VirtualSharedLibrarySnapshot[] newArray(int size) {
            return new VirtualSharedLibrarySnapshot[size];
        }
    };

    private static String kind(String value) {
        String normalized = required(value, "kind").toUpperCase(Locale.ROOT);
        if (!normalized.equals(KIND_JAVA) && !normalized.equals(KIND_NATIVE)
                && !normalized.equals(KIND_SDK) && !normalized.equals(KIND_STATIC)) {
            throw new IllegalArgumentException("Unsupported shared library kind: " + value);
        }
        return normalized;
    }
    private static String required(String value, String name) {
        String normalized = value(value);
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " is required");
        return normalized;
    }
    private static String value(String value) { return value == null ? "" : value.trim(); }
}
