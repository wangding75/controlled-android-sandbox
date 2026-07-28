package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.framework.identity.ArgumentRewriteRule;
import com.warden.controlledsandbox.framework.identity.MethodIdentityPolicy;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record FrameworkServiceSpec(
        String serviceName,
        String ownerClassName,
        String singletonFieldName,
        List<MethodIdentityPolicy> inboundPolicies,
        Set<String> outboundMethods) {

    public FrameworkServiceSpec {
        serviceName = requireText(serviceName, "serviceName");
        ownerClassName = requireText(ownerClassName, "ownerClassName");
        singletonFieldName = requireText(singletonFieldName, "singletonFieldName");
        inboundPolicies = List.copyOf(Objects.requireNonNull(inboundPolicies, "inboundPolicies"));
        outboundMethods = Set.copyOf(Objects.requireNonNull(outboundMethods, "outboundMethods"));
    }

    public Optional<MethodIdentityPolicy> inboundPolicy(Method method) {
        return inboundPolicies.stream().filter(policy -> policy.matches(method)).findFirst();
    }

    public boolean hasInboundMethodName(String methodName) {
        return inboundPolicies.stream().anyMatch(policy -> policy.methodName().equals(methodName));
    }

    public static FrameworkServiceSpec activityManager() {
        return new FrameworkServiceSpec(
                "activity-manager",
                "android.app.ActivityManager",
                "IActivityManagerSingleton",
                List.of(
                        policy("moveTaskToFront", 5, pkg(1)),
                        policy("moveTaskToFront", 6, pkg(1)),
                        policy("getContentProvider", 5, pkg(1)),
                        policy("registerReceiver", 7, pkg(1)),
                        policy("registerReceiverWithFeature", 9, pkg(1)),
                        policy("startService", 6, pkg(4)),
                        policy("startService", 7, pkg(4)),
                        policy("bindService", 8, pkg(6)),
                        policy("bindServiceInstance", 9, pkg(7)),
                        policy("clearApplicationUserData", 4, pkg(0)),
                        policy("stopAppForUser", 2, pkg(0)),
                        policy("forceStopPackage", 2, pkg(0)),
                        policy("forceStopPackageEvenWhenStopping", 2, pkg(0)),
                        policy("killBackgroundProcesses", 2, pkg(0)),
                        policy("addPackageDependency", 1, pkg(0)),
                        policy("checkPermission", 3, uid(2)),
                        policy("isUidActive", 2, uid(0), pkg(1)),
                        policy("getUidProcessState", 2, uid(0), pkg(1)),
                        policy("handleIncomingUser", 7, uid(1), pkg(6)),
                        policy("noteWakeupAlarm", 5, uid(2), pkg(3)),
                        policy("peekService", 3, pkg(2))),
                Set.of(
                        "getRunningAppProcesses",
                        "getRunningExternalApplications",
                        "getProcessesInErrorState"));
    }

    public static FrameworkServiceSpec activityTaskManager() {
        return new FrameworkServiceSpec(
                "activity-task-manager",
                "android.app.ActivityTaskManager",
                "IActivityTaskManagerSingleton",
                List.of(
                        policy("startActivity", 10, pkg(1)),
                        policy("startActivity", 11, pkg(1)),
                        policy("startActivities", 7, pkg(1)),
                        policy("startActivities", 8, pkg(1)),
                        policy("startActivityAsUser", 11, pkg(1)),
                        policy("startActivityAsUser", 12, pkg(1)),
                        policy("startActivityWithFeature", 11, pkg(1)),
                        policy("startActivityAndWait", 11, pkg(1)),
                        policy("startActivityAndWait", 12, pkg(1)),
                        policy("startActivityWithConfig", 12, pkg(1)),
                        policy("startActivityWithConfig", 13, pkg(1)),
                        policy("startActivityAsCaller", 12, pkg(1)),
                        policy("startVoiceActivity", 12, pkg(0), uid(3)),
                        policy("startAssistantActivity", 9, pkg(0), uid(3)),
                        policy("startActivityFromGameSession", 8, pkg(1), uid(4)),
                        policy("moveTaskToFront", 5, pkg(1)),
                        policy("moveTaskToFront", 6, pkg(1)),
                        policy("getAppTasks", 1, pkg(0)),
                        policy("getAppTasks", 2, pkg(0))),
                Set.of(
                        "getAppTasks",
                        "getTasks",
                        "getRecentTasks"));
    }

    private static MethodIdentityPolicy policy(
            String methodName,
            int argumentCount,
            ArgumentRewriteRule... rules) {
        return MethodIdentityPolicy.of(methodName, argumentCount, rules);
    }

    private static ArgumentRewriteRule pkg(int index) {
        return ArgumentRewriteRule.packageName(index);
    }

    private static ArgumentRewriteRule uid(int index) {
        return ArgumentRewriteRule.uid(index);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
