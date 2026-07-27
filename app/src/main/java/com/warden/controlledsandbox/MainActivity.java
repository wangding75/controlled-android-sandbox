package com.warden.controlledsandbox;

import android.app.Activity;
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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements PackageAdapter.Listener {
    private static final int REQUEST_APK = 1001;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<SandboxRecord> records = new ArrayList<>();
    private final List<SandboxInstance> instances = new ArrayList<>();
    private SandboxPackageLifecycle packageLifecycle;
    private RuntimeClient runtime;
    private PackageAdapter adapter;
    private TextView runtimeStatus;
    private TextView emptyText;
    private Button importButton;
    private boolean metadataHealthy = true;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        packageLifecycle = new SandboxPackageLifecycle(this);
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
        startActivityForResult(intent, REQUEST_APK);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_APK || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        runtimeStatus.setText("Importing APK…");
        worker.execute(() -> {
            try {
                SandboxRecord imported = packageLifecycle.importApk(uri);
                runOnUiThread(() -> {
                    runtimeStatus.setText(operationMessage("Imported " + imported.packageName));
                    refresh();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    runtimeStatus.setText("Import failed");
                    Toast.makeText(this, error.getClass().getSimpleName() + ": " + error.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void refresh() {
        synchronized (records) {
            try {
                SandboxCatalogState state = packageLifecycle.load();
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
            } catch (PersistentStateException error) {
                failClosedMetadata(error);
            } catch (Exception error) {
                failClosedMetadata(new PersistentStateException("Cannot load package catalog", error));
            }
        }
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
                int userId = packageLifecycle.createClone(item.record.packageName);
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
                packageLifecycle.deleteInstance(item.record.packageName, item.instance.virtualUserId);
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
        packageLifecycle.updateInstanceStatus(instance.packageName, instance.virtualUserId, status);
    }

    private String operationMessage(String success) {
        String warning = packageLifecycle.maintenanceWarning();
        return warning.isEmpty() ? success : success + " · Cleanup pending: " + warning;
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
        runtime.close();
        worker.shutdownNow();
        super.onDestroy();
    }

    private interface BundleOperation { Bundle run() throws Exception; }
}
