# C2-T07 Application Environment and Long-Tail RD API32 Design

## Scope and evidence boundary

本文档定义 `C2-T07` 的 package-neutral 设备追赶活动。范围按
`C2_T01_SYSTEM_SERVICE_METHOD_CATALOG.md` 收敛到动态解析的 MuMu `RD测试` API32
设备，覆盖 C2-T01-F1-12/F1-14 的 User、Launcher、Shortcut、AppWidget、UsageStats、
Settings/Content 方法族，以及 C2-T01-P2 的 Biometric、DevicePolicy、Autofill、NFC、
USB、Print、Companion、SensorPrivacy、Power、Vibrator、Search、StorageStats、
SystemUpdate、ContextHub、GraphicsStats、PersistentDataBlock、SMS/Captioning 等
长尾边界。

通过的结果只表示 `RD_BASELINE`/`RD_API32_L3`，不宣称 Android Matrix、API33+、OEM/HAL、
ARM/16 KB、商业应用、SX/XH 或 VA PRO 等价性。fixture 只使用 Guest 公共 API 和必要的
反射适配，不加入包名专用生产分支；Host 真值只用于验证“不应出现”。设备端点必须由
`RD测试` 实例名动态解析，runner 不保存固定 ADB serial 或端口。

## DISCOVER / CLASSIFY

任务前按治理文件完成 collect-all 诊断，证据目录为
`artifacts/capability-audit/all/20260822T194431Z`；结果为 34 个 PASS、8 个既有
`KNOWN_ISSUE`、0 个新增回归，诊断非零项均已按现有治理分类。M5-T11 应用环境门禁和
`static_android_compile.py` 自测通过；直接把 Java 文件交给 Python unittest 的尝试是
错误的 harness 调用，不能作为运行时失败，已由模块级静态编译与自测替代。

现有 `ApplicationEnvironmentStore`、环境 hook、P2 typed profile、Host self-test 和
`CAPABILITY_REGISTRY.yaml` 的静态边界已经存在，但缺少一个 package-neutral RD fixture
来关联 request/return、observer/callback、注销/cleanup、跨虚拟用户隔离和进程死亡。
该证据缺口分类为 `KI-R03-041`。P2 方法若没有可安全投影的 Android 对象，必须记录
`NOT_SUPPORTED`/安全拒绝或 `NOT_APPLICABLE`，不得回退 Host，也不得把长尾的 profile
返回冒充 VA PRO 兼容。

## Method and boundary matrix

The campaign treats cross-user separation as a required gate and never promotes a single-user
result to a VA PRO claim.

| 家族 | RD 请求与返回证据 | 生命周期/负向边界 | 通过条件 |
|---|---|---|---|
| User | user handle、serial、name、user/profile list、running/unlocked/restriction | 不出现 Host user；mutation 明确拒绝 | 返回与 Guest profile 一致 |
| Launcher | package/activity visibility、activity list、callback 注册/注销 | 不可见包返回空/false；注销后 listener lease 为零 | 可见性和 callback 生命周期闭环 |
| Shortcut | dynamic shortcut add/list/report/remove、rate-limit query | static 模式、包归属和 remove cleanup | ID、包和数量隔离，移除后为空 |
| AppWidget | allocate/list/info/bind/delete（默认 disabled 时为空/false） | host-only/disabled mutation 不泄露 Host widget | 默认 profile 的显式边界可审计 |
| UsageStats | report/query events、query stats、standby bucket | mutation 拒绝；事件只属于 Guest 包/user | query 返回 Guest 事件，数量有界 |
| Settings/Content | Secure/System/Global get/put/delete 与 observer register/notify/unregister | blocked key/global write 拒绝；observer 注销和进程死亡收敛 | namespace 投影、通知和 cleanup 一致 |
| Biometric/DevicePolicy/Autofill | service 可见性、profile getter 和安全负向调用 | callback adapter 或 mutation 不支持时 fail-closed | 不生成假认证/设备所有权 |
| NFC/USB/Print/Companion | service 可见性、查询/会话入口 | session/association/callback 未支持时 explicit negative | 不出现 Host peripheral/session |
| SensorPrivacy/Power/Vibrator | profile getter、listener/lease 与禁止 mutation | reboot/shutdown/隐私 mutation/振动超限拒绝 | profile 值和边界稳定 |
| Search/StorageStats/SystemUpdate/ContextHub/GraphicsStats/PersistentDataBlock | 可投影 query 返回或 explicit unsupported | write/wipe/mutation 和对象 adapter fail-closed | 不把 Host 状态作为 Guest 返回 |
| SMS/Captioning/其余 P2 | service lookup 与安全负向矩阵 | 未覆盖真实产品命中时 `NOT_APPLICABLE` 或 `NOT_SUPPORTED` | 无 ownerless `UNKNOWN` |

## Campaign phases

1. 动态解析 `RD测试`，安装锁定的 Host/Companion/fixture APK，执行 `import-prepare`，
   获取设备/API/ABI/boot-id/android-id 快照；清理 Host 数据并为 user 0 与 clone user
   设置权限。
2. 运行 `C2T07ApplicationEnvironmentActivity` full probe：完成 environment getter、
   shortcut/usage/settings/content request/return，注册并注销 ContentObserver 和
   Launcher callback，写入并删除 Guest 文件，记录每个 marker 的 request、返回摘要、
   profile hash、user id 和生命周期计数。
3. 运行 bounded loop，重复 getter、shortcut、usage、settings observer 和 cleanup；
   对 Host identity、Global setting mutation、long-tail biometric/policy/peripheral/
   privileged mutation 做负向调用，要求错误码/异常包含 `VIRTUAL_` 或明确
   `NOT_SUPPORTED`，且不返回 Host 值。
4. 在独立 virtual user 上重复 profile probe，要求 profile hash 与 user 0 分离；执行
   arm/kill/restart/death cleanup，确认替代进程可重新建立 observer/shortcut/usage 状态，
   不残留旧 Guest 文件、listener 或 callback marker。
5. 保存 raw logcat、command result、设备快照、APK hash、marker 计数和结构化回执到
   `artifacts/capability-audit/catch-up-c2-t07/` 与 `verification/catch-up/C2-T07/`。

## Acceptance and failure policy

runner 对缺失 required marker、profile hash 串值、Host identity 泄漏、负向调用未拒绝、
observer/callback 未注销、FATAL/ANR 或 death residue fail closed。确定性的 runner 或
实现缺陷先修复并重跑；只有需要人工恢复外部设备/权限且安全范围内无法继续时才记录
`BLOCKED`。证据中的 `va_pro_equivalent` 固定为 `NOT_PROVEN`，P2 与商业 VA PRO 的
未覆盖项必须保留为 `NOT_SUPPORTED`/`NOT_APPLICABLE`/`UNVERIFIED` 的明确边界。
