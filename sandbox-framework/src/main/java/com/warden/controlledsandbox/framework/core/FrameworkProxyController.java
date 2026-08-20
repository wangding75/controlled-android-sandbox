package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.framework.binder.BinderSessionFence;
import com.warden.controlledsandbox.framework.identity.IdentityContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class FrameworkProxyController {
    private final List<ProxyInstallReport> reports;
    private final List<InstalledFrameworkProxy> installed;

    private FrameworkProxyController(
            List<ProxyInstallReport> reports,
            List<InstalledFrameworkProxy> installed) {
        this.reports = List.copyOf(reports);
        this.installed = new ArrayList<>(installed);
    }

    public static FrameworkProxyController installDefault(
            IdentityContext context,
            ProxyTelemetry telemetry) {
        return installDefault(context, telemetry, FrameworkCallInterceptor.NO_OP);
    }

    public static FrameworkProxyController installDefault(
            IdentityContext context,
            ProxyTelemetry telemetry,
            FrameworkCallInterceptor callInterceptor) {
        return installDefault(context, telemetry, callInterceptor,
                BinderSessionFence.ALWAYS_ACTIVE);
    }

    public static FrameworkProxyController installDefault(
            IdentityContext context,
            ProxyTelemetry telemetry,
            FrameworkCallInterceptor callInterceptor,
            BinderSessionFence sessionFence) {
        Objects.requireNonNull(context, "context");
        FrameworkProxyInstaller installer = new FrameworkProxyInstaller(sessionFence);
        List<ProxyInstallReport> reports = new ArrayList<>();
        List<InstalledFrameworkProxy> installed = new ArrayList<>();
        for (FrameworkServiceSpec spec : List.of(
                FrameworkServiceSpec.activityManager(),
                FrameworkServiceSpec.activityTaskManager())) {
            FrameworkProxyInstaller.InstallOutcome outcome = installer.install(
                    spec, context, telemetry, callInterceptor);
            reports.add(outcome.report());
            if (outcome.installedProxy() != null) {
                installed.add(outcome.installedProxy());
            }
        }
        FrameworkProxyController controller = new FrameworkProxyController(reports, installed);
        if (!controller.passed()) {
            controller.rollbackAll();
        }
        return controller;
    }

    public List<ProxyInstallReport> reports() {
        return reports;
    }

    public boolean passed() {
        return reports.size() == 2 && reports.stream().allMatch(ProxyInstallReport::passed);
    }

    public synchronized List<String> rollbackAll() {
        List<String> rolledBack = new ArrayList<>();
        Collections.reverse(installed);
        for (InstalledFrameworkProxy proxy : installed) {
            try {
                if (proxy.rollback()) {
                    rolledBack.add(proxy.serviceName());
                }
            } catch (IllegalAccessException exception) {
                rolledBack.add(proxy.serviceName() + ":rollback-failed:" + exception);
            }
        }
        installed.clear();
        return List.copyOf(rolledBack);
    }
}
