package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Typed source-side MediaRouter policy. */
public final class VirtualMediaRouterProfileSnapshot implements Parcelable {
    private final String mode;
    private final String selectedRouteId;
    private final String selectedRouteName;
    private final int routeType;
    private final int routeVolume;
    private final int routeVolumeMax;
    private final boolean allowRouteChanges;
    private final int maximumClients;

    public VirtualMediaRouterProfileSnapshot(
            String mode,
            String selectedRouteId,
            String selectedRouteName,
            int routeType,
            int routeVolume,
            int routeVolumeMax,
            boolean allowRouteChanges,
            int maximumClients) {
        this.mode = VirtualLocationProfileSnapshot.mode(mode);
        this.selectedRouteId = ContractChecks.optionalText(
                selectedRouteId, "selectedRouteId", 256);
        this.selectedRouteName = ContractChecks.optionalText(
                selectedRouteName, "selectedRouteName", 256);
        this.routeType = routeType;
        this.routeVolume = routeVolume;
        this.routeVolumeMax = routeVolumeMax;
        this.allowRouteChanges = allowRouteChanges;
        this.maximumClients = maximumClients;
        if (routeVolume < 0) {
            throw new IllegalArgumentException("routeVolume is invalid");
        }
        if (routeVolumeMax < 0 || routeVolumeMax > 1000) {
            throw new IllegalArgumentException("routeVolumeMax is invalid");
        }
        if (maximumClients < 0 || maximumClients > 128) {
            throw new IllegalArgumentException("maximumClients is invalid");
        }
        if (routeVolume > routeVolumeMax) {
            throw new IllegalArgumentException("routeVolume exceeds routeVolumeMax");
        }
    }

    private VirtualMediaRouterProfileSnapshot(Parcel in) {
        this(
                in.readString(),
                in.readString(),
                in.readString(),
                in.readInt(),
                in.readInt(),
                in.readInt(),
                in.readInt() != 0,
                in.readInt());
    }

    public String mode() { return mode; }
    public String selectedRouteId() { return selectedRouteId; }
    public String selectedRouteName() { return selectedRouteName; }
    public int routeType() { return routeType; }
    public int routeVolume() { return routeVolume; }
    public int routeVolumeMax() { return routeVolumeMax; }
    public boolean allowRouteChanges() { return allowRouteChanges; }
    public int maximumClients() { return maximumClients; }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode);
        out.writeString(selectedRouteId);
        out.writeString(selectedRouteName);
        out.writeInt(routeType);
        out.writeInt(routeVolume);
        out.writeInt(routeVolumeMax);
        out.writeInt(allowRouteChanges ? 1 : 0);
        out.writeInt(maximumClients);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<VirtualMediaRouterProfileSnapshot> CREATOR = new Creator<>() {
        @Override
        public VirtualMediaRouterProfileSnapshot createFromParcel(Parcel in) {
            return new VirtualMediaRouterProfileSnapshot(in);
        }

        @Override
        public VirtualMediaRouterProfileSnapshot[] newArray(int size) {
            return new VirtualMediaRouterProfileSnapshot[size];
        }
    };
}
