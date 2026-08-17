#include "controlled_sandbox/native_interceptors.h"
#include "controlled_sandbox/native_hook.h"
#include "controlled_sandbox/native_file_system.h"
#include "controlled_sandbox/native_policy.h"
#include "controlled_sandbox/native_loader.h"
#include "controlled_sandbox/native_network.h"
#include "controlled_sandbox/native_network_interceptors.h"
#include "controlled_sandbox/native_audio.h"

#include <arpa/inet.h>
#include <cerrno>
#include <climits>
#include <cstdarg>
#include <cstdint>
#include <cstring>
#include <dlfcn.h>
#include <dirent.h>
#include <fcntl.h>
#include <ifaddrs.h>
#include <netdb.h>
#include <sys/mman.h>
#include <sys/utsname.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <linux/stat.h>
#if __has_include(<linux/openat2.h>)
#include <linux/openat2.h>
#else
struct open_how { std::uint64_t flags; std::uint64_t mode; std::uint64_t resolve; };
#endif
#if __has_include(<android/dlext.h>)
#include <android/dlext.h>
#else
struct android_namespace_t;
struct android_dlextinfo {
    std::uint64_t flags;
    void* reserved_addr;
    std::size_t reserved_size;
    int relro_fd;
    int library_fd;
    std::int64_t library_fd_offset;
    android_namespace_t* library_namespace;
};
#ifndef ANDROID_DLEXT_USE_LIBRARY_FD
#define ANDROID_DLEXT_USE_LIBRARY_FD 0x10ULL
#endif
#ifndef ANDROID_DLEXT_USE_LIBRARY_FD_OFFSET
#define ANDROID_DLEXT_USE_LIBRARY_FD_OFFSET 0x20ULL
#endif
#ifndef ANDROID_DLEXT_USE_NAMESPACE
#define ANDROID_DLEXT_USE_NAMESPACE 0x200ULL
#endif
#ifndef ANDROID_DLEXT_RESERVED_ADDRESS
#define ANDROID_DLEXT_RESERVED_ADDRESS 0x1ULL
#endif
#ifndef ANDROID_DLEXT_RESERVED_ADDRESS_HINT
#define ANDROID_DLEXT_RESERVED_ADDRESS_HINT 0x2ULL
#endif
#ifndef ANDROID_DLEXT_WRITE_RELRO
#define ANDROID_DLEXT_WRITE_RELRO 0x4ULL
#endif
#ifndef ANDROID_DLEXT_USE_RELRO
#define ANDROID_DLEXT_USE_RELRO 0x8ULL
#endif
#ifndef ANDROID_DLEXT_FORCE_LOAD
#define ANDROID_DLEXT_FORCE_LOAD 0x40ULL
#endif
#ifndef ANDROID_DLEXT_RESERVED_ADDRESS_RECURSIVE
#define ANDROID_DLEXT_RESERVED_ADDRESS_RECURSIVE 0x400ULL
#endif
#endif
#ifndef RESOLVE_NO_SYMLINKS
#define RESOLVE_NO_SYMLINKS 0x04ULL
#endif
#ifndef RESOLVE_BENEATH
#define RESOLVE_BENEATH 0x08ULL
#endif
#ifndef RESOLVE_IN_ROOT
#define RESOLVE_IN_ROOT 0x10ULL
#endif

#include <android/log.h>
#include <csignal>
#include <algorithm>
#include <atomic>
#include <map>
#include <mutex>
#include <string>
#include <string_view>
#include <vector>
#include <utility>
#include <sys/types.h>

namespace controlled_sandbox {
namespace {

using OpenFn = int (*)(const char*, int, ...);
using OpenAtFn = int (*)(int, const char*, int, ...);
using Open2Fn = int (*)(const char*, int);
using OpenAtCheckedFn = int (*)(int, const char*, int);
using OpenAt2Fn = int (*)(int, const char*, const struct open_how*, std::size_t);
using AccessFn = int (*)(const char*, int);
using FaccessAtFn = int (*)(int, const char*, int, int);
using FaccessAt2Fn = int (*)(int, const char*, int, int);
using StatFn = int (*)(const char*, struct stat*);
using FstatAtFn = int (*)(int, const char*, struct stat*, int);
using StatxFn = int (*)(int, const char*, int, unsigned int, struct statx*);
using RenameAt2Fn = int (*)(int, const char*, int, const char*, unsigned int);
using RenameFn = int (*)(const char*, const char*);
using RenameAtFn = int (*)(int, const char*, int, const char*);
using UnlinkFn = int (*)(const char*);
using UnlinkAtFn = int (*)(int, const char*, int);
using MkdirFn = int (*)(const char*, mode_t);
using MkdirAtFn = int (*)(int, const char*, mode_t);
using RmdirFn = int (*)(const char*);
using OpendirFn = DIR* (*)(const char*);
using Getdents64Fn = ssize_t (*)(int, void*, std::size_t);
using MmapFn = void* (*)(void*, std::size_t, int, int, int, off_t);
using ReadlinkFn = ssize_t (*)(const char*, char*, size_t);
using ReadlinkAtFn = ssize_t (*)(int, const char*, char*, size_t);
using SetSockOptFn = int (*)(int, int, int, const void*, socklen_t);
using GetSockOptFn = int (*)(int, int, int, void*, socklen_t*);
using IfNameToIndexFn = unsigned int (*)(const char*);
using IfIndexToNameFn = char* (*)(unsigned int, char*);
using GetAddrInfoFn = int (*)(const char*, const char*, const addrinfo*, addrinfo**);
using FreeAddrInfoFn = void (*)(addrinfo*);
using GetNameInfoFn = int (*)(const sockaddr*, socklen_t, char*, socklen_t, char*, socklen_t, int);
using GetHostNameFn = int (*)(char*, std::size_t);
using UnameFn = int (*)(struct utsname*);
using GetIfAddrsFn = int (*)(ifaddrs**);
using FreeIfAddrsFn = void (*)(ifaddrs*);
using AudioCallFn = int (*)(void*);
using DlopenFn = void* (*)(const char*, int);
using AndroidDlopenExtFn = void* (*)(const char*, int, const android_dlextinfo*);
using KillFn = int (*)(pid_t, int);
using KillPgFn = int (*)(int, int);
using TgKillFn = int (*)(int, int, int);
using TKillFn = int (*)(int, int);
using ExitFn = void (*)(int);
using AbortFn = void (*)();
using SyscallFn = long (*)(long, ...);

bool is_own_proc_fd_path(const char* value) {
    if (value == nullptr) return false;
    const std::string_view path(value);
    constexpr std::string_view prefix = "/proc/";
    if (path.compare(0, prefix.size(), prefix) != 0) return false;
    const std::size_t target_end = path.find('/', prefix.size());
    if (target_end == std::string_view::npos || target_end == prefix.size()) return false;
    const std::string_view target = path.substr(prefix.size(), target_end - prefix.size());
    const NativePolicySnapshot policy = global_policy().snapshot();
    if (!policy.configured) return false;
    bool own = target == "self" || target == "thread-self";
    if (!own) {
        long pid = 0;
        if (target.empty()) return false;
        for (const char character : target) {
            if (character < '0' || character > '9') return false;
            pid = pid * 10 + (character - '0');
            if (pid > INT_MAX) return false;
        }
        own = pid == static_cast<long>(getpid()) || pid == policy.virtual_pid;
    }
    if (!own) return false;
    constexpr std::string_view fd_prefix = "/fd/";
    const std::string_view suffix = path.substr(target_end);
    if (suffix.compare(0, fd_prefix.size(), fd_prefix) != 0) return false;
    const std::string_view descriptor = suffix.substr(fd_prefix.size());
    if (descriptor.empty()) return false;
    for (const char character : descriptor) {
        if (character < '0' || character > '9') return false;
    }
    return true;
}

std::atomic<OpenFn> real_open{nullptr};
std::atomic<OpenFn> real_open64{nullptr};
std::atomic<OpenAtFn> real_openat{nullptr};
std::atomic<OpenAtFn> real_openat64{nullptr};
std::atomic<Open2Fn> real_open_2{nullptr};
std::atomic<OpenAtCheckedFn> real_openat_2{nullptr};
std::atomic<OpenAt2Fn> real_openat2{nullptr};
std::atomic<AccessFn> real_access{nullptr};
std::atomic<FaccessAtFn> real_faccessat{nullptr};
std::atomic<FaccessAt2Fn> real_faccessat2{nullptr};
std::atomic<StatFn> real_stat{nullptr};
std::atomic<StatFn> real_lstat{nullptr};
std::atomic<FstatAtFn> real_fstatat{nullptr};
std::atomic<StatxFn> real_statx{nullptr};
std::atomic<RenameAt2Fn> real_renameat2{nullptr};
std::atomic<RenameFn> real_rename{nullptr};
std::atomic<RenameAtFn> real_renameat{nullptr};
std::atomic<UnlinkFn> real_unlink{nullptr};
std::atomic<UnlinkAtFn> real_unlinkat{nullptr};
std::atomic<MkdirFn> real_mkdir{nullptr};
std::atomic<MkdirAtFn> real_mkdirat{nullptr};
std::atomic<RmdirFn> real_rmdir{nullptr};
std::atomic<OpendirFn> real_opendir{nullptr};
std::atomic<Getdents64Fn> real_getdents64{nullptr};
std::atomic<MmapFn> real_mmap{nullptr};
std::atomic<ReadlinkFn> real_readlink{nullptr};
std::atomic<ReadlinkAtFn> real_readlinkat{nullptr};
std::atomic<SetSockOptFn> real_setsockopt{nullptr};
std::atomic<GetSockOptFn> real_getsockopt{nullptr};
[[maybe_unused]] std::atomic<IfNameToIndexFn> real_if_nametoindex{nullptr};
[[maybe_unused]] std::atomic<IfIndexToNameFn> real_if_indextoname{nullptr};
std::atomic<GetAddrInfoFn> real_getaddrinfo{nullptr};
std::atomic<FreeAddrInfoFn> real_freeaddrinfo{nullptr};
std::atomic<GetNameInfoFn> real_getnameinfo{nullptr};
[[maybe_unused]] std::atomic<GetHostNameFn> real_gethostname{nullptr};
std::atomic<UnameFn> real_uname{nullptr};
[[maybe_unused]] std::atomic<GetIfAddrsFn> real_getifaddrs{nullptr};
std::atomic<FreeIfAddrsFn> real_freeifaddrs{nullptr};
std::atomic<AudioCallFn> real_aaudio_start{nullptr};
std::atomic<AudioCallFn> real_aaudio_stop{nullptr};
std::atomic<AudioCallFn> real_media_recorder_start{nullptr};
std::atomic<AudioCallFn> real_media_recorder_stop{nullptr};
std::mutex audio_handles_mutex;
std::map<void*, std::uint64_t> aaudio_handles;
std::map<void*, std::uint64_t> media_recorder_handles;
std::atomic<DlopenFn> real_dlopen{nullptr};
std::atomic<AndroidDlopenExtFn> real_android_dlopen_ext{nullptr};
std::atomic<KillFn> real_kill{nullptr};
std::atomic<KillPgFn> real_killpg{nullptr};
std::atomic<TgKillFn> real_tgkill{nullptr};
std::atomic<TKillFn> real_tkill{nullptr};
std::atomic<ExitFn> real_exit{nullptr};
std::atomic<ExitFn> real_underscore_exit{nullptr};
std::atomic<AbortFn> real_abort{nullptr};
std::atomic<SyscallFn> real_syscall{nullptr};
std::atomic<bool> process_exit_allowed{false};
thread_local bool inside_refresh = false;

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

/** Call the real libc syscall entry from an interceptor without re-entering our PLT replacement. */
long raw_syscall6(long number, long a0 = 0, long a1 = 0, long a2 = 0,
                  long a3 = 0, long a4 = 0, long a5 = 0) {
    SyscallFn function = require_real(real_syscall, "syscall");
    if (function == nullptr) {
        errno = ENOSYS;
        return -1;
    }
    return function(number, a0, a1, a2, a3, a4, a5);
}

int call_open_resolved(const NativeResolvedPath& resolved, OpenFn function,
                       int flags, bool has_mode, mode_t mode) {
    if (!resolved.capability) {
        if (function == nullptr) { errno = ENOSYS; return -1; }
        return has_mode ? function(resolved.path.c_str(), flags, mode)
                        : function(resolved.path.c_str(), flags);
    }
    if (global_policy().is_capability_file_fd(resolved.directory_fd)) {
#ifdef F_DUPFD_CLOEXEC
        const int duplicated = static_cast<int>(raw_syscall6(SYS_fcntl, resolved.directory_fd,
                F_DUPFD_CLOEXEC, 3));
        __android_log_print(ANDROID_LOG_INFO, "CS_CAP_IO", "open file capability fd=%d dup=%d flags=0x%x",
                resolved.directory_fd, duplicated, flags);
        return duplicated;
#else
        const int duplicated = static_cast<int>(raw_syscall6(SYS_dup, resolved.directory_fd));
        __android_log_print(ANDROID_LOG_INFO, "CS_CAP_IO", "open file capability fd=%d dup=%d flags=0x%x",
                resolved.directory_fd, duplicated, flags);
        return duplicated;
#endif
    }
    OpenAtFn at = require_real(real_openat, "openat");
    if (at != nullptr) {
        return has_mode ? at(resolved.directory_fd, resolved.path.c_str(), flags, mode)
                        : at(resolved.directory_fd, resolved.path.c_str(), flags);
    }
#ifdef SYS_openat
    return static_cast<int>(raw_syscall6(SYS_openat, resolved.directory_fd,
            reinterpret_cast<long>(resolved.path.c_str()), flags,
            has_mode ? static_cast<long>(mode) : 0L));
#else
    errno = ENOSYS;
    return -1;
#endif
}

void register_opened_capability(const NativeResolvedPath& resolved, int descriptor) {
    if (resolved.capability && descriptor >= 0) global_policy().register_capability_fd(descriptor);
}

int call_access_resolved(const NativeResolvedPath& resolved, AccessFn function, int mode) {
    if (!resolved.capability) {
        if (function == nullptr) { errno = ENOSYS; return -1; }
        return function(resolved.path.c_str(), mode);
    }
    if (global_policy().is_capability_file_fd(resolved.directory_fd)) {
#ifdef SYS_fstat
        struct stat value{};
        if (raw_syscall6(SYS_fstat, resolved.directory_fd,
                reinterpret_cast<long>(&value)) != 0) return -1;
        if (mode == F_OK) return 0;
        const mode_t permissions = value.st_mode;
        if ((mode & R_OK) != 0 && (permissions & (S_IRUSR | S_IRGRP | S_IROTH)) == 0) {
            errno = EACCES; return -1;
        }
        if ((mode & W_OK) != 0 && (permissions & (S_IWUSR | S_IWGRP | S_IWOTH)) == 0) {
            errno = EACCES; return -1;
        }
        if ((mode & X_OK) != 0 && (permissions & (S_IXUSR | S_IXGRP | S_IXOTH)) == 0) {
            errno = EACCES; return -1;
        }
        return 0;
#else
        errno = ENOSYS;
        return -1;
#endif
    }
    FaccessAtFn at = require_real(real_faccessat, "faccessat");
    if (at != nullptr) return at(resolved.directory_fd, resolved.path.c_str(), mode, 0);
#ifdef SYS_faccessat
    return static_cast<int>(raw_syscall6(SYS_faccessat, resolved.directory_fd,
            reinterpret_cast<long>(resolved.path.c_str()), mode, 0));
#else
    errno = ENOSYS;
    return -1;
#endif
}

int call_stat_resolved(const NativeResolvedPath& resolved, StatFn function,
                       struct stat* value, int flags) {
    if (!resolved.capability) {
        if (function == nullptr) { errno = ENOSYS; return -1; }
        return function(resolved.path.c_str(), value);
    }
    if (global_policy().is_capability_file_fd(resolved.directory_fd)) {
#ifdef SYS_fstat
        if ((flags & AT_SYMLINK_NOFOLLOW) != 0) {
            errno = EINVAL;
            return -1;
        }
        return static_cast<int>(raw_syscall6(SYS_fstat, resolved.directory_fd,
                reinterpret_cast<long>(value)));
#else
        errno = ENOSYS;
        return -1;
#endif
    }
    FstatAtFn at = require_real(real_fstatat, "fstatat");
    if (at != nullptr) return at(resolved.directory_fd, resolved.path.c_str(), value, flags);
#ifdef SYS_newfstatat
    return static_cast<int>(raw_syscall6(SYS_newfstatat, resolved.directory_fd,
            reinterpret_cast<long>(resolved.path.c_str()), reinterpret_cast<long>(value), flags));
#else
    errno = ENOSYS;
    return -1;
#endif
}

void log_native_binding(const char* api, const char* requested, const char* resolved,
                        int flags, const android_dlextinfo* info, void* handle) {
    const std::uint64_t extension_flags = info == nullptr ? 0 : info->flags;
    const void* namespace_ptr = info == nullptr ? nullptr : info->library_namespace;
    __android_log_print(ANDROID_LOG_INFO, "CS_NATIVE_BIND",
            "SO api=%s requested=%s resolved=%s flags=0x%x extFlags=0x%llx useNamespace=%d "
            "namespace=%p handle=%p",
            api == nullptr ? "" : api,
            requested == nullptr ? "" : requested,
            resolved == nullptr ? "" : resolved,
            flags,
            static_cast<unsigned long long>(extension_flags),
            (extension_flags & ANDROID_DLEXT_USE_NAMESPACE) != 0 ? 1 : 0,
            namespace_ptr,
            handle);
}

bool requires_mode(int flags) {
    if ((flags & O_CREAT) != 0) return true;
#ifdef O_TMPFILE
    if ((flags & O_TMPFILE) == O_TMPFILE) return true;
#endif
    return false;
}

template <typename Resolver>
bool resolve_checked(Resolver&& resolver, bool follow_final_symlink, NativeResolvedPath& out) {
    try {
        out = resolver();
        NativeFileSystemResolver::validate_confinement(out, follow_final_symlink);
        return true;
    } catch (const PathPolicyError& error) {
        errno = error.error_number();
        return false;
    } catch (...) {
        errno = EACCES;
        return false;
    }
}

extern "C" int controlled_open(const char* path, int flags, ...) {
    OpenFn function = require_real(real_open, "open");
    NativeResolvedPath resolved;
    if (!resolve_checked([&] { return NativeFileSystemResolver::resolve(path); }, true, resolved)) return -1;
    if (requires_mode(flags)) {
        va_list values;
        va_start(values, flags);
        mode_t mode = static_cast<mode_t>(va_arg(values, int));
        va_end(values);
        const int descriptor = call_open_resolved(resolved, function, flags, true, mode);
        register_opened_capability(resolved, descriptor);
        return descriptor;
    }
    const int descriptor = call_open_resolved(resolved, function, flags, false, 0);
    register_opened_capability(resolved, descriptor);
    return descriptor;
}

extern "C" int controlled_open64(const char* path, int flags, ...) {
    OpenFn function = require_real(real_open64, "open64");
    if (function == nullptr) function = require_real(real_open, "open");
    NativeResolvedPath resolved;
    if (!resolve_checked([&] { return NativeFileSystemResolver::resolve(path); }, true, resolved)) return -1;
    if (requires_mode(flags)) {
        va_list values;
        va_start(values, flags);
        mode_t mode = static_cast<mode_t>(va_arg(values, int));
        va_end(values);
        const int descriptor = call_open_resolved(resolved, function, flags, true, mode);
        register_opened_capability(resolved, descriptor);
        return descriptor;
    }
    const int descriptor = call_open_resolved(resolved, function, flags, false, 0);
    register_opened_capability(resolved, descriptor);
    return descriptor;
}

extern "C" int controlled_openat(int directory, const char* path, int flags, ...) {
    OpenAtFn function = require_real(real_openat, "openat");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    NativeResolvedPath resolved;
    if (!resolve_checked([&] { return NativeFileSystemResolver::resolve_at(directory, path); }, true, resolved)) return -1;
    if (requires_mode(flags)) {
        va_list values;
        va_start(values, flags);
        mode_t mode = static_cast<mode_t>(va_arg(values, int));
        va_end(values);
        const int descriptor = function(resolved.directory_fd, resolved.path.c_str(), flags, mode);
        register_opened_capability(resolved, descriptor);
        return descriptor;
    }
    const int descriptor = function(resolved.directory_fd, resolved.path.c_str(), flags);
    register_opened_capability(resolved, descriptor);
    return descriptor;
}

extern "C" int controlled_openat64(int directory, const char* path, int flags, ...) {
    OpenAtFn function = require_real(real_openat64, "openat64");
    if (function == nullptr) function = require_real(real_openat, "openat");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    NativeResolvedPath resolved;
    if (!resolve_checked([&] { return NativeFileSystemResolver::resolve_at(directory, path); }, true, resolved)) return -1;
    if (requires_mode(flags)) {
        va_list values;
        va_start(values, flags);
        mode_t mode = static_cast<mode_t>(va_arg(values, int));
        va_end(values);
        const int descriptor = function(resolved.directory_fd, resolved.path.c_str(), flags, mode);
        register_opened_capability(resolved, descriptor);
        return descriptor;
    }
    const int descriptor = function(resolved.directory_fd, resolved.path.c_str(), flags);
    register_opened_capability(resolved, descriptor);
    return descriptor;
}

extern "C" int controlled_open_2(const char* path, int flags) {
    Open2Fn function = require_real(real_open_2, "__open_2");
    NativeResolvedPath resolved;
    if (!resolve_checked([&] { return NativeFileSystemResolver::resolve(path); }, true, resolved)) return -1;
    if (!resolved.capability) return function == nullptr ? (errno = ENOSYS, -1)
            : function(resolved.path.c_str(), flags);
    const int descriptor = call_open_resolved(resolved, nullptr, flags, false, 0);
    register_opened_capability(resolved, descriptor);
    return descriptor;
}

extern "C" int controlled_openat_2(int directory, const char* path, int flags) {
    OpenAtCheckedFn function = require_real(real_openat_2, "__openat_2");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    NativeResolvedPath resolved;
    if (!resolve_checked([&] { return NativeFileSystemResolver::resolve_at(directory, path); }, true, resolved)) return -1;
    return function(resolved.directory_fd, resolved.path.c_str(), flags);
}

extern "C" int controlled_openat2(int directory, const char* path,
                                  const struct open_how* how, std::size_t size) {
    if (how == nullptr) { errno = EFAULT; return -1; }
    if (size < sizeof(struct open_how)) { errno = EINVAL; return -1; }
    if (size > sizeof(struct open_how)) { errno = E2BIG; return -1; }
    NativeResolvedPath resolved;
    if (!resolve_checked([&] { return NativeFileSystemResolver::resolve_at(directory, path); },
                         (how->resolve & RESOLVE_NO_SYMLINKS) == 0, resolved)) return -1;
    struct open_how mapped = *how;
    mapped.resolve &= ~(static_cast<std::uint64_t>(RESOLVE_BENEATH)
            | static_cast<std::uint64_t>(RESOLVE_IN_ROOT));
    OpenAt2Fn function = require_real(real_openat2, "openat2");
    if (function != nullptr) {
        const int descriptor = function(resolved.directory_fd, resolved.path.c_str(), &mapped,
                sizeof(mapped));
        register_opened_capability(resolved, descriptor);
        return descriptor;
    }
#ifdef SYS_openat2
    const int descriptor = static_cast<int>(raw_syscall6(SYS_openat2, resolved.directory_fd,
            reinterpret_cast<long>(resolved.path.c_str()), reinterpret_cast<long>(&mapped),
            sizeof(mapped)));
    register_opened_capability(resolved, descriptor);
    return descriptor;
#else
    errno = ENOSYS;
    return -1;
#endif
}

extern "C" int controlled_access(const char* path, int mode) {
    AccessFn function = require_real(real_access, "access");
    NativeResolvedPath resolved;
    if (!resolve_checked([&] { return NativeFileSystemResolver::resolve(path); }, true, resolved)) return -1;
    return call_access_resolved(resolved, function, mode);
}

extern "C" int controlled_faccessat(int directory, const char* path, int mode, int flags) {
    FaccessAtFn function = require_real(real_faccessat, "faccessat");
    if (function == nullptr) { errno = ENOSYS; return -1; }
#ifdef AT_EMPTY_PATH
    if (path != nullptr && path[0] == '\0' && (flags & AT_EMPTY_PATH) != 0) {
        return function(directory, path, mode, flags);
    }
#endif
    NativeResolvedPath resolved;
    if (!resolve_checked([&] { return NativeFileSystemResolver::resolve_at(directory, path); }, true, resolved)) return -1;
    return function(resolved.directory_fd, resolved.path.c_str(), mode, flags);
}

extern "C" int controlled_faccessat2(int directory, const char* path, int mode, int flags) {
#ifdef AT_EMPTY_PATH
    if (path != nullptr && path[0] == '\0' && (flags & AT_EMPTY_PATH) != 0) {
        NativeResolvedPath fd_path;
        if (!resolve_checked([&] { return NativeFileSystemResolver::resolve_fd(directory); }, true, fd_path)) return -1;
        FaccessAt2Fn function = require_real(real_faccessat2, "faccessat2");
        if (function != nullptr) return function(directory, path, mode, flags);
#ifdef SYS_faccessat2
        return static_cast<int>(raw_syscall6(SYS_faccessat2, directory,
                reinterpret_cast<long>(path), mode, flags));
#else
        errno = ENOSYS;
        return -1;
#endif
    }
#endif
    NativeResolvedPath resolved;
    const bool follow = (flags & AT_SYMLINK_NOFOLLOW) == 0;
    if (!resolve_checked([&] { return NativeFileSystemResolver::resolve_at(directory, path); }, follow, resolved)) return -1;
    FaccessAt2Fn function = require_real(real_faccessat2, "faccessat2");
    if (function != nullptr) return function(resolved.directory_fd, resolved.path.c_str(), mode, flags);
#ifdef SYS_faccessat2
    return static_cast<int>(raw_syscall6(SYS_faccessat2, resolved.directory_fd,
            reinterpret_cast<long>(resolved.path.c_str()), mode, flags));
#else
    errno = ENOSYS;
    return -1;
#endif
}

extern "C" int controlled_stat(const char* path, struct stat* value) {
    StatFn function = require_real(real_stat, "stat");
    NativeResolvedPath resolved;
    if (!resolve_checked([&] { return NativeFileSystemResolver::resolve(path); }, true, resolved)) return -1;
    return call_stat_resolved(resolved, function, value, 0);
}

extern "C" int controlled_lstat(const char* path, struct stat* value) {
    StatFn function = require_real(real_lstat, "lstat");
    NativeResolvedPath resolved;
    if (!resolve_checked([&] { return NativeFileSystemResolver::resolve(path); }, false, resolved)) return -1;
    return call_stat_resolved(resolved, function, value, AT_SYMLINK_NOFOLLOW);
}

extern "C" int controlled_fstatat(int directory, const char* path, struct stat* value, int flags) {
    FstatAtFn function = require_real(real_fstatat, "fstatat");
    if (function == nullptr) { errno = ENOSYS; return -1; }
#ifdef AT_EMPTY_PATH
    if (path != nullptr && path[0] == '\0' && (flags & AT_EMPTY_PATH) != 0) {
        return function(directory, path, value, flags);
    }
#endif
    const bool follow = (flags & AT_SYMLINK_NOFOLLOW) == 0;
    NativeResolvedPath resolved;
    if (!resolve_checked([&] { return NativeFileSystemResolver::resolve_at(directory, path); }, follow, resolved)) return -1;
    return function(resolved.directory_fd, resolved.path.c_str(), value, flags);
}

extern "C" int controlled_statx(int directory, const char* path, int flags,
                                unsigned int mask, struct statx* value) {
    if (value == nullptr) { errno = EFAULT; return -1; }
#ifdef AT_EMPTY_PATH
    if (path != nullptr && path[0] == '\0' && (flags & AT_EMPTY_PATH) != 0) {
        NativeResolvedPath fd_path;
        if (!resolve_checked([&] { return NativeFileSystemResolver::resolve_fd(directory); }, true, fd_path)) return -1;
        StatxFn function = require_real(real_statx, "statx");
        if (function != nullptr) return function(directory, path, flags, mask, value);
#ifdef SYS_statx
        return static_cast<int>(raw_syscall6(SYS_statx, directory,
                reinterpret_cast<long>(path), flags, mask, reinterpret_cast<long>(value)));
#else
        errno = ENOSYS;
        return -1;
#endif
    }
#endif
    const bool follow = (flags & AT_SYMLINK_NOFOLLOW) == 0;
    NativeResolvedPath resolved;
    if (!resolve_checked([&] { return NativeFileSystemResolver::resolve_at(directory, path); }, follow, resolved)) return -1;
    StatxFn function = require_real(real_statx, "statx");
    if (function != nullptr) return function(resolved.directory_fd, resolved.path.c_str(), flags, mask, value);
#ifdef SYS_statx
    return static_cast<int>(raw_syscall6(SYS_statx, resolved.directory_fd,
            reinterpret_cast<long>(resolved.path.c_str()), flags, mask,
            reinterpret_cast<long>(value)));
#else
    errno = ENOSYS;
    return -1;
#endif
}

extern "C" int controlled_renameat2(int old_directory, const char* old_path,
                                    int new_directory, const char* new_path,
                                    unsigned int flags) {
    NativeResolvedPath old_resolved;
    NativeResolvedPath new_resolved;
    if (!resolve_checked([&] { return NativeFileSystemResolver::resolve_at(old_directory, old_path); },
                         false, old_resolved)) return -1;
    if (!resolve_checked([&] { return NativeFileSystemResolver::resolve_at(new_directory, new_path); },
                         false, new_resolved)) return -1;
    try {
        NativeFileSystemResolver::validate_same_confinement(old_resolved, new_resolved);
    } catch (const PathPolicyError& error) {
        errno = error.error_number();
        return -1;
    }
    RenameAt2Fn function = require_real(real_renameat2, "renameat2");
    if (function != nullptr) return function(old_resolved.directory_fd, old_resolved.path.c_str(),
            new_resolved.directory_fd, new_resolved.path.c_str(), flags);
#ifdef SYS_renameat2
    return static_cast<int>(raw_syscall6(SYS_renameat2, old_resolved.directory_fd,
            reinterpret_cast<long>(old_resolved.path.c_str()), new_resolved.directory_fd,
            reinterpret_cast<long>(new_resolved.path.c_str()), flags));
#else
    errno = ENOSYS;
    return -1;
#endif
}

extern "C" int controlled_rename(const char* old_path, const char* new_path) {
    RenameFn function = require_real(real_rename, "rename");
    NativeResolvedPath old_resolved;
    NativeResolvedPath new_resolved;
    if (!resolve_checked([&] { return NativeFileSystemResolver::resolve(old_path); }, false,
                         old_resolved)) return -1;
    if (!resolve_checked([&] { return NativeFileSystemResolver::resolve(new_path); }, false,
                         new_resolved)) return -1;
    try {
        NativeFileSystemResolver::validate_same_confinement(old_resolved, new_resolved);
    } catch (const PathPolicyError& error) {
        errno = error.error_number();
        return -1;
    }
    if (old_resolved.capability || new_resolved.capability) {
        RenameAtFn at = require_real(real_renameat, "renameat");
        if (at == nullptr) { errno = ENOSYS; return -1; }
        return at(old_resolved.directory_fd, old_resolved.path.c_str(),
                new_resolved.directory_fd, new_resolved.path.c_str());
    }
    if (function == nullptr) { errno = ENOSYS; return -1; }
    return function(old_resolved.path.c_str(), new_resolved.path.c_str());
}

extern "C" int controlled_renameat(int old_directory, const char* old_path,
                                    int new_directory, const char* new_path) {
    RenameAtFn function = require_real(real_renameat, "renameat");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    NativeResolvedPath old_resolved;
    NativeResolvedPath new_resolved;
    if (!resolve_checked([&] { return NativeFileSystemResolver::resolve_at(old_directory, old_path); }, false,
                         old_resolved)) return -1;
    if (!resolve_checked([&] { return NativeFileSystemResolver::resolve_at(new_directory, new_path); }, false,
                         new_resolved)) return -1;
    try {
        NativeFileSystemResolver::validate_same_confinement(old_resolved, new_resolved);
    } catch (const PathPolicyError& error) {
        errno = error.error_number();
        return -1;
    }
    return function(old_resolved.directory_fd, old_resolved.path.c_str(),
            new_resolved.directory_fd, new_resolved.path.c_str());
}

extern "C" int controlled_unlink(const char* path) {
    UnlinkFn function = require_real(real_unlink, "unlink");
    NativeResolvedPath resolved;
    if (!resolve_checked([&] { return NativeFileSystemResolver::resolve(path); }, false, resolved)) {
        return -1;
    }
    if (resolved.capability) {
        UnlinkAtFn at = require_real(real_unlinkat, "unlinkat");
        if (at == nullptr) { errno = ENOSYS; return -1; }
        return at(resolved.directory_fd, resolved.path.c_str(), 0);
    }
    if (function == nullptr) { errno = ENOSYS; return -1; }
    return function(resolved.path.c_str());
}

extern "C" int controlled_unlinkat(int directory, const char* path, int flags) {
    UnlinkAtFn function = require_real(real_unlinkat, "unlinkat");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    NativeResolvedPath resolved;
    if (!resolve_checked([&] { return NativeFileSystemResolver::resolve_at(directory, path); }, false,
                         resolved)) return -1;
    return function(resolved.directory_fd, resolved.path.c_str(), flags);
}

extern "C" int controlled_mkdir(const char* path, mode_t mode) {
    MkdirFn function = require_real(real_mkdir, "mkdir");
    NativeResolvedPath resolved;
    if (!resolve_checked([&] { return NativeFileSystemResolver::resolve(path); }, false, resolved)) {
        return -1;
    }
    if (resolved.capability) {
        MkdirAtFn at = require_real(real_mkdirat, "mkdirat");
        if (at == nullptr) { errno = ENOSYS; return -1; }
        const int status = at(resolved.directory_fd, resolved.path.c_str(), mode);
        if (status == 0) return status;
        return status;
    }
    if (function == nullptr) { errno = ENOSYS; return -1; }
    return function(resolved.path.c_str(), mode);
}

extern "C" int controlled_mkdirat(int directory, const char* path, mode_t mode) {
    MkdirAtFn function = require_real(real_mkdirat, "mkdirat");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    NativeResolvedPath resolved;
    if (!resolve_checked([&] { return NativeFileSystemResolver::resolve_at(directory, path); }, false,
                         resolved)) return -1;
    return function(resolved.directory_fd, resolved.path.c_str(), mode);
}

extern "C" int controlled_rmdir(const char* path) {
    RmdirFn function = require_real(real_rmdir, "rmdir");
    NativeResolvedPath resolved;
    if (!resolve_checked([&] { return NativeFileSystemResolver::resolve(path); }, false, resolved)) {
        return -1;
    }
    if (resolved.capability) {
        UnlinkAtFn at = require_real(real_unlinkat, "unlinkat");
        if (at == nullptr) { errno = ENOSYS; return -1; }
        return at(resolved.directory_fd, resolved.path.c_str(), AT_REMOVEDIR);
    }
    if (function == nullptr) { errno = ENOSYS; return -1; }
    return function(resolved.path.c_str());
}

extern "C" DIR* controlled_opendir(const char* path) {
    OpendirFn function = require_real(real_opendir, "opendir");
    NativeResolvedPath resolved;
    if (!resolve_checked([&] { return NativeFileSystemResolver::resolve(path); }, true, resolved)) {
        return nullptr;
    }
    if (resolved.capability) {
        const int descriptor = call_open_resolved(resolved, nullptr, O_RDONLY | O_DIRECTORY,
                false, 0);
        if (descriptor < 0) return nullptr;
        register_opened_capability(resolved, descriptor);
        return fdopendir(descriptor);
    }
    if (function == nullptr) { errno = ENOSYS; return nullptr; }
    return function(resolved.path.c_str());
}

extern "C" ssize_t controlled_getdents64(int directory, void* buffer, std::size_t size) {
    if (buffer == nullptr && size > 0) { errno = EFAULT; return -1; }
    NativeResolvedPath resolved;
    if (!resolve_checked([&] { return NativeFileSystemResolver::resolve_fd(directory); }, true, resolved)) return -1;
    Getdents64Fn function = require_real(real_getdents64, "getdents64");
    if (function != nullptr) return function(directory, buffer, size);
#ifdef SYS_getdents64
    return static_cast<ssize_t>(raw_syscall6(SYS_getdents64, directory,
            reinterpret_cast<long>(buffer), size));
#else
    errno = ENOSYS;
    return -1;
#endif
}

extern "C" void* controlled_mmap(void* address, std::size_t length, int protection,
                                 int flags, int file_descriptor, off_t offset) {
    MmapFn function = require_real(real_mmap, "mmap");
    if (function == nullptr) { errno = ENOSYS; return MAP_FAILED; }
    if (file_descriptor >= 0 && (flags & MAP_ANONYMOUS) == 0) {
        NativeResolvedPath resolved;
        if (!resolve_checked([&] { return NativeFileSystemResolver::resolve_fd(file_descriptor); },
                             true, resolved)) return MAP_FAILED;
        try {
            NativeFileSystemResolver::validate_confinement(resolved, true);
        } catch (const PathPolicyError& error) {
            errno = error.error_number();
            return MAP_FAILED;
        }
    }
    return function(address, length, protection, flags, file_descriptor, offset);
}

ssize_t copy_readlink_value(const std::string& value, char* buffer, size_t size) {
    if (size == 0) { errno = EINVAL; return -1; }
    if (buffer == nullptr) { errno = EFAULT; return -1; }
    const std::size_t count = std::min(size, value.size());
    std::memcpy(buffer, value.data(), count);
    return static_cast<ssize_t>(count);
}

template <typename Call>
ssize_t controlled_readlink_common(Call&& call, char* buffer, size_t size) {
    if (size == 0) { errno = EINVAL; return -1; }
    if (buffer == nullptr) { errno = EFAULT; return -1; }
    std::vector<char> raw(256);
    for (;;) {
        const ssize_t length = call(raw.data(), raw.size());
        if (length < 0) return -1;
        if (static_cast<std::size_t>(length) < raw.size()) {
            const std::string rewritten = NativeFileSystemResolver::rewrite_readlink_result(
                    std::string_view(raw.data(), static_cast<std::size_t>(length)));
            return copy_readlink_value(rewritten, buffer, size);
        }
        if (raw.size() >= 65536U) { errno = ENAMETOOLONG; return -1; }
        raw.resize(raw.size() * 2U);
    }
}

extern "C" ssize_t controlled_readlink(const char* path, char* buffer, size_t size) {
    ReadlinkFn function = require_real(real_readlink, "readlink");
    if (is_own_proc_fd_path(path)) {
        if (function == nullptr) { errno = ENOSYS; return -1; }
        return controlled_readlink_common([&](char* out, size_t capacity) {
            return function(path, out, capacity);
        }, buffer, size);
    }
    NativeResolvedPath resolved;
    if (!resolve_checked([&] { return NativeFileSystemResolver::resolve(path); }, false, resolved)) return -1;
    if (resolved.capability) {
        ReadlinkAtFn at = require_real(real_readlinkat, "readlinkat");
        if (at == nullptr) { errno = ENOSYS; return -1; }
        return controlled_readlink_common([&](char* out, size_t capacity) {
            return at(resolved.directory_fd, resolved.path.c_str(), out, capacity);
        }, buffer, size);
    }
    if (function == nullptr) { errno = ENOSYS; return -1; }
    return controlled_readlink_common([&](char* out, size_t capacity) {
        return function(resolved.path.c_str(), out, capacity);
    }, buffer, size);
}

extern "C" ssize_t controlled_readlinkat(int directory, const char* path, char* buffer, size_t size) {
    ReadlinkAtFn function = require_real(real_readlinkat, "readlinkat");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    if (directory == AT_FDCWD && is_own_proc_fd_path(path)) {
        return controlled_readlink_common([&](char* out, size_t capacity) {
            return function(directory, path, out, capacity);
        }, buffer, size);
    }
    if (path != nullptr && path[0] == '\0' && directory != AT_FDCWD) {
        return controlled_readlink_common([&](char* out, size_t capacity) {
            return function(directory, path, out, capacity);
        }, buffer, size);
    }
    NativeResolvedPath resolved;
    if (!resolve_checked([&] { return NativeFileSystemResolver::resolve_at(directory, path); }, false, resolved)) return -1;
    return controlled_readlink_common([&](char* out, size_t capacity) {
        return function(resolved.directory_fd, resolved.path.c_str(), out, capacity);
    }, buffer, size);
}

extern "C" int controlled_setsockopt(int socket_fd, int level, int option_name,
                                      const void* option_value, socklen_t option_length) {
    bool handled = false;
    const int virtual_status = native_set_virtual_socket_option(
            socket_fd, level, option_name, option_value, option_length, &handled);
    if (handled) return virtual_status;
    SetSockOptFn function = require_real(real_setsockopt, "setsockopt");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    return function(socket_fd, level, option_name, option_value, option_length);
}

extern "C" int controlled_getsockopt(int socket_fd, int level, int option_name,
                                      void* option_value, socklen_t* option_length) {
    bool handled = false;
    const int virtual_status = native_get_virtual_socket_option(
            socket_fd, level, option_name, option_value, option_length, &handled);
    if (handled) return virtual_status;
    GetSockOptFn function = require_real(real_getsockopt, "getsockopt");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    return function(socket_fd, level, option_name, option_value, option_length);
}

extern "C" unsigned int controlled_if_nametoindex(const char* name) {
    return native_virtual_if_nametoindex(name);
}

extern "C" char* controlled_if_indextoname(unsigned int index, char* name) {
    return native_virtual_if_indextoname(index, name);
}

extern "C" int controlled_getaddrinfo(const char* node, const char* service,
                                       const addrinfo* hints, addrinfo** result) {
    GetAddrInfoFn function = require_real(real_getaddrinfo, "getaddrinfo");
    FreeAddrInfoFn release = require_real(real_freeaddrinfo, "freeaddrinfo");
    if (function == nullptr || release == nullptr) return EAI_SYSTEM;
    if (result == nullptr || (node != nullptr && !global_policy().allow_host(node))) return EAI_NONAME;
    *result = nullptr;
    const int status = function(node, service, hints, result);
    if (status != 0 || *result == nullptr) return status;
    for (addrinfo* item = *result; item != nullptr; item = item->ai_next) {
        if (item->ai_addr != nullptr && !native_socket_address_allowed(item->ai_addr, item->ai_addrlen)) {
            release(*result); *result = nullptr; return EAI_NONAME;
        }
    }
    return 0;
}

extern "C" int controlled_getnameinfo(const sockaddr* address, socklen_t address_length,
                                       char* host, socklen_t host_length,
                                       char* service, socklen_t service_length, int flags) {
    GetNameInfoFn function = require_real(real_getnameinfo, "getnameinfo");
    if (function == nullptr) return EAI_SYSTEM;
    if (!native_socket_address_allowed(address, address_length)) return EAI_NONAME;
    const int status = function(address, address_length, host, host_length, service, service_length,
                                flags | NI_NUMERICHOST);
    if (status != 0) return status;
    if (host != nullptr && host_length > 0 && (flags & NI_NUMERICHOST) == 0) {
        const std::string value = native_virtual_hostname();
        if (std::cmp_greater(value.size() + 1, host_length)) return EAI_OVERFLOW;
        std::memcpy(host, value.c_str(), value.size() + 1);
    }
    return 0;
}

extern "C" int controlled_gethostname(char* name, std::size_t length) {
    if (name == nullptr || length == 0) { errno = EINVAL; return -1; }
    try {
        const std::string value = native_virtual_hostname();
        if (value.size() + 1 > length) { errno = ENAMETOOLONG; return -1; }
        std::memcpy(name, value.c_str(), value.size() + 1);
        return 0;
    } catch (...) { errno = EACCES; return -1; }
}

extern "C" int controlled_uname(struct utsname* value) {
    UnameFn function = require_real(real_uname, "uname");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    if (value == nullptr) { errno = EFAULT; return -1; }
    if (function(value) != 0) return -1;
    try {
        const std::string hostname = native_virtual_hostname();
        std::memset(value->nodename, 0, sizeof(value->nodename));
        std::memcpy(value->nodename, hostname.data(), std::min(hostname.size(), sizeof(value->nodename) - 1));
        return 0;
    } catch (...) { errno = EACCES; return -1; }
}

extern "C" int controlled_getifaddrs(ifaddrs** result) {
    return native_project_ifaddrs(result);
}

extern "C" void controlled_freeifaddrs(ifaddrs* value) {
    if (native_free_projected_ifaddrs(value)) return;
    FreeIfAddrsFn function = require_real(real_freeifaddrs, "freeifaddrs");
    if (function != nullptr) function(value);
}

int controlled_audio_start(void* handle, const char* api, std::atomic<AudioCallFn>& storage,
                           std::map<void*, std::uint64_t>& handles) {
    AudioCallFn function = require_real(storage, api);
    if (function == nullptr) return -38;
    const std::uint64_t token = global_audio_capture_policy().begin(api);
    if (token == 0) return -1;
    const int status = function(handle);
    if (status != 0) { global_audio_capture_policy().end(token); return status; }
    std::lock_guard lock(audio_handles_mutex);
    handles[handle] = token;
    return status;
}

int controlled_audio_stop(void* handle, const char* api, std::atomic<AudioCallFn>& storage,
                          std::map<void*, std::uint64_t>& handles) {
    AudioCallFn function = require_real(storage, api);
    if (function == nullptr) return -38;
    const int status = function(handle);
    std::uint64_t token = 0;
    { std::lock_guard lock(audio_handles_mutex); auto found = handles.find(handle);
      if (found != handles.end()) { token = found->second; handles.erase(found); } }
    global_audio_capture_policy().end(token);
    return status;
}

extern "C" int controlled_AAudioStream_requestStart(void* stream) {
    return controlled_audio_start(stream, "AAudioStream_requestStart", real_aaudio_start, aaudio_handles);
}
extern "C" int controlled_AAudioStream_requestStop(void* stream) {
    return controlled_audio_stop(stream, "AAudioStream_requestStop", real_aaudio_stop, aaudio_handles);
}
extern "C" int controlled_AMediaRecorder_start(void* recorder) {
    return controlled_audio_start(recorder, "AMediaRecorder_start", real_media_recorder_start, media_recorder_handles);
}
extern "C" int controlled_AMediaRecorder_stop(void* recorder) {
    return controlled_audio_stop(recorder, "AMediaRecorder_stop", real_media_recorder_stop, media_recorder_handles);
}

bool refresh_loaded_handle(void* handle) {
    if (handle == nullptr || inside_refresh) return handle != nullptr;
    inside_refresh = true;
    const bool refreshed = global_hooks().refresh();
    inside_refresh = false;
    if (!refreshed) {
        (void) dlclose(handle);
        errno = EACCES;
        return false;
    }
    return true;
}

extern "C" void* controlled_dlopen(const char* name, int flags) {
    DlopenFn function = require_real(real_dlopen, "dlopen");
    if (function == nullptr) { errno = ENOSYS; return nullptr; }
    try {
        const NativeLibraryDecision decision = NativeLibraryLoaderPolicy::resolve(name);
        NativeLibraryLoaderPolicy::validate_library(decision);
        void* handle = function(decision.resolved_name.c_str(), flags);
        log_native_binding("dlopen", name, decision.resolved_name.c_str(), flags, nullptr, handle);
        return refresh_loaded_handle(handle) ? handle : nullptr;
    } catch (const PathPolicyError& error) {
        NativeLibraryLoaderPolicy::record_denial(error.what());
        errno = error.error_number();
        return nullptr;
    } catch (...) {
        NativeLibraryLoaderPolicy::record_denial("DLOPEN_UNEXPECTED_FAILURE");
        errno = EACCES;
        return nullptr;
    }
}

extern "C" void* controlled_android_dlopen_ext(const char* name, int flags,
                                                const android_dlextinfo* info) {
    AndroidDlopenExtFn function = require_real(real_android_dlopen_ext, "android_dlopen_ext");
    if (function == nullptr) { errno = ENOSYS; return nullptr; }
    try {
        const std::uint64_t extension_flags = info == nullptr ? 0 : info->flags;
        NativeLibraryLoaderPolicy::validate_android_dlext(
                extension_flags,
                info == nullptr ? -1 : info->library_fd,
                info == nullptr ? 0 : info->library_fd_offset,
                info == nullptr ? -1 : info->relro_fd,
                info == nullptr ? nullptr : info->reserved_addr,
                info == nullptr ? 0 : info->reserved_size,
                info != nullptr && info->library_namespace != nullptr);
        if (info != nullptr && (info->flags & ANDROID_DLEXT_USE_LIBRARY_FD) != 0) {
            NativeResolvedPath descriptor = NativeFileSystemResolver::resolve_fd(info->library_fd);
            NativeFileSystemResolver::validate_confinement(descriptor, true);
        }
        std::string resolved_name;
        const char* call_name = name;
        if (name != nullptr && name[0] != '\0') {
            NativeLibraryDecision decision = NativeLibraryLoaderPolicy::resolve(name);
            NativeLibraryLoaderPolicy::validate_library(decision);
            resolved_name = std::move(decision.resolved_name);
            call_name = resolved_name.c_str();
        } else if (info == nullptr || (info->flags & ANDROID_DLEXT_USE_LIBRARY_FD) == 0) {
            throw PathPolicyError(EACCES, "ANDROID_DLOPEN_EXT_SOURCE_REQUIRED");
        }
        void* handle = function(call_name, flags, info);
        log_native_binding("android_dlopen_ext", name, call_name, flags, info, handle);
        return refresh_loaded_handle(handle) ? handle : nullptr;
    } catch (const PathPolicyError& error) {
        NativeLibraryLoaderPolicy::record_denial(error.what());
        errno = error.error_number();
        return nullptr;
    } catch (...) {
        NativeLibraryLoaderPolicy::record_denial("ANDROID_DLOPEN_EXT_UNEXPECTED_FAILURE");
        errno = EACCES;
        return nullptr;
    }
}

bool terminating_signal(int signal_number) {
    return signal_number == SIGKILL || signal_number == SIGTERM || signal_number == SIGABRT;
}

bool self_process_target(pid_t pid) {
    const pid_t me = getpid();
    return pid == me || pid == 0 || pid == -me;
}

bool block_self_termination(pid_t pid, int signal_number) {
    return !process_exit_allowed.load(std::memory_order_acquire)
            && self_process_target(pid)
            && terminating_signal(signal_number);
}

extern "C" int controlled_kill(pid_t pid, int signal_number) {
    if (block_self_termination(pid, signal_number)) {
        __android_log_print(ANDROID_LOG_INFO, "CS_GUEST_LIFETIME",
                "guest self-signal ignored pid=%d sig=%d", static_cast<int>(pid), signal_number);
        return 0;
    }
    KillFn function = require_real(real_kill, "kill");
    if (function == nullptr) {
        errno = ENOSYS;
        return -1;
    }
    return function(pid, signal_number);
}

extern "C" int controlled_killpg(int process_group, int signal_number) {
    if (block_self_termination(static_cast<pid_t>(-process_group), signal_number)
            || block_self_termination(static_cast<pid_t>(process_group), signal_number)) {
        __android_log_print(ANDROID_LOG_INFO, "CS_GUEST_LIFETIME",
                "guest self-killpg ignored pgid=%d sig=%d", process_group, signal_number);
        return 0;
    }
    KillPgFn function = require_real(real_killpg, "killpg");
    if (function == nullptr) {
        errno = ENOSYS;
        return -1;
    }
    return function(process_group, signal_number);
}

extern "C" int controlled_tgkill(int process_id, int thread_id, int signal_number) {
    if (block_self_termination(static_cast<pid_t>(process_id), signal_number)) {
        __android_log_print(ANDROID_LOG_INFO, "CS_GUEST_LIFETIME",
                "guest self-tgkill ignored pid=%d tid=%d sig=%d",
                process_id, thread_id, signal_number);
        return 0;
    }
    TgKillFn function = require_real(real_tgkill, "tgkill");
    if (function == nullptr) {
        return static_cast<int>(raw_syscall6(SYS_tgkill, process_id, thread_id, signal_number));
    }
    return function(process_id, thread_id, signal_number);
}

extern "C" int controlled_tkill(int thread_id, int signal_number) {
    if (block_self_termination(getpid(), signal_number)) {
        __android_log_print(ANDROID_LOG_INFO, "CS_GUEST_LIFETIME",
                "guest self-tkill ignored tid=%d sig=%d", thread_id, signal_number);
        return 0;
    }
    TKillFn function = require_real(real_tkill, "tkill");
    if (function == nullptr) {
        errno = ENOSYS;
        return -1;
    }
    return function(thread_id, signal_number);
}

extern "C" void controlled_exit(int status) {
    if (!process_exit_allowed.load(std::memory_order_acquire)) {
        __android_log_print(ANDROID_LOG_INFO, "CS_GUEST_LIFETIME",
                "guest exit(%d) ignored", status);
        return;
    }
    ExitFn function = require_real(real_exit, "exit");
    if (function != nullptr) function(status);
    _exit(status);
}

extern "C" void controlled_abort() {
    if (!process_exit_allowed.load(std::memory_order_acquire)) {
        __android_log_print(ANDROID_LOG_INFO, "CS_GUEST_LIFETIME",
                "guest abort() ignored");
        return;
    }
    AbortFn function = require_real(real_abort, "abort");
    if (function != nullptr) function();
    raw_syscall6(SYS_exit_group, 134);
}

extern "C" void controlled_underscore_exit(int status) {
    if (!process_exit_allowed.load(std::memory_order_acquire)) {
        __android_log_print(ANDROID_LOG_INFO, "CS_GUEST_LIFETIME",
                "guest _exit(%d) ignored", status);
        return;
    }
    ExitFn function = require_real(real_underscore_exit, "_exit");
    if (function != nullptr) function(status);
    raw_syscall6(SYS_exit_group, status);
}

/**
 * Mediate the libc syscall() entry for Guest modules.  Inline assembly and a raw SVC/SYSCALL
 * instruction cannot be intercepted by a userspace PLT rewrite; this function nevertheless closes
 * the common JNI/NDK escape hatch where libraries call syscall(SYS_openat/ SYS_connect) directly.
 * Unknown calls are forwarded with the Linux six-argument ABI so futex, clock and ART support
 * syscalls continue to work.  The native policy remains a compatibility boundary, not a hostile
 * code security boundary, until the process is additionally protected by seccomp.
 */
extern "C" long controlled_syscall(long number, ...) {
    va_list values;
    va_start(values, number);

#define CS_CALL1(fn, t1) { t1 a1 = va_arg(values, t1); va_end(values); return fn(a1); }
#define CS_CALL2(fn, t1, t2) { t1 a1 = va_arg(values, t1); t2 a2 = va_arg(values, t2); va_end(values); return fn(a1, a2); }
#define CS_CALL3(fn, t1, t2, t3) { t1 a1 = va_arg(values, t1); t2 a2 = va_arg(values, t2); t3 a3 = va_arg(values, t3); va_end(values); return fn(a1, a2, a3); }
#define CS_CALL4(fn, t1, t2, t3, t4) { t1 a1 = va_arg(values, t1); t2 a2 = va_arg(values, t2); t3 a3 = va_arg(values, t3); t4 a4 = va_arg(values, t4); va_end(values); return fn(a1, a2, a3, a4); }
#define CS_CALL5(fn, t1, t2, t3, t4, t5) { t1 a1 = va_arg(values, t1); t2 a2 = va_arg(values, t2); t3 a3 = va_arg(values, t3); t4 a4 = va_arg(values, t4); t5 a5 = va_arg(values, t5); va_end(values); return fn(a1, a2, a3, a4, a5); }
#define CS_CALL6(fn, t1, t2, t3, t4, t5, t6) { t1 a1 = va_arg(values, t1); t2 a2 = va_arg(values, t2); t3 a3 = va_arg(values, t3); t4 a4 = va_arg(values, t4); t5 a5 = va_arg(values, t5); t6 a6 = va_arg(values, t6); va_end(values); return fn(a1, a2, a3, a4, a5, a6); }

#if defined(SYS_open)
    if (number == SYS_open) {
        const char* p = va_arg(values, const char*);
        const int f = va_arg(values, int);
        if (requires_mode(f)) { const mode_t m = static_cast<mode_t>(va_arg(values, int)); va_end(values); return controlled_open(p, f, m); }
        va_end(values); return controlled_open(p, f);
    }
#endif
#if defined(SYS_openat)
    if (number == SYS_openat) {
        const int d = va_arg(values, int);
        const char* p = va_arg(values, const char*);
        const int f = va_arg(values, int);
        if (requires_mode(f)) { const mode_t m = static_cast<mode_t>(va_arg(values, int)); va_end(values); return controlled_openat(d, p, f, m); }
        va_end(values); return controlled_openat(d, p, f);
    }
#endif
#if defined(SYS_openat2)
    if (number == SYS_openat2) CS_CALL4(controlled_openat2, int, const char*, const struct open_how*, std::size_t)
#endif
#if defined(SYS_access)
    if (number == SYS_access) CS_CALL2(controlled_access, const char*, int)
#endif
#if defined(SYS_faccessat)
    if (number == SYS_faccessat) CS_CALL4(controlled_faccessat, int, const char*, int, int)
#endif
#if defined(SYS_faccessat2)
    if (number == SYS_faccessat2) CS_CALL4(controlled_faccessat2, int, const char*, int, int)
#endif
#if defined(SYS_stat)
    if (number == SYS_stat) CS_CALL2(controlled_stat, const char*, struct stat*)
#endif
#if defined(SYS_lstat)
    if (number == SYS_lstat) CS_CALL2(controlled_lstat, const char*, struct stat*)
#endif
#if defined(SYS_newfstatat)
    if (number == SYS_newfstatat) CS_CALL4(controlled_fstatat, int, const char*, struct stat*, int)
#endif
#if defined(SYS_fstatat64)
    if (number == SYS_fstatat64) CS_CALL4(controlled_fstatat, int, const char*, struct stat*, int)
#endif
#if defined(SYS_statx)
    if (number == SYS_statx) CS_CALL5(controlled_statx, int, const char*, int, unsigned int, struct statx*)
#endif
#if defined(SYS_rename)
    if (number == SYS_rename) CS_CALL2(controlled_rename, const char*, const char*)
#endif
#if defined(SYS_renameat)
    if (number == SYS_renameat) CS_CALL4(controlled_renameat, int, const char*, int, const char*)
#endif
#if defined(SYS_renameat2)
    if (number == SYS_renameat2) CS_CALL5(controlled_renameat2, int, const char*, int, const char*, unsigned int)
#endif
#if defined(SYS_unlink)
    if (number == SYS_unlink) CS_CALL1(controlled_unlink, const char*)
#endif
#if defined(SYS_unlinkat)
    if (number == SYS_unlinkat) CS_CALL3(controlled_unlinkat, int, const char*, int)
#endif
#if defined(SYS_mkdir)
    if (number == SYS_mkdir) CS_CALL2(controlled_mkdir, const char*, mode_t)
#endif
#if defined(SYS_mkdirat)
    if (number == SYS_mkdirat) CS_CALL3(controlled_mkdirat, int, const char*, mode_t)
#endif
#if defined(SYS_rmdir)
    if (number == SYS_rmdir) CS_CALL1(controlled_rmdir, const char*)
#endif
#if defined(SYS_readlink)
    if (number == SYS_readlink) CS_CALL3(controlled_readlink, const char*, char*, std::size_t)
#endif
#if defined(SYS_readlinkat)
    if (number == SYS_readlinkat) CS_CALL4(controlled_readlinkat, int, const char*, char*, std::size_t)
#endif
#if defined(SYS_getdents64)
    if (number == SYS_getdents64) CS_CALL3(controlled_getdents64, int, void*, std::size_t)
#endif
#if defined(SYS_mmap)
    if (number == SYS_mmap) {
        void* addr = va_arg(values, void*);
        const std::size_t len = va_arg(values, std::size_t);
        const int prot = va_arg(values, int);
        const int fl = va_arg(values, int);
        const int fd = va_arg(values, int);
        const off_t off = va_arg(values, off_t);
        va_end(values);
        return reinterpret_cast<long>(controlled_mmap(addr, len, prot, fl, fd, off));
    }
#endif
#if defined(SYS_socket)
    if (number == SYS_socket) CS_CALL3(controlled_socket, int, int, int)
#endif
#if defined(SYS_fcntl)
    if (number == SYS_fcntl) CS_CALL3(controlled_fcntl, int, int, long)
#endif
#if defined(SYS_fcntl64)
    if (number == SYS_fcntl64) CS_CALL3(controlled_fcntl, int, int, long)
#endif
#if defined(SYS_close)
    if (number == SYS_close) CS_CALL1(controlled_close, int)
#endif
#if defined(SYS_dup)
    if (number == SYS_dup) CS_CALL1(controlled_dup, int)
#endif
#if defined(SYS_dup2)
    if (number == SYS_dup2) CS_CALL2(controlled_dup2, int, int)
#endif
#if defined(SYS_dup3)
    if (number == SYS_dup3) CS_CALL3(controlled_dup3, int, int, int)
#endif
#if defined(SYS_bind)
    if (number == SYS_bind) CS_CALL3(controlled_bind, int, const sockaddr*, socklen_t)
#endif
#if defined(SYS_connect)
    if (number == SYS_connect) CS_CALL3(controlled_connect, int, const sockaddr*, socklen_t)
#endif
#if defined(SYS_send)
    if (number == SYS_send) CS_CALL4(controlled_send, int, const void*, std::size_t, int)
#endif
#if defined(SYS_sendto)
    if (number == SYS_sendto) CS_CALL6(controlled_sendto, int, const void*, std::size_t, int, const sockaddr*, socklen_t)
#endif
#if defined(SYS_sendmsg)
    if (number == SYS_sendmsg) CS_CALL3(controlled_sendmsg, int, const msghdr*, int)
#endif
#if defined(SYS_recvfrom)
    if (number == SYS_recvfrom) CS_CALL6(controlled_recvfrom, int, void*, std::size_t, int, sockaddr*, socklen_t*)
#endif
#if defined(SYS_recv)
    if (number == SYS_recv) CS_CALL4(controlled_recv, int, void*, std::size_t, int)
#endif
#if defined(SYS_recvmsg)
    if (number == SYS_recvmsg) CS_CALL3(controlled_recvmsg, int, msghdr*, int)
#endif
#if defined(SYS_setsockopt)
    if (number == SYS_setsockopt) CS_CALL5(controlled_setsockopt, int, int, int, const void*, socklen_t)
#endif
#if defined(SYS_getsockopt)
    if (number == SYS_getsockopt) CS_CALL5(controlled_getsockopt, int, int, int, void*, socklen_t*)
#endif
#if defined(SYS_read)
    if (number == SYS_read) CS_CALL3(controlled_read, int, void*, std::size_t)
#endif
#if defined(SYS_write)
    if (number == SYS_write) CS_CALL3(controlled_write, int, const void*, std::size_t)
#endif
#if defined(SYS_accept)
    if (number == SYS_accept) CS_CALL3(controlled_accept, int, sockaddr*, socklen_t*)
#endif
#if defined(SYS_accept4)
    if (number == SYS_accept4) CS_CALL4(controlled_accept4, int, sockaddr*, socklen_t*, int)
#endif
#if defined(SYS_getsockname)
    if (number == SYS_getsockname) CS_CALL3(controlled_getsockname, int, sockaddr*, socklen_t*)
#endif
#if defined(SYS_getpeername)
    if (number == SYS_getpeername) CS_CALL3(controlled_getpeername, int, sockaddr*, socklen_t*)
#endif
#if defined(SYS_kill)
    if (number == SYS_kill) CS_CALL2(controlled_kill, pid_t, int)
#endif
#if defined(SYS_tgkill)
    if (number == SYS_tgkill) CS_CALL3(controlled_tgkill, int, int, int)
#endif
#if defined(SYS_tkill)
    if (number == SYS_tkill) CS_CALL2(controlled_tkill, int, int)
#endif
#if defined(SYS_exit)
    if (number == SYS_exit
#if defined(SYS_exit_group)
            || number == SYS_exit_group
#endif
    ) {
        const int status = va_arg(values, int);
        va_end(values);
        if (!process_exit_allowed.load(std::memory_order_acquire)) {
            __android_log_print(ANDROID_LOG_INFO, "CS_GUEST_LIFETIME", "guest direct syscall exit(%d) ignored", status);
            return 0;
        }
        SyscallFn function = require_real(real_syscall, "syscall");
        return function == nullptr ? -1 : function(number, status);
    }
#elif defined(SYS_exit_group)
    if (number == SYS_exit_group) {
        const int status = va_arg(values, int);
        va_end(values);
        if (!process_exit_allowed.load(std::memory_order_acquire)) {
            __android_log_print(ANDROID_LOG_INFO, "CS_GUEST_LIFETIME", "guest direct syscall exit(%d) ignored", status);
            return 0;
        }
        SyscallFn function = require_real(real_syscall, "syscall");
        return function == nullptr ? -1 : function(number, status);
    }
#endif

#undef CS_CALL1
#undef CS_CALL2
#undef CS_CALL3
#undef CS_CALL4
#undef CS_CALL5
#undef CS_CALL6

    long arguments[6]{};
    for (long& argument : arguments) argument = va_arg(values, long);
    va_end(values);
    SyscallFn function = require_real(real_syscall, "syscall");
    if (function == nullptr) {
        errno = ENOSYS;
        return -1;
    }
    return function(number, arguments[0], arguments[1], arguments[2], arguments[3],
            arguments[4], arguments[5]);
}

void* replacement_for(std::string_view name) {
    if (name == "open") return reinterpret_cast<void*>(&controlled_open);
    if (name == "open64") return reinterpret_cast<void*>(&controlled_open64);
    if (name == "openat") return reinterpret_cast<void*>(&controlled_openat);
    if (name == "openat64") return reinterpret_cast<void*>(&controlled_openat64);
    if (name == "__open_2") return reinterpret_cast<void*>(&controlled_open_2);
    if (name == "__openat_2") return reinterpret_cast<void*>(&controlled_openat_2);
    if (name == "openat2") return reinterpret_cast<void*>(&controlled_openat2);
    if (name == "access") return reinterpret_cast<void*>(&controlled_access);
    if (name == "faccessat") return reinterpret_cast<void*>(&controlled_faccessat);
    if (name == "faccessat2") return reinterpret_cast<void*>(&controlled_faccessat2);
    if (name == "stat") return reinterpret_cast<void*>(&controlled_stat);
    if (name == "lstat") return reinterpret_cast<void*>(&controlled_lstat);
    if (name == "fstatat") return reinterpret_cast<void*>(&controlled_fstatat);
    if (name == "statx") return reinterpret_cast<void*>(&controlled_statx);
    if (name == "renameat2") return reinterpret_cast<void*>(&controlled_renameat2);
    if (name == "rename") return reinterpret_cast<void*>(&controlled_rename);
    if (name == "renameat") return reinterpret_cast<void*>(&controlled_renameat);
    if (name == "unlink") return reinterpret_cast<void*>(&controlled_unlink);
    if (name == "unlinkat") return reinterpret_cast<void*>(&controlled_unlinkat);
    if (name == "mkdir") return reinterpret_cast<void*>(&controlled_mkdir);
    if (name == "mkdirat") return reinterpret_cast<void*>(&controlled_mkdirat);
    if (name == "rmdir") return reinterpret_cast<void*>(&controlled_rmdir);
    if (name == "opendir") return reinterpret_cast<void*>(&controlled_opendir);
    if (name == "getdents64") return reinterpret_cast<void*>(&controlled_getdents64);
    if (name == "readlink") return reinterpret_cast<void*>(&controlled_readlink);
    if (name == "readlinkat") return reinterpret_cast<void*>(&controlled_readlinkat);
    if (name == "socket") return reinterpret_cast<void*>(&controlled_socket);
    if (name == "close") return reinterpret_cast<void*>(&controlled_close);
    if (name == "dup") return reinterpret_cast<void*>(&controlled_dup);
    if (name == "dup2") return reinterpret_cast<void*>(&controlled_dup2);
    if (name == "dup3") return reinterpret_cast<void*>(&controlled_dup3);
    if (name == "fcntl") return reinterpret_cast<void*>(&controlled_fcntl);
    if (name == "fcntl64") return reinterpret_cast<void*>(&controlled_fcntl);
    if (name == "bind") return reinterpret_cast<void*>(&controlled_bind);
    if (name == "connect") return reinterpret_cast<void*>(&controlled_connect);
    if (name == "send") return reinterpret_cast<void*>(&controlled_send);
    if (name == "sendto") return reinterpret_cast<void*>(&controlled_sendto);
    if (name == "sendmsg") return reinterpret_cast<void*>(&controlled_sendmsg);
    if (name == "recv") return reinterpret_cast<void*>(&controlled_recv);
    if (name == "recvfrom") return reinterpret_cast<void*>(&controlled_recvfrom);
    if (name == "recvmsg") return reinterpret_cast<void*>(&controlled_recvmsg);
    if (name == "read") return reinterpret_cast<void*>(&controlled_read);
    if (name == "write") return reinterpret_cast<void*>(&controlled_write);
    if (name == "accept") return reinterpret_cast<void*>(&controlled_accept);
    if (name == "accept4") return reinterpret_cast<void*>(&controlled_accept4);
    if (name == "getsockname") return reinterpret_cast<void*>(&controlled_getsockname);
    if (name == "getpeername") return reinterpret_cast<void*>(&controlled_getpeername);
    if (name == "setsockopt") return reinterpret_cast<void*>(&controlled_setsockopt);
    if (name == "getsockopt") return reinterpret_cast<void*>(&controlled_getsockopt);
    if (name == "if_nametoindex") return reinterpret_cast<void*>(&controlled_if_nametoindex);
    if (name == "if_indextoname") return reinterpret_cast<void*>(&controlled_if_indextoname);
    if (name == "getaddrinfo") return reinterpret_cast<void*>(&controlled_getaddrinfo);
    if (name == "getnameinfo") return reinterpret_cast<void*>(&controlled_getnameinfo);
    if (name == "gethostname") return reinterpret_cast<void*>(&controlled_gethostname);
    if (name == "uname") return reinterpret_cast<void*>(&controlled_uname);
    if (name == "getifaddrs") return reinterpret_cast<void*>(&controlled_getifaddrs);
    if (name == "freeifaddrs") return reinterpret_cast<void*>(&controlled_freeifaddrs);
    if (name == "AAudioStream_requestStart") return reinterpret_cast<void*>(&controlled_AAudioStream_requestStart);
    if (name == "AAudioStream_requestStop") return reinterpret_cast<void*>(&controlled_AAudioStream_requestStop);
    if (name == "AMediaRecorder_start") return reinterpret_cast<void*>(&controlled_AMediaRecorder_start);
    if (name == "AMediaRecorder_stop") return reinterpret_cast<void*>(&controlled_AMediaRecorder_stop);
    if (name == "dlopen") return reinterpret_cast<void*>(&controlled_dlopen);
    if (name == "android_dlopen_ext") return reinterpret_cast<void*>(&controlled_android_dlopen_ext);
    if (name == "kill") return reinterpret_cast<void*>(&controlled_kill);
    if (name == "killpg") return reinterpret_cast<void*>(&controlled_killpg);
    if (name == "tgkill") return reinterpret_cast<void*>(&controlled_tgkill);
    if (name == "tkill") return reinterpret_cast<void*>(&controlled_tkill);
    if (name == "exit") return reinterpret_cast<void*>(&controlled_exit);
    if (name == "_exit" || name == "_Exit") return reinterpret_cast<void*>(&controlled_underscore_exit);
    if (name == "abort") return reinterpret_cast<void*>(&controlled_abort);
    if (name == "syscall") return reinterpret_cast<void*>(&controlled_syscall);
    return nullptr;
}


}  // namespace

void revoke_native_audio_captures() noexcept {
    std::lock_guard lock(audio_handles_mutex);
    AudioCallFn stop_aaudio = require_real(real_aaudio_stop, "AAudioStream_requestStop");
    AudioCallFn stop_media = require_real(real_media_recorder_stop, "AMediaRecorder_stop");
    for (const auto& item : aaudio_handles) { if (stop_aaudio != nullptr) (void) stop_aaudio(item.first); }
    for (const auto& item : media_recorder_handles) { if (stop_media != nullptr) (void) stop_media(item.first); }
    aaudio_handles.clear();
    media_recorder_handles.clear();
}

void* replacement_for_symbol(std::string_view name) noexcept {
    return replacement_for(name);
}

void set_guest_process_exit_allowed(bool allowed) noexcept {
    process_exit_allowed.store(allowed, std::memory_order_release);
}

bool guest_process_exit_allowed() noexcept {
    return process_exit_allowed.load(std::memory_order_acquire);
}

}  // namespace controlled_sandbox
