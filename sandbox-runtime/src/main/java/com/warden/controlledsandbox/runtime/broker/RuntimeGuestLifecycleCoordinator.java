package com.warden.controlledsandbox.runtime.broker;

import android.os.Bundle;
import android.os.Parcel;

import com.warden.controlledsandbox.contract.RuntimeOperationRequest;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.session.SessionRevisionPolicy;
import com.warden.controlledsandbox.domain.session.SessionState;
import com.warden.controlledsandbox.domain.protocol.RuntimeProtocol;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import com.warden.controlledsandbox.runtime.diagnostics.RuntimeEventLog;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Set;

/**
 * Owns the Guest process lease transaction.
 *
 * <p>Preparation, generation recovery and stop are one lifecycle state machine.  Keeping that
 * state machine out of {@link RuntimeBrokerService} makes it possible to reason about the
 * bindApplication/LoadedApk boundary independently from Activity, Service and Provider routing.
 * The Broker remains the authority; this class only owns the process-lifecycle projection.</p>
 */
final class RuntimeGuestLifecycleCoordinator {
    private static final long STOP_TIMEOUT_MILLIS = 10_000L;
    private static final String VALIDATED_STATE_FINGERPRINT =
            "validatedPackageStateFingerprint";
    /** Package-state projection hash used as the immutable catalog generation for a proof. */
    private static final String VALIDATED_CATALOG_GENERATION =
            "validatedCatalogGeneration";
    private static final String VALIDATED_PROCESS_NAMES = "validatedProcessNames";
    private static final String VALIDATED_ARTIFACT_PATHS = "validatedArtifactPaths";
    private static final String VALIDATED_ARTIFACT_SIZES = "validatedArtifactSizes";
    private static final String VALIDATED_ARTIFACT_IDENTITIES = "validatedArtifactIdentities";
    private static final String VALIDATED_ARTIFACT_MODIFIED = "validatedArtifactModified";

    private static final String[] VALIDATED_STRING_KEYS = {
            RuntimeKeys.PACKAGE_NAME,
            RuntimeKeys.APK_PATH,
            RuntimeKeys.APK_SHA256,
            RuntimeKeys.BASE_APK_SHA256,
            RuntimeKeys.PACKAGE_REVISION,
            RuntimeKeys.NATIVE_LIBRARY_DIR,
            RuntimeKeys.NATIVE_ABI,
            RuntimeKeys.NATIVE_GUEST_TRUST,
            RuntimeKeys.NATIVE_EXECUTION_MODE,
            RuntimeKeys.APPLICATION_CLASS,
            RuntimeKeys.SHARED_LIBRARIES
    };
    private static final String[] VALIDATED_LIST_KEYS = {
            RuntimeKeys.SPLIT_NAMES,
            RuntimeKeys.SPLIT_TYPES,
            RuntimeKeys.SPLIT_CONFIG_FOR,
            RuntimeKeys.SPLIT_USES,
            RuntimeKeys.SPLIT_PATHS,
            RuntimeKeys.SPLIT_SHA256S,
            RuntimeKeys.PERMISSIONS
    };

    private final RuntimeBrokerService owner;

    RuntimeGuestLifecycleCoordinator(RuntimeBrokerService owner) {
        this.owner = java.util.Objects.requireNonNull(owner, "owner");
    }

    Bundle prepareGuest(Bundle request) {
        Bundle input = null;
        try {
            input = request == null ? new Bundle() : new Bundle(request);
            long lifecycleStarted = android.os.SystemClock.elapsedRealtime();
            lifecycleStage(input, null, "BEGIN", lifecycleStarted);
            String packageName = input.getString(RuntimeKeys.PACKAGE_NAME, "");
            int userId = input.getInt(RuntimeKeys.VIRTUAL_USER_ID, -1);
            String processName = RuntimeBrokerService.processName(input, packageName);
            GuestSession existing = null;
            Bundle cachedPrepared = null;
            Bundle cachedValidation = null;
            boolean fastReusePath = false;
            boolean recoveryArtifactReusePath = false;
            boolean validationArtifactReusePath = false;
            String validationCacheKey = "";
            if (syntacticallyReusableInput(input, packageName, userId, processName)) {
                existing = owner.sessions.get(packageName, userId, processName);
                cachedPrepared = existing == null ? null : owner.brokerState.prepared(
                        RuntimeBrokerService.processKey(packageName, userId, processName));
                boolean cachedArtifactMatches = canReusePreparedArtifact(
                        input, existing, cachedPrepared);
                fastReusePath = cachedArtifactMatches && existing != null
                        && (existing.state() == SessionState.READY
                        || existing.state() == SessionState.ACTIVE);
                recoveryArtifactReusePath = cachedArtifactMatches && existing != null
                        && existing.state() == SessionState.RECOVERING;
                if (!input.getBoolean(RuntimeKeys.ISOLATED_PROCESS, false)) {
                    validationCacheKey = validationCacheKey(input, packageName, userId);
                    cachedValidation = validationCacheKey.isEmpty() ? null
                            : owner.brokerState.validatedArtifact(validationCacheKey);
                    validationArtifactReusePath = !fastReusePath && !recoveryArtifactReusePath
                            && canReuseValidatedArtifact(input, packageName, userId, processName,
                            cachedValidation);
                }
            }
            if (fastReusePath) {
                input.putString(RuntimeKeys.PROCESS_NAME, processName);
                input.putString(RuntimeKeys.PACKAGE_REVISION, existing.packageRevision());
                // NBB/VA reuse the already bound process record and LoadedApk on a hot Activity
                // launch.  The immutable revision, cached prepared spec and native policy have
                // already crossed the full validation boundary during the first prepare.
                lifecycleStage(input, existing, "INPUT_VALIDATE_SKIPPED_REUSE", lifecycleStarted);
                lifecycleStage(input, existing, "PROCESS_VALIDATE_SKIPPED_REUSE", lifecycleStarted);
                lifecycleStage(input, existing, "REVISION_READ_RETURN", lifecycleStarted);
            } else if (recoveryArtifactReusePath) {
                // A process may intentionally exit after handing the virtual task to a child
                // process (DingTalk's LaunchHomeActivity does this).  NBB/VA keep the validated
                // PackageSetting/LoadedApk artifact and rebuild only the dead process record;
                // they do not hash/index the same installed revision again.  Keep the declared
                // process identity is already bound to the exact RECOVERING registry key and
                // broker-owned immutable spec; NBB/VA do not re-query the package process table
                // while rebuilding a dead process record.  A changed package/user/process or
                // APK hash/version/trust/native mode cannot enter this branch because the
                // existing-session and canReusePreparedArtifact() checks reject it.
                Bundle recoveryInput = new Bundle(cachedPrepared);
                for (String key : new String[] {
                        RuntimeKeys.SLOT_PAD_COUNT, RuntimeKeys.SLOT_TARGET}) {
                    if (input.containsKey(key)) recoveryInput.putInt(key, input.getInt(key));
                }
                input = recoveryInput;
                packageName = input.getString(RuntimeKeys.PACKAGE_NAME, packageName);
                userId = input.getInt(RuntimeKeys.VIRTUAL_USER_ID, userId);
                processName = RuntimeBrokerService.processName(input, packageName);
                input.putString(RuntimeKeys.PROCESS_NAME, processName);
                input.putString(RuntimeKeys.PACKAGE_REVISION, existing.packageRevision());
                lifecycleStage(input, existing, "INPUT_VALIDATE_SKIPPED_RECOVERY_ARTIFACT",
                        lifecycleStarted);
                lifecycleStage(input, existing, "PROCESS_VALIDATE_SKIPPED_RECOVERY_ARTIFACT",
                        lifecycleStarted);
                lifecycleStage(input, existing, "REVISION_READ_RETURN", lifecycleStarted);
            } else if (validationArtifactReusePath) {
                applyValidatedArtifact(input, cachedValidation);
                packageName = input.getString(RuntimeKeys.PACKAGE_NAME, packageName);
                userId = input.getInt(RuntimeKeys.VIRTUAL_USER_ID, userId);
                processName = RuntimeBrokerService.processName(input, packageName);
                input.putString(RuntimeKeys.PROCESS_NAME, processName);
                lifecycleStage(input, null, "INPUT_VALIDATE_SKIPPED_CACHED_ARTIFACT",
                        lifecycleStarted);
                lifecycleStage(input, null, "PROCESS_VALIDATE_SKIPPED_CACHED_ARTIFACT",
                        lifecycleStarted);
                lifecycleStage(input, null, "REVISION_READ_RETURN", lifecycleStarted);
            } else {
                owner.validateInput(input);
                lifecycleStage(input, null, "INPUT_VALIDATE_RETURN", lifecycleStarted);
                packageName = input.getString(RuntimeKeys.PACKAGE_NAME, "");
                userId = input.getInt(RuntimeKeys.VIRTUAL_USER_ID, -1);
                processName = RuntimeBrokerService.processName(input, packageName);
                Set<String> declaredProcesses = owner.validateDeclaredProcess(
                        packageName, userId, processName);
                lifecycleStage(input, null, "PROCESS_VALIDATE_RETURN", lifecycleStarted);
                validationCacheKey = validationCacheKey(input, packageName, userId);
                if (!validationCacheKey.isEmpty()
                        && !input.getBoolean(RuntimeKeys.ISOLATED_PROCESS, false)) {
                    owner.brokerState.putValidatedArtifact(validationCacheKey,
                            validatedArtifact(input, declaredProcesses));
                    lifecycleStage(input, null, "VALIDATION_ARTIFACT_CACHE_PUT",
                            lifecycleStarted);
                }
                lifecycleStage(input, null, "REVISION_READ_RETURN", lifecycleStarted);
                existing = owner.sessions.get(packageName, userId, processName);
            }
            String packageRevision = RuntimeBrokerService.required(input,
                    RuntimeKeys.PACKAGE_REVISION);
            input.putString(RuntimeKeys.PROCESS_NAME, processName);
            boolean reusePreparedSession = existing != null
                    && existing.packageRevision().equals(packageRevision)
                    && (existing.state() == SessionState.READY
                            || existing.state() == SessionState.ACTIVE);
            boolean reusePreparedArtifact = reusePreparedSession || recoveryArtifactReusePath;
            // A same-revision READY/ACTIVE process has already crossed the package-install,
            // process-bind and Activity-ledger boundaries.  NBB/VA reuse that process record on
            // each ActivityThread launch; they do not rescan or stop package sessions on the hot
            // path.  A revision change cannot take this branch because the session revision is
            // part of the reuse predicate, so cold/recovery/upgrade still performs the complete
            // stale-session and ledger cleanup below.
            if (reusePreparedArtifact) {
                lifecycleStage(input, existing,
                        recoveryArtifactReusePath
                                ? "REVISION_CLEANUP_SKIPPED_RECOVERY_ARTIFACT"
                                : "REVISION_CLEANUP_SKIPPED_REUSE",
                        lifecycleStarted);
            } else {
                stopMismatchedRevisionSessions(packageName, userId, packageRevision);
                lifecycleStage(input, existing, "REVISION_CLEANUP_RETURN", lifecycleStarted);
            }
            // VA/NBB build the package/receiver view while the virtual process is prepared and
            // reuse that view for subsequent Activity launches.  Re-reading the APK manifest on
            // every hot launch is both redundant and observable on MuMu: the large commercial
            // APK parse can consume several seconds before the actual ActivityStarter call.
            // Keep indexing on first prepare, recovery, and any non-ready lease; only a live
            // same-revision session with a broker-owned prepared spec may take the fast path.
            if (!reusePreparedArtifact) {
                lifecycleStage(input, null, "INDEX_BEGIN", lifecycleStarted);
                owner.receiverCoordinator.indexPackage(input);
                lifecycleStage(input, null, "INDEX_RETURN", lifecycleStarted);
            } else {
                lifecycleStage(input, existing,
                        recoveryArtifactReusePath
                                ? "INDEX_SKIPPED_RECOVERY_ARTIFACT" : "INDEX_SKIPPED_REUSE",
                        lifecycleStarted);
            }
            padOrdinarySlots(packageName, userId, packageRevision, processName,
                    input.getInt(RuntimeKeys.SLOT_PAD_COUNT, 0),
                    input.getInt(RuntimeKeys.SLOT_TARGET, -1));
            lifecycleStage(input, null, "SLOT_PAD_RETURN", lifecycleStarted);
            GuestSession session = owner.sessions.allocate(
                    packageName, userId, processName, packageRevision, owner.now());
            lifecycleStage(input, session, "SESSION_ALLOCATE_RETURN", lifecycleStarted);
            GuestSession staleRecovery = null;
            String key = RuntimeBrokerService.processKey(packageName, userId, processName);
            if (session.state() == SessionState.READY || session.state() == SessionState.ACTIVE) {
                Bundle cached = owner.brokerState.prepared(key);
                if (cached == null) throw new IllegalStateException("PREPARED_SPEC_MISSING");
                if (!session.packageRevision().equals(
                        cached.getString(RuntimeKeys.PACKAGE_REVISION, ""))) {
                    throw new IllegalStateException("PREPARED_SPEC_REVISION_MISMATCH");
                }
                // A persisted READY/ACTIVE session is only a broker-side lease.  After a
                // process death Android may recreate the declared Guest service with an empty
                // GuestRuntimeEnvironment while the old session record and prepared spec still
                // exist.  Do not return the cached spec until the newly bound Binder proves that
                // bindApplication/LoadedApk/Application bootstrap is actually READY.
                Bundle runtimeStatus = null;
                Throwable statusFailure = null;
                try {
                    lifecycleStage(input, session, "STATUS_CALL_BEGIN", lifecycleStarted);
                    runtimeStatus = owner.callGuestWithLaunchDeadline(session.processSlot(), input, guest ->
                            RuntimeBrokerService.guestOperation(guest,
                                    RuntimeOperationRequest.GUEST_RUNTIME_STATUS, new Bundle()));
                    lifecycleStage(input, session, "STATUS_CALL_RETURN", lifecycleStarted);
                } catch (Throwable error) {
                    lifecycleStage(input, session, "STATUS_CALL_FAILED", lifecycleStarted);
                    com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
                    statusFailure = error;
                }
                boolean frameworkActivityReady = runtimeStatus != null
                        && runtimeStatus.getBoolean("frameworkActivityTransportInstalled", false)
                        && runtimeStatus.getBoolean("frameworkComponentLifecycleReady", false)
                        && !"BLOCKED".equals(runtimeStatus.getString("frameworkReadiness", ""));
                String runtimeReadiness = runtimeStatus == null
                        ? "" : runtimeStatus.getString(RuntimeKeys.STATUS, "");
                boolean reusableRuntime = "READY".equals(runtimeReadiness)
                        || "DEGRADED".equals(runtimeReadiness);
                if (runtimeStatus != null
                        && reusableRuntime
                        && frameworkActivityReady) {
                    // A non-mandatory framework capability may be unavailable by an expected
                    // platform policy (for example API33's untrusted-app wifiscanner boundary).
                    // That status remains visible to diagnostics and capability assertions, but
                    // it must not make an otherwise live bindApplication/LoadedApk transport
                    // look process-dead. Rebuilding the same generation during an Activity
                    // callback would tear down the caller's main-thread session and deadlock the
                    // framework launch transaction.
                    lifecycleStage(input, session, "SESSION_BIND_BEGIN", lifecycleStarted);
                    owner.receiverCoordinator.bindSession(session);
                    lifecycleStage(input, session, "SESSION_BIND_RETURN", lifecycleStarted);
                    Bundle out = new Bundle(cached);
                    out.putString(RuntimeKeys.STATUS, cached.getBoolean("frameworkDegraded", false)
                            || "DEGRADED".equals(runtimeReadiness)
                            ? "ALREADY_PREPARED_DEGRADED" : "ALREADY_PREPARED");
                    return out;
                }
                if (fastReusePath) {
                    // A READY registry entry is only a lease hint.  If the Guest Binder no longer
                    // proves the compact runtime contract, recover through the full immutable
                    // artifact and declared-process validation before making a new generation.
                    owner.validateInput(input);
                    lifecycleStage(input, session, "INPUT_VALIDATE_RECOVERY_RETURN", lifecycleStarted);
                    packageName = input.getString(RuntimeKeys.PACKAGE_NAME, "");
                    userId = input.getInt(RuntimeKeys.VIRTUAL_USER_ID, -1);
                    processName = RuntimeBrokerService.processName(input, packageName);
                    owner.validateDeclaredProcess(packageName, userId, processName);
                    lifecycleStage(input, session, "PROCESS_VALIDATE_RECOVERY_RETURN", lifecycleStarted);
                    packageRevision = RuntimeBrokerService.required(input,
                            RuntimeKeys.PACKAGE_REVISION);
                    fastReusePath = false;
                }
                GuestSession observed = owner.sessions.get(packageName, userId, processName);
                if (observed != null && (observed.state() == SessionState.READY
                        || observed.state() == SessionState.ACTIVE)) {
                    String reason = statusFailure == null
                            ? "GUEST_RUNTIME_STATUS_" + (runtimeStatus == null
                                    ? "MISSING" : runtimeStatus.getString(RuntimeKeys.STATUS, "UNKNOWN"))
                            : "GUEST_RUNTIME_STATUS_FAILED:" + statusFailure.getClass().getSimpleName();
                    if (runtimeStatus != null && "READY".equals(
                            runtimeStatus.getString(RuntimeKeys.STATUS, ""))
                            && !frameworkActivityReady) {
                        reason = "GUEST_FRAMEWORK_ACTIVITY_TRANSPORT_NOT_READY";
                    }
                    GuestSession died = owner.sessions.markProcessDied(packageName, userId,
                            processName, observed.generation(), owner.now(), reason);
                    // A stale Binder can remain technically reachable while the process has
                    // already lost its Guest runtime. Converge every component authority here,
                    // not only the SessionRegistry, so Service recovery sees RECOVERING records
                    // and no old Provider/Receiver/Activity capability survives the rebind.
                    owner.activityRuntime.processDisconnected(died);
                    owner.serviceCoordinator.disconnectSession(died);
                    owner.receiverCoordinator.disconnectSession(died,
                            "GUEST_RUNTIME_STATUS_NOT_READY:" + reason);
                    owner.providerResources.disconnectSession(died);
                }
                session = owner.sessions.get(packageName, userId, processName);
            }
            if (session.state() == SessionState.RECOVERING) {
                staleRecovery = session;
                session = owner.sessions.beginRecovery(packageName, userId, processName,
                        session.generation(), owner.now());
            } else if (session.state() == SessionState.ALLOCATED) {
                session = owner.sessions.transition(packageName, userId, processName,
                        session.generation(), SessionState.PREPARING, owner.now(), "");
            } else {
                throw new IllegalStateException("SESSION_BUSY:" + session.state());
            }
            Bundle spec = makeSpec(input, session);
            owner.systemServiceCoordinator.attach(session, spec);
            // Publish only the exact PREPARING generation so Application.onCreate() can use
            // standard Context APIs without recursively trying to prepare the same process.
            owner.brokerState.putPrepared(key, new Bundle(spec));
            Bundle guestResult;
            try {
                guestResult = owner.callGuestWithLaunchDeadline(session.processSlot(), input, guest ->
                        RuntimeBrokerService.guestOperation(guest,
                                RuntimeOperationRequest.PREPARE_GUEST, spec));
            } catch (Throwable error) {
                try {
                    owner.brokerState.removePrepared(key);
                    if (staleRecovery != null) {
                        owner.activityRuntime.invalidate(staleRecovery);
                        owner.serviceCoordinator.invalidate(staleRecovery);
                        owner.receiverCoordinator.stopSession(staleRecovery,
                                "ORDERED_RECEIVER_RECOVERY_FAILED");
                        owner.providerResources.stopSession(staleRecovery);
                    }
                    owner.systemServiceCoordinator.stop(session);
                } finally {
                    com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
                }
                throw error;
            }
            String guestStatus = guestResult.getString(RuntimeKeys.STATUS, "FAILED");
            boolean degraded = "DEGRADED".equals(guestStatus)
                    || "ALREADY_DEGRADED".equals(guestStatus);
            if (!"READY".equals(guestStatus) && !"ALREADY_READY".equals(guestStatus) && !degraded) {
                owner.sessions.transition(packageName, userId, processName, session.generation(),
                        SessionState.FAILED,
                        owner.now(), guestResult.getString(RuntimeKeys.ERROR_TYPE,
                                "GUEST_PREPARE_FAILED"));
                if (staleRecovery != null) {
                    owner.activityRuntime.invalidate(staleRecovery);
                    owner.serviceCoordinator.invalidate(staleRecovery);
                    owner.receiverCoordinator.stopSession(staleRecovery,
                            "ORDERED_RECEIVER_RECOVERY_FAILED");
                    owner.providerResources.stopSession(staleRecovery);
                }
                owner.systemServiceCoordinator.stop(session);
                owner.brokerState.removePrepared(key);
                return guestResult;
            }
            if (staleRecovery != null) {
                owner.componentRecoveryCoordinator.recover(staleRecovery, session, spec);
            }
            GuestSession ready = owner.sessions.transition(packageName, userId, processName,
                    session.generation(), SessionState.READY, owner.now(), "");
            Bundle cachedSpec = new Bundle(spec);
            cachedSpec.putBoolean("frameworkDegraded", degraded);
            owner.brokerState.putPrepared(key, cachedSpec);
            owner.receiverCoordinator.bindSession(ready);
            Bundle out = new Bundle(spec);
            out.putAll(guestResult);
            int guestPid = guestResult.getInt("pid", guestResult.getInt(RuntimeKeys.PLATFORM_PID, 0));
            if (guestPid > 0) out.putInt(RuntimeKeys.PLATFORM_PID, guestPid);
            out.putString(RuntimeKeys.STATUS, degraded ? "PREPARED_DEGRADED" : "PREPARED");
            out.putString("sessionState", ready.state().name());
            return out;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            // Every failure after allocation is terminal for this generation.  In particular,
            // recovery prewarm can fail from a Guest callback after the inner Guest-call
            // boundary has returned.  Leaving that lease in PREPARING makes the next explicit
            // launch observe a permanent SESSION_BUSY instead of starting a clean generation.
            rollbackAllocatedSession(input, error);
            return RuntimeBrokerService.failure(error);
        }
    }

    private void rollbackAllocatedSession(Bundle input, Throwable failure) {
        if (input == null) return;
        String packageName = input.getString(RuntimeKeys.PACKAGE_NAME, "");
        int userId = input.getInt(RuntimeKeys.VIRTUAL_USER_ID, -1);
        if (packageName.isEmpty() || userId < 0) return;
        String processName = RuntimeBrokerService.processName(input, packageName);
        GuestSession current = owner.sessions.get(packageName, userId, processName);
        if (current == null || (current.state() != SessionState.ALLOCATED
                && current.state() != SessionState.PREPARING)) return;
        String key = RuntimeBrokerService.processKey(packageName, userId, processName);
        String reason = "PREPARE_ROLLBACK:" + failure.getClass().getSimpleName();
        try {
            owner.sessions.transition(packageName, userId, processName, current.generation(),
                    SessionState.FAILED, owner.now(), reason);
        } catch (Throwable transitionFailure) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(
                    transitionFailure);
        }
        owner.brokerState.removePrepared(key);
        try {
            owner.systemServiceCoordinator.stop(current);
        } catch (Throwable cleanupFailure) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(
                    cleanupFailure);
        }
        try {
            owner.activityRuntime.invalidate(current);
            owner.serviceCoordinator.stopSession(current);
            owner.receiverCoordinator.stopSession(current, reason);
            owner.providerResources.stopSession(current);
            owner.crossAbiProviderRelay.invalidateCaller(packageName, userId,
                    current.sessionId(), current.generation());
        } catch (Throwable cleanupFailure) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(
                    cleanupFailure);
        }
        RuntimeEventLog.event("GUEST_PREPARE_ROLLBACK", owner.sessionBundle(current, reason));
    }

    void stopGuest(String packageName, int userId) {
        java.util.List<GuestSession> active = new ArrayList<>(owner.sessions.getAll(packageName, userId));
        for (GuestSession session : active) stopSession(session);
        owner.isolatedProcessCoordinator.stopGuest(packageName, userId);
        owner.receiverCoordinator.invalidateInstance(packageName, userId,
                "ORDERED_RECEIVER_INSTANCE_STOPPED");
        owner.providerResources.invalidateInstance(packageName, userId);
        owner.crossAbiProviderRelay.invalidateTarget(packageName, userId);
        owner.activityRuntime.clearPackageInstance(userId, packageName);
    }

    Bundle makeSpec(Bundle input, GuestSession session) throws Exception {
        Bundle spec = new Bundle(input);
        spec.putInt(RuntimeKeys.PROTOCOL, RuntimeProtocol.CURRENT);
        spec.putString(RuntimeKeys.SESSION_ID, session.sessionId());
        spec.putLong(RuntimeKeys.GENERATION, session.generation());
        spec.putInt(RuntimeKeys.PROCESS_SLOT, session.processSlot());
        spec.putString(RuntimeKeys.PROCESS_NAME, session.processName());
        spec.putString(RuntimeKeys.PACKAGE_REVISION, session.packageRevision());
        spec.putInt(RuntimeKeys.VIRTUAL_UID,
                owner.uidRegistry().uidFor(session.packageName(), session.virtualUserId()));
        File dataRoot = new File(owner.getFilesDir(), "instances/u" + session.virtualUserId()
                + "/" + RuntimeBrokerService.safe(session.packageName()));
        if (!dataRoot.isDirectory() && !dataRoot.mkdirs() && !dataRoot.isDirectory()) {
            throw new IllegalStateException("Cannot create Guest instance root");
        }
        spec.putString(RuntimeKeys.DATA_ROOT, dataRoot.getCanonicalPath());
        if (input.getBoolean(RuntimeKeys.ISOLATED_PROCESS, false)) {
            spec.putBinder(RuntimeKeys.RUNTIME_STORAGE_BINDER,
                    owner.storageBinder.asBinder());
            spec.putBundle(RuntimeKeys.ISOLATED_FRAMEWORK_SERVICE_RELAYS,
                    FrameworkServiceRelay.capture());
            return owner.isolatedShares.prepare(spec, session);
        }
        return spec;
    }

    private static boolean syntacticallyReusableInput(Bundle input, String packageName,
                                                      int userId, String processName) {
        if (input == null || !RuntimeProtocol.isCompatible(
                input.getInt(RuntimeKeys.PROTOCOL, RuntimeProtocol.CURRENT))) return false;
        if (packageName == null || !packageName.matches(
                "[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+") || userId < 0) return false;
        return processName != null && processName.matches(
                "[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+(\\:[A-Za-z0-9_.]+)?");
    }

    private static boolean canReusePreparedArtifact(Bundle input, GuestSession existing,
                                                     Bundle cached) {
        if (existing == null || cached == null
                || !existing.packageName().equals(cached.getString(RuntimeKeys.PACKAGE_NAME, ""))
                || existing.virtualUserId() != cached.getInt(RuntimeKeys.VIRTUAL_USER_ID, -1)
                || !existing.processName().equals(RuntimeBrokerService.processName(cached,
                        cached.getString(RuntimeKeys.PACKAGE_NAME, "")))) return false;
        String sha256 = input.getString(RuntimeKeys.APK_SHA256, "");
        if (sha256 == null) return false;
        sha256 = sha256.trim().toLowerCase(java.util.Locale.ROOT);
        long versionCode = input.getLong(RuntimeKeys.APK_VERSION_CODE, -1L);
        if (versionCode < 0 || !sha256.matches("[0-9a-f]{64}")) return false;
        String revision = "v" + versionCode + ":sha256:" + sha256;
        if (!revision.equals(existing.packageRevision())
                || !revision.equals(cached.getString(RuntimeKeys.PACKAGE_REVISION, ""))) {
            return false;
        }
        String cachedSha256 = cached.getString(RuntimeKeys.APK_SHA256, "");
        if (cachedSha256 == null || !sha256.equals(
                cachedSha256.trim().toLowerCase(java.util.Locale.ROOT))) return false;
        if (cached.getLong(RuntimeKeys.APK_VERSION_CODE, -1L) != versionCode) return false;
        return sameText(input, cached, RuntimeKeys.NATIVE_GUEST_TRUST)
                && sameText(input, cached, RuntimeKeys.NATIVE_EXECUTION_MODE)
                && input.getBoolean(RuntimeKeys.NATIVE_CODE_PRESENT, false)
                    == cached.getBoolean(RuntimeKeys.NATIVE_CODE_PRESENT, false);
    }

    /**
     * NBB/VA retain the validated package/process record beyond a process death.  The CAS
     * equivalent is a broker-owned immutable artifact, keyed by the complete revision and the
     * exact virtual package-state projection.  It is not a deadline relaxation and is never
     * populated until both APK and declared-process validation have succeeded.
     */
    private static boolean canReuseValidatedArtifact(Bundle input, String packageName, int userId,
                                                     String processName, Bundle cached) {
        if (input == null || cached == null || packageName == null || packageName.isEmpty()
                || userId < 0 || input.getBoolean(RuntimeKeys.ISOLATED_PROCESS, false)) {
            return false;
        }
        if (!packageName.equals(cached.getString(RuntimeKeys.PACKAGE_NAME, ""))
                || userId != cached.getInt(RuntimeKeys.VIRTUAL_USER_ID, -1)) return false;
        String requestedProcess = RuntimeBrokerService.processName(input, packageName);
        if (requestedProcess == null || !requestedProcess.equals(processName)) return false;
        ArrayList<String> declared = cached.getStringArrayList(VALIDATED_PROCESS_NAMES);
        if (declared == null || !declared.contains(requestedProcess)) return false;
        String requestedState = packageStateFingerprint(input);
        if (requestedState.isEmpty() || !requestedState.equals(
                cached.getString(VALIDATED_STATE_FINGERPRINT, ""))) return false;
        if (!requestedState.equals(cached.getString(VALIDATED_CATALOG_GENERATION, ""))) {
            return false;
        }
        if (!sameText(input, cached, RuntimeKeys.APK_SHA256)
                || input.getLong(RuntimeKeys.APK_VERSION_CODE, -1L)
                != cached.getLong(RuntimeKeys.APK_VERSION_CODE, -1L)
                || !sameText(input, cached, RuntimeKeys.BASE_APK_SHA256)
                || !sameText(input, cached, RuntimeKeys.NATIVE_ABI)
                || !sameText(input, cached, RuntimeKeys.NATIVE_GUEST_TRUST)
                || !sameText(input, cached, RuntimeKeys.NATIVE_EXECUTION_MODE)
                || !sameText(input, cached, RuntimeKeys.APPLICATION_CLASS)
                || !sameText(input, cached, RuntimeKeys.SHARED_LIBRARIES)
                || input.getBoolean(RuntimeKeys.NATIVE_CODE_PRESENT, false)
                != cached.getBoolean(RuntimeKeys.NATIVE_CODE_PRESENT, false)) return false;
        if (!sameCanonicalPath(input, cached, RuntimeKeys.APK_PATH)
                || !sameCanonicalPath(input, cached, RuntimeKeys.NATIVE_LIBRARY_DIR)) return false;
        for (String key : VALIDATED_LIST_KEYS) {
            if (!sameStringList(input, cached, key)) return false;
        }
        if (!sameArtifactProof(input, cached)) return false;
        String requestedRevision = input.getString(RuntimeKeys.PACKAGE_REVISION, "").trim();
        if (requestedRevision.isEmpty()) {
            requestedRevision = "v" + input.getLong(RuntimeKeys.APK_VERSION_CODE, -1L)
                    + ":sha256:" + input.getString(RuntimeKeys.APK_SHA256, "").trim()
                    .toLowerCase(java.util.Locale.ROOT);
        }
        return requestedRevision.equals(cached.getString(RuntimeKeys.PACKAGE_REVISION, ""));
    }

    private static Bundle validatedArtifact(Bundle input, Set<String> declaredProcesses) {
        Bundle out = new Bundle();
        if (input.containsKey(RuntimeKeys.PROTOCOL)) {
            out.putInt(RuntimeKeys.PROTOCOL, input.getInt(RuntimeKeys.PROTOCOL));
        }
        out.putInt(RuntimeKeys.VIRTUAL_USER_ID, input.getInt(RuntimeKeys.VIRTUAL_USER_ID, -1));
        out.putLong(RuntimeKeys.APK_VERSION_CODE,
                input.getLong(RuntimeKeys.APK_VERSION_CODE, -1L));
        // Carry only the proof minted by RuntimeGuestRequestValidator.  The validator resets
        // caller input before hashing, so a replayed or forged request cannot enable this path.
        out.putBoolean(RuntimeKeys.PACKAGE_REVISION_VERIFIED_BY_BROKER,
                input.getBoolean(RuntimeKeys.PACKAGE_REVISION_VERIFIED_BY_BROKER, false));
        for (String key : VALIDATED_STRING_KEYS) copyString(input, out, key);
        for (String key : VALIDATED_LIST_KEYS) copyStringList(input, out, key);
        if (input.containsKey(RuntimeKeys.NATIVE_CODE_PRESENT)) {
            out.putBoolean(RuntimeKeys.NATIVE_CODE_PRESENT,
                    input.getBoolean(RuntimeKeys.NATIVE_CODE_PRESENT));
        }
        if (input.containsKey(RuntimeKeys.PACKAGE_STATE)) {
            out.putParcelable(RuntimeKeys.PACKAGE_STATE,
                    input.getParcelable(RuntimeKeys.PACKAGE_STATE));
        }
        String catalogGeneration = packageStateFingerprint(input);
        out.putString(VALIDATED_STATE_FINGERPRINT, catalogGeneration);
        out.putString(VALIDATED_CATALOG_GENERATION, catalogGeneration);
        putArtifactProof(out, input);
        ArrayList<String> processNames = new ArrayList<>();
        if (declaredProcesses != null) processNames.addAll(declaredProcesses);
        out.putStringArrayList(VALIDATED_PROCESS_NAMES, processNames);
        return out;
    }

    private static void applyValidatedArtifact(Bundle input, Bundle cached) {
        for (String key : VALIDATED_STRING_KEYS) copyString(cached, input, key);
        for (String key : VALIDATED_LIST_KEYS) copyStringList(cached, input, key);
        input.putInt(RuntimeKeys.VIRTUAL_USER_ID,
                cached.getInt(RuntimeKeys.VIRTUAL_USER_ID, input.getInt(RuntimeKeys.VIRTUAL_USER_ID)));
        input.putLong(RuntimeKeys.APK_VERSION_CODE,
                cached.getLong(RuntimeKeys.APK_VERSION_CODE, -1L));
        input.putBoolean(RuntimeKeys.PACKAGE_REVISION_VERIFIED_BY_BROKER,
                cached.getBoolean(RuntimeKeys.PACKAGE_REVISION_VERIFIED_BY_BROKER, false));
        input.putBoolean(RuntimeKeys.NATIVE_CODE_PRESENT,
                cached.getBoolean(RuntimeKeys.NATIVE_CODE_PRESENT, false));
        if (cached.containsKey(RuntimeKeys.PACKAGE_STATE)) {
            input.putParcelable(RuntimeKeys.PACKAGE_STATE,
                    cached.getParcelable(RuntimeKeys.PACKAGE_STATE));
        }
    }

    private static void copyString(Bundle source, Bundle target, String key) {
        if (source.containsKey(key)) target.putString(key, source.getString(key, ""));
    }

    private static void copyStringList(Bundle source, Bundle target, String key) {
        ArrayList<String> values = source.getStringArrayList(key);
        if (values != null) target.putStringArrayList(key, new ArrayList<>(values));
    }

    /**
     * Captures cheap, non-content identity for every immutable APK artifact. A cache hit must
     * never be based on mtime alone: path, byte length, and the filesystem file key are all
     * checked. Platforms that cannot provide a file key conservatively miss the cache and fall
     * back to the complete SHA verification.
     */
    private static void putArtifactProof(Bundle target, Bundle input) {
        ArrayList<String> paths = artifactPaths(input);
        ArrayList<String> sizes = new ArrayList<>();
        ArrayList<String> identities = new ArrayList<>();
        ArrayList<String> modified = new ArrayList<>();
        for (String path : paths) {
            try {
                File file = new File(path).getCanonicalFile();
                BasicFileAttributes attributes = Files.readAttributes(file.toPath(),
                        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                Object fileKey = attributes.fileKey();
                sizes.add(Long.toString(attributes.size()));
                identities.add(fileKey == null ? "" : String.valueOf(fileKey));
                modified.add(Long.toString(attributes.lastModifiedTime().toMillis()));
            } catch (Exception error) {
                sizes.add("-1");
                identities.add("");
                modified.add("-1");
            }
        }
        target.putStringArrayList(VALIDATED_ARTIFACT_PATHS, paths);
        target.putStringArrayList(VALIDATED_ARTIFACT_SIZES, sizes);
        target.putStringArrayList(VALIDATED_ARTIFACT_IDENTITIES, identities);
        target.putStringArrayList(VALIDATED_ARTIFACT_MODIFIED, modified);
    }

    private static boolean sameArtifactProof(Bundle input, Bundle cached) {
        ArrayList<String> expectedPaths = cached.getStringArrayList(VALIDATED_ARTIFACT_PATHS);
        ArrayList<String> expectedSizes = cached.getStringArrayList(VALIDATED_ARTIFACT_SIZES);
        ArrayList<String> expectedIdentities =
                cached.getStringArrayList(VALIDATED_ARTIFACT_IDENTITIES);
        ArrayList<String> expectedModified = cached.getStringArrayList(VALIDATED_ARTIFACT_MODIFIED);
        ArrayList<String> paths = artifactPaths(input);
        if (expectedPaths == null || expectedSizes == null || expectedIdentities == null
                || expectedModified == null || expectedPaths.size() != paths.size()
                || expectedSizes.size() != paths.size()
                || expectedIdentities.size() != paths.size()
                || expectedModified.size() != paths.size()) return false;
        for (int index = 0; index < paths.size(); index++) {
            try {
                File file = new File(paths.get(index)).getCanonicalFile();
                if (!file.isFile() || !file.getCanonicalPath().equals(expectedPaths.get(index))) {
                    return false;
                }
                BasicFileAttributes attributes = Files.readAttributes(file.toPath(),
                        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                Object fileKey = attributes.fileKey();
                String identity = fileKey == null ? "" : String.valueOf(fileKey);
                if (identity.isEmpty() || !identity.equals(expectedIdentities.get(index))) {
                    return false;
                }
                if (!Long.toString(attributes.size()).equals(expectedSizes.get(index))
                        || !Long.toString(attributes.lastModifiedTime().toMillis())
                        .equals(expectedModified.get(index))) return false;
            } catch (Exception error) {
                return false;
            }
        }
        return true;
    }

    private static ArrayList<String> artifactPaths(Bundle input) {
        ArrayList<String> paths = new ArrayList<>();
        String base = input.getString(RuntimeKeys.APK_PATH, "").trim();
        if (base.isEmpty()) return paths;
        try {
            paths.add(new File(base).getCanonicalPath());
        } catch (Exception error) {
            return paths;
        }
        ArrayList<String> splits = input.getStringArrayList(RuntimeKeys.SPLIT_PATHS);
        if (splits != null) {
            for (String split : splits) {
                if (split == null || split.trim().isEmpty()) return new ArrayList<>();
                try {
                    paths.add(new File(split).getCanonicalPath());
                } catch (Exception error) {
                    return new ArrayList<>();
                }
            }
        }
        return paths;
    }

    private static boolean sameStringList(Bundle left, Bundle right, String key) {
        ArrayList<String> a = left.getStringArrayList(key);
        ArrayList<String> b = right.getStringArrayList(key);
        if (a == null) a = new ArrayList<>();
        if (b == null) b = new ArrayList<>();
        return a.equals(b);
    }

    private static boolean sameCanonicalPath(Bundle left, Bundle right, String key) {
        String a = left.getString(key, "").trim();
        String b = right.getString(key, "").trim();
        if (a.isEmpty() || b.isEmpty()) return a.isEmpty() && b.isEmpty();
        try {
            return new File(a).getCanonicalPath().equals(new File(b).getCanonicalPath());
        } catch (Exception error) {
            return false;
        }
    }

    private static String validationCacheKey(Bundle input, String packageName, int userId) {
        if (input == null || packageName == null || packageName.isEmpty() || userId < 0) return "";
        String sha256 = input.getString(RuntimeKeys.APK_SHA256, "").trim()
                .toLowerCase(java.util.Locale.ROOT);
        long versionCode = input.getLong(RuntimeKeys.APK_VERSION_CODE, -1L);
        String state = packageStateFingerprint(input);
        if (versionCode < 0 || !sha256.matches("[0-9a-f]{64}") || state.isEmpty()) return "";
        String revision = input.getString(RuntimeKeys.PACKAGE_REVISION, "").trim();
        if (revision.isEmpty()) revision = "v" + versionCode + ":sha256:" + sha256;
        String material = packageName + "\n" + userId + "\n" + revision + "\n"
                + state + "\n" + input.getString(RuntimeKeys.NATIVE_GUEST_TRUST, "") + "\n"
                + input.getString(RuntimeKeys.NATIVE_EXECUTION_MODE, "") + "\n"
                + input.getBoolean(RuntimeKeys.NATIVE_CODE_PRESENT, false);
        return "validated:" + sha256Hex(material.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String packageStateFingerprint(Bundle input) {
        if (input == null || !input.containsKey(RuntimeKeys.PACKAGE_STATE)) return "";
        Object value = input.getParcelable(RuntimeKeys.PACKAGE_STATE);
        if (!(value instanceof VirtualPackageStateSnapshot state)) return "";
        Parcel parcel = Parcel.obtain();
        try {
            state.writeToParcel(parcel, 0);
            return sha256Hex(parcel.marshall());
        } catch (RuntimeException error) {
            return "";
        } finally {
            parcel.recycle();
        }
    }

    private static String sha256Hex(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte item : digest) out.append(String.format(java.util.Locale.ROOT,
                    "%02x", item & 0xff));
            return out.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA256_UNAVAILABLE", error);
        }
    }

    private static boolean sameText(Bundle left, Bundle right, String key) {
        String a = left.getString(key, "");
        String b = right.getString(key, "");
        return a != null && b != null && !a.trim().isEmpty() && a.trim().equals(b.trim());
    }

    private void stopMismatchedRevisionSessions(String packageName, int userId,
                                                String requestedRevision) {
        java.util.List<GuestSession> existing = owner.sessions.getAll(packageName, userId);
        for (GuestSession session : SessionRevisionPolicy.mismatchedLiveSessions(
                existing, requestedRevision)) {
            stopSession(session);
        }
        owner.activityRuntime.clearMismatchedRevision(userId, packageName, requestedRevision);
    }

    private static void lifecycleStage(Bundle input, GuestSession session, String stage,
                                       long started) {
        Bundle evidence = input == null ? new Bundle() : new Bundle(input);
        if (session != null) {
            evidence.putString(RuntimeKeys.SESSION_ID, session.sessionId());
            evidence.putLong(RuntimeKeys.GENERATION, session.generation());
            evidence.putInt(RuntimeKeys.PROCESS_SLOT, session.processSlot());
        }
        evidence.putString("traceDomain", "BROKER");
        evidence.putString(RuntimeKeys.LAUNCH_STAGE, stage);
        evidence.putLong(RuntimeKeys.LAUNCH_STAGE_AT_ELAPSED_MS,
                Math.max(0L, android.os.SystemClock.elapsedRealtime() - started));
        RuntimeEventLog.event("GUEST_LIFECYCLE_STAGE", evidence);
    }

    // Do not hold the Broker monitor while waiting for Guest ActivityThread teardown.  Activity
    // onDestroy/unregisterReceiver callbacks synchronously re-enter this Binder; holding the
    // monitor here turns a normal stop into a callback deadlock and then an artificial timeout.
    private void stopSession(GuestSession original) {
        GuestSession session = original;
        Throwable stopFailure = null;
        try {
            if (session.state() != SessionState.STOPPING
                    && session.state() != SessionState.STOPPED
                    && session.state() != SessionState.FAILED) {
                session = owner.sessions.transition(session.packageName(), session.virtualUserId(),
                        session.processName(), session.generation(), SessionState.STOPPING,
                        owner.now(), "");
                final GuestSession stopping = session;
                owner.guestConnections.callWithTimeoutAndAwaitDisconnect(session.processSlot(),
                        guest -> {
                            guest.shutdown(stopping.sessionId(), stopping.generation());
                            Bundle out = new Bundle();
                            out.putString(RuntimeKeys.STATUS, "STOPPED");
                            return out;
                        }, STOP_TIMEOUT_MILLIS);
                owner.sessions.transition(session.packageName(), session.virtualUserId(),
                        session.processName(), session.generation(), SessionState.STOPPED,
                        owner.now(), "");
            }
        } catch (Throwable error) {
            stopFailure = error;
            try {
                GuestSession current = owner.sessions.get(original.packageName(),
                        original.virtualUserId(), original.processName());
                if (current != null && current.state() == SessionState.STOPPING
                        && current.state().canTransitionTo(SessionState.FAILED)) {
                    // A failed death barrier is not a successful stop.  Keep the slot out of
                    // service until Android reports the actual Binder/process death rather than
                    // releasing it while an old Guest can still execute teardown callbacks.
                    owner.sessions.transition(current.packageName(), current.virtualUserId(),
                            current.processName(), current.generation(), SessionState.FAILED,
                            owner.now(), String.valueOf(error.getMessage()));
                }
            } catch (Throwable transitionError) {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(
                        transitionError);
            }
        } finally {
            owner.brokerState.removePrepared(RuntimeBrokerService.processKey(
                    original.packageName(), original.virtualUserId(), original.processName()));
            if (owner.ownershipSweep != null) {
                owner.ownershipSweep.stop(original, "ORDERED_RECEIVER_SESSION_STOPPED");
            } else {
                owner.receiverCoordinator.stopSession(original, "ORDERED_RECEIVER_SESSION_STOPPED");
                owner.activityRuntime.invalidate(original);
                owner.serviceCoordinator.stopSession(original);
                owner.providerResources.stopSession(original);
                owner.crossAbiProviderRelay.invalidateCaller(original.packageName(),
                        original.virtualUserId(), original.sessionId(), original.generation());
                if (owner.systemServiceCoordinator != null) {
                    owner.systemServiceCoordinator.stop(original);
                }
            }
            owner.releaseGuestConnection(original.processSlot());
        }
        if (stopFailure != null) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(stopFailure);
            if (stopFailure instanceof RuntimeException runtime) throw runtime;
            if (stopFailure instanceof Error fatal) throw fatal;
            throw new IllegalStateException("GUEST_STOP_FAILED", stopFailure);
        }
    }

    /**
     * Occupies ordinary slots in the SessionRegistry without starting Guest processes.
     * Used by RD high-slot transport probes so slot 31/32/62/63 can be exercised without
     * keeping 64 live APK-loaded processes.
     */
    private void padOrdinarySlots(String packageName, int userId, String packageRevision,
                                  String requestedProcess, int padCount, int slotTarget) {
        int capacity = com.warden.controlledsandbox.contract.ProcessSlotContract.ORDINARY_SLOT_COUNT;
        if (slotTarget >= 0) {
            if (!com.warden.controlledsandbox.contract.ProcessSlotContract.isOrdinarySlot(slotTarget)) {
                throw new IllegalArgumentException("SLOT_TARGET_OUT_OF_RANGE:" + slotTarget);
            }
            for (int slot = 0; slot < capacity; slot++) {
                if (slot == slotTarget) continue;
                String padProcess = packageName + ":__slot_pad_" + slot;
                if (padProcess.equals(requestedProcess)) {
                    throw new IllegalArgumentException("SLOT_PAD_COLLIDES_WITH_REQUEST");
                }
                owner.sessions.allocateExact(packageName, userId, padProcess, packageRevision,
                        slot, owner.now());
            }
            return;
        }
        if (padCount <= 0) return;
        if (padCount > capacity) {
            throw new IllegalArgumentException("SLOT_PAD_COUNT_OUT_OF_RANGE:" + padCount);
        }
        for (int index = 0; index < padCount; index++) {
            String padProcess = packageName + ":__slot_pad_" + index;
            if (padProcess.equals(requestedProcess)) {
                throw new IllegalArgumentException("SLOT_PAD_COLLIDES_WITH_REQUEST");
            }
            owner.sessions.allocateExact(packageName, userId, padProcess, packageRevision,
                    index, owner.now());
        }
    }
}
