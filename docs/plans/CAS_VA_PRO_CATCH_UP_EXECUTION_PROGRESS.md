# CAS 追平 VA PRO 执行进度

账本版本：2.0
更新时间：2026-09-02 12:00（Asia/Shanghai）
任务书：`docs/plans/CAS_VA_PRO_CATCH_UP_EXECUTION_TASK_BOOK_20260821.md`
任务分支：`feature/t57-r03-va-pro-capability-campaign`
远端：`origin`
当前阶段：`C4`（IN_PROGRESS，R05 的 Host LOW_MEMORY 按宿主机性能策略记录并续接；明确 launch/Guest TimeoutException 按最多 5 次重试续接；其他失败仍 fail-closed）
当前任务：`C4-R05`（IN_PROGRESS）
下一任务：`C4-R05`
最后完成任务：`C4-TEMP-01`

## 1. 使用规则

1. 本文件是唯一任务进度账本，只追加回执和更新任务状态，不在此处改写任务定义。
2. 开始任务前先核对本表、最后回执、Git 历史和远端；一次只能有一个 `IN_PROGRESS`。
3. 完成任务后按任务书第 2.2 节执行两提交和推送协议。
4. `DONE` 表示实现提交、回执提交均已推送并验证；否则只能是 `IN_PROGRESS` 或 `BLOCKED`。
5. 若任务书必须变更，先提交变更理由、影响和迁移规则，再修改任务书版本；不得静默改变验收标准。

## 2. 阶段状态

| 阶段 | 状态 | 阶段门禁 | 完成回执 |
|---|---|---|---|
| C0 事实源与 RD 基线 | DONE | 两轮 RD 一致、事实源无冲突、可跨环境续接 | §5 C0-T04 |
| C1 组件/包/进程 | DONE | 双用户、50 轮与任务书规定压力 | §5 C1-GATE |
| C2 系统服务/F2-F5 | DONE | SX/XH 调用面 L3，P0/P1 无 NOT_PROVEN | §5 C2-T07 |
| C3 Native/ABI/隔离 | IN_PROGRESS | trusted/hostile 闭环，条件项有决策 | §5 C3-T01 |
| C4 SX 迁移 | IN_PROGRESS | C4-R01..R05；真实首帧、添加矩阵和 30 分钟双用户压力；Host LOW_MEMORY 仅记录并续接；明确 launch/Guest TimeoutException 最多 5 次重试；其他失败 fail-closed | §5 C4-R05 timeout continuation（2026-09-02） |
| C5 XH 支持 | NOT_APPLICABLE | 用户决定跳过，不阻塞 C6/C7 | §5 PLAN-20260824-C4-REOPEN |
| C6 API/ABI 矩阵 | PENDING | 声明组合均有 Android Matrix 证据 | - |
| C7 OEM/发布 | PENDING | 商业矩阵与 VA PRO scope 总验收 | - |

## 3. 任务状态

| 任务 ID | 任务名称 | 状态 | 依赖 | 实现提交 | 回执位置 |
|---|---|---|---|---|---|
| C0-T01 | 固化任务续接与证据协议 | DONE | BOOTSTRAP-DOCS | `602da7e65a145e1fa277723bd0a97f2abc473c15` | §5 C0-T01 |
| C0-T02 | 当前 HEAD 可复现构建基线 | DONE | C0-T01 | `beef510b2993a38b010dd8c09a51a497247fd783` | §5 C0-T02 final recovery |
| C0-T03 | MuMu RD 完整基线 | DONE | C0-T02 | `222211efebad3c4adfc66804d32e5c3e60e8f3dd` | §5 C0-T03 |
| C0-T04 | 统一能力事实源与 VA PRO corpus | DONE | C0-T03 | `8bb4470c6054b728f64e83529a22f7e2222f6a7d` | §5 C0-T04 |
| C1-T01 | Activity/Application 与任务栈 | DONE | C0 | `87d96611` | §5 C1-T01 |
| C1-T02 | Service/FGS/Job | DONE | C1-T01 | `ea3f9a322b2a4c0907644aa2160c5d16ed7835c0` | §5 C1-T02 |
| C1-T03 | Broadcast | DONE | C1-T01 | `236ce46b` | §5 C1-T03 |
| C1-T04 | ContentProvider | DONE | C1-T01 | `454c1b4a30cd78ce8eeadbadeca0369c7dcbe99d` | §5 C1-T04 |
| C1-T05 | PendingIntent/Alarm/Notification holder | DONE | C1-T02,C1-T03 | `d50d8d91cb98a1c96524efbe2bd00edbc40dddb5` | §5 C1-T05 |
| C1-T06 | Package 生命周期 | DONE | C1-T04,C1-T05 | `68f28f43b5d35bc4b85a03938b5d7009f9e6f277` | §5 C1-T06 |
| C1-T07 | Process/ABI/Recovery | DONE | C1-T01..T06 | `5d7195cb475108d7c2e864913a18ce6b778a70c2` | §5 C1-T07 |
| C2-T01 | SX/XH 系统服务方法清单 | DONE | C1 | `ebcc31841f805502a0791f245b1df505333dfa04` | §5 C2-T01 |
| C2-T02 | PMS/Permission/AppOps/Attribution | DONE | C2-T01 | `16a9aa38fdf7f9227de06cadf7304033f59a4fa3` | §5 C2-T02 |
| C2-T03 | Location | DONE | C2-T01,C2-T02 | `e327d329dba2feb93665566fa2721f5a2b6ed378` | §5 C2-T03 |
| C2-T04 | Camera1/Camera2 | DONE | C2-T01,C2-T02 | `91cb86b62e2c8dd64b5047aee7b93609093eac36` | §5 C2-T04 |
| C2-T05 | 调度与交互服务 | DONE | C2-T01,C1 | `547ba7ae` | §5 C2-T05 |
| C2-T06 | 设备/网络/媒体服务 | DONE | C2-T01,C2-T02 | `048ca1b1` | §5 C2-T06 |
| C2-T07 | Biometric 与长尾收敛 | DONE | C2-T02..T06 | `b6d9dafa` | §5 C2-T07 |
| C3-T01 | Native 绕过与兼容 corpus | DONE | C1,C2-T01 | `8b4233623a0e09028969a88370a68f3ae0137c54` | §5 C3-T01 |
| C3-T02 | 文件/proc/network/FD | DONE | C3-T01 | `566fadc60437a22c36fc985bbf54dce77c177173` | §5 C3-T02 |
| C3-T03 | 四 ABI/16KB/native media | DONE | C3-T01,C2-T04 | `372fc11a9548184e568de213c4c8264f7bf39771` | §5 C3-T03 |
| C3-T04 | Hostile native 隔离 | DONE | C3-T01,C3-T02 | `22716fbfec845b288ea119c6ec6be678fc23915f` | §5 C3-T04 |
| C3-T05 | seccomp/user-notify 决策 | NOT_APPLICABLE | C3-T04 | `537c20211300c93ae42dda4365bcb0cdb0ee0b70` | §5 C3-T05 |
| C3-T06 | ART/Xposed Extension 决策 | NOT_APPLICABLE | C2,C3-T04 | `1931baa5ebf5fb3470b9881230cb4fbdcb0ca3b3` | §5 C3-T06 |
| C4-T01 | SX 依赖与功能冻结 | DONE | C1,C2,C3-T01..T04 | `4dcce11e08bdc6edafbf867032a0790f0ef8ee57` | §5 C4-T01 |
| C4-T02 | SX CAS SDK adapter | DONE | C4-T01 | `d6763e40f971fa60db015b17b294cea15fdcdc32` | §5 C4-T02 |
| C4-T03 | SX 数据迁移 | DONE | C4-T01,C4-T02 | `e064e2174854c661248e0c2970b8fe621bc161ef` | §5 C4-T03 |
| C4-T04 | 移除 BlackBox/Pine/Xposed runtime | DONE | C4-T02,C4-T03 | `525f3aec84ae1ff09192f11a417adf51464f965e` | §5 C4-T04 |
| C4-T05 | SX F1-F5/DingTalk/长稳 | DONE | C4-T04 | `0e34f37535aec5d3dd93cdf9bc2463c61639310b` | §5 C4-T05 |
| C4-R01 | 证据纠偏、复现与 VA/NBB 映射 | DONE | C4-T05 | `d2f1b0aa7137195661525c442e290bd6e009646c` | §5 C4-R01 |
| C4-R02 | 添加事务、超时与 UI 状态机 | DONE | C4-R01 | `46eed7be60a83f5b5adfe865a8c4b0d37e0a63a1` | §5 C4-R02 |
| C4-R03 | 启动 readiness 与窗口合同 | DONE | C4-R01 | `d8797c89` | §5 C4-R03（用户批准残余风险豁免） |
| C4-R04 | C4 fail-closed 验收编排 | DONE | C4-R02,C4-R03 | `1d9b83d54c13d2a758752281dbc492859d8bd05d` | §5 C4-R04 |
| C4-TEMP-01 | CAS 导入/克隆/添加/启动耗时根因与修复 | DONE | C4-R04；完成后解除 C4-R05 临时阻断 | `7c0c819a58513f89e91ec0fb44cdc05a151e2c32` | §5 C4-TEMP-01 修复完成（2026-08-30） |
| C4-R05 | MuMu RD 正式重验与关门 | IN_PROGRESS | C4-R04,C4-TEMP-01 | `9cb1bc3f04365564761d3689ee0b6782a475d8f3`（timeout continuation policy） | §5 C4-R05 timeout continuation（2026-09-02） |
| C5-T01 | 原始 XH 产品能力契约 | NOT_APPLICABLE | C2,C3 | `a8f24e40` | §5 PLAN-20260824-C4-REOPEN |
| C5-T02 | XH CAS Host/SDK 集成 | NOT_APPLICABLE | C5-T01,C4-T02 | `a8f24e40` | §5 PLAN-20260824-C4-REOPEN |
| C5-T03 | 原始 XH/DingTalk 验收 | NOT_APPLICABLE | C5-T02,C4 | `a8f24e40` | §5 PLAN-20260824-C4-REOPEN |
| C5-T04 | 可选 Xposed 模块验收 | NOT_APPLICABLE | C3-T06,C5-T01 | `a8f24e40` | §5 PLAN-20260824-C4-REOPEN |
| C6-T01 | API33-37 回归 | PENDING | C4-R05 | - | - |
| C6-T02 | ARM/跨宽度/16KB | PENDING | C3-T03,C6-T01 | - | - |
| C6-T03 | Android Matrix 发布门禁 | PENDING | C6-T01,C6-T02 | - | - |
| C7-T01 | OEM 优先级与代表设备 | PENDING | C6 | - | - |
| C7-T02 | 逐厂商通用/SX 适配 | PENDING | C7-T01 | - | - |
| C7-T03 | VA PRO scope 与商业发布总验收 | PENDING | C7-T02 | - | - |

## 4. 阻断项

当前 C4 阶段已有记录项：`KI-R03-053`、`KI-R03-054`、`KI-R03-057`、`KI-R03-058`、`KI-R03-059`、`KI-R03-061`、`KI-R03-062`、
`KI-R03-063`、`KI-R03-064`、`KI-R03-065`、`KI-R03-066`、`KI-R03-069`；其中
`KI-R03-069` 为宿主机性能导致的非阻断 `LOW_MEMORY` 记录，不因事件次数阻断本次矩阵。
`KI-R03-060` 为已接受但仍开放的强制回归项。原 `aapt2` 供应链缺口已按官方 Google Maven 字节比对修复，严格 Gradle 与
M5-T19.1-U 供应链门均通过；`KI-R03-BUILD-001` 与 `KI-R03-BUILD-002` 均已 `FIXED`，
两者 `blocks_current_campaign: false`。C0-T02 的锁定构建已连续两轮成功并完成哈希一致性核验。
外部设备、ARM/16KB 环境和可选 ART/Xposed 产品决策在对应任务中确认。当前主线为 C4-R01..R05；
C5 已由用户明确排除；`KI-R03-057`、`KI-R03-058`、`KI-R03-059`、`KI-R03-060` 的未闭合矩阵/启动证据继续保留。
按用户 2026-08-25 明确指令，C4-R03 以“行政 DONE、残余风险接受、Issue 后续回归”的例外方式关账并推进到
C4-R04；这不表示 500/500 正式首试门禁已通过，也不表示 C4 阶段已关闭。`KI-R03-060` 保持开放，后续回归为强制项。

对 `KI-R03-059` 当前 formal occurrence，明确 launch/Guest `TimeoutException` 按用户批准的
最多 5 次显式重试策略执行；只有预算耗尽或出现非该类型失败才形成当前阻断。

## 5. 任务回执

### BOOTSTRAP-DOCS：制定追赶计划执行任务书

- **状态**：DONE
- **开始/结束时间**：2026-08-21 / 2026-08-21（Asia/Shanghai）
- **执行环境**：Windows PowerShell；仓库 `D:\github\controlled-android-sandbox`；本任务未执行 Android 设备测试
- **开始基线**：`feature/t57-r03-va-pro-capability-campaign` @ `75369847`
- **实现摘要**：创建执行任务书和独立进度账本，将 C0-C7 拆分为可独立验收、提交、推送和续接的任务。
- **变更文件**：任务书、进度账本，以及本轮形成的两份差距分析文档。
- **验收命令与结果**：39 个任务 ID 唯一且与进度表完全一致；39 个任务均包含任务目标、执行方案、验收标准、
  任务回执；代码围栏成对；引用的核心脚本与治理文件均存在；格式修正后 `git diff --check` 通过。
- **设备证据**：不适用；设备基线从 `C0-T03` 开始采集。
- **Known Issues**：无新增 runtime issue；首次提交后发现 Markdown 行尾空格，以独立格式提交修正，未改写历史。
- **偏离任务书**：BOOTSTRAP 是任务书生效前的文档任务，使用实现提交加格式修正提交，再使用独立回执提交。
- **实现提交 SHA**：`d82ff91ed6cbdf1e6a48b8b46588e039ef5826a3`
- **补充格式提交 SHA**：`13c58005b2e10566dcb8d7ec9decc0e6cef3d2f5`
- **推送与远端验证**：已推送到 `origin/feature/t57-r03-va-pro-capability-campaign`；实现与格式提交推送后，
  `git ls-remote` 显示远端 HEAD 为 `13c58005b2e10566dcb8d7ec9decc0e6cef3d2f5`，与本地一致。
- **遗留风险**：任务尚未进入设备执行；ARM/16KB 环境及 ART/Xposed 产品决策在对应阶段处理。
- **下一任务**：`C0-T01`

### C0-T01：固化任务续接与证据协议

- **状态**：DONE
- **开始/结束时间**：2026-08-21 11:46 / 2026-08-21 12:00（Asia/Shanghai）
- **执行环境**：Windows PowerShell；仓库 `D:\github\controlled-android-sandbox`；Python 3；
  本地 Git 身份 `OpenAI <openai@users.noreply.github.com>`；MuMu `RD测试` API 32；ABI
  `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`
- **开始基线**：`feature/t57-r03-va-pro-capability-campaign` @ `36513fec4984a277324353974d454bc99abc71ef`；
  工作区干净；上一任务回执 `BOOTSTRAP-DOCS`，实现提交 `d82ff91e`、补充格式提交 `13c58005`、
  回执提交 `36513fec`
- **实现摘要**：新增仓库级 fail-closed 续接校验器和单测，能够定位当前/下一任务、依赖、最后回执、
  基线提交、证据路径、分支/远端 HEAD、canonical Git identity、动态 `RD测试` 和 evidence directory；
  将 `FIXED` 纳入 Known Issues 状态枚举；移除 Quark RD 稳定性脚本的历史 serial 默认值并接入共享动态解析器。
- **变更文件**：`scripts/verify-catch-up-continuation.py`、`scripts/test_catch_up_continuation.py`、
  `tools/capability/campaign_status.py`、`tools/device/t57_quark_stability_probe.ps1`、
  `docs/review/T57_R03_C0_T01_CONTINUATION_PROTOCOL.md`、
  `verification/catch-up/C0-T01/continuation-preflight.json`、
  `verification/catch-up/C0-T01/continuation-preflight-second-session.json`。
- **验收命令与结果**：
  `python scripts/mumu_instance.py --instance-name RD测试` PASS；PowerShell AST parse PASS；
  `python scripts/test_catch_up_continuation.py` PASS（4 tests）；
  `python tools/capability/validate_campaign_infra.py` PASS；
  `python tools/capability/test_campaign_infra.py` PASS（9 tests）；第二 PowerShell 会话运行
  `python scripts/verify-catch-up-continuation.py` PASS；`git diff --check` PASS。
- **设备证据**：动态解析 `RD测试` 得到本次会话 serial `127.0.0.1:16416`，不是脚本常量；model
  `22041211A`；API 32；boot ID `7cac15ce-d76e-44ea-968b-959d91d03be7`；证据 JSON 位于
  `verification/catch-up/C0-T01/`。本任务未宣称任何 Android runtime/VA PRO 兼容性 PASS。
- **Known Issues**：无新增 runtime issue；修复治理 validator 对既有 `KI-R03-NATIVE-010` 状态
  `FIXED` 的枚举漂移；移除一个设备脚本的历史 endpoint 默认值。
- **偏离任务书**：无。按要求先 DISCOVER/CLASSIFY，再 DESIGN/IMPLEMENT/LOCAL_VERIFY；本任务仅
  修改续接治理、校验和设备入口，不提前执行 C0-T02/C0-T03。
- **实现提交 SHA**：`602da7e65a145e1fa277723bd0a97f2abc473c15`
- **回执提交**：通过提交主题 `docs(progress): record [C0-T01] receipt` 在 Git 历史定位。
- **推送目标与远端验证结果**：目标 `origin/feature/t57-r03-va-pro-capability-campaign`；实现提交已在
  回执提交前保留为当前父提交；回执提交完成后按任务书执行非强制推送，并以
  `git ls-remote --heads origin feature/t57-r03-va-pro-capability-campaign` 与本地 `HEAD` 对比。
- **遗留风险**：C0-T02 可复现构建、C0-T03 双轮 RD 完整基线和后续 API/OEM/业务范围仍未验证；
  allowlisted historical serial 仅存在于负向静态 guard，不作为设备选择依据。
- **下一任务**：`C0-T02`

### C0-T02：当前 HEAD 可复现构建基线

- **状态**：BLOCKED
- **开始/结束时间**：2026-08-21 12:05 / 2026-08-21 12:06（Asia/Shanghai）
- **执行环境**：Windows 11 amd64；PowerShell；JDK 17.0.18（Zulu）；Android Gradle Plugin 8.11.1；
  compile SDK 36；target SDK 35；Build Tools 35.0.0；NDK 27.2.12479018；CMake 3.22.1；Gradle 8.13
- **开始基线**：`feature/t57-r03-va-pro-capability-campaign` @
  `78d0a96e322a46912b061e53e750aa3a338de37e`；开始前工作区干净；上一任务回执 `C0-T01`，实现提交
  `602da7e65a145e1fa277723bd0a97f2abc473c15`，回执提交 `78d0a96e322a46912b061e53e750aa3a338de37e`
- **实现摘要**：完成工具链、wrapper、Gradle 锁定依赖和供应链身份前检；执行锁定的离线设备测试 APK 构建；
  在 `:sandbox-contract:compileDebugLibraryResources` 因 `aapt2:8.11.1-12782657` POM 缺少严格验证 metadata
  而失败；未产生 APK，未修改生产运行时代码；新增 `KI-R03-BUILD-001` 并记录恢复条件。
- **变更文件**：`verification/catch-up/C0-T02/build-baseline-blocked.md`；
  `docs/review/KNOWN_ISSUES.yaml`；`docs/plans/CAS_VA_PRO_CATCH_UP_EXECUTION_PROGRESS.md`
- **验收命令与结果**：
  `python scripts/check-build-environment.py --android` PASS；
  `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-wrapper-bootstrap.ps1` PASS；
  `python tools/gradle_lock_state.py verify --require-clean` PASS（48 files, 0 coordinates）；
  `python scripts/check-m5-t19-1-u-supply-chain-governance.py` PASS；
  `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-device-test-apks.ps1` BLOCKED，
  失败详情与 Gradle report 路径见 `verification/catch-up/C0-T02/build-baseline-blocked.md`；治理 validator 与
  9 个 campaign infra tests PASS；未按失败停止规则重试构建。
- **设备证据**：不适用；本任务未运行 MuMu；Host、fixture、Companion32 APK 均未生成，因此无 APK SHA-256；
  构建报告位于 `build/reports/dependency-verification/at-1787285099295/dependency-verification-report.html`
- **Known Issues**：新增 `KI-R03-BUILD-001`（`TEST_EVIDENCE_GAP`，构建供应链 metadata 缺口）；无关闭项。
- **偏离任务书**：按任务书失败即记录 `BLOCKED` 并停止；未绕过依赖验证、未修改 `gradle/verification-metadata.xml`、
  未执行第二轮构建。
- **实现提交 SHA**：`d0800113e5679dfebae97627e646e5cc88cfd6b8`
- **回执提交**：通过提交主题 `docs(progress): record [C0-T02] receipt` 在 Git 历史定位。
- **推送目标与远端验证**：目标 `origin/feature/t57-r03-va-pro-capability-campaign`；实现提交与本回执提交均须
  非强制推送；完成后以 `git ls-remote --heads origin feature/t57-r03-va-pro-capability-campaign` 对比本地 `HEAD`；
  构建阻断不改变推送和回执保全要求。
- **遗留风险**：未取得三枚设备测试 APK 及其 hash；无法建立 C0-T02 的可复现构建基线；严格依赖验证缺口未解决。
- **下一任务**：仍为 `C0-T02`；满足恢复条件后重新执行，不得进入 `C0-T03`。

### C0-T02 recovery：修复 aapt2 校验后重新验收

- **状态**：BLOCKED
- **开始/结束时间**：2026-08-21 12:36 / 2026-08-21 12:42（Asia/Shanghai）
- **执行环境**：Windows 11 amd64；PowerShell；JDK 17.0.18（Zulu）；Android Gradle Plugin 8.11.1；
  compile SDK 36；target SDK 35；Build Tools 35.0.0；NDK 27.2.12479018；CMake 3.22.1；Gradle 8.13
- **开始基线**：`feature/t57-r03-va-pro-capability-campaign` @
  `90ddc49a9a4e62cdbe238eb7c55436714d8044a8`；恢复前工作区干净；上一回执为 C0-T02 BLOCKED，
  实现提交 `d0800113e5679dfebae97627e646e5cc88cfd6b8`，回执提交 `90ddc49a9a4e62cdbe238eb7c55436714d8044a8`
- **实现摘要**：从官方 Google Maven 获取并逐字节比对 `aapt2:8.11.1-12782657` POM/JAR/签名文件与缓存；
  补充 `gradle/verification-metadata.xml` 的精确 SHA-256，加入 reviewed coordinate 和 provenance；
  原始依赖阻断关闭。第 1 轮完整构建进入 Gradle check/lint 后因 43 个错误、30 个警告失败；新增
  `KI-R03-BUILD-002`，未执行第 2 轮。
- **变更文件**：`gradle/verification-metadata.xml`；`gradle/reviewed-dependency-coordinates.json`；
  `gradle/dependency-verification-provenance.json`；`docs/review/KNOWN_ISSUES.yaml`；
  `verification/catch-up/C0-T02/build-baseline-blocked.md`；`docs/plans/CAS_VA_PRO_CATCH_UP_EXECUTION_PROGRESS.md`
- **验收命令与结果**：Android 环境锁定 PASS；wrapper checksum PASS；Gradle lock state PASS（48 files, 0 coordinates）；
  `python scripts/check-m5-t19-1-u-supply-chain-governance.py` PASS；严格离线 Gradle `help` PASS；
  `python tools/capability/validate_campaign_infra.py` PASS；9 个 campaign infra tests PASS；
  `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-device-test-apks.ps1` BLOCKED，
  lint 报告见 `fixture-compat32/build/reports/lint-results-debug.txt`，摘要和原始日志位置见
  `verification/catch-up/C0-T02/build-baseline-blocked.md`。
- **设备证据**：不适用；未运行 MuMu；构建在 APK assemble 前的 check/lint 阶段失败，Host、fixture、
  Companion32 APK 均未生成，无 APK SHA-256；未执行第二轮。
- **Known Issues**：关闭 `KI-R03-BUILD-001`；新增 `KI-R03-BUILD-002`（`CURRENT_DEFECT`，fixture lint/source
  阻断）；无 runtime issue 被标记为 PASS。
- **偏离任务书**：按失败即停止规则停止；未执行第二轮、未跳过 lint、未使用依赖验证绕过；本地无 `gpg`，未虚称
  独立 GPG 签名验证通过，依赖官方字节比对与 Gradle 严格验证门。
- **实现提交 SHA**：`b933d1a0ea45ecea8554302c1718dab91eca0161`
- **回执提交**：通过提交主题 `docs(progress): record [C0-T02] receipt` 在 Git 历史定位。
- **推送目标与远端验证**：目标 `origin/feature/t57-r03-va-pro-capability-campaign`；实现提交与本回执提交均须
  非强制推送；完成后以 `git ls-remote --heads origin feature/t57-r03-va-pro-capability-campaign` 对比本地 `HEAD`。
- **遗留风险**：三枚 APK 尚未生成；fixture lint 的权限、API、常量和私有 API 问题未修复/复验；C0-T02 仍未建立
  可复现构建基线。
- **下一任务**：仍为 `C0-T02`；修复并分类 `KI-R03-BUILD-002` 后重新执行，不得进入 `C0-T03`。

### C0-T02 final recovery：当前 HEAD 可复现构建基线

- **状态**：DONE
- **开始/结束时间**：2026-08-21 / 2026-08-21（Asia/Shanghai）
- **执行环境**：Windows 11 amd64；PowerShell；JDK 17.0.18（Zulu）；Android Gradle Plugin 8.11.1；
  compile SDK 36；target SDK 35；Build Tools 35.0.0；NDK 27.2.12479018；CMake 3.22.1；Gradle 8.13。
- **开始基线**：`feature/t57-r03-va-pro-capability-campaign` @
  `b35ca0feccf6c2d150766d8b9a740680d6f30057`；恢复前工作区干净；上一回执为 C0-T02 recovery，
  实现提交 `b933d1a0ea45ecea8554302c1718dab91eca0161`，回执提交 `b35ca0feccf6c2d150766d8b9a740680d6f30057`。
- **实现摘要**：按 DISCOVER/CLASSIFY 结果修复 package-neutral fixture、framework、runtime 和 app
  的 lint 阻断；使用显式 API level guard、官方常量、精确方法级权限注解、目标私有 API 注解和可选
  camera feature 声明；将可选 `ApplicationInfo.appComponentFactory` 访问改为受控兼容读取；未使用
  blanket suppression、lint bypass 或运行时缺陷改名。
- **变更文件**：fixture-basic 探针与 manifest；`sandbox-framework` 服务代理；`sandbox-runtime`
  Guest 兼容读取、classloader、component/connection bridge；app debug/native campaign 与 manifest；
  `docs/review/KNOWN_ISSUES.yaml`；`verification/catch-up/C0-T02/build-baseline-blocked.md`；
  `.gitignore`（忽略本地 M5 device-test APK 输出）。
- **验收命令与结果**：构建环境、wrapper、Gradle lock、M5-T19.1-U supply-chain、campaign validator
  和 9 个 campaign infra tests 全部 PASS；fixture、framework、runtime、app 的 targeted lint 全部
  `BUILD SUCCESSFUL`；锁定命令
  `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-device-test-apks.ps1`
  连续两轮 PASS，均生成 Host/fixture/Companion32 三枚 APK（3710513B / 1766894B / 42218435B）；
  两轮 SHA-256 逐项一致：Host
  `e6c565f7f9349901f5ac91fc234a052e86c6409d1d7eeaa2e1695c33b8fdeb9d`，fixture
  `af85225a53002ce43084b5a32db5a17193be75c7bec0d2477780eb44702fb169`，Companion32
  `cdb690449ee858954625a24f2683e15208dd7727bed9cb13a55bb82b61712483`；manifest applicationId、
  signature、ABI 和 native library inventory 校验 PASS；`git diff --check` PASS。
- **设备证据**：本任务不运行 MuMu；APK 构建产物和 SHA-256 位于本地
  `artifacts/m5-device-test-build/b35ca0feccf6/`，清单为 `build-manifest.json`，摘要为
  `SHA256SUMS.txt`。不宣称 Android runtime、RD、ARM/16KB 或业务 PASS。
- **Known Issues**：`KI-R03-BUILD-001`、`KI-R03-BUILD-002` 均更新为 `FIXED`，且
  `blocks_current_campaign: false`；历史 BLOCKED 回执和失败日志保留用于审计。
- **偏离任务书**：无。恢复按原失败分类继续；修复后完成两轮锁定构建和确定性哈希核验；未进入 C0-T03。
- **实现提交 SHA**：`beef510b2993a38b010dd8c09a51a497247fd783`
- **回执提交**：使用主题 `docs(progress): record [C0-T02] receipt` 单独提交；实现提交与回执提交均
  按任务书要求非强制推送到 `origin/feature/t57-r03-va-pro-capability-campaign`，并用
  `git ls-remote --heads origin feature/t57-r03-va-pro-capability-campaign` 校验远端 HEAD。
- **遗留风险**：C0-T03 仍需在动态解析的 MuMu `RD测试` 上执行双轮完整基线；ARM/16KB、设备 runtime、
  SX/XH 业务和后续 API/OEM 组合尚未验证。
- **下一任务**：`C0-T03`；本轮不执行。

### C0-T03：MuMu RD 完整基线

- **状态**：DONE
- **开始/结束时间**：2026-08-21 13:50 / 2026-08-21 14:35（Asia/Shanghai）
- **执行环境**：Windows 11 amd64；PowerShell；JDK 17.0.18（Zulu）；Android Gradle Plugin 8.11.1；
  compile SDK 36；target SDK 35；Build Tools 35.0.0；NDK 27.2.12479018；CMake 3.22.1；Gradle 8.13；
  MuMu 实例通过 `python scripts/mumu_instance.py --instance-name 'RD测试'` 动态解析。
- **开始基线**：`feature/t57-r03-va-pro-capability-campaign` @
  `25ee6ba5afced5abf1c991aa94d3bc559d03022f`；开始前工作区干净，远端分支同 HEAD；上一回执为
  C0-T02 final recovery，上一实现提交 `beef510b2993a38b010dd8c09a51a497247fd783`，上一回执提交
  `b47eb12ea9cc7c5566621986b99cc6fa38edab98`。
- **实现摘要**：完成 MuMu `RD测试` 的 RD_BASELINE 双轮完整回归；补建任务要求的
  `fixture-compat32` debug APK；修复 transport probe 未订阅 `CS_CROSS_PACKAGE_ROUTE` 与
  `CS_CROSS_ABI_ROUTE` logcat tag 的 harness 缺口；保留失败原始日志、分类说明、设备快照、APK
  SHA-256 和 Git bundle 证据。
- **变更文件**：`tools/device/t57_rd_framework_transport_probe.ps1`；
  `verification/catch-up/C0-T01/continuation-preflight.json`；
  `verification/catch-up/C0-T03/`（证据索引、两轮完整回归、针对性重跑、失败原始日志、Git bundle、
  acceptance-evidence）。
- **验收命令与结果**：`python scripts/verify-catch-up-continuation.py` PASS；
  `python tools/capability/run_local_capability_audit.py --all` 按治理要求 collect-all 并分类后返回
  非零，属于 diagnostic-only，不作为本任务 runtime gate；锁定命令
  `gradlew.bat --no-daemon --no-build-cache --no-parallel --stacktrace --offline :fixture-compat32:assembleDebug`
  PASS；transport probe PowerShell AST parse PASS；针对性 transport 与 cross-ABI recovery PASS；
  `tools/device/t57_rd_full_regression.ps1` 在同一设备快照连续两轮均 PASS，9/9 case 分类一致；
  `scripts/capture-acceptance-evidence.ps1` PASS；`git diff --check` PASS。
- **设备证据**：MuMu `RD测试`，动态 serial `127.0.0.1:16416`，model `22041211A`，API 32 / Android 12，
  boot ID `7cac15ce-d76e-44ea-968b-959d91d03be7`；两轮设备快照位于
  `verification/catch-up/C0-T03/round-1-complete/` 与 `round-2-complete/`；四枚 APK 和 bundle 的
  SHA-256 由 `acceptance-evidence/artifact-hashes.txt` 生成，Git bundle 由
  `acceptance-evidence/bundle-verify.txt` 验证。
- **Known Issues**：未新增或关闭 Known Issue；现有 `KI-T57-015`（RD API32 上完整
  process-death matrix 尚未证明）仍保留；本回执仅声明 RD_BASELINE，不外推 Android Matrix、ARM/16KB、
  OEM、SX/XH 或商业应用等能力。
- **偏离任务书**：无验收范围偏离。执行中按 DISCOVER/CLASSIFY 分类并修复了测试前置 APK 缺口和
  harness logcat tag 缺口；首次 cross-ABI recovery 瞬态失败已保留原始证据并在同一设备快照下针对性
  重跑及两轮完整回归通过；无需人工介入，不记录 BLOCKED。
- **实现提交 SHA**：`222211efebad3c4adfc66804d32e5c3e60e8f3dd`
- **推送与远端验证**：本回执提交使用主题 `docs(progress): record [C0-T03] receipt` 单独提交；实现
  提交与本回执提交均按任务书要求非强制推送到
  `origin/feature/t57-r03-va-pro-capability-campaign`，并以
  `git ls-remote --heads origin feature/t57-r03-va-pro-capability-campaign` 对比最终本地 `HEAD`。
- **遗留风险**：仅完成 MuMu `RD测试` API32 RD_BASELINE；API33+、ARM/跨宽度/16KB、OEM、SX/XH、
  商业应用和完整 process-death matrix 仍待后续任务验证。
- **下一任务**：`C0-T04`；本轮不执行。

### C0-T04：统一能力事实源与 VA PRO corpus

- **状态**：DONE
- **开始/结束时间**：2026-08-21 14:44 / 2026-08-21 15:10（Asia/Shanghai）
- **执行环境**：Windows 11 amd64；PowerShell；JDK 17.0.18（Zulu）；Android Gradle Plugin 8.11.1；
  compile SDK 36；target SDK 35；Build Tools 35.0.0；NDK 27.2.12479018；CMake 3.22.1；Gradle 8.13。
- **开始基线**：`feature/t57-r03-va-pro-capability-campaign` @
  `76d164795294b676a7cd9a2b20bbd829c5cd5ae3`；开始前工作区干净，远端分支同 HEAD；上一回执为
  C0-T03，上一实现提交 `222211efebad3c4adfc66804d32e5c3e60e8f3dd`，上一回执提交
  `76d164795294b676a7cd9a2b20bbd829c5cd5ae3`。
- **实现摘要**：新增 CAS/VA PRO 事实对账器和 C0-T04 证据；将 83 条 corpus 统一分类为
  `IN_SCOPE=51`、`OUT_OF_SCOPE=3`、`NEEDS_FIXTURE=28`、`PROVEN=1`、`DUPLICATE=0`；
  修复 SBOM 对 Gradle `projectDir` 映射的识别；补齐静态编译 harness 的 API stub；修复审计 runner
  对已修复/未声明 Known Issue 的分类逻辑；将 `KI-R03-021` 标记为 `FIXED`。
- **变更文件**：`tools/capability/reconcile_cas_va_pro.py`、
  `verification/catch-up/C0-T04/fact-convergence.json`、
  `verification/catch-up/C0-T04/fact-convergence.md`、
  `docs/capability/CAPABILITY_REGISTRY.yaml`、`docs/review/KNOWN_ISSUES.yaml`、
  `scripts/generate-sbom.py`、`verification/sbom.json`、
  `tools/capability/run_local_capability_audit.py`、`tools/capability/test_campaign_infra.py`、
  `tools/static_android_compile.py`。
- **验收命令与结果**：`python tools/capability/validate_campaign_infra.py` PASS；
  `python tools/capability/test_campaign_infra.py` PASS（12 tests）；
  `python -m unittest tools/capability/test_a01_semantic_runner_gate.py` PASS（18 tests）；
  `python tools/static_android_compile.py` PASS（160 tests）；
  `python scripts/check-pre-device-runtime-hardening.py` PASS；
  `python scripts/generate-sbom.py --check` PASS（14 components）；
  `python tools/capability/reconcile_cas_va_pro.py --audit-summary
  artifacts/capability-audit/all/20260821T070134Z/summary.json` PASS；
  `python tools/capability/run_local_capability_audit.py --all` 按治理要求 collect-all 后返回
  diagnostic-only 非零：42 gates 中 PASS=30、KNOWN_ISSUE=12、NEW_REGRESSION=0、FAIL=12，
  所有 FAIL 均已归类为现有 Known Issues；`python scripts/check-broadcast-model.py` 的失败对应
  `KI-R03-022`，未误改 runtime；`git diff --check` PASS。
- **设备证据**：本任务为事实源/治理对账任务，不新增设备运行声明；沿用 C0-T03 的 MuMu `RD测试`
  证据，动态 serial `127.0.0.1:16416`，model `22041211A`，API 32 / Android 12，boot ID
  `7cac15ce-d76e-44ea-968b-959d91d03be7`；两轮证据位于
  `verification/catch-up/C0-T03/round-1-complete/` 与 `round-2-complete/`。
- **Known Issues**：关闭 `KI-R03-021`（SBOM source digest stale）；未新增 runtime issue；
  `KI-R03-022` 及其余未解决项仍保留，未宣称 API33+、ARM/16KB、OEM、SX/XH 或 VA PRO 等价性。
- **偏离任务书**：无验收范围偏离；执行中按 DISCOVER/CLASSIFY 修复了静态编译 harness、SBOM
  projectDir 映射和 runner 分类缺口，无需人工介入，不记录 BLOCKED；未执行下一任务。
- **实现提交 SHA**：`8bb4470c6054b728f64e83529a22f7e2222f6a7d`
- **推送与远端验证**：本回执提交使用主题 `docs(progress): record [C0-T04] receipt` 单独提交；
  实现提交与本回执提交均按任务书要求非强制推送到
  `origin/feature/t57-r03-va-pro-capability-campaign`，并以
  `git ls-remote --heads origin feature/t57-r03-va-pro-capability-campaign` 对比最终本地 `HEAD`。
- **遗留风险**：C0 已完成 RD_BASELINE 与事实源收敛，但 API33+、ARM/跨宽度/16KB、OEM、SX/XH、
  商业应用和 VA PRO 等价性仍未证明；下一任务按账本为 `C1-T01`。
- **下一任务**：`C1-T01`；本轮不执行。

### C1-T01：Activity/Application 与任务栈语义闭环

- **状态**：DONE
- **开始/结束时间**：2026-08-21 15:11 / 2026-08-21 17:16（Asia/Shanghai）
- **执行环境**：Windows 11 amd64；PowerShell；JDK 17.0.18（Zulu）；Android Gradle Plugin 8.11.1；
  compile SDK 36；target SDK 35；Build Tools 35.0.0；NDK 27.2.12479018；CMake 3.22.1；Gradle 8.13。
  MuMu `RD测试` 通过 `python scripts/mumu_instance.py --instance-name 'RD测试'` 动态解析。
- **开始基线**：分支 `feature/t57-r03-va-pro-capability-campaign` @
  `81f41418e29ad3bd8183fdaa7ecb1a16cf138c33`；开始前工作区干净、远端同 HEAD；上一回执为
  `C0-T04`，实现/回执提交 `8bb4470c6054b728f64e83529a22f7e2222f6a7d`。
- **实现摘要**：DISCOVER/CLASSIFY 将当前缺口归类为 `TEST_EVIDENCE_GAP`；新增 fail-closed 的
  RD_BASELINE Activity/Task 矩阵 runner，动态解析设备并覆盖双虚拟用户、standard/singleTop
  top/non-top/singleTask/CLEAR_TOP/REORDER_TO_FRONT；修复 fixture 的 Back 快照窗口和重复运行时序，
  不改生产 Activity/Task runtime；补充 ActivityResult 与 process-death generation recovery 专项证据。
- **变更文件**：`tools/capability/run_c1_t01_rd.py`、`docs/review/C1_T01_ACTIVITY_TASK_DESIGN.md`、
  `fixture-basic/src/main/java/com/warden/controlledsandbox/fixture/` 下 9 个 task fixture 文件、
  `verification/catch-up/C1-T01/c1-t01-rd-summary.json`、
  `verification/catch-up/C1-T01/c1-t01-supplemental-probes.json`。
- **验收命令与结果**：
  `python tools/capability/run_local_capability_audit.py --all` 收集 42 gates：30 `PASS`、12 已登记
  `KNOWN_ISSUE`、0 `NEW_REGRESSION`；`python scripts/check-activity-task-virtualization.py` PASS；
  `python -m unittest tools/capability/test_a01_semantic_runner_gate.py` PASS（18 tests）；
  `python tools/static_android_compile.py` PASS；四枚 Debug APK assemble PASS；
  `python tools/capability/run_c1_t01_rd.py --loops 50` PASS（user0/user1 各 50 轮，700/700，失败 0）；
  `t57_rd_framework_activity_result_probe.ps1` PASS；`t57_rd_recovery_probe.ps1` PASS；
  receipt JSON、700 个原始 `result.json`、supplemental JSON 和 `git diff --check` 均 PASS。
- **设备证据**：MuMu `RD测试`，动态 serial `127.0.0.1:16416`，model `22041211A`，API 32 / Android 12，
  ABI `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`，boot ID
  `7cac15ce-d76e-44ea-968b-959d91d03be7`；主回执为
  `verification/catch-up/C1-T01/c1-t01-rd-summary.json`，矩阵原始证据为
  `artifacts/capability-audit/catch-up-c1-t01/20260821T074920Z`；专项证据索引为
  `verification/catch-up/C1-T01/c1-t01-supplemental-probes.json`。
- **Known Issues**：本任务未新增 runtime issue；显式 referrer 传播、物理 rotation/configuration change、
  动态 singleInstance 设备路径未单独证明，已在 supplemental receipt 明确记录；现有 API/OEM/VA PRO
  未证明项保持原状态。
- **偏离任务书**：核心 RD 双用户 50 轮和专项 result/death 验收无偏离；为使 transition dump 与真实
  Back 顺序可回溯，fixture 增加事件后 800ms 快照窗口并将 Back settle 限定为 600ms，runner 仍以
  dumpsys、CAS route/token、生命周期和真实 Back 共同 fail-closed 判定；未把未覆盖维度推断为 PASS，
  无需人工介入，不记录 BLOCKED。
- **实现提交 SHA**：`87d96611`（`test(activity): [C1-T01] stabilize RD task evidence matrix`）。
- **推送与远端验证**：回执提交完成后，两个提交将按任务书要求非强制推送到
  `origin/feature/t57-r03-va-pro-capability-campaign`，并以
  `git ls-remote --heads origin feature/t57-r03-va-pro-capability-campaign` 对比本地 `HEAD`。
- **遗留风险**：本任务仅声明 RD API32 `RD_BASELINE`；显式 referrer、物理 rotation/configuration change、
  动态 singleInstance、API33+、ARM/16KB、OEM、商业应用和 VA PRO 等价性仍未证明。
- **下一任务**：`C1-T02`；本轮不执行。

### C1-T02：Service/FGS/Job

- **状态**：DONE
- **开始/结束时间**：2026-08-21 17:17 / 2026-08-21 18:43（Asia/Shanghai）
- **执行环境**：Windows 11 amd64；PowerShell；JDK 17.0.18（Zulu）；Android Gradle Plugin 8.11.1；
  compile SDK 36；target SDK 35；Build Tools 35.0.0；NDK 27.2.12479018；CMake 3.22.1；Gradle 8.13；
  MuMu `RD测试` 通过 `python scripts/mumu_instance.py --instance-name 'RD测试'` 动态解析。
- **开始基线**：分支 `feature/t57-r03-va-pro-capability-campaign` @
  `1d0f351160db665ab91b3cdfa26e3c1f1f39d2b6`；开始前工作区干净、远端同 HEAD；上一回执为
  `C1-T01`，实现提交 `87d96611`，回执提交 `1d0f351160db665ab91b3cdfa26e3c1f1f39d2b6`；本轮只执行
  `C1-T02`。
- **实现摘要**：补齐 Service start/bind/publish/unbind/rebind/sticky 生命周期、启动序号和旧序号
  停止语义、真实框架 FGS promotion/demotion、Binder lease 收敛、Job WorkItem/cancel/reschedule
  与 process-death generation recovery 的 RD 验收路径；修复框架 Service 回调与 broker 成功路径的
  重复计数风险；新增动态 MuMu 设备 runner、设计审查和可追溯证据索引。
- **变更文件**：`app/src/debug/java/com/warden/controlledsandbox/DebugCommandActivity.java`、
  `app/src/main/java/com/warden/controlledsandbox/RuntimeClient.java`、
  `app/src/main/java/com/warden/controlledsandbox/SandboxRecord.java`、
  `fixture-basic/src/main/java/com/warden/controlledsandbox/fixture/FixtureService.java`、
  `sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeComponentOperationCoordinator.java`、
  `scripts/check-notification-job-lifecycle.py`、`docs/review/C1_T02_SERVICE_JOB_DESIGN.md`、
  `tools/capability/run_c1_t02_rd.py`、`verification/catch-up/C1-T02/`，以及更新后的
  `verification/catch-up/C0-T01/continuation-preflight.json`、`verification/m5-t16-source-closure-audit.json`、
  `verification/sbom.json`。
- **验收命令与结果**：
  `python scripts/check-notification-job-lifecycle.py` PASS；
  `python scripts/check-service-lifecycle.py` PASS；
  `python scripts/check-guest-jobservice-bridge.py` PASS；
  `python -m py_compile tools/capability/run_c1_t02_rd.py` PASS；
  `python tools/static_android_compile.py` PASS；
  `python scripts/generate-sbom.py --check` PASS（14 components）；
  `gradlew.bat :app:assembleDebug :fixture-basic:assembleDebug` PASS；
  `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\\build-device-test-apks.ps1` PASS；
  `gradlew.bat :fixture-compat32:assembleDebug` PASS；`git diff --check` 和 JSON evidence parse PASS。
  正式短测 `python tools/capability/run_c1_t02_rd.py --instance 'RD测试' --loops 50
  --batch-iterations 5 --pressure-seconds 1800` PASS：双用户各 15 分钟、总观测
  `1812.74s`，期望 100 cycles、实际 715 cycles，143 batches，failed batches=0；最终设备 APK
  构建摘要中的 host/fixture/companion32/fixture32 哈希见主回执 `final_build`。
  Companion probes `RD-10`、`RD-11`、`RD-07` 均 PASS。
- **设备证据**：MuMu `RD测试`，动态 serial `127.0.0.1:16416`，model `22041211A`，API 32 / Android 12，
  boot ID `7cac15ce-d76e-44ea-968b-959d91d03be7`；主回执为
  `verification/catch-up/C1-T02/c1-t02-rd-summary.json`，专项证据索引为
  `verification/catch-up/C1-T02/c1-t02-companion-probes.json`，原始日志位于
  `artifacts/capability-audit/catch-up-c1-t02/20260821T095942Z`，companion 原始文件位于
  `build/c1-t02-companion/`。
- **Known Issues**：记录 `C1-T02-ISSUE-8H-STABILITY-SOAK`（`FOLLOW_UP_REQUIRED`）。按本轮补充验收标准，
  取消 8 小时稳定性 soak，仅以 30 分钟短测判定本任务 DONE；后续仍需补跑扩展 soak 并检查 leak、
  ghost-task 和 ANR telemetry。本任务仅声明 RD API32 `RD_BASELINE`，不声明 VA PRO 等价性。
- **偏离任务书**：8 小时 soak 为用户在本轮明确的验收标准覆盖；未执行且已记录后续 issue，30 分钟
  短测完成即满足 DONE。其余验收范围无偏离；执行中发现的失败均已修复并重跑通过，无需人工介入，
  不记录 BLOCKED。
- **实现提交 SHA**：`ea3f9a322b2a4c0907644aa2160c5d16ed7835c0`
  （`test(service): [C1-T02] close Service FGS Job lifecycle gates`）。
- **推送与远端验证**：本回执提交使用主题 `docs(progress): record [C1-T02] receipt` 单独提交；实现
  提交与本回执提交均按任务书要求非强制推送到
  `origin/feature/t57-r03-va-pro-capability-campaign`，并以
  `git ls-remote --heads origin feature/t57-r03-va-pro-capability-campaign` 对比最终本地 `HEAD`。
- **遗留风险**：仅完成 MuMu `RD测试` API32 `RD_BASELINE` 与 30 分钟短测；8 小时 soak、API33+、
  ARM/跨宽度/16KB、OEM、SX/XH、商业应用和 VA PRO 等价性仍未证明。
- **下一任务**：`C1-T03`；本轮不执行。

### C1-T03：Broadcast 事件模型闭环

- **状态**：DONE
- **开始/结束时间**：2026-08-21 18:44 / 2026-08-21 19:39（Asia/Shanghai）
- **执行环境**：Windows 11 amd64；PowerShell；JDK 17.0.18（Zulu）；Android Gradle Plugin 8.11.1；
  compile SDK 36；target SDK 35；Build Tools 35.0.0；NDK 27.2.12479018；CMake 3.22.1；Gradle 8.13；
  MuMu `RD测试` 通过动态解析器定位。
- **开始基线**：`feature/t57-r03-va-pro-capability-campaign` @
  `602bdc944408cedcdde5e042e3af2e1ec76d3eb2`；开始前工作区干净、远端同 HEAD；上一回执为
  `C1-T02`，实现提交 `ea3f9a322b2a4c0907644aa2160c5d16ed7835c0`，回执提交
  `602bdc944408cedcdde5e042e3af2e1ec76d3eb2`。
- **实现摘要**：将 Broadcast 架构检查器更新到当前 operation/lifecycle coordinator 边界并同步
  `RuntimeKeys` timeout；新增 package-neutral manifest/dynamic fixture，覆盖 explicit/implicit、
  ordered result/abort、`goAsync`、receiver permission/拒绝、动态注册注销、双虚拟用户和进程停止清理；
  新增 fail-closed RD runner 与设计/证据回执。运行时仅增加动态注销观测事件，未改变广播语义；`KI-R03-022`
  已修复为 `TEST_EVIDENCE_GAP`。
- **变更文件**：`app/src/debug/java/com/warden/controlledsandbox/DebugCommandActivity.java`；
  `fixture-basic/src/main/AndroidManifest.xml` 及 `BroadcastCampaign*.java` 5 个 fixture；
  `sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestDynamicReceiverTransport.java`；
  `scripts/check-broadcast-model.py`；`tools/capability/run_c1_t03_rd.py`；
  `docs/review/C1_T03_BROADCAST_DESIGN.md`；`docs/review/KNOWN_ISSUES.yaml`；
  `verification/catch-up/C1-T03/c1-t03-rd-summary.json`；`verification/m5-t16-source-closure-audit.json`；
  `verification/sbom.json`。
- **验收命令与结果**：
  `python tools/capability/run_local_capability_audit.py --all` 按治理要求 collect-all，42 gates 为
  PASS=30、KNOWN_ISSUE=12、NEW_REGRESSION=0；`python scripts/check-broadcast-model.py` PASS；
  `python scripts/check-m5-t3-broadcast-fgs.py` PASS；`python tools/capability/validate_campaign_infra.py`
  PASS；`python tools/static_android_compile.py` PASS；app/fixture lint 与 assemble PASS；
  `scripts/build-device-test-apks.ps1` PASS。该脚本未包含 `fixture-compat32`，按既有锁定命令补建
  `:fixture-compat32:assembleDebug` PASS。初次 50 轮尝试暴露单次 Host 命令/环形 logcat 证据截断（19/50、
  17/50），已修复 runner 为逐轮即时抓取后重跑；最终
  `python tools/capability/run_c1_t03_rd.py --instance 'RD测试' --loops 50` PASS：user0/user1
  各 50 轮，15 类核心标记与 4 类 ordered result 均精确 50/50；无 runtime FAIL、abort low、async
  finish failure 或 fatal marker。`python scripts/generate-sbom.py --check` 与 `git diff --check` PASS。
- **设备证据**：主回执为 `verification/catch-up/C1-T03/c1-t03-rd-summary.json`；原始逐轮日志与
  debug 回执位于 `artifacts/capability-audit/catch-up-c1-t03/20260821T112654Z/user-0/` 和
  `user-1/`。动态设备为 MuMu `RD测试`，serial `127.0.0.1:16416`，model `22041211A`，API 32 /
  Android 12，ABI `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`，boot ID
  `7cac15ce-d76e-44ea-968b-959d91d03be7`。APK SHA-256：host
  `208916e29d3dca45e08a7ecf4fe038fd548771a1fa38584d6a99c7e0d6095b07`；fixture
  `d5025a8a30acc5fe961531a2b66229b8dae9f5d86c21af1ea0a3f7779024c6ef`；companion32
  `7f4bb7d740040dd840dca4e7488c895eaf838a1dabc2307301bbe5dd3cf94567`；fixture32
  `4f6a0332c15aa09275876a08102712b8af1f89b35275ec65659866ef3e063d7d`。
- **Known Issues**：关闭 `KI-R03-022` 的当前架构检查器漂移记录；未新增 runtime issue。仅声明 MuMu
  API32 `RD_BASELINE`，不外推 API33+、ARM/16KB、OEM、SX/XH、商业应用或 VA PRO 等价性。
- **偏离任务书**：无验收范围偏离；按失败优先修复规则补建缺失的 fixture32 APK，并修复 runner 的
  Host 长命令与 logcat 环形缓冲证据缺口后重跑通过；无需人工介入，不记录 BLOCKED。
- **实现提交 SHA**：`236ce46b2736f4177d6ce14f8ee576218b47a9d1`
  （`test(broadcast): [C1-T03] close RD event model gates`）。
- **回执提交**：通过提交主题 `docs(progress): record [C1-T03] receipt` 在 Git 历史定位。
- **推送与远端验证**：目标 `origin/feature/t57-r03-va-pro-capability-campaign`；实现提交与本回执提交
  均按任务书要求非强制推送，完成后以 `git ls-remote --heads origin
  feature/t57-r03-va-pro-capability-campaign` 对比最终本地 `HEAD`。
- **遗留风险**：本任务仅完成 MuMu `RD测试` API32 `RD_BASELINE`；跨 API、ARM/16KB、OEM、完整 8 小时
  soak、SX/XH、商业应用和 VA PRO 等价性仍待后续任务验证。
- **下一任务**：`C1-T04`；本轮不执行。

### C1-T04：ContentProvider 数据与授权生命周期闭环

- **状态**：DONE
- **开始/结束时间**：2026-08-21 19:40 / 2026-08-21 21:00（Asia/Shanghai）
- **执行环境**：Windows 11 amd64；PowerShell；JDK 17.0.18（Zulu）；Android Gradle Plugin 8.11.1；
  compile SDK 36；target SDK 35；Build Tools 35.0.0；NDK 27.2.12479018；CMake 3.22.1；Gradle 8.13；
  MuMu `RD测试` 由动态解析器定位，未在新增 runner 中固化 ADB endpoint。
- **开始基线**：`feature/t57-r03-va-pro-capability-campaign` @
  `ada061ca695ccd88fe1605f9ae35172b4652a5c0`；开始前工作区干净、远端同 HEAD；上一回执为
  `C1-T03`，实现提交 `236ce46b2736f4177d6ce14f8ee576218b47a9d1`，回执提交
  `ada061ca695ccd88fe1605f9ae35172b4652a5c0`。
- **实现摘要**：新增 package-neutral `ProviderCampaignActivity`，从 Guest 公开
  `ContentResolver` / `Context` API 覆盖 authority/type、128 行 bulk + tail CRUD、cursor 分页与
  requery、applyBatch/call/exception-allowed/back-reference/rollback、FD/asset/typed asset、
  URI grant/revoke、CancellationSignal、跨包 Provider observer 和双虚拟用户 recovery；Debug Host
  增加单用户/双用户 campaign 命令，并在并发启动前准备两个用户的 compat32 peer instance；新增
  动态 `RD测试` runner、设计文档和结构化回执；同步 static Android compile stub、Fixture notifyChange、
  SBOM 与续接证据。
- **变更文件**：`app/src/debug/java/com/warden/controlledsandbox/DebugCommandActivity.java`；
  `fixture-basic/src/main/AndroidManifest.xml`、`FixtureProvider.java`、`ProviderCampaignActivity.java`；
  `tools/capability/run_c1_t04_rd.py`、`tools/static_android_compile.py`；
  `docs/review/C1_T04_PROVIDER_DESIGN.md`、`docs/review/KNOWN_ISSUES.yaml`；
  `verification/catch-up/C0-T01/continuation-preflight.json`、`verification/catch-up/C1-T04/c1-t04-rd-summary.json`、
  `verification/sbom.json`。
- **验收命令与结果**：`python tools/static_android_compile.py` PASS；
  `python scripts/check-pre-device-runtime-hardening.py` PASS（staticTests=160）；
  `python tools/capability/validate_campaign_infra.py` PASS；
  `python scripts/generate-sbom.py --check` PASS；四枚 Debug APK build/lint（
  `scripts/build-device-test-apks.ps1 -NoClean`）PASS；`python tools/capability/run_local_capability_audit.py --all`
  为 PASS=31、KNOWN_ISSUE=11、NEW_REGRESSION=0，11 个 FAIL 均映射既有 Known Issues；
  `python tools/capability/run_c1_t04_rd.py --loops 1 --pressure-seconds 0` PASS；随后以
  `python tools/capability/run_c1_t04_rd.py --loops 50` 启动双用户 campaign，按最新操作者指示在
  1800 秒压力窗口前停止，raw logcat 已记录 116 个完整 pass marker（两用户并发各 58 轮）、10 类
  marker 均为 116，`C1_T04_PROVIDER_FAIL`、runtime fail、fatal、ANR 均为 0。该停止事实已在回执
  `acceptance_note` 中明确记录，未把 1800 秒写成已完成。
- **设备证据**：主回执为 `verification/catch-up/C1-T04/c1-t04-rd-summary.json`；raw logcat 位于
  `artifacts/capability-audit/catch-up-c1-t04/20260821T123725Z/provider-campaign-logcat.txt`；
  动态设备为 MuMu `RD测试`，serial `127.0.0.1:16416`，model `22041211A`，API 32 / Android 12，
  ABI `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`，boot ID
  `7cac15ce-d76e-44ea-968b-959d91d03be7`，Android ID `398eea33120cd887`。APK SHA-256：host
  `f1c8aaa12032e9b7c0faa850088eaba60f856e7b82f3345c58ee9d8dc89083ed`；fixture
  `95a4f6484646a5ab2cb02e1ed9d1e26a7d71431f18aa334c836675c6a4e7b8e2`；companion32
  `7f4bb7d740040dd840dca4e7488c895eaf838a1dabc2307301bbe5dd3cf94567`；fixture32
  `58fb06e5a6db09892f2c671629aa8c088ca9d223cc9c047d19fd4e2e1e258e7b`。
- **Known Issues**：新增 `KI-R03-031`（`TEST_EVIDENCE_GAP`，SBOM digest 在 C1-T03 fixture
  additions 后过期）并立即重生成 SBOM 修复为 `FIXED`；未新增 runtime issue。仅声明 MuMu API32
  `RD_BASELINE`，不外推 API33+、ARM/16KB、OEM、SX/XH、商业应用或 VA PRO 等价性。
- **偏离任务书**：用户明确要求不等待 1800 秒，故长压在达到两用户各 50 轮以上后停止；本回执
  透明记录该偏离，不宣称 1800 秒压力完成。其余 Provider 矩阵、双用户隔离、recovery、local/build/
  governance 门禁均按失败优先修复并复验通过；无需人工介入，不记录 BLOCKED。
- **实现提交 SHA**：`454c1b4a30cd78ce8eeadbadeca0369c7dcbe99d`
  （`test(provider): [C1-T04] close RD ContentProvider lifecycle gates`）。
- **推送与远端验证**：实现提交已以 canonical identity `OpenAI <openai@users.noreply.github.com>`
  推送到 `origin/feature/t57-r03-va-pro-capability-campaign`；实现提交推送后远端 HEAD 已验证为
  `454c1b4a30cd78ce8eeadbadeca0369c7dcbe99d`。本段账本更新将在下一笔独立回执提交中固化并再次
  推送验证。
- **遗留风险**：仅完成 MuMu `RD测试` API32 `RD_BASELINE`，且本次按指示未完成 1800 秒长压；
  API33+、ARM/16KB、OEM、完整 soak、SX/XH、商业应用和 VA PRO 等价性仍未证明。
- **下一任务**：`C1-T05`；本轮不执行。

### C1-T05：PendingIntent、Alarm、Notification 系统持有者闭环

- **状态**：DONE
- **开始/结束时间**：2026-08-21 21:27 / 2026-08-21 23:12（Asia/Shanghai）
- **执行环境**：Windows 11 amd64；PowerShell；JDK 17.0.18（Zulu）；Android Gradle Plugin 8.11.1；
  compile SDK 36；target SDK 35；Build Tools 35.0.0；NDK 27.2.12479018；CMake 3.22.1；Gradle 8.13。
  MuMu `RD测试` 每次按实例名动态解析；本次实际 serial `127.0.0.1:16416`，Redmi `22041211A`，
  API 32 / Android 12，ABI `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`，boot ID
  `7cac15ce-d76e-44ea-968b-959d91d03be7`，Android ID `398eea33120cd887`。runner 未固化 ADB endpoint。
- **开始基线**：分支 `feature/t57-r03-va-pro-capability-campaign` @
  `585e1ace6fedeb9d43784545327b691ce44f234b`；开始前工作区干净、远端分支同 HEAD；上一回执为
  `C1-T04`，实现/回执提交 `8a4a36e5737d970a838259d492dc516234bddad5`。
- **实现摘要**：系统持有方 PendingIntent 强制改写为显式 Host relay，并在无可改写 Intent 时
  fail-closed；AMS `IIntentSender` 返回保持 raw，Broker relay recovery 改为异步避免 Guest 冷启动
  回调死锁；补齐 Intent/Intent[]、Alarm、Notification、update/cancel、revision/stale fencing、
  Guest death/rebind 和清理证据；runner 加入动态设备解析、框架探针预热及持续 logcat 采集。
- **变更文件**：`sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/FrameworkIdentityInvocationHandler.java`；
  `sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/PendingIntentFrameworkInterceptor.java`；
  `sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeBrokerService.java`；
  `fixture-basic/src/main/java/com/warden/controlledsandbox/fixture/SystemHolderPendingIntentActivity.java`；
  `scripts/check-alarm-notification-lifecycle.py`；`tools/capability/run_c1_t05_rd.py`；
  `docs/review/C1_T05_PENDING_INTENT_ALARM_NOTIFICATION_DESIGN.md`；`docs/review/KNOWN_ISSUES.yaml`；
  `verification/catch-up/C1-T05/c1-t05-rd-summary.json` 及 `artifacts/capability-audit/catch-up-c1-t05/`。
- **验收命令与结果**：`python tools/capability/run_c1_t05_rd.py --loops 50` overall PASS；框架
  PendingIntent/Binder/callback、Notification readback、Alarm clock readback、cross PendingIntent
  六项 marker 全 PASS，无 `FATAL EXCEPTION`；用户 0/1 均 50/50 command pass，relay delivery 分别
  53/50；Guest death recovery、正确 user、旧 session 拒绝、Notification/Alarm residue 均 PASS。
  `scripts/build-device-test-apks.ps1 -NoClean` PASS；`check-pending-intent-lifecycle.py`、
  `check-alarm-notification-lifecycle.py`、`check-contracts.py`、campaign infra validate/tests、
  `generate-sbom.py --check`、本地 `android_oem_compatibility` audit（4/4 PASS）及 `git diff --check`
  均 PASS。`check-system-services-broker-split.py` 仍报告既有 `KI-R03-027` token drift，已登记且
  `blocks_current_campaign: false`，不属于本任务范围。
- **设备证据**：主回执 `verification/catch-up/C1-T05/c1-t05-rd-summary.json`；本次 raw 证据
  `artifacts/capability-audit/catch-up-c1-t05/20260821T145454Z/`，含 campaign logcat、框架探针、
  recovery 前后 dumpsys、kill/rebind 和双用户命令记录；pre-fix 事实见
  `artifacts/capability-audit/catch-up-c1-t05/pre-fix-system-holder-logcat.txt`。
- **Known Issues**：`KI-R03-033` 已由显式 Host relay、raw AMS sender、异步 recovery 与设备证据
  修复，状态更新为 `FIXED` 且不再阻断；`KI-R03-027` 保持既有非阻断记录；`KI-R03-032` 保持
  `RECORDED`，明确八小时稳定性未验证。
- **偏离任务书**：无。任务书 1.1 已移除显式八小时 soak/长稳门槛，本回执不作八小时稳定性声明；
  C1-T05 runner 未启用额外 30 分钟压力参数，已完成任务书要求的双用户各 50 个闭环，C1 阶段总门禁
  仍由后续任务汇总，不提前宣称阶段完成。失败项均已修复并重跑，无需人工介入，不记录 BLOCKED。
- **实现提交 SHA**：`d50d8d91cb98a1c96524efbe2bd00edbc40dddb5`
  （`fix(runtime): [C1-T05] close system-held PendingIntent routes`）。
- **推送与远端验证**：实现提交已以 canonical identity `OpenAI <openai@users.noreply.github.com>`
  推送到 `origin/feature/t57-r03-va-pro-capability-campaign`；实现提交推送后远端 HEAD 已验证为
  `d50d8d91cb98a1c96524efbe2bd00edbc40dddb5`。本段账本更新将在下一笔独立回执提交中固化并再次
  推送验证。
- **下一任务**：`C1-T06`。

### C1-T06：Package 生命周期

- **状态**：DONE
- **开始/结束时间**：2026-08-21 23:12 / 2026-08-22 00:10（Asia/Shanghai）
- **执行环境**：Windows 11 amd64；PowerShell；JDK 17.0.18（Zulu）；Android Gradle Plugin 8.11.1；
  compile SDK 36；target SDK 35；Build Tools 35.0.0；NDK 27.2.12479018；CMake 3.22.1；Gradle 8.13；
  MuMu `RD测试` 通过实例名动态解析，未在新增 runner 中固化 ADB endpoint。
- **开始基线**：分支 `feature/t57-r03-va-pro-capability-campaign` @
  `23ca42d45a1dab1f803248bb0c8f34efa2668fee`；开始前工作区干净、远端同 HEAD；上一回执为
  `C1-T05`，实现/回执提交 `d50d8d91cb98a1c96524efbe2bd00edbc40dddb5` /
  `23ca42d45a1dab1f803248bb0c8f34efa2668fee`。
- **实现摘要**：新增 package-neutral 的 package state/query-resolve/permission-AppOps 与失败安装
  session debug 验收；新增动态 `C1-T06` RD runner，覆盖 lifecycle v1/v2 revision、clone、升级后
  rollback、identity reset、split base/feature、clear/delete/reinstall；修正 split checker 到当前
  typed Binder/Guest verifier 边界；补齐请求 ID、宿主进程排空和 `am start -S`，保证 RD 回执不被旧
  Activity 结果污染。生产路径未新增包名特判或第二状态源。
- **变更文件**：`app/src/debug/java/com/warden/controlledsandbox/DebugCommandActivity.java`；
  `scripts/check-split-install-sessions.py`；`tools/capability/run_c1_t06_rd.py`、
  `run_p1_00_rd.py`、`run_rd_campaign.py`；`docs/review/C1_T06_PACKAGE_LIFECYCLE_DESIGN.md`；
  `docs/review/KNOWN_ISSUES.yaml`；`verification/catch-up/C1-T06/c1-t06-rd-summary.json`；
  `verification/catch-up/C0-T01/continuation-preflight.json`、`verification/m5-t16-source-closure-audit.json`、
  `verification/sbom.json`。
- **验收命令与结果**：`python tools/static_android_compile.py` PASS（仓库既有 javac warnings）；
  `:app:assembleDebug` PASS；package lifecycle、split install、PackageManager query/resolve、virtual
  package state、package-service boundary、pre-device hardening（staticTests=160）、campaign infra、
  SBOM check 均 PASS；`python tools/capability/run_c1_t06_rd.py --instance 'RD测试'` 最终 PASS，
  29 步全部有匹配 request ID 的回执；`git diff --check` PASS。降级策略按预期记录
  `import-prepare` 的 `Package downgrade rejected: 1 < 2`，不是未处理失败；失败安装 session 持久化
  `FAILED`、retry 回到 `OPEN`、abandon 后 package revision 未变化。
- **设备证据**：主回执 `verification/catch-up/C1-T06/c1-t06-rd-summary.json`；raw logcat 与逐步
  evidence 位于 `artifacts/capability-audit/catch-up-c1-t06/20260821T160853Z/`。动态设备为 MuMu
  `RD测试`，serial `127.0.0.1:16416`，model `22041211A`，API 32 / Android 12，ABI
  `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`，boot ID
  `7cac15ce-d76e-44ea-968b-959d91d03be7`，Android ID `398eea33120cd887`。APK SHA-256：host
  `38e6067ab4be1233429cc650446e4f4d6444252a6654be38d5939aee46eb7c4c`；companion32
  `ce9271ae537ec0b98dcdadd1a5933c7836a1dad24f676ec6033a6f5e0bc4d793`；fixture
  `5afa749473194c49b9d1191980c74a6f6bc13c20e742dc5976539a2c3b1a178a`；fixture32
  `1158888d35124e1c243684cb41f323109af87715ee4f28588a8e703b9c95ba3d`；lifecycle v1/v2
  `3b1c7aad9d0c62134292d09ed96dc82932c35a62e69e605867783cf36811ac63` /
  `cd5a5d868eb41b661da6a03e7993d6ce2efd12f7b19d4a69be6676bd85e51a31`；split base/feature
  `0894ff252e16213a92132d02630d721d5190d8b7cf9f0d3b2868ea2bf5ab9c5a` /
  `67947cd7e514cdbd5228f90295c0a403d25d814bed8912993fe847d055e3a598`。
- **Known Issues**：`KI-T57-016` 保持 `NOT_PROVEN`，已重述为跨 API/OEM/VA PRO 覆盖缺口并登记本次
  RD API32 lifecycle 证据；`KI-R03-026`、`KI-R03-029` 保持既有非阻断记录；`KI-R03-032` 保持
  八小时稳定性需单独计划的记录；未新增 runtime issue。
- **偏离任务书**：无验收范围偏离。任务书 1.1 已移除显式八小时 soak/长稳门槛，本回执不作八小时
  稳定性声明；中间发现的 harness/编排竞态均已修复并重跑通过，无需人工介入，不记录 BLOCKED。
- **实现提交 SHA**：`68f28f43b5d35bc4b85a03938b5d7009f9e6f277`
  （`test(package): [C1-T06] verify package lifecycle on RD`）。
- **推送与远端验证**：回执将以主题 `docs(progress): record [C1-T06] receipt` 单独提交；实现提交与
  回执提交均按任务书要求非强制推送到 `origin/feature/t57-r03-va-pro-capability-campaign`，并以
  `git ls-remote --heads origin feature/t57-r03-va-pro-capability-campaign` 对比最终本地 `HEAD`。
- **遗留风险**：仅声明 MuMu `RD测试` API32 `RD_BASELINE`；API33+、ARM/16KB、OEM、SX/XH、商业
  应用和 VA PRO 等价性及八小时稳定性仍未证明，后续按 Known Issue/任务书推进。
- **下一任务**：`C1-T07`；本轮不执行。

## 6. 回执追加模板

后续回执复制以下模板并追加到本节末尾：

```markdown
### <TASK-ID>：<任务名称>

- **状态**：DONE | NOT_APPLICABLE | BLOCKED
- **开始/结束时间**：
- **执行环境**：
- **开始基线**：
- **实现摘要**：
- **变更文件**：
- **验收命令与结果**：
- **设备证据**：
- **Known Issues**：
- **偏离任务书**：无；或填写原因、依据和影响
- **实现提交 SHA**：
- **推送与远端验证**：
- **遗留风险**：
- **下一任务**：
```

### C1-T07：进程槽位、跨 ABI 与故障恢复闭环

- **状态**：BLOCKED
- **开始/结束时间**：2026-08-22 00:12 / 2026-08-22 00:25（Asia/Shanghai）
- **执行环境**：Windows 11 amd64；PowerShell；MuMu `RD测试` 动态解析；API 32；ABI
  `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`；serial `127.0.0.1:16416`；boot ID
  `7cac15ce-d76e-44ea-968b-959d91d03be7`。
- **开始基线**：`feature/t57-r03-va-pro-capability-campaign` @ `0df832982712c5ebd5c6fd14570ea84396f73462`；工作区干净；上一任务 `C1-T06` 已完成并推送。
- **实现摘要**：新增 `t57_rd_c1_t07_process_abi_recovery.ps1`，按实例名动态解析设备、每轮清理测试包、委托现有跨 ABI 死亡恢复 probe，并要求至少 50 轮；新增 `C1_T07_PROCESS_ABI_RECOVERY_DESIGN.md`。
- **变更文件**：`tools/device/t57_rd_c1_t07_process_abi_recovery.ps1`、`docs/review/C1_T07_PROCESS_ABI_RECOVERY_DESIGN.md`、`verification/catch-up/C1-T07/`、`verification/catch-up/C1-T07-rerun/`、`verification/catch-up/C1-T07-rerun2/`。
- **验收命令与结果**：静态 campaign validator、12 个 infra tests、pre-device hardening（160）、Companion identity、timeout recovery、system-service recovery 均 PASS；设备 campaign 首轮跨 ABI recovery PASS，后续轮次未完成：probe 在收尾判定报告 `CROSS_ABI_RECOVERY_FATAL_MARKER`，保存的原始 logcat 未匹配 FATAL/ANR/stale/service-rejected marker；50 轮门禁未满足。
- **设备证据**：`verification/catch-up/C1-T07/`、`verification/catch-up/C1-T07-rerun/`、`verification/catch-up/C1-T07-rerun2/`；包含动态设备快照与原始 logcat。已完成轮次证明 generation 递增、PID 替换、x86 native load 和 `GUEST_PROCESS_DISCONNECTED`；不足以宣称任务 PASS。
- **Known Issues**：未新增 runtime issue；发现 probe/harness 收尾判定与设备原始日志不一致，待下一次执行先修复并分类。
- **偏离任务书**：未修改生产运行时代码；因设备 probe 收尾失败且 50 轮未完成，按失败门禁记录 BLOCKED，不虚报 PASS。
- **实现提交 SHA**：`2ac7502f5b0621c1c079a003e1e54650270bf5ef`。
- **推送与远端验证**：实现提交先行推送；回执提交完成后再次以 `git ls-remote --heads origin feature/t57-r03-va-pro-capability-campaign` 对比本地 HEAD。
- **遗留风险**：50 次 kill/restart、并发压力、双用户污染隔离和完整槽位收敛尚未形成 C1-T07 专属设备证据；恢复条件为修复/解释 probe 收尾判定并完成至少 50 轮。
- **下一任务**：仍为 `C1-T07`；满足恢复条件前不得进入 C2。

### C1-T07：进程槽位、跨 ABI 与故障恢复闭环（恢复执行）

- **状态**：DONE
- **开始/结束时间**：2026-08-22 00:26 / 2026-08-22 02:24（Asia/Shanghai）
- **执行环境**：Windows 11 amd64；PowerShell；MuMu `RD测试` 动态解析；API 32；ABI
  `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`；serial `127.0.0.1:16416`；boot ID
  `7cac15ce-d76e-44ea-968b-959d91d03be7`；Android ID `398eea33120cd887`。
- **开始基线**：`feature/t57-r03-va-pro-capability-campaign` @
  `f36775d458ba330b350cba74710e5fa858c68927`；开始前工作区干净，远端同 HEAD；上一轮
  `C1-T07` BLOCKED 回执保留为历史记录，本节为用户明确要求的恢复执行回执。
- **实现摘要**：修复 API32 Guest 死亡重连后 InputMethodManager 对已失效 Binder client
  返回 `unknown client` 导致 Guest 崩溃的问题：仅对精确的 inputmethod
  `startInputOrWindowGainedFocus`/`IllegalArgumentException`/`unknown client` 组合 fail-closed，
  返回方法默认值，其他异常继续抛出。修复 C1-T07 runner 的 marker 轮询、完整失败 logcat、
  ADB daemon 短暂失败重试；修复 P1-00 普通主进程不应传空 `processName` 的 harness 缺陷。
- **变更文件**：`sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/SystemServiceInvocationHandler.java`、
  `tools/device/t57_rd_c1_t07_process_abi_recovery.ps1`、`tools/device/t57_rd_cross_abi_recovery_probe.ps1`、
  `tools/capability/run_p1_00_rd.py`、`docs/review/C1_T07_PROCESS_ABI_RECOVERY_DESIGN.md`，以及
  `verification/catch-up/C1-T07*`、P1-00 与设备测试 APK manifest/hash 证据。
- **验收命令与结果**：
  `python tools/static_android_compile.py` PASS；`python scripts/check-pre-device-runtime-hardening.py`
  PASS（160）；campaign validator PASS、12 个 infra tests PASS；Companion identity、timeout
  recovery、binder-death、guest-pool reconnect、system-service recovery、interaction-service、
  architecture 与 supply-chain 门禁均 PASS；`scripts/build-device-test-apks.ps1 -NoClean` PASS。
  C1-T07 runner 返回 `RESULT: PASS task=C1-T07 loops=50`，逐轮结果 50/50 PASS，generation
  `1→2`、PID 全部替换、Companion `x86`/32-bit、`GUEST_PROCESS_DISCONNECTED`；普通 recovery、
  cross-ABI clear/delete/reinstall、isolated service 均 PASS。P1-00 槽位证据中 ordinary
  `0/1/7/8/31/32/62/63` 与 isolated `0/7/8/14/15` 全部命中，耗尽分别为预期的
  `NO_PROCESS_SLOT`。
- **设备证据**：`verification/catch-up/C1-T07/c1-t07-rerun-acceptance.json`、
  `verification/catch-up/C1-T07-rerun-final2/`、`verification/catch-up/C1-T07-diagnostic/`、
  `verification/catch-up/C1-T07-recovery-final/`、`verification/catch-up/C1-T07-cross-abi-lifecycle-final/`、
  `verification/catch-up/C1-T07-isolated-final/`、`artifacts/capability-audit/p1-00/20260821T180354Z/`。
- **Known Issues**：诊断轮次捕获的旧 IME stale-client 崩溃已由本次生产修复关闭。相邻完整
  RD regression 的 transport case 在动态 receiver marker 已到达后卡在 `adb shell am broadcast`；
  原始目录 `verification/catch-up/C1-T07-full-regression-final/` 已保留并分类为相邻
  transport/harness 问题，不作为 C1-T07 进程/ABI/死亡重连验收的 PASS 依据。P1-00 的
  system-holder 结果属于 C1-T05 范围，不改变本任务的槽位验收结论。
- **偏离任务书**：未修改任务书或验收标准；本任务 50 次 kill/restart、槽位、Companion32、
  死亡重连与直接相关回归均完成。C1 阶段级“双用户 + 30 分钟压力 + 资源收敛”门禁仍标记
  `PENDING`，不提前宣称 C1 阶段完成。
- **实现提交 SHA**：`5d7195cb475108d7c2e864913a18ce6b778a70c2`
  （`fix(process): [C1-T07] close death recovery and slot gates`）。
- **推送与远端验证**：实现提交先行提交；本回执提交随后以主题
  `docs(progress): record [C1-T07] receipt` 单独提交；两提交均非强制推送到
  `origin/feature/t57-r03-va-pro-capability-campaign`，并用 `git ls-remote --heads` 校验最终远端 HEAD。
- **遗留风险**：C1 阶段门禁的双用户全 fixture、30 分钟并发压力和 clear/delete/restart/death
  全资源收敛仍需阶段收口时单独补齐；本任务不宣称 API33+、ARM/16KB、OEM、SX/XH 或商业等价性。
- **下一任务**：`C1 阶段门禁（完成后进入 C2-T01）`；本轮不执行后续任务。

### C1-GATE：C1 阶段门禁

- **状态**：DONE
- **开始/结束时间**：2026-08-22 15:05 / 2026-08-22 15:53（Asia/Shanghai）
- **执行环境**：Windows 11 amd64；PowerShell；MuMu `RD测试` 动态解析；serial
  `127.0.0.1:16416`；model `22041211A`；API 32；ABI
  `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`；boot ID
  `773adc6f-e0aa-4997-a0ee-481a7773a10d`；Android ID `398eea33120cd887`。
- **开始基线**：`feature/t57-r03-va-pro-capability-campaign` @
  `d1de49a463f41ecd068eab867be4e4efe59cc34e`；C1-T07 恢复回执已完成；按协议先将本任务置为
  `IN_PROGRESS`，未修改任务书验收标准。
- **实现摘要**：补齐 C1 阶段门禁设计、动态 RD gate runner 和 fail-closed validator；修复
  Provider 双用户代际同步/Host-stop 归属及 `GUEST_NOT_PREPARED` 一次恢复；为每个 Guest
  增加框架 ordered broadcast FIFO dispatcher；修复设备时钟回退导致的非法安装时间戳；补齐
  full regression 的精确 ADB daemon 恢复重试与前台 receiver 传输入口。
- **变更文件**：`app/src/debug/java/com/warden/controlledsandbox/DebugCommandActivity.java`、
  `sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestContextComponentRouter.java`、
  `app/src/main/java/com/warden/controlledsandbox/SandboxCatalogState.java`、
  `fixture-basic/src/main/java/com/warden/controlledsandbox/fixture/ProviderCampaignActivity.java`、
  `tools/capability/run_c1_t04_rd.py`、`tools/capability/run_p1_00_rd.py`、
  `tools/capability/validate_c1_stage_gate.py`、`tools/device/t57_rd_c1_stage_gate.ps1`、
  `tools/device/t57_rd_full_regression.ps1`、`docs/review/C1_STAGE_GATE_DESIGN.md`、
  `verification/catch-up/C1-GATE/`。
- **验收命令与结果**：`python tools/android_gradle_build_gate.py verify` PASS；
  `python tools/static_android_compile.py` PASS；`python tools/capability/validate_c1_stage_gate.py`
  PASS。Provider 双用户各完成 105 个代际循环（共 210 个循环/每类 marker 210），要求压力
  `1800` 秒、实测 `1817.687` 秒；C1 full regression 9/9 PASS；clear/delete/reinstall 与
  process-death/cross-ABI 四组资源收敛结果均 PASS。
- **设备证据**：主回执为 `verification/catch-up/C1-GATE/c1-gate-receipt.json`；Provider
  回执为 `verification/catch-up/C1-GATE/c1-gate-provider-rd-summary.json`；9-case transcript
  为 `verification/catch-up/C1-GATE/c1-gate-run.txt`；最终设备快照和逐 case 证据位于
  `verification/catch-up/C1-GATE/`，均记录同一 serial、API、boot ID 和实现提交。
- **Known Issues**：无新增 runtime issue。初始冷启动、Provider 代际清理、ordered broadcast
  FIFO、ADB daemon 短暂失败和设备墙钟回退均已分类并修复后重跑；原始失败材料保留在
  `verification/catch-up/C1-GATE/` 的分类/诊断证据中。
- **偏离任务书**：无。仅执行 C1 阶段门禁一个任务；失败优先修复并重跑，未记录 `BLOCKED`；
  本回执只关闭 RD API 32/C1 范围，不外推 API33+、ARM/16KB、OEM、SX/XH 或商业等价性。
- **实现提交 SHA**：`df60c2dfa3d81af805216225da51fa0bc4865670`
  （`feat(c1): close phase gate with RD evidence`）。
- **回执提交**：使用主题 `docs(progress): record [C1-GATE] receipt` 单独提交；本回执中
  `C1-GATE` 状态由 `IN_PROGRESS` 更新为 `DONE`。
- **推送与远端验证**：实现提交先行提交；本回执提交完成后，两个提交均非强制推送到
  `origin/feature/t57-r03-va-pro-capability-campaign`，并用 `git ls-remote --heads` 校验最终
  远端 HEAD 与本地 HEAD 一致。
- **遗留风险**：C1 仅证明当前 RD API 32 设备范围；C2 系统服务/F2-F5、API33+、ARM/16KB、
  OEM、SX/XH 与商业等价性仍未验证。
- **下一任务**：`C2-T01`；本轮不执行后续任务。

### C2-T01：SX/XH 系统服务方法清单

- **状态**：DONE
- **开始/结束时间**：2026-08-22 15:57 / 2026-08-22 16:41（Asia/Shanghai）
- **执行环境**：Windows 11 amd64；PowerShell；仓库 `D:\github\controlled-android-sandbox`；分支
  `feature/t57-r03-va-pro-capability-campaign`。MuMu 实例按名称 `RD测试` 动态解析成功，当前状态为
  `device`，resolved serial `127.0.0.1:16416`，model `22041211A`，API 32，ABI
  `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`，boot ID
  `773adc6f-e0aa-4997-a0ee-481a7773a10d`，Android ID `398eea33120cd887`。
- **开始基线**：`feature/t57-r03-va-pro-capability-campaign` @
  `d0da3743197b609756d0bd5ed309ad2bfa71de2f`；开始前已核对远端同 HEAD；上一任务
  `C1-GATE` 已 DONE，主回执为 `verification/catch-up/C1-GATE/c1-gate-receipt.json`。
  工作区原有的未跟踪 C1-GATE 诊断/重试证据未纳入本任务，保持原样。
- **实现摘要**：将 59 个 service Hook 文件逐一分类为 F1-F5 产品面、C2 P1 支撑面或明确分离的
  C2 P2 长尾；建立 40 条逻辑方法族 backlog（P0=20、P1=15、P2=5）。每条 P0/P1 都记录
  request/identity、return/callback、cleanup/death、现有 owner、当前证据、测试计划和目标证据等级。
  同时修复预检发现的静态检查器 owner/实现漂移，并重新生成 SBOM digest；未修改生产运行时行为。
- **变更文件**：`docs/review/C2_T01_SYSTEM_SERVICE_METHOD_CATALOG.md`、
  `verification/catch-up/C2-T01/c2-t01-method-inventory.json`、
  `scripts/check-binder-system-services.py`、`scripts/check-m5-t12-webview-gms-oem-detection.py`、
  `scripts/check-package-query-resolve.py`、`scripts/check-system-services-broker-split.py`、
  `verification/sbom.json`。
- **验收命令与结果**：目录实际 Hook 文件与清单核对 `59/59`；JSON readback PASS；P0/P1 owner、
  test plan、target evidence 完整性 PASS；`python scripts/test_mumu_instance.py` PASS；M5-T8 至
  M5-T15、system-service split、Binder system-service、PackageManager query/resolve 与 SBOM
  checks 全部 PASS；`git diff --check` PASS。`python tools/capability/run_local_capability_audit.py --all`
  在实现提交上完成：42 gates 中 34 PASS、8 个已登记 KNOWN_ISSUE、0 NEW_REGRESSION，证据目录为
  `artifacts/capability-audit/all/20260822T083903Z/`。
- **设备证据**：本任务是方法清单与治理验收，不新增设备行为声明；仅按任务书动态解析并记录
  `RD测试` 快照，完整字段保存在 `verification/catch-up/C2-T01/c2-t01-method-inventory.json`。
  C1 行为基线仍只引用 `verification/catch-up/C1-GATE/c1-gate-receipt.json`，不外推为 C2 L3。
- **Known Issues**：全量审计中的 `KI-R03-020`、`KI-M10-001/002`、`KI-R03-023`、`KI-R03-024`、
  `KI-R03-025`、`KI-R03-026`、`KI-M10-005`、`KI-M10-006` 均为既有非阻断项；本任务未新增
  runtime issue。预检发现的 SBOM digest 与 package-query 静态规则漂移已修复并复验通过；VA PRO
  等价性仍为 `NOT_PROVEN`。
- **偏离任务书**：无验收范围偏离。按任务书 C2-T01 交付文档/矩阵/证据；P2 未调用长尾明确分离，
  隐藏 API32/OEM 具体 overload 留给后续 C2 任务；失败优先修复并重跑，未记录 BLOCKED。
- **实现提交 SHA**：`ebcc31841f805502a0791f245b1df505333dfa04`
  （`docs(c2): [C2-T01] inventory system-service method surface`）。
- **回执提交**：使用主题 `docs(progress): record [C2-T01] receipt` 单独提交；本回执将 C2-T01
  从 `IN_PROGRESS` 更新为 `DONE`。
- **推送与远端验证**：回执提交完成后，两个提交一并推送到
  `origin/feature/t57-r03-va-pro-capability-campaign`，并用
  `git ls-remote --heads origin feature/t57-r03-va-pro-capability-campaign` 对比最终本地 HEAD。
- **遗留风险**：C2-T02 至 C2-T07 尚未补齐 SX/XH F1-F5 的 RD_API32 L3 方法证据；API33+、ARM/16KB、
  OEM、商业应用和 VA PRO 等价性仍未验证；P2 长尾按本清单保持显式 `UNVERIFIED`。
- **下一任务**：`C2-T02`；本轮不执行后续任务。

### C2-T02：PMS/Permission/AppOps/Attribution

- **状态**：DONE
- **开始/结束时间**：2026-08-22 16:50 / 18:02（Asia/Shanghai）
- **执行环境**：Windows 11 amd64；PowerShell；仓库 `D:\github\controlled-android-sandbox`；分支
  `feature/t57-r03-va-pro-capability-campaign`。MuMu 实例按名称 `RD测试` 动态解析成功，状态为
  `device`，resolved serial `127.0.0.1:16416`，model `22041211A`，API 32，ABI
  `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`，boot ID
  `773adc6f-e0aa-4997-a0ee-481a7773a10d`，Android ID `398eea33120cd887`。
- **开始基线**：`feature/t57-r03-va-pro-capability-campaign` @
  `4d912297d91d86efa6181d72f4f7c7c8f60483bc`；开始前已完整读取任务书、进度账本和治理文件，
  通过继续执行预检，确认上一任务 `C2-T01` 已 DONE、远端同 HEAD，首个依赖满足任务为 C2-T02。
  工作区原有 C1-GATE 未跟踪诊断/重试证据保持原样，未纳入本任务。
- **实现摘要**：统一 PMS、Permission、AppOps 的 Host/Guest package、UID 和 attribution
  边界；Guest Context 生成 API31+ AttributionSource，AppOps note/start 使用真实
  `SyncNotedAppOp` mode 构造；Broker 直接 Provider transaction 安装并恢复 Guest callback
  identity；clear instance data 同步清除权限/AppOps policy。新增 package-neutral C2-T02
  probe、Provider callback 证据、policy-state reset audit 和动态 RD runner。
- **变更文件**：`app/src/debug/java/com/warden/controlledsandbox/DebugCommandActivity.java`；
  `app/src/main/java/com/warden/controlledsandbox/SandboxPackageLifecycle.java`；
  `app/src/testHarness/java/com/warden/controlledsandbox/RuntimePermissionWorkflowSelfTest.java`；
  `fixture-basic/src/main/AndroidManifest.xml`、`fixture-basic/src/main/java/com/warden/controlledsandbox/fixture/FixtureProvider.java`、
  `PmsPermissionAttributionProbeActivity.java`；
  `sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/SystemServiceInvocationHandler.java`、
  `FrameworkIdentityProxySelfTest.java`；
  `sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestAttributionSourceBridge.java`、
  `GuestComponentRuntime.java`、`GuestContext.java`、`GuestPackageContext.java`、
  `GuestRuntimeBrokerBridge.java`、`RuntimeKeys.java`；
  `scripts/check-runtime-permission-workflow.py`；`tools/capability/run_c2_t02_rd.py`；
  `docs/review/C2_T02_PMS_PERMISSION_APPOPS_ATTRIBUTION_DESIGN.md`；
  `verification/catch-up/C2-T02/`；`verification/catch-up/C0-T01/continuation-preflight.json`。
- **验收命令与结果**：`python tools/static_android_compile.py` PASS，160/160 self-test PASS、
  0 non-pass；pre-device hardening PASS（staticTests=160）；runtime permission、virtual
  package state、PackageManager query/resolve、package-service boundary、Binder system-service
  checks 全部 PASS；四模块最终 Gradle assemble PASS；`git diff --check` PASS。
  `python tools/capability/run_c2_t02_rd.py --instance RD测试` 最终 PASS，17 步完成。
  中间发现的 API31+ static stub/JVM 反射差异、effective AppOps 与 raw reset 口径差异及一次
  probe 启动 gate 时序失败均已修复并重跑；未记录 `BLOCKED`。
- **设备证据**：主回执为 `verification/catch-up/C2-T02/c2-t02-rd-summary.json`；本地验收清单为
  `verification/catch-up/C2-T02/c2-t02-local-verification.json`；设计与方法矩阵为
  `docs/review/C2_T02_PMS_PERMISSION_APPOPS_ATTRIBUTION_DESIGN.md`；最终 raw logcat 与逐步
  设备证据位于 `artifacts/capability-audit/catch-up-c2-t02/20260822T100204Z/`。
  user0 的 CAMERA `DENIED/IGNORED`、user1 的 `GRANTED/ALLOWED`、PMS/Permission 负面边界、
  AppOps check/note/start/proxy/checkPackage、Guest/Provider Attribution callback、clear/delete
  收敛及双用户隔离均在回执中记录。
- **Known Issues**：`KI-R03-020`、`KI-R03-023`、`KI-R03-024`、`KI-R03-025`、`KI-R03-026`、
  `KI-M10-005`、`KI-M10-006`、`KI-M10-007` 均为既有非阻断项；本任务未新增 runtime issue，
  VA PRO 等价性保持 `NOT_PROVEN`。
- **偏离任务书**：无验收范围偏离；本轮只执行 C2-T02，设备证据限定 RD API32，不外推 API33+、
  ARM/16KB、OEM、SX/XH 或商业等价性；失败优先修复并重跑，未记录 `BLOCKED`。
- **实现提交 SHA**：`16a9aa38fdf7f9227de06cadf7304033f59a4fa3`
  （`feat(c2): [C2-T02] close PMS permission AppOps attribution boundary`）。
- **回执提交**：使用主题 `docs(progress): record [C2-T02] receipt` 单独提交；本回执将 C2-T02
  从 `IN_PROGRESS` 更新为 `DONE`。
- **推送与远端验证**：回执提交完成后，两个提交均非强制推送到
  `origin/feature/t57-r03-va-pro-capability-campaign`，并用
  `git ls-remote --heads origin feature/t57-r03-va-pro-capability-campaign` 对比最终本地 HEAD。
- **遗留风险**：C2-T03 至 C2-T07 尚未补齐 SX/XH F1-F5 的 RD_API32 L3 方法证据；API33+、
  ARM/16KB、OEM、商业应用和 VA PRO 等价性仍未验证；P2 长尾按本清单保持显式 `UNVERIFIED`。
- **下一任务**：`C2-T03`；本轮不执行后续任务。

### C2-T03：Location 通用能力闭环

- **状态**：DONE
- **开始/结束时间**：2026-08-22 18:06 / 21:31（Asia/Shanghai）
- **执行环境**：Windows 11 amd64；PowerShell；仓库 `D:\github\controlled-android-sandbox`；分支
  `feature/t57-r03-va-pro-capability-campaign`。MuMu 实例按名称 `RD测试` 动态解析成功，状态为
  `device`，resolved serial `127.0.0.1:16416`，model `22041211A`，API 32，ABI
  `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`，boot ID
  `773adc6f-e0aa-4997-a0ee-481a7773a10d`。
- **开始基线**：`feature/t57-r03-va-pro-capability-campaign` @
  `be8f23a181ae4ec292a0685289bd7e9298899ad2`；开始前完整读取任务书、进度账本、
  `docs/capability/CAPABILITY_CAMPAIGN_WORKFLOW.md` 与 `docs/COMMIT_IDENTITY_POLICY.md`，
  核对最后回执为 `C2-T02`，执行 `git fetch origin` 后确认本地与远端同 HEAD；首个依赖满足的
  `PENDING` 任务为 C2-T03。工作区原有 C1-GATE 未跟踪诊断/重试证据保持原样，未纳入本任务。
- **实现摘要**：补齐 Location 的 profile-driven provider/status、last/current（含
  `LocationRequest`）、listener 注册/注销、NMEA、GNSS、权限变化、前后台、进程关闭和双用户
  隔离生命周期；回调在注销、clear、权限拒绝、profile 不可用和 manager close 后 fail-closed，
  PendingIntent/test-provider 不支持路径显式返回负面结果。Guest Hook 使用动态 capability policy，
  explicit release 释放 capability lease；新增 package-neutral Location campaign、动态 MuMu RD
  runner、设计矩阵、静态门禁和结构化设备证据。构建所需的既有 API/lint 注解修复一并纳入实现。
- **变更文件**：`sandbox-framework/src/main/java/android/location/ControlledLocationManager.java`、
  `sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/service/LocationServiceHook.java`、
  `sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/DeviceServiceInvocationInterceptor.java`、
  `app/src/debug/java/com/warden/controlledsandbox/DebugCommandActivity.java`、
  `fixture-basic/src/main/AndroidManifest.xml`、`fixture-basic/src/main/java/com/warden/controlledsandbox/fixture/LocationCampaignActivity.java`、
  `scripts/check-c2-t03-location.py`、`tools/capability/run_c2_t03_rd.py`、
  `docs/review/C2_T03_LOCATION_DESIGN.md`、`docs/review/KNOWN_ISSUES.yaml`、
  `verification/catch-up/C2-T03/` 及相关 static stub、lint、SBOM 文件。
- **验收命令与结果**：`build-device-test-apks.ps1` PASS（locked host/fixture/companion32 APK set）；
  `:fixture-compat32:assembleDebug` PASS；`verify-device-test-artifacts.py --android-tools --profile device-lab`
  PASS（locked host/fixture64/fixture32/companion32 APK set）；`python tools/static_android_compile.py`
  PASS，160/160 self-tests；C2-T03、pre-device hardening、M5-T8、Campaign validator、Campaign
  infrastructure 12 tests、SBOM、Python compile 与 `git diff --check` 全部 PASS。全量本地审计为
  42 gates 中 34 PASS、8 个既有 KNOWN_ISSUE、0 NEW_REGRESSION；该诊断命令因既有问题返回 1，
  未把它们误判为本任务阻断。
- **设备证据**：`python tools/capability/run_c2_t03_rd.py --instance RD测试` 最终 PASS；主回执为
  `verification/catch-up/C2-T03/c2-t03-rd-summary.json`，本地验收清单为
  `verification/catch-up/C2-T03/c2-t03-local-verification.json`，设计矩阵为
  `docs/review/C2_T03_LOCATION_DESIGN.md`，raw logcat 与逐步证据位于
  `artifacts/capability-audit/catch-up-c2-t03/20260822T122226Z/`。user0 permission-denied 5 秒
  callbacks/NMEA/current/current-request 均为 0；user0 profile-update 1800 秒为 callbacks/NMEA
  `1801/1801`、current/current-request `1/1`；user1 isolation 1800 秒为 `1800/1800`、`1/1`；
  user1 clear 5 秒全部为 0。两个稳定阶段均记录 GNSS `started/firstFix/stopped`，每阶段注销 1 次，
  force-stop 后 `post_stop_processes` 为空；user0/user1 坐标、provider、accuracy、时间和 callback
  order 均通过隔离校验。
- **设备 APK SHA-256**：host
  `ABF6F8BB061F2A2634C83F95C1BD8ED947F6D51CCC0A0F5E971458C443A9904D`；companion32
  `C1072E638210802492242D310893AF6CA06C9C6A917B3FC62904415FC5C5BAA5`；fixture64
  `2DB21ECDB66BAB821A28803D2B5C3F32355DC1148CCC222FB754D9270C8ED748`；fixture32
  `E783D04A7614332312B98B47E8CB3DD287E802DEFC46804747621011FE5C0D11`。
- **Known Issues**：`KI-R03-034` 已 FIXED（Location manager 权限感知的生命周期清理）；
  `KI-R03-035` 已 RECORDED（GNSS 为 profile-driven callback，非真实 HAL satellite object）；
  `KI-R03-020`、`KI-R03-023`、`KI-R03-024`、`KI-R03-025`、`KI-R03-026`、`KI-M10-005`、
  `KI-M10-006`、`KI-M10-007` 为既有非阻断项；本任务无 BLOCKED。
- **偏离任务书**：无验收范围偏离；本轮只执行 C2-T03。设备证据限定 RD API32，不外推 API33+、
  ARM/16KB、OEM、商业应用、SX/XH 或 VA PRO 等价性，均保持 `NOT_PROVEN`；失败均优先修复并重跑。
- **实现提交 SHA**：`e327d329dba2feb93665566fa2721f5a2b6ed378`
  （`feat(location): [C2-T03] close Guest location lifecycle gates`）。
- **回执提交**：将使用主题 `docs(progress): record [C2-T03] receipt` 单独提交；本回执将 C2-T03
  从 `IN_PROGRESS` 更新为 `DONE`。
- **推送与远端验证**：回执提交完成后，两个提交均非强制推送到
  `origin/feature/t57-r03-va-pro-capability-campaign`，并用
  `git ls-remote --heads origin feature/t57-r03-va-pro-capability-campaign` 对比最终本地 HEAD。
- **下一任务**：`C2-T04`；本轮不执行后续任务。

### C2-T04：Camera1/Camera2 通用能力闭环

- **状态**：DONE
- **开始/结束时间**：2026-08-22 21:40 / 2026-08-22 23:12（Asia/Shanghai）
- **执行环境**：Windows 11 amd64；PowerShell；仓库 `D:\github\controlled-android-sandbox`；分支
  `feature/t57-r03-va-pro-capability-campaign`。MuMu 实例按名称 `RD测试` 动态解析成功，状态为
  `device`，resolved serial `127.0.0.1:16416`，model `22041211A`，API 32，ABI
  `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`，boot ID
  `773adc6f-e0aa-4997-a0ee-481a7773a10d`，Android ID `398eea33120cd887`。
- **开始基线**：`feature/t57-r03-va-pro-capability-campaign` @
  `1d109c59e2e6722863eaaa5452eb1bca0cc195cc`；开始前完整读取任务书、进度账本、
  `docs/capability/CAPABILITY_CAMPAIGN_WORKFLOW.md`、`docs/COMMIT_IDENTITY_POLICY.md`、
  C2-T03 最后回执与 Camera 事实源，执行 `git fetch origin` 后确认本地与远端同 HEAD；首个
  依赖满足的 `PENDING` 任务为 C2-T04。工作区原有 C1-GATE 未跟踪诊断/重试证据保持原样，
  未纳入本任务。
- **实现摘要**：增加 package-neutral CameraCampaignActivity，覆盖 Camera1/Camera2 源帧、
  尺寸、格式、时间戳、回调/结果、ImageReader/设备会话关闭和资源清理；Debug 管理命令生成
  确定性 PNG 源并把 campaign 参数安全传入 Guest；Camera capability lease 在显式 release、
  disconnect、adapter 失败和 Guest 清理路径收敛；新增动态 `RD测试` runner、C2-T04 静态门、
  设计矩阵与结构化回执。续接扫描器仅允许负向历史 serial 断言，不放宽可执行 runner 的动态
  设备解析。
- **变更文件**：`app/src/debug/java/com/warden/controlledsandbox/DebugCommandActivity.java`、
  `app/src/main/java/com/warden/controlledsandbox/RuntimeClient.java`、
  `sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/PeripheralCameraInvocationHandler.java`、
  `PeripheralInvocationState.java`、`fixture-basic/src/main/AndroidManifest.xml`、
  `fixture-basic/src/main/java/com/warden/controlledsandbox/fixture/CameraCampaignActivity.java`、
  `tools/capability/run_c2_t04_rd.py`、`scripts/check-c2-t04-camera.py`、
  `scripts/verify-catch-up-continuation.py`、`docs/review/C2_T04_CAMERA_DESIGN.md`、
  `docs/review/KNOWN_ISSUES.yaml`、`verification/catch-up/C2-T04/` 及相关 SBOM/source-closure/
  C0-T01 续接证据。
- **验收命令与结果**：`python tools/static_android_compile.py` PASS（160/160 self-tests）；
  锁定 `build-device-test-apks.ps1 -NoClean` PASS；`verify-device-test-artifacts.py
  --android-tools --profile device-lab` PASS；C2-T04 静态门、pre-device hardening、campaign
  validator、campaign infrastructure 12 tests、SBOM、JSON/Python compile、续接校验和
  `git diff --check` 全部 PASS。collect-all 按治理要求完成并分类为 42 gates 中 34 PASS、
  8 个既有 KNOWN_ISSUE、0 NEW_REGRESSION；其诊断命令因既有问题返回 1，不作为本任务阻断。
- **设备证据**：`python tools/capability/run_c2_t04_rd.py --instance RD测试 --loops 100
  --pressure-seconds 1800` 最终 PASS；主回执为
  `verification/catch-up/C2-T04/c2-t04-rd-summary.json`，本地验收清单为
  `verification/catch-up/C2-T04/c2-t04-local-verification.json`，设计矩阵为
  `docs/review/C2_T04_CAMERA_DESIGN.md`，raw logcat 与逐步证据位于
  `artifacts/capability-audit/catch-up-c2-t04/20260822T142834Z/`。正式 RD 结果：source SHA-256
  `c3e47c885142b911aa7ce38744af8eb79c735ae6be3f6de5958ee284976a16b3`；smoke 为
  `frames/cleanup/session-closed=19/19/19`；100-iteration loops PASS，cleanup marker 200、
  failure marker 0（raw log 含两个成功的 100-iteration 窗口）；Camera2 preview 1800 秒 PASS，
  progress 60、首帧 1、session close 1；两轮 recovery PASS；permission revoked 只得到预期
  `GUEST_CAMERA_PERMISSION_DENIED` 且无 camera-open，恢复后 smoke 与 clear 均 PASS；无 FATAL、
  ANR、stale-session 或 leak marker。
- **Known Issues**：`KI-R03-036` 已 FIXED（本次 RD API32 范围内闭合 Camera1/Camera2 源帧与
  生命周期证据缺口）；`KI-R03-037` 已 FIXED（续接负向 serial guard 误报）；`KI-R03-035` 保持
  RECORDED；`KI-R03-020`、`KI-R03-023`、`KI-R03-024`、`KI-R03-025`、`KI-R03-026`、
  `KI-M10-005`、`KI-M10-006`、`KI-M10-007` 为既有非阻断项；本任务无 BLOCKED。
- **偏离任务书**：无验收范围偏离；设备证据限定 RD API32，不外推 API33+、ARM/16KB、OEM、
  SX/XH、商业应用或 VA PRO 等价性；Camera2 `YUV_420_888` 仅记录为 advertised format，
  实际交付源路径为 Camera1 NV21 与 Camera2 JPEG，`va_pro_equivalent` 保持 `NOT_PROVEN`；
  失败均优先修复并重跑。
- **实现提交 SHA**：`91cb86b62e2c8dd64b5047aee7b93609093eac36`
  （`feat(c2): [C2-T04] close Camera1 Camera2 source and lifecycle gates`）。
- **回执提交**：使用主题 `docs(progress): record [C2-T04] receipt` 单独提交；本回执将 C2-T04
  从 `IN_PROGRESS` 更新为 `DONE`。
- **推送与远端验证**：回执提交完成后，两个提交均非强制推送到
  `origin/feature/t57-r03-va-pro-capability-campaign`，并用
  `git ls-remote --heads origin feature/t57-r03-va-pro-capability-campaign` 对比最终本地 HEAD。
- **遗留风险**：C2-T05 至 C2-T07 尚未补齐对应 RD/API/OEM/商业方法证据；本任务仍不声称
  API33+、ARM/16KB、OEM、SX/XH 或 VA PRO 等价性；C2-T04 的 Camera2 source format 与
  `RD_BASELINE` 边界按回执保持显式记录。
- **下一任务**：`C2-T05`；本轮不执行后续任务。

### C2-T05：调度与交互服务

- **状态**：DONE
- **开始/结束时间**：2026-08-22 23:20 / 2026-08-23 01:50（Asia/Shanghai）
- **执行环境**：Windows 11 amd64；PowerShell；仓库 `D:\github\controlled-android-sandbox`；分支
  `feature/t57-r03-va-pro-capability-campaign`。MuMu 实例按名称 `RD测试` 动态解析成功，状态为
  `device`，resolved serial `127.0.0.1:16416`，model `22041211A`，API 32，ABI
  `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`，boot ID
  `773adc6f-e0aa-4997-a0ee-481a7773a10d`，Android ID `398eea33120cd887`。
- **开始基线**：`feature/t57-r03-va-pro-capability-campaign` @
  `b1655e74611d4f3006582cfe334bc7a0c8341ea9`；开始前完整读取任务书、进度账本、
  `docs/capability/CAPABILITY_CAMPAIGN_WORKFLOW.md`、`docs/COMMIT_IDENTITY_POLICY.md`，
  核对 C2-T04 最后回执、Git/远端 HEAD 和动态 RD 环境；首个依赖满足的 `PENDING` 任务为
  C2-T05。工作区原有 C1-GATE 未跟踪诊断/重试证据保持原样，未纳入本任务。
- **实现摘要**：新增 package-neutral C2-T05 fixture、事件接收器、设计矩阵、静态门禁和动态
  RD runner，闭合 Notification channel/post/click/delete、exact Alarm schedule/callback/
  cancel、JobInfo constraints/schedule/callback/finish、FGS declared/runtime `dataSync` type/
  promotion/stop，以及 Window token、Display context、Input/IME request/return 证据；增加
  Guest Service stop acknowledgement、typed XML hex integer、Guest display context 和 Host
  bridge metadata 投影修复。普通循环 PendingIntent 使用稳定 request identity +
  `FLAG_UPDATE_CURRENT`，避免长循环产生无界等价远端记录。
- **变更文件**：`app/src/debug/java/com/warden/controlledsandbox/DebugCommandActivity.java`、
  `app/src/main/java/com/warden/controlledsandbox/VirtualPackageStateBuilder.java`、
  `fixture-basic/src/main/AndroidManifest.xml`、`fixture-basic/src/main/java/com/warden/controlledsandbox/fixture/`、
  `sandbox-domain/src/main/java/com/warden/controlledsandbox/domain/packageinfo/manifest/BinaryXmlManifestParser.java`、
  `sandbox-domain/src/testHarness/java/com/warden/controlledsandbox/domain/`、
  `sandbox-runtime/src/main/AndroidManifest.xml`、`sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/`、
  `scripts/check-c2-t05-scheduling-interaction.py`、`tools/capability/run_c2_t05_rd.py`、
  `tools/static_android_compile.py`、`docs/review/C2_T05_SCHEDULING_INTERACTION_DESIGN.md`、
  `docs/review/KNOWN_ISSUES.yaml`、`verification/catch-up/C2-T05/`。
- **验收命令与结果**：`python scripts/check-c2-t05-scheduling-interaction.py` PASS；通知/Job、
  Alarm/Notification、JobScheduler policy gates PASS；`python tools/static_android_compile.py`
  PASS（module self-tests）；`build-device-test-apks.ps1` 清洁构建 PASS（766 tasks），
  `:fixture-compat32:check :fixture-compat32:assembleDebug` PASS，
  `verify-device-test-artifacts.py --android-tools --profile device-lab` PASS；Python compile 和
  `git diff --check` PASS。首次 50-loop runner 因等待预算不足在 31 轮后提前判失败，设备日志显示
  循环仍通过；修复 runner 每轮 18 秒预算后重跑，最终 `run_c2_t05_rd.py --instance RD测试
  --loops 50` PASS，未记录 BLOCKED。
- **设备证据**：主回执为 `verification/catch-up/C2-T05/c2-t05-rd-summary.json`；本地验收清单为
  `verification/catch-up/C2-T05/c2-t05-local-verification.json`；设计矩阵为
  `docs/review/C2_T05_SCHEDULING_INTERACTION_DESIGN.md`；最终 raw logcat 与设备证据位于
  `artifacts/capability-audit/catch-up-c2-t05/20260822T173726Z/`。50/50 loop 的 notification
  return/click/delete/pass、alarm return/callback/pass、job return/callback、FGS return/
  promoted/stop 和 loop pass 均为 50；FGS type 全部为 `1`；window token、display context、
  IME host catalog isolation 均 PASS。死亡探针使用 `run-as` 终止旧 Guest，旧 PID 已死、替代
  进程 callback 到达，cleanup PASS，notification/alarm residue 均为 false。
- **设备 APK SHA-256**：host
  `5bc966f24861c9162f460a5b5bd69da131d0fdffe9aa160fdef3d727fec5dff5`；companion32
  `399ac60dad484b5eeef0ae6fd4bd67f822140dc58b7a74d3a948a66b820730d4`；fixture64
  `b42ae1a3266d76621c75fb9f2642d03fa01530908ccdbffd27839764da24ff13`；fixture32
  `187803a00ea8174858c2ce0ea2f8cdd6f0860f044795ef128eadff7151e9ef6e`。
- **Known Issues**：`KI-R03-038` 已 FIXED（C2-T05 调度与交互方法级 RD 证据缺口）；
  `KI-R03-020`、`KI-R03-023`、`KI-R03-024`、`KI-R03-025`、`KI-R03-026`、`KI-M10-005`、
  `KI-M10-006`、`KI-M10-007` 为既有非阻断项；本任务无 BLOCKED。
- **偏离任务书**：无验收范围偏离；本轮只执行 C2-T05。设备证据限定 RD API32，不外推
  API33+、ARM/16KB、OEM、商业应用、SX/XH 或 VA PRO 等价性，`va_pro_equivalent` 保持
  `NOT_PROVEN`；失败优先修复并重跑。
- **实现提交 SHA**：`547ba7ae`（`feat(c2): [C2-T05] close scheduling and interaction evidence gates`）。
- **回执提交**：使用主题 `docs(progress): record [C2-T05] receipt` 单独提交；本回执将 C2-T05
  从 `IN_PROGRESS` 更新为 `DONE`。
- **推送与远端验证**：实现提交先行提交；回执提交完成后，两个提交均非强制推送到
  `origin/feature/t57-r03-va-pro-capability-campaign`，并用 `git ls-remote --heads` 对比最终
  远端 HEAD 与本地 HEAD。
- **遗留风险**：C2-T06 至 C2-T07 尚未补齐对应 RD/API/OEM/商业方法证据；本任务仍不声称
  API33+、ARM/16KB、OEM、SX/XH 或 VA PRO 等价性。
- **黑屏恢复（2026-08-23）**：对照 NewBlackBox `ContextCompat.fix` /
  `IWindowSessionProxy` / `IAudioServiceProxy`。RD API32 上 `ViewRootImpl.<init>`
  调用 `AudioManager.areNavigationRepeatSoundEffectsEnabled()`，CAS audio 拦截器抛
  `VIRTUAL_AUDIO_ROUTING_OPERATION_UNSUPPORTED`，`WindowManagerGlobal.addView` 失败，
  Stub `windows=[]` / `reportedDrawn=false`。已按 NBB 放行该只读查询，并补 Host
  `LayoutParams.packageName` / ContextImpl op-package、保留 `IWindow` Binder。
  夸克 `import-launch` 在 `RD测试` 上 `require_guest_window_drawn` PASS，
  `dumpsys window` 可见 `com.quark.browser/com.ucpro.BrowserActivity` 挂在
  `StubActivity60W1`。`GuestContext.getOpPackageName()` 仍为 Guest（C2-T02）。
- **下一任务**：`C2-T06`；本轮不执行后续任务。

### C2-T06：设备/网络/媒体服务

- **状态**：DONE
- **开始/结束时间**：2026-08-23 02:04 / 2026-08-23 03:04（Asia/Shanghai）
- **执行环境**：Windows 11 amd64；PowerShell；仓库 `D:\github\controlled-android-sandbox`；分支
  `feature/t57-r03-va-pro-capability-campaign`。MuMu 实例按名称 `RD测试` 动态解析成功，状态为
  `device`，resolved serial `127.0.0.1:16416`，model `22041211A`，API 32，ABI
  `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`，boot ID
  `773adc6f-e0aa-4997-a0ee-481a7773a10d`，Android ID `398eea33120cd887`。
- **开始基线**：`feature/t57-r03-va-pro-capability-campaign` @
  `a1a423c9bee79fb2b65270f70cb6c42e172a0529`；开始前完整读取任务书、进度账本、
  `docs/capability/CAPABILITY_CAMPAIGN_WORKFLOW.md`、`docs/COMMIT_IDENTITY_POLICY.md`，
  核对 C2-T05 最后回执、Git/远端 HEAD 和动态 RD 环境；首个依赖满足的 `PENDING` 任务为
  C2-T06。工作区原有 C1-GATE 未跟踪诊断/重试证据保持原样，未纳入本任务。
- **实现摘要**：新增 C2-T06 设备/网络/媒体设计矩阵，按 identity、Telephony、Wi-Fi、
  Connectivity/DNS/VPN、Audio/Media、Bluetooth、Sensor 的 typed getter、callback、权限、
  双用户和清理边界分类；补齐 Telephony 注册/注销、Sensor listener lease、API32 `SensorEvent`
  构造和 callback；把受控 SensorManager 正确绑定到 Guest Context，提供 event/flush/unregister
  生命周期；新增 package-neutral `C2T06DeviceNetworkMediaActivity`、静态门禁和动态 RD runner。
  runner 动态解析设备、安装 APK、准备双用户、执行 20/10 loop、负权限和 guest death probe，
  并保存结构化 profile/APK/cleanup 证据。
- **变更文件**：`app/src/debug/java/com/warden/controlledsandbox/DebugCommandActivity.java`、
  `fixture-basic/src/main/AndroidManifest.xml`、`fixture-basic/src/main/java/com/warden/controlledsandbox/fixture/C2T06DeviceNetworkMediaActivity.java`、
  `sandbox-framework/src/main/java/android/hardware/ControlledSensorManager.java`、
  `sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/DeviceServiceInvocationInterceptor.java`、
  `sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/FrameworkHooks.java`、
  相关 clean-room Android test stubs、`tools/static_android_compile.py`、
  `scripts/check-c2-t06-device-network-media.py`、`tools/capability/run_c2_t06_rd.py`、
  `docs/review/C2_T06_DEVICE_NETWORK_MEDIA_DESIGN.md`、`docs/review/KNOWN_ISSUES.yaml`、
  `verification/catch-up/C2-T06/`、SBOM 和 M5-T16 source-closure 回执。
- **验收命令与结果**：C2-T06 static gate、M5-T8/M5-T10/M5-T14/M5-T15 checks、clean-room
  `python tools/static_android_compile.py`、Python compile、`git diff --check` 全部 PASS；
  `python tools/android_gradle_build_gate.py verify --timeout-seconds 1800` PASS；
  `python scripts/generate-sbom.py --check` PASS；`python scripts/check-m5-t16-source-closure.py`
  PASS。全量本地审计 `artifacts/capability-audit/all/20260822T190035Z/` 分类为 42 gates 中
  34 PASS、8 个既有 KNOWN_ISSUE、0 NEW_REGRESSION；其诊断命令因既有问题返回 1，未误判为
  本任务阻断。
- **设备证据**：`python tools/capability/run_c2_t06_rd.py --instance RD测试 --loops 20
  --clone-loops 10` 最终 PASS；主回执为
  `verification/catch-up/C2-T06/c2-t06-rd-summary.json`，最终 raw logcat、逐步证据和 APK
  SHA-256 位于 `artifacts/capability-audit/catch-up-c2-t06/20260822T190315Z/`。user0 20 loop、
  user1 clone 10 loop、permission-negative 1 loop 均 PASS；user0/user1 profile hash 分别为
  `a77f464d2f843e2c0b686cbd01d076ddf0cf8dbe413b1d97339b534c4907fb1a`、
  `042b5b39a1d5cbd3ff5625b7737c22a87f28ea053c56a9056583687276f37ce0`；网络 callback、sensor
  event/flush、Telephony/Wi-Fi/audio getter、Bluetooth state、DNS/VPN boundary、permission
  check `-1`、显式注销和最终 `networkRegistered=false/sensorRegistered=false/
  telephonyRegistered=false/focusHeld=false` 均有 trace。death probe 记录
  `old_process_dead=true`，user0/clone clear 均 PASS。Media public manager 在该 API32 image
  记录为明确 `NOT_SUPPORTED` boundary，未伪造 Host media truth；Telephony callback 同样保留
  unsupported 边界日志。
- **设备 APK SHA-256**：host
  `fd907711017f2e1b577385cd27e2e25c4fc31b1c2f29453ceabdd0f77c706447`；companion32
  `8f89c4fb6603f4aaf29cc906695e0d385231a6645ee49a83fb27a008a619839b`；fixture64
  `e611017b47e76881c728a6e7d403a2a9a67e1fffe8daf6888e4e53bdce0704e4`；fixture32
  `629682b3f361fd7c0dfc61c74a14449b8c02d82c96b0db928581fca9e768029f`。
- **Known Issues**：`KI-R03-039` 已 FIXED（C2-T06 设备/网络/媒体方法级 RD 证据缺口）；
  `KI-R03-040` 已 FIXED（C2-T05 后 SBOM 漂移）；`KI-R03-020`、`KI-R03-023`、
  `KI-R03-024`、`KI-R03-025`、`KI-R03-026`、`KI-M10-005`、`KI-M10-006` 为既有非阻断项；
  本任务无 BLOCKED。
- **偏离任务书**：无验收范围偏离；本轮只执行 C2-T06。设备证据限定 RD API32，不外推
  API33+、ARM/16KB、OEM、商业应用、SX/XH 或 VA PRO 等价性，`va_pro_equivalent` 保持
  `NOT_PROVEN`；Bluetooth discovery、Media public manager、Telephony callback 和 DNS/VPN
  unavailable adapters 的边界均在设计矩阵与 raw trace 中显式记录；失败均优先修复并重跑。
- **实现提交 SHA**：`048ca1b183f76de37c70c3b4868edfd40e91c562`
  （`feat(c2): [C2-T06] close device network media evidence gates`）。
- **回执提交**：使用主题 `docs(progress): record [C2-T06] receipt` 单独提交；本回执将 C2-T06
  从 `IN_PROGRESS` 更新为 `DONE`。
- **推送与远端验证**：回执提交完成后，两个提交均非强制推送到
  `origin/feature/t57-r03-va-pro-capability-campaign`，并用
  `git ls-remote --heads origin feature/t57-r03-va-pro-capability-campaign` 对比最终本地 HEAD。
- **下一任务**：`C2-T07`；本轮不执行后续任务。

### C2-T07：Biometric 与长尾服务收敛

- **状态**：DONE
- **开始/结束时间**：2026-08-23 03:05 / 2026-08-23 10:40（Asia/Shanghai）
- **执行环境**：Windows amd64；PowerShell；JDK 17；仓库 `D:\github\controlled-android-sandbox`；分支
  `feature/t57-r03-va-pro-capability-campaign`。MuMu 实例按名称 `RD测试` 动态解析成功，状态为
  `device`，resolved serial `127.0.0.1:16416`，model `V2241A`，API 32，ABI
  `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`，boot ID
  `4e8df89b-cebc-41c1-9b1e-d4abf7ac2c31`，Android ID `8acae00bece8090b`。
- **开始基线**：`feature/t57-r03-va-pro-capability-campaign` @
  `2f31db3089d53a0136ea76a2d6191e0ec216dae0`；开始前完整读取任务书、进度账本、
  `docs/capability/CAPABILITY_CAMPAIGN_WORKFLOW.md`、`docs/COMMIT_IDENTITY_POLICY.md`，
  核对 C2-T06 最后回执、Git/远端 HEAD、交接文档和动态 RD 环境；首个依赖满足的任务为
  C2-T07。本任务未启动 C3。
- **实现摘要**：补齐 User/Launcher/Shortcut/AppWidget/UsageStats 的 process-level
  ServiceManager descriptor-validated hooks；实现 API31/32 `LauncherActivityInfoInternal`、
  `AndroidFuture`/`ParceledListSlice` 和 shortcut rank projection；修复 callback identity、
  list carrier flattening、Binder callback method 名称和 `notifyChange`；加入 package-neutral
  application-environment/long-tail fixture、跨用户与死亡替换循环；修复同一 looper 的
  `stopService` 等待死锁、旧 generation shutdown 覆盖新 native hook，以及 Windows
  `MappedByteBuffer` 锁定 `core.jar` 的静态自测清理问题。P2 host-only/unavailable 路径保持
  显式 `NOT_SUPPORTED`/`NOT_APPLICABLE`，不回退 Host 真值。
- **变更文件**：`sandbox-framework` 应用环境工厂、拦截器、值投影和五个 ServiceManager
  hook；`sandbox-runtime` service bridge、generation recovery、native projection；
  `sandbox-native/src/main/cpp/native_policy_jni.cpp`；C2-T07 fixture、任务语义串行化；
  `scripts/check-c2-t07-application-environment.py`、`tools/capability/run_c2_t07_rd.py`、
  `tools/device/t57_rd_common.ps1`；registry/corpus/KNOWN_ISSUES；
  `verification/catch-up/C2-T07/`、SBOM 和 M5-T16 source-closure 回执。
- **验收命令与结果**：`python tools/static_android_compile.py` PASS（全量模块自测）；
  `scripts/build-device-test-apks.ps1 -Online -NoClean` PASS（740 actionable tasks，70 executed）；
  `python scripts/check-c2-t07-application-environment.py` PASS；Python compile、YAML/JSON
  解析、`git diff --check`、`python scripts/generate-sbom.py --check`、
  `python scripts/check-m5-t16-source-closure.py` PASS；`run_c2_t07_rd.py --instance RD测试
  --loops 5 --clone-loops 3` PASS；`t57_rd_full_regression.ps1` 九个案例全部 PASS；无
  FATAL/ANR、stale-session 或 cleanup residue。失败项均先修复并重跑，未记录 BLOCKED。
- **设备证据**：主回执为
  `verification/catch-up/C2-T07/c2-t07-rd-summary.json`，设备 raw evidence 位于
  `artifacts/capability-audit/catch-up-c2-t07/20260823T023548Z/`；本地验收清单为
  `verification/catch-up/C2-T07/c2-t07-local-verification.json`；C1 九案例证据位于
  `verification/catch-up/C2-T07/c1-full-regression/`。C2-T07 user0 5 loop、clone user1
  3 loop、death replacement 2 loop、19 项 long-tail matrix 全部 PASS；C1 full regression
  覆盖 activity-result、framework transport、Job、FGS、recovery、isolated service、
  lifecycle clear/delete/reinstall、cross-ABI recovery 和 cross-ABI lifecycle。
- **设备 APK SHA-256**：host
  `8e2c70d251ce13847b993d12c320148279a39fd79865978f0d70b0070c823406`；companion32
  `5edaf1c0e083faafe5bbba0e7d30631f9bd40cd5194aa5a21dd4952c05b004e8`；fixture
  `c52d4302e8d29ba9c6bdb58f1aa25b94b870895c2516b76bd1f6077f5577083e`。
- **Known Issues**：`KI-R03-041` 已 FIXED（应用环境/长尾 API32 RD 证据缺口）；
  `KI-R03-020`、`KI-R03-023`、`KI-R03-024`、`KI-R03-025`、`KI-R03-026`、`KI-M10-005`、
  `KI-M10-006` 继续作为既有非阻断项；本任务无 BLOCKED。
- **偏离任务书**：无验收范围偏离；证据严格限定动态 RD API32，不外推 API33-36、OEM/HAL、
  ARM/16KB、商业应用、SX/XH 或 VA PRO 等价性；`va_pro_equivalent` 保持 `NOT_PROVEN`。
  C2 阶段门禁以 C2-T01..T07 的 RD_API32 L3 证据、P2 显式边界和 C1 九案例回归通过关闭。
- **实现提交 SHA**：`b6d9dafadf63ebdbd01bfc15dd297766ca71d91e`
  （`feat(c2): [C2-T07] close application environment evidence gates`）。
- **回执提交**：使用主题 `docs(progress): record [C2-T07] receipt` 单独提交；本回执将 C2-T07
  从 `IN_PROGRESS` 更新为 `DONE`，并将 C2 阶段更新为 `DONE`。
- **推送与远端验证**：实现提交已先行非强制推送；本回执提交完成后，两个提交均将再次核验
  `git ls-remote --heads origin feature/t57-r03-va-pro-capability-campaign`，确保远端 HEAD
  与本地 HEAD 一致。
- **遗留风险**：API33-36、OEM/HAL、ARM/16KB、商业应用、SX/XH 和 VA PRO equivalence
  仍未证明；这些是后续阶段范围，不影响本次 C2-T07 DONE 判定。
- **下一任务**：`C3-T01`；本轮按用户指令停止，不执行 C3。

### C3-T01：Native 绕过与兼容 corpus

- **状态**：DONE
- **开始/结束时间**：2026-08-23 10:40 / 2026-08-23 11:31（Asia/Shanghai）
- **执行环境**：Windows amd64；PowerShell；JDK 17；Gradle 8.13；Android SDK 36；NDK
  `27.2.12479018`；CMake 3.22.1；仓库 `D:\github\controlled-android-sandbox`；分支
  `feature/t57-r03-va-pro-capability-campaign`。MuMu 通过实例名 `RD测试` 动态解析，状态为
  `device`，本次 resolved serial `127.0.0.1:16416`，model `V2241A`，API 32，ABI
  `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`，boot ID
  `4e8df89b-cebc-41c1-9b1e-d4abf7ac2c31`，Android ID `8acae00bece8090b`；runner 使用
  `MUMU_ROOT=D:\install\Netease\MuMu` 发现安装根目录，未在代码中固化历史 ADB endpoint。
- **开始基线**：`feature/t57-r03-va-pro-capability-campaign` @
  `d9d89ca19e86cf97542729e63f698c9edb4bfef6`；远端 HEAD 同步；开始前完整读取任务书、
  本账本、`docs/capability/CAPABILITY_CAMPAIGN_WORKFLOW.md`、
  `docs/COMMIT_IDENTITY_POLICY.md`，核对 C2-T07 最后回执、Git/远端和动态 RD 环境；首个
  依赖满足的 PENDING 任务为 C3-T01。本轮只执行 C3-T01。
- **实现摘要**：新增 test-only `NATIVE-ADV-011`，覆盖 `open/openat/openat2`、
  `faccessat/faccessat2`、`stat/fstatat`、xattr、`getcwd/chdir`、`realpath`、安全的
  pipe `ioctl(FIONREAD)`、libc/`syscall()` 变体和 foreign Host path 负向检查；保留既有
  execve、socket、loader、procfs/FD、Binder、raw SVC/int 案例。边界矩阵扩展为 55 条并
  新增 `SYS-IOCTL`，每一条均有字段、风险和 `TRUSTED_COMPAT`/`ISOLATED_HOSTILE` 分类；
  新增静态 C3-T01 gate、动态 RD runner、设计文档和四 ABI build/evidence 校验。未修改
  production native interceptor；`BYPASS_CONFIRMED` 仅表示发现，不升级为 hostile isolation
  或 VA PRO 等价性。
- **变更文件**：`fixture-basic/src/main/cpp/adversarial_native.cpp`、
  `docs/native/T57_R03_NATIVE_BOUNDARY_MATRIX.yaml`、
  `docs/review/C3_T01_NATIVE_CORPUS_DESIGN.md`、
  `scripts/check-c3-t01-native-corpus.py`、`scripts/check-native-boundary-matrix.py`、
  `tools/capability/run_c3_t01_rd.py`、`tools/capability/run_native_adversarial_rd.py`、
  `verification/catch-up/C3-T01/`、`verification/sbom.json`；本回执同步更新
  `docs/capability/CAPABILITY_REGISTRY.yaml` 的证据路径和 `last_verified_commit`。
- **验收命令与结果**：`python scripts/check-c3-t01-native-corpus.py`、既有 native
  boundary/files-loader/file-hooks/ABI-companion/hostile-profile/enforcement-P0C gates、
  Python compile、YAML/JSON 解析、`git diff --check`、
  `python scripts/generate-sbom.py --check`、`python scripts/check-m5-t16-source-closure.py`
  全部 PASS；`scripts/build-device-test-apks.ps1 -Online -NoClean` PASS（740 actionable
  tasks，36 executed），因共享 C++ 源的 32-bit Gradle 增量输入需显式刷新，随后
  `:fixture-compat32:assembleDebug --rerun-tasks` PASS（38/38 tasks）。
- **设备证据**：`$env:MUMU_ROOT='D:\install\Netease\MuMu'; python
  tools/capability/run_c3_t01_rd.py --instance-name 'RD测试'` PASS。主回执为
  `verification/catch-up/C3-T01/c3-t01-rd-summary.json`，本地验收清单为
  `verification/catch-up/C3-T01/c3-t01-local-verification.json`，raw logcat、动态环境、
  APK/SO hashes 位于 ignored `artifacts/capability-audit/catch-up-c3-t01/20260823T032245Z/`。
  direct 64-bit `x86_64` 的 11/11 案例均 `PASS_COMPAT`；direct 32-bit `x86` 的 11/11
  案例均 `PASS_COMPAT`；in-sandbox `x86_64` 的 11/11 案例均有结果，其中
  `NATIVE-ADV-003`、`NATIVE-ADV-011` 为预期 `BYPASS_CONFIRMED`，fork/exec 相关案例按策略
  `BLOCKED_BY_POLICY`，`NATIVE-ADV-011` 的 `negative_host_path=DENIED`。四个 ABI
  `arm64-v8a/x86_64/armeabi-v7a/x86` 的 fixture/payload native libraries 均存在并记录
  SHA-256。设备 APK SHA-256：host
  `8e2c70d251ce13847b993d12c320148279a39fd79865978f0d70b0070c823406`；companion32
  `5edaf1c0e083faafe5bbba0e7d30631f9bd40cd5194aa5a21dd4952c05b004e8`；fixture64
  `78dffc7797f396e672caefcc5000ba2434958914c12eb9a8cac719a94035d52b`；fixture32
  `726a5da7f7d7aa9668fb43ae874aca349a02830cccae4e0824a8e71cefc8d8bd`。
- **附加 host 自测说明**：`scripts/test-native.sh` 在 PowerShell 的 WSL launcher 下因无可用
  host `g++` 立即退出；改用 Git Bash 后确认同样缺少 `g++`；WSL Ubuntu 的前置 native
  self-tests 通过，但将 Windows NDK headers 作为 host include 会与 glibc/JNI 产生既有
  harness 冲突。因此该附加 host harness 未作为本任务 PASS 依据；本任务的 Android NDK
  四 ABI Gradle 编译和 RD 动态验收均 PASS，未发现 C3-T01 新 runtime regression，也未
  记录 BLOCKED。
- **Known Issues**：继续显式保留 `KI-T57-009`、`KI-R03-023`、
  `KI-R03-NATIVE-001` 至 `KI-R03-NATIVE-009`、`KI-R03-NATIVE-ENF-001` 等既有非阻断
  架构/证据边界；raw syscall/SVC、identity/process、procfs/FD、metadata/cwd、custom
  loader、Binder device 和 seccomp/user-notify 不因本任务而关闭；无新增 Known Issue。
- **偏离任务书**：无验收范围偏离；仅因共享源的 32-bit 增量构建显式重跑一次兼容 fixture，
  不改变任务范围。证据严格限定 RD API32、当前 MuMu image 和静态四 ABI build，不外推
  API33+、ARM 真机/16 KB、OEM/HAL、商业应用、SX/XH 或 VA PRO 等价性，
  `va_pro_equivalent` 保持 `NOT_PROVEN`；失败均先修复并重跑。
- **实现提交 SHA**：`8b4233623a0e09028969a88370a68f3ae0137c54`
  （`test(native): [C3-T01] establish native compatibility corpus`）。
- **回执提交**：使用主题 `docs(progress): record [C3-T01] receipt` 单独提交；本回执将
  C3-T01 从 `IN_PROGRESS` 更新为 `DONE`，并将 C3 保持为 `IN_PROGRESS`，下一任务为
  `C3-T02`。
- **推送与远端验证**：实现提交已先行非强制推送；回执提交完成后，两个提交均非强制推送到
  `origin/feature/t57-r03-va-pro-capability-campaign`，并用 `git ls-remote --heads` 对比
  最终远端 HEAD 与本地 HEAD。
- **遗留风险**：C3-T02/T03/T04/T05/T06 尚未执行；API33+、ARM/16KB、OEM/HAL、商业
  应用、SX/XH 和 VA PRO equivalence 仍未证明；hostile native 隔离仍由后续 C3-T04/T05
  决策与验收负责。
- **下一任务**：`C3-T02`；本轮按用户指令停止，不执行后续任务。

### C3-T02：文件/procfs/网络/FD 生命周期

- **状态**：DONE
- **开始/结束时间**：2026-08-23 11:40 / 2026-08-23 12:53（Asia/Shanghai）
- **执行环境**：Windows amd64；PowerShell；JDK 17；Gradle 8.13；Android SDK 36；NDK
  `27.2.12479018`；CMake 3.22.1；仓库 `D:\github\controlled-android-sandbox`；分支
  `feature/t57-r03-va-pro-capability-campaign`。开始前完整读取任务书、本账本、
  `docs/capability/CAPABILITY_CAMPAIGN_WORKFLOW.md` 和 `docs/COMMIT_IDENTITY_POLICY.md`，
  核对 Git/远端/最后 C3-T01 回执；MuMu 通过实例名 `RD测试` 动态解析，状态为
  `device`，resolved serial `127.0.0.1:16416`，model `V2241A`，API 32，ABI
  `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`，boot ID
  `4e8df89b-cebc-41c1-9b1e-d4abf7ac2c31`，Android ID `8acae00bece8090b`；本轮 runner
  使用 `MUMU_ROOT=D:\install\Netease\MuMu`，代码和 runner 未固化历史 ADB endpoint。
- **开始基线**：`feature/t57-r03-va-pro-capability-campaign` @
  `ffb69277a5efa253a929beeb458f5bb78bdb3517`；远端 HEAD 同步；最后完成回执为 C3-T01，
  实现提交 `8b4233623a0e09028969a88370a68f3ae0137c54`，回执主题为
  `docs(progress): record [C3-T01] receipt`；依赖满足的首个 PENDING 任务为 C3-T02，
  本轮只执行 C3-T02。
- **实现摘要**：新增 package-neutral test-only 文件、procfs、网络和 FD 生命周期 corpus，
  覆盖 dfd/relative path/symlink escape、maps/smaps/fd/task/cgroup/fdinfo、`/proc/net`、
  DNS/socket trace、dup/dup2/dup3/F_DUPFD_CLOEXEC、SCM_RIGHTS、close-on-exec、clear/death
  和 raw syscall 边界；生产 native procfs/file/process 访问对未知或 HostInternal FD
  fail-closed，补齐 fdinfo 快照路径和 Guest fdinfo 权威元数据兜底；网络接收对匿名 Unix
  socket 使用 `getpeername/getsockname` 识别并在无地址时保持安全一致性；sandbox 夹具建立
  真实窗口后异步运行，避免生命周期门控竞态；新增静态 gate、RD runner、设计文档和证据。
- **变更文件**：`sandbox-native/src/main/cpp/native_procfs.cpp`、
  `sandbox-native/src/main/cpp/native_file_system.cpp`、
  `sandbox-native/src/main/cpp/native_process_interceptors.cpp`、
  `sandbox-native/src/main/cpp/native_network_interceptors.cpp`、
  `fixture-basic/src/main/cpp/c3_t02_native.cpp`、
  `fixture-basic/src/main/cpp/CMakeLists.txt`、
  `fixture-basic/src/main/java/com/warden/controlledsandbox/fixture/C3T02FileProcNetworkFdActivity.java`、
  `fixture-basic/src/main/java/com/warden/controlledsandbox/fixture/C3T02FileProcNetworkFdProbe.java`、
  两个 fixture manifest、`app/src/debug/java/com/warden/controlledsandbox/DebugCommandActivity.java`、
  `docs/review/C3_T02_FILE_PROC_NETWORK_FD_DESIGN.md`、
  `scripts/check-c3-t02-file-proc-network-fd.py`、`tools/capability/run_c3_t02_rd.py`、
  `scripts/verify-catch-up-continuation.py`、`verification/catch-up/C0-T01/continuation-preflight.json`、
  `verification/catch-up/C3-T02/`。
- **验收命令与结果**：`python scripts/check-c3-t02-file-proc-network-fd.py`、既有
  native files-loader/file-hooks/network-audio/M5 network correctness gates、
  `python -m py_compile tools/capability/run_c3_t02_rd.py`、
  `python tools/static_android_compile.py`、最终
  `:fixture-basic:assembleDebug :fixture-compat32:assembleDebug :sandbox-native:assembleDebug
  :app:assembleDebug :sandbox-companion32:assembleDebug --no-daemon`、`git diff --check`
  均 PASS；静态 Android 编译的 declared Host source/self-test receipt 为 PASS，仅有既有
  unchecked/deprecation/try-resource warnings。
- **设备证据**：`$env:MUMU_ROOT='D:\install\Netease\MuMu'; python
  tools/capability/run_c3_t02_rd.py` PASS，证据目录为 ignored
  `artifacts/capability-audit/catch-up-c3-t02/20260823T044723Z/`；主回执为
  `verification/catch-up/C3-T02/c3-t02-rd-summary.json`，本地验收清单为
  `verification/catch-up/C3-T02/c3-t02-local-verification.json`，续接预检为
  `verification/catch-up/C0-T01/continuation-preflight.json`。direct64/direct32 所有
  7/7 案例为 `PASS_COMPAT`；sandbox 所有 7/7 案例均有预期结果，其中 FD exec 为
  `BLOCKED_BY_POLICY`，raw syscall 为显式 `BYPASS_CONFIRMED` 且带
  `UNMEDIATED_DIRECT_SYSCALL_EXPOSED`；procfs 未知项拒绝、`host_leak=0`，SCM_RIGHTS
  收发和 close convergence 通过；clear/death 的 Guest 结果清除和残留扫描通过。
  APK SHA-256：host `31e7e54e0fca3daff85cc355a69648be4ed3abba7236801b36ebd2ed248acf74`；
  companion32 `485f90191c77acd03ed09a2a91872d4850ce6739302a2a6ad593a063f550842d`；
  fixture64 `42cb2f03127ebcf50a44ac5985619e5f3f3c2ede9fdddf1c250951e47f1a8923`；
  fixture32 `9e949587f6ed039597620a6e65e1477b42255418c5f6359a03e1d4faf559c833`。
- **失败修复记录**：早期 SCM_RIGHTS 的匿名 socket 接收路径出现 EAGAIN，补齐匿名 Unix
  peer/local 识别与无地址 equality 处理；路径型 Unix socket 在 sandbox 映射中出现 ENOENT，
  恢复为匿名 `socketpair`；procfs fdinfo 快照落入错误目录导致 EIO，修正为
  `fdinfo/<descriptor>`；Activity launch gate 竞态通过真实 content view 和异步夹具执行修复。
  每项均修复后重编译、重跑；未记录 BLOCKED。
- **Known Issues / 偏离任务书**：raw direct syscall 仍明确暴露为未调解边界，不把它计为
  hostile isolation PASS；unknown proc leaves 继续 fail-closed 而非完整虚拟化；Provider/Binder
  FD producer、kernel inheritance 和 hostile network 仍受 `KI-R03-NATIVE-009`、
  `KI-R03-NATIVE-ENF-001` 等既有边界约束。证据严格限定 RD API32、当前 MuMu image 和
  四 ABI build，不外推 API33+、ARM 真机/16 KB、OEM/HAL、商业应用、SX/XH 或 VA PRO
  等价性，`va_pro_equivalent` 保持 `NOT_PROVEN`；无验收范围偏离。
- **实现提交 SHA**：`566fadc60437a22c36fc985bbf54dce77c177173`
  （`test(native): [C3-T02] close file/proc/network/FD lifecycle gaps`）。
- **回执提交**：使用主题 `docs(progress): record [C3-T02] receipt` 单独提交；本回执将
  C3-T02 从 `IN_PROGRESS` 更新为 `DONE`，C3 保持 `IN_PROGRESS`，下一任务为 C3-T03。
- **推送与远端验证**：实现提交先行非强制推送；回执提交随后以独立提交非强制推送到
  `origin/feature/t57-r03-va-pro-capability-campaign`，并用 `git ls-remote --heads`
  验证最终远端 HEAD 与本地 HEAD 一致。
- **遗留风险**：C3-T03/T04/T05/T06 尚未执行；API33+、ARM/16KB、OEM/HAL、商业应用、
  SX/XH 和 VA PRO equivalence 仍未证明；hostile native 隔离后续由 C3-T04/T05 决策与验收。
- **下一任务**：`C3-T03`；本轮只执行 C3-T02，不执行后续任务。

### C3-T03：四 ABI、16 KB page 与 native Camera/Media

- **状态**：DONE
- **开始/结束时间**：2026-08-23 13:10 / 2026-08-23 14:12（Asia/Shanghai）
- **执行环境**：Windows amd64；PowerShell；JDK 17；Gradle 8.13；Android SDK 36；NDK
  `27.2.12479018`；CMake 3.22.1；仓库 `D:\github\controlled-android-sandbox`；分支
  `feature/t57-r03-va-pro-capability-campaign`。开始前完整读取任务书、本账本、
  `docs/capability/CAPABILITY_CAMPAIGN_WORKFLOW.md` 和 `docs/COMMIT_IDENTITY_POLICY.md`，
  核对 Git/远端/最后 C3-T02 回执；MuMu 通过实例名 `RD测试` 动态解析，状态为
  `device`，resolved serial `127.0.0.1:16416`，model `V2241A`，API 32，ABI
  `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`，manufacturer `vivo`，Android release `12`，
  boot ID `4e8df89b-cebc-41c1-9b1e-d4abf7ac2c31`，Android ID `8acae00bece8090b`；本轮
  使用 `MUMU_ROOT=D:\install\Netease\MuMu`，执行器未固化 ADB endpoint。
- **开始基线**：`feature/t57-r03-va-pro-capability-campaign` @
  `ffcd3d1c142a30c7920aaf77b605ce9ff3386502`；远端 HEAD 同步；最后完成回执为 C3-T02，
  实现提交 `566fadc60437a22c36fc985bbf54dce77c177173`，其回执提交为
  `ffcd3d1c142a30c7920aaf77b605ce9ff3386502`；依赖满足的首个 PENDING 任务为 C3-T03，
  本轮只执行 C3-T03。
- **DISCOVER / CLASSIFY**：登记 `KI-R03-042`（`TEST_EVIDENCE_GAP`，四 ABI ELF/
  page/native media/Companion32 关联证据缺口）和 `KI-R03-043`
  （`ENVIRONMENT_BLOCKED`，ARM/16 KB 动态环境缺失）。设计文档明确静态 ELF 16 KB
  对齐、RD 动态结果和 ARM/16 KB 环境缺口分开计量，不把 x86/4 KB PASS 外推为 ARM、
  16 KB、API33+、OEM 或 VA PRO 等价性。
- **实现摘要**：为 fixture、Host native-enforcement、sandbox-native 和 Companion32
  的 native targets 加入 `-Wl,-z,max-page-size=16384`；新增纯 Python ELF/class/machine/
  `DT_NEEDED`/`PT_LOAD` 静态报告；新增 package-neutral `C3T03NativeMediaActivity` 和
  JNI fixture，覆盖编译 ABI、page size、late `dlopen`、真实 `ANativeWindow` buffer/
  ImageReader callback、NDK `AMediaCodec` AVC encode/dequeue/release 和 cleanup；Host
  debug command 增加 in-sandbox 路径；RD runner 动态解析 `RD测试`，关联 direct64、
  direct32、sandbox、Companion32 identity/load/death-recovery 及原始日志。同步补齐
  static Android compiler 的 PixelFormat/Image/Plane API stubs、SBOM 和 capability registry。
- **变更文件**：`app/src/debug/cpp/CMakeLists.txt`、
  `app/src/debug/java/com/warden/controlledsandbox/DebugCommandActivity.java`、
  `fixture-basic/src/main/AndroidManifest.xml`、`fixture-basic/src/main/cpp/CMakeLists.txt`、
  `fixture-basic/src/main/cpp/c3_t03_native.cpp`、
  `fixture-basic/src/main/java/com/warden/controlledsandbox/fixture/C3T03NativeMediaActivity.java`、
  `fixture-compat32/src/main/AndroidManifest.xml`、`sandbox-native/src/main/cpp/CMakeLists.txt`、
  `sandbox-companion32/src/main/cpp/CMakeLists.txt`、`tools/static_android_compile.py`、
  `scripts/check-c3-t03-abi-elf.py`、`tools/capability/run_c3_t03_rd.py`、
  `docs/review/C3_T03_ABI_16KB_NATIVE_MEDIA_DESIGN.md`、
  `docs/review/KNOWN_ISSUES.yaml`、`docs/capability/CAPABILITY_REGISTRY.yaml`、
  `verification/sbom.json`、`verification/catch-up/C0-T01/continuation-preflight.json` 和
  `verification/catch-up/C3-T03/`。
- **验收命令与结果**：`python scripts/check-c3-t03-abi-elf.py` PASS（host 4、fixture64
  6、fixture32 6、companion32 4 个 ELF）；`python scripts/check-native-abi-companion.py`、
  `python scripts/check-m5-t2-cross-width-runtime.py`、`python scripts/check-c2-t04-camera.py`
  均 PASS；`python scripts/verify-device-test-artifacts.py --android-tools --profile device-lab`
  PASS（locked APK set：host 4487692B、fixture64 2331726B、fixture32 884796B、
  companion32 5026291B）；最终无缓存 Gradle 四 APK 构建 PASS（238 actionable tasks，
  32 executed）；`python tools/static_android_compile.py` PASS，receipt 为 160/160
  self-tests PASS；`python scripts/generate-sbom.py --check` PASS（14 components）；Python
  compile 和 `git diff --check` PASS（仅既有 compiler warnings/CRLF 提示）。
- **设备证据**：最终 runner 命令为
  `$env:MUMU_ROOT='D:\install\Netease\MuMu'; python tools/capability/run_c3_t03_rd.py --instance RD测试`，
  返回 PASS，raw 目录为 ignored
  `artifacts/capability-audit/catch-up-c3-t03/20260823T060630Z/`；主回执为
  `verification/catch-up/C3-T03/c3-t03-rd-summary.json`，本地验收清单为
  `verification/catch-up/C3-T03/c3-t03-local-verification.json`，ELF 报告为
  `verification/catch-up/C3-T03/c3-t03-abi-report.json`，环境回执为
  `verification/catch-up/C3-T03/c3-t03-environment-block.json`。direct64 `x86_64`、
  direct32 `x86`、sandbox `x86_64` 均 PASS：late-dlopen marker 为
  `FIXTURE_ADV_PAYLOAD_V1`，native Surface 为 `64x48/stride=64`，ImageReader 为
  `64x48/12288 bytes/rowStride=256/pixelStride=4`，codec 为 `inputBytes=384`、
  `outputBytes=29`、`formatChanged=1`，cleanup 均 PASS。Companion32 为
  `bitness=32;abi=x86;hookLibrary=controlled_sandbox_native32`，cross-width 进程死亡
  恢复 PASS，generation `1 -> 2`，`GUEST_PROCESS_DISCONNECTED` marker 存在。
- **APK SHA-256**：最终设备 campaign 对应产物为 host
  `812F3F625FAF5B7178EAE9803179F252CDB4357E9ADE5BF80ADE7ED5C5697470`；fixture64
  `204DF526209B4F3CC6490B8A5CBEF1990C17925190CC1316C14C741016067D5A`；fixture32
  `5564918E1AF297ECBDBA85548E36F1B7FB655423F4CB17358EC8622F3FDA9E10`；companion32
  `57B1DBDB765E233376D35F1BF2AAE74F325520A82FF12C7554BA8EF1C281EF1D`。
- **失败修复记录**：首轮包装器读取 PowerShell UTF-8 BOM 回执失败，改为 `utf-8-sig`
  并按结果文件动态发现；MuMu 曾出现一次 `GUEST_PREPARED_MARKER_TIMEOUT` 瞬态，单独
  重跑通过，runner 增加仅针对该 marker timeout 的一次独立目录重试并保留两次原始证据；
  static Android compiler 首次因 stub 缺少 PixelFormat/Image/Plane API 失败，补齐 stubs
  后 160 项全部通过。每项均修复后重编译、重跑；最终 campaign 首次尝试 PASS，未记录
  BLOCKED。
- **Known Issues**：`KI-R03-042` 已关闭为 `FIXED`，证据包含设计、静态 gate、RD
  summary/local verification；`KI-R03-043` 保持 `RECORDED`，设备 `getconf PAGE_SIZE=4096`，
  `page_16kb_dynamic_status=ENVIRONMENT_NOT_AVAILABLE`、ARM32/ARM64 为
  `UNVERIFIED_RUNTIME`。既有 `KI-R03-NATIVE-001` 等 raw syscall/SVC、procfs/FD、
  custom loader、Binder device、seccomp/user-notify 和 hostile native 边界不因本任务关闭。
- **偏离任务书**：无验收范围偏离。静态四 ABI 16 KB PT_LOAD 对齐通过，但当前 RD 仅为
  API32 x86/x86_64、4 KB page；不宣称 ARM32/ARM64 动态 PASS、16 KB 动态 PASS、API33+、
  OEM/HAL、商业应用、SX/XH 或 VA PRO equivalence，`va_pro_equivalent` 保持 `NOT_PROVEN`。
- **实现提交 SHA**：`372fc11a9548184e568de213c4c8264f7bf39771`
  （`test(native): [C3-T03] close ABI 16KB native media gates`）。
- **回执提交**：使用主题 `docs(progress): record [C3-T03] receipt` 单独提交；本回执将
  C3-T03 从 `IN_PROGRESS` 更新为 `DONE`，C3 保持 `IN_PROGRESS`，下一任务为 C3-T04；
  capability registry 的 `last_verified_commit` 同步更新为实现提交 SHA。
- **推送与远端验证**：实现提交已先行非强制推送，推送后
  `git ls-remote --heads origin feature/t57-r03-va-pro-capability-campaign` 等于
  `372fc11a9548184e568de213c4c8264f7bf39771`；回执提交完成后将再次非强制推送两个提交，
  并以最终 `git ls-remote --heads` 对比本地 HEAD。
- **遗留风险**：C3-T04/T05/T06 尚未执行；API33+、ARM/16KB、OEM/HAL、商业应用、SX/XH
  和 VA PRO equivalence 仍未证明；hostile native 隔离、raw syscall/SVC 和完整 kernel
  boundary 仍按既有 Known Issues 及后续 C3-T04/C3-T05 处理。
- **下一任务**：`C3-T04`；本轮只执行 C3-T03，不执行后续任务。

### C3-T04：Hostile native 隔离与 Broker-only 能力

- **状态**：DONE
- **开始/结束时间**：2026-08-23 15:30 / 2026-08-23 15:55（Asia/Shanghai）
- **执行环境**：Windows amd64；PowerShell；JDK 17；Gradle 8.13；Android SDK 36；NDK
  `27.2.12479018`；CMake 3.22.1；仓库 `D:\github\controlled-android-sandbox`；分支
  `feature/t57-r03-va-pro-capability-campaign`。开始前完整读取任务书、本账本、
  `docs/capability/CAPABILITY_CAMPAIGN_WORKFLOW.md` 和 `docs/COMMIT_IDENTITY_POLICY.md`，
  核对 Git/远端/最后 C3-T03 回执；MuMu 通过实例名 `RD测试` 动态解析，状态为
  `device`，resolved serial `127.0.0.1:16416`，model `V2241A`，API 32，ABI
  `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`，boot ID
  `4e8df89b-cebc-41c1-9b1e-d4abf7ac2c31`，Android ID `8acae00bece8090b`；本轮
  使用 `MUMU_ROOT=D:\install\Netease\MuMu`，执行器未固化 ADB endpoint。
- **开始基线**：`feature/t57-r03-va-pro-capability-campaign` @
  `6e98c0e143fc32fc1d4774483843b948df68b7e3`；远端 HEAD 同步；最后完成回执为 C3-T03，
  实现提交 `372fc11a9548184e568de213c4c8264f7bf39771`，回执提交
  `6e98c0e143fc32fc1d4774483843b948df68b7e3`；依赖满足的首个 PENDING 任务为 C3-T04。
- **DISCOVER / CLASSIFY**：登记 `KI-R03-044`（`TEST_EVIDENCE_GAP`，hostile 攻击矩阵
  缺少 other-Guest/core storage、inherited FD、`/dev/binder`、ptrace/clone/execve、
  capability expiry/replay 与 death 撤销的 RD 证据）。既有 `KI-R03-NATIVE-001/006/008`
  与 `KI-R03-NATIVE-ENF-001` 保持架构边界，不把 PLT 命中或残余内核限制改名为 PASS。
- **实现摘要**：扩展 isolated UID + production Broker + deny-only seccomp 的
  package-neutral 攻击矩阵；JNI 记录 libc/syscall/raw 与 ptrace/execve/fork/
  binder/inherited-FD/core/other-Guest 探针；Broker ledger 在设备上证明
  grant/scope/generation/expiry/replay；unbind 后校验 isolated PID 消失和 token
  撤销。修复 `KI-R03-040` 非法 capability 标签使治理 validator 通过。
- **变更文件**：`HostileProductionCampaign.java`、`NativeEnforcementChild.java`、
  `NativeEnforcementNative.java`、`NativeEnforcementIsolatedService.java`、
  `DebugCommandActivity.java`、`enforcement_native.cpp`、
  `HostileCapabilityRegistrySelfTest.java`、
  `docs/review/C3_T04_HOSTILE_NATIVE_ISOLATION_DESIGN.md`、
  `scripts/check-c3-t04-hostile-isolation.py`、`tools/capability/run_c3_t04_rd.py`、
  `docs/review/KNOWN_ISSUES.yaml`、`docs/native/T57_R03_NATIVE_BOUNDARY_THREAT_MODEL.md`、
  `docs/capability/CAPABILITY_REGISTRY.yaml`、`verification/catch-up/C3-T04/`。
- **验收命令与结果**：`python scripts/check-c3-t04-hostile-isolation.py`、
  `python scripts/check-native-hostile-profile.py`、
  `python scripts/check-native-enforcement-poc.py`、
  `python scripts/check-native-boundary-matrix.py`、
  `python tools/static_android_compile.py`（含 hostile capability self-test）、
  `python tools/capability/validate_campaign_infra.py`、
  `scripts/build-device-test-apks.ps1 -Online -NoClean`、
  `python scripts/generate-sbom.py --check` 全部 PASS。
  `$env:MUMU_ROOT='D:\install\Netease\MuMu'; python tools/capability/run_c3_t04_rd.py --instance RD测试`
  最终 PASS。首次 runner 因 evidence schema 拒绝额外字段失败，已把 case 状态写入
  notes/local verification 后重跑通过；未记录 BLOCKED。
- **设备证据**：主回执 `verification/catch-up/C3-T04/c3-t04-rd-summary.json`，本地
  验收 `verification/catch-up/C3-T04/c3-t04-local-verification.json`，raw 目录
  `artifacts/capability-audit/catch-up-c3-t04/20260823T075418Z/`。
  `C3-T04-ISO-001=PASS_ISOLATED`；core/other-Guest=`DENIED_BY_KERNEL_POLICY`；
  ungranted=`DENIED`；inherited FD=`PASS_NO_LEAK`；ptrace/execve/socket=
  `DENIED_BY_SECCOMP`；Broker grant/scope/rev/expiry/replay 与 death=`PASS`；
  residual binder=`KERNEL_LIMIT_EXPOSED`，clone=`KERNEL_LIMIT_EXPOSED_SAME_UID`。
- **APK SHA-256**：host
  `17875705e6f95c5dc9ffdfe8b705e4989a6965c2da5f6d9ef6011e5328ba6efa`；companion32
  `57b1dbdb765e233376d35f1bf2aae74f325520a82ff12c7554ba8ef1c281ef1d`；fixture
  `18cb010cbe42159aff720beb716a2d25f5c0eaf248b9c37e93098de13caed53e`；fixture32
  `5564918e1af297ecbdba85548e36f1b7fb655423f4cb17358ec8622f3fda9e10`。
- **Known Issues**：`KI-R03-044` 已 `FIXED`；`KI-R03-NATIVE-001/006/008` 与
  `KI-R03-NATIVE-ENF-001` 保持既有非阻断架构边界；`KI-R03-040` 仅修正非法
  capability 标签。`va_pro_equivalent` 保持 `NOT_PROVEN`。
- **偏离任务书**：无验收范围偏离。本轮按用户指令将执行 3 个任务，本回执只关闭
  C3-T04；证据限定 RD API32，不外推 API33+、ARM/16KB、OEM、SX/XH 或 VA PRO。
- **实现提交 SHA**：`22716fbfec845b288ea119c6ec6be678fc23915f`
  （`test(native): [C3-T04] close hostile isolation attack matrix`）。
- **回执提交**：使用主题 `docs(progress): record [C3-T04] receipt` 单独提交；
  本回执将 C3-T04 从 `PENDING` 更新为 `DONE`，C3 保持 `IN_PROGRESS`，下一任务为
  `C3-T05`。
- **推送与远端验证**：实现提交先行提交；回执提交完成后，两个提交均非强制推送到
  `origin/feature/t57-r03-va-pro-capability-campaign`，并用 `git ls-remote --heads`
  对比最终远端 HEAD 与本地 HEAD。
- **遗留风险**：C3-T05/T06 尚未执行；`/dev/binder` 观察与 same-UID clone 仍是
  内核残余限制；user-notify 与 ART/Xposed 由后续条件任务决策；API33+、ARM/16KB、
  OEM/HAL、商业应用、SX/XH 和 VA PRO equivalence 仍未证明。
- **下一任务**：`C3-T05`。

### C3-T05：seccomp/user-notify 可行性决策

- **状态**：NOT_APPLICABLE
- **开始/结束时间**：2026-08-23 15:56 / 2026-08-23 16:05（Asia/Shanghai）
- **执行环境**：Windows amd64；PowerShell；仓库 `D:\github\controlled-android-sandbox`；
  分支 `feature/t57-r03-va-pro-capability-campaign`。MuMu `RD测试` 动态解析，serial
  `127.0.0.1:16416`，model `V2241A`，API 32，kernel `5.4.32-perf-gda349bfae95e`，
  boot ID `4e8df89b-cebc-41c1-9b1e-d4abf7ac2c31`。
- **开始基线**：`3fd633479880c8745d70ed1cd7ff1a83ba8a5146`；远端同 HEAD；上一任务
  `C3-T04` DONE，实现提交 `22716fbfec845b288ea119c6ec6be678fc23915f`。
- **实现摘要**：对 RD 内核做 `/proc/config.gz` 与 uname 探测；确认
  `CONFIG_SECCOMP_FILTER=y` 且 `CONFIG_SECCOMP_USER_NOTIF` 未编译。ADR 决定普通
  APK 不实现 user-notify 监督器；产品 hostile 边界保持 C3-T04 isolated UID +
  Broker + deny-only BPF。Option D/E 保留为 `REQUIRES_PRIVILEGE` / OEM SKU。
- **变更文件**：`docs/review/C3_T05_SECCOMP_USER_NOTIFY_ADR.md`、
  `scripts/check-c3-t05-seccomp-decision.py`、`tools/capability/run_c3_t05_rd.py`、
  `verification/catch-up/C3-T05/`、`docs/review/KNOWN_ISSUES.yaml`、
  `docs/capability/CAPABILITY_REGISTRY.yaml`。
- **验收命令与结果**：`python scripts/check-c3-t05-seccomp-decision.py` PASS；
  `$env:MUMU_ROOT='D:\install\Netease\MuMu'; python tools/capability/run_c3_t05_rd.py --instance RD测试`
  PASS，`decision=NOT_APPLICABLE`。未修改生产运行时。
- **设备证据**：`verification/catch-up/C3-T05/c3-t05-rd-summary.json` 与
  `c3-t05-local-verification.json`；raw
  `artifacts/capability-audit/catch-up-c3-t05/`。config 仅
  `CONFIG_SECCOMP=y` / `HAVE_ARCH_SECCOMP_FILTER=y` / `SECCOMP_FILTER=y`；
  getenforce=`Permissive`（不外推 Enforcing OEM）。
- **Known Issues**：`KI-R03-NATIVE-008` 保持记录并引用本 ADR；无新增 runtime
  issue。`va_pro_equivalent` 保持 `NOT_PROVEN`。
- **偏离任务书**：无。条件任务按验收标准以正式决策关闭为 `NOT_APPLICABLE`，
  并写明 C3-T04 替代边界与特权部署路径。
- **实现提交 SHA**：`537c20211300c93ae42dda4365bcb0cdb0ee0b70`
  （`docs(native): [C3-T05] record seccomp user-notify not applicable`）。
- **回执提交**：主题 `docs(progress): record [C3-T05] receipt`。
- **推送与远端验证**：两个提交非强制推送到
  `origin/feature/t57-r03-va-pro-capability-campaign`，并以 `git ls-remote --heads`
  对比 HEAD。
- **遗留风险**：特权 companion / OEM user-notify 仍未建设；C3-T06 ART/Xposed
  决策未执行；API33+ / ARM / Enforcing SELinux 未验证。
- **下一任务**：`C3-T06`。

### C3-T06：ART/Xposed Compatibility Extension 决策

- **状态**：NOT_APPLICABLE
- **开始/结束时间**：2026-08-23 16:06 / 2026-08-23 16:15（Asia/Shanghai）
- **执行环境**：Windows amd64；PowerShell；仓库 `D:\github\controlled-android-sandbox`；
  分支 `feature/t57-r03-va-pro-capability-campaign`。MuMu `RD测试` 动态解析，serial
  `127.0.0.1:16416`，model `V2241A`，API 32，boot ID
  `4e8df89b-cebc-41c1-9b1e-d4abf7ac2c31`。本任务不宣称新的 runtime 行为 PASS。
- **开始基线**：`4a8faa64db8b6843c3ed209a97a50b3cc4ac9df5`；远端同 HEAD；上一任务
  `C3-T05` 为 `NOT_APPLICABLE`，实现提交 `537c20211300c93ae42dda4365bcb0cdb0ee0b70`。
- **实现摘要**：产品问题判定为“只需 F2-F5/已知兼容，不加载任意第三方 Xposed 模块”。
  ADR 排除 CAS 核心中的 ART/Xposed/Pine 模块宿主；原始 XH 是 VA Host 产品，
  `spoofer_project` 仍是独立可选 SKU。生产源码扫描确认无 `XposedBridge` /
  `PineXposed` / `xposed_init` 运行时依赖。许可证审查禁止复制 SX Pine 树。
- **变更文件**：`docs/review/C3_T06_ART_XPOSED_EXTENSION_ADR.md`、
  `scripts/check-c3-t06-art-xposed-decision.py`、`tools/capability/run_c3_t06_rd.py`、
  `verification/catch-up/C3-T06/`、`docs/capability/CAPABILITY_REGISTRY.yaml`。
- **验收命令与结果**：`python scripts/check-c3-t06-art-xposed-decision.py` PASS；
  `$env:MUMU_ROOT='D:\install\Netease\MuMu'; python tools/capability/run_c3_t06_rd.py --instance RD测试`
  PASS，`decision=NOT_APPLICABLE`。未修改生产运行时。
- **设备证据**：设备快照写入
  `verification/catch-up/C3-T06/c3-t06-local-verification.json`；决策正文为
  `docs/review/C3_T06_ART_XPOSED_EXTENSION_ADR.md`。不新增 Xposed callback 设备
  PASS，因为模块宿主不在产品范围。
- **Known Issues**：无新增 runtime issue。`va_pro_equivalent` 保持 `NOT_PROVEN`。
- **偏离任务书**：无。条件任务按“仅需 F2-F5 则 NOT_APPLICABLE”关闭；C5-T04 仍可
  在未来显式 SKU 下重新立项，不得把本 ADR 当成模块拦截证据。
- **实现提交 SHA**：`1931baa5ebf5fb3470b9881230cb4fbdcb0ca3b3`
  （`docs(compat): [C3-T06] exclude ART Xposed module host`）。
- **回执提交**：主题 `docs(progress): record [C3-T06] receipt`。
- **推送与远端验证**：两个提交非强制推送到
  `origin/feature/t57-r03-va-pro-capability-campaign`，并以 `git ls-remote --heads`
  对比 HEAD。
- **遗留风险**：C3 阶段门禁仍要求单独确认 C1/C2 回归无退化后才能关闭阶段；
  C4-T01 SX 冻结清单可开始，因其依赖仅为 C1/C2/C3-T01..T04。可选
  `spoofer_project` 模块宿主、API33+、ARM/16KB、OEM 和 VA PRO 仍未证明。
- **下一任务**：`C4-T01`。

### C4-T01：冻结 SX 依赖、功能与运行时清单

- **状态**：DONE
- **开始/结束时间**：2026-08-23 17:03 / 2026-08-23 17:20（Asia/Shanghai）
- **执行环境**：Windows amd64；PowerShell；仓库 `D:\github\controlled-android-sandbox`；
  分支 `feature/t57-r03-va-pro-capability-campaign`。MuMu `RD测试` 动态解析，serial
  `127.0.0.1:16416`，model `V2241A`，API 32，ABI
  `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`，boot ID
  `4e8df89b-cebc-41c1-9b1e-d4abf7ac2c31`。SX 源码树 `D:\github\all_project\sx`。
- **开始基线**：`feature/t57-r03-va-pro-capability-campaign` @
  `65c5c5691327b0b7b6cc81885370e55117e60b4f`；远端同 HEAD；上一任务 `C3-T06`
  `NOT_APPLICABLE`，实现提交 `1931baa5ebf5fb3470b9881230cb4fbdcb0ca3b3`。
- **DISCOVER / CLASSIFY**：续接解析器原先要求 `开始基线 @ SHA`，C3-T05/T06 回执没有
  `@`；C3 runners 的 `FORBIDDEN_SERIALS` 也未进 allowlist。已归为续接 harness 缺口并
  修复。SX 冻结缺口登记为 `KI-R03-046`（T52 清单未对 live Gradle/`SandboxEngine`/
  `BlackBoxCore` 反射做 fail-closed）。
- **实现摘要**：对照 live SX 冻结 Gradle 图、启动链、17 个 `SandboxEngine` 方法、
  UI、F1-F5 Hook、`sx_config`/ConfigProvider 数据键和动态加载；每条有
  REPLACE/DELETE/DROP/GENERIC_ALREADY 与 CAS 目标。生产 CAS 再次确认无
  `BlackBoxCore`/`PineXposed`。未改生产运行时。
- **变更文件**：`docs/review/C4_T01_SX_DEPENDENCY_FREEZE_DESIGN.md`、
  `verification/catch-up/C4-T01/c4-t01-freeze.json`、
  `scripts/check-c4-t01-sx-freeze.py`、`tools/capability/run_c4_t01_rd.py`、
  `scripts/verify-catch-up-continuation.py`、`docs/review/KNOWN_ISSUES.yaml`。
- **验收命令与结果**：`python scripts/check-c4-t01-sx-freeze.py` PASS；
  `python tools/capability/validate_campaign_infra.py` PASS；
  `python scripts/generate-sbom.py --check` PASS；
  `$env:MUMU_ROOT='D:\install\Netease\MuMu'; python tools/capability/run_c4_t01_rd.py --instance RD测试`
  PASS。
- **设备证据**：主回执 `verification/catch-up/C4-T01/c4-t01-rd-summary.json`，
  本地 `c4-t01-local-verification.json`，冻结矩阵 `c4-t01-freeze.json`。本任务不构建
  APK，不宣称 SX/DingTalk 业务 PASS。
- **Known Issues**：`KI-R03-046` 已 `FIXED`。无新增 runtime issue。
  `va_pro_equivalent` 保持 `NOT_PROVEN`。
- **偏离任务书**：无。C3 阶段门禁仍未单独重跑 C1/C2 回归；C4-T01 依赖仅为
  C1/C2/C3-T01..T04，已满足。本轮只执行 C4-T01。
- **实现提交 SHA**：`4dcce11e08bdc6edafbf867032a0790f0ef8ee57`
  （`docs(sx): [C4-T01] freeze SX dependency and runtime inventory`）。
- **回执提交**：主题 `docs(progress): record [C4-T01] receipt`。
- **推送与远端验证**：两个提交非强制推送到
  `origin/feature/t57-r03-va-pro-capability-campaign`，并以 `git ls-remote --heads`
  对比 HEAD。
- **遗留风险**：C4-T02 必须让 SX 生产入口只走 CAS SDK；C4-T03 迁移
  ConfigProvider/`sx_config`；C4-T04 删除 engine-bb/Bcore/Pine。C3 阶段门禁的
  C1/C2 回归仍待单独确认。
- **下一任务**：`C4-T02`。

### C4-T02：实现 SX 到 CAS SDK 的唯一引擎适配

- **状态**：DONE
- **开始/结束时间**：2026-08-23 17:30 / 2026-08-23 17:55（Asia/Shanghai）
- **执行环境**：Windows amd64；PowerShell；JDK 17；Gradle 8.13；仓库
  `D:\github\controlled-android-sandbox`；分支
  `feature/t57-r03-va-pro-capability-campaign`。MuMu `RD测试` 动态解析，serial
  `127.0.0.1:16416`，model `V2241A`，API 32，ABI
  `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`，boot ID
  `4e8df89b-cebc-41c1-9b1e-d4abf7ac2c31`。runner 使用
  `MUMU_ROOT=D:\install\Netease\MuMu`，未固化 ADB endpoint。
- **开始基线**：`feature/t57-r03-va-pro-capability-campaign` @
  `3de95e15332aa09da1427d92a15d7eb9bd2f82db`；远端同 HEAD；上一任务 `C4-T01`
  DONE，实现提交 `4dcce11e08bdc6edafbf867032a0790f0ef8ee57`，回执提交
  `3de95e15332aa09da1427d92a15d7eb9bd2f82db`。
- **DISCOVER / CLASSIFY**：Host UI 仍走 `SxSandboxAdapter` extras 而非公开
  `SandboxSdk`；SDK 缺少 `importInstalledApplication`/`stopAll`；失败常抛异常
  而非可诊断结果；clone 无回滚；无 observer 与 package-neutral RD engine smoke。
  登记为 `KI-R03-047`（`TEST_EVIDENCE_GAP`）。
- **实现摘要**：新增 `CasSandboxEngine` 将 17 个 SX `SandboxEngine` 方法映射到
  `SandboxSdk`；catalog/status 每次从 SDK 重读，不复制权威状态；
  `onAttachBaseContext` 为 `NO_OP_CAS_HOST`；失败返回 `PACKAGE_NOT_INSTALLED` 等
  errorCode；clone 失败删除新 virtual user。Host `SandboxApplicationLayer` 的
  install/launch/stop/clone/clear/delete 改走 engine。Debug `c4-t02-engine`
  只用 adapter/engine。未修改 live SX 树，未删除 engine-bb。
- **变更文件**：`CasSandboxEngine.java`、`SandboxEngineObserver.java`、
  `SandboxSdk.java`、`SxSandboxAdapter.java`、`SandboxApplicationLayer.java`、
  `DebugCommandActivity.java`、`docs/review/C4_T02_SX_CAS_SDK_ADAPTER_DESIGN.md`、
  `scripts/check-c4-t02-sx-adapter.py`、`tools/capability/run_c4_t02_rd.py`、
  `verification/catch-up/C4-T02/`、`docs/review/KNOWN_ISSUES.yaml`。
- **验收命令与结果**：`python scripts/check-c4-t02-sx-adapter.py` PASS；
  `python scripts/check-c4-t01-sx-freeze.py` PASS；
  `python tools/capability/validate_campaign_infra.py` PASS；
  `:sandbox-sdk:selfTest` PASS；`:app:assembleDebug` 与 fixture/companion32
  assemble PASS；`$env:MUMU_ROOT='D:\install\Netease\MuMu'; python
  tools/capability/run_c4_t02_rd.py --instance RD测试` PASS。首次 stop 因 Guest
  尚未完成 death-barrier 注册失败，已分类为 harness 时序并在 launch 后 settle/retry
  后重跑通过，未记录 BLOCKED。
- **设备证据**：主回执 `verification/catch-up/C4-T02/c4-t02-rd-summary.json`，
  本地 `c4-t02-local-verification.json`，映射
  `c4-t02-engine-mapping.json`。smoke：`cloneUser=1`，observerOperations=22，
  traceCount=21。APK SHA-256：host
  `17b232be212895d502b1cf71824af58dd8ec735472627f2e20a3addb885e3a91`；
  companion32 `57b1dbdb765e233376d35f1bf2aae74f325520a82ff12c7554ba8ef1c281ef1d`；
  fixture `18cb010cbe42159aff720beb716a2d25f5c0eaf248b9c37e93098de13caed53e`；
  fixture32 `82f3d33ab2bd83203c2f3e86be603afffcb1d15641e8d769f865016d32895616`。
- **Known Issues**：`KI-R03-047` 已 `FIXED`。无新增 runtime issue。
  `va_pro_equivalent` 保持 `NOT_PROVEN`。
- **偏离任务书**：无验收范围偏离。live SX 仍含 `BlackBoxSandboxEngine`，删除
  归属 C4-T04；`setDisplayName` 持久化与 `sx_config` 归属 C4-T03。本轮只执行
  C4-T02。证据限定 RD API32。
- **实现提交 SHA**：`d6763e40f971fa60db015b17b294cea15fdcdc32`
  （`feat(sx): [C4-T02] route SX engine through CAS SDK adapter`）。
- **回执提交**：主题 `docs(progress): record [C4-T02] receipt`。
- **推送与远端验证**：两个提交非强制推送到
  `origin/feature/t57-r03-va-pro-capability-campaign`，并以 `git ls-remote --heads`
  对比 HEAD。
- **遗留风险**：C4-T03 迁移 ConfigProvider/`sx_config`；C4-T04 删除
  engine-bb/Bcore/Pine；C3 阶段门禁的 C1/C2 回归仍待单独确认。API33+、ARM/16KB、
  OEM、DingTalk 业务和 VA PRO 等价性仍未证明。
- **下一任务**：`C4-T03`。

### C4-T03：迁移 SX 用户、包、Profile、媒体与配置数据

- **状态**：DONE
- **开始/结束时间**：2026-08-23 18:10 / 2026-08-23 18:27（Asia/Shanghai）
- **执行环境**：Windows amd64；PowerShell；JDK 17；Gradle 8.13；仓库
  `D:\github\controlled-android-sandbox`；分支
  `feature/t57-r03-va-pro-capability-campaign`。MuMu `RD测试` 动态解析，serial
  `127.0.0.1:16416`，model `V2241A`，API 32，ABI
  `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`，boot ID
  `4e8df89b-cebc-41c1-9b1e-d4abf7ac2c31`。
- **开始基线**：`feature/t57-r03-va-pro-capability-campaign` @
  `e7ac819bedf09b3e02d3f038c23e3e1f25e20b44`；远端同 HEAD；上一任务 `C4-T02`
  DONE，实现提交 `d6763e40f971fa60db015b17b294cea15fdcdc32`。
- **DISCOVER / CLASSIFY**：SX `sx_config`/ConfigProvider 仍按 BlackBox 同 UID 共享；
  CAS 已有实例级 profile/media store，但没有版本化、幂等、可回滚的桥。登记
  `KI-R03-048`（`TEST_EVIDENCE_GAP`）。
- **实现摘要**：新增 `sx-config-v1` → `cas-instance-profile-v1` 迁移器；提交前备份
  CAS profile；同 hash 重放为 IDEMPOTENT；中断不改 live；回滚恢复备份并保留旧源；
  双用户坐标/androidId/媒体 hash 隔离。license/time-guard DROP。
- **变更文件**：`sandbox-domain/.../migration/*`、`SxMigrationHostStore.java`、
  `DebugCommandActivity.java`、`docs/review/C4_T03_SX_DATA_MIGRATION_DESIGN.md`、
  `scripts/check-c4-t03-sx-migration.py`、`tools/capability/run_c4_t03_rd.py`、
  `verification/catch-up/C4-T03/`。
- **验收命令与结果**：`python scripts/check-c4-t03-sx-migration.py` PASS；
  `:sandbox-domain:selfTest` PASS；`:app:assembleDebug` PASS；
  `$env:MUMU_ROOT='D:\install\Netease\MuMu'; python
  tools/capability/run_c4_t03_rd.py --instance RD测试` PASS。
- **设备证据**：`verification/catch-up/C4-T03/c4-t03-rd-summary.json`；
  cloneUser=1；replay=IDEMPOTENT；interrupt=INTERRUPTED；rollback=ROLLED_BACK；
  user0Lat=31.2304 / user1Lat=22.543099；sourceKept=true。APK SHA-256：host
  `f9405a13b2a448b7182c02d6259d74e9296313476b7cc6a9cf67d68d6905fc65`。
- **Known Issues**：`KI-R03-048` 已 `FIXED`。`va_pro_equivalent` 保持 `NOT_PROVEN`。
- **偏离任务书**：无。本轮只关闭 C4-T03。
- **实现提交 SHA**：`e064e2174854c661248e0c2970b8fe621bc161ef`
  （`feat(sx): [C4-T03] migrate SX config onto instance profiles`）。
- **回执提交**：主题 `docs(progress): record [C4-T03] receipt`。
- **推送与远端验证**：两个提交非强制推送到
  `origin/feature/t57-r03-va-pro-capability-campaign`，并以 `git ls-remote --heads`
  对比 HEAD。
- **遗留风险**：C4-T04 仍需生产 APK 无 BlackBox/Pine 门禁；C4-T05 业务长稳未做。
- **下一任务**：`C4-T04`。

### C4-T04：移除 SX 生产 BlackBox/Pine/Xposed 运行时

- **状态**：DONE
- **开始/结束时间**：2026-08-23 18:27 / 2026-08-23 18:32（Asia/Shanghai）
- **执行环境**：Windows amd64；PowerShell；JDK 17；Gradle 8.13；仓库
  `D:\github\controlled-android-sandbox`；分支
  `feature/t57-r03-va-pro-capability-campaign`。MuMu `RD测试` 动态解析，serial
  `127.0.0.1:16416`，model `V2241A`，API 32，boot ID
  `4e8df89b-cebc-41c1-9b1e-d4abf7ac2c31`。
- **开始基线**：`b931724fbe2aef0854eac7bb65409a26b9c24b82`；上一任务 `C4-T03` DONE。
- **DISCOVER / CLASSIFY**：CAS Gradle 本无 `:Bcore`/`:engine-bb`，但缺少 fail-closed
  的源码/APK 内容门。登记 `KI-R03-049`（`TEST_EVIDENCE_GAP`）。
- **实现摘要**：新增生产 Gradle/src/main/APK zip 扫描，禁止 BlackBoxCore、PineXposed、
  `xposed_init`、libpine/libblackbox、`top.niunaijun.blackbox`。`ref/` 参考树不打包。
  CAS-only smoke 复用 `c4-t02-engine`。未改 live SX 工程。C4-T03 迁移代码保留。
- **变更文件**：`docs/review/C4_T04_CAS_ONLY_RUNTIME_DESIGN.md`、
  `scripts/check-c4-t04-cas-only-runtime.py`、`tools/capability/run_c4_t04_rd.py`、
  `verification/catch-up/C4-T04/`。
- **验收命令与结果**：`python scripts/check-c4-t04-cas-only-runtime.py` PASS；
  `python scripts/check-c4-t03-sx-migration.py` PASS；assembleDebug PASS；
  `$env:MUMU_ROOT='D:\install\Netease\MuMu'; python
  tools/capability/run_c4_t04_rd.py --instance RD测试` PASS。
- **设备证据**：`verification/catch-up/C4-T04/c4-t04-rd-summary.json`；
  CAS-only smoke PASS。host SHA-256
  `f9405a13b2a448b7182c02d6259d74e9296313476b7cc6a9cf67d68d6905fc65`。
- **Known Issues**：`KI-R03-049` 已 `FIXED`。`va_pro_equivalent` 保持 `NOT_PROVEN`。
- **偏离任务书**：无。本轮只关闭 C4-T04，不执行 C4-T05。
- **实现提交 SHA**：`525f3aec84ae1ff09192f11a417adf51464f965e`
  （`test(runtime): [C4-T04] gate CAS-only production against BlackBox`）。
- **回执提交**：主题 `docs(progress): record [C4-T04] receipt`。
- **推送与远端验证**：两个提交非强制推送到
  `origin/feature/t57-r03-va-pro-capability-campaign`，并以 `git ls-remote --heads`
  对比 HEAD。
- **遗留风险**：C4-T05 F1-F5/DingTalk 长稳未做；C4 阶段门禁未关。
- **下一任务**：`C4-T05`。

### C4-T05：SX F1-F5、DingTalk 与长稳验收

- **状态**：DONE
- **开始/结束时间**：2026-08-23 19:10 / 2026-08-23 21:14（Asia/Shanghai）
- **执行环境**：Windows amd64；PowerShell；JDK 17；Gradle 8.13；仓库
  `D:\github\controlled-android-sandbox`；分支
  `feature/t57-r03-va-pro-capability-campaign`。MuMu `RD测试` 动态解析，serial
  `127.0.0.1:16416`，model `V2241A`，API 32，boot ID
  `4e8df89b-cebc-41c1-9b1e-d4abf7ac2c31`。
- **开始基线**：`6aed424ea38b2eea7aa29e776a8bc0f804d9e9d6`；上一任务 `C4-T04` DONE。
- **DISCOVER / CLASSIFY**：C4-T02..T04 已关闭引擎、迁移和 CAS-only 门，但缺少 F1-F5
  实际调用面、DingTalk 7.8.10/1178 冷热/升级/登录表面/前后台、以及 100 轮关键业务的
  fail-closed 战役。登记 `KI-R03-050`（`TEST_EVIDENCE_GAP`）。导入钉钉时
  `requireCompatibleElf` 把 packed `lib/<abi>/*.so` 当 ELF 拒绝，登记
  `KI-R03-051`（`CURRENT_DEFECT`）并在本任务修复。
- **实现摘要**：通用 fixture 先按 F1 相机、F2 定位、F4 网络、F5 蓝牙、F3 设备调用
  Location/Camera/C2T06/C2T05/WebView/RemoteActivity，FileProvider
  `prepareProvider`，shortcut，DingTalk 特化保持关闭且不改 generic profile；再 100
  轮 launch/stop。指定钉钉 7.8.10/1178 冷/热启动、同版本再导入升级、dumpsys 登录表面
  （PrivacyPolicy/LaunchHome/Home + CAS Stub/guest）与 HOME 前后台。ELF 校验仅作用于
  带 ELF magic 的文件。
- **变更文件**：`docs/review/C4_T05_SX_BUSINESS_DESIGN.md`、
  `scripts/check-c4-t05-sx-business.py`、`tools/capability/run_c4_t05_rd.py`、
  `app/src/debug/.../DebugCommandActivity.java`、
  `app/src/main/.../ApkImportManager.java`、
  `verification/catch-up/C4-T05/`。
- **验收命令与结果**：`python scripts/check-c4-t05-sx-business.py` PASS；
  `python scripts/check-c4-t04-cas-only-runtime.py` PASS；assembleDebug PASS；
  `$env:MUMU_ROOT='D:\install\Netease\MuMu'; python
  tools/capability/run_c4_t05_rd.py --instance RD测试` PASS。F1-F5 log 标记、
  100 轮、DingTalk 7.8.10/1178 cold/hot/upgrade/login-surface/fg-bg 均 PASS。
- **设备证据**：`verification/catch-up/C4-T05/c4-t05-rd-summary.json`；
  host SHA-256
  `8434e8dd7b02518022d7e80aa99166a5fbce0b4383a56d53e97f098c9e63275c`。
- **Known Issues**：`KI-R03-050`、`KI-R03-051` 已 `FIXED`。
  `va_pro_equivalent` 保持 `NOT_PROVEN`。未宣称 8 小时 soak、账号密码登录或 OEM。
- **偏离任务书**：无显式 8 小时 soak（任务书 1.1 已移除）。钉钉账号凭据登录不在本
  Host 门禁内；登录表面以 Guest `PrivacyPolicyActivity`/`LaunchHomeActivity` 和
  CAS Stub 为准。
- **实现提交 SHA**：`0e34f37535aec5d3dd93cdf9bc2463c61639310b`
  （`test(sx): [C4-T05] prove F1-F5 DingTalk and 100-round business`）。
- **回执提交**：主题 `docs(progress): record [C4-T05] receipt`。
- **推送与远端验证**：两个提交非强制推送到
  `origin/feature/t57-r03-va-pro-capability-campaign`，并以 `git ls-remote --heads`
  对比 HEAD。
- **遗留风险**：C4 阶段门禁尚未单独落账；DingTalk 账号级登录需要凭据；100 轮
  launch/stop 偏慢（guest bind）。C5 未开始。
- **下一任务**：`C5-T01`。

### PLAN-20260824-C4-REOPEN：重开 C4、跳过 C5 并制定 RD 返修计划

- **状态**：DONE
- **开始/结束时间**：2026-08-24 10:20 / 2026-08-24 10:29（Asia/Shanghai）
- **执行环境**：Windows amd64；PowerShell；仓库
  `D:\github\controlled-android-sandbox`；分支
  `feature/t57-r03-va-pro-capability-campaign`。MuMu `RD测试` 仅做动态可用性解析，未复现、定位或修复
  红果/番茄小说异常，也未执行新的 Android runtime 验收。
- **开始基线**：`feature/t57-r03-va-pro-capability-campaign` @
  `1bef0951218ec8356f94c869ae9131ad5859864e`；开始时本地、上游一致且工作区干净；最后完成任务
  `C4-T05`，但其阶段关闭证据随后被本次审查判定不足。
- **审查结论**：原 C4 runner 将 `LAUNCH_PASS`、Stub/Guest marker 作为成功，未验证 Window/Surface/
  `reportedDrawn` 或首帧，故黑屏仍可 PASS；启动流程缺少 request-scoped readiness 和阶段 deadline；添加只做
  少量样本，不能证明事务可靠性；静默 launch retry、固定 sleep 与宽泛窗口重试会隐藏首次失败。指定提交
  `6e1044b0` 修复了部分 Window/Audio/Context 合同但未在同一 commit 闭合 C4 证据；`1bef0951` 只是说明性
  进度补记，不是完整 C4 重验回执。
- **计划变更**：任务书升至 1.2，保留 C4-T01..T05 历史，新增 C4-R01..R05；C4 必须依次完成证据纠偏与
  VA/NBB mapping、添加状态机、启动/窗口合同、fail-closed runner 和 RD 正式重验。C5-T01..T04 全部
  `NOT_APPLICABLE`，C6-T01 直接依赖 C4-R05。
- **商业样本门槛**：夸克为正向对照，红果和番茄小说为必须关闭的异常样本；连同 DingTalk 分别执行 10 次
  add/delete/re-add，单项成功率 100%。任一样本异常直接 FAIL，夸克成功不能替代红果或番茄小说。实际
  package/version/base/split/ABI 在 C4-R01/R02 执行时动态记录，本轮不猜测包名或具体异常。
- **变更文件**：
  `docs/plans/CAS_VA_PRO_CATCH_UP_EXECUTION_TASK_BOOK_20260821.md`、
  `docs/review/C4_RD_RETEST_ROOT_CAUSE_AND_ACCEPTANCE_PLAN_20260824.md`、
  `docs/review/KNOWN_ISSUES.yaml`、`scripts/verify-catch-up-continuation.py`、
  `scripts/test_catch_up_continuation.py`、本进度账本。
- **Known Issues**：新增 `KI-R03-053`（黑屏误判）、`KI-R03-054`（启动超时不可诊断）、
  `KI-R03-055`（红果/番茄小说添加兼容性样本）和 `KI-R03-056`（隐藏重试与猜测式修复流程），均
  `RECORDED` 且阻断 C4 关门。
- **验收命令与结果**：`python scripts/test_catch_up_continuation.py` PASS（6 tests）；
  `python tools/capability/validate_campaign_infra.py` PASS；`git diff --check` PASS。续接器已支持 `C4-R*`
  任务 ID，确保新环境能够从账本选择 C4-R01。
- **设备证据**：动态解析 MuMu `RD测试` 为 API 32、model `22041211A`、boot ID
  `d09f0f79-058d-42af-924c-3a99f1429ea4`；仅证明计划首要设备可解析，不声明红果、番茄小说、夸克或
  任何 runtime 能力通过。
- **偏离任务书**：按用户最新指示，本轮只制定计划，不定位实际添加异常或提交生产修复；运行中的只读设备
  解析在收到该指示后停止于环境确认，没有执行应用操作。
- **实现提交 SHA**：`a8f24e40`（任务书、分析和 Known Issues）；`22387799`（续接器支持 C4-R 任务）。
- **回执提交**：主题 `docs(progress): record [PLAN-20260824-C4-REOPEN] receipt`。
- **推送目标与远端验证**：本回执提交后将实现提交与回执提交非强制推送至
  `origin/feature/t57-r03-va-pro-capability-campaign`，并以 `git ls-remote --heads` 对比本地 HEAD。
- **遗留风险**：四个问题的实际首次失败签名和最终 owner 仍待 C4-R01；C4-R02..R05 尚未执行；历史
  C4-T05 summary 仅保留审计，不得用于关闭重新打开的 C4。
- **下一任务**：`C4-R01`；执行前必须先读取任务书 1.2、本账本最后回执、Known Issues 和 Git/远端状态。

### C4-R01：证据纠偏、确定性复现与 VA/NBB 映射

- **状态**：DONE
- **开始/结束时间**：2026-08-24 10:37 / 2026-08-24 11:08（Asia/Shanghai）
- **执行环境**：Windows amd64；PowerShell；仓库
  `D:\github\controlled-android-sandbox`；分支
  `feature/t57-r03-va-pro-capability-campaign`。MuMu 实例按名称 `RD测试` 动态解析，本轮观测 endpoint
  为 `127.0.0.1:16416`，脚本未硬编码 ADB 地址。设备 API 32、model `22041211A`、boot ID
  `d09f0f79-058d-42af-924c-3a99f1429ea4`、Android ID `398eea33120cd887`、ABI list
  `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`。
- **开始基线与预检**：`d73003748d64ef70fe8b74c03b8c733be1338636`；开始时工作区干净，本地与远端
  HEAD 一致，Git 身份为 `OpenAI <openai@users.noreply.github.com>`，账本下一任务为 `C4-R01`；开始后先将
  C4-R01 标记 `IN_PROGRESS`。
- **范围与实现摘要**：只新增 fail-fast 诊断 collector、机器可读 evidence、证据纠偏、owner 分类和
  VA/NBB mapping；没有修改生产代码，没有执行 C4-R02..R05、C6 或 OEM 适配。原 C4-T05 两份 summary
  均已标记 `SUPERSEDED`、`historical_only=true`、`usable_for_c4_closure=false`，历史证据保留但禁止关闭 C4。
- **构建与 APK**：device-test build PASS；manifest SHA-256
  `a25258d81e78cfc1d04524cd5edc5b58e5b457fddf0ae601ffccddbc2686df55`；Host
  `97d0ea1d3a6f492ee1b07311ac0a39f17027d0534f65f89b7e19f09c98d65b7f`；fixture
  `e4f9d4458eee7d3d123f7be11426424c0d2533329435e60185f6fd6c78691058`；companion32
  `6f9d2e6aaf7d704e5ae74d72e938fe8ccb413c565ebbf83dd7e018ff53d44ef0`。
- **商业样本动态清单**：夸克 `com.quark.browser` 10.10.5.1080/1080、base 1/split 0、arm64-v8a，首次
  PASS 且截图可见真实隐私页，仅作正向对照；红果免费短剧 `com.phoenix.read` 7.0.5.33/70533、base 1/split 0、
  arm64-v8a，首次 FAIL；番茄免费小说 `com.dragon.read` 7.1.9.32/71932、base 1/split 0、arm64-v8a，首次
  FAIL。红果和番茄均为 `SecurityException: NATIVE_ELF_ABI_MISMATCH:arm64-v8a`，夸克 PASS 未替代二者。
- **首次失败与 owner**：修复前同一 RD raw 已确认黑屏为 `LAUNCH_PASS` + Guest Stub `RESUMED` +
  `windows=[]/hasVisible=false/reportedDrawn=false`；该精确签名当前未重现，明确列为待 C4-R03/R04/R05 验证，
  未伪装成当前失败。当前 resume-crash 单次启动在 43.729 秒以 `LAUNCH_GATE_FAILED` 失败并保存完整设备快照，
  超过 cold first-frame 30 秒 SLO，但内部首要阶段因 telemetry gap 待验证，owner 保持 CAS 通用启动 readiness。
  红果/番茄在 CAS native 校验、catalog success 之前失败，owner 确认为 CAS 通用导入兼容性，不转交 SX/UI。
- **首次失败策略与时间线**：每个 operation `attempt=1`、`retry_budget=0`、无自动 operation retry；保留
  request/operation ID、错误分类、retryable 判断和 snapshot 时间。已确认当前生产路径未把 ID 贯穿
  import/catalog/bind/prepare/attach/Activity/window/draw，缺失阶段写为 telemetry gap，不编造时间。原 C4 loop
  lines 1550-1558 的 stop + 400 ms + relaunch 已确认可隐藏第一次失败，生产移除归 C4-R04。
- **VA/NBB mapping**：已在设计前查阅并固化 NewBlackBox 的
  `BPackageManagerService`/`BPackageInstallerService`、`BlackBoxCore`/AMS/`ActivityStack`、
  `BProcessManagerService`/`BActivityThread`、`ContextCompat`/WindowManager/WindowSession，以及 VirtualApp 的
  `VAppManagerService`、`VActivityManager`/AMS/`ActivityStack`、Stub/HCallback/Instrumentation/Window
  实现和 SHA-256。采纳原子安装状态、显式 Stub/process attach/death、最小 Host window identity、正常
  ActivityThread draw；不采纳直接复制、固定 sleep、无分类重试、broad addView retry 或 raw Host Context 暴露。
- **指定提交审查**：`6e1044b013fab19a53dd4ceab75230963c4dd83f` 是 12 文件 `+548/-34` 的部分
  Window/Audio/Context 恢复，但多假设面、broad post-resume addView retry 和 Quark-only evidence 不能关闭 C4；
  `1bef0951218ec8356f94c869ae9131ad5859864e` 仅追加 9 行说明，不是完整重验回执。
- **Evidence**：`verification/catch-up/C4-R01/c4-r01-rd-summary.json`、
  `verification/catch-up/C4-R01/reference-mapping.json`、
  `docs/review/C4_R01_EVIDENCE_REPRO_CLASSIFICATION_AND_REFERENCE_MAPPING_20260824.md`；raw 当前目录
  `artifacts/capability-audit/catch-up-c4-r01/20260824T025555Z`，summary SHA-256
  `f40d0fa694ebac1b144ddaf57f35756263e53165a8382ba7ad8269b8c3d35950`；历史黑屏 transition SHA-256
  `cba8dd7aeb9e7a0a9d75026ef853328e35b91a636c447486d763924c493ce9ca`。
- **Known Issues**：`KI-R03-053` 至 `KI-R03-056` 均已补充独立证据、最小复现、owner 和已确认/待验证边界；
  它们仍为 `RECORDED` 且 `blocks_current_campaign: true`，由 C4-R02..R05 后续关闭，本任务没有伪造 FIXED。
- **验收命令与结果**：collector AST、tracked JSON/YAML parse PASS；
  `python scripts/test_catch_up_continuation.py` PASS（6 tests）；
  `python tools/capability/validate_campaign_infra.py` PASS；`git diff --check` PASS；设备 collect-all 完成。
- **偏离与裁决**：修复前黑屏精确签名在当前 HEAD 未重现，故保留历史 raw 作为已确认首次失败证据，并把
  当前完整回归明确留给 R03/R04/R05；没有通过降级代码、延长 sleep/deadline 或重复启动制造/掩盖结果。
  C4-R01 的证据纠偏、独立 Known Issue、原始 evidence、最小复现、owner 分类和参考合同均已闭合，因此本任务
  DONE；这不表示四个 runtime/gate issue 已关闭，也不表示 C4 已关闭。
- **实现/证据提交 SHA**：`d2f1b0aa7137195661525c442e290bd6e009646c`
  （`test(c4): [C4-R01] preserve and classify first failures`）。
- **回执提交**：主题 `docs(progress): record [C4-R01] receipt`。
- **推送与远端验证**：本回执提交后将两个提交非强制推送到
  `origin/feature/t57-r03-va-pro-capability-campaign`，再以 `git ls-remote --heads` 比较本地/远端 HEAD。
- **下一任务**：`C4-R02`（PENDING）；下一环境必须从本回执和续接预检无损继续，禁止跳到 C4-R03、C6 或 OEM。

### C4-R02：添加事务、超时与 UI 操作状态机

- **状态**：DONE
- **开始/结束时间**：2026-08-24 12:58 / 2026-08-24 14:39（Asia/Shanghai）
- **执行环境**：Windows amd64；PowerShell；JDK 17；Gradle 8.13；仓库
  `D:\github\controlled-android-sandbox`；分支
  `feature/t57-r03-va-pro-capability-campaign`。MuMu 实例按名称 `RD测试` 动态解析，最终观测
  endpoint `127.0.0.1:16416`，设备 model `22041211A`、API 32、boot ID
  `d09f0f79-058d-42af-924c-3a99f1429ea4`；runner 未硬编码 ADB 地址。
- **开始基线与预检**：`577d0e6a79a14b269058a01affebf1e482b20237`；开始时账本下一任务为
  `C4-R02`，工作区、本地 HEAD、远端 HEAD 和 Git 身份符合规范；先将 C4-R02 标记为
  `IN_PROGRESS`。规范身份为 `OpenAI <openai@users.noreply.github.com>`。
- **实现摘要**：新增 package/user 单飞 coordinator、统一 request/operation trace、COPY/HASH/
  PARSE/NATIVE_EXTRACT/PUBLISH/CATALOG/ENSURE_INSTANCE 分段 deadline、导入+首实例原子 catalog
  提交、失败 staging 清理、单次 connector 获取、UI request/stage/elapsed 与终态恢复、并发
  `MUTATION_BUSY`、Host/PackageService 死亡恢复。混合 ELF 目标机器作为可审计 anomaly 接受，未知
  格式/目标仍 fail-closed。设计前已查阅并记录 NBB/VA 安装、启动、进程和窗口实现。
- **动态商业样本**：夸克 `com.quark.browser` 10.10.5.1080/1080，base 1/split 0，arm64-v8a，
  仅作正向对照；红果 `com.phoenix.read` 7.0.5.33/70533，base 1/split 0，arm64-v8a；番茄小说
  `com.dragon.read` 7.1.9.32/71932，base 1/split 0，arm64-v8a；钉钉动态识别为
  `com.alibaba.android.rimet` 7.8.10/1178，base 1/split 0，arm64-v8a。红果/番茄均记录
  `MIXED_ELF_MACHINE`，未以夸克成功外推运行时兼容。
- **验收结果**：fixture 50 次 add/delete/re-add（150/150）；钉钉、夸克、红果、番茄各 10 次
  （30/30、30/30、31/31、31/31，红果/番茄含首次导入证据），共 272 条产品操作，失败 0；
  latency min/median/p95/max 为 5513/7595/16416/23565 ms；每条 attempt=1、retryBudget=0。
  并发添加为 1 success + 1 `MUTATION_BUSY` 且同 operation ID；未授权 native 负测稳定返回
  `UNTRUSTED_NATIVE_GUEST_DENIED`，不重试且无 residue；Host 与 PackageService 死亡恢复均 PASS，
  revision SHA 保持不变。
- **证据与回执**：完整回执为 `docs/review/C4_R02_TASK_RECEIPT.md`，机器汇总为
  `verification/catch-up/C4-R02/acceptance-summary.json`，设备原始证据在
  `verification/catch-up/C4-R02/rd-acceptance/`；`python scripts/check-c4-r02-package-mutation.py`
  PASS；`python scripts/test_catch_up_continuation.py` PASS（6 tests）；
  `python tools/capability/validate_campaign_infra.py` PASS；Gradle APK 构建、完整静态 Android
  self-test、证据 JSON/YAML parse 和 `git diff --check` PASS。最终 APK SHA-256 为
  `17138281206690EA5B3C10AD0E0D21FC2C33C8DD7051A2386E0B9464CCB72CC6`。
- **Known Issues**：`KI-R03-055` 已更新为 `FIXED`（仅 CAS 导入兼容性）；`KI-R03-053`、
  `KI-R03-054`、`KI-R03-056` 保持 `RECORDED` 且阻断，分别由 C4-R03/R04/R05 继续处理；C4
  尚未关闭。
- **实现提交 SHA**：`46eed7be60a83f5b5adfe865a8c4b0d37e0a63a1`
  （`feat(c4): implement R02 package mutation transactions`）。
- **回执提交**：本段为独立的 `docs(progress): record [C4-R02] receipt` 提交。
- **下一任务**：`C4-R03`（PENDING）；下一环境必须从本回执和最终续接预检无损继续，禁止跳到
  C4-R04、C4-R05、C6 或 OEM。

### C4-R03：启动 readiness、窗口合同与超时修复

- **状态**：BLOCKED
- **开始/结束时间**：2026-08-24 14:46 / 2026-08-24 17:55（Asia/Shanghai）
- **执行环境**：Windows amd64；PowerShell；JDK 17；Gradle 8.13；仓库
  `D:\github\controlled-android-sandbox`；分支
  `feature/t57-r03-va-pro-capability-campaign`。MuMu 实例按名称 `RD测试` 动态解析，观测 endpoint
  `127.0.0.1:16416` 只作为快照字段记录，runner 没有硬编码 ADB 地址；设备 model `22041211A`、
  API 32、boot ID `d09f0f79-058d-42af-924c-3a99f1429ea4`、ABI
  `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`。
- **开始预检**：开始时账本下一任务为 `C4-R03`，工作区、本地 HEAD、远端 HEAD 和 Git 身份符合规范，
  身份为 `OpenAI <openai@users.noreply.github.com>`；开始状态记录在
  `verification/catch-up/C4-R03/start-state.json`。
- **任务边界**：只执行 R03 启动 readiness、窗口合同、首帧证据和参考实现映射；没有进入 C4-R04、
  C4-R05、C6 或 OEM。未实施 R04 的 fail-closed runner，也未实施 R05 关门压力。
- **实现摘要**：加入 `REQUEST_ACCEPTED`、`GUEST_READY`、`ACTIVITY_RESUMED`、`FIRST_FRAME_DRAWN`
  阶段、request/operation ID、attempt/retry metadata、token 绑定的 Activity event 过滤和首帧 gate；
  将 post-resume window 修复限定为一次明确观察/一次 fallback，不使用循环重试、固定 sleep 或扩大生产
  deadline。新增动态 target discovery 和首次失败立即 snapshot 的 R03 runner。
- **NBB/VA 参考映射**：已在设计前查阅并记录 NBB 的 AMS/ActivityStack、process attach/death、
  ContextCompat/WindowManager/WindowSession，以及 VA 的 VActivityManager/ActivityStack、
  StubActivityRecord、Instrumentation、WindowManager/Session 实现。R03 采用其“broker-owned
  ActivityRecord/route + 小 Host Stub Intent + 正常 framework ActivityThread draw”边界，不复制源码。
  详细记录见 `docs/review/C4_R03_LAUNCH_READINESS_WINDOW_DESIGN_20260824.md`。
- **动态商业样本**：夸克 `com.quark.browser` 10.10.5.1080/1080，base 1/split 0，arm64-v8a；
  红果 `com.phoenix.read` 7.0.5.33/70533，base 1/split 0，arm64-v8a；番茄小说
  `com.dragon.read` 7.1.9.32/71932，base 1/split 0，arm64-v8a；钉钉动态识别为
  `com.alibaba.android.rimet` 7.8.10/1178，base 1/split 0，arm64-v8a。夸克只作正向对照，
  没有用于判定红果或番茄兼容。
- **验收证据**：fixture user0/user1 各一轮 cold/hot 共 4 行通过；番茄 user0 cold/hot 一轮通过，
  cold 29400 ms、hot 4829 ms，均有首帧、非空 Window/Surface 和非黑截图。该证据不满足任务书的
  5 target × 2 users × 50 cold/hot 全矩阵。
- **首次失败**：钉钉 request `ade67da601b74b9b81c7d6f46c5ce3e3` 首次冷启动在
  `TransactionTooLargeException` 270596 bytes 失败；夸克 request
  `67a3de60be574c7bb97cc5480943297d` 在 283304 bytes 失败；红果 request
  `b3b25112f8e24922b19bff1f590bc258` 在 308616 bytes 失败。每个均为
  `attempt=1/retryBudget=0/automaticRetryPerformed=false`，并立即保存 logcat、dumpsys、Surface、
  进程、事务、截图和设备快照；证据索引见 `verification/catch-up/C4-R03/rd-acceptance/summary.json`。
- **owner 裁决**：夸克 logcat 已确认 `Guest Activity.startActivity → GuestRuntimeBrokerBridge →
  RuntimeOperationTransport → IRuntimeBroker.executeV2`，失败发生在 CAS import/catalog 成功之后、
  首帧之前。当前 owner 保持 CAS 通用 Activity/Intent transport；没有 SX/UI 调用失败证据，
  不猜测下游 owner。新增 `KI-R03-057`，状态 `RECORDED`、阻断为 true；`KI-R03-055` 仍仅表示
  导入兼容 FIXED，不代表运行时兼容。
- **设计裁决**：参考 NBB/VA 后确认 CAS 当前把大 Intent extras 直接复制到顶层
  `RuntimeOperationRequest`，完整 wire 超过 256 KiB 时又只丢弃 wire 而保留 extras，导致 Guest→Broker
  Binder 事务过大。候选修复必须采用 generation/session 绑定的有界 payload handle 或等价 broker-owned
  record，不能静默截断；在协议和边界测试未完成前不再盲目重试。本任务不提前实施 R04/R05。
- **验证命令**：`python scripts/test_catch_up_continuation.py` PASS（6 tests）；
  `python tools/capability/validate_campaign_infra.py` PASS；Known Issues YAML、R03 summary JSON
  解析 PASS；`git diff --check` PASS；Gradle `:app:assembleDebug :sandbox-runtime:compileDebugJavaWithJavac`
  PASS。APK SHA-256 为 `0278ADAA13DA8A066423E5351F8FBD751B239F7B7EEB1322080744FD85360B13`。
- **阻断结论**：R03 商业启动验收未满足，不能标记 DONE，不能推进 C4-R04。账本当前任务保持
  `C4-R03 (BLOCKED)`，下一任务不前移；恢复执行前必须先完成有界 Intent transport 设计/实现及其
  证据闭环。
- **实现/证据提交 SHA**：`f65ea6f3`（`feat(c4): record [C4-R03] launch readiness blocker`）。
- **回执提交**：本段为独立的 `docs(progress): record [C4-R03] receipt` 提交。
- **推送与远端验证**：本回执提交后将实现提交和回执提交非强制推送至
  `origin/feature/t57-r03-va-pro-capability-campaign`，再比较本地/远端 HEAD；由于任务 BLOCKED，
  最终续接预检确认仍停留在 C4-R03，不能伪造下一任务 C4-R04。

### C4-R03：8 小时上限续接回执（2026-08-25）

- **最终状态**：`BLOCKED`。本次按用户最新指示，将当前执行窗口上限覆盖为 8 小时；这只是本次运行
  的时间限制，不改写任务书已移除显式 8 小时 soak 的全局修订，也不等同于 8 小时稳定性声明。
  有效窗口为 2026-08-24 23:00:28 至 2026-08-25 07:00:28（Asia/Shanghai），到点停止正在执行的
  fixture case；未伪造 `DONE`，下一任务仍为 `C4-R03`，不得进入 R04/R05、C6 或 OEM。
- **执行环境与动态设备**：Windows amd64、PowerShell、JDK 17、Gradle 8.13；MuMu 实例通过名称
  `RD测试` 动态解析。当前证据中的 `127.0.0.1:16416` 仅为解析快照字段，runner 没有硬编码 ADB
  地址。设备快照见
  `artifacts/capability-audit/catch-up-c4-r03/final-prelookup-owner-fixture-u0-u1-25-20260825/environment.json`；
  本轮 boot ID 为 `60d44ff7-2d1b-44a3-8cec-7b1f0608b633`。
- **矩阵定义与实际结果**：按用户此前将每个 50 轮改为 25 轮的指示，矩阵为 fixture、DingTalk、夸克、
  红果、番茄小说 × user0/user1 × cold/hot 各 25，共 500 rows。最终修复代码实际完成 260 rows，
  260/260 PASS、0 non-PASS：fixture user0 50/50，fixture user1 10/50（在 cold-006 证据采集阶段
  截止）；DingTalk user0/user1 各 50/50；夸克 user0/user1 各 50/50；红果和番茄小说本轮最终矩阵
  各 0/50。机器可读统计和每个 case 的 request/operation ID 见
  `verification/catch-up/C4-R03/rd-acceptance/summary.json` 的 `continuation8h`，原始证据目录为：
  `artifacts/capability-audit/catch-up-c4-r03/post-prelookup-owner-clean-reboot-quark-u0-25-20260825`、
  `artifacts/capability-audit/catch-up-c4-r03/final-prelookup-owner-quark-u1-25-20260825`、
  `artifacts/capability-audit/catch-up-c4-r03/final-prelookup-owner-dingtalk-u0-u1-25-20260825` 和
  `artifacts/capability-audit/catch-up-c4-r03/final-prelookup-owner-fixture-u0-u1-25-20260825`。
- **首次失败/截止证据**：历史首次失败仍保留并区分为 CAS 通用边界：DingTalk/夸克/红果的 oversized
  Intent/Binder 事务，以及后续 Quark `lowmemorykiller` + Guest/Broker 进程断开。后者在
  `.../post-client-broker-owner-clean-reboot-quark-u0-25-20260825/.../hot-018/first-failure-full` 和
  `.../post-clean-reboot-quark-u0-25-20260825/.../hot-020/first-failure-full` 保存了 logcat、dumpsys、
  window、Surface、进程、事务、截图和设备快照。8 小时截止时的 fixture user1 `cold-006` 没有
  `case.json`，但已保存 `logcat.txt`、`activity-activities.txt`、`window-windows.txt`、
  `surface-list.txt`、`screenshot.png` 和 cold-stop 证据；该中断 case 不计入通过。
- **修复与 owner**：查阅 NBB/VA 的 ProcessRecord/ActivityStack、启动前进程归属、绑定与死亡回收实现
  后，确认该问题 owner 是 CAS 通用 Broker/Guest 进程生命周期，而不是 SX/UI，也不是夸克专属兼容性。
  修复在 `RebindableServiceConnector`、Broker/Guest connection pool、`BaseGuestProcessService`、
  `RuntimeClient` 和 `DebugCommandActivity` 建立 `BIND_AUTO_CREATE|BIND_IMPORTANT|BIND_ABOVE_CLIENT`
  owner edge，并在 package lookup 前 prime RuntimeClient owner；没有新增重试、sleep 或 deadline 延长。
  修复后夸克双用户和 DingTalk 双用户完成 200/200 PASS；红果/番茄小说的兼容性结论仍为待验证，
  夸克仅为正向对照。Known Issue 新增 `KI-R03-058`，因矩阵未完成保持 `RECORDED` 且阻断。
- **重试审计**：260 个已完成 rows 全部为 `attempt=1`、`retryBudget=0`、
  `automaticRetryPerformed=false`、`retryable=false`。没有把失败通过重复运行、延长 deadline 或固定
  sleep 隐藏；8 小时截止停止产生的 KeyboardInterrupt 是时间门限结果，不是产品 PASS/FAIL。
- **验证命令**：`.\gradlew.bat --no-daemon --no-build-cache --no-parallel --offline :app:assembleDebug
  :sandbox-runtime:compileDebugJavaWithJavac` PASS；`python tools/static_android_compile.py` PASS；
  `git diff --check` PASS。当前 APK SHA-256 为
  `3F8D3CB58B29E2FE9A39566DDBA7A8D277DB9318F0A120326DA9BE7683AB5E7E`。
- **阻断结论与恢复条件**：C4-R03 不能标记 `DONE`，因为红果/番茄小说最终启动矩阵未执行、fixture
  user1 未完成，且 C4-R04 fail-closed 与 C4-R05 两轮正式重验尚未执行。恢复时从当前 `C4-R03`
  继续，先完成剩余 240 rows（含红果/番茄小说完整双用户矩阵），再按任务书验证 C4-R04/R05；不得
  复制旧 PASS 或把本回执推进为 C4-R04。
- **最终续接预检**：已修正账本 `下一任务` 标题为脚本可解析的 `C4-R03`；随后运行
  `python scripts/verify-catch-up-continuation.py`，脚本按 fail-closed 规则报告
  `ledger next task C4-R03 is not first dependency-ready PENDING task None`。这是当前任务保持
  `BLOCKED`、没有可安全推进的 PENDING 任务的预期结果；本次不把 C4-R03 改成 PENDING，也不伪造
  C4-R04 续接通过。恢复执行前必须先满足本回执的剩余矩阵和阻断恢复条件。

### C4-R03：2 小时续接回执（2026-08-25）

- **状态与窗口**：按用户指示从 C4-R03 阻断恢复点继续运行 2 小时，窗口为 2026-08-25
  07:31:53–09:31:53（Asia/Shanghai）。工作区、Git 本地/远端 HEAD 和身份预检通过；MuMu
  `RD测试` 继续由名称动态解析，未硬编码 ADB 地址。到点停止番茄小说 runner，C4-R03 仍为
  `BLOCKED`，没有进入 C4-R04/R05、C6 或 OEM。
- **非重复矩阵结果**：延续先前最终代码证据后，当前非重复完成 404/500 rows，404/404 PASS、
  0 non-PASS：fixture user0/user1 各 50/50；DingTalk user0/user1 各 50/50；夸克 user0/user1
  各 50/50；红果 user0/user1 各 50/50；番茄小说 user0 4/50 PASS、user1 0/50。番茄小说
  user0 的第 3 个 cold case 在截止时只有 cold-stop 文件，没有 `case.json`，不计为通过；
  未启动番茄 user1。
- **新增证据目录**：fixture user1 完整补跑见
  `artifacts/capability-audit/catch-up-c4-r03/continuation-2h-fixture-u1-25-20260825`；红果
  双用户完整矩阵见
  `artifacts/capability-audit/catch-up-c4-r03/continuation-2h-hongguo-u0-u1-25-20260825`；
  番茄部分矩阵见
  `artifacts/capability-audit/catch-up-c4-r03/continuation-2h-fanqie-u0-u1-25-20260825`。
  三个新车道均无 `first-failure-full` 目录；已完成 rows 全部保存 request/operation ID、窗口、
  Surface、截图和设备证据。
- **样本结论边界**：红果导入 operation `SUCCEEDED` 并记录 `MIXED_ELF_MACHINE`，启动 100/100
  PASS；番茄 user0 导入 operation `SUCCEEDED` 并记录同类 anomaly，启动已完成的 4/4 PASS。
  夸克仍只作正向对照，不能用来推断红果或番茄；番茄完整双用户兼容性仍待验证。
- **重试审计**：本次新增非重复 154 rows 全部为 `attempt=1`、`retryBudget=0`、
  `automaticRetryPerformed=false`、`retryable=false`；没有重试、延长 sleep 或扩大 deadline。
  截止停止的番茄 cold case 是时间上限中断，不是产品 PASS/FAIL。
- **阻断结论与恢复条件**：C4-R03 仍不能标记 `DONE`，因为番茄小说缺少 user0 剩余 46 个和
  user1 全部 50 个最终 case，尚未达到 500 rows；C4-R04/R05 也尚未执行。下一次从 C4-R03
  继续，先补齐番茄小说 96 rows，再运行规定的 R04/R05 门禁；不得复制部分 PASS 或推进下一任务。

### C4-R03：重启后断点续接回执（2026-08-25）

- **状态**：`BLOCKED`。按用户指示安全停止番茄 user1 runner，保留已完成的 `cold-001`、`hot-001`
  和随后生成的 `cold-002` 首次失败证据；没有把已完成 case 重置成新的 attempt。C4-R03 仍是当前任务，
  没有进入 C4-R04、C4-R05、C6 或 OEM。
- **RD测试重启与续接预检**：停止 runner 后以实例名 `RD测试` 动态解析 MuMu index，再执行实例级
  `control restart`。重启前 boot ID 为 `60d44ff7-2d1b-44a3-8cec-7b1f0608b633`，重启后重新解析
  为 `70f2ef8b-daf7-4492-b011-4a1da57a5c49`；设备仍为 model `22041211A`、API 32。当前 ADB
  endpoint 只出现在动态 environment 快照，runner 没有硬编码地址。
- **断点 collector**：原 runner 只有从头 fail-fast 模式；本回执新增显式
  `MANUAL_RESUME_AFTER_RESTART` 起点参数和 `resume.json`，仅为测试采集器能力，不改生产代码。
  续接从番茄 `com.dragon.read` user1 `cold-002` 开始，使用独立 lane 记录
  `attempt=2/retryBudget=0/automaticRetryPerformed=false`，并通过 `previousLane` 链回首次失败。
  生产 readiness SLO、collector wait budget 和 fail-fast 规则均未放宽。
- **断点结果**：重启后 `cold-002` 仍失败，`readinessElapsedMs=42106`，分类为
  `READINESS_SLO_EXCEEDED`；`Activity` created/resumed、`FIRST_FRAME_DRAWN`、Window、Surface 和
  非黑截图均存在。运行器在保存完整 first-failure snapshot 后停止，未进入 `hot-002` 和后续 case。
  重启前 user0 `cold-001` 为 33167 ms 首次失败，user1 `cold-002` 为 31071 ms 首次失败；三次证据
  均保留 request/operation ID、logcat、dumpsys、截图、Window、Surface、进程和事务快照。
- **owner 分类**：两次 user1 `cold-002` 与 user0 `cold-001` 的共同日志均出现
  `GUEST_MAIN_THREAD_TIMEOUT`，调用链为 `GuestContentProviderFrameworkInterceptor` →
  `GuestRuntimeBrokerBridge`，触发点落在 `com.dragon.read` Mira plugin provider 访问；之后才绘制首帧。
  已确认 owner 保持 CAS 通用 Guest ContentProvider/launch readiness 边界；具体 app-side provider 触发
  和最小 CAS provider/broker 协议仍为待验证，不猜测为番茄专属兼容性，也不转交 SX/UI。新增
  `KI-R03-059`，状态 `RECORDED`、阻断为 true。
- **矩阵与重试审计**：番茄 user1 续接 lane 预期剩余 48 行，但在第一个续接行阻断；该 lane 实际
  观察 1 行且为非通过。原 user1 lane 已观察 `cold-001/hot-001` 通过和 `cold-002` 首次失败；原
  user0 lane 在 `cold-001` 首次失败。不能把这些不同 attempt 合并为 500/500 PASS，也不能用此前
  的 4 个 user0 PASS 推断番茄兼容。所有自动重试字段仍为 false，retry budget 为 0；没有延长 sleep、
  deadline 或不停重试。
- **机器证据**：
  `verification/catch-up/C4-R03/rd-acceptance/summary.json` 的 `continuationAfterRestart`；
  `artifacts/capability-audit/catch-up-c4-r03/continuation-final-fanqie-u0-u1-25-20260825`；
  `artifacts/capability-audit/catch-up-c4-r03/continuation-final-fanqie-u1-25-20260825`；
  `artifacts/capability-audit/catch-up-c4-r03/continuation-after-reboot-fanqie-u1-from-cold2-a2-20260825`。
- **验证命令**：`python -m py_compile tools/capability/run_c4_r03_rd.py`、
  `python scripts/test_catch_up_continuation.py`（6 tests）、summary JSON/YAML parse 和
  `git diff --check` PASS。
- **实现/证据提交 SHA**：`18c07cd3`（`test(c4): [C4-R03] preserve Fanqie timeout and restart resume`）。
- **本回执提交**：本段为独立的 `docs(progress): record [C4-R03] restart resume receipt` 提交。
- **下一任务**：继续为 `C4-R03`；由于阻断未解除，续接预检应 fail-closed，不能把下一任务改成
  `C4-R04`，也不能标记 C4-R03 `DONE`。

### C4-R03：Guest ContentProvider 初始化锁环修复与定向验证回执（2026-08-25）

- **状态**：`BLOCKED`。本轮只处理 CAS Guest ContentProvider 初始化锁环的生产修复、回归和定向
  验证；没有进入 C4-R04、C4-R05、C6 或 OEM 适配。历史首次失败仍是权威证据，不能被本轮定向
  PASS 覆盖。
- **根因与 owner**：在查阅并记录 NBB 的 ProcessRecord/Binder owner/death 状态、VA 的
  ActivityStack/进程启动与 Provider 生命周期实现后，结合首次失败完整栈确认：
  `GuestContentProviderFrameworkInterceptor` 的全局 `synchronized` 在
  `attachInfo()/prepare()` 和 Guest 主线程 Broker 调用期间保持，番茄 Mira plugin provider
  再通过 ContentResolver 回入另一个 Provider，形成 CAS 通用 ContentProvider/launch readiness
  锁环。owner 保持 CAS 通用边界，不猜测为番茄专属，也没有 SX/UI owner 证据。
- **修复范围**：按 authority single-flight；读取、发布和关闭只持有短 `stateLock`；Provider
  创建、Broker `prepare()`、反射和 shutdown 回调均在锁外；同 authority 的 Guest-main-thread
  回入 fail-closed。没有延长 15000 ms Guest timeout、冷/热 readiness SLO、sleep 或 retry budget，
  也没有提前实施 C4-R04/R05 的生产修复。
- **定向结果**：通过实例名动态解析 MuMu `RD测试`（boot ID
  `70f2ef8b-daf7-4492-b011-4a1da57a5c49`），对 `com.dragon.read` 7.1.9.32/71932（base 1、
  split 0、arm64-v8a）用户 0/1 冷/热各 1 轮，4/4 `LAUNCH_PASS`。readiness 为
  `15978/463/16744/626 ms`；Activity created/resumed、FIRST_FRAME_DRAWN、Window、Surface 和
  非黑截图均通过。每行 `attempt=1`、`retryBudget=0`、`automaticRetryPerformed=false`、
  `retryable=false`，case-scoped fatal markers 为空。完整 request/operation ID、环境、截图和
  快照见 `verification/catch-up/C4-R03/rd-acceptance/targeted-fix-20260825.json` 及其 raw lane。
- **门禁结论**：4/4 是修复后的定向观察，不是 500 行正式矩阵；番茄完整双用户矩阵仍缺失，
  C4-R03 不得改为 `DONE`，下一任务继续为 `C4-R03`。
- **实现/证据提交 SHA**：`d8797c89`（`fix(c4): break Guest ContentProvider initialization lock cycle`）。
- **本回执提交**：本段为独立的进度回执提交；按两提交协议晚于实现/证据提交。
- **下一任务**：继续 `C4-R03`；先补齐规定矩阵并完成正式门禁，之后才可评估 C4-R04。

### C4-R03：按恢复条件继续最终矩阵（2026-08-25）

- **状态**：`BLOCKED`。本段从此前 `BLOCKED` 恢复后执行正式矩阵；DingTalk 优先续接在首次
  readiness SLO 失败后再次观察到同类阻断，故不能继续保持 `IN_PROGRESS`。用户明确要求继续完成
  C4-R03；此前 `BLOCKED` 的直接恢复条件
  （CAS Provider 锁环修复、静态/Gradle 验证、Fanqie 用户 0/1 冷热 4/4 定向通过）已满足。
- **续接边界**：原 404/500 rows 是修复前代码的历史矩阵证据，本轮不把它们直接升级为修复后
  通过；将使用当前 APK SHA-256 `89DCBEB082F9F6452813CF363BB5E5AE17632ACE2031EAE4490D17C2FB6B75A1`
  重新执行 C4-R03 规定的 fixture、DingTalk、夸克、红果、番茄小说双用户冷/热矩阵。夸克仍只作
  正向对照，不推导红果或番茄兼容性。
- **设备与重试策略**：运行前再次按实例名动态解析 MuMu `RD测试`，ADB endpoint 仅写入环境快照；
  每个 case 保持 `attempt=1`、`retryBudget=0`、`automaticRetryPerformed=false`，首次失败立即
  保存日志、dumpsys、窗口、Surface、进程、事务和截图并停止该 lane，不延长 deadline、不增加
  sleep、不自动重试。
- **完成条件**：500/500 rows 满足 readiness、首帧、Window、Surface、截图和无 fatal/ANR 后，
  再把 C4-R03 更新为 `DONE`，写入完整回执并进入两提交/推送/远端对比；任一首次失败则保持
  `BLOCKED` 并记录阻断证据。

### C4-R03：DingTalk 优先、fixture 最后续接回执（2026-08-25）

- **任务 ID / 名称**：`C4-R03` / 启动 readiness、窗口合同与超时修复。
- **最终状态**：`BLOCKED`。本回合按用户顺序先跑 DingTalk；fixture 保持暂停并留到最后，未用
  fixture 或夸克对照替代 DingTalk 的失败，也未进入 C4-R04、C4-R05、C6 或 OEM 适配。
- **开始/结束时间（Asia/Shanghai）**：2026-08-25 17:55 / 2026-08-25 18:06。
- **执行环境**：Windows PowerShell；分支
  `feature/t57-r03-va-pro-capability-campaign`；开始 HEAD `c07f96f80c85e9086875ec27504f2abf32f64b71`；
  MuMu `RD测试` 通过实例名动态解析，重启后 boot ID
  `7fec8065-1d25-4e25-8c53-f7cb7eb3b26a`；model `22041211A`、API 32、ABI
  `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`。ADB endpoint 只存在于 environment evidence，
  未写入 runner 选择器。
- **样本事实**：DingTalk 动态记录为 `com.alibaba.android.rimet`、7.8.10/1178、base 1、split 0、
  primary ABI `arm64-v8a`，launchable Activity 为
  `com.alibaba.android.rimet.biz.LaunchHomeActivity`。
- **执行与首次失败**：正式优先 lane
  `artifacts/capability-audit/catch-up-c4-r03/fix-dingtalk-u0-u1-a1-20260825` 产生 84 行，
  83 行满足 readiness 门禁；user1/hot-017 首次非通过，readiness `11720 ms`，request ID
  `33720dd87741423c9042654f7f5a3d99`，operation ID
  `33720dd87741423c9042654f7f5a3d99-launch`。该行已立即保存 logcat、dumpsys、Window、
  Surface、进程、事务、设备快照和截图。
- **重启后有限观察**：按 NBB/VA 生命周期参考和 fail-fast 规则，重启后只做独立的
  `MANUAL_RESUME_AFTER_RESTART` 观察，lane 为
  `artifacts/capability-audit/catch-up-c4-r03/fix-dingtalk-u1-r17-a2-20260825`，共 6 行，
  其中 cold/hot-017、cold/hot-018 和 cold-019 通过，hot-019 再次首次非通过，readiness
  `10179 ms`，超出 hot SLO `179 ms`，request ID `417dbc1ae7534d39b82429871b779de6`，
  operation ID `417dbc1ae7534d39b82429871b779de6-launch`。该行完整 first-failure snapshot
  已保留；没有自动重试、sleep 延长、deadline 延长或 retry budget 增加。
- **证据结论**：两次失败最终均有 `ACTIVITY_RESUMED`、`FIRST_FRAME_DRAWN`、非空 Window、
  非空 Surface 和非黑截图，不是黑屏或 SX/UI Surface 缺失。hot-019 的首次失败快照记录了
  三条 `lowmemorykiller ... reason: device is not responding`，目标为 WebView、Contacts、
  ExternalStorage；没有 DingTalk 或 CAS host 的同类直接 LMK kill，也没有
  `GUEST_MAIN_THREAD_TIMEOUT`、`ANR in` 或 `FATAL EXCEPTION`。设备资源压力是已确认环境信号，
  但不是完整因果证明。
- **owner 与 Known Issues**：导入/catalog 已成功，owner 继续保持 CAS 通用 launch/readiness
  边界；MuMu 资源压力作为已确认贡献信号单独记录。没有证据转交 SX/UI，也不根据夸克正向对照
  推断其他商业样本。新增 `KI-R03-060`，状态 `RECORDED`、`blocks_current_campaign: true`；
  恢复条件是保留资源快照、分离 CAS process/prepare 延迟与 MuMu responsiveness，再决定是否
  需要 CAS 通用设计变更。
- **变更文件**：`tools/capability/run_c4_r01_rd.py`（Windows 长路径交易证据保存的诊断性
  collector 修正）、`docs/review/C4_R03_LAUNCH_READINESS_WINDOW_DESIGN_20260824.md`、
  `docs/review/KNOWN_ISSUES.yaml`、`verification/catch-up/C4-R03/dingtalk-priority-20260825.json`；
  fixture 仍未启动。
- **验收命令与结果**：`python -m py_compile tools/capability/run_c4_r01_rd.py tools/capability/run_c4_r03_rd.py`；
  `python -m json.tool verification/catch-up/C4-R03/dingtalk-priority-20260825.json`；
  `git diff --check` 均通过。C4-R03 500 行门禁未满足，不能标记 `DONE`。
- **机器证据**：完整索引见
  `verification/catch-up/C4-R03/dingtalk-priority-20260825.json`；原始首次失败目录见上述两条
  lane 的 `attempts/dingtalk/user-1/hot-017/first-failure-full` 和
  `attempts/dingtalk/user-1/hot-019/first-failure-full`。
- **下一任务**：仍为 `C4-R03`；续接预检必须 fail-closed。DingTalk lane 停止后仍应按用户要求
  将 fixture 作为最后 target 执行；本回执后的更正段已完成该顺序，不能推进 C4-R04。

### C4-R03：fixture 最后续接更正回执（2026-08-25）

- **状态**：`BLOCKED`（fixture 续接本身为 `PASS_OBSERVATION`，但 DingTalk 首失败仍阻断 C4-R03）。
  先前在 DingTalk lane fail-fast 后停止整个流程是顺序判断错误；本回执更正为 DingTalk lane 停止后
  继续执行 fixture，且 fixture 是最后一个 target。未进入 C4-R04、C4-R05、C6 或 OEM。
- **执行环境**：通过实例名动态解析 MuMu `RD测试`；boot ID
  `7fec8065-1d25-4e25-8c53-f7cb7eb3b26a` 与 DingTalk lane 相同，未发生设备重启；ADB endpoint
  仅记录在环境快照。fixture package `com.warden.controlledsandbox.fixture`、version
  `1.0-fixture/1`、base 1、split 0、ABI `arm64-v8a,x86_64`。
- **续接策略**：fixture user0 已有 50 行，user1 已有 15 行。由于 DingTalk 在两段 fixture 之间
  运行，旧 hot 前置不再有效；从 user1 `cold-008/hot-008` 重建有效对照，再继续 iteration 9–25。
  新 lane 产生 36 行，其中 `cold-008` 是替换观察，新增唯一行 35 行。runner 原始 resume 标签
  是通用 `MANUAL_RESUME_AFTER_RESTART`，但 boot ID 未变化、实际没有重启；高层证据按实际原因记为
  `MANUAL_CONTINUATION_AFTER_DINGTALK_TARGET_SWITCH`。
- **结果**：36/36 PASS，0 non-pass；按 `(user, mode, iteration)` 去重后 fixture 唯一观察为
  100/100：user0 50/50、user1 50/50、cold 50/50、hot 50/50。所有行都有
  `REQUEST_ACCEPTED → GUEST_READY → ACTIVITY_RESUMED → FIRST_FRAME_DRAWN`、非空 Window、
  非空 Surface、非黑截图和空 fatal/ANR markers。续接行记录 `attempt=2`、`retryBudget=0`、
  `automaticRetryPerformed=false`、`retryable=false`，无自动重试；这不是一次未经中断的全量
  attempt=1 门禁，因此不能单独关闭 C4-R03。
- **机器证据**：
  `verification/catch-up/C4-R03/fixture-priority-last-20260825.json`；
  `artifacts/capability-audit/catch-up-c4-r03/fix-fixture-u1-after-dingtalk-last-a2-20260825`；
  汇总已写入 `verification/catch-up/C4-R03/rd-acceptance/summary.json`。
- **验收结论**：fixture 最后 target 已完成当前缺失唯一观察，不能覆盖 DingTalk
  `user1/hot-017` 与 `hot-019` 的 readiness 首失败；C4-R03 继续 `BLOCKED`，下一任务仍为
  `C4-R03`，不得推进 C4-R04。

### C4-R03：DingTalk 有界复现回执（2026-08-25）

- **状态**：`BLOCKED`；本回执状态为 `NON_REPRODUCED_NOT_PASS`，不是 `PASS`，不改变 C4-R03
  的阻断状态，也不推进 C4-R04/R05、C6 或 OEM 适配。
- **用户指定动作**：fixture 最后续接完成后，对 DingTalk 直接建立一条有界人工复现 lane，目标是
  覆盖历史首次失败点；不是自动重试，不延长 sleep/deadline，不增加 retry budget。运行前按实例名
  动态解析 MuMu `RD测试`；boot ID `7fec8065-1d25-4e25-8c53-f7cb7eb3b26a` 未改变。
- **重试审计**：`attempt=3`、`retryBudget=0`、`automaticRetryPerformed=false`、
  `retryable=false`；没有自动重试。runner 在覆盖历史点并生成 `cold/hot-020` 后、进入无关的
  `cold-021` 前主动停止，避免把一次复现验证扩大成无意义矩阵重跑。
- **结果**：`cold/hot-017`、`cold/hot-018`、`cold/hot-019`、`cold/hot-020` 共 8 行全部通过；
  历史失败点 `hot-017` readiness `8546 ms`、`hot-019` readiness `8515 ms`，均低于 hot SLO。
  8 行均有首帧、Window、Surface、非黑截图且无 FATAL/ANR。两条通过行仍有与目标无关的
  WebView LMK 标记，故 LMK 只能作为环境信号，不能单独解释历史 readiness 失败。
- **证据与结论**：机器回执为
  `verification/catch-up/C4-R03/dingtalk-repro-after-fixture-20260825.json`，原始 lane 为
  `artifacts/capability-audit/catch-up-c4-r03/dingtalk-repro-after-fixture-a3-20260825`。
  本次未复现只能说明问题具有非确定性；历史 `hot-017`/`hot-019` 首次失败及其完整日志、
  dumpsys、窗口、Surface、进程、事务和设备快照继续有效，不能将历史证据改写为 PASS。
- **开始基线**：`999ffa47f2786c69d8c91c2427b8f29ddf600933`（fixture 最后续接回执之后、
  本次有界复现之前）。
- **实现/证据提交 SHA**：`736464d6515efa037d82220bf7f47648d50b148f`
  （`test(c4): [C4-R03] record bounded DingTalk non-reproduction`）。
- **回执提交**：本段为独立的
  `docs(progress): record [C4-R03] bounded replay receipt` 进度回执提交。
- **owner 与门禁**：当前仍无证据转交 SX/UI，也不能据夸克正向对照推断红果或番茄小说兼容性；
  owner 保持 CAS 通用 launch/readiness 边界，CAS process/prepare 延迟与 MuMu responsiveness
  的因果分离待验证。`KI-R03-060` 保持 `RECORDED` 且阻断当前 campaign；500 行正式门禁仍未满足，
  C4-R03 不能标记 `DONE`。
- **下一任务**：仍为 `C4-R03`；续接预检应 fail-closed，不能进入 C4-R04。

### C4-R03：用户批准的条件性关账与后续回归安排（2026-08-25）

- **状态**：`DONE`（用户明确批准的残余风险豁免/行政关账）。本状态不改写正式证据：
  `KI-R03-060` 仍为开放 Issue，500 条正式首试矩阵仍未闭合；本回执不宣称 500/500 PASS，
  也不表示 C4 阶段已经关闭。
- **用户指令与决策**：保留 DingTalk readiness 问题为 Issue，接受当前残余风险，先关闭 C4-R03
  账本任务并让远端进入 C4-R04；后续在 C4-R04/C4-R05 将该 Issue 作为强制回归项。没有执行
  C4-R04 生产修复、没有删除首次失败证据，也没有用最新复现 PASS 覆盖历史失败。
- **已知结果**：500 个唯一坐标的当前汇总仍为 `482 PASS + 8 首次失败 + 10 未执行`；DingTalk
  的 `hot-017`/`hot-019` 首次失败仍在 `KI-R03-060` 中，后续有界复现通过仅作为非确定性证据。
- **Issue 状态**：`KI-R03-060` 保持 `RECORDED`、`ACCEPTED_RISK_FOR_C4_R03_ADVANCE_NOT_FIXED`，
  `follow_up_required=true`，后续任务为 `C4-R04/R05 regression`。CAS 与 MuMu 的主导因果仍待
  分离；该 Issue 不得被描述为已修复。
- **机器证据**：
  `verification/catch-up/C4-R03/rd-acceptance/summary.json`、
  `verification/catch-up/C4-R03/dingtalk-repro-after-fixture-20260825.json`、
  `docs/review/C4_R03_LAUNCH_READINESS_WINDOW_DESIGN_20260824.md` 和
  `docs/review/KNOWN_ISSUES.yaml`。
- **开始基线**：`0be42eb6d053945fa6caf79bc42f9923d73c1c56`；工作区在本次变更前干净，
  分支仍为 `feature/t57-r03-va-pro-capability-campaign`。
- **实现/证据提交 SHA**：`18d79039027cdab2fc1d18e8cb6d315b222bdb36`
  （`docs(c4): [C4-R03] record accepted-risk closure and regression`）。
- **偏离任务书**：按用户明确风险接受推进；任务书规定的 500/500 首试门禁被延期到后续回归，
  未静默改写任务书验收标准。
- **回执提交**：本段为独立的 `docs(progress): record [C4-R03] receipt` 提交。
- **下一任务**：`C4-R04`（PENDING）；下一环境可从 C4-R04 续接，但必须携带
  `KI-R03-060` 回归要求，且不得把本次行政 DONE 当作 C4 阶段关闭。

### C4-R04：C4 fail-closed 验收编排（2026-08-25）

- **状态**：`DONE`。本回执只关闭 C4-R04 任务，不关闭 C4 阶段；C4-R05 的正式 MuMu
  双轮矩阵、C1/C2/C4/SX 门禁和 30 分钟双用户压力仍是强制后续项。
- **执行窗口与基线**：Asia/Shanghai 2026-08-25 19:55:58 至 20:18；Windows
  PowerShell；分支 `feature/t57-r03-va-pro-capability-campaign`；开始基线为
  `46a59f3a9c1f8703213705562f9f48c2bff02691`。开始时远端与基线一致；工作区仅保留续接预检
  自动更新的 `verification/catch-up/C0-T01/continuation-preflight.json`，该既有证据变更已纳入
  本轮记录。Git 身份为 `OpenAI <openai@users.noreply.github.com>`。
- **设备快照**：MuMu `RD测试` 由实例名动态解析为本轮证据中的
  `127.0.0.1:16416`，API 32，ABI `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`，model
  `22041211A`，Android ID `398eea33120cd887`，boot ID
  `7fec8065-1d25-4e25-8c53-f7cb7eb3b26a`。该 endpoint 只存在于环境快照，runner 没有硬编码
  ADB serial。
- **任务边界**：按任务书和 `C4_RD_RETEST_ROOT_CAUSE_AND_ACCEPTANCE_PLAN_20260824.md`，
  本任务实现可审计的 fail-closed 验收编排、五类故障注入、独立 recovery contract 和一条
  fixture live smoke；没有把离线注入结果冒充商业全矩阵，也没有提前宣称 C4-R05 通过。
- **实现与证据提交 SHA**：`57d2c34a04c3c33b0292934373d763c3e4369ea7`（实现初始提交）及
  `1d9b83d54c13d2a758752281dbc492859d8bd05d`（修正合成 mutation fixture 包名并重生成证据的
  补充实现提交，作为本任务最终实现提交）。远端已核验到
  `1d9b83d54c13d2a758752281dbc492859d8bd05d`。
- **实现内容**：新增 `tools/capability/c4_r04_fail_closed.py`，将
  `REQUEST_ACCEPTED → GUEST_READY → ACTIVITY_RESUMED → FIRST_FRAME_DRAWN` 阶段时序、
  request/operation/package/user/revision 关联、动态 Window/Surface/截图、FATAL/ANR、
  retryDecision 和 mutation residue 纳入判定；静态 `LAUNCH_PASS` 等 marker 明确为非权威。
  新增 `tools/capability/run_c4_r04_rd.py`，failure-injection、recovery、live 三种模式均为
  首次失败停止、retry budget=0；live 模式只委托一次现有 R03 fail-fast runner。新增静态门禁
  `scripts/check-c4-r04-fail-closed.py`、7 项单测 `scripts/test_c4_r04_fail_closed.py`，并补充
  `docs/review/C4_R04_FAIL_CLOSED_ACCEPTANCE_ORCHESTRATION_DESIGN_20260825.md`。
- **离线故障注入结果**：`verification/catch-up/C4-R04/acceptance/failure-injection/failure-injection-summary.json`
  为 `PASS`，五个场景全部按预期由 runner 返回 `FAIL`，且保留首次失败：

  | 场景 | 预期/实际分类 | runner 状态 |
  |---|---|---|
  | `windows-empty` | `WINDOWS_EMPTY` | `FAIL` |
  | `draw-timeout` | `DRAW_TIMEOUT` | `FAIL` |
  | `bind-failure` | `BIND_FIRST_ATTEMPT_FAILED` | `FAIL` |
  | `duplicate-add` | `DUPLICATE_MUTATION_ACCEPTED` | `FAIL` |
  | `staging-residue` | `STAGING_RESIDUE` | `FAIL` |

- **独立恢复结果**：`verification/catch-up/C4-R04/acceptance/recovery/recovery-summary.json`
  为 `PASS`。首失败与恢复使用不同 request ID，恢复不是隐式 retry；两阶段都记录
  `attempt=1`、`retryBudget=0`、`automaticRetryPerformed=false`、`retryDecision=NO_RETRY`。
- **设备 live smoke**：第一次 wrapper 运行发现委托文件名解析错误（wrapper 错找
  `summary.json`，实际委托输出为 `c4-r03-summary.json`），该 harness 错误保留在被忽略的
  `verification/catch-up/C4-R04/live-smoke/` 原始目录中并修正；它不是 runtime failure，也
  没有触发自动 retry。修正后的独立 lane
  `verification/catch-up/C4-R04/live-smoke-fixed/live-summary.json` 为 `PASS`，fixture
  user0/user1 冷/热共 4/4，全部具备动态 FIRST_FRAME_DRAWN、非空 Window、非空 Surface、非黑
  截图、空 FATAL/ANR、`attempt=1` 和零 retry budget。
- **验收命令**：`python scripts/test_c4_r04_fail_closed.py`（7 tests OK）；
  `python scripts/check-c4-r04-fail-closed.py`（PASS）；`python -m py_compile
  tools/capability/c4_r04_fail_closed.py tools/capability/run_c4_r04_rd.py
  scripts/test_c4_r04_fail_closed.py scripts/check-c4-r04-fail-closed.py`（PASS）；
  两次 `run_c4_r04_rd.py --mode failure-injection/recovery`（PASS）；
  `python scripts/test_catch_up_continuation.py`（6 tests OK）；
  `python tools/capability/validate_campaign_infra.py`（PASS）；`git diff --check`（PASS）。
- **Known Issues 结论**：`KI-R03-056` 已按本任务的 fail-closed 注入、独立 recovery 和 live
  smoke 证据改为 `FIXED` 且不再阻断当前 campaign；`KI-R03-055` 仍仅在 import compatibility
  范围为 `FIXED`。`KI-R03-053`、`KI-R03-054`、`KI-R03-057`、`KI-R03-058`、`KI-R03-059`
  保持 `RECORDED`/阻断；`KI-R03-060` 保持 `RECORDED`、接受风险但不阻断当前推进，必须在
  C4-R05 回归并不得被描述为已修复。
- **偏离与残余风险**：R04 没有声称 500/500 首试、商业样本双轮、C1/C2/C4/SX F1-F5 或
  30 分钟压力已通过；这些门禁严格留给同一最终提交上的 C4-R05。历史首次失败证据没有被
  PASS 观察覆盖，旧 C4-T05 runner 仍是历史证据，不用于 C4 关门。
- **回执提交**：本段为独立的 `docs(progress): record [C4-R04] receipt` 提交；实现提交和
  本回执提交均已推送，并以 `git ls-remote` 核验远端 HEAD 与本地一致。
- **下一任务**：`C4-R05`（PENDING）；下一环境必须从本回执、续接预检和
  `KI-R03-053/054/057/058/059/060` 回归要求开始，完成正式双轮矩阵后才能判断 C4 是否关闭。

### C4-R05：100-case 有界进度回执（2026-08-25）

- **状态**：`IN_PROGRESS`。本段是用户要求“完成这 100 个后停止”的进度回执，不是
  C4-R05 完成回执，不是正式 `PASS`，也不关闭 C4 阶段。当前任务和下一任务仍为 `C4-R05`。
- **执行范围与停止**：在同一 clean commit `2667d0f3956751f85c83bec4ade4f89145e7bb2e` 上，
  R05 第一轮 `clean-install-cold` 的 R03 fixture/user0 lane 完成 100/100：cold 50/50、hot 50/50。
  用户指定的有界范围完成后停止父进程；停止时间为 2026-08-25 22:18:26（Asia/Shanghai）。
  停止后无 Python R05/R03 进程，未继续执行剩余 400 行。
- **结果**：100/100 `case.json` 可解析且满足首试合同；100/100 `failureDetected=false`、
  `errorClassification=NONE`、`attempt=1`、`retryBudget=0`、`automaticRetryPerformed=false`，
  均有 `FIRST_FRAME_DRAWN`、Window、Surface、非黑截图，FATAL/ANR marker 为 0。该结果仅证明
  已完成的 fixture/user0 100 行，不外推到 DingTalk、夸克、红果、番茄小说、user1、第二轮或压力。
- **机器证据**：聚合核验见
  `verification/catch-up/C4-R05/round-1-clean-install-cold/launch-matrix/fixture-user0-100-summary.json`；
  逐用例目录为
  `verification/catch-up/C4-R05/round-1-clean-install-cold/launch-matrix/attempts/fixture/user-0/`，
  最新文件为 `hot-050/case.json`；设备与开始基线见
  `verification/catch-up/C4-R05/start-state.json` 和 `environment-at-start.json`。
- **实现与证据提交 SHA**：`d75d0dbcae4a223abdad4d109aaf03dcf97b889f`、
  `2667d0f3956751f85c83bec4ade4f89145e7bb2e`；本段回执提交完成后，须与实现提交一起推送并
  用 `git ls-remote` 核验远端 HEAD。
- **回执提交**：本段使用独立主题 `docs(progress): record [C4-R05] receipt` 固化；实现/证据
  提交与本回执提交均推送到当前任务分支并执行远端 HEAD 校验。
- **偏离与遗留风险**：按用户有界停止指令，R05 任务书要求的本轮剩余 400 行、第二轮、
  C1/C2/C4/SX 回归、双用户压力、完整添加矩阵及 C4 关门证据均未执行；不能进入 C6。
- **下一任务**：仍为 `C4-R05`；后续必须显式续接并完成完整两轮正式门禁，不能把本段 100-case
  观察升级为 R05 或 C4 `DONE`。

### C4-R05：用户批准的减半用例范围（2026-08-25）

- **状态**：`IN_PROGRESS`。用户明确要求将剩余用例数减半；本段同步任务书 1.3 和执行器的验收范围，
  不代表 C4-R05 或 C4 已完成。
- **范围变更**：C4 阶段只执行 1 轮启动矩阵；每个目标/用户冷 25 + 热 25，五个目标和两个用户合计
  500 个启动观察；每轮添加门禁为 fixture 25、商业样本各 5。两用户各 15 分钟且至少 50 周期的压力
  门禁保持不变。待 C0-C7 全部任务完成，再执行两轮 `loops=50` 的整体验收。
- **依据与影响**：依据为用户 2026-08-25 明确指令；首帧、Window、Surface、非黑截图、FATAL/ANR、
  retryBudget=0、无自动重试、资源/事务收敛和 fail-closed 规则不变。该变更已写入
  `docs/plans/CAS_VA_PRO_CATCH_UP_EXECUTION_TASK_BOOK_20260821.md` 版本 1.4，机器可读记录见
  `verification/catch-up/C4-R05/continuation-v2241a-a1-20260825/scope-reduction.json`。
- **当前证据**：新环境 `V2241A`、API 32、boot ID `a90ff55f-bed6-4881-b9d9-a5aa327cfdda`；fixture/user1
  续接 lane 在用户指令到达时保存 75 个完整 `case.json`（cold 38、hot 37），0 首发失败；中断点和空的
  `hot-038` 目录均保留，未将中断观察伪装成完整 PASS。
- **偏离任务书**：无静默偏离；任务书和执行器已显式更新，旧 50 次历史证据保留为补充，当前 C4-R05
  正式阶段验收以 1 轮、25 次重复为准，完成后可关闭 C4。待 C0-C7 全部任务完成，再另行使用两轮
  `loops=50` 执行整体验收。C4 仍需完成这一轮减半矩阵、减半添加门禁、C1/C2/C4/SX 回归和双用户压力。
- **验收命令与结果**：`python -m py_compile tools/capability/run_c4_r02_rd.py tools/capability/run_c4_r03_rd.py
  tools/capability/run_c4_r05_rd.py` PASS；`python scripts/check-c4-r05-orchestrator.py` PASS；`git diff --check` PASS。
- **实现提交 SHA**：`4e15d219`（任务书 1.4、分层 scope 编排器、减半添加矩阵、动态视觉等待和续接证据）。
- **回执提交**：本段使用独立主题 `docs(progress): record [C4-R05] scope receipt`；推送后用
  `git ls-remote --heads origin feature/t57-r03-va-pro-capability-campaign` 核验远端 HEAD。
- **下一任务**：仍为 `C4-R05`；从当前新环境和保存的续接证据继续执行剩余减半矩阵。

### C4-R05：DingTalk 冷启动失败与 8 GB 重启复验回执（2026-08-25）

- **状态**：`BLOCKED`。用户明确要求将首发失败不计入用例数，并把 `RD测试` 模拟器内存提升到 8 GB、重启后重新测试；首发证据仍保留，未覆盖或删除。
- **首发失败**：减半正式矩阵在 `dingtalk/user0/cold-001` 的首个观察失败，耗时约 96.2 秒，生产返回
  `GUEST_PREPARE_MAIN_THREAD_TIMEOUT`；`import-only` 通过，截图非黑且 Surface 非空，但没有
  `ACTIVITY_RESUMED/FIRST_FRAME_DRAWN`。按用户指令执行独立手动 `attempt=2` 后，8 GB 配置已由
  MuMu `vms/MuMuPlayer-12.0-1/configs/vm_config.json` 的 `memory=8.000000` 和 Guest
  `/proc/meminfo MemTotal=8157056 kB` 复核，重试仍在约 97.9 秒以同一错误失败。
- **重试范围与策略**：只重跑 `dingtalk/user0`、`loops=25` lane 的 `cold-001`，使用
  `--resume-target dingtalk --resume-user 0 --resume-iteration 1 --resume-mode cold
  --resume-attempt 2`；`retryBudget=0`、无自动重试，旧首发目录通过 `--resume-of` 关联。
- **证据**：首发 lane 为
  `verification/catch-up/C4-R05/continuation-v2241a-a1-20260825/round-1/launch-matrix-dingtalk-user0/`；
  8 GB 重启复验 lane 为
  `verification/catch-up/C4-R05/continuation-v2241a-a1-20260825/round-1/launch-matrix-dingtalk-user0-a2/`，
  其中 `c4-r03-summary.json`、`case.json`、`emulator-memory8.json` 和 `first-failure-full/` 为可审计证据。
- **分类与影响**：两次结果均为 `LAUNCH_RESULT_NOT_PASS`，Guest 动态 Receiver 注册栈继续出现
  `GUEST_MAIN_THREAD_TIMEOUT/GUEST_NOT_PREPARED`；本次内存调整未清除阻断。该问题已登记为 `KI-R03-061`，
  仍阻断 C4-R05；不将慢启动、截图或后续 lane 结果升级为 PASS。
- **下一步**：按用户“记录后提交、停止任务”指令停在当前阻断点。后续只有在 Guest dispatcher/receiver
  owner 修复或有界恢复方案明确后，才能从本回执继续执行剩余商业矩阵并重新评估 C4 关门。

### C4-R05：本机 DingTalk user0 首例复验与继续矩阵回执（2026-08-26）

- **状态**：`IN_PROGRESS`。按用户最新条件执行：新机器首例失败保留为历史证据；本机动态解析
  `RD测试` 后，若首例复现同一异常或任意其他失败则停止。本机 `dingtalk/user0/cold-001`
  首次尝试通过，因此按用户指示忽略新机器这条观察并继续本机 lane；C4-R05 未完成，C4 阶段未关闭。
- **开始基线**：分支 `feature/t57-r03-va-pro-capability-campaign`，commit
  `4ad796a24acd39216b439862b50e746efaae066e`；本机证据 lane 使用该 clean commit 现有 APK，
  未启用自动重试或隐式补跑。
- **执行环境**：MuMu `RD测试` 动态解析到 `127.0.0.1:16416`，API 32、ABI
  `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`、boot ID
  `7fec8065-1d25-4e25-8c53-f7cb7eb3b26a`、Android ID `398eea33120cd887`、Redmi
  `22041211A`；DingTalk 动态发现为 `com.alibaba.android.rimet` 7.8.10/1178，base 1、split 0、
  primary ABI `arm64-v8a`。
- **验收命令**：`python tools/capability/run_c4_r03_rd.py --instance-name 'RD测试' --loops 25
  --users 0 --targets dingtalk --output verification/catch-up/C4-R05/continuation-local-dingtalk-user0-20260825-235820`。
- **结果**：冷启动 25/25、热启动 25/25，共 50/50；runner `PASS`、`blockedAt=null`；首次尝试
  失败 0、FIRST_FRAME_DRAWN 缺失 0、FATAL/ANR 0、Surface 空 0、黑屏/透明/均匀截图 0，
  所有行 `errorClassification=NONE`。冷启动 readiness 21,441–23,977 ms，热启动 7,747–8,944 ms。
  首例截图是 DingTalk 首次启动隐私协议页，证明真实首帧和非黑内容；采集器没有自动点击同意，
  不将其描述为协议确认后的会话首页。
- **证据目录**：`verification/catch-up/C4-R05/continuation-local-dingtalk-user0-20260825-235820/`；
  汇总、设备、发现、
  每行 `case.json` 和首例截图均已保留。结构化 lane 回执见该目录 `local-lane-receipt.json`。
- **问题处理**：KI-R03-061 的两次新机器失败证据仍保留，`acceptance=NOT_FIXED`；本机 50/50
  只证明该提交在本机 RD 环境未复现，不清除该 Known Issue，也不替代另一台机器 user1 的独立
  证据。按用户条件，本条不再阻止本次继续矩阵，但仍属于最终 C4-R05 回归与关门风险。
- **实现/证据提交 SHA**：`49f11df397c19daa4244a0f99f82a84faa899b46`
  （`test(c4): [C4-R05] record local DingTalk user0 lane`），已推送并用 `git ls-remote` 验证。
- **下一步**：继续当前 R05 剩余减半矩阵：本机 user0 的 fixture、夸克、红果、番茄小说及所需
  添加门禁、C1/C2/C4 回归和双用户压力；另一台机器 user1 继续使用独立 `RD测试` 和独立证据目录。
  只有所有门禁、回归和压力均通过后，才重新评估 C4 关门。

### C4-R05：本机 R04 与减半添加门禁回执（2026-08-26）

- **状态**：`IN_PROGRESS`。这是当前 C4-R05 的继续执行回执，不关闭 C4 阶段；本机 DingTalk
  user0 启动矩阵已由上一回执记录，当前补齐 R04 合同和 R02 减半添加门禁。
- **执行环境**：同一动态 `RD测试`，解析到 `127.0.0.1:16416`，API 32、boot ID
  `7fec8065-1d25-4e25-8c53-f7cb7eb3b26a`、Android ID `398eea33120cd887`；DingTalk、夸克、
  红果、番茄的 package/version/base/split/ABI 由设备运行时发现并保存在样本清单。
- **C4-R04 验收**：`run_c4_r04_rd.py --mode failure-injection` 与 `--mode recovery` 均为
  `PASS`；failure-injection 的 windows-empty、draw-timeout、bind-failure、duplicate-add、
  staging-residue 均正确得到预期 FAIL 分类且首失败保留；recovery PASS，retry budget=0、无自动重试、
  动态首帧/Window/Surface/非黑截图门禁通过。证据见
  `verification/catch-up/C4-R05/continuation-local-r04-20260826/`。
- **C4-R02 添加门禁**：执行 `python tools/capability/run_c4_r02_rd.py --instance-name 'RD测试'
  --reduced-r05-scope --output verification/catch-up/C4-R05/continuation-local-add-gate-20260826`；
  fixture 25 个 add/delete/re-add 循环，DingTalk、夸克、红果、番茄各 5 个循环，合计 137 条操作，
  `status=PASS`、`firstFailureCount=0`、全部 `attempt=1`、无自动重试。并发添加为一成功一
  `MUTATION_BUSY` 且同 operation ID，判定 `CONCURRENT_ADD_SINGLE_FLIGHT_PASS`；`.install-*` 残留和
  active transaction 均为空。
- **证据**：添加汇总为该目录 `summary.json`，逐条操作为 `operations.json`，动态样本为
  `sample-inventory.json`，并发和清理结果分别为 `concurrent-add.json`、`residue.json`；结构化回执为
  `local-r04-add-gate-receipt.json`。
- **实现/证据提交 SHA**：`31efe59d40c3f1bfd28bc6b3239c10a7900c631e`
  （`test(c4): [C4-R05] record local R04 and add gates`），已推送并用 `git ls-remote` 验证。
- **下一步**：继续本机 user0 的剩余 launch matrix（fixture、夸克、红果、番茄，各 25 cold + 25 hot），
  同时保留已完成 DingTalk 50/50 作为独立 lane；之后执行 C1/C2/C4/SX 回归和本机压力，另一台机器
  user1 的独立证据仍需汇总到同一最终提交范围后才能重新评估 C4 关门。

### C4-R05：本机 user0 Quark 宿主低内存首发阻断回执（2026-08-26）

- **状态**：`BLOCKED`。本机剩余启动矩阵按首次失败即停执行；fixture 已完成 50/50，Quark
  `cold-001`、`hot-001`、`cold-002`、`hot-002` 通过后，在 `quark/user-0/cold-003` 首次失败，
  因此没有继续红果或番茄，也没有执行自动重试。
- **首发合同**：请求 `requestId=4647d04f6e5540b2a4c74c313331f311`，
  `attempt=1`、`retryBudget=0`、`automaticRetryPerformed=false`；runner 返回
  `LAUNCH_RESULT_NOT_PASS`，原因是 `RD_ENVIRONMENT_RESOLUTION_BLOCKED:
  debug-command-result timeout`，同 requestId 的生产 `operation` 不可用。
- **根因分类**：设备 `ApplicationExitInfo` 显示被杀进程为
  `com.warden.controlledsandbox.debug` pid 17929，原因 `LOW_MEMORY`，PSS 约 120 MB、RSS
  约 201 MB。失败快照仍有 Quark focused Window、非空 Surface、非黑截图和 0 个 FATAL/ANR，
  但这些观察没有 request-scoped launch result 关联，不能升级为 PASS。该证据归入既有
  `KI-R03-058` 的 CAS 进程 owner / MuMu 低内存边界，问题仍为 `RECORDED`、阻断当前 campaign；
  不新增 Quark 专属修复结论。
- **进度**：本 lane 已保留 55 条 case（fixture 50 条通过、Quark 4 条通过 + 1 条首发失败），
  本 lane 尚有 145 条未执行；Hongguo/Fanqie 均为 0 条。已完成 DingTalk user0 50/50、R04
  failure-injection/recovery 和减半添加门禁仍保持独立证据，不能覆盖本次首发阻断。
- **证据**：结构化回执为
  `verification/catch-up/C4-R05/continuation-local-launch-user0-remaining-20260826/local-launch-failure-receipt.json`；
  完整分类与恢复设计为
  `docs/review/C4_R05_QUARK_HOST_PROCESS_LOW_MEMORY_BLOCK_DESIGN_20260826.md`；原始失败目录为
  `verification/catch-up/C4-R05/continuation-local-launch-user0-remaining-20260826/attempts/quark/user-0/cold-003/first-failure-full/`。
- **恢复约束**：恢复前不得继续剩余 lane；后续只能在 RD 进程 owner/低内存边界明确恢复后，
  使用新的 requestId、独立目录和显式手动 resume 观察，首发证据仍然权威。C4-R05 尚未完成，
  C4 阶段不得关闭。
- **实现/证据提交**：`90aceaf7`（`test(c4): [C4-R05] record local Quark low-memory block`），
  已推送；随后用独立主题 `docs(progress): record [C4-R05] receipt` 提交本回执并核验远端 HEAD。

### C4-R05：ActivityThread 生命周期与进程边界对照修复回执（2026-08-26）

- **状态**：`IN_PROGRESS`。本回执只记录一次通用生命周期修复和本机定向回归，不关闭
  C4-R05 或 C4 阶段；user1 未在本机执行。
- **修复起点与 owner**：修复基线为 `f517d025`。对照 VA 的 `HCallbackStub`/
  `AppInstrumentation`/`VirtualRuntime` 和 NBB 的 `HCallbackProxy`/
  `BaseInstrumentationDelegate`/`IActivityClientProxy` 后确认，上一轮把
  `Runtime.nativeExit` 吞掉后再用 Activity `mCalled`/`onDestroy` 特例维持 root 进程，混淆了
  ActivityThread 生命周期与 ProcessRecord/Binder death 生命周期，导致“修一层才显露下一层”。
- **实现提交**：`8ce27b8b6a041cb1664183fd309ed29821865173`（`fix(c4): align ActivityThread and process lifetime contracts`）。
  ActivityThread 侧恢复完整 delegate/base lifecycle；删除 `mCalled` 伪造、`onDestroy` 跳过和
  退出特例；translated Guest 侧仅保留 direct native `kill/_exit/abort` 的 deny-only 保护，
  `Runtime.nativeExit` 进入 libopenjdk 原始实现并产生真实进程退出，由 Binder death、slot 和
  generation recovery 收敛。专项对照见
  `docs/review/C4_R05_ACTIVITYTHREAD_LIFECYCLE_ALIGNMENT_20260826.md`。
- **验证命令**：`:app:assembleDebug :fixture-basic:assembleDebug` PASS；
  `:sandbox-native:testDebugUnitTest` PASS（无 Java unit source）；
  `python tools/static_android_compile.py` PASS；
  `python scripts/check-activity-task-virtualization.py` PASS；
  `python scripts/check-c4-r05-orchestrator.py` PASS；`git diff --check` PASS。
- **本机定向回归**：动态解析 `RD测试` 后仅执行
  `python tools/capability/run_c4_r03_rd.py --instance-name 'RD测试' --loops 1 --users 0
  --targets dingtalk --output verification/catch-up/C4-R05/continuation-local-launch-user0-runtime-exit-forwarded-probe-20260826`；
  `c4-r03-summary.json` 为 `PASS`，cold/hot 2/2 均 `LAUNCH_PASS`，动态 Activity
  created/resumed、Window、Surface、FIRST_FRAME_DRAWN 和非黑截图均有效，零自动重试。
  cold 为 `generation=1/PID=16306`，hot 为 `generation=2/PID=16618`；hot logcat 记录
  `Runtime.nativeExit(0) forwarded as process boundary` 后 root `guest4` death，证明发生
  真实退出和新代际重建，而不是旧 root 保活复用。
- **Known Issue/停止规则**：本机定向回归未复现新的非 `LOW_MEMORY` 失败；历史首发失败和
  `KI-R03-061` 仍保留，不能因 2/2 定向 PASS 标记为 FIXED。按用户最新指令，纯
  `LOW_MEMORY` 作为 RD 环境信号不阻断流程，遇到时重启模拟器并从新的 request/证据目录
  继续；任何首个非 `LOW_MEMORY` 失败仍按 fail-fast 停止。该操作规则不等于清除历史
  `LOW_MEMORY`/进程 owner 证据。
- **推送核验**：实现与定向证据已推送到
  `origin/feature/t57-r03-va-pro-capability-campaign`，远端 HEAD 已核验为
  `8ce27b8b6a041cb1664183fd309ed29821865173`。工作区仍保留未纳入本提交的历史回执/探针
  目录及既有证据变更，未删除或覆盖。
- **下一步**：继续本机 user0 的剩余 C4-R05 矩阵和回归；不启动 user1。只有用户另行授权并
  汇总另一台机器 user1 的独立证据后，才可重新评估 C4-R05/C4 关门。

### C4-R05：本机双用户无分片续跑策略回执（2026-08-26）

- **状态**：`IN_PROGRESS`。根据用户最新指令，当前执行不再按机器分片；同一台动态解析的
  `RD测试` 必须依次执行 user0 和 user1，不能再把 user1 排除在本机矩阵之外。
- **续跑规则**：正式 R05 启动矩阵保持每个 target/user 25 个 cold + 25 个 hot，
  共 500 条 launch observation；仍为一次清洁安装回合、retry budget=0。普通首个失败仍
  fail-fast，保留首失败证据并停止。
- **LOW_MEMORY 例外**：仅当失败快照的 `adb shell dumpsys activity exit-info
  com.warden.controlledsandbox.debug` 明确给出宿主 `LOW_MEMORY` 时，记录为非阻断环境事件；
  通过动态解析的 MuMu root/index 执行 emulator restart，等待新 `boot_id`，再用新的
  requestId、独立证据目录和显式手动续接从失败 target/user/iteration/mode 继续。首失败仍
  权威保留，不把续接观察写成自动重试，也不把其他应用或历史 `lowmemorykiller` 行误判为该例外。
- **实现变更**：R03 失败快照新增 `application-exit-info.txt`；新增
  `tools/capability/run_c4_r03_low_memory_continuation.py`，R05 启动矩阵改由该包装器调用
  原始 `run_c4_r03_rd.py`，并汇总首失败与续接观察。静态合同、Activity/Task 检查和 Python
  编译检查均已通过；正式运行前仍需完成提交、推送和 continuation preflight。
- **当前边界**：本回执只恢复 R05 进行中状态，不关闭 C4；只有 user0/user1 全矩阵、添加
  门禁、C1/C2/C4/SX 回归及双用户压力全部通过，且没有未处理的非 `LOW_MEMORY` P0/P1，
  才能形成正式关门回执。

### C4-R05：正式双轮验收首轮 Quark 冷启动阻断回执（2026-08-28）

- **任务 ID / 名称**：`C4-R05` / MuMu RD 正式重验与关门。
- **最终状态**：`BLOCKED`。本次首轮 `clean-install-cold` 在第一个非环境中断之后的首个
  商业样本 Quark 冷启动失败，按 fail-closed 规则停止；未进入第二轮、双用户短测或后续
  回归，未将 R05 或 C4 标记为 DONE。
- **开始/结束时间（Asia/Shanghai）**：2026-08-28 15:02:01 / 16:18:37。
- **开始基线**：分支 `feature/t57-r03-va-pro-capability-campaign`，commit
  `0cf92b03139cda347abaa29bb44bed1e51caaa76`，开始时工作区干净；上一任务 C4-R04 已于
  2026-08-25 20:18 以 `1d9b83d54c13d2a758752281dbc492859d8bd05d` 完成并推送。
- **执行环境与 RD 测试快照**：MuMu 实例名 `RD测试` 由实例发现解析，当前失败快照为
  `127.0.0.1:16416`、API 32、ABI `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`、型号
  `22041211A`、Android ID `398eea33120cd887`、boot ID
  `818b4e5f-bbe0-4f2f-9503-971c16cc53ee`。没有使用固定 serial、端口或设备型号；
  设备重启后的 serial/boot 变化按用户指令作为环境边界记录，未覆盖历史首失败。
- **前置门禁**：运行 `python scripts/verify-catch-up-continuation.py` 时识别下一任务为
  `C4-R05` 且状态为 PASS；构建/安装、C4-R04 failure-injection/recovery、C4-R02 减半
  add gate 均保留为 PASS。R02 减半门禁为 137 个操作、首失败 0；R04 注入场景的
  windows-empty、draw-timeout、首次 bind failure、重复添加、staging 残留均按预期 FAIL
  且首证据保留，独立 recovery 为 PASS。
- **已批准的环境中断**：先前 MuMu 自发重启和一次宿主 `LOW_MEMORY` 退出分别记录了新的
  boot/session、独立 attempt 和续接边界；它们不是自动重试，也没有把旧失败改写为 PASS。
  本回执之后没有再重启、重试或扩大 deadline。
- **首次失败证据**：
  - 目录：
    `verification/catch-up/C4-R05/formal-two-round-20260828-rerun2/round-1-clean-install-cold/launch-matrix/attempt-005/attempts/quark/user-0/cold-001/`；
    `case.json`、`first-failure-full/`、logcat、Activity/进程、Window、Surface、截图、
    ApplicationExitInfo、package/catalog、transaction/staging 快照均保留，并在正式运行
    `artifact-index.json` 中索引。
  - request/operation：`d261bd887c1d4970bd1a6debeb79adae` /
    `d261bd887c1d4970bd1a6debeb79adae-launch`；runner operation
    `c4-r03-quark-u0-cold-1-a5-d261bd887c`；attempt 5，`retryBudget=0`，
    `automaticRetryPerformed=false`，`retryable=false`。
  - 命令在 16:18:25.465 返回 `LAUNCH_FAILED / LAUNCH_GATE_FAILED`：
    `guest Activity create/resume/window not confirmed`；case 总耗时 80436 ms。
    这是 request-scoped 的真实结果，不是静态 marker、Guest 进程存在或截图替代。
  - Host 结构化诊断显示 PREPARE_RETURN 12985 ms、LEDGER_LAUNCH_RETURN 13038 ms、
    HOST_START_BEGIN 13041 ms、HOST_START_RETURN 13055 ms；Host gate 在 HOST_START_RETURN
    后 30000 ms 到期。
  - Guest 结构化诊断显示 root `com.ucpro.MainActivity` 使用 session
    `80460375-5156-46f0-a65b-7e5513b4bfbf`/task 155，随后同步交接到 child
    `com.ucpro.BrowserActivity`，child request 为
    `b0b26b83-94b5-4671-b270-9934e21426ae`。child CREATED 16:18:12.525、RESUMED
    16:18:12.786、FIRST_FRAME_DRAWN 16:18:28.830（`windowAttached=true`、
    `windowRegistered=true`），比 Host gate deadline 晚约 3.4 秒。
  - 失败快照最终可见 Window、非空 Surface、1080x1920 非黑非均匀截图；
    `FATAL/ANR/LOW_MEMORY` 均无证据。之后出现的
    `CONTENT_PROVIDER_AUTHORITY_NOT_VIRTUALIZED:media` 是延迟的应用侧异常，保留为次级
    风险，不能倒推为本次 gate 的根因。
- **根因及分类**：CAS 的 fail-closed gate 正确拒绝了未在 30 秒观察窗内完成首帧的启动；
  nested token/session/task/request 关联已由结构化事件证明存在。当前责任边界仍需在
  “CAS 通用 readiness 延迟”与“Quark/app-SDK 冷启动延迟”之间做有界分类，不能猜测为
  Quark 专属缺陷，也不能用晚到的首帧覆盖首失败；本项登记为 `KI-R03-062`
  (`NEEDS_REPRODUCTION_AND_CLASSIFICATION`、`RECORDED`、阻断当前 campaign)。
- **VA/NBB 对照**：复核了 VA `HCallbackStub`/`AppInstrumentation`/`VirtualRuntime` 与
  NBB `HCallbackProxy`/`BaseInstrumentationDelegate`/`IActivityClientProxy` 的生命周期、
  Binder/token/task/window identity 边界；当前 CAS 对应实现为
  `RuntimeActivityLaunchCoordinator` 的 framework-owned child `linkActivity`、
  `GuestLaunchObservation` 的 correlation map 和 `GuestLaunchGate.evaluate` 的真实
  FIRST_FRAME_DRAWN 门禁。对照结论支持保留现有 gate，不采纳固定 sleep、隐藏重试、延长
  SLO 或 package 特判；对应回归必须覆盖 Quark root→child handoff 与 deadline 边界。
- **实现摘要 / 修改文件**：本次 R05 是正式验收任务，未在证据不足时修改生产代码；保留
  运行产物和 `case.json`，并更新本账本、`docs/review/KNOWN_ISSUES.yaml` 及最新续接预检
  快照。首失败证据已由 `5ee894f3e32339b951ba7c81f19010f1d13bf392` 推送。
- **验收命令与结果**：R05 首轮 launch matrix 命令返回码 1、`c4-r03-summary.json`
  为 `FAIL`，`blockedAt={target:quark,user:0,mode:cold,iteration:1,
  classification:LAUNCH_RESULT_NOT_PASS}`；R05 汇总为 `FAIL/BLOCKED`，首轮未完成，故
  后续验收均为未执行而非通过。
- **商业样本矩阵**：package-neutral fixture 双用户 cold/hot 各 25/25 已通过；DingTalk
  双用户 cold/hot 各 25/25 在批准的环境续接后通过，但续接观察不能覆盖旧环境边界；Quark
  user0 cold-001 首次非环境失败，剩余 Quark、红果、番茄小说均未继续，不能以夸克或
  fixture 代替红果/番茄验收。
- **APK / commit / device hash**：运行基线构建 commit
  `0cf92b03139cda347abaa29bb44bed1e51caaa76`；host APK SHA-256
  `598067e83b1bfe87e21b6f0fead546d8bef56d3c61b3004953df12f480b1aae6`，fixture APK
  SHA-256 `8d8f4d776b287ea947358690882a33763c3967578952cc88e57d5188ca8275a5`；设备
  boot ID 见上，失败截图 SHA-256
  `bb3137ce0d402fbb19813660d8822dbe779d04292ef962e15286a1b9e544c3ac`。
- **Known Issues 变化**：新增 `KI-R03-062`；`KI-R03-053/054/057/058/059/061` 继续
  `RECORDED` 且阻断，`KI-R03-060` 继续为已接受但开放的强制回归项；没有把任何历史
  问题错误标记为 FIXED。
- **重试记录**：Quark 首失败没有重试（retry budget 0、retryable false、retry decision
  `NO_RETRY`）；没有捕获异常后循环、固定 sleep、延长总 timeout 或隐藏失败。前序两次
  环境续接均有独立 request/operation/boot/attempt 证据，且不是本次 Quark 的重试。
- **偏离任务书说明**：任务书要求的第二轮、完整商业矩阵、C1/C2/C4/SX 回归和 30 分钟
  双用户短测因首个真实非环境失败按第 6 节停止，属于强制 fail-fast 偏离；没有降低门槛，
  没有把 R05 或 C4 关闭。
- **遗留风险与恢复条件**：先完成 `KI-R03-062` 的 CAS readiness 与 Quark/app-SDK
  延迟责任分类；若需生产改动，先形成有 VA/NBB 对照和回归用例的独立设计，再用新的
  clean commit、request/operation 和独立证据目录重跑 Quark cold 边界。只有完整两轮、
  全商业样本 FIRST_FRAME_DRAWN、回归和 30 分钟双用户短测均通过，且 P0/P1 关闭，才可
  重新评估 C4 关门。
- **实现提交 SHA**：`5ee894f3e32339b951ba7c81f19010f1d13bf392`（首失败证据提交）。
- **回执提交**：本段由独立提交主题
  `docs(progress): record [C4-R05] block receipt` 定位；完成推送后以 Git SHA 核验。
- **推送与远端验证**：账本/Known Issues/预检变更完成独立提交后推送到
  `origin/feature/t57-r03-va-pro-capability-campaign`，核验本地与远端 HEAD 一致，工作区
  干净。
- **下一任务**：仍为 `C4-R05`（BLOCKED，待满足恢复条件）；不得进入 `C6-T01`，C4 阶段
  不得标记 DONE。

### C4-TEMP-01：CAS 导入/克隆/添加/启动耗时根因与修复（2026-08-28 起）

- **状态**：`BLOCKED`（2026-08-29 22:46，Asia/Shanghai）。按用户明确要求插入 C4-R05 之前，
  作为临时通用性能根因与修复前置；C4-R05 原有 BLOCKED 状态、首帧门禁和历史证据均保留，
  不因本任务开始或本轮性能改动而自动恢复或关闭。
- **开始时间 / 开始基线**：2026-08-28 18:11（Asia/Shanghai）；分支
  `feature/t57-r03-va-pro-capability-campaign`；开始前工作区干净；任务书变更前置设计提交
  `d6175da2`；本次拉取后实际执行基线为 `54d48c5cfa85602299531b5215cfa0c9c352fdc3`，
  证据/验证实现提交为 `cdd69a2d7b31e15cc205c4328ddccd4b42dac103`。
- **拉取后续接开始**：2026-08-29 22:37（Asia/Shanghai）；按用户指令先执行
  `git pull --ff-only origin feature/t57-r03-va-pro-capability-campaign`，从
  `6c7c41aa` fast-forward 到 `54d48c5cfa85602299531b5215cfa0c9c352fdc3`；本地 HEAD、
  远端 HEAD 和任务分支一致，未覆盖已有 `C0-T01` 预检 JSON 或本任务历史证据。续接预检
  通过并继续识别 `C4-TEMP-01`；提交身份为 `OpenAI <openai@users.noreply.github.com>`。
- **拉取后 RD 快照**：动态解析 MuMu `RD测试`，serial `127.0.0.1:16416`、API 32、ABI
  `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`、型号 `22041211A`、boot ID
  `bd6fc459-0d52-4689-868a-420364ea407c`、Android ID `398eea33120cd887`；serial 仅写入
  运行时环境快照，未进入脚本常量或验收分支。
- **拉取后本地门禁**：`py_compile`、C4-TEMP-01 latency contract、C4-R05 orchestrator
  contract、修订绑定 gate（更新为扫描拆分后的 validator/lifecycle owner）和静态 Android
  编译/self-test 通过；首次旧门禁失败原文保留于
  `verification/catch-up/C4-TEMP-01/static-gates/20260829T1430_check-apk-revision-binding-first-failure.txt`，
  归类为检查器与最新职责拆分的断言漂移，未进行运行时重试。
- **上一任务回执**：C4-R04 已于 2026-08-25 20:18 以
  `1d9b83d54c13d2a758752281dbc492859d8bd05d` 完成并推送；C4-R05 于 2026-08-28 因 Quark
  cold 首帧 gate 超时以 `5ee894f3e32339b951ba7c81f19010f1d13bf392` 形成首失败阻断回执，账本/KI
  更新为 `4001c755a51af9db428f2a592cbc2f73a07c8ef6`。
- **预检与环境**：开始前已执行 `python scripts/verify-catch-up-continuation.py`；原预检按
  R05 BLOCKED 正确 fail-closed。插入临时任务后必须重新执行并识别 `C4-TEMP-01`；MuMu
  `RD测试` serial/API/ABI/model/boot ID/Android ID 在动态 benchmark 开始前补写，禁止在代码或
  命令中硬编码。
- **事实源与专项设计**：本任务开始前完整读取任务书、进度账本、
  `C4_RD_RETEST_ROOT_CAUSE_AND_ACCEPTANCE_PLAN_20260824.md`、`KNOWN_ISSUES.yaml`、
  `CAPABILITY_CAMPAIGN_WORKFLOW.md`、`COMMIT_IDENTITY_POLICY.md`、C4-R01/R02/R03/R04/R05
  设计与 VA/NBB 参考实现；根因/影响/迁移规则先提交于
  `docs/review/C4_TEMP_01_CAS_IMPORT_LAUNCH_LATENCY_ROOT_CAUSE_AND_FIX_DESIGN_20260828.md`。
- **当前工作边界**：先完成 CAS validator/Guest prepare/package-universe 的重复 hash/parse
  证据与 VA/NBB mapping，再实施 broker-issued verification flag、权威 package state projection
  和 peer-universe archive parse 移除；isolated 校验、身份/路径/事务安全边界不放宽。
- **验收门槛**：同一 clean commit 动态解析 `RD测试`，夸克直启与 CAS 沙箱冷启动各至少 3 次，
  真实首帧/可见为结束点，`sandbox/direct <= 10x` 硬门槛、`<= 3x` 目标；首次失败即保存完整
  request/operation、stage timing、日志、dumpsys、Window/Surface、截图/帧和 hash，不自动重试。
- **Known Issues**：本任务开始时不关闭 `KI-R03-053/054/057/058/059/061/062` 或开放的
  `KI-R03-060`；只有证据闭环后才追加变化。
- **下一任务**：临时任务完成且恢复条件满足后为 `C4-R05`；本轮性能硬门禁真实阻断，已按任务书
  标记本任务 `BLOCKED`，停止后续任务并提交以下阻断回执。

### C4-TEMP-01：阻断回执（2026-08-29）

- **任务 ID / 最终状态**：`C4-TEMP-01 / BLOCKED`。本轮没有标记 DONE，也没有进入 C4-R05。
- **开始/结束时间与基线**：续接开始 2026-08-29 22:37（Asia/Shanghai），首个动态 benchmark
  运行目录 `20260829T223756`；修正观测边界后独立验证目录 `20260829T224349`，于约
  22:46 因沙箱首帧硬门禁失败结束。代码基线为拉取后的
  `54d48c5cfa85602299531b5215cfa0c9c352fdc3`，实现/证据提交为
  `cdd69a2d7b31e15cc205c4328ddccd4b42dac103`
  （`fix(c4): [C4-TEMP-01] preserve latency block evidence`）。
- **执行环境 / RD 快照**：动态解析 MuMu `RD测试`，API 32，ABI
  `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`，型号 `22041211A`，boot ID
  `bd6fc459-0d52-4689-868a-420364ea407c`，Android ID `398eea33120cd887`；完整环境和
  动态 discovery 见 `verification/catch-up/C4-TEMP-01/20260829T224349-artifact-index.md`
  及同目录 raw `environment.json`/`quark-discovery.json`。运行时 serial 未进入脚本常量或分支。
- **首次失败证据**：第二条独立链的第一失败为 Quark 沙箱 sample-01，request
  `bdb03fe6b78d4323ab3b1adcf170be36`，operation
  `bdb03fe6b78d4323ab3b1adcf170be36-launch`，session
  `eb8a4856-f830-4b3a-ad14-79a82f0496ef`，attempt 1，retry budget 0，automatic retry
  `false`；`targetStopBeforeSandbox` 返回码 0。import-only PASS，launch 返回码 1，耗时
  `88953ms`，结构化错误为 `LAUNCH_FAILED / LAUNCH_GATE_FAILED`，
  `guest Activity create/resume/window not confirmed`，没有完整 launch timeline，
  `requiredStagesPresent=false`。原始 logcat 关键行是 22:45:46.486 的
  `GUEST_LAUNCH_READINESS status=LAUNCH_FAILED`，随后 22:45:46.608 的同一命令失败。
  完整 request/operation、logcat、Host/Guest、dumpsys、Window/Surface、截图、安装事务、
  staging/catalog/revision、进程、boot 和 APK hash 由 artifact index 索引；第一次
  `20260829T223756` 证据未覆盖。
- **真实显示证据**：失败边界快照中 Host `StubActivity60W1` 为 top-resumed，但 Guest
  `resumed_guest_stub_count=1`、`reported_drawn=false`、`has_visible=false`、`drawn=false`；
  目标 Host-owned Window 的 `mHasSurface=true` 但 `Surface: shown=false`、
  `mDrawState=DRAW_PENDING`，`readyForDisplay=false`、`visible=false`；截图为
  `1080x1920` 全黑均匀，SHA-256
  `4afb293f262964138b1d2e2a08733ad4d4216150e508b9f62e4610e47e0cb930`。因此没有用 Guest
  进程存在、静态 marker、系统 Surface 数量或后续窗口残留代替 FIRST_FRAME_DRAWN。
- **对照矩阵**：夸克直接冷启动 3/3 通过动态 Activity/Window/Surface/非黑首帧，耗时
  `6937/6147/7582ms`；沙箱在 sample-01 的第一项即失败，故不计算 ratio 以绕过硬门禁，
  也没有继续发起后续样本。商业样本、C1/C2/C4/SX 和 30 分钟双用户短测均未执行，不能
  以夸克直启或历史 fixture/DingTalk 结果替代。
- **根因及分类**：当前可证实的分类为
  `CAS_READINESS_GATE_FAILURE_WITH_NESTED_QUARK_HANDOFF_UNRESOLVED`。CAS fail-closed
  gate 在该 request 的 deadline 内没有得到 Guest `FIRST_FRAME_DRAWN` 合同，这一失败是真实
  且可重复的；但现有日志同时包含 Quark/app-SDK native profile/SecurityGuard 相关现象，
  没有足够因果证据将责任定为 CAS 通用层或 Quark 专属层。历史其他运行的 FATAL 不与本
  request 关联，未被提升为本次根因。结论保持“待验证”，不授权延长 deadline、固定 sleep、
  吞异常循环重试或 package-specific production fix。
- **VA/NBB 对照**：NBB 安装解析/设置缓存后复用 `ProcessRecord`；VA 在安装阶段完成
  parse/copy/cache，启动复用已建立的身份与进程边界。CAS import-only 已通过并记录
  `HASH/MANIFEST_PARSE/PACKAGE_INFO/REVISION_VERIFY/CATALOG` 分段；失败位于
  `RuntimeActivityLaunchCoordinator`、`GuestLaunchObservation`、`GuestLaunchGate` 对
  Guest create/resume/window identity 的 readiness 合同。采纳真实 request-scoped gate 和
  fail-closed 语义，不采纳固定 sleep、隐藏 retry、延长 SLO 或包名特判；后续回归为
  KI-R03-062 root→child handoff 与 C4-R05 两轮矩阵。
- **本轮修改文件**：`tools/capability/run_c4_temp_01_quark_latency.py`（动态目标清理、
  Host/Guest Window/Surface/Activity 归属、真实首帧 fail-fast、request/operation/retry
  记录）；`scripts/check-apk-revision-binding.py`（适配 validator/lifecycle 职责拆分）；
  `.gitignore`（raw device output 保留本地）；`verification/catch-up/C4-TEMP-01/` 下
  首失败原文、分类和 artifact index；以及最新续接预检快照。生产 CAS 未因证据不足作 R03
  范围外改动。
- **验收命令与结果**：`python scripts/verify-catch-up-continuation.py` PASS，识别
  `C4-TEMP-01`；`python -m py_compile ...` PASS；`python scripts/check-c4-temp-01-latency.py`
  PASS；`python scripts/check-c4-r05-orchestrator.py` PASS；`python scripts/check-apk-revision-binding.py`
  PASS；`python tools/static_android_compile.py` PASS（self-tests PASS）；Gradle
  `:app:assembleDebug :fixture-basic:assembleDebug :sandbox-companion32:assembleDebug
  :fixture-compat32:assembleDebug --no-daemon` PASS。动态 TEMP-01 硬门禁 FAIL，故任务总体
  BLOCKED。
- **Known Issues 变化**：`KI-R03-062` 保持 `RECORDED / NEEDS_REPRODUCTION_AND_CLASSIFICATION /
  blocks_current_campaign: true / acceptance: NOT_FIXED`，追加本轮 request、快照和索引；
  `KI-R03-053/054/057/058/059/061` 仍未关闭，`KI-R03-060` 仍为已接受但开放的强制回归项。
- **重试记录**：生产请求没有重试，`attempt=1`、`retryBudget=0`、`automaticRetryPerformed=false`；
  第一条链的失败证据保存后只修正了 benchmark 的错误观测边界，再以独立链验证，未重发同一
  request、未捕获异常循环、未固定 sleep、未延长总 timeout。旧静态 gate 的第一次失败是
  checker contract drift，原文保留并经职责拆分修正，不是运行时 retry。
- **偏离任务书 / 遗留风险 / 恢复条件**：因首个真实非环境失败，按 fail-fast 停止，未执行
  3×沙箱余项、商业样本、R05 两轮、C1/C2/C4/SX 回归和 30 分钟双用户短测；这是任务书
  第 6 节要求的阻断路径，不是降低门槛。恢复前须先完成 CAS readiness 与 Quark/app-SDK
  延迟的有界分类；若需生产修改，先提交独立 VA/NBB 设计和回归用例，再在新的 clean commit
  上重跑 TEMP-01 和完整 C4-R05。
- **回执提交 / 推送 / 下一任务**：本段由独立提交主题
  `docs(progress): record [C4-TEMP-01] blocked receipt` 提交；完成推送后以 Git SHA 核验。
  本地与远端 HEAD 必须一致、工作区保持干净；下一任务仍记录为
  `C4-TEMP-01 (BLOCKED)`，不得进入 `C4-R05` 或 `C6-T01`。

### C4-TEMP-01：8 小时窗口续作（2026-08-30）

- **任务 ID / 当前状态**：`C4-TEMP-01 / IN_PROGRESS`。本段是对 2026-08-29 首失败阻断的
  明确人工续作，不覆盖历史 `BLOCKED` 回执，也不把首失败改写为 PASS。当前仍只有本任务
  一个 `IN_PROGRESS`，在本任务关闭前不得进入 `C4-R05`。
- **窗口与开始记录**：开始时间 `2026-08-30 02:30:22 +08:00`；窗口截止
  `2026-08-30 10:30:22 +08:00`；开始 commit、分支与远端 HEAD 均为
  `691ebac73bc1a69eca3b4e8733bc5dc4160b59d6` /
  `feature/t57-r03-va-pro-capability-campaign` /
  `origin/feature/t57-r03-va-pro-capability-campaign`。上一任务回执为 C4-R04
  `1d9b83d54c13d2a758752281dbc492859d8bd05d`；上一轮 C4-TEMP-01 阻断回执为
  `691ebac73bc1a69eca3b4e8733bc5dc4160b59d6`。
- **续接预检**：状态恢复前运行 `python scripts/verify-catch-up-continuation.py`，按
  历史 BLOCKED 状态 fail-closed，原始输出为 `ledger next task C4-TEMP-01 is not first
  dependency-ready PENDING task`；这是预期阻断，不是任务跳过。写入本段后必须重新运行并
  识别 `C4-TEMP-01` 为当前唯一活动任务。
- **RD 测试设备快照**：通过 `python scripts/mumu_instance.py --instance-name 'RD测试'`
  动态解析；MuMu `RD测试`（实例索引 1，`MuMuPlayer-12.0-1`），API 32，ABI
  `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`，型号 `22041211A`，Android ID
  `398eea33120cd887`，boot ID `bd6fc459-0d52-4689-868a-420364ea407c`；resolved serial
  仅存在于本次运行快照，未进入代码常量、任务分支或验收逻辑。
- **当前 Known Issues**：`KI-R03-053`、`KI-R03-054`、`KI-R03-057`、`KI-R03-058`、
  `KI-R03-059`、`KI-R03-061`、`KI-R03-062` 仍为当前 C4 阻断；`KI-R03-060` 为已接受但
  必须回归的开放项。本次恢复开始时不关闭任何 Issue。
- **恢复边界**：先完成首次 Quark 沙箱失败的 CAS readiness 与 Quark/app-SDK 延迟的
  有界分类；只有存在可归因、最小且有 VA/NBB 对照的 CAS 修复并通过静态/构建/动态门禁，
  才能将本任务改为 DONE 并续接 C4-R05。若设备、样本、权限或真实首帧仍不可用，保留
  首次失败证据，按任务书重新形成 BLOCKED 回执并停止后续任务。

### C4-TEMP-01：8 小时窗口最终阻断回执（2026-08-30）

- **任务 ID / 最终状态**：`C4-TEMP-01 / BLOCKED`。本窗口未达到任务书的动态沙箱首帧
  门禁，未标记 DONE，未启动 C4-R05；当前任务保持 BLOCKED，不能继续后续 C4 任务或
  关闭 C4 阶段。
- **开始/结束时间与基线**：本次 8 小时续作从 `2026-08-30 02:30:22 +08:00` 开始，
  截止时间为 `2026-08-30 10:30:22 +08:00`，但因真实阻断于
  `2026-08-30 03:58:10 +08:00` 停止。开始 commit 为
  `691ebac73bc1a69eca3b4e8733bc5dc4160b59d6`；分支为
  `feature/t57-r03-va-pro-capability-campaign`；远端为
  `origin/feature/t57-r03-va-pro-capability-campaign`。上一任务 C4-R04 回执为
  `1d9b83d54c13d2a758752281dbc492859d8bd05d`；上一轮 C4-TEMP-01 阻断回执为
  `691ebac73bc1a69eca3b4e8733bc5dc4160b59d6`。窗口开始前已执行
  `git fetch origin --prune`、`git pull --ff-only`，本地与远端均为上述基线。
- **事实源与续接预检**：任务开始前重新完整读取任务书、进度账本、C4 RD 重测根因与验收
  计划、`KNOWN_ISSUES.yaml`、能力活动工作流、提交身份规范，以及 C4-TEMP-01 专项设计
  和 VA/NBB 参考实现映射。恢复状态写入后，`python scripts/verify-catch-up-continuation.py`
  通过并识别 `C4-TEMP-01`；最终将任务置为 BLOCKED 后再次运行，原始结果为
  `FAIL C0-T01 continuation preflight: ledger next task C4-TEMP-01 is not first
  dependency-ready PENDING task None`，进程退出码 `1`。原始输出另存于
  `verification/catch-up/C4-TEMP-01/20260830T0357-final-continuation-preflight-failure.md`；
  该 fail-closed 结果确认未误入 C4-R05。
- **执行环境 / RD 测试快照**：动态解析 MuMu `RD测试`（实例索引 1，
  `MuMuPlayer-12.0-1`），API 32，型号 `22041211A`，ABI
  `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`，Android ID
  `398eea33120cd887`，boot ID `bd6fc459-0d52-4689-868a-420364ea407c`。运行时 serial
  只存在于环境快照，未进入代码常量、验收分支或固定设备逻辑。动态发现的商业样本为
  夸克 `com.quark.browser`，组件 `com.ucpro.MainActivity`，版本
  `10.10.5.1080/code1080`，base 1、split 0，primary ABI `arm64-v8a`。
- **第一次失败原始证据（lane A）**：目录
  `verification/catch-up/C4-TEMP-01/quark-latency/20260830T034123/`，完整日志为
  `live-logcat-20260830T034123-native-dir-fix.txt`。import-only 通过，导入 revision
  为 `v1080:sha256:2cb38172da5da4aee03826da0feccb77ff0391ee7356f1562052ddc1fae9ecb3`。
  夸克直启 3/3 通过，耗时 `6430/6603/6044 ms`。首个沙箱失败 request 为
  `f54f6ec3009d489ca33769e14df29ec4`，operation 为
  `f54f6ec3009d489ca33769e14df29ec4-launch`，session
  `ef529806-f368-482a-bc8f-9fe48658345c`，root task `167`，child token
  `c5efc5bd-4c37-4a17-9cc2-07f8783906fc`；attempt `1`、retry budget `0`、
  `automaticRetryPerformed=false`，没有自动重试。gate 在 `03:43:17.955` 以
  `LAUNCH_FAILED/LAUNCH_GATE_FAILED` 失败；child 随后于 `03:43:21.608` 才达到真实
  `FIRST_FRAME_DRAWN`，晚到首帧不能覆盖首失败。首次失败快照位于
  `sandbox/sample-01/first-failure-full/`，截图为 12504 bytes，SHA-256
  `4afb293f262964138b1d2e2a08733ad4d4216150e508b9f62e4610e47e0cb930`，快照时
  `surfaceNonEmpty=false`，Activity/Window/Surface 探针为空；request、operation、
  Host/Guest 日志、dumpsys、安装事务、staging、revision、catalog、进程和设备信息均保留。
- **独立复测与第一次失败原始证据（lane B）**：目录
  `verification/catch-up/C4-TEMP-01/quark-latency/20260830T035048/`，完整日志为
  `live-logcat-20260830T035047-callback-timing.txt`。import-only 通过，package
  operation `11c4c43a-09a8-40bc-bb5c-fc3282dea1c4` 为 `SUCCEEDED/DONE`，耗时
  `18716 ms`；夸克直启 3/3 通过，耗时 `8737/8405/7803 ms`。首个沙箱失败 request 为
  `7bc5f68e932b413ea03c3f300df92526`，operation 为
  `7bc5f68e932b413ea03c3f300df92526-launch`，session
  `3f0dd041-3ac6-4494-b711-28d72e8fc715`，root task `168`，child token
  `ee220fd9-e528-4d02-a48f-51a324405f63`；attempt `1`、retry budget `0`、
  `automaticRetryPerformed=false`。gate 在 `03:52:58.800` 失败，失败快照于
  `03:53:33.317617+08:00` 保存，截图仍为 12504 bytes、同一黑屏 SHA-256
  `4afb293f262964138b1d2e2a08733ad4d4216150e508b9f62e4610e47e0cb930`，runner 在首失败
  后停止，因此没有使用未观测的后续首帧替代失败。
- **阶段时序与分类**：lane B child `GUEST_READY` 为 `03:52:30.094`，
  `CALLBACK_CREATE_BEGIN` 为 `03:52:30.099`，delegate begin 为 `03:52:30.108`，
  delegate return 为 `03:52:46.444`，delegate elapsed `16337 ms`，随后
  `CREATED/STARTED/POST_CREATED/RESUMED` 为 `03:52:46.445/03:52:46.528/
  03:52:46.533/03:52:46.620`，且 `windowAttached=false`。Host gate 在
  `03:52:58.800` 截止；因此已把残余延迟收敛到目标 Activity delegate/Guest 环境边界的
  可观测阶段，但现有证据不足以把全部延迟归因于 CAS 通用层、Quark 应用或 SDK。最终
  分类为 `CAS_READINESS_GATE_FAILURE_WITH_NESTED_QUARK_HANDOFF_UNRESOLVED`，责任边界
  保持“待验证”，不是猜测出的根因。未发现本次 request 关联的 FATAL、未界定 ANR、设备
  丢失或 LOW_MEMORY 退出。
- **VA/NBB 对照与采纳边界**：复核 VA `HCallbackStub`、`AppInstrumentation`、
  `VirtualRuntime` 与 NBB `HCallbackProxy`、`BaseInstrumentationDelegate`、
  `IActivityClientProxy` 的生命周期、Binder、token、task、window identity 以及安装/进程
  复用边界。采纳 broker-issued revision proof、权威 package-state projection、peer
  universe 状态复用和真实 request-scoped FIRST_FRAME_DRAWN gate；不采纳固定 sleep、
  扩大 deadline、post-resume 多次 addView、吞异常循环重试或 package-specific 特判。对应
  回归覆盖 import revision、nested root→child handoff、Window/Surface/首帧及首失败保留。
- **实现摘要 / 修改文件**：保留并验证本轮最小 CAS 边界改动：
  `GuestApplicationInfoFactory` 将 `ApplicationInfo.nativeLibraryDir` 与 U4 runtime
  native 目录分离，避免把打包应用的 native identity 指向错误运行时根；
  `GuestNativeRuntimeProjection`、`GuestRuntimeEnvironment`、`BaseGuestProcessService`、
  `GuestContextComponentRouter`、`GuestActivityController`、
  `RuntimeActivityLaunchCoordinator` 和 `GuestActivityThreadInstrumentation` 补充
  native/生命周期/回调阶段诊断。该候选修复后两条运行链均未再出现
  `libsgmainso-6.6.230703.so not found`；ART profile-directory 信息被确认是非致命现象，
  但 30 秒真实首帧门禁仍失败，故不扩大修改范围、不改变门槛。
- **验收命令与结果**：续接预检在恢复后 `PASS`；最终 fail-closed 预检如上为预期
  `FAIL/exit 1`。`python scripts/check-c4-temp-01-latency.py`、
  `python scripts/check-c4-r05-orchestrator.py`、`python scripts/check-apk-revision-binding.py`
  和 `python -m py_compile ...` 均通过；Gradle
  `:sandbox-runtime:compileDebugJavaWithJavac --no-daemon` 及
  `:app:assembleDebug :fixture-basic:assembleDebug :sandbox-companion32:assembleDebug
  :fixture-compat32:assembleDebug --no-daemon` 均通过。动态命令
  `python tools/capability/run_c4_temp_01_quark_latency.py --samples 3` 在 lane A/B
  均于沙箱 sample-01 首失败返回 `exit 1`；没有为追求偶然通过而重试或扩大门禁。
- **商业样本矩阵**：夸克直启两条独立 lane 各 3/3 PASS；每条 lane 的 CAS 沙箱 sample-01
  均 FAIL，故没有伪造 ratio、继续余下沙箱样本或宣称商业矩阵通过。DingTalk、红果、番茄
  小说以及 C4-R05 两轮正式矩阵、C1/C2/C4/SX 回归、30 分钟双用户短测均未执行，按首个
  真实非环境失败 fail-fast 停止；夸克直启和历史 fixture/DingTalk 结果不能替代它们。
- **APK / revision / device hash**：本轮构建 APK SHA-256 为 host
  `B176F18AEA3997CBED829580D21DB255D561421A62870B5C5E98E04DB79C5D07`，fixture-basic
  `8D8F4D776B287EA947358690882A33763C3967578952CC88E57D5188CA8275A5`，companion32
  `AF576D1D53C98E9F408B1691B3AFC8042AF9BC20628F024132DCFC6039528BB3`，fixture-compat32
  `9193694EE36848992E98D7E1FF7197A833182807784EB5A375F0D32BFF5C96E1`；revision、设备
  boot ID 与截图 hash 已在两条动态证据的环境/summary/case/snapshot 文件中保存。
- **Known Issues 变化**：`KI-R03-062` 保持
  `RECORDED / NEEDS_REPRODUCTION_AND_CLASSIFICATION / acceptance: NOT_FIXED /
  blocks_current_campaign: true`，追加两条 2026-08-30 动态 lane、首次失败 request、
  callback timing 和候选 native 边界结果；`KI-R03-053/054/057/058/059/061` 保持
  `RECORDED` 阻断，`KI-R03-060` 保持已接受但开放的强制回归项，未错误关闭任何 Issue。
- **重试记录**：两条生产 launch request 都是 attempt `1`、retry budget `0`、
  `automaticRetryPerformed=false`，retry decision 为首次观察不重试；lane B 是保留 lane A
  首失败后重新建立的独立 request/operation，不能解释成同一请求重试。没有捕获所有异常后
  循环、固定 sleep、静默 launch retry 或扩大总 timeout。
- **偏离任务书 / 遗留风险 / 恢复条件**：因首个真实沙箱失败，未完成沙箱余项、全商业
  样本、C4-R05 两轮、C1/C2/C4/SX 回归和 30 分钟双用户短测，这是任务书 fail-fast 阻断路径，
  不是降低门槛。恢复前须完成 CAS readiness 与 Quark/app-SDK/Guest 环境责任的有界分类；
  若需生产改动，先提交独立 VA/NBB 设计和回归用例，再用新的 clean commit、动态 RD 快照、
  独立 request/operation 和完整首失败证据重跑 C4-TEMP-01。只有该任务达到 30 秒 cold /
  10 秒 hot 首帧门禁并完成规定回归，才可恢复 C4-R05。
- **提交、推送与下一任务**：实现/证据提交和本回执提交分离；本次实现/证据提交为
  `a9add66722cb3ab6da996059bcc4ad32c502778d`（`fix(c4): [C4-TEMP-01] classify Quark
  readiness latency`）；独立任务回执提交为
  `c5c7348b0d6f798bdf2325984b7a16bfaaba80e3`（`docs(progress): record [C4-TEMP-01]
  blocked 8h receipt`）；最终 fail-closed 原始输出补充提交为
  `b6604419d90c9b2b9b96aa06e599b74021df62a3`。上述提交均已推送到
  `origin/feature/t57-r03-va-pro-capability-campaign`，并验证本地与远端 HEAD 一致、
  工作区干净。下一任务仍为 `C4-TEMP-01 (BLOCKED)`，C4-R05 保持 `BLOCKED`；不得进入
  C6-T01，C4 阶段不得标记 DONE。

### C4-TEMP-01：5 小时窗口续作（2026-08-30）

- **任务 ID / 当前状态**：`C4-TEMP-01 / IN_PROGRESS`。本段是用户明确“重新开始 5 小时”
  后对上一轮阻断的正式续作；不覆盖历史 BLOCKED 回执，也不把旧失败改写为 PASS。当前
  只允许本任务处于 `IN_PROGRESS`，在任务关闭或再次形成真实阻断前不得进入 C4-R05。
- **窗口与开始基线**：开始时间 `2026-08-30 11:09:55 +08:00`，窗口截止
  `2026-08-30 16:09:55 +08:00`；开始 commit、分支和远端 HEAD 均为
  `b5efd69d000d40f2383cd7290e9d7482cef06522` /
  `feature/t57-r03-va-pro-capability-campaign` /
  `origin/feature/t57-r03-va-pro-capability-campaign`。上一任务 C4-R04 回执为
  `1d9b83d54c13d2a758752281dbc492859d8bd05d`；上一轮 C4-TEMP-01 最终阻断回执为
  `c5c7348b0d6f798bdf2325984b7a16bfaaba80e3`，最终证据补充为
  `b6604419d90c9b2b9b96aa06e599b74021df62a3`，账本哈希收尾提交为
  `b5efd69d000d40f2383cd7290e9d7482cef06522`。
- **拉取与预检**：窗口开始前执行 `git fetch origin --prune` 和
  `git pull --ff-only origin feature/t57-r03-va-pro-capability-campaign`，结果为
  `Already up to date`，本地与远端一致且工作区干净。重开前执行
  `python scripts/verify-catch-up-continuation.py`，按历史 BLOCKED 状态返回
  `FAIL C0-T01 continuation preflight: ledger next task C4-TEMP-01 is not first
  dependency-ready PENDING task None`，退出码 `1`；这是预期 fail-closed 边界，写入本段后
  将重新执行并确认当前唯一活动任务为 `C4-TEMP-01`。
- **事实源与专项参考**：已重新完整读取任务书、进度账本、C4 RD 重测根因与验收计划、
  `KNOWN_ISSUES.yaml`、能力活动工作流、提交身份策略、C4-TEMP-01 专项设计及 C4-R01/R02/R03/R04/R05
  设计、证据边界、交接和 VA/NBB 参考映射；本轮仍遵守 30 秒 cold/10 秒 hot 首帧门槛，
  不使用固定 sleep、静默 launch retry、扩大 timeout 或 package-specific 分支。
- **RD 测试设备快照**：通过 `python scripts/mumu_instance.py --instance-name 'RD测试'`
  动态解析到 MuMu `RD测试`（实例索引 1，`MuMuPlayer-12.0-1`），API 32，型号
  `22041211A`，ABI `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`，Android ID
  `398eea33120cd887`，boot ID `bd6fc459-0d52-4689-868a-420364ea407c`；resolved serial
  只进入本次快照，不进入生产代码、任务分支或验收逻辑。
- **当前 Known Issues 与续作边界**：`KI-R03-053/054/057/058/059/061/062` 仍为
  `RECORDED` 阻断项，`KI-R03-060` 仍为已接受但必须回归的开放项；本轮先针对
  `KI-R03-062` 的 CAS readiness 与 Quark/app-SDK/Guest 延迟边界做新的独立诊断，只有
  形成可归因、最小、具 VA/NBB 对照的修复并通过静态、构建、动态门禁，才允许关闭 TEMP-01。

### C4-TEMP-01：5 小时窗口最终阻断回执（2026-08-30）

- **任务 ID / 最终状态**：`C4-TEMP-01 / BLOCKED`。本窗口已完成有依据的诊断、最小
  观测改动和一次独立复现，仍未达到动态沙箱 `FIRST_FRAME_DRAWN` 门禁；没有标记 DONE，
  没有进入 C4-R05。C4 阶段保持 `BLOCKED/REOPENED`，C4-R05 保持 `BLOCKED`，C6-T01
  仍为 `PENDING`。下一任务仍为 `C4-TEMP-01`，待恢复条件满足后续接。
- **开始/结束时间与基线**：开始 `2026-08-30 11:09:55 +08:00`，结束
  `2026-08-30 11:51:29 +08:00`；开始 commit 为
  `b5efd69d000d40f2383cd7290e9d7482cef06522`，结束实现基线为
  `ffef74c31edcb4497a51bf18b9e6a869d6593e53`；分支为
  `feature/t57-r03-va-pro-capability-campaign`，远端为
  `origin/feature/t57-r03-va-pro-capability-campaign`。上一任务 C4-R04 回执为
  `1d9b83d54c13d2a758752281dbc492859d8bd05d`，上一轮 TEMP 阻断回执为
  `c5c7348b0d6f798bdf2325984b7a16bfaaba80e3`。
- **续接预检与 Git**：重开后 `python scripts/verify-catch-up-continuation.py` 返回
  `PASS C0-T01 continuation preflight: verification/catch-up/C4-TEMP-01`；分支、
  本地/远端 HEAD 和身份均正确，身份为 `OpenAI <openai@users.noreply.github.com>`。
  完成阻断收口后再次运行该预检，原始结果为
  `FAIL C0-T01 continuation preflight: ledger next task C4-TEMP-01 is not first
  dependency-ready PENDING task None`，退出码 `1`；该 fail-closed 证据见
  `verification/catch-up/C4-TEMP-01/20260830T1150-final-continuation-preflight-failure.md`，
  不得把它解释为可进入 C4-R05 的信号。
- **事实源、参考实现与纪律**：本窗口重新完整读取任务书、进度账本、C4 RD 重测根因与
  验收计划、`KNOWN_ISSUES.yaml`、能力活动工作流、提交身份策略、C4-TEMP-01 设计及
  C4-R01/R02/R03/R04/R05 专项设计/证据/交接/VA-NBB 参考映射。对照
  `C4_R01_EVIDENCE_REPRO_CLASSIFICATION_AND_REFERENCE_MAPPING_20260824.md`、
  `C4_R03_LAUNCH_READINESS_WINDOW_DESIGN_20260824.md`、
  `C4_R05_ACTIVITYTHREAD_LIFECYCLE_ALIGNMENT_20260826.md` 及
  `C4_R05_FINAL_TWO_ROUND_EVIDENCE_BOUNDARY_DESIGN_20260827.md`，保留正常
  ActivityThread/framework `addView` 路径和当前 readiness 合同。没有扩大 deadline、
  固定 sleep、静默 launch retry、捕获所有异常循环重试或 package-specific 分支。
- **RD 测试快照**：通过 `python scripts/mumu_instance.py --instance-name 'RD测试'`
  动态解析 MuMu `RD测试`（实例索引 1，`MuMuPlayer-12.0-1`），API 32，型号
  `22041211A`，ABI `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`，Android ID
  `398eea33120cd887`，boot ID `bd6fc459-0d52-4689-868a-420364ea407c`。resolved
  serial 只在生成的设备快照中出现，未进入源码、runner 或任务逻辑。
- **动态商业样本发现**：本窗口动态解析夸克为 package
  `com.quark.browser`、入口 `com.ucpro.MainActivity`、实际 child Activity
  `com.ucpro.BrowserActivity`、版本 `10.10.5.1080/code1080`、base 1/split 0、
  primary ABI `arm64-v8a`；broker revision 为
  `v1080:sha256:2cb38172da5da4aee03826da0feccb77ff0391ee7356f1562052ddc1fae9ecb3`。
  本 TEMP 任务只规定夸克性能/首帧分类和 package-neutral fixture 回归，DingTalk、夸克、
  红果、番茄小说的 C4-R02 商业矩阵及 C4-R05 两轮正式验收未在阻断后伪造为通过。
- **首次失败证据一（诊断前一轮）**：动态 lane
  `verification/catch-up/C4-TEMP-01/quark-latency/20260830T111850/` 的 import-only
  通过；直接冷启动 3/3 通过，耗时 `8762/6929/6106 ms`。沙箱 sample-01 首次失败
  request `cc25809c3091482c849f7fa231f26230`、operation
  `cc25809c3091482c849f7fa231f26230-launch`，`attempt=1`、`retryBudget=0`、
  无自动重试，耗时 `93515 ms`，错误为 `java.lang.IllegalStateException` /
  `LAUNCH_GATE_FAILED`。fail-fast 前保存完整 logcat、Activity/process、Window/Surface、
  transaction/catalog、截图和设备快照；截图为纯黑，SHA-256 为
  `4afb293f262964138b1d2e2a08733ad4d4216150e508b9f62e4610e47e0cb930`。
- **首次失败链路签名**：上述首失败的唯一 post-resume 观测为
  `WINDOW_POST_RESUME_STATE boundary=before_publish`，目标为
  `com.ucpro.BrowserActivity`，`mWindowAdded=true`、`mDecor=true`、
  `visibleFromClient=true`、`visibleFromServer=true`、`attached=false`、
  `registration=REGISTERED`、`parent=android.view.ViewRootImpl`、`viewRoot=null`、
  `wmgViews=1`、`windowVisibility=8`。同一快照的目标 Window 为
  `mHasSurface=true/isReadyForDisplay=true`，但 `Surface: shown=false`、
  `mDrawState=DRAW_PENDING`；Activity 为 RESUMED，但 `reportedDrawn=false`、
  `mNumDrawnWindows=0`、`allDrawn=false`。这证明存在 Window/Surface 但不等于首帧成功。
- **独立验证一（只读观测提交后）**：lane
  `verification/catch-up/C4-TEMP-01/quark-latency/20260830T113744/` 的 import-only
  通过，导入总耗时 `18288 ms`，阶段包括 `HASH=673`、`PACKAGE_INFO=1057`、
  `EXISTING_REVISION_VERIFY=2386`、`NATIVE_EXTRACT=2017`、
  `PUBLISHED_REVISION_VERIFY=3298`、`CATALOG_WRITE=3778`；日志确认
  `CS_REVISION_VERIFY: skipped broker-verified`，没有重复 Guest revision 校验。
  直接冷启动 3/3 通过，耗时 `7104/6242/5873 ms`，每次均 resumed、hasSurface、
  readyForDisplay、drawn、surfaceShown 和非黑屏均为 true。沙箱 sample-01 首次失败
  request `ab1ecc26cc1e4511919dc45482794de6`、operation
  `ab1ecc26cc1e4511919dc45482794de6-launch`，`attempt=1`、`retryBudget=0`、
  `automaticRetryPerformed=false`，耗时 `91889 ms`，状态
  `SANDBOX_FIRST_FRAME_NOT_CONFIRMED`，错误分类 `java.lang.IllegalStateException`。
  快照截图仍为纯黑且 SHA-256 同上；失败链路输出
  `GUEST_LAUNCH_READINESS ... LAUNCH_FAILED ... window_not_confirmed`。
  该 lane 是一次独立的 one-shot validation，不是同一 attempt 的重试；runner 的首次
  失败策略保持 `NO_RETRY_FIRST_OBSERVATION`。
- **通用 fixture 回归**：在同一结束基线对 package-neutral fixture 执行
  `python tools/capability/run_c4_r03_rd.py --loops 1 --users 0 --targets fixture
  --output verification/catch-up/C4-TEMP-01/fixture-diagnostic-20260830T
  --instance-name "RD测试"`，结果 `status=PASS`、2 行无阻断。cold-001
  `readiness=7861 ms`、`FIRST_FRAME_DRAWN=true`、非黑比例 `0.983263`；hot-001
  `readiness=292 ms`、`FIRST_FRAME_DRAWN=true`、非黑比例 `0.983292`；两轮均有
  Window/Surface/ViewRoot 正常证据、无 FATAL、无 ANR、无重试。该结果支持通用 CAS
  framework/addView 路径可工作，不支持把问题归因为所有 CAS Activity 都失败。
- **根因及分类结论**：导入、revision、native extract、catalog 和通用 fixture 均已
  通过；夸克 direct path 也通过。失败稳定出现在 sandbox 的 host Stub/guest logical
  Activity、嵌套 `BrowserActivity` handoff 与首帧门之间。现有证据足以确认
  `KI-R03-062` 的真实阻断和 app/SDK/Guest 边界候选，但不足以在 CAS 通用、SX adapter/UI、
  App/SDK 特有、RD 环境、验收脚本之间做唯一根因判定，故保持
  `NEEDS_REPRODUCTION_AND_CLASSIFICATION`，不得猜测或关闭 KI。当前唯一相关的
  `CONTENT_PROVIDER_AUTHORITY_NOT_VIRTUALIZED:com.vivo.push.sdk.service.SystemPushConfig`
  来自另一进程，未提升为本次 gate 根因。
- **VA/NBB 对照与实现摘要**：对照 VA/NBB 的 ActivityThread、WindowSession/IWindow、
  token/task 和 Window identity 合同，CAS 当前仍由 broker/guest instrumentation 做包、
  revision、host Stub 与 logical Activity 映射；正常 framework `addView` 负责创建
  ViewRoot/Surface 后才允许进入 `FIRST_FRAME_DRAWN`。本窗口仅增加两次可观测性提交：
  `2bb1f2864bc2b556fa5987bff2c69445be0a0862`
  （`ActivityFieldBridge` post-resume Window 状态）和
  `ffef74c31edcb4497a51bf18b9e6a869d6593e53`
  （`ActivityFieldBridge` ViewRoot 生命周期字段）；均为一次性只读诊断，不改变
  addView、visibility、deadline、retry 或错误处理语义。没有把 C4-R02/R03 的既定修复
  偷换成临时行为补丁。
- **修改文件与验收命令**：源码为
  `sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/component/activity/ActivityFieldBridge.java`；
  本回执新增当前窗口分类/证据索引、Known Issues 更新和 fixture 证据。已通过
  `./gradlew.bat :sandbox-runtime:compileDebugJavaWithJavac --no-daemon`、
  `./gradlew.bat :app:assembleDebug :fixture-basic:assembleDebug
  :sandbox-companion32:assembleDebug :fixture-compat32:assembleDebug --no-daemon`、
  `python scripts/check-c4-temp-01-latency.py`、`python scripts/check-apk-revision-binding.py`、
  `python scripts/check-c4-r05-orchestrator.py`、两 runner `py_compile` 和 `git diff --check`。
- **APK/设备摘要**：本窗口安装文件当前 SHA-256 为 app
  `14e7511f66311677434bb35c61737e35e7b3643382e978ccf08d875cafba4324`、fixture
  `8d8f4d776b287ea947358690882a33763c3967578952cc88e57d5188ca8275a5`、companion32
  `b7304a02c71b0001d3baca31a162cceeb87d3d4113d0f557965f7c04d533ab1d`、fixture-compat32
  `9193694ee36848992e98d7e1ff7197a833182807784eb5a375f0d32bff5c96e1`；设备 boot ID
  为 `bd6fc459-0d52-4689-868a-420364ea407c`。夸克 raw device output 按仓库约定保留在
  本地 ignored run directory，已在 artifact index 中列出关键文件 hash；fixture 证据目录
  纳入本回执提交。
- **Known Issues 变化**：更新 `KI-R03-062` 增加本窗口两条独立动态 lane、首失败签名、
  fixture 对照和恢复边界；该项仍为 `RECORDED`、`acceptance: NOT_FIXED`、
  `blocks_current_campaign: true`。没有关闭 KI-R03-053 至 KI-R03-062 中任何阻断项，
  也没有更改 C5-T01 至 C5-T04 的 `NOT_APPLICABLE` 结论。
- **重试记录与偏离任务书说明**：所有 lane 首次观测均 `attempt=1`、`retryBudget=0`、
  无自动重试；第二条 lane 为独立 validation，不是失败后的隐藏重试。未执行商业样本
  10 次矩阵、C4-R05 两轮正式验收或 30 分钟双用户短测，原因是 TEMP-01 的动态夸克首帧
  门禁真实阻断；继续执行会违反“阻断后停止后续任务”的任务书要求。
- **遗留风险与恢复条件**：仍需在不降低门槛的前提下，补齐当前 Quark nested
  `BrowserActivity` 的 CAS readiness 与 App/SDK/Guest owner 边界，并在同一 clean commit
  通过 dynamic Quark direct/sandbox 首帧门禁、fixture 回归及其余任务要求后，重新打开
  TEMP-01；在此之前不得恢复 C4-R05，也不得以 late frame、Guest 进程存在、Activity marker
  或 Quark direct 成功替代真实 `FIRST_FRAME_DRAWN`。
- **提交、推送与下一任务**：实现提交 `2bb1f2864bc2b556fa5987bff2c69445be0a0862`、
  `ffef74c31edcb4497a51bf18b9e6a869d6593e53` 已分别推送；本回执/证据提交 SHA 在提交
  `38440d5f2df62c6e50cb609444353cb7d0aea29c` 已推送。推送后必须再次核验本地与远端
  HEAD 一致、工作区干净；最终续接预检因
  `C4-TEMP-01` 为 BLOCKED 应 fail-closed；最终原始输出已记录在
  `verification/catch-up/C4-TEMP-01/20260830T1150-final-continuation-preflight-failure.md`，
  不得识别为 C4-R05。

### C4-TEMP-01：修复续作启动记录（2026-08-30 17:14）

- **任务 ID / 当前状态**：`C4-TEMP-01 / IN_PROGRESS`。这是用户明确要求“开始修复”后
  对上一轮真实首帧阻断的重新修复尝试；保留历史 BLOCKED 回执及首次失败证据，不将旧失败
  改写为 PASS。当前只有本任务处于 `IN_PROGRESS`，C4-R05 仍为 BLOCKED。
- **开始时间与开始基线**：开始时间 `2026-08-30 17:14:45 +08:00`；开始 commit、
  分支和远端 HEAD 均为 `f4d69bb53d6a8f2cc70469902c40ffad77d2d431`、
  `feature/t57-r03-va-pro-capability-campaign`、
  `origin/feature/t57-r03-va-pro-capability-campaign`；上一回执为
  `38440d5f2df62c6e50cb609444353cb7d0aea29c`，最终 SHA 收尾为
  `f4d69bb53d6a8f2cc70469902c40ffad77d2d431`。
- **拉取与预检边界**：已执行 `git fetch origin --prune` 和
  `git pull --ff-only origin feature/t57-r03-va-pro-capability-campaign`，结果
  `Already up to date`，本地/远端一致、工作区干净、身份为
  `OpenAI <openai@users.noreply.github.com>`。由于历史任务为 BLOCKED，直接预检按
  fail-closed 返回 `ledger next task C4-TEMP-01 is not first dependency-ready PENDING task`；
  本次明确启动修复后将以唯一 `IN_PROGRESS` 状态重新运行预检并记录结果。
- **事实源与参考重读**：已重新完整读取任务书、进度账本、C4 RD 重测根因与验收计划、
  `KNOWN_ISSUES.yaml`、能力活动工作流、提交身份策略、C4-TEMP-01 设计，以及 C4-R01/R02/
  R03/R04/R05 的专项设计、证据边界、交接和 VA/NBB 参考映射。当前工作从保存的首失败
  快照开始，不先重跑已知失败。
- **RD 测试快照**：`python scripts/mumu_instance.py --instance-name "RD测试"` 于
  `2026-08-30 17:14:52 +08:00` 动态解析 MuMu `RD测试`（索引 1，
  `MuMuPlayer-12.0-1`），API 32，型号 `22041211A`，ABI
  `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`，Android ID `398eea33120cd887`，
  boot ID `bd6fc459-0d52-4689-868a-420364ea407c`；resolved serial 只保留在快照，
  不进入源码、runner 或业务逻辑。
- **修复目标与约束**：围绕 `KI-R03-062` 的 nested `BrowserActivity` handoff、
  ActivityThread/WindowSession/IWindow、task/token、Window identity 与真实首帧合同，
  查明是否存在可最小修复的 CAS owner 边界。禁止固定 sleep、隐藏重试、扩大 deadline、
  捕获所有异常循环 addView、静态 marker 替代首帧或 package-specific 旁路；首次失败必须
  保留 request/operation、阶段计时、logcat、Activity/Window/Surface/ViewRoot、事务/catalog、
  截图、APK 和 boot 证据。
- **下一步**：先检查 `ActivityFieldBridge`、`GuestActivityThreadInstrumentation`、
  `RuntimeActivityLaunchCoordinator`、`GuestLaunchObservation/Gate` 及对应测试，形成
  可验证的最小修复设计后再编译；修复完成前不标记 DONE、不启动 C4-R05。

### C4-TEMP-01：修复完成回执（2026-08-30）

- **任务 ID**：`C4-TEMP-01`
- **状态**：`DONE`
- **实现提交 SHA**：`7c0c819a58513f89e91ec0fb44cdc05a151e2c32`
- **任务 ID / 最终状态**：`C4-TEMP-01 / DONE`。本回执仅关闭临时耗时任务，不把临时
  3+3 结果写成 C4-R05 或 C4 阶段完成；C4-R05 已恢复为唯一下一依赖就绪任务并置为
  `PENDING`。历史 BLOCKED 回执和首次失败证据全部保留。
- **开始/结束时间与开始基线**：开始 `2026-08-30 17:14:45 +08:00`，结束
  `2026-08-30 22:09:13 +08:00`；开始基线为
  `f4d69bb53d6a8f2cc70469902c40ffad77d2d431`，启动记录提交为
  `d2d2d5b1c37d3cad669d2964129d12fadc3ccdf9`；分支为
  `feature/t57-r03-va-pro-capability-campaign`，远端为
  `origin/feature/t57-r03-va-pro-capability-campaign`；上一回执为
  `38440d5f2df62c6e50cb609444353cb7d0aea29c`。
- **续接、Git 和身份**：任务开始前已执行 `git fetch origin --prune`、
  `git pull --ff-only origin feature/t57-r03-va-pro-capability-campaign`；
  结果 Already up to date），并运行 `python scripts/verify-catch-up-continuation.py`，
  结果 `PASS` 且识别当前 `C4-TEMP-01`。分支和身份检查通过，身份为
  `OpenAI <openai@users.noreply.github.com>`；代码提交前后未覆盖已有用户修改。
- **执行环境与 RD 快照**：Windows PowerShell，MuMu `RD测试`（动态解析，索引 1，
  `MuMuPlayer-12.0-1`），Android API 32，型号 `22041211A`，ABI
  `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`，Android ID `398eea33120cd887`，
  boot ID `bd6fc459-0d52-4689-868a-420364ea407c`。resolved ADB serial 仅保存在 raw
  设备快照，没有进入源码、runner 或业务分支。
- **首次失败证据**：保留了前序 route-fence、diagnostic-stack 和 layout-inflater
  独立 lane；本次修复前最后一次首失败为 request
  `c73ef73bc087476789e90baa65b3f6e3` / operation
  `c73ef73bc087476789e90baa65b3f6e3-launch`，attempt=1、`retryBudget=0`、无自动重试，
  raw 证据位于 `verification/catch-up/C4-TEMP-01/quark-layout-inflater-20260830/20260830T180827/`。
  其 Guest 时间线显示后台 Receiver 登记到注销约 15,032 ms，与
  `GuestMainThreadDispatcher.DEFAULT_TIMEOUT_MS=15_000` 一致；首次失败快照、logcat、
  Activity/process、Window/Surface/ViewRoot、截图和事务/catalog 均已保留。
- **根因及责任分类**：已确认是 CAS 通用 `GuestDynamicReceiverTransport` 把调用方 SDK
  worker 的 Host `registerReceiver`/`unregisterReceiver` 强制同步切到 Guest 主线程，和
  Quark `BrowserActivity.onCreate` 等待 worker 形成锁反转；不是 SX adapter/UI、不是验收
  脚本门槛，也没有证据把它归因于 RD 低内存。Quark 自身剩余导入/prepare 成本仍作为
  App/SDK/环境待观察项，不以扩大 timeout 或包名旁路处理。
- **VA/NBB 对照**：采纳 Android `Context.registerReceiver(..., Handler, ...)` 的调用线程
  与回调 Handler 分离合同，以及 VA/NBB 的 Broker receiver registry、permission、lease、
  death cleanup 与 framework dispatcher 分层边界；不采纳绕过 Broker、把回调交给 Host
  主线程、静默 retry、固定 sleep 或扩大 deadline。相关设计补充在
  `docs/review/C4_TEMP_01_CAS_IMPORT_LAUNCH_LATENCY_ROOT_CAUSE_AND_FIX_DESIGN_20260828.md`
  第 8 节。
- **实现摘要**：动态 Receiver 的 Host 登记、注销和 close teardown 改为在发起线程直接
  执行，仍以传入 Handler 投递 Guest callback，保留 Broker lease、身份、异常回滚和清理；
  Guest/GuestPackage Context 缓存 Context-bound LayoutInflater；物理 Stub route 放行前
  重新确认 ActivityThread instrumentation，失败留证并 fail-closed；补齐 static Android
  compiler 的 ViewParent/visibility stub，使诊断字段可被完整静态编译。
- **修改文件**：
  `sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestDynamicReceiverTransport.java`、
  `GuestContext.java`、`GuestPackageContext.java`、`GuestRuntimeEnvironment.java`、
  `sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/component/activity/StubActivityBase.java`、
  `sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/guest/GuestContextBoundarySelfTest.java`、
  `scripts/check-c4-temp-01-latency.py`、`tools/static_android_compile.py`、
  `docs/review/C4_TEMP_01_CAS_IMPORT_LAUNCH_LATENCY_ROOT_CAUSE_AND_FIX_DESIGN_20260828.md`、
  `docs/review/KNOWN_ISSUES.yaml`、`.gitignore` 及本回执引用的机器可读 benchmark/classification。
- **验收命令与结果**：
  `python scripts/check-c4-temp-01-latency.py` PASS；
  `python scripts/check-c4-r04-fail-closed.py` PASS；
  `python scripts/test_c4_r04_fail_closed.py` PASS（7 tests）；
  `python scripts/check-c4-r05-orchestrator.py` PASS；
  `python scripts/check-apk-revision-binding.py` PASS；
  `python tools/static_android_compile.py` PASS（模块编译及全部 test-harness）；
  `.\gradlew.bat :app:assembleDebug :fixture-basic:assembleDebug :sandbox-companion32:assembleDebug
  :fixture-compat32:assembleDebug --no-daemon` BUILD SUCCESSFUL；`git diff --check` PASS。
- **动态验收**：package-neutral fixture 命令
  `python tools/capability/run_c4_r03_rd.py --loops 1 --users 0 --targets fixture
  --output verification/catch-up/C4-TEMP-01/fixture-receiver-thread-20260830` PASS（2 行，
  cold/hot 均真实 Window/Surface/首帧/非黑屏）。Quark 命令
  `python tools/capability/run_c4_temp_01_quark_latency.py --samples 3 --instance-name "RD测试"
  --output verification/catch-up/C4-TEMP-01/quark-receiver-thread-20260830` PASS：直启
  7534/7042/6114 ms，CAS 63013/41235/39081 ms，比值 8.3638/5.8556/6.3921，最大
  8.3638，小于 10x 硬门槛但高于 3x 目标。CAS 三次均为真实
  `LAUNCH_PASS`/`FIRST_FRAME_DRAWN`，readiness 14387/14368/13668 ms，activity
  created/resumed、Window/Surface、readyForDisplay、drawn、surfaceShown 和非黑截图
  全部为真；attempt=1、`retryBudget=0`、无自动重试。
- **商业样本矩阵**：本 TEMP 只要求动态 Quark 3+3 与 package-neutral fixture，已完成；
  Quark 实际 package `com.quark.browser`、入口 `com.ucpro.MainActivity`、child
  `com.ucpro.BrowserActivity`、版本 `10.10.5.1080/code1080`、base=1/split=0、
  primary ABI `arm64-v8a` 已记录。DingTalk、红果、番茄小说的 5 次添加矩阵及 R05 两轮
  正式验收不在 TEMP 内提前宣称通过，现由 C4-R05 执行。
- **APK / revision / evidence**：本次构建 APK SHA-256 为 app
  `F84FE832532C6243DE37EE815C940F85F3FDBC7B79BB873F6918CFFBCB247026`、fixture-basic
  `8D8F4D776B287EA947358690882A33763C3967578952CC88E57D5188CA8275A5`、companion32
  `95CA996A9005C7FEA1BAFADA62EEC27C90D53F0BCC84FF99CB7B394FDBFDF529`、fixture-compat32
  `9193694EE36848992E98D7E1FF7197A833182807784EB5A375F0D32BFF5C96E1`；Quark broker
  revision 为 `v1080:sha256:2cb38172da5da4aee03826da0feccb77ff0391ee7356f1562052ddc1fae9ecb3`。
  机器可读摘要为 `verification/catch-up/C4-TEMP-01/20260830T181923-benchmark-summary.json`，
  分类和证据索引为 `20260830T181923-first-frame-fix-classification.md`，raw run tree
  保留在对应日期目录。
- **Known Issues 变化**：`KI-R03-062` 的历史首失败仍保留，状态仍为
  `RECORDED`/`acceptance: NOT_FIXED`/`blocks_current_campaign: true`，但本次已关闭其
  可复现的 CAS Receiver 主线程锁反转签名；完整 KI 关闭留待 R05 全量回归。`KI-R03-053`、
  `054`、`057`、`058`、`059`、`061`、`060` 均未被本 TEMP 错误关闭，C5-T01 至 C5-T04
  仍为 `NOT_APPLICABLE`。
- **重试记录**：所有前序失败 lane 和本次 pass lane 均保留 request/operation；失败均
  attempt=1、`retryBudget=0`、无自动重试。未使用固定 sleep、扩大总 timeout、捕获所有
  异常循环、marker 或 Guest 进程存在替代真实显示成功。
- **偏离任务书与遗留风险**：3x 是目标而非硬门槛，本次最大 8.3638x，未把目标写成达成；
  未执行 R05 的商业添加矩阵、两轮 25 冷/25 热启动、C1/C2/C4/SX 回归和 30 分钟双用户
  短测，因为这些属于下一任务。残余风险是 Quark 导入/prepare 的非硬阻断成本、现有 KI
  和低内存环境项，必须由 R05 在同一 clean commit 继续验收。
- **提交与推送**：实现/设计/测试/证据提交为
  `7c0c819a58513f89e91ec0fb44cdc05a151e2c32`，主题
  `fix(c4): [C4-TEMP-01] remove Guest-main-thread receiver hop`；本段为独立进度回执，
  提交主题 `docs(progress): record [C4-TEMP-01] receipt`。回执提交后按任务书执行
  `git push origin feature/t57-r03-va-pro-capability-campaign`、`git ls-remote --heads`
  对比和工作区清洁核验；在这些步骤成功后本任务状态才成立。
- **下一任务**：`C4-R05 / PENDING`。恢复条件已满足 TEMP 的硬门槛，但 C4 阶段仍未关闭。

### C4-R05：正式重验启动记录（2026-08-30 22:11）

- **任务 ID / 当前状态**：`C4-R05 / IN_PROGRESS`。这是在 C4-TEMP-01 完成、实现与
  回执均推送且最终续接预检识别 `C4-R05` 后启动的正式关门任务；C4-R05 的历史 BLOCKED
  证据不覆盖，本次只从当前 clean baseline 开始。当前只有本任务处于 `IN_PROGRESS`。
- **开始时间与基线**：开始时间 `2026-08-30 22:11:35 +08:00`；开始 commit、分支和
  远端 HEAD 均为 `2cd121711df9a8347dd7e9e897f1eb6cdf60fcbb`、
  `feature/t57-r03-va-pro-capability-campaign`、
  `origin/feature/t57-r03-va-pro-capability-campaign`；上一任务 C4-TEMP-01 的实现提交为
  `7c0c819a58513f89e91ec0fb44cdc05a151e2c32`，正式回执提交为
  `b2c021c884058491bf8a966eca653906138f84e2`，格式修复提交为
  `2cd121711df9a8347dd7e9e897f1eb6cdf60fcbb`。
- **续接预检与 Git 前检**：C4-TEMP-01 收口后运行
  `python scripts/verify-catch-up-continuation.py`，结果 `PASS`，识别
  `C4-R05` 为第一依赖就绪任务；分支、上游、local/remote HEAD 和 Git 身份均通过，
  身份为 `OpenAI <openai@users.noreply.github.com>`。本任务标记后将再次运行预检，
  并在所有正式验收结束前保持单一 `IN_PROGRESS`。
- **事实源重读**：开始前重新完整读取任务书、进度账本、
  `docs/review/C4_RD_RETEST_ROOT_CAUSE_AND_ACCEPTANCE_PLAN_20260824.md`、
  `docs/review/KNOWN_ISSUES.yaml`、能力活动工作流、提交身份策略、C4-R02/R03/R04/R05
  专项设计、证据边界、交接、VA/NBB 参考映射及 C4-TEMP-01 最新修复设计；采用任务书
  当前正式门禁：两轮 `loops=25`，clean-install/cold 与 retained-state/hot/recovery，
  每 target/user 冷热各 25 次，添加 fixture 25 次和商业样本各 5 次，C1/C2/C4/SX 回归，
  user0/user1 各 15 分钟且至少 50 周期。未执行 8 小时 soak，亦不以历史结果替代本轮。
- **RD 测试设备快照**：通过 `python scripts/mumu_instance.py --instance-name "RD测试"`
  动态解析于 `2026-08-30 22:11:37 +08:00` 得到 MuMu `RD测试`（索引 1，
  `MuMuPlayer-12.0-1`），runtimeStatus=device，API 32，型号 `22041211A`，ABI
  `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`，Android ID `398eea33120cd887`，
  boot ID `bd6fc459-0d52-4689-868a-420364ea407c`。resolved serial 仅进入本轮证据快照，
  不进入源码、runner 或包名分支。
- **当前 Known Issues**：`KI-R03-053`、`054`、`057`、`058`、`059`、`061`、`062`
  仍为 `RECORDED` 阻断项，`KI-R03-060` 为已接受但必须回归的开放项；C5-T01 至 C5-T04
  保持 `NOT_APPLICABLE`。R05 必须在每一轮把这些 issue 的实际结果、首次失败证据和
  关闭/保留结论写入最终回执，不得因 TEMP 的性能硬门槛通过而自动清除。
- **执行边界**：本任务不扩大 deadline、不固定 sleep、不静默 launch retry，不以
  `LAUNCH_PASS`、Activity marker、Guest 进程存在或夸克成功替代当前 package/user/revision
  的真实 `FIRST_FRAME_DRAWN`；首次失败立即保存 request/operation、attempt/retry decision、
  logcat、Host/Guest、dumpsys、Window/Surface/ViewRoot/进程、截图、事务/catalog、APK/commit/
  boot 证据，随后按任务书 fail-closed 停止该轮并决定是否阻断 C4-R05。
- **下一步**：先运行本启动记录对应的续接预检与 formal R05 orchestrator 静态门，确认
  当前 commit clean；然后执行第一轮 clean-install/cold，完成后不在单任务汇报环节停留，
  直接按账本继续第二轮 retained-state/hot/recovery 和其余正式矩阵，直到通过或形成真实阻断。

### C4-R05：readiness terminal publication race 首次失败与修复（2026-08-31）

- **首次失败状态**：本次续接没有重试失败 case。第二轮 retained-state/hot/recovery 的
  `dingtalk / user0 / hot-001` 首次观察在 `07:37:36 +08:00` fail-closed，request
  `b4a8ef989fd6459ba15db59aab8a4e5b`、operation
  `b4a8ef989fd6459ba15db59aab8a4e5b-launch`、attempt=2、`retryBudget=0`、
  `automaticRetryPerformed=false`，错误为 `LAUNCH_OBSERVATION_NOT_FOUND`。失败时
  Guest Window/Surface/截图有效，但 correlated terminal result 不可读；因此不能用
  独立显示证据覆盖 command failure。
- **证据与根因**：原始失败目录为
  `verification/catch-up/C4-R05/formal-two-round-20260830-receiver-fix/round-2-retained-hot-recovery/launch-matrix/attempt-002/attempts/dingtalk/user-0/hot-001/`。
  logcat 按顺序保留 `FIRST_FRAME_DRAWN`、`GUEST_LAUNCH_READINESS=LAUNCH_PASS`、
  `LAUNCH_OBSERVATION_NOT_FOUND`；根因归类 CAS 通用 readiness terminal-result
  publication race：异步观察线程先移除 observation aliases，后发布 terminal result，
  DebugCommand poll 在两者之间读到空窗。不是 SX/UI、商业包黑屏或 RD 丢失。完整分类、
  VA/NBB 对照和采纳/不采纳理由见
  `docs/review/C4_R05_READINESS_PUBLICATION_RACE_FIX_20260831.md`。
- **修复与验证**：实现提交 `d80c9e1538ed60152094d6c4ed4b7bc66d01f1ce` 将
  `publishLaunchReadiness` 前置到 `removeObservationMappings`，不改变 deadline、
  retry budget 或 readiness 标准。静态 checker、R04/R05 gate、Gradle debug build、
  `python tools/static_android_compile.py` 和 `git diff --check` 通过；动态 DingTalk
  user0 cold/hot 2/2 通过并具备真实 FIRST_FRAME_DRAWN/Window/Surface/non-black 证据。
- **Known Issues 更新**：新增 `KI-R03-063`，当前 `RECORDED`、
  `acceptance: NOT_FIXED`、`blocks_current_campaign: true`；只在完整 R05 两轮 clean
  commit 回归通过后关闭。原 `KI-R03-053/054/057/058/059/061/062` 未被本次定向回归
  擅自清除。
- **当前状态/下一步**：`C4-R05` 仍为唯一 `IN_PROGRESS`；本次修复文档和 KI 记录提交
  后，重新运行续接预检、formal orchestrator，并从新的修复 clean commit 重新开始两轮
  正式验收。上一轮 102 条输出只作失败证据，不与新的 formal PASS 合并。

### C4-R05：recovery prewarm PREPARING 残留首次失败与修复（2026-08-31）

- **首次失败证据**：formal 首轮 clean-install/cold 已完成并确认 162 个 case；续接
  `dingtalk/user1/cold-007` PASS 后，紧接的 `dingtalk/user1/hot-007` 首次失败。
  request=`9746f9500c97412caee29f44eebf9896`，operation=`9746f9500c97412caee29f44eebf9896-launch`，
  attempt=2（续接 lane），`retryBudget=0`，`automaticRetryPerformed=false`，
  `retryable=false`，错误 `SESSION_BUSY:PREPARING`。原始证据目录为
  `verification/catch-up/C4-R05/formal-two-round-20260831-publication-race-fix/round-1-clean-install-cold/launch-matrix/attempt-002/attempts/dingtalk/user-1/hot-007/`，
  保留 logcat、Activity/Window/Surface、截图、事务/catalog、boot/commit/APK 关联快照。
- **根因分类**：CAS 通用 Guest lifecycle 回滚缺口，非 SX adapter/UI、商业包黑屏、
  设备断连或验收编排重试。`cold-007` 后延迟 recovery prewarm 的 Guest 回调产生
  `PREPARED_SPEC_MISSING`；外层 `prepareGuest` 只返回 FAILED，没有清理已分配的
  `PREPARING` generation，导致随后显式 hot launch 被正确拒绝。
- **VA/NBB 对照与修复**：恢复事务在 prepare 失败时终止当前 lease 并回收
  process/window/component ownership，不让半完成 generation 继续可用。现于
  `RuntimeGuestLifecycleCoordinator.prepareGuest` 外层失败路径增加统一 rollback：
  `ALLOCATED/PREPARING -> FAILED`、释放 slot、移除 prepared spec、停止系统服务并
  失效 Activity/Service/Receiver/Provider/cross-ABI ownership；写入
  `GUEST_PREPARE_ROLLBACK`。不改变 retry budget、deadline、FIRST_FRAME_DRAWN 门槛。
- **验证进度**：`python tools/static_android_compile.py`、`:sandbox-domain:test`、
  `:sandbox-runtime:test`、`git diff --check` 已通过；待构建新 APK 后从该失败坐标
  继续，重新完成剩余首轮及第二轮正式验收。新增 `KI-R03-064`，当前仍为
  `RECORDED`/`acceptance: NOT_FIXED`/`blocks_current_campaign: true`。

### C4-R05：结构化 FAILED Bundle 未回收 PREPARING 的补充修复（2026-08-31）

- **补充证据**：新 APK 的独立 DingTalk user1 冷/热 2-row 回归中，cold-001 PASS；
  hot-001 首次失败为 `SESSION_BUSY:PREPARING`，request=`7ddbc8e2e3344d40ac73d3bdccd20b5f`，
  operation=`7ddbc8e2e3344d40ac73d3bdccd20b5f-launch`，attempt=1，retry budget 0，
  无自动重试。其 logcat 显示 recovery prewarm 收到结构化 `FAILED` 结果，Guest
  `VIRTUAL_SYSTEM_SERVICE_CAPABILITY_DENIED`，随后会话仍为 generation 2 PREPARING。
- **补充根因**：预热的 `prepare()` 对 Guest `FAILED` Bundle 不抛异常，原协调器只记录
  `GUEST_RECOVERY_PREWARM_COMPLETED status=FAILED`，没有执行终态回收；因此仅补外层
  Throwable catch 不足。
- **补充修复**：`GuestRecoveryPrewarmCoordinator` 现在识别 `status=FAILED`，将当前
  `ALLOCATED/PREPARING` generation 转为 `FAILED`，移除 prepared spec 并写入
  `GUEST_RECOVERY_PREWARM_ROLLBACK`；仍不引入重试、sleep 或门槛降低。
- **验证**：静态 Android 编译、`:sandbox-runtime:compileDebugJavaWithJavac`、
  `git diff --check` 通过；该针对性失败证据已保留，须在新 clean commit 重新构建后
  复测冷→热，随后才能恢复 R05 正式矩阵。

### C4-R05：recovery prewarm rollback 针对性复测通过（2026-08-31）

- 新 clean commit `0e6e0591` 构建并安装后，DingTalk user1 独立 cold→hot 2-row 回归
  `verification/catch-up/C4-R05/recovery-prewarm-rollback-targeted-20260831-v2/`
  结果 `PASS`，2/2 均达到真实 `FIRST_FRAME_DRAWN`、Guest Window、Surface 和非黑屏
  证据，未发生自动重试。
- 该结果仅证明本次 recovery prewarm PREPARING 残留修复通过针对性回归，不替代 R05
  两轮正式矩阵；`KI-R03-064` 仍保持 `RECORDED`、`acceptance: NOT_FIXED`、阻断状态。
- 当前下一步：在该 clean commit 上启动新的 R05 formal 两轮验收；旧 formal 输出不与
  新 commit 混合，旧输出仅作为首次失败证据保留。

### C4-R05：hot Host Activity teardown race 首次失败与修复（2026-09-01）

- **首次失败与中断关系**：formal lane `formal-two-round-20260831-prewarm-rollback-v2`
  的首个非环境失败是 Quark user0 hot-011，request=`ab260ac494814d72b3637abad6ad899e`、
  attempt=1、retryBudget=0、automaticRetryPerformed=false。前一轮 Host StubActivity60
  出现 top-resumed/pause timeout，下一次 START 虽返回但没有 Guest ActivityRecord/Window
  生命周期证据，30 秒观察到期后 fail-closed。该失败发生在后续低内存检测与 MuMu restart
  之前，故不是中断重试引起；重启后的独立 attempt=2 首帧可见但 readiness=13032 ms，
  超过 hot 10 秒 SLO。
- **分类与修复**：新增 `KI-R03-065`，分类为验收编排/Host ActivityRecord teardown
  证据缺口，不归因于 Quark SDK。`tools/capability/run_p1_00_rd.py` 对 hot command 在
  启动下一次 DebugCommandActivity 前动态等待 ATMS ActivityRecord 与 WM Window 消失；
  不停止 Guest、不固定 sleep、不自动重试。原始 full snapshot 保留在失败目录。
- **本地验证**：`python -m py_compile tools/capability/run_p1_00_rd.py tools/capability/run_c4_r03_rd.py tools/capability/run_c4_r05_rd.py` PASS；`python scripts/check-c4-r05-orchestrator.py` PASS；`python scripts/verify-catch-up-continuation.py` PASS。待新 clean commit 构建并重跑 formal 两轮，才能关闭该 KI 和 C4-R05。

### C4-R05：正式两轮验收在 LOW_MEMORY 恢复后的独立 hot 续接失败（2026-09-01）

- **任务 ID / 最终状态**：`C4-R05 / BLOCKED`。本段是当前正式验收的失败回执；不得将
  C4-R05 标记为 `DONE`，不得进入后续任务，C4 阶段保持 `BLOCKED`。
- **开始/结束时间**：formal run `2026-09-01T11:21:45.130817+08:00` 至
  `2026-09-01T12:58:29.787644+08:00`（Asia/Shanghai）。开始基线为 clean commit
  `58e86b09cf8a6671e3d064042976ba5487c57ec2`，分支为
  `feature/t57-r03-va-pro-capability-campaign`；启动前 local/remote HEAD 一致，Git 身份为
  `OpenAI <openai@users.noreply.github.com>`。
- **上一任务回执**：`C4-TEMP-01 / DONE`，实现提交
  `7c0c819a58513f89e91ec0fb44cdc05a151e2c32`，回执/收口提交
  `2cd121711df9a8347dd7e9e897f1eb6cdf60fcbb`。本轮被测基线承接了 R05 hot Host Activity
  teardown barrier 的 `58e86b09` 修复；此前针对性 recovery prewarm 复测已通过，但不替代本轮。
- **执行环境与 RD 快照**：动态解析 MuMu `RD测试`（index 1，`MuMuPlayer-12.0-1`），
  serial `127.0.0.1:16416`，API `32`，型号 `22041211A`，ABI
  `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`，Android ID `398eea33120cd887`，
  formal start boot `de531bad-89f7-4470-ae4c-408a70bfdf43`。APK/commit/device 及全量
  first-failure artifact 索引见
  `verification/catch-up/C4-R05/20260901-formal-failure-classification.md`。
- **验收范围**：按 R05 `c4-stage-reduced` 两轮门禁启动：两轮、每 target/user 冷热各 25
  次，fixture 添加 25 次、商业样本添加各 5 次，C1/C2/C4/SX 回归，user0/user1 各 15 分钟
  且至少 50 周期；未执行 8 小时 soak。构建命令、R04 failure-injection/recovery 和 R02
  减半添加门禁均 `PASS`。
- **首次失败证据**：`attempt-001` 是用户会话中断，只有 3 个已持久化 case row，没有失败
  case；selector 只从 durable row 续接，未把中断当 PASS。首次实际失败为 DingTalk
  `com.alibaba.android.rimet` / user1 / hot-019，request
  `71304b9029ce4641b1d0307b4777fab9`，operation
  `71304b9029ce4641b1d0307b4777fab9-launch`，runner operation
  `c4-r03-dingtalk-u1-hot-19-a2-71304b9029`，attempt=2、retryBudget=0、
  `retryable=false`、`automaticRetryPerformed=false`；命令因
  `RD_ENVIRONMENT_RESOLUTION_BLOCKED: debug-command-result timeout` 失败。Host
  `ApplicationExitInfo` 明确记录 `com.warden.controlledsandbox.debug`、reason
  `3 (LOW_MEMORY)`、PSS `188MB`、RSS `246MB`，并有同时间 lowmemorykiller 记录，完整快照保留。
- **允许的恢复及终止失败**：依据 R05 LOW_MEMORY 例外，仅动态解析 MuMuManager 并重启一次；
  `restart.json` 记录 boot 从 `de531bad-89f7-4470-ae4c-408a70bfdf43` 变为
  `fbd12b02-b1dd-49e0-9946-6f94b0da4a64`。attempt-003 使用新 request
  `fbfdb9b18d0e413b8471199e13e56254`、operation
  `fbfdb9b18d0e413b8471199e13e56254-launch`，runner operation
  `c4-r03-dingtalk-u1-hot-19-a3-fbfdb9b18d`，仍为 retryBudget=0 且无自动重试；
  command/operation 均报告 `PASS/LAUNCH_PASS`，但关联真实首帧的 readiness 为 `18,345 ms`，
  超过 hot deadline `10,000 ms`，分类为 `READINESS_SLO_EXCEEDED`。Window、Surface、
  Activity resumed、非黑截图均有效，但不能覆盖超时门禁；该非 LOW_MEMORY 失败使本轮正式
  停止，未再重试。
- **根因及分类**：分类为“MuMu/RD Host memory-pressure process-owner 边界 + 新 boot 后
  CAS/Guest recovery-startup latency”，不是黑屏、静态 marker、Guest 进程存在或 SX UI
  成功。重启后同一请求阶段记录 package state `5262 ms`、package universe `4338 ms`、
  broker connect `11849 ms`、Guest prepare `11653 ms`，最终在 `FIRST_FRAME_DRAWN` 真实成立
  时已超出 hot SLO。现有证据不能唯一证明 DingTalk SDK 或单一 CAS 方法为唯一根因，需继续
  以 `NEEDS_REPRODUCTION_AND_CLASSIFICATION` 处理，不得擅自做包名分支、扩大 deadline 或
  用 fixed sleep 掩盖。
- **VA/NBB 对照**：已复核 VA `VActivityManagerService.startProcessIfNeedLocked/processDead`、
  `ActivityStack.startActivityProcess/processDied`、`VirtualRuntime.crash`，以及 NBB
  `BProcessManagerService.startProcessLocked`、`ActivityStack.startActivityProcess`、
  `BActivityThread.bindApplication`。两者均以真实 process owner/death/rebind 为边界；CAS
  对应 `RuntimeGuestLifecycleCoordinator` 与 generation-fenced
  `GuestRecoveryPrewarmCoordinator`。本次只确认恢复后启动成本超界，未形成可安全采纳的
  新源码修复；既有 teardown barrier 仍保持有效。
- **实现摘要/修改文件**：本轮被测实现为 `58e86b09` 的动态 Host Activity teardown
  barrier；本次新增的是失败分类与治理记录，不宣称已修复 post-restart latency。新增
  `verification/catch-up/C4-R05/20260901-formal-failure-classification.md`，更新
  `docs/review/KNOWN_ISSUES.yaml` 新增 `KI-R03-066`；本段账本为独立进度提交。
- **验收结果与商业样本矩阵**：R04 注入/恢复 `PASS`；R02 reduced add gate `PASS`（fixture
  25，DingTalk/夸克/红果/番茄小说各 5）。R03 预期 500 行，实际 188 个 terminal coordinate：
  fixture `100`，DingTalk user0 `50`，DingTalk user1 `38`（含 hot-019 终止失败）；夸克、
  红果、番茄小说启动行尚未执行。第二轮、C1/C2/C4/SX 回归和双用户 15 分钟/50 周期短测
  未执行，不能据此关闭 C4。
- **Known Issues 变化**：新增 `KI-R03-066`，状态 `RECORDED`、
  `acceptance: NOT_FIXED`、`blocks_current_campaign: true`；`KI-R03-053/054/057/058/059/061/062/063/064/065`
  均未被本轮擅自关闭，`KI-R03-060` 仍为已接受但必须回归的开放项。C5-T01 至 C5-T04
  保持 `NOT_APPLICABLE`，C6-T01 不前移。
- **重试记录**：attempt-001 为会话中断；attempt-002 为 Host-scoped LOW_MEMORY 首失败，
  仅执行一次有依据的动态 MuMu 重启；attempt-003 为新 boot 上的独立人工续接，并非隐藏
  重试。所有失败记录 `retryBudget=0`、`automaticRetryPerformed=false`；无 deadline 扩大、
  无 fixed sleep、无吞异常、无以晚到首帧改写失败。
- **偏离任务书/遗留风险**：正式两轮在第一轮 R03 launch matrix 处 fail-closed 停止，属于
  任务书允许的阻断路径；没有执行未达到条件的后续轮次。遗留风险为 Host 低内存 owner
  收敛及新 boot 后 CAS/Guest readiness 是否能在 hot SLO 内恢复，需新 clean commit、独立
  raw 目录和完整两轮回归验证。
- **实现提交与回执提交**：被测实现基线 `58e86b09cf8a6671e3d064042976ba5487c57ec2`；
  本次失败证据/KI 回执提交 `72841e79`（完整 SHA 见推送核验）；本段为独立进度回执提交。
  `72841e79` 已推送到 `origin/feature/t57-r03-va-pro-capability-campaign`。
- **下一任务/恢复条件**：下一任务仍为 `C4-R05`（BLOCKED recovery），不得写成 C6-T01。
  恢复前必须完成有界 Host process-owner/memory-pressure 与 post-restart Guest readiness
  设计/修复或明确外部环境校正；随后在新的 clean commit 上重新开始，不得把本轮历史行
  合并为 PASS，并完成两轮正式矩阵、商业样本、回归和双用户短测后再评估关门。

### C4-R05：post-LOW_MEMORY 新 boot Guest rebootstrap 恢复（2026-09-01）

- **状态**：`IN_PROGRESS`。本段重新开启唯一当前任务 C4-R05；C4 同步从 `BLOCKED` 恢复为
  `IN_PROGRESS (REOPENED)`。原 2026-09-01 首次 LOW_MEMORY 和 post-restart hot SLO 失败仍保留，
  不作为 PASS 的组成部分。
- **开始时间与基线**：2026-09-01 13:26（Asia/Shanghai）；开始 commit、分支和远端 HEAD
  均为 `898dc4d53c2e723d56522ec273c88dc120545559`、
  `feature/t57-r03-va-pro-capability-campaign`、
  `origin/feature/t57-r03-va-pro-capability-campaign`；工作区干净；上一回执提交为
  `898dc4d5`，上一被测实现基线为 `58e86b09`。
- **上一任务回执**：C4-TEMP-01 已 DONE；其实现提交为 `7c0c819a`，正式回执/格式提交链已
  推送。C4-R05 上一轮正式失败回执为 `72841e79`（证据/KI）和 `898dc4d5`（账本）。
- **执行环境与设备快照**：Windows PowerShell；MuMu `RD测试` 动态解析，API 32、型号
  `22041211A`；本恢复执行前必须再次解析 serial、boot id、ABI、Android ID 和实例索引；
  禁止将 serial/端口/型号写入源码或 runner。
- **事实源与专项设计**：已重新完整读取任务书、进度账本、C4 重测根因与验收计划、Known
  Issues、能力工作流、提交身份策略、C4-R01/R02/R03/R04/R05 专项设计、VA/NBB 参考映射及
  `docs/review/C4_R05_POST_RESTART_REBOOTSTRAP_DESIGN_20260901.md`。
- **恢复设计**：仅在 host-scoped `LOW_MEMORY` 且一次动态 MuMu 重启成功后，发出一次独立的
  package-neutral `prepare` rebootstrap；显式记录新 request/operation、boot、阶段和终态。
  随后原坐标按原 hot/cold 模式重新发出真实 launch，仍执行原 10/30 秒 SLO 和
  `FIRST_FRAME_DRAWN`/Window/Surface/非黑截图门禁。第二次 LOW_MEMORY、rebootstrap 失败或
  任一非 LOW_MEMORY 失败均直接 BLOCKED；不扩大 deadline、不固定 readiness sleep、不隐藏
  首次失败。
- **当前 Known Issues**：`KI-R03-066` 继续 `RECORDED/NOT_FIXED/blocks_current_campaign=true`；
  其余 C4 P0/P1/高风险问题和 `KI-R03-060` 回归要求原样保留。任务尚未达到 DONE，C6-T01
  不前移。
- **下一步**：先实现并单测该有界 rebootstrap，编译和定向回归通过后，用新的 clean commit
  执行完整 R05 两轮；只有完整矩阵、回归和双用户短测全通过才可追加 DONE 回执。

### C4-R05：host phase boundary durable-lane continuation 修复（2026-09-01）

- **状态**：`IN_PROGRESS`。上一轮 formal 在 R03 launch matrix 的 host 编排边界停止，
  不是 C4-R05 验收通过；原始 LOW_MEMORY、动态重启、Guest rebootstrap 和后续 host timeout
  证据全部保留，C4 仍为 `IN_PROGRESS`，不得进入 C6-T01。
- **首次失败与证据**：`formal-two-round-20260901-rebootstrap-v1` 的
  `round-1-clean-install-cold/launch-matrix` 在 Quark `user1/cold-006` 记录了
  request=`4f1ccc2faa094958a0f2b376839b5281`、operation=`4f1ccc2faa094958a0f2b376839b5281-launch`、
  attempt=1、`retryBudget=0`、`automaticRetryPerformed=false`，命令错误为
  `RD_ENVIRONMENT_RESOLUTION_BLOCKED: debug-command-result timeout`。完整首失败 bundle
  的 `application-exit-info.txt` 明确记录 Host `com.warden.controlledsandbox.debug`
  `reason=3 (LOW_MEMORY)`；Window/Surface/截图等快照仍按原始失败保存，不能覆盖 command
  错误。随后一次动态 MuMu restart 和独立 Guest rebootstrap PASS，rebootstrap 证据位于
  `attempt-002/post-restart-rebootstrap/quark/user-1/post-restart-rebootstrap.json`。
- **真实阻断边界**：attempt-002 已落盘到 `fanqie/user1/hot-015`；R03 期望 500 个终态
  coordinate，当前 durable selector 识别 480 个唯一完成坐标，下一坐标为
  `fanqie/user1/cold-016`。外层 `run_command` 在 14,400 秒 host phase envelope 到期，
  返回 124 且 `summaryStatus=MISSING`；这不是 PASS，也不是新的 App/UI/Guest 首帧根因。
  证据索引为 `summary.json`、`commands/004-round-1-clean-install-cold-c4-r03-launch-matrix.json`、
  Quark cold-006 `case.json` 及其 `first-failure-full`。
- **根因分类与 VA/NBB 对照**：分类为验收编排的 host phase session boundary，叠加已确认
  的 Host LOW_MEMORY 环境事件；未推断为 Quark SDK、SX adapter、黑屏或真实 readiness
  通过。VA 对照为 `VActivityManagerService` process start/death、`ActivityStack` process
  start/death、`VirtualRuntime.crash`；NBB 对照为 `BProcessManagerService.startProcessLocked`、
  `ActivityStack.startActivityProcess`、`BActivityThread.bindApplication`。共同边界均是
  process owner/death/rebind，故继续必须保留原始 request/operation/boot/Window/Surface/
  process/screenshot/transaction evidence。
- **实现与设计**：新增
  `docs/review/C4_R05_HOST_PHASE_BOUNDARY_CONTINUATION_DESIGN_20260901.md`；
  `run_c4_r05_rd.py` 记录 `timedOut`/timeout 秒数，识别 host-scoped LOW_MEMORY 的严格
  证据，并只允许一次 `HOST_PHASE_BOUNDARY_INTERRUPTION` continuation；
  `run_c4_r03_low_memory_continuation.py` 增加完整 durable lane seed，跨所有
  `attempt-*` 保留首失败与恢复观察，最终按最新 coordinate 聚合，不删除历史。未改变
  cold 30 秒、hot 10 秒 FIRST_FRAME_DRAWN deadline、retry budget、固定 sleep 或商业包
  特例。
- **验证**：`python -m py_compile tools/capability/run_c4_r03_low_memory_continuation.py
  tools/capability/run_c4_r05_rd.py scripts/test_c4_r05_orchestrator.py`、
  `python scripts/test_c4_r03_rebootstrap.py`（3/3）、
  `python scripts/test_c4_r05_orchestrator.py`（7/7）、
  `python scripts/check-c4-r05-orchestrator.py`、`git diff --check` 均通过。针对旧 raw
  lane 的纯读取验证确认 481 条观测（含 1 条历史 LOW_MEMORY 首失败）和 480 个唯一终态，
  没有把它误报为 PASS。
- **Known Issues**：新增 `KI-R03-067`，状态 `RECORDED`、`acceptance: NOT_FIXED`、
  `blocks_current_campaign: true`；`KI-R03-066` 仍保持 `RECORDED/NOT_FIXED`。当前
  修复提交后必须在同一 clean commit 上续接/完成两轮正式 R05、C1/C2/C4/SX 回归和
  user0/user1 各 15 分钟且至少 50 周期短测，方可关闭相关 KI 和 C4-R05。
- **下一步**：提交并推送该编排修复及本回执；在同一 clean commit 上从上述 durable lane
  的精确 `fanqie/user1/cold-016` 续接第一轮，随后自动执行 retained-state/hot/recovery
  第二轮及后续回归、短测。若出现第二次 host boundary 或任一非 LOW_MEMORY terminal
  failure，立即保留证据并 fail-closed；未达到全部门槛不得标记 DONE。

### C4-R05：12 小时 host phase timeout 基线启动（2026-09-02 02:29）

- **任务 ID / 状态**：`C4-R05 / IN_PROGRESS`。本段记录用户要求的 12 小时编排器超时
  基线启动；不改变 C4-R05 的两轮、商业样本、真实首帧或双用户短测验收门槛，也不把历史
  formal lane 改写为 PASS。
- **开始时间与基线**：2026-09-02 02:29:06 +08:00；开始 commit、分支和远端 HEAD
  均为 `14a6f38bf6fa1132998227f2bb34cf813071cf35`、
  `feature/t57-r03-va-pro-capability-campaign`、
  `origin/feature/t57-r03-va-pro-capability-campaign`；启动前工作区干净，Git 身份为
  `OpenAI <openai@users.noreply.github.com>`。上一已完成任务 C4-TEMP-01 的实现提交为
  `7c0c819a58513f89e91ec0fb44cdc05a151e2c32`，回执/收口提交为
  `2cd121711df9a8347dd7e9e897f1eb6cdf60fcbb`。
- **上一回执与历史失败**：上一 R05 host-boundary 回执和首失败证据继续保留；
  `formal-two-round-20260901-rebootstrap-v1`、`formal-two-round-20260901-process-tree-fix-v1`
  均为历史观察，不与本次新 formal lane 合并。最新首失败 `quark/user1/cold-020` 的
  `debug-command-result timeout`、Host `LOW_MEMORY` 证据、attempt=1 原始快照及独立
  post-restart observation 均保持权威边界。
- **实现摘要**：`run_c4_r05_rd.py` 新增并默认使用
  `DEFAULT_PHASE_TIMEOUT_SECONDS = 43,200`，通过 `--phase-timeout-seconds` 记录在任务摘要；
  R04、add-gate、launch matrix、回归阶段均使用该预算，launch matrix 同时向嵌套
  `run_c4_r03_low_memory_continuation.py` 传递 `--child-timeout-seconds 43,200`。构建阶段仍
  为独立 3,600 秒；case 级 cold 30 秒、hot 10 秒 `FIRST_FRAME_DRAWN` deadline 未改变。
- **设计/证据文件**：新增
  `docs/review/C4_R05_HOST_PHASE_TIMEOUT_BUDGET_20260902.md`；本次新 formal raw lane 预定为
  `verification/catch-up/C4-R05/formal-two-round-20260902-timeout12h-v1/`，历史 raw lane
  只读保留。`.gitignore` 仅忽略 raw lane，curated JSON、命令记录、回执和索引按任务书单独固化。
- **验收前检查**：`python scripts/verify-catch-up-continuation.py` PASS；动态解析 MuMu
  `RD测试` 得到实例索引 `1`、API `32`、型号 `22041211A`、ABI
  `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`、Android ID `398eea33120cd887`、boot ID
  `51c808e4-f08f-4a9e-a663-792bc715e383`；resolved serial 仅记录于设备快照，不进入源码。
  `python -m py_compile ...`、`python scripts/check-c4-r05-orchestrator.py` 和
  `python -m unittest scripts/test_c4_r05_orchestrator.py` 均通过（10 tests）。
- **当前 Known Issues**：`KI-R03-053/054/057/058/059/061/062/063/064/065/066/067/068`
  仍按各自记录保持未关闭；`KI-R03-060` 保持已接受但必须回归的开放项；C5-T01 至 C5-T04
  继续为 `NOT_APPLICABLE`。12 小时 host timeout 只修正阶段预算，不关闭任何 KI。
- **下一步**：本段回执提交并推送后，从新 clean commit 启动两轮 C4-R05 formal acceptance；
  首次非允许环境例外失败立即保留证据并停止，所有阶段通过后才执行回归和 30 分钟双用户
  短测。当前 C4-R05 未达到 DONE，C6-T01 不前移。

### C4-R05：12 小时 formal lane 第二次 Host LOW_MEMORY 证据与续接（2026-09-02 09:31）

- **任务 ID / 当前状态**：`C4-R05 / IN_PROGRESS`。本条保留当前 12 小时 formal lane 的
  原始失败证据；按宿主机性能策略，C4 保持 `IN_PROGRESS`，不得把部分 round-2 结果写成
  PASS，也不得提前进入 `C6-T01`。
- **起止时间与基线**：12 小时基线于 `2026-09-02 02:29:06 +08:00` 启动，续接外层进程
  于 `06:43:59` 启动，当前 launch child 于 `07:38:28` 启动；夸克 user0 cold-009
  于 `09:25:28.868` 发出，`09:27:07.963` 失败，`09:27:09.033` 完成首失败快照。
  被测基线为 `0b78ea9fd4fbfafe2ff1608a9f56466b3b3d0b0d`，分支和远端均为
  `feature/t57-r03-va-pro-capability-campaign` / `origin`，Git 身份为
  `OpenAI <openai@users.noreply.github.com>`。
- **执行环境**：MuMu `RD测试` 动态解析 index=1、API=32、型号 `22041211A`、ABI
  `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`、Android ID `398eea33120cd887`、
  boot `3e612f4b-d8e0-4333-b5e1-71697f5944b7`；resolved serial 只在快照中记录。
  夸克实际为 `com.quark.browser` version `10.10.5.1080`/code `1080`、base
  revision/APK SHA-256 `2cb38172da5da4aee03826da0feccb77ff0391ee7356f1562052ddc1fae9ecb3`、
  ABI `arm64-v8a`。
- **当前阻断首失败证据**：request=`e4f0057e1f6c400b9796eddfd76ee870`、
  operation=`e4f0057e1f6c400b9796eddfd76ee870-launch`、runner operation=
  `c4-r03-quark-u0-cold-9-e4f0057e1f`、attempt=1、retryBudget=0、
  `automaticRetryPerformed=false`、`retryable=false`；case 99,095 ms 后以
  `RD_ENVIRONMENT_RESOLUTION_BLOCKED: debug-command-result timeout` 返回 code 1。
  Host `com.warden.controlledsandbox.debug` PID 24780 的 ApplicationExitInfo 在
  `09:26:06.458` 明确为 reason `3 (LOW_MEMORY)`、PSS 117MB/RSS 184MB；logcat 同时
  记录 Host process death、WIN DEATH，`com.mumu.acc` 随后退出。完整原始 bundle、
  request/operation、日志、Activity/Window/Surface/process、截图、package/设备及
  transaction 快照见 `verification/catch-up/C4-R05/20260902-formal-second-low-memory-blocked.md`
  所引用的 cold-009 目录。
- **现场边界**：Guest stub `drawn=true`、Window 非空、Surface 非空，截图非黑但
  `debug-command-result` 缺失；这不能替代 request-scoped terminal result。无 FATAL/ANR
  证据，transaction 快照显示生命周期 ACTIVE、catalog/lastgood 存在，未见新的 staging/
  半发布 revision/孤儿实例。
- **根因及分类**：`ENVIRONMENT_BLOCKED`，即 MuMu/RD Host process-owner 的
  memory-pressure/LOW_MEMORY 终止使 CAS 结果采集失去 Host owner；没有证据把唯一根因
  归给 Quark SDK、SX UI 或某个 CAS 方法，细分内存因果保持待验证。VA/NBB 对照仍为
  `VActivityManagerService`/`ActivityStack`/`VirtualRuntime` 与
  `BProcessManagerService`/`ActivityStack`/`BActivityThread` 的 process owner/death/rebind
  边界；本次不添加包名分支、不扩大 cold 30s/hot 10s SLO、不引入重试。
- **实现摘要与变更文件**：本次新增 Host `LOW_MEMORY` 非阻断策略：保留每次原始失败证据，
  动态重启 MuMu/Host/Guest 并从精确坐标继续，事件次数不作为阻断条件；被测 12h timeout
  实现提交 `14a6f38bf6fa1132998227f2bb34cf813071cf35`，策略实现提交
  `cd7cdf3dc0fafc8a4fafbe67db1aacaf77d465fe`，本轮原始基线为 `0b78ea9f`。本回执新增
  `verification/catch-up/C4-R05/20260902-formal-second-low-memory-blocked.md`，更新
  `docs/review/KNOWN_ISSUES.yaml`（新增 `KI-R03-069`）和本账本；cold-009 原始证据
  目录被保留并随证据提交固化。
- **验收结果**：build、两轮 R04、round-1 R02/add/launch（500/500 terminal coordinate，
  保留一次允许的 LOW_MEMORY 恢复）均通过；round-2 R02 add gate 137/137 通过，launch
  在 216 条通过后于 Quark user0 cold-009 fail-closed，期望 500。到阻断点 round-2
  fixture 100/100、DingTalk 100/100、夸克 16 条通过后失败，红果/番茄小说未进入；
  C1/C2/C4/SX 回归及双用户 30 分钟短测未执行。具体命令、阶段 deadline、截图/哈希和
  失败文件索引见独立阻断回执。
- **重试/偏离/遗留风险**：round-1 与本次 round-2 均为 Host `LOW_MEMORY` 环境事件，
  attempt=1、retry budget=0、无隐藏的 case 自动重试；保存证据后的 Ctrl+C 是旧策略停止，
  现按新策略从精确坐标继续。每次事件仍单独记录 restart/rebootstrap 证据；普通非
  `LOW_MEMORY` 失败、恢复失败、坐标缺失和 phase deadline 仍 fail-closed。遗留风险是
  Host memory-pressure/process-owner、`com.mumu.acc` 联动退出及 Host death 后终态回收。
- **Known Issues 与下一任务**：新增 `KI-R03-069`，状态 `RECORDED`、
  `acceptance: NOT_FIXED`、`blocks_current_campaign: false`；既有 C4 高风险项和
  `KI-R03-060` 均未关闭。C5-T01..T04 仍为 `NOT_APPLICABLE`；当前任务继续为
  `C4-R05`，从 Quark user0/cold-009 续接，不前移 `C6-T01`。
- **提交/推送**：策略实现提交 `cd7cdf3dc0fafc8a4fafbe67db1aacaf77d465fe` 已推送；本回执
  文件、Known Issue、进度和选定原始失败 bundle 另形成独立证据/进度提交并推送。随后
  从本坐标续接矩阵；回执提交 SHA、`git ls-remote`、工作区干净状态在提交后回填并核验。

### C4-R05：非阻断 LOW_MEMORY 策略启用后的续接预检（2026-09-02 09:49-09:50）

- **首次预检失败证据**：`2026-09-02 09:49:30 +08:00` 首次运行
  `python scripts/verify-catch-up-continuation.py`，动态 MuMu `RD测试` 的 ADB 查询返回
  `RD_ENVIRONMENT_RESOLUTION_BLOCKED ... error: closed`；原始记录见
  `verification/catch-up/C4-R05/continuation-preflight-failure-20260902-094930.md`，未先行重试。
- **恢复与设备快照**：按动态实例名调用 MuMu Manager restart，manager 返回 0；boot 从
  `3e612f4b-d8e0-4333-b5e1-71697f5944b7` 变为
  `754f6e00-da46-426d-857e-4bce363cad10`，恢复记录见
  `verification/catch-up/C4-R05/continuation-preflight-recovery-20260902-094930/restart.json`。
- **预检结果**：恢复后 `python scripts/verify-catch-up-continuation.py --output
  verification/catch-up/C4-R05/continuation-preflight-nonblocking-20260902.json` PASS；
  分支为 `feature/t57-r03-va-pro-capability-campaign`，本地/远端 HEAD 均为
  `196e34f7a8d0887e6255d967ee00e9aa05ceb013`，静态执行 serial 扫描无 unexpected 项。
- **续接坐标**：当前任务仍为 `C4-R05 / IN_PROGRESS`，后续从已保存的 Quark `user0 /
  cold-009` 精确坐标启动；Host `LOW_MEMORY` 继续按只记录、动态恢复、不按次数阻断策略处理。

### C4-R05：Fanqie cold-004 非 LOW_MEMORY 启动超时阻断（2026-09-02 11:34-11:43）

- **最终状态**：`C4-R05 / BLOCKED`。本条不是 DONE 回执；C4 同步标记为 `BLOCKED`，不得进入
  `C6-T01`。阻断只针对本次真实非 `LOW_MEMORY` 终态失败；Host `LOW_MEMORY` 的无限次数记录、
  动态重启和续接策略仍然有效。
- **开始基线与环境**：本次续接使用 commit
  `29e4f72246e24f37430249c0a660ba23f2443249`，分支
  `feature/t57-r03-va-pro-capability-campaign`，本地与远端一致，Git 身份为
  `OpenAI <openai@users.noreply.github.com>`。动态解析 MuMu `RD测试` 为 API 32、model
  `22041211A`、ABI `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`、boot
  `754f6e00-da46-426d-857e-4bce363cad10`；设备快照位于 formal lane 的 `attempt-002/environment.json`。
- **动态样本**：番茄免费小说 `com.dragon.read`，7.1.9.32/71932，base-only、0 split、
  primary ABI `arm64-v8a`，revision/base hash
  `35493ffa0979bc1e10d5e177a0526c3d3d922af779dca4d8c91505c50757daf9`。
- **首失败证据**：round-2 retained/hot/recovery 的
  `fanqie/user-0/cold-004`，request
  `82ed7754c0af4a80a5ed1e3d290a64a0`，operation
  `82ed7754c0af4a80a5ed1e3d290a64a0-launch`，runner operation
  `c4-r03-fanqie-u0-cold-4-a2-82ed7754c`；request 于本地 `11:34:27.141` 开始，命令于
  `11:35:40.899` 失败，完整快照于 `11:35:50.879` 保存。case 的 `attempt=2` 是前一
  Quark Host LOW_MEMORY 之后的 durable-lane 观察，不是此坐标的隐藏重试；本坐标
  `retryBudget=0`、`automaticRetryPerformed=false`、`retryable=false`。
- **失败链路**：`GUEST_PREPARE` 完成 Provider 阶段（累计 `11561 ms`），在累计
  `11575 ms` 进入 `APPLICATION_ONCREATE`，未产生对应结束、Activity resumed 或
  `FIRST_FRAME_DRAWN`；`30233 ms` 回滚并以 `java.util.concurrent.TimeoutException` 返回
  `PREPARE_RETURN FAILED`。截图虽非黑，快照仍为 `resumed_guest_stub_count=0`，无目标
  Activity/Window/首帧；非黑截图不能替代 request-scoped terminal result。
- **分类与根因**：完整 `application-exit-info` 只有显式 `USER REQUESTED` 冷停记录，
  没有 Host `LOW_MEMORY`；无 FATAL、ANR 或进程死亡。分类为
  `LAUNCH_RESULT_NOT_PASS`，归入既有 `KI-R03-059`；根因保持“待验证”，证据只证明
  Guest prepare deadline 在 App `Application.onCreate` 未返回时到期，尚不能区分 App/SDK
  启动工作与 CAS Guest/broker 边界延迟。未修改生产代码、未放宽 cold 30 秒 SLO、未固定
  sleep、未增加隐藏重试。
- **矩阵进度**：round-1 已有 500/500 terminal coordinate（含已记录并恢复的 Host
  LOW_MEMORY）；round-2 add gate 137/137 通过；round-2 launch 当前 407 个唯一坐标，
  406 PASS、1 个非 PASS，已完成 fixture/DingTalk/夸克各 100/100，Fanqie 6/7（user0
  cold-004 失败），尚未完成剩余 Fanqie 及后续回归、30 分钟双用户短测。番茄小说该失败
  直接使 R05 formal gate 不通过，不能用夸克成功抵消。
- **VA/NBB 对照**：沿用 `C4_R03_LAUNCH_READINESS_WINDOW_DESIGN_20260824.md`、
  `C4_R05_FINAL_TWO_ROUND_EVIDENCE_BOUNDARY_DESIGN_20260827.md` 及已有
  `KI-R03-059` 映射，覆盖 CAS `GuestContentProviderFrameworkInterceptor`、
  `GuestRuntimeBrokerBridge`、`RuntimeActivityLaunchCoordinator`、`GuestLaunchGate` 与
  VA/NBB 的 `VActivityManagerService`/`ActivityStack`/`VirtualRuntime`、
  `BProcessManagerService`/`ActivityStack`/`BActivityThread` 的 process owner、token、
  Window identity、package 和 lifecycle 边界。当前证据不足以提出新的生产修复。
- **证据文件**：完整快照在
  `verification/catch-up/C4-R05/formal-two-round-20260902-timeout12h-v1/round-2-retained-hot-recovery/launch-matrix/attempt-002/attempts/fanqie/user-0/cold-004/first-failure-full/`；
  归纳报告为 `verification/catch-up/C4-R05/20260902-formal-fanqie-cold004-non-low-memory-timeout.md`。
  账本和 Known Issues 已记录本次失败，原始首失败未被后续观察覆盖。
- **恢复条件与下一任务**：完成 App/SDK 与 CAS Guest/broker 边界分类并形成有证据修复或
  明确外部归属；在 clean commit 上重新通过该坐标及剩余两轮门禁后，才可恢复
  `C4-R05`。下一任务字段保持 `C4-R05`，不前移 `C6-T01`。

### C4-R05：TimeoutException 性能异常有界续接（2026-09-02 12:00）

- **任务 ID / 当前状态**：`C4-R05 / IN_PROGRESS`。上一条 11:34-11:43 的 Fanqie
  `cold-004` 失败回执保留为历史首失败证据；本条依据用户最新决定恢复执行，不将历史
  失败改写为 PASS，也不继续保留已被覆盖的 BLOCKED 当前状态。
- **开始基线与提交**：续接策略开始前工作区干净，分支为
  `feature/t57-r03-va-pro-capability-campaign`，本地与 `origin` 一致；实现/设计/测试
  提交为 `9cb1bc3f04365564761d3689ee0b6782a475d8f3`，已推送。Git 身份为
  `OpenAI <openai@users.noreply.github.com>`。
- **用户批准的异常策略**：仅当失败 launch command result 明确包含
  `java.util.concurrent.TimeoutException` 时，按宿主机性能限制允许同一精确坐标最多 5
  次显式重试；每个 attempt 独立保存 request/operation、阶段时间、设备 boot、日志、
  Activity/Window/Surface、进程、transaction 和截图证据。Host `LOW_MEMORY` 继续按
  不限次数的动态 restart/rebootstrap 策略处理。泛化 `debug-command-result timeout`、
  phase/subprocess timeout、黑屏、窗口/首帧缺失及其他错误不进入该预算，仍立即
  fail-closed。
- **实现与 VA/NBB 边界**：新增
  `PERFORMANCE_TIMEOUT_RETRY_BUDGET = 5`、显式 command-result classifier、跨 durable
  lane 的 timeout 事件 seed 和同坐标续接；不修改生产 readiness SLO、首帧定义或商业
  样本门槛。沿用 R03/R05 既有 CAS GuestContentProvider/broker/readiness 与
  VA/NBB process owner、token、Window identity、package/lifecycle 对照；本次只改变
  宿主机性能异常的编排策略，不采纳固定 sleep、扩大 deadline 或包名分支。
- **验证结果**：`python -m unittest scripts/test_c4_r05_orchestrator.py -v`（12/12
  PASS）、`python -m py_compile tools/capability/run_c4_r03_low_memory_continuation.py
  tools/capability/run_c4_r05_rd.py scripts/test_c4_r05_orchestrator.py`（PASS）、
  `python scripts/check-c4-r05-orchestrator.py`（PASS）、`git diff --check`（PASS）。
  新增回归覆盖“明确 TimeoutException 可续接/后续观察替换”和“泛化 timeout 不可误判”。
- **续接预检证据**：策略提交后重新运行
  `python scripts/verify-catch-up-continuation.py` 两次均 PASS；设备快照仍为 MuMu
  `RD测试`、API 32、model `22041211A`、boot
  `754f6e00-da46-426d-857e-4bce363cad10`，未发现 unexpected hard-coded serial。原始预检
  结果和推送后复核分别保存在
  `verification/catch-up/C4-R05/continuation-preflight-timeout-policy-20260902.json`、
  `verification/catch-up/C4-R05/continuation-preflight-timeout-policy-pushed-20260902.json`、
  `verification/catch-up/C4-R05/continuation-preflight-timeout-policy-final-20260902.json`。
- **Known Issues**：`KI-R03-059` 仍为 `RECORDED`、`NOT_FIXED`、当前 formal gate 仍需
  通过；但当前这一类显式 TimeoutException 不再因首次出现立即阻断，改为最多 5 次
  有证据重试。原始 Fanqie 首失败报告新增策略附录，原始 full snapshot 保持不变。
- **当前验收坐标与下一步**：正式输出保持
  `verification/catch-up/C4-R05/formal-two-round-20260902-timeout12h-v1/`；从 round-2
  retained/hot/recovery 的 Fanqie `user0/cold-004` 精确坐标续接，优先观察 bounded
  timeout retry 事件，然后完成 round-2 剩余矩阵、回归和双用户短测。未达到全部门禁前
  不标记 DONE，不前移 `C6-T01`。
- **推送/远端验证**：实现提交已推送，进度与 Known Issues 将以独立回执提交推送；本
  条完成后必须再次执行续接预检并核对工作区干净、local/remote HEAD 一致，然后继续
  当前正式矩阵。

### C4-R05：用户要求调整回归批次并主动停止 C1（2026-09-02 13:22）

- **任务状态**：`C4-R05 / IN_PROGRESS`，没有任务完成或状态前移。当前 C1 子回归被
  用户明确要求停止，未生成当前 R05 C1 回执，因此不判定为 PASS/DONE。
- **中断事实**：父编排器在 C1 子任务运行期间收到用户 `Ctrl+C`；停止前原始证据保留
  在 `artifacts/capability-audit/catch-up-c1-t01/20260902T045201Z/`，并由
  `docs/review/C4_R05_C1_USER_INTERRUPT_20260902.md` 记录。该事件是编排调整，不是
  测试失败、设备阻断或自动重试。
- **用户指定的新顺序**：C1 Activity、C2 Window/Audio、C2 Device Audio、C4 CAS-only
  组成一个 `c1-c2-c4` 连续回归批次；SX F1-F5 保持独立的
  `sx-f1-f5-business` 批次。每个子门继续保留独立 summary、原始证据和 fail-closed
  判定，不能用批次合并降低验收门槛。
- **实现与验证**：编排器、单元测试和说明文件待提交为本次批次调整变更；在重新启动
  R05 前必须通过 `python -m unittest scripts/test_c4_r05_orchestrator.py`、静态检查、
  提交推送和续接预检。正式两轮输出不重跑，按现有 durable evidence 续接。

### C4-R05：重启前 clean-worktree 首次失败与证据提交（2026-09-02 13:27）

- **首次失败**：续接预检本身 PASS，但它更新了已跟踪的
  `verification/catch-up/C0-T01/continuation-preflight.json`；随后 R05 在证据捕获前因
  `worktree dirty` 立即退出。该事件发生在设备测试前，不构成产品/设备失败，也没有
  进行无依据重试。
- **证据**：原始差异和命令错误由
  `docs/review/C4_R05_RERUN_CLEAN_WORKTREE_PREFLIGHT_FAILURE_20260902.md` 记录；更新后
  的预检快照随本进度提交保存。
- **恢复条件**：提交并推送新的预检快照，确认工作区干净且 local/remote HEAD 一致，
  再从现有 formal durable evidence 续接 R05，并使用新的 `c1-c2-c4` → SX 批次。
