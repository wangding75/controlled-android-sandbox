# P1-04 参考实现与复现决策

日期：2026-09-08；前置：P1-03 的共享库投影已在 API36 AVD 通过。

## 参考方法与 CAS 对应点

| 参考实现 | 已核对的方法 | 可采用的执行约束 | CAS 对应实现 / 决策 |
|---|---|---|---|
| NBB | `BActivityThread.handleBindApplication`（约 358 行） | 从虚拟 PMS 取同一份 `ApplicationInfo`，建立 package `Context` / `LoadedApk`，配置运行时后创建 Application；安装 Provider，最后调用 `Application.onCreate`。 | `GuestRuntimeEnvironment.prepareOnCurrentThread`、`GuestLoadedApkBridge.install/bindApplication`、`GuestComponentRuntime.prepareDeclaredProviders`。保留 CAS 受控 `GuestContext`，不复制 NBB 的 host `createPackageContext` 路径。 |
| VA OSS | `VClientImpl.bindApplicationNoCheck`（约 316 行） | `LoadedApk.makeApplication` 与 `ActivityThread.mInitialApplication` 必须指向同一个 Application；修正 Context 后安装 Provider，再调用 `callApplicationOnCreate`。 | CAS 先经 `AppComponentFactory` 创建 Application，再将同一对象发布给 `GuestLoadedApkBridge`，随后 attach、Provider、`onCreate`。不调用 host-owned `makeApplication` 来二次创建。 |

## 当前失败与可观察合同

历史 Chrome 证据仅返回 `Object.getClass()` 的 NPE 文本，没有 Guest 栈或发生阶段，故根因保持未知，不能以 null guard 修复。本任务将对每次 bootstrap 输出 request/session/generation 绑定的阶段事件；失败结果必须携带当前阶段和有界因果栈。

P1-04 fixture 使用声明 `AppComponentFactory`、`Application`、同进程 `ContentProvider` 和 Activity：factory 只能构造一次，Application 的 attach/onCreate 各一次，Provider `onCreate` 必须发生在 Application `onCreate` 之前。该 fixture 验证启动合同，不冒充 Chrome/Trichrome，也不扩大 Provider 权限或绕过后续 P1-06 的外部 Provider 路由范围。

## 修改边界

只增加 bootstrap 诊断和 fixture/定向执行器。若 AVD fixture 失败，仅从记录的第一失败阶段定位单一分支；不吞异常、不增加重试、不伪造 `onCreate` 或 Provider 成功。Chrome ARM64/OEM 栈继续移交 P2-02。
