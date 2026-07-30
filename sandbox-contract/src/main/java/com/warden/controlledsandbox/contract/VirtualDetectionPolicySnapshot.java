package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Bounded host-leak prevention and virtual-environment detection policy. */
public final class VirtualDetectionPolicySnapshot implements Parcelable {
    private final String mode;
    private final boolean hideHostPackage;
    private final boolean sanitizeProcFiles;
    private final boolean maskDebugger;
    private final boolean maskRootArtifacts;
    private final boolean sanitizeStackTraces;
    private final int maximumSuspiciousQueries;
    private final List<String> hiddenPathPrefixes;
    private final List<String> hiddenClassPrefixes;
    private final List<String> hiddenPackageNames;

    public VirtualDetectionPolicySnapshot(
            String mode,
            boolean hideHostPackage,
            boolean sanitizeProcFiles,
            boolean maskDebugger,
            boolean maskRootArtifacts,
            boolean sanitizeStackTraces,
            int maximumSuspiciousQueries,
            List<String> hiddenPathPrefixes,
            List<String> hiddenClassPrefixes,
            List<String> hiddenPackageNames) {
        this.mode = VirtualLocationProfileSnapshot.mode(mode);
        this.hideHostPackage = hideHostPackage;
        this.sanitizeProcFiles = sanitizeProcFiles;
        this.maskDebugger = maskDebugger;
        this.maskRootArtifacts = maskRootArtifacts;
        this.sanitizeStackTraces = sanitizeStackTraces;
        if (maximumSuspiciousQueries < 0 || maximumSuspiciousQueries > 100000) {
            throw new IllegalArgumentException("maximumSuspiciousQueries is invalid");
        }
        this.maximumSuspiciousQueries = maximumSuspiciousQueries;
        this.hiddenPathPrefixes = boundedUnique(
                hiddenPathPrefixes, "hiddenPathPrefixes", 128, 256, true);
        for (String path : this.hiddenPathPrefixes) {
            if (!path.startsWith("/")) {
                throw new IllegalArgumentException("hiddenPathPrefixes must be absolute");
            }
        }
        this.hiddenClassPrefixes = boundedUnique(
                hiddenClassPrefixes, "hiddenClassPrefixes", 128, 256, false);
        this.hiddenPackageNames = boundedUnique(
                hiddenPackageNames, "hiddenPackageNames", 128, 192, false);
    }

    private VirtualDetectionPolicySnapshot(Parcel in) {
        this(
                in.readString(),
                in.readInt() != 0,
                in.readInt() != 0,
                in.readInt() != 0,
                in.readInt() != 0,
                in.readInt() != 0,
                in.readInt(),
                in.createStringArrayList(),
                in.createStringArrayList(),
                in.createStringArrayList());
    }

    public String mode() { return mode; }
    public boolean hideHostPackage() { return hideHostPackage; }
    public boolean sanitizeProcFiles() { return sanitizeProcFiles; }
    public boolean maskDebugger() { return maskDebugger; }
    public boolean maskRootArtifacts() { return maskRootArtifacts; }
    public boolean sanitizeStackTraces() { return sanitizeStackTraces; }
    public int maximumSuspiciousQueries() { return maximumSuspiciousQueries; }
    public List<String> hiddenPathPrefixes() { return hiddenPathPrefixes; }
    public List<String> hiddenClassPrefixes() { return hiddenClassPrefixes; }
    public List<String> hiddenPackageNames() { return hiddenPackageNames; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode);
        out.writeInt(hideHostPackage ? 1 : 0);
        out.writeInt(sanitizeProcFiles ? 1 : 0);
        out.writeInt(maskDebugger ? 1 : 0);
        out.writeInt(maskRootArtifacts ? 1 : 0);
        out.writeInt(sanitizeStackTraces ? 1 : 0);
        out.writeInt(maximumSuspiciousQueries);
        out.writeStringList(hiddenPathPrefixes);
        out.writeStringList(hiddenClassPrefixes);
        out.writeStringList(hiddenPackageNames);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<VirtualDetectionPolicySnapshot> CREATOR = new Creator<>() {
        @Override public VirtualDetectionPolicySnapshot createFromParcel(Parcel in) {
            return new VirtualDetectionPolicySnapshot(in);
        }

        @Override public VirtualDetectionPolicySnapshot[] newArray(int size) {
            return new VirtualDetectionPolicySnapshot[size];
        }
    };

    private static List<String> boundedUnique(
            List<String> values,
            String field,
            int maximum,
            int maximumChars,
            boolean allowWhitespace) {
        if (values == null) return List.of();
        if (values.size() > maximum) throw new IllegalArgumentException(field + " limit exceeded");
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = ContractChecks.requiredText(value, field, maximumChars).trim();
            if (!allowWhitespace && normalized.indexOf(' ') >= 0) {
                throw new IllegalArgumentException(field + " contains whitespace");
            }
            if (!unique.add(normalized)) throw new IllegalArgumentException(field + " contains duplicates");
        }
        return List.copyOf(new ArrayList<>(unique));
    }
}
