package com.warden.controlledsandbox.fixture;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Package-neutral C2-T07 application-environment and long-tail RD fixture. */
@SuppressLint({"MissingPermission", "NewApi", "WrongConstant"})
public final class C2T07ApplicationEnvironmentActivity extends Activity {
    private static final String TAG = "CS_C2_T07";
    private static final int DEFAULT_LOOPS = 10;
    private static final int MAX_LOOPS = 50;
    private final String session = UUID.randomUUID().toString();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean destroyed;
    private ContentResolver resolver;
    private ContentObserver contentObserver;
    private LauncherApps launcherApps;
    private LauncherApps.Callback launcherCallback;
    private int contentChanges;
    private boolean observerRegistered;
    private boolean launcherCallbackRegistered;
    private String settingsKey;
    private String settingsUri;
    private int requestedUserId;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(new android.view.View(this));
        resolver = getContentResolver();
        mainHandler.postDelayed(this::runCampaign, 250L);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        cleanupLeases("destroy");
        Log.i(TAG, "C2_T07_DESTROY pid=" + Process.myPid() + " session=" + session);
        super.onDestroy();
    }

    private void runCampaign() {
        Bundle extras = getIntent() == null ? null : getIntent().getExtras();
        String mode = extras == null ? "full" : extras.getString("c2t07Mode", "full");
        int loops = extras == null ? DEFAULT_LOOPS
                : Math.max(1, Math.min(MAX_LOOPS, extras.getInt("c2t07Loops", DEFAULT_LOOPS)));
        requestedUserId = extras == null ? 0 : extras.getInt("c2t07User", 0);
        try {
            UserSnapshot user = readUserSnapshot();
            if ("arm".equals(mode)) {
                armLeases(user.handle);
                Log.i(TAG, "C2_T07_ARMED pid=" + Process.myPid() + " userId=" + user.userId
                        + " session=" + session);
                return;
            }
            if ("cleanup".equals(mode)) {
                cleanupLeases("explicit");
                Log.i(TAG, "C2_T07_CLEANUP_PASS pid=" + Process.myPid() + " session=" + session);
                return;
            }
            String profileHash = runEnvironmentProbe(user);
            runLongTailMatrix();
            runNegativeBoundaries();
            for (int loop = 1; loop <= loops && !destroyed; loop++) {
                readUserSnapshot();
                runShortcutProbe(loop);
                runUsageProbe(loop);
                runSettingsAndObserver(loop);
                if (loop == 1 || loop % 5 == 0) {
                    Log.i(TAG, "C2_T07_LOOP_PASS loop=" + loop + " profileHash=" + profileHash
                            + " observerChanges=" + contentChanges + " session=" + session);
                }
            }
            cleanupLeases("campaign");
            Log.i(TAG, "C2_T07_CAMPAIGN_PASS loops=" + loops + " profileHash=" + profileHash
                    + " observerChanges=" + contentChanges + " pid=" + Process.myPid()
                    + " session=" + session);
        } catch (Throwable error) {
            cleanupLeases("failure");
            Log.e(TAG, "C2_T07_CAMPAIGN_FAIL type=" + error.getClass().getName()
                    + " message=" + safeMessage(error) + " pid=" + Process.myPid()
                    + " session=" + session, error);
        }
    }

    private UserSnapshot readUserSnapshot() {
        UserManager manager = (UserManager) getSystemService(Context.USER_SERVICE);
        if (manager == null) throw new IllegalStateException("USER_MANAGER_UNAVAILABLE");
        UserHandle handle = Process.myUserHandle();
        if (handle == null) throw new IllegalStateException("VIRTUAL_USER_HANDLE_MISSING");
        int userId = requestedUserId;
        long serial = -1L;
        try {
            serial = manager.getSerialNumberForUser(handle);
        } catch (RuntimeException error) {
            logUserLimitation("getSerialNumberForUser", error);
        }
        String name = "";
        try {
            name = manager.getUserName();
        } catch (RuntimeException error) {
            // Android protects this host-facing getter behind MANAGE_USERS.  Do not turn the
            // expected fail-closed boundary into a campaign abort or expose the host name.
            logUserLimitation("getUserName", error);
        }
        List<UserHandle> profiles = Collections.emptyList();
        try {
            List<UserHandle> returnedProfiles = manager.getUserProfiles();
            if (returnedProfiles != null) profiles = returnedProfiles;
        } catch (RuntimeException error) {
            logUserLimitation("getUserProfiles", error);
        }
        boolean running = false;
        try {
            running = manager.isUserRunning(handle);
        } catch (RuntimeException error) {
            logUserLimitation("isUserRunning", error);
        }
        boolean unlocked = false;
        try {
            unlocked = manager.isUserUnlocked();
        } catch (RuntimeException error) {
            logUserLimitation("isUserUnlocked", error);
        }
        String profileHash = sha256(userId + "|" + serial + "|" + name + "|" + profiles.size());
        Log.i(TAG, "C2_T07_USER_RETURN userId=" + userId + " serial=" + serial
                + " nameHash=" + sha256(name == null ? "" : name)
                + " profiles=" + profiles.size() + " running=" + running
                + " unlocked=" + unlocked + " profileHash=" + profileHash
                + " package=" + getPackageName() + " session=" + session);
        return new UserSnapshot(handle, userId, serial, profileHash);
    }

    private void logUserLimitation(String method, RuntimeException error) {
        Log.i(TAG, "C2_T07_USER_NEGATIVE method=" + method + " status=KNOWN_LIMITATION type="
                + error.getClass().getName() + " message=" + safeMessage(error)
                + " session=" + session);
    }

    private String runEnvironmentProbe(UserSnapshot user) {
        runLauncherProbe(user.handle);
        runShortcutProbe(0);
        runWidgetProbe();
        runUsageProbe(0);
        runSettingsAndObserver(0);
        runStorageProbe();
        return user.profileHash;
    }

    private void runLauncherProbe(UserHandle user) {
        launcherApps = (LauncherApps) getSystemService(Context.LAUNCHER_APPS_SERVICE);
        if (launcherApps == null) {
            Log.i(TAG, "C2_T07_LAUNCHER_RETURN status=NOT_APPLICABLE reason=service_unavailable");
            return;
        }
        boolean enabled = launcherApps.isPackageEnabled(getPackageName(), user);
        List<LauncherActivityInfo> activities = launcherApps.getActivityList(getPackageName(), user);
        launcherCallback = new LauncherApps.Callback() {
            @Override public void onPackageRemoved(String packageName, UserHandle callbackUser) { }
            @Override public void onPackageAdded(String packageName, UserHandle callbackUser) { }
            @Override public void onPackageChanged(String packageName, UserHandle callbackUser) { }
            @Override public void onPackagesAvailable(String[] packages, UserHandle callbackUser,
                    boolean replacing) { }
            @Override public void onPackagesUnavailable(String[] packages, UserHandle callbackUser,
                    boolean replacing) { }
        };
        launcherApps.registerCallback(launcherCallback);
        launcherCallbackRegistered = true;
        launcherApps.unregisterCallback(launcherCallback);
        launcherCallbackRegistered = false;
        Log.i(TAG, "C2_T07_LAUNCHER_RETURN enabled=" + enabled + " activities="
                + (activities == null ? -1 : activities.size()) + " callbackRegistered=false"
                + " userId=" + requestedUserId + " session=" + session);
    }

    private void runShortcutProbe(int loop) {
        ShortcutManager manager = (ShortcutManager) getSystemService("shortcut");
        if (manager == null) {
            Log.i(TAG, "C2_T07_SHORTCUT_RETURN status=NOT_APPLICABLE loop=" + loop);
            return;
        }
        String id = "c2t07-" + session.replace('-', '_') + "-" + loop;
        Context shortcutContext = getApplicationContext();
        android.content.Intent shortcutIntent = new android.content.Intent(
                android.content.Intent.ACTION_VIEW);
        shortcutIntent.setComponent(new ComponentName(getPackageName(),
                C2T07ApplicationEnvironmentActivity.class.getName()));
        ShortcutInfo info = new ShortcutInfo.Builder(shortcutContext, id)
                .setShortLabel("C2-T07")
                .setLongLabel("C2-T07 application environment")
                .setIntent(shortcutIntent)
                .build();
        List<ShortcutInfo> before = manager.getDynamicShortcuts();
        boolean added = manager.addDynamicShortcuts(Collections.singletonList(info));
        List<ShortcutInfo> after = manager.getDynamicShortcuts();
        manager.reportShortcutUsed(id);
        manager.removeDynamicShortcuts(Collections.singletonList(id));
        List<ShortcutInfo> remaining = manager.getDynamicShortcuts();
        Log.i(TAG, "C2_T07_SHORTCUT_RETURN loop=" + loop + " added=" + added
                + " before=" + size(before) + " after=" + size(after)
                + " remaining=" + size(remaining) + " max=" + manager.getMaxShortcutCountPerActivity()
                + " session=" + session);
    }

    private void runWidgetProbe() {
        AppWidgetManager manager = (AppWidgetManager) getSystemService(Context.APPWIDGET_SERVICE);
        if (manager == null) {
            Log.i(TAG, "C2_T07_WIDGET_RETURN status=NOT_APPLICABLE");
            return;
        }
        int[] ids = manager.getAppWidgetIds(new ComponentName(this, C2T07ApplicationEnvironmentActivity.class));
        Log.i(TAG, "C2_T07_WIDGET_RETURN ids=" + Arrays.toString(ids)
                + " enabled=" + (!manager.getInstalledProviders().isEmpty()) + " session=" + session);
    }

    private void runUsageProbe(int loop) {
        UsageStatsManager manager = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        if (manager == null) {
            Log.i(TAG, "C2_T07_USAGE_RETURN status=NOT_APPLICABLE loop=" + loop);
            return;
        }
        long now = System.currentTimeMillis();
        try {
            invoke(manager, "reportEvent", getPackageName(), now,
                    UsageEvents.Event.MOVE_TO_FOREGROUND);
        } catch (Throwable error) {
            Log.i(TAG, "C2_T07_USAGE_NEGATIVE loop=" + loop + " status=NOT_SUPPORTED type="
                    + error.getClass().getName() + " message=" + safeMessage(error));
        }
        List<?> stats = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 60_000L, now + 1L);
        UsageEvents events = manager.queryEvents(now - 60_000L, now + 1L);
        int eventCount = 0;
        UsageEvents.Event event = new UsageEvents.Event();
        while (events != null && events.hasNextEvent()) {
            events.getNextEvent(event);
            eventCount++;
        }
        Log.i(TAG, "C2_T07_USAGE_RETURN loop=" + loop + " stats=" + size(stats)
                + " events=" + eventCount + " standby=" + manager.getAppStandbyBucket()
                + " package=" + getPackageName() + " session=" + session);
    }

    private void runSettingsAndObserver(int loop) {
        String key = "c2_t07_" + session.replace('-', '_');
        String value = "loop-" + loop + "-" + session;
        Uri uri = Settings.Secure.getUriFor(key);
        if (contentObserver == null) {
            contentObserver = new ContentObserver(mainHandler) {
                @Override public void onChange(boolean selfChange, Uri changedUri) {
                    contentChanges++;
                    Log.i(TAG, "C2_T07_CONTENT_OBSERVER_CALLBACK selfChange=" + selfChange
                            + " uri=" + String.valueOf(changedUri) + " count=" + contentChanges
                            + " session=" + session);
                }
            };
            resolver.registerContentObserver(uri, false, contentObserver);
            observerRegistered = true;
            settingsKey = key;
            settingsUri = uri.toString();
        }
        boolean securePut = Settings.Secure.putString(resolver, key, value);
        String secureRead = Settings.Secure.getString(resolver, key);
        boolean systemPut = Settings.System.putString(resolver, key, value);
        String systemRead = Settings.System.getString(resolver, key);
        resolver.notifyChange(uri, contentObserver);
        mainHandler.postDelayed(() -> { }, 10L);
        if (!securePut || !value.equals(secureRead) || !systemPut || !value.equals(systemRead)) {
            throw new IllegalStateException("VIRTUAL_SETTINGS_ROUND_TRIP_FAILED:" + loop);
        }
        Settings.Secure.putString(resolver, key, null);
        Settings.System.putString(resolver, key, null);
        Log.i(TAG, "C2_T07_SETTINGS_RETURN loop=" + loop + " securePut=" + securePut
                + " systemPut=" + systemPut + " secureReadHash=" + sha256(secureRead)
                + " systemReadHash=" + sha256(systemRead) + " key=" + key
                + " session=" + session);
        if (loop == 0) {
            try {
                Settings.Global.putString(resolver, key, value);
                throw new IllegalStateException("VIRTUAL_GLOBAL_SETTINGS_MUTATION_NOT_DENIED");
            } catch (SecurityException expected) {
                Log.i(TAG, "C2_T07_SETTINGS_GLOBAL_DENIED type=" + expected.getClass().getName()
                        + " message=" + safeMessage(expected) + " session=" + session);
            }
        }
    }

    private void runStorageProbe() {
        File file = new File(getFilesDir(), "c2-t07-" + session + ".txt");
        byte[] payload = ("guest-storage-" + session).getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(payload);
        } catch (Exception error) {
            throw new IllegalStateException("VIRTUAL_GUEST_STORAGE_WRITE_FAILED", error);
        }
        byte[] read = new byte[payload.length];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < read.length) {
                int count = input.read(read, offset, read.length - offset);
                if (count < 0) break;
                offset += count;
            }
        } catch (Exception error) {
            throw new IllegalStateException("VIRTUAL_GUEST_STORAGE_READ_FAILED", error);
        }
        boolean equal = Arrays.equals(payload, read);
        boolean deleted = file.delete();
        if (!equal || !deleted || file.exists()) {
            throw new IllegalStateException("VIRTUAL_GUEST_STORAGE_CLEANUP_FAILED");
        }
        Log.i(TAG, "C2_T07_STORAGE_RETURN bytes=" + payload.length + " roundTrip=" + equal
                + " deleted=" + deleted + " nameHash=" + sha256(file.getName())
                + " session=" + session);
    }

    private void runLongTailMatrix() {
        List<String> services = Arrays.asList(
                "biometric", "fingerprint", "device_policy", "autofill", "nfc", "usb", "print",
                "companiondevice", "sensor_privacy", "power", "vibrator", "search", "storagestats",
                "system_update", "contexthub", "persistent_data_block", "sms", "captioning",
                "graphicsstats");
        int observed = 0;
        int unavailable = 0;
        for (String service : services) {
            Object manager;
            try {
                manager = getSystemService(service);
            } catch (Throwable error) {
                Log.i(TAG, "C2_T07_LONGTAIL_NEGATIVE service=" + service
                        + " status=NOT_SUPPORTED type=" + error.getClass().getName()
                        + " message=" + safeMessage(error));
                continue;
            }
            if (manager == null) {
                unavailable++;
                Log.i(TAG, "C2_T07_LONGTAIL_NEGATIVE service=" + service
                        + " status=NOT_APPLICABLE reason=service_unavailable");
                continue;
            }
            String method = queryMethod(service);
            try {
                Object result = invokeQuery(manager, service, method);
                observed++;
                Log.i(TAG, "C2_T07_LONGTAIL_QUERY service=" + service + " method=" + method
                        + " status=OBSERVED resultType=" + (result == null ? "null" : result.getClass().getName())
                        + " resultHash=" + sha256(String.valueOf(result)));
            } catch (Throwable error) {
                Log.i(TAG, "C2_T07_LONGTAIL_NEGATIVE service=" + service + " method=" + method
                        + " status=NOT_SUPPORTED type=" + error.getClass().getName()
                        + " message=" + safeMessage(error));
            }
        }
        Log.i(TAG, "C2_T07_LONGTAIL_MATRIX services=" + services.size() + " observed=" + observed
                + " unavailable=" + unavailable + " explicitNegative="
                + (services.size() - observed - unavailable) + " session=" + session);
    }

    private void runNegativeBoundaries() {
        if (!getPackageName().equals("com.warden.controlledsandbox.fixture")) {
            throw new SecurityException("VIRTUAL_HOST_PACKAGE_IDENTITY_LEAK");
        }
        Log.i(TAG, "C2_T07_HOST_IDENTITY_GUARDED status=PASS package=" + getPackageName()
                + " uid=" + Process.myUid() + " pid=" + Process.myPid() + " session=" + session);
    }

    private void armLeases(UserHandle user) {
        runLauncherProbe(user);
        if (contentObserver == null) {
            settingsKey = "c2_t07_arm_" + session.replace('-', '_');
            Uri uri = Settings.Secure.getUriFor(settingsKey);
            contentObserver = new ContentObserver(mainHandler) { @Override public void onChange(boolean self, Uri uri) { contentChanges++; } };
            resolver.registerContentObserver(uri, false, contentObserver);
            observerRegistered = true;
            settingsUri = uri.toString();
        }
    }

    private void cleanupLeases(String reason) {
        try {
            if (launcherCallbackRegistered && launcherApps != null && launcherCallback != null) {
                launcherApps.unregisterCallback(launcherCallback);
            }
        } catch (Throwable error) {
            Log.i(TAG, "C2_T07_CLEANUP_NEGATIVE resource=launcher reason=" + reason
                    + " type=" + error.getClass().getName());
        }
        launcherCallbackRegistered = false;
        try {
            if (observerRegistered && resolver != null && contentObserver != null) {
                resolver.unregisterContentObserver(contentObserver);
            }
        } catch (Throwable error) {
            Log.i(TAG, "C2_T07_CLEANUP_NEGATIVE resource=content reason=" + reason
                    + " type=" + error.getClass().getName());
        }
        observerRegistered = false;
        Log.i(TAG, "C2_T07_CLEANUP reason=" + reason + " launcherRegistered="
                + launcherCallbackRegistered + " observerRegistered=" + observerRegistered
                + " settingsKey=" + (settingsKey == null ? "" : settingsKey)
                + " settingsUri=" + (settingsUri == null ? "" : settingsUri)
                + " session=" + session);
    }

    private static String queryMethod(String service) {
        switch (service) {
            case "biometric": return "canAuthenticate";
            case "fingerprint": return "isHardwareDetected";
            case "device_policy": return "isDeviceOwnerApp";
            case "autofill": return "isEnabled";
            case "nfc": return "isEnabled";
            case "usb": return "getDeviceList";
            case "print": return "getPrintJobs";
            case "companiondevice": return "getAssociations";
            case "sensor_privacy": return "isAllSensorPrivacyEnabled";
            case "power": return "isPowerSaveMode";
            case "vibrator": return "hasVibrator";
            case "search": return "getGlobalSearchActivity";
            case "storagestats": return "getTotalBytes";
            case "system_update": return "retrieveSystemUpdateInfo";
            case "contexthub": return "getContextHubs";
            case "persistent_data_block": return "getDataBlockSize";
            case "captioning": return "isEnabled";
            default: return "toString";
        }
    }

    private Object invokeQuery(Object manager, String service, String method) throws Throwable {
        if ("device_policy".equals(service)) return invoke(manager, method, getPackageName());
        if ("storagestats".equals(service)) {
            Class<?> storageManager = Class.forName("android.os.storage.StorageManager");
            Object uuid = storageManager.getField("UUID_DEFAULT").get(null);
            return invoke(manager, method, uuid);
        }
        return invoke(manager, method);
    }

    private static Object invoke(Object target, String name, Object... arguments) throws Throwable {
        Method selected = null;
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterTypes().length != arguments.length
                    || !Modifier.isPublic(method.getModifiers())) continue;
            if (compatible(method.getParameterTypes(), arguments)) {
                selected = method;
                break;
            }
        }
        if (selected == null) throw new UnsupportedOperationException("VIRTUAL_METHOD_NOT_FOUND:" + name);
        try {
            return selected.invoke(target, arguments);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            throw cause == null ? error : cause;
        }
    }

    private static boolean compatible(Class<?>[] types, Object[] arguments) {
        for (int i = 0; i < types.length; i++) {
            if (arguments[i] == null) continue;
            Class<?> type = types[i];
            if (type.isPrimitive()) type = boxed(type);
            if (!type.isInstance(arguments[i])) return false;
        }
        return true;
    }

    private static Class<?> boxed(Class<?> type) {
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return type;
    }

    private static int size(Object value) {
        if (value instanceof List<?>) return ((List<?>) value).size();
        if (value instanceof int[]) return ((int[]) value).length;
        return value == null ? 0 : 1;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte item : digest) out.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            return out.toString();
        } catch (Exception error) {
            return "hash-error";
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null ? "" : message.replace('\n', '_').replace('\r', '_');
    }

    private static final class UserSnapshot {
        final UserHandle handle;
        final int userId;
        final long serial;
        final String profileHash;
        UserSnapshot(UserHandle handle, int userId, long serial, String profileHash) {
            this.handle = handle;
            this.userId = userId;
            this.serial = serial;
            this.profileHash = profileHash;
        }
    }
}
