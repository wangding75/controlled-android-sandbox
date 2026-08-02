package com.warden.controlledsandbox.contract;
import com.warden.controlledsandbox.contract.NativeCompanionRequest;
import com.warden.controlledsandbox.contract.NativeCompanionResult;
import com.warden.controlledsandbox.contract.NativeCompanionIdentity;

/** Signature-permission protected cross-width Native companion contract. */
interface INativeAbiCompanion {
    NativeCompanionResult execute(in NativeCompanionRequest request);
    NativeCompanionIdentity getIdentity();
}
