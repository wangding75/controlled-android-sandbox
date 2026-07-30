package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Screen/audio capture projection policy for one guest scope. */
public final class VirtualMediaProjectionProfileSnapshot implements Parcelable {
    private final String mode;
    private final boolean projectionAvailable;
    private final boolean allowScreenCapture;
    private final boolean allowAudioCapture;
    private final boolean requireConsent;
    private final int maximumActiveSessions;
    private final int virtualWidth;
    private final int virtualHeight;
    private final int densityDpi;

    public VirtualMediaProjectionProfileSnapshot(
            String mode, boolean projectionAvailable, boolean allowScreenCapture,
            boolean allowAudioCapture, boolean requireConsent, int maximumActiveSessions,
            int virtualWidth, int virtualHeight, int densityDpi) {
        this.mode = VirtualLocationProfileSnapshot.mode(mode);
        this.projectionAvailable = projectionAvailable;
        this.allowScreenCapture = allowScreenCapture;
        this.allowAudioCapture = allowAudioCapture;
        this.requireConsent = requireConsent;
        if (maximumActiveSessions < 0 || maximumActiveSessions > 16) {
            throw new IllegalArgumentException("maximumActiveSessions must be in [0,16]");
        }
        if (virtualWidth < 1 || virtualWidth > 16384 || virtualHeight < 1 || virtualHeight > 16384) {
            throw new IllegalArgumentException("virtual projection size is invalid");
        }
        if (densityDpi < 72 || densityDpi > 1280) {
            throw new IllegalArgumentException("densityDpi is invalid");
        }
        this.maximumActiveSessions = maximumActiveSessions;
        this.virtualWidth = virtualWidth;
        this.virtualHeight = virtualHeight;
        this.densityDpi = densityDpi;
    }

    private VirtualMediaProjectionProfileSnapshot(Parcel in) {
        this(in.readString(), in.readInt() != 0, in.readInt() != 0, in.readInt() != 0,
                in.readInt() != 0, in.readInt(), in.readInt(), in.readInt(), in.readInt());
    }

    public String mode() { return mode; }
    public boolean projectionAvailable() { return projectionAvailable; }
    public boolean allowScreenCapture() { return allowScreenCapture; }
    public boolean allowAudioCapture() { return allowAudioCapture; }
    public boolean requireConsent() { return requireConsent; }
    public int maximumActiveSessions() { return maximumActiveSessions; }
    public int virtualWidth() { return virtualWidth; }
    public int virtualHeight() { return virtualHeight; }
    public int densityDpi() { return densityDpi; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode);
        out.writeInt(projectionAvailable ? 1 : 0);
        out.writeInt(allowScreenCapture ? 1 : 0);
        out.writeInt(allowAudioCapture ? 1 : 0);
        out.writeInt(requireConsent ? 1 : 0);
        out.writeInt(maximumActiveSessions);
        out.writeInt(virtualWidth);
        out.writeInt(virtualHeight);
        out.writeInt(densityDpi);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<VirtualMediaProjectionProfileSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualMediaProjectionProfileSnapshot createFromParcel(Parcel in) {
            return new VirtualMediaProjectionProfileSnapshot(in);
        }
        @Override public VirtualMediaProjectionProfileSnapshot[] newArray(int size) {
            return new VirtualMediaProjectionProfileSnapshot[size];
        }
    };
}
