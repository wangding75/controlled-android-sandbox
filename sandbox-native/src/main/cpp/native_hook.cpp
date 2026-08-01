#include "controlled_sandbox/native_hook.h"
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
        void* replacement = replacement_for_symbol(name);
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
    static constexpr std::array<std::string_view, 56> targets{
            "open", "open64", "openat", "openat64", "__open_2", "__openat_2", "openat2",
            "access", "faccessat", "faccessat2", "stat", "lstat", "fstatat", "statx",
            "renameat2", "readlink", "readlinkat", "getdents64", "mmap",
            "socket", "close", "dup", "dup2", "dup3", "fcntl", "fcntl64", "bind", "connect",
            "send", "sendto", "sendmsg", "recv", "recvfrom", "recvmsg", "read", "write",
            "accept", "accept4", "getsockname",
            "getpeername", "setsockopt", "getsockopt", "if_nametoindex", "if_indextoname",
            "getaddrinfo", "getnameinfo", "gethostname", "uname",
            "getifaddrs", "freeifaddrs", "AAudioStream_requestStart",
            "AAudioStream_requestStop", "AMediaRecorder_start", "AMediaRecorder_stop",
            "dlopen", "android_dlopen_ext"};
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
