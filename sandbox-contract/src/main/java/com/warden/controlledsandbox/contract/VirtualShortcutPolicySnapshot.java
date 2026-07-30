package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** ShortcutManager quota and mutation policy. */
public final class VirtualShortcutPolicySnapshot implements Parcelable {
    private final String mode;
    private final boolean enabled;
    private final int maximumShortcutsPerActivity;
    private final int maximumDynamicShortcuts;
    private final int remainingCallCount;
    private final long rateLimitResetTimeMs;
    private final boolean allowPinRequests;
    private final boolean allowLongLived;

    public VirtualShortcutPolicySnapshot(String mode, boolean enabled, int maximumShortcutsPerActivity,
            int maximumDynamicShortcuts, int remainingCallCount, long rateLimitResetTimeMs,
            boolean allowPinRequests, boolean allowLongLived) {
        this.mode = VirtualLocationProfileSnapshot.mode(mode);
        this.enabled = enabled;
        if (maximumShortcutsPerActivity < 1 || maximumShortcutsPerActivity > 64
                || maximumDynamicShortcuts < 1 || maximumDynamicShortcuts > 256) {
            throw new IllegalArgumentException("shortcut limits are invalid");
        }
        this.maximumShortcutsPerActivity = maximumShortcutsPerActivity;
        this.maximumDynamicShortcuts = maximumDynamicShortcuts;
        this.remainingCallCount = ContractChecks.nonNegative(remainingCallCount, "remainingCallCount");
        this.rateLimitResetTimeMs = ContractChecks.nonNegative(rateLimitResetTimeMs, "rateLimitResetTimeMs");
        this.allowPinRequests = allowPinRequests;
        this.allowLongLived = allowLongLived;
    }
    private VirtualShortcutPolicySnapshot(Parcel in) {
        this(in.readString(), in.readInt() != 0, in.readInt(), in.readInt(), in.readInt(), in.readLong(),
                in.readInt() != 0, in.readInt() != 0);
    }
    public String mode() { return mode; }
    public boolean enabled() { return enabled; }
    public int maximumShortcutsPerActivity() { return maximumShortcutsPerActivity; }
    public int maximumDynamicShortcuts() { return maximumDynamicShortcuts; }
    public int remainingCallCount() { return remainingCallCount; }
    public long rateLimitResetTimeMs() { return rateLimitResetTimeMs; }
    public boolean allowPinRequests() { return allowPinRequests; }
    public boolean allowLongLived() { return allowLongLived; }
    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode); out.writeInt(enabled ? 1 : 0); out.writeInt(maximumShortcutsPerActivity);
        out.writeInt(maximumDynamicShortcuts); out.writeInt(remainingCallCount); out.writeLong(rateLimitResetTimeMs);
        out.writeInt(allowPinRequests ? 1 : 0); out.writeInt(allowLongLived ? 1 : 0);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualShortcutPolicySnapshot> CREATOR = new Creator<>() {
        @Override public VirtualShortcutPolicySnapshot createFromParcel(Parcel in) { return new VirtualShortcutPolicySnapshot(in); }
        @Override public VirtualShortcutPolicySnapshot[] newArray(int size) { return new VirtualShortcutPolicySnapshot[size]; }
    };
}
