package com.warden.controlledsandbox.framework.routing;

/** Logical payload type stored behind a one-time broker route token. */
public enum RouteKind {
    ACTIVITY_INTENT,
    ACTIVITY_LAUNCH,
    NEW_INTENT,
    ACTIVITY_RESULT
}
