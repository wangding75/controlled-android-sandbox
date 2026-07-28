package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** One immutable data constraint from a virtual component intent filter. */
public final class VirtualIntentDataSnapshot implements Parcelable {
    private final String scheme;
    private final String host;
    private final String path;
    private final String pathPrefix;
    private final String pathPattern;
    private final String mimeType;

    public VirtualIntentDataSnapshot(String scheme, String host, String path,
                                     String pathPrefix, String pathPattern, String mimeType) {
        this.scheme = value(scheme);
        this.host = value(host);
        this.path = value(path);
        this.pathPrefix = value(pathPrefix);
        this.pathPattern = value(pathPattern);
        this.mimeType = value(mimeType);
        if (this.scheme.length() > 128 || this.host.length() > 512
                || this.path.length() > 2048 || this.pathPrefix.length() > 2048
                || this.pathPattern.length() > 2048 || this.mimeType.length() > 255) {
            throw new IllegalArgumentException("Intent data constraint is too large");
        }
    }

    private VirtualIntentDataSnapshot(Parcel in) {
        this(in.readString(), in.readString(), in.readString(), in.readString(),
                in.readString(), in.readString());
    }

    public String scheme() { return scheme; }
    public String host() { return host; }
    public String path() { return path; }
    public String pathPrefix() { return pathPrefix; }
    public String pathPattern() { return pathPattern; }
    public String mimeType() { return mimeType; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(scheme); out.writeString(host); out.writeString(path);
        out.writeString(pathPrefix); out.writeString(pathPattern); out.writeString(mimeType);
    }
    @Override public int describeContents() { return 0; }

    public static final Creator<VirtualIntentDataSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualIntentDataSnapshot createFromParcel(Parcel in) {
            return new VirtualIntentDataSnapshot(in);
        }
        @Override public VirtualIntentDataSnapshot[] newArray(int size) {
            return new VirtualIntentDataSnapshot[size];
        }
    };

    private static String value(String value) { return value == null ? "" : value.trim(); }
}
