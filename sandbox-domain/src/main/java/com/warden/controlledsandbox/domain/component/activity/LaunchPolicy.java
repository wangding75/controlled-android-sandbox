package com.warden.controlledsandbox.domain.component.activity;

import com.warden.controlledsandbox.domain.packageinfo.manifest.ManifestModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Collections;

public final class LaunchPolicy {
    private final int hostApiLevel;

    public LaunchPolicy(int hostApiLevel) { this.hostApiLevel = hostApiLevel; }

    public LaunchDecision evaluate(ManifestModel manifest, int processSlot, boolean apkExists) {
        List<String> reasons = new ArrayList<>();
        if (!apkExists) reasons.add("APK_FILE_MISSING");
        if (manifest.packageName().trim().isEmpty()) reasons.add("PACKAGE_NAME_MISSING");
        if (manifest.launcherActivity().trim().isEmpty()) reasons.add("LAUNCHER_ACTIVITY_MISSING");
        if (manifest.minSdk() > 0 && manifest.minSdk() > hostApiLevel) reasons.add("HOST_API_TOO_OLD");
        if (processSlot < 0) reasons.add("NO_PROCESS_SLOT");
        if (!reasons.isEmpty()) return new LaunchDecision(LaunchDecision.Status.REJECTED, processSlot, "", Collections.unmodifiableList(new ArrayList<>(reasons)));
        return new LaunchDecision(LaunchDecision.Status.READY_FOR_PROBE, processSlot,
                manifest.launcherActivity(), Collections.singletonList("CLASS_LOAD_PROBE_ONLY"));
    }
}
