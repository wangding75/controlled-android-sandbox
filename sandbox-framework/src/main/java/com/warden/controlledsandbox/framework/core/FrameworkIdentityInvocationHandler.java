package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.framework.binder.BinderIdentity;
import com.warden.controlledsandbox.framework.binder.BinderInterceptionFoundation;
import com.warden.controlledsandbox.framework.binder.BinderSessionFence;
import com.warden.controlledsandbox.framework.identity.IdentityArgumentRewriter;
import com.warden.controlledsandbox.framework.identity.IdentityContext;
import com.warden.controlledsandbox.framework.identity.IdentityRewriteException;
import com.warden.controlledsandbox.framework.identity.MethodIdentityPolicy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Objects;

public final class FrameworkIdentityInvocationHandler implements InvocationHandler {
    private final FrameworkServiceSpec spec;
    private final Object delegate;
    private final IdentityArgumentRewriter rewriter;
    private final ProxyTelemetry telemetry;
    private final FrameworkCallInterceptor callInterceptor;
    private final BinderSessionFence sessionFence;
    private volatile BinderInterceptionFoundation binderBoundary;

    FrameworkIdentityInvocationHandler(
            FrameworkServiceSpec spec,
            Object delegate,
            IdentityContext context,
            ProxyTelemetry telemetry,
            FrameworkCallInterceptor callInterceptor) {
        this(spec, delegate, context, telemetry, callInterceptor,
                BinderSessionFence.ALWAYS_ACTIVE);
    }

    FrameworkIdentityInvocationHandler(
            FrameworkServiceSpec spec,
            Object delegate,
            IdentityContext context,
            ProxyTelemetry telemetry,
            FrameworkCallInterceptor callInterceptor,
            BinderSessionFence sessionFence) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.rewriter = new IdentityArgumentRewriter(Objects.requireNonNull(context, "context"));
        this.telemetry = telemetry == null ? ProxyTelemetry.NO_OP : telemetry;
        this.callInterceptor = callInterceptor == null
                ? FrameworkCallInterceptor.NO_OP : callInterceptor;
        this.sessionFence = sessionFence == null
                ? BinderSessionFence.ALWAYS_ACTIVE : sessionFence;
    }

    /** Attaches the common Binder boundary after the typed framework proxy exists. */
    void attachBinderBoundary(Object localInterface) {
        if (binderBoundary != null || !(delegate instanceof android.os.IInterface typed)) return;
        android.os.IBinder binder;
        try {
            binder = typed.asBinder();
        } catch (Throwable error) {
            com.warden.controlledsandbox.framework.capability.FatalErrorPolicy.rethrowIfFatal(error);
            throw new IllegalStateException("FRAMEWORK_BINDER_LOOKUP_FAILED:" + spec.serviceName(),
                    error);
        }
        if (binder == null) {
            throw new IllegalStateException("FRAMEWORK_BINDER_UNAVAILABLE:" + spec.serviceName());
        }
        String expected = spec.expectedDescriptor();
        if (!expected.isEmpty()) {
            String actual;
            try {
                actual = binder.getInterfaceDescriptor();
            } catch (Throwable error) {
                com.warden.controlledsandbox.framework.capability.FatalErrorPolicy
                        .rethrowIfFatal(error);
                throw new IllegalStateException("FRAMEWORK_BINDER_DESCRIPTOR_LOOKUP_FAILED:"
                        + spec.serviceName(), error);
            }
            if (!expected.equals(actual)) {
                throw new IllegalStateException("Unexpected Binder descriptor for "
                        + spec.serviceName() + ": " + actual + " expected=" + expected);
            }
        }
        binderBoundary = BinderInterceptionFoundation.builder(
                binder, BinderIdentity.fromIdentityContext(rewriter.context()))
                .descriptor(expected)
                .serviceName(spec.serviceName())
                .localInterface(localInterface)
                .sessionFence(sessionFence)
                .preserveBinderType("android.app.IApplicationThread")
                .build();
    }

    void invalidateBinderBoundary(String reason) {
        BinderInterceptionFoundation boundary = binderBoundary;
        if (boundary != null) boundary.invalidate(reason);
    }

    Object delegate() {
        return delegate;
    }

    String serviceName() {
        return spec.serviceName();
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
        String methodName = method.getName();
        if (method.getDeclaringClass() == Object.class) {
            return invokeObjectMethod(proxy, method, arguments);
        }

        if ("asBinder".equals(methodName) && method.getParameterCount() == 0
                && binderBoundary != null) {
            return binderBoundary.binder();
        }

        FrameworkCallInterceptor.Interception interception =
                callInterceptor.intercept(spec.serviceName(), method, arguments);
        if (interception != null && interception.handled()) {
            safeRecord(ProxyEvent.now(
                    spec.serviceName(), methodName, "runtime-interceptor", true,
                    "framework call handled by runtime bridge"));
            return wrapBoundaryResult(interception.result(), method.getReturnType(),
                    methodName + ".interceptor.return");
        }

        MethodIdentityPolicy policy = spec.inboundPolicy(method).orElse(null);
        if (policy == null && spec.hasInboundMethodName(methodName)) {
            IdentityRewriteException exception = new IdentityRewriteException(
                    "Unsupported framework signature: " + spec.serviceName() + "."
                            + methodName + "/" + method.getParameterCount());
            safeRecord(ProxyEvent.now(
                    spec.serviceName(), methodName, "signature-policy", false,
                    exception.getMessage()));
            throw exception;
        }
        boolean inbound = policy != null;
        Object[] rewrittenArguments = inbound ? rewriter.rewriteInbound(arguments, policy) : arguments;
        if (inbound) {
            safeRecord(new ProxyEvent(
                    Instant.now(), spec.serviceName(), methodName,
                    "rewrite-inbound", true,
                    "signature policy matched argumentCount=" + method.getParameterCount()));
        }

        try {
            Object[] binderArguments = binderBoundary == null ? rewrittenArguments
                    : binderBoundary.wrapArguments(rewrittenArguments, method.getParameterTypes(),
                            spec.serviceName() + ".callback");
            Object result = method.invoke(delegate, binderArguments);
            if (rewrittenArguments != null) {
                for (Object argument : rewrittenArguments) {
                    rewriter.rewriteOutboundInPlace(argument);
                }
            }
            if (spec.outboundMethods().contains(methodName)) {
                Object rewritten = rewriter.rewriteOutbound(result);
                safeRecord(ProxyEvent.now(
                        spec.serviceName(), methodName, "rewrite-outbound", true,
                        "exact host identity projection evaluated"));
                return wrapBoundaryResult(rewritten, method.getReturnType(),
                        methodName + ".outbound.return");
            }
            return wrapBoundaryResult(result, method.getReturnType(), methodName + ".return");
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            safeRecord(ProxyEvent.now(
                    spec.serviceName(), methodName, "delegate-call", false,
                    cause == null ? exception.toString() : cause.toString()));
            throw cause == null ? exception : cause;
        } catch (RuntimeException exception) {
            safeRecord(ProxyEvent.now(
                    spec.serviceName(), methodName, "proxy-call", false, exception.toString()));
            throw exception;
        }
    }

    private Object invokeObjectMethod(Object proxy, Method method, Object[] arguments) {
        return switch (method.getName()) {
            case "toString" -> "ControlledSandboxProxy[" + spec.serviceName() + "]->" + delegate;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (arguments == null || arguments.length == 0 ? null : arguments[0]);
            default -> throw new IllegalStateException("Unexpected Object method: " + method);
        };
    }

    private void safeRecord(ProxyEvent event) {
        try {
            telemetry.record(event);
        } catch (RuntimeException ignored) {
            // Diagnostics must never destabilize framework calls.
        }
    }

    private Object wrapBoundaryResult(Object value, String role) {
        return wrapBoundaryResult(value, null, role);
    }

    private Object wrapBoundaryResult(Object value, Class<?> expectedType, String role) {
        return binderBoundary == null ? value
                : binderBoundary.wrapReturned(value, expectedType, role);
    }
}
