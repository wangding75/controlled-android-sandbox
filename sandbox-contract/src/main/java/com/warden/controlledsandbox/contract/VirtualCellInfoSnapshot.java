package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.Locale;

/** Consistent virtual serving-cell projection for one guest scope. */
public final class VirtualCellInfoSnapshot implements Parcelable {
    public static final String GSM = "GSM";
    public static final String WCDMA = "WCDMA";
    public static final String LTE = "LTE";
    public static final String NR = "NR";

    private final String technology;
    private final int mcc;
    private final int mnc;
    private final int lac;
    private final int tac;
    private final long cid;
    private final int pci;
    private final int arfcn;
    private final boolean registered;
    private final int signalLevel;

    public VirtualCellInfoSnapshot(String technology, int mcc, int mnc, int lac, int tac,
            long cid, int pci, int arfcn, boolean registered, int signalLevel) {
        this.technology = normalizeTechnology(technology);
        if (mcc < 0 || mcc > 999 || mnc < 0 || mnc > 999 || lac < 0 || lac > 65535
                || tac < 0 || tac > 1_048_575 || cid < 0L || cid > 68_719_476_735L
                || pci < 0 || pci > 1007 || arfcn < 0 || arfcn > 1_000_000
                || signalLevel < -200 || signalLevel > 100) {
            throw new IllegalArgumentException("virtual cell fields are invalid");
        }
        this.mcc = mcc;
        this.mnc = mnc;
        this.lac = lac;
        this.tac = tac;
        this.cid = cid;
        this.pci = pci;
        this.arfcn = arfcn;
        this.registered = registered;
        this.signalLevel = signalLevel;
    }

    private VirtualCellInfoSnapshot(Parcel in) {
        this(in.readString(), in.readInt(), in.readInt(), in.readInt(), in.readInt(),
                in.readLong(), in.readInt(), in.readInt(), in.readInt() != 0, in.readInt());
    }

    public String technology() { return technology; }
    public int mcc() { return mcc; }
    public int mnc() { return mnc; }
    public int lac() { return lac; }
    public int tac() { return tac; }
    public long cid() { return cid; }
    public int pci() { return pci; }
    public int arfcn() { return arfcn; }
    public boolean registered() { return registered; }
    public int signalLevel() { return signalLevel; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(technology); out.writeInt(mcc); out.writeInt(mnc); out.writeInt(lac);
        out.writeInt(tac); out.writeLong(cid); out.writeInt(pci); out.writeInt(arfcn);
        out.writeInt(registered ? 1 : 0); out.writeInt(signalLevel);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualCellInfoSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualCellInfoSnapshot createFromParcel(Parcel in) {
            return new VirtualCellInfoSnapshot(in);
        }
        @Override public VirtualCellInfoSnapshot[] newArray(int size) {
            return new VirtualCellInfoSnapshot[size];
        }
    };

    private static String normalizeTechnology(String value) {
        String normalized = ContractChecks.requiredText(value, "technology", 16)
                .toUpperCase(Locale.ROOT);
        if (!GSM.equals(normalized) && !WCDMA.equals(normalized)
                && !LTE.equals(normalized) && !NR.equals(normalized)) {
            throw new IllegalArgumentException("unsupported cell technology: " + value);
        }
        return normalized;
    }
}
