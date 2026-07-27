package com.warden.controlledsandbox.runtime.provider;

import com.warden.controlledsandbox.domain.component.provider.UriGrantRegistry;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Strict ContentProvider file mode allow-list shared by Broker and Guest runtimes. */
public final class ProviderFileModes {
    private static final Set<String> ALLOWED = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("r", "w", "wt", "wa", "rw", "rwt")));

    private ProviderFileModes() { }

    static String requireAllowed(String mode) {
        if (mode == null || !ALLOWED.contains(mode)) {
            throw new IllegalArgumentException("UNSUPPORTED_PROVIDER_FILE_MODE:" + String.valueOf(mode));
        }
        return mode;
    }

    static int flags(String mode) {
        String normalized = requireAllowed(mode);
        switch (normalized) {
            case "r":
                return UriGrantRegistry.READ;
            case "w":
            case "wt":
            case "wa":
                return UriGrantRegistry.WRITE;
            case "rw":
            case "rwt":
                return UriGrantRegistry.READ | UriGrantRegistry.WRITE;
            default:
                throw new IllegalStateException("Unhandled Provider file mode: " + normalized);
        }
    }
}
