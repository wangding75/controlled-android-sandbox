package com.warden.controlledsandbox;

/** Immutable state exposed from the application layer to screens. */
record SandboxUiState(SandboxCatalogState catalog, boolean metadataHealthy, String message) {
    SandboxUiState {
        message = message == null ? "" : message;
    }

    static SandboxUiState empty() {
        return new SandboxUiState(new SandboxCatalogState(java.util.List.of(), java.util.List.of()),
                true, "");
    }
}
