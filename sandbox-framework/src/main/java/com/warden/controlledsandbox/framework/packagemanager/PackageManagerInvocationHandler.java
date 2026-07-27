package com.warden.controlledsandbox.framework.packagemanager;

import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import com.warden.controlledsandbox.framework.identity.IdentityObjectRewriter;
import com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/** Virtual package query surface layered over the host IPackageManager Binder interface. */
public final class PackageManagerInvocationHandler implements InvocationHandler {
    private final Object delegate;
    private final GuestIdentity identity;
    private final VirtualPackageMetadata metadata;

    PackageManagerInvocationHandler(Object delegate, GuestIdentity identity) {
        this.delegate = delegate;
        this.identity = identity;
        this.metadata = identity.packageMetadata();
    }

    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if (method.getDeclaringClass() == Object.class) return method.invoke(delegate, args);

        Object virtual = virtualResult(name, method.getReturnType(), args);
        if (virtual != NoResult.VALUE) return virtual;

        Object[] rewritten = args == null ? null : args.clone();
        IdentityObjectRewriter.RewriteScope scope = IdentityObjectRewriter.rewriteArguments(rewritten, identity);
        try {
            try { return IdentityObjectRewriter.rewriteResult(method.invoke(delegate, rewritten), identity); }
            catch (InvocationTargetException error) { throw error.getCause(); }
        } finally {
            scope.close();
        }
    }

    private Object virtualResult(String methodName, Class<?> returnType, Object[] args) {
        boolean guestTarget = containsGuestPackage(args);
        switch (methodName) {
            case "getApplicationInfo":
                return guestTarget ? metadata.applicationInfo() : NoResult.VALUE;
            case "getPackageInfo":
            case "getPackageInfoVersioned":
                return guestTarget ? metadata.packageInfo() : NoResult.VALUE;
            case "getPackageUid":
            case "getPackageUidAsUser":
                return guestTarget ? identity.virtualUid() : NoResult.VALUE;
            case "isPackageAvailable":
                return guestTarget ? true : NoResult.VALUE;
            case "getInstallerPackageName":
            case "getInstallSourceInfo":
                return guestTarget ? null : NoResult.VALUE;
            case "checkPermission":
                if (!guestTarget) return NoResult.VALUE;
                String permission = firstString(args);
                return identity.requestedPermissions().contains(permission)
                        ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED;
            case "getPackagesForUid":
                return firstInt(args, -1) == identity.virtualUid()
                        ? new String[]{identity.packageName()} : NoResult.VALUE;
            case "getNameForUid":
                return firstInt(args, -1) == identity.virtualUid()
                        ? identity.packageName() : NoResult.VALUE;
            case "getActivityInfo":
                return component(args, VirtualPackageMetadata.Type.ACTIVITY);
            case "getReceiverInfo":
                return component(args, VirtualPackageMetadata.Type.RECEIVER);
            case "getServiceInfo":
                return component(args, VirtualPackageMetadata.Type.SERVICE);
            case "getProviderInfo":
                return component(args, VirtualPackageMetadata.Type.PROVIDER);
            case "resolveContentProvider": {
                ProviderInfo provider = metadata.provider(firstString(args));
                return provider == null ? NoResult.VALUE : provider;
            }
            case "resolveIntent":
            case "resolveActivity": {
                ResolveInfo resolved = metadata.resolve(firstIntent(args), VirtualPackageMetadata.Type.ACTIVITY);
                return resolved == null ? NoResult.VALUE : resolved;
            }
            case "resolveService": {
                ResolveInfo resolved = metadata.resolve(firstIntent(args), VirtualPackageMetadata.Type.SERVICE);
                return resolved == null ? NoResult.VALUE : resolved;
            }
            case "queryIntentActivities":
                return query(returnType, args, VirtualPackageMetadata.Type.ACTIVITY);
            case "queryIntentReceivers":
            case "queryBroadcastReceivers":
                return query(returnType, args, VirtualPackageMetadata.Type.RECEIVER);
            case "queryIntentServices":
                return query(returnType, args, VirtualPackageMetadata.Type.SERVICE);
            case "getInstalledPackages":
            case "getPackagesHoldingPermissions":
                return metadata.adaptCollection(Collections.singletonList(metadata.packageInfo()), returnType);
            case "getInstalledApplications":
                return metadata.adaptCollection(Collections.singletonList(metadata.applicationInfo()), returnType);
            case "queryContentProviders": {
                List<ProviderInfo> providers = new java.util.ArrayList<>();
                for (VirtualPackageMetadata.Component item : metadata.components()) {
                    if (item.type() != VirtualPackageMetadata.Type.PROVIDER || !item.enabled()) continue;
                    providers.add(metadata.provider(firstAuthority(item.authority())));
                }
                return metadata.adaptCollection(providers, returnType);
            }
            default:
                return NoResult.VALUE;
        }
    }

    private Object component(Object[] args, VirtualPackageMetadata.Type type) {
        ComponentName name = firstComponent(args);
        if (name == null || !identity.packageName().equals(name.getPackageName())) return NoResult.VALUE;
        Object info = metadata.componentInfo(name, type);
        return info == null ? NoResult.VALUE : info;
    }

    private Object query(Class<?> returnType, Object[] args, VirtualPackageMetadata.Type type) {
        Intent intent = firstIntent(args);
        List<ResolveInfo> matches = metadata.query(intent, type);
        return matches.isEmpty() ? NoResult.VALUE : metadata.adaptCollection(matches, returnType);
    }

    private boolean containsGuestPackage(Object[] args) {
        if (args == null) return false;
        for (Object arg : args) {
            if (identity.packageName().equals(arg)) return true;
            if (arg instanceof ComponentName
                    && identity.packageName().equals(((ComponentName) arg).getPackageName())) return true;
            if (arg != null && arg.getClass().getName().endsWith("VersionedPackage")) {
                try {
                    Method getter = arg.getClass().getMethod("getPackageName");
                    if (identity.packageName().equals(getter.invoke(arg))) return true;
                } catch (ReflectiveOperationException ignored) { }
            }
        }
        return false;
    }

    private static String firstString(Object[] args) {
        if (args == null) return "";
        for (Object arg : args) if (arg instanceof String) return (String) arg;
        return "";
    }
    private static int firstInt(Object[] args, int fallback) {
        if (args == null) return fallback;
        for (Object arg : args) if (arg instanceof Integer) return (Integer) arg;
        return fallback;
    }
    private static ComponentName firstComponent(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) if (arg instanceof ComponentName) return (ComponentName) arg;
        return null;
    }
    private static Intent firstIntent(Object[] args) {
        if (args == null) return new Intent();
        for (Object arg : args) if (arg instanceof Intent) return (Intent) arg;
        return new Intent();
    }
    private static String firstAuthority(String value) {
        if (value == null) return "";
        int separator = value.indexOf(';');
        return separator < 0 ? value : value.substring(0, separator);
    }

    private enum NoResult { VALUE }
}
