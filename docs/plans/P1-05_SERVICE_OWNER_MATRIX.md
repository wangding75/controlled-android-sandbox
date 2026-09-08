# P1-05 Service Owner / 返回语义矩阵

状态：`FIXTURE_PASS`（2026-09-08）。API32/35/36 已完成含跨包拒绝的设备级回执；P1-05 验收关闭。未运行大规模回归或 8 小时稳定性测试。

## 已落实的规则

| 输入 / 查询面 | Owner / 返回 | 证据 |
|---|---|---|
| 虚拟已声明 Service（显式、包约束或可解析隐式） | virtual PMS 优先；`resolveService`、`queryIntentServices`、`getServiceInfo` 与 `Context` 路由使用同一虚拟结果与 processName。 | `PackageManagerQueryVariantsSelfTest`；API32/35/36 原生 fixture 的 `P1_05_VIRTUAL_PM_PASS`。 |
| 合格外部 system/updated-system Service | 仅在显式组件或 package 约束下，且 PMS 实际结果为 enabled、exported、package 一致的 system owner 时可见/路由；虚拟包同名时绝不回退物理包。 | `PackageManagerQueryVariantsSelfTest` 的 system-owner、same-name virtual-first、bare implicit 无 host 枚举、ordinary host user absent 分支。 |
| 普通 host user Service / 伪造 metadata | 不可见、不绑定；绝不制造 `ResolveInfo`、Binder、权限、签名或回调。 | `PackageManagerInvocationHandler` owner filter 与 P1-05 reference decision。 |
| 显式虚拟 peer 非导出或需要权限的 Service | `SecurityException`（`VIRTUAL_SERVICE_NOT_EXPORTED` / `VIRTUAL_SERVICE_PERMISSION_DENIED`）；不会转换成 host fallback。 | `GuestIntentResolver.rejectExplicitVirtualServiceAccess`；API32/35/36 CAS `P1_05_PEER_DENIED_PASS`。 |
| 合法缺失 Service | `bindService=false`、`startService=null`、`stopService=false`；同时有 `CS_GUEST_SERVICE_ABSENT` 与有界 resolve diagnostic，但无 `NO_GUEST_SERVICE_MATCH` 未捕获异常。 | API36 CAS `P1_05_MISSING_RETURNS_PASS`；API35/36 native 同 marker。 |
| callback executor / null / process death | callback 由真实 `GuestServiceConnectionRelay` 交付；无合成 Binder。顺序 probe 分别绑定普通、null-binding、独立 dying process 服务，再解绑。 | API36 CAS and API35 native: connected、null-binding、disconnect、cleanup markers。 |

## 动态回执

| 坐标 | 原生 | CAS | 结论 |
|---|---|---|---|
| API36 x86_64, 4 KiB, `google/sdk_gphone64_x86_64/emu64xa:16/...` | PASS | PASS | [`run.json`](../../out/verification/p1-05-service-api36-fourth-20260908/run.json)；真实 `onServiceConnected`、`onNullBinding`、`onServiceDisconnected`（dying process）和 cleanup 都出现。 |
| API35 x86_64, 4 KiB, `google/sdk_gphone64_x86_64/emu64xa:15/...` | PASS | PASS | [`run.json`](../../out/verification/p1-05-service-api35-after-sharedlib-fix-20260908/run.json)；P1-03 将可选 `SharedLibraryInfo.getCertDigests()` 改为反射读取后 import 与完整 Service fixture 均通过。此前首错保留在 `p1-05-service-api35-20260908`。 |
| API36 x86_64, 4 KiB，peer owner | PASS | PASS | [`run.json`](../../out/verification/p1-05-service-api36-peer-owner-20260908/run.json)；CAS 同时记录 `P1_05_PEER_DENIED_PASS nonExported=security permission=security`。 |
| API35 x86_64, 4 KiB，peer owner | PASS | PASS | [`run.json`](../../out/verification/p1-05-service-api35-peer-owner-20260908/run.json)；同一 peer 导入顺序与 CAS 拒绝 marker 均通过。 |
| API32 x86_64, 4 KiB, default image | PASS | PASS | [`run.json`](../../out/verification/p1-05-service-api32-after-wifiscanner-fix-20260908/run.json)；官方 ZIP SHA-1 校验后创建 AVD；首错 `p1-05-service-api32-peer-owner-20260908` 的 API32 应用域 `wifiscanner` 不可见已按决策最小修复。 |

早期 API36 回执 `p1-05-service-api36-first-20260908`（probe Activity 非导出）和 `...-second...`（缺失 `getServiceInfo` 被错误升级）及 `...-third...`（同槽并发异 Service token 复用）均保留为首错证据；最终 API36 PASS 使用修复后的独立新坐标，未对失败坐标执行诊断重试。

## 移交项

1. 同槽并发异 Service token 隔离已保留首错并移交 P1-11 的进程 owner/失败清理范围；它不改变本任务已验收的顺序 bind/unbind/null-binding/process-death 合同。
2. Quark/OEM 最新系统 Service 绑定留给 P2-03。
