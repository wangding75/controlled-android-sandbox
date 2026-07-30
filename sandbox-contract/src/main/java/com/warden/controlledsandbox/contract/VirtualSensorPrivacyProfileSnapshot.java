package com.warden.controlledsandbox.contract;
import android.os.Parcel;
import android.os.Parcelable;
/** Camera/microphone sensor privacy projection and listener policy. */ public final class VirtualSensorPrivacyProfileSnapshot implements Parcelable {
    private final String mode;
    private final boolean allSensorsPrivacyEnabled;
    private final boolean cameraPrivacyEnabled;
    private final boolean microphonePrivacyEnabled;
    private final boolean allowChanges;
    private final int maximumListeners;
    public VirtualSensorPrivacyProfileSnapshot(String mode, boolean allSensorsPrivacyEnabled, boolean cameraPrivacyEnabled, boolean microphonePrivacyEnabled, boolean allowChanges, int maximumListeners){
        this.mode=VirtualLocationProfileSnapshot.mode(mode);
        this.allSensorsPrivacyEnabled=allSensorsPrivacyEnabled;
        this.cameraPrivacyEnabled=cameraPrivacyEnabled;
        this.microphonePrivacyEnabled=microphonePrivacyEnabled;
        this.allowChanges=allowChanges;
        if(maximumListeners<0||maximumListeners>128)throw new IllegalArgumentException("maximumListeners must be in [0,128]");
        this.maximumListeners=maximumListeners;
    }
    private VirtualSensorPrivacyProfileSnapshot(Parcel in){
        this(in.readString(), in.readInt()!=0, in.readInt()!=0, in.readInt()!=0, in.readInt()!=0, in.readInt());
    }
    public String mode(){
        return mode;
    }
    public boolean allSensorsPrivacyEnabled(){
        return allSensorsPrivacyEnabled;
    }
    public boolean cameraPrivacyEnabled(){
        return cameraPrivacyEnabled;
    }
    public boolean microphonePrivacyEnabled(){
        return microphonePrivacyEnabled;
    }
    public boolean allowChanges(){
        return allowChanges;
    }
    public int maximumListeners(){
        return maximumListeners;
    }
    @Override public void writeToParcel(Parcel out, int flags){
        out.writeString(mode);
        out.writeInt(allSensorsPrivacyEnabled?1:0);
        out.writeInt(cameraPrivacyEnabled?1:0);
        out.writeInt(microphonePrivacyEnabled?1:0);
        out.writeInt(allowChanges?1:0);
        out.writeInt(maximumListeners);
    }
    @Override public int describeContents(){
        return 0;
    }
    public static final Creator<VirtualSensorPrivacyProfileSnapshot> CREATOR=new Creator<>(){
        public VirtualSensorPrivacyProfileSnapshot createFromParcel(Parcel in){
            return new VirtualSensorPrivacyProfileSnapshot(in);
        }
        public VirtualSensorPrivacyProfileSnapshot[] newArray(int size){
            return new VirtualSensorPrivacyProfileSnapshot[size];
        }
    };
}
