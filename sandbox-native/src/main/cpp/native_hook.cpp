#include "controlled_sandbox/native_hook.h"
#include "controlled_sandbox/native_file_system.h"
#include "controlled_sandbox/native_policy.h"

#include <arpa/inet.h>
#include <cerrno>
#include <cstdarg>
#include <cstdint>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <cstdio>
#include <link.h>
#include <mutex>
#include <netdb.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <string>
#include <string_view>
#include <unordered_set>
#include <vector>

namespace controlled_sandbox {
namespace {

using OpenFn = int (*)(const char*, int, ...);
using OpenAtFn = int (*)(int, const char*, int, ...);
using Open2Fn = int (*)(const char*, int);
using OpenAt2Fn = int (*)(int, const char*, int);
using AccessFn = int (*)(const char*, int);
using FaccessAtFn = int (*)(int, const char*, int, int);
using StatFn = int (*)(const char*, struct stat*);
using FstatAtFn = int (*)(int, const char*, struct stat*, int);
using ReadlinkFn = ssize_t (*)(const char*, char*, size_t);
using ReadlinkAtFn = ssize_t (*)(int, const char*, char*, size_t);
using ConnectFn = int (*)(int, const sockaddr*, socklen_t);
using GetAddrInfoFn = int (*)(const char*, const char*, const addrinfo*, addrinfo**);
using DlopenFn = void* (*)(const char*, int);

std::atomic<OpenFn> real_open{nullptr};
std::atomic<OpenFn> real_open64{nullptr};
std::atomic<OpenAtFn> real_openat{nullptr};
std::atomic<OpenAtFn> real_openat64{nullptr};
std::atomic<Open2Fn> real_open_2{nullptr};
std::atomic<OpenAt2Fn> real_openat_2{nullptr};
std::atomic<AccessFn> real_access{nullptr};
std::atomic<FaccessAtFn> real_faccessat{nullptr};
std::atomic<StatFn> real_stat{nullptr};
std::atomic<StatFn> real_lstat{nullptr};
std::atomic<FstatAtFn> real_fstatat{nullptr};
std::atomic<ReadlinkFn> real_readlink{nullptr};
std::atomic<ReadlinkAtFn> real_readlinkat{nullptr};
std::atomic<ConnectFn> real_connect{nullptr};
std::atomic<GetAddrInfoFn> real_getaddrinfo{nullptr};
std::atomic<DlopenFn> real_dlopen{nullptr};
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
    OpenAt2Fn function = require_real(real_openat_2, "__openat_2");
    if (function == nullptr) { errno = ENOSYS; return -1; }
    NativeResolvedPath resolved;
    if (!resolve_checked([&] { return NativeFileSystemResolver::resolve_at(directory, path); }, true, resolved)) return -1;
    return function(resolved.directory_fd, resolved.path.c_str(), flags);
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

extern "C" void* controlled_dlopen(const char* name, int flags) {
    DlopenFn function = require_real(real_dlopen, "dlopen");
    if (function == nullptr) { errno = ENOSYS; return nullptr; }
    void* handle = function(name, flags);
    if (handle != nullptr && !inside_refresh) {
        inside_refresh = true;
        const bool refreshed = global_hooks().refresh();
        inside_refresh = false;
        if (!refreshed) {
            (void) dlclose(handle);
            errno = EACCES;
            return nullptr;
        }
    }
    return handle;
}

void* replacement_for(std::string_view name) {
    if (name == "open") return reinterpret_cast<void*>(&controlled_open);
    if (name == "open64") return reinterpret_cast<void*>(&controlled_open64);
    if (name == "openat") return reinterpret_cast<void*>(&controlled_openat);
    if (name == "openat64") return reinterpret_cast<void*>(&controlled_openat64);
    if (name == "__open_2") return reinterpret_cast<void*>(&controlled_open_2);
    if (name == "__openat_2") return reinterpret_cast<void*>(&controlled_openat_2);
    if (name == "access") return reinterpret_cast<void*>(&controlled_access);
    if (name == "faccessat") return reinterpret_cast<void*>(&controlled_faccessat);
    if (name == "stat") return reinterpret_cast<void*>(&controlled_stat);
    if (name == "lstat") return reinterpret_cast<void*>(&controlled_lstat);
    if (name == "fstatat") return reinterpret_cast<void*>(&controlled_fstatat);
    if (name == "readlink") return reinterpret_cast<void*>(&controlled_readlink);
    if (name == "readlinkat") return reinterpret_cast<void*>(&controlled_readlinkat);
    if (name == "connect") return reinterpret_cast<void*>(&controlled_connect);
    if (name == "getaddrinfo") return reinterpret_cast<void*>(&controlled_getaddrinfo);
    if (name == "dlopen") return reinterpret_cast<void*>(&controlled_dlopen);
    return nullptr;
}

std::uintptr_t runtime_pointer(std::uintptr_t base, std::uintptr_t value) {
    return value < base ? base + value : value;
}

int protection_for_address(std::uintptr_t address) {
    FILE* maps = std::fopen("/proc/self/maps", "r");
    if (maps == nullptr) return -1;
    char line[512]{};
    int protection = -1;
    while (std::fgets(line, sizeof(line), maps) != nullptr) {
        unsigned long long start = 0;
        unsigned long long end = 0;
        char permissions[5]{};
        if (std::sscanf(line, "%llx-%llx %4s", &start, &end, permissions) != 3) continue;
        if (address < start || address >= end) continue;
        protection = 0;
        if (permissions[0] == 'r') protection |= PROT_READ;
        if (permissions[1] == 'w') protection |= PROT_WRITE;
        if (permissions[2] == 'x') protection |= PROT_EXEC;
        break;
    }
    std::fclose(maps);
    return protection;
}

bool make_writable(void* address, int& original_protection) {
    const long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) return false;
    const auto raw = reinterpret_cast<std::uintptr_t>(address);
    const auto page = raw & ~static_cast<std::uintptr_t>(page_size - 1);
    original_protection = protection_for_address(raw);
    if (original_protection < 0) return false;
    if ((original_protection & PROT_WRITE) != 0) return true;
    return mprotect(reinterpret_cast<void*>(page), static_cast<std::size_t>(page_size),
                    original_protection | PROT_WRITE) == 0;
}

void restore_protection(void* address, int original_protection) {
    if (original_protection < 0 || (original_protection & PROT_WRITE) != 0) return;
    const long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) return;
    const auto raw = reinterpret_cast<std::uintptr_t>(address);
    const auto page = raw & ~static_cast<std::uintptr_t>(page_size - 1);
    (void) mprotect(reinterpret_cast<void*>(page), static_cast<std::size_t>(page_size),
            original_protection);
}

#if __SIZEOF_POINTER__ == 8
std::size_t relocation_symbol(ElfW(Xword) info) { return static_cast<std::size_t>(ELF64_R_SYM(info)); }
#else
std::size_t relocation_symbol(ElfW(Xword) info) { return static_cast<std::size_t>(ELF32_R_SYM(info)); }
#endif

bool supported_relocation_type(ElfW(Xword) info) {
#if defined(__x86_64__)
    const auto type = ELF64_R_TYPE(info);
    return type == R_X86_64_JUMP_SLOT || type == R_X86_64_GLOB_DAT;
#elif defined(__i386__)
    const auto type = ELF32_R_TYPE(info);
    return type == R_386_JMP_SLOT || type == R_386_GLOB_DAT;
#elif defined(__aarch64__)
    const auto type = ELF64_R_TYPE(info);
    return type == R_AARCH64_JUMP_SLOT || type == R_AARCH64_GLOB_DAT;
#elif defined(__arm__)
    const auto type = ELF32_R_TYPE(info);
    return type == R_ARM_JUMP_SLOT || type == R_ARM_GLOB_DAT;
#else
    (void) info;
    return false;
#endif
}

template <typename Relocation>
std::size_t patch_relocations(std::uintptr_t base, const Relocation* entries, std::size_t count,
                              const ElfW(Sym)* symbols, const char* strings,
                              std::size_t& targets, std::size_t& failures) {
    if (entries == nullptr || symbols == nullptr || strings == nullptr) return 0;
    std::size_t patched = 0;
    for (std::size_t index = 0; index < count; index++) {
        const Relocation& relocation = entries[index];
        if (!supported_relocation_type(relocation.r_info)) continue;
        const std::size_t symbol_index = relocation_symbol(relocation.r_info);
        if (symbol_index == 0) continue;
        const char* name = strings + symbols[symbol_index].st_name;
        if (name == nullptr || !NativeHookRuntime::is_target_symbol(name)) continue;
        void* replacement = replacement_for(name);
        if (replacement == nullptr) continue;
        targets++;
        auto** slot = reinterpret_cast<void**>(base + relocation.r_offset);
        if (*slot == replacement) continue;
        int original_protection = -1;
        if (!make_writable(slot, original_protection)) { failures++; continue; }
        __atomic_store_n(slot, replacement, __ATOMIC_RELEASE);
        restore_protection(slot, original_protection);
        patched++;
    }
    return patched;
}

struct ScanContext {
    NativeHookRuntime* runtime;
    std::string root;
    std::size_t scanned{0};
    std::size_t matched{0};
    std::size_t patched{0};
    std::size_t targets{0};
    std::size_t failures{0};
};

int scan_module(dl_phdr_info* info, std::size_t, void* opaque) {
    auto* context = static_cast<ScanContext*>(opaque);
    context->scanned++;
    const std::string_view path = info->dlpi_name == nullptr ? std::string_view{} : std::string_view(info->dlpi_name);
    if (!NativeHookRuntime::is_guest_module(path, context->root)) return 0;
    context->matched++;

    const ElfW(Dyn)* dynamic = nullptr;
    for (ElfW(Half) index = 0; index < info->dlpi_phnum; index++) {
        if (info->dlpi_phdr[index].p_type == PT_DYNAMIC) {
            dynamic = reinterpret_cast<const ElfW(Dyn)*>(info->dlpi_addr + info->dlpi_phdr[index].p_vaddr);
            break;
        }
    }
    if (dynamic == nullptr) return 0;

    const ElfW(Sym)* symbols = nullptr;
    const char* strings = nullptr;
    const void* jump_relocations = nullptr;
    std::size_t jump_size = 0;
    long jump_type = DT_RELA;
    const ElfW(Rela)* rela = nullptr;
    std::size_t rela_size = 0;
    const ElfW(Rel)* rel = nullptr;
    std::size_t rel_size = 0;
    const std::uintptr_t base = static_cast<std::uintptr_t>(info->dlpi_addr);

    for (const ElfW(Dyn)* item = dynamic; item->d_tag != DT_NULL; item++) {
        switch (item->d_tag) {
            case DT_SYMTAB:
                symbols = reinterpret_cast<const ElfW(Sym)*>(runtime_pointer(base, item->d_un.d_ptr));
                break;
            case DT_STRTAB:
                strings = reinterpret_cast<const char*>(runtime_pointer(base, item->d_un.d_ptr));
                break;
            case DT_JMPREL:
                jump_relocations = reinterpret_cast<const void*>(runtime_pointer(base, item->d_un.d_ptr));
                break;
            case DT_PLTRELSZ: jump_size = static_cast<std::size_t>(item->d_un.d_val); break;
            case DT_PLTREL: jump_type = item->d_un.d_val; break;
            case DT_RELA:
                rela = reinterpret_cast<const ElfW(Rela)*>(runtime_pointer(base, item->d_un.d_ptr));
                break;
            case DT_RELASZ: rela_size = static_cast<std::size_t>(item->d_un.d_val); break;
            case DT_REL:
                rel = reinterpret_cast<const ElfW(Rel)*>(runtime_pointer(base, item->d_un.d_ptr));
                break;
            case DT_RELSZ: rel_size = static_cast<std::size_t>(item->d_un.d_val); break;
            default: break;
        }
    }

    if (jump_relocations != nullptr && jump_size > 0) {
        if (jump_type == DT_RELA) {
            context->patched += patch_relocations(base,
                    static_cast<const ElfW(Rela)*>(jump_relocations), jump_size / sizeof(ElfW(Rela)), symbols, strings, context->targets, context->failures);
        } else if (jump_type == DT_REL) {
            context->patched += patch_relocations(base,
                    static_cast<const ElfW(Rel)*>(jump_relocations), jump_size / sizeof(ElfW(Rel)), symbols, strings, context->targets, context->failures);
        }
    }
    context->patched += patch_relocations(base, rela, rela_size / sizeof(ElfW(Rela)), symbols, strings, context->targets, context->failures);
    context->patched += patch_relocations(base, rel, rel_size / sizeof(ElfW(Rel)), symbols, strings, context->targets, context->failures);
    return 0;
}

struct HookState {
    mutable std::mutex mutex;
    NativeHookStatus status;
};

HookState& hook_state() {
    static HookState state;
    return state;
}

}  // namespace

bool NativeHookRuntime::install(std::string guest_library_root) {
    while (guest_library_root.size() > 1 && guest_library_root.back() == '/') guest_library_root.pop_back();
    if (guest_library_root.empty() || guest_library_root.front() != '/') return false;
    const NativePolicySnapshot policy = global_policy().snapshot();
    if (!policy.configured) return false;
    {
        auto& state = hook_state();
        std::lock_guard lock(state.mutex);
        state.status = {};
        state.status.guest_library_root = std::move(guest_library_root);
        state.status.policy_revision = policy.revision;
        state.status.installed = true;
    }
    return refresh();
}

bool NativeHookRuntime::refresh() {
    auto& state = hook_state();
    std::string root;
    {
        std::lock_guard lock(state.mutex);
        if (!state.status.installed) return false;
        root = state.status.guest_library_root;
    }
    const NativePolicySnapshot policy = global_policy().snapshot();
    {
        std::lock_guard lock(state.mutex);
        if (!policy.configured || policy.revision != state.status.policy_revision) {
            state.status.last_error = "POLICY_REVISION_CHANGED_REINSTALL_REQUIRED";
            state.status.installed = false;
            return false;
        }
    }
    ScanContext context{this, root};
    const int result = dl_iterate_phdr(scan_module, &context);
    std::lock_guard lock(state.mutex);
    state.status.refresh_count++;
    state.status.modules_scanned = context.scanned;
    state.status.modules_matched = context.matched;
    state.status.relocations_patched += context.patched;
    state.status.target_relocations = context.targets;
    state.status.patch_failures += context.failures;
    if (result != 0) state.status.last_error = "DL_ITERATE_PHDR_FAILED:" + std::to_string(result);
    else if (context.failures > 0) state.status.last_error = "PLT_PATCH_FAILED:" + std::to_string(context.failures);
    const bool success = result == 0 && context.failures == 0;
    if (!success) state.status.installed = false;
    return success;
}

void NativeHookRuntime::reset() {
    auto& state = hook_state();
    std::lock_guard lock(state.mutex);
    state.status = {};
}

NativeHookStatus NativeHookRuntime::status() const {
    auto& state = hook_state();
    std::lock_guard lock(state.mutex);
    return state.status;
}

bool NativeHookRuntime::is_target_symbol(std::string_view symbol) noexcept {
    static constexpr std::array<std::string_view, 16> targets{
            "open", "open64", "openat", "openat64", "__open_2", "__openat_2",
            "access", "faccessat", "stat", "lstat", "fstatat", "readlink", "readlinkat",
            "connect", "getaddrinfo", "dlopen"};
    return std::find(targets.begin(), targets.end(), symbol) != targets.end();
}

bool NativeHookRuntime::is_guest_module(std::string_view module_path,
                                        std::string_view guest_library_root) noexcept {
    if (module_path.empty() || guest_library_root.empty()) return false;
    if (module_path.size() < guest_library_root.size()) return false;
    if (module_path.compare(0, guest_library_root.size(), guest_library_root) != 0) return false;
    return module_path.size() == guest_library_root.size()
            || module_path[guest_library_root.size()] == '/';
}

NativeHookRuntime& global_hooks() {
    static NativeHookRuntime hooks;
    return hooks;
}

}  // namespace controlled_sandbox
