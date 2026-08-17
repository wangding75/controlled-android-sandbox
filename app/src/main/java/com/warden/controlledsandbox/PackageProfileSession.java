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

/** Owns profile API dispatch while preserving the management-session authority boundary. */
final class PackageProfileSession {
    private final PackageProfileAuthority profiles;
    private final PackageManagementAuthorityGuard guard;

    PackageProfileSession(PackageProfileAuthority profiles,
                          PackageManagementAuthorityGuard guard) {
        this.profiles = java.util.Objects.requireNonNull(profiles, "profiles");
        this.guard = java.util.Objects.requireNonNull(guard, "guard");
    }

    VirtualDeviceServiceProfileSnapshot getDeviceServiceProfile(String packageName, int userId) {
        guard.requireOwner();
        return profiles.getDeviceServiceProfile(packageName, userId);
    }
    VirtualDeviceServiceProfileSnapshot setDeviceServiceProfile(
            String packageName, int userId, VirtualDeviceServiceProfileSnapshot profile) {
        guard.requireOwner();
        return profiles.setDeviceServiceProfile(packageName, userId, profile);
    }
    VirtualDeviceServiceProfileSnapshot resetDeviceServiceProfile(String packageName, int userId) {
        guard.requireOwner();
        return profiles.resetDeviceServiceProfile(packageName, userId);
    }

    VirtualInteractionProfileSnapshot getInteractionProfile(String packageName, int userId) {
        guard.requireOwner();
        return profiles.getInteractionProfile(packageName, userId);
    }
    VirtualInteractionProfileSnapshot setInteractionProfile(
            String packageName, int userId, VirtualInteractionProfileSnapshot profile) {
        guard.requireOwner();
        return profiles.setInteractionProfile(packageName, userId, profile);
    }
    VirtualInteractionProfileSnapshot resetInteractionProfile(String packageName, int userId) {
        guard.requireOwner();
        return profiles.resetInteractionProfile(packageName, userId);
    }

    VirtualNetworkServiceProfileSnapshot getNetworkServiceProfile(String packageName, int userId) {
        guard.requireOwner();
        return profiles.getNetworkServiceProfile(packageName, userId);
    }
    VirtualNetworkServiceProfileSnapshot setNetworkServiceProfile(
            String packageName, int userId, VirtualNetworkServiceProfileSnapshot profile) {
        guard.requireOwner();
        return profiles.setNetworkServiceProfile(packageName, userId, profile);
    }
    VirtualNetworkServiceProfileSnapshot resetNetworkServiceProfile(String packageName, int userId) {
        guard.requireOwner();
        return profiles.resetNetworkServiceProfile(packageName, userId);
    }

    ApplicationEnvironmentProfileSnapshot getApplicationEnvironmentProfile(
            String packageName, int userId) {
        guard.requireOwner();
        return profiles.getApplicationEnvironmentProfile(packageName, userId);
    }
    ApplicationEnvironmentProfileSnapshot setApplicationEnvironmentProfile(
            String packageName, int userId, ApplicationEnvironmentProfileSnapshot profile) {
        guard.requireOwner();
        return profiles.setApplicationEnvironmentProfile(packageName, userId, profile);
    }
    ApplicationEnvironmentProfileSnapshot resetApplicationEnvironmentProfile(
            String packageName, int userId) {
        guard.requireOwner();
        return profiles.resetApplicationEnvironmentProfile(packageName, userId);
    }

    VirtualCompatibilityProfileSnapshot getCompatibilityProfile(String packageName, int userId) {
        guard.requireOwner();
        return profiles.getCompatibilityProfile(packageName, userId);
    }
    VirtualCompatibilityProfileSnapshot setCompatibilityProfile(
            String packageName, int userId, VirtualCompatibilityProfileSnapshot profile) {
        guard.requireOwner();
        return profiles.setCompatibilityProfile(packageName, userId, profile);
    }
    VirtualCompatibilityProfileSnapshot resetCompatibilityProfile(String packageName, int userId) {
        guard.requireOwner();
        return profiles.resetCompatibilityProfile(packageName, userId);
    }

    VirtualPolicyServicesProfileSnapshot getPolicyServicesProfile(String packageName, int userId) {
        guard.requireOwner();
        return profiles.getPolicyServicesProfile(packageName, userId);
    }
    VirtualPolicyServicesProfileSnapshot setPolicyServicesProfile(
            String packageName, int userId, VirtualPolicyServicesProfileSnapshot profile) {
        guard.requireOwner();
        return profiles.setPolicyServicesProfile(packageName, userId, profile);
    }
    VirtualPolicyServicesProfileSnapshot resetPolicyServicesProfile(String packageName, int userId) {
        guard.requireOwner();
        return profiles.resetPolicyServicesProfile(packageName, userId);
    }

    VirtualMediaCommunicationProfileSnapshot getMediaCommunicationProfile(
            String packageName, int userId) {
        guard.requireOwner();
        return profiles.getMediaCommunicationProfile(packageName, userId);
    }
    VirtualMediaCommunicationProfileSnapshot setMediaCommunicationProfile(
            String packageName, int userId, VirtualMediaCommunicationProfileSnapshot profile) {
        guard.requireOwner();
        return profiles.setMediaCommunicationProfile(packageName, userId, profile);
    }
    VirtualMediaCommunicationProfileSnapshot resetMediaCommunicationProfile(
            String packageName, int userId) {
        guard.requireOwner();
        return profiles.resetMediaCommunicationProfile(packageName, userId);
    }

    VirtualPeripheralServicesProfileSnapshot getPeripheralServicesProfile(
            String packageName, int userId) {
        guard.requireOwner();
        return profiles.getPeripheralServicesProfile(packageName, userId);
    }
    VirtualPeripheralServicesProfileSnapshot setPeripheralServicesProfile(
            String packageName, int userId, VirtualPeripheralServicesProfileSnapshot profile) {
        guard.requireOwner();
        return profiles.setPeripheralServicesProfile(packageName, userId, profile);
    }
    VirtualPeripheralServicesProfileSnapshot resetPeripheralServicesProfile(
            String packageName, int userId) {
        guard.requireOwner();
        return profiles.resetPeripheralServicesProfile(packageName, userId);
    }

    VirtualPrivilegedServicesProfileSnapshot getPrivilegedServicesProfile(
            String packageName, int userId) {
        guard.requireOwner();
        return profiles.getPrivilegedServicesProfile(packageName, userId);
    }
    VirtualPrivilegedServicesProfileSnapshot setPrivilegedServicesProfile(
            String packageName, int userId, VirtualPrivilegedServicesProfileSnapshot profile) {
        guard.requireOwner();
        return profiles.setPrivilegedServicesProfile(packageName, userId, profile);
    }
    VirtualPrivilegedServicesProfileSnapshot resetPrivilegedServicesProfile(
            String packageName, int userId) {
        guard.requireOwner();
        return profiles.resetPrivilegedServicesProfile(packageName, userId);
    }
}
