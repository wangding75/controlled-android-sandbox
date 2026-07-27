package com.warden.controlledsandbox.domain.routing;

/** One-time capability authorizing a component launch into a leased guest process. */
public final class RouteTicket {
    private final String token;
    private final String sessionId;
    private final long generation;
    private final int processSlot;
    private final String componentClass;
    private final long expiresAtMs;

    RouteTicket(String token, String sessionId, long generation, int processSlot,
                String componentClass, long expiresAtMs) {
        this.token = token;
        this.sessionId = sessionId;
        this.generation = generation;
        this.processSlot = processSlot;
        this.componentClass = componentClass;
        this.expiresAtMs = expiresAtMs;
    }

    public String token() { return token; }
    public String sessionId() { return sessionId; }
    public long generation() { return generation; }
    public int processSlot() { return processSlot; }
    public String componentClass() { return componentClass; }
    public long expiresAtMs() { return expiresAtMs; }
}
