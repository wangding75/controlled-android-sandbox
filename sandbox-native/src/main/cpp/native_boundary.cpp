#include "controlled_sandbox/native_boundary.h"

#include "controlled_sandbox/native_policy.h"

#include <cerrno>
#include <climits>
#include <cstdint>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <unistd.h>

namespace controlled_sandbox {
namespace {

#ifndef PR_GET_NO_NEW_PRIVS
#define PR_GET_NO_NEW_PRIVS 39
#endif
#ifndef PR_GET_SECCOMP
#define PR_GET_SECCOMP 21
#endif
#ifndef PR_SET_SECCOMP
#define PR_SET_SECCOMP 22
#endif
#ifndef PR_SET_NO_NEW_PRIVS
#define PR_SET_NO_NEW_PRIVS 38
#endif
#ifndef SECCOMP_SET_MODE_STRICT
#define SECCOMP_SET_MODE_STRICT 0
#endif
#ifndef SECCOMP_SET_MODE_FILTER
#define SECCOMP_SET_MODE_FILTER 1
#endif
#ifndef SECCOMP_GET_ACTION_AVAIL
#define SECCOMP_GET_ACTION_AVAIL 2
#endif
#ifndef SECCOMP_GET_NOTIF_SIZES
#define SECCOMP_GET_NOTIF_SIZES 3
#endif

NativePolicySnapshot policy_snapshot() noexcept {
    try {
        return global_policy().snapshot();
    } catch (...) {
        return {};
    }
}

bool same(long number, long value) noexcept { return number == value; }

}  // namespace

long trusted_syscall6(long number, long a0, long a1, long a2, long a3, long a4,
                      long a5) noexcept {
    // This path is reserved for CAS-owned policy code after it has already
    // resolved a host/capability path.  Keep it outside the Guest PLT/GOT
    // dispatch so procfs materialization and FD bookkeeping cannot recurse
    // through controlled_syscall.  The raw result is normalized to libc's
    // errno contract before returning to callers.
    long result;
#if defined(__x86_64__)
    register long r10 asm("r10") = a3;
    register long r8 asm("r8") = a4;
    register long r9 asm("r9") = a5;
    asm volatile("syscall"
            : "=a"(result)
            : "a"(number), "D"(a0), "S"(a1), "d"(a2), "r"(r10), "r"(r8), "r"(r9)
            : "rcx", "r11", "memory");
#elif defined(__aarch64__)
    register long x8 asm("x8") = number;
    register long x0 asm("x0") = a0;
    register long x1 asm("x1") = a1;
    register long x2 asm("x2") = a2;
    register long x3 asm("x3") = a3;
    register long x4 asm("x4") = a4;
    register long x5 asm("x5") = a5;
    asm volatile("svc #0"
            : "+r"(x0)
            : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5)
            : "memory");
    result = x0;
#elif defined(__i386__)
    asm volatile(
            "push %%ebp\n\t"
            "movl %7, %%ebp\n\t"
            "int $0x80\n\t"
            "pop %%ebp"
            : "=a"(result)
            : "a"(number), "b"(a0), "c"(a1), "d"(a2), "S"(a3), "D"(a4), "g"(a5)
            : "memory");
#elif defined(__arm__)
    register long r0 asm("r0") = a0;
    register long r1 asm("r1") = a1;
    register long r2 asm("r2") = a2;
    register long r3 asm("r3") = a3;
    register long r4 asm("r4") = a4;
    register long r5 asm("r5") = a5;
    asm volatile(
            "push {r7}\n\t"
            "mov r7, %[nr]\n\t"
            "svc #0\n\t"
            "pop {r7}"
            : "+r"(r0)
            : [nr] "r"(number), "r"(r1), "r"(r2), "r"(r3), "r"(r4), "r"(r5)
            : "memory");
    result = r0;
#else
    return ::syscall(number, a0, a1, a2, a3, a4, a5);
#endif
    if (result < 0 && result >= -4095) {
        errno = static_cast<int>(-result);
        return -1;
    }
    return result;
}

NativeSyscallRule NativeSyscallPolicy::lookup(long number) noexcept {
#ifdef SYS_open
    if (same(number, SYS_open)) return {NativeSyscallAction::Project, "open"};
#endif
#ifdef SYS_openat
    if (same(number, SYS_openat)) return {NativeSyscallAction::Project, "openat"};
#endif
#ifdef SYS_openat2
    if (same(number, SYS_openat2)) return {NativeSyscallAction::Project, "openat2"};
#endif
#ifdef SYS_access
    if (same(number, SYS_access)) return {NativeSyscallAction::Project, "access"};
#endif
#ifdef SYS_faccessat
    if (same(number, SYS_faccessat)) return {NativeSyscallAction::Project, "faccessat"};
#endif
#ifdef SYS_faccessat2
    if (same(number, SYS_faccessat2)) return {NativeSyscallAction::Project, "faccessat2"};
#endif
#ifdef SYS_stat
    if (same(number, SYS_stat)) return {NativeSyscallAction::Project, "stat"};
#endif
#ifdef SYS_lstat
    if (same(number, SYS_lstat)) return {NativeSyscallAction::Project, "lstat"};
#endif
#ifdef SYS_newfstatat
    if (same(number, SYS_newfstatat)) return {NativeSyscallAction::Project, "newfstatat"};
#endif
#ifdef SYS_fstat
    if (same(number, SYS_fstat)) return {NativeSyscallAction::Project, "fstat"};
#endif
#ifdef SYS_statx
    if (same(number, SYS_statx)) return {NativeSyscallAction::Project, "statx"};
#endif
#ifdef SYS_readlink
    if (same(number, SYS_readlink)) return {NativeSyscallAction::Project, "readlink"};
#endif
#ifdef SYS_readlinkat
    if (same(number, SYS_readlinkat)) return {NativeSyscallAction::Project, "readlinkat"};
#endif
#ifdef SYS_getdents
    if (same(number, SYS_getdents)) return {NativeSyscallAction::Project, "getdents"};
#endif
#ifdef SYS_getdents64
    if (same(number, SYS_getdents64)) return {NativeSyscallAction::Project, "getdents64"};
#endif
#ifdef SYS_rename
    if (same(number, SYS_rename)) return {NativeSyscallAction::Project, "rename"};
#endif
#ifdef SYS_renameat
    if (same(number, SYS_renameat)) return {NativeSyscallAction::Project, "renameat"};
#endif
#ifdef SYS_renameat2
    if (same(number, SYS_renameat2)) return {NativeSyscallAction::Project, "renameat2"};
#endif
#ifdef SYS_unlink
    if (same(number, SYS_unlink)) return {NativeSyscallAction::Project, "unlink"};
#endif
#ifdef SYS_unlinkat
    if (same(number, SYS_unlinkat)) return {NativeSyscallAction::Project, "unlinkat"};
#endif
#ifdef SYS_mkdir
    if (same(number, SYS_mkdir)) return {NativeSyscallAction::Project, "mkdir"};
#endif
#ifdef SYS_mkdirat
    if (same(number, SYS_mkdirat)) return {NativeSyscallAction::Project, "mkdirat"};
#endif
#ifdef SYS_rmdir
    if (same(number, SYS_rmdir)) return {NativeSyscallAction::Project, "rmdir"};
#endif
#ifdef SYS_chdir
    if (same(number, SYS_chdir)) return {NativeSyscallAction::Project, "chdir"};
#endif
#ifdef SYS_fchdir
    if (same(number, SYS_fchdir)) return {NativeSyscallAction::Project, "fchdir"};
#endif
#ifdef SYS_fchmod
    if (same(number, SYS_fchmod)) return {NativeSyscallAction::Project, "fchmod"};
#endif
#ifdef SYS_fchmodat
    if (same(number, SYS_fchmodat)) return {NativeSyscallAction::Project, "fchmodat"};
#endif
#ifdef SYS_chmod
    if (same(number, SYS_chmod)) return {NativeSyscallAction::Project, "chmod"};
#endif
#ifdef SYS_fchown
    if (same(number, SYS_fchown)) return {NativeSyscallAction::Project, "fchown"};
#endif
#ifdef SYS_fchownat
    if (same(number, SYS_fchownat)) return {NativeSyscallAction::Project, "fchownat"};
#endif
#ifdef SYS_chown
    if (same(number, SYS_chown)) return {NativeSyscallAction::Project, "chown"};
#endif
#ifdef SYS_truncate
    if (same(number, SYS_truncate)) return {NativeSyscallAction::Project, "truncate"};
#endif
#ifdef SYS_ftruncate
    if (same(number, SYS_ftruncate)) return {NativeSyscallAction::Project, "ftruncate"};
#endif
#ifdef SYS_mmap
    if (same(number, SYS_mmap)) return {NativeSyscallAction::Project, "mmap"};
#endif
#ifdef SYS_munmap
    if (same(number, SYS_munmap)) return {NativeSyscallAction::Project, "munmap"};
#endif
#ifdef SYS_getpid
    if (same(number, SYS_getpid)) return {NativeSyscallAction::Project, "getpid"};
#endif
#ifdef SYS_getppid
    if (same(number, SYS_getppid)) return {NativeSyscallAction::Project, "getppid"};
#endif
#ifdef SYS_gettid
    if (same(number, SYS_gettid)) return {NativeSyscallAction::Project, "gettid"};
#endif
#ifdef SYS_getuid
    if (same(number, SYS_getuid)) return {NativeSyscallAction::Project, "getuid"};
#endif
#ifdef SYS_geteuid
    if (same(number, SYS_geteuid)) return {NativeSyscallAction::Project, "geteuid"};
#endif
#ifdef SYS_getgid
    if (same(number, SYS_getgid)) return {NativeSyscallAction::Project, "getgid"};
#endif
#ifdef SYS_getegid
    if (same(number, SYS_getegid)) return {NativeSyscallAction::Project, "getegid"};
#endif
#ifdef SYS_prctl
    if (same(number, SYS_prctl)) return {NativeSyscallAction::Project, "prctl"};
#endif
#ifdef SYS_ptrace
    if (same(number, SYS_ptrace)) return {NativeSyscallAction::Deny, "ptrace"};
#endif
#ifdef SYS_clone
    if (same(number, SYS_clone)) return {NativeSyscallAction::Project, "clone"};
#endif
#ifdef SYS_clone3
    if (same(number, SYS_clone3)) return {NativeSyscallAction::Project, "clone3"};
#endif
#ifdef SYS_fork
    if (same(number, SYS_fork)) return {NativeSyscallAction::Deny, "fork"};
#endif
#ifdef SYS_vfork
    if (same(number, SYS_vfork)) return {NativeSyscallAction::Deny, "vfork"};
#endif
#ifdef SYS_execve
    if (same(number, SYS_execve)) return {NativeSyscallAction::Deny, "execve"};
#endif
#ifdef SYS_execveat
    if (same(number, SYS_execveat)) return {NativeSyscallAction::Deny, "execveat"};
#endif
#ifdef SYS_seccomp
    if (same(number, SYS_seccomp)) return {NativeSyscallAction::Deny, "seccomp"};
#endif
#ifdef SYS_dup
    if (same(number, SYS_dup)) return {NativeSyscallAction::Project, "dup"};
#endif
#ifdef SYS_dup2
    if (same(number, SYS_dup2)) return {NativeSyscallAction::Project, "dup2"};
#endif
#ifdef SYS_dup3
    if (same(number, SYS_dup3)) return {NativeSyscallAction::Project, "dup3"};
#endif
#ifdef SYS_fcntl
    if (same(number, SYS_fcntl)) return {NativeSyscallAction::Project, "fcntl"};
#endif
#ifdef SYS_ioctl
    if (same(number, SYS_ioctl)) return {NativeSyscallAction::PassThrough, "ioctl"};
#endif
#ifdef SYS_close
    if (same(number, SYS_close)) return {NativeSyscallAction::Project, "close"};
#endif
    return {NativeSyscallAction::PassThrough, {}};
}

bool NativeSyscallPolicy::is_projected(long number) noexcept {
    return lookup(number).action == NativeSyscallAction::Project;
}

bool NativeSyscallPolicy::is_denied(long number) noexcept {
    return lookup(number).action == NativeSyscallAction::Deny;
}

pid_t NativeProcessIdentity::host_pid() noexcept {
#ifdef SYS_getpid
    return static_cast<pid_t>(trusted_syscall6(SYS_getpid));
#else
    return ::getpid();
#endif
}

pid_t NativeProcessIdentity::host_ppid() noexcept {
#ifdef SYS_getppid
    return static_cast<pid_t>(trusted_syscall6(SYS_getppid));
#else
    return ::getppid();
#endif
}

pid_t NativeProcessIdentity::host_tid() noexcept {
#ifdef SYS_gettid
    return static_cast<pid_t>(trusted_syscall6(SYS_gettid));
#else
    return host_pid();
#endif
}

uid_t NativeProcessIdentity::host_uid() noexcept {
#ifdef SYS_getuid
    return static_cast<uid_t>(trusted_syscall6(SYS_getuid));
#else
    return ::getuid();
#endif
}

uid_t NativeProcessIdentity::host_euid() noexcept {
#ifdef SYS_geteuid
    return static_cast<uid_t>(trusted_syscall6(SYS_geteuid));
#else
    return ::geteuid();
#endif
}

gid_t NativeProcessIdentity::host_gid() noexcept {
#ifdef SYS_getgid
    return static_cast<gid_t>(trusted_syscall6(SYS_getgid));
#else
    return ::getgid();
#endif
}

gid_t NativeProcessIdentity::host_egid() noexcept {
#ifdef SYS_getegid
    return static_cast<gid_t>(trusted_syscall6(SYS_getegid));
#else
    return ::getegid();
#endif
}

pid_t NativeProcessIdentity::guest_pid() noexcept {
    const NativePolicySnapshot policy = policy_snapshot();
    return policy.configured ? static_cast<pid_t>(policy.virtual_pid) : host_pid();
}

pid_t NativeProcessIdentity::guest_ppid() noexcept {
    const NativePolicySnapshot policy = policy_snapshot();
    // The virtual process graph has one stable root.  It is also the value
    // emitted by the procfs projection, so getppid and /proc agree.
    return policy.configured ? static_cast<pid_t>(1) : host_ppid();
}

pid_t NativeProcessIdentity::guest_tid() noexcept {
    const NativePolicySnapshot policy = policy_snapshot();
    // CAS currently exposes one virtual thread identity per process.  This is
    // intentionally conservative: it cannot leak a host TID and matches the
    // /proc task projection.  A richer virtual thread ledger is deferred.
    return policy.configured ? static_cast<pid_t>(policy.virtual_pid) : host_tid();
}

uid_t NativeProcessIdentity::guest_uid() noexcept {
    const NativePolicySnapshot policy = policy_snapshot();
    return policy.configured ? static_cast<uid_t>(policy.virtual_uid) : host_uid();
}

uid_t NativeProcessIdentity::guest_euid() noexcept {
    const NativePolicySnapshot policy = policy_snapshot();
    return policy.configured ? static_cast<uid_t>(policy.virtual_uid) : host_euid();
}

gid_t NativeProcessIdentity::guest_gid() noexcept {
    const NativePolicySnapshot policy = policy_snapshot();
    return policy.configured ? static_cast<gid_t>(policy.virtual_uid) : host_gid();
}

gid_t NativeProcessIdentity::guest_egid() noexcept {
    const NativePolicySnapshot policy = policy_snapshot();
    return policy.configured ? static_cast<gid_t>(policy.virtual_uid) : host_egid();
}

bool NativeProcessIdentity::translate_target(pid_t guest_target, pid_t& host_target) noexcept {
    const NativePolicySnapshot policy = policy_snapshot();
    if (!policy.configured) {
        host_target = guest_target;
        return true;
    }
    if (guest_target == 0 || guest_target == policy.virtual_pid) {
        host_target = host_pid();
        return true;
    }
    return false;
}

bool NativeProcessIdentity::is_current_target(pid_t target) noexcept {
    pid_t translated = 0;
    return translate_target(target, translated) && translated == host_pid();
}

std::string NativeProcessIdentity::sanitize_process_name(std::string_view value) {
    std::string out(value);
    if (out.size() > 255U) out.resize(255U);
    for (char& character : out) {
        if (character == '\n' || character == '\r' || character == '\t'
                || character == '\0' || character == '(' || character == ')') {
            character = '_';
        }
    }
    return out.empty() ? "guest" : out;
}

std::string NativeProcessIdentity::guest_process_name() {
    const NativePolicySnapshot policy = policy_snapshot();
    return policy.configured ? sanitize_process_name(policy.process_name) : "";
}

NativeSeccompSnapshot NativeSeccompPolicy::snapshot() noexcept {
    NativeSeccompSnapshot result{};
    errno = 0;
    int mode = -1;
#ifdef SYS_prctl
    mode = static_cast<int>(trusted_syscall6(SYS_prctl, PR_GET_SECCOMP, 0, 0, 0, 0));
#else
    mode = ::prctl(PR_GET_SECCOMP, 0, 0, 0, 0);
#endif
    if (mode >= 0) result.mode = mode;
    errno = 0;
    int no_new_privs = -1;
#ifdef SYS_prctl
    no_new_privs = static_cast<int>(trusted_syscall6(
            SYS_prctl, PR_GET_NO_NEW_PRIVS, 0, 0, 0, 0));
#else
    no_new_privs = ::prctl(PR_GET_NO_NEW_PRIVS, 0, 0, 0, 0);
#endif
    if (no_new_privs >= 0) result.no_new_privs = no_new_privs;
    return result;
}

NativeGuestSeccompAction NativeSeccompPolicy::guest_prctl_action(int option) noexcept {
    switch (option) {
        case PR_GET_SECCOMP:
        case PR_GET_NO_NEW_PRIVS:
        case PR_GET_DUMPABLE:
        case PR_GET_NAME:
            return NativeGuestSeccompAction::Mediated;
        case PR_SET_SECCOMP:
            return NativeGuestSeccompAction::Denied;
        case PR_SET_NO_NEW_PRIVS:
        case PR_SET_DUMPABLE:
        case PR_SET_NAME:
            return NativeGuestSeccompAction::Allowed;
        default:
            return NativeGuestSeccompAction::Allowed;
    }
}

bool NativeSeccompPolicy::is_install_operation(unsigned int operation) noexcept {
    return operation == SECCOMP_SET_MODE_STRICT || operation == SECCOMP_SET_MODE_FILTER;
}

NativeGuestSeccompAction NativeSeccompPolicy::guest_seccomp_action(
        unsigned int operation) noexcept {
    if (is_install_operation(operation)) return NativeGuestSeccompAction::Denied;
    if (operation == SECCOMP_GET_ACTION_AVAIL || operation == SECCOMP_GET_NOTIF_SIZES) {
        return NativeGuestSeccompAction::Mediated;
    }
    return NativeGuestSeccompAction::Allowed;
}

}  // namespace controlled_sandbox
