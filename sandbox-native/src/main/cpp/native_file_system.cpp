#include "controlled_sandbox/native_file_system.h"
#include "controlled_sandbox/native_policy.h"
#include "controlled_sandbox/native_procfs.h"

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
    NativePathDecision decision = NativeProcFileSystem::is_virtual_path(path)
            ? NativeProcFileSystem::materialize(path) : global_policy().resolve_path(path);
    return NativeResolvedPath{AT_FDCWD, std::move(decision.path),
            std::move(decision.confinement_root), decision.policy_revision, decision.rewritten};
}

NativeResolvedPath NativeFileSystemResolver::resolve_at(int directory_fd, const char* path) {
    if (path == nullptr) throw PathPolicyError(EFAULT, "PATH_NULL");
    if (path[0] == '/') return resolve(path);
    const std::string base_host = directory_for_fd(directory_fd);
    const std::string base_guest = global_policy().reverse_map_path(base_host);
    const std::string guest_path = normalize_join(base_guest, path);
    NativePathDecision decision = NativeProcFileSystem::is_virtual_path(guest_path)
            ? NativeProcFileSystem::materialize(guest_path) : global_policy().resolve_path(guest_path);
    return NativeResolvedPath{AT_FDCWD, std::move(decision.path),
            std::move(decision.confinement_root), decision.policy_revision, decision.rewritten};
}

NativeResolvedPath NativeFileSystemResolver::resolve_fd(int file_descriptor) {
    if (file_descriptor < 0) throw PathPolicyError(EBADF, "FD_NEGATIVE");
    const std::string host = raw_readlink("/proc/self/fd/" + std::to_string(file_descriptor));
    if (host.rfind("socket:[", 0) == 0 || host.rfind("pipe:[", 0) == 0
            || host.rfind("anon_inode:", 0) == 0 || host.rfind("memfd:", 0) == 0
            || host.rfind("/memfd:", 0) == 0) {
        const NativePolicySnapshot policy = global_policy().snapshot();
        if (!policy.configured) throw PathPolicyError(EACCES, "NATIVE_POLICY_NOT_CONFIGURED");
        return NativeResolvedPath{file_descriptor, host, {}, policy.revision, false};
    }
    const std::string guest = global_policy().reverse_map_path(host);
    NativePathDecision decision = global_policy().resolve_path(guest);
    if (decision.path != host) throw PathPolicyError(EACCES, "FD_HOST_PATH_MISMATCH");
    return NativeResolvedPath{file_descriptor, std::move(decision.path),
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


void NativeFileSystemResolver::validate_same_confinement(
        const NativeResolvedPath& first, const NativeResolvedPath& second) {
    if (first.policy_revision != second.policy_revision) {
        throw PathPolicyError(EAGAIN, "POLICY_REVISION_CHANGED");
    }
    if (first.confinement_root == second.confinement_root) return;
    if (first.confinement_root.empty() && second.confinement_root.empty()) return;
    throw PathPolicyError(EXDEV, "CROSS_CONFINEMENT_OPERATION_DENIED");
}

std::string NativeFileSystemResolver::rewrite_readlink_result(std::string_view value) {
    if (value.empty() || value.front() != '/') return std::string(value);
    return global_policy().reverse_map_path(value);
}

}  // namespace controlled_sandbox
