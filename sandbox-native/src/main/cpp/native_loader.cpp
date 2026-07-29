#include "controlled_sandbox/native_loader.h"

#include "controlled_sandbox/native_file_system.h"
#include "controlled_sandbox/native_policy.h"

#include <array>
#include <cerrno>
#include <fcntl.h>
#include <string>
#include <string_view>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

namespace controlled_sandbox {
namespace {

bool path_has_prefix(std::string_view path, std::string_view prefix) {
    return path == prefix || (path.size() > prefix.size()
            && path.compare(0, prefix.size(), prefix) == 0
            && path[prefix.size()] == '/');
}

bool regular_file(std::string_view path) {
    struct stat value{};
    return syscall(SYS_newfstatat, AT_FDCWD, std::string(path).c_str(), &value, 0) == 0
            && S_ISREG(value.st_mode);
}

bool safe_soname(std::string_view value) {
    if (value.empty() || value.size() > 255U || value.find('/') != std::string_view::npos
            || value.find('\\') != std::string_view::npos || value.find("..") != std::string_view::npos) {
        return false;
    }
    return value.size() > 3U && value.substr(value.size() - 3U) == ".so";
}

}  // namespace

NativeLibraryDecision NativeLibraryLoaderPolicy::resolve(const char* name) {
    if (name == nullptr || name[0] == '\0') {
        throw PathPolicyError(EACCES, "DLOPEN_MAIN_PROGRAM_DENIED");
    }
    const NativePolicySnapshot policy = global_policy().snapshot();
    if (!policy.configured) throw PathPolicyError(EACCES, "NATIVE_POLICY_NOT_CONFIGURED");
    const std::string value(name);
    if (value.front() == '/' || value.find('/') != std::string::npos) {
        NativeResolvedPath resolved = NativeFileSystemResolver::resolve(name);
        NativeFileSystemResolver::validate_confinement(resolved, true);
        if (!policy.native_library_root.empty()
                && path_has_prefix(resolved.path, policy.native_library_root)) {
            return NativeLibraryDecision{resolved.path, policy.revision, true, false};
        }
        if (is_allowed_system_path(resolved.path)) {
            return NativeLibraryDecision{resolved.path, policy.revision, false, true};
        }
        throw PathPolicyError(EACCES, "DLOPEN_PATH_OUTSIDE_ALLOWED_ROOTS");
    }
    if (!safe_soname(value)) throw PathPolicyError(EINVAL, "DLOPEN_SONAME_INVALID");
    if (!policy.native_library_root.empty()) {
        const std::string guest = policy.native_library_root + "/" + value;
        if (regular_file(guest)) return NativeLibraryDecision{guest, policy.revision, true, false};
    }
    if (is_allowed_system_soname(value)) {
        return NativeLibraryDecision{value, policy.revision, false, true};
    }
    throw PathPolicyError(ENOENT, "DLOPEN_SONAME_NOT_ALLOWED");
}

bool NativeLibraryLoaderPolicy::is_allowed_system_soname(std::string_view name) noexcept {
    static constexpr std::array<std::string_view, 22> allowed{
            "libandroid.so", "libaaudio.so", "libc.so", "libcamera2ndk.so", "libdl.so",
            "libEGL.so", "libGLESv2.so", "libGLESv3.so", "libjnigraphics.so", "liblog.so",
            "libm.so", "libmediandk.so", "libnativewindow.so", "libOpenMAXAL.so",
            "libOpenSLES.so", "libstdc++.so", "libsync.so", "libvulkan.so", "libz.so",
            "libbinder_ndk.so", "libandroid_runtime.so", "libneuralnetworks.so"};
    for (const auto value : allowed) if (value == name) return true;
    return false;
}

bool NativeLibraryLoaderPolicy::is_allowed_system_path(std::string_view path) noexcept {
    static constexpr std::array<std::string_view, 5> roots{
            "/system", "/apex", "/vendor", "/product", "/odm"};
    for (const auto root : roots) if (path_has_prefix(path, root)) return true;
    return false;
}

}  // namespace controlled_sandbox
