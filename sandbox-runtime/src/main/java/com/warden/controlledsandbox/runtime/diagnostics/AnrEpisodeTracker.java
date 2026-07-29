package com.warden.controlledsandbox.runtime.diagnostics;

/** Android-independent ANR episode state machine used by the process watchdog. */
final class AnrEpisodeTracker {
    enum Transition { NONE, STARTED, CONTINUING, RECOVERED }

    static final class Sample {
        final Transition transition;
        final long episodeId;
        final long delayMs;
        final long maxDelayMs;
        final long sampleCount;
        final long durationMs;

        Sample(Transition transition, long episodeId, long delayMs,
               long maxDelayMs, long sampleCount, long durationMs) {
            this.transition = transition;
            this.episodeId = episodeId;
            this.delayMs = delayMs;
            this.maxDelayMs = maxDelayMs;
            this.sampleCount = sampleCount;
            this.durationMs = durationMs;
        }
    }

    private final long thresholdMs;
    private long nextEpisodeId = 1;
    private boolean active;
    private long episodeId;
    private long startedAtMs;
    private long maxDelayMs;
    private long sampleCount;

    AnrEpisodeTracker(long thresholdMs) {
        if (thresholdMs < 1) throw new IllegalArgumentException("thresholdMs must be positive");
        this.thresholdMs = thresholdMs;
    }

    synchronized Sample observe(long nowMs, long delayMs) {
        if (nowMs < 0 || delayMs < 0) throw new IllegalArgumentException("time values must be non-negative");
        if (delayMs >= thresholdMs) {
            if (!active) {
                active = true;
                episodeId = nextEpisodeId++;
                startedAtMs = nowMs;
                maxDelayMs = delayMs;
                sampleCount = 1;
                return sample(Transition.STARTED, nowMs, delayMs);
            }
            maxDelayMs = Math.max(maxDelayMs, delayMs);
            sampleCount++;
            return sample(Transition.CONTINUING, nowMs, delayMs);
        }
        if (!active) return new Sample(Transition.NONE, 0, delayMs, 0, 0, 0);
        Sample recovered = sample(Transition.RECOVERED, nowMs, delayMs);
        active = false;
        episodeId = 0;
        startedAtMs = 0;
        maxDelayMs = 0;
        sampleCount = 0;
        return recovered;
    }

    private Sample sample(Transition transition, long nowMs, long delayMs) {
        return new Sample(transition, episodeId, delayMs, maxDelayMs, sampleCount,
                Math.max(0, nowMs - startedAtMs));
    }
}
