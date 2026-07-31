package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.contract.VirtualCompatibilityProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualDeviceIdentitySnapshot;
import com.warden.controlledsandbox.contract.VirtualGoogleServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualOemProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualWebViewProfileSnapshot;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

/** Source-level WebView, identifier and OEM Binder result projection. */
final class CompatibilityInvocationInterceptor {
    record Decision(boolean handled, Object result) {
        static Decision passThrough() {
            return new Decision(false, null);
        }

        static Decision handled(Object value) {
            return new Decision(true, value);
        }
    }

    private final GuestIdentity identity;
    private final String serviceName;

    CompatibilityInvocationInterceptor(GuestIdentity identity, String serviceName) {
        this.identity = identity;
        this.serviceName = serviceName.toLowerCase(Locale.ROOT);
    }

    Decision before(Method method, Object[] args) {
        VirtualCompatibilityProfileSnapshot profile =
                identity.virtualServices().compatibilityProfile();
        return switch (serviceName) {
            case "webviewupdate" -> webView(method, args, profile.webView());
            case "deviceidentifiers" -> identifiers(method, profile);
            case "gms" -> google(method, profile.googleServices());
            case "oemidentifier" -> oem(method, profile.oem());
            default -> Decision.passThrough();
        };
    }

    private Decision webView(
            Method method,
            Object[] args,
            VirtualWebViewProfileSnapshot profile) {
        if (VirtualLocationProfileSnapshot.MODE_HOST.equals(profile.mode())) {
            return Decision.passThrough();
        }
        String methodName = method.getName();
        Class<?> returnType = method.getReturnType();
        boolean blocked = VirtualLocationProfileSnapshot.MODE_BLOCKED.equals(profile.mode());

        if (InvocationMethodMatcher.startsWith(methodName, "enable", "change", "set")) {
            throw new SecurityException("VIRTUAL_WEBVIEW_MUTATION_DENIED:" + methodName);
        }
        if (methodName.equals("isMultiProcessEnabled")) {
            return Decision.handled(!blocked && profile.multiprocessEnabled());
        }
        if (methodName.equals("isWebViewPackage")) {
            return Decision.handled(
                    !blocked && profile.providerPackage().equals(firstString(args)));
        }
        if (InvocationMethodMatcher.containsAny(methodName, "CurrentWebViewPackage", "WebViewProvider")
                || InvocationMethodMatcher.named(methodName, "waitForAndGetProvider")) {
            if (blocked) {
                return Decision.handled(defaultValue(returnType));
            }
            if (returnType == String.class) {
                return Decision.handled(profile.providerPackage());
            }
            return Decision.handled(
                    FrameworkCompatibilityObjectFactory.webViewValue(returnType, profile));
        }
        if (InvocationMethodMatcher.containsAny(methodName,
                "ValidWebViewPackages", "AllWebViewPackages", "WebViewPackages")) {
            return webViewPackageCollection(returnType, profile, blocked);
        }
        return Decision.passThrough();
    }

    private static Decision webViewPackageCollection(
            Class<?> returnType,
            VirtualWebViewProfileSnapshot profile,
            boolean blocked) {
        if (returnType.isArray()) {
            Object array = Array.newInstance(returnType.getComponentType(), blocked ? 0 : 1);
            if (!blocked) {
                Array.set(
                        array,
                        0,
                        FrameworkCompatibilityObjectFactory.webViewValue(
                                returnType.getComponentType(), profile));
            }
            return Decision.handled(array);
        }
        if (List.class.isAssignableFrom(returnType)) {
            return Decision.handled(
                    blocked
                            ? List.of()
                            : List.of(FrameworkCompatibilityObjectFactory.webViewPackageInfo(profile)));
        }
        return Decision.passThrough();
    }

    private Decision identifiers(
            Method method,
            VirtualCompatibilityProfileSnapshot profile) {
        String mode = profile.googleServices().mode();
        if (VirtualLocationProfileSnapshot.MODE_HOST.equals(mode)) {
            return Decision.passThrough();
        }

        String methodName = method.getName().toLowerCase(Locale.ROOT);
        String value = "";
        VirtualDeviceIdentitySnapshot device =
                identity.virtualServices().deviceServiceProfile().identity();
        if (!VirtualLocationProfileSnapshot.MODE_BLOCKED.equals(mode)) {
            if (InvocationMethodMatcher.containsAny(methodName, "serial")) {
                value = device.serial();
            } else if (InvocationMethodMatcher.containsAny(methodName, "advert")) {
                value = profile.googleServices().advertisingId();
            } else if (InvocationMethodMatcher.containsAny(methodName, "appset")) {
                value = profile.googleServices().appSetId();
            } else if (InvocationMethodMatcher.containsAny(methodName, "gsf")) {
                value = profile.googleServices().gsfId();
            } else {
                value = device.installationId();
            }
        }

        Class<?> returnType = method.getReturnType();
        if (returnType == String.class || returnType == Object.class) {
            return Decision.handled(value);
        }
        if (returnType == boolean.class || returnType == Boolean.class) {
            return Decision.handled(!value.isEmpty());
        }
        return Decision.handled(defaultValue(returnType));
    }

    private Decision google(Method method, VirtualGoogleServicesProfileSnapshot profile) {
        if (VirtualLocationProfileSnapshot.MODE_HOST.equals(profile.mode())) {
            return Decision.passThrough();
        }

        String methodName = method.getName().toLowerCase(Locale.ROOT);
        boolean available = !VirtualLocationProfileSnapshot.MODE_BLOCKED.equals(profile.mode())
                && profile.playServicesAvailable();
        Class<?> returnType = method.getReturnType();

        if (InvocationMethodMatcher.containsAny(methodName, "advert")) {
            return Decision.handled(
                    returnType == String.class
                            ? available ? profile.advertisingId() : ""
                            : defaultValue(returnType));
        }
        if (InvocationMethodMatcher.containsAny(methodName, "appset")) {
            return Decision.handled(
                    returnType == String.class
                            ? available ? profile.appSetId() : ""
                            : defaultValue(returnType));
        }
        if (InvocationMethodMatcher.containsAny(methodName, "accounttype")) {
            return stringCollection(returnType, available, profile.visibleAccountTypes());
        }
        if (InvocationMethodMatcher.containsAny(methodName, "enabledapi", "availableapi")) {
            return stringCollection(returnType, available, profile.enabledApis());
        }
        if (InvocationMethodMatcher.containsAny(methodName, "available", "connected")
                || InvocationMethodMatcher.startsWith(methodName, "is")) {
            if (returnType == boolean.class || returnType == Boolean.class) {
                return Decision.handled(available);
            }
            if (returnType == int.class || returnType == Integer.class) {
                return Decision.handled(available ? 0 : 1);
            }
        }
        if (InvocationMethodMatcher.containsAny(methodName, "token", "authenticate")
                || InvocationMethodMatcher.named(methodName, "getservice")) {
            if (!available) {
                throw new SecurityException(
                        "VIRTUAL_GOOGLE_SERVICE_UNAVAILABLE:" + method.getName());
            }
            return Decision.passThrough();
        }
        return available
                ? Decision.passThrough()
                : Decision.handled(defaultValue(returnType));
    }

    private static Decision stringCollection(
            Class<?> returnType,
            boolean available,
            List<String> values) {
        if (returnType.isArray() && returnType.getComponentType() == String.class) {
            return Decision.handled(available ? values.toArray(new String[0]) : new String[0]);
        }
        if (List.class.isAssignableFrom(returnType)) {
            return Decision.handled(available ? values : List.of());
        }
        return Decision.passThrough();
    }

    private Decision oem(Method method, VirtualOemProfileSnapshot profile) {
        if (VirtualLocationProfileSnapshot.MODE_HOST.equals(profile.mode())) {
            return Decision.passThrough();
        }
        String methodName = method.getName().toLowerCase(Locale.ROOT);
        boolean blocked = VirtualLocationProfileSnapshot.MODE_BLOCKED.equals(profile.mode());
        if (InvocationMethodMatcher.startsWith(methodName, "enforce") && method.getReturnType() == void.class) {
            return Decision.handled(null);
        }
        if (InvocationMethodMatcher.containsAny(methodName, "oaid", "vaid", "aaid", "attribution")) {
            return Decision.handled(blocked ? "" : profile.attributionId());
        }
        if (InvocationMethodMatcher.startsWith(methodName, "is")
                && (method.getReturnType() == boolean.class
                        || method.getReturnType() == Boolean.class)) {
            return Decision.handled(!blocked);
        }
        return Decision.passThrough();
    }

    private static String firstString(Object[] args) {
        if (args != null) {
            for (Object arg : args) {
                if (arg instanceof String value) {
                    return value;
                }
            }
        }
        return "";
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) return false;
        if (type == void.class) return null;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        throw new IllegalArgumentException("Unsupported primitive type: " + type);
    }
}
