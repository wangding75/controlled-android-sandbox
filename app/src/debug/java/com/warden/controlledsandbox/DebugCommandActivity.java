package com.warden.controlledsandbox;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import com.warden.controlledsandbox.contract.InstallSessionInfoSnapshot;
import com.warden.controlledsandbox.contract.InstallSessionParamsSnapshot;
import com.warden.controlledsandbox.contract.NativeCompanionResult;
import com.warden.controlledsandbox.contract.PackageAppOpSnapshot;
import com.warden.controlledsandbox.contract.PermissionAuditSnapshot;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
import com.warden.controlledsandbox.contract.VirtualPermissionSnapshot;
import com.warden.controlledsandbox.contract.VirtualCameraProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualCameraSourceSnapshot;
import com.warden.controlledsandbox.contract.VirtualDeviceServiceProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPeripheralServicesProfileSnapshot;
import com.warden.controlledsandbox.compatibility.dingtalk.DingTalkCompatibilityManager;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import com.warden.controlledsandbox.sdk.CasSandboxEngine;
import com.warden.controlledsandbox.sdk.SandboxCatalog;
import com.warden.controlledsandbox.sdk.SandboxEngineObserver;
import com.warden.controlledsandbox.sdk.SandboxInstance;
import com.warden.controlledsandbox.sdk.SandboxOperationResult;
import com.warden.controlledsandbox.domain.migration.SxInstanceProfileMigrator;
import com.warden.controlledsandbox.domain.migration.SxLegacyConfigDocument;
import com.warden.controlledsandbox.domain.migration.SxMigrationRecord;
import java.util.LinkedHashMap;
import java.util.Map;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import org.json.JSONObject;
import org.json.JSONArray;

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
        String requestId = intent == null ? "" : intent.getStringExtra("requestId");
        int virtualUserId = intent == null ? 0 : intent.getIntExtra("user", 0);
        Bundle extras = intent == null ? null : intent.getExtras();
        Bundle commandExtras = extras == null ? new Bundle() : new Bundle(extras);
        boolean trustNativeGuest = extras != null && extras.getBoolean("trustNativeGuest", false);
        Log.i(TAG, "COMMAND_BEGIN command=" + command + " package=" + packageName
                + " user=" + virtualUserId);
        worker.execute(() -> execute(command == null ? "" : command,
                packageName == null ? "" : packageName, virtualUserId, trustNativeGuest,
                commandExtras, requestId == null ? "" : requestId));
    }

    private void execute(String command, String packageName, int virtualUserId,
                         boolean trustNativeGuest, Bundle extras, String requestId) {
        requestId = requestId == null || requestId.trim().isEmpty()
                ? UUID.randomUUID().toString() : requestId.trim();
        String operationId = requestId + "-launch";
        JSONObject result = new JSONObject();
        RuntimeClient runtime = null;
        PackageServiceClient packages = null;
        try {
            result.put("command", command).put("package", packageName)
                    .put("virtualUserId", virtualUserId).put("trustNativeGuest", trustNativeGuest)
                    .put("requestId", requestId)
                    .put("operationId", operationId)
                    .put("startedAt", System.currentTimeMillis());
            if ("native-hostile".equals(command) || "c3-t04-hostile".equals(command)) {
                JSONObject campaign = HostileProductionCampaign.run(this);
                result.put("nativeHostile", campaign);
                result.put("operation", new JSONObject().put("status",
                        campaign.optBoolean("pocValid", false)
                                ? "NATIVE_HOSTILE_RAN" : "NATIVE_HOSTILE_INVALID"));
                if (!campaign.optBoolean("pocValid", false)) {
                    throw new IllegalStateException("ISOLATED_UID_POC_INVALID:"
                            + campaign.optString("error", "uid not distinct"));
                }
                if ("c3-t04-hostile".equals(command)
                        && !campaign.optBoolean("c3t04Pass", false)) {
                    throw new IllegalStateException("C3_T04_ATTACK_MATRIX_FAILED:"
                            + campaign.optJSONObject("residual"));
                }
                result.put("status", "PASS");
                Log.i(TAG, "PASS native-hostile isolatedUid="
                        + campaign.optJSONObject("isolated"));
                return;
            }
            if ("pi-system-holder-cancel".equals(command)) {
                int cancelled = cancelSystemHolderNotifications();
                result.put("cancelled", cancelled);
                result.put("operation", new JSONObject().put("status",
                        cancelled > 0 ? "CANCELLED" : "NONE"));
                result.put("status", "PASS");
                Log.i(TAG, "PASS pi-system-holder-cancel cancelled=" + cancelled);
                return;
            }
            if ("native-enforcement".equals(command)) {
                // Host debug isolated process. Do not touch guest Activity/Service runtime
                // (KI-R03-NATIVE-010). No production Broker/policy path.
                JSONObject campaign = NativeEnforcementCampaign.run(this);
                result.put("nativeEnforcement", campaign);
                result.put("operation", new JSONObject().put("status",
                        campaign.optBoolean("pocValid", false)
                                ? "NATIVE_ENFORCEMENT_RAN" : "NATIVE_ENFORCEMENT_INVALID"));
                if (!campaign.optBoolean("pocValid", false)) {
                    throw new IllegalStateException("ISOLATED_UID_POC_INVALID:"
                            + campaign.optString("error", "uid not distinct"));
                }
                result.put("status", "PASS");
                Log.i(TAG, "PASS native-enforcement isolatedUid="
                        + campaign.optJSONObject("isolated"));
                return;
            }
            if ("c4-t05-sx-business".equals(command)) {
                if (packageName.trim().isEmpty()) {
                    throw new IllegalArgumentException("package extra is required");
                }
                String trust = trustNativeGuest
                        ? InstallSessionParamsSnapshot.NATIVE_GUEST_TRUST_EXPLICITLY_TRUSTED
                        : InstallSessionParamsSnapshot.NATIVE_GUEST_TRUST_UNTRUSTED;
                int loops = Math.max(0, Math.min(100, extras.getInt("loops", 100)));
                boolean skipSurfaces = extras.getBoolean("skipSurfaces", false);
                boolean skipLoops = extras.getBoolean("skipLoops", false);
                JSONObject campaign = runC4T05SxBusiness(
                        this, packageName, trust, loops, skipSurfaces, skipLoops);
                result.put("c4t05", campaign);
                result.put("operation", new JSONObject().put("status",
                        campaign.optBoolean("pass", false)
                                ? "C4_T05_SX_BUSINESS_PASS" : "C4_T05_SX_BUSINESS_FAIL"));
                if (!campaign.optBoolean("pass", false)) {
                    throw new IllegalStateException("C4_T05_SX_BUSINESS_FAILED:"
                            + campaign.optString("error", "sx business failed"));
                }
                result.put("status", "PASS");
                Log.i(TAG, "PASS c4-t05-sx-business package=" + packageName);
                return;
            }
            if ("c4-t05-dingtalk".equals(command)) {
                JSONObject campaign = runC4T05DingTalk(this, trustNativeGuest);
                result.put("c4t05DingTalk", campaign);
                result.put("operation", new JSONObject().put("status",
                        campaign.optBoolean("pass", false)
                                ? "C4_T05_DINGTALK_PASS" : "C4_T05_DINGTALK_FAIL"));
                if (!campaign.optBoolean("pass", false)) {
                    throw new IllegalStateException("C4_T05_DINGTALK_FAILED:"
                            + campaign.optString("error", "dingtalk failed"));
                }
                result.put("status", "PASS");
                Log.i(TAG, "PASS c4-t05-dingtalk");
                return;
            }
            if ("c4-r02-concurrent-add".equals(command)) {
                if (packageName.trim().isEmpty()) {
                    throw new IllegalArgumentException("package extra is required");
                }
                JSONObject concurrent = runConcurrentPackageAdds(packageName, virtualUserId,
                        trustNativeGuest, requestId);
                result.put("concurrentAdds", concurrent);
                result.put("operation", new JSONObject().put("status",
                        concurrent.optBoolean("pass", false)
                                ? "CONCURRENT_ADD_SINGLE_FLIGHT_PASS"
                                : "CONCURRENT_ADD_SINGLE_FLIGHT_FAIL"));
                if (!concurrent.optBoolean("pass", false)) {
                    throw new IllegalStateException("CONCURRENT_ADD_SINGLE_FLIGHT_FAILED");
                }
                result.put("status", "PASS");
                return;
            }
            if ("c4-t03-migrate".equals(command)) {
                if (packageName.trim().isEmpty()) {
                    throw new IllegalArgumentException("package extra is required");
                }
                String trust = trustNativeGuest
                        ? InstallSessionParamsSnapshot.NATIVE_GUEST_TRUST_EXPLICITLY_TRUSTED
                        : InstallSessionParamsSnapshot.NATIVE_GUEST_TRUST_UNTRUSTED;
                JSONObject campaign = runC4T03Migrate(this, packageName, trust);
                result.put("c4t03", campaign);
                result.put("operation", new JSONObject().put("status",
                        campaign.optBoolean("pass", false)
                                ? "C4_T03_MIGRATE_PASS" : "C4_T03_MIGRATE_FAIL"));
                if (!campaign.optBoolean("pass", false)) {
                    throw new IllegalStateException("C4_T03_MIGRATE_FAILED:"
                            + campaign.optString("error", "migration smoke failed"));
                }
                result.put("status", "PASS");
                Log.i(TAG, "PASS c4-t03-migrate package=" + packageName);
                return;
            }
            if ("c4-t02-engine".equals(command)) {
                if (packageName.trim().isEmpty()) {
                    throw new IllegalArgumentException("package extra is required");
                }
                String trust = trustNativeGuest
                        ? InstallSessionParamsSnapshot.NATIVE_GUEST_TRUST_EXPLICITLY_TRUSTED
                        : InstallSessionParamsSnapshot.NATIVE_GUEST_TRUST_UNTRUSTED;
                JSONObject campaign = runC4T02Engine(this, packageName, trust);
                result.put("c4t02", campaign);
                result.put("operation", new JSONObject().put("status",
                        campaign.optBoolean("pass", false)
                                ? "C4_T02_ENGINE_PASS" : "C4_T02_ENGINE_FAIL"));
                if (!campaign.optBoolean("pass", false)) {
                    throw new IllegalStateException("C4_T02_ENGINE_FAILED:"
                            + campaign.optString("error", "engine smoke failed"));
                }
                result.put("status", "PASS");
                Log.i(TAG, "PASS c4-t02-engine package=" + packageName);
                return;
            }
            if ("lifecycle-clone".equals(command)
                    || "lifecycle-reset-identity".equals(command)
                    || "lifecycle-rollback".equals(command)
                    || "lifecycle-status".equals(command)) {
                if (packageName.trim().isEmpty()) {
                    throw new IllegalArgumentException("package extra is required");
                }
                packages = new PackageServiceClient(this);
                Bundle lifecycleOp = new Bundle();
                if ("lifecycle-clone".equals(command)) {
                    int cloneUser = packages.createClone(packageName);
                    lifecycleOp.putString(RuntimeKeys.STATUS, "CLONED");
                    lifecycleOp.putInt(RuntimeKeys.VIRTUAL_USER_ID, cloneUser);
                } else if ("lifecycle-reset-identity".equals(command)) {
                    lifecycleOp.putString(RuntimeKeys.STATUS, "IDENTITY_RESET");
                    lifecycleOp.putString("transaction", packages.resetIdentity(packageName));
                } else if ("lifecycle-rollback".equals(command)) {
                    lifecycleOp.putString(RuntimeKeys.STATUS, "ROLLED_BACK");
                    lifecycleOp.putString("transaction", packages.rollbackPackage(packageName));
                } else {
                    lifecycleOp.putString(RuntimeKeys.STATUS, "LIFECYCLE");
                    lifecycleOp.putString("transaction", packages.lifecycleTransaction(packageName));
                }
                result.put("operation", bundleJson(lifecycleOp));
                result.put("status", "PASS");
                Log.i(TAG, "PASS " + command + " " + packageName);
                return;
            }
            if (packageName.trim().isEmpty()) throw new IllegalArgumentException("package extra is required");
            // Keep the command's foreground owner edge to Runtime Broker alive before package
            // lookup.  A running large Guest must remain attached to the NBB/VA-style virtual
            // ProcessRecord owner while lookup/import resolves the next operation; creating the
            // RuntimeClient only after lookup leaves a hot Guest exposed to MuMu background LMK.
            if (!"import-only".equals(command)) {
                runtime = new RuntimeClient(this);
                runtime.primeOperationOwner(requestId, operationId);
            }
            Log.i(TAG, "PACKAGE_LOOKUP_BEGIN command=" + command + " package=" + packageName);
            packages = new PackageServiceClient(this);
            SandboxRecord record = packages.findRecord(packageName);
            Log.i(TAG, "PACKAGE_LOOKUP_RETURN command=" + command + " package=" + packageName);
            // Destructive/data lifecycle operations target the authoritative virtual record. Do
            // not re-import the currently installed host APK before clear/delete/stop: the host
            // may already be uninstalled or may be an older physical revision while the virtual
            // record is intentionally being retired.
            boolean virtualLifecycleOnly = "stop".equals(command)
                    || "clear".equals(command) || "delete".equals(command)
                    || "launch-virtual-component".equals(command);
            boolean importRequested = !virtualLifecycleOnly && ("import-launch".equals(command)
                    || "import-prepare".equals(command) || "import-only".equals(command)
                    || record == null
                    || installedApkRevisionChanged(record, packageName));
            boolean instanceEnsuredByImport = false;
            if (importRequested) {
                // Resolve the complete host-installed artifact set.  A package with a dynamic
                // feature or ABI/resource split must enter the same multi-artifact pipeline as
                // the foreground import flow; importing only sourceDir silently publishes a
                // base-only revision and makes split classes invisible at runtime.
                PackageImportResult imported = packages.importInstalledApplicationAndEnsure(
                        requestId, packageName,
                        trustNativeGuest
                                ? InstallSessionParamsSnapshot.NATIVE_GUEST_TRUST_EXPLICITLY_TRUSTED
                                : InstallSessionParamsSnapshot.NATIVE_GUEST_TRUST_UNTRUSTED,
                        virtualUserId);
                record = imported.record();
                instanceEnsuredByImport = true;
                if (!imported.operationTraceJson().isEmpty()) {
                    result.put("packageOperationTrace",
                            new JSONObject(imported.operationTraceJson()));
                }
            }
            result.put("nativeGuestTrust", record.nativeGuestTrust);
            if (!instanceEnsuredByImport) packages.ensureInstance(packageName, virtualUserId);
            Log.i(TAG, "INSTANCE_READY command=" + command + " package=" + packageName
                    + " user=" + virtualUserId);
            if ("import-only".equals(command)) {
                result.put("operation", new JSONObject().put(RuntimeKeys.STATUS, "IMPORTED"));
                result.put("status", "PASS");
                Log.i(TAG, "PASS import-only " + packageName + " user=" + virtualUserId);
                return;
            }
            if (runtime == null) runtime = new RuntimeClient(this);
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
                PackageDeleteResult deleted = packages.deleteInstanceWithOperation(
                        requestId, packageName, virtualUserId);
                if (!deleted.operationTraceJson().isEmpty()) {
                    result.put("packageOperationTrace",
                            new JSONObject(deleted.operationTraceJson()));
                }
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
            } else if ("set-appops".equals(command)) {
                operation = setAppOps(packages, packageName, virtualUserId, extras);
                requireStatus("appops", operation, "APPOPS_UPDATED");
            } else if ("policy-state".equals(command)) {
                operation = policyState(packages, packageName, virtualUserId);
                requireStatus("policy-state", operation, "POLICY_STATE");
            } else if ("configure-location".equals(command)
                    || "configure-camera".equals(command)
                    || "dingtalk-profile".equals(command)) {
                boolean dingtalk = "dingtalk-profile".equals(command);
                operation = configureProfiles(packages, record, packageName, virtualUserId,
                        extras, dingtalk);
                requireStatus("profile", operation, "PROFILE_CONFIGURED");
            } else if ("launch-component".equals(command)
                    || "launch-virtual-component".equals(command)) {
                String component = extras.getString("component", "").trim();
                if (component.isEmpty()) {
                    throw new IllegalArgumentException("component extra is required");
                }
                // Framework probes are non-visual Activities.  Keep the launch owner alive
                // through the generic onCreate boundary, then let the probe markers establish
                // its framework semantics; a first-frame gate is not applicable here.
                operation = runtime.launchComponentAwaitingActivityCreated(record, virtualUserId,
                        component, componentIntentExtras(extras));
                requireStatus("launch-component", operation, "LAUNCH_PASS");
            } else if ("c3-t02-file-proc-network-fd".equals(command)) {
                Bundle probeExtras = new Bundle();
                probeExtras.putString("cas.native.context", "IN_SANDBOX");
                operation = runtime.launchComponent(record, virtualUserId,
                        "com.warden.controlledsandbox.fixture.C3T02FileProcNetworkFdActivity",
                        probeExtras);
                result.put("c3T02", bundleJson(operation));
                requireStatus("c3-t02-file-proc-network-fd", operation, "LAUNCH_PASS");
            } else if ("c3-t03-native-media".equals(command)) {
                Bundle probeExtras = new Bundle();
                probeExtras.putString("cas.native.context", "IN_SANDBOX");
                operation = runtime.launchComponent(record, virtualUserId,
                        "com.warden.controlledsandbox.fixture.C3T03NativeMediaActivity",
                        probeExtras);
                result.put("c3T03", bundleJson(operation));
                requireStatus("c3-t03-native-media", operation, "LAUNCH_PASS");
            } else if ("broadcast-campaign".equals(command)) {
                int iterations = Math.max(1, Math.min(100,
                        extras.getInt("iterations", 1)));
                operation = runBroadcastCampaign(runtime, record, virtualUserId, iterations);
                result.put("broadcastCampaign", bundleJson(operation));
                requireStatus("broadcast-campaign", operation, "BROADCAST_CAMPAIGN_LAUNCHED");
            } else if ("provider-campaign".equals(command)) {
                int iterations = Math.max(1, Math.min(1000,
                        extras.getInt("iterations", 1)));
                int pressureSeconds = Math.max(0, Math.min(86_400,
                        extras.getInt("pressureSeconds", 0)));
                operation = runProviderCampaign(runtime, record, virtualUserId,
                        iterations, pressureSeconds, 0L);
                result.put("providerCampaign", bundleJson(operation));
                requireStatus("provider-campaign", operation, "PROVIDER_CAMPAIGN_PASS");
            } else if ("provider-concurrent-campaign".equals(command)) {
                int iterations = Math.max(1, Math.min(1000,
                        extras.getInt("iterations", 1)));
                int pressureSeconds = Math.max(0, Math.min(86_400,
                        extras.getInt("pressureSeconds", 0)));
                operation = runProviderConcurrentCampaign(packages, record, packageName, runtime,
                        virtualUserId, iterations, pressureSeconds);
                result.put("providerCampaign", bundleJson(operation));
                requireStatus("provider-concurrent-campaign", operation,
                        "PROVIDER_CAMPAIGN_PASS");
            } else if ("import-launch".equals(command) || "launch".equals(command)) {
                operation = runtime.launch(record, virtualUserId, requestId, operationId);
                // Product launch() deliberately returns once the runtime and Host ActivityStarter
                // have accepted the request.  The debug command is an evidence collector, so it
                // explicitly follows the independent readiness observation until the terminal
                // LAUNCH_PASS/LAUNCH_FAILED result is published.
                if ("LAUNCH_ACCEPTED".equals(operation.getString(RuntimeKeys.STATUS, ""))) {
                    operation = awaitLaunchReadiness(runtime, operation);
                }
                requireStatus("launch", operation, "LAUNCH_PASS");
            } else if ("package-state-campaign".equals(command)) {
                operation = packageStateCampaign(packages, record, packageName, virtualUserId);
                requireStatus("package-state-campaign", operation, "PACKAGE_STATE_PASS");
            } else if ("install-session-failure".equals(command)) {
                operation = installSessionFailureCampaign(this, packages, record, packageName);
                requireStatus("install-session-failure", operation, "INSTALL_FAILURE_ROLLED_BACK");
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
            } else if ("service-lifecycle-suite".equals(command)) {
                String component = extras.getString("serviceComponent", record.serviceClass).trim();
                if (component.isEmpty()) throw new IllegalArgumentException(
                        "serviceComponent extra is required");
                String processName = extras.getString("serviceProcess", record.serviceProcess);
                SandboxRecord serviceRecord = record.withServiceComponent(component, processName);
                operation = runtime.prepare(serviceRecord, virtualUserId);
                requireStatus("service-lifecycle-prepare", operation,
                        "PREPARED", "ALREADY_PREPARED", "PREPARED_DEGRADED",
                        "ALREADY_PREPARED_DEGRADED");
                int iterations = Math.max(1, Math.min(100,
                        extras.getInt("iterations", 1)));
                JSONArray cycles = new JSONArray();
                for (int iteration = 1; iteration <= iterations; iteration++) {
                    Bundle firstStart = runtime.startService(serviceRecord, virtualUserId,
                            component, processName, "C1-T02_START");
                    requireStatus("service-lifecycle-first-start", firstStart, "SERVICE_STARTED");
                    int firstStartId = awaitServiceStartId(runtime, serviceRecord, virtualUserId);
                    Bundle secondStart = runtime.startService(serviceRecord, virtualUserId,
                            component, processName, "C1-T02_START");
                    requireStatus("service-lifecycle-second-start", secondStart, "SERVICE_STARTED");
                    int secondStartId = awaitServiceStartId(runtime, serviceRecord, virtualUserId);
                    if (firstStartId < 1 || secondStartId <= firstStartId) {
                        throw new IllegalStateException("SERVICE_START_ID_NOT_MONOTONIC:first="
                                + firstStartId + ":second=" + secondStartId);
                    }
                    Bundle staleStop = runtime.stopServiceStartId(serviceRecord, virtualUserId, firstStartId);
                    if (staleStop.getBoolean(RuntimeKeys.SERVICE_STOPPED_BY_START_ID, false)
                            || staleStop.getInt(RuntimeKeys.SERVICE_LAST_START_ID, -1) != secondStartId
                            || staleStop.getInt(RuntimeKeys.SERVICE_START_COUNT, -1) < 2) {
                        throw new IllegalStateException("STALE_SERVICE_START_ID_STOPPED_NEWER_START");
                    }

                    RuntimeClient.BoundServiceLease firstLease = null;
                    RuntimeClient.BoundServiceLease secondLease = null;
                    boolean firstBinder = false;
                    boolean secondBinder = false;
                    try {
                        firstLease = runtime.bindService(serviceRecord, virtualUserId,
                                "c1-t02-" + iteration + "-a");
                        secondLease = runtime.bindService(serviceRecord, virtualUserId,
                                "c1-t02-" + iteration + "-b");
                        firstBinder = firstLease.binder() != null;
                        secondBinder = secondLease.binder() != null;
                        if (!firstBinder || !secondBinder) {
                            throw new IllegalStateException("SERVICE_BINDER_MISSING");
                        }
                    } finally {
                        if (secondLease != null) secondLease.close();
                        if (firstLease != null) firstLease.close();
                    }

                    Bundle foregroundStart = runtime.startForegroundService(serviceRecord, virtualUserId,
                            true, "C1-T02", 0, 5_000L);
                    requireStatus("service-lifecycle-foreground-start", foregroundStart,
                            "SERVICE_STARTED", "SERVICE_RECOVERED");
                    Bundle promoted = awaitServiceSnapshot(runtime, serviceRecord, virtualUserId);
                    if (!promoted.getBoolean(RuntimeKeys.SERVICE_FOREGROUND, false)
                            || !promoted.getBoolean(RuntimeKeys.SERVICE_FOREGROUND_REQUESTED, false)) {
                        throw new IllegalStateException("SERVICE_FOREGROUND_PROMOTION_NOT_RECORDED");
                    }
                    Bundle demoteStart = runtime.startService(serviceRecord, virtualUserId,
                            component, processName, "C1-T02_DEMOTE");
                    requireStatus("service-lifecycle-foreground-demote", demoteStart,
                            "SERVICE_STARTED");
                    Bundle demoted = awaitServiceSnapshot(runtime, serviceRecord, virtualUserId);
                    if (demoted.getBoolean(RuntimeKeys.SERVICE_FOREGROUND, true)
                            || demoted.getBoolean(RuntimeKeys.SERVICE_FOREGROUND_REQUESTED, true)) {
                        throw new IllegalStateException("SERVICE_FOREGROUND_DEMOTION_NOT_RECORDED");
                    }
                    Bundle stopped = runtime.stopService(serviceRecord, virtualUserId);
                    if (stopped.getInt(RuntimeKeys.SERVICE_START_COUNT, -1) != 0
                            || stopped.getInt(RuntimeKeys.SERVICE_CONNECTION_COUNT, -1) != 0
                            || stopped.getBoolean(RuntimeKeys.SERVICE_FOREGROUND, true)) {
                        throw new IllegalStateException("SERVICE_OWNERSHIP_DID_NOT_CONVERGE");
                    }
                    JSONObject cycle = new JSONObject();
                    cycle.put("iteration", iteration);
                    cycle.put("firstStartId", firstStartId);
                    cycle.put("secondStartId", secondStartId);
                    cycle.put("staleStartIdPreserved", true);
                    cycle.put("firstBinder", firstBinder);
                    cycle.put("secondBinder", secondBinder);
                    cycle.put("foregroundPromoted", promoted.getBoolean(
                            RuntimeKeys.SERVICE_FOREGROUND, false));
                    cycle.put("foregroundDemoted", !demoted.getBoolean(
                            RuntimeKeys.SERVICE_FOREGROUND, true));
                    cycle.put("stoppedState", stopped.getString(RuntimeKeys.SERVICE_STATE, ""));
                    cycles.put(cycle);
                }
                result.put("serviceLifecycleIterations", iterations);
                result.put("serviceLifecycleCycles", cycles);
                operation = new Bundle();
                operation.putString(RuntimeKeys.STATUS, "SERVICE_LIFECYCLE_PASS");
            } else if ("isolated-service".equals(command)) {
                String component = extras.getString("component", "").trim();
                if (component.isEmpty()) {
                    throw new IllegalArgumentException("component extra is required");
                }
                String processName = extras.getString("processName", "").trim();
                String serviceOperation = extras.getString("serviceOperation", "start").trim();
                int slotPad = extras.getInt("slotPadCount", extras.getInt("slotPad", 0));
                int slotTarget = extras.getInt("slotTarget", -1);
                if ("stop".equalsIgnoreCase(serviceOperation)) {
                    operation = runtime.stopService(record, virtualUserId, component, processName);
                    requireStatus("isolated-service-stop", operation,
                            "SERVICE_STOPPED", "SERVICE_NOT_RUNNING", "SERVICE_STOP_REQUESTED");
                } else if ("start".equalsIgnoreCase(serviceOperation)) {
                    operation = runtime.startService(record, virtualUserId, component, processName,
                            slotPad, slotTarget);
                    requireStatus("isolated-service-start", operation,
                            "SERVICE_STARTED", "SERVICE_RECOVERED");
                } else {
                    throw new IllegalArgumentException("serviceOperation must be start or stop");
                }
                result.put("isolatedComponent", component);
                result.put("isolatedServiceOperation", serviceOperation);
            } else if ("native-adversarial".equals(command)) {
                operation = runtime.prepare(record, virtualUserId);
                requireStatus("prepare", operation, "PREPARED", "ALREADY_PREPARED");
                String component = extras.getString("component",
                        "com.warden.controlledsandbox.fixture.NativeAdversarialProbeService")
                        .trim();
                Bundle started = runtime.startService(record, virtualUserId, component, "");
                requireStatus("native-adversarial-service", started,
                        "SERVICE_STARTED", "SERVICE_RECOVERED");
                result.put("nativeAdversarial", bundleJson(started));
                try {
                    Thread.sleep(12_000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("NATIVE_ADV_WAIT_INTERRUPTED", interrupted);
                }
                operation = started;
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
            } else if ("slot-campaign".equals(command)) {
                String processName = extras.getString("processName", "").trim();
                int slotPad = extras.getInt("slotPadCount", extras.getInt("slotPad", 0));
                int slotTarget = extras.getInt("slotTarget", -1);
                operation = runtime.prepare(record, virtualUserId, processName, slotPad, slotTarget);
                requireStatus("slot-campaign-prepare", operation, "PREPARED", "ALREADY_PREPARED",
                        "ALREADY_PREPARED_DEGRADED");
                String serviceComponent = extras.getString("component",
                        "com.warden.controlledsandbox.fixture.NativeAdversarialProbeService").trim();
                if (extras.getBoolean("startService", true)) {
                    Bundle started = runtime.startService(record, virtualUserId, serviceComponent,
                            processName);
                    requireStatus("slot-campaign-service", started,
                            "SERVICE_STARTED", "SERVICE_RECOVERED");
                    result.put("service", bundleJson(started));
                }
                result.put("slotPadCount", slotPad);
                result.put("slotTarget", slotTarget);
                result.put("processName", processName);
            } else if ("fault-probe".equals(command)) {
                String mode = extras.getString("mode", "").trim().toLowerCase(java.util.Locale.ROOT);
                operation = runtime.prepare(record, virtualUserId);
                requireStatus("fault-prepare", operation, "PREPARED", "ALREADY_PREPARED",
                        "ALREADY_PREPARED_DEGRADED");
                result.put("generationBefore", operation.getLong(RuntimeKeys.GENERATION, -1L));
                result.put("slotBefore", operation.getInt(RuntimeKeys.PROCESS_SLOT, -1));
                if ("java".equals(mode) || "uncaught".equals(mode)) {
                    operation = runtime.launchComponent(record, virtualUserId,
                            "com.warden.controlledsandbox.fixture.FaultJavaCrashActivity");
                } else if ("main".equals(mode)) {
                    operation = runtime.launchComponent(record, virtualUserId,
                            "com.warden.controlledsandbox.fixture.FaultMainThreadCrashActivity");
                } else if ("service".equals(mode)) {
                    operation = runtime.startService(record, virtualUserId,
                            "com.warden.controlledsandbox.fixture.FaultCrashService",
                            extras.getString("processName", "").trim());
                } else if ("anr-activity".equals(mode)) {
                    operation = runtime.launchComponent(record, virtualUserId,
                            "com.warden.controlledsandbox.fixture.FaultAnrActivity");
                } else if ("anr-service".equals(mode)) {
                    operation = runtime.startService(record, virtualUserId,
                            "com.warden.controlledsandbox.fixture.FaultAnrService", "");
                } else if ("anr-provider".equals(mode)) {
                    runtime.prepareProvider(record, virtualUserId);
                    operation = runtime.queryProvider(record, virtualUserId,
                            extras.getString("component",
                                    "com.warden.controlledsandbox.fixture.FixtureProvider"),
                            extras.getString("authority", record.providerAuthority));
                } else if ("native-segv".equals(mode) || "segv".equals(mode)) {
                    operation = runtime.launchComponent(record, virtualUserId,
                            "com.warden.controlledsandbox.fixture.FaultNativeCrashActivity");
                } else if ("native-abort".equals(mode) || "abort".equals(mode)) {
                    operation = runtime.launchComponent(record, virtualUserId,
                            "com.warden.controlledsandbox.fixture.FaultNativeAbortActivity");
                } else if ("isolated-native-segv".equals(mode) || "isolated-native-abort".equals(mode)) {
                    operation = runtime.startService(record, virtualUserId,
                            "com.warden.controlledsandbox.fixture.IsolatedFaultNativeService",
                            extras.getString("processName",
                                    "com.warden.controlledsandbox.fixture:fault_iso_native").trim());
                } else {
                    throw new IllegalArgumentException("Unsupported fault mode: " + mode);
                }
                result.put("faultMode", mode);
                result.put("faultOperation", bundleJson(operation));
            } else if ("pi-system-holder".equals(command)) {
                operation = runtime.prepare(record, virtualUserId);
                requireStatus("pi-system-holder-prepare", operation, "PREPARED", "ALREADY_PREPARED",
                        "ALREADY_PREPARED_DEGRADED");
                operation = runtime.launchComponent(record, virtualUserId,
                        extras.getString("component",
                                "com.warden.controlledsandbox.fixture.SystemHolderPendingIntentActivity")
                                .trim());
                requireStatus("pi-system-holder-launch", operation, "LAUNCH_PASS");
            } else if ("neighbor-service".equals(command)) {
                runtime.prepare(record, virtualUserId);
                operation = runtime.startService(record, virtualUserId);
                requireStatus("neighbor-service", operation, "SERVICE_STARTED", "SERVICE_RECOVERED");
            } else if ("neighbor-provider".equals(command)) {
                runtime.prepare(record, virtualUserId);
                // record.providerClass resolves to the FaultAnrProvider ANR fixture; exercise the
                // real data provider explicitly so the smoke does not stall the guest on an ANR.
                String providerComponent = "com.warden.controlledsandbox.fixture.FixtureProvider";
                String providerAuthority = "com.warden.controlledsandbox.fixture.provider";
                Bundle providerPrepare = runtime.prepareProvider(record, virtualUserId,
                        providerComponent, providerAuthority);
                requireStatus("neighbor-provider-prepare", providerPrepare,
                        "PROVIDER_READY", "PROVIDER_ALREADY_READY", "PROVIDER_AUTHORITY_ATTACHED");
                Bundle providerQuery = runtime.queryProviderSmoke(record, virtualUserId,
                        providerComponent, providerAuthority);
                if ("FAILED".equals(providerQuery.getString(RuntimeKeys.STATUS, ""))) {
                    requireStatus("neighbor-provider-query", providerQuery, "OK");
                }
                result.put("providerPrepare", bundleJson(providerPrepare));
                result.put("providerQuery", bundleJson(providerQuery));
                operation = providerQuery;
            } else if ("stale-session".equals(command)) {
                String staleSessionId = extras.getString("staleSessionId", "").trim();
                long staleGeneration = extras.getLong("staleGeneration", -1L);
                if (staleSessionId.isEmpty() || staleGeneration < 1) {
                    throw new IllegalArgumentException("staleSessionId and staleGeneration are required");
                }
                Bundle probe = runtime.staleSessionProbe(record, staleSessionId, staleGeneration,
                        "android.permission.VIBRATE", 1000);
                result.put("staleSessionProbe", bundleJson(probe));
                operation = probe;
                if (probe.getBoolean("accepted", false)) {
                    throw new IllegalStateException("STALE_SESSION_ACCEPTED:"
                            + probe.getString(RuntimeKeys.ERROR_MESSAGE, ""));
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

    private static int awaitServiceStartId(RuntimeClient runtime, SandboxRecord record,
                                            int virtualUserId) throws Exception {
        return awaitServiceSnapshot(runtime, record, virtualUserId)
                .getInt(RuntimeKeys.SERVICE_LAST_START_ID, -1);
    }

    private static Bundle awaitServiceSnapshot(RuntimeClient runtime, SandboxRecord record,
                                               int virtualUserId) throws Exception {
        for (int attempt = 0; attempt < 30; attempt++) {
            // MAX_VALUE can never equal the live startId. The Broker still returns its snapshot,
            // which gives this debug-only verifier the callback-assigned id without mutating the
            // started ownership.
            Bundle snapshot = runtime.stopServiceStartId(record, virtualUserId, Integer.MAX_VALUE);
            int lastStartId = snapshot.getInt(RuntimeKeys.SERVICE_LAST_START_ID, -1);
            if (lastStartId > 0 && snapshot.containsKey(RuntimeKeys.SERVICE_START_COUNT)) {
                return snapshot;
            }
            Thread.sleep(100L);
        }
        throw new IllegalStateException("SERVICE_START_CALLBACK_TIMEOUT");
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

    private static Bundle setAppOps(PackageServiceClient packages, String packageName,
                                    int virtualUserId, Bundle extras) throws Exception {
        String appOps = extras.getString("appOps", "");
        String mode = extras.getString("mode", "").trim().toUpperCase(java.util.Locale.ROOT);
        if (appOps.trim().isEmpty()) throw new IllegalArgumentException("appOps extra is required");
        if (!java.util.Set.of("DEFAULT", "ALLOWED", "IGNORED", "ERRORED").contains(mode)) {
            throw new IllegalArgumentException("mode must be DEFAULT, ALLOWED, IGNORED or ERRORED");
        }
        int count = 0;
        for (String appOp : appOps.split(",")) {
            String normalized = appOp.trim();
            if (normalized.isEmpty()) continue;
            packages.setAppOpMode(packageName, virtualUserId, normalized, mode);
            count++;
        }
        if (count == 0) throw new IllegalArgumentException("appOps extra contains no values");
        Bundle result = new Bundle();
        result.putString(RuntimeKeys.STATUS, "APPOPS_UPDATED");
        result.putString("mode", mode);
        result.putInt("appOpCount", count);
        return result;
    }

    private static Bundle policyState(PackageServiceClient packages, String packageName,
                                      int virtualUserId) throws Exception {
        VirtualPackageStateSnapshot state = packages.virtualPackageState(packageName, virtualUserId);
        PermissionAuditSnapshot latestCameraAppOpReset = null;
        for (PermissionAuditSnapshot audit : packages.permissionAudit(packageName, virtualUserId, 256)) {
            if ("android:camera".equals(audit.permission())
                    && "RESET_APP_OP".equals(audit.action())) {
                latestCameraAppOpReset = audit;
                break;
            }
        }
        Bundle result = new Bundle();
        result.putString(RuntimeKeys.STATUS, "POLICY_STATE");
        result.putInt(RuntimeKeys.VIRTUAL_USER_ID, virtualUserId);
        result.putString("cameraPermission", permissionDecision(state, "android.permission.CAMERA"));
        result.putString("internetPermission", permissionDecision(state, "android.permission.INTERNET"));
        result.putString("cameraAppOp", appOpMode(state, "android:camera"));
        result.putString("recordAudioAppOp", appOpMode(state, "android:record_audio"));
        result.putInt("permissionCount", state.permissions().size());
        result.putInt("appOpCount", state.appOps().size());
        result.putInt("splitCount", state.splitNames().size());
        // cameraAppOp is the runtime-effective projection. When the virtual CAMERA permission
        // is DEFAULT (and therefore not granted), the projection may be IGNORED even though the
        // raw AppOps policy has been removed. Expose the reset audit separately so a data-clear
        // check can prove raw policy convergence without changing the runtime semantics.
        result.putString("cameraAppOpPolicy", latestCameraAppOpReset == null
                ? "UNOBSERVED" : latestCameraAppOpReset.outcome());
        result.putString("cameraAppOpResetReason", latestCameraAppOpReset == null
                ? "" : latestCameraAppOpReset.reason());
        result.putLong("cameraAppOpResetSequence", latestCameraAppOpReset == null
                ? 0L : latestCameraAppOpReset.sequence());
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
        String mode = text(extras, "mode", VirtualLocationProfileSnapshot.MODE_STATIC);
        String provider = text(extras, "provider", "gps");
        boolean providerEnabled = bool(extras, "providerEnabled", true);
        long minimumUpdateIntervalMs = whole(extras, "minimumUpdateIntervalMs", 1000L);
        boolean gnssEnabled = bool(extras, "gnssEnabled", true);
        int satellitesInView = (int) whole(extras, "satellitesInView", 8L);
        int satellitesUsedInFix = (int) whole(extras, "satellitesUsedInFix", 5L);
        String nmeaSentence = text(extras, "nmeaSentence",
                "$GPGGA,000000.00,3113.824,N,12128.422,E,1,08,0.9,4.0,M,0.0,M,,*00");
        long now = whole(extras, "timeMs", System.currentTimeMillis());
        long elapsed = whole(extras, "elapsedRealtimeNanos", System.nanoTime());
        return new VirtualLocationProfileSnapshot(
                mode, provider, providerEnabled, latitude, longitude, altitude, accuracy, speed,
                bearing, now, elapsed, minimumUpdateIntervalMs, gnssEnabled, satellitesInView,
                satellitesUsedInFix, nmeaSentence);
    }

    private static double number(Bundle extras, String key, double fallback) {
        Object value = extras == null ? null : extras.get(key);
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof String text) {
            try { return Double.parseDouble(text); } catch (NumberFormatException ignored) { }
        }
        return fallback;
    }

    private static long whole(Bundle extras, String key, long fallback) {
        double value = number(extras, key, fallback);
        return value < 0d || value > Long.MAX_VALUE ? fallback : (long) value;
    }

    private static boolean bool(Bundle extras, String key, boolean fallback) {
        Object value = extras == null ? null : extras.get(key);
        if (value instanceof Boolean flag) return flag;
        if (value instanceof String text) return Boolean.parseBoolean(text);
        return fallback;
    }

    private static String text(Bundle extras, String key, String fallback) {
        String value = extras == null ? null : extras.getString(key, null);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private VirtualCameraProfileSnapshot cameraFromExtras(
            VirtualCameraProfileSnapshot current, String packageName, int virtualUserId,
            Bundle extras) throws Exception {
        VirtualCameraSourceSnapshot source = current.source();
        String sourceUri = extras.getString("sourceUri", "");
        if (sourceUri.trim().isEmpty() && bool(extras, "generateCameraSource", false)) {
            sourceUri = generateCameraSource().toURI().toString();
        }
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

    private static Bundle componentIntentExtras(Bundle extras) {
        Bundle result = new Bundle();
        String mode = text(extras, "componentMode", "");
        if (!mode.isEmpty()) result.putString("c2t04Mode", mode);
        String c2t05Mode = text(extras, "c2t05Mode", "");
        if (!c2t05Mode.isEmpty()) result.putString("c2t05Mode", c2t05Mode);
        if (extras.containsKey("c2t05Loops")) {
            result.putInt("c2t05Loops", Math.max(1, extras.getInt("c2t05Loops", 20)));
        }
        String c2t06Mode = text(extras, "c2t06Mode", "");
        if (!c2t06Mode.isEmpty()) result.putString("c2t06Mode", c2t06Mode);
        if (extras.containsKey("c2t06Loops")) {
            result.putInt("c2t06Loops", Math.max(1, extras.getInt("c2t06Loops", 20)));
        }
        String c2t07Mode = text(extras, "c2t07Mode", "");
        if (!c2t07Mode.isEmpty()) result.putString("c2t07Mode", c2t07Mode);
        if (extras.containsKey("c2t07Loops")) {
            result.putInt("c2t07Loops", Math.max(1, extras.getInt("c2t07Loops", 10)));
        }
        if (extras.containsKey("c2t07User")) {
            result.putInt("c2t07User", Math.max(0, extras.getInt("c2t07User", 0)));
        }
        if (extras.containsKey("cameraLoops")) {
            result.putInt("c2t04Loops", Math.max(1, extras.getInt("cameraLoops", 100)));
        }
        if (extras.containsKey("cameraPressureSeconds")) {
            result.putInt("c2t04PressureSeconds",
                    Math.max(1, extras.getInt("cameraPressureSeconds", 1800)));
        }
        if (extras.containsKey("cameraRecoveryDelayMs")) {
            result.putLong("c2t04RecoveryDelayMs",
                    Math.max(0L, extras.getLong("cameraRecoveryDelayMs", 500L)));
        }
        return result;
    }

    private File generateCameraSource() throws Exception {
        File output = new File(getFilesDir(), "c2-t04-camera-source.png");
        final int width = 320;
        final int height = 240;
        byte[] raw = new byte[height * (1 + width * 4)];
        int offset = 0;
        for (int row = 0; row < height; row++) {
            raw[offset++] = 0;
            for (int column = 0; column < width; column++) {
                int tileRow = row / 40;
                int tileColumn = column / 40;
                raw[offset++] = (byte) (20 + tileRow * 22);
                raw[offset++] = (byte) (70 + tileColumn * 12);
                raw[offset++] = (byte) (120 + ((tileRow + tileColumn) * 9) % 100);
                raw[offset++] = (byte) 255;
            }
        }
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream stream = new DeflaterOutputStream(compressed,
                new Deflater(6))) {
            stream.write(raw);
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.write(new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10});
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        try (DataOutputStream stream = new DataOutputStream(header)) {
            stream.writeInt(width);
            stream.writeInt(height);
            stream.writeByte(8);
            stream.writeByte(6);
            stream.writeByte(0);
            stream.writeByte(0);
            stream.writeByte(0);
        }
        pngChunk(png, "IHDR", header.toByteArray());
        pngChunk(png, "IDAT", compressed.toByteArray());
        pngChunk(png, "IEND", new byte[0]);
        try (FileOutputStream stream = new FileOutputStream(output)) {
            stream.write(png.toByteArray());
        }
        return output;
    }

    private static void pngChunk(ByteArrayOutputStream output, String type, byte[] data)
            throws Exception {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        DataOutputStream stream = new DataOutputStream(output);
        stream.writeInt(data.length);
        stream.write(name);
        stream.write(data);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        stream.writeInt((int) crc.getValue());
        stream.flush();
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

    private boolean installedApkRevisionChanged(SandboxRecord record, String packageName) {
        if (record == null || packageName == null || packageName.trim().isEmpty()) return false;
        try {
            ApplicationInfo installed = getPackageManager().getApplicationInfo(packageName, 0);
            File source = new File(installed.sourceDir);
            if (!source.isFile()) return false;
            String digest = sha256Hex(source);
            return digest != null && !digest.equalsIgnoreCase(record.sha256);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String sha256Hex(File file) {
        try (FileInputStream input = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) > 0) digest.update(buffer, 0, read);
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) hex.append(String.format("%02x", value));
            return hex.toString();
        } catch (Exception error) {
            return null;
        }
    }

    private int cancelSystemHolderNotifications() throws Exception {
        Object manager = getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return 0;
        Object raw = manager.getClass().getMethod("getActiveNotifications").invoke(manager);
        if (!(raw instanceof Object[] active)) return 0;
        int cancelled = 0;
        for (Object sbn : active) {
            if (sbn == null) continue;
            Object notification = sbn.getClass().getMethod("getNotification").invoke(sbn);
            String haystack = String.valueOf(notification);
            try {
                haystack += String.valueOf(notification.getClass().getMethod("getChannelId").invoke(notification));
            } catch (Exception ignored) { }
            try {
                Object extras = notification.getClass().getField("extras").get(notification);
                haystack += String.valueOf(extras.getClass()
                        .getMethod("getCharSequence", String.class)
                        .invoke(extras, "android.title"));
            } catch (Exception ignored) { }
            if (!haystack.contains("system.holder") && !haystack.contains("system-holder")
                    && !haystack.contains("CAS system")) {
                continue;
            }
            String tag = (String) sbn.getClass().getMethod("getTag").invoke(sbn);
            int id = (Integer) sbn.getClass().getMethod("getId").invoke(sbn);
            if (tag == null || tag.isEmpty()) {
                manager.getClass().getMethod("cancel", int.class).invoke(manager, id);
            } else {
                manager.getClass().getMethod("cancel", String.class, int.class).invoke(manager, tag, id);
            }
            cancelled++;
            Log.i(TAG, "SYSTEM_HOLDER_NOTIFICATION_CANCEL tag=" + tag + " id=" + id);
        }
        return cancelled;
    }

    private static Bundle runBroadcastCampaign(RuntimeClient runtime, SandboxRecord record,
                                               int virtualUserId, int iterations) throws Exception {
        Bundle out = new Bundle();
        out.putString(RuntimeKeys.STATUS, "BROADCAST_CAMPAIGN_LAUNCHED");
        out.putInt("iterations", iterations);
        out.putInt(RuntimeKeys.VIRTUAL_USER_ID, virtualUserId);
        for (int iteration = 1; iteration <= iterations; iteration++) {
            try {
                int baselineActivityCount = runtime.status().snapshot().activityCount();
                Bundle launch = runtime.launchComponent(record, virtualUserId,
                        "com.warden.controlledsandbox.fixture.BroadcastCampaignActivity",
                        null, true);
                requireStatus("broadcast-campaign-launch-" + iteration, launch, "LAUNCH_PASS");
                if (!runtime.awaitActivityCountAtMost(baselineActivityCount, 30_000L)) {
                    throw new IllegalStateException("BROADCAST_ACTIVITY_FINISH_TIMEOUT");
                }
            } finally {
                runtime.stop(record, virtualUserId);
            }
        }
        return out;
    }

    private Bundle runProviderConcurrentCampaign(PackageServiceClient packages,
                                                 SandboxRecord record, String packageName,
                                                 RuntimeClient firstRuntime, int firstUser,
                                                 int iterations, int pressureSeconds) throws Exception {
        int secondUser = firstUser == 0 ? 1 : 0;
        packages.ensureInstance(packageName, secondUser);
        // The campaign observes and grants the exported compat32 Provider. Prepare that peer in
        // both virtual users before launching the concurrent Guest Activities; otherwise the
        // first user can race the peer's lazy instance creation and fail closed as "instance does
        // not exist".
        String peerPackage = "com.warden.controlledsandbox.fixture32";
        packages.ensureInstance(peerPackage, firstUser);
        packages.ensureInstance(peerPackage, secondUser);
        try (RuntimeClient secondRuntime = new RuntimeClient(this)) {
            long startedAt = System.currentTimeMillis();
            long deadline = pressureSeconds > 0
                    ? startedAt + pressureSeconds * 1000L : Long.MIN_VALUE;
            int cycles = 0;
            boolean firstActive = false;
            boolean secondActive = false;
            ExecutorService firstCycleLaunchers = Executors.newFixedThreadPool(2);
            try {
                    while (cycles < iterations
                        || (pressureSeconds > 0 && System.currentTimeMillis() < deadline)) {
                    cycles++;
                    if (!firstActive) {
                        int cycle = cycles;
                        Future<Bundle> firstLaunch = firstCycleLaunchers.submit(() ->
                                launchProviderWithRecovery(firstRuntime, record, firstUser,
                                        "provider-concurrent-launch-" + firstUser + "-" + cycle));
                        Future<Bundle> secondLaunch = firstCycleLaunchers.submit(() ->
                                launchProviderWithRecovery(secondRuntime, record, secondUser,
                                        "provider-concurrent-launch-" + secondUser + "-" + cycle));
                        Bundle firstResult = firstLaunch.get();
                        firstActive = true;
                        Bundle secondResult = secondLaunch.get();
                        secondActive = true;
                    } else {
                        // Keep the other user's successful window visible while each generation
                        // is fenced and relaunched. This is a state-based foreground precondition
                        // for API32, not a timing retry or a bypass of the platform gate.
                        firstRuntime.stop(record, firstUser);
                        firstActive = false;
                        Thread.sleep(250L);
                        launchProviderWithRecovery(firstRuntime, record, firstUser,
                                "provider-concurrent-launch-" + firstUser + "-" + cycles);
                        firstActive = true;

                        secondRuntime.stop(record, secondUser);
                        secondActive = false;
                        Thread.sleep(250L);
                        launchProviderWithRecovery(secondRuntime, record, secondUser,
                                "provider-concurrent-launch-" + secondUser + "-" + cycles);
                        secondActive = true;
                    }
                    // The Guest Activity closes its Provider resources before reporting PASS and
                    // remains visible until Host stop. Allow observer callbacks and cursor leases
                    // to settle before the next generation fence.
                    Thread.sleep(15_000L);
                }
            } finally {
                firstCycleLaunchers.shutdownNow();
                if (firstActive) firstRuntime.stop(record, firstUser);
                if (secondActive) secondRuntime.stop(record, secondUser);
            }
            Log.i(TAG, "C1_T04_PROVIDER_USER_PASS user=" + firstUser + " cycles=" + cycles);
            Log.i(TAG, "C1_T04_PROVIDER_USER_PASS user=" + secondUser + " cycles=" + cycles);
            Bundle out = new Bundle();
            out.putString(RuntimeKeys.STATUS, "PROVIDER_CAMPAIGN_PASS");
            out.putInt("firstUser", firstUser);
            out.putInt("secondUser", secondUser);
            out.putInt("firstCycles", cycles);
            out.putInt("secondCycles", cycles);
            out.putInt("cycles", cycles * 2);
            out.putInt("pressureSeconds", pressureSeconds);
            out.putLong("elapsedMs", System.currentTimeMillis() - startedAt);
            return out;
        }
    }

    private Bundle runProviderCampaign(RuntimeClient runtime, SandboxRecord record,
                                       int virtualUserId, int iterations,
                                       int pressureSeconds, long interCycleStopDelayMs) throws Exception {
        long startedAt = System.currentTimeMillis();
        long deadline = pressureSeconds > 0
                ? startedAt + pressureSeconds * 1000L : Long.MIN_VALUE;
        int cycles = 0;
        while (cycles < iterations || (pressureSeconds > 0 && System.currentTimeMillis() < deadline)) {
            cycles++;
            boolean moreCycles;
            try {
                launchProviderWithRecovery(runtime, record, virtualUserId,
                        "provider-campaign-launch-" + virtualUserId + "-" + cycles);
                // The Guest Activity closes its own Provider resources before reporting PASS and
                // remains visible until Host stop. Keep the generation alive long enough for
                // observer callbacks and cursor leases to settle.
                Thread.sleep(15_000L);
            } finally {
                moreCycles = cycles < iterations
                        || (pressureSeconds > 0 && System.currentTimeMillis() < deadline);
                if (moreCycles && interCycleStopDelayMs > 0) {
                    // The peer Guest Activity intentionally remains visible after PASS. Delay
                    // this user's stop so the peer remains a legitimate foreground anchor while
                    // this generation is fenced and the next one is launched.
                    Thread.sleep(interCycleStopDelayMs);
                }
                runtime.stop(record, virtualUserId);
            }
            Thread.sleep(250L);
        }
        Bundle out = new Bundle();
        out.putString(RuntimeKeys.STATUS, "PROVIDER_CAMPAIGN_PASS");
        out.putInt(RuntimeKeys.VIRTUAL_USER_ID, virtualUserId);
        out.putInt("cycles", cycles);
        out.putLong("elapsedMs", System.currentTimeMillis() - startedAt);
        return out;
    }

    private static Bundle launchProviderWithRecovery(RuntimeClient runtime, SandboxRecord record,
                                                     int virtualUserId, String operation)
            throws Exception {
        try {
            Bundle launch = runtime.launchComponent(record, virtualUserId,
                    "com.warden.controlledsandbox.fixture.ProviderCampaignActivity");
            requireStatus(operation, launch, "LAUNCH_PASS");
            return launch;
        } catch (Exception error) {
            String detail = String.valueOf(error);
            if (!detail.contains("GUEST_NOT_PREPARED")) throw error;
            Bundle prepared = runtime.prepare(record, virtualUserId);
            requireStatus(operation + "-prepare-recovery", prepared,
                    "PREPARED", "ALREADY_PREPARED");
            Bundle retry = runtime.launchComponent(record, virtualUserId,
                    "com.warden.controlledsandbox.fixture.ProviderCampaignActivity");
            requireStatus(operation + "-retry", retry, "LAUNCH_PASS");
            Log.i(TAG, "C1_T04_PROVIDER_PREPARE_RECOVERY user=" + virtualUserId);
            return retry;
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

    /**
     * Debug-only bridge from product launch acceptance to the independent first-frame observer.
     * This wait is intentionally outside RuntimeClient.launch()'s product critical path.
     */
    private static Bundle awaitLaunchReadiness(RuntimeClient runtime, Bundle accepted)
            throws Exception {
        if (runtime == null || accepted == null) {
            throw new IllegalArgumentException("runtime and accepted launch result are required");
        }
        final long deadline = android.os.SystemClock.elapsedRealtime() + 35_000L;
        Bundle observed = accepted;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            observed = runtime.observeLaunch(accepted);
            String status = observed.getString(RuntimeKeys.STATUS, "");
            if (!"LAUNCH_PENDING".equals(status)) return observed;
            Thread.sleep(100L);
        }
        throw new IllegalStateException("LAUNCH_OBSERVATION_TIMEOUT");
    }

    private static Bundle packageStateCampaign(PackageServiceClient packages, SandboxRecord record,
                                                String packageName, int virtualUserId)
            throws Exception {
        int isolatedUser = virtualUserId == 0 ? 1 : 0;
        packages.ensureInstance(packageName, isolatedUser);
        packages.resetVirtualPolicy(packageName, virtualUserId);
        packages.resetVirtualPolicy(packageName, isolatedUser);
        VirtualPackageStateSnapshot before = packages.virtualPackageState(packageName, virtualUserId);
        VirtualPackageStateSnapshot otherBefore = packages.virtualPackageState(packageName, isolatedUser);
        VirtualPackageStateSnapshot changed = packages.setPermissionDecision(packageName,
                virtualUserId, "android.permission.INTERNET", "DENIED");
        changed = packages.setAppOpMode(packageName, virtualUserId, "android:camera", "IGNORED");
        VirtualPackageStateSnapshot other = packages.virtualPackageState(packageName, isolatedUser);
        String changedPermission = permissionDecision(changed, "android.permission.INTERNET");
        String changedAppOp = appOpMode(changed, "android:camera");
        String otherPermission = permissionDecision(other, "android.permission.INTERNET");
        String otherAppOp = appOpMode(other, "android:camera");
        if (!"DENIED".equals(changedPermission) || !"IGNORED".equals(changedAppOp)) {
            throw new IllegalStateException("PACKAGE_POLICY_MUTATION_NOT_VISIBLE");
        }
        if (!permissionDecision(otherBefore, "android.permission.INTERNET").equals(otherPermission)
                || !appOpMode(otherBefore, "android:camera").equals(otherAppOp)) {
            throw new IllegalStateException("PACKAGE_POLICY_CROSSED_VIRTUAL_USER");
        }
        packages.resetVirtualPolicy(packageName, virtualUserId);
        packages.resetVirtualPolicy(packageName, isolatedUser);
        Bundle operation = new Bundle();
        operation.putString(RuntimeKeys.STATUS, "PACKAGE_STATE_PASS");
        operation.putString("recordRevision", record.sha256);
        operation.putLong("recordVersionCode", record.versionCode);
        operation.putInt("artifactCount", record.artifacts.size());
        operation.putInt("splitCount", before.splitNames().size());
        operation.putInt("queryCount", before.queries().size());
        operation.putInt("componentCount", before.components().size());
        operation.putString("changedPermission", changedPermission);
        operation.putString("changedAppOp", changedAppOp);
        operation.putString("otherUserPermission", otherPermission);
        operation.putString("otherUserAppOp", otherAppOp);
        operation.putInt("virtualUserId", virtualUserId);
        operation.putInt("isolatedVirtualUserId", isolatedUser);
        return operation;
    }

    private static Bundle installSessionFailureCampaign(Context context,
                                                         PackageServiceClient packages,
                                                         SandboxRecord before, String packageName)
            throws Exception {
        int sessionId = packages.createInstallSession(packageName + ".intentionalmismatch");
        boolean commitFailed = false;
        String failure = "";
        try {
            File hostApk = new File(context.getApplicationInfo().sourceDir);
            packages.addInstallArtifact(sessionId, Uri.parse(hostApk.toURI().toString()));
            packages.commitInstallSession(sessionId);
        } catch (Exception expected) {
            commitFailed = true;
            failure = String.valueOf(expected.getMessage());
        }
        if (!commitFailed) throw new IllegalStateException("INSTALL_FAILURE_NOT_REJECTED");
        InstallSessionInfoSnapshot failed = packages.installSessionInfo(sessionId);
        if (!InstallSessionInfoSnapshot.STATE_FAILED.equals(failed.state())) {
            throw new IllegalStateException("INSTALL_FAILURE_STATE_NOT_PERSISTED:" + failed.state());
        }
        InstallSessionInfoSnapshot reopened = packages.retryInstallSession(sessionId);
        if (!InstallSessionInfoSnapshot.STATE_OPEN.equals(reopened.state())) {
            throw new IllegalStateException("INSTALL_FAILURE_RETRY_NOT_OPEN:" + reopened.state());
        }
        packages.abandonInstallSession(sessionId);
        SandboxRecord after = packages.findRecord(packageName);
        if (before == null || after == null || !before.sha256.equals(after.sha256)
                || before.versionCode != after.versionCode) {
            throw new IllegalStateException("INSTALL_FAILURE_MUTATED_PACKAGE_STATE");
        }
        Bundle operation = new Bundle();
        operation.putString(RuntimeKeys.STATUS, "INSTALL_FAILURE_ROLLED_BACK");
        operation.putInt("sessionId", sessionId);
        operation.putString("failedState", failed.state());
        operation.putString("failureCode", failed.failureCode());
        operation.putString("failureMessage", failed.failureMessage());
        operation.putString("failureThrown", failure);
        operation.putString("revisionAfter", after.sha256);
        return operation;
    }

    private static String permissionDecision(VirtualPackageStateSnapshot state, String name) {
        for (VirtualPermissionSnapshot permission : state.permissions()) {
            if (name.equals(permission.name())) return permission.decision();
        }
        return "MISSING";
    }

    private static String appOpMode(VirtualPackageStateSnapshot state, String name) {
        for (PackageAppOpSnapshot appOp : state.appOps()) {
            if (name.equals(appOp.opName())) return appOp.mode();
        }
        return "MISSING";
    }

    private JSONObject bundleJson(Bundle bundle) throws Exception {
        JSONObject out = new JSONObject();
        out.put("status", bundle.getString(RuntimeKeys.STATUS, ""));
        out.put("errorType", bundle.getString(RuntimeKeys.ERROR_TYPE, ""));
        out.put("errorMessage", bundle.getString(RuntimeKeys.ERROR_MESSAGE, ""));
        out.put("sessionId", bundle.getString(RuntimeKeys.SESSION_ID, ""));
        out.put("generation", bundle.getLong(RuntimeKeys.GENERATION, 0));
        out.put("processSlot", bundle.getInt(RuntimeKeys.PROCESS_SLOT, -1));
        out.put("platformPid", bundle.getInt(RuntimeKeys.PLATFORM_PID, bundle.getInt("pid", 0)));
        copyIfPresent(bundle, out, "pid");
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
        copyIfPresent(bundle, out, RuntimeKeys.REQUEST_ID);
        copyIfPresent(bundle, out, RuntimeKeys.OPERATION_ID);
        copyIfPresent(bundle, out, RuntimeKeys.ATTEMPT);
        copyIfPresent(bundle, out, RuntimeKeys.RETRY_BUDGET);
        copyIfPresent(bundle, out, RuntimeKeys.AUTOMATIC_RETRY_PERFORMED);
        copyIfPresent(bundle, out, "activityCreated");
        copyIfPresent(bundle, out, "activityResumed");
        copyIfPresent(bundle, out, "windowEvidence");
        copyIfPresent(bundle, out, "firstFrameDrawn");
        copyIfPresent(bundle, out, "launchReadinessElapsedMs");
        copyIfPresent(bundle, out, RuntimeKeys.LAUNCH_ACCEPTED_AT_ELAPSED_MS);
        copyIfPresent(bundle, out, "launchTimeline");
        copyIfPresent(bundle, out, "startId");
        copyIfPresent(bundle, out, "created");
        copyIfPresent(bundle, out, "accepted");
        copyIfPresent(bundle, out, "cycles");
        copyIfPresent(bundle, out, "firstUser");
        copyIfPresent(bundle, out, "secondUser");
        copyIfPresent(bundle, out, "firstCycles");
        copyIfPresent(bundle, out, "secondCycles");
        copyIfPresent(bundle, out, "pressureSeconds");
        copyIfPresent(bundle, out, "elapsedMs");
        copyIfPresent(bundle, out, "recordRevision");
        copyIfPresent(bundle, out, "recordVersionCode");
        copyIfPresent(bundle, out, "artifactCount");
        copyIfPresent(bundle, out, "splitCount");
        copyIfPresent(bundle, out, "queryCount");
        copyIfPresent(bundle, out, "componentCount");
        copyIfPresent(bundle, out, "changedPermission");
        copyIfPresent(bundle, out, "changedAppOp");
        copyIfPresent(bundle, out, "otherUserPermission");
        copyIfPresent(bundle, out, "otherUserAppOp");
        copyIfPresent(bundle, out, "cameraPermission");
        copyIfPresent(bundle, out, "internetPermission");
        copyIfPresent(bundle, out, "cameraAppOp");
        copyIfPresent(bundle, out, "recordAudioAppOp");
        copyIfPresent(bundle, out, "permissionCount");
        copyIfPresent(bundle, out, "appOpCount");
        copyIfPresent(bundle, out, "cameraAppOpPolicy");
        copyIfPresent(bundle, out, "cameraAppOpResetReason");
        copyIfPresent(bundle, out, "cameraAppOpResetSequence");
        copyIfPresent(bundle, out, "isolatedVirtualUserId");
        copyIfPresent(bundle, out, "failedState");
        copyIfPresent(bundle, out, "failureCode");
        copyIfPresent(bundle, out, "failureMessage");
        copyIfPresent(bundle, out, "failureThrown");
        copyIfPresent(bundle, out, "revisionAfter");
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

    private JSONObject runC4T05SxBusiness(Context context, String packageName, String trust,
                                          int loops, boolean skipSurfaces, boolean skipLoops)
            throws Exception {
        JSONObject campaign = new JSONObject();
        try (SxSandboxAdapter adapter = new SxSandboxAdapter(context);
             PackageServiceClient packages = new PackageServiceClient(context);
             RuntimeClient runtime = new RuntimeClient(context)) {
            CasSandboxEngine engine = new CasSandboxEngine(adapter);
            engine.killAll();
            SxMigrationHostStore store = new SxMigrationHostStore(context, packages);
            Map<String, String> applied = Map.of();
            if (!skipSurfaces) {
                for (SandboxInstance existing : engine.listInstalled()) {
                    if (packageName.equals(existing.packageName())) {
                        engine.uninstall(existing.packageName(), existing.virtualUserId());
                    }
                }
                requireEngine(new JSONArray(), "c4-t05-import",
                        engine.installFromHost(packageName, trust));
                SxInstanceProfileMigrator migrator = new SxInstanceProfileMigrator(store);
                byte[] cameraPng = java.nio.file.Files.readAllBytes(generateCameraSource().toPath());
                SxLegacyConfigDocument f1f5 = sxFixture(packageName, 0, "31.230400", "121.473700",
                        "02:00:00:00:00:10", "0123456789abcdef", "SX-F1F5", cameraPng);
                SxMigrationRecord previous = store.read(packageName, 0);
                if (previous != null) {
                    store.write(previous.withStatus(SxMigrationRecord.FAILED, Map.of(), "",
                            previous.mediaPath, true));
                }
                SxMigrationRecord migrated = migrator.migrate(f1f5);
                if (!SxMigrationRecord.COMMITTED.equals(migrated.status)) {
                    throw new IllegalStateException("F1F5_MIGRATE_FAILED:" + migrated.status);
                }
                applied = store.readApplied(packageName, 0);
                if (applied.getOrDefault("location.lat", "").isEmpty()
                        || applied.getOrDefault("device.androidId", "").isEmpty()
                        || applied.getOrDefault("network.ssid", "").isEmpty()
                        || applied.getOrDefault("bluetooth.name", "").isEmpty()
                        || applied.getOrDefault("camera.sha256", "").isEmpty()) {
                    throw new IllegalStateException("F1F5_PROFILE_INCOMPLETE:" + applied);
                }
            } else if (adapter.findRecord(packageName) == null) {
                requireEngine(new JSONArray(), "c4-t05-import",
                        engine.installFromHost(packageName, trust));
            }
            SandboxRecord record = adapter.findRecord(packageName);
            if (record == null) throw new IllegalStateException("FIXTURE_RECORD_MISSING");
            packages.setPermissionDecision(packageName, 0, "android.permission.CAMERA", "GRANTED");
            packages.setPermissionDecision(packageName, 0,
                    "android.permission.ACCESS_FINE_LOCATION", "GRANTED");
            packages.setPermissionDecision(packageName, 0,
                    "android.permission.ACCESS_COARSE_LOCATION", "GRANTED");
            packages.setAppOpMode(packageName, 0, "android:camera", "ALLOWED");
            packages.setAppOpMode(packageName, 0, "android:fine_location", "ALLOWED");
            packages.setAppOpMode(packageName, 0, "android:coarse_location", "ALLOWED");
            JSONArray components = new JSONArray();
            Bundle provider;
            if (!skipSurfaces) {
                requireEngine(new JSONArray(), "c4-t05-prepare", engine.launch(packageName, 0));
                // Package-neutral fixture first, then F1 camera, F2 location, F4 network,
                // F5 bluetooth, F3 device. ConfigProvider is the migrated instance store.
                Bundle cameraExtras = new Bundle();
                cameraExtras.putString("c2t04Mode", "smoke");
                launchSurface(runtime, record, components,
                        "com.warden.controlledsandbox.fixture.CameraCampaignActivity",
                        cameraExtras, 30_000L);
                launchSurface(runtime, record, components,
                        "com.warden.controlledsandbox.fixture.LocationCampaignActivity",
                        null, 8_000L);
                Bundle networkExtras = new Bundle();
                networkExtras.putString("c2t06Mode", "full");
                networkExtras.putInt("c2t06Loops", 1);
                launchSurface(runtime, record, components,
                        "com.warden.controlledsandbox.fixture.C2T06DeviceNetworkMediaActivity",
                        networkExtras, 12_000L);
                launchSurface(runtime, record, components,
                        "com.warden.controlledsandbox.fixture.RemoteActivity",
                        null, 2_000L);
                Bundle schedulingExtras = new Bundle();
                schedulingExtras.putString("c2t05Mode", "full");
                schedulingExtras.putInt("c2t05Loops", 1);
                launchSurface(runtime, record, components,
                        "com.warden.controlledsandbox.fixture.C2T05SchedulingInteractionActivity",
                        schedulingExtras, 20_000L);
            }
            if (!skipSurfaces) {
                provider = runtime.prepareProvider(record, 0);
                if (!"PROVIDER_READY".equals(provider.getString(RuntimeKeys.STATUS, ""))
                        && !"PROVIDER_ALREADY_READY".equals(provider.getString(RuntimeKeys.STATUS, ""))) {
                    throw new IllegalStateException("FILEPROVIDER_FAILED:"
                            + provider.getString(RuntimeKeys.STATUS, ""));
                }
                requireEngine(new JSONArray(), "c4-t05-preloop-stop",
                        killSettled(engine, packageName, 0));
            } else {
                provider = new Bundle();
                provider.putString(RuntimeKeys.STATUS, "SKIPPED_LOOPS_PHASE");
            }
            int passed = 0;
            int plannedLoops = skipLoops ? 0 : loops;
            for (int loop = 1; loop <= plannedLoops; loop++) {
                Bundle loopLaunchExtras = new Bundle();
                loopLaunchExtras.putBoolean(RuntimeKeys.HOST_TASK_REUSE, true);
                Bundle launched = runtime.launchComponent(record, 0,
                        "com.warden.controlledsandbox.fixture.MainActivity", loopLaunchExtras);
                String launchStatus = launched.getString(RuntimeKeys.STATUS, "FAILED");
                if (!"LAUNCH_PASS".equals(launchStatus)) {
                    stopSoft(runtime, record);
                    Thread.sleep(400L);
                    launched = runtime.launchComponent(record, 0,
                            "com.warden.controlledsandbox.fixture.MainActivity", loopLaunchExtras);
                    launchStatus = launched.getString(RuntimeKeys.STATUS, "FAILED");
                }
                if (!"LAUNCH_PASS".equals(launchStatus)) {
                    throw new IllegalStateException("LOOP_LAUNCH_FAILED:" + loop + ":"
                            + launchStatus + ":"
                            + launched.getString("failureMessage",
                                    launched.getString("errorMessage", "")));
                }
                stopSoft(runtime, record);
                Thread.sleep(200L);
                passed++;
            }
            SandboxOperationResult shortcut = engine.createShortcut(packageName, 0);
            if (!shortcut.successful()) {
                throw new IllegalStateException("SHORTCUT_FAILED:" + shortcut.errorCode());
            }
            DingTalkCompatibilityManager manager = new DingTalkCompatibilityManager();
            if (manager.enabled(context, packageName, 0)) {
                throw new IllegalStateException("DINGTALK_SPECIALIZATION_LEAKED_ONTO_FIXTURE");
            }
            if (!skipSurfaces) {
                Map<String, String> after = store.readApplied(packageName, 0);
                if (!applied.get("location.lat").equals(after.get("location.lat"))
                        || !applied.get("device.androidId").equals(after.get("device.androidId"))
                        || !applied.get("network.ssid").equals(after.get("network.ssid"))
                        || !applied.get("bluetooth.name").equals(after.get("bluetooth.name"))
                        || !applied.get("camera.sha256").equals(after.get("camera.sha256"))) {
                    throw new IllegalStateException("GENERIC_PROFILE_MUTATED_AFTER_SPECIALIZATION_OFF");
                }
            }
            campaign.put("pass", true);
            campaign.put("loops", passed);
            campaign.put("configProvider", "sx-config-v1-instance-store");
            campaign.put("f1Camera", applied.get("camera.sha256"));
            campaign.put("f2Location", applied.get("location.lat"));
            campaign.put("f3Device", applied.get("device.androidId"));
            campaign.put("f4Network", applied.get("network.ssid"));
            campaign.put("f5Bluetooth", applied.get("bluetooth.name"));
            campaign.put("components", components);
            campaign.put("provider", provider.getString(RuntimeKeys.STATUS, ""));
            campaign.put("shortcut", shortcut.status());
            campaign.put("dingTalkEnabledOnFixture", false);
        } catch (Exception error) {
            campaign.put("pass", false);
            campaign.put("error", String.valueOf(error.getMessage()));
        }
        return campaign;
    }

    private JSONObject runC4T05DingTalk(Context context, boolean trustNativeGuest) throws Exception {
        JSONObject campaign = new JSONObject();
        String packageName = DingTalkCompatibilityManager.PACKAGE_NAME;
        String trust = trustNativeGuest
                ? InstallSessionParamsSnapshot.NATIVE_GUEST_TRUST_EXPLICITLY_TRUSTED
                : InstallSessionParamsSnapshot.NATIVE_GUEST_TRUST_UNTRUSTED;
        try (SxSandboxAdapter adapter = new SxSandboxAdapter(context)) {
            CasSandboxEngine engine = new CasSandboxEngine(adapter);
            SandboxOperationResult imported = engine.installFromHost(packageName, trust);
            if (!imported.successful()) {
                throw new IllegalStateException("DINGTALK_IMPORT_FAILED:" + imported.errorCode()
                        + ":" + imported.errorMessage());
            }
            SandboxRecord record = adapter.findRecord(packageName);
            if (record == null) throw new IllegalStateException("DINGTALK_RECORD_MISSING");
            DingTalkCompatibilityManager manager = new DingTalkCompatibilityManager();
            DingTalkCompatibilityManager.Target target = manager.identify(
                    record.packageName, record.versionName, record.versionCode);
            campaign.put("versionName", record.versionName);
            campaign.put("versionCode", record.versionCode);
            campaign.put("targetReason", target.reason());
            campaign.put("supported", target.supported());
            campaign.put("defaultEnabled", manager.enabled(context, packageName, 0));
            if (!target.supported()) {
                throw new IllegalStateException("DINGTALK_REVISION_UNSUPPORTED:" + target.reason());
            }
            if (manager.enabled(context, packageName, 0)) {
                throw new IllegalStateException("DINGTALK_COMPATIBILITY_NOT_DEFAULT_OFF");
            }
            SandboxOperationResult cold = engine.launch(packageName, 0);
            if (!cold.successful()) {
                throw new IllegalStateException("DINGTALK_COLD_LAUNCH_FAILED:" + cold.errorCode()
                        + ":" + cold.errorMessage());
            }
            campaign.put("cold", cold.status());
            // LaunchHomeActivity historically System.exit()s into PrivacyPolicyActivity.
            Thread.sleep(12_000L);
            SandboxOperationResult hot = engine.launch(packageName, 0);
            if (!hot.successful() || "LAUNCH_FAILED".equals(hot.status())) {
                throw new IllegalStateException("DINGTALK_HOT_LAUNCH_FAILED:" + hot.status()
                        + ":" + hot.errorCode() + ":" + hot.errorMessage());
            }
            campaign.put("hot", hot.status());
            Thread.sleep(8_000L);
            // HOME/fg-bg is driven by the RD runner via adb keyevent so the Host
            // DebugCommandActivity is not itself backgrounded (Android 12+
            // BackgroundServiceStartNotAllowedException).
            campaign.put("background", "RUNNER_HOME");
            campaign.put("foreground", "RUNNER_LAUNCH");
            SandboxOperationResult upgrade = engine.installFromHost(packageName, trust);
            campaign.put("upgrade", upgrade.status());
            if (!upgrade.successful()) {
                throw new IllegalStateException("DINGTALK_UPGRADE_REIMPORT_FAILED:"
                        + upgrade.errorCode());
            }
            SandboxRecord afterUpgrade = adapter.findRecord(packageName);
            if (afterUpgrade == null) throw new IllegalStateException("DINGTALK_MISSING_AFTER_UPGRADE");
            campaign.put("upgradeVersionName", afterUpgrade.versionName);
            campaign.put("upgradeVersionCode", afterUpgrade.versionCode);
            if (!DingTalkCompatibilityManager.SUPPORTED_VERSION_NAME.equals(afterUpgrade.versionName)
                    || afterUpgrade.versionCode != DingTalkCompatibilityManager.SUPPORTED_VERSION_CODE) {
                throw new IllegalStateException("DINGTALK_UPGRADE_REVISION_DRIFT:"
                        + afterUpgrade.versionName + "/" + afterUpgrade.versionCode);
            }
            SandboxOperationResult afterUpgradeLaunch = engine.launch(packageName, 0);
            if (!afterUpgradeLaunch.successful()) {
                throw new IllegalStateException("DINGTALK_POST_UPGRADE_LAUNCH_FAILED:"
                        + afterUpgradeLaunch.errorCode());
            }
            campaign.put("postUpgradeLaunch", afterUpgradeLaunch.status());
            Thread.sleep(8_000L);
            campaign.put("pass", true);
            campaign.put("loginSurface", "DUMPSYS_REQUIRED");
        } catch (Exception error) {
            campaign.put("pass", false);
            campaign.put("error", String.valueOf(error.getMessage()));
        }
        return campaign;
    }

    private static void launchSurface(RuntimeClient runtime, SandboxRecord record,
                                      JSONArray components, String component, Bundle extras,
                                      long settleMs) throws Exception {
        Bundle launched = runtime.launchComponent(record, 0, component, extras);
        String status = launched.getString(RuntimeKeys.STATUS, "FAILED");
        components.put(component + "=" + status);
        if (!"LAUNCH_PASS".equals(status)) {
            throw new IllegalStateException("COMPONENT_LAUNCH_FAILED:" + component + ":" + status);
        }
        if (settleMs > 0L) Thread.sleep(settleMs);
    }

    private JSONObject runC4T03Migrate(Context context, String packageName, String trust)
            throws Exception {
        JSONObject campaign = new JSONObject();
        try (SxSandboxAdapter adapter = new SxSandboxAdapter(context);
             PackageServiceClient packages = new PackageServiceClient(context)) {
            CasSandboxEngine engine = new CasSandboxEngine(adapter);
            engine.killAll();
            for (SandboxInstance existing : engine.listInstalled()) {
                if (packageName.equals(existing.packageName())) {
                    engine.uninstall(existing.packageName(), existing.virtualUserId());
                }
            }
            requireEngine(new JSONArray(), "c4-t03-import",
                    engine.installFromHost(packageName, trust));
            SandboxOperationResult cloned = requireEngine(new JSONArray(), "c4-t03-clone",
                    engine.clone(packageName));
            int cloneUser = Integer.parseInt(cloned.diagnostics().getOrDefault("virtualUserId", "-1"));
            if (cloneUser < 1) throw new IllegalStateException("CLONE_USER_INVALID");
            SxMigrationHostStore store = new SxMigrationHostStore(context, packages);
            SxInstanceProfileMigrator migrator = new SxInstanceProfileMigrator(store);
            SxLegacyConfigDocument user0 = sxFixture(packageName, 0, "31.230400", "121.473700",
                    "02:00:00:00:00:10", "0123456789abcdef", "Fixture-0", new byte[]{1, 0});
            SxLegacyConfigDocument user1 = sxFixture(packageName, cloneUser, "22.543099", "113.929884",
                    "02:00:00:00:00:20", "fedcba9876543210", "Fixture-1", new byte[]{2, 0});
            SxMigrationRecord first = migrator.migrate(user0);
            SxMigrationRecord second = migrator.migrate(user1);
            SxMigrationRecord replay = migrator.migrate(user0);
            if (!SxMigrationRecord.COMMITTED.equals(first.status)
                    || !SxMigrationRecord.COMMITTED.equals(second.status)
                    || !SxMigrationRecord.IDEMPOTENT.equals(replay.status)
                    || !first.sourceKept) {
                throw new IllegalStateException("MIGRATION_STATUS_UNEXPECTED:"
                        + first.status + "/" + second.status + "/" + replay.status);
            }
            Map<String, String> applied0 = store.readApplied(packageName, 0);
            Map<String, String> applied1 = store.readApplied(packageName, cloneUser);
            if (applied0.getOrDefault("location.lat", "").equals(applied1.get("location.lat"))
                    || applied0.getOrDefault("device.androidId", "").equals(applied1.get("device.androidId"))
                    || applied0.getOrDefault("camera.sha256", "").equals(applied1.get("camera.sha256"))) {
                throw new IllegalStateException("CROSS_USER_PROFILE_LEAK");
            }
            SxLegacyConfigDocument changed = sxFixture(packageName, 0, "39.904200", "116.407400",
                    "02:00:00:00:00:10", "0123456789abcdef", "Fixture-0", new byte[]{1, 0});
            SxMigrationRecord interrupted = migrator.migrate(changed, true);
            if (!SxMigrationRecord.INTERRUPTED.equals(interrupted.status)) {
                throw new IllegalStateException("INTERRUPT_NOT_RECORDED:" + interrupted.status);
            }
            if (!applied0.get("location.lat").startsWith("31.2304")) {
                throw new IllegalStateException("INTERRUPT_MUTATED_LIVE:" + applied0.get("location.lat"));
            }
            SxMigrationRecord rolled = migrator.rollback(packageName, 0);
            Map<String, String> restored = store.readApplied(packageName, 0);
            if (!SxMigrationRecord.ROLLED_BACK.equals(rolled.status)
                    || !restored.getOrDefault("location.lat", "").startsWith("31.2304")) {
                throw new IllegalStateException("ROLLBACK_DID_NOT_RESTORE:" + restored);
            }
            if (!store.read(packageName, 0).sourceKept) {
                throw new IllegalStateException("OLD_SOURCE_DROPPED");
            }
            campaign.put("pass", true);
            campaign.put("cloneUser", cloneUser);
            campaign.put("user0Hash", first.sourceHash);
            campaign.put("user1Hash", second.sourceHash);
            campaign.put("replay", replay.status);
            campaign.put("interrupt", interrupted.status);
            campaign.put("rollback", rolled.status);
            campaign.put("user0Lat", restored.get("location.lat"));
            campaign.put("user1Lat", applied1.get("location.lat"));
            campaign.put("sourceKept", true);
        } catch (Exception error) {
            campaign.put("pass", false);
            campaign.put("error", String.valueOf(error.getMessage()));
        }
        return campaign;
    }

    private static SxLegacyConfigDocument sxFixture(String packageName, int userId, String lat,
            String lng, String mac, String androidId, String label, byte[] media) {
        Map<String, String> location = new LinkedHashMap<>();
        location.put("enabled", "true");
        location.put("lat", lat);
        location.put("lng", lng);
        location.put("accuracy", "5");
        location.put("altitude", "10");
        location.put("intervalMs", "1000");
        Map<String, String> device = new LinkedHashMap<>();
        device.put("enabled", "true");
        device.put("brand", "FixtureBrand");
        device.put("model", "FixtureModel");
        device.put("manufacturer", "FixtureMfr");
        device.put("board", "fixture");
        device.put("serial", "FXSERIAL000" + userId);
        device.put("imei", "353322101234567");
        device.put("androidId", androidId);
        device.put("imsi", "460001234567890");
        device.put("iccid", "89860012345678901234");
        device.put("operatorName", "FixtureNet");
        Map<String, String> network = new LinkedHashMap<>();
        network.put("enabled", "true");
        network.put("ssid", "Fixture-WiFi-" + userId);
        network.put("bssid", mac);
        network.put("mac", mac);
        network.put("mcc", "460");
        network.put("mnc", "1");
        network.put("lac", "1234");
        network.put("cid", "5678");
        Map<String, String> camera = new LinkedHashMap<>();
        camera.put("enabled", "true");
        camera.put("type", "image");
        camera.put("path", "fixture.png");
        Map<String, String> bluetooth = new LinkedHashMap<>();
        bluetooth.put("enabled", "true");
        bluetooth.put("name", "FixtureBT-" + userId);
        bluetooth.put("address", mac);
        return new SxLegacyConfigDocument(packageName, userId, label, location, device, network,
                camera, bluetooth, media, "image");
    }

    private JSONObject runC4T02Engine(Context context, String packageName, String trust)
            throws Exception {
        JSONObject campaign = new JSONObject();
        JSONArray traces = new JSONArray();
        JSONArray observerOps = new JSONArray();
        try (SxSandboxAdapter adapter = new SxSandboxAdapter(context)) {
            CasSandboxEngine engine = new CasSandboxEngine(adapter);
            engine.addObserver(new SandboxEngineObserver() {
                @Override public void onOperation(SandboxOperationResult result) {
                    try {
                        observerOps.put(operationJson(result));
                        Log.i("CS_C4_T02", "ENGINE_OP operation=" + result.operation()
                                + " successful=" + result.successful()
                                + " status=" + result.status()
                                + " error=" + result.errorCode());
                    } catch (Exception ignored) {
                    }
                }

                @Override public void onCatalogChanged(SandboxCatalog catalog) {
                    Log.i("CS_C4_T02", "ENGINE_CATALOG packages=" + catalog.packages().size()
                            + " instances=" + catalog.instances().size());
                }

                @Override public void onStatusChanged(SandboxOperationResult status) {
                    Log.i("CS_C4_T02", "ENGINE_STATUS successful=" + status.successful()
                            + " status=" + status.status());
                }
            });
            requireEngine(traces, "initialize", engine.initialize());
            if (!engine.isReady()) throw new IllegalStateException("ENGINE_NOT_READY");
            SandboxOperationResult attach = engine.onAttachBaseContext();
            requireEngine(traces, "onAttachBaseContext", attach);
            if (!"NO_OP_CAS_HOST".equals(attach.status())) {
                throw new IllegalStateException("ATTACH_MUST_BE_NO_OP");
            }
            requireEngine(traces, "onAppCreate", engine.onAppCreate());
            traces.put(namedOperation("reset-killAll", engine.killAll()));
            for (SandboxInstance existing : engine.listInstalled()) {
                if (!packageName.equals(existing.packageName())) continue;
                traces.put(namedOperation("reset-uninstall-u" + existing.virtualUserId(),
                        engine.uninstall(existing.packageName(), existing.virtualUserId())));
            }
            requireEngine(traces, "installFromHost",
                    engine.installFromHost(packageName, trust));
            if (!engine.isInstalled(packageName, 0) || engine.get(packageName, 0) == null) {
                throw new IllegalStateException("PRIMARY_INSTANCE_MISSING");
            }
            if (engine.listInstalled().isEmpty()) {
                throw new IllegalStateException("CATALOG_EMPTY_AFTER_IMPORT");
            }
            requireEngine(traces, "launch-user0", engine.launch(packageName, 0));
            requireEngine(traces, "kill-user0", killSettled(engine, packageName, 0));
            requireEngine(traces, "recovery-launch", engine.launch(packageName, 0));
            requireEngine(traces, "recovery-kill", killSettled(engine, packageName, 0));
            SandboxOperationResult cloned = requireEngine(traces, "clone", engine.clone(packageName));
            int cloneUser = Integer.parseInt(cloned.diagnostics().getOrDefault("virtualUserId", "-1"));
            if (cloneUser < 1) throw new IllegalStateException("CLONE_USER_INVALID:" + cloneUser);
            campaign.put("cloneUser", cloneUser);
            requireEngine(traces, "launch-clone", engine.launch(packageName, cloneUser));
            requireEngine(traces, "kill-clone", killSettled(engine, packageName, cloneUser));
            requireEngine(traces, "clear-user0", engine.clearData(packageName, 0));
            SandboxOperationResult shortcut = requireEngine(traces, "createShortcut",
                    engine.createShortcut(packageName, 0));
            if (!shortcut.diagnostics().getOrDefault("instanceId", "").contains(packageName)) {
                throw new IllegalStateException("SHORTCUT_IDENTITY_MISSING");
            }
            requireEngine(traces, "setDisplayName",
                    engine.setDisplayName(packageName, 0, "c4-t02"));
            SandboxOperationResult missing = engine.launch(
                    "com.warden.controlledsandbox.missing", 0);
            traces.put(namedOperation("launch-missing", missing));
            if (missing.successful()
                    || !CasSandboxEngine.PACKAGE_NOT_INSTALLED.equals(missing.errorCode())) {
                throw new IllegalStateException("MISSING_PACKAGE_NOT_FAIL_CLOSED:"
                        + missing.errorCode());
            }
            SandboxOperationResult badClone = engine.clone("com.warden.controlledsandbox.missing");
            traces.put(namedOperation("clone-missing", badClone));
            if (badClone.successful()) {
                throw new IllegalStateException("MISSING_CLONE_NOT_FAIL_CLOSED");
            }
            requireEngine(traces, "killAll", engine.killAll());
            requireEngine(traces, "uninstall-clone", engine.uninstall(packageName, cloneUser));
            requireEngine(traces, "uninstall-user0", engine.uninstall(packageName, 0));
            if (engine.isInstalled(packageName, 0) || engine.isInstalled(packageName, cloneUser)) {
                throw new IllegalStateException("INSTANCE_RESIDUE_AFTER_DELETE");
            }
            boolean blackBox = traces.toString().contains("BlackBoxCore")
                    || traces.toString().contains("top.niunaijun.blackbox");
            if (blackBox) throw new IllegalStateException("BLACKBOX_TOKEN_IN_ENGINE_TRACE");
            campaign.put("pass", true);
            campaign.put("observerOperations", observerOps.length());
            campaign.put("traceCount", traces.length());
            campaign.put("authority", "SandboxSdk");
            campaign.put("adapterOwnsCatalog", false);
        } catch (Exception error) {
            campaign.put("pass", false);
            campaign.put("error", String.valueOf(error.getMessage()));
        }
        campaign.put("traces", traces);
        campaign.put("observerOps", observerOps);
        campaign.put("observerOperations", observerOps.length());
        campaign.put("traceCount", traces.length());
        return campaign;
    }

    private static void stopSoft(RuntimeClient runtime, SandboxRecord record) throws Exception {
        try {
            runtime.stop(record, 0);
        } catch (Exception error) {
            String message = String.valueOf(error.getMessage());
            if (!message.contains("GUEST_STOP_FAILED") && !message.contains("STOP_FAILED")) {
                throw error;
            }
        }
    }

    private static SandboxOperationResult killSettled(CasSandboxEngine engine, String packageName,
                                                      int userId) throws Exception {
        SandboxOperationResult result = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            Thread.sleep(attempt == 0 ? 1500L : 500L);
            result = engine.kill(packageName, userId);
            if (result != null && result.successful()) return result;
            String code = result == null ? "" : result.errorCode();
            String message = result == null ? "" : String.valueOf(result.errorMessage());
            if (!"STOP_FAILED".equals(code) && !message.contains("GUEST_STOP_FAILED")) {
                return result;
            }
        }
        return result;
    }

    private static SandboxOperationResult requireEngine(JSONArray traces, String name,
                                                        SandboxOperationResult result)
            throws Exception {
        traces.put(namedOperation(name, result));
        if (result == null || !result.successful()) {
            throw new IllegalStateException("C4_T02_" + name + "_FAILED:"
                    + (result == null ? "null" : result.errorCode() + ":" + result.errorMessage()));
        }
        return result;
    }

    private static JSONObject namedOperation(String name, SandboxOperationResult result)
            throws Exception {
        JSONObject row = operationJson(result);
        row.put("step", name);
        return row;
    }

    private static JSONObject operationJson(SandboxOperationResult result) throws Exception {
        JSONObject out = new JSONObject();
        if (result == null) {
            return out.put("successful", false).put("errorCode", "NO_RESULT");
        }
        out.put("successful", result.successful());
        out.put("operation", result.operation());
        out.put("status", result.status());
        out.put("errorCode", result.errorCode());
        out.put("errorMessage", result.errorMessage());
        if (result.identity() != null) {
            out.put("packageName", result.identity().packageName());
            out.put("virtualUserId", result.identity().virtualUserId());
            out.put("instanceId", result.identity().instanceId());
        }
        JSONObject diagnostics = new JSONObject();
        for (var entry : result.diagnostics().entrySet()) {
            diagnostics.put(entry.getKey(), entry.getValue());
        }
        out.put("diagnostics", diagnostics);
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

    private JSONObject runConcurrentPackageAdds(String packageName, int virtualUserId,
            boolean trustNativeGuest, String requestId) throws Exception {
        String trust = trustNativeGuest
                ? InstallSessionParamsSnapshot.NATIVE_GUEST_TRUST_EXPLICITLY_TRUSTED
                : InstallSessionParamsSnapshot.NATIVE_GUEST_TRUST_UNTRUSTED;
        ExecutorService callers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<JSONObject> first = callers.submit(() -> packageAddAttempt(packageName,
                    virtualUserId, trust, requestId + "-a", ready, start));
            Future<JSONObject> second = callers.submit(() -> packageAddAttempt(packageName,
                    virtualUserId, trust, requestId + "-b", ready, start));
            if (!ready.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("CONCURRENT_ADD_READY_TIMEOUT");
            }
            start.countDown();
            JSONObject a = first.get(240, TimeUnit.SECONDS);
            JSONObject b = second.get(240, TimeUnit.SECONDS);
            int succeeded = (a.optBoolean("successful", false) ? 1 : 0)
                    + (b.optBoolean("successful", false) ? 1 : 0);
            int busy = ("MUTATION_BUSY".equals(a.optString("errorCode")) ? 1 : 0)
                    + ("MUTATION_BUSY".equals(b.optString("errorCode")) ? 1 : 0);
            String aOperation = a.optJSONObject("trace") == null ? ""
                    : a.optJSONObject("trace").optString("operationId");
            String bOperation = b.optJSONObject("trace") == null ? ""
                    : b.optJSONObject("trace").optString("operationId");
            boolean sameOperation = !aOperation.isEmpty() && aOperation.equals(bOperation);
            return new JSONObject().put("first", a).put("second", b)
                    .put("successfulCount", succeeded).put("busyCount", busy)
                    .put("sameOperationId", sameOperation)
                    .put("pass", succeeded == 1 && busy == 1 && sameOperation);
        } finally {
            start.countDown();
            callers.shutdownNow();
        }
    }

    private JSONObject packageAddAttempt(String packageName, int virtualUserId, String trust,
            String requestId, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("CONCURRENT_ADD_START_TIMEOUT");
        }
        try (PackageServiceClient client = new PackageServiceClient(this)) {
            PackageImportResult imported = client.importInstalledApplicationAndEnsure(
                    requestId, packageName, trust, virtualUserId);
            return new JSONObject().put("requestId", requestId).put("successful", true)
                    .put("errorCode", "").put("trace",
                            new JSONObject(imported.operationTraceJson()));
        } catch (PackageMutationFailureException failure) {
            JSONObject row = new JSONObject().put("requestId", requestId)
                    .put("successful", false).put("errorCode", failure.code);
            if (!failure.operationTraceJson.isEmpty()) {
                row.put("trace", new JSONObject(failure.operationTraceJson));
            }
            return row;
        }
    }
}
