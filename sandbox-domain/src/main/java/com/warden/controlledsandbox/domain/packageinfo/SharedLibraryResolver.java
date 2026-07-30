package com.warden.controlledsandbox.domain.packageinfo;

import com.warden.controlledsandbox.domain.packageinfo.manifest.ManifestModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Deterministic, injected-catalog resolver for manifest shared-library requirements. */
public final class SharedLibraryResolver {
    public static final class AvailableLibrary {
        private final ManifestModel.SharedLibraryDependency.Kind kind;
        private final String name;
        private final long version;
        private final String certificateDigest;
        private final String providerPackage;

        public AvailableLibrary(ManifestModel.SharedLibraryDependency.Kind kind, String name,
                                long version, String certificateDigest, String providerPackage) {
            this.kind = kind == null ? ManifestModel.SharedLibraryDependency.Kind.JAVA : kind;
            this.name = required(name, "name");
            if (version < 0) throw new IllegalArgumentException("version is invalid");
            this.version = version;
            String digest = value(certificateDigest).toLowerCase(Locale.ROOT).replace(":", "");
            if (!digest.isEmpty() && !digest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("certificateDigest is invalid");
            }
            this.certificateDigest = digest;
            this.providerPackage = value(providerPackage);
        }

        public ManifestModel.SharedLibraryDependency.Kind kind() { return kind; }
        public String name() { return name; }
        public long version() { return version; }
        public String certificateDigest() { return certificateDigest; }
        public String providerPackage() { return providerPackage; }
        String key() { return kind.name() + ":" + name; }
    }

    public static final class Resolution {
        private final List<AvailableLibrary> resolved;
        private final List<ManifestModel.SharedLibraryDependency> missingRequired;
        private final List<ManifestModel.SharedLibraryDependency> missingOptional;
        private final List<String> errors;

        Resolution(List<AvailableLibrary> resolved,
                   List<ManifestModel.SharedLibraryDependency> missingRequired,
                   List<ManifestModel.SharedLibraryDependency> missingOptional,
                   List<String> errors) {
            this.resolved = List.copyOf(resolved);
            this.missingRequired = List.copyOf(missingRequired);
            this.missingOptional = List.copyOf(missingOptional);
            this.errors = List.copyOf(errors);
        }

        public List<AvailableLibrary> resolved() { return resolved; }
        public List<ManifestModel.SharedLibraryDependency> missingRequired() { return missingRequired; }
        public List<ManifestModel.SharedLibraryDependency> missingOptional() { return missingOptional; }
        public List<String> errors() { return errors; }
        public boolean successful() { return missingRequired.isEmpty() && errors.isEmpty(); }
        public void requireSuccessful() {
            if (!successful()) {
                throw new IllegalStateException("SHARED_LIBRARY_RESOLUTION_FAILED: "
                        + String.join(";", errors));
            }
        }
    }

    private final Map<String, AvailableLibrary> available;

    public SharedLibraryResolver(List<AvailableLibrary> available) {
        Map<String, AvailableLibrary> values = new LinkedHashMap<>();
        for (AvailableLibrary item : available == null ? List.<AvailableLibrary>of() : available) {
            AvailableLibrary previous = values.put(item.key(), item);
            if (previous != null && (!previous.certificateDigest().equals(item.certificateDigest())
                    || previous.version() != item.version()
                    || !previous.providerPackage().equals(item.providerPackage()))) {
                throw new IllegalArgumentException("Conflicting available library: " + item.name());
            }
        }
        this.available = Collections.unmodifiableMap(values);
    }

    public Resolution resolve(List<ManifestModel.SharedLibraryDependency> dependencies) {
        List<AvailableLibrary> resolved = new ArrayList<>();
        List<ManifestModel.SharedLibraryDependency> missingRequired = new ArrayList<>();
        List<ManifestModel.SharedLibraryDependency> missingOptional = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (ManifestModel.SharedLibraryDependency dependency
                : dependencies == null ? List.<ManifestModel.SharedLibraryDependency>of() : dependencies) {
            AvailableLibrary candidate = available.get(dependency.key());
            if (candidate == null) {
                (dependency.required() ? missingRequired : missingOptional).add(dependency);
                if (dependency.required()) errors.add("missing " + dependency.key());
                continue;
            }
            if (dependency.version() > 0 && candidate.version() != dependency.version()) {
                (dependency.required() ? missingRequired : missingOptional).add(dependency);
                if (dependency.required()) {
                    errors.add("version mismatch " + dependency.key() + " expected="
                            + dependency.version() + " actual=" + candidate.version());
                }
                continue;
            }
            if (!dependency.certificateDigest().isEmpty()
                    && !dependency.certificateDigest().equals(candidate.certificateDigest())) {
                (dependency.required() ? missingRequired : missingOptional).add(dependency);
                if (dependency.required()) errors.add("certificate mismatch " + dependency.key());
                continue;
            }
            resolved.add(candidate);
        }
        return new Resolution(resolved, missingRequired, missingOptional, errors);
    }

    public List<AvailableLibrary> available() { return List.copyOf(available.values()); }

    private static String required(String value, String name) {
        String normalized = value(value);
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " is required");
        return normalized;
    }
    private static String value(String value) { return value == null ? "" : value.trim(); }
}
