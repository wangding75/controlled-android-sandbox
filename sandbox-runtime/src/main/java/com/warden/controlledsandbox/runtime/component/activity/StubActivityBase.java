package com.warden.controlledsandbox.runtime.component.activity;

import android.annotation.SuppressLint;
import com.warden.controlledsandbox.runtime.diagnostics.RuntimeEventLog;
import com.warden.controlledsandbox.runtime.guest.GuestPackageSpec;
import com.warden.controlledsandbox.runtime.guest.GuestRuntimeEnvironment;
import com.warden.controlledsandbox.runtime.guest.GuestActivityResultBridge;
import com.warden.controlledsandbox.runtime.guest.RouteBrokerClient;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

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
        guestCreationPosted = true;
        new android.os.Handler(getMainLooper()).postDelayed(() -> {
            guestCreationPosted = false;
            if (pendingGrantedRoute == null || hostStage < 3 || destroying) return;
            Bundle route = pendingGrantedRoute;
            Bundle state = pendingRouteState;
            pendingGrantedRoute = null;
            pendingRouteState = null;
            createGuestActivity(route, state);
        }, 1000L);
    }

    private void createGuestActivity(Bundle route, Bundle state) {
        try {
            GuestPackageSpec spec = new GuestPackageSpec(route);
            GuestRuntimeEnvironment.Session session = GuestRuntimeEnvironment.require(spec.sessionId, spec.generation);
            guestSession = session;
            activityToken = route.getString(RuntimeKeys.ACTIVITY_TOKEN, "");
            int taskId = route.getInt(RuntimeKeys.TASK_ID, 0);
            frameworkActivityToken = ActivityFieldBridge.hostToken(this);
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

    @Override protected void onStart() { super.onStart(); hostStage = 2; if (controller != null) controller.start(); }
    @Override protected void onResume() {
        super.onResume();
        // ActivityThread performs its window update immediately after this callback returns.
        // A trampoline can have a detached DecorView after another same-process Stub crosses
        // pause/stop, while the framework-side mWindowAdded marker is still true. Clear that
        // stale marker here so the framework takes its add-window path instead of updating a
        // ViewRoot that no longer exists.
        clearMissingWindowRoot();
        if (!diagnosticInstalled) {
            setContentView(diagnostic);
            diagnosticInstalled = true;
        }
        hostStage = 3;
        if (controller != null) controller.resume();
        if (controller != null && activityResults != null) {
            activityResults.drain(controller::activityResult);
        }
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
        RouteBrokerClient.consume(this, token, sessionId, generation, route -> {
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
        reportPermissionResult(0, requestCode, permissions, grantResults, effective);
    }

    private void reportPermissionResult(int index, int requestCode, String[] permissions,
                                        int[] hostResults, int[] effective) {
        if (index >= permissions.length) {
            if (controller != null) controller.permissionResult(requestCode, permissions, effective);
            return;
        }
        String permission = permissions[index] == null ? "" : permissions[index].trim();
        if (permission.isEmpty()) {
            reportPermissionResult(index + 1, requestCode, permissions, hostResults, effective);
            return;
        }
        boolean hostGranted = hostResults[index] == PackageManager.PERMISSION_GRANTED;
        RouteBrokerClient.requestPermission(this, sessionId, generation, permission, requestCode,
                requested -> {
                    if (requested == null || !requested.successful()) {
                        reportPermissionResult(index + 1, requestCode, permissions, hostResults, effective);
                        return;
                    }
                    RouteBrokerClient.reportPermissionResult(this, sessionId, generation, permission,
                            requestCode, hostGranted, "host-permission-callback", result -> {
                                boolean granted = refreshPermissionState(result, permission);
                                effective[index] = granted ? PackageManager.PERMISSION_GRANTED
                                        : PackageManager.PERMISSION_DENIED;
                                reportPermissionResult(index + 1, requestCode, permissions,
                                        hostResults, effective);
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
        activityEvents.addLast(request);
        sendNextActivityEvent();
    }

    private synchronized void sendNextActivityEvent() {
        if (activityEventInFlight || activityEvents.isEmpty()) return;
        activityEventInFlight = true;
        Bundle request = activityEvents.peekFirst();
        RouteBrokerClient.event(this, request, result -> {
            synchronized (StubActivityBase.this) {
                activityEventInFlight = false;
                if ("ACTIVITY_EVENT_APPLIED".equals(result.getString(RuntimeKeys.STATUS))) {
                    activityEvents.removeFirst();
                    String currentToken = result.getString(RuntimeKeys.ACTIVITY_TOKEN, activityToken);
                    if (!destroying && !currentToken.isEmpty()) {
                        activityToken = currentToken;
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

    /**
     * An emulator can remove a trampoline root while two same-process Stub Activities are
     * crossing pause/stop. ActivityThread can still leave mWindowAdded=true, so its later destroy
     * path calls WindowManagerGlobal.removeViewImmediate() for a root that no longer exists. Only
     * clear the local marker when the DecorView has neither an attachment nor a ViewRoot; a live
     * root keeps the platform's normal cleanup path.
     */
    private void clearMissingWindowRoot() {
        try {
            android.view.View decor = getWindow() == null ? null : getWindow().getDecorView();
            if (decor == null || decor.isAttachedToWindow() || viewRoot(decor) != null) return;
            java.lang.reflect.Field added = Activity.class.getDeclaredField("mWindowAdded");
            added.setAccessible(true);
            if (added.getBoolean(this)) added.setBoolean(this, false);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
        }
    }

    private static Object viewRoot(android.view.View view) {
        try {
            java.lang.reflect.Method method = android.view.View.class.getDeclaredMethod("getViewRootImpl");
            method.setAccessible(true);
            return method.invoke(view);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            return null;
        }
    }

    private static String value(String value) { return value == null ? "" : value; }
}
