# P1-08 参考源码决策单：系统身份、权限与 Attribution

日期：2026-09-08  
任务：P1-08（依赖 P1-07）

## 修改前溯源

| 来源 | 已核对的方法/参数 | 可采用的事实 | 不采用/边界 |
|---|---|---|---|
| NBB `HookManager.init()` | Guest/黑盒进程注册 `IPackageManagerProxy`、`IAppOpsManagerProxy` 等 injector | PMS 与 AppOps 必须作为进程级框架边界安装 | injector 已加入列表不是注入成功，更不是一次业务调用成功；不能把 NBB 的 `GetPackageInfo` 伪造权限或 Google Play 包分支当作 CAS 成功语义 |
| NBB `IPackageManagerProxy.ResolveService` / `getPackageUid` | PMS 无虚拟结果回原 PMS；`getPackageUid` 改写第一个 app package | 调用方 package 与目标 package 必须分开；虚拟优先、物理回退必须受可见性约束 | NBB 该版本按位置读取 int flags，且部分权限结果是历史兼容逻辑；不照搬为全局 grant |
| VA `InvocationStubManager.injectInternal()` | 仅 VApp 进程注册 `PackageManagerStub`、`AppOpsManagerStub` 等，按 API 级别增加服务 | 按进程角色/API 条件注册并保留失败可诊断 | 注册表本身没有业务级成功证明；不以其旧 API 签名推断现代 Binder 参数 |
| CAS `PackageManagerInvocationHandler.invoke/virtualResult` | PMS 虚拟结果先于 delegate；`checkPermission(permission, package)` 使用目标 package，Host 包返回 `PERMISSION_DENIED` | PMS 回包投影以虚拟 package universe、virtual UID 和 per-user permission policy 为准 | Host PMS 的真实返回不能泄露 Host 包、UID 或授权状态 |
| CAS `FrameworkIdentityInvocationHandler.invoke` | 精确 `MethodIdentityPolicy` 重写入参；未支持的受保护同名方法抛 `IdentityRewriteException`；delegate 异常记录 telemetry 后原样传播 | 只对已声明签名做身份重写；签名故障必须可识别，不可静默默认成功 | 不按参数猜测/吞掉 SecurityException |
| CAS `SystemServiceInvocationHandler.appOpsDecision` / `GuestContext` | AppOps 对 Guest target 本地按 `SandboxAppOpsPolicy` 返回；Host package/identity 注入抛 `VIRTUAL_APPOPS_HOST_PACKAGE_HIDDEN`；`GuestContext` 权限检查由 `GuestCapabilityGate` 决定 | `CAMERA` 授权和 `android:camera` AppOp 必须分别验证；服务发现不等于权限授予 | 不修改 Host 全局 AppOps/权限表，也不能以 physical UID 直接推断 Guest 许可 |
| CAS `GuestAttributionSourceBridge` / `GuestComponentRuntime` | API 31+ 为 Guest Provider 回调显式安装 `(virtualUid, guestPackage)` AttributionSource 并 finally restore | Guest Provider 回调必须看到虚拟 package/UID；调用结束后恢复状态 | 非虚拟 provider 的 `ContentResolver` 仍用物理 Host transport，避免 Android 验证虚拟 UID；该路径不应被误报为 Guest system identity |
| CAS `GuestRuntimeEnvironment.Session.updatePermissionState` / `FrameworkHookReport` | session 严格校验 package/user/revision，再替换 permission/AppOps、刷新 context gate、撤销已拒绝 lease；报告区分 installed/failures/readiness | 状态更新、缓存刷新与关键 hook 失败要独立可验 | 仅 `installed=true` 不是业务成功；mandatory hook 缺失会阻断 session，而非 PASS |

## P1-08 实施决策

1. 保持 PMS、AppOps 和 Provider 的现有 fail-closed 实现：审计后未发现需要为本任务放宽物理 Host 身份或全局授权的行为。
2. 扩展既有 `PmsPermissionAttributionProbeActivity`，令其以经 Debug 白名单透传的 launch extra 明确断言 virtual CAMERA、INTERNET permission 与 camera AppOp 的预期值；仍保留真实 `ContentResolver.call` Provider 回调和显式 Host package 拒绝。白名单是 CAS Debug command 到 Guest Intent 的唯一丢失点；不改 Broker/ActivityStack 的原始 Intent 路由。
3. 新建窄范围 runner：先以只读 `policy-state` 确认 Host APK 更新后的 Package Service 已连接，再用 Package Service 给同一 fixture 的 user 0 写入 `CAMERA=GRANTED/camera=ALLOWED/INTERNET=DEFAULT`、user 1 写入 `CAMERA=DENIED/camera=IGNORED/INTERNET=DENIED`，并分别冷启动 probe。Host 未获 CAMERA 时 user 0 的有效 CAMERA 必须仍是 `DENIED/IGNORED`，这是防越权断言；INTERNET 和真实 Provider callback 形成允许路径。每个 case 的 `import-only`、三项 policy 写入和 `policy-state` 回读构成可复核状态边界；不在安装后强制停止 Host，以免把 Package Service 单次导入连接的建立竞态伪造成业务失败。该 fixture 的 authority 在独立 `:provider` Guest 进程；由同一 `launch-component` Debug owner 先按 manifest `ProviderInfo.processName` 准备精确 `FixtureProvider`，再启动 probe，避免旧 `neighbor-provider` 重载将 process 置空、以及跨命令释放 lease。probe 的完整查询仍在 fixture worker 中执行，以避开 CAS `GuestMainThreadDispatcher` 已定义的 UI 线程重入等待边界，且使用相同的真实 PMS/AppOps/`ContentResolver.call`/Provider Binder 路径。保持通用 `LAUNCH_PASS` 创建门槛和 fixture 的 `C2_T02_PROBE_PASS` 语义标记。每次记录 Package Service 预检、policy-state、精确 Provider 准备、runtime launch status、fixture 回包以及 Hook 安装/失败字段。该 runner 不运行全量 capability matrix。
4. API33、35、36 分别实测；API33 AVD 若未运行则标为未测试，绝不并入 PASS。小米新增/变动 Attribution 参数只登记给 P2-07。

## 预期合同

* 允许：Host 已有的 INTERNET 在 user 0 为 `0`，真实 Provider callback 返回 Guest package/attribution package/UID；这不需要也不写入 Host 全局权限/AppOps。
* 拒绝：没有 Host CAMERA capability 时，即使 virtual policy 请求 `GRANTED/ALLOWED`，Guest CAMERA 仍为 `-1`、AppOps 为 `1`（IGNORED）；user 1 的显式拒绝同样无数据。Host package 查询抛 `VIRTUAL_APPOPS_HOST_PACKAGE_HIDDEN`。
* 故障：关键 hook 未安装、launch 非 `LAUNCH_PASS`、fixture 缺少 `C2_T02_PROBE_PASS` 或出现 fail marker、身份/策略不符或 hook failure 均为 FAIL，并保存首错。
