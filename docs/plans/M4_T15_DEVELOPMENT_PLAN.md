# M4-T15 Activity 与 Task 虚拟化补强开发计划

## 1. 基线与状态规则

- 正式起点：`68a93bc9983d3a8fe8929ce992d4f56649a8af19`（M4-T14）。
- `a22ab564a3b2515219b59e43846b645252ad8606` 仅作为 M4-T15 阶段性实现，不作为正式完成基线。
- 每个开发批次必须：专项测试 PASS、全仓回归 PASS、合并 `main`、生成完整源码 ZIP、Git bundle、增量 Patch、验证日志与 SHA-256。
- 最终状态只允许 `PASS` 或 `BLOCKED`。
- 本阶段不把源码证据换算成真实 APK 兼容率；模拟器和真机证据保持为 0，直至设备阶段实际执行。

## 2. 冻结范围

### LaunchMode

- `standard`
- `singleTop`
- `singleTask`
- `singleInstance`
- `singleInstancePerTask`

### Intent Flags

- `FLAG_ACTIVITY_NEW_TASK`
- `FLAG_ACTIVITY_CLEAR_TOP`
- `FLAG_ACTIVITY_CLEAR_TASK`
- `FLAG_ACTIVITY_NEW_DOCUMENT`
- `FLAG_ACTIVITY_MULTIPLE_TASK`
- `FLAG_ACTIVITY_REORDER_TO_FRONT`
- `FLAG_ACTIVITY_NO_HISTORY`
- `FLAG_ACTIVITY_FORWARD_RESULT`

### Result 链路

- `startActivityForResult`
- Result Who、Request Code、Result Code、Result Intent
- Activity Result API 兼容入口
- Intent Sender Activity Result

### Task 与恢复

- 虚拟 Task ID、Affinity、Document Mode
- Task 栈恢复
- Activity 销毁与重建
- Configuration Change
- Process death 后恢复
- APK Revision 更新后的任务清理
- `finishAffinity`
- `finishAndRemoveTask`
- `moveTaskToBack`
- Running Tasks 与 Recent Tasks 虚拟结果

## 3. 开发批次

### B1：LaunchMode、Intent Flag 与 Task 核心策略

状态：**PASS，提交 `44cb141` 已合入 `main`，备份已生成。**

交付：

- 补齐并固定五种 LaunchMode 行为矩阵。
- 校验 `MULTIPLE_TASK`、`CLEAR_TASK`、`NEW_DOCUMENT` 的合法组合。
- 增加 Document Mode 与文档任务身份。
- 实现 `finishAffinity`、`finishAndRemoveTask`、`moveTaskToBack`。
- Task 状态绑定 APK Revision，并提供旧 Revision 清理。
- 扩展 checkpoint，兼容已有 schema。

退出条件：专项矩阵测试、checkpoint 兼容测试和全仓门禁通过。

### B2：Result、Activity 重建与恢复

状态：**PASS，提交 `e2716eb` 已合入 `main`，标签 `m4-t15-b2-source-pass`，备份已生成。**

交付：

- 完整验证 Result Who、Request Code、Result Code、Result Intent。
- 增加 Activity Result 注册键到 request-code 的有界映射。
- 增加 Intent Sender Activity Result 路由。
- 完善 Configuration Change、销毁重建和进程恢复后的 Result 所有权重写。
- 持久化失败采用事务性回滚或显式拒绝，不保留无 checkpoint 的已接受状态。

退出条件：Result/恢复专项测试、损坏/容量门禁和全仓门禁通过。

### B3：Framework 查询入口与最终收口

状态：**PASS；随正式 M4-T15 提交合入 `main`，并生成 B3 与最终 M4-T15 两套备份。**

交付：

- Guest Framework 调用通过 Runtime Broker 获取 Running/Recent Task 虚拟结果。
- 增加 Task 前移、后移、删除和 finish 系列的 Framework 入口。
- Android 版本差异由反射投影层集中处理，禁止宿主任务结果回落。
- 完成 VA/NBB 对比、能力矩阵、未完成项和阶段报告。

退出条件：Framework 拦截测试、身份隔离测试、全仓门禁及可复现打包通过。

## 4. 固定质量门禁

- 新增跨模块业务契约优先 typed Parcelable/AIDL，不新增大业务 `Bundle`。
- 所有 Task 操作绑定 Virtual User、Package、Session、Generation 和 APK Revision。
- 所有持久化输入具备版本、CRC、容量上限和损坏隔离。
- 所有一次性路由、Binder 权限和 Result 传输在 Broker 重启后 fail closed。
- APK Revision 变化、实例删除和 Guest 进程死亡必须清理对应状态。
- 不允许 Running/Recent Task 查询回落到宿主真实任务。

## 5. 备份命名

每个批次生成：

- `controlled-sandbox-m4-t15-bN-<commit>-source.zip`
- `controlled-sandbox-m4-t15-bN-<commit>.git.bundle`
- `controlled-sandbox-m4-t15-bN-<commit>.patch`
- `M4-T15-BN-development-report-<commit>.md`
- `controlled-sandbox-m4-t15-bN-<commit>-verification.txt`
- `controlled-sandbox-m4-t15-bN-<commit>-SHA256SUMS.txt`

最终完成后另生成不带批次后缀的正式 M4-T15 完整备份。
