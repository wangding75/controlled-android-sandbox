#include "controlled_sandbox/native_loader.h"
#include "controlled_sandbox/native_policy.h"

#include <cerrno>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>

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
    std::ofstream(library / "libguest.so") << "guest";

    auto& policy = controlled_sandbox::global_policy();
    policy.configure("loader-session", 1, "com.example.guest", "com.example.guest",
            0, 10000, 20000, "x86_64", instance.string(), apk.string(), library.string(),
            true, {}, {}, {}, {});

    auto guest = controlled_sandbox::NativeLibraryLoaderPolicy::resolve("libguest.so");
    require(guest.guest_library && !guest.system_library, "guest soname classification");
    require(guest.resolved_name == (library / "libguest.so").string(), "guest soname resolution");
    auto alias = controlled_sandbox::NativeLibraryLoaderPolicy::resolve(
            "/data/app/com.example.guest/lib/x86_64/libguest.so");
    require(alias.guest_library && alias.resolved_name == guest.resolved_name, "guest absolute alias");
    auto system = controlled_sandbox::NativeLibraryLoaderPolicy::resolve("libc.so");
    require(system.system_library && system.resolved_name == "libc.so", "system soname allowlist");
    require(controlled_sandbox::NativeLibraryLoaderPolicy::is_allowed_system_path(
            "/apex/com.android.runtime/lib64/bionic/libc.so"), "system path allowlist");
    require(policy_error([] { (void) controlled_sandbox::NativeLibraryLoaderPolicy::resolve(nullptr); },
                         EACCES), "main program denied");
    require(policy_error([] { (void) controlled_sandbox::NativeLibraryLoaderPolicy::resolve("libunknown.so"); },
                         ENOENT), "unknown soname denied");
    require(policy_error([] { (void) controlled_sandbox::NativeLibraryLoaderPolicy::resolve("../libguest.so"); },
                         EACCES), "relative traversal denied");
    require(policy_error([] { (void) controlled_sandbox::NativeLibraryLoaderPolicy::resolve(
            "/data/user/0/com.host.app/lib/libhost.so"); }, EACCES), "host private library denied");

    policy.reset();
    std::filesystem::remove_all(root);
    std::cout << "PASS sandbox-native dynamic loader policy self-test\n";
    return EXIT_SUCCESS;
}
