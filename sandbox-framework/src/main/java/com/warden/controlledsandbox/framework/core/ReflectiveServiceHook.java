package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.framework.identity.GuestIdentity;

import android.content.Context;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationHandler;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.lang.reflect.Array;

/** Reversible proxy installer for framework manager fields and android.util.Singleton instances. */
public final class ReflectiveServiceHook implements AutoCloseable {
    private final Object fieldOwner;
    private final Field field;
    private final Object original;
    private final Object proxy;

    private ReflectiveServiceHook(Object fieldOwner, Field field, Object original, Object proxy) {
        this.fieldOwner = fieldOwner;
        this.field = field;
        this.original = original;
        this.proxy = proxy;
    }

    private ReflectiveServiceHook() {
        this.fieldOwner = null;
        this.field = null;
        this.original = null;
        this.proxy = null;
    }

    public static ReflectiveServiceHook managerField(Context context, String serviceName, String fieldName,
                                              GuestIdentity identity) throws Exception {
        Object manager = context.getSystemService(serviceName);
        if (manager == null) throw new IllegalStateException("System service unavailable: " + serviceName);
        return replaceField(manager, fieldName, identity, serviceName);
    }

    /**
     * Installs at the stable ServiceManager boundary for framework managers which deliberately
     * retain no per-instance IInterface field.  The replacement accepts only the expected Binder
     * descriptor and exposes the already-audited interface proxy through queryLocalInterface.
     */
    public static AutoCloseable serviceManagerBinding(String serviceName, String descriptor,
                                                      GuestIdentity identity) throws Exception {
        return serviceManagerBinding(serviceName, serviceName, descriptor, identity);
    }

    public static AutoCloseable serviceManagerBinding(
            String androidServiceName, String logicalServiceName, String descriptor,
            GuestIdentity identity) throws Exception {
        if (androidServiceName == null || androidServiceName.trim().isEmpty()) {
            throw new IllegalArgumentException("androidServiceName is required");
        }
        if (descriptor == null || descriptor.trim().isEmpty()) {
            throw new IllegalArgumentException("descriptor is required");
        }
        Class<?> managerClass = Class.forName("android.os.ServiceManager");
        Method getService = managerClass.getDeclaredMethod("getService", String.class);
        getService.setAccessible(true);
        Object original = getService.invoke(null, androidServiceName);
        if (!(original instanceof android.os.IBinder)) {
            throw new IllegalStateException("ServiceManager binder unavailable: " + androidServiceName);
        }
        android.os.IBinder binder = (android.os.IBinder) original;
        validateBinderDescriptor(binder, descriptor, androidServiceName);
        if (binder instanceof Proxy) {
            InvocationHandler existing = Proxy.getInvocationHandler(binder);
            if (existing instanceof ServiceManagerBinderInvocationHandler handler
                    && handler.matches(descriptor, logicalServiceName)) {
                return () -> { };
            }
        }
        Class<?> stub = Class.forName(descriptor + "$Stub");
        Object service = findAsInterface(stub).invoke(null, binder);
        if (service == null) throw new IllegalStateException(
                "Binder descriptor has no local interface: " + androidServiceName);
        Object serviceProxy = createProxy(service, identity, logicalServiceName);
        InvocationHandler binderHandler = new ServiceManagerBinderInvocationHandler(
                binder, descriptor, logicalServiceName, serviceProxy);
        android.os.IBinder replacement = (android.os.IBinder) Proxy.newProxyInstance(
                binder.getClass().getClassLoader() == null ? ReflectiveServiceHook.class.getClassLoader()
                        : binder.getClass().getClassLoader(),
                new Class<?>[] {android.os.IBinder.class}, binderHandler);
        Field cacheField = findField(managerClass, "sCache");
        cacheField.setAccessible(true);
        Object cache = cacheField.get(null);
        if (!(cache instanceof Map)) throw new IllegalStateException("ServiceManager cache unavailable");
        @SuppressWarnings("unchecked") Map<String, Object> entries = (Map<String, Object>) cache;
        Object previous;
        synchronized (entries) {
            previous = entries.put(androidServiceName, replacement);
        }
        return new ServiceManagerBinding(entries, androidServiceName, previous, replacement);
    }

    /**
     * Installs a local, descriptor-bound Binder only when every requested platform service is
     * absent.  This is the controlled boundary for radio-less images: a missing host service is
     * represented by a virtual AIDL interface, while an existing Binder (including a descriptor
     * mismatch) remains fail-closed and is never overwritten.
     */
    public static AutoCloseable syntheticServiceManagerBindings(
            List<String> androidServiceNames, String descriptor, String logicalServiceName,
            GuestIdentity identity) throws Exception {
        if (androidServiceNames == null || androidServiceNames.isEmpty()) {
            throw new IllegalArgumentException("androidServiceNames are required");
        }
        Class<?> interfaceType = Class.forName(descriptor);
        if (!interfaceType.isInterface()) {
            throw new IllegalStateException("Synthetic Binder contract is not an interface: " + descriptor);
        }
        ClassLoader interfaceLoader = interfaceType.getClassLoader();
        if (interfaceLoader == null) interfaceLoader = ReflectiveServiceHook.class.getClassLoader();
        Object serviceProxy = Proxy.newProxyInstance(interfaceLoader,
                new Class<?>[] {interfaceType},
                new SystemServiceInvocationHandler(null, identity, logicalServiceName));
        Class<?> managerClass = Class.forName("android.os.ServiceManager");
        Method getService = managerClass.getDeclaredMethod("getService", String.class);
        getService.setAccessible(true);
        Field cacheField = findField(managerClass, "sCache");
        cacheField.setAccessible(true);
        Object cache = cacheField.get(null);
        if (!(cache instanceof Map)) throw new IllegalStateException("ServiceManager cache unavailable");
        @SuppressWarnings("unchecked") Map<String, Object> entries = (Map<String, Object>) cache;
        List<ServiceManagerBinding> installed = new ArrayList<>();
        try {
            for (String androidServiceName : androidServiceNames) {
                Object existing = getService.invoke(null, androidServiceName);
                if (existing instanceof android.os.IBinder binder) {
                    String actual = binder.getInterfaceDescriptor();
                    throw new IllegalStateException("Host Binder exists for " + androidServiceName
                            + ": " + actual + "; synthetic replacement refused");
                }
            }
            for (String androidServiceName : androidServiceNames) {
                android.os.IBinder replacement = syntheticBinder(descriptor, serviceProxy);
                Object previous;
                synchronized (entries) {
                    previous = entries.put(androidServiceName, replacement);
                }
                installed.add(new ServiceManagerBinding(entries, androidServiceName,
                        previous, replacement));
            }
            return new CompositeHook(installed);
        } catch (Throwable error) {
            for (int index = installed.size() - 1; index >= 0; index--) {
                try { installed.get(index).close(); } catch (Throwable rollback) { error.addSuppressed(rollback); }
            }
            com.warden.controlledsandbox.framework.capability.FatalErrorPolicy.rethrowIfFatal(error);
            if (error instanceof Exception exception) throw exception;
            throw new IllegalStateException("SYNTHETIC_SERVICE_MANAGER_BINDING_FAILED", error);
        }
    }

    private static android.os.IBinder syntheticBinder(String descriptor, Object serviceProxy) {
        return (android.os.IBinder) Proxy.newProxyInstance(
                ReflectiveServiceHook.class.getClassLoader(),
                new Class<?>[] {android.os.IBinder.class}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "SyntheticBinder[" + descriptor + "]";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == (args == null ? null : args[0]);
                            default -> null;
                        };
                    }
                    return switch (method.getName()) {
                        case "getInterfaceDescriptor" -> descriptor;
                        case "queryLocalInterface" -> args != null && args.length == 1
                                && descriptor.equals(args[0]) ? serviceProxy : null;
                        case "isBinderAlive", "pingBinder" -> true;
                        case "unlinkToDeath" -> true;
                        case "linkToDeath" -> null;
                        default -> throw new UnsupportedOperationException(
                                "SYNTHETIC_BINDER_SIGNATURE_UNSUPPORTED:" + method.getName());
                    };
                });
    }

    /**
     * Prefer the stable ServiceManager boundary and retain a descriptor-checked legacy field
     * fallback for platform revisions which do not expose the service through that boundary.
     */
    public static AutoCloseable managerFieldCandidatesOrServiceManagerBinding(
            Context context, String androidServiceName, String logicalServiceName,
            String descriptor, GuestIdentity identity, String... fieldPaths) throws Exception {
        return managerFieldCandidatesOrServiceManagerBinding(context, androidServiceName,
                logicalServiceName, descriptor, identity,
                java.util.List.of(androidServiceName), fieldPaths);
    }

    public static AutoCloseable managerFieldCandidatesOrServiceManagerBinding(
            Context context, String androidServiceName, String logicalServiceName,
            String descriptor, GuestIdentity identity, java.util.List<String> serviceNames,
            String... fieldPaths) throws Exception {
        java.util.ArrayList<Throwable> failures = new java.util.ArrayList<>();
        for (String serviceName : serviceNames) {
            try {
                AutoCloseable serviceManagerHook = serviceManagerBinding(
                        serviceName, logicalServiceName, descriptor, identity);
                try {
                    ReflectiveServiceHook managerHook = managerFieldCandidatesWithDescriptor(
                            context, androidServiceName, logicalServiceName, descriptor,
                            identity, fieldPaths);
                    return new CompositeHook(serviceManagerHook, managerHook);
                } catch (Throwable managerError) {
                    // A lazy Android manager can resolve the now-replaced ServiceManager entry
                    // later.  Keep the descriptor-validated binding when its private cache has
                    // no compatible field, but never hide failures from the field-only path.
                    return serviceManagerHook;
                }
            } catch (Throwable error) {
                failures.add(error);
            }
        }

        try {
            return managerFieldCandidatesWithDescriptor(context, androidServiceName,
                    logicalServiceName, descriptor, identity, fieldPaths);
        } catch (Throwable error) {
            failures.add(error);
        }
        IllegalStateException failure = new IllegalStateException(
                "No supported Binder binding for " + logicalServiceName);
        for (Throwable error : failures) failure.addSuppressed(error);
        throw failure;
    }

    /**
     * Installs a descriptor-checked manager-field binding without accepting an unvalidated
     * private field.  This is the common fallback for Android releases whose managers cache an
     * IInterface before ServiceManager can be replaced.
     */
    public static ReflectiveServiceHook managerFieldCandidatesWithDescriptor(
            Context context, String androidServiceName, String logicalServiceName,
            String descriptor, GuestIdentity identity, String... fieldPaths) throws Exception {
        Object manager = context.getSystemService(androidServiceName);
        if (manager == null) {
            throw new IllegalStateException("System service unavailable: " + androidServiceName);
        }
        java.util.ArrayList<Throwable> failures = new java.util.ArrayList<>();
        for (String path : fieldPaths) {
            try {
                return replacePath(manager, path, identity, logicalServiceName, descriptor);
            } catch (Throwable error) {
                failures.add(error);
            }
        }
        IllegalStateException failure = new IllegalStateException(
                "No descriptor-validated manager field for " + logicalServiceName);
        for (Throwable error : failures) failure.addSuppressed(error);
        throw failure;
    }

    /**
     * Creates a fail-closed local interface only when the platform explicitly exposes a null
     * IInterface field and no host Binder exists.  It is used for headless telephony stacks; a
     * non-null host object is never replaced by this path.
     */
    public static ReflectiveServiceHook syntheticManagerFieldCandidates(
            Context context, String androidServiceName, String logicalServiceName,
            GuestIdentity identity, String... fieldPaths) throws Exception {
        Object manager = context.getSystemService(androidServiceName);
        if (manager == null) {
            throw new IllegalStateException("System service unavailable: " + androidServiceName);
        }
        java.util.ArrayList<Throwable> failures = new java.util.ArrayList<>();
        for (String path : fieldPaths) {
            try {
                return replaceSyntheticPath(manager, path, identity, logicalServiceName);
            } catch (Throwable error) {
                failures.add(error);
            }
        }
        IllegalStateException failure = new IllegalStateException(
                "No null manager field for synthetic " + logicalServiceName);
        for (Throwable error : failures) failure.addSuppressed(error);
        throw failure;
    }

    public static ReflectiveServiceHook managerFieldCandidates(
            Context context, String androidServiceName, String logicalServiceName,
            GuestIdentity identity, String... fieldPaths) throws Exception {
        Object manager = context.getSystemService(androidServiceName);
        if (manager == null) throw new IllegalStateException(
                "System service unavailable: " + androidServiceName);
        java.util.ArrayList<Throwable> failures = new java.util.ArrayList<>();
        for (String path : fieldPaths) {
            try { return replacePath(manager, path, identity, logicalServiceName); }
            catch (Throwable error) { failures.add(error); }
        }
        IllegalStateException failure = new IllegalStateException(
                "No supported Binder field for " + logicalServiceName);
        for (Throwable error : failures) failure.addSuppressed(error);
        throw failure;
    }

    /**
     * Replaces known manager/global caches with empty instances for the lifetime of a proxy.
     * This prevents a DisplayManager created before installation from returning a host Display
     * object after its Binder field has been virtualized.  Every replaced field is restored only
     * if it still contains the replacement, so rollback cannot clobber a later owner.
     */
    public static AutoCloseable clearManagerCaches(Context context, String serviceName,
                                                   String... fieldPaths) throws Exception {
        Object manager = context.getSystemService(serviceName);
        if (manager == null) throw new IllegalStateException("System service unavailable: " + serviceName);
        List<ReflectiveServiceHook> installed = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        for (String path : fieldPaths) {
            if (path == null || path.isBlank()) continue;
            try {
                Field target = resolvePath(manager, path);
                Object original = target.get(targetOwner(manager, path));
                Object replacement = emptyCache(original, target.getType());
                if (replacement == null || replacement == original) continue;
                Object owner = targetOwner(manager, path);
                target.set(owner, replacement);
                installed.add(new ReflectiveServiceHook(owner, target, original, replacement));
            } catch (NoSuchFieldException ignored) {
                // API32/API35 do not expose exactly the same cache set.
            } catch (Throwable error) {
                failures.add(error);
            }
        }
        if (!failures.isEmpty()) {
            for (int index = installed.size() - 1; index >= 0; index--) {
                try { installed.get(index).close(); } catch (Throwable rollback) {
                    failures.get(0).addSuppressed(rollback);
                }
            }
            IllegalStateException failure = new IllegalStateException(
                    "DISPLAY_MANAGER_CACHE_SYNC_FAILED");
            for (Throwable error : failures) failure.addSuppressed(error);
            throw failure;
        }
        if (installed.isEmpty()) {
            throw new IllegalStateException("DISPLAY_MANAGER_CACHE_STRUCTURE_UNSUPPORTED");
        }
        return new CompositeHook(installed);
    }

    /** Combines reversible handles while preserving reverse-order rollback. */
    public static AutoCloseable compose(AutoCloseable... hooks) {
        List<AutoCloseable> values = new ArrayList<>();
        if (hooks != null) for (AutoCloseable hook : hooks) if (hook != null) values.add(hook);
        return new CompositeHook(values);
    }

    public static ReflectiveServiceHook staticField(String ownerClassName, String fieldName,
                                             String initializerMethod, GuestIdentity identity) throws Exception {
        return staticField(ownerClassName, fieldName, initializerMethod, identity, "");
    }

    public static ReflectiveServiceHook staticField(String ownerClassName, String fieldName,
                                             String initializerMethod, GuestIdentity identity,
                                             String serviceName) throws Exception {
        Class<?> owner = Class.forName(ownerClassName);
        Field field = findField(owner, fieldName);
        field.setAccessible(true);
        Object original = field.get(null);
        if (original == null && initializerMethod != null && !initializerMethod.isEmpty()) {
            Method initializer = owner.getDeclaredMethod(initializerMethod);
            initializer.setAccessible(true);
            original = initializer.invoke(null);
        }
        return replace(null, field, original, identity, serviceName);
    }

    public static ReflectiveServiceHook staticInstanceFieldCandidates(
            String ownerClassName, String getterMethod, GuestIdentity identity,
            String serviceName, String... fieldPaths) throws Exception {
        Class<?> owner = Class.forName(ownerClassName);
        Method getter = owner.getDeclaredMethod(getterMethod);
        getter.setAccessible(true);
        Object instance = getter.invoke(null);
        if (instance == null) throw new IllegalStateException(ownerClassName + "." + getterMethod + " returned null");
        java.util.ArrayList<Throwable> failures = new java.util.ArrayList<>();
        for (String path : fieldPaths) {
            try { return replacePath(instance, path, identity, serviceName); }
            catch (Throwable error) { failures.add(error); }
        }
        IllegalStateException failure = new IllegalStateException("No supported Binder field for " + serviceName);
        for (Throwable error : failures) failure.addSuppressed(error);
        throw failure;
    }

    public static ReflectiveServiceHook singleton(String ownerClassName, String singletonFieldName,
                                           GuestIdentity identity) throws Exception {
        return singleton(ownerClassName, singletonFieldName, identity, "");
    }

    public static ReflectiveServiceHook singleton(String ownerClassName, String singletonFieldName,
                                           GuestIdentity identity, String serviceName) throws Exception {
        Class<?> owner = Class.forName(ownerClassName);
        Field singletonField = findField(owner, singletonFieldName);
        singletonField.setAccessible(true);
        Object singleton = singletonField.get(null);
        if (singleton == null) throw new IllegalStateException(singletonFieldName + " is null");
        Field instance = findField(singleton.getClass(), "mInstance");
        instance.setAccessible(true);
        Object original = instance.get(singleton);
        if (original == null) {
            Method get = singleton.getClass().getMethod("get");
            get.setAccessible(true);
            original = get.invoke(singleton);
        }
        return replace(singleton, instance, original, identity, serviceName);
    }

    /**
     * Installs one proxy across a framework Singleton's value and any version-specific cache
     * fields which can bypass {@code Singleton#get()}.  ActivityClient has used both the
     * inherited {@code mInstance} cache and the additional {@code mKnownInstance} fast path;
     * replacing only one of them makes readiness look successful while calls still reach the
     * host controller.
     */
    public static AutoCloseable singletonWithCacheCandidates(
            String ownerClassName, String singletonFieldName, GuestIdentity identity,
            String serviceName, String expectedDescriptor, String... cacheFieldNames)
            throws Exception {
        Objects.requireNonNull(cacheFieldNames, "cacheFieldNames");
        Class<?> owner = Class.forName(ownerClassName);
        Field singletonField = findField(owner, singletonFieldName);
        singletonField.setAccessible(true);
        Object singleton = singletonField.get(null);
        if (singleton == null) throw new IllegalStateException(singletonFieldName + " is null");

        Field instanceField = findField(singleton.getClass(), "mInstance");
        instanceField.setAccessible(true);
        List<Field> fields = new ArrayList<>();
        fields.add(instanceField);
        for (String name : cacheFieldNames) {
            if (name == null || name.isBlank()) continue;
            Field candidate;
            try { candidate = findField(singleton.getClass(), name); }
            catch (NoSuchFieldException ignored) { continue; }
            candidate.setAccessible(true);
            if (!fields.contains(candidate)) fields.add(candidate);
        }

        Object delegate = null;
        Object existingProxy = null;
        for (Field field : fields) {
            Object value = field.get(singleton);
            if (isServiceProxy(value, serviceName)) {
                if (existingProxy != null && existingProxy != value) {
                    throw new IllegalStateException("FRAMEWORK_SINGLETON_CACHE_INCONSISTENT:" + serviceName);
                }
                existingProxy = value;
            } else if (value != null && delegate == null) {
                delegate = value;
            }
        }
        if (delegate == null && existingProxy == null) {
            Method get = singleton.getClass().getMethod("get");
            get.setAccessible(true);
            delegate = get.invoke(singleton);
        }
        if (existingProxy != null) {
            for (Field field : fields) {
                Object value = field.get(singleton);
                if (value != existingProxy) {
                    throw new IllegalStateException("FRAMEWORK_SINGLETON_CACHE_INCONSISTENT:" + serviceName);
                }
            }
            return () -> { };
        }
        if (delegate == null) {
            throw new IllegalStateException("Framework delegate is null after singleton get(): "
                    + serviceName);
        }
        if (expectedDescriptor != null && !expectedDescriptor.isEmpty()) {
            validateServiceDescriptor(delegate, expectedDescriptor, serviceName);
        }
        Object proxy = createProxy(delegate, identity, serviceName);
        List<ReflectiveServiceHook> installed = new ArrayList<>();
        try {
            for (Field field : fields) {
                Object original = field.get(singleton);
                field.set(singleton, proxy);
                installed.add(new ReflectiveServiceHook(singleton, field, original, proxy));
            }
            return new CompositeHook(installed);
        } catch (Throwable error) {
            for (int index = installed.size() - 1; index >= 0; index--) {
                try { installed.get(index).close(); } catch (Throwable rollback) {
                    error.addSuppressed(rollback);
                }
            }
            com.warden.controlledsandbox.framework.capability.FatalErrorPolicy.rethrowIfFatal(error);
            if (error instanceof Exception exception) throw exception;
            throw new IllegalStateException("FRAMEWORK_SINGLETON_CACHE_INSTALL_FAILED", error);
        }
    }

    static ReflectiveServiceHook replaceField(Object owner, String fieldName, GuestIdentity identity) throws Exception {
        return replaceField(owner, fieldName, identity, "");
    }

    static ReflectiveServiceHook replaceField(Object owner, String fieldName, GuestIdentity identity,
                                              String serviceName) throws Exception {
        Field field = findField(owner.getClass(), fieldName);
        field.setAccessible(true);
        return replace(owner, field, field.get(owner), identity, serviceName);
    }

    private static ReflectiveServiceHook replacePath(Object root, String path, GuestIdentity identity,
                                                     String serviceName) throws Exception {
        return replacePath(root, path, identity, serviceName, "");
    }

    private static ReflectiveServiceHook replacePath(Object root, String path, GuestIdentity identity,
                                                     String serviceName, String expectedDescriptor) throws Exception {
        if (path == null || path.trim().isEmpty()) throw new IllegalArgumentException("field path is required");
        String[] segments = path.split("\\.");
        Object owner = root;
        for (int index = 0; index < segments.length - 1; index++) {
            Field field = findField(owner.getClass(), segments[index]);
            field.setAccessible(true);
            owner = field.get(owner);
            if (owner == null) throw new IllegalStateException("Null service field segment: " + segments[index]);
        }
        Field target = findField(owner.getClass(), segments[segments.length - 1]);
        target.setAccessible(true);
        return replace(owner, target, target.get(owner), identity, serviceName, expectedDescriptor);
    }

    private static ReflectiveServiceHook replaceSyntheticPath(Object root, String path,
                                                              GuestIdentity identity,
                                                              String serviceName) throws Exception {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("field path is required");
        }
        String[] segments = path.split("\\.");
        Object owner = root;
        for (int index = 0; index < segments.length - 1; index++) {
            Field field = findField(owner.getClass(), segments[index]);
            field.setAccessible(true);
            owner = field.get(owner);
            if (owner == null) {
                throw new IllegalStateException("Null service field segment: " + segments[index]);
            }
        }
        Field target = findField(owner.getClass(), segments[segments.length - 1]);
        target.setAccessible(true);
        if (target.get(owner) != null) {
            throw new IllegalStateException("Synthetic binding refuses non-null field: " + path);
        }
        if (!target.getType().isInterface()) {
            throw new IllegalStateException("Synthetic binding requires interface field: " + path);
        }
        Object proxy = createSyntheticProxy(target.getType(), identity, serviceName);
        target.set(owner, proxy);
        return new ReflectiveServiceHook(owner, target, null, proxy);
    }

    static Object createProxy(Object original, GuestIdentity identity) {
        return createProxy(original, identity, "");
    }

    static Object createProxy(Object original, GuestIdentity identity, String serviceName) {
        if (original == null) throw new IllegalStateException("Framework service is null");
        Set<Class<?>> interfaces = new LinkedHashSet<>();
        Class<?> cursor = original.getClass();
        while (cursor != null) {
            for (Class<?> iface : cursor.getInterfaces()) interfaces.add(iface);
            cursor = cursor.getSuperclass();
        }
        if (interfaces.isEmpty()) throw new IllegalStateException("Framework service exposes no interfaces");
        ClassLoader loader = original.getClass().getClassLoader();
        if (loader == null) loader = ReflectiveServiceHook.class.getClassLoader();
        return Proxy.newProxyInstance(loader, interfaces.toArray(new Class<?>[0]),
                new SystemServiceInvocationHandler(original, identity, serviceName));
    }

    static boolean isServiceProxy(Object value, String serviceName) {
        if (value == null || !Proxy.isProxyClass(value.getClass())) return false;
        InvocationHandler handler = Proxy.getInvocationHandler(value);
        return handler instanceof SystemServiceInvocationHandler system
                && system.serviceName().equalsIgnoreCase(serviceName == null ? "" : serviceName);
    }

    private static Object createSyntheticProxy(Class<?> serviceType, GuestIdentity identity,
                                               String serviceName) {
        ClassLoader loader = serviceType.getClassLoader();
        if (loader == null) loader = ReflectiveServiceHook.class.getClassLoader();
        return Proxy.newProxyInstance(loader, new Class<?>[] {serviceType},
                new SystemServiceInvocationHandler(null, identity, serviceName));
    }

    private static Field resolvePath(Object root, String path) throws Exception {
        String[] segments = path.split("\\.");
        Object owner = root;
        for (int index = 0; index < segments.length - 1; index++) {
            Field field = findField(owner.getClass(), segments[index]);
            field.setAccessible(true);
            owner = field.get(owner);
            if (owner == null) throw new IllegalStateException(
                    "Null manager cache field segment: " + segments[index]);
        }
        Field target = findField(owner.getClass(), segments[segments.length - 1]);
        target.setAccessible(true);
        return target;
    }

    private static Object targetOwner(Object root, String path) throws Exception {
        String[] segments = path.split("\\.");
        Object owner = root;
        for (int index = 0; index < segments.length - 1; index++) {
            Field field = findField(owner.getClass(), segments[index]);
            field.setAccessible(true);
            owner = field.get(owner);
            if (owner == null) throw new IllegalStateException(
                    "Null manager cache field segment: " + segments[index]);
        }
        return owner;
    }

    private static Object emptyCache(Object original, Class<?> type) throws Exception {
        if (original == null) return null;
        if (type.isArray()) return Array.newInstance(type.getComponentType(), 0);
        try {
            java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (NoSuchMethodException ignored) {
            if (java.util.Collection.class.isAssignableFrom(type)) return new ArrayList<>();
            throw new IllegalStateException("No empty cache constructor: " + type.getName());
        }
    }

    private static ReflectiveServiceHook replace(Object owner, Field field, Object original,
                                                 GuestIdentity identity, String serviceName) throws Exception {
        return replace(owner, field, original, identity, serviceName, "");
    }

    private static ReflectiveServiceHook replace(Object owner, Field field, Object original,
                                                 GuestIdentity identity, String serviceName,
                                                 String expectedDescriptor) throws Exception {
        if (expectedDescriptor != null && !expectedDescriptor.isEmpty()) {
            validateServiceDescriptor(original, expectedDescriptor, serviceName);
        }
        if (isServiceProxy(original, serviceName)) return new ReflectiveServiceHook();
        Object proxy = createProxy(original, identity, serviceName);
        field.set(owner, proxy);
        return new ReflectiveServiceHook(owner, field, original, proxy);
    }

    private static void validateServiceDescriptor(Object service, String expectedDescriptor,
                                                  String serviceName) throws Exception {
        if (service == null) {
            throw new IllegalStateException("Framework service is null: " + serviceName);
        }
        Object binder = service instanceof android.os.IBinder
                ? service
                : findPublicMethod(service.getClass(), "asBinder").invoke(service);
        if (!(binder instanceof android.os.IBinder)) {
            throw new IllegalStateException("Framework service has no Binder: " + serviceName);
        }
        validateBinderDescriptor((android.os.IBinder) binder, expectedDescriptor, serviceName);
    }

    private static void validateBinderDescriptor(android.os.IBinder binder, String expectedDescriptor,
                                                String serviceName) throws Exception {
        Method descriptorMethod = findPublicMethod(binder.getClass(), "getInterfaceDescriptor");
        descriptorMethod.setAccessible(true);
        Object value = descriptorMethod.invoke(binder);
        String actualDescriptor = value == null ? "" : String.valueOf(value);
        if (!expectedDescriptor.equals(actualDescriptor)) {
            throw new IllegalStateException("Unexpected Binder descriptor for " + serviceName
                    + ": " + actualDescriptor + " expected=" + expectedDescriptor);
        }
    }

    private static Method findPublicMethod(Class<?> type, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        return type.getMethod(name, parameterTypes);
    }

    private static Method findAsInterface(Class<?> stub) throws NoSuchMethodException {
        for (Method method : stub.getDeclaredMethods()) {
            if (method.getName().equals("asInterface") && method.getParameterCount() == 1) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new NoSuchMethodException(stub.getName() + ".asInterface");
    }

    private static final class ServiceManagerBinding implements AutoCloseable {
        private final Map<String, Object> cache;
        private final String key;
        private final Object original;
        private final Object replacement;

        private ServiceManagerBinding(Map<String, Object> cache, String key,
                                      Object original, Object replacement) {
            this.cache = cache;
            this.key = key;
            this.original = original;
            this.replacement = replacement;
        }

        @Override public void close() {
            synchronized (cache) {
                if (cache.get(key) != replacement) return;
                if (original == null) cache.remove(key);
                else cache.put(key, original);
            }
        }
    }

    private static final class ServiceManagerBinderInvocationHandler
            implements InvocationHandler {
        private final android.os.IBinder binder;
        private final String descriptor;
        private final String logicalServiceName;
        private final Object serviceProxy;

        private ServiceManagerBinderInvocationHandler(android.os.IBinder binder,
                String descriptor, String logicalServiceName, Object serviceProxy) {
            this.binder = binder;
            this.descriptor = descriptor;
            this.logicalServiceName = logicalServiceName;
            this.serviceProxy = serviceProxy;
        }

        private boolean matches(String expectedDescriptor, String expectedService) {
            return descriptor.equals(expectedDescriptor)
                    && logicalServiceName.equals(expectedService);
        }

        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("queryLocalInterface".equals(method.getName()) && args != null
                    && args.length == 1 && descriptor.equals(args[0])) return serviceProxy;
            try { return method.invoke(binder, args); }
            catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
        }
    }

    private static final class CompositeHook implements AutoCloseable {
        private final AutoCloseable first;
        private final AutoCloseable second;

        private CompositeHook(AutoCloseable first, AutoCloseable second) {
            this.first = first;
            this.second = second;
        }

        private CompositeHook(List<? extends AutoCloseable> hooks) {
            this.first = hooks.isEmpty() ? () -> { } : hooks.get(0);
            AutoCloseable remainder = () -> { };
            for (int index = hooks.size() - 1; index >= 1; index--) {
                AutoCloseable current = hooks.get(index);
                AutoCloseable previous = remainder;
                remainder = () -> {
                    Exception failure = null;
                    try { current.close(); } catch (Exception error) { failure = error; }
                    try { previous.close(); } catch (Exception error) {
                        if (failure == null) failure = error; else failure.addSuppressed(error);
                    }
                    if (failure != null) throw failure;
                };
            }
            this.second = remainder;
        }

        @Override public void close() throws Exception {
            Exception failure = null;
            try { second.close(); } catch (Exception error) { failure = error; }
            try { first.close(); } catch (Exception error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
            if (failure != null) throw failure;
        }
    }

    @Override public void close() {
        if (field == null || fieldOwner == null) return;
        try {
            if (field.get(fieldOwner) == proxy) field.set(fieldOwner, original);
        } catch (Throwable ignored) { }
    }

    private static Object readCurrent(Object owner, Field field) {
        if (owner == null || field == null) return null;
        try { return field.get(owner); } catch (Throwable ignored) { return null; }
    }

    static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        HiddenApiAccess.ensureExemptions();
        Class<?> cursor = type;
        while (cursor != null) {
            try { return cursor.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { cursor = cursor.getSuperclass(); }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }
}
