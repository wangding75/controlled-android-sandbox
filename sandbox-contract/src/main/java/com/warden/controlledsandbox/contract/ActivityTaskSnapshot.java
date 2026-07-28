package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Typed bounded projection of one virtual running or recent task. */
public final class ActivityTaskSnapshot implements Parcelable {
    public static final Creator<ActivityTaskSnapshot> CREATOR = new Creator<>() {
        @Override public ActivityTaskSnapshot createFromParcel(Parcel source) {
            return new ActivityTaskSnapshot(
                    source.readInt(), source.readInt(), source.readString(), source.readString(),
                    source.readInt() != 0, source.readInt() != 0, source.readInt() != 0,
                    source.readInt() != 0, source.readInt(), source.readString(), source.readString(),
                    source.readLong(), source.readLong());
        }

        @Override public ActivityTaskSnapshot[] newArray(int size) {
            return new ActivityTaskSnapshot[size];
        }
    };

    private final int taskId;
    private final int virtualUserId;
    private final String packageName;
    private final String affinity;
    private final boolean documentTask;
    private final boolean active;
    private final boolean excludedFromRecents;
    private final boolean retainInRecents;
    private final int activityCount;
    private final String baseComponentName;
    private final String topComponentName;
    private final long lastActiveSequence;
    private final long moveToFrontCount;

    public ActivityTaskSnapshot(
            int taskId,
            int virtualUserId,
            String packageName,
            String affinity,
            boolean documentTask,
            boolean active,
            boolean excludedFromRecents,
            boolean retainInRecents,
            int activityCount,
            String baseComponentName,
            String topComponentName,
            long lastActiveSequence,
            long moveToFrontCount) {
        if (taskId < 1 || virtualUserId < 0 || activityCount < 0
                || lastActiveSequence < 0 || moveToFrontCount < 0) {
            throw new IllegalArgumentException("invalid Activity task snapshot");
        }
        if (active && activityCount < 1) {
            throw new IllegalArgumentException("active task must contain an Activity");
        }
        this.taskId = taskId;
        this.virtualUserId = virtualUserId;
        this.packageName = ContractChecks.requiredText(packageName, "packageName", 255);
        this.affinity = ContractChecks.optionalText(affinity, "affinity", 255);
        this.documentTask = documentTask;
        this.active = active;
        this.excludedFromRecents = excludedFromRecents;
        this.retainInRecents = retainInRecents;
        this.activityCount = activityCount;
        this.baseComponentName = ContractChecks.optionalText(baseComponentName, "baseComponentName", 512);
        this.topComponentName = ContractChecks.optionalText(topComponentName, "topComponentName", 512);
        this.lastActiveSequence = lastActiveSequence;
        this.moveToFrontCount = moveToFrontCount;
    }

    public int taskId() { return taskId; }
    public int virtualUserId() { return virtualUserId; }
    public String packageName() { return packageName; }
    public String affinity() { return affinity; }
    public boolean documentTask() { return documentTask; }
    public boolean active() { return active; }
    public boolean excludedFromRecents() { return excludedFromRecents; }
    public boolean retainInRecents() { return retainInRecents; }
    public int activityCount() { return activityCount; }
    public String baseComponentName() { return baseComponentName; }
    public String topComponentName() { return topComponentName; }
    public long lastActiveSequence() { return lastActiveSequence; }
    public long moveToFrontCount() { return moveToFrontCount; }

    @Override public int describeContents() { return 0; }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(taskId);
        dest.writeInt(virtualUserId);
        dest.writeString(packageName);
        dest.writeString(affinity);
        dest.writeInt(documentTask ? 1 : 0);
        dest.writeInt(active ? 1 : 0);
        dest.writeInt(excludedFromRecents ? 1 : 0);
        dest.writeInt(retainInRecents ? 1 : 0);
        dest.writeInt(activityCount);
        dest.writeString(baseComponentName);
        dest.writeString(topComponentName);
        dest.writeLong(lastActiveSequence);
        dest.writeLong(moveToFrontCount);
    }
}
