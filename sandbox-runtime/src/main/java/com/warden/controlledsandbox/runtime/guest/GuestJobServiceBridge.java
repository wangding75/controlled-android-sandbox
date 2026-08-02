package com.warden.controlledsandbox.runtime.guest;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import com.warden.controlledsandbox.framework.identity.VirtualSystemServiceAuthority;
import com.warden.controlledsandbox.runtime.diagnostics.RuntimeEventLog;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Executes Binder-delivered jobs against Guest JobService instances. */
final class GuestJobServiceBridge implements AutoCloseable {
    private static final long MAIN_THREAD_TIMEOUT_MS = 10_000L;
    private final GuestRuntimeEnvironment.Session session;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, JobService> services = new LinkedHashMap<>();
    private final Map<Integer, RunningJob> running = new LinkedHashMap<>();

    GuestJobServiceBridge(GuestRuntimeEnvironment.Session session) {
        this.session = java.util.Objects.requireNonNull(session, "session");
    }

    boolean start(int guestJobId, Object jobPayload,
            VirtualSystemServiceAuthority.JobParametersRecord parameters,
            VirtualSystemServiceAuthority.JobExecution execution) {
        if (guestJobId < 0 || parameters == null || execution == null
                || execution.guestJobId() != guestJobId
                || execution.generation() != session.spec.generation
                || execution.dispatchToken() != parameters.dispatchToken() || !execution.active()) {
            return false;
        }
        synchronized (this) {
            RunningJob existing = running.get(guestJobId);
            if (existing != null && existing.execution.active()) return false;
        }
        String className = serviceClass(jobPayload);
        JobService service = service(className);
        GuestJobCallbackBinder callback = new GuestJobCallbackBinder(guestJobId, execution,
                () -> removeFinished(guestJobId));
        JobParameters jobParameters = GuestJobParametersFactory.create(parameters, callback);
        boolean ongoing;
        try {
            ongoing = onMain(() -> service.onStartJob(jobParameters));
        } catch (RuntimeException error) {
            callback.invalidate();
            event("GUEST_JOB_START_FAILED", guestJobId, className, error.getClass().getSimpleName());
            return false;
        }
        if (!ongoing) {
            callback.finish(false);
            event("GUEST_JOB_FINISHED_SYNCHRONOUSLY", guestJobId, className, "");
            return true;
        }
        synchronized (this) {
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
            active = new ArrayList<>(running.values()); running.clear();
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
            JobService existing = services.get(className);
            if (existing != null) return existing;
        }
        JobService created;
        try {
            Class<?> type = session.classLoader.loadClass(className);
            if (!JobService.class.isAssignableFrom(type)) {
                throw new IllegalArgumentException("Component is not a JobService: " + className);
            }
            created = (JobService) type.getDeclaredConstructor().newInstance();
            attachBaseContext(created, session.context);
            setOptionalField(created, "mApplication", session.application);
            setOptionalField(created, "mClassName", className);
            onMain(() -> { created.onCreate(); return false; });
        } catch (RuntimeException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException("GUEST_JOB_SERVICE_CREATE_FAILED", error); }
        JobService existing;
        synchronized (this) { existing = services.putIfAbsent(className, created); }
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
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try { return action.get(); }
            catch (RuntimeException error) { throw error; }
            catch (Exception error) { throw new IllegalStateException(error); }
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        if (!mainHandler.post(() -> {
            try { result.set(action.get()); }
            catch (Throwable error) {
                failure.set(error);
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            }
            finally { latch.countDown(); }
        })) throw new IllegalStateException("GUEST_JOB_MAIN_HANDLER_REJECTED");
        try {
            if (!latch.await(MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("GUEST_JOB_MAIN_THREAD_TIMEOUT");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GUEST_JOB_MAIN_THREAD_INTERRUPTED", error);
        }
        Throwable error = failure.get();
        if (error instanceof RuntimeException runtime) throw runtime;
        if (error != null) throw new IllegalStateException(error);
        return result.get();
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

    /** Raw callback accepted by hidden JobParameters constructors; only jobFinished is honored. */
    static final class GuestJobCallbackBinder extends Binder {
        private static final String DESCRIPTOR = "android.app.job.IJobCallback";
        private static final int JOB_FINISHED_TRANSACTION = transactionCode();
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
        private static int transactionCode() {
            try {
                Class<?> type = Class.forName("android.app.job.IJobCallback$Stub");
                Field field = type.getDeclaredField("TRANSACTION_jobFinished");
                field.setAccessible(true); return field.getInt(null);
            } catch (Throwable ignored) {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(ignored);
                // API 26+ has dequeueWork/completeWork before jobFinished.
                return 5;
            }
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
