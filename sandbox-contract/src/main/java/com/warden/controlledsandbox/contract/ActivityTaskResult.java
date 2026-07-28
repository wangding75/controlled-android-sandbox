package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.List;

/** Typed result for virtual task queries, mutations and checkpoint status. */
public final class ActivityTaskResult implements Parcelable {
    public static final Creator<ActivityTaskResult> CREATOR = new Creator<>() {
        @Override public ActivityTaskResult createFromParcel(Parcel source) {
            return new ActivityTaskResult(
                    source.readInt(), source.readString(), source.readInt() != 0,
                    (SandboxError) source.readParcelable(SandboxError.class.getClassLoader()),
                    source.readString(), source.readString(), source.readInt() != 0,
                    source.readString(), source.readInt(), source.readInt(), source.readInt(),
                    source.readInt(), source.readInt(), source.createTypedArrayList(ActivityTaskSnapshot.CREATOR));
        }

        @Override public ActivityTaskResult[] newArray(int size) {
            return new ActivityTaskResult[size];
        }
    };

    private final int protocolVersion;
    private final String requestId;
    private final boolean successful;
    private final SandboxError error;
    private final String status;
    private final String operation;
    private final boolean changed;
    private final String checkpointStatus;
    private final int taskCount;
    private final int activityCount;
    private final int restoredTaskCount;
    private final int restoredActivityCount;
    private final int droppedDeliveryCount;
    private final List<ActivityTaskSnapshot> tasks;

    private ActivityTaskResult(
            int protocolVersion,
            String requestId,
            boolean successful,
            SandboxError error,
            String status,
            String operation,
            boolean changed,
            String checkpointStatus,
            int taskCount,
            int activityCount,
            int restoredTaskCount,
            int restoredActivityCount,
            int droppedDeliveryCount,
            List<ActivityTaskSnapshot> tasks) {
        if (protocolVersion <= 0) throw new IllegalArgumentException("protocolVersion must be positive");
        if (taskCount < 0 || activityCount < 0 || restoredTaskCount < 0
                || restoredActivityCount < 0 || droppedDeliveryCount < 0) {
            throw new IllegalArgumentException("Activity task result counts must be non-negative");
        }
        if (successful ? error != null : error == null) {
            throw new IllegalArgumentException("Activity task result success/error mismatch");
        }
        this.protocolVersion = protocolVersion;
        this.requestId = ContractChecks.requiredText(requestId, "requestId", 128);
        this.successful = successful;
        this.error = error;
        this.status = ContractChecks.optionalText(status, "status", 128);
        this.operation = ContractChecks.optionalText(operation, "operation", 64);
        this.changed = changed;
        this.checkpointStatus = ContractChecks.optionalText(checkpointStatus, "checkpointStatus", 512);
        this.taskCount = taskCount;
        this.activityCount = activityCount;
        this.restoredTaskCount = restoredTaskCount;
        this.restoredActivityCount = restoredActivityCount;
        this.droppedDeliveryCount = droppedDeliveryCount;
        this.tasks = List.copyOf(tasks == null ? List.of() : tasks);
        if (this.tasks.size() > 100) throw new IllegalArgumentException("too many Activity task results");
    }

    public static ActivityTaskResult success(
            int protocolVersion,
            String requestId,
            String operation,
            boolean changed,
            String checkpointStatus,
            int taskCount,
            int activityCount,
            int restoredTaskCount,
            int restoredActivityCount,
            int droppedDeliveryCount,
            List<ActivityTaskSnapshot> tasks) {
        return new ActivityTaskResult(protocolVersion, requestId, true, null,
                "ACTIVITY_TASK_OPERATION_APPLIED", operation, changed, checkpointStatus,
                taskCount, activityCount, restoredTaskCount, restoredActivityCount,
                droppedDeliveryCount, tasks);
    }

    public static ActivityTaskResult failure(
            int protocolVersion,
            String requestId,
            SandboxError error) {
        return new ActivityTaskResult(protocolVersion, requestId, false, error,
                "FAILED", "", false, "", 0, 0, 0, 0, 0, List.of());
    }

    public int protocolVersion() { return protocolVersion; }
    public String requestId() { return requestId; }
    public boolean successful() { return successful; }
    public SandboxError error() { return error; }
    public String status() { return status; }
    public String operation() { return operation; }
    public boolean changed() { return changed; }
    public String checkpointStatus() { return checkpointStatus; }
    public int taskCount() { return taskCount; }
    public int activityCount() { return activityCount; }
    public int restoredTaskCount() { return restoredTaskCount; }
    public int restoredActivityCount() { return restoredActivityCount; }
    public int droppedDeliveryCount() { return droppedDeliveryCount; }
    public List<ActivityTaskSnapshot> tasks() { return tasks; }

    @Override public int describeContents() {
        int result = error == null ? 0 : error.describeContents();
        for (ActivityTaskSnapshot task : tasks) result |= task.describeContents();
        return result;
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(protocolVersion);
        dest.writeString(requestId);
        dest.writeInt(successful ? 1 : 0);
        dest.writeParcelable(error, flags);
        dest.writeString(status);
        dest.writeString(operation);
        dest.writeInt(changed ? 1 : 0);
        dest.writeString(checkpointStatus);
        dest.writeInt(taskCount);
        dest.writeInt(activityCount);
        dest.writeInt(restoredTaskCount);
        dest.writeInt(restoredActivityCount);
        dest.writeInt(droppedDeliveryCount);
        dest.writeTypedList(tasks);
    }
}
