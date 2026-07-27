package com.warden.controlledsandbox.runtime.diagnostics;

import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.content.Context;
import android.os.Bundle;
import java.io.File;
import java.nio.file.Files;

public final class RuntimeDiagnosticsSelfTest {
    public static void main(String[] args) throws Exception {
        File root = new File("build/runtime-diagnostics-self-test").getCanonicalFile();
        deleteTree(root);
        Context context = new TestContext(root);
        RuntimeDiagnostics.install(context, "self-test");
        Bundle event = new Bundle();
        event.putString(RuntimeKeys.STATUS, "OK");
        event.putString(RuntimeKeys.PACKAGE_NAME, "com.example.fixture");
        RuntimeDiagnostics.record("FIXTURE_EVENT", event, "first");
        RuntimeDiagnostics.record("FIXTURE_EVENT", event, "second");
        Bundle snapshot = RuntimeDiagnostics.snapshot();
        require("DIAGNOSTICS_READY".equals(snapshot.getString(RuntimeKeys.STATUS, "")), "snapshot ready");
        require(snapshot.getLong("diagnosticsEventBytes", 0) > 0, "event bytes");
        require(snapshot.getStringArrayList("diagnosticsEventCounts").contains("FIXTURE_EVENT=2"), "event count");
        File manifest = RuntimeDiagnostics.exportEvidence(new File(root, "export"));
        require(manifest.isFile(), "manifest exported");
        require(new File(manifest.getParentFile(), "runtime-events.jsonl").isFile(), "events exported");
        String body = Files.readString(manifest.toPath());
        require(body.contains("sha256="), "manifest hashes");
        deleteTree(root);
        System.out.println("PASS runtime diagnostics evidence self-test");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
    private static void deleteTree(File value) {
        if (value == null || !value.exists()) return;
        File[] children = value.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (!value.delete() && value.exists()) throw new IllegalStateException("Cannot delete " + value);
    }
    private static final class TestContext extends Context {
        private final File root;
        TestContext(File root) { this.root = root; }
        @Override public File getFilesDir() { return root; }
    }
}
