package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.VirtualBluetoothDeviceSnapshot;
import com.warden.controlledsandbox.contract.VirtualBluetoothProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualCellInfoSnapshot;
import com.warden.controlledsandbox.contract.VirtualDeviceIdentitySnapshot;
import com.warden.controlledsandbox.contract.VirtualDeviceServiceProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationPointSnapshot;
import com.warden.controlledsandbox.contract.VirtualSensorProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualSensorSnapshot;
import com.warden.controlledsandbox.contract.VirtualTelephonyProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualTelephonySlotSnapshot;
import com.warden.controlledsandbox.contract.VirtualWifiNetworkSnapshot;
import com.warden.controlledsandbox.contract.VirtualWifiProfileSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** JSON schema for virtual device-service profiles. */
final class VirtualDeviceServiceStoreCodec {
    static final int SCHEMA = 1;
    static final int MAX_SCOPES = 512;

    private VirtualDeviceServiceStoreCodec() { }

    static String encode(Map<VirtualSystemServiceStore.Scope, VirtualDeviceServiceProfileSnapshot> profiles) {
        try {
            JSONArray scopes = new JSONArray();
            for (Map.Entry<VirtualSystemServiceStore.Scope, VirtualDeviceServiceProfileSnapshot> entry
                    : profiles.entrySet()) {
                VirtualSystemServiceStore.Scope scope = entry.getKey();
                scopes.put(new JSONObject().put("packageName", scope.packageName())
                        .put("virtualUserId", scope.virtualUserId())
                        .put("profile", profile(entry.getValue())));
            }
            return new JSONObject().put("schemaVersion", SCHEMA).put("scopes", scopes).toString();
        } catch (Exception error) {
            throw new IllegalStateException("Cannot encode virtual device-service profiles", error);
        }
    }

    static Map<VirtualSystemServiceStore.Scope, VirtualDeviceServiceProfileSnapshot> decode(String payload) {
        try {
            JSONObject root = new JSONObject(payload);
            if (root.optInt("schemaVersion", -1) != SCHEMA) {
                throw new IllegalStateException("Unsupported virtual device-profile schema");
            }
            JSONArray scopes = root.optJSONArray("scopes");
            Map<VirtualSystemServiceStore.Scope, VirtualDeviceServiceProfileSnapshot> result = new LinkedHashMap<>();
            if (scopes == null) return result;
            if (scopes.length() > MAX_SCOPES) throw new IllegalStateException("Device-profile scope limit exceeded");
            for (int index = 0; index < scopes.length(); index++) {
                JSONObject item = scopes.getJSONObject(index);
                VirtualSystemServiceStore.Scope scope = new VirtualSystemServiceStore.Scope(
                        item.getString("packageName"), item.getInt("virtualUserId"));
                if (result.putIfAbsent(scope, profile(item.getJSONObject("profile"))) != null) {
                    throw new IllegalStateException("Duplicate virtual device-profile scope");
                }
            }
            return result;
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("Cannot decode virtual device-service profiles", error);
        }
    }

    private static JSONObject profile(VirtualDeviceServiceProfileSnapshot value) throws Exception {
        return new JSONObject().put("policyVersion", value.policyVersion())
                .put("updatedAtMs", value.updatedAtMs())
                .put("location", location(value.location()))
                .put("identity", identity(value.identity()))
                .put("telephony", telephony(value.telephony()))
                .put("wifi", wifi(value.wifi()))
                .put("bluetooth", bluetooth(value.bluetooth()))
                .put("sensors", sensors(value.sensors()));
    }

    private static VirtualDeviceServiceProfileSnapshot profile(JSONObject value) throws Exception {
        return new VirtualDeviceServiceProfileSnapshot(value.getLong("policyVersion"),
                value.optLong("updatedAtMs", 0L), location(value.getJSONObject("location")),
                identity(value.getJSONObject("identity")), telephony(value.getJSONObject("telephony")),
                wifi(value.getJSONObject("wifi")), bluetooth(value.getJSONObject("bluetooth")),
                sensors(value.getJSONObject("sensors")));
    }

    private static JSONObject location(VirtualLocationProfileSnapshot value) throws Exception {
        return new JSONObject().put("mode", value.mode()).put("provider", value.provider())
                .put("providerEnabled", value.providerEnabled()).put("latitude", value.latitude())
                .put("longitude", value.longitude()).put("altitudeMeters", value.altitudeMeters())
                .put("accuracyMeters", value.accuracyMeters())
                .put("speedMetersPerSecond", value.speedMetersPerSecond())
                .put("bearingDegrees", value.bearingDegrees()).put("timeMs", value.timeMs())
                .put("elapsedRealtimeNanos", value.elapsedRealtimeNanos())
                .put("minimumUpdateIntervalMs", value.minimumUpdateIntervalMs())
                .put("gnssEnabled", value.gnssEnabled())
                .put("satellitesInView", value.satellitesInView())
                .put("satellitesUsedInFix", value.satellitesUsedInFix())
                .put("nmeaSentence", value.nmeaSentence())
                .put("trajectoryMode", value.trajectoryMode())
                .put("trajectoryIntervalMs", value.trajectoryIntervalMs())
                .put("trajectoryPoints", trajectoryPoints(value.trajectoryPoints()))
                .put("timestampPolicy", value.timestampPolicy())
                .put("elapsedRealtimePolicy", value.elapsedRealtimePolicy());
    }
    private static VirtualLocationProfileSnapshot location(JSONObject value) throws Exception {
        JSONArray points = value.optJSONArray("trajectoryPoints");
        List<VirtualLocationPointSnapshot> trajectory = new ArrayList<>();
        if (points != null) {
            if (points.length() > 512) throw new IllegalStateException("Trajectory point limit exceeded");
            for (int index = 0; index < points.length(); index++) {
                JSONObject point = points.getJSONObject(index);
                trajectory.add(new VirtualLocationPointSnapshot(point.optLong("offsetMs", 0L),
                        point.optDouble("latitude", 0d), point.optDouble("longitude", 0d),
                        point.optDouble("altitudeMeters", 0d),
                        (float) point.optDouble("accuracyMeters", 0d),
                        (float) point.optDouble("speedMetersPerSecond", 0d),
                        (float) point.optDouble("bearingDegrees", 0d)));
            }
        }
        return new VirtualLocationProfileSnapshot(value.getString("mode"), value.optString("provider", ""),
                value.optBoolean("providerEnabled", false), value.optDouble("latitude", 0d),
                value.optDouble("longitude", 0d), value.optDouble("altitudeMeters", 0d),
                (float) value.optDouble("accuracyMeters", 0d),
                (float) value.optDouble("speedMetersPerSecond", 0d),
                (float) value.optDouble("bearingDegrees", 0d), value.optLong("timeMs", 0L),
                value.optLong("elapsedRealtimeNanos", 0L), value.optLong("minimumUpdateIntervalMs", 1000L),
                value.optBoolean("gnssEnabled", false), value.optInt("satellitesInView", 0),
                value.optInt("satellitesUsedInFix", 0), value.optString("nmeaSentence", ""),
                value.optString("trajectoryMode", VirtualLocationProfileSnapshot.TRAJECTORY_FIXED),
                value.optLong("trajectoryIntervalMs", value.optLong("minimumUpdateIntervalMs", 1000L)),
                trajectory, value.optString("timestampPolicy", VirtualLocationProfileSnapshot.TIME_POLICY_NOW),
                value.optString("elapsedRealtimePolicy", VirtualLocationProfileSnapshot.ELAPSED_POLICY_NOW));
    }

    private static JSONArray trajectoryPoints(List<VirtualLocationPointSnapshot> values)
            throws Exception {
        JSONArray out = new JSONArray();
        for (VirtualLocationPointSnapshot point : values) out.put(new JSONObject()
                .put("offsetMs", point.offsetMs()).put("latitude", point.latitude())
                .put("longitude", point.longitude()).put("altitudeMeters", point.altitudeMeters())
                .put("accuracyMeters", point.accuracyMeters())
                .put("speedMetersPerSecond", point.speedMetersPerSecond())
                .put("bearingDegrees", point.bearingDegrees()));
        return out;
    }

    private static JSONObject identity(VirtualDeviceIdentitySnapshot value) throws Exception {
        return new JSONObject().put("mode", value.mode()).put("androidId", value.androidId())
                .put("serial", value.serial()).put("advertisingId", value.advertisingId())
                .put("limitAdTracking", value.limitAdTracking()).put("installationId", value.installationId())
                .put("manufacturer", value.manufacturer()).put("brand", value.brand())
                .put("model", value.model()).put("device", value.device()).put("product", value.product())
                .put("fingerprint", value.fingerprint()).put("board", value.board())
                .put("hardware", value.hardware());
    }
    private static VirtualDeviceIdentitySnapshot identity(JSONObject value) throws Exception {
        return new VirtualDeviceIdentitySnapshot(value.getString("mode"), value.optString("androidId", ""),
                value.optString("serial", ""), value.optString("advertisingId", ""),
                value.optBoolean("limitAdTracking", true), value.optString("installationId", ""),
                value.optString("manufacturer", ""), value.optString("brand", ""),
                value.optString("model", ""), value.optString("device", ""),
                value.optString("product", ""), value.optString("fingerprint", ""),
                value.optString("board", ""), value.optString("hardware", ""));
    }

    private static JSONObject telephony(VirtualTelephonyProfileSnapshot value) throws Exception {
        JSONArray slots = new JSONArray();
        for (VirtualTelephonySlotSnapshot slot : value.slots()) slots.put(new JSONObject()
                .put("slotIndex", slot.slotIndex()).put("subscriptionId", slot.subscriptionId())
                .put("imei", slot.imei()).put("meid", slot.meid()).put("subscriberId", slot.subscriberId())
                .put("simSerialNumber", slot.simSerialNumber()).put("line1Number", slot.line1Number())
                .put("simOperator", slot.simOperator()).put("networkOperator", slot.networkOperator())
                .put("simCountryIso", slot.simCountryIso()).put("networkCountryIso", slot.networkCountryIso())
                .put("carrierName", slot.carrierName()).put("phoneType", slot.phoneType())
                .put("simState", slot.simState()).put("dataNetworkType", slot.dataNetworkType())
                .put("voiceNetworkType", slot.voiceNetworkType()).put("dataEnabled", slot.dataEnabled())
                .put("roaming", slot.roaming()));
        JSONArray cells = new JSONArray();
        for (VirtualCellInfoSnapshot cell : value.cells()) cells.put(new JSONObject()
                .put("technology", cell.technology()).put("mcc", cell.mcc()).put("mnc", cell.mnc())
                .put("lac", cell.lac()).put("tac", cell.tac()).put("cid", cell.cid())
                .put("pci", cell.pci()).put("arfcn", cell.arfcn())
                .put("registered", cell.registered()).put("signalLevel", cell.signalLevel()));
        return new JSONObject().put("mode", value.mode())
                .put("defaultSubscriptionId", value.defaultSubscriptionId())
                .put("activeDataSubscriptionId", value.activeDataSubscriptionId())
                .put("voiceCapable", value.voiceCapable()).put("smsCapable", value.smsCapable())
                .put("emergencyOnly", value.emergencyOnly()).put("slots", slots)
                .put("cells", cells);
    }
    private static VirtualTelephonyProfileSnapshot telephony(JSONObject value) throws Exception {
        JSONArray array = value.optJSONArray("slots");
        List<VirtualTelephonySlotSnapshot> slots = new ArrayList<>();
        if (array != null) {
            if (array.length() > 4) throw new IllegalStateException("Telephony slot limit exceeded");
            for (int index = 0; index < array.length(); index++) {
                JSONObject slot = array.getJSONObject(index);
                slots.add(new VirtualTelephonySlotSnapshot(slot.getInt("slotIndex"),
                        slot.optInt("subscriptionId", -1), slot.optString("imei", ""),
                        slot.optString("meid", ""), slot.optString("subscriberId", ""),
                        slot.optString("simSerialNumber", ""), slot.optString("line1Number", ""),
                        slot.optString("simOperator", ""), slot.optString("networkOperator", ""),
                        slot.optString("simCountryIso", ""), slot.optString("networkCountryIso", ""),
                        slot.optString("carrierName", ""), slot.optInt("phoneType", 0),
                        slot.optInt("simState", 0), slot.optInt("dataNetworkType", 0),
                        slot.optInt("voiceNetworkType", 0), slot.optBoolean("dataEnabled", false),
                        slot.optBoolean("roaming", false)));
            }
        }
        JSONArray cellArray = value.optJSONArray("cells");
        List<VirtualCellInfoSnapshot> cells = new ArrayList<>();
        if (cellArray != null) {
            if (cellArray.length() > 16) throw new IllegalStateException("Telephony cell limit exceeded");
            for (int index = 0; index < cellArray.length(); index++) {
                JSONObject cell = cellArray.getJSONObject(index);
                cells.add(new VirtualCellInfoSnapshot(cell.optString("technology", "LTE"),
                        cell.optInt("mcc", 0), cell.optInt("mnc", 0), cell.optInt("lac", 0),
                        cell.optInt("tac", 0), cell.optLong("cid", 0L), cell.optInt("pci", 0),
                        cell.optInt("arfcn", 0), cell.optBoolean("registered", false),
                        cell.optInt("signalLevel", -127)));
            }
        }
        return new VirtualTelephonyProfileSnapshot(value.getString("mode"),
                value.optInt("defaultSubscriptionId", -1), value.optInt("activeDataSubscriptionId", -1),
                value.optBoolean("voiceCapable", false), value.optBoolean("smsCapable", false),
                value.optBoolean("emergencyOnly", false), slots, cells);
    }

    private static JSONObject wifi(VirtualWifiProfileSnapshot value) throws Exception {
        JSONArray scans = new JSONArray();
        for (VirtualWifiNetworkSnapshot network : value.scanResults()) scans.put(new JSONObject()
                .put("ssid", network.ssid()).put("bssid", network.bssid())
                .put("capabilities", network.capabilities()).put("frequencyMhz", network.frequencyMhz())
                .put("rssi", network.rssi()).put("hidden", network.hidden()));
        return new JSONObject().put("mode", value.mode()).put("enabled", value.enabled())
                .put("ssid", value.ssid()).put("bssid", value.bssid()).put("macAddress", value.macAddress())
                .put("ipv4Address", value.ipv4Address()).put("networkId", value.networkId())
                .put("linkSpeedMbps", value.linkSpeedMbps()).put("rssi", value.rssi())
                .put("frequencyMhz", value.frequencyMhz()).put("metered", value.metered())
                .put("hiddenSsid", value.hiddenSsid()).put("scanResults", scans);
    }
    private static VirtualWifiProfileSnapshot wifi(JSONObject value) throws Exception {
        JSONArray array = value.optJSONArray("scanResults");
        List<VirtualWifiNetworkSnapshot> scans = new ArrayList<>();
        if (array != null) {
            if (array.length() > 128) throw new IllegalStateException("Wi-Fi scan result limit exceeded");
            for (int index = 0; index < array.length(); index++) {
                JSONObject network = array.getJSONObject(index);
                scans.add(new VirtualWifiNetworkSnapshot(network.optString("ssid", ""),
                        network.optString("bssid", ""), network.optString("capabilities", ""),
                        network.optInt("frequencyMhz", 0), network.optInt("rssi", -127),
                        network.optBoolean("hidden", false)));
            }
        }
        return new VirtualWifiProfileSnapshot(value.getString("mode"), value.optBoolean("enabled", false),
                value.optString("ssid", ""), value.optString("bssid", ""),
                value.optString("macAddress", ""), value.optInt("ipv4Address", 0),
                value.optInt("networkId", -1), value.optInt("linkSpeedMbps", 0),
                value.optInt("rssi", -127), value.optInt("frequencyMhz", 0),
                value.optBoolean("metered", false), value.optBoolean("hiddenSsid", false), scans);
    }

    private static JSONObject bluetooth(VirtualBluetoothProfileSnapshot value) throws Exception {
        return new JSONObject().put("mode", value.mode()).put("enabled", value.enabled())
                .put("state", value.state()).put("name", value.name()).put("address", value.address())
                .put("discovering", value.discovering()).put("bondedDevices", devices(value.bondedDevices()))
                .put("scanResults", devices(value.scanResults()));
    }
    private static JSONArray devices(List<VirtualBluetoothDeviceSnapshot> values) throws Exception {
        JSONArray array = new JSONArray();
        for (VirtualBluetoothDeviceSnapshot value : values) array.put(new JSONObject()
                .put("address", value.address()).put("name", value.name()).put("type", value.type())
                .put("bondState", value.bondState()).put("rssi", value.rssi())
                .put("serviceUuids", new JSONArray(value.serviceUuids())));
        return array;
    }
    private static VirtualBluetoothProfileSnapshot bluetooth(JSONObject value) throws Exception {
        return new VirtualBluetoothProfileSnapshot(value.getString("mode"),
                value.optBoolean("enabled", false), value.optInt("state", 10),
                value.optString("name", ""), value.optString("address", ""),
                value.optBoolean("discovering", false), devices(value.optJSONArray("bondedDevices"), 64),
                devices(value.optJSONArray("scanResults"), 128));
    }
    private static List<VirtualBluetoothDeviceSnapshot> devices(JSONArray array, int maximum) throws Exception {
        List<VirtualBluetoothDeviceSnapshot> result = new ArrayList<>();
        if (array == null) return result;
        if (array.length() > maximum) throw new IllegalStateException("Bluetooth device limit exceeded");
        for (int index = 0; index < array.length(); index++) {
            JSONObject value = array.getJSONObject(index);
            result.add(new VirtualBluetoothDeviceSnapshot(value.getString("address"),
                    value.optString("name", ""), value.optInt("type", 0),
                    value.optInt("bondState", 10), value.optInt("rssi", -127),
                    strings(value.optJSONArray("serviceUuids"), 32)));
        }
        return result;
    }

    private static JSONObject sensors(VirtualSensorProfileSnapshot value) throws Exception {
        JSONArray array = new JSONArray();
        for (VirtualSensorSnapshot sensor : value.sensors()) {
            JSONArray samples = new JSONArray();
            for (float sample : sensor.values()) samples.put(sample);
            array.put(new JSONObject().put("handle", sensor.handle()).put("type", sensor.type())
                    .put("name", sensor.name()).put("vendor", sensor.vendor()).put("version", sensor.version())
                    .put("maximumRange", sensor.maximumRange()).put("resolution", sensor.resolution())
                    .put("powerMilliamp", sensor.powerMilliamp()).put("minimumDelayUs", sensor.minimumDelayUs())
                    .put("maximumDelayUs", sensor.maximumDelayUs()).put("reportingMode", sensor.reportingMode())
                    .put("wakeUp", sensor.wakeUp()).put("dynamic", sensor.dynamic())
                    .put("values", samples).put("accuracy", sensor.accuracy()));
        }
        return new JSONObject().put("mode", value.mode())
                .put("maximumEventsPerSecond", value.maximumEventsPerSecond()).put("sensors", array);
    }
    private static VirtualSensorProfileSnapshot sensors(JSONObject value) throws Exception {
        JSONArray array = value.optJSONArray("sensors");
        List<VirtualSensorSnapshot> sensors = new ArrayList<>();
        if (array != null) {
            if (array.length() > 128) throw new IllegalStateException("Sensor limit exceeded");
            for (int index = 0; index < array.length(); index++) {
                JSONObject sensor = array.getJSONObject(index);
                JSONArray samples = sensor.optJSONArray("values");
                int count = samples == null ? 0 : samples.length();
                if (count > 16) throw new IllegalStateException("Sensor sample limit exceeded");
                float[] values = new float[count];
                for (int sample = 0; sample < count; sample++) values[sample] = (float) samples.getDouble(sample);
                sensors.add(new VirtualSensorSnapshot(sensor.getInt("handle"), sensor.getInt("type"),
                        sensor.getString("name"), sensor.optString("vendor", ""),
                        sensor.optInt("version", 0), (float) sensor.optDouble("maximumRange", 0d),
                        (float) sensor.optDouble("resolution", 0d),
                        (float) sensor.optDouble("powerMilliamp", 0d),
                        sensor.optInt("minimumDelayUs", 0), sensor.optInt("maximumDelayUs", 0),
                        sensor.optInt("reportingMode", 0), sensor.optBoolean("wakeUp", false),
                        sensor.optBoolean("dynamic", false), values, sensor.optInt("accuracy", 0)));
            }
        }
        return new VirtualSensorProfileSnapshot(value.getString("mode"),
                value.optInt("maximumEventsPerSecond", 60), sensors);
    }

    private static List<String> strings(JSONArray array, int maximum) throws Exception {
        List<String> result = new ArrayList<>();
        if (array == null) return result;
        if (array.length() > maximum) throw new IllegalStateException("String-list limit exceeded");
        for (int index = 0; index < array.length(); index++) result.add(array.getString(index));
        return result;
    }
}
