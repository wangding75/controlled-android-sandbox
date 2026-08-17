package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.List;

/** Typed bounded projection of one virtual running or recent task. */
public final class ActivityTaskSnapshot implements Parcelable {
    public static final Creator<ActivityTaskSnapshot> CREATOR = new Creator<>() {
        @Override public ActivityTaskSnapshot createFromParcel(Parcel source) {
            return new ActivityTaskSnapshot(
                    source.readInt(), source.readInt(), source.readString(), source.readString(),
                    source.readString(), source.readInt() != 0, source.readString(), source.readString(),
                    source.readInt() != 0, source.readInt() != 0, source.readInt() != 0,
                    source.readInt(), source.readString(), source.readString(),
                    source.readLong(), source.readLong(), source.readInt(), source.readString(), source.readString(),
                    source.readString(), source.createStringArrayList(), source.readLong());
        }

        @Override public ActivityTaskSnapshot[] newArray(int size) {
            return new ActivityTaskSnapshot[size];
        }
    };

    private final int taskId;
    private final int virtualUserId;
    private final String packageName;
    private final String packageRevision;
    private final String affinity;
    private final boolean documentTask;
    private final String documentLaunchMode;
    private final String documentKey;
    private final boolean active;
    private final boolean excludedFromRecents;
    private final boolean retainInRecents;
    private final int activityCount;
    private final String baseComponentName;
    private final String topComponentName;
    private final long lastActiveSequence;
    private final long moveToFrontCount;
    private final int baseIntentFlags;
    private final String baseIntentAction;
    private final String baseIntentDataUri;
    private final String baseIntentMimeType;
    private final List<String> baseIntentCategories;
    private final long lastActiveTimeMillis;

    public ActivityTaskSnapshot(
            int taskId,
            int virtualUserId,
            String packageName,
            String packageRevision,
            String affinity,
            boolean documentTask,
            String documentLaunchMode,
            String documentKey,
            boolean active,
            boolean excludedFromRecents,
            boolean retainInRecents,
            int activityCount,
            String baseComponentName,
            String topComponentName,
            long lastActiveSequence,
            long moveToFrontCount,
            int baseIntentFlags,
            String baseIntentAction,
            String baseIntentDataUri,
            String baseIntentMimeType,
            List<String> baseIntentCategories,
            long lastActiveTimeMillis) {
        if (taskId < 1 || virtualUserId < 0 || activityCount < 0
                || lastActiveSequence < 0 || moveToFrontCount < 0 || lastActiveTimeMillis < 0) {
            throw new IllegalArgumentException("invalid Activity task snapshot");
        }
        if (active && activityCount < 1) {
            throw new IllegalArgumentException("active task must contain an Activity");
        }
        this.taskId = taskId;
        this.virtualUserId = virtualUserId;
        this.packageName = ContractChecks.requiredText(packageName, "packageName", 255);
        this.packageRevision = ContractChecks.requiredText(
                packageRevision, "packageRevision", 1024);
        this.affinity = ContractChecks.optionalText(affinity, "affinity", 255);
        this.documentTask = documentTask;
        this.documentLaunchMode = ContractChecks.optionalText(
                documentLaunchMode, "documentLaunchMode", 64);
        this.documentKey = ContractChecks.optionalText(documentKey, "documentKey", 2048);
        this.active = active;
        this.excludedFromRecents = excludedFromRecents;
        this.retainInRecents = retainInRecents;
        this.activityCount = activityCount;
        this.baseComponentName = ContractChecks.optionalText(baseComponentName, "baseComponentName", 512);
        this.topComponentName = ContractChecks.optionalText(topComponentName, "topComponentName", 512);
        this.lastActiveSequence = lastActiveSequence;
        this.moveToFrontCount = moveToFrontCount;
        this.baseIntentFlags = baseIntentFlags;
        this.baseIntentAction = ContractChecks.optionalText(baseIntentAction, "baseIntentAction", 512);
        this.baseIntentDataUri = ContractChecks.optionalText(baseIntentDataUri, "baseIntentDataUri", 4096);
        this.baseIntentMimeType = ContractChecks.optionalText(baseIntentMimeType, "baseIntentMimeType", 255);
        this.baseIntentCategories = baseIntentCategories == null
                ? List.of() : List.copyOf(baseIntentCategories);
        this.lastActiveTimeMillis = lastActiveTimeMillis;
        if (this.baseIntentCategories.size() > 64) {
            throw new IllegalArgumentException("baseIntentCategories exceeds 64 values");
        }
        if (!documentTask && (!this.documentKey.isEmpty()
                || !"NONE".equals(this.documentLaunchMode))) {
            throw new IllegalArgumentException("non-document task cannot expose document metadata");
        }
    }

    /** Compatibility constructor for callers compiled before task time was projected. */
    public ActivityTaskSnapshot(
            int taskId,
            int virtualUserId,
            String packageName,
            String packageRevision,
            String affinity,
            boolean documentTask,
            String documentLaunchMode,
            String documentKey,
            boolean active,
            boolean excludedFromRecents,
            boolean retainInRecents,
            int activityCount,
            String baseComponentName,
            String topComponentName,
            long lastActiveSequence,
            long moveToFrontCount,
            int baseIntentFlags,
            String baseIntentAction,
            String baseIntentDataUri,
            String baseIntentMimeType,
            List<String> baseIntentCategories) {
        this(taskId, virtualUserId, packageName, packageRevision, affinity, documentTask,
                documentLaunchMode, documentKey, active, excludedFromRecents,
                retainInRecents, activityCount, baseComponentName, topComponentName,
                lastActiveSequence, moveToFrontCount, baseIntentFlags, baseIntentAction,
                baseIntentDataUri, baseIntentMimeType, baseIntentCategories, 0L);
    }

    /** Compatibility constructor for schema-1 task projections. */
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
        this(taskId, virtualUserId, packageName, "legacy", affinity, documentTask,
                documentTask ? "ALWAYS" : "NONE", "", active, excludedFromRecents,
                retainInRecents, activityCount, baseComponentName, topComponentName,
                lastActiveSequence, moveToFrontCount, 0, "", "", "", List.of(), 0L);
    }

    /** Compatibility constructor for the schema-2 task projection without base Intent metadata. */
    public ActivityTaskSnapshot(
            int taskId,
            int virtualUserId,
            String packageName,
            String packageRevision,
            String affinity,
            boolean documentTask,
            String documentLaunchMode,
            String documentKey,
            boolean active,
            boolean excludedFromRecents,
            boolean retainInRecents,
            int activityCount,
            String baseComponentName,
            String topComponentName,
            long lastActiveSequence,
            long moveToFrontCount) {
        this(taskId, virtualUserId, packageName, packageRevision, affinity, documentTask,
                documentLaunchMode, documentKey, active, excludedFromRecents,
                retainInRecents, activityCount, baseComponentName, topComponentName,
                lastActiveSequence, moveToFrontCount, 0, "", "", "", List.of(), 0L);
    }

    public int taskId() { return taskId; }
    public int virtualUserId() { return virtualUserId; }
    public String packageName() { return packageName; }
    public String packageRevision() { return packageRevision; }
    public String affinity() { return affinity; }
    public boolean documentTask() { return documentTask; }
    public String documentLaunchMode() { return documentLaunchMode; }
    public String documentKey() { return documentKey; }
    public boolean active() { return active; }
    public boolean excludedFromRecents() { return excludedFromRecents; }
    public boolean retainInRecents() { return retainInRecents; }
    public int activityCount() { return activityCount; }
    public String baseComponentName() { return baseComponentName; }
    public String topComponentName() { return topComponentName; }
    public long lastActiveSequence() { return lastActiveSequence; }
    public long moveToFrontCount() { return moveToFrontCount; }
    public int baseIntentFlags() { return baseIntentFlags; }
    public String baseIntentAction() { return baseIntentAction; }
    public String baseIntentDataUri() { return baseIntentDataUri; }
    public String baseIntentMimeType() { return baseIntentMimeType; }
    public List<String> baseIntentCategories() { return baseIntentCategories; }
    public long lastActiveTimeMillis() { return lastActiveTimeMillis; }

    @Override public int describeContents() { return 0; }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(taskId);
        dest.writeInt(virtualUserId);
        dest.writeString(packageName);
        dest.writeString(packageRevision);
        dest.writeString(affinity);
        dest.writeInt(documentTask ? 1 : 0);
        dest.writeString(documentLaunchMode);
        dest.writeString(documentKey);
        dest.writeInt(active ? 1 : 0);
        dest.writeInt(excludedFromRecents ? 1 : 0);
        dest.writeInt(retainInRecents ? 1 : 0);
        dest.writeInt(activityCount);
        dest.writeString(baseComponentName);
        dest.writeString(topComponentName);
        dest.writeLong(lastActiveSequence);
        dest.writeLong(moveToFrontCount);
        dest.writeInt(baseIntentFlags);
        dest.writeString(baseIntentAction);
        dest.writeString(baseIntentDataUri);
        dest.writeString(baseIntentMimeType);
        dest.writeStringList(baseIntentCategories);
        dest.writeLong(lastActiveTimeMillis);
    }
}
