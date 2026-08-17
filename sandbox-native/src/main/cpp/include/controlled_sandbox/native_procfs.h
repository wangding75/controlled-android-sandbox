#pragma once

#include "controlled_sandbox/native_policy.h"

#include <string>
#include <string_view>

namespace controlled_sandbox {

/** Materializes bounded, Guest-facing proc snapshots for identity-sensitive self files. */
class NativeProcFileSystem final {
public:
    [[nodiscard]] static bool is_virtual_path(std::string_view guest_path) noexcept;
    [[nodiscard]] static NativePathDecision materialize(std::string_view guest_path);

    [[nodiscard]] static std::string sanitize_maps(std::string_view raw,
                                                   const NativePolicySnapshot& policy);
    [[nodiscard]] static std::string sanitize_mountinfo(std::string_view raw,
                                                        const NativePolicySnapshot& policy);
    [[nodiscard]] static std::string render_cmdline(const NativePolicySnapshot& policy);
    [[nodiscard]] static std::string render_status(const NativePolicySnapshot& policy);
    [[nodiscard]] static std::string render_stat(const NativePolicySnapshot& policy);
    [[nodiscard]] static std::string render_statm(const NativePolicySnapshot& policy);
    [[nodiscard]] static std::string render_io(const NativePolicySnapshot& policy);
};

}  // namespace controlled_sandbox
