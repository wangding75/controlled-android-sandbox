package com.warden.controlledsandbox.runtime.hostile;

import com.warden.controlledsandbox.contract.HostileAdmissionSnapshot;
import com.warden.controlledsandbox.contract.HostileCapabilityRequest;
import com.warden.controlledsandbox.contract.HostileCapabilitySnapshot;
import com.warden.controlledsandbox.contract.NativeExecutionProfile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Broker-process registry. Tokens are opaque. Host paths and network targets stay here.
 *
 * <p>Revocation stops new capability use and new FD issuance. An already-delegated kernel FD
 * remains a kernel object until the child closes it or dies. That difference is intentional.</p>
 */
public final class HostileCapabilityRegistry {
    private final Map<String, Record> records = new ConcurrentHashMap<>();
    private final Map<String, HostileAdmissionSnapshot> sessions = new ConcurrentHashMap<>();

    public HostileAdmissionSnapshot admit(HostileAdmissionSnapshot admission) {
        if (admission == null) throw new IllegalArgumentException("admission is required");
        sessions.put(sessionKey(admission.sessionId(), admission.generation()), admission);
        return admission;
    }

    public HostileCapabilitySnapshot issueReadResource(HostileAdmissionSnapshot admission,
            String resourceId, File hostFile, String expectedBody, long expiresAtMillis) {
        requireHostile(admission);
        if (hostFile == null || !hostFile.isFile()) {
            throw new IllegalArgumentException("hostFile must exist");
        }
        String token = newToken("fs-");
        Record record = new Record(new HostileCapabilitySnapshot(token, admission.sessionId(),
                admission.generation(), admission.guestPackage(), admission.virtualUserId(),
                HostileCapabilityRequest.OP_READ_RESOURCE,
                HostileCapabilitySnapshot.RESOURCE_READ_ONLY, expiresAtMillis, false),
                hostFile.getAbsolutePath(), expectedBody == null ? "" : expectedBody,
                "", 0);
        records.put(token, record);
        return record.snapshot;
    }

    public HostileCapabilitySnapshot issueNetwork(HostileAdmissionSnapshot admission,
            String host, int port, String expectedNonce, long expiresAtMillis) {
        requireHostile(admission);
        if (host == null || !"127.0.0.1".equals(host)) {
            throw new SecurityException("HOSTILE_NETWORK_ENDPOINT_NOT_ALLOWLISTED");
        }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port is invalid");
        String token = newToken("net-");
        Record record = new Record(new HostileCapabilitySnapshot(token, admission.sessionId(),
                admission.generation(), admission.guestPackage(), admission.virtualUserId(),
                HostileCapabilityRequest.OP_NETWORK_REQUEST,
                HostileCapabilitySnapshot.ENDPOINT_LOOPBACK_TEST, expiresAtMillis, false),
                "", expectedNonce == null ? "" : expectedNonce, host, port);
        records.put(token, record);
        return record.snapshot;
    }

    public HostileCapabilitySnapshot issueFd(HostileAdmissionSnapshot admission, File hostFile,
            long expiresAtMillis) {
        requireHostile(admission);
        if (hostFile == null || !hostFile.isFile()) {
            throw new IllegalArgumentException("hostFile must exist");
        }
        String token = newToken("fd-");
        Record record = new Record(new HostileCapabilitySnapshot(token, admission.sessionId(),
                admission.generation(), admission.guestPackage(), admission.virtualUserId(),
                HostileCapabilityRequest.OP_DELEGATE_FD,
                HostileCapabilitySnapshot.RESOURCE_READ_ONLY, expiresAtMillis, false),
                hostFile.getAbsolutePath(), "", "", 0);
        records.put(token, record);
        return record.snapshot;
    }

    public Record require(HostileCapabilityRequest request, String expectedOperation) {
        if (request == null) throw new SecurityException("CAPABILITY_REQUEST_MISSING");
        Record record = records.get(request.tokenId());
        if (record == null) throw new SecurityException("CAPABILITY_UNKNOWN");
        HostileCapabilitySnapshot snap = record.snapshot;
        if (snap.revoked()) throw new SecurityException("CAPABILITY_REVOKED");
        if (snap.expiresAtMillis() > 0 && System.currentTimeMillis() > snap.expiresAtMillis()) {
            throw new SecurityException("CAPABILITY_EXPIRED");
        }
        if (!snap.sessionId().equals(request.sessionId())) {
            throw new SecurityException("SESSION_MISMATCH");
        }
        if (snap.generation() != request.generation()) {
            throw new SecurityException("GENERATION_MISMATCH");
        }
        if (!snap.guestPackage().equals(request.guestPackage())) {
            throw new SecurityException("OWNER_MISMATCH");
        }
        if (snap.virtualUserId() != request.virtualUserId()) {
            throw new SecurityException("USER_MISMATCH");
        }
        if (!snap.operation().equals(expectedOperation)
                || !snap.operation().equals(request.operation())) {
            throw new SecurityException("OPERATION_MISMATCH");
        }
        HostileAdmissionSnapshot session = sessions.get(
                sessionKey(request.sessionId(), request.generation()));
        if (session == null) throw new SecurityException("SESSION_UNKNOWN");
        if (!NativeExecutionProfile.isHostile(session.executionProfile())) {
            throw new SecurityException("PROFILE_NOT_HOSTILE");
        }
        return record;
    }

    public String readFile(Record record) throws Exception {
        try (FileInputStream input = new FileInputStream(record.hostPath)) {
            byte[] buffer = new byte[4096];
            int n = input.read(buffer);
            if (n <= 0) return "";
            return new String(buffer, 0, n, StandardCharsets.UTF_8);
        }
    }

    public String connectLoopback(Record record) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByName(record.networkHost),
                    record.networkPort), 2000);
            socket.setSoTimeout(2000);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[256];
            int n = socket.getInputStream().read(buffer);
            if (n > 0) output.write(buffer, 0, n);
            return output.toString(StandardCharsets.UTF_8.name()).trim();
        }
    }

    public void revokeToken(String tokenId) {
        Record record = records.get(tokenId);
        if (record == null) return;
        record.snapshot = record.snapshot.revokedCopy();
    }

    public void revokeSession(String sessionId, long generation) {
        String key = sessionKey(sessionId, generation);
        sessions.remove(key);
        for (Record record : records.values()) {
            if (record.snapshot.sessionId().equals(sessionId)
                    && record.snapshot.generation() == generation) {
                record.snapshot = record.snapshot.revokedCopy();
            }
        }
    }

    public HostileCapabilitySnapshot snapshot(String tokenId) {
        Record record = records.get(tokenId);
        return record == null ? null : record.snapshot;
    }

    private static void requireHostile(HostileAdmissionSnapshot admission) {
        if (admission == null || !admission.hostile()) {
            throw new SecurityException("HOSTILE_ADMISSION_REQUIRED");
        }
    }

    private static String sessionKey(String sessionId, long generation) {
        return sessionId + ":" + generation;
    }

    private static String newToken(String prefix) {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder builder = new StringBuilder(prefix);
        for (byte value : bytes) builder.append(String.format("%02x", value));
        return builder.toString();
    }

    public static final class Record {
        private volatile HostileCapabilitySnapshot snapshot;
        final String hostPath;
        final String expectedBody;
        final String networkHost;
        final int networkPort;

        Record(HostileCapabilitySnapshot snapshot, String hostPath, String expectedBody,
                String networkHost, int networkPort) {
            this.snapshot = snapshot;
            this.hostPath = hostPath;
            this.expectedBody = expectedBody;
            this.networkHost = networkHost;
            this.networkPort = networkPort;
        }

        public HostileCapabilitySnapshot snapshot() { return snapshot; }
        public String hostPath() { return hostPath; }
    }
}
