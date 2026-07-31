package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.Arrays;

/** Typed read/write policy for the privileged persistent-data-block service. */
public final class VirtualPersistentDataBlockProfileSnapshot implements Parcelable {
    private final String mode;
    private final boolean readable;
    private final boolean writable;
    private final boolean allowWipe;
    private final int maximumDataBytes;
    private final byte[] data;
    private final boolean oemUnlockEnabled;
    private final int flashLockState;
    private final boolean checksumValid;

    public VirtualPersistentDataBlockProfileSnapshot(String mode, boolean readable,
            boolean writable, boolean allowWipe, int maximumDataBytes, byte[] data,
            boolean oemUnlockEnabled, int flashLockState, boolean checksumValid) {
        this.mode = VirtualLocationProfileSnapshot.mode(mode);
        if (maximumDataBytes < 0 || maximumDataBytes > 65536) {
            throw new IllegalArgumentException("maximumDataBytes must be in [0,65536]");
        }
        this.maximumDataBytes = maximumDataBytes;
        byte[] normalized = data == null ? new byte[0] : data.clone();
        if (normalized.length > maximumDataBytes) {
            throw new IllegalArgumentException("persistent data exceeds maximumDataBytes");
        }
        this.data = normalized;
        this.readable = readable;
        this.writable = writable;
        this.allowWipe = allowWipe;
        this.oemUnlockEnabled = oemUnlockEnabled;
        if (flashLockState < 0 || flashLockState > 2) {
            throw new IllegalArgumentException("flashLockState must be in [0,2]");
        }
        this.flashLockState = flashLockState;
        this.checksumValid = checksumValid;
    }

    private VirtualPersistentDataBlockProfileSnapshot(Parcel in) {
        this(in.readString(), in.readInt() != 0, in.readInt() != 0, in.readInt() != 0,
                in.readInt(), in.createByteArray(), in.readInt() != 0, in.readInt(), in.readInt() != 0);
    }

    public String mode() { return mode; }
    public boolean readable() { return readable; }
    public boolean writable() { return writable; }
    public boolean allowWipe() { return allowWipe; }
    public int maximumDataBytes() { return maximumDataBytes; }
    public byte[] data() { return data.clone(); }
    public boolean oemUnlockEnabled() { return oemUnlockEnabled; }
    public int flashLockState() { return flashLockState; }
    public boolean checksumValid() { return checksumValid; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode);
        out.writeInt(readable ? 1 : 0);
        out.writeInt(writable ? 1 : 0);
        out.writeInt(allowWipe ? 1 : 0);
        out.writeInt(maximumDataBytes);
        out.writeByteArray(data);
        out.writeInt(oemUnlockEnabled ? 1 : 0);
        out.writeInt(flashLockState);
        out.writeInt(checksumValid ? 1 : 0);
    }
    @Override public int describeContents() { return 0; }
    @Override public boolean equals(Object other) {
        if (!(other instanceof VirtualPersistentDataBlockProfileSnapshot value)) return false;
        return mode.equals(value.mode) && readable == value.readable && writable == value.writable
                && allowWipe == value.allowWipe && maximumDataBytes == value.maximumDataBytes
                && Arrays.equals(data, value.data) && oemUnlockEnabled == value.oemUnlockEnabled
                && flashLockState == value.flashLockState && checksumValid == value.checksumValid;
    }
    @Override public int hashCode() { return 31 * mode.hashCode() + Arrays.hashCode(data); }
    public static final Creator<VirtualPersistentDataBlockProfileSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualPersistentDataBlockProfileSnapshot createFromParcel(Parcel in) {
            return new VirtualPersistentDataBlockProfileSnapshot(in);
        }
        @Override public VirtualPersistentDataBlockProfileSnapshot[] newArray(int size) {
            return new VirtualPersistentDataBlockProfileSnapshot[size];
        }
    };
}
