package com.warden.controlledsandbox.runtime.guest;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import com.warden.controlledsandbox.framework.core.FrameworkCallInterceptor;
import com.warden.controlledsandbox.framework.routing.VirtualPendingIntentRegistry;
import com.warden.controlledsandbox.framework.identity.VirtualPendingIntentToken;
import com.warden.controlledsandbox.framework.identity.VirtualSystemServiceState;
import com.warden.controlledsandbox.contract.IRuntimeBroker;
import com.warden.controlledsandbox.runtime.broker.RuntimePendingIntentRelayReceiver;
import com.warden.controlledsandbox.contract.RuntimeOperationRequest;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import com.warden.controlledsandbox.runtime.protocol.RuntimeOperationTransport;
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

    /** Result retained across the Guest/Broker boundary for an IIntentSender completion callback. */
    record PersistentSendResult(boolean delivered, int resultCode, Intent deliveredIntent) { }

    private final GuestPackageSpec spec;
    private final VirtualPendingIntentRegistry registry;
    private final ActivityTokenResolver activityTokenResolver;
    private final String hostPackageName;
    private final Map<Object, Object> proxies = new IdentityHashMap<>();
    /** Maps the locally-owned registry key to the Binder exposed to host/system processes. */
    private final Map<Object, IBinder> exposedBinders = new IdentityHashMap<>();
    /** Lets framework callbacks that return only the remote Binder resolve the local record. */
    private final Map<Object, Object> remoteTokens = new IdentityHashMap<>();

    PendingIntentFrameworkInterceptor(GuestPackageSpec spec, Dispatcher dispatcher) {
        this(spec, null, token -> { throw new SecurityException("VIRTUAL_ACTIVITY_FRAMEWORK_TOKEN_UNKNOWN"); }, dispatcher, "");
    }

    PendingIntentFrameworkInterceptor(GuestPackageSpec spec,
            VirtualSystemServiceState.PendingIntentState persistence,
            ActivityTokenResolver activityTokenResolver, Dispatcher dispatcher) {
        this(spec, persistence, activityTokenResolver, dispatcher, "");
    }

    PendingIntentFrameworkInterceptor(GuestPackageSpec spec,
            VirtualSystemServiceState.PendingIntentState persistence,
            ActivityTokenResolver activityTokenResolver, Dispatcher dispatcher,
            String hostPackageName) {
        this.spec = java.util.Objects.requireNonNull(spec, "spec");
        this.activityTokenResolver = java.util.Objects.requireNonNull(activityTokenResolver, "activityTokenResolver");
        this.hostPackageName = hostPackageName == null ? "" : hostPackageName.trim();
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
            registry.cancel(token);
            proxies.remove(token);
            IBinder exposed = exposedBinders.remove(token);
            if (exposed != null) remoteTokens.remove(exposed);
            return Interception.handled(defaultValue(method.getReturnType()));
        }
        if (name.startsWith("sendintentsender")) {
            Object token = senderToken(arguments);
            if (token == null) return Interception.passThrough();
            int result = registry.send(token, sendRequest(method, arguments));
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

    boolean sendPersistent(String tokenId) {
        return sendPersistent(tokenId, VirtualPendingIntentRegistry.SendRequest.simple(null));
    }

    boolean sendPersistent(String tokenId, VirtualPendingIntentRegistry.SendRequest request) {
        return sendPersistentResult(tokenId, request).delivered();
    }

    PersistentSendResult sendPersistentResult(String tokenId,
                                              VirtualPendingIntentRegistry.SendRequest request) {
        try {
            VirtualPendingIntentRegistry.SendRequest resolved = request == null
                    ? VirtualPendingIntentRegistry.SendRequest.simple(null) : request;
            VirtualPendingIntentRegistry.Record record = registry.findPersistent(tokenId);
            if (record == null) return new PersistentSendResult(false, -1, null);
            // Compute the callback payload before delivery retires a one-shot record.  This is
            // the exact IIntentSender semantic: callback Intent = base sender Intent merged
            // with fill-in and masked flags, not the raw fill-in parcel supplied by the caller.
            Intent delivered = GuestPendingIntentDispatcher.selectedIntent(record.payload(), resolved);
            int resultCode = registry.sendPersistent(tokenId, resolved);
            return new PersistentSendResult(true, resultCode, delivered);
        } catch (Exception error) {
            return new PersistentSendResult(false, -1, null);
        }
    }

    @Override public synchronized void close() {
        registry.close();
        proxies.clear();
        exposedBinders.clear();
        remoteTokens.clear();
    }

    private Interception createSender(Method method, Object[] arguments) {
        if (!method.getReturnType().isInterface()) {
            throw new SecurityException("VIRTUAL_PENDING_INTENT_RETURN_TYPE_UNSUPPORTED:" + method.getReturnType());
        }
        Parsed parsed = parse(method, arguments);
        SenderBinder candidate = new SenderBinder(registry, descriptor(method.getReturnType()));
        VirtualPendingIntentRegistry.IssueResult issued = registry.issue(parsed.spec, candidate, parsed.intents);
        if (issued.record() == null) return Interception.handled(null);
        candidate.bind(issued.record().persistentTokenId());
        Object token = issued.record().token();
        IBinder exposed = exposedBinders.get(token);
        if (exposed == null) {
            exposed = createBrokerSender(issued.record().persistentTokenId(),
                    descriptor(method.getReturnType()));
            if (exposed == null) exposed = candidate;
            exposedBinders.put(token, exposed);
            if (exposed != candidate) remoteTokens.put(exposed, token);
        }
        Object sender = proxies.get(token);
        if (sender == null) {
            sender = createSenderProxy(method.getReturnType(), token, exposed);
            proxies.put(token, sender);
        }
        // System holders (NotificationManager / AlarmManager) can only send a real AMS
        // PendingIntentRecord. Rewrite the payload to a Broker-process relay and let AMS
        // own the record. Guest-local send still uses the Broker IIntentSender proxy.
        if (spec.runtimeBrokerBinder != null && !hostPackageName.isEmpty()) {
            rewriteIntentsForSystemHolder(arguments, issued.record().persistentTokenId());
            return Interception.passThrough();
        }
        return Interception.handled(sender);
    }

    private void rewriteIntentsForSystemHolder(Object[] arguments, String tokenId) {
        if (arguments == null || tokenId == null || tokenId.isEmpty()) return;
        for (int index = 0; index < arguments.length; index++) {
            if (arguments[index] instanceof String value && spec.packageName.equals(value)) {
                arguments[index] = hostPackageName;
                continue;
            }
            if (!(arguments[index] instanceof Intent[] intents)) continue;
            Intent[] rewritten = new Intent[intents.length];
            for (int cursor = 0; cursor < intents.length; cursor++) {
                Intent shadow = new Intent(RuntimePendingIntentRelayReceiver.ACTION);
                shadow.setComponent(new ComponentName(hostPackageName,
                        RuntimePendingIntentRelayReceiver.CLASS_NAME));
                shadow.setPackage(hostPackageName);
                shadow.putExtra(RuntimeKeys.PENDING_INTENT_TOKEN_ID, tokenId);
                rewritten[cursor] = shadow;
            }
            arguments[index] = rewritten;
        }
    }

    private IBinder createBrokerSender(String tokenId, String senderDescriptor) {
        if (spec.runtimeBrokerBinder == null) return null;
        try {
            IRuntimeBroker broker = IRuntimeBroker.Stub.asInterface(spec.runtimeBrokerBinder);
            if (broker == null) throw new IllegalStateException("RUNTIME_BROKER_BINDER_UNAVAILABLE");
            Bundle request = spec.toBundle();
            request.putString(ComponentOperations.OPERATION,
                    ComponentOperations.CREATE_PENDING_INTENT_SENDER);
            request.putString(RuntimeKeys.PENDING_INTENT_TOKEN_ID, tokenId);
            request.putString("pendingIntentSenderDescriptor", senderDescriptor);
            Bundle result = RuntimeOperationTransport.toLegacyBundle(RuntimeOperationTransport.execute(
                    broker, RuntimeOperationRequest.INVOKE_COMPONENT, request));
            GuestRuntimeBrokerBridge.requireSuccess(result,
                    ComponentOperations.CREATE_PENDING_INTENT_SENDER);
            IBinder binder = result.getBinder(RuntimeKeys.PENDING_INTENT_SENDER_BINDER);
            if (binder == null) throw new IllegalStateException("PENDING_INTENT_SENDER_BINDER_MISSING");
            return binder;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            // A production Guest always has the Broker capability. Do not silently return a
            // process-owned sender in that mode: doing so would reintroduce the post-death hole
            // this relay is designed to close. The legacy self-test has no Broker binder and
            // intentionally retains the local transport.
            throw error instanceof RuntimeException
                    ? (RuntimeException) error : new IllegalStateException(error);
        }
    }

    private Object createSenderProxy(Class<?> senderType, Object token, IBinder exposedBinder) {
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
            if (name.equals("asBinder")) return exposedBinder;
            if (name.toLowerCase(Locale.ROOT).startsWith("send")) {
                int result = registry.send(token, sendRequest(method, arguments));
                return returnFor(method.getReturnType(), result);
            }
            throw new SecurityException("VIRTUAL_PENDING_INTENT_SENDER_SIGNATURE_UNSUPPORTED:" + name);
        };
        return Proxy.newProxyInstance(loader, new Class<?>[]{senderType}, handler);
    }

    private Parsed parse(Method method, Object[] arguments) {
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
                creationPermission(method, arguments));
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
            Object mapped = remoteTokens.get(value);
            if (mapped != null) return mapped;
            if (Proxy.isProxyClass(value.getClass())) {
                try {
                    Method asBinder = value.getClass().getMethod("asBinder");
                    asBinder.setAccessible(true);
                    Object token = asBinder.invoke(value);
                    if (registry.find(token) != null) return token;
                    mapped = remoteTokens.get(token);
                    if (mapped != null) return mapped;
                } catch (Throwable ignored) { com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(ignored); }
            }
            if (value instanceof IBinder) {
                mapped = remoteTokens.get(value);
                if (mapped != null) return mapped;
            }
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
    private static VirtualPendingIntentRegistry.SendRequest sendRequest(Method method,
                                                                          Object[] arguments) {
        Intent fillIn = firstIntent(arguments);
        // IIntentSender.send's first integer is the caller-supplied result code.  It is
        // delivered to IIntentReceiver.performReceive and is not the Intent fill-in flags
        // mask/value pair.  Treating it as flagsValues corrupts mutable PendingIntent routing
        // and makes an immutable sender reject an otherwise empty fill-in when code != 0.
        int mask = 0;
        int values = 0;
        int resultCode = 0;
        if (arguments != null) {
            for (Object value : arguments) {
                if (value instanceof Integer) {
                    resultCode = (Integer) value;
                    break;
                }
            }
        }
        return new VirtualPendingIntentRegistry.SendRequest(fillIn, mask, values,
                sendPermission(method, arguments), -1, resultCode);
    }

    /**
     * IIntentSender.send has a stable AIDL position for requiredPermission.  Do not infer a
     * permission from a package/action string: valid application identifiers may contain the
     * word "permission" without being a permission contract.
     */
    private static String sendPermission(Method method, Object[] arguments) {
        if (method == null || arguments == null) return "";
        Class<?>[] types = method.getParameterTypes();
        int index = 5;
        // The four-argument FakeIntentSender in the host self-test is intentionally a
        // positional compatibility adapter; production IIntentSender always uses index 5.
        if (types.length == 4 && types[3] == String.class) index = 3;
        if (types.length <= index || types[index] != String.class || arguments.length <= index) return "";
        Object value = arguments[index];
        return value instanceof String ? ((String) value).trim() : "";
    }

    private static String creationPermission(Method method, Object[] arguments) {
        // AMS getIntentSender* does not carry the sender permission.  It is delivered by the
        // real IIntentSender.send AIDL call above, so creation arguments are never heuristically
        // classified as permissions.
        // The host self-test uses a legacy seven-argument adapter with an explicit final
        // permission slot; retain that positional adapter without string-content matching.
        if (method != null && arguments != null && method.getParameterCount() == 7
                && method.getParameterTypes()[6] == String.class && arguments.length > 6
                && arguments[6] instanceof String) {
            return ((String) arguments[6]).trim();
        }
        return "";
    }

    private static String descriptor(Class<?> senderType) {
        if (senderType != null) {
            try {
                java.lang.reflect.Field field = senderType.getDeclaredField("DESCRIPTOR");
                field.setAccessible(true);
                Object value = field.get(null);
                if (value instanceof String && !((String) value).trim().isEmpty()) {
                    return ((String) value).trim();
                }
            } catch (Throwable ignored) {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(ignored);
            }
            if (senderType.getName() != null && !senderType.getName().isEmpty()) {
                return senderType.getName();
            }
        }
        return "android.content.IIntentSender";
    }

    private static Object returnFor(Class<?> type, int result) {
        if (type == void.class) return null;
        if (type == int.class || type == Integer.class) return result;
        if (type == boolean.class || type == Boolean.class) return result >= 0;
        return null;
    }
    private static Object defaultValue(Class<?> type) { return returnFor(type, 0); }

    private static final class SenderBinder extends Binder implements VirtualPendingIntentToken {
        private static final int INTERFACE_TRANSACTION = 0x5f4e5446;
        private static final int SEND_TRANSACTION = 1;
        private final VirtualPendingIntentRegistry registry;
        private final String descriptor;
        private volatile String tokenId = "";

        SenderBinder(VirtualPendingIntentRegistry registry, String descriptor) {
            this.registry = java.util.Objects.requireNonNull(registry, "registry");
            this.descriptor = descriptor == null || descriptor.isBlank()
                    ? "android.content.IIntentSender" : descriptor;
            // A null local owner is deliberate.  Framework Stub.asInterface must build its
            // generated Proxy and exercise the Binder transport even in the hosting process.
            attachInterface(null, this.descriptor);
        }

        void bind(String value) { if (value != null && !value.isBlank()) tokenId = value.trim(); }
        @Override public String persistentTokenId() { return tokenId; }
        @Override public String toString() { return "VirtualPendingIntentBinder[" + tokenId + "]"; }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags);
            if (code != SEND_TRANSACTION) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(descriptor);
            if (dataAvail(data) <= 0) throw new RemoteException("IIntentSender.send payload missing");
            int resultCode = data.readInt();
            Intent intent = data.readInt() != 0 ? readIntent(data) : null;
            data.readString(); // resolved type
            readStrongBinder(data); // whitelist token
            readStrongBinder(data); // finished receiver
            String permission = data.readString();
            if (dataAvail(data) > 0) readBundle(data);
            try {
                int result = registry.send(this, new VirtualPendingIntentRegistry.SendRequest(
                        intent, 0, 0, permission, -1, resultCode));
                reply.writeNoException();
                reply.writeInt(result);
                return true;
            } catch (Throwable error) {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
                writeException(reply, error instanceof Exception
                        ? (Exception) error : new RuntimeException(error));
                return true;
            }
        }

        private static int dataAvail(Parcel parcel) {
            try {
                Method method = Parcel.class.getMethod("dataAvail");
                return ((Number) method.invoke(parcel)).intValue();
            } catch (Throwable ignored) {
                return 0;
            }
        }

        private static Object readStrongBinder(Parcel parcel) {
            try {
                return Parcel.class.getMethod("readStrongBinder").invoke(parcel);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static Intent readIntent(Parcel parcel) throws RemoteException {
            try {
                java.lang.reflect.Field field = Intent.class.getField("CREATOR");
                Object creator = field.get(null);
                Method method = creator.getClass().getMethod("createFromParcel", Parcel.class);
                return (Intent) method.invoke(creator, parcel);
            } catch (Throwable error) {
                throw new RemoteException("IIntentSender Intent decode failed: " + error);
            }
        }

        private static void readBundle(Parcel parcel) {
            try {
                Parcel.class.getMethod("readBundle", ClassLoader.class)
                        .invoke(parcel, SenderBinder.class.getClassLoader());
            } catch (Throwable ignored) {
                // Older static/API adapters do not expose Bundle options. The send contract
                // remains valid because options are not part of the virtual route identity.
            }
        }

        private static void writeException(Parcel parcel, Exception error) {
            try {
                Parcel.class.getMethod("writeException", Exception.class).invoke(parcel, error);
            } catch (Throwable ignored) {
                parcel.writeNoException();
            }
        }
    }
    private record Parsed(VirtualPendingIntentRegistry.Spec spec, Intent[] intents) { }
}
