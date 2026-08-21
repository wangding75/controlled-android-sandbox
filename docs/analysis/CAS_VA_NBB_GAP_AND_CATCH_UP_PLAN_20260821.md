# CAS、VirtualApp、NewBlackbox 深度差距分析与追赶计划

日期：2026-08-21
主项目：Controlled Android Sandbox（CAS）
第一稳定基线：MuMu `RD测试`，Android 12 / API 32
业务目标：为 XH、SX 提供完整、可复用的沙箱承载能力
长期对标：VA PRO 的通用兼容能力，而不是旧 VA 公共源码的代码形态

> 执行更新：结合用户补充的《CAS vs VA PRO / NBB 差距分析与追赶计划》完成交叉核验后，
> 最终实施顺序和冲突裁决见
> `docs/analysis/CAS_PLAN_CROSS_COMPARISON_AND_FINAL_EXECUTION_PLAN_20260821.md`。

## 1. 执行结论

CAS 已经不是“缺少一个 VirtualApp 框架”的早期项目。它在包生命周期事务、状态所有权、
Binder generation/death fencing、显式 fail-closed、测试 fixture、证据治理和 32 位 Companion
架构上，比旧 VA 公共源码和 NBB 更系统。核心 Activity、Service、Provider、PendingIntent、
多进程及基础 native 路径也已经在 MuMu `RD测试` 上产生了真实运行证据。

但 CAS **尚未追平 VA PRO**，也不能把当前 P5 报告中的 `PASS WITH DEFERRED` 或
`XH_READINESS: READY` 解读成“完整沙箱能力已就绪”。项目自己的权威注册表仍给出：

- 14 个顶层能力中，13 个 `implementation_status=PARTIAL`，OEM 为 `GAP`；
- 14 个能力全部 `va_pro_equivalent=NOT_PROVEN`；
- SystemService 的 21 个重点域中 20 个为 `PARTIAL`，GMS 为 `DEFERRED`；
- VA PRO 713 条商业更新中，现有语料只选择了 83 条，51 条仍映射为 `GAP`，28 条为
  `NEEDS_TEST`；
- native 仍明确存在 raw syscall/SVC、完整 seccomp、custom loader、Binder device、procfs/FD、
  translated ABI 等架构缺口；
- API 35/36 targeted evidence、P5 报告、Capability Registry、Roadmap 之间存在状态漂移。

因此，当前真实位置应定义为：

> **CAS 已形成比旧 VA/NBB 更可治理的现代沙箱骨架，并在 RD/API32 上关闭了一批核心路径；
> 但组件长尾、系统服务深度、native 强制边界、WebView/GMS、ABI/API/OEM 广度和真实业务闭环
> 尚不足以证明 VA PRO 等价。**

正确追赶策略不是继续横向增加 Hook 类，而是把每一项通用能力按“状态所有权、身份变换、
调用与回调、生命周期清理、故障恢复、设备证据”纵向关闭。优先顺序应为：

1. RD测试 通用能力闭环；
2. RD测试 长稳、故障与跨进程闭环；
3. SX/XH 业务在同一 RD 环境闭环；
4. Android API 广度；
5. 最后进入 MIUI/HyperOS、ColorOS、OriginOS、EMUI/HarmonyOS 等厂商适配。

## 2. 分析边界与基线

### 2.1 仓库快照

| 项目 | 路径 | 本次分析快照 | 性质 |
|---|---|---|---|
| CAS | `D:\github\controlled-android-sandbox` | `75369847b37923fb846d4b40d6b1e17abb2765e2` | 当前产品源码 |
| VA（旧） | `D:\github\t57-reference-sources\VirtualApp` | `802a82c2b9c15a1990e75eee1e9fe07168854772` | 旧公共源码 + 更新到 2026-08-04 的商业日志 README |
| NBB | `D:\github\t57-reference-sources\NewBlackbox` | `89b59836c66f173756a4ae258cf379a957649820` | 现代化 VA/BlackBox 分支参考 |
| SX | `D:\github\all_project\sx` | `c83624f55057c5265664f9330c4cef8014cd96f6` | 当前仍以 BlackBox 为实际 engine 的业务项目 |
| XH | `D:\github\all_project\xh` | 无 Git 元数据 | 反编译/恢复材料和独立 spoofer 工程，不是单一可构建权威源码 |

用户给出的 `D:\github\all\_project\sx|xh` 实际对应
`D:\github\all_project\sx|xh`。

### 2.2 证据等级

本报告不以“类存在”作为完成证据。

| 等级 | 定义 | 可作出的结论 |
|---|---|---|
| L0 | 无实现 | GAP |
| L1 | 有源码入口或 Hook 壳 | 只能说明设计/接线存在 |
| L2 | 主机单测、静态编译或自测通过 | 源码能力成立，不能证明 Android 行为 |
| L3 | MuMu `RD测试` 上真实 Binder/组件/进程路径通过 | RD/API32 范围可用 |
| L4 | 多 Android API、ABI、压力/故障矩阵通过 | 通用 Android 能力基本闭环 |
| L5 | 多 OEM + SX/XH + 命名商业 App 长稳通过 | 才可声明目标范围内 VA PRO 等价 |

当前 CAS 大部分成熟项位于 L2-L3，少数 targeted API35/36 项接近 L4，但尚无 L5。

### 2.3 三个 VA 概念必须分开

- **旧 VA 公共源码**：`compileSdk 26 / targetSdk 22`，代表 2017 年左右的公开实现；可以做架构和
  行为参考，不能代表今天的 VA PRO。
- **VA PRO 商业日志**：README 声称商业代码持续更新并支持 Android 5.0-17.0；本地没有商业源码，
  日志只能作为兼容性需求语料，不能作为实现证据。
- **追平 VA PRO**：只能通过黑盒行为矩阵证明。不能因为 CAS 也有同名 Hook、或某个 fixture PASS，
  就声称与商业实现等价。

## 3. 三套框架的实际调用链

### 3.1 CAS

```text
Sandbox SDK / App
  -> PackageManagementService（包、revision、user、profile 权威）
  -> RuntimeBrokerService（session、generation、slot、route、组件资源权威）
  -> Guest process / Companion32
  -> GuestRuntimeEnvironment（LoadedApk、ClassLoader、Application）
  -> GuestActivityThreadInstrumentation / Service / Receiver / Provider bridge
  -> FrameworkHooks + ServiceManagerBinderHook
  -> BinderInterceptionFoundation（root/returned/callback Binder、death/session fence）
  -> sandbox-native（PLT/GOT、libc/loader/network/procfs/FD policy）
```

核心特点是把包、运行时、系统服务状态、回调租约和清理权威显式拆分。优点是可恢复、可审计、
可测试；代价是现阶段大量 API 采用“识别后显式支持，否则 fail-closed”，所以能力广度尚未自动
等于旧框架多年积累的兼容广度。

关键源码入口：

- `app/.../PackageManagementService.java`
- `sandbox-runtime/.../RuntimeBrokerService.java`
- `sandbox-runtime/.../GuestRuntimeEnvironment.java`
- `sandbox-runtime/.../GuestActivityThreadInstrumentation.java`
- `sandbox-framework/.../FrameworkHooks.java`
- `sandbox-framework/.../ServiceManagerBinderHook.java`
- `sandbox-framework/.../BinderInterceptionFoundation.java`
- `sandbox-native/src/main/cpp/native_hook.cpp`
- `sandbox-native/src/main/cpp/native_interceptors.cpp`

### 3.2 旧 VA

```text
VirtualCore
  -> ServiceManagerNative
  -> VPackageManagerService / VActivityManagerService / ActivityStack
  -> VClientImpl.bindApplication
  -> HCallbackStub + InstrumentationDelegate + ActivityThread mirror
  -> InvocationStubManager 安装 43 个系统服务代理域
  -> NativeEngine / IOUniformer 做路径重定向与 native hook
```

旧 VA 的优势是组件模型和 framework mirror/proxy 体系集中、应用生态使用历史长；弱点是公开源码
停留在旧 Android 时代、中心服务耦合较重、状态与安全边界不如 CAS 显式，公共源码本身也有
PackageInstaller、权限、通知等未完成点。

### 3.3 NBB

```text
BlackBoxCore
  -> BPackageManagerService / BActivityManagerService / BJobManagerService
  -> BActivityThread.bindApplication
  -> HookManager
  -> HCallbackProxy + IActivityManagerProxy + IActivityTaskManagerProxy
  -> 94 个 fake/service 源文件覆盖现代 service 名称与若干类级 Hook
  -> IOCore / NativeCore / BoxCore.cpp（Dobby/xDL 路径）
```

NBB 的优点是对新 Android 名称、WebView/GMS、设备伪装、MIUI 入口和 service proxy 的横向覆盖比
旧 VA 公共源码新；缺点是 `targetSdk 28` 规避了部分现代 target 行为，许多代理主要是参数改写或
固定返回，README/Docs 的“完整支持”没有同等级设备证据。NBB 适合做 API/兼容入口清单，不能直接
作为 CAS 的质量标准或复制来源。

## 4. 源码能力矩阵

“VA PRO 信号”来自 README 商业日志；“旧 VA/NBB”列只评价可见源码。

| 能力域 | CAS 当前状态 | 旧 VA 公共源码 | NBB | 相对落后结论 |
|---|---|---|---|---|
| 包导入、revision、事务 | immutable SHA-256 revision、签名连续性、分阶段提交、回滚保护；split E2E 与 inherit/rollback 仍部分 | 有 VPackageInstaller 与 parser，事务/安全边界较旧 | 有 BPackageManager/installer，工程实用性较强 | CAS 基础设计领先；PackageInstaller 全语义、split/upgrade/rollback 设备闭环落后 VA PRO |
| 多用户/多开/数据隔离 | virtual UID + 独立 instance root + profile authority；RD 有基础证据 | 成熟 VUser/多开模型 | BUser、多开已用在 SX | CAS 设计不落后；高并发 user/slot、clone/update/reset 证据不足 |
| Application/LoadedApk/ClassLoader | framework-owned normal path、Guest defining loader、host boundary；基础 L3 | VClientImpl bindApplication 成熟 | BActivityThread bindApplication 成熟 | CAS 核心可用；split/plugin/custom loader、ComponentFactory 长尾未闭环 |
| Activity 生命周期 | Instrumentation + ActivityThread + virtual task/token ledger；RD result/lifecycle PASS | ActivityStack、HCallback、Instrumentation 历史成熟 | HCallback + AMS/ATMS proxy，现代入口更多 | CAS 仍为 PARTIAL；task mode、top-resumed、referrer、PiP/multi-window、Recents、配置变更及 API 签名矩阵落后 |
| Service 生命周期 | start/bind/unbind/foreground/sticky/redeliver/recovery 有 owner；FGS/JobWorkItem L3 | 商业日志至少两次重写 service；公开源码组件路径成熟 | Service/Job 代理和 stub 完整度较高 | CAS scoped 核心已成；publishService、reentrant start/stop、Job unbind/networkChanged、Application context、ANR/LMK 长尾未证明 |
| Broadcast/Receiver | manifest/dynamic/ordered/async/cross-package 路径存在；runner 仍出现 marker 缺失 | 公开源码有 receiver routing | 有 AMS/receiver 代理 | CAS 为 PARTIAL；ordered result、abort、timeout、protected/system/background、process death 压力矩阵是阻断项 |
| ContentProvider | authority、CRUD、batch、cursor、observer、URI grant、FD lease；scoped L3 | Provider proxy 与 server routing 成熟 | ContentProvider/SystemProvider 代理较广 | CAS scoped 较强；高压 FD、grant 重启、ANR、OEM authority 与 install-time provider 长尾不足 |
| PendingIntent/IntentSender | durable sender、generation、system-holder relay、跨包 delivery，scoped L3 | 多年商业日志反复修 flag/回调/后台启动 | AMS/Alarm/Notification 代理路径 | CAS scoped 可用；SystemUI/Alarm/NMS/ActivityResult、mutability、creator identity、reboot/Doze 仍未闭环 |
| 进程/slot/death | 64 ordinary + 16 isolated，generation recovery，Companion32 路由 | 成熟 process record/client 模型 | BProcessManager/BActivityThread | CAS 架构领先；slot exhaustion、LMK、Broker 重启、isolated UID 资源、translated ABI 未形成完整证据 |
| PMS/权限/AppOps/可见性 | revision-bound snapshot、queries、permission/AppOps policy；均为 PARTIAL | 公开 PMS 面较宽但现代 visibility 不存在 | IPackage/Permission/AppOps/Launcher 入口较新 | CAS 精确所有权强；IntentFilter scoring、shared library、roles/special access/one-time/signature 权限、API variants 落后 |
| Binder 拦截 | Java semantic proxy 下有统一真实 local Binder boundary；returned/callback/death fencing scoped L3 | 旧版以 dynamic proxy/mirror 为主 | 以 method proxy 为主 | CAS 架构方向接近 VA PRO #604；但未达到商业 binder-stub-dex、全 transaction/Parcel/API corpus，`/dev/binder` 无强制边界 |
| SystemService 横向覆盖 | 59 个 hook 文件；21 域状态矩阵中 20 PARTIAL、GMS DEFERRED | 43 个代理目录，公开实现深浅不一 | 94 个 fake/service 文件，现代名称最广 | CAS 类数量不落后，但方法深度、回调、framework object 构造和设备证据显著落后；“有 Hook”不能计完成 |
| Storage/IO 重定向 | Java data roots + native PLT/GOT；部分 cwd/metadata/procfs/FD | IOUniformer 是 VA 核心成熟路径 | IOCore/NativeCore + Dobby | CAS 基础路径可用；xattr、openat2/faccessat2、所有 dfd、memfd、execve 环境、custom loader、raw syscall 不完整 |
| Native hook / syscall / seccomp | libc/PLT 与 `syscall()` 入口部分覆盖；isolated deny-only seccomp；raw SVC 可绕过 | 旧公开源码有 Substrate/HookZz/IOUniformer | Dobby/xDL 且有 anti-detect hooks | CAS 是最大高危差距：无 Guest seccomp mediation/user-notify，raw syscall、custom loader、Binder node、完整 proc/FD 仍开放 |
| Java/Native 通用方法 Hook | framework service semantic hook 完整度较高；没有面向业务的通用 Xposed 等价 SDK | README 明确宣称 Xposed/native 任意方法 Hook；旧源码带 HookZz 等 | 最新 NBB 已移除 Xposed，但 SX 分支仍带 Pine/Xposed | 若“追平 VA PRO”包含任意方法 Hook，CAS 为功能缺口；应做受控、版本化 Compatibility Extension SPI，而不是把 Xposed 直接塞入核心 |
| WebView | provider/profile/data-root/renderer ownership L2；真实 Chromium 深度未闭环 | 商业日志持续修 WebView/OEM | WebViewFactory/WebViewUpdate 代理较多 | CAS 落后：Cookie/DB/service worker/JS bridge/file chooser/renderer crash/GPU/provider update 未形成设备矩阵 |
| Account/GMS | Account 基础 virtual state；GMS visibility/identity fail-closed，真实 GMS deferred | 公开 VAccount 较完整；PRO 多次修 Google login | GoogleAccount/GmsProxy 明确存在 | CAS 明显落后真实 authenticator/session/token、GSF/Play Services broker、登录和多进程回调 |
| 位置/设备/Wi-Fi/Cell/蓝牙/相机 | typed per-instance profile；MuMu 上 F2-F5 多项有 fixture 证据；Camera1/2 有专项证据 | 旧 VA 有 virtual location/device；商业版更广 | NBB/SX spoof 面较广 | CAS 控制面和隔离优于参考；真实 HAL、BLE callback、GNSS/NMEA、Camera vendor metadata/Surface、native sensor 仍部分 |
| Android 版本 | compileSdk 36、target 35、min 26；API32 主线，35/36 targeted evidence 有漂移，37 deferred | 公共源码 compile26；PRO 声称 5.0-17.0 | compile35、target28、min21，README 声称 5.0-15+ | CAS 满足 SX/XH 的 min26，但不满足 VA PRO 字面 5.0-17.0；API33/34/37 和完整 35/36 行为未闭环 |
| ABI | x86/x86_64 RD/AVD、Companion32 source/build path；ARM/OEM 未证 | PRO 声称 ARM/x86、32/64 | README 声称 ARMv7/ARM64/x86；单包位宽仍有限制 | CAS 架构方向正确；四 ABI + translated/native loader 实证不足 |
| OEM | 有 profile/hook 框架，P5 明确禁止并未测试 OEM | PRO 日志积累大量 MIUI/EMUI/OPPO/VIVO/Harmony 修复 | 有 MIUI/Xiaomi proxy，ColorOS known issue | CAS 当前为 GAP，符合“最后厂商适配”的计划，但不能提前宣称商业兼容 |
| 可观测性/验证 | fixture、JSONL、capability registry、known issue、静态审计和 RD 脚本最强 | 旧公共源码测试/证据弱 | NBB 以手工运行和日志为主 | CAS 明显领先；但 registry/report/roadmap 漂移会削弱优势，必须先治理 |

## 5. CAS 的主要领先点

### 5.1 包与运行时状态权威清晰

CAS 把 package revision、virtual user、session、generation、process slot、component resource、
callback Binder 的所有权分开，并在 death/stop/delete/reinstall 时做 fencing。旧 VA/NBB 更偏向
“中央服务 + Map + proxy”的工程模式。对于长期维护、并发多开和错误恢复，CAS 的方向更可靠。

### 5.2 失败闭合优于静默穿透宿主

CAS 对配置为非 HOST 的关键系统服务在 Hook 安装失败时阻断 Guest 启动，避免把宿主包名、UID、
目录或真实设备状态静默泄露给 Guest。很多旧框架为了“能跑”选择固定返回或 host passthrough；
这会把兼容错误变成不一致状态。CAS 应保留这一原则。

### 5.3 Binder 与资源生命周期模型更现代

统一的 `BinderInterceptionFoundation`、returned/callback Binder lease、death/session fence、Provider
Cursor/FD/Observer/URI Grant coordinator，是 CAS 对旧动态代理模式的实质改进。这与 VA PRO #604
从动态代理转向 Binder AIDL 拦截的方向一致。

### 5.4 SX/XH 的 F2-F5 控制面已经通用化

位置、Camera source、设备身份、Wi-Fi、Cell 等数据不是写死到 DingTalk 包名，而是按
package/user/revision 持久化的 profile。DingTalk manager 默认关闭并只做版本门控，业务特化没有
污染通用 Hook 核心。这个边界应继续保持。

## 6. CAS 的关键落后项

### P0：阻断“通用沙箱稳定”的缺口

1. **Activity/Task 语义未闭环**：P5 自己记录 A01 overall false；standard/singleTop/clearTop/
   reorder、result、referrer、top-resumed、任务恢复的 runner 与实际语义仍未统一。
2. **Service/Job 长尾未闭环**：需要覆盖 VA PRO 明确暴露的 publishService、JobService unbind、
   stopService re-entry、getApplicationContext、release keep、Job callback/API drift、ANR。
3. **Broadcast 不稳定**：ordered marker 缺失、脚本可能挂住；不能把一次先出现
   `FRAMEWORK_PROBE_PASS` 当作长期稳定。
4. **Package/split/revision E2E 不完整**：源码能力存在，但现有 KI-R03-026/029 和 registry 仍未
   关闭；upgrade/rollback/clone identity reset 也未证明。
5. **SystemService 只有面，没有深度**：大量 interceptor 对未识别方法显式抛
   `UnsupportedOperationException`；这是安全的，但表示真实 App 一旦调用长尾 API 就会失败。
6. **native 不是强制边界**：raw SVC、custom loader、unknown inherited FD、`/dev/binder`、完整
   seccomp 都可越过当前 PLT/GOT 兼容层。

### P1：阻断“VA PRO 级商业 App 承载”的缺口

1. WebView/Chromium 深行为；
2. Account authenticator 与真实 GMS/GSF/Play 登录；
3. API33/34/35/36/37 的完整 framework signature、Parcel、callback 矩阵；
4. ARM32/ARM64 与 32/64 交叉组合；
5. Kernel/SELinux/LMK/reboot/Doze/后台限制；
6. 可治理的 Java/native Compatibility Extension 与 hook 生命周期；
7. 命名商业 App 的升级、长稳、进程死亡与数据一致性回归。

### P2：最后处理的广度缺口

1. MIUI/HyperOS、ColorOS、OriginOS、EMUI/HarmonyOS 的 framework/HAL/SystemUI 差异；
2. 旧 Android 5-7 支持。如果“VA PRO 5.0-17.0”是硬目标，CAS `minSdk 26` 是明确 GAP；
   对当前 minSdk26 的 SX/XH 业务不应让 legacy lane 阻塞主线；
3. 特定 OEM peripheral、账户、launcher、notification、WebView provider 变体；
4. 仅由单一商业 App/加固触发、且不能抽象为通用契约的兼容补丁。

## 7. VA PRO 713 条更新日志给出的追赶信号

VA README 中 1-713 号更新号完整存在（713 个唯一编号，另有一条重复编号行）。这份日志最重要的
信息不是某个函数名，而是 VA PRO 的能力是多年按真实 App 故障持续修出来的。

### 7.1 组件生命周期不是一次实现完成

- #147、#279：两次重做 Service 机制；
- #449、#541：JobService unbind、publishService crash；
- #480、#514：FGS、Service Application context；
- #524、#525：process/service re-entry deadlock；
- #566、#700、#704、#705：release 混淆、Job ANR、Android 17 service package check、
  IJobCallback。

对 CAS 的含义：Service `CLOSED (scoped)` 只能作为阶段性结论。必须把上述故障模式转为 fixture，
并加上 Binder death、LMK、进程重启、并发 start/stop/bind/unbind 和 50/500 次循环。

### 7.2 Binder 拦截已成为商业版新主轴

- #604：从动态代理转向系统 AIDL/Binder 拦截；
- #619、#629、#631：缺失 injector class、IBatteryStats、transaction 多线程；
- #683、#701：Parcel `appendFrom`、long/int 解析；
- #706：插件进程生成/加载 binder stub dex。

对 CAS 的含义：`BinderInterceptionFoundation` 方向正确，但需要 transaction corpus、Parcel
fuzz、oneway/exception、returned/callback nested Binder、并发和插件/Companion32 一致性，而不是
只统计仍保留多少 Java Proxy。

### 7.3 Native/IO/反检测是最大投入域

- #555、#572、#600-601：seccomp-BPF、32 位、only mode 与重定向；
- #615-617：execve 子进程 inline hook、libc API、native 宿主信息；
- #627、#637、#640：16 KB page、seccomp execve/ptrace；
- #647-650、#657-658、#662、#668：seccomp 调用、openat2/faccessat2、maps、execve FD、
  VisitRoots、only mode、crash；
- #673-677、#686、#693：fstat/proc/fd/task/map_files、trusted syscall re-entry、逐行 maps；
- #708、#710-713：短指令 hook、execve library path、路径/oat/sdcard 系统安装语义。

对 CAS 的含义：当前 PLT/GOT + libc `syscall()` 入口是兼容层，不是完整 hostile boundary。
必须先做架构决策：

- 可信 native Guest：继续使用高性能 in-process compatibility layer；
- 不可信 native Guest：迁移到独立 UID/进程，Host 文件/网络只走 Broker capability；
- 需要 syscall 级虚拟化的场景：研究 seccomp trap/user-notify 或受控 supervisor；
- 不允许用增加更多 PLT Hook 来伪称 raw SVC 已被覆盖。

### 7.4 SystemService、WebView、GMS、OEM 是持续矩阵

- #583、#590、#610、#620、#629/#634、#632、#654、#664/#670、#667、#684、#699、#703：
  UsageStats、AppSearch、NetworkScore、多类 manager、BatteryStats、NotificationProvider、
  AdvancedProtection/Supervision、StatusBar、Contacts、Shortcut、Role、Accessibility；
- #182/#185/#539：WebView 加载、包名、微信 WebView；
- #289/#309/#440-443/#594：GMS/Google login/Play；
- #200/#346/#391/#516/#518/#622：MIUI/EMUI、VIVO、Huawei account/Harmony、OPPO。

对 CAS 的含义：59 个 Hook 文件不等于覆盖上述调用。应为每个服务生成 API-level method
surface 清单，记录参数身份、返回对象、callback、状态 owner、清理条件和设备证据。

### 7.5 不建议照搬的 VA PRO 范围

日志中包含大量“加固/检测/特定 App 打不开”的修复。CAS 的目标应是通用 Android 语义、隔离和
兼容，不应把隐藏恶意行为、绕过账户/完整性/反作弊作为通用核心能力。确有合法业务需求时，
必须进入默认关闭、revision 精确匹配、可审计、可删除的 Compatibility Extension 层。

## 8. SX/XH 的真实依赖与迁移差距

### 8.1 SX 当前事实

外部 SX 工程当前仍然：

- `settings.gradle` 包含 `:blackbox` 与 `:engine-bb`；
- `BlackBoxSandboxEngine` 直接调用 `BlackBoxCore` 安装、创建 user、launch、stop、clear、uninstall；
- `SpoofRuntime` 安装 Location、Device、Network、Cell、Camera、Bluetooth 和 DingTalk Hook；
- `installFromApk` 仍返回 `Not supported in Phase 1` 等阶段性结果；
- DingTalk 仍存在业务包名 Hook。

CAS 内部已有 `SxSandboxAdapter` 和 F1-F5 产品 UI/contract，但这不等于
`D:\github\all_project\sx` 已经切换到 CAS。两者需要做真正的 SDK/工程集成和数据迁移。

### 8.2 XH 当前事实

XH 是恢复/反编译材料，manifest 中保留大量 VA Shadow Activity/Provider process slot（一直到
高编号 slot），并有位置、设备、网络、相机、快捷方式等产品线索。它适合作为业务功能需求和
兼容语料，不适合作为可直接编译合入的实现基线。

### 8.3 SX/XH 所需的通用能力

| 业务能力 | 通用沙箱依赖 | 当前判断 | 在何时接入业务 |
|---|---|---|---|
| F1 导入/多开/清理/删除/升级/快捷方式 | PMS、split/revision、virtual user、Activity/task、storage | 核心有，边角与设备矩阵部分 | RD 通用 P0 全绿后 |
| F2 固定/轨迹位置 | Location callback/PendingIntent、GNSS time、后台、权限 | 固定/回调 L3，PendingIntent/GNSS/OEM 部分 | RD Location 套件 50 次稳定后 |
| F3 图片/视频虚拟相机 | Camera1/2、Surface、ImageReader、native/HAL、媒体 FD | MuMu 专项有证据，vendor/Surface/format 部分 | RD Camera1/2 长稳后 |
| F4 设备/SIM/Telephony | PMS/permission/AppOps、AttributionSource、callback、native identity | profile 基础可用，API/HAL/native 部分 | RD identity consistency gate 后 |
| F5 Wi-Fi/Cell/网络一致性 | Connectivity/Wifi/Telephony/DNS/VPN、callback | control plane 可用，真实 callback/netd/VPN 部分 | RD network suite 后 |
| DingTalk 运行 | 上述所有通用能力 + WebView/多进程/Provider/Job/通知 | 7.8.10 有阶段证据，不等于版本普适 | SX 主流程稳定后，以 revision matrix 接入 |

业务代码必须只消费 CAS SDK/profile，不得重新拥有一套 Hook、进程表、虚拟 UID 或文件根。

## 9. 追赶计划

### 阶段 R0：统一事实源和可重复基线

目标：先解决“到底什么已完成”的治理问题，不改大功能。

工作项：

1. 选 `CAPABILITY_REGISTRY.yaml` 为唯一状态源，报告和 Roadmap 从它生成或校验；
2. 把 VA PRO 1-713 全量解析成机器可读 corpus，按组件/Service/Binder/native/WebView/GMS/
   API/OEM 分类，保留原编号和回归测试映射；
3. 修复 A01 task runner、ordered broadcast runner、dirty-tree identity gate；
4. 固化 MuMu `RD测试` 名称解析，不把 `127.0.0.1:16416` 当永久身份；
5. 在同一 RD 上跑 CAS、NBB、可构建旧 VA fixture 的同场 A/B，关闭 KI-T57-019；
6. 每个 capability 只允许由证据工具更新状态，禁止手写报告提升等级。

退出门槛：

- registry、P5、Roadmap、known issues 无状态冲突；
- 同一 commit + 同一 APK manifest + 同一设备快照可重复运行；
- 失败明确分为 runtime defect、harness defect、environment gap；
- VA PRO corpus 713/713 有分类，重点项有 owner 和 test ID。

### 阶段 R1：RD 上关闭 Android 四大组件与包/进程主干

目标：任何业务 App 接入前，先把通用 Android 生命周期打稳。

工作包按顺序：

1. Activity/Application：launchMode、flags、ActivityResult、newIntent/referrer、configuration、
   top-resumed、task query/remove/move、process death restore；
2. Service/Job：start/stop/bind/unbind/rebind/publish、FGS、sticky/redeliver、Job work、callback、
   process death、ANR/timeout；
3. Broadcast：manifest/dynamic/ordered/async/cross-package、abort/result、timeout、permission、
   background/protected、death cleanup；
4. Provider：CRUD/batch/cursor/observer/URI grant/file/asset FD、跨进程、死亡、分页和压力；
5. PendingIntent/Alarm/Notification：四种 sender、system holder、mutability、creator identity、
   cancel/update、FGS/notification/alarm delivery；
6. Package/process：base+split、upgrade/reinstall/clear/delete/clone、64 slot exhaustion、remote/
   isolated/Companion32、Broker/Guest death recovery。

退出门槛（全部在 RD测试）：

- 每个组件路径 50 次冷启动循环 + 50 次热启动/重用循环；
- 2 个 virtual user、主/remote/provider/isolated 进程同时运行且身份一致；
- kill Guest、kill Broker、force-stop Host、清数据/删除/重装后没有旧 generation 回调；
- 30 分钟压力无 FATAL/ANR/资源持续增长，8 小时 soak 无 session/FD/cursor/receiver 泄漏；
- 所有 P0 fixture 不依赖 App 包名分支；
- 组件能力最多可标记 `RD_CLOSED`，仍不得标记 VA PRO equivalent。

### 阶段 R2：RD 上关闭 SystemService 与 Hook 通用层

目标：从“59 个 Hook 类”升级为“真实 App 调用面有闭环”。

工作项：

1. 从 Android 12 framework AIDL/manager surface 生成 service-method matrix；
2. 优先 PMS/Permission/AppOps、Activity/Window/Input/IME、Notification/Alarm/Job、Storage、
   Location/Connectivity/Wifi/Telephony/Camera/Audio、Clipboard/Account；
3. 每个方法记录：输入身份变换、Host delegate 或 virtual state、返回对象、callback Binder、
   death/unregister、package clear/delete/restart 行为；
4. 将未支持 API 从运行时偶发异常升级为启动前 capability negotiation 或明确 typed error；
5. 完成 Binder Parcel/transaction/concurrency fuzz 和 Companion32 一致性；
6. 设计 `CompatibilityExtension` SPI：版本/ABI/API 条件、安装时机、卸载/回滚、审计、默认关闭，
   严禁扩展直接修改核心 session/package authority。

退出门槛：

- SX/XH 实际调用到的 service 方法 100% 有行为与回调测试；
- P0 service 的 ownership/identity/callback/lifecycle 四列均有 L3 证据；
- 未识别方法不会泄露 Host 身份，也不会以假数据宣称成功；
- Hook 安装失败、重复安装、热更新、Guest death、Host service death 都可恢复或明确失败。

### 阶段 R3：RD 上关闭 native、ABI、WebView/GMS 风险路径

目标：补齐与 VA PRO 最大的能力距离。

分三条并行设计、顺序验收：

1. **可信 native compatibility lane**：完善 open/openat/openat2/faccessat2、dfd、xattr、cwd、
   stat/fstat、link/symlink、execve env/FD、16 KB page、late dlopen、proc/maps/fd；
2. **不可信 native isolation lane**：独立 UID，文件/网络/Provider FD 只通过 capability；验证
   `/dev/binder`、inherited FD、socket、ptrace/clone/execve；
3. **syscall mediation 研究 lane**：seccomp trap/user-notify/supervisor 的可行性、性能与 Android
   限制；明确 raw SVC 不能由 PLT 测试替代。

WebView/GMS 同期按真实 runtime 关闭：

- WebView provider、renderer/GPU/utility、Cookie/DB/cache、service worker、JS bridge、file chooser、
  crash/restart、profile/data isolation；
- Account authenticator/session/token、GMS/GSF package visibility、Play Services broker、登录回调；
- 如果产品不需要完整 GMS，必须把目标写成明确的 `GMS_NOT_IN_SCOPE`，不能长期用“基础 boundary”
  冒充支持。

退出门槛：

- native-heavy fixture 和 SX/XH native 路径 100 次循环；
- 32/64 Guest/Companion 在 RD 可用组合通过；
- raw syscall/custom loader 等无法安全支持的输入按 policy 拒绝，而不是静默穿透；
- WebView 多进程与数据隔离 8 小时稳定；
- GMS scope 有明确产品决策和对应黑盒证据。

### 阶段 R4：在同一 RD 环境接入 SX，再验证 XH 目标

前置条件：R1-R3 的 P0 gate 全绿。此阶段不得用业务 Hook 反向掩盖通用缺陷。

SX 接入顺序：

1. 将外部 SX 的 `SandboxEngine` 实现切到 CAS SDK；
2. 移除生产路径对 `BlackBoxCore`、`:blackbox`、`:engine-bb` 的依赖；
3. 迁移 package/user/profile/media/config 数据，提供一次性、可回滚迁移；
4. F1 导入/多开/启动/停止/清理/删除/快捷方式闭环；
5. F2 位置、F4 设备、F5 网络/Cell、F3 Camera 按顺序接 UI；
6. 最后启用 revision-gated DingTalk compatibility，默认 OFF；
7. 每个业务失败先在通用 fixture 复现，只有无法抽象时才进入 CompatibilityExtension。

XH 目标验证：

- 用 XH 恢复资料做功能验收清单，不直接合入其 VA stub/Hook 实现；
- 确认 F1-F5 UI/产品流与 CAS contract 一一对应；
- 对历史 VA 100-slot、多开、shortcut、位置/设备/相机行为做数据兼容和用户迁移验收；
- License/Activation/VMP/Dex2C 与沙箱通用能力分离，不作为 runtime PASS 条件。

退出门槛：

- SX 在 RD 上完成安装、首次启动、登录/主流程、F1-F5、后台、通知/Job/WebView、多进程；
- DingTalk 至少覆盖指定 revision 的冷/热启动、升级、登录、Camera/Location、后台/回前台；
- 100 次关键业务循环、8 小时业务 soak；
- SX 生产依赖图中没有 BlackBox/NBB runtime；
- XH/SX 特化代码不能被非目标包加载，关闭特化后通用 fixture 结果不变。

### 阶段 R5：Android API 矩阵，然后才做 OEM 适配

先做无厂商变量的 API 矩阵：

1. API33、34、35、36、37；
2. x86/x86_64 自动化，随后 ARM32/ARM64；
3. targetSdk 行为、hidden API、AttributionSource、Parcel/AIDL、FGS/Job/notification、WebView；
4. 每个 API 使用同一 R1-R4 suite，不为单 API 写业务包名分支。

API 矩阵稳定后再进入 OEM：

1. 第一批：产品真实用户占比最高的一个厂商/版本；
2. 第二批：HyperOS/MIUI、ColorOS、OriginOS、EMUI/HarmonyOS 各代表机；
3. 每个 OEM 先跑通用 suite，再跑 SX，再跑 XH/DingTalk；
4. OEM patch 必须包含 manufacturer + API + framework signature + 复现证据，默认不影响 AOSP；
5. 每个厂商升级建立独立 regression lane。

最终 VA PRO 目标范围的退出门槛：

- 宣称支持的 API/ABI/OEM 组合全部达到 L5；
- 713 条 VA PRO corpus 中属于产品 scope 的条目都有测试、`NOT_APPLICABLE` 理由或风险接受；
- 无 `NOT_PROVEN` 的 P0/P1 capability；
- SX/XH 命名商业业务长稳通过；
- 只在此时允许使用“VA PRO 等价（限定版本/ABI/OEM/业务范围）”措辞。

## 10. 推荐优先 backlog

| 顺序 | Epic | 原因 | 首个验收环境 |
|---:|---|---|---|
| 1 | 证据单一事实源 + A01/Broadcast runner | 当前结论互相冲突，会导致错误排期 | RD测试 |
| 2 | Activity/Task/Application 全生命周期 | 所有 App 的最上游承载路径 | RD测试 |
| 3 | Service/Job/FGS 全生命周期 | VA PRO 日志高频、SX/XH 后台核心 | RD测试 |
| 4 | Broadcast/Provider/PendingIntent | 组件间事件、通知、Alarm、跨进程基础 | RD测试 |
| 5 | Package/split/process/death/Companion32 | 多开、升级、恢复和 ABI 的底座 | RD测试 |
| 6 | PMS/Permission/AppOps + 业务高频 SystemService | 避免长尾 API 在真实 App 首次触发时失败 | RD测试 |
| 7 | native/seccomp/procfs/FD/custom loader 架构 | 与 VA PRO 最大能力差距和最高安全风险 | RD测试 |
| 8 | WebView/Account/GMS | DingTalk/企业 App/网页登录常见阻断 | RD测试 |
| 9 | SX 真实工程迁移 + F1-F5 | 通用能力稳定后的业务交付 | RD测试 |
| 10 | XH 功能清单与 DingTalk revision matrix | 第二业务目标，复用 SX/CAS 能力 | RD测试 |
| 11 | API33-37 + ARM | 消除 Android/ABI 变量 | AOSP Emulator/ARM 设备 |
| 12 | OEM 扩展 | 按用户优先级控制适配成本 | 厂商真机 |

## 11. 工程规则

1. 不以 Hook 数量、类数量、README 宣称或一次 marker 作为 PASS。
2. 每项通用能力必须同时有 owner、identity、request、callback/return、cleanup、death/recovery。
3. RD PASS 只更新 `rd_api32_status`；不能顺带更新 API/OEM/VA PRO 状态。
4. 业务故障必须先用 package-neutral fixture 复现；禁止先加 DingTalk/XH/SX 包名分支。
5. CompatibilityExtension 默认关闭、精确 revision、可审计、可回滚，不拥有核心状态。
6. native trusted compatibility 与 hostile isolation 分开，不以性能层替代安全层。
7. OEM 工作在通用 + SX/XH 的 RD 基线稳定之后开始。
8. 旧 Android 5-7 是独立 legacy lane；除非产品明确要求，不阻塞 minSdk26 的 SX/XH 主线。
9. 所有“READY/CLOSED/PASS”必须带作用域，例如 `RD_API32_SERVICE_LIFECYCLE_CLOSED`。
10. 每个阶段结束都生成可机器验证的 APK hash、commit、device snapshot、test matrix 和原始日志。

## 12. 最终判断

- **相对旧 VA 公共源码**：CAS 在架构、安全治理、包事务、状态/资源生命周期和验证体系上领先；
  在旧 VA 多年组件兼容经验、通用方法 Hook 和部分完整服务语义上仍有差距。
- **相对 NBB**：CAS 的状态权威、故障恢复、测试和 fail-closed 更强；NBB 在现代 service 名称、
  WebView/GMS/设备伪装入口的横向覆盖和现成生态上更宽，但证据质量较弱。
- **相对 VA PRO**：CAS 目前只达到“RD/API32 核心承载可用、其余大量部分完成”，尚不等价。
- **对 SX/XH**：CAS 已具备 F1-F5 的良好通用控制面和若干 MuMu 证据，但外部 SX 仍在使用
  BlackBox，XH 仍主要是恢复资料；完整业务支持尚需真实工程迁移和长稳验收。
- **推荐路线**：严格执行 R0 -> R1 -> R2 -> R3 -> R4 -> R5。先把一个 RD 环境的通用能力和
  SX/XH 业务跑稳，再做 API/ABI 广度，最后大规模 OEM 适配。

## 13. 主要证据索引

- `docs/capability/CAPABILITY_REGISTRY.yaml`
- `docs/capability/VA_PRO_COMPATIBILITY_CORPUS.yaml`
- `verification/t57-r03-p4-systemservice-coverage-matrix.json`
- `docs/system/T57_R03_BINDER_INTERCEPTION_MATRIX.yaml`
- `docs/review/KNOWN_ISSUES.yaml`
- `docs/ROADMAP.md`
- `reports/t57-r03/p5-integration-va-pro-parity/T57_R03_P5_INTEGRATION_VA_PRO_PARITY_XH_READINESS_REPORT.md`
- `docs/sx-migration/SX_TO_SANDBOX_MAPPING.md`
- `docs/dingtalk-xh/XH_SANDBOX_CAPABILITY_MATRIX.md`
- `docs/product/XH_SX_PRODUCT_FEATURE_REGISTRY.md`
- `D:\github\t57-reference-sources\VirtualApp\README.md`
- `D:\github\t57-reference-sources\VirtualApp\VirtualApp\lib\src\main`
- `D:\github\t57-reference-sources\NewBlackbox\Bcore\src\main`
- `D:\github\all_project\sx`
- `D:\github\all_project\xh`
