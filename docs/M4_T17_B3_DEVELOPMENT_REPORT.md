# M4-T17 B3 开发报告：四 ABI 构建架构与 32 位 Companion

## 基线与结果

- 起点：`9b984bdf9be47ea5896d9fa357a17efdc1df8e3d`（M4-T17 B2）
- 功能提交：`0d0c4f9`
- 结果：PASS
- 设备/模拟器：未执行

## 完成内容

### 四 ABI 分包

- Host Native 模块仅打包 `arm64-v8a`、`x86_64`。
- 新增独立 `sandbox-companion32` Android Application 模块。
- Companion 仅打包 `armeabi-v7a`、`x86`。
- Companion 独立生成 `controlled_sandbox_native32` Hook Library，复用当前 clean-room native policy/hook 源码。
- Host App 不依赖 Companion App 模块，保持两个独立 APK 的部署边界。

### Typed 跨位宽 Binder

新增：

- `INativeAbiCompanion`
- `NativeCompanionRequest`
- `NativeCompanionResult`

请求载荷明确包含：

- Protocol；
- Session ID；
- Generation；
- Virtual User ID；
- Guest Package；
- APK Revision；
- 16～64 字节一次性 Nonce；
- Requested ABI；
- Operation。

契约不使用 `Bundle`。

### 信任与生命周期

- Host 声明 `signature` 级 `BIND_NATIVE_COMPANION` 权限。
- Companion Service 通过该权限导出。
- Host 使用确定的 Package 和 Component 显式绑定。
- Companion 拒绝：
  - 协议版本不匹配；
  - Nonce 重放；
  - 当前进程不是 32 位；
  - ABI 与请求不匹配；
  - 旧 Generation；
  - 同一 Generation 下更换 Session、APK Revision 或 ABI。
- Nonce 和 Generation 注册表均限制为最多 256 项。

### Host ABI 路由

新增 `NativeAbiRoutePlanner`：

| Guest ABI | 路由 |
|---|---|
| 无 Native Library | Host 64 位路径 |
| `arm64-v8a` | Host 64 位路径 |
| `x86_64` | Host 64 位路径 |
| `armeabi-v7a` | 32 位 Companion |
| `x86` | 32 位 Companion |
| `legacy-unknown`／其他 ABI | Fail closed |

`RuntimeClient` 在 32 位 Guest 进入 Host Broker 前先执行显式 Companion Probe。即使 Probe 成功，当前版本仍以 `NATIVE_COMPANION_CROSS_WIDTH_EXECUTION_NOT_WIRED` 拒绝继续在 64 位 Broker 中执行，防止错误 ABI 静默回退。

## 验证

通过：

- Companion Typed Parcelable 往返测试；
- ABI 路由矩阵测试；
- Nonce 边界测试；
- Generation 所有权、陈旧拒绝、身份冲突和容量测试；
- Host/Companion ABI Filters 结构门禁；
- Manifest 签名权限和独立进程门禁；
- 显式 Binder 绑定门禁；
- Companion JNI Host 源码编译；
- 静态 Android 全仓编译和全部 Host self-test；
- Native 文件系统、Loader、Network、Audio、Hook、Crash 回归；
- M3 严格证据门禁；
- 双次可复现源码 ZIP 字节比较。

统一验证脚本在能力矩阵后受到单次执行时限终止，终止前无失败。静态 Android、Native、严格门禁和可复现打包按原顺序分段续跑并全部 PASS。

## 证据边界

本批次证明的是源码和构建架构成立。没有证明：

- Android SDK 实际生成四 ABI APK；
- Host 与 Companion 使用同一签名安装；
- Android 设备上的跨包 Binder 调用；
- 32 位 Guest Activity、Service、Provider 的完整生命周期；
- ARM/x86 四 ABI 真机或模拟器 Hook 行为。

因此 `native.four-abi-build-architecture` 和 `runtime.native-abi-routing` 的 production 状态保留为 partial，设备状态为 not-tested。
