package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Typed admission record for one Native execution session. */
public final class HostileAdmissionSnapshot implements Parcelable {
    public static final String NETWORK_BROKER_ONLY = "BROKER_ONLY";
    public static final String PROCESS_ISOLATED_UID = "ISOLATED_UID";

    private final String executionProfile;
    private final String guestPackage;
    private final int virtualUserId;
    private final String processName;
    private final String abi;
    private final long generation;
    private final String sessionId;
    private final String networkPolicy;
    private final String processPolicy;

    public HostileAdmissionSnapshot(String executionProfile, String guestPackage, int virtualUserId,
            String processName, String abi, long generation, String sessionId,
            String networkPolicy, String processPolicy) {
        this.executionProfile = NativeExecutionProfile.normalize(executionProfile);
        this.guestPackage = ContractChecks.requiredText(guestPackage, "guestPackage", 256);
        this.virtualUserId = ContractChecks.nonNegative(virtualUserId, "virtualUserId");
        this.processName = ContractChecks.requiredText(processName, "processName", 320);
        this.abi = ContractChecks.requiredText(abi, "abi", 32);
        if (generation < 1) throw new IllegalArgumentException("generation must be positive");
        this.generation = generation;
        this.sessionId = ContractChecks.requiredText(sessionId, "sessionId", 128);
        this.networkPolicy = ContractChecks.requiredText(networkPolicy, "networkPolicy", 64);
        this.processPolicy = ContractChecks.requiredText(processPolicy, "processPolicy", 64);
        if (NativeExecutionProfile.isHostile(this.executionProfile)) {
            if (!NETWORK_BROKER_ONLY.equals(this.networkPolicy)) {
                throw new SecurityException("HOSTILE_NETWORK_POLICY_REQUIRED");
            }
            if (!PROCESS_ISOLATED_UID.equals(this.processPolicy)) {
                throw new SecurityException("HOSTILE_PROCESS_POLICY_REQUIRED");
            }
        }
    }

    private HostileAdmissionSnapshot(Parcel in) {
        this(in.readString(), in.readString(), in.readInt(), in.readString(), in.readString(),
                in.readLong(), in.readString(), in.readString(), in.readString());
    }

    public String executionProfile() { return executionProfile; }
    public String guestPackage() { return guestPackage; }
    public int virtualUserId() { return virtualUserId; }
    public String processName() { return processName; }
    public String abi() { return abi; }
    public long generation() { return generation; }
    public String sessionId() { return sessionId; }
    public String networkPolicy() { return networkPolicy; }
    public String processPolicy() { return processPolicy; }
    public boolean hostile() { return NativeExecutionProfile.isHostile(executionProfile); }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(executionProfile);
        out.writeString(guestPackage);
        out.writeInt(virtualUserId);
        out.writeString(processName);
        out.writeString(abi);
        out.writeLong(generation);
        out.writeString(sessionId);
        out.writeString(networkPolicy);
        out.writeString(processPolicy);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<HostileAdmissionSnapshot> CREATOR = new Creator<>() {
        @Override public HostileAdmissionSnapshot createFromParcel(Parcel in) {
            return new HostileAdmissionSnapshot(in);
        }

        @Override public HostileAdmissionSnapshot[] newArray(int size) {
            return new HostileAdmissionSnapshot[size];
        }
    };
}
