# SX Legacy Hook Audit

审计对象是 `D:\github\all_project\sx\engine-bb\src\main\java\com\sx\app\sandbox\spoof`。结论是：没有把旧 Hook 类、Xposed 入口或 BlackBox 初始化复制到 Controlled Android Sandbox。每个能力按“通用身份/系统服务/真实 Sandbox 缺陷/Android 兼容/应用特定/废弃”重新分类。

| 旧实现 | 原因 | 分类 | 新处理 |
|---|---|---|---|
| `LocationHook.java` | 通过 Xposed 替换 LocationManager/定位读取 | `GENERAL_SYSTEM_SERVICE` | 进入 virtual service/profile 设计；由 Guest/Runtime scope 提供，不改真实系统服务 |
| `DeviceHook.java` | 伪造设备号、Build/设备属性 | `GENERAL_IDENTITY` | 使用 `SandboxIdentity.androidIdentityProfile`；identity 在 session/generation/runtime key 中显式传递 |
| `NetworkHook.java` | 替换网络与标识读取 | `GENERAL_SYSTEM_SERVICE` | 使用 virtual network service profile；核心不读取 package name 做分支 |
| `CellHook.java` | 伪造 Telephony/Cell 信息 | `GENERAL_SYSTEM_SERVICE` | 使用 virtual device/telephony profile；需要逐项能力证据 |
| `CameraHook.java` | 替换 Camera/相机枚举与输出 | `GENERAL_SYSTEM_SERVICE` | ABI/companion capability route；不复制方法级 Hook |
| `BluetoothHook.java` | 替换 BluetoothAdapter/设备列表 | `GENERAL_SYSTEM_SERVICE` | virtual peripheral profile；不将 SX 的 hook 逻辑带入 Guest |
| `DingTalkHook.java` | 修改钉钉隐私偏好/特定读取行为 | `APP_SPECIFIC` | 仅保留为证据驱动候选；通过 `CompatibilityPatchRegistry` 显式 enable，默认关闭 |
| `SpoofRuntime.java` | 从配置加载并安装上述全部 Hook | `OBSOLETE` | 不迁移；改由统一 SDK identity/profile 和 Runtime capability 组合提供 |
| `BlackBoxSandboxEngine.java` 内的 `BlackBoxCore` 初始化 | 旧虚拟化引擎生命周期、安装、启动 | `SANDBOX_DEFECT` | 由 Package Authority、Runtime Broker、Guest Process、32-bit companion 和 `SandboxSdk` 替代 |

## 分类边界

- `GENERAL_IDENTITY`：影响多个应用且应由 `SandboxIdentity` 统一描述，不允许应用包名分支。
- `GENERAL_SYSTEM_SERVICE`：影响 Android 服务语义，应由 virtual service/profile 或 Runtime/Framework 修复。
- `SANDBOX_DEFECT`：旧引擎能力缺失，优先修复 Controlled 核心，而不是复制旧引擎。
- `ANDROID_COMPAT`：只在 Android 版本差异有可复现证据时进入兼容补丁注册表。
- `APP_SPECIFIC`：必须有目标应用、版本、堆栈/日志证据和回归测试；默认关闭。
- `OBSOLETE`：不再符合边界或属于非业务控制面。

## 负向审计

受控源码中不应出现 `com.sx`、`BlackBoxCore`、`xposed_init` 运行时依赖，也不应在 core/runtime 以 `com.alibaba.android.rimet` 或 `com.quark.browser` 做逻辑分支。唯一允许的目标包字符串是验收工具/文档中的输入；兼容补丁注册表本身不做包名检查，必须由外部显式选择 patch。
