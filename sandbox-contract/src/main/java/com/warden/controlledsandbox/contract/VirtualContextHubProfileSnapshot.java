package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.List;

/** Typed ContextHub and nanoapp policy. */
public final class VirtualContextHubProfileSnapshot implements Parcelable {
    private final String mode;
    private final boolean contextHubAvailable;
    private final boolean allowClientSessions;
    private final boolean allowMessages;
    private final boolean allowNanoAppMutations;
    private final int maximumClients;
    private final List<VirtualContextHubSnapshot> hubs;

    public VirtualContextHubProfileSnapshot(String mode, boolean contextHubAvailable,
            boolean allowClientSessions, boolean allowMessages, boolean allowNanoAppMutations,
            int maximumClients, List<VirtualContextHubSnapshot> hubs) {
        this.mode = VirtualLocationProfileSnapshot.mode(mode);
        this.contextHubAvailable = contextHubAvailable;
        this.allowClientSessions = allowClientSessions;
        this.allowMessages = allowMessages;
        this.allowNanoAppMutations = allowNanoAppMutations;
        if (maximumClients < 0 || maximumClients > 128) {
            throw new IllegalArgumentException("maximumClients must be in [0,128]");
        }
        this.maximumClients = maximumClients;
        List<VirtualContextHubSnapshot> source = hubs == null ? List.of() : hubs;
        if (source.size() > 32) throw new IllegalArgumentException("hubs limit exceeded");
        java.util.LinkedHashSet<Integer> ids = new java.util.LinkedHashSet<>();
        ArrayList<VirtualContextHubSnapshot> checked = new ArrayList<>();
        for (VirtualContextHubSnapshot hub : source) {
            VirtualContextHubSnapshot value = java.util.Objects.requireNonNull(hub, "hub");
            if (!ids.add(value.hubId())) throw new IllegalArgumentException("duplicate hubId");
            checked.add(value);
        }
        this.hubs = List.copyOf(checked);
    }

    private VirtualContextHubProfileSnapshot(Parcel in) {
        this(in.readString(), in.readInt() != 0, in.readInt() != 0, in.readInt() != 0,
                in.readInt() != 0, in.readInt(), in.createTypedArrayList(VirtualContextHubSnapshot.CREATOR));
    }

    public String mode() { return mode; }
    public boolean contextHubAvailable() { return contextHubAvailable; }
    public boolean allowClientSessions() { return allowClientSessions; }
    public boolean allowMessages() { return allowMessages; }
    public boolean allowNanoAppMutations() { return allowNanoAppMutations; }
    public int maximumClients() { return maximumClients; }
    public List<VirtualContextHubSnapshot> hubs() { return hubs; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode);
        out.writeInt(contextHubAvailable ? 1 : 0);
        out.writeInt(allowClientSessions ? 1 : 0);
        out.writeInt(allowMessages ? 1 : 0);
        out.writeInt(allowNanoAppMutations ? 1 : 0);
        out.writeInt(maximumClients);
        out.writeTypedList(hubs);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualContextHubProfileSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualContextHubProfileSnapshot createFromParcel(Parcel in) {
            return new VirtualContextHubProfileSnapshot(in);
        }
        @Override public VirtualContextHubProfileSnapshot[] newArray(int size) {
            return new VirtualContextHubProfileSnapshot[size];
        }
    };
}
