# M4-T17 阶段报告：Native Hook 与 ABI 架构

## 基线

- 正式起点：`82148251767a29ef810ccabd0c359ac684a0e36e`（M4-T16）
- B1：`cf3cdc105bdde6998acc0457813f5d7cf0c862be`
- B2：`9b984bdf9be47ea5896d9fa357a17efdc1df8e3d`
- B3 功能提交：`0d0c4f9`
- 正式标签：最终文档和门禁通过后冻结为 `m4-t17-source-pass`
- 设备验证：未执行

## 本阶段新增能力

### Native 文件系统与 Procfs

- `openat2`
- `statx`
- `renameat2`
- `faccessat2`
- `getdents64`
- File-backed `mmap`
- `/proc/self/maps`
- `/proc/self/cmdline`
- `/proc/self/status`

所有路径先进入 Guest confinement policy。跨根目录 rename、Host 私有映射和未授权 Proc 身份采用 fail-closed。

### 动态库加载

- `dlopen`
- `android_dlopen_ext`
- Guest Native Library 根目录映射
- Split APK Native Library 来源接线
- Public System Soname Allowlist
- Host 私有路径、未知 Soname、错误 Linker Namespace 拒绝
- Native ABI 作为显式 Package/Runtime 元数据持久化，不再根据 `lib` 目录名推断

### 网络身份

- IPv4／IPv6 Socket 和 Connect 策略
- Forward／Reverse DNS
- 虚拟 Hostname 和 `uname` nodename
- 有界合成 Network Interface
- Proxy、Cleartext 和 Network Security 元数据
- Host Interface 和 Host 网络身份不向 Guest 返回

### 录音链路

- RECORD_AUDIO 与 AppOps 决策进入 Native Gate
- Generation-bound Native Capture Token
- AAudio start/stop Hook
- NDK MediaRecorder start/stop Hook
- 权限或 Generation 撤销后失效全部活动 Token
- 对已跟踪 Native Handle 执行 best-effort stop
- 既有 Binder Audio Lease 继续负责 Java/Binder 资源死亡清理

### ABI 架构

- Host APK Native Runtime：`arm64-v8a`、`x86_64`
- 独立 Companion APK：`armeabi-v7a`、`x86`
- Bundle-free Typed Binder Contract
- Signature Permission
- 显式 Package/Component Binding
- Nonce Replay 防护
- Session/Generation/Virtual User/APK Revision 身份绑定
- 独立 `controlled_sandbox_native32` Hook Library
- Unknown／Legacy ABI Fail-closed
- 32 位完整 Guest 执行未接线时拒绝静默回退

## 架构变化

- `native_hook.cpp` 保持为扫描和安装协调层，主要 syscall/network/audio wrapper 移入独立 interceptor/policy 文件。
- Native 文件、Proc、Loader、Network、Audio 分别拥有独立策略和 Host-native self-test。
- Package Record、Package Service DTO、Runtime Request 和 Guest Spec 均携带显式 Native ABI。
- 新增独立 32 位 Application Module，没有把 32 位库混入 64 位 Host APK。
- 跨位宽接口只依赖 `sandbox-contract`，没有让 Companion 反向依赖 Runtime、Framework 或 App 内部模型。
- Runtime 对未完成的 32 位执行路径 fail-closed，避免用源码架构冒充可运行兼容性。

## 验证

已通过：

- M4-T17 B1 Filesystem/Proc/Loader 专项；
- M4-T17 B2 Network/Audio 专项；
- M4-T17 B3 ABI/Companion 专项；
- Typed AIDL 和模块边界；
- M4-T14 Service、M4-T15 Activity/Task、M4-T16 System Scheduling 回归；
- 静态 Android 全仓编译和全部 Host self-test；
- Native Policy、Filesystem、Procfs、Loader、Network、Audio、PLT Hook、Crash；
- Host 64 位 JNI 和 Companion JNI 源码编译；
- M3 严格证据门禁；
- 双次可复现源码 ZIP 字节比较；
- Shell、Python 和 PowerShell 静态检查。

完整验证因单次执行上限分段运行。各分段均按统一脚本原顺序衔接，未跳过失败项。

## 仓库指标

| 项目 | 数量 |
|---|---:|
| Git 跟踪文件 | 694 |
| Java 文件 | 424 |
| AIDL 文件 | 45 |
| Java + AIDL 行数 | 51,377 |
| M4-T16 → M4-T17 变更文件 | 79 |
| 新增／删除行 | 4,012／401 |
| 能力条目 | 110 |
| 源码 complete／partial | 106／4 |
| 源码加权完成度 | 98.2% |
| 生产 wired／partial | 100／8 |
| 生产 blocked／n/a | 1／1 |
| 生产加权完成度 | 95.4% |
| 设备 verified | 0 |
| 设备证据完成度 | 0.0% |

仓库指标是能力证据统计，不代表第三方 APK 启动率。

## 质量判断

M4-T17 的有效改进集中在三点：

1. 新增 Native 能力均有独立策略层、显式输入和 Host-native 执行测试。
2. Native ABI 从隐式目录推断升级为 Package/Runtime 全链路权威字段。
3. 32/64 位边界通过独立 APK 和 Typed Binder 表达，未采用不可行的单进程混合 ABI 假设。

仍然存在的主要技术债务：

1. 32 位 Companion 尚未承接 Guest Activity/Service/Provider 完整执行，Runtime 只完成 Probe 和 fail-closed 路由。
2. `android_dlopen_ext`、Linker Namespace 和 Bionic 私有实现只完成源码策略，缺少 Android 版本矩阵。
3. OpenSL ES、Java AudioRecord 和 AudioSystem Binder 的设备路径仍不完整。
4. VPN、ConnectivityService、Network Security Config 的真实系统投影未设备验证。
5. 设备证据为 0，不能评价四 ABI Hook 成功率、第三方 APK 兼容率或 20 分钟稳定性。

## 下一阶段

按冻结路线进入 M4-T18：设备测试前源码总收口。

- 全仓 Review 和重复代码清理；
- 拆分剩余 God Class；
- 审核 AIDL Bundle、Guest 查询 Host 回落、PID/UID/Generation 绑定；
- 审核持久化容量、写入回滚、死亡清理和 APK Revision 清理；
- 重新统计目录、文件和行数；
- 重算 VA/NBB 对比矩阵；
- 生成设备测试前未完成清单；
- 冻结正式源码标签。

M4-T18 仍是源码收口阶段。真实模拟器/真机证据应在其冻结基线之后单独执行。

## 仍不确定的点

- Android 26～35 各版本 Bionic 符号和 Linker Namespace 行为。
- Companion APK 的签名、安装顺序、后台启动和跨包 Binder 限制。
- ARM 32/64 与 x86 32/64 的真实 ELF 加载和 Hook 行为。
- OEM 网络、录音、SELinux 和 Native Crash 行为。
- 第三方 APK 的实际运行率和稳定性。
