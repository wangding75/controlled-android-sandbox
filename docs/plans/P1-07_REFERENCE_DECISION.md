# P1-07 PMS 隐藏接口签名适配决策

状态：`SOURCE_ANALYZED`；本文件先于 P1-07 产品代码修改建立。

## 已核对的参考与平台合同

| 来源 | 已观察的执行方法 | 对本任务的结论 |
|---|---|---|
| NBB N04 `IPackageManagerProxy.ResolveService`（107） | 从 `args[0..2]` 按既定位置取得 `Intent`、`resolvedType`、flags；虚拟 PMS 命中即返回，否则以原 `args` 调真实 PMS。 | 可借鉴“虚拟优先、物理回退”的分流顺序；不能借鉴其 `int` flags 假设或 Play/权限伪造分支。 |
| VA V04 `MethodProxies.QueryIntentServices`（439） | 明确使用 `(Intent) args[0]`、`(String) args[1]`、`(Integer) args[2]`，再按返回类型处理 `List`/`ParceledListSlice`。 | 可借鉴逐位置绑定和返回容器解包；其旧 API 的 `int` flags 不可套用到 API32+。 |
| VA V09 `InvocationStubManager`（71） | 代理按注册的服务/方法边界安装，而不是基于方法名随意选择重载。 | 仅作为“显式支持集合”的结构参考；不采用其旧版全局 Hook 模型。 |
| AOSP `IPackageManager.aidl` Android 12 与 Android 13 release | Android 12 使用 `resolveService/queryIntentServices(Intent, String, int, int)` 和 `resolveContentProvider(String, int, int)`；Android 13 起对应 flags 扩为 `long`。API32 实机 `IPackageManager$Stub$Proxy` 的首次调用也确认 long 签名不存在。 | CAS API32 显式支持三项 `int` flags 合同，API33--36 显式支持对应 `long` 合同。最后参数均是物理 `userId`；Service 的 `resolvedType` 可以是 Java `null`，不得用空字符串替代。 |

## 现状和采用方案

旧 `HostPackageManagerBridge.invoke` 先找到任意同名方法，再以参数类型推导实参。这会在同名重载、两个 `String`，以及 int/long flags 之间串位；`resolveContentProvider` 还把不存在的第二个 `String` 参数传入了候选选择过程。

P1-07 将改为：

1. 以一个不可扩展的签名表枚举三个已审计 Binder 调用及 API32 `int` / API33--36 `long` 两种完整形态；仅接受参数类型和顺序完全相等的方法。优先 long，缺失时才尝试已登记的 int 形态。
2. Service 传入 `(intent, null, flags, physicalUserId)`，Provider 传入 `(authority, flags, physicalUserId)`；查询可接受 `List` 或含 `getList()` 的 `ParceledListSlice`。
3. `EMPTY`、`UNSUPPORTED_SIGNATURE`、`INVOCATION_FAILURE` 和 `UNEXPECTED_RETURN` 分开返回，并记录选择的完整签名。调用者只能把 `EMPTY` 解释为普通不存在；其余状态必须保留为可诊断失败。
4. 仅继续允许既有的虚拟优先、已启用/导出且非虚拟包的宿主 system-owner 窄路由。未知 OEM 签名不作猜测适配，留给 P2-07 用真机首错决定。

补充（静态首错 `GuestContextBoundarySelfTest`）：NBB/VA 都是在目标可被外部/物理 PMS 路由时才作物理回退；CAS 的 Host-system 规则也只允许显式 component 或 package-constrained Service。因此无地址的可选隐式 Service 不查询 raw PMS，直接维持普通缺失返回。这样既不隐藏已请求 Host route 的适配失败，也不让不可路由的 Host transport 改变平台 `false` 合同。

## 覆盖边界

本项不修改 `PackageManagerInvocationHandler` 的虚拟 PMS 投影策略（P1-08 将单独审计身份、UID 和 Attribution）。P1-07 只替换 `HostPackageManagerBridge` 对预捕获物理 PMS 的 owner-classification 调用。
