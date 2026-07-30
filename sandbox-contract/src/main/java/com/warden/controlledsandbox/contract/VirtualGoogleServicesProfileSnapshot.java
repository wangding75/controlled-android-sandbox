package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Deterministic Google-service identity and availability policy. */
public final class VirtualGoogleServicesProfileSnapshot implements Parcelable {
    private final String mode;
    private final boolean playServicesAvailable;
    private final String advertisingId;
    private final boolean limitAdTracking;
    private final String appSetId;
    private final String gsfId;
    private final String installationId;
    private final List<String> visibleAccountTypes;
    private final List<String> enabledApis;

    public VirtualGoogleServicesProfileSnapshot(
            String mode,
            boolean playServicesAvailable,
            String advertisingId,
            boolean limitAdTracking,
            String appSetId,
            String gsfId,
            String installationId,
            List<String> visibleAccountTypes,
            List<String> enabledApis) {
        this.mode = VirtualLocationProfileSnapshot.mode(mode);
        this.playServicesAvailable = playServicesAvailable;
        this.advertisingId = ContractChecks.optionalText(
                advertisingId, "advertisingId", 96).trim();
        if (!this.advertisingId.isEmpty()
                && !this.advertisingId.toLowerCase(Locale.ROOT).matches(
                        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("advertisingId is invalid");
        }
        this.limitAdTracking = limitAdTracking;
        this.appSetId = ContractChecks.optionalText(appSetId, "appSetId", 192).trim();
        this.gsfId = ContractChecks.optionalText(gsfId, "gsfId", 64).trim();
        if (!this.gsfId.isEmpty() && !this.gsfId.matches("[0-9a-fA-F]{1,16}")) {
            throw new IllegalArgumentException("gsfId must be hexadecimal");
        }
        this.installationId = ContractChecks.optionalText(
                installationId, "installationId", 192).trim();
        this.visibleAccountTypes = boundedUnique(
                visibleAccountTypes, "visibleAccountTypes", 32, 128);
        this.enabledApis = boundedUnique(enabledApis, "enabledApis", 64, 160);
    }

    private VirtualGoogleServicesProfileSnapshot(Parcel in) {
        this(
                in.readString(),
                in.readInt() != 0,
                in.readString(),
                in.readInt() != 0,
                in.readString(),
                in.readString(),
                in.readString(),
                in.createStringArrayList(),
                in.createStringArrayList());
    }

    public String mode() { return mode; }
    public boolean playServicesAvailable() { return playServicesAvailable; }
    public String advertisingId() { return advertisingId; }
    public boolean limitAdTracking() { return limitAdTracking; }
    public String appSetId() { return appSetId; }
    public String gsfId() { return gsfId; }
    public String installationId() { return installationId; }
    public List<String> visibleAccountTypes() { return visibleAccountTypes; }
    public List<String> enabledApis() { return enabledApis; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode);
        out.writeInt(playServicesAvailable ? 1 : 0);
        out.writeString(advertisingId);
        out.writeInt(limitAdTracking ? 1 : 0);
        out.writeString(appSetId);
        out.writeString(gsfId);
        out.writeString(installationId);
        out.writeStringList(visibleAccountTypes);
        out.writeStringList(enabledApis);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<VirtualGoogleServicesProfileSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualGoogleServicesProfileSnapshot createFromParcel(Parcel in) {
            return new VirtualGoogleServicesProfileSnapshot(in);
        }

        @Override public VirtualGoogleServicesProfileSnapshot[] newArray(int size) {
            return new VirtualGoogleServicesProfileSnapshot[size];
        }
    };

    private static List<String> boundedUnique(
            List<String> values, String field, int maximum, int maximumChars) {
        if (values == null) return List.of();
        if (values.size() > maximum) throw new IllegalArgumentException(field + " limit exceeded");
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = ContractChecks.requiredText(value, field, maximumChars).trim();
            if (!unique.add(normalized)) throw new IllegalArgumentException(field + " contains duplicates");
        }
        return List.copyOf(new ArrayList<>(unique));
    }
}
