package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.List;

/** LauncherApps visibility and callback policy. */
public final class VirtualLauncherProfileSnapshot implements Parcelable {
    private final String mode;
    private final boolean enabled;
    private final boolean allowStartMainActivity;
    private final boolean allowPackageCallbacks;
    private final int maximumListeners;
    private final List<String> visiblePackages;
    private final List<String> hiddenPackages;

    public VirtualLauncherProfileSnapshot(String mode, boolean enabled, boolean allowStartMainActivity,
            boolean allowPackageCallbacks, int maximumListeners, List<String> visiblePackages,
            List<String> hiddenPackages) {
        this.mode = VirtualLocationProfileSnapshot.mode(mode);
        this.enabled = enabled;
        this.allowStartMainActivity = allowStartMainActivity;
        this.allowPackageCallbacks = allowPackageCallbacks;
        if (maximumListeners < 0 || maximumListeners > 128) {
            throw new IllegalArgumentException("maximumListeners must be in [0,128]");
        }
        this.maximumListeners = maximumListeners;
        this.visiblePackages = VirtualUserProfileSnapshot.strings(visiblePackages, "visiblePackages", 256, 255);
        this.hiddenPackages = VirtualUserProfileSnapshot.strings(hiddenPackages, "hiddenPackages", 256, 255);
        for (String value : this.visiblePackages) if (this.hiddenPackages.contains(value)) {
            throw new IllegalArgumentException("package cannot be visible and hidden");
        }
    }
    private VirtualLauncherProfileSnapshot(Parcel in) {
        this(in.readString(), in.readInt() != 0, in.readInt() != 0, in.readInt() != 0,
                in.readInt(), in.createStringArrayList(), in.createStringArrayList());
    }
    public String mode() { return mode; }
    public boolean enabled() { return enabled; }
    public boolean allowStartMainActivity() { return allowStartMainActivity; }
    public boolean allowPackageCallbacks() { return allowPackageCallbacks; }
    public int maximumListeners() { return maximumListeners; }
    public List<String> visiblePackages() { return visiblePackages; }
    public List<String> hiddenPackages() { return hiddenPackages; }
    public boolean visible(String packageName) {
        return packageName != null && !hiddenPackages.contains(packageName)
                && (visiblePackages.isEmpty() || visiblePackages.contains(packageName));
    }
    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode); out.writeInt(enabled ? 1 : 0); out.writeInt(allowStartMainActivity ? 1 : 0);
        out.writeInt(allowPackageCallbacks ? 1 : 0); out.writeInt(maximumListeners);
        out.writeStringList(visiblePackages); out.writeStringList(hiddenPackages);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualLauncherProfileSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualLauncherProfileSnapshot createFromParcel(Parcel in) { return new VirtualLauncherProfileSnapshot(in); }
        @Override public VirtualLauncherProfileSnapshot[] newArray(int size) { return new VirtualLauncherProfileSnapshot[size]; }
    };
}
