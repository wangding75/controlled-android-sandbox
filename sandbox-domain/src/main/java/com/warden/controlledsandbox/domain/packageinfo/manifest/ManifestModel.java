package com.warden.controlledsandbox.domain.packageinfo.manifest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ManifestModel {
    private String packageName = "";
    private String applicationClass = "";
    private String applicationPermission = "";
    private String splitName = "";
    private String configForSplit = "";
    private String usesSplit = "";
    private boolean featureSplit;
    private int minSdk;
    private int targetSdk;
    private final List<Component> activities = new ArrayList<>();
    private final List<Component> services = new ArrayList<>();
    private final List<Component> receivers = new ArrayList<>();
    private final List<Component> providers = new ArrayList<>();
    private final List<String> permissions = new ArrayList<>();
    private final List<String> sharedLibraries = new ArrayList<>();
    private final List<SharedLibraryDependency> sharedLibraryDependencies = new ArrayList<>();
    private final List<String> providedSharedLibraries = new ArrayList<>();
    private final List<Instrumentation> instrumentations = new ArrayList<>();

    public String packageName() { return packageName; }
    public void packageName(String value) { packageName = value == null ? "" : value; }
    public String applicationClass() { return applicationClass; }
    public void applicationClass(String value) { applicationClass = resolveClassName(value); }
    public String applicationPermission() { return applicationPermission; }
    public String splitName() { return splitName; }
    public void splitName(String value) { splitName = normalize(value); }
    public String configForSplit() { return configForSplit; }
    public void configForSplit(String value) { configForSplit = normalize(value); }
    public String usesSplit() { return usesSplit; }
    public void usesSplit(String value) { usesSplit = normalize(value); }
    public boolean featureSplit() { return featureSplit; }
    public void featureSplit(boolean value) { featureSplit = value; }
    public void applicationPermission(String value) { applicationPermission = normalize(value); }
    public int minSdk() { return minSdk; }
    public void minSdk(int value) { minSdk = value; }
    public int targetSdk() { return targetSdk; }
    public void targetSdk(int value) { targetSdk = value; }
    public List<Component> activities() { return Collections.unmodifiableList(activities); }
    public List<Component> services() { return Collections.unmodifiableList(services); }
    public List<Component> receivers() { return Collections.unmodifiableList(receivers); }
    public List<Component> providers() { return Collections.unmodifiableList(providers); }
    public List<String> permissions() { return Collections.unmodifiableList(permissions); }
    public List<String> sharedLibraries() { return Collections.unmodifiableList(sharedLibraries); }
    public List<SharedLibraryDependency> sharedLibraryDependencies() {
        return Collections.unmodifiableList(sharedLibraryDependencies);
    }
    public List<String> providedSharedLibraries() {
        return Collections.unmodifiableList(providedSharedLibraries);
    }
    public List<Instrumentation> instrumentations() {
        return Collections.unmodifiableList(instrumentations);
    }

    public void addActivity(Component component) { activities.add(component); }
    public void addService(Component component) { services.add(component); }
    public void addReceiver(Component component) { receivers.add(component); }
    public void addProvider(Component component) { providers.add(component); }

    /** Backward-compatible shorthand for a required Java uses-library declaration. */
    public void addSharedLibrary(String library) {
        addSharedLibrary(new SharedLibraryDependency(
                SharedLibraryDependency.Kind.JAVA, library, true, 0L, ""));
    }

    public void addSharedLibrary(SharedLibraryDependency dependency) {
        if (dependency == null || dependency.name().isEmpty()) return;
        String key = dependency.key();
        for (SharedLibraryDependency existing : sharedLibraryDependencies) {
            if (existing.key().equals(key)) {
                if (!existing.equals(dependency)) {
                    throw new IllegalArgumentException(
                            "Conflicting shared-library declaration: " + dependency.name());
                }
                return;
            }
        }
        sharedLibraryDependencies.add(dependency);
        if (!sharedLibraries.contains(dependency.name())) sharedLibraries.add(dependency.name());
    }

    public void addProvidedSharedLibrary(String library) {
        String normalized = normalize(library);
        if (!normalized.isEmpty() && !providedSharedLibraries.contains(normalized)) {
            providedSharedLibraries.add(normalized);
        }
    }

    public void addInstrumentation(Instrumentation instrumentation) {
        if (instrumentation == null) return;
        for (Instrumentation existing : instrumentations) {
            if (existing.className().equals(instrumentation.className())) {
                if (!existing.equals(instrumentation)) {
                    throw new IllegalArgumentException(
                            "Conflicting instrumentation declaration: " + instrumentation.className());
                }
                return;
            }
        }
        instrumentations.add(instrumentation);
    }

    public void addPermission(String permission) {
        String normalized = normalize(permission);
        if (!normalized.isEmpty() && !permissions.contains(normalized)) permissions.add(normalized);
    }

    public String launcherActivity() {
        for (Component activity : activities) if (activity.launcher()) return activity.className();
        return "";
    }

    public int isolatedProcessCount() {
        int count = 0;
        for (Component service : services) if (service.isolatedProcess()) count++;
        return count;
    }

    public String resolveClassName(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        if (raw.startsWith(".")) return packageName + raw;
        if (raw.indexOf('.') < 0 && !packageName.trim().isEmpty()) return packageName + "." + raw;
        return raw;
    }

    public static final class SharedLibraryDependency {
        public enum Kind { JAVA, NATIVE, SDK, STATIC }

        private final Kind kind;
        private final String name;
        private final boolean required;
        private final long version;
        private final String certificateDigest;

        public SharedLibraryDependency(Kind kind, String name, boolean required,
                                       long version, String certificateDigest) {
            this.kind = kind == null ? Kind.JAVA : kind;
            this.name = normalize(name);
            if (this.name.isEmpty()) throw new IllegalArgumentException("shared library name is required");
            if (version < 0) throw new IllegalArgumentException("shared library version is invalid");
            this.required = required;
            this.version = version;
            String digest = normalize(certificateDigest).toLowerCase(Locale.ROOT).replace(":", "");
            if (!digest.isEmpty() && !digest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("shared library certificate digest is invalid");
            }
            this.certificateDigest = digest;
        }

        public Kind kind() { return kind; }
        public String name() { return name; }
        public boolean required() { return required; }
        public long version() { return version; }
        public String certificateDigest() { return certificateDigest; }
        public String key() { return kind.name() + ":" + name; }

        @Override public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof SharedLibraryDependency)) return false;
            SharedLibraryDependency other = (SharedLibraryDependency) value;
            return kind == other.kind && name.equals(other.name) && required == other.required
                    && version == other.version && certificateDigest.equals(other.certificateDigest);
        }

        @Override public int hashCode() {
            return java.util.Objects.hash(kind, name, required, version, certificateDigest);
        }
    }

    public static final class Instrumentation {
        private final String className;
        private final String targetPackage;
        private final String targetProcesses;
        private final boolean handleProfiling;
        private final boolean functionalTest;
        private final boolean enabled;

        public Instrumentation(String className, String targetPackage, String targetProcesses,
                               boolean handleProfiling, boolean functionalTest, boolean enabled) {
            this.className = normalize(className);
            this.targetPackage = normalize(targetPackage);
            this.targetProcesses = normalize(targetProcesses);
            if (this.className.isEmpty()) {
                throw new IllegalArgumentException("instrumentation class name is required");
            }
            if (this.targetPackage.isEmpty()) {
                throw new IllegalArgumentException("instrumentation target package is required");
            }
            this.handleProfiling = handleProfiling;
            this.functionalTest = functionalTest;
            this.enabled = enabled;
        }

        public String className() { return className; }
        public String targetPackage() { return targetPackage; }
        public String targetProcesses() { return targetProcesses; }
        public boolean handleProfiling() { return handleProfiling; }
        public boolean functionalTest() { return functionalTest; }
        public boolean enabled() { return enabled; }

        @Override public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof Instrumentation)) return false;
            Instrumentation other = (Instrumentation) value;
            return handleProfiling == other.handleProfiling && functionalTest == other.functionalTest
                    && enabled == other.enabled && className.equals(other.className)
                    && targetPackage.equals(other.targetPackage)
                    && targetProcesses.equals(other.targetProcesses);
        }

        @Override public int hashCode() {
            return java.util.Objects.hash(className, targetPackage, targetProcesses,
                    handleProfiling, functionalTest, enabled);
        }
    }

    public static final class Component {
        private final String className;
        private final String processName;
        private final boolean exported;
        private final boolean exportedExplicit;
        private final boolean enabled;
        private final boolean isolatedProcess;
        private final String authorities;
        private final String permission;
        private final String readPermission;
        private final String writePermission;
        private final boolean grantUriPermissions;
        private final List<ProviderPathRule> providerPathRules = new ArrayList<>();
        private final List<String> actions = new ArrayList<>();
        private final List<IntentFilter> intentFilters = new ArrayList<>();
        private boolean launcher;
        private boolean intentFilterDeclared;

        public Component(String className, String processName, boolean exported, boolean enabled,
                         boolean isolatedProcess) {
            this(className, processName, exported, enabled, isolatedProcess, "", "");
        }

        public Component(String className, String processName, boolean exported, boolean enabled,
                         boolean isolatedProcess, String authorities) {
            this(className, processName, exported, enabled, isolatedProcess, authorities, "");
        }

        public Component(String className, String processName, boolean exported, boolean enabled,
                         boolean isolatedProcess, String authorities, String permission) {
            this(className, processName, exported, true, enabled, isolatedProcess, authorities, permission);
        }

        public Component(String className, String processName, boolean exported, boolean exportedExplicit,
                         boolean enabled, boolean isolatedProcess, String authorities, String permission) {
            this(className, processName, exported, exportedExplicit, enabled, isolatedProcess, authorities,
                    permission, permission, permission, false);
        }

        public Component(String className, String processName, boolean exported, boolean exportedExplicit,
                         boolean enabled, boolean isolatedProcess, String authorities, String permission,
                         String readPermission, String writePermission, boolean grantUriPermissions) {
            this.className = normalize(className);
            this.processName = normalize(processName);
            this.exported = exported;
            this.exportedExplicit = exportedExplicit;
            this.enabled = enabled;
            this.isolatedProcess = isolatedProcess;
            this.authorities = normalize(authorities);
            this.permission = normalize(permission);
            this.readPermission = normalize(readPermission);
            this.writePermission = normalize(writePermission);
            this.grantUriPermissions = grantUriPermissions;
        }

        public String className() { return className; }
        public String processName() { return processName; }
        public boolean exported() { return exportedExplicit ? exported : intentFilterDeclared; }
        public boolean exportedExplicit() { return exportedExplicit; }
        public boolean enabled() { return enabled; }
        public boolean isolatedProcess() { return isolatedProcess; }
        public String authorities() { return authorities; }
        public String permission() { return permission; }
        public String readPermission() { return readPermission; }
        public String writePermission() { return writePermission; }
        public boolean grantUriPermissions() { return grantUriPermissions; }
        public List<ProviderPathRule> providerPathRules() {
            return Collections.unmodifiableList(providerPathRules);
        }
        public void addProviderPathRule(ProviderPathRule rule) {
            if (rule != null) providerPathRules.add(rule);
        }
        public List<String> actions() { return Collections.unmodifiableList(actions); }
        public List<IntentFilter> intentFilters() { return Collections.unmodifiableList(intentFilters); }
        public void addAction(String action) {
            if (action != null && !action.trim().isEmpty() && !actions.contains(action)) actions.add(action);
        }
        public IntentFilter addIntentFilter(int priority) {
            intentFilterDeclared = true;
            IntentFilter filter = new IntentFilter(priority);
            intentFilters.add(filter);
            return filter;
        }
        public void intentFilterDeclared() { intentFilterDeclared = true; }
        public boolean hasIntentFilter() { return intentFilterDeclared; }
        public boolean launcher() { return launcher; }
        public void launcher(boolean value) { launcher = value; }
    }

    public static final class ProviderPathRule {
        private final String path;
        private final String pathPrefix;
        private final String pathPattern;
        private final String readPermission;
        private final String writePermission;
        private final boolean uriGrantRule;

        public ProviderPathRule(String path, String pathPrefix, String pathPattern,
                                String readPermission, String writePermission,
                                boolean uriGrantRule) {
            this.path = normalize(path);
            this.pathPrefix = normalize(pathPrefix);
            this.pathPattern = normalize(pathPattern);
            int matchers = (this.path.isEmpty() ? 0 : 1) + (this.pathPrefix.isEmpty() ? 0 : 1)
                    + (this.pathPattern.isEmpty() ? 0 : 1);
            if (matchers != 1) throw new IllegalArgumentException(
                    "Provider path rule requires exactly one path matcher");
            this.readPermission = normalize(readPermission);
            this.writePermission = normalize(writePermission);
            this.uriGrantRule = uriGrantRule;
        }

        public String path() { return path; }
        public String pathPrefix() { return pathPrefix; }
        public String pathPattern() { return pathPattern; }
        public String readPermission() { return readPermission; }
        public String writePermission() { return writePermission; }
        public boolean uriGrantRule() { return uriGrantRule; }
    }

    public static final class IntentFilter {
        private final int priority;
        private final Set<String> actions = new LinkedHashSet<>();
        private final Set<String> categories = new LinkedHashSet<>();
        private final List<DataRule> dataRules = new ArrayList<>();

        IntentFilter(int priority) {
            if (priority < -1000 || priority > 1000) throw new IllegalArgumentException("intent-filter priority out of range");
            this.priority = priority;
        }

        public int priority() { return priority; }
        public Set<String> actions() { return Collections.unmodifiableSet(actions); }
        public Set<String> categories() { return Collections.unmodifiableSet(categories); }
        public List<DataRule> dataRules() { return Collections.unmodifiableList(dataRules); }
        public void addAction(String action) { addNonBlank(actions, action); }
        public void addCategory(String category) { addNonBlank(categories, category); }
        public void addDataRule(DataRule rule) { if (rule != null) dataRules.add(rule); }
    }

    public static final class DataRule {
        private final String scheme;
        private final String host;
        private final String path;
        private final String pathPrefix;
        private final String pathPattern;
        private final String mimeType;

        public DataRule(String scheme, String host, String path, String pathPrefix,
                        String pathPattern, String mimeType) {
            this.scheme = normalize(scheme);
            this.host = normalize(host);
            this.path = normalize(path);
            this.pathPrefix = normalize(pathPrefix);
            this.pathPattern = normalize(pathPattern);
            this.mimeType = normalize(mimeType);
        }

        public String scheme() { return scheme; }
        public String host() { return host; }
        public String path() { return path; }
        public String pathPrefix() { return pathPrefix; }
        public String pathPattern() { return pathPattern; }
        public String mimeType() { return mimeType; }
        public boolean empty() {
            return scheme.isEmpty() && host.isEmpty() && path.isEmpty() && pathPrefix.isEmpty()
                    && pathPattern.isEmpty() && mimeType.isEmpty();
        }
    }

    private static void addNonBlank(Set<String> target, String value) {
        if (value != null && !value.trim().isEmpty()) target.add(value.trim());
    }
    private static String normalize(String value) { return value == null ? "" : value.trim(); }
}
