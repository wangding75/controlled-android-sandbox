package com.warden.controlledsandbox.runtime.hostile;

import com.warden.controlledsandbox.contract.HostileAdmissionSnapshot;
import com.warden.controlledsandbox.contract.HostileCapabilityRequest;
import com.warden.controlledsandbox.contract.HostileCapabilitySnapshot;
import com.warden.controlledsandbox.contract.NativeExecutionProfile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public final class HostileCapabilityRegistrySelfTest {
    private HostileCapabilityRegistrySelfTest() { }

    public static void main(String[] args) throws Exception {
        File file = File.createTempFile("hostile-cap", ".txt");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write("SENTINEL".getBytes(StandardCharsets.UTF_8));
        }
        HostileCapabilityRegistry registry = new HostileCapabilityRegistry();
        HostileAdmissionSnapshot admission = new HostileAdmissionSnapshot(
                NativeExecutionProfile.ISOLATED_HOSTILE, "com.example.guest", 0,
                "com.example.guest:hostile", "x86_64", 3L, "sess-1",
                HostileAdmissionSnapshot.NETWORK_BROKER_ONLY,
                HostileAdmissionSnapshot.PROCESS_ISOLATED_UID);
        registry.admit(admission);
        HostileCapabilitySnapshot fs = registry.issueReadResource(admission, "sentinel", file,
                "SENTINEL", System.currentTimeMillis() + 60_000L);
        HostileCapabilityRequest good = new HostileCapabilityRequest(fs.tokenId(), "sess-1", 3L,
                "com.example.guest", 0, HostileCapabilityRequest.OP_READ_RESOURCE);
        check(registry.require(good, HostileCapabilityRequest.OP_READ_RESOURCE) != null,
                "owner session generation must accept");
        check("SENTINEL".equals(registry.readFile(registry.require(good,
                HostileCapabilityRequest.OP_READ_RESOURCE))), "broker file read");

        expectDenied(() -> registry.require(new HostileCapabilityRequest(fs.tokenId(), "sess-1",
                99L, "com.example.guest", 0, HostileCapabilityRequest.OP_READ_RESOURCE),
                HostileCapabilityRequest.OP_READ_RESOURCE), "GENERATION_MISMATCH");
        expectDenied(() -> registry.require(new HostileCapabilityRequest(fs.tokenId(), "sess-1",
                3L, "com.other", 0, HostileCapabilityRequest.OP_READ_RESOURCE),
                HostileCapabilityRequest.OP_READ_RESOURCE), "OWNER_MISMATCH");
        expectDenied(() -> registry.require(new HostileCapabilityRequest(fs.tokenId(), "sess-1",
                3L, "com.example.guest", 7, HostileCapabilityRequest.OP_READ_RESOURCE),
                HostileCapabilityRequest.OP_READ_RESOURCE), "USER_MISMATCH");
        expectDenied(() -> registry.require(new HostileCapabilityRequest("missing", "sess-1",
                3L, "com.example.guest", 0, HostileCapabilityRequest.OP_READ_RESOURCE),
                HostileCapabilityRequest.OP_READ_RESOURCE), "CAPABILITY_UNKNOWN");

        registry.revokeToken(fs.tokenId());
        expectDenied(() -> registry.require(good, HostileCapabilityRequest.OP_READ_RESOURCE),
                "CAPABILITY_REVOKED");

        try {
            registry.issueNetwork(admission, "8.8.8.8", 53, "no", 0);
            throw new AssertionError("arbitrary network target must be rejected");
        } catch (SecurityException expected) {
            check("HOSTILE_NETWORK_ENDPOINT_NOT_ALLOWLISTED".equals(expected.getMessage()),
                    expected.getMessage());
        }

        HostileAdmissionSnapshot compat = new HostileAdmissionSnapshot(
                NativeExecutionProfile.TRUSTED_COMPAT, "com.example.guest", 0,
                "com.example.guest", "x86_64", 1L, "sess-compat",
                "COMPAT_NET", "ORDINARY");
        try {
            registry.issueReadResource(compat, "x", file, "x", 0);
            throw new AssertionError("trusted-compat must not issue hostile caps");
        } catch (SecurityException expected) {
            check("HOSTILE_ADMISSION_REQUIRED".equals(expected.getMessage()),
                    expected.getMessage());
        }
        System.out.println("PASS hostile capability owner/generation/revoke self-test");
    }

    private static void expectDenied(Runnable action, String token) {
        try {
            action.run();
            throw new AssertionError("expected deny " + token);
        } catch (SecurityException expected) {
            if (!token.equals(expected.getMessage())) {
                throw new AssertionError("wanted " + token + " got " + expected.getMessage(),
                        expected);
            }
        }
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
