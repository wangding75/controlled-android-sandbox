package com.warden.controlledsandbox;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import com.warden.controlledsandbox.contract.InstallSessionInfoSnapshot;
import com.warden.controlledsandbox.contract.InstallSessionParamsSnapshot;
import com.warden.controlledsandbox.contract.NativeCompanionResult;
import com.warden.controlledsandbox.contract.VirtualCameraProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualCameraSourceSnapshot;
import com.warden.controlledsandbox.contract.VirtualDeviceServiceProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPeripheralServicesProfileSnapshot;
import com.warden.controlledsandbox.compatibility.dingtalk.DingTalkCompatibilityManager;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Debug-build-only ADB entrypoint for deterministic emulator gates. */
public final class DebugCommandActivity extends Activity {
    private static final String TAG = "CS_COMMAND";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        executeIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        executeIntent(intent);
    }

    private void executeIntent(Intent intent) {
        String command = intent == null ? "" : intent.getStringExtra("command");
        String packageName = intent == null ? "" : intent.getStringExtra("package");
        int virtualUserId = intent == null ? 0 : intent.getIntExtra("user", 0);
        Bundle extras = intent == null ? null : intent.getExtras();
        Bundle commandExtras = extras == null ? new Bundle() : new Bundle(extras);
        boolean trustNativeGuest = extras != null && extras.getBoolean("trustNativeGuest", false);
        Log.i(TAG, "COMMAND_BEGIN command=" + command + " package=" + packageName
                + " user=" + virtualUserId);
        worker.execute(() -> execute(command == null ? "" : command,
                packageName == null ? "" : packageName, virtualUserId, trustNativeGuest,
                commandExtras));
    }

    private void execute(String command, String packageName, int virtualUserId,
                         boolean trustNativeGuest, Bundle extras) {
        JSONObject result = new JSONObject();
        RuntimeClient runtime = null;
        PackageServiceClient packages = null;
        try {
            result.put("command", command).put("package", packageName)
                    .put("virtualUserId", virtualUserId).put("trustNativeGuest", trustNativeGuest)
                    .put("startedAt", System.currentTimeMillis());
            if (packageName.trim().isEmpty()) throw new IllegalArgumentException("package extra is required");
            Log.i(TAG, "PACKAGE_LOOKUP_BEGIN command=" + command + " package=" + packageName);
            packages = new PackageServiceClient(this);
            SandboxRecord record = packages.findRecord(packageName);
            Log.i(TAG, "PACKAGE_LOOKUP_RETURN command=" + command + " package=" + packageName);
            boolean importRequested = "import-launch".equals(command)
                    || "import-prepare".equals(command) || record == null;
            if (importRequested) {
                ApplicationInfo installed = getPackageManager().getApplicationInfo(packageName, 0);
                File source = new File(installed.sourceDir);
                record = trustNativeGuest
                        ? trustedNativeImport(packages, packageName, source)
                        : packages.importApkFile(source);
            }
            result.put("nativeGuestTrust", record.nativeGuestTrust);
            packages.ensureInstance(packageName, virtualUserId);
            Log.i(TAG, "INSTANCE_READY command=" + command + " package=" + packageName
                    + " user=" + virtualUserId);
            runtime = new RuntimeClient(this);
            Bundle operation;
            if ("stop".equals(command)) {
                Log.i(TAG, "STOP_CALL_BEGIN package=" + packageName + " user=" + virtualUserId);
                runtime.stop(record, virtualUserId);
                Log.i(TAG, "STOP_CALL_RETURN package=" + packageName + " user=" + virtualUserId);
                operation = new Bundle();
                operation.putString(RuntimeKeys.STATUS, "STOPPED");
            } else if ("clear".equals(command)) {
                packages.clearInstanceData(packageName, virtualUserId);
                operation = new Bundle();
                operation.putString(RuntimeKeys.STATUS, "CLEARED");
            } else if ("delete".equals(command)) {
                runtime.stop(record, virtualUserId);
                packages.deleteInstance(packageName, virtualUserId);
                operation = new Bundle();
                operation.putString(RuntimeKeys.STATUS, "DELETED");
            } else if ("dingtalk-disable".equals(command)) {
                new DingTalkCompatibilityManager().disable(this, packageName, virtualUserId);
                operation = new Bundle();
                operation.putString(RuntimeKeys.STATUS, "DINGTALK_COMPATIBILITY_DISABLED");
            } else if ("reset-device-profile".equals(command)) {
                VirtualDeviceServiceProfileSnapshot reset = packages.resetDeviceServiceProfile(
                        packageName, virtualUserId);
                operation = new Bundle();
                operation.putString(RuntimeKeys.STATUS, "DEVICE_PROFILE_RESET");
                operation.putLong("policyVersion", reset.policyVersion());
                operation.putInt("cellCount", reset.telephony().cells().size());
            } else if ("set-permissions".equals(command)) {
                operation = setPermissions(packages, packageName, virtualUserId, extras);
                requireStatus("permissions", operation, "PERMISSIONS_UPDATED");
            } else if ("configure-location".equals(command)
                    || "configure-camera".equals(command)
                    || "dingtalk-profile".equals(command)) {
                boolean dingtalk = "dingtalk-profile".equals(command);
                operation = configureProfiles(packages, record, packageName, virtualUserId,
                        extras, dingtalk);
                requireStatus("profile", operation, "PROFILE_CONFIGURED");
            } else if ("launch-component".equals(command)) {
                String component = extras.getString("component", "").trim();
                if (component.isEmpty()) {
                    throw new IllegalArgumentException("component extra is required");
                }
                operation = runtime.launchComponent(record, virtualUserId, component);
                requireStatus("launch-component", operation, "LAUNCH_PASS");
            } else if ("import-launch".equals(command) || "launch".equals(command)) {
                operation = runtime.launch(record, virtualUserId);
                requireStatus("launch", operation, "LAUNCH_PASS");
            } else if ("component-suite".equals(command)) {
                Bundle serviceStart = runtime.startService(record, virtualUserId);
                requireStatus("serviceStart", serviceStart, "SERVICE_STARTED");
                Bundle receiver = runtime.sendBroadcast(record, virtualUserId);
                requireStatus("receiver", receiver, "BROADCAST_DELIVERED");
                Bundle provider = runtime.prepareProvider(record, virtualUserId);
                requireStatus("provider", provider, "PROVIDER_READY", "PROVIDER_ALREADY_READY");
                Bundle serviceStop = runtime.stopService(record, virtualUserId);
                requireStatus("serviceStop", serviceStop, "SERVICE_STOPPED", "SERVICE_NOT_RUNNING");
                JSONObject components = new JSONObject();
                components.put("serviceStart", bundleJson(serviceStart));
                components.put("receiver", bundleJson(receiver));
                components.put("provider", bundleJson(provider));
                components.put("serviceStop", bundleJson(serviceStop));
                result.put("components", components);
                operation = runtime.prepare(record, virtualUserId);
                requireStatus("prepare", operation, "PREPARED", "ALREADY_PREPARED");
            } else if ("isolated-service".equals(command)) {
                String component = extras.getString("component", "").trim();
                if (component.isEmpty()) {
                    throw new IllegalArgumentException("component extra is required");
                }
                String processName = extras.getString("processName", "").trim();
                String serviceOperation = extras.getString("serviceOperation", "start").trim();
                if ("stop".equalsIgnoreCase(serviceOperation)) {
                    operation = runtime.stopService(record, virtualUserId, component, processName);
                    requireStatus("isolated-service-stop", operation,
                            "SERVICE_STOPPED", "SERVICE_NOT_RUNNING", "SERVICE_STOP_REQUESTED");
                } else if ("start".equalsIgnoreCase(serviceOperation)) {
                    operation = runtime.startService(record, virtualUserId, component, processName);
                    requireStatus("isolated-service-start", operation,
                            "SERVICE_STARTED", "SERVICE_RECOVERED");
                } else {
                    throw new IllegalArgumentException("serviceOperation must be start or stop");
                }
                result.put("isolatedComponent", component);
                result.put("isolatedServiceOperation", serviceOperation);
            } else if ("prepare".equals(command) || "import-prepare".equals(command)) {
                operation = runtime.prepare(record, virtualUserId);
                requireStatus("prepare", operation, "PREPARED", "ALREADY_PREPARED");
            } else if ("hold-prepare".equals(command)) {
                // RD crash/recovery probe only: keep the RuntimeClient/Broker binding alive long
                // enough for the external harness to SIGKILL the concrete Guest process before
                // the normal client teardown path releases the slot.
                operation = runtime.prepare(record, virtualUserId);
                requireStatus("hold-prepare", operation, "PREPARED", "ALREADY_PREPARED");
                String kickoffComponent = extras.getString("launchComponent", "").trim();
                if (!kickoffComponent.isEmpty()) {
                    Bundle kickoff = runtime.launchComponent(record, virtualUserId, kickoffComponent);
                    requireStatus("hold-prepare-launch", kickoff, "LAUNCH_PASS");
                }
                long holdMs = Math.max(5_000L, Math.min(60_000L,
                        extras.getLong("holdMs", 30_000L)));
                try {
                    Thread.sleep(holdMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("HOLD_PREPARE_INTERRUPTED", interrupted);
                }
            } else {
                throw new IllegalArgumentException("Unsupported command: " + command);
            }
            result.put("operation", bundleJson(operation));
            if (NativeAbiRoutePlanner.requiresCompanion(record.nativeAbi)) {
                try (NativeCompanionClient companion = new NativeCompanionClient(this)) {
                    NativeCompanionResult probe = companion.probe(record, virtualUserId);
                    result.put("companion", companionJson(probe));
                    if (!probe.successful() || probe.processBitness() != 32) {
                        throw new IllegalStateException("NATIVE_COMPANION_PROBE_FAILED:"
                                + probe.errorType() + ":" + probe.errorMessage());
                    }
                }
            }
            result.put("status", "PASS");
            Log.i(TAG, "PASS " + command + " " + packageName + " user=" + virtualUserId + " " + operation.getString(RuntimeKeys.STATUS, ""));
        } catch (Throwable error) {
            try {
                result.put("status", "FAIL").put("errorType", error.getClass().getName())
                        .put("errorMessage", String.valueOf(error.getMessage()));
            } catch (Exception ignored) { }
            Log.e(TAG, "FAIL " + command + " " + packageName, error);
        } finally {
            if (runtime != null) runtime.close();
            if (packages != null) packages.close();
            // hold-prepare is intentionally concurrent with the follow-up recovery
            // command; never let its delayed finally block overwrite the result file
            // owned by the replacement launch.
            if (!"hold-prepare".equals(command)) writeResult(result);
            runOnUiThread(() -> { finish(); worker.shutdown(); });
        }
    }

    private Bundle configureProfiles(PackageServiceClient packages, SandboxRecord record,
                                     String packageName, int virtualUserId, Bundle extras,
                                     boolean dingtalk) throws Exception {
        DingTalkCompatibilityManager manager = new DingTalkCompatibilityManager();
        DingTalkCompatibilityManager.Target target = manager.identify(
                record.packageName, record.versionName, record.versionCode);
        if (dingtalk) manager.enable(this, target, virtualUserId);

        VirtualDeviceServiceProfileSnapshot device =
                packages.deviceServiceProfile(packageName, virtualUserId);
        VirtualLocationProfileSnapshot location = locationFromExtras(device.location(), extras);
        VirtualDeviceServiceProfileSnapshot updatedDevice = new VirtualDeviceServiceProfileSnapshot(
                device.policyVersion(), System.currentTimeMillis(), location, device.identity(),
                device.telephony(), device.wifi(), device.bluetooth(), device.sensors());
        packages.setDeviceServiceProfile(packageName, virtualUserId, updatedDevice);

        VirtualPeripheralServicesProfileSnapshot peripheral =
                packages.peripheralServicesProfile(packageName, virtualUserId);
        VirtualCameraProfileSnapshot camera = cameraFromExtras(peripheral.camera(), packageName,
                virtualUserId, extras);
        VirtualPeripheralServicesProfileSnapshot updatedPeripheral =
                new VirtualPeripheralServicesProfileSnapshot(peripheral.policyVersion(),
                        System.currentTimeMillis(), peripheral.nfc(), peripheral.usb(),
                        peripheral.printing(), peripheral.companionDevice(),
                        peripheral.mediaProjection(), camera, peripheral.oemSystemServices());
        packages.setPeripheralServicesProfile(packageName, virtualUserId, updatedPeripheral);

        Bundle result = new Bundle();
        result.putString(RuntimeKeys.STATUS, "PROFILE_CONFIGURED");
        result.putString("profileClass", dingtalk ? "DINGTALK_COMPATIBILITY" : "GENERAL_SANDBOX");
        result.putString("targetReason", target.reason());
        result.putBoolean("targetSupported", target.supported());
        result.putBoolean("dingtalkCompatibilityEnabled",
                manager.enabled(this, packageName, virtualUserId));
        result.putString("locationMode", location.mode());
        result.putDouble("latitude", location.latitude());
        result.putDouble("longitude", location.longitude());
        result.putBoolean("cameraAvailable", camera.cameraAvailable());
        result.putBoolean("captureSubstitution", camera.substituteCaptureResult());
        result.putString("cameraSourceKind", camera.source().kind());
        result.putString("cameraSourceSha256", camera.source().sha256());
        return result;
    }

    private static Bundle setPermissions(PackageServiceClient packages, String packageName,
                                         int virtualUserId, Bundle extras) throws Exception {
        String permissions = extras.getString("permissions", "");
        String decision = extras.getString("decision", "").trim().toUpperCase(java.util.Locale.ROOT);
        if (permissions.trim().isEmpty()) throw new IllegalArgumentException("permissions extra is required");
        if (!java.util.Set.of("DEFAULT", "GRANTED", "DENIED").contains(decision)) {
            throw new IllegalArgumentException("decision must be DEFAULT, GRANTED or DENIED");
        }
        int count = 0;
        for (String permission : permissions.split(",")) {
            String normalized = permission.trim();
            if (normalized.isEmpty()) continue;
            packages.setPermissionDecision(packageName, virtualUserId, normalized, decision);
            count++;
        }
        if (count == 0) throw new IllegalArgumentException("permissions extra contains no values");
        Bundle result = new Bundle();
        result.putString(RuntimeKeys.STATUS, "PERMISSIONS_UPDATED");
        result.putString("decision", decision);
        result.putInt("permissionCount", count);
        return result;
    }

    private static VirtualLocationProfileSnapshot locationFromExtras(
            VirtualLocationProfileSnapshot current, Bundle extras) {
        double latitude = number(extras, "latitude", 31.2304d);
        double longitude = number(extras, "longitude", 121.4737d);
        double altitude = number(extras, "altitude", 4d);
        float accuracy = (float) number(extras, "accuracy", 5f);
        float speed = (float) number(extras, "speed", 0f);
        float bearing = (float) number(extras, "bearing", 0f);
        long now = System.currentTimeMillis();
        return new VirtualLocationProfileSnapshot(
                VirtualLocationProfileSnapshot.MODE_STATIC, "gps", true, latitude, longitude,
                altitude, accuracy, speed, bearing, now, System.nanoTime(), 1000L,
                true, 8, 5, "$GPGGA,000000.00,3113.824,N,12128.422,E,1,08,0.9,4.0,M,0.0,M,,*00");
    }

    private static double number(Bundle extras, String key, double fallback) {
        Object value = extras == null ? null : extras.get(key);
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof String text) {
            try { return Double.parseDouble(text); } catch (NumberFormatException ignored) { }
        }
        return fallback;
    }

    private VirtualCameraProfileSnapshot cameraFromExtras(
            VirtualCameraProfileSnapshot current, String packageName, int virtualUserId,
            Bundle extras) throws Exception {
        VirtualCameraSourceSnapshot source = current.source();
        String sourceUri = extras.getString("sourceUri", "");
        if (!sourceUri.trim().isEmpty()) {
            source = VirtualCameraMediaStore.importSource(this, packageName, virtualUserId,
                    Uri.parse(sourceUri), extras.getString("sourceKind", ""));
        }
        boolean configured = source != null && source.isConfigured();
        boolean enabled = extras.containsKey("cameraEnabled")
                ? extras.getBoolean("cameraEnabled") : configured;
        if (!enabled && sourceUri.trim().isEmpty()) return current;
        return new VirtualCameraProfileSnapshot(VirtualLocationProfileSnapshot.MODE_STATIC,
                enabled, enabled, false, enabled ? 1 : 0,
                enabled ? java.util.List.of("0") : java.util.List.of(),
                enabled ? java.util.List.of("0") : java.util.List.of(), java.util.List.of(),
                source == null ? VirtualCameraSourceSnapshot.none() : source,
                enabled && configured);
    }

    /**
     * Debug-only management command: the caller must explicitly opt in with
     * trustNativeGuest=true. The persisted install session still requires
     * USER_ACTION_REQUIRED, and Runtime keeps enforcing the stored trust state.
     */
    private static SandboxRecord trustedNativeImport(PackageServiceClient packages,
                                                      String packageName, File source)
            throws Exception {
        int sessionId = -1;
        try {
            InstallSessionInfoSnapshot session = packages.createInstallSession(
                    InstallSessionParamsSnapshot.trustedNativeFullInstall(packageName));
            sessionId = session.sessionId();
            packages.addInstallArtifact(sessionId, Uri.parse(source.toURI().toString()));
            SandboxRecord record = packages.commitInstallSession(sessionId);
            sessionId = -1;
            if (!InstallSessionParamsSnapshot.NATIVE_GUEST_TRUST_EXPLICITLY_TRUSTED.equals(
                    record.nativeGuestTrust)) {
                throw new IllegalStateException("DEBUG_NATIVE_TRUST_NOT_RECORDED");
            }
            return record;
        } catch (Exception error) {
            if (sessionId != -1) {
                try {
                    packages.abandonInstallSession(sessionId);
                } catch (Exception abandonFailure) {
                    error.addSuppressed(abandonFailure);
                }
            }
            throw error;
        }
    }

    private static void requireStatus(String operation, Bundle bundle, String... accepted) {
        String status = bundle == null ? "" : bundle.getString(RuntimeKeys.STATUS, "");
        for (String candidate : accepted) if (candidate.equals(status)) return;
        String errorType = bundle == null ? "MISSING_RESULT" : bundle.getString(RuntimeKeys.ERROR_TYPE, "");
        String errorMessage = bundle == null ? "No result Bundle" : bundle.getString(RuntimeKeys.ERROR_MESSAGE, "");
        throw new IllegalStateException(operation + " failed: status=" + status
                + ", errorType=" + errorType + ", errorMessage=" + errorMessage);
    }

    private JSONObject bundleJson(Bundle bundle) throws Exception {
        JSONObject out = new JSONObject();
        out.put("status", bundle.getString(RuntimeKeys.STATUS, ""));
        out.put("errorType", bundle.getString(RuntimeKeys.ERROR_TYPE, ""));
        out.put("errorMessage", bundle.getString(RuntimeKeys.ERROR_MESSAGE, ""));
        out.put("sessionId", bundle.getString(RuntimeKeys.SESSION_ID, ""));
        out.put("generation", bundle.getLong(RuntimeKeys.GENERATION, 0));
        out.put("processSlot", bundle.getInt(RuntimeKeys.PROCESS_SLOT, -1));
        copyIfPresent(bundle, out, "profileClass");
        copyIfPresent(bundle, out, "targetReason");
        copyIfPresent(bundle, out, "targetSupported");
        copyIfPresent(bundle, out, "dingtalkCompatibilityEnabled");
        copyIfPresent(bundle, out, "locationMode");
        copyIfPresent(bundle, out, "latitude");
        copyIfPresent(bundle, out, "longitude");
        copyIfPresent(bundle, out, "cameraAvailable");
        copyIfPresent(bundle, out, "captureSubstitution");
        copyIfPresent(bundle, out, "cameraSourceKind");
        copyIfPresent(bundle, out, "cameraSourceSha256");
        copyIfPresent(bundle, out, "isolatedProcess");
        copyIfPresent(bundle, out, "isolatedPlatformPid");
        copyIfPresent(bundle, out, "isolatedPlatformUid");
        copyIfPresent(bundle, out, "processName");
        copyIfPresent(bundle, out, "componentClass");
        copyIfPresent(bundle, out, "startId");
        copyIfPresent(bundle, out, "created");
        return out;
    }

    private static void copyIfPresent(Bundle source, JSONObject target, String key) throws Exception {
        if (source != null && source.containsKey(key)) target.put(key, source.get(key));
    }


    private static JSONObject companionJson(NativeCompanionResult value) throws Exception {
        JSONObject out = new JSONObject();
        out.put("successful", value.successful());
        out.put("operation", value.operation());
        out.put("requestedAbi", value.requestedAbi());
        out.put("processBitness", value.processBitness());
        out.put("acceptedGeneration", value.acceptedGeneration());
        out.put("nativeStatus", value.nativeStatus());
        out.put("errorType", value.errorType());
        out.put("errorMessage", value.errorMessage());
        return out;
    }

    private void writeResult(JSONObject result) {
        try (FileOutputStream output = new FileOutputStream(new File(getFilesDir(), "debug-command-result.json"))) {
            output.write(result.toString(2).getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        } catch (Exception error) {
            Log.e(TAG, "Cannot write command result: " + error);
        }
    }
}
