package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.List;

/** Companion-device association and presence policy for one guest scope. */
public final class VirtualCompanionDeviceProfileSnapshot implements Parcelable {
    private final String mode;
    private final boolean allowAssociation;
    private final boolean allowDisassociation;
    private final boolean presenceObservationEnabled;
    private final boolean selfManagedAssociationsAllowed;
    private final int maximumAssociations;
    private final List<String> associationIds;
    private final List<String> approvedDeviceProfiles;

    public VirtualCompanionDeviceProfileSnapshot(
            String mode, boolean allowAssociation, boolean allowDisassociation,
            boolean presenceObservationEnabled, boolean selfManagedAssociationsAllowed,
            int maximumAssociations, List<String> associationIds,
            List<String> approvedDeviceProfiles) {
        this.mode = VirtualLocationProfileSnapshot.mode(mode);
        this.allowAssociation = allowAssociation;
        this.allowDisassociation = allowDisassociation;
        this.presenceObservationEnabled = presenceObservationEnabled;
        this.selfManagedAssociationsAllowed = selfManagedAssociationsAllowed;
        if (maximumAssociations < 0 || maximumAssociations > 128) {
            throw new IllegalArgumentException("maximumAssociations must be in [0,128]");
        }
        this.maximumAssociations = maximumAssociations;
        this.associationIds = ContractLists.unique(
                associationIds, "associationIds", 128, 192, false);
        if (this.associationIds.size() > maximumAssociations) {
            throw new IllegalArgumentException("associationIds exceed maximumAssociations");
        }
        this.approvedDeviceProfiles = ContractLists.unique(
                approvedDeviceProfiles, "approvedDeviceProfiles", 64, 192, false);
    }

    private VirtualCompanionDeviceProfileSnapshot(Parcel in) {
        this(in.readString(), in.readInt() != 0, in.readInt() != 0, in.readInt() != 0,
                in.readInt() != 0, in.readInt(), in.createStringArrayList(),
                in.createStringArrayList());
    }

    public String mode() { return mode; }
    public boolean allowAssociation() { return allowAssociation; }
    public boolean allowDisassociation() { return allowDisassociation; }
    public boolean presenceObservationEnabled() { return presenceObservationEnabled; }
    public boolean selfManagedAssociationsAllowed() { return selfManagedAssociationsAllowed; }
    public int maximumAssociations() { return maximumAssociations; }
    public List<String> associationIds() { return associationIds; }
    public List<String> approvedDeviceProfiles() { return approvedDeviceProfiles; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode);
        out.writeInt(allowAssociation ? 1 : 0);
        out.writeInt(allowDisassociation ? 1 : 0);
        out.writeInt(presenceObservationEnabled ? 1 : 0);
        out.writeInt(selfManagedAssociationsAllowed ? 1 : 0);
        out.writeInt(maximumAssociations);
        out.writeStringList(associationIds);
        out.writeStringList(approvedDeviceProfiles);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<VirtualCompanionDeviceProfileSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualCompanionDeviceProfileSnapshot createFromParcel(Parcel in) {
            return new VirtualCompanionDeviceProfileSnapshot(in);
        }
        @Override public VirtualCompanionDeviceProfileSnapshot[] newArray(int size) {
            return new VirtualCompanionDeviceProfileSnapshot[size];
        }
    };
}
