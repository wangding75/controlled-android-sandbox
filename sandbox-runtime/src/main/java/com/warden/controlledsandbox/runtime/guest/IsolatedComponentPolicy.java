package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata;

/** Prevents isolated components from silently running under an ordinary Guest host UID. */
final class IsolatedComponentPolicy {
    private IsolatedComponentPolicy() { }

    static void requireSupported(VirtualPackageMetadata metadata, String componentClass) {
        requireSupported(metadata, componentClass, false);
    }

    static void requireSupported(VirtualPackageMetadata metadata, String componentClass,
                                 boolean dedicatedIsolatedTransport) {
        if (metadata == null || componentClass == null || componentClass.trim().isEmpty()) return;
        if (metadata.isIsolatedComponent(componentClass) && !dedicatedIsolatedTransport) {
            throw new UnsupportedOperationException(
                    "ISOLATED_PROCESS_UNAVAILABLE: component requires a real Android isolated UID slot: "
                            + componentClass);
        }
    }
}
