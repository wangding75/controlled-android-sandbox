#include "controlled_sandbox/native_file_system.h"
#include "controlled_sandbox/native_policy.h"

#include <cerrno>
#include <climits>
#include <cstdlib>
#include <fcntl.h>
#include <limits.h>
#include <string>
#include <string_view>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <vector>

namespace controlled_sandbox {
namespace {

bool path_has_prefix(std::string_view path, std::string_view prefix) {
    return path == prefix || (path.size() > prefix.size()
            && path.compare(0, prefix.size(), prefix) == 0
            && path[prefix.size()] == '/');
}

std::string normalize_join(std::string_view base, std::string_view relative) {
    if (base.empty() || base.front() != '/') throw PathPolicyError(EINVAL, "DIRFD_BASE_NOT_ABSOLUTE");
    if (relative.empty()) throw PathPolicyError(ENOENT, "PATH_EMPTY");
    if (relative.size() > PATH_MAX) throw PathPolicyError(ENAMETOOLONG, "PATH_TOO_LONG");
    if (relative.find('\0') != std::string_view::npos) throw PathPolicyError(EINVAL, "PATH_NUL");
    std::string combined(base);
    if (combined.back() != '/') combined.push_back('/');
    combined.append(relative);
    if (combined.size() > PATH_MAX * 2U) throw PathPolicyError(ENAMETOOLONG, "PATH_TOO_LONG");
    return combined;
}

std::string raw_readlink(std::string_view path) {
    std::vector<char> buffer(256);
    for (;;) {
        const long length = syscall(SYS_readlinkat, AT_FDCWD, std::string(path).c_str(),
                buffer.data(), buffer.size());
        if (length < 0) throw PathPolicyError(errno, "READLINK_DIRFD_FAILED");
        if (static_cast<std::size_t>(length) < buffer.size()) {
            return std::string(buffer.data(), static_cast<std::size_t>(length));
        }
        if (buffer.size() >= static_cast<std::size_t>(PATH_MAX) * 4U) {
            throw PathPolicyError(ENAMETOOLONG, "READLINK_DIRFD_TOO_LONG");
        }
        buffer.resize(buffer.size() * 2U);
    }
}

std::string current_directory() {
    std::vector<char> buffer(256);
    for (;;) {
        errno = 0;
        if (getcwd(buffer.data(), buffer.size()) != nullptr) return std::string(buffer.data());
        if (errno != ERANGE) throw PathPolicyError(errno, "GETCWD_FAILED");
        if (buffer.size() >= static_cast<std::size_t>(PATH_MAX) * 4U) {
            throw PathPolicyError(ENAMETOOLONG, "GETCWD_TOO_LONG");
        }
        buffer.resize(buffer.size() * 2U);
    }
}

std::string directory_for_fd(int directory_fd) {
    if (directory_fd == AT_FDCWD) return current_directory();
    struct stat value{};
    if (fstat(directory_fd, &value) != 0) throw PathPolicyError(errno, "DIRFD_FSTAT_FAILED");
    if (!S_ISDIR(value.st_mode)) throw PathPolicyError(ENOTDIR, "DIRFD_NOT_DIRECTORY");
    return raw_readlink("/proc/self/fd/" + std::to_string(directory_fd));
}

std::string parent_path(std::string path) {
    while (path.size() > 1 && path.back() == '/') path.pop_back();
    const std::size_t slash = path.rfind('/');
    if (slash == std::string::npos || slash == 0) return "/";
    return path.substr(0, slash);
}

std::string canonical_existing(std::string path) {
    for (;;) {
        char* resolved = realpath(path.c_str(), nullptr);
        if (resolved != nullptr) {
            std::string value(resolved);
            std::free(resolved);
            return value;
        }
        if (errno != ENOENT && errno != ENOTDIR) throw PathPolicyError(errno, "REALPATH_FAILED");
        if (path == "/") throw PathPolicyError(EACCES, "CONFINEMENT_ROOT_MISSING");
        path = parent_path(std::move(path));
    }
}

}  // namespace

NativeResolvedPath NativeFileSystemResolver::resolve(const char* path) {
    if (path == nullptr) throw PathPolicyError(EFAULT, "PATH_NULL");
    if (path[0] != '/') return resolve_at(AT_FDCWD, path);
    NativePathDecision decision = global_policy().resolve_path(path);
    return NativeResolvedPath{AT_FDCWD, std::move(decision.path),
            std::move(decision.confinement_root), decision.policy_revision, decision.rewritten};
}

NativeResolvedPath NativeFileSystemResolver::resolve_at(int directory_fd, const char* path) {
    if (path == nullptr) throw PathPolicyError(EFAULT, "PATH_NULL");
    if (path[0] == '/') return resolve(path);
    const std::string base_host = directory_for_fd(directory_fd);
    const std::string base_guest = global_policy().reverse_map_path(base_host);
    NativePathDecision decision = global_policy().resolve_path(normalize_join(base_guest, path));
    return NativeResolvedPath{AT_FDCWD, std::move(decision.path),
            std::move(decision.confinement_root), decision.policy_revision, decision.rewritten};
}

void NativeFileSystemResolver::validate_confinement(
        const NativeResolvedPath& resolved, bool follow_final_symlink) {
    if (resolved.confinement_root.empty()) return;
    if (!path_has_prefix(resolved.path, resolved.confinement_root)) {
        throw PathPolicyError(EACCES, "CONFINEMENT_LEXICAL_ESCAPE");
    }
    const std::string canonical_root = canonical_existing(resolved.confinement_root);
    const std::string checked_path = follow_final_symlink ? resolved.path : parent_path(resolved.path);
    const std::string canonical_target = canonical_existing(checked_path);
    if (!path_has_prefix(canonical_target, canonical_root)) {
        throw PathPolicyError(EACCES, "CONFINEMENT_SYMLINK_ESCAPE");
    }
}

std::string NativeFileSystemResolver::rewrite_readlink_result(std::string_view value) {
    if (value.empty() || value.front() != '/') return std::string(value);
    return global_policy().reverse_map_path(value);
}

}  // namespace controlled_sandbox
