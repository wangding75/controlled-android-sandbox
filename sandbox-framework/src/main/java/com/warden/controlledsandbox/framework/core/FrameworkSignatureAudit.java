package com.warden.controlledsandbox.framework.core;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public record FrameworkSignatureAudit(
        List<String> matchedInboundSignatures,
        List<String> unsupportedProtectedSignatures) {

    public FrameworkSignatureAudit {
        matchedInboundSignatures = List.copyOf(matchedInboundSignatures);
        unsupportedProtectedSignatures = List.copyOf(unsupportedProtectedSignatures);
    }

    public boolean passed() {
        return unsupportedProtectedSignatures.isEmpty();
    }

    public static FrameworkSignatureAudit inspect(
            FrameworkServiceSpec spec,
            Collection<Class<?>> interfaces) {
        Set<String> matched = new TreeSet<>();
        Set<String> unsupported = new TreeSet<>();
        for (Class<?> contract : interfaces) {
            for (Method method : contract.getMethods()) {
                if (!spec.hasInboundMethodName(method.getName())) {
                    continue;
                }
                String signature = signature(method);
                if (spec.inboundPolicy(method).isPresent()) {
                    matched.add(signature);
                } else {
                    unsupported.add(signature);
                }
            }
        }
        return new FrameworkSignatureAudit(new ArrayList<>(matched), new ArrayList<>(unsupported));
    }

    private static String signature(Method method) {
        StringBuilder builder = new StringBuilder(method.getName()).append('(');
        Class<?>[] parameterTypes = method.getParameterTypes();
        for (int index = 0; index < parameterTypes.length; index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(parameterTypes[index].getTypeName());
        }
        return builder.append(')').toString();
    }
}
