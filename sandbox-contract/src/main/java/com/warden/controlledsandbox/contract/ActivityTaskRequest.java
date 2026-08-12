package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Typed, versioned request for Broker-owned virtual task queries and mutations. */
public final class ActivityTaskRequest implements Parcelable {
    public static final String QUERY_RUNNING = "QUERY_RUNNING";
    public static final String QUERY_RECENT = "QUERY_RECENT";
    public static final String MOVE_TO_FRONT = "MOVE_TO_FRONT";
    public static final String MOVE_TO_BACK = "MOVE_TO_BACK";
    public static final String REMOVE_TASK = "REMOVE_TASK";
    public static final String FINISH_AFFINITY = "FINISH_AFFINITY";
    public static final String FINISH_AND_REMOVE_TASK = "FINISH_AND_REMOVE_TASK";
    public static final String QUERY_ACTIVITY_ROOT = "QUERY_ACTIVITY_ROOT";
    public static final String CHECKPOINT_STATUS = "CHECKPOINT_STATUS";

    public static final Creator<ActivityTaskRequest> CREATOR = new Creator<>() {
        @Override public ActivityTaskRequest createFromParcel(Parcel source) {
            return new ActivityTaskRequest(
                    source.readInt(), source.readString(), source.readString(), source.readLong(),
                    source.readInt(), source.readString(), source.readString(), source.readInt(),
                    source.readInt(), source.readString());
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
    private final String activityToken;

    public ActivityTaskRequest(
            int protocolVersion,
            String requestId,
            String sessionId,
            long generation,
            int virtualUserId,
            String packageName,
            String operation,
            int taskId,
            int maxCount,
            String activityToken) {
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
        this.activityToken = ContractChecks.optionalText(activityToken, "activityToken", 128);
        if (requiresTaskId(this.operation) && taskId < 1) {
            throw new IllegalArgumentException("taskId must be positive for task mutation");
        }
        if (requiresActivityToken(this.operation) && this.activityToken.isEmpty()) {
            throw new IllegalArgumentException("activityToken is required for Activity operation");
        }
        if ((QUERY_RUNNING.equals(this.operation) || QUERY_RECENT.equals(this.operation))
                && (maxCount < 1 || maxCount > 100)) {
            throw new IllegalArgumentException("maxCount must be between 1 and 100 for task query");
        }
        if (taskId < 0 || maxCount < 0 || maxCount > 100) {
            throw new IllegalArgumentException("invalid task request bounds");
        }
    }

    /** Compatibility constructor used by M4-T15 stage-1 callers. */
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
        this(protocolVersion, requestId, sessionId, generation, virtualUserId, packageName,
                operation, taskId, maxCount, "");
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
    public String activityToken() { return activityToken; }

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
        dest.writeString(activityToken);
    }

    private static boolean requiresTaskId(String operation) {
        return MOVE_TO_FRONT.equals(operation)
                || MOVE_TO_BACK.equals(operation)
                || REMOVE_TASK.equals(operation);
    }

    private static boolean requiresActivityToken(String operation) {
        return FINISH_AFFINITY.equals(operation)
                || FINISH_AND_REMOVE_TASK.equals(operation)
                || QUERY_ACTIVITY_ROOT.equals(operation);
    }

    private static String requireOperation(String value) {
        String normalized = ContractChecks.requiredText(value, "operation", 64);
        if (!QUERY_RUNNING.equals(normalized) && !QUERY_RECENT.equals(normalized)
                && !MOVE_TO_FRONT.equals(normalized) && !MOVE_TO_BACK.equals(normalized)
                && !REMOVE_TASK.equals(normalized) && !FINISH_AFFINITY.equals(normalized)
                && !FINISH_AND_REMOVE_TASK.equals(normalized)
                && !QUERY_ACTIVITY_ROOT.equals(normalized)
                && !CHECKPOINT_STATUS.equals(normalized)) {
            throw new IllegalArgumentException("unsupported Activity task operation: " + normalized);
        }
        return normalized;
    }
}
