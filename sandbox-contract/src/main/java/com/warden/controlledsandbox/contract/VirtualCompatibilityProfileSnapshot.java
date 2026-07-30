package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Aggregate WebView, Google-service, OEM and detection policy for one package/user scope. */
public final class VirtualCompatibilityProfileSnapshot implements Parcelable {
    private final long policyVersion;
    private final long updatedAtMs;
    private final VirtualWebViewProfileSnapshot webView;
    private final VirtualGoogleServicesProfileSnapshot googleServices;
    private final VirtualOemProfileSnapshot oem;
    private final VirtualDetectionPolicySnapshot detection;

    public VirtualCompatibilityProfileSnapshot(
            long policyVersion,
            long updatedAtMs,
            VirtualWebViewProfileSnapshot webView,
            VirtualGoogleServicesProfileSnapshot googleServices,
            VirtualOemProfileSnapshot oem,
            VirtualDetectionPolicySnapshot detection) {
        if (policyVersion < 1) throw new IllegalArgumentException("policyVersion must be positive");
        if (updatedAtMs < 0) throw new IllegalArgumentException("updatedAtMs must be non-negative");
        if (webView == null || googleServices == null || oem == null || detection == null) {
            throw new IllegalArgumentException("compatibility profile domains are required");
        }
        this.policyVersion = policyVersion;
        this.updatedAtMs = updatedAtMs;
        this.webView = webView;
        this.googleServices = googleServices;
        this.oem = oem;
        this.detection = detection;
    }

    private VirtualCompatibilityProfileSnapshot(Parcel in) {
        this(
                in.readLong(),
                in.readLong(),
                in.readParcelable(VirtualWebViewProfileSnapshot.class.getClassLoader()),
                in.readParcelable(VirtualGoogleServicesProfileSnapshot.class.getClassLoader()),
                in.readParcelable(VirtualOemProfileSnapshot.class.getClassLoader()),
                in.readParcelable(VirtualDetectionPolicySnapshot.class.getClassLoader()));
    }

    public long policyVersion() { return policyVersion; }
    public long updatedAtMs() { return updatedAtMs; }
    public VirtualWebViewProfileSnapshot webView() { return webView; }
    public VirtualGoogleServicesProfileSnapshot googleServices() { return googleServices; }
    public VirtualOemProfileSnapshot oem() { return oem; }
    public VirtualDetectionPolicySnapshot detection() { return detection; }

    public VirtualCompatibilityProfileSnapshot withVersion(long version, long updatedAt) {
        return new VirtualCompatibilityProfileSnapshot(
                version, updatedAt, webView, googleServices, oem, detection);
    }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeLong(policyVersion);
        out.writeLong(updatedAtMs);
        out.writeParcelable(webView, flags);
        out.writeParcelable(googleServices, flags);
        out.writeParcelable(oem, flags);
        out.writeParcelable(detection, flags);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<VirtualCompatibilityProfileSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualCompatibilityProfileSnapshot createFromParcel(Parcel in) {
            return new VirtualCompatibilityProfileSnapshot(in);
        }

        @Override public VirtualCompatibilityProfileSnapshot[] newArray(int size) {
            return new VirtualCompatibilityProfileSnapshot[size];
        }
    };
}
