package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Non-sensitive account identity returned by collection enumeration. */
public final class VirtualAccountSummary implements Parcelable {
    private final String name;
    private final String type;
    public VirtualAccountSummary(String name, String type) {
        this.name = ContractChecks.requiredText(name, "accountName", 512).trim();
        this.type = ContractChecks.requiredText(type, "accountType", 512).trim();
    }
    private VirtualAccountSummary(Parcel in) { this(in.readString(), in.readString()); }
    public String name() { return name; }
    public String type() { return type; }
    @Override public void writeToParcel(Parcel out, int flags) { out.writeString(name); out.writeString(type); }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualAccountSummary> CREATOR = new Creator<>() {
        @Override public VirtualAccountSummary createFromParcel(Parcel in) { return new VirtualAccountSummary(in); }
        @Override public VirtualAccountSummary[] newArray(int size) { return new VirtualAccountSummary[size]; }
    };
}
