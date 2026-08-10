package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualNfcProfileSnapshot;

import java.util.List;

/** Stable Android NFC Binder and feature contract shared by the hook and PackageManager policy. */
public final class NfcServiceContract {
    public static final String SERVICE_NAME = "nfc";
    public static final List<String> SERVICE_NAMES = List.of(SERVICE_NAME);
    public static final String DESCRIPTOR = "android.nfc.INfcAdapter";
    public static final String LOGICAL_SERVICE = "nfc";
    public static final String FEATURE_NFC = "android.hardware.nfc";
    public static final String ADAPTER_CACHE_FIELD = "sService";

    private NfcServiceContract() { }

    public static boolean isNfcFeature(String featureName) {
        return FEATURE_NFC.equals(featureName);
    }

    /** Static/blocked profiles own guest feature truth; HOST delegates to the real platform. */
    public static boolean guestFeatureEnabled(VirtualNfcProfileSnapshot profile) {
        if (profile == null) throw new IllegalArgumentException("NFC profile is required");
        if (VirtualLocationProfileSnapshot.MODE_HOST.equals(profile.mode())) return true;
        return !VirtualLocationProfileSnapshot.MODE_BLOCKED.equals(profile.mode());
    }
}
