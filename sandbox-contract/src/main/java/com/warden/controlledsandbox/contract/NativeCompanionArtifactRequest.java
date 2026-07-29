package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Typed, bounded metadata for one file staged into the 32-bit companion workspace. */
public final class NativeCompanionArtifactRequest implements Parcelable {
    public static final String BASE_APK = "BASE_APK";
    public static final String SPLIT_APK = "SPLIT_APK";
    public static final String NATIVE_LIBRARY = "NATIVE_LIBRARY";
    private static final long MAX_ARTIFACT_BYTES = 512L * 1024L * 1024L;

    private final int protocol;
    private final String sessionId;
    private final long generation;
    private final int virtualUserId;
    private final String packageName;
    private final String packageRevision;
    private final String requestedAbi;
    private final String artifactKind;
    private final String relativePath;
    private final String sha256;
    private final long sizeBytes;

    public NativeCompanionArtifactRequest(int protocol, String sessionId, long generation,
            int virtualUserId, String packageName, String packageRevision, String requestedAbi,
            String artifactKind, String relativePath, String sha256, long sizeBytes) {
        if (protocol < 1) throw new IllegalArgumentException("protocol must be positive");
        if (generation < 1) throw new IllegalArgumentException("generation must be positive");
        if (virtualUserId < 0) throw new IllegalArgumentException("virtualUserId must be non-negative");
        if (sizeBytes < 0 || sizeBytes > MAX_ARTIFACT_BYTES) {
            throw new IllegalArgumentException("artifact size is invalid");
        }
        this.protocol = protocol;
        this.sessionId = required(sessionId, "sessionId", 128);
        this.generation = generation;
        this.virtualUserId = virtualUserId;
        this.packageName = packageName(packageName);
        this.packageRevision = required(packageRevision, "packageRevision", 160);
        this.requestedAbi = abi(requestedAbi);
        this.artifactKind = kind(artifactKind);
        this.relativePath = relativePath(relativePath, this.artifactKind);
        this.sha256 = sha256(sha256);
        this.sizeBytes = sizeBytes;
    }

    private NativeCompanionArtifactRequest(Parcel in) {
        this(in.readInt(), in.readString(), in.readLong(), in.readInt(), in.readString(),
                in.readString(), in.readString(), in.readString(), in.readString(),
                in.readString(), in.readLong());
    }

    public int protocol() { return protocol; }
    public String sessionId() { return sessionId; }
    public long generation() { return generation; }
    public int virtualUserId() { return virtualUserId; }
    public String packageName() { return packageName; }
    public String packageRevision() { return packageRevision; }
    public String requestedAbi() { return requestedAbi; }
    public String artifactKind() { return artifactKind; }
    public String relativePath() { return relativePath; }
    public String sha256() { return sha256; }
    public long sizeBytes() { return sizeBytes; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeInt(protocol);
        out.writeString(sessionId);
        out.writeLong(generation);
        out.writeInt(virtualUserId);
        out.writeString(packageName);
        out.writeString(packageRevision);
        out.writeString(requestedAbi);
        out.writeString(artifactKind);
        out.writeString(relativePath);
        out.writeString(sha256);
        out.writeLong(sizeBytes);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<NativeCompanionArtifactRequest> CREATOR = new Creator<>() {
        @Override public NativeCompanionArtifactRequest createFromParcel(Parcel in) {
            return new NativeCompanionArtifactRequest(in);
        }
        @Override public NativeCompanionArtifactRequest[] newArray(int size) {
            return new NativeCompanionArtifactRequest[size];
        }
    };

    private static String required(String value, String name, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " is required");
        if (normalized.length() > maxLength) throw new IllegalArgumentException(name + " is too long");
        return normalized;
    }

    private static String packageName(String value) {
        String normalized = required(value, "packageName", 255);
        if (!normalized.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) {
            throw new IllegalArgumentException("packageName is invalid");
        }
        return normalized;
    }

    private static String abi(String value) {
        String normalized = required(value, "requestedAbi", 32);
        if (!"armeabi-v7a".equals(normalized) && !"x86".equals(normalized)) {
            throw new IllegalArgumentException("requestedAbi must be 32-bit");
        }
        return normalized;
    }

    private static String kind(String value) {
        String normalized = required(value, "artifactKind", 32);
        if (!BASE_APK.equals(normalized) && !SPLIT_APK.equals(normalized)
                && !NATIVE_LIBRARY.equals(normalized)) {
            throw new IllegalArgumentException("unsupported artifact kind: " + normalized);
        }
        return normalized;
    }

    private static String relativePath(String value, String artifactKind) {
        String normalized = required(value, "relativePath", 512).replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("../")
                || normalized.equals("..") || normalized.contains("//")
                || normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("relativePath is unsafe");
        }
        if (BASE_APK.equals(artifactKind) && !"base.apk".equals(normalized)) {
            throw new IllegalArgumentException("base APK path must be base.apk");
        }
        if (SPLIT_APK.equals(artifactKind) && (!normalized.startsWith("splits/")
                || !normalized.endsWith(".apk"))) {
            throw new IllegalArgumentException("split APK path is invalid");
        }
        if (NATIVE_LIBRARY.equals(artifactKind) && (!normalized.startsWith("lib/")
                || !normalized.endsWith(".so"))) {
            throw new IllegalArgumentException("native library path is invalid");
        }
        return normalized;
    }

    private static String sha256(String value) {
        String normalized = required(value, "sha256", 64).toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("sha256 is invalid");
        return normalized;
    }
}
