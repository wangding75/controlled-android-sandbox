package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import com.warden.controlledsandbox.framework.packagemanager.PackageManagerHook;
import com.warden.controlledsandbox.framework.permission.AppOpsManagerHook;
import com.warden.controlledsandbox.framework.permission.PermissionManagerHook;
import com.warden.controlledsandbox.framework.service.JobSchedulerHook;
import com.warden.controlledsandbox.framework.service.NotificationManagerHook;
import com.warden.controlledsandbox.framework.service.StorageManagerHook;
import com.warden.controlledsandbox.framework.service.CameraServiceHook;
import com.warden.controlledsandbox.framework.service.LocationServiceHook;
import com.warden.controlledsandbox.framework.service.AudioCaptureServiceHook;
import com.warden.controlledsandbox.framework.service.AlarmManagerHook;
import com.warden.controlledsandbox.framework.service.ClipboardManagerHook;
import com.warden.controlledsandbox.framework.service.AccountManagerHook;
import com.warden.controlledsandbox.framework.service.TelephonyServiceHook;
import com.warden.controlledsandbox.framework.service.WifiServiceHook;
import com.warden.controlledsandbox.framework.service.BluetoothServiceHook;
import com.warden.controlledsandbox.framework.service.SensorServiceHook;
import com.warden.controlledsandbox.framework.service.WindowManagerHook;
import com.warden.controlledsandbox.framework.service.ActivityClientHook;
import com.warden.controlledsandbox.framework.service.InputMethodManagerHook;
import com.warden.controlledsandbox.framework.service.DisplayManagerHook;

import android.content.Context;
import com.warden.controlledsandbox.framework.core.FrameworkProxyController;
import com.warden.controlledsandbox.framework.identity.IdentityContext;
import com.warden.controlledsandbox.framework.core.ProxyInstallReport;
import com.warden.controlledsandbox.framework.core.ProxyTelemetry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Installs framework proxies independently and keeps reversible handles for process shutdown. */
public final class FrameworkHooks implements AutoCloseable {
    private final List<AutoCloseable> hooks;
    private final FrameworkHookReport report;

    private FrameworkHooks(List<AutoCloseable> hooks, FrameworkHookReport report) {
        this.hooks = hooks;
        this.report = report;
    }

    public static FrameworkHooks install(Context context, GuestIdentity identity) {
        return install(context, context, identity, FrameworkCallInterceptor.NO_OP);
    }

    public static FrameworkHooks install(
            Context context, GuestIdentity identity, FrameworkCallInterceptor callInterceptor) {
        return install(context, context, identity, callInterceptor);
    }

    public static FrameworkHooks install(
            Context guestContext, Context hostServiceContext, GuestIdentity identity,
            FrameworkCallInterceptor callInterceptor) {
        List<AutoCloseable> hooks = new ArrayList<>();
        hooks.add(identity.virtualServices());
        hooks.add(identity.interactions());
        Map<String, Boolean> installed = new LinkedHashMap<>();
        Map<String, String> failures = new LinkedHashMap<>();
        attempt("packageManager", installed, failures, hooks, () -> PackageManagerHook.install(guestContext, identity));
        installActivityFrameworkPair(identity, callInterceptor, installed, failures, hooks);
        attempt("activityClient", installed, failures, hooks, () -> ActivityClientHook.install(identity));
        attempt("window", installed, failures, hooks, () -> WindowManagerHook.install(identity));
        attempt("inputMethod", installed, failures, hooks,
                () -> InputMethodManagerHook.install(hostServiceContext, identity));
        attempt("display", installed, failures, hooks,
                () -> DisplayManagerHook.install(hostServiceContext, identity));
        attempt("appOps", installed, failures, hooks, () -> AppOpsManagerHook.install(guestContext, identity));
        attempt("permission", installed, failures, hooks, () -> PermissionManagerHook.install(guestContext, identity));
        FrameworkHookReport mandatoryReport = new FrameworkHookReport(installed, failures);
        if (mandatoryReport.readiness() == FrameworkHookReport.Readiness.BLOCKED) {
            rollbackInstalled(hooks, installed, failures);
            return new FrameworkHooks(hooks, new FrameworkHookReport(installed, failures));
        }
        attempt("notification", installed, failures, hooks, () -> NotificationManagerHook.install(identity));
        attempt("jobScheduler", installed, failures, hooks, () -> JobSchedulerHook.install(guestContext, identity));
        attempt("alarm", installed, failures, hooks, () -> AlarmManagerHook.install(hostServiceContext, identity));
        attempt("clipboard", installed, failures, hooks, () -> ClipboardManagerHook.install(hostServiceContext, identity));
        attempt("account", installed, failures, hooks, () -> AccountManagerHook.install(hostServiceContext, identity));
        attempt("storage", installed, failures, hooks, () -> StorageManagerHook.install(guestContext, identity));
        attempt("camera", installed, failures, hooks, () -> CameraServiceHook.install(hostServiceContext, identity));
        attempt("location", installed, failures, hooks, () -> LocationServiceHook.install(hostServiceContext, identity));
        attempt("deviceIdentity", installed, failures, hooks, () -> BuildIdentityHook.install(identity));
        attempt("settingsIdentity", installed, failures, hooks,
                () -> SettingsProviderIdentityHook.install(guestContext, identity));
        attempt("telephony", installed, failures, hooks,
                () -> TelephonyServiceHook.installTelephony(hostServiceContext, identity));
        attempt("phoneSubInfo", installed, failures, hooks,
                () -> TelephonyServiceHook.installSubscriberInfo(hostServiceContext, identity));
        attempt("telephonyRegistry", installed, failures, hooks,
                () -> TelephonyServiceHook.installRegistry(hostServiceContext, identity));
        attempt("subscription", installed, failures, hooks,
                () -> TelephonyServiceHook.installSubscription(hostServiceContext, identity));
        attempt("wifi", installed, failures, hooks, () -> WifiServiceHook.install(hostServiceContext, identity));
        attempt("wifiScanner", installed, failures, hooks,
                () -> WifiServiceHook.installScanner(hostServiceContext, identity));
        attempt("bluetooth", installed, failures, hooks,
                () -> BluetoothServiceHook.install(hostServiceContext, identity));
        attempt("sensorCatalog", installed, failures, hooks,
                () -> SensorServiceHook.install(hostServiceContext, identity));
        attempt("audioCapture", installed, failures, hooks, () -> AudioCaptureServiceHook.install(hostServiceContext, identity));
        return new FrameworkHooks(hooks, new FrameworkHookReport(installed, failures));
    }

    public FrameworkHookReport report() { return report; }

    @Override public void close() {
        for (int index = hooks.size() - 1; index >= 0; index--) {
            try { hooks.get(index).close(); } catch (Exception ignored) { }
        }
        hooks.clear();
    }

    private static void installActivityFrameworkPair(
            GuestIdentity identity,
            FrameworkCallInterceptor callInterceptor,
            Map<String, Boolean> installed,
            Map<String, String> failures,
            List<AutoCloseable> hooks) {
        final String activityManager = "activityManager";
        final String activityTaskManager = "activityTaskManager";
        try {
            IdentityContext context = new IdentityContext(
                    identity.packageName(),
                    identity.virtualUid(),
                    identity.hostPackageName(),
                    identity.hostUid(),
                    identity.processName(),
                    identity.virtualUserId(),
                    identity.generation());
            FrameworkProxyController controller = FrameworkProxyController.installDefault(
                    context, ProxyTelemetry.NO_OP, callInterceptor);
            if (!controller.passed()) {
                installed.put(activityManager, false);
                installed.put(activityTaskManager, false);
                for (ProxyInstallReport item : controller.reports()) {
                    String key = serviceKey(item.service());
                    failures.put(key, failureDetail(item));
                }
                failures.putIfAbsent(activityManager, "java.lang.IllegalStateException:atomic proxy pair failed");
                failures.putIfAbsent(activityTaskManager, "java.lang.IllegalStateException:atomic proxy pair failed");
                return;
            }
            installed.put(activityManager, true);
            installed.put(activityTaskManager, true);
            hooks.add(() -> { controller.rollbackAll(); });
        } catch (Throwable error) {
            String failure = error.getClass().getName() + ":" + String.valueOf(error.getMessage());
            installed.put(activityManager, false);
            installed.put(activityTaskManager, false);
            failures.put(activityManager, failure);
            failures.put(activityTaskManager, failure);
        }
    }

    private static String serviceKey(String service) {
        return switch (service) {
            case "activity-manager" -> "activityManager";
            case "activity-task-manager" -> "activityTaskManager";
            default -> service;
        };
    }

    private static String failureDetail(ProxyInstallReport report) {
        String message = report.failure();
        if (message.isEmpty() && !report.unsupportedProtectedSignatures().isEmpty()) {
            message = "unsupported signatures=" + String.join(",", report.unsupportedProtectedSignatures());
        }
        if (message.isEmpty()) message = "proxy installation did not pass";
        return "java.lang.IllegalStateException:" + message;
    }

    private static void rollbackInstalled(List<AutoCloseable> hooks, Map<String, Boolean> installed,
                                          Map<String, String> failures) {
        for (int index = hooks.size() - 1; index >= 0; index--) {
            try { hooks.get(index).close(); }
            catch (Exception error) {
                failures.put("rollback-" + index, error.getClass().getName() + ":" + String.valueOf(error.getMessage()));
            }
        }
        hooks.clear();
        for (Map.Entry<String, Boolean> item : installed.entrySet()) {
            if (Boolean.TRUE.equals(item.getValue())) {
                item.setValue(false);
                failures.putIfAbsent(item.getKey(), "java.lang.IllegalStateException:rolled back after mandatory hook failure");
            }
        }
    }

    private static void attempt(String name, Map<String, Boolean> installed, Map<String, String> failures,
                                List<AutoCloseable> hooks, Installer installer) {
        try {
            AutoCloseable hook = installer.install();
            hooks.add(hook);
            installed.put(name, true);
        } catch (Throwable error) {
            installed.put(name, false);
            failures.put(name, error.getClass().getName() + ":" + String.valueOf(error.getMessage()));
        }
    }

    private interface Installer { AutoCloseable install() throws Exception; }
}
