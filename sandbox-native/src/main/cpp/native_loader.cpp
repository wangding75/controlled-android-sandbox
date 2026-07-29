#include "controlled_sandbox/native_loader.h"

#include "controlled_sandbox/native_file_system.h"
#include "controlled_sandbox/native_policy.h"

#include <array>
#include <cerrno>
#include <cstring>
#include <elf.h>
#include <fcntl.h>
#include <mutex>
#include <string>
#include <string_view>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

namespace controlled_sandbox {
namespace {

constexpr std::uint64_t DLEXT_RESERVED_ADDRESS = 0x1ULL;
constexpr std::uint64_t DLEXT_RESERVED_ADDRESS_HINT = 0x2ULL;
constexpr std::uint64_t DLEXT_WRITE_RELRO = 0x4ULL;
constexpr std::uint64_t DLEXT_USE_RELRO = 0x8ULL;
constexpr std::uint64_t DLEXT_USE_LIBRARY_FD = 0x10ULL;
constexpr std::uint64_t DLEXT_USE_LIBRARY_FD_OFFSET = 0x20ULL;
constexpr std::uint64_t DLEXT_FORCE_LOAD = 0x40ULL;
constexpr std::uint64_t DLEXT_USE_NAMESPACE = 0x200ULL;
constexpr std::uint64_t DLEXT_RESERVED_ADDRESS_RECURSIVE = 0x400ULL;
constexpr std::uint64_t DLEXT_ALLOWED_FLAGS = DLEXT_RESERVED_ADDRESS
        | DLEXT_RESERVED_ADDRESS_HINT | DLEXT_WRITE_RELRO | DLEXT_USE_RELRO
        | DLEXT_USE_LIBRARY_FD | DLEXT_USE_LIBRARY_FD_OFFSET | DLEXT_FORCE_LOAD
        | DLEXT_USE_NAMESPACE | DLEXT_RESERVED_ADDRESS_RECURSIVE;
constexpr std::size_t ELF_HEADER_BYTES = 64;

struct LoaderAuditState {
    std::mutex mutex;
    NativeLoaderStatus status;
};

LoaderAuditState& audit_state() {
    static LoaderAuditState state;
    return state;
}

void audit_path() {
    auto& state = audit_state();
    std::lock_guard lock(state.mutex);
    state.status.path_validations++;
}

void audit_fd(bool relro) {
    auto& state = audit_state();
    std::lock_guard lock(state.mutex);
    if (relro) state.status.relro_validations++;
    else state.status.fd_validations++;
}

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

std::pair<unsigned char, std::uint16_t> expected_elf_identity(std::string_view abi) {
    if (abi == "arm64-v8a") return {ELFCLASS64, EM_AARCH64};
    if (abi == "armeabi-v7a") return {ELFCLASS32, EM_ARM};
    if (abi == "x86_64") return {ELFCLASS64, EM_X86_64};
    if (abi == "x86") return {ELFCLASS32, EM_386};
    throw PathPolicyError(ENOEXEC, "ELF_ABI_UNSUPPORTED");
}

void validate_elf_bytes(const std::array<unsigned char, ELF_HEADER_BYTES>& header,
                        std::string_view abi) {
    if (header[EI_MAG0] != ELFMAG0 || header[EI_MAG1] != ELFMAG1
            || header[EI_MAG2] != ELFMAG2 || header[EI_MAG3] != ELFMAG3) {
        throw PathPolicyError(ENOEXEC, "ELF_MAGIC_INVALID");
    }
    if (header[EI_DATA] != ELFDATA2LSB || header[EI_VERSION] != EV_CURRENT) {
        throw PathPolicyError(ENOEXEC, "ELF_ENCODING_UNSUPPORTED");
    }
    const auto expected = expected_elf_identity(abi);
    if (header[EI_CLASS] != expected.first) throw PathPolicyError(ENOEXEC, "ELF_CLASS_MISMATCH");
    std::uint16_t type = 0;
    std::uint16_t machine = 0;
    std::memcpy(&type, header.data() + 16, sizeof(type));
    std::memcpy(&machine, header.data() + 18, sizeof(machine));
    if (type != ET_DYN) throw PathPolicyError(ENOEXEC, "ELF_TYPE_NOT_SHARED_OBJECT");
    if (machine != expected.second) throw PathPolicyError(ENOEXEC, "ELF_MACHINE_MISMATCH");
}

void validate_fd_header(int file_descriptor, std::int64_t offset, bool relro) {
    if (file_descriptor < 0) throw PathPolicyError(EBADF, relro ? "RELRO_FD_INVALID" : "LIBRARY_FD_INVALID");
    if (offset < 0) throw PathPolicyError(EINVAL, "LIBRARY_FD_OFFSET_NEGATIVE");
    struct stat metadata{};
    if (fstat(file_descriptor, &metadata) != 0) throw PathPolicyError(errno, "LIBRARY_FD_STAT_FAILED");
    if (!S_ISREG(metadata.st_mode)) {
        if (relro && (S_ISCHR(metadata.st_mode) || S_ISFIFO(metadata.st_mode))) {
            audit_fd(true);
            return;
        }
        throw PathPolicyError(EACCES, relro ? "RELRO_FD_NOT_REGULAR" : "LIBRARY_FD_NOT_REGULAR");
    }
    if (relro) {
        audit_fd(true);
        return;
    }
    if (offset > metadata.st_size || metadata.st_size - offset < static_cast<off_t>(ELF_HEADER_BYTES)) {
        throw PathPolicyError(ENOEXEC, "ELF_HEADER_TRUNCATED");
    }
    std::array<unsigned char, ELF_HEADER_BYTES> header{};
    const ssize_t count = pread(file_descriptor, header.data(), header.size(), static_cast<off_t>(offset));
    if (count != static_cast<ssize_t>(header.size())) throw PathPolicyError(errno == 0 ? EIO : errno, "ELF_HEADER_READ_FAILED");
    validate_elf_bytes(header, global_policy().snapshot().abi_name);
    audit_fd(false);
}

void validate_reserved_address(std::uint64_t flags, const void* address, std::size_t size) {
    const bool strict = (flags & DLEXT_RESERVED_ADDRESS) != 0;
    const bool hint = (flags & DLEXT_RESERVED_ADDRESS_HINT) != 0;
    const bool recursive = (flags & DLEXT_RESERVED_ADDRESS_RECURSIVE) != 0;
    if (strict && hint) throw PathPolicyError(EINVAL, "DLEXT_RESERVED_ADDRESS_FLAGS_CONFLICT");
    if (recursive && !strict) throw PathPolicyError(EINVAL, "DLEXT_RECURSIVE_REQUIRES_RESERVED_ADDRESS");
    if (!strict && !hint) return;
    if (address == nullptr || size == 0) throw PathPolicyError(EINVAL, "DLEXT_RESERVED_ADDRESS_MISSING");
    const auto raw = reinterpret_cast<std::uintptr_t>(address);
    const long page = sysconf(_SC_PAGESIZE);
    const std::size_t page_size = page > 0 ? static_cast<std::size_t>(page) : 4096U;
    if ((raw % page_size) != 0 || (size % page_size) != 0) {
        throw PathPolicyError(EINVAL, "DLEXT_RESERVED_ADDRESS_UNALIGNED");
    }
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

void NativeLibraryLoaderPolicy::validate_library(const NativeLibraryDecision& decision) {
    if (!decision.guest_library) return;
    const NativePolicySnapshot policy = global_policy().snapshot();
    if (!policy.configured || policy.revision != decision.policy_revision) {
        throw PathPolicyError(EAGAIN, "LOADER_POLICY_REVISION_CHANGED");
    }
    const int descriptor = open(decision.resolved_name.c_str(), O_RDONLY | O_CLOEXEC);
    if (descriptor < 0) throw PathPolicyError(errno, "LIBRARY_OPEN_FAILED");
    try {
        validate_fd_header(descriptor, 0, false);
        close(descriptor);
        audit_path();
    } catch (...) {
        close(descriptor);
        throw;
    }
}

void NativeLibraryLoaderPolicy::validate_library_fd(int file_descriptor, std::int64_t offset) {
    validate_fd_header(file_descriptor, offset, false);
}

void NativeLibraryLoaderPolicy::validate_android_dlext(
        std::uint64_t flags, int library_fd, std::int64_t library_fd_offset, int relro_fd,
        const void* reserved_address, std::size_t reserved_size, bool namespace_supplied) {
    if ((flags & ~DLEXT_ALLOWED_FLAGS) != 0) throw PathPolicyError(EINVAL, "DLEXT_UNKNOWN_FLAGS");
    if (namespace_supplied || (flags & DLEXT_USE_NAMESPACE) != 0) {
        throw PathPolicyError(EACCES, "FOREIGN_LINKER_NAMESPACE_DENIED");
    }
    validate_reserved_address(flags, reserved_address, reserved_size);
    const bool library_source = (flags & DLEXT_USE_LIBRARY_FD) != 0;
    const bool offset_source = (flags & DLEXT_USE_LIBRARY_FD_OFFSET) != 0;
    if (offset_source && !library_source) throw PathPolicyError(EINVAL, "DLEXT_OFFSET_REQUIRES_LIBRARY_FD");
    if (library_source) validate_fd_header(library_fd, offset_source ? library_fd_offset : 0, false);
    const bool write_relro = (flags & DLEXT_WRITE_RELRO) != 0;
    const bool use_relro = (flags & DLEXT_USE_RELRO) != 0;
    if (write_relro && use_relro) throw PathPolicyError(EINVAL, "DLEXT_RELRO_FLAGS_CONFLICT");
    if (write_relro || use_relro) validate_fd_header(relro_fd, 0, true);
}

void NativeLibraryLoaderPolicy::record_denial(std::string reason) noexcept {
    auto& state = audit_state();
    std::lock_guard lock(state.mutex);
    state.status.denied_requests++;
    state.status.last_error = std::move(reason);
}

NativeLoaderStatus NativeLibraryLoaderPolicy::status() {
    auto& state = audit_state();
    std::lock_guard lock(state.mutex);
    return state.status;
}

void NativeLibraryLoaderPolicy::reset_status() noexcept {
    auto& state = audit_state();
    std::lock_guard lock(state.mutex);
    state.status = {};
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
