package com.warden.controlledsandbox;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.util.Log;
import com.warden.controlledsandbox.contract.HostileAdmissionSnapshot;
import com.warden.controlledsandbox.contract.HostileCapabilityRequest;
import com.warden.controlledsandbox.contract.HostileCapabilityResult;
import com.warden.controlledsandbox.contract.HostileCapabilitySnapshot;
import com.warden.controlledsandbox.contract.IHostileCapabilityBroker;
import com.warden.controlledsandbox.contract.NativeExecutionProfile;
import com.warden.controlledsandbox.runtime.hostile.HostileCapabilityBrokerStub;
import com.warden.controlledsandbox.runtime.hostile.HostileCapabilityRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Production-API RD campaign. Isolated child + production Broker + hostile seccomp. */
public final class HostileProductionCampaign {
    private static final String TAG = "CS_NATIVE_HOSTILE";

    private HostileProductionCampaign() { }

    public static JSONObject run(Context context) throws Exception {
        JSONObject result = new JSONObject();
        result.put("schema", "t57-r03-p0a-03-hostile");
        result.put("profile", NativeExecutionProfile.ISOLATED_HOSTILE);
        JSONObject host = new JSONObject();
        host.put("uid", Process.myUid());
        host.put("pid", Process.myPid());
        host.put("processName", android.app.Application.getProcessName());
        result.put("host", host);

        String session = "hsess-" + System.nanoTime();
        long generation = 7L;
        String pkg = context.getPackageName();
        HostileAdmissionSnapshot admission = new HostileAdmissionSnapshot(
                NativeExecutionProfile.ISOLATED_HOSTILE, pkg, 0,
                pkg + ":native_enf_iso", NativeEnforcementNative.compiledAbi(),
                generation, session, HostileAdmissionSnapshot.NETWORK_BROKER_ONLY,
                HostileAdmissionSnapshot.PROCESS_ISOLATED_UID);
        HostileCapabilityRegistry registry = new HostileCapabilityRegistry();
        registry.admit(admission);

        File root = new File(context.getFilesDir(), "hostile/" + session);
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IllegalStateException("cannot create hostile sentinel dir");
        }
        File sentinel = new File(root, "sentinel.txt");
        String token = "htok-" + Long.toHexString(System.nanoTime());
        try (FileOutputStream output = new FileOutputStream(sentinel)) {
            output.write(token.getBytes(StandardCharsets.UTF_8));
        }
        String nonce = "hnet-" + Long.toHexString(System.nanoTime());
        ServerSocket server = new ServerSocket();
        AtomicBoolean accept = new AtomicBoolean(true);
        try {
            server.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8);
            server.setSoTimeout(1000);
            int port = server.getLocalPort();
            Thread acceptor = new Thread(() -> acceptLoop(server, accept, nonce), "hostile-loopback");
            acceptor.setDaemon(true);
            acceptor.start();

            HostileCapabilitySnapshot fs = registry.issueReadResource(admission, "sentinel",
                    sentinel, token, System.currentTimeMillis() + 120_000L);
            HostileCapabilitySnapshot net = registry.issueNetwork(admission, "127.0.0.1", port,
                    nonce, System.currentTimeMillis() + 120_000L);
            HostileCapabilitySnapshot fd = registry.issueFd(admission, sentinel,
                    System.currentTimeMillis() + 120_000L);
            IBinder broker = new HostileCapabilityBrokerStub(registry);
            BindState bind = bindIsolated(context);
            try {
                if (bind.binder == null) {
                    result.put("pocValid", false);
                    result.put("error", "ISOLATED_BIND_FAILED:" + bind.error);
                    persist(context, result);
                    return result;
                }
                JSONObject isolated = identity(bind.binder);
                result.put("isolated", isolated);
                boolean distinct = isolated.optInt("uid", Process.myUid()) != Process.myUid()
                        && isolated.optBoolean("isolated", false);
                result.put("isolatedUidDistinct", distinct);
                result.put("pocValid", distinct);
                if (!distinct) {
                    result.put("error", "ISOLATED_UID_NOT_ASSIGNED");
                    persist(context, result);
                    return result;
                }
                JSONObject child = runChild(bind.binder, broker, session, generation, pkg,
                        fs.tokenId(), net.tokenId(), fd.tokenId(),
                        sentinel.getAbsolutePath(), "127.0.0.1", port, token, nonce, registry);
                result.put("child", child);
                result.put("cases", child.optJSONArray("cases"));
                result.put("fs_conclusion", child.optString("fs_conclusion"));
                result.put("net_conclusion", child.optString("net_conclusion"));
                result.put("seccomp_conclusion", child.optString("seccomp_conclusion"));
                result.put("BROKER_FS_CAPABILITY", child.optString("BROKER_FS_CAPABILITY"));
                result.put("BROKER_NET_CAPABILITY", child.optString("BROKER_NET_CAPABILITY"));
            } finally {
                context.unbindService(bind.connection);
                registry.revokeSession(session, generation);
            }
        } finally {
            accept.set(false);
            try { server.close(); } catch (Exception ignored) { }
            deleteRecursively(root);
        }
        persist(context, result);
        persistSplit(context, result);
        return result;
    }

    private static JSONObject runChild(IBinder child, IBinder broker, String session,
            long generation, String pkg, String fsCap, String netCap, String fdCap,
            String realPath, String host, int port, String sentinelToken, String nonce,
            HostileCapabilityRegistry registry) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(NativeEnforcementIsolatedService.DESCRIPTOR);
            data.writeStrongBinder(broker);
            data.writeString(session);
            data.writeString(fsCap);
            data.writeString(netCap);
            data.writeString(realPath);
            data.writeString(host);
            data.writeInt(port);
            data.writeLong(generation);
            data.writeString(pkg);
            data.writeString(fdCap);
            data.writeInt(1); // production broker
            child.transact(NativeEnforcementIsolatedService.TX_RUN, data, reply, 0);
            reply.readException();
            JSONObject out = new JSONObject(reply.readString());
            classify(out, sentinelToken, nonce, registry, session, generation, pkg, fsCap);
            return out;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static void classify(JSONObject out, String sentinelToken, String nonce,
            HostileCapabilityRegistry registry, String session, long generation, String pkg,
            String fsCap) throws Exception {
        JSONArray cases = out.optJSONArray("cases");
        if (cases == null) cases = new JSONArray();
        HostileCapabilityResult stale = new HostileCapabilityBrokerStub(registry).readResource(
                new HostileCapabilityRequest(fsCap, session, generation + 1, pkg, 0,
                        HostileCapabilityRequest.OP_READ_RESOURCE));
        JSONObject staleCase = new JSONObject();
        staleCase.put("id", "NATIVE-ENF-STALE-001");
        staleCase.put("status", stale.successful() ? "UNEXPECTED_ALLOW" : "DENIED");
        staleCase.put("errorType", stale.errorType());
        cases.put(staleCase);
        out.put("cases", cases);
        boolean fsDenied = statusIs(cases, "NATIVE-ENF-FS-001", "DENIED_BY_KERNEL_POLICY")
                && statusIs(cases, "NATIVE-ENF-FS-002", "DENIED_BY_KERNEL_POLICY");
        boolean fsBroker = statusIs(cases, "NATIVE-ENF-FS-004", "PASS_CAPABILITY");
        out.put("fs_conclusion", fsDenied && fsBroker ? "PROVEN" : "PARTIAL");
        out.put("BROKER_FS_CAPABILITY", fsDenied && fsBroker ? "PROVEN_ON_RD" : "PARTIAL");
        boolean netDenied = statusIs(cases, "NATIVE-ENF-NET-001", "DIRECT_DENIED")
                || statusIs(cases, "NATIVE-ENF-NET-001", "DIRECT_DENIED_BY_SECCOMP");
        boolean netBroker = statusIs(cases, "NATIVE-ENF-NET-004", "PASS_CAPABILITY");
        out.put("net_conclusion", netDenied && netBroker ? "PROVEN" : "PARTIAL");
        out.put("BROKER_NET_CAPABILITY", netDenied && netBroker
                ? "PROVEN_ON_RD" : "PARTIAL/NOT_ENFORCED");
        out.put("seccomp_conclusion", out.optString("seccompStatus", "UNVERIFIED_RUNTIME")
                .contains("INSTALLED") ? "FEASIBLE" : "ENVIRONMENT_LIMITED");
        out.put("sentinelToken", sentinelToken);
        out.put("networkNonce", nonce);
    }

    private static boolean statusIs(JSONArray cases, String id, String status) {
        for (int i = 0; i < cases.length(); i++) {
            JSONObject item = cases.optJSONObject(i);
            if (item != null && id.equals(item.optString("id"))
                    && status.equals(item.optString("status"))) {
                return true;
            }
        }
        return false;
    }

    private static BindState bindIsolated(Context context) throws Exception {
        BindState state = new BindState();
        CountDownLatch latch = new CountDownLatch(1);
        state.connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) {
                state.binder = service;
                latch.countDown();
            }

            @Override public void onServiceDisconnected(ComponentName name) { }
        };
        boolean bound = context.bindService(new Intent(context, NativeEnforcementIsolatedService.class),
                state.connection, Context.BIND_AUTO_CREATE);
        if (!bound) {
            state.error = "bindService returned false";
            return state;
        }
        if (!latch.await(20, TimeUnit.SECONDS)) state.error = "bind timeout";
        return state;
    }

    private static JSONObject identity(IBinder child) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(NativeEnforcementIsolatedService.DESCRIPTOR);
            child.transact(NativeEnforcementIsolatedService.TX_IDENTITY, data, reply, 0);
            reply.readException();
            return new JSONObject(reply.readString());
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static void acceptLoop(ServerSocket server, AtomicBoolean running, String nonce) {
        byte[] payload = (nonce + "\n").getBytes(StandardCharsets.UTF_8);
        while (running.get()) {
            try {
                Socket socket = server.accept();
                try {
                    socket.getOutputStream().write(payload);
                    socket.getOutputStream().flush();
                } finally {
                    socket.close();
                }
            } catch (SocketTimeoutException ignored) {
            } catch (Exception error) {
                if (running.get()) Log.w(TAG, "loopback: " + error.getMessage());
            }
        }
    }

    private static void persist(Context context, JSONObject result) {
        writeFile(new File(context.getFilesDir(), "native-hostile-results.json"), result.toString());
    }

    private static void persistSplit(Context context, JSONObject result) {
        writeFile(new File(context.getFilesDir(), "native-enf-results.json"), result.toString());
        try {
            JSONObject uid = new JSONObject();
            uid.put("host", result.optJSONObject("host"));
            uid.put("isolated", result.optJSONObject("isolated"));
            uid.put("isolatedUidDistinct", result.optBoolean("isolatedUidDistinct"));
            writeFile(new File(context.getFilesDir(), "native-enf-uid-map.json"), uid.toString());
            writeFile(new File(context.getFilesDir(), "native-enf-fs.json"), result.toString());
            writeFile(new File(context.getFilesDir(), "native-enf-net.json"), result.toString());
            writeFile(new File(context.getFilesDir(), "native-enf-seccomp.json"), result.toString());
        } catch (Exception error) {
            Log.e(TAG, "persist split", error);
        }
    }

    private static void writeFile(File file, String body) {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) parent.mkdirs();
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(body.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        } catch (Exception error) {
            Log.e(TAG, "write " + file, error);
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }

    private static final class BindState {
        ServiceConnection connection;
        IBinder binder;
        String error = "";
    }
}
