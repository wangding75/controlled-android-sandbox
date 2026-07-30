package com.warden.controlledsandbox.contract;
import com.warden.controlledsandbox.contract.IsolatedProcessRequest;
import com.warden.controlledsandbox.contract.IsolatedProcessResult;
interface IIsolatedGuestProcess {
    IsolatedProcessResult prepare(in IsolatedProcessRequest request);
    IsolatedProcessResult invoke(in IsolatedProcessRequest request);
    IsolatedProcessResult status(in IsolatedProcessRequest request);
    void shutdown(String sessionId, long generation, String capabilityToken);
}
