package com.warden.controlledsandbox;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import com.warden.controlledsandbox.contract.RuntimeStatusResult;
import com.warden.controlledsandbox.domain.persistence.PersistentStateException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements PackageAdapter.Listener {
    private static final int REQUEST_APK = 1001;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<SandboxRecord> records = new ArrayList<>();
    private final List<SandboxInstance> instances = new ArrayList<>();
    private PackageServiceClient packageService;
    private RuntimeClient runtime;
    private PackageAdapter adapter;
    private TextView runtimeStatus;
    private TextView emptyText;
    private Button importButton;
    private boolean metadataHealthy = true;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        packageService = new PackageServiceClient(this);
        runtime = new RuntimeClient(this);
        runtimeStatus = findViewById(R.id.runtimeStatus);
        emptyText = findViewById(R.id.emptyText);
        ListView list = findViewById(R.id.packageList);
        adapter = new PackageAdapter(this, this);
        list.setAdapter(adapter);
        importButton = findViewById(R.id.importButton);
        importButton.setOnClickListener(v -> chooseApk());
        findViewById(R.id.refreshButton).setOnClickListener(v -> refresh());
        refresh();
        worker.execute(() -> {
            try {
                RuntimeStatusResult status = runtime.status();
                runOnUiThread(() -> {
                    if (status.successful()) {
                        runtimeStatus.setText("Runtime: " + status.status() + " · " + status.capability());
                    } else {
                        runtimeStatus.setText("Runtime error: " + status.error().code() + " · " + status.error().message());
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> runtimeStatus.setText("Runtime error: " + error.getMessage()));
            }
        });
    }

    private void chooseApk() {
        if (!metadataHealthy) {
            Toast.makeText(this, "Package metadata is unavailable; repair it before importing", Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/vnd.android.package-archive");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, REQUEST_APK);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_APK || resultCode != RESULT_OK || data == null) return;
        List<Uri> selected = selectedUris(data);
        if (selected.isEmpty()) return;
        runtimeStatus.setText(selected.size() == 1 ? "Importing APK…"
                : "Importing " + selected.size() + " APK artifacts…");
        worker.execute(() -> importSelection(selected));
    }


    private void importSelection(List<Uri> selected) {
        int sessionId = -1;
        try {
            SandboxRecord imported;
            if (selected.size() == 1) {
                imported = packageService.importApk(selected.get(0));
            } else {
                sessionId = packageService.createInstallSession("");
                for (Uri uri : selected) packageService.addInstallArtifact(sessionId, uri);
                imported = packageService.commitInstallSession(sessionId);
                sessionId = -1;
            }
            SandboxRecord completed = imported;
            runOnUiThread(() -> {
                runtimeStatus.setText(operationMessage("Imported " + completed.packageName
                        + " · artifacts=" + completed.artifacts.size()));
                refresh();
            });
        } catch (Exception error) {
            if (sessionId > 0) {
                try { packageService.abandonInstallSession(sessionId); }
                catch (Exception cleanupFailure) { error.addSuppressed(cleanupFailure); }
            }
            showImportFailure(error);
        }
    }

    private static List<Uri> selectedUris(Intent data) {
        Set<String> seen = new LinkedHashSet<>();
        List<Uri> result = new ArrayList<>();
        Uri direct = data.getData();
        if (direct != null && seen.add(direct.toString())) result.add(direct);
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int index = 0; index < clip.getItemCount(); index++) {
                Uri uri = clip.getItemAt(index).getUri();
                if (uri != null && seen.add(uri.toString())) result.add(uri);
            }
        }
        return result;
    }

    private void showImportFailure(Exception error) {
        runOnUiThread(() -> {
            runtimeStatus.setText("Import failed");
            Toast.makeText(this, error.getClass().getSimpleName() + ": " + error.getMessage(),
                    Toast.LENGTH_LONG).show();
        });
    }

    private void refresh() {
        worker.execute(() -> {
            try {
                SandboxCatalogState state = packageService.load();
                runOnUiThread(() -> applyCatalog(state));
            } catch (PersistentStateException error) {
                runOnUiThread(() -> failClosedMetadata(error));
            } catch (Exception error) {
                runOnUiThread(() -> failClosedMetadata(
                        new PersistentStateException("Cannot load package catalog", error)));
            }
        });
    }

    private void applyCatalog(SandboxCatalogState state) {
        records.clear();
        records.addAll(state.records());
        instances.clear();
        instances.addAll(state.instances());
        List<SandboxItem> items = new ArrayList<>();
        for (SandboxInstance instance : instances) {
            SandboxRecord record = findRecord(instance.packageName);
            if (record != null) items.add(new SandboxItem(record, instance));
        }
        metadataHealthy = true;
        importButton.setEnabled(true);
        adapter.replace(items);
        emptyText.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void failClosedMetadata(PersistentStateException error) {
        metadataHealthy = false;
        records.clear();
        instances.clear();
        importButton.setEnabled(false);
        adapter.replace(List.of());
        emptyText.setVisibility(View.VISIBLE);
        runtimeStatus.setText("Metadata blocked: " + error.getMessage());
    }

    private SandboxRecord findRecord(String packageName) {
        for (SandboxRecord record : records) if (record.packageName.equals(packageName)) return record;
        return null;
    }

    @Override public void onPrepare(SandboxItem item) {
        setBusy("Preparing", item);
        worker.execute(() -> runOperation(item, () -> runtime.prepare(item.record, item.instance.virtualUserId)));
    }

    @Override public void onLaunch(SandboxItem item) {
        setBusy("Launching", item);
        worker.execute(() -> runOperation(item, () -> runtime.launch(item.record, item.instance.virtualUserId)));
    }

    @Override public void onComponentTest(SandboxItem item) {
        setBusy("Testing components for", item);
        worker.execute(() -> {
            try {
                int userId = item.instance.virtualUserId;
                Bundle service = runtime.startService(item.record, userId);
                Bundle receiver = runtime.sendBroadcast(item.record, userId);
                Bundle provider = runtime.prepareProvider(item.record, userId);
                Bundle stop = runtime.stopService(item.record, userId);
                String summary = "Service=" + service.getString("status", "")
                        + " · Receiver=" + receiver.getString("status", "")
                        + " · Provider=" + provider.getString("status", "")
                        + " · Stop=" + stop.getString("status", "");
                saveInstanceStatus(item.instance, summary);
                runOnUiThread(() -> {
                    runtimeStatus.setText(summary);
                    refresh();
                });
            } catch (Exception error) {
                showFailure("Component test failed", error);
            }
        });
    }

    @Override public void onClone(SandboxItem item) {
        worker.execute(() -> {
            try {
                int userId = packageService.createClone(item.record.packageName);
                runOnUiThread(() -> {
                    runtimeStatus.setText("Created clone user=" + userId);
                    refresh();
                });
            } catch (Exception error) {
                showFailure("Clone failed", error);
            }
        });
    }

    @Override public void onDelete(SandboxItem item) {
        worker.execute(() -> {
            try {
                runtime.stop(item.record, item.instance.virtualUserId);
                packageService.deleteInstance(item.record.packageName, item.instance.virtualUserId);
                runOnUiThread(() -> {
                    runtimeStatus.setText(operationMessage("Deleted " + item.record.packageName
                            + " user=" + item.instance.virtualUserId));
                    refresh();
                });
            } catch (Exception error) {
                showFailure("Delete failed", error);
            }
        });
    }

    private void runOperation(SandboxItem item, BundleOperation operation) {
        try {
            Bundle result = operation.run();
            String status = result.getString("status", "UNKNOWN");
            if ("FAILED".equals(status)) {
                status += " / " + result.getString("errorType", "") + ": "
                        + result.getString("errorMessage", "");
            }
            saveInstanceStatus(item.instance, status);
            String display = status + " · user=" + item.instance.virtualUserId
                    + " · pid=" + result.getInt("pid", -1)
                    + " · " + result.getLong("durationMs", -1) + "ms";
            runOnUiThread(() -> {
                runtimeStatus.setText(display);
                refresh();
            });
        } catch (Exception error) {
            showFailure("Runtime operation failed", error);
        }
    }

    private void saveInstanceStatus(SandboxInstance instance, String status) throws Exception {
        packageService.updateInstanceStatus(instance.packageName, instance.virtualUserId, status);
    }

    private String operationMessage(String success) {
        try {
            String warning = packageService.maintenanceWarning();
            return warning.isEmpty() ? success : success + " · Cleanup pending: " + warning;
        } catch (Exception error) {
            return success + " · Cleanup status unavailable: " + error.getMessage();
        }
    }

    private void setBusy(String action, SandboxItem item) {
        runtimeStatus.setText(action + " " + item.record.packageName
                + " user=" + item.instance.virtualUserId + "…");
    }

    private void showFailure(String prefix, Exception error) {
        runOnUiThread(() -> {
            runtimeStatus.setText(prefix);
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        });
    }

    @Override protected void onDestroy() {
        packageService.close();
        runtime.close();
        worker.shutdownNow();
        super.onDestroy();
    }

    private interface BundleOperation { Bundle run() throws Exception; }
}
