package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Broker-side public view of a capability. Resource location is never exported. */
public final class HostileCapabilitySnapshot implements Parcelable {
    public static final String ENDPOINT_LOOPBACK_TEST = "LOOPBACK_TEST";
    public static final String RESOURCE_READ_ONLY = "READ_ONLY";

    private final String tokenId;
    private final String sessionId;
    private final long generation;
    private final String guestPackage;
    private final int virtualUserId;
    private final String operation;
    private final String resourceClass;
    private final long expiresAtMillis;
    private final boolean revoked;

    public HostileCapabilitySnapshot(String tokenId, String sessionId, long generation,
            String guestPackage, int virtualUserId, String operation, String resourceClass,
            long expiresAtMillis, boolean revoked) {
        this.tokenId = ContractChecks.requiredText(tokenId, "tokenId", 192);
        this.sessionId = ContractChecks.requiredText(sessionId, "sessionId", 128);
        if (generation < 1) throw new IllegalArgumentException("generation must be positive");
        this.generation = generation;
        this.guestPackage = ContractChecks.requiredText(guestPackage, "guestPackage", 256);
        this.virtualUserId = ContractChecks.nonNegative(virtualUserId, "virtualUserId");
        this.operation = HostileCapabilityRequest.normalizeOperation(operation);
        this.resourceClass = ContractChecks.requiredText(resourceClass, "resourceClass", 64);
        this.expiresAtMillis = ContractChecks.nonNegative(expiresAtMillis, "expiresAtMillis");
        this.revoked = revoked;
    }

    private HostileCapabilitySnapshot(Parcel in) {
        this(in.readString(), in.readString(), in.readLong(), in.readString(), in.readInt(),
                in.readString(), in.readString(), in.readLong(), in.readInt() != 0);
    }

    public String tokenId() { return tokenId; }
    public String sessionId() { return sessionId; }
    public long generation() { return generation; }
    public String guestPackage() { return guestPackage; }
    public int virtualUserId() { return virtualUserId; }
    public String operation() { return operation; }
    public String resourceClass() { return resourceClass; }
    public long expiresAtMillis() { return expiresAtMillis; }
    public boolean revoked() { return revoked; }

    public HostileCapabilitySnapshot revokedCopy() {
        return new HostileCapabilitySnapshot(tokenId, sessionId, generation, guestPackage,
                virtualUserId, operation, resourceClass, expiresAtMillis, true);
    }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(tokenId);
        out.writeString(sessionId);
        out.writeLong(generation);
        out.writeString(guestPackage);
        out.writeInt(virtualUserId);
        out.writeString(operation);
        out.writeString(resourceClass);
        out.writeLong(expiresAtMillis);
        out.writeInt(revoked ? 1 : 0);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<HostileCapabilitySnapshot> CREATOR = new Creator<>() {
        @Override public HostileCapabilitySnapshot createFromParcel(Parcel in) {
            return new HostileCapabilitySnapshot(in);
        }

        @Override public HostileCapabilitySnapshot[] newArray(int size) {
            return new HostileCapabilitySnapshot[size];
        }
    };
}
