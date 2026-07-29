package com.warden.controlledsandbox.contract;
import android.os.ParcelFileDescriptor;
import com.warden.controlledsandbox.contract.NativeCompanionArtifactRequest;
import com.warden.controlledsandbox.contract.NativeCompanionArtifactResult;
import com.warden.controlledsandbox.contract.NativeCompanionRequest;

/** Signature-protected, bounded file transfer boundary for 32-bit Guest execution. */
interface INativeCompanionArtifactService {
    NativeCompanionArtifactResult prepareWorkspace(in NativeCompanionRequest request);
    NativeCompanionArtifactResult stageArtifact(in NativeCompanionArtifactRequest request,
                                                 in ParcelFileDescriptor source);
    NativeCompanionArtifactResult clearWorkspace(in NativeCompanionRequest request);
}
