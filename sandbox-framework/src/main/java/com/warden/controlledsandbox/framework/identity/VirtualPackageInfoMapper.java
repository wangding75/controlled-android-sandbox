package com.warden.controlledsandbox.framework.identity;

import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PathPermission;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.PatternMatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Package-private framework component info mapping and reflection projection for VirtualPackageMetadata. */
final class VirtualPackageInfoMapper {

    private VirtualPackageInfoMapper() { }

    static PackageInfo packageInfo(VirtualPackageMetadata metadata, long flags) {
        PackageInfo info = new PackageInfo();
        info.packageName = metadata.packageName();
        info.versionName = metadata.versionName();
        info.versionCode = metadata.versionCode() > Integer.MAX_VALUE
                ? Integer.MAX_VALUE : (int) metadata.versionCode();
        info.firstInstallTime = metadata.firstInstallTime();
        info.lastUpdateTime = metadata.lastUpdateTime();
        info.applicationInfo = metadata.applicationInfo();
        if ((flags & 0x00000001L) != 0) {
            info.activities = activityInfos(metadata, VirtualPackageMetadata.Type.ACTIVITY, flags);
        }
        if ((flags & 0x00000002L) != 0) {
            info.receivers = activityInfos(metadata, VirtualPackageMetadata.Type.RECEIVER, flags);
        }
        if ((flags & 0x00000004L) != 0) {
            info.services = serviceInfos(metadata, flags);
        }
        if ((flags & 0x00000008L) != 0) {
            info.providers = providerInfos(metadata, flags);
        }
        if ((flags & 0x00000010L) != 0) {
            info.instrumentation = instrumentationInfos(metadata, flags);
        }
        if ((flags & 0x00001000L) != 0) {
            info.requestedPermissions = metadata.requestedPermissions().toArray(new String[0]);
            if (!metadata.permissionDeclarations().isEmpty()) {
                setField(info, "permissions", declaredPermissionInfoArray(metadata));
            }
        }
        return info;
    }

    static Object declaredPermissionInfoArray(VirtualPackageMetadata metadata) {
        try {
            Class<?> type = Class.forName("android.content.pm.PermissionInfo");
            List<VirtualPackageMetadata.PermissionDeclaration> declarations = metadata.permissionDeclarations();
            Object array = java.lang.reflect.Array.newInstance(type, declarations.size());
            for (int i = 0; i < declarations.size(); i++) {
                java.lang.reflect.Array.set(array, i, buildPermissionInfo(metadata.packageName(), declarations.get(i)));
            }
            return array;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("VIRTUAL_PACKAGE_PERMISSIONS_UNAVAILABLE", error);
        }
    }

    static Object buildPermissionInfo(String packageName, VirtualPackageMetadata.PermissionDeclaration declaration) {
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

    static Object buildPermissionGroupInfo(String packageName, VirtualPackageMetadata.PermissionGroup group) {
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

    static InstrumentationInfo[] instrumentationInfos(VirtualPackageMetadata metadata, long flags) {
        List<InstrumentationInfo> values = metadata.queryInstrumentation("", flags);
        return values.toArray(new InstrumentationInfo[0]);
    }

    static InstrumentationInfo toInstrumentationInfo(VirtualPackageMetadata metadata,
                                                     VirtualPackageMetadata.Instrumentation instrumentation) {
        InstrumentationInfo info = new InstrumentationInfo();
        info.packageName = metadata.packageName();
        info.name = instrumentation.className();
        info.targetPackage = instrumentation.targetPackage();
        info.targetProcesses = instrumentation.targetProcesses();
        info.handleProfiling = instrumentation.handleProfiling();
        info.functionalTest = instrumentation.functionalTest();
        ApplicationInfo app = metadata.applicationInfo();
        info.sourceDir = app.sourceDir;
        info.publicSourceDir = app.publicSourceDir;
        info.dataDir = app.dataDir;
        return info;
    }

    static ActivityInfo[] activityInfos(VirtualPackageMetadata metadata,
                                        VirtualPackageMetadata.Type type,
                                        long flags) {
        List<ActivityInfo> out = new ArrayList<>();
        for (VirtualPackageMetadata.Component component : metadata.components()) {
            if (component.type() == type && metadata.isComponentVisible(component, flags)) {
                out.add((ActivityInfo) toInfo(metadata, component));
            }
        }
        return out.toArray(new ActivityInfo[0]);
    }

    static ServiceInfo[] serviceInfos(VirtualPackageMetadata metadata, long flags) {
        List<ServiceInfo> out = new ArrayList<>();
        for (VirtualPackageMetadata.Component component : metadata.components()) {
            if (component.type() == VirtualPackageMetadata.Type.SERVICE
                    && metadata.isComponentVisible(component, flags)) {
                out.add((ServiceInfo) toInfo(metadata, component));
            }
        }
        return out.toArray(new ServiceInfo[0]);
    }

    static ProviderInfo[] providerInfos(VirtualPackageMetadata metadata, long flags) {
        List<ProviderInfo> out = new ArrayList<>();
        for (VirtualPackageMetadata.Component component : metadata.components()) {
            if (component.type() == VirtualPackageMetadata.Type.PROVIDER
                    && metadata.isComponentVisible(component, flags)) {
                out.add((ProviderInfo) toInfo(metadata, component));
            }
        }
        return out.toArray(new ProviderInfo[0]);
    }

    static ComponentInfo toInfo(VirtualPackageMetadata metadata,
                               VirtualPackageMetadata.Component component) {
        ComponentInfo info;
        if (component.type() == VirtualPackageMetadata.Type.SERVICE) info = new ServiceInfo();
        else if (component.type() == VirtualPackageMetadata.Type.PROVIDER) info = new ProviderInfo();
        else info = new ActivityInfo();
        info.packageName = metadata.packageName();
        info.name = component.className();
        info.processName = component.processName().isEmpty() ? metadata.packageName() : component.processName();
        info.exported = component.exported();
        info.enabled = metadata.isComponentVisible(component, 0L);
        info.applicationInfo = metadata.applicationInfo();
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
            List<VirtualPackageMetadata.ProviderPathRule> pathRules = component.providerPathRules().stream()
                    .filter(rule -> !rule.uriGrantRule())
                    .collect(java.util.stream.Collectors.toList());
            if (!pathRules.isEmpty()) {
                PathPermission[] pathPermissions = new PathPermission[pathRules.size()];
                for (int index = 0; index < pathPermissions.length; index++) {
                    VirtualPackageMetadata.ProviderPathRule rule = pathRules.get(index);
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

    private static void projectActivityContract(ActivityInfo info,
                                                VirtualPackageMetadata.Component component) {
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

    static int staticInt(Class<?> type, String name) {
        try {
            java.lang.reflect.Field field = type.getField(name);
            return field.getInt(null);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    static void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getField(name);
            field.set(target, value);
        } catch (Throwable ignored) {
        }
    }

    static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    static ResolveInfo resolveInfo(VirtualPackageMetadata.Type type,
                                   ComponentInfo info,
                                   int priority,
                                   boolean isDefault,
                                   int match,
                                   VirtualPackageMetadata.Filter sourceFilter) {
        ResolveInfo out = new ResolveInfo();
        if (type == VirtualPackageMetadata.Type.ACTIVITY
                || type == VirtualPackageMetadata.Type.RECEIVER) {
            out.activityInfo = (ActivityInfo) info;
        } else if (type == VirtualPackageMetadata.Type.SERVICE) {
            out.serviceInfo = (ServiceInfo) info;
        } else {
            out.providerInfo = (ProviderInfo) info;
        }
        out.priority = priority;
        out.isDefault = isDefault;
        out.match = match;
        if (sourceFilter != null) setField(out, "filter", toIntentFilter(sourceFilter));
        return out;
    }

    static IntentFilter toIntentFilter(VirtualPackageMetadata.Filter source) {
        IntentFilter projected = new IntentFilter();
        for (String action : source.actions()) projected.addAction(action);
        for (String category : source.categories()) projected.addCategory(category);
        projected.setPriority(source.priority());
        for (VirtualPackageMetadata.DataRule rule : source.data()) {
            if (!rule.scheme().isEmpty()) projected.addDataScheme(rule.scheme());
            if (!rule.host().isEmpty()) {
                projected.addDataAuthority(rule.host(),
                        rule.port() < 0 ? null : Integer.toString(rule.port()));
            }
            if (!rule.path().isEmpty()) {
                projected.addDataPath(rule.path(), PatternMatcher.PATTERN_LITERAL);
            } else if (!rule.pathPrefix().isEmpty()) {
                projected.addDataPath(rule.pathPrefix(), PatternMatcher.PATTERN_PREFIX);
            } else if (!rule.pathPattern().isEmpty()) {
                projected.addDataPath(rule.pathPattern(), PatternMatcher.PATTERN_SIMPLE_GLOB);
            }
            if (!rule.mimeType().isEmpty()) {
                try {
                    projected.addDataType(rule.mimeType());
                } catch (Exception ignored) {
                }
            }
        }
        return projected;
    }
}
