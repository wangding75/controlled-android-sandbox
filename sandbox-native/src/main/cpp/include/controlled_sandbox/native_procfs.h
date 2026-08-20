#pragma once

#include "controlled_sandbox/native_policy.h"

#include <cstddef>
#include <string>
#include <string_view>
#include <sys/stat.h>
#include <sys/types.h>

namespace controlled_sandbox {

/** Materializes bounded, Guest-facing proc snapshots for identity-sensitive self files. */
class NativeProcFileSystem final {
public:
    [[nodiscard]] static bool is_virtual_path(std::string_view guest_path) noexcept;
    [[nodiscard]] static bool is_virtual_directory_path(std::string_view guest_path) noexcept;
    [[nodiscard]] static bool is_proc_fd_path(std::string_view guest_path) noexcept;
    [[nodiscard]] static bool is_proc_fdinfo_path(std::string_view guest_path) noexcept;
    [[nodiscard]] static bool is_proc_map_file_path(std::string_view guest_path) noexcept;
    [[nodiscard]] static NativePathDecision materialize(std::string_view guest_path);
    [[nodiscard]] static NativePathDecision materialize_directory(std::string_view guest_path);
    [[nodiscard]] static int open_fd_path(std::string_view guest_path, int flags);
    [[nodiscard]] static int stat_fd_path(std::string_view guest_path, struct stat* value,
                                          bool follow);
    [[nodiscard]] static ssize_t readlink_virtual(std::string_view guest_path,
                                                  char* buffer, std::size_t size);

    [[nodiscard]] static std::string sanitize_maps(std::string_view raw,
                                                   const NativePolicySnapshot& policy);
    [[nodiscard]] static std::string sanitize_mountinfo(std::string_view raw,
                                                        const NativePolicySnapshot& policy);
    [[nodiscard]] static std::string render_cmdline(const NativePolicySnapshot& policy);
    [[nodiscard]] static std::string render_status(const NativePolicySnapshot& policy);
    [[nodiscard]] static std::string render_stat(const NativePolicySnapshot& policy);
    [[nodiscard]] static std::string render_statm(const NativePolicySnapshot& policy);
    [[nodiscard]] static std::string render_io(const NativePolicySnapshot& policy);
    [[nodiscard]] static std::string render_mounts(std::string_view raw,
                                                   const NativePolicySnapshot& policy);
    [[nodiscard]] static std::string render_cgroup(std::string_view raw,
                                                   const NativePolicySnapshot& policy);
};

}  // namespace controlled_sandbox
