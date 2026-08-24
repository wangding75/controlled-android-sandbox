# C4 RD 重验根因分析与修复验收方案

日期：2026-08-24

范围：MuMu 模拟器实例 `RD测试`、C4 SX 迁移与业务验收、提交
`6e1044b013fab19a53dd4ceab75230963c4dd83f` 与
`1bef0951218ec8356f94c869ae9131ad5859864e`。

## 1. 结论

C4-T01 至 C4-T04 的架构交付可以保留为历史 DONE，但 C4-T05 的设备验收结论不能继续作为
C4 阶段关闭证据。原因不是单一黑屏缺陷，而是验收判据、操作状态机和问题处理流程同时存在缺口：

1. `LAUNCH_PASS` 只证明 Broker 接收并路由了启动请求，不证明 Guest 窗口绘制成功；
2. 原 C4 runner 只要求 Stub、包名或 `GUEST_ACTIVITY_CREATE` 出现，黑屏 Activity 也能通过；
3. 100 轮循环在第一次启动失败时会 stop、等待 400 ms 后静默重试，首发失败被掩盖；
4. 添加 App 只验证单次成功，没有覆盖重复点击、重复导入、失败回滚、Host/Guest 死亡和事务残留；
5. 添加和启动缺少统一 request ID、阶段耗时、确定性 deadline 和可诊断终态；
6. 黑屏修复晚于 C4-T05，原 C4 证据的 commit 早于修复，必须在修复后的同一 commit 上重跑；
7. 黑屏修复提交一次修改多个假设面，且加入宽泛的 post-resume addView 重试，需要收敛和回归。
8. 当前现场反馈夸克可以添加，而红果、番茄小说添加时抛异常；本轮不定位实际异常，先将三者加入
   同一商业样本矩阵，红果和番茄小说不得因夸克通过而豁免。

因此，C4 阶段状态改为 `IN_PROGRESS (REOPENED)`，新增 C4-R01 至 C4-R05。只有新的 RD 重验
全部通过才能重新关闭 C4，并进入 C6。C5 按用户决定整体跳过。

## 2. 四个已知问题的原因分析

### 2.1 添加 App 后启动黑屏，但验收通过

直接原因分为 runtime 缺陷和验收缺陷。

Runtime 已确认的触发链：

- 首次 resume 时，旧 `ActivityFieldBridge` 把 `onCreate/setContentView` 已创建但尚未发布的 `mDecor`
  当作陈旧窗口处理，并设置 `hideForNow`；
- API32 的 `ViewRootImpl` 构造期间调用
  `AudioManager.areNavigationRepeatSoundEffectsEnabled()`，旧 Audio interceptor 抛出
  `VIRTUAL_AUDIO_ROUTING_OPERATION_UNSUPPORTED`，使 `WindowManagerGlobal.addView` 中断；
- Window Binder、`LayoutParams.packageName`、ContextImpl op-package 与 WMS 看到的 Host UID 之间
  还存在身份契约缺口；
- 结果是 Activity 已到 `RESUMED`，但 `windows=[]`、`reportedDrawn=false`，用户只看到黑屏。

验收误判原因：

- `run_c4_t05_rd.py` 只检查 `LAUNCH_PASS`、Stub、Guest 进程和 Activity marker；
- `GUEST_ACTIVITY_CREATE` 发生在窗口绘制以前；
- 原 runner 没有检查 `reportedDrawn`、`hasVisible`、Window/Surface、首帧内容或截图；
- 100 轮只统计路由返回，没有每轮验证可见窗口。

所以原结论实际是“启动请求被接受”，不是“App 成功显示”。

### 2.2 添加 App 后启动出现超时

当前源码能确认以下结构性原因，但具体首要瓶颈仍必须用新 trace 复现后裁决：

- `RebindableServiceConnector` 单次获取 Binder 的默认 deadline 为 30 秒；错误只汇总为 service
  unavailable，UI 看不到 bind、prepare、attach、launch、draw 中具体哪个阶段耗尽时间；
- UI 所有导入、刷新、启动和状态保存共享一个 single-thread executor，前一个大 APK 导入、catalog
  refresh 或残留任务会让后一个启动排队，看起来像“启动超时”；
- DingTalk runner 使用 8/12 秒固定 sleep，没有等待确定的 `GUEST_PREPARED`、`RESUMED`、`DRAWN`
  状态，因此快环境浪费时间，慢环境又可能提前进入下一步；
- 启动 API 返回 `LAUNCH_PASS` 后仍可能在 Guest bind、Application 创建、Activity transaction 或
  Window draw 阶段失败，调用层没有端到端 readiness contract；
- force-stop Host、Guest death barrier、revision re-import 和启动被组合在同一大命令中，单一 240 秒
  deadline 无法区分业务慢、Binder 慢或 runner 等待不当。

### 2.3 偶现添加失败

原 C4 验收无法证明添加可靠性：它只执行一次 fixture import 和一次 DingTalk import/re-import，
没有重复添加压力或故障注入。需要重点验证：

- 同一包的重复点击、并发请求是否被合并或明确拒绝；
- `.install-*` staging、revision publish、catalog commit、ensureInstance 之间失败是否原子回滚；
- Host 包的 base/split 集合在读取过程中是否发生变化；
- 大 APK copy/hash/extract 与 Binder/session 生命周期是否超过 deadline；
- 失败后是否残留 in-flight lifecycle transaction、半发布 revision、过期 record 或孤儿实例；
- UI 在操作中没有禁用重复按钮，也没有 operation ID、进度阶段和取消/恢复语义；
- `SandboxViewModel` 单线程队列没有队列长度、开始时间和超时可观测性。

在拿到失败签名以前，不把偶发失败归结为“模拟器慢”或通过无条件重试关闭。

补充样本边界：夸克当前可添加，只能作为正向对照，不能证明通用导入兼容性；红果、番茄小说的添加异常
先登记为 CAS 通用导入兼容性待修缺口。C4-R01 执行时再依据同一 operation ID 的边界证据分类：若失败发生在
CAS 的 APK snapshot/copy/parse/native/publish/catalog/instance 链路，由 CAS 修复；只有 CAS 已返回可验证的
添加成功、而 SX adapter/UI 随后抛错或误报时，才转交 SX。无论最终归属，C4-R02 必须保证三者都能添加。

### 2.4 不停重试、猜问题，没有先对照 VA/NBB

现有源码中可见三类重试：

- C4 100 轮：首次启动失败后 stop + 400 ms，再启动一次；
- `killSettled`：最多 5 次 stop；
- `ensureWindowPublishedAfterResume`：捕获任意 Throwable 后最多再尝试 5 次 addView。

这些重试虽然有限，但没有统一 error classification、attempt trace 和 retry budget，并可能把第一次失败
从主验收结果中隐藏。正确流程应是：先保存首次失败证据，按 error code 判定是否可重试，然后对照
VA/NBB 的同一调用链，写设计后一次性修复。

## 3. 两个指定提交的审查

### 3.1 `6e1044b0` 黑屏修复

做对的部分：

- 提交说明和设计文档明确引用 NBB `ContextCompat.fix`、`IWindowSessionProxy`、
  `IWindowManagerProxy` 与 `IAudioServiceProxy`；
- 找到了 API32 `ViewRootImpl` 的 Audio 查询阻断；
- 新增 Quark `reportedDrawn/windows` 失败判据，已不再只看 launch marker；
- 首次 resume 不再把仅存在 `mDecor` 误判为陈旧窗口。

仍需优化的部分：

1. 一个提交同时修改 Audio、WindowManager、Binder、Activity record、task route 和 GuestContext，
   缺少逐假设的 A/B 证据；
2. `ensureWindowPublishedAfterResume` 主动调用 addView、捕获所有 Throwable 并重试 6 次；NBB 的核心
   合同是修正 Context/WindowSession 后交给正常 ActivityThread，而不是长期依赖 post-resume 强制发布；
3. `GuestContext.hostServiceContext()` 从 package-private 改为 public，暴露了原本受限的 Host Context；
   应改成最小 window identity capability，不能把原始 Host Context 暴露给 Guest 可反射表面；
4. `ActivityRuntimeRouteCoordinator` 对部分旧 task action 直接分配新 physical identity，需验证不会形成
   重复窗口、token 泄漏或错误 task restore；
5. Audio 查询固定返回 false 与 NBB “未 Hook 方法透传”不是完全相同，需要验证 Android 基准行为和
   Guest-owned policy，避免因修黑屏改变音频语义；
6. 设备验证只覆盖 Quark，没有在修复 commit 上重跑 C4 DingTalk/SX；
7. 被引用的 tracked `c2-t05-rd-summary.json` 仍记录旧 commit `b1655e74`，提交本身没有包含新设备
   evidence 文件，证据链不闭合。

### 3.2 `1bef0951` 进度回执

该提交只给已有 C2-T05 回执追加 9 行说明。它没有：

- 重开 C4 阶段；
- 作废修复前的 C4-T05 PASS；
- 记录黑屏修复实现 SHA、开始基线、APK hash、设备 boot ID 和原始日志目录；
- 提交新的机器可读 RD summary；
- 验证 DingTalk 或 SX UI 添加/启动路径。

因此它是说明性记录，不是完整任务回执，不能作为 C4 重验完成证据。

## 4. 必须采用的 VA/NBB 对照方式

每个 C4-R 修复任务在 DESIGN 前必须输出 reference mapping：

| CAS 问题面 | NBB 必读实现 | VA 必读实现 | 需要提取的合同 |
|---|---|---|---|
| Activity/window | `ContextCompat`、`IWindowManagerProxy`、`IWindowSessionProxy`、`BActivityThread` | window session proxies、Instrumentation/HCallback、StubActivity | Context/UID、IWindow、token、正常 ActivityThread 时序 |
| 启动 | `BlackBoxCore.launchApk`、`BActivityManagerService.startActivity` | `VActivityManager`、Activity stack/stub route | 请求接受、进程 ready、Activity ready、窗口 drawn 的边界 |
| 安装 | `BPackageManagerService.installPackageAsUserLocked`、`BPackageInstallerService` | `VAppManagerService`、PackageInstaller session | 包锁、copy/parse/publish 顺序、失败回滚、通知时点 |
| 重试 | Binder/service injection 与 process init | client bind/process restart | 哪些错误可重试、最大次数、代际与幂等键 |

参考实现用于提取状态机和边界，不直接复制代码。每个采纳/不采纳点都必须写理由和 CAS 对应测试。

## 5. 修复任务与顺序

### C4-R01：证据纠偏、确定性复现和参考实现映射

- 保留修复前 C4 evidence，但标记 `SUPERSEDED`，禁止删除历史；
- 在当前 HEAD、同一 `RD测试` 快照分别复现黑屏、启动超时、添加失败，首次失败立即停止并采集全量证据；
- 对每个问题建立 request ID 时间线：UI enqueue/start、import stage、catalog commit、broker bind、prepare、
  Guest attach、Activity create/resume、window add、first draw；
- 完成第 4 节 NBB/VA mapping 后才允许设计生产修复。
- 对夸克、红果、番茄小说动态记录实际 package/version/base/split/ABI，不在计划阶段猜测包名；夸克作为
  正向对照，红果和番茄小说作为异常样本，按 CAS 导入边界与 SX adapter/UI 边界裁决 owner。

### C4-R02：添加事务、超时和 UI 操作状态机修复

- 同 package/user 同一时间只允许一个 mutating operation，重复点击返回同一个 operation 或明确 BUSY；
- UI 操作期间禁用对应按钮，展示 stage、elapsed、request ID，终态后再恢复；
- 为 copy/hash/parse/native extract/publish/catalog/ensureInstance 分段计时并设置各自 deadline；
- 只对明确 `retryable=true` 的 bind/unavailable 错误允许至多一次自动重试；解析、签名、ABI、权限、
  安全和事务错误禁止重试；
- 任何失败都必须回收 staging/in-flight state，保持旧 revision 和 catalog 可用。

### C4-R03：启动 readiness 与窗口合同收敛

- 将 launch 结果拆成 `REQUEST_ACCEPTED`、`GUEST_READY`、`ACTIVITY_RESUMED`、`FIRST_FRAME_DRAWN`；
- UI 的“启动成功”和设备验收只接受当前 package/user/revision/request ID 的 `FIRST_FRAME_DRAWN`；
- 对照 NBB/VA 收敛 `6e1044b0`，优先让 ActivityThread 正常 addView；如必须保留 fallback，限定为一个
  明确错误、一次尝试、代际绑定，不得捕获所有异常循环猜测；
- 用最小 Host window identity capability 替换 public raw Host Context；
- 验证 Audio read-only query、IWindow identity、task restore 和重复窗口的独立回归。

### C4-R04：重写 C4 fail-closed 验收编排

- 移除静默 launch retry；首次失败即该轮 FAIL，并保存前后状态；恢复能力作为独立用例；
- 每次启动检查 `dumpsys activity/window`、Surface、`reportedDrawn/hasVisible`、目标 Guest Activity、
  first-draw marker 和非全黑截图；
- 每个操作写 attempt、stage timing、error code、retry decision 和 artifact path；
- runner 只通过确定状态等待，不使用固定 8/12 秒 sleep 代替 readiness；
- 添加 fixture 50 次事务循环，以及 DingTalk、夸克、红果、番茄小说各 10 次真实添加/删除/重加循环；
- user0/user1 各 50 次冷/热启动，不能只验证 user0；
- 30 分钟 RD 压力由两个虚拟用户各 15 分钟组成，合计约 30 分钟；每用户至少 50 周期。

### C4-R05：MuMu RD 正式重验与阶段关门

- 在同一 clean commit 构建、安装并运行 C4-R04 suite 两遍；
- 第一遍验证 clean install/cold path，第二遍验证 retained state/hot/recovery path；
- 夸克正向对照、红果和番茄小说异常样本均须满足添加门槛；不得用一个商业 App 的成功替代其他样本；
- C1 Activity、C2 Window/Audio、C4 CAS-only 和 SX F1-F5 回归同时通过；
- 生成新的 C4 summary、APK SHA-256、设备快照、完整日志、截图/帧 hash 和 Known Issues 状态；
- 通过后才把 C4 重新标记 DONE；C5 为 NOT_APPLICABLE，下一任务进入 C6-T01。

## 6. RD 验收门槛

### 6.1 添加 App

- package-neutral fixture：50 次 add/delete/re-add，成功率 100%；
- DingTalk 7.8.10/1178：10 次 add/delete/re-add，成功率 100%；
- 夸克、红果、番茄小说：分别 10 次 add/delete/re-add，单项成功率均为 100%；执行时记录实际
  package/version/base/split/ABI，任务书不预设包名；
- 红果或番茄小说任何一次抛异常、超时或形成半安装状态，均直接判定添加门禁 FAIL；夸克成功不能抵消；
- 重复点击、并发添加、Host/PackageService 死亡各有独立负面或恢复用例；
- 失败时无 `.install-*`、半发布 revision、in-flight transaction、孤儿 instance；
- UI 在 1 秒内反馈已受理和 request ID；阶段耗时完整；禁止无上限或无分类重试。

### 6.2 启动 App

- user0/user1 各 50 次冷/热组合，首次尝试成功率 100%；
- 每轮必须到 `FIRST_FRAME_DRAWN`，且 `windows` 非空、`reportedDrawn=true` 或等价强证据；
- 截图的目标窗口区域不得为全黑/全透明/Host 占位页；同时存在目标 Guest Activity 与 revision marker；
- 不出现启动超时、重复 Stub、重复 ViewRoot、BadToken、`View not attached`、FATAL 或 ANR；
- cold first-frame 初始 RD SLO 为 30 秒，hot first-frame 为 10 秒；超出即 FAIL，不通过延长 runner
  总 deadline 掩盖。

### 6.3 30 分钟压力

- user0 15 分钟且至少 50 周期；user1 15 分钟且至少 50 周期；顺序执行合计约 30 分钟；
- 时间与最少轮次必须同时满足；若 15 分钟不足 50 周期，本轮 FAIL，不解释成 `30 分钟 × 50`；
- 黑屏、添加失败、启动超时、隐藏重试均为 0；
- 内存、线程、FD、Binder、Window/ViewRoot、process slot 和 staging 目录最终收敛；
- 任何新问题先登记 Known Issue。P0/P1 未关闭时不得关 C4；非阻断问题必须有 owner、后续任务和风险说明。

## 7. 不在本轮范围

- C5 原始 XH 与可选模块路线按用户决定跳过；
- API33-37、ARM/16KB 和 OEM 分别属于 C6/C7；
- 本轮结果仍只代表 `RD测试` API32，不代表 VA PRO 等价或商业矩阵完成。
