package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Typed runtime status envelope with either a snapshot or a stable error. */
public final class RuntimeStatusResult implements Parcelable {
    public static final Creator<RuntimeStatusResult> CREATOR = new Creator<>() {
        @Override public RuntimeStatusResult createFromParcel(Parcel source) {
            return new RuntimeStatusResult(
                    source.readInt(), source.readString(), source.readInt() != 0,
                    (SandboxError) source.readParcelable(SandboxError.class.getClassLoader()),
                    source.readString(), source.readString(), source.readString(),
                    (RuntimeStatusSnapshot) source.readParcelable(RuntimeStatusSnapshot.class.getClassLoader()));
        }

        @Override public RuntimeStatusResult[] newArray(int size) {
            return new RuntimeStatusResult[size];
        }
    };

    private final int protocolVersion;
    private final String requestId;
    private final boolean successful;
    private final SandboxError error;
    private final String status;
    private final String capability;
    private final String warning;
    private final RuntimeStatusSnapshot snapshot;

    private RuntimeStatusResult(int protocolVersion, String requestId, boolean successful,
                                SandboxError error, String status, String capability,
                                String warning, RuntimeStatusSnapshot snapshot) {
        if (protocolVersion <= 0) throw new IllegalArgumentException("protocolVersion must be positive");
        if (successful) {
            if (error != null || snapshot == null) {
                throw new IllegalArgumentException("successful result requires a snapshot and no error");
            }
        } else if (error == null || snapshot != null) {
            throw new IllegalArgumentException("failed result requires an error and no snapshot");
        }
        this.protocolVersion = protocolVersion;
        this.requestId = ContractChecks.requiredText(requestId, "requestId", 128);
        this.successful = successful;
        this.error = error;
        this.status = ContractChecks.optionalText(status, "status", 128);
        this.capability = ContractChecks.optionalText(capability, "capability", 512);
        this.warning = ContractChecks.optionalText(warning, "warning", 512);
        this.snapshot = snapshot;
    }

    public static RuntimeStatusResult success(int protocolVersion, String requestId,
                                              String status, String capability, String warning,
                                              RuntimeStatusSnapshot snapshot) {
        return new RuntimeStatusResult(protocolVersion, requestId, true, null,
                status, capability, warning, snapshot);
    }

    public static RuntimeStatusResult failure(int protocolVersion, String requestId, SandboxError error) {
        return new RuntimeStatusResult(protocolVersion, requestId, false, error,
                "FAILED", "", "", null);
    }

    public int protocolVersion() { return protocolVersion; }
    public String requestId() { return requestId; }
    public boolean successful() { return successful; }
    public SandboxError error() { return error; }
    public String status() { return status; }
    public String capability() { return capability; }
    public String warning() { return warning; }
    public RuntimeStatusSnapshot snapshot() { return snapshot; }

    @Override public int describeContents() {
        if (error != null) return error.describeContents();
        return snapshot == null ? 0 : snapshot.describeContents();
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(protocolVersion);
        dest.writeString(requestId);
        dest.writeInt(successful ? 1 : 0);
        dest.writeParcelable(error, flags);
        dest.writeString(status);
        dest.writeString(capability);
        dest.writeString(warning);
        dest.writeParcelable(snapshot, flags);
    }
}
