package com.warden.controlledsandbox;

/** Internal pair returned under the package lifecycle lock. */
final class SandboxPackagePolicyView {
    final SandboxRecord record;
    final SandboxPolicyState policy;

    SandboxPackagePolicyView(SandboxRecord record, SandboxPolicyState policy) {
        if (record == null) throw new IllegalArgumentException("Package is not installed");
        this.record = record;
        this.policy = java.util.Objects.requireNonNull(policy, "policy");
    }
}
