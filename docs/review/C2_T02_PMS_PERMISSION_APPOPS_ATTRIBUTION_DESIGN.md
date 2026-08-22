# C2-T02 PMS / Permission / AppOps / AttributionSource 设计与验收

## 1. 范围与结论

本任务关闭 Guest 包可见性、运行时权限、AppOps、AttributionSource 和 Provider callback
identity 在 Host / Guest / virtual-user 边界上的一致性。实现与验收范围是当前 MuMu
`RD测试`、Android API 32；不把本任务结果外推为 API 33+、ARM/16KB、OEM 或 VA PRO
等价性证明，`va_pro_equivalent` 明确记录为 `NOT_PROVEN`。

## 2. 方法矩阵

| 面 | 实际方法/路径 | 约束与结果 | 证据 |
|---|---|---|---|
| PMS | `getApplicationInfo`、`getPackageInfo`、`resolveActivity`、`queryIntentActivities` | Guest 包可见；显式 Host 包查询按 Guest 语义隐藏；Host launcher 不泄漏 | `PmsPermissionAttributionProbeActivity` |
| Permission | `Context.checkSelfPermission`、`PackageManager.checkPermission`、权限服务入口 | 虚拟用户的 `DEFAULT/GRANTED/DENIED` 投影决定结果；Host 包权限查询拒绝 | 同上，user0/user1 |
| AppOps | `checkOpNoThrow`、`noteOpNoThrow`、`startOpNoThrow`、`finishOp`、`noteProxyOpNoThrow`、`checkPackage` | check/note/start/proxy 的 mode 与 virtual policy 一致；Host package/UID 组合 fail-closed | 同上，四条公开 mode 路径和负面用例 |
| Attribution | `Context.getAttributionSource`、`ContentProvider.getCallingAttributionSource` | Guest package、virtual UID 和 chain 在调用与 Provider callback 中保持一致 | Fixture Provider callback |
| Lifecycle | `clear`、`delete`、recreate、policy reset | clear 清理原始 permission/AppOps policy；delete 移除实例策略，重建为默认实例 | `policy-state` + reset audit + 双用户证据 |

## 3. 身份变换规则

1. `SystemServiceInvocationHandler` 对 PMS/Permission/AppOps 参数执行统一边界判断：显式
   Host package 被隐藏；携带物理 Host UID 但只指向 Guest package 的受控 AppOps 检查，映射到
   Guest virtual policy；Host UID 与 Host package 不匹配时不返回 Host 结果。
2. Guest Context 生成 API 32 `AttributionSource(packageName, virtualUid, ...)`，并以该
   source 作为 AppOps proxy 和 ContentResolver 调用的入口身份。
3. Broker 转发 provider transaction 时，将经 request/session 校验的 Guest caller package
   与 virtual UID 安装到 `ContentProvider` 的 calling attribution source；调用结束后恢复
   原值，避免 callback identity 泄漏或跨调用残留。
4. `SyncNotedAppOp` 使用 API 32 隐藏四参数构造保留真实 mode；不能使用公开二参数构造，后者
   会把 mode 固定为允许，造成 note/start 与 check 分叉。

## 4. 状态与生命周期语义

`policy-state.cameraAppOp` 是运行时有效投影：当 CAMERA permission 为 `DEFAULT` 且 Host
能力未授予时，有效投影仍可为 `IGNORED`。因此 clear 验收同时读取
`cameraAppOpPolicy` 和 `RESET_APP_OP` audit；后者为 `DEFAULT` 才代表原始 AppOps policy
已清除。delete 会删除目标实例、其 policy、request 和 audit，`policy-state` 的后续
`ensureInstance` 只得到无策略默认实例；另一 virtual user 的策略与 reset audit 不变。

## 5. 变更面

- `sandbox-framework/.../SystemServiceInvocationHandler.java`：PMS/Permission/AppOps identity
  boundary、Host 隐藏、AppOps mode/Attribution 参数适配。
- `sandbox-runtime/.../GuestContext.java`、`GuestPackageContext.java`、
  `GuestAttributionSourceBridge.java`：Guest AttributionSource 和真实 API 32
  ApplicationContentResolver。
- `sandbox-runtime/.../GuestComponentRuntime.java`、`GuestRuntimeBrokerBridge.java`、
  `RuntimeKeys.java`：provider callback caller identity 传递、校验和恢复。
- `app/.../SandboxPackageLifecycle.java`：clear instance data 后 reset permission/AppOps policy。
- `app/.../DebugCommandActivity.java`、fixture probe/provider：可重复的 policy、PMS、
  Permission、AppOps、Attribution 设备证据入口。
- `tools/capability/run_c2_t02_rd.py`、静态检查和回归自测：方法矩阵、负面测试、双用户及
  clear/delete 收敛验证。

## 6. 验收入口

```text
python tools/capability/run_c2_t02_rd.py --instance RD测试
python tools/static_android_compile.py
python scripts/check-pre-device-runtime-hardening.py
python scripts/check-runtime-permission-workflow.py
python scripts/check-virtual-package-state.py
python scripts/check-package-query-resolve.py
python scripts/check-package-service-boundary.py
python scripts/check-binder-system-services.py
```

设备主回执：`verification/catch-up/C2-T02/c2-t02-rd-summary.json`；本轮设备为 API 32、
serial `127.0.0.1:16416`，user0 denied / user1 allowed 的权限、AppOps、Attribution 和
Provider callback 均通过，clear/delete 后状态收敛，跨用户策略未串扰。

## 7. 已知边界

本任务继续引用既有非阻断项 `KI-R03-020`、`KI-R03-023`、`KI-R03-024`、`KI-R03-025`、
`KI-R03-026`、`KI-M10-005`、`KI-M10-006`、`KI-M10-007`；本轮未将其伪装为已修复，也未
新增 `BLOCKED`。VA PRO 等价性、API33+ 和 OEM 特定隐藏 overload 仍留在后续任务范围。
