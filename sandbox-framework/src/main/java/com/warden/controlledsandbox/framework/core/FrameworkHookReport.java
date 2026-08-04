package com.warden.controlledsandbox.framework.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class FrameworkHookReport {
    private static final Set<String> MANDATORY = Set.of(
            "packageManager", "activityManager", "activityTaskManager", "appOps", "permission",
            "notification", "jobScheduler", "alarm", "clipboard", "account", "storage");
    private final Map<String, Boolean> installed;
    private final Map<String, String> failures;

    FrameworkHookReport(Map<String, Boolean> installed, Map<String, String> failures) {
        this.installed = Collections.unmodifiableMap(new LinkedHashMap<>(installed));
        this.failures = Collections.unmodifiableMap(new LinkedHashMap<>(failures));
    }

    public boolean packageManagerInstalled() { return installed("packageManager"); }
    public boolean installed(String service) { return Boolean.TRUE.equals(installed.get(service)); }
    public Map<String, Boolean> installedServices() { return installed; }
    public Map<String, String> failures() { return failures; }

    public Set<String> mandatoryFailures() {
        Set<String> result = new LinkedHashSet<>();
        for (String service : MANDATORY) if (!installed(service)) result.add(service);
        return Collections.unmodifiableSet(result);
    }

    public Readiness readiness() {
        if (!mandatoryFailures().isEmpty()) return Readiness.BLOCKED;
        return failures.isEmpty() ? Readiness.READY : Readiness.DEGRADED;
    }

    public void requireMandatoryReady() {
        Set<String> missing = mandatoryFailures();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("MANDATORY_FRAMEWORK_HOOKS_FAILED:" + String.join(",", missing));
        }
    }

    public String errorType() {
        if (failures.isEmpty()) return "";
        String first = failures.values().iterator().next();
        int separator = first.indexOf(':');
        return separator < 0 ? first : first.substring(0, separator);
    }

    public String errorMessage() {
        if (failures.isEmpty()) return "";
        String first = failures.values().iterator().next();
        int separator = first.indexOf(':');
        return separator < 0 ? "" : first.substring(separator + 1);
    }

    public enum Readiness { READY, DEGRADED, BLOCKED }
}
