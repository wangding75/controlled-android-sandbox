# SX Legacy Feature Inventory

本清单基于 `D:\github\all_project\sx` 的源码盘点，范围为 `app`、`sandbox-api`、`engine-bb`。共发现 26 个 app Java 文件、21 个 sandbox-api Java 文件、9 个 engine-bb Java 文件。SX 的 BlackBox 运行时、Xposed 入口、授权/服务端业务均作为迁移输入记录，但不把旧实现直接复制进 Controlled Android Sandbox。

## 分类规则

| 分类 | 含义 |
|---|---|
| `MIGRATE` | 业务能力继续保留，改走 Controlled Android Sandbox SDK/Adapter。 |
| `REIMPLEMENT` | 能力保留，但由现有 Sandbox Runtime/Framework/Contract 重新实现。 |
| `REPLACE_BY_SANDBOX` | 旧引擎/Hook 入口由统一 Guest、实例、生命周期和服务边界替代。 |
| `DROP_NON_BUSINESS` | 授权、设备指纹、远端配置等非业务能力不进入本迁移面。 |
| `OBSOLETE` | 与当前产品架构或安全边界冲突，不保留。 |
| `DEFERRED_NON_BUSINESS` | 不是当前验收路径，单独留档，不阻塞简单应用/Quark/钉钉准备。 |

## 功能清单

| SX 功能/入口 | 源码位置 | 分类 | Controlled 处理 | 状态 |
|---|---|---|---|---|
| 应用扫描与 HostAppInfo | `sandbox-api/.../HostAppScanner.java`、`HostAppInfo.java` | `MIGRATE` | 由 Host Package Authority/PackageService 提供已安装包和 revision 信息 | 已映射 |
| 导入 APK / 安装结果 | `sandbox-api/.../InstallResult.java`、`SandboxEngine.installFromApk` | `MIGRATE` | `PackageServiceClient` + 安装 session + `SandboxSdk.importPackage` | 已实现 |
| 安装包目录/应用列表 | `app/.../AppListActivity.java`、`AppListFragment.java`、`SandboxAppAdapter.java` | `MIGRATE` | `MainActivity -> SxSandboxAdapter -> SandboxSdk.catalog` | 已接入 Adapter |
| 启动、停止、杀进程 | `sandbox-api/.../SandboxEngine.java`、`AppDetailActivity.java` | `MIGRATE` | `SandboxSdk.launch/stop`，Runtime Broker/GuestProcessService 执行 | Stage A PASS |
| 实例创建、克隆、删除 | `SandboxEngine.createClone/uninstall/clone` | `REIMPLEMENT` | `ensureInstance/cloneInstance/deleteInstance`，实例目录按 user/package 隔离 | 已实现 |
| 实例数据清理 | SX 无安全等价的统一入口 | `REIMPLEMENT` | 新增 Contract `clearInstanceData`，保留 instance record/root，仅递归清理数据 | Stage A PASS |
| 多用户/多实例身份 | `SandboxAppInfo.java`、`ProfileRepository.java` | `REIMPLEMENT` | `SandboxIdentity` 显式携带 package、instance、virtualUser、virtualUid、process、storage、generation、slot | SDK self-test PASS |
| 设备/位置/网络/蓝牙/相机配置页 | `DeviceSettingsActivity.java`、`LocationSettingsActivity.java`、`NetworkSettingsActivity.java`、`VirtualCameraActivity.java` | `REIMPLEMENT` | 通过统一 identity profile/appDataScope 进入已有 virtual service profile；不复制 Hook | 接口已预留，按能力逐项验收 |
| 位置服务 | `MockLocationService.java`、`LocationPickerActivity.java` | `REIMPLEMENT` | Runtime/Framework 的 virtual service/profile 路径；不修改真实系统 LocationManager | 结构映射 |
| 相机能力 | `CameraConfig.java`、`CameraHook.java`、`VirtualCameraActivity.java` | `REPLACE_BY_SANDBOX` | 先走 Guest/companion/native capability；只有可复现缺陷才进入通用兼容层 | 旧 Hook 未迁移 |
| 设备标识、电话、Cell | `DeviceProfile.java`、`DeviceHook.java`、`CellHook.java` | `REPLACE_BY_SANDBOX` | `SandboxIdentity.androidIdentityProfile` 与 virtual device service profile | 统一身份建模 |
| 网络标识 | `NetworkProfile.java`、`NetworkHook.java` | `REPLACE_BY_SANDBOX` | virtual network service profile；不在 App/Runtime 核心写包名判断 | 统一 profile |
| 蓝牙 | `BluetoothProfile.java`、`BluetoothHook.java` | `REPLACE_BY_SANDBOX` | virtual peripheral profile；不复制 Xposed hook | 旧 Hook 未迁移 |
| DingTalk 隐私偏好写盘 | `DingTalkHook.initPrivacyPreferencesOnDisk` | `REIMPLEMENT` | 仅在有日志/复现证据后设计通用 scoped storage/SharedPreferences 兼容；默认不启用钉钉补丁 | Stage C 仅准备 |
| FakeSandboxEngine | `app/.../FakeSandboxEngine.java` | `OBSOLETE` | 不作为产品运行时；测试替身改用 SDK self-test/fixture | 不迁移 |
| Shortcut/桌面快捷方式 | `ShortcutLaunchActivity.java`、`SandboxEngine.createShortcut` | `MIGRATE` | 以 `SandboxSdk.launch` + 统一 instanceId 作为后续 Shortcut adapter 目标 | 接口映射 |
| 应用名称/标签 | `SandboxEngine.setDisplayName`、`SandboxAppInfo` | `MIGRATE` | `SandboxPackage.label` 与 catalog/UI DTO | 接口映射 |
| 配置广播/偏好仓库 | `ConfigBroadcast.java`、`SxPrefs.java`、`ConfigProvider.java` | `DEFERRED_NON_BUSINESS` | 不将 SX 全局偏好直接带入；配置必须落在 instance/profile scope | 非当前验收面 |
| License/设备指纹/时间保护 | `LicenseManager.java`、`LicenseConfig.java`、`DeviceFingerprint.java`、`TimeGuard.java` | `DROP_NON_BUSINESS` | 不迁移到 Sandbox SDK；由上层产品另行决定 | 明确排除 |
| SX Server License/远程配置 | `SxServerLicenseClient.java`、`ConfigProvider.java` | `DEFERRED_NON_BUSINESS` | 不调用、不复制服务端契约 | 明确延期 |
| Xposed module 入口 | `app/src/main/assets/xposed_init` | `OBSOLETE` | Controlled 使用明确的 Host/Guest/companion 边界，不使用 Xposed 注入 | 不迁移 |
| BlackBoxCore 初始化 | `BlackBoxSandboxEngine.java` | `REPLACE_BY_SANDBOX` | 由现有 Package Authority、Runtime Broker、Guest Process、32-bit companion 替代 | 无 BlackBox 依赖 |

## 迁移结论

保留的是“导入、实例、身份、生命周期、组件调用、配置 scope”这些业务/平台能力；删除的是 SX 授权与远端业务之外的控制平面；旧 BlackBox/Xposed/包名特判不进入新核心。所有后续应用兼容都必须先落到 SDK、Adapter 或通用 Runtime 修复，并有可复现证据。
