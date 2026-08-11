package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.Locale;

/** Metadata for a sandbox-owned image/video source; no host absolute path crosses the boundary. */
public final class VirtualCameraSourceSnapshot implements Parcelable {
    public static final String NONE = "NONE";
    public static final String IMAGE = "IMAGE";
    public static final String VIDEO = "VIDEO";

    private final String kind;
    private final String relativePath;
    private final String mimeType;
    private final String sha256;
    private final int width;
    private final int height;
    private final int orientationDegrees;
    private final long durationMs;

    public VirtualCameraSourceSnapshot(String kind, String relativePath, String mimeType,
            String sha256, int width, int height, int orientationDegrees, long durationMs) {
        this.kind = normalizeKind(kind);
        this.relativePath = ContractChecks.optionalText(relativePath, "relativePath", 256).trim();
        this.mimeType = ContractChecks.optionalText(mimeType, "mimeType", 96).trim();
        this.sha256 = ContractChecks.optionalText(sha256, "sha256", 64).trim().toLowerCase(Locale.ROOT);
        if (NONE.equals(this.kind)) {
            if (!this.relativePath.isEmpty() || !this.sha256.isEmpty()) {
                throw new IllegalArgumentException("NONE camera source cannot have a file");
            }
        } else {
            if (this.relativePath.isEmpty() || isUnsafePath(this.relativePath)) {
                throw new IllegalArgumentException("camera source relativePath is invalid");
            }
            if (!this.sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("camera source sha256 is invalid");
            }
            if (this.mimeType.isEmpty()) throw new IllegalArgumentException("mimeType is required");
        }
        if (width < 0 || width > 16_384 || height < 0 || height > 16_384) {
            throw new IllegalArgumentException("camera source dimensions are invalid");
        }
        if (orientationDegrees != 0 && orientationDegrees != 90
                && orientationDegrees != 180 && orientationDegrees != 270) {
            throw new IllegalArgumentException("orientationDegrees is invalid");
        }
        if (durationMs < 0L || durationMs > 24L * 60L * 60L * 1000L) {
            throw new IllegalArgumentException("durationMs is invalid");
        }
        this.width = width;
        this.height = height;
        this.orientationDegrees = orientationDegrees;
        this.durationMs = durationMs;
    }

    public static VirtualCameraSourceSnapshot none() {
        return new VirtualCameraSourceSnapshot(NONE, "", "", "", 0, 0, 0, 0L);
    }

    private VirtualCameraSourceSnapshot(Parcel in) {
        this(in.readString(), in.readString(), in.readString(), in.readString(), in.readInt(),
                in.readInt(), in.readInt(), in.readLong());
    }

    public String kind() { return kind; }
    public String relativePath() { return relativePath; }
    public String mimeType() { return mimeType; }
    public String sha256() { return sha256; }
    public int width() { return width; }
    public int height() { return height; }
    public int orientationDegrees() { return orientationDegrees; }
    public long durationMs() { return durationMs; }
    public boolean isConfigured() { return !NONE.equals(kind); }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(kind); out.writeString(relativePath); out.writeString(mimeType);
        out.writeString(sha256); out.writeInt(width); out.writeInt(height);
        out.writeInt(orientationDegrees); out.writeLong(durationMs);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualCameraSourceSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualCameraSourceSnapshot createFromParcel(Parcel in) {
            return new VirtualCameraSourceSnapshot(in);
        }
        @Override public VirtualCameraSourceSnapshot[] newArray(int size) {
            return new VirtualCameraSourceSnapshot[size];
        }
    };

    private static String normalizeKind(String value) {
        String normalized = ContractChecks.requiredText(value, "kind", 16).toUpperCase(Locale.ROOT);
        if (!NONE.equals(normalized) && !IMAGE.equals(normalized) && !VIDEO.equals(normalized)) {
            throw new IllegalArgumentException("unsupported camera source kind: " + value);
        }
        return normalized;
    }
    private static boolean isUnsafePath(String value) {
        return value.startsWith("/") || value.startsWith("\\") || value.contains(":")
                || value.contains("..") || value.contains("\\") || value.contains("//");
    }
}
