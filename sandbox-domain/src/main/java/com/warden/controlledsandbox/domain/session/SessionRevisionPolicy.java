package com.warden.controlledsandbox.domain.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Selects live process sessions that must be stopped before another APK revision is loaded. */
public final class SessionRevisionPolicy {
    private SessionRevisionPolicy() { }

    public static List<GuestSession> mismatchedLiveSessions(
            List<GuestSession> sessions, String requestedRevision) {
        if (sessions == null) throw new IllegalArgumentException("sessions are required");
        if (requestedRevision == null || requestedRevision.trim().isEmpty()) {
            throw new IllegalArgumentException("requestedRevision is required");
        }
        List<GuestSession> out = new ArrayList<>();
        for (GuestSession session : sessions) {
            if (session == null) continue;
            if (session.state() == SessionState.STOPPED || session.state() == SessionState.FAILED) continue;
            if (!requestedRevision.equals(session.packageRevision())) out.add(session);
        }
        return Collections.unmodifiableList(out);
    }
}
