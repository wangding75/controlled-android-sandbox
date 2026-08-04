package com.warden.controlledsandbox;

public final class PackageAuthorityRetryBackoffSelfTest {
    public static void main(String[] args) {
        PackageAuthorityRetryBackoff policy = new PackageAuthorityRetryBackoff(17);
        long previousBaseCeiling = 0L;
        for (int attempt = 1; attempt < PackageAuthorityRetryBackoff.CIRCUIT_THRESHOLD; attempt++) {
            long delay = policy.nextDelayMillis();
            require(delay >= PackageAuthorityRetryBackoff.INITIAL_DELAY_MS,
                    "retry delay remains bounded below");
            require(delay <= PackageAuthorityRetryBackoff.MAX_EXPONENTIAL_DELAY_MS
                            + (PackageAuthorityRetryBackoff.MAX_EXPONENTIAL_DELAY_MS / 8L),
                    "exponential retry remains bounded");
            require(delay >= previousBaseCeiling / 2L,
                    "retry delay does not collapse between attempts");
            previousBaseCeiling = delay;
        }
        long circuitDelay = policy.nextDelayMillis();
        require(policy.circuitOpen(), "circuit opens after repeated failures");
        require(circuitDelay >= PackageAuthorityRetryBackoff.CIRCUIT_DELAY_MS
                        - (PackageAuthorityRetryBackoff.CIRCUIT_DELAY_MS / 8L),
                "open circuit applies long retry delay");
        policy.reset();
        require(!policy.circuitOpen() && policy.consecutiveFailures() == 0,
                "successful connection resets retry state");
        require(policy.nextDelayMillis() < 1_000L,
                "retry restarts at the initial window after reset");
        System.out.println("PASS Package Authority bounded bootstrap retry self-test");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
