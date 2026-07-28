package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Typed, versioned request for Broker-owned virtual task queries and mutations. */
public final class ActivityTaskRequest implements Parcelable {
    public static final String QUERY_RUNNING = "QUERY_RUNNING";
    public static final String QUERY_RECENT = "QUERY_RECENT";
    public static final String MOVE_TO_FRONT = "MOVE_TO_FRONT";
    public static final String REMOVE_TASK = "REMOVE_TASK";
    public static final String CHECKPOINT_STATUS = "CHECKPOINT_STATUS";

    public static final Creator<ActivityTaskRequest> CREATOR = new Creator<>() {
        @Override public ActivityTaskRequest createFromParcel(Parcel source) {
            return new ActivityTaskRequest(
                    source.readInt(), source.readString(), source.readString(), source.readLong(),
                    source.readInt(), source.readString(), source.readString(), source.readInt(),
                    source.readInt());
        }

        @Override public ActivityTaskRequest[] newArray(int size) {
            return new ActivityTaskRequest[size];
        }
    };

    private final int protocolVersion;
    private final String requestId;
    private final String sessionId;
    private final long generation;
    private final int virtualUserId;
    private final String packageName;
    private final String operation;
    private final int taskId;
    private final int maxCount;

    public ActivityTaskRequest(
            int protocolVersion,
            String requestId,
            String sessionId,
            long generation,
            int virtualUserId,
            String packageName,
            String operation,
            int taskId,
            int maxCount) {
        if (protocolVersion <= 0) throw new IllegalArgumentException("protocolVersion must be positive");
        if (generation < 1) throw new IllegalArgumentException("generation must be positive");
        if (virtualUserId < 0) throw new IllegalArgumentException("virtualUserId must be non-negative");
        this.protocolVersion = protocolVersion;
        this.requestId = ContractChecks.requiredText(requestId, "requestId", 128);
        this.sessionId = ContractChecks.requiredText(sessionId, "sessionId", 128);
        this.generation = generation;
        this.virtualUserId = virtualUserId;
        this.packageName = ContractChecks.requiredText(packageName, "packageName", 255);
        this.operation = requireOperation(operation);
        this.taskId = taskId;
        this.maxCount = maxCount;
        if ((MOVE_TO_FRONT.equals(this.operation) || REMOVE_TASK.equals(this.operation)) && taskId < 1) {
            throw new IllegalArgumentException("taskId must be positive for task mutation");
        }
        if ((QUERY_RUNNING.equals(this.operation) || QUERY_RECENT.equals(this.operation))
                && (maxCount < 1 || maxCount > 100)) {
            throw new IllegalArgumentException("maxCount must be between 1 and 100 for task query");
        }
        if (taskId < 0 || maxCount < 0 || maxCount > 100) {
            throw new IllegalArgumentException("invalid task request bounds");
        }
    }

    public int protocolVersion() { return protocolVersion; }
    public String requestId() { return requestId; }
    public String sessionId() { return sessionId; }
    public long generation() { return generation; }
    public int virtualUserId() { return virtualUserId; }
    public String packageName() { return packageName; }
    public String operation() { return operation; }
    public int taskId() { return taskId; }
    public int maxCount() { return maxCount; }

    @Override public int describeContents() { return 0; }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(protocolVersion);
        dest.writeString(requestId);
        dest.writeString(sessionId);
        dest.writeLong(generation);
        dest.writeInt(virtualUserId);
        dest.writeString(packageName);
        dest.writeString(operation);
        dest.writeInt(taskId);
        dest.writeInt(maxCount);
    }

    private static String requireOperation(String value) {
        String normalized = ContractChecks.requiredText(value, "operation", 64);
        if (!QUERY_RUNNING.equals(normalized) && !QUERY_RECENT.equals(normalized)
                && !MOVE_TO_FRONT.equals(normalized) && !REMOVE_TASK.equals(normalized)
                && !CHECKPOINT_STATUS.equals(normalized)) {
            throw new IllegalArgumentException("unsupported Activity task operation: " + normalized);
        }
        return normalized;
    }
}
