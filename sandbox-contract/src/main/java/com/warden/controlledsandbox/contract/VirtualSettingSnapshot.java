package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.Locale;

/** One virtual Settings namespace value. */
public final class VirtualSettingSnapshot implements Parcelable {
    private final String namespace;
    private final String key;
    private final String value;
    private final long updatedAtMs;

    public VirtualSettingSnapshot(String namespace, String key, String value, long updatedAtMs) {
        String normalized = ContractChecks.requiredText(namespace, "settingsNamespace", 16).toLowerCase(Locale.ROOT);
        if (!VirtualSettingsProfileSnapshot.NAMESPACE_SECURE.equals(normalized)
                && !VirtualSettingsProfileSnapshot.NAMESPACE_SYSTEM.equals(normalized)
                && !VirtualSettingsProfileSnapshot.NAMESPACE_GLOBAL.equals(normalized)) {
            throw new IllegalArgumentException("unsupported settings namespace " + namespace);
        }
        this.namespace = normalized;
        this.key = ContractChecks.requiredText(key, "settingsKey", 128);
        this.value = ContractChecks.optionalText(value, "settingsValue", 16_384);
        this.updatedAtMs = ContractChecks.nonNegative(updatedAtMs, "settingsUpdatedAtMs");
    }
    private VirtualSettingSnapshot(Parcel in) { this(in.readString(), in.readString(), in.readString(), in.readLong()); }
    public String namespace() { return namespace; }
    public String key() { return key; }
    public String value() { return value; }
    public long updatedAtMs() { return updatedAtMs; }
    public String storageKey() { return namespace + ":" + key; }
    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(namespace); out.writeString(key); out.writeString(value); out.writeLong(updatedAtMs);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualSettingSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualSettingSnapshot createFromParcel(Parcel in) { return new VirtualSettingSnapshot(in); }
        @Override public VirtualSettingSnapshot[] newArray(int size) { return new VirtualSettingSnapshot[size]; }
    };
}
