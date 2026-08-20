# T57-R03-P4-FIX02-A01-FIX03-REPAIR02

## 任务结果

本次只修复 FIX03 REPAIR01 独立审核发现的验收 false-pass、API matrix 聚合和 physical evidence / frozen architecture 交付一致性问题；不启动 A02。

结果：PASS。

tested source commit：`31c8684bec0ddcd4f5263dd7c232910e03a48151`

tested source tree：`3ad178ecd54dcff3f8ee12302961ae0f5e620349`

## 验收闭环

Guest 只输出 lifecycle callback count/sequence、route/activity token 和 request timing/event。runner 从 runtime `ATMS_ACTIVITY_LAUNCH_REQUEST`、`ATMS_ACTIVITY_RECORD_MAPPING`、`dumpsys activity activities`、top-resumed ActivityRecord、物理 Host component 和 lifecycle log 计算 semantic assertions；缺字段、超时、缺 API 或 digest 不一致均 fail-closed。

API32、API35、API36 的 7 个 task mode 均通过 fixture lifecycle、system task、physical top / ActivityRecord stack、token mapping 和真实 Back 后栈 gate。Android 15/16 的 physical closing transition 只在 framework transition settle 后发出 `BACK_COMPLETE`，不改变 semantic 判定来源。

## API matrix

显式聚合器验证了同一 tested commit、clean worktree、canonical evidence SHA256、每个 API device `overall_pass=true`，并生成：

`overall_pass=true`, `observed_api_levels=[32,35,36]`, `failed_gates=[]`。

- API32：`device-api32-127.0.0.1_16416.json`，overall PASS。
- API35：`device-api35-emulator-5554.json`，overall PASS。
- API36：`device-api36-emulator-5554.json`，overall PASS。
- Final matrix：[final_matrix_evidence.json](../../artifacts/capability-audit/a01-acceptance/final-matrix-31c8684b/final_matrix_evidence.json)。

## 架构状态

当前采用 Architecture Revision `AR-02`，正式 supersede FIX01 的 `64×2=128` 旧表述：ordinary slots=64、isolated slots=16、每个 ordinary slot 的 bounded physical window pool=16、Host Activity components=`64×2×16=2048`、aliases=0；第 17 个同时存活 identity 返回 `PHYSICAL_ACTIVITY_IDENTITY_POOL_EXHAUSTED`。PackageParser/API32/API35/API36 和 canonical SHA 要求均记录在 [AR-02 architecture revision](T57_R03_P4_FIX02_A01_FIX03_REPAIR02_ARCHITECTURE_REVISION.md)。

本次没有重设计生产 Activity mapping / task ledger；生产侧仅补充 framework identity mapping 的可观察 evidence，semantic 结论仍由 runner 计算。

## 本地验证

- `python tools/capability/test_a01_semantic_runner_gate.py`：18 tests PASS。
- `python tools/static_android_compile.py`：PASS，输出包含 `PASS bounded physical Activity identity allocator`。
- `python tools/capability/run_local_capability_audit.py --all`：29 PASS / 13 KNOWN_ISSUE，`NEW_REGRESSION=0`；该诊断命令按既有 policy 返回非 0。
- `git diff --check`：PASS。
- Gradle `fixture-basic:assembleDebug` 与 static Android compile：PASS。

最终 review pack manifest 会同时保留 review-pack HEAD/TREE、tested source commit、API evidence SHA256 和 final matrix；不签发 VA Pro 结论。
