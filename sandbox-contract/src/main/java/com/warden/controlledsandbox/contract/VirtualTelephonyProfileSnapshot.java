package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Virtual telephony service profile with bounded multi-SIM slot mapping. */
public final class VirtualTelephonyProfileSnapshot implements Parcelable {
    private final String mode;
    private final int defaultSubscriptionId;
    private final int activeDataSubscriptionId;
    private final boolean voiceCapable;
    private final boolean smsCapable;
    private final boolean emergencyOnly;
    private final List<VirtualTelephonySlotSnapshot> slots;
    private final List<VirtualCellInfoSnapshot> cells;

    public VirtualTelephonyProfileSnapshot(String mode, int defaultSubscriptionId,
            int activeDataSubscriptionId, boolean voiceCapable, boolean smsCapable,
            boolean emergencyOnly, List<VirtualTelephonySlotSnapshot> slots) {
        this(mode, defaultSubscriptionId, activeDataSubscriptionId, voiceCapable, smsCapable,
                emergencyOnly, slots, List.of());
    }

    public VirtualTelephonyProfileSnapshot(String mode, int defaultSubscriptionId,
            int activeDataSubscriptionId, boolean voiceCapable, boolean smsCapable,
            boolean emergencyOnly, List<VirtualTelephonySlotSnapshot> slots,
            List<VirtualCellInfoSnapshot> cells) {
        this.mode = VirtualLocationProfileSnapshot.mode(mode);
        if (defaultSubscriptionId < -1 || activeDataSubscriptionId < -1) {
            throw new IllegalArgumentException("subscription id is invalid");
        }
        this.defaultSubscriptionId = defaultSubscriptionId;
        this.activeDataSubscriptionId = activeDataSubscriptionId;
        this.voiceCapable = voiceCapable;
        this.smsCapable = smsCapable;
        this.emergencyOnly = emergencyOnly;
        List<VirtualTelephonySlotSnapshot> copy = slots == null ? List.of() : new ArrayList<>(slots);
        if (copy.size() > 4 || copy.contains(null)) throw new IllegalArgumentException("telephony slots are invalid");
        Set<Integer> slotIds = new LinkedHashSet<>();
        Set<Integer> subscriptionIds = new LinkedHashSet<>();
        for (VirtualTelephonySlotSnapshot slot : copy) {
            if (!slotIds.add(slot.slotIndex())) throw new IllegalArgumentException("duplicate telephony slot");
            if (slot.subscriptionId() >= 0 && !subscriptionIds.add(slot.subscriptionId())) {
                throw new IllegalArgumentException("duplicate subscription id");
            }
        }
        if (!copy.isEmpty() && defaultSubscriptionId >= 0 && !subscriptionIds.contains(defaultSubscriptionId)) {
            throw new IllegalArgumentException("defaultSubscriptionId has no slot");
        }
        if (!copy.isEmpty() && activeDataSubscriptionId >= 0
                && !subscriptionIds.contains(activeDataSubscriptionId)) {
            throw new IllegalArgumentException("activeDataSubscriptionId has no slot");
        }
        this.slots = Collections.unmodifiableList(copy);
        List<VirtualCellInfoSnapshot> cellCopy = cells == null ? List.of() : new ArrayList<>(cells);
        if (cellCopy.size() > 16 || cellCopy.contains(null)) {
            throw new IllegalArgumentException("telephony cells are invalid");
        }
        this.cells = Collections.unmodifiableList(cellCopy);
    }

    private VirtualTelephonyProfileSnapshot(Parcel in) {
        this(in.readString(), in.readInt(), in.readInt(), in.readInt() != 0,
                in.readInt() != 0, in.readInt() != 0,
                in.createTypedArrayList(VirtualTelephonySlotSnapshot.CREATOR),
                in.createTypedArrayList(VirtualCellInfoSnapshot.CREATOR));
    }

    public String mode() { return mode; }
    public int defaultSubscriptionId() { return defaultSubscriptionId; }
    public int activeDataSubscriptionId() { return activeDataSubscriptionId; }
    public boolean voiceCapable() { return voiceCapable; }
    public boolean smsCapable() { return smsCapable; }
    public boolean emergencyOnly() { return emergencyOnly; }
    public List<VirtualTelephonySlotSnapshot> slots() { return slots; }
    public List<VirtualCellInfoSnapshot> cells() { return cells; }
    public VirtualTelephonySlotSnapshot slotForIndex(int index) {
        for (VirtualTelephonySlotSnapshot slot : slots) if (slot.slotIndex() == index) return slot;
        return null;
    }
    public VirtualTelephonySlotSnapshot slotForSubscription(int subscriptionId) {
        for (VirtualTelephonySlotSnapshot slot : slots) {
            if (slot.subscriptionId() == subscriptionId) return slot;
        }
        return null;
    }
    public VirtualTelephonySlotSnapshot defaultSlot() {
        VirtualTelephonySlotSnapshot bySubscription = slotForSubscription(defaultSubscriptionId);
        return bySubscription != null ? bySubscription : (slots.isEmpty() ? null : slots.get(0));
    }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode); out.writeInt(defaultSubscriptionId); out.writeInt(activeDataSubscriptionId);
        out.writeInt(voiceCapable ? 1 : 0); out.writeInt(smsCapable ? 1 : 0);
        out.writeInt(emergencyOnly ? 1 : 0); out.writeTypedList(slots);
        out.writeTypedList(cells);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualTelephonyProfileSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualTelephonyProfileSnapshot createFromParcel(Parcel in) {
            return new VirtualTelephonyProfileSnapshot(in);
        }
        @Override public VirtualTelephonyProfileSnapshot[] newArray(int size) {
            return new VirtualTelephonyProfileSnapshot[size];
        }
    };
}
