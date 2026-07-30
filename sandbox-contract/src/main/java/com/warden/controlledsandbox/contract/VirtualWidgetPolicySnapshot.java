package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** AppWidget host, allocation and binding policy. */
public final class VirtualWidgetPolicySnapshot implements Parcelable {
    private final String mode;
    private final boolean enabled;
    private final boolean allowBind;
    private final boolean exposeInstalledProviders;
    private final int maximumWidgets;
    private final int maximumHosts;

    public VirtualWidgetPolicySnapshot(String mode, boolean enabled, boolean allowBind,
            boolean exposeInstalledProviders, int maximumWidgets, int maximumHosts) {
        this.mode = VirtualLocationProfileSnapshot.mode(mode);
        this.enabled = enabled;
        this.allowBind = allowBind;
        this.exposeInstalledProviders = exposeInstalledProviders;
        if (maximumWidgets < 0 || maximumWidgets > 512 || maximumHosts < 1 || maximumHosts > 64) {
            throw new IllegalArgumentException("app-widget limits are invalid");
        }
        this.maximumWidgets = maximumWidgets;
        this.maximumHosts = maximumHosts;
    }
    private VirtualWidgetPolicySnapshot(Parcel in) {
        this(in.readString(), in.readInt() != 0, in.readInt() != 0, in.readInt() != 0,
                in.readInt(), in.readInt());
    }
    public String mode() { return mode; }
    public boolean enabled() { return enabled; }
    public boolean allowBind() { return allowBind; }
    public boolean exposeInstalledProviders() { return exposeInstalledProviders; }
    public int maximumWidgets() { return maximumWidgets; }
    public int maximumHosts() { return maximumHosts; }
    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode); out.writeInt(enabled ? 1 : 0); out.writeInt(allowBind ? 1 : 0);
        out.writeInt(exposeInstalledProviders ? 1 : 0); out.writeInt(maximumWidgets); out.writeInt(maximumHosts);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualWidgetPolicySnapshot> CREATOR = new Creator<>() {
        @Override public VirtualWidgetPolicySnapshot createFromParcel(Parcel in) { return new VirtualWidgetPolicySnapshot(in); }
        @Override public VirtualWidgetPolicySnapshot[] newArray(int size) { return new VirtualWidgetPolicySnapshot[size]; }
    };
}
