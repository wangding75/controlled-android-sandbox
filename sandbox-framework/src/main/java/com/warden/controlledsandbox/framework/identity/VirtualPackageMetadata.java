package com.warden.controlledsandbox.framework.identity;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.PathPermission;
import android.os.PatternMatcher;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Immutable public-API package view used by the process-local PackageManager proxy. */
public final class VirtualPackageMetadata {
    public enum Type { ACTIVITY, SERVICE, RECEIVER, PROVIDER }

    public static final long MATCH_DISABLED_COMPONENTS = 0x00000200L;
    public static final long MATCH_DEFAULT_ONLY = 0x00010000L;

    public static final class DataRule {
        private final String scheme, host, path, pathPrefix, pathPattern, mimeType;
        public DataRule(String scheme, String host, String path, String pathPrefix,
                        String pathPattern, String mimeType) {
            this.scheme = value(scheme).toLowerCase(Locale.ROOT);
            this.host = value(host).toLowerCase(Locale.ROOT);
            this.path = value(path); this.pathPrefix = value(pathPrefix);
            this.pathPattern = value(pathPattern);
            this.mimeType = value(mimeType).toLowerCase(Locale.ROOT);
        }
        public String scheme() { return scheme; }
        public String host() { return host; }
        public String path() { return path; }
        public String pathPrefix() { return pathPrefix; }
        public String pathPattern() { return pathPattern; }
        public String mimeType() { return mimeType; }
    }

    public static final class Filter {
        private final int priority;
        private final Set<String> actions;
        private final Set<String> categories;
        private final List<DataRule> data;
        public Filter(int priority, Set<String> actions, Set<String> categories, List<DataRule> data) {
            if (priority < -1000 || priority > 1000) throw new IllegalArgumentException("priority out of range");
            this.priority = priority;
            this.actions = immutableSet(actions);
            this.categories = immutableSet(categories);
            this.data = Collections.unmodifiableList(new ArrayList<>(data == null ? List.of() : data));
        }
        public int priority() { return priority; }
        public Set<String> actions() { return actions; }
        public Set<String> categories() { return categories; }
        public List<DataRule> data() { return data; }
        boolean defaultCategory() { return categories.contains("android.intent.category.DEFAULT"); }
    }

    public static final class SharedLibrary {
        private final String kind;
        private final String name;
        private final boolean required;
        private final long version;
        private final String certificateDigest;
        private final boolean resolved;
        private final String providerPackage;

        public SharedLibrary(String kind, String name, boolean required, long version,
                             String certificateDigest, boolean resolved, String providerPackage) {
            this.kind = requireText(kind, "kind").toUpperCase(Locale.ROOT);
            if (!Set.of("JAVA", "NATIVE", "SDK", "STATIC").contains(this.kind)) {
                throw new IllegalArgumentException("unsupported shared library kind " + kind);
            }
            this.name = requireText(name, "name");
            if (version < 0) throw new IllegalArgumentException("shared library version is invalid");
            this.version = version;
            this.required = required;
            String digest = value(certificateDigest).toLowerCase(Locale.ROOT).replace(":", "");
            if (!digest.isEmpty() && !digest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("shared library certificate digest is invalid");
            }
            this.certificateDigest = digest;
            this.resolved = resolved;
            this.providerPackage = value(providerPackage);
            if (resolved && this.providerPackage.isEmpty()) {
                throw new IllegalArgumentException("resolved shared library requires provider package");
            }
        }
        public String kind() { return kind; }
        public String name() { return name; }
        public boolean required() { return required; }
        public long version() { return version; }
        public String certificateDigest() { return certificateDigest; }
        public boolean resolved() { return resolved; }
        public String providerPackage() { return providerPackage; }
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
            this.className = requireText(className, "className");
            this.targetPackage = requireText(targetPackage, "targetPackage");
            this.targetProcesses = value(targetProcesses);
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
            this.path = value(path);
            this.pathPrefix = value(pathPrefix);
            this.pathPattern = value(pathPattern);
            int matchers = (this.path.isEmpty() ? 0 : 1) + (this.pathPrefix.isEmpty() ? 0 : 1)
                    + (this.pathPattern.isEmpty() ? 0 : 1);
            if (matchers != 1) throw new IllegalArgumentException(
                    "Provider path rule requires exactly one matcher");
            this.readPermission = value(readPermission);
            this.writePermission = value(writePermission);
            this.uriGrantRule = uriGrantRule;
        }

        public String path() { return path; }
        public String pathPrefix() { return pathPrefix; }
        public String pathPattern() { return pathPattern; }
        public String readPermission() { return readPermission; }
        public String writePermission() { return writePermission; }
        public boolean uriGrantRule() { return uriGrantRule; }
    }

    public static final class Component {
        private final Type type;
        private final String className;
        private final String processName;
        private final boolean exported;
        private final boolean enabled;
        private final boolean isolated;
        private final Set<String> actions;
        private final String authority;
        private final String permission;
        private final String readPermission;
        private final String writePermission;
        private final boolean grantUriPermissions;
        private final String enabledSetting;
        private final List<Filter> filters;
        private final List<ProviderPathRule> providerPathRules;
        private final Bundle metaData;
        private final String launchMode;
        private final String taskAffinity;
        private final String documentLaunchMode;
        private final int configChanges;
        private final String screenOrientation;
        private final int windowSoftInputMode;
        private final int flags;
        private final boolean excludeFromRecents;
        private final boolean noHistory;
        private final boolean finishOnTaskLaunch;
        private final boolean clearTaskOnLaunch;
        private final boolean alwaysRetainTaskState;
        private final boolean allowTaskReparenting;
        private final String resizeMode;
        private final float maxAspectRatio;
        private final float minAspectRatio;
        private final boolean supportsPictureInPicture;
        private final int themeResId;
        private final int foregroundServiceType;
        private final boolean stopWithTask;
        private final boolean directBootAware;
        private final boolean multiprocess;
        private final int initOrder;
        private final boolean syncable;

        public Component(Type type, String className, String processName,
                         boolean exported, boolean enabled, boolean isolated,
                         Set<String> actions, String authority) {
            this(type, className, processName, exported, enabled, isolated, actions, authority, "");
        }

        public Component(Type type, String className, String processName,
                         boolean exported, boolean enabled, boolean isolated,
                         Set<String> actions, String authority, String permission) {
            this(type, className, processName, exported, enabled, isolated, actions, authority,
                    permission, "DEFAULT", legacyFilters(actions));
        }

        public Component(Type type, String className, String processName,
                         boolean exported, boolean enabled, boolean isolated,
                         Set<String> actions, String authority, String permission,
                         String enabledSetting, List<Filter> filters) {
            this(type, className, processName, exported, enabled, isolated, actions, authority,
                    permission, permission, permission, false, enabledSetting, filters, List.of());
        }

        public Component(Type type, String className, String processName,
                         boolean exported, boolean enabled, boolean isolated, Set<String> actions,
                         String authority, String permission, String readPermission,
                         String writePermission, boolean grantUriPermissions, String enabledSetting,
                         List<Filter> filters, List<ProviderPathRule> providerPathRules) {
            this(type, className, processName, exported, enabled, isolated, actions, authority,
                    permission, readPermission, writePermission, grantUriPermissions, enabledSetting,
                    filters, providerPathRules, null, "standard", "", "none", 0, "", 0, 0,
                    false, false, false, false, false, false, "", 0f, 0f, false, 0,
                    0, false, false);
        }

        public Component(Type type, String className, String processName,
                         boolean exported, boolean enabled, boolean isolated, Set<String> actions,
                         String authority, String permission, String readPermission,
                         String writePermission, boolean grantUriPermissions, String enabledSetting,
                         List<Filter> filters, List<ProviderPathRule> providerPathRules,
                         Bundle metaData) {
            this(type, className, processName, exported, enabled, isolated, actions, authority,
                    permission, readPermission, writePermission, grantUriPermissions, enabledSetting,
                    filters, providerPathRules, metaData, "standard", "", "none", 0, "", 0, 0,
                    false, false, false, false, false, false, "", 0f, 0f, false, 0,
                    0, false, false);
        }

        public Component(Type type, String className, String processName,
                         boolean exported, boolean enabled, boolean isolated, Set<String> actions,
                         String authority, String permission, String readPermission,
                         String writePermission, boolean grantUriPermissions, String enabledSetting,
                         List<Filter> filters, List<ProviderPathRule> providerPathRules,
                         Bundle metaData, String launchMode, String taskAffinity,
                         String documentLaunchMode, int configChanges, String screenOrientation,
                         int windowSoftInputMode, int flags, boolean excludeFromRecents,
                         boolean noHistory, boolean finishOnTaskLaunch, boolean clearTaskOnLaunch,
                         boolean alwaysRetainTaskState, boolean allowTaskReparenting, String resizeMode,
                         float maxAspectRatio, float minAspectRatio, boolean supportsPictureInPicture,
                         int themeResId) {
            this(type, className, processName, exported, enabled, isolated, actions, authority,
                    permission, readPermission, writePermission, grantUriPermissions, enabledSetting,
                    filters, providerPathRules, metaData, launchMode, taskAffinity,
                    documentLaunchMode, configChanges, screenOrientation, windowSoftInputMode, flags,
                    excludeFromRecents, noHistory, finishOnTaskLaunch, clearTaskOnLaunch,
                    alwaysRetainTaskState, allowTaskReparenting, resizeMode, maxAspectRatio,
                    minAspectRatio, supportsPictureInPicture, themeResId, 0, false, false);
        }

        public Component(Type type, String className, String processName,
                         boolean exported, boolean enabled, boolean isolated, Set<String> actions,
                         String authority, String permission, String readPermission,
                         String writePermission, boolean grantUriPermissions, String enabledSetting,
                         List<Filter> filters, List<ProviderPathRule> providerPathRules,
                         Bundle metaData, String launchMode, String taskAffinity,
                         String documentLaunchMode, int configChanges, String screenOrientation,
                         int windowSoftInputMode, int flags, boolean excludeFromRecents,
                         boolean noHistory, boolean finishOnTaskLaunch, boolean clearTaskOnLaunch,
                         boolean alwaysRetainTaskState, boolean allowTaskReparenting, String resizeMode,
                          float maxAspectRatio, float minAspectRatio, boolean supportsPictureInPicture,
                          int themeResId, int foregroundServiceType, boolean stopWithTask,
                          boolean directBootAware) {
            this(type, className, processName, exported, enabled, isolated, actions, authority,
                    permission, readPermission, writePermission, grantUriPermissions, enabledSetting,
                    filters, providerPathRules, metaData, launchMode, taskAffinity,
                    documentLaunchMode, configChanges, screenOrientation, windowSoftInputMode, flags,
                    excludeFromRecents, noHistory, finishOnTaskLaunch, clearTaskOnLaunch,
                    alwaysRetainTaskState, allowTaskReparenting, resizeMode, maxAspectRatio,
                    minAspectRatio, supportsPictureInPicture, themeResId, foregroundServiceType,
                    stopWithTask, directBootAware, false, 0, false);
        }

        public Component(Type type, String className, String processName,
                         boolean exported, boolean enabled, boolean isolated, Set<String> actions,
                         String authority, String permission, String readPermission,
                         String writePermission, boolean grantUriPermissions, String enabledSetting,
                         List<Filter> filters, List<ProviderPathRule> providerPathRules,
                         Bundle metaData, String launchMode, String taskAffinity,
                         String documentLaunchMode, int configChanges, String screenOrientation,
                         int windowSoftInputMode, int flags, boolean excludeFromRecents,
                         boolean noHistory, boolean finishOnTaskLaunch, boolean clearTaskOnLaunch,
                         boolean alwaysRetainTaskState, boolean allowTaskReparenting, String resizeMode,
                         float maxAspectRatio, float minAspectRatio, boolean supportsPictureInPicture,
                         int themeResId, int foregroundServiceType, boolean stopWithTask,
                         boolean directBootAware, boolean multiprocess, int initOrder,
                         boolean syncable) {
            this.type = java.util.Objects.requireNonNull(type, "type");
            this.className = requireText(className, "className");
            this.processName = value(processName);
            this.exported = exported;
            this.enabled = enabled;
            this.isolated = isolated;
            this.actions = immutableSet(actions);
            this.authority = value(authority);
            this.permission = value(permission);
            this.readPermission = value(readPermission);
            this.writePermission = value(writePermission);
            this.grantUriPermissions = grantUriPermissions;
            this.enabledSetting = VirtualPackageMetadata.enabledSetting(enabledSetting);
            this.filters = Collections.unmodifiableList(new ArrayList<>(filters == null ? List.of() : filters));
            this.providerPathRules = Collections.unmodifiableList(new ArrayList<>(
                    providerPathRules == null ? List.of() : providerPathRules));
            this.metaData = metaData == null ? null : new Bundle(metaData);
            this.launchMode = enumValue(launchMode, "standard");
            this.taskAffinity = value(taskAffinity);
            this.documentLaunchMode = enumValue(documentLaunchMode, "none");
            if (configChanges < 0 || windowSoftInputMode < 0 || flags < 0
                    || maxAspectRatio < 0f || minAspectRatio < 0f) {
                throw new IllegalArgumentException("activity task contract contains a negative value");
            }
            this.configChanges = configChanges;
            this.screenOrientation = value(screenOrientation);
            this.windowSoftInputMode = windowSoftInputMode;
            this.flags = flags;
            this.excludeFromRecents = excludeFromRecents;
            this.noHistory = noHistory;
            this.finishOnTaskLaunch = finishOnTaskLaunch;
            this.clearTaskOnLaunch = clearTaskOnLaunch;
            this.alwaysRetainTaskState = alwaysRetainTaskState;
            this.allowTaskReparenting = allowTaskReparenting;
            this.resizeMode = value(resizeMode);
            this.maxAspectRatio = maxAspectRatio;
            this.minAspectRatio = minAspectRatio;
            this.supportsPictureInPicture = supportsPictureInPicture;
            if (themeResId < 0) throw new IllegalArgumentException("themeResId must be non-negative");
            this.themeResId = themeResId;
            if (foregroundServiceType < 0) {
                throw new IllegalArgumentException("foregroundServiceType must be non-negative");
            }
            this.foregroundServiceType = foregroundServiceType;
            this.stopWithTask = stopWithTask;
            this.directBootAware = directBootAware;
            this.multiprocess = multiprocess;
            if (initOrder < 0) throw new IllegalArgumentException("initOrder must be non-negative");
            this.initOrder = initOrder;
            this.syncable = syncable;
        }

        public Type type() { return type; }
        public String className() { return className; }
        public String processName() { return processName; }
        public boolean exported() { return exported; }
        public boolean enabled() { return enabled; }
        public boolean isolated() { return isolated; }
        public Set<String> actions() { return actions; }
        public String authority() { return authority; }
        public String permission() { return permission; }
        public String readPermission() { return readPermission; }
        public String writePermission() { return writePermission; }
        public boolean grantUriPermissions() { return grantUriPermissions; }
        public String enabledSetting() { return enabledSetting; }
        public List<Filter> filters() { return filters; }
        public List<ProviderPathRule> providerPathRules() { return providerPathRules; }
        public Bundle metaData() { return metaData == null ? null : new Bundle(metaData); }
        public String launchMode() { return launchMode; }
        public String taskAffinity() { return taskAffinity; }
        public String documentLaunchMode() { return documentLaunchMode; }
        public int configChanges() { return configChanges; }
        public String screenOrientation() { return screenOrientation; }
        public int windowSoftInputMode() { return windowSoftInputMode; }
        public int flags() { return flags; }
        public boolean excludeFromRecents() { return excludeFromRecents; }
        public boolean noHistory() { return noHistory; }
        public boolean finishOnTaskLaunch() { return finishOnTaskLaunch; }
        public boolean clearTaskOnLaunch() { return clearTaskOnLaunch; }
        public boolean alwaysRetainTaskState() { return alwaysRetainTaskState; }
        public boolean allowTaskReparenting() { return allowTaskReparenting; }
        public String resizeMode() { return resizeMode; }
        public float maxAspectRatio() { return maxAspectRatio; }
        public float minAspectRatio() { return minAspectRatio; }
        public boolean supportsPictureInPicture() { return supportsPictureInPicture; }
        public int themeResId() { return themeResId; }
        public int foregroundServiceType() { return foregroundServiceType; }
        public boolean stopWithTask() { return stopWithTask; }
        public boolean directBootAware() { return directBootAware; }
        public boolean multiprocess() { return multiprocess; }
        public int initOrder() { return initOrder; }
        public boolean syncable() { return syncable; }
    }

    private final String packageName;
    private final String launcherActivity;
    private final ApplicationInfo applicationInfo;
    private final List<Component> components;
    private final Map<String, Component> byClass;
    private final Map<String, Component> providersByAuthority;
    private final String versionName;
    private final long versionCode;
    private final String signatureSha256;
    private final long firstInstallTime;
    private final long lastUpdateTime;
    private final String installerPackageName;
    private final List<String> sharedLibraries;
    private final List<SharedLibrary> sharedLibraryDetails;
    private final List<Instrumentation> instrumentations;
    private final Map<String, Instrumentation> instrumentationsByClass;
    private final List<String> requestedPermissions;
    private final Set<String> queryPackages;
    private final Set<String> queryProviderAuthorities;
    private final List<Filter> queryIntentFilters;
    private final boolean enabled;
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> enabledSettingOverrides =
            new java.util.concurrent.ConcurrentHashMap<>();

    public VirtualPackageMetadata(String packageName, String launcherActivity,
                                  ApplicationInfo applicationInfo, List<Component> components) {
        this(packageName, launcherActivity, applicationInfo, components, "", 0L, "",
                0L, 0L, "", List.of(), List.of(), true);
    }

    public VirtualPackageMetadata(String packageName, String launcherActivity,
                                  ApplicationInfo applicationInfo, List<Component> components,
                                  String versionName, long versionCode, String signatureSha256,
                                  long firstInstallTime, long lastUpdateTime,
                                  String installerPackageName, List<String> sharedLibraries,
                                  List<String> requestedPermissions, boolean enabled) {
        this(packageName, launcherActivity, applicationInfo, components, versionName, versionCode,
                signatureSha256, firstInstallTime, lastUpdateTime, installerPackageName,
                sharedLibraries, List.of(), List.of(), requestedPermissions, enabled);
    }

    public VirtualPackageMetadata(String packageName, String launcherActivity,
                                  ApplicationInfo applicationInfo, List<Component> components,
                                  String versionName, long versionCode, String signatureSha256,
                                  long firstInstallTime, long lastUpdateTime,
                                  String installerPackageName, List<String> sharedLibraries,
                                  List<SharedLibrary> sharedLibraryDetails,
                                  List<Instrumentation> instrumentations,
                                  List<String> requestedPermissions, boolean enabled) {
        this(packageName, launcherActivity, applicationInfo, components, versionName, versionCode,
                signatureSha256, firstInstallTime, lastUpdateTime, installerPackageName,
                sharedLibraries, sharedLibraryDetails, instrumentations, requestedPermissions,
                enabled, Set.of(), Set.of(), List.of());
    }

    public VirtualPackageMetadata(String packageName, String launcherActivity,
                                  ApplicationInfo applicationInfo, List<Component> components,
                                  String versionName, long versionCode, String signatureSha256,
                                  long firstInstallTime, long lastUpdateTime,
                                  String installerPackageName, List<String> sharedLibraries,
                                  List<SharedLibrary> sharedLibraryDetails,
                                  List<Instrumentation> instrumentations,
                                  List<String> requestedPermissions, boolean enabled,
                                  Set<String> queryPackages, Set<String> queryProviderAuthorities,
                                  List<Filter> queryIntentFilters) {
        this.packageName = requireText(packageName, "packageName");
        this.launcherActivity = value(launcherActivity);
        this.applicationInfo = new ApplicationInfo(applicationInfo);
        this.versionName = value(versionName);
        this.versionCode = Math.max(0L, versionCode);
        this.signatureSha256 = value(signatureSha256).toLowerCase(Locale.ROOT);
        this.firstInstallTime = Math.max(0L, firstInstallTime);
        this.lastUpdateTime = Math.max(this.firstInstallTime, lastUpdateTime);
        this.installerPackageName = value(installerPackageName);
        this.sharedLibraries = immutableList(sharedLibraries);
        this.sharedLibraryDetails = Collections.unmodifiableList(
                new ArrayList<>(sharedLibraryDetails == null ? List.of() : sharedLibraryDetails));
        this.instrumentations = Collections.unmodifiableList(
                new ArrayList<>(instrumentations == null ? List.of() : instrumentations));
        Map<String, Instrumentation> instrumentationMap = new LinkedHashMap<>();
        for (Instrumentation instrumentation : this.instrumentations) {
            if (instrumentationMap.put(instrumentation.className(), instrumentation) != null) {
                throw new IllegalArgumentException("Duplicate instrumentation "
                        + instrumentation.className());
            }
        }
        this.instrumentationsByClass = Collections.unmodifiableMap(instrumentationMap);
        this.requestedPermissions = immutableList(requestedPermissions);
        this.queryPackages = immutableSet(queryPackages);
        this.queryProviderAuthorities = immutableSet(queryProviderAuthorities);
        this.queryIntentFilters = Collections.unmodifiableList(new ArrayList<>(
                queryIntentFilters == null ? List.of() : queryIntentFilters));
        this.enabled = enabled;
        List<Component> copy = new ArrayList<>(components == null ? List.of() : components);
        Map<String, Component> classes = new LinkedHashMap<>();
        Map<String, Component> authorities = new LinkedHashMap<>();
        List<Component> accepted = new ArrayList<>();
        for (Component component : copy) {
            if (classes.containsKey(component.className())) {
                // PackageManager keeps the first component when a malformed manifest repeats
                // the same component name. Do not let a duplicate declaration replace it.
                continue;
            }
            if (component.type() == Type.PROVIDER && !component.authority().isEmpty()) {
                for (String authority : component.authority().split(";")) {
                    String normalized = authority.trim();
                    // Keep the first authority owner for resolver routing, but retain every
                    // ProviderInfo by class for PackageManager queries. Real APKs can declare
                    // multiple providers with the same authority and the platform exposes both
                    // component records even though only the first owns the authority.
                    if (!normalized.isEmpty() && !authorities.containsKey(normalized)) {
                        authorities.put(normalized, component);
                    }
                }
            }
            classes.put(component.className(), component);
            accepted.add(component);
        }
        this.components = Collections.unmodifiableList(accepted);
        byClass = Collections.unmodifiableMap(classes);
        providersByAuthority = Collections.unmodifiableMap(authorities);
    }

    public String packageName() { return packageName; }
    public String launcherActivity() { return launcherActivity; }
    public ApplicationInfo applicationInfo() { return new ApplicationInfo(applicationInfo); }
    public List<Component> components() { return components; }
    public String installerPackageName() { return installerPackageName; }
    public List<String> sharedLibraries() { return sharedLibraries; }
    public List<SharedLibrary> sharedLibraryDetails() { return sharedLibraryDetails; }
    public List<Instrumentation> instrumentations() { return instrumentations; }
    public Set<String> queryPackages() { return queryPackages; }
    public Set<String> queryProviderAuthorities() { return queryProviderAuthorities; }
    public List<Filter> queryIntentFilters() { return queryIntentFilters; }
    public List<String> resolvedSharedLibraryNames() {
        List<String> names = new ArrayList<>();
        for (SharedLibrary library : sharedLibraryDetails) {
            if (library.resolved() && !names.contains(library.name())) names.add(library.name());
        }
        return Collections.unmodifiableList(names);
    }

    public List<String> requestedPermissions() {
        return requestedPermissions;
    }
    public String signatureSha256() { return signatureSha256; }
    public boolean enabled() { return enabled; }

    public Component component(String className) { return byClass.get(normalizeClass(className)); }
    public boolean isIsolatedComponent(String className) {
        Component component = component(className); return component != null && component.isolated();
    }
    public int componentEnabledSetting(ComponentName name) {
        if (name == null || !packageName.equals(name.getPackageName())) return 0;
        Integer override = enabledSettingOverrides.get(normalizeClass(name.getClassName()));
        if (override != null) return override;
        Component item = byClass.get(normalizeClass(name.getClassName()));
        if (item == null) return 0;
        if ("ENABLED".equals(item.enabledSetting())) return 1;
        if ("DISABLED".equals(item.enabledSetting())) return 2;
        return 0;
    }

    public void setComponentEnabledSetting(ComponentName name, int newState) {
        if (name == null || !packageName.equals(name.getPackageName())) {
            throw new SecurityException("COMPONENT_STATE_FOREIGN_PACKAGE");
        }
        if (newState < 0 || newState > 4) {
            throw new IllegalArgumentException("COMPONENT_ENABLED_STATE_INVALID");
        }
        enabledSettingOverrides.put(normalizeClass(name.getClassName()), newState);
    }

    public PackageInfo packageInfo() { return packageInfo(~0L); }
    public PackageInfo packageInfo(long flags) {
        PackageInfo info = new PackageInfo();
        info.packageName = packageName; info.versionName = versionName;
        info.versionCode = versionCode > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) versionCode;
        info.firstInstallTime = firstInstallTime; info.lastUpdateTime = lastUpdateTime;
        info.applicationInfo = applicationInfo();
        if ((flags & 0x00000001L) != 0) info.activities = activityInfos(Type.ACTIVITY, flags);
        if ((flags & 0x00000002L) != 0) info.receivers = activityInfos(Type.RECEIVER, flags);
        if ((flags & 0x00000004L) != 0) info.services = serviceInfos(flags);
        if ((flags & 0x00000008L) != 0) info.providers = providerInfos(flags);
        if ((flags & 0x00000010L) != 0) info.instrumentation = instrumentationInfos(flags);
        if ((flags & 0x00001000L) != 0) info.requestedPermissions = requestedPermissions.toArray(new String[0]);
        return info;
    }

    public ComponentInfo componentInfo(ComponentName name, Type expected) {
        return componentInfo(name, expected, 0L);
    }
    public ComponentInfo componentInfo(ComponentName name, Type expected, long flags) {
        if (name == null || !packageName.equals(name.getPackageName())) return null;
        Component component = byClass.get(normalizeClass(name.getClassName()));
        if (component == null || component.type() != expected || !visible(component, flags)) return null;
        return toInfo(component);
    }

    /** Internal PMS policy view used when another virtual package resolves this component. */
    public Component component(String className, Type expected) {
        Component component = byClass.get(normalizeClass(className));
        return component == null || component.type() != expected ? null : component;
    }

    /** Returns the manifest provider owning an authority, including non-exported providers. */
    public Component providerComponent(String authority) {
        return providersByAuthority.get(value(authority));
    }

    public ResolveInfo resolve(Intent intent, Type type) { return resolve(intent, type, 0L); }
    public ResolveInfo resolve(Intent intent, Type type, long flags) {
        List<ResolveInfo> matches = query(intent, type, flags);
        return matches.isEmpty() ? null : matches.get(0);
    }

    public List<ResolveInfo> query(Intent intent, Type type) { return query(intent, type, 0L); }
    public List<ResolveInfo> query(Intent intent, Type type, long flags) {
        List<Match> matches = new ArrayList<>();
        Intent value = intent == null ? new Intent() : intent;
        ComponentName explicit = value.getComponent();
        String targetPackage = value.getPackage();
        if (targetPackage != null && !targetPackage.isEmpty() && !packageName.equals(targetPackage)) return List.of();
        if (explicit != null) {
            ComponentInfo info = componentInfo(explicit, type, flags);
            return info == null ? List.of() : List.of(resolveInfo(type, info, 0, true, 0x100000));
        }
        for (Component component : components) {
            if (component.type() != type || !visible(component, flags)) continue;
            for (Filter filter : component.filters()) {
                if ((flags & MATCH_DEFAULT_ONLY) != 0 && !filter.defaultCategory()) continue;
                Match match = match(value, filter, flags);
                if (match != null) matches.add(new Match(component, filter, match.score));
            }
        }
        matches.sort(Comparator.comparingInt((Match item) -> item.filter.priority()).reversed()
                .thenComparing(Comparator.comparingInt((Match item) -> item.score).reversed())
                .thenComparing(item -> item.component.className()));
        List<ResolveInfo> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Match item : matches) {
            if (!seen.add(item.component.className())) continue;
            out.add(resolveInfo(type, toInfo(item.component), item.filter.priority(),
                    item.filter.defaultCategory(), item.score));
        }
        return out;
    }

    /** Returns whether an Android 11+ {@code <queries><intent>} declaration can see this APK. */
    public boolean matchesQueryFilter(Filter query) {
        if (query == null) return false;
        for (Component component : components) {
            for (Filter declared : component.filters()) {
                if (filtersIntersect(query, declared)) return true;
            }
        }
        return false;
    }

    private static boolean filtersIntersect(Filter query, Filter declared) {
        if (!query.actions().isEmpty() && !declared.actions().isEmpty()) {
            boolean actionMatch = false;
            for (String action : query.actions()) {
                if (declared.actions().contains(action)) { actionMatch = true; break; }
            }
            if (!actionMatch) return false;
        }
        if (!query.categories().isEmpty()
                && !declared.categories().containsAll(query.categories())) return false;
        if (query.data().isEmpty() || declared.data().isEmpty()) return query.data().isEmpty();
        for (DataRule left : query.data()) {
            for (DataRule right : declared.data()) {
                boolean scheme = left.scheme().isEmpty() || right.scheme().isEmpty()
                        || left.scheme().equals(right.scheme());
                boolean mime = left.mimeType().isEmpty() || right.mimeType().isEmpty()
                        || left.mimeType().equals(right.mimeType());
                if (scheme && mime) return true;
            }
        }
        return false;
    }

    public InstrumentationInfo instrumentationInfo(ComponentName name, long flags) {
        if (name == null || !packageName.equals(name.getPackageName())) return null;
        Instrumentation instrumentation = instrumentationsByClass.get(normalizeClass(name.getClassName()));
        return instrumentation == null || !instrumentationVisible(instrumentation, flags)
                ? null : toInstrumentationInfo(instrumentation);
    }

    public List<InstrumentationInfo> queryInstrumentation(String targetPackage, long flags) {
        String target = value(targetPackage);
        List<InstrumentationInfo> result = new ArrayList<>();
        for (Instrumentation instrumentation : instrumentations) {
            if (!target.isEmpty() && !target.equals(instrumentation.targetPackage())) continue;
            if (instrumentationVisible(instrumentation, flags)) {
                result.add(toInstrumentationInfo(instrumentation));
            }
        }
        result.sort(Comparator.comparing(item -> item.name));
        return Collections.unmodifiableList(result);
    }

    public List<Object> sharedLibraryInfoObjects() {
        List<Object> result = new ArrayList<>();
        for (SharedLibrary library : sharedLibraryDetails) {
            if (!library.resolved()) continue;
            Object value = SharedLibraryInfoFactory.create(library);
            if (value == null) {
                throw new IllegalStateException("SHARED_LIBRARY_INFO_UNAVAILABLE:" + library.name());
            }
            result.add(value);
        }
        return Collections.unmodifiableList(result);
    }

    public boolean ownsAuthority(String authority) { return providersByAuthority.containsKey(value(authority)); }

    public ProviderInfo provider(String authority) { return provider(authority, 0L); }
    public ProviderInfo provider(String authority, long flags) {
        Component component = providersByAuthority.get(value(authority));
        return component == null || !visible(component, flags) ? null : (ProviderInfo) toInfo(component);
    }

    public ProviderInfo providerForClass(String className) {
        return providerForClass(className, MATCH_DISABLED_COMPONENTS);
    }

    public ProviderInfo providerForClass(String className, long flags) {
        Component component = byClass.get(normalizeClass(className));
        if (component == null || component.type() != Type.PROVIDER || !visible(component, flags)) {
            return null;
        }
        return (ProviderInfo) toInfo(component);
    }

    public Object adaptCollection(List<?> values, Class<?> returnType) {
        if (List.class.isAssignableFrom(returnType)) return values;
        if (returnType.isArray()) {
            Object array = Array.newInstance(returnType.getComponentType(), values.size());
            for (int index = 0; index < values.size(); index++) Array.set(array, index, values.get(index));
            return array;
        }
        if (returnType.getName().endsWith("ParceledListSlice")) {
            try {
                Constructor<?> constructor = returnType.getDeclaredConstructor(List.class);
                constructor.setAccessible(true); return constructor.newInstance(values);
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Cannot construct " + returnType.getName(), error);
            }
        }
        throw new IllegalStateException("Unsupported package query return type " + returnType.getName());
    }

    private InstrumentationInfo[] instrumentationInfos(long flags) {
        List<InstrumentationInfo> values = queryInstrumentation("", flags);
        return values.toArray(new InstrumentationInfo[0]);
    }

    private boolean instrumentationVisible(Instrumentation instrumentation, long flags) {
        return (enabled && instrumentation.enabled()) || (flags & MATCH_DISABLED_COMPONENTS) != 0;
    }

    private InstrumentationInfo toInstrumentationInfo(Instrumentation instrumentation) {
        InstrumentationInfo info = new InstrumentationInfo();
        info.packageName = packageName;
        info.name = instrumentation.className();
        info.targetPackage = instrumentation.targetPackage();
        info.targetProcesses = instrumentation.targetProcesses();
        info.handleProfiling = instrumentation.handleProfiling();
        info.functionalTest = instrumentation.functionalTest();
        info.sourceDir = applicationInfo.sourceDir;
        info.publicSourceDir = applicationInfo.publicSourceDir;
        info.dataDir = applicationInfo.dataDir;
        return info;
    }

    private ActivityInfo[] activityInfos(Type type, long flags) {
        List<ActivityInfo> out = new ArrayList<>();
        for (Component component : components) if (component.type() == type && visible(component, flags)) out.add((ActivityInfo) toInfo(component));
        return out.toArray(new ActivityInfo[0]);
    }
    private ServiceInfo[] serviceInfos(long flags) {
        List<ServiceInfo> out = new ArrayList<>();
        for (Component component : components) if (component.type() == Type.SERVICE && visible(component, flags)) out.add((ServiceInfo) toInfo(component));
        return out.toArray(new ServiceInfo[0]);
    }
    private ProviderInfo[] providerInfos(long flags) {
        List<ProviderInfo> out = new ArrayList<>();
        for (Component component : components) if (component.type() == Type.PROVIDER && visible(component, flags)) out.add((ProviderInfo) toInfo(component));
        return out.toArray(new ProviderInfo[0]);
    }
    private boolean visible(Component component, long flags) {
        if ((flags & MATCH_DISABLED_COMPONENTS) != 0) return true;
        if (!enabled) return false;
        Integer override = enabledSettingOverrides.get(normalizeClass(component.className()));
        if (override == null || override == 0) return component.enabled();
        return override == 1;
    }

    private ComponentInfo toInfo(Component component) {
        ComponentInfo info;
        if (component.type() == Type.SERVICE) info = new ServiceInfo();
        else if (component.type() == Type.PROVIDER) info = new ProviderInfo();
        else info = new ActivityInfo();
        info.packageName = packageName; info.name = component.className();
        info.processName = component.processName().isEmpty() ? packageName : component.processName();
        info.exported = component.exported();
        info.enabled = visible(component, 0L);
        info.applicationInfo = applicationInfo();
        if (info instanceof ActivityInfo) {
            ((ActivityInfo) info).permission = component.permission();
            projectActivityContract((ActivityInfo) info, component);
        }
        if (info instanceof ServiceInfo) {
            ServiceInfo service = (ServiceInfo) info;
            service.flags = component.isolated() ? ServiceInfo.FLAG_ISOLATED_PROCESS : 0;
            if (component.stopWithTask()) service.flags |= staticInt(ServiceInfo.class, "FLAG_STOP_WITH_TASK");
            service.permission = component.permission();
            setField(service, "foregroundServiceType", component.foregroundServiceType());
        }
        setField(info, "directBootAware", component.directBootAware());
        if (info instanceof ProviderInfo) {
            ProviderInfo provider = (ProviderInfo) info;
            provider.authority = component.authority();
            provider.readPermission = component.readPermission();
            provider.writePermission = component.writePermission();
            provider.grantUriPermissions = component.grantUriPermissions();
            provider.metaData = component.metaData();
            setField(provider, "multiprocess", component.multiprocess());
            setField(provider, "initOrder", component.initOrder());
            setField(provider, "isSyncable", component.syncable());
            List<ProviderPathRule> pathRules = component.providerPathRules().stream()
                    .filter(rule -> !rule.uriGrantRule())
                    .collect(java.util.stream.Collectors.toList());
            if (!pathRules.isEmpty()) {
                PathPermission[] pathPermissions = new PathPermission[pathRules.size()];
                for (int index = 0; index < pathPermissions.length; index++) {
                    ProviderPathRule rule = pathRules.get(index);
                    int kind = rule.path().isEmpty()
                            ? (rule.pathPrefix().isEmpty()
                            ? PatternMatcher.PATTERN_SIMPLE_GLOB : PatternMatcher.PATTERN_PREFIX)
                            : PatternMatcher.PATTERN_LITERAL;
                    String pattern = rule.path().isEmpty()
                            ? (rule.pathPrefix().isEmpty() ? rule.pathPattern() : rule.pathPrefix())
                            : rule.path();
                    pathPermissions[index] = new PathPermission(pattern, kind,
                            rule.readPermission(), rule.writePermission());
                }
                provider.pathPermissions = pathPermissions;
            }
        }
        return info;
    }

    /** Projects manifest task semantics without compiling against hidden/API-specific fields. */
    private static void projectActivityContract(ActivityInfo info, Component component) {
        setField(info, "launchMode", launchMode(component.launchMode()));
        setField(info, "documentLaunchMode", documentLaunchMode(component.documentLaunchMode()));
        setField(info, "taskAffinity", component.taskAffinity());
        setField(info, "configChanges", component.configChanges());
        setField(info, "screenOrientation", orientation(component.screenOrientation()));
        setField(info, "windowSoftInputMode", component.windowSoftInputMode());
        setField(info, "theme", component.themeResId());
        setField(info, "maxAspectRatio", component.maxAspectRatio());
        setField(info, "minAspectRatio", component.minAspectRatio());
        int flags = component.flags();
        if (component.excludeFromRecents()) flags |= staticInt(ActivityInfo.class, "FLAG_EXCLUDE_FROM_RECENTS");
        if (component.noHistory()) flags |= staticInt(ActivityInfo.class, "FLAG_NO_HISTORY");
        if (component.finishOnTaskLaunch()) flags |= staticInt(ActivityInfo.class, "FLAG_FINISH_ON_TASK_LAUNCH");
        if (component.clearTaskOnLaunch()) flags |= staticInt(ActivityInfo.class, "FLAG_CLEAR_TASK_ON_LAUNCH");
        if (component.alwaysRetainTaskState()) flags |= staticInt(ActivityInfo.class, "FLAG_ALWAYS_RETAIN_TASK_STATE");
        if (component.allowTaskReparenting()) flags |= staticInt(ActivityInfo.class, "FLAG_ALLOW_TASK_REPARENTING");
        if (component.supportsPictureInPicture()) flags |= staticInt(ActivityInfo.class, "FLAG_SUPPORTS_PICTURE_IN_PICTURE");
        setField(info, "flags", flags);
        setField(info, "resizeMode", resizeMode(component.resizeMode()));
    }

    private static int launchMode(String value) {
        String name = value(value).toUpperCase(Locale.ROOT);
        if ("SINGLETOP".equals(name)) return staticInt(ActivityInfo.class, "LAUNCH_SINGLE_TOP");
        if ("SINGLETASK".equals(name)) return staticInt(ActivityInfo.class, "LAUNCH_SINGLE_TASK");
        if ("SINGLEINSTANCE".equals(name)) return staticInt(ActivityInfo.class, "LAUNCH_SINGLE_INSTANCE");
        if ("SINGLEINSTANCEPERTASK".equals(name)) return staticInt(ActivityInfo.class, "LAUNCH_SINGLE_INSTANCE_PER_TASK");
        return staticInt(ActivityInfo.class, "LAUNCH_MULTIPLE");
    }

    private static int documentLaunchMode(String value) {
        String name = value(value).toUpperCase(Locale.ROOT);
        if ("INTO_EXISTING".equals(name)) return staticInt(ActivityInfo.class, "DOCUMENT_LAUNCH_INTO_EXISTING");
        if ("ALWAYS".equals(name)) return staticInt(ActivityInfo.class, "DOCUMENT_LAUNCH_ALWAYS");
        if ("NEVER".equals(name)) return staticInt(ActivityInfo.class, "DOCUMENT_LAUNCH_NEVER");
        return staticInt(ActivityInfo.class, "DOCUMENT_LAUNCH_NONE");
    }

    private static int orientation(String value) {
        String name = value(value).toUpperCase(Locale.ROOT);
        if (name.isEmpty()) return staticInt(ActivityInfo.class, "SCREEN_ORIENTATION_UNSPECIFIED");
        return staticInt(ActivityInfo.class, "SCREEN_ORIENTATION_" + name);
    }

    private static int resizeMode(String value) {
        String name = value(value).toUpperCase(Locale.ROOT);
        if (name.isEmpty()) return staticInt(ActivityInfo.class, "RESIZE_MODE_RESIZEABLE");
        return staticInt(ActivityInfo.class, "RESIZE_MODE_" + name);
    }

    private static int staticInt(Class<?> type, String name) {
        try {
            java.lang.reflect.Field field = type.getField(name);
            return field.getInt(null);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getField(name);
            field.set(target, value);
        } catch (Throwable ignored) {
            // API adapters intentionally leave fields unavailable on older framework releases.
        }
    }

    private static ResolveInfo resolveInfo(Type type, ComponentInfo info, int priority,
                                           boolean isDefault, int match) {
        ResolveInfo out = new ResolveInfo();
        if (type == Type.ACTIVITY || type == Type.RECEIVER) out.activityInfo = (ActivityInfo) info;
        else if (type == Type.SERVICE) out.serviceInfo = (ServiceInfo) info;
        else out.providerInfo = (ProviderInfo) info;
        out.priority = priority; out.isDefault = isDefault; out.match = match;
        return out;
    }

    private static Match match(Intent intent, Filter filter, long flags) {
        String action = value(intent.getAction());
        if (!action.isEmpty() && !filter.actions().contains(action)) return null;
        if (action.isEmpty() && !filter.actions().isEmpty()) return null;
        Set<String> categories = intent.getCategories();
        if (categories != null && !filter.categories().containsAll(categories)) return null;
        if ((flags & MATCH_DEFAULT_ONLY) != 0 && !filter.defaultCategory()) return null;
        String type = value(intent.getType()).toLowerCase(Locale.ROOT);
        String data = intent.getData() == null ? "" : intent.getData().toString();
        if (filter.data().isEmpty()) {
            if (!type.isEmpty() || !data.isEmpty()) return null;
            return new Match(null, filter, 0x10000);
        }
        int best = -1;
        for (DataRule rule : filter.data()) best = Math.max(best, dataMatch(rule, type, data));
        return best < 0 ? null : new Match(null, filter, best);
    }

    private static int dataMatch(DataRule rule, String type, String rawData) {
        if (!mimeMatches(rule.mimeType(), type)) return -1;
        URI uri = null;
        if (!rawData.isEmpty()) {
            try { uri = URI.create(rawData); } catch (IllegalArgumentException ignored) { return -1; }
        }
        if (!rule.scheme().isEmpty()) {
            if (uri == null || !rule.scheme().equalsIgnoreCase(value(uri.getScheme()))) return -1;
        } else if (uri != null && !type.isEmpty()) {
            String scheme = value(uri.getScheme()).toLowerCase(Locale.ROOT);
            if (!scheme.isEmpty() && !"content".equals(scheme) && !"file".equals(scheme)) return -1;
        }
        if (!rule.host().isEmpty() && (uri == null || !rule.host().equalsIgnoreCase(value(uri.getHost())))) return -1;
        String path = uri == null ? "" : value(uri.getPath());
        if (!rule.path().isEmpty() && !rule.path().equals(path)) return -1;
        if (!rule.pathPrefix().isEmpty() && !path.startsWith(rule.pathPrefix())) return -1;
        if (!rule.pathPattern().isEmpty() && !simpleGlob(rule.pathPattern(), path)) return -1;
        int score = 0x20000;
        if (!rule.mimeType().isEmpty()) score += 0x1000;
        if (!rule.scheme().isEmpty()) score += 0x100;
        if (!rule.host().isEmpty()) score += 0x40;
        if (!rule.path().isEmpty() || !rule.pathPrefix().isEmpty() || !rule.pathPattern().isEmpty()) score += 0x20;
        return score;
    }

    private static boolean simpleGlob(String pattern, String value) {
        return AndroidSimpleGlobMatcher.matches(pattern, value);
    }

    private static boolean mimeMatches(String filter, String actual) {
        if (filter.isEmpty()) return actual.isEmpty();
        if (actual.isEmpty()) return false;
        if (filter.equals("*/*")) return actual.contains("/");
        int slash = filter.indexOf('/');
        if (slash > 0 && filter.endsWith("/*")) return actual.startsWith(filter.substring(0, slash + 1));
        return filter.equalsIgnoreCase(actual);
    }


    private static List<Filter> legacyFilters(Set<String> actions) {
        if (actions == null || actions.isEmpty()) return List.of();
        return List.of(new Filter(0, actions, Set.of(), List.of()));
    }
    private static Set<String> immutableSet(Set<String> input) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(input == null ? Set.of() : input));
    }
    private static List<String> immutableList(List<String> input) {
        return Collections.unmodifiableList(new ArrayList<>(input == null ? List.of() : input));
    }
    private String normalizeClass(String className) {
        if (className == null || className.trim().isEmpty()) return "";
        String value = className.trim();
        if (value.startsWith(".")) return packageName + value;
        if (value.indexOf('.') < 0) return packageName + "." + value;
        return value;
    }
    private static String enabledSetting(String value) {
        String normalized = value(value).toUpperCase(Locale.ROOT);
        return Set.of("DEFAULT", "ENABLED", "DISABLED").contains(normalized) ? normalized : "DEFAULT";
    }
    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
    private static String value(String value) { return value == null ? "" : value; }
    private static String enumValue(String value, String fallback) {
        String normalized = value(value).toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? fallback : normalized;
    }

    private static final class Match {
        final Component component; final Filter filter; final int score;
        Match(Component component, Filter filter, int score) { this.component = component; this.filter = filter; this.score = score; }
    }
}
