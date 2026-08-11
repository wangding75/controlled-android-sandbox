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
        boolean trustNativeGuest = extras != null && extras.getBoolean("trustNativeGuest", false);
        Log.i(TAG, "COMMAND_BEGIN command=" + command + " package=" + packageName
                + " user=" + virtualUserId);
        worker.execute(() -> execute(command == null ? "" : command,
                packageName == null ? "" : packageName, virtualUserId, trustNativeGuest));
    }

    private void execute(String command, String packageName, int virtualUserId,
                         boolean trustNativeGuest) {
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
            } else if ("import-launch".equals(command) || "launch".equals(command)) {
                operation = runtime.launch(record, virtualUserId);
                requireStatus("launch", operation, "LAUNCH_REQUESTED");
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
            } else if ("prepare".equals(command) || "import-prepare".equals(command)) {
                operation = runtime.prepare(record, virtualUserId);
                requireStatus("prepare", operation, "PREPARED", "ALREADY_PREPARED");
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
            writeResult(result);
            runOnUiThread(() -> { finish(); worker.shutdown(); });
        }
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
        return out;
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
