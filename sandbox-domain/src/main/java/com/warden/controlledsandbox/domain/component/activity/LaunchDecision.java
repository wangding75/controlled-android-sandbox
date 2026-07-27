package com.warden.controlledsandbox.domain.component.activity;

import java.util.List;

public final class LaunchDecision {
    public enum Status { READY_FOR_PROBE, REJECTED, RUNTIME_NOT_IMPLEMENTED }
    private final Status status;
    private final int processSlot;
    private final String entryClass;
    private final List<String> reasons;
    public LaunchDecision(Status status, int processSlot, String entryClass, List<String> reasons) {
        this.status = status; this.processSlot = processSlot; this.entryClass = entryClass; this.reasons = reasons;
    }
    public Status status() { return status; }
    public int processSlot() { return processSlot; }
    public String entryClass() { return entryClass; }
    public List<String> reasons() { return reasons; }
}
