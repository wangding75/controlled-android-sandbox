package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Aggregate NFC/USB/printing/companion/projection/camera/OEM service profile. */
public final class VirtualPeripheralServicesProfileSnapshot implements Parcelable {
    private final long policyVersion;
    private final long updatedAtMs;
    private final VirtualNfcProfileSnapshot nfc;
    private final VirtualUsbProfileSnapshot usb;
    private final VirtualPrintProfileSnapshot printing;
    private final VirtualCompanionDeviceProfileSnapshot companionDevice;
    private final VirtualMediaProjectionProfileSnapshot mediaProjection;
    private final VirtualCameraProfileSnapshot camera;
    private final VirtualOemSystemServicesProfileSnapshot oemSystemServices;

    public VirtualPeripheralServicesProfileSnapshot(
            long policyVersion, long updatedAtMs, VirtualNfcProfileSnapshot nfc,
            VirtualUsbProfileSnapshot usb, VirtualPrintProfileSnapshot printing,
            VirtualCompanionDeviceProfileSnapshot companionDevice,
            VirtualMediaProjectionProfileSnapshot mediaProjection,
            VirtualCameraProfileSnapshot camera,
            VirtualOemSystemServicesProfileSnapshot oemSystemServices) {
        if (policyVersion < 1L || updatedAtMs < 0L) {
            throw new IllegalArgumentException("peripheral profile version/time is invalid");
        }
        this.policyVersion = policyVersion;
        this.updatedAtMs = updatedAtMs;
        this.nfc = java.util.Objects.requireNonNull(nfc, "nfc");
        this.usb = java.util.Objects.requireNonNull(usb, "usb");
        this.printing = java.util.Objects.requireNonNull(printing, "printing");
        this.companionDevice = java.util.Objects.requireNonNull(companionDevice, "companionDevice");
        this.mediaProjection = java.util.Objects.requireNonNull(mediaProjection, "mediaProjection");
        this.camera = java.util.Objects.requireNonNull(camera, "camera");
        this.oemSystemServices = java.util.Objects.requireNonNull(oemSystemServices, "oemSystemServices");
    }

    private VirtualPeripheralServicesProfileSnapshot(Parcel in) {
        this(in.readLong(), in.readLong(),
                in.readParcelable(VirtualNfcProfileSnapshot.class.getClassLoader()),
                in.readParcelable(VirtualUsbProfileSnapshot.class.getClassLoader()),
                in.readParcelable(VirtualPrintProfileSnapshot.class.getClassLoader()),
                in.readParcelable(VirtualCompanionDeviceProfileSnapshot.class.getClassLoader()),
                in.readParcelable(VirtualMediaProjectionProfileSnapshot.class.getClassLoader()),
                in.readParcelable(VirtualCameraProfileSnapshot.class.getClassLoader()),
                in.readParcelable(VirtualOemSystemServicesProfileSnapshot.class.getClassLoader()));
    }

    public long policyVersion() { return policyVersion; }
    public long updatedAtMs() { return updatedAtMs; }
    public VirtualNfcProfileSnapshot nfc() { return nfc; }
    public VirtualUsbProfileSnapshot usb() { return usb; }
    public VirtualPrintProfileSnapshot printing() { return printing; }
    public VirtualCompanionDeviceProfileSnapshot companionDevice() { return companionDevice; }
    public VirtualMediaProjectionProfileSnapshot mediaProjection() { return mediaProjection; }
    public VirtualCameraProfileSnapshot camera() { return camera; }
    public VirtualOemSystemServicesProfileSnapshot oemSystemServices() { return oemSystemServices; }

    public VirtualPeripheralServicesProfileSnapshot withVersion(long version, long updatedAt) {
        return new VirtualPeripheralServicesProfileSnapshot(
                version, updatedAt, nfc, usb, printing, companionDevice,
                mediaProjection, camera, oemSystemServices);
    }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeLong(policyVersion);
        out.writeLong(updatedAtMs);
        out.writeParcelable(nfc, flags);
        out.writeParcelable(usb, flags);
        out.writeParcelable(printing, flags);
        out.writeParcelable(companionDevice, flags);
        out.writeParcelable(mediaProjection, flags);
        out.writeParcelable(camera, flags);
        out.writeParcelable(oemSystemServices, flags);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<VirtualPeripheralServicesProfileSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualPeripheralServicesProfileSnapshot createFromParcel(Parcel in) {
            return new VirtualPeripheralServicesProfileSnapshot(in);
        }
        @Override public VirtualPeripheralServicesProfileSnapshot[] newArray(int size) {
            return new VirtualPeripheralServicesProfileSnapshot[size];
        }
    };
}
