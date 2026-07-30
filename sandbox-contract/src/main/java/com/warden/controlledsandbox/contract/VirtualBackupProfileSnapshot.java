package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.List;

/** Typed source-side BackupManager policy. */
public final class VirtualBackupProfileSnapshot implements Parcelable {
    private final String mode;
    private final boolean backupEnabled;
    private final boolean backupProvisioned;
    private final String currentTransport;
    private final List<String> transports;
    private final boolean allowDataChanged;
    private final boolean allowBackupNow;
    private final boolean allowRestore;

    public VirtualBackupProfileSnapshot(
            String mode,
            boolean backupEnabled,
            boolean backupProvisioned,
            String currentTransport,
            List<String> transports,
            boolean allowDataChanged,
            boolean allowBackupNow,
            boolean allowRestore) {
        this.mode = VirtualLocationProfileSnapshot.mode(mode);
        this.backupEnabled = backupEnabled;
        this.backupProvisioned = backupProvisioned;
        this.currentTransport = ContractChecks.optionalText(
                currentTransport, "currentTransport", 256);
        this.transports = checkedStrings(transports, "transports");
        this.allowDataChanged = allowDataChanged;
        this.allowBackupNow = allowBackupNow;
        this.allowRestore = allowRestore;
    }

    private VirtualBackupProfileSnapshot(Parcel in) {
        this(
                in.readString(),
                in.readInt() != 0,
                in.readInt() != 0,
                in.readString(),
                in.createStringArrayList(),
                in.readInt() != 0,
                in.readInt() != 0,
                in.readInt() != 0);
    }

    public String mode() { return mode; }
    public boolean backupEnabled() { return backupEnabled; }
    public boolean backupProvisioned() { return backupProvisioned; }
    public String currentTransport() { return currentTransport; }
    public List<String> transports() { return transports; }
    public boolean allowDataChanged() { return allowDataChanged; }
    public boolean allowBackupNow() { return allowBackupNow; }
    public boolean allowRestore() { return allowRestore; }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode);
        out.writeInt(backupEnabled ? 1 : 0);
        out.writeInt(backupProvisioned ? 1 : 0);
        out.writeString(currentTransport);
        out.writeStringList(transports);
        out.writeInt(allowDataChanged ? 1 : 0);
        out.writeInt(allowBackupNow ? 1 : 0);
        out.writeInt(allowRestore ? 1 : 0);
    }

    @Override public int describeContents() { return 0; }

    private static List<String> checkedStrings(List<String> values, String field) {
        List<String> source = values == null ? List.of() : values;
        if (source.size() > 128) {
            throw new IllegalArgumentException(field + " limit exceeded");
        }
        ArrayList<String> checked = new ArrayList<>(source.size());
        for (String value : source) {
            checked.add(ContractChecks.optionalText(value, field, 256));
        }
        return List.copyOf(checked);
    }

    public static final Creator<VirtualBackupProfileSnapshot> CREATOR = new Creator<>() {
        @Override
        public VirtualBackupProfileSnapshot createFromParcel(Parcel in) {
            return new VirtualBackupProfileSnapshot(in);
        }

        @Override
        public VirtualBackupProfileSnapshot[] newArray(int size) {
            return new VirtualBackupProfileSnapshot[size];
        }
    };
}
