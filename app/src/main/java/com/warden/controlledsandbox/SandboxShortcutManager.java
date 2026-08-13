package com.warden.controlledsandbox;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.content.pm.PackageInfo;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;

import java.util.List;

/** Product shortcut contract: package plus exact virtual instance, never just the home screen. */
final class SandboxShortcutManager {
    static final String ACTION_LAUNCH_INSTANCE =
            "com.warden.controlledsandbox.action.LAUNCH_INSTANCE";
    static final String EXTRA_PACKAGE_NAME = "packageName";
    static final String EXTRA_VIRTUAL_USER_ID = "virtualUserId";

    private SandboxShortcutManager() { }

    static boolean create(Context context, SandboxItem item) {
        if (Build.VERSION.SDK_INT < 26) return false;
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        if (manager == null || !manager.isRequestPinShortcutSupported()) return false;
        Intent launch = new Intent(context, ShortcutActivity.class)
                .setAction(ACTION_LAUNCH_INSTANCE)
                .putExtra(EXTRA_PACKAGE_NAME, item.instance.packageName)
                .putExtra(EXTRA_VIRTUAL_USER_ID, item.instance.virtualUserId);
        ShortcutInfo shortcut = new ShortcutInfo.Builder(context, shortcutId(
                item.instance.packageName, item.instance.virtualUserId))
                .setShortLabel(item.record.label + " · " + item.instance.displayName)
                .setLongLabel(item.record.label + " · " + item.instance.displayName)
                .setIcon(Icon.createWithResource(context, R.drawable.ic_app_placeholder))
                .setIntent(launch)
                .build();
        return manager.requestPinShortcut(shortcut, null);
    }

    static void disable(Context context, String packageName, int virtualUserId) {
        if (Build.VERSION.SDK_INT < 25) return;
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        if (manager == null) return;
        manager.disableShortcuts(List.of(shortcutId(packageName, virtualUserId)),
                "Sandbox instance no longer exists");
    }

    static String shortcutId(String packageName, int virtualUserId) {
        return packageName + "#instance-" + virtualUserId;
    }

    static Drawable iconForRecord(Context context, SandboxRecord record) {
        try {
            PackageManager manager = context.getPackageManager();
            PackageInfo info = manager.getPackageArchiveInfo(record.apkPath,
                    PackageManager.GET_META_DATA);
            if (info != null && info.applicationInfo != null) {
                ApplicationInfo application = info.applicationInfo;
                application.sourceDir = record.apkPath;
                application.publicSourceDir = record.apkPath;
                Drawable icon = application.loadIcon(manager);
                if (icon != null) return icon;
            }
        } catch (Exception ignored) { }
        return context.getPackageManager().getDefaultActivityIcon();
    }
}
