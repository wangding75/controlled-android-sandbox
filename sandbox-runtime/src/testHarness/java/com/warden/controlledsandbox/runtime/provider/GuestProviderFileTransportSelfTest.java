package com.warden.controlledsandbox.runtime.provider;

import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.FileNotFoundException;

public final class GuestProviderFileTransportSelfTest {
    public static void main(String[] args) throws Exception {
        openCloseAndTypedAsset();
        ownershipExpiryAndCapacity();
        System.out.println("PASS Guest Provider file transport self-test");
    }

    private static void openCloseAndTypedAsset() throws Exception {
        FakeProvider provider = new FakeProvider();
        GuestProviderFileTransport transport = new GuestProviderFileTransport();
        Bundle file = transport.openFile(provider, Uri.parse("content://authority/file"), "rw",
                "broker-file", "target-session", 4, 0, 100);
        ParcelFileDescriptor descriptor = file.getParcelable(RuntimeKeys.FILE_DESCRIPTOR);
        require(descriptor != null, "file descriptor returned");
        require(provider.lastMode.equals("rw"), "open mode forwarded");
        Bundle closed = transport.close("broker-file", "target-session", 4);
        require(closed.getString(RuntimeKeys.STATUS, "").equals("PROVIDER_FILE_CLOSED"), "file close status");
        require(descriptor.isClosed(), "Guest descriptor closed");

        Bundle typed = transport.openTypedAssetFile(provider, Uri.parse("content://authority/typed"),
                "text/plain", new Bundle(), "broker-typed", "target-session", 4, 0, 100);
        AssetFileDescriptor asset = typed.getParcelable(RuntimeKeys.ASSET_FILE_DESCRIPTOR);
        require(asset != null && typed.getString(RuntimeKeys.FILE_DESCRIPTOR_KIND, "").equals("TYPED_ASSET"),
                "typed asset returned");
        require(provider.lastMime.equals("text/plain"), "MIME type forwarded");
        transport.closeAll();
        require(asset.isClosed(), "typed asset closed on shutdown");
    }

    private static void ownershipExpiryAndCapacity() throws Exception {
        FakeProvider provider = new FakeProvider();
        GuestProviderFileTransport transport = new GuestProviderFileTransport();
        transport.openAssetFile(provider, Uri.parse("content://authority/asset"), "r",
                "asset", "owner", 2, 0, 10);
        boolean wrongOwner = false;
        try { transport.close("asset", "wrong", 2); }
        catch (SecurityException expected) { wrongOwner = true; }
        require(wrongOwner, "wrong owner rejected");
        require(transport.purgeExpired(11) == 1, "expired Guest lease removed");

        transport.openFile(provider, Uri.parse("content://authority/session"), "r",
                "session-token", "session-owner", 7, 0, 100);
        require(transport.closeSession("session-owner", 7) == 1, "Guest Session cleanup closes file");

        boolean modeDenied = false;
        try {
            transport.openFile(provider, Uri.parse("content://authority/file"), "invalid",
                    "invalid", "owner", 2, 0, 10);
        } catch (IllegalArgumentException expected) { modeDenied = true; }
        require(modeDenied, "invalid mode rejected before retention");

        for (int index = 0; index < GuestProviderFileTransport.MAX_ACTIVE_LEASES; index++) {
            transport.openFile(provider, Uri.parse("content://authority/file/" + index), "r",
                    "token-" + index, "owner-" + index, 1, 0, 100);
        }
        boolean exhausted = false;
        try {
            transport.openFile(provider, Uri.parse("content://authority/overflow"), "r",
                    "overflow", "owner", 1, 0, 100);
        } catch (IllegalStateException expected) { exhausted = true; }
        require(exhausted, "Guest file capacity enforced");
        require(transport.closeAll() == GuestProviderFileTransport.MAX_ACTIVE_LEASES,
                "all Guest leases closed");
    }

    private static final class FakeProvider extends ContentProvider {
        String lastMode = "";
        String lastMime = "";

        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri uri, String[] projection, String selection,
                                      String[] selectionArgs, String sortOrder) { return null; }
        @Override public String getType(Uri uri) { return "text/plain"; }
        @Override public Uri insert(Uri uri, ContentValues values) { return uri; }
        @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
        @Override public int update(Uri uri, ContentValues values, String selection,
                                    String[] selectionArgs) { return 0; }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            lastMode = mode;
            return new ParcelFileDescriptor();
        }
        @Override public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
            lastMode = mode;
            return new AssetFileDescriptor(new ParcelFileDescriptor(), 3L, 12L);
        }
        @Override public AssetFileDescriptor openTypedAssetFile(Uri uri, String mimeType, Bundle options)
                throws FileNotFoundException {
            lastMime = mimeType;
            return new AssetFileDescriptor(new ParcelFileDescriptor(), 5L, 20L);
        }
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
