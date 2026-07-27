package com.warden.controlledsandbox.framework.core;

import java.util.List;
import java.util.Objects;

public record ProxyInstallReport(
        String service,
        boolean installed,
        boolean alreadyInstalled,
        String ownerClass,
        String singletonField,
        String delegateClass,
        List<String> interfaces,
        List<String> matchedInboundSignatures,
        List<String> unsupportedProtectedSignatures,
        String failure) {

    public ProxyInstallReport {
        service = Objects.requireNonNull(service, "service");
        ownerClass = Objects.requireNonNull(ownerClass, "ownerClass");
        singletonField = Objects.requireNonNull(singletonField, "singletonField");
        delegateClass = delegateClass == null ? "" : delegateClass;
        interfaces = List.copyOf(interfaces == null ? List.of() : interfaces);
        matchedInboundSignatures = List.copyOf(
                matchedInboundSignatures == null ? List.of() : matchedInboundSignatures);
        unsupportedProtectedSignatures = List.copyOf(
                unsupportedProtectedSignatures == null ? List.of() : unsupportedProtectedSignatures);
        failure = failure == null ? "" : failure;
    }

    public boolean passed() {
        return (installed || alreadyInstalled) && unsupportedProtectedSignatures.isEmpty();
    }
}
