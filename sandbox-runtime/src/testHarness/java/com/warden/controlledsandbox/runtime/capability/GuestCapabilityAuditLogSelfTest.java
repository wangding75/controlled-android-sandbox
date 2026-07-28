package com.warden.controlledsandbox.runtime.capability;

public final class GuestCapabilityAuditLogSelfTest {
    public static void main(String[] args) {
        GuestCapabilityAuditLog log = new GuestCapabilityAuditLog(3);
        log.event("camera", "camera", "connect", "ALLOWED", "");
        log.event("location", "location", "request", "DENIED", "missing permission");
        log.event("microphone", "audio", "startInput", "ALLOWED", "");
        log.event("camera", "resource", "cleanup", "CLEANUP_FAILED", "test");
        require(log.size() == 3, "audit is bounded");
        require(log.deniedCount() == 2, "denied and failed decisions counted");
        require(log.compactSnapshot().get(0).contains("location"), "oldest event evicted");
        log.record(new com.warden.controlledsandbox.framework.capability.CapabilityAuditEvent(
                9999, "camera", "camera", "open", "ALLOWED", "external"));
        java.util.List<String> compact = log.compactSnapshot();
        require(compact.get(compact.size() - 1).startsWith("5:"),
                "external event IDs cannot create gaps or duplicate local sequence");
        System.out.println("PASS guest capability audit log self-test");
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
