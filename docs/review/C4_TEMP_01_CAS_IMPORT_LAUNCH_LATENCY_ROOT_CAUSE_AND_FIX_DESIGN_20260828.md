# C4-TEMP-01：CAS 导入/克隆/添加/启动耗时根因与修复设计

## 1. 任务边界与变更理由

本临时任务插入 C4-R05 之前，原因是 C4-R05 在 MuMu `RD测试` 的夸克冷启动首帧门禁被真实阻断：
CAS 在 Host 启动后 30 秒窗口内未收到目标 `BrowserActivity` 的 `FIRST_FRAME_DRAWN`，而 Guest 随后
在约 33.44 秒才报告首帧。该证据不能用启动 marker 或重试覆盖。本任务只处理可归属于 CAS 通用导入/克隆/添加/启动
路径的延迟；不改变 C4-R05 的首帧、失败即停、FATAL/ANR、添加矩阵或双用户压力门槛，也不把历史阻断
自动改成通过。夸克自身 SDK/网络/媒体初始化若在 CAS 通用边界之外，仍保留为独立待验证风险。

任务书变更前置理由、影响和迁移规则：

- 原任务书没有“性能根因分析与修复”这个可插拔前置任务，直接继续 R05 会把通用 CAS 的同步成本与商业
  App 自身冷启动成本混在同一个首帧失败中，无法形成可审计归因。
- 新任务增加一个临时前置节点，不降低任何已有门禁；R05 仍保持 BLOCKED，只有在本任务完成且重新满足
  R05 恢复条件后才可续接。
- 新代码必须保持 package/user/revision 中立；不加入夸克包名、固定 ADB serial、端口、型号、固定 sleep 或
  隐藏重试。旧证据、Known Issues 和失败签名保留不覆盖。

## 2. 首次失败事实

来源：`verification/catch-up/C4-R05/formal-two-round-20260828-rerun2/round-1-clean-install-cold/launch-matrix/attempt-005/attempts/quark/user-0/cold-001/`。

- request/operation：`d261bd...` / `d261bd...-launch`；runner operation
  `c4-r03-quark-u0-cold-1-a5-d261bd...`；attempt=5，retryBudget=0，retryable=false。
- Host stage timing：`INPUT_VALIDATE_RETURN` 1.637s，`PROCESS_VALIDATE_RETURN` 5.724s，
  `VALIDATION_ARTIFACT_CACHE_PUT` 5.748s，`REVISION_READ_RETURN` 5.749s，`INDEX_RETURN` 5.813s，
  `SESSION_ALLOCATE_RETURN` 5.815s，`PREPARE_RETURN` 12.985s，`HOST_START_RETURN` 13.055s；其后
  30s host gate 到期。
- Guest trace：root `com.ucpro.MainActivity` 先报告 READY 后离开；child `com.ucpro.BrowserActivity`
  在 16:18:12.786 resumed、16:18:28.830 才报告 `FIRST_FRAME_DRAWN`，windowAttached/windowRegistered
  均为 true。快照窗口和截图最终有效且 `nonBlackFraction=1.0`，所以“显示未发生”与“发生但超过 gate”必须分开。
- 商业样本 revision：Quark 10.10.5.1080，base 169,654,003 bytes，primary ABI `arm64-v8a`；
  设备快照、APK hash、boot ID 详见上述 evidence 的 `device.json`、`case.json` 和原始 logcat。

## 3. VA/NBB 对照与 CAS 差异

### 3.1 安装、导入和克隆

- VA `VAppManagerService.installPackage`：`parsePackage` 一次，检查 `PackageCacheManager`，复制 APK/库，
  持久化 package cache，必要时一次 dex-opt；`installPackageAsUser` 只切换 user 安装状态并持久化，不重新解析或复制 APK。
- NBB `BPackageManagerService.installPackageAsUserLocked` 与 `BPackageInstallerService`：解析/ABI/设置校验一次，
  建立 `CreateUser -> CreatePackage -> Copy` executor 链，结束时保存组件和 settings；同 package/user 的事务由安装器状态承载。
- CAS `ApkImportManager` 的复制、全量 hash、manifest parse、split/native 扫描和 content-addressed publish 是导入时
  必要的一次性成本；`SandboxPackageLifecycle.createClone` 仅保存 catalog clone，不复制 APK。问题不在于取消完整性检查，
  而在于不能把导入成本再次带入每次 prepare/launch。

### 3.2 启动、进程和窗口

- VA `ActivityStack.startActivityProcess` 先调用 `startProcessIfNeedLocked`；`VActivityManagerService` 复用存活且
  Binder 有效的 `ProcessRecord`，仅在缺失时启动一次，然后构造 stub intent。
- NBB `ActivityStack.startActivityProcess` 建立 `ProxyActivityRecord` 并调用 `BProcessManagerService.startProcessLocked`，
  依赖已缓存的 PackageSetting/进程记录。
- CAS `RuntimeGuestLifecycleCoordinator` 冷路径在 Activity 启动前同步执行 validator、revision/index、session allocate、
  native bootstrap 和 system-service attach；`RuntimeGuestRequestValidator` 对 base+split 做完整 hash，随后
  `GuestRuntimeEnvironment.prepareNativeBootstrap` 再次完整 hash。`RuntimeClient.packageUniverse` 每次请求还会对
  其他 catalog APK 调用 Host `getPackageArchiveInfo`，而 `GuestRuntimeEnvironment` 又对当前 APK 调用 archive parser
  获取 ApplicationInfo 和 component factory。上述是 CAS 通用重复工作，正好落在 R05 的 Host prepare 13s 与大 APK 场景。

## 4. 根因分类

| 分类 | 结论 | 证据/边界 |
|---|---|---|
| CAS 通用 | Broker validator 与 Guest native bootstrap 对同一 immutable revision 重复全量 hash | 代码路径与 R05 的 5.7s validate、13s prepare 相符；属于可修复通用成本 |
| CAS 通用 | 每次请求对 peer APK 做 Host archive parse；当前 APK 的 ApplicationInfo/factory 也被重复 parse | `RuntimeClient.packageUniverse`、`GuestRuntimeEnvironment`；权威 `VirtualPackageStateSnapshot` 已包含这些字段 |
| CAS 通用 | 冷启动把 prepare/attach 全部串行放在 Host `startActivity` 前 | `RuntimeGuestLifecycleCoordinator` stage trace；应保留安全边界，仅减少重复工作 |
| App/SDK 特有（待验证） | Quark nested BrowserActivity 在 root Activity 离开后约 33.44s 才首帧，可能有 SDK/网络/媒体初始化 | R05 Guest lifecycle；必须用直启基线与修复后 CAS 对照才能归因 |
| RD 环境 | 低内存/Host process 压力是独立 KI，不能用性能修复掩盖 | `KI-R03-058` 等已有记录 |

## 5. 修复设计

1. **Broker-issued revision verification**：新增内部 Bundle key。Validator 在成功完成完整 base/split hash 后
   清除来包的标志并写入 `true`；验证缓存 artifact 和 `GuestPackageSpec.toBundle()` 原样携带。普通 package 在 Guest
   仅当该 Broker-issued 标志为真时跳过第二次 hash；isolated descriptor 路径始终重新验证。该优化不改变 revision、路径
   归属、sealed content-addressed 文件或 Broker 的首次验证。
2. **权威 package state projection**：Guest `ApplicationInfo` 和 app component factory 直接来自 CAS
   `VirtualPackageStateSnapshot.applicationInfo()`/manifest metadata，不再对普通 APK 调用 Host
   `getPackageArchiveInfo`。身份、source/split/native 路径仍由 `GuestApplicationInfoFactory` 重写；isolated 能力路径不变。
3. **Peer universe 不再 archive parse**：`RuntimeClient.packageUniverse` 使用 package authority state 的 4-arg
   `VirtualPackageProjectionSnapshot` 构造函数，保留 virtual UID 和 immutable paths；不把可选 Host parser 结果带入请求。

这些改动不增加 timeout、不使用固定 sleep、不增加 launch retry，不按商业包名分支。若压测仍由 Quark SDK 自身占用，
应在证据中明确标为 App/SDK 待验证，而不是继续扩大 CAS gate。

## 6. 回归与验收

- 先执行锁定构建、静态/单元测试；普通 package 和 isolated package 各覆盖 revision flag 行为，确保不可信 caller
  不能自行声明已验证。
- 动态解析 MuMu `RD测试`，记录 serial/API/model/ABI/boot ID/Android ID，不在脚本中硬编码设备或商业包名。
- 在同一 clean commit 上分别执行夸克模拟器直启和 CAS 沙箱启动/初始化，首轮失败立即保留 request/operation、attempt、
  stage timing、logcat/dumpsys/window/surface/截图/首帧和 APK/revision hash；测试控制只允许显式 force-stop 冷启动，
  不进行自动重试。
- 采用不少于 3 次直启冷基线和 3 次 CAS 冷启动对照，计算 `sandbox/direct`；硬门槛不超过 10x，目标不超过 3x。
  直启与沙箱均以真实可见/首帧为结束点，不能以 LAUNCH_PASS、Activity marker 或 Guest 进程存在代替。
- 运行 package-neutral fixture 的导入/clone/add/start 回归和既有 C1/C2/C4 静态门；若 RD 设备、夸克样本或构建产物
  缺失，任务保持 BLOCKED 并提交完整阻断证据，不修改 R05 门槛。

## 7. 风险、回滚与下一步

- 风险：某些 OEM parser 补充字段未被 CAS manifest parser 覆盖。通过 `VirtualPackageStateBuilder` 字段映射和现有
  Guest metadata tests 复核；若发现缺失，恢复单字段 fallback 并记录成本，而不是恢复整包重复 parse。
- 风险：验证缓存与 process generation 不一致。flag 仅由当前 Broker validator 产生，并随 revision/package state
  fingerprint 绑定；缓存失效时走完整验证。
- 回滚：两个独立提交可分别回退；不删除历史 R05 evidence/Known Issues。
- 任务完成后重新写入临时任务回执和 Known Issues 变化；只有动态 ratio 达标且无新增 P0/P1，才把 C4-R05 恢复为下一
  `PENDING` 依赖；否则临时任务本身 `BLOCKED`，后续停止。
