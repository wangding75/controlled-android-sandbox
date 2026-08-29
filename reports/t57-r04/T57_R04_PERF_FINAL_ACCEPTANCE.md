# T57-R04 PERF-TEMP — MuMu 临时专项整体验收

## TASK

`T57-R04-PERF-TEMP-PLAN-20260828`（PERF-T00～PERF-T16）整体验收，覆盖构建、静态结构 Gate、MuMu 动态导入/启动、失败注入/恢复和功能回归。

## START_HEAD

`9e9599d1`（验收开始时的代码头）

## FINAL_HEAD

`9e9599d1`（本报告为工作树新增证据，尚未提交）

## RESULT

`BLOCKED — 未通过整体验收`

本轮已执行真实 MuMu 动态验收，但关键 Gate 在 fail-fast 规则下被阻断。不能将历史结果、单 fixture 冒烟或“设备稍后完成”的结果记为整体验收通过。

## ENVIRONMENT

- MuMu root：`D:\install\Netease\MuMu`
- 实例：`RD测试` / `MuMuPlayer-12.0-1`
- ADB：`127.0.0.1:16416`，状态 `device`
- Android：API 32，模型 `V2241A`，ABI 列表 `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`
- 运行器：`tools/capability/run_c4_r03_rd.py`、`tools/capability/run_c4_r04_rd.py`

## SAMPLE DISCOVERY

动态发现成功，四个真实样本均存在，之前“缺少番茄”的判断已纠正：

| 样本 | 包名 | 版本 | 基础 APK |
| --- | --- | --- | ---: |
| 夸克 | `com.quark.browser` | 10.10.5.1080 / 1080 | 169,654,003 B |
| 红果免费短剧 | `com.phoenix.read` | 7.0.5.33 / 70533 | 116,439,278 B |
| 番茄免费小说 | `com.dragon.read` | 7.1.9.32 / 71932 | 124,857,929 B |
| 钉钉 | `com.alibaba.android.rimet` | 7.8.10 / 1178 | 运行时发现 |

证据：[targets.json](../../build/t57-r04-final-acceptance/r03-loop1-u0/targets.json)、[environment.json](../../build/t57-r04-final-acceptance/r03-loop1-u0/environment.json)。

## VERIFICATION EXECUTED

以下检查通过：

- `python tools/static_android_compile.py`：通过（静态自测和结构检查通过，只有既有 warning）。
- `gradlew.bat :app:assembleDebug :sandbox-runtime:compileDebugJavaWithJavac --offline`：`BUILD SUCCESSFUL`。
- `git diff --check`：通过。
- `python -m py_compile tools/capability/run_c4_r03_rd.py tools/capability/run_c4_r04_rd.py tools/capability/c4_r04_fail_closed.py scripts/mumu_instance.py`：通过。
- C4-R04 failure-injection：5 个场景均按预期失败并被正确分类，证据状态 `PASS`。
- C4-R04 recovery：无自动重试、恢复路径和动态首帧/Window/Surface 检查通过，证据状态 `PASS`。

R04 证据：[failure-injection runner-summary.json](../../build/t57-r04-final-acceptance/r04-failure-injection/runner-summary.json)、[recovery runner-summary.json](../../build/t57-r04-final-acceptance/r04-recovery/runner-summary.json)。

## DYNAMIC C4-R03 RESULTS

运行参数：`--loops 1 --users 0`，每个坐标 `attempt=1`、`retryBudget=0`；发现首个失败即停止。

| 坐标 | 观测结果 | 结论 |
| --- | --- | --- |
| fixture cold | 首帧 readiness `12,735 ms`；Window/Surface/非黑屏均通过 | PASS（仅样本坐标） |
| fixture hot | `808 ms`；Window/Surface/非黑屏均通过 | PASS（仅样本坐标） |
| 钉钉 cold | `19,729 ms`；Window/Surface/非黑屏均通过 | PASS（仅样本坐标） |
| 钉钉 hot | `4,508 ms`；Window/Surface/非黑屏均通过 | PASS（仅样本坐标） |
| 夸克 cold（首次） | 运行器 `LAUNCH_RESULT_NOT_PASS`，约 `80,366 ms`；结果为 `LAUNCH_GATE_FAILED` | FAIL，首失败证据保留 |
| 夸克 cold（人工重启续跑） | 最终 `LAUNCH_PASS`，但 readiness `35,596 ms`，超过 cold `30,000 ms` Gate | FAIL：`READINESS_SLO_EXCEEDED` |
| 红果导入 | 两次 `import-only` 均在收集窗口超时；设备晚到结果为 `PACKAGE_OPERATION_STAGE_TIMEOUT_CATALOG`，`CATALOG_WRITE` 约 `36,708 ms` | FAIL：导入 Gate 未在 deadline 内完成 |
| 番茄导入（单独补跑） | 运行器 60 秒内超时；日志显示 `PACKAGE_LOOKUP` 约 `106.7 s` 后才开始导入，内部导入约 `40.881 s`，晚到设备结果为 `PASS`，并记录 `MIXED_ELF_MACHINE` anomaly | FAIL：验收窗口超时，不能按晚到结果计 PASS |

R03 证据：[首轮 summary](../../build/t57-r04-final-acceptance/r03-loop1-u0/c4-r03-summary.json)、[夸克续跑 summary](../../build/t57-r04-final-acceptance/r03-resume1-u0/c4-r03-summary.json)、[红果首轮 setup](../../build/t57-r04-final-acceptance/r03-readers-loop1-u0/setup/hongguo/user-0.json)、[番茄单样本 summary](../../build/t57-r04-final-acceptance/r03-fanqie-loop1-u0/c4-r03-summary.json)。

## FUNCTIONAL REGRESSION

`tools/device/t57_rd_full_regression.ps1` 在首个 `RD-09-framework-activity-result-transport` 探针处超时；清理涉及进程后单独重跑仍失败，错误为：

`LAUNCH_COMMAND_FAILED: status=LAUNCH_ACCEPTED`

设备日志随后出现真实夹具的 `FIRST_FRAME_DRAWN` 和 `FRAMEWORK_PROBE_ACTIVITY_RESULT_PASS`，因此失败点是 debug `launch-component` 命令仍把产品的异步 `LAUNCH_ACCEPTED` 当作终态，没有完成独立 readiness observation。按功能回归契约，这仍是失败，不能用后续 marker 覆盖。

本轮没有继续执行后续 8 个探针；fail-fast 的首失败和重跑证据已保留：[activity-result retry observation](../../build/t57-r04-final-acceptance/full-regression-activity-result-retry/observation.json)。

## STRUCTURAL PERFORMANCE GATES

源码/自测层面的以下 Gate 通过，但都不能替代真实设备上的 P95/Max：

- 启动关键路径固定 sleep：`STATIC_PASS (scoped)`。
- Hot launch 完整 APK SHA、同 revision copy/native extraction：`STATIC_PASS`。
- 非 `lib/<abi>/*.so` 的 ZIP 内容流读取：`STATIC_PASS`。
- 单请求统一 deadline、重复 ServiceManager 初始化收敛：`STATIC_PASS`。
- Package-universe 批量快照：`STATIC_PASS (controlled)`。
- 黑屏不能作为启动成功：`STATIC_PASS (semantic)`。

动态数值 Gate 未闭合：本轮没有满足每场景 10/30/50 次，也没有产生可信的 median/P90/P95/P99/stddev；因此不能宣称 Hot ≤1.5 s、Cold product launch ≤3～5 s、Cold first frame ≤7 s、Same-revision import P95 ≤1 s，亦未完成 N=1/10/50/100 扩展。

## COMPARISON / DEFERRED

尚未完成 Native Android、VA、SX、CAS Before/After 的同 APK 同 MuMu 四方对照，也未执行 S01～S11 全状态矩阵、I01～I07 全导入矩阵或 Perfetto/System Trace 二次验证。历史 artifact 仅作为背景，不用于覆盖本轮失败。

## ROOT_CAUSE / REQUIRED FOLLOW-UP

1. 修复 debug `launch-component` 的 accepted→readiness observation 桥接，同时保持产品 `launch()` 不同步等待首帧的语义。
2. 调查 Catalog 锁/序列化/全局写入长尾；不能通过单纯增大 timeout 或增加 retry 绕过 `CATALOG_WRITE` 超时。
3. 调查夸克 cold readiness 的 35.6 秒长尾和首次 `LAUNCH_GATE_FAILED`，完成统一 deadline、Guest prepare 与 Activity handoff 的阶段级定位。
4. 在上述问题修复后，从干净 MuMu 状态重跑：先 1 loop 全样本，再 10/30/50 loop，补齐统计、N 扩展、四方对照和 Perfetto 证据。

## GIT_STATUS

代码头为 `9e9599d1`；验收新增本报告，工作树另有用户已有的未跟踪目录 `artifacts/t57-r04/`，未删除或覆盖。
