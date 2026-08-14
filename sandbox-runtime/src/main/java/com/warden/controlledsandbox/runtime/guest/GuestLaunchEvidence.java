package com.warden.controlledsandbox.runtime.guest;

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
    public final boolean observationCompleted;
    public final int fatalCount;
    public final int anrCount;
    public final boolean stubPresent;
    public final boolean guestProcessPresent;
    public final String failure;

    public GuestLaunchEvidence(boolean prepared, boolean launcherResolved, boolean targetClassLoaded,
                               boolean activityInstantiated, boolean activityAttached,
                               boolean onCreateCompleted, boolean resumed, boolean windowEvidence,
                               boolean observationCompleted, int fatalCount, int anrCount,
                               boolean stubPresent, boolean guestProcessPresent, String failure) {
        this.prepared = prepared;
        this.launcherResolved = launcherResolved;
        this.targetClassLoaded = targetClassLoaded;
        this.activityInstantiated = activityInstantiated;
        this.activityAttached = activityAttached;
        this.onCreateCompleted = onCreateCompleted;
        this.resumed = resumed;
        this.windowEvidence = windowEvidence;
        this.observationCompleted = observationCompleted;
        this.fatalCount = Math.max(0, fatalCount);
        this.anrCount = Math.max(0, anrCount);
        this.stubPresent = stubPresent;
        this.guestProcessPresent = guestProcessPresent;
        this.failure = failure == null ? "" : failure;
    }
}
