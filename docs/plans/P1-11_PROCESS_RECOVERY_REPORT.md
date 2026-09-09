# P1-11 · 进程 owner、失败清理与短恢复

状态：`FIXTURE_PASS`（2026-09-09，API 35 / API 36 x86_64 AVD）。这不是小米真机、LMK 或长稳结论。

## 参考决策

决策和逐方法对照见 `P1-11_REFERENCE_DECISION.md`：NBB `BProcessManagerService` 的
ProcessRecord/client/death 清理与 VA `VActivityManagerService` 的 attach/death 记录，分别映射到
CAS `RuntimeGuestConnectionPool` death recipient、`SessionRegistry` 状态/代次和
`GuestRecoveryPrewarmCoordinator`。保留 Binder death 与 generation fencing；未将 SIGKILL 解释为 LMK。

## 实现与验证夹具

- 增加显式 Java crash、Native abort、prepare-failure、late-connection 夹具；故障服务先结束自身，避免恢复代次被同一故障服务反复启动。
- `fault-probe` 显式维持 `RuntimeBrokerService` 的 `START_STICKY` owner，避免单次调试客户端 unbind 在 death callback 前销毁预热执行器。
- late callback 用 75 ms bind timeout + 300 ms 延迟服务验证 epoch fence；高 slot 用干净 Broker 后分别精确占位 62、63。
- API 35/36 无窗口 AVD 关闭系统 Java crash dialog；Broker SIGKILL 使用 userdebug rooted adbd，均在收据中记录。

## 最终动态证据

| AVD | 收据 | 结果 |
| --- | --- | --- |
| API 35 | `out/verification/p1-11-api35-final-contract-fix-attempt1-20260909/run.json` | PASS |
| API 36 | `out/verification/p1-11-api36-final-logcat-fix-attempt1-20260909/run.json` | PASS |

两个收据均包含：Java crash ×3、native abort ×3（generation 1 → 2）、Broker SIGKILL/recreate ×3、真实 `P111_PREPARE_FAILURE` ×3、late callback timeout/不复活 ×3，及普通 slot 62、63 精确落位和 Service 启动。原始失败和显式恢复独立保存。

先前失败的首次收据仍保留于 `out/verification/p1-11-*attempt1-20260909`，记录了调试夹具的进程归属、headless crash dialog、Broker 持有和高槽 reset 修复过程；未删除或重写。

## 未覆盖边界

完整 64 槽耗尽、LMK/LOW_MEMORY、大规模回归、8 小时稳定性和小米真机均未运行，按任务书留在后续/P2-90/P2-91。Activity 多进程实际调度的独立缺口移交 P1-13，不以本次显式 Service owner 验证替代。
