# P1-06 Provider 路由与授权生命周期回执

状态：`DONE`（2026-09-08）。本回执只覆盖 API35/36 x86_64、4 KB AVD 的通用 Provider 路径；不替代小米 ARM64/HyperOS 的 P2 验收。

## 类型与权限矩阵

| 目标类型 | CAS 行为 | 授权/空结果合同 | 证据 |
|---|---|---|---|
| 已声明虚拟 Provider（同包或同一 virtual universe） | virtual descriptor -> single-flight owner 创建 -> Broker holder/IContentProvider | 实际 ContentResolver query/call/openFile；不伪造 holder | `GuestBrokerContentProviderSelfTest`；API35/36 P1-06 campaigns |
| 已解析的 system/updated-system、enabled、exported Host Provider | 保留 physical Host Context/AMS pass-through | 仅合格 system owner；真实 transport 由 AMS 返回 | `GuestContentProviderFrameworkInterceptor.isAllowedHostProvider`；不扩大到普通 Host APK |
| 物理 PMS 无法解析的 authority | interceptor handled `null`，不再 pass-through | SDK 的正常 acquire-null；不制造 ProviderInfo/Binder/grant | `GuestBrokerContentProviderSelfTest` 的 regular 与 modern getContentProvider 形状 |
| 普通 Host、未导出、停用、package 不一致或 virtual collision | fail closed | 保持 `CONTENT_PROVIDER_AUTHORITY_NOT_VIRTUALIZED`，不以 absence 放宽访问 | `isAllowedHostProvider` 的所有 owner 条件；Provider 权限/lifecycle self-tests |

## 生命周期与资源证据

- NBB `GetContentProvider` 和 VA `GetContentProvider` 均为虚拟优先，进程/holder 缺失返回 `null`；CAS 采用该空结果语义，但拒绝其过宽的 Host fallback。完整采用与拒绝理由见 `P1-06_REFERENCE_DECISION.md`。
- 双 authority 解析、不同 authority 重入、同 authority single-flight、close terminal fence 均由 `GuestContentProviderFrameworkInterceptor` 管理。静态 bridge 自测证明同一 authority 只 prepare 一次，且 close 后不能重新发布 holder。
- `UriGrantLifecycleSelfTest`、`ProviderLifecycleCoordinatorSelfTest`、`RuntimeProviderResourceCoordinatorSelfTest` 分别验证 grant 消耗/撤销/换代、Cursor/FD/observer 的 cleanup 与并发 cleanup。静态套件为 165 项、全部通过。

## AVD 定向验收

| API | 设备 | CAS 轮次 | 结果 | 原始回执 |
|---|---|---:|---|---|
| 35 | `emulator-5556`, x86_64, 4 KB, `google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys` | user 0 5 次 + user 1 5 次 | `PASS`，每轮均有 CRUD、Cursor、batch call、FD、grant/revoke、cancel、observer、cross-package、final marker | `out/verification/p1-06-api35-cas-20260908/run.json` |
| 36 | `emulator-5554`, x86_64, 4 KB, `google/sdk_gphone64_x86_64/emu64xa:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys` | user 0 5 次 + user 1 5 次 | `PASS`，同一冻结 APK 与 marker 集 | `out/verification/p1-06-api36-cas-20260908/run.json` |

构建输入 SHA-256：Host `9e1271784ec738c45b85ebf0c43405a81748b8a8ac50c0545f5efd79f7c738ff`；fixture `5899e323ecc54eb704289457cde8dc96afadd0d468b1e14fc2651b72be41511f`；peer fixture `85bd0bde9785ce92b33f9ae5eb640cfc104c47b8d344908f61ac1ccc9c201f97`。

## 保留的首错与范围边界

`out/verification/p1-06-api35-20260908/run.json` 记录首次 native shell 启动被 Android 拒绝：`ProviderCampaignActivity` 是刻意 non-exported 的内部 fixture Activity。该坐标不是 CAS Provider route，未通过把它导出或重试来覆盖；runner 随后明确把 native external baseline 记为 `NOT_APPLICABLE_NON_EXPORTED_FIXTURE_ACTIVITY`，并只执行任务所需的 CAS Guest launch 入口。

本轮附带修复了阻断静态验证的两个非 Provider 行为：静态 Android API 桩补齐真实的 `PackageManager.queryIntentServices(Intent,int)` 声明；shared-library resolver 在同版本证书不匹配时不再错误退到最新版本并误报 version mismatch。两者均保持 fail-closed，未扩大 Provider、PackageManager 或 Host 权限范围。
