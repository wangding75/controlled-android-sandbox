package com.warden.controlledsandbox;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.app.AlertDialog;
import com.warden.controlledsandbox.contract.InstallSessionParamsSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Flash 2 product shell: Home, Apps, and Me. Developer diagnostics are a separate destination. */
public final class MainActivity extends Activity implements PackageAdapter.Listener {
    private static final int REQUEST_APK = 1001;
    private SandboxViewModel viewModel;
    private final List<SandboxRecord> records = new ArrayList<>();
    private final List<SandboxInstance> instances = new ArrayList<>();
    private PackageAdapter adapter;
    private FrameLayout content;
    private TextView toolbar;
    private TextView appStatus;
    private TextView emptyText;
    private Button importButton;
    private boolean metadataHealthy = true;
    private SandboxUiState uiState = SandboxUiState.empty();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        viewModel = new SandboxViewModel(this);
        toolbar = findViewById(R.id.mainToolbar);
        content = findViewById(R.id.contentContainer);
        findViewById(R.id.navHome).setOnClickListener(v -> dispatch(SandboxUiAction.HOME));
        findViewById(R.id.navApps).setOnClickListener(v -> dispatch(SandboxUiAction.APPS));
        findViewById(R.id.navMe).setOnClickListener(v -> dispatch(SandboxUiAction.ME));
        adapter = new PackageAdapter(this, this);
        showHome();
        refresh();
    }

    private void dispatch(SandboxUiAction action) {
        switch (action) {
            case HOME -> showHome();
            case APPS -> showApps();
            case ME -> showMe();
            default -> { }
        }
    }

    private void showHome() {
        toolbar.setText(R.string.app_name_full);
        content.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = column(16);
        root.addView(card("沙箱已就绪", "导入 App 后，每个实例都有独立的运行状态与 Profile。"));
        TextView heading = label("功能模块", 18, true);
        root.addView(heading, margins(0, 18, 0, 8));
        addFeature(root, "沙箱应用", "导入应用 · 多实例 · 启动与数据管理", () -> showApps());
        addFeature(root, "虚拟定位", "坐标、精度、速度、固定位置与轨迹模式", () -> openDefaultSettings("location"));
        addFeature(root, "虚拟相机", "图片 / 视频媒体源与 Capture substitution", () -> openDefaultSettings("camera"));
        addFeature(root, "设备信息", "Android ID、IMEI/MEID、品牌、型号与 SIM", () -> openDefaultSettings("device"));
        addFeature(root, "网络与基站", "Wi-Fi SSID/BSSID/MAC 与 MCC/MNC/LAC/CID", () -> openDefaultSettings("network"));
        TextView hint = label("开发者诊断位于“我的”，不会出现在普通用户首页。", 12, false);
        root.addView(hint, margins(0, 18, 0, 0));
        scroll.addView(root);
        content.addView(scroll);
    }

    private void showApps() {
        toolbar.setText(R.string.tab_apps);
        content.removeAllViews();
        LinearLayout root = column(0);
        LinearLayout actions = row(12);
        importButton = actionButton(R.string.action_import);
        importButton.setOnClickListener(v -> chooseApk());
        Button refresh = actionButton(R.string.action_refresh);
        refresh.setOnClickListener(v -> refresh());
        actions.addView(importButton, weight(1));
        actions.addView(refresh, weight(1));
        root.addView(actions, margins(12, 12, 12, 4));
        appStatus = label("正在读取应用列表…", 13, false);
        root.addView(appStatus, margins(14, 0, 14, 0));
        emptyText = label(getString(R.string.empty_apps), 15, false);
        emptyText.setGravity(Gravity.CENTER);
        root.addView(emptyText, new LinearLayout.LayoutParams(-1, 72));
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        content.addView(root);
        applyCurrentCatalog();
    }

    private void showMe() {
        toolbar.setText(R.string.tab_me);
        content.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = column(16);
        root.addView(label("我的", 22, true));
        root.addView(label("闪现2 · 当前 Sandbox 架构", 15, false), margins(0, 10, 0, 0));
        root.addView(card("实例 Profile", "定位、相机、设备、Wi-Fi 与蜂窝信息都按 App / 实例保存。"), margins(0, 18, 0, 0));
        Button diagnostics = actionButton(R.string.action_diagnostics);
        diagnostics.setOnClickListener(v -> startActivity(new Intent(this, DeveloperDiagnosticsActivity.class)));
        root.addView(diagnostics, margins(0, 18, 0, 0));
        scroll.addView(root);
        content.addView(scroll);
    }

    private void addFeature(LinearLayout parent, String title, String description, Runnable action) {
        TextView card = label(title + "\n" + description, 15, false);
        card.setTextColor(getColor(R.color.text_primary));
        card.setBackgroundResource(R.drawable.flash_card);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> action.run());
        parent.addView(card, margins(0, 0, 0, 10));
    }

    private TextView card(String title, String description) {
        TextView view = label(title + "\n" + description, 15, false);
        view.setTextColor(getColor(R.color.text_primary));
        view.setBackgroundResource(R.drawable.flash_card);
        view.setPadding(dp(14), dp(14), dp(14), dp(14));
        return view;
    }

    private void openDefaultSettings(String section) {
        if (instances.isEmpty()) {
            Toast.makeText(this, "请先在“应用”中导入 App", Toast.LENGTH_SHORT).show();
            showApps();
            return;
        }
        SandboxInstance instance = instances.get(0);
        openSettings(instance.packageName, instance.virtualUserId, section);
    }

    private void openSettings(String packageName, int userId, String section) {
        Intent intent = new Intent(this, InstanceSettingsActivity.class)
                .putExtra(InstanceSettingsActivity.EXTRA_PACKAGE_NAME, packageName)
                .putExtra(InstanceSettingsActivity.EXTRA_USER_ID, userId)
                .putExtra(InstanceSettingsActivity.EXTRA_SECTION, section);
        startActivity(intent);
    }

    private void chooseApk() {
        if (!metadataHealthy) {
            Toast.makeText(this, "应用元数据不可用，请先修复后再导入", Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/vnd.android.package-archive")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
        startActivityForResult(intent, REQUEST_APK);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_APK || resultCode != RESULT_OK || data == null) return;
        List<Uri> selected = selectedUris(data);
        if (selected.isEmpty()) return;
        appStatus.setText("正在导入…");
        confirmNativeGuestImport(selected.get(0));
    }

    private void confirmNativeGuestImport(Uri uri) {
        new AlertDialog.Builder(this)
                .setTitle("Native Guest trust")
                .setMessage("This APK may contain native Guest code. Trust it only after reviewing the APK; PLT/GOT compatibility hooks are not a security boundary.")
                .setPositiveButton("Trust and import", (dialog, which) -> importSelectedApk(uri,
                        InstallSessionParamsSnapshot.NATIVE_GUEST_TRUST_EXPLICITLY_TRUSTED))
                .setNegativeButton("Do not trust", (dialog, which) -> importSelectedApk(uri,
                        InstallSessionParamsSnapshot.NATIVE_GUEST_TRUST_UNTRUSTED))
                .setOnCancelListener(dialog -> {
                    if (appStatus != null) appStatus.setText("Import cancelled");
                })
                .show();
    }

    private void importSelectedApk(Uri uri, String nativeGuestTrust) {
        viewModel.execute(() -> viewModel.application().importApk(uri, nativeGuestTrust),
                record -> runOnUiThread(() -> {
                    appStatus.setText("已导入 " + record.label);
                    refresh();
                }),
                error -> runOnUiThread(() -> showFailure("导入失败", error)));
    }

    private static List<Uri> selectedUris(Intent data) {
        Set<String> seen = new LinkedHashSet<>();
        List<Uri> result = new ArrayList<>();
        if (data.getData() != null && seen.add(data.getData().toString())) result.add(data.getData());
        ClipData clip = data.getClipData();
        if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) {
            Uri uri = clip.getItemAt(i).getUri();
            if (uri != null && seen.add(uri.toString())) result.add(uri);
        }
        return result;
    }

    private void refresh() {
        viewModel.execute(() -> viewModel.application().load(),
                state -> runOnUiThread(() -> {
                    uiState = new SandboxUiState(state, true, "");
                    records.clear(); records.addAll(state.records());
                    instances.clear(); instances.addAll(state.instances());
                    metadataHealthy = true;
                    applyCurrentCatalog();
                }),
                error -> runOnUiThread(() -> {
                    uiState = new SandboxUiState(SandboxCatalogState.empty(), false, error.getMessage());
                    metadataHealthy = false;
                    records.clear(); instances.clear();
                    if (importButton != null) importButton.setEnabled(false);
                    if (appStatus != null) appStatus.setText("应用元数据已阻止：" + error.getMessage());
                    if (emptyText != null) emptyText.setVisibility(View.VISIBLE);
                    adapter.replace(List.of());
                }));
    }

    private void applyCurrentCatalog() {
        if (adapter == null) return;
        List<SandboxItem> items = new ArrayList<>();
        for (SandboxInstance instance : uiState.catalog().instances()) {
            SandboxRecord record = findRecord(instance.packageName);
            if (record != null) items.add(new SandboxItem(record, instance));
        }
        adapter.replace(items);
        if (importButton != null) importButton.setEnabled(metadataHealthy);
        if (emptyText != null) emptyText.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private SandboxRecord findRecord(String packageName) {
        for (SandboxRecord record : records) if (record.packageName.equals(packageName)) return record;
        return null;
    }

    @Override public void onLaunch(SandboxItem item) {
        if (appStatus != null) appStatus.setText("正在启动 " + item.record.label + "…");
        viewModel.execute(() -> viewModel.application().launch(item.record, item.instance.virtualUserId),
                result -> runOnUiThread(() -> completeOperation(item, result, "启动")),
                error -> runOnUiThread(() -> showFailure("启动失败", error)));
    }

    @Override public void onSettings(SandboxItem item) {
        openSettings(item.instance.packageName, item.instance.virtualUserId, "location");
    }

    @Override public void onStop(SandboxItem item) {
        if (appStatus != null) appStatus.setText("正在停止 " + item.record.label + "…");
        viewModel.execute(() -> {
                    viewModel.application().stop(item.record, item.instance.virtualUserId);
                    viewModel.application().updateInstanceStatus(
                            item.instance.packageName, item.instance.virtualUserId, "STOPPED");
                    return null;
                }, ignored -> runOnUiThread(() -> {
                    if (appStatus != null) appStatus.setText("停止：STOPPED");
                    refresh();
                }),
                error -> runOnUiThread(() -> showFailure("停止失败", error)));
    }

    @Override public void onClone(SandboxItem item) {
        viewModel.execute(() -> viewModel.application().createClone(item.record.packageName),
                userId -> runOnUiThread(() -> { appStatus.setText("已创建分身 " + userId); refresh(); }),
                error -> runOnUiThread(() -> showFailure("克隆失败", error)));
    }

    @Override public void onClear(SandboxItem item) {
        confirm("清除实例数据", "清除“" + item.record.label + " / " + item.instance.displayName + "”的数据？",
                () -> viewModel.execute(() -> { viewModel.application().clearData(
                                item.record.packageName, item.instance.virtualUserId); return null; },
                        ignored -> runOnUiThread(() -> { appStatus.setText("实例数据已清除"); refresh(); }),
                        error -> runOnUiThread(() -> showFailure("清除失败", error))));
    }

    @Override public void onDelete(SandboxItem item) {
        confirm("删除实例", "删除“" + item.record.label + " / " + item.instance.displayName + "”？",
                () -> viewModel.execute(() -> { viewModel.application().deleteInstance(
                                item.record.packageName, item.instance.virtualUserId); return null; },
                        ignored -> runOnUiThread(() -> { appStatus.setText("实例已删除"); refresh(); }),
                        error -> runOnUiThread(() -> showFailure("删除失败", error))));
    }

    private void completeOperation(SandboxItem item, Bundle result, String operation) {
        String status = result == null ? "FAILED" : result.getString("status", "UNKNOWN");
        viewModel.execute(() -> { viewModel.application().updateInstanceStatus(
                        item.instance.packageName, item.instance.virtualUserId, status); return null; },
                ignored -> runOnUiThread(() -> { appStatus.setText(operation + "：" + status); refresh(); }),
                error -> runOnUiThread(() -> showFailure(operation + "状态保存失败", error)));
    }

    private void confirm(String title, String message, Runnable positive) {
        new android.app.AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setNegativeButton("取消", null).setPositiveButton("确认", (dialog, which) -> positive.run()).show();
    }

    private void showFailure(String prefix, Exception error) {
        if (appStatus != null) appStatus.setText(prefix);
        Toast.makeText(this, prefix + "：" + String.valueOf(error.getMessage()), Toast.LENGTH_LONG).show();
    }

    private LinearLayout column(int padding) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        if (padding > 0) root.setPadding(dp(padding), dp(padding), dp(padding), dp(padding));
        return root;
    }
    private LinearLayout row(int padding) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        if (padding > 0) root.setPadding(dp(padding), 0, dp(padding), 0);
        return root;
    }
    private Button actionButton(int text) {
        Button button = new Button(this);
        button.setText(text); button.setAllCaps(false);
        return button;
    }
    private TextView label(String text, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(size);
        view.setTextColor(getColor(R.color.text_secondary));
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }
    private LinearLayout.LayoutParams margins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom)); return params;
    }
    private LinearLayout.LayoutParams weight(float value) {
        return new LinearLayout.LayoutParams(0, -2, value);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        if (viewModel != null) viewModel.close();
        super.onDestroy();
    }
}
