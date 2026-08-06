package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Typed Provider path permission or URI-grant rule from AndroidManifest.xml. */
public final class VirtualProviderPathRuleSnapshot implements Parcelable {
    private final String path;
    private final String pathPrefix;
    private final String pathPattern;
    private final String readPermission;
    private final String writePermission;
    private final boolean uriGrantRule;

    public VirtualProviderPathRuleSnapshot(String path, String pathPrefix, String pathPattern,
                                           String readPermission, String writePermission,
                                           boolean uriGrantRule) {
        this.path = value(path);
        this.pathPrefix = value(pathPrefix);
        this.pathPattern = value(pathPattern);
        int matchers = (this.path.isEmpty() ? 0 : 1) + (this.pathPrefix.isEmpty() ? 0 : 1)
                + (this.pathPattern.isEmpty() ? 0 : 1);
        if (matchers != 1) throw new IllegalArgumentException("Provider path rule requires exactly one matcher");
        this.readPermission = value(readPermission);
        this.writePermission = value(writePermission);
        this.uriGrantRule = uriGrantRule;
        if (uriGrantRule && (!this.readPermission.isEmpty() || !this.writePermission.isEmpty())) {
            throw new IllegalArgumentException("URI grant rule cannot declare read/write permission");
        }
    }

    private VirtualProviderPathRuleSnapshot(Parcel in) {
        this(in.readString(), in.readString(), in.readString(), in.readString(), in.readString(),
                in.readInt() != 0);
    }

    public String path() { return path; }
    public String pathPrefix() { return pathPrefix; }
    public String pathPattern() { return pathPattern; }
    public String readPermission() { return readPermission; }
    public String writePermission() { return writePermission; }
    public boolean uriGrantRule() { return uriGrantRule; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(path); out.writeString(pathPrefix); out.writeString(pathPattern);
        out.writeString(readPermission); out.writeString(writePermission);
        out.writeInt(uriGrantRule ? 1 : 0);
    }
    @Override public int describeContents() { return 0; }

    public static final Creator<VirtualProviderPathRuleSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualProviderPathRuleSnapshot createFromParcel(Parcel in) {
            return new VirtualProviderPathRuleSnapshot(in);
        }
        @Override public VirtualProviderPathRuleSnapshot[] newArray(int size) {
            return new VirtualProviderPathRuleSnapshot[size];
        }
    };

    private static String value(String value) { return value == null ? "" : value.trim(); }
}
