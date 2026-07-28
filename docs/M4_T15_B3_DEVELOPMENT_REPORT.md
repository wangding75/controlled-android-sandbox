# M4-T15 B3 Framework 查询入口与最终收口报告

## 状态

**PASS — SOURCE/HOST VERIFIED**

- Android 模拟器/真机：未执行。
- 设备证据：0。
- 本报告只说明源码、Host self-test、静态 Android 编译和可复现源码包证据。

## 1. 目标

B3 将 B1/B2 已完成的 Broker Activity/Task 状态接入 Guest Framework 调用入口，避免 Guest 通过 `ActivityManager`、`ActivityTaskManager` 或 `AppTask` 查询到宿主任务，或用虚拟 Task ID 直接操作宿主任务。

固定要求：

- Running/Recent/AppTask 数据只能来自 Runtime Broker。
- 查询和操作绑定 Session、Generation、Virtual User、Guest Package 和 APK Revision。
- 投影失败、Binder 失败、身份不一致时 fail closed。
- 不允许以宿主真实任务结果作为兼容回退。

## 2. 实现内容

### 2.1 Runtime Broker 能力注入

`RuntimeSystemServiceCoordinator` 在 Guest prepare spec 中同时注入：

- Package Service 所有的虚拟系统服务 Binder；
- Runtime Broker Binder。

`GuestPackageSpec` 保存并透传 Runtime Broker Binder。`GuestActivityTaskClient` 使用 typed `ActivityTaskRequest` / `ActivityTaskResult` 调用 `IRuntimeBroker.activityTaskOperation`，并校验 request ID、operation、Session 和 Generation 对应的响应。

### 2.2 Framework 查询入口

新增 `ActivityTaskFrameworkInterceptor`，拦截 `activity-manager` 和 `activity-task-manager`：

- `getTasks`
- `getRunningTasks`
- `getRecentTasks`
- `getAppTasks`
- `getTaskForActivity`

查询结果由 `AndroidTaskInfoProjector` 集中投影为：

- `ActivityManager.RunningTaskInfo`
- `ActivityManager.RecentTaskInfo`
- `ParceledListSlice`
- 本地 `IAppTask` Binder

`getAppTasks` 在隐藏 Binder 接口层返回本地 `IAppTask` Binder，由 Android 公共 `ActivityManager` API 包装为 `AppTask`，没有在错误层级直接伪造 `ActivityManager.AppTask`。

### 2.3 Framework Task 操作

拦截并接入 Broker：

- `moveTaskToFront`
- `removeTask`
- `moveActivityTaskToBack`
- `finishActivityAffinity`
- `finishActivityAndRemoveTask`
- `IAppTask.moveToFront`
- `IAppTask.finishAndRemoveTask`

Guest Activity 使用的 framework `IBinder` token 会映射到 Broker Activity token 和虚拟 Task ID。未知 token、跨包 AppTask 查询、过期 Session/Generation 均直接拒绝。

Broker 完成状态变更后，Stub Activity 在受控 bypass 中同步执行宿主 Task 的前移、后移或结束动作。结束动作先标记 Broker 已完成，随后 Stub `onDestroy` 只执行 Guest `onDestroy`，不再重复发送 `DESTROYED` 或 `FINISH_RESULT`。

### 2.4 Android 版本适配边界

反射差异集中在 `AndroidTaskInfoProjector`：

- Task ID 同时尝试 `taskId`、`id`、`persistentId`；
- 设置 `baseActivity`、`topActivity`、`baseIntent`、`numActivities`、`userId`、`lastActiveTime` 等可用字段；
- `getRecentTasks` 根据返回类型构造 `ParceledListSlice`；
- `IAppTask` 通过本地 Binder interface 注册，不依赖直接调用隐藏 `AppTask` 构造器。

任何必须类、构造器、字段或返回容器无法投影时抛出明确异常，不调用宿主 delegate。

## 3. 测试证据

新增 `ActivityTaskFrameworkInterceptorSelfTest`，覆盖：

- Running Task Android 对象投影；
- Recent Task `ParceledListSlice` 投影；
- IAppTask Binder 查询和操作；
- Session、Generation、Virtual User、Package 身份绑定；
- Framework token 到虚拟 Task ID 的映射；
- move-to-front、move-to-back、finishAffinity、removeTask 的 Broker 调用和宿主镜像；
- Broker 已完成后的重复 destroy 抑制；
- 跨包 AppTask 查询拒绝；
- Broker 失败后抛错，禁止宿主任务回落。

同时通过：

- M4-T15 Activity/Task 专项门禁；
- M4-T14 Service 生命周期回归；
- Typed AIDL、架构边界、包边界和 Guest 边界；
- 静态 Android 源码编译及全部 Host self-test；
- Native self-test；
- M3 严格发布阻断门禁；
- 两次源码 ZIP 字节级可复现比较。

## 4. 架构结果

- `RuntimeBrokerService`：1,370 行，没有因 B3 增长。
- `ActivityTaskFrameworkInterceptor`：约 250 行，集中处理 Framework 方法路由和 host-token 绑定。
- `AndroidTaskInfoProjector`：约 200 行，集中处理版本差异。
- 新增接口没有使用业务大 `Bundle`；Task Binder 调用继续使用 typed Parcelable/AIDL。
- 能力矩阵新增 `framework.activity-task-framework-api`。

## 5. 限制

以下内容没有设备证据：

- 不同 Android API 的隐藏接口真实签名和字段可访问性；
- OEM 对 `TaskInfo`、`ParceledListSlice`、`IAppTask` 的差异；
- 系统 Recents UI 中的图标、缩略图、TaskDescription 和转场；
- 多窗口、画中画、TaskFragment、Display/WindowContainer 关系；
- 宿主镜像动作失败后的 UI 收敛时间；
- 真实进程强杀后的 Android 回调顺序。

因此 B3 PASS 表示源码与 Host 门禁通过，不表示第三方 APK 已达到 VA/NBB 的设备兼容率。
