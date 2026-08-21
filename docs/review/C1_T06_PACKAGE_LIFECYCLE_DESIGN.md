# C1-T06 — Package 生命周期与 revision 设备验收设计

## 目标

在 `RD测试` API 32 上以 package-neutral fixture 验证包生命周期的完整闭环：base/split
导入、revision 切换、升级后回滚、克隆用户、identity reset、clear/delete/reinstall，以及
查询/解析和 permission/AppOps snapshot 的用户隔离。该任务只声明 `RD_BASELINE`，不外推
API 33+、ARM/16KB、OEM 或 VA PRO 等价性。

## DISCOVER / CLASSIFY

2026-08-21 在开始基线 `23ca42d45a1dab1f803248bb0c8f34efa2668fee` 上完成：

- `run_local_capability_audit.py --all`：42 gates，PASS=31、KNOWN_ISSUE=11、
  NEW_REGRESSION=0；`package-lifecycle` 为 PASS，`apk-revision-binding` 和
  `split-install` 的失败分别归入既有 `KI-R03-026`、`KI-R03-029`。
- `check-package-lifecycle-transaction.py`、PackageManager query/resolve、virtual package
  state、package-service boundary 检查通过。
- `check-split-install-sessions.py` 的四个 token 匹配与当前实现边界漂移，属于既有
  `KI-R03-029` 的治理/harness 缺口，不将静态检查失败改名为 runtime PASS。
- `KI-T57-016` 在开始时仍记录升级/回滚/克隆/identity-reset 缺少当前 campaign 的设备闭环；
  本任务完成后仅补齐 RD API32 证据，Known Issue 改为保留跨 API/OEM/VA PRO 范围。

未发现新的 runtime defect；不新增 Known Issue。

## DESIGN

1. 复用现有 Binder-owned package authority 和 `DebugCommandActivity`，不在生产路径添加
   包名特判或第二状态源。
2. 新增 `run_c1_t06_rd.py`：每次以 `RD测试` 实例名动态解析设备，记录设备快照、commit、
   APK hash 和逐步原始结果；先安装 lifecycle v1，再执行 clone/launch/virtual-state，
   安装 v2 后执行 revision switch/launch/rollback/reset；再以 dynamic-feature base+feature
   执行 split import/launch；最后执行双用户 clear/delete/reinstall 和失败安装 session
   回滚检查。
3. 将 debug-only 状态检查封装为 package-neutral campaign command，只验证 authority 返回的
   record、split metadata、query/resolve、permission 和 AppOps snapshot；fixture 仍通过
   正常 Guest API 使用这些结果。
4. 修正 `check-split-install-sessions.py` 的架构匹配，使其检查当前 typed Binder/Guest
   verifier 边界；保留 `KI-R03-029` 作为未覆盖的跨环境/设备证据风险。
5. 设备失败先按 environment、harness、runtime 分类并修复后重跑；任一未分类 runtime
   failure 不得进入 DONE。

## LOCAL_VERIFY / RD_CAMPAIGN

实现后运行：

- `python scripts/check-package-lifecycle-transaction.py`
- `python scripts/check-split-install-sessions.py`
- `python scripts/check-package-query-resolve.py`
- `python scripts/check-virtual-package-state.py`
- `python tools/static_android_compile.py`
- `python tools/capability/validate_campaign_infra.py`
- `python tools/capability/run_c1_t06_rd.py --instance 'RD测试'`
- `python scripts/generate-sbom.py --check`
- `git diff --check`

成功条件是上述静态/本地门禁通过，RD receipt 中 base/split、revision、clone/reset、
clear/delete/reinstall、failure rollback、query/resolve、permission/AppOps 和双用户隔离
全部为 PASS；其余 Known Issues 保持显式分类。
