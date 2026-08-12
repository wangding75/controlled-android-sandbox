package com.warden.controlledsandbox;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import com.warden.controlledsandbox.compatibility.dingtalk.DingTalkCompatibilityManager;
import com.warden.controlledsandbox.contract.VirtualCameraProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualCameraSourceSnapshot;
import com.warden.controlledsandbox.contract.VirtualCellInfoSnapshot;
import com.warden.controlledsandbox.contract.VirtualConnectivityProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualDeviceIdentitySnapshot;
import com.warden.controlledsandbox.contract.VirtualDeviceServiceProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualNetworkServiceProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPeripheralServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualTelephonyProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualTelephonySlotSnapshot;
import com.warden.controlledsandbox.contract.VirtualWifiNetworkSnapshot;
import com.warden.controlledsandbox.contract.VirtualWifiProfileSnapshot;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Scoped F2-F5 and DingTalk profile editor. */
public final class InstanceSettingsActivity extends Activity {
    public static final String EXTRA_PACKAGE_NAME = "packageName";
    public static final String EXTRA_USER_ID = "virtualUserId";
    public static final String EXTRA_SECTION = "section";
    private static final int REQUEST_CAMERA_MEDIA = 2001;

    private SandboxViewModel viewModel;
    private SandboxApplicationLayer application;
    private String packageName;
    private int userId;
    private String section = "location";
    private SandboxRecord record;
    private VirtualDeviceServiceProfileSnapshot device;
    private VirtualNetworkServiceProfileSnapshot network;
    private VirtualPeripheralServicesProfileSnapshot peripheral;
    private LinearLayout body;
    private TextView status;
    private Uri pendingMediaUri;
    private String pendingMediaKind = VirtualCameraSourceSnapshot.IMAGE;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        packageName = getIntent().getStringExtra(EXTRA_PACKAGE_NAME);
        userId = getIntent().getIntExtra(EXTRA_USER_ID, 0);
        section = getIntent().getStringExtra(EXTRA_SECTION);
        if (section == null || section.trim().isEmpty()) section = "location";
        viewModel = new SandboxViewModel(this);
        application = viewModel.application();
        buildShell();
        loadProfiles();
    }

    private void buildShell() {
        LinearLayout root = column(0);
        LinearLayout header = column(16);
        TextView title = label("实例设置", 22, true);
        header.addView(title);
        header.addView(label(packageName + " · 实例 " + userId, 13, false), margins(0, 6, 0, 0));
        status = label("正在读取 Profile…", 12, false);
        header.addView(status, margins(0, 8, 0, 0));
        root.addView(header);
        LinearLayout tabs = row(4);
        addTab(tabs, "定位", "location");
        addTab(tabs, "相机", "camera");
        addTab(tabs, "设备", "device");
        addTab(tabs, "网络", "network");
        addTab(tabs, "钉钉", "dingtalk");
        root.addView(tabs, margins(8, 0, 8, 4));
        ScrollView scroll = new ScrollView(this);
        body = column(16);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private void loadProfiles() {
        viewModel.execute(() -> {
            record = application.findRecord(packageName);
            if (record == null) throw new IllegalArgumentException("实例对应的 App 不存在");
            device = application.deviceProfile(packageName, userId);
            network = application.networkProfile(packageName, userId);
            peripheral = application.peripheralProfile(packageName, userId);
            return Boolean.TRUE;
        }, ignored -> runOnUiThread(() -> {
            status.setText("已加载 · 所有修改写入当前实例 Profile");
            renderSection();
        }), error -> runOnUiThread(() -> {
            status.setText("Profile 读取失败");
            showError(error);
        }));
    }

    private void addTab(LinearLayout tabs, String title, String value) {
        Button button = new Button(this);
        button.setText(title); button.setAllCaps(false);
        button.setOnClickListener(v -> { section = value; renderSection(); });
        tabs.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private void renderSection() {
        if (device == null || network == null || peripheral == null) return;
        body.removeAllViews();
        switch (section) {
            case "camera" -> renderCamera();
            case "device" -> renderDevice();
            case "network" -> renderNetwork();
            case "dingtalk" -> renderDingTalk();
            default -> renderLocation();
        }
    }

    private void renderLocation() {
        VirtualLocationProfileSnapshot current = device.location();
        body.addView(title("F2 · 虚拟定位"));
        body.addView(note("稳定的坐标/Profile 编辑。地图 SDK 尚未接入，地图选点明确标记为 NOT_IMPLEMENTED。"));
        Switch enabled = toggle("启用位置模拟", !VirtualLocationProfileSnapshot.MODE_BLOCKED.equals(current.mode())
                && current.providerEnabled());
        body.addView(enabled);
        EditText latitude = field("纬度", value(current.latitude()), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        EditText longitude = field("经度", value(current.longitude()), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        EditText altitude = field("海拔（米）", value(current.altitudeMeters()), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        EditText accuracy = field("精度（米）", value(current.accuracyMeters()), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText speed = field("速度（米/秒）", value(current.speedMetersPerSecond()), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText bearing = field("方向（0-360）", value(current.bearingDegrees()), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText interval = field("更新间隔（毫秒）", Long.toString(current.minimumUpdateIntervalMs()), InputType.TYPE_CLASS_NUMBER);
        body.addView(latitude); body.addView(longitude); body.addView(altitude);
        body.addView(accuracy); body.addView(speed); body.addView(bearing); body.addView(interval);
        Spinner trajectory = spinner("轨迹模式", new String[]{VirtualLocationProfileSnapshot.TRAJECTORY_FIXED,
                VirtualLocationProfileSnapshot.TRAJECTORY_WALKING, VirtualLocationProfileSnapshot.TRAJECTORY_CYCLING,
                VirtualLocationProfileSnapshot.TRAJECTORY_DRIVING, VirtualLocationProfileSnapshot.TRAJECTORY_ROUTE}, current.trajectoryMode());
        body.addView(trajectory);
        body.addView(note("当前轨迹点数量：" + current.trajectoryPoints().size() + "。移动轨迹沿用同一个 Location Profile。"));
        Button map = action("地图选点 · NOT_IMPLEMENTED"); map.setEnabled(false); body.addView(map);
        Button save = action(R.string.action_save); save.setOnClickListener(v -> saveLocation(current, enabled,
                latitude, longitude, altitude, accuracy, speed, bearing, interval, trajectory)); body.addView(save);
        Button reset = action(R.string.action_reset); reset.setOnClickListener(v -> resetLocation()); body.addView(reset);
    }

    private void saveLocation(VirtualLocationProfileSnapshot current, Switch enabled,
            EditText latitude, EditText longitude, EditText altitude, EditText accuracy,
            EditText speed, EditText bearing, EditText interval, Spinner trajectory) {
        try {
            String mode = enabled.isChecked() ? VirtualLocationProfileSnapshot.MODE_STATIC
                    : VirtualLocationProfileSnapshot.MODE_BLOCKED;
            String trajectoryMode = String.valueOf(trajectory.getSelectedItem());
            VirtualLocationProfileSnapshot next = new VirtualLocationProfileSnapshot(mode,
                    current.provider().isEmpty() ? "gps" : current.provider(), enabled.isChecked(),
                    number(latitude), number(longitude), number(altitude), (float) number(accuracy),
                    (float) number(speed), (float) number(bearing), current.timeMs(),
                    current.elapsedRealtimeNanos(), longNumber(interval), current.gnssEnabled(),
                    current.satellitesInView(), current.satellitesUsedInFix(), current.nmeaSentence(),
                    trajectoryMode, current.trajectoryIntervalMs(), current.trajectoryPoints(),
                    current.timestampPolicy(), current.elapsedRealtimePolicy());
            saveDevice(new VirtualDeviceServiceProfileSnapshot(device.policyVersion(), device.updatedAtMs(),
                    next, device.identity(), device.telephony(), device.wifi(), device.bluetooth(), device.sensors()));
        } catch (Exception error) { showError(error); }
    }

    private void renderCamera() {
        VirtualCameraProfileSnapshot current = peripheral.camera();
        body.addView(title("F3 · 虚拟相机"));
        body.addView(note("用户只配置媒体源；Camera1 / Camera2 属于 Runtime 自动兼容能力，不暴露为选项。"));
        Switch enabled = toggle("启用 Capture substitution", current.cameraAvailable()); body.addView(enabled);
        RadioGroup group = new RadioGroup(this); group.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton image = new RadioButton(this); image.setText("IMAGE"); image.setId(View.generateViewId());
        RadioButton video = new RadioButton(this); video.setText("VIDEO"); video.setId(View.generateViewId());
        group.addView(image); group.addView(video);
        boolean videoSelected = current.source().isConfigured()
                && VirtualCameraSourceSnapshot.VIDEO.equals(current.source().kind());
        (videoSelected ? video : image).setChecked(true); body.addView(group, margins(0, 10, 0, 0));
        TextView preview = note(cameraPreview(current.source())); body.addView(preview, margins(0, 8, 0, 0));
        Button choose = action(R.string.action_choose_media);
        choose.setOnClickListener(v -> { pendingMediaKind = video.isChecked() ? VirtualCameraSourceSnapshot.VIDEO : VirtualCameraSourceSnapshot.IMAGE; chooseMedia(); });
        body.addView(choose);
        Button clear = action(R.string.action_clear_media);
        clear.setOnClickListener(v -> setCameraSource(VirtualCameraSourceSnapshot.none(), enabled.isChecked())); body.addView(clear);
        Button save = action(R.string.action_save);
        save.setOnClickListener(v -> saveCamera(current, enabled.isChecked(), video.isChecked())); body.addView(save);
    }

    private void chooseMedia() {
        String type = VirtualCameraSourceSnapshot.VIDEO.equals(pendingMediaKind) ? "video/*" : "image/*";
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(type), REQUEST_CAMERA_MEDIA);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAMERA_MEDIA || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        pendingMediaUri = data.getData();
        status.setText("正在导入媒体…");
        viewModel.execute(() -> application.importCameraSource(packageName, userId, pendingMediaUri, pendingMediaKind),
                wrapper -> runOnUiThread(() -> setCameraSource(wrapper.snapshot(), true)),
                error -> runOnUiThread(() -> showError(error)));
    }

    private void saveCamera(VirtualCameraProfileSnapshot current, boolean enabled, boolean video) {
        VirtualCameraSourceSnapshot source = current.source();
        if (source.isConfigured() && video != VirtualCameraSourceSnapshot.VIDEO.equals(source.kind())) {
            source = VirtualCameraSourceSnapshot.none();
        }
        setCameraSource(source, enabled);
    }

    private void setCameraSource(VirtualCameraSourceSnapshot source, boolean enabled) {
        VirtualCameraProfileSnapshot current = peripheral.camera();
        List<String> ids = current.cameraIds().isEmpty() && enabled ? List.of("0") : current.cameraIds();
        int maximum = current.maximumOpenCameras() == 0 && enabled ? 1 : current.maximumOpenCameras();
        VirtualCameraProfileSnapshot next = new VirtualCameraProfileSnapshot(
                VirtualLocationProfileSnapshot.MODE_STATIC, enabled, enabled,
                current.allowTorch(), maximum, ids, current.frontCameraIds(), current.torchAvailableCameraIds(),
                source, current.substituteCaptureResult());
        VirtualPeripheralServicesProfileSnapshot updated = new VirtualPeripheralServicesProfileSnapshot(
                peripheral.policyVersion(), peripheral.updatedAtMs(), peripheral.nfc(), peripheral.usb(),
                peripheral.printing(), peripheral.companionDevice(), peripheral.mediaProjection(), next,
                peripheral.oemSystemServices());
        viewModel.execute(() -> application.savePeripheralProfile(packageName, userId, updated),
                value -> runOnUiThread(() -> { peripheral = value; status.setText("相机 Profile 已保存"); renderSection(); }),
                error -> runOnUiThread(() -> showError(error)));
    }

    private void renderDevice() {
        VirtualDeviceIdentitySnapshot identity = device.identity();
        VirtualTelephonySlotSnapshot slot = device.telephony().defaultSlot();
        if (slot == null) { body.addView(note("当前实例没有 SIM Profile")); return; }
        body.addView(title("F4 · 设备信息"));
        body.addView(note("所有身份字段写入同一个 VirtualDeviceServiceProfile；不会维护第二套 UI 身份数据。"));
        Switch enabled = toggle("启用虚拟设备信息", !VirtualLocationProfileSnapshot.MODE_BLOCKED.equals(identity.mode())); body.addView(enabled);
        EditText androidId = field("Android ID（16 位十六进制）", identity.androidId(), InputType.TYPE_CLASS_TEXT);
        EditText brand = field("品牌 Brand", identity.brand(), InputType.TYPE_CLASS_TEXT);
        EditText model = field("型号 Model", identity.model(), InputType.TYPE_CLASS_TEXT);
        EditText manufacturer = field("制造商 Manufacturer", identity.manufacturer(), InputType.TYPE_CLASS_TEXT);
        EditText serial = field("序列号 Serial", identity.serial(), InputType.TYPE_CLASS_TEXT);
        EditText imei = field("IMEI", slot.imei(), InputType.TYPE_CLASS_NUMBER);
        EditText meid = field("MEID", slot.meid(), InputType.TYPE_CLASS_TEXT);
        EditText sim = field("SIM / 手机号", slot.line1Number(), InputType.TYPE_CLASS_PHONE);
        EditText imsi = field("IMSI", slot.subscriberId(), InputType.TYPE_CLASS_NUMBER);
        EditText iccid = field("ICCID", slot.simSerialNumber(), InputType.TYPE_CLASS_NUMBER);
        EditText operator = field("运营商", slot.carrierName(), InputType.TYPE_CLASS_TEXT);
        body.addView(androidId); body.addView(brand); body.addView(model); body.addView(manufacturer); body.addView(serial);
        body.addView(imei); body.addView(meid); body.addView(sim); body.addView(imsi); body.addView(iccid); body.addView(operator);
        Button random = action("随机生成"); random.setOnClickListener(v -> {
            String hex = UUID.randomUUID().toString().replace("-", "");
            androidId.setText(hex.substring(0, 16));
            serial.setText("CS" + hex.substring(16, 28).toUpperCase(Locale.ROOT));
            imei.setText(randomDigits(15));
            meid.setText(hex.substring(0, 14).toUpperCase(Locale.ROOT));
        }); body.addView(random);
        Button save = action(R.string.action_save); save.setOnClickListener(v -> saveDevice(enabled.isChecked(), androidId, brand, model,
                manufacturer, serial, imei, meid, sim, imsi, iccid, operator)); body.addView(save);
        Button reset = action(R.string.action_reset); reset.setOnClickListener(v -> resetDevice()); body.addView(reset);
    }

    private void saveDevice(boolean enabled, EditText androidId, EditText brand, EditText model,
            EditText manufacturer, EditText serial, EditText imei, EditText meid, EditText sim,
            EditText imsi, EditText iccid, EditText operator) {
        try {
            VirtualDeviceIdentitySnapshot old = device.identity();
            String mode = enabled ? VirtualLocationProfileSnapshot.MODE_STATIC : VirtualLocationProfileSnapshot.MODE_BLOCKED;
            VirtualDeviceIdentitySnapshot identity = new VirtualDeviceIdentitySnapshot(mode, text(androidId), text(serial),
                    old.advertisingId(), old.limitAdTracking(), old.installationId(), text(manufacturer), text(brand),
                    text(model), old.device(), old.product(), old.fingerprint(), old.board(), old.hardware());
            VirtualTelephonyProfileSnapshot oldTelephony = device.telephony();
            VirtualTelephonySlotSnapshot oldSlot = oldTelephony.defaultSlot();
            VirtualTelephonySlotSnapshot slot = new VirtualTelephonySlotSnapshot(oldSlot.slotIndex(), oldSlot.subscriptionId(),
                    text(imei), text(meid), text(imsi), text(iccid), text(sim), oldSlot.simOperator(), oldSlot.networkOperator(),
                    oldSlot.simCountryIso(), oldSlot.networkCountryIso(), text(operator), oldSlot.phoneType(), oldSlot.simState(),
                    oldSlot.dataNetworkType(), oldSlot.voiceNetworkType(), oldSlot.dataEnabled(), oldSlot.roaming());
            VirtualTelephonyProfileSnapshot telephony = new VirtualTelephonyProfileSnapshot(mode,
                    oldTelephony.defaultSubscriptionId(), oldTelephony.activeDataSubscriptionId(), oldTelephony.voiceCapable(),
                    oldTelephony.smsCapable(), oldTelephony.emergencyOnly(), List.of(slot), oldTelephony.cells());
            saveDevice(new VirtualDeviceServiceProfileSnapshot(device.policyVersion(), device.updatedAtMs(), device.location(),
                    identity, telephony, device.wifi(), device.bluetooth(), device.sensors()));
        } catch (Exception error) { showError(error); }
    }

    private void saveDevice(VirtualDeviceServiceProfileSnapshot next) {
        viewModel.execute(() -> application.saveDeviceProfile(packageName, userId, next),
                value -> runOnUiThread(() -> { device = value; status.setText("设备 Profile 已保存"); renderSection(); }),
                error -> runOnUiThread(() -> showError(error)));
    }

    private void resetDevice() {
        viewModel.execute(() -> application.resetDeviceProfile(packageName, userId),
                value -> runOnUiThread(() -> { device = value; status.setText("已恢复默认设备 Profile"); renderSection(); }),
                error -> runOnUiThread(() -> showError(error)));
    }

    private void resetLocation() {
        viewModel.execute(() -> {
            VirtualDeviceServiceProfileSnapshot defaults = application.defaultDeviceProfile(packageName, userId);
            VirtualDeviceServiceProfileSnapshot current = application.deviceProfile(packageName, userId);
            return application.saveDeviceProfile(packageName, userId, new VirtualDeviceServiceProfileSnapshot(
                    current.policyVersion(), current.updatedAtMs(), defaults.location(), current.identity(),
                    current.telephony(), current.wifi(), current.bluetooth(), current.sensors()));
        }, value -> runOnUiThread(() -> { device = value; status.setText("已恢复默认定位 Profile"); renderSection(); }),
                error -> runOnUiThread(() -> showError(error)));
    }

    private void renderNetwork() {
        VirtualWifiProfileSnapshot wifi = device.wifi();
        VirtualTelephonyProfileSnapshot telephony = device.telephony();
        VirtualTelephonySlotSnapshot slot = telephony.defaultSlot();
        VirtualCellInfoSnapshot cell = telephony.cells().isEmpty() ? null : telephony.cells().get(0);
        body.addView(title("F5 · 网络与基站"));
        body.addView(note("Wi-Fi / Cell 属于当前实例设备 Profile；Network Service 同步保存连通性模式。"));
        Switch enabled = toggle("启用网络 Profile", wifi.enabled() && !VirtualLocationProfileSnapshot.MODE_BLOCKED.equals(wifi.mode())); body.addView(enabled);
        EditText ssid = field("Wi-Fi SSID", wifi.ssid(), InputType.TYPE_CLASS_TEXT);
        EditText bssid = field("Wi-Fi BSSID", wifi.bssid(), InputType.TYPE_CLASS_TEXT);
        EditText mac = field("Wi-Fi MAC", wifi.macAddress(), InputType.TYPE_CLASS_TEXT);
        EditText mcc = field("MCC", cell == null ? "460" : Integer.toString(cell.mcc()), InputType.TYPE_CLASS_NUMBER);
        EditText mnc = field("MNC", cell == null ? "0" : Integer.toString(cell.mnc()), InputType.TYPE_CLASS_NUMBER);
        EditText lac = field("LAC", cell == null ? "0" : Integer.toString(cell.lac()), InputType.TYPE_CLASS_NUMBER);
        EditText cid = field("CID", cell == null ? "0" : Long.toString(cell.cid()), InputType.TYPE_CLASS_NUMBER);
        EditText scans = field("扫描 Profile（每行 SSID,BSSID,level）", scanText(wifi), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        scans.setMinLines(4); scans.setGravity(Gravity.TOP);
        body.addView(ssid); body.addView(bssid); body.addView(mac); body.addView(mcc); body.addView(mnc); body.addView(lac); body.addView(cid); body.addView(scans);
        Button save = action(R.string.action_save); save.setOnClickListener(v -> saveNetwork(enabled.isChecked(), ssid, bssid, mac, mcc, mnc, lac, cid, scans)); body.addView(save);
        Button reset = action(R.string.action_reset); reset.setOnClickListener(v -> resetNetwork()); body.addView(reset);
    }

    private void saveNetwork(boolean enabled, EditText ssid, EditText bssid, EditText mac,
            EditText mcc, EditText mnc, EditText lac, EditText cid, EditText scans) {
        try {
            VirtualWifiProfileSnapshot oldWifi = device.wifi();
            List<VirtualWifiNetworkSnapshot> scanResults = parseScans(text(scans));
            VirtualWifiProfileSnapshot wifi = new VirtualWifiProfileSnapshot(
                    enabled ? VirtualLocationProfileSnapshot.MODE_STATIC : VirtualLocationProfileSnapshot.MODE_BLOCKED,
                    enabled, text(ssid), text(bssid), text(mac), oldWifi.ipv4Address(), oldWifi.networkId(),
                    oldWifi.linkSpeedMbps(), oldWifi.rssi(), oldWifi.frequencyMhz(), oldWifi.metered(), oldWifi.hiddenSsid(), scanResults);
            VirtualTelephonyProfileSnapshot oldTelephony = device.telephony();
            VirtualTelephonySlotSnapshot oldSlot = oldTelephony.defaultSlot();
            VirtualCellInfoSnapshot oldCell = oldTelephony.cells().isEmpty() ? null : oldTelephony.cells().get(0);
            VirtualCellInfoSnapshot newCell = new VirtualCellInfoSnapshot(oldCell == null ? VirtualCellInfoSnapshot.LTE : oldCell.technology(),
                    intNumber(mcc), intNumber(mnc), intNumber(lac), oldCell == null ? 0 : oldCell.tac(), longNumber(cid),
                    oldCell == null ? 0 : oldCell.pci(), oldCell == null ? 0 : oldCell.arfcn(),
                    oldCell == null || oldCell.registered(), oldCell == null ? -84 : oldCell.signalLevel());
            VirtualTelephonyProfileSnapshot telephony = new VirtualTelephonyProfileSnapshot(oldTelephony.mode(),
                    oldTelephony.defaultSubscriptionId(), oldTelephony.activeDataSubscriptionId(), oldTelephony.voiceCapable(),
                    oldTelephony.smsCapable(), oldTelephony.emergencyOnly(), oldTelephony.slots(), List.of(newCell));
            VirtualDeviceServiceProfileSnapshot nextDevice = new VirtualDeviceServiceProfileSnapshot(device.policyVersion(),
                    device.updatedAtMs(), device.location(), device.identity(), telephony, wifi, device.bluetooth(), device.sensors());
            VirtualConnectivityProfileSnapshot connectivity = network.connectivity();
            VirtualConnectivityProfileSnapshot nextConnectivity = new VirtualConnectivityProfileSnapshot(
                    enabled ? VirtualLocationProfileSnapshot.MODE_STATIC : VirtualLocationProfileSnapshot.MODE_BLOCKED,
                    connectivity.defaultNetworkId(), connectivity.airplaneMode(), connectivity.backgroundRestricted(),
                    connectivity.maximumCallbacks(), connectivity.networks());
            VirtualNetworkServiceProfileSnapshot nextNetwork = new VirtualNetworkServiceProfileSnapshot(network.policyVersion(),
                    network.updatedAtMs(), nextConnectivity, network.dns(), network.proxy(), network.vpn());
            viewModel.execute(() -> {
                application.saveDeviceProfile(packageName, userId, nextDevice);
                return application.saveNetworkProfile(packageName, userId, nextNetwork);
            }, value -> runOnUiThread(() -> { device = nextDevice; network = value; status.setText("网络 / Cell Profile 已保存"); renderSection(); }),
                    error -> runOnUiThread(() -> showError(error)));
        } catch (Exception error) { showError(error); }
    }

    private void resetNetwork() {
        viewModel.execute(() -> {
            VirtualDeviceServiceProfileSnapshot defaults = application.defaultDeviceProfile(packageName, userId);
            VirtualDeviceServiceProfileSnapshot current = application.deviceProfile(packageName, userId);
            application.saveDeviceProfile(packageName, userId, new VirtualDeviceServiceProfileSnapshot(
                    current.policyVersion(), current.updatedAtMs(), current.location(), current.identity(),
                    defaults.telephony(), defaults.wifi(), current.bluetooth(), current.sensors()));
            application.resetNetworkProfile(packageName, userId);
            return Boolean.TRUE;
        }, ignored -> runOnUiThread(() -> {
            status.setText("已恢复默认网络 / Cell Profile");
            loadProfiles();
        }), error -> runOnUiThread(() -> showError(error)));
    }

    private void renderDingTalk() {
        body.addView(title("钉钉兼容模式"));
        body.addView(note("仅对 " + DingTalkCompatibilityManager.PACKAGE_NAME
                + " 7.8.10 / 1178 开放。按钮只调用 DingTalkCompatibilityManager；不 Hook、改 dex、改 exported 或伪造 Camera/Location。"));
        DingTalkCompatibilityManager manager = new DingTalkCompatibilityManager();
        DingTalkCompatibilityManager.Target target = manager.identify(record.packageName, record.versionName, record.versionCode);
        body.addView(note("目标：" + record.packageName + " · " + record.versionName + " / " + record.versionCode + "\n判定：" + target.reason()));
        Switch enabled = toggle("DingTalk 兼容模式（默认 OFF）", target.supported() && application.dingTalkEnabled(record.packageName, userId));
        enabled.setEnabled(target.supported()); body.addView(enabled);
        Button save = action(R.string.action_save); save.setEnabled(target.supported());
        save.setOnClickListener(v -> viewModel.execute(() -> { application.setDingTalkEnabled(target, userId, enabled.isChecked()); return null; },
                ignored -> runOnUiThread(() -> { status.setText("DingTalk 兼容模式已保存"); renderSection(); }),
                error -> runOnUiThread(() -> showError(error)))); body.addView(save);
        body.addView(note(target.supported() ? "状态由兼容管理器持久化，Profile 编排仍使用通用 Sandbox 合约。"
                : "NOT_IMPLEMENTED：当前实例不是受支持的 DingTalk 版本。"));
    }

    private List<VirtualWifiNetworkSnapshot> parseScans(String raw) {
        List<VirtualWifiNetworkSnapshot> result = new ArrayList<>();
        for (String line : raw.split("\\r?\\n")) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split(",");
            if (parts.length < 3) throw new IllegalArgumentException("扫描 Profile 格式应为 SSID,BSSID,level");
            result.add(new VirtualWifiNetworkSnapshot(parts[0].trim(), parts[1].trim(), "[ESS]", 0,
                    Integer.parseInt(parts[2].trim()), false));
            if (result.size() > 128) throw new IllegalArgumentException("扫描 Profile 最多 128 行");
        }
        return result;
    }
    private String scanText(VirtualWifiProfileSnapshot wifi) {
        StringBuilder out = new StringBuilder();
        for (VirtualWifiNetworkSnapshot value : wifi.scanResults()) out.append(value.ssid()).append(',')
                .append(value.bssid()).append(',').append(value.rssi()).append('\n');
        return out.toString();
    }
    private String cameraPreview(VirtualCameraSourceSnapshot source) {
        if (source == null || !source.isConfigured()) return "当前媒体：未选择\nCapture substitution：未配置";
        return "当前媒体：" + source.kind() + "\n路径：" + source.relativePath() + "\n预览："
                + source.width() + "×" + source.height() + " · SHA-256 " + source.sha256().substring(0, Math.min(12, source.sha256().length()));
    }
    private String text(EditText value) { return value.getText().toString().trim(); }
    private double number(EditText value) { return Double.parseDouble(text(value)); }
    private long longNumber(EditText value) { return Long.parseLong(text(value)); }
    private int intNumber(EditText value) { return Integer.parseInt(text(value)); }
    private String randomDigits(int count) {
        String raw = Long.toUnsignedString(UUID.randomUUID().getMostSignificantBits())
                + Long.toUnsignedString(UUID.randomUUID().getLeastSignificantBits());
        StringBuilder out = new StringBuilder(count);
        for (int index = 0; out.length() < count && index < raw.length(); index++) {
            char value = raw.charAt(index);
            if (value >= '0' && value <= '9') out.append(value);
        }
        while (out.length() < count) out.append('0');
        return out.toString();
    }
    private String value(double value) { return String.format(Locale.ROOT, "%.6f", value); }
    private void showError(Exception error) { Toast.makeText(this, "保存失败：" + error.getMessage(), Toast.LENGTH_LONG).show(); }

    private TextView title(String value) { return label(value, 20, true); }
    private TextView note(String value) { return label(value, 13, false); }
    private Switch toggle(String title, boolean checked) { Switch value = new Switch(this); value.setText(title); value.setChecked(checked); return value; }
    private EditText field(String title, String value, int inputType) {
        EditText input = new EditText(this); input.setHint(title); input.setText(value); input.setInputType(inputType); input.setSelectAllOnFocus(false); return input;
    }
    private Spinner spinner(String title, String[] values, String selected) {
        Spinner spinner = new Spinner(this);
        spinner.setPrompt(title);
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, values));
        int selectedIndex = Arrays.asList(values).indexOf(selected); if (selectedIndex >= 0) spinner.setSelection(selectedIndex);
        return spinner;
    }
    private Button action(int text) { return action(getString(text)); }
    private Button action(String text) { Button value = new Button(this); value.setText(text); value.setAllCaps(false); return value; }
    private LinearLayout column(int padding) { LinearLayout value = new LinearLayout(this); value.setOrientation(LinearLayout.VERTICAL); if (padding > 0) value.setPadding(dp(padding), dp(padding), dp(padding), dp(padding)); return value; }
    private LinearLayout row(int padding) { LinearLayout value = new LinearLayout(this); value.setOrientation(LinearLayout.HORIZONTAL); if (padding > 0) value.setPadding(dp(padding), 0, dp(padding), 0); return value; }
    private TextView label(String text, int size, boolean bold) { TextView value = new TextView(this); value.setText(text); value.setTextSize(size); value.setTextColor(getColor(R.color.text_primary)); if (bold) value.setTypeface(null, android.graphics.Typeface.BOLD); return value; }
    private LinearLayout.LayoutParams margins(int left, int top, int right, int bottom) { LinearLayout.LayoutParams value = new LinearLayout.LayoutParams(-1, -2); value.setMargins(dp(left), dp(top), dp(right), dp(bottom)); return value; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override protected void onDestroy() { if (viewModel != null) viewModel.close(); super.onDestroy(); }
}
