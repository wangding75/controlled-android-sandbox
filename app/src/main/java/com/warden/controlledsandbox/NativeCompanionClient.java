package com.warden.controlledsandbox;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import com.warden.controlledsandbox.contract.INativeAbiCompanion;
import com.warden.controlledsandbox.contract.NativeCompanionRequest;
import com.warden.controlledsandbox.contract.NativeCompanionResult;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Explicit, signature-permission protected client for the independently packaged 32-bit process. */
final class NativeCompanionClient implements AutoCloseable {
    private static final String RELEASE_PACKAGE = "com.warden.controlledsandbox.companion32";
    private static final String DEBUG_PACKAGE = RELEASE_PACKAGE + ".debug";
    private static final String SERVICE_CLASS =
            "com.warden.controlledsandbox.companion32.NativeCompanionService";
    private static final int PROTOCOL = 1;
    private static final long BIND_TIMEOUT_SECONDS = 5L;

    private final Context context;
    private final SecureRandom random = new SecureRandom();
    private final CountDownLatch connected = new CountDownLatch(1);
    private volatile INativeAbiCompanion companion;
    private volatile boolean bound;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            companion = INativeAbiCompanion.Stub.asInterface(service);
            connected.countDown();
        }
        @Override public void onServiceDisconnected(ComponentName name) { companion = null; }
        @Override public void onBindingDied(ComponentName name) { companion = null; }
        @Override public void onNullBinding(ComponentName name) { companion = null; connected.countDown(); }
    };

    NativeCompanionClient(Context context) {
        this.context = context.getApplicationContext();
    }

    NativeCompanionResult probe(SandboxRecord record, int virtualUserId) throws Exception {
        if (!NativeAbiRoutePlanner.requiresCompanion(record.nativeAbi)) {
            throw new IllegalArgumentException("NATIVE_COMPANION_NOT_REQUIRED:" + record.nativeAbi);
        }
        INativeAbiCompanion service = requireCompanion();
        byte[] nonce = new byte[32];
        random.nextBytes(nonce);
        NativeCompanionRequest request = new NativeCompanionRequest(
                PROTOCOL,
                "preflight-" + UUID.randomUUID(),
                1L,
                virtualUserId,
                record.packageName,
                record.sha256,
                nonce,
                record.nativeAbi,
                NativeCompanionRequest.OP_PROBE);
        NativeCompanionResult result = service.execute(request);
        if (result == null) throw new IllegalStateException("NATIVE_COMPANION_EMPTY_RESULT");
        return result;
    }

    private INativeAbiCompanion requireCompanion() throws Exception {
        INativeAbiCompanion current = companion;
        if (current != null) return current;
        synchronized (this) {
            if (!bound) {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(companionPackage(), SERVICE_CLASS));
                bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
                if (!bound) connected.countDown();
            }
        }
        if (!connected.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS) || companion == null) {
            throw new IllegalStateException("NATIVE_COMPANION_UNAVAILABLE:" + companionPackage());
        }
        return companion;
    }

    private String companionPackage() {
        String hostPackage = context.getPackageName();
        return hostPackage != null && hostPackage.endsWith(".debug") ? DEBUG_PACKAGE : RELEASE_PACKAGE;
    }

    @Override public void close() {
        if (bound) {
            try { context.unbindService(connection); } catch (RuntimeException ignored) { }
        }
        bound = false;
        companion = null;
    }
}
