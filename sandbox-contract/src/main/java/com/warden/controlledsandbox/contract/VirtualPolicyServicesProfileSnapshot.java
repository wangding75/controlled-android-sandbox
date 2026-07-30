package com.warden.controlledsandbox.contract;
import android.os.Parcel;
import android.os.Parcelable;
/** Aggregate policy/accessibility/autofill/biometric/privacy/power profile. */ public final class VirtualPolicyServicesProfileSnapshot implements Parcelable {
    private final long policyVersion;
    private final long updatedAtMs;
    private final VirtualDevicePolicyProfileSnapshot devicePolicy;
    private final VirtualAccessibilityProfileSnapshot accessibility;
    private final VirtualAutofillProfileSnapshot autofill;
    private final VirtualBiometricProfileSnapshot biometric;
    private final VirtualSensorPrivacyProfileSnapshot sensorPrivacy;
    private final VirtualPowerProfileSnapshot power;
    public VirtualPolicyServicesProfileSnapshot(long policyVersion, long updatedAtMs, VirtualDevicePolicyProfileSnapshot devicePolicy, VirtualAccessibilityProfileSnapshot accessibility, VirtualAutofillProfileSnapshot autofill, VirtualBiometricProfileSnapshot biometric, VirtualSensorPrivacyProfileSnapshot sensorPrivacy, VirtualPowerProfileSnapshot power){
        if(policyVersion<1L||updatedAtMs<0L)throw new IllegalArgumentException("policy profile version/time is invalid");
        this.policyVersion=policyVersion;
        this.updatedAtMs=updatedAtMs;
        this.devicePolicy=java.util.Objects.requireNonNull(devicePolicy, "devicePolicy");
        this.accessibility=java.util.Objects.requireNonNull(accessibility, "accessibility");
        this.autofill=java.util.Objects.requireNonNull(autofill, "autofill");
        this.biometric=java.util.Objects.requireNonNull(biometric, "biometric");
        this.sensorPrivacy=java.util.Objects.requireNonNull(sensorPrivacy, "sensorPrivacy");
        this.power=java.util.Objects.requireNonNull(power, "power");
    }
    private VirtualPolicyServicesProfileSnapshot(Parcel in){
        this(in.readLong(), in.readLong(), in.readParcelable(VirtualDevicePolicyProfileSnapshot.class.getClassLoader()), in.readParcelable(VirtualAccessibilityProfileSnapshot.class.getClassLoader()), in.readParcelable(VirtualAutofillProfileSnapshot.class.getClassLoader()), in.readParcelable(VirtualBiometricProfileSnapshot.class.getClassLoader()), in.readParcelable(VirtualSensorPrivacyProfileSnapshot.class.getClassLoader()), in.readParcelable(VirtualPowerProfileSnapshot.class.getClassLoader()));
    }
    public long policyVersion(){
        return policyVersion;
    }
    public long updatedAtMs(){
        return updatedAtMs;
    }
    public VirtualDevicePolicyProfileSnapshot devicePolicy(){
        return devicePolicy;
    }
    public VirtualAccessibilityProfileSnapshot accessibility(){
        return accessibility;
    }
    public VirtualAutofillProfileSnapshot autofill(){
        return autofill;
    }
    public VirtualBiometricProfileSnapshot biometric(){
        return biometric;
    }
    public VirtualSensorPrivacyProfileSnapshot sensorPrivacy(){
        return sensorPrivacy;
    }
    public VirtualPowerProfileSnapshot power(){
        return power;
    }
    public VirtualPolicyServicesProfileSnapshot withVersion(long version, long updatedAt){
        return new VirtualPolicyServicesProfileSnapshot(version, updatedAt, devicePolicy, accessibility, autofill, biometric, sensorPrivacy, power);
    }
    @Override public void writeToParcel(Parcel out, int flags){
        out.writeLong(policyVersion);
        out.writeLong(updatedAtMs);
        out.writeParcelable(devicePolicy, flags);
        out.writeParcelable(accessibility, flags);
        out.writeParcelable(autofill, flags);
        out.writeParcelable(biometric, flags);
        out.writeParcelable(sensorPrivacy, flags);
        out.writeParcelable(power, flags);
    }
    @Override public int describeContents(){
        return 0;
    }
    public static final Creator<VirtualPolicyServicesProfileSnapshot> CREATOR=new Creator<>(){
        public VirtualPolicyServicesProfileSnapshot createFromParcel(Parcel in){
            return new VirtualPolicyServicesProfileSnapshot(in);
        }
        public VirtualPolicyServicesProfileSnapshot[] newArray(int size){
            return new VirtualPolicyServicesProfileSnapshot[size];
        }
    };
}
