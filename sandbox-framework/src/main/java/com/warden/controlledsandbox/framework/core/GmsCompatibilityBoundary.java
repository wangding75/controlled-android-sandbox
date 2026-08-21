package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.contract.VirtualGoogleServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.framework.contract.WebViewProviderServiceContract;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/**
 * Basic GMS compatibility boundary.  It owns package visibility and identity decisions but does
 * not claim that a Google Play services runtime has been implemented.
 */
public final class GmsCompatibilityBoundary {
    public enum Status { HOST_MODE, BLOCKED, DEFERRED_GMS_RUNTIME, BASIC_BOUNDARY }

    public record Assessment(Status status, boolean gmsVisible, boolean gsfVisible,
                             boolean basicBoundaryReady, String detail) { }

    public static final String GMS_PACKAGE = "com.google.android.gms";
    public static final String GSF_PACKAGE = "com.google.android.gsf";

    private GmsCompatibilityBoundary() { }

    public static Assessment assess(GuestIdentity identity) {
        if (identity == null) {
            return new Assessment(Status.DEFERRED_GMS_RUNTIME, false, false, false,
                    "identity-unavailable");
        }
        VirtualGoogleServicesProfileSnapshot profile;
        try {
            profile = identity.virtualServices().compatibilityProfile().googleServices();
        } catch (RuntimeException unavailable) {
            return new Assessment(Status.DEFERRED_GMS_RUNTIME, false, false, false,
                    "compatibility-profile-unavailable");
        }
        if (VirtualLocationProfileSnapshot.MODE_HOST.equals(profile.mode())) {
            return new Assessment(Status.HOST_MODE, false, false, false, "host-mode");
        }
        if (VirtualLocationProfileSnapshot.MODE_BLOCKED.equals(profile.mode())) {
            return new Assessment(Status.BLOCKED, false, false, false, "policy-blocked");
        }
        boolean gmsVisible = visible(identity, GMS_PACKAGE);
        boolean gsfVisible = visible(identity, GSF_PACKAGE);
        if (!profile.playServicesAvailable() || !gmsVisible || !gsfVisible) {
            String detail = !profile.playServicesAvailable() ? "profile-disabled"
                    : (!gmsVisible ? "gms-package-not-visible" : "gsf-package-not-visible");
            return new Assessment(Status.DEFERRED_GMS_RUNTIME, gmsVisible, gsfVisible,
                    false, detail);
        }
        // This means that package resolution and the allowlisted Binder boundary are coherent;
        // it intentionally does not mean that arbitrary Play-services APIs are successful.
        return new Assessment(Status.BASIC_BOUNDARY, true, true, true,
                "package-visible-basic-boundary");
    }

    public static boolean isAllowlistedPackage(String packageName) {
        return GMS_PACKAGE.equals(packageName) || GSF_PACKAGE.equals(packageName)
                || WebViewProviderServiceContract.GOOGLE_MOBILE_SERVICES.equals(packageName);
    }

    private static boolean visible(GuestIdentity identity, String packageName) {
        return identity.packageUniverse().packageMetadata(packageName) != null
                && identity.packageUniverse().isVisibleTo(identity.packageName(), packageName);
    }
}
