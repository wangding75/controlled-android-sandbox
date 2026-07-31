package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.List;

/** Deterministic source-side ContextHub description. */
public final class VirtualContextHubSnapshot implements Parcelable {
    private final int hubId;
    private final String name;
    private final String vendor;
    private final int maximumPacketLengthBytes;
    private final List<String> nanoAppIds;

    public VirtualContextHubSnapshot(int hubId, String name, String vendor,
            int maximumPacketLengthBytes, List<String> nanoAppIds) {
        if (hubId < 0 || hubId > 65535) throw new IllegalArgumentException("hubId is invalid");
        this.hubId = hubId;
        this.name = ContractChecks.requiredText(name, "name", 128);
        this.vendor = ContractChecks.optionalText(vendor, "vendor", 128);
        if (maximumPacketLengthBytes < 0 || maximumPacketLengthBytes > 1_048_576) {
            throw new IllegalArgumentException("maximumPacketLengthBytes is invalid");
        }
        this.maximumPacketLengthBytes = maximumPacketLengthBytes;
        this.nanoAppIds = checkedNanoApps(nanoAppIds);
    }

    private VirtualContextHubSnapshot(Parcel in) {
        this(in.readInt(), in.readString(), in.readString(), in.readInt(), in.createStringArrayList());
    }

    public int hubId() { return hubId; }
    public String name() { return name; }
    public String vendor() { return vendor; }
    public int maximumPacketLengthBytes() { return maximumPacketLengthBytes; }
    public List<String> nanoAppIds() { return nanoAppIds; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeInt(hubId);
        out.writeString(name);
        out.writeString(vendor);
        out.writeInt(maximumPacketLengthBytes);
        out.writeStringList(nanoAppIds);
    }
    @Override public int describeContents() { return 0; }

    private static List<String> checkedNanoApps(List<String> values) {
        List<String> checked = ContractLists.unique(values, "nanoAppIds", 256, 18, false);
        for (String value : checked) {
            if (!value.matches("0x[0-9a-fA-F]{1,16}")) {
                throw new IllegalArgumentException("nanoAppId must be hexadecimal");
            }
        }
        return checked;
    }

    public static final Creator<VirtualContextHubSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualContextHubSnapshot createFromParcel(Parcel in) {
            return new VirtualContextHubSnapshot(in);
        }
        @Override public VirtualContextHubSnapshot[] newArray(int size) {
            return new VirtualContextHubSnapshot[size];
        }
    };
}
