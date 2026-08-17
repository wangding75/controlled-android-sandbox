package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Child-side request. Carries only an opaque token, never a host path or arbitrary endpoint. */
public final class HostileCapabilityRequest implements Parcelable {
    public static final String OP_READ_RESOURCE = "READ_RESOURCE";
    public static final String OP_NETWORK_REQUEST = "NETWORK_REQUEST";
    public static final String OP_DELEGATE_FD = "DELEGATE_FD";

    private final String tokenId;
    private final String sessionId;
    private final long generation;
    private final String guestPackage;
    private final int virtualUserId;
    private final String operation;

    public HostileCapabilityRequest(String tokenId, String sessionId, long generation,
            String guestPackage, int virtualUserId, String operation) {
        this.tokenId = ContractChecks.requiredText(tokenId, "tokenId", 192);
        this.sessionId = ContractChecks.requiredText(sessionId, "sessionId", 128);
        if (generation < 1) throw new IllegalArgumentException("generation must be positive");
        this.generation = generation;
        this.guestPackage = ContractChecks.requiredText(guestPackage, "guestPackage", 256);
        this.virtualUserId = ContractChecks.nonNegative(virtualUserId, "virtualUserId");
        this.operation = normalizeOperation(operation);
        if (this.tokenId.indexOf('/') >= 0 || this.tokenId.contains("..")) {
            throw new SecurityException("CAPABILITY_TOKEN_LOOKS_LIKE_PATH");
        }
    }

    private HostileCapabilityRequest(Parcel in) {
        this(in.readString(), in.readString(), in.readLong(), in.readString(), in.readInt(),
                in.readString());
    }

    public String tokenId() { return tokenId; }
    public String sessionId() { return sessionId; }
    public long generation() { return generation; }
    public String guestPackage() { return guestPackage; }
    public int virtualUserId() { return virtualUserId; }
    public String operation() { return operation; }

    public static String normalizeOperation(String operation) {
        String normalized = ContractChecks.requiredText(operation, "operation", 64)
                .toUpperCase(java.util.Locale.ROOT);
        if (!OP_READ_RESOURCE.equals(normalized) && !OP_NETWORK_REQUEST.equals(normalized)
                && !OP_DELEGATE_FD.equals(normalized)) {
            throw new SecurityException("UNSUPPORTED_HOSTILE_CAPABILITY_OPERATION");
        }
        return normalized;
    }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(tokenId);
        out.writeString(sessionId);
        out.writeLong(generation);
        out.writeString(guestPackage);
        out.writeInt(virtualUserId);
        out.writeString(operation);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<HostileCapabilityRequest> CREATOR = new Creator<>() {
        @Override public HostileCapabilityRequest createFromParcel(Parcel in) {
            return new HostileCapabilityRequest(in);
        }

        @Override public HostileCapabilityRequest[] newArray(int size) {
            return new HostileCapabilityRequest[size];
        }
    };
}
