# T57-R02 架构追赶交接记录

> 本文是 T57-R02 深度源码分析、VA/NBB 对比、Framework 执行链优化和 RD 模拟器验收的交接基线。
> 交接时应以当前源码、最新验证证据和本文为准；历史 review 文档中仍保留的旧状态不能覆盖本文的现状判断。

## 1. 交接范围和结论

- 工作目录：`D:\github\controlled-android-sandbox-t57-runtime-review`
- 分支：`feature/t57-runtime-deep-review-observability`
- RD 设备：MuMu 实例名 `RD测试`，API 32 / Android 12，x86。交接当时的会话 serial `127.0.0.1:16416` 只是历史连接数据，不是设备身份；后续每次验证必须重新解析。
- 目标：在 API32 RD 测试模拟器中达到 VA/NBB 商业实现的 Framework 虚拟化成熟度，并以 VA Pro 的执行链、生命周期和稳定性作为追赶目标；API33–36 与 OEM 运行环境按任务约定后续补测。

当前结论是：

1. CAS 的核心 Framework、Binder、PMS、Provider、进程恢复和数据生命周期链路已经完成一轮实质性架构优化。
2. RD API32 的动态基线已经通过：Gradle 四模块编译、静态自测、五类架构门禁、完整 RD 回归 9/9、Quark 300 秒稳定运行。
3. 上述结果只证明“当前 API32/RD 测试范围内的基线通过”，不能直接推出已经达到所有 API、OEM、系统服务隐藏语义或 native hostile-code 场景的 VA Pro 等价水平。
4. Native direct syscall、完整 Android/OEM SystemService 语义、全 Android 版本兼容和更大规模真实压力仍是后续交付项，不能在交接时标记为已完成。

## 2. 本轮已经处理的架构问题

### 2.1 Component Runtime 与 Framework bootstrap

- Activity 正常路径已经向真实 `ActivityThread`/`Instrumentation`/`LoadedApk`/`AppComponentFactory` 执行链靠拢。
- `GuestLoadedApkBridge` 创建并绑定真实平台 `LoadedApk`，注入 `ActivityThread` 的 bound application/package 结构，使用 Guest APK 的 `PathClassLoader`、资源和 `ApplicationInfo`。
- Instrumentation 在 Guest `Application.onCreate()` 前安装，Guest Application、Provider 和组件均在同一个 Guest bootstrap 语义中创建。
- Activity task ledger 覆盖 standard、singleTop、singleTask、singleInstance、singleInstancePerTask、taskAffinity、`NEW_TASK`、`CLEAR_TOP`、`CLEAR_TASK`、`REORDER_TO_FRONT`、`RESET_TASK_IF_NEEDED`、`MULTIPLE_TASK`、`NEW_DOCUMENT`、结果回传、saved state、configuration/process generation 等状态。
- Activity host task detach/rebind 已纳入 launch decision 和 route coordinator；host task 重绑定使用 `NEW_TASK`，真实新虚拟 task 才携带新 task 标志，减少 OEM/API32 上的错误复用。
- Service 正常路径使用 `ActivityThread` service message、真实 Service record/token、`Service.attach()`、`serviceDoneExecuting`、`unbindFinished` 和 framework service bridge；覆盖 start/bind/unbind/stop、foreground、rebind、restart mode、process death/recovery。
- 新增 `RuntimeGuestLifecycleCoordinator`，将 Guest stop、generation、状态机、物理进程终止、Provider/Receiver/Activity/SystemService 清理统一到一个生命周期协调点，避免 Broker、Application layer、Adapter 重复 stop。
- `GuestComponentRuntime` 保留为 isolated 或 framework 无法接管时的受控 fallback，不能把 fallback 当作正常 Component Runtime 的唯一实现。

### 2.2 Application / process / class loading

- `bindApplication`、Guest `LoadedApk`、`ContextImpl`、`ApplicationInfo`、`AppComponentFactory`、Instrumentation、Provider install、Application create/onCreate 已形成有序 bootstrap。
- 多进程通过 Manifest `android:process` 映射到独立 session、slot、generation、Guest Application、ClassLoader、Provider、Service 和 Binder identity。
- 普通进程池生产容量为 64 个 slot，isolated Service 独立池为 16 个 slot；普通与 isolated 不共享 slot 状态。
- isolated process 使用显式 `IsolatedGuestProcessService0..15`，独立 Android isolated UID 路由、token/generation 校验和物理终止等待；停止超时会 abort/unbind/stopService 并汇总失败，而不是留下半活进程。
- `GuestClassLoader` 使用真实 PathClassLoader/InMemoryDexClassLoader，Guest-first 规则、Android/contract parent-first 规则、host internal class 拒绝、Guest resource first、native `findLibrary` 和 shared library APK projection 已统一。
- Native 版本、split、ABI、WebView-style overlay 和 APK revision 均由受信任的 artifact projection 与 revision verifier 约束。

### 2.3 Virtual PMS / Manifest / package visibility

- `ManifestModel` 和 binary manifest parser 已覆盖 Application、Activity、Service、Receiver、Provider、alias、permission/group、shared library、metadata、intent filter、`<queries>` 等主要字段。
- Provider authority、read/write permission、grant URI、path permission、multiprocess、initOrder、exported、process 等字段进入 Guest projection。
- Activity launchMode、documentLaunchMode、taskAffinity、configChanges、orientation、window flags、PiP/resize/noHistory/reparent 等字段进入任务决策。
- Virtual Package Universe 统一处理已安装虚拟包、package/provider/intent visibility、exported、permission、signature、queries 和 cross-package resolve。
- `getComponentEnabledSetting`/`setComponentEnabledSetting` 已加入虚拟包身份边界：自身可读写、可见 foreign package 只读并拒绝写入、隐藏包 fail closed，不能回落到 host PMS。
- PackageManager 的 platform permission/group 合并改为精确 group 语义，避免 guest 看到错误的 host permission group。
- Package import、revision commit、native trust、catalog switch 使用 immutable revision 和事务目录；clear/delete/reinstall 前先停止旧 Guest generation，再切 catalog/data，避免旧代码继续访问新 APK 或新数据。

### 2.4 Binder / PendingIntent / Provider transport

- PendingIntent 的 `IIntentSender.send(int code, ...)` 已按真实 positional contract 解析：第一个整数是 result code，不是 fill-in flags。
- PendingIntent Activity、Service、Broadcast route 均携带 result code；ActivityResult、ordered broadcast 初始 result code 和 callback result 已进入 Guest route。
- Mutable/immutable fill-in 语义已区分；immutable sender 拒绝 fill-in 但仍接受 result code。
- One-shot PendingIntent 在 registry authority 内原子 claim，持久化记录在交付前标记已发送，跨 registry 并发发送不会重复交付。
- PendingIntent registry 使用 generation、creator package/user/process、revision、sender permission 和 durable state 进行发送前校验；旧 generation 的 Binder 不能复活。
- Provider transport 已覆盖 `query` 分页、lazy Cursor page、CursorWindow bounded lease、extras、notification URI、取消/关闭、bulkInsert、applyBatch、call、CRUD、file/asset/typed asset、ContentObserver、URI grant 和跨 ABI relay。
- `ProviderBatchRuntime` 对普通 provider 使用真实 `applyBatch` 入口；不是简单把 batch 拆成 Broker 侧 CRUD。
- Binder death registration 使用 reserve → linkToDeath → recheck → publish 的线性化顺序，应用于 Provider observer、Job execution、SystemService session、ordered Receiver 等 capability。

### 2.5 Runtime recovery 与数据生命周期

- generation fencing 已贯穿 session、Guest Binder、Activity route、Service connection、Provider、PendingIntent、virtual SystemService callback 和 isolated process。
- Guest process death 会统一收敛到：Binder death → generation invalid → stale Binder reject → Provider/Service/Activity detach → slot retire/recycle → cold bindApplication。
- `killProcess`/Runtime exit/Service disconnect 的恢复路径已有 RD 证据；物理 isolated process 停止有 termination barrier。
- clear data、delete instance、reinstall/revision replacement 的 authority 已收敛到 Package lifecycle session；去除了多层重复 stop，避免旧 session 与新 catalog revision 交错。
- Virtual SystemService state 使用 package/user/process/generation 范围，Notification、Job、Alarm、PendingIntent 等持久化记录可在 recovery/clear/delete 中清理或拒绝旧 owner。

### 2.6 Native、IO 和进程身份

- native libc PLT/GOT 入口已覆盖常见 open/openat/openat2、stat/access、rename/unlink/mkdir/rmdir、readlink/getdents、mmap、socket/network、dlopen/android_dlopen_ext、process lifetime 等路径。
- native private path、external path、procfs `/proc/self`、maps/cmdline/status/mountinfo/stat/statm/io、virtual PID/UID 和 Guest path mask 已实现。
- ELF ABI、soname、native root/system soname、fd/relro 和 reserved address 校验已接入 NativeLoader。
- native Guest 默认拒绝，只有 import session 明确记录 `EXPLICITLY_TRUSTED` 才允许启动；`BEST_EFFORT_COMPATIBILITY` 标签会持久化并在 Runtime/Broker/Package Service 重新校验。
- Quark 在 RD API32 上 300 秒 / 30 tick 稳定运行，单一 PID、`processCount=1`、`errors=0`，证明当前 native loader/IO 兼容路径在该测试应用上未出现回归。

## 3. 当前已验证证据

### 3.1 构建与静态门禁

已通过：

```text
python tools/static_android_compile.py
.\gradlew.bat :app:assembleDebug :sandbox-companion32:assembleDebug :fixture-basic:assembleDebug :fixture-compat32:assembleDebug --console=plain
```

Gradle 结果：`BUILD SUCCESSFUL`。

已通过的主要架构门禁：

```text
python scripts/check-activity-task-virtualization.py
python scripts/check-service-lifecycle.py
python scripts/check-m5-t18-architecture-quality.py
python scripts/check-m5-t19-architecture-decoupling.py
python scripts/check-package-lifecycle-transaction.py
git diff --check
```

### 3.2 RD API32

最近一次完整回归证据目录：

`build/t57-rd-evidence/full-regression-pending-intent-transaction-v1-retry`

9/9 通过：

1. ActivityResult / Activity transport
2. Framework transport：Provider、PendingIntent、Receiver、Service、PMS、cross-package
3. JobWorkItem
4. Foreground Service
5. Process death / generation recovery
6. Isolated Service transport
7. clear/delete/reinstall
8. cross-ABI recovery
9. cross-ABI lifecycle

Quark 稳定性证据：

`build/t57-rd-evidence/quark-pending-intent-transaction-v1`

结果为 300 秒监控、30/30 tick、`alive=True`、`processCount=1`、`errors=0`。

### 3.3 五个能力 Gate 的范围判断

| Gate | 当前 API32/RD 基线 | 全面 VA Pro 等价状态 |
|---|---|---|
| Component Runtime | 通过 Activity/Service/Receiver/Provider/Application 真实 fixture 链路 | 仍需 API 变体、OEM task/window、更多异常终止方式 |
| Virtual Android World | 通过 PMS、queries、cross-package、processName、slot 和 cross-ABI marker | 仍需商业 App 可见性/签名/installer/多用户压力 |
| IPC Fidelity | 通过 Binder、PendingIntent、ServiceConnection、Provider batch/cursor/observer | 仍需 SystemUI/Alarm/NMS/跨进程 callback 全链路和取消竞态 |
| Native Fidelity | 当前 RD ABI 的 loader/IO/procfs/JNI 兼容路径通过 | direct syscall/raw SVC 和自定义 loader 仍不是 kernel-enforced boundary |
| Lifecycle Recovery | 通过 death/generation、isolated stop、clear/delete/reinstall | 仍需 SIGSEGV、ANR、LMK、reboot、upgrade/rollback/clone 全矩阵 |

## 4. 正在处理但尚未完成的问题

### 4.1 普通 slot 的验证覆盖

生产容量已经是 `64 ordinary + 16 isolated`，但 `IsolatedProcessArchitectureSelfTest` 仍有 `SessionRegistry(8, ...)` 和容量 8 的旧测试假设。这个问题是验证代码落后于生产 contract，不是生产 slot 数量不足。下一步应：

- 改为使用 `ProcessSlotContract.ORDINARY_SLOT_COUNT`；
- 填充至高位 slot，验证 64 槽耗尽、普通/isolated 独立耗尽和 recycle；
- 增加多进程并发 allocation、disconnect、recovery、slot reuse 的 device/host stress。

### 4.2 Native boundary 的架构决策

当前 `sandbox-native` 是 PLT/GOT 和选定 system module rebinding。源码自测已经明确记录：Guest 自行执行 `syscall(SYS_openat/SYS_connect/SYS_sendto)`、inline assembly/raw SVC 或自定义 loader 可能绕过该层。

不能简单增加 libc symbol 数量来宣称解决，因为 seccomp 直接拒绝 `openat/connect` 同样会破坏 Guest libc 的正常路径。需要单独设计并验证下列方案之一：

- isolated UID/process + Broker-only file/network supervisor；
- seccomp user-notify 或等价 privileged supervisor；
- 明确的 trusted-native admission 与能力分级，把 same-UID Native 只定义为兼容边界而非 hostile-code 安全边界。

在方案落地前，不能把 Native Gate 扩大解释为“对任意恶意 native 完全隔离”。

### 4.3 SystemService 的真实语义验证

现有代码已有 Package-Service-owned typed authority、generation-bound records、Notification/Job/Alarm/Clipboard/Account 等状态和 callback，但以下语义还没有达到“全 Android Framework 级”证据：

- AlarmManager 的 Doze、allow-while-idle、exact/inexact、AlarmClock、重复 alarm、reboot 恢复和取消竞态；
- Notification channel/group、SystemUI 点击 PendingIntent、跨进程 cancel/update、权限变化和 user/profile 维度；
- JobScheduler 的 constraints、deadline/backoff、stop/reschedule、network/idle/charging 状态、JobWorkItem 多批次和 callback death；
- Account、Shortcut、AppWidget、Settings、UsageStats 等隐藏/版本相关 API；
- OEM 改写后的 manager transaction、callback、权限和 token 行为。

### 4.4 Framework fallback 与全路径证明

正常 Activity/Service 已走 framework-owned bootstrap，但 stale/direct trampoline、OEM 兼容和 isolated fallback 仍保留手工 `GuestComponentRuntime`/Stub 路径。需要把每个 fallback 的触发条件、边界和清理行为固化为显式 contract，并在 RD 上证明：

- 正常路径不会意外降级到手工实例化；
- fallback 不会产生双重 lifecycle、重复 onCreate/onDestroy 或旧 token；
- host task、window token、Activity result、Service token 与 Guest generation 一致。

### 4.5 文档和测试基线收敛

`docs/runtime/T57_R02_FRAMEWORK_OWNERSHIP.md`、`docs/ARCHITECTURE.md` 及部分历史 M5 文档仍包含“Activity/Service 完全手工”“PendingIntent/Activity result 尚未接通”等旧描述。它们不是当前源码状态的权威结论，后续要统一为：正常路径 framework-owned，fallback 和未验证版本语义单独列明。

## 5. 已发现但本轮尚未处理的残留问题

以下项目已在源码或对比审计中确认存在，不能标记为完成：

1. API 33–36、Android 13–16 的真实设备/模拟器兼容性尚未执行。
2. OEM framework transaction、隐式权限、厂商 ActivityTaskManager/PackageManager 差异尚未做矩阵测试。
3. direct syscall/raw SVC/custom loader 绕过 native PLT/GOT 的问题尚未获得 kernel-enforced 解决方案。
4. 真实 SystemUI → PendingIntent → Guest Activity/Receiver 的完整链路、Alarm/NMS/Job callback 仍需跨进程设备证据。
5. native `procfs`、linker namespace、`/proc/<pid>`、system properties、uname/mountinfo 等 anti-sandbox 观察面尚未达到所有商业 App 的兼容覆盖。
6. Provider 的超大 CursorWindow、远程 cursor 长时间 lease、文件 descriptor/typed asset、persistable URI grant 和取消/ANR 压力仍需扩展。
7. ClassLoader 的多 split、插件同名库、不同版本 Java dependency、relocation/linker namespace 冲突仍需更多真实 APK。
8. process death 仍需补 SIGSEGV/native abort、ANR、LMK、reboot 后 restore、callback late arrival、Binder death 与 stop/reinstall 并发矩阵。
9. clear/delete/reinstall 之后的 upgrade、rollback、clone、reset identity、跨用户清理、正在进行的 Provider/Service/Job/Alarm 事务仍需验证。
10. 真实 VA/NBB/VA Pro 商业版同设备 A/B 行为数据还没有形成可重复的自动化对比报告；现有参考主要来自源码、README 迭代记录和 CAS 自己的证据，不能将对方宣传能力直接当作 CAS 证据。

## 6. 后续追赶计划和交付门槛

### P0：先消除验证盲区和明确 Native 边界

- 修正 8 槽旧测试，增加普通高槽位、isolated 饱和、并发 allocation/recycle/recovery 测试。
- 将 Activity launch mode、task reuse、host task rebind、ActivityResult、Service token 和 processName 组合成独立 device probe，不只依赖一个综合 transport probe。
- 建立 PendingIntent 真实跨进程矩阵：mutable/immutable、one-shot、cancel/update, activity/service/broadcast、result code、sender permission、SystemUI/Alarm/NMS callback。
- 产出 Native boundary design：明确 same-UID trusted compatibility 与 isolated hostile-code execution 的边界、错误码、admission、观测和恢复；没有 kernel/supervisor 方案之前不修改指标冒充完成。

### P1：补齐跨进程 Framework 语义

- SystemService 按 Alarm、Notification、Job、Account、Clipboard、Settings、UsageStats 分域建立 state machine、owner、callback、death、recovery 和 clear/delete contract。
- Provider 增加大 Cursor、FD/asset、URI grant、observer、cancel、timeout、跨 ABI 和 provider death 压力。
- ClassLoader/NativeLoader 增加 split/plugin/duplicate dependency/ABI/linker namespace 真实 APK 测试。
- 将每个 framework fallback 的进入条件、退出条件和 generation fencing 纳入静态门禁与 RD trace。

### P2：生命周期完整性和商业 App 稳定性

- 执行 killProcess、System.exit、native abort/SIGSEGV、ANR、LMK、Binder death、reboot、force-stop、clear、delete、reinstall、upgrade、rollback、clone、reset identity 全矩阵。
- 在 RD 上对 Quark 等复杂应用执行至少 5 分钟稳定性作为最低线，并增加多进程、后台调度、WebView/JNI、文件/网络和跨包交互场景；5 分钟通过不等于最终完成。
- 形成每个 Gate 的 pass/fail/blocked 证据，而不是只报告一个兼容百分比。

### P3：API/OEM 兼容矩阵

- 先补 API33、34、35、36 的 ActivityThread、IIntentSender、PackageManager、Provider、Job/Alarm/Notification transaction 差异。
- 再补目标 OEM 的权限、窗口、后台启动、Binder callback、native linker/procfs 差异。
- API/OEM 当前受测试环境限制，不能把未执行标记为通过；环境具备后优先补动态证据和回归脚本。

## 7. 接手者操作顺序

1. 先运行 `python tools/static_android_compile.py` 和五个架构门禁，确认本地源码没有破坏基线。
2. 查看 `build/t57-rd-evidence/full-regression-pending-intent-transaction-v1-retry` 和 `build/t57-rd-evidence/quark-pending-intent-transaction-v1`，确认当前 API32 RD 证据。
3. 先修正普通 slot 测试的 8 槽假设，再新增高槽位和并发压力；不要降低生产 64/16 contract。
4. 按 Native boundary design 选择 isolated supervisor/seccomp user-notify 或 trusted-native 分级方案；不要用更多 PLT Hook 代替内核边界。
5. 再推进 SystemService/Provider/ClassLoader 的真实跨进程压力，最后进入 API33–36/OEM 矩阵。
6. 每个修复必须同时更新源码 contract、自测、RD evidence 和本交接文档状态；未有设备证据的能力保持 `未验证` 或 `受环境限制`。

## 8. 交接时的禁止性结论

- 不得把 RD API32 9/9 或 Quark 5 分钟稳定性写成所有 Android/OEM 的 VA Pro 等价证明。
- 不得把源码有 Hook、Java self-test 通过写成 native direct syscall 已被隔离。
- 不得把历史比较文档中的 VA/NBB 功能描述写成 CAS 已通过的动态证据。
- 不得为通过静态门禁而删除 fallback、放宽 generation 校验、回落 host PMS 或允许旧 Binder 继续工作。
- 不得在未完成 P0/P1 残留项前擅自宣布五个 Gate 达到完整 VA Pro 水平。
