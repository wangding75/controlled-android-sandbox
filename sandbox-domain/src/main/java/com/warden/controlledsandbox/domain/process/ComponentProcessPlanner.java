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
        plans.put("", new ProcessPlan("", manifest.packageName(), false, 0));
        int[] next = {1};
        collect(manifest.packageName(), manifest.activities(), plans, next);
        collect(manifest.packageName(), manifest.services(), plans, next);
        collect(manifest.packageName(), manifest.receivers(), plans, next);
        collect(manifest.packageName(), manifest.providers(), plans, next);
        return Collections.unmodifiableList(new ArrayList<>(plans.values()));
    }

    private static void collect(String packageName, List<ManifestModel.Component> components,
                                Map<String, ProcessPlan> plans, int[] next) {
        for (ManifestModel.Component component : components) {
            String declared = component.processName();
            if (component.isolatedProcess()) {
                String key = "isolated:" + component.className();
                plans.putIfAbsent(key, new ProcessPlan(declared,
                        packageName + ":isolated_" + safe(component.className()), true, next[0]++));
                continue;
            }
            String key = declared == null ? "" : declared;
            if (plans.containsKey(key)) continue;
            String normalized;
            if (key.isEmpty()) normalized = packageName;
            else if (key.startsWith(":")) normalized = packageName + key;
            else normalized = key;
            plans.put(key, new ProcessPlan(key, normalized, false, next[0]++));
        }
    }

    private static String safe(String value) { return value.replaceAll("[^A-Za-z0-9_]", "_"); }
}
