package com.warden.controlledsandbox.runtime.component.activity;

import com.warden.controlledsandbox.runtime.broker.RuntimeBrokerService;
import com.warden.controlledsandbox.runtime.guest.GuestRuntimeEnvironment;
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
    private final String activityToken;
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

    Bundle create(String componentClass, Bundle state) {
        Bundle result = new Bundle();
        try {
            Class<?> type = session.classLoader().loadClass(componentClass);
            if (!Activity.class.isAssignableFrom(type)) throw new IllegalArgumentException("Component is not an Activity: " + componentClass);
            guest = (Activity) type.getDeclaredConstructor().newInstance();
            attachBaseContext(guest);
            ActivityFieldBridge.BridgeReport bridge = ActivityFieldBridge.install(host, guest, session, componentClass);
            Thread.currentThread().setContextClassLoader(session.classLoader());
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
            emitBestEffort("DESTROYED", new Bundle());
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

    void destroy() {
        if (!created || destroyed) return;
        if (started) stop();
        invokeIfCreated("onDestroy", new Class<?>[0], new Object[0]);
        emitBestEffort("DESTROYED", new Bundle());
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

    private void emit(String event, Bundle details) {
        eventSink.emit(event, details);
    }

    private void emitBestEffort(String event, Bundle details) {
        try { emit(event, details); } catch (Throwable ignored) { }
    }

    private void addActivityRecord(Bundle result, String state) {
        result.putString(RuntimeKeys.ACTIVITY_TOKEN, activityToken);
        result.putInt(RuntimeKeys.TASK_ID, taskId);
        result.putString("activityState", state);
        result.putString("instanceId", session.instanceId());
    }

    private void attachBaseContext(Activity activity) throws Exception {
        Method attach = android.content.ContextWrapper.class.getDeclaredMethod("attachBaseContext", android.content.Context.class);
        attach.setAccessible(true);
        attach.invoke(activity, session.context());
    }

    private void invokeIfCreated(String name, Class<?>[] types, Object[] args) {
        if (guest == null) return;
        try { invokeLifecycle(guest, name, types, args); }
        catch (Throwable error) { throw new IllegalStateException("Guest lifecycle " + name + " failed", root(error)); }
    }

    private static void invokeLifecycle(Activity activity, String name, Class<?>[] types, Object[] args) throws Exception {
        Method method = findMethod(activity.getClass(), name, types);
        method.setAccessible(true);
        method.invoke(activity, args);
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
