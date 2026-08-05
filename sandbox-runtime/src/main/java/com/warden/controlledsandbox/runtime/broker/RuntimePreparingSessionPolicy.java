package com.warden.controlledsandbox.runtime.broker;

import android.os.Bundle;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.session.SessionState;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

/** Exact identity policy for calls made while a Guest generation is preparing. */
final class RuntimePreparingSessionPolicy {
    private RuntimePreparingSessionPolicy() { }

    static boolean isOperational(GuestSession session, Bundle request, boolean callerIdentity) {
        if (session == null) return false;
        if (session.state() == SessionState.READY || session.state() == SessionState.ACTIVE) {
            return true;
        }
        if (session.state() != SessionState.PREPARING || request == null) return false;
        String sessionKey = callerIdentity ? RuntimeKeys.CALLER_SESSION_ID : RuntimeKeys.SESSION_ID;
        String generationKey = callerIdentity ? RuntimeKeys.CALLER_GENERATION : RuntimeKeys.GENERATION;
        if (!session.sessionId().equals(request.getString(sessionKey, ""))
                || session.generation() != request.getLong(generationKey, -1L)) {
            return false;
        }
        String revision = request.getString(RuntimeKeys.PACKAGE_REVISION, "");
        return revision.isEmpty() || session.packageRevision().equals(revision);
    }
}
