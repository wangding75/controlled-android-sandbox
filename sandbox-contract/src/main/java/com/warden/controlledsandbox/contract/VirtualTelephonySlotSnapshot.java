package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.Locale;

/** One deterministic virtual subscription/phone slot. */
public final class VirtualTelephonySlotSnapshot implements Parcelable {
    private final int slotIndex;
    private final int subscriptionId;
    private final String imei;
    private final String meid;
    private final String subscriberId;
    private final String simSerialNumber;
    private final String line1Number;
    private final String simOperator;
    private final String networkOperator;
    private final String simCountryIso;
    private final String networkCountryIso;
    private final String carrierName;
    private final int phoneType;
    private final int simState;
    private final int dataNetworkType;
    private final int voiceNetworkType;
    private final boolean dataEnabled;
    private final boolean roaming;

    public VirtualTelephonySlotSnapshot(int slotIndex, int subscriptionId, String imei,
            String meid, String subscriberId, String simSerialNumber, String line1Number,
            String simOperator, String networkOperator, String simCountryIso,
            String networkCountryIso, String carrierName, int phoneType, int simState,
            int dataNetworkType, int voiceNetworkType, boolean dataEnabled, boolean roaming) {
        if (slotIndex < 0 || slotIndex > 3) throw new IllegalArgumentException("slotIndex is invalid");
        if (subscriptionId < -1) throw new IllegalArgumentException("subscriptionId is invalid");
        this.slotIndex = slotIndex;
        this.subscriptionId = subscriptionId;
        this.imei = digits(imei, "imei", 14, 16);
        this.meid = hexadecimal(meid, "meid", 14);
        this.subscriberId = digits(subscriberId, "subscriberId", 5, 16);
        this.simSerialNumber = digits(simSerialNumber, "simSerialNumber", 10, 22);
        this.line1Number = phoneNumber(line1Number);
        this.simOperator = digits(simOperator, "simOperator", 0, 8);
        this.networkOperator = digits(networkOperator, "networkOperator", 0, 8);
        this.simCountryIso = country(simCountryIso, "simCountryIso");
        this.networkCountryIso = country(networkCountryIso, "networkCountryIso");
        this.carrierName = ContractChecks.optionalText(carrierName, "carrierName", 96).trim();
        this.phoneType = nonNegativeBounded(phoneType, "phoneType", 16);
        this.simState = nonNegativeBounded(simState, "simState", 16);
        this.dataNetworkType = nonNegativeBounded(dataNetworkType, "dataNetworkType", 64);
        this.voiceNetworkType = nonNegativeBounded(voiceNetworkType, "voiceNetworkType", 64);
        this.dataEnabled = dataEnabled;
        this.roaming = roaming;
    }

    private VirtualTelephonySlotSnapshot(Parcel in) {
        this(in.readInt(), in.readInt(), in.readString(), in.readString(), in.readString(),
                in.readString(), in.readString(), in.readString(), in.readString(),
                in.readString(), in.readString(), in.readString(), in.readInt(), in.readInt(),
                in.readInt(), in.readInt(), in.readInt() != 0, in.readInt() != 0);
    }

    public int slotIndex() { return slotIndex; }
    public int subscriptionId() { return subscriptionId; }
    public String imei() { return imei; }
    public String meid() { return meid; }
    public String subscriberId() { return subscriberId; }
    public String simSerialNumber() { return simSerialNumber; }
    public String line1Number() { return line1Number; }
    public String simOperator() { return simOperator; }
    public String networkOperator() { return networkOperator; }
    public String simCountryIso() { return simCountryIso; }
    public String networkCountryIso() { return networkCountryIso; }
    public String carrierName() { return carrierName; }
    public int phoneType() { return phoneType; }
    public int simState() { return simState; }
    public int dataNetworkType() { return dataNetworkType; }
    public int voiceNetworkType() { return voiceNetworkType; }
    public boolean dataEnabled() { return dataEnabled; }
    public boolean roaming() { return roaming; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeInt(slotIndex); out.writeInt(subscriptionId); out.writeString(imei);
        out.writeString(meid); out.writeString(subscriberId); out.writeString(simSerialNumber);
        out.writeString(line1Number); out.writeString(simOperator); out.writeString(networkOperator);
        out.writeString(simCountryIso); out.writeString(networkCountryIso); out.writeString(carrierName);
        out.writeInt(phoneType); out.writeInt(simState); out.writeInt(dataNetworkType);
        out.writeInt(voiceNetworkType); out.writeInt(dataEnabled ? 1 : 0); out.writeInt(roaming ? 1 : 0);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualTelephonySlotSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualTelephonySlotSnapshot createFromParcel(Parcel in) {
            return new VirtualTelephonySlotSnapshot(in);
        }
        @Override public VirtualTelephonySlotSnapshot[] newArray(int size) {
            return new VirtualTelephonySlotSnapshot[size];
        }
    };

    private static String digits(String value, String field, int minimum, int maximum) {
        String normalized = ContractChecks.optionalText(value, field, maximum).trim();
        if (normalized.isEmpty() && minimum == 0) return "";
        if (!normalized.matches("[0-9]{" + minimum + "," + maximum + "}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }
    private static String hexadecimal(String value, String field, int maximum) {
        String normalized = ContractChecks.optionalText(value, field, maximum).trim().toUpperCase(Locale.ROOT);
        if (!normalized.isEmpty() && !normalized.matches("[0-9A-F]{1," + maximum + "}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }
    private static String phoneNumber(String value) {
        String normalized = ContractChecks.optionalText(value, "line1Number", 32).trim();
        if (!normalized.isEmpty() && !normalized.matches("\\+?[0-9]{3,31}")) {
            throw new IllegalArgumentException("line1Number is invalid");
        }
        return normalized;
    }
    private static String country(String value, String field) {
        String normalized = ContractChecks.optionalText(value, field, 2).trim().toLowerCase(Locale.ROOT);
        if (!normalized.isEmpty() && !normalized.matches("[a-z]{2}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }
    private static int nonNegativeBounded(int value, String field, int maximum) {
        if (value < 0 || value > maximum) throw new IllegalArgumentException(field + " is invalid");
        return value;
    }
}
