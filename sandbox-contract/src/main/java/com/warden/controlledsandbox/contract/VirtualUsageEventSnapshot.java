package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Bounded virtual UsageStats event. */
public final class VirtualUsageEventSnapshot implements Parcelable {
    private final long timestampMs;
    private final int eventType;
    private final String packageName;
    private final String className;
    private final String taskRootPackage;
    private final String configuration;
    private final String shortcutId;
    private final int instanceId;

    public VirtualUsageEventSnapshot(long timestampMs, int eventType, String packageName,
            String className, String taskRootPackage, String configuration,
            String shortcutId, int instanceId) {
        this.timestampMs = ContractChecks.nonNegative(timestampMs, "usageTimestampMs");
        if (eventType < 0 || eventType > 10_000) throw new IllegalArgumentException("usageEventType is invalid");
        this.eventType = eventType;
        this.packageName = ContractChecks.requiredText(packageName, "usagePackageName", 255);
        this.className = ContractChecks.optionalText(className, "usageClassName", 255);
        this.taskRootPackage = ContractChecks.optionalText(taskRootPackage, "taskRootPackage", 255);
        this.configuration = ContractChecks.optionalText(configuration, "usageConfiguration", 2048);
        this.shortcutId = ContractChecks.optionalText(shortcutId, "usageShortcutId", 128);
        this.instanceId = ContractChecks.nonNegative(instanceId, "usageInstanceId");
    }
    private VirtualUsageEventSnapshot(Parcel in) {
        this(in.readLong(), in.readInt(), in.readString(), in.readString(), in.readString(),
                in.readString(), in.readString(), in.readInt());
    }
    public long timestampMs() { return timestampMs; }
    public int eventType() { return eventType; }
    public String packageName() { return packageName; }
    public String className() { return className; }
    public String taskRootPackage() { return taskRootPackage; }
    public String configuration() { return configuration; }
    public String shortcutId() { return shortcutId; }
    public int instanceId() { return instanceId; }
    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeLong(timestampMs); out.writeInt(eventType); out.writeString(packageName);
        out.writeString(className); out.writeString(taskRootPackage); out.writeString(configuration);
        out.writeString(shortcutId); out.writeInt(instanceId);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualUsageEventSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualUsageEventSnapshot createFromParcel(Parcel in) { return new VirtualUsageEventSnapshot(in); }
        @Override public VirtualUsageEventSnapshot[] newArray(int size) { return new VirtualUsageEventSnapshot[size]; }
    };
}
