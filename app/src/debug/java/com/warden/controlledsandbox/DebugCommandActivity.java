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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
        JSONObject result = new JSONObject();
        RuntimeClient runtime = null;
        PackageServiceClient packages = null;
        try {
            result.put("command", command).put("package", packageName)
                    .put("virtualUserId", virtualUserId).put("trustNativeGuest", trustNativeGuest)
                    .put("requestId", requestId)
                    .put("startedAt", System.currentTimeMillis());
            if ("native-hostile".equals(command)) {
                JSONObject campaign = HostileProductionCampaign.run(this);
                result.put("nativeHostile", campaign);
                result.put("operation", new JSONObject().put("status",
                        campaign.optBoolean("pocValid", false)
                                ? "NATIVE_HOSTILE_RAN" : "NATIVE_HOSTILE_INVALID"));
                if (!campaign.optBoolean("pocValid", false)) {
                    throw new IllegalStateException("ISOLATED_UID_POC_INVALID:"
                            + campaign.optString("error", "uid not distinct"));
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
                    || "import-prepare".equals(command) || record == null
                    || installedApkRevisionChanged(record, packageName));
            if (importRequested) {
                // Resolve the complete host-installed artifact set.  A package with a dynamic
                // feature or ABI/resource split must enter the same multi-artifact pipeline as
                // the foreground import flow; importing only sourceDir silently publishes a
                // base-only revision and makes split classes invisible at runtime.
                record = packages.importInstalledApplication(packageName,
                        trustNativeGuest
                                ? InstallSessionParamsSnapshot.NATIVE_GUEST_TRUST_EXPLICITLY_TRUSTED
                                : InstallSessionParamsSnapshot.NATIVE_GUEST_TRUST_UNTRUSTED);
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
                operation = runtime.launchComponent(record, virtualUserId, component);
                requireStatus("launch-component", operation, "LAUNCH_PASS");
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
                operation = runtime.launch(record, virtualUserId);
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
                        "PREPARED", "ALREADY_PREPARED");
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
                Bundle launch = runtime.launchComponent(record, virtualUserId,
                        "com.warden.controlledsandbox.fixture.BroadcastCampaignActivity");
                requireStatus("broadcast-campaign-launch-" + iteration, launch, "LAUNCH_PASS");
                // The Guest Activity logs the real API results. Keep the Host command alive long
                // enough for ordered async completion and Activity teardown before the next round.
                Thread.sleep(1_400L);
            } finally {
                runtime.stop(record, virtualUserId);
            }
            Thread.sleep(200L);
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

    private void writeResult(JSONObject result) {
        try (FileOutputStream output = new FileOutputStream(new File(getFilesDir(), "debug-command-result.json"))) {
            output.write(result.toString(2).getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        } catch (Exception error) {
            Log.e(TAG, "Cannot write command result: " + error);
        }
    }
}
