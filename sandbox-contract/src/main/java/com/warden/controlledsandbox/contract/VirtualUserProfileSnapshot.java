package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Guest-visible UserManager identity and restriction projection. */
public final class VirtualUserProfileSnapshot implements Parcelable {
    private final String mode;
    private final int userId;
    private final long serialNumber;
    private final String name;
    private final int flags;
    private final boolean running;
    private final boolean unlocked;
    private final boolean quietMode;
    private final List<String> restrictions;
    private final List<String> applicationRestrictionKeys;
    private final List<String> applicationRestrictionValues;

    public VirtualUserProfileSnapshot(String mode, int userId, long serialNumber, String name, int flags,
            boolean running, boolean unlocked, boolean quietMode, List<String> restrictions,
            List<String> applicationRestrictionKeys, List<String> applicationRestrictionValues) {
        this.mode = VirtualLocationProfileSnapshot.mode(mode);
        this.userId = ContractChecks.nonNegative(userId, "userId");
        this.serialNumber = ContractChecks.nonNegative(serialNumber, "serialNumber");
        this.name = ContractChecks.requiredText(name, "userName", 128);
        this.flags = flags;
        this.running = running;
        this.unlocked = unlocked;
        this.quietMode = quietMode;
        this.restrictions = strings(restrictions, "restrictions", 128, 128);
        this.applicationRestrictionKeys = strings(applicationRestrictionKeys,
                "applicationRestrictionKeys", 128, 128);
        this.applicationRestrictionValues = strings(applicationRestrictionValues,
                "applicationRestrictionValues", 128, 1024);
        if (this.applicationRestrictionKeys.size() != this.applicationRestrictionValues.size()) {
            throw new IllegalArgumentException("application restrictions are misaligned");
        }
    }
    private VirtualUserProfileSnapshot(Parcel in) {
        this(in.readString(), in.readInt(), in.readLong(), in.readString(), in.readInt(),
                in.readInt() != 0, in.readInt() != 0, in.readInt() != 0,
                in.createStringArrayList(), in.createStringArrayList(), in.createStringArrayList());
    }
    public String mode() { return mode; }
    public int userId() { return userId; }
    public long serialNumber() { return serialNumber; }
    public String name() { return name; }
    public int flags() { return flags; }
    public boolean running() { return running; }
    public boolean unlocked() { return unlocked; }
    public boolean quietMode() { return quietMode; }
    public List<String> restrictions() { return restrictions; }
    public List<String> applicationRestrictionKeys() { return applicationRestrictionKeys; }
    public List<String> applicationRestrictionValues() { return applicationRestrictionValues; }
    public boolean hasRestriction(String key) { return key != null && restrictions.contains(key); }
    public String applicationRestriction(String key) {
        if (key == null) return null;
        int index = applicationRestrictionKeys.indexOf(key);
        return index < 0 ? null : applicationRestrictionValues.get(index);
    }
    @Override public void writeToParcel(Parcel out, int parcelFlags) {
        out.writeString(mode); out.writeInt(userId); out.writeLong(serialNumber); out.writeString(name);
        out.writeInt(flags); out.writeInt(running ? 1 : 0); out.writeInt(unlocked ? 1 : 0);
        out.writeInt(quietMode ? 1 : 0); out.writeStringList(restrictions);
        out.writeStringList(applicationRestrictionKeys); out.writeStringList(applicationRestrictionValues);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualUserProfileSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualUserProfileSnapshot createFromParcel(Parcel in) { return new VirtualUserProfileSnapshot(in); }
        @Override public VirtualUserProfileSnapshot[] newArray(int size) { return new VirtualUserProfileSnapshot[size]; }
    };
    static List<String> strings(List<String> source, String field, int maxItems, int maxLength) {
        List<String> copy = source == null ? List.of() : new ArrayList<>(source);
        if (copy.size() > maxItems || copy.contains(null)) throw new IllegalArgumentException(field + " is invalid");
        List<String> normalized = new ArrayList<>();
        for (String value : copy) {
            String item = ContractChecks.requiredText(value, field, maxLength);
            if (!normalized.contains(item)) normalized.add(item);
        }
        return Collections.unmodifiableList(normalized);
    }
}
