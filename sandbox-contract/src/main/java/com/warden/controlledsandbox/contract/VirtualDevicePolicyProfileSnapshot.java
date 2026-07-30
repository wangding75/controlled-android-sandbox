package com.warden.controlledsandbox.contract;
import android.os.Parcel;
import android.os.Parcelable;
/** Device-administration query and mutation policy for one guest scope. */ public final class VirtualDevicePolicyProfileSnapshot implements Parcelable {
    private final String mode;
    private final boolean adminActive;
    private final boolean profileOwner;
    private final boolean deviceOwner;
    private final boolean cameraDisabled;
    private final boolean screenCaptureDisabled;
    private final boolean storageEncryptionEnabled;
    private final int passwordQuality;
    private final int minimumPasswordLength;
    private final int maximumFailedPasswords;
    public VirtualDevicePolicyProfileSnapshot(String mode, boolean adminActive, boolean profileOwner, boolean deviceOwner, boolean cameraDisabled, boolean screenCaptureDisabled, boolean storageEncryptionEnabled, int passwordQuality, int minimumPasswordLength, int maximumFailedPasswords) {
        this.mode = VirtualLocationProfileSnapshot.mode(mode);
        this.adminActive = adminActive;
        this.profileOwner = profileOwner;
        this.deviceOwner = deviceOwner;
        this.cameraDisabled = cameraDisabled;
        this.screenCaptureDisabled = screenCaptureDisabled;
        this.storageEncryptionEnabled = storageEncryptionEnabled;
        if (passwordQuality < 0) throw new IllegalArgumentException("passwordQuality must be non-negative");
        if (minimumPasswordLength < 0 || minimumPasswordLength > 128) {
            throw new IllegalArgumentException("minimumPasswordLength must be in [0,128]");
        }
        if (maximumFailedPasswords < 0 || maximumFailedPasswords > 1000) {
            throw new IllegalArgumentException("maximumFailedPasswords must be in [0,1000]");
        }
        this.passwordQuality = passwordQuality;
        this.minimumPasswordLength = minimumPasswordLength;
        this.maximumFailedPasswords = maximumFailedPasswords;
    }
    private VirtualDevicePolicyProfileSnapshot(Parcel in) {
        this(in.readString(), in.readInt()!=0, in.readInt()!=0, in.readInt()!=0, in.readInt()!=0, in.readInt()!=0, in.readInt()!=0, in.readInt(), in.readInt(), in.readInt());
    }
    public String mode(){
        return mode;
    }
    public boolean adminActive(){
        return adminActive;
    }
    public boolean profileOwner(){
        return profileOwner;
    }
    public boolean deviceOwner(){
        return deviceOwner;
    }
    public boolean cameraDisabled(){
        return cameraDisabled;
    }
    public boolean screenCaptureDisabled(){
        return screenCaptureDisabled;
    }
    public boolean storageEncryptionEnabled(){
        return storageEncryptionEnabled;
    }
    public int passwordQuality(){
        return passwordQuality;
    }
    public int minimumPasswordLength(){
        return minimumPasswordLength;
    }
    public int maximumFailedPasswords(){
        return maximumFailedPasswords;
    }
    @Override public void writeToParcel(Parcel out, int flags){
        out.writeString(mode);
        out.writeInt(adminActive?1:0);
        out.writeInt(profileOwner?1:0);
        out.writeInt(deviceOwner?1:0);
        out.writeInt(cameraDisabled?1:0);
        out.writeInt(screenCaptureDisabled?1:0);
        out.writeInt(storageEncryptionEnabled?1:0);
        out.writeInt(passwordQuality);
        out.writeInt(minimumPasswordLength);
        out.writeInt(maximumFailedPasswords);
    }
    @Override public int describeContents(){
        return 0;
    }
    public static final Creator<VirtualDevicePolicyProfileSnapshot> CREATOR=new Creator<>(){
        public VirtualDevicePolicyProfileSnapshot createFromParcel(Parcel in){
            return new VirtualDevicePolicyProfileSnapshot(in);
        }
        public VirtualDevicePolicyProfileSnapshot[] newArray(int size){
            return new VirtualDevicePolicyProfileSnapshot[size];
        }
    };
}
