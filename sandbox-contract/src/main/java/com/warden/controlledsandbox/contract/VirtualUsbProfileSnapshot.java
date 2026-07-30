package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.List;

/** USB host/accessory and permission policy for one guest scope. */
public final class VirtualUsbProfileSnapshot implements Parcelable {
    private final String mode;
    private final boolean hostSupported;
    private final boolean accessorySupported;
    private final boolean allowPermissionRequests;
    private final boolean allowOpenDevice;
    private final int maximumOpenDevices;
    private final String defaultFunctions;
    private final List<String> approvedDeviceNames;
    private final List<String> approvedAccessoryIds;

    public VirtualUsbProfileSnapshot(
            String mode, boolean hostSupported, boolean accessorySupported,
            boolean allowPermissionRequests, boolean allowOpenDevice, int maximumOpenDevices,
            String defaultFunctions, List<String> approvedDeviceNames,
            List<String> approvedAccessoryIds) {
        this.mode = VirtualLocationProfileSnapshot.mode(mode);
        this.hostSupported = hostSupported;
        this.accessorySupported = accessorySupported;
        this.allowPermissionRequests = allowPermissionRequests;
        this.allowOpenDevice = allowOpenDevice;
        if (maximumOpenDevices < 0 || maximumOpenDevices > 64) {
            throw new IllegalArgumentException("maximumOpenDevices must be in [0,64]");
        }
        this.maximumOpenDevices = maximumOpenDevices;
        this.defaultFunctions = ContractChecks.optionalText(
                defaultFunctions, "defaultFunctions", 128).trim();
        this.approvedDeviceNames = ContractLists.unique(
                approvedDeviceNames, "approvedDeviceNames", 128, 256, false);
        this.approvedAccessoryIds = ContractLists.unique(
                approvedAccessoryIds, "approvedAccessoryIds", 64, 256, false);
    }

    private VirtualUsbProfileSnapshot(Parcel in) {
        this(in.readString(), in.readInt() != 0, in.readInt() != 0, in.readInt() != 0,
                in.readInt() != 0, in.readInt(), in.readString(),
                in.createStringArrayList(), in.createStringArrayList());
    }

    public String mode() { return mode; }
    public boolean hostSupported() { return hostSupported; }
    public boolean accessorySupported() { return accessorySupported; }
    public boolean allowPermissionRequests() { return allowPermissionRequests; }
    public boolean allowOpenDevice() { return allowOpenDevice; }
    public int maximumOpenDevices() { return maximumOpenDevices; }
    public String defaultFunctions() { return defaultFunctions; }
    public List<String> approvedDeviceNames() { return approvedDeviceNames; }
    public List<String> approvedAccessoryIds() { return approvedAccessoryIds; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode);
        out.writeInt(hostSupported ? 1 : 0);
        out.writeInt(accessorySupported ? 1 : 0);
        out.writeInt(allowPermissionRequests ? 1 : 0);
        out.writeInt(allowOpenDevice ? 1 : 0);
        out.writeInt(maximumOpenDevices);
        out.writeString(defaultFunctions);
        out.writeStringList(approvedDeviceNames);
        out.writeStringList(approvedAccessoryIds);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<VirtualUsbProfileSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualUsbProfileSnapshot createFromParcel(Parcel in) {
            return new VirtualUsbProfileSnapshot(in);
        }
        @Override public VirtualUsbProfileSnapshot[] newArray(int size) {
            return new VirtualUsbProfileSnapshot[size];
        }
    };
}
