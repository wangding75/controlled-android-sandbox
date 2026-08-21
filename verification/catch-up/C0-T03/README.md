# C0-T03 MuMu RD 完整基线证据索引

## 结论

同一源码工作树、同一 `RD测试` 设备快照连续完成两轮 `t57_rd_full_regression.ps1`。两轮聚合结果均为 9/9 case `PASS`，分类一致；本证据只更新 `RD_BASELINE`，不外推为 Android Matrix、OEM、商业应用或 VA PRO Equivalent。

设备快照（两轮）：

- MuMu 实例：`RD测试`
- API / Android：32 / 12
- model：`22041211A`
- 动态解析 serial：`127.0.0.1:16416`
- boot ID：`7cac15ce-d76e-44ea-968b-959d91d03be7`
- 快照原文：`round-1-complete/*-device.json`、`round-2-complete/*-device.json`

## 两轮矩阵

| Case | round-1-complete | round-2-complete |
|---|---|---|
| ActivityResult | PASS | PASS |
| framework transport / cross-package / cross-ABI | PASS | PASS |
| JobWorkItem | PASS | PASS |
| FGS | PASS | PASS |
| ordinary process-death recovery | PASS | PASS |
| isolated service | PASS | PASS |
| clear/delete/reinstall lifecycle | PASS | PASS |
| cross-ABI process-death recovery | PASS | PASS |
| cross-ABI clear/delete/reinstall lifecycle | PASS | PASS |

## 原始证据

- `round-1-complete/`：完整第一轮的设备快照、结果 JSON、logcat 和 probe 专项日志。
- `round-2-complete/`：完整第二轮的设备快照、结果 JSON、logcat 和 probe 专项日志。
- `round-1-final/`：首轮重跑中 cross-ABI recovery 的瞬态失败原始日志；随后针对性重跑已通过。
- `targeted-transport/`：补充日志 tag 后 transport 针对性 PASS。
- `targeted-cross-abi-recovery/`：`GENERATION_NOT_ADVANCED` 后针对性 recovery PASS。
- `round-1/`：补建 `fixture-compat32` APK 前的 `PEER_GUEST_APK_MISSING` 原始失败。
- `acceptance-evidence/evidence-manifest.json`：由 `scripts/capture-acceptance-evidence.ps1` 生成的 Git、工作区和产物清单。
- `acceptance-evidence/artifact-hashes.txt`：由证据脚本生成的四枚 debug APK 与 bundle SHA-256；不在本文手工抄录。
- `acceptance-evidence/bundle-verify.txt`：Git bundle 验证原文。

## 失败分类与处理

1. `PEER_GUEST_APK_MISSING`：环境/测试前置缺口。按既有锁定 Gradle 参数补建 `:fixture-compat32:assembleDebug`，未修改生产代码。
2. `CROSS_PACKAGE_BROKER_ROUTE_MARKER_MISSING`：harness defect。设备 logcat 已有 cross-package/cross-ABI marker，但 probe 的 tag filter 未订阅对应 tag；最小修复补充 `CS_CROSS_PACKAGE_ROUTE` 与 `CS_CROSS_ABI_ROUTE`，针对性 probe 与两轮聚合均 PASS。
3. `GENERATION_NOT_ADVANCED:first=1 second=1`：首次 cross-ABI recovery 的瞬态设备/runtime 观察；同一设备快照下立即针对性重跑及两轮聚合均 PASS。保留原始日志，并映射现有 `KI-T57-015` 的“完整 process-death matrix 尚未证明”限制，不新增 PASS 声明。

## 诊断性 collect-all 限制

`python tools/capability/run_local_capability_audit.py --all` 按治理要求继续收集并返回非零；该工具是 diagnostic-only，原始汇总位于 `artifacts/capability-audit/all/20260821T055135Z/`。其中已分类的既有 Known Issue 与静态治理证据缺口不改变本任务的 RD 聚合验收；本轮未用诊断性 FAIL 冒充 runtime FAIL，也未在该工具运行期间修改源码。

