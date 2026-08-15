package com.warden.controlledsandbox.runtime.guest;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.PersistableBundle;
import com.warden.controlledsandbox.contract.IRuntimeBroker;
import com.warden.controlledsandbox.runtime.component.activity.ActivityFieldBridge;
import com.warden.controlledsandbox.runtime.diagnostics.RuntimeEventLog;
import com.warden.controlledsandbox.runtime.protocol.RuntimeIntentWireCodec;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import com.warden.controlledsandbox.contract.RuntimeOperationRequest;
import com.warden.controlledsandbox.runtime.protocol.RuntimeOperationTransport;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Framework-owned Activity bridge modelled after VA/NBB's client-side launch interception.
 *
 * <p>The host AMS still allocates a declared Stub component, but the real ActivityThread
 * Instrumentation path receives the Guest object before {@code performLaunchActivity} records
 * it. Consequently attach, onCreate, start, resume, pause, stop and destroy are delivered by
 * Android to the Guest Activity instead of being manually replayed by a Stub Activity.</p>
 */
final class GuestActivityThreadInstrumentation extends Instrumentation implements AutoCloseable {
    private final Object activityThread;
    private final Field instrumentationField;
    private final Instrumentation delegate;
    private final GuestRuntimeEnvironment.Session session;
    private final Map<Activity, Launch> launches =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private final ExecutorService events = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "cs-framework-activity-events");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean closed;

    private GuestActivityThreadInstrumentation(Object activityThread, Field instrumentationField,
                                               Instrumentation delegate,
                                               GuestRuntimeEnvironment.Session session) {
        this.activityThread = activityThread;
        this.instrumentationField = instrumentationField;
        this.delegate = delegate;
        this.session = session;
    }

    static GuestActivityThreadInstrumentation install(GuestRuntimeEnvironment.Session session)
            throws Exception {
        Class<?> type = Class.forName("android.app.ActivityThread");
        Field currentField = findField(type, "sCurrentActivityThread");
        currentField.setAccessible(true);
        Object thread = currentField.get(null);
        if (thread == null) throw new IllegalStateException("GUEST_ACTIVITY_THREAD_UNAVAILABLE");
        Field instrumentation = findField(thread.getClass(), "mInstrumentation");
        instrumentation.setAccessible(true);
        Object value = instrumentation.get(thread);
        if (!(value instanceof Instrumentation delegate)) {
            throw new IllegalStateException("GUEST_INSTRUMENTATION_UNAVAILABLE");
        }
        if (value instanceof GuestActivityThreadInstrumentation) {
            throw new IllegalStateException("GUEST_INSTRUMENTATION_ALREADY_INSTALLED");
        }
        GuestActivityThreadInstrumentation bridge = new GuestActivityThreadInstrumentation(
                thread, instrumentation, delegate, session);
        instrumentation.set(thread, bridge);
        android.util.Log.i("CS_FRAMEWORK_ACTIVITY", "INSTRUMENTATION_READY mode=ACTIVITY_THREAD");
        return bridge;
    }

    @Override public Activity newActivity(ClassLoader classLoader, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        Launch route = route(intent);
        if (route == null) return delegate.newActivity(classLoader, className, intent);
        try {
            Bundle consumed = consume(route.token);
            if (!"ROUTE_GRANTED".equals(consumed.getString(RuntimeKeys.STATUS, ""))) {
                throw new IllegalStateException(consumed.getString(RuntimeKeys.ERROR_TYPE,
                        "ACTIVITY_ROUTE_NOT_GRANTED"));
            }
            String component = consumed.getString(RuntimeKeys.COMPONENT_CLASS, route.component);
            Intent guestIntent = RuntimeIntentWireCodec.decode(consumed);
            Activity guest = GuestComponentFactory.instantiateActivity(
                    session.context().getClassLoader(),
                    session.context().getApplicationInfo().appComponentFactory,
                    component, guestIntent);
            if (guest == null) throw new IllegalStateException("GUEST_ACTIVITY_FACTORY_RETURNED_NULL");
            if (!session.classLoader().loadClass(component).isInstance(guest)) {
                throw new IllegalStateException("GUEST_ACTIVITY_FACTORY_CLASS_MISMATCH");
            }
            launches.put(guest, new Launch(route.token, route.activityToken,
                    route.sessionId, route.generation, route.taskId, component, guestIntent));
            RuntimeEventLog.event("GUEST_ACTIVITY_FRAMEWORK_INSTANTIATED", evidence(route, guest));
            return guest;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            InstantiationException failure = new InstantiationException(
                    "GUEST_ACTIVITY_FRAMEWORK_INSTANTIATION_FAILED:" + error.getMessage());
            failure.initCause(error);
            throw failure;
        }
    }

    /**
     * Activity.startActivity() reaches the Instrumentation stored on the Activity instance.
     * ActivityThread installs this bridge during attach; keeping the same object in both places
     * is what lets framework lifecycle callbacks and Guest routing share one ownership boundary.
     * The method is hidden from the public SDK stubs, therefore it intentionally has no
     * @Override annotation.
     */
    public ActivityResult execStartActivity(android.content.Context who, android.os.IBinder contextThread,
                                            android.os.IBinder token, Activity target, Intent intent,
                                            int requestCode, Bundle options) {
        Launch launch = launches.get(target);
        if (launch == null) {
            throw new IllegalStateException("GUEST_ACTIVITY_ROUTE_NOT_REGISTERED");
        }
        session.context().startActivityFromActivity(intent, options, launch.taskId);
        return null;
    }

    @Override public void callActivityOnCreate(Activity activity, Bundle state) {
        Launch route = launches.get(activity);
        if (route == null) {
            delegate.callActivityOnCreate(activity, state);
            return;
        }
        try {
            ActivityFieldBridge.installGuest(activity, session, route.component,
                    route.intent, route.taskId);
            ActivityFieldBridge.promoteFrameworkRecord(activity, session,
                    route.component, route.intent);
            delegate.callActivityOnCreate(activity, state);
            emit(activity, route, "CREATED", state == null ? new Bundle() : state);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            emitFailure(activity, route, error);
            if (error instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("GUEST_ACTIVITY_ON_CREATE_FAILED", error);
        }
    }

    @Override public void callActivityOnCreate(Activity activity, Bundle state,
                                                PersistableBundle persistentState) {
        callActivityOnCreate(activity, state);
    }

    @Override public void callActivityOnStart(Activity activity) {
        delegate.callActivityOnStart(activity);
        emitKnown(activity, "STARTED", new Bundle());
    }

    @Override public void callActivityOnResume(Activity activity) {
        delegate.callActivityOnResume(activity);
        emitKnown(activity, "RESUMED", new Bundle());
    }

    @Override public void callActivityOnPause(Activity activity) {
        delegate.callActivityOnPause(activity);
        emitKnown(activity, "PAUSED", new Bundle());
    }

    @Override public void callActivityOnStop(Activity activity) {
        delegate.callActivityOnStop(activity);
        emitKnown(activity, "STOPPED", new Bundle());
    }

    @Override public void callActivityOnDestroy(Activity activity) {
        try {
            ActivityFieldBridge.repairFrameworkWindowBeforeDestroy(activity);
            delegate.callActivityOnDestroy(activity);
            emitKnown(activity, "DESTROYED", new Bundle());
        } finally {
            launches.remove(activity);
        }
    }

    @Override public void callActivityOnNewIntent(Activity activity, Intent intent) {
        delegate.callActivityOnNewIntent(activity, intent);
        emitKnown(activity, "NEW_INTENT", new Bundle());
    }

    /**
     * Closes every framework-owned Guest Activity before a destructive lifecycle transaction
     * releases the process-service binding.  Clearing only the broker ledger is insufficient:
     * ActivityManager will otherwise recreate the StubActivity's process because its task is
     * still top-resumed, defeating clear/delete's physical stop barrier.
     *
     * <p>This method is called on the Guest main thread by Session.shutdown().</p>
     */
    void finishAllActivities() {
        if (closed) return;
        Activity[] active;
        synchronized (launches) {
            active = launches.keySet().toArray(new Activity[0]);
        }
        for (Activity activity : active) {
            try {
                activity.finishAndRemoveTask();
            } catch (Throwable error) {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
                try {
                    activity.finish();
                } catch (Throwable fallback) {
                    com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(fallback);
                    android.util.Log.w("CS_FRAMEWORK_ACTIVITY",
                            "finish guest activity failed=" + fallback.getClass().getSimpleName());
                }
            }
        }
        if (active.length > 0) {
            android.util.Log.i("CS_FRAMEWORK_ACTIVITY",
                    "FINISH_ALL_DESTRUCTIVE count=" + active.length);
        }
    }

    @Override public void callActivityOnSaveInstanceState(Activity activity, Bundle state) {
        delegate.callActivityOnSaveInstanceState(activity, state);
        emitKnown(activity, "SAVE_STATE", state == null ? new Bundle() : state);
    }

    @Override public void callActivityOnSaveInstanceState(Activity activity, Bundle state,
                                                           PersistableBundle persistentState) {
        delegate.callActivityOnSaveInstanceState(activity, state, persistentState);
        emitKnown(activity, "SAVE_STATE", state == null ? new Bundle() : state);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        events.shutdownNow();
        try {
            if (instrumentationField.get(activityThread) == this) {
                instrumentationField.set(activityThread, delegate);
            }
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            android.util.Log.e("CS_FRAMEWORK_ACTIVITY", "restore instrumentation failed", error);
        }
        launches.clear();
    }

    private Launch route(Intent intent) {
        if (closed || intent == null) return null;
        String token = intent.getStringExtra(RuntimeKeys.ROUTE_TOKEN);
        String sessionId = intent.getStringExtra(RuntimeKeys.SESSION_ID);
        long generation = intent.getLongExtra(RuntimeKeys.GENERATION, 0L);
        String component = intent.getStringExtra(RuntimeKeys.COMPONENT_CLASS);
        if (token == null || token.trim().isEmpty() || sessionId == null
                || !session.sessionId().equals(sessionId) || generation != session.generation()
                || component == null || component.trim().isEmpty()) return null;
        return new Launch(token, intent.getStringExtra(RuntimeKeys.ACTIVITY_TOKEN),
                sessionId, generation, intent.getIntExtra(RuntimeKeys.TASK_ID, 0),
                component, null);
    }

    private Bundle consume(String token) throws Exception {
        Bundle request = new Bundle();
        request.putInt(RuntimeKeys.PROTOCOL,
                com.warden.controlledsandbox.domain.protocol.RuntimeProtocol.CURRENT);
        request.putString(RuntimeKeys.ROUTE_TOKEN, token);
        request.putString(RuntimeKeys.SESSION_ID, session.sessionId());
        request.putLong(RuntimeKeys.GENERATION, session.generation());
        IRuntimeBroker broker = IRuntimeBroker.Stub.asInterface(session.spec().runtimeBrokerBinder);
        if (broker == null) throw new IllegalStateException("RUNTIME_BROKER_BINDER_UNAVAILABLE");
        return RuntimeOperationTransport.toLegacyBundle(RuntimeOperationTransport.execute(
                broker, RuntimeOperationRequest.CONSUME_ROUTE, request));
    }

    private void emitKnown(Activity activity, String event, Bundle details) {
        Launch route = launches.get(activity);
        if (route != null) emit(activity, route, event, details);
    }

    private void emitFailure(Activity activity, Launch route, Throwable error) {
        Bundle details = new Bundle();
        details.putString(RuntimeKeys.ERROR_TYPE, error.getClass().getName());
        details.putString(RuntimeKeys.ERROR_MESSAGE, String.valueOf(error.getMessage()));
        emit(activity, route, "FAILED", details);
    }

    private void emit(Activity activity, Launch route, String event, Bundle details) {
        Bundle request = details == null ? new Bundle() : new Bundle(details);
        request.putInt(RuntimeKeys.PROTOCOL,
                com.warden.controlledsandbox.domain.protocol.RuntimeProtocol.CURRENT);
        request.putString(RuntimeKeys.SESSION_ID, route.sessionId);
        request.putLong(RuntimeKeys.GENERATION, route.generation);
        request.putString(RuntimeKeys.ACTIVITY_TOKEN, route.activityToken);
        request.putString(RuntimeKeys.ACTIVITY_EVENT, event);
        request.putString(RuntimeKeys.COMPONENT_CLASS, route.component);
        Bundle evidence;
        try {
            evidence = ActivityFieldBridge.frameworkEvidence(activity);
            request.putAll(evidence);
        } catch (Throwable error) {
            request.putString("frameworkEvidenceError", error.getClass().getName() + ":"
                    + String.valueOf(error.getMessage()));
        }
        android.view.Window window = activity.getWindow();
        android.view.View decor = window == null ? null : window.getDecorView();
        request.putBoolean("windowAttached", decor != null && decor.isAttachedToWindow());
        request.putBoolean("frameworkOwnedActivity", true);
        RuntimeEventLog.event("GUEST_ACTIVITY_" + event, request);
        if (closed) return;
        events.execute(() -> {
            try {
                Bundle result = GuestRuntimeEnvironment.dispatchActivityEvent(session, request);
                if (!"ACTIVITY_EVENT_APPLIED".equals(result.getString(RuntimeKeys.STATUS, ""))) {
                    android.util.Log.w("CS_FRAMEWORK_ACTIVITY", "event rejected="
                            + result.getString(RuntimeKeys.ERROR_TYPE, "UNKNOWN"));
                }
            } catch (Throwable error) {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
                android.util.Log.e("CS_FRAMEWORK_ACTIVITY", "event dispatch failed", error);
            }
        });
    }

    private static Bundle evidence(Launch route, Activity activity) {
        Bundle out = new Bundle();
        out.putString(RuntimeKeys.ROUTE_TOKEN, route.token);
        out.putString(RuntimeKeys.ACTIVITY_TOKEN, route.activityToken);
        out.putString(RuntimeKeys.COMPONENT_CLASS, route.component);
        out.putBoolean("frameworkOwnedActivity", true);
        out.putString("activityClass", activity.getClass().getName());
        return out;
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> cursor = type;
        while (cursor != null) {
            try { return cursor.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { cursor = cursor.getSuperclass(); }
        }
        return null;
    }

    private static final class Launch {
        final String token;
        final String activityToken;
        final String sessionId;
        final long generation;
        final int taskId;
        final String component;
        final Intent intent;

        Launch(String token, String activityToken, String sessionId, long generation,
               int taskId, String component, Intent intent) {
            this.token = token;
            this.activityToken = activityToken == null ? "" : activityToken;
            this.sessionId = sessionId;
            this.generation = generation;
            this.taskId = taskId;
            this.component = component;
            this.intent = intent;
        }
    }
}
