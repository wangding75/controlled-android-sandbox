package com.warden.controlledsandbox;

import android.app.Service;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable dependency graph shared by package-service Binder capabilities. */
final class PackageServiceDependencies implements AutoCloseable {
    final Object operationLock = new Object();
    final SandboxPackageLifecycle lifecycle;
    final PackageCallerVerifier callerVerifier;
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

    static PackageServiceDependencies create(Service service, File filesDir) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(filesDir, "filesDir");
        return new PackageServiceDependencies(
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
                new VirtualPrivilegedServicesStore(filesDir));
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
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.callerVerifier = Objects.requireNonNull(callerVerifier, "callerVerifier");
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

    @Override public void close() {
        systemServices.close();
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
