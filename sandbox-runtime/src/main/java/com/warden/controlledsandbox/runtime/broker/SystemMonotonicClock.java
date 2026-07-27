package com.warden.controlledsandbox.runtime.broker;

import android.os.SystemClock;
import com.warden.controlledsandbox.domain.port.Clock;

/** Android monotonic clock adapter; domain and use-case code depend only on Clock. */
public final class SystemMonotonicClock implements Clock {
    @Override public long nowMillis() { return SystemClock.elapsedRealtime(); }
}
