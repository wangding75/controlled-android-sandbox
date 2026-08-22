# C2-T03 Location 通用能力闭环设计

## 范围与证据边界

本任务只执行 `C2-T03`，目标是 Guest `LocationManager` 在 RD API32 上的标准 API
闭环。设备证据必须通过 MuMu 实例名 `RD测试` 动态解析获得 serial；本文及 runner 不保存
历史 ADB endpoint。VA PRO 等价性、API33+、OEM/HAL 和真实 GNSS 射频能力仍为
`NOT_PROVEN`。

## API / 回调矩阵

| 面 | 入口 | Guest 行为 | 验收证据 |
|---|---|---|---|
| provider | `isLocationEnabled`、`isProviderEnabled`、`getProviders`、`getBestProvider`、`hasProvider` | 由 typed profile、权限/AppOps 和 `BLOCKED` 状态共同决定 | `PROBE` |
| last/current | `getLastKnownLocation`、两种 `getCurrentLocation` | 只返回 profile sample，含 provider、坐标、accuracy、time、elapsed | `PROBE`、`CURRENT`、`CURRENT_REQUEST` |
| updates | listener 的时间/Executor/Looper 路径、single update | 每个注册拥有可撤销 registration，队列任务二次检查 generation/active | `CALLBACK` sequence、时间和注销后零回调 |
| GNSS/NMEA | `registerGnssStatusCallback`、四种 NMEA 注册、对应 remove | 由 profile 的 `gnssEnabled` 和 NMEA sentence 驱动；注册返回值和停止事件可观察 | `GNSS`、`NMEA`、register/unregister |
| PendingIntent | listener/PendingIntent overload、single update PendingIntent | 明确 `VIRTUAL_LOCATION_PENDING_INTENT_UNSUPPORTED`，不创建定时任务、不触发 Host | `NEGATIVE/EXPLICIT_UNSUPPORTED` |
| test provider / inject / geofence | test-provider mutation APIs | 明确拒绝，保持 Host provider state 不可写 | `NEGATIVE/EXPLICIT_UNSUPPORTED` |
| 生命周期 | Activity pause/resume/destroy、Guest generation close | pause 主动 unregister；FrameworkHooks close 关闭 manager scheduler；clear/death 取消 registration | `LIFECYCLE`、`UNREGISTER`、跨 generation logcat |
| 权限与 profile | location permission/AppOps 动态读取；profile 由 DebugCommand 写入 | 权限撤销或 BLOCKED 时 provider 关闭、current 为 null、无 update/GNSS/NMEA | user0 denied/clear phases |

## 实现要点

- `ControlledLocationManager` 对每个 listener、NMEA listener 和 GNSS callback 保存独立的
  active registration。回调提交到 Guest executor 后再次验证 registration，避免注销后已排队
  的 callback 越过边界。
- profile 变为不可用时，周期任务主动移除自身；`close()` 先失效所有 registration、取消
  futures，再关闭 daemon scheduler。`LocationServiceHook` 将 override 与 manager 组成一个
  closeable，Guest generation 结束时两者同时回收。
- location manager 读取 `GuestIdentity.capabilityPolicy()`，因此 permission/AppOps 变化不再
  绕过 public manager。Binder interceptor 的 explicit cleanup 同时释放 capability lease。
- `DebugCommandActivity configure-location` 保留现有 profile 字段，并允许测试显式写入 mode、
  provider、坐标、accuracy、interval、GNSS、satellite counts 和 NMEA sentence，从而可证明
  profile update 而不是固定常量。
- fixture 只记录观察值；host-side runner 负责坐标容差、callback 顺序、计数、注销后尾部、
  negative branch、双用户隔离和 30 分钟压力判断。

## 后台与死亡语义

`LocationCampaignActivity.onPause()` 立即移除 location/NMEA/GNSS 注册；恢复时重新注册，
所以后台策略是“停止 Guest callback”，不是继续向不可见 Activity 投递。Guest process
death/clear 由 runtime generation close 负责 scheduler/lease 回收，runner 在下一 generation
清空 logcat 后重新 launch，禁止把旧 session 的 callback 计入新 session。

## 已知边界

当前实现提供 profile 驱动的 GNSS started/first-fix 和 NMEA 时间/句子 callback；没有伪造
平台 `GnssStatus` 的 satellite object，也不宣称真实 HAL/GNSS 测量能力。该边界记录为
`KI-R03-035`，不阻塞本任务要求的 GNSS/NMEA/time callback 证据。

## 验收入口

```text
python tools/static_android_compile.py
python scripts/check-c2-t03-location.py
python tools/capability/run_c2_t03_rd.py --instance RD测试
```

默认 RD runner 对可用 profile 运行两组 user phase，各保持 30 分钟，并额外运行 permission
denied、profile update、clear 和后台 unregister phase；所有输出写入
`artifacts/capability-audit/catch-up-c2-t03/<timestamp>/`，主回执为
`verification/catch-up/C2-T03/c2-t03-rd-summary.json`。
