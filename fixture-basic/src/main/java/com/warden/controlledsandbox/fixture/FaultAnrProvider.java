package com.warden.controlledsandbox.fixture;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.Log;

/** Provider query stall long enough to be an ANR. Do not shorten to avoid ANR. */
public final class FaultAnrProvider extends ContentProvider {
    @Override public boolean onCreate() {
        return true;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        Log.i("CS_FAULT", "ANR_PROVIDER_BEGIN uri=" + uri);
        try {
            Thread.sleep(25_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        Log.i("CS_FAULT", "ANR_PROVIDER_END");
        return new MatrixCursor(new String[]{"_id"});
    }

    @Override public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.controlledsandbox.fault";
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) {
        return 0;
    }
}
