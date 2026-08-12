package com.warden.controlledsandbox;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.warden.controlledsandbox.contract.RuntimeStatusResult;

/** Engineering-only diagnostics surface kept outside the ordinary product home. */
public final class DeveloperDiagnosticsActivity extends Activity {
    private SandboxViewModel viewModel;
    private SandboxApplicationLayer application;
    private TextView output;
    private String packageName;
    private int userId;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        packageName = getIntent().getStringExtra(InstanceSettingsActivity.EXTRA_PACKAGE_NAME);
        userId = getIntent().getIntExtra(InstanceSettingsActivity.EXTRA_USER_ID, 0);
        viewModel = new SandboxViewModel(this);
        application = viewModel.application();
        buildUi();
        refresh();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        TextView title = new TextView(this);
        title.setText("开发者诊断"); title.setTextSize(22); title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);
        TextView notice = new TextView(this);
        notice.setText("本页保留 Runtime、组件和 Profile 诊断；普通用户首页不展示这些内部术语。");
        notice.setTextSize(13); root.addView(notice, margins(0, 8, 0, 8));
        output = new TextView(this); output.setTextSize(13); output.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this); scroll.addView(output);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        Button refresh = new Button(this); refresh.setText("刷新诊断"); refresh.setAllCaps(false); refresh.setOnClickListener(v -> refresh());
        root.addView(refresh);
        Button smoke = new Button(this); smoke.setText("组件烟测（Stage A 诊断）"); smoke.setAllCaps(false); smoke.setOnClickListener(v -> componentSmoke());
        root.addView(smoke);
        setContentView(root);
    }

    private void refresh() {
        output.setText("正在读取诊断…");
        viewModel.execute(() -> {
            RuntimeStatusResult runtime = application.runtimeStatus();
            SandboxCatalogState catalog = application.load();
            String maintenance = application.maintenanceWarning();
            StringBuilder text = new StringBuilder();
            text.append("Runtime status\n");
            if (runtime.successful()) {
                text.append("status=").append(runtime.status()).append('\n')
                        .append("capability=").append(runtime.capability()).append('\n');
                if (runtime.snapshot() != null) text.append("slots=").append(runtime.snapshot().slotUsed())
                        .append('/').append(runtime.snapshot().slotCapacity()).append(" sessions=")
                        .append(runtime.snapshot().sessionCount()).append('\n');
            } else {
                text.append("error=").append(runtime.error().code()).append(':')
                        .append(runtime.error().message()).append('\n');
            }
            text.append("maintenance=").append(maintenance).append('\n')
                    .append("catalog packages=").append(catalog.records().size())
                    .append(" instances=").append(catalog.instances().size()).append('\n')
                    .append("verification boundary=API32 Runtime; Android 14/15/16 and Xiaomi HyperOS="
                            + "REAL_DEVICE_VERIFICATION_PENDING\n");
            if (packageName == null && !catalog.instances().isEmpty()) {
                SandboxInstance first = catalog.instances().get(0); packageName = first.packageName; userId = first.virtualUserId;
            }
            if (packageName != null) {
                text.append("scope=").append(packageName).append("/u").append(userId).append('\n')
                        .append("deviceProfileVersion=").append(application.deviceProfile(packageName, userId).policyVersion()).append('\n')
                        .append("networkProfileVersion=").append(application.networkProfile(packageName, userId).policyVersion()).append('\n')
                        .append("peripheralProfileVersion=").append(application.peripheralProfile(packageName, userId).policyVersion()).append('\n');
            }
            return text.toString();
        }, text -> runOnUiThread(() -> output.setText(text)), error -> runOnUiThread(() -> output.setText("诊断失败：" + error.getMessage())));
    }

    private void componentSmoke() {
        if (packageName == null) { output.setText("没有实例可执行组件烟测"); return; }
        output.setText("正在执行组件烟测…");
        viewModel.execute(() -> application.componentSmoke(packageName, userId), result -> runOnUiThread(() -> {
            StringBuilder text = new StringBuilder("Component smoke\n");
            for (String key : new String[]{"prepare", "service", "receiver", "provider", "stop"}) {
                Bundle value = result.getBundle(key);
                text.append(key).append('=').append(value == null ? "NO_RESULT" : value.getString("status", "UNKNOWN"));
                if (value != null && "FAILED".equals(value.getString("status"))) text.append(' ').append(value.getString("errorMessage", ""));
                text.append('\n');
            }
            output.setText(text);
        }), error -> runOnUiThread(() -> output.setText("组件烟测失败：" + error.getMessage())));
    }

    private LinearLayout.LayoutParams margins(int l, int t, int r, int b) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(dp(l), dp(t), dp(r), dp(b)); return p; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override protected void onDestroy() { if (viewModel != null) viewModel.close(); super.onDestroy(); }
}
