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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable public-API package view used by the process-local PackageManager proxy. */
public final class VirtualPackageMetadata {
    public enum Type { ACTIVITY, SERVICE, RECEIVER, PROVIDER }

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

        public Component(Type type, String className, String processName,
                         boolean exported, boolean enabled, boolean isolated,
                         Set<String> actions, String authority) {
            this(type, className, processName, exported, enabled, isolated, actions, authority, "");
        }

        public Component(Type type, String className, String processName,
                         boolean exported, boolean enabled, boolean isolated,
                         Set<String> actions, String authority, String permission) {
            this.type = java.util.Objects.requireNonNull(type, "type");
            this.className = requireText(className, "className");
            this.processName = processName == null ? "" : processName;
            this.exported = exported;
            this.enabled = enabled;
            this.isolated = isolated;
            this.actions = Collections.unmodifiableSet(new LinkedHashSet<>(
                    actions == null ? Collections.emptySet() : actions));
            this.authority = authority == null ? "" : authority;
            this.permission = permission == null ? "" : permission;
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
    }

    private final String packageName;
    private final String launcherActivity;
    private final ApplicationInfo applicationInfo;
    private final List<Component> components;
    private final Map<String, Component> byClass;
    private final Map<String, Component> providersByAuthority;

    public VirtualPackageMetadata(String packageName, String launcherActivity,
                                  ApplicationInfo applicationInfo, List<Component> components) {
        this.packageName = requireText(packageName, "packageName");
        this.launcherActivity = launcherActivity == null ? "" : launcherActivity;
        this.applicationInfo = new ApplicationInfo(applicationInfo);
        List<Component> copy = new ArrayList<>(components == null ? Collections.emptyList() : components);
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

    public Component component(String className) {
        return byClass.get(normalizeClass(className));
    }

    public boolean isIsolatedComponent(String className) {
        Component component = component(className);
        return component != null && component.isolated();
    }

    public PackageInfo packageInfo() {
        PackageInfo info = new PackageInfo();
        info.packageName = packageName;
        info.applicationInfo = applicationInfo();
        info.activities = activityInfos(Type.ACTIVITY);
        info.receivers = activityInfos(Type.RECEIVER);
        info.services = serviceInfos();
        info.providers = providerInfos();
        return info;
    }

    public ComponentInfo componentInfo(ComponentName name, Type expected) {
        if (name == null || !packageName.equals(name.getPackageName())) return null;
        Component component = byClass.get(normalizeClass(name.getClassName()));
        if (component == null || component.type() != expected || !component.enabled()) return null;
        return toInfo(component);
    }

    public ResolveInfo resolve(Intent intent, Type type) {
        List<ResolveInfo> matches = query(intent, type);
        return matches.isEmpty() ? null : matches.get(0);
    }

    public List<ResolveInfo> query(Intent intent, Type type) {
        List<ResolveInfo> out = new ArrayList<>();
        ComponentName explicit = intent == null ? null : intent.getComponent();
        String action = intent == null ? "" : value(intent.getAction());
        if (explicit != null) {
            ComponentInfo info = componentInfo(explicit, type);
            if (info != null) out.add(resolveInfo(type, info));
            return out;
        }
        for (Component component : components) {
            if (component.type() != type || !component.enabled()) continue;
            if (!action.isEmpty() && !component.actions().contains(action)) continue;
            out.add(resolveInfo(type, toInfo(component)));
        }
        return out;
    }

    public ProviderInfo provider(String authority) {
        Component component = providersByAuthority.get(value(authority));
        return component == null || !component.enabled() ? null : (ProviderInfo) toInfo(component);
    }

    public Object adaptCollection(List<?> values, Class<?> returnType) {
        if (returnType.isAssignableFrom(List.class) || List.class.isAssignableFrom(returnType)) {
            return values;
        }
        if (returnType.isArray()) {
            Object array = Array.newInstance(returnType.getComponentType(), values.size());
            for (int index = 0; index < values.size(); index++) Array.set(array, index, values.get(index));
            return array;
        }
        if (returnType.getName().endsWith("ParceledListSlice")) {
            try {
                Constructor<?> constructor = returnType.getDeclaredConstructor(List.class);
                constructor.setAccessible(true);
                return constructor.newInstance(values);
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Cannot construct " + returnType.getName(), error);
            }
        }
        throw new IllegalStateException("Unsupported package query return type " + returnType.getName());
    }

    private ActivityInfo[] activityInfos(Type type) {
        List<ActivityInfo> out = new ArrayList<>();
        for (Component component : components) {
            if (component.type() == type && component.enabled()) out.add((ActivityInfo) toInfo(component));
        }
        return out.toArray(new ActivityInfo[0]);
    }

    private ServiceInfo[] serviceInfos() {
        List<ServiceInfo> out = new ArrayList<>();
        for (Component component : components) {
            if (component.type() == Type.SERVICE && component.enabled()) out.add((ServiceInfo) toInfo(component));
        }
        return out.toArray(new ServiceInfo[0]);
    }

    private ProviderInfo[] providerInfos() {
        List<ProviderInfo> out = new ArrayList<>();
        for (Component component : components) {
            if (component.type() == Type.PROVIDER && component.enabled()) out.add((ProviderInfo) toInfo(component));
        }
        return out.toArray(new ProviderInfo[0]);
    }

    private ComponentInfo toInfo(Component component) {
        ComponentInfo info;
        if (component.type() == Type.SERVICE) info = new ServiceInfo();
        else if (component.type() == Type.PROVIDER) info = new ProviderInfo();
        else info = new ActivityInfo();
        info.packageName = packageName;
        info.name = component.className();
        info.processName = component.processName().isEmpty() ? packageName : component.processName();
        info.exported = component.exported();
        info.enabled = component.enabled();
        info.applicationInfo = applicationInfo();
        if (info instanceof ActivityInfo) ((ActivityInfo) info).permission = component.permission();
        if (info instanceof ServiceInfo) {
            ((ServiceInfo) info).flags = component.isolated() ? ServiceInfo.FLAG_ISOLATED_PROCESS : 0;
            ((ServiceInfo) info).permission = component.permission();
        }
        if (info instanceof ProviderInfo) ((ProviderInfo) info).authority = component.authority();
        return info;
    }

    private static ResolveInfo resolveInfo(Type type, ComponentInfo info) {
        ResolveInfo out = new ResolveInfo();
        if (type == Type.ACTIVITY || type == Type.RECEIVER) out.activityInfo = (ActivityInfo) info;
        else if (type == Type.SERVICE) out.serviceInfo = (ServiceInfo) info;
        else out.providerInfo = (ProviderInfo) info;
        return out;
    }

    private String normalizeClass(String className) {
        if (className == null || className.isEmpty()) return "";
        if (className.startsWith(".")) return packageName + className;
        if (className.indexOf('.') < 0) return packageName + "." + className;
        return className;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
    private static String value(String value) { return value == null ? "" : value; }
}
