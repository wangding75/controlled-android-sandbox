#include "controlled_sandbox/native_procfs.h"

#include "controlled_sandbox/native_boundary.h"
#include "controlled_sandbox/native_fd_ledger.h"

#include <algorithm>
#include <cerrno>
#include <cctype>
#include <climits>
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <fcntl.h>
#include <fstream>
#include <optional>
#include <sstream>
#include <string>
#include <string_view>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <utility>
#include <vector>

namespace controlled_sandbox {
namespace {

constexpr std::size_t kMaxMapsBytes = 2U * 1024U * 1024U;
constexpr std::size_t kMaxProcBytes = 2U * 1024U * 1024U;

enum class ProcKind { None, File, Directory, Fd, FdInfo, MapFile };

struct ProcPath {
    ProcPath(ProcKind kind_value, int descriptor_value = -1,
             std::string leaf_value = {}, std::string map_file_value = {})
        : kind(kind_value), descriptor(descriptor_value), leaf(std::move(leaf_value)),
          map_file(std::move(map_file_value)) {}

    ProcKind kind{ProcKind::None};
    int descriptor{-1};
    std::string leaf;
    std::string map_file;
};

bool path_has_prefix(std::string_view path, std::string_view prefix) {
    return path == prefix || (path.size() > prefix.size()
            && path.compare(0, prefix.size(), prefix) == 0
            && path[prefix.size()] == '/');
}

bool decimal(std::string_view value) {
    if (value.empty()) return false;
    for (const char character : value) {
        if (character < '0' || character > '9') return false;
    }
    return true;
}

int parse_number(std::string_view value) {
    if (!decimal(value)) return -1;
    long result = 0;
    for (const char character : value) {
        result = result * 10 + (character - '0');
        if (result > INT_MAX) return -1;
    }
    return static_cast<int>(result);
}

bool own_proc_target(std::string_view target, const NativePolicySnapshot& policy) {
    if (target == "self" || target == "thread-self") return true;
    const int value = parse_number(target);
    if (value < 0) return false;
    return policy.configured ? value == policy.virtual_pid
            : value == NativeProcessIdentity::host_pid();
}

bool own_thread(std::string_view target, const NativePolicySnapshot& policy) {
    const int value = parse_number(target);
    if (value < 0) return false;
    return policy.configured ? value == policy.virtual_pid
            : value == NativeProcessIdentity::host_tid();
}

std::optional<ProcPath> classify(std::string_view path) {
    constexpr std::string_view prefix = "/proc/";
    if (path.size() <= prefix.size() || path.compare(0, prefix.size(), prefix) != 0) {
        return std::nullopt;
    }
    const std::size_t target_end = path.find('/', prefix.size());
    const std::size_t target_limit = target_end == std::string_view::npos
            ? path.size() : target_end;
    if (target_limit == prefix.size()) return std::nullopt;
    const NativePolicySnapshot policy = global_policy().snapshot();
    const std::string_view target = path.substr(prefix.size(), target_limit - prefix.size());
    if (!own_proc_target(target, policy)) return std::nullopt;

    if (target_end == std::string_view::npos) return ProcPath{ProcKind::Directory};
    std::string tail(path.substr(target_end + 1));
    if (tail.empty()) return ProcPath{ProcKind::Directory};
    if (tail == "task") return ProcPath{ProcKind::Directory, -1, "task", {}};
    if (tail.rfind("task/", 0) == 0) {
        const std::size_t task_end = tail.find('/', 5);
        const std::string_view task = task_end == std::string::npos
                ? std::string_view(tail).substr(5)
                : std::string_view(tail).substr(5, task_end - 5);
        if (!own_thread(task, policy)) return std::nullopt;
        if (task_end == std::string::npos) {
            return ProcPath{ProcKind::Directory, -1, "task/" + std::string(task), {}};
        }
        tail = tail.substr(task_end + 1);
        if (tail.empty()) {
            return ProcPath{ProcKind::Directory, -1, "task/" + std::string(task), {}};
        }
    }
    if (tail == "fd" || tail == "fdinfo" || tail == "map_files") {
        return ProcPath{ProcKind::Directory, -1, tail, {}};
    }
    for (const std::string_view leaf : {"maps", "smaps", "cmdline", "status", "mountinfo",
                                        "mounts", "cgroup", "stat", "statm", "io"}) {
        if (tail == leaf) return ProcPath{ProcKind::File, -1, std::string(leaf), {}};
    }
    if (tail.rfind("fd/", 0) == 0) {
        const int descriptor = parse_number(std::string_view(tail).substr(3));
        if (descriptor >= 0) return ProcPath{ProcKind::Fd, descriptor};
        return std::nullopt;
    }
    if (tail.rfind("fdinfo/", 0) == 0) {
        const int descriptor = parse_number(std::string_view(tail).substr(7));
        if (descriptor >= 0) return ProcPath{ProcKind::FdInfo, descriptor};
        return std::nullopt;
    }
    if (tail.rfind("map_files/", 0) == 0) {
        const std::string value = tail.substr(10);
        if (value.find('/') == std::string::npos && !value.empty()) {
            return ProcPath{ProcKind::MapFile, -1, {}, value};
        }
    }
    return std::nullopt;
}

std::string raw_readlink(std::string_view path) {
    std::vector<char> buffer(256);
    for (;;) {
        const std::string owned_path(path);
        const long length = trusted_syscall6(SYS_readlinkat, AT_FDCWD,
                reinterpret_cast<long>(owned_path.c_str()),
                reinterpret_cast<long>(buffer.data()), static_cast<long>(buffer.size()));
        if (length < 0) throw PathPolicyError(errno, "PROC_READLINK_FAILED");
        if (static_cast<std::size_t>(length) < buffer.size()) {
            return std::string(buffer.data(), static_cast<std::size_t>(length));
        }
        if (buffer.size() >= PATH_MAX * 4U) {
            throw PathPolicyError(ENAMETOOLONG, "PROC_READLINK_TOO_LONG");
        }
        buffer.resize(buffer.size() * 2U);
    }
}

std::string read_raw_file(std::string_view path) {
    const std::string owned_path(path);
    const long fd = trusted_syscall6(SYS_openat, AT_FDCWD,
            reinterpret_cast<long>(owned_path.c_str()), O_RDONLY | O_CLOEXEC, 0);
    if (fd < 0) throw PathPolicyError(errno, "PROC_FILE_OPEN_FAILED");
    std::string out;
    out.reserve(16384);
    std::vector<char> buffer(4096);
    for (;;) {
        const long count = trusted_syscall6(SYS_read, fd,
                reinterpret_cast<long>(buffer.data()), static_cast<long>(buffer.size()));
        if (count < 0) {
            const int saved = errno;
            (void) trusted_syscall6(SYS_close, fd);
            throw PathPolicyError(saved, "PROC_FILE_READ_FAILED");
        }
        if (count == 0) break;
        if (out.size() + static_cast<std::size_t>(count) > kMaxProcBytes) {
            (void) trusted_syscall6(SYS_close, fd);
            throw PathPolicyError(EFBIG, "PROC_FILE_TOO_LARGE");
        }
        out.append(buffer.data(), static_cast<std::size_t>(count));
    }
    (void) trusted_syscall6(SYS_close, fd);
    return out;
}

std::string sanitize_map_path(std::string_view path, const NativePolicySnapshot& policy) {
    if (path.empty() || path.front() == '[') return std::string(path);
    if (path == policy.apk_path || path_has_prefix(path, policy.instance_root)
            || (!policy.native_library_root.empty()
                && path_has_prefix(path, policy.native_library_root))) {
        return global_policy().reverse_map_path(path);
    }
    for (const std::string_view root : {"/system", "/apex", "/vendor", "/product", "/odm"}) {
        if (path_has_prefix(path, root)) return std::string(path);
    }
    if (path.front() == '/') return "[anon:sandbox-runtime]";
    return std::string(path);
}

std::string sanitize_mount_path(std::string line, const NativePolicySnapshot& policy) {
    auto replace_all = [&line](std::string_view from, std::string_view to) {
        if (from.empty()) return;
        std::size_t position = 0;
        while ((position = line.find(from, position)) != std::string::npos) {
            line.replace(position, from.size(), to);
            position += to.size();
        }
    };
    const std::string virtual_data = "/data/user/" + std::to_string(policy.virtual_user_id)
            + "/" + policy.package_name;
    const std::string virtual_apk = "/data/app/" + policy.package_name + "/base.apk";
    const std::string virtual_lib = "/data/app/" + policy.package_name + "/lib/"
            + policy.abi_name;
    replace_all(policy.instance_root, virtual_data);
    replace_all(policy.apk_path, virtual_apk);
    replace_all(policy.native_library_root, virtual_lib);
    return line;
}

std::string status_value(std::string_view raw, std::string_view key,
                         std::string_view fallback) {
    std::size_t cursor = 0;
    while (cursor < raw.size()) {
        const std::size_t end = raw.find('\n', cursor);
        const std::string_view line = raw.substr(cursor,
                end == std::string_view::npos ? raw.size() - cursor : end - cursor);
        if (line.rfind(key, 0) == 0) {
            const std::size_t separator = line.find_first_of("\t ", key.size());
            if (separator != std::string_view::npos) {
                std::size_t start = separator;
                while (start < line.size() && (line[start] == '\t' || line[start] == ' ')) start++;
                return std::string(line.substr(start));
            }
        }
        if (end == std::string_view::npos) break;
        cursor = end + 1;
    }
    return std::string(fallback);
}

void ensure_capability_directories(int root_fd, std::string_view relative) {
    std::string current;
    std::size_t cursor = 0;
    while (cursor < relative.size()) {
        const std::size_t slash = relative.find('/', cursor);
        const std::size_t end = slash == std::string_view::npos ? relative.size() : slash;
        if (end == cursor) { cursor = end + 1; continue; }
        if (!current.empty()) current.push_back('/');
        current.append(relative.substr(cursor, end - cursor));
        const long result = trusted_syscall6(SYS_mkdirat, root_fd,
                reinterpret_cast<long>(current.c_str()), 0700);
        if (result < 0 && errno != EEXIST) {
            throw PathPolicyError(errno, "PROC_CAPABILITY_DIRECTORY_FAILED");
        }
        if (slash == std::string_view::npos) break;
        cursor = slash + 1;
    }
}

void write_capability_file(int root_fd, std::string_view relative, std::string_view content) {
    const std::string path(relative);
    const long fd = trusted_syscall6(SYS_openat, root_fd,
            reinterpret_cast<long>(path.c_str()), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0400);
    if (fd < 0) throw PathPolicyError(errno, "PROC_CAPABILITY_FILE_OPEN_FAILED");
    std::size_t cursor = 0;
    while (cursor < content.size()) {
        const long count = trusted_syscall6(SYS_write, fd,
                reinterpret_cast<long>(content.data() + cursor),
                static_cast<long>(content.size() - cursor));
        if (count <= 0) {
            const int saved = errno == 0 ? EIO : errno;
            (void) trusted_syscall6(SYS_close, fd);
            throw PathPolicyError(saved, "PROC_CAPABILITY_FILE_WRITE_FAILED");
        }
        cursor += static_cast<std::size_t>(count);
    }
#ifdef SYS_fchmod
    (void) trusted_syscall6(SYS_fchmod, fd, 0400);
#else
    (void) ::fchmod(static_cast<int>(fd), 0400);
#endif
    (void) trusted_syscall6(SYS_close, fd);
}

NativePathDecision snapshot_decision(const NativePolicySnapshot& policy,
                                     std::string relative, bool directory) {
    const int root_fd = global_policy().capability_data_root_fd();
    if (root_fd >= 0) {
        const std::size_t slash = relative.rfind('/');
        if (slash != std::string::npos) ensure_capability_directories(root_fd, relative.substr(0, slash));
        if (directory) ensure_capability_directories(root_fd, relative);
        return NativePathDecision{std::move(relative), {}, policy.revision, true,
                root_fd, true};
    }
    const std::filesystem::path output = std::filesystem::path(policy.instance_root)
            / ".runtime" / "proc" / std::to_string(policy.revision) / relative;
    std::error_code error;
    if (directory) std::filesystem::create_directories(output, error);
    else std::filesystem::create_directories(output.parent_path(), error);
    if (error) throw PathPolicyError(EIO, "PROC_SNAPSHOT_DIRECTORY_FAILED");
    return NativePathDecision{output.string(), policy.instance_root, policy.revision, true};
}

void write_snapshot(const NativePathDecision& decision, std::string_view content) {
    if (decision.capability) {
        write_capability_file(decision.directory_fd, decision.path, content);
        return;
    }
    const std::filesystem::path temporary = decision.path + ".tmp";
    {
        std::ofstream stream(temporary, std::ios::binary | std::ios::trunc);
        if (!stream) throw PathPolicyError(EIO, "PROC_SNAPSHOT_OPEN_FAILED");
        stream.write(content.data(), static_cast<std::streamsize>(content.size()));
        stream.flush();
        if (!stream) throw PathPolicyError(EIO, "PROC_SNAPSHOT_WRITE_FAILED");
    }
    (void) ::chmod(temporary.c_str(), S_IRUSR);
    std::error_code error;
    std::filesystem::rename(temporary, decision.path, error);
    if (error) {
        std::filesystem::remove(temporary);
        throw PathPolicyError(EIO, "PROC_SNAPSHOT_RENAME_FAILED");
    }
}

void create_virtual_symlink(const NativePathDecision& directory, std::string_view name,
                            std::string_view target) {
    if (directory.capability) {
        const std::string link_path = directory.path + "/" + std::string(name);
        const std::string owned_target(target);
        const long result = trusted_syscall6(SYS_symlinkat,
                reinterpret_cast<long>(owned_target.c_str()), directory.directory_fd,
                reinterpret_cast<long>(link_path.c_str()));
        if (result < 0 && errno != EEXIST) {
            throw PathPolicyError(errno, "PROC_SNAPSHOT_SYMLINK_FAILED");
        }
        return;
    }
    const std::filesystem::path path = std::filesystem::path(directory.path) / name;
    std::error_code error;
    std::filesystem::remove(path, error);
    error.clear();
    std::filesystem::create_symlink(std::string(target), path, error);
    if (error && error.value() != EEXIST) {
        throw PathPolicyError(EIO, "PROC_SNAPSHOT_SYMLINK_FAILED");
    }
}

std::vector<int> visible_fd_candidates() {
    std::vector<int> descriptors = NativeFdLedger::visible_descriptors();
    for (const int descriptor : {0, 1, 2}) {
        if (std::find(descriptors.begin(), descriptors.end(), descriptor) == descriptors.end()) {
            descriptors.push_back(descriptor);
        }
    }
    std::sort(descriptors.begin(), descriptors.end());
    return descriptors;
}

bool allow_guest_descriptor(int descriptor) {
    const auto record = NativeFdLedger::lookup(descriptor);
    if (!record && descriptor > STDERR_FILENO) {
        // An unknown descriptor is not evidence of Guest ownership.  Only the
        // process stdio descriptors may be adopted as inherited compatibility
        // state; every other inherited/foreign FD must fail closed until a
        // brokered or intercepted producer registers it in the ledger.
        errno = EACCES;
        return false;
    }
    if (!record) {
        NativeFdLedger::observe_inherited(descriptor,
                global_policy().snapshot().revision);
    }
    if (!NativeFdLedger::guest_visible(descriptor)) {
        errno = EACCES;
        return false;
    }
    return true;
}

std::string fdinfo_content(int descriptor) {
    if (!allow_guest_descriptor(descriptor)) {
        throw PathPolicyError(errno == EACCES ? EACCES : EPERM, "UNKNOWN_OR_INTERNAL_FD_DENIED");
    }
    try {
        return read_raw_file("/proc/self/fdinfo/" + std::to_string(descriptor));
    } catch (const PathPolicyError&) {
        // Some runtime layers expose a Guest FD whose kernel fdinfo leaf is not
        // readable through the host proc mount.  Re-render only the
        // authoritative, already-validated Guest descriptor metadata instead of
        // falling back to an unfiltered host procfs read.
        const long status_flags = trusted_syscall6(SYS_fcntl, descriptor, F_GETFL);
        const long descriptor_flags = trusted_syscall6(SYS_fcntl, descriptor, F_GETFD);
        struct stat value{};
        if (status_flags < 0 || descriptor_flags < 0
                || trusted_syscall6(SYS_fstat, descriptor,
                        reinterpret_cast<long>(&value)) != 0) {
            throw;
        }
        std::ostringstream rendered;
        rendered << "pos:\t0\nflags:\t" << std::oct
                 << (status_flags | ((descriptor_flags & FD_CLOEXEC) != 0 ? O_CLOEXEC : 0))
                 << "\nmnt_id:\t0\nino:\t" << std::dec << value.st_ino << "\n";
        return rendered.str();
    }
}

}  // namespace

bool NativeProcFileSystem::is_virtual_path(std::string_view guest_path) noexcept {
    // The classifier projects the self/thread-self hierarchy before
    // materializing a leaf, including the explicit /proc/self/cmdline view.
    try { return classify(guest_path).has_value(); } catch (...) { return false; }
}

bool NativeProcFileSystem::is_virtual_directory_path(std::string_view guest_path) noexcept {
    try {
        const auto path = classify(guest_path);
        return path && path->kind == ProcKind::Directory;
    } catch (...) { return false; }
}

bool NativeProcFileSystem::is_proc_fd_path(std::string_view guest_path) noexcept {
    try {
        const auto path = classify(guest_path);
        return path && path->kind == ProcKind::Fd;
    } catch (...) { return false; }
}

bool NativeProcFileSystem::is_proc_fdinfo_path(std::string_view guest_path) noexcept {
    try {
        const auto path = classify(guest_path);
        return path && path->kind == ProcKind::FdInfo;
    } catch (...) { return false; }
}

bool NativeProcFileSystem::is_proc_map_file_path(std::string_view guest_path) noexcept {
    try {
        const auto path = classify(guest_path);
        return path && path->kind == ProcKind::MapFile;
    } catch (...) { return false; }
}

NativePathDecision NativeProcFileSystem::materialize(std::string_view guest_path) {
    const NativePolicySnapshot policy = global_policy().snapshot();
    if (!policy.configured) throw PathPolicyError(EACCES, "NATIVE_POLICY_NOT_CONFIGURED");
    const auto classified = classify(guest_path);
    if (!classified) throw PathPolicyError(ENOENT, "PROC_VIRTUAL_PATH_UNKNOWN");
    if (classified->kind == ProcKind::Directory) return materialize_directory(guest_path);
    if (classified->kind == ProcKind::Fd || classified->kind == ProcKind::MapFile) {
        throw PathPolicyError(EACCES, "PROC_SPECIAL_PATH");
    }

    std::string content;
    if (classified->kind == ProcKind::FdInfo) {
        content = fdinfo_content(classified->descriptor);
    } else if (classified->leaf == "maps") {
        content = sanitize_maps(read_raw_file("/proc/self/maps"), policy);
    } else if (classified->leaf == "smaps") {
        content = sanitize_maps(read_raw_file("/proc/self/smaps"), policy);
    } else if (classified->leaf == "cmdline") {
        content = render_cmdline(policy);
    } else if (classified->leaf == "status") {
        content = render_status(policy);
    } else if (classified->leaf == "mountinfo") {
        content = sanitize_mountinfo(read_raw_file("/proc/self/mountinfo"), policy);
    } else if (classified->leaf == "mounts") {
        content = render_mounts(read_raw_file("/proc/self/mounts"), policy);
    } else if (classified->leaf == "cgroup") {
        content = render_cgroup(read_raw_file("/proc/self/cgroup"), policy);
    } else if (classified->leaf == "stat") {
        content = render_stat(policy);
    } else if (classified->leaf == "statm") {
        content = render_statm(policy);
    } else if (classified->leaf == "io") {
        content = render_io(policy);
    } else {
        throw PathPolicyError(ENOENT, "PROC_VIRTUAL_PATH_UNKNOWN");
    }
    std::string snapshot_suffix = classified->leaf;
    if (classified->kind == ProcKind::FdInfo) {
        snapshot_suffix = "fdinfo/" + std::to_string(classified->descriptor);
    }
    NativePathDecision decision = snapshot_decision(policy,
            ".runtime/proc/" + std::to_string(policy.revision) + "/" + snapshot_suffix,
            false);
    write_snapshot(decision, content);
    return decision;
}

NativePathDecision NativeProcFileSystem::materialize_directory(std::string_view guest_path) {
    const NativePolicySnapshot policy = global_policy().snapshot();
    if (!policy.configured) throw PathPolicyError(EACCES, "NATIVE_POLICY_NOT_CONFIGURED");
    const auto classified = classify(guest_path);
    if (!classified || classified->kind != ProcKind::Directory) {
        throw PathPolicyError(ENOENT, "PROC_DIRECTORY_UNKNOWN");
    }
    const std::string suffix = classified->leaf.empty() ? "root" : classified->leaf;
    NativePathDecision decision = snapshot_decision(policy,
            ".runtime/proc/" + std::to_string(policy.revision) + "/" + suffix, true);

    if (suffix == "fd") {
        for (const int descriptor : visible_fd_candidates()) {
            std::string raw;
            try { raw = raw_readlink("/proc/self/fd/" + std::to_string(descriptor)); }
            catch (...) { continue; }
            NativeFdLedger::observe_inherited(descriptor, policy.revision);
            create_virtual_symlink(decision, std::to_string(descriptor),
                    NativeFdLedger::project_readlink(descriptor, raw));
        }
    } else if (suffix == "fdinfo") {
        for (const int descriptor : visible_fd_candidates()) {
            try {
                const NativePathDecision info = snapshot_decision(policy,
                        ".runtime/proc/" + std::to_string(policy.revision) + "/fdinfo/"
                        + std::to_string(descriptor), false);
                write_snapshot(info, fdinfo_content(descriptor));
            } catch (...) { }
        }
    } else if (suffix == "task") {
        const std::string tid = std::to_string(NativeProcessIdentity::guest_tid());
        if (decision.capability) {
            ensure_capability_directories(decision.directory_fd, decision.path + "/" + tid);
        } else {
            std::error_code error;
            std::filesystem::create_directories(std::filesystem::path(decision.path) / tid, error);
        }
    } else if (suffix == "map_files") {
        std::stringstream input(read_raw_file("/proc/self/maps"));
        std::string line;
        while (std::getline(input, line)) {
            std::istringstream fields(line);
            std::string range;
            std::string permissions;
            std::string offset;
            std::string device;
            std::string inode;
            fields >> range >> permissions >> offset >> device >> inode;
            std::string path;
            std::getline(fields, path);
            while (!path.empty() && std::isspace(static_cast<unsigned char>(path.front()))) {
                path.erase(path.begin());
            }
            if (range.empty() || path.empty() || path.front() == '[') continue;
            create_virtual_symlink(decision, range, sanitize_map_path(path, policy));
        }
    }
    return decision;
}

int NativeProcFileSystem::open_fd_path(std::string_view guest_path, int flags) {
    const auto classified = classify(guest_path);
    if (!classified || classified->kind != ProcKind::Fd) { errno = ENOENT; return -1; }
    if (!allow_guest_descriptor(classified->descriptor)) return -1;
    if ((flags & O_DIRECTORY) != 0) {
        struct stat value{};
        if (trusted_syscall6(SYS_fstat, classified->descriptor,
                reinterpret_cast<long>(&value)) != 0) return -1;
        if (!S_ISDIR(value.st_mode)) { errno = ENOTDIR; return -1; }
    }
    (void) flags;
    const long duplicate = trusted_syscall6(SYS_fcntl, classified->descriptor,
#ifdef F_DUPFD_CLOEXEC
            F_DUPFD_CLOEXEC,
#else
            F_DUPFD,
#endif
            3);
    if (duplicate < 0) return -1;
    const auto source_record = NativeFdLedger::lookup(classified->descriptor);
    const std::string virtual_target = source_record && !source_record->virtual_path.empty()
            ? source_record->virtual_path : NativeFdLedger::project_readlink(
                    classified->descriptor, "");
    NativeFdLedger::duplicate(classified->descriptor, static_cast<int>(duplicate));
    global_policy().register_capability_fd(static_cast<int>(duplicate));
    NativeFdLedger::register_fd(static_cast<int>(duplicate),
            NativeFdOwnership::VirtualizedPath, global_policy().snapshot().revision,
            virtual_target);
    return static_cast<int>(duplicate);
}

int NativeProcFileSystem::stat_fd_path(std::string_view guest_path, struct stat* value,
                                       bool follow) {
    if (value == nullptr) { errno = EFAULT; return -1; }
    const auto classified = classify(guest_path);
    if (!classified || classified->kind != ProcKind::Fd
            || !allow_guest_descriptor(classified->descriptor)) {
        errno = EACCES;
        return -1;
    }
    if (follow) {
        return static_cast<int>(trusted_syscall6(SYS_fstat, classified->descriptor,
                reinterpret_cast<long>(value)));
    }
    std::memset(value, 0, sizeof(*value));
    value->st_mode = S_IFLNK | 0777;
    const std::string raw = raw_readlink("/proc/self/fd/" + std::to_string(classified->descriptor));
    value->st_size = static_cast<off_t>(NativeFdLedger::project_readlink(
            classified->descriptor, raw).size());
    return 0;
}

ssize_t NativeProcFileSystem::readlink_virtual(std::string_view guest_path, char* buffer,
                                               std::size_t size) {
    if (buffer == nullptr) { errno = EFAULT; return -1; }
    if (size == 0) return 0;
    const auto classified = classify(guest_path);
    if (!classified || (classified->kind != ProcKind::Fd
            && classified->kind != ProcKind::MapFile)) { errno = EINVAL; return -1; }
    std::string value;
    if (classified->kind == ProcKind::Fd) {
        if (!allow_guest_descriptor(classified->descriptor)) return -1;
        const std::string raw = raw_readlink("/proc/self/fd/" + std::to_string(classified->descriptor));
        value = NativeFdLedger::project_readlink(classified->descriptor, raw);
    } else {
        value = sanitize_map_path(raw_readlink("/proc/self/map_files/" + classified->map_file),
                global_policy().snapshot());
    }
    const std::size_t count = std::min(size, value.size());
    std::memcpy(buffer, value.data(), count);
    return static_cast<ssize_t>(count);
}

std::string NativeProcFileSystem::sanitize_mountinfo(std::string_view raw,
                                                     const NativePolicySnapshot& policy) {
    return render_mounts(raw, policy);
}

std::string NativeProcFileSystem::sanitize_maps(std::string_view raw,
                                                const NativePolicySnapshot& policy) {
    std::stringstream input{std::string(raw)};
    std::string output;
    std::string line;
    while (std::getline(input, line)) {
        std::size_t path_start = std::string::npos;
        std::size_t fields = 0;
        bool in_space = true;
        for (std::size_t index = 0; index < line.size(); index++) {
            const bool space = line[index] == ' ' || line[index] == '\t';
            if (in_space && !space) {
                fields++;
                if (fields == 6U) { path_start = index; break; }
            }
            in_space = space;
        }
        if (path_start != std::string::npos) {
            const std::string replacement = sanitize_map_path(
                    std::string_view(line).substr(path_start), policy);
            line.replace(path_start, std::string::npos, replacement);
        }
        output.append(line);
        output.push_back('\n');
        if (output.size() > kMaxMapsBytes) throw PathPolicyError(EFBIG, "PROC_MAPS_TOO_LARGE");
    }
    return output;
}

std::string NativeProcFileSystem::render_mounts(std::string_view raw,
                                                const NativePolicySnapshot& policy) {
    std::string output;
    output.reserve(raw.size());
    std::size_t cursor = 0;
    while (cursor < raw.size()) {
        const std::size_t end = raw.find('\n', cursor);
        output.append(sanitize_mount_path(std::string(raw.substr(cursor,
                end == std::string_view::npos ? raw.size() - cursor : end - cursor)), policy));
        output.push_back('\n');
        if (output.size() > kMaxProcBytes) throw PathPolicyError(EFBIG, "PROC_MOUNTS_TOO_LARGE");
        if (end == std::string_view::npos) break;
        cursor = end + 1;
    }
    return output;
}

std::string NativeProcFileSystem::render_cgroup(std::string_view raw,
                                                const NativePolicySnapshot& policy) {
    return render_mounts(raw, policy);
}

std::string NativeProcFileSystem::render_cmdline(const NativePolicySnapshot& policy) {
    std::string value = NativeProcessIdentity::sanitize_process_name(policy.process_name);
    value.push_back('\0');
    return value;
}

std::string NativeProcFileSystem::render_status(const NativePolicySnapshot& policy) {
    const std::string raw = read_raw_file("/proc/self/status");
    const NativeSeccompSnapshot seccomp = NativeSeccompPolicy::snapshot();
    std::ostringstream out;
    out << "Name:\t" << NativeProcessIdentity::sanitize_process_name(policy.process_name) << "\n";
    out << "Umask:\t0077\nState:\tS (sleeping)\n";
    out << "Tgid:\t" << policy.virtual_pid << "\nNgid:\t0\nPid:\t"
        << policy.virtual_pid << "\nPPid:\t" << NativeProcessIdentity::guest_ppid() << "\n";
    out << "TracerPid:\t0\nUid:\t" << policy.virtual_uid << '\t' << policy.virtual_uid
        << '\t' << policy.virtual_uid << '\t' << policy.virtual_uid << "\nGid:\t"
        << NativeProcessIdentity::guest_gid() << '\t' << NativeProcessIdentity::guest_gid()
        << '\t' << NativeProcessIdentity::guest_gid() << '\t'
        << NativeProcessIdentity::guest_gid() << "\nFDSize:\t128\nGroups:\t\nThreads:\t"
        << status_value(raw, "Threads:", "1") << "\nNoNewPrivs:\t" << seccomp.no_new_privs
        << "\nSeccomp:\t" << seccomp.mode << "\nSpeculation_Store_Bypass:\tunknown\n";
    return out.str();
}

std::string NativeProcFileSystem::render_stat(const NativePolicySnapshot& policy) {
    const std::string name = NativeProcessIdentity::sanitize_process_name(policy.process_name);
    return std::to_string(policy.virtual_pid) + " (" + name + ") S "
            + std::to_string(NativeProcessIdentity::guest_ppid())
            + " 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0\n";
}

std::string NativeProcFileSystem::render_statm(const NativePolicySnapshot&) {
    return "0 0 0 0 0 0 0\n";
}

std::string NativeProcFileSystem::render_io(const NativePolicySnapshot&) {
    return "rchar: 0\nwchar: 0\nsyscr: 0\nsyscw: 0\nread_bytes: 0\nwrite_bytes: 0\ncancelled_write_bytes: 0\n";
}

}  // namespace controlled_sandbox
