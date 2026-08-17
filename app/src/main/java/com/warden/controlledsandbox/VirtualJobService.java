package com.warden.controlledsandbox;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.app.job.JobWorkItem;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.warden.controlledsandbox.contract.IHostJobCallback;
import com.warden.controlledsandbox.contract.IPackageService;
import com.warden.controlledsandbox.contract.VirtualJobParametersSnapshot;
import com.warden.controlledsandbox.contract.VirtualJobWorkItemSnapshot;
import com.warden.controlledsandbox.runtime.protocol.RuntimePackageAuthorityCapability;
import com.warden.controlledsandbox.runtime.protocol.RuntimePackageAuthorityRecovery;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** Trusted Host JobScheduler callback bridge running in the Runtime Broker process. */
public final class VirtualJobService extends JobService {
    private static final String TAG = "CS_JOB_BRIDGE";
    private static final int MAX_START_ATTEMPTS = 20;
    private static final long START_RETRY_DELAY_MS = 250L;
    private final ConcurrentMap<Integer, JobConnection> connections = new ConcurrentHashMap<>();
    /** JobService's framework callback is main-thread owned even when Guest calls back over Binder. */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService startRetries = Executors.newScheduledThreadPool(1,
            new ThreadFactory() {
                @Override public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "cs-job-start-retry");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    @Override public boolean onStartJob(JobParameters params) {
        if (params == null) return false;
        int hostJobId = params.getJobId();
        JobConnection value = new JobConnection(hostJobId, params,
                HostJobParametersSnapshotFactory.from(params));
        if (connections.putIfAbsent(hostJobId, value) != null) return true;
        boolean bound;
        try {
            bound = bindService(new Intent(this, PackageManagementService.class), value,
                    Context.BIND_AUTO_CREATE);
        } catch (RuntimeException error) { bound = false; }
        if (!bound) {
            connections.remove(hostJobId, value);
            return false;
        }
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        if (params == null) return true;
        int hostJobId = params.getJobId();
        int stopReason = HostJobParametersSnapshotFactory.stopReason(params);
        int internalStopReason = HostJobParametersSnapshotFactory.internalStopReason(params);
        String debugStopReason = HostJobParametersSnapshotFactory.debugStopReason(params);
        boolean frameworkAutoFinished = HostJobParametersSnapshotFactory.isSuccessfulFinish(params);
        JobConnection value = connections.remove(hostJobId);
        if (value == null) {
            Log.i(TAG, "JOB_STOP_IGNORED hostJobId=" + hostJobId
                    + " autoFinished=" + frameworkAutoFinished);
            return !frameworkAutoFinished;
        }
        value.markStopped();
        boolean reschedule = true;
        IPackageService service = value.service;
        if (service != null) {
            try {
                reschedule = service.stopVirtualJobWithCapability(hostJobId,
                        stopReason, internalStopReason, debugStopReason,
                        RuntimePackageAuthorityCapability.token(),
                        RuntimePackageAuthorityCapability.epochMarker());
            } catch (Exception error) {
                Log.e(TAG, "JOB_STOP_FORWARD_FAILED hostJobId=" + hostJobId
                        + " autoFinished=" + frameworkAutoFinished, error);
                reschedule = true;
            }
        }
        if (frameworkAutoFinished) {
            // JobParameters.dequeueWork() owns this successful terminal transition. Android
            // deliberately calls onStopJob() instead of requiring a later jobFinished() call.
            // The Guest callback may still be unwinding its synchronous dequeue loop; the
            // generation-fenced Package Service completion must therefore be idempotent.
            Log.i(TAG, "JOB_AUTO_FINISHED_RECEIVED hostJobId=" + hostJobId
                    + " internalStopReason=" + internalStopReason
                    + " debug=" + debugStopReason);
            reschedule = false;
        } else {
            Log.i(TAG, "JOB_STOP_RECEIVED hostJobId=" + hostJobId
                    + " stopReason=" + stopReason
                    + " internalStopReason=" + internalStopReason
                    + " reschedule=" + reschedule);
        }
        unbind(value);
        return reschedule;
    }

    @Override public void onDestroy() {
        startRetries.shutdownNow();
        for (JobConnection value : connections.values()) {
            if (connections.remove(value.hostJobId, value)) {
                value.markStopped();
                IPackageService service = value.service;
                if (service != null) {
                    boolean frameworkAutoFinished = value.queueDrained;
                    int internalStopReason = frameworkAutoFinished ? 10 : -1;
                    String debugStopReason = frameworkAutoFinished
                            ? "last work dequeued" : "host JobService destroyed";
                    try { service.stopVirtualJobWithCapability(value.hostJobId, 0,
                            internalStopReason, debugStopReason,
                            RuntimePackageAuthorityCapability.token(),
                            RuntimePackageAuthorityCapability.epochMarker()); }
                    catch (Exception error) {
                        Log.e(TAG, "JOB_DESTROY_FORWARD_FAILED hostJobId="
                                + value.hostJobId + " autoFinished=" + frameworkAutoFinished,
                                error);
                    }
                    if (frameworkAutoFinished) {
                        Log.i(TAG, "JOB_AUTO_FINISHED_RECEIVED hostJobId=" + value.hostJobId
                                + " source=service_destroy");
                    } else {
                        Log.i(TAG, "JOB_DESTROY_RECEIVED hostJobId=" + value.hostJobId);
                    }
                }
                unbind(value);
            }
        }
        super.onDestroy();
    }

    private void complete(JobConnection value, boolean needsReschedule) {
        if (value.stopped || !connections.remove(value.hostJobId, value)) {
            Log.i(TAG, "JOB_FINISH_IGNORED hostJobId=" + value.hostJobId
                    + " stopped=" + value.stopped);
            return;
        }
        value.markStopped();
        Runnable finish = () -> {
            try {
                // Android's JobService callback state is dispatched from its main Handler. A
                // Guest jobFinished() arrives through Package Service Binder, so invoking this
                // directly on that Binder thread races onStopJob()/unbind and can silently lose
                // the host receipt even though WorkItem completion already succeeded.
                jobFinished(value.parameters, needsReschedule);
                Log.i(TAG, "JOB_FINISHED_RECEIVED hostJobId=" + value.hostJobId
                        + " reschedule=" + needsReschedule);
            } catch (RuntimeException error) {
                Log.e(TAG, "JOB_FINISHED_FAILED hostJobId=" + value.hostJobId
                        + " type=" + error.getClass().getSimpleName(), error);
            } finally {
                unbind(value);
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) finish.run();
        else if (!mainHandler.post(finish)) {
            Log.e(TAG, "JOB_FINISHED_MAIN_DISPATCH_FAILED hostJobId=" + value.hostJobId);
            unbind(value);
        }
    }

    private void unbind(ServiceConnection value) {
        try { unbindService(value); } catch (RuntimeException ignored) { }
    }

    private final class JobConnection implements ServiceConnection {
        final int hostJobId;
        final JobParameters parameters;
        final VirtualJobParametersSnapshot snapshot;
        final IHostJobCallback callback;
        volatile IPackageService service;
        volatile boolean stopped;
        private ScheduledFuture<?> startRetry;
        private int startAttempts;
        private volatile boolean queueDrained;
        private final Map<Integer, JobWorkItem> dequeuedWork = new LinkedHashMap<>();
        private int nextProjectedWorkId = 1;

        JobConnection(int hostJobId, JobParameters parameters,
                      VirtualJobParametersSnapshot snapshot) {
            this.hostJobId = hostJobId; this.parameters = parameters; this.snapshot = snapshot;
            callback = new IHostJobCallback.Stub() {
                @Override public void finishHostJob(int reportedHostJobId, boolean needsReschedule) {
                    if (reportedHostJobId != JobConnection.this.hostJobId) {
                        throw new SecurityException("HOST_JOB_CALLBACK_ID_MISMATCH");
                    }
                    complete(JobConnection.this, needsReschedule);
                }
                @Override public VirtualJobWorkItemSnapshot dequeueHostWork(int reportedHostJobId) {
                    verifyHostJobId(reportedHostJobId);
                    return JobConnection.this.dequeueWork();
                }
                @Override public boolean completeHostWork(int reportedHostJobId, int workId) {
                    verifyHostJobId(reportedHostJobId);
                    return JobConnection.this.completeWork(workId);
                }
            };
        }

        private void verifyHostJobId(int reportedHostJobId) {
            if (reportedHostJobId != hostJobId) {
                throw new SecurityException("HOST_JOB_CALLBACK_ID_MISMATCH");
            }
        }

        /** Runs on the trusted callback path; the real JobParameters remains Host-owned. */
        private synchronized VirtualJobWorkItemSnapshot dequeueWork() {
            if (stopped) return null;
            JobWorkItem item = parameters.dequeueWork();
            if (item == null) {
                // JobScheduler treats an empty queue with no outstanding work as a successful
                // terminal transition and may destroy the Host JobService without calling
                // onStopJob(). Keep this fact on the exact JobConnection so onDestroy can
                // complete the virtual Job rather than misclassifying it as a crash/stop.
                queueDrained = true;
                return null;
            }
            if (dequeuedWork.size() >= 128) {
                throw new IllegalStateException("VIRTUAL_JOB_WORK_ITEM_LIMIT_EXCEEDED");
            }
            int projectedWorkId = nextProjectedWorkId++;
            if (projectedWorkId <= 0) throw new IllegalStateException("VIRTUAL_JOB_WORK_ID_EXHAUSTED");
            VirtualJobWorkItemSnapshot snapshot = HostJobParametersSnapshotFactory.workItem(
                    item, projectedWorkId);
            if (dequeuedWork.putIfAbsent(snapshot.workId(), item) != null) {
                throw new IllegalStateException("VIRTUAL_JOB_WORK_ITEM_ID_REUSED");
            }
            return snapshot;
        }

        /** Completion is one-shot and is fenced by the Host JobService connection. */
        private synchronized boolean completeWork(int workId) {
            if (stopped) return false;
            JobWorkItem item = dequeuedWork.get(workId);
            if (item == null) return false;
            try {
                // API 32 exposes completeWork() as void.  The Host acknowledgement boundary
                // is therefore the no-exception return; retain the mapping when the framework
                // rejects the call by throwing so a retry remains possible.
                parameters.completeWork(item);
                dequeuedWork.remove(workId);
                return true;
            } catch (RuntimeException error) {
                return false;
            }
        }

        synchronized void markStopped() {
            stopped = true;
            ScheduledFuture<?> retry = startRetry;
            startRetry = null;
            if (retry != null) retry.cancel(false);
            dequeuedWork.clear();
        }

        private void scheduleStartAttempt(long delayMs) {
            synchronized (this) {
                if (stopped || startRetry != null) return;
                startRetry = startRetries.schedule(() -> {
                    synchronized (JobConnection.this) { startRetry = null; }
                    attemptStart();
                }, delayMs, TimeUnit.MILLISECONDS);
            }
        }

        /**
         * Package Service and the Guest ActivityThread are separate process leases. After a
         * force-stop/recovery, JobScheduler can reconnect to this Host service before the
         * generation-scoped virtual system-service client has committed its Binder registration.
         * A single false result used to lose the dispatch even though the Guest became ready
         * milliseconds later. Retry only while this exact Host JobConnection is alive; the
         * Broker still validates package, process, generation and callback identity on every
         * attempt.
         */
        private void attemptStart() {
            synchronized (this) {
                if (stopped) return;
                if (startAttempts >= MAX_START_ATTEMPTS) {
                    Log.w(TAG, "JOB_START_RETRY_EXHAUSTED hostJobId=" + hostJobId);
                    complete(JobConnection.this, true);
                    return;
                }
                startAttempts++;
            }
            boolean accepted = false;
            try {
                if (service != null) {
                    try {
                        accepted = service.startVirtualJobWithCapability(snapshot, callback,
                                RuntimePackageAuthorityCapability.token(),
                                RuntimePackageAuthorityCapability.epochMarker());
                    } catch (SecurityException capabilityFailure) {
                        if (!RuntimePackageAuthorityRecovery.isCapabilityFailure(capabilityFailure)) {
                            throw capabilityFailure;
                        }
                        RuntimePackageAuthorityRecovery.register(service);
                        accepted = service.startVirtualJobWithCapability(snapshot, callback,
                                RuntimePackageAuthorityCapability.token(),
                                RuntimePackageAuthorityCapability.epochMarker());
                    }
                }
            } catch (Exception error) {
                Log.w(TAG, "JOB_START_ATTEMPT_FAILED hostJobId=" + hostJobId
                        + " attempt=" + startAttempts + " type="
                        + error.getClass().getSimpleName());
            }
            if (accepted) {
                Log.i(TAG, "JOB_START_ACCEPTED hostJobId=" + hostJobId
                        + " attempt=" + startAttempts);
                return;
            }
            synchronized (this) {
                if (stopped) return;
                if (startAttempts < MAX_START_ATTEMPTS) {
                    scheduleStartAttempt(START_RETRY_DELAY_MS);
                    return;
                }
                Log.w(TAG, "JOB_START_RETRY_EXHAUSTED hostJobId=" + hostJobId);
            }
            complete(JobConnection.this, true);
        }

        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (stopped) { unbind(this); return; }
            service = IPackageService.Stub.asInterface(binder);
            if (service == null) { complete(this, true); return; }
            scheduleStartAttempt(0L);
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            service = null; complete(this, true);
        }
        @Override public void onBindingDied(ComponentName name) {
            service = null; complete(this, true);
        }
        @Override public void onNullBinding(ComponentName name) {
            service = null; complete(this, true);
        }
    }
}
