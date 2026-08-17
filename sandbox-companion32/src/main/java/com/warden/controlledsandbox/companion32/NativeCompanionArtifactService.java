package com.warden.controlledsandbox.companion32;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import com.warden.controlledsandbox.contract.INativeCompanionArtifactService;
import com.warden.controlledsandbox.contract.NativeCompanionArtifactRequest;
import com.warden.controlledsandbox.contract.NativeCompanionArtifactResult;
import com.warden.controlledsandbox.contract.NativeCompanionRequest;

/** Signature-permission endpoint that stages Host-private artifacts into Companion-private storage. */
public final class NativeCompanionArtifactService extends Service {
    private NativeCompanionWorkspaceStore workspaces;

    private final INativeCompanionArtifactService.Stub binder =
            new INativeCompanionArtifactService.Stub() {
        @Override public NativeCompanionArtifactResult prepareWorkspace(NativeCompanionRequest request) {
            NativeCompanionCallerGuard.requireSignedPeer(NativeCompanionArtifactService.this);
            return workspaces.prepare(request);
        }

        @Override public NativeCompanionArtifactResult inspectArtifact(
                NativeCompanionArtifactRequest request) {
            NativeCompanionCallerGuard.requireSignedPeer(NativeCompanionArtifactService.this);
            return workspaces.inspect(request);
        }

        @Override public NativeCompanionArtifactResult stageArtifact(
                NativeCompanionArtifactRequest request, ParcelFileDescriptor source) {
            NativeCompanionCallerGuard.requireSignedPeer(NativeCompanionArtifactService.this);
            return workspaces.stage(request, source);
        }

        @Override public NativeCompanionArtifactResult clearWorkspace(NativeCompanionRequest request) {
            NativeCompanionCallerGuard.requireSignedPeer(NativeCompanionArtifactService.this);
            return workspaces.clear(request);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        workspaces = new NativeCompanionWorkspaceStore(getFilesDir());
    }

    @Override public IBinder onBind(Intent intent) { return binder; }
}
