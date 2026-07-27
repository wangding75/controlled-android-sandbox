package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata;

/** Prevents an isolatedProcess component from silently running under an ordinary Guest host UID. */
public final class IsolatedComponentPolicy {
    private IsolatedComponentPolicy() { }

    static void requireSupported(VirtualPackageMetadata metadata, String componentClass) {
        if (componentClass == null || componentClass.trim().isEmpty()) return;
        VirtualPackageMetadata.Component component = metadata.component(componentClass);
        if (component != null && component.isolated()) {
            throw new UnsupportedOperationException(
                    "ISOLATED_PROCESS_UNAVAILABLE: component requires a real Android isolated UID slot: "
                            + component.className());
        }
    }
}
