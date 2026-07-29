#include "controlled_sandbox/native_loader.h"
#include "controlled_sandbox/native_policy.h"

#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <elf.h>
#include <filesystem>
#include <fstream>
#include <fcntl.h>
#include <iostream>
#include <stdexcept>
#include <string>
#include <unistd.h>

namespace {

void require(bool condition, const std::string& label) {
    if (!condition) throw std::runtime_error("Failed: " + label);
}

template <typename Callback>
bool policy_error(Callback&& callback, int expected) {
    try {
        callback();
        return false;
    } catch (const controlled_sandbox::PathPolicyError& error) {
        return error.error_number() == expected;
    }
}

void write_fake_elf(const std::filesystem::path& path, unsigned char elf_class,
                    std::uint16_t machine, std::size_t prefix = 0) {
    std::array<unsigned char, 64> header{};
    header[EI_MAG0] = ELFMAG0;
    header[EI_MAG1] = ELFMAG1;
    header[EI_MAG2] = ELFMAG2;
    header[EI_MAG3] = ELFMAG3;
    header[EI_CLASS] = elf_class;
    header[EI_DATA] = ELFDATA2LSB;
    header[EI_VERSION] = EV_CURRENT;
    const std::uint16_t type = ET_DYN;
    std::memcpy(header.data() + 16, &type, sizeof(type));
    std::memcpy(header.data() + 18, &machine, sizeof(machine));
    std::ofstream output(path, std::ios::binary);
    std::string padding(prefix, '\0');
    output.write(padding.data(), static_cast<std::streamsize>(padding.size()));
    output.write(reinterpret_cast<const char*>(header.data()), static_cast<std::streamsize>(header.size()));
}

}  // namespace

int main() {
    char pattern[] = "/tmp/controlled-sandbox-loader-XXXXXX";
    char* created = mkdtemp(pattern);
    require(created != nullptr, "mkdtemp");
    const std::filesystem::path root(created);
    const auto instance = root / "instance";
    const auto library = root / "lib" / "x86_64";
    const auto apk = root / "base.apk";
    std::filesystem::create_directories(instance / "data");
    std::filesystem::create_directories(library);
    std::ofstream(apk) << "apk";
    write_fake_elf(library / "libguest.so", ELFCLASS64, EM_X86_64);
    write_fake_elf(library / "libwrong.so", ELFCLASS32, EM_386);
    write_fake_elf(root / "archive.bin", ELFCLASS64, EM_X86_64, 4096);
    std::ofstream(root / "relro.bin", std::ios::binary) << std::string(4096, '\0');

    auto& policy = controlled_sandbox::global_policy();
    policy.configure("loader-session", 1, "com.example.guest", "com.example.guest",
            0, 10000, 20000, "x86_64", instance.string(), apk.string(), library.string(),
            true, {}, {}, {}, {});
    controlled_sandbox::NativeLibraryLoaderPolicy::reset_status();

    auto guest = controlled_sandbox::NativeLibraryLoaderPolicy::resolve("libguest.so");
    require(guest.guest_library && !guest.system_library, "guest soname classification");
    require(guest.resolved_name == (library / "libguest.so").string(), "guest soname resolution");
    controlled_sandbox::NativeLibraryLoaderPolicy::validate_library(guest);
    auto alias = controlled_sandbox::NativeLibraryLoaderPolicy::resolve(
            "/data/app/com.example.guest/lib/x86_64/libguest.so");
    require(alias.guest_library && alias.resolved_name == guest.resolved_name, "guest absolute alias");
    auto system = controlled_sandbox::NativeLibraryLoaderPolicy::resolve("libc.so");
    require(system.system_library && system.resolved_name == "libc.so", "system soname allowlist");
    controlled_sandbox::NativeLibraryLoaderPolicy::validate_library(system);
    require(controlled_sandbox::NativeLibraryLoaderPolicy::is_allowed_system_path(
            "/apex/com.android.runtime/lib64/bionic/libc.so"), "system path allowlist");

    auto wrong = controlled_sandbox::NativeLibraryLoaderPolicy::resolve("libwrong.so");
    require(policy_error([&] { controlled_sandbox::NativeLibraryLoaderPolicy::validate_library(wrong); },
                         ENOEXEC), "ELF ABI mismatch denied");

    const int archive_fd = open((root / "archive.bin").c_str(), O_RDONLY | O_CLOEXEC);
    const int relro_fd = open((root / "relro.bin").c_str(), O_RDONLY | O_CLOEXEC);
    require(archive_fd >= 0 && relro_fd >= 0, "open loader fixtures");
    controlled_sandbox::NativeLibraryLoaderPolicy::validate_library_fd(archive_fd, 4096);
    controlled_sandbox::NativeLibraryLoaderPolicy::validate_android_dlext(
            0x10ULL | 0x20ULL, archive_fd, 4096, -1, nullptr, 0, false);
    controlled_sandbox::NativeLibraryLoaderPolicy::validate_android_dlext(
            0x8ULL, -1, 0, relro_fd, nullptr, 0, false);
    require(policy_error([&] { controlled_sandbox::NativeLibraryLoaderPolicy::validate_library_fd(
            archive_fd, 8192); }, ENOEXEC), "truncated fd ELF denied");
    require(policy_error([&] { controlled_sandbox::NativeLibraryLoaderPolicy::validate_android_dlext(
            0x20ULL, archive_fd, 4096, -1, nullptr, 0, false); }, EINVAL),
            "fd offset without fd flag denied");
    require(policy_error([&] { controlled_sandbox::NativeLibraryLoaderPolicy::validate_android_dlext(
            0x8ULL | 0x4ULL, -1, 0, relro_fd, nullptr, 0, false); }, EINVAL),
            "RELRO conflict denied");
    require(policy_error([&] { controlled_sandbox::NativeLibraryLoaderPolicy::validate_android_dlext(
            0x80000000ULL, -1, 0, -1, nullptr, 0, false); }, EINVAL),
            "unknown extension flags denied");
    require(policy_error([&] { controlled_sandbox::NativeLibraryLoaderPolicy::validate_android_dlext(
            0x200ULL, -1, 0, -1, nullptr, 0, true); }, EACCES),
            "foreign namespace denied");
    close(archive_fd);
    close(relro_fd);

    require(policy_error([] { (void) controlled_sandbox::NativeLibraryLoaderPolicy::resolve(nullptr); },
                         EACCES), "main program denied");
    require(policy_error([] { (void) controlled_sandbox::NativeLibraryLoaderPolicy::resolve("libunknown.so"); },
                         ENOENT), "unknown soname denied");
    require(policy_error([] { (void) controlled_sandbox::NativeLibraryLoaderPolicy::resolve("../libguest.so"); },
                         EACCES), "relative traversal denied");
    require(policy_error([] { (void) controlled_sandbox::NativeLibraryLoaderPolicy::resolve(
            "/data/user/0/com.host.app/lib/libhost.so"); }, EACCES), "host private library denied");

    const auto status = controlled_sandbox::NativeLibraryLoaderPolicy::status();
    require(status.path_validations >= 1, "path validation audit");
    require(status.fd_validations >= 2, "fd validation audit");
    require(status.relro_validations >= 1, "RELRO validation audit");

    policy.reset();
    std::filesystem::remove_all(root);
    std::cout << "PASS sandbox-native dynamic loader ELF/RELRO policy self-test\n";
    return EXIT_SUCCESS;
}
