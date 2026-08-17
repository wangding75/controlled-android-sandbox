#include "controlled_sandbox/native_procfs.h"

#include <cerrno>
#include <climits>
#include <cstdint>
#include <filesystem>
#include <fcntl.h>
#include <fstream>
#include <algorithm>
#include <sstream>
#include <string>
#include <string_view>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <vector>

namespace controlled_sandbox {
namespace {

constexpr std::size_t kMaxMapsBytes = 2U * 1024U * 1024U;
constexpr std::size_t kMaxProcBytes = 2U * 1024U * 1024U;

bool path_has_prefix(std::string_view path, std::string_view prefix) {
    return path == prefix || (path.size() > prefix.size()
            && path.compare(0, prefix.size(), prefix) == 0
            && path[prefix.size()] == '/');
}

std::string canonical_path(std::string_view path);

std::string leaf_for(std::string_view path) {
    const std::string canonical = canonical_path(path);
    if (canonical == "/proc/self/maps") return "maps";
    if (canonical == "/proc/self/cmdline") return "cmdline";
    if (canonical == "/proc/self/status") return "status";
    if (canonical == "/proc/self/mountinfo") return "mountinfo";
    if (canonical == "/proc/self/stat") return "stat";
    if (canonical == "/proc/self/statm") return "statm";
    if (canonical == "/proc/self/io") return "io";
    throw PathPolicyError(ENOENT, "PROC_VIRTUAL_PATH_UNKNOWN");
}

bool is_decimal(std::string_view value) {
    if (value.empty()) return false;
    for (const char character : value) {
        if (character < '0' || character > '9') return false;
    }
    return true;
}

int parse_pid(std::string_view value) {
    if (!is_decimal(value)) return -1;
    long result = 0;
    for (const char character : value) {
        result = result * 10 + (character - '0');
        if (result > INT_MAX) return -1;
    }
    return static_cast<int>(result);
}

std::string canonical_path(std::string_view path) {
    constexpr std::string_view prefix = "/proc/";
    if (path.size() <= prefix.size() || path.compare(0, prefix.size(), prefix) != 0) {
        return {};
    }
    const std::size_t target_end = path.find('/', prefix.size());
    if (target_end == std::string_view::npos || target_end == prefix.size()) return {};
    const std::string_view target = path.substr(prefix.size(), target_end - prefix.size());
    const NativePolicySnapshot policy = global_policy().snapshot();
    const int host_pid = static_cast<int>(getpid());
    const int target_pid = parse_pid(target);
    if (target != "self" && target != "thread-self"
            && target_pid != host_pid && target_pid != policy.virtual_pid) {
        return {};
    }

    std::string tail(path.substr(target_end + 1));
    constexpr std::string_view task_prefix = "task/";
    if (tail.compare(0, task_prefix.size(), task_prefix) == 0) {
        const std::size_t task_end = tail.find('/', task_prefix.size());
        if (task_end == std::string::npos) return {};
        const std::string_view task_id(tail.data() + task_prefix.size(),
                task_end - task_prefix.size());
        if (parse_pid(task_id) < 0) return {};
        tail.erase(0, task_end + 1);
    }
    for (const std::string_view leaf : {"maps", "cmdline", "status", "mountinfo",
                                        "stat", "statm", "io"}) {
        if (tail == leaf) return "/proc/self/" + std::string(leaf);
    }
    return {};
}

std::string read_raw_file(std::string_view path) {
    const long fd = syscall(SYS_openat, AT_FDCWD, std::string(path).c_str(),
            O_RDONLY | O_CLOEXEC, 0);
    if (fd < 0) throw PathPolicyError(errno, "PROC_MAPS_OPEN_FAILED");
    std::string out;
    out.reserve(16384);
    std::vector<char> buffer(4096);
    for (;;) {
        const long count = syscall(SYS_read, static_cast<int>(fd), buffer.data(), buffer.size());
        if (count < 0) {
            const int saved = errno;
            (void) syscall(SYS_close, static_cast<int>(fd));
            throw PathPolicyError(saved, "PROC_MAPS_READ_FAILED");
        }
        if (count == 0) break;
        if (out.size() + static_cast<std::size_t>(count) > kMaxProcBytes) {
            (void) syscall(SYS_close, static_cast<int>(fd));
            throw PathPolicyError(EFBIG, "PROC_MAPS_TOO_LARGE");
        }
        out.append(buffer.data(), static_cast<std::size_t>(count));
    }
    (void) syscall(SYS_close, static_cast<int>(fd));
    return out;
}

std::string read_raw_maps() { return read_raw_file("/proc/self/maps"); }

std::string sanitize_map_path(std::string_view path, const NativePolicySnapshot& policy) {
    if (path.empty() || path.front() == '[') return std::string(path);
    if (path == policy.apk_path || path_has_prefix(path, policy.instance_root)
            || (!policy.native_library_root.empty()
                && path_has_prefix(path, policy.native_library_root))) {
        return global_policy().reverse_map_path(path);
    }
    if (path_has_prefix(path, "/system") || path_has_prefix(path, "/apex")
            || path_has_prefix(path, "/vendor") || path_has_prefix(path, "/product")
            || path_has_prefix(path, "/odm")) {
        return std::string(path);
    }
    if (path_has_prefix(path, "/data") || path_has_prefix(path, "/storage")) {
        return "[anon:sandbox-runtime]";
    }
    return std::string(path);
}

std::string sanitize_process_name(std::string value) {
    if (value.size() > 63U) value.resize(63U);
    for (char& character : value) {
        if (character == '\n' || character == '\r' || character == '\t' || character == '\0'
                || character == '(' || character == ')') {
            character = '_';
        }
    }
    return value.empty() ? "guest" : value;
}

void write_atomic(const std::filesystem::path& output, std::string_view content) {
    std::error_code error;
    std::filesystem::create_directories(output.parent_path(), error);
    if (error) throw PathPolicyError(EIO, "PROC_SNAPSHOT_DIRECTORY_FAILED");
    const std::filesystem::path temporary = output.string() + ".tmp";
    {
        std::ofstream stream(temporary, std::ios::binary | std::ios::trunc);
        if (!stream) throw PathPolicyError(EIO, "PROC_SNAPSHOT_OPEN_FAILED");
        stream.write(content.data(), static_cast<std::streamsize>(content.size()));
        stream.flush();
        if (!stream) throw PathPolicyError(EIO, "PROC_SNAPSHOT_WRITE_FAILED");
    }
    (void) chmod(temporary.c_str(), S_IRUSR);
    std::filesystem::rename(temporary, output, error);
    if (error) {
        std::filesystem::remove(temporary);
        throw PathPolicyError(EIO, "PROC_SNAPSHOT_RENAME_FAILED");
    }
}

}  // namespace

bool NativeProcFileSystem::is_virtual_path(std::string_view guest_path) noexcept {
    try { return !canonical_path(guest_path).empty(); }
    catch (...) { return false; }
}

NativePathDecision NativeProcFileSystem::materialize(std::string_view guest_path) {
    const NativePolicySnapshot policy = global_policy().snapshot();
    if (!policy.configured) throw PathPolicyError(EACCES, "NATIVE_POLICY_NOT_CONFIGURED");
    const std::string leaf = leaf_for(guest_path);
    const std::filesystem::path output = std::filesystem::path(policy.instance_root)
            / ".runtime" / "proc" / std::to_string(policy.revision) / leaf;
    std::string content;
    if (leaf == "maps") content = sanitize_maps(read_raw_maps(), policy);
    else if (leaf == "cmdline") content = render_cmdline(policy);
    else if (leaf == "status") content = render_status(policy);
    else if (leaf == "mountinfo") content = sanitize_mountinfo(
            read_raw_file("/proc/self/mountinfo"), policy);
    else if (leaf == "stat") content = render_stat(policy);
    else if (leaf == "statm") content = render_statm(policy);
    else content = render_io(policy);
    write_atomic(output, content);
    return NativePathDecision{output.string(), policy.instance_root, policy.revision, true};
}

std::string NativeProcFileSystem::sanitize_mountinfo(std::string_view raw,
                                                     const NativePolicySnapshot& policy) {
    std::string output;
    output.reserve(raw.size());
    const std::string virtual_data = "/data/user/" + std::to_string(policy.virtual_user_id)
            + "/" + policy.package_name;
    const std::string virtual_apk = "/data/app/" + policy.package_name + "/base.apk";
    const std::string virtual_lib = "/data/app/" + policy.package_name + "/lib/"
            + policy.abi_name;
    for (std::size_t cursor = 0; cursor < raw.size();) {
        const std::size_t end = raw.find('\n', cursor);
        std::string line = std::string(raw.substr(cursor,
                end == std::string_view::npos ? raw.size() - cursor : end - cursor));
        auto replace_all = [&line](const std::string& from, const std::string& to) {
            if (from.empty()) return;
            std::size_t position = 0;
            while ((position = line.find(from, position)) != std::string::npos) {
                line.replace(position, from.size(), to);
                position += to.size();
            }
        };
        replace_all(policy.instance_root, virtual_data);
        replace_all(policy.apk_path, virtual_apk);
        replace_all(policy.native_library_root, virtual_lib);
        output.append(line);
        output.push_back('\n');
        if (output.size() > kMaxProcBytes) throw PathPolicyError(EFBIG, "PROC_MOUNTINFO_TOO_LARGE");
        if (end == std::string_view::npos) break;
        cursor = end + 1;
    }
    return output;
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

std::string NativeProcFileSystem::render_cmdline(const NativePolicySnapshot& policy) {
    std::string value = sanitize_process_name(policy.process_name);
    value.push_back('\0');
    return value;
}

std::string NativeProcFileSystem::render_status(const NativePolicySnapshot& policy) {
    const std::string name = sanitize_process_name(policy.process_name);
    std::ostringstream out;
    out << "Name:\t" << name << "\n";
    out << "Umask:\t0077\n";
    out << "State:\tS (sleeping)\n";
    out << "Tgid:\t" << policy.virtual_pid << "\n";
    out << "Ngid:\t0\n";
    out << "Pid:\t" << policy.virtual_pid << "\n";
    out << "PPid:\t1\n";
    out << "TracerPid:\t0\n";
    out << "Uid:\t" << policy.virtual_uid << '\t' << policy.virtual_uid << '\t'
        << policy.virtual_uid << '\t' << policy.virtual_uid << "\n";
    out << "Gid:\t" << policy.virtual_uid << '\t' << policy.virtual_uid << '\t'
        << policy.virtual_uid << '\t' << policy.virtual_uid << "\n";
    out << "FDSize:\t128\n";
    out << "Groups:\t\n";
    out << "Threads:\t1\n";
    out << "NoNewPrivs:\t1\n";
    out << "Seccomp:\t2\n";
    out << "Speculation_Store_Bypass:\tunknown\n";
    return out.str();
}

std::string NativeProcFileSystem::render_stat(const NativePolicySnapshot& policy) {
    const std::string name = sanitize_process_name(policy.process_name);
    // Keep the Linux /proc/stat field ordering.  Values not meaningful inside the virtual
    // process are deterministic zeroes, while pid/ppid/state retain the Guest identity.
    return std::to_string(policy.virtual_pid) + " (" + name + ") S 1 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0\n";
}

std::string NativeProcFileSystem::render_statm(const NativePolicySnapshot&) {
    return "0 0 0 0 0 0 0\n";
}

std::string NativeProcFileSystem::render_io(const NativePolicySnapshot&) {
    return "rchar: 0\nwchar: 0\nsyscr: 0\nsyscw: 0\nread_bytes: 0\nwrite_bytes: 0\ncancelled_write_bytes: 0\n";
}

}  // namespace controlled_sandbox
