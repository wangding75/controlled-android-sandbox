package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Typed source-side audio-routing and focus policy. */
public final class VirtualAudioRoutingProfileSnapshot implements Parcelable {
    private final String mode;
    private final int audioMode;
    private final int ringerMode;
    private final boolean speakerphoneOn;
    private final boolean bluetoothScoOn;
    private final boolean microphoneMuted;
    private final int musicVolume;
    private final int musicVolumeMax;
    private final boolean allowVolumeChanges;
    private final boolean allowAudioFocus;
    private final int maximumFocusOwners;

    public VirtualAudioRoutingProfileSnapshot(
            String mode,
            int audioMode,
            int ringerMode,
            boolean speakerphoneOn,
            boolean bluetoothScoOn,
            boolean microphoneMuted,
            int musicVolume,
            int musicVolumeMax,
            boolean allowVolumeChanges,
            boolean allowAudioFocus,
            int maximumFocusOwners) {
        this.mode = VirtualLocationProfileSnapshot.mode(mode);
        this.audioMode = audioMode;
        this.ringerMode = ringerMode;
        this.speakerphoneOn = speakerphoneOn;
        this.bluetoothScoOn = bluetoothScoOn;
        this.microphoneMuted = microphoneMuted;
        this.musicVolume = musicVolume;
        this.musicVolumeMax = musicVolumeMax;
        this.allowVolumeChanges = allowVolumeChanges;
        this.allowAudioFocus = allowAudioFocus;
        this.maximumFocusOwners = maximumFocusOwners;
        if (musicVolume < 0) {
            throw new IllegalArgumentException("musicVolume is invalid");
        }
        if (musicVolumeMax < 0 || musicVolumeMax > 1000) {
            throw new IllegalArgumentException("musicVolumeMax is invalid");
        }
        if (maximumFocusOwners < 0 || maximumFocusOwners > 128) {
            throw new IllegalArgumentException("maximumFocusOwners is invalid");
        }
        if (musicVolume > musicVolumeMax) {
            throw new IllegalArgumentException("musicVolume exceeds musicVolumeMax");
        }
    }

    private VirtualAudioRoutingProfileSnapshot(Parcel in) {
        this(
                in.readString(),
                in.readInt(),
                in.readInt(),
                in.readInt() != 0,
                in.readInt() != 0,
                in.readInt() != 0,
                in.readInt(),
                in.readInt(),
                in.readInt() != 0,
                in.readInt() != 0,
                in.readInt());
    }

    public String mode() { return mode; }
    public int audioMode() { return audioMode; }
    public int ringerMode() { return ringerMode; }
    public boolean speakerphoneOn() { return speakerphoneOn; }
    public boolean bluetoothScoOn() { return bluetoothScoOn; }
    public boolean microphoneMuted() { return microphoneMuted; }
    public int musicVolume() { return musicVolume; }
    public int musicVolumeMax() { return musicVolumeMax; }
    public boolean allowVolumeChanges() { return allowVolumeChanges; }
    public boolean allowAudioFocus() { return allowAudioFocus; }
    public int maximumFocusOwners() { return maximumFocusOwners; }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode);
        out.writeInt(audioMode);
        out.writeInt(ringerMode);
        out.writeInt(speakerphoneOn ? 1 : 0);
        out.writeInt(bluetoothScoOn ? 1 : 0);
        out.writeInt(microphoneMuted ? 1 : 0);
        out.writeInt(musicVolume);
        out.writeInt(musicVolumeMax);
        out.writeInt(allowVolumeChanges ? 1 : 0);
        out.writeInt(allowAudioFocus ? 1 : 0);
        out.writeInt(maximumFocusOwners);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<VirtualAudioRoutingProfileSnapshot> CREATOR = new Creator<>() {
        @Override
        public VirtualAudioRoutingProfileSnapshot createFromParcel(Parcel in) {
            return new VirtualAudioRoutingProfileSnapshot(in);
        }

        @Override
        public VirtualAudioRoutingProfileSnapshot[] newArray(int size) {
            return new VirtualAudioRoutingProfileSnapshot[size];
        }
    };
}
