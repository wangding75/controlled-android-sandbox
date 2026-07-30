package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.List;

/** Generic fail-closed OEM Binder service surface for one guest scope. */
public final class VirtualOemSystemServicesProfileSnapshot implements Parcelable {
    private final String mode;
    private final List<String> serviceNames;
    private final List<String> allowedQueryPrefixes;
    private final List<String> blockedMutationPrefixes;
    private final int maximumSessions;

    public VirtualOemSystemServicesProfileSnapshot(
            String mode, List<String> serviceNames, List<String> allowedQueryPrefixes,
            List<String> blockedMutationPrefixes, int maximumSessions) {
        this.mode = VirtualLocationProfileSnapshot.mode(mode);
        this.serviceNames = ContractLists.unique(serviceNames, "serviceNames", 64, 160, false);
        this.allowedQueryPrefixes = ContractLists.unique(
                allowedQueryPrefixes, "allowedQueryPrefixes", 128, 96, false);
        this.blockedMutationPrefixes = ContractLists.unique(
                blockedMutationPrefixes, "blockedMutationPrefixes", 128, 96, false);
        if (maximumSessions < 0 || maximumSessions > 128) {
            throw new IllegalArgumentException("maximumSessions must be in [0,128]");
        }
        this.maximumSessions = maximumSessions;
    }

    private VirtualOemSystemServicesProfileSnapshot(Parcel in) {
        this(in.readString(), in.createStringArrayList(), in.createStringArrayList(),
                in.createStringArrayList(), in.readInt());
    }

    public String mode() { return mode; }
    public List<String> serviceNames() { return serviceNames; }
    public List<String> allowedQueryPrefixes() { return allowedQueryPrefixes; }
    public List<String> blockedMutationPrefixes() { return blockedMutationPrefixes; }
    public int maximumSessions() { return maximumSessions; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode);
        out.writeStringList(serviceNames);
        out.writeStringList(allowedQueryPrefixes);
        out.writeStringList(blockedMutationPrefixes);
        out.writeInt(maximumSessions);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<VirtualOemSystemServicesProfileSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualOemSystemServicesProfileSnapshot createFromParcel(Parcel in) {
            return new VirtualOemSystemServicesProfileSnapshot(in);
        }
        @Override public VirtualOemSystemServicesProfileSnapshot[] newArray(int size) {
            return new VirtualOemSystemServicesProfileSnapshot[size];
        }
    };
}
