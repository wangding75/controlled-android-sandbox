package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** OEM service and system-property projection policy. */
public final class VirtualOemProfileSnapshot implements Parcelable {
    private final String mode;
    private final String vendor;
    private final String skin;
    private final String attributionId;
    private final List<String> propertyKeys;
    private final List<String> propertyValues;
    private final List<String> availableServices;
    private final List<String> blockedPackages;

    public VirtualOemProfileSnapshot(
            String mode,
            String vendor,
            String skin,
            String attributionId,
            List<String> propertyKeys,
            List<String> propertyValues,
            List<String> availableServices,
            List<String> blockedPackages) {
        this.mode = VirtualLocationProfileSnapshot.mode(mode);
        this.vendor = ContractChecks.optionalText(vendor, "vendor", 96).trim();
        this.skin = ContractChecks.optionalText(skin, "skin", 96).trim();
        this.attributionId = ContractChecks.optionalText(
                attributionId, "attributionId", 192).trim();
        this.propertyKeys = boundedUnique(propertyKeys, "propertyKeys", 128, 160, false);
        this.propertyValues = bounded(propertyValues, "propertyValues", 128, 512, true);
        if (this.propertyKeys.size() != this.propertyValues.size()) {
            throw new IllegalArgumentException("property key/value size mismatch");
        }
        this.availableServices = boundedUnique(
                availableServices, "availableServices", 64, 160, false);
        this.blockedPackages = boundedUnique(
                blockedPackages, "blockedPackages", 128, 192, false);
    }

    private VirtualOemProfileSnapshot(Parcel in) {
        this(
                in.readString(),
                in.readString(),
                in.readString(),
                in.readString(),
                in.createStringArrayList(),
                in.createStringArrayList(),
                in.createStringArrayList(),
                in.createStringArrayList());
    }

    public String mode() { return mode; }
    public String vendor() { return vendor; }
    public String skin() { return skin; }
    public String attributionId() { return attributionId; }
    public List<String> propertyKeys() { return propertyKeys; }
    public List<String> propertyValues() { return propertyValues; }
    public List<String> availableServices() { return availableServices; }
    public List<String> blockedPackages() { return blockedPackages; }

    public String property(String key) {
        int index = propertyKeys.indexOf(key);
        return index < 0 ? null : propertyValues.get(index);
    }

    public boolean serviceAvailable(String name) { return availableServices.contains(name); }
    public boolean packageBlocked(String name) { return blockedPackages.contains(name); }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode);
        out.writeString(vendor);
        out.writeString(skin);
        out.writeString(attributionId);
        out.writeStringList(propertyKeys);
        out.writeStringList(propertyValues);
        out.writeStringList(availableServices);
        out.writeStringList(blockedPackages);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<VirtualOemProfileSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualOemProfileSnapshot createFromParcel(Parcel in) {
            return new VirtualOemProfileSnapshot(in);
        }

        @Override public VirtualOemProfileSnapshot[] newArray(int size) {
            return new VirtualOemProfileSnapshot[size];
        }
    };

    private static List<String> boundedUnique(
            List<String> values, String field, int maximum, int maximumChars, boolean optional) {
        List<String> normalized = bounded(values, field, maximum, maximumChars, optional);
        if (new LinkedHashSet<>(normalized).size() != normalized.size()) {
            throw new IllegalArgumentException(field + " contains duplicates");
        }
        return normalized;
    }

    private static List<String> bounded(
            List<String> values, String field, int maximum, int maximumChars, boolean optional) {
        if (values == null) return List.of();
        if (values.size() > maximum) throw new IllegalArgumentException(field + " limit exceeded");
        ArrayList<String> out = new ArrayList<>(values.size());
        for (String value : values) {
            String normalized = optional
                    ? ContractChecks.optionalText(value, field, maximumChars)
                    : ContractChecks.requiredText(value, field, maximumChars);
            out.add(normalized.trim());
        }
        return List.copyOf(out);
    }
}
