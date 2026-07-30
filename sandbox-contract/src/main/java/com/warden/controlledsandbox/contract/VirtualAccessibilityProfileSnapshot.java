package com.warden.controlledsandbox.contract;
import android.os.Parcel;
import android.os.Parcelable;
import java.util.List;
/** Accessibility service visibility and client policy for one guest scope. */ public final class VirtualAccessibilityProfileSnapshot implements Parcelable {
    private final String mode;
    private final boolean enabled;
    private final boolean touchExplorationEnabled;
    private final boolean highTextContrastEnabled;
    private final boolean allowEventDispatch;
    private final int maximumClients;
    private final long recommendedTimeoutMs;
    private final List<String> enabledServices;
    public VirtualAccessibilityProfileSnapshot(String mode, boolean enabled, boolean touchExplorationEnabled, boolean highTextContrastEnabled, boolean allowEventDispatch, int maximumClients, long recommendedTimeoutMs, List<String> enabledServices) {
        this.mode=VirtualLocationProfileSnapshot.mode(mode);
        this.enabled=enabled;
        this.touchExplorationEnabled=touchExplorationEnabled;
        this.highTextContrastEnabled=highTextContrastEnabled;
        this.allowEventDispatch=allowEventDispatch;
        if(maximumClients<0||maximumClients>128)throw new IllegalArgumentException("maximumClients must be in [0,128]");
        if(recommendedTimeoutMs<0||recommendedTimeoutMs>300000L)throw new IllegalArgumentException("recommendedTimeoutMs must be in [0,300000]");
        this.maximumClients=maximumClients;
        this.recommendedTimeoutMs=recommendedTimeoutMs;
        this.enabledServices=VirtualUserProfileSnapshot.strings(enabledServices, "enabledServices", 128, 512);
    }
    private VirtualAccessibilityProfileSnapshot(Parcel in){
        this(in.readString(), in.readInt()!=0, in.readInt()!=0, in.readInt()!=0, in.readInt()!=0, in.readInt(), in.readLong(), in.createStringArrayList());
    }
    public String mode(){
        return mode;
    }
    public boolean enabled(){
        return enabled;
    }
    public boolean touchExplorationEnabled(){
        return touchExplorationEnabled;
    }
    public boolean highTextContrastEnabled(){
        return highTextContrastEnabled;
    }
    public boolean allowEventDispatch(){
        return allowEventDispatch;
    }
    public int maximumClients(){
        return maximumClients;
    }
    public long recommendedTimeoutMs(){
        return recommendedTimeoutMs;
    }
    public List<String> enabledServices(){
        return enabledServices;
    }
    @Override public void writeToParcel(Parcel out, int flags){
        out.writeString(mode);
        out.writeInt(enabled?1:0);
        out.writeInt(touchExplorationEnabled?1:0);
        out.writeInt(highTextContrastEnabled?1:0);
        out.writeInt(allowEventDispatch?1:0);
        out.writeInt(maximumClients);
        out.writeLong(recommendedTimeoutMs);
        out.writeStringList(enabledServices);
    }
    @Override public int describeContents(){
        return 0;
    }
    public static final Creator<VirtualAccessibilityProfileSnapshot> CREATOR=new Creator<>(){
        public VirtualAccessibilityProfileSnapshot createFromParcel(Parcel in){
            return new VirtualAccessibilityProfileSnapshot(in);
        }
        public VirtualAccessibilityProfileSnapshot[] newArray(int size){
            return new VirtualAccessibilityProfileSnapshot[size];
        }
    };
}
