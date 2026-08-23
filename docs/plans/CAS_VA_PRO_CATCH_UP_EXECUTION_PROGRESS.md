# CAS 追平 VA PRO 执行进度

账本版本：1.1
更新时间：2026-08-23 21:14（Asia/Shanghai）
任务书：`docs/plans/CAS_VA_PRO_CATCH_UP_EXECUTION_TASK_BOOK_20260821.md`
任务分支：`feature/t57-r03-va-pro-capability-campaign`
远端：`origin`
当前阶段：`C4`（编号任务完成，阶段门禁待关）
当前任务：`C5-T01`（PENDING）
下一任务：`C5-T01`
最后完成任务：`C4-T05`

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
| C4 SX 迁移 | IN_PROGRESS | CAS-only，100 轮和任务书规定压力（不含显式 8 小时门槛） | - |
| C5 XH 支持 | PENDING | 原始 XH 通过，可选模块有决策 | - |
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
| C5-T01 | 原始 XH 产品能力契约 | PENDING | C2,C3 | - | - |
| C5-T02 | XH CAS Host/SDK 集成 | PENDING | C5-T01,C4-T02 | - | - |
| C5-T03 | 原始 XH/DingTalk 验收 | PENDING | C5-T02,C4 | - | - |
| C5-T04 | 可选 Xposed 模块验收 | PENDING | C3-T06,C5-T01 | - | - |
| C6-T01 | API33-37 回归 | PENDING | C4,C5 | - | - |
| C6-T02 | ARM/跨宽度/16KB | PENDING | C3-T03,C6-T01 | - | - |
| C6-T03 | Android Matrix 发布门禁 | PENDING | C6-T01,C6-T02 | - | - |
| C7-T01 | OEM 优先级与代表设备 | PENDING | C6 | - | - |
| C7-T02 | 逐厂商通用/SX/XH 适配 | PENDING | C7-T01 | - | - |
| C7-T03 | VA PRO scope 与商业发布总验收 | PENDING | C7-T02 | - | - |

## 4. 阻断项

当前阻断：无。原 `aapt2` 供应链缺口已按官方 Google Maven 字节比对修复，严格 Gradle 与
M5-T19.1-U 供应链门均通过；`KI-R03-BUILD-001` 与 `KI-R03-BUILD-002` 均已 `FIXED`，
两者 `blocks_current_campaign: false`。C0-T02 的锁定构建已连续两轮成功并完成哈希一致性核验。
外部设备、ARM/16KB 环境和可选 ART/Xposed 产品决策在对应任务中确认，不得提前据此跳过 C0-C5 主线。

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
