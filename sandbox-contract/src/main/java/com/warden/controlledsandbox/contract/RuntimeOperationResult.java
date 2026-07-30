package com.warden.controlledsandbox.contract;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/** Typed result envelope for runtime operations with stable status and error semantics. */
public final class RuntimeOperationResult implements Parcelable {
    public static final Creator<RuntimeOperationResult> CREATOR = new Creator<>() {
        @Override public RuntimeOperationResult createFromParcel(Parcel source) {
            return new RuntimeOperationResult(
                    source.readInt(), source.readString(), source.readString(),
                    source.readInt() != 0, source.readString(),
                    source.readParcelable(SandboxError.class.getClassLoader()),
                    source.readParcelable(Bundle.class.getClassLoader()));
        }

        @Override public RuntimeOperationResult[] newArray(int size) {
            return new RuntimeOperationResult[size];
        }
    };

    private final int protocolVersion;
    private final String requestId;
    private final String operation;
    private final boolean successful;
    private final String status;
    private final SandboxError error;
    private final Bundle payload;

    private RuntimeOperationResult(int protocolVersion, String requestId, String operation,
            boolean successful, String status, SandboxError error, Bundle payload) {
        if (protocolVersion <= 0) {
            throw new IllegalArgumentException("protocolVersion must be positive");
        }
        if (successful && error != null) {
            throw new IllegalArgumentException("successful result cannot contain an error");
        }
        if (!successful && error == null) {
            throw new IllegalArgumentException("failed result requires an error");
        }
        this.protocolVersion = protocolVersion;
        this.requestId = ContractChecks.requiredText(requestId, "requestId", 128);
        this.operation = ContractChecks.requiredText(operation, "operation", 64);
        this.successful = successful;
        this.status = ContractChecks.requiredText(status, "status", 128);
        this.error = error;
        this.payload = payload == null ? new Bundle() : new Bundle(payload);
    }

    public static RuntimeOperationResult success(RuntimeOperationRequest request,
            String status, Bundle payload) {
        return new RuntimeOperationResult(request.protocolVersion(), request.requestId(),
                request.operation(), true, status, null, payload);
    }

    public static RuntimeOperationResult failure(RuntimeOperationRequest request,
            String status, SandboxError error, Bundle payload) {
        return new RuntimeOperationResult(request.protocolVersion(), request.requestId(),
                request.operation(), false, status, error, payload);
    }

    public int protocolVersion() { return protocolVersion; }
    public String requestId() { return requestId; }
    public String operation() { return operation; }
    public boolean successful() { return successful; }
    public String status() { return status; }
    public SandboxError error() { return error; }
    public Bundle payload() { return new Bundle(payload); }

    @Override public int describeContents() {
        return (error == null ? 0 : error.describeContents()) | payload.describeContents();
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(protocolVersion);
        dest.writeString(requestId);
        dest.writeString(operation);
        dest.writeInt(successful ? 1 : 0);
        dest.writeString(status);
        dest.writeParcelable(error, flags);
        dest.writeParcelable(payload, flags);
    }
}
