package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.List;
import java.util.Locale;

/** Settings/ContentService namespace and write policy. */
public final class VirtualSettingsProfileSnapshot implements Parcelable {
    public static final String NAMESPACE_SECURE = "secure";
    public static final String NAMESPACE_SYSTEM = "system";
    public static final String NAMESPACE_GLOBAL = "global";
    private final String mode;
    private final boolean allowSecureWrites;
    private final boolean allowSystemWrites;
    private final boolean allowGlobalWrites;
    private final int maximumEntries;
    private final List<String> allowedNamespaces;
    private final List<String> blockedKeys;

    public VirtualSettingsProfileSnapshot(String mode, boolean allowSecureWrites, boolean allowSystemWrites,
            boolean allowGlobalWrites, int maximumEntries, List<String> allowedNamespaces,
            List<String> blockedKeys) {
        this.mode = VirtualLocationProfileSnapshot.mode(mode);
        this.allowSecureWrites = allowSecureWrites;
        this.allowSystemWrites = allowSystemWrites;
        this.allowGlobalWrites = allowGlobalWrites;
        if (maximumEntries < 0 || maximumEntries > 4096) {
            throw new IllegalArgumentException("maximumEntries is invalid");
        }
        this.maximumEntries = maximumEntries;
        this.allowedNamespaces = normalizedNamespaces(allowedNamespaces);
        this.blockedKeys = VirtualUserProfileSnapshot.strings(blockedKeys, "blockedSettingKeys", 512, 128);
    }
    private VirtualSettingsProfileSnapshot(Parcel in) {
        this(in.readString(), in.readInt() != 0, in.readInt() != 0, in.readInt() != 0,
                in.readInt(), in.createStringArrayList(), in.createStringArrayList());
    }
    public String mode() { return mode; }
    public boolean allowSecureWrites() { return allowSecureWrites; }
    public boolean allowSystemWrites() { return allowSystemWrites; }
    public boolean allowGlobalWrites() { return allowGlobalWrites; }
    public int maximumEntries() { return maximumEntries; }
    public List<String> allowedNamespaces() { return allowedNamespaces; }
    public List<String> blockedKeys() { return blockedKeys; }
    public boolean namespaceAllowed(String namespace) {
        return namespace != null && allowedNamespaces.contains(namespace.toLowerCase(Locale.ROOT));
    }
    public boolean writeAllowed(String namespace) {
        if (!namespaceAllowed(namespace)) return false;
        return switch (namespace.toLowerCase(Locale.ROOT)) {
            case NAMESPACE_SECURE -> allowSecureWrites;
            case NAMESPACE_SYSTEM -> allowSystemWrites;
            case NAMESPACE_GLOBAL -> allowGlobalWrites;
            default -> false;
        };
    }
    public boolean keyBlocked(String key) { return key != null && blockedKeys.contains(key); }
    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode); out.writeInt(allowSecureWrites ? 1 : 0); out.writeInt(allowSystemWrites ? 1 : 0);
        out.writeInt(allowGlobalWrites ? 1 : 0); out.writeInt(maximumEntries);
        out.writeStringList(allowedNamespaces); out.writeStringList(blockedKeys);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualSettingsProfileSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualSettingsProfileSnapshot createFromParcel(Parcel in) { return new VirtualSettingsProfileSnapshot(in); }
        @Override public VirtualSettingsProfileSnapshot[] newArray(int size) { return new VirtualSettingsProfileSnapshot[size]; }
    };
    private static List<String> normalizedNamespaces(List<String> source) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (String value : VirtualUserProfileSnapshot.strings(source, "allowedNamespaces", 3, 16)) {
            String normalized = value.toLowerCase(Locale.ROOT);
            if (!NAMESPACE_SECURE.equals(normalized) && !NAMESPACE_SYSTEM.equals(normalized)
                    && !NAMESPACE_GLOBAL.equals(normalized)) {
                throw new IllegalArgumentException("unsupported settings namespace " + value);
            }
            if (!out.contains(normalized)) out.add(normalized);
        }
        return java.util.Collections.unmodifiableList(out);
    }
}
