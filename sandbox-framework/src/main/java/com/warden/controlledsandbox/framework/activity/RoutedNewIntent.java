package com.warden.controlledsandbox.framework.activity;

import com.warden.controlledsandbox.framework.routing.RoutePayload;
import java.util.Objects;

/** One pending new-Intent callback paired with its consumed broker payload. */
public record RoutedNewIntent(
        NewIntentDelivery delivery,
        RoutePayload payload) {

    public RoutedNewIntent {
        delivery = Objects.requireNonNull(delivery, "delivery");
        payload = Objects.requireNonNull(payload, "payload");
    }
}
