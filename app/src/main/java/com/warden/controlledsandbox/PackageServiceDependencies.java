package com.warden.controlledsandbox;

import android.app.Service;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable dependency graph shared by package-service Binder capabilities. */
final class PackageServiceDependencies implements AutoCloseable {
    final Object operationLock = new Object();
    final PackageMutationCoordinator packageMutations = new PackageMutationCoordinator();
    final File filesDir;
    final SandboxPackageLifecycle lifecycle;
    final PackageCallerVerifier callerVerifier;
    final PackageAuthorityCapabilityRegistry capabilityRegistry;
    final VirtualPackageStateBuilder packageStateBuilder;
    final HostPermissionStateResolver hostPermissions;
    final VirtualSystemServiceStore systemServices;
    final VirtualDeviceServiceStore deviceServices;
    final VirtualInteractionStore interactions;
    final VirtualNetworkServiceStore networkServices;
    final ApplicationEnvironmentStore applicationEnvironment;
    final VirtualCompatibilityStore compatibility;
    final VirtualPolicyServicesStore policyServices;
    final VirtualMediaCommunicationStore mediaCommunication;
    final VirtualPeripheralServicesStore peripheralServices;
    final VirtualPrivilegedServicesStore privilegedServices;
    final RuntimeClient runtimeClient;

    static PackageServiceDependencies create(Service service, File filesDir) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(filesDir, "filesDir");
        return new PackageServiceDependencies(filesDir,
                new SandboxPackageLifecycle(service),
                new PackageCallerVerifier(service),
                new VirtualPackageStateBuilder(service),
                new HostPermissionStateResolver(service),
                new VirtualSystemServiceStore(filesDir),
                new VirtualDeviceServiceStore(filesDir),
                new VirtualInteractionStore(filesDir),
                new VirtualNetworkServiceStore(filesDir),
                new ApplicationEnvironmentStore(filesDir),
                new VirtualCompatibilityStore(filesDir),
                new VirtualPolicyServicesStore(filesDir),
                new VirtualMediaCommunicationStore(filesDir),
                new VirtualPeripheralServicesStore(filesDir),
                new VirtualPrivilegedServicesStore(filesDir), new RuntimeClient(service));
    }

    PackageServiceDependencies(
            SandboxPackageLifecycle lifecycle,
            PackageCallerVerifier callerVerifier,
            VirtualPackageStateBuilder packageStateBuilder,
            HostPermissionStateResolver hostPermissions,
            VirtualSystemServiceStore systemServices,
            VirtualDeviceServiceStore deviceServices,
            VirtualInteractionStore interactions,
            VirtualNetworkServiceStore networkServices,
            ApplicationEnvironmentStore applicationEnvironment,
            VirtualCompatibilityStore compatibility,
            VirtualPolicyServicesStore policyServices,
            VirtualMediaCommunicationStore mediaCommunication,
            VirtualPeripheralServicesStore peripheralServices,
            VirtualPrivilegedServicesStore privilegedServices) {
        this(new File(System.getProperty("java.io.tmpdir"), "controlled-sandbox-tests"),
                lifecycle, callerVerifier, packageStateBuilder, hostPermissions, systemServices,
                deviceServices, interactions, networkServices, applicationEnvironment, compatibility,
                policyServices, mediaCommunication, peripheralServices, privilegedServices, null);
    }

    PackageServiceDependencies(File filesDir,
            SandboxPackageLifecycle lifecycle,
            PackageCallerVerifier callerVerifier,
            VirtualPackageStateBuilder packageStateBuilder,
            HostPermissionStateResolver hostPermissions,
            VirtualSystemServiceStore systemServices,
            VirtualDeviceServiceStore deviceServices,
            VirtualInteractionStore interactions,
            VirtualNetworkServiceStore networkServices,
            ApplicationEnvironmentStore applicationEnvironment,
            VirtualCompatibilityStore compatibility,
            VirtualPolicyServicesStore policyServices,
            VirtualMediaCommunicationStore mediaCommunication,
            VirtualPeripheralServicesStore peripheralServices,
            VirtualPrivilegedServicesStore privilegedServices) {
        this(filesDir, lifecycle, callerVerifier, packageStateBuilder, hostPermissions, systemServices,
                deviceServices, interactions, networkServices, applicationEnvironment, compatibility,
                policyServices, mediaCommunication, peripheralServices, privilegedServices, null);
    }

    PackageServiceDependencies(File filesDir,
            SandboxPackageLifecycle lifecycle,
            PackageCallerVerifier callerVerifier,
            VirtualPackageStateBuilder packageStateBuilder,
            HostPermissionStateResolver hostPermissions,
            VirtualSystemServiceStore systemServices,
            VirtualDeviceServiceStore deviceServices,
            VirtualInteractionStore interactions,
            VirtualNetworkServiceStore networkServices,
            ApplicationEnvironmentStore applicationEnvironment,
            VirtualCompatibilityStore compatibility,
            VirtualPolicyServicesStore policyServices,
            VirtualMediaCommunicationStore mediaCommunication,
            VirtualPeripheralServicesStore peripheralServices,
            VirtualPrivilegedServicesStore privilegedServices,
            RuntimeClient runtimeClient) {
        this.filesDir = Objects.requireNonNull(filesDir, "filesDir");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.callerVerifier = Objects.requireNonNull(callerVerifier, "callerVerifier");
        this.capabilityRegistry = new PackageAuthorityCapabilityRegistry(this.callerVerifier);
        this.packageStateBuilder = Objects.requireNonNull(packageStateBuilder, "packageStateBuilder");
        this.hostPermissions = Objects.requireNonNull(hostPermissions, "hostPermissions");
        this.systemServices = Objects.requireNonNull(systemServices, "systemServices");
        this.deviceServices = Objects.requireNonNull(deviceServices, "deviceServices");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.networkServices = Objects.requireNonNull(networkServices, "networkServices");
        this.applicationEnvironment = Objects.requireNonNull(
                applicationEnvironment, "applicationEnvironment");
        this.compatibility = Objects.requireNonNull(compatibility, "compatibility");
        this.policyServices = Objects.requireNonNull(policyServices, "policyServices");
        this.mediaCommunication = Objects.requireNonNull(mediaCommunication, "mediaCommunication");
        this.peripheralServices = Objects.requireNonNull(peripheralServices, "peripheralServices");
        this.privilegedServices = Objects.requireNonNull(privilegedServices, "privilegedServices");
        this.runtimeClient = runtimeClient;
    }

    String maintenanceWarning() {
        List<String> warnings = new ArrayList<>();
        addWarning(warnings, lifecycle.maintenanceWarning());
        addWarning(warnings, systemServices.maintenanceWarning());
        addWarning(warnings, deviceServices.maintenanceWarning());
        addWarning(warnings, interactions.maintenanceWarning());
        addWarning(warnings, networkServices.maintenanceWarning());
        addWarning(warnings, applicationEnvironment.maintenanceWarning());
        addWarning(warnings, compatibility.maintenanceWarning());
        addWarning(warnings, policyServices.maintenanceWarning());
        addWarning(warnings, mediaCommunication.maintenanceWarning());
        addWarning(warnings, peripheralServices.maintenanceWarning());
        addWarning(warnings, privilegedServices.maintenanceWarning());
        return String.join(";", warnings);
    }

    void deleteScopeBestEffort(VirtualSystemServiceStore.Scope scope) {
        systemServices.deleteScopeBestEffort(scope);
        deviceServices.deleteScopeBestEffort(scope);
        interactions.deleteScopeBestEffort(scope);
        networkServices.deleteScopeBestEffort(scope);
        applicationEnvironment.deleteScopeBestEffort(scope);
        compatibility.deleteScopeBestEffort(scope);
        policyServices.deleteScopeBestEffort(scope);
        mediaCommunication.deleteScopeBestEffort(scope);
        peripheralServices.deleteScopeBestEffort(scope);
        privilegedServices.deleteScopeBestEffort(scope);
    }

    void stopGuestBeforeDestructiveOperation(String packageName, int virtualUserId)
            throws Exception {
        if (runtimeClient == null) return;
        SandboxRecord record = lifecycle.findRecord(packageName);
        if (record == null) throw new IllegalArgumentException("Package is not installed");
        runtimeClient.stop(record, virtualUserId);
    }

    /** Package-wide destructive switch: every catalog virtual user, not only user0. */
    void stopAllGuestsForPackage(String packageName) throws Exception {
        SandboxCatalogState current = lifecycle.load();
        java.util.LinkedHashSet<Integer> users = new java.util.LinkedHashSet<>();
        for (SandboxInstance instance : current.instances()) {
            if (packageName.equals(instance.packageName)) users.add(instance.virtualUserId);
        }
        if (users.isEmpty()) users.add(0);
        for (int userId : users) {
            stopGuestBeforeDestructiveOperation(packageName, userId);
            deleteScopeBestEffort(new VirtualSystemServiceStore.Scope(packageName, userId));
        }
    }

    /**
     * Stops every catalog instance before an APK revision becomes authoritative. An upgrade is
     * destructive to the running ClassLoader/native workspace even though the old immutable APK
     * file remains on disk, so the old generation must be dead before the catalog switch.
     */
    void stopGuestBeforeRevisionCommit(SandboxCatalogState current, SandboxRecord imported)
            throws Exception {
        if (runtimeClient == null || current == null || imported == null) return;
        SandboxRecord previous = current.findRecord(imported.packageName);
        if (previous == null || previous.sha256.equals(imported.sha256)) return;
        java.util.LinkedHashSet<Integer> users = new java.util.LinkedHashSet<>();
        for (SandboxInstance instance : current.instances()) {
            if (imported.packageName.equals(instance.packageName)) {
                users.add(instance.virtualUserId);
            }
        }
        // A stale runtime can survive a catalog inconsistency; probe the default instance even
        // when the aggregate no longer contains a row so an upgrade cannot publish over it.
        if (users.isEmpty()) users.add(0);
        for (int userId : users) runtimeClient.stop(previous, userId);
    }

    @Override public void close() {
        capabilityRegistry.close();
        systemServices.close();
        if (runtimeClient != null) runtimeClient.close();
    }

    static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static void addWarning(List<String> warnings, String value) {
        if (value != null && !value.trim().isEmpty()) warnings.add(value.trim());
    }
}
