# P1-03 共享库用例矩阵

执行日期：2026-09-08。P1-03 的普通 provider/consumer 是 CAS 虚拟 Java-library 图，不把普通 APK 误称为 Android PMS static/SDK library；真实 Chrome/Trichrome ARM64 仍为 P2-02 范围。

| 用例 | 输入/前置 | 预期 fail-closed 或成功语义 | 证据 |
|---|---|---|---|
| 原生 provider 投影 | 直装 provider、consumer（consumer 的 `uses-library` 为 optional） | provider 类、字符串资源、asset 可读；原生缺 provider 维持平台的 `null`/`IllegalArgumentException` 表达 | `out/verification/p1-03-native-final-20260908/run.json`：PASS，6 个 marker |
| CAS virtual provider 投影 | 先 `import-only` provider，再 `import-only` consumer，再启动 `LibraryProjectionActivity` | consumer 自身 ClassLoader 可加载 provider 类；`ApplicationInfo.sharedLibraryFiles` 非空、每项可用且 canonical 去重；provider 资源与 asset 可读 | `out/verification/p1-03-cas-library-final-20260908/run.json`：三步 `IMPORTED`/`IMPORTED`/`LAUNCH_PASS`；`logcat.txt` 含 `VIRTUAL_LIBRARY_PROJECTION_PASS` |
| 不存在 provider authority | consumer 在 CAS 调用物理 PMS 也无法解析的 authority | 不得掉回宿主；CAS 截获并返回 `null` holder，供 SDK 按正常 absence 分支处理 | P1-06 `GuestBrokerContentProviderSelfTest` 断言 regular/modern `getContentProvider` 均为 intercepted null |
| 缺失 virtual provider | 已解析依赖但 package universe 中无 provider projection | `SHARED_LIBRARY_PROVIDER_PROJECTION_MISSING:<package>` | `GuestSharedLibraryPathResolverSelfTest` |
| provider 更新后的旧路径 | `VirtualSharedLibrarySnapshot` 指向不存在的旧 APK | `SHARED_LIBRARY_PROVIDER_APK_UNAVAILABLE:<package>`；不得寻找 Host 回退路径 | `GuestSharedLibraryPathResolverSelfTest` |
| 重复 provider source | source projection 内含同一 canonical APK 两次 | `sharedLibraryFiles` 仅保留一次 | `GuestSharedLibraryPathResolverSelfTest`；fixture 同时检查运行时数组无重复 |
| required/optional、版本不符 | `SharedLibraryResolver` 有 SDK v3/v5，要求 v4 或 optional 不存在项 | required 返回 `version mismatch`，optional 保留未解析状态而不阻断 | `sandbox-domain ... SelfTest.testSharedLibraryResolution` |
| 证书不符 | 正确名称/版本、不同 SHA-256 cert digest | required 返回 `certificate mismatch` | `sandbox-domain ... SelfTest.testSharedLibraryResolution` |

不新增包名特判、普通 provider 宿主私有目录或合成 `sharedLibraryFiles`。NBB 的 Apache legacy `fixJar` 限制与 VA 的 PMS 投影方法见 `P1-03_REFERENCE_DECISION.md`。
