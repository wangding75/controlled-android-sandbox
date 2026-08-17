package com.warden.controlledsandbox.contract;
import com.warden.controlledsandbox.contract.HostileCapabilityRequest;
import com.warden.controlledsandbox.contract.HostileCapabilityResult;
interface IHostileCapabilityBroker {
    HostileCapabilityResult readResource(in HostileCapabilityRequest request);
    HostileCapabilityResult networkRequest(in HostileCapabilityRequest request);
    HostileCapabilityResult delegateReadOnlyFd(in HostileCapabilityRequest request);
}
