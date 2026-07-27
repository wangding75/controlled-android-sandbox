package com.warden.controlledsandbox.framework.activity;


import com.warden.controlledsandbox.framework.routing.RouteOwner;
import com.warden.controlledsandbox.framework.routing.RouteToken;
import java.util.Objects;

/** Evidence that route storage and virtual task mutation both completed. */
public record ActivityLaunchTransaction(
        LaunchDecision decision,
        RouteToken routeToken,
        RouteOwner routeOwner) {

    public ActivityLaunchTransaction {
        decision = Objects.requireNonNull(decision, "decision");
        routeToken = Objects.requireNonNull(routeToken, "routeToken");
        routeOwner = Objects.requireNonNull(routeOwner, "routeOwner");
        if (!decision.routeToken().equals(routeToken.value())) {
            throw new IllegalArgumentException("decision and route token must match");
        }
    }
}
