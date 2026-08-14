package com.warden.controlledsandbox.framework.core;

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

    FrameworkIdentityInvocationHandler(
            FrameworkServiceSpec spec,
            Object delegate,
            IdentityContext context,
            ProxyTelemetry telemetry,
            FrameworkCallInterceptor callInterceptor) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.rewriter = new IdentityArgumentRewriter(Objects.requireNonNull(context, "context"));
        this.telemetry = telemetry == null ? ProxyTelemetry.NO_OP : telemetry;
        this.callInterceptor = callInterceptor == null
                ? FrameworkCallInterceptor.NO_OP : callInterceptor;
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

        FrameworkCallInterceptor.Interception interception =
                callInterceptor.intercept(spec.serviceName(), method, arguments);
        if (interception != null && interception.handled()) {
            safeRecord(ProxyEvent.now(
                    spec.serviceName(), methodName, "runtime-interceptor", true,
                    "framework call handled by runtime bridge"));
            return interception.result();
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
            Object result = method.invoke(delegate, rewrittenArguments);
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
                return rewritten;
            }
            return result;
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
}
