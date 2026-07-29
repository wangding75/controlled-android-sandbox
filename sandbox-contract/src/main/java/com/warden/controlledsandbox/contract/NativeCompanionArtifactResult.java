package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Typed result for companion artifact staging and workspace lifecycle operations. */
public final class NativeCompanionArtifactResult implements Parcelable {
    private final boolean successful;
    private final String operation;
    private final String artifactKind;
    private final String relativePath;
    private final String absolutePath;
    private final String workspaceRoot;
    private final String dataRoot;
    private final String nativeLibraryRoot;
    private final String errorType;
    private final String errorMessage;

    public NativeCompanionArtifactResult(boolean successful, String operation, String artifactKind,
            String relativePath, String absolutePath, String workspaceRoot, String dataRoot,
            String nativeLibraryRoot, String errorType, String errorMessage) {
        this.successful = successful;
        this.operation = value(operation, 64);
        this.artifactKind = value(artifactKind, 32);
        this.relativePath = value(relativePath, 512);
        this.absolutePath = value(absolutePath, 4096);
        this.workspaceRoot = value(workspaceRoot, 4096);
        this.dataRoot = value(dataRoot, 4096);
        this.nativeLibraryRoot = value(nativeLibraryRoot, 4096);
        this.errorType = value(errorType, 128);
        this.errorMessage = value(errorMessage, 1024);
        if (successful && (!this.errorType.isEmpty() || this.workspaceRoot.isEmpty()
                || this.dataRoot.isEmpty() || this.nativeLibraryRoot.isEmpty())) {
            throw new IllegalArgumentException("successful artifact result is incomplete");
        }
    }

    public static NativeCompanionArtifactResult success(String operation, String artifactKind,
            String relativePath, String absolutePath, String workspaceRoot, String dataRoot,
            String nativeLibraryRoot) {
        return new NativeCompanionArtifactResult(true, operation, artifactKind, relativePath,
                absolutePath, workspaceRoot, dataRoot, nativeLibraryRoot, "", "");
    }

    public static NativeCompanionArtifactResult failure(String operation, String type, String message) {
        return new NativeCompanionArtifactResult(false, operation, "", "", "", "", "", "",
                type, message);
    }

    private NativeCompanionArtifactResult(Parcel in) {
        this(in.readInt() != 0, in.readString(), in.readString(), in.readString(), in.readString(),
                in.readString(), in.readString(), in.readString(), in.readString(), in.readString());
    }

    public boolean successful() { return successful; }
    public String operation() { return operation; }
    public String artifactKind() { return artifactKind; }
    public String relativePath() { return relativePath; }
    public String absolutePath() { return absolutePath; }
    public String workspaceRoot() { return workspaceRoot; }
    public String dataRoot() { return dataRoot; }
    public String nativeLibraryRoot() { return nativeLibraryRoot; }
    public String errorType() { return errorType; }
    public String errorMessage() { return errorMessage; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeInt(successful ? 1 : 0);
        out.writeString(operation);
        out.writeString(artifactKind);
        out.writeString(relativePath);
        out.writeString(absolutePath);
        out.writeString(workspaceRoot);
        out.writeString(dataRoot);
        out.writeString(nativeLibraryRoot);
        out.writeString(errorType);
        out.writeString(errorMessage);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<NativeCompanionArtifactResult> CREATOR = new Creator<>() {
        @Override public NativeCompanionArtifactResult createFromParcel(Parcel in) {
            return new NativeCompanionArtifactResult(in);
        }
        @Override public NativeCompanionArtifactResult[] newArray(int size) {
            return new NativeCompanionArtifactResult[size];
        }
    };

    private static String value(String value, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maxLength) throw new IllegalArgumentException("value is too long");
        return normalized;
    }
}
