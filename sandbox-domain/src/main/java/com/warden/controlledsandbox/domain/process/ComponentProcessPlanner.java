package com.warden.controlledsandbox.domain.process;

import com.warden.controlledsandbox.domain.packageinfo.manifest.ManifestModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Maps manifest process declarations to stable Guest process aliases before device binding. */
public final class ComponentProcessPlanner {
    public static final class ProcessPlan {
        private final String declaredName;
        private final String normalizedName;
        private final boolean isolated;
        private final int ordinal;

        private ProcessPlan(String declaredName, String normalizedName, boolean isolated, int ordinal) {
            this.declaredName = declaredName;
            this.normalizedName = normalizedName;
            this.isolated = isolated;
            this.ordinal = ordinal;
        }

        public String declaredName() { return declaredName; }
        public String normalizedName() { return normalizedName; }
        public boolean isolated() { return isolated; }
        public int ordinal() { return ordinal; }
    }

    public List<ProcessPlan> plan(ManifestModel manifest) {
        Map<String, ProcessPlan> plans = new LinkedHashMap<>();
        String declaredApplicationProcess = value(manifest.applicationProcessName());
        String applicationProcess = normalize(manifest.packageName(), declaredApplicationProcess);
        plans.put(applicationProcess,
                new ProcessPlan(declaredApplicationProcess, applicationProcess, false, 0));
        int[] next = {1};
        collect(manifest.packageName(), declaredApplicationProcess,
                manifest.activities(), plans, next);
        collect(manifest.packageName(), declaredApplicationProcess,
                manifest.services(), plans, next);
        collect(manifest.packageName(), declaredApplicationProcess,
                manifest.receivers(), plans, next);
        collect(manifest.packageName(), declaredApplicationProcess,
                manifest.providers(), plans, next);
        return Collections.unmodifiableList(new ArrayList<>(plans.values()));
    }

    private static void collect(String packageName, String applicationDeclaredProcess,
                                List<ManifestModel.Component> components,
                                Map<String, ProcessPlan> plans, int[] next) {
        for (ManifestModel.Component component : components) {
            if (component.isolatedProcess()) {
                String key = "isolated:" + component.className();
                if (!plans.containsKey(key)) {
                    String declared = value(component.processName());
                    plans.put(key, new ProcessPlan(declared,
                            packageName + ":isolated_" + safe(component.className()), true,
                            next[0]++));
                }
                continue;
            }
            String declared = value(component.processName());
            String normalized = normalize(packageName,
                    declared.isEmpty() ? applicationDeclaredProcess : declared);
            if (plans.containsKey(normalized)) continue;
            plans.put(normalized, new ProcessPlan(
                    declared.isEmpty() ? applicationDeclaredProcess : declared,
                    normalized, false, next[0]++));
        }
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalize(String packageName, String declared) {
        String value = value(declared);
        if (value.isEmpty()) return packageName;
        return value.startsWith(":") ? packageName + value : value;
    }

    private static String safe(String value) { return value.replaceAll("[^A-Za-z0-9_]", "_"); }
}
