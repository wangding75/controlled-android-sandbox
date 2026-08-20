package com.warden.controlledsandbox.runtime.broker;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import com.warden.controlledsandbox.framework.activity.LaunchFlags;
import com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata;
import com.warden.controlledsandbox.runtime.diagnostics.RuntimeEventLog;
import com.warden.controlledsandbox.runtime.guest.GuestLaunchEvidence;
import com.warden.controlledsandbox.runtime.guest.GuestLaunchGate;
import com.warden.controlledsandbox.runtime.guest.GuestLaunchObservation;
import com.warden.controlledsandbox.runtime.guest.GuestPackageMetadataMapper;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import com.warden.controlledsandbox.runtime.component.activity.BrokerActivityRuntime;
import com.warden.controlledsandbox.domain.session.GuestSession;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the Activity launch transaction after the Binder boundary.
 *
 * <p>The Broker service remains the authority and supplies the generation-fenced helpers.  This
 * class owns only the Activity-specific projection: virtual task decision, Stub allocation,
 * framework launch envelope and the asynchronous create/resume/window observation.  Keeping
 * that state machine outside the service prevents component-domain code from becoming another
 * implicit global router.</p>
 */
final class RuntimeActivityLaunchCoordinator {
    private static final long LAUNCH_OBSERVATION_MS = 35_000L;

    private final RuntimeBrokerService owner;

    RuntimeActivityLaunchCoordinator(RuntimeBrokerService owner) {
        this.owner = java.util.Objects.requireNonNull(owner, "owner");
    }

    Bundle launch(Bundle request) {
        owner.startService(new Intent(owner, RuntimeBrokerService.class));
        Bundle routedRequest = request == null ? new Bundle() : new Bundle(request);
        String callerPackage = routedRequest.getString(RuntimeKeys.PACKAGE_NAME, "");
            String targetPackage = RuntimeBrokerService.targetPackageForRequest(
                    routedRequest, callerPackage);
            String issuedRouteToken = "";
            try {
                if (!targetPackage.isEmpty() && !targetPackage.equals(callerPackage)) {
                    GuestSession caller = owner.callerSession(routedRequest, callerPackage);
                    if (owner.targetRequiresCompanion(targetPackage,
                            caller.virtualUserId())) {
                        return owner.routeForeignOperation(routedRequest, caller, targetPackage,
                                VirtualPackageMetadata.Type.ACTIVITY,
                                com.warden.controlledsandbox.contract.RuntimeOperationRequest
                                        .LAUNCH_ACTIVITY);
                    }
                    routedRequest = owner.prepareForeignTargetRequest(routedRequest, caller,
                            targetPackage, VirtualPackageMetadata.Type.ACTIVITY, true);
                }
            Bundle prepared = owner.prepareGuestInternal(routedRequest);
            if (!RuntimeBrokerService.isPrepared(prepared)) return prepared;
            String packageName = prepared.getString(RuntimeKeys.PACKAGE_NAME, "");
            int userId = prepared.getInt(RuntimeKeys.VIRTUAL_USER_ID, -1);
            String processName = RuntimeBrokerService.processName(prepared, packageName);
            GuestSession session = owner.findSession(prepared.getString(RuntimeKeys.SESSION_ID, ""),
                    prepared.getLong(RuntimeKeys.GENERATION, 0L));
            if (session != null && (!session.packageName().equals(packageName)
                    || session.virtualUserId() != userId
                    || !session.processName().equals(processName))) {
                return RuntimeBrokerService.failure("SESSION_IDENTITY_MISMATCH",
                        "Prepared session identity changed");
            }
            if (session == null) return RuntimeBrokerService.failure("SESSION_NOT_FOUND",
                    "Prepared session disappeared");
            String component = routedRequest.getString(RuntimeKeys.COMPONENT_CLASS, "");
            if (component.trim().isEmpty()) {
                component = prepared.getString(RuntimeKeys.COMPONENT_CLASS, "");
            }
            if (component.trim().isEmpty()) return RuntimeBrokerService.failure("COMPONENT_MISSING",
                    "No Guest Activity class supplied");

            BrokerActivityRuntime activityRuntime = owner.activityRuntime;
            Bundle transaction = activityRuntime.launch(session, component, prepared, routedRequest);
            issuedRouteToken = transaction.getString(RuntimeKeys.ROUTE_TOKEN, "");
            boolean frameworkHost = routedRequest.getBoolean(RuntimeKeys.ACTIVITY_FRAMEWORK_HOST, false);
            Intent launch = new Intent();
            launch.setComponent(new ComponentName(owner.getPackageName(),
                    RuntimeStubComponents.activityComponentFor(session.processSlot(), component,
                            RuntimeBrokerService.packageState(prepared),
                            transaction.getInt(RuntimeKeys.PHYSICAL_ACTIVITY_WINDOW, 0))));
            int hostFlags = hostActivityLaunchFlags(transaction, frameworkHost);
            launch.addFlags(hostFlags);
            launch.putExtra(RuntimeKeys.ROUTE_TOKEN, issuedRouteToken);
            launch.putExtra(RuntimeKeys.SESSION_ID, session.sessionId());
            launch.putExtra(RuntimeKeys.GENERATION, session.generation());
            launch.putExtra(RuntimeKeys.ACTIVITY_TOKEN,
                    transaction.getString(RuntimeKeys.ACTIVITY_TOKEN, ""));
            for (String key : new String[] {
                    RuntimeKeys.COMPONENT_CLASS, RuntimeKeys.TASK_ID,
                    RuntimeKeys.PHYSICAL_ACTIVITY_COMPONENT,
                    RuntimeKeys.ACTIVITY_LAUNCH_MODE,
                    RuntimeKeys.TARGET_PACKAGE_NAME, RuntimeKeys.INTENT_COMPONENT_PACKAGE,
                    RuntimeKeys.INTENT_COMPONENT_CLASS, RuntimeKeys.ACTIVITY_ACTION,
                    RuntimeKeys.ACTIVITY_FLAGS, RuntimeKeys.URI, RuntimeKeys.BROADCAST_SCHEME,
                    RuntimeKeys.BROADCAST_HOST, RuntimeKeys.BROADCAST_PORT, RuntimeKeys.BROADCAST_PATH,
                    RuntimeKeys.BROADCAST_MIME_TYPE, RuntimeKeys.BROADCAST_CATEGORIES}) {
                copyActivityFrameworkField(launch, transaction, key);
            }
            if (transaction.containsKey(RuntimeKeys.INTENT_EXTRAS)) {
                Bundle extras = transaction.getBundle(RuntimeKeys.INTENT_EXTRAS);
                if (extras != null) launch.putExtra(RuntimeKeys.INTENT_EXTRAS, new Bundle(extras));
            }
            Bundle physicalEvidence = new Bundle(transaction);
            physicalEvidence.putString(RuntimeKeys.PACKAGE_NAME, session.packageName());
            physicalEvidence.putInt(RuntimeKeys.VIRTUAL_USER_ID, session.virtualUserId());
            physicalEvidence.putString(RuntimeKeys.PROCESS_NAME, session.processName());
            physicalEvidence.putString(RuntimeKeys.COMPONENT_CLASS, component);
            physicalEvidence.putString(RuntimeKeys.PHYSICAL_ACTIVITY_COMPONENT,
                    launch.getComponent() == null ? "" : launch.getComponent().getClassName());
            physicalEvidence.putInt(RuntimeKeys.HOST_ACTIVITY_FLAGS, hostFlags);
            physicalEvidence.putBoolean(RuntimeKeys.ACTIVITY_FRAMEWORK_HOST, frameworkHost);
            RuntimeEventLog.event("ATMS_ACTIVITY_LAUNCH_REQUEST", physicalEvidence);
            if (frameworkHost) {
                Bundle out = owner.sessionBundle(session, GuestLaunchGate.LAUNCH_PENDING);
                out.putAll(transaction);
                out.putBoolean("launcherResolved", true);
                out.putString(RuntimeKeys.HOST_ACTIVITY_CLASS,
                        launch.getComponent() == null ? "" : launch.getComponent().getClassName());
                out.putInt(RuntimeKeys.HOST_ACTIVITY_FLAGS, hostFlags);
                return out;
            }
            // Every launch, including virtual reuse, must cross the real Host ActivityStarter.
            // The virtual ledger supplies only the selected physical component and the desired
            // operation flags; it never finishes or retargets a live Activity itself.
            owner.startActivity(launch);
            if (routedRequest.getInt(RuntimeKeys.CALLER_TASK_ID, 0) > 0) {
                Bundle nested = owner.sessionBundle(session, GuestLaunchGate.LAUNCH_PENDING);
                nested.putAll(transaction);
                nested.putString(RuntimeKeys.STATUS, GuestLaunchGate.LAUNCH_PENDING);
                nested.putBoolean("launcherResolved", true);
                return nested;
            }
            String activityToken = transaction.getString(RuntimeKeys.ACTIVITY_TOKEN, "");
            String sessionId = session.sessionId();
            GuestLaunchObservation existing = sessionId.isEmpty()
                    ? null : owner.launchObservations.get(sessionId);
            if (existing != null) {
                owner.launchObservations.put(activityToken, existing);
                Bundle nested = owner.sessionBundle(session, GuestLaunchGate.LAUNCH_PENDING);
                nested.putAll(transaction);
                nested.putString(RuntimeKeys.STATUS, GuestLaunchGate.LAUNCH_PENDING);
                return nested;
            }
            GuestLaunchObservation observation = new GuestLaunchObservation(activityToken, component);
            if (!sessionId.isEmpty()) owner.launchObservations.put(sessionId, observation);
            owner.launchObservations.put(activityToken, observation);
            try {
                observation.await(LAUNCH_OBSERVATION_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            GuestLaunchEvidence evidence = observation.close();
            owner.launchObservations.remove(activityToken, observation);
            if (!sessionId.isEmpty()) owner.launchObservations.remove(sessionId, observation);
            String gate = GuestLaunchGate.evaluate(evidence);
            Bundle out = owner.sessionBundle(session, gate);
            out.putAll(transaction);
            out.putString(RuntimeKeys.STATUS, gate);
            out.putBoolean("launcherResolved", evidence.launcherResolved);
            out.putBoolean("activityCreated", evidence.onCreateCompleted);
            out.putBoolean("activityResumed", evidence.resumed);
            out.putBoolean("windowEvidence", evidence.windowEvidence);
            out.putInt("fatalCount", evidence.fatalCount);
            out.putInt("anrCount", evidence.anrCount);
            if (GuestLaunchGate.LAUNCH_FAILED.equals(gate)) {
                out.putString(RuntimeKeys.ERROR_TYPE, "LAUNCH_GATE_FAILED");
                out.putString(RuntimeKeys.ERROR_MESSAGE, evidence.failure.isEmpty()
                        ? "guest Activity create/resume/window not confirmed" : evidence.failure);
            }
            return out;
        } catch (Throwable error) {
            try {
                if (!issuedRouteToken.isEmpty()) owner.activityRuntime.launchFailed(issuedRouteToken);
            } finally {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            }
            return RuntimeBrokerService.failure(error);
        }
    }

    private static void copyActivityFrameworkField(Intent target, Bundle source, String key) {
        if (target == null || source == null || key == null || !source.containsKey(key)) return;
        Object value = source.get(key);
        if (value instanceof String string) target.putExtra(key, string);
        else if (value instanceof Integer integer) target.putExtra(key, integer);
        else if (value instanceof ArrayList<?> list) {
            ArrayList<String> strings = new ArrayList<>();
            for (Object item : list) if (item instanceof String string) strings.add(string);
            target.putStringArrayListExtra(key, strings);
        }
    }

    private static int hostActivityLaunchFlags(Bundle transaction, boolean frameworkHost) {
        int rawFlags = transaction == null ? 0
                : transaction.getInt(RuntimeKeys.ACTIVITY_FLAGS, 0);
        int flags = rawFlags;
        // The virtual ledger is the authority for reuse: the raw Guest flags (which may carry a
        // launchMode-originated SINGLE_TOP/CLEAR_TOP/REORDER_TO_FRONT) are stripped here and
        // re-derived below from the recorded launch decision.  Keeping the raw flags would let a
        // shared bounded Stub class make ActivityStarter rematch the wrong physical record.
        flags &= ~(LaunchFlags.SINGLE_TOP | LaunchFlags.CLEAR_TOP | LaunchFlags.REORDER_TO_FRONT);
        // The virtual ledger is the source of truth for task creation.  VA/NBB add a separate
        // host task boundary when their virtual AMS creates a task; using only NEW_TASK here lets
        // Android reuse an old task belonging to the same host package, which is especially
        // visible on OEM ActivityTaskManager implementations after force-stop/recovery.  Keep
        // normal nested launches in the caller's task, but make a newly-created virtual task
        // unambiguously new at the host boundary as well.
        boolean createdNewTask = transaction != null
                && transaction.getBoolean(RuntimeKeys.CREATED_NEW_TASK, false);
        boolean hostTaskRebind = transaction != null
                && transaction.getBoolean(RuntimeKeys.HOST_TASK_REBIND_REQUIRED, false);
        if (!frameworkHost || createdNewTask || hostTaskRebind) flags |= LaunchFlags.NEW_TASK;
        // A restored virtual task needs a fresh Host boundary, but it is still the same virtual
        // task.  MULTIPLE_TASK on MuMu API32 can make ActivityThread replay the parent route in
        // ActivityResult flows; reserve it for an actual virtual-task creation.
        if (createdNewTask) {
            flags |= LaunchFlags.MULTIPLE_TASK | LaunchFlags.RESET_TASK_IF_NEEDED;
        }
        // Project the virtual desired operation into real ActivityStarter flags.  The selected
        // physical component is one-to-one with the virtual Activity record, so ATMS can own the
        // clear/reorder/delivery transition without a Guest-side finish() or callback replay.
        String action = transaction == null ? ""
                : transaction.getString(RuntimeKeys.ACTIVITY_ACTION, "");
        switch (action) {
            // singleTop-onto-top and singleInstance reuse never move the target: the top already
            // holds the selected record so ActivityStarter.deliverToCurrentTopIfNeeded matches it.
            case "DELIVERED_NEW_INTENT":
                flags |= LaunchFlags.SINGLE_TOP;
                break;
            case "CLEARED_TOP":
                flags |= LaunchFlags.CLEAR_TOP;
                // The virtual ledger uses CLEARED_TOP for singleTask, singleTop and
                // document-into-existing reuse.  Preserve the framework delivery semantics for
                // those modes while allowing a STANDARD+CLEAR_TOP launch to recreate its target.
                String launchMode = transaction == null ? "STANDARD"
                        : transaction.getString(RuntimeKeys.ACTIVITY_LAUNCH_MODE, "STANDARD");
                if (!"STANDARD".equalsIgnoreCase(launchMode)
                        || (rawFlags & LaunchFlags.SINGLE_TOP) != 0) {
                    flags |= LaunchFlags.SINGLE_TOP;
                }
                break;
            case "CREATED_ACTIVITY":
                // CLEAR_TOP+STANDARD destroys the old target and creates a replacement.  The
                // route coordinator reuses the old target's physical window for that replacement
                // so ATMS can clear the real ActivityRecord before creating the new one.
                if ((rawFlags & LaunchFlags.CLEAR_TOP) != 0) flags |= LaunchFlags.CLEAR_TOP;
                break;
            case "REORDERED_TO_FRONT":
                flags |= LaunchFlags.REORDER_TO_FRONT;
                break;
            default:
                break;
        }
        return flags;
    }
}
