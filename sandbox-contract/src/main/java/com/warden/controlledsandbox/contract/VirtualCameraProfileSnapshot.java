package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.List;

/** Camera inventory, torch and open-session policy for one guest scope. */
public final class VirtualCameraProfileSnapshot implements Parcelable {
    private final String mode;
    private final boolean cameraAvailable;
    private final boolean allowOpen;
    private final boolean allowTorch;
    private final int maximumOpenCameras;
    private final List<String> cameraIds;
    private final List<String> frontCameraIds;
    private final List<String> torchAvailableCameraIds;

    public VirtualCameraProfileSnapshot(
            String mode, boolean cameraAvailable, boolean allowOpen, boolean allowTorch,
            int maximumOpenCameras, List<String> cameraIds, List<String> frontCameraIds,
            List<String> torchAvailableCameraIds) {
        this.mode = VirtualLocationProfileSnapshot.mode(mode);
        this.cameraAvailable = cameraAvailable;
        this.allowOpen = allowOpen;
        this.allowTorch = allowTorch;
        if (maximumOpenCameras < 0 || maximumOpenCameras > 16) {
            throw new IllegalArgumentException("maximumOpenCameras must be in [0,16]");
        }
        this.maximumOpenCameras = maximumOpenCameras;
        this.cameraIds = ContractLists.unique(cameraIds, "cameraIds", 32, 64, false);
        this.frontCameraIds = ContractLists.unique(frontCameraIds, "frontCameraIds", 32, 64, false);
        this.torchAvailableCameraIds = ContractLists.unique(
                torchAvailableCameraIds, "torchAvailableCameraIds", 32, 64, false);
        requireSubset(this.frontCameraIds, this.cameraIds, "frontCameraIds");
        requireSubset(this.torchAvailableCameraIds, this.cameraIds, "torchAvailableCameraIds");
    }

    private VirtualCameraProfileSnapshot(Parcel in) {
        this(in.readString(), in.readInt() != 0, in.readInt() != 0, in.readInt() != 0,
                in.readInt(), in.createStringArrayList(), in.createStringArrayList(),
                in.createStringArrayList());
    }

    public String mode() { return mode; }
    public boolean cameraAvailable() { return cameraAvailable; }
    public boolean allowOpen() { return allowOpen; }
    public boolean allowTorch() { return allowTorch; }
    public int maximumOpenCameras() { return maximumOpenCameras; }
    public List<String> cameraIds() { return cameraIds; }
    public List<String> frontCameraIds() { return frontCameraIds; }
    public List<String> torchAvailableCameraIds() { return torchAvailableCameraIds; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode);
        out.writeInt(cameraAvailable ? 1 : 0);
        out.writeInt(allowOpen ? 1 : 0);
        out.writeInt(allowTorch ? 1 : 0);
        out.writeInt(maximumOpenCameras);
        out.writeStringList(cameraIds);
        out.writeStringList(frontCameraIds);
        out.writeStringList(torchAvailableCameraIds);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<VirtualCameraProfileSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualCameraProfileSnapshot createFromParcel(Parcel in) {
            return new VirtualCameraProfileSnapshot(in);
        }
        @Override public VirtualCameraProfileSnapshot[] newArray(int size) {
            return new VirtualCameraProfileSnapshot[size];
        }
    };

    private static void requireSubset(List<String> values, List<String> parent, String field) {
        if (!parent.containsAll(values)) throw new IllegalArgumentException(field + " must be a cameraIds subset");
    }
}
