package com.warden.controlledsandbox.runtime.broker;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import com.warden.controlledsandbox.contract.PackageArtifactSnapshot;
import com.warden.controlledsandbox.contract.PackageRecordSnapshot;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
import com.warden.controlledsandbox.domain.component.provider.UriGrantRegistry;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.session.SessionRegistry;
import com.warden.controlledsandbox.domain.session.SessionState;
import com.warden.controlledsandbox.domain.identity.VirtualUidRegistry;
import com.warden.controlledsandbox.framework.identity.VirtualPackageUniverse;
import com.warden.controlledsandbox.runtime.component.activity.BrokerActivityRuntime;
import com.warden.controlledsandbox.runtime.guest.GuestLaunchObservation;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import com.warden.controlledsandbox.runtime.provider.BrokerProviderRuntime;
import com.warden.controlledsandbox.domain.persistence.DurableAtomicFile;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class RuntimeBrokerResourceOperationCoordinator {
    private final RuntimeBrokerService owner;
    private final SessionRegistry sessions;
    private final UriGrantRegistry uriGrants;
    private final BrokerProviderRuntime providerRuntime;
    private final BrokerActivityRuntime activityRuntime;
    private final ConcurrentHashMap<String, GuestLaunchObservation> launchObservations;

    RuntimeBrokerResourceOperationCoordinator(RuntimeBrokerService owner) {
        this.owner = owner;
        this.sessions = owner.sessions;
        this.uriGrants = owner.uriGrants;
        this.providerRuntime = owner.providerRuntime;
        this.activityRuntime = owner.activityRuntime;
        this.launchObservations = owner.launchObservations;
    }

    Bundle grantUriPermission(Bundle request) {
        CallerGuard.requireRuntimePeer(owner);
        try {
            if (request == null) throw new IllegalArgumentException("request is required");
            String ownerPackage = required(request, RuntimeKeys.PACKAGE_NAME);
            int ownerUser = request.getInt(RuntimeKeys.VIRTUAL_USER_ID, -1);
            String targetPackage = required(request, RuntimeKeys.TARGET_PACKAGE_NAME);
            int targetUser = request.getInt(RuntimeKeys.TARGET_VIRTUAL_USER_ID, -1);
            if (ownerUser < 0 || targetUser < 0) {
                throw new IllegalArgumentException("virtual user ids must be non-negative");
            }
            if (ownerUser != targetUser) throw new SecurityException("URI_GRANT_CROSS_USER_DENIED");
            GuestSession ownerSession = requireCurrentGuestSession(request, ownerPackage, ownerUser);
            requireInstalledVirtualPackage(targetPackage, targetUser);
            String uri = required(request, RuntimeKeys.URI);
            providerRuntime.requireGrantOwner(uri, ownerUser,
                    ownerKey(ownerPackage, ownerUser));
            int flags = request.getInt(RuntimeKeys.URI_FLAGS, 0);
            long ttlMs = request.getLong(RuntimeKeys.URI_GRANT_TTL_MS,
                    UriGrantRegistry.DURABLE_TTL_MS);
            boolean oneTime = request.getBoolean(RuntimeKeys.URI_GRANT_ONE_TIME, false);
            UriGrantRegistry.Grant grant = uriGrants.grantForTargetInstance(
                    ownerKey(ownerPackage, ownerUser), ownerSession.sessionId(), ownerSession.generation(),
                    ownerKey(targetPackage, targetUser), targetUser, uri, flags, oneTime, now(), ttlMs);
            Bundle out = new Bundle();
            out.putString(RuntimeKeys.STATUS, "URI_PERMISSION_GRANTED");
            out.putString(RuntimeKeys.URI_GRANT_ID, grant.id());
            out.putLong(RuntimeKeys.URI_GRANT_EXPIRES_AT, grant.expiresAtMs());
            out.putInt(RuntimeKeys.URI_FLAGS, grant.flags());
            out.putBoolean(RuntimeKeys.URI_GRANT_ONE_TIME, grant.oneTime());
            out.putString(RuntimeKeys.URI_GRANT_TARGET_SESSION_ID, grant.targetSessionId());
            out.putLong(RuntimeKeys.URI_GRANT_TARGET_GENERATION, grant.targetGeneration());
            return out;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            android.util.Log.e("CS_ACTIVITY_EVENT", "activity event rejected", error);
            return failure(error);
        }
    }

    Bundle revokeUriPermission(Bundle request) {
        CallerGuard.requireRuntimePeer(owner);
        try {
            if (request == null) throw new IllegalArgumentException("request is required");
            String ownerPackage = required(request, RuntimeKeys.PACKAGE_NAME);
            int ownerUser = request.getInt(RuntimeKeys.VIRTUAL_USER_ID, -1);
            GuestSession ownerSession = requireCurrentGuestSession(request, ownerPackage, ownerUser);
            String grantId = request.getString(RuntimeKeys.URI_GRANT_ID, "").trim();
            int revokedCount;
            if (!grantId.isEmpty()) {
                UriGrantRegistry.Grant grant = uriGrants.require(grantId, now());
                boolean revoked = uriGrants.revoke(grantId, ownerKey(ownerPackage, ownerUser),
                        ownerSession.sessionId(), ownerSession.generation(), now());
                revokedCount = revoked ? 1 : 0;
            } else {
                String uri = required(request, RuntimeKeys.URI);
                int flags = request.getInt(RuntimeKeys.URI_FLAGS, 0);
                revokedCount = uriGrants.revokeOwned(ownerKey(ownerPackage, ownerUser),
                        uri, flags, now());
            }
            Bundle out = new Bundle();
            out.putString(RuntimeKeys.STATUS, revokedCount > 0
                    ? "URI_PERMISSION_REVOKED" : "URI_PERMISSION_NOT_FOUND");
            out.putInt("uriPermissionRevokedCount", revokedCount);
            return out;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            return failure(error);
        }
    }

    Bundle checkUriPermission(Bundle request) {
        CallerGuard.requireRuntimePeer(owner);
        try {
            if (request == null) throw new IllegalArgumentException("request is required");
            String packageName = required(request, RuntimeKeys.PACKAGE_NAME);
            int userId = request.getInt(RuntimeKeys.VIRTUAL_USER_ID, -1);
            GuestSession caller = requireCurrentGuestSession(request, packageName, userId);
            String uri = required(request, RuntimeKeys.URI);
            int flags = request.getInt(RuntimeKeys.URI_FLAGS, 0);
            int requestedUid = request.getInt(RuntimeKeys.URI_CHECK_UID, -1);
            int virtualUid = uidRegistry().uidFor(packageName, userId);
            if (requestedUid >= 0 && requestedUid != virtualUid
                    && requestedUid != owner.getApplicationInfo().uid) {
                return uriPermissionResult(android.content.pm.PackageManager.PERMISSION_DENIED,
                        "URI_CHECK_UID_MISMATCH");
            }
            String authority = providerAuthorityFromUri(uri);
            Bundle providerRequest = new Bundle(request);
            providerRequest.putString(ComponentOperations.OPERATION,
                    ComponentOperations.PROVIDER_QUERY);
            providerRequest.putString(ComponentOperations.AUTHORITY, authority);
            providerRequest.putString(RuntimeKeys.URI, uri);
            String callerInstance = providerRuntime.requireCallerInstance(providerRequest,
                    packageName, userId, this::sessionById);
            UriGrantRegistry.Authorization authorization = uriGrants.beginAuthorization(
                    callerInstance, caller.sessionId(), caller.generation(), userId, now());
            BrokerProviderRuntime.OperationRoute route = null;
            try {
                route = providerRuntime.routeOperation(providerRequest,
                        ComponentOperations.PROVIDER_QUERY, callerInstance, userId, "",
                        authorization::allows,
                        permission -> callerPermissions(providerRequest).contains(permission), now());
                Bundle result = uriPermissionResult(
                        android.content.pm.PackageManager.PERMISSION_GRANTED,
                        route.permissionBasis());
                providerRuntime.completeOperation(route, result, now());
                return result;
            } catch (Throwable error) {
                if (route != null) providerRuntime.failOperation(route, error, now());
                return uriPermissionResult(android.content.pm.PackageManager.PERMISSION_DENIED,
                        error.getClass().getSimpleName());
            }
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            return failure(error);
        }
    }

    private Bundle uriPermissionResult(int result, String basis) {
        Bundle out = new Bundle();
        out.putString(RuntimeKeys.STATUS, "URI_PERMISSION_CHECKED");
        out.putInt(RuntimeKeys.URI_PERMISSION_RESULT, result);
        out.putString(RuntimeKeys.PROVIDER_PERMISSION_BASIS, basis == null ? "" : basis);
        return out;
    }

    /**
     * Opens a read-only APK capability for a visible virtual package.  Guest code must never
     * receive the host pathname and then ask AssetManager/native open() to cross the current
     * package boundary: NativePolicy intentionally rejects that path.  The Broker therefore
     * revalidates visibility and the installed revision, opens the artifacts itself, and hands
     * only bounded file capabilities to the requesting Guest process.
     */
    Bundle openPackageResources(Bundle request) {
        CallerGuard.requireRuntimePeer(owner);
        ArrayList<ParcelFileDescriptor> opened = new ArrayList<>();
        try {
            if (request == null) throw new IllegalArgumentException("request is required");
            String callerPackage = required(request, RuntimeKeys.PACKAGE_NAME);
            int userId = request.getInt(RuntimeKeys.VIRTUAL_USER_ID, -1);
            GuestSession caller = requireCurrentGuestSession(request, callerPackage, userId);
            String targetPackage = required(request, RuntimeKeys.PACKAGE_RESOURCE_TARGET);
            if (callerPackage.equals(targetPackage)) {
                throw new IllegalArgumentException("own package resources use the current context");
            }
            PackageRecordSnapshot record = owner.packageAuthority.findRecord(targetPackage);
            if (record == null || !targetPackage.equals(record.packageName())) {
                throw new SecurityException("PACKAGE_RESOURCE_TARGET_NOT_INSTALLED:" + targetPackage);
            }
            VirtualPackageStateSnapshot targetState = owner.packageAuthority.virtualPackageState(
                    targetPackage, userId);
            if (targetState == null || !targetPackage.equals(targetState.packageName())
                    || targetState.virtualUserId() != userId || !targetState.enabled()) {
                throw new SecurityException("PACKAGE_RESOURCE_TARGET_STATE_INVALID:" + targetPackage);
            }
            VirtualPackageUniverse universe = crossPackageUniverse(request, caller,
                    targetPackage, targetState);
            if (!universe.isVisibleTo(callerPackage, targetPackage)) {
                throw new SecurityException("PACKAGE_RESOURCE_TARGET_NOT_VISIBLE:" + targetPackage);
            }
            ParcelFileDescriptor base = null;
            ArrayList<ParcelFileDescriptor> splits = new ArrayList<>();
            for (PackageArtifactSnapshot artifact : record.artifacts()) {
                if (artifact == null || artifact.path() == null || artifact.path().trim().isEmpty()) {
                    throw new SecurityException("PACKAGE_RESOURCE_ARTIFACT_INVALID:" + targetPackage);
                }
                ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(
                        new File(artifact.path()), ParcelFileDescriptor.MODE_READ_ONLY);
                opened.add(descriptor);
                if (artifact.base()) base = descriptor;
                else splits.add(descriptor);
            }
            if (base == null) throw new SecurityException("PACKAGE_RESOURCE_BASE_MISSING:" + targetPackage);
            Bundle out = new Bundle();
            out.putString(RuntimeKeys.STATUS, "PACKAGE_RESOURCES_OPENED");
            out.putString(RuntimeKeys.PACKAGE_RESOURCE_TARGET, targetPackage);
            out.putParcelable(RuntimeKeys.PACKAGE_RESOURCE_APK_FD, base);
            out.putParcelableArrayList(RuntimeKeys.PACKAGE_RESOURCE_SPLIT_FDS, splits);
            // The returned Bundle owns the descriptors after the Binder transfer.  Do not close
            // them on the success path; the Guest duplicates them into AssetManager/Dex buffers.
            opened.clear();
            return out;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            return failure(error);
        } finally {
            for (ParcelFileDescriptor descriptor : opened) {
                try { descriptor.close(); } catch (Throwable ignored) { }
            }
        }
    }

    Bundle consumeRoute(String token, String sessionId, long generation) {
        CallerGuard.requireRuntimePeer(owner);
        try {
            GuestSession current = sessionById(sessionId, generation);
            if (current == null) current = latestSessionById(sessionId);
            if (current == null) throw new SecurityException("SESSION_OR_GENERATION_MISMATCH");
            if (current.state() == SessionState.RECOVERING) {
                // ActivityManager can recreate the Stub Activity before the replacement Guest
                // generation has been prepared.  Recover from the broker-owned route envelope;
                // this is the same generic prepare path used by launchActivity.
                Bundle route = activityRuntime.routeForPreparation(token);
                if (route == null) throw new IllegalStateException("ACTIVITY_ROUTE_NOT_FOUND");
                Bundle prepared = prepareGuestInternal(route);
                if (!isPrepared(prepared)) {
                    throw new IllegalStateException(prepared.getString(
                            RuntimeKeys.ERROR_TYPE, "GUEST_RECOVERY_FAILED"));
                }
                current = findSession(sessionId,
                        prepared.getLong(RuntimeKeys.GENERATION, current.generation()));
            }
            Bundle payload = activityRuntime.consume(token, current);
            if (current.state() == SessionState.READY) {
                current = sessions.transition(current.packageName(), current.virtualUserId(), current.processName(),
                        current.generation(), SessionState.ACTIVE, now(), "");
            }
            payload.putString(RuntimeKeys.STATUS, "ROUTE_GRANTED");
            return payload;
        } catch (Throwable error) {
            try {
                activityRuntime.launchFailed(token);
            } finally {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            }
            return failure(error);
        }
    }

    Bundle activityEvent(Bundle request) {
        CallerGuard.requireRuntimePeer(owner);
        try {
            if (request == null) throw new IllegalArgumentException("request is required");
            String activityToken = request.getString(RuntimeKeys.ACTIVITY_TOKEN, "");
            for (GuestLaunchObservation observation : new java.util.LinkedHashSet<>(
                    launchObservations.values())) {
                if (!activityToken.isEmpty() && !observation.acceptsActivityToken(activityToken)) continue;
                observation.onActivityEvent(request);
            }
            if ("FAILED".equals(request.getString(RuntimeKeys.ACTIVITY_EVENT, ""))) {
                Bundle acknowledged = new Bundle();
                acknowledged.putString(RuntimeKeys.STATUS, "ACTIVITY_EVENT_APPLIED");
                acknowledged.putString(RuntimeKeys.ACTIVITY_TOKEN, activityToken);
                return acknowledged;
            }
            GuestSession current = findSession(required(request, RuntimeKeys.SESSION_ID),
                    request.getLong(RuntimeKeys.GENERATION, -1));
            return activityRuntime.event(current, request);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            android.util.Log.e("CS_ACTIVITY_EVENT", "activity event rejected", error);
            return failure(error);
        }
    }

    Bundle storageOperation(Bundle request, String sessionId, long generation) {
        CallerGuard.requireRuntimePeer(owner);
        return performStorageOperation(request, sessionId, generation);
    }

    Bundle performStorageOperation(Bundle request, String sessionId, long generation) {
        try {
            if (request == null) throw new IllegalArgumentException("request is required");
            GuestSession session = findStorageSession(sessionId, generation);
            String packageName = required(request, RuntimeKeys.PACKAGE_NAME);
            int virtualUserId = request.getInt(RuntimeKeys.VIRTUAL_USER_ID, -1);
            if (!session.packageName().equals(packageName)
                    || session.virtualUserId() != virtualUserId) {
                throw new SecurityException("ISOLATED_STORAGE_SESSION_IDENTITY_MISMATCH");
            }
            if (session.state() != SessionState.PREPARING
                    && session.state() != SessionState.READY
                    && session.state() != SessionState.ACTIVE) {
                throw new SecurityException("ISOLATED_STORAGE_SESSION_NOT_OPERATIONAL");
            }
            String operation = required(request, RuntimeKeys.STORAGE_OPERATION);
            String name = required(request, RuntimeKeys.STORAGE_NAME);
            validateIsolatedStorageName(name);
            boolean deviceProtected = request.getBoolean(RuntimeKeys.STORAGE_DEVICE_PROTECTED, false);
            File root = isolatedPreferencesRoot(session, deviceProtected);
            File target = new File(root, name).getCanonicalFile();
            if (!target.toPath().startsWith(root.toPath())) {
                throw new SecurityException("ISOLATED_STORAGE_PATH_ESCAPE");
            }
            Bundle result = new Bundle();
            result.putString(RuntimeKeys.STATUS, "STORAGE_OK");
            if ("read".equals(operation)) {
                if (!target.isFile()) {
                    result.putBoolean(RuntimeKeys.STORAGE_EXISTS, false);
                    return result;
                }
                if (target.length() > 16L * 1024L * 1024L) {
                    throw new IOException("isolated preferences file is too large");
                }
                result.putBoolean(RuntimeKeys.STORAGE_EXISTS, true);
                result.putByteArray(RuntimeKeys.STORAGE_DATA,
                        java.nio.file.Files.readAllBytes(target.toPath()));
                android.util.Log.i("CS_STORAGE", "read package=" + packageName + " name=" + name
                        + " bytes=" + target.length());
                return result;
            }
            if ("write".equals(operation)) {
                byte[] data = request.getByteArray(RuntimeKeys.STORAGE_DATA);
                if (data == null || data.length > 16 * 1024 * 1024) {
                    throw new IllegalArgumentException("isolated preferences payload is invalid");
                }
                DurableAtomicFile.writeAcknowledged(target.toPath(), data);
                result.putBoolean(RuntimeKeys.STORAGE_SUCCESS, true);
                android.util.Log.i("CS_STORAGE", "write package=" + packageName + " name=" + name
                        + " bytes=" + data.length);
                return result;
            }
            if ("delete".equals(operation)) {
                java.nio.file.Files.deleteIfExists(target.toPath());
                java.nio.file.Files.deleteIfExists(new File(root, name + ".tmp").toPath());
                result.putBoolean(RuntimeKeys.STORAGE_SUCCESS, true);
                android.util.Log.i("CS_STORAGE", "delete package=" + packageName + " name=" + name);
                return result;
            }
            throw new IllegalArgumentException("ISOLATED_STORAGE_OPERATION_UNKNOWN:" + operation);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            android.util.Log.e("CS_STORAGE", "storage operation failed", error);
            return failure(error);
        }
    }

    Bundle performStorageMove(Bundle request, String sessionId, long generation) {
        try {
            if (request == null) throw new IllegalArgumentException("request is required");
            GuestSession session = findStorageSession(sessionId, generation);
            String packageName = required(request, RuntimeKeys.PACKAGE_NAME);
            int virtualUserId = request.getInt(RuntimeKeys.VIRTUAL_USER_ID, -1);
            if (!session.packageName().equals(packageName)
                    || session.virtualUserId() != virtualUserId) {
                throw new SecurityException("ISOLATED_STORAGE_SESSION_IDENTITY_MISMATCH");
            }
            if (session.state() != SessionState.PREPARING
                    && session.state() != SessionState.READY
                    && session.state() != SessionState.ACTIVE) {
                throw new SecurityException("ISOLATED_STORAGE_SESSION_NOT_OPERATIONAL");
            }
            String sourceName = required(request, RuntimeKeys.STORAGE_NAME);
            String targetName = required(request, RuntimeKeys.STORAGE_TARGET_NAME);
            validateIsolatedStorageName(sourceName);
            validateIsolatedStorageName(targetName);
            boolean sourceDeviceProtected = request.getBoolean(
                    RuntimeKeys.STORAGE_SOURCE_DEVICE_PROTECTED, false);
            boolean targetDeviceProtected = request.getBoolean(
                    RuntimeKeys.STORAGE_TARGET_DEVICE_PROTECTED, false);
            File sourceRoot = isolatedPreferencesRoot(session, sourceDeviceProtected);
            File targetRoot = isolatedPreferencesRoot(session, targetDeviceProtected);
            File source = containedStorageFile(sourceRoot, sourceName);
            File target = containedStorageFile(targetRoot, targetName);
            Bundle result = new Bundle();
            result.putString(RuntimeKeys.STATUS, "STORAGE_OK");
            if (!source.isFile() || target.exists()) {
                result.putBoolean(RuntimeKeys.STORAGE_SUCCESS, false);
                return result;
            }
            DurableAtomicFile.moveAcknowledged(source.toPath(), target.toPath());
            result.putBoolean(RuntimeKeys.STORAGE_SUCCESS, true);
            android.util.Log.i("CS_STORAGE", "move package=" + packageName + " source="
                    + sourceName + " target=" + targetName + " sourceDevice="
                    + sourceDeviceProtected + " targetDevice=" + targetDeviceProtected);
            return result;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            android.util.Log.e("CS_STORAGE", "storage move failed", error);
            return failure(error);
        }
    }

    File isolatedPreferencesRoot(GuestSession session, boolean deviceProtected)
            throws IOException {
        File instance = new File(owner.getFilesDir(), "instances/u" + session.virtualUserId()
                + "/" + safe(session.packageName())).getCanonicalFile();
        File root = new File(instance, (deviceProtected ? "device_protected" : "data")
                + "/shared_prefs").getCanonicalFile();
        File privateRoot = owner.getFilesDir().getCanonicalFile();
        if (!root.toPath().startsWith(privateRoot.toPath())) {
            throw new SecurityException("ISOLATED_STORAGE_ROOT_ESCAPE");
        }
        if (!root.isDirectory() && !root.mkdirs() && !root.isDirectory()) {
            throw new IOException("cannot create isolated storage root: " + root);
        }
        return root;
    }

    private static File containedStorageFile(File root, String name) throws IOException {
        File target = new File(root, name).getCanonicalFile();
        if (!target.toPath().startsWith(root.toPath())) {
            throw new SecurityException("ISOLATED_STORAGE_PATH_ESCAPE");
        }
        return target;
    }

    private static void validateIsolatedStorageName(String name) {
        if (name.length() > 256 || name.contains("/") || name.contains("\\")
                || !name.endsWith(".cspf")
                || !(name.matches("v2_[A-Za-z0-9_-]+\\.cspf")
                || name.matches("v2h_[0-9a-fA-F]{64}\\.cspf"))) {
            throw new SecurityException("ISOLATED_STORAGE_NAME_INVALID");
        }
    }


    private GuestSession requireCurrentGuestSession(Bundle request, String packageName, int userId) {
        return owner.requireCurrentGuestSession(request, packageName, userId);
    }

    private void requireInstalledVirtualPackage(String packageName, int virtualUserId)
            throws Exception {
        owner.requireInstalledVirtualPackage(packageName, virtualUserId);
    }

    private VirtualPackageUniverse crossPackageUniverse(Bundle request, GuestSession caller,
                                                        String targetPackage,
                                                        VirtualPackageStateSnapshot targetState) {
        return owner.crossPackageUniverse(request, caller, targetPackage, targetState);
    }

    private GuestSession sessionById(String sessionId, long generation) {
        return owner.sessionById(sessionId, generation);
    }

    private GuestSession findSession(String sessionId, long generation) {
        return owner.findSession(sessionId, generation);
    }

    private GuestSession latestSessionById(String sessionId) {
        return owner.latestSessionById(sessionId);
    }

    private GuestSession findStorageSession(String sessionId, long generation) {
        return owner.findStorageSession(sessionId, generation);
    }

    private Bundle prepareGuestInternal(Bundle request) {
        return owner.prepareGuestInternal(request);
    }

    private VirtualUidRegistry uidRegistry() {
        return owner.uidRegistry();
    }

    private long now() {
        return owner.now();
    }

    private static String required(Bundle bundle, String key) {
        return RuntimeBrokerService.required(bundle, key);
    }

    private static String ownerKey(String packageName, int userId) {
        return RuntimeBrokerService.ownerKey(packageName, userId);
    }

    private static String providerAuthorityFromUri(String uri) {
        return RuntimeBrokerService.providerAuthorityFromUri(uri);
    }

    private static Set<String> callerPermissions(Bundle request) {
        return RuntimeBrokerService.callerPermissions(request);
    }

    private static boolean isPrepared(Bundle bundle) {
        return RuntimeBrokerService.isPrepared(bundle);
    }

    private static String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static Bundle failure(Throwable error) {
        return RuntimeBrokerService.failure(error);
    }

    private static Bundle failure(String type, String message) {
        return RuntimeBrokerService.failure(type, message);
    }

}
