package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Typed source-side SMS and messaging policy. */
public final class VirtualMessagingProfileSnapshot implements Parcelable {
    private final String mode;
    private final int subscriptionId;
    private final String defaultSmsPackage;
    private final boolean allowTextMessages;
    private final boolean allowDataMessages;
    private final boolean allowMultipartMessages;
    private final int maximumMessagesPerWindow;
    private final long quotaWindowMs;
    private final boolean storeSentMessages;

    public VirtualMessagingProfileSnapshot(
            String mode,
            int subscriptionId,
            String defaultSmsPackage,
            boolean allowTextMessages,
            boolean allowDataMessages,
            boolean allowMultipartMessages,
            int maximumMessagesPerWindow,
            long quotaWindowMs,
            boolean storeSentMessages) {
        this.mode = VirtualLocationProfileSnapshot.mode(mode);
        this.subscriptionId = subscriptionId;
        this.defaultSmsPackage = ContractChecks.optionalText(
                defaultSmsPackage, "defaultSmsPackage", 256);
        this.allowTextMessages = allowTextMessages;
        this.allowDataMessages = allowDataMessages;
        this.allowMultipartMessages = allowMultipartMessages;
        this.maximumMessagesPerWindow = maximumMessagesPerWindow;
        this.quotaWindowMs = quotaWindowMs;
        this.storeSentMessages = storeSentMessages;
        if (subscriptionId < -1) {
            throw new IllegalArgumentException("subscriptionId is invalid");
        }
        if (maximumMessagesPerWindow < 0 || maximumMessagesPerWindow > 10000) {
            throw new IllegalArgumentException("maximumMessagesPerWindow is invalid");
        }
        if (quotaWindowMs < 1000L || quotaWindowMs > 86400000L) {
            throw new IllegalArgumentException("quotaWindowMs is invalid");
        }
    }

    private VirtualMessagingProfileSnapshot(Parcel in) {
        this(
                in.readString(),
                in.readInt(),
                in.readString(),
                in.readInt() != 0,
                in.readInt() != 0,
                in.readInt() != 0,
                in.readInt(),
                in.readLong(),
                in.readInt() != 0);
    }

    public String mode() { return mode; }
    public int subscriptionId() { return subscriptionId; }
    public String defaultSmsPackage() { return defaultSmsPackage; }
    public boolean allowTextMessages() { return allowTextMessages; }
    public boolean allowDataMessages() { return allowDataMessages; }
    public boolean allowMultipartMessages() { return allowMultipartMessages; }
    public int maximumMessagesPerWindow() { return maximumMessagesPerWindow; }
    public long quotaWindowMs() { return quotaWindowMs; }
    public boolean storeSentMessages() { return storeSentMessages; }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode);
        out.writeInt(subscriptionId);
        out.writeString(defaultSmsPackage);
        out.writeInt(allowTextMessages ? 1 : 0);
        out.writeInt(allowDataMessages ? 1 : 0);
        out.writeInt(allowMultipartMessages ? 1 : 0);
        out.writeInt(maximumMessagesPerWindow);
        out.writeLong(quotaWindowMs);
        out.writeInt(storeSentMessages ? 1 : 0);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<VirtualMessagingProfileSnapshot> CREATOR = new Creator<>() {
        @Override
        public VirtualMessagingProfileSnapshot createFromParcel(Parcel in) {
            return new VirtualMessagingProfileSnapshot(in);
        }

        @Override
        public VirtualMessagingProfileSnapshot[] newArray(int size) {
            return new VirtualMessagingProfileSnapshot[size];
        }
    };
}
