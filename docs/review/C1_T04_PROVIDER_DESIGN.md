# C1-T04 ContentProvider 数据与授权生命周期设计

## 1. DISCOVER / CLASSIFY

本任务开始基线为 `feature/t57-r03-va-pro-capability-campaign` @
`ada061ca695ccd88fe1605f9ae35172b4652a5c0`。开始前工作区干净；按任务书将
`C1-T04` 标记为 `IN_PROGRESS` 后，续接校验器动态解析 MuMu `RD测试`，得到 API 32、
serial `127.0.0.1:16416`、boot ID `7cac15ce-d76e-44ea-968b-959d91d03be7`。

现状盘点：

| Provider 面 | 已有 owner / 证据 | C1-T04 剩余证明 |
| --- | --- | --- |
| authority / manifest metadata | `ProviderAuthorityRegistry`、`ProviderManifestAuthorityResolver`、`GuestContentProviderFrameworkInterceptor`；Provider self-tests | 真实 Guest `ContentResolver` 的 owner、authority、跨包/跨用户隔离 |
| CRUD / bulk / cursor paging | `BrokerProviderRuntime`、`GuestBrokerContentProvider`、Provider transport self-tests；旧 FrameworkProbe 已测 bulk | CRUD、分页、cursor close/requery 在 Guest Activity 中的可回溯结果 |
| `applyBatch` / call / rollback | `ProviderBatchRuntime`、`AtomicProviderBatch`、既有 framework probe | 含 call、exception-allowed、extra back-reference 的真实 Resolver 事务 |
| file / asset / typed asset | `BrokerFileRuntime`、`GuestProviderFileTransport`、file self-tests | 真实 `ContentResolver` FD 打开、读写、关闭和重复循环 |
| URI grant / permission | `UriGrantRegistry`、`BrokerProviderRuntime`、Guest Context router；URI grant self-test | Guest grant/revoke、跨用户拒绝、授权后路由及清理证据 |
| ContentObserver | `BrokerObserverRuntime`、Guest observer bridge、framework observer self-test | Guest register/notify/unregister、回调次数和 stop/death 清理 |
| death / recovery / pressure | `ProviderLifecycleCoordinator`、resource coordinator self-tests；`KI-T57-013` | provider/Guest 代际重建、取消、双用户并发压力；不得以“不崩溃”代替资源收敛 |

首次 collect-all 结果为 42 gates：30 `PASS`、11 已登记 `KNOWN_ISSUE`、1
`NEW_REGRESSION`（SBOM 校验）。该新回归不是 runtime 缺陷：C1-T03 追踪的
`fixture-basic` 已有 62 个文件，但 `verification/sbom.json` 仍保留 57 个文件及旧摘要。
按 `TEST_EVIDENCE_GAP` 分类，使用 `scripts/generate-sbom.py` 重生成并保留原始审计证据；
不改变 Provider runtime 以掩盖治理门。

## 2. 目标与验收矩阵

新增 package-neutral `ProviderCampaignActivity`，所有 Provider 操作从 Guest 的公开
`ContentResolver` / `Context` API 发起。每一轮覆盖：

| 维度 | 证据 | 失败判定 |
| --- | --- | --- |
| authority / type | `getType`、self provider 与 exported `fixture32` provider | authority 漂移、类型为空或跨用户命中错误 owner |
| CRUD / bulk | insert、query 全量迭代、bulkInsert、update、delete | 返回值、列、值或用户归属不一致 |
| cursor / cancellation | 大于一页的 cursor、requery、`CancellationSignal` 取消 ANR 查询 | 截断、重放、未关闭、取消后仍泄漏或错误标记 PASS |
| batch / call | insert/update/call、exception-allowed、extra back-reference、失败后的状态查询 | 结果数量/extra 错误、事务半提交或异常被吞掉 |
| file leases | `openFileDescriptor`、`openAssetFileDescriptor`、`openTypedAssetFileDescriptor` 读写关闭 | Host 路径泄露、FD 无法读写、重复关闭/泄漏 |
| observer | register、provider `notifyChange`、self/descendant callback、unregister | 回调缺失/重复、跨用户回调或停止后继续回调 |
| URI grant | Guest `grantUriPermission` / revoke、授权操作、跨用户负测 | 未声明 grant 仍成功、跨用户成功、revoke 后仍可用 |
| recovery / isolation | 每轮 stop 后重新建立 Provider；user0/user1 同时运行；跨包 exported provider | stale generation、authority、数据或资源跨用户串线 |

设备证据只声明 `RD_BASELINE`，不外推 API33+、ARM/16KB、OEM、SX/XH 或 VA PRO
等价性。C1-T04 的 30 分钟压力采用 user0/user1 并发的 Host 控制任务；每轮结束
显式 stop Guest，并由 runner 保存分用户命令结果、logcat、设备快照和 APK hash。

## 3. IMPLEMENT_BATCH 边界

1. 在 `fixture-basic` 增加 Provider campaign Activity 和必要的 provider notify/change、
   grantUriPermissions 配置；不添加产品包名特判。
2. 在 debug Host 增加单用户/双用户 Provider campaign 命令，使用 runtime stop 作为每轮
   generation fence，并返回结构化 cycle 结果。
3. 增加 `run_c1_t04_rd.py`：动态解析 `RD测试`、安装四枚 APK、执行双用户 50 轮和
   30 分钟并发压力，逐轮抓取 logcat，校验关键 marker、fatal/ANR、用户隔离和清理。
4. 以现有 Provider self-tests、pre-device hardening 和 framework transport probe 为
   companion gates；不把已有源码存在或单个 marker 当作设备 PASS。
5. 重生成 `verification/sbom.json`，将已分类的 C1-T04 前置 SBOM 漂移记录为已修复治理
   证据；不关闭 `KI-T57-013`，除非本任务实际证据满足其全部高压范围。

## 4. LOCAL_VERIFY / RD_CAMPAIGN 门

提交前运行：

- `python scripts/generate-sbom.py --check`
- `python scripts/check-pre-device-runtime-hardening.py`
- `python tools/static_android_compile.py`
- Provider/fixture/framework/runtime/app targeted lint 与四枚 Debug APK assemble
- `python tools/capability/validate_campaign_infra.py`
- `git diff --check`、JSON evidence parse、供应链检查

设备门：

- `python tools/capability/run_c1_t04_rd.py --instance 'RD测试' --loops 50`
- user0/user1 各完成 50 轮；各轮包含 CRUD、cursor、batch、FD、observer、grant 和
  recovery；并发压力观测至少 1800 秒，失败 0、资源/回调不跨用户。
- 原始 logcat、设备快照、APK SHA-256、命令 JSON 和结构化回执位于
  `verification/catch-up/C1-T04/` 或其索引的 campaign artifacts 目录。
