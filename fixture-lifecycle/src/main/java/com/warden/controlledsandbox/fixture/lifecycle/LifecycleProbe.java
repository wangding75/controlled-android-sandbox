package com.warden.controlledsandbox.fixture.lifecycle;

import java.util.ArrayList;
import java.util.List;

/** Process-local assertion state; each cold launch receives a new Android process state. */
final class LifecycleProbe {
    private static final List<String> EVENTS = new ArrayList<>();

    private LifecycleProbe() { }

    static synchronized void record(String event) { EVENTS.add(event); }

    static synchronized String bootstrapMarker() {
        int factory = count("factory.construct");
        int factoryApplication = count("factory.application");
        int attach = count("application.attach");
        int provider = count("provider.onCreate");
        int onCreate = count("application.onCreate");
        boolean ordered = before("application.attach", "provider.onCreate")
                && before("provider.onCreate", "application.onCreate");
        // The real framework-owned LoadedApk may instantiate its own factory object in addition
        // to CAS's cached factory.  The observable bootstrap contract is that it creates only
        // one Application through the factory, not that the two framework domains share an
        // object identity.
        String state = factory >= 1 && factoryApplication == 1 && attach == 1 && provider == 1
                && onCreate == 1 && ordered
                ? "P1_04_BOOTSTRAP_PASS" : "P1_04_BOOTSTRAP_FAIL";
        return state + " factoryApplication=" + factoryApplication + " applicationAttach=" + attach
                + " provider=" + provider + " applicationOnCreate=" + onCreate
                + " ordered=" + ordered + " factoryConstruct=" + factory
                + " events=" + String.join(",", EVENTS);
    }

    private static int count(String value) {
        int total = 0;
        for (String event : EVENTS) if (value.equals(event)) total++;
        return total;
    }

    private static boolean before(String first, String second) {
        return EVENTS.indexOf(first) >= 0 && EVENTS.indexOf(second) >= 0
                && EVENTS.indexOf(first) < EVENTS.indexOf(second);
    }
}
