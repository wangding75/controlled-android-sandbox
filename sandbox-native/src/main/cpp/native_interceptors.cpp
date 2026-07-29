#include "controlled_sandbox/native_interceptors.h"
#include "controlled_sandbox/native_hook.h"
#include "controlled_sandbox/native_file_system.h"
#include "controlled_sandbox/native_policy.h"
#include "controlled_sandbox/native_loader.h"

#include <arpa/inet.h>
#include <cerrno>
#include <cstdarg>
#include <cstdint>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <netdb.h>
#include <sys/mman.h>
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

#include <algorithm>
#include <atomic>
#include <string>
#include <string_view>
#include <vector>

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
using Getdents64Fn = ssize_t (*)(int, void*, std::size_t);
using MmapFn = void* (*)(void*, std::size_t, int, int, int, off_t);
using ReadlinkFn = ssize_t (*)(const char*, char*, size_t);
using ReadlinkAtFn = ssize_t (*)(int, const char*, char*, size_t);
using ConnectFn = int (*)(int, const sockaddr*, socklen_t);
using GetAddrInfoFn = int (*)(const char*, const char*, const addrinfo*, addrinfo**);
using DlopenFn = void* (*)(const char*, int);
using AndroidDlopenExtFn = void* (*)(const char*, int, const android_dlextinfo*);

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
std::atomic<Getdents64Fn> real_getdents64{nullptr};
std::atomic<MmapFn> real_mmap{nullptr};
std::atomic<ReadlinkFn> real_readlink{nullptr};
std::atomic<ReadlinkAtFn> real_readlinkat{nullptr};
std::atomic<ConnectFn> real_connect{nullptr};
std::atomic<GetAddrInfoFn> real_getaddrinfo{nullptr};
std::atomic<DlopenFn> real_dlopen{nullptr};
std::atomic<AndroidDlopenExtFn> real_android_dlopen_ext{nullptr};
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
    if (function == nullptr) { errno = ENOSYS; return -1; }
    NativeResolvedPath resolved;
    if (!resolve_checked([&] { return NativeFileSystemResolver::resolve(path); }, true, resolved)) return -1;
    if (requires_mode(flags)) {
        va_list values;
        va_start(values, flags);
        mode_t mode = static_cast<mode_t>(va_arg(values, int));
        va_end(values);
        return function(resolved.path.c_str(), flags, mode);
    }
    return function(resolved.path.c_str(), flags);
}

extern "C" int controlled_open64(const char* path, int flags, ...) {
    OpenFn function = require_real(real_open64, "open64");
    if (function == nullptr) function = require_real(real_open, "open");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    NativeResolvedPath resolved;
    if (!resolve_checked([&] { return NativeFileSystemResolver::resolve(path); }, true, resolved)) return -1;
    if (requires_mode(flags)) {
        va_list values;
        va_start(values, flags);
        mode_t mode = static_cast<mode_t>(va_arg(values, int));
        va_end(values);
        return function(resolved.path.c_str(), flags, mode);
    }
    return function(resolved.path.c_str(), flags);
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
        return function(resolved.directory_fd, resolved.path.c_str(), flags, mode);
    }
    return function(resolved.directory_fd, resolved.path.c_str(), flags);
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
        return function(resolved.directory_fd, resolved.path.c_str(), flags, mode);
    }
    return function(resolved.directory_fd, resolved.path.c_str(), flags);
}

extern "C" int controlled_open_2(const char* path, int flags) {
    Open2Fn function = require_real(real_open_2, "__open_2");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    NativeResolvedPath resolved;
    if (!resolve_checked([&] { return NativeFileSystemResolver::resolve(path); }, true, resolved)) return -1;
    return function(resolved.path.c_str(), flags);
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
    if (function != nullptr) return function(resolved.directory_fd, resolved.path.c_str(), &mapped, sizeof(mapped));
#ifdef SYS_openat2
    return static_cast<int>(syscall(SYS_openat2, resolved.directory_fd, resolved.path.c_str(),
            &mapped, sizeof(mapped)));
#else
    errno = ENOSYS;
    return -1;
#endif
}

extern "C" int controlled_access(const char* path, int mode) {
    AccessFn function = require_real(real_access, "access");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    NativeResolvedPath resolved;
    if (!resolve_checked([&] { return NativeFileSystemResolver::resolve(path); }, true, resolved)) return -1;
    return function(resolved.path.c_str(), mode);
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
        return static_cast<int>(syscall(SYS_faccessat2, directory, path, mode, flags));
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
    return static_cast<int>(syscall(SYS_faccessat2, resolved.directory_fd,
            resolved.path.c_str(), mode, flags));
#else
    errno = ENOSYS;
    return -1;
#endif
}

extern "C" int controlled_stat(const char* path, struct stat* value) {
    StatFn function = require_real(real_stat, "stat");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    NativeResolvedPath resolved;
    if (!resolve_checked([&] { return NativeFileSystemResolver::resolve(path); }, true, resolved)) return -1;
    return function(resolved.path.c_str(), value);
}

extern "C" int controlled_lstat(const char* path, struct stat* value) {
    StatFn function = require_real(real_lstat, "lstat");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    NativeResolvedPath resolved;
    if (!resolve_checked([&] { return NativeFileSystemResolver::resolve(path); }, false, resolved)) return -1;
    return function(resolved.path.c_str(), value);
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
        return static_cast<int>(syscall(SYS_statx, directory, path, flags, mask, value));
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
    return static_cast<int>(syscall(SYS_statx, resolved.directory_fd,
            resolved.path.c_str(), flags, mask, value));
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
    return static_cast<int>(syscall(SYS_renameat2, old_resolved.directory_fd,
            old_resolved.path.c_str(), new_resolved.directory_fd,
            new_resolved.path.c_str(), flags));
#else
    errno = ENOSYS;
    return -1;
#endif
}

extern "C" ssize_t controlled_getdents64(int directory, void* buffer, std::size_t size) {
    if (buffer == nullptr && size > 0) { errno = EFAULT; return -1; }
    NativeResolvedPath resolved;
    if (!resolve_checked([&] { return NativeFileSystemResolver::resolve_fd(directory); }, true, resolved)) return -1;
    Getdents64Fn function = require_real(real_getdents64, "getdents64");
    if (function != nullptr) return function(directory, buffer, size);
#ifdef SYS_getdents64
    return static_cast<ssize_t>(syscall(SYS_getdents64, directory, buffer, size));
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
    if (function == nullptr) { errno = ENOSYS; return -1; }
    NativeResolvedPath resolved;
    if (!resolve_checked([&] { return NativeFileSystemResolver::resolve(path); }, false, resolved)) return -1;
    return controlled_readlink_common([&](char* out, size_t capacity) {
        return function(resolved.path.c_str(), out, capacity);
    }, buffer, size);
}

extern "C" ssize_t controlled_readlinkat(int directory, const char* path, char* buffer, size_t size) {
    ReadlinkAtFn function = require_real(real_readlinkat, "readlinkat");
    if (function == nullptr) { errno = ENOSYS; return -1; }
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

extern "C" int controlled_connect(int socket_fd, const sockaddr* address, socklen_t length) {
    ConnectFn function = require_real(real_connect, "connect");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    if (address != nullptr && address->sa_family == AF_INET && length >= sizeof(sockaddr_in)) {
        const auto* ipv4 = reinterpret_cast<const sockaddr_in*>(address);
        char text[INET_ADDRSTRLEN]{};
        if (inet_ntop(AF_INET, &ipv4->sin_addr, text, sizeof(text)) != nullptr
                && !global_policy().allow_ipv4(text)) {
            errno = EACCES;
            return -1;
        }
    }
    return function(socket_fd, address, length);
}

extern "C" int controlled_getaddrinfo(const char* node, const char* service,
                                       const addrinfo* hints, addrinfo** result) {
    GetAddrInfoFn function = require_real(real_getaddrinfo, "getaddrinfo");
    if (function == nullptr) return EAI_SYSTEM;
    if (node != nullptr && !global_policy().allow_host(node)) return EAI_NONAME;
    return function(node, service, hints, result);
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
        void* handle = function(decision.resolved_name.c_str(), flags);
        return refresh_loaded_handle(handle) ? handle : nullptr;
    } catch (const PathPolicyError& error) {
        errno = error.error_number();
        return nullptr;
    } catch (...) {
        errno = EACCES;
        return nullptr;
    }
}

extern "C" void* controlled_android_dlopen_ext(const char* name, int flags,
                                                const android_dlextinfo* info) {
    AndroidDlopenExtFn function = require_real(real_android_dlopen_ext, "android_dlopen_ext");
    if (function == nullptr) { errno = ENOSYS; return nullptr; }
    try {
        if (info != nullptr && (info->flags & ANDROID_DLEXT_USE_NAMESPACE) != 0) {
            throw PathPolicyError(EACCES, "FOREIGN_LINKER_NAMESPACE_DENIED");
        }
        if (info != nullptr && (info->flags & ANDROID_DLEXT_USE_LIBRARY_FD) != 0) {
            NativeResolvedPath descriptor = NativeFileSystemResolver::resolve_fd(info->library_fd);
            NativeFileSystemResolver::validate_confinement(descriptor, true);
        }
        std::string resolved_name;
        const char* call_name = name;
        if (name != nullptr && name[0] != '\0') {
            NativeLibraryDecision decision = NativeLibraryLoaderPolicy::resolve(name);
            resolved_name = std::move(decision.resolved_name);
            call_name = resolved_name.c_str();
        } else if (info == nullptr || (info->flags & ANDROID_DLEXT_USE_LIBRARY_FD) == 0) {
            throw PathPolicyError(EACCES, "ANDROID_DLOPEN_EXT_SOURCE_REQUIRED");
        }
        void* handle = function(call_name, flags, info);
        return refresh_loaded_handle(handle) ? handle : nullptr;
    } catch (const PathPolicyError& error) {
        errno = error.error_number();
        return nullptr;
    } catch (...) {
        errno = EACCES;
        return nullptr;
    }
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
    if (name == "getdents64") return reinterpret_cast<void*>(&controlled_getdents64);
    if (name == "mmap") return reinterpret_cast<void*>(&controlled_mmap);
    if (name == "readlink") return reinterpret_cast<void*>(&controlled_readlink);
    if (name == "readlinkat") return reinterpret_cast<void*>(&controlled_readlinkat);
    if (name == "connect") return reinterpret_cast<void*>(&controlled_connect);
    if (name == "getaddrinfo") return reinterpret_cast<void*>(&controlled_getaddrinfo);
    if (name == "dlopen") return reinterpret_cast<void*>(&controlled_dlopen);
    if (name == "android_dlopen_ext") return reinterpret_cast<void*>(&controlled_android_dlopen_ext);
    return nullptr;
}


}  // namespace

void* replacement_for_symbol(std::string_view name) noexcept {
    return replacement_for(name);
}

}  // namespace controlled_sandbox
