package com.warden.controlledsandbox;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import com.warden.controlledsandbox.contract.IPackageService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Host JobScheduler callback bridge. Runs in the trusted Runtime Broker process. */
public final class VirtualJobService extends JobService {
    private final ConcurrentMap<Integer, ServiceConnection> connections = new ConcurrentHashMap<>();

    @Override public boolean onStartJob(JobParameters params) {
        if (params == null) return false;
        int hostJobId = params.getJobId();
        Intent intent = new Intent(this, PackageManagementService.class);
        ServiceConnection value = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                boolean delivered = false;
                try {
                    IPackageService service = IPackageService.Stub.asInterface(binder);
                    delivered = service != null && service.dispatchVirtualJob(hostJobId);
                } catch (Exception ignored) { }
                finally { complete(hostJobId, params, !delivered, this); }
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                complete(hostJobId, params, true, this);
            }
        };
        if (connections.putIfAbsent(hostJobId, value) != null) return true;
        boolean bound;
        try { bound = bindService(intent, value, Context.BIND_AUTO_CREATE); }
        catch (RuntimeException error) { bound = false; }
        if (!bound) {
            connections.remove(hostJobId, value);
            return false;
        }
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        if (params == null) return true;
        ServiceConnection value = connections.remove(params.getJobId());
        if (value != null) unbind(value);
        return true;
    }

    private void complete(int hostJobId, JobParameters params, boolean needsReschedule,
                          ServiceConnection value) {
        if (!connections.remove(hostJobId, value)) return;
        try { jobFinished(params, needsReschedule); } catch (RuntimeException ignored) { }
        unbind(value);
    }

    private void unbind(ServiceConnection value) {
        try { unbindService(value); } catch (RuntimeException ignored) { }
    }
}
