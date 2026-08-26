package com.warden.controlledsandbox.runtime.guest;

import android.os.Bundle;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Collects Activity lifecycle evidence for one launch and closes the observation window. */
public final class GuestLaunchObservation {
    private final String activityToken;
    private final String componentClass;
    private final CountDownLatch done = new CountDownLatch(1);
    private boolean prepared = true;
    private boolean launcherResolved;
    private boolean classLoaded;
    private boolean instantiated;
    private boolean attached;
    private boolean created;
    private boolean resumed;
    private boolean windowEvidence;
    private boolean firstFrameDrawn;
    private boolean observed;
    private int fatalCount;
    private int anrCount;
    private boolean stubPresent = true;
    private boolean guestProcessPresent = true;
    private String failure = "";
    private final String requestId;
    private final String operationId;
    private final long acceptedAtElapsedMs;
    private final ArrayList<String> timeline = new ArrayList<>();
    /** ActivityThread may replace the launcher Activity with the app's real top Activity. */
    private final Map<String, Correlation> activityCorrelations = new HashMap<>();

    public GuestLaunchObservation(String activityToken, String componentClass) {
        this(activityToken, componentClass, "", "");
    }

    public GuestLaunchObservation(String activityToken, String componentClass,
                                  String requestId, String operationId) {
        this(activityToken, componentClass, requestId, operationId,
                android.os.SystemClock.elapsedRealtime());
    }

    public GuestLaunchObservation(String activityToken, String componentClass,
                                  String requestId, String operationId,
                                  long acceptedAtElapsedMs) {
        this.activityToken = activityToken == null ? "" : activityToken;
        this.componentClass = componentClass == null ? "" : componentClass.trim();
        this.launcherResolved = !this.componentClass.isEmpty();
        this.requestId = requestId == null ? "" : requestId.trim();
        this.operationId = operationId == null ? "" : operationId.trim();
        this.acceptedAtElapsedMs = acceptedAtElapsedMs > 0L
                ? acceptedAtElapsedMs : android.os.SystemClock.elapsedRealtime();
        timeline.add("REQUEST_ACCEPTED@" + this.acceptedAtElapsedMs);
        if (!this.activityToken.isEmpty()) {
            activityCorrelations.put(this.activityToken,
                    new Correlation(this.requestId, this.operationId));
        }
    }

    /**
     * Associates a child Activity route with this logical launch.  VA/NBB-style task ownership
     * allows an app launcher to synchronously replace its entry Activity; the launch gate must
     * observe the resulting top record rather than only the host trampoline's first token.
     */
    public synchronized void linkActivity(String token, String childRequestId,
                                           String childOperationId, String component) {
        String normalizedToken = token == null ? "" : token.trim();
        if (normalizedToken.isEmpty()) return;
        // An Activity token identifies one physical Activity instance.  A singleTop or
        // CLEAR_TOP|SINGLE_TOP delivery can issue a new route for that same instance, while
        // StubActivityBase continues to emit lifecycle events with the instance's original
        // request/operation identity.  Do not replace that identity with the delivery request;
        // doing so turns a valid onNewIntent/resume sequence into a false correlation failure.
        if (!activityCorrelations.containsKey(normalizedToken)) {
            activityCorrelations.put(normalizedToken, new Correlation(
                    childRequestId == null ? "" : childRequestId.trim(),
                    childOperationId == null ? "" : childOperationId.trim()));
        }
        String normalizedComponent = component == null ? "" : component.trim();
        timeline.add("ACTIVITY_LINK:" + (normalizedComponent.isEmpty()
                ? normalizedToken : normalizedComponent) + "@"
                + android.os.SystemClock.elapsedRealtime());
    }

    public synchronized boolean acceptsActivityToken(String token) {
        String normalizedToken = token == null ? "" : token.trim();
        return normalizedToken.isEmpty() || activityCorrelations.containsKey(normalizedToken);
    }

    public synchronized void onActivityEvent(Bundle request) {
        if (request == null) return;
        String event = request.getString(RuntimeKeys.ACTIVITY_EVENT, "");
        String eventActivityToken = request.getString(RuntimeKeys.ACTIVITY_TOKEN, "");
        String eventRequestId = request.getString(RuntimeKeys.REQUEST_ID, "");
        String eventOperationId = request.getString(RuntimeKeys.OPERATION_ID, "");
        Correlation expected = activityCorrelations.get(eventActivityToken);
        if (expected == null && !eventActivityToken.isEmpty()) {
            failure = "LAUNCH_ACTIVITY_TOKEN_UNEXPECTED";
        } else if (expected == null) {
            expected = new Correlation(requestId, operationId);
        }
        if ((!expected.requestId.isEmpty() && !expected.requestId.equals(eventRequestId))
                || (!expected.operationId.isEmpty() && !expected.operationId.equals(eventOperationId))) {
            failure = "LAUNCH_CORRELATION_MISMATCH";
        }
        String stage = "GUEST_READY".equals(event) ? "GUEST_READY"
                : "RESUMED".equals(event) ? "ACTIVITY_RESUMED"
                : "FIRST_FRAME_DRAWN".equals(event) ? "FIRST_FRAME_DRAWN"
                : "LIFECYCLE_" + event;
        timeline.add(stage + "@" + android.os.SystemClock.elapsedRealtime());
        if (request.getBoolean("windowAttached", false)
                || request.getBoolean("windowAddedMarker", false)
                || request.getBoolean("windowRegistered", false)
                // Framework-owned Activities can legitimately be paused before the first
                // DecorView is attached (for example an onCreate() that immediately launches a
                // child Activity). In that case the authoritative ActivityThread record and its
                // Window object are still present; require the stronger attached/registered
                // signal whenever it is available, but do not reject a valid invisible launch.
                || request.getBoolean("frameworkOwnedActivity", false)
                        && request.getBoolean("windowCreated", false)) {
            windowEvidence = true;
        }
        if ("CREATED".equals(event)) {
            classLoaded = true;
            instantiated = true;
            attached = true;
            created = true;
        } else if ("RESUMED".equals(event)) {
            classLoaded = true;
            instantiated = true;
            attached = true;
            created = true;
            resumed = true;
        } else if ("GUEST_READY".equals(event)) {
            classLoaded = true;
            instantiated = true;
        } else if ("NEW_INTENT".equals(event)) {
            // Reopening a task can deliver the launcher Intent to an already-created Activity;
            // that path has no CREATED callback. Preserve the lifecycle facts that the callback
            // itself proves, while RESUMED/FIRST_FRAME_DRAWN remain independently required.
            classLoaded = true;
            instantiated = true;
            attached = true;
            created = true;
            if (request.getBoolean("activityResumed", false)) resumed = true;
        } else if ("FIRST_FRAME_DRAWN".equals(event)) {
            firstFrameDrawn = true;
            windowEvidence = true;
        } else if ("FAILED".equals(event)) {
            fatalCount++;
            failure = request.getString(RuntimeKeys.ERROR_MESSAGE, event);
        }
        if (failedLocked() || passedLocked()) finishLocked();
    }

    public boolean await(long timeoutMs) throws InterruptedException {
        return done.await(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
    }

    public synchronized GuestLaunchEvidence close() {
        observed = true;
        finishLocked();
        return evidenceLocked();
    }

    public String activityToken() { return activityToken; }

    private boolean failedLocked() {
        return fatalCount > 0 || !failure.isEmpty();
    }

    private boolean passedLocked() {
        return launcherResolved && classLoaded && instantiated && attached && created
                && resumed && windowEvidence && fatalCount == 0 && anrCount == 0
                && firstFrameDrawn && failure.isEmpty();
    }

    private void finishLocked() {
        if (done.getCount() == 0) return;
        done.countDown();
    }

    private GuestLaunchEvidence evidenceLocked() {
        return new GuestLaunchEvidence(prepared, launcherResolved, classLoaded, instantiated,
                attached, created, resumed, windowEvidence, firstFrameDrawn, observed,
                fatalCount, anrCount, stubPresent, guestProcessPresent, failure, timeline);
    }

    public long acceptedAtElapsedMs() { return acceptedAtElapsedMs; }

    private static final class Correlation {
        final String requestId;
        final String operationId;

        Correlation(String requestId, String operationId) {
            this.requestId = requestId == null ? "" : requestId;
            this.operationId = operationId == null ? "" : operationId;
        }
    }
}
