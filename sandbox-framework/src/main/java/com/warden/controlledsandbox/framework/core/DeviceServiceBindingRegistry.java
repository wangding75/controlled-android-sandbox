package com.warden.controlledsandbox.framework.core;

import android.content.Context;
import android.os.IBinder;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The single binding contract for the device-facing framework surface.  The registry owns the
 * logical-to-platform mapping; domain interceptors remain responsible only for virtual data and
 * security semantics.
 */
public final class DeviceServiceBindingRegistry {
    private static final Contract WIFI_CONTRACT = new Contract("wifi", "wifi", List.of("wifi"),
            "android.net.wifi.IWifiManager", "WifiManager",
            "ReflectiveServiceHook + ServiceManager", "mService,mWifiService,sService");
    private static final List<Contract> CONTRACTS = List.of(
            new Contract("location", "location", List.of("location"),
                    "android.location.ILocationManager", "LocationManager",
                    "ReflectiveServiceHook + ServiceManager", "mService,mLocationManagerService"),
            new Contract("settingsIdentity", "content://settings", List.of(),
                    "android.content.IContentProvider", "Settings.Secure/System/Global",
                    "SettingsProviderIdentityHook", "sNameValueCache provider"),
            new Contract("telephony", "phone", List.of("phone"),
                    "com.android.internal.telephony.ITelephony", "TelephonyManager",
                    "ReflectiveServiceHook + ServiceManager", "mITelephony,sITelephony,mTelephonyService"),
            new Contract("phoneSubInfo", "iphonesubinfo", List.of("iphonesubinfo"),
                    "com.android.internal.telephony.IPhoneSubInfo", "TelephonyManager",
                    "ReflectiveServiceHook + ServiceManager", "mSubscriberInfo,mIPhoneSubInfo,sIPhoneSubInfo"),
            new Contract("telephonyRegistry", "telephony.registry", List.of("telephony.registry"),
                    "com.android.internal.telephony.ITelephonyRegistry", "TelephonyRegistry",
                    "ReflectiveServiceHook + ServiceManager", "mTelephonyRegistry,sTelephonyRegistry,mService"),
            new Contract("subscription", "isub", List.of("isub", "telephony_subscription_service"),
                    "com.android.internal.telephony.ISub", "SubscriptionManager",
                    "ReflectiveServiceHook + ServiceManager", "mService,mSubscriptionManagerService,mISub,sISub"),
            new Contract("wifiScanner", "wifiscanner", List.of("wifiscanner"),
                    "android.net.wifi.IWifiScanner", "WifiScanner",
                    "ReflectiveServiceHook + ServiceManager", "mService,mWifiScannerService,sService"),
            new Contract("bluetooth", "bluetooth_manager", List.of("bluetooth_manager", "bluetooth"),
                    "android.bluetooth.IBluetoothManager|android.bluetooth.IBluetooth",
                    "BluetoothManager/BluetoothAdapter",
                    "ReflectiveServiceHook + ServiceManager", "mService,mManagerService,mAdapter.mService"),
            new Contract("sensorCatalog", "sensorservice", List.of("sensorservice"),
                    "android.gui.SensorServer|android.frameworks.sensorservice.ISensorManager/default",
                    "SensorManager",
                    "SensorCatalogHook + SensorService descriptor audit", "mFullSensorsList,mSensorList")
    );

    private DeviceServiceBindingRegistry() { }

    public static List<Contract> contracts() { return CONTRACTS; }

    public static Contract contract(String logicalCapability) {
        if ("wifi".equals(logicalCapability)) return WIFI_CONTRACT;
        for (Contract value : CONTRACTS) {
            if (value.logicalCapability().equals(logicalCapability)) return value;
        }
        throw new IllegalArgumentException("Unknown device service capability: " + logicalCapability);
    }

    public static AutoCloseable install(Context context, GuestIdentity identity,
                                        String logicalCapability) throws Exception {
        Contract contract = contract(logicalCapability);
        if ("settingsIdentity".equals(logicalCapability)) {
            return described(logicalCapability, contract,
                    SettingsProviderIdentityHook.install(context, identity),
                    "provider=content://settings; contract=android.content.IContentProvider");
        }
        if ("sensorCatalog".equals(logicalCapability)) {
            AutoCloseable catalog = SensorCatalogHook.install(context, identity);
            try {
                return described(logicalCapability, contract, catalog,
                        "service=sensorservice; descriptor=" + sensorServiceDescriptor()
                                + "; provider=SensorManager catalog");
            } catch (Throwable error) {
                try { catalog.close(); } catch (Exception rollback) { error.addSuppressed(rollback); }
                com.warden.controlledsandbox.framework.capability.FatalErrorPolicy.rethrowIfFatal(error);
                if (error instanceof Exception exception) throw exception;
                throw new IllegalStateException("SENSOR_SERVICE_DESCRIPTOR_AUDIT_FAILED", error);
            }
        }
        if ("bluetooth".equals(logicalCapability)) {
            return installBluetooth(context, identity, contract);
        }

        AutoCloseable binding;
        try {
            binding = ReflectiveServiceHook.managerFieldCandidatesOrServiceManagerBinding(
                    context, contract.managerServiceName(), logicalCapability,
                    contract.descriptor(), identity, contract.serviceNames(),
                    contract.managerFieldCandidates().split(","));
        } catch (Exception failure) {
            // A radio-less Android image legitimately has no phone/iphonesubinfo/isub Binder.
            // Only a completely absent platform Binder may use the local virtual boundary; any
            // existing host object or descriptor mismatch remains fail-closed in
            // ReflectiveServiceHook.
            if (!isTelephony(logicalCapability)) throw failure;
            try {
                binding = ReflectiveServiceHook.syntheticServiceManagerBindings(
                        contract.serviceNames(), contract.descriptor(), logicalCapability, identity);
                return described(logicalCapability, contract, binding,
                        "service=" + String.join(",", contract.serviceNames())
                                + "(absent); provider=virtual telephony"
                                + "; descriptor=" + contract.descriptor()
                                + "; method=synthetic ServiceManager Binder");
            } catch (Exception syntheticFailure) {
                syntheticFailure.addSuppressed(failure);
                // Retain the null-field fallback for releases which do not expose a usable
                // ServiceManager cache, but never replace a non-null host interface.
                binding = ReflectiveServiceHook.syntheticManagerFieldCandidates(
                        context, contract.managerServiceName(), logicalCapability, identity,
                        contract.managerFieldCandidates().split(","));
                return described(logicalCapability, contract, binding,
                        "service=" + contract.serviceName() + "(absent); provider=virtual telephony"
                                + "; descriptor=" + contract.descriptor()
                                + "; method=synthetic null-IInterface slot");
            }
        }
        return described(logicalCapability, contract, binding,
                "service=" + resolvedServiceName(contract) + "; descriptor=" + resolvedDescriptor(contract)
                        + "; manager=" + contract.manager() + "; method=descriptor-validated proxy");
    }

    private static AutoCloseable installBluetooth(Context context, GuestIdentity identity,
                                                  Contract contract) throws Exception {
        List<AutoCloseable> hooks = new ArrayList<>();
        try {
            AutoCloseable manager = ReflectiveServiceHook.managerFieldCandidatesOrServiceManagerBinding(
                    context, "bluetooth", "bluetooth", "android.bluetooth.IBluetoothManager",
                    identity, contract.serviceNames(), "mService", "mManagerService");
            hooks.add(manager);
            // BluetoothManager and BluetoothAdapter have separate cached Binder interfaces on
            // API32/API35.  Install both when the adapter is already materialized; the manager
            // binding remains sufficient on revisions which lazily create the adapter.
            try {
                AutoCloseable adapter = ReflectiveServiceHook.managerFieldCandidatesOrServiceManagerBinding(
                        context, "bluetooth", "bluetooth", "android.bluetooth.IBluetooth",
                        identity, List.of("bluetooth"), "mAdapter.mService", "mAdapter.mManagerService");
                hooks.add(adapter);
            } catch (Throwable ignored) {
                com.warden.controlledsandbox.framework.capability.FatalErrorPolicy.rethrowIfFatal(ignored);
            }
            return described(logicalName(contract), contract, composite(hooks),
                    "service=" + resolvedServiceName(contract) + "; descriptor=manager/adapter Binder contracts"
                            + "; manager=BluetoothManager/BluetoothAdapter"
                            + "; method=descriptor-validated proxy pair");
        } catch (Throwable error) {
            for (int index = hooks.size() - 1; index >= 0; index--) {
                try { hooks.get(index).close(); } catch (Exception rollback) { error.addSuppressed(rollback); }
            }
            com.warden.controlledsandbox.framework.capability.FatalErrorPolicy.rethrowIfFatal(error);
            if (error instanceof Exception exception) throw exception;
            throw new IllegalStateException("BLUETOOTH_BINDING_FAILED", error);
        }
    }

    private static String logicalName(Contract contract) { return contract.logicalCapability(); }

    private static boolean isTelephony(String logicalCapability) {
        return logicalCapability.equals("telephony") || logicalCapability.equals("phoneSubInfo")
                || logicalCapability.equals("subscription");
    }

    private static AutoCloseable described(String logical, Contract contract, AutoCloseable hook,
                                           String actual) {
        return new DescribedBinding(logical, contract, hook, actual);
    }

    private static AutoCloseable composite(List<AutoCloseable> hooks) {
        return new AutoCloseable() {
            @Override public void close() throws Exception {
                Exception failure = null;
                for (int index = hooks.size() - 1; index >= 0; index--) {
                    try { hooks.get(index).close(); }
                    catch (Exception error) {
                        if (failure == null) failure = error; else failure.addSuppressed(error);
                    }
                }
                if (failure != null) throw failure;
            }
        };
    }

    private static String resolvedServiceName(Contract contract) {
        for (String name : contract.serviceNames()) {
            String descriptor = serviceDescriptor(name);
            if (!descriptor.isEmpty()) return name;
        }
        return contract.serviceName() + "(manager-field)";
    }

    private static String resolvedDescriptor(Contract contract) {
        for (String name : contract.serviceNames()) {
            String descriptor = serviceDescriptor(name);
            if (!descriptor.isEmpty()) return descriptor;
        }
        return contract.descriptor();
    }

    private static String sensorServiceDescriptor() throws Exception {
        String descriptor = serviceDescriptor("sensorservice");
        if (descriptor.isEmpty()) {
            throw new IllegalStateException("SensorService Binder descriptor unavailable");
        }
        if (!descriptor.equals("android.gui.SensorServer")
                && !descriptor.equals("android.frameworks.sensorservice.ISensorManager/default")
                && !descriptor.equals("android.hardware.ISensorServer")) {
            throw new IllegalStateException("Unexpected SensorService descriptor: " + descriptor);
        }
        return descriptor;
    }

    private static String serviceDescriptor(String serviceName) {
        try {
            Class<?> type = Class.forName("android.os.ServiceManager");
            Method getService = type.getDeclaredMethod("getService", String.class);
            getService.setAccessible(true);
            Object value = getService.invoke(null, serviceName);
            if (!(value instanceof IBinder binder)) return "";
            String descriptor = binder.getInterfaceDescriptor();
            return descriptor == null ? "" : descriptor.trim();
        } catch (Throwable error) {
            com.warden.controlledsandbox.framework.capability.FatalErrorPolicy.rethrowIfFatal(error);
            return "";
        }
    }

    public record Contract(String logicalCapability, String serviceName, List<String> serviceNames,
                           String descriptor, String manager, String existingHook,
                           String managerFieldCandidates) {
        public Contract {
            if (logicalCapability == null || logicalCapability.isBlank()) {
                throw new IllegalArgumentException("logicalCapability is required");
            }
            serviceNames = List.copyOf(serviceNames);
        }

        public String classification() {
            if (serviceName.startsWith("content://")) return "CONTENT_PROVIDER";
            if (logicalCapability.equals("sensorCatalog")) return "MANAGER_CATALOG";
            return "BINDER_SERVICE";
        }

        public String managerServiceName() {
            return switch (logicalCapability) {
                case "telephony", "phoneSubInfo" -> "phone";
                case "telephonyRegistry" -> "telephony_registry";
                case "subscription" -> "telephony_subscription_service";
                case "wifiScanner" -> "wifiscanner";
                case "bluetooth" -> "bluetooth";
                case "location" -> "location";
                default -> serviceName;
            };
        }

        @Override public String toString() {
            return logicalCapability + "[" + classification().toLowerCase(Locale.ROOT) + "]";
        }
    }

    public interface Described extends AutoCloseable {
        String description();
    }

    private static final class DescribedBinding implements Described {
        private final String logical;
        private final Contract contract;
        private final AutoCloseable delegate;
        private final String actual;

        private DescribedBinding(String logical, Contract contract, AutoCloseable delegate,
                                 String actual) {
            this.logical = logical;
            this.contract = contract;
            this.delegate = delegate;
            this.actual = actual;
        }

        @Override public String description() {
            return logical + "|classification=" + contract.classification()
                    + "|androidPath=" + contract.serviceName()
                    + "|descriptor=" + contract.descriptor()
                    + "|existingHook=" + contract.existingHook()
                    + "|" + actual;
        }

        @Override public void close() throws Exception { delegate.close(); }
    }
}
