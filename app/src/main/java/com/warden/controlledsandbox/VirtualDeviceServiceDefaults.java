package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.VirtualBluetoothProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualDeviceIdentitySnapshot;
import com.warden.controlledsandbox.contract.VirtualDeviceServiceProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualSensorProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualSensorSnapshot;
import com.warden.controlledsandbox.contract.VirtualTelephonyProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualTelephonySlotSnapshot;
import com.warden.controlledsandbox.contract.VirtualWifiNetworkSnapshot;
import com.warden.controlledsandbox.contract.VirtualWifiProfileSnapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Deterministic, host-independent defaults for a new package/user device profile. */
final class VirtualDeviceServiceDefaults {
    private VirtualDeviceServiceDefaults() { }

    static VirtualDeviceServiceProfileSnapshot create(String packageName, int virtualUserId,
            long version, long updatedAtMs) {
        byte[] seed = digest(packageName + "#u" + virtualUserId + "#controlled-sandbox-device-v1");
        String hex = hex(seed);
        String androidId = hex.substring(0, 16);
        String serial = "CS" + hex.substring(16, 30).toUpperCase(Locale.ROOT);
        String advertisingId = uuid(seed, 8).toString();
        String installationId = uuid(seed, 0).toString();
        String imei = luhnImei(decimal(seed, 0, 14));
        String subscriberId = "00101" + decimal(seed, 6, 10);
        String simSerial = "890101" + decimal(seed, 10, 13);
        int subscriptionId = 1000 + Math.floorMod(virtualUserId, 8000);
        String wifiMac = mac(seed, 0);
        String wifiBssid = mac(seed, 6);
        String bluetoothAddress = mac(seed, 12);
        String ssid = "ControlledSandbox-u" + virtualUserId;

        VirtualLocationProfileSnapshot location = new VirtualLocationProfileSnapshot(
                VirtualLocationProfileSnapshot.MODE_BLOCKED, "sandbox", false,
                0d, 0d, 0d, 50f, 0f, 0f, 0L, 0L, 1000L,
                false, 0, 0, "");
        VirtualDeviceIdentitySnapshot identity = new VirtualDeviceIdentitySnapshot(
                VirtualLocationProfileSnapshot.MODE_STATIC, androidId, serial, advertisingId,
                true, installationId, "ControlledSandbox", "ControlledSandbox",
                "Virtual Device", "controlled_sandbox", "controlled_sandbox",
                "controlled/sandbox/virtual:1/CS1/1:user/release-keys",
                "virtual", "controlled-sandbox");
        VirtualTelephonySlotSnapshot slot = new VirtualTelephonySlotSnapshot(
                0, subscriptionId, imei, hex.substring(0, 14).toUpperCase(Locale.ROOT),
                subscriberId, simSerial, "", "00101", "00101", "us", "us",
                "Controlled Sandbox", 1, 5, 0, 0, false, false);
        VirtualTelephonyProfileSnapshot telephony = new VirtualTelephonyProfileSnapshot(
                VirtualLocationProfileSnapshot.MODE_STATIC, subscriptionId, subscriptionId,
                true, true, false, List.of(slot));
        VirtualWifiNetworkSnapshot network = new VirtualWifiNetworkSnapshot(
                ssid, wifiBssid, "[WPA2-PSK-CCMP][ESS]", 5180, -48, false);
        VirtualWifiProfileSnapshot wifi = new VirtualWifiProfileSnapshot(
                VirtualLocationProfileSnapshot.MODE_STATIC, true, ssid, wifiBssid, wifiMac,
                ipv4(10, 88, virtualUserId & 0xff, 2), 1, 433, -48, 5180,
                false, false, List.of(network));
        VirtualBluetoothProfileSnapshot bluetooth = new VirtualBluetoothProfileSnapshot(
                VirtualLocationProfileSnapshot.MODE_STATIC, false, 10,
                "ControlledSandbox-u" + virtualUserId, bluetoothAddress, false,
                List.of(), List.of());
        VirtualSensorProfileSnapshot sensors = new VirtualSensorProfileSnapshot(
                VirtualLocationProfileSnapshot.MODE_STATIC, 60, List.of(
                        sensor(1, 1, "Virtual Accelerometer", 39.2266f, 0.001f,
                                new float[]{0f, 0f, 9.80665f}),
                        sensor(2, 2, "Virtual Magnetic Field", 2000f, 0.1f,
                                new float[]{0f, 0f, 0f}),
                        sensor(3, 4, "Virtual Gyroscope", 34.9066f, 0.001f,
                                new float[]{0f, 0f, 0f}),
                        sensor(4, 5, "Virtual Light", 100_000f, 1f,
                                new float[]{100f}),
                        sensor(5, 8, "Virtual Proximity", 10f, 0.1f,
                                new float[]{10f}),
                        sensor(6, 19, "Virtual Step Counter", 1_000_000f, 1f,
                                new float[]{0f})));
        return new VirtualDeviceServiceProfileSnapshot(version, updatedAtMs,
                location, identity, telephony, wifi, bluetooth, sensors);
    }

    private static VirtualSensorSnapshot sensor(int handle, int type, String name,
            float maxRange, float resolution, float[] values) {
        return new VirtualSensorSnapshot(handle, type, name, "ControlledSandbox", 1,
                maxRange, resolution, 0.1f, 10_000, 1_000_000, 0,
                false, false, values, 3);
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return out.toString();
    }
    private static String decimal(byte[] seed, int offset, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            out.append((seed[(offset + index) % seed.length] & 0xff) % 10);
        }
        return out.toString();
    }
    private static String luhnImei(String fourteenDigits) {
        int sum = 0;
        for (int index = 0; index < fourteenDigits.length(); index++) {
            int digit = fourteenDigits.charAt(index) - '0';
            if ((index & 1) == 1) {
                digit *= 2;
                if (digit > 9) digit -= 9;
            }
            sum += digit;
        }
        return fourteenDigits + ((10 - (sum % 10)) % 10);
    }
    private static UUID uuid(byte[] seed, int offset) {
        long high = 0L;
        long low = 0L;
        for (int index = 0; index < 8; index++) high = (high << 8) | (seed[(offset + index) % seed.length] & 0xffL);
        for (int index = 0; index < 8; index++) low = (low << 8) | (seed[(offset + 8 + index) % seed.length] & 0xffL);
        high = (high & 0xffffffffffff0fffL) | 0x0000000000004000L;
        low = (low & 0x3fffffffffffffffL) | 0x8000000000000000L;
        return new UUID(high, low);
    }
    private static String mac(byte[] seed, int offset) {
        int first = ((seed[offset % seed.length] & 0xff) | 0x02) & 0xfe;
        return String.format(Locale.ROOT, "%02X:%02X:%02X:%02X:%02X:%02X", first,
                seed[(offset + 1) % seed.length] & 0xff, seed[(offset + 2) % seed.length] & 0xff,
                seed[(offset + 3) % seed.length] & 0xff, seed[(offset + 4) % seed.length] & 0xff,
                seed[(offset + 5) % seed.length] & 0xff);
    }
    private static int ipv4(int a, int b, int c, int d) {
        return (a & 0xff) | ((b & 0xff) << 8) | ((c & 0xff) << 16) | ((d & 0xff) << 24);
    }
}
