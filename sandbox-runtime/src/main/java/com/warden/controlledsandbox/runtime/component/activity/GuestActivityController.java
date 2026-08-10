package com.warden.controlledsandbox.runtime.component.activity;

import com.warden.controlledsandbox.runtime.broker.RuntimeBrokerService;
import com.warden.controlledsandbox.runtime.guest.GuestRuntimeEnvironment;
import com.warden.controlledsandbox.runtime.guest.GuestActivityResultBridge;
import com.warden.controlledsandbox.contract.ActivityResultSnapshot;
import com.warden.controlledsandbox.contract.ActivityResultIntentSnapshot;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import java.lang.reflect.Method;

/** Guest Activity bridge whose authoritative lifecycle/task state is owned by RuntimeBrokerService. */
public final class GuestActivityController {
    interface EventSink { void emit(String event, Bundle details); }

    private final Activity host;
    private final GuestRuntimeEnvironment.Session session;
    private String activityToken;
    private final int taskId;
    private final EventSink eventSink;
    private Activity guest;
    private boolean created;
    private boolean started;
    private boolean resumed;
    private boolean destroyed;
    private long savedStateVersion;

    GuestActivityController(Activity host, GuestRuntimeEnvironment.Session session,
                            String activityToken, int taskId, EventSink eventSink) {
        this.host = java.util.Objects.requireNonNull(host, "host");
        this.session = java.util.Objects.requireNonNull(session, "session");
        if (activityToken == null || activityToken.trim().isEmpty()) throw new IllegalArgumentException("activityToken is required");
        if (taskId < 1) throw new IllegalArgumentException("taskId must be positive");
        this.activityToken = activityToken;
        this.taskId = taskId;
        this.eventSink = java.util.Objects.requireNonNull(eventSink, "eventSink");
    }

    Bundle create(String componentClass, Intent launchIntent, Bundle state) {
        Bundle result = new Bundle();
        try {
            Class<?> type = session.classLoader().loadClass(componentClass);
            if (!Activity.class.isAssignableFrom(type)) throw new IllegalArgumentException("Component is not an Activity: " + componentClass);
            guest = (Activity) type.getDeclaredConstructor().newInstance();
            attachBaseContext(guest);
            ActivityFieldBridge.BridgeReport bridge = ActivityFieldBridge.install(host, guest, session, componentClass);
            guest.setIntent(launchIntent == null ? new Intent() : new Intent(launchIntent));
            invokeLifecycle(guest, "onCreate", new Class<?>[]{Bundle.class}, new Object[]{state});
            created = true;
            emit("CREATED", new Bundle());
            result.putString(RuntimeKeys.STATUS, "ACTIVITY_CREATED");
            result.putString(RuntimeKeys.COMPONENT_CLASS, componentClass);
            result.putString("bridge", "AUDITED_HOST_FIELD_BRIDGE_BROKER_LEDGER");
            result.putInt("bridgeApi", bridge.apiLevel());
            result.putInt("bridgeFieldCount", bridge.appliedFieldCount());
            result.putStringArrayList("bridgeOptionalMissing", new java.util.ArrayList<>(bridge.optionalMissingFields()));
            addActivityRecord(result, "CREATED");
        } catch (Throwable error) {
            try {
                emitBestEffort("DESTROYED", new Bundle());
            } finally {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            }
            result.putString(RuntimeKeys.STATUS, "FAILED");
            result.putString(RuntimeKeys.ERROR_TYPE, error.getClass().getName());
            result.putString(RuntimeKeys.ERROR_MESSAGE, String.valueOf(root(error).getMessage()));
            result.putString("stack", stackSummary(root(error)));
        }
        return result;
    }

    void start() {
        if (!created || started || destroyed) return;
        invokeIfCreated("onStart", new Class<?>[0], new Object[0]);
        emit("STARTED", new Bundle());
        started = true;
    }

    void resume() {
        if (!created || resumed || destroyed) return;
        if (!started) start();
        invokeIfCreated("onResume", new Class<?>[0], new Object[0]);
        emit("RESUMED", new Bundle());
        resumed = true;
    }

    void pause() {
        if (!resumed || destroyed) return;
        invokeIfCreated("onPause", new Class<?>[0], new Object[0]);
        emit("PAUSED", new Bundle());
        resumed = false;
    }

    void stop() {
        if (!started || destroyed) return;
        if (resumed) pause();
        invokeIfCreated("onStop", new Class<?>[0], new Object[0]);
        emit("STOPPED", new Bundle());
        started = false;
    }

    void destroy() { destroy(false); }

    void destroy(boolean brokerAlreadyFinalized) {
        if (!created || destroyed) return;
        if (started) stop();
        ActivityResultFieldBridge.Captured result = ActivityResultFieldBridge.capture(guest);
        invokeIfCreated("onDestroy", new Class<?>[0], new Object[0]);
        if (!brokerAlreadyFinalized) {
            if (result.explicit()) {
                Bundle details = new Bundle();
                details.putInt(RuntimeKeys.RESULT_CODE, result.resultCode());
                putResultIntent(details, GuestActivityResultBridge.snapshot(result.data()));
                emitBestEffort("FINISH_RESULT", details);
            } else {
                emitBestEffort("DESTROYED", new Bundle());
            }
        }
        destroyed = true;
        guest = null;
    }

    void newIntent(Intent intent) { invokeIfCreated("onNewIntent", new Class<?>[]{Intent.class}, new Object[]{intent}); }

    void configurationChanged(Configuration configuration) {
        invokeIfCreated("onConfigurationChanged", new Class<?>[]{Configuration.class}, new Object[]{configuration});
        Bundle details = new Bundle();
        details.putBoolean(RuntimeKeys.HANDLES_CONFIGURATION, true);
        details.putString(RuntimeKeys.CONFIGURATION_TOKEN, "host-config-" + android.os.SystemClock.elapsedRealtime());
        emit("CONFIGURATION", details);
    }

    void activityResult(int requestCode, int resultCode, Intent data) {
        invokeIfCreated("onActivityResult", new Class<?>[]{int.class, int.class, Intent.class},
                new Object[]{requestCode, resultCode, data});
    }

    void activityResult(ActivityResultSnapshot result) {
        activityResult(result.requestCode(), result.resultCode(),
                GuestActivityResultBridge.toIntent(result.resultIntent()));
    }

    synchronized void updateActivityToken(String currentToken) {
        if (currentToken == null || currentToken.trim().isEmpty()) {
            throw new IllegalArgumentException("currentToken is required");
        }
        activityToken = currentToken.trim();
    }

    void permissionResult(int requestCode, String[] permissions, int[] grantResults) {
        invokeIfCreated("onRequestPermissionsResult",
                new Class<?>[]{int.class, String[].class, int[].class},
                new Object[]{requestCode, permissions, grantResults});
    }

    void saveInstanceState(Bundle state) {
        invokeIfCreated("onSaveInstanceState", new Class<?>[]{Bundle.class}, new Object[]{state});
        Bundle details = new Bundle();
        details.putLong(RuntimeKeys.SAVED_STATE_VERSION, ++savedStateVersion);
        for (String key : state.keySet()) {
            Object value = state.get(key);
            if (value != null) details.putString(RuntimeKeys.SAVED_STATE_PREFIX + key, String.valueOf(value));
        }
        emit("SAVE_STATE", details);
    }

    private static void putResultIntent(Bundle details, ActivityResultIntentSnapshot intent) {
        details.putString(RuntimeKeys.RESULT_INTENT_ACTION, intent.action());
        details.putString(RuntimeKeys.RESULT_INTENT_DATA, intent.dataUri());
        details.putString(RuntimeKeys.RESULT_INTENT_TYPE, intent.mimeType());
        details.putString(RuntimeKeys.RESULT_INTENT_COMPONENT, intent.componentName());
        details.putInt(RuntimeKeys.RESULT_INTENT_FLAGS, intent.flags());
        details.putString(RuntimeKeys.RESULT_INTENT_CLIP, intent.clipDescription());
        for (java.util.Map.Entry<String, String> entry : intent.extras().entrySet()) {
            details.putString(RuntimeKeys.RESULT_INTENT_EXTRA_PREFIX + entry.getKey(), entry.getValue());
        }
    }

    private void emit(String event, Bundle details) {
        eventSink.emit(event, details);
    }

    private void emitBestEffort(String event, Bundle details) {
        try { emit(event, details); } catch (Throwable ignored) { com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(ignored); }
    }

    private void addActivityRecord(Bundle result, String state) {
        result.putString(RuntimeKeys.ACTIVITY_TOKEN, activityToken);
        result.putInt(RuntimeKeys.TASK_ID, taskId);
        result.putString("activityState", state);
        result.putString("instanceId", session.instanceId());
    }

    private void attachBaseContext(Activity activity) throws Exception {
        // Calling Activity.attachBaseContext on API 32 invokes the hidden Autofill client
        // hand-off through ContextWrapper. GuestContext intentionally has no Host base, so that
        // hand-off would dereference null. Temporarily provide only the Host transport for this
        // framework attach operation, then restore the detached Guest Context boundary before any
        // Guest lifecycle callback can observe it.
        java.lang.reflect.Field base = android.content.ContextWrapper.class.getDeclaredField("mBase");
        base.setAccessible(true);
        android.content.Context hostContext = host.getBaseContext();
        Method attach = android.app.Activity.class.getDeclaredMethod(
                "attachBaseContext", android.content.Context.class);
        attach.setAccessible(true);
        attach.invoke(activity, hostContext);
        // Activity.attachBaseContext has now initialized the framework-owned Activity state.
        // Replace the base with the Guest Context. Its framework-only bridge supplies the
        // Activity-owned theme/display queries without exposing the Host Context through
        // GuestContext.getBaseContext().
        base.set(session.context(), new com.warden.controlledsandbox.runtime.guest.GuestActivityBaseContext(hostContext));
        base.set(activity, session.context());
    }

    private void invokeIfCreated(String name, Class<?>[] types, Object[] args) {
        if (guest == null) return;
        try { invokeLifecycle(guest, name, types, args); }
        catch (Throwable error) { com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error); throw new IllegalStateException("Guest lifecycle " + name + " failed", root(error)); }
    }

    private void invokeLifecycle(Activity activity, String name, Class<?>[] types, Object[] args) throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(session.classLoader());
            Method method = findMethod(activity.getClass(), name, types);
            method.setAccessible(true);
            method.invoke(activity, args);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private static Method findMethod(Class<?> type, String name, Class<?>[] parameters) throws NoSuchMethodException {
        Class<?> cursor = type;
        while (cursor != null) {
            try { return cursor.getDeclaredMethod(name, parameters); }
            catch (NoSuchMethodException ignored) { cursor = cursor.getSuperclass(); }
        }
        throw new NoSuchMethodException(name);
    }

    private static Throwable root(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current;
    }

    private static String stackSummary(Throwable error) {
        StringBuilder out = new StringBuilder().append(error).append('\n');
        for (int i = 0; i < Math.min(error.getStackTrace().length, 24); i++) out.append("  at ").append(error.getStackTrace()[i]).append('\n');
        return out.toString();
    }
}
