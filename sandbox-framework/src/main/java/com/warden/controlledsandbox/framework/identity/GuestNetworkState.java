package com.warden.controlledsandbox.framework.identity;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Process-local ownership for virtual connectivity callbacks and VPN sessions. */
public final class GuestNetworkState implements AutoCloseable {
    private final Set<Object> callbacks = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Object> vpnSessions = Collections.newSetFromMap(new IdentityHashMap<>());

    public synchronized boolean reserveCallback(Object token, int maximum) {
        if (token == null) throw new IllegalArgumentException("network callback token is required");
        if (callbacks.contains(token)) return false;
        if (maximum < 1 || callbacks.size() >= maximum) {
            throw new IllegalStateException("VIRTUAL_NETWORK_CALLBACK_LIMIT_REACHED");
        }
        callbacks.add(token);
        return true;
    }
    public synchronized boolean releaseCallback(Object token) { return token != null && callbacks.remove(token); }
    public synchronized int callbackCount() { return callbacks.size(); }

    public synchronized boolean reserveVpnSession(Object token, int maximum) {
        if (token == null) throw new IllegalArgumentException("VPN session token is required");
        if (vpnSessions.contains(token)) return false;
        if (maximum < 1 || vpnSessions.size() >= maximum) {
            throw new IllegalStateException("VIRTUAL_VPN_SESSION_LIMIT_REACHED");
        }
        vpnSessions.add(token);
        return true;
    }
    public synchronized boolean releaseVpnSession(Object token) { return token != null && vpnSessions.remove(token); }
    public synchronized int vpnSessionCount() { return vpnSessions.size(); }
    public synchronized void clearVpnSessions() { vpnSessions.clear(); }

    @Override public synchronized void close() { callbacks.clear(); vpnSessions.clear(); }
}
