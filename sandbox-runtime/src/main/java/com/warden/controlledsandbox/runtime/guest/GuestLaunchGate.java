package com.warden.controlledsandbox.runtime.guest;

/**
 * Formal Guest launch acceptance. {@code GUEST_PREPARED} and {@code LAUNCH_REQUESTED}
 * are not {@code LAUNCH_PASS}.
 */
public final class GuestLaunchGate {
    public static final String PREPARE_PASS = "PREPARE_PASS";
    public static final String LAUNCH_PASS = "LAUNCH_PASS";
    /** Product launch was accepted; first-frame readiness is observed asynchronously. */
    public static final String LAUNCH_ACCEPTED = "LAUNCH_ACCEPTED";
    public static final String LAUNCH_FAILED = "LAUNCH_FAILED";
    public static final String LAUNCH_PENDING = "LAUNCH_PENDING";

    private GuestLaunchGate() { }

    public static String prepareStatus(boolean prepared) {
        return prepared ? PREPARE_PASS : LAUNCH_FAILED;
    }

    public static String evaluate(GuestLaunchEvidence evidence) {
        if (evidence == null || !evidence.prepared) return LAUNCH_FAILED;
        if (evidence.fatalCount > 0 || evidence.anrCount > 0) return LAUNCH_FAILED;
        if (!evidence.failure.isEmpty()) return LAUNCH_FAILED;
        if (!evidence.launcherResolved || !evidence.targetClassLoaded
                || !evidence.activityInstantiated || !evidence.activityAttached
                || !evidence.onCreateCompleted || !evidence.resumed
                || !evidence.windowEvidence || !evidence.firstFrameDrawn) {
            return evidence.observationCompleted ? LAUNCH_FAILED : LAUNCH_PENDING;
        }
        if (!evidence.observationCompleted) return LAUNCH_PENDING;
        return LAUNCH_PASS;
    }

    /**
     * Evaluates the non-visual launch contract used by framework probes.  A probe Activity can
     * complete all of its framework work from onCreate without ever owning a DecorView; that is
     * still a real Activity launch, but it is not a first-frame launch.
     */
    public static String evaluateActivityCreated(GuestLaunchEvidence evidence) {
        if (evidence == null || !evidence.prepared) return LAUNCH_FAILED;
        if (evidence.fatalCount > 0 || evidence.anrCount > 0) return LAUNCH_FAILED;
        if (!evidence.failure.isEmpty()) return LAUNCH_FAILED;
        if (!evidence.launcherResolved || !evidence.targetClassLoaded
                || !evidence.activityInstantiated || !evidence.activityAttached
                || !evidence.onCreateCompleted) {
            return evidence.observationCompleted ? LAUNCH_FAILED : LAUNCH_PENDING;
        }
        if (!evidence.observationCompleted) return LAUNCH_PENDING;
        return LAUNCH_PASS;
    }

    public static boolean isLaunchPass(String status) {
        return LAUNCH_PASS.equals(status);
    }

    public static boolean isLaunchAccepted(String status) {
        return LAUNCH_ACCEPTED.equals(status) || LAUNCH_PASS.equals(status);
    }
}
