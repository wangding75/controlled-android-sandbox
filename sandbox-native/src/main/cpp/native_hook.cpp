#include "controlled_sandbox/native_hook.h"
#include "controlled_sandbox/native_camera1.h"
#include "controlled_sandbox/native_interceptors.h"
#include "controlled_sandbox/native_policy.h"

#include <cstdio>
#include <cstdint>
#include <link.h>
#include <mutex>
#include <sys/mman.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <string>
#include <string_view>

namespace controlled_sandbox {
namespace {

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

bool is_host_elf(const dl_phdr_info* info) {
    if (info == nullptr) return false;
    const auto* header = reinterpret_cast<const ElfW(Ehdr)*>(info->dlpi_addr);
    if (header == nullptr
            || header->e_ident[EI_MAG0] != ELFMAG0
            || header->e_ident[EI_MAG1] != ELFMAG1
            || header->e_ident[EI_MAG2] != ELFMAG2
            || header->e_ident[EI_MAG3] != ELFMAG3) {
        // Some linker-owned entries do not expose an ELF header at dlpi_addr. Keep
        // their existing behavior; only reject a positively identified foreign ELF.
        return true;
    }
#if defined(__x86_64__)
    return header->e_machine == EM_X86_64;
#elif defined(__i386__)
    return header->e_machine == EM_386;
#elif defined(__aarch64__)
    return header->e_machine == EM_AARCH64;
#elif defined(__arm__)
    return header->e_machine == EM_ARM;
#else
    return true;
#endif
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
                              std::size_t& targets, std::size_t& failures,
                              bool camera_system, bool lifetime_system, bool system_io) {
    if (entries == nullptr || symbols == nullptr || strings == nullptr) return 0;
    std::size_t patched = 0;
    for (std::size_t index = 0; index < count; index++) {
        const Relocation& relocation = entries[index];
        if (!supported_relocation_type(relocation.r_info)) continue;
        const std::size_t symbol_index = relocation_symbol(relocation.r_info);
        if (symbol_index == 0) continue;
        const char* name = strings + symbols[symbol_index].st_name;
        if (name == nullptr) continue;
        if (camera_system ? !is_camera1_system_symbol(name)
                : lifetime_system ? !NativeHookRuntime::is_process_lifetime_symbol(name)
                : system_io ? !NativeHookRuntime::is_target_symbol(name)
                : !NativeHookRuntime::is_target_symbol(name)) continue;
        void* replacement = camera_system ? replacement_for_camera1_symbol(name)
                                          : replacement_for_symbol(name);
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
    bool camera_system{false};
    bool lifetime_system{false};
    bool system_io{false};
    std::size_t scanned{0};
    std::size_t matched{0};
    std::size_t patched{0};
    std::size_t targets{0};
    std::size_t failures{0};
    bool foreign_guest_module{false};
};

int scan_module(dl_phdr_info* info, std::size_t, void* opaque) {
    auto* context = static_cast<ScanContext*>(opaque);
    context->scanned++;
    const std::string_view path = info->dlpi_name == nullptr ? std::string_view{} : std::string_view(info->dlpi_name);
    const bool guest_module = !context->camera_system && !context->lifetime_system
            && !context->system_io
            && NativeHookRuntime::is_guest_module(path, context->root);
    // The Android native bridge may expose a foreign-ABI module through the host
    // linker's module walk. Its ELF tables and relocation types are not parseable by
    // this library, and patching them corrupts translated calls (notably WebView).
    if (!is_host_elf(info)) {
        if (guest_module) context->foreign_guest_module = true;
        return 0;
    }
    if (context->camera_system) {
        if (!is_camera1_system_module(path)) return 0;
    } else if (context->lifetime_system) {
        if (!NativeHookRuntime::is_process_lifetime_system_module(path)) return 0;
    } else if (context->system_io) {
        if (!NativeHookRuntime::is_process_io_system_module(path)) return 0;
    } else if (!NativeHookRuntime::is_guest_module(path, context->root)) {
        return 0;
    }
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
                    static_cast<const ElfW(Rela)*>(jump_relocations), jump_size / sizeof(ElfW(Rela)), symbols, strings, context->targets, context->failures, context->camera_system, context->lifetime_system, context->system_io);
        } else if (jump_type == DT_REL) {
            context->patched += patch_relocations(base,
                    static_cast<const ElfW(Rel)*>(jump_relocations), jump_size / sizeof(ElfW(Rel)), symbols, strings, context->targets, context->failures, context->camera_system, context->lifetime_system, context->system_io);
        }
    }
    context->patched += patch_relocations(base, rela, rela_size / sizeof(ElfW(Rela)), symbols, strings, context->targets, context->failures, context->camera_system, context->lifetime_system, context->system_io);
    context->patched += patch_relocations(base, rel, rel_size / sizeof(ElfW(Rel)), symbols, strings, context->targets, context->failures, context->camera_system, context->lifetime_system, context->system_io);
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

bool NativeHookRuntime::install_system_io() {
    const NativePolicySnapshot policy = global_policy().snapshot();
    if (!policy.configured || !global_policy().file_capabilities_configured()) return false;
    {
        auto& state = hook_state();
        std::lock_guard lock(state.mutex);
        if (state.status.installed && state.status.policy_revision != policy.revision) {
            state.status.last_error = "POLICY_REVISION_CHANGED_REINSTALL_REQUIRED";
            state.status.installed = false;
            return false;
        }
        state.status.installed = true;
        state.status.system_io_installed = true;
        state.status.policy_revision = policy.revision;
    }
    return refresh();
}

bool NativeHookRuntime::refresh() {
    auto& state = hook_state();
    std::string root;
    bool system_io = false;
    {
        std::lock_guard lock(state.mutex);
        if (!state.status.installed) return false;
        root = state.status.guest_library_root;
        system_io = state.status.system_io_installed;
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
    ScanContext context{this, root, false, false, false};
    const int result = root.empty() ? 0 : dl_iterate_phdr(scan_module, &context);
    ScanContext lifetime{this, root, false, true, false};
    if (!context.foreign_guest_module) (void) dl_iterate_phdr(scan_module, &lifetime);
    ScanContext system{this, root, false, false, system_io};
    if (system_io) (void) dl_iterate_phdr(scan_module, &system);
    context.patched += lifetime.patched;
    context.targets += lifetime.targets;
    context.patched += system.patched;
    context.targets += system.targets;
    std::lock_guard lock(state.mutex);
    state.status.refresh_count++;
    state.status.modules_scanned = context.scanned;
    state.status.modules_matched = context.matched;
    state.status.relocations_patched += context.patched;
    state.status.target_relocations = context.targets;
    state.status.patch_failures += context.failures + system.failures;
    if (result != 0) state.status.last_error = "DL_ITERATE_PHDR_FAILED:" + std::to_string(result);
    else if (context.failures > 0 || system.failures > 0) {
        state.status.last_error = "PLT_PATCH_FAILED:" + std::to_string(
                context.failures + system.failures);
    }
    const bool success = result == 0 && context.failures == 0 && system.failures == 0;
    if (!success) state.status.installed = false;
    return success;
}

bool NativeHookRuntime::installCamera1() {
    if (!global_camera1_adapter().prepare_symbols()) return false;
    return refreshCamera1();
}

bool NativeHookRuntime::refreshCamera1() {
    if (!global_camera1_adapter().prepare_symbols()) return false;
    ScanContext context{this, {}, true, false, false};
    const int result = dl_iterate_phdr(scan_module, &context);
    global_camera1_adapter().record_hook_result(context.patched, context.targets, context.failures);
    return result == 0 && context.failures == 0 && context.targets > 0;
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

bool NativeHookRuntime::is_process_lifetime_symbol(std::string_view symbol) noexcept {
    static constexpr std::array<std::string_view, 8> targets{
            "kill", "killpg", "tgkill", "tkill", "exit", "_exit", "_Exit", "abort"};
    return std::find(targets.begin(), targets.end(), symbol) != targets.end();
}

bool NativeHookRuntime::is_process_lifetime_system_module(std::string_view module_path) noexcept {
    auto ends_with = [](std::string_view path, std::string_view name) {
        return path == name || (path.size() > name.size()
                && path.compare(path.size() - name.size(), name.size(), name) == 0);
    };
    return ends_with(module_path, "libandroid_runtime.so")
            || ends_with(module_path, "libopenjdk.so")
            || ends_with(module_path, "libopenjdkjvm.so")
            || ends_with(module_path, "libjavacore.so");
}

bool NativeHookRuntime::is_process_io_system_module(std::string_view module_path) noexcept {
    auto ends_with = [](std::string_view path, std::string_view name) {
        return path == name || (path.size() > name.size()
                && path.compare(path.size() - name.size(), name.size(), name) == 0);
    };
    return ends_with(module_path, "libandroid_runtime.so")
            || ends_with(module_path, "libopenjdk.so")
            || ends_with(module_path, "libopenjdkjvm.so")
            || ends_with(module_path, "libjavacore.so");
}

bool NativeHookRuntime::is_target_symbol(std::string_view symbol) noexcept {
    static constexpr std::array<std::string_view, 74> targets{
            "open", "open64", "openat", "openat64", "__open_2", "__openat_2", "openat2",
            "access", "faccessat", "faccessat2", "stat", "lstat", "fstatat", "statx",
            "rename", "renameat", "renameat2", "unlink", "unlinkat", "mkdir", "mkdirat", "rmdir", "opendir",
            "readlink", "readlinkat", "getdents64", "mmap",
            "socket", "close", "dup", "dup2", "dup3", "fcntl", "fcntl64", "bind", "connect",
            "send", "sendto", "sendmsg", "recv", "recvfrom", "recvmsg", "read", "write",
            "accept", "accept4", "getsockname",
            "getpeername", "setsockopt", "getsockopt", "if_nametoindex", "if_indextoname",
            "getaddrinfo", "getnameinfo", "gethostname", "uname",
            "getifaddrs", "freeifaddrs", "AAudioStream_requestStart",
            "AAudioStream_requestStop", "AMediaRecorder_start", "AMediaRecorder_stop",
            "dlopen", "android_dlopen_ext", "syscall",
            "kill", "killpg", "tgkill", "tkill", "exit", "_exit", "_Exit", "abort"};
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
