package com.warden.controlledsandbox.runtime.component.receiver;

import android.os.Bundle;
import com.warden.controlledsandbox.domain.component.receiver.OrderedBroadcastState;
import com.warden.controlledsandbox.domain.packageinfo.manifest.ManifestModel;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.session.SessionState;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ManifestBroadcastDispatcherSelfTest {
    private ManifestBroadcastDispatcherSelfTest() { }

    public static void main(String[] args) throws Exception {
        testOrderedResultAndAbort();
        testFailurePolicies();
        System.out.println("PASS manifest broadcast dispatcher self-test");
    }

    private static void testOrderedResultAndAbort() {
        List<BrokerManifestReceiverRuntime.Route> routes = routes();
        ManifestBroadcastDispatcher.DispatchReport report = new ManifestBroadcastDispatcher().dispatch(
                routes, true, false, OrderedBroadcastState.initial(0, "start", Map.of()),
                (route, state) -> {
                    if (route.priority() == 300) {
                        return ManifestBroadcastDispatcher.DeliveryOutcome.success(
                                new OrderedBroadcastState.ResultUpdate().resultCode(10)
                                        .resultData("high").resultExtras(Map.of("owner", "high")));
                    }
                    if (route.priority() == 200) {
                        return ManifestBroadcastDispatcher.DeliveryOutcome.success(
                                new OrderedBroadcastState.ResultUpdate().resultCode(20)
                                        .resultData("middle").abort());
                    }
                    return ManifestBroadcastDispatcher.DeliveryOutcome.success(
                            new OrderedBroadcastState.ResultUpdate().resultCode(30).resultData("low"));
                });
        require(report.matchedCount() == 3 && report.deliveredCount() == 2 && report.failedCount() == 0
                        && report.processedCount() == 2,
                "ordered delivery counts and abort stop");
        require(report.finalState().resultCode() == 20
                        && "middle".equals(report.finalState().resultData())
                        && report.finalState().aborted(),
                "ordered result chain");
    }

    private static void testFailurePolicies() {
        List<BrokerManifestReceiverRuntime.Route> routes = routes();
        ManifestBroadcastDispatcher dispatcher = new ManifestBroadcastDispatcher();
        ManifestBroadcastDispatcher.DispatchReport continueReport = dispatcher.dispatch(routes, true, false,
                OrderedBroadcastState.initial(0, "", Map.of()),
                (route, state) -> route.priority() == 300
                        ? ManifestBroadcastDispatcher.DeliveryOutcome.failure("BROKEN")
                        : ManifestBroadcastDispatcher.DeliveryOutcome.success(
                                new OrderedBroadcastState.ResultUpdate().resultCode(5)));
        require(continueReport.deliveredCount() == 2 && continueReport.failedCount() == 1
                        && !continueReport.finalState().aborted(),
                "ordered continue-on-failure");
        ManifestBroadcastDispatcher.DispatchReport stopReport = dispatcher.dispatch(routes, true, true,
                OrderedBroadcastState.initial(0, "", Map.of()),
                (route, state) -> ManifestBroadcastDispatcher.DeliveryOutcome.failure("BROKEN"));
        require(stopReport.deliveredCount() == 0 && stopReport.failedCount() == 1
                        && stopReport.processedCount() == 1 && stopReport.finalState().aborted(),
                "ordered stop-on-failure");
    }

    private static List<BrokerManifestReceiverRuntime.Route> routes() {
        BrokerManifestReceiverRuntime runtime = new BrokerManifestReceiverRuntime();
        runtime.indexManifest(sender(), template("com.example.sender"));
        runtime.indexManifest(target("com.example.high", "HighReceiver", 300), template("com.example.high"));
        runtime.indexManifest(target("com.example.middle", "MiddleReceiver", 200), template("com.example.middle"));
        runtime.indexManifest(target("com.example.low", "LowReceiver", 10), template("com.example.low"));
        Bundle request = new Bundle();
        request.putString(ComponentOperations.ACTION, "ACTION");
        GuestSession sender = new GuestSession("sender", "com.example.sender", 0,
                "com.example.sender", 0, 1, SessionState.READY, 1, "");
        return runtime.routeImplicit(request, sender);
    }

    private static ManifestModel sender() {
        ManifestModel model = new ManifestModel();
        model.packageName("com.example.sender");
        return model;
    }

    private static ManifestModel target(String packageName, String className, int priority) {
        ManifestModel model = new ManifestModel();
        model.packageName(packageName);
        ManifestModel.Component receiver = new ManifestModel.Component(packageName + "." + className,
                "", true, true, false, "", "");
        receiver.addIntentFilter(priority).addAction("ACTION");
        model.addReceiver(receiver);
        return model;
    }

    private static Bundle template(String packageName) {
        Bundle bundle = new Bundle();
        bundle.putString(RuntimeKeys.PACKAGE_NAME, packageName);
        bundle.putInt(RuntimeKeys.VIRTUAL_USER_ID, 0);
        bundle.putString(RuntimeKeys.APK_PATH, "/private/" + packageName + ".apk");
        bundle.putStringArrayList(RuntimeKeys.PERMISSIONS, new ArrayList<>());
        return bundle;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
