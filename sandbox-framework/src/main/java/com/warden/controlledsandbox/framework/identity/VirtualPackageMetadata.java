package com.warden.controlledsandbox.framework.identity;

import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
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

    // Keep the public ResolveInfo.match value aligned with IntentFilter.matchData().  The
    // resolver uses the category portion to rank otherwise equivalent filters; a private,
    // low-order score makes a virtual result look valid while changing framework selection.
    private static final int MATCH_CATEGORY_EMPTY = 0x00100000;
    private static final int MATCH_CATEGORY_SCHEME = 0x00200000;
    private static final int MATCH_CATEGORY_HOST = 0x00300000;
    private static final int MATCH_CATEGORY_PORT = 0x00400000;
    private static final int MATCH_CATEGORY_PATH = 0x00500000;
    private static final int MATCH_CATEGORY_TYPE = 0x00600000;
    private static final int MATCH_ADJUSTMENT_NORMAL = 0x00008000;

    private static final int NO_MATCH_TYPE = -1;
    private static final int NO_MATCH_DATA = -2;

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
        /** Non-empty only for an activity-alias; the logical component remains className. */
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

        /** Full component constructor including the PackageParser activity-alias target. */
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
            this.providerPathRules = Collections.unmodifiableList(new ArrayList<>(
                    providerPathRules == null ? List.of() : providerPathRules));
            this.metaData = metaData == null ? null : new Bundle(metaData);
            this.launchMode = enumValue(launchMode, "standard");
            this.taskAffinity = value(taskAffinity);
            this.documentLaunchMode = enumValue(documentLaunchMode, "none");
            this.persistableMode = enumValue(persistableMode, "never");
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
            this.targetActivity = value(targetActivity);
            if (!this.targetActivity.isEmpty() && type != Type.ACTIVITY) {
                throw new IllegalArgumentException("targetActivity is activity-only metadata");
            }
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
    private final Map<String, Component> byClass;
    private final Map<String, Component> providersByAuthority;
    private final String versionName;
    private final long versionCode;
    private final String signatureSha256;
    private final List<byte[]> signingCertificates;
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
    /**
     * Effective permission state projected by the virtual PMS for this package.
     *
     * <p>The requested-permission list alone is not enough for
     * {@code PackageManager.checkPermission()}: a package may request a runtime permission and
     * still be denied.  Keep the tri-state decision out of the host PMS and carry the effective
     * result with the immutable package projection instead.</p>
     */
    private final Map<String, Boolean> permissionGrants;
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
        this(packageName, launcherActivity, applicationInfo, components, versionName, versionCode,
                signatureSha256, firstInstallTime, lastUpdateTime, installerPackageName,
                sharedLibraries, sharedLibraryDetails, instrumentations, requestedPermissions,
                enabled, queryPackages, queryProviderAuthorities, queryIntentFilters, Map.of());
    }

    /**
     * Full package projection including the effective state of each requested permission.
     * Older callers use the overload above and retain its requested-permission compatibility
     * behavior.
     */
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

    /** Full package projection including declared custom permissions and groups. */
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
        this(packageName, launcherActivity, applicationInfo, components, versionName, versionCode,
                signatureSha256, firstInstallTime, lastUpdateTime, installerPackageName,
                sharedLibraries, sharedLibraryDetails, instrumentations, requestedPermissions,
                enabled, queryPackages, queryProviderAuthorities, queryIntentFilters,
                permissionGrants, permissionDeclarations, permissionGroups, List.of());
    }

    /** Full package projection with current signer and signer-lineage certificates. */
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
                                  List<PermissionGroup> permissionGroups,
                                  List<byte[]> signingCertificates) {
        this.packageName = requireText(packageName, "packageName");
        this.launcherActivity = value(launcherActivity);
        this.applicationInfo = new ApplicationInfo(applicationInfo);
        this.versionName = value(versionName);
        this.versionCode = Math.max(0L, versionCode);
        this.signatureSha256 = value(signatureSha256).toLowerCase(Locale.ROOT);
        this.signingCertificates = immutableCertificates(signingCertificates);
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
    /**
     * Projects the package-level enabled state into the same ApplicationInfo object returned by
     * PackageManager.  The immutable template is built from the APK and is intentionally always
     * enabled; the virtual package state is a separate per-user decision.  Returning the raw
     * template here lets a disabled Guest observe an enabled ApplicationInfo and is different
     * from both Android PMS and VA/NBB's package projection.
     */
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
                return buildPermissionInfo(declaration);
            }
        }
        return null;
    }

    public List<Object> queryPermissionsByGroup(String group, long flags) {
        String requested = value(group);
        List<Object> result = new ArrayList<>();
        for (PermissionDeclaration declaration : permissionDeclarations) {
            if (declaration.group().equals(requested)) result.add(buildPermissionInfo(declaration));
        }
        return Collections.unmodifiableList(result);
    }

    public Object permissionGroupInfo(String name, long flags) {
        String requested = value(name);
        for (PermissionGroup group : permissionGroups) {
            if (group.name().equals(requested)) return buildPermissionGroupInfo(group);
        }
        return null;
    }

    public List<Object> permissionGroupInfos(long flags) {
        List<Object> result = new ArrayList<>();
        for (PermissionGroup group : permissionGroups) result.add(buildPermissionGroupInfo(group));
        return Collections.unmodifiableList(result);
    }

    private Object buildPermissionInfo(PermissionDeclaration declaration) {
        try {
            Class<?> type = Class.forName("android.content.pm.PermissionInfo");
            Object info = type.getDeclaredConstructor().newInstance();
            setField(info, "name", declaration.name());
            setField(info, "packageName", packageName);
            setField(info, "group", emptyToNull(declaration.group()));
            setField(info, "protectionLevel", declaration.protectionLevel());
            setField(info, "flags", declaration.flags());
            setField(info, "labelRes", declaration.labelRes());
            setField(info, "descriptionRes", declaration.descriptionRes());
            setField(info, "icon", declaration.icon());
            setField(info, "nonLocalizedLabel", emptyToNull(declaration.label()));
            setField(info, "nonLocalizedDescription", emptyToNull(declaration.description()));
            return info;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("VIRTUAL_PERMISSION_INFO_UNAVAILABLE", error);
        }
    }

    private Object buildPermissionGroupInfo(PermissionGroup group) {
        try {
            Class<?> type = Class.forName("android.content.pm.PermissionGroupInfo");
            Object info = type.getDeclaredConstructor().newInstance();
            setField(info, "name", group.name());
            setField(info, "packageName", packageName);
            setField(info, "labelRes", group.labelRes());
            setField(info, "descriptionRes", group.descriptionRes());
            setField(info, "icon", group.icon());
            setField(info, "requestRes", group.requestRes());
            setField(info, "priority", group.priority());
            setField(info, "flags", group.flags());
            setField(info, "nonLocalizedLabel", emptyToNull(group.label()));
            setField(info, "nonLocalizedDescription", emptyToNull(group.description()));
            return info;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("VIRTUAL_PERMISSION_GROUP_INFO_UNAVAILABLE", error);
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /** Returns whether the virtual PMS considers this package's permission effectively granted. */
    public boolean permissionGranted(String permission) {
        String normalized = value(permission);
        Boolean explicit = permissionGrants.get(normalized);
        // Legacy metadata constructors did not carry decisions.  Preserve their historical
        // model, where a requested permission represented the effective grant.
        return explicit == null && !permissionGrants.containsKey(normalized)
                ? requestedPermissions.contains(normalized) : Boolean.TRUE.equals(explicit);
    }
    public Map<String, Boolean> permissionGrants() { return permissionGrants; }
    public String signatureSha256() { return signatureSha256; }
    public List<byte[]> signingCertificates() {
        List<byte[]> copy = new ArrayList<>(signingCertificates.size());
        for (byte[] certificate : signingCertificates) copy.add(certificate.clone());
        return Collections.unmodifiableList(copy);
    }
    public boolean enabled() { return enabled; }

    /**
     * Returns the virtual package enabled setting using PackageManager's stable integer
     * contract: DEFAULT when the package is enabled, DISABLED when the virtual package state
     * has disabled it.  Keep the values local so the compact API harness does not need newer
     * PackageManager constants at compile time.
     */
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
        PackageInfo info = new PackageInfo();
        info.packageName = packageName; info.versionName = versionName;
        info.versionCode = versionCode > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) versionCode;
        info.firstInstallTime = firstInstallTime; info.lastUpdateTime = lastUpdateTime;
        info.applicationInfo = applicationInfo();
        setField(info, "splitNames", applicationSplitNames(info.applicationInfo));
        // GET_SIGNATURES and GET_SIGNING_CERTIFICATES use the same immutable signer source. The
        // latter additionally receives a SigningInfo object on API levels exposing its public
        // construction path; older releases still get a non-null object and the legacy array.
        if ((flags & 0x00000040L) != 0 || (flags & 0x08000000L) != 0) {
            Object signatures = signatureArray();
            if (signatures != null) setField(info, "signatures", signatures);
        }
        if ((flags & 0x08000000L) != 0) {
            Object signingInfo = signingInfoProjection();
            if (signingInfo != null) setField(info, "signingInfo", signingInfo);
        }
        if ((flags & 0x00000001L) != 0) info.activities = activityInfos(Type.ACTIVITY, flags);
        if ((flags & 0x00000002L) != 0) info.receivers = activityInfos(Type.RECEIVER, flags);
        if ((flags & 0x00000004L) != 0) info.services = serviceInfos(flags);
        if ((flags & 0x00000008L) != 0) info.providers = providerInfos(flags);
        if ((flags & 0x00000010L) != 0) info.instrumentation = instrumentationInfos(flags);
        if ((flags & 0x00001000L) != 0) {
            info.requestedPermissions = requestedPermissions.toArray(new String[0]);
            // PackageParser exposes declarations separately from requested permissions.  A
            // caller using GET_PERMISSIONS must therefore see the PermissionInfo projection as
            // well; returning only requestedPermissions makes a virtual package look as though
            // its custom permissions were never declared and breaks SDK manifest discovery.
            if (!permissionDeclarations.isEmpty()) {
                setField(info, "permissions", declaredPermissionInfoArray());
            }
        }
        return info;
    }

    private Object signatureArray() {
        if (signingCertificates.isEmpty()) return null;
        try {
            Class<?> signatureType = Class.forName("android.content.pm.Signature");
            Object result = Array.newInstance(signatureType, signingCertificates.size());
            for (int index = 0; index < signingCertificates.size(); index++) {
                Array.set(result, index, newSignature(signatureType, signingCertificates.get(index)));
            }
            return result;
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return null;
        }
    }

    private Object signingInfoProjection() {
        Object signatureArray = signatureArray();
        if (signatureArray == null) return null;
        try {
            Class<?> signingInfoType = Class.forName("android.content.pm.SigningInfo");
            List<Object> current = new ArrayList<>();
            int count = Array.getLength(signatureArray);
            for (int index = 0; index < count; index++) current.add(Array.get(signatureArray, index));
            for (Constructor<?> constructor : signingInfoType.getConstructors()) {
                Class<?>[] types = constructor.getParameterTypes();
                if (types.length == 4 && types[0] == int.class
                        && java.util.Collection.class.isAssignableFrom(types[1])
                        && java.util.Collection.class.isAssignableFrom(types[2])
                        && java.util.Collection.class.isAssignableFrom(types[3])) {
                    // Android's public constructor is
                    // (schemeVersion, apkContentsSigners, publicKeys, signerHistory).  The
                    // immutable CAS record currently carries the verified current signer set;
                    // do not invent a rotation lineage, so history remains empty.
                    return constructor.newInstance(3, current, Collections.emptyList(),
                            Collections.emptyList());
                }
            }
            Constructor<?> empty = signingInfoType.getDeclaredConstructor();
            empty.setAccessible(true);
            return empty.newInstance();
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return null;
        }
    }

    private static Object newSignature(Class<?> signatureType, byte[] certificate)
            throws ReflectiveOperationException {
        try {
            Constructor<?> constructor = signatureType.getConstructor(byte[].class);
            return constructor.newInstance((Object) certificate.clone());
        } catch (NoSuchMethodException unavailable) {
            Constructor<?> constructor = signatureType.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        }
    }

    private Object declaredPermissionInfoArray() {
        try {
            Class<?> type = Class.forName("android.content.pm.PermissionInfo");
            Object array = java.lang.reflect.Array.newInstance(type, permissionDeclarations.size());
            for (int i = 0; i < permissionDeclarations.size(); i++) {
                java.lang.reflect.Array.set(array, i, buildPermissionInfo(permissionDeclarations.get(i)));
            }
            return array;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("VIRTUAL_PACKAGE_PERMISSIONS_UNAVAILABLE", error);
        }
    }

    private static Object applicationSplitNames(ApplicationInfo info) {
        if (info == null) return null;
        try {
            java.lang.reflect.Field field = info.getClass().getField("splitNames");
            return field.get(info);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return null;
        }
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
        return type == Type.ACTIVITY ? chooseBestActivity(matches)
                : (matches.isEmpty() ? null : matches.get(0));
    }

    /**
     * Mirrors the resolver choice made by VA/NBB and Android's activity manager.  A query is
     * still allowed to return multiple candidates; resolveActivity/resolveIntent only choose a
     * candidate when the first two are distinguishable by priority, preferred order, or default
     * status.  Returning the first candidate unconditionally makes package iteration order
     * decide a chooser-visible ambiguity and is not Framework-compatible.
     */
    private static ResolveInfo chooseBestActivity(List<ResolveInfo> query) {
        if (query == null || query.isEmpty()) return null;
        if (query.size() == 1) return query.get(0);
        ResolveInfo first = query.get(0);
        ResolveInfo second = query.get(1);
        if (first.priority != second.priority
                || preferredOrder(first) != preferredOrder(second)
                || first.isDefault != second.isDefault) {
            return first;
        }
        return null;
    }

    /** preferredOrder is absent from the compact API-32 harness but public on Android. */
    private static int preferredOrder(ResolveInfo value) {
        if (value == null) return 0;
        try {
            java.lang.reflect.Field field = value.getClass().getField("preferredOrder");
            return field.getInt(value);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return 0;
        }
    }

    public List<ResolveInfo> query(Intent intent, Type type) { return query(intent, type, 0L); }
    public List<ResolveInfo> query(Intent intent, Type type, long flags) {
        List<Match> matches = new ArrayList<>();
        // PackageManager resolves against Intent.selector when present.  Ignoring the selector
        // makes shares/deep-links use the outer routing action instead of the actual target and
        // diverges from the framework resolver used by VA/NBB.
        Intent value = selectedIntent(intent);
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
                    item.filter.defaultCategory(), item.score, item.filter));
        }
        return out;
    }

    /**
     * Implements the hidden PackageManager activitySupportsIntent contract against the same
     * manifest filters used by queryIntentActivities(). Explicit component intents are valid
     * when the declared Activity exists; implicit intents must match one of that Activity's
     * filters. Keeping this in the package model prevents the Host PMS from answering about a
     * different component with the same action.
     */
    public boolean activitySupportsIntent(ComponentName name, Intent intent, long flags) {
        if (name == null || !packageName.equals(name.getPackageName())) return false;
        Component component = byClass.get(normalizeClass(name.getClassName()));
        if (component == null || component.type() != Type.ACTIVITY || !visible(component, flags)) {
            return false;
        }
        Intent value = selectedIntent(intent);
        ComponentName explicit = value.getComponent();
        // ComponentName is a value object on Android.  Keep the comparison value-based here as
        // well: compact API harnesses and a few OEM wrappers expose a ComponentName without
        // the platform equals() implementation, and reference comparison would make an
        // otherwise valid explicit intent fail this hidden PMS query.
        if (explicit != null && !sameComponent(name, explicit)) return false;
        if (explicit != null) return true;
        for (Filter filter : component.filters()) {
            if ((flags & MATCH_DEFAULT_ONLY) != 0 && !filter.defaultCategory()) continue;
            if (match(value, filter, flags) != null) return true;
        }
        return false;
    }

    private static boolean sameComponent(ComponentName left, ComponentName right) {
        return left == right || (left != null && right != null
                && value(left.getPackageName()).equals(value(right.getPackageName()))
                && value(left.getClassName()).equals(value(right.getClassName())));
    }

    /** API-level adapter for Intent.selector, which is absent from the compact API-32 stubs. */
    private static Intent selectedIntent(Intent intent) {
        if (intent == null) return new Intent();
        try {
            java.lang.reflect.Method getter = intent.getClass().getMethod("getSelector");
            getter.setAccessible(true);
            Object selector = getter.invoke(intent);
            return selector instanceof Intent ? (Intent) selector : intent;
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return intent;
        }
    }

    /** Returns whether an Android 11+ {@code <queries><intent>} declaration can see this APK. */
    public boolean matchesQueryFilter(Filter query) {
        if (query == null) return false;
        for (Component component : components) {
            // Android's AppsFilter derives <queries><intent> visibility from components that
            // another package could actually address.  A private component must not make its
            // package discoverable merely because it carries a matching internal filter.
            if (!component.exported()) continue;
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
        // A query without a <data> declaration is intentionally broad: it asks whether an
        // action/category can be handled by the package, independent of the component's data
        // constraints. Once the query does declare data, however, a no-data target filter is
        // not a possible match. Treating an empty MIME/scheme field inside a DataRule as a
        // wildcard here is incorrect; IntentFilter semantics use it as a real constraint.
        if (query.data().isEmpty()) return true;
        if (declared.data().isEmpty()) return false;
        return dataRulesIntersect(query.data(), declared.data());
    }

    /**
     * Returns whether two manifest data rule lists have at least one common intent value.
     *
     * <p>This is deliberately a symbolic intersection rather than a guessed sample URI. The
     * old implementation compared only scheme and MIME strings, which made
     * {@code https://other.example/...} visible through a query for
     * {@code https://example.com/...}. Android also merges the dimensions contributed by
     * multiple {@code <data>} tags in one filter; treating each tag as an independent complete
     * URI rule causes common split declarations (scheme + host + path + MIME) to disappear.
     * Exact and prefix constraints are resolved precisely; simple-glob combinations remain
     * conservative when the pattern language cannot be proven disjoint.</p>
     */
    private static boolean dataRulesIntersect(List<DataRule> leftRules,
                                              List<DataRule> rightRules) {
        boolean leftMime = hasMime(leftRules);
        boolean rightMime = hasMime(rightRules);
        if (leftMime != rightMime) return false;
        if (leftMime && !mimeListsIntersect(leftRules, rightRules)) return false;

        boolean leftScheme = hasScheme(leftRules);
        boolean rightScheme = hasScheme(rightRules);
        if (leftScheme && rightScheme) {
            if (!schemeListsIntersect(leftRules, rightRules)) return false;
        } else if (leftScheme || rightScheme) {
            String explicit = firstScheme(leftScheme ? leftRules : rightRules);
            if (!implicitDataScheme(explicit)) return false;
        }

        if (hasHost(leftRules) && hasHost(rightRules)) {
            if (!authorityListsIntersect(leftRules, rightRules)) return false;
        } else if (hasPort(leftRules) && hasPort(rightRules)
                && !portListsIntersect(leftRules, rightRules)) return false;
        return !hasPath(leftRules) || !hasPath(rightRules)
                || pathListsIntersect(leftRules, rightRules);
    }

    private static boolean hasMime(List<DataRule> rules) {
        if (rules == null) return false;
        for (DataRule rule : rules) if (rule != null && !rule.mimeType().isEmpty()) return true;
        return false;
    }

    private static boolean hasScheme(List<DataRule> rules) {
        if (rules == null) return false;
        for (DataRule rule : rules) if (rule != null && !rule.scheme().isEmpty()) return true;
        return false;
    }

    private static boolean hasHost(List<DataRule> rules) {
        if (rules == null) return false;
        for (DataRule rule : rules) if (rule != null && !rule.host().isEmpty()) return true;
        return false;
    }

    private static boolean hasPort(List<DataRule> rules) {
        if (rules == null) return false;
        for (DataRule rule : rules) if (rule != null && rule.port() >= 0) return true;
        return false;
    }

    private static boolean hasPath(List<DataRule> rules) {
        if (rules == null) return false;
        for (DataRule rule : rules) {
            if (rule != null && (!rule.path().isEmpty() || !rule.pathPrefix().isEmpty()
                    || !rule.pathPattern().isEmpty())) return true;
        }
        return false;
    }

    private static boolean mimeListsIntersect(List<DataRule> leftRules,
                                              List<DataRule> rightRules) {
        for (DataRule left : leftRules) {
            if (left == null || left.mimeType().isEmpty()) continue;
            for (DataRule right : rightRules) {
                if (right != null && !right.mimeType().isEmpty()
                        && mimeIntersects(left.mimeType(), right.mimeType())) return true;
            }
        }
        return false;
    }

    private static boolean schemeListsIntersect(List<DataRule> leftRules,
                                                List<DataRule> rightRules) {
        for (DataRule left : leftRules) {
            if (left == null || left.scheme().isEmpty()) continue;
            for (DataRule right : rightRules) {
                if (right != null && !right.scheme().isEmpty()
                        && schemeIntersects(left.scheme(), right.scheme())) return true;
            }
        }
        return false;
    }

    private static String firstScheme(List<DataRule> rules) {
        if (rules != null) {
            for (DataRule rule : rules) {
                if (rule != null && !rule.scheme().isEmpty()) return rule.scheme();
            }
        }
        return "";
    }

    private static boolean authorityListsIntersect(List<DataRule> leftRules,
                                                   List<DataRule> rightRules) {
        for (DataRule left : leftRules) {
            if (left == null || left.host().isEmpty()) continue;
            for (DataRule right : rightRules) {
                if (right != null && !right.host().isEmpty()
                        && hostIntersects(left.host(), right.host())
                        && (left.port() < 0 || right.port() < 0 || left.port() == right.port())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean portListsIntersect(List<DataRule> leftRules,
                                              List<DataRule> rightRules) {
        for (DataRule left : leftRules) {
            if (left == null || left.port() < 0) continue;
            for (DataRule right : rightRules) {
                if (right != null && right.port() == left.port()) return true;
            }
        }
        return false;
    }

    private static boolean pathListsIntersect(List<DataRule> leftRules,
                                              List<DataRule> rightRules) {
        for (DataRule left : leftRules) {
            if (left == null || !hasPath(List.of(left))) continue;
            for (DataRule right : rightRules) {
                if (right != null && hasPath(List.of(right)) && pathIntersects(left, right)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean mimeIntersects(String left, String right) {
        String first = value(left).toLowerCase(Locale.ROOT);
        String second = value(right).toLowerCase(Locale.ROOT);
        // A rule without a MIME type matches only an untyped Intent. It is not a wildcard for
        // every typed rule, even when the URI portions happen to overlap.
        if (first.isEmpty() || second.isEmpty()) return first.isEmpty() && second.isEmpty();
        if ("*/*".equals(first) || "*/*".equals(second)) return true;
        int firstSlash = first.indexOf('/');
        int secondSlash = second.indexOf('/');
        if (firstSlash <= 0 || secondSlash <= 0) return first.equals(second);
        String firstType = first.substring(0, firstSlash);
        String secondType = second.substring(0, secondSlash);
        String firstSubtype = first.substring(firstSlash + 1);
        String secondSubtype = second.substring(secondSlash + 1);
        boolean typeOverlap = firstType.equals("*") || secondType.equals("*")
                || firstType.equals(secondType);
        boolean subtypeOverlap = firstSubtype.equals("*") || secondSubtype.equals("*")
                || firstSubtype.equals(secondSubtype);
        return typeOverlap && subtypeOverlap;
    }

    private static boolean schemeIntersects(String left, String right) {
        String first = value(left).toLowerCase(Locale.ROOT);
        String second = value(right).toLowerCase(Locale.ROOT);
        if (first.isEmpty() && second.isEmpty()) return true;
        if (first.isEmpty()) return implicitDataScheme(second);
        if (second.isEmpty()) return implicitDataScheme(first);
        return first.equals(second);
    }

    private static boolean implicitDataScheme(String scheme) {
        // IntentFilter allows a filter with no explicit scheme to match unqualified data and
        // the platform content/file schemes. It does not match an arbitrary custom scheme.
        return scheme.isEmpty() || "content".equals(scheme) || "file".equals(scheme);
    }

    private static boolean hostIntersects(String left, String right) {
        String first = value(left).toLowerCase(Locale.ROOT);
        String second = value(right).toLowerCase(Locale.ROOT);
        if (first.isEmpty() || second.isEmpty()) return true;
        if (first.equals(second)) return true;
        if (first.startsWith("*") && second.startsWith("*")) {
            String firstSuffix = first.substring(1);
            String secondSuffix = second.substring(1);
            return firstSuffix.endsWith(secondSuffix) || secondSuffix.endsWith(firstSuffix);
        }
        if (first.startsWith("*")) return hostPatternMatches(first, second);
        if (second.startsWith("*")) return hostPatternMatches(second, first);
        return false;
    }

    private static boolean hostPatternMatches(String pattern, String actual) {
        String suffix = value(pattern).toLowerCase(Locale.ROOT);
        if (!suffix.startsWith("*")) return suffix.equals(actual);
        suffix = suffix.substring(1);
        if (suffix.isEmpty()) return true;
        if (suffix.startsWith(".")) suffix = suffix.substring(1);
        return actual.equals(suffix) || actual.endsWith("." + suffix);
    }

    private static boolean pathIntersects(DataRule left, DataRule right) {
        String[] first = pathConstraints(left);
        String[] second = pathConstraints(right);
        if (first.length == 0 || second.length == 0) return true;
        for (String firstConstraint : first) {
            for (String secondConstraint : second) {
                if (pathConstraintsIntersect(firstConstraint, secondConstraint)) return true;
            }
        }
        return false;
    }

    private static String[] pathConstraints(DataRule rule) {
        ArrayList<String> constraints = new ArrayList<>(3);
        if (!rule.path().isEmpty()) constraints.add("=:" + rule.path());
        if (!rule.pathPrefix().isEmpty()) constraints.add("^:" + rule.pathPrefix());
        if (!rule.pathPattern().isEmpty()) constraints.add("*:" + rule.pathPattern());
        return constraints.toArray(new String[0]);
    }

    private static boolean pathConstraintsIntersect(String left, String right) {
        char leftKind = left.charAt(0);
        char rightKind = right.charAt(0);
        String first = left.substring(2);
        String second = right.substring(2);
        if (leftKind == '=' && rightKind == '=') return first.equals(second);
        if (leftKind == '=' && rightKind == '^') return first.startsWith(second);
        if (leftKind == '^' && rightKind == '=') return second.startsWith(first);
        if (leftKind == '=' && rightKind == '*') return simpleGlob(second, first);
        if (leftKind == '*' && rightKind == '=') return simpleGlob(first, second);
        if (leftKind == '^' && rightKind == '^') {
            return first.startsWith(second) || second.startsWith(first);
        }
        if (leftKind == '^' && rightKind == '*') {
            return globIntersectsPrefix(second, first);
        }
        if (leftKind == '*' && rightKind == '^') {
            return globIntersectsPrefix(first, second);
        }
        return globIntersects(second, first);
    }

    private static boolean globIntersectsPrefix(String pattern, String prefix) {
        if (simpleGlob(pattern, prefix)) return true;
        if (simpleGlob(pattern, prefix + "x")) return true;
        String literal = globLiteralPrefix(pattern);
        return literal.isEmpty() || literal.startsWith(prefix) || prefix.startsWith(literal);
    }

    private static boolean globIntersects(String first, String second) {
        if (first.equals(second) || simpleGlob(first, second) || simpleGlob(second, first)) {
            return true;
        }
        String left = globLiteralPrefix(first);
        String right = globLiteralPrefix(second);
        return left.isEmpty() || right.isEmpty() || left.startsWith(right) || right.startsWith(left);
    }

    private static String globLiteralPrefix(String pattern) {
        String value = value(pattern);
        StringBuilder prefix = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '\\' && index + 1 < value.length()) {
                prefix.append(value.charAt(++index));
                continue;
            }
            if (current == '.' || (index + 1 < value.length() && value.charAt(index + 1) == '*')) {
                break;
            }
            prefix.append(current);
        }
        return prefix.toString();
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
        // PackageParser attaches component <meta-data> to every ComponentInfo subtype,
        // not only ProviderInfo.  Keeping this projection here makes PackageManager queries
        // and ActivityThread/Service bootstrap observe the same immutable manifest view.
        setField(info, "metaData", component.metaData());
        if (info instanceof ActivityInfo) {
            ((ActivityInfo) info).permission = component.permission();
            setField((ActivityInfo) info, "targetActivity",
                    component.targetActivity().isEmpty() ? null : component.targetActivity());
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
        setField(info, "persistableMode", persistableMode(component.persistableMode()));
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
        // Activity.attach() consumes the ActivityInfo bit to seed WindowManager's hardware
        // acceleration flag.  The application-level bit alone is not sufficient for a
        // framework-created Guest Activity, especially on API37's transaction path.
        if (info.applicationInfo != null
                && (info.applicationInfo.flags & ApplicationInfo.FLAG_HARDWARE_ACCELERATED) != 0) {
            flags |= staticInt(ActivityInfo.class, "FLAG_HARDWARE_ACCELERATED");
        }
        setField(info, "flags", flags);
        setField(info, "resizeMode", resizeMode(component.resizeMode()));
    }

    private static int launchMode(String value) {
        String name = normalizedEnum(value);
        if ("singletop".equals(name)) return staticInt(ActivityInfo.class, "LAUNCH_SINGLE_TOP");
        if ("singletask".equals(name)) return staticInt(ActivityInfo.class, "LAUNCH_SINGLE_TASK");
        if ("singleinstance".equals(name)) return staticInt(ActivityInfo.class, "LAUNCH_SINGLE_INSTANCE");
        if ("singleinstancepertask".equals(name)) return staticInt(ActivityInfo.class, "LAUNCH_SINGLE_INSTANCE_PER_TASK");
        return staticInt(ActivityInfo.class, "LAUNCH_MULTIPLE");
    }

    private static int documentLaunchMode(String value) {
        String name = normalizedEnum(value);
        if ("intoexisting".equals(name)) return staticInt(ActivityInfo.class, "DOCUMENT_LAUNCH_INTO_EXISTING");
        if ("always".equals(name)) return staticInt(ActivityInfo.class, "DOCUMENT_LAUNCH_ALWAYS");
        if ("never".equals(name)) return staticInt(ActivityInfo.class, "DOCUMENT_LAUNCH_NEVER");
        return staticInt(ActivityInfo.class, "DOCUMENT_LAUNCH_NONE");
    }

    private static int persistableMode(String value) {
        String name = normalizedEnum(value);
        if ("acrossreboots".equals(name) || "persistacrossreboots".equals(name)) {
            return staticInt(ActivityInfo.class, "PERSIST_ACROSS_REBOOTS");
        }
        if ("rootonly".equals(name) || "persistrootonly".equals(name)) {
            return staticInt(ActivityInfo.class, "PERSIST_ROOT_ONLY");
        }
        return staticInt(ActivityInfo.class, "PERSIST_NEVER");
    }

    private static int orientation(String value) {
        String name = normalizedEnum(value);
        if (name.isEmpty()) return staticInt(ActivityInfo.class, "SCREEN_ORIENTATION_UNSPECIFIED");
        if ("sensorportrait".equals(name)) name = "sensor_portrait";
        else if ("sensorlandscape".equals(name)) name = "sensor_landscape";
        else if ("fullsensor".equals(name)) name = "full_sensor";
        else if ("fulluser".equals(name)) name = "full_user";
        else if ("userlandscape".equals(name)) name = "user_landscape";
        else if ("userportrait".equals(name)) name = "user_portrait";
        else if ("reverselandscape".equals(name)) name = "reverse_landscape";
        else if ("reverseportrait".equals(name)) name = "reverse_portrait";
        return staticInt(ActivityInfo.class, "SCREEN_ORIENTATION_" + name.toUpperCase(Locale.ROOT));
    }

    private static int resizeMode(String value) {
        String name = normalizedEnum(value);
        if (name.isEmpty()) return staticInt(ActivityInfo.class, "RESIZE_MODE_RESIZEABLE");
        if ("forceresizable".equals(name)) name = "force_resizeable";
        return staticInt(ActivityInfo.class, "RESIZE_MODE_" + name.toUpperCase(Locale.ROOT));
    }

    private static String normalizedEnum(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replace("-", "").replace("_", "");
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
        return resolveInfo(type, info, priority, isDefault, match, null);
    }

    private static ResolveInfo resolveInfo(Type type, ComponentInfo info, int priority,
                                           boolean isDefault, int match, Filter sourceFilter) {
        ResolveInfo out = new ResolveInfo();
        if (type == Type.ACTIVITY || type == Type.RECEIVER) out.activityInfo = (ActivityInfo) info;
        else if (type == Type.SERVICE) out.serviceInfo = (ServiceInfo) info;
        else out.providerInfo = (ProviderInfo) info;
        out.priority = priority; out.isDefault = isDefault; out.match = match;
        if (sourceFilter != null) setField(out, "filter", toIntentFilter(sourceFilter));
        return out;
    }

    /**
     * Reconstructs the public IntentFilter carried by framework ResolveInfo.  The virtual
     * matcher stores immutable, transport-safe data, but callers still expect the Android
     * ResolveInfo contract to expose actions, categories, priority and data dimensions.
     */
    private static IntentFilter toIntentFilter(Filter source) {
        IntentFilter projected = new IntentFilter();
        for (String action : source.actions()) projected.addAction(action);
        for (String category : source.categories()) projected.addCategory(category);
        projected.setPriority(source.priority());
        for (DataRule rule : source.data()) {
            if (!rule.scheme().isEmpty()) projected.addDataScheme(rule.scheme());
            if (!rule.host().isEmpty()) {
                projected.addDataAuthority(rule.host(),
                        rule.port() < 0 ? null : Integer.toString(rule.port()));
            }
            if (!rule.path().isEmpty()) {
                projected.addDataPath(rule.path(), android.os.PatternMatcher.PATTERN_LITERAL);
            } else if (!rule.pathPrefix().isEmpty()) {
                projected.addDataPath(rule.pathPrefix(), android.os.PatternMatcher.PATTERN_PREFIX);
            } else if (!rule.pathPattern().isEmpty()) {
                projected.addDataPath(rule.pathPattern(), android.os.PatternMatcher.PATTERN_SIMPLE_GLOB);
            }
            if (!rule.mimeType().isEmpty()) {
                try {
                    projected.addDataType(rule.mimeType());
                } catch (Exception ignored) {
                    // Malformed data was already excluded from matching; keep the remaining
                    // ResolveInfo projection usable for tolerant PackageManager callers.
                }
            }
        }
        return projected;
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
            // IntentFilter.matchData() returns the category score plus the normal adjustment
            // even when both sides carry no data.  The old low-order-only value happened to
            // sort simple intents, but exposed an invalid ResolveInfo.match to callers that
            // compare it against framework constants.
            return new Match(null, filter, MATCH_CATEGORY_EMPTY + MATCH_ADJUSTMENT_NORMAL);
        }
        int best = dataMatch(filter.data(), type, data);
        return best < 0 ? null : new Match(null, filter, best);
    }

    /** Matches an IntentFilter after merging all of its manifest data dimensions. */
    private static int dataMatch(List<DataRule> rules, String type, String rawData) {
        boolean hasMime = hasMime(rules);
        boolean hasScheme = hasScheme(rules);
        boolean hasHost = hasHost(rules);
        boolean hasPort = hasPort(rules);
        boolean hasPath = hasPath(rules);

        // IntentFilter treats a filter with neither MIME types nor schemes as an empty data
        // filter. Authorities and paths declared without a base scheme are not an implicit URI
        // matcher; the framework ignores those dimensions for matching purposes.
        if (!hasMime && !hasScheme) {
            return type.isEmpty() && rawData.isEmpty()
                    ? MATCH_CATEGORY_EMPTY + MATCH_ADJUSTMENT_NORMAL : NO_MATCH_DATA;
        }
        if (hasPort && !hasHost) return NO_MATCH_DATA;

        URI uri = null;
        if (!rawData.isEmpty()) {
            try { uri = URI.create(rawData); } catch (IllegalArgumentException ignored) {
                return NO_MATCH_DATA;
            }
        }
        String actualScheme = uri == null ? "" : value(uri.getScheme()).toLowerCase(Locale.ROOT);
        int match = MATCH_CATEGORY_EMPTY;

        if (hasScheme) {
            if (uri == null || !schemeMatches(rules, actualScheme)) return NO_MATCH_DATA;
            match = MATCH_CATEGORY_SCHEME;

            // AOSP only evaluates authorities after a scheme matched. Paths are evaluated only
            // after both a scheme and an authority matched.
            if (hasHost) {
                int authority = uri == null || value(uri.getHost()).isEmpty()
                        ? NO_MATCH_DATA : authorityMatch(rules, value(uri.getHost()), uri.getPort());
                if (authority < 0) {
                    return NO_MATCH_DATA;
                }
                match = authority;
                if (hasPath) {
                    if (!pathMatches(rules, value(uri.getPath()))) return NO_MATCH_DATA;
                    match = MATCH_CATEGORY_PATH;
                }
            }
        } else {
            // MIME-only filters are allowed to match an unqualified URI, content:, or file:.
            // A custom URI scheme requires an explicit <data android:scheme> declaration.
            if (!actualScheme.isEmpty() && !"content".equals(actualScheme)
                    && !"file".equals(actualScheme)) return NO_MATCH_DATA;
        }

        if (hasMime) {
            boolean matchedMime = false;
            for (DataRule rule : rules) {
                if (rule != null && !rule.mimeType().isEmpty()
                        && mimeMatches(rule.mimeType(), type)) {
                    matchedMime = true;
                    break;
                }
            }
            if (!matchedMime) return NO_MATCH_TYPE;
            // MIME type is the strongest standard IntentFilter category and supersedes the URI
            // category in the framework's matchData() implementation.
            match = MATCH_CATEGORY_TYPE;
        } else if (!type.isEmpty()) {
            return NO_MATCH_TYPE;
        }
        return match + MATCH_ADJUSTMENT_NORMAL;
    }

    private static boolean schemeMatches(List<DataRule> rules, String actualScheme) {
        for (DataRule rule : rules) {
            if (rule != null && !rule.scheme().isEmpty() && rule.scheme().equals(actualScheme)) {
                return true;
            }
        }
        return false;
    }

    private static int authorityMatch(List<DataRule> rules, String actualHost, int actualPort) {
        int best = NO_MATCH_DATA;
        for (DataRule rule : rules) {
            if (rule != null && !rule.host().isEmpty()
                    && hostPatternMatches(rule.host(), actualHost)
                    && (rule.port() < 0 || rule.port() == actualPort)) {
                best = Math.max(best, rule.port() < 0 ? MATCH_CATEGORY_HOST : MATCH_CATEGORY_PORT);
            }
        }
        return best;
    }

    private static boolean pathMatches(List<DataRule> rules, String path) {
        for (DataRule rule : rules) {
            if (rule != null && hasPath(List.of(rule)) && pathMatches(rule, path)) return true;
        }
        return false;
    }

    private static boolean pathMatches(DataRule rule, String path) {
        if (!rule.path().isEmpty() && !rule.path().equals(path)) return false;
        if (!rule.pathPrefix().isEmpty() && !path.startsWith(rule.pathPrefix())) return false;
        return rule.pathPattern().isEmpty() || simpleGlob(rule.pathPattern(), path);
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
    private static List<byte[]> immutableCertificates(List<byte[]> input) {
        ArrayList<byte[]> values = new ArrayList<>();
        if (input == null) return Collections.unmodifiableList(values);
        if (input.size() > 64) throw new IllegalArgumentException("signing certificate list is too large");
        for (byte[] certificate : input) {
            if (certificate == null || certificate.length == 0 || certificate.length > 128 * 1024) {
                throw new IllegalArgumentException("signing certificate bytes are invalid");
            }
            values.add(certificate.clone());
        }
        return Collections.unmodifiableList(values);
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
        // A caller using the old constructors has no explicit decisions.  Do not populate the
        // map in that case; permissionGranted() will apply the compatibility fallback above.
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

    private static final class Match {
        final Component component; final Filter filter; final int score;
        Match(Component component, Filter filter, int score) { this.component = component; this.filter = filter; this.score = score; }
    }
}
