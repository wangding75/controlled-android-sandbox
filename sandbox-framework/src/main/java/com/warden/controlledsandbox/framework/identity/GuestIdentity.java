package com.warden.controlledsandbox.framework.identity;

import android.content.pm.ApplicationInfo;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import com.warden.controlledsandbox.framework.capability.CapabilityAccessPolicy;
import com.warden.controlledsandbox.framework.capability.CapabilityAuditSink;
import com.warden.controlledsandbox.framework.capability.CapabilityLeaseRegistry;


public final class GuestIdentity {
    private final String packageName;
    private final int virtualUid;
    private final ApplicationInfo applicationInfo;
    private final Set<String> requestedPermissions;
    private final String hostPackageName;
    private final int hostUid;
    private final VirtualPackageMetadata packageMetadata;
    private final String processName;
    private final int virtualUserId;
    private final long generation;
    private final String packageRevision;
    private final VirtualPermissionPolicy permissionPolicy;
    private final SandboxAppOpsPolicy appOpsPolicy;
    private final CapabilityAccessPolicy capabilityPolicy;
    private final CapabilityAuditSink capabilityAudit;
    private final CapabilityLeaseRegistry capabilityLeases;
    private final VirtualSystemServiceState virtualServices;

    public GuestIdentity(String packageName, int virtualUid, ApplicationInfo applicationInfo,
                         Set<String> requestedPermissions) {
        this(packageName, virtualUid, applicationInfo, requestedPermissions, packageName, virtualUid,
                new VirtualPackageMetadata(packageName, "", applicationInfo, java.util.Collections.emptyList()),
                packageName, 0, 1L);
    }

    public GuestIdentity(String packageName, int virtualUid, ApplicationInfo applicationInfo,
                         Set<String> requestedPermissions, String hostPackageName, int hostUid) {
        this(packageName, virtualUid, applicationInfo, requestedPermissions, hostPackageName, hostUid,
                new VirtualPackageMetadata(packageName, "", applicationInfo, java.util.Collections.emptyList()),
                packageName, 0, 1L);
    }

    public GuestIdentity(String packageName, int virtualUid, ApplicationInfo applicationInfo,
                         Set<String> requestedPermissions, String hostPackageName, int hostUid,
                         VirtualPackageMetadata packageMetadata) {
        this(packageName, virtualUid, applicationInfo, requestedPermissions, hostPackageName, hostUid,
                packageMetadata, packageName, 0, 1L);
    }

    public GuestIdentity(String packageName, int virtualUid, ApplicationInfo applicationInfo,
                         Set<String> requestedPermissions, String hostPackageName, int hostUid,
                         VirtualPackageMetadata packageMetadata, String processName,
                         int virtualUserId, long generation) {
        this(packageName, virtualUid, applicationInfo, requestedPermissions, hostPackageName, hostUid,
                packageMetadata, processName, virtualUserId, generation,
                new VirtualPermissionPolicy(requestedPermissions, java.util.Map.of()),
                new SandboxAppOpsPolicy(java.util.Map.of()));
    }

    public GuestIdentity(String packageName, int virtualUid, ApplicationInfo applicationInfo,
                         Set<String> requestedPermissions, String hostPackageName, int hostUid,
                         VirtualPackageMetadata packageMetadata, String processName,
                         int virtualUserId, long generation,
                         VirtualPermissionPolicy permissionPolicy,
                         SandboxAppOpsPolicy appOpsPolicy) {
        this(packageName, virtualUid, applicationInfo, requestedPermissions, hostPackageName, hostUid,
                packageMetadata, processName, virtualUserId, generation, permissionPolicy, appOpsPolicy,
                CapabilityAuditSink.NO_OP, new CapabilityLeaseRegistry(), new VirtualSystemServiceState());
    }

    public GuestIdentity(String packageName, int virtualUid, ApplicationInfo applicationInfo,
                         Set<String> requestedPermissions, String hostPackageName, int hostUid,
                         VirtualPackageMetadata packageMetadata, String processName,
                         int virtualUserId, long generation,
                         VirtualPermissionPolicy permissionPolicy,
                         SandboxAppOpsPolicy appOpsPolicy,
                         CapabilityAuditSink capabilityAudit,
                         CapabilityLeaseRegistry capabilityLeases) {
        this(packageName, virtualUid, applicationInfo, requestedPermissions, hostPackageName, hostUid,
                packageMetadata, processName, virtualUserId, generation, permissionPolicy, appOpsPolicy,
                capabilityAudit, capabilityLeases, new VirtualSystemServiceState());
    }

    public GuestIdentity(String packageName, int virtualUid, ApplicationInfo applicationInfo,
                         Set<String> requestedPermissions, String hostPackageName, int hostUid,
                         VirtualPackageMetadata packageMetadata, String processName,
                         int virtualUserId, long generation,
                         VirtualPermissionPolicy permissionPolicy,
                         SandboxAppOpsPolicy appOpsPolicy,
                         CapabilityAuditSink capabilityAudit,
                         CapabilityLeaseRegistry capabilityLeases,
                         VirtualSystemServiceState virtualServices) {
        this(packageName, virtualUid, applicationInfo, requestedPermissions, hostPackageName, hostUid,
                packageMetadata, processName, virtualUserId, generation, permissionPolicy, appOpsPolicy,
                capabilityAudit, capabilityLeases, virtualServices, "legacy-revision");
    }

    public GuestIdentity(String packageName, int virtualUid, ApplicationInfo applicationInfo,
                         Set<String> requestedPermissions, String hostPackageName, int hostUid,
                         VirtualPackageMetadata packageMetadata, String processName,
                         int virtualUserId, long generation,
                         VirtualPermissionPolicy permissionPolicy,
                         SandboxAppOpsPolicy appOpsPolicy,
                         CapabilityAuditSink capabilityAudit,
                         CapabilityLeaseRegistry capabilityLeases,
                         VirtualSystemServiceState virtualServices,
                         String packageRevision) {
        if (packageName == null || packageName.trim().isEmpty()) {
            throw new IllegalArgumentException("packageName is required");
        }
        if (hostPackageName == null || hostPackageName.trim().isEmpty()) {
            throw new IllegalArgumentException("hostPackageName is required");
        }
        if (processName == null || processName.trim().isEmpty()) {
            throw new IllegalArgumentException("processName is required");
        }
        if (virtualUid < 0 || hostUid < 0 || virtualUserId < 0) {
            throw new IllegalArgumentException("uids and virtualUserId must be non-negative");
        }
        if (generation < 1) {
            throw new IllegalArgumentException("generation must be positive");
        }
        this.packageName = packageName;
        this.virtualUid = virtualUid;
        this.applicationInfo = new ApplicationInfo(applicationInfo);
        this.requestedPermissions = Collections.unmodifiableSet(new HashSet<>(requestedPermissions));
        this.hostPackageName = hostPackageName;
        this.hostUid = hostUid;
        this.packageMetadata = java.util.Objects.requireNonNull(packageMetadata, "packageMetadata");
        this.processName = processName;
        this.virtualUserId = virtualUserId;
        this.generation = generation;
        if (packageRevision == null || packageRevision.trim().isEmpty()) {
            throw new IllegalArgumentException("packageRevision is required");
        }
        this.packageRevision = packageRevision.trim();
        this.permissionPolicy = java.util.Objects.requireNonNull(permissionPolicy, "permissionPolicy");
        this.appOpsPolicy = java.util.Objects.requireNonNull(appOpsPolicy, "appOpsPolicy");
        this.capabilityPolicy = new CapabilityAccessPolicy(this.permissionPolicy::isGranted, this.appOpsPolicy::mode);
        this.capabilityAudit = java.util.Objects.requireNonNull(capabilityAudit, "capabilityAudit");
        this.capabilityLeases = java.util.Objects.requireNonNull(capabilityLeases, "capabilityLeases");
        this.virtualServices = java.util.Objects.requireNonNull(virtualServices, "virtualServices");
    }

    public String packageName() { return packageName; }
    public int virtualUid() { return virtualUid; }
    public ApplicationInfo applicationInfo() { return new ApplicationInfo(applicationInfo); }
    public Set<String> requestedPermissions() { return requestedPermissions; }
    public String hostPackageName() { return hostPackageName; }
    public int hostUid() { return hostUid; }
    public VirtualPackageMetadata packageMetadata() { return packageMetadata; }
    public String processName() { return processName; }
    public int virtualUserId() { return virtualUserId; }
    public long generation() { return generation; }
    public String packageRevision() { return packageRevision; }
    public VirtualPermissionPolicy permissionPolicy() { return permissionPolicy; }
    public SandboxAppOpsPolicy appOpsPolicy() { return appOpsPolicy; }
    public CapabilityAccessPolicy capabilityPolicy() { return capabilityPolicy; }
    public CapabilityAuditSink capabilityAudit() { return capabilityAudit; }
    public CapabilityLeaseRegistry capabilityLeases() { return capabilityLeases; }
    public VirtualSystemServiceState virtualServices() { return virtualServices; }
}
