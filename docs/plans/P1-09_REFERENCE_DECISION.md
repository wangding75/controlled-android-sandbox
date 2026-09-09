# P1-09 参考实现与复现决策

日期：2026-09-09；前置：P1-04 已完成 API36 启动期 bootstrap fixture 验收。

## 参考方法与 CAS 对应点

| 参考实现 | 已核对的方法 | 可采用的执行约束 | CAS 对应实现 / 决策 |
|---|---|---|---|
| NewBlackbox | `ActivityStack.startActivityLocked`、`startActivityInNewTaskLocked`、`startActivityInSourceTask`（`Bcore/.../ActivityStack.java:96, 279, 293`） | 服务端以 `ActivityRecord` 保存 resultTo/requestCode，并通过代理 Intent 将同一任务的目标交给真实 AMS；`BActivityThread.finishActivity` 读取真实 Activity result 后调用 AMS finish（`BActivityThread.java:1100`）。 | `ActivityTaskLedger` 是 CAS 的权威 task/Activity record；`RuntimeActivityLaunchCoordinator` 在发起真实 Host Activity 前注册观测，并将同 task 的子 token 关联到根观测。采用记录/关联约束，不复制 NBB 的旧反射 AMS 参数拼装。 |
| VirtualApp OSS | `ActivityStack.startActivityLocked`、`startActivityFromSourceTask`、`realStartActivityLocked`（`VirtualApp/lib/.../ActivityStack.java:268, 448, 482`） | 维护 `ActivityRecord`/Task，source token、resultWho 和 requestCode 随真实 startActivity 传递；新任务与同 task 路径不能混用。 | `RuntimeActivityLaunchCoordinator` 根据 task key 将嵌套/跨进程子 Activity 接入同一 `GuestLaunchObservation`；`StubActivityBase.onNewIntent` 保留物理 token 身份并上报新路由 request。采用单一终态关联，拒绝仅以 resumed、进程存在或旧窗口认定成功。 |
| Android 平台合同 | Activity first frame 是实际已绘制窗口的事实；`singleTask` 重用、Back、configuration recreation 和 `onActivityResult` 必须由 ActivityThread/ATMS 发生。 | 首帧的 request/session/generation 必须来自同一次发起；结果与旋转不可用日志中的“已 resumed”替代。 | `GuestLaunchGate.evaluate` 已要求 window evidence 与 `FIRST_FRAME_DRAWN`；`GuestLaunchObservation` 已对 task-front 旧回调做 `awaitingNewIntent` 防护；终态先 `publishLaunchReadiness`、再删观测映射。P1-09 只为这些既有合同建立 fixture 和 runner 证据。 |

## 已定位的失败路径与最小复现

1. `KI-R03-052` 已标记 FIXED：首个 resume 不得提前制造 decor/hideForNow；P1-09 仍以真实首帧验证，不能回用历史 PASS。
2. `KI-R03-053` 要求拒绝“resumed 但未绘制”的假通过；runner 把没有 `FIRST_FRAME_DRAWN`、非黑屏/窗口证据或终态缺失作为失败。
3. `KI-R03-062` 是 Quark 嵌套 BrowserActivity 的历史超时，根因仍未分类；本任务只以包无关 fixture 验证 token/task 关联，不延长 SLO、不重试、不加入 Quark 特判。
4. `KI-R03-063` 的发布竞态已在源码以“发布终态后再移除观测”修复；runner 每次读取 request-bound durable result，验证不出现 `LAUNCH_OBSERVATION_NOT_FOUND`。
5. `KI-R03-065` 的 Host teardown barrier 仍需完整历史矩阵；本任务各 case 自行等待 fixture Back/finish 边界，不把上一 case 的旧窗口当作下一 case 证据。

## 修改边界

仅新增 package-neutral rotation fixture 与 P1-09 runner/receipt。fixture 通过 Android 真实 configuration recreate、singleTask nested handoff 和 framework result 回调产生 marker；runner 保留 `attempt=1`，不更改 launch deadline、不执行诊断重试、不强制成功、不增大权限或修改 CAS 运行时代码。任何首次动态失败均先用其 request/task/window/terminal receipt 分类，才允许后续产品代码改动。
