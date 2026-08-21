package com.warden.controlledsandbox;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Build;
import android.os.Parcel;
import android.os.Process;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Host-process orchestrator for the debug-only Native enforcement POC. */
public final class NativeEnforcementCampaign {
    private static final String TAG = "CS_NATIVE_ENF";

    private NativeEnforcementCampaign() { }

    public static JSONObject run(Context context) throws Exception {
        JSONObject result = new JSONObject();
        result.put("schema", "t57-r03-p0a-02-native-enf");
        JSONObject host = new JSONObject();
        host.put("uid", Process.myUid());
        host.put("pid", Process.myPid());
        host.put("processName", processName());
        host.put("packageName", context.getPackageName());
        result.put("host", host);

        String session = NativeEnforcementBroker.newOpaqueId("sess-");
        String fsCap = NativeEnforcementBroker.newOpaqueId("fs-");
        String netCap = NativeEnforcementBroker.newOpaqueId("net-");
        String sentinelToken = NativeEnforcementBroker.newOpaqueId("tok-");
        String networkNonce = NativeEnforcementBroker.newOpaqueId("netn-");

        File root = new File(context.getFilesDir(), "native-enf/" + session);
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IllegalStateException("cannot create sentinel directory");
        }
        File sentinel = new File(root, "sentinel.txt");
        writeFile(sentinel, sentinelToken);

        ServerSocket server = new ServerSocket();
        AtomicBoolean accept = new AtomicBoolean(true);
        try {
            server.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8);
            server.setSoTimeout(1000);
            int port = server.getLocalPort();
            Thread acceptor = new Thread(() -> acceptLoop(server, accept, networkNonce), "enf-loopback");
            acceptor.setDaemon(true);
            acceptor.start();

            NativeEnforcementBroker broker = new NativeEnforcementBroker(session, fsCap, netCap,
                    sentinel, sentinelToken, "127.0.0.1", port, networkNonce);
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
                int hostUid = Process.myUid();
                int childUid = isolated.optInt("uid", hostUid);
                boolean distinct = childUid != hostUid && childUid >= 99000 && childUid <= 99999;
                result.put("isolatedUidDistinct", distinct);
                result.put("pocValid", distinct);
                if (!distinct) {
                    result.put("error", "ISOLATED_UID_NOT_ASSIGNED host=" + hostUid
                            + " child=" + childUid);
                    persist(context, result);
                    return result;
                }
                JSONObject child = runChild(bind.binder, broker, session, fsCap, netCap,
                        sentinel.getAbsolutePath(), "127.0.0.1", port);
                result.put("child", child);
                mergeCases(result, child, sentinelToken, networkNonce);
            } finally {
                context.unbindService(bind.connection);
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

    private static void mergeCases(JSONObject result, JSONObject child, String sentinelToken,
            String networkNonce) throws Exception {
        JSONArray cases = child.optJSONArray("cases");
        result.put("cases", cases == null ? new JSONArray() : cases);
        JSONObject brokerFs = child.optJSONObject("brokerFs");
        JSONObject brokerNet = child.optJSONObject("brokerNet");
        if (brokerFs != null) {
            boolean match = sentinelToken.equals(brokerFs.optString("body"));
            brokerFs.put("tokenMatch", match);
            if (!match && brokerFs.optInt("ok", 0) == 1) {
                replaceStatus(cases, "NATIVE-ENF-FS-004", "BROKER_TOKEN_MISMATCH");
            }
        }
        if (brokerNet != null) {
            boolean match = networkNonce.equals(brokerNet.optString("body"));
            brokerNet.put("nonceMatch", match);
            if (!match && brokerNet.optInt("ok", 0) == 1) {
                replaceStatus(cases, "NATIVE-ENF-NET-004", "BROKER_NONCE_MISMATCH");
            }
        }
        result.put("fs_conclusion", concludeFs(cases));
        result.put("net_conclusion", concludeNet(cases));
        result.put("seccomp_conclusion", concludeSeccomp(cases));
        result.put("BROKER_FS_CAPABILITY", "PROVEN".equals(result.optString("fs_conclusion"))
                ? "PROVEN_ON_RD" : result.optString("fs_conclusion"));
        String net = result.optString("net_conclusion");
        result.put("BROKER_NET_CAPABILITY", "PROVEN".equals(net)
                ? "PROVEN_ON_RD" : "PARTIAL".equals(net) ? "PARTIAL/NOT_ENFORCED" : net);
        String sec = result.optString("seccomp_conclusion");
        result.put("SECCOMP_FILTER", "FEASIBLE".equals(sec)
                ? "SECCOMP_FILTER_FEASIBLE_ON_RD" : "SECCOMP_FILTER_NOT_FEASIBLE_ON_RD");
    }

    private static String concludeFs(JSONArray cases) {
        boolean d1 = statusIs(cases, "NATIVE-ENF-FS-001", "DENIED_BY_KERNEL_POLICY");
        boolean d2 = statusIs(cases, "NATIVE-ENF-FS-002", "DENIED_BY_KERNEL_POLICY");
        boolean d3 = statusIs(cases, "NATIVE-ENF-FS-003", "DENIED_BY_KERNEL_POLICY")
                || statusIs(cases, "NATIVE-ENF-FS-003", "UNVERIFIED_RUNTIME");
        boolean broker = statusIs(cases, "NATIVE-ENF-FS-004", "PASS_CAPABILITY");
        boolean guess = statusIs(cases, "NATIVE-ENF-FS-005", "DENIED");
        if (d1 && d2 && d3 && broker && guess) return "PROVEN";
        if (broker && (d1 || d2)) return "PARTIAL";
        return "FAILED";
    }

    private static String concludeNet(JSONArray cases) {
        boolean broker = statusIs(cases, "NATIVE-ENF-NET-004", "PASS_CAPABILITY");
        boolean directDenied = statusIs(cases, "NATIVE-ENF-NET-001", "DIRECT_DENIED")
                && statusIs(cases, "NATIVE-ENF-NET-002", "DIRECT_DENIED");
        boolean directAllowed = statusIs(cases, "NATIVE-ENF-NET-001", "DIRECT_ALLOWED")
                || statusIs(cases, "NATIVE-ENF-NET-002", "DIRECT_ALLOWED");
        if (broker && directDenied) return "PROVEN";
        if (broker && directAllowed) return "PARTIAL";
        if (broker) return "PARTIAL";
        return "FAILED";
    }

    private static String concludeSeccomp(JSONArray cases) {
        String classification = caseField(cases, "NATIVE-ENF-SECCOMP-64", "classification");
        if ("SECCOMP_FILTER_FEASIBLE".equals(classification)) return "FEASIBLE";
        if ("SECCOMP_FILTER_BLOCKED_BY_ANDROID".equals(classification)
                || "UNVERIFIED_RUNTIME".equals(classification)) {
            return "ENVIRONMENT_LIMITED";
        }
        return "NOT_FEASIBLE";
    }

    private static boolean statusIs(JSONArray cases, String id, String status) {
        return status.equals(caseField(cases, id, "status"));
    }

    private static String caseField(JSONArray cases, String id, String field) {
        if (cases == null) return "";
        for (int i = 0; i < cases.length(); i++) {
            JSONObject item = cases.optJSONObject(i);
            if (item != null && id.equals(item.optString("id"))) return item.optString(field);
        }
        return "";
    }

    private static void replaceStatus(JSONArray cases, String id, String status) {
        if (cases == null) return;
        for (int i = 0; i < cases.length(); i++) {
            JSONObject item = cases.optJSONObject(i);
            if (item != null && id.equals(item.optString("id"))) {
                try { item.put("status", status); } catch (Exception ignored) { }
            }
        }
    }

    private static BindState bindIsolated(Context context) throws Exception {
        BindState state = new BindState();
        CountDownLatch latch = new CountDownLatch(1);
        Intent intent = new Intent(context, NativeEnforcementIsolatedService.class);
        state.connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) {
                state.binder = service;
                latch.countDown();
            }

            @Override public void onServiceDisconnected(ComponentName name) { }
        };
        boolean bound = context.bindService(intent, state.connection, Context.BIND_AUTO_CREATE);
        if (!bound) {
            state.error = "bindService returned false";
            return state;
        }
        if (!latch.await(20, TimeUnit.SECONDS)) {
            state.error = "bind timeout";
        }
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

    private static JSONObject runChild(IBinder child, IBinder broker, String session, String fsCap,
            String netCap, String realPath, String host, int port) throws Exception {
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
            child.transact(NativeEnforcementIsolatedService.TX_RUN, data, reply, 0);
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
                if (running.get()) Log.w(TAG, "loopback accept: " + error.getMessage());
            }
        }
    }

    private static void persist(Context context, JSONObject result) {
        writeFile(new File(context.getFilesDir(), "native-enf-results.json"), result.toString());
    }

    private static void persistSplit(Context context, JSONObject result) {
        try {
            JSONObject uid = new JSONObject();
            uid.put("host", result.optJSONObject("host"));
            uid.put("isolated", result.optJSONObject("isolated"));
            uid.put("isolatedUidDistinct", result.optBoolean("isolatedUidDistinct"));
            writeFile(new File(context.getFilesDir(), "native-enf-uid-map.json"), uid.toString());
            writeFile(new File(context.getFilesDir(), "native-enf-fs.json"),
                    subset(result, "filesystem").toString());
            writeFile(new File(context.getFilesDir(), "native-enf-net.json"),
                    subset(result, "network").toString());
            writeFile(new File(context.getFilesDir(), "native-enf-seccomp.json"),
                    subset(result, "seccomp").toString());
        } catch (Exception error) {
            Log.e(TAG, "persist split", error);
        }
    }

    private static JSONObject subset(JSONObject result, String domain) throws Exception {
        JSONObject out = new JSONObject();
        out.put("conclusion", "filesystem".equals(domain) ? result.optString("fs_conclusion")
                : "network".equals(domain) ? result.optString("net_conclusion")
                : result.optString("seccomp_conclusion"));
        JSONArray cases = new JSONArray();
        JSONArray all = result.optJSONArray("cases");
        if (all != null) {
            for (int i = 0; i < all.length(); i++) {
                JSONObject item = all.optJSONObject(i);
                if (item != null && domain.equals(item.optString("domain"))) cases.put(item);
                if ("seccomp".equals(domain) && item != null
                        && item.optString("id").contains("SECCOMP")) {
                    cases.put(item);
                }
            }
        }
        out.put("cases", cases);
        return out;
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
        if (!file.delete()) Log.w(TAG, "could not delete " + file);
    }

    private static String processName() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return android.app.Application.getProcessName();
        }
        return "unknown";
    }

    private static final class BindState {
        ServiceConnection connection;
        IBinder binder;
        String error = "";
    }
}
