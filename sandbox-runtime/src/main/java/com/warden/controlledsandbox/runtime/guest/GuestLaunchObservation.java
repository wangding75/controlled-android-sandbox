package com.warden.controlledsandbox.runtime.guest;

import android.os.Bundle;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.util.ArrayList;
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
    }

    public synchronized void onActivityEvent(Bundle request) {
        if (request == null) return;
        String event = request.getString(RuntimeKeys.ACTIVITY_EVENT, "");
        String eventRequestId = request.getString(RuntimeKeys.REQUEST_ID, "");
        String eventOperationId = request.getString(RuntimeKeys.OPERATION_ID, "");
        if ((!requestId.isEmpty() && !requestId.equals(eventRequestId))
                || (!operationId.isEmpty() && !operationId.equals(eventOperationId))) {
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
}
