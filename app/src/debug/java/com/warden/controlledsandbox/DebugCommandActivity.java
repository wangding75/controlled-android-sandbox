package com.warden.controlledsandbox;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.util.Log;
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
        String command = getIntent().getStringExtra("command");
        String packageName = getIntent().getStringExtra("package");
        int virtualUserId = getIntent().getIntExtra("user", 0);
        worker.execute(() -> execute(command == null ? "" : command, packageName == null ? "" : packageName, virtualUserId));
    }

    private void execute(String command, String packageName, int virtualUserId) {
        JSONObject result = new JSONObject();
        RuntimeClient runtime = null;
        PackageServiceClient packages = null;
        try {
            result.put("command", command).put("package", packageName).put("virtualUserId", virtualUserId).put("startedAt", System.currentTimeMillis());
            if (packageName.trim().isEmpty()) throw new IllegalArgumentException("package extra is required");
            packages = new PackageServiceClient(this);
            SandboxRecord record = packages.findRecord(packageName);
            boolean importRequested = "import-launch".equals(command)
                    || "import-prepare".equals(command) || record == null;
            if (importRequested) {
                ApplicationInfo installed = getPackageManager().getApplicationInfo(packageName, 0);
                record = packages.importApkFile(new File(installed.sourceDir));
            }
            packages.ensureInstance(packageName, virtualUserId);
            runtime = new RuntimeClient(this);
            Bundle operation;
            if ("import-launch".equals(command) || "launch".equals(command)) {
                operation = runtime.launch(record, virtualUserId);
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
            Log.e(TAG, "FAIL " + command + " " + packageName + " " + error);
        } finally {
            if (runtime != null) runtime.close();
            if (packages != null) packages.close();
            writeResult(result);
            runOnUiThread(() -> { finish(); worker.shutdown(); });
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
