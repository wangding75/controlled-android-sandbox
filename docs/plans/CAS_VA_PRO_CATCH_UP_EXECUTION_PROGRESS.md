# CAS 追平 VA PRO 执行进度

账本版本：1.0
更新时间：2026-08-21
任务书：`docs/plans/CAS_VA_PRO_CATCH_UP_EXECUTION_TASK_BOOK_20260821.md`
任务分支：`feature/t57-r03-va-pro-capability-campaign`
远端：`origin`
当前阶段：`C0`
下一任务：`C0-T02`（解除构建供应链阻断后重试）
最后完成任务：`C0-T01`

## 1. 使用规则

1. 本文件是唯一任务进度账本，只追加回执和更新任务状态，不在此处改写任务定义。
2. 开始任务前先核对本表、最后回执、Git 历史和远端；一次只能有一个 `IN_PROGRESS`。
3. 完成任务后按任务书第 2.2 节执行两提交和推送协议。
4. `DONE` 表示实现提交、回执提交均已推送并验证；否则只能是 `IN_PROGRESS` 或 `BLOCKED`。
5. 若任务书必须变更，先提交变更理由、影响和迁移规则，再修改任务书版本；不得静默改变验收标准。

## 2. 阶段状态

| 阶段 | 状态 | 阶段门禁 | 完成回执 |
|---|---|---|---|
| C0 事实源与 RD 基线 | PENDING | 两轮 RD 一致、事实源无冲突、可跨环境续接 | - |
| C1 组件/包/进程 | PENDING | 双用户、50 轮、压力、8 小时 soak | - |
| C2 系统服务/F2-F5 | PENDING | SX/XH 调用面 L3，P0/P1 无 NOT_PROVEN | - |
| C3 Native/ABI/隔离 | PENDING | trusted/hostile 闭环，条件项有决策 | - |
| C4 SX 迁移 | PENDING | CAS-only，100 轮和 8 小时业务 soak | - |
| C5 XH 支持 | PENDING | 原始 XH 通过，可选模块有决策 | - |
| C6 API/ABI 矩阵 | PENDING | 声明组合均有 Android Matrix 证据 | - |
| C7 OEM/发布 | PENDING | 商业矩阵与 VA PRO scope 总验收 | - |

## 3. 任务状态

| 任务 ID | 任务名称 | 状态 | 依赖 | 实现提交 | 回执位置 |
|---|---|---|---|---|---|
| C0-T01 | 固化任务续接与证据协议 | DONE | BOOTSTRAP-DOCS | `602da7e65a145e1fa277723bd0a97f2abc473c15` | §5 C0-T01 |
| C0-T02 | 当前 HEAD 可复现构建基线 | BLOCKED | C0-T01 | `d0800113e5679dfebae97627e646e5cc88cfd6b8` | §5 C0-T02 |
| C0-T03 | MuMu RD 完整基线 | PENDING | C0-T02 | - | - |
| C0-T04 | 统一能力事实源与 VA PRO corpus | PENDING | C0-T03 | - | - |
| C1-T01 | Activity/Application 与任务栈 | PENDING | C0 | - | - |
| C1-T02 | Service/FGS/Job | PENDING | C1-T01 | - | - |
| C1-T03 | Broadcast | PENDING | C1-T01 | - | - |
| C1-T04 | ContentProvider | PENDING | C1-T01 | - | - |
| C1-T05 | PendingIntent/Alarm/Notification holder | PENDING | C1-T02,C1-T03 | - | - |
| C1-T06 | Package 生命周期 | PENDING | C1-T04,C1-T05 | - | - |
| C1-T07 | Process/ABI/Recovery | PENDING | C1-T01..T06 | - | - |
| C2-T01 | SX/XH 系统服务方法清单 | PENDING | C1 | - | - |
| C2-T02 | PMS/Permission/AppOps/Attribution | PENDING | C2-T01 | - | - |
| C2-T03 | Location | PENDING | C2-T01,C2-T02 | - | - |
| C2-T04 | Camera1/Camera2 | PENDING | C2-T01,C2-T02 | - | - |
| C2-T05 | 调度与交互服务 | PENDING | C2-T01,C1 | - | - |
| C2-T06 | 设备/网络/媒体服务 | PENDING | C2-T01,C2-T02 | - | - |
| C2-T07 | Biometric 与长尾收敛 | PENDING | C2-T02..T06 | - | - |
| C3-T01 | Native 绕过与兼容 corpus | PENDING | C1,C2-T01 | - | - |
| C3-T02 | 文件/proc/network/FD | PENDING | C3-T01 | - | - |
| C3-T03 | 四 ABI/16KB/native media | PENDING | C3-T01,C2-T04 | - | - |
| C3-T04 | Hostile native 隔离 | PENDING | C3-T01,C3-T02 | - | - |
| C3-T05 | seccomp/user-notify 决策 | PENDING | C3-T04 | - | - |
| C3-T06 | ART/Xposed Extension 决策 | PENDING | C2,C3-T04 | - | - |
| C4-T01 | SX 依赖与功能冻结 | PENDING | C1,C2,C3-T01..T04 | - | - |
| C4-T02 | SX CAS SDK adapter | PENDING | C4-T01 | - | - |
| C4-T03 | SX 数据迁移 | PENDING | C4-T01,C4-T02 | - | - |
| C4-T04 | 移除 BlackBox/Pine/Xposed runtime | PENDING | C4-T02,C4-T03 | - | - |
| C4-T05 | SX F1-F5/DingTalk/长稳 | PENDING | C4-T04 | - | - |
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

当前阻断：`C0-T02` 的离线设备测试 APK 构建在 Gradle 严格依赖验证阶段拒绝
`com.android.tools.build:aapt2:8.11.1-12782657` 的 POM，仓库
`gradle/verification-metadata.xml` 缺少其校验条目。恢复条件：受信维护者完成产物来源审查并补充已验证
checksum/signature，或恢复与现有 metadata 匹配的缓存产物，然后重新运行任务锁定构建命令；禁止绕过依赖验证。
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
