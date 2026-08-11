package com.warden.controlledsandbox.sdk;

import java.util.List;

/** Read-only catalog snapshot; adapters keep Sandbox DTOs out of the UI. */
public record SandboxCatalog(List<SandboxPackage> packages, List<SandboxInstance> instances,
                             String maintenanceWarning) {
    public SandboxCatalog {
        packages = List.copyOf(packages == null ? List.of() : packages);
        instances = List.copyOf(instances == null ? List.of() : instances);
        maintenanceWarning = maintenanceWarning == null ? "" : maintenanceWarning;
    }
}
