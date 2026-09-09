# P1-14 WebView 多进程与 GMS 边界验收报告

**状态：** 已完成（2026-09-09；API36 x86_64/4 KiB AVD `FIXTURE_PASS`）

## 决策与实现边界

修改前已完成 [参考源码决策单](P1-14_REFERENCE_DECISION.md)。NBB
`BActivityThread.handleBindApplication` 在 Guest Application 前设置 WebView data-directory
suffix；NBB `AppSystemEnv.isOpenPackage` 仅提供 provider 包开放白名单，`GmsCore.isSupportGms`
只证明包可解析。VA OSS `VClientImpl.bindApplicationNoCheck` 在创建 Application、安装
provider 后调用 `onCreate`。这些实现均不证明 GMS API、账号、Chrome 浏览器或 WebView
renderer 恢复成功。

CAS 现有 `GuestRuntimeEnvironment.prepare` 已先安装 `WebViewProfileManager`，再配置
`GuestWebViewProviderServiceBridge`；API36 的 `GuestContext.appendApi36WebViewAssets` 将
受控 provider APK 集添加到 Guest-owned AssetManager。P1-14 没有改变这条生产路径、
provider allowlist、identity、权限、超时或 GMS 策略。仅增加 debug-command 和离线 fixture
探针，以实际 WebView 回执验证既有边界。

初次执行保留了两类验证工具/fixture 发现：Windows 默认 GBK 解码无法读取 UTF-8 logcat，
以及 WebView 默认 DOM storage 关闭、脚本 file-input click 被 Chromium 正确拒绝为无用户
activation。runner 固定 UTF-8 解码；fixture 显式启用 DOM storage，并让 runner 在 JS 首回执
后对可见按钮发送一次真实触摸，按钮再触发实际 `input[type=file]`。这不是重试或隐藏首错：
最初的 r2–r6 receipts 保留，最终 r7 使用新的单次请求坐标。

## API36 定向矩阵

设备为 `google/sdk_gphone64_x86_64/emu64xa:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys`、
API36、x86_64、4 KiB；Host APK SHA-256 为
`a136cea2897a0f0f2c61064fdf7da8db543efd2da01d17f68ced93afde2e787e`，fixture SHA-256 为
`35230e85bdc9a6c254cbfc3b2a226eb3c33da399b845b6ee1c0171e9bcc2969f`。

| 单次请求 | 用户 | 观察到的结果 |
| --- | --- | --- |
| `u0-write` | 0 | API36 provider asset `chrome_100_percent.pak+com.google.android.webview+` 可由 Guest AssetManager 读取；JS 回传、真实触摸、file chooser callback、页面导航、cookie/localStorage `u0_p114` 均 PASS。 |
| `u1-write` | 1 | 相同路径以独立 profile 写入 `u1_p114`，未观察到用户 0 token。 |
| `u0-read-renderer` | 0 | 用户 0 在用户 1 后读回 `u0_p114`；`chrome://crash` 引发实际 isolated renderer SIGSEGV，`onRenderProcessGone` 到达，replacement WebView 返回 JS `recovered=ok`；callback 之后没有 `FixtureApplication` 新建记录。 |

最终 receipt 为
`out/verification/p1-14-api36-webview-boundary-r7-20260909/run.json`，每个 case 的
`attempt=1`、`retryBudget=0`、`automaticRetryPerformed=false`。逐请求完整 logcat 分别在
同目录的 `u0-write.logcat.txt`、`u1-write.logcat.txt` 和 `u0-read-renderer.logcat.txt`。

## GMS 和浏览器范围

`GmsCompatibilityBoundary` 的 `BASIC_BOUNDARY` 仅代表受控包可见/allowlisted Binder
边界一致。P1-14 不登录账号、不请求 token、不调用 Play services API，也不把 WebView
renderer 成功推断为 Chrome 自身浏览器引擎成功。因此 GMS 在 receipt 中明确为 `DEFERRED`，
完整运行时/账号验证仍仅由条件任务 P2-10 承接；Chrome/Trichrome ARM64 浏览闭环仍属 P2-02。

## 最终校验与未覆盖范围

* `:app:assembleDebug` 与 `:fixture-basic:assembleDebug`：PASS。
* `python -m py_compile tools/verification/run_p1_14_webview_boundary.py`：PASS。
* `git diff --check`：PASS。
* 未启动大规模回归或 8 小时稳定性测试；小米/ARM64/OEM、真实商业网页和 GMS 登录保持后续阶段范围。
