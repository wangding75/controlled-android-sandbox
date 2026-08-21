# CAS 追平 VA PRO 执行进度

账本版本：1.0
更新时间：2026-08-21 17:16（Asia/Shanghai）
任务书：`docs/plans/CAS_VA_PRO_CATCH_UP_EXECUTION_TASK_BOOK_20260821.md`
任务分支：`feature/t57-r03-va-pro-capability-campaign`
远端：`origin`
当前阶段：`C1`
下一任务：`C1-T02`
最后完成任务：`C1-T01`

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
| C0-T02 | 当前 HEAD 可复现构建基线 | DONE | C0-T01 | `beef510b2993a38b010dd8c09a51a497247fd783` | §5 C0-T02 final recovery |
| C0-T03 | MuMu RD 完整基线 | DONE | C0-T02 | `222211efebad3c4adfc66804d32e5c3e60e8f3dd` | §5 C0-T03 |
| C0-T04 | 统一能力事实源与 VA PRO corpus | DONE | C0-T03 | `8bb4470c6054b728f64e83529a22f7e2222f6a7d` | §5 C0-T04 |
| C1-T01 | Activity/Application 与任务栈 | DONE | C0 | `87d96611` | §5 C1-T01 |
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
