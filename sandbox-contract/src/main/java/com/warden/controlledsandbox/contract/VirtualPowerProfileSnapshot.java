package com.warden.controlledsandbox.contract;
import android.os.Parcel;
import android.os.Parcelable;
/** Power, WakeLock and vibration policy for one guest scope. */ public final class VirtualPowerProfileSnapshot implements Parcelable {
    private final String mode;
    private final boolean interactive;
    private final boolean powerSaveMode;
    private final boolean deviceIdleMode;
    private final boolean batteryOptimizationsIgnored;
    private final int maximumWakeLocks;
    private final long maximumWakeLockDurationMs;
    private final boolean allowVibration;
    private final int maximumVibrations;
    private final long maximumVibrationDurationMs;
    public VirtualPowerProfileSnapshot(String mode, boolean interactive, boolean powerSaveMode, boolean deviceIdleMode, boolean batteryOptimizationsIgnored, int maximumWakeLocks, long maximumWakeLockDurationMs, boolean allowVibration, int maximumVibrations, long maximumVibrationDurationMs){
        this.mode=VirtualLocationProfileSnapshot.mode(mode);
        this.interactive=interactive;
        this.powerSaveMode=powerSaveMode;
        this.deviceIdleMode=deviceIdleMode;
        this.batteryOptimizationsIgnored=batteryOptimizationsIgnored;
        if(maximumWakeLocks<0||maximumWakeLocks>256)throw new IllegalArgumentException("maximumWakeLocks must be in [0,256]");
        if(maximumWakeLockDurationMs<0||maximumWakeLockDurationMs>3600000L)throw new IllegalArgumentException("maximumWakeLockDurationMs must be in [0,3600000]");
        if(maximumVibrations<0||maximumVibrations>256)throw new IllegalArgumentException("maximumVibrations must be in [0,256]");
        if(maximumVibrationDurationMs<0||maximumVibrationDurationMs>60000L)throw new IllegalArgumentException("maximumVibrationDurationMs must be in [0,60000]");
        this.maximumWakeLocks=maximumWakeLocks;
        this.maximumWakeLockDurationMs=maximumWakeLockDurationMs;
        this.allowVibration=allowVibration;
        this.maximumVibrations=maximumVibrations;
        this.maximumVibrationDurationMs=maximumVibrationDurationMs;
    }
    private VirtualPowerProfileSnapshot(Parcel in){
        this(in.readString(), in.readInt()!=0, in.readInt()!=0, in.readInt()!=0, in.readInt()!=0, in.readInt(), in.readLong(), in.readInt()!=0, in.readInt(), in.readLong());
    }
    public String mode(){
        return mode;
    }
    public boolean interactive(){
        return interactive;
    }
    public boolean powerSaveMode(){
        return powerSaveMode;
    }
    public boolean deviceIdleMode(){
        return deviceIdleMode;
    }
    public boolean batteryOptimizationsIgnored(){
        return batteryOptimizationsIgnored;
    }
    public int maximumWakeLocks(){
        return maximumWakeLocks;
    }
    public long maximumWakeLockDurationMs(){
        return maximumWakeLockDurationMs;
    }
    public boolean allowVibration(){
        return allowVibration;
    }
    public int maximumVibrations(){
        return maximumVibrations;
    }
    public long maximumVibrationDurationMs(){
        return maximumVibrationDurationMs;
    }
    @Override public void writeToParcel(Parcel out, int flags){
        out.writeString(mode);
        out.writeInt(interactive?1:0);
        out.writeInt(powerSaveMode?1:0);
        out.writeInt(deviceIdleMode?1:0);
        out.writeInt(batteryOptimizationsIgnored?1:0);
        out.writeInt(maximumWakeLocks);
        out.writeLong(maximumWakeLockDurationMs);
        out.writeInt(allowVibration?1:0);
        out.writeInt(maximumVibrations);
        out.writeLong(maximumVibrationDurationMs);
    }
    @Override public int describeContents(){
        return 0;
    }
    public static final Creator<VirtualPowerProfileSnapshot> CREATOR=new Creator<>(){
        public VirtualPowerProfileSnapshot createFromParcel(Parcel in){
            return new VirtualPowerProfileSnapshot(in);
        }
        public VirtualPowerProfileSnapshot[] newArray(int size){
            return new VirtualPowerProfileSnapshot[size];
        }
    };
}
