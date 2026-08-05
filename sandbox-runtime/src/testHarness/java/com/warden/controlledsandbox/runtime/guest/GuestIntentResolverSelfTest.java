package com.warden.controlledsandbox.runtime.guest;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Bundle;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.util.List;

/** Regression evidence for Guest-scoped explicit and implicit Intent resolution. */
public final class GuestIntentResolverSelfTest {
    public static void main(String[] args) {
        FakePackageManager packageManager = new FakePackageManager();
        GuestPackageSpec spec = new GuestPackageSpec(specBundle());
        GuestIntentResolver resolver = new GuestIntentResolver(spec, packageManager);

        Intent activity = new Intent("guest.action.VIEW")
                .setPackage("guest.pkg")
                .setDataAndType(Uri.parse("content://guest.authority/items/7"), "text/plain")
                .addCategory("guest.category.DEFAULT")
                .putExtra("payload", "value")
                .putExtra(RuntimeKeys.SESSION_ID, "attacker-controlled")
                .addFlags(0x40);
        GuestIntentResolver.Target activityTarget = resolver.resolveOne(
                activity, GuestIntentResolver.Kind.ACTIVITY);
        require("guest.pkg.MainActivity".equals(activityTarget.className()),
                "implicit Activity resolved inside virtual package");
        require(packageManager.lastActivityFlags == PackageManager.MATCH_DEFAULT_ONLY,
                "Activity resolution requires default category semantics");
        Bundle activityRequest = resolver.request(activity, activityTarget);
        require("guest.action.VIEW".equals(
                        activityRequest.getString(ComponentOperations.ACTION, "")),
                "Intent action serialized");
        require("content://guest.authority/items/7".equals(
                        activityRequest.getString(RuntimeKeys.URI, "")),
                "Intent data URI serialized");
        require("text/plain".equals(
                        activityRequest.getString(RuntimeKeys.BROADCAST_MIME_TYPE, "")),
                "Intent MIME serialized");
        require(activityRequest.getStringArrayList(RuntimeKeys.BROADCAST_CATEGORIES)
                        .contains("guest.category.DEFAULT"),
                "Intent categories serialized");
        Bundle wireExtras = activityRequest.getBundle(RuntimeKeys.INTENT_EXTRAS);
        require(wireExtras != null && "value".equals(wireExtras.getString("payload", "")),
                "Intent extras serialized in isolated envelope");
        require(!activityRequest.keySet().contains("payload")
                        && !activityRequest.keySet().contains(RuntimeKeys.SESSION_ID),
                "Intent extras cannot overwrite Broker control fields");
        Intent decoded = com.warden.controlledsandbox.runtime.protocol.RuntimeIntentWireCodec.decode(
                activityRequest);
        require("guest.action.VIEW".equals(decoded.getAction())
                        && "value".equals(decoded.getStringExtra("payload"))
                        && "attacker-controlled".equals(decoded.getStringExtra(RuntimeKeys.SESSION_ID))
                        && decoded.getFlags() == 0x40
                        && "content://guest.authority/items/7".equals(decoded.getData().toString())
                        && "text/plain".equals(decoded.getType()),
                "Intent action/data/type/flags/extras round-trip without protocol collision");

        Intent service = new Intent("guest.action.SYNC").setPackage("guest.pkg");
        require("guest.pkg.SyncService".equals(resolver.resolveOne(
                        service, GuestIntentResolver.Kind.SERVICE).className()),
                "implicit Service resolved inside virtual package");

        Intent broadcast = new Intent("guest.action.NOTIFY").setPackage("guest.pkg");
        List<GuestIntentResolver.Target> receivers = resolver.resolveReceivers(broadcast);
        require(receivers.size() == 2
                        && "guest.pkg.HighReceiver".equals(receivers.get(0).className())
                        && "guest.pkg.LowReceiver".equals(receivers.get(1).className()),
                "implicit Receiver order follows virtual PackageManager priority");

        Intent explicit = new Intent().setComponent(
                new ComponentName("guest.pkg", "guest.pkg.MainActivity"));
        require("guest.pkg.MainActivity".equals(resolver.resolveOne(
                        explicit, GuestIntentResolver.Kind.ACTIVITY).className()),
                "explicit Guest component remains virtual");

        boolean crossPackageDenied = false;
        try {
            resolver.resolveOne(new Intent("guest.action.VIEW").setPackage("other.pkg"),
                    GuestIntentResolver.Kind.ACTIVITY);
        } catch (SecurityException expected) {
            crossPackageDenied = expected.getMessage().contains("CROSS_PACKAGE_INTENT_DENIED");
        }
        require(crossPackageDenied, "cross-package implicit Intent denied");

        boolean crossComponentDenied = false;
        try {
            resolver.resolveOne(new Intent().setComponent(
                            new ComponentName("other.pkg", "other.pkg.Activity")),
                    GuestIntentResolver.Kind.ACTIVITY);
        } catch (SecurityException expected) {
            crossComponentDenied = expected.getMessage().contains("CROSS_PACKAGE_COMPONENT_DENIED");
        }
        require(crossComponentDenied, "cross-package explicit component denied");

        boolean missingActivity = false;
        try {
            resolver.resolveOne(new Intent("guest.action.MISSING"),
                    GuestIntentResolver.Kind.ACTIVITY);
        } catch (ActivityNotFoundException expected) {
            missingActivity = true;
        }
        require(missingActivity, "unresolved Activity fails closed");
        System.out.println("PASS Guest implicit Intent resolver self-test");
    }

    private static final class FakePackageManager extends PackageManager {
        int lastActivityFlags;

        @Override public ResolveInfo resolveActivity(Intent intent, int flags) {
            lastActivityFlags = flags;
            if (intent.getComponent() != null
                    && "guest.pkg.MainActivity".equals(intent.getComponent().getClassName())) {
                return activity("guest.pkg.MainActivity", "guest.pkg");
            }
            if ("guest.action.VIEW".equals(intent.getAction())) {
                return activity("guest.pkg.MainActivity", "guest.pkg");
            }
            return null;
        }

        @Override public ResolveInfo resolveService(Intent intent, int flags) {
            if (!"guest.action.SYNC".equals(intent.getAction())) return null;
            ResolveInfo result = new ResolveInfo();
            ServiceInfo info = new ServiceInfo();
            info.packageName = "guest.pkg";
            info.name = "guest.pkg.SyncService";
            info.processName = "guest.pkg:sync";
            result.serviceInfo = info;
            return result;
        }

        @Override public List<ResolveInfo> queryBroadcastReceivers(Intent intent, int flags) {
            if (!"guest.action.NOTIFY".equals(intent.getAction())) return List.of();
            ResolveInfo high = activity("guest.pkg.HighReceiver", "guest.pkg:receiver");
            high.priority = 100;
            ResolveInfo low = activity("guest.pkg.LowReceiver", "guest.pkg");
            low.priority = 10;
            return List.of(high, low);
        }

        private static ResolveInfo activity(String className, String processName) {
            ResolveInfo result = new ResolveInfo();
            ActivityInfo info = new ActivityInfo();
            info.packageName = "guest.pkg";
            info.name = className;
            info.processName = processName;
            result.activityInfo = info;
            return result;
        }
    }

    private static Bundle specBundle() {
        Bundle input = new Bundle();
        input.putInt(RuntimeKeys.PROTOCOL, 3);
        input.putString(RuntimeKeys.SESSION_ID, "session-intent");
        input.putLong(RuntimeKeys.GENERATION, 2L);
        input.putString(RuntimeKeys.PACKAGE_NAME, "guest.pkg");
        input.putInt(RuntimeKeys.VIRTUAL_USER_ID, 2);
        input.putInt(RuntimeKeys.VIRTUAL_UID, 12002);
        input.putInt(RuntimeKeys.PROCESS_SLOT, 0);
        input.putString(RuntimeKeys.PROCESS_NAME, "guest.pkg");
        input.putString(RuntimeKeys.APK_PATH, "/tmp/base.apk");
        String sha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        input.putString(RuntimeKeys.APK_SHA256, sha);
        input.putString(RuntimeKeys.BASE_APK_SHA256, sha);
        input.putLong(RuntimeKeys.APK_VERSION_CODE, 1L);
        input.putString(RuntimeKeys.PACKAGE_REVISION, "v1:" + sha);
        input.putString(RuntimeKeys.DATA_ROOT, "/tmp/guest");
        input.putParcelable(RuntimeKeys.PACKAGE_STATE,
                new VirtualPackageStateSnapshot("guest.pkg", 2, "Guest", "1.0", 1L,
                        sha, sha, "guest.pkg.MainActivity", "", true,
                        List.of(), List.of(), List.of()));
        return input;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
