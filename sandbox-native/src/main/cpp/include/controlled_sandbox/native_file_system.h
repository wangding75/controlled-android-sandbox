#pragma once

#include <cstdint>
#include <string>
#include <string_view>
#include <utility>

namespace controlled_sandbox {

struct NativeResolvedPath {
    NativeResolvedPath() = default;
    NativeResolvedPath(int directory_fd_value, std::string path_value,
                       std::string confinement_root_value,
                       std::uint64_t policy_revision_value,
                       bool rewritten_value = false, bool capability_value = false,
                       std::string virtual_path_value = {})
        : directory_fd(directory_fd_value),
          path(std::move(path_value)),
          confinement_root(std::move(confinement_root_value)),
          policy_revision(policy_revision_value),
          rewritten(rewritten_value),
          capability(capability_value),
          virtual_path(std::move(virtual_path_value)) {}

    int directory_fd{};
    std::string path;
    std::string confinement_root;
    std::uint64_t policy_revision{};
    bool rewritten{false};
    bool capability{false};
    std::string virtual_path;
};

class NativeFileSystemResolver final {
public:
    [[nodiscard]] static NativeResolvedPath resolve(const char* path);
    [[nodiscard]] static NativeResolvedPath resolve_at(int directory_fd, const char* path);
    [[nodiscard]] static NativeResolvedPath resolve_fd(int file_descriptor);
    static void validate_confinement(const NativeResolvedPath& resolved, bool follow_final_symlink);
    static void validate_same_confinement(const NativeResolvedPath& first,
                                          const NativeResolvedPath& second);
    [[nodiscard]] static std::string rewrite_readlink_result(std::string_view value);
};

}  // namespace controlled_sandbox
