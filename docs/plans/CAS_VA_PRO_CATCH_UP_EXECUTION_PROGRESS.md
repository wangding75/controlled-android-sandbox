# CAS 追平 VA PRO 执行进度

账本版本：1.0
更新时间：2026-08-21
任务书：`docs/plans/CAS_VA_PRO_CATCH_UP_EXECUTION_TASK_BOOK_20260821.md`
任务分支：`feature/t57-r03-va-pro-capability-campaign`
远端：`origin`
当前阶段：`C0`
下一任务：`C0-T01`
最后完成任务：`BOOTSTRAP-DOCS`

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
| C0-T01 | 固化任务续接与证据协议 | PENDING | BOOTSTRAP-DOCS | - | - |
| C0-T02 | 当前 HEAD 可复现构建基线 | PENDING | C0-T01 | - | - |
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

当前无任务执行阻断。外部设备、ARM/16KB 环境和可选 ART/Xposed 产品决策在对应任务中确认，
不得提前据此跳过 C0-C5 主线。

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
