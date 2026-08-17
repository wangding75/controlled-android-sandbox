package com.warden.controlledsandbox;

import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.util.Log;
import java.io.File;
import org.json.JSONArray;
import org.json.JSONObject;

/** Isolated-process case runner. Direct access is kernel-enforced, not PLT. */
public final class NativeEnforcementChild {
    private static final String TAG = "CS_NATIVE_ENF";

    private NativeEnforcementChild() { }

    static String run(Context context, IBinder broker, String session, String fsCap, String netCap,
            String realPath, String loopbackHost, int loopbackPort) {
        JSONObject out = new JSONObject();
        try {
            out.put("identity", NativeEnforcementIsolatedService.identityJson());
            out.put("uid", Process.myUid());
            out.put("pid", Process.myPid());
            JSONArray cases = new JSONArray();
            JSONObject open = parse(NativeEnforcementNative.probeOpen(realPath));
            cases.put(fsCase("NATIVE-ENF-FS-001", "libc", open.optJSONObject("libc"),
                    expectedDenied(open.optJSONObject("libc"))));
            cases.put(fsCase("NATIVE-ENF-FS-002", "syscall", open.optJSONObject("syscall"),
                    expectedDenied(open.optJSONObject("syscall"))));
            JSONObject rawOpen = open.optJSONObject("raw");
            boolean rawCompiled = open.optBoolean("raw_available", false);
            String rawAbi = open.optString("abi", NativeEnforcementNative.compiledAbi());
            boolean arm64Unverified = "arm64-v8a".equals(rawAbi)
                    && !"arm64-v8a".equals(android.os.Build.SUPPORTED_ABIS[0]);
            if (!rawCompiled || arm64Unverified) {
                cases.put(unverified("NATIVE-ENF-FS-003", "raw syscall not executed on this ABI"));
            } else {
                cases.put(fsCase("NATIVE-ENF-FS-003", "raw", rawOpen, expectedDenied(rawOpen)));
            }

            JSONObject brokerFs = callBroker(broker, NativeEnforcementBroker.TX_READ_FS,
                    session, fsCap);
            cases.put(brokerCase("NATIVE-ENF-FS-004", brokerFs, true));

            JSONArray guesses = new JSONArray();
            guesses.put(guessOpen(realPath + "/../" + new File(realPath).getName()));
            guesses.put(guessOpen(realPath + "/../../files/" + new File(realPath).getName()));
            File files = context.getFilesDir();
            if (files != null) {
                guesses.put(guessOpen(new File(files, "native-enf-should-not-exist").getAbsolutePath()));
            }
            guesses.put(callBroker(broker, NativeEnforcementBroker.TX_READ_FS, session, "wrong-cap"));
            guesses.put(callBroker(broker, NativeEnforcementBroker.TX_READ_FS, session, "/etc/passwd"));
            guesses.put(callBroker(broker, NativeEnforcementBroker.TX_READ_FS, "wrong-session", fsCap));
            boolean guessDenied = true;
            for (int i = 0; i < guesses.length(); i++) {
                JSONObject item = guesses.getJSONObject(i);
                if (item.optBoolean("allowed", false) || item.optInt("ok", 0) == 1) {
                    guessDenied = false;
                }
            }
            JSONObject fs005 = new JSONObject();
            fs005.put("id", "NATIVE-ENF-FS-005");
            fs005.put("domain", "filesystem");
            fs005.put("status", guessDenied ? "DENIED" : "UNEXPECTED_ALLOW");
            fs005.put("guesses", guesses);
            cases.put(fs005);

            JSONObject net = parse(NativeEnforcementNative.probeConnect(loopbackHost, loopbackPort));
            cases.put(netCase("NATIVE-ENF-NET-001", net.optJSONObject("libc")));
            cases.put(netCase("NATIVE-ENF-NET-002", net.optJSONObject("syscall")));
            JSONObject rawNet = net.optJSONObject("raw");
            if (!net.optBoolean("raw_available", false) || arm64Unverified) {
                cases.put(unverified("NATIVE-ENF-NET-003", "raw socket/connect not executed"));
            } else {
                cases.put(netCase("NATIVE-ENF-NET-003", rawNet));
            }
            JSONObject brokerNet = callBroker(broker, NativeEnforcementBroker.TX_NET,
                    session, netCap);
            cases.put(brokerCase("NATIVE-ENF-NET-004", brokerNet, false));

            JSONObject seccomp = parse(NativeEnforcementNative.probeSeccomp());
            JSONObject seccompCase = new JSONObject();
            seccompCase.put("id", "NATIVE-ENF-SECCOMP-64");
            seccompCase.put("domain", "seccomp");
            JSONObject inner = seccomp.optJSONObject("result");
            if (inner == null) inner = seccomp;
            seccompCase.put("classification", inner.optString("classification",
                    seccomp.optString("classification", "UNVERIFIED_RUNTIME")));
            seccompCase.put("detail", seccomp);
            seccompCase.put("abi", inner.optString("abi", NativeEnforcementNative.compiledAbi()));
            cases.put(seccompCase);

            out.put("cases", cases);
            out.put("openProbe", open);
            out.put("netProbe", net);
            out.put("brokerFs", brokerFs);
            out.put("brokerNet", brokerNet);
            out.put("seccomp", seccomp);
        } catch (Exception error) {
            Log.e(TAG, "child run failed", error);
            try {
                out.put("error", error.getClass().getName() + ":" + error.getMessage());
            } catch (Exception ignored) { }
        }
        return out.toString();
    }

    private static JSONObject fsCase(String id, String pathKind, JSONObject attempt, boolean denied)
            throws Exception {
        JSONObject item = new JSONObject();
        item.put("id", id);
        item.put("domain", "filesystem");
        item.put("pathKind", pathKind);
        item.put("status", denied ? "DENIED_BY_KERNEL_POLICY" : "DIRECT_ALLOWED");
        item.put("detail", attempt == null ? JSONObject.NULL : attempt);
        return item;
    }

    private static boolean expectedDenied(JSONObject attempt) {
        if (attempt == null) return false;
        return attempt.optInt("rc", 0) < 0 && attempt.optInt("errno", 0) != 0;
    }

    private static JSONObject netCase(String id, JSONObject attempt) throws Exception {
        JSONObject item = new JSONObject();
        item.put("id", id);
        item.put("domain", "network");
        String outcome = attempt == null ? "UNVERIFIED_RUNTIME" : attempt.optString("outcome",
                "UNVERIFIED_RUNTIME");
        item.put("status", outcome);
        item.put("detail", attempt == null ? JSONObject.NULL : attempt);
        return item;
    }

    private static JSONObject brokerCase(String id, JSONObject reply, boolean fs) throws Exception {
        JSONObject item = new JSONObject();
        item.put("id", id);
        item.put("domain", fs ? "filesystem" : "network");
        boolean ok = reply.optInt("ok", 0) == 1 && reply.optString("body", "").length() > 0;
        item.put("status", ok ? "PASS_CAPABILITY" : "BROKER_DENIED");
        item.put("detail", reply);
        return item;
    }

    private static JSONObject unverified(String id, String reason) throws Exception {
        JSONObject item = new JSONObject();
        item.put("id", id);
        item.put("status", "UNVERIFIED_RUNTIME");
        item.put("detail", reason);
        return item;
    }

    private static JSONObject guessOpen(String path) throws Exception {
        JSONObject probe = parse(NativeEnforcementNative.probeOpen(path));
        JSONObject libc = probe.optJSONObject("libc");
        JSONObject out = new JSONObject();
        out.put("path", path);
        out.put("allowed", libc != null && libc.optInt("rc", -1) >= 0);
        out.put("probe", probe);
        return out;
    }

    private static JSONObject callBroker(IBinder broker, int code, String session, String cap)
            throws Exception {
        JSONObject out = new JSONObject();
        if (broker == null) {
            out.put("ok", 0);
            out.put("reason", "NO_BROKER");
            return out;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(NativeEnforcementBroker.DESCRIPTOR);
            data.writeString(session);
            data.writeString(cap);
            broker.transact(code, data, reply, 0);
            reply.readException();
            int ok = reply.readInt();
            String body = reply.readString();
            out.put("ok", ok);
            out.put("body", body == null ? "" : body);
            if (ok == 0) out.put("reason", body);
        } finally {
            data.recycle();
            reply.recycle();
        }
        return out;
    }

    private static JSONObject parse(String text) {
        try {
            return new JSONObject(text == null ? "{}" : text);
        } catch (Exception error) {
            JSONObject fallback = new JSONObject();
            try {
                fallback.put("error", "json");
                fallback.put("raw", text);
            } catch (Exception ignored) { }
            return fallback;
        }
    }
}
