package com.warden.controlledsandbox;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import com.warden.controlledsandbox.contract.IHostJobCallback;
import com.warden.controlledsandbox.contract.IPackageService;
import com.warden.controlledsandbox.contract.VirtualJobParametersSnapshot;
import com.warden.controlledsandbox.runtime.protocol.RuntimePackageAuthorityCapability;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Trusted Host JobScheduler callback bridge running in the Runtime Broker process. */
public final class VirtualJobService extends JobService {
    private final ConcurrentMap<Integer, JobConnection> connections = new ConcurrentHashMap<>();

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
        JobConnection value = connections.remove(hostJobId);
        if (value == null) return true;
        value.stopped = true;
        boolean reschedule = true;
        IPackageService service = value.service;
        if (service != null) {
            try {
                reschedule = service.stopVirtualJobWithCapability(hostJobId,
                        HostJobParametersSnapshotFactory.stopReason(params),
                        HostJobParametersSnapshotFactory.internalStopReason(params),
                        HostJobParametersSnapshotFactory.debugStopReason(params),
                        RuntimePackageAuthorityCapability.token(),
                        RuntimePackageAuthorityCapability.epochMarker());
            } catch (Exception ignored) { reschedule = true; }
        }
        unbind(value);
        return reschedule;
    }

    @Override public void onDestroy() {
        for (JobConnection value : connections.values()) {
            if (connections.remove(value.hostJobId, value)) {
                value.stopped = true;
                IPackageService service = value.service;
                if (service != null) {
                    try { service.stopVirtualJobWithCapability(value.hostJobId, 0, -1,
                            "host JobService destroyed", RuntimePackageAuthorityCapability.token(),
                            RuntimePackageAuthorityCapability.epochMarker()); }
                    catch (Exception ignored) { }
                }
                unbind(value);
            }
        }
        super.onDestroy();
    }

    private void complete(JobConnection value, boolean needsReschedule) {
        if (value.stopped || !connections.remove(value.hostJobId, value)) return;
        value.stopped = true;
        try { jobFinished(value.parameters, needsReschedule); }
        catch (RuntimeException ignored) { }
        unbind(value);
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
            };
        }

        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (stopped) { unbind(this); return; }
            service = IPackageService.Stub.asInterface(binder);
            boolean accepted = false;
            try {
                if (service != null) {
                    accepted = service.startVirtualJobWithCapability(snapshot, callback,
                            RuntimePackageAuthorityCapability.token(),
                            RuntimePackageAuthorityCapability.epochMarker());
                }
            }
            catch (Exception ignored) { accepted = false; }
            if (!accepted) complete(this, true);
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
