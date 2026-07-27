package com.warden.controlledsandbox;

/** Internal package, policy and permission-workflow view returned under lifecycle serialization. */
final class SandboxPackagePolicyView {
    final SandboxRecord record;
    final SandboxPolicyState policy;
    final SandboxCatalogState catalog;

    SandboxPackagePolicyView(SandboxRecord record, SandboxPolicyState policy,
                             SandboxCatalogState catalog) {
        if (record == null) throw new IllegalArgumentException("Package is not installed");
        this.record = record;
        this.policy = java.util.Objects.requireNonNull(policy, "policy");
        this.catalog = java.util.Objects.requireNonNull(catalog, "catalog");
    }
}
