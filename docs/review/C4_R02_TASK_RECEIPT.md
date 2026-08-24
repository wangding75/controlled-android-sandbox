# C4-R02 任务回执：添加事务、超时与 UI 操作状态机

## 结论

C4-R02 验收 `PASS`。本任务只完成 CAS package mutation 的证据、事务、deadline、单飞、回滚、UI 状态和死亡恢复；未进入 C4-R03、C4-R04、C4-R05、C6 或 OEM 适配。

## 设备与构建

- MuMu 实例按名称动态解析：`RD测试`；本轮解析 endpoint 为 `127.0.0.1:16416`，未在 runner 中硬编码。
- API 32，model `22041211A`，boot ID `d09f0f79-058d-42af-924c-3a99f1429ea4`，ABI `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`。
- 最终 APK：`app/build/outputs/apk/debug/app-debug.apk`；SHA-256 `17138281206690EA5B3C10AD0E0D21FC2C33C8DD7051A2386E0B9464CCB72CC6`。

## 实现与参考映射

设计前已查阅并记录 NBB/VA 的安装、启动、进程和窗口实现，映射见 `docs/review/C4_R02_PACKAGE_MUTATION_TRANSACTION_DESIGN.md` 与 `verification/catch-up/C4-R02/reference-mapping.json`。采纳了单飞、原子 catalog 提交、staging 清理、显式进程恢复和阶段可观测性；不采纳固定 sleep、宽泛重试或用夸克结果外推其他商业样本。

生产实现包括：package/user 单飞 coordinator；request/operation trace；COPY/HASH/PARSE/NATIVE_EXTRACT/PUBLISH/CATALOG/ENSURE_INSTANCE 分段计时与 deadline；导入+首实例单次 catalog 提交；单次 connector 获取；失败清理；UI 按钮禁用和 elapsed/stage/request 展示；并发 `MUTATION_BUSY`；Host/PackageService 进程死亡恢复。

## 商业样本与矩阵

| 样本 | 动态 package/version | base/split | ABI | 结果 |
|---|---|---:|---|---:|
| fixture | `com.warden.controlledsandbox.fixture` / `1.0-fixture` | 1/0 | x86_64 | 50× add/delete/re-add，150/150 |
| 钉钉 | `com.alibaba.android.rimet` / `7.8.10` / 1178 | 1/0 | arm64-v8a | 10×，30/30 |
| 夸克（仅正向对照） | `com.quark.browser` / `10.10.5.1080` / 1080 | 1/0 | arm64-v8a | 10×，30/30 |
| 红果免费短剧 | `com.phoenix.read` / `7.0.5.33` / 70533 | 1/0 | arm64-v8a | 10×，31/31；记录 `MIXED_ELF_MACHINE` |
| 番茄免费小说 | `com.dragon.read` / `7.1.9.32` / 71932 | 1/0 | arm64-v8a | 10×，31/31；记录 `MIXED_ELF_MACHINE` |

红果和番茄的 `lib/arm64-v8a/libcvt.so` 是已知目标 ARM32 ELF，导入时记录 anomaly，不再以目录名拒绝。夸克只作为正向对照，未用于判定红果或番茄运行时兼容；运行时是否请求该混合 payload 留给 C4-R03。

## 失败、重试与恢复

- 主矩阵产品操作 272 条，失败 0；每条 attempt=1、retryBudget=0，未通过重试覆盖首次失败。
- 并发添加：1 个成功、1 个 `MUTATION_BUSY`，两者引用同一 operation ID。
- 未授权 native 负测：稳定错误 `UNTRUSTED_NATIVE_GUEST_DENIED`，attempt=1、retryBudget=0、`NO_RETRY_NON_RETRYABLE_SECURITY_POLICY`，staging 和事务残留扫描通过。
- Host 进程死亡与 PackageService 进程死亡均观察到旧 PID 消失、新进程恢复，revision SHA 保持不变；恢复操作 attempt=1、retryBudget=0。
- 首次负测断言错误已单独保留为 `negative-rollback-first-observation.json`，分类为 harness assertion false negative；修正权威嵌套 `errorMessage` 后复测通过。

## Known Issues 裁决

`KI-R03-055` 已更新为 `FIXED`，仅表示 CAS 导入兼容性门通过；`KI-R03-053`（黑屏）、`KI-R03-054`（启动 phase telemetry）和 `KI-R03-056`（旧 launch fail-closed runner）继续 `RECORDED` 且阻断 C4，分别由 C4-R03/R04/R05 处理。C4 尚未关闭。

完整机器可读汇总见 `verification/catch-up/C4-R02/acceptance-summary.json`。
