package com.warden.controlledsandbox.fixture.libraryprovider;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

/** Tests an outer provider call that re-enters the same authority for an inner call. */
public final class ReentrantFixtureProvider extends ContentProvider {
    private static final String AUTHORITY =
            "com.warden.controlledsandbox.fixture.libraryprovider.reentrant";
    private static final Uri URI = Uri.parse("content://" + AUTHORITY);

    @Override public boolean onCreate() {
        Log.i("CS_P1_02_FIXTURE", "REENTRANT_PROVIDER_CREATE pid="
                + android.os.Process.myPid());
        return true;
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if ("inner".equals(method)) {
            Bundle result = new Bundle();
            result.putString("value", "P1_REENTRANT_INNER_OK");
            return result;
        }
        if ("outer".equals(method)) {
            Bundle inner = getContext().getContentResolver().call(URI, "inner", arg, extras);
            String value = inner == null ? null : inner.getString("value");
            if (!"P1_REENTRANT_INNER_OK".equals(value)) {
                throw new IllegalStateException("P1_REENTRANT_INNER_RESULT=" + value);
            }
            Log.i("CS_P1_02_FIXTURE", "REENTRANT_PROVIDER_PASS pid="
                    + android.os.Process.myPid());
            Bundle result = new Bundle();
            result.putString("value", value);
            return result;
        }
        throw new IllegalArgumentException("P1_REENTRANT_UNKNOWN_METHOD=" + method);
    }

    @Override public String getType(Uri uri) { return "vnd.android.cursor.item/p1-reentrant"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) { return 0; }
}
