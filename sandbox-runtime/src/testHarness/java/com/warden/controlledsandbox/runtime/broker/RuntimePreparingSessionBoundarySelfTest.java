package com.warden.controlledsandbox.runtime.broker;

import android.os.Bundle;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.session.SessionState;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

/** Regression evidence for tightly scoped runtime calls during Application.onCreate preparation. */
public final class RuntimePreparingSessionBoundarySelfTest {
    public static void main(String[] args) throws Exception {
        GuestSession preparing = new GuestSession("session-1", "guest.pkg", 2,
                "guest.pkg", "revision-1", 0, 7L, SessionState.PREPARING, 1L, "");
        Bundle target = request("session-1", 7L, "revision-1", false);
        require(RuntimePreparingSessionPolicy.isOperational(preparing, target, false),
                "exact PREPARING target generation is operational");

        Bundle caller = request("session-1", 7L, "revision-1", true);
        require(RuntimePreparingSessionPolicy.isOperational(preparing, caller, true),
                "exact PREPARING caller generation is operational");

        Bundle wrongGeneration = request("session-1", 8L, "revision-1", false);
        require(!RuntimePreparingSessionPolicy.isOperational(preparing, wrongGeneration, false),
                "different generation is rejected");

        Bundle wrongSession = request("session-2", 7L, "revision-1", false);
        require(!RuntimePreparingSessionPolicy.isOperational(preparing, wrongSession, false),
                "different session is rejected");

        Bundle wrongRevision = request("session-1", 7L, "revision-2", false);
        require(!RuntimePreparingSessionPolicy.isOperational(preparing, wrongRevision, false),
                "different package revision is rejected");

        GuestSession ready = new GuestSession("ready", "guest.pkg", 2,
                "guest.pkg", "revision-1", 0, 9L, SessionState.READY, 1L, "");
        require(RuntimePreparingSessionPolicy.isOperational(ready, new Bundle(), false),
                "READY session remains operational");
        System.out.println("PASS preparing-session exact identity boundary self-test");
    }

    private static Bundle request(String sessionId, long generation, String revision,
            boolean caller) {
        Bundle request = new Bundle();
        request.putString(caller ? RuntimeKeys.CALLER_SESSION_ID : RuntimeKeys.SESSION_ID,
                sessionId);
        request.putLong(caller ? RuntimeKeys.CALLER_GENERATION : RuntimeKeys.GENERATION,
                generation);
        request.putString(RuntimeKeys.PACKAGE_REVISION, revision);
        return request;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
