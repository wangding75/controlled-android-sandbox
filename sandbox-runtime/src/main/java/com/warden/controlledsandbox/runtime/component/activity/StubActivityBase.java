package com.warden.controlledsandbox.runtime.component.activity;

import android.annotation.SuppressLint;
import com.warden.controlledsandbox.runtime.diagnostics.RuntimeEventLog;
import com.warden.controlledsandbox.runtime.guest.GuestPackageSpec;
import com.warden.controlledsandbox.runtime.guest.GuestRuntimeEnvironment;
import com.warden.controlledsandbox.runtime.guest.GuestActivityResultBridge;
import com.warden.controlledsandbox.runtime.guest.RouteBrokerClient;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import com.warden.controlledsandbox.framework.activity.StubActivityWindowOwnership;
import com.warden.controlledsandbox.nativebridge.NativePolicy;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.Context;
import android.content.pm.PackageManager;
import com.warden.controlledsandbox.contract.PackageServiceResult;
import com.warden.controlledsandbox.contract.VirtualPermissionSnapshot;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Gravity;
import android.widget.TextView;
import java.util.ArrayDeque;
import java.util.Deque;

public abstract class StubActivityBase extends Activity {
    private GuestActivityController controller;
    private GuestRuntimeEnvironment.Session guestSession;
    private IBinder frameworkActivityToken;
    private GuestActivityResultBridge activityResults;
    private TextView diagnostic;
    private int hostStage = 1;
    private String sessionId = "";
    private long generation;
    private String activityToken = "";
    private final Deque<Bundle> activityEvents = new ArrayDeque<>();
    private boolean activityEventInFlight;
    private boolean destroying;
    private boolean diagnosticInstalled;
    private Bundle pendingGrantedRoute;
    private Bundle pendingRouteState;
    private boolean guestCreationPosted;
    private boolean windowEvidenceEmitted;
    private final StubActivityWindowOwnership windowOwnership = new StubActivityWindowOwnership();
    private StubActivityWindowOwnership.Lease ownerLease;
    private boolean windowRecoveryRequired;
    private boolean windowWasExpected;
    private String packageName = "";
    private int virtualUserId = -1;
    private int processSlot = -1;
    private int virtualTaskId;
    private String windowLayoutToken = "";

    @Override protected void onCreate(Bundle state) {
        // This Activity is only a Host trampoline. Its FragmentManager must not restore a Guest
        // Activity's saved Fragment classes with the Host class loader after a Guest process
        // restart (for example androidx.lifecycle.ReportFragment). The original state remains
        // available to GuestActivityController below.
        super.onCreate(null);
        diagnostic = new TextView(this);
        diagnostic.setGravity(Gravity.CENTER);
        diagnostic.setPadding(32, 32, 32, 32);
        diagnostic.setText("Resolving one-time Guest route…");
        consumeInitialRoute(getIntent(), state);
    }

    private void consumeInitialRoute(Intent intent, Bundle state) {
        String token = intent.getStringExtra(RuntimeKeys.ROUTE_TOKEN);
        sessionId = value(intent.getStringExtra(RuntimeKeys.SESSION_ID));
        generation = intent.getLongExtra(RuntimeKeys.GENERATION, 0);
        if (token == null || sessionId.isEmpty() || generation < 1) {
            showFailure("INVALID_ROUTE_INTENT", "Missing route token/session/generation");
            return;
        }
        RouteBrokerClient.consume(this, token, sessionId, generation, route -> {
            if (!"ROUTE_GRANTED".equals(route.getString(RuntimeKeys.STATUS))) {
                if (isStaleRouteFailure(route.getString(RuntimeKeys.ERROR_TYPE, ""),
                        route.getString(RuntimeKeys.ERROR_MESSAGE, ""))) {
                    // Android may restore a Host trampoline after its Guest generation was
                    // stopped. The one-time route is intentionally invalid at that point;
                    // discard only this stale task and keep real launch failures observable.
                    discardStaleRouteTask();
                    return;
                }
                showFailure(route.getString(RuntimeKeys.ERROR_TYPE, "ROUTE_DENIED"),
                        route.getString(RuntimeKeys.ERROR_MESSAGE, "Route was denied"));
                return;
            }
            queueGrantedRoute(route, state);
        });
    }

    private void queueGrantedRoute(Bundle route, Bundle state) {
        if (destroying) return;
        bindWindowOwner(route);
        pendingGrantedRoute = new Bundle(route);
        pendingRouteState = state == null ? null : new Bundle(state);
        // Broker recovery may have advanced the Guest process generation while Android was
        // recreating this Host trampoline.  The returned route is authoritative; retaining the
        // stale Intent extras here would make GuestRuntimeEnvironment.require() reject a valid
        // recovered session.
        sessionId = value(route.getString(RuntimeKeys.SESSION_ID, sessionId));
        generation = route.getLong(RuntimeKeys.GENERATION, generation);
        postGuestCreationIfResumed();
    }

    private void postGuestCreationIfResumed() {
        if (pendingGrantedRoute == null || hostStage < 3 || guestCreationPosted || destroying) return;
        StubActivityWindowOwnership.Lease scheduledOwner = ownerLease;
        guestCreationPosted = true;
        // Run after onResume returns so ActivityThread can finish the window update, but do
        // not delay a full second: a splash Activity may start the real UI immediately and
        // the trampoline would otherwise be destroyed before create runs.
        new android.os.Handler(getMainLooper()).post(() -> {
            guestCreationPosted = false;
            if (pendingGrantedRoute == null || hostStage < 3 || destroying
                    || !isCurrentOwner(scheduledOwner)) return;
            Bundle route = pendingGrantedRoute;
            Bundle state = pendingRouteState;
            pendingGrantedRoute = null;
            pendingRouteState = null;
            createGuestActivity(route, state);
        });
    }

    private void createGuestActivity(Bundle route, Bundle state) {
        if (!isCurrentOwner(ownerLease)) return;
        try {
            GuestPackageSpec spec = new GuestPackageSpec(route);
            GuestRuntimeEnvironment.Session session = GuestRuntimeEnvironment.require(spec.sessionId, spec.generation);
            guestSession = session;
            activityToken = route.getString(RuntimeKeys.ACTIVITY_TOKEN, "");
            int taskId = route.getInt(RuntimeKeys.TASK_ID, 0);
            frameworkActivityToken = ActivityFieldBridge.hostToken(this);
            Bundle mappingEvidence = new Bundle();
            mappingEvidence.putString(RuntimeKeys.ROUTE_TOKEN,
                    route.getString(RuntimeKeys.ROUTE_TOKEN, ""));
            mappingEvidence.putString(RuntimeKeys.ACTIVITY_TOKEN, activityToken);
            mappingEvidence.putInt(RuntimeKeys.TASK_ID, taskId);
            mappingEvidence.putString(RuntimeKeys.PHYSICAL_ACTIVITY_COMPONENT,
                    getClass().getName());
            mappingEvidence.putString("frameworkActivityToken",
                    String.valueOf(frameworkActivityToken));
            RuntimeEventLog.event("ATMS_ACTIVITY_RECORD_MAPPING", mappingEvidence);
            session.bindActivityTaskHost(frameworkActivityToken, activityToken, taskId,
                    this::moveHostTaskToFront, this::moveHostTaskToBack,
                    this::finishHostAffinity, this::finishHostAndRemoveTask);
            controller = new GuestActivityController(this, session, activityToken, taskId,
                    this::enqueueActivityEvent);
            activityResults = new GuestActivityResultBridge(
                    this, session, activityToken, taskId);
            Intent guestIntent = com.warden.controlledsandbox.runtime.protocol.RuntimeIntentWireCodec.decode(route);
            Bundle result = controller.create(spec.componentClass, guestIntent, state);
            RuntimeEventLog.event("GUEST_ACTIVITY_CREATE", result);
            if ("ACTIVITY_CREATED".equals(result.getString(RuntimeKeys.STATUS))) {
                if (hostStage >= 2) controller.start();
                if (hostStage >= 3) controller.resume();
            } else {
                session.unbindActivityTaskHost(frameworkActivityToken);
                frameworkActivityToken = null;
                showFailure(result.getString(RuntimeKeys.ERROR_TYPE, "ACTIVITY_CREATE_FAILED"),
                        result.getString(RuntimeKeys.ERROR_MESSAGE, "Unknown failure") + "\n\n" + result.getString("stack", ""));
            }
        } catch (Throwable error) {
            IBinder failedToken = frameworkActivityToken;
            frameworkActivityToken = null;
            try {
                if (guestSession != null && failedToken != null) {
                    guestSession.unbindActivityTaskHost(failedToken);
                }
            } finally {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            }
            showFailure(error.getClass().getName(), String.valueOf(error.getMessage()));
        }
    }

    @Override protected void onStart() {
        super.onStart();
        windowWasExpected = true;
        hostStage = 2;
        if (controller != null) controller.start();
    }
    @Override protected void onResume() {
        // ActivityThread performs its final window update after this callback returns.  A few
        // framework/OEM paths remove the WMG root after onPause(), without delivering
        // onDetachedFromWindow().  Detect that state before super.onResume() so the framework
        // sees the cleared ActivityClientRecord and takes its normal addView path.
        detectMissingWindowBeforeFrameworkResume();
        recoverDetachedWindow();
        super.onResume();
        if (!diagnosticInstalled) {
            setContentView(diagnostic);
            diagnosticInstalled = true;
        }
        // ActivityThread performs its final layout/update immediately after this callback. If
        // the framework/OEM removed the previous root, establish the current Window content
        // first, then restore that same current Stub window before returning; otherwise the
        // framework can update a DecorView absent from WMG.mViews.
        hostStage = 3;
        if (controller != null) controller.resume();
        if (controller != null && activityResults != null) {
            activityResults.drain(controller::activityResult);
        }
        postWindowAttachmentObservation();
        postGuestCreationIfResumed();
    }
    @Override protected void onPause() {
        hostStage = 2;
        if (controller != null) controller.pause();
        clearMissingWindowRoot();
        super.onPause();
    }
    @Override protected void onStop() {
        hostStage = 1;
        if (controller != null) controller.stop();
        clearMissingWindowRoot();
        super.onStop();
    }
    @Override protected void onDestroy() {
        destroying = true;
        windowRecoveryRequired = false;
        if (ownerLease != null && windowOwnership.accepts(ownerLease)) {
            windowOwnership.destroy(ownerLease);
        }
        IBinder destroyedToken = frameworkActivityToken;
        frameworkActivityToken = null;
        boolean brokerFinalized = guestSession != null && destroyedToken != null
                && guestSession.consumeActivityTaskFinalized(destroyedToken);
        if (controller != null) controller.destroy(brokerFinalized);
        if (guestSession != null && destroyedToken != null) {
            guestSession.unbindActivityTaskHost(destroyedToken);
        }
        clearMissingWindowRoot();
        super.onDestroy();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String token = intent.getStringExtra(RuntimeKeys.ROUTE_TOKEN);
        if (token == null || token.trim().isEmpty()) {
            if (controller != null) controller.newIntent(intent);
            return;
        }
        String incomingSession = value(intent.getStringExtra(RuntimeKeys.SESSION_ID));
        long incomingGeneration = intent.getLongExtra(RuntimeKeys.GENERATION, 0);
        if (!sessionId.equals(incomingSession) || generation != incomingGeneration) {
            showFailure("NEW_INTENT_OWNER_MISMATCH", "New Intent belongs to another Guest generation");
            return;
        }
        StubActivityWindowOwnership.Lease callbackOwner = ownerLease;
        RouteBrokerClient.consume(this, token, sessionId, generation, route -> {
            if (!isCurrentOwner(callbackOwner)) return;
            if (!"ROUTE_GRANTED".equals(route.getString(RuntimeKeys.STATUS))) {
                if (isStaleRouteFailure(route.getString(RuntimeKeys.ERROR_TYPE, ""),
                        route.getString(RuntimeKeys.ERROR_MESSAGE, ""))) return;
                showFailure(route.getString(RuntimeKeys.ERROR_TYPE, "NEW_INTENT_ROUTE_DENIED"),
                        route.getString(RuntimeKeys.ERROR_MESSAGE, "New Intent route denied"));
                return;
            }
            String routedActivityToken = route.getString(RuntimeKeys.ACTIVITY_TOKEN, "");
            if (!activityToken.equals(routedActivityToken)) {
                showFailure("NEW_INTENT_ACTIVITY_MISMATCH", "Route targets another virtual Activity");
                return;
            }
            if (controller != null) controller.newIntent(
                    com.warden.controlledsandbox.runtime.protocol.RuntimeIntentWireCodec.decode(route));
        });
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (controller != null) controller.configurationChanged(configuration);
    }

    @Override public void onDetachedFromWindow() {
        // WindowManagerGlobal has removed this trampoline's root. Remember the ownership loss;
        // the next resume re-adds this current DecorView before ActivityThread's update branch.
        windowRecoveryRequired = !destroying;
        StubActivityWindowOwnership.Lease currentOwner = ownerLease;
        if (currentOwner != null) {
            windowOwnership.detach(currentOwner, windowIdentity());
        }
        clearWindowAddedMarker();
        logWindowEvent("STUB_WINDOW_DETACHED", "DETACHED");
        super.onDetachedFromWindow();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (controller != null) controller.activityResult(requestCode, resultCode, data);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                     int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (controller == null || permissions == null || grantResults == null
                || permissions.length != grantResults.length) return;
        int[] effective = new int[permissions.length];
        java.util.Arrays.fill(effective, PackageManager.PERMISSION_DENIED);
        reportPermissionResult(0, requestCode, permissions, grantResults, effective, ownerLease);
    }

    private void reportPermissionResult(int index, int requestCode, String[] permissions,
                                        int[] hostResults, int[] effective,
                                        StubActivityWindowOwnership.Lease callbackOwner) {
        if (!isCurrentOwner(callbackOwner)) return;
        if (index >= permissions.length) {
            if (controller != null) controller.permissionResult(requestCode, permissions, effective);
            return;
        }
        String permission = permissions[index] == null ? "" : permissions[index].trim();
        if (permission.isEmpty()) {
            reportPermissionResult(index + 1, requestCode, permissions, hostResults, effective,
                    callbackOwner);
            return;
        }
        boolean hostGranted = hostResults[index] == PackageManager.PERMISSION_GRANTED;
        RouteBrokerClient.requestPermission(this, sessionId, generation, permission, requestCode,
                requested -> {
                    if (!isCurrentOwner(callbackOwner)) return;
                    if (requested == null || !requested.successful()) {
                        reportPermissionResult(index + 1, requestCode, permissions, hostResults,
                                effective, callbackOwner);
                        return;
                    }
                    RouteBrokerClient.reportPermissionResult(this, sessionId, generation, permission,
                            requestCode, hostGranted, "host-permission-callback", result -> {
                                if (!isCurrentOwner(callbackOwner)) return;
                                boolean granted = refreshPermissionState(result, permission);
                                effective[index] = granted ? PackageManager.PERMISSION_GRANTED
                                        : PackageManager.PERMISSION_DENIED;
                                reportPermissionResult(index + 1, requestCode, permissions,
                                        hostResults, effective, callbackOwner);
                            });
                });
    }

    private boolean refreshPermissionState(PackageServiceResult result, String permission) {
        if (result == null || !result.successful() || result.packageState() == null) return false;
        try {
            GuestRuntimeEnvironment.updatePermissionState(sessionId, generation, result.packageState());
        } catch (RuntimeException error) {
            return false;
        }
        return effectiveGrant(result, permission);
    }

    private static boolean effectiveGrant(PackageServiceResult result, String permission) {
        if (result == null || !result.successful() || result.packageState() == null) return false;
        for (VirtualPermissionSnapshot snapshot : result.packageState().permissions()) {
            if (permission.equals(snapshot.name())) return snapshot.effectiveGranted();
        }
        return false;
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (controller != null) controller.saveInstanceState(state);
        super.onSaveInstanceState(state);
    }

    private synchronized void enqueueActivityEvent(String event, Bundle details) {
        if (sessionId.isEmpty() || generation < 1 || activityToken.isEmpty()) {
            throw new IllegalStateException("ACTIVITY_EVENT_IDENTITY_MISSING");
        }
        if (destroying && !"DESTROYED".equals(event)) return;
        Bundle request = new Bundle(details);
        request.putString(RuntimeKeys.SESSION_ID, sessionId);
        request.putLong(RuntimeKeys.GENERATION, generation);
        request.putString(RuntimeKeys.ACTIVITY_TOKEN, activityToken);
        request.putString(RuntimeKeys.ACTIVITY_EVENT, event);
        android.view.View decor = getWindow() == null ? null : getWindow().getDecorView();
        request.putBoolean("windowAttached", decor != null && decor.isAttachedToWindow());
        request.putBoolean("windowAddedMarker", windowAddedMarker());
        request.putBoolean("windowRegistered", decor != null && isWindowRegistered(decor));
        activityEvents.addLast(request);
        sendNextActivityEvent();
    }

    private synchronized void sendNextActivityEvent() {
        if (activityEventInFlight || activityEvents.isEmpty()) return;
        activityEventInFlight = true;
        Bundle request = activityEvents.peekFirst();
        StubActivityWindowOwnership.Lease callbackOwner = ownerLease;
        RouteBrokerClient.event(this, request, result -> {
            synchronized (StubActivityBase.this) {
                activityEventInFlight = false;
                if (!isCurrentOwner(callbackOwner)) {
                    activityEvents.clear();
                    return;
                }
                if ("ACTIVITY_EVENT_APPLIED".equals(result.getString(RuntimeKeys.STATUS))) {
                    activityEvents.removeFirst();
                    String currentToken = result.getString(RuntimeKeys.ACTIVITY_TOKEN, activityToken);
                    if (!destroying && !currentToken.isEmpty()) {
                        activityToken = currentToken;
                        if (!currentToken.equals(callbackOwner.owner().activityToken())) {
                            ownerLease = windowOwnership.replace(callbackOwner,
                                    callbackOwner.owner().withActivityToken(currentToken));
                            observeWindowOwnership();
                        }
                        if (controller != null) controller.updateActivityToken(currentToken);
                        if (activityResults != null) activityResults.updateActivityToken(currentToken);
                        if (guestSession != null && frameworkActivityToken != null) {
                            guestSession.updateActivityTaskHost(frameworkActivityToken, currentToken);
                        }
                    }
                } else {
                    activityEvents.clear();
                    if (!destroying) {
                        showFailure(result.getString(RuntimeKeys.ERROR_TYPE, "ACTIVITY_EVENT_FAILED"),
                                result.getString(RuntimeKeys.ERROR_MESSAGE, "Broker rejected Activity event"));
                    }
                }
                sendNextActivityEvent();
            }
        });
    }


    @SuppressLint("MissingPermission")
    private void moveHostTaskToFront() {
        Object service = getSystemService(Context.ACTIVITY_SERVICE);
        if (!(service instanceof ActivityManager manager)) {
            throw new IllegalStateException("HOST_ACTIVITY_MANAGER_UNAVAILABLE");
        }
        manager.moveTaskToFront(getTaskId(), 0);
    }

    private boolean moveHostTaskToBack() { return super.moveTaskToBack(true); }
    private void finishHostAffinity() { super.finishAffinity(); }
    private void finishHostAndRemoveTask() { super.finishAndRemoveTask(); }

    private void showFailure(String type, String message) {
        if (diagnostic != null) {
            // If Guest creation fails after changing the host content view, restore the
            // host-owned diagnostic view before platform teardown.
            try {
                setContentView(diagnostic);
            } catch (Throwable error) {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            }
            diagnostic.setText("Guest launch failed\n\n" + type + "\n" + message);
        }
        Bundle event = new Bundle();
        event.putString(RuntimeKeys.STATUS, "FAILED");
        event.putString(RuntimeKeys.ERROR_TYPE, type);
        event.putString(RuntimeKeys.ERROR_MESSAGE, message);
        RuntimeEventLog.event("GUEST_ACTIVITY_FAILED", event);
        if (!sessionId.isEmpty() && generation >= 1 && !activityToken.isEmpty()) {
            enqueueActivityEvent("FAILED", event);
        }
    }

    private void discardStaleRouteTask() {
        try {
            finishAndRemoveTask();
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            finish();
        }
    }

    private static boolean isStaleRouteFailure(String type, String message) {
        return containsStaleRouteCode(type) || containsStaleRouteCode(message);
    }

    private static boolean containsStaleRouteCode(String value) {
        return "SESSION_OR_GENERATION_MISMATCH".equals(value)
                || "SESSION_NOT_FOUND".equals(value)
                || "ACTIVITY_TRANSACTION_NOT_FOUND".equals(value)
                || "ACTIVITY_ROUTE_EXPIRED_OR_CONSUMED".equals(value)
                || "ACTIVITY_ROUTE_ENVELOPE_MISSING".equals(value);
    }

    private void bindWindowOwner(Bundle route) {
        packageName = value(route.getString(RuntimeKeys.PACKAGE_NAME, packageName));
        virtualUserId = route.getInt(RuntimeKeys.VIRTUAL_USER_ID, virtualUserId);
        processSlot = route.getInt(RuntimeKeys.PROCESS_SLOT, processSlot);
        String routeSession = value(route.getString(RuntimeKeys.SESSION_ID, sessionId));
        long routeGeneration = route.getLong(RuntimeKeys.GENERATION, generation);
        String routeActivityToken = value(route.getString(RuntimeKeys.ACTIVITY_TOKEN, activityToken));
        int taskId = route.getInt(RuntimeKeys.TASK_ID, 0);
        virtualTaskId = taskId;
        StubActivityWindowOwnership.Owner next = new StubActivityWindowOwnership.Owner(
                packageName, virtualUserId, routeSession, routeGeneration, processSlot,
                routeActivityToken, taskId);
        ownerLease = windowOwnership.bind(next);
        logWindowEvent("STUB_OWNER_BOUND", "DETACHED");
    }

    private boolean isCurrentOwner(StubActivityWindowOwnership.Lease candidate) {
        return candidate != null && windowOwnership.accepts(candidate);
    }

    private void clearMissingWindowRoot() {
        android.view.View decor = getWindow() == null ? null : getWindow().getDecorView();
        if (decor == null || decor.isAttachedToWindow() || isWindowRegistered(decor)) return;
        if (!destroying) windowRecoveryRequired = true;
        clearWindowAddedMarker();
        StubActivityWindowOwnership.Lease currentOwner = ownerLease;
        if (currentOwner != null) windowOwnership.detach(currentOwner, windowIdentity());
        logWindowEvent("STUB_WINDOW_ROOT_MISSING", "DETACHED");
    }

    private void detectMissingWindowBeforeFrameworkResume() {
        if (destroying || !diagnosticInstalled || !windowWasExpected) return;
        android.view.View decor = getWindow() == null ? null : getWindow().getDecorView();
        if (decor == null || !windowAddedMarker() || isWindowRegistered(decor)) return;
        windowRecoveryRequired = true;
        clearWindowAddedMarker();
        StubActivityWindowOwnership.Lease currentOwner = ownerLease;
        if (currentOwner != null) windowOwnership.detach(currentOwner, windowIdentity());
        logWindowEvent("STUB_WINDOW_ROOT_MISSING_BEFORE_RESUME", "DETACHED");
    }

    /**
     * Repairs the framework record before ActivityThread can reach updateViewLayout. The
     * framework then owns the normal r.window == null -> addView transition for this Activity.
     */
    private void recoverDetachedWindow() {
        if (!windowRecoveryRequired || destroying) return;
        android.view.Window window = getWindow();
        android.view.View decor = window == null ? null : window.getDecorView();
        if (decor == null) throw new IllegalStateException("STUB_WINDOW_DECOR_MISSING");
        logWindowEvent("STUB_WINDOW_RECOVERY_ATTEMPT", "DETACHED");
        if (!decor.isAttachedToWindow() && !isWindowRegistered(decor)) {
            android.view.WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.type = android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
            int oldSoftInputMode = attributes.softInputMode;
            attributes.softInputMode &= ~android.view.WindowManager.LayoutParams.SOFT_INPUT_IS_FORWARD_NAVIGATION;
            windowLayoutToken = String.valueOf(attributes.token);
            NativePolicy.clearDetachedActivityRecord(this);
            clearWindowAddedMarker();
            windowLayoutToken = windowLayoutToken + ";softInput=" + oldSoftInputMode
                    + "->" + attributes.softInputMode;
            logWindowEvent("STUB_WINDOW_RECORD_REPAIRED", "DETACHED");
        }
        windowRecoveryRequired = false;
        observeWindowOwnership();
    }

    private void postWindowAttachmentObservation() {
        new android.os.Handler(getMainLooper()).post(() -> {
            if (!destroying) observeWindowOwnership();
        });
    }

    private void observeWindowOwnership() {
        StubActivityWindowOwnership.Lease currentOwner = ownerLease;
        android.view.View decor = getWindow() == null ? null : getWindow().getDecorView();
        if (currentOwner == null || decor == null) return;
        String identity = windowIdentity();
        boolean registered = isWindowRegistered(decor);
        if (decor.isAttachedToWindow() || registered) {
            windowOwnership.attach(currentOwner, identity);
            emitWindowEvidenceIfNeeded(decor, registered);
        }
        logWindowEvent("STUB_WINDOW_STATE", windowOwnership.stage().name());
    }

    private void emitWindowEvidenceIfNeeded(android.view.View decor, boolean registered) {
        if (windowEvidenceEmitted || controller == null || activityToken.isEmpty()) return;
        windowEvidenceEmitted = true;
        Bundle details = new Bundle();
        details.putBoolean("windowAttached", decor != null && decor.isAttachedToWindow());
        details.putBoolean("windowRegistered", registered);
        details.putBoolean("windowAddedMarker", windowAddedMarker());
        enqueueActivityEvent("WINDOW", details);
    }

    private boolean isWindowRegistered(android.view.View decor) {
        return windowRootContains(decor);
    }

    private int windowRootCount() {
        try {
            Object windowManager = getWindowManager();
            java.lang.reflect.Field global = windowManager.getClass().getDeclaredField("mGlobal");
            global.setAccessible(true);
            Object windowManagerGlobal = global.get(windowManager);
            java.lang.reflect.Field views = windowManagerGlobal.getClass().getDeclaredField("mViews");
            views.setAccessible(true);
            Object registeredViews = views.get(windowManagerGlobal);
            if (!(registeredViews instanceof java.util.List<?>)) {
                throw new IllegalStateException("WINDOW_MANAGER_ROOT_LIST_UNAVAILABLE");
            }
            return ((java.util.List<?>) registeredViews).size();
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw new IllegalStateException("WINDOW_MANAGER_ROOT_INSPECTION_FAILED", error);
        }
    }

    private boolean windowRootContains(android.view.View decor) {
        try {
            Object windowManager = getWindowManager();
            java.lang.reflect.Field global = windowManager.getClass().getDeclaredField("mGlobal");
            global.setAccessible(true);
            Object windowManagerGlobal = global.get(windowManager);
            java.lang.reflect.Field views = windowManagerGlobal.getClass().getDeclaredField("mViews");
            views.setAccessible(true);
            Object registeredViews = views.get(windowManagerGlobal);
            if (!(registeredViews instanceof java.util.List<?>)) {
                throw new IllegalStateException("WINDOW_MANAGER_ROOT_LIST_UNAVAILABLE");
            }
            return ((java.util.List<?>) registeredViews).contains(decor);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw new IllegalStateException("WINDOW_MANAGER_ROOT_INSPECTION_FAILED", error);
        }
    }

    private void clearWindowAddedMarker() { setWindowAddedMarker(false); }

    private void setWindowAddedMarker(boolean value) {
        try {
            java.lang.reflect.Field added = Activity.class.getDeclaredField("mWindowAdded");
            added.setAccessible(true);
            added.setBoolean(this, value);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw new IllegalStateException("ACTIVITY_WINDOW_MARKER_UPDATE_FAILED", error);
        }
    }

    private String windowIdentity() {
        android.view.View decor = getWindow() == null ? null : getWindow().getDecorView();
        return decor == null ? "" : "decor@" + Integer.toHexString(System.identityHashCode(decor));
    }

    private void logWindowEvent(String event, String stage) {
        Bundle details = new Bundle();
        details.putString(RuntimeKeys.PACKAGE_NAME, packageName);
        details.putInt(RuntimeKeys.VIRTUAL_USER_ID, virtualUserId);
        details.putString(RuntimeKeys.SESSION_ID, sessionId);
        details.putLong(RuntimeKeys.GENERATION, generation);
        details.putInt(RuntimeKeys.PROCESS_SLOT, processSlot);
        details.putString(RuntimeKeys.ACTIVITY_TOKEN, activityToken);
        details.putInt(RuntimeKeys.TASK_ID, virtualTaskId);
        details.putString("virtualTask", String.valueOf(virtualTaskId));
        details.putString("frameworkTask", String.valueOf(getTaskId()));
        details.putString("frameworkActivityToken", String.valueOf(frameworkActivityToken));
        details.putString("activityClientRecord", "activity=" + getClass().getName()
                + ";frameworkToken=" + String.valueOf(frameworkActivityToken));
        details.putString("stubClass", getClass().getName());
        details.putString("windowIdentity", windowIdentity());
        details.putString("windowToken", String.valueOf(
                getWindow() == null || getWindow().getDecorView() == null
                        ? null : getWindow().getDecorView().getWindowToken()));
        details.putString("windowLayoutToken", windowLayoutToken);
        details.putBoolean("windowAttached", getWindow() != null
                && getWindow().getDecorView() != null
                && getWindow().getDecorView().isAttachedToWindow());
        int rootCount = windowRootCount();
        details.putInt("windowRootCount", rootCount);
        details.putBoolean("windowRegistered", rootCount >= 0 && getWindow() != null
                && getWindow().getDecorView() != null
                && isWindowRegistered(getWindow().getDecorView()));
        details.putString("windowStage", stage);
        details.putBoolean("windowAddedMarker", windowAddedMarker());
        details.putLong("ownerEpoch", windowOwnership.epoch());
        RuntimeEventLog.event(event, details);
    }

    private boolean windowAddedMarker() {
        try {
            java.lang.reflect.Field added = Activity.class.getDeclaredField("mWindowAdded");
            added.setAccessible(true);
            return added.getBoolean(this);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw new IllegalStateException("ACTIVITY_WINDOW_MARKER_READ_FAILED", error);
        }
    }

    private static String value(String value) { return value == null ? "" : value; }
}
