package com.warden.controlledsandbox.runtime.guest;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import com.warden.controlledsandbox.framework.core.FrameworkCallInterceptor;
import com.warden.controlledsandbox.framework.routing.VirtualPendingIntentRegistry;
import com.warden.controlledsandbox.framework.identity.VirtualSystemServiceState;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Intercepts AMS/ATMS PendingIntent sender creation and keeps Guest identity out of host sender metadata. */
final class PendingIntentFrameworkInterceptor implements FrameworkCallInterceptor, AutoCloseable {
    @FunctionalInterface interface ActivityTokenResolver {
        String resolve(IBinder frameworkToken);
    }
    @FunctionalInterface interface Dispatcher {
        int dispatch(VirtualPendingIntentRegistry.Record record,
                     VirtualPendingIntentRegistry.SendRequest request) throws Exception;
    }

    private final GuestPackageSpec spec;
    private final VirtualPendingIntentRegistry registry;
    private final ActivityTokenResolver activityTokenResolver;
    private final Map<Object, Object> proxies = new IdentityHashMap<>();

    PendingIntentFrameworkInterceptor(GuestPackageSpec spec, Dispatcher dispatcher) {
        this(spec, null, token -> { throw new SecurityException("VIRTUAL_ACTIVITY_FRAMEWORK_TOKEN_UNKNOWN"); }, dispatcher);
    }

    PendingIntentFrameworkInterceptor(GuestPackageSpec spec,
            VirtualSystemServiceState.PendingIntentState persistence,
            ActivityTokenResolver activityTokenResolver, Dispatcher dispatcher) {
        this.spec = java.util.Objects.requireNonNull(spec, "spec");
        this.activityTokenResolver = java.util.Objects.requireNonNull(activityTokenResolver, "activityTokenResolver");
        this.registry = new VirtualPendingIntentRegistry(spec.packageName, spec.virtualUserId,
                spec.virtualUid, spec.generation, spec.processName, spec.packageRevision,
                persistence == null ? null : new PendingIntentPersistenceAdapter(persistence),
                dispatcher::dispatch);
    }

    @Override public synchronized Interception intercept(String serviceName, Method method, Object[] arguments)
            throws Throwable {
        if (!("activity-manager".equals(serviceName) || "activity-task-manager".equals(serviceName))
                || method == null) return Interception.passThrough();
        String name = method.getName().toLowerCase(Locale.ROOT);
        if (name.startsWith("getintentsender")) return createSender(method, arguments);
        if (name.startsWith("cancelintentsender")) {
            Object token = senderToken(arguments);
            if (token == null) return Interception.passThrough();
            registry.cancel(token); proxies.remove(token); return Interception.handled(defaultValue(method.getReturnType()));
        }
        if (name.startsWith("sendintentsender")) {
            Object token = senderToken(arguments);
            if (token == null) return Interception.passThrough();
            int result = registry.send(token, sendRequest(arguments));
            return Interception.handled(returnFor(method.getReturnType(), result));
        }
        if (name.startsWith("getpackageforintentsender")) {
            return senderToken(arguments) == null ? Interception.passThrough() : Interception.handled(spec.packageName);
        }
        if (name.startsWith("getuidforintentsender")) {
            return senderToken(arguments) == null ? Interception.passThrough() : Interception.handled(spec.virtualUid);
        }
        if (name.startsWith("getflagsforintentsender")) {
            Object token = senderToken(arguments); VirtualPendingIntentRegistry.Record record = registry.find(token);
            return record == null ? Interception.passThrough() : Interception.handled(record.spec().flags());
        }
        if (name.startsWith("isintentsendertargetedtopackage")) {
            return senderToken(arguments) == null ? Interception.passThrough() : Interception.handled(Boolean.TRUE);
        }
        if (name.startsWith("isintentsenderanactivity")) {
            Object token = senderToken(arguments); VirtualPendingIntentRegistry.Record record = registry.find(token);
            return record == null ? Interception.passThrough()
                    : Interception.handled(record.spec().kind() == VirtualPendingIntentRegistry.Kind.ACTIVITY);
        }
        if (name.startsWith("getintentforintentsender")) {
            Object token = senderToken(arguments); VirtualPendingIntentRegistry.Record record = registry.find(token);
            return record == null ? Interception.passThrough() : Interception.handled(firstIntent(record.payload()));
        }
        if (name.startsWith("gettagforintentsender")) {
            Object token = senderToken(arguments); VirtualPendingIntentRegistry.Record record = registry.find(token);
            return record == null ? Interception.passThrough()
                    : Interception.handled("cs:u" + spec.virtualUserId + ":g" + spec.generation + ":pi" + record.id());
        }
        return Interception.passThrough();
    }

    VirtualPendingIntentRegistry.Snapshot snapshot() { return registry.snapshot(); }

    @Override public synchronized void close() { registry.close(); proxies.clear(); }

    private Interception createSender(Method method, Object[] arguments) {
        if (!method.getReturnType().isInterface()) {
            throw new SecurityException("VIRTUAL_PENDING_INTENT_RETURN_TYPE_UNSUPPORTED:" + method.getReturnType());
        }
        Parsed parsed = parse(arguments);
        SenderBinder candidate = new SenderBinder();
        VirtualPendingIntentRegistry.IssueResult issued = registry.issue(parsed.spec, candidate, parsed.intents);
        if (issued.record() == null) return Interception.handled(null);
        Object token = issued.record().token();
        Object sender = proxies.get(token);
        if (sender == null) {
            sender = createSenderProxy(method.getReturnType(), token);
            proxies.put(token, sender);
        }
        return Interception.handled(sender);
    }

    private Object createSenderProxy(Class<?> senderType, Object token) {
        ClassLoader loader = senderType.getClassLoader();
        if (loader == null) loader = getClass().getClassLoader();
        InvocationHandler handler = (proxy, method, arguments) -> {
            String name = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                return switch (name) {
                    case "toString" -> "VirtualIntentSender[" + spec.packageName + ",u" + spec.virtualUserId + "]";
                    case "hashCode" -> registry.find(token).persistentTokenId().hashCode();
                    case "equals" -> {
                        Object other = arguments == null || arguments.length == 0 ? null : arguments[0];
                        Object otherToken = senderToken(new Object[]{other});
                        yield otherToken != null && registry.equivalent(token, otherToken);
                    }
                    default -> null;
                };
            }
            if (name.equals("asBinder")) return token;
            if (name.toLowerCase(Locale.ROOT).startsWith("send")) {
                int result = registry.send(token, sendRequest(arguments));
                return returnFor(method.getReturnType(), result);
            }
            throw new SecurityException("VIRTUAL_PENDING_INTENT_SENDER_SIGNATURE_UNSUPPORTED:" + name);
        };
        return Proxy.newProxyInstance(loader, new Class<?>[]{senderType}, handler);
    }

    private Parsed parse(Object[] arguments) {
        List<Integer> integers = new ArrayList<>();
        if (arguments != null) for (Object value : arguments) if (value instanceof Integer) integers.add((Integer) value);
        int type = integers.isEmpty() ? -1 : integers.get(0);
        int requestCode = integers.size() > 1 ? integers.get(1) : 0;
        int flags = integers.size() > 2 ? integers.get(integers.size() >= 4 ? integers.size() - 2 : integers.size() - 1) : 0;
        Intent[] intents = intentArray(arguments);
        Intent intent = intents.length == 0 ? new Intent() : intents[intents.length - 1];
        ComponentName component = intent.getComponent();
        String componentName = component == null ? "" : component.getClassName();
        if (kind(type) == VirtualPendingIntentRegistry.Kind.ACTIVITY_RESULT) {
            componentName = activityResultTarget(arguments);
        }
        String data = intent.getData() == null ? "" : intent.getData().toString();
        VirtualPendingIntentRegistry.Spec senderSpec = new VirtualPendingIntentRegistry.Spec(kind(type),
                requestCode, intent.getAction(), componentName, data, filterIdentity(intent), flags,
                requiredPermission(arguments));
        return new Parsed(senderSpec, intents);
    }

    private String activityResultTarget(Object[] arguments) {
        if (arguments != null) for (Object value : arguments) {
            if (value instanceof IBinder binder && registry.find(value) == null) {
                return activityTokenResolver.resolve(binder);
            }
        }
        throw new SecurityException("VIRTUAL_PENDING_INTENT_ACTIVITY_RESULT_TARGET_REQUIRED");
    }

    private static String filterIdentity(Intent intent) {
        StringBuilder out = new StringBuilder(160);
        out.append("a=").append(normalizeIdentity(intent.getAction()));
        out.append("|d=").append(intent.getData() == null ? "" : intent.getData());
        out.append("|t=").append(normalizeIdentity(intent.getType()));
        out.append("|p=").append(normalizeIdentity(intent.getPackage()));
        ComponentName component = intent.getComponent();
        out.append("|c=");
        if (component != null) {
            out.append(normalizeIdentity(component.getPackageName())).append('/')
                    .append(normalizeIdentity(component.getClassName()));
        }
        java.util.Set<String> categories = intent.getCategories();
        if (categories != null && !categories.isEmpty()) {
            java.util.List<String> sorted = new java.util.ArrayList<>(categories);
            java.util.Collections.sort(sorted);
            out.append("|g=").append(String.join(",", sorted));
        } else {
            out.append("|g=");
        }
        try {
            Object identifier = Intent.class.getMethod("getIdentifier").invoke(intent);
            out.append("|i=").append(identifier == null ? "" : identifier);
        } catch (ReflectiveOperationException ignored) {
            out.append("|i=");
        }
        return out.toString();
    }

    private static String normalizeIdentity(String value) {
        return value == null ? "" : value.trim();
    }

    private static VirtualPendingIntentRegistry.Kind kind(int type) {
        return switch (type) {
            case 1 -> VirtualPendingIntentRegistry.Kind.BROADCAST;
            case 2 -> VirtualPendingIntentRegistry.Kind.ACTIVITY;
            case 3 -> VirtualPendingIntentRegistry.Kind.ACTIVITY_RESULT;
            case 4 -> VirtualPendingIntentRegistry.Kind.SERVICE;
            case 5 -> VirtualPendingIntentRegistry.Kind.FOREGROUND_SERVICE;
            default -> throw new SecurityException("VIRTUAL_PENDING_INTENT_KIND_UNSUPPORTED:" + type);
        };
    }

    private Object senderToken(Object[] arguments) {
        if (arguments == null) return null;
        for (Object value : arguments) {
            if (value == null) continue;
            if (registry.find(value) != null) return value;
            if (Proxy.isProxyClass(value.getClass())) {
                try {
                    InvocationHandler handler = Proxy.getInvocationHandler(value);
                    Method asBinder = value.getClass().getMethod("asBinder");
                    asBinder.setAccessible(true);
                    Object token = asBinder.invoke(value);
                    if (registry.find(token) != null) return token;
                } catch (Throwable ignored) { }
            }
            if (value instanceof IBinder && registry.find(value) != null) return value;
        }
        return null;
    }

    private static Intent[] intentArray(Object[] arguments) {
        if (arguments == null) return new Intent[0];
        for (Object value : arguments) {
            if (value instanceof Intent[]) return ((Intent[]) value).clone();
            if (value != null && value.getClass().isArray()
                    && Intent.class.isAssignableFrom(value.getClass().getComponentType())) {
                int size = Array.getLength(value); Intent[] out = new Intent[size];
                for (int i = 0; i < size; i++) out[i] = new Intent((Intent) Array.get(value, i));
                return out;
            }
        }
        Intent one = firstIntent(arguments); return one == null ? new Intent[0] : new Intent[]{new Intent(one)};
    }

    private static Intent firstIntent(Object value) {
        if (value instanceof Intent) return (Intent) value;
        if (value instanceof Intent[] && ((Intent[]) value).length > 0) return ((Intent[]) value)[0];
        if (value instanceof Object[]) return firstIntent((Object[]) value);
        return null;
    }
    private static Intent firstIntent(Object[] arguments) {
        if (arguments == null) return null;
        for (Object value : arguments) {
            Intent found = firstIntent(value); if (found != null) return found;
        }
        return null;
    }
    private static VirtualPendingIntentRegistry.SendRequest sendRequest(Object[] arguments) {
        Intent fillIn = firstIntent(arguments);
        List<Integer> integers = new ArrayList<>();
        if (arguments != null) for (Object value : arguments) if (value instanceof Integer) integers.add((Integer) value);
        int mask = integers.size() >= 2 ? integers.get(integers.size() - 2) : 0;
        int values = integers.size() >= 1 ? integers.get(integers.size() - 1) : 0;
        return new VirtualPendingIntentRegistry.SendRequest(fillIn, mask, values,
                requiredPermission(arguments), -1);
    }
    private static String requiredPermission(Object[] arguments) {
        if (arguments == null) return "";
        for (Object value : arguments) {
            if (!(value instanceof String text)) continue;
            String normalized = text.trim();
            if (normalized.startsWith("android.permission.") || normalized.contains(".permission.")) {
                return normalized;
            }
        }
        return "";
    }

    private static Object returnFor(Class<?> type, int result) {
        if (type == void.class) return null;
        if (type == int.class || type == Integer.class) return result;
        if (type == boolean.class || type == Boolean.class) return result >= 0;
        return null;
    }
    private static Object defaultValue(Class<?> type) { return returnFor(type, 0); }

    private static final class SenderBinder extends Binder { }
    private record Parsed(VirtualPendingIntentRegistry.Spec spec, Intent[] intents) { }
}
