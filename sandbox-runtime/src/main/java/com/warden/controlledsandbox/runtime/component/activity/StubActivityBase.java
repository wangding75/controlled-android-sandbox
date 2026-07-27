package com.warden.controlledsandbox.runtime.component.activity;

import com.warden.controlledsandbox.runtime.diagnostics.RuntimeEventLog;
import com.warden.controlledsandbox.runtime.guest.GuestPackageSpec;
import com.warden.controlledsandbox.runtime.guest.GuestRuntimeEnvironment;
import com.warden.controlledsandbox.runtime.guest.RouteBrokerClient;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;
import java.util.ArrayDeque;
import java.util.Deque;

public abstract class StubActivityBase extends Activity {
    private GuestActivityController controller;
    private TextView diagnostic;
    private int hostStage = 1;
    private String sessionId = "";
    private long generation;
    private String activityToken = "";
    private final Deque<Bundle> activityEvents = new ArrayDeque<>();
    private boolean activityEventInFlight;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        diagnostic = new TextView(this);
        diagnostic.setGravity(Gravity.CENTER);
        diagnostic.setPadding(32, 32, 32, 32);
        diagnostic.setText("Resolving one-time Guest route…");
        setContentView(diagnostic);
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
                showFailure(route.getString(RuntimeKeys.ERROR_TYPE, "ROUTE_DENIED"),
                        route.getString(RuntimeKeys.ERROR_MESSAGE, "Route was denied"));
                return;
            }
            try {
                GuestPackageSpec spec = new GuestPackageSpec(route);
                GuestRuntimeEnvironment.Session session = GuestRuntimeEnvironment.require(spec.sessionId, spec.generation);
                activityToken = route.getString(RuntimeKeys.ACTIVITY_TOKEN, "");
                int taskId = route.getInt(RuntimeKeys.TASK_ID, 0);
                controller = new GuestActivityController(this, session, activityToken, taskId,
                        this::enqueueActivityEvent);
                Bundle result = controller.create(spec.componentClass, state);
                RuntimeEventLog.event("GUEST_ACTIVITY_CREATE", result);
                if ("ACTIVITY_CREATED".equals(result.getString(RuntimeKeys.STATUS))) {
                    if (hostStage >= 2) controller.start();
                    if (hostStage >= 3) controller.resume();
                } else {
                    showFailure(result.getString(RuntimeKeys.ERROR_TYPE, "ACTIVITY_CREATE_FAILED"),
                            result.getString(RuntimeKeys.ERROR_MESSAGE, "Unknown failure") + "\n\n" + result.getString("stack", ""));
                }
            } catch (Throwable error) {
                showFailure(error.getClass().getName(), String.valueOf(error.getMessage()));
            }
        });
    }

    @Override protected void onStart() { super.onStart(); hostStage = 2; if (controller != null) controller.start(); }
    @Override protected void onResume() { super.onResume(); hostStage = 3; if (controller != null) controller.resume(); }
    @Override protected void onPause() { hostStage = 2; if (controller != null) controller.pause(); super.onPause(); }
    @Override protected void onStop() { hostStage = 1; if (controller != null) controller.stop(); super.onStop(); }
    @Override protected void onDestroy() { if (controller != null) controller.destroy(); super.onDestroy(); }

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
                showFailure(route.getString(RuntimeKeys.ERROR_TYPE, "NEW_INTENT_ROUTE_DENIED"),
                        route.getString(RuntimeKeys.ERROR_MESSAGE, "New Intent route denied"));
                return;
            }
            String routedActivityToken = route.getString(RuntimeKeys.ACTIVITY_TOKEN, "");
            if (!activityToken.equals(routedActivityToken)) {
                showFailure("NEW_INTENT_ACTIVITY_MISMATCH", "Route targets another virtual Activity");
                return;
            }
            if (controller != null) controller.newIntent(new Intent(intent));
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

    @Override protected void onSaveInstanceState(Bundle state) {
        if (controller != null) controller.saveInstanceState(state);
        super.onSaveInstanceState(state);
    }

    private synchronized void enqueueActivityEvent(String event, Bundle details) {
        if (sessionId.isEmpty() || generation < 1 || activityToken.isEmpty()) {
            throw new IllegalStateException("ACTIVITY_EVENT_IDENTITY_MISSING");
        }
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
                    if (!currentToken.isEmpty()) activityToken = currentToken;
                } else {
                    activityEvents.clear();
                    showFailure(result.getString(RuntimeKeys.ERROR_TYPE, "ACTIVITY_EVENT_FAILED"),
                            result.getString(RuntimeKeys.ERROR_MESSAGE, "Broker rejected Activity event"));
                }
                sendNextActivityEvent();
            }
        });
    }

    private void showFailure(String type, String message) {
        if (diagnostic != null) diagnostic.setText("Guest launch failed\n\n" + type + "\n" + message);
        Bundle event = new Bundle();
        event.putString(RuntimeKeys.STATUS, "FAILED");
        event.putString(RuntimeKeys.ERROR_TYPE, type);
        event.putString(RuntimeKeys.ERROR_MESSAGE, message);
        RuntimeEventLog.event("GUEST_ACTIVITY_FAILED", event);
    }

    private static String value(String value) { return value == null ? "" : value; }
}
