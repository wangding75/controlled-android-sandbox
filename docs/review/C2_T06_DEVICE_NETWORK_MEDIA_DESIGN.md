# C2-T06 Telephony、Wi-Fi、Connectivity、Audio、Bluetooth、Sensor RD API32 Design

## Scope and evidence boundary

本文档定义 `C2-T06` 的 package-neutral 设备追赶活动。范围按 C2-T01 方法目录的
F4/F5 P0/P1 条目收敛到动态解析的 MuMu `RD测试` API32 设备；通过的结果只表示
`RD_BASELINE`/`RD_API32_L3`，不宣称 Android Matrix、OEM、ARM、16 KB、商业应用、
SX/XH 或 VA PRO 等价性。

本任务复用现有的 typed device/network/media profile、GuestIdentity 的 capability
lease、GuestNetworkState，以及已有的 system-service Binder hook。fixture 只使用
Guest 公共 API/反射适配，不加入包名专用生产分支；Host 真值只用于验证“不应出现”，
不作为 Guest 返回值来源。

## DISCOVER / CLASSIFY

任务前按治理文件运行了 collect-all 诊断，证据目录为
`artifacts/capability-audit/all/20260822T180556Z`。本次诊断结果为 42 个 gate 中
33 PASS、8 个既有 `KNOWN_ISSUE`、1 个 `NEW_REGRESSION`、9 个非零历史门禁，
未将这些结果直接当作 C2-T06 运行失败。唯一的新发现是 C2-T05 增加的两个
fixture-basic 文件没有同步生成 SBOM，已分类为 `KI-R03-040`，并在本任务中修复。

现有 typed profile、默认值、service hook 和 framework self-test 已覆盖基本同步
返回值，但没有一个 package-neutral RD fixture 将每个域的 request/return/callback/
permission/death/cleanup 串成可审计证据。该范围缺口分类为 `KI-R03-039`；本任务
只关闭 API32 RD campaign 的方法级证据，不扩张为 Host OEM/HAL 或 VA PRO 兼容承诺。

## Typed method matrix

| 域 | Guest request | 必须记录的返回/回调 | 注销、死亡与权限边界 |
|---|---|---|---|
| Identity/Telephony | Android ID/Build/serial、slot/subscription、IMEI/MEID/IMSI/ICCID、operator、cell | typed identity/subscription/cell 与 profile hash；telephony registration 的 Guest callback/negative result | Host identity 不出现；撤销权限后读请求 fail-closed；clear/restart 后 profile revision 仍一致 |
| Wi-Fi | enabled/state、connection/DHCP/MAC、scan results、startScan | typed connection/scan 与 scan request result | Guest profile 隔离；MAC/SSID 不来自 Host；权限撤销和 profile clear 可复核 |
| Connectivity | active/default network、capabilities、link properties、register/request callback | `onAvailable`、capabilities、link-properties、blocked callback 顺序与 network id | unregister 释放 callback lease；Guest death 清空 callback/request；Host 网络不泄露 |
| DNS/VPN | resolver servers/query/error、VPN state/config/establish/stop | profile DNS answer/NXDOMAIN；VPN 状态及 bounded session 返回 | raw DNS、Host VPN mutation 明确拒绝；stop/death 收敛 session |
| Audio/Media | route/mode/volume、audio focus request/abandon、router/session readback | typed route/focus return；session/router registration 的 bounded ownership | abandon/unregister/release 后 ownership 为零；capture/Host audio 仍按原有边界处理 |
| Bluetooth | enabled/state/name/address、bonded/remote device、discovery | typed adapter/device 值；discovery 的 explicit unsupported/false negative boundary | Host adapter/address 不出现；mutation 和未支持 callback fail-closed；Binder death 不留 lease |
| Sensor | list/default、register、event、flush、unregister | typed sensor list 与 profile values；至少一个 event callback 及序号 | flush/unregister 取消 Guest lease；死亡/clear 后没有继续投递 |

`RD_API32_L3` 的“callback”允许两类有界结果：已有 hook 能投递的 typed Guest
callback，或对当前 API/返回类型明确记录的 `NOT_SUPPORTED`/安全拒绝。后一类不得
被伪装成 PASS，也不得回退到 Host callback。

## Campaign phases

1. 以实例名 `RD测试` 动态解析设备，安装锁定的 Host/Companion/fixture APK，执行
   `import-prepare`，获取设备/API/ABI/boot-id/android-id 快照；runner 不保存固定
   ADB serial。
2. 运行一个完整 probe，记录 identity、Telephony、Wi-Fi、Connectivity、DNS/VPN、
   Audio/Media、Bluetooth、Sensor 的 method marker、typed 值摘要和 callback 顺序。
   每轮都显式释放 callback/focus/session/sensor 资源。
3. 重复有界 loop，验证 getter 与 callback 的 profile hash/网络 id 一致，并检查
   callback/lease 计数随 unregister 收敛；对权限撤销执行一轮负面调用，确认不返回
   Host 真值。
4. 对 Guest user 0 和一个独立的 virtual-user profile 执行相同 identity/network
   probe，比较 profile hash，禁止跨用户复用；随后 clear/restart，再检查 callback
   和 VPN/sensor/focus ownership 收敛。
5. 保存 raw logcat、command result、设备快照、APK hash、profile/callback corpus 与
   结构化回执到 `artifacts/capability-audit/catch-up-c2-t06/` 和
   `verification/catch-up/C2-T06/`。

## Acceptance and failure policy

runner 对缺少 required marker、callback 顺序/identity 不一致、权限负面泄露、跨用户
profile 串值、FATAL/ANR 或 cleanup residue fail closed。发现 runner 或确定性实现缺陷
时先修复并重跑；只有需要人工恢复外部设备/权限且安全范围内无法继续时才记录
`BLOCKED`。证据中的 `va_pro_equivalent` 固定为 `NOT_PROVEN`。

The runner treats cross-user profile separation as a required gate and never promotes a
single-user result to a cross-user or VA PRO claim.
