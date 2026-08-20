package com.warden.controlledsandbox.framework.binder;

import android.os.IBinder;
import android.os.IInterface;
import android.os.Binder;
import android.os.Parcel;
import android.os.RemoteException;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Common CAS Binder boundary for root services, nested returned interfaces and callbacks.
 *
 * <p>The Android Java API does not expose a way for an application to replace a remote Binder's
 * kernel object.  This class therefore uses one narrow {@link IBinder} adapter at the boundary;
 * all transaction semantics live here, rather than in one proxy per system service.  Existing
 * Java proxies remain above this class for typed argument and compatibility adaptation.</p>
 */
public final class BinderInterceptionFoundation implements AutoCloseable {
    private static final Map<IBinder, BinderInterceptionFoundation> ACTIVE_BINDERS =
            Collections.synchronizedMap(new IdentityHashMap<>());

    private final IBinder delegate;
    private final String descriptor;
    private final String serviceName;
    private final BinderIdentity identity;
    private final BinderSessionFence sessionFence;
    private final Object localInterface;
    private final List<BinderTransactionInterceptor> interceptors;
    private final Set<String> preservedArgumentBinderTypes;
    private final Map<IBinder, BinderInterceptionFoundation> returnedBinders =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private final Map<IBinder.DeathRecipient, IBinder.DeathRecipient> deathRecipients =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private final IBinder binder;
    private volatile boolean invalidated;
    private volatile String invalidationReason;

    private BinderInterceptionFoundation(Builder builder) {
        this.delegate = Objects.requireNonNull(builder.delegate, "delegate");
        this.identity = Objects.requireNonNull(builder.identity, "identity");
        this.serviceName = builder.serviceName == null ? "" : builder.serviceName.trim();
        this.sessionFence = builder.sessionFence == null
                ? BinderSessionFence.ALWAYS_ACTIVE : builder.sessionFence;
        this.localInterface = builder.localInterface;
        this.interceptors = Collections.unmodifiableList(new ArrayList<>(builder.interceptors));
        this.preservedArgumentBinderTypes = Collections.unmodifiableSet(
                new HashSet<>(builder.preservedArgumentBinderTypes));
        String actualDescriptor = builder.descriptor;
        if (actualDescriptor == null || actualDescriptor.trim().isEmpty()) {
            actualDescriptor = readDescriptor(delegate);
        }
        if (actualDescriptor == null || actualDescriptor.trim().isEmpty()) {
            throw new IllegalStateException("BINDER_DESCRIPTOR_UNAVAILABLE:" + serviceName);
        }
        this.descriptor = actualDescriptor.trim();
        // A Java Proxy implementing IBinder cannot be marshalled by Android's native Parcel
        // layer. Production therefore uses a real local Binder object; the Proxy fallback is
        // retained only for the repository's plain-Java test stubs, whose Binder.transact is not
        // the platform final/native entry point.
        this.binder = supportsNativeBinderBoundary()
                ? new BoundaryBinder(this)
                : createTestBinderProxy(this);
        ACTIVE_BINDERS.put(this.binder, this);
    }

    private static boolean supportsNativeBinderBoundary() {
        try {
            Method transact = Binder.class.getDeclaredMethod(
                    "transact", int.class, Parcel.class, Parcel.class, int.class);
            return Modifier.isFinal(transact.getModifiers());
        } catch (ReflectiveOperationException error) {
            return false;
        }
    }

    private static IBinder createTestBinderProxy(BinderInterceptionFoundation owner) {
        ClassLoader loader = owner.delegate.getClass().getClassLoader();
        if (loader == null) loader = BinderInterceptionFoundation.class.getClassLoader();
        return (IBinder) Proxy.newProxyInstance(
                loader, new Class<?>[] {IBinder.class},
                new BoundaryInvocationHandler(owner));
    }

    public static Builder builder(IBinder delegate, BinderIdentity identity) {
        return new Builder(delegate, identity);
    }

    /** Invalidates a Binder returned by this foundation without needing the owning hook object. */
    public static boolean invalidate(IBinder value, String reason) {
        if (value == null) return false;
        BinderInterceptionFoundation foundation = ACTIVE_BINDERS.get(value);
        if (foundation == null) return false;
        foundation.invalidateInternal(reason == null ? "INVALIDATED" : reason);
        return true;
    }

    /** Returns true when the value is a CAS-created Binder boundary. */
    public static boolean isBoundary(IBinder value) {
        return value != null && ACTIVE_BINDERS.containsKey(value);
    }

    public IBinder binder() { return binder; }
    public String descriptor() { return descriptor; }
    public String serviceName() { return serviceName; }
    public BinderIdentity identity() { return identity; }
    public boolean isActive() { return ensureActive(false); }
    public String invalidationReason() { return invalidationReason == null ? "" : invalidationReason; }

    /** Wraps a Binder returned from a typed service method or a callback argument. */
    public IBinder wrapBinder(IBinder value, String role) {
        if (value == null || value == binder) return value;
        if (value == delegate) return binder;
        if (isBoundary(value)) return value;
        synchronized (returnedBinders) {
            BinderInterceptionFoundation existing = returnedBinders.get(value);
            if (existing != null) return existing.binder;
            Builder child = new Builder(value, identity)
                    .serviceName(joinRole(role))
                    .sessionFence(sessionFence)
                    .interceptors(interceptors);
            String childDescriptor = readDescriptor(value);
            if (childDescriptor == null || childDescriptor.trim().isEmpty()) {
                // Plain local callback Binder implementations are allowed to omit an AIDL
                // descriptor.  Give the transaction record a non-sensitive CAS descriptor while
                // still forcing all calls through the same boundary.
                child.descriptor(fallbackDescriptor(role));
            }
            BinderInterceptionFoundation created = new BinderInterceptionFoundation(child);
            returnedBinders.put(value, created);
            return created.binder;
        }
    }

    /** Wraps a returned AIDL interface and preserves its generated interface type. */
    public Object wrapReturned(Object value, String role) {
        return wrapReturned(value, null, role);
    }

    /** Wraps a return value while retaining the method's declared AIDL interface type. */
    public Object wrapReturned(Object value, Class<?> expectedType, String role) {
        return wrapObject(value, expectedType, role == null ? "returned" : role, false);
    }

    /** Wraps a callback argument before it is written to a Host/System Server Binder call. */
    public Object wrapCallback(Object value, String role) {
        return wrapObject(value, null, role == null ? "callback" : role, false);
    }

    public Object[] wrapArguments(Object[] arguments, String role) {
        return wrapArguments(arguments, null, role);
    }

    /**
     * Wraps callback arguments while retaining declared AIDL parameter types. Some framework
     * AIDL proxy implementations do not expose their generated interface in the runtime class's
     * interface list on API32; the declared parameter type is the authoritative type here.
     */
    public Object[] wrapArguments(Object[] arguments, Class<?>[] expectedTypes, String role) {
        if (arguments == null || arguments.length == 0) return arguments;
        Object[] copy = arguments.clone();
        for (int index = 0; index < copy.length; index++) {
            Class<?> expectedType = expectedTypes != null && index < expectedTypes.length
                    ? expectedTypes[index] : null;
            if (expectedType != null
                    && preservedArgumentBinderTypes.contains(expectedType.getName())) {
                // AOSP registers this process endpoint by its exact Binder identity. Replacing
                // it would make ActivityManager/ContentProvider reject an otherwise valid call.
                continue;
            }
            copy[index] = wrapObject(copy[index], expectedType,
                    (role == null ? "argument" : role) + "[" + index + "]", true);
        }
        return copy;
    }

    /** Explicitly retires the root and every returned/callback Binder leased from it. */
    public void invalidate(String reason) {
        invalidateInternal(reason == null ? "INVALIDATED" : reason);
    }

    @Override public void close() {
        invalidate("CLOSED");
    }

    private Object wrapObject(Object value, Class<?> expectedType, String role,
                              boolean preserveRawBinder) {
        if (value == null) return null;
        // Raw IBinder parameters are AOSP authority tokens (ActivityRecord, ServiceRecord,
        // resultTo, permission and published-service binders). Their exact identity is part of
        // the system-server lookup key; typed AIDL interfaces below still receive a leased child
        // boundary.
        if (preserveRawBinder && expectedType == IBinder.class) return value;
        // A local AIDL Stub is both IBinder and IInterface. Prefer the declared AIDL type or the
        // typed view first; returning only an IBinder wrapper would fail strict reflection calls
        // such as IActivityManager.getContentProvider on API32.
        if (value instanceof IInterface interfaceValue
                && (expectedType == null || expectedType.isInterface())) {
            return wrapInterface(interfaceValue, expectedType, role);
        }
        if (value instanceof IBinder binderValue) return wrapBinder(binderValue, role);
        if (value instanceof IInterface interfaceValue) {
            return wrapInterface(interfaceValue, expectedType, role);
        }
        Class<?> type = value.getClass();
        if (type.isArray() && !type.getComponentType().isPrimitive()) {
            int length = Array.getLength(value);
            Object copy = Array.newInstance(type.getComponentType(), length);
            Class<?> expectedComponent = expectedType != null && expectedType.isArray()
                    ? expectedType.getComponentType() : null;
            for (int index = 0; index < length; index++) {
                Array.set(copy, index, wrapObject(Array.get(value, index), expectedComponent,
                        role + "[" + index + "]", preserveRawBinder));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            ArrayList<Object> copy = new ArrayList<>(list.size());
            for (int index = 0; index < list.size(); index++) {
                copy.add(wrapObject(list.get(index), null, role + "[" + index + "]",
                        preserveRawBinder));
            }
            return copy;
        }
        return value;
    }

    private Object wrapInterface(IInterface value, Class<?> expectedType, String role) {
        IBinder originalBinder;
        try {
            originalBinder = value.asBinder();
        } catch (Throwable error) {
            throw new IllegalStateException("BINDER_RETURNED_INTERFACE_BINDER_LOOKUP_FAILED:" + role,
                    error);
        }
        if (originalBinder == null) {
            throw new IllegalStateException("BINDER_RETURNED_INTERFACE_WITHOUT_BINDER:" + role);
        }
        IBinder wrapped = wrapBinder(originalBinder, role);
        if (wrapped == originalBinder) return value;
        Class<?>[] interfaces = collectInterfaces(value.getClass(), expectedType);
        if (interfaces.length == 0) {
            throw new IllegalStateException("BINDER_RETURNED_INTERFACE_TYPE_UNAVAILABLE:" + role);
        }
        ClassLoader loader = expectedType == null ? null : expectedType.getClassLoader();
        if (loader == null) loader = value.getClass().getClassLoader();
        if (loader == null) loader = BinderInterceptionFoundation.class.getClassLoader();
        return Proxy.newProxyInstance(loader, interfaces,
                new ReturnedInterfaceInvocationHandler(value, wrapped, role));
    }

    private boolean ensureActive(boolean notify) {
        if (invalidated) return false;
        boolean fenced;
        try {
            fenced = sessionFence.isActive(identity);
        } catch (Throwable error) {
            fenced = false;
        }
        boolean alive;
        try {
            alive = delegate.isBinderAlive();
        } catch (Throwable error) {
            alive = false;
        }
        if (!fenced || !alive) {
            invalidateInternal(!fenced ? "SESSION_FENCED" : "DELEGATE_DEAD");
            return false;
        }
        return true;
    }

    private void requireActive() throws RemoteException {
        if (!ensureActive(true)) {
            throw new RemoteException("CAS_BINDER_DEAD:" + invalidationReason());
        }
    }

    private void invalidateInternal(String reason) {
        List<IBinder.DeathRecipient> recipients;
        synchronized (this) {
            if (invalidated) return;
            invalidated = true;
            invalidationReason = reason;
            recipients = new ArrayList<>(deathRecipients.keySet());
            deathRecipients.clear();
        }
        ACTIVE_BINDERS.remove(binder);
        List<BinderInterceptionFoundation> children;
        synchronized (returnedBinders) {
            children = new ArrayList<>(returnedBinders.values());
            returnedBinders.clear();
        }
        for (BinderInterceptionFoundation child : children) child.invalidateInternal(reason);
        for (IBinder.DeathRecipient recipient : recipients) {
            try { recipient.binderDied(); }
            catch (Throwable ignored) {
                com.warden.controlledsandbox.framework.capability.FatalErrorPolicy
                        .rethrowIfFatal(ignored);
            }
        }
    }

    private String joinRole(String role) {
        String value = role == null ? "returned" : role.trim();
        if (value.isEmpty()) value = "returned";
        return serviceName.isEmpty() ? value : serviceName + "/" + value;
    }

    private static String readDescriptor(IBinder binder) {
        try {
            return binder.getInterfaceDescriptor();
        } catch (Throwable error) {
            com.warden.controlledsandbox.framework.capability.FatalErrorPolicy
                    .rethrowIfFatal(error);
            return "";
        }
    }

    private static String fallbackDescriptor(String role) {
        String value = role == null ? "callback" : role.trim();
        if (value.isEmpty()) value = "callback";
        StringBuilder safe = new StringBuilder("com.warden.controlledsandbox.binder");
        for (int index = 0; index < value.length() && safe.length() < 160; index++) {
            char current = value.charAt(index);
            safe.append(Character.isJavaIdentifierPart(current) ? current : '_');
        }
        return safe.toString();
    }

    private static Class<?>[] collectInterfaces(Class<?> type, Class<?> requiredInterface) {
        ArrayList<Class<?>> values = new ArrayList<>();
        if (requiredInterface != null && requiredInterface.isInterface()) {
            values.add(requiredInterface);
        }
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            for (Class<?> value : cursor.getInterfaces()) {
                if (!values.contains(value)) values.add(value);
                collectParents(value, values);
            }
        }
        if (values.isEmpty()) values.add(IInterface.class);
        return values.toArray(new Class<?>[0]);
    }

    private static void collectParents(Class<?> type, List<Class<?>> values) {
        for (Class<?> parent : type.getInterfaces()) {
            if (values.contains(parent)) continue;
            values.add(parent);
            collectParents(parent, values);
        }
    }

    private static Object invokeDelegate(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause == null) throw error;
            throw cause;
        }
    }

    /** Real local Binder transport used by Android's native Parcel marshalling path. */
    private static final class BoundaryBinder extends Binder {
        private final BinderInterceptionFoundation owner;

        BoundaryBinder(BinderInterceptionFoundation owner) {
            this.owner = owner;
            IInterface local = owner.localInterface instanceof IInterface
                    ? (IInterface) owner.localInterface : null;
            attachInterface(local, owner.descriptor);
        }

        @Override public String getInterfaceDescriptor() {
            return owner.descriptor;
        }

        public boolean pingBinder() {
            return owner.ensureActive(false);
        }

        @Override public boolean isBinderAlive() {
            return owner.ensureActive(false);
        }

        @Override public IInterface queryLocalInterface(String descriptor) {
            if (!owner.ensureActive(false)) return null;
            return owner.localInterface instanceof IInterface
                    && owner.descriptor.equals(descriptor)
                    ? (IInterface) owner.localInterface : null;
        }

        @Override protected boolean onTransact(int code, Parcel input, Parcel output, int flags)
                throws RemoteException {
            return owner.transact(code, input, output, flags);
        }

        @Override public void linkToDeath(IBinder.DeathRecipient recipient, int flags) {
            try {
                owner.linkToDeath(recipient, flags);
            } catch (RemoteException error) {
                sneakyThrow(error);
            }
        }

        @Override public boolean unlinkToDeath(IBinder.DeathRecipient recipient, int flags) {
            return owner.unlinkToDeath(recipient, flags);
        }

        @SuppressWarnings("unchecked")
        private static <T extends Throwable> void sneakyThrow(Throwable error) throws T {
            throw (T) error;
        }
    }

    private static final class BoundaryInvocationHandler implements InvocationHandler {
        private final BinderInterceptionFoundation owner;

        BoundaryInvocationHandler(BinderInterceptionFoundation owner) {
            this.owner = owner;
        }

        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                return switch (name) {
                    case "toString" -> "CASBinder[" + owner.descriptor + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (args == null ? null : args[0]);
                    default -> null;
                };
            }
            switch (name) {
                case "getInterfaceDescriptor":
                    return owner.descriptor;
                case "queryLocalInterface":
                    return args != null && args.length == 1
                            && owner.descriptor.equals(args[0]) ? owner.localInterface : null;
                case "isBinderAlive":
                    return owner.ensureActive(false);
                case "transact":
                    if (args == null || args.length != 4
                            || !(args[0] instanceof Integer code)
                            || !(args[1] instanceof Parcel input)
                            || !(args[3] instanceof Integer flags)) {
                        throw new IllegalArgumentException("BINDER_TRANSACT_SIGNATURE_INVALID");
                    }
                    Parcel output = args[2] instanceof Parcel ? (Parcel) args[2] : null;
                    return owner.transact(code, input, output, flags);
                case "linkToDeath":
                    if (args == null || args.length < 1 || !(args[0] instanceof IBinder.DeathRecipient)) {
                        throw new IllegalArgumentException("BINDER_DEATH_LINK_SIGNATURE_INVALID");
                    }
                    int linkFlags = args.length > 1 && args[1] instanceof Integer
                            ? (Integer) args[1] : 0;
                    owner.linkToDeath((IBinder.DeathRecipient) args[0], linkFlags);
                    return null;
                case "unlinkToDeath":
                    if (args == null || args.length < 1 || !(args[0] instanceof IBinder.DeathRecipient)) {
                        throw new IllegalArgumentException("BINDER_DEATH_UNLINK_SIGNATURE_INVALID");
                    }
                    int unlinkFlags = args.length > 1 && args[1] instanceof Integer
                            ? (Integer) args[1] : 0;
                    return owner.unlinkToDeath((IBinder.DeathRecipient) args[0], unlinkFlags);
                default:
                    return invokeDelegate(owner.delegate, method, args);
            }
        }
    }

    private boolean transact(int code, Parcel input, Parcel output, int flags)
            throws RemoteException {
        requireActive();
        BinderTransaction transaction = new BinderTransaction(descriptor, serviceName, code,
                input, output, flags, identity);
        return dispatch(transaction, 0);
    }

    private boolean dispatch(BinderTransaction transaction, int index) throws RemoteException {
        if (index >= interceptors.size()) {
            return delegate.transact(transaction.code(), transaction.input(), transaction.output(),
                    transaction.flags());
        }
        BinderTransactionInterceptor interceptor = interceptors.get(index);
        return interceptor.intercept(transaction, () -> dispatch(transaction, index + 1));
    }

    private void linkToDeath(IBinder.DeathRecipient recipient, int flags) throws RemoteException {
        requireActive();
        IBinder.DeathRecipient delegateRecipient = () -> invalidateInternal("BINDER_DIED");
        synchronized (deathRecipients) {
            deathRecipients.put(recipient, delegateRecipient);
        }
        try {
            delegate.linkToDeath(delegateRecipient, flags);
        } catch (RemoteException | RuntimeException | Error error) {
            deathRecipients.remove(recipient);
            throw error;
        }
    }

    private boolean unlinkToDeath(IBinder.DeathRecipient recipient, int flags) {
        IBinder.DeathRecipient delegateRecipient = deathRecipients.remove(recipient);
        if (delegateRecipient == null) return false;
        try {
            return delegate.unlinkToDeath(delegateRecipient, flags);
        } catch (RuntimeException error) {
            com.warden.controlledsandbox.framework.capability.FatalErrorPolicy
                    .rethrowIfFatal(error);
            return false;
        }
    }

    private final class ReturnedInterfaceInvocationHandler implements InvocationHandler {
        private final IInterface delegateInterface;
        private final IBinder wrappedBinder;
        private final String role;

        ReturnedInterfaceInvocationHandler(IInterface delegateInterface, IBinder wrappedBinder,
                                           String role) {
            this.delegateInterface = delegateInterface;
            this.wrappedBinder = wrappedBinder;
            this.role = role;
        }

        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getName().equals("asBinder") && method.getParameterCount() == 0) {
                return wrappedBinder;
            }
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "CASReturnedInterface[" + role + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (args == null ? null : args[0]);
                    default -> null;
                };
            }
            Object[] rewritten = wrapArguments(args, method.getParameterTypes(),
                    role + "." + method.getName());
            Object result = invokeDelegate(delegateInterface, method, rewritten);
            return wrapReturned(result, method.getReturnType(),
                    role + "." + method.getName() + ".return");
        }
    }

    public static final class Builder {
        private final IBinder delegate;
        private final BinderIdentity identity;
        private String descriptor;
        private String serviceName = "";
        private BinderSessionFence sessionFence = BinderSessionFence.ALWAYS_ACTIVE;
        private Object localInterface;
        private List<BinderTransactionInterceptor> interceptors = new ArrayList<>();
        private Set<String> preservedArgumentBinderTypes = new HashSet<>();

        private Builder(IBinder delegate, BinderIdentity identity) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.identity = Objects.requireNonNull(identity, "identity");
        }

        public Builder descriptor(String value) { descriptor = value; return this; }
        public Builder serviceName(String value) { serviceName = value; return this; }
        public Builder sessionFence(BinderSessionFence value) {
            sessionFence = value == null ? BinderSessionFence.ALWAYS_ACTIVE : value;
            return this;
        }
        public Builder localInterface(Object value) { localInterface = value; return this; }
        public Builder interceptor(BinderTransactionInterceptor value) {
            interceptors.add(Objects.requireNonNull(value, "interceptor"));
            return this;
        }
        public Builder interceptors(List<? extends BinderTransactionInterceptor> values) {
            interceptors = new ArrayList<>();
            if (values != null) for (BinderTransactionInterceptor value : values) {
                interceptors.add(Objects.requireNonNull(value, "interceptor"));
            }
            return this;
        }
        /** Preserves exact AOSP-registered process-token Binder arguments. */
        public Builder preserveBinderType(String className) {
            if (className != null && !className.trim().isEmpty()) {
                preservedArgumentBinderTypes.add(className.trim());
            }
            return this;
        }
        public BinderInterceptionFoundation build() {
            return new BinderInterceptionFoundation(this);
        }
    }
}
