# SX → Controlled Android Sandbox Mapping

迁移后的唯一业务路径为：

`UI/Application → SxSandboxAdapter → sandbox-sdk → PackageService/RuntimeClient → Contract/Binder → Host/Runtime/Guest → MuMu`

Debug ADB command 是验收工具，不是生产 UI 的业务入口；生产 UI 不再直接持有 `PackageServiceClient` 或 `RuntimeClient`。

| SX 功能 | SX 原入口 | 旧底层 | Sandbox SDK | 迁移方案 | 状态 |
|---|---|---|---|---|---|
| 应用列表 | `AppListActivity` / `SandboxEngine.listInstalled` | BlackBox installed apps | `SandboxSdk.catalog()` | Adapter 将 PackageCatalogSnapshot 转为 `SandboxCatalog` | 已接入 |
| 导入 APK | `SandboxEngine.installFromApk` | BlackBox install | `importPackage(Path)` | PackageService install session、artifact、commit，保留 sha256/revision | 已接入 |
| 导入并启动 | `AppDetailActivity` | BlackBox launch | `importPackage` + `launch` | Adapter 先确认 package revision，再创建 instance、prepare、launch | Stage A PASS |
| 准备运行时 | `SandboxEngine.initialize/onAttach/onAppCreate` | BlackBoxCore lifecycle | `status` / `ensureInstance` | Package Authority 和 Runtime Broker 负责启动；连接器可重绑定 | 已接入 |
| 启动 Activity | `SandboxEngine.launch` | BlackBox virtual Activity | `launch(SandboxInstance)` | 统一 `SandboxIdentity` 生成 RuntimeKey，StubActivity 只接受当前 generation | Stage A PASS |
| 停止进程 | `SandboxEngine.kill` | BlackBox process manager | `stop(SandboxInstance)` | Broker/companion stopGuest，禁止悬挂旧 Binder | Stage A PASS |
| 清理数据 | SX 无安全统一 API | BlackBox user data | `clearData(SandboxInstance)` | AIDL 新增 `clearInstanceData`，目录根保持不变并拒绝 symlink | Stage A PASS |
| 删除实例 | `SandboxEngine.uninstall` / `clearData` | BlackBox package/user store | `deleteInstance` | 停止后由 lifecycle 原子删除实例目录与记录 | Stage A PASS |
| 克隆实例 | `SandboxEngine.clone` | BlackBox user clone | `cloneInstance` | 分配新的 virtualUserId/instanceId，复制受控 profile，不共享 dataRoot | 接口已接入 |
| 组件 Service | SX Engine 组件启动 | BlackBox component proxy | `startService`（Adapter 内部） | Runtime component operation + GuestProcessService | Stage A PASS |
| 组件 Broadcast | SX Engine 组件广播 | BlackBox receiver dispatch | `sendBroadcast`（Adapter 内部） | Manifest/动态 receiver registry，按 priority/scope 路由 | Stage A PASS |
| 组件 Provider | SX Engine provider | BlackBox provider proxy | `prepareProvider`（Adapter 内部） | authority/session/generation 绑定，Provider transport 走 Binder | Stage A PASS |
| 应用显示名 | `setDisplayName` | BlackBox app label | `SandboxPackage.label` | catalog DTO/UI 显示，不改变 package identity | 已映射 |
| 快捷方式 | `createShortcut` / `ShortcutLaunchActivity` | BlackBox shortcut | `launch` + instanceId | Shortcut 只保存公开 instance identity，启动仍走 SDK | 接口映射 |
| 设备 profile | `DeviceSettingsActivity` / `DeviceProfile` | DeviceHook | `SandboxIdentity.androidIdentityProfile` | profile 存储位于 instance scope；运行时统一读取 | 结构映射 |
| 位置 profile | `LocationSettingsActivity` / `LocationHook` | Xposed LocationManager | `SandboxIdentity.appDataScope` + virtual profile | 作为通用 virtual service 能力，不注入包名代码 | 结构映射 |
| 网络 profile | `NetworkSettingsActivity` / `NetworkHook` | Xposed network hook | virtual network profile | 由 network service profile 提供 | 结构映射 |
| 蓝牙 profile | `BluetoothProfile` / `BluetoothHook` | Xposed Bluetooth hook | virtual peripheral profile | 由 peripheral profile 提供 | 结构映射 |
| 相机 profile | `CameraConfig` / `CameraHook` | Xposed camera hook | virtual peripheral/companion route | 先基于 ABI/能力路由，缺陷修复必须有证据 | 结构映射 |
| Cell profile | `CellHook` | Xposed telephony hook | virtual device service profile | 统一 identity 与 profile，禁止包名分支 | 结构映射 |
| DingTalk private prefs | `DingTalkHook` | App-specific disk mutation | `CompatibilityPatchRegistry` | Patch 默认 disabled；有证据才 enable，且 patch 不进入核心 identity | 准备中 |
| License | `LicenseManager` | SX server/device fingerprint | 无 | 不属于 sandbox business contract | DROP |
| Xposed bootstrap | `xposed_init` | Xposed module | 无 | Controlled 采用显式 Host/Guest boundary | OBSOLETE |

## SDK 边界

公开 SDK 只使用 `SandboxPackage`、`SandboxInstance`、`SandboxIdentity`、`SandboxCatalog`、`SandboxOperationResult` 等 DTO；Binder、Android `Context`、SX/BlackBox 类名不出现在公开边界。生产 UI 通过 `SxSandboxAdapter` 使用 SDK，Adapter 负责把旧 UI 事件映射到现有受控服务。
