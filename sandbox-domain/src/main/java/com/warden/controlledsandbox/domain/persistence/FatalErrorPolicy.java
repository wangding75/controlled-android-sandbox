package com.warden.controlledsandbox.domain.persistence;

/** Persistence recovery may handle corrupt data and I/O failures, never JVM Errors. */
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
