# CAS 追平 VA PRO 执行任务书

版本：1.0
制定日期：2026-08-21
基准分支：`feature/t57-r03-va-pro-capability-campaign`
首要验收环境：MuMu 模拟器实例 `RD测试`
任务进度账本：`docs/plans/CAS_VA_PRO_CATCH_UP_EXECUTION_PROGRESS.md`

## 1. 任务书目标

本任务书用于把 CAS 追平 VA PRO 的目标转化为可以逐项执行、验收、提交、推送和跨环境续接的工程任务。
最终目标是：

1. 先在 MuMu `RD测试` 上关闭 Android 沙箱通用能力，包括组件、生命周期、事件、Hook、系统服务、
   native/ABI、故障恢复与隔离边界；
2. 通用能力稳定后，以 CAS 作为唯一沙箱宿主完成 SX 业务迁移和验收；
3. 复用同一底座支持原始 XH 产品能力，并把可选 Xposed 模块宿主作为独立条件路线；
4. 最后扩展 Android API/ABI 和厂商适配；
5. 对产品范围内的 VA PRO 更新日志能力逐条建立实现、测试、证据或不适用说明。

本任务书的事实依据为：

- `docs/analysis/CAS_VA_NBB_GAP_AND_CATCH_UP_PLAN_20260821.md`；
- `docs/analysis/CAS_PLAN_CROSS_COMPARISON_AND_FINAL_EXECUTION_PLAN_20260821.md`；
- `docs/capability/CAPABILITY_REGISTRY.yaml`；
- `docs/capability/VA_PRO_COMPATIBILITY_CORPUS.yaml`；
- `docs/review/KNOWN_ISSUES.yaml`；
- CAS、VA（旧）、NBB、SX、XH 当前源码及已有设备证据。

## 2. 强制执行协议

### 2.1 每次开始任务前

执行者必须先完成以下动作，任何一项未完成都不得修改生产代码：

1. 完整读取本任务书和 `CAS_VA_PRO_CATCH_UP_EXECUTION_PROGRESS.md`；
2. 读取 `docs/capability/CAPABILITY_CAMPAIGN_WORKFLOW.md`、`docs/COMMIT_IDENTITY_POLICY.md`；
3. 执行 `git status --short`、`git branch --show-current`、`git log -5 --oneline`、`git remote -v`；
4. 执行 `git fetch origin`，确认本地分支与远端关系；不得 rebase、amend、force-push 或重写历史；
5. 核对进度账本中最后一个 `DONE`/`NOT_APPLICABLE` 回执、实现提交、证据路径和远端分支；
6. 若工作区存在非本任务改动，必须保留并区分归属；无法安全隔离时将当前任务标记为 `BLOCKED`；
7. 从进度表选择依赖已满足的第一个 `PENDING` 任务，将其改为 `IN_PROGRESS`；同一时间只能有一个
   `IN_PROGRESS`；
8. 重新解析 MuMu 实例名 `RD测试`，禁止硬编码 ADB 端口；涉及设备的任务必须记录设备快照；
9. 先执行 DISCOVER 和 CLASSIFY，再进入 DESIGN 与 IMPLEMENT，不允许发现一个问题就立即打补丁。

### 2.2 每个任务完成后

每个任务必须使用以下两提交协议：

1. 完成代码、测试和证据，运行任务验收命令；
2. 提交实现与证据，提交主题格式：`<type>(<scope>): [任务ID] <摘要>`；
3. 取得实现提交 SHA；
4. 在进度账本追加完整任务回执，将状态从 `IN_PROGRESS` 改为 `DONE` 或 `NOT_APPLICABLE`；
5. 单独提交进度回执，提交主题格式：`docs(progress): record [任务ID] receipt`；
6. 将两个提交推送到当前远端任务分支，禁止强推；
7. 使用 `git ls-remote --heads origin <branch>` 验证远端 HEAD 等于本地 HEAD；
8. 只有实现提交、回执提交和远端验证全部成功，任务才算完成；推送失败时状态必须是 `BLOCKED`，
   不得继续下一任务。

任务不得用无关改动凑提交。若任务只产生调研/决策结果，设计文档、矩阵和证据就是实现提交内容。

### 2.3 状态与门禁

允许状态只有：

- `PENDING`：尚未开始；
- `IN_PROGRESS`：正在执行，最多一个；
- `BLOCKED`：存在明确阻断，回执必须写恢复条件；
- `DONE`：任务验收全部通过，两个提交均已推送；
- `NOT_APPLICABLE`：条件任务经正式决策排除，决策、影响分析和替代方案已提交推送。

阶段只有在所有非条件任务为 `DONE`、条件任务为 `DONE` 或 `NOT_APPLICABLE`、阶段门禁通过后才能关闭。
一次设备 PASS 只能更新相应设备/API/ABI 维度，不能推导其他环境通过。

### 2.4 标准任务回执

每个任务回执至少包含：

```text
任务 ID / 名称：
最终状态：DONE | NOT_APPLICABLE | BLOCKED
开始/结束时间（Asia/Shanghai）：
执行环境：OS、JDK、Android SDK/NDK、Gradle、设备名/API/ABI
开始基线：分支、commit、工作区状态、上一任务回执
实现摘要：
变更文件：
验收命令与结果：
设备证据：日志、截图、报告、APK SHA-256、设备快照
发现问题：新增/关闭的 KNOWN_ISSUES ID
偏离任务书：原因、批准依据、影响
实现提交 SHA：
回执提交：通过提交主题在 Git 历史定位
推送目标与远端验证结果：
遗留风险：
下一任务：
```

## 3. 全局工程规则

1. 不以 Hook 数量、类存在、README 声明、单次 marker 或“不崩溃”作为完成证据。
2. 每项能力必须覆盖 owner、identity、request、return/callback、unregister/cleanup、death/recovery。
3. 业务问题先用 package-neutral fixture 复现；禁止先添加 SX、XH、DingTalk 包名特判。
4. VA/NBB 只作为行为和实现思路参考；代码引入必须符合 clean-room、来源与许可证规则。
5. SX 迁移后 CAS 必须是唯一沙箱宿主，生产路径不得继续嵌套 BlackBox/Pine。
6. Compatibility Extension 默认关闭、精确匹配版本、可审计、可回滚，不拥有核心状态。
7. 普通 APK 的 trusted compatibility 与 hostile isolation 分开验收，不用 PLT Hook 冒充安全边界。
8. 所有测试报告必须记录 commit、APK hash、设备、API、ABI、profile/revision 和原始日志位置。
9. 修复测试工具不能把 runtime FAIL 改名为 PASS；harness defect 和 runtime defect 必须分开登记。
10. 时间预估不替代退出门禁；未满足验收标准不得因为排期到期而关闭任务。

## 4. 阶段 C0：事实源、续接机制与 RD 当前基线

### 4.1 阶段目标

建立可跨会话、跨机器复现的当前 HEAD 基线，消除 Registry、Roadmap、Known Issues、测试报告之间的状态漂移，
确保后续任务从同一事实源继续。

### 4.2 任务列表

`C0-T01` 续接与证据协议；`C0-T02` 可复现构建；`C0-T03` RD 完整基线；`C0-T04` 事实源对账。

### C0-T01：固化任务续接与证据协议

- **任务目标**：验证任务书、进度账本、Git/设备前检和两提交回执流程在新环境可执行。
- **执行方案**：按第 2 节从零走一遍只读前检；检查脚本入口、仓库身份配置、远端分支、设备动态解析；
  为缺失项补充脚本或说明，并对续接流程增加静态校验。
- **验收标准**：在第二个 PowerShell 会话中，仅依靠仓库文件能正确定位下一任务、最后回执、基准提交、
  `RD测试` 和证据目录；仓库级 Git 身份符合策略；无硬编码 ADB 端口。
- **任务回执**：记录两次会话前检输出、远端同步状态、设备解析结果、发现并修复的续接缺口。

### C0-T02：建立当前 HEAD 可复现构建基线

- **任务目标**：确认当前任务分支能够在干净依赖条件下生成设备测试 APK，并固化产物身份。
- **执行方案**：运行构建环境检查、锁定依赖检查和 `scripts/build-device-test-apks.ps1`；记录 JDK/SDK/NDK/
  Gradle 版本、输入 commit、APK 路径和 SHA-256；失败先分类为环境、供应链或源码问题。
- **验收标准**：同一 commit 连续两次构建成功；关键 APK 哈希一致，或对签名/时间戳导致的差异有可验证解释；
  供应链及提交身份检查通过。
- **任务回执**：记录完整命令、工具链版本、两轮产物 hash、耗时、缓存条件和失败分类。

### C0-T03：执行 MuMu RD 完整基线

- **任务目标**：得到当前 HEAD 在 `RD测试` 上可信且可重复的组件、进程、系统服务和 native 基线。
- **执行方案**：运行 `tools/device/t57_rd_full_regression.ps1` 以及 ActivityResult、FGS、JobWorkItem、Provider、
  PendingIntent、recovery、isolated、cross-ABI 专项；再运行 `scripts/capture-acceptance-evidence.ps1`。
- **验收标准**：同一 commit、同一设备快照连续两轮结果分类一致；每个 FAIL 明确属于 runtime defect、harness
  defect、environment block 或 expected limitation；原始日志可回溯。
- **任务回执**：记录设备快照、APK hash、两轮矩阵、差异、日志目录、失败签名和 Known Issue 映射。

### C0-T04：统一能力事实源与 VA PRO corpus

- **任务目标**：消除能力 Registry、VA PRO corpus、Known Issues、Roadmap 和报告之间互相矛盾的状态。
- **执行方案**：逐项核对当前源码和 C0-T03 证据；仅更新有作用域证据的维度；将 VA PRO 更新日志条目分类为
  IN_SCOPE、OUT_OF_SCOPE、DUPLICATE、NEEDS_FIXTURE、PROVEN；修复 A01/Broadcast 等 runner 的分类缺陷。
- **验收标准**：P0/P1 条目不存在无证据 `PASS`；相同 capability 在各账本状态一致；所有差异有原因和证据链接；
  本地 capability audit 结果稳定。
- **任务回执**：记录状态变更清单、corpus 分类统计、修复的 harness 问题、尚未证明的 P0/P1 清单。

### 4.3 阶段门禁

同一 commit 在 `RD测试` 连续两次完整运行分类一致；事实源无冲突；下一环境可按进度账本无损续接。

## 5. 阶段 C1：四大组件、PendingIntent、包与进程主干

### 5.1 阶段目标

关闭所有 App 共用的 Android 组件承载路径和生命周期语义，形成 SX/XH 接入前不可绕过的通用底座。

### 5.2 任务列表

`C1-T01` Activity/Application；`C1-T02` Service/FGS/Job；`C1-T03` Broadcast；`C1-T04` Provider；
`C1-T05` PendingIntent；`C1-T06` Package；`C1-T07` Process/ABI/Recovery。

### C1-T01：Activity、Application 与任务栈语义闭环

- **任务目标**：关闭启动模式、flags、result/referrer、configuration、重建和 task restore 的通用语义。
- **执行方案**：建立 standard/singleTop/singleTask/singleInstance、newTask/clearTop/reorder、跨用户与死亡恢复矩阵；
  修复 framework-owned route/task ledger，保持真实 Android lifecycle 顺序。
- **验收标准**：双虚拟用户冷/热启动各 50 轮；result/referrer、back stack、rotation/config change、进程死亡恢复
  与基准 App 一致；无跨用户状态泄漏。
- **任务回执**：记录矩阵结果、关键生命周期 trace、修复路径、50 轮统计和未覆盖系统行为。

### C1-T02：Service、FGS、Job 全生命周期闭环

- **任务目标**：稳定 started/bound/foreground/job 服务的发布、重入、解绑、停止、死亡与恢复。
- **执行方案**：覆盖 start/bind/publish/unbind/rebind/sticky、startForeground 时限、FGS type、JobWorkItem、
  cancel/reschedule、宿主及 Guest 死亡；加入 `FOREGROUND_SERVICE_LOCATION` fixture。
- **验收标准**：生命周期顺序和 callback 正确；重复 bind/unbind 无泄漏；死亡后按策略恢复或 fail-closed；
  30 分钟压力和 8 小时 soak 无未界定 ANR、泄漏或幽灵任务。
- **任务回执**：记录 service/job/FGS trace、压力数据、ANR/leak 指标、通知关联和恢复结果。

### C1-T03：Broadcast 事件模型闭环

- **任务目标**：支持 manifest/dynamic、explicit/implicit、ordered/async、permission、background 和死亡路径。
- **执行方案**：构建发送、匹配、排序、result/abort、goAsync、超时、反注册、跨用户和进程死亡 corpus；
  BOOT_COMPLETED 由虚拟事件状态触发，不伪造 Host 开机事实。
- **验收标准**：ordered result/abort 顺序确定；async token 正确回收；权限与用户隔离有效；压力运行无 receiver 泄漏；
  runtime FAIL 不被 harness marker 掩盖。
- **任务回执**：记录事件矩阵、顺序 trace、权限拒绝、超时/死亡案例、runner 修复和泄漏统计。

### C1-T04：ContentProvider 数据与授权生命周期闭环

- **任务目标**：关闭 Provider CRUD、batch、cursor、observer、URI grant、FD 和死亡恢复路径。
- **执行方案**：覆盖 acquire/release、unstable/dead provider、applyBatch、分页/大 cursor、notifyChange、
  FileProvider grant/revoke、ParcelFileDescriptor 和跨用户 authority；加入 SX ConfigProvider fixture。
- **验收标准**：大 cursor 与 FD 无截断/泄漏；grant 权限和回收正确；provider 死亡后可重连或明确失败；
  30 分钟并发压力无跨用户数据泄漏。
- **任务回执**：记录 authority/operation 矩阵、cursor/FD 统计、grant ledger、死亡恢复和 ConfigProvider 结果。

### C1-T05：PendingIntent、Alarm、Notification 系统持有者闭环

- **任务目标**：保证身份在跨进程、延迟触发、Host 重启和系统持有期间不漂移。
- **执行方案**：覆盖 activity/service/broadcast PendingIntent、mutable/immutable、update/cancel、Alarm 与 Notification
  点击、删除、重启恢复；校验 creator identity、virtual user、revision binding 和 stale route 清理。
- **验收标准**：系统回调回到正确 Guest/user/revision；旧 revision token 被拒绝；cancel/clear/delete 后无残留；
  Alarm/Notification 重启前后行为可预测。
- **任务回执**：记录 token ledger、identity trace、重启测试、stale/replay 拒绝和系统 UI 触发证据。

### C1-T06：包安装、升级、拆分、回滚和克隆闭环

- **任务目标**：建立事务化 package 生命周期和稳定的虚拟 PMS 查询语义。
- **执行方案**：覆盖 base/split 安装、升级/降级策略、失败回滚、clone user、clear/delete/reinstall、签名与 revision；
  验证 query/resolve/permission/AppOps snapshot 与运行 revision 一致。
- **验收标准**：事务失败不产生半安装状态；split 集合原子切换；clear/delete 无陈旧权限、URI、PI、进程或文件；
  双用户安装互不污染。
- **任务回执**：记录每种 package transition、前后 snapshot、故障注入、回滚证据和残留扫描。

### C1-T07：进程槽位、跨 ABI 与故障恢复闭环

- **任务目标**：稳定主进程、remote/provider/isolated、Companion32 和死亡重连。
- **执行方案**：建立 process slot 分配/回收、binder death、broker reconnect、32/64 位路由、并发启动与崩溃风暴
  fixture；验证 user/package/revision 绑定不可混用。
- **验收标准**：进程死亡不污染其他 Guest；槽位最终回收；Companion32 身份和产物匹配；50 次 kill/restart 与
  并发压力无死锁、错误复用或无限重启。
- **任务回执**：记录进程拓扑、slot ledger、死亡/重连 trace、ABI 路由、压力统计和阶段门禁结果。

### 5.3 阶段门禁

C1 全部 fixture 在 `RD测试` 双用户通过；50 次关键循环、30 分钟压力和 8 小时 soak 达标；所有资源在
clear/delete/restart/death 后可证明收敛。

## 6. 阶段 C2：高频系统服务与 F2-F5 深度

### 6.1 阶段目标

不追求 Hook 类数量，而是把 SX/XH 所需系统服务的真实方法、回调、状态、死亡和权限路径提升为 L3 设备证据。

### 6.2 任务列表

`C2-T01` 方法清单；`C2-T02` PMS/Permission/AppOps；`C2-T03` Location；`C2-T04` Camera；
`C2-T05` 调度与交互服务；`C2-T06` 设备/网络/媒体；`C2-T07` 长尾与阶段收敛。

### C2-T01：建立 SX/XH 实际系统服务方法清单

- **任务目标**：把现有 59 个 Hook 文件转换为按业务调用、Android 版本和证据深度管理的方法级 backlog。
- **执行方案**：结合静态调用图、受控 runtime trace、VA PRO corpus 和现有矩阵，记录 service/method/signature、
  request/return/callback、调用业务、现有 owner 和证据缺口；禁止以类存在判定覆盖。
- **验收标准**：SX/XH F1-F5 实际调用面 100% 被分类；P0/P1 方法都有 owner、测试计划和目标证据等级；
  未调用长尾与产品 scope 明确分开。
- **任务回执**：记录方法数量、P0/P1/P2 分布、动态/静态来源、未知调用和后续任务映射。

### C2-T02：PMS、Permission、AppOps 与 AttributionSource

- **任务目标**：统一包可见性、权限、AppOps、调用归因和 callback identity。
- **执行方案**：验证 query/resolve、runtime permission、AppOps note/start/finish、AttributionSource chain、跨用户与
  revision；修复 framework/Binder/system-service 入口的 identity 变换一致性。
- **验收标准**：允许/拒绝结果与虚拟状态一致；Host 权限不泄漏给 Guest；callback 保留虚拟身份；升级、clear、
  delete 后权限与 AppOps 状态正确收敛。
- **任务回执**：记录方法矩阵、身份 trace、权限/AppOps transition、负面测试和跨用户隔离结果。

### C2-T03：Location 通用能力闭环

- **任务目标**：在已有标准 API 基础上关闭 callback、PendingIntent、provider、GNSS time 和后台语义。
- **执行方案**：覆盖 last/current/update、listener/PendingIntent 注册注销、provider 状态、GNSS/NMEA/time、
  foreground/background、权限变化、进程死亡和双用户 profile 更新。
- **验收标准**：来源、坐标、时间、精度、provider 和 callback 次序可验证；注销/clear/death 后零回调；
  user0/user1 不串值；30 分钟更新与 8 小时低频 soak 稳定。
- **任务回执**：记录 API/回调矩阵、位置轨迹、时间误差、后台策略、资源回收和已知 HAL/OEM 边界。

### C2-T04：Camera1/Camera2 通用能力闭环

- **任务目标**：关闭 SurfaceTexture、ImageReader、预览/拍照/录像、格式、session 和 reopen/release 长尾。
- **执行方案**：扩展 package-neutral camera fixture，覆盖 NV21/JPEG/YUV、尺寸/方向、Surface、capture result、
  并发/抢占、权限撤销、进程死亡；区分 framework、native 和 HAL/OEM 边界。
- **验收标准**：不能只验证空白预览不崩；必须证明来源帧、尺寸、格式、时间戳和 callback/result；连续打开关闭
  100 次、30 分钟预览及 8 小时业务 soak 无句柄/Surface 泄漏。
- **任务回执**：记录帧 hash/来源、格式矩阵、callback trace、reopen 统计、资源计数和未覆盖 vendor metadata。

### C2-T05：Notification、Alarm、Job、FGS、Window/Input/IME/Display

- **任务目标**：关闭业务运行必需的调度、通知、窗口、输入法与显示服务方法。
- **执行方案**：以 C2-T01 P0 方法为准逐项补 request/return/callback/death；复用 C1 token/identity ledger；
  覆盖 channel、notification click、exact alarm、job constraints、FGS type、window token、IME 和 display context。
- **验收标准**：目标方法 100% 有设备证据；系统持有 token 不漂移；窗口/输入/显示不跨 Guest；后台限制行为
  与当前 API32 基准一致。
- **任务回执**：记录方法级结果、系统 UI 证据、token/identity trace、调度时延和限制条件。

### C2-T06：Telephony、Wi-Fi、Connectivity、Audio、Bluetooth、Sensor

- **任务目标**：完成设备、网络、通信、媒体和传感器的返回值与 callback 一致性。
- **执行方案**：按 profile owner 建立 typed value 与 callback corpus；覆盖 subscription/registry、scan/network callback/
  DNS/VPN、audio route/focus、Bluetooth state/device、sensor registration/event/unregister 和权限变化。
- **验收标准**：同步 getter 与异步 callback 不矛盾；双用户不串值；注销/死亡后资源回收；Host 真值不越权泄露；
  SX/XH 实际调用全部达到 L3。
- **任务回执**：记录各域方法矩阵、profile 快照、callback trace、权限负面测试和资源统计。

### C2-T07：Biometric 及其他长尾服务收敛

- **任务目标**：处理 Biometric/Fingerprint、Settings/User/Storage/Shortcut 及产品实际命中的其他服务。
- **执行方案**：按 C2-T01 优先级实施；TEE/Keyguard 等普通 APK 无法虚拟化的边界必须明确 fail-closed 或
  NOT_SUPPORTED；同步更新 capability registry 和 VA PRO corpus。
- **验收标准**：所有 P0/P1 方法为 PROVEN、KNOWN_LIMITATION 或 NOT_APPLICABLE，不得留无 owner 的 UNKNOWN；
  阶段回归不破坏 C1。
- **任务回执**：记录关闭条目、明确限制、负面安全结果、全量回归和 C2 阶段门禁。

### 6.3 阶段门禁

SX/XH F1-F5 实际系统服务调用面 100% 有 `RD_API32` L3 证据；P0/P1 无 `NOT_PROVEN`；C1 全量回归通过。

## 7. 阶段 C3：Native、ABI、隔离边界与可选 Hook 扩展

### 7.1 阶段目标

补齐 trusted native compatibility，证明 hostile native 的实际安全边界，并用正式决策确定 seccomp user-notify 和
ART/Xposed 是否进入产品范围。

### 7.2 任务列表

`C3-T01` native corpus；`C3-T02` 文件/proc/network/FD；`C3-T03` ABI/16KB/native media；
`C3-T04` hostile isolation；`C3-T05` seccomp 决策；`C3-T06` ART/Xposed 决策或实现。

### C3-T01：建立 native 绕过与兼容 corpus

- **任务目标**：覆盖 libc、直接 syscall、JNI、dlopen/late-load 和自定义 loader 可达到的真实入口。
- **执行方案**：建立 open/openat/openat2/faccessat2/stat/xattr/cwd/execve/socket/ioctl/raw SVC fixture；记录每条
  是兼容虚拟化、隔离拒绝还是当前不可控；与 VA/NBB 只做行为比较。
- **验收标准**：产品 scope 内 native 入口全部分类；fixture 可在四 ABI 构建；任何宣称拦截的路径都有正负测试；
  不把 PLT 命中等同于 raw syscall 安全。
- **任务回执**：记录 syscall/API corpus、入口类型、预期策略、四 ABI 构建结果和新 Known Issues。

### C3-T02：文件系统、procfs、网络与 FD 生命周期

- **任务目标**：关闭路径重定向、proc 视图、socket/network 和文件描述符继承/回收的兼容与泄漏问题。
- **执行方案**：覆盖 dfd、relative path、symlink、unknown proc leaves、maps/smaps/fd/task/cgroup、`/proc/net`、
  socket/DNS、dup/pass/inherit/close-on-exec；将状态接入权威 ledger。
- **验收标准**：路径和 proc 视图不泄漏其他 Guest；未知入口按策略拒绝或透明处理；clear/death/exec 后 FD 收敛；
  直接 syscall 绕过被测试明确暴露而非静默标为 PASS。
- **任务回执**：记录路径/proc corpus、FD 前后快照、网络 trace、泄漏扫描和限制说明。

### C3-T03：四 ABI、16 KB page 与 native Camera/Media

- **任务目标**：验证 x86/x86_64/ARM32/ARM64 产物、Companion32、16 KB page 以及 native Camera/Media 路径。
- **执行方案**：检查 ELF ABI/page alignment/依赖，运行 cross-width fixture；在可用设备上验证加载、late dlopen、
  native buffer/Surface/codec；缺少 ARM/16KB 设备时生成明确的环境阻断回执。
- **验收标准**：四 ABI 构建和静态校验通过；可用设备动态路径通过；Companion32 revision/identity 正确；
  16KB 产物满足加载要求，未验证环境不得宣称动态 PASS。
- **任务回执**：记录 ELF 报告、设备/API/page size、加载 trace、native media 结果和环境缺口。

### C3-T04：Hostile native 隔离与 Broker-only 能力

- **任务目标**：证明恶意或不可控 native 代码无法直接取得 CAS 核心状态和 Host 权限。
- **执行方案**：测试 isolated UID/process、继承 FD、`/dev/binder`、socket、ptrace、clone、execve、broker capability
  和重放；无法安全虚拟化的操作必须拒绝，不允许静默穿透。
- **验收标准**：攻击 fixture 不能访问其他 Guest、核心存储或未授予 Host 能力；capability 有 scope/revision/expiry；
  进程死亡后全部撤销；残余内核限制进入 threat model。
- **任务回执**：记录攻击矩阵、允许/拒绝证据、capability ledger、逃逸尝试和剩余风险。

### C3-T05：seccomp/user-notify 可行性决策（条件任务）

- **任务目标**：确定目标内核与普通 APK 权限下 user-notify 是否可部署，避免把研究 POC 当生产承诺。
- **执行方案**：验证 kernel config、listener ownership、FD transfer、SELinux、zygote/app sandbox 和性能；比较 deny-only、
  privileged companion、OEM image 三种部署；形成 ADR 和 POC 证据。
- **验收标准**：若可行，给出最小生产架构、威胁模型和性能数据并实现批准范围；若不可行，标记
  `NOT_APPLICABLE`，明确适用的特权部署和 C3-T04 替代边界。
- **任务回执**：记录内核/SELinux/权限探测、POC 结果、ADR 结论、产品影响和最终状态。

### C3-T06：ART/Xposed Compatibility Extension 决策（条件任务）

- **任务目标**：确认是否必须加载任意第三方 Xposed 模块；仅在产品明确要求时建设受控模块宿主。
- **执行方案**：先完成需求、威胁、来源和许可证审查；若仅需 F2-F5，标记 `NOT_APPLICABLE` 并继续通用适配；
  若需要模块生态，独立实现 classloader、xposed_init、callback、hook/unhook、scope、generation cleanup 和限额。
- **验收标准**：决策有批准依据；引擎不得获得 PackageService/RuntimeBroker 根权限；默认关闭且精确 scope；
  测试必须证明目标 Guest 方法被 callback 拦截，而非只证明服务启动。
- **任务回执**：记录产品决策、许可证清单、架构/威胁模型、模块 fixture、性能和安全结果。

### 7.3 阶段门禁

trusted native P0 路径在 `RD测试` 闭环；hostile 边界有可重复攻击证据；条件任务均有 DONE 或
NOT_APPLICABLE 决策；C1/C2 回归无退化。

## 8. 阶段 C4：SX 迁移与业务验收

### 8.1 阶段目标

在 C1、C2 P0、C3 trusted native 门禁通过后，将 SX 从 BlackBox/Pine 混合架构迁移为 CAS 唯一宿主，
并在 `RD测试` 完成真实业务长稳。

### 8.2 任务列表

`C4-T01` 冻结清单；`C4-T02` CAS adapter；`C4-T03` 数据迁移；`C4-T04` 移除旧 runtime；
`C4-T05` F1-F5 与业务长稳。

### C4-T01：冻结 SX 依赖、功能与运行时清单

- **任务目标**：明确 SX 当前 BlackBox、Pine/Xposed、UI、数据、Provider、DingTalk hook 和 F1-F5 依赖。
- **执行方案**：生成 Gradle/runtime dependency graph、启动链、数据 schema、业务调用与 fallback 清单；
  区分通用能力、迁移工具和临时兼容扩展。
- **验收标准**：所有生产入口都有目标 CAS 映射；未知/缺失类和动态加载被记录；每项旧依赖有保留、替换或删除结论。
- **任务回执**：记录依赖图、功能映射、数据清单、风险、迁移顺序和阻塞项。

### C4-T02：实现 SX 到 CAS SDK 的唯一引擎适配

- **任务目标**：让 SX 的 install/start/stop/user/profile/status 全部通过 CAS SDK，不再直接调用 BlackBoxCore。
- **执行方案**：实现 `SandboxEngine` adapter、错误映射和状态观察；UI 只消费 CAS contract；用 fixture 验证安装、启动、
  多用户、清理和恢复。
- **验收标准**：目标入口全部走 CAS；adapter 不复制 CAS 权威状态；失败可诊断、可回滚；通用 fixture 和 SX smoke 通过。
- **任务回执**：记录接口映射、调用 trace、错误场景、状态一致性和迁移未完成入口。

### C4-T03：迁移 SX 用户、包、Profile、媒体与配置数据

- **任务目标**：无损迁移 SX 现有用户配置和业务数据，且失败不会破坏旧数据。
- **执行方案**：设计版本化、幂等、可回滚迁移；覆盖 package/user/profile/media/ConfigProvider 数据；
  使用脱敏 fixture 做旧版本到新版本和重复迁移测试。
- **验收标准**：迁移前后语义一致；中断可恢复；重复执行无副作用；不同用户不串数据；旧数据保留到明确确认成功。
- **任务回执**：记录 schema/version、样本 hash、迁移/回滚结果、故障注入和数据差异。

### C4-T04：移除 SX 生产 BlackBox/Pine/Xposed 运行时

- **任务目标**：消除双沙箱、双 Hook、重复 classloader 和冲突状态源。
- **执行方案**：在功能迁移完成后逐项删除或禁止 engine-bb/Bcore/Pine 生产依赖和启动入口；保留参考源码时确保不打包；
  加入 dependency/APK content gate。
- **验收标准**：生产依赖图与 APK 中没有 BlackBox/NBB/Pine runtime；无旧 engine 反射/动态加载；CAS-only smoke 通过；
  删除不影响已迁移数据。
- **任务回执**：记录删除项、依赖/APK 扫描、包体变化、启动 trace 和回滚点。

### C4-T05：SX F1-F5、DingTalk 与长稳验收

- **任务目标**：验证虚拟相机、定位、设备、网络/基站、蓝牙及核心 SX 业务在 CAS 上完整运行。
- **执行方案**：先用通用 fixture，再按 F1、F2、F4、F5、F3 顺序开启；覆盖 ConfigProvider、FileProvider、shortcut、
  FGS、notification、Job、WebView、多进程和指定 DingTalk revision。
- **验收标准**：F1-F5 实际调用面全有证据；指定 DingTalk 冷/热启动、升级、登录、前后台通过；关键业务 100 轮、
  8 小时 soak 达标；特化关闭后通用 fixture 不变。
- **任务回执**：记录 SX/DingTalk 版本、业务脚本、F1-F5 证据、循环/soak 指标、崩溃/ANR 和阶段门禁。

### 8.3 阶段门禁

SX 生产路径只有 CAS 沙箱；目标业务在 `RD测试` 100 轮与 8 小时长稳通过；无未解释 P0/P1 业务阻断。

## 9. 阶段 C5：XH 产品支持与可选模块路线

### 9.1 阶段目标

复用 CAS/SX 已关闭能力支持原始 XH 沙箱产品；只有明确要求时才交付 `spoofer_project` 模块宿主能力。

### 9.2 任务列表

`C5-T01` XH 契约；`C5-T02` CAS Host 集成；`C5-T03` XH 业务验收；`C5-T04` 可选 Xposed 模块验收。

### C5-T01：冻结原始 XH 产品能力契约

- **任务目标**：从恢复源码和文档中提取原始 XH 的 UI、数据、F1-F5、DingTalk 和用户迁移需求。
- **执行方案**：审计 `com.xin.h6` 启动链、VA Shadow slots、libvv/libpine、VirtualCamera/FackLocService 和资源；
  将原产品与独立 `spoofer_project` 分开建表。
- **验收标准**：每项原始 XH 功能映射到 CAS、SX 复用、独立开发或不适用；不存在把原始 XH 误判为纯模块的条目。
- **任务回执**：记录功能契约、调用/数据映射、旧依赖、两条产品路线和缺失源码风险。

### C5-T02：实现原始 XH 的 CAS Host/SDK 集成

- **任务目标**：使用 CAS 替代旧 VA Host、Shadow stubs、libvv 和未知 Pine 路径。
- **执行方案**：复用 C4 adapter/profile/data 模式，迁移 XH UI 与产品差异；通用缺陷回到 CAS fixture，
  不复制旧 VA/NBB/Pine 生产实现。
- **验收标准**：XH install/start/user/profile/F1-F5 走 CAS；旧 VA runtime 不进入生产 APK；数据与 UI 流程可恢复；
  SX/CAS 回归不受影响。
- **任务回执**：记录集成映射、依赖/APK 扫描、关键流程、数据结果和共用/特化代码比例。

### C5-T03：原始 XH 与 DingTalk 业务验收

- **任务目标**：完成原始 XH 产品范围的功能、稳定性和故障恢复验收。
- **执行方案**：运行与 SX 相同的通用 suite，再执行 XH 差异用例、DingTalk revision、F1-F5、前后台、多进程、
  clear/upgrade/death/recovery 和长稳。
- **验收标准**：产品契约 100% 有结论；关键业务 100 轮、8 小时 soak 达标；无旧 VA/Pine 运行时；
  XH 特化关闭后通用行为不变。
- **任务回执**：记录版本、业务矩阵、循环/soak、恢复、崩溃/ANR、残余差异和阶段门禁。

### C5-T04：`spoofer_project`/第三方 Xposed 模块验收（条件任务）

- **任务目标**：仅在 C3-T06 决定建设模块宿主时，验证受控 Xposed 模块在目标 Guest 中工作。
- **执行方案**：分别测试外部 root/LSPosed 模式与 CAS 内置宿主模式；验证 scope、module classloader、callback、
  hook/unhook、进程代际、异常隔离和资源限额。
- **验收标准**：必须证明目标 Guest 方法被模块 callback 拦截；模块不能越权访问其他 Guest/CAS 核心；
  卸载、禁用、死亡后 hook 全部清理。若不在产品 scope，提交 `NOT_APPLICABLE` 决策。
- **任务回执**：记录模块版本/签名、目标 scope、hook trace、安全负测、性能、清理结果或排除理由。

### 9.3 阶段门禁

原始 XH 产品在 `RD测试` 达到与 SX 同级的通用能力和业务稳定性；可选模块路线有 DONE 或
NOT_APPLICABLE 回执。

## 10. 阶段 C6：Android API 与 ABI 扩展

### 10.1 阶段目标

在不引入 OEM 变量的前提下，把同一套 C1-C5 验收扩展到 API33-37 和目标 ABI。

### 10.2 任务列表

`C6-T01` API33-37；`C6-T02` ARM/跨宽度/16KB；`C6-T03` Android 矩阵发布门禁。

### C6-T01：API33-37 统一回归

- **任务目标**：识别并关闭 framework/AIDL/Parcel/权限/后台策略随 API 的变化。
- **执行方案**：按 33、34、35、36、37 顺序，在 AOSP 环境运行同一 C1-C5 suite；修复应基于 API/signature，
  禁止业务包名分支；更新 API capability matrix。
- **验收标准**：每个声明支持的 API 均有完整设备快照和回归结果；targetSdk、AttributionSource、FGS/Job/
  notification、WebView 等关键漂移有证据；P0/P1 无未分类失败。
- **任务回执**：记录各 API image/patch、结果差异、修复、性能和未支持 API 理由。

### C6-T02：ARM32/ARM64、跨宽度和 16KB 动态验收

- **任务目标**：补齐 ARM 真机/模拟环境和 16KB page 的动态证据。
- **执行方案**：选择可审计环境运行 native、Companion32、Camera/Media、process death 和 SX/XH smoke；
  对照 C3 静态结果，记录指令集和 linker 差异。
- **验收标准**：目标 ARM32/ARM64 组合通过；16KB 环境可加载并运行关键路径；跨宽度身份/revision 正确；
  不以 x86 结果替代 ARM。
- **任务回执**：记录硬件/镜像、ABI/page size、产物 hash、动态 trace、性能与失败签名。

### C6-T03：关闭 Android Matrix 发布门禁

- **任务目标**：形成精确到 API/ABI/业务的支持声明和回归入口。
- **执行方案**：汇总 C6-T01/T02，更新 registry、compat matrix、release gate 和自动化；对未支持组合写风险接受或
  发布阻断，不做泛化宣传。
- **验收标准**：支持矩阵每个格子有 evidence ID；发布 gate 能阻止缺证据组合；SX/XH 在目标组合完成 smoke/关键业务。
- **任务回执**：记录最终矩阵、gate 输出、排除组合、风险接受和进入 OEM 阶段的基线。

### 10.3 阶段门禁

所有声明支持的 API/ABI 组合达到 Android Matrix 证据等级；不再存在用 RD/API32 结果代替其他组合的状态。

## 11. 阶段 C7：OEM 厂商适配与商业发布收敛

### 11.1 阶段目标

在通用能力、SX/XH 和 Android Matrix 稳定后，按真实市场优先级处理厂商差异，形成限定范围的 VA PRO 等价声明。

### 11.2 任务列表

`C7-T01` OEM 优先级与实验设计；`C7-T02` 分厂商适配；`C7-T03` 商业发布总验收。

### C7-T01：确定 OEM 优先级和代表设备

- **任务目标**：用用户占比、Android 版本和业务风险选择设备，避免同时引入过多变量。
- **执行方案**：优先一台真实目标厂商/版本，再安排 HyperOS/MIUI、ColorOS、OriginOS、EMUI/HarmonyOS；
  固定设备快照、ROM、权限设置、后台策略和同一回归 suite。
- **验收标准**：设备选择和顺序有数据依据；每台设备可复现、可恢复；通用/SX/XH 三层测试边界明确。
- **任务回执**：记录优先级依据、设备/ROM 清单、实验控制变量、访问条件和计划顺序。

### C7-T02：逐厂商执行通用、SX、XH 适配

- **任务目标**：关闭目标 OEM 的 framework、权限、后台、WebView、Camera/Location 和进程差异。
- **执行方案**：每个 OEM 先跑通用 suite，再跑 SX，最后 XH；patch 必须带 manufacturer/API/framework signature/
  reproduction，默认不影响 AOSP；每家独立提交和回执。
- **验收标准**：目标 OEM P0/P1 全部通过或有批准限制；厂商 patch 不破坏 RD/API matrix；关键业务长稳达标；
  OTA/ROM 升级有回归触发条件。
- **任务回执**：每个 OEM 单独记录设备/ROM、失败签名、patch guard、全量回归、SX/XH 结果和风险。

### C7-T03：VA PRO 范围等价与商业发布总验收

- **任务目标**：对限定版本、API、ABI、OEM 和业务范围给出最终能力声明。
- **执行方案**：汇总全部阶段证据；逐条关闭产品 scope 内 VA PRO corpus；执行供应链、安全、架构、发布、
  SX/XH 业务和长稳 gate；生成最终报告与回滚方案。
- **验收标准**：scope 内 VA PRO corpus 均有 PROVEN、NOT_APPLICABLE 或批准的风险接受；P0/P1 无 NOT_PROVEN；
  支持矩阵达到 L5；最终声明明确限定范围，不使用无边界“完全兼容”。
- **任务回执**：记录 corpus 统计、所有 gate、产物/SBOM/hash、支持范围、已知限制、回滚和发布批准。

### 11.3 阶段门禁

声明支持的 OEM/API/ABI/SX/XH 组合全部有商业设备证据；最终报告、产物、SBOM、风险和回滚方案完整并已推送。

## 12. 跨环境无损续接判定

新环境只有同时满足以下条件才能继续下一个任务：

1. 已完整读取任务书与进度账本；
2. 当前分支和远端分支与账本一致；
3. 远端包含最后一个完成任务的实现提交与回执提交；
4. 工作区改动归属明确；
5. 最后证据路径可读取，设备依赖可重新解析；
6. 下一任务依赖全部完成；
7. 若上一任务为 `BLOCKED`，已满足回执中的恢复条件；
8. 续接环境差异已写入当前任务回执，未把旧环境 PASS 复制为新环境 PASS。

## 13. 读取任务书并开始执行的指令

后续可直接向执行代理发送以下指令：

> 请严格按照 `docs/plans/CAS_VA_PRO_CATCH_UP_EXECUTION_TASK_BOOK_20260821.md` 执行追赶计划。
> 开始前必须完整读取该任务书、`docs/plans/CAS_VA_PRO_CATCH_UP_EXECUTION_PROGRESS.md`、
> `docs/capability/CAPABILITY_CAMPAIGN_WORKFLOW.md` 和 `docs/COMMIT_IDENTITY_POLICY.md`，然后检查当前分支、
> Git 状态、最近提交、远端同步状态及最后任务回执。根据进度选择第一个依赖已满足的 PENDING 任务，
> 本次只执行一个任务。先 DISCOVER/CLASSIFY，再设计、实现和验收；涉及设备时动态解析 MuMu `RD测试`，
> 不得硬编码端口。任务完成后先提交代码与证据，再把实现提交 SHA、验收结果、环境、证据、风险和下一任务
> 写入进度账本并单独提交，随后推送两个提交并验证远端 HEAD。若验收或推送失败，写 BLOCKED 回执并停止，
> 不得跳到下一任务。
