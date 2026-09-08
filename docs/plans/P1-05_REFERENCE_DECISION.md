# P1-05 Service 查询、Owner 与返回语义决策

状态：`SOURCE_ANALYZED`；本文在 P1-05 产品代码修改前写入。

## NBB / VA 方法与 CAS 对应

| 参考实现 | 已核对的执行方法 | CAS 对应与采用决策 |
|---|---|---|
| NBB `IActivityManagerProxy.StartService`（约 219 行） | 先以虚拟 PMS 解析；未命中时才交还原始 AMS。`BindServiceCommon`（约 341 行）同样先做虚拟解析，再决定代理或原始 Binder 调用。 | CAS `GuestIntentResolver` 仍以虚拟 PMS 为第一权威。CAS 不采用无条件原始 AMS 回退：仅可路由到已解析、启用、导出且 system/updated-system 所有的宿主 Service，并且 Intent 有显式组件或包约束。 |
| NBB `IPackageManagerProxy.ResolveService`（约 107 行） | 虚拟结果非空即返回；空结果再调用真实 PMS。该文件还有伪造 Play/默认权限的分支。 | CAS 的 `PackageManagerInvocationHandler` 对 `resolveService`、`queryIntentServices`、`getServiceInfo` 使用同一套虚拟优先/受限系统 Owner 条件；绝不把伪造包、签名或权限结果当成 Service 成功。 |
| VA `am.MethodProxies.BindService`（约 850 行）和 `StartService`（约 891 行） | 使用虚拟 `resolveServiceInfo`；命中后进入虚拟 ActivityManager，未命中才调用 host。 | CAS `GuestContextComponentRouter` 对 Guest 命中使用 Broker/ActivityThread bridge；已允许的 system Owner 使用受控 host context。缺失不会变成任意 host bind。 |
| VA `pm.MethodProxies.QueryIntentServices`（约 439 行）与 `ResolveService`（约 558 行） | 将虚拟服务和可见 host 查询结果组合，且 resolve 先取虚拟结果。 | CAS 仅组合合格 system Owner，且去重并保留虚拟同名包优先。普通 host app、未导出、停用、无包约束的 host 结果均保持不可见。 |

## 失败路径和 API 合同

当前路径是 `GuestContext.bindService` → `GuestContextComponentRouter.bindService` → `GuestIntentResolver.resolveOne(SERVICE)`。缺失服务抛出 `NO_GUEST_SERVICE_MATCH`，使一个正常的可选服务探测成为未捕获异常。Android `Context.bindService` 对合法“未找到”应返回 `false`；`startService` 的未找到返回 `null`，`stopService` 返回 `false`。策略/权限/解析错误不能被该转换吞掉。

因此 P1-05 只将**已完成解析、但没有 Guest 或受限 system Owner 候选**的 Service 转为上述每个 Context API 的 absence 返回值。空 Intent、关闭后的 Router、重复连接、Broker/host transport、权限与安全异常依旧保留原异常。对每个 absence 记录 `CS_GUEST_SERVICE_ABSENT`，并保留 resolver 的候选、MIME、enabled/exported、process 和 owner 诊断。

## 范围与拒绝项

- 外部 Service 不等于任意宿主应用：同名虚拟包永远优先，普通 host app 不可见；仅有 package/component 约束的 system/updated-system、enabled、exported Service 才可通过。
- 不制造 `ResolveInfo`、`ServiceInfo`、Binder、权限授权或回调；`onServiceConnected` / `onNullBinding` / disconnect 必须来自实际 bridge/host binding。
- MIME 为 null 仍按原始 `Intent` 传入 PMS；不将空字符串替换为 MIME type。
- P1-05 不把 Quark/OEM 的未知系统服务契约视作通过；真实 Quark 验收留给 P2-03。

## 跨包拒绝验收补充（修改前确认）

PMS 的可见性过滤只能证明 `resolveService`/`queryIntentServices` 不返回跨包非导出或调用方未获权限的 Service，不能证明 `Context.bindService` 没有把“空查询”降格为缺失返回。因而 P1-05 fixture 另以显式 peer component 调用 bind：`GuestIntentResolver.rejectExplicitVirtualServiceAccess` 在虚拟查询未命中后读取同一虚拟 manifest 投影，分别返回 `VIRTUAL_SERVICE_NOT_EXPORTED` 与 `VIRTUAL_SERVICE_PERMISSION_DENIED`。这保持 NBB/VA 的“虚拟优先”顺序，又不采用其通用 raw-host 回退。Probe 仅在 CAS 启动时启用，因为原生 PMS 对显式组件的信息可见性不构成 CAS 策略的基线。

## API32 `wifiscanner` 启动阻断补充（修改前确认）

API32 default x86_64 的 shell `service list` 显示 `wifiscanner`，但首轮 CAS 回执证明未受信任应用域的 `ServiceManager` 取值为空，导致 `VIRTUAL_DEVICE_SERVICE_PROXY_REQUIRED:wifiScanner` 于 P1-05 Activity 前阻断。NBB `IWifiScannerProxy` 直接以 raw `wifiscanner` Binder 构造并替换系统服务；它不覆盖该 Binder 在应用域不可发现的情形。VA `WifiScannerStub` 则安装 `GhostWifiScannerImpl`，伪造 Messenger 和空信道。

CAS 不采用任一结果：不把 system/control-plane Binder 回退给 Host，也不伪造 scanner Binder。现有 `PlatformServiceCompatibility` 已将 API33+ 的同一非应用域边界降级为可观测的 `DEGRADED`，保留真正的 `WifiManager` facade。将门槛扩至 API32，仅跳过这个非应用域 Binder 的安装/launch-readiness；不会跳过 `wifi` hook、改变 Service owner 规则、吞掉真实 `WifiManager` 异常或声明 scanner 功能成功。
