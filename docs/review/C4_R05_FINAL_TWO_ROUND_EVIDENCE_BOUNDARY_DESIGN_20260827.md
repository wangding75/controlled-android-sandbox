# C4-R05 双轮正式关门与证据边界设计

日期：2026-08-27  
任务：`C4-R05`  
基线：`feature/t57-r03-va-pro-capability-campaign` @ `4fb42b736d921888ccdb4d82efb367a085787290`

## 1. 已确认事实与分类

本设计在新的正式关门运行前固化三个已确认问题；不把跨提交的历史观察拼接为 PASS。

1. 旧正式续跑的首个非环境失败是 DingTalk user0 `hot-012`：request
   `dd0706448a294a22a0263995dd738b46`、attempt 3（独立人工续接）、retry budget 0，
   `FIRST_FRAME_DRAWN` 已出现但 readiness 为 10,123 ms，超过 10 秒 hot SLO。首失败的
   logcat、Activity/Window/Surface、进程、事务、截图与 frame hash 已保留。该问题属于 CAS
   通用 Guest process recovery/readiness，不是 SX UI 或 App 专属失败。
2. `fa057b1a` 保留 hot resume 的既有 runtime state；`4fb42b73` 在 Guest Binder death 后用
   package/user/process/generation fence 预热已验证的 prepared spec。定向复测 cold-012 为
   26,518 ms、hot-012 为 633 ms，均在 SLO 内，但仍只属于定向观察，不能替代完整双轮。
3. R03 collector 每例导出整个未清理 logcat buffer，717 份 `logcat.txt` 累计约 58.98 GB；
   R05 regression 还会把新结果写回历史 `C4-T04/C4-T05` tracked summary，导致 R01 的
   `SUPERSEDED` 保护被覆盖。两者均为验收编排/证据边界缺陷，不是 runtime PASS/FAIL。

当前用户指令要求同一 clean commit 上执行两轮正式验收。任务书 1.4 顶部的“一轮 25 次”修订
与 R05 本文及根因方案的“两轮”门槛矛盾；本轮以用户最新指令为批准依据，恢复为两轮、每轮
每 target/user 冷 25 + 热 25。C0-C7 后的整体门禁仍为两轮 loops=50，不被本次替代。

## 2. VA/NBB 对照

设计前完整读取并固定以下参考输入：

- NBB `BProcessManagerService.startProcessLocked`、`ActivityStack.startActivityProcess`、
  `BActivityThread.bindApplication`：Activity 启动先取得显式 ProcessRecord，再绑定 Application；
  进程/slot 是独立 owner，不在 Activity 层伪造存活。
- VA `VActivityManagerService.startProcessIfNeedLocked/processDead`、
  `ActivityStack.startActivityProcess/processDied`、`VirtualRuntime.crash`：Binder death 清理旧
  process/activity，下一次启动重新取得 ProcessRecord；真实 process exit 不被 Activity marker
  代替。
- 参考文件 SHA-256 与 C4-R01 mapping 一致：NBB `BProcessManagerService.java`
  `7cfb3c53...66f98`、`BActivityThread.java` `12cf3899...40f5`、`ActivityStack.java`
  `bad3643c...a6b41f`；VA `VActivityManagerService.java` `5c8539cb...9204`、
  `ActivityStack.java` `93dba3e7...24e73`、`VirtualRuntime.java` `4abee24c...840e2`。

CAS 差异是一个 hot 请求仍需在 10 秒内完成 translated Guest prepare/bind；仅在请求到达后同步重建
会吃掉几乎全部 hot SLO。因此采纳 broker-owned、generation-fenced 的预热，但不采纳上游全局单例、
旧私有 API 或无分类 restart。预热不携带新的 Activity requestId，不改变当前 operation，不回写
失败终态；任何失败只记 `GUEST_RECOVERY_PREWARM_FAILED`，后续真实 launch 仍 fail-closed。

## 3. 最小实现边界

1. R05 `c4-stage-reduced` 改为严格两轮、每轮 loops=25；第一轮 clean-install/cold，第二轮
   retained-state/hot/recovery。添加门禁、R04 合同和 500-row 首帧矩阵在两轮分别执行。
2. R03 每个 child lane 开始清理一次 logcat，并在每个 case 完成取证后清理；单次导出再加
   20,000 行上限。首次失败先完成 full snapshot，之后才清理，保证失败原始证据不被覆盖。
3. C2-T05/T06、C4-T04/T05 runner 增加显式 `--verification-dir`；R05 将其 summary 写入自身
   regression evidence，禁止覆盖历史任务 summary。历史 C4-T05 仍保持 `SUPERSEDED`、
   `historical_only=true`、`usable_for_c4_closure=false`。
4. R05 启动前记录 `git status --porcelain` 并要求 clean；正式输出只改变 evidence，不改变被测
   commit。大体积 `.txt/.png/.log` 保留在本地 raw lane，由 artifact index 固化 bytes/SHA-256；
   JSON summary、case contract、command record 与 index 进入 Git。
5. C4-T05 的 F1-F5 marker 只证明调用面，不作为启动成功依据；R05 的两轮 R03 matrix 才是
   `FIRST_FRAME_DRAWN`、Window、Surface、截图和 SLO 的权威结果。

## 4. 不采纳

- 不把历史 attempt-001/002/003/004 跨 commit 合并为正式 PASS。
- 不扩大 cold/hot SLO，不用 fixed sleep、marker、Stub 或 Guest 进程存在替代首帧。
- 不删除约 61 GB 历史 raw evidence；只通过 ignore + artifact index 保持 Git 工作区可管理。
- 不把 LOW_MEMORY 以外的 readiness failure 自动续跑；LOW_MEMORY 仍必须由 host-scoped
  `ApplicationExitInfo` 明确证明，并在独立目录/新 requestId 中继续。

## 5. 对应验证

- `python -m py_compile` 覆盖所有修改的 runner。
- `python scripts/check-c4-r05-orchestrator.py`、R04 fail-closed tests、静态 Android compile、
  Activity/task virtualization 检查。
- runner 参数负测：R05 stage scope 只接受 rounds=2/loops=25；overall scope 只接受
  rounds=2/loops=50。
- 新 clean commit 上执行两轮完整 R05；随后 C1/C2/C4/SX 回归和 user0/user1 各 15 分钟且
  至少 50 cycle 压力。任何首次非 LOW_MEMORY 失败立即停止并保存 full snapshot。

