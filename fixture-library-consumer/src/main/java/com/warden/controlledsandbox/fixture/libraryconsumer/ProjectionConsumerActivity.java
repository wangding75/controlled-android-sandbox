package com.warden.controlledsandbox.fixture.libraryconsumer;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/** Native baseline for package projection and explicit absence/transport contracts. */
public final class ProjectionConsumerActivity extends Activity {
    private static final String PROVIDER_PACKAGE =
            "com.warden.controlledsandbox.fixture.libraryprovider";
    private static final String MARKER_CLASS = PROVIDER_PACKAGE + ".ProviderLibraryMarker";
    private static final Uri REENTRANT_URI = Uri.parse("content://" + PROVIDER_PACKAGE + ".reentrant");
    private static final Uri MISSING_PROVIDER_URI =
            Uri.parse("content://com.warden.controlledsandbox.fixture32.missing");

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            String projection = checkProjection();
            String providerAbsence = checkMissingProvider();
            checkMissingService();
            checkRemoteService();
            checkReentrantProvider();
            Log.i("CS_P1_03_FIXTURE", "VIRTUAL_LIBRARY_PROJECTION_PASS " + projection);
            Log.i("CS_P1_02_FIXTURE", "NATIVE_PROJECTION_PASS " + projection
                    + " missingService=NULL missingProvider=" + providerAbsence);
        } catch (Throwable error) {
            Log.e("CS_P1_02_FIXTURE", "NATIVE_PROJECTION_FAIL", error);
            throw new AssertionError("P1_02_NATIVE_PROJECTION_FAILED", error);
        } finally {
            finish();
        }
    }

    private String checkProjection() throws Exception {
        Context provider = createPackageContext(PROVIDER_PACKAGE,
                Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
        Class<?> marker = Class.forName(MARKER_CLASS, true, provider.getClassLoader());
        Method value = marker.getMethod("value");
        Object classValue = value.invoke(null);
        if (!"P1_PROVIDER_CLASS_OK".equals(classValue)) {
            throw new IllegalStateException("P1_PROVIDER_CLASS_VALUE=" + classValue);
        }
        int resourceId = provider.getResources().getIdentifier("p1_provider_resource", "string",
                PROVIDER_PACKAGE);
        String resource = resourceId == 0 ? null : provider.getString(resourceId);
        if (!"P1_PROVIDER_RESOURCE_OK".equals(resource)) {
            throw new IllegalStateException("P1_PROVIDER_RESOURCE_VALUE=" + resource);
        }
        String asset;
        try (InputStream input = provider.getAssets().open("p1_provider_asset.txt")) {
            asset = new String(readAll(input), StandardCharsets.UTF_8).trim();
        }
        if (!"P1_PROVIDER_ASSET_OK".equals(asset)) {
            throw new IllegalStateException("P1_PROVIDER_ASSET_VALUE=" + asset);
        }
        return "class=OK resource=OK asset=OK";
    }

    private String checkMissingProvider() {
        try {
            Bundle response = getContentResolver().call(MISSING_PROVIDER_URI, "probe", null, null);
            if (response != null) throw new IllegalStateException("P1_MISSING_PROVIDER_NON_NULL");
            return "NULL";
        } catch (IllegalArgumentException expectedAbsence) {
            return "IllegalArgumentException";
        } catch (SecurityException virtualizedAbsence) {
            String message = virtualizedAbsence.getMessage();
            if (message != null && message.startsWith(
                    "CONTENT_PROVIDER_AUTHORITY_NOT_VIRTUALIZED:")) {
                // CAS must deny an absent authority at the virtualization boundary rather than
                // accidentally falling through to an ordinary Host provider.  Native Android
                // represents the same absence as null/IllegalArgumentException above.
                return "NOT_VIRTUALIZED";
            }
            throw virtualizedAbsence;
        }
    }

    private void checkMissingService() {
        ComponentName missing = new ComponentName("com.warden.controlledsandbox.fixture32",
                "com.warden.controlledsandbox.fixture32.MissingService");
        ResolveInfo resolved = getPackageManager().resolveService(new Intent().setComponent(missing), 0);
        if (resolved != null) throw new IllegalStateException("P1_MISSING_SERVICE_RESOLVED");
    }

    private void checkRemoteService() {
        ComponentName expected = new ComponentName(PROVIDER_PACKAGE,
                PROVIDER_PACKAGE + ".RemoteFixtureService");
        ComponentName started = startService(new Intent().setComponent(expected));
        if (!expected.equals(started)) {
            throw new IllegalStateException("P1_REMOTE_SERVICE_START=" + started);
        }
    }

    private void checkReentrantProvider() {
        Bundle result = getContentResolver().call(REENTRANT_URI, "outer", "p1-02", null);
        String value = result == null ? null : result.getString("value");
        if (!"P1_REENTRANT_INNER_OK".equals(value)) {
            throw new IllegalStateException("P1_REENTRANT_RESULT=" + value);
        }
    }

    private static byte[] readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toByteArray();
    }
}
