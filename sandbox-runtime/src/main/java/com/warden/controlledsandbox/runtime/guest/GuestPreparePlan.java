package com.warden.controlledsandbox.runtime.guest;

import android.os.Bundle;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Explicit classification of the work performed while a Guest process is prepared.
 *
 * <p>The plan is intentionally separate from the installer.  A service proxy is not moved to a
 * lazy path merely because it is classified as first-use or background: a proxy can still be
 * required by an application's {@code Application.onCreate()} or by a provider.  Only operations
 * whose first-use boundary is independently guarded are marked deferred.  This gives us useful
 * timing evidence without turning a security or lifecycle assumption into an optimization.</p>
 */
final class GuestPreparePlan {
    static final int VERSION = 1;

    enum Tier { LAUNCH_CRITICAL, FIRST_USE, BACKGROUND }

    record Entry(String name, Tier tier, boolean deferred) {
        Entry {
            if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("name is required");
            if (tier == null) throw new IllegalArgumentException("tier is required");
            name = name.trim();
        }
    }

    private static final Map<String, Tier> HOOK_TIERS = hookTiers();
    private static final EnumSet<Tier> ALL_TIERS = EnumSet.allOf(Tier.class);
    private final List<Entry> entries;

    private GuestPreparePlan(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) throw new IllegalArgumentException("entries are required");
        Map<String, Entry> unique = new LinkedHashMap<>();
        for (Entry entry : entries) {
            if (unique.put(entry.name(), entry) != null) {
                throw new IllegalArgumentException("duplicate prepare operation: " + entry.name());
            }
        }
        this.entries = List.copyOf(unique.values());
    }

    /** Builds the plan for the current runtime path without changing the install ordering. */
    static GuestPreparePlan forSpec(GuestPackageSpec spec, boolean nativePolicyConfigured) {
        if (spec == null) throw new IllegalArgumentException("spec is required");
        return forFlags(spec.isolatedProcess, nativePolicyConfigured);
    }

    /** Package-visible deterministic constructor used by the source self-test. */
    static GuestPreparePlan forFlags(boolean isolatedProcess, boolean nativePolicyConfigured) {
        ArrayList<Entry> result = new ArrayList<>();
        add(result, "systemServiceSession", Tier.LAUNCH_CRITICAL, false);
        add(result, "nativeBootstrap", Tier.LAUNCH_CRITICAL, false);
        add(result, "classloader", Tier.LAUNCH_CRITICAL, false);
        add(result, "resources", Tier.LAUNCH_CRITICAL, false);
        add(result, "frameworkIdentity", Tier.LAUNCH_CRITICAL, false);
        // Android requires the suffix before any WebView object is created.  Directory creation
        // below is a separate, first-use operation and is the only WebView work deferred here.
        add(result, "webViewProfileSuffix", Tier.LAUNCH_CRITICAL, false);
        add(result, "frameworkHooks", Tier.LAUNCH_CRITICAL, false);
        for (Map.Entry<String, Tier> item : HOOK_TIERS.entrySet()) {
            add(result, item.getKey(), item.getValue(), false);
        }
        if (nativePolicyConfigured && !isolatedProcess) {
            // GuestClassLoader installs this adapter when android.hardware.Camera is first loaded.
            add(result, "camera1Adapter", Tier.FIRST_USE, true);
        }
        add(result, "applicationAttach", Tier.LAUNCH_CRITICAL, false);
        add(result, "activityThreadBridges", Tier.LAUNCH_CRITICAL, false);
        add(result, "providerPrepare", Tier.LAUNCH_CRITICAL, false);
        add(result, "applicationOnCreate", Tier.LAUNCH_CRITICAL, false);
        add(result, "webViewStorageDirectories", Tier.BACKGROUND, true);
        return new GuestPreparePlan(result);
    }

    List<Entry> entries() { return entries; }

    List<String> names(Tier tier) {
        if (tier == null || !ALL_TIERS.contains(tier)) return List.of();
        ArrayList<String> result = new ArrayList<>();
        for (Entry entry : entries) if (entry.tier() == tier) result.add(entry.name());
        return Collections.unmodifiableList(result);
    }

    List<String> deferredNames() {
        ArrayList<String> result = new ArrayList<>();
        for (Entry entry : entries) if (entry.deferred()) result.add(entry.name());
        return Collections.unmodifiableList(result);
    }

    Bundle toBundle() {
        Bundle out = new Bundle();
        out.putInt(RuntimeKeys.GUEST_PREPARE_PLAN_VERSION, VERSION);
        out.putStringArrayList(RuntimeKeys.GUEST_PREPARE_TIER_A,
                new ArrayList<>(names(Tier.LAUNCH_CRITICAL)));
        out.putStringArrayList(RuntimeKeys.GUEST_PREPARE_TIER_B,
                new ArrayList<>(names(Tier.FIRST_USE)));
        out.putStringArrayList(RuntimeKeys.GUEST_PREPARE_TIER_C,
                new ArrayList<>(names(Tier.BACKGROUND)));
        out.putStringArrayList(RuntimeKeys.GUEST_PREPARE_DEFERRED,
                new ArrayList<>(deferredNames()));
        // This status is deliberately explicit: no unverified service proxy is made lazy by the
        // classification pass.  It can be changed only when a first-use boundary has a test.
        out.putString(RuntimeKeys.GUEST_PREPARE_LAZY_POLICY, "EXPLICIT_BOUNDARIES_ONLY");
        return out;
    }

    static Tier hookTier(String name) {
        if (name == null) return null;
        return HOOK_TIERS.get(name.trim());
    }

    static boolean isDeferred(String name) {
        if (name == null) return false;
        return "camera1Adapter".equals(name.trim())
                || "webViewStorageDirectories".equals(name.trim());
    }

    private static void add(List<Entry> entries, String name, Tier tier, boolean deferred) {
        entries.add(new Entry(name, tier, deferred));
    }

    private static Map<String, Tier> hookTiers() {
        EnumMap<Tier, List<String>> grouped = new EnumMap<>(Tier.class);
        grouped.put(Tier.LAUNCH_CRITICAL, List.of(
                "packageManager", "activityManager", "activityTaskManager", "window",
                "inputManager", "inputMethod", "display", "appOps", "permission",
                "notification", "jobScheduler", "alarm", "clipboard", "account", "storage"));
        grouped.put(Tier.FIRST_USE, List.of(
                "camera", "location", "locationGuestManager", "deviceIdentity", "settingsIdentity",
                "webViewUpdate", "deviceIdentifiers", "googleServiceBroker", "oemIdentifiers",
                "telephony", "telephonyGuestManager", "telecom", "phoneSubInfo",
                "telephonyRegistry", "subscription", "wifi", "wifiScanner", "connectivity",
                "dnsResolver", "vpn", "userManager", "restrictions", "launcherApps", "shortcut",
                "appWidget", "usageStats", "devicePolicy", "accessibility",
                "captioning", "autofill", "biometric", "sensorPrivacy", "power", "battery",
                "vibrator", "mediaSession", "mediaRouter", "sms", "audioCapture", "bluetooth",
                "sensorCatalog"));
        grouped.put(Tier.BACKGROUND, List.of(
                "content", "backup", "dropBox", "nfc", "usb", "print", "companionDevice", "mediaProjection",
                "oemSystemServices", "search", "storageStats", "graphicsStats", "contextHub",
                "persistentDataBlock", "systemUpdate"));
        LinkedHashMap<String, Tier> result = new LinkedHashMap<>();
        for (Tier tier : Tier.values()) {
            for (String name : grouped.get(tier)) result.put(name, tier);
        }
        return Collections.unmodifiableMap(result);
    }
}
