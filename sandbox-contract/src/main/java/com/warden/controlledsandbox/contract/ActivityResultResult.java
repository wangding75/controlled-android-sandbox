package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.List;

/** Typed response for Activity Result operations. */
public final class ActivityResultResult implements Parcelable {
    public static final Creator<ActivityResultResult> CREATOR = new Creator<>() {
        @Override public ActivityResultResult createFromParcel(Parcel source) {
            return new ActivityResultResult(source.readInt(), source.readString(), source.readInt() != 0,
                    (SandboxError) source.readParcelable(SandboxError.class.getClassLoader()),
                    source.readString(), source.readInt() != 0, source.readInt(),
                    source.createTypedArrayList(ActivityResultSnapshot.CREATOR));
        }
        @Override public ActivityResultResult[] newArray(int size) {
            return new ActivityResultResult[size];
        }
    };

    private final int protocolVersion;
    private final String requestId;
    private final boolean successful;
    private final SandboxError error;
    private final String operation;
    private final boolean changed;
    private final int assignedRequestCode;
    private final List<ActivityResultSnapshot> results;

    private ActivityResultResult(
            int protocolVersion, String requestId, boolean successful, SandboxError error,
            String operation, boolean changed, int assignedRequestCode,
            List<ActivityResultSnapshot> results) {
        if (protocolVersion <= 0 || (successful ? error != null : error == null)) {
            throw new IllegalArgumentException("invalid Activity result response");
        }
        this.protocolVersion = protocolVersion;
        this.requestId = ContractChecks.requiredText(requestId, "requestId", 128);
        this.successful = successful;
        this.error = error;
        this.operation = ContractChecks.optionalText(operation, "operation", 32);
        this.changed = changed;
        if (assignedRequestCode < -1 || assignedRequestCode > 0xffff) {
            throw new IllegalArgumentException("invalid assigned request code");
        }
        this.assignedRequestCode = assignedRequestCode;
        this.results = List.copyOf(results == null ? List.of() : results);
        if (this.results.size() > 128) throw new IllegalArgumentException("too many Activity results");
    }

    public static ActivityResultResult success(
            int protocolVersion, String requestId, String operation, boolean changed,
            int assignedRequestCode, List<ActivityResultSnapshot> results) {
        return new ActivityResultResult(protocolVersion, requestId, true, null, operation,
                changed, assignedRequestCode, results);
    }

    public static ActivityResultResult failure(
            int protocolVersion, String requestId, SandboxError error) {
        return new ActivityResultResult(protocolVersion, requestId, false, error, "", false, -1, List.of());
    }

    public int protocolVersion() { return protocolVersion; }
    public String requestId() { return requestId; }
    public boolean successful() { return successful; }
    public SandboxError error() { return error; }
    public String operation() { return operation; }
    public boolean changed() { return changed; }
    public int assignedRequestCode() { return assignedRequestCode; }
    public List<ActivityResultSnapshot> results() { return results; }

    @Override public int describeContents() {
        int value = error == null ? 0 : error.describeContents();
        for (ActivityResultSnapshot result : results) value |= result.describeContents();
        return value;
    }
    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(protocolVersion); dest.writeString(requestId); dest.writeInt(successful ? 1 : 0);
        dest.writeParcelable(error, flags); dest.writeString(operation); dest.writeInt(changed ? 1 : 0);
        dest.writeInt(assignedRequestCode); dest.writeTypedList(results);
    }
}
