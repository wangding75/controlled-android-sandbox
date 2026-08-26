# C4-R05 ActivityThread 生命周期与进程边界收敛

日期：2026-08-26
范围：CAS translated-ABI Guest 的 ActivityThread 启动、Activity 生命周期和进程退出边界
状态：源码修复已完成；本机 user0 DingTalk cold→hot 定向验收通过；C4-R05 正式矩阵仍未关闭

## 1. 结论

本次问题不是缺少一个 `Activity.onCreate()` 或 `onDestroy()` 回调，而是把两个不同层级的状态混在了一起：

1. `ActivityThread` 应继续拥有真实的 `newActivity → onCreate → onStart → onResume →
   onPause/onStop/onDestroy` 调度和 `Activity.mCalled` 合同。
2. `Runtime.exit/System.exit` 是进程生命周期事件，应由真实进程退出、Guest service Binder death、
   process slot/generation recovery 收敛，不能通过跳过 `onDestroy`、伪造 `mCalled` 或吞掉退出异常把
   已死亡进程伪装成存活。

因此本次修复保留 Host Stub/route 和 ActivityThread 正常生命周期，只把 translated Guest 的
`Runtime.nativeExit` 恢复为真实进程边界；没有继续增加 Activity 层例外分支。

## 2. NBB/VA 参考实现

### VA

- `ref/upstream/VirtualApp/VirtualApp/lib/src/main/java/com/lody/virtual/client/hook/proxies/am/HCallbackStub.java`
  的 `handleLaunchActivity` 解码 `StubActivityRecord`，确保 `VClientImpl` 已绑定，向
  `VActivityManager` 报告 Activity 创建，再改写 framework record 的 Intent/ActivityInfo，最后把
  消息交回真实 `ActivityThread`。
- `ref/upstream/VirtualApp/VirtualApp/lib/src/main/java/com/lody/virtual/client/hook/delegate/AppInstrumentation.java`
  在前置修正/后置通知之间调用 `super.callActivityOnCreate`；resume、pause、destroy 等回调也都
  调用 `super`，没有改写 `mCalled` 或跳过 framework destroy。
- `ref/upstream/VirtualApp/VirtualApp/lib/src/main/java/com/lody/virtual/client/env/VirtualRuntime.java`
  对虚拟进程重启使用真实的 `Process.killProcess(Process.myPid())` 和 `System.exit(0)`，由进程
  重新 attach/restart 收敛，不把退出转成 Activity 成功标记。

### NBB

- `ref/upstream/NewBlackbox/Bcore/src/main/java/top/niunaijun/blackbox/fake/service/HCallbackProxy.java`
  同时处理 API 28+ 的 `EXECUTE_TRANSACTION` 和旧版 `LAUNCH_ACTIVITY`，解码
  `ProxyActivityRecord`、确保 `BActivityThread`/进程环境就绪，改写 transaction/item 后继续真实
  `ActivityThread` 执行。
- `ref/upstream/NewBlackbox/Bcore/src/main/java/top/niunaijun/blackbox/fake/delegate/AppInstrumentation.java`
  的两个 `callActivityOnCreate` overload 都调用 `super`；
  `BaseInstrumentationDelegate` 将 create/start/resume/pause/stop/destroy 等生命周期完整转发到
  base instrumentation。
- `ref/upstream/NewBlackbox/Bcore/src/main/java/top/niunaijun/blackbox/fake/service/IActivityClientProxy.java`
  在 `activityDestroyed` 等 ActivityClient 边界回报虚拟 Activity 状态，而不是伪造 ActivityThread
  内部回调。
- `AntiVirtualDetectProxy.java` 的 Runtime.exit 代码在该参考快照中只是未实现的占位说明，不能把它
  当作 NBB 已验证的退出拦截实现；本次只采纳其 process/activity owner 分层思路。

## 3. CAS 差异与本次收敛

| 边界 | NBB/VA 合同 | CAS 之前的问题 | 本次处理 |
|---|---|---|---|
| 启动事务 | 小的 Stub/Proxy projection，真实 framework 继续执行 | CAS route/Stub 与 translated Guest 进程态耦合，启动失败时曾继续在 Activity 层补偿 | 保留 route/Stub 和 request-scoped readiness |
| Activity 生命周期 | instrumentation 前置修正，随后完整 `super` 回调 | 为吞掉退出而跳过 `onDestroy`、修复 `mCalled`，破坏 `performLaunchActivity` 合同 | 删除这些特例；所有 lifecycle callback 均交 delegate/base |
| `mCalled` | 由 Activity 自己调用 `super.onCreate/onDestroy`，framework 负责校验 | 通过反射伪造 `mCalled` 掩盖回调未完成 | 不再写入或修复 `mCalled` |
| 进程退出 | 真实 process death，ProcessRecord/Binder death/rebind 负责恢复 | `Runtime.nativeExit` 被吞掉，root 被热复用，暴露 Doraemon 未初始化等二次错误 | JNI 只在进入原始 `Runtime.nativeExit` 的瞬间打开 native gate，随后真实退出 |
| direct native termination | 不应由普通 Guest 直接杀死宿主 slot | translated Guest 的 native 边界需要保护 CAS-owned service | `kill/_exit/abort` 仍为 deny-only；仅原始 Runtime exit 和 broker 自身 teardown 有明确放行 |

上一版的“逐层修复”链条是可解释的：吞掉退出后先暴露 `mCalled`，补上 `mCalled` 后再暴露
`onDestroy`/Doraemon 状态；这些不是独立缺陷，而是同一个错误进程语义产生的连锁症状。本次以
进程边界为 owner，移除 Activity 层伪装，避免继续沿着症状添加补丁。

## 4. 实现边界

- `sandbox-runtime/.../GuestActivityThreadInstrumentation.java`：不再根据进程退出状态跳过
  `onDestroy` 或修改 `mCalled`；正常委托 ActivityThread lifecycle。
- `sandbox-native/.../native_policy_jni.cpp`：注册 `Runtime.nativeExit(I)V` 的 JNI replacement，
  将调用转发到 libopenjdk 原始实现；原始实现通常不会返回，因为它终止当前进程。
- `sandbox-native/.../native_hook.cpp` 与 `GuestRuntimeEnvironment.java`：translated Guest 只安装
  狭窄的 host runtime lifetime boundary，不解析或改写 foreign Guest ELF 的通用 PLT/GOT。
- CAS broker/session/slot/generation 仍是死亡后的状态 owner；Activity readiness 只接受当前
  request、package、user、revision 的动态 `FIRST_FRAME_DRAWN`。

## 5. 验收证据

- 构建：`:app:assembleDebug :fixture-basic:assembleDebug` 通过；native 四 ABI 均完成。
- 静态/源码：`python tools/static_android_compile.py`、
  `python scripts/check-activity-task-virtualization.py`、
  `python scripts/check-c4-r05-orchestrator.py` 均通过。
- 本机 user0 定向矩阵：
  `verification/catch-up/C4-R05/continuation-local-launch-user0-runtime-exit-forwarded-probe-20260826/`
  的 `c4-r03-summary.json` 为 `PASS`，cold/hot 两行均为 `LAUNCH_PASS`，动态 Window/Surface/首帧/非黑
  截图有效，`retryBudget=0`、无自动重试。
- cold 为 `generation=1, platformPid=16306`，hot 为 `generation=2, platformPid=16618`；
  hot 日志记录 `Runtime.nativeExit(0) forwarded as process boundary`，随后 root `guest4` 进程
  death，证明使用了真实退出和新代际重建，而不是保活旧 root。

本证据只覆盖本机 user0 的 DingTalk cold→hot 定向回归；user1 未在本机执行，C4-R05 全量矩阵和
C4 阶段仍不得标记为关闭。
