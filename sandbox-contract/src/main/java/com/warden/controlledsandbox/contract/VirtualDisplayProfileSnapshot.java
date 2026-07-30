package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Display query and virtual-display creation policy. */
public final class VirtualDisplayProfileSnapshot implements Parcelable {
    private final String mode;
    private final int defaultDisplayId;
    private final boolean allowCreateVirtualDisplay;
    private final int maximumVirtualDisplays;
    private final List<VirtualDisplaySnapshot> displays;

    public VirtualDisplayProfileSnapshot(String mode, int defaultDisplayId,
            boolean allowCreateVirtualDisplay, int maximumVirtualDisplays,
            List<VirtualDisplaySnapshot> displays) {
        this.mode = normalizeMode(mode);
        this.defaultDisplayId = ContractChecks.nonNegative(defaultDisplayId, "defaultDisplayId");
        if (maximumVirtualDisplays < 0 || maximumVirtualDisplays > 16) {
            throw new IllegalArgumentException("maximumVirtualDisplays must be in [0,16]");
        }
        this.maximumVirtualDisplays = maximumVirtualDisplays;
        if (displays == null || displays.isEmpty() || displays.size() > 16) {
            throw new IllegalArgumentException("display list must contain 1..16 entries");
        }
        Map<Integer, VirtualDisplaySnapshot> unique = new LinkedHashMap<>();
        for (VirtualDisplaySnapshot display : displays) {
            if (display == null || unique.put(display.displayId(), display) != null) {
                throw new IllegalArgumentException("duplicate/null display");
            }
        }
        if (!unique.containsKey(this.defaultDisplayId)) {
            throw new IllegalArgumentException("default display is missing");
        }
        this.displays = Collections.unmodifiableList(new ArrayList<>(unique.values()));
        this.allowCreateVirtualDisplay = allowCreateVirtualDisplay;
    }

    private VirtualDisplayProfileSnapshot(Parcel in) {
        this(in.readString(), in.readInt(), in.readInt() != 0, in.readInt(), readDisplays(in));
    }

    public String mode() { return mode; }
    public int defaultDisplayId() { return defaultDisplayId; }
    public boolean allowCreateVirtualDisplay() { return allowCreateVirtualDisplay; }
    public int maximumVirtualDisplays() { return maximumVirtualDisplays; }
    public List<VirtualDisplaySnapshot> displays() { return displays; }
    public VirtualDisplaySnapshot display(int id) {
        for (VirtualDisplaySnapshot display : displays) if (display.displayId() == id) return display;
        return null;
    }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode); out.writeInt(defaultDisplayId);
        out.writeInt(allowCreateVirtualDisplay ? 1 : 0); out.writeInt(maximumVirtualDisplays);
        out.writeTypedList(displays);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualDisplayProfileSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualDisplayProfileSnapshot createFromParcel(Parcel in) {
            return new VirtualDisplayProfileSnapshot(in);
        }
        @Override public VirtualDisplayProfileSnapshot[] newArray(int size) {
            return new VirtualDisplayProfileSnapshot[size];
        }
    };

    private static List<VirtualDisplaySnapshot> readDisplays(Parcel in) {
        ArrayList<VirtualDisplaySnapshot> result = in.createTypedArrayList(VirtualDisplaySnapshot.CREATOR);
        return result == null ? List.of() : result;
    }
    private static String normalizeMode(String value) {
        String normalized = ContractChecks.requiredText(value, "mode", 16).toUpperCase(java.util.Locale.ROOT);
        if (!VirtualWindowPolicySnapshot.MODE_BLOCKED.equals(normalized)
                && !VirtualWindowPolicySnapshot.MODE_STATIC.equals(normalized)
                && !VirtualWindowPolicySnapshot.MODE_HOST.equals(normalized)) {
            throw new IllegalArgumentException("Unsupported display mode: " + value);
        }
        return normalized;
    }
}
