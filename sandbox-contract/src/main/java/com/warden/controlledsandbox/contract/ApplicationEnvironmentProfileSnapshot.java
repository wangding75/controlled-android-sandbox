package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** User, launcher, shortcut, widget, usage and settings profile for one package/user scope. */
public final class ApplicationEnvironmentProfileSnapshot implements Parcelable {
    private final long policyVersion;
    private final long updatedAtMs;
    private final VirtualUserProfileSnapshot user;
    private final VirtualLauncherProfileSnapshot launcher;
    private final VirtualShortcutPolicySnapshot shortcut;
    private final VirtualWidgetPolicySnapshot appWidget;
    private final VirtualUsageStatsPolicySnapshot usageStats;
    private final VirtualSettingsProfileSnapshot settings;

    public ApplicationEnvironmentProfileSnapshot(long policyVersion, long updatedAtMs,
            VirtualUserProfileSnapshot user, VirtualLauncherProfileSnapshot launcher,
            VirtualShortcutPolicySnapshot shortcut, VirtualWidgetPolicySnapshot appWidget,
            VirtualUsageStatsPolicySnapshot usageStats, VirtualSettingsProfileSnapshot settings) {
        if (policyVersion < 1L || updatedAtMs < 0L) {
            throw new IllegalArgumentException("application-environment profile version/time is invalid");
        }
        this.policyVersion = policyVersion;
        this.updatedAtMs = updatedAtMs;
        this.user = java.util.Objects.requireNonNull(user, "user");
        this.launcher = java.util.Objects.requireNonNull(launcher, "launcher");
        this.shortcut = java.util.Objects.requireNonNull(shortcut, "shortcut");
        this.appWidget = java.util.Objects.requireNonNull(appWidget, "appWidget");
        this.usageStats = java.util.Objects.requireNonNull(usageStats, "usageStats");
        this.settings = java.util.Objects.requireNonNull(settings, "settings");
    }
    private ApplicationEnvironmentProfileSnapshot(Parcel in) {
        this(in.readLong(), in.readLong(),
                in.readParcelable(VirtualUserProfileSnapshot.class.getClassLoader()),
                in.readParcelable(VirtualLauncherProfileSnapshot.class.getClassLoader()),
                in.readParcelable(VirtualShortcutPolicySnapshot.class.getClassLoader()),
                in.readParcelable(VirtualWidgetPolicySnapshot.class.getClassLoader()),
                in.readParcelable(VirtualUsageStatsPolicySnapshot.class.getClassLoader()),
                in.readParcelable(VirtualSettingsProfileSnapshot.class.getClassLoader()));
    }
    public long policyVersion() { return policyVersion; }
    public long updatedAtMs() { return updatedAtMs; }
    public VirtualUserProfileSnapshot user() { return user; }
    public VirtualLauncherProfileSnapshot launcher() { return launcher; }
    public VirtualShortcutPolicySnapshot shortcut() { return shortcut; }
    public VirtualWidgetPolicySnapshot appWidget() { return appWidget; }
    public VirtualUsageStatsPolicySnapshot usageStats() { return usageStats; }
    public VirtualSettingsProfileSnapshot settings() { return settings; }
    public ApplicationEnvironmentProfileSnapshot withVersion(long version, long updatedAt) {
        return new ApplicationEnvironmentProfileSnapshot(version, updatedAt,
                user, launcher, shortcut, appWidget, usageStats, settings);
    }
    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeLong(policyVersion); out.writeLong(updatedAtMs); out.writeParcelable(user, flags);
        out.writeParcelable(launcher, flags); out.writeParcelable(shortcut, flags);
        out.writeParcelable(appWidget, flags); out.writeParcelable(usageStats, flags);
        out.writeParcelable(settings, flags);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<ApplicationEnvironmentProfileSnapshot> CREATOR = new Creator<>() {
        @Override public ApplicationEnvironmentProfileSnapshot createFromParcel(Parcel in) {
            return new ApplicationEnvironmentProfileSnapshot(in);
        }
        @Override public ApplicationEnvironmentProfileSnapshot[] newArray(int size) {
            return new ApplicationEnvironmentProfileSnapshot[size];
        }
    };
}
