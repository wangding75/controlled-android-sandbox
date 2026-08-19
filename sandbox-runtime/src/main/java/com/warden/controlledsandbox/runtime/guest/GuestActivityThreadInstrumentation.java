package com.warden.controlledsandbox.runtime.guest;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.PersistableBundle;
import com.warden.controlledsandbox.contract.IRuntimeBroker;
import com.warden.controlledsandbox.runtime.component.activity.ActivityFieldBridge;
import com.warden.controlledsandbox.runtime.component.activity.ActivityResultBundleCodec;
import com.warden.controlledsandbox.runtime.component.activity.ActivityResultFieldBridge;
import com.warden.controlledsandbox.runtime.diagnostics.RuntimeEventLog;
import com.warden.controlledsandbox.runtime.protocol.RuntimeIntentWireCodec;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import com.warden.controlledsandbox.contract.RuntimeOperationRequest;
import com.warden.controlledsandbox.runtime.protocol.RuntimeOperationTransport;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
        try (FrameworkClassLoaderScope ignored = enterFrameworkClassLoader()) {
            Bundle consumed = consume(route.token);
            if (!"ROUTE_GRANTED".equals(consumed.getString(RuntimeKeys.STATUS, ""))) {
                throw new IllegalStateException(consumed.getString(RuntimeKeys.ERROR_TYPE,
                        "ACTIVITY_ROUTE_NOT_GRANTED"));
            }
            String component = consumed.getString(RuntimeKeys.COMPONENT_CLASS, route.component);
            // PackageParser keeps an activity-alias as the logical component name but
            // ActivityThread/AppComponentFactory instantiate the alias's target Activity class.
            // The previous path passed the alias name to the factory, which is not a loadable
            // class for the normal manifest form and failed only for apps that use aliases.
            String instantiateComponent = activityInstantiationClass(component);
            Intent guestIntent = RuntimeIntentWireCodec.decode(consumed);
            Activity guest = GuestComponentFactory.instantiateActivity(
                    GuestDefiningLoader.of(session),
                    session.context().getApplicationInfo().appComponentFactory,
                    instantiateComponent, guestIntent);
            if (guest == null) throw new IllegalStateException("GUEST_ACTIVITY_FACTORY_RETURNED_NULL");
            if (!GuestDefiningLoader.loadComponent(session, instantiateComponent).isInstance(guest)) {
                throw new IllegalStateException("GUEST_ACTIVITY_FACTORY_CLASS_MISMATCH");
            }
            ClassLoader guestClassLoader = session.context().getClassLoader();
            Bundle restoredState = SavedStateWireCodec.unmarshallBundle(
                    consumed.getByteArray(RuntimeKeys.SAVED_STATE_PAYLOAD), guestClassLoader);
            if (restoredState == null) restoredState = legacySavedState(consumed);
            PersistableBundle restoredPersistableState =
                    SavedStateWireCodec.unmarshallPersistableBundle(
                            consumed.getByteArray(RuntimeKeys.SAVED_STATE_PERSISTABLE_PAYLOAD),
                            guestClassLoader);
            String consumedActivityToken = consumed.getString(
                    RuntimeKeys.ACTIVITY_TOKEN, route.activityToken);
            String consumedSessionId = consumed.getString(
                    RuntimeKeys.SESSION_ID, route.sessionId);
            long consumedGeneration = consumed.getLong(RuntimeKeys.GENERATION, route.generation);
            int consumedTaskId = consumed.getInt(RuntimeKeys.TASK_ID, route.taskId);
            launches.put(guest, new Launch(route.token, consumedActivityToken,
                    consumedSessionId, consumedGeneration, consumedTaskId, component, guestIntent,
                    restoredState, restoredPersistableState,
                    consumed.getLong(RuntimeKeys.SAVED_STATE_VERSION, 0L)));
            Bundle evidence = evidence(route, guest);
            evidence.putBoolean("restoredStatePresent", restoredState != null);
            evidence.putBoolean("restoredPersistableStatePresent", restoredPersistableState != null);
            RuntimeEventLog.event("GUEST_ACTIVITY_FRAMEWORK_INSTANTIATED", evidence);
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
     * ActivityThread installs this Instrumentation on the real framework Activity.  When that
     * Activity is a Guest object, prepare the virtual transaction in the Broker but let the
     * delegate send the returned host Stub intent with the Guest Activity's real framework token.
     * A Service/Application Context cannot do this and continues through the Broker's
     * startActivity path with NEW_TASK.
     */
    public ActivityResult execStartActivity(android.content.Context who,
            android.os.IBinder contextThread, android.os.IBinder token, Activity target,
            Intent intent, int requestCode, Bundle options) {
        Launch route = launches.get(target);
        if (route == null) {
            return invokeDelegateExecStartActivity(who, contextThread, token, target, intent,
                    requestCode, options);
        }
        try (FrameworkClassLoaderScope ignored = enterFrameworkClassLoader()) {
            Bundle result = session.context().startActivityFromFrameworkActivity(
                    intent, options, route.taskId, requestCode);
            // A virtual reuse decision (singleTask / CLEAR_TOP / singleTop) must still be
            // realized by the Host AMS/ATMS, never by replanting callbacks onto a live Guest
            // Activity.  The ledger marks the physical records it removes; finish them through
            // the framework here so Android owns the onDestroy/onPause/onStop chain and the
            // reused target naturally becomes the task top.  The forwarded host Intent then
            // carries the reuse flags produced by the Broker so ActivityStarter performs the
            // matching move-to-front / single-top onNewIntent as a genuine ClientTransaction.
            finishRemovedActivities(result);
            Intent hostIntent = hostIntent(result);
            if (hostIntent == null) {
                throw new IllegalStateException("FRAMEWORK_HOST_ACTIVITY_INTENT_MISSING");
            }
            return invokeDelegateExecStartActivity(who, contextThread, token, target, hostIntent,
                    requestCode, options);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            emitFailure(target, route, error);
            if (error instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("FRAMEWORK_HOST_ACTIVITY_START_FAILED", error);
        }
    }

    private Intent hostIntent(Bundle result) {
        if (result == null) return null;
        String className = result.getString(RuntimeKeys.HOST_ACTIVITY_CLASS, "");
        if (className.trim().isEmpty()) return null;
        Intent host = new Intent().setComponent(new android.content.ComponentName(
                session.context().hostServiceContext().getPackageName(), className));
        host.setFlags(result.getInt(RuntimeKeys.HOST_ACTIVITY_FLAGS, 0));
        copyString(result, host, RuntimeKeys.ROUTE_TOKEN);
        copyString(result, host, RuntimeKeys.SESSION_ID);
        copyLong(result, host, RuntimeKeys.GENERATION);
        copyString(result, host, RuntimeKeys.COMPONENT_CLASS);
        copyString(result, host, RuntimeKeys.TARGET_PACKAGE_NAME);
        copyString(result, host, RuntimeKeys.INTENT_COMPONENT_PACKAGE);
        copyString(result, host, RuntimeKeys.INTENT_COMPONENT_CLASS);
        copyString(result, host, RuntimeKeys.ACTIVITY_ACTION);
        copyString(result, host, RuntimeKeys.ACTIVITY_TOKEN);
        copyInt(result, host, RuntimeKeys.TASK_ID);
        copyInt(result, host, RuntimeKeys.ACTIVITY_FLAGS);
        copyString(result, host, RuntimeKeys.URI);
        copyString(result, host, RuntimeKeys.BROADCAST_SCHEME);
        copyString(result, host, RuntimeKeys.BROADCAST_HOST);
        copyInt(result, host, RuntimeKeys.BROADCAST_PORT);
        copyString(result, host, RuntimeKeys.BROADCAST_PATH);
        copyString(result, host, RuntimeKeys.BROADCAST_MIME_TYPE);
        if (result.containsKey(RuntimeKeys.BROADCAST_CATEGORIES)) {
            java.util.ArrayList<String> categories = result.getStringArrayList(
                    RuntimeKeys.BROADCAST_CATEGORIES);
            if (categories != null) host.putStringArrayListExtra(
                    RuntimeKeys.BROADCAST_CATEGORIES, new java.util.ArrayList<>(categories));
        }
        Bundle extras = result.getBundle(RuntimeKeys.INTENT_EXTRAS);
        if (extras != null) host.putExtra(RuntimeKeys.INTENT_EXTRAS, new Bundle(extras));
        return host;
    }

    private static void copyString(Bundle source, Intent target, String key) {
        if (source.containsKey(key)) target.putExtra(key, source.getString(key, ""));
    }

    private static void copyLong(Bundle source, Intent target, String key) {
        if (source.containsKey(key)) target.putExtra(key, source.getLong(key));
    }

    private static void copyInt(Bundle source, Intent target, String key) {
        if (source.containsKey(key)) target.putExtra(key, source.getInt(key));
    }

    @Override public void callActivityOnCreate(Activity activity, Bundle state) {
        Launch route = launches.get(activity);
        if (route == null) {
            delegate.callActivityOnCreate(activity, state);
            return;
        }
        android.util.Log.i("CS_FRAMEWORK_ACTIVITY", "CALLBACK_TWO_ARG component="
                + route.component + " persistableMode="
                + ActivityFieldBridge.frameworkPersistableMode(activity)
                + " infoIdentity=" + ActivityFieldBridge.frameworkActivityInfoIdentity(activity));
        try (FrameworkClassLoaderScope ignored = enterFrameworkClassLoader()) {
            ActivityFieldBridge.installGuest(activity, session, route.component,
                    route.intent, route.taskId);
            ActivityFieldBridge.promoteFrameworkRecord(activity, session,
                    route.component, route.intent);
            Bundle effectiveState = effectiveState(route, state);
            delegate.callActivityOnCreate(activity, effectiveState);
            dispatchMissingRestoreCallbacks(activity, route, state, null, effectiveState, null);
            Bundle details = callbackStateDetails(effectiveState, null);
            emit(activity, route, "CREATED", details);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            emitFailure(activity, route, error);
            if (error instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("GUEST_ACTIVITY_ON_CREATE_FAILED", error);
        }
    }

    @Override public void callActivityOnCreate(Activity activity, Bundle state,
                                                PersistableBundle persistentState) {
        Launch route = launches.get(activity);
        if (route == null) {
            delegate.callActivityOnCreate(activity, state, persistentState);
            return;
        }
        android.util.Log.i("CS_FRAMEWORK_ACTIVITY", "CALLBACK_THREE_ARG component="
                + route.component + " persistableMode="
                + ActivityFieldBridge.frameworkPersistableMode(activity)
                + " infoIdentity=" + ActivityFieldBridge.frameworkActivityInfoIdentity(activity)
                + " statePresent=" + (persistentState != null));
        try (FrameworkClassLoaderScope ignored = enterFrameworkClassLoader()) {
            ActivityFieldBridge.installGuest(activity, session, route.component,
                    route.intent, route.taskId);
            ActivityFieldBridge.promoteFrameworkRecord(activity, session,
                    route.component, route.intent);
            // Do not downgrade a persistable launch to the two-argument callback. Android
            // selects this overload from ActivityInfo.persistableMode and applications use the
            // PersistableBundle to restore task state across process/reboot recreation.
            Bundle effectiveState = effectiveState(route, state);
            PersistableBundle effectivePersistentState = effectivePersistentState(
                    route, persistentState);
            delegate.callActivityOnCreate(activity, effectiveState, effectivePersistentState);
            dispatchMissingRestoreCallbacks(activity, route, state, persistentState,
                    effectiveState, effectivePersistentState);
            Bundle details = callbackStateDetails(effectiveState, effectivePersistentState);
            details.putBoolean("persistableCreate", persistentState != null);
            if (effectivePersistentState != null) {
                details.putInt("persistableStateKeyCount", effectivePersistentState.keySet().size());
            }
            emit(activity, route, "CREATED", details);
            // The callback overload is the framework contract under test. Android is allowed
            // to select the three-argument path with a null/empty restored state on a first
            // launch; tying the evidence marker to non-null state would report a false two-arg
            // downgrade and hide the actual ActivityInfo.persistableMode decision.
            Bundle persistableEvidence = evidence(route, activity);
            persistableEvidence.putBoolean("persistableCallback", true);
            persistableEvidence.putBoolean("persistableStatePresent", persistentState != null);
            persistableEvidence.putInt("persistableStateKeyCount",
                    persistentState == null ? 0 : persistentState.keySet().size());
            RuntimeEventLog.event("GUEST_ACTIVITY_PERSISTABLE_CREATE", persistableEvidence);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            emitFailure(activity, route, error);
            if (error instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("GUEST_ACTIVITY_ON_CREATE_PERSISTABLE_FAILED", error);
        }
    }

    @Override public void callActivityOnStart(Activity activity) {
        Launch route = launches.get(activity);
        if (route == null) {
            delegate.callActivityOnStart(activity);
            return;
        }
        try (FrameworkClassLoaderScope ignored = enterFrameworkClassLoader()) {
            delegate.callActivityOnStart(activity);
            emit(activity, route, "STARTED", new Bundle());
        }
    }

    @Override public void callActivityOnResume(Activity activity) {
        Launch route = launches.get(activity);
        if (route == null) {
            delegate.callActivityOnResume(activity);
            return;
        }
        try (FrameworkClassLoaderScope ignored = enterFrameworkClassLoader()) {
        // ActivityThread.handleResumeActivity() runs immediately after this callback.  Repair
        // an unregistered trampoline root before the framework reaches its updateViewLayout()
        // branch; otherwise a task transition can kill the Guest process even though onResume
        // itself completed successfully.
        ActivityFieldBridge.repairFrameworkWindowBeforeResume(activity);
        delegate.callActivityOnResume(activity);
        // Guest onResume() is allowed to synchronously change task/window state (for example a
        // launcher that immediately opens a document or finishes a splash Activity).  That can
        // detach the same DecorView after the first inspection but before ActivityThread gets
        // control back. Reconcile once more at this transaction boundary so the framework sees
        // a null ActivityClientRecord.window and follows its normal addView path.
        ActivityFieldBridge.repairFrameworkWindowBeforeResume(activity);
        emit(activity, route, "RESUMED", new Bundle());
        }
    }

    @Override public void callActivityOnPause(Activity activity) {
        Launch route = launches.get(activity);
        if (route == null) {
            delegate.callActivityOnPause(activity);
            return;
        }
        try (FrameworkClassLoaderScope ignored = enterFrameworkClassLoader()) {
            delegate.callActivityOnPause(activity);
            emit(activity, route, "PAUSED", new Bundle());
        }
    }

    @Override public void callActivityOnStop(Activity activity) {
        Launch route = launches.get(activity);
        if (route == null) {
            delegate.callActivityOnStop(activity);
            return;
        }
        try (FrameworkClassLoaderScope ignored = enterFrameworkClassLoader()) {
            delegate.callActivityOnStop(activity);
            emit(activity, route, "STOPPED", new Bundle());
        }
    }

    @Override public void callActivityOnDestroy(Activity activity) {
        Launch route = launches.get(activity);
        if (route == null) {
            delegate.callActivityOnDestroy(activity);
            return;
        }
        try (FrameworkClassLoaderScope ignored = enterFrameworkClassLoader()) {
        try {
            ActivityResultFieldBridge.Captured result = ActivityResultFieldBridge.capture(activity);
            ActivityFieldBridge.repairFrameworkWindowBeforeDestroy(activity);
            delegate.callActivityOnDestroy(activity);
            if (result.finished()) {
                emit(activity, route, "FINISH_RESULT",
                        ActivityResultBundleCodec.encode(result.resultCode(), result.data()));
            } else {
                emit(activity, route, "DESTROYED", new Bundle());
            }
        } finally {
            launches.remove(activity);
        }
        }
    }

    @Override public void callActivityOnNewIntent(Activity activity, Intent intent) {
        Launch route = launches.get(activity);
        if (route == null) {
            delegate.callActivityOnNewIntent(activity, intent);
            return;
        }
        try (FrameworkClassLoaderScope ignored = enterFrameworkClassLoader()) {
            String routeToken = intent == null
                    ? "" : intent.getStringExtra(RuntimeKeys.ROUTE_TOKEN);
            if (routeToken == null || routeToken.trim().isEmpty()) {
                throw new IllegalStateException("GUEST_ACTIVITY_NEW_INTENT_ROUTE_MISSING");
            }
            Bundle consumed = consume(routeToken);
            if (!"ROUTE_GRANTED".equals(consumed.getString(RuntimeKeys.STATUS, ""))) {
                throw new IllegalStateException(consumed.getString(RuntimeKeys.ERROR_TYPE,
                        "GUEST_ACTIVITY_NEW_INTENT_ROUTE_DENIED"));
            }
            String activityToken = consumed.getString(RuntimeKeys.ACTIVITY_TOKEN, "");
            if (!route.activityToken.equals(activityToken)) {
                Bundle mismatch = new Bundle();
                mismatch.putString("routeComponent", route.component);
                mismatch.putString("routeActivityToken", route.activityToken);
                mismatch.putString("consumedActivityToken", activityToken);
                mismatch.putString("consumedComponent",
                        consumed.getString(RuntimeKeys.COMPONENT_CLASS, ""));
                mismatch.putString("deliveredIntentComponent",
                        intent == null || intent.getComponent() == null
                                ? "" : intent.getComponent().flattenToShortString());
                mismatch.putString("physicalActivityClass", activity.getClass().getName());
                RuntimeEventLog.event("GUEST_ACTIVITY_NEW_INTENT_MISMATCH", mismatch);
                android.util.Log.e("CS_FRAMEWORK_ACTIVITY", "NEW_INTENT_MISMATCH"
                        + " routeComponent=" + route.component
                        + " expectedToken=" + route.activityToken
                        + " consumedToken=" + activityToken
                        + " consumedComponent="
                        + consumed.getString(RuntimeKeys.COMPONENT_CLASS, "")
                        + " deliveredComponent="
                        + (intent == null || intent.getComponent() == null
                                ? "" : intent.getComponent().flattenToShortString())
                        + " physicalClass=" + activity.getClass().getName());
                throw new SecurityException("GUEST_ACTIVITY_NEW_INTENT_ACTIVITY_MISMATCH");
            }
            Intent guestIntent = RuntimeIntentWireCodec.decode(consumed);
            ActivityFieldBridge.projectFrameworkNewIntent(activity, session, route.component,
                    guestIntent);
            delegate.callActivityOnNewIntent(activity, guestIntent);
            Bundle details = new Bundle();
            details.putString(RuntimeKeys.ROUTE_TOKEN, routeToken);
            details.putBoolean("frameworkNewIntentProjected", true);
            emit(activity, route, "NEW_INTENT", details);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            emitFailure(activity, route, error);
            if (error instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("GUEST_ACTIVITY_NEW_INTENT_FAILED", error);
        }
    }

    /**
     * ActivityThread invokes this hidden/public compatibility callback when the Guest's
     * ActivityInfo declares the relevant configuration bits.  The legacy StubActivity controller
     * already reported this edge, but the real ActivityThread path previously let the callback
     * reach the Guest without updating the virtual task ledger.  Keep both sides aligned: first
     * let the platform Instrumentation deliver the Guest callback, then fence the ledger with the
     * same configuration token used by the framework-owned lifecycle transaction.
     */
    public void callActivityOnConfigurationChanged(Activity activity, Configuration newConfig) {
        Launch route = launches.get(activity);
        if (route == null) {
            invokeDelegateConfiguration(activity, newConfig);
            return;
        }
        try (FrameworkClassLoaderScope ignored = enterFrameworkClassLoader()) {
            invokeDelegateConfiguration(activity, newConfig);
            Bundle details = new Bundle();
            details.putString(RuntimeKeys.CONFIGURATION_TOKEN,
                    configurationToken(newConfig));
            details.putBoolean(RuntimeKeys.HANDLES_CONFIGURATION, true);
            details.putInt("configurationHash", newConfig == null ? 0 : newConfig.hashCode());
            emit(activity, route, "CONFIGURATION", details);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            emitFailure(activity, route, error);
            if (error instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("GUEST_ACTIVITY_CONFIGURATION_FAILED", error);
        }
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
        Launch route = launches.get(activity);
        if (route == null) {
            delegate.callActivityOnSaveInstanceState(activity, state);
            return;
        }
        try (FrameworkClassLoaderScope ignored = enterFrameworkClassLoader()) {
            delegate.callActivityOnSaveInstanceState(activity, state);
            emit(activity, route, "SAVE_STATE", savedStateDetails(route, state, null));
        }
    }

    @Override public void callActivityOnSaveInstanceState(Activity activity, Bundle state,
                                                           PersistableBundle persistentState) {
        Launch route = launches.get(activity);
        if (route == null) {
            delegate.callActivityOnSaveInstanceState(activity, state, persistentState);
            return;
        }
        try (FrameworkClassLoaderScope ignored = enterFrameworkClassLoader()) {
            delegate.callActivityOnSaveInstanceState(activity, state, persistentState);
            emit(activity, route, "SAVE_STATE",
                    savedStateDetails(route, state, persistentState));
        }
    }

    /**
     * Keep the complete ActivityThread restore/post-create path inside the Guest class-loader
     * scope.  These callbacks are easy to miss because they do not change the task ledger, but
     * they are where saved View state, WebView state and app-owned restoration code execute after
     * process recreation.  Letting them run with the host TCCL makes split/plugin class lookup
     * fail only on recovery, which is precisely the lifecycle edge a sandbox must not lose.
     */
    @Override public void callActivityOnRestoreInstanceState(Activity activity, Bundle state) {
        Launch route = launches.get(activity);
        if (route == null) {
            delegate.callActivityOnRestoreInstanceState(activity, state);
            return;
        }
        Bundle effectiveState = effectiveState(route, state);
        callFrameworkCallback(activity, "RESTORED_STATE", callbackStateDetails(effectiveState, null),
                () -> delegate.callActivityOnRestoreInstanceState(activity, effectiveState));
    }

    @Override public void callActivityOnRestoreInstanceState(Activity activity, Bundle state,
                                                               PersistableBundle persistentState) {
        Launch route = launches.get(activity);
        if (route == null) {
            delegate.callActivityOnRestoreInstanceState(activity, state, persistentState);
            return;
        }
        Bundle effectiveState = effectiveState(route, state);
        PersistableBundle effectivePersistentState = effectivePersistentState(
                route, persistentState);
        Bundle details = callbackStateDetails(effectiveState, effectivePersistentState);
        callFrameworkCallback(activity, "RESTORED_STATE", details,
                () -> delegate.callActivityOnRestoreInstanceState(activity, effectiveState,
                        effectivePersistentState));
    }

    @Override public void callActivityOnPostCreate(Activity activity, Bundle state) {
        Launch route = launches.get(activity);
        if (route == null) {
            delegate.callActivityOnPostCreate(activity, state);
            return;
        }
        Bundle effectiveState = effectiveState(route, state);
        callFrameworkCallback(activity, "POST_CREATED", callbackStateDetails(effectiveState, null),
                () -> delegate.callActivityOnPostCreate(activity, effectiveState));
    }

    @Override public void callActivityOnPostCreate(Activity activity, Bundle state,
                                                    PersistableBundle persistentState) {
        Launch route = launches.get(activity);
        if (route == null) {
            delegate.callActivityOnPostCreate(activity, state, persistentState);
            return;
        }
        Bundle effectiveState = effectiveState(route, state);
        PersistableBundle effectivePersistentState = effectivePersistentState(
                route, persistentState);
        Bundle details = callbackStateDetails(effectiveState, effectivePersistentState);
        callFrameworkCallback(activity, "POST_CREATED", details,
                () -> delegate.callActivityOnPostCreate(activity, effectiveState,
                        effectivePersistentState));
    }

    @Override public void callActivityOnRestart(Activity activity) {
        callFrameworkCallback(activity, "RESTARTED", new Bundle(),
                () -> delegate.callActivityOnRestart(activity));
    }

    @Override public void callActivityOnUserLeaving(Activity activity) {
        callFrameworkCallback(activity, "USER_LEAVING", new Bundle(),
                () -> delegate.callActivityOnUserLeaving(activity));
    }

    /** API 26+ callback used by ActivityThread for a real Picture-in-Picture transition. */
    public void callActivityOnPictureInPictureModeChanged(Activity activity,
                                                           boolean inPictureInPictureMode,
                                                           Configuration newConfig) {
        Bundle details = windowModeDetails(inPictureInPictureMode, newConfig);
        callFrameworkCallback(activity, "PICTURE_IN_PICTURE", details,
                () -> invokeDelegateWindowMode("callActivityOnPictureInPictureModeChanged",
                        "onPictureInPictureModeChanged", activity, inPictureInPictureMode,
                        newConfig));
    }

    /** API 24+ callback used by ActivityThread for a real multi-window transition. */
    public void callActivityOnMultiWindowModeChanged(Activity activity,
                                                       boolean inMultiWindowMode,
                                                       Configuration newConfig) {
        Bundle details = windowModeDetails(inMultiWindowMode, newConfig);
        callFrameworkCallback(activity, "MULTI_WINDOW", details,
                () -> invokeDelegateWindowMode("callActivityOnMultiWindowModeChanged",
                        "onMultiWindowModeChanged", activity, inMultiWindowMode, newConfig));
    }

    private static Bundle windowModeDetails(boolean enabled, Configuration configuration) {
        Bundle details = new Bundle();
        details.putBoolean("windowModeEnabled", enabled);
        details.putInt("configurationHash", configuration == null ? 0 : configuration.hashCode());
        return details;
    }

    private void invokeDelegateWindowMode(String instrumentationMethod,
                                           String activityMethod,
                                           Activity activity,
                                           boolean enabled,
                                           Configuration configuration) {
        try {
            Method method = Instrumentation.class.getDeclaredMethod(instrumentationMethod,
                    Activity.class, boolean.class, Configuration.class);
            method.setAccessible(true);
            method.invoke(delegate, activity, enabled, configuration);
            return;
        } catch (NoSuchMethodException ignored) {
            // The callback was moved out of Instrumentation on some platform/compile-SDK
            // combinations.  Invoke the Activity contract below instead of dropping it.
        } catch (java.lang.reflect.InvocationTargetException error) {
            rethrowCallbackCause(error, "FRAMEWORK_WINDOW_MODE_CALLBACK_FAILED");
            return;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw new IllegalStateException("FRAMEWORK_WINDOW_MODE_CALLBACK_UNAVAILABLE", error);
        }

        try {
            Method method = Activity.class.getDeclaredMethod(activityMethod,
                    boolean.class, Configuration.class);
            method.setAccessible(true);
            method.invoke(activity, enabled, configuration);
        } catch (NoSuchMethodException unavailableConfigurationOverload) {
            try {
                Method method = Activity.class.getDeclaredMethod(activityMethod, boolean.class);
                method.setAccessible(true);
                method.invoke(activity, enabled);
            } catch (java.lang.reflect.InvocationTargetException error) {
                rethrowCallbackCause(error, "FRAMEWORK_WINDOW_MODE_CALLBACK_FAILED");
            } catch (Throwable error) {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
                throw new IllegalStateException("FRAMEWORK_WINDOW_MODE_CALLBACK_UNAVAILABLE", error);
            }
        } catch (java.lang.reflect.InvocationTargetException error) {
            rethrowCallbackCause(error, "FRAMEWORK_WINDOW_MODE_CALLBACK_FAILED");
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw new IllegalStateException("FRAMEWORK_WINDOW_MODE_CALLBACK_UNAVAILABLE", error);
        }
    }

    private static void rethrowCallbackCause(java.lang.reflect.InvocationTargetException error,
                                              String message) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(cause);
        if (cause instanceof RuntimeException runtime) throw runtime;
        if (cause instanceof Error fatal) throw fatal;
        throw new IllegalStateException(message, cause);
    }

    private Bundle effectiveState(Launch route, Bundle frameworkState) {
        return SavedStateWireCodec.merge(frameworkState, route.restoredState,
                session.context().getClassLoader());
    }

    private PersistableBundle effectivePersistentState(
            Launch route, PersistableBundle frameworkState) {
        return SavedStateWireCodec.mergePersistable(frameworkState,
                route.restoredPersistableState, session.context().getClassLoader());
    }

    /**
     * If the host Stub had no platform-owned saved state, ActivityThread will not call the two
     * restore callbacks at all. The virtual Activity still must see the same callback ordering as
     * a normal process recreation, so perform the missing pair immediately after onCreate.
     */
    private void dispatchMissingRestoreCallbacks(Activity activity, Launch route,
                                                  Bundle frameworkState,
                                                  PersistableBundle frameworkPersistentState,
                                                  Bundle effectiveState,
                                                  PersistableBundle effectivePersistentState) {
        // A persistable Activity may save only its PersistableBundle (for example task/document
        // metadata) while the ordinary Bundle is empty.  ActivityThread can then omit the
        // platform restore pair because the Stub record has no host Bundle, even though the
        // Guest has a real persisted payload.  Gate on both payload families so the Guest still
        // observes the framework callback ordering for that case.  Conversely, if ActivityThread
        // already supplied either host payload, its normal callback path owns the pair and this
        // bridge must not invoke it a second time.
        boolean guestStatePresent = route.restoredState != null
                || route.restoredPersistableState != null;
        boolean frameworkStatePresent = frameworkState != null || frameworkPersistentState != null;
        if (!guestStatePresent || frameworkStatePresent || route.restoreCallbacksDispatched) {
            return;
        }
        if (effectivePersistentState == null) {
            delegate.callActivityOnRestoreInstanceState(activity, effectiveState);
            delegate.callActivityOnPostCreate(activity, effectiveState);
        } else {
            delegate.callActivityOnRestoreInstanceState(activity, effectiveState,
                    effectivePersistentState);
            delegate.callActivityOnPostCreate(activity, effectiveState,
                    effectivePersistentState);
        }
        route.restoreCallbacksDispatched = true;
    }

    private Bundle savedStateDetails(Launch route, Bundle state,
                                     PersistableBundle persistentState) {
        Bundle details = new Bundle();
        long version = route.nextSavedStateVersion();
        details.putLong(RuntimeKeys.SAVED_STATE_VERSION, version);
        details.putBoolean("savedStatePresent", state != null);
        details.putInt("savedStateKeyCount", state == null ? 0 : state.keySet().size());
        byte[] payload = SavedStateWireCodec.marshall(state, "Activity saved Bundle");
        byte[] persistablePayload = SavedStateWireCodec.marshall(
                persistentState, "Activity persistable saved Bundle");
        if (payload.length + persistablePayload.length
                > com.warden.controlledsandbox.framework.activity.SavedActivityState.MAX_PAYLOAD_BYTES) {
            // Preserve the ordinary Bundle, which contains View/WebView/app state. Persistable
            // task metadata is deliberately dropped rather than overflowing the route Binder.
            persistablePayload = new byte[0];
            details.putBoolean("persistableStateTruncated", true);
        }
        if (payload.length != 0) details.putByteArray(RuntimeKeys.SAVED_STATE_PAYLOAD, payload);
        if (persistablePayload.length != 0) {
            details.putByteArray(RuntimeKeys.SAVED_STATE_PERSISTABLE_PAYLOAD, persistablePayload);
        }
        copySimpleStateValues(details, state);
        return details;
    }

    private static Bundle callbackStateDetails(Bundle state, PersistableBundle persistentState) {
        Bundle details = new Bundle();
        details.putBoolean("savedStatePresent", state != null);
        details.putInt("savedStateKeyCount", state == null ? 0 : state.keySet().size());
        details.putBoolean("persistableStatePresent", persistentState != null);
        if (persistentState != null) {
            details.putInt("persistableStateKeyCount", persistentState.keySet().size());
        }
        return details;
    }

    private static void copySimpleStateValues(Bundle target, Bundle state) {
        if (state == null) return;
        for (String key : state.keySet()) {
            try {
                Object value = state.get(key);
                if (value == null) continue;
                if (value instanceof String || value instanceof Number
                        || value instanceof Boolean || value instanceof Character
                        || value instanceof CharSequence) {
                    target.putString(RuntimeKeys.SAVED_STATE_PREFIX + key, String.valueOf(value));
                }
            } catch (Throwable ignored) {
                // The opaque Parcel is authoritative. A broken diagnostic value must not block
                // the real ActivityThread save transaction.
            }
        }
    }

    private static Bundle legacySavedState(Bundle envelope) {
        Bundle state = new Bundle();
        for (String key : envelope.keySet()) {
            if (!key.startsWith(RuntimeKeys.SAVED_STATE_PREFIX)) continue;
            String stateKey = key.substring(RuntimeKeys.SAVED_STATE_PREFIX.length());
            if (stateKey.isEmpty()) continue;
            Object value = envelope.get(key);
            if (value instanceof String stringValue) state.putString(stateKey, stringValue);
        }
        return state.isEmpty() ? null : state;
    }

    private void callFrameworkCallback(Activity activity, String event, Bundle details,
                                       Runnable callback) {
        Launch route = launches.get(activity);
        if (route == null) {
            callback.run();
            return;
        }
        try (FrameworkClassLoaderScope ignored = enterFrameworkClassLoader()) {
            callback.run();
            emit(activity, route, event, details);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            emitFailure(activity, route, error);
            if (error instanceof RuntimeException runtime) throw runtime;
            if (error instanceof Error fatal) throw fatal;
            throw new IllegalStateException("GUEST_ACTIVITY_FRAMEWORK_CALLBACK_FAILED", error);
        }
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

    private ActivityResult invokeDelegateExecStartActivity(android.content.Context who,
            android.os.IBinder contextThread, android.os.IBinder token, Activity target,
            Intent intent, int requestCode, Bundle options) {
        try {
            java.lang.reflect.Method method = Instrumentation.class.getDeclaredMethod(
                    "execStartActivity", android.content.Context.class, android.os.IBinder.class,
                    android.os.IBinder.class, Activity.class, Intent.class, int.class,
                    Bundle.class);
            method.setAccessible(true);
            return (ActivityResult) method.invoke(delegate, who, contextThread, token, target,
                    intent, requestCode, options);
        } catch (java.lang.reflect.InvocationTargetException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(cause);
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("FRAMEWORK_DELEGATE_EXEC_START_FAILED", cause);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw new IllegalStateException("FRAMEWORK_DELEGATE_EXEC_START_UNAVAILABLE", error);
        }
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

    Activity findActivityByToken(String activityToken) {
        if (activityToken == null || activityToken.isBlank()) return null;
        synchronized (launches) {
            for (Map.Entry<Activity, Launch> entry : launches.entrySet()) {
                if (activityToken.equals(entry.getValue().activityToken)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    /**
     * Realizes only the removal half of a virtual reuse decision.  The target's onNewIntent /
     * move-to-front is intentionally NOT replayed here: it is the Host AMS/ATMS ActivityStarter
     * flag set carried by the forwarded host Intent that owns that transition.  Directly invoking
     * callActivityOnNewIntent would strip the framework-owned ClientTransaction and let a Guest
     * bridge mark a STOPPED Activity as reused without any real ActivityRecord movement.
     */
    private int finishRemovedActivities(Bundle result) {
        if (result == null) return 0;
        java.util.List<String> removed = result.getStringArrayList(RuntimeKeys.REMOVED_ACTIVITY_TOKENS);
        if (removed == null) return 0;
        int finished = 0;
        for (String token : removed) {
            Activity removedActivity = findActivityByToken(token);
            if (removedActivity == null) continue;
            removedActivity.finish();
            finished++;
        }
        return finished;
    }

    /**
     * Broker-side APPLY_ACTIVITY_HOST_DECISION handler.  This runs on the Guest Binder thread, so
     * the concrete framework finish must be posted to the Activity main looper.  Reuse delivery is
     * left to the forwarded host Intent; this method is removal-only and never injects onNewIntent.
     */
    Bundle applyHostDecision(Bundle request) {
        Bundle out = new Bundle();
        if (request == null) {
            out.putString(RuntimeKeys.STATUS, "FAILED");
            out.putString(RuntimeKeys.ERROR_TYPE, "HOST_ACTIVITY_DECISION_MISSING");
            return out;
        }
        java.util.List<String> removed = request.getStringArrayList(RuntimeKeys.REMOVED_ACTIVITY_TOKENS);
        android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        if (removed != null) {
            for (String token : removed) {
                Activity removedActivity = findActivityByToken(token);
                if (removedActivity != null) {
                    mainHandler.post(removedActivity::finish);
                }
            }
        }
        out.putString(RuntimeKeys.STATUS, "APPLIED");
        out.putInt(RuntimeKeys.REMOVED_ACTIVITY_COUNT, removed == null ? 0 : removed.size());
        return out;
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

    private String activityInstantiationClass(String component) {
        if (component == null || component.trim().isEmpty()) return component;
        com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata.Component metadata =
                session.packageMetadata.component(component,
                        com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata.Type.ACTIVITY);
        if (metadata == null || metadata.targetActivity().isEmpty()) return component;
        return metadata.targetActivity();
    }

    private void invokeDelegateConfiguration(Activity activity, Configuration newConfig) {
        try {
            Method method = Instrumentation.class.getDeclaredMethod(
                    "callActivityOnConfigurationChanged", Activity.class, Configuration.class);
            method.setAccessible(true);
            method.invoke(delegate, activity, newConfig);
        } catch (NoSuchMethodException unavailableOnApi) {
            // Very old/compact API surfaces do not expose the Instrumentation helper.  The
            // public Activity callback is still the platform contract and is safe to invoke as a
            // compatibility fallback when ActivityThread reaches this dispatch point.
            activity.onConfigurationChanged(newConfig);
        } catch (java.lang.reflect.InvocationTargetException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(cause);
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error fatal) throw fatal;
            throw new IllegalStateException("FRAMEWORK_CONFIGURATION_CALLBACK_FAILED", cause);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw new IllegalStateException("FRAMEWORK_CONFIGURATION_CALLBACK_UNAVAILABLE", error);
        }
    }

    private static String configurationToken(Configuration configuration) {
        if (configuration == null) return "configuration-null";
        return "configuration-" + Integer.toHexString(configuration.hashCode())
                + "-" + android.os.SystemClock.elapsedRealtime();
    }

    /**
     * ActivityThread invokes Instrumentation callbacks directly from its Handler. Those calls
     * do not pass through GuestMainThreadDispatcher, so the thread context loader can otherwise
     * remain the host process loader. Android component callbacks run with the LoadedApk loader;
     * preserving that invariant is required by split loaders, WebView/U4 and JNI libraries.
     */
    private FrameworkClassLoaderScope enterFrameworkClassLoader() {
        ClassLoader guestLoader = session.context().getClassLoader();
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        if (guestLoader != null && previous != guestLoader) {
            Thread.currentThread().setContextClassLoader(guestLoader);
            return new FrameworkClassLoaderScope(previous, true);
        }
        return new FrameworkClassLoaderScope(previous, false);
    }

    private static final class FrameworkClassLoaderScope implements AutoCloseable {
        private final ClassLoader previous;
        private final boolean changed;

        FrameworkClassLoaderScope(ClassLoader previous, boolean changed) {
            this.previous = previous;
            this.changed = changed;
        }

        @Override public void close() {
            if (changed) Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private static final class Launch {
        final String token;
        final String activityToken;
        final String sessionId;
        final long generation;
        final int taskId;
        final String component;
        final Intent intent;
        final Bundle restoredState;
        final PersistableBundle restoredPersistableState;
        long savedStateVersion;
        boolean restoreCallbacksDispatched;

        Launch(String token, String activityToken, String sessionId, long generation,
               int taskId, String component, Intent intent) {
            this(token, activityToken, sessionId, generation, taskId, component, intent,
                    null, null, 0L);
        }

        Launch(String token, String activityToken, String sessionId, long generation,
               int taskId, String component, Intent intent, Bundle restoredState,
               PersistableBundle restoredPersistableState, long savedStateVersion) {
            this.token = token;
            this.activityToken = activityToken == null ? "" : activityToken;
            this.sessionId = sessionId;
            this.generation = generation;
            this.taskId = taskId;
            this.component = component;
            this.intent = intent;
            this.restoredState = restoredState;
            this.restoredPersistableState = restoredPersistableState;
            this.savedStateVersion = Math.max(0L, savedStateVersion);
        }

        synchronized long nextSavedStateVersion() {
            if (savedStateVersion == Long.MAX_VALUE) {
                throw new IllegalStateException("GUEST_ACTIVITY_SAVED_STATE_VERSION_EXHAUSTED");
            }
            return ++savedStateVersion;
        }
    }
}
