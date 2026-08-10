package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.framework.identity.IdentityContext;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class FrameworkProxyInstaller {
    public InstallOutcome install(
            FrameworkServiceSpec spec,
            IdentityContext context,
            ProxyTelemetry telemetry) {
        return install(spec, context, telemetry, FrameworkCallInterceptor.NO_OP);
    }

    public InstallOutcome install(
            FrameworkServiceSpec spec,
            IdentityContext context,
            ProxyTelemetry telemetry,
            FrameworkCallInterceptor callInterceptor) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(context, "context");
        try {
            Class<?> owner = Class.forName(spec.ownerClassName());
            Field singletonField = findField(owner, spec.singletonFieldName());
            singletonField.setAccessible(true);
            Object singleton = singletonField.get(null);
            if (singleton == null) {
                return failure(spec, "Framework singleton is null", "");
            }

            Field instanceField = findField(singleton.getClass(), "mInstance");
            instanceField.setAccessible(true);
            Object delegate = instanceField.get(singleton);
            if (delegate == null) {
                Method get = findNoArgMethod(singleton.getClass(), "get");
                get.setAccessible(true);
                delegate = get.invoke(singleton);
            }
            if (delegate == null) {
                return failure(spec, "Framework delegate is null after singleton get()", "");
            }

            if (Proxy.isProxyClass(delegate.getClass())) {
                InvocationHandler handler = Proxy.getInvocationHandler(delegate);
                if (handler instanceof FrameworkIdentityInvocationHandler existing
                        && existing.serviceName().equals(spec.serviceName())) {
                    ProxyInstallReport report = report(
                            spec, false, true, existing.delegate().getClass(),
                            immutableList(collectInterfaces(existing.delegate().getClass())), "");
                    return new InstallOutcome(report, null);
                }
            }

            validateBinderDescriptor(delegate, spec.expectedDescriptor(), spec.serviceName());

            // Collection.toArray(IntFunction) was added in Android API 33. The array overload is
            // API 1 and has the same component-type, null, order, and duplicate semantics here.
            Class<?>[] interfaces = collectInterfaces(delegate.getClass()).toArray(new Class<?>[0]);
            if (interfaces.length == 0) {
                return failure(spec, "Delegate exposes no interfaces for dynamic proxy", delegate.getClass().getName());
            }
            List<Class<?>> interfaceList = immutableList(Arrays.asList(interfaces));
            FrameworkSignatureAudit audit = FrameworkSignatureAudit.inspect(spec, interfaceList);
            if (!audit.passed()) {
                return failure(
                        spec,
                        "Unsupported protected framework signatures: "
                                + String.join(", ", audit.unsupportedProtectedSignatures()),
                        delegate.getClass().getName(),
                        interfaceList,
                        audit);
            }
            ClassLoader loader = chooseClassLoader(delegate.getClass(), interfaces);
            FrameworkIdentityInvocationHandler handler = new FrameworkIdentityInvocationHandler(
                    spec, delegate, context, telemetry, callInterceptor);
            Object proxy = Proxy.newProxyInstance(loader, interfaces, handler);
            instanceField.set(singleton, proxy);

            InstalledFrameworkProxy installed = new InstalledFrameworkProxy(
                    spec, singleton, instanceField, delegate, proxy);
            ProxyInstallReport report = report(
                    spec, true, false, delegate.getClass(), interfaceList, "");
            return new InstallOutcome(report, installed);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return failure(spec, exception.toString(), "");
        }
    }

    private static InstallOutcome failure(
            FrameworkServiceSpec spec,
            String failure,
            String delegateClass) {
        return failure(
                spec, failure, delegateClass, Collections.emptyList(),
                new FrameworkSignatureAudit(Collections.emptyList(), Collections.emptyList()));
    }

    private static InstallOutcome failure(
            FrameworkServiceSpec spec,
            String failure,
            String delegateClass,
            List<Class<?>> interfaces,
            FrameworkSignatureAudit audit) {
        ProxyInstallReport report = new ProxyInstallReport(
                spec.serviceName(), false, false, spec.ownerClassName(),
                spec.singletonFieldName(), delegateClass, interfaceNames(interfaces),
                audit.matchedInboundSignatures(), audit.unsupportedProtectedSignatures(), failure);
        return new InstallOutcome(report, null);
    }

    private static ProxyInstallReport report(
            FrameworkServiceSpec spec,
            boolean installed,
            boolean alreadyInstalled,
            Class<?> delegateClass,
            List<Class<?>> interfaces,
            String failure) {
        FrameworkSignatureAudit audit = FrameworkSignatureAudit.inspect(spec, interfaces);
        return new ProxyInstallReport(
                spec.serviceName(), installed, alreadyInstalled,
                spec.ownerClassName(), spec.singletonFieldName(),
                delegateClass.getName(), interfaceNames(interfaces),
                audit.matchedInboundSignatures(), audit.unsupportedProtectedSignatures(), failure);
    }

    private static List<String> interfaceNames(List<Class<?>> interfaces) {
        ArrayList<String> names = new ArrayList<>(interfaces.size());
        for (Class<?> type : interfaces) {
            names.add(type.getName());
        }
        Collections.sort(names);
        return Collections.unmodifiableList(names);
    }

    private static <T> List<T> immutableList(Iterable<? extends T> values) {
        ArrayList<T> copy = new ArrayList<>();
        for (T value : values) {
            copy.add(value);
        }
        return Collections.unmodifiableList(copy);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> cursor = type;
        while (cursor != null) {
            try {
                return cursor.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private static Method findNoArgMethod(Class<?> type, String name) throws NoSuchMethodException {
        Class<?> cursor = type;
        while (cursor != null) {
            try {
                return cursor.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name + "()");
    }

    private static Set<Class<?>> collectInterfaces(Class<?> type) {
        LinkedHashSet<Class<?>> interfaces = new LinkedHashSet<>();
        Class<?> cursor = type;
        while (cursor != null) {
            interfaces.addAll(Arrays.asList(cursor.getInterfaces()));
            cursor = cursor.getSuperclass();
        }
        ArrayList<Class<?>> inherited = new ArrayList<>(interfaces);
        for (Class<?> current : inherited) {
            collectParentInterfaces(current, interfaces);
        }
        return interfaces;
    }

    private static void collectParentInterfaces(Class<?> type, Set<Class<?>> sink) {
        for (Class<?> parent : type.getInterfaces()) {
            if (sink.add(parent)) {
                collectParentInterfaces(parent, sink);
            }
        }
    }

    private static ClassLoader chooseClassLoader(Class<?> delegateClass, Class<?>[] interfaces) {
        ClassLoader loader = delegateClass.getClassLoader();
        if (loader != null) {
            return loader;
        }
        for (Class<?> current : interfaces) {
            if (current.getClassLoader() != null) {
                return current.getClassLoader();
            }
        }
        return ClassLoader.getSystemClassLoader();
    }

    static void validateBinderDescriptorForTest(
            android.os.IBinder binder, String expectedDescriptor, String serviceName) {
        validateBinderDescriptor(binder, expectedDescriptor, serviceName);
    }

    private static void validateBinderDescriptor(
            Object delegate, String expectedDescriptor, String serviceName) {
        if (expectedDescriptor == null || expectedDescriptor.isEmpty()) return;
        Method asBinder;
        try {
            asBinder = delegate.getClass().getMethod("asBinder");
        } catch (NoSuchMethodException error) {
            // Host-side self-tests use plain interface doubles. Real platform AIDL delegates
            // implement IInterface, so an actual Binder-shaped delegate remains fail-closed.
            if (!(delegate instanceof android.os.IInterface)) return;
            throw new IllegalStateException("Framework delegate has no Binder contract: " + serviceName, error);
        }
        Object binder;
        try {
            binder = asBinder.invoke(delegate);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Framework delegate Binder lookup failed: " + serviceName, error);
        }
        if (!(binder instanceof android.os.IBinder)) {
            throw new IllegalStateException("Framework delegate returned no Binder: " + serviceName);
        }
        validateBinderDescriptor((android.os.IBinder) binder, expectedDescriptor, serviceName);
    }

    private static void validateBinderDescriptor(
            android.os.IBinder binder, String expectedDescriptor, String serviceName) {
        if (binder == null) throw new IllegalStateException("Framework Binder is null: " + serviceName);
        String actual;
        try {
            actual = binder.getInterfaceDescriptor();
        } catch (Throwable error) {
            throw new IllegalStateException("Framework Binder descriptor lookup failed: " + serviceName, error);
        }
        if (!expectedDescriptor.equals(actual)) {
            throw new IllegalStateException("Unexpected Binder descriptor for " + serviceName
                    + ": " + actual + " expected=" + expectedDescriptor);
        }
    }

    public record InstallOutcome(ProxyInstallReport report, InstalledFrameworkProxy installedProxy) {
        public InstallOutcome {
            Objects.requireNonNull(report, "report");
        }
    }
}
