package com.warden.controlledsandbox.runtime.broker;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import com.warden.controlledsandbox.framework.activity.LaunchFlags;
import com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata;
import com.warden.controlledsandbox.runtime.diagnostics.RuntimeEventLog;
import com.warden.controlledsandbox.runtime.diagnostics.RuntimePerformanceTrace;
import com.warden.controlledsandbox.runtime.guest.GuestLaunchEvidence;
import com.warden.controlledsandbox.runtime.guest.GuestLaunchGate;
import com.warden.controlledsandbox.runtime.guest.GuestLaunchObservation;
import com.warden.controlledsandbox.runtime.guest.GuestPackageMetadataMapper;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import com.warden.controlledsandbox.runtime.protocol.RuntimeIntentWireCodec;
import com.warden.controlledsandbox.runtime.component.activity.BrokerActivityRuntime;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.session.SessionState;
import com.warden.controlledsandbox.framework.activity.ActivityTaskLedger;
import java.util.ArrayList;
import java.util.Locale;
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
    private static final long LAUNCH_OBSERVATION_MS = 30_000L;

    private final RuntimeBrokerService owner;

    RuntimeActivityLaunchCoordinator(RuntimeBrokerService owner) {
        this.owner = java.util.Objects.requireNonNull(owner, "owner");
    }

    Bundle launch(Bundle request) {
        owner.startService(new Intent(owner, RuntimeBrokerService.class));
        Bundle routedRequest = request == null ? new Bundle() : new Bundle(request);
        String requestId = routedRequest.getString(RuntimeKeys.REQUEST_ID, "").trim();
        if (requestId.isEmpty()) requestId = java.util.UUID.randomUUID().toString();
        String operationId = routedRequest.getString(RuntimeKeys.OPERATION_ID, "").trim();
        if (operationId.isEmpty()) operationId = requestId + "-launch";
        boolean awaitReadiness = routedRequest.getBoolean(RuntimeKeys.LAUNCH_AWAIT_READINESS, false);
        routedRequest.putString(RuntimeKeys.REQUEST_ID, requestId);
        routedRequest.putString(RuntimeKeys.OPERATION_ID, operationId);
        routedRequest.putInt(RuntimeKeys.ATTEMPT, 1);
        routedRequest.putInt(RuntimeKeys.RETRY_BUDGET, 0);
        routedRequest.putBoolean(RuntimeKeys.AUTOMATIC_RETRY_PERFORMED, false);
        RuntimePerformanceTrace perf = new RuntimePerformanceTrace(requestId, operationId,
                routedRequest.getString(RuntimeKeys.PACKAGE_NAME, ""));
        try (RuntimePerformanceTrace.Stage ignored = perf.stage(RuntimePerformanceTrace.CLIENT_LAUNCH_BEGIN)) {
            // The caller-side trace is correlated with this Broker trace by requestId/operationId.
        }
        long acceptedAtElapsedMs = android.os.SystemClock.elapsedRealtime();
        routedRequest.putLong(RuntimeKeys.LAUNCH_ACCEPTED_AT_ELAPSED_MS, acceptedAtElapsedMs);
        launchStage(requestId, operationId, "REQUEST_ACCEPTED", 0L, routedRequest);
        // The Guest sends oversized Intents through a bounded FD capability. Materialize only
        // inside the Broker, before the broker-owned Activity route is created; never echo the
        // large byte array through RuntimeOperationResult.
        RuntimeIntentWireCodec.materializePayloadForBroker(routedRequest);
        launchStage(requestId, operationId, "PREPARE_BEGIN",
                elapsedSince(acceptedAtElapsedMs), routedRequest);
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
            ActivityTaskLedger.LauncherTaskReuse launcherTaskReuse =
                    selectLauncherTaskReuse(routedRequest, callerPackage, targetPackage);
            if (launcherTaskReuse != null) {
                applyLauncherTaskReuse(routedRequest, launcherTaskReuse);
                Bundle reuseDetails = new Bundle(routedRequest);
                reuseDetails.putInt(RuntimeKeys.TASK_ID, launcherTaskReuse.taskId());
                reuseDetails.putString(RuntimeKeys.COMPONENT_CLASS,
                        launcherTaskReuse.top().identity().componentName());
                reuseDetails.putString(RuntimeKeys.PROCESS_NAME,
                        launcherTaskReuse.top().processName());
                launchStage(requestId, operationId, "LAUNCHER_TASK_REUSE_SELECTED",
                        elapsedSince(acceptedAtElapsedMs), reuseDetails);
                RuntimeEventLog.event("GUEST_LAUNCH_TASK_REUSE", reuseDetails);
            }
            Bundle prepared;
            try (RuntimePerformanceTrace.Stage ignored = perf.stage(RuntimePerformanceTrace.GUEST_PREPARE)) {
                prepared = owner.prepareGuestInternal(routedRequest);
            }
            launchStage(requestId, operationId, "PREPARE_RETURN",
                    elapsedSince(acceptedAtElapsedMs), prepared);
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
            launchStage(requestId, operationId, "LEDGER_LAUNCH_BEGIN",
                    elapsedSince(acceptedAtElapsedMs), routedRequest);
            Bundle transaction = activityRuntime.launch(session, component, prepared, routedRequest);
            launchStage(requestId, operationId, "LEDGER_LAUNCH_RETURN",
                    elapsedSince(acceptedAtElapsedMs), transaction);
            transaction.putString(RuntimeKeys.REQUEST_ID, requestId);
            transaction.putString(RuntimeKeys.OPERATION_ID, operationId);
            String activityToken = transaction.getString(RuntimeKeys.ACTIVITY_TOKEN, "");
            String sessionId = session.sessionId();
            int taskId = transaction.getInt(RuntimeKeys.TASK_ID, 0);
            int callerTaskId = routedRequest.getInt(RuntimeKeys.CALLER_TASK_ID, 0);
            boolean nestedLaunch = callerTaskId > 0;
            String taskKey = taskObservationKey(session, taskId);
            String callerTaskKey = taskObservationKey(session, callerTaskId);
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
                // ActivityThread Instrumentation launches (for example an app's synchronous
                // MainActivity -> BrowserActivity handoff) return LAUNCH_PENDING by design.
                // They still belong to the same virtual task launch observation.  Link the
                // child token before returning, otherwise the Guest can report a complete
                // lifecycle while the Broker waits only on the entry token.
                GuestLaunchObservation parent = sessionId.isEmpty()
                        ? null : owner.launchObservations.get(sessionId);
                if (parent == null && !callerTaskKey.isEmpty()) {
                    parent = owner.launchObservations.get(callerTaskKey);
                }
                if (parent != null) {
                    parent.linkActivity(activityToken, requestId, operationId, component);
                    if (!activityToken.isEmpty()) owner.launchObservations.put(activityToken, parent);
                    if (!taskKey.isEmpty()) owner.launchObservations.put(taskKey, parent);
                }
                Bundle out = owner.sessionBundle(session, GuestLaunchGate.LAUNCH_PENDING);
                out.putAll(transaction);
                out.putBoolean("launcherResolved", true);
                out.putString(RuntimeKeys.HOST_ACTIVITY_CLASS,
                        launch.getComponent() == null ? "" : launch.getComponent().getClassName());
                out.putInt(RuntimeKeys.HOST_ACTIVITY_FLAGS, hostFlags);
                return out;
            }
            GuestLaunchObservation existing = null;
            GuestLaunchObservation observation = null;
            // Register before the real ActivityStarter call.  The Host trampoline can reach
            // Guest onCreate/onResume before startActivity() returns, especially on MuMu when
            // the Guest process is already warm; registering afterwards loses the first event.
            existing = sessionId.isEmpty() ? null : owner.launchObservations.get(sessionId);
            if (existing == null && nestedLaunch && !callerTaskKey.isEmpty()) {
                // The Guest-side ActivityThread can create a new process/session for a child
                // Activity while retaining the virtual task.  NBB/VA resolve that child through
                // TaskRecord ownership; sessionId alone therefore cannot correlate lifecycle
                // evidence for a same-task handoff.
                existing = owner.launchObservations.get(callerTaskKey);
            }
            if (!nestedLaunch) {
                if (existing == null) {
                    observation = new GuestLaunchObservation(activityToken, component,
                            requestId, operationId,
                                    routedRequest.getLong(RuntimeKeys.LAUNCH_ACCEPTED_AT_ELAPSED_MS,
                                    acceptedAtElapsedMs));
                    if (!sessionId.isEmpty()) owner.launchObservations.put(sessionId, observation);
                    owner.launchObservations.put(requestId, observation);
                    owner.launchObservations.put(activityToken, observation);
                    if (!taskKey.isEmpty()) owner.launchObservations.put(taskKey, observation);
                } else {
                    existing.linkActivity(activityToken, requestId, operationId, component);
                    owner.launchObservations.put(activityToken, existing);
                    if (!taskKey.isEmpty()) owner.launchObservations.put(taskKey, existing);
                }
            } else if (existing != null) {
                // A Guest launcher such as Quark's MainActivity can synchronously start the
                // real BrowserActivity in the same virtual task.  Keep that child token on the
                // root observation so its CREATED/RESUMED/FIRST_FRAME evidence closes the same
                // logical launch gate.
                existing.linkActivity(activityToken, requestId, operationId, component);
                owner.launchObservations.put(activityToken, existing);
                if (!taskKey.isEmpty()) owner.launchObservations.put(taskKey, existing);
            }
            // Every launch, including virtual reuse, must cross the real Host ActivityStarter.
            // The virtual ledger supplies only the selected physical component and the desired
            // operation flags; it never finishes or retargets a live Activity itself.
            try {
                launchStage(requestId, operationId, "HOST_START_BEGIN",
                        elapsedSince(acceptedAtElapsedMs), transaction);
                try (RuntimePerformanceTrace.Stage ignored = perf.stage(RuntimePerformanceTrace.HOST_START_ACTIVITY)) {
                    owner.startActivity(launch);
                }
                launchStage(requestId, operationId, "HOST_START_RETURN",
                        elapsedSince(acceptedAtElapsedMs), transaction);
            } catch (Throwable error) {
                if (observation != null) removeObservationMappings(observation);
                else if (existing != null) owner.launchObservations.remove(activityToken, existing);
                throw error;
            }
            if (nestedLaunch) {
                Bundle nested = owner.sessionBundle(session, GuestLaunchGate.LAUNCH_PENDING);
                nested.putAll(transaction);
                nested.putString(RuntimeKeys.STATUS, GuestLaunchGate.LAUNCH_PENDING);
                nested.putBoolean("launcherResolved", true);
                return nested;
            }
            if (existing != null) {
                owner.launchObservations.put(activityToken, existing);
                Bundle nested = owner.sessionBundle(session, GuestLaunchGate.LAUNCH_PENDING);
                nested.putAll(transaction);
                nested.putString(RuntimeKeys.STATUS, GuestLaunchGate.LAUNCH_PENDING);
                return nested;
            }
            if (observation == null) {
                return owner.sessionBundle(session, GuestLaunchGate.LAUNCH_PENDING);
            }
            if (!awaitReadiness) {
                String observationToken = activityToken.isEmpty() ? requestId : activityToken;
                scheduleReadinessObservation(observation, session, transaction, observationToken,
                        component, requestId, operationId);
                Bundle accepted = owner.sessionBundle(session, GuestLaunchGate.LAUNCH_ACCEPTED);
                accepted.putAll(transaction);
                accepted.putString(RuntimeKeys.STATUS, GuestLaunchGate.LAUNCH_ACCEPTED);
                accepted.putBoolean(RuntimeKeys.LAUNCH_RUNTIME_ACCEPTED, true);
                accepted.putBoolean(RuntimeKeys.LAUNCH_GUEST_PROCESS_READY, true);
                accepted.putBoolean(RuntimeKeys.LAUNCH_ESSENTIAL_RUNTIME_READY, true);
                accepted.putBoolean(RuntimeKeys.LAUNCH_START_ACTIVITY_ACCEPTED, true);
                accepted.putBoolean(RuntimeKeys.LAUNCH_READINESS_PENDING, true);
                accepted.putString(RuntimeKeys.LAUNCH_OBSERVATION_TOKEN, observationToken);
                accepted.putBoolean("launcherResolved", true);
                accepted.putLong(RuntimeKeys.LAUNCH_ACCEPTED_AT_ELAPSED_MS,
                        observation.acceptedAtElapsedMs());
                accepted.putLong("launchAcceptedElapsedMs", elapsedSince(
                        observation.acceptedAtElapsedMs()));
                accepted.putString(RuntimeKeys.REQUEST_ID, requestId);
                accepted.putString(RuntimeKeys.OPERATION_ID, operationId);
                accepted.putInt(RuntimeKeys.ATTEMPT, 1);
                accepted.putInt(RuntimeKeys.RETRY_BUDGET, 0);
                accepted.putBoolean(RuntimeKeys.AUTOMATIC_RETRY_PERFORMED, false);
                perf.close();
                return accepted;
            }
            try {
                observation.await(LAUNCH_OBSERVATION_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            GuestLaunchEvidence evidence = observation.close();
            removeObservationMappings(observation);
            String gate = GuestLaunchGate.evaluate(evidence);
            Bundle out = owner.sessionBundle(session, gate);
            out.putAll(transaction);
            out.putString(RuntimeKeys.STATUS, gate);
            out.putBoolean("launcherResolved", evidence.launcherResolved);
            out.putBoolean("activityCreated", evidence.onCreateCompleted);
            out.putBoolean("activityResumed", evidence.resumed);
            out.putBoolean("windowEvidence", evidence.windowEvidence);
            out.putBoolean("firstFrameDrawn", evidence.firstFrameDrawn);
            out.putStringArrayList("launchTimeline", evidence.timeline);
            out.putLong("launchReadinessElapsedMs",
                    Math.max(0L, android.os.SystemClock.elapsedRealtime()
                            - observation.acceptedAtElapsedMs()));
            out.putLong(RuntimeKeys.LAUNCH_ACCEPTED_AT_ELAPSED_MS,
                    observation.acceptedAtElapsedMs());
            out.putString(RuntimeKeys.REQUEST_ID, requestId);
            out.putString(RuntimeKeys.OPERATION_ID, operationId);
            out.putInt(RuntimeKeys.ATTEMPT, 1);
            out.putInt(RuntimeKeys.RETRY_BUDGET, 0);
            out.putBoolean(RuntimeKeys.AUTOMATIC_RETRY_PERFORMED, false);
            out.putInt("fatalCount", evidence.fatalCount);
            out.putInt("anrCount", evidence.anrCount);
            if (GuestLaunchGate.LAUNCH_FAILED.equals(gate)) {
                out.putString(RuntimeKeys.ERROR_TYPE, "LAUNCH_GATE_FAILED");
                out.putString(RuntimeKeys.ERROR_MESSAGE, evidence.failure.isEmpty()
                        ? "guest Activity create/resume/window not confirmed" : evidence.failure);
            }
            out.putString(RuntimeKeys.LAUNCH_OBSERVATION_TOKEN,
                    activityToken.isEmpty() ? requestId : activityToken);
            owner.publishLaunchReadiness(activityToken.isEmpty() ? requestId : activityToken, out);
            perf.close();
            return out;
        } catch (Throwable error) {
            try {
                if (!issuedRouteToken.isEmpty()) owner.activityRuntime.launchFailed(issuedRouteToken);
            } finally {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            }
            perf.close();
            return RuntimeBrokerService.failure(error);
        }
    }

    private void removeObservationMappings(GuestLaunchObservation observation) {
        if (observation == null) return;
        owner.launchObservations.forEach((key, value) -> {
            if (value == observation) owner.launchObservations.remove(key, value);
        });
    }

    private void scheduleReadinessObservation(GuestLaunchObservation observation,
                                              GuestSession session, Bundle transaction,
                                              String activityToken, String component,
                                              String requestId, String operationId) {
        owner.executeLaunchObservation(() -> {
            try {
                observation.await(LAUNCH_OBSERVATION_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            GuestLaunchEvidence evidence = observation.close();
            removeObservationMappings(observation);
            String gate = GuestLaunchGate.evaluate(evidence);
            Bundle details = owner.sessionBundle(session, gate);
            details.putAll(transaction);
            details.putString(RuntimeKeys.STATUS, gate);
            details.putString(RuntimeKeys.REQUEST_ID, requestId);
            details.putString(RuntimeKeys.OPERATION_ID, operationId);
            details.putString(RuntimeKeys.LAUNCH_OBSERVATION_TOKEN, activityToken);
            details.putString(RuntimeKeys.COMPONENT_CLASS, component);
            details.putBoolean(RuntimeKeys.LAUNCH_READINESS_PENDING, false);
            details.putString("readinessStatus", gate);
            details.putBoolean("activityCreated", evidence.onCreateCompleted);
            details.putBoolean("activityResumed", evidence.resumed);
            details.putBoolean("windowEvidence", evidence.windowEvidence);
            details.putBoolean("firstFrameDrawn", evidence.firstFrameDrawn);
            details.putStringArrayList("launchTimeline", evidence.timeline);
            details.putLong("launchReadinessElapsedMs", Math.max(0L,
                    android.os.SystemClock.elapsedRealtime() - observation.acceptedAtElapsedMs()));
            details.putInt("fatalCount", evidence.fatalCount);
            details.putInt("anrCount", evidence.anrCount);
            if (GuestLaunchGate.LAUNCH_FAILED.equals(gate)) {
                details.putString(RuntimeKeys.ERROR_TYPE, "LAUNCH_GATE_FAILED");
                details.putString(RuntimeKeys.ERROR_MESSAGE, evidence.failure.isEmpty()
                        ? "guest Activity create/resume/window not confirmed" : evidence.failure);
            }
            owner.publishLaunchReadiness(activityToken, details);
            RuntimeEventLog.event("GUEST_LAUNCH_READINESS", details);
        });
    }

    private static String taskObservationKey(GuestSession session, int taskId) {
        if (session == null || taskId <= 0) return "";
        // The task id is only unique inside the virtual package/user namespace.  Do not include
        // processName: NBB/VA allow a same-task launcher and child Activity to cross Guest
        // process/session boundaries.
        return "task:" + session.virtualUserId() + ":" + session.packageName() + ":" + taskId;
    }

    /**
     * Performs a read-only task lookup before Guest preparation. The lookup is intentionally
     * narrow: only the ordinary external NEW_TASK launcher shape may take it, so explicit task,
     * document, clear, reorder and nested-launch contracts retain their existing semantics.
     */
    private ActivityTaskLedger.LauncherTaskReuse selectLauncherTaskReuse(
            Bundle request, String callerPackage, String targetPackage) {
        if (request == null || callerPackage == null || callerPackage.trim().isEmpty()
                || (!targetPackage.isEmpty() && !targetPackage.equals(callerPackage))
                || request.getBoolean(RuntimeKeys.ACTIVITY_FRAMEWORK_HOST, false)
                || request.getInt(RuntimeKeys.CALLER_TASK_ID, 0) > 0) {
            return null;
        }
        String launchMode = request.getString(RuntimeKeys.ACTIVITY_LAUNCH_MODE, "STANDARD");
        if (!"STANDARD".equalsIgnoreCase(launchMode == null ? "" : launchMode.trim())) {
            return null;
        }
        String documentMode = request.getString(RuntimeKeys.DOCUMENT_LAUNCH_MODE, "NONE");
        if (!"NONE".equalsIgnoreCase(documentMode == null ? "" : documentMode.trim())) {
            return null;
        }
        int flags = request.containsKey(RuntimeKeys.ACTIVITY_FLAGS)
                ? request.getInt(RuntimeKeys.ACTIVITY_FLAGS, 0) : LaunchFlags.NEW_TASK;
        int launcherSafeFlags = LaunchFlags.NEW_TASK | LaunchFlags.RESET_TASK_IF_NEEDED;
        if (!LaunchFlags.has(flags, LaunchFlags.NEW_TASK)
                || (flags & ~launcherSafeFlags) != 0) {
            return null;
        }
        int userId = request.getInt(RuntimeKeys.VIRTUAL_USER_ID, -1);
        String component = request.getString(RuntimeKeys.COMPONENT_CLASS, "");
        String packageName = targetPackage == null || targetPackage.trim().isEmpty()
                ? callerPackage.trim() : targetPackage.trim();
        String revision = packageRevision(request);
        if (userId < 0 || component == null || component.trim().isEmpty() || revision.isEmpty()) {
            return null;
        }
        String affinity = request.getString(RuntimeKeys.TASK_AFFINITY, packageName);
        if (affinity == null || affinity.trim().isEmpty()) affinity = packageName;
        ActivityTaskLedger.LauncherTaskReuse candidate = owner.activityRuntime.launcherTaskReuse(
                userId, packageName, revision, component.trim(), affinity.trim());
        if (candidate == null) return null;
        GuestSession topSession = owner.sessions.get(
                packageName, userId, candidate.top().processName());
        if (topSession == null || topSession.generation() != candidate.top().processGeneration()
                || (topSession.state() != SessionState.READY
                && topSession.state() != SessionState.ACTIVE)) {
            return null;
        }
        return candidate;
    }

    private static void applyLauncherTaskReuse(
            Bundle request, ActivityTaskLedger.LauncherTaskReuse candidate) {
        String originalComponent = request.getString(RuntimeKeys.COMPONENT_CLASS, "").trim();
        request.putString(RuntimeKeys.LAUNCHER_REQUESTED_COMPONENT, originalComponent);
        request.putBoolean(RuntimeKeys.LAUNCHER_TASK_REUSE, true);
        request.putString(RuntimeKeys.COMPONENT_CLASS,
                candidate.top().identity().componentName());
        request.putString(RuntimeKeys.PROCESS_NAME, candidate.top().processName());
        request.putString(RuntimeKeys.TASK_AFFINITY, candidate.taskAffinity());
        request.putString(RuntimeKeys.ACTIVITY_LAUNCH_MODE, "SINGLE_TOP");
        int flags = request.containsKey(RuntimeKeys.ACTIVITY_FLAGS)
                ? request.getInt(RuntimeKeys.ACTIVITY_FLAGS, 0) : LaunchFlags.NEW_TASK;
        request.putInt(RuntimeKeys.ACTIVITY_FLAGS,
                flags | LaunchFlags.NEW_TASK | LaunchFlags.SINGLE_TOP);
    }

    private static String packageRevision(Bundle request) {
        String explicit = request.getString(RuntimeKeys.PACKAGE_REVISION, "");
        if (explicit != null && !explicit.trim().isEmpty()) return explicit.trim();
        long versionCode = request.getLong(RuntimeKeys.APK_VERSION_CODE, -1L);
        String sha256 = request.getString(RuntimeKeys.APK_SHA256, "");
        if (versionCode < 0 || sha256 == null || sha256.trim().isEmpty()) return "";
        return "v" + versionCode + ":sha256:" + sha256.trim().toLowerCase(Locale.ROOT);
    }

    private static void launchStage(String requestId, String operationId, String stage,
                                    long stageElapsedMs, Bundle source) {
        Bundle details = source == null ? new Bundle() : new Bundle(source);
        details.putString(RuntimeKeys.REQUEST_ID, requestId);
        details.putString(RuntimeKeys.OPERATION_ID, operationId);
        details.putString(RuntimeKeys.LAUNCH_STAGE, stage);
        details.putLong(RuntimeKeys.LAUNCH_STAGE_AT_ELAPSED_MS, stageElapsedMs);
        RuntimeEventLog.event("GUEST_LAUNCH_STAGE", details);
    }

    private static long elapsedSince(long acceptedAtElapsedMs) {
        return Math.max(0L, android.os.SystemClock.elapsedRealtime() - acceptedAtElapsedMs);
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
        boolean hostTaskReuse = transaction != null
                && transaction.getBoolean(RuntimeKeys.HOST_TASK_REUSE, false);
        if (!frameworkHost || createdNewTask || hostTaskRebind) flags |= LaunchFlags.NEW_TASK;
        // A restored virtual task needs a fresh Host boundary, but it is still the same virtual
        // task.  MULTIPLE_TASK on MuMu API32 can make ActivityThread replay the parent route in
        // ActivityResult flows; reserve it for an actual virtual-task creation.
        if (createdNewTask && !hostTaskReuse) {
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
