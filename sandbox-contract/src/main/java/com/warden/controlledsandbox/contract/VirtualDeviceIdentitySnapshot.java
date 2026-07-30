package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.Locale;

/** Stable virtual device identity visible to one package and virtual user. */
public final class VirtualDeviceIdentitySnapshot implements Parcelable {
    private final String mode;
    private final String androidId;
    private final String serial;
    private final String advertisingId;
    private final boolean limitAdTracking;
    private final String installationId;
    private final String manufacturer;
    private final String brand;
    private final String model;
    private final String device;
    private final String product;
    private final String fingerprint;
    private final String board;
    private final String hardware;

    public VirtualDeviceIdentitySnapshot(String mode, String androidId, String serial,
            String advertisingId, boolean limitAdTracking, String installationId,
            String manufacturer, String brand, String model, String device,
            String product, String fingerprint, String board, String hardware) {
        this.mode = VirtualLocationProfileSnapshot.mode(mode);
        this.androidId = identifier(androidId, "androidId", 64);
        if (VirtualLocationProfileSnapshot.MODE_STATIC.equals(this.mode)
                && !this.androidId.matches("[0-9a-fA-F]{16}")) {
            throw new IllegalArgumentException("androidId must be 16 hexadecimal characters in STATIC mode");
        }
        this.serial = identifier(serial, "serial", 64);
        this.advertisingId = identifier(advertisingId, "advertisingId", 64);
        if (!this.advertisingId.isEmpty() && !this.advertisingId.toLowerCase(Locale.ROOT)
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("advertisingId is invalid");
        }
        this.limitAdTracking = limitAdTracking;
        this.installationId = identifier(installationId, "installationId", 128);
        this.manufacturer = text(manufacturer, "manufacturer", 64);
        this.brand = text(brand, "brand", 64);
        this.model = text(model, "model", 96);
        this.device = text(device, "device", 64);
        this.product = text(product, "product", 64);
        this.fingerprint = text(fingerprint, "fingerprint", 256);
        this.board = text(board, "board", 64);
        this.hardware = text(hardware, "hardware", 64);
    }

    private VirtualDeviceIdentitySnapshot(Parcel in) {
        this(in.readString(), in.readString(), in.readString(), in.readString(), in.readInt() != 0,
                in.readString(), in.readString(), in.readString(), in.readString(), in.readString(),
                in.readString(), in.readString(), in.readString(), in.readString());
    }

    public String mode() { return mode; }
    public String androidId() { return androidId; }
    public String serial() { return serial; }
    public String advertisingId() { return advertisingId; }
    public boolean limitAdTracking() { return limitAdTracking; }
    public String installationId() { return installationId; }
    public String manufacturer() { return manufacturer; }
    public String brand() { return brand; }
    public String model() { return model; }
    public String device() { return device; }
    public String product() { return product; }
    public String fingerprint() { return fingerprint; }
    public String board() { return board; }
    public String hardware() { return hardware; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode); out.writeString(androidId); out.writeString(serial);
        out.writeString(advertisingId); out.writeInt(limitAdTracking ? 1 : 0);
        out.writeString(installationId); out.writeString(manufacturer); out.writeString(brand);
        out.writeString(model); out.writeString(device); out.writeString(product);
        out.writeString(fingerprint); out.writeString(board); out.writeString(hardware);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualDeviceIdentitySnapshot> CREATOR = new Creator<>() {
        @Override public VirtualDeviceIdentitySnapshot createFromParcel(Parcel in) {
            return new VirtualDeviceIdentitySnapshot(in);
        }
        @Override public VirtualDeviceIdentitySnapshot[] newArray(int size) {
            return new VirtualDeviceIdentitySnapshot[size];
        }
    };

    private static String identifier(String value, String field, int limit) {
        String normalized = ContractChecks.optionalText(value, field, limit).trim();
        if (!normalized.matches("[A-Za-z0-9._:-]*")) {
            throw new IllegalArgumentException(field + " contains unsupported characters");
        }
        return normalized;
    }
    private static String text(String value, String field, int limit) {
        return ContractChecks.optionalText(value, field, limit).trim();
    }
}
