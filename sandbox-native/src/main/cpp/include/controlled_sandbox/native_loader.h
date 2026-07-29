#pragma once

#include <cstdint>
#include <string>
#include <string_view>

namespace controlled_sandbox {

struct NativeLibraryDecision {
    std::string resolved_name;
    std::uint64_t policy_revision{};
    bool guest_library{false};
    bool system_library{false};
};

/** Fail-closed loader policy used by dlopen and android_dlopen_ext hooks. */
class NativeLibraryLoaderPolicy final {
public:
    [[nodiscard]] static NativeLibraryDecision resolve(const char* name);
    [[nodiscard]] static bool is_allowed_system_soname(std::string_view name) noexcept;
    [[nodiscard]] static bool is_allowed_system_path(std::string_view path) noexcept;
};

}  // namespace controlled_sandbox
