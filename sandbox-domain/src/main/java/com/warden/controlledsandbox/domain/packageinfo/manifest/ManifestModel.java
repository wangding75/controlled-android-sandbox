package com.warden.controlledsandbox.domain.packageinfo.manifest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ManifestModel {
    private String packageName = "";
    private String applicationClass = "";
    private String applicationPermission = "";
    private int minSdk;
    private int targetSdk;
    private final List<Component> activities = new ArrayList<>();
    private final List<Component> services = new ArrayList<>();
    private final List<Component> receivers = new ArrayList<>();
    private final List<Component> providers = new ArrayList<>();
    private final List<String> permissions = new ArrayList<>();

    public String packageName() { return packageName; }
    public void packageName(String value) { packageName = value == null ? "" : value; }
    public String applicationClass() { return applicationClass; }
    public void applicationClass(String value) { applicationClass = resolveClassName(value); }
    public String applicationPermission() { return applicationPermission; }
    public void applicationPermission(String value) { applicationPermission = value == null ? "" : value.trim(); }
    public int minSdk() { return minSdk; }
    public void minSdk(int value) { minSdk = value; }
    public int targetSdk() { return targetSdk; }
    public void targetSdk(int value) { targetSdk = value; }
    public List<Component> activities() { return Collections.unmodifiableList(activities); }
    public List<Component> services() { return Collections.unmodifiableList(services); }
    public List<Component> receivers() { return Collections.unmodifiableList(receivers); }
    public List<Component> providers() { return Collections.unmodifiableList(providers); }
    public List<String> permissions() { return Collections.unmodifiableList(permissions); }

    public void addActivity(Component component) { activities.add(component); }
    public void addService(Component component) { services.add(component); }
    public void addReceiver(Component component) { receivers.add(component); }
    public void addProvider(Component component) { providers.add(component); }
    public void addPermission(String permission) {
        if (permission != null && !permission.trim().isEmpty() && !permissions.contains(permission)) permissions.add(permission);
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

    public static final class Component {
        private final String className;
        private final String processName;
        private final boolean exported;
        private final boolean exportedExplicit;
        private final boolean enabled;
        private final boolean isolatedProcess;
        private final String authorities;
        private final String permission;
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
            this.className = className == null ? "" : className;
            this.processName = processName == null ? "" : processName;
            this.exported = exported;
            this.exportedExplicit = exportedExplicit;
            this.enabled = enabled;
            this.isolatedProcess = isolatedProcess;
            this.authorities = authorities == null ? "" : authorities;
            this.permission = permission == null ? "" : permission;
        }

        public String className() { return className; }
        public String processName() { return processName; }
        public boolean exported() { return exportedExplicit ? exported : intentFilterDeclared; }
        public boolean exportedExplicit() { return exportedExplicit; }
        public boolean enabled() { return enabled; }
        public boolean isolatedProcess() { return isolatedProcess; }
        public String authorities() { return authorities; }
        public String permission() { return permission; }
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
