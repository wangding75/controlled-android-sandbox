# C3-T02 文件、procfs、网络与 FD 生命周期设计

## 边界与证据模式

C3-T02 的直接 fixture 是 TRUSTED_COMPAT 基线，sandbox fixture 运行在
ISOLATED_HOSTILE 观测模式；RD_BASELINE 结果只证明当前 MuMu RD测试环境，
不代表 Android 33+、OEM commercial matrix、VA Pro 或内核级 hostile isolation。
PASS_COMPAT 只表示该调用在对应模式下完成并满足本 corpus 的约束。

## Corpus

- C3-T02-FS-001 使用 directory FD、relative openat、relative symlink、
  fstatat 与 readlinkat，并要求 sandbox 拒绝 symlink 跳出 Guest root。
- C3-T02-PROC-001 覆盖 maps、smaps、fd、task、cgroup、fdinfo，
  unknown proc leaf、unknown fd/fdinfo 与 /proc/net。已知叶子走虚拟快照；
  未知入口必须拒绝或显式归类，不能用原始宿主路径替代。
- C3-T02-NET-001 记录 getaddrinfo、socket、connect、getsockname 的 rc/errno
  trace。网络是否可达不是把 direct syscall 伪装成隔离证明的理由。
- C3-T02-FD-001 覆盖 dup/dup2/dup3/F_DUPFD、FD_CLOEXEC、关闭前后 proc-fd
  快照；C3-T02-FD-002 使用 SCM_RIGHTS 覆盖传递、登记、关闭和回收；
  C3-T02-FD-003 记录 close-on-exec 及 fork/exec 的策略结果。
- C3-T02-RAW-001 使用 ABI raw syscall/SVC 直接尝试 /proc/net 与 socket。
  成功必须标记 BYPASS_CONFIRMED/UNMEDIATED_DIRECT_SYSCALL_EXPOSED，拒绝必须
  标记 BLOCKED_BY_POLICY；不得静默变成 PASS。

## Authoritative ledger and lifecycle

NativeFdLedger 是 FD ownership、policy revision、virtual path、dup propagation、
SCM_RIGHTS receive 和 close 删除的 authoritative FD ledger。未知 FD 不再因为
/proc/self/fd 可读就自动登记为 Guest；仅 stdio 0/1/2 可作为 inherited
compatibility state，其余未知 inherited/foreign FD 在 proc-fd、fdinfo、fstat 和
基于 FD 的路径操作上 fail closed。policy clear/reset 通过 revision fence 与 ledger
reset 收敛旧句柄；进程 death 由内核关闭句柄，回执另记录 force-stop 后的残留扫描。

验收回执必须同时保存 path/proc corpus、FD 前后快照、network trace、leak scan、
clear/death/exec 结果及 raw/direct syscall 限制说明。没有内核级 syscall mediation
时，raw path 只能作为边界暴露证据，不能宣称 VA Pro 等价或 hostile isolation。
