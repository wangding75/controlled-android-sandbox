package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.List;

/** Typed source-side DropBoxManager policy. */
public final class VirtualDropBoxProfileSnapshot implements Parcelable {
    private final String mode;
    private final List<String> enabledTags;
    private final boolean allowWrites;
    private final boolean exposeEntries;
    private final int maximumEntries;
    private final int maximumEntryBytes;

    public VirtualDropBoxProfileSnapshot(
            String mode,
            List<String> enabledTags,
            boolean allowWrites,
            boolean exposeEntries,
            int maximumEntries,
            int maximumEntryBytes) {
        this.mode = VirtualLocationProfileSnapshot.mode(mode);
        this.enabledTags = checkedTags(enabledTags);
        this.allowWrites = allowWrites;
        this.exposeEntries = exposeEntries;
        this.maximumEntries = maximumEntries;
        this.maximumEntryBytes = maximumEntryBytes;
        if (maximumEntries < 0 || maximumEntries > 4096) {
            throw new IllegalArgumentException("maximumEntries is invalid");
        }
        if (maximumEntryBytes < 0 || maximumEntryBytes > 1048576) {
            throw new IllegalArgumentException("maximumEntryBytes is invalid");
        }
    }

    private VirtualDropBoxProfileSnapshot(Parcel in) {
        this(
                in.readString(),
                in.createStringArrayList(),
                in.readInt() != 0,
                in.readInt() != 0,
                in.readInt(),
                in.readInt());
    }

    public String mode() { return mode; }
    public List<String> enabledTags() { return enabledTags; }
    public boolean allowWrites() { return allowWrites; }
    public boolean exposeEntries() { return exposeEntries; }
    public int maximumEntries() { return maximumEntries; }
    public int maximumEntryBytes() { return maximumEntryBytes; }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode);
        out.writeStringList(enabledTags);
        out.writeInt(allowWrites ? 1 : 0);
        out.writeInt(exposeEntries ? 1 : 0);
        out.writeInt(maximumEntries);
        out.writeInt(maximumEntryBytes);
    }

    @Override public int describeContents() { return 0; }

    private static List<String> checkedTags(List<String> values) {
        List<String> source = values == null ? List.of() : values;
        if (source.size() > 128) {
            throw new IllegalArgumentException("enabledTags limit exceeded");
        }
        ArrayList<String> checked = new ArrayList<>(source.size());
        for (String value : source) {
            checked.add(ContractChecks.optionalText(value, "enabledTag", 128));
        }
        return List.copyOf(checked);
    }

    public static final Creator<VirtualDropBoxProfileSnapshot> CREATOR = new Creator<>() {
        @Override
        public VirtualDropBoxProfileSnapshot createFromParcel(Parcel in) {
            return new VirtualDropBoxProfileSnapshot(in);
        }

        @Override
        public VirtualDropBoxProfileSnapshot[] newArray(int size) {
            return new VirtualDropBoxProfileSnapshot[size];
        }
    };
}
