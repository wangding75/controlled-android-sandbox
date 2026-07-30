package com.warden.controlledsandbox.contract;
import android.os.Parcel;
import android.os.Parcelable;
/** Biometric capability and deterministic authentication policy for one guest scope. */ public final class VirtualBiometricProfileSnapshot implements Parcelable {
    public static final String OUTCOME_SUCCESS="SUCCESS", OUTCOME_FAILURE="FAILURE", OUTCOME_LOCKOUT="LOCKOUT", OUTCOME_CANCELED="CANCELED";
    private final String mode;
    private final boolean hardwareDetected;
    private final boolean enrolled;
    private final int authenticatorTypes;
    private final int strength;
    private final boolean allowAuthentication;
    private final boolean deviceCredentialAllowed;
    private final String outcome;
    private final int maximumSessions;
    private final long authenticationLatencyMs;
    public VirtualBiometricProfileSnapshot(String mode, boolean hardwareDetected, boolean enrolled, int authenticatorTypes, int strength, boolean allowAuthentication, boolean deviceCredentialAllowed, String outcome, int maximumSessions, long authenticationLatencyMs){
        this.mode=VirtualLocationProfileSnapshot.mode(mode);
        this.hardwareDetected=hardwareDetected;
        this.enrolled=enrolled;
        if(authenticatorTypes<0||strength<0)throw new IllegalArgumentException("biometric masks must be non-negative");
        this.authenticatorTypes=authenticatorTypes;
        this.strength=strength;
        this.allowAuthentication=allowAuthentication;
        this.deviceCredentialAllowed=deviceCredentialAllowed;
        String normalized=outcome==null?"":outcome.trim().toUpperCase(java.util.Locale.ROOT);
        if(!java.util.Set.of(OUTCOME_SUCCESS, OUTCOME_FAILURE, OUTCOME_LOCKOUT, OUTCOME_CANCELED).contains(normalized))throw new IllegalArgumentException("unsupported biometric outcome");
        this.outcome=normalized;
        if(maximumSessions<0||maximumSessions>32)throw new IllegalArgumentException("maximumSessions must be in [0,32]");
        if(authenticationLatencyMs<0||authenticationLatencyMs>30000L)throw new IllegalArgumentException("authenticationLatencyMs must be in [0,30000]");
        this.maximumSessions=maximumSessions;
        this.authenticationLatencyMs=authenticationLatencyMs;
    }
    private VirtualBiometricProfileSnapshot(Parcel in){
        this(in.readString(), in.readInt()!=0, in.readInt()!=0, in.readInt(), in.readInt(), in.readInt()!=0, in.readInt()!=0, in.readString(), in.readInt(), in.readLong());
    }
    public String mode(){
        return mode;
    }
    public boolean hardwareDetected(){
        return hardwareDetected;
    }
    public boolean enrolled(){
        return enrolled;
    }
    public int authenticatorTypes(){
        return authenticatorTypes;
    }
    public int strength(){
        return strength;
    }
    public boolean allowAuthentication(){
        return allowAuthentication;
    }
    public boolean deviceCredentialAllowed(){
        return deviceCredentialAllowed;
    }
    public String outcome(){
        return outcome;
    }
    public int maximumSessions(){
        return maximumSessions;
    }
    public long authenticationLatencyMs(){
        return authenticationLatencyMs;
    }
    @Override public void writeToParcel(Parcel out, int flags){
        out.writeString(mode);
        out.writeInt(hardwareDetected?1:0);
        out.writeInt(enrolled?1:0);
        out.writeInt(authenticatorTypes);
        out.writeInt(strength);
        out.writeInt(allowAuthentication?1:0);
        out.writeInt(deviceCredentialAllowed?1:0);
        out.writeString(outcome);
        out.writeInt(maximumSessions);
        out.writeLong(authenticationLatencyMs);
    }
    @Override public int describeContents(){
        return 0;
    }
    public static final Creator<VirtualBiometricProfileSnapshot> CREATOR=new Creator<>(){
        public VirtualBiometricProfileSnapshot createFromParcel(Parcel in){
            return new VirtualBiometricProfileSnapshot(in);
        }
        public VirtualBiometricProfileSnapshot[] newArray(int size){
            return new VirtualBiometricProfileSnapshot[size];
        }
    };
}
