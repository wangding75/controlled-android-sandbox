package com.warden.controlledsandbox.runtime.guest;

import android.os.Bundle;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

public final class GuestLaunchGateSelfTest {
    public static void main(String[] args) {
        require(!GuestLaunchGate.isLaunchPass(GuestLaunchGate.prepareStatus(true)),
                "PREPARE_PASS is not LAUNCH_PASS");
        require(GuestLaunchGate.LAUNCH_FAILED.equals(GuestLaunchGate.evaluate(
                        evidence(true, false, false, false, false, false, false, false, false, true, 0, 0, true, true, ""))),
                "prepared process without Activity is not LAUNCH_PASS");
        require(GuestLaunchGate.LAUNCH_FAILED.equals(GuestLaunchGate.evaluate(
                        evidence(true, true, false, false, false, false, false, false, false, true, 0, 0, true, true, ""))),
                "stub/process presence is not LAUNCH_PASS");
        require(GuestLaunchGate.LAUNCH_PENDING.equals(GuestLaunchGate.evaluate(
                        evidence(true, true, true, true, true, true, true, true, false, false, 0, 0, true, true, ""))),
                "observation window must complete");
        require(GuestLaunchGate.LAUNCH_PASS.equals(GuestLaunchGate.evaluate(
                        evidence(true, true, true, true, true, true, true, true, true, true, 0, 0, true, true, ""))),
                "full real Activity evidence is LAUNCH_PASS");
        require(GuestLaunchGate.LAUNCH_FAILED.equals(GuestLaunchGate.evaluate(
                        evidence(true, true, true, true, true, true, true, true, true, true, 1, 0, true, true, "fatal"))),
                "FATAL rejects LAUNCH_PASS");
        require(GuestLaunchGate.LAUNCH_FAILED.equals(GuestLaunchGate.evaluate(
                        evidence(true, true, true, true, true, true, true, true, true, true, 0, 1, true, true, ""))),
                "ANR rejects LAUNCH_PASS");

        Bundle created = new Bundle();
        created.putString(RuntimeKeys.ACTIVITY_EVENT, "CREATED");
        created.putBoolean("windowAttached", true);
        GuestLaunchObservation live = new GuestLaunchObservation("tok", "guest.Main");
        live.onActivityEvent(created);
        Bundle resumed = new Bundle();
        resumed.putString(RuntimeKeys.ACTIVITY_EVENT, "RESUMED");
        resumed.putBoolean("windowAttached", true);
        live.onActivityEvent(resumed);
        Bundle firstFrame = new Bundle();
        firstFrame.putString(RuntimeKeys.ACTIVITY_EVENT, "FIRST_FRAME_DRAWN");
        firstFrame.putBoolean("windowAttached", true);
        live.onActivityEvent(firstFrame);
        GuestLaunchEvidence pass = live.close();
        require(GuestLaunchGate.LAUNCH_PASS.equals(GuestLaunchGate.evaluate(pass)),
                "CREATED+RESUMED+window is LAUNCH_PASS");

        GuestLaunchObservation taskHandoff = new GuestLaunchObservation(
                "root-token", "guest.Launch", "root-request", "root-operation");
        Bundle rootReady = new Bundle();
        rootReady.putString(RuntimeKeys.ACTIVITY_EVENT, "GUEST_READY");
        rootReady.putString(RuntimeKeys.ACTIVITY_TOKEN, "root-token");
        rootReady.putString(RuntimeKeys.REQUEST_ID, "root-request");
        rootReady.putString(RuntimeKeys.OPERATION_ID, "root-operation");
        taskHandoff.onActivityEvent(rootReady);
        taskHandoff.linkActivity("child-token", "child-request", "child-operation",
                "guest.RealActivity");
        Bundle childCreated = new Bundle();
        childCreated.putString(RuntimeKeys.ACTIVITY_EVENT, "CREATED");
        childCreated.putString(RuntimeKeys.ACTIVITY_TOKEN, "child-token");
        childCreated.putString(RuntimeKeys.REQUEST_ID, "child-request");
        childCreated.putString(RuntimeKeys.OPERATION_ID, "child-operation");
        childCreated.putBoolean("windowAttached", true);
        taskHandoff.onActivityEvent(childCreated);
        Bundle childResumed = new Bundle(childCreated);
        childResumed.putString(RuntimeKeys.ACTIVITY_EVENT, "RESUMED");
        taskHandoff.onActivityEvent(childResumed);
        Bundle childFrame = new Bundle(childCreated);
        childFrame.putString(RuntimeKeys.ACTIVITY_EVENT, "FIRST_FRAME_DRAWN");
        taskHandoff.onActivityEvent(childFrame);
        require(GuestLaunchGate.LAUNCH_PASS.equals(GuestLaunchGate.evaluate(taskHandoff.close())),
                "same-task child lifecycle correlation is LAUNCH_PASS");

        GuestLaunchObservation delayedWindow = new GuestLaunchObservation("tok", "guest.Main");
        Bundle createdOnly = new Bundle();
        createdOnly.putString(RuntimeKeys.ACTIVITY_EVENT, "CREATED");
        delayedWindow.onActivityEvent(createdOnly);
        Bundle resumedOnly = new Bundle();
        resumedOnly.putString(RuntimeKeys.ACTIVITY_EVENT, "RESUMED");
        delayedWindow.onActivityEvent(resumedOnly);
        Bundle window = new Bundle();
        window.putString(RuntimeKeys.ACTIVITY_EVENT, "WINDOW");
        window.putBoolean("windowAttached", true);
        delayedWindow.onActivityEvent(window);
        Bundle delayedFrame = new Bundle();
        delayedFrame.putString(RuntimeKeys.ACTIVITY_EVENT, "FIRST_FRAME_DRAWN");
        delayedFrame.putBoolean("windowAttached", true);
        delayedWindow.onActivityEvent(delayedFrame);
        require(GuestLaunchGate.LAUNCH_PASS.equals(GuestLaunchGate.evaluate(delayedWindow.close())),
                "window evidence may arrive after resume");

        GuestLaunchObservation failed = new GuestLaunchObservation("tok", "guest.Main");
        Bundle fail = new Bundle();
        fail.putString(RuntimeKeys.ACTIVITY_EVENT, "FAILED");
        fail.putString(RuntimeKeys.ERROR_MESSAGE, "IgSessionManager_not_initialized");
        failed.onActivityEvent(fail);
        require(GuestLaunchGate.LAUNCH_FAILED.equals(GuestLaunchGate.evaluate(failed.close())),
                "FAILED event is not LAUNCH_PASS");
        System.out.println("PASS guest launch acceptance gate self-test");
    }

    private static GuestLaunchEvidence evidence(boolean prepared, boolean launcher, boolean loaded,
                                                boolean instantiated, boolean attached, boolean created,
                                                boolean resumed, boolean window, boolean firstFrame,
                                                boolean observed,
                                                int fatal, int anr, boolean stub, boolean process,
                                                String failure) {
        return new GuestLaunchEvidence(prepared, launcher, loaded, instantiated, attached, created,
                resumed, window, firstFrame, observed, fatal, anr, stub, process, failure,
                new java.util.ArrayList<>());
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
