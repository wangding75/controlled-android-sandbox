package com.warden.controlledsandbox;

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Base64;
import com.warden.controlledsandbox.contract.VirtualBluetoothProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualCameraProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualCameraSourceSnapshot;
import com.warden.controlledsandbox.contract.VirtualCellInfoSnapshot;
import com.warden.controlledsandbox.contract.VirtualDeviceIdentitySnapshot;
import com.warden.controlledsandbox.contract.VirtualDeviceServiceProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualNetworkServiceProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPeripheralServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualTelephonyProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualTelephonySlotSnapshot;
import com.warden.controlledsandbox.contract.VirtualWifiNetworkSnapshot;
import com.warden.controlledsandbox.contract.VirtualWifiProfileSnapshot;
import com.warden.controlledsandbox.domain.migration.SxMigrationRecord;
import com.warden.controlledsandbox.domain.migration.SxMigrationStore;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONObject;

/** Host-side SX migration store. Profiles stay instance-scoped; old source is kept on disk. */
final class SxMigrationHostStore implements SxMigrationStore {
    private final Context context;
    private final PackageServiceClient packages;

    SxMigrationHostStore(Context context, PackageServiceClient packages) {
        this.context = context.getApplicationContext();
        this.packages = packages;
    }

    @Override public SxMigrationRecord read(String packageName, int virtualUserId) {
        File file = recordFile(packageName, virtualUserId);
        if (!file.isFile()) return null;
        try {
            JSONObject json = new JSONObject(new String(readAll(file), StandardCharsets.UTF_8));
            Map<String, String> backup = new LinkedHashMap<>();
            JSONObject backupJson = json.optJSONObject("backup");
            if (backupJson != null) {
                java.util.Iterator<String> keys = backupJson.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    backup.put(key, backupJson.optString(key, ""));
                }
            }
            return new SxMigrationRecord(json.optString("packageName"), json.optInt("virtualUserId"),
                    json.optString("sourceSchema"), json.optString("targetSchema"),
                    json.optString("sourceHash"), json.optString("sourceCanonical"),
                    json.optString("status"), backup, json.optString("appliedHash"),
                    json.optString("mediaPath"), json.optBoolean("sourceKept", true));
        } catch (Exception error) {
            throw new IllegalStateException("SX_MIGRATION_RECORD_UNREADABLE", error);
        }
    }

    @Override public void write(SxMigrationRecord record) {
        try {
            File file = recordFile(record.packageName, record.virtualUserId);
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IllegalStateException("SX_MIGRATION_DIRECTORY_CREATE_FAILED");
            }
            JSONObject backup = new JSONObject();
            for (Map.Entry<String, String> entry : record.backup.entrySet()) {
                backup.put(entry.getKey(), entry.getValue());
            }
            JSONObject json = new JSONObject()
                    .put("packageName", record.packageName)
                    .put("virtualUserId", record.virtualUserId)
                    .put("sourceSchema", record.sourceSchema)
                    .put("targetSchema", record.targetSchema)
                    .put("sourceHash", record.sourceHash)
                    .put("sourceCanonical", record.sourceCanonical)
                    .put("status", record.status)
                    .put("backup", backup)
                    .put("appliedHash", record.appliedHash)
                    .put("mediaPath", record.mediaPath)
                    .put("sourceKept", record.sourceKept);
            FileOutputStream output = new FileOutputStream(file);
            try {
                output.write(json.toString(2).getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            } finally {
                output.close();
            }
        } catch (Exception error) {
            throw new IllegalStateException("SX_MIGRATION_RECORD_WRITE_FAILED", error);
        }
    }

    @Override public Map<String, String> snapshotProfiles(String packageName, int virtualUserId) {
        try {
            Map<String, String> snapshot = new LinkedHashMap<>();
            snapshot.put("device", encode(packages.deviceServiceProfile(packageName, virtualUserId)));
            snapshot.put("network", encode(packages.networkServiceProfile(packageName, virtualUserId)));
            snapshot.put("peripheral", encode(packages.peripheralServicesProfile(packageName, virtualUserId)));
            return snapshot;
        } catch (Exception error) {
            throw new IllegalStateException("SX_MIGRATION_SNAPSHOT_FAILED", error);
        }
    }

    @Override public void restoreProfiles(String packageName, int virtualUserId,
            Map<String, String> snapshot) {
        try {
            if (snapshot.containsKey("device")) {
                packages.setDeviceServiceProfile(packageName, virtualUserId,
                        decode(snapshot.get("device"), VirtualDeviceServiceProfileSnapshot.CREATOR));
            }
            if (snapshot.containsKey("network")) {
                packages.setNetworkServiceProfile(packageName, virtualUserId,
                        decode(snapshot.get("network"), VirtualNetworkServiceProfileSnapshot.CREATOR));
            }
            if (snapshot.containsKey("peripheral")) {
                packages.setPeripheralServicesProfile(packageName, virtualUserId,
                        decode(snapshot.get("peripheral"), VirtualPeripheralServicesProfileSnapshot.CREATOR));
            }
        } catch (Exception error) {
            throw new IllegalStateException("SX_MIGRATION_RESTORE_FAILED", error);
        }
    }

    @Override public void applyProfiles(String packageName, int virtualUserId,
            Map<String, String> mapped) {
        try {
            writeText(displayNameFile(packageName, virtualUserId),
                    mapped.getOrDefault("displayName", ""));
            VirtualDeviceServiceProfileSnapshot device =
                    packages.deviceServiceProfile(packageName, virtualUserId);
            packages.setDeviceServiceProfile(packageName, virtualUserId,
                    overlayDevice(device, mapped));
            VirtualNetworkServiceProfileSnapshot network =
                    packages.networkServiceProfile(packageName, virtualUserId);
            packages.setNetworkServiceProfile(packageName, virtualUserId, network);
            VirtualPeripheralServicesProfileSnapshot peripheral =
                    packages.peripheralServicesProfile(packageName, virtualUserId);
            packages.setPeripheralServicesProfile(packageName, virtualUserId,
                    overlayPeripheral(peripheral, mapped));
        } catch (Exception error) {
            throw new IllegalStateException("SX_MIGRATION_APPLY_FAILED", error);
        }
    }

    @Override public Map<String, String> readApplied(String packageName, int virtualUserId) {
        try {
            VirtualDeviceServiceProfileSnapshot device =
                    packages.deviceServiceProfile(packageName, virtualUserId);
            VirtualPeripheralServicesProfileSnapshot peripheral =
                    packages.peripheralServicesProfile(packageName, virtualUserId);
            Map<String, String> applied = new LinkedHashMap<>();
            applied.put("location.lat", Double.toString(device.location().latitude()));
            applied.put("location.lng", Double.toString(device.location().longitude()));
            applied.put("device.androidId", device.identity().androidId());
            applied.put("device.brand", device.identity().brand());
            applied.put("network.ssid", device.wifi().ssid());
            applied.put("bluetooth.name", device.bluetooth().name());
            applied.put("camera.mediaPath", peripheral.camera().source().relativePath());
            applied.put("camera.sha256", peripheral.camera().source().sha256());
            applied.put("displayName", readText(displayNameFile(packageName, virtualUserId)));
            return applied;
        } catch (Exception error) {
            throw new IllegalStateException("SX_MIGRATION_READBACK_FAILED", error);
        }
    }

    @Override public String writeMedia(String packageName, int virtualUserId, String kind,
            byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String sha = hex(digest.digest(bytes));
            File mediaRoot = new File(context.getFilesDir(),
                    "instances/u" + virtualUserId + "/" + safe(packageName)
                            + "/data/files/virtual-camera");
            if (!mediaRoot.isDirectory() && !mediaRoot.mkdirs() && !mediaRoot.isDirectory()) {
                throw new IllegalStateException("SX_MIGRATION_MEDIA_DIRECTORY_FAILED");
            }
            String relative = "virtual-camera/source-" + sha + ".png";
            File destination = new File(mediaRoot, "source-" + sha + ".png");
            FileOutputStream output = new FileOutputStream(destination);
            try {
                output.write(bytes);
                output.getFD().sync();
            } finally {
                output.close();
            }
            return relative + "|" + sha;
        } catch (Exception error) {
            throw new IllegalStateException("SX_MIGRATION_MEDIA_WRITE_FAILED", error);
        }
    }

    @Override public void deleteMedia(String packageName, int virtualUserId, String relativePath) {
        String path = relativePath == null ? "" : relativePath.split("\\|")[0];
        if (path.isEmpty()) return;
        File file = new File(context.getFilesDir(),
                "instances/u" + virtualUserId + "/" + safe(packageName) + "/data/files/" + path);
        if (file.isFile() && !file.delete()) file.deleteOnExit();
    }

    private VirtualDeviceServiceProfileSnapshot overlayDevice(
            VirtualDeviceServiceProfileSnapshot current, Map<String, String> mapped) {
        boolean locationEnabled = bool(mapped, "location.enabled");
        VirtualLocationProfileSnapshot location = new VirtualLocationProfileSnapshot(
                locationEnabled ? VirtualLocationProfileSnapshot.MODE_STATIC
                        : VirtualLocationProfileSnapshot.MODE_BLOCKED,
                "gps", locationEnabled,
                number(mapped, "location.lat", current.location().latitude()),
                number(mapped, "location.lng", current.location().longitude()),
                number(mapped, "location.altitude", current.location().altitudeMeters()),
                (float) number(mapped, "location.accuracy", current.location().accuracyMeters()),
                current.location().speedMetersPerSecond(), current.location().bearingDegrees(),
                current.location().timeMs(), current.location().elapsedRealtimeNanos(),
                (long) number(mapped, "location.intervalMs",
                        current.location().minimumUpdateIntervalMs()),
                locationEnabled, current.location().satellitesInView(),
                current.location().satellitesUsedInFix(), current.location().nmeaSentence());
        boolean deviceEnabled = bool(mapped, "device.enabled");
        VirtualDeviceIdentitySnapshot identity = new VirtualDeviceIdentitySnapshot(
                deviceEnabled ? VirtualLocationProfileSnapshot.MODE_STATIC
                        : current.identity().mode(),
                mapped.getOrDefault("device.androidId", current.identity().androidId()),
                mapped.getOrDefault("device.serial", current.identity().serial()),
                current.identity().advertisingId(), current.identity().limitAdTracking(),
                current.identity().installationId(),
                mapped.getOrDefault("device.manufacturer", current.identity().manufacturer()),
                mapped.getOrDefault("device.brand", current.identity().brand()),
                mapped.getOrDefault("device.model", current.identity().model()),
                current.identity().device(), current.identity().product(),
                current.identity().fingerprint(),
                mapped.getOrDefault("device.board", current.identity().board()),
                current.identity().hardware());
        VirtualTelephonySlotSnapshot slot = current.telephony().slots().isEmpty()
                ? null : current.telephony().slots().get(0);
        List<VirtualTelephonySlotSnapshot> slots = new ArrayList<>();
        if (slot != null) {
            slots.add(new VirtualTelephonySlotSnapshot(slot.slotIndex(), slot.subscriptionId(),
                    mapped.getOrDefault("device.imei", slot.imei()),
                    mapped.getOrDefault("device.meid", slot.meid()),
                    mapped.getOrDefault("device.imsi", slot.subscriberId()),
                    mapped.getOrDefault("device.iccid", slot.simSerialNumber()),
                    mapped.getOrDefault("device.phoneNumber", slot.line1Number()),
                    slot.simOperator(), slot.networkOperator(), slot.simCountryIso(),
                    slot.networkCountryIso(),
                    mapped.getOrDefault("device.operatorName", slot.carrierName()),
                    slot.phoneType(), slot.simState(), slot.dataNetworkType(),
                    slot.voiceNetworkType(), slot.dataEnabled(), slot.roaming()));
        } else {
            slots.addAll(current.telephony().slots());
        }
        int mcc = (int) number(mapped, "network.mcc", 460);
        int mnc = (int) number(mapped, "network.mnc", 1);
        int lac = (int) number(mapped, "network.lac", 1);
        long cid = (long) number(mapped, "network.cid", 1);
        List<VirtualCellInfoSnapshot> cells = List.of(new VirtualCellInfoSnapshot(
                VirtualCellInfoSnapshot.LTE, mcc, mnc, lac, lac, cid, 1, 100, true, -80));
        VirtualTelephonyProfileSnapshot telephony = new VirtualTelephonyProfileSnapshot(
                current.telephony().mode(), current.telephony().defaultSubscriptionId(),
                current.telephony().activeDataSubscriptionId(), current.telephony().voiceCapable(),
                current.telephony().smsCapable(), current.telephony().emergencyOnly(),
                slots, cells);
        boolean wifiEnabled = bool(mapped, "network.enabled");
        String ssid = mapped.getOrDefault("network.ssid", current.wifi().ssid());
        String bssid = mapped.getOrDefault("network.bssid", current.wifi().bssid());
        String mac = mapped.getOrDefault("network.mac", current.wifi().macAddress());
        VirtualWifiNetworkSnapshot scan = new VirtualWifiNetworkSnapshot(
                ssid, bssid, "[WPA2-PSK-CCMP][ESS]", current.wifi().frequencyMhz(),
                current.wifi().rssi(), false);
        VirtualWifiProfileSnapshot wifi = new VirtualWifiProfileSnapshot(
                wifiEnabled ? VirtualLocationProfileSnapshot.MODE_STATIC : current.wifi().mode(),
                wifiEnabled, ssid, bssid, mac, current.wifi().ipv4Address(),
                current.wifi().networkId(), current.wifi().linkSpeedMbps(), current.wifi().rssi(),
                current.wifi().frequencyMhz(), current.wifi().metered(), current.wifi().hiddenSsid(),
                List.of(scan));
        boolean btEnabled = bool(mapped, "bluetooth.enabled");
        VirtualBluetoothProfileSnapshot bluetooth = new VirtualBluetoothProfileSnapshot(
                btEnabled ? VirtualLocationProfileSnapshot.MODE_STATIC : current.bluetooth().mode(),
                btEnabled, btEnabled ? 12 : current.bluetooth().state(),
                mapped.getOrDefault("bluetooth.name", current.bluetooth().name()),
                mapped.getOrDefault("bluetooth.address", current.bluetooth().address()),
                false, List.of(), List.of());
        return new VirtualDeviceServiceProfileSnapshot(current.policyVersion(),
                System.currentTimeMillis(), location, identity, telephony, wifi, bluetooth,
                current.sensors());
    }

    private VirtualPeripheralServicesProfileSnapshot overlayPeripheral(
            VirtualPeripheralServicesProfileSnapshot current, Map<String, String> mapped) {
        String media = mapped.getOrDefault("camera.mediaPath", "");
        String[] parts = media.split("\\|", 2);
        String relative = parts.length > 0 ? parts[0] : "";
        String sha = parts.length > 1 ? parts[1] : "";
        boolean cameraEnabled = bool(mapped, "camera.enabled") && !relative.isEmpty();
        VirtualCameraSourceSnapshot source = cameraEnabled
                ? new VirtualCameraSourceSnapshot(VirtualCameraSourceSnapshot.IMAGE, relative,
                "image/png", sha, 1, 1, 0, 0L)
                : VirtualCameraSourceSnapshot.none();
        VirtualCameraProfileSnapshot camera = new VirtualCameraProfileSnapshot(
                cameraEnabled ? VirtualLocationProfileSnapshot.MODE_STATIC
                        : current.camera().mode(),
                cameraEnabled, cameraEnabled, current.camera().allowTorch(),
                cameraEnabled ? Math.max(1, current.camera().maximumOpenCameras()) : 0,
                cameraEnabled ? List.of("0") : List.of(),
                cameraEnabled ? List.of("0") : List.of(),
                List.of(), source, cameraEnabled);
        return new VirtualPeripheralServicesProfileSnapshot(current.policyVersion(),
                System.currentTimeMillis(), current.nfc(), current.usb(), current.printing(),
                current.companionDevice(), current.mediaProjection(), camera,
                current.oemSystemServices());
    }

    private File recordFile(String packageName, int virtualUserId) {
        return new File(context.getFilesDir(),
                "sx-migration/" + safe(packageName) + "/u" + virtualUserId + "/record.json");
    }

    private File displayNameFile(String packageName, int virtualUserId) {
        return new File(context.getFilesDir(),
                "instances/u" + virtualUserId + "/" + safe(packageName) + "/display-name.txt");
    }

    private static String encode(Parcelable value) {
        Parcel parcel = Parcel.obtain();
        try {
            value.writeToParcel(parcel, 0);
            return Base64.encodeToString(parcel.marshall(), Base64.NO_WRAP);
        } finally {
            parcel.recycle();
        }
    }

    private static <T> T decode(String encoded, Parcelable.Creator<T> creator) {
        byte[] data = Base64.decode(encoded, Base64.NO_WRAP);
        Parcel parcel = Parcel.obtain();
        try {
            parcel.unmarshall(data, 0, data.length);
            parcel.setDataPosition(0);
            return creator.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }

    private static boolean bool(Map<String, String> mapped, String key) {
        return "true".equalsIgnoreCase(mapped.getOrDefault(key, "false"));
    }

    private static double number(Map<String, String> mapped, String key, double fallback) {
        try {
            return Double.parseDouble(mapped.getOrDefault(key, Double.toString(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String safe(String packageName) {
        return packageName.replace(':', '_').replace('/', '_');
    }

    private static String hex(byte[] data) {
        StringBuilder out = new StringBuilder(data.length * 2);
        for (byte item : data) out.append(String.format(Locale.ROOT, "%02x", item));
        return out.toString();
    }

    private static byte[] readAll(File file) throws Exception {
        byte[] data = new byte[(int) file.length()];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < data.length) {
                int read = input.read(data, offset, data.length - offset);
                if (read < 0) break;
                offset += read;
            }
        }
        return data;
    }

    private static void writeText(File file, String text) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IllegalStateException("SX_MIGRATION_TEXT_DIRECTORY_FAILED");
        }
        FileOutputStream output = new FileOutputStream(file);
        try {
            output.write((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        } finally {
            output.close();
        }
    }

    private static String readText(File file) throws Exception {
        if (!file.isFile()) return "";
        return new String(readAll(file), StandardCharsets.UTF_8).trim();
    }
}
