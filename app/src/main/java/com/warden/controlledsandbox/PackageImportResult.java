package com.warden.controlledsandbox;

/** Typed package import result plus request-scoped transaction telemetry. */
record PackageImportResult(SandboxRecord record, String operationTraceJson) {
    PackageImportResult {
        if (record == null) throw new IllegalArgumentException("record is required");
        operationTraceJson = operationTraceJson == null ? "" : operationTraceJson;
    }
}

record PackageDeleteResult(SandboxCatalogState catalog, String operationTraceJson) {
    PackageDeleteResult {
        if (catalog == null) throw new IllegalArgumentException("catalog is required");
        operationTraceJson = operationTraceJson == null ? "" : operationTraceJson;
    }
}

final class PackageMutationFailureException extends IllegalStateException {
    final String code;
    final String operationTraceJson;
    PackageMutationFailureException(String code, String message, String operationTraceJson) {
        super((code == null ? "PACKAGE_MUTATION_FAILURE" : code) + ": "
                + (message == null ? "" : message));
        this.code = code == null || code.isBlank() ? "PACKAGE_MUTATION_FAILURE" : code;
        this.operationTraceJson = operationTraceJson == null ? "" : operationTraceJson;
    }
}
