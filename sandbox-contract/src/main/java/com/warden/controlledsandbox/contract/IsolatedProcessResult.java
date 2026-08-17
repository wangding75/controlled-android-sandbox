package com.warden.controlledsandbox.contract;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/** Typed result and platform-process identity evidence from an isolated worker. */
public final class IsolatedProcessResult implements Parcelable {
    private final boolean successful;
    private final String status;
    private final String sessionId;
    private final long generation;
    private final int processSlot;
    private final String processName;
    private final String componentClass;
    private final int platformPid;
    private final int platformUid;
    private final String errorType;
    private final String errorMessage;
    private final Bundle payload;

    public IsolatedProcessResult(boolean successful, String status, String sessionId,
            long generation, int processSlot, String processName, String componentClass,
            int platformPid, int platformUid, String errorType, String errorMessage,
            Bundle payload) {
        this.successful = successful;
        this.status = ContractChecks.requiredText(status, "status", 96);
        this.sessionId = ContractChecks.requiredText(sessionId, "sessionId", 128);
        if (generation < 1) throw new IllegalArgumentException("generation must be positive");
        this.generation = generation;
        if (!ProcessSlotContract.isOrdinarySlot(processSlot)) {
            throw new IllegalArgumentException("processSlot is invalid");
        }
        this.processSlot = processSlot;
        this.processName = ContractChecks.requiredText(processName, "processName", 320);
        this.componentClass = ContractChecks.requiredText(componentClass, "componentClass", 512);
        this.platformPid = ContractChecks.nonNegative(platformPid, "platformPid");
        this.platformUid = ContractChecks.nonNegative(platformUid, "platformUid");
        this.errorType = ContractChecks.optionalText(errorType, "errorType", 160);
        this.errorMessage = ContractChecks.optionalText(errorMessage, "errorMessage", 2048);
        this.payload = copyPayload(payload);
        if (successful && !this.errorType.isEmpty()) {
            throw new IllegalArgumentException("successful isolated result cannot contain an error");
        }
    }

    public static IsolatedProcessResult success(IsolatedProcessRequest request, String status,
            int pid, int uid, Bundle payload) {
        return new IsolatedProcessResult(true, status, request.sessionId(), request.generation(),
                request.processSlot(), request.processName(), request.componentClass(), pid, uid,
                "", "", payload);
    }

    public static IsolatedProcessResult failure(IsolatedProcessRequest request, Throwable error,
            int pid, int uid) {
        String type = error == null ? "UNKNOWN" : error.getClass().getName();
        String message = error == null ? "Unknown isolated process failure" : String.valueOf(error.getMessage());
        return new IsolatedProcessResult(false, "FAILED", request.sessionId(), request.generation(),
                request.processSlot(), request.processName(), request.componentClass(), pid, uid,
                type, message, new Bundle());
    }

    private IsolatedProcessResult(Parcel in) {
        this(in.readInt() != 0, in.readString(), in.readString(), in.readLong(), in.readInt(),
                in.readString(), in.readString(), in.readInt(), in.readInt(), in.readString(),
                in.readString(), in.readParcelable(IsolatedProcessResult.class.getClassLoader()));
    }

    public boolean successful() { return successful; }
    public String status() { return status; }
    public String sessionId() { return sessionId; }
    public long generation() { return generation; }
    public int processSlot() { return processSlot; }
    public String processName() { return processName; }
    public String componentClass() { return componentClass; }
    public int platformPid() { return platformPid; }
    public int platformUid() { return platformUid; }
    public String errorType() { return errorType; }
    public String errorMessage() { return errorMessage; }
    public Bundle payload() { return copyPayload(payload); }

    private static Bundle copyPayload(Bundle source) {
        ClassLoader loader = IsolatedProcessResult.class.getClassLoader();
        if (source == null) {
            Bundle empty = new Bundle();
            empty.setClassLoader(loader);
            return empty;
        }
        // Isolated workers return contract Parcelables (for example the virtual package
        // snapshot) inside this Bundle.  The Binder default loader is the boot loader and
        // cannot restore application contract classes in the host broker process.
        source.setClassLoader(loader);
        Bundle copy = new Bundle(source);
        copy.setClassLoader(loader);
        return copy;
    }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeInt(successful ? 1 : 0);
        out.writeString(status);
        out.writeString(sessionId);
        out.writeLong(generation);
        out.writeInt(processSlot);
        out.writeString(processName);
        out.writeString(componentClass);
        out.writeInt(platformPid);
        out.writeInt(platformUid);
        out.writeString(errorType);
        out.writeString(errorMessage);
        out.writeParcelable(payload, flags);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<IsolatedProcessResult> CREATOR = new Creator<>() {
        @Override public IsolatedProcessResult createFromParcel(Parcel source) {
            return new IsolatedProcessResult(source);
        }
        @Override public IsolatedProcessResult[] newArray(int size) {
            return new IsolatedProcessResult[size];
        }
    };
}
