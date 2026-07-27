package com.warden.controlledsandbox.domain.routing;

import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.session.SessionState;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** One-time, expiring launch capabilities. Tokens are removed before being returned. */
public final class RouteTable {
    private static final long MAX_TTL_MS = 60_000;
    private final SecureRandom random;
    private final Map<String, RouteTicket> tickets = new HashMap<>();

    public RouteTable() { this(new SecureRandom()); }
    RouteTable(SecureRandom random) { this.random = random; }

    public synchronized RouteTicket issue(GuestSession session, String componentClass,
                                          long nowMs, long ttlMs) {
        if (session.state() != SessionState.READY && session.state() != SessionState.ACTIVE) {
            throw new IllegalStateException("Session is not launchable: " + session.state());
        }
        if (componentClass == null || componentClass.trim().isEmpty()) {
            throw new IllegalArgumentException("componentClass is required");
        }
        if (ttlMs < 1 || ttlMs > MAX_TTL_MS) throw new IllegalArgumentException("ttlMs out of range");
        purgeExpired(nowMs);
        byte[] bytes = new byte[24];
        String token;
        do {
            random.nextBytes(bytes);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } while (tickets.containsKey(token));
        RouteTicket ticket = new RouteTicket(token, session.sessionId(), session.generation(),
                session.processSlot(), componentClass, nowMs + ttlMs);
        tickets.put(token, ticket);
        return ticket;
    }

    public synchronized RouteTicket consume(String token, String expectedSessionId,
                                            long expectedGeneration, long nowMs) {
        RouteTicket ticket = tickets.remove(token);
        if (ticket == null) throw new IllegalStateException("ROUTE_NOT_FOUND_OR_REPLAYED");
        if (nowMs > ticket.expiresAtMs()) throw new IllegalStateException("ROUTE_EXPIRED");
        if (!ticket.sessionId().equals(expectedSessionId)) throw new SecurityException("ROUTE_SESSION_MISMATCH");
        if (ticket.generation() != expectedGeneration) throw new SecurityException("ROUTE_GENERATION_MISMATCH");
        return ticket;
    }

    public synchronized boolean revoke(String token) {
        if (token == null || token.trim().isEmpty()) return false;
        return tickets.remove(token) != null;
    }

    public synchronized int revokeSession(String sessionId, long generation) {
        int removed = 0;
        Iterator<RouteTicket> iterator = tickets.values().iterator();
        while (iterator.hasNext()) {
            RouteTicket ticket = iterator.next();
            if (ticket.sessionId().equals(sessionId) && ticket.generation() == generation) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    public synchronized int purgeExpired(long nowMs) {
        int removed = 0;
        Iterator<RouteTicket> iterator = tickets.values().iterator();
        while (iterator.hasNext()) {
            if (nowMs > iterator.next().expiresAtMs()) { iterator.remove(); removed++; }
        }
        return removed;
    }

    public synchronized int size() { return tickets.size(); }
}
