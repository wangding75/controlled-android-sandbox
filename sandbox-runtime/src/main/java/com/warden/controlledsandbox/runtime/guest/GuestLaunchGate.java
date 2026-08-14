package com.warden.controlledsandbox.runtime.guest;

/**
 * Formal Guest launch acceptance. {@code GUEST_PREPARED} and {@code LAUNCH_REQUESTED}
 * are not {@code LAUNCH_PASS}.
 */
public final class GuestLaunchGate {
    public static final String PREPARE_PASS = "PREPARE_PASS";
    public static final String LAUNCH_PASS = "LAUNCH_PASS";
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
                || !evidence.windowEvidence) {
            return evidence.observationCompleted ? LAUNCH_FAILED : LAUNCH_PENDING;
        }
        if (!evidence.observationCompleted) return LAUNCH_PENDING;
        return LAUNCH_PASS;
    }

    public static boolean isLaunchPass(String status) {
        return LAUNCH_PASS.equals(status);
    }
}
