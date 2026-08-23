package com.warden.controlledsandbox;

import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.util.Log;
import com.warden.controlledsandbox.contract.HostileCapabilityRequest;
import com.warden.controlledsandbox.contract.HostileCapabilityResult;
import com.warden.controlledsandbox.contract.IHostileCapabilityBroker;
import com.warden.controlledsandbox.runtime.hostile.HostileSeccompInstaller;
import java.io.File;
import java.io.FileInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** Isolated-process case runner. Direct access is kernel-enforced, not PLT. */
public final class NativeEnforcementChild {
    private static final String TAG = "CS_NATIVE_ENF";

    private NativeEnforcementChild() { }

    static String run(Context context, IBinder broker, String session, String fsCap, String netCap,
            String realPath, String loopbackHost, int loopbackPort) {
        return run(context, broker, session, fsCap, netCap, realPath, loopbackHost, loopbackPort,
                1L, context.getPackageName(), "", false);
    }

    static String run(Context context, IBinder broker, String session, String fsCap, String netCap,
            String realPath, String loopbackHost, int loopbackPort, long generation,
            String guestPackage, String fdCap, boolean production) {
        return run(context, broker, session, fsCap, netCap, realPath, loopbackHost, loopbackPort,
                generation, guestPackage, fdCap, production, "", "", context.getPackageName(), 0);
    }

    static String run(Context context, IBinder broker, String session, String fsCap, String netCap,
            String realPath, String loopbackHost, int loopbackPort, long generation,
            String guestPackage, String fdCap, boolean production, String coreStoragePath,
            String otherGuestPath, String hostPackage, int hostPid) {
        JSONObject out = new JSONObject();
        JSONArray cases = new JSONArray();
        try {
            out.put("identity", NativeEnforcementIsolatedService.identityJson());
            out.put("uid", Process.myUid());
            out.put("pid", Process.myPid());
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

            JSONObject brokerFs;
            try {
                brokerFs = production
                        ? callProduction(broker, session, generation, guestPackage, fsCap,
                                HostileCapabilityRequest.OP_READ_RESOURCE)
                        : callBroker(broker, NativeEnforcementBroker.TX_READ_FS, session, fsCap);
            } catch (Exception error) {
                brokerFs = new JSONObject().put("ok", 0).put("reason", error.toString());
            }
            cases.put(brokerCase("NATIVE-ENF-FS-004", brokerFs, true));

            JSONArray guesses = new JSONArray();
            guesses.put(guessOpen(realPath + "/../" + new File(realPath).getName()));
            guesses.put(guessOpen(realPath + "/../../files/" + new File(realPath).getName()));
            File files = context.getFilesDir();
            if (files != null) {
                guesses.put(guessOpen(new File(files, "native-enf-should-not-exist").getAbsolutePath()));
            }
            if (production) {
                guesses.put(safeProduction(broker, session, generation, guestPackage, "wrong-cap",
                        HostileCapabilityRequest.OP_READ_RESOURCE));
                guesses.put(safeProduction(broker, session, generation, guestPackage, "/etc/passwd",
                        HostileCapabilityRequest.OP_READ_RESOURCE));
                guesses.put(safeProduction(broker, "wrong-session", generation, guestPackage, fsCap,
                        HostileCapabilityRequest.OP_READ_RESOURCE));
            } else {
                guesses.put(callBroker(broker, NativeEnforcementBroker.TX_READ_FS, session, "wrong-cap"));
                guesses.put(callBroker(broker, NativeEnforcementBroker.TX_READ_FS, session, "/etc/passwd"));
                guesses.put(callBroker(broker, NativeEnforcementBroker.TX_READ_FS, "wrong-session", fsCap));
            }
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

            String seccompStatus = "";
            if (production) {
                seccompStatus = HostileSeccompInstaller.installInCallingProcess();
                out.put("seccompStatus", seccompStatus);
            }
            JSONObject net = parse(NativeEnforcementNative.probeConnect(loopbackHost, loopbackPort));
            cases.put(netCase("NATIVE-ENF-NET-001", net.optJSONObject("libc")));
            cases.put(netCase("NATIVE-ENF-NET-002", net.optJSONObject("syscall")));
            JSONObject rawNet = net.optJSONObject("raw");
            if (!net.optBoolean("raw_available", false) || arm64Unverified) {
                cases.put(unverified("NATIVE-ENF-NET-003", "raw socket/connect not executed"));
            } else {
                cases.put(netCase("NATIVE-ENF-NET-003", rawNet));
            }
            JSONObject brokerNet = production
                    ? callProduction(broker, session, generation, guestPackage, netCap,
                            HostileCapabilityRequest.OP_NETWORK_REQUEST)
                    : callBroker(broker, NativeEnforcementBroker.TX_NET, session, netCap);
            cases.put(brokerCase("NATIVE-ENF-NET-004", brokerNet, false));
            if (production && fdCap != null && !fdCap.isEmpty()) {
                cases.put(fdCase(broker, session, generation, guestPackage, fdCap));
            }
            if (production) {
                JSONObject attack = parse(NativeEnforcementNative.probeAttack(coreStoragePath,
                        otherGuestPath, hostPackage, hostPid));
                out.put("attack", attack);
                cases.put(deniedPathCase("C3-T04-FS-CORE-001", attack.optJSONObject("core_storage")));
                cases.put(deniedPathCase("C3-T04-FS-GUEST-001", attack.optJSONObject("other_guest")));
                cases.put(attackStatus("C3-T04-PTRACE-001", "ptrace",
                        attack.optJSONObject("ptrace"), true));
                cases.put(attackStatus("C3-T04-EXEC-001", "execve",
                        attack.optJSONObject("execve"), true));
                cases.put(cloneCase(attack.optJSONObject("clone")));
                cases.put(binderCase(attack.optJSONObject("binder"),
                        attack.optJSONObject("binderfs")));
                cases.put(inheritedFdCase(attack.optJSONObject("inherited_fd")));
            }

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
            out.put("production", production);
            out.put("openProbe", open);
            out.put("netProbe", net);
            out.put("brokerFs", brokerFs);
            out.put("brokerNet", brokerNet);
            out.put("seccomp", seccomp);
        } catch (Exception error) {
            Log.e(TAG, "child run failed", error);
            try {
                out.put("error", error.getClass().getName() + ":" + error.getMessage());
                if (!out.has("cases") && cases != null) out.put("cases", cases);
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
        if ("DIRECT_DENIED".equals(outcome)) outcome = "DIRECT_DENIED_BY_SECCOMP";
        item.put("status", outcome);
        item.put("detail", attempt == null ? JSONObject.NULL : attempt);
        return item;
    }

    private static JSONObject safeProduction(IBinder binder, String session, long generation,
            String pkg, String token, String operation) {
        try {
            return callProduction(binder, session, generation, pkg, token, operation);
        } catch (Exception error) {
            try {
                return new JSONObject().put("ok", 0).put("reason", error.toString());
            } catch (Exception ignored) {
                return new JSONObject();
            }
        }
    }

    private static JSONObject callProduction(IBinder binder, String session, long generation,
            String pkg, String token, String operation) throws Exception {
        JSONObject out = new JSONObject();
        int code = HostileCapabilityRequest.OP_NETWORK_REQUEST.equals(operation)
                ? IBinder.FIRST_CALL_TRANSACTION + 1 : IBinder.FIRST_CALL_TRANSACTION;
        HostileCapabilityRequest request = new HostileCapabilityRequest(token, session, generation,
                pkg, 0, operation);
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("com.warden.controlledsandbox.contract.IHostileCapabilityBroker");
            data.writeInt(1);
            request.writeToParcel(data, 0);
            binder.transact(code, data, reply, 0);
            reply.readException();
            HostileCapabilityResult result = reply.readInt() != 0
                    ? HostileCapabilityResult.CREATOR.createFromParcel(reply)
                    : HostileCapabilityResult.denied("EMPTY_RESULT");
            out.put("ok", result.successful() ? 1 : 0);
            out.put("body", result.body());
            if (!result.successful()) out.put("reason", result.errorType());
            return out;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static JSONObject fdCase(IBinder binder, String session, long generation, String pkg,
            String token) throws Exception {
        JSONObject item = new JSONObject();
        item.put("id", "NATIVE-ENF-FD-001");
        item.put("domain", "fd");
        IHostileCapabilityBroker broker = IHostileCapabilityBroker.Stub.asInterface(binder);
        HostileCapabilityResult result = broker.delegateReadOnlyFd(
                new HostileCapabilityRequest(token, session, generation, pkg, 0,
                        HostileCapabilityRequest.OP_DELEGATE_FD));
        boolean readable = false;
        String proc = "";
        if (result.successful() && result.delegatedFd() != null) {
            int raw = result.delegatedFd().getFd();
            try (FileInputStream input = new FileInputStream(result.delegatedFd().getFileDescriptor())) {
                readable = input.read() >= 0;
            }
            File link = new File("/proc/self/fd/" + raw);
            proc = link.getCanonicalPath();
        }
        item.put("status", readable ? "PASS_FD" : "FD_DENIED");
        item.put("procPath", proc);
        item.put("errorType", result.errorType());
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

    private static JSONObject deniedPathCase(String id, JSONObject probe) throws Exception {
        JSONObject item = new JSONObject();
        item.put("id", id);
        item.put("domain", "filesystem");
        boolean libcDenied = expectedDenied(probe == null ? null : probe.optJSONObject("libc"));
        boolean sysDenied = expectedDenied(probe == null ? null : probe.optJSONObject("syscall"));
        boolean rawAvailable = probe != null && probe.optBoolean("raw_available", false);
        boolean rawDenied = !rawAvailable
                || expectedDenied(probe.optJSONObject("raw"));
        boolean denied = libcDenied && sysDenied && rawDenied;
        item.put("status", denied ? "DENIED_BY_KERNEL_POLICY" : "DIRECT_ALLOWED");
        item.put("detail", probe == null ? JSONObject.NULL : probe);
        return item;
    }

    private static JSONObject attackStatus(String id, String domain, JSONObject attempt,
            boolean mustDeny) throws Exception {
        JSONObject item = new JSONObject();
        item.put("id", id);
        item.put("domain", domain);
        boolean denied = attempt != null && attempt.optBoolean("denied", attempt.optInt("rc", 0) < 0);
        if (mustDeny) {
            item.put("status", denied ? "DENIED_BY_SECCOMP" : "KERNEL_LIMIT_EXPOSED");
        } else {
            item.put("status", denied ? "DENIED" : "KERNEL_LIMIT_EXPOSED");
        }
        item.put("detail", attempt == null ? JSONObject.NULL : attempt);
        return item;
    }

    private static JSONObject cloneCase(JSONObject attempt) throws Exception {
        JSONObject item = new JSONObject();
        item.put("id", "C3-T04-CLONE-001");
        item.put("domain", "clone");
        boolean denied = attempt != null && attempt.optInt("rc", 0) < 0;
        item.put("status", denied ? "DENIED_BY_KERNEL_POLICY" : "KERNEL_LIMIT_EXPOSED_SAME_UID");
        item.put("detail", attempt == null ? JSONObject.NULL : attempt);
        return item;
    }

    private static JSONObject binderCase(JSONObject binder, JSONObject binderfs) throws Exception {
        JSONObject item = new JSONObject();
        item.put("id", "C3-T04-BINDER-001");
        item.put("domain", "binder");
        boolean opened = (binder != null && binder.optInt("rc", -1) >= 0)
                || (binderfs != null && binderfs.optInt("rc", -1) >= 0);
        item.put("status", opened ? "KERNEL_LIMIT_EXPOSED" : "DENIED_BY_KERNEL_POLICY");
        item.put("binder", binder == null ? JSONObject.NULL : binder);
        item.put("binderfs", binderfs == null ? JSONObject.NULL : binderfs);
        return item;
    }

    private static JSONObject inheritedFdCase(JSONObject scan) throws Exception {
        JSONObject item = new JSONObject();
        item.put("id", "C3-T04-FD-INHERIT-001");
        item.put("domain", "fd");
        int leaks = scan == null ? -1 : scan.optInt("host_private_leaks", -1);
        item.put("status", leaks == 0 ? "PASS_NO_LEAK" : "HOST_PRIVATE_FD_LEAK");
        item.put("detail", scan == null ? JSONObject.NULL : scan);
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
