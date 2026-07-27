package com.warden.controlledsandbox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Produces a deterministic dependency-first order for installed split APKs. */
final class PackageArtifactOrder {
    private PackageArtifactOrder() { }

    static List<PackageArtifactRecord> runtimeOrder(List<PackageArtifactRecord> input) {
        if (input == null || input.isEmpty()) throw new IllegalArgumentException("Package artifacts are required");
        if (input.size() > 256) throw new IllegalArgumentException("Package contains too many artifacts");
        PackageArtifactRecord base = null;
        Map<String, PackageArtifactRecord> splits = new HashMap<>();
        for (PackageArtifactRecord artifact : input) {
            if (artifact == null) throw new IllegalArgumentException("Package artifact is required");
            if (artifact.base()) {
                if (base != null) throw new IllegalArgumentException("Package contains multiple base artifacts");
                base = artifact;
            } else if (splits.put(artifact.splitName, artifact) != null) {
                throw new IllegalArgumentException("Duplicate split metadata: " + artifact.splitName);
            }
        }
        if (base == null) throw new IllegalArgumentException("Package does not contain a base artifact");

        List<String> names = new ArrayList<>(splits.keySet());
        Collections.sort(names);
        List<PackageArtifactRecord> ordered = new ArrayList<>();
        ordered.add(base);
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String name : names) visit(name, splits, visiting, visited, ordered);
        return Collections.unmodifiableList(ordered);
    }

    private static void visit(String name, Map<String, PackageArtifactRecord> splits,
                              Set<String> visiting, Set<String> visited,
                              List<PackageArtifactRecord> ordered) {
        if (visited.contains(name)) return;
        if (!visiting.add(name)) throw new IllegalArgumentException("Split dependency cycle: " + name);
        PackageArtifactRecord artifact = splits.get(name);
        if (artifact == null) throw new IllegalArgumentException("Missing split dependency: " + name);
        visitDependency(artifact.usesSplit, splits, visiting, visited, ordered);
        if (PackageArtifactRecord.TYPE_CONFIG.equals(artifact.type)
                && !"base".equals(artifact.configForSplit)) {
            visitDependency(artifact.configForSplit, splits, visiting, visited, ordered);
        }
        visiting.remove(name);
        visited.add(name);
        ordered.add(artifact);
    }

    private static void visitDependency(String dependency, Map<String, PackageArtifactRecord> splits,
                                        Set<String> visiting, Set<String> visited,
                                        List<PackageArtifactRecord> ordered) {
        if (dependency == null || dependency.isEmpty() || "base".equals(dependency)) return;
        if (!splits.containsKey(dependency)) {
            throw new IllegalArgumentException("Missing split dependency: " + dependency);
        }
        visit(dependency, splits, visiting, visited, ordered);
    }
}
