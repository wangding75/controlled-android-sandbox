package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Typed result from the 32-bit Native companion. */
public final class NativeCompanionResult implements Parcelable {
    private final boolean successful;
    private final String operation;
    private final String requestedAbi;
    private final int processBitness;
    private final long acceptedGeneration;
    private final String nativeStatus;
    private final String errorType;
    private final String errorMessage;

    public NativeCompanionResult(boolean successful, String operation, String requestedAbi,
                                 int processBitness, long acceptedGeneration, String nativeStatus,
                                 String errorType, String errorMessage) {
        this.successful = successful;
        this.operation = value(operation, 64);
        this.requestedAbi = value(requestedAbi, 32);
        if (processBitness != 0 && processBitness != 32) throw new IllegalArgumentException("processBitness must be 32");
        this.processBitness = processBitness;
        this.acceptedGeneration = acceptedGeneration;
        this.nativeStatus = value(nativeStatus, 1024);
        this.errorType = value(errorType, 128);
        this.errorMessage = value(errorMessage, 512);
        if (successful && (!this.errorType.isEmpty() || acceptedGeneration < 1 || processBitness != 32)) {
            throw new IllegalArgumentException("successful companion result is incomplete");
        }
    }

    public static NativeCompanionResult success(NativeCompanionRequest request, String nativeStatus) {
        return new NativeCompanionResult(true, request.operation(), request.requestedAbi(), 32,
                request.generation(), nativeStatus, "", "");
    }
    public static NativeCompanionResult failure(String operation, String abi, String type, String message) {
        return new NativeCompanionResult(false, operation, abi, 0, 0, "", type, message);
    }

    private NativeCompanionResult(Parcel in) {
        this(in.readInt() != 0, in.readString(), in.readString(), in.readInt(), in.readLong(),
                in.readString(), in.readString(), in.readString());
    }

    public boolean successful() { return successful; }
    public String operation() { return operation; }
    public String requestedAbi() { return requestedAbi; }
    public int processBitness() { return processBitness; }
    public long acceptedGeneration() { return acceptedGeneration; }
    public String nativeStatus() { return nativeStatus; }
    public String errorType() { return errorType; }
    public String errorMessage() { return errorMessage; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeInt(successful ? 1 : 0); out.writeString(operation); out.writeString(requestedAbi);
        out.writeInt(processBitness); out.writeLong(acceptedGeneration); out.writeString(nativeStatus);
        out.writeString(errorType); out.writeString(errorMessage);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<NativeCompanionResult> CREATOR = new Creator<>() {
        @Override public NativeCompanionResult createFromParcel(Parcel in) { return new NativeCompanionResult(in); }
        @Override public NativeCompanionResult[] newArray(int size) { return new NativeCompanionResult[size]; }
    };

    private static String value(String value, int max) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > max) throw new IllegalArgumentException("value is too long");
        return normalized;
    }
}
