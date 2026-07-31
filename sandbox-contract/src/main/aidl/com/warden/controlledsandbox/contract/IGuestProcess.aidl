package com.warden.controlledsandbox.contract;
import com.warden.controlledsandbox.contract.RuntimeOperationRequest;
import com.warden.controlledsandbox.contract.RuntimeOperationResult;
interface IGuestProcess {
    RuntimeOperationResult executeV2(in RuntimeOperationRequest request);
    void shutdown(String sessionId, long generation);
}
