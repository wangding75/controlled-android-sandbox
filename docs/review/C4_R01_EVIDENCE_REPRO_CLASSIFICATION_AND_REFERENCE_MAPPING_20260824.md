# C4-R01 证据纠偏、首次失败分类与 VA/NBB 映射

日期：2026-08-24<br>
任务：`C4-R01`<br>
基线：`feature/t57-r03-va-pro-capability-campaign` @ `d73003748d64ef70fe8b74c03b8c733be1338636`

## 1. 任务边界与结论

本任务只完成证据纠偏、首次失败保全、owner 分类和参考实现映射，没有修改生产代码，也没有提前执行
`C4-R02`、`C4-R03`、`C4-R04`、`C4-R05`、C6 或 OEM 适配。

已确认：

- 原 C4-T05 两份 summary 已标记 `SUPERSEDED`、`historical_only=true`、
  `usable_for_c4_closure=false`；历史文件保留但不得关闭 C4。
- 当前 HEAD 上的夸克正向对照首次通过并显示真实 Guest 页面；红果、番茄小说首次导入均在 CAS
  `ApkImportManager` native ELF/ABI 校验边界失败，签名均为
  `SecurityException: NATIVE_ELF_ABI_MISMATCH:arm64-v8a`。
- 当前 HEAD 的受控 resume-crash 启动在 43.729 秒以
  `LAUNCH_GATE_FAILED: guest Activity create/resume/window not confirmed` 首次失败，超过 30 秒 cold
  first-frame SLO；失败后立即保存 screenshot、logcat、Activity、Window、Surface、进程、事务和设备快照。
- 原 C4 100 轮代码在首次 launch 非 `LAUNCH_PASS` 时执行 stop、`sleep(400 ms)` 和第二次 launch，
  确认存在隐藏首次失败的路径。本任务的所有 operation 均为 attempt 1、retry budget 0。
- 修复前同一 `RD测试` 的原始 evidence 已确认黑屏签名：Guest Stub `state=RESUMED`，同时
  `windows=[]`、`hasVisible=false`、`reportedDrawn=false`，而 operation 已是 `LAUNCH_PASS`。
- 当前生产路径没有把同一 request/operation ID 贯穿 import、catalog、bind、prepare、attach、Activity、
  Window 和 first draw；本任务只能在 harness/debug-command 边界关联，这是已确认的 telemetry gap。

待验证：

- 黑屏的上述精确签名未在当前 HEAD 再次出现；这与 `6e1044b0` 的部分恢复一致，但不能等价为 C4
  已关闭。当前修复的完整启动/窗口矩阵和 fail-closed 验收分别由 C4-R03、R04、R05 证明。
- 红果、番茄 native 集合为何被判为 `arm64-v8a` ELF mismatch 尚未下钻；在 C4-R02 之前只把 owner
  保持为 CAS 通用导入兼容性问题，不猜测模拟器、SX adapter 或 UI。
- 43.729 秒启动失败中最先耗尽的是 bind、prepare、attach、resume 还是 window readiness，现有生产
  telemetry 无法裁决，因此 owner 保持 CAS 通用启动 readiness，不猜测具体内部组件。

## 2. 续接预检与构建/设备基线

- 开始时账本下一任务为 `C4-R01`，任务状态已先改为 `IN_PROGRESS`。
- 开始时工作区干净，本地 HEAD 与远端 HEAD 均为 `d7300374`。
- Git 身份为 `OpenAI <openai@users.noreply.github.com>`，符合
  `docs/COMMIT_IDENTITY_POLICY.md`。
- MuMu 地址通过实例名 `RD测试` 动态解析；执行脚本没有硬编码 ADB 地址。该次观测解析为
  `127.0.0.1:16416`，仅作为 evidence 字段记录。
- 设备：API 32，model `22041211A`，boot ID
  `d09f0f79-058d-42af-924c-3a99f1429ea4`，Android ID `398eea33120cd887`，ABI list
  `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`。
- 构建 manifest SHA-256：
  `a25258d81e78cfc1d04524cd5edc5b58e5b457fddf0ae601ffccddbc2686df55`。
- Host APK：`97d0ea1d3a6f492ee1b07311ac0a39f17027d0534f65f89b7e19f09c98d65b7f`；
  fixture：`e4f9d4458eee7d3d123f7be11426424c0d2533329435e60185f6fd6c78691058`；
  companion32：`6f9d2e6aaf7d704e5ae74d72e938fe8ccb413c565ebbf83dd7e018ff53d44ef0`。

## 3. 商业样本动态清单

采集方法为 `pm list packages -3` → 对每包动态 `pm path` → pull 当前 base → `aapt2 dump badging`
按应用 label 匹配，再以 `dumpsys package` 记录 ABI；package ID 未写入执行器常量。

| 样本 | 角色 | 实际 package | 版本 | base/split | native/primary ABI | 首次结果 |
|---|---|---|---|---|---|---|
| 夸克 | 仅正向对照 | `com.quark.browser` | `10.10.5.1080` / 1080 | base 1 / split 0 | `arm64-v8a` | PASS，27.729 s，截图为夸克隐私页 |
| 红果免费短剧 | 独立异常样本 | `com.phoenix.read` | `7.0.5.33` / 70533 | base 1 / split 0 | `arm64-v8a` | FAIL，7.186 s，`NATIVE_ELF_ABI_MISMATCH` |
| 番茄免费小说 | 独立异常样本 | `com.dragon.read` | `7.1.9.32` / 71932 | base 1 / split 0 | `arm64-v8a` | FAIL，5.994 s，`NATIVE_ELF_ABI_MISMATCH` |

夸克 PASS 只证明该样本在该次快照可导入启动，不证明红果或番茄兼容，也不改变二者的独立 FAIL。

## 4. 四个问题的首次失败与 owner

| Known Issue | 证据状态 | 首次失败/最小复现 | owner 结论 |
|---|---|---|---|
| `KI-R03-053` 黑屏误判 | 历史已确认；当前精确签名未复现 | pre-fix fixture `StandardTaskProbeActivity`：`LAUNCH_PASS` 后立即 dumpsys，Stub 已 RESUMED 但 `windows=[]`、`reportedDrawn=false` | runtime 属 CAS Window/Activity 合同；误判属 C4 gate。R03/R04/R05 关闭 |
| `KI-R03-054` 启动超时不可诊断 | 当前已确认 readiness failure；内部首要阶段待验证 | `launch-virtual-component FaultMainThreadCrashActivity`，单次、零重试，43.729 s 后 `LAUNCH_GATE_FAILED` | CAS 通用启动 readiness；telemetry 不足，不下分到具体 broker/window owner |
| `KI-R03-055` 商业添加失败 | 当前已确认 | 动态发现红果、番茄后，各执行一次 `import-launch`；均在 catalog success 前抛 native ELF ABI mismatch | CAS 通用导入兼容性；没有 CAS success 后 SX/UI 抛错证据，禁止转交 SX |
| `KI-R03-056` 隐藏重试 | 当前源码与受控失败均已确认 | 原 runner 路径 lines 1550-1558 有二次 launch；C4-R01 以不存在组件单次失败，`attempt=1/retryable=false/budget=0` | C4 acceptance orchestration；R04 移除/重写，R01 不改生产行为 |

当前受控 ANR fixture 是反例：它在 33.360 秒返回 `LAUNCH_PASS`，且快照中 Stub 已绘制，因此未被伪造为
启动失败或黑屏。`LAUNCH_PASS` 超过 30 秒 cold SLO 仍提示 R03/R04 必须把 first-frame 与 SLO 纳入终态。

## 5. 统一 request/operation ID 时间线

本任务在 harness 边界为每个 operation 生成 `request_id` 和 `operation_id`，并把 attempt、retryable、
retry budget 与 snapshot 时间写入 case JSON。关键 request 如下：

| operation | request ID | attempt / budget | 终态 |
|---|---|---|---|
| Quark positive control | `ee46e94285634681b510dbd4edbcbac8` | 1 / 0 | PASS |
| Hongguo first add | `f3e4cbc337924e2bb95a6f7a2dad3f9c` | 1 / 0 | CAS import FAIL |
| Fanqie first add | `2849f6c7253440e7a5efe012c87475b2` | 1 / 0 | CAS import FAIL |
| hidden-retry negative | `1c76b36b4c6a47a684b3ca5ba6c1af46` | 1 / 0 | component-state FAIL |
| launch readiness failure | `dd469ef8ad7b40048dcd72c349cc0515` | 1 / 0 | LAUNCH_GATE_FAILED |

已确认的可关联段只有 `UI_COMMAND_ENQUEUED`、debug command result 和 device snapshot。import、catalog
commit、broker bind、Guest prepare/attach、Activity create/resume、window add、first draw 目前没有共同 ID；
摘要把该段写为 `UNAVAILABLE_AS_SINGLE_CORRELATED_TIMELINE`，而不是伪造时间。

## 6. CAS 与 SX/UI owner 边界

CAS 导入边界包括 APK snapshot/copy/hash/parse、split/native 校验、revision publish、catalog commit 和
instance 创建。当前 native mismatch 由
`app/src/main/java/com/warden/controlledsandbox/ApkImportManager.java:395` 直接抛出，早于可验证的 catalog
success，因此红果、番茄 owner 已确认是 CAS。

SX adapter/UI 只有在 CAS 已返回可验证的 install/import success 后，`SxSandboxAdapter` 或 UI 随后抛错、
误报或丢失状态时才成为 owner。当前没有这类证据。启动 readiness 的 request 只到 debug command 边界，
无法安全拆到某一个 CAS 内部类，故保持 CAS 通用问题。

## 7. VA/NBB 参考实现映射

以下文件在任何修复设计前已完整查阅。SHA-256 用于固定本次映射输入，参考源码只提取状态机和边界，
不直接复制代码。

### 7.1 NewBlackBox

| 合同 | 文件与 SHA-256 | 关键位置/结论 |
|---|---|---|
| install | `BPackageManagerService.java` `9b3ba72b...071cad9`；`BPackageInstallerService.java` `6c3b175a...5cae0` | install 由 PM 锁保护，installer executor 分阶段执行，再更新 package settings |
| start | `BlackBoxCore.java` `41566905...ca0875`；`BActivityManagerService.java` `70c33877...dc0fd`；`ActivityStack.java` `bad3643c...a6b41f` | launch intent 经 AMS/ActivityStack，先确定 process，再生成 Host Stub intent，交 Android framework 启动 |
| process | `BProcessManagerService.java` `7cfb3c53...66f98`；`BActivityThread.java` `12cf3899...40f5` | process slot/identity 是权威记录；attach binder 有 death recipient；Application bind 先于 Activity |
| context/window | `ContextCompat.java` `48bb6360...96298`；`IWindowManagerProxy.java` `3d85a74e...164a1c`；`IWindowSessionProxy.java` `d4763935...78b8` | Context op-package/attribution 与 WMS LayoutParams 使用 Host identity；修正边界后由正常 ActivityThread 发布窗口 |

### 7.2 VirtualApp

| 合同 | 文件与 SHA-256 | 关键位置/结论 |
|---|---|---|
| install | `VAppManagerService.java` `442ec666...ae319` | `installPackage` synchronized；parse、copy/odex、settings/cache 与通知形成明确安装状态机 |
| start | `VActivityManager.java` `db04a477...62746`；`VActivityManagerService.java` `5c8539cb...9204`；`ActivityStack.java` `93dba3e7...24e73` | client → AMS → ActivityStack；先解析/启动 process，再以 Host Stub intent 进入 framework |
| process | `VActivityManagerService.java` lines 711-750；`ActivityStack.java:617` | attach binder death 与 processDied 清理活动栈；process lifecycle 是显式状态，而非盲 sleep |
| activity/window | `StubActivity.java` `dd88cc4f...b6f5c`；`HCallbackStub.java` `f72d1757...b23fab`；`AppInstrumentation.java` `e91a4e30...d5a82`；`WindowManagerStub.java` `d6b3bbb7...ee14c`；`WindowSessionPatch.java` `4e7e4da4...745e5` | Stub/transaction/instrumentation 恢复 Guest Activity，WindowManager/Session 在 Binder 边界替换 Host identity，最终仍走 framework addView/draw |

### 7.3 采纳与不采纳

采纳到后续设计的参考合同：

- install 必须有单飞/同步边界、分阶段状态、原子 publish 与失败回滚；catalog 只在校验成功后提交。
- start 必须显式记录 route、process start/attach、Activity transaction、window 与 first draw，且每阶段有稳定
  终态和 deadline。
- process binder death 必须驱动 generation/route/task 清理，不能靠重复 stop 或 sleep 猜测收敛。
- Context op-package、WindowSession Binder、LayoutParams package 与 Host UID 必须形成最小 Host window
  identity capability，再交正常 ActivityThread 发布窗口。

不采纳：

- 不直接复制 VA/NBB 源码或其全局单例/旧 Android 私有 API 假设。
- 不采用无分类重复 launch、固定 sleep、扩大总 deadline 或捕获所有 Throwable 后反复 addView。
- 不暴露 raw Host Context 给 Guest；只允许最小 window identity capability。
- 不把 `LAUNCH_PASS`、Stub 存在或 Activity marker 当作 first-frame success。

后续对应测试：C4-R02 覆盖 import stage/rollback/并发与商业样本矩阵；C4-R03 覆盖 bind→draw readiness、
Window identity、process death；C4-R04 覆盖 fail-closed、retry decision 和 failure injection；C4-R05 执行
正式 RD 关闭门。

## 8. 指定提交审查

### `6e1044b013fab19a53dd4ceab75230963c4dd83f`

该提交改 12 个文件，`+548/-34`，同时涉及 Audio、WindowManager、Activity field/route、GuestContext、
测试与 runner。确认它修补了首 resume/window identity 的一部分，并引用 NBB Context/WindowSession 合同；
但它还引入 `ensureWindowPublishedAfterResume` 的 broad catch/post-resume addView 重试，多个假设面缺少独立
A/B 证据，设备证据只有 Quark，且没有同 commit 的新 C4 summary。结论：可作为部分修复历史，不能关闭 C4。

### `1bef0951218ec8356f94c869ae9131ad5859864e`

该提交只给进度账本增加 9 行说明，没有重开 C4、作废旧 C4 evidence、记录新 APK/device/raw evidence、
提交机器可读 summary 或验证 DingTalk/SX。结论：说明性记录，不是完整 C4 验收回执。

## 9. Evidence 索引

- 机器可读摘要：`verification/catch-up/C4-R01/c4-r01-rd-summary.json`。
- 原始当前目录：`artifacts/capability-audit/catch-up-c4-r01/20260824T025555Z`；raw summary
  SHA-256 `f40d0fa6...d35950`。
- 历史黑屏目录：`artifacts/capability-audit/a01-acceptance/20260820T091343Z`；standard transition
  SHA-256 `cba8dd7a...ce9ca`，对应 baseline `1d8b4b0f`。
- 采集器：`tools/capability/run_c4_r01_rd.py`。

原始目录保留全量大文件；tracked summary 固化路径、哈希、签名、request ID 和结论。任何后续结论若与
raw 不一致，以 raw hash 对应文件为准。
