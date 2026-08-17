package com.warden.controlledsandbox;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Debug/test-only Broker. Session-scoped allowlist. Not an arbitrary file or
 * network proxy. Production Host/Broker policy is not used.
 */
public final class NativeEnforcementBroker extends Binder {
    static final String DESCRIPTOR = "com.warden.controlledsandbox.debug.INativeEnforcementBroker";
    static final int TX_READ_FS = IBinder.FIRST_CALL_TRANSACTION;
    static final int TX_NET = IBinder.FIRST_CALL_TRANSACTION + 1;

    private static final String TAG = "CS_NATIVE_ENF";

    private final String sessionToken;
    private final String fsCapabilityId;
    private final String netCapabilityId;
    private final File sentinelFile;
    private final String sentinelToken;
    private final String loopbackHost;
    private final int loopbackPort;
    private final String networkNonce;
    private final Set<String> allowlist;

    NativeEnforcementBroker(String sessionToken, String fsCapabilityId, String netCapabilityId,
            File sentinelFile, String sentinelToken, String loopbackHost, int loopbackPort,
            String networkNonce) {
        this.sessionToken = sessionToken;
        this.fsCapabilityId = fsCapabilityId;
        this.netCapabilityId = netCapabilityId;
        this.sentinelFile = sentinelFile;
        this.sentinelToken = sentinelToken;
        this.loopbackHost = loopbackHost;
        this.loopbackPort = loopbackPort;
        this.networkNonce = networkNonce;
        Set<String> ids = new HashSet<>();
        ids.add(fsCapabilityId);
        ids.add(netCapabilityId);
        this.allowlist = Collections.unmodifiableSet(ids);
    }

    static String newOpaqueId(String prefix) {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder builder = new StringBuilder(prefix);
        for (byte value : bytes) builder.append(String.format("%02x", value));
        return builder.toString();
    }

    boolean isAllowed(String capabilityId) {
        return capabilityId != null && allowlist.contains(capabilityId);
    }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        try {
            data.enforceInterface(DESCRIPTOR);
            String session = data.readString();
            String capabilityId = data.readString();
            if (sessionToken == null || !sessionToken.equals(session)) {
                writeDenied(reply, "SESSION_MISMATCH");
                return true;
            }
            if (capabilityId == null || capabilityId.isEmpty() || looksLikePath(capabilityId)) {
                writeDenied(reply, "CAPABILITY_REJECTED");
                return true;
            }
            if (!isAllowed(capabilityId)) {
                writeDenied(reply, "CAPABILITY_MISMATCH");
                return true;
            }
            if (code == TX_READ_FS) {
                if (!fsCapabilityId.equals(capabilityId)) {
                    writeDenied(reply, "CAPABILITY_TYPE_MISMATCH");
                    return true;
                }
                String body = readSentinel();
                if (body == null || !body.equals(sentinelToken)) {
                    writeDenied(reply, "SENTINEL_READ_FAILED");
                    return true;
                }
                reply.writeNoException();
                reply.writeInt(1);
                reply.writeString(body);
                return true;
            }
            if (code == TX_NET) {
                if (!netCapabilityId.equals(capabilityId)) {
                    writeDenied(reply, "CAPABILITY_TYPE_MISMATCH");
                    return true;
                }
                String body = brokerConnect();
                reply.writeNoException();
                reply.writeInt(1);
                reply.writeString(body);
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        } catch (Exception error) {
            Log.e(TAG, "broker transact failed", error);
            writeDenied(reply, "BROKER_ERROR");
            return true;
        }
    }

    private static boolean looksLikePath(String value) {
        return value.indexOf('/') >= 0 || value.indexOf('\\') >= 0 || value.contains("..");
    }

    private static void writeDenied(Parcel reply, String reason) {
        reply.writeNoException();
        reply.writeInt(0);
        reply.writeString(reason);
    }

    private String readSentinel() throws Exception {
        try (FileInputStream input = new FileInputStream(sentinelFile)) {
            byte[] buffer = new byte[256];
            int n = input.read(buffer);
            if (n <= 0) return "";
            return new String(buffer, 0, n, StandardCharsets.UTF_8);
        }
    }

    private String brokerConnect() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByName(loopbackHost), loopbackPort),
                    2000);
            socket.setSoTimeout(2000);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[128];
            int n = socket.getInputStream().read(buffer);
            if (n > 0) output.write(buffer, 0, n);
            return output.toString(StandardCharsets.UTF_8.name()).trim();
        }
    }
}
