package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.Objects;

/** Aggregate media, communication and archival system-environment profile. */
public final class VirtualMediaCommunicationProfileSnapshot implements Parcelable {
    private final long policyVersion;
    private final long updatedAtMs;
    private final VirtualMediaSessionProfileSnapshot mediaSession;
    private final VirtualMediaRouterProfileSnapshot mediaRouter;
    private final VirtualAudioRoutingProfileSnapshot audioRouting;
    private final VirtualMessagingProfileSnapshot messaging;
    private final VirtualBackupProfileSnapshot backup;
    private final VirtualDropBoxProfileSnapshot dropBox;

    public VirtualMediaCommunicationProfileSnapshot(
            long policyVersion,
            long updatedAtMs,
            VirtualMediaSessionProfileSnapshot mediaSession,
            VirtualMediaRouterProfileSnapshot mediaRouter,
            VirtualAudioRoutingProfileSnapshot audioRouting,
            VirtualMessagingProfileSnapshot messaging,
            VirtualBackupProfileSnapshot backup,
            VirtualDropBoxProfileSnapshot dropBox) {
        if (policyVersion < 1L || updatedAtMs < 0L) {
            throw new IllegalArgumentException("media communication version/time is invalid");
        }
        this.policyVersion = policyVersion;
        this.updatedAtMs = updatedAtMs;
        this.mediaSession = Objects.requireNonNull(mediaSession, "mediaSession");
        this.mediaRouter = Objects.requireNonNull(mediaRouter, "mediaRouter");
        this.audioRouting = Objects.requireNonNull(audioRouting, "audioRouting");
        this.messaging = Objects.requireNonNull(messaging, "messaging");
        this.backup = Objects.requireNonNull(backup, "backup");
        this.dropBox = Objects.requireNonNull(dropBox, "dropBox");
    }

    private VirtualMediaCommunicationProfileSnapshot(Parcel in) {
        this(
                in.readLong(),
                in.readLong(),
                in.readParcelable(VirtualMediaSessionProfileSnapshot.class.getClassLoader()),
                in.readParcelable(VirtualMediaRouterProfileSnapshot.class.getClassLoader()),
                in.readParcelable(VirtualAudioRoutingProfileSnapshot.class.getClassLoader()),
                in.readParcelable(VirtualMessagingProfileSnapshot.class.getClassLoader()),
                in.readParcelable(VirtualBackupProfileSnapshot.class.getClassLoader()),
                in.readParcelable(VirtualDropBoxProfileSnapshot.class.getClassLoader()));
    }

    public long policyVersion() { return policyVersion; }
    public long updatedAtMs() { return updatedAtMs; }
    public VirtualMediaSessionProfileSnapshot mediaSession() { return mediaSession; }
    public VirtualMediaRouterProfileSnapshot mediaRouter() { return mediaRouter; }
    public VirtualAudioRoutingProfileSnapshot audioRouting() { return audioRouting; }
    public VirtualMessagingProfileSnapshot messaging() { return messaging; }
    public VirtualBackupProfileSnapshot backup() { return backup; }
    public VirtualDropBoxProfileSnapshot dropBox() { return dropBox; }

    public VirtualMediaCommunicationProfileSnapshot withVersion(long version, long updatedAt) {
        return new VirtualMediaCommunicationProfileSnapshot(
                version,
                updatedAt,
                mediaSession,
                mediaRouter,
                audioRouting,
                messaging,
                backup,
                dropBox);
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeLong(policyVersion);
        out.writeLong(updatedAtMs);
        out.writeParcelable(mediaSession, flags);
        out.writeParcelable(mediaRouter, flags);
        out.writeParcelable(audioRouting, flags);
        out.writeParcelable(messaging, flags);
        out.writeParcelable(backup, flags);
        out.writeParcelable(dropBox, flags);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<VirtualMediaCommunicationProfileSnapshot> CREATOR = new Creator<>() {
        @Override
        public VirtualMediaCommunicationProfileSnapshot createFromParcel(Parcel in) {
            return new VirtualMediaCommunicationProfileSnapshot(in);
        }

        @Override
        public VirtualMediaCommunicationProfileSnapshot[] newArray(int size) {
            return new VirtualMediaCommunicationProfileSnapshot[size];
        }
    };
}
