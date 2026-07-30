package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.List;

/** Printing service and virtual print-job policy for one guest scope. */
public final class VirtualPrintProfileSnapshot implements Parcelable {
    private final String mode;
    private final boolean printingEnabled;
    private final boolean allowPrintJobs;
    private final int maximumActiveJobs;
    private final String defaultPrinterId;
    private final String defaultPrinterName;
    private final List<String> availablePrintServices;

    public VirtualPrintProfileSnapshot(
            String mode, boolean printingEnabled, boolean allowPrintJobs,
            int maximumActiveJobs, String defaultPrinterId, String defaultPrinterName,
            List<String> availablePrintServices) {
        this.mode = VirtualLocationProfileSnapshot.mode(mode);
        this.printingEnabled = printingEnabled;
        this.allowPrintJobs = allowPrintJobs;
        if (maximumActiveJobs < 0 || maximumActiveJobs > 128) {
            throw new IllegalArgumentException("maximumActiveJobs must be in [0,128]");
        }
        this.maximumActiveJobs = maximumActiveJobs;
        this.defaultPrinterId = ContractChecks.optionalText(
                defaultPrinterId, "defaultPrinterId", 192).trim();
        this.defaultPrinterName = ContractChecks.optionalText(
                defaultPrinterName, "defaultPrinterName", 192).trim();
        this.availablePrintServices = ContractLists.unique(
                availablePrintServices, "availablePrintServices", 64, 256, false);
    }

    private VirtualPrintProfileSnapshot(Parcel in) {
        this(in.readString(), in.readInt() != 0, in.readInt() != 0, in.readInt(),
                in.readString(), in.readString(), in.createStringArrayList());
    }

    public String mode() { return mode; }
    public boolean printingEnabled() { return printingEnabled; }
    public boolean allowPrintJobs() { return allowPrintJobs; }
    public int maximumActiveJobs() { return maximumActiveJobs; }
    public String defaultPrinterId() { return defaultPrinterId; }
    public String defaultPrinterName() { return defaultPrinterName; }
    public List<String> availablePrintServices() { return availablePrintServices; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode);
        out.writeInt(printingEnabled ? 1 : 0);
        out.writeInt(allowPrintJobs ? 1 : 0);
        out.writeInt(maximumActiveJobs);
        out.writeString(defaultPrinterId);
        out.writeString(defaultPrinterName);
        out.writeStringList(availablePrintServices);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<VirtualPrintProfileSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualPrintProfileSnapshot createFromParcel(Parcel in) {
            return new VirtualPrintProfileSnapshot(in);
        }
        @Override public VirtualPrintProfileSnapshot[] newArray(int size) {
            return new VirtualPrintProfileSnapshot[size];
        }
    };
}
