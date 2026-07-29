#pragma once

#include <cstddef>
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

struct NativeLoaderStatus {
    std::uint64_t path_validations{};
    std::uint64_t fd_validations{};
    std::uint64_t relro_validations{};
    std::uint64_t denied_requests{};
    std::string last_error;
};

/** Fail-closed loader policy used by dlopen and android_dlopen_ext hooks. */
class NativeLibraryLoaderPolicy final {
public:
    [[nodiscard]] static NativeLibraryDecision resolve(const char* name);
    static void validate_library(const NativeLibraryDecision& decision);
    static void validate_library_fd(int file_descriptor, std::int64_t offset);
    static void validate_android_dlext(std::uint64_t flags, int library_fd,
                                       std::int64_t library_fd_offset, int relro_fd,
                                       const void* reserved_address, std::size_t reserved_size,
                                       bool namespace_supplied);
    static void record_denial(std::string reason) noexcept;
    [[nodiscard]] static NativeLoaderStatus status();
    static void reset_status() noexcept;
    [[nodiscard]] static bool is_allowed_system_soname(std::string_view name) noexcept;
    [[nodiscard]] static bool is_allowed_system_path(std::string_view path) noexcept;
};

}  // namespace controlled_sandbox
