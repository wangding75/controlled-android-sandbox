package com.warden.controlledsandbox.fixture.libraryconsumer;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** P1-03-only witness for CAS's shared-library loader and resource projection. */
public final class LibraryProjectionActivity extends Activity {
    private static final String PROVIDER_PACKAGE =
            "com.warden.controlledsandbox.fixture.libraryprovider";
    private static final String MARKER_CLASS = PROVIDER_PACKAGE + ".ProviderLibraryMarker";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            checkConsumerClassLoaderAndSharedFiles();
            Context provider = createPackageContext(PROVIDER_PACKAGE,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            checkProviderResources(provider);
            Log.i("CS_P1_03_FIXTURE", "VIRTUAL_LIBRARY_PROJECTION_PASS "
                    + "class=OK resource=OK asset=OK sharedLibraryFiles=OK");
        } catch (Throwable error) {
            Log.e("CS_P1_03_FIXTURE", "VIRTUAL_LIBRARY_PROJECTION_FAIL", error);
            throw new AssertionError("P1_03_LIBRARY_PROJECTION_FAILED", error);
        } finally {
            finish();
        }
    }

    private void checkConsumerClassLoaderAndSharedFiles() throws Exception {
        Class<?> marker = Class.forName(MARKER_CLASS, true, getClassLoader());
        Method value = marker.getMethod("value");
        if (!"P1_PROVIDER_CLASS_OK".equals(value.invoke(null))) {
            throw new IllegalStateException("P1_PROVIDER_CLASS_LOADER_VALUE");
        }
        ApplicationInfo info = getApplicationInfo();
        String[] files = info.sharedLibraryFiles;
        if (files == null || files.length == 0) {
            throw new IllegalStateException("P1_SHARED_LIBRARY_FILES_MISSING");
        }
        Set<String> canonical = new HashSet<>();
        for (String raw : files) {
            if (raw == null || raw.trim().isEmpty()) {
                throw new IllegalStateException("P1_SHARED_LIBRARY_FILE_EMPTY");
            }
            String path = new File(raw).getCanonicalPath();
            if (!new File(path).isFile()) {
                throw new IllegalStateException("P1_SHARED_LIBRARY_FILE_UNAVAILABLE");
            }
            if (!canonical.add(path)) {
                throw new IllegalStateException("P1_SHARED_LIBRARY_FILE_DUPLICATE");
            }
            if (path.contains("/data/user/") && path.contains(PROVIDER_PACKAGE)) {
                throw new IllegalStateException("P1_HOST_PROVIDER_PRIVATE_PATH_INTRODUCED");
            }
        }
    }

    private static void checkProviderResources(Context provider) throws Exception {
        int resourceId = provider.getResources().getIdentifier("p1_provider_resource", "string",
                PROVIDER_PACKAGE);
        if (resourceId == 0 || !"P1_PROVIDER_RESOURCE_OK".equals(provider.getString(resourceId))) {
            throw new IllegalStateException("P1_PROVIDER_RESOURCE_VALUE");
        }
        try (InputStream input = provider.getAssets().open("p1_provider_asset.txt")) {
            String asset = new String(readAll(input), StandardCharsets.UTF_8).trim();
            if (!"P1_PROVIDER_ASSET_OK".equals(asset)) {
                throw new IllegalStateException("P1_PROVIDER_ASSET_VALUE");
            }
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
