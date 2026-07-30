package com.warden.controlledsandbox.runtime.status;

import com.warden.controlledsandbox.domain.port.SessionMetricsRepository;

/** Read-only aggregate of ordinary Guest slots and dedicated isolated-process slots. */
public final class CombinedSessionMetricsRepository implements SessionMetricsRepository {
    private final SessionMetricsRepository ordinary;
    private final SessionMetricsRepository isolated;

    public CombinedSessionMetricsRepository(SessionMetricsRepository ordinary,
                                            SessionMetricsRepository isolated) {
        if (ordinary == null || isolated == null) {
            throw new IllegalArgumentException("session metric repositories are required");
        }
        this.ordinary = ordinary;
        this.isolated = isolated;
    }

    @Override public int capacity() { return Math.addExact(ordinary.capacity(), isolated.capacity()); }
    @Override public int used() { return Math.addExact(ordinary.used(), isolated.used()); }
    @Override public int count() { return Math.addExact(ordinary.count(), isolated.count()); }
}
