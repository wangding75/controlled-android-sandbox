package com.warden.controlledsandbox.domain.port;

/** Time source injected at stateful domain/runtime boundaries. */
@FunctionalInterface
public interface Clock {
    long nowMillis();
}
