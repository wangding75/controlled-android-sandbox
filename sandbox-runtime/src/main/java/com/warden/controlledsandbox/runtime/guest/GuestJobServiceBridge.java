package com.warden.controlledsandbox.runtime.guest;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.app.job.JobWorkItem;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.PersistableBundle;
import com.warden.controlledsandbox.framework.identity.VirtualSystemServiceAuthority;
import com.warden.controlledsandbox.contract.VirtualJobWorkItemSnapshot;
import com.warden.controlledsandbox.runtime.diagnostics.RuntimeEventLog;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Executes Binder-delivered jobs against Guest JobService instances. */
final class GuestJobServiceBridge implements AutoCloseable {
    private final GuestRuntimeEnvironment.Session session;
    private final Map<String, JobService> services = new LinkedHashMap<>();
    private final Map<Integer, RunningJob> running = new LinkedHashMap<>();
    private final java.util.Set<Integer> starting = new java.util.LinkedHashSet<>();
    /** Generation teardown must win over a queued JobScheduler callback or service start. */
    private volatile boolean closed;

    GuestJobServiceBridge(GuestRuntimeEnvironment.Session session) {
        this.session = java.util.Objects.requireNonNull(session, "session");
    }

    boolean start(int guestJobId, Object jobPayload,
            VirtualSystemServiceAuthority.JobParametersRecord parameters,
            VirtualSystemServiceAuthority.JobExecution execution) {
        if (closed) return false;
        if (guestJobId < 0 || parameters == null || execution == null
                || execution.guestJobId() != guestJobId
                || execution.generation() != session.spec.generation
                || execution.dispatchToken() != parameters.dispatchToken() || !execution.active()) {
            return false;
        }
        synchronized (this) {
            if (closed) return false;
            RunningJob existing = running.get(guestJobId);
            if ((existing != null && existing.execution.active()) || !starting.add(guestJobId)) return false;
        }
        String className;
        JobService service;
        try {
            className = serviceClass(jobPayload);
            service = service(className);
        } catch (RuntimeException error) {
            synchronized (this) { starting.remove(guestJobId); }
            throw error;
        }
        if (closed) {
            synchronized (this) { starting.remove(guestJobId); }
            return false;
        }
        GuestJobCallbackBinder callback = new GuestJobCallbackBinder(guestJobId, execution,
                () -> removeFinished(guestJobId));
        JobParameters jobParameters = GuestJobParametersFactory.create(parameters, callback);
        boolean ongoing;
        try {
            ongoing = onMain(() -> {
                if (closed) return false;
                return service.onStartJob(jobParameters);
            });
        } catch (RuntimeException error) {
            synchronized (this) { starting.remove(guestJobId); }
            callback.invalidate();
            event("GUEST_JOB_START_FAILED", guestJobId, className, error.getClass().getSimpleName());
            return false;
        }
        if (closed) {
            synchronized (this) { starting.remove(guestJobId); }
            callback.invalidate();
            return false;
        }
        if (!ongoing) {
            synchronized (this) { starting.remove(guestJobId); }
            callback.finish(false);
            event("GUEST_JOB_FINISHED_SYNCHRONOUSLY", guestJobId, className, "");
            return true;
        }
        synchronized (this) {
            if (closed) {
                starting.remove(guestJobId);
                callback.invalidate();
                return false;
            }
            starting.remove(guestJobId);
            if (callback.active()) {
                RunningJob previous = running.putIfAbsent(guestJobId,
                        new RunningJob(className, service, jobParameters, callback, execution));
                if (previous != null) {
                    callback.invalidate();
                    event("GUEST_JOB_DUPLICATE_START", guestJobId, className, "");
                    return false;
                }
            }
        }
        event("GUEST_JOB_STARTED", guestJobId, className, "");
        return true;
    }

    boolean stop(int guestJobId,
            VirtualSystemServiceAuthority.JobParametersRecord parameters) {
        if (closed) return true;
        RunningJob value;
        synchronized (this) { value = running.remove(guestJobId); }
        if (value == null) return true;
        GuestJobParametersFactory.applyStopReason(value.parameters, parameters);
        value.callback.invalidate();
        boolean reschedule;
        try {
            reschedule = onMain(() -> value.service.onStopJob(value.parameters));
        } catch (RuntimeException error) {
            reschedule = true;
            event("GUEST_JOB_STOP_FAILED", guestJobId, value.className,
                    error.getClass().getSimpleName());
        }
        event("GUEST_JOB_STOPPED", guestJobId, value.className,
                reschedule ? "RESCHEDULE" : "DROP");
        return reschedule;
    }

    synchronized int runningCount() { return running.size(); }

    @Override public void close() {
        List<RunningJob> active;
        List<JobService> created;
        synchronized (this) {
            if (closed) return;
            closed = true;
            active = new ArrayList<>(running.values()); running.clear(); starting.clear();
            created = new ArrayList<>(services.values()); services.clear();
        }
        for (RunningJob value : active) {
            value.callback.invalidate();
            try { onMain(() -> value.service.onStopJob(value.parameters)); }
            catch (RuntimeException ignored) { }
        }
        for (JobService service : created) {
            try { onMain(() -> { service.onDestroy(); return false; }); }
            catch (RuntimeException ignored) { }
        }
    }

    private synchronized void removeFinished(int guestJobId) { running.remove(guestJobId); }

    private JobService service(String className) {
        synchronized (this) {
            if (closed) throw new IllegalStateException("GUEST_JOB_BRIDGE_CLOSED");
            JobService existing = services.get(className);
            if (existing != null) return existing;
        }
        JobService created;
        try {
            created = onMain(() -> {
                if (closed) throw new IllegalStateException("GUEST_JOB_BRIDGE_CLOSED");
                Class<?> type = GuestDefiningLoader.loadComponent(session, className);
                if (!JobService.class.isAssignableFrom(type)) {
                    throw new IllegalArgumentException("Component is not a JobService: " + className);
                }
                // JobService is a Service component.  ActivityThread/LoadedApk uses the
                // application's AppComponentFactory for it as well; direct reflection here
                // bypassed factory-installed loader/bootstrap state and made JobScheduler a
                // semantic exception to the normal component lifecycle.
                Service instantiated = GuestComponentFactory.instantiateService(
                        GuestDefiningLoader.of(session),
                        session.context.getApplicationInfo().appComponentFactory,
                        className, new Intent("android.app.job.JobService"));
                if (!(instantiated instanceof JobService)) {
                    throw new IllegalArgumentException("Factory did not create a JobService: "
                            + className);
                }
                JobService value = (JobService) instantiated;
                attachBaseContext(value, session.context);
                setOptionalField(value, "mApplication", session.application);
                setOptionalField(value, "mClassName", className);
                value.onCreate();
                // JobService.jobFinished() delegates through the engine initialized by
                // onBind().  Guest services are instantiated directly by the bridge, so
                // reproduce that lifecycle edge before delivering the first callback.
                value.onBind(new Intent("android.app.job.JobService"));
                return value;
            });
        } catch (RuntimeException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException("GUEST_JOB_SERVICE_CREATE_FAILED", error); }
        JobService existing;
        synchronized (this) {
            if (closed) {
                existing = null;
            } else {
                existing = services.putIfAbsent(className, created);
            }
        }
        if (closed) {
            try { onMain(() -> { created.onDestroy(); return false; }); }
            catch (RuntimeException ignored) { }
            throw new IllegalStateException("GUEST_JOB_BRIDGE_CLOSED");
        }
        if (existing == null) return created;
        try { onMain(() -> { created.onDestroy(); return false; }); }
        catch (RuntimeException ignored) { }
        return existing;
    }

    private static String serviceClass(Object jobPayload) {
        if (jobPayload == null) throw new IllegalArgumentException("VIRTUAL_JOB_INFO_REQUIRED");
        try {
            Method getService = jobPayload.getClass().getMethod("getService");
            Object component = getService.invoke(jobPayload);
            if (component instanceof ComponentName name && !name.getClassName().trim().isEmpty()) {
                return name.getClassName().trim();
            }
        } catch (ReflectiveOperationException error) {
            throw new IllegalArgumentException("VIRTUAL_JOB_SERVICE_UNRESOLVED", error);
        }
        throw new IllegalArgumentException("VIRTUAL_JOB_SERVICE_UNRESOLVED");
    }

    private <T> T onMain(ThrowingSupplier<T> action) {
        return session.mainThread.call(action::get);
    }

    private static void attachBaseContext(ContextWrapper wrapper, Context context) throws Exception {
        Method method = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
        method.setAccessible(true); method.invoke(wrapper, context);
    }
    private static void setOptionalField(Object target, String name, Object value) {
        for (Class<?> cursor = target.getClass(); cursor != null; cursor = cursor.getSuperclass()) {
            try {
                Field field = cursor.getDeclaredField(name); field.setAccessible(true); field.set(target, value); return;
            } catch (NoSuchFieldException ignored) { }
            catch (Throwable ignored) { com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(ignored); return; }
        }
    }
    private void event(String name, int guestJobId, String className, String detail) {
        android.os.Bundle out = new android.os.Bundle();
        out.putInt("guestJobId", guestJobId); out.putString("component", className);
        out.putString("detail", detail); out.putLong("generation", session.spec.generation);
        RuntimeEventLog.event(name, out);
    }

    private record RunningJob(String className, JobService service, JobParameters parameters,
                              GuestJobCallbackBinder callback,
                              VirtualSystemServiceAuthority.JobExecution execution) { }
    @FunctionalInterface private interface ThrowingSupplier<T> { T get() throws Exception; }

    /** Raw callback accepted by hidden JobParameters constructors. */
    static final class GuestJobCallbackBinder extends Binder {
        private static final String DESCRIPTOR = "android.app.job.IJobCallback";
        private static final int DEQUEUE_WORK_TRANSACTION = transactionCode(
                "TRANSACTION_dequeueWork", 3);
        private static final int COMPLETE_WORK_TRANSACTION = transactionCode(
                "TRANSACTION_completeWork", 4);
        private static final int JOB_FINISHED_TRANSACTION = transactionCode(
                "TRANSACTION_jobFinished", 5);
        private final int guestJobId;
        private final VirtualSystemServiceAuthority.JobExecution execution;
        private final Runnable completion;
        private boolean active = true;

        GuestJobCallbackBinder(int guestJobId, VirtualSystemServiceAuthority.JobExecution execution,
                               Runnable completion) {
            this.guestJobId = guestJobId; this.execution = execution;
            this.completion = completion == null ? () -> { } : completion;
        }
        synchronized boolean active() { return active && execution.active(); }
        synchronized void finish(boolean needsReschedule) {
            if (!active) return; active = false;
            try { execution.finish(needsReschedule); }
            finally { completion.run(); }
        }
        synchronized void invalidate() { active = false; }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(DESCRIPTOR); return true;
            }
            if (code == DEQUEUE_WORK_TRANSACTION) {
                data.enforceInterface(DESCRIPTOR);
                int reportedJobId = data.readInt();
                if (reportedJobId != guestJobId) {
                    throw new SecurityException("VIRTUAL_JOB_CALLBACK_ID_MISMATCH");
                }
                VirtualJobWorkItemSnapshot snapshot = active() ? execution.dequeueWork() : null;
                JobWorkItem item = GuestJobWorkItemFactory.create(snapshot);
                if (reply != null) {
                    reply.writeNoException();
                    if (item == null) reply.writeInt(0);
                    else { reply.writeInt(1); item.writeToParcel(reply, 1); }
                }
                return true;
            }
            if (code == COMPLETE_WORK_TRANSACTION) {
                data.enforceInterface(DESCRIPTOR);
                int reportedJobId = data.readInt();
                int workId = data.readInt();
                if (reportedJobId != guestJobId) {
                    throw new SecurityException("VIRTUAL_JOB_CALLBACK_ID_MISMATCH");
                }
                boolean completed = active() && execution.completeWork(workId);
                if (reply != null) { reply.writeNoException(); reply.writeInt(completed ? 1 : 0); }
                return true;
            }
            if (code == JOB_FINISHED_TRANSACTION) {
                data.enforceInterface(DESCRIPTOR);
                int reportedJobId = data.readInt();
                boolean needsReschedule = data.readInt() != 0;
                if (reportedJobId != guestJobId) {
                    throw new SecurityException("VIRTUAL_JOB_CALLBACK_ID_MISMATCH");
                }
                finish(needsReschedule);
                if (reply != null) reply.writeNoException();
                return true;
            }
            try { return super.onTransact(code, data, reply, flags); }
            catch (Exception error) { throw new IllegalStateException(error); }
        }
        private static int transactionCode(String fieldName, int fallback) {
            try {
                Class<?> type = Class.forName("android.app.job.IJobCallback$Stub");
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true); return field.getInt(null);
            } catch (Throwable ignored) {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(ignored);
                return fallback;
            }
        }
    }

    /** Reconstructs a real framework JobWorkItem only after the Guest callback asks for it. */
    static final class GuestJobWorkItemFactory {
        static JobWorkItem create(VirtualJobWorkItemSnapshot value) {
            if (value == null) return null;
            Intent intent = parcelable(value.intent(), Intent.class);
            if (intent == null) throw new IllegalStateException("VIRTUAL_JOB_WORK_INTENT_MISSING");
            JobWorkItem item = new JobWorkItem(intent);
            if (!setField(item, value.workId(), "mWorkId", "workId")) {
                throw new IllegalStateException("VIRTUAL_JOB_WORK_ID_FIELD_UNSUPPORTED");
            }
            setField(item, value.deliveryCount(), "mDeliveryCount", "deliveryCount");
            setField(item, value.estimatedNetworkDownloadBytes(),
                    "mEstimatedNetworkDownloadBytes", "estimatedNetworkDownloadBytes");
            setField(item, value.estimatedNetworkUploadBytes(),
                    "mEstimatedNetworkUploadBytes", "estimatedNetworkUploadBytes");
            setField(item, value.minimumNetworkChunkBytes(),
                    "mMinimumNetworkChunkBytes", "minimumNetworkChunkBytes");
            Object extras = parcelable(value.extras(), PersistableBundle.class);
            if (extras != null) setField(item, extras, "mExtras", "extras");
            return item;
        }

        private static <T> T parcelable(byte[] payload, Class<T> expected) {
            if (payload == null || payload.length == 0) return null;
            Parcel parcel = Parcel.obtain();
            try {
                parcel.unmarshall(payload, 0, payload.length);
                parcel.setDataPosition(0);
                Object value = parcel.readParcelable(expected.getClassLoader());
                return expected == Object.class || expected.isInstance(value)
                        ? expected.cast(value) : null;
            } finally { parcel.recycle(); }
        }

        private static boolean setField(Object target, Object value, String... names) {
            for (String name : names) {
                for (Class<?> cursor = target.getClass(); cursor != null; cursor = cursor.getSuperclass()) {
                    try {
                        Field field = cursor.getDeclaredField(name);
                        field.setAccessible(true); field.set(target, value); return true;
                    } catch (NoSuchFieldException ignored) {
                    } catch (Throwable error) {
                        com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
                        return false;
                    }
                }
            }
            return false;
        }
    }

    /** Reflective constructor adapter across Android JobParameters revisions. */
    static final class GuestJobParametersFactory {
        static JobParameters create(VirtualSystemServiceAuthority.JobParametersRecord value,
                                    IBinder callback) {
            if (value == null || value.guestJobId() < 0 || callback == null) {
                throw new IllegalArgumentException("invalid Guest JobParameters input");
            }
            List<Constructor<?>> constructors = new ArrayList<>(java.util.Arrays.asList(JobParameters.class.getDeclaredConstructors()));
            constructors.sort((left, right) -> Integer.compare(right.getParameterCount(), left.getParameterCount()));
            Throwable last = null;
            for (Constructor<?> constructor : constructors) {
                try {
                    Object[] arguments = constructorArguments(constructor.getParameterTypes(), value, callback);
                    if (arguments == null) continue;
                    constructor.setAccessible(true);
                    JobParameters parameters = (JobParameters) constructor.newInstance(arguments);
                    applyStopReason(parameters, value);
                    return parameters;
                } catch (Throwable error) { com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error); last = error; }
            }
            throw new IllegalStateException("GUEST_JOB_PARAMETERS_CONSTRUCTOR_UNSUPPORTED", last);
        }

        static void applyStopReason(JobParameters parameters,
                                    VirtualSystemServiceAuthority.JobParametersRecord value) {
            if (parameters == null || value == null) return;
            for (Method method : JobParameters.class.getDeclaredMethods()) {
                if (!"setStopReason".equals(method.getName())) continue;
                try {
                    method.setAccessible(true);
                    if (method.getParameterCount() == 3) {
                        method.invoke(parameters, value.stopReason(), value.internalStopReason(),
                                value.debugStopReason()); return;
                    }
                    if (method.getParameterCount() == 1) {
                        method.invoke(parameters, value.stopReason()); return;
                    }
                } catch (Throwable ignored) { com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(ignored); }
            }
            setField(parameters, value.stopReason(), "mStopReason", "stopReason");
            setField(parameters, value.internalStopReason(), "mInternalStopReason", "internalStopReason");
            setField(parameters, value.debugStopReason(), "debugStopReason", "mDebugStopReason");
        }

        private static Object[] constructorArguments(Class<?>[] types,
                VirtualSystemServiceAuthority.JobParametersRecord value, IBinder callback) {
            boolean hasBinder = false, hasJobId = false;
            for (Class<?> type : types) {
                if (IBinder.class.isAssignableFrom(type)) hasBinder = true;
                if (type == int.class || type == Integer.class) hasJobId = true;
            }
            if (!hasBinder || !hasJobId) return null;
            Object[] out = new Object[types.length];
            int intIndex = 0, booleanIndex = 0, stringIndex = 0;
            for (int index = 0; index < types.length; index++) {
                Class<?> type = types[index]; String name = type.getName();
                if (IBinder.class.isAssignableFrom(type)) out[index] = callback;
                else if (type == int.class || type == Integer.class) {
                    out[index] = intIndex++ == 0 ? value.guestJobId() : value.clipGrantFlags();
                } else if (type == boolean.class || type == Boolean.class) {
                    out[index] = switch (booleanIndex++) {
                        case 0 -> value.overrideDeadlineExpired();
                        case 1 -> value.expedited();
                        case 2 -> value.userInitiated();
                        default -> false;
                    };
                } else if (type == long.class || type == Long.class) out[index] = 0L;
                else if (type == String.class) out[index] = stringIndex++ == 0 ? value.namespace() : "";
                else if (name.equals("android.os.PersistableBundle")) out[index] = compatible(type, value.extras());
                else if (name.equals("android.os.Bundle")) out[index] = compatible(type, value.transientExtras());
                else if (name.equals("android.content.ClipData")) out[index] = compatible(type, value.clipData());
                else if (name.equals("android.net.Network")) out[index] = compatible(type, value.network());
                else if (type.isArray() && type.getComponentType().getName().equals("android.net.Uri")) {
                    Object array = Array.newInstance(type.getComponentType(), value.triggeredUris().size());
                    for (int item = 0; item < value.triggeredUris().size(); item++) {
                        Array.set(array, item, Uri.parse(value.triggeredUris().get(item)));
                    }
                    out[index] = array;
                } else if (type == String[].class) out[index] = value.triggeredAuthorities().toArray(new String[0]);
                else if (type.isArray()) out[index] = Array.newInstance(type.getComponentType(), 0);
                else if (!type.isPrimitive()) out[index] = null;
                else return null;
            }
            return out;
        }
        private static Object compatible(Class<?> type, Object value) {
            return value != null && type.isInstance(value) ? value : null;
        }
        private static void setField(Object target, Object value, String... names) {
            for (String name : names) for (Class<?> cursor = target.getClass(); cursor != null; cursor = cursor.getSuperclass()) {
                try { Field field = cursor.getDeclaredField(name); field.setAccessible(true); field.set(target, value); return; }
                catch (NoSuchFieldException ignored) { }
                catch (Throwable ignored) { com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(ignored); return; }
            }
        }
    }
}
