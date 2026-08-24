# C4-R02 添加事务、超时与 UI 状态机设计

日期：2026-08-24

## 1. 范围与结论

本设计只处理 C4-R02：package/user 变更操作的单飞、可观测阶段、确定 deadline、失败回滚、UI 状态和商业样本导入兼容性。不实现 C4-R03 的启动/首帧修复，也不改 C4-R04/R05 验收结论。

已确认：

- C4-R01 的红果与番茄首次失败均发生在 CAS `ApkImportManager.requireCompatibleElf`，早于 catalog commit 与 SX/UI 成功边界，owner 是 CAS 通用导入兼容性。
- 当前动态 RD 快照中，红果 `com.phoenix.read` 7.0.5.33 与番茄免费小说 `com.dragon.read` 7.1.9.32 均只有一个 base APK，PMS 报告主 ABI 为 `arm64-v8a`。
- 两个 APK 分别有 125/136 个正常 arm64 ELF，但都在 `lib/arm64-v8a/libcvt.so` 夹带一个 class=32、machine=ARM(40) 的 ELF。Android PMS 已接受这两个物理安装；CAS 的“每个 ELF machine 必须等于目录 ABI”规则比 Android 安装合同更严格，形成误拒绝。
- 夸克 `com.quark.browser` 10.10.5.1080 当前只有 arm64 ELF（另有 10 个非 ELF `.so` payload），不覆盖上述混合 ELF 情形，只是正向对照。
- Package Service 当前用一个全局 `operationLock` 串行所有请求；第二个相同 package/user 请求会排队并再次执行，不会返回同一 operation 或 `BUSY`。
- Host UI 使用单线程 executor，但没有 request/operation ID、阶段、elapsed 或 package/user 单飞；重复点击只是排队。
- `importInstalledApplication` 先发布 revision、提交 catalog，再单独 `ensureInstance`。后一步失败时，调用方看到添加失败但 catalog/revision 可能已经变化，边界不是一个原子添加事务。

待验证：

- 混合 ELF 中 `libcvt.so` 是否会被目标 App 的实际加载路径请求；C4-R02 只恢复与 Android PMS 一致的安装兼容性，不把后续 native load/启动兼容性宣称为 PASS。
- Host/PackageService 死亡注入下的具体 Binder 首发错误签名；验收必须保留 attempt、retryable 与 retry budget，不预设错误文本。

## 2. VA / NBB 安装合同映射

### NewBlackBox

- `BPackageManagerService.installPackageAsUser` 用 `mInstallLock` 串行安装/卸载；解析、ABI 目录可用性、进程停止、installer executor、settings/component publish、安装通知按顺序发生。
- `BPackageInstallerService` 明确按 create-user、create-package、copy executor 顺序执行，任一非零立即终止。
- `AbiUtils` 只按 APK 中存在的 ABI 目录判断进程位宽可用性；`NativeUtils.copyNativeLib` 复制选定 ABI 目录的 `.so`，不逐文件拒绝目录内 machine 不一致的 payload。
- NBB 缺少 CAS 所需的持久化原子 revision 与精确回滚，不能直接复制其实现。

### VirtualApp

- `VAppManagerService.installPackage` 是同步单入口；先 parse/update policy，再选择 ABI 并调用平台 `NativeLibraryHelper`，随后复制 base、更新 cache/settings、持久化，最后启动广播系统并通知 observer。
- `NativeLibraryHelperCompat` 以 ABI 目录集合和平台 `findSupportedAbi/copyNativeBinaries` 选择/复制，不自行逐 ELF machine 审核。
- VA 更新路径会先删除旧 lib/odex，弱于 CAS “旧 revision 直到新 catalog 原子切换前保持可用”的回滚要求，因此只采纳时序边界，不采纳覆盖式更新。

### 采纳 / 不采纳

采纳：package/user 单飞；parse/ABI/copy/publish/persist/notify 分阶段；只有完整提交后暴露成功；ABI 选择按 Android APK 目录合同，不对目录内每个 payload施加额外 machine 拒绝；失败清理 staging。

不采纳：直接复制上游代码；覆盖旧 revision；宽泛异常后反复重试；固定 sleep；把 observer/UI 状态当权威 catalog；把 Quark PASS 外推到红果/番茄。

## 3. CAS 设计

1. Package Service 增加进程级 `PackageMutationCoordinator`。key 为规范化 `packageName + virtualUserId`；请求先登记 request ID/operation ID，再进入现有 authority lock。已有同 key operation 时立即返回稳定 `MUTATION_BUSY` 和现有 operation ID，不排队形成第二次提交。
2. `importInstalledApplicationAndEnsure` 作为一个服务端事务执行：snapshot/copy/hash/parse/native extract/publish 后，在一个 catalog state 中同时写入 record 与 user instance；catalog save 失败删除未引用 revision、恢复 lifecycle transaction，旧 catalog/revision 保持可用。
3. 每个 operation 输出 `requestId`、`operationId`、package/user、attempt、retry budget、stage、terminal status、stable error code 与阶段耗时。阶段为 `BIND`、`COPY`、`HASH`、`PARSE`、`NATIVE_EXTRACT`、`PUBLISH`、`CATALOG`、`ENSURE_INSTANCE`。
4. deadline 不通过扩大 runner 总 timeout 实现。各阶段有代码内固定预算，并在流式 copy/hash 与阶段结束处检查；超时返回 `PACKAGE_OPERATION_STAGE_TIMEOUT:<stage>`，禁止自动重跑业务事务。
5. Binder acquisition 使用单次 bind attempt；只有调用服务端之前的明确 `SERVICE_UNAVAILABLE/BIND_*` 才允许一次自动重试。每次 attempt 和 retry decision 写入 client trace。解析、签名、ABI、权限、安全、catalog、事务错误 `retryable=false`、retry budget=0。
6. Native extract 保留选中 ABI 目录和 ELF 安全头检查，但不再把目录内不同 class/machine 的 ELF 当安装错误；记录 `MIXED_ELF_MACHINE` anomaly。运行时如果实际加载不兼容 ELF，按 native load 错误 fail-closed，不在安装阶段猜测。
7. UI 在提交前生成 request ID，立即禁用添加入口并显示 `QUEUED/IMPORTING + elapsed + requestId`；终态显示稳定 code 和阶段摘要后恢复按钮。重复 UI 请求由本地单飞拒绝，服务端单飞仍是最终防线。

## 4. 验证设计

- 单元/静态：相同 key 并发只允许一个 owner；不同 key 可登记；BUSY 返回现有 operation；deadline 分类稳定；非 bind 错误 retry budget=0；混合 ELF 记录 anomaly 而不误拒绝；catalog/ensure 故障回滚旧 revision。
- RD：动态解析 `RD测试`；动态发现 fixture、DingTalk、夸克、红果、番茄实际 package/version/base/split/ABI；fixture 50 次，四个商业样本各 10 次 add/delete/re-add；每次记录阶段和残留扫描。
- 故障：重复点击、并发 add、Host/PackageService death 分开执行；首次失败立即保存日志/事务/目录/进程/设备快照。恢复用例不改写首次失败结果。
- 门禁：任一规定样本不是 100%，或存在 `.install-*`、半发布 revision、in-flight lifecycle transaction、孤儿 instance、未分类 retry，即 C4-R02 BLOCKED。

