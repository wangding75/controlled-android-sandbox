package com.warden.controlledsandbox;

/** Bounded exponential retry policy for Package Authority bootstrap bindings. */
final class PackageAuthorityRetryBackoff {
    static final long INITIAL_DELAY_MS = 250L;
    static final long MAX_EXPONENTIAL_DELAY_MS = 30_000L;
    static final int CIRCUIT_THRESHOLD = 8;
    static final long CIRCUIT_DELAY_MS = 60_000L;

    private final int salt;
    private int consecutiveFailures;

    PackageAuthorityRetryBackoff(int salt) { this.salt = salt; }

    long nextDelayMillis() {
        int attempt = ++consecutiveFailures;
        long base;
        if (attempt >= CIRCUIT_THRESHOLD) {
            base = CIRCUIT_DELAY_MS;
        } else {
            int shift = Math.min(attempt - 1, 20);
            base = Math.min(MAX_EXPONENTIAL_DELAY_MS, INITIAL_DELAY_MS << shift);
        }
        long range = Math.max(1L, base / 8L);
        long mixed = Integer.toUnsignedLong((attempt * 1_103_515_245) ^ salt);
        long jitter = (mixed % ((range * 2L) + 1L)) - range;
        return Math.max(INITIAL_DELAY_MS, base + jitter);
    }

    void reset() { consecutiveFailures = 0; }
    int consecutiveFailures() { return consecutiveFailures; }
    boolean circuitOpen() { return consecutiveFailures >= CIRCUIT_THRESHOLD; }
}
