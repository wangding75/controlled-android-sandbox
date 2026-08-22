# C2-T07 交接记录（暂停）

交接时间：2026-08-23（Asia/Shanghai）  
任务：C2-T07「Biometric 与长尾服务收敛」  
分支：`feature/t57-r03-va-pro-capability-campaign`  
状态：`IN_PROGRESS`，本次尚未完成验收、提交或推送。

## 当前结论

本轮只选择并执行了 C2-T07，没有进入 C2-T08 或 C2 阶段门禁。Host 静态编译、自测和 Android 设备构建均通过；RD API32 追赶活动尚未通过。最近一次失败是 fixture 的 Shortcut 请求在设备端返回：

`SecurityException: Shortcut package name mismatch`

因此不能把当前结果记为 DONE，也不能提交任务回执或推送。

## 已完成的工作

- 按任务书、进度账本、`CAPABILITY_CAMPAIGN_WORKFLOW.md` 和 `COMMIT_IDENTITY_POLICY.md` 完成了启动前检查；当前基线 HEAD 与 `origin/feature/t57-r03-va-pro-capability-campaign` 均为 `9bb551cd4ba13062ae36fc9744c55229e7e88870`。
- 完成 `DISCOVER`：collect-all 诊断未发现 NEW_REGRESSION；既有诊断问题已分类为 `KI-R03-041`。
- 增加 C2-T07 package-neutral fixture、动态 MuMu `RD测试` runner、静态 checker、设计文档，以及 DebugCommand extras/manifest 接线。
- 扩展静态 Android API stubs，覆盖 User、Launcher、Shortcut、AppWidget、UsageStats 和 Settings 所需签名。
- fixture 已修复两处确定性问题：
  - `UserManager.getUserName()` 的 API32 权限拒绝改为显式 `KNOWN_LIMITATION`，不暴露 Host 值并继续执行；
  - Shortcut Intent 补充 `ACTION_VIEW`，解决设备端 `intent's action must be set`。
- `python scripts/check-c2-t07-application-environment.py`：PASS。
- `python tools/static_android_compile.py`：PASS，完整 Host self-test 集合通过。
- `scripts/build-device-test-apks.ps1 -Online`：PASS，766 tasks；Host/fixture/companion32 构建通过。
- runner 还需要 `fixture-compat32`，已单独执行：
  ` .\gradlew.bat --no-daemon --no-build-cache --no-parallel --stacktrace :fixture-compat32:assembleDebug`：PASS。
- fixture/Host/compat32 定向 Gradle 构建：PASS。

## RD 运行记录

设备均由实例名动态解析，不应把下面的 serial 固化进代码。当前设备快照：MuMu `RD测试`，API 32，设备 `22041211A`，ABI `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`，boot-id `773adc6f-e0aa-4997-a0ee-481a7773a10d`。

| 目录 | 结果 | 原因 |
|---|---|---|
| `20260822T201246Z` | `BLOCKED_ENV` | runner 需要的 compat32 APK 尚未生成；已通过单独 Gradle 任务修复环境 |
| `20260822T201326Z` | `FAIL` | `UserManager.getUserName()` 权限拒绝；已改为明确 `KNOWN_LIMITATION` |
| `20260822T202252Z` | `BLOCKED_ENV` | build wrapper 的锁定 artifact 列表不产出 compat32；已单独构建 |
| `20260822T202335Z` | `FAIL` | Shortcut Intent 缺少 action；已补 `ACTION_VIEW` |
| `20260822T202426Z` | `FAIL` | `Shortcut package name mismatch`，这是当前待修复点 |

最近一次结构化回执：`verification/catch-up/C2-T07/c2-t07-rd-summary.json`，当前 `rd_result=FAIL`、`targeted_result=UNVERIFIED`、`regression_result=UNVERIFIED`。最近一次原始日志和详情位于：

`artifacts/capability-audit/catch-up-c2-t07/20260822T202426Z/`

最近日志已证明 User 和 Launcher marker，随后在 Shortcut add 阶段失败；尚未证明 Widget、UsageStats、Settings/Content、Storage、long-tail matrix、Host identity guard、campaign pass、跨用户和死亡恢复。

## 当前工作树与提交状态

当前 HEAD 仍为 `9bb551cd4ba13062ae36fc9744c55229e7e88870`，C2-T07 没有 implementation commit，也没有 progress receipt commit，也没有 push。

本次任务相关的修改/新增主要包括：

- `fixture-basic/src/main/java/com/warden/controlledsandbox/fixture/C2T07ApplicationEnvironmentActivity.java`
- `fixture-basic/src/main/AndroidManifest.xml`
- `app/src/debug/java/com/warden/controlledsandbox/DebugCommandActivity.java`
- `tools/capability/run_c2_t07_rd.py`
- `scripts/check-c2-t07-application-environment.py`
- `tools/static_android_compile.py`
- `docs/review/C2_T07_APPLICATION_ENVIRONMENT_LONG_TAIL_DESIGN.md`
- `docs/review/KNOWN_ISSUES.yaml`（已记录 `KI-R03-041`）
- `docs/plans/CAS_VA_PRO_CATCH_UP_EXECUTION_PROGRESS.md`（C2-T07 已置为 `IN_PROGRESS`）
- `verification/catch-up/C2-T07/c2-t07-rd-summary.json`

工作树中另有启动前已存在的 C0-T01 preflight 修改和 C1-GATE 诊断/重试证据；恢复时必须继续保留，不能 reset、checkout 或误 stage。当前尚未按任务书更新 `CAPABILITY_REGISTRY.yaml`、`VA_PRO_COMPATIBILITY_CORPUS.yaml`，也尚未追加最终回执。

## 恢复时的首要动作

1. 先检查 ShortcutInfo 的包身份来源，不要直接放宽 runner。优先在 fixture 中使用 Guest application context 构造 `ShortcutInfo.Builder`，并使用显式 Guest `ComponentName`；同时记录 `ShortcutInfo.getPackage()`、activity package 与 `getPackageName()/getOpPackageName()` 的摘要，确认是 fixture 构造器身份、Guest Context 投影还是生产 Shortcut hook 的问题。
2. 若发现是生产 Guest identity projection/hook 缺陷，修生产路径并补 self-test；若 API32 当前形态确实不能安全投影，则按设计记录 `NOT_SUPPORTED`/`KNOWN_LIMITATION`，但必须保证不会回退 Host，且调整 runner/验收只接受有 owner 的显式负向结果。
3. 重新执行定向构建和：
   `python tools/capability/run_c2_t07_rd.py --instance 'RD测试' --loops 5 --clone-loops 3`
4. 只有 user0、clone、death replacement 全部通过后，补跑 C1 regression，更新 capability registry 与 VA PRO corpus（保持 `va_pro_equivalent: NOT_PROVEN`），再执行 `git diff --check` 和任务验收。
5. 按任务书创建两个提交：先 implementation，再 progress receipt；非 force push 两次，并用 `git ls-remote --heads origin feature/t57-r03-va-pro-capability-campaign` 验证远端 HEAD。

## 不可改变的边界

- 只继续 C2-T07 一个任务；不提前执行 C2 gate/C2-T08。
- RD 设备端点继续通过 `RD测试` 实例名动态解析，runner 不得写入固定 serial/port。
- 证据成熟度只能写 `RD_BASELINE`/`RD_API32_L3`；不得宣称 API33+、OEM/HAL、SX/XH、商业应用或 VA PRO 等价。
- 失败优先修复；只有确需人工恢复外部设备/权限且安全范围内无法继续时才记录 BLOCKED。
