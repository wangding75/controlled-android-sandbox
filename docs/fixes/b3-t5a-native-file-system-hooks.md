# B3-T5A Native 文件系统 Hook 完整化

## 范围

本阶段只完善 Guest 原生库发起的文件系统调用：

- `open/open64/openat/openat64`
- Bionic fortified `__open_2/__openat_2`
- `access/faccessat`
- `stat/lstat/fstatat`
- `readlink/readlinkat`

不在本阶段扩展网络 Hook、动态加载器 Hook、32 位 ABI 或设备验证。

## 路径模型

Native Policy 绑定：

- Session ID
- generation
- packageName
- virtualUserId
- instance root
- APK 文件
- Guest native library root

配置未建立、Session 冲突、generation 回退或同一 Session 身份变化时均 fail-closed。

支持的路径映射：

- `/data/data/<package>`
- `/data/user/<virtualUserId>/<package>`
- `/data/user/0/<package>`
- `/data/user_de/<virtualUserId>/<package>`
- `/storage/emulated/<virtualUserId>/Android/data/<package>`
- `/data/app/.../<package>-.../base.apk`
- `/data/app/.../<package>-.../lib/<abi>/...`
- `/data/data/<package>/lib/...`

其他包的私有 data、external Android/data、Android/obb 和 APK 路径被拒绝。

## 相对路径与 dirfd

相对路径通过以下顺序解析：

1. `AT_FDCWD` 使用当前工作目录；
2. 其他 `dirfd` 先 `fstat` 验证必须是目录；
3. 通过 `/proc/self/fd/<dirfd>` 获取实际目录；
4. 将宿主实例路径反向映射为 Guest 路径；
5. 应用同一 Native Policy 后再执行真实 syscall。

错误 `EBADF`、`ENOTDIR`、`ENOENT`、`ENAMETOOLONG` 和 `EFAULT` 保持稳定。

## Symlink 与路径穿越

- 对 Guest 私有前缀的 `..` 逃逸直接拒绝；
- 跟随最终 symlink 的调用会校验真实目标仍处于 confinement root；
- `lstat/readlink` 允许检查最终 symlink，但中间 symlink 不得逃离实例根；
- `/proc/self/fd` 和 `/proc/self/cwd` 返回的宿主实例路径会反向映射，避免泄露宿主目录；
- `/proc/self/mem`、`pagemap`、`clear_refs` 和 `syscall` 被拒绝。

当前 symlink 校验使用真实路径预检；设备阶段仍需验证 Android 内核时序与竞态边界。

## PLT/GOT Hook

仅对 Guest native library root 下加载的 ELF 模块执行 PLT/GOT rebinding：

- 仅处理各 ABI 的 `JUMP_SLOT/GLOB_DAT`；
- 修改前读取 `/proc/self/maps` 中的原始页权限；
- 修改后恢复原权限，不再无条件改为只读；
- 任意 relocation 写保护失败都会使 refresh 失败；
- Policy revision 变化后必须重新 install，旧 refresh 会拒绝。

## 生产接线

Guest 准备阶段将 Session、generation、APK、data root 和 native library root 一并传入 JNI。存在 Guest native library 时：

- Policy 不可用：Guest 准备失败；
- Hook install 失败：Guest 准备失败；
- Application、Service 或 Provider 加载新原生库后 refresh 失败：对应操作失败。

Session 结束和 Guest 准备失败路径均先 reset hooks，再 reset policy。

## 测试

- Native Policy 映射、隔离、generation 和并发读测试；
- dirfd、相对路径、symlink 逃逸、readlink 反向映射和 errno 测试；
- 真实测试 `.so` 的 PLT relocation patch；
- 通过测试 `.so` 实际调用 open/openat/access/stat/lstat/readlink；
- 跨包、symlink 逃逸和 `/proc/self/mem` 拒绝；
- Policy revision 切换后 refresh 拒绝与 reinstall。

模拟器、真机、Android linker、SELinux 和 OEM 行为继续保持 `not-tested`。
