# M4-T18 设备测试前未完成能力清单

## 判断口径

M4-T18 冻结的是可进入 Android 构建和模拟器验证的源码基线。当前没有执行真实 AGP/NDK 构建、模拟器、真机或第三方 APK 测试，因此设备证据保持 0，以下项目不得从 Host self-test 推断为已完成。

## 一、生产接线仍未完全闭合

| 能力 | 当前状态 | 进入设备测试前后的处理 |
|---|---|---|
| Ordered Broadcast 平台来源与完整结果链 | Partial | 在 Android 真实广播队列中验证 abort/result/timeout/后台限制；再决定是否补平台回调适配 |
| Declared remote/isolated process | Blocked | 当前只保留规划和 fail-closed；需要独立进程槽、UID/SELinux 和 Binder 生命周期设备证据 |
| Native network hook | Partial | 补 VPN、ConnectivityService、socket option、Network Security Config 与 OEM 差异验证 |
| Native dynamic loader hook | Partial | 验证各 API Bionic 符号、RELRO、Linker Namespace、压缩/直接 APK native library 路径 |
| Crash/ANR diagnostics | Partial | 需要真实进程崩溃、主线程阻塞、Native tombstone 和证据导出验证 |
| Foreground Service 完整模型 | Partial | 验证通知时限、FGS type、后台启动限制、系统终止与重启行为 |
| 四 ABI Android 构建 | Partial | 生成并检查 arm64-v8a、x86_64 Host 和 armeabi-v7a、x86 Companion APK/ELF |
| 32 位 Guest Runtime | Partial | 补齐 Companion 中 Activity/Service/Receiver/Provider 完整执行与恢复链路 |

机器可读清单位于 `verification/m4-t18-device-preflight.json`，并由 `check-m4-t18-final-freeze.py` 与能力矩阵交叉校验。

## 二、必须取得的设备证据

### 1. 锁定工具链构建

- JDK 17。
- Gradle 8.13 的已校验发行包。
- Android Gradle Plugin 8.11.1。
- compileSdk 36、targetSdk 35、Build Tools 35.0.0。
- NDK 27.2.12479018、CMake 3.22.1。
- Host、Fixture、Companion APK 的 SHA-256 和 ABI 清单。

### 2. 安装、签名与跨包 Binder

- Host 与 Companion 使用相同签名安装。
- Signature Permission 能阻止非授权调用方。
- 64 位 Host 和 32 位 Companion 的进程/ELF 位宽正确。
- Companion 重启、Binder death、Nonce 重放和 stale Generation 均按预期拒绝或恢复。

### 3. 包、身份和存储隔离

- 同一 APK 的两个虚拟实例使用不同虚拟 UID 和数据根。
- SharedPreferences、数据库、文件、缓存和 device-protected storage 不交叉。
- APK Revision 更新后旧 Session、Task、Service、Alarm、Notification、Job、PendingIntent 和 Native 状态被清理。
- Guest 查询失败时不泄露宿主包、任务、网络接口或文件路径。

### 4. Activity 与 Task

- 五种 LaunchMode 和冻结 Intent Flag 组合矩阵。
- startActivityForResult、Result Who、Request/Result Code 和 Result Intent。
- Configuration Change、销毁重建、进程死亡和 Broker 重启恢复。
- Running/Recent/AppTask 投影、move/finish/remove 操作和系统 Recents 差异。

### 5. Service、Receiver、Provider

- Started/Bound/Foreground Service 混合生命周期、多客户端计数和 Binder death。
- Sticky/Redeliver、后台启动限制和 Guest 进程死亡恢复。
- Dynamic/Manifest/Ordered Receiver 的真实回调顺序、超时和 PendingResult。
- Provider CRUD、Call、Batch、Cursor、FileDescriptor、Observer、URI Grant 的跨进程行为和死亡清理。

### 6. 系统调度与通知

- PendingIntent mutable/immutable、FillIn、ClipData、sender 权限和跨进程恢复。
- Exact/Repeating Alarm、Doze、重启恢复和 OEM 电量策略。
- Notification Channel/Group、点击/删除/Action、FGS Notification 与权限行为。
- Job 网络/电量/存储/空闲约束、periodic、deadline、expedited、retry/backoff 和进程恢复。

### 7. Native 与四 ABI

- arm64-v8a、x86_64、armeabi-v7a、x86 分别执行文件、Procfs、Loader、Network、Audio 和 Crash Fixture。
- `openat2/statx/renameat2/faccessat2/getdents64/mmap` 的 Android/Bionic 可用性。
- `dlopen/android_dlopen_ext`、Split APK Native Library、Linker Namespace 和 RELRO。
- DNS、Hostname、Interface、VPN/Proxy 和宿主身份泄露检查。
- AAudio、MediaRecorder、AudioRecord/OpenSL ES/AudioSystem 的权限撤销终止。

### 8. WebView 与稳定性

- WebView 主进程、Renderer、GPU、Utility 进程的数据目录和身份隔离。
- 两个虚拟实例并行运行。
- Fixture 全组件链路。
- 20 分钟零 Crash、零 ANR。
- 证据清单、日志、截图和 SHA-256 通过严格 M3 release gate。

## 三、进入设备测试的建议顺序

1. 锁定工具链构建并验证 APK/ABI/签名。
2. 运行 Fixture 基础安装、双实例、包/存储/UID 隔离。
3. 按 Activity → Service → Receiver → Provider → 系统调度 → Native/WebView 顺序验证。
4. 每个失败建立可复现 Fixture 和 API/OEM 记录，不使用 README 声明替代证据。
5. 最后执行完整 20 分钟稳定性门禁和 release evidence 校验。
