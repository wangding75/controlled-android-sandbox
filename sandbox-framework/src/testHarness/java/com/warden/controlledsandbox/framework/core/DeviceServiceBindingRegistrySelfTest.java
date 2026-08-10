package com.warden.controlledsandbox.framework.core;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Host regression for the single cross-version device-service binding registry. */
public final class DeviceServiceBindingRegistrySelfTest {
    private DeviceServiceBindingRegistrySelfTest() { }

    public static void main(String[] args) {
        List<DeviceServiceBindingRegistry.Contract> contracts =
                DeviceServiceBindingRegistry.contracts();
        Set<String> names = contracts.stream()
                .map(DeviceServiceBindingRegistry.Contract::logicalCapability)
                .collect(Collectors.toSet());
        require(names.equals(Set.of("location", "settingsIdentity", "telephony", "phoneSubInfo",
                "telephonyRegistry", "subscription", "wifiScanner", "bluetooth", "sensorCatalog")),
                "registry must contain exactly the nine mandatory device capabilities");
        require(DeviceServiceBindingRegistry.contract("settingsIdentity").classification()
                        .equals("CONTENT_PROVIDER"), "settings identity must use provider pathway");
        require(DeviceServiceBindingRegistry.contract("sensorCatalog").classification()
                        .equals("MANAGER_CATALOG"), "sensor catalog must use manager catalog pathway");
        require(DeviceServiceBindingRegistry.contract("telephony").descriptor()
                        .equals("com.android.internal.telephony.ITelephony"),
                "telephony descriptor contract must be explicit");
        require(DeviceServiceBindingRegistry.contract("subscription").serviceNames()
                        .equals(List.of("isub", "telephony_subscription_service")),
                "subscription aliases must be platform aliases, not ROM aliases");
        for (DeviceServiceBindingRegistry.Contract contract : contracts) {
            require(!contract.existingHook().isBlank(), contract.logicalCapability()
                    + " must name the shared existing hook infrastructure");
            require(!contract.managerFieldCandidates().isBlank(), contract.logicalCapability()
                    + " must have a bounded compatibility candidate set");
        }
        System.out.println("PASS device-service binding registry contract self-test");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
