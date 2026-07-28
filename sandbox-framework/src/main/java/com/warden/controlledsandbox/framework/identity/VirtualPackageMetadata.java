package com.warden.controlledsandbox.framework.identity;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
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
import java.util.regex.Pattern;

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
        private final String enabledSetting;
        private final List<Filter> filters;

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
            this.type = java.util.Objects.requireNonNull(type, "type");
            this.className = requireText(className, "className");
            this.processName = value(processName);
            this.exported = exported;
            this.enabled = enabled;
            this.isolated = isolated;
            this.actions = immutableSet(actions);
            this.authority = value(authority);
            this.permission = value(permission);
            this.enabledSetting = VirtualPackageMetadata.enabledSetting(enabledSetting);
            this.filters = Collections.unmodifiableList(new ArrayList<>(filters == null ? List.of() : filters));
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
        public String enabledSetting() { return enabledSetting; }
        public List<Filter> filters() { return filters; }
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
    private final List<String> requestedPermissions;
    private final boolean enabled;

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
        this.requestedPermissions = immutableList(requestedPermissions);
        this.enabled = enabled;
        List<Component> copy = new ArrayList<>(components == null ? List.of() : components);
        this.components = Collections.unmodifiableList(copy);
        Map<String, Component> classes = new LinkedHashMap<>();
        Map<String, Component> authorities = new LinkedHashMap<>();
        for (Component component : copy) {
            if (classes.put(component.className(), component) != null) {
                throw new IllegalArgumentException("Duplicate component " + component.className());
            }
            if (component.type() == Type.PROVIDER && !component.authority().isEmpty()) {
                for (String authority : component.authority().split(";")) {
                    String normalized = authority.trim();
                    if (!normalized.isEmpty() && authorities.put(normalized, component) != null) {
                        throw new IllegalArgumentException("Duplicate provider authority " + normalized);
                    }
                }
            }
        }
        byClass = Collections.unmodifiableMap(classes);
        providersByAuthority = Collections.unmodifiableMap(authorities);
    }

    public String packageName() { return packageName; }
    public String launcherActivity() { return launcherActivity; }
    public ApplicationInfo applicationInfo() { return new ApplicationInfo(applicationInfo); }
    public List<Component> components() { return components; }
    public String installerPackageName() { return installerPackageName; }
    public List<String> sharedLibraries() { return sharedLibraries; }
    public String signatureSha256() { return signatureSha256; }
    public boolean enabled() { return enabled; }

    public Component component(String className) { return byClass.get(normalizeClass(className)); }
    public boolean isIsolatedComponent(String className) {
        Component component = component(className); return component != null && component.isolated();
    }
    public int componentEnabledSetting(ComponentName name) {
        if (name == null || !packageName.equals(name.getPackageName())) return 0;
        Component item = byClass.get(normalizeClass(name.getClassName()));
        if (item == null) return 0;
        if ("ENABLED".equals(item.enabledSetting())) return 1;
        if ("DISABLED".equals(item.enabledSetting())) return 2;
        return 0;
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

    public boolean ownsAuthority(String authority) { return providersByAuthority.containsKey(value(authority)); }

    public ProviderInfo provider(String authority) { return provider(authority, 0L); }
    public ProviderInfo provider(String authority, long flags) {
        Component component = providersByAuthority.get(value(authority));
        return component == null || !visible(component, flags) ? null : (ProviderInfo) toInfo(component);
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
        return (enabled && component.enabled()) || (flags & MATCH_DISABLED_COMPONENTS) != 0;
    }

    private ComponentInfo toInfo(Component component) {
        ComponentInfo info;
        if (component.type() == Type.SERVICE) info = new ServiceInfo();
        else if (component.type() == Type.PROVIDER) info = new ProviderInfo();
        else info = new ActivityInfo();
        info.packageName = packageName; info.name = component.className();
        info.processName = component.processName().isEmpty() ? packageName : component.processName();
        info.exported = component.exported(); info.enabled = component.enabled(); info.applicationInfo = applicationInfo();
        if (info instanceof ActivityInfo) ((ActivityInfo) info).permission = component.permission();
        if (info instanceof ServiceInfo) {
            ((ServiceInfo) info).flags = component.isolated() ? ServiceInfo.FLAG_ISOLATED_PROCESS : 0;
            ((ServiceInfo) info).permission = component.permission();
        }
        if (info instanceof ProviderInfo) ((ProviderInfo) info).authority = component.authority();
        return info;
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
        if (!rule.pathPattern().isEmpty() && !simpleGlob(rule.pathPattern()).matcher(path).matches()) return -1;
        int score = 0x20000;
        if (!rule.mimeType().isEmpty()) score += 0x1000;
        if (!rule.scheme().isEmpty()) score += 0x100;
        if (!rule.host().isEmpty()) score += 0x40;
        if (!rule.path().isEmpty() || !rule.pathPrefix().isEmpty() || !rule.pathPattern().isEmpty()) score += 0x20;
        return score;
    }

    private static boolean mimeMatches(String filter, String actual) {
        if (filter.isEmpty()) return actual.isEmpty();
        if (actual.isEmpty()) return false;
        if (filter.equals("*/*")) return actual.contains("/");
        int slash = filter.indexOf('/');
        if (slash > 0 && filter.endsWith("/*")) return actual.startsWith(filter.substring(0, slash + 1));
        return filter.equalsIgnoreCase(actual);
    }

    private static Pattern simpleGlob(String value) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '*') regex.append(".*");
            else regex.append(Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(regex.append('$').toString());
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

    private static final class Match {
        final Component component; final Filter filter; final int score;
        Match(Component component, Filter filter, int score) { this.component = component; this.filter = filter; this.score = score; }
    }
}
