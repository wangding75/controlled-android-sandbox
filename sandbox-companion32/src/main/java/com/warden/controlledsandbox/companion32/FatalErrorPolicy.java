package com.warden.controlledsandbox.companion32;

/** Workspace transactions map recoverable failures but must not swallow JVM Errors. */
final class FatalErrorPolicy {
    private FatalErrorPolicy() { }

    static void rethrowIfFatal(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof Error error) throw error;
            Throwable next = current.getCause();
            if (next == current) return;
            current = next;
        }
    }
}
