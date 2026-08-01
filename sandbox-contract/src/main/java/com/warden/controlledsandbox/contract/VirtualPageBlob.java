package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Handle metadata for a binary field intentionally kept outside the Binder page payload. */
public final class VirtualPageBlob implements Parcelable {
    private final int itemIndex;
    private final String fieldName;
    private final String blobToken;
    private final int byteCount;
    private final String sha256;

    public VirtualPageBlob(int itemIndex, String fieldName, String blobToken, int byteCount, String sha256) {
        this.itemIndex = ContractChecks.nonNegative(itemIndex, "pageBlobItemIndex");
        this.fieldName = ContractChecks.requiredText(fieldName, "pageBlobFieldName", 64);
        this.blobToken = ContractChecks.requiredText(blobToken, "pageBlobToken", 4096);
        this.byteCount = ContractChecks.nonNegative(byteCount, "pageBlobByteCount");
        this.sha256 = ContractChecks.requiredText(sha256, "pageBlobSha256", 64);
        if (this.sha256.length() != 64) throw new IllegalArgumentException("PAGE_BLOB_DIGEST_INVALID");
    }
    private VirtualPageBlob(Parcel in) {
        this(in.readInt(), in.readString(), in.readString(), in.readInt(), in.readString());
    }
    public int itemIndex() { return itemIndex; }
    public String fieldName() { return fieldName; }
    public String blobToken() { return blobToken; }
    public int byteCount() { return byteCount; }
    public String sha256() { return sha256; }
    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeInt(itemIndex); out.writeString(fieldName); out.writeString(blobToken);
        out.writeInt(byteCount); out.writeString(sha256);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualPageBlob> CREATOR = new Creator<>() {
        @Override public VirtualPageBlob createFromParcel(Parcel in) { return new VirtualPageBlob(in); }
        @Override public VirtualPageBlob[] newArray(int size) { return new VirtualPageBlob[size]; }
    };
}
