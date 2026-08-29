# T57-R04 PERF-T16 — MuMu 动态验收补充

## 结论

MuMu 12（实例 `RD测试`）上的 fixture 导入与启动烟测通过。结果覆盖的是同一模拟器上的小样本动态回归，不替代专项计划要求的 A01–A08、四方对照和 50 次 Final Gate。

## 环境

| 项目 | 值 |
| --- | --- |
| MuMu 实例 | `RD测试` / `MuMuPlayer-12.0-1` |
| ADB | `127.0.0.1:16416`（由 `scripts/mumu_instance.py` 动态解析） |
| Android | API 32 / Android 12 |
| 设备 | vivo V2241A / x86_64 |
| fixture | `com.warden.controlledsandbox.fixture`，user 0 |
| 被测 APK | `app/build/outputs/apk/debug/app-debug.apk`，已重编译并安装 |

## 启动结果

冷启动定义为停止 Guest 后重新发起启动；不是 MuMu 整机重启。每次均通过独立 `observeLaunch` 等待首帧终态，未把 `LAUNCH_ACCEPTED` 误判为成功。

| 模式 | 次数 | 首帧就绪（ms） | 中位数（ms） | 最大（ms） | 结果 |
| --- | ---: | --- | ---: | ---: | --- |
| Cold | 4 | 6656 / 6962 / 7233 / 13797 | 7097.5 | 13797 | 4/4 `LAUNCH_PASS` |
| Hot | 4 | 547 / 716 / 1027 / 1183 | 871.5 | 1183 | 4/4 `LAUNCH_PASS` |

所有通过样本的 `activityCreated`、`activityResumed`、`windowEvidence`、`firstFrameDrawn` 均为 true；`attempt=1`、`retryBudget=0`、未发生自动重试。按当前烟测阈值，Cold ≤30 s、Hot ≤10 s 均满足。样本量为 4，未计算或宣称 P95。

首次冷启动曾遇到一次 `PACKAGE_OPERATION_STAGE_TIMEOUT_ENSURE_INSTANCE`（服务冷启动实测约 10.9 s，阈值 10 s）；随后预热完成后的 4 次冷启动全部通过，故该次作为环境预热异常单独记录。

## 导入结果

`import-only` 在同一 MuMu fixture 上通过：操作 `SUCCEEDED`，导入 trace 5.728 s，外层命令约 10.526 s，APK 读写各 2,743,494 bytes，native 提取 892,456 bytes，3 个 native 库。

该次 trace 的 `shaBytesRead=1,365,185,354` 不是 APK 被读了 1.36 GB：当前 catalog 有 5 个已发布包，普通 catalog 保存会按设计对所有已发布 artifact 做完整 digest 校验；设备上这些 revision 合计约 1.2 GB。因此这是全量 catalog 完整性校验的可观测成本，不应记作 fixture APK 复制量，也没有在本轮跳过该安全校验。

## 证据

- [benchmark-summary.json](../../build/t57-r04-mumu-fixture-smoke/benchmark-summary.json)
- [fixed-import.json](../../build/t57-r04-mumu-fixture-smoke/fixed-import.json)
- [fixed-cold-repeats.json](../../build/t57-r04-mumu-fixture-smoke/fixed-cold-repeats.json)
- [fixed-hot-repeats.json](../../build/t57-r04-mumu-fixture-smoke/fixed-hot-repeats.json)
- [parsed-stages-v2.json](../../build/t57-r04-mumu-fixture-smoke/parsed-stages-v2.json)
- [final-logcat.txt](../../build/t57-r04-mumu-fixture-smoke/final-logcat.txt)
- [final-screenshot.png](../../build/t57-r04-mumu-fixture-smoke/final-screenshot.png)

## 代码验收

Debug 命令桥已在 `perf-T16-fix-debug-launch-observation` 提交中改为：产品 `launch()` 返回 `LAUNCH_ACCEPTED` 后，测试侧通过独立 readiness observation 等待 `LAUNCH_PASS/LAUNCH_FAILED`。`git diff --check`、`python tools/static_android_compile.py` 和此前的 `:app:assembleDebug` 均通过。

## 限制

官方 C4-R03 runner 在进入 case 前仍要求动态发现 `fanqie` 等商业样本；MuMu 当前缺少该样本，因此本轮使用 fixture 专用命令完成可复现烟测，未伪造商业样本或完整矩阵通过结论。
