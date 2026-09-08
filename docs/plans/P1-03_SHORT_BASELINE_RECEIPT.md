# P1-03 简短回执

状态：已完成（2026-09-08）。

- 修改前已读 VA `PackageParserEx.initApplicationInfoBase`、NBB `BActivityThread.handleBindApplication` 与 `PackageManagerCompat.fixJar`；决策见 `P1-03_REFERENCE_DECISION.md`。
- provider/consumer fixture 使用 CAS 的 `<library>` / optional `<uses-library>` 图。CAS 实测：provider 与 consumer 均 `IMPORTED`，`LibraryProjectionActivity` 为 `LAUNCH_PASS`，且记录 `class=OK resource=OK asset=OK sharedLibraryFiles=OK`。
- 原生直装比较通过，未将原生 optional library 误称为 PMS static-library 结果。
- `GuestSharedLibraryPathResolverSelfTest` 覆盖缺 provider、旧路径、重复路径；domain `SelfTest` 覆盖 required/optional、版本及证书 mismatch。

最终动态证据：`out/verification/p1-03-native-final-20260908`、`out/verification/p1-03-cas-library-final-20260908`。静态证据：`python tools/static_android_compile.py`。真实 Chrome/Trichrome ARM64 保留 P2-02；下一项为 P1-04。
