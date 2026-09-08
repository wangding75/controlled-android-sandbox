# P1-06 Provider 外部路由、空结果与授权生命周期决策

状态：`SOURCE_ANALYZED`；本文在 P1-06 产品代码修改前写入。

## NBB / VA 方法与 CAS 对应

| 参考实现 | 已核对的执行方法 | CAS 对应与采用决策 |
|---|---|---|
| NBB `IActivityManagerProxy.GetContentProvider.hook`（约 132–188 行） | 系统 authority 走原始 AMS；其余 authority 由虚拟 PMS 解析，启动对应虚拟进程，向原始 holder 写回虚拟 `ProviderInfo` 与 Binder。没有 ProviderInfo 或 Binder 时返回 `null`。 | CAS `GuestContentProviderFrameworkInterceptor.intercept` 已按 authority 建虚拟 descriptor、single-flight 创建 Broker holder。采用“缺失返回 null”的结果语义；不采用 NBB 的 Google/厂商 authority allow-list 或无条件宿主 AMS 直通。 |
| VA `MethodProxies.GetContentProvider.call`（约 1349–1401 行） | 虚拟 PMS 命中且启用的 app Provider：启动 owner、取得 stub holder、替换 Binder/Info；进程创建或 holder 缺失均返回 `null`。未命中则调用宿主 AMS，并在成功时包装宿主 Binder。 | CAS 采用虚拟优先、holder 缺失不伪造的原则。CAS 不采用 VA 的原始 host fallback/包装，因为它会向 Guest 暴露普通 Host Provider 与其调用身份。仅 `enabled && exported && system/updated-system` 的物理 Provider 可 pass-through。 |
| VA `pm.MethodProxies.QueryIntentServices/ResolveService`（任务书 V04） | 虚拟结果优先，外部结果必须经过可见性条件后组合。 | 该方法不是 Provider transport 实现，不复制。其“虚拟优先、外部可见性受限”原则与 CAS Provider owner 策略一致。 |

## 已定位的失败路径与平台合同

当前路径为 `ContentResolver.acquire*` -> `IActivityManager.getContentProvider` ->
`GuestContentProviderFrameworkInterceptor`。当前实现对非虚拟 authority 调用 raw Host PMS：

1. PMS 返回合格 system/updated-system、enabled、exported owner：保留 pass-through，由物理 AMS 提供真实 transport；
2. PMS 返回 `null`（authority 不存在）：当前错误地抛出 `CONTENT_PROVIDER_AUTHORITY_NOT_VIRTUALIZED`；
3. PMS 解析到普通 Host、未导出、停用、package 不一致或虚拟包：保持拒绝，不能退化为 Host route 或伪造 holder。

第 2 项与 Android 的 optional provider probe 合同以及 NBB/VA 的空结果行为冲突。P1-06 仅将它改为 `Interception.handled(null)`：framework proxy 不会继续调用 Host AMS，SDK 得到正常的 acquire-null。第 3 项仍抛出结构化安全错误，故不存在把越权/未导出 Provider 放开的路径。

`HostPackageManagerBridge` 的反射签名选择属于 P1-07 的明确范围；本项不修改其方法选择、参数推导或失败分类。若 raw PMS 因该桥接返回 `null`，CAS fail-closed 为 acquire-null，且不宣称目标不存在已经由真实物理 PMS 证实；P1-07 将补签名审计。

## 生命周期范围、现有证据与本项验证

- 双 authority 由 `addDescriptors` 分号拆分，并以 first-wins 规则避免后续重复声明篡改 owner。
- 不同 authority 的创建不持有全局锁，`initializations` single-flight；同 authority 共享 holder，递归同 authority显式失败以避免 main-thread 死锁；`close()` 先发布 terminal fence，再清空 holder/provider，禁止死代复活。
- `GuestBrokerContentProviderSelfTest` 覆盖 query/call/openFile、Cursor 取消/关闭、nested provider 路由、holder single-flight 与不同 API 形状的 authority 提取。
- `UriGrantLifecycleSelfTest`、`ProviderLifecycleCoordinatorSelfTest`、`RuntimeProviderResourceCoordinatorSelfTest` 分别覆盖 URI grant 消耗/撤销/换代、Cursor/FD/observer 的 cleanup 和并发 cleanup。它们是 Broker 层 lease 证据，不把 framework holder `noReleaseNeeded` 误记为普通平台 client lease。

## 拒绝项

- 不为缺失 authority 制造 `ProviderInfo`、holder、Binder、grant、observer、回调或成功内容。
- 不将普通 Host APK、未导出/停用 Provider、虚拟 package collision 或跨 user Provider 直通给 Guest。
- 不复制 NBB 的 authority allow-list、VA 的原始 Host Binder 包装或任何 VA PRO README 声明。
- 不借本项修改 P1-07 的按方法名反射适配，也不提前声称小米/OEM Provider 已验收。
