package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** WebView provider, profile and renderer policy for one package/user scope. */
public final class VirtualWebViewProfileSnapshot implements Parcelable {
    private final String mode;
    private final String providerPackage;
    private final String providerVersion;
    private final String dataDirectorySuffix;
    private final String rendererProcessPrefix;
    private final boolean multiprocessEnabled;
    private final boolean safeBrowsingEnabled;
    private final boolean debuggingAllowed;
    private final int maximumRendererProcesses;

    public VirtualWebViewProfileSnapshot(
            String mode,
            String providerPackage,
            String providerVersion,
            String dataDirectorySuffix,
            String rendererProcessPrefix,
            boolean multiprocessEnabled,
            boolean safeBrowsingEnabled,
            boolean debuggingAllowed,
            int maximumRendererProcesses) {
        this.mode = VirtualLocationProfileSnapshot.mode(mode);
        this.providerPackage = ContractChecks.optionalText(
                providerPackage, "providerPackage", 192).trim();
        this.providerVersion = ContractChecks.optionalText(
                providerVersion, "providerVersion", 96).trim();
        this.dataDirectorySuffix = ContractChecks.optionalText(
                dataDirectorySuffix, "dataDirectorySuffix", 96).trim();
        this.rendererProcessPrefix = ContractChecks.optionalText(
                rendererProcessPrefix, "rendererProcessPrefix", 128).trim();
        if (VirtualLocationProfileSnapshot.MODE_STATIC.equals(this.mode)) {
            if (this.providerPackage.isEmpty()) {
                throw new IllegalArgumentException("providerPackage is required in STATIC mode");
            }
            if (this.dataDirectorySuffix.isEmpty()) {
                throw new IllegalArgumentException("dataDirectorySuffix is required in STATIC mode");
            }
            if (multiprocessEnabled && this.rendererProcessPrefix.isEmpty()) {
                throw new IllegalArgumentException(
                        "rendererProcessPrefix is required when multiprocess is enabled");
            }
        }
        if (maximumRendererProcesses < 0 || maximumRendererProcesses > 32) {
            throw new IllegalArgumentException("maximumRendererProcesses is invalid");
        }
        if (multiprocessEnabled && maximumRendererProcesses < 1) {
            throw new IllegalArgumentException(
                    "maximumRendererProcesses must be positive when multiprocess is enabled");
        }
        this.multiprocessEnabled = multiprocessEnabled;
        this.safeBrowsingEnabled = safeBrowsingEnabled;
        this.debuggingAllowed = debuggingAllowed;
        this.maximumRendererProcesses = maximumRendererProcesses;
    }

    private VirtualWebViewProfileSnapshot(Parcel in) {
        this(
                in.readString(),
                in.readString(),
                in.readString(),
                in.readString(),
                in.readString(),
                in.readInt() != 0,
                in.readInt() != 0,
                in.readInt() != 0,
                in.readInt());
    }

    public String mode() { return mode; }
    public String providerPackage() { return providerPackage; }
    public String providerVersion() { return providerVersion; }
    public String dataDirectorySuffix() { return dataDirectorySuffix; }
    public String rendererProcessPrefix() { return rendererProcessPrefix; }
    public boolean multiprocessEnabled() { return multiprocessEnabled; }
    public boolean safeBrowsingEnabled() { return safeBrowsingEnabled; }
    public boolean debuggingAllowed() { return debuggingAllowed; }
    public int maximumRendererProcesses() { return maximumRendererProcesses; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode);
        out.writeString(providerPackage);
        out.writeString(providerVersion);
        out.writeString(dataDirectorySuffix);
        out.writeString(rendererProcessPrefix);
        out.writeInt(multiprocessEnabled ? 1 : 0);
        out.writeInt(safeBrowsingEnabled ? 1 : 0);
        out.writeInt(debuggingAllowed ? 1 : 0);
        out.writeInt(maximumRendererProcesses);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<VirtualWebViewProfileSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualWebViewProfileSnapshot createFromParcel(Parcel in) {
            return new VirtualWebViewProfileSnapshot(in);
        }

        @Override public VirtualWebViewProfileSnapshot[] newArray(int size) {
            return new VirtualWebViewProfileSnapshot[size];
        }
    };
}
