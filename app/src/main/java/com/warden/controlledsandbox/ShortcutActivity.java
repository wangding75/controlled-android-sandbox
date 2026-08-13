package com.warden.controlledsandbox;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

/** Launcher entry point for a pinned exact-instance shortcut. */
public final class ShortcutActivity extends Activity {
    private SandboxViewModel viewModel;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String packageName = getIntent().getStringExtra(SandboxShortcutManager.EXTRA_PACKAGE_NAME);
        int virtualUserId = getIntent().getIntExtra(
                SandboxShortcutManager.EXTRA_VIRTUAL_USER_ID, -1);
        if (!SandboxShortcutManager.ACTION_LAUNCH_INSTANCE.equals(getIntent().getAction())
                || packageName == null || packageName.trim().isEmpty() || virtualUserId < 0) {
            finish();
            return;
        }
        viewModel = new SandboxViewModel(this);
        viewModel.execute(() -> {
            SandboxRecord record = viewModel.application().findRecord(packageName);
            if (record == null) throw new IllegalArgumentException("Shortcut instance is invalid");
            boolean exists = false;
            for (SandboxInstance instance : viewModel.application().load().instances()) {
                if (packageName.equals(instance.packageName)
                        && virtualUserId == instance.virtualUserId) {
                    exists = true;
                    break;
                }
            }
            if (!exists) throw new IllegalArgumentException("Shortcut instance is invalid");
            Bundle result = viewModel.application().launch(record, virtualUserId);
            String status = result == null ? "FAILED"
                    : result.getString(RuntimeKeys.STATUS, "FAILED");
            if ("FAILED".equals(status)) throw new IllegalStateException("Shortcut launch failed");
            viewModel.application().updateInstanceStatus(packageName, virtualUserId, status);
            return result;
        }, result -> runOnUiThread(this::finish), error -> runOnUiThread(() -> {
            Toast.makeText(this, "Shortcut is no longer valid", Toast.LENGTH_SHORT).show();
            finish();
        }));
    }

    @Override protected void onDestroy() {
        if (viewModel != null) viewModel.close();
        super.onDestroy();
    }
}
