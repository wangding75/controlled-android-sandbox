package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Set;

/** Guest-scoped lifecycle state shared by peripheral capability handlers. */
final class PeripheralInvocationState {
    private final GuestIdentity identity;
    final Set<Object> nfcReaders = identitySet();
    final Set<Object> usbDevices = identitySet();
    final Set<Object> printJobs = identitySet();
    final Set<Object> companionObservers = identitySet();
    final Set<String> companionAssociations = new LinkedHashSet<>();
    final Set<Object> projectionSessions = identitySet();
    final Set<Object> cameraSessions = identitySet();
    final Set<Object> cameraListeners = identitySet();
    final Set<Object> oemSessions = identitySet();
    int syntheticSequence;
    int tagOperations;
    boolean companionAssociationsInitialized;

    PeripheralInvocationState(GuestIdentity identity) {
        this.identity = java.util.Objects.requireNonNull(identity, "identity");
    }

    GuestIdentity identity() { return identity; }

    Object syntheticToken() { return new SyntheticToken(++syntheticSequence); }

    synchronized void removeCameraSession(Object session) { cameraSessions.remove(session); }

    synchronized void removeCameraListener(Object listener) { cameraListeners.remove(listener); }

    private static Set<Object> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private record SyntheticToken(int id) { }
}
