# T57-R02 VA/NBB/VA Pro 追赶优化验收记录

日期：2026-08-15
分支：`feature/t57-runtime-deep-review-observability`
验证设备：`127.0.0.1:16416`，API 32 RD 测试模拟器

## 结论范围

本记录是本轮源码优化和 RD 模拟器验收的证据，不把 VA/NBB 的源码或 README 历史记录直接当作 CAS 的运行证据。API 33–36 和 OEM 兼容性按任务约定留待后续运行环境补强；因此“VA Pro 水平”在本记录中指已验证的 API32 RD 沙箱执行链和生命周期范围，不宣称所有 Android 版本、OEM 和 native 恶意代码路径都已等价。

## 本轮优化

- Activity/Service 继续使用 framework-owned 的 `ActivityThread`、`LoadedApk`、`AppComponentFactory` 和 Service record/token 路径；补齐 Service stop/unbind/recycle 的清理边界。
- Provider transport 增加单次跨进程 `bulkInsert`，与 `applyBatch`、Cursor extras/notification URI 共用有界 wire contract。
- Manifest/PMS 增加 ApplicationInfo、ComponentInfo、ProviderInfo/PathPermission、`<queries>`、跨包可见性和 permission-holder 查询投影。
- 普通 guest process slot 从 32 扩展为 64，使用集中式 `ProcessSlotContract` 约束分配、Stub 名称和 manifest 映射。
- Native 文件边界补齐 `rename/renameat`、`unlink/unlinkat`、`mkdir/mkdirat`、`rmdir`、`opendir` 的 libc PLT/GOT 重定向；保留 direct syscall 不可由 PLT hook 覆盖的边界。

## 五 Gate 证据

| Gate | RD/API32 结果 | 证据 |
|---|---|---|
| 1. Component Runtime | PASS | Framework transport probe 覆盖 Activity、Service bind/start/stop、Receiver、Provider、Application/LoadedApk；无 fatal/ANR。 |
| 2. Virtual Android World | PASS | package universe、`<queries>`/跨包 resolve、remote processName/slot 路由 marker 通过；普通 slot contract 为 64。 |
| 3. IPC Fidelity | PASS | PendingIntent/IIntentSender、ServiceConnection、Provider bulkInsert、Provider batch、remote route 均通过真实 Binder transport probe。 |
| 4. Native Fidelity | PASS（已测 RD ABI 范围） | native loader/path/network/identity 生产接线已编译并由 Quark guest 稳定运行验证；本轮增加文件变更入口。PLT/GOT 方案不等同于拦截 guest 自行发起的 direct syscall。 |
| 5. Lifecycle Recovery | PASS | clear/delete/reinstall transaction、process death/generation recovery 通过；Quark 300 秒稳定性 30/30 ticks 通过。 |

## 可复核命令与结果

```text
gradlew :app:assembleDebug :fixture-basic:assembleDebug
         :sandbox-companion32:assembleDebug :fixture-compat32:assembleDebug --offline
BUILD SUCCESSFUL

t57_rd_framework_transport_probe.ps1 -Serial 127.0.0.1:16416
RESULT: PASS case=RD-06-framework-transport-probe api=32

t57_rd_lifecycle_probe.ps1 -Serial 127.0.0.1:16416
RESULT: PASS case=RD-06-clear-delete-reinstall-transaction api=32

t57_rd_recovery_probe.ps1 -Serial 127.0.0.1:16416
RESULT: PASS case=RD-07-process-death-generation-recovery api=32

t57_quark_stability_probe.ps1 -Serial 127.0.0.1:16416
  -DurationSeconds 300 -IntervalSeconds 10 -ProcessSlot 60
RESULT: PASS；30/30，alive=True，errors=0，PID=23357，elapsedSeconds=293
```

完整 Quark 监控日志：`build/t57-rd-evidence/quark-5min-abi-gated/monitor.log`。Framework、生命周期和 recovery 的 logcat/evidence 文件位于 `build/t57-rd-evidence/`。

## 未纳入本轮结论的边界

- API33–36、OEM、真实 VA Pro 商业版二进制的同机 A/B 测试尚未在当前环境执行。
- Native direct syscall、未覆盖的 linker 私有 ABI、isolated process 的真实 Android UID slot 行为不能仅凭本轮 PLT/GOT 或 Quark 稳定性推出已完全等价。
- 聚合 `t57_rd_full_regression.ps1` 中依赖外部 fixture command 的路径仍需由 RD 测试编排提供命令；本轮各个可执行的 framework、lifecycle、recovery 和 Quark 门禁已单独通过。
