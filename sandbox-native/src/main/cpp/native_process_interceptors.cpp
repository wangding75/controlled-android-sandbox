#include "controlled_sandbox/native_process_interceptors.h"

#include "controlled_sandbox/native_boundary.h"
#include "controlled_sandbox/native_fd_ledger.h"
#include "controlled_sandbox/native_file_system.h"
#include "controlled_sandbox/native_policy.h"

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cstdarg>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <limits.h>
#include <sched.h>
#include <string>
#include <string_view>
#include <sys/prctl.h>
#include <sys/ptrace.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#if __has_include(<linux/seccomp.h>)
#include <linux/seccomp.h>
#endif

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

using GetPidFn = pid_t (*)();
using GetUidFn = uid_t (*)();
using GetGidFn = gid_t (*)();
using PrctlFn = int (*)(int, ...);
using PtraceFn = long (*)(long, ...);
using ForkFn = pid_t (*)();
using CloneFn = int (*)(int (*)(void*), void*, int, void*, ...);
using Clone3Fn = long (*)(void*, std::size_t);
using ExecveFn = int (*)(const char*, char* const[], char* const[]);
using ExecveAtFn = int (*)(int, const char*, char* const[], char* const[], int);
using SeccompFn = int (*)(unsigned int, unsigned int, void*);
using GetcwdFn = char* (*)(char*, std::size_t);
using ChdirFn = int (*)(const char*);
using FchdirFn = int (*)(int);
using RealpathFn = char* (*)(const char*, char*);
using ChmodFn = int (*)(const char*, mode_t);
using FchmodFn = int (*)(int, mode_t);
using FchmodAtFn = int (*)(int, const char*, mode_t, int);
using ChownFn = int (*)(const char*, uid_t, gid_t);
using FchownFn = int (*)(int, uid_t, gid_t);
using FchownAtFn = int (*)(int, const char*, uid_t, gid_t, int);
using TruncateFn = int (*)(const char*, off_t);
using FtruncateFn = int (*)(int, off_t);
using FstatFn = int (*)(int, struct stat*);
using GetdentsFn = ssize_t (*)(int, void*, std::size_t);
using ClosedirFn = int (*)(DIR*);
using DlsymFn = void* (*)(void*, const char*);

std::atomic<GetPidFn> real_getpid{nullptr};
std::atomic<GetPidFn> real_getppid{nullptr};
std::atomic<GetPidFn> real_gettid{nullptr};
std::atomic<GetUidFn> real_getuid{nullptr};
std::atomic<GetUidFn> real_geteuid{nullptr};
std::atomic<GetGidFn> real_getgid{nullptr};
std::atomic<GetGidFn> real_getegid{nullptr};
std::atomic<PrctlFn> real_prctl{nullptr};
std::atomic<PtraceFn> real_ptrace{nullptr};
std::atomic<ForkFn> real_fork{nullptr};
std::atomic<ForkFn> real_vfork{nullptr};
std::atomic<CloneFn> real_clone{nullptr};
std::atomic<Clone3Fn> real_clone3{nullptr};
std::atomic<ExecveFn> real_execve{nullptr};
std::atomic<ExecveAtFn> real_execveat{nullptr};
std::atomic<SeccompFn> real_seccomp{nullptr};
std::atomic<GetcwdFn> real_getcwd{nullptr};
std::atomic<ChdirFn> real_chdir{nullptr};
std::atomic<FchdirFn> real_fchdir{nullptr};
std::atomic<RealpathFn> real_realpath{nullptr};
std::atomic<ChmodFn> real_chmod{nullptr};
std::atomic<FchmodFn> real_fchmod{nullptr};
std::atomic<FchmodAtFn> real_fchmodat{nullptr};
std::atomic<ChownFn> real_chown{nullptr};
std::atomic<FchownFn> real_fchown{nullptr};
std::atomic<FchownAtFn> real_fchownat{nullptr};
std::atomic<TruncateFn> real_truncate{nullptr};
std::atomic<FtruncateFn> real_ftruncate{nullptr};
std::atomic<FstatFn> real_fstat{nullptr};
std::atomic<GetdentsFn> real_getdents{nullptr};
std::atomic<ClosedirFn> real_closedir{nullptr};
std::atomic<DlsymFn> real_dlsym{nullptr};
thread_local std::string guest_thread_name;

void* resolve_next(const char* name) {
    dlerror();
    void* value = dlsym(RTLD_NEXT, name);
    (void) dlerror();
    return value;
}

template <typename Function>
Function require_real(std::atomic<Function>& storage, const char* name) {
    Function current = storage.load(std::memory_order_acquire);
    if (current != nullptr) return current;
    current = reinterpret_cast<Function>(resolve_next(name));
    storage.store(current, std::memory_order_release);
    return current;
}

bool configured() noexcept {
    return global_policy().snapshot().configured;
}

template <typename Function, typename... Args>
int call_path(Function& storage, const char* name, const NativeResolvedPath& resolved,
              Args... args) {
    auto function = require_real(storage, name);
    if (function == nullptr) { errno = ENOSYS; return -1; }
    if (resolved.capability) {
        errno = EOPNOTSUPP;
        return -1;
    }
    return function(resolved.path.c_str(), args...);
}

bool resolve_path_checked(const char* path, bool follow, NativeResolvedPath& resolved) {
    try {
        resolved = NativeFileSystemResolver::resolve(path);
        NativeFileSystemResolver::validate_confinement(resolved, follow);
        return true;
    } catch (const PathPolicyError& error) {
        errno = error.error_number();
        return false;
    } catch (...) {
        errno = EACCES;
        return false;
    }
}

bool resolve_at_checked(int directory, const char* path, bool follow,
                       NativeResolvedPath& resolved) {
    try {
        resolved = NativeFileSystemResolver::resolve_at(directory, path);
        NativeFileSystemResolver::validate_confinement(resolved, follow);
        return true;
    } catch (const PathPolicyError& error) {
        errno = error.error_number();
        return false;
    } catch (...) {
        errno = EACCES;
        return false;
    }
}

bool resolve_fd_checked(int descriptor, NativeResolvedPath& resolved) {
    try {
        resolved = NativeFileSystemResolver::resolve_fd(descriptor);
        NativeFileSystemResolver::validate_confinement(resolved, true);
        return true;
    } catch (const PathPolicyError& error) {
        errno = error.error_number();
        return false;
    } catch (...) {
        errno = EACCES;
        return false;
    }
}

bool fd_operation_current(int descriptor) noexcept {
    const auto record = NativeFdLedger::lookup(descriptor);
    if (!record) return true;
    if (record->ownership == NativeFdOwnership::HostInternal
            || record->ownership == NativeFdOwnership::BrokerTransport) {
        errno = EACCES;
        return false;
    }
    if (record->policy_revision != 0
            && !global_policy().revision_current(record->policy_revision)) {
        errno = EAGAIN;
        return false;
    }
    return true;
}

int deny_process_creation() noexcept {
    errno = EPERM;
    return -1;
}

bool is_thread_clone(int flags) noexcept {
    return (flags & CLONE_THREAD) != 0;
}

std::string projected_cwd() {
    return global_policy().guest_cwd();
}

char* copy_cwd(char* buffer, std::size_t size, const std::string& value) {
    const std::size_t required = value.size() + 1U;
    if (buffer == nullptr) {
        if (size == 0) size = required;
        if (size < required) { errno = ERANGE; return nullptr; }
        buffer = static_cast<char*>(std::malloc(size));
        if (buffer == nullptr) { errno = ENOMEM; return nullptr; }
    } else if (size < required) {
        errno = ERANGE;
        return nullptr;
    }
    std::memcpy(buffer, value.c_str(), required);
    return buffer;
}

int copy_realpath_value(const std::string& value, char* resolved) {
    if (resolved == nullptr) return 0;
    std::memcpy(resolved, value.c_str(), value.size() + 1U);
    return 1;
}

}  // namespace

int native_prctl_argument_count(int option) noexcept {
    switch (option) {
        case PR_GET_DUMPABLE:
        case PR_GET_SECCOMP:
        case PR_GET_NO_NEW_PRIVS:
            return 0;
        case PR_GET_NAME:
        case PR_SET_NAME:
        case PR_SET_DUMPABLE:
        case PR_SET_NO_NEW_PRIVS:
            return 1;
        case PR_SET_SECCOMP:
            return 2;
        default:
            // Preserve the legacy forwarding shape for options that are not
            // part of the native projection contract yet.
            return 4;
    }
}

extern "C" pid_t controlled_getpid() {
    if (configured()) return NativeProcessIdentity::guest_pid();
    GetPidFn function = require_real(real_getpid, "getpid");
    return function == nullptr ? NativeProcessIdentity::host_pid() : function();
}

extern "C" pid_t controlled_getppid() {
    if (configured()) return NativeProcessIdentity::guest_ppid();
    GetPidFn function = require_real(real_getppid, "getppid");
    return function == nullptr ? NativeProcessIdentity::host_ppid() : function();
}

extern "C" pid_t controlled_gettid() {
    if (configured()) return NativeProcessIdentity::guest_tid();
    GetPidFn function = require_real(real_gettid, "gettid");
    return function == nullptr ? NativeProcessIdentity::host_tid() : function();
}

extern "C" uid_t controlled_getuid() {
    if (configured()) return NativeProcessIdentity::guest_uid();
    GetUidFn function = require_real(real_getuid, "getuid");
    return function == nullptr ? NativeProcessIdentity::host_uid() : function();
}

extern "C" uid_t controlled_geteuid() {
    if (configured()) return NativeProcessIdentity::guest_euid();
    GetUidFn function = require_real(real_geteuid, "geteuid");
    return function == nullptr ? NativeProcessIdentity::host_euid() : function();
}

extern "C" gid_t controlled_getgid() {
    if (configured()) return NativeProcessIdentity::guest_gid();
    GetGidFn function = require_real(real_getgid, "getgid");
    return function == nullptr ? NativeProcessIdentity::host_gid() : function();
}

extern "C" gid_t controlled_getegid() {
    if (configured()) return NativeProcessIdentity::guest_egid();
    GetGidFn function = require_real(real_getegid, "getegid");
    return function == nullptr ? NativeProcessIdentity::host_egid() : function();
}

extern "C" int controlled_prctl(int option, ...) {
    PrctlFn function = require_real(real_prctl, "prctl");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    va_list values;
    va_start(values, option);
    unsigned long arguments[4] = {};
    const int argument_count = std::min(native_prctl_argument_count(option), 4);
    for (int index = 0; index < argument_count; ++index) {
        arguments[index] = va_arg(values, unsigned long);
    }
    va_end(values);

    const unsigned long arg2 = arguments[0];
    const unsigned long arg3 = arguments[1];
    const unsigned long arg4 = arguments[2];
    const unsigned long arg5 = arguments[3];

    if (configured()) {
        const NativeGuestSeccompAction action = NativeSeccompPolicy::guest_prctl_action(option);
        if (action == NativeGuestSeccompAction::Denied) {
            errno = EPERM;
            return -1;
        }
        if (option == PR_GET_NAME) {
            if (arg2 == 0) { errno = EFAULT; return -1; }
            const std::string name = NativeProcessIdentity::sanitize_process_name(
                    guest_thread_name.empty() ? NativeProcessIdentity::guest_process_name()
                            : guest_thread_name);
            char* output = reinterpret_cast<char*>(arg2);
            std::memset(output, 0, 16U);
            std::memcpy(output, name.data(), std::min<std::size_t>(name.size(), 15U));
            return 0;
        }
        if (option == PR_SET_NAME) {
            if (arg2 == 0) { errno = EFAULT; return -1; }
            const char* input = reinterpret_cast<const char*>(arg2);
            guest_thread_name = NativeProcessIdentity::sanitize_process_name(
                    std::string(input, strnlen(input, 16U)));
        }
    }
    return function(option, arg2, arg3, arg4, arg5);
}

extern "C" long controlled_ptrace(long request, ...) {
    PtraceFn function = require_real(real_ptrace, "ptrace");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    va_list values;
    va_start(values, request);
    const pid_t guest_target = va_arg(values, pid_t);
    void* address = va_arg(values, void*);
    void* data = va_arg(values, void*);
    va_end(values);

    pid_t host_target = guest_target;
    if (configured() && !NativeProcessIdentity::translate_target(guest_target, host_target)) {
        errno = ESRCH;
        return -1;
    }
    if (request == PTRACE_TRACEME) host_target = 0;
    return function(request, host_target, address, data);
}

extern "C" pid_t controlled_fork() {
    if (configured()) return static_cast<pid_t>(deny_process_creation());
    ForkFn function = require_real(real_fork, "fork");
    return function == nullptr ? static_cast<pid_t>(deny_process_creation()) : function();
}

extern "C" pid_t controlled_vfork() {
    if (configured()) return static_cast<pid_t>(deny_process_creation());
    ForkFn function = require_real(real_vfork, "vfork");
    return function == nullptr ? static_cast<pid_t>(deny_process_creation()) : function();
}

extern "C" int controlled_clone(int (*function)(void*), void* stack, int flags,
                                 void* argument, ...) {
    CloneFn real_function = require_real(real_clone, "clone");
    if (real_function == nullptr) { errno = ENOSYS; return -1; }
    if (configured() && !is_thread_clone(flags)) return deny_process_creation();
    va_list values;
    va_start(values, argument);
    void* parent_tid = nullptr;
    void* tls = nullptr;
    void* child_tid = nullptr;
    if ((flags & CLONE_PARENT_SETTID) != 0 || (flags & CLONE_CHILD_SETTID) != 0) {
        parent_tid = va_arg(values, void*);
    }
    if ((flags & CLONE_SETTLS) != 0) tls = va_arg(values, void*);
    if ((flags & CLONE_CHILD_CLEARTID) != 0) child_tid = va_arg(values, void*);
    va_end(values);
    return real_function(function, stack, flags, argument, parent_tid, tls, child_tid);
}

extern "C" long controlled_clone3(void* arguments, std::size_t size) {
    Clone3Fn function = require_real(real_clone3, "clone3");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    if (configured()) {
        if (arguments == nullptr || size < sizeof(std::uint64_t)) { errno = EFAULT; return -1; }
        const std::uint64_t flags = *reinterpret_cast<const std::uint64_t*>(arguments);
        if ((flags & CLONE_THREAD) == 0) return deny_process_creation();
    }
    return function(arguments, size);
}

extern "C" int controlled_execve(const char* filename, char* const argv[],
                                  char* const envp[]) {
    if (configured()) { errno = EPERM; return -1; }
    ExecveFn function = require_real(real_execve, "execve");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    return function(filename, argv, envp);
}

extern "C" int controlled_execveat(int directory, const char* filename,
                                    char* const argv[], char* const envp[], int flags) {
    if (configured()) { errno = EPERM; return -1; }
    ExecveAtFn function = require_real(real_execveat, "execveat");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    return function(directory, filename, argv, envp, flags);
}

extern "C" int controlled_seccomp(unsigned int operation, unsigned int flags,
                                   void* arguments) {
    SeccompFn function = require_real(real_seccomp, "seccomp");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    if (configured() && NativeSeccompPolicy::is_install_operation(operation)) {
        errno = EPERM;
        return -1;
    }
    return function(operation, flags, arguments);
}

extern "C" char* controlled_getcwd(char* buffer, std::size_t size) {
    if (!configured()) {
        GetcwdFn function = require_real(real_getcwd, "getcwd");
        if (function == nullptr) { errno = ENOSYS; return nullptr; }
        return function(buffer, size);
    }
    try { return copy_cwd(buffer, size, projected_cwd()); }
    catch (...) { errno = EACCES; return nullptr; }
}

extern "C" int controlled_chdir(const char* path) {
    if (!configured()) {
        ChdirFn function = require_real(real_chdir, "chdir");
        if (function == nullptr) { errno = ENOSYS; return -1; }
        return function(path);
    }
    NativeResolvedPath resolved;
    if (!resolve_path_checked(path, true, resolved)) return -1;
    int status = -1;
    if (resolved.capability) {
        using OpenAtFn = int (*)(int, const char*, int, ...);
        const OpenAtFn openat = reinterpret_cast<OpenAtFn>(resolve_next("openat"));
        FchdirFn fchdir = require_real(real_fchdir, "fchdir");
        if (openat == nullptr || fchdir == nullptr) { errno = ENOSYS; return -1; }
        const int descriptor = openat(resolved.directory_fd, resolved.path.c_str(),
                O_RDONLY | O_DIRECTORY | O_CLOEXEC);
        if (descriptor < 0) return -1;
        status = fchdir(descriptor);
        const int saved = errno;
        (void) ::close(descriptor);
        errno = saved;
    } else {
        ChdirFn function = require_real(real_chdir, "chdir");
        if (function == nullptr) { errno = ENOSYS; return -1; }
        status = function(resolved.path.c_str());
    }
    if (status == 0) global_policy().set_guest_cwd(resolved.virtual_path);
    return status;
}

extern "C" int controlled_fchdir(int descriptor) {
    FchdirFn function = require_real(real_fchdir, "fchdir");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    if (!configured()) return function(descriptor);
    if (!fd_operation_current(descriptor)) return -1;
    NativeResolvedPath resolved;
    if (!resolve_fd_checked(descriptor, resolved)) return -1;
    const int status = function(descriptor);
    if (status == 0 && !resolved.virtual_path.empty()) global_policy().set_guest_cwd(
            resolved.virtual_path);
    return status;
}

extern "C" char* controlled_realpath(const char* path, char* resolved_buffer) {
    if (!configured()) {
        RealpathFn function = require_real(real_realpath, "realpath");
        if (function == nullptr) { errno = ENOSYS; return nullptr; }
        return function(path, resolved_buffer);
    }
    NativeResolvedPath resolved;
    if (!resolve_path_checked(path, true, resolved)) return nullptr;
    if (resolved.capability) {
        const std::string value = resolved.virtual_path.empty() ? "/" : resolved.virtual_path;
        if (resolved_buffer != nullptr) {
            copy_realpath_value(value, resolved_buffer);
            return resolved_buffer;
        }
        char* result = static_cast<char*>(std::malloc(value.size() + 1U));
        if (result == nullptr) { errno = ENOMEM; return nullptr; }
        std::memcpy(result, value.c_str(), value.size() + 1U);
        return result;
    }
    RealpathFn function = require_real(real_realpath, "realpath");
    if (function == nullptr) { errno = ENOSYS; return nullptr; }
    char* host_value = function(resolved.path.c_str(), nullptr);
    if (host_value == nullptr) return nullptr;
    const std::string value = global_policy().reverse_map_path(host_value);
    std::free(host_value);
    if (resolved_buffer != nullptr) {
        copy_realpath_value(value, resolved_buffer);
        return resolved_buffer;
    }
    char* result = static_cast<char*>(std::malloc(value.size() + 1U));
    if (result == nullptr) { errno = ENOMEM; return nullptr; }
    std::memcpy(result, value.c_str(), value.size() + 1U);
    return result;
}

extern "C" int controlled_chmod(const char* path, mode_t mode) {
    NativeResolvedPath resolved;
    if (!configured()) {
        ChmodFn function = require_real(real_chmod, "chmod");
        return function == nullptr ? (errno = ENOSYS, -1) : function(path, mode);
    }
    if (!resolve_path_checked(path, true, resolved)) return -1;
    return call_path(real_chmod, "chmod", resolved, mode);
}

extern "C" int controlled_fchmod(int descriptor, mode_t mode) {
    FchmodFn function = require_real(real_fchmod, "fchmod");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    if (configured()) {
        if (!fd_operation_current(descriptor)) return -1;
        NativeResolvedPath resolved;
        if (!resolve_fd_checked(descriptor, resolved)) return -1;
    }
    return function(descriptor, mode);
}

extern "C" int controlled_fchmodat(int directory, const char* path, mode_t mode, int flags) {
    FchmodAtFn function = require_real(real_fchmodat, "fchmodat");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    if (!configured()) return function(directory, path, mode, flags);
    NativeResolvedPath resolved;
    if (!resolve_at_checked(directory, path, (flags & AT_SYMLINK_NOFOLLOW) == 0, resolved)) return -1;
    if (resolved.capability) { errno = EOPNOTSUPP; return -1; }
    return function(resolved.directory_fd, resolved.path.c_str(), mode, flags);
}

extern "C" int controlled_chown(const char* path, uid_t owner, gid_t group) {
    ChownFn function = require_real(real_chown, "chown");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    if (!configured()) return function(path, owner, group);
    NativeResolvedPath resolved;
    if (!resolve_path_checked(path, true, resolved)) return -1;
    if (resolved.capability) { errno = EOPNOTSUPP; return -1; }
    return function(resolved.path.c_str(), owner, group);
}

extern "C" int controlled_fchown(int descriptor, uid_t owner, gid_t group) {
    FchownFn function = require_real(real_fchown, "fchown");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    if (configured()) {
        if (!fd_operation_current(descriptor)) return -1;
        NativeResolvedPath resolved;
        if (!resolve_fd_checked(descriptor, resolved)) return -1;
    }
    return function(descriptor, owner, group);
}

extern "C" int controlled_fchownat(int directory, const char* path, uid_t owner,
                                    gid_t group, int flags) {
    FchownAtFn function = require_real(real_fchownat, "fchownat");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    if (!configured()) return function(directory, path, owner, group, flags);
    NativeResolvedPath resolved;
    if (!resolve_at_checked(directory, path, (flags & AT_SYMLINK_NOFOLLOW) == 0, resolved)) return -1;
    if (resolved.capability) { errno = EOPNOTSUPP; return -1; }
    return function(resolved.directory_fd, resolved.path.c_str(), owner, group, flags);
}

extern "C" int controlled_truncate(const char* path, off_t length) {
    TruncateFn function = require_real(real_truncate, "truncate");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    if (!configured()) return function(path, length);
    NativeResolvedPath resolved;
    if (!resolve_path_checked(path, true, resolved)) return -1;
    if (resolved.capability) { errno = EOPNOTSUPP; return -1; }
    return function(resolved.path.c_str(), length);
}

extern "C" int controlled_ftruncate(int descriptor, off_t length) {
    FtruncateFn function = require_real(real_ftruncate, "ftruncate");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    if (configured()) {
        if (!fd_operation_current(descriptor)) return -1;
        NativeResolvedPath resolved;
        if (!resolve_fd_checked(descriptor, resolved)) return -1;
    }
    return function(descriptor, length);
}

extern "C" int controlled_fstat(int descriptor, struct stat* value) {
    FstatFn function = require_real(real_fstat, "fstat");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    if (value == nullptr) { errno = EFAULT; return -1; }
    if (configured()) {
        if (!fd_operation_current(descriptor)) return -1;
        NativeFdLedger::observe_inherited(descriptor, global_policy().snapshot().revision);
        if (!NativeFdLedger::guest_visible(descriptor)) { errno = EACCES; return -1; }
    }
    return function(descriptor, value);
}

extern "C" ssize_t controlled_getdents(int descriptor, void* buffer, std::size_t size) {
    GetdentsFn function = require_real(real_getdents, "getdents");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    return function(descriptor, buffer, size);
}

extern "C" int controlled_closedir(DIR* directory) {
    ClosedirFn function = require_real(real_closedir, "closedir");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    if (directory == nullptr) { errno = EINVAL; return -1; }
    const int descriptor = dirfd(directory);
    if (descriptor >= 0) {
        global_policy().unregister_capability_fd(descriptor);
        NativeFdLedger::close(descriptor);
    }
    return function(directory);
}

extern "C" void* controlled_dlsym(void* handle, const char* name) {
    DlsymFn function = require_real(real_dlsym, "dlsym");
    if (name != nullptr && configured()) {
        if (void* replacement = native_process_replacement_for_symbol(name); replacement != nullptr) {
            return replacement;
        }
    }
    if (function == nullptr) { errno = ENOSYS; return nullptr; }
    return function(handle, name);
}

void* native_process_replacement_for_symbol(std::string_view symbol) noexcept {
    if (symbol == "getpid") return reinterpret_cast<void*>(&controlled_getpid);
    if (symbol == "getppid") return reinterpret_cast<void*>(&controlled_getppid);
    if (symbol == "gettid") return reinterpret_cast<void*>(&controlled_gettid);
    if (symbol == "getuid") return reinterpret_cast<void*>(&controlled_getuid);
    if (symbol == "geteuid") return reinterpret_cast<void*>(&controlled_geteuid);
    if (symbol == "getgid") return reinterpret_cast<void*>(&controlled_getgid);
    if (symbol == "getegid") return reinterpret_cast<void*>(&controlled_getegid);
    if (symbol == "prctl") return reinterpret_cast<void*>(&controlled_prctl);
    if (symbol == "ptrace") return reinterpret_cast<void*>(&controlled_ptrace);
    if (symbol == "fork") return reinterpret_cast<void*>(&controlled_fork);
    if (symbol == "vfork") return reinterpret_cast<void*>(&controlled_vfork);
    if (symbol == "clone") return reinterpret_cast<void*>(&controlled_clone);
    if (symbol == "clone3") return reinterpret_cast<void*>(&controlled_clone3);
    if (symbol == "execve") return reinterpret_cast<void*>(&controlled_execve);
    if (symbol == "execveat") return reinterpret_cast<void*>(&controlled_execveat);
    if (symbol == "seccomp") return reinterpret_cast<void*>(&controlled_seccomp);
    if (symbol == "getcwd") return reinterpret_cast<void*>(&controlled_getcwd);
    if (symbol == "chdir") return reinterpret_cast<void*>(&controlled_chdir);
    if (symbol == "fchdir") return reinterpret_cast<void*>(&controlled_fchdir);
    if (symbol == "realpath") return reinterpret_cast<void*>(&controlled_realpath);
    if (symbol == "chmod") return reinterpret_cast<void*>(&controlled_chmod);
    if (symbol == "fchmod") return reinterpret_cast<void*>(&controlled_fchmod);
    if (symbol == "fchmodat") return reinterpret_cast<void*>(&controlled_fchmodat);
    if (symbol == "chown") return reinterpret_cast<void*>(&controlled_chown);
    if (symbol == "fchown") return reinterpret_cast<void*>(&controlled_fchown);
    if (symbol == "fchownat") return reinterpret_cast<void*>(&controlled_fchownat);
    if (symbol == "truncate") return reinterpret_cast<void*>(&controlled_truncate);
    if (symbol == "ftruncate") return reinterpret_cast<void*>(&controlled_ftruncate);
    if (symbol == "fstat") return reinterpret_cast<void*>(&controlled_fstat);
    if (symbol == "getdents") return reinterpret_cast<void*>(&controlled_getdents);
    if (symbol == "closedir") return reinterpret_cast<void*>(&controlled_closedir);
    if (symbol == "dlsym") return reinterpret_cast<void*>(&controlled_dlsym);
    return nullptr;
}

}  // namespace controlled_sandbox
