# CAS 追赶计划交叉对比与合并执行版

日期：2026-08-21  
对比输入：

1. `CAS_VA_NBB_GAP_AND_CATCH_UP_PLAN_20260821.md`；
2. 用户附件《CAS vs VA PRO / NBB 差距分析与追赶计划》；
3. 当前 CAS、VA、NBB、SX、XH 源码和已有 RD/API32 证据。

本文件是两份计划的交叉裁决和最终执行顺序。详细源码能力分析仍以第一份文档为准。

后续逐任务执行、验收、回执、提交和跨环境续接，以
`docs/plans/CAS_VA_PRO_CATCH_UP_EXECUTION_TASK_BOOK_20260821.md` 和
`docs/plans/CAS_VA_PRO_CATCH_UP_EXECUTION_PROGRESS.md` 为准。

## 1. 总体裁决

附件的战略方向正确：MuMu `RD测试` 优先、先四大组件、再系统服务和 Location/Camera、关注
SX 双重沙箱、补 native、最后 OEM。它提供了更直接的工程命令和业务视角，适合作为执行清单。

但附件的能力矩阵基于较早状态或把不同 XH 工程混在一起，不能原样作为当前 backlog：

- CAS 已有 59 个系统服务 Hook 文件，不是约 15-20 个；
- Telephony、Sensor、Biometric/Fingerprint、Wi-Fi、Location、Camera、GMS boundary 均已有源码；
- Location、Camera1、Camera2、设备、Telephony、Wi-Fi、Cell 已有 MuMu 专项证据，不是“只有 stub”；
- CAS Binder 不再只是 Java 动态代理，已有统一真实 local Binder transaction/session boundary；
- VA PRO README 只说 Binder/AIDL 拦截，不能推断成“Binder ioctl 拦截”；
- CAS 有 isolated deny-only seccomp POC，但没有 Guest syscall virtualization/user-notify；
- 原始 XH `com.xin.h6` 是定制 VA 沙箱产品，不是纯 Xposed 模块；
- `xh/spoofer_project` 才是从 XH 模式整理出的独立 LSPosed/Xposed boilerplate；
- SX 是“BlackBox 沙箱 Host + 内嵌 Pine-Xposed + LSPosed metadata”的混合工程，不是单纯模块；
- Pine 不能在未完成来源/许可证审计时直接认定为 Apache-2.0 可合入；本地 tree 缺少 Pine 根许可证，
  且存在不同许可证声明；
- seccomp user notification 在普通非 root APK 上不是可直接承诺的 5-7 天功能。

最终采用的顺序是：

> **C0 事实源/基线 -> C1 四大组件 -> C2 高频 SystemService/F2-F5 -> C3 native/ABI 与 Hook
> 决策 -> C4 SX 迁移 -> C5 XH 产品验证/可选模块 lane -> C6 API 矩阵 -> C7 OEM。**

该顺序保留附件的快速业务价值，但不允许 SX/XH 业务补丁先于通用能力稳定，也不把 ART/Xposed
错误地变成所有业务的前置条件。

## 2. 能力矩阵逐项交叉裁决

状态含义：`采纳`、`修正后采纳`、`不采纳`、`条件采纳`。

| 附件判断 | 裁决 | 当前源码/证据 | 合并计划处理 |
|---|---|---|---|
| Activity 差距中等 | 采纳 | framework-owned path、route/task ledger 和 RD result/lifecycle 已有；A01 task semantics 仍部分 | C1 首位关闭 launchMode/flags/result/referrer/task restore |
| Service 差距低 | 修正后采纳 | scoped 核心已 L3，但 publish/reentrant/Job unbind/ANR/API drift 未闭环 | 仍列 C1 P0，不降为“低风险完成项” |
| Broadcast 差距低 | 修正后采纳 | manifest/dynamic/ordered/async/cross 路径存在；ordered runner 仍有 marker 缺失 | C1 P0，先修 runtime/harness 分类和压力矩阵 |
| Provider 差距低 | 修正后采纳 | CRUD/batch/cursor/observer/grant/FD 较强；高压、死亡、OEM 未闭环 | C1 P0 scoped closure，不重写架构 |
| PMS 差距中等 | 采纳 | snapshot/queries/revision 已有；split/rollback/clone/reset 设备闭环不足 | C1 package/process 工作包 |
| Binder 仅 Java 动态代理、易检测 | 不采纳该原因 | CAS 有 A+C：semantic proxy + `BinderInterceptionFoundation`；driver mediation 仍 deferred | C2 做 transaction/Parcel/callback/death corpus；不以“删 Proxy 类”作为目标 |
| VA PRO 直接 Binder ioctl 拦截 | 不采纳 | README 只证明 AIDL/Binder 拦截和 binder stub dex，没有 ioctl 证据 | 只作为黑盒行为目标，不写实现推断 |
| CAS 只有 15-20 个系统服务 | 不采纳 | `sandbox-framework/.../service` 有 59 个 Hook 文件；P4 矩阵有 21 个重点域 | C2 从“新增数量”改为“现有方法深度和设备闭环” |
| Native/IO 可被 raw syscall 绕过 | 采纳 | 项目 KI-R03-NATIVE-001/002/005/008 明确记录 | C3 分 trusted compatibility、isolated hostile、research 三 lane |
| procfs 仅基础覆盖 | 修正后采纳 | 已覆盖多项 status/maps/smaps/mount/fd/task，但未知 leaves、`/proc/net` 等仍部分 | C3 补 corpus，不从零实现 |
| Location 只有 stub、为高缺口 | 不采纳现状；采纳业务优先级 | user0/user1 standard API 和 callback 已 PASS；PendingIntent/GNSS/HAL/background 未闭环 | C2 优先补长尾，禁止重做 profile/owner |
| Camera 只有 stub、为高缺口 | 不采纳现状；采纳业务优先级 | Camera1 NV21/JPEG/reopen、Camera2 image/video/preview/capture 已有 MuMu 证据 | C2/C3 补 Surface/vendor metadata/format/HAL/长稳 |
| Xposed 是 XH/SX 致命前置 | 不采纳为统一前置 | 原始 XH 是 VA Host；独立 `spoofer_project` 才是 LSPosed 模块；CAS F2-F5 已不用 Pine 实现 | C3 做产品决策，只有“第三方模块加载”明确入 scope 才建 ART lane |
| GMS 完全没有 | 不采纳 | `GoogleServiceBrokerHook` 和 GMS visibility/identity boundary 已有；真实 GMS runtime deferred | C3 按产品 scope 关闭或明确 NOT_IN_SCOPE |
| Seccomp 完全没有 | 修正后采纳 | 有 isolated deny-only fixture/POC；无 Guest mediation、user-notify、生产 enforcement | C3 继续架构验证，不宣称生产能力 |
| Inline Hook 完全没有 | 基本采纳 | CAS 主 native 路径是 PLT/GOT 与 libc/syscall entry mediation，不是通用 inline engine | C3 先明确需求与安全边界，再选型，不直接引 Dobby/Pine |
| ART Hook 没有 | 采纳事实，条件采纳优先级 | CAS 没有通用 ART/Xposed SDK；但 SX/XH 产品功能已能通过通用 service/native adapter 实现 | Compatibility Extension P1；第三方 Xposed module support 是独立产品 Epic |
| 设备信息只有虚拟 UID | 不采纳 | typed device profile 已含 Build/Android ID/IMEI/MEID/SIM/IMSI/ICCID/operator | C2 补 native/API/OEM 一致性，不重建控制面 |
| Telephony 缺失 | 不采纳 | `TelephonyServiceHook` + profile + user0/user1 fixture PASS | C2 补 callback/API/RIL/permission |
| Sensor 缺失 | 不采纳 | `SensorServiceHook` 和 typed profile 已有 | C2/C3 补 native event/callback/设备证据 |
| Fingerprint 缺失 | 不采纳 | `BiometricServiceHook` 覆盖 biometric/fingerprint replacement | C2 补 callback/TEE/Keyguard/permission，保持 PARTIAL |
| API33-36 全部未验证 | 修正后采纳 | Registry 仍多为 UNVERIFIED；P5 有 API35/36 targeted evidence，但状态漂移 | C0 对账，C6 跑完整统一 suite |
| MuMu 构建基线待首次确认 | 修正后采纳 | RD API32 当前在线，P5 多条证据和 APK 路径已存在 | C0 做当前 HEAD clean rerun，不重复“发现设备”工作 |

## 3. SX/XH 身份和需求的正确拆分

### 3.1 SX 是混合工程

可见事实：

- `settings.gradle` 同时包含 `:engine-bb`、`:Bcore`、`:pine-core`、`:pine-xposed`；
- `BlackBoxSandboxEngine` 直接使用 `BlackBoxCore`；
- engine-bb 的 Location/Camera/Device/Network/Cell/Bluetooth Hook 调用 XposedHelpers；
- App manifest 有 LSPosed/Xposed metadata；
- `assets/xposed_init` 指向 `com.sx.app.xposed.SxModule`，但当前非 build 目录源码中找不到该类；
- BlackBox `BActivityThread` 会加载 PineXposed module；
- DingTalkHook 自己记录多项 Pine trampoline 因 ART memory scan 被禁用，最终路径强调
  “zero Pine ART method hooks”。

所以 SX 的真实风险是：**旧产品同时携带 BlackBox/Pine/LSPosed 遗留面，但目标功能不应继续依赖
这些实现细节。** CAS 接入应迁移 F1-F5 contract，并删除生产 BlackBox engine；不能设计成 CAS 外面
再套一层 SX BlackBox。

附件提出的“双重沙箱冲突”应采纳，但解决方式不是长期兼容双层 Hook，而是：

1. 先做 dependency/runtime inventory；
2. 迁移 UI、数据、profile 和业务 contract；
3. 替换 `SandboxEngine` 为 CAS SDK adapter；
4. 删除/禁用 BlackBox/Pine runtime；
5. 只有缺少通用实现的行为才进入受控 Compatibility Extension。

### 3.2 原始 XH 与 spoofer_project 不是同一个交付物

原始 XH 事实：

- 包名 `com.xin.h6`；
- manifest 有 Splash/Home/VirtualCamera/ListApp 等产品 Activity；
- 有大量 `com.lody.virtual.client.stub.ShadowActivity/Service/Provider` slot；
- 恢复说明和 handover 均将它描述为定制 VA 沙箱，使用 `libvv.so` 和 `libpine.so`；
- 原始资源甚至提示检测到 Xposed 后要求卸载。

`xh/spoofer_project` 事实：

- 是“从 XH 模式清理出的 LSPosed boilerplate”；
- compileOnly Xposed API 82；
- 通过 `assets/xposed_init` 加载 `SpooferModule`；
- 运行前提是 root/LSPosed。

因此：

- 若“支持 XH”指复原原产品功能，目标是 CAS Host + F1-F5，不需要先实现完整 Xposed loader；
- 若“支持 XH”明确指 `spoofer_project` 模块，那么这是独立的 `XPOSED_MODULE_HOSTING` 产品 lane，
  需要 ART engine、module loader、scope、callback 和安全模型，不能混入 C1/C2；
- 两种目标必须使用不同 package、测试套件和完成定义。

## 4. 两份阶段计划映射

| 附件阶段 | 原 R0-R5 | 合并裁决 |
|---|---|---|
| Phase 0 环境基线 | R0 | 合并为 C0；脚本存在且 RD 在线，重点从“首次确认”改为当前 HEAD 重跑与状态对账 |
| Phase 1 四大组件 | R1 | 完整采纳为 C1；保留 Activity/Service/Broadcast/Provider/PendingIntent/package/process 全链 |
| Phase 2 系统服务扩展 | R2 | 合并为 C2；目标不是 30+ 数量，而是现有 59 Hook 的高频方法纵向闭环 |
| Phase 3 ART/Pine/Xposed | R2 的 extension + R3 | 改为 C3 条件 lane；不得默认阻塞 SX/XH Host 产品 |
| Phase 4 Location/Camera | R2 | 前移并入 C2；当前已有实现，工作是补 callback/Surface/HAL/API/长稳 |
| Phase 5 Native Hook | R3 | 合并为 C3，但 seccomp/user-notify 必须按普通 APK/特权边界拆分 |
| Phase 6 SX 与 4/5 并行 | R4 | 不采纳运行时并行；可并行做 UI/数据迁移准备，实际业务验收在 C1-C3 P0 gate 后 |
| Phase 7 XH Xposed | R4 | 拆成 C5：原始 XH Host 产品验证；可选 Xposed module hosting 独立 Epic |
| Phase 8 OEM + API | R5 | 拆成 C6 API/ABI、C7 OEM；先 AOSP/API，再厂商，符合用户优先级 |

## 5. 合并后的指定执行计划

### C0：当前 HEAD 的 MuMu 基线和事实源收敛

采纳附件中的实际命令：

```powershell
.\scripts\build-device-test-apks.ps1
.\scripts\capture-acceptance-evidence.ps1
```

同时执行当前专用 RD suite：

- `tools/device/t57_rd_full_regression.ps1`；
- ActivityResult、FGS、JobWorkItem、Provider、PendingIntent、recovery、isolated、cross-ABI；
- `tools/capability/run_a01_acceptance.py`，先修其 timing/dirty-tree 证据问题。

交付：

1. 当前 HEAD APK hash 和 RD device snapshot；
2. Registry/P5/Roadmap/Known Issues 对账；
3. runtime defect 与 harness defect 分离；
4. VA PRO 713 条 corpus 全量分类计划。

退出门槛：同一 commit 连续两次完整 RD run 结果一致；不再出现报告提升状态但 registry 未更新。

时间口径：附件的 1-2 天只适用于已有脚本的基线重跑，不是修完所有基线失败的承诺。

### C1：四大组件、PendingIntent、包和进程主干

优先顺序：

1. Activity/Application/task/result/referrer/configuration；
2. Service/FGS/Job/bind/publish/sticky/reentrant/death；
3. Broadcast ordered/async/result/abort/permission/background/death；
4. Provider grant/cursor/observer/FD/high-pressure/death；
5. PendingIntent/Alarm/Notification/system holder/mutability；
6. Package split/upgrade/rollback/clone/delete/reinstall；
7. process slot/remote/provider/isolated/Companion32/death recovery。

保留附件的关键场景：`FOREGROUND_SERVICE_LOCATION`、BOOT_COMPLETED、FileProvider grant、大 cursor、
SX ConfigProvider。但 BOOT_COMPLETED 应由虚拟事件模型触发，不能伪造 Host 已真实开机。

退出门槛沿用主计划：50 次冷/热循环、双 virtual user、进程死亡、30 分钟压力、8 小时 soak。
`fixture-basic`/`fixture-lifecycle` 一次 PASS 只是入口门槛，不是阶段完成。

### C2：高频 SystemService 和 F2-F5 深度

不再以“从 15 扩到 30 个服务”为 KPI。采用实际方法矩阵：

P0：

- PMS/Permission/AppOps/AttributionSource；
- Location callback/PendingIntent/provider/GNSS time/background；
- Camera1/Camera2/SurfaceTexture/ImageReader/formats/session/reopen；
- Notification/Alarm/Job/FGS；
- Window/Input/IME/Display 的 Activity 必要面。

P1：

- Telephony/Registry/Subscription/PhoneSubInfo；
- Wi-Fi/WifiScanner/Connectivity/DNS/VPN；
- Settings/User/Shortcut/Storage；
- Audio/Bluetooth/Sensor。

P2：

- Biometric/Fingerprint、DevicePolicy、Autofill、NFC/USB/Print/Companion、其他 privileged service。

每个方法必须有 ownership、identity transform、return/callback、unregister/death、clear/delete/restart。
现有 profile/store 不重写，只补真实 Android surface 和设备证据。

退出门槛：SX/XH F1-F5 实际调用面 100% 有 L3 证据；不接受“空白预览不崩溃”作为 Camera 成功，
必须验证来源帧、尺寸/格式、callback/result 和 release/reopen。

### C3：native/ABI 与 ART/Extension 决策

#### C3-A 必做：trusted native compatibility

- JNI/dlopen/late load/ABI；
- openat/openat2/faccessat2/dfd/xattr/stat/cwd/execve env/FD；
- proc/maps/smaps/fd/task/cgroup；
- Camera native、network/socket/FD ledger；
- 16 KB page 和 Companion32。

#### C3-B 必做：hostile isolation 边界

- isolated UID/process + Broker-only capability；
- inherited FD、`/dev/binder`、socket、ptrace/clone/execve 测试；
- 无法安全虚拟化时明确拒绝，不静默穿透。

#### C3-C 研究：seccomp/user-notify

- deny-only filter 可在 fixture 中继续；
- `SECCOMP_RET_USER_NOTIF` 必须先证明 named kernel、listener ownership、FD transfer 和非特权可行性；
- 普通 APK 不可行时归为 privileged companion/OEM deployment，不阻塞 SX 主线；
- 不承诺通过增加 Dobby/inline hook 就关闭 raw SVC。

#### C3-D 条件：ART/Xposed Compatibility Extension

先回答产品问题：

1. 是否要求 CAS 加载任意第三方 Xposed 模块？
2. 还是只要求 F2-F5/少量已知兼容行为？

若答案是第二种，继续使用 framework/Binder/native 通用适配，不引入 Xposed runtime。

若答案是第一种，单独立项：

- clean-room architecture 和 threat model；
- Pine/SandHook/自研选型；
- 完整 source provenance、许可证和专利审查；
- API/ABI 支持矩阵；
- module classloader、`xposed_init`、callback dispatch、hook/unhook、generation cleanup；
- 目标包 scope、默认关闭、签名/revision、资源/性能限额；
- 不允许模块取得 PackageService/RuntimeBroker root authority。

本地 SX Pine tree 不能直接复制：没有发现 Pine 根 LICENSE，源码还出现 Anti-996、GPLv2-classpath、
Apache 等不同声明。附件的“Apache-2.0、Clean-room 兼容”必须由正式来源审计后才能成立。

### C4：SX 迁移和业务验收

前置：C1、C2 P0、C3-A 的 RD gate 通过。

1. 固化 `SandboxEngine` -> CAS SDK adapter；
2. 迁移 package/user/profile/media/config 数据；
3. 删除生产 BlackBox/Pine/Xposed engine 依赖，防止双 Hook；
4. 验证 F1 后依次启用 F2、F4、F5、F3；
5. 验证 ConfigProvider、FileProvider、shortcut、FGS、notification、Job/WebView、多进程；
6. DingTalk compatibility 默认 OFF、精确 revision、通用缺陷先回到 fixture；
7. 100 次业务循环和 8 小时 soak。

可与 C1-C3 并行的只有 UI adapter、DTO 和迁移工具准备；不得同时用 SX 私有 Hook 修复尚未关闭的
CAS runtime 缺陷。

### C5：XH 两条目标 lane

#### C5-A 原始 XH 产品功能

- 以恢复资料提取 F1-F5、UI、数据和用户迁移要求；
- 使用 CAS Host/SDK/profile；
- 不合入旧 VA Shadow stub、libvv、未知 libpine 实现；
- 与 SX 共用通用 suite，验证 XH 产品差异。

#### C5-B 可选 spoofer/Xposed module hosting

- 仅当用户明确将 `xh/spoofer_project` 作为交付目标时启用；
- 依赖 C3-D 完成；
- root/LSPosed 外部模式与 CAS 内置 module host 模式分开；
- 不以 FackLocService 启动作为 Xposed hook 成功，必须证明 target Guest 方法被模块 callback 拦截。

### C6：Android API/ABI 矩阵

顺序：API33 -> 34 -> 35 -> 36 -> 37；x86/x86_64 后 ARM32/ARM64。所有环境跑同一 C1-C5 suite。

附件把 API 验证放进 OEM Phase，应拆开，因为 API framework drift 和 OEM drift 是两个变量。

### C7：OEM 厂商适配

在 C6 之后开始：HyperOS/MIUI、ColorOS、OriginOS、EMUI/HarmonyOS，再按市场需要补 Flyme 等。

每个 OEM：先通用 suite，再 SX，再 XH；patch 带 manufacturer/API/signature/reproduction，默认不影响
AOSP。该顺序与用户“最后大规模厂商适配”的要求一致。

## 6. 采用、延后和删除的附件工作项

### 直接采用

- MuMu RD 基线脚本；
- 四大组件详细场景；
- FGS Location、ConfigProvider、FileProvider、大 cursor；
- SX 双重 BlackBox/Pine 冲突；
- native raw syscall、procfs、16 KB page、四 ABI 风险；
- OEM 最后；
- 大类拆分技术债务，但不得优先于 runtime gate。

### 修正后采用

- SystemService：从“新增到 30+”改为“59 Hook 的方法闭环”；
- Location/Camera：从“新建代理”改为“补齐未证长尾和长稳”；
- ART Hook：从“XH 致命前置”改为条件产品 lane；
- seccomp：从“实现 user-notify”改为 POC/privilege-gated 研究；
- SX 业务：从与底座并行修复改为 gate 后验收；
- 时间：从固定周承诺改为阶段退出门槛。

### 删除或禁止作为目标

- “VA PRO 直接 Binder ioctl”实现假设；
- 以 Proxy 类可见性作为 Binder 完成标准；
- 重新实现已经存在的 Telephony/Sensor/Fingerprint/Location/Camera 控制面；
- 把原始 XH 当成纯 Xposed 模块；
- 直接复制 SX/NBB/Pine 源码进入 CAS；
- 用“虚拟/空白预览不崩”作为 Camera 完成；
- 用绕过 360/梆梆环境检测作为通用验收。Native 验收应使用受控 adversarial fixture 和授权 App，
  不把规避完整性/安全检测设为核心产品目标。

## 7. 最终优先级

| 优先级 | Epic | 是否阻塞 SX | 是否阻塞原始 XH | 是否阻塞可选 Xposed module |
|---:|---|---:|---:|---:|
| 0 | C0 事实源/当前 HEAD RD baseline | 是 | 是 | 是 |
| 1 | C1 四大组件/PendingIntent/package/process | 是 | 是 | 是 |
| 2 | C2 P0 SystemService + F2-F5 | 是 | 是 | 是 |
| 3 | C3-A trusted native/ABI | 是 | 是 | 是 |
| 4 | C3-B hostile boundary | 视目标 App native trust | 视目标 App native trust | 是 |
| 5 | C4 SX 迁移 | 交付本身 | 否 | 否 |
| 6 | C5-A 原始 XH 产品 | 否 | 交付本身 | 否 |
| 7 | C3-D/C5-B ART/Xposed module hosting | 否，除非明确要求保留模块生态 | 否，除非明确要求 | 交付前置 |
| 8 | C6 API/ABI | 商业发布前 | 商业发布前 | 商业发布前 |
| 9 | C7 OEM | 最后 | 最后 | 最后 |

## 8. 最终结论

附件适合补充主计划的工程可操作性，但不能替代当前源码状态。合并后的关键变化是：

1. 不重复建设已有系统服务和 F2-F5 控制面；
2. 先修组件、回调、生命周期、方法深度和设备证据；
3. 保留 native 为最高风险域，但区分普通 APK 与 privileged deployment；
4. 解除“Xposed 是 SX/XH 统一致命前置”的错误依赖；
5. 通过移除 SX BlackBox/Pine 解决双沙箱，而不是长期兼容双层 Hook；
6. 将原始 XH 产品与独立 spoofer/Xposed 工程拆成两条 lane；
7. 先 RD 通用、再 SX、再 XH、再 API/ABI、最后 OEM。

后续执行应以本文件 C0-C7 顺序为准，以
`CAS_VA_NBB_GAP_AND_CATCH_UP_PLAN_20260821.md` 的证据等级和退出门槛作为状态判定规则。

## 9. 关键核验路径

- `sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/service/`
- `verification/t57-r03-p4-systemservice-coverage-matrix.json`
- `docs/system/T57_R03_BINDER_INTERCEPTION_MATRIX.yaml`
- `docs/native/T57_R03_NATIVE_ENFORCEMENT_OPTIONS.md`
- `docs/review/KNOWN_ISSUES.yaml`
- `docs/dingtalk-xh/XH_SANDBOX_CAPABILITY_MATRIX.md`
- `docs/dingtalk-xh/CAMERA1_NATIVE_PATH_ANALYSIS.md`
- `D:\github\all_project\sx\settings.gradle`
- `D:\github\all_project\sx\engine-bb\src\main`
- `D:\github\all_project\xh\src_restore\README.md`
- `D:\github\all_project\xh\docs\handover_document.md`
- `D:\github\all_project\xh\spoofer_project\README.md`
- `D:\github\t57-reference-sources\VirtualApp\README.md`
