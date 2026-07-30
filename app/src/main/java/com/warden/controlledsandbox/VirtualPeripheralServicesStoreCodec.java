package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.VirtualCameraProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualCompanionDeviceProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualMediaProjectionProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualNfcProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualOemSystemServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPeripheralServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPrintProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualUsbProfileSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded JSON codec for peripheral/external-service profiles. */
final class VirtualPeripheralServicesStoreCodec {
    static final int SCHEMA = 1;
    static final int MAX_SCOPES = 256;

    private VirtualPeripheralServicesStoreCodec() { }

    static String encode(
            Map<VirtualSystemServiceStore.Scope, VirtualPeripheralServicesProfileSnapshot> profiles) {
        try {
            JSONArray scopes = new JSONArray();
            for (Map.Entry<VirtualSystemServiceStore.Scope, VirtualPeripheralServicesProfileSnapshot> entry
                    : profiles.entrySet()) {
                VirtualPeripheralServicesProfileSnapshot value = entry.getValue();
                scopes.put(new JSONObject()
                        .put("packageName", entry.getKey().packageName())
                        .put("virtualUserId", entry.getKey().virtualUserId())
                        .put("policyVersion", value.policyVersion())
                        .put("updatedAtMs", value.updatedAtMs())
                        .put("nfc", nfc(value.nfc()))
                        .put("usb", usb(value.usb()))
                        .put("printing", printing(value.printing()))
                        .put("companionDevice", companion(value.companionDevice()))
                        .put("mediaProjection", projection(value.mediaProjection()))
                        .put("camera", camera(value.camera()))
                        .put("oemSystemServices", oem(value.oemSystemServices())));
            }
            return new JSONObject().put("schema", SCHEMA).put("scopes", scopes).toString();
        } catch (Exception error) {
            throw new IllegalStateException("Cannot encode peripheral-services profiles", error);
        }
    }

    static Map<VirtualSystemServiceStore.Scope, VirtualPeripheralServicesProfileSnapshot> decode(
            String payload) {
        try {
            JSONObject root = new JSONObject(payload);
            if (root.getInt("schema") != SCHEMA) {
                throw new IllegalStateException("PERIPHERAL_SERVICES_SCHEMA_UNSUPPORTED");
            }
            JSONArray scopes = root.getJSONArray("scopes");
            if (scopes.length() > MAX_SCOPES) {
                throw new IllegalStateException("Peripheral-services scope limit exceeded");
            }
            Map<VirtualSystemServiceStore.Scope, VirtualPeripheralServicesProfileSnapshot> out =
                    new LinkedHashMap<>();
            for (int index = 0; index < scopes.length(); index++) {
                JSONObject value = scopes.getJSONObject(index);
                VirtualSystemServiceStore.Scope scope = new VirtualSystemServiceStore.Scope(
                        value.getString("packageName"), value.getInt("virtualUserId"));
                VirtualPeripheralServicesProfileSnapshot previous = out.put(scope,
                        new VirtualPeripheralServicesProfileSnapshot(
                                value.getLong("policyVersion"),
                                value.optLong("updatedAtMs", 0L),
                                nfc(value.getJSONObject("nfc")),
                                usb(value.getJSONObject("usb")),
                                printing(value.getJSONObject("printing")),
                                companion(value.getJSONObject("companionDevice")),
                                projection(value.getJSONObject("mediaProjection")),
                                camera(value.getJSONObject("camera")),
                                oem(value.getJSONObject("oemSystemServices"))));
                if (previous != null) {
                    throw new IllegalStateException("PERIPHERAL_SERVICES_SCOPE_DUPLICATE");
                }
            }
            return out;
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("Cannot decode peripheral-services profiles", error);
        }
    }

    private static JSONObject nfc(VirtualNfcProfileSnapshot value) throws Exception {
        return new JSONObject().put("mode", value.mode())
                .put("adapterState", value.adapterState())
                .put("readerModeAllowed", value.readerModeAllowed())
                .put("cardEmulationAvailable", value.cardEmulationAvailable())
                .put("ndefPushEnabled", value.ndefPushEnabled())
                .put("maximumReaderSessions", value.maximumReaderSessions())
                .put("maximumTagOperations", value.maximumTagOperations())
                .put("tagIds", new JSONArray(value.tagIds()));
    }

    private static VirtualNfcProfileSnapshot nfc(JSONObject value) throws Exception {
        return new VirtualNfcProfileSnapshot(
                value.getString("mode"), value.optString("adapterState", "OFF"),
                value.optBoolean("readerModeAllowed", false),
                value.optBoolean("cardEmulationAvailable", false),
                value.optBoolean("ndefPushEnabled", false),
                value.optInt("maximumReaderSessions", 0),
                value.optInt("maximumTagOperations", 0),
                strings(value.optJSONArray("tagIds"), 128));
    }

    private static JSONObject usb(VirtualUsbProfileSnapshot value) throws Exception {
        return new JSONObject().put("mode", value.mode())
                .put("hostSupported", value.hostSupported())
                .put("accessorySupported", value.accessorySupported())
                .put("allowPermissionRequests", value.allowPermissionRequests())
                .put("allowOpenDevice", value.allowOpenDevice())
                .put("maximumOpenDevices", value.maximumOpenDevices())
                .put("defaultFunctions", value.defaultFunctions())
                .put("approvedDeviceNames", new JSONArray(value.approvedDeviceNames()))
                .put("approvedAccessoryIds", new JSONArray(value.approvedAccessoryIds()));
    }

    private static VirtualUsbProfileSnapshot usb(JSONObject value) throws Exception {
        return new VirtualUsbProfileSnapshot(
                value.getString("mode"), value.optBoolean("hostSupported", false),
                value.optBoolean("accessorySupported", false),
                value.optBoolean("allowPermissionRequests", false),
                value.optBoolean("allowOpenDevice", false),
                value.optInt("maximumOpenDevices", 0),
                value.optString("defaultFunctions", "none"),
                strings(value.optJSONArray("approvedDeviceNames"), 128),
                strings(value.optJSONArray("approvedAccessoryIds"), 64));
    }

    private static JSONObject printing(VirtualPrintProfileSnapshot value) throws Exception {
        return new JSONObject().put("mode", value.mode())
                .put("printingEnabled", value.printingEnabled())
                .put("allowPrintJobs", value.allowPrintJobs())
                .put("maximumActiveJobs", value.maximumActiveJobs())
                .put("defaultPrinterId", value.defaultPrinterId())
                .put("defaultPrinterName", value.defaultPrinterName())
                .put("availablePrintServices", new JSONArray(value.availablePrintServices()));
    }

    private static VirtualPrintProfileSnapshot printing(JSONObject value) throws Exception {
        return new VirtualPrintProfileSnapshot(
                value.getString("mode"), value.optBoolean("printingEnabled", false),
                value.optBoolean("allowPrintJobs", false),
                value.optInt("maximumActiveJobs", 0),
                value.optString("defaultPrinterId", ""),
                value.optString("defaultPrinterName", ""),
                strings(value.optJSONArray("availablePrintServices"), 64));
    }

    private static JSONObject companion(VirtualCompanionDeviceProfileSnapshot value) throws Exception {
        return new JSONObject().put("mode", value.mode())
                .put("allowAssociation", value.allowAssociation())
                .put("allowDisassociation", value.allowDisassociation())
                .put("presenceObservationEnabled", value.presenceObservationEnabled())
                .put("selfManagedAssociationsAllowed", value.selfManagedAssociationsAllowed())
                .put("maximumAssociations", value.maximumAssociations())
                .put("associationIds", new JSONArray(value.associationIds()))
                .put("approvedDeviceProfiles", new JSONArray(value.approvedDeviceProfiles()));
    }

    private static VirtualCompanionDeviceProfileSnapshot companion(JSONObject value) throws Exception {
        return new VirtualCompanionDeviceProfileSnapshot(
                value.getString("mode"), value.optBoolean("allowAssociation", false),
                value.optBoolean("allowDisassociation", false),
                value.optBoolean("presenceObservationEnabled", false),
                value.optBoolean("selfManagedAssociationsAllowed", false),
                value.optInt("maximumAssociations", 0),
                strings(value.optJSONArray("associationIds"), 128),
                strings(value.optJSONArray("approvedDeviceProfiles"), 64));
    }

    private static JSONObject projection(VirtualMediaProjectionProfileSnapshot value) throws Exception {
        return new JSONObject().put("mode", value.mode())
                .put("projectionAvailable", value.projectionAvailable())
                .put("allowScreenCapture", value.allowScreenCapture())
                .put("allowAudioCapture", value.allowAudioCapture())
                .put("requireConsent", value.requireConsent())
                .put("maximumActiveSessions", value.maximumActiveSessions())
                .put("virtualWidth", value.virtualWidth())
                .put("virtualHeight", value.virtualHeight())
                .put("densityDpi", value.densityDpi());
    }

    private static VirtualMediaProjectionProfileSnapshot projection(JSONObject value) throws Exception {
        return new VirtualMediaProjectionProfileSnapshot(
                value.getString("mode"), value.optBoolean("projectionAvailable", false),
                value.optBoolean("allowScreenCapture", false),
                value.optBoolean("allowAudioCapture", false),
                value.optBoolean("requireConsent", true),
                value.optInt("maximumActiveSessions", 0),
                value.optInt("virtualWidth", 1080), value.optInt("virtualHeight", 1920),
                value.optInt("densityDpi", 420));
    }

    private static JSONObject camera(VirtualCameraProfileSnapshot value) throws Exception {
        return new JSONObject().put("mode", value.mode())
                .put("cameraAvailable", value.cameraAvailable())
                .put("allowOpen", value.allowOpen())
                .put("allowTorch", value.allowTorch())
                .put("maximumOpenCameras", value.maximumOpenCameras())
                .put("cameraIds", new JSONArray(value.cameraIds()))
                .put("frontCameraIds", new JSONArray(value.frontCameraIds()))
                .put("torchAvailableCameraIds", new JSONArray(value.torchAvailableCameraIds()));
    }

    private static VirtualCameraProfileSnapshot camera(JSONObject value) throws Exception {
        return new VirtualCameraProfileSnapshot(
                value.getString("mode"), value.optBoolean("cameraAvailable", false),
                value.optBoolean("allowOpen", false), value.optBoolean("allowTorch", false),
                value.optInt("maximumOpenCameras", 0),
                strings(value.optJSONArray("cameraIds"), 32),
                strings(value.optJSONArray("frontCameraIds"), 32),
                strings(value.optJSONArray("torchAvailableCameraIds"), 32));
    }

    private static JSONObject oem(VirtualOemSystemServicesProfileSnapshot value) throws Exception {
        return new JSONObject().put("mode", value.mode())
                .put("serviceNames", new JSONArray(value.serviceNames()))
                .put("allowedQueryPrefixes", new JSONArray(value.allowedQueryPrefixes()))
                .put("blockedMutationPrefixes", new JSONArray(value.blockedMutationPrefixes()))
                .put("maximumSessions", value.maximumSessions());
    }

    private static VirtualOemSystemServicesProfileSnapshot oem(JSONObject value) throws Exception {
        return new VirtualOemSystemServicesProfileSnapshot(
                value.getString("mode"), strings(value.optJSONArray("serviceNames"), 64),
                strings(value.optJSONArray("allowedQueryPrefixes"), 128),
                strings(value.optJSONArray("blockedMutationPrefixes"), 128),
                value.optInt("maximumSessions", 0));
    }

    private static List<String> strings(JSONArray array, int maximum) throws Exception {
        if (array == null) return List.of();
        if (array.length() > maximum) throw new IllegalStateException("String-list limit exceeded");
        ArrayList<String> out = new ArrayList<>();
        for (int index = 0; index < array.length(); index++) out.add(array.getString(index));
        return out;
    }
}
