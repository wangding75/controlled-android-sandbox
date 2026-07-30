package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Window, ActivityClient, input/IME and display profile for one package/user scope. */
public final class VirtualInteractionProfileSnapshot implements Parcelable {
    private final long policyVersion;
    private final long updatedAtMs;
    private final VirtualWindowPolicySnapshot window;
    private final VirtualInputMethodProfileSnapshot inputMethod;
    private final VirtualDisplayProfileSnapshot display;

    public VirtualInteractionProfileSnapshot(long policyVersion, long updatedAtMs,
            VirtualWindowPolicySnapshot window,
            VirtualInputMethodProfileSnapshot inputMethod,
            VirtualDisplayProfileSnapshot display) {
        if (policyVersion < 1L || updatedAtMs < 0L) {
            throw new IllegalArgumentException("interaction profile version/time is invalid");
        }
        this.policyVersion = policyVersion;
        this.updatedAtMs = updatedAtMs;
        this.window = java.util.Objects.requireNonNull(window, "window");
        this.inputMethod = java.util.Objects.requireNonNull(inputMethod, "inputMethod");
        this.display = java.util.Objects.requireNonNull(display, "display");
    }

    private VirtualInteractionProfileSnapshot(Parcel in) {
        this(in.readLong(), in.readLong(),
                in.readParcelable(VirtualWindowPolicySnapshot.class.getClassLoader()),
                in.readParcelable(VirtualInputMethodProfileSnapshot.class.getClassLoader()),
                in.readParcelable(VirtualDisplayProfileSnapshot.class.getClassLoader()));
    }

    public long policyVersion() { return policyVersion; }
    public long updatedAtMs() { return updatedAtMs; }
    public VirtualWindowPolicySnapshot window() { return window; }
    public VirtualInputMethodProfileSnapshot inputMethod() { return inputMethod; }
    public VirtualDisplayProfileSnapshot display() { return display; }
    public VirtualInteractionProfileSnapshot withVersion(long version, long updatedAt) {
        return new VirtualInteractionProfileSnapshot(version, updatedAt, window, inputMethod, display);
    }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeLong(policyVersion); out.writeLong(updatedAtMs);
        out.writeParcelable(window, flags); out.writeParcelable(inputMethod, flags);
        out.writeParcelable(display, flags);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualInteractionProfileSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualInteractionProfileSnapshot createFromParcel(Parcel in) {
            return new VirtualInteractionProfileSnapshot(in);
        }
        @Override public VirtualInteractionProfileSnapshot[] newArray(int size) {
            return new VirtualInteractionProfileSnapshot[size];
        }
    };
}
