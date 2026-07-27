#pragma once

#include <cstdint>
#include <string>
#include <string_view>

namespace controlled_sandbox {

struct NativeResolvedPath {
    int directory_fd{};
    std::string path;
    std::string confinement_root;
    std::uint64_t policy_revision{};
    bool rewritten{false};
};

class NativeFileSystemResolver final {
public:
    [[nodiscard]] static NativeResolvedPath resolve(const char* path);
    [[nodiscard]] static NativeResolvedPath resolve_at(int directory_fd, const char* path);
    static void validate_confinement(const NativeResolvedPath& resolved, bool follow_final_symlink);
    [[nodiscard]] static std::string rewrite_readlink_result(std::string_view value);
};

}  // namespace controlled_sandbox
