package com.warden.controlledsandbox.framework.identity;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;

public record MethodIdentityPolicy(
        String methodName,
        int argumentCount,
        List<ArgumentRewriteRule> rules) {

    public MethodIdentityPolicy {
        methodName = Objects.requireNonNull(methodName, "methodName");
        if (methodName.isBlank()) {
            throw new IllegalArgumentException("methodName must not be blank");
        }
        if (argumentCount < 0) {
            throw new IllegalArgumentException("argumentCount must be >= 0");
        }
        rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        for (ArgumentRewriteRule rule : rules) {
            if (rule.index() >= argumentCount) {
                throw new IllegalArgumentException(
                        "rule index " + rule.index() + " outside argument count " + argumentCount);
            }
        }
    }

    public boolean matches(Method method) {
        if (!methodName.equals(method.getName()) || argumentCount != method.getParameterCount()) {
            return false;
        }
        Class<?>[] parameterTypes = method.getParameterTypes();
        for (ArgumentRewriteRule rule : rules) {
            if (!rule.acceptsParameterType(parameterTypes[rule.index()])) {
                return false;
            }
        }
        return true;
    }

    public static MethodIdentityPolicy of(
            String methodName,
            int argumentCount,
            ArgumentRewriteRule... rules) {
        return new MethodIdentityPolicy(methodName, argumentCount, List.of(rules));
    }
}
