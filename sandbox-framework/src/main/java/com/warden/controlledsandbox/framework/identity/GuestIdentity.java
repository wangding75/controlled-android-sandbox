package com.warden.controlledsandbox.framework.identity;

import android.content.pm.ApplicationInfo;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import com.warden.controlledsandbox.framework.binder.BinderSessionFence;
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
    private final VirtualPackageUniverse packageUniverse;
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
    private final GuestInteractionState interactions;
    private final GuestNetworkState networks;
    private final AtomicBoolean binderSessionActive;
    private final BinderSessionFence binderSessionFence;
    private volatile ContentObserverBridge contentObserverBridge;

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
        this(packageName, virtualUid, applicationInfo, requestedPermissions, hostPackageName,
                hostUid, packageMetadata, processName, virtualUserId, generation,
                permissionPolicy, appOpsPolicy, capabilityAudit, capabilityLeases,
                virtualServices, packageRevision, VirtualPackageUniverse.single(packageMetadata));
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
                         String packageRevision,
                         VirtualPackageUniverse packageUniverse) {
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
        this.packageUniverse = java.util.Objects.requireNonNull(packageUniverse, "packageUniverse");
        if (this.packageUniverse.packageMetadata(packageName) == null) {
            throw new IllegalArgumentException("package universe does not contain current package");
        }
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
        this.interactions = new GuestInteractionState();
        this.networks = new GuestNetworkState();
        this.binderSessionActive = new AtomicBoolean(true);
        this.binderSessionFence = candidate -> binderSessionActive.get()
                && candidate != null
                && packageName.equals(candidate.packageName())
                && virtualUid == candidate.virtualUid()
                && virtualUserId == candidate.virtualUserId()
                && generation == candidate.generation()
                && processName.equals(candidate.processName())
                && (packageName + "@" + processName).equals(candidate.sessionId());
        this.contentObserverBridge = null;
    }

    public String packageName() { return packageName; }
    public int virtualUid() { return virtualUid; }
    public ApplicationInfo applicationInfo() { return new ApplicationInfo(applicationInfo); }
    public Set<String> requestedPermissions() { return requestedPermissions; }
    public String hostPackageName() { return hostPackageName; }
    public int hostUid() { return hostUid; }
    public VirtualPackageMetadata packageMetadata() { return packageMetadata; }
    public VirtualPackageUniverse packageUniverse() { return packageUniverse; }
    public String processName() { return processName; }
    /** Platform isolated service slots use a manifest-owned :isolated_ process identity. */
    public boolean isolatedProcess() { return processName.contains(":isolated_"); }
    public int virtualUserId() { return virtualUserId; }
    public long generation() { return generation; }
    public String packageRevision() { return packageRevision; }
    public VirtualPermissionPolicy permissionPolicy() { return permissionPolicy; }
    public SandboxAppOpsPolicy appOpsPolicy() { return appOpsPolicy; }
    public CapabilityAccessPolicy capabilityPolicy() { return capabilityPolicy; }
    public CapabilityAuditSink capabilityAudit() { return capabilityAudit; }
    public CapabilityLeaseRegistry capabilityLeases() { return capabilityLeases; }
    public VirtualSystemServiceState virtualServices() { return virtualServices; }
    public GuestInteractionState interactions() { return interactions; }
    public GuestNetworkState networks() { return networks; }

    /** Shared process-generation fence for every Binder lease created from this identity. */
    public BinderSessionFence binderSessionFence() { return binderSessionFence; }

    /** Retires all Binder leases before component/resource teardown begins. */
    public void closeBinderSession() { binderSessionActive.set(false); }

    /** Installs the process-scoped Broker relay before Framework service proxies are published. */
    public void installContentObserverBridge(ContentObserverBridge bridge) {
        this.contentObserverBridge = bridge;
    }

    public ContentObserverBridge contentObserverBridge() { return contentObserverBridge; }

    /**
     * Resolves a visible virtual Provider from its URI authority. Unknown authorities are left to
     * the virtual SystemService fallback (for example Settings on a compact test environment).
     */
    public ProviderRoute providerRoute(String authority) {
        if (authority == null || authority.trim().isEmpty()) return null;
        String normalized = authority.trim();
        for (VirtualPackageMetadata target : packageUniverse.packages()) {
            if (!packageUniverse.isVisibleTo(packageName, target.packageName())) continue;
            VirtualPackageMetadata.Component provider = target.providerComponent(normalized);
            if (provider == null || !provider.enabled()) continue;
            String process = provider.processName();
            if (process == null || process.trim().isEmpty()) process = target.packageName();
            else if (process.startsWith(":")) process = target.packageName() + process;
            return new ProviderRoute(target.packageName(), virtualUserId, process,
                    provider.className(), provider.authority());
        }
        return null;
    }

    public record ProviderRoute(String packageName, int virtualUserId, String processName,
                                String componentClass, String authority) { }
}
