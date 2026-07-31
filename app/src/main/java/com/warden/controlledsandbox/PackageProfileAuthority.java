package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.ApplicationEnvironmentProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualCompatibilityProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualDeviceServiceProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualInteractionProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualMediaCommunicationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualNetworkServiceProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPeripheralServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPolicyServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPrivilegedServicesProfileSnapshot;
import java.util.Objects;
import static com.warden.controlledsandbox.PackageServiceDependencies.required;

/** Authoritative profile mutation boundary used by package-management capabilities. */
final class PackageProfileAuthority {
    private final Object operationLock;
    private final SandboxPackageLifecycle lifecycle;
    private final VirtualSystemServiceStore systemServices;
    private final VirtualDeviceServiceStore deviceServices;
    private final VirtualInteractionStore interactions;
    private final VirtualNetworkServiceStore networkServices;
    private final ApplicationEnvironmentStore applicationEnvironment;
    private final VirtualCompatibilityStore compatibility;
    private final VirtualPolicyServicesStore policyServices;
    private final VirtualMediaCommunicationStore mediaCommunication;
    private final VirtualPeripheralServicesStore peripheralServices;
    private final VirtualPrivilegedServicesStore privilegedServices;

    PackageProfileAuthority(PackageServiceDependencies dependencies) {
        Objects.requireNonNull(dependencies, "dependencies");
        operationLock = dependencies.operationLock;
        lifecycle = dependencies.lifecycle;
        systemServices = dependencies.systemServices;
        deviceServices = dependencies.deviceServices;
        interactions = dependencies.interactions;
        networkServices = dependencies.networkServices;
        applicationEnvironment = dependencies.applicationEnvironment;
        compatibility = dependencies.compatibility;
        policyServices = dependencies.policyServices;
        mediaCommunication = dependencies.mediaCommunication;
        peripheralServices = dependencies.peripheralServices;
        privilegedServices = dependencies.privilegedServices;
    }

    public VirtualDeviceServiceProfileSnapshot getDeviceServiceProfile(
            String packageName, int virtualUserId) {
        synchronized (operationLock) {
            String normalizedPackage = required(packageName, "packageName");
            requirePackageInstance(normalizedPackage, virtualUserId);
            return deviceServices.getOrCreate(
                    new VirtualSystemServiceStore.Scope(normalizedPackage, virtualUserId));
        }
    }

    public VirtualDeviceServiceProfileSnapshot setDeviceServiceProfile(
            String packageName, int virtualUserId,
            VirtualDeviceServiceProfileSnapshot profile) {
        synchronized (operationLock) {
            String normalizedPackage = required(packageName, "packageName");
            requirePackageInstance(normalizedPackage, virtualUserId);
            VirtualSystemServiceStore.Scope scope =
                    new VirtualSystemServiceStore.Scope(normalizedPackage, virtualUserId);
            VirtualDeviceServiceProfileSnapshot updated = deviceServices.update(scope, profile);
            systemServices.notifyDeviceProfileChanged(scope, updated.policyVersion());
            return updated;
        }
    }

    public VirtualDeviceServiceProfileSnapshot resetDeviceServiceProfile(
            String packageName, int virtualUserId) {
        synchronized (operationLock) {
            String normalizedPackage = required(packageName, "packageName");
            requirePackageInstance(normalizedPackage, virtualUserId);
            VirtualSystemServiceStore.Scope scope =
                    new VirtualSystemServiceStore.Scope(normalizedPackage, virtualUserId);
            VirtualDeviceServiceProfileSnapshot reset = deviceServices.reset(scope);
            systemServices.notifyDeviceProfileChanged(scope, reset.policyVersion());
            return reset;
        }
    }

    public VirtualInteractionProfileSnapshot getInteractionProfile(
            String packageName, int virtualUserId) {
        synchronized (operationLock) {
            String normalizedPackage = required(packageName, "packageName");
            requirePackageInstance(normalizedPackage, virtualUserId);
            return interactions.getOrCreate(
                    new VirtualSystemServiceStore.Scope(normalizedPackage, virtualUserId));
        }
    }

    public VirtualInteractionProfileSnapshot setInteractionProfile(
            String packageName, int virtualUserId,
            VirtualInteractionProfileSnapshot profile) {
        synchronized (operationLock) {
            String normalizedPackage = required(packageName, "packageName");
            requirePackageInstance(normalizedPackage, virtualUserId);
            VirtualSystemServiceStore.Scope scope =
                    new VirtualSystemServiceStore.Scope(normalizedPackage, virtualUserId);
            VirtualInteractionProfileSnapshot updated = interactions.update(scope, profile);
            systemServices.notifyInteractionProfileChanged(scope, updated.policyVersion());
            return updated;
        }
    }

    public VirtualInteractionProfileSnapshot resetInteractionProfile(
            String packageName, int virtualUserId) {
        synchronized (operationLock) {
            String normalizedPackage = required(packageName, "packageName");
            requirePackageInstance(normalizedPackage, virtualUserId);
            VirtualSystemServiceStore.Scope scope =
                    new VirtualSystemServiceStore.Scope(normalizedPackage, virtualUserId);
            VirtualInteractionProfileSnapshot reset = interactions.reset(scope);
            systemServices.notifyInteractionProfileChanged(scope, reset.policyVersion());
            return reset;
        }
    }

    public VirtualNetworkServiceProfileSnapshot getNetworkServiceProfile(
            String packageName, int virtualUserId) {
        synchronized (operationLock) {
            String normalizedPackage = required(packageName, "packageName");
            requirePackageInstance(normalizedPackage, virtualUserId);
            return networkServices.getOrCreate(
                    new VirtualSystemServiceStore.Scope(normalizedPackage, virtualUserId));
        }
    }

    public VirtualNetworkServiceProfileSnapshot setNetworkServiceProfile(
            String packageName, int virtualUserId,
            VirtualNetworkServiceProfileSnapshot profile) {
        synchronized (operationLock) {
            String normalizedPackage = required(packageName, "packageName");
            requirePackageInstance(normalizedPackage, virtualUserId);
            VirtualSystemServiceStore.Scope scope =
                    new VirtualSystemServiceStore.Scope(normalizedPackage, virtualUserId);
            VirtualNetworkServiceProfileSnapshot updated = networkServices.update(scope, profile);
            systemServices.notifyNetworkProfileChanged(scope, updated.policyVersion());
            return updated;
        }
    }

    public VirtualNetworkServiceProfileSnapshot resetNetworkServiceProfile(
            String packageName, int virtualUserId) {
        synchronized (operationLock) {
            String normalizedPackage = required(packageName, "packageName");
            requirePackageInstance(normalizedPackage, virtualUserId);
            VirtualSystemServiceStore.Scope scope =
                    new VirtualSystemServiceStore.Scope(normalizedPackage, virtualUserId);
            VirtualNetworkServiceProfileSnapshot reset = networkServices.reset(scope);
            systemServices.notifyNetworkProfileChanged(scope, reset.policyVersion());
            return reset;
        }
    }

    public ApplicationEnvironmentProfileSnapshot getApplicationEnvironmentProfile(
            String packageName, int virtualUserId) {
        synchronized (operationLock) {
            String normalizedPackage = required(packageName, "packageName");
            requirePackageInstance(normalizedPackage, virtualUserId);
            return applicationEnvironment.getOrCreate(
                    new VirtualSystemServiceStore.Scope(normalizedPackage, virtualUserId));
        }
    }

    public ApplicationEnvironmentProfileSnapshot setApplicationEnvironmentProfile(
            String packageName, int virtualUserId,
            ApplicationEnvironmentProfileSnapshot profile) {
        synchronized (operationLock) {
            String normalizedPackage = required(packageName, "packageName");
            requirePackageInstance(normalizedPackage, virtualUserId);
            VirtualSystemServiceStore.Scope scope =
                    new VirtualSystemServiceStore.Scope(normalizedPackage, virtualUserId);
            ApplicationEnvironmentProfileSnapshot updated =
                    applicationEnvironment.update(scope, profile);
            systemServices.notifyApplicationEnvironmentProfileChanged(scope, updated.policyVersion());
            return updated;
        }
    }

    public ApplicationEnvironmentProfileSnapshot resetApplicationEnvironmentProfile(
            String packageName, int virtualUserId) {
        synchronized (operationLock) {
            String normalizedPackage = required(packageName, "packageName");
            requirePackageInstance(normalizedPackage, virtualUserId);
            VirtualSystemServiceStore.Scope scope =
                    new VirtualSystemServiceStore.Scope(normalizedPackage, virtualUserId);
            ApplicationEnvironmentProfileSnapshot reset = applicationEnvironment.reset(scope);
            systemServices.notifyApplicationEnvironmentProfileChanged(scope, reset.policyVersion());
            return reset;
        }
    }

    public VirtualCompatibilityProfileSnapshot getCompatibilityProfile(
            String packageName, int virtualUserId) {
        synchronized (operationLock) {
            String normalizedPackage=required(packageName,"packageName"); requirePackageInstance(normalizedPackage,virtualUserId);
            return compatibility.getOrCreate(new VirtualSystemServiceStore.Scope(normalizedPackage,virtualUserId));
        }
    }

    public VirtualCompatibilityProfileSnapshot setCompatibilityProfile(
            String packageName, int virtualUserId, VirtualCompatibilityProfileSnapshot profile) {
        synchronized (operationLock) {
            String normalizedPackage=required(packageName,"packageName"); requirePackageInstance(normalizedPackage,virtualUserId);
            VirtualSystemServiceStore.Scope scope=new VirtualSystemServiceStore.Scope(normalizedPackage,virtualUserId);
            VirtualCompatibilityProfileSnapshot updated=compatibility.update(scope,profile);
            systemServices.notifyCompatibilityProfileChanged(scope,updated.policyVersion()); return updated;
        }
    }

    public VirtualCompatibilityProfileSnapshot resetCompatibilityProfile(
            String packageName, int virtualUserId) {
        synchronized (operationLock) {
            String normalizedPackage=required(packageName,"packageName"); requirePackageInstance(normalizedPackage,virtualUserId);
            VirtualSystemServiceStore.Scope scope=new VirtualSystemServiceStore.Scope(normalizedPackage,virtualUserId);
            VirtualCompatibilityProfileSnapshot reset=compatibility.reset(scope);
            systemServices.notifyCompatibilityProfileChanged(scope,reset.policyVersion()); return reset;
        }
    }

    public VirtualPolicyServicesProfileSnapshot getPolicyServicesProfile(
            String packageName, int virtualUserId) {
        synchronized (operationLock) {
            String normalizedPackage=required(packageName,"packageName"); requirePackageInstance(normalizedPackage,virtualUserId);
            return policyServices.getOrCreate(new VirtualSystemServiceStore.Scope(normalizedPackage,virtualUserId));
        }
    }

    public VirtualPolicyServicesProfileSnapshot setPolicyServicesProfile(
            String packageName, int virtualUserId, VirtualPolicyServicesProfileSnapshot profile) {
        synchronized (operationLock) {
            String normalizedPackage=required(packageName,"packageName"); requirePackageInstance(normalizedPackage,virtualUserId);
            VirtualSystemServiceStore.Scope scope=new VirtualSystemServiceStore.Scope(normalizedPackage,virtualUserId);
            VirtualPolicyServicesProfileSnapshot updated=policyServices.update(scope,profile);
            systemServices.notifyPolicyServicesProfileChanged(scope,updated.policyVersion()); return updated;
        }
    }

    public VirtualPolicyServicesProfileSnapshot resetPolicyServicesProfile(
            String packageName, int virtualUserId) {
        synchronized (operationLock) {
            String normalizedPackage=required(packageName,"packageName"); requirePackageInstance(normalizedPackage,virtualUserId);
            VirtualSystemServiceStore.Scope scope=new VirtualSystemServiceStore.Scope(normalizedPackage,virtualUserId);
            VirtualPolicyServicesProfileSnapshot reset=policyServices.reset(scope);
            systemServices.notifyPolicyServicesProfileChanged(scope,reset.policyVersion()); return reset;
        }
    }

    public VirtualMediaCommunicationProfileSnapshot getMediaCommunicationProfile(
            String packageName, int virtualUserId) {
        synchronized (operationLock) {
            String normalizedPackage = required(packageName, "packageName");
            requirePackageInstance(normalizedPackage, virtualUserId);
            return mediaCommunication.getOrCreate(
                    new VirtualSystemServiceStore.Scope(normalizedPackage, virtualUserId));
        }
    }

    public VirtualMediaCommunicationProfileSnapshot setMediaCommunicationProfile(
            String packageName,
            int virtualUserId,
            VirtualMediaCommunicationProfileSnapshot profile) {
        synchronized (operationLock) {
            String normalizedPackage = required(packageName, "packageName");
            requirePackageInstance(normalizedPackage, virtualUserId);
            VirtualSystemServiceStore.Scope scope = new VirtualSystemServiceStore.Scope(
                    normalizedPackage, virtualUserId);
            VirtualMediaCommunicationProfileSnapshot updated =
                    mediaCommunication.update(scope, profile);
            systemServices.notifyMediaCommunicationProfileChanged(
                    scope, updated.policyVersion());
            return updated;
        }
    }

    public VirtualMediaCommunicationProfileSnapshot resetMediaCommunicationProfile(
            String packageName, int virtualUserId) {
        synchronized (operationLock) {
            String normalizedPackage = required(packageName, "packageName");
            requirePackageInstance(normalizedPackage, virtualUserId);
            VirtualSystemServiceStore.Scope scope = new VirtualSystemServiceStore.Scope(
                    normalizedPackage, virtualUserId);
            VirtualMediaCommunicationProfileSnapshot reset = mediaCommunication.reset(scope);
            systemServices.notifyMediaCommunicationProfileChanged(
                    scope, reset.policyVersion());
            return reset;
        }
    }

    public VirtualPeripheralServicesProfileSnapshot getPeripheralServicesProfile(
            String packageName, int virtualUserId) {
        synchronized (operationLock) {
            String normalizedPackage = required(packageName, "packageName");
            requirePackageInstance(normalizedPackage, virtualUserId);
            return peripheralServices.getOrCreate(
                    new VirtualSystemServiceStore.Scope(normalizedPackage, virtualUserId));
        }
    }

    public VirtualPeripheralServicesProfileSnapshot setPeripheralServicesProfile(
            String packageName, int virtualUserId,
            VirtualPeripheralServicesProfileSnapshot profile) {
        synchronized (operationLock) {
            String normalizedPackage = required(packageName, "packageName");
            requirePackageInstance(normalizedPackage, virtualUserId);
            VirtualSystemServiceStore.Scope scope = new VirtualSystemServiceStore.Scope(
                    normalizedPackage, virtualUserId);
            VirtualPeripheralServicesProfileSnapshot updated =
                    peripheralServices.update(scope, profile);
            systemServices.notifyPeripheralServicesProfileChanged(
                    scope, updated.policyVersion());
            return updated;
        }
    }

    public VirtualPeripheralServicesProfileSnapshot resetPeripheralServicesProfile(
            String packageName, int virtualUserId) {
        synchronized (operationLock) {
            String normalizedPackage = required(packageName, "packageName");
            requirePackageInstance(normalizedPackage, virtualUserId);
            VirtualSystemServiceStore.Scope scope = new VirtualSystemServiceStore.Scope(
                    normalizedPackage, virtualUserId);
            VirtualPeripheralServicesProfileSnapshot reset = peripheralServices.reset(scope);
            systemServices.notifyPeripheralServicesProfileChanged(
                    scope, reset.policyVersion());
            return reset;
        }
    }

    public VirtualPrivilegedServicesProfileSnapshot getPrivilegedServicesProfile(
            String packageName, int virtualUserId) {
        synchronized (operationLock) {
            String normalizedPackage = required(packageName, "packageName");
            requirePackageInstance(normalizedPackage, virtualUserId);
            return privilegedServices.getOrCreate(
                    new VirtualSystemServiceStore.Scope(normalizedPackage, virtualUserId));
        }
    }

    public VirtualPrivilegedServicesProfileSnapshot setPrivilegedServicesProfile(
            String packageName, int virtualUserId,
            VirtualPrivilegedServicesProfileSnapshot profile) {
        synchronized (operationLock) {
            String normalizedPackage = required(packageName, "packageName");
            requirePackageInstance(normalizedPackage, virtualUserId);
            VirtualSystemServiceStore.Scope scope = new VirtualSystemServiceStore.Scope(
                    normalizedPackage, virtualUserId);
            VirtualPrivilegedServicesProfileSnapshot updated =
                    privilegedServices.update(scope, profile);
            systemServices.notifyPrivilegedServicesProfileChanged(scope, updated.policyVersion());
            return updated;
        }
    }

    public VirtualPrivilegedServicesProfileSnapshot resetPrivilegedServicesProfile(
            String packageName, int virtualUserId) {
        synchronized (operationLock) {
            String normalizedPackage = required(packageName, "packageName");
            requirePackageInstance(normalizedPackage, virtualUserId);
            VirtualSystemServiceStore.Scope scope = new VirtualSystemServiceStore.Scope(
                    normalizedPackage, virtualUserId);
            VirtualPrivilegedServicesProfileSnapshot reset = privilegedServices.reset(scope);
            systemServices.notifyPrivilegedServicesProfileChanged(scope, reset.policyVersion());
            return reset;
        }
    }

    private void requirePackageInstance(String packageName, int virtualUserId) {
        try {
            lifecycle.packagePolicy(packageName, virtualUserId);
        } catch (Exception error) {
            throw new IllegalStateException("VIRTUAL_PACKAGE_INSTANCE_REQUIRED", error);
        }
    }
}
