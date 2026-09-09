# P1-10 大 Intent 与 Binder 载荷边界报告

日期：2026-09-09；状态：**完成（API35/36 `FIXTURE_PASS`）**；前置：P1-09。

参考对照和修改前决策见 [`P1-10_REFERENCE_DECISION.md`](P1-10_REFERENCE_DECISION.md)。
NBB/VA 都以服务端 ActivityRecord/代理 Intent 持有完整 payload；CAS 也应使用单一
wire payload/FD + broker-owned one-time route，而不能在 `executeV2` 包内再复制 extras。

## 首错与修复

API35 首次运行 `p1-10-api35-attempt1-20260909` 保留了首错：270596、283304、
308616 bytes 已到达 fixture，但 1,000,000-byte case 失败为
`ROUTE_PAYLOAD_TOO_LARGE:1018308`。追踪确认 `RuntimeClient.launchComponent` 直连入口
把 raw extras 放进 request Bundle，绕过已有 `RuntimeIntentWireCodec.encodeActivity`；
Broker 控制 envelope 因而达到 512 KiB 边界。

修复将该入口改为与 Guest Activity path 相同的 `encodeActivity`：一个完整 Intent
Parcel，小 payload 内联、大 payload 以受限 FD 跨 Binder，成功 marshal 时绝不附加
第二份 `INTENT_EXTRAS`。没有改变 1 MiB route 上限、SLO、重试或权限。

## 当前实测

| 环境 | 回执 | 正向 payload | 超限 | 结论 |
|---|---|---|---|---|
| API35 x86_64 / 4 KB | `out/verification/p1-10-api35-after-wire-fix-attempt1-20260909/run.json` | 270596、283304、308616、1000000 bytes 均 CRC/长度 PASS | 1048577-byte input -> `INTENT_PAYLOAD_TOO_LARGE:1048780` | PASS |
| API36 x86_64 / 4 KB | `out/verification/p1-10-api36-after-wire-fix-attempt1-20260909/run.json` | 同上 | 1048577-byte input -> `INTENT_PAYLOAD_TOO_LARGE:1048788` | PASS |

每个 case 是独立 `attempt=1`、`retryBudget=0`、`automaticRetryPerformed=false`。
runner 对 `TransactionTooLargeException` fail-closed；fixture 的 `P1_10_PAYLOAD_PASS` 同时
验证 deterministic byte pattern 的长度与 CRC，因此不是仅验证启动/resume。

`tools/static_android_compile.py` 在同一源码修复后通过并执行
`OneTimeRouteStoreSelfTest` 和 `BrokerStateStoreSelfTest`：原子一次消费、owner/kind
不匹配不烧毁 token、过期、跨 generation revoke、并发唯一赢家、route byte/count
边界和消费后的无残留均受覆盖。Broker consume 仍以 user/package/process/generation
的 `RouteOwner` 绑定；这覆盖跨用户/跨代/重复使用均受限的合同。

本项将 KI-R03-057 从“直连 RuntimeClient 仍可重复 extras”这一当前缺口修复为
fixture 范围通过；历史商业 App 的大 Intent 闭环和全量矩阵仍不能由本报告关闭，留给
后续小米/商业任务。最终 APK SHA-256：Host
`8a1063ff618545c0fc6f13d505558273aa12fd61411f4d9874ce5cffd3222585`；fixture
`b849ab18126942c043b137e6fa84672c006a038690422c45d5791f82019c9247`。
