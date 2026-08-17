package com.warden.controlledsandbox.framework.identity;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Immutable public-API package view used by the process-local PackageManager proxy. */
public final class VirtualPackageMetadata {
    public enum Type { ACTIVITY, SERVICE, RECEIVER, PROVIDER }

    public static final long MATCH_DISABLED_COMPONENTS = 0x00000200L;
    public static final long MATCH_DEFAULT_ONLY = 0x00010000L;

    public static final class DataRule {
        private final String scheme, host, path, pathPrefix, pathPattern, mimeType;
        private final int port;
        public DataRule(String scheme, String host, String path, String pathPrefix,
                        String pathPattern, String mimeType) {
            this(scheme, host, -1, path, pathPrefix, pathPattern, mimeType);
        }
        public DataRule(String scheme, String host, int port, String path, String pathPrefix,
                        String pathPattern, String mimeType) {
            this.scheme = value(scheme).toLowerCase(Locale.ROOT);
            this.host = value(host).toLowerCase(Locale.ROOT);
            if (port < -1 || port > 65535) throw new IllegalArgumentException("data port out of range");
            this.port = port;
            this.path = value(path); this.pathPrefix = value(pathPrefix);
            this.pathPattern = value(pathPattern);
            this.mimeType = value(mimeType).toLowerCase(Locale.ROOT);
        }
        public String scheme() { return scheme; }
        public String host() { return host; }
        public int port() { return port; }
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

    /** Framework-neutral custom permission declaration projected by the virtual PMS. */
    public static final class PermissionDeclaration {
        private final String name;
        private final String group;
        private final String label;
        private final String description;
        private final int labelRes;
        private final int descriptionRes;
        private final int icon;
        private final int protectionLevel;
        private final int flags;
        private final boolean tree;

        public PermissionDeclaration(String name, String group, String label, String description,
                                     int labelRes, int descriptionRes, int icon,
                                     int protectionLevel, int flags) {
            this(name, group, label, description, labelRes, descriptionRes, icon,
                    protectionLevel, flags, false);
        }

        public PermissionDeclaration(String name, String group, String label, String description,
                                     int labelRes, int descriptionRes, int icon,
                                     int protectionLevel, int flags, boolean tree) {
            this.name = requireText(name, "permissionName");
            this.group = value(group);
            this.label = value(label);
            this.description = value(description);
            this.labelRes = Math.max(0, labelRes);
            this.descriptionRes = Math.max(0, descriptionRes);
            this.icon = Math.max(0, icon);
            this.protectionLevel = Math.max(0, protectionLevel);
            this.flags = Math.max(0, flags);
            this.tree = tree;
        }
        public String name() { return name; }
        public String group() { return group; }
        public String label() { return label; }
        public String description() { return description; }
        public int labelRes() { return labelRes; }
        public int descriptionRes() { return descriptionRes; }
        public int icon() { return icon; }
        public int protectionLevel() { return protectionLevel; }
        public int flags() { return flags; }
        public boolean tree() { return tree; }
    }

    /** Framework-neutral custom permission-group declaration projected by the virtual PMS. */
    public static final class PermissionGroup {
        private final String name;
        private final String label;
        private final String description;
        private final int labelRes;
        private final int descriptionRes;
        private final int icon;
        private final int requestRes;
        private final int priority;
        private final int flags;

        public PermissionGroup(String name, String label, String description,
                               int labelRes, int descriptionRes, int icon,
                               int requestRes, int priority, int flags) {
            this.name = requireText(name, "permissionGroupName");
            this.label = value(label);
            this.description = value(description);
            this.labelRes = Math.max(0, labelRes);
            this.descriptionRes = Math.max(0, descriptionRes);
            this.icon = Math.max(0, icon);
            this.requestRes = Math.max(0, requestRes);
            this.priority = priority;
            this.flags = Math.max(0, flags);
        }
        public String name() { return name; }
        public String label() { return label; }
        public String description() { return description; }
        public int labelRes() { return labelRes; }
        public int descriptionRes() { return descriptionRes; }
        public int icon() { return icon; }
        public int requestRes() { return requestRes; }
        public int priority() { return priority; }
        public int flags() { return flags; }
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
        private final String persistableMode;
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
        private final String targetActivity;

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
                    stopWithTask, directBootAware, false, 0, false, "never");
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
                         boolean syncable, String persistableMode) {
            this(type, className, processName, exported, enabled, isolated, actions, authority,
                    permission, readPermission, writePermission, grantUriPermissions, enabledSetting,
                    filters, providerPathRules, metaData, launchMode, taskAffinity,
                    documentLaunchMode, configChanges, screenOrientation, windowSoftInputMode, flags,
                    excludeFromRecents, noHistory, finishOnTaskLaunch, clearTaskOnLaunch,
                    alwaysRetainTaskState, allowTaskReparenting, resizeMode, maxAspectRatio,
                    minAspectRatio, supportsPictureInPicture, themeResId, foregroundServiceType,
                    stopWithTask, directBootAware, multiprocess, initOrder, syncable,
                    persistableMode, "");
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
                         boolean syncable, String persistableMode, String targetActivity) {
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
            this.providerPathRules = Collections.unmodifiableList(
                    new ArrayList<>(providerPathRules == null ? List.of() : providerPathRules));
            this.metaData = metaData == null ? null : new Bundle(metaData);
            this.launchMode = enumValue(launchMode, "standard");
            this.taskAffinity = value(taskAffinity);
            this.documentLaunchMode = enumValue(documentLaunchMode, "none");
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
            this.resizeMode = enumValue(resizeMode, "unspecified");
            this.maxAspectRatio = Math.max(0f, maxAspectRatio);
            this.minAspectRatio = Math.max(0f, minAspectRatio);
            this.supportsPictureInPicture = supportsPictureInPicture;
            this.themeResId = Math.max(0, themeResId);
            this.foregroundServiceType = Math.max(0, foregroundServiceType);
            this.stopWithTask = stopWithTask;
            this.directBootAware = directBootAware;
            this.multiprocess = multiprocess;
            this.initOrder = initOrder;
            this.syncable = syncable;
            this.persistableMode = enumValue(persistableMode, "never");
            this.targetActivity = value(targetActivity);
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
        public String persistableMode() { return persistableMode; }
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
        public String targetActivity() { return targetActivity; }
    }

    private final String packageName;
    private final String launcherActivity;
    private final ApplicationInfo applicationInfo;
    private final List<Component> components;
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
    private final List<PermissionDeclaration> permissionDeclarations;
    private final List<PermissionGroup> permissionGroups;
    private final Set<String> queryPackages;
    private final Set<String> queryProviderAuthorities;
    private final List<Filter> queryIntentFilters;
    private final Map<String, Boolean> permissionGrants;
    private final boolean enabled;
    private final Map<String, Component> byClass;
    private final Map<String, Component> providersByAuthority;
    private final ConcurrentHashMap<String, Integer> enabledSettingOverrides = new ConcurrentHashMap<>();

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
        this(packageName, launcherActivity, applicationInfo, components, versionName, versionCode,
                signatureSha256, firstInstallTime, lastUpdateTime, installerPackageName,
                sharedLibraries, sharedLibraryDetails, instrumentations, requestedPermissions,
                enabled, queryPackages, queryProviderAuthorities, queryIntentFilters, Map.of());
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
                                  List<Filter> queryIntentFilters,
                                  Map<String, Boolean> permissionGrants) {
        this(packageName, launcherActivity, applicationInfo, components, versionName, versionCode,
                signatureSha256, firstInstallTime, lastUpdateTime, installerPackageName,
                sharedLibraries, sharedLibraryDetails, instrumentations, requestedPermissions,
                enabled, queryPackages, queryProviderAuthorities, queryIntentFilters,
                permissionGrants, List.of(), List.of());
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
                                  List<Filter> queryIntentFilters,
                                  Map<String, Boolean> permissionGrants,
                                  List<PermissionDeclaration> permissionDeclarations,
                                  List<PermissionGroup> permissionGroups) {
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
        this.permissionDeclarations = immutablePermissionDeclarations(permissionDeclarations);
        this.permissionGroups = immutablePermissionGroups(permissionGroups);
        this.permissionGrants = immutablePermissionGrants(permissionGrants, this.requestedPermissions);
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
                continue;
            }
            if (component.type() == Type.PROVIDER && !component.authority().isEmpty()) {
                for (String authority : component.authority().split(";")) {
                    String normalized = authority.trim();
                    if (!normalized.isEmpty() && !authorities.containsKey(normalized)) {
                        authorities.put(normalized, component);
                    }
                }
            }
            classes.put(component.className(), component);
            accepted.add(component);
        }
        this.components = Collections.unmodifiableList(accepted);
        this.byClass = Collections.unmodifiableMap(classes);
        this.providersByAuthority = Collections.unmodifiableMap(authorities);
    }

    public String packageName() { return packageName; }
    public String launcherActivity() { return launcherActivity; }
    public ApplicationInfo applicationInfo() {
        ApplicationInfo projected = new ApplicationInfo(applicationInfo);
        projected.enabled = enabled;
        return projected;
    }
    public List<Component> components() { return components; }
    public String installerPackageName() { return installerPackageName; }
    public List<String> sharedLibraries() { return sharedLibraries; }
    public List<SharedLibrary> sharedLibraryDetails() { return sharedLibraryDetails; }
    public List<Instrumentation> instrumentations() { return instrumentations; }
    public List<PermissionDeclaration> permissionDeclarations() { return permissionDeclarations; }
    public List<PermissionGroup> permissionGroups() { return permissionGroups; }
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

    public Object permissionInfo(String name, long flags) {
        String requested = value(name);
        for (PermissionDeclaration declaration : permissionDeclarations) {
            if (declaration.name().equals(requested)) {
                return VirtualPackageInfoMapper.buildPermissionInfo(packageName, declaration);
            }
        }
        return null;
    }

    public List<Object> queryPermissionsByGroup(String group, long flags) {
        String targetGroup = value(group);
        List<Object> result = new ArrayList<>();
        for (PermissionDeclaration declaration : permissionDeclarations) {
            if (declaration.group().equals(targetGroup)) {
                result.add(VirtualPackageInfoMapper.buildPermissionInfo(packageName, declaration));
            }
        }
        return Collections.unmodifiableList(result);
    }

    public Object permissionGroupInfo(String name, long flags) {
        String requested = value(name);
        for (PermissionGroup group : permissionGroups) {
            if (group.name().equals(requested)) {
                return VirtualPackageInfoMapper.buildPermissionGroupInfo(packageName, group);
            }
        }
        return null;
    }

    public List<Object> permissionGroupInfos(long flags) {
        List<Object> result = new ArrayList<>();
        for (PermissionGroup group : permissionGroups) {
            result.add(VirtualPackageInfoMapper.buildPermissionGroupInfo(packageName, group));
        }
        return Collections.unmodifiableList(result);
    }

    public boolean permissionGranted(String permission) {
        String normalized = value(permission);
        Boolean explicit = permissionGrants.get(normalized);
        return explicit == null && !permissionGrants.containsKey(normalized)
                ? requestedPermissions.contains(normalized) : Boolean.TRUE.equals(explicit);
    }
    public Map<String, Boolean> permissionGrants() { return permissionGrants; }
    public String signatureSha256() { return signatureSha256; }
    public boolean enabled() { return enabled; }

    public int applicationEnabledSetting() { return enabled ? 0 : 2; }

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
        return VirtualPackageInfoMapper.packageInfo(this, flags);
    }

    public ComponentInfo componentInfo(ComponentName name, Type expected) {
        return componentInfo(name, expected, 0L);
    }
    public ComponentInfo componentInfo(ComponentName name, Type expected, long flags) {
        if (name == null || !packageName.equals(name.getPackageName())) return null;
        Component component = byClass.get(normalizeClass(name.getClassName()));
        if (component == null || component.type() != expected || !isComponentVisible(component, flags)) return null;
        return VirtualPackageInfoMapper.toInfo(this, component);
    }

    public Component component(String className, Type expected) {
        Component component = byClass.get(normalizeClass(className));
        return component == null || component.type() != expected ? null : component;
    }

    public Component providerComponent(String authority) {
        return providersByAuthority.get(value(authority));
    }

    public ResolveInfo resolve(Intent intent, Type type) { return resolve(intent, type, 0L); }
    public ResolveInfo resolve(Intent intent, Type type, long flags) {
        List<ResolveInfo> matches = query(intent, type, flags);
        return type == Type.ACTIVITY ? VirtualPackageFilterMatcher.chooseBestActivity(matches)
                : (matches.isEmpty() ? null : matches.get(0));
    }

    public List<ResolveInfo> query(Intent intent, Type type) { return query(intent, type, 0L); }
    public List<ResolveInfo> query(Intent intent, Type type, long flags) {
        List<VirtualPackageFilterMatcher.Match> matches = new ArrayList<>();
        Intent value = VirtualPackageFilterMatcher.selectedIntent(intent);
        ComponentName explicit = value.getComponent();
        String targetPackage = value.getPackage();
        if (targetPackage != null && !targetPackage.isEmpty() && !packageName.equals(targetPackage)) return List.of();
        if (explicit != null) {
            ComponentInfo info = componentInfo(explicit, type, flags);
            return info == null ? List.of() : List.of(VirtualPackageInfoMapper.resolveInfo(
                    type, info, 0, true, 0x100000, null));
        }
        for (Component component : components) {
            if (component.type() != type || !isComponentVisible(component, flags)) continue;
            for (Filter filter : component.filters()) {
                if ((flags & MATCH_DEFAULT_ONLY) != 0 && !filter.defaultCategory()) continue;
                VirtualPackageFilterMatcher.Match match = VirtualPackageFilterMatcher.match(value, filter, flags);
                if (match != null) matches.add(new VirtualPackageFilterMatcher.Match(component, filter, match.score));
            }
        }
        matches.sort(Comparator.comparingInt((VirtualPackageFilterMatcher.Match item) -> item.filter.priority()).reversed()
                .thenComparing(Comparator.comparingInt((VirtualPackageFilterMatcher.Match item) -> item.score).reversed())
                .thenComparing(item -> item.component.className()));
        List<ResolveInfo> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (VirtualPackageFilterMatcher.Match item : matches) {
            if (!seen.add(item.component.className())) continue;
            out.add(VirtualPackageInfoMapper.resolveInfo(type, VirtualPackageInfoMapper.toInfo(this, item.component),
                    item.filter.priority(), item.filter.defaultCategory(), item.score, item.filter));
        }
        return out;
    }

    public boolean activitySupportsIntent(ComponentName name, Intent intent, long flags) {
        if (name == null || !packageName.equals(name.getPackageName())) return false;
        Component component = byClass.get(normalizeClass(name.getClassName()));
        if (component == null || component.type() != Type.ACTIVITY || !isComponentVisible(component, flags)) {
            return false;
        }
        Intent value = VirtualPackageFilterMatcher.selectedIntent(intent);
        ComponentName explicit = value.getComponent();
        if (explicit != null && !VirtualPackageFilterMatcher.sameComponent(name, explicit)) return false;
        if (explicit != null) return true;
        for (Filter filter : component.filters()) {
            if ((flags & MATCH_DEFAULT_ONLY) != 0 && !filter.defaultCategory()) continue;
            if (VirtualPackageFilterMatcher.match(value, filter, flags) != null) return true;
        }
        return false;
    }

    public boolean matchesQueryFilter(Filter query) {
        return VirtualPackageFilterMatcher.matchesQueryFilter(components, query);
    }

    public InstrumentationInfo instrumentationInfo(ComponentName name, long flags) {
        if (name == null || !packageName.equals(name.getPackageName())) return null;
        Instrumentation instrumentation = instrumentationsByClass.get(normalizeClass(name.getClassName()));
        return instrumentation == null || !instrumentationVisible(instrumentation, flags)
                ? null : VirtualPackageInfoMapper.toInstrumentationInfo(this, instrumentation);
    }

    public List<InstrumentationInfo> queryInstrumentation(String targetPackage, long flags) {
        String target = value(targetPackage);
        List<InstrumentationInfo> result = new ArrayList<>();
        for (Instrumentation instrumentation : instrumentations) {
            if (!target.isEmpty() && !target.equals(instrumentation.targetPackage())) continue;
            if (instrumentationVisible(instrumentation, flags)) {
                result.add(VirtualPackageInfoMapper.toInstrumentationInfo(this, instrumentation));
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
        return component == null || !isComponentVisible(component, flags)
                ? null : (ProviderInfo) VirtualPackageInfoMapper.toInfo(this, component);
    }

    public ProviderInfo providerForClass(String className) {
        return providerForClass(className, MATCH_DISABLED_COMPONENTS);
    }

    public ProviderInfo providerForClass(String className, long flags) {
        Component component = byClass.get(normalizeClass(className));
        if (component == null || component.type() != Type.PROVIDER || !isComponentVisible(component, flags)) {
            return null;
        }
        return (ProviderInfo) VirtualPackageInfoMapper.toInfo(this, component);
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

    public String versionName() { return versionName; }
    public long versionCode() { return versionCode; }
    public long firstInstallTime() { return firstInstallTime; }
    public long lastUpdateTime() { return lastUpdateTime; }

    private boolean instrumentationVisible(Instrumentation instrumentation, long flags) {
        return (enabled && instrumentation.enabled()) || (flags & MATCH_DISABLED_COMPONENTS) != 0;
    }

    boolean isComponentVisible(Component component, long flags) {
        if ((flags & MATCH_DISABLED_COMPONENTS) != 0) return true;
        if (!enabled) return false;
        Integer override = enabledSettingOverrides.get(normalizeClass(component.className()));
        if (override == null || override == 0) return component.enabled();
        return override == 1;
    }

    static int dataMatch(List<DataRule> rules, String type, String rawData) {
        return VirtualPackageFilterMatcher.dataMatch(rules, type, rawData);
    }
    static boolean simpleGlob(String pattern, String value) {
        return VirtualPackageFilterMatcher.simpleGlob(pattern, value);
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
    private static List<PermissionDeclaration> immutablePermissionDeclarations(
            List<PermissionDeclaration> input) {
        Map<String, PermissionDeclaration> unique = new LinkedHashMap<>();
        if (input != null) {
            for (PermissionDeclaration declaration : input) {
                if (declaration != null) unique.putIfAbsent(declaration.name(), declaration);
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(unique.values()));
    }
    private static List<PermissionGroup> immutablePermissionGroups(List<PermissionGroup> input) {
        Map<String, PermissionGroup> unique = new LinkedHashMap<>();
        if (input != null) {
            for (PermissionGroup group : input) {
                if (group != null) unique.putIfAbsent(group.name(), group);
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(unique.values()));
    }
    private static Map<String, Boolean> immutablePermissionGrants(
            Map<String, Boolean> input, List<String> requestedPermissions) {
        Map<String, Boolean> output = new LinkedHashMap<>();
        if (input != null) {
            for (Map.Entry<String, Boolean> entry : input.entrySet()) {
                String name = value(entry.getKey());
                if (name.isEmpty()) continue;
                output.put(name, Boolean.TRUE.equals(entry.getValue()));
            }
        }
        return Collections.unmodifiableMap(output);
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
}
