package com.warden.controlledsandbox.runtime.protocol;

/** Shared Runtime boundary policy: Errors are never converted into typed operation failures. */
public final class FatalErrorPolicy {
    private FatalErrorPolicy() { }

    public static void rethrowIfFatal(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof Error error) throw error;
            Throwable next = current.getCause();
            if (next == current) return;
            current = next;
        }
    }
}
