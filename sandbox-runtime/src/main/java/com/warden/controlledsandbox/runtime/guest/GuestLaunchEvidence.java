package com.warden.controlledsandbox.runtime.guest;

import java.util.ArrayList;

/** Observable facts for Guest launch acceptance. Stub or process presence alone is not enough. */
public final class GuestLaunchEvidence {
    public final boolean prepared;
    public final boolean launcherResolved;
    public final boolean targetClassLoaded;
    public final boolean activityInstantiated;
    public final boolean activityAttached;
    public final boolean onCreateCompleted;
    public final boolean resumed;
    public final boolean windowEvidence;
    public final boolean firstFrameDrawn;
    public final boolean observationCompleted;
    public final int fatalCount;
    public final int anrCount;
    public final boolean stubPresent;
    public final boolean guestProcessPresent;
    public final String failure;
    public final ArrayList<String> timeline;

    public GuestLaunchEvidence(boolean prepared, boolean launcherResolved, boolean targetClassLoaded,
                               boolean activityInstantiated, boolean activityAttached,
                               boolean onCreateCompleted, boolean resumed, boolean windowEvidence,
                               boolean firstFrameDrawn,
                               boolean observationCompleted, int fatalCount, int anrCount,
                               boolean stubPresent, boolean guestProcessPresent, String failure,
                               ArrayList<String> timeline) {
        this.prepared = prepared;
        this.launcherResolved = launcherResolved;
        this.targetClassLoaded = targetClassLoaded;
        this.activityInstantiated = activityInstantiated;
        this.activityAttached = activityAttached;
        this.onCreateCompleted = onCreateCompleted;
        this.resumed = resumed;
        this.windowEvidence = windowEvidence;
        this.firstFrameDrawn = firstFrameDrawn;
        this.observationCompleted = observationCompleted;
        this.fatalCount = Math.max(0, fatalCount);
        this.anrCount = Math.max(0, anrCount);
        this.stubPresent = stubPresent;
        this.guestProcessPresent = guestProcessPresent;
        this.failure = failure == null ? "" : failure;
        this.timeline = timeline == null ? new ArrayList<>() : new ArrayList<>(timeline);
    }
}
