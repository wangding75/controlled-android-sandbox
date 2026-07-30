package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.List;

/** Durable virtual shortcut record. */
public final class VirtualShortcutSnapshot implements Parcelable {
    private final String id;
    private final String activityClass;
    private final String shortLabel;
    private final String longLabel;
    private final String disabledMessage;
    private final List<String> intentUris;
    private final int rank;
    private final boolean enabled;
    private final boolean dynamic;
    private final boolean pinned;
    private final boolean manifest;
    private final boolean longLived;
    private final long lastChangedMs;
    private final int usageCount;

    public VirtualShortcutSnapshot(String id, String activityClass, String shortLabel, String longLabel,
            String disabledMessage, List<String> intentUris, int rank, boolean enabled, boolean dynamic,
            boolean pinned, boolean manifest, boolean longLived, long lastChangedMs, int usageCount) {
        this.id = ContractChecks.requiredText(id, "shortcutId", 128);
        this.activityClass = ContractChecks.optionalText(activityClass, "shortcutActivity", 255);
        this.shortLabel = ContractChecks.requiredText(shortLabel, "shortcutShortLabel", 256);
        this.longLabel = ContractChecks.optionalText(longLabel, "shortcutLongLabel", 1024);
        this.disabledMessage = ContractChecks.optionalText(disabledMessage, "shortcutDisabledMessage", 1024);
        this.intentUris = VirtualUserProfileSnapshot.strings(intentUris, "shortcutIntentUris", 16, 4096);
        if (rank < 0 || rank > 10_000) throw new IllegalArgumentException("shortcut rank is invalid");
        this.rank = rank; this.enabled = enabled; this.dynamic = dynamic; this.pinned = pinned;
        this.manifest = manifest; this.longLived = longLived;
        this.lastChangedMs = ContractChecks.nonNegative(lastChangedMs, "shortcutLastChangedMs");
        this.usageCount = ContractChecks.nonNegative(usageCount, "shortcutUsageCount");
        if (!dynamic && !pinned && !manifest) throw new IllegalArgumentException("shortcut has no source");
    }
    private VirtualShortcutSnapshot(Parcel in) {
        this(in.readString(), in.readString(), in.readString(), in.readString(), in.readString(),
                in.createStringArrayList(), in.readInt(), in.readInt() != 0, in.readInt() != 0,
                in.readInt() != 0, in.readInt() != 0, in.readInt() != 0, in.readLong(), in.readInt());
    }
    public String id() { return id; }
    public String activityClass() { return activityClass; }
    public String shortLabel() { return shortLabel; }
    public String longLabel() { return longLabel; }
    public String disabledMessage() { return disabledMessage; }
    public List<String> intentUris() { return intentUris; }
    public int rank() { return rank; }
    public boolean enabled() { return enabled; }
    public boolean dynamic() { return dynamic; }
    public boolean pinned() { return pinned; }
    public boolean manifest() { return manifest; }
    public boolean longLived() { return longLived; }
    public long lastChangedMs() { return lastChangedMs; }
    public int usageCount() { return usageCount; }
    public VirtualShortcutSnapshot withUsage(int count, long changedAt) {
        return new VirtualShortcutSnapshot(id, activityClass, shortLabel, longLabel, disabledMessage,
                intentUris, rank, enabled, dynamic, pinned, manifest, longLived, changedAt, count);
    }
    public VirtualShortcutSnapshot withEnabled(boolean value, String message, long changedAt) {
        return new VirtualShortcutSnapshot(id, activityClass, shortLabel, longLabel, message,
                intentUris, rank, value, dynamic, pinned, manifest, longLived, changedAt, usageCount);
    }
    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(id); out.writeString(activityClass); out.writeString(shortLabel); out.writeString(longLabel);
        out.writeString(disabledMessage); out.writeStringList(intentUris); out.writeInt(rank);
        out.writeInt(enabled ? 1 : 0); out.writeInt(dynamic ? 1 : 0); out.writeInt(pinned ? 1 : 0);
        out.writeInt(manifest ? 1 : 0); out.writeInt(longLived ? 1 : 0); out.writeLong(lastChangedMs);
        out.writeInt(usageCount);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualShortcutSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualShortcutSnapshot createFromParcel(Parcel in) { return new VirtualShortcutSnapshot(in); }
        @Override public VirtualShortcutSnapshot[] newArray(int size) { return new VirtualShortcutSnapshot[size]; }
    };
}
