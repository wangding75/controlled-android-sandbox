package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Typed source-side MediaSession policy. */
public final class VirtualMediaSessionProfileSnapshot implements Parcelable {
    private final String mode;
    private final boolean active;
    private final boolean allowSessionCreation;
    private final boolean allowTransportControls;
    private final int maximumSessions;
    private final String playbackState;
    private final long playbackPositionMs;
    private final String title;
    private final String artist;

    public VirtualMediaSessionProfileSnapshot(
            String mode,
            boolean active,
            boolean allowSessionCreation,
            boolean allowTransportControls,
            int maximumSessions,
            String playbackState,
            long playbackPositionMs,
            String title,
            String artist) {
        this.mode = VirtualLocationProfileSnapshot.mode(mode);
        this.active = active;
        this.allowSessionCreation = allowSessionCreation;
        this.allowTransportControls = allowTransportControls;
        this.maximumSessions = maximumSessions;
        this.playbackState = ContractChecks.optionalText(playbackState, "playbackState", 256);
        this.playbackPositionMs = playbackPositionMs;
        this.title = ContractChecks.optionalText(title, "title", 256);
        this.artist = ContractChecks.optionalText(artist, "artist", 256);
        if (maximumSessions < 0 || maximumSessions > 128) {
            throw new IllegalArgumentException("maximumSessions is invalid");
        }
        if (playbackPositionMs < 0L) {
            throw new IllegalArgumentException("playbackPositionMs is invalid");
        }
    }

    private VirtualMediaSessionProfileSnapshot(Parcel in) {
        this(
                in.readString(),
                in.readInt() != 0,
                in.readInt() != 0,
                in.readInt() != 0,
                in.readInt(),
                in.readString(),
                in.readLong(),
                in.readString(),
                in.readString());
    }

    public String mode() { return mode; }
    public boolean active() { return active; }
    public boolean allowSessionCreation() { return allowSessionCreation; }
    public boolean allowTransportControls() { return allowTransportControls; }
    public int maximumSessions() { return maximumSessions; }
    public String playbackState() { return playbackState; }
    public long playbackPositionMs() { return playbackPositionMs; }
    public String title() { return title; }
    public String artist() { return artist; }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode);
        out.writeInt(active ? 1 : 0);
        out.writeInt(allowSessionCreation ? 1 : 0);
        out.writeInt(allowTransportControls ? 1 : 0);
        out.writeInt(maximumSessions);
        out.writeString(playbackState);
        out.writeLong(playbackPositionMs);
        out.writeString(title);
        out.writeString(artist);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<VirtualMediaSessionProfileSnapshot> CREATOR = new Creator<>() {
        @Override
        public VirtualMediaSessionProfileSnapshot createFromParcel(Parcel in) {
            return new VirtualMediaSessionProfileSnapshot(in);
        }

        @Override
        public VirtualMediaSessionProfileSnapshot[] newArray(int size) {
            return new VirtualMediaSessionProfileSnapshot[size];
        }
    };
}
