#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

namespace controlled_sandbox {

struct NativeHookStatus {
    bool installed{false};
    bool system_io_installed{false};
    bool process_lifetime_installed{false};
    std::size_t modules_scanned{0};
    std::size_t modules_matched{0};
    std::size_t relocations_patched{0};
    std::size_t refresh_count{0};
    std::size_t target_relocations{0};
    std::size_t patch_failures{0};
    std::size_t process_lifetime_modules_scanned{0};
    std::size_t process_lifetime_modules_matched{0};
    std::size_t process_lifetime_relocations_patched{0};
    std::size_t process_lifetime_refresh_count{0};
    std::size_t process_lifetime_target_relocations{0};
    std::size_t process_lifetime_patch_failures{0};
    std::uint64_t policy_revision{0};
    std::string guest_library_root;
    std::string last_error;
};

/**
 * Installs PLT/GOT rebinding only in libraries loaded from the Guest native directory.
 * The host process and Android system libraries are deliberately excluded.
 *
 * This is a best-effort compatibility and redirection mechanism, not a security boundary.
 * Guest code can bypass imported libc symbols with direct syscalls or inline assembly.
 */
class NativeHookRuntime final {
public:
    bool install(std::string guest_library_root);
    /** Patch the Android Java/native file boundary for an isolated capability-backed process. */
    bool install_system_io();
    /**
     * Patch only host runtime-library process-lifetime imports for a translated guest.
     * Foreign guest ELF modules are never parsed or modified by this path.
     */
    bool install_process_lifetime();
    bool refresh();
    bool refresh_process_lifetime();
    bool installCamera1();
    bool refreshCamera1();
    void reset();
    [[nodiscard]] NativeHookStatus status() const;

    static bool is_target_symbol(std::string_view symbol) noexcept;
    static bool is_system_io_symbol(std::string_view symbol) noexcept;
    static bool is_process_lifetime_symbol(std::string_view symbol) noexcept;
    static bool is_process_lifetime_system_module(std::string_view module_path) noexcept;
    static bool is_process_io_system_module(std::string_view module_path) noexcept;
    static bool is_guest_module(std::string_view module_path,
                                std::string_view guest_library_root) noexcept;
};

NativeHookRuntime& global_hooks();

}  // namespace controlled_sandbox
