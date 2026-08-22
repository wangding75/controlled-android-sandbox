package com.warden.controlledsandbox.fixture;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import org.json.JSONObject;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/** Device probe for the C2-T02 PMS, permission, AppOps and attribution boundary. */
@SuppressLint({"NewApi", "WrongConstant"})
public final class PmsPermissionAttributionProbeActivity extends Activity {
    private static final String TAG = "CS_FIXTURE";
    private static final String HOST_PACKAGE = "com.warden.controlledsandbox.debug";
    private static final String CAMERA = "android.permission.CAMERA";
    private static final String INTERNET = "android.permission.INTERNET";
    private static final String CAMERA_OP = "android:camera";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            JSONObject result = runProbe();
            Log.i(TAG, "C2_T02_PROBE_PASS " + result);
        } catch (Throwable error) {
            Log.e(TAG, "C2_T02_PROBE_FAIL " + error, error);
        } finally {
            new Handler(Looper.getMainLooper()).postDelayed(this::finish, 250L);
        }
    }

    private JSONObject runProbe() throws Exception {
        PackageManager packages = getPackageManager();
        ApplicationInfo application = packages.getApplicationInfo(getPackageName(), 0);
        PackageInfo packageInfo = packages.getPackageInfo(getPackageName(), 0);
        if (!getPackageName().equals(application.packageName)
                || !getPackageName().equals(packageInfo.packageName)) {
            throw new AssertionError("PMS_GUEST_PROJECTION_MISMATCH");
        }

        ResolveInfo resolved = packages.resolveActivity(
                new Intent().setComponent(new ComponentName(getPackageName(),
                        MainActivity.class.getName())), 0);
        if (resolved == null || resolved.activityInfo == null
                || !getPackageName().equals(resolved.activityInfo.packageName)) {
            throw new AssertionError("PMS_RESOLVE_GUEST_ACTIVITY_FAILED");
        }
        List<ResolveInfo> launcher = packages.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        boolean guestVisible = false;
        boolean hostVisible = false;
        for (ResolveInfo item : launcher) {
            if (item == null || item.activityInfo == null) continue;
            guestVisible |= getPackageName().equals(item.activityInfo.packageName);
            hostVisible |= HOST_PACKAGE.equals(item.activityInfo.packageName);
        }
        if (!guestVisible || hostVisible) throw new AssertionError("PMS_VISIBILITY_BOUNDARY_FAILED");
        try {
            packages.getApplicationInfo(HOST_PACKAGE, 0);
            throw new AssertionError("PMS_HOST_APPLICATION_VISIBLE");
        } catch (Exception expected) {
            // The static platform stubs do not declare NameNotFoundException on this method,
            // while the device API does. Preserve the strict runtime assertion when another
            // exception escapes.
            if (!(expected instanceof PackageManager.NameNotFoundException)) throw expected;
        }

        int contextCamera = checkSelfPermission(CAMERA);
        int packageCamera = packages.checkPermission(CAMERA, getPackageName());
        int hostPackageCamera = packages.checkPermission(CAMERA, HOST_PACKAGE);
        int contextInternet = checkSelfPermission(INTERNET);
        if (contextCamera != packageCamera) throw new AssertionError("PERMISSION_PROJECTION_MISMATCH");
        if (hostPackageCamera != PackageManager.PERMISSION_DENIED) {
            throw new AssertionError("PERMISSION_HOST_PACKAGE_VISIBLE");
        }

        // Keep the fixture source-compatible with the static platform stubs, which intentionally
        // omit the API31+ AppOps/Attribution methods. The device path still invokes the real
        // framework methods by their stable signatures.
        Object appOps = getSystemService("appops");
        if (appOps == null) throw new AssertionError("APPOPS_MANAGER_UNAVAILABLE");
        int physicalUid = Process.myUid();
        Class<?> appOpsType = Class.forName("android.app.AppOpsManager");
        int check = (Integer) invoke(appOpsType, appOps, "checkOpNoThrow",
                new Class<?>[] {String.class, int.class, String.class},
                CAMERA_OP, physicalUid, getPackageName());
        int note = (Integer) invoke(appOpsType, appOps, "noteOpNoThrow",
                new Class<?>[] {String.class, int.class, String.class},
                CAMERA_OP, physicalUid, getPackageName());
        int start = (Integer) invoke(appOpsType, appOps, "startOpNoThrow",
                new Class<?>[] {String.class, int.class, String.class},
                CAMERA_OP, physicalUid, getPackageName());
        invoke(appOpsType, appOps, "finishOp",
                new Class<?>[] {String.class, int.class, String.class},
                CAMERA_OP, physicalUid, getPackageName());
        int proxy = (Integer) invoke(appOpsType, appOps, "noteProxyOpNoThrow",
                new Class<?>[] {String.class, String.class}, CAMERA_OP, getPackageName());
        invoke(appOpsType, appOps, "checkPackage", new Class<?>[] {int.class, String.class},
                physicalUid, getPackageName());

        boolean hostAppOpsHidden = false;
        try {
            invoke(appOpsType, appOps, "noteProxyOpNoThrow",
                    new Class<?>[] {String.class, String.class}, CAMERA_OP, HOST_PACKAGE);
        } catch (SecurityException expected) {
            hostAppOpsHidden = String.valueOf(expected.getMessage())
                    .contains("VIRTUAL_APPOPS_HOST_PACKAGE_HIDDEN");
        }
        if (!hostAppOpsHidden) throw new AssertionError("APPOPS_HOST_PACKAGE_VISIBLE");

        JSONObject attribution = attributionJson();
        ContentResolver resolver = getContentResolver();
        Bundle callback = (Bundle) invoke(ContentResolver.class, resolver, "call",
                new Class<?>[] {android.net.Uri.class, String.class, String.class, Bundle.class},
                UriHolder.providerUri(getPackageName()), "c2-t02-attribution", null, null);
        if (callback == null || !getPackageName().equals(callback.getString("callingPackage"))) {
            throw new AssertionError("CALLBACK_PACKAGE_IDENTITY_MISMATCH:" + callback);
        }

        return new JSONObject()
                .put("packageName", getPackageName())
                .put("opPackageName", getOpPackageName())
                .put("physicalUid", physicalUid)
                .put("packageUid", application.uid)
                .put("packageRevision", packageInfo.versionCode)
                .put("resolvedActivity", resolved.activityInfo.name)
                .put("launcherCount", launcher == null ? 0 : launcher.size())
                .put("contextCamera", contextCamera)
                .put("packageCamera", packageCamera)
                .put("hostPackageCamera", hostPackageCamera)
                .put("contextInternet", contextInternet)
                .put("appOpsCheck", check)
                .put("appOpsNote", note)
                .put("appOpsStart", start)
                .put("appOpsProxy", proxy)
                .put("attribution", attribution)
                .put("callback", new JSONObject().put("callingPackage",
                        callback.getString("callingPackage"))
                        .put("callingAttributionPackage",
                                callback.getString("callingAttributionPackage"))
                        .put("callingAttributionUid",
                                callback.getInt("callingAttributionUid", -1)));
    }

    private JSONObject attributionJson() throws Exception {
        JSONObject result = new JSONObject();
        if (Build.VERSION.SDK_INT < 31) return result.put("api", Build.VERSION.SDK_INT);
        Object source = invoke(getClass(), this, "getAttributionSource", new Class<?>[0]);
        String sourcePackage = source == null ? null : stringProperty(source, "getPackageName");
        if (source == null || !getPackageName().equals(sourcePackage)) {
            throw new AssertionError("ATTRIBUTION_SOURCE_IDENTITY_MISMATCH:" + source);
        }
        result.put("api", Build.VERSION.SDK_INT)
                .put("packageName", sourcePackage)
                .put("uid", intProperty(source, "getUid"))
                .put("tag", stringProperty(source, "getAttributionTag"));
        Object next = invoke(source.getClass(), source, "getNext", new Class<?>[0]);
        if (next != null) result.put("nextPackageName", stringProperty(next, "getPackageName"))
                .put("nextUid", intProperty(next, "getUid"));
        return result;
    }

    private static Object invoke(Class<?> owner, Object receiver, String name,
                                 Class<?>[] parameterTypes, Object... arguments) throws Exception {
        Method method = owner.getMethod(name, parameterTypes);
        try {
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            if (cause instanceof Exception) throw (Exception) cause;
            throw error;
        }
    }

    private static String stringProperty(Object receiver, String method) throws Exception {
        return (String) invoke(receiver.getClass(), receiver, method, new Class<?>[0]);
    }

    private static int intProperty(Object receiver, String method) throws Exception {
        return (Integer) invoke(receiver.getClass(), receiver, method, new Class<?>[0]);
    }

    private static final class UriHolder {
        private static android.net.Uri providerUri(String packageName) {
            return android.net.Uri.parse("content://" + packageName + ".provider");
        }
    }
}
