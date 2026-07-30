package com.warden.controlledsandbox.contract;
import android.os.Bundle;
import com.warden.controlledsandbox.contract.RuntimeOperationRequest;
import com.warden.controlledsandbox.contract.RuntimeOperationResult;
interface IGuestProcess {
    RuntimeOperationResult executeV2(in RuntimeOperationRequest request);
    Bundle prepareGuest(in Bundle request);
    Bundle invokeComponent(in Bundle request);
    Bundle runtimeStatus();
    void shutdown(String sessionId, long generation);
}
