package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.List;
import java.util.Locale;

/** NFC adapter, reader and tag policy for one guest scope. */
public final class VirtualNfcProfileSnapshot implements Parcelable {
    private final String mode;
    private final String adapterState;
    private final boolean readerModeAllowed;
    private final boolean cardEmulationAvailable;
    private final boolean ndefPushEnabled;
    private final int maximumReaderSessions;
    private final int maximumTagOperations;
    private final List<String> tagIds;

    public VirtualNfcProfileSnapshot(
            String mode, String adapterState, boolean readerModeAllowed,
            boolean cardEmulationAvailable, boolean ndefPushEnabled,
            int maximumReaderSessions, int maximumTagOperations, List<String> tagIds) {
        this.mode = VirtualLocationProfileSnapshot.mode(mode);
        String state = ContractChecks.requiredText(adapterState, "adapterState", 24)
                .trim().toUpperCase(Locale.ROOT);
        if (!List.of("OFF", "TURNING_ON", "ON", "TURNING_OFF").contains(state)) {
            throw new IllegalArgumentException("unsupported NFC adapter state: " + adapterState);
        }
        this.adapterState = state;
        this.readerModeAllowed = readerModeAllowed;
        this.cardEmulationAvailable = cardEmulationAvailable;
        this.ndefPushEnabled = ndefPushEnabled;
        this.maximumReaderSessions = bounded(maximumReaderSessions, "maximumReaderSessions", 64);
        this.maximumTagOperations = bounded(maximumTagOperations, "maximumTagOperations", 1024);
        this.tagIds = ContractLists.unique(tagIds, "tagIds", 128, 128, false);
    }

    private VirtualNfcProfileSnapshot(Parcel in) {
        this(in.readString(), in.readString(), in.readInt() != 0, in.readInt() != 0,
                in.readInt() != 0, in.readInt(), in.readInt(), in.createStringArrayList());
    }

    public String mode() { return mode; }
    public String adapterState() { return adapterState; }
    public boolean readerModeAllowed() { return readerModeAllowed; }
    public boolean cardEmulationAvailable() { return cardEmulationAvailable; }
    public boolean ndefPushEnabled() { return ndefPushEnabled; }
    public int maximumReaderSessions() { return maximumReaderSessions; }
    public int maximumTagOperations() { return maximumTagOperations; }
    public List<String> tagIds() { return tagIds; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode);
        out.writeString(adapterState);
        out.writeInt(readerModeAllowed ? 1 : 0);
        out.writeInt(cardEmulationAvailable ? 1 : 0);
        out.writeInt(ndefPushEnabled ? 1 : 0);
        out.writeInt(maximumReaderSessions);
        out.writeInt(maximumTagOperations);
        out.writeStringList(tagIds);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<VirtualNfcProfileSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualNfcProfileSnapshot createFromParcel(Parcel in) {
            return new VirtualNfcProfileSnapshot(in);
        }
        @Override public VirtualNfcProfileSnapshot[] newArray(int size) {
            return new VirtualNfcProfileSnapshot[size];
        }
    };

    private static int bounded(int value, String field, int maximum) {
        if (value < 0 || value > maximum) throw new IllegalArgumentException(field + " is invalid");
        return value;
    }
}
