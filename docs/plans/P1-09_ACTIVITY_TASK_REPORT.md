# P1-09 首帧、任务栈与终态定向报告

日期：2026-09-09；状态：**完成（API35/36 `FIXTURE_PASS`）**；前置：P1-04。

## 先研究再改的结论

完整的 NBB/VA 方法对照、CAS 对应点、采用与排除理由见
[`P1-09_REFERENCE_DECISION.md`](P1-09_REFERENCE_DECISION.md)。本项核对的是
NBB `ActivityStack.startActivityLocked/startActivityInNewTaskLocked/startActivityInSourceTask`
及 `BActivityThread.finishActivity`，和 VA `ActivityStack.startActivityLocked/
startActivityFromSourceTask/realStartActivityLocked`。CAS 沿用已有的
`ActivityTaskLedger`、`RuntimeActivityLaunchCoordinator`、`GuestLaunchObservation`
和 `GuestLaunchGate` 合同：同 task 的子 Activity 关联根观测，终态先发布再移除
观测，且首帧必须同时具备窗口证据与 `FIRST_FRAME_DRAWN`。没有猜测或复制旧版
NBB 反射参数拼装。

本项没有修改产品运行时代码、启动 SLO、权限范围或重试预算。为将既有合同变为
可复核证据，只新增了 package-neutral 的可视/旋转 fixture、debug-only 的完整
首帧入口和定向 runner；所有正常 case 均为 `attempt=1`、
`retryBudget=0`、`automaticRetryPerformed=false`。

## 当前版本实测

| 环境 | 回执 | 首帧（冷 3 / 热 3，ms） | 任务/结果/旋转 | 结论 |
|---|---|---|---|---|
| API35 x86_64 / 4 KB | `out/verification/p1-09-api35-attempt1-20260909/run.json` | 4685, 3581, 3820 / 373, 311, 289 | 3 / 3 / 3 | PASS |
| API36 x86_64 / 4 KB | `out/verification/p1-09-api36-attempt5-20260909/run.json` | 2108, 2234, 2471 / 223, 283, 239 | 3 / 3 / 3 | PASS |

两条回执各有 15 个 case：

- 6 次可视 Activity（3 冷 + 3 热）均得到 `LAUNCH_PASS`、同次的
  request/session/generation、`windowEvidence=true` 和 `firstFrameDrawn=true`；
  每条首帧终态 requestId 都唯一，且没有 pending terminal。
- 3 次 package-neutral `singleTask` 流程均实际执行 `A -> Detail -> A` 的
  `onNewIntent`，随后取得 `BACK_COMPLETE`；不是以 resumed 或存活进程替代。
- 3 次 `startActivityForResult` 都收到
  `FRAMEWORK_PROBE_ACTIVITY_RESULT_PASS requestCode=701 resultCode=-1`。
- 3 次真实 configuration recreation 都收到 `P1_09_ROTATION_PASS`。runner 先读
  fixture 实际 ready 朝向、再反向旋转，避免把异步窗口方向切换误判为产品失败；
  每条回执确认已恢复原 `accelerometer_rotation` 与 `user_rotation`。
- runner 对 `LAUNCH_OBSERVATION_NOT_FOUND`、`LAUNCH_GATE_FAILED`、旧任务复用失败和
  Activity-result 失败均 fail-closed。首帧缺失、窗口证据缺失、generation 缺失或
  terminal pending 会直接失败。

API35 指纹为 `google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys`，API36 指纹为
`google/sdk_gphone64_x86_64/emu64xa:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys`；
精确 metadata、APK SHA-256、每例 request/session/generation、`am start -W` 和 logcat
均在对应 `run.json` 与同目录 logcat 中。两版使用相同 APK：Host
`7a50f10a0d6e2a552bc8cae069c7b123c844e1cfd0814bbcd1674f39c52f1780`，fixture
`0429df843ba73fbcd71efd85e76466a773888509f3c7a4740949d6802855d991`。

## 历史 KI 的本项边界

| KI | 本项依据 | 当前结论 |
|---|---|---|
| KI-R03-052 | 历史状态为 `FIXED`；当前 18 个可视首帧都以真实绘制门控。 | fixture 范围补证，不回用历史 PASS。 |
| KI-R03-053 | 所有可视 PASS 均要求 window + first frame；负向标记 fail-closed。 | 该 fixture 假通过路径未出现；历史商业矩阵仍独立保留。 |
| KI-R03-062 | 历史 Quark BrowserActivity 超时未在本项重现。 | 仍为 `RECORDED`；未延长 SLO、未重试、未加包特判，留待 P2-03。 |
| KI-R03-063 | request-bound durable terminal 在 18 个可视启动均可读取。 | 当前 fixture 补证通过；历史完整 R05 关闭条件不因此改变。 |
| KI-R03-065 | 每个 task/result case 等到 Back/result/finish 标记才进入下一例。 | fixture teardown 边界通过；历史正式 runner 问题仍为 `RECORDED`。 |

## 保留的首错

同日的 API36 尝试 1–4 保留在 `out/verification/`：先后揭示 package mutation 尚在
进行、runner 等待了错误的 task marker、fixture 跨独立三次运行保留静态计数，以及
旋转 runner 假设固定初始朝向。它们均未被重试覆盖：修正的是只读预热、case 边界或
fixture/runner 的验收逻辑，随后以新的独立 `attempt=1` 回执完成最终矩阵。

本结论只关闭 P1-09 的 AVD fixture 门禁，不等同于 Chrome、Quark、OEM 或 VA PRO
商业兼容性结论；大规模回归和 8 小时稳定性测试仍保持在计划尾部。
