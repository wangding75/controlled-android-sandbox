package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Bounded request for one immutable Binder collection page. */
public final class VirtualPageRequest implements Parcelable {
    public static final int MAX_ITEMS = 128;
    public static final int MIN_BYTES = 16 * 1024;
    public static final int MAX_BYTES = 256 * 1024;
    private final int maxItems;
    private final int maxBytes;
    private final String pageToken;

    public VirtualPageRequest(int maxItems, int maxBytes, String pageToken) {
        if (maxItems < 1 || maxItems > MAX_ITEMS) {
            throw new IllegalArgumentException("PAGE_MAX_ITEMS_INVALID");
        }
        if (maxBytes < MIN_BYTES || maxBytes > MAX_BYTES) {
            throw new IllegalArgumentException("PAGE_MAX_BYTES_INVALID");
        }
        String token = pageToken == null ? "" : pageToken.trim();
        if (token.length() > 4096) throw new IllegalArgumentException("PAGE_TOKEN_TOO_LONG");
        this.maxItems = maxItems;
        this.maxBytes = maxBytes;
        this.pageToken = token;
    }

    private VirtualPageRequest(Parcel in) { this(in.readInt(), in.readInt(), in.readString()); }
    public int maxItems() { return maxItems; }
    public int maxBytes() { return maxBytes; }
    public String pageToken() { return pageToken; }
    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeInt(maxItems); out.writeInt(maxBytes); out.writeString(pageToken);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualPageRequest> CREATOR = new Creator<>() {
        @Override public VirtualPageRequest createFromParcel(Parcel in) { return new VirtualPageRequest(in); }
        @Override public VirtualPageRequest[] newArray(int size) { return new VirtualPageRequest[size]; }
    };
}
