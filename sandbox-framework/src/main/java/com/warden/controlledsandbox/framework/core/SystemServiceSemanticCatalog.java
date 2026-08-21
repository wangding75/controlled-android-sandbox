package com.warden.controlledsandbox.framework.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Single semantic inventory for the service adapters.  It is deliberately data-oriented: a
 * service being present in the ServiceManager is not enough to make its row CLOSED.
 */
public final class SystemServiceSemanticCatalog {
    private static final Map<String, SystemServiceSemanticContract> CONTRACTS = create();

    private SystemServiceSemanticCatalog() { }

    public static List<SystemServiceSemanticContract> all() {
        return Collections.unmodifiableList(new ArrayList<>(CONTRACTS.values()));
    }

    public static SystemServiceSemanticContract forService(String service) {
        if (service == null) return null;
        return CONTRACTS.get(service.replace("-", "").toLowerCase(Locale.ROOT));
    }

    private static Map<String, SystemServiceSemanticContract> create() {
        Map<String, SystemServiceSemanticContract> result = new LinkedHashMap<>();
        add(result, "activitymanager", "Guest task/activity ledger", "Guest package+uid; binder session", "returned task/activity objects projected", "death fence + task ledger", SystemServiceSemanticContract.Status.PARTIAL);
        add(result, "packagemanager", "virtual package universe", "caller package/uid and visibility graph", "ApplicationInfo/PackageInfo/component projection", "package revision + loader lifecycle", SystemServiceSemanticContract.Status.PARTIAL);
        add(result, "appops", "Guest app-op policy", "package, uid, opPackageName and AttributionSource", "mode/SyncNotedAppOp projected", "operation scope is non-mutating", SystemServiceSemanticContract.Status.PARTIAL);
        add(result, "permission", "Guest runtime permission policy", "package owner and virtual uid", "check result projected; mutations stay package-owned", "runtime state owned by package authority", SystemServiceSemanticContract.Status.PARTIAL);
        add(result, "devicepolicy", "Guest policy profile", "virtual user/package", "policy objects projected", "policy lease and session fence", SystemServiceSemanticContract.Status.PARTIAL);
        add(result, "notification", "Guest notification store", "guest id/tag/channel namespace", "callbacks and ids rebound", "channel/record lifecycle + observer fence", SystemServiceSemanticContract.Status.PARTIAL);
        add(result, "alarm", "Guest alarm store", "creator package/uid and PendingIntent token", "listener/PendingIntent delivery projected", "generation-aware cancellation", SystemServiceSemanticContract.Status.PARTIAL);
        add(result, "connectivity", "Guest network profile", "virtual network and callback owner", "NetworkCapabilities/LinkProperties projected", "callback registration fenced", SystemServiceSemanticContract.Status.PARTIAL);
        add(result, "wifi", "Guest Wi-Fi profile", "virtual device/package identity", "scan/result visibility projected", "scan callbacks scoped to session", SystemServiceSemanticContract.Status.PARTIAL);
        add(result, "telephony", "Guest telephony profile", "virtual device/subscriber identity", "slot and callback projection", "callback binder fenced", SystemServiceSemanticContract.Status.PARTIAL);
        add(result, "location", "Guest location profile", "provider identity + capability policy", "location callback projection", "lease registry closes listeners", SystemServiceSemanticContract.Status.PARTIAL);
        add(result, "sensor", "Guest sensor profile", "virtual device identity", "sensor event/listener projection", "listener lease lifecycle", SystemServiceSemanticContract.Status.PARTIAL);
        add(result, "camera", "Guest camera capability", "camera permission + virtual uid", "camera callback binder boundary", "capture lease teardown", SystemServiceSemanticContract.Status.PARTIAL);
        add(result, "media", "Guest media policy", "package/uid and media session owner", "codec/session callback projection", "session lifecycle + death fence", SystemServiceSemanticContract.Status.PARTIAL);
        add(result, "input", "Guest window/input ownership", "window token and process identity", "input device/event projection", "window session close fence", SystemServiceSemanticContract.Status.PARTIAL);
        add(result, "accessibility", "Guest accessibility profile", "virtual package/user", "service identity projection", "service binding lifecycle", SystemServiceSemanticContract.Status.PARTIAL);
        add(result, "autofill", "Guest autofill profile", "virtual package/user", "fill callback projection", "session close fence", SystemServiceSemanticContract.Status.PARTIAL);
        add(result, "storage", "Guest storage namespace", "package/user data root", "path and quota projection", "revision-aware cleanup", SystemServiceSemanticContract.Status.PARTIAL);
        add(result, "account", "Guest account authority", "account owner/package visibility", "Account and authenticator callback projection", "token/visibility/listener lifecycle", SystemServiceSemanticContract.Status.PARTIAL);
        add(result, "gms", "allowlisted GMS boundary only", "GMS/GSF package visibility + Guest uid", "basic status/account type projection", "broker only when runtime is real", SystemServiceSemanticContract.Status.DEFERRED);
        add(result, "webview", "Guest WebView profile/provider boundary", "provider allowlist + Guest data root", "provider/renderer/callback projection", "profile suffix + renderer teardown", SystemServiceSemanticContract.Status.PARTIAL);
        return result;
    }

    private static void add(Map<String, SystemServiceSemanticContract> target,
                            String service, String ownership, String identity,
                            String callback, String lifecycle,
                            SystemServiceSemanticContract.Status status) {
        SystemServiceSemanticContract contract = new SystemServiceSemanticContract(
                service, ownership, identity, callback, lifecycle, status);
        target.put(service, contract);
    }
}
