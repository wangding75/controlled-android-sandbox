package com.warden.controlledsandbox.contract;
import android.os.Parcel;
import android.os.Parcelable;
/** Autofill service and session policy for one guest scope. */ public final class VirtualAutofillProfileSnapshot implements Parcelable {
    private final String mode;
    private final boolean enabled;
    private final String serviceComponent;
    private final boolean saveEnabled;
    private final boolean augmentedAutofillEnabled;
    private final int maximumSessions;
    private final long sessionTimeoutMs;
    public VirtualAutofillProfileSnapshot(String mode, boolean enabled, String serviceComponent, boolean saveEnabled, boolean augmentedAutofillEnabled, int maximumSessions, long sessionTimeoutMs){
        this.mode=VirtualLocationProfileSnapshot.mode(mode);
        this.enabled=enabled;
        this.serviceComponent=clean(serviceComponent, 512);
        this.saveEnabled=saveEnabled;
        this.augmentedAutofillEnabled=augmentedAutofillEnabled;
        if(maximumSessions<0||maximumSessions>64)throw new IllegalArgumentException("maximumSessions must be in [0,64]");
        if(sessionTimeoutMs<1000L||sessionTimeoutMs>600000L)throw new IllegalArgumentException("sessionTimeoutMs must be in [1000,600000]");
        this.maximumSessions=maximumSessions;
        this.sessionTimeoutMs=sessionTimeoutMs;
    }
    private VirtualAutofillProfileSnapshot(Parcel in){
        this(in.readString(), in.readInt()!=0, in.readString(), in.readInt()!=0, in.readInt()!=0, in.readInt(), in.readLong());
    }
    public String mode(){
        return mode;
    }
    public boolean enabled(){
        return enabled;
    }
    public String serviceComponent(){
        return serviceComponent;
    }
    public boolean saveEnabled(){
        return saveEnabled;
    }
    public boolean augmentedAutofillEnabled(){
        return augmentedAutofillEnabled;
    }
    public int maximumSessions(){
        return maximumSessions;
    }
    public long sessionTimeoutMs(){
        return sessionTimeoutMs;
    }
    private static String clean(String value, int max){
        String v=value==null?"":value.trim();
        if(v.length()>max)throw new IllegalArgumentException("value too long");
        return v;
    }
    @Override public void writeToParcel(Parcel out, int flags){
        out.writeString(mode);
        out.writeInt(enabled?1:0);
        out.writeString(serviceComponent);
        out.writeInt(saveEnabled?1:0);
        out.writeInt(augmentedAutofillEnabled?1:0);
        out.writeInt(maximumSessions);
        out.writeLong(sessionTimeoutMs);
    }
    @Override public int describeContents(){
        return 0;
    }
    public static final Creator<VirtualAutofillProfileSnapshot> CREATOR=new Creator<>(){
        public VirtualAutofillProfileSnapshot createFromParcel(Parcel in){
            return new VirtualAutofillProfileSnapshot(in);
        }
        public VirtualAutofillProfileSnapshot[] newArray(int size){
            return new VirtualAutofillProfileSnapshot[size];
        }
    };
}
