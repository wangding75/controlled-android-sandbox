package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.Arrays;

/** Bounded typed request for a cross-width Native companion process. */
public final class NativeCompanionRequest implements Parcelable {
    public static final String OP_PROBE = "PROBE";
    public static final String OP_PREPARE_GENERATION = "PREPARE_GENERATION";
    public static final String OP_CLEAR_GENERATION = "CLEAR_GENERATION";
    private static final int MAX_NONCE_BYTES = 64;

    private final int protocol;
    private final String sessionId;
    private final long generation;
    private final int virtualUserId;
    private final String packageName;
    private final String packageRevision;
    private final byte[] capabilityNonce;
    private final String requestedAbi;
    private final String operation;

    public NativeCompanionRequest(int protocol, String sessionId, long generation,
                                  int virtualUserId, String packageName, String packageRevision,
                                  byte[] capabilityNonce, String requestedAbi, String operation) {
        if (protocol < 1) throw new IllegalArgumentException("protocol must be positive");
        if (generation < 1) throw new IllegalArgumentException("generation must be positive");
        if (virtualUserId < 0) throw new IllegalArgumentException("virtualUserId must be non-negative");
        this.protocol = protocol;
        this.sessionId = required(sessionId, "sessionId", 128);
        this.generation = generation;
        this.virtualUserId = virtualUserId;
        this.packageName = required(packageName, "packageName", 255);
        this.packageRevision = required(packageRevision, "packageRevision", 160);
        if (capabilityNonce == null || capabilityNonce.length < 16 || capabilityNonce.length > MAX_NONCE_BYTES) {
            throw new IllegalArgumentException("capabilityNonce length is invalid");
        }
        this.capabilityNonce = capabilityNonce.clone();
        this.requestedAbi = abi(requestedAbi);
        this.operation = operation(operation);
    }

    private NativeCompanionRequest(Parcel in) {
        this(in.readInt(), in.readString(), in.readLong(), in.readInt(), in.readString(),
                in.readString(), in.createByteArray(), in.readString(), in.readString());
    }

    public int protocol() { return protocol; }
    public String sessionId() { return sessionId; }
    public long generation() { return generation; }
    public int virtualUserId() { return virtualUserId; }
    public String packageName() { return packageName; }
    public String packageRevision() { return packageRevision; }
    public byte[] capabilityNonce() { return capabilityNonce.clone(); }
    public String requestedAbi() { return requestedAbi; }
    public String operation() { return operation; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeInt(protocol); out.writeString(sessionId); out.writeLong(generation);
        out.writeInt(virtualUserId); out.writeString(packageName); out.writeString(packageRevision);
        out.writeByteArray(capabilityNonce); out.writeString(requestedAbi); out.writeString(operation);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<NativeCompanionRequest> CREATOR = new Creator<>() {
        @Override public NativeCompanionRequest createFromParcel(Parcel in) { return new NativeCompanionRequest(in); }
        @Override public NativeCompanionRequest[] newArray(int size) { return new NativeCompanionRequest[size]; }
    };

    @Override public boolean equals(Object other) {
        if (!(other instanceof NativeCompanionRequest value)) return false;
        return protocol == value.protocol && generation == value.generation
                && virtualUserId == value.virtualUserId && sessionId.equals(value.sessionId)
                && packageName.equals(value.packageName) && packageRevision.equals(value.packageRevision)
                && requestedAbi.equals(value.requestedAbi) && operation.equals(value.operation)
                && Arrays.equals(capabilityNonce, value.capabilityNonce);
    }
    @Override public int hashCode() { return 31 * packageRevision.hashCode() + Arrays.hashCode(capabilityNonce); }

    private static String required(String value, String name, int max) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
        String normalized = value.trim();
        if (normalized.length() > max) throw new IllegalArgumentException(name + " is too long");
        return normalized;
    }
    private static String abi(String value) {
        String normalized = required(value, "requestedAbi", 32);
        if (!"armeabi-v7a".equals(normalized) && !"x86".equals(normalized)) {
            throw new IllegalArgumentException("requestedAbi must be 32-bit");
        }
        return normalized;
    }
    private static String operation(String value) {
        String normalized = required(value, "operation", 64);
        if (!OP_PROBE.equals(normalized) && !OP_PREPARE_GENERATION.equals(normalized)
                && !OP_CLEAR_GENERATION.equals(normalized)) {
            throw new IllegalArgumentException("unsupported operation: " + normalized);
        }
        return normalized;
    }
}
