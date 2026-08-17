package com.warden.controlledsandbox.runtime.component.activity;

import com.warden.controlledsandbox.runtime.broker.RuntimeBrokerService;
import com.warden.controlledsandbox.runtime.guest.GuestRuntimeEnvironment;
import com.warden.controlledsandbox.runtime.guest.GuestActivityResultBridge;
import com.warden.controlledsandbox.runtime.guest.GuestComponentFactory;
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
            String instantiateComponent = activityInstantiationClass(componentClass);
            Class<?> type = session.classLoader().loadClass(instantiateComponent);
            com.warden.controlledsandbox.runtime.guest.GuestNativeBindingDiagnostic.recordClass(
                    "activity." + instantiateComponent, type);
            if (!Activity.class.isAssignableFrom(type)) throw new IllegalArgumentException("Component is not an Activity: " + componentClass);
            guest = GuestComponentFactory.instantiateActivity(session.context().getClassLoader(),
                    session.context().getApplicationInfo().appComponentFactory,
                    instantiateComponent, launchIntent == null ? new Intent() : new Intent(launchIntent));
            attachFrameworkState(guest, componentClass);
            ActivityFieldBridge.BridgeReport bridge = ActivityFieldBridge.install(
                    host, guest, session, componentClass, taskId);
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
            com.warden.controlledsandbox.runtime.diagnostics.RuntimeEventLog.failure(
                    "GUEST_ACTIVITY_CREATE", root(error));
        }
        return result;
    }

    private String activityInstantiationClass(String componentClass) {
        if (componentClass == null || componentClass.trim().isEmpty()) return componentClass;
        com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata.Component metadata =
                session.packageMetadata().component(componentClass,
                        com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata.Type.ACTIVITY);
        return metadata == null || metadata.targetActivity().isEmpty()
                ? componentClass : metadata.targetActivity();
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
        resumed = false;
        if (!invokeIfCreated("onPause", new Class<?>[0], new Object[0])) return;
        emit("PAUSED", new Bundle());
    }

    void stop() {
        if (!started || destroyed) return;
        if (resumed) pause();
        started = false;
        if (destroyed) return;
        if (!invokeIfCreated("onStop", new Class<?>[0], new Object[0])) return;
        emit("STOPPED", new Bundle());
    }

    void destroy() { destroy(false); }

    void destroy(boolean brokerAlreadyFinalized) {
        if (!created || destroyed) return;
        if (started) stop();
        if (destroyed || guest == null) return;
        ActivityResultFieldBridge.Captured result = ActivityResultFieldBridge.capture(guest);
        if (!invokeIfCreated("onDestroy", new Class<?>[0], new Object[0])) return;
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
        result.putString(RuntimeKeys.PACKAGE_NAME, session.packageName());
        result.putInt(RuntimeKeys.VIRTUAL_USER_ID, session.virtualUserId());
        result.putString(RuntimeKeys.SESSION_ID, session.sessionId());
        result.putLong(RuntimeKeys.GENERATION, session.generation());
        result.putInt(RuntimeKeys.PROCESS_SLOT, session.processSlot());
        result.putString(RuntimeKeys.ACTIVITY_TOKEN, activityToken);
        result.putInt(RuntimeKeys.TASK_ID, taskId);
        result.putString("virtualTask", String.valueOf(taskId));
        result.putString("activityState", state);
        result.putString("instanceId", session.instanceId());
    }

    private void attachFrameworkState(Activity activity, String componentClass) throws Exception {
        // Activity.attachBaseContext alone leaves framework-owned state such as mFragments
        // uninitialized. Android's own ActivityThread invokes the hidden full attach() before
        // onCreate; reproduce that state transition with the already-created Stub's framework
        // transport, then ActivityFieldBridge replaces identity-bearing fields with Guest data.
        Method attach = null;
        for (Method candidate : Activity.class.getDeclaredMethods()) {
            if ("attach".equals(candidate.getName()) && candidate.getParameterTypes().length >= 16) {
                attach = candidate;
                break;
            }
        }
        if (attach == null) throw new IllegalStateException("ACTIVITY_ATTACH_METHOD_UNAVAILABLE");
        attach.setAccessible(true);
        Class<?>[] types = attach.getParameterTypes();
        Object[] args = new Object[types.length];
        int binderIndex = 0;
        int stringIndex = 0;
        for (int index = 0; index < types.length; index++) {
            Class<?> type = types[index];
            String name = type.getName();
            if (android.app.Application.class.isAssignableFrom(type)) {
                args[index] = session.application();
            } else if (android.app.Activity.class.isAssignableFrom(type)) {
                args[index] = null;
            } else if (android.content.Context.class.isAssignableFrom(type)) {
                // Activity.attachBaseContext invokes framework callbacks such as Autofill on
                // this argument. The already-attached Stub is the only safe framework transport
                // at this point; ActivityFieldBridge projects mBase to the Guest Context after
                // attach() has initialized the platform state.
                args[index] = host;
            } else if ("android.app.ActivityThread".equals(name)) {
                args[index] = fieldValue(host, "mMainThread");
            } else if (android.app.Instrumentation.class.isAssignableFrom(type)) {
                args[index] = fieldValue(host, "mInstrumentation");
            } else if (android.os.IBinder.class.isAssignableFrom(type)) {
                if (binderIndex++ == 0) args[index] = fieldValue(host, "mToken");
                else if (binderIndex == 2) args[index] = fieldValue(host, "mAssistToken");
                else args[index] = fieldValue(host, "mShareableActivityToken");
            } else if (type == int.class || type == Integer.class) {
                args[index] = fieldValue(host, "mIdent");
            } else if (android.content.Intent.class.isAssignableFrom(type)) {
                Intent intent = host.getIntent() == null ? new Intent() : new Intent(host.getIntent());
                intent.setComponent(new android.content.ComponentName(session.spec().packageName,
                        componentClass));
                args[index] = intent;
            } else if (android.content.pm.ActivityInfo.class.isAssignableFrom(type)) {
                args[index] = fieldValue(host, "mActivityInfo");
            } else if (CharSequence.class.isAssignableFrom(type)) {
                args[index] = componentClass;
            } else if (type == String.class) {
                args[index] = stringIndex++ == 0
                        ? fieldValue(host, "mEmbeddedID") : fieldValue(host, "mReferrer");
            } else if (android.content.res.Configuration.class.isAssignableFrom(type)) {
                args[index] = fieldValue(host, "mCurrentConfig");
            } else if ("android.app.Activity$NonConfigurationInstances".equals(name)) {
                args[index] = fieldValue(host, "mLastNonConfigurationInstances");
            } else if (name.endsWith("IVoiceInteractor")) {
                args[index] = fieldValue(host, "mVoiceInteractor");
            } else if ("android.view.Window".equals(name)) {
                // Activity.attach() uses this only as an optional parent/container window.
                // A Guest Activity is a separate virtual Activity record, so sharing the Stub
                // window would give two Android Activity records ownership of one DecorView.
                args[index] = null;
            } else if (name.endsWith("ActivityConfigCallback")) {
                args[index] = fieldValue(host, "mActivityConfigCallback");
            } else {
                throw new IllegalStateException("ACTIVITY_ATTACH_PARAMETER_UNSUPPORTED:" + name);
            }
        }
        attach.invoke(activity, args);
    }

    private static Object fieldValue(Object target, String name) throws Exception {
        Class<?> cursor = target.getClass();
        while (cursor != null) {
            try {
                java.lang.reflect.Field field = cursor.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        return null;
    }

    private boolean invokeIfCreated(String name, Class<?>[] types, Object[] args) {
        if (guest == null || destroyed) return false;
        try { invokeLifecycle(guest, name, types, args); }
        catch (Throwable error) {
            if (isClosedDispatcher(error)) {
                // Runtime stop/disconnect can close the Guest broker while Android is still
                // delivering the Host trampoline's pause/stop callback. Treat that callback as
                // already finalized; a later lifecycle callback must remain idempotent.
                destroyed = true;
                guest = null;
                return false;
            }
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw new IllegalStateException("Guest lifecycle " + name + " failed", root(error));
        }
        return true;
    }

    private static boolean isClosedDispatcher(Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            if ("GUEST_MAIN_DISPATCHER_CLOSED".equals(cursor.getMessage())) return true;
            if (cursor.getCause() == cursor) break;
            cursor = cursor.getCause();
        }
        return false;
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
